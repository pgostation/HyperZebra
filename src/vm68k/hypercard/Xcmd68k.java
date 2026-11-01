import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the classic HyperCard XCmdBlock and executes one 68K XCMD/XFCN resource.
 *
 * Every external runs in a VM of its own ({@link Session}). Most are one-shot: the VM is thrown away when the external
 * returns, as its windows and heap would be. An external that opened an external window (NewXWindow, HyperCard 2.x's
 * XWindoids) keeps its VM alive for as long as the window exists, the way HyperCard keeps the code resource loaded and
 * locked: later calls of the same external from HyperTalk run in that VM (its A4 globals and heap intact), and the window's
 * events are delivered to the same code with an event XCmdBlock (see {@link XWindows68k}). The script thread pumps those
 * events from its idle loop and from `wait`, which is when HyperCard's own event loop serves external windows.
 */
final class Xcmd68k {
	static final int CODE_BASE = 0x00010000;
	/**
	 * HyperCard's windows' records lie at the same address in every external's VM - the first block of its heap - so `the id of
	 * card window` / `the id of the message window` (HyperCard: the WindowPtr) name them to any external: xDraw draws into
	 * `port,<the id of window>`. HyperCard 2.4 answers such addresses too (2321078); HyperZebra used to answer 0.
	 */
	static final int CARD_WINDOW_RECORD = 0x00100000, MESSAGE_WINDOW_RECORD = CARD_WINDOW_RECORD+0xa0;
	private static final int STACK_TOP = 0x00fff000, STACK_CLEARED = 0x4000;
	private static final int PARAMETER_CAPACITY = 64 * 1024;
	private static final int XCMD_BLOCK_SIZE = 128, XCMD_BLOCK_SLACK = 384;
	private static final int RETURN_VALUE_OFFSET = 66;
	private static final int PASS_FLAG_OFFSET = 70;
	private static final int ENTRY_POINT_OFFSET = 72;
	/** Low memory: SFSaveDisk (the negated vRefNum of the Standard File dialog's folder) and CurDirStore (its directory ID). */
	private static final int SF_SAVE_DISK = 0x214, CUR_DIR_STORE = 0x398, MBAR_HEIGHT = 0xbaa;
	private static final boolean TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.xwindow");

	private Xcmd68k() {}

	/** The VM of one external: the code at {@link #CODE_BASE}, its heap, Toolbox and (for an XWindoid) its windows. */
	static final class Session {
		final M68kMemory memory;final Mac68kApi macApi;final M68kCpu cpu;final HyperCard68kCallbacks callbacks;
		OStack stack;final StackTextEncoding encoding;final String key,name;
		final XWindows68k xwindows;
		/** The resource the code came from (GetXResInfo); null for a bare code image. */
		Rsrc.xcmdClass external;
		/** Guest code is running (a HyperTalk call, or an event being delivered). */
		int running;
		/** An event delivery failed and the CPU state was printed (later failures print one line). */
		boolean eventFailureShown;
		private boolean shutDown;
		/**
		 * The last call left state in the VM (Handles, Ptrs or loaded resources it did not dispose): the VM stays, as
		 * HyperCard keeps an external's loaded resources and heap blocks until the stack closes. Some externals
		 * installs its player from its ISTk/PSTk resources on the first call and finds it through those same, patched,
		 * resource Handles on the next; a one-shot VM reloaded them from disk and jumped into nothing.
		 */
		boolean keepAlive;
		/** When the session was last called (the oldest state-only sessions go when there are too many). */
		long lastUsed;
		/**
		 * Where each external loaded into this VM starts, by session key. The first one sits at {@link #CODE_BASE}; the stack's
		 * other externals join a kept VM, as they all share HyperCard's heap: AnimationMaker's AMLoad returns a Handle that
		 * AMPlay and AMRelease are then given (TheAppleAnimation).
		 */
		final Map<String,Integer> codeAt=new LinkedHashMap<String,Integer>();
		/** The entry point of the external being called (an XWindow it creates keeps it for its events). */
		int entry=CODE_BASE;
		/** The folder of the stack whose external is being called (GetXResInfo names that stack's resource file). */
		File runningFrom;
		/**
		 * What A4 holds when HyperCard calls an external: a register of HyperCard's own code, i.e. somewhere in HyperCard's memory.
		 * Externals with A4-relative globals that never call SetUpA4 (Core Collection's BootVolume: THINK C header, `clr.b $548(a4)`)
		 * write into that and do no visible harm on a Mac; with A4 = 0 they wrote "HyperZebra HD:" over the trap table at $548-$557
		 * (StripAddress's entry) and the next external crashed at an F-line. A4 points into the middle of a block of their own (a
		 * signed 16-bit displacement reaches every byte of it), as THINK's SetUpA4 would point it at the code's globals.
		 */
		final int strayA4;
		private static final int STRAY_A4_BLOCK=0x10000;

		private Session(byte[] code,StackTextEncoding encoding,OStack stack,String key,String name) throws M68kException {
			this.stack=stack;this.encoding=encoding;this.key=key;this.name=name;
			memory=new M68kMemory();
			memory.setGrowable(256*1024*1024); // room for screen-sized external windows without paying for it on every one-shot call
			boolean windowRecords=memory.allocate(MESSAGE_WINDOW_RECORD+WindowManager68k.WINDOW_SIZE-CARD_WINDOW_RECORD,4)==CARD_WINDOW_RECORD; // the heap's first block
			memory.load(CODE_BASE,code);
			if(key!=null)codeAt.put(key,CODE_BASE);
			int stray=memory.allocate(STRAY_A4_BLOCK,4);memory.fill(stray,STRAY_A4_BLOCK,0);strayA4=stray+STRAY_A4_BLOCK/2;
			macApi=new Mac68kApi(memory,stack);
			macApi.application().hyperCardWindowRecords=windowRecords;
			macApi.application().externalLoaded(CODE_BASE,code,name);
			callbacks=new HyperCard68kCallbacks(memory,macApi,0);
			callbacks.session=this;
			cpu=new M68kCpu(memory,0,macApi,callbacks);
			cpu.external=true;
			xwindows=new XWindows68k(this);
			callbacks.xwindows=xwindows;
		}

