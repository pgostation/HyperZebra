import java.util.HashMap;
import java.util.Map;

/**
 * QuickDraw 3D RAVE (the "QuickDraw™ 3D Accelerator" shared library) for PowerPC programs, after the Universal Interfaces
 * 3.3 headers RAVE.h and RAVESystem.h: the RAVE manager and a drawing engine of its own, in Java, in place of the
 * library's PowerPC code (whose software rasterizer took nine tenths of Nanosaur's time).
 * <p>
 * -Dhzppc.rave=extension uses the real library from the Extensions folder instead; -Dhzppc.rave=trace uses it and logs
 * every call into it, and into the draw contexts it makes (a debugging aid).
 */
final class PpcRave {
	/** The library's name in its 'cfrg' (the file is "QuickDraw™ 3D RAVE"). */
	static final String LIBRARY="QuickDraw™ 3D Accelerator";
	private static final String MODE=System.getProperty("hzppc.rave","native");
	/** Whether this class serves the library (else the real one is loaded). */
	static final boolean NATIVE=MODE.equals("native");
	/** Whether the real library's calls are logged. */
	static final boolean TRACE=MODE.equals("trace");

	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcRave(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private int tb(String name,int... args) throws M68kException {return rt.toolbox(name,args);}

	// ================================================================== the manager

	static final int NO_ERR=0,ERROR=1,OUT_OF_MEMORY=2,NOT_SUPPORTED=3,PARAM_ERR=5,GESTALT_UNKNOWN=6;
	static final int DEVICE_MEMORY=0,DEVICE_GDEVICE=1;
	/** Our own device kind: a context QuickDraw 3D's Interactive Renderer draws into, whose frames it shows itself. */
	static final int DEVICE_HOST=0x686f7374;
	static final int PIXEL_ALPHA1=0,PIXEL_RGB16=1,PIXEL_ARGB16=2,PIXEL_RGB32=3,PIXEL_ARGB32=4,PIXEL_CL4=5,PIXEL_CL8=6,PIXEL_RGB16_565=7,PIXEL_RGB24=8,
		PIXEL_RGB8_332=9,PIXEL_ARGB16_4444=10,PIXEL_ACL16_88=11,PIXEL_I8=12,PIXEL_AI16_88=13;
	static final int CONTEXT_NO_Z=1,TEXTURE_MIPMAP=2,TEXTURE_NO_COPY=32,TEXTURE_FLIP=64;
	/** The TQADrawContext version this manager makes (kQAVersion_1_6: every method of the 1.6 structure is there). */
	static final int VERSION=4;
	/**
	 * Our engine answers as ATI Rage 128 hardware (kQAVendor_ATI = 1) with its fast features, so games take the accelerated
	 * path (Bugdom warned "requires 3D hardware acceleration" and shrank its view for Apple's software engine).
	 * -Dhzppc.rave.engine=apple answers as the software engine QuickDraw 3D's RAVE carried: vendor Apple (0), engine 0, revision 3.
	 * The drawing conventions (sampling, texture v) stay the software engine's either way.
	 * Without that flag the software engine is listed too, after the ATI one, as QuickDraw 3D 1.6 always carries it: WaterRace
	 * looks for vendor 0 engine 0 among the engines and otherwise says "QuickDraw 3D 1.6 is recommended".
	 */
	static final boolean APPLE_SOFTWARE="apple".equalsIgnoreCase(System.getProperty("hzppc.rave.engine",""));
	static final String ENGINE_NAME=APPLE_SOFTWARE?"Apple Software":"ATI RAGE 128";
	static final int VENDOR=APPLE_SOFTWARE?0:1,ENGINE_ID=APPLE_SOFTWARE?0:4,REVISION=APPLE_SOFTWARE?3:0x41;
	/** The software engine's own answers (the second engine, or the only one with -Dhzppc.rave.engine=apple). */
	static final String SOFTWARE_NAME="Apple Software";
	static final int SOFTWARE_REVISION=3,SOFTWARE_TEXTURE_MEMORY=0x01ff0000;
	/** kQAGestalt_TextureMemory / FastTextureMemory: the software engine's answers, or 16 MB of card memory. Nanosaur needs
	 *  an ATI engine ID of at least 4 (it quits before) and fast texture memory; revision 31+ is its current-driver path.
	 *  The revision is ATI's driver version in BCD: WaterRace wants 0x41 (ATI RAVE 4.1) or later for engine 4, else it warns. */
	static final int TEXTURE_MEMORY=APPLE_SOFTWARE?0x01ff0000:16<<20,FAST_TEXTURE_MEMORY=APPLE_SOFTWARE?0:16<<20;
	/** kQAGestalt_FastFeatures of the hardware: Line, Gouraud, Texture, Blend, FogAlpha, FogDepth. */
	static final int FAST_FEATURES=APPLE_SOFTWARE?0:1|2|4|16|(1<<9)|(1<<10);
	/** kQAGestalt_OptionalFeatures: DeepZ, Texture, TextureColor, Blend, BlendAlpha, PerspectiveZ, CL4, CL8, BufferComposite,
	 *  NoDither, FogAlpha, FogDepth, MipmapBias, ChannelMask, ZBufferMask, AlphaTest, AccessDrawBuffer, ClearDrawBuffer, ClearZBuffer. */
	static final int OPTIONAL_FEATURES=1|2|8|16|32|256|(1<<13)|(1<<14)|(1<<15)|(1<<16)|(1<<17)|(1<<18)|(1<<20)|(1<<21)|(1<<22)|(1<<23)|(1<<26)|(1<<28)|(1<<29);
	/** kQAGestalt_OptionalFeatures2: TextureDrawContexts, BitmapDrawContexts, Busy, SwapBuffers, Chromakey, FlipOrigin, BitmapScale. */
	static final int OPTIONAL_FEATURES_2=2|4|8|16|32|(1<<9)|(1<<10);
	/** The pixel types textures and bitmaps may have (a mask of 1 << TQAImagePixelType). */
	static final int TEXTURE_TYPES=(1<<14)-1;

	/** A drawing engine: ours, or one a plug-in registered (QARegisterEngine) whose methods are PowerPC code. */
	private final class Engine {
		final int ref;final int getMethod,refCon;boolean enabled=true;
		/** One of ours answering as Apple's software engine rather than the ATI hardware. */
		boolean software;
		final int[] methods=new int[18];
		Engine(int ref,int getMethod,int refCon){this.ref=ref;this.getMethod=getMethod;this.refCon=refCon;}
		boolean own(){return getMethod==0;}
		/** A registered engine's method (TQAEngineMethodTag), fetched once through its getMethod; 0 if it has none. */
		int method(int tag) throws M68kException {
			if(methods[tag]!=0)return methods[tag];
			int out=scratch(4);memory.writeLong(out,0);
			int err=rt.cpu.call(getMethod,tag,out);
			methods[tag]=err==0?memory.readLong(out):0;return methods[tag];
		}
	}
	private final java.util.List<Engine> engines=new java.util.ArrayList<Engine>();
	private Engine engine(int ref){for(Engine e:engines)if(e.ref==ref)return e;return null;}
	private int scratchBlock;
	/** A small guest block for out-parameters of calls into registered engines. */
	private int scratch(int size) throws M68kCpuException {if(scratchBlock==0)scratchBlock=rt.allocate(64,8);return scratchBlock;}

	private void ensureEngines() throws M68kCpuException {
		if(!engines.isEmpty())return;
		Engine first=new Engine(rt.allocate(16,4),0,0);first.software=APPLE_SOFTWARE;engines.add(first);
		if(!APPLE_SOFTWARE){Engine soft=new Engine(rt.allocate(16,4),0,0);soft.software=true;engines.add(soft);}
	}

	/** Whether an engine can draw to a device (TQADevice*): memory in the 16/32-bit ARGB/RGB types, any GDevice. */
	private int checkDevice(Engine e,int device) throws M68kException {
		if(device==0)return PARAM_ERR;
		if(!e.own()){int m=e.method(2);return m==0?NOT_SUPPORTED:rt.cpu.call(m,device);}
		int type=memory.readLong(device);
		if(type==DEVICE_GDEVICE)return memory.readLong(device+4)!=0?NO_ERR:PARAM_ERR;
		if(type==DEVICE_MEMORY){int pt=memory.readLong(device+8);return pt==PIXEL_RGB16||pt==PIXEL_ARGB16||pt==PIXEL_RGB32||pt==PIXEL_ARGB32?NO_ERR:NOT_SUPPORTED;}
		return NOT_SUPPORTED;
	}
	private int nextEngine(int device,int after) throws M68kException {
		ensureEngines();
		boolean found=after==0;
		for(Engine e:engines){
			if(!found){if(e.ref==after)found=true;continue;}
			if(e.enabled&&(device==0||checkDevice(e,device)==NO_ERR))return e.ref; // a NIL device lists every engine
		}
		return 0;
	}
	private int gestalt(Engine e,int selector,int response) throws M68kException {
		if(!e.own()){int m=e.method(3);return m==0?GESTALT_UNKNOWN:rt.cpu.call(m,selector,response);}
		if(response==0)return PARAM_ERR;
		int v;boolean sw=e.software;String name=sw?SOFTWARE_NAME:ENGINE_NAME;
		switch(selector){
			case 0:v=OPTIONAL_FEATURES;break;
			case 1:v=sw?0:FAST_FEATURES;break; // kQAGestalt_FastFeatures
			case 2:v=sw?0:VENDOR;break;case 3:v=sw?0:ENGINE_ID;break;case 4:v=sw?SOFTWARE_REVISION:REVISION;break;
			case 5:v=name.length();break;
			case 6:{byte[] b=name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);memory.load(response,b);memory.writeByte(response+b.length,0);return NO_ERR;}
			case 7:v=sw?SOFTWARE_TEXTURE_MEMORY:TEXTURE_MEMORY;break;
			case 8:v=sw?0:FAST_TEXTURE_MEMORY;break;
			case 9:v=1<<PIXEL_ARGB16|1<<PIXEL_ARGB32|1<<PIXEL_RGB16|1<<PIXEL_RGB32;break;
			case 10:v=1<<PIXEL_ARGB32|1<<PIXEL_RGB32;break;
			case 11:case 13:v=TEXTURE_TYPES;break;
			case 12:case 14:v=1<<PIXEL_ARGB32|1<<PIXEL_RGB32|1<<PIXEL_ARGB16|1<<PIXEL_RGB16;break;
			case 15:v=OPTIONAL_FEATURES_2;break;
			case 16:v=0;break;
			// ATI's engine-specific selector 1000 (kQAGestalt_EngineSpecific_Minimum): Pangea's games (Nanosaur's
			// MakeShadowTexture) pass a pointer in the response and take an answer left as it was as "not offered"; an error
			// made Nanosaur warn that it "needs an ATI Rage Pro card with the latest beta drivers"
			case 1000:if(sw)return GESTALT_UNKNOWN;return NO_ERR;
			default:return GESTALT_UNKNOWN;
		}
		memory.writeLong(response,v);return NO_ERR;
	}
	private int engineId(Engine e,int selector) throws M68kException {
		if(e.own())return e.software?0:selector==2?VENDOR:ENGINE_ID;
		int out=scratch(4);memory.writeLong(out,-2);gestalt(e,selector,out);return memory.readLong(out);
	}

