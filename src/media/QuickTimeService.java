import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Movie playback for HyperZebra's QTMovie/Movie XCMDs through the installed QuickTime extension.
 *
 * A service 68K VM runs a tiny synthesized application (InitGraf... then a WaitNextEvent loop) with the virtual System
 * Folder's extensions loaded, so Apple's QuickTime, its codecs, the Sound Manager and the music synthesizer are all the
 * real thing. Every Movie Toolbox call is made from Java on the VM thread through a thunk (trap word, then a jump to the run loop's sentinel):
 * OpenMovieFile/NewMovieFromFile, a 32-bit GWorld as the movie's port, StartMovie, and MoviesTask from the VM's idle
 * hook; after each task the GWorld's pixels are copied into a BufferedImage that {@link TQTMovie} shows in its window.
 * Sound plays through the VM's SoundManager68k as it does for any hosted program.
 *
 * -Dhz68k.xcmdmovie=ffmpeg keeps the conversion path (own decoders, ffmpeg) even when QuickTime is installed;
 * -Dhz68k.xcmdmovie=native uses HyperZebra's own decoders only.
 */
final class QuickTimeService {
	private static final boolean DISABLED="ffmpeg".equalsIgnoreCase(System.getProperty("hz68k.xcmdmovie",""))||TQTMovie.NATIVE_ONLY;
	private static final long CALL_TIMEOUT_MS=Long.getLong("hz68k.qtservice.timeout",20_000L);
	private static final int TASK_INTERVAL_MS=25;
	private static final boolean TRACE=Mac68kApi.TRACE||Boolean.getBoolean("hz68k.qtservice.trace");

	private static QuickTimeService instance;
	private static boolean failed;

	/** Whether movies should go through the installed QuickTime: the extension is in the virtual System Folder and nothing disabled it. */
	static boolean wanted(){
		if(DISABLED||failed||!Extensions68k.QUICKTIME_EXTENSION)return false;
		return Extensions68k.hasQuickTimeExtension(systemFolder());
	}
	static File systemFolder(){
		File home=new File(System.getProperty("user.home"));
		return new File(new File(home,"Library"+File.separator+"Application Support"+File.separator+"HyperZebra"),"System Folder");
	}

	/** The running service, started on first use; null when it could not start (the caller falls back to ffmpeg). */
	static synchronized QuickTimeService get(){
		if(instance!=null&&!instance.app.finished)return instance;
		if(failed)return null;
		try{instance=new QuickTimeService();}
		catch(Exception e){System.err.println("QuickTime service: could not start: "+e);failed=true;instance=null;}
		return instance;
	}
	/** Starts the service in the background so the first movie does not wait for the extensions to load. */
	static void prewarm(){
		if(!wanted())return;
		Thread t=new Thread(new Runnable(){@Override public void run(){get();}},"QuickTime service start");t.setDaemon(true);t.start();
	}

	// ------------------------------------------------------------------ the VM

	private final MacApp68k app;
	private final List<GuestMovie> movies=new ArrayList<GuestMovie>();
	private final java.util.Map<Integer,Integer> thunks=new java.util.HashMap<Integer,Integer>();
	private long lastTask;
	private boolean entered;

	private QuickTimeService() throws Exception {
		File dir=new File(systemFolder().getParentFile(),"QuickTime Service");dir.mkdirs();
		File stub=new File(dir,"QuickTime Service");
		writeStub(stub);
		app=MacApp68k.launchService(stub);
		long deadline=System.currentTimeMillis()+60_000;
		while(System.currentTimeMillis()<deadline&&!app.finished&&(app.toolbox==null||!app.toolbox.extensions.loaded()))Thread.sleep(20);
		if(app.finished)throw new IOException("the service application stopped: "+app.exitMessage);
		if(app.toolbox==null||!app.toolbox.extensions.loaded())throw new IOException("the service application did not finish loading its extensions");
		if(!app.toolbox.guestQuickTime)throw new IOException("QuickTime is not installed in the virtual System Folder");
		// QuickTime's INIT finishes its start-up from a Notification Manager response at the first system task time; EnterMovies must come after it
		deadline=System.currentTimeMillis()+10_000;
		while(System.currentTimeMillis()<deadline&&!app.finished&&app.toolbox.notificationsPending())Thread.sleep(20);
		Thread.sleep(300);
		app.toolbox.idleHook=new Runnable(){@Override public void run(){try{tasks();}catch(M68kException e){System.err.println("QuickTime service: "+e.getMessage());}}};
		// EnterMovies on the VM thread; also proves the Movie Toolbox trap answers
		Integer err=onVm(new Call<Integer>(){@Override public Integer run() throws M68kException {return movies(0x0001,2);}});
		if(err==null||err!=0)throw new IOException("EnterMovies failed: "+err);
		entered=true;
		System.out.println("QuickTime service: ready (QuickTime extension in the service VM)");
	}

	/** CODE 0 + CODE 1 + SIZE of the stub: the classic initialization calls and a WaitNextEvent(everyEvent, &event, 1 tick, nil) loop. */
	private static void writeStub(File file) throws IOException {
		int[] code1={0x0000,0x0001,                       // segment header: first JT entry offset 0, 1 entry
			0x486d,0xfffc,0xa86e,                          // PEA -4(A5); _InitGraf
			0xa8fe,0xa912,0xa930,0xa9cc,                   // _InitFonts _InitWindows _InitMenus _TEInit
			0x42a7,0xa97b,                                 // CLR.L -(SP); _InitDialogs
			0xa850,                                        // _InitCursor
			// loop (offset 24):
			0x558f,0x3f3c,0xffff,0x486d,0xff60,0x2f3c,0x0000,0x0001,0x42a7,0xa860, // WaitNextEvent(everyEvent, -160(A5), 1, nil)
			0x4a5f,                                        // TST.W (SP)+
			0x60e8};                                       // BRA.S loop
		int[] code0={0x0000,0x0028,0x0000,0x0200,0x0000,0x0008,0x0000,0x0020, // above A5 40, below A5 512, JT size 8, JT offset 32
			0x0000,0x3f3c,0x0001,0xa9f0};                  // unloaded entry: offset 0, MOVE.W #1,-(SP); _LoadSeg
		ResourceFile68k rf=new ResourceFile68k(file);
		rf.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CODE"),0,null,0,words(code0)));
		rf.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("CODE"),1,"Main",0,words(code1)));
		rf.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc("SIZE"),-1,null,0,new byte[]{0x58,0,0,0x40,0,0,0,0x40,0,0})); // 4 MB preferred/minimum
		rf.write();
	}
	private static byte[] words(int[] w){byte[] b=new byte[w.length*2];for(int i=0;i<w.length;i++){b[i*2]=(byte)(w[i]>>>8);b[i*2+1]=(byte)w[i];}return b;}

	// ------------------------------------------------------------------ calling the guest

	interface Call<T>{T run() throws M68kException,IOException;}

	/** Runs {@code call} on the VM thread at its next safe point and waits for the result. */
	private <T> T onVm(final Call<T> call) throws IOException {
		if(Thread.currentThread()==app.thread){try{return call.run();}catch(M68kException e){throw new IOException(e.getMessage(),e);}}
		final Object[] result=new Object[2];final CountDownLatch done=new CountDownLatch(1);
		app.toolbox.later(new Runnable(){@Override public void run(){
			try{result[0]=call.run();}catch(Throwable t){result[1]=t;if(TRACE&&t instanceof M68kCpuException)System.err.println(app.cpu.dumpState(((M68kCpuException)t).pc));}finally{done.countDown();}}});
		try{if(!done.await(CALL_TIMEOUT_MS,TimeUnit.MILLISECONDS))throw new IOException("the QuickTime service did not answer within "+CALL_TIMEOUT_MS+" ms");}
		catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("interrupted");}
		if(result[1]!=null){Throwable t=(Throwable)result[1];throw t instanceof IOException?(IOException)t:new IOException(t.getMessage(),t);}
		@SuppressWarnings("unchecked") T value=(T)result[0];return value;
	}

	/** Argument for {@link #trap}: a word or a long pushed in Pascal order. */
	static int[] w(int value){return new int[]{value,2};}
	static int[] l(int value){return new int[]{value,4};}

	/**
	 * Executes a trap on the VM thread the way compiled glue does: the result slot and the arguments are pushed, D0 carries
	 * the selector, the thunk (trap word + RTS) runs, and the result (0, 2 or 4 bytes) is read back. Registers are preserved.
	 */
	private int trap(int trapWord,int selector,int resultBytes,int[]... args) throws M68kException {
		M68kCpu cpu=app.cpu;M68kMemory memory=app.memory;
		int[] d=cpu.d.clone(),a=cpu.a.clone();int sr=cpu.sr,sp=cpu.a[7];
		try{
			cpu.a[7]-=resultBytes;if(resultBytes==2)memory.writeWord(cpu.a[7],0);else if(resultBytes==4)memory.writeLong(cpu.a[7],0);
			int slot=cpu.a[7]; // the result stays where it was reserved, whoever pops the arguments
			int argBytes=0;
			for(int[] arg:args){if(arg[1]==2){cpu.a[7]-=2;memory.writeWord(cpu.a[7],arg[0]);}else{cpu.a[7]-=4;memory.writeLong(cpu.a[7],arg[0]);}argBytes+=arg[1];}
			cpu.d[0]=selector;
			try{cpu.runUntilSentinel(thunk(trapWord));}
			catch(M68kCpuException e){if(TRACE)System.err.println("QuickTime service: trap $"+Integer.toHexString(trapWord)+" selector $"+Integer.toHexString(selector)+" failed\n"+cpu.dumpState(e.pc)+app.toolbox.describeSegments());throw e;}
			if(Boolean.getBoolean("hz68k.qtservice.sp")&&cpu.a[7]+argBytes!=slot)System.err.println("QuickTime service: trap $"+Integer.toHexString(trapWord)+" selector $"+Integer.toHexString(selector)+" left SP at slot"+(cpu.a[7]-slot>=0?"+":"")+(cpu.a[7]-slot)+" (arguments "+argBytes+")");
			return resultBytes==2?(short)memory.readWord(slot):resultBytes==4?memory.readLong(slot):0;
		}finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;cpu.a[7]=sp;}
	}
	private int thunk(int trapWord) throws M68kCpuException {
		Integer t=thunks.get(trapWord);if(t!=null)return t;
		int code=app.memory.allocate(8,2);app.memory.writeWord(code,trapWord);app.memory.writeWord(code+2,0x4ef9);app.memory.writeLong(code+4,M68kCpu.RETURN_SENTINEL); // trap word; JMP sentinel
		thunks.put(trapWord,code);return code;
	}
	private int movies(int selector,int resultBytes,int[]... args) throws M68kException {return trap(0xaaaa,selector,resultBytes,args);}

	// ------------------------------------------------------------------ tools (tools/qt: the installed QuickTime as an oracle)

	/** Runs {@code call} on the service VM's thread (tools only). */
	<T> T callOnVm(Call<T> call) throws IOException {return onVm(call);}
	/** A Movie Toolbox call ($AAAA with selector {@code selector}) from the VM thread (tools only). */
	int movieCall(int selector,int resultBytes,int[]... args) throws M68kException {return movies(selector,resultBytes,args);}
	/** Any stack-based trap from the VM thread (tools only). */
	int trapCall(int trapWord,int selector,int resultBytes,int[]... args) throws M68kException {return trap(trapWord,selector,resultBytes,args);}
	MacApp68k app(){return app;}
	private int qdExtensions(int selector,int argBytes,int resultBytes,int[]... args) throws M68kException {return trap(0xab1d,argBytes<<16|selector,resultBytes,args);}

	// ------------------------------------------------------------------ movies

	/** One movie open in the service: the Movie Toolbox object, its GWorld and the latest frame copied out of it. */
	final class GuestMovie {
		final File file;final boolean loop;
		int movie,gworld,pixMap,pixelBase,rowBytes,width,height,timeScale;long durationUnits;
		volatile BufferedImage frame;private final BufferedImage[] ring=new BufferedImage[3];private int ringIndex;private long frameSerial=-1; // the window may still paint the previous frame while the next is written
		volatile boolean playing,done,disposed;
		int volume=0x0100;

		GuestMovie(File file,boolean loop){this.file=file;this.loop=loop;}
		boolean hasVideo(){return gworld!=0;}
		long durationMillis(){return timeScale>0?durationUnits*1000L/timeScale:0;}

		/** The latest frame (a BufferedImage that is never written again once published), or null without video. */
		BufferedImage frame(){return frame;}

		void play(){run(new Call<Void>(){@Override public Void run() throws M68kException {if(disposed)return null;
			if(movies(0x00dd,2,l(movie))>>>8!=0)movies(0x000d,0,l(movie)); // IsMovieDone -> GoToBeginningOfMovie
			movies(0x000b,0,l(movie));playing=true;done=false;return null;}});} // StartMovie
		void stop(){run(new Call<Void>(){@Override public Void run() throws M68kException {if(disposed)return null;movies(0x000c,0,l(movie));playing=false;return null;}});} // StopMovie
		void setVolume(final int volume256){volume=Math.max(0,Math.min(256,volume256));
			run(new Call<Void>(){@Override public Void run() throws M68kException {if(disposed)return null;movies(0x002f,0,l(movie),w(volume));return null;}});} // SetMovieVolume
		void setTimeMillis(long millis){setTimeUnits(timeScale>0?millis*timeScale/1000L:0);}
		/** Stops and repositions the movie (units of its time scale), drawing the frame there. */
		void setTimeUnits(final long units){run(new Call<Void>(){@Override public Void run() throws M68kException {if(disposed)return null;
			movies(0x000c,0,l(movie));playing=false;done=false;
			movies(0x003d,0,l(movie),l((int)Math.max(0,Math.min(durationUnits,units)))); // SetMovieTimeValue
			// UpdateMovie only marks the movie for redrawing; MoviesTask draws the frame at the new time into the GWorld
			movies(0x001f,2,l(movie));movies(0x0005,0,l(movie),l(0));readFrame();return null;}});} // UpdateMovie, MoviesTask
		long timeMillis(){Integer t=run(new Call<Integer>(){@Override public Integer run() throws M68kException {return disposed?0:movies(0x0039,4,l(movie),l(0));}});return t==null||timeScale<=0?0:t*1000L/timeScale;}
		void dispose(){run(new Call<Void>(){@Override public Void run() throws M68kException {if(disposed)return null;disposed=true;
			movies(0x000c,0,l(movie));movies(0x0023,0,l(movie)); // StopMovie, DisposeMovie
			if(gworld!=0){app.memory.untrackBuffer(pixelBase,rowBytes*height);qdExtensions(0x0004,4,0,l(gworld));} // DisposeGWorld
			synchronized(movies){movies.remove(GuestMovie.this);}return null;}});}
		private <T> T run(Call<T> call){try{return onVm(call);}catch(IOException e){System.err.println("QuickTime service: "+e.getMessage());return null;}}

		/** Copies the GWorld into a fresh frame image when its pixels changed since the last copy (VM thread). */
		void readFrame() throws M68kCpuException {
			if(gworld==0)return;M68kMemory memory=app.memory;
			long serial=memory.chunkSerial(pixelBase,rowBytes*height);if(serial==frameSerial)return;frameSerial=serial;
			ringIndex=(ringIndex+1)%ring.length;BufferedImage image=ring[ringIndex];
			if(image==null||image.getWidth()!=width||image.getHeight()!=height)ring[ringIndex]=image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
			int[] pixels=((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
			byte[] raw=memory.raw();
			for(int y=0;y<height;y++){int row=pixelBase+y*rowBytes,o=y*width;
				for(int x=0;x<width;x++){int p=row+x*4;pixels[o+x]=(raw[p+1]&0xff)<<16|(raw[p+2]&0xff)<<8|(raw[p+3]&0xff);}}
			frame=image;
		}
	}

	/**
	 * Opens {@code file} in the service: OpenMovieFile/NewMovieFromFile, a 32-bit GWorld the size of the movie box as its
	 * port, the first frame drawn. Returns null when QuickTime cannot open it (the caller then converts with ffmpeg).
	 */
	GuestMovie open(final File file,final boolean loop,final boolean startPlaying){
		if(!entered||app.finished)return null;
		final GuestMovie gm=new GuestMovie(file,loop);
		try{
			Boolean ok=onVm(new Call<Boolean>(){@Override public Boolean run() throws M68kException,IOException {
				M68kMemory memory=app.memory;
				int spec=memory.allocate(70,2),refPtr=memory.allocate(2,2),moviePtr=memory.allocate(4,2),resId=memory.allocate(2,2),changed=memory.allocate(2,2),box=memory.allocate(8,2),gwPtr=memory.allocate(4,2);
				try{
					app.toolbox.files.writeFileSpec(spec,file);
					int err=movies(0x0192,2,l(spec),l(refPtr),w(1)); // OpenMovieFile(spec, &ref, fsRdPerm)
					if(err!=0){System.err.println("QuickTime service: OpenMovieFile "+file.getName()+" -> "+err);return false;}
					int ref=(short)memory.readWord(refPtr);memory.writeWord(resId,0);
					err=movies(0x00f0,2,l(moviePtr),w(ref),l(resId),l(0),w(1),l(changed)); // NewMovieFromFile(&movie, ref, &resId, nil, newMovieActive, &changed)
					movies(0x00d5,2,w(ref)); // CloseMovieFile
					gm.movie=memory.readLong(moviePtr);
					if(err!=0||gm.movie==0){System.err.println("QuickTime service: NewMovieFromFile "+file.getName()+" -> "+err);return false;}
					gm.timeScale=movies(0x0029,4,l(gm.movie));gm.durationUnits=movies(0x002b,4,l(gm.movie))&0xffffffffL; // GetMovieTimeScale, GetMovieDuration
					movies(0x00f9,0,l(gm.movie),l(box)); // GetMovieBox
					int top=(short)memory.readWord(box),left=(short)memory.readWord(box+2),bottom=(short)memory.readWord(box+4),right=(short)memory.readWord(box+6);
					gm.width=Math.max(0,right-left);gm.height=Math.max(0,bottom-top);
					if(gm.width>0&&gm.height>0&&(long)gm.width*gm.height<=4096L*4096L){
						memory.writeWord(box,0);memory.writeWord(box+2,0);memory.writeWord(box+4,gm.height);memory.writeWord(box+6,gm.width);
						movies(0x00fa,0,l(gm.movie),l(box)); // SetMovieBox at 0,0
						int qdErr=qdExtensions(0x0000,0x16,2,l(gwPtr),w(32),l(box),l(0),l(0),l(0)); // NewGWorld(&gw, 32, &box, nil, nil, 0)
						gm.gworld=memory.readLong(gwPtr);
						if(qdErr!=0||gm.gworld==0){System.err.println("QuickTime service: NewGWorld "+gm.width+"x"+gm.height+" -> "+qdErr);gm.gworld=0;}
						else{
							gm.pixMap=qdExtensions(0x0017,4,4,l(gm.gworld)); // GetGWorldPixMap
							qdExtensions(0x0001,4,2,l(gm.pixMap));            // LockPixels
							gm.pixelBase=qdExtensions(0x000f,4,4,l(gm.pixMap)); // GetPixBaseAddr
							gm.rowBytes=memory.readWord(memory.readLong(gm.pixMap)+4)&0x3fff;
							memory.trackBuffer(gm.pixelBase,gm.rowBytes*gm.height); // write serials tell readFrame when the codec drew
							movies(0x0016,0,l(gm.movie),l(gm.gworld),l(0)); // SetMovieGWorld(movie, gworld, nil)
						}
					}
					movies(0x000d,0,l(gm.movie)); // GoToBeginningOfMovie
					movies(0x001f,2,l(gm.movie)); // UpdateMovie: the first frame
					movies(0x0005,0,l(gm.movie),l(0)); // MoviesTask draws it
					gm.readFrame();
					if(startPlaying){movies(0x000b,0,l(gm.movie));gm.playing=true;} // StartMovie
					synchronized(movies){movies.add(gm);}
					if(TRACE)System.err.println("QuickTime service: opened "+file.getName()+" movie $"+Integer.toHexString(gm.movie)+" "+gm.width+"x"+gm.height+" timeScale "+gm.timeScale+" duration "+gm.durationUnits+" gworld $"+Integer.toHexString(gm.gworld)+" pixels $"+Integer.toHexString(gm.pixelBase)+" rowBytes "+gm.rowBytes);
					return true;
				}finally{memory.free(spec);memory.free(refPtr);memory.free(moviePtr);memory.free(resId);memory.free(changed);memory.free(box);memory.free(gwPtr);}
			}});
			if(ok==null||!ok){if(gm.movie!=0)gm.dispose();return null;}
			return gm;
		}catch(IOException e){System.err.println("QuickTime service: "+e.getMessage());return null;}
	}

	/** Idle hook (VM thread): MoviesTask for every open movie, end-of-movie handling and frame readout, at most every TASK_INTERVAL_MS. */
	private void tasks() throws M68kException {
		long now=System.currentTimeMillis();if(now-lastTask<TASK_INTERVAL_MS)return;lastTask=now;
		List<GuestMovie> open;synchronized(movies){open=new ArrayList<GuestMovie>(movies);}
		for(GuestMovie gm:open){
			if(gm.disposed)continue;
			movies(0x0005,0,l(gm.movie),l(0)); // MoviesTask(movie, 0)
			if(gm.playing&&(movies(0x00dd,2,l(gm.movie))>>>8)!=0){ // IsMovieDone
				if(gm.loop){movies(0x000d,0,l(gm.movie));movies(0x000b,0,l(gm.movie));} // GoToBeginningOfMovie, StartMovie
				else{gm.playing=false;gm.done=true;}
			}
			gm.readFrame();
		}
	}
}