		/** Whether externals of {@code other} (not of {@code closing}) are loaded here: their keys lie in other's folder. */
		boolean hostsExternalsOf(OStack other,OStack closing){
			if(other==null||closing==null||sameStack(other,closing))return false;
			File dir=folderOf(other),gone=folderOf(closing);
			if(dir==null||dir.equals(gone))return false;
			for(String k:codeAt.keySet())if(dir.equals(new File(k).getAbsoluteFile().getParentFile()))return true;
			return false;
		}
		/** Whether externals of a stack in use (start using), other than {@code closing}, are loaded here: that stack's resource file
		 *  stays open, and so do its externals' windows (CalendarPallete's palette, from a library stack, when the stack that called
		 *  it closes: its closeCalendar went to the card being closed, "no field Message"). */
		boolean hostsExternalsInUse(OStack closing){
			OStack current=PCARD.pc==null?null:PCARD.pc.stack;
			if(current==null||current.usingStacks==null)return false;
			for(OStack u:new ArrayList<OStack>(current.usingStacks))if(u!=null&&!sameStack(u,closing)&&hostsExternalsOf(u,closing))return true;
			return false;
		}
		/** Where the externals of {@code stack} were loaded in this VM. */
		java.util.Set<Integer> entriesOf(OStack stack){
			java.util.Set<Integer> out=new java.util.HashSet<Integer>();File dir=folderOf(stack);
			for(Map.Entry<String,Integer> e:codeAt.entrySet())if(dir!=null&&dir.equals(new File(e.getKey()).getAbsoluteFile().getParentFile()))out.add(e.getValue());
			return out;
		}
		private File folderOf(OStack stack){
			if(stack==null)return null;
			File f=stack.file!=null?stack.file:stack.path!=null?new File(stack.path):null;
			if(f==null)return null;
			return (f.isDirectory()?f:f.getParentFile()).getAbsoluteFile();
		}

		/** Whether the VM must outlive the current call (an external window is open, or state was left behind). */
		/** Kept for its windows, its state, or a sound it started and returns from (ending the VM now silences it). */
		boolean persistent(){return !xwindows.isEmpty()||keepAlive||macApi.soundManager().sounding()||macApi.application().soundDriver.busy()||macApi.application().quickTime.musicSounding();} // a tune queued by an external plays on after it returns

		/** Loads another external of the stack into this VM, in a locked Handle as HyperCard loads code resources. */
		int load(String key,byte[] code) throws M68kException {
			Integer at=codeAt.get(key);if(at!=null)return at;
			int data=memory.allocate(Math.max(code.length,2),2),handle=memory.allocate(4,2);
			memory.load(data,code);memory.writeLong(handle,data);macApi.registerExistingHandle(handle,code.length);
			codeAt.put(key,data);
			macApi.application().externalLoaded(data,code,new File(key).getName());
			return data;
		}

		/** Runs the external once with {@code parameters}, as HyperCard does for a HyperTalk call, and returns its result. */
		String call(String[] parameters) throws M68kException {return call(CODE_BASE,parameters);}