	void define(PpcLibraries l){
		final String lib=LIBRARY;
		l.register(lib,"QADeviceGetFirstEngine",(r,c)->{int e=nextEngine(arg(0),0);
			if(TRACE_NATIVE)System.err.printf("[rave] QADeviceGetFirstEngine(%08X type %d %08X) = %08X lr %08X%n",arg(0),arg(0)==0?-1:memory.readLong(arg(0)),arg(0)==0?0:memory.readLong(arg(0)+4),e,rt.cpu.lr);
			ret(e);});
		l.register(lib,"QADeviceGetNextEngine",(r,c)->ret(arg(1)==0?0:nextEngine(arg(0),arg(1))));
		l.register(lib,"QAEngineCheckDevice",(r,c)->{Engine e=engine(arg(0));ret(e==null?PARAM_ERR:checkDevice(e,arg(1)));});
		l.register(lib,"QAEngineGestalt",(r,c)->{ensureEngines();Engine e=engine(arg(0));int res=e==null?PARAM_ERR:gestalt(e,arg(1),arg(2));
			if(TRACE_NATIVE){System.err.printf("[rave] QAEngineGestalt(%08X, %d) = %d response %08X lr %08X%n",arg(0),arg(1),res,arg(2)==0?0:memory.readLong(arg(2)),rt.cpu.lr);
				String dump=System.getProperty("hzppc.rave.dumpcaller");
				if(dump!=null)try{java.nio.file.Files.write(java.nio.file.Paths.get(dump+"-"+Integer.toHexString(rt.cpu.lr)),memory.readBytes(rt.cpu.lr-0x400,0x800));}catch(java.io.IOException ignored){}}
			ret(res);});
		l.register(lib,"QAEngineEnable",(r,c)->ret(enable(arg(0),arg(1),true)));
		l.register(lib,"QAEngineDisable",(r,c)->ret(enable(arg(0),arg(1),false)));
		l.register(lib,"QADrawContextNew",(r,c)->ret(drawContextNew(arg(0),arg(1),arg(2),arg(3),arg(4),arg(5))));
		l.register(lib,"QADrawContextDelete",(r,c)->drawContextDelete(arg(0)));
		l.register(lib,"QAColorTableNew",(r,c)->ret(colorTableNew(arg(0),arg(1),arg(2),arg(3),arg(4))));
		l.register(lib,"QAColorTableDelete",(r,c)->{Engine e=engine(arg(0));if(e!=null&&!e.own()){int m=e.method(11);if(m!=0)rt.cpu.call(m,arg(1));}else{colorTables.remove(arg(1));free(arg(1));}});
		l.register(lib,"QATextureNew",(r,c)->ret(imageNew(arg(0),arg(1),arg(2),arg(3),arg(4),false)));
		l.register(lib,"QABitmapNew",(r,c)->ret(imageNew(arg(0),arg(1),arg(2),arg(3),arg(4),true)));
		l.register(lib,"QATextureDetach",(r,c)->ret(foreign(arg(0),5,arg(1))));
		l.register(lib,"QABitmapDetach",(r,c)->ret(foreign(arg(0),8,arg(1))));
		l.register(lib,"QATextureDelete",(r,c)->{if(foreign(arg(0),6,arg(1))==NOT_OURS)deleteImage(arg(1));});
		l.register(lib,"QABitmapDelete",(r,c)->{if(foreign(arg(0),9,arg(1))==NOT_OURS)deleteImage(arg(1));});
		l.register(lib,"QATextureBindColorTable",(r,c)->ret(bindColorTable(arg(0),arg(1),arg(2),12)));
		l.register(lib,"QABitmapBindColorTable",(r,c)->ret(bindColorTable(arg(0),arg(1),arg(2),13)));
		// RAVE 1.6 access to texture and bitmap memory: our images are converted copies, so not offered (nor claimed in the gestalt)
		for(String n:new String[]{"QAAccessTexture","QAAccessTextureEnd","QAAccessBitmap","QAAccessBitmapEnd"})l.register(lib,n,(r,c)->ret(NOT_SUPPORTED));
		// draw notification procs: kept (the manager called them when an engine drew over their global rectangle)
		l.register(lib,"QARegisterDrawNotificationProc",(r,c)->{int ref=++notificationRefNum;if(arg(3)!=0)memory.writeLong(arg(3),ref);ret(NO_ERR);});
		l.register(lib,"QAUnregisterDrawNotificationProc",(r,c)->ret(NO_ERR));
		l.register(lib,"QARegisterEngine",(r,c)->ret(registerEngine(arg(0),0)));
		l.register(lib,"QARegisterEngineWithRefCon",(r,c)->ret(registerEngine(arg(0),arg(1))));
		l.register(lib,"QAGetCurrentEngineRefCon",(r,c)->ret(currentEngineRefCon));
		l.register(lib,"QARegisterDrawMethod",(r,c)->{int ctx=arg(0),tag=arg(1);if(ctx==0||tag<0||tag>=DRAW_METHODS.length){ret(PARAM_ERR);return;}memory.writeLong(ctx+8+4*tag,arg(2));ret(NO_ERR);});
		// the software engine's private big-block allocator (exported by the 1.5 library for engine plug-ins)
		l.register(lib,"QABigMemoryNew",(r,c)->ret(tb("NewPtrClear",arg(0))));
		l.register(lib,"QABigMemoryDelete",(r,c)->{if(arg(0)!=0)tb("DisposePtr",arg(0));});
	}
	private int notificationRefNum,currentEngineRefCon;
	private void deleteImage(int ref){RaveRenderer.Texture t=textures.remove(ref);if(t!=null){noCopy.remove(t);textureFrame.remove(t);}free(ref);}
	private static final int NOT_OURS=Integer.MIN_VALUE;
	/** Calls a registered engine's texture/bitmap method {@code tag} with {@code object}; NOT_OURS when the engine is ours. */
	private int foreign(int engineRef,int tag,int object) throws M68kException {
		Engine e=engine(engineRef);
		if(e==null||e.own())return e==null&&!textures.containsKey(object)?PARAM_ERR:(tag==5||tag==8)?NO_ERR:NOT_OURS;
		int m=e.method(tag);if(m==0)return NOT_SUPPORTED;
		currentEngineRefCon=e.refCon;return rt.cpu.call(m,object);
	}
	private int enable(int vendor,int id,boolean on) throws M68kException {
		ensureEngines();int result=ERROR;
		for(Engine e:engines)if(engineId(e,2)==vendor&&engineId(e,3)==id){e.enabled=on;result=NO_ERR;}
		return result;
	}
	private int registerEngine(int getMethod,int refCon) throws M68kException {
		if(getMethod==0)return PARAM_ERR;
		ensureEngines();
		Engine e=new Engine(rt.allocate(16,4),getMethod,refCon);engines.add(e);
		System.err.println("[rave] engine registered: "+engineName(e));
		return NO_ERR;
	}
	private String engineName(Engine e) throws M68kException {
		int out=rt.allocate(256,4);try{memory.writeByte(out,0);gestalt(e,6,out);return rt.cString(out);}finally{free(out);}
	}
	private void free(int p){try{if(p!=0&&memory.isAllocated(p))memory.free(p);}catch(RuntimeException ignored){}}

