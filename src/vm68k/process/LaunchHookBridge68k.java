import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A launcher's _LoadSeg patch run for the program it launched. On a Mac all processes share one trap dispatch table and one
 * address space: a program that writes code of its own into the _LoadSeg entry (Gestalt 'tbtt' gives the table) and then
 * launches another one with launchContinue has that code called at the new process's first LoadSeg, in the new process's
 * context, and the code can hand the new process a Handle of the launcher's through AppParmHandle. Here every program has its
 * own memory, so the patch runs in the launcher's VM (at its next event call, the Process Manager reporting the launched program
 * as the current process); the Handle it leaves in AppParmHandle is copied into the launched program - with the Handles it holds -
 * and copied back when that program ends, and an ExitToShell patch it installed for that program runs then in the launcher.
 *
 * Aladdin's trial engine works this way: ShrinkWrap 3.0's "Aladdin Transaction Engine Loader" hands its purchase record to the
 * Aladdin Transaction Engine application, waits until the engine's ExitToShell patch sets its flag and reads the answer ('DE' try,
 * 'QU' quit ...) from the record. Without it the engine found no record ("An incompatibility has been detected between the product
 * purchasing code and this demo product").
 */
final class LaunchHookBridge68k {
	private static final int LOAD_SEG=0xa9f0,EXIT_TO_SHELL=0xa9f4,APP_PARM_HANDLE=0xaec;
	private final MacApp68k launcher,child;private final int hook;
	private final CountDownLatch ran=new CountDownLatch(1);
	// launcher side: the Handle the patch put in AppParmHandle (0 = none), the Handles held in it and their offsets, the ExitToShell patch
	private int record;private final List<Integer> innerOffsets=new ArrayList<Integer>(),innerHandles=new ArrayList<Integer>();private int exitHook;
	private byte[] recordBytes;private final List<byte[]> innerBytes=new ArrayList<byte[]>();
	// launched program side: the copies
	private int childRecord;private final List<Integer> childInner=new ArrayList<Integer>();

	LaunchHookBridge68k(MacApp68k launcher,MacApp68k child,int hook){
		this.launcher=launcher;this.child=child;this.hook=hook;
		// the patch runs once the launcher has the new process's serial number (it compares it with the current process)
		launcher.toolbox.laterAtEvent(this::runHook);
	}

	/** Launcher thread, at an event call: the patch as the launched program's first LoadSeg calls it. */
	private void runHook(){
		Toolbox68k tb=launcher.toolbox;M68kCpu cpu=tb.cpu;M68kMemory memory=tb.memory;
		try{
			int savedParms=memory.readLong(APP_PARM_HANDLE),savedExit=cpu.patchedTrap(EXIT_TO_SHELL);
			tb.currentProcessOverride=child.serialNumber;
			try{cpu.pushWord(1);cpu.stopAtRomEntry=cpu.defaultTrapAddress(LOAD_SEG);cpu.callPascal(hook,2);} // LoadSeg(1), ending where the patch chains to the trap
			finally{cpu.stopAtRomEntry=-1;tb.currentProcessOverride=0;}
			int parms=memory.readLong(APP_PARM_HANDLE);
			if(parms!=savedParms&&tb.api.isHandle(parms)){
				record=parms;recordBytes=contents(tb,parms);
				for(int o=0;o+4<=recordBytes.length;o+=2){int v=readLong(recordBytes,o);if(v!=parms&&tb.api.isHandle(v)){innerOffsets.add(o);innerHandles.add(v);innerBytes.add(contents(tb,v));}}
				memory.writeLong(APP_PARM_HANDLE,savedParms); // it was the launched program's AppParmHandle the patch set
			}
			int exit=cpu.patchedTrap(EXIT_TO_SHELL);
			if(exit!=savedExit){exitHook=exit;cpu.patchTrap(EXIT_TO_SHELL,savedExit);} // the launched program's ExitToShell, not the launcher's
			System.err.println("[68K "+launcher.name+"] _LoadSeg patch $"+Integer.toHexString(hook)+" run for "+child.name+(record!=0?": AppParmHandle of "+recordBytes.length+" bytes":"")+(exitHook!=0?", ExitToShell patch $"+Integer.toHexString(exitHook):""));
		}catch(M68kException e){System.err.println("[68K "+launcher.name+"] _LoadSeg patch for "+child.name+" failed: "+e);}
		finally{ran.countDown();}
	}

	/** Launched program's thread, before its first instruction: waits for the patch and takes the Handle it passes. */
	void childStarting(){
		try{if(!ran.await(30,TimeUnit.SECONDS))return;}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
		if(recordBytes==null)return;
		try{
			byte[] bytes=recordBytes.clone();
			for(int i=0;i<innerOffsets.size();i++){int h=child.api.createHandle(innerBytes.get(i));childInner.add(h);writeLong(bytes,innerOffsets.get(i),h);}
			childRecord=child.api.createHandle(bytes);child.memory.writeLong(APP_PARM_HANDLE,childRecord);
		}catch(M68kCpuException e){System.err.println("[68K "+child.name+"] AppParmHandle from "+launcher.name+": "+e);}
	}

	/** Launched program's thread, once it has ended: the Handles go back to the launcher, then its ExitToShell patch runs there. */
	void childEnded(){
		if(childRecord==0&&exitHook==0)return;
		final byte[] bytes;final List<byte[]> inner=new ArrayList<byte[]>();
		try{
			bytes=childRecord==0?null:contents(child.toolbox,childRecord);
			for(int h:childInner)inner.add(contents(child.toolbox,h));
		}catch(M68kCpuException e){return;}
		if(launcher.finished||launcher.toolbox==null)return;
		launcher.toolbox.laterAtEvent(()->{
			Toolbox68k tb=launcher.toolbox;M68kCpu cpu=tb.cpu;
			try{
				if(bytes!=null){
					for(int i=0;i<inner.size();i++)store(tb,innerHandles.get(i),inner.get(i));
					for(int i=0;i<innerOffsets.size();i++)writeLong(bytes,innerOffsets.get(i),innerHandles.get(i));
					store(tb,record,bytes);
				}
				System.err.println("[68K "+launcher.name+"] "+child.name+" ended"+(bytes!=null?": AppParmHandle copied back":"")+(exitHook!=0?", ExitToShell patch run":""));
				if(exitHook!=0){
					tb.currentProcessOverride=child.serialNumber;
					try{cpu.stopAtRomEntry=cpu.defaultTrapAddress(EXIT_TO_SHELL);cpu.callPascal(exitHook,0);}
					finally{cpu.stopAtRomEntry=-1;tb.currentProcessOverride=0;}
				}
			}catch(M68kException e){System.err.println("[68K "+launcher.name+"] ExitToShell patch for "+child.name+" failed: "+e);}
		});
	}

	private static byte[] contents(Toolbox68k tb,int handle) throws M68kCpuException {return tb.memory.readBytes(tb.memory.readLong(handle),tb.api.handleSize(handle));}
	private static void store(Toolbox68k tb,int handle,byte[] bytes) throws M68kCpuException {
		if(tb.api.handleSize(handle)!=bytes.length&&tb.api.setHandleSize(handle,bytes.length)!=0)return;
		tb.memory.load(tb.memory.readLong(handle),bytes);
	}
	private static int readLong(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|(b[o+3]&0xff);}
	private static void writeLong(byte[] b,int o,int v){b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}
}