		/** Runs the external starting at {@code entryPoint}. */
		String call(int entryPoint,String[] parameters) throws M68kException {
			if(parameters.length > 16) throw new M68kCpuException(CODE_BASE, "XCMD accepts at most 16 parameters");
			// the card window's records and the A5 world are made before anything of the call: made after the parameters, the A5
			// globals (a nonrelocatable block) sat between the external's Handles in its kept VM, and Core Collection's FolderContents
			// could not grow its locked result Handle past them (memFullErr: "files" lost names)
			if(running==0&&macApi.application().externalA5World()==0)macApi.application().openCardWindow(cpu);
			// with room behind it: HyperCard's block lives in its own stack frame, where writing past its 128 bytes harms nothing.
			// FoxTools' Xtherm keeps its window at paramPtr+176, which landed on the next heap block (its window's colours: cyan labels)
			int block = memory.allocate(XCMD_BLOCK_SIZE+XCMD_BLOCK_SLACK, 2);memory.fill(block,XCMD_BLOCK_SIZE+XCMD_BLOCK_SLACK,0);
			memory.writeWord(block, parameters.length);
			List<Integer> handles=new ArrayList<Integer>();
			for(int i=0;i<parameters.length;i++) {
				byte[] bytes=encoding.encodeReplacing(FileManager68k.macCharsOfSfm(toClassicLineEndings(parameters[i])));
				// at least 64K (externals that write past the string without SetHandleSize keep working), more for a longer value:
				// HyperCard hands over a Handle of any size (a 70000-char parameter was "XCMD parameter is too long")
				int data=memory.allocate(Math.max(PARAMETER_CAPACITY,bytes.length+1),2), handle=memory.allocate(4,2);
				memory.load(data,bytes); memory.writeByte(data+bytes.length,0); memory.writeLong(handle,data);
				memory.writeLong(block+2+i*4,handle);
				macApi.registerExistingHandle(handle,bytes.length+1);handles.add(handle);
			}
			// returnValue is NIL on entry, as HyperCard passes it: Apple's Support Tools externals dispose a non-NIL one before
			// setting theirs (FileModDate's error with "noDialog:global" came back as the result too, the freed Handle's block
			// reused for the global) and HFSCopy only builds its "N files … were copied" result when it is still NIL
			memory.writeLong(block+RETURN_VALUE_OFFSET,0);
			memory.writeWord(block+PASS_FLAG_OFFSET,0);
			memory.writeLong(block+ENTRY_POINT_OFFSET,M68kCpu.CALLBACK_SENTINEL);
			String result;
			if(TRACE)System.err.println("[xcmd] "+name+" "+java.util.Arrays.toString(parameters)+(persistent()?" (VM kept for its windows)":""));
			running++;
			lastUsed=System.nanoTime();
			int blocksBefore,resourcesBefore,streamsBefore;
			try{
				if(running==1)cpu.a[7]=STACK_TOP; // a call from HyperTalk while an event is being delivered (SendCardMessage from the event handler) nests on the guest stack
				// the stack starts without the last call's locals: HyperCard's own code runs over that memory between two calls.
				// XTernal Collection's ListSelect reads its ModalDialog itemHit uninitialised; the 1 or 2 its previous dialog left at
				// the same address closed every later dialog at the first click or key
				if(running==1)memory.fill(STACK_TOP-STACK_CLEARED,STACK_CLEARED,0);
				macApi.application().openCardWindow(cpu);
				if(running==1&&macApi.application().hosted())macApi.application().host().externalEntered(); // a pop-up it shows with the button up (mouseUp) is over at once
				// Mouse/RawMouse/MTemp ($828-$833), MBState ($172) and KeyMap ($174) as the external starts: Memory Tools' PeekWord("830")
				// and PeekByte("172") read 0,0 and a button held down for good (MBState 0)
				if(running==1)macApi.application().publishMouse();
				memory.writeWord(MBAR_HEIGHT,TTalk.menuBarShown?MacAppHost.MENU_BAR_HEIGHT:0); // hide menuBar: HyperCard's menu bar is 0 high
				if(running==1){EventManager68k ev=macApi.application().events;ev.hostClicksEnabled=xwindows.isEmpty();ev.resyncHostClicks();}
				// without the parameter Handles, which are gone again when the count is compared (HyperBBS's configureSPort, 5
				// parameters, made its globals Handle and 4 Ptrs - 5 blocks - and seemed to leave nothing behind: its VM went, and
				// every later serial external read garbage through the Handle address it had put into the global SPortGlobals)
				blocksBefore=macApi.liveBlockCount()-handles.size();resourcesBefore=macApi.application().resources.loadedCount();streamsBefore=macApi.application().macTcp.openStreams();
				int forksBefore=openForks();
				int savedEntry=entry;entry=entryPoint;
				if(running==1)followFileDialogFolder();
				int sfSaveDisk=memory.readWord(SF_SAVE_DISK),curDirStore=memory.readLong(CUR_DIR_STORE);
				int savedA4=cpu.a[4];cpu.a[4]=strayA4;
				try{cpu.callExternal(entryPoint,block);}finally{entry=savedEntry;cpu.a[4]=savedA4;}
				macApi.application().resources.storeStackFiles(); // resources it added to its stack are the stack's now
				macApi.application().menus.flushIfDirty(); // what it did to the menus shows without a DrawMenuBar, as HyperCard draws its menus when pulled down
				noteFileDialogFolder(sfSaveDisk,curDirStore);
				if(running==1){
					EventManager68k events=macApi.application().events;
					// HyperCard's event loop updates the card window first: what the external invalidated there is the card again
					CardWindow68k card=macApi.application().cardWindow;
					if(card!=null){card.updateInvalidated(cpu);card.applyShape();}
					events.drawWindowPictures();events.drawDialogUpdates(r->xwindows.window(r)!=null);
					if(xwindows.isEmpty()){handPostedClicksToCard(events.takePostedClicks(),card);events.discardUserEvents();}
					xwindows.afterCall(); // a window created by this call gets its xOpenEvt now
					// HyperCard hands an external window only the events in it: once this call made one (UxBack's backdrop), clicks on
					// the card are no longer copied into the queue the windows are pumped from (Let's Movie! got mouseDownInPicture)
					macApi.application().events.hostClicksEnabled=xwindows.isEmpty();
				}
				else xwindows.afterCall(running); // a nested call (SendCardMessage from a running external) that returns opens its windows too
				//passFlag: the external lets the message go on, as a handler's pass would (PowerBook Dial Fix's dial XCMD passes to
				//HyperCard's own dial); TXcmd sees it through passedLastCall()
				if(memory.readWord(block+PASS_FLAG_OFFSET)!=0)PASSED.set(Boolean.TRUE);
				int handle=memory.readLong(block+RETURN_VALUE_OFFSET);
				int pointer=handle==0?0:memory.readLong(handle);
				// the result Handle may hold more than 64K (FullRead's hex dump of a 108K file, GetDir's path list of the System Folder:
				// "unterminated XCMD string"): read up to its size, NUL included
				int limit=handle==0?PARAMETER_CAPACITY:Math.max(PARAMETER_CAPACITY,macApi.handleSize(handle)+1);
				result=pointer==0?"":toInternalLineEndings(decodeText(encoding,memory.readCString(pointer,limit)));
				if(handle!=0)macApi.disposeHandleAndFree(handle); // HyperCard disposes the Handle the external returned
				if(running==1){
					for(int h:handles)macApi.disposeHandleAndFree(h);handles.clear();
					// an open MacTCP stream is state too, whatever memory it took (TCPActiveOpen's connection id is its stream)
					// and so is a file it opened and did not close: File Utilities' FSOpen answers the refNum that GetEOF/FSRead/FSClose
					// are given in the next calls (rfNumErr -51 in a fresh VM)
					boolean left=macApi.liveBlockCount()>blocksBefore||macApi.application().resources.loadedCount()>resourcesBefore||macApi.application().macTcp.openStreams()>streamsBefore||openForks()>forksBefore;if(left&&!keepAlive)keepAlive=true; // sticky: the state is still there on later calls that add none
					if(TRACE&&left)System.err.println("[xcmd] "+name+" left state behind (blocks "+blocksBefore+" -> "+macApi.liveBlockCount()+", resources "+resourcesBefore+" -> "+macApi.application().resources.loadedCount()+", open files "+forksBefore+" -> "+openForks()+"): VM kept");
				}
			}
			finally{
				running--;
				if(persistent()){ // the VM lives on: give the call's storage back (a one-shot VM goes away whole)
					for(int h:handles)macApi.disposeHandleAndFree(h);
					memory.free(block);
					try{macApi.application().refreshCardWindow();}catch(M68kCpuException ignored){}
				}
			}
			return result;
		}

