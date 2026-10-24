import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Component Manager ($A82A) for hosted applications: registration of 'thng'
 * components (from the program, from loaded extensions and from whole resource
 * files, as QuickTime registers its own), lazy loading of component code from
 * the file it lives in, instances, dispatching of component calls into 68K
 * component code, CallComponentFunction glue, capture/default ordering, and
 * the native components (the QuickTime movie controller, the graphics importers and the music components of the native
 * Movie Toolbox, absent when a real QuickTime extension is installed).
 */
final class Components68k {
	static final int BAD_COMPONENT_INSTANCE=0x80008001,BAD_COMPONENT_SELECTOR=0x80008002,INVALID_COMPONENT_ID=-3000,VALID_INSTANCES_EXIST=-3001,COMPONENT_NOT_CAPTURED=-3002,COMPONENT_DONT_REGISTER=-3003,UNRESOLVED_COMPONENT_DLL=-3004;
	// ComponentDescription.componentFlags / thng componentRegisterFlags
	static final int CMP_WANTS_REGISTER_MESSAGE=0x80000000,CMP_FAST_DISPATCH=0x40000000,CMP_IS_MISSING=0x20000000;
	static final int REG_DO_AUTO_VERSION=1,REG_WANTS_UNREGISTER=2,REG_AUTO_VERSION_INCLUDE_FLAGS=4,REG_HAS_MULTIPLE_PLATFORMS=8,REG_LOAD_RESIDENT=16;
	// standard selectors
	static final int SEL_OPEN=-1,SEL_CLOSE=-2,SEL_CAN_DO=-3,SEL_VERSION=-4,SEL_REGISTER=-5,SEL_TARGET=-6,SEL_UNREGISTER=-7;
	static final int PLATFORM_68K=1;

	final class Component {
		final int id;int type,subType,manufacturer,flags,flagsMask;
		int entry; // start of the loaded code (0 until loaded)
		int resFile;java.io.File file; // the resource file the code comes from
		int codeType,codeId,codeHandle;
		int nameType,nameId,infoType,infoId,iconType,iconId;
		int name,info,icon; // Handles (RegisterComponent gives them directly; thng components load theirs on demand)
		int version,registerFlags,refcon;
		boolean nativeController;
		Native handler; // a component implemented in Java (service harnesses): every selector goes to it
		boolean isNative(){return nativeController||handler!=null;}
		Component aliasOf; // a 'thga' component alias: runs the target's code, but its own file is the component's resource file
		Component capturedBy; // a captured component is hidden from searches
		Component(int id){this.id=id;}
		boolean fromResource(){return codeType!=0;}
	}
	/** A Java-implemented component: called with the instance, the selector and the address of params[0] of the ComponentParameters block. */
	interface Native{int call(int instance,int what,int params) throws M68kException;default void closed(int instance){}}
	final class Instance {final int id;final Component component;int storage,error,a5;Instance(int id,Component c){this.id=id;component=c;}}
	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final List<Component> components=new ArrayList<Component>();
	private final Map<Integer,Component> byId=new HashMap<Integer,Component>();
	private final Map<Integer,Instance> instances=new HashMap<Integer,Instance>();
	private int nextComponent=0x00f10000,nextInstance=0x00f20000,modSeed=1;
	private int globals,flags; // the low-memory globals block ($28A) and the private flags long (selectors $31/$32)
	private final Map<Integer,Integer> records=new HashMap<Integer,Integer>(); // component/instance id -> internal record in guest memory (selectors $23/$25)
	static final int CM_VERSION=8; // what QuickTime 4's own Component Manager patch reports (Gestalt 'cpnt', selector $19)