	// ================================================================== draw contexts

	/** A draw context of our engine. */
	private final class Context {
		final int ref,deviceType,device,gDevice,left,top,flags,clipRgn;
		final int memRowBytes,memPixelType,memBase,memWidth,memHeight;
		final RaveRenderer r;
		/** The context's tags (float tags as their bits), as QAGet* answer them. */
		final int[] tags=new int[160];
		int texture,multiTexture;
		final int[] noticeProc=new int[5],noticeRefCon=new int[5];
		/** The trimesh vertices last submitted (TQAVGouraud / TQAVTexture floats). */
		float[] meshGouraud=new float[0],meshTexture=new float[0];int meshGouraudCount,meshTextureCount;
		/** Guest image of the colour buffer (ARGB32): the GWorld CopyBits shows on a GDevice, else a block for callbacks. */
		int gworld,pixMap,bufferBase,bufferRowBytes;
		/** A host context's presenter (QuickDraw 3D shows its frames itself). */
		HostPresenter presenter;
		Context(int ref,int device,int left,int top,int width,int height,int flags,int clipRgn) throws M68kCpuException {
			this.ref=ref;this.device=device;this.left=left;this.top=top;this.flags=flags;this.clipRgn=clipRgn;
			deviceType=memory.readLong(device);
			gDevice=deviceType==DEVICE_GDEVICE?memory.readLong(device+4):0;
			memRowBytes=deviceType==DEVICE_MEMORY?memory.readLong(device+4):0;memPixelType=deviceType==DEVICE_MEMORY?memory.readLong(device+8):0;
			memBase=deviceType==DEVICE_MEMORY?memory.readLong(device+20):0;
			memWidth=deviceType==DEVICE_MEMORY?memory.readLong(device+12):0;memHeight=deviceType==DEVICE_MEMORY?memory.readLong(device+16):0;
			r=new RaveRenderer(width,height,(flags&CONTEXT_NO_Z)==0);
			tags[0]=r.zFunction;tags[5]=Float.floatToIntBits(1);tags[6]=Float.floatToIntBits(Z_MIN_OFFSET);tags[7]=Float.floatToIntBits(1);
			tags[8]=1;tags[27]=15;tags[28]=1;tags[52]=tags[53]=Float.floatToIntBits(1);
			r.clearColor(0,0,r.width,r.height);r.clearDepth(0,0,r.width,r.height);
		}
		float f(int tag){return Float.intBitsToFloat(tags[tag]);}
	}
	/** The smallest Z step that stays visible, as the software engine answered for kQATag_ZMinOffset. */
	static final float Z_MIN_OFFSET=0x1p-22f;
	private final Map<Integer,Context> contexts=new HashMap<Integer,Context>();
	/** Transition vectors of our draw context methods (made once). */
	private int[] methodVectors;

	private int drawContextNew(int device,int rect,int clip,int engineRef,int flags,int out) throws M68kException {
		ensureEngines();
		if(out==0||device==0||rect==0)return PARAM_ERR;
		memory.writeLong(out,0);
		Engine e=engine(engineRef);if(e==null)return PARAM_ERR;
		int l=memory.readLong(rect),rr=memory.readLong(rect+4),t=memory.readLong(rect+8),b=memory.readLong(rect+12);
		int ref=rt.allocate(8+4*DRAW_METHODS.length,4);
		memory.writeLong(ref+4,VERSION);
		if(!e.own()){ // a registered engine fills the structure in its drawPrivateNew (QARegisterDrawMethod)
			int m=e.method(0);if(m==0){free(ref);return NOT_SUPPORTED;}
			currentEngineRefCon=e.refCon;
			int err=rt.cpu.call(m,ref,device,rect,clip,flags);
			if(err!=0){free(ref);return err;}
			foreignContexts.put(ref,e);memory.writeLong(out,ref);return NO_ERR;
		}
		int err=checkDevice(e,device);if(err!=NO_ERR){free(ref);return err;}
		if(rr-l<=0||b-t<=0||(long)(rr-l)*(b-t)>16_777_216L){free(ref);return PARAM_ERR;}
		// the clip region is copied (as the software engine did): Clock^3 empties the region it passed once the context is made
		int clipRgn=clip!=0&&memory.readLong(clip)==0?memory.readLong(clip+4):0;
		if(clipRgn!=0){int copy=tb("NewRgn");tb("CopyRgn",clipRgn,copy);clipRgn=copy;}
		Context c=new Context(ref,device,l,t,rr-l,b-t,flags,clipRgn);
		if(c.deviceType==DEVICE_GDEVICE&&!ensureBuffer(c)){if(clipRgn!=0)tb("DisposeRgn",clipRgn);free(ref);return OUT_OF_MEMORY;} // the back buffer GWorld
		if(methodVectors==null)makeMethodVectors();
		memory.writeLong(ref,ref); // drawPrivate: the context itself
		for(int i=0;i<methodVectors.length;i++)memory.writeLong(ref+8+4*i,methodVectors[i]);
		contexts.put(ref,c);
		if(PpcRuntime.TRACE||TRACE_NATIVE)System.err.printf("[rave] draw context %08X: %s %dx%d at %d,%d flags %X%s%n",ref,c.deviceType==DEVICE_GDEVICE?"GDevice":"memory type "+c.memPixelType,rr-l,b-t,l,t,flags,
			clipRgn==0?"":String.format(" clip %08X bounds t%d l%d b%d r%d",clipRgn,(short)memory.readWord(memory.readLong(clipRgn)+2),(short)memory.readWord(memory.readLong(clipRgn)+4),(short)memory.readWord(memory.readLong(clipRgn)+6),(short)memory.readWord(memory.readLong(clipRgn)+8)));
		memory.writeLong(out,ref);
		return NO_ERR;
	}
	private final Map<Integer,Engine> foreignContexts=new HashMap<Integer,Engine>();
	/** -Dhzppc.rave.trace=true: our engine logs its contexts. */
	private static final boolean TRACE_NATIVE=Boolean.getBoolean("hzppc.rave.trace");
	private void drawContextDelete(int ref) throws M68kException {
		Engine fe=foreignContexts.remove(ref);
		if(fe!=null){int m=fe.method(1);if(m!=0)rt.cpu.call(m,memory.readLong(ref));free(ref);return;}
		Context c=contexts.remove(ref);if(c==null)return;
		if(c.clipRgn!=0)tb("DisposeRgn",c.clipRgn);
		if(c.gworld!=0)tb("DisposeGWorld",c.gworld);
		else if(c.bufferBase!=0)free(c.bufferBase);
		free(ref);
	}