		/**
		 * The external changed SFSaveDisk/CurDirStore (the folder HyperCard's next Standard File dialog shows): G-XCollection's
		 * MtSetDefaultDir sets them for the stack's next answer/ask file, and the external's own file dialog leaves its folder there.
		 */
		private void noteFileDialogFolder(int sfSaveDiskBefore,int curDirStoreBefore) throws M68kCpuException {
			int sfSaveDisk=(short)memory.readWord(SF_SAVE_DISK),curDirStore=memory.readLong(CUR_DIR_STORE);
			if(sfSaveDisk==(short)sfSaveDiskBefore&&curDirStore==curDirStoreBefore)return;
			try{File folder=macApi.application().files.fileManager.directoryForId(curDirStore,-sfSaveDisk);
				if(folder!=null&&folder.isDirectory())TTalk.externalFileDialogFolder=folder;}
			catch(IOException|RuntimeException e){}
		}

		/** Files the external opened through the File Manager (not the resource files the Resource Manager has open). */
		private int openForks(){FileSystem68k files=macApi.application().files;return files==null||files.fileManager==null?0:files.fileManager.openForkReferences().size();}

		/**
		 * SFSaveDisk/CurDirStore are HyperCard's low memory, one for all its externals: Support Tools' SetDefaultDir sets them and the
		 * next external's GetDefaultDir / SFGetFile starts there. A VM made for a later call starts with the stack's folder; the
		 * folder the last external left (or answer/ask file used, see {@link TTalk#externalFileDialogFolder}) is put back.
		 */
		private void followFileDialogFolder() throws M68kCpuException {
			File folder=TTalk.externalFileDialogFolder;if(folder==null||!folder.isDirectory())return;
			FileSystem68k files=macApi.application().files;if(files==null||files.fileManager==null)return;
			try{
				FileManager68k fm=files.fileManager;if(fm.volumeIndexOf(folder)<0)return;
				memory.writeWord(SF_SAVE_DISK,-fm.volumeRefNumFor(folder));memory.writeLong(CUR_DIR_STORE,fm.directoryIdFor(folder));
			}catch(IOException|RuntimeException e){}
		}

		/**
		 * Mouse-downs the external posted (PostEvent) are HyperCard's: its event loop takes them once the handler that called the
		 * external is done, a click at that global point - Caliban's PostClick "389,270",true clicks the card's Next button. Each
		 * becomes a click on the card, queued after the running handler; a point off the card (a title bar, another window) is dropped.
		 */
		private void handPostedClicksToCard(List<int[]> clicks,CardWindow68k card){
			if(clicks.isEmpty()||card==null)return;
			int[] place=card.screenPlace();
			OStack current=PCARD.pc!=null&&PCARD.pc.stack!=null?PCARD.pc.stack:stack;
			for(int[] c:clicks){
				int x=c[0]-place[0],y=c[1]-place[1];
				if(current==null||x<0||y<0||x>=current.width||y>=current.height){if(TRACE)System.err.println("[xcmd] "+name+": posted click at "+c[0]+","+c[1]+" is off the card");continue;}
				StringBuilder with=new StringBuilder();
				if((c[2]&0x200)!=0)with.append(",shiftKey");if((c[2]&0x800)!=0)with.append(",optionKey");if((c[2]&0x100)!=0)with.append(",commandKey");
				final String script="click at "+x+","+y+(with.length()==0?"":" with "+with.substring(1));
				if(TRACE)System.err.println("[xcmd] "+name+": posted click -> "+script);
				java.util.function.Consumer<String> sink=postedClickSink;
				if(sink!=null){sink.accept(script);continue;}
				TTalk.runOnScriptThread(()->{try{TTalk.doScriptforMenu(script);}catch(xTalkException e){System.out.println("posted click: "+e.getMessage());}});
			}
		}

		/** Between calls, a kept VM's Time Manager/VBL tasks and deferred work still run (a player installed by an external). */
		void idle() throws M68kException {
			Toolbox68k tb=macApi.application();tb.attach(cpu);
			tb.runEventWork();tb.updateTicks();tb.runTimers();
			tb.events.drawWindowPictures();
		}

		/** Closes the external's windows (with their xCloseEvt) and ends the VM. */
		void shutDown(){shutDown(false);}
		/** With releaseSound (its stack closed) a sound the external left playing gets the closed stack's grace. */
		void shutDown(boolean releaseSound){
			if(shutDown)return;shutDown=true;
			try{xwindows.closeAll();}catch(Exception e){if(TRACE)System.err.println("[xwindow] "+name+": close failed: "+e);}
			macApi.application().shutDown(releaseSound); // windows the external opened disappear with its VM
		}
		boolean isShutDown(){return shutDown;}
	}

	/** The VMs kept alive for their external windows or their state, by stack file and resource. */
	private static final Map<String,Session> sessions=new LinkedHashMap<String,Session>();
	/** Sessions in a call now: a fresh one joins {@link #sessions} only when its call returns, and an external can reach HyperCard
	 *  meanwhile (ColorCover's doMenu "next window" through RunHandler, right after showing its cover window). */
	private static final java.util.Set<Session> calling=java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** How many state-only VMs stay at once (each holds 16 MB): the least recently called go first. */
	static final int MAX_KEPT_SESSIONS=Integer.getInteger("hz68k.xcmdsessions",12);

	private static void trimSessions(Session keep){
		List<Session> victims=new ArrayList<Session>();
		synchronized(sessions){
			List<Session> stateOnly=new ArrayList<Session>();
			for(Session s:new java.util.LinkedHashSet<Session>(sessions.values()))if(s.xwindows.isEmpty())stateOnly.add(s);
			stateOnly.sort((a,b)->Long.compare(a.lastUsed,b.lastUsed));
			for(int i=0;stateOnly.size()-i>MAX_KEPT_SESSIONS&&i<stateOnly.size();i++){Session s=stateOnly.get(i);if(s==keep)continue;victims.add(s);sessions.values().removeIf(x->x==s);}
		}
		for(Session s:victims)synchronized(s){if(s.running==0)s.shutDown();}
	}

	/** Tests: takes the clicks externals post for the card (the "click at" statements) instead of running them. */
	static volatile java.util.function.Consumer<String> postedClickSink;

	private static final ThreadLocal<Boolean> PASSED=new ThreadLocal<Boolean>();
	/** Whether the external that just returned on this thread set passFlag (and clears the note) */
	static boolean passedLastCall(){boolean p=PASSED.get()==Boolean.TRUE;PASSED.remove();return p;}