	Components68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;if(!tb.guestQuickTime){movieController();GraphicsImporter68k.register(this,tb);PictureCompression68k.register(this,tb);DecompressSequence68k.registerBase(this);QuickTimeMusic68k.register(this,tb);}}

	/**
	 * The Component Manager's own routines are pascal calls (Components.h): the caller reserves the result
	 * slot, the callee pops the arguments and leaves the result at the stack pointer. D0 gets a copy.
	 */
	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xff;
		int sp=cpu.a[7];
		switch(selector){
			case 0x00:return callComponent(cpu);
			case 0x01:{ // RegisterComponent(cd, entry, global, name, info, icon) : Component
				int icon=memory.readLong(sp),info=memory.readLong(sp+4),name=memory.readLong(sp+8),global=(short)memory.readWord(sp+12),entry=memory.readLong(sp+14),cd=memory.readLong(sp+18);cpu.a[7]+=22;
				Component c=new Component(nextComponent+=0x10);readDescription(c,cd);c.entry=entry;c.name=name;c.info=info;c.icon=icon;c.resFile=tb.resources.currentRefNum();
				return resultLong(cpu,add(c)?c.id:0);}
			case 0x12:{int global=(short)memory.readWord(sp),thng=memory.readLong(sp+2);cpu.a[7]+=6;Component c=registerResource(thng);return resultLong(cpu,c==null?0:c.id);} // RegisterComponentResource
			case 0x14:{int global=(short)memory.readWord(sp),ref=(short)memory.readWord(sp+2);cpu.a[7]+=4;return resultLong(cpu,registerFile(ref));} // RegisterComponentResourceFile(refNum, global) : count
			case 0x02:{int c=memory.readLong(sp);cpu.a[7]+=4;Component comp=byId.get(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);for(Instance i:instances.values())if(i.component==comp)return resultWord(cpu,VALID_INSTANCES_EXIST);remove(comp,true);return resultWord(cpu,0);} // UnregisterComponent : OSErr
			case 0x03:{int cd=memory.readLong(sp);cpu.a[7]+=4;int n=0;for(Component c:components)if(c.capturedBy==null&&matches(c,cd))n++;return resultLong(cpu,n);} // CountComponents
			case 0x04:{int cd=memory.readLong(sp),previous=memory.readLong(sp+4);cpu.a[7]+=8;boolean after=previous==0;int found=0;for(Component c:components){if(!after){if(c.id==previous)after=true;continue;}if(c.capturedBy==null&&matches(c,cd)){found=c.id;break;}}
				if(CM_TRACE)System.err.println(String.format("[cm] FindNextComponent %s/%s/%s flags %08X mask %08X after %X -> %X",ResourceFile68k.typeName(memory.readLong(cd)),ResourceFile68k.typeName(memory.readLong(cd+4)),ResourceFile68k.typeName(memory.readLong(cd+8)),memory.readLong(cd+12),memory.readLong(cd+16),previous,found));
				return resultLong(cpu,found);} // FindNextComponent
			case 0x05:{int icon=memory.readLong(sp),info=memory.readLong(sp+4),name=memory.readLong(sp+8),cd=memory.readLong(sp+12),c=memory.readLong(sp+16);cpu.a[7]+=20; // GetComponentInfo : OSErr
				Component comp=componentOf(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);
				if(cd!=0){memory.writeLong(cd,comp.type);memory.writeLong(cd+4,comp.subType);memory.writeLong(cd+8,comp.manufacturer);memory.writeLong(cd+12,comp.flags);memory.writeLong(cd+16,comp.flagsMask);}
				if(name!=0)copyHandle(nameHandle(comp),name);if(info!=0)copyHandle(infoHandle(comp),info);if(icon!=0)copyHandle(iconHandle(comp),icon);return resultWord(cpu,0);}
			case 0x06:return resultLong(cpu,modSeed); // GetComponentListModSeed
			case 0x2c:cpu.a[7]+=4;return resultLong(cpu,modSeed); // GetComponentTypeModSeed
			case 0x07:{int c=memory.readLong(sp);cpu.a[7]+=4;return resultLong(cpu,open(byId.get(c)));} // OpenComponent
			case 0x2d:{int ciPtr=memory.readLong(sp),c=memory.readLong(sp+4);cpu.a[7]+=8;Component comp=byId.get(c);if(comp==null){if(ciPtr!=0)memory.writeLong(ciPtr,0);return resultWord(cpu,INVALID_COMPONENT_ID);}int ci=open(comp);if(ciPtr!=0)memory.writeLong(ciPtr,ci);return resultWord(cpu,ci==0?(lastOpenError!=0?lastOpenError:INVALID_COMPONENT_ID):0);} // OpenAComponent : OSErr
			case 0x21:{int subType=memory.readLong(sp),type=memory.readLong(sp+4);cpu.a[7]+=8;return resultLong(cpu,openDefault(type,subType));} // OpenDefaultComponent
			case 0x2e:{int ciPtr=memory.readLong(sp),subType=memory.readLong(sp+4),type=memory.readLong(sp+8);cpu.a[7]+=12;int ci=openDefault(type,subType);if(ciPtr!=0)memory.writeLong(ciPtr,ci);return resultWord(cpu,ci==0?(lastOpenError!=0?lastOpenError:INVALID_COMPONENT_ID):0);} // OpenADefaultComponent : OSErr
			case 0x08:{int ci=memory.readLong(sp);cpu.a[7]+=4;return resultWord(cpu,close(ci));} // CloseComponent : OSErr
			case 0x0a:{int ci=memory.readLong(sp);cpu.a[7]+=4;Instance i=instances.get(ci);int error=i==null?INVALID_COMPONENT_ID:i.error;if(i!=null)i.error=0;return resultWord(cpu,error);} // GetComponentInstanceError: ICM distinguishes unopened Components by invalidComponentID, then calls OpenComponent
			case 0x0b:{int error=(short)memory.readWord(sp),ci=memory.readLong(sp+2);cpu.a[7]+=6;Instance i=instances.get(ci);if(i!=null)i.error=error;return true;} // SetComponentInstanceError(ci, OSErr)
			case 0x0c:{int ci=memory.readLong(sp);cpu.a[7]+=4;Instance i=instances.get(ci);return resultLong(cpu,i==null?0:i.storage);} // GetComponentInstanceStorage
			case 0x0d:{int storage=memory.readLong(sp),ci=memory.readLong(sp+4);cpu.a[7]+=8;Instance i=instances.get(ci);if(i!=null)i.storage=storage;return true;} // SetComponentInstanceStorage
			case 0x0e:{int ci=memory.readLong(sp);cpu.a[7]+=4;Instance i=instances.get(ci);return resultLong(cpu,i==null?0:i.a5);} // GetComponentInstanceA5
			case 0x0f:{int a5=memory.readLong(sp),ci=memory.readLong(sp+4);cpu.a[7]+=8;Instance i=instances.get(ci);if(i!=null)i.a5=a5;return true;} // SetComponentInstanceA5
			case 0x10:{int c=memory.readLong(sp);cpu.a[7]+=4;Component comp=componentOf(c);return resultLong(cpu,comp==null?0:comp.refcon);} // GetComponentRefcon
			case 0x11:{int refcon=memory.readLong(sp),c=memory.readLong(sp+4);cpu.a[7]+=8;Component comp=componentOf(c);if(comp!=null)comp.refcon=refcon;return true;} // SetComponentRefcon
			case 0x13:{int c=memory.readLong(sp);cpu.a[7]+=4;int n=0;for(Instance i:instances.values())if(i.component.id==c)n++;return resultLong(cpu,n);} // CountComponentInstances
			case 0x15:{int c=memory.readLong(sp);cpu.a[7]+=4;Component comp=componentOf(c);return resultWord(cpu,comp==null?INVALID_COMPONENT_ID:openResFile(comp));} // OpenComponentResFile : refNum
			case 0x1f:case 0x2f:{int refPtr=memory.readLong(sp),c=memory.readLong(sp+4);cpu.a[7]+=8;/* $1F: the same call under its older private number (QuickTime's note allocator) */Component comp=componentOf(c);int ref=comp==null?-1:openResFile(comp);if(refPtr!=0)memory.writeWord(refPtr,ref);return resultWord(cpu,ref<0?(comp==null?INVALID_COMPONENT_ID:-193):0);} // OpenAComponentResFile : OSErr
			case 0x18:{int ref=(short)memory.readWord(sp);cpu.a[7]+=2;tb.resources.closeResFile(ref);return resultWord(cpu,0);} // CloseComponentResFile : OSErr (a pinned extension file stays open, only its place in the chain changes)
			case 0x1c:{int capturing=memory.readLong(sp),captured=memory.readLong(sp+4);cpu.a[7]+=8;Component a=byId.get(captured),b=byId.get(capturing);if(a==null||b==null)return resultLong(cpu,0);a.capturedBy=b;modSeed++;return resultLong(cpu,captured);} // CaptureComponent: the captured component's old id keeps working for the capturer
			case 0x1d:{int c=memory.readLong(sp);cpu.a[7]+=4;Component comp=byId.get(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);if(comp.capturedBy==null)return resultWord(cpu,COMPONENT_NOT_CAPTURED);comp.capturedBy=null;modSeed++;return resultWord(cpu,0);} // UncaptureComponent : OSErr
			case 0x1e:{int flags=(short)memory.readWord(sp),c=memory.readLong(sp+2);cpu.a[7]+=6;Component comp=byId.get(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);components.remove(comp);components.add(0,comp);modSeed++;return resultWord(cpu,0);} // SetDefaultComponent(component, flags): searched first from now on
			case 0x20:{int c=memory.readLong(sp);cpu.a[7]+=4;return resultLong(cpu,byId.containsKey(c)?c:0);} // ResolveComponentAlias
			case 0x24:{int target=memory.readLong(sp),params=memory.readLong(sp+4);cpu.a[7]+=8;return resultLong(cpu,invoke(target,params));} // DelegateComponentCall
			case 0x29:{int suitePtr=memory.readLong(sp),c=memory.readLong(sp+4);cpu.a[7]+=8;if(suitePtr!=0)memory.writeLong(suitePtr,0);return resultWord(cpu,componentOf(c)==null?INVALID_COMPONENT_ID:-192);} // GetComponentIconSuite: none
			case 0x35:{int handlePtr=memory.readLong(sp),resId=(short)memory.readWord(sp+4),resType=memory.readLong(sp+6),c=memory.readLong(sp+10);cpu.a[7]+=14; // GetComponentResource(component, type, id, Handle*): a copy of a resource of the component's file
				Component comp=componentOf(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);int h=resourceCopy(comp,resType,resId);if(handlePtr!=0)memory.writeLong(handlePtr,h);return resultWord(cpu,h==0?-192:0);}
			case 0x36:{int index=(short)memory.readWord(sp),strListId=(short)memory.readWord(sp+2),string=memory.readLong(sp+4),c=memory.readLong(sp+8);cpu.a[7]+=12; // GetComponentIndString(component, Str255, strListID, index)
				Component comp=componentOf(c);if(comp==null)return resultWord(cpu,INVALID_COMPONENT_ID);return resultWord(cpu,indString(comp,strListId,index,string)?0:-192);}
			case 0x38:{int handlePtr=memory.readLong(sp),resId=(short)memory.readWord(sp+4),resType=memory.readLong(sp+6),c=memory.readLong(sp+10);cpu.a[7]+=14;if(handlePtr!=0)memory.writeLong(handlePtr,0);return resultWord(cpu,componentOf(c)==null?INVALID_COMPONENT_ID:-192);} // GetComponentPublicResource: none published
			case 0x39:{cpu.a[7]+=10;return resultWord(cpu,-192);} // GetComponentPublicResourceList
			// Private selectors of the QuickTime-era Component Manager (Mac OS 8/9's, and the head patch QuickTime installs on
			// older ones - 'ptch' -19071 of the extension is the reference for these):
			case 0x19:return resultLong(cpu,CM_VERSION); // Component Manager version : long
			case 0x23:{int c=memory.readLong(sp);cpu.a[7]+=4;return resultLong(cpu,record(componentOf(c),c));} // (Component or ComponentInstance) : internal component record, 0 when not registered
			case 0x25:{int ci=memory.readLong(sp);cpu.a[7]+=4;Instance i=instances.get(ci);return resultLong(cpu,i==null?0:record(i));} // (ComponentInstance) : internal instance record, 0 when not open
			case 0x31:{flags=memory.readLong(sp);cpu.a[7]+=4;return true;} // set private flags (QuickTime brackets resource loads with bit 0)
			case 0x32:return resultLong(cpu,flags); // get private flags : long
			case 0x37:return true; // rescan after a resource file change: registration is immediate here
			case 0x3a:{cpu.a[7]+=12;return resultWord(cpu,-192);} // GetComponentPublicIndString
			case 0xff:{ // CallComponentFunction(params, func) and CallComponentFunctionWithStorage(storage, params, func): the same selector
				// The Component Manager pops func and params, pushes a copy of the parameters and jumps to func: a storage Handle the
				// caller pushed first then sits exactly where func expects its first argument, and func's own pascal return pops it
				// too, so the stack is left as func leaves it and the result lands in the slot the caller reserved.
				int func=memory.readLong(sp),params=memory.readLong(sp+4);cpu.a[7]+=8;
				if(func==0)return resultLong(cpu,BAD_COMPONENT_SELECTOR);
				int paramSize=memory.readByte(params+1);
				cpu.a[7]-=paramSize;memory.copy(params+4,cpu.a[7],paramSize);
				int[] d=cpu.d.clone(),a=cpu.a.clone();
				try{cpu.callLeavingStack(func);}
				finally{int spAfter=cpu.a[7];System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.a[7]=spAfter;}
				cpu.d[0]=memory.readLong(cpu.a[7]);return true;}
			case 0xfe:{ // fast-dispatch delegation: (delegate ComponentInstance, header long) pushed above the caller's pascal frame [ret][args][storage][result]
				int delegate=memory.readLong(sp),header=memory.readLong(sp+4);cpu.a[7]+=8;
				if(delegate==0)return true; // no target: the dispatcher's own code reports badComponentSelector
				int paramSize=(header>>>16)&0xff;int ret=memory.readLong(cpu.a[7]);
				int block=memory.allocate(8+paramSize,4);
				// The parameter block keeps the ORIGINAL instance (self): a base media handler reached this way calls the derived
				// handler's routines back through it. Only the storage comes from the delegate.
				Instance target=instances.get(delegate);int self=callStack.isEmpty()||target==null?delegate:callStack.peek();
				try{memory.writeLong(block,header);memory.copy(cpu.a[7]+4,block+4,paramSize);memory.writeLong(block+4+paramSize,self);
					int result=target==null?invoke(delegate,block):delegateCall(target,block,self);
					// the delegate's return is the caller's: the frame is popped as the handler's RTD would, the result left in its slot
					cpu.a[7]+=4+paramSize+4;memory.writeLong(cpu.a[7],result);cpu.d[0]=result;cpu.pc=ret;
				}finally{memory.free(block);}
				return true;}
			default:throw new M68kMacApiException(cpu.pc,0xa82a,String.format("Component Manager selector $%02X",selector));
		}
	}
	private boolean resultLong(M68kCpu cpu,int value) throws M68kCpuException {memory.writeLong(cpu.a[7],value);cpu.d[0]=value;return true;}
	private boolean resultWord(M68kCpu cpu,int value) throws M68kCpuException {memory.writeWord(cpu.a[7],value);cpu.d[0]=value;return true;}

	/** The Component Manager globals a real Mac keeps at $28A. QuickTime stores its own globals pointer in the long at +8. */
	int globals() throws M68kCpuException {if(globals==0){globals=memory.allocate(0x40,4);memory.fill(globals,0x40,0);memory.writeLong(globals+4,modSeed);}return globals;}
	/**
	 * QuickTime's ICM asks the Component Manager for the internal record of a Component (or of an instance's Component)
	 * only to learn whether it still exists, so a stand-in record - the ComponentDescription at +4, the id's generation
	 * byte at +$29 as in the real table - is enough; it lives as long as the component.
	 */
	private int record(Component c,int id) throws M68kCpuException {
		if(c==null)return 0;
		Integer r=records.get(c.id);
		if(r==null){r=memory.allocate(0x5a,4);memory.fill(r,0x5a,0);memory.writeLong(r+4,c.type);memory.writeLong(r+8,c.subType);memory.writeLong(r+12,c.manufacturer);memory.writeLong(r+16,c.flags);memory.writeByte(r+0x29,c.id>>>16);records.put(c.id,r);}
		return r;
	}
	private int record(Instance i) throws M68kCpuException {
		Integer r=records.get(i.id);
		if(r==null){r=memory.allocate(0x20,4);memory.fill(r,0x20,0);memory.writeLong(r,record(i.component,i.component.id));memory.writeByte(r+5,i.id>>>16);memory.writeLong(r+8,i.storage);memory.writeLong(r+12,i.a5);records.put(i.id,r);}
		return r;
	}
	private void forgetRecord(int id){Integer r=records.remove(id);if(r!=null)memory.free(r);}
	private Component componentOf(int id){Component c=byId.get(id);if(c==null){Instance i=instances.get(id);if(i!=null)c=i.component;}return c;}
	private boolean matches(Component c,int cd) throws M68kCpuException {
		if(cd==0)return true;int type=memory.readLong(cd),sub=memory.readLong(cd+4),man=memory.readLong(cd+8),flags=memory.readLong(cd+12),mask=memory.readLong(cd+16);
		return (type==0||type==c.type)&&(sub==0||sub==c.subType)&&(man==0||man==c.manufacturer)&&((c.flags&mask)==(flags&mask));
	}
	private void copyHandle(int source,int destination) throws M68kCpuException {if(destination==0)return;byte[] data=source==0?null:tb.api.handleContents(source);tb.api.loadHandle(destination,data==null?new byte[0]:data);}
	private void readDescription(Component c,int cd) throws M68kCpuException {
		if(cd==0)return;c.type=memory.readLong(cd);c.subType=memory.readLong(cd+4);c.manufacturer=memory.readLong(cd+8);c.flags=memory.readLong(cd+12);c.flagsMask=memory.readLong(cd+16);
	}

	// ------------------------------------------------------------------ registration

	/** Registers a 'thng' resource Handle (RegisterComponentResource, extension loading): its code is loaded from the resource's own file on first open. */
	Component registerResource(int thng) throws M68kException {
		if(thng==0)return null;
		byte[] b=tb.api.handleContents(thng);if(b==null)return null;
		int ref=tb.resources.homeRefNumOf(thng);if(ref==0)ref=tb.resources.currentRefNum();
		Component c=fromThng(b,ref);
		return c!=null&&add(c)?c:null;
	}
	/** RegisterComponentResourceFile: every 'thng' of the file; returns how many were registered. */
	int registerFile(int ref) throws M68kException {
		int n=0;
		for(ResourceFile68k.Resource r:new ArrayList<ResourceFile68k.Resource>(tb.resources.resourcesOfFile(ref,ResourceFile68k.fourcc("thng")))){
			byte[] b;try{b=tb.resources.data(r);}catch(M68kCpuException e){continue;}
			Component c=fromThng(b,ref);
			if(c!=null&&add(c))n++;
		}
		// 'thga' component aliases (QuickTime™ Musical Instruments: an 'inst'/'gs8 ' alias of the base instrument component whose
		// resource file holds the instrument samples): the target's code under the alias's own description, name and file
		for(ResourceFile68k.Resource r:new ArrayList<ResourceFile68k.Resource>(tb.resources.resourcesOfFile(ref,ResourceFile68k.fourcc("thga")))){
			byte[] b;try{b=tb.resources.data(r);}catch(M68kCpuException e){continue;}
			if(b.length<64)continue;
			int type=u4(b,44),subType=u4(b,48),manufacturer=u4(b,52),flags=u4(b,56),mask=u4(b,60);Component target=null;
			for(Component each:components)if((type==0||each.type==type)&&(subType==0||each.subType==subType)&&(manufacturer==0||each.manufacturer==manufacturer)&&((each.flags^flags)&mask)==0){target=each;break;}
			if(target==null){if(Mac68kApi.TRACE)System.err.println("[68K] component alias "+ResourceFile68k.typeName(u4(b,0))+"/"+ResourceFile68k.typeName(u4(b,4))+": target "+ResourceFile68k.typeName(type)+"/"+ResourceFile68k.typeName(subType)+" not registered");continue;}
			Component c=new Component(nextComponent+=0x10);
			c.type=u4(b,0);c.subType=u4(b,4);c.manufacturer=u4(b,8);c.flags=u4(b,12)|(target.flags&CMP_FAST_DISPATCH);c.flagsMask=u4(b,16); // the calling convention is the code's
			c.nameType=u4(b,26);c.nameId=(short)u2(b,30);c.infoType=u4(b,32);c.infoId=(short)u2(b,36);c.iconType=u4(b,38);c.iconId=(short)u2(b,42);
			c.codeType=target.codeType;c.codeId=target.codeId;c.version=target.version;c.registerFlags=target.registerFlags&~REG_DO_AUTO_VERSION;c.aliasOf=target;
			c.resFile=ref;c.file=tb.resources.fileOf(ref);
			if(add(c))n++;
		}
		return n;
	}
	/** Parses a component resource (ComponentResource, then the extended fields when present) and picks its 68K code; null when there is none. */
	private Component fromThng(byte[] b,int ref){
		if(b.length<44)return null;
		Component c=new Component(nextComponent+=0x10);
		c.type=u4(b,0);c.subType=u4(b,4);c.manufacturer=u4(b,8);c.flags=u4(b,12);c.flagsMask=u4(b,16);
		c.codeType=u4(b,20);c.codeId=(short)u2(b,24);
		c.nameType=u4(b,26);c.nameId=(short)u2(b,30);c.infoType=u4(b,32);c.infoId=(short)u2(b,36);c.iconType=u4(b,38);c.iconId=(short)u2(b,42);
		if(b.length>=58){
			c.version=u4(b,44);c.registerFlags=u4(b,48);
			int count=u4(b,54);
			if(count>0){ // a platform table (componentHasMultiplePlatforms may be set with an empty table: the basic code spec applies then, e.g. QuickTime's base graphics importer)
				boolean found=false;
				for(int i=0;i<count&&58+i*12+12<=b.length;i++){int at=58+i*12;int platform=u2(b,at+10);
					if(platform==PLATFORM_68K){c.flags=u4(b,at);c.codeType=u4(b,at+4);c.codeId=(short)u2(b,at+8);found=true;break;}}
				if(!found)return null; // PowerPC-only component
			}
		}
		if(c.codeType==0)return null;
		c.resFile=ref;c.file=tb.resources.fileOf(ref);
		return c;
	}
	/**
	 * Adds a component to the list, honouring the registration protocol: a component that wants the register message may refuse
	 * (nonzero result), and with componentDoAutoVersion an older version of an already registered component is dropped while a
	 * newer one replaces it.
	 */
	private boolean add(Component c) throws M68kException {
		if((c.registerFlags&REG_DO_AUTO_VERSION)!=0){
			Component existing=null;for(Component each:components)if(each.type==c.type&&each.subType==c.subType&&each.manufacturer==c.manufacturer&&((c.registerFlags&REG_AUTO_VERSION_INCLUDE_FLAGS)==0||each.flags==c.flags)){existing=each;break;}
			if(existing!=null){
				int newVersion=version(c),oldVersion=version(existing);
				if(newVersion<=oldVersion){if(Mac68kApi.TRACE)System.err.println("[68K] component "+describe(c)+" not registered: version "+Integer.toHexString(newVersion)+" <= "+Integer.toHexString(oldVersion));return false;}
				remove(existing,true);
			}
		}
		if((c.flags&CMP_WANTS_REGISTER_MESSAGE)!=0&&!c.isNative()){
			int result=callSelector(c,SEL_REGISTER,c.id,0);
			if(result!=0){if(Mac68kApi.TRACE)System.err.println("[68K] component "+describe(c)+" declined registration ("+Integer.toHexString(result)+")");return false;}
		}
		components.add(c);byId.put(c.id,c);modSeed++;
		if(Mac68kApi.TRACE)System.err.println("[68K] component registered: "+describe(c)+" = "+Integer.toHexString(c.id));
		return true;
	}
	private void remove(Component c,boolean message) throws M68kException {
		if(message&&(c.registerFlags&REG_WANTS_UNREGISTER)!=0&&!c.isNative())callSelector(c,SEL_UNREGISTER,c.id,0);
		components.remove(c);byId.remove(c.id);forgetRecord(c.id);modSeed++;
	}
	private int version(Component c) throws M68kException {
		if(c.isNative())return 0x00010001;
		if(c.version!=0)return c.version; // the thng's own version field (componentDoAutoVersion components carry it)
		return callSelector(c,SEL_VERSION,c.id,0);
	}
	String describe(Component c){return ResourceFile68k.typeName(c.type)+"/"+ResourceFile68k.typeName(c.subType)+"/"+ResourceFile68k.typeName(c.manufacturer)+(c.file!=null?" ("+c.file.getName()+")":"");}

	// ------------------------------------------------------------------ code and resources of a component

	/** Loads the component's code resource (once) and returns its entry point, 0 when it cannot be loaded. */
	private int entry(Component c) throws M68kCpuException {
		if(c.entry!=0||!c.fromResource())return c.entry;
		if(c.aliasOf!=null){c.entry=entry(c.aliasOf);return c.entry;}
		int ref=ensureFileOpen(c);
		ResourceFile68k.Resource r=ref<0?null:tb.resources.inFile(ref,c.codeType,c.codeId);
		if(r==null){System.err.println("[68K "+tb.app.name+"] component "+describe(c)+": code "+ResourceFile68k.typeName(c.codeType)+" "+c.codeId+" not found");return 0;}
		int h=tb.resources.handleFor(r);if(h==0)return 0;
		tb.api.setHandleState(h,tb.api.handleState(h)|0x80);
		tb.resources.pinHandle(h); // component code stays even if its file is closed
		c.codeHandle=h;c.entry=memory.readLong(h);
		if(Mac68kApi.TRACE)System.err.println("[68K] component "+describe(c)+" code "+ResourceFile68k.typeName(c.codeType)+" "+c.codeId+" at $"+Integer.toHexString(c.entry)+" size "+tb.api.handleSize(h));
		return c.entry;
	}
	/** The refNum of the component's resource file, reopening it if the program closed it. */
	private int ensureFileOpen(Component c) throws M68kCpuException {
		if(tb.resources.isOpen(c.resFile)&&(c.file==null||c.file.equals(tb.resources.fileOf(c.resFile))))return c.resFile; // refNums are recycled: make sure it is still this file
		if(c.file==null)return -1;
		int ref=tb.resources.openFileNoChain(c.file);if(ref>=0)c.resFile=ref;return ref;
	}
	private int openResFile(Component c) throws M68kCpuException {
		if(!c.fromResource()&&c.file==null)return c.resFile;
		int ref=ensureFileOpen(c);if(ref>=0)tb.resources.raiseForProgram(ref);return ref;
	}
	private int resourceHandle(Component c,int type,int id) throws M68kCpuException {
		if(type==0)return 0;int ref=ensureFileOpen(c);if(ref<0)return 0;
		ResourceFile68k.Resource r=tb.resources.inFile(ref,type,id);return r==null?0:tb.resources.handleFor(r);
	}
	private int nameHandle(Component c) throws M68kCpuException {if(c.name==0)c.name=resourceHandle(c,c.nameType,c.nameId);return c.name;}
	private int infoHandle(Component c) throws M68kCpuException {if(c.info==0)c.info=resourceHandle(c,c.infoType,c.infoId);return c.info;}
	private int iconHandle(Component c) throws M68kCpuException {if(c.icon==0)c.icon=resourceHandle(c,c.iconType,c.iconId);return c.icon;}
	/** A detached copy of a resource from the component's file (GetComponentResource). */
	private int resourceCopy(Component c,int type,int id) throws M68kCpuException {
		int ref=ensureFileOpen(c);if(ref<0)return 0;
		ResourceFile68k.Resource r=tb.resources.inFile(ref,type,id);if(r==null)return 0;
		return tb.api.createHandle(tb.resources.data(r));
	}
	private boolean indString(Component c,int listId,int index,int string) throws M68kCpuException {
		int ref=ensureFileOpen(c);if(ref<0)return false;
		ResourceFile68k.Resource r=tb.resources.inFile(ref,ResourceFile68k.fourcc("STR#"),listId);if(r==null)return false;
		byte[] b=tb.resources.data(r);int n=u2(b,0);if(index<1||index>n)return false;
		int at=2;for(int i=1;i<index;i++)at+=1+(b[at]&0xff);
		int len=b[at]&0xff;if(string!=0)memory.load(string,java.util.Arrays.copyOfRange(b,at,at+1+len));
		return true;
	}

	// ------------------------------------------------------------------ instances

	private int lastOpenError;

	int open(Component c) throws M68kException {
		lastOpenError=0;
		if(c==null)return 0;
		Instance i=new Instance(nextInstance+=0x10,c);instances.put(i.id,i);i.a5=tb.app!=null?tb.app.a5:0;
		if(c.nativeController){tb.quickTime.openController(i.id);return i.id;}
		if(c.handler!=null)return i.id;
		if(entry(c)==0){instances.remove(i.id);lastOpenError=UNRESOLVED_COMPONENT_DLL;return 0;}
		int result=callSelector(c,SEL_OPEN,i.id,i.storage);
		if(Mac68kApi.TRACE)System.err.println("[68K] component open "+describe(c)+" -> "+Integer.toHexString(i.id)+(result!=0?" failed "+Integer.toHexString(result):""));
		if(result!=0){
			if(Mac68kApi.TRACE)System.err.println("[68K] component "+describe(c)+" open failed: "+Integer.toHexString(result));
			Instance opened=instances.remove(i.id);if(opened!=null)callSelector(c,SEL_CLOSE,i.id,opened.storage); // the component releases what it allocated before failing
			lastOpenError=(short)result;return 0;}
		return i.id;
	}
	private int openDefault(int type,int subType) throws M68kException {
		for(Component c:components)if(c.capturedBy==null&&c.type==type&&(subType==0||c.subType==subType)){int ci=open(c);if(ci!=0)return ci;}
		return 0;
	}
	int close(int ci) throws M68kException {
		Instance i=instances.get(ci);if(i==null)return BAD_COMPONENT_INSTANCE;
		if(i.component.nativeController){instances.remove(ci);tb.quickTime.closeController(ci);return 0;}
		if(i.component.handler!=null){instances.remove(ci);forgetRecord(ci);i.component.handler.closed(ci);return 0;}
		try{callSelector(i.component,SEL_CLOSE,ci,i.storage);} // the instance stays valid while its close routine runs (it calls itself and its delegates)
		finally{instances.remove(ci);forgetRecord(ci);}
		return 0;
	}
	/** Calls a standard selector (open/close/register/version/unregister) with params = {ComponentInstance}; selectors made without an instance get the stand-in storage. */
	private int callSelector(Component c,int what,int ci,int storage) throws M68kException {
		if(entry(c)==0)return BAD_COMPONENT_SELECTOR;
		boolean noInstance=what==SEL_VERSION||what==SEL_REGISTER||what==SEL_UNREGISTER;
		int params=memory.allocate(16,4);memory.writeByte(params,0);memory.writeByte(params+1,noInstance?0:4);memory.writeWord(params+2,what);
		if(noInstance){memory.writeLong(params+4,ci);}else{memory.writeLong(params+4,ci);memory.writeLong(params+8,ci);}
		try{return noInstance?callWithoutInstance(c,params):callEntry(c,params,storage);}finally{memory.free(params);}
	}

	/** A component call: the ComponentParameters record is on the stack (flags, paramSize, what, params..., instance). */
	private boolean callComponent(M68kCpu cpu) throws M68kException {
		int params=cpu.a[7];int paramSize=memory.readByte(params+1);
		int ci=memory.readLong(params+4+paramSize);
		if(Mac68kApi.TRACE){StringBuilder b=new StringBuilder();for(int i=0;i<paramSize;i+=4)b.append(String.format(" %08X",memory.readLong(params+4+i)));System.err.println("[cm] call $"+Integer.toHexString(ci)+" what $"+Integer.toHexString(memory.readWord(params+2))+" params"+b);}
		int result=invoke(ci,params);
		if(Mac68kApi.TRACE)System.err.println("[cm] call $"+Integer.toHexString(ci)+" what $"+Integer.toHexString(memory.readWord(params+2))+" -> "+result);
		cpu.a[7]+=4+paramSize+4;
		return resultLong(cpu,result);
	}

	/** A delegated call: the delegate's code and storage, the parameter block (whose instance field names the original caller's instance) unchanged. */
	private int delegateCall(Instance target,int params,int self) throws M68kException {
		if(target.component.isNative())return invoke(target.id,params);
		callStack.push(self); // a further delegation down the chain still names the original instance
		try{return callEntry(target.component,params,target.storage);}finally{callStack.pop();}
	}
	/** Dispatches a ComponentParameters block to the instance's component. */
	int invoke(int ci,int params) throws M68kException {
		Instance i=instances.get(ci);
		int what=(short)memory.readWord(params+2);
		if(i==null){Component c=byId.get(ci);
			if(c!=null){ // calls on a Component (not an instance): version/canDo, and routines a component answers without storage (DataHCanUseDataRef)
				if(c.isNative())return what==SEL_CAN_DO?1:what==SEL_VERSION?0x00010001:what<0?0:BAD_COMPONENT_SELECTOR;
				return entry(c)==0?BAD_COMPONENT_SELECTOR:callWithoutInstance(c,params);}
			return BAD_COMPONENT_INSTANCE;}
		if(i.component.isNative()){
			if(what==SEL_CAN_DO)return 1;if(what==SEL_VERSION)return 0x00010001;if(what<0)return 0;
			return i.component.handler!=null?i.component.handler.call(ci,what,params+4):tb.quickTime.controllerCall(ci,what,params+4);
		}
		callStack.push(ci);
		try{int result=callEntry(i.component,params,i.storage);
			if(CM_TRACE)System.err.println("[cm] "+(System.nanoTime()/1000000%1000000)+" "+describe(i.component)+" #"+what+(result!=0?" -> "+Integer.toHexString(result):""));
			return result;}finally{callStack.pop();}
	}
	/** -Dhz68k.cmtrace=true: one line per component call (type/subtype, selector, non-zero result). */
	private static final boolean CM_TRACE=Boolean.getBoolean("hz68k.cmtrace");
	/** The instances of the component calls in progress (innermost last): a fast-dispatch delegation keeps the original one. */
	private final java.util.ArrayDeque<Integer> callStack=new java.util.ArrayDeque<Integer>();

	/**
	 * A call on a Component that has no instance (DataHCanUseDataRef on a search result, the register and version
	 * messages): like the real Component Manager, a temporary instance is opened for the call and closed afterwards, so the
	 * routine sees the storage its open routine set up (QuickTime's alias data handler reads its subtype from it even in
	 * DataHCanUseDataRef). When the open routine itself fails the call falls back to a stand-in storage record whose first
	 * long is the Component.
	 */
	private int callWithoutInstance(Component c,int params) throws M68kException {
		int what=(short)memory.readWord(params+2);
		if(what!=SEL_OPEN&&what!=SEL_CLOSE){
			Instance i=new Instance(nextInstance+=0x10,c);instances.put(i.id,i);i.a5=tb.app!=null?tb.app.a5:0;
			int opened=callSelector(c,SEL_OPEN,i.id,i.storage);
			if(opened==0){
				try{return callEntry(c,params,i.storage);}
				finally{try{callSelector(c,SEL_CLOSE,i.id,i.storage);}finally{instances.remove(i.id);forgetRecord(i.id);}}
			}
			instances.remove(i.id);
		}
		int standIn=memory.allocate(32,4);
		try{memory.fill(standIn,32,0);memory.writeLong(standIn,c.id);return callEntry(c,params,standIn);}
		finally{memory.free(standIn);}
	}

	/**
	 * Calls the component's entry point with a ComponentParameters block. The documented convention is
	 * pascal ComponentResult entry(ComponentParameters *params, Handle storage); components flagged with bit 30 of
	 * componentFlags (QuickTime's own, built with its "fast dispatch" glue) are entered instead with the caller's pascal
	 * frame itself - the arguments, then the storage Handle in the slot the ComponentInstance occupied, then the result
	 * slot - with the block's first long (flags, paramSize, what) in D0 and the address of that storage slot in A0; their
	 * handlers are plain pascal routines that pop arguments and storage with RTD.
	 */
	private int callEntry(Component c,int params,int storage) throws M68kException {
		if(c.entry==0)return BAD_COMPONENT_SELECTOR;
		M68kCpu cpu=tb.cpu;int[] d=cpu.d.clone(),a=cpu.a.clone();
		int result;
		try{
			if((c.flags&CMP_FAST_DISPATCH)!=0){
				int paramSize=memory.readByte(params+1);
				cpu.a[7]-=4;cpu.pushLong(storage);int storageSlot=cpu.a[7];
				cpu.a[7]-=paramSize;memory.copy(params+4,cpu.a[7],paramSize);
				cpu.d[0]=memory.readLong(params);cpu.a[0]=storageSlot; // A0 addresses the storage slot: the dispatcher finds its delegate through (A0)
				cpu.callPascal(c.entry,paramSize+4);
			}else{
				cpu.a[7]-=4;cpu.pushLong(params);cpu.pushLong(storage);
				cpu.callPascal(c.entry,8);
			}
			result=memory.readLong(cpu.a[7]);cpu.a[7]+=4;}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);}
		return result;
	}

	/** The native QuickTime movie controller component ('play'/'moov'/'appl'), registered at start so OpenDefaultComponent finds it. */
	private Component movieController(){
		for(Component each:components)if(each.nativeController)return each;
		Component c=new Component(nextComponent+=0x10);c.type=0x706c6179;c.subType=0x6d6f6f76;c.manufacturer=0x6170706c;c.nativeController=true;components.add(c);byId.put(c.id,c);
		return c;
	}
	Component componentById(int id){return byId.get(id);}
	/** Calls selector {@code what} of the open instance {@code ci} as CallComponent does; {@code args} are {value,size} pairs in declaration order. */
	int call(int ci,int what,int[]... args) throws M68kException {
		int paramSize=0;for(int[] a:args)paramSize+=a[1];
		int block=memory.allocate(8+paramSize,4);
		try{
			memory.writeByte(block,0);memory.writeByte(block+1,paramSize);memory.writeWord(block+2,what);
			int at=block+4;
			for(int i=args.length-1;i>=0;i--){if(args[i][1]==2)memory.writeWord(at,args[i][0]);else memory.writeLong(at,args[i][0]);at+=args[i][1];}
			memory.writeLong(block+4+paramSize,ci);
			return invoke(ci,block);
		}finally{memory.free(block);}
	}
	/** Registers a Java-implemented component under the given description (service harnesses, e.g. a sound source that feeds a decompressor). */
	Component registerNative(int type,int subType,int manufacturer,int flags,Native handler){
		Component c=new Component(nextComponent+=0x10);c.type=type;c.subType=subType;c.manufacturer=manufacturer;c.flags=flags;c.handler=handler;components.add(c);byId.put(c.id,c);modSeed++;
		return c;
	}
	/** The component a search would find first for the description, null when none is registered. */
	/** Every registered component of a type (tools). */
	List<Component> all(int type){List<Component> list=new ArrayList<Component>();for(Component c:components)if(type==0||c.type==type)list.add(c);return list;}
	Component find(int type,int subType){for(Component c:components)if(c.capturedBy==null&&c.type==type&&(subType==0||c.subType==subType))return c;return null;}
	/** NewMovieController: a native movie controller instance (its movie is attached by QuickTime68k). */
	int openMovieController() throws M68kException {return open(movieController());}

	private static int u2(byte[] b,int i){return (b[i]&0xff)<<8|(b[i+1]&0xff);}
	private static int u4(byte[] b,int i){return (b[i]&0xff)<<24|(b[i+1]&0xff)<<16|(b[i+2]&0xff)<<8|(b[i+3]&0xff);}
}
