import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Drag Manager (_DragDispatch $ABED, selector in D0) for drags within a program: drag references with items and
 * flavors, tracking and receive handlers per window, and TrackDrag, which follows the mouse on the emulation thread
 * (a translucent host outline of the drag region moves along), sends the tracking messages to the handlers of the
 * window under the mouse and hands the drop to that window's receive handlers. Drag hiliting draws a two-pixel frame
 * inside a region and restores the pixels under it afterwards. Files dragged from the host's Finder or Explorer arrive as a
 * drag from the Finder ({@link #external}); drags to other programs are not attempted.
 */
final class DragManager68k {
	private static final int NO_ERR=0,PARAM_ERR=-50,USER_CANCELED=-128,BAD_DRAG_REF=-1850,BAD_DRAG_ITEM=-1851,BAD_DRAG_FLAVOR=-1852,DUPLICATE_FLAVOR=-1853,CANT_GET_FLAVOR=-1854,DUPLICATE_HANDLER=-1855,HANDLER_NOT_FOUND=-1856,DRAG_NOT_ACCEPTED=-1857;
	private static final int ENTER_HANDLER=1,ENTER_WINDOW=2,IN_WINDOW=3,LEAVE_WINDOW=4,LEAVE_HANDLER=5;
	private final Toolbox68k tb;private final M68kMemory memory;

	private static final class Flavor {int type,flags;byte[] data;}
	private static final class Item {final LinkedHashMap<Integer,Flavor> flavors=new LinkedHashMap<Integer,Flavor>();int[] bounds=new int[4];}
	private static final class Drag {
		final int reference;final LinkedHashMap<Integer,Item> items=new LinkedHashMap<Integer,Item>();
		int sendProc,sendRefCon,inputProc,drawingProc;int attributes,mouseH,mouseV,originH,originV,modifiers,mouseDownModifiers,mouseUpModifiers;
		int[] dropLocation=new int[]{0x6e756c6c,0};int senderWindow;
		/** ShowDragHilite state: the region, and the pixels under the frame band to restore. */
		int hiliteWindow;QdRegion hiliteRegion,pendingRegion;boolean hiliteInside;int[][] savedPixels;int scrollH,scrollV;
		Drag(int reference){this.reference=reference;}
	}
	private static final class Handler {final int proc,window,refCon;Handler(int proc,int window,int refCon){this.proc=proc;this.window=window;this.refCon=refCon;}}
	private final Map<Integer,Drag> drags=new LinkedHashMap<Integer,Drag>();
	private final List<Handler> trackingHandlers=new ArrayList<Handler>(),receiveHandlers=new ArrayList<Handler>();
	private int nextReference=0x4452,nextItem=1;

	DragManager68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff,sp=cpu.a[7];int args;int result=NO_ERR;
		switch(selector){
			case 1:case 2:{int refCon=memory.readLong(sp),window=memory.readLong(sp+4),proc=memory.readLong(sp+8);args=12; // InstallTrackingHandler / InstallReceiveHandler(proc, window, refCon)
				List<Handler> list=selector==1?trackingHandlers:receiveHandlers;boolean dup=false;for(Handler h:list)if(h.proc==proc&&h.window==window)dup=true;
				if(proc==0)result=PARAM_ERR;else if(dup)result=DUPLICATE_HANDLER;else list.add(new Handler(proc,window,refCon));break;}
			case 3:case 4:{int window=memory.readLong(sp),proc=memory.readLong(sp+4);args=8; // RemoveTrackingHandler / RemoveReceiveHandler(proc, window)
				List<Handler> list=selector==3?trackingHandlers:receiveHandlers;boolean removed=list.removeIf(h->h.proc==proc&&h.window==window);if(!removed)result=HANDLER_NOT_FOUND;break;}
			case 5:{int refPtr=memory.readLong(sp);args=4;Drag d=new Drag(nextReference++);drags.put(d.reference,d);if(refPtr!=0)memory.writeLong(refPtr,d.reference);break;} // NewDrag
			case 6:{args=4;Drag d=drags.remove(memory.readLong(sp));if(d==null)result=BAD_DRAG_REF;break;}                     // DisposeDrag
			case 7:{int flags=memory.readLong(sp),size=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),type=memory.readLong(sp+12),itemRef=memory.readLong(sp+16);Drag d=drags.get(memory.readLong(sp+20));args=24; // AddDragItemFlavor
				if(d==null){result=BAD_DRAG_REF;break;}Item item=d.items.get(itemRef);if(item==null){item=new Item();d.items.put(itemRef,item);}
				if(item.flavors.containsKey(type)){result=DUPLICATE_FLAVOR;break;}
				Flavor f=new Flavor();f.type=type;f.flags=flags;f.data=dataPtr==0?null:memory.readBytes(dataPtr,Math.max(0,size));item.flavors.put(type,f);break;}
			case 9:{int offset=memory.readLong(sp),size=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),type=memory.readLong(sp+12),itemRef=memory.readLong(sp+16);Drag d=drags.get(memory.readLong(sp+20));args=24; // SetDragItemFlavorData
				Flavor f=flavor(d,itemRef,type);if(d==null)result=BAD_DRAG_REF;else if(f==null)result=BAD_DRAG_FLAVOR;
				else{byte[] chunk=dataPtr==0?new byte[0]:memory.readBytes(dataPtr,Math.max(0,size));byte[] old=f.data==null?new byte[0]:f.data;if(offset<0)offset=0;
					byte[] merged=new byte[Math.max(old.length,offset+chunk.length)];System.arraycopy(old,0,merged,0,old.length);System.arraycopy(chunk,0,merged,offset,chunk.length);f.data=merged;}break;}
			case 10:case 11:case 12:{int refCon=memory.readLong(sp),proc=memory.readLong(sp+4);Drag d=drags.get(memory.readLong(sp+8));args=12; // SetDragSendProc / SetDragInputProc / SetDragDrawingProc
				if(d==null)result=BAD_DRAG_REF;else if(selector==10){d.sendProc=proc;d.sendRefCon=refCon;}else if(selector==11)d.inputProc=proc;else d.drawingProc=proc;break;}
			case 39:args=18;break;                                                                                             // SetDragImage: the outline stands in for the image
			case 40:args=12;break;                                                                                             // ChangeDragBehaviors
			case 13:{int region=memory.readLong(sp),event=memory.readLong(sp+4);Drag d=drags.get(memory.readLong(sp+8));args=12;result=d==null?BAD_DRAG_REF:track(cpu,d,event,region);break;} // TrackDrag
			case 14:{int countPtr=memory.readLong(sp);Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;else if(countPtr!=0)memory.writeWord(countPtr,d.items.size());break;} // CountDragItems
			case 15:{int refPtr=memory.readLong(sp),index=memory.readWord(sp+4);Drag d=drags.get(memory.readLong(sp+6));args=10; // GetDragItemReferenceNumber(theDrag, index, VAR itemRef)
				if(d==null){result=BAD_DRAG_REF;break;}int i=1,found=0;for(int ref:d.items.keySet()){if(i++==index){found=ref;break;}}if(found==0)result=BAD_DRAG_ITEM;else if(refPtr!=0)memory.writeLong(refPtr,found);break;}
			case 16:{int countPtr=memory.readLong(sp),itemRef=memory.readLong(sp+4);Drag d=drags.get(memory.readLong(sp+8));args=12;Item item=d==null?null:d.items.get(itemRef); // CountDragItemFlavors
				if(d==null)result=BAD_DRAG_REF;else if(item==null)result=BAD_DRAG_ITEM;else if(countPtr!=0)memory.writeWord(countPtr,item.flavors.size());break;}
			case 17:{int typePtr=memory.readLong(sp),index=memory.readWord(sp+4),itemRef=memory.readLong(sp+6);Drag d=drags.get(memory.readLong(sp+10));args=14;Item item=d==null?null:d.items.get(itemRef); // GetFlavorType(theDrag, itemRef, index, VAR type)
				if(d==null){result=BAD_DRAG_REF;break;}if(item==null){result=BAD_DRAG_ITEM;break;}int i=1,found=0;for(int t:item.flavors.keySet()){if(i++==index){found=t;break;}}if(found==0)result=BAD_DRAG_FLAVOR;else if(typePtr!=0)memory.writeLong(typePtr,found);break;}
			case 18:{int flagsPtr=memory.readLong(sp),type=memory.readLong(sp+4),itemRef=memory.readLong(sp+8);Drag d=drags.get(memory.readLong(sp+12));args=16;Flavor f=flavor(d,itemRef,type); // GetFlavorFlags
				if(d==null)result=BAD_DRAG_REF;else if(f==null)result=BAD_DRAG_FLAVOR;else if(flagsPtr!=0)memory.writeLong(flagsPtr,f.flags);break;}
			case 19:{int sizePtr=memory.readLong(sp),type=memory.readLong(sp+4),itemRef=memory.readLong(sp+8);Drag d=drags.get(memory.readLong(sp+12));args=16;Flavor f=flavor(d,itemRef,type); // GetFlavorDataSize
				if(d==null)result=BAD_DRAG_REF;else if(f==null)result=BAD_DRAG_FLAVOR;else{byte[] data=flavorData(cpu,d,itemRef,f);if(data==null)result=CANT_GET_FLAVOR;else if(sizePtr!=0)memory.writeLong(sizePtr,data.length);}break;}
			case 20:{int offset=memory.readLong(sp),sizePtr=memory.readLong(sp+4),dataPtr=memory.readLong(sp+8),type=memory.readLong(sp+12),itemRef=memory.readLong(sp+16);Drag d=drags.get(memory.readLong(sp+20));args=24;Flavor f=flavor(d,itemRef,type); // GetFlavorData
				if(d==null)result=BAD_DRAG_REF;else if(f==null)result=BAD_DRAG_FLAVOR;else{byte[] data=flavorData(cpu,d,itemRef,f);if(data==null)result=CANT_GET_FLAVOR;
					else{int room=sizePtr==0?0:memory.readLong(sizePtr);int available=Math.max(0,data.length-Math.max(0,offset)),n=Math.min(room,available);if(dataPtr!=0&&n>0)memory.load(dataPtr,java.util.Arrays.copyOfRange(data,offset,offset+n));if(sizePtr!=0)memory.writeLong(sizePtr,n);}}break;}
			case 21:case 22:{int rect=memory.readLong(sp),itemRef=memory.readLong(sp+4);Drag d=drags.get(memory.readLong(sp+8));args=12;Item item=d==null?null:d.items.get(itemRef); // Get/SetDragItemBounds
				if(d==null)result=BAD_DRAG_REF;else if(item==null)result=BAD_DRAG_ITEM;else if(rect!=0){if(selector==21)for(int i=0;i<4;i++)memory.writeWord(rect+i*2,item.bounds[i]);else for(int i=0;i<4;i++)item.bounds[i]=(short)memory.readWord(rect+i*2);}break;}
			case 23:case 24:{int desc=memory.readLong(sp);Drag d=drags.get(memory.readLong(sp+4));args=8; // Get/SetDropLocation(theDrag, AEDesc*)
				if(d==null)result=BAD_DRAG_REF;else if(desc!=0){if(selector==23){memory.writeLong(desc,d.dropLocation[0]);memory.writeLong(desc+4,d.dropLocation[1]);}else{d.dropLocation=new int[]{memory.readLong(desc),memory.readLong(desc+4)};}}break;}
			case 25:{int p=memory.readLong(sp);Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;else if(p!=0)memory.writeLong(p,d.attributes);break;} // GetDragAttributes
			case 26:{int pinnedPtr=memory.readLong(sp),mousePtr=memory.readLong(sp+4);Drag d=drags.get(memory.readLong(sp+8));args=12; // GetDragMouse(theDrag, VAR mouse, VAR pinnedMouse)
				if(d==null)result=BAD_DRAG_REF;else{if(mousePtr!=0)memory.writeLong(mousePtr,d.mouseV<<16|d.mouseH&0xffff);if(pinnedPtr!=0)memory.writeLong(pinnedPtr,d.mouseV<<16|d.mouseH&0xffff);}break;}
			case 27:{Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;break;}                     // SetDragMouse: the pinned mouse is not shown apart
			case 28:{int p=memory.readLong(sp);Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;else if(p!=0)memory.writeLong(p,d.originV<<16|d.originH&0xffff);break;} // GetDragOrigin
			case 29:{int upPtr=memory.readLong(sp),downPtr=memory.readLong(sp+4),modPtr=memory.readLong(sp+8);Drag d=drags.get(memory.readLong(sp+12));args=16; // GetDragModifiers
				if(d==null)result=BAD_DRAG_REF;else{if(modPtr!=0)memory.writeWord(modPtr,d.modifiers);if(downPtr!=0)memory.writeWord(downPtr,d.mouseDownModifiers);if(upPtr!=0)memory.writeWord(upPtr,d.mouseUpModifiers);}break;}
			case 30:{boolean inside=memory.readByte(sp)!=0;int region=memory.readLong(sp+2);Drag d=drags.get(memory.readLong(sp+6));args=10;if(d==null)result=BAD_DRAG_REF;else result=showHilite(d,region,inside);break;} // ShowDragHilite
			case 31:{Drag d=drags.get(memory.readLong(sp));args=4;if(d==null)result=BAD_DRAG_REF;else hideHilite(d);break;}       // HideDragHilite
			case 32:{int dv=(short)memory.readWord(sp),dh=(short)memory.readWord(sp+2);Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;else{d.scrollH=dh;d.scrollV=dv;if(d.hiliteRegion!=null){QdRegion r=d.hiliteRegion;boolean inside=d.hiliteInside;hideHilite(d);d.pendingRegion=r.offset(dh,dv);d.hiliteInside=inside;}}break;} // DragPreScroll
			case 33:{Drag d=drags.get(memory.readLong(sp));args=4;if(d==null)result=BAD_DRAG_REF;else if(d.pendingRegion!=null){QdRegion r=d.pendingRegion;d.pendingRegion=null;result=showHilite(d,r,d.hiliteInside);}break;} // DragPostScroll
			case 34:{Drag d=drags.get(memory.readLong(sp+4));args=8;if(d==null)result=BAD_DRAG_REF;else if(d.hiliteRegion!=null){QdRegion r=d.hiliteRegion;boolean inside=d.hiliteInside;hideHilite(d);result=showHilite(d,r,inside);}break;} // UpdateDragHilite
			case 38:{int color=memory.readLong(sp);args=8;if(color!=0){memory.writeWord(color,0x4444);memory.writeWord(color+2,0x4444);memory.writeWord(color+4,0x4444);}break;} // GetDragHiliteColor
			case 35:{int start=memory.readLong(sp);cpu.a[7]+=4;tb.writeBoolean(cpu.a[7],waitMouseMoved((short)(start&0xffff),(short)(start>>16)));return true;} // WaitMouseMoved(initialMouse): Boolean
			case 36:args=12;break;                                                                                              // ZoomRects: no animation
			case 37:args=12;break;                                                                                              // ZoomRegion
			default:cpu.a[7]+=0;memory.writeWord(cpu.a[7],PARAM_ERR);if(Mac68kApi.TRACE)System.err.println("[68K] Drag Manager selector "+selector+" unknown");return true;
		}
		cpu.a[7]+=args;memory.writeWord(cpu.a[7],result);return true;
	}

	private static Flavor flavor(Drag d,int itemRef,int type){if(d==null)return null;Item item=d.items.get(itemRef);return item==null?null:item.flavors.get(type);}
	/** The flavor's data, asking the drag's send procedure (pascal OSErr proc(theType, refCon, itemRef, theDrag)) for data promised without it. */
	private byte[] flavorData(M68kCpu cpu,Drag d,int itemRef,Flavor f) throws M68kException {
		if(f.data!=null||d.sendProc==0)return f.data;
		int saved=cpu.a[7];int[] regs=cpu.d.clone(),addr=cpu.a.clone();
		try{cpu.a[7]-=2;cpu.pushLong(f.type);cpu.pushLong(d.sendRefCon);cpu.pushLong(itemRef);cpu.pushLong(d.reference);cpu.callPascal(d.sendProc,16);
			int err=(short)memory.readWord(cpu.a[7]);if(err!=0)return null;}
		finally{cpu.a[7]=saved;System.arraycopy(regs,0,cpu.d,0,8);System.arraycopy(addr,0,cpu.a,0,8);}
		return f.data;
	}

	// ------------------------------------------------------------------ tracking

	/** WaitMouseMoved: true once the mouse leaves a 4-pixel square around the start while the button stays down. */
	private boolean waitMouseMoved(int startH,int startV){
		MacAppHost host=tb.host();long deadline=System.currentTimeMillis()+60_000;
		while(host.isMouseDown()&&System.currentTimeMillis()<deadline){java.awt.Point p=host.mouseLocation();if(Math.abs(p.x-startH)>3||Math.abs(p.y-startV)>3)return true;try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
		return false;
	}
	/**
	 * TrackDrag(theDrag, theEvent, theRegion): follows the mouse until the button goes up, keeping the handlers of the
	 * window under it informed (enter/in/leave), then offers the drop to that window's receive handlers.
	 */
	private int track(M68kCpu cpu,Drag d,int event,int region) throws M68kException {
		MacAppHost host=tb.host();
		d.originH=(short)memory.readWord(event+12);d.originV=(short)memory.readWord(event+10);d.mouseDownModifiers=memory.readWord(event+14);d.modifiers=d.mouseDownModifiers;
		d.senderWindow=tb.windows.windowAt(d.originH,d.originV);d.mouseH=d.originH;d.mouseV=d.originV;
		int[] bounds=region==0?null:regionBounds(region);
		host.showDragOutline(bounds==null?null:new int[]{bounds[1],bounds[0],bounds[3]-bounds[1],bounds[2]-bounds[0]});
		int currentWindow=0;List<Handler> currentHandlers=new ArrayList<Handler>();int result=NO_ERR;
		try{
			long deadline=System.currentTimeMillis()+120_000;boolean first=true;
			while(host.isMouseDown()&&System.currentTimeMillis()<deadline){
				java.awt.Point p=host.mouseLocation();follow(d,p);d.modifiers=host.modifiers();
				int under=tb.windows.windowAt(p.x,p.y);
				d.attributes=(d.senderWindow!=0&&under==d.senderWindow?4:0)|2|(under!=d.senderWindow?1:0); // kDragInsideSenderWindow, kDragInsideSenderApplication, kDragHasLeftSenderWindow
				if(under!=currentWindow||first){
					if(currentWindow!=0)for(Handler h:currentHandlers)call(cpu,h,LEAVE_WINDOW,currentWindow,d);
					List<Handler> next=handlersFor(trackingHandlers,under);
					for(Handler h:currentHandlers)if(!next.contains(h))call(cpu,h,LEAVE_HANDLER,currentWindow,d);
					for(Handler h:next)if(!currentHandlers.contains(h))call(cpu,h,ENTER_HANDLER,under,d);
					if(under!=0)for(Handler h:next)call(cpu,h,ENTER_WINDOW,under,d);
					currentHandlers=next;currentWindow=under;first=false;
				}
				if(currentWindow!=0)for(Handler h:currentHandlers)call(cpu,h,IN_WINDOW,currentWindow,d);
				if(bounds!=null)host.moveDragOutline(bounds[1]+p.x-d.originH,bounds[0]+p.y-d.originV);
				try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
			}
			d.mouseUpModifiers=host.modifiers();java.awt.Point p=host.mouseLocation();follow(d,p);
			host.hideDragOutline();
			if(currentWindow!=0){
				List<Handler> receivers=handlersFor(receiveHandlers,currentWindow);
				if(receivers.isEmpty())result=DRAG_NOT_ACCEPTED;
				else{result=DRAG_NOT_ACCEPTED;for(Handler h:receivers){int r=callReceive(cpu,h,currentWindow,d);if(r==NO_ERR)result=NO_ERR;else if(result!=NO_ERR)result=r;}}
			}else result=DRAG_NOT_ACCEPTED;
			if(currentWindow!=0)for(Handler h:currentHandlers)call(cpu,h,LEAVE_WINDOW,currentWindow,d);
			for(Handler h:currentHandlers)call(cpu,h,LEAVE_HANDLER,currentWindow,d);
		}finally{host.hideDragOutline();hideHilite(d);}
		if(Mac68kApi.TRACE)System.err.println("[68K] TrackDrag: dropped on $"+Integer.toHexString(currentWindow)+" -> "+result);
		return result;
	}
	/** The drag moved to p: the mouse, and every item's bounds by the same distance - the Drag Manager moves the item bounds
	 *  along with the drag, and receive handlers place what was dropped from GetDragItemBounds (REALbasic's control palette). */
	private static void follow(Drag d,java.awt.Point p){
		int dh=p.x-d.mouseH,dv=p.y-d.mouseV;d.mouseH=p.x;d.mouseV=p.y;if(dh==0&&dv==0)return;
		for(Item item:d.items.values()){item.bounds[0]+=dv;item.bounds[1]+=dh;item.bounds[2]+=dv;item.bounds[3]+=dh;}
	}
	private static List<Handler> handlersFor(List<Handler> all,int window){List<Handler> r=new ArrayList<Handler>();if(window==0)return r;for(Handler h:all)if(h.window==0||h.window==window)r.add(h);return r;}
	/** pascal OSErr trackingHandler(message, theWindow, handlerRefCon, theDrag). */
	private int call(M68kCpu cpu,Handler h,int message,int window,Drag d) throws M68kException {
		int saved=cpu.a[7];int[] regs=cpu.d.clone(),addr=cpu.a.clone();
		try{cpu.a[7]-=2;cpu.pushWord(message);cpu.pushLong(window);cpu.pushLong(h.refCon);cpu.pushLong(d.reference);cpu.callPascal(h.proc,14);return (short)memory.readWord(cpu.a[7]);}
		finally{cpu.a[7]=saved;System.arraycopy(regs,0,cpu.d,0,8);System.arraycopy(addr,0,cpu.a,0,8);}
	}
	/** pascal OSErr receiveHandler(theWindow, handlerRefCon, theDrag). */
	private int callReceive(M68kCpu cpu,Handler h,int window,Drag d) throws M68kException {
		int saved=cpu.a[7];int[] regs=cpu.d.clone(),addr=cpu.a.clone();
		try{cpu.a[7]-=2;cpu.pushLong(window);cpu.pushLong(h.refCon);cpu.pushLong(d.reference);cpu.callPascal(h.proc,12);return (short)memory.readWord(cpu.a[7]);}
		finally{cpu.a[7]=saved;System.arraycopy(regs,0,cpu.d,0,8);System.arraycopy(addr,0,cpu.a,0,8);}
	}
	// ------------------------------------------------------------------ drags from the host (Finder / Explorer)

	static final int EXTERNAL_MOVE=0,EXTERNAL_EXIT=1,EXTERNAL_DROP=2;
	private static final int HFS_FLAVOR=0x68667320; // 'hfs '
	/** The drag of files the host's file manager is dragging over the program's windows, or null. */
	private Drag external;private int externalWindow;private List<Handler> externalHandlers=new ArrayList<Handler>();private boolean externalFilesKnown;

	/**
	 * A drag of host files (the macOS Finder, Windows Explorer) over or onto a window of the program, as a drag from the Finder
	 * looks on a Mac: one item per file with an 'hfs ' flavor (HFSFlavor: type, creator, fdFlags, FSSpec), attributes without
	 * kDragInsideSenderApplication. Moves send the tracking messages to the handlers of the window under the point; the drop
	 * goes to that window's receive handlers. {@code files} is null while the host does not tell them during the drag (the
	 * items then have the flavor without data). Returns true when the drop was taken by a receive handler; false for moves,
	 * exits and drops nobody took (the caller opens the files as documents instead).
	 */
	boolean external(M68kCpu cpu,int phase,int h,int v,List<java.io.File> files,int modifiers) throws M68kException {
		if(phase==EXTERNAL_EXIT){endExternal(cpu);return false;}
		if(external==null){
			Drag d=new Drag(nextReference++);drags.put(d.reference,d);external=d;externalWindow=0;externalHandlers=new ArrayList<Handler>();externalFilesKnown=false;
			d.originH=h;d.originV=v;d.mouseH=h;d.mouseV=v;d.mouseDownModifiers=modifiers&~0x80;d.attributes=1; // kDragHasLeftSenderWindow: from another application
			if(Mac68kApi.TRACE)System.err.println("[68K] host drag enters at "+h+","+v+(files==null?" (files not known yet)":" "+files));
		}
		Drag d=external;
		if(files!=null&&!externalFilesKnown){fillExternalItems(d,files,h,v);externalFilesKnown=true;}
		else if(d.items.isEmpty())fillExternalItems(d,null,h,v);
		follow(d,new java.awt.Point(h,v));d.modifiers=modifiers;
		int under=tb.windows.windowAt(h,v);
		if(under!=externalWindow){
			if(externalWindow!=0)for(Handler hd:externalHandlers)call(cpu,hd,LEAVE_WINDOW,externalWindow,d);
			List<Handler> next=handlersFor(trackingHandlers,under);
			for(Handler hd:externalHandlers)if(!next.contains(hd))call(cpu,hd,LEAVE_HANDLER,externalWindow,d);
			for(Handler hd:next)if(!externalHandlers.contains(hd))call(cpu,hd,ENTER_HANDLER,under,d);
			if(under!=0)for(Handler hd:next)call(cpu,hd,ENTER_WINDOW,under,d);
			externalHandlers=next;externalWindow=under;
		}
		if(externalWindow!=0)for(Handler hd:externalHandlers)call(cpu,hd,IN_WINDOW,externalWindow,d);
		if(phase!=EXTERNAL_DROP)return false;
		d.mouseUpModifiers=modifiers|0x80;
		boolean taken=false;
		try{
			List<Handler> receivers=handlersFor(receiveHandlers,externalWindow);
			for(Handler hd:receivers){int r=callReceive(cpu,hd,externalWindow,d);if(r==NO_ERR)taken=true;}
			if(Mac68kApi.TRACE)System.err.println("[68K] host drop at "+h+","+v+" on $"+Integer.toHexString(externalWindow)+": "+receivers.size()+" receive handler(s), "+(taken?"taken":"not taken"));
		}finally{endExternal(cpu);}
		return taken;
	}
	/** Whether a host drag is being tracked (its moves are worth sending on). */
	boolean externalActive(){return external!=null;}
	private void endExternal(M68kCpu cpu) throws M68kException {
		Drag d=external;if(d==null)return;
		try{
			if(externalWindow!=0)for(Handler hd:externalHandlers)call(cpu,hd,LEAVE_WINDOW,externalWindow,d);
			for(Handler hd:externalHandlers)call(cpu,hd,LEAVE_HANDLER,externalWindow,d);
		}finally{external=null;externalWindow=0;externalHandlers=new ArrayList<Handler>();drags.remove(d.reference);hideHilite(d);}
	}
	/** One item per file (reference numbers 1, 2 ...) with its 'hfs ' flavor; without the files, one item whose flavor has no data yet. */
	private void fillExternalItems(Drag d,List<java.io.File> files,int h,int v) throws M68kCpuException {
		d.items.clear();
		if(files==null||files.isEmpty()){Item item=new Item();Flavor f=new Flavor();f.type=HFS_FLAVOR;item.flavors.put(HFS_FLAVOR,f);item.bounds=new int[]{v-16,h-16,v+16,h+16};d.items.put(1,item);return;}
		int ref=1;
		for(java.io.File file:files){
			Item item=new Item();Flavor f=new Flavor();f.type=HFS_FLAVOR;f.data=hfsFlavor(file);item.flavors.put(HFS_FLAVOR,f);
			int dy=(ref-1)*36;item.bounds=new int[]{v-16+dy,h-16,v+16+dy,h+16}; // the Finder's icons, one under the other
			d.items.put(ref++,item);
		}
	}
	/** HFSFlavor (80 bytes): fileType, fileCreator, fdFlags, FSSpec - what the Finder puts on each item it drags. */
	private byte[] hfsFlavor(java.io.File file) throws M68kCpuException {
		byte[] out=new byte[80];
		boolean folder=file.isDirectory();FileManager68k fm=tb.files.fileManager;
		byte[] info=FinderInfo68k.read(file);
		int type,creator;
		if(folder){
			boolean volume=false;try{volume=fm.volumeRoot(fm.volumeRefNumFor(file)).equals(file.getCanonicalFile());}catch(java.io.IOException ignored){}
			type=volume?0x6469736b:0x666f6c64;creator=0x4d414353; // 'disk' / 'fold', 'MACS'
		}else{type=fm.shownType(file);creator=info==null?0:MacApp68k.u4(info,4);if(type==0)type=0x3f3f3f3f;if(creator==0)creator=0x3f3f3f3f;}
		putLong(out,0,type);putLong(out,4,creator);
		if(info!=null&&info.length>9){out[8]=info[8];out[9]=info[9];}
		int spec=memory.allocate(70,2);
		try{tb.files.fileManager.writeFSSpec(spec,file);byte[] s=memory.readBytes(spec,70);System.arraycopy(s,0,out,10,70);}
		finally{memory.free(spec);}
		return out;
	}
	private static void putLong(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}

	private int[] regionBounds(int handle) throws M68kCpuException {int data=handle==0?0:memory.readLong(handle);if(data==0)return null;return new int[]{(short)memory.readWord(data+2),(short)memory.readWord(data+4),(short)memory.readWord(data+6),(short)memory.readWord(data+8)};}

	// ------------------------------------------------------------------ hiliting

	private int showHilite(Drag d,int regionHandle,boolean inside) throws M68kCpuException {
		int data=regionHandle==0?0:memory.readLong(regionHandle);if(data==0)return PARAM_ERR;
		return showHilite(d,QdRegion.decode(memory,data),inside);
	}
	/** A two-pixel frame just inside (or outside) the region, in the current port's local coordinates, drawn dark grey; the pixels under it are kept. */
	private int showHilite(Drag d,QdRegion region,boolean inside) throws M68kCpuException {
		hideHilite(d);
		int port=tb.api.currentQuickDrawPort();if(port==0)return PARAM_ERR;
		QdRegion band=inside?QdRegion.combine(region,region.inset(2,2),QdRegion.DIFF):QdRegion.combine(region.inset(-2,-2),region,QdRegion.DIFF);
		List<int[]> rects=band.rects();List<int[]> saved=new ArrayList<int[]>();
		for(int[] r:rects){int w=r[3]-r[1],h=r[2]-r[0];if(w<=0||h<=0)continue;int[] pixels=tb.api.quickDraw().readRgb(r[0],r[1],r[2],r[3]);saved.add(new int[]{r[0],r[1],r[2],r[3]});saved.add(pixels);
			tb.api.quickDraw().fillRgb(r[0],r[1],r[2],r[3],0x444444);}
		d.hiliteWindow=port;d.hiliteRegion=region;d.hiliteInside=inside;d.savedPixels=saved.toArray(new int[0][]);
		return NO_ERR;
	}
	private void hideHilite(Drag d) throws M68kCpuException {
		if(d.savedPixels==null)return;int[][] saved=d.savedPixels;d.savedPixels=null;d.hiliteRegion=null;
		int port=tb.api.currentQuickDrawPort();if(port!=d.hiliteWindow)return; // the port changed: the program repaints anyway
		for(int i=0;i+1<saved.length;i+=2){int[] r=saved[i];tb.api.quickDraw().writeRgb(r[0],r[1],r[2],r[3],saved[i+1]);}
	}
}