	static String execute(byte[] code, String[] parameters, StackTextEncoding encoding) throws M68kException {
		return execute(code,parameters,encoding,null);
	}

	static String execute(byte[] code, String[] parameters, StackTextEncoding encoding,
		OStack stack) throws M68kException {
		return execute(code,parameters,encoding,stack,null,null);
	}

	private static String execute(byte[] code,String[] parameters,StackTextEncoding encoding,OStack stack,String key,String name) throws M68kException {
		return execute(code,parameters,encoding,stack,key,name,null);
	}

	private static String execute(byte[] code,String[] parameters,StackTextEncoding encoding,OStack stack,String key,String name,Rsrc.xcmdClass xcmd) throws M68kException {
		if(code!=null){byte[] linked=code.clone();if(OpenTransport68k.linkA4Stubs(linked)>0){code=linked;if(TRACE)System.err.println("[xcmd] "+name+": A5-relative library stubs addressed from A4");}}
		Session session=null;
		// called from HyperTalk while another external of the stack is in its call on this thread (Dialoger's ModalDialog sends
		// its callback handler a message, and the handler calls dChanger/SRMStringWidth): HyperCard has one heap, and the dialog
		// pointer the handler was given is good only in the running external's VM - a VM of its own saw garbage there
		Session caller=key!=null&&stack!=null?callingSessionOf(stack):null;
		if(caller!=null){session=caller;if(code==null&&!caller.codeAt.containsKey(key))try{code=Files.readAllBytes(new File(key).toPath());}catch(IOException e){throw new M68kCpuException(CODE_BASE,"cannot read the external "+key+": "+e.getMessage());}}
		code=powerPcCode(code,xcmd,name);
		if(session==null&&key!=null)synchronized(sessions){session=sessions.get(key);if(session!=null&&session.isShutDown()){sessions.remove(key);session=null;}}
		if(session==null&&key!=null&&stack!=null&&code!=null)session=keptSessionOf(stack); // join the stack's kept VM (shared heap)
		if(session==null)session=new Session(code,encoding,stack,key,name==null?"":name);
		if(stack!=null&&session.stack!=stack&&sameStack(session.stack,stack)){session.stack=stack;session.macApi.rebind(stack);}
		if(xcmd!=null)session.external=xcmd;
		String result;
		synchronized(session){
			try{
				int entry=key==null?CODE_BASE:session.codeAt.containsKey(key)?session.codeAt.get(key):session.load(key,code);
				if(TRACE&&entry!=CODE_BASE)System.err.println("[xcmd] "+name+" runs in the kept VM of "+session.name+" at $"+Integer.toHexString(entry));
				File savedFrom=session.runningFrom;session.runningFrom=key==null?null:new File(key).getAbsoluteFile().getParentFile();
				boolean outer=calling.add(session); // a call nested in its own (HyperTalk calling the external again) leaves it to the outer one
				try{result=session.call(entry,parameters);}finally{session.runningFrom=savedFrom;if(outer)calling.remove(session);}
			}
			catch(M68kException e){
				if(session.running>0)throw e; // nested in a call of the same VM still running: that call's end decides
				// A failing call ends the VM; a VM with windows open stays (the windows are the user's), and so does one that holds
				// state from earlier calls when the call stopped cleanly at a trap HyperZebra lacks or by cmd-period: HyperCard never
				// throws an external's memory away, and the Handles its globals name stay good (CTB Toolkit's xyzzyCTBGlobals: after
				// CTBConfigure stopped at IntlTokenize every later CTB call read a dead VM's address, "Failed due to OS error 7")
				boolean clean=e instanceof M68kMacApiException||e instanceof M68kAbortException;
				if(clean&&session.keepAlive&&!session.isShutDown()&&key!=null){synchronized(sessions){sessions.put(key,session);}trimSessions(session);}
				else if(session.xwindows.isEmpty()){session.shutDown();final Session ended=session;synchronized(sessions){sessions.values().removeIf(x->x==ended);}}
				throw e;
			}
			if(session.running>0)return result; // nested in a call of the same VM that is still running (see callingSessionOf): it decides when it returns
			//an external called again soon keeps its VM, as the state-keeping ones do: ASCII-art calls the thePixel XFCN 3240 times in
			//a loop, and making a fresh VM (Toolbox, System resources, card window) for each call took most of its 18 ms
			if(key!=null&&!session.keepAlive&&stack!=null){
				long now=System.nanoTime();Long last;
				synchronized(lastCalls){last=lastCalls.put(key,now);if(lastCalls.size()>64)lastCalls.clear();}
				if(last!=null&&now-last<REPEAT_WINDOW_NANOS)session.keepAlive=true;
			}
			//the external went to another stack and did not come back ("go home" from an external): its stack is closed now
			boolean stackGone=stack!=null&&PCARD.pc!=null&&!sameStack(PCARD.pc.stack,session.stack);
			if(!session.persistent()||stackGone||session.isShutDown()){session.shutDown();final Session ended=session;synchronized(sessions){sessions.values().removeIf(x->x==ended);}}
			else if(key!=null){synchronized(sessions){sessions.put(key,session);}trimSessions(session);}
		}
		return result;
	}

	/**
	 * The code of a PowerPC external (HyperCard 2.3's 'xcmd'/'xfcn'): its routine descriptor runs the fragment through the VM's
	 * Mixed Mode (PpcRuntime.powerPcOnlyFat, Toolbox68k.externalLoaded); a bare fragment gets the descriptor such a resource
	 * has. A PowerPC external in any other form would be run as 68K code, so it is refused by name. 68K code comes back as it is.
	 */
	private static byte[] powerPcCode(byte[] code,Rsrc.xcmdClass xcmd,String name) throws M68kCpuException {
		if(code==null)return null;
		code=PpcRuntime.withRoutineDescriptor(code);
		if(isPowerPc(xcmd)&&!PpcRuntime.powerPcOnlyFat(code))throw new M68kCpuException(CODE_BASE,"the PowerPC external "+(xcmd.name!=null?xcmd.name:name)+" is neither a routine descriptor for a code fragment nor a code fragment");
		return code;
	}
	/** Whether the external is a PowerPC one (an 'xcmd'/'xfcn' resource: platform "macppc", not "mac68k"). */
	static boolean isPowerPc(Rsrc.xcmdClass xcmd){
		String p=xcmd==null||xcmd.platform==null?"":xcmd.platform.toLowerCase();
		return !p.contains("68k")&&p.contains("ppc");
	}