	private Context ctx() throws M68kCpuException {return contexts.get(arg(0));}
	private void makeMethodVectors() throws M68kCpuException {
		PpcRuntime.Native[] n=new PpcRuntime.Native[DRAW_METHODS.length];
		n[0]=(r,c)->{Context x=ctx();if(x!=null)setTag(x,arg(1),Float.floatToIntBits((float)r.farg(0)));};                 // setFloat
		n[1]=(r,c)->{Context x=ctx();if(x!=null)setTag(x,arg(1),arg(2));};                                                    // setInt
		n[2]=(r,c)->{Context x=ctx();if(x!=null)setPtr(x,arg(1),arg(2));};                                                    // setPtr
		n[3]=(r,c)->{Context x=ctx();int tag=arg(1);r.retD(x==null||tag<0||tag>=x.tags.length?0:x.f(tag));};                 // getFloat
		n[4]=(r,c)->{Context x=ctx();int tag=arg(1);ret(x==null||tag<0||tag>=x.tags.length?0:x.tags[tag]);};                  // getInt
		n[5]=(r,c)->{Context x=ctx();int tag=arg(1);ret(x==null?0:tag==13?x.texture:tag==26?x.multiTexture:0);};              // getPtr
		n[6]=(r,c)->{Context x=ctx();if(x!=null){float[] v=vertices(arg(1),1,RaveRenderer.GOURAUD);x.r.point(v,0);}};         // drawPoint
		n[7]=(r,c)->{Context x=ctx();if(x!=null){float[] v=vertices2(arg(1),arg(2));x.r.line(v,0,RaveRenderer.GOURAUD);}};    // drawLine
		n[8]=(r,c)->{Context x=ctx();if(x!=null){float[] v=vertices3(arg(1),arg(2),arg(3),RaveRenderer.GOURAUD);x.r.triGouraud(v,0,8,16);}};
		n[9]=(r,c)->{Context x=ctx();if(x!=null){prepareTexture(x);float[] v=vertices3(arg(1),arg(2),arg(3),RaveRenderer.TEXTURE);x.r.triTexture(v,0,16,32);}};
		n[10]=(r,c)->{Context x=ctx();if(x!=null)drawV(x,arg(1),arg(2),arg(3),arg(4),false);};                                 // drawVGouraud
		n[11]=(r,c)->{Context x=ctx();if(x!=null){prepareTexture(x);drawV(x,arg(1),arg(2),arg(3),arg(4),true);}};              // drawVTexture
		n[12]=(r,c)->{Context x=ctx();if(x!=null){float[] v=vertices(arg(1),1,RaveRenderer.GOURAUD);x.r.bitmap(v,0,textures.get(arg(2)));}};
		n[13]=(r,c)->{Context x=ctx();if(x!=null)renderStart(x,arg(2));};                                                      // renderStart
		n[14]=(r,c)->{Context x=ctx();ret(x==null?PARAM_ERR:renderEnd(x));};                                                  // renderEnd
		n[15]=(r,c)->ret(NO_ERR);                                                                                              // renderAbort
		n[16]=(r,c)->ret(NO_ERR);                                                                                              // flush
		n[17]=(r,c)->ret(NO_ERR);                                                                                              // sync
		n[18]=(r,c)->{Context x=ctx();if(x!=null){int count=arg(1);x.meshGouraud=readFloats(arg(2),count*RaveRenderer.GOURAUD,x.meshGouraud);x.meshGouraudCount=count;}};
		n[19]=(r,c)->{Context x=ctx();if(x!=null){int count=arg(1);x.meshTexture=readFloats(arg(2),count*RaveRenderer.TEXTURE,x.meshTexture);x.meshTextureCount=count;}};
		n[20]=(r,c)->{Context x=ctx();if(x!=null)drawMesh(x,arg(1),arg(2),false);};                                            // drawTriMeshGouraud
		n[21]=(r,c)->{Context x=ctx();if(x!=null){prepareTexture(x);drawMesh(x,arg(1),arg(2),true);}};                         // drawTriMeshTexture
		n[22]=(r,c)->{Context x=ctx();int m=arg(1);if(x==null||m<0||m>=5){ret(PARAM_ERR);return;}x.noticeProc[m]=arg(2);x.noticeRefCon[m]=arg(3);ret(NO_ERR);};
		n[23]=(r,c)->{Context x=ctx();int m=arg(1);if(x==null||m<0||m>=5){ret(PARAM_ERR);return;}if(arg(2)!=0)memory.writeLong(arg(2),x.noticeProc[m]);if(arg(3)!=0)memory.writeLong(arg(3),x.noticeRefCon[m]);ret(NO_ERR);};
		n[24]=(r,c)->{};                                                                                                       // submitMultiTextureParams
		n[25]=(r,c)->{Context x=ctx();if(x==null){ret(PARAM_ERR);return;}exposeBuffer(x);if(x.bufferBase==0){ret(OUT_OF_MEMORY);return;}int pb=arg(1);
			memory.writeLong(pb,x.bufferRowBytes);memory.writeLong(pb+4,PIXEL_ARGB32);memory.writeLong(pb+8,x.r.width);memory.writeLong(pb+12,x.r.height);memory.writeLong(pb+16,x.bufferBase);ret(NO_ERR);};
		n[26]=(r,c)->{Context x=ctx();if(x==null){ret(PARAM_ERR);return;}absorbBuffer(x);ret(NO_ERR);};                       // accessDrawBufferEnd
		n[27]=(r,c)->ret(NOT_SUPPORTED);n[28]=(r,c)->ret(NOT_SUPPORTED);                                                       // accessZBuffer(End)
		n[29]=(r,c)->{Context x=ctx();if(x!=null)clearRect(x,arg(1),arg(2),true,false);ret(NO_ERR);};                           // clearDrawBuffer
		n[30]=(r,c)->{Context x=ctx();if(x!=null)clearRect(x,arg(1),arg(2),false,true);ret(NO_ERR);};                           // clearZBuffer
		n[31]=(r,c)->{Context x=ctx();ret(x==null?PARAM_ERR:imageFromContext(x,arg(2),false));};                                 // textureFromContext
		n[32]=(r,c)->{Context x=ctx();ret(x==null?PARAM_ERR:imageFromContext(x,arg(2),true));};                                  // bitmapFromContext
		n[33]=(r,c)->ret(0);                                                                                                   // busy
		n[34]=(r,c)->{Context x=ctx();if(x!=null)present(x);ret(NO_ERR);};                                                      // swapBuffers
		methodVectors=new int[n.length];
		for(int i=0;i<n.length;i++)methodVectors[i]=rt.nativeVector("RAVE "+DRAW_METHODS[i],n[i]);
	}

	/** Sets a tag and the renderer state it controls. */
	private static final boolean TAG_TRACE=Boolean.getBoolean("hzppc.rave.tagtrace");
	private void setTag(Context x,int tag,int v){
		if(TAG_TRACE&&tag>=17&&tag<=25)System.err.printf("[rave] tag %d = %d / %g%n",tag,v,Float.intBitsToFloat(v));
		if(tag<0||tag>=x.tags.length||tag==6||tag==7)return; // ZMinOffset/ZMinScale are read-only
		x.tags[tag]=v;RaveRenderer r=x.r;float f=Float.intBitsToFloat(v);
		switch(tag){
			case 0:r.zFunction=r.depth==null?RaveRenderer.Z_NONE:v;break;
			case 1:r.bgA=f;break;case 2:r.bgR=f;break;case 3:r.bgG=f;break;case 4:r.bgB=f;break;
			case 5:r.lineWidth=f;break;
			case 9:r.blend=v==0?RaveRenderer.BLEND_PREMULTIPLY:RaveRenderer.BLEND_INTERPOLATE;break;
			case 10:r.perspectiveZ=v!=0;break;
			case 11:case 103:case 104:r.textureFilter=x.tags[11]!=0||x.tags[103]!=0||x.tags[104]!=0?1:0;break;
			case 12:r.textureOp=v;break;
			case 17:r.fogMode=v;break;
			case 18:r.fogA=f;break;case 19:r.fogR=f;break;case 20:r.fogG=f;break;case 21:r.fogB=f;break;
			case 22:r.fogStart=f;break;case 23:r.fogEnd=f;break;case 24:r.fogDensity=f;break;case 25:r.fogMaxDepth=f;break;
			case 27:r.channelMask=v&15;break;
			case 28:r.zWrite=v!=0;break;
			case 30:r.chromakey=v!=0;break;
			case 31:r.alphaTest=v;break;
			case 41:r.mipmapBias=f;break;
			case 43:r.chromaR=f;break;case 44:r.chromaG=f;break;case 45:r.chromaB=f;break;
			case 46:r.alphaTestRef=f;break;
			case 52:r.bitmapScaleX=f;break;case 53:r.bitmapScaleY=f;break;
			case 101:r.wrapU=v==0;break;case 102:r.wrapV=v==0;break;
			case 105:case 106:case 107:case 108:{int x0=x.tags[105],y0=x.tags[106],x1=x.tags[107],y1=x.tags[108];
				boolean on=x1>x0&&y1>y0;r.scissorLeft=on?Math.max(0,x0):0;r.scissorTop=on?Math.max(0,y0):0;
				r.scissorRight=on?Math.min(r.width,x1):r.width;r.scissorBottom=on?Math.min(r.height,y1):r.height;break;}
			case 151:r.envR=f;break;case 152:r.envG=f;break;case 153:r.envB=f;break;
			default:break;
		}
	}
	private void setPtr(Context x,int tag,int p){
		if(tag==13){x.texture=p;x.r.texture=p==0?null:textures.get(p);}
		else if(tag==26)x.multiTexture=p;
	}