	/** When each external last ran in a VM of its own (by key), to keep the VM of one that is called over and over. */
	private static final Map<String,Long> lastCalls=new java.util.HashMap<String,Long>();
	private static final long REPEAT_WINDOW_NANOS=3_000_000_000L;

	/** The VM of an external of {@code stack} whose call is running on this thread (it is in the middle of a callback), or null. */
	private static Session callingSessionOf(OStack stack){
		for(Session s:calling)if(Thread.holdsLock(s)&&s.running>0&&!s.isShutDown()&&sameStack(s.stack,stack))return s;
		return null;
	}

	/** A VM another external of {@code stack} left state in (or keeps windows in), or null. */
	private static Session keptSessionOf(OStack stack){
		synchronized(sessions){
			for(Session s:sessions.values())if(sameStack(s.stack,stack)&&!s.isShutDown()&&s.persistent())return s;
		}
		return null;
	}

	/** The same stack, or the same stack file opened again (a "go back" after "go home" makes a new OStack for it). */
	static boolean sameStack(OStack a,OStack b){
		if(a==b)return true;
		if(a==null||b==null||a.path==null||b.path==null)return false;
		return a.path.equals(b.path);
	}

	/**
	 * Text between HyperTalk and an external: HyperTalk keeps return as '\n' and lineFeed as '\r' (charToNum/numToChar swap 10 and
	 * 13), the external sees the Mac bytes 13 and 10. The two swap both ways and every other byte passes as it is, as in HyperCard
	 * (HC J-2.3: RestoreCtrlChars("a[10]b") keeps the LF and "a[13][10]b" stays 4 chars; folding LF into CR broke Rinaldi's Scramble
	 * on every encoded byte 10).
	 */
	static String swapLineBreaks(String value) {
		if(value==null || value.indexOf('\r')<0 && value.indexOf('\n')<0) return value;
		char[] c=value.toCharArray();
		for(int i=0;i<c.length;i++){ if(c[i]=='\r') c[i]='\n'; else if(c[i]=='\n') c[i]='\r'; }
		return new String(c);
	}
	/**
	 * Text an external hands to HyperTalk, decoded so that it encodes back to the same bytes: HyperCard's strings are bytes, and
	 * externals pass bytes that are no character through it (UxGetTextValue / UxDraw 1.2 put '?' for every trail byte $C2 around
	 * EvalExpr and restore it in the answer: 青 came back "??"). Japanese keeps such bytes (MacJapaneseDecoder rawBytes); the
	 * one-byte encodings have a character for every byte.
	 */
	static String decodeText(StackTextEncoding encoding,byte[] bytes,int offset,int length) {
		return encoding==StackTextEncoding.JAPANESE?MacJapaneseDecoder.decode(bytes,offset,length,true):encoding.decode(bytes,offset,length);
	}
	static String decodeText(StackTextEncoding encoding,byte[] bytes) {return decodeText(encoding,bytes,0,bytes.length);}
	private static String toClassicLineEndings(String value) { return swapLineBreaks(value); }
	private static String toInternalLineEndings(String value) { return swapLineBreaks(value); }

	static String execute(File resource, String[] parameters, StackTextEncoding encoding) throws IOException, M68kException {
		return execute(Files.readAllBytes(resource.toPath()),parameters,encoding);
	}

	static String execute(File resource,String[] parameters,StackTextEncoding encoding,OStack stack)
		throws IOException,M68kException {
		return execute(resource,parameters,encoding,stack,null);
	}

	/** Runs the external {@code xcmd} (its type, id and name answer GetXResInfo) stored in {@code resource}. */
	static String execute(File resource,String[] parameters,StackTextEncoding encoding,OStack stack,Rsrc.xcmdClass xcmd)
		throws IOException,M68kException {
		String key=resource.getAbsolutePath();
		Session existing;synchronized(sessions){existing=sessions.get(key);}
		byte[] code=existing!=null&&!existing.isShutDown()?null:Files.readAllBytes(resource.toPath()); // a live VM has the code loaded
		return execute(code,parameters,encoding,stack,key,resource.getName(),xcmd);
	}

	static File resourceFile(OStack stack, Rsrc.xcmdClass xcmd) {
		File stackFile=stack.file!=null?stack.file:new File(stack.path);
		File directory=stackFile.isDirectory()?stackFile:stackFile.getParentFile();
		return new File(directory,xcmd.filename);
	}

	// ------------------------------------------------------------------ external windows

	/** Whether any external keeps windows open (the script thread then pumps their events more often). */
	static boolean hasSessions(){synchronized(sessions){return !sessions.isEmpty();}}
	/** {@link #hasSessions}, or this thread is inside the first call of a VM not kept yet (see {@link #liveSessions}). */
	static boolean hasWindowSessions(){if(hasSessions())return true;for(Session s:calling)if(Thread.holdsLock(s))return true;return false;}
	private static volatile long lastPumpNanos;
	/** {@link #pump} at most every 20 ms: called from the script thread between statements so a kept VM's double-buffer
	 *  refills (PlayMOD) keep up while a handler runs without idling. Clicks stay queued then (see {@link XWindows68k#pump(boolean)}). */
	static void pumpIfDue(){
		long now=System.nanoTime();if(now-lastPumpNanos<20_000_000L)return;
		lastPumpNanos=now;pump(true);
	}

	/** The host menu bar was clicked while menus of {@code owner} (an external's Toolbox) are in it: its windows that registered
	 *  menus get xMBarClickedEvt at the next pump (see {@link XWindows68k#noteMenuBarClicked}). */
	static void menuBarClicked(Object owner){
		for(Session s:sessions())if(!s.isShutDown()&&s.macApi.application()==owner)s.xwindows.noteMenuBarClicked();
		lastPumpNanos=0;
	}
	/** Item {@code item} of the external menu {@code menuId} of {@code owner} was chosen: true when one of its windows registered
	 *  that menu and gets the choice as xMenuEvt (HyperCard sends no doMenu then). */
	static boolean menuChosen(Object owner,int menuId,int item){
		for(Session s:sessions())if(!s.isShutDown()&&s.macApi.application()==owner&&s.xwindows.noteMenuChoice(menuId,item)){lastPumpNanos=0;return true;}
		return false;
	}

	/** the mouseClick: takes a click queued for an external window (made while a handler ran); false when there is none. */
	static boolean takeWindowClick(){
		for(Session s:sessions()){synchronized(s){if(!s.isShutDown()&&s.xwindows.takeClick())return true;}}
		return false;
	}

	/**
	 * Delivers pending events (clicks, updates, idle) to every external window; called from the script thread when no
	 * handler is running and while a handler waits, which is when HyperCard's event loop serves XWindoids.
	 */
	static void pump(){pump(false,false);}

	/** {@link #pump()} during a handler's wait: clicks go to the windows as at idle, null events only to the windows that
	 *  have interrupt code (see {@link XWindows68k#pump(boolean,boolean)}). */
	static void pumpWhileWaiting(){pump(false,true);}

	private static void pump(boolean handlerRunning){pump(handlerRunning,handlerRunning);}

	private static void pump(boolean handlerRunning,boolean scriptRunning){
		lastPumpNanos=System.nanoTime();
		List<Session> live;synchronized(sessions){if(sessions.isEmpty())return;live=new ArrayList<Session>(new java.util.LinkedHashSet<Session>(sessions.values()));} // a VM shared by several externals once
		java.util.function.BooleanSupplier abort=M68kCpu.hostAbort;M68kCpu.hostAbort=null; // cmd-. stopped a script, not the windows' event handling
		try{
			for(Session s:live){
				synchronized(s){
					if(s.isShutDown()||s.running>0)continue;
					try{if(s.xwindows.isEmpty())s.idle();else s.xwindows.pump(handlerRunning,scriptRunning);}
					catch(Exception e){System.err.println("[xwindow] "+s.name+": "+e.getMessage());if(TRACE)e.printStackTrace();
						if(!s.eventFailureShown){s.eventFailureShown=true;System.err.println(s.cpu.dumpState(s.cpu.pc));}} // the first one in full: they repeat at every pump
					if(!s.persistent()){s.shutDown();synchronized(sessions){sessions.values().removeIf(x->x==s);}}
				}
			}
		}finally{M68kCpu.hostAbort=abort;}
	}

	/**
	 * HyperCard drew the card window anew (another card): externals of {@code stack} that hooked that drawing (their own
	 * bitsProc on the card window's port, see {@link CardWindow68k#drawingHooked}) get HyperCard's CopyBits of the card, as
	 * on a real Mac. True when one did - what it drew then stands for the card window.
	 */
	static boolean redrawHookedCards(OStack stack){return redrawHookedCards(stack,0,0,32767,32767);}

	/** True when an external of {@code stack} draws the card window whenever HyperCard copies the card there. Reads the VMs
	 *  without taking their sessions: the event thread asks it (unlock screen's visual effect) while a call that waits for that
	 *  thread holds its session (UxPlayMovie's own unlock screen hung both). */
	static boolean cardDrawingHooked(OStack stack){
		for(Session s:sessions()){
			if(s.isShutDown()||!sameStack(s.stack,stack))continue;
			CardWindow68k card=s.macApi.application().cardWindow;
			try{if(card!=null&&card.drawingHooked())return true;}catch(M68kCpuException|RuntimeException e){}
		}
		return false;
	}

	/** HyperCard copied part of the card into the window (card coordinates): a part shown or hidden while the screen is not
	 *  locked. ColorizeHC draws its pictures then (G'S LAIR adds a monster's picture, then shows the button under it). */
	static boolean redrawHookedCards(OStack stack,int top,int left,int bottom,int right){
		boolean any=false;
		for(Session s:sessions()){
			synchronized(s){
				if(s.isShutDown()||!sameStack(s.stack,stack))continue;
				Toolbox68k tb=s.macApi.application();CardWindow68k card=tb.cardWindow;
				try{
					if(card==null||!card.drawingHooked())continue;
					boolean outer=s.running==0;
					if(outer){s.cpu.a[7]=STACK_TOP;tb.attach(s.cpu);tb.openA5World(s.cpu);}
					s.running++;
					try{card.redrawCard(s.cpu,top,left,bottom,right);any=true;}
					finally{s.running--;if(outer)tb.refreshCardWindow();}
				}catch(M68kException e){System.err.println("[xcmd] "+s.name+": the card's redraw failed: "+e.getMessage());if(TRACE)System.err.println(s.cpu.dumpState(s.cpu.pc));}
			}
		}
		return any;
	}

	/** The stack is closing: its externals' windows close (xCloseEvt) and their VMs end. */
	static void stackClosing(OStack stack){
		List<Session> closing=new ArrayList<Session>();
		List<Session> handedOn=new ArrayList<Session>();
		synchronized(sessions){
			for(java.util.Iterator<Session> it=sessions.values().iterator();it.hasNext();){Session s=it.next();
				//an external that is running went to another stack itself (AMLoad's "go home"/"go back"): it goes on, and
				//execute() ends its VM afterwards unless the stack is open again
				if(s.running>0&&stack!=null)continue;
				if(stack==null){closing.add(s);it.remove();continue;}
				if(!sameStack(s.stack,stack))continue;
				if(s.hostsExternalsOf(PCARD.pc==null?null:PCARD.pc.stack,stack)||s.hostsExternalsInUse(stack))handedOn.add(s);
				else{closing.add(s);it.remove();}
			}
		}
		for(Session s:closing)synchronized(s){s.shutDown(true);}
		//HyperCard's externals all share its heap: the stack now open loaded its own externals into this VM (Spelunx's launcher
		//made the frame window, the game stack then installed HyperTint there), so the VM goes on for it and only the windows
		//of the closing stack's externals close
		for(Session s:handedOn)synchronized(s){
			try{s.xwindows.closeMadeBy(s.entriesOf(stack));}catch(M68kException e){if(TRACE)System.err.println("[xwindow] close failed: "+e.getMessage());}
			s.stack=PCARD.pc.stack;
			try{s.macApi.rebindToStack(s.stack);}catch(M68kException e){if(TRACE)System.err.println("[xcmd] "+s.name+": "+e.getMessage());}
		}
	}