	// ---- vertices

	private final float[] vbuf=new float[48];
	/** Reads {@code count} floats at {@code address} into {@code into} (grown as needed). */
	private float[] readFloats(int address,int count,float[] into) throws M68kCpuException {
		if(into.length<count)into=new float[Math.max(count,into.length*2)];
		if(count<=0)return into;
		byte[] m=memory.raw();int p=memory.physical(address);
		if(address==0||p+4L*count>m.length)throw new M68kCpuException(address,"RAVE vertices outside memory");
		for(int i=0;i<count;i++,p+=4)into[i]=Float.intBitsToFloat((m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff);
		return into;
	}
	private float[] vertices(int a,int count,int stride) throws M68kCpuException {return readFloats(a,count*stride,vbuf);}
	private final float[] one=new float[16];
	private float[] vertices2(int a,int b) throws M68kCpuException {
		readFloats(a,8,one);System.arraycopy(one,0,vbuf,0,8);
		readFloats(b,8,one);System.arraycopy(one,0,vbuf,8,8);
		return vbuf;
	}
	private float[] vertices3(int a,int b,int c,int stride) throws M68kCpuException {
		readFloats(a,stride,one);System.arraycopy(one,0,vbuf,0,stride);
		readFloats(b,stride,one);System.arraycopy(one,0,vbuf,stride,stride);
		readFloats(c,stride,one);System.arraycopy(one,0,vbuf,2*stride,stride);
		return vbuf;
	}
	private float[] vArray=new float[0];
	/** QADrawVGouraud / QADrawVTexture: points, lines, a polyline, triangles, a strip or a fan. */
	private void drawV(Context x,int n,int mode,int vertices,int flags,boolean textured) throws M68kCpuException {
		if(n<=0||vertices==0)return;
		int s=textured?RaveRenderer.TEXTURE:RaveRenderer.GOURAUD;
		float[] v=vArray=readFloats(vertices,n*s,vArray);RaveRenderer r=x.r;
		switch(mode){
			case 0:for(int i=0;i<n;i++)r.point(v,i*s);break;
			case 1:for(int i=0;i+1<n;i+=2)r.line(v,i*s,(i+1)*s);break;
			case 2:for(int i=0;i+1<n;i++)r.line(v,i*s,(i+1)*s);break;
			case 3:for(int i=0;i+2<n;i+=3)tri(r,v,i*s,(i+1)*s,(i+2)*s,textured);break;
			case 4:for(int i=0;i+2<n;i++)tri(r,v,i*s,(i+1)*s,(i+2)*s,textured);break;
			case 5:for(int i=1;i+1<n;i++)tri(r,v,0,i*s,(i+1)*s,textured);break;
			default:break;
		}
	}
	private static void tri(RaveRenderer r,float[] v,int a,int b,int c,boolean textured){if(textured)r.triTexture(v,a,b,c);else r.triGouraud(v,a,b,c);}
	/** QADrawTriMesh*: TQAIndexedTriangle entries (flags, three indices) into the vertices last submitted. */
	private void drawMesh(Context x,int n,int triangles,boolean textured) throws M68kCpuException {
		if(n<=0||triangles==0)return;
		byte[] m=memory.raw();int p=memory.physical(triangles);
		if(p+16L*n>m.length)throw new M68kCpuException(triangles,"RAVE triangle mesh outside memory");
		float[] v=textured?x.meshTexture:x.meshGouraud;int count=textured?x.meshTextureCount:x.meshGouraudCount;int s=textured?RaveRenderer.TEXTURE:RaveRenderer.GOURAUD;
		RaveRenderer r=x.r;
		for(int i=0;i<n;i++,p+=16){
			int a=u4(m,p+4),b=u4(m,p+8),c=u4(m,p+12);
			if(a<0||b<0||c<0||a>=count||b>=count||c>=count)continue;
			tri(r,v,a*s,b*s,c*s,textured);
		}
	}
	private static int u4(byte[] m,int p){return (m[p]&0xff)<<24|(m[p+1]&0xff)<<16|(m[p+2]&0xff)<<8|m[p+3]&0xff;}

	// ---- rendering

	private void renderStart(Context x,int initial) throws M68kException {
		Context from=initial==0?null:contexts.get(initial);
		RaveRenderer r=x.r;
		if(from!=null)r.copyFrom(from.r,0,0,r.width,r.height,true,true);
		else{r.clearColor(0,0,r.width,r.height);r.clearDepth(0,0,r.width,r.height);}
		frame++;
		if(x.noticeProc[3]!=0)bufferNotice(x,3);
	}
	/** The frame count (textures made with kQATexture_NoCopy are read again once a frame). */
	private int frame;
	private int renderEnd(Context x) throws M68kException {
		if(x.noticeProc[4]!=0)bufferNotice(x,4);
		if(x.tags[32]==0)present(x); // kQATag_DontSwap
		if(x.noticeProc[0]!=0)rt.cpu.call(x.noticeProc[0],x.ref,x.noticeRefCon[0]);
		return NO_ERR;
	}
	/** A buffer notice (ImageBufferInitialize / ImageBuffer2DComposite): the program draws into the buffer as a memory device. */
	private void bufferNotice(Context x,int method) throws M68kException {
		exposeBuffer(x);if(x.bufferBase==0)return;
		int dev=rt.allocate(24+16,4);
		try{
			memory.writeLong(dev,DEVICE_MEMORY);memory.writeLong(dev+4,x.bufferRowBytes);memory.writeLong(dev+8,PIXEL_ARGB32);
			memory.writeLong(dev+12,x.r.width);memory.writeLong(dev+16,x.r.height);memory.writeLong(dev+20,x.bufferBase);
			int rect=dev+24;memory.writeLong(rect,0);memory.writeLong(rect+4,x.r.width);memory.writeLong(rect+8,0);memory.writeLong(rect+12,x.r.height);
			rt.cpu.call(x.noticeProc[method],x.ref,dev,rect,x.noticeRefCon[method]);
		}finally{free(dev);}
		absorbBuffer(x);
	}
	private void clearRect(Context x,int rect,int initial,boolean colour,boolean z) throws M68kCpuException {
		RaveRenderer r=x.r;int l=0,t=0,rr=r.width,b=r.height;
		if(rect!=0){l=memory.readLong(rect)-x.left;rr=memory.readLong(rect+4)-x.left;t=memory.readLong(rect+8)-x.top;b=memory.readLong(rect+12)-x.top;}
		Context from=initial==0?null:contexts.get(initial);
		if(from!=null)r.copyFrom(from.r,l,t,rr,b,colour,z);
		else{if(colour)r.clearColor(l,t,rr,b);if(z)r.clearDepth(l,t,rr,b);}
	}

	/** Makes the guest image of the colour buffer exist (a GWorld for a GDevice context) and fills it from the buffer. */
	private void exposeBuffer(Context x) throws M68kException {
		if(!ensureBuffer(x))return;
		RaveRenderer r=x.r;int w=r.width,h=r.height,rb=x.bufferRowBytes;
		byte[] b=rowBytes=rowBytes.length>=rb*h?rowBytes:new byte[rb*h];
		int[] c=r.color;
		for(int y=0;y<h;y++){int o=y*rb,i=y*w;for(int xx=0;xx<w;xx++,o+=4){int v=c[i+xx];b[o]=(byte)(v>>>24);b[o+1]=(byte)(v>>>16);b[o+2]=(byte)(v>>>8);b[o+3]=(byte)v;}}
		memory.load(x.bufferBase,b,0,rb*h);
	}
	private byte[] rowBytes=new byte[0];
	/** Reads the guest image back into the colour buffer (after the program drew into it). */
	private void absorbBuffer(Context x) throws M68kCpuException {
		if(x.bufferBase==0)return;
		RaveRenderer r=x.r;int w=r.width,h=r.height,rb=x.bufferRowBytes;
		byte[] m=memory.raw();int base=memory.physical(x.bufferBase);if(base+(long)rb*h>m.length)return;
		for(int y=0;y<h;y++){int o=base+y*rb,i=y*w;for(int xx=0;xx<w;xx++,o+=4)r.color[i+xx]=(m[o]&0xff)<<24|(m[o+1]&0xff)<<16|(m[o+2]&0xff)<<8|m[o+3]&0xff;}
	}
	/** The guest image of the colour buffer; false when there is no memory for it. */
	private boolean ensureBuffer(Context x) throws M68kException {
		if(x.bufferBase!=0)return true;
		int w=x.r.width,h=x.r.height;
		if(x.deviceType==DEVICE_GDEVICE||x.deviceType==DEVICE_HOST){
			int out=rt.allocate(4+8,4);
			try{
				int rect=out+4;memory.writeWord(rect,0);memory.writeWord(rect+2,0);memory.writeWord(rect+4,h);memory.writeWord(rect+6,w);
				int err=tb("NewGWorld",out,32,rect,0,0,0);
				if(err!=0)err=tb("NewGWorld",out,32,rect,0,0,4); // useTempMem
				if(err!=0){System.err.println("[rave] no memory for a "+w+"x"+h+" back buffer (NewGWorld "+err+")");return false;}
				x.gworld=memory.readLong(out);
			}finally{free(out);}
			x.pixMap=tb("GetGWorldPixMap",x.gworld);tb("LockPixels",x.pixMap);
			x.bufferBase=tb("GetPixBaseAddr",x.pixMap);x.bufferRowBytes=memory.readWord(memory.readLong(x.pixMap)+4)&0x3fff;
		}else{
			x.bufferRowBytes=w*4;x.bufferBase=rt.allocate(w*4*h,4);
		}
		return x.bufferBase!=0;
	}

	/** Shows the colour buffer: CopyBits onto a GDevice (clipped to the context's clip region), or into a memory device. */
	private void present(Context x) throws M68kException {
		RaveRenderer r=x.r;
		if(x.deviceType==DEVICE_MEMORY){writeMemoryDevice(x);return;}
		if(x.deviceType==DEVICE_HOST){if(x.presenter!=null)x.presenter.present();return;}
		exposeBuffer(x);
		int gd=memory.readLong(x.gDevice);if(gd==0)return;
		int pmh=memory.readLong(gd+22);if(pmh==0||memory.readLong(pmh)==0)return;
		int save=rt.allocate(8+16,4);
		try{
			tb("GetGWorld",save,save+4);
			tb("SetGWorld",x.gworld,0);
			int src=save+8,dst=save+16;
			memory.writeWord(src,0);memory.writeWord(src+2,0);memory.writeWord(src+4,r.height);memory.writeWord(src+6,r.width);
			memory.writeWord(dst,x.top);memory.writeWord(dst+2,x.left);memory.writeWord(dst+4,x.top+r.height);memory.writeWord(dst+6,x.left+r.width);
			tb("CopyBits",memory.readLong(x.pixMap),memory.readLong(pmh),src,dst,0,x.clipRgn);
			tb("SetGWorld",memory.readLong(save),memory.readLong(save+4));
		}finally{free(save);}
	}
	/** Writes the colour buffer into a memory device in its pixel type (rows of the context's rectangle). */
	private void writeMemoryDevice(Context x) throws M68kCpuException {
		RaveRenderer r=x.r;int pt=x.memPixelType,rb=x.memRowBytes;
		int bpp=pt==PIXEL_RGB16||pt==PIXEL_ARGB16?2:4;
		int composite=x.tags[16];
		// the part of the context's rectangle inside the pixmap
		int x0=Math.max(0,-x.left),x1=Math.min(r.width,x.memWidth-x.left),y0=Math.max(0,-x.top),y1=Math.min(r.height,x.memHeight-x.top),w=x1-x0;
		if(w<=0||y1<=y0||x.memBase==0)return;
		byte[] row=rowBytes=rowBytes.length>=w*bpp?rowBytes:new byte[w*bpp];
		for(int y=y0;y<y1;y++){
			int address=x.memBase+(x.top+y)*rb+(x.left+x0)*bpp;
			if(composite!=0)memory.read(address,row,0,w*bpp);
			for(int i=0,o=0;i<w;i++,o+=bpp){
				int c=r.color[y*r.width+x0+i];
				if(composite!=0){int d=bpp==2?from16((row[o]&0xff)<<8|row[o+1]&0xff,pt==PIXEL_ARGB16):(row[o]&0xff)<<24|(row[o+1]&0xff)<<16|(row[o+2]&0xff)<<8|row[o+3]&0xff;c=composite(c,d,composite);}
				if(bpp==2){int v=((c>>>19)&31)<<10|((c>>>11)&31)<<5|(c>>>3)&31;if(pt==PIXEL_ARGB16&&(c>>>24)>=128)v|=0x8000;row[o]=(byte)(v>>>8);row[o+1]=(byte)v;}
				else{if(pt==PIXEL_RGB32)c&=0xffffff;row[o]=(byte)(c>>>24);row[o+1]=(byte)(c>>>16);row[o+2]=(byte)(c>>>8);row[o+3]=(byte)c;}
			}
			memory.load(address,row,0,w*bpp);
		}
	}
	private static int from16(int v,boolean alpha){int r=(v>>>10)&31,g=(v>>>5)&31,b=v&31;return (alpha&&(v&0x8000)==0?0:0xff000000)|(r<<3|r>>>2)<<16|(g<<3|g>>>2)<<8|(b<<3|b>>>2);}
	/** kQATag_BufferComposite: the rendered pixel over what the device held (1 premultiplied, 2 interpolated). */
	private static int composite(int s,int d,int mode){
		float a=(s>>>24)/255f,ia=1-a;int out=0xff000000;
		for(int sh=0;sh<24;sh+=8){float sc=(s>>>sh)&255,dc=(d>>>sh)&255;float v=mode==1?sc+ia*dc:a*sc+ia*dc;out|=Math.min(255,Math.round(v))<<sh;}
		return out;
	}

	// ================================================================== textures, bitmaps, colour tables

	/** A texture or bitmap of our engine, with what kQATexture_NoCopy needs to read it again. */
	private final Map<Integer,RaveRenderer.Texture> textures=new HashMap<Integer,RaveRenderer.Texture>();
	private final Map<RaveRenderer.Texture,int[]> noCopy=new HashMap<RaveRenderer.Texture,int[]>();
	private final Map<RaveRenderer.Texture,Integer> textureFrame=new HashMap<RaveRenderer.Texture,Integer>();
	private final Map<Integer,int[]> colorTables=new HashMap<Integer,int[]>();

	private int colorTableNew(int engineRef,int type,int data,int transparent,int out) throws M68kException {
		Engine e=engine(engineRef);if(e==null||out==0)return PARAM_ERR;
		if(!e.own()){int m=e.method(10);return m==0?NOT_SUPPORTED:rt.cpu.call(m,type,data,transparent,out);}
		int n=type==0?256:type==1?16:0;if(n==0||data==0)return PARAM_ERR;
		int[] t=new int[n];for(int i=0;i<n;i++)t[i]=0xff000000|memory.readLong(data+4*i)&0xffffff;
		if(transparent!=0)t[0]&=0xffffff;
		int ref=rt.allocate(8,4);colorTables.put(ref,t);memory.writeLong(out,ref);return NO_ERR;
	}
	private int bindColorTable(int engineRef,int image,int table,int tag) throws M68kException {
		Engine e=engine(engineRef);
		if(e!=null&&!e.own()){int m=e.method(tag);return m==0?NOT_SUPPORTED:rt.cpu.call(m,image,table);}
		RaveRenderer.Texture t=textures.get(image);int[] ct=colorTables.get(table);
		if(t==null||ct==null)return PARAM_ERR;
		t.bind(ct);return NO_ERR;
	}
	/** QATextureNew / QABitmapNew: the image(s) converted to ARGB (a mipmapped texture's levels down to 1x1). */
	private int imageNew(int engineRef,int flags,int pixelType,int images,int out,boolean bitmap) throws M68kException {
		Engine e=engine(engineRef);if(e==null||out==0||images==0)return PARAM_ERR;
		if(!e.own()){int m=e.method(bitmap?7:4);return m==0?NOT_SUPPORTED:rt.cpu.call(m,flags,pixelType,images,out);}
		if(pixelType<0||pixelType>PIXEL_AI16_88)return NOT_SUPPORTED;
		int levels=1;
		if(!bitmap&&(flags&TEXTURE_MIPMAP)!=0){int w=memory.readLong(images),h=memory.readLong(images+4);while(w>1||h>1){w=Math.max(1,w/2);h=Math.max(1,h/2);levels++;}}
		RaveRenderer.Texture t=new RaveRenderer.Texture(levels);
		t.mask=bitmap&&pixelType==PIXEL_ALPHA1;
		int[] spec=new int[]{flags,pixelType,images,levels};
		if(!readImages(t,spec))return PARAM_ERR;
		if((flags&TEXTURE_NO_COPY)!=0)noCopy.put(t,spec);
		int ref=rt.allocate(8,4);textures.put(ref,t);memory.writeLong(out,ref);
		return NO_ERR;
	}
	private boolean readImages(RaveRenderer.Texture t,int[] spec) throws M68kCpuException {
		int flags=spec[0],pixelType=spec[1],images=spec[2];
		for(int l=0;l<t.levels();l++){
			int img=images+16*l;
			int w=memory.readLong(img),h=memory.readLong(img+4),rb=memory.readLong(img+8),px=memory.readLong(img+12);
			if(w<=0||h<=0||px==0||(long)w*h>16_777_216L)return false;
			t.widths[l]=w;t.heights[l]=h;
			int[] p=t.pixels[l]==null||t.pixels[l].length!=w*h?new int[w*h]:t.pixels[l];
			boolean indexed=pixelType==PIXEL_CL4||pixelType==PIXEL_CL8||pixelType==PIXEL_ACL16_88;
			int[] ix=indexed?new int[w*h]:null;
			byte[] b=memory.readBytes(px,Math.max(rb,1)*(h-1)+rowLength(pixelType,w));
			for(int y=0;y<h;y++){int row=((flags&TEXTURE_FLIP)!=0?h-1-y:y)*rb;
				for(int x=0;x<w;x++)if(indexed)ix[y*w+x]=index(b,row,x,pixelType);else p[y*w+x]=pixel(b,row,x,pixelType);}
			t.pixels[l]=p;t.indices[l]=ix;
			if(indexed)for(int i=0;i<ix.length;i++){int g=ix[i]&0xff;p[i]=(ix[i]>=0x100?ix[i]>>>8:255)<<24|g<<16|g<<8|g;} // grey until a table is bound
		}
		t.updateAlpha();
		return true;
	}
	private static int rowLength(int type,int w){
		switch(type){case PIXEL_ALPHA1:return (w+7)/8;case PIXEL_CL4:return (w+1)/2;case PIXEL_CL8:case PIXEL_RGB8_332:case PIXEL_I8:return w;
			case PIXEL_RGB24:return 3*w;case PIXEL_RGB32:case PIXEL_ARGB32:return 4*w;default:return 2*w;}
	}
	private static int b2(byte[] b,int o){return (b[o]&0xff)<<8|b[o+1]&0xff;}
	private static int index(byte[] b,int row,int x,int type){
		switch(type){
			case PIXEL_CL4:{int v=b[row+x/2]&0xff;return (x&1)==0?v>>>4:v&15;}
			case PIXEL_CL8:return b[row+x]&0xff;
			default:{int v=b2(b,row+2*x);return (v>>>8)<<8|v&0xff|0x10000;} // ACL16_88: alpha 8-15, index 0-7 (bit 16 marks an alpha)
		}
	}
	private static int x5(int v){return v<<3|v>>>2;}
	private static int x4(int v){return v<<4|v;}
	/** One pixel of a texture or bitmap image as ARGB. */
	private static int pixel(byte[] b,int row,int x,int type){
		switch(type){
			case PIXEL_ALPHA1:return ((b[row+x/8]>>>(7-(x&7)))&1)!=0?0xffffffff:0x00ffffff;
			case PIXEL_RGB16:case PIXEL_ARGB16:{int v=b2(b,row+2*x);return (type==PIXEL_ARGB16&&(v&0x8000)==0?0:0xff000000)|x5((v>>>10)&31)<<16|x5((v>>>5)&31)<<8|x5(v&31);}
			case PIXEL_RGB32:case PIXEL_ARGB32:{int o=row+4*x;int v=(b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;return type==PIXEL_RGB32?v|0xff000000:v;}
			case PIXEL_RGB16_565:{int v=b2(b,row+2*x);int g=(v>>>5)&63;return 0xff000000|x5(v>>>11)<<16|(g<<2|g>>>4)<<8|x5(v&31);}
			case PIXEL_RGB24:{int o=row+3*x;return 0xff000000|(b[o]&0xff)<<16|(b[o+1]&0xff)<<8|b[o+2]&0xff;}
			case PIXEL_RGB8_332:{int v=b[row+x]&0xff;int r=v>>>5,g=(v>>>2)&7,bl=v&3;return 0xff000000|(r*255/7)<<16|(g*255/7)<<8|bl*85;}
			case PIXEL_ARGB16_4444:{int v=b2(b,row+2*x);return x4(v>>>12)<<24|x4((v>>>8)&15)<<16|x4((v>>>4)&15)<<8|x4(v&15);}
			case PIXEL_I8:{int v=b[row+x]&0xff;return 0xff000000|v<<16|v<<8|v;}
			case PIXEL_AI16_88:{int v=b2(b,row+2*x);int i=v&0xff;return (v>>>8)<<24|i<<16|i<<8|i;}
			default:return 0xff000000;
		}
	}
	/** A texture made with kQATexture_NoCopy reads its image again (once a frame) when it is chosen: the program may change it. */
	private void prepareTexture(Context x) throws M68kCpuException {
		RaveRenderer.Texture t=x.r.texture;if(t==null||noCopy.isEmpty())return;
		int[] spec=noCopy.get(t);if(spec==null)return;
		Integer seen=textureFrame.get(t);if(seen!=null&&seen==frame)return;
		textureFrame.put(t,frame);readImages(t,spec);
	}
	/** QATextureNewFromDrawContext / QABitmapNewFromDrawContext: an image of the colour buffer. */
	private int imageFromContext(Context x,int out,boolean bitmap) throws M68kCpuException {
		if(out==0)return PARAM_ERR;
		RaveRenderer.Texture t=new RaveRenderer.Texture(1);t.widths[0]=x.r.width;t.heights[0]=x.r.height;t.pixels[0]=x.r.color.clone();t.updateAlpha();
		int ref=rt.allocate(8,4);textures.put(ref,t);memory.writeLong(out,ref);return NO_ERR;
	}

	// ================================================================== contexts of QuickDraw 3D's Interactive Renderer

	/** What shows a host context's frame (the draw context's window or pixmap). */
	interface HostPresenter { void present() throws M68kException; }
	/**
	 * A draw context of our engine that QuickDraw 3D draws into from Java: a real TQADrawContext (a program may set its tags
	 * or draw into it through Q3InteractiveRenderer_GetRAVEDrawContexts), whose frames {@code presenter} shows.
	 */
	int hostContextNew(int width,int height,int flags,HostPresenter presenter) throws M68kException {
		ensureEngines();
		if(width<=0||height<=0||(long)width*height>16_777_216L)return 0;
		int device=rt.allocate(24,4);memory.writeLong(device,DEVICE_HOST);
		int ref=rt.allocate(8+4*DRAW_METHODS.length,4);
		memory.writeLong(ref+4,VERSION);
		Context c=new Context(ref,device,0,0,width,height,flags,0);c.presenter=presenter;
		if(methodVectors==null)makeMethodVectors();
		memory.writeLong(ref,ref);
		for(int i=0;i<methodVectors.length;i++)memory.writeLong(ref+8+4*i,methodVectors[i]);
		contexts.put(ref,c);
		return ref;
	}
	/** The renderer of one of our contexts (null for none). */
	RaveRenderer renderer(int ref){Context c=contexts.get(ref);return c==null?null:c.r;}
	/** Our engine (the one QuickDraw 3D's contexts are made on). */
	int ownEngine() throws M68kCpuException {ensureEngines();return engines.get(0).ref;}
	/** Sets a tag of one of our contexts as QASetInt / QASetFloat would. */
	void setHostTag(int ref,int tag,int bits){Context c=contexts.get(ref);if(c!=null)setTag(c,tag,bits);}
	int hostTag(int ref,int tag){Context c=contexts.get(ref);return c==null||tag<0||tag>=c.tags.length?0:c.tags[tag];}
	/** Puts the renderer state back to what the context's tags say (after QuickDraw 3D changed it for a frame's objects). */
	void resyncTags(int ref){Context c=contexts.get(ref);if(c==null)return;for(int t=0;t<c.tags.length;t++)if(t!=6&&t!=7)setTag(c,t,c.tags[t]);}
	/** Starts a frame of one of our contexts: cleared to its background, or only its depth buffer cleared. */
	void hostStart(int ref,boolean clearColour) throws M68kException {
		Context c=contexts.get(ref);if(c==null)return;
		if(clearColour)c.r.clearColor(0,0,c.r.width,c.r.height);
		c.r.clearDepth(0,0,c.r.width,c.r.height);
		frame++;
		if(c.noticeProc[3]!=0)bufferNotice(c,3);
	}
	/** Ends a frame: the program's buffer composite notice, the presenter, the render completion notice. */
	void hostEnd(int ref) throws M68kException {Context c=contexts.get(ref);if(c!=null)renderEnd(c);}
	void hostDelete(int ref) throws M68kException {Context c=contexts.get(ref);if(c==null)return;int dev=c.device;drawContextDelete(ref);free(dev);}
	/** The context's colour buffer copied into a GWorld: its PixMapHandle (for CopyBits), 0 when there is no memory. */
	int hostPixMap(int ref) throws M68kException {Context c=contexts.get(ref);if(c==null)return 0;exposeBuffer(c);return c.pixMap;}

	// ================================================================== tracing the real library (-Dhzppc.rave=trace)

	/** The TQADrawContext method slots, in order after drawPrivate and version. */
	static final String[] DRAW_METHODS={"setFloat","setInt","setPtr","getFloat","getInt","getPtr","drawPoint","drawLine","drawTriGouraud",
		"drawTriTexture","drawVGouraud","drawVTexture","drawBitmap","renderStart","renderEnd","renderAbort","flush","sync","submitVerticesGouraud",
		"submitVerticesTexture","drawTriMeshGouraud","drawTriMeshTexture","setNoticeMethod","getNoticeMethod","submitMultiTextureParams",
		"accessDrawBuffer","accessDrawBufferEnd","accessZBuffer","accessZBufferEnd","clearDrawBuffer","clearZBuffer","textureFromContext",
		"bitmapFromContext","busy","swapBuffers"};
	private final Map<String,Integer> traceCounts=new HashMap<String,Integer>();
	private final Map<Integer,Integer> tracedVectors=new HashMap<Integer,Integer>();

	/** A logging stand-in for the real library's export {@code name} at transition vector {@code tv}. */
	int traceExport(String name,int tv) throws M68kCpuException {return traceVector(name,tv);}
	private int traceVector(final String name,final int tv) throws M68kCpuException {
		Integer known=tracedVectors.get(tv);if(known!=null)return known;
		int v=rt.nativeVector("RAVE "+name,(r,c)->{
			int[] a=new int[10];for(int i=0;i<a.length;i++)a[i]=r.arg(i);
			double[] f={r.farg(0),r.farg(1),r.farg(2),r.farg(3)};
			int n=traceCounts.merge(name,1,Integer::sum);
			boolean draw=name.startsWith("draw")||name.startsWith("submit");
			boolean log=draw?n<=12:n<=400;
			if(log)System.err.printf("[rave] %s(%s) f1=%s%s%n",name,args(a,6),(float)f[0],draw?vertices(name,a):"");
			int result=c.call(tv,a,f);long f1=c.lastF1;
			c.r[3]=result;c.f[1]=f1;
			if(log&&!draw)System.err.printf("[rave]   %s -> %08X f1=%s%s%n",name,result,(float)Double.longBitsToDouble(f1),details(name,a,result));
			if(name.equals("QADrawContextNew")&&result==0&&a[5]!=0)wrapContext(memory.readLong(a[5]));
		});
		tracedVectors.put(tv,v);tracedVectors.put(v,v);
		return v;
	}
	private static String args(int[] a,int n){StringBuilder sb=new StringBuilder();for(int i=0;i<n;i++){if(i>0)sb.append(' ');sb.append(String.format("%08X",a[i]));}return sb.toString();}
	private float fl(int p) throws M68kCpuException {return Float.intBitsToFloat(memory.readLong(p));}
	private String floats(int p,int n) throws M68kCpuException {StringBuilder sb=new StringBuilder("{");for(int i=0;i<n;i++){if(i>0)sb.append(' ');sb.append(fl(p+4*i));}return sb.append('}').toString();}
	private String vertices(String name,int[] a) throws M68kCpuException {
		switch(name){
			case "drawTriGouraud":return " "+floats(a[1],8)+floats(a[2],8)+floats(a[3],8)+" flags "+a[4];
			case "drawTriTexture":return " "+floats(a[1],17)+floats(a[2],17)+floats(a[3],17)+" flags "+a[4];
			case "drawPoint":return " "+floats(a[1],8);
			case "drawLine":return " "+floats(a[1],8)+floats(a[2],8);
			case "drawVGouraud":return " n="+a[1]+" mode="+a[2]+" "+floats(a[3],8);
			case "drawVTexture":return " n="+a[1]+" mode="+a[2]+" "+floats(a[3],17);
			case "submitVerticesGouraud":return " n="+a[1]+" "+floats(a[2],8);
			case "submitVerticesTexture":return " n="+a[1]+" "+floats(a[2],17);
			case "drawTriMeshGouraud":case "drawTriMeshTexture":return " n="+a[1]+" first "+String.format("%X %d %d %d",memory.readLong(a[2]),memory.readLong(a[2]+4),memory.readLong(a[2]+8),memory.readLong(a[2]+12));
			default:return "";
		}
	}
	/** What a call's pointer arguments held (devices, rectangles, images, gestalt responses). */
	private String details(String name,int[] a,int result) throws M68kCpuException {
		switch(name){
			case "QADrawContextNew":return " device "+device(a[0])+" rect "+qaRect(a[1])+" clip "+(a[2]==0?"none":String.format("type %d rgn %08X",memory.readLong(a[2]),memory.readLong(a[2]+4)))+String.format(" engine %08X flags %X context %08X",a[3],a[4],a[5]==0?0:memory.readLong(a[5]));
			case "QADeviceGetFirstEngine":case "QADeviceGetNextEngine":case "QAEngineCheckDevice":return " device "+device(a[0]);
			case "QAEngineGestalt":return a[2]==0?"":String.format(" selector %d response %08X",a[1],memory.readLong(a[2]))+(a[1]==6?" \""+rt.cString(a[2])+"\"":"");
			case "QATextureNew":case "QABitmapNew":{int img=a[3];return String.format(" flags %X pixelType %d image %dx%d rowBytes %d pixmap %08X out %08X",a[1],a[2],memory.readLong(img),memory.readLong(img+4),memory.readLong(img+8),memory.readLong(img+12),a[4]==0?0:memory.readLong(a[4]));}
			case "renderStart":case "renderEnd":return " rect "+(a[1]==0?"all":qaRect(a[1]))+(name.equals("renderStart")?String.format(" initial %08X",a[2]):"");
			case "setInt":case "getInt":case "setPtr":case "getPtr":case "setFloat":case "getFloat":return " tag "+a[1];
			default:return "";
		}
	}
	private String qaRect(int p) throws M68kCpuException {return p==0?"null":String.format("l%d r%d t%d b%d",memory.readLong(p),memory.readLong(p+4),memory.readLong(p+8),memory.readLong(p+12));}
	private String device(int p) throws M68kCpuException {
		if(p==0)return "null";
		int type=memory.readLong(p);
		if(type==0)return String.format("memory rowBytes %d pixelType %d %dx%d base %08X",memory.readLong(p+4),memory.readLong(p+8),memory.readLong(p+12),memory.readLong(p+16),memory.readLong(p+20));
		return String.format("type %d gDevice %08X",type,memory.readLong(p+4));
	}
	/** Replaces a real draw context's method pointers with logging stand-ins. */
	private void wrapContext(int ctx) throws M68kCpuException {
		int version=memory.readLong(ctx+4);
		int count=version>=4?DRAW_METHODS.length:version>=3?24:version>=2?22:18;
		System.err.printf("[rave] draw context %08X version %d private %08X%n",ctx,version,memory.readLong(ctx));
		for(int i=0;i<count;i++){int slot=ctx+8+4*i,tv=memory.readLong(slot);if(tv!=0)memory.writeLong(slot,traceVector(DRAW_METHODS[i],tv));}
	}
}