	/** An external window a script names (window "X"): the VM it belongs to and its record. */
	static final class ExternalWindow {
		final Session session;final XWindows68k.XWindow window;
		ExternalWindow(Session session,XWindows68k.XWindow window){this.session=session;this.window=window;}
		/** Runs {@code work} against the window's VM, as an event delivered from HyperCard's event loop; null once the window is gone. */
		<T> T call(Work<T> work) throws M68kException {
			synchronized(session){if(session.isShutDown()||session.xwindows.record(window)==null)return null;return work.run(session.xwindows,window);}
		}
		interface Work<T>{T run(XWindows68k xwindows,XWindows68k.XWindow window) throws M68kException;}
	}

	/** The external window titled {@code name} (its VM kept alive for it), or null. */
	static ExternalWindow findWindow(String name){
		List<Session> live=liveSessions();if(live.isEmpty())return null;
		for(Session s:live){
			synchronized(s){
				if(s.isShutDown())continue;
				XWindows68k.XWindow w=s.xwindows.findByTitle(name);
				if(w!=null)return new ExternalWindow(s,w);
			}
		}
		return null;
	}

	/** The external window whose WindowPtr (its "id" in HyperTalk) is {@code record}, or null. Dither Comparison keeps the
	 *  number its NewWindow XFCN answered and says "set UpdateMsg of window id StdDitherWindow to …". */
	static ExternalWindow findWindowById(int record){
		for(ExternalWindow w:windows())if(w.window.record==record)return w;
		return null;
	}

	/** Every external window, newest first (HyperCard lists them in "the windows", hidden ones too: Myst's closemoovs closes
	 *  its invisible sound movies by looking for "moov" there). */
	static List<ExternalWindow> windows(){
		List<Session> live=liveSessions();if(live.isEmpty())return new ArrayList<ExternalWindow>();
		List<ExternalWindow> out=new ArrayList<ExternalWindow>();
		//sessions that share one VM (a stack in use and the stack whose script called its external) hold the same windows:
		//each window is listed once (the windows showed Pictoid's "T1" twice)
		java.util.Set<XWindows68k.XWindow> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<XWindows68k.XWindow,Boolean>());
		for(Session s:live){
			synchronized(s){
				if(s.isShutDown())continue;
				for(XWindows68k.XWindow w:s.xwindows.windows())if(s.xwindows.record(w)!=null&&seen.add(w))out.add(0,new ExternalWindow(s,w));
			}
		}
		return out;
	}

	/** Whether {@code record} is a floating external window (a windoid, always over the card window) in the VM of {@code tb}. */
	static boolean floatingWindow(Toolbox68k tb,int record){
		List<Session> live=liveSessions();if(live.isEmpty())return false;
		for(Session s:live){
			if(s.macApi.application()!=tb)continue;
			synchronized(s){XWindows68k.XWindow w=s.xwindows.window(record);if(w!=null)return w.floating;}
		}
		return false;
	}

	/** {@code doMenu "next window"} sent HyperCard's card window in front of an external's window {@code w}: it stays behind
	 *  the card ({@link MacAppHost#sendBehindHyperCard}). False when no external owns {@code w}. EDT. */
	static boolean sendBehindHyperCard(java.awt.Window w){
		List<Session> live;synchronized(sessions){if(sessions.isEmpty())return false;live=new ArrayList<Session>(new java.util.LinkedHashSet<Session>(sessions.values()));}
		for(Session s:live){final Toolbox68k tb=s.macApi.application();if(tb!=null&&tb.hosted()&&tb.host().sendBehindHyperCard(w)){
			tb.later(new Runnable(){@Override public void run(){tb.windows.refreshLinks();}}); // the card window now comes before it in WindowList
			return true;}}
		return false;
	}

	/** {@code doMenu "next window"}: externals' shown windows around the card window ({@code card}, screen bounds) go behind it
	 *  ({@link MacAppHost#sendBackdropsBehindHyperCard}). False when there were none. EDT. */
	static boolean sendBackdropsBehindHyperCard(java.awt.Rectangle card){
		java.util.Set<Session> all=new java.util.LinkedHashSet<Session>(calling);synchronized(sessions){all.addAll(sessions.values());}
		List<Session> live=new ArrayList<Session>(all);
		boolean any=false;
		for(Session s:live){final Toolbox68k tb=s.macApi.application();if(tb!=null&&tb.hosted()&&tb.host().sendBackdropsBehindHyperCard(card)){
			any=true;tb.later(new Runnable(){@Override public void run(){tb.windows.refreshLinks();}});}}
		return any;
	}

	/** Every external VM with windows (tests, diagnostics). */
	static List<Session> sessions(){synchronized(sessions){return new ArrayList<Session>(new java.util.LinkedHashSet<Session>(sessions.values()));}}
	/** The kept sessions and the ones running a call now: a VM made for this call is kept only when the call returns, and the
	 *  windows it opened are asked for their properties before that (UxMovie's OpenEvent sends OpenMovie, whose handler reads
	 *  `the passMovieClick of window` - "passmovieclick" was not understood at the first call of a session). */
	private static List<Session> liveSessions(){
		java.util.LinkedHashSet<Session> all;synchronized(sessions){all=new java.util.LinkedHashSet<Session>(sessions.values());}
		// only from inside the call (its callbacks run on the calling thread, which holds the session): another thread - the event
		// thread painting - would wait for the whole call, while the call waits for the event thread
		for(Session s:calling)if(Thread.holdsLock(s))all.add(s);
		return new ArrayList<Session>(all);
	}
}
