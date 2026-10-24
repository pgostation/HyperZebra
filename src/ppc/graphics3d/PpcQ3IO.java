import java.util.HashMap;
import java.util.Map;

/**
 * Storage and files of QuickDraw 3D ({@link PpcQuickDraw3D}, QD3DStorage.h and QD3DIO.h): memory, handle, file
 * reference and FSSpec storage over guest memory and the File Manager; 3DMF files read with {@link Q3Metafile} and
 * written through a view in writing mode; view hints, unknown objects, and the primitive Read/Write calls of custom
 * classes (which this library, loading no plug-ins, only answers).
 */
final class PpcQ3IO {
	private final PpcQuickDraw3D q;private final M68kMemory memory;private final PpcRuntime rt;
	PpcQ3IO(PpcQuickDraw3D q){this.q=q;this.memory=q.memory;this.rt=q.rt;}
	private int rl(int a) throws M68kCpuException {return memory.readLong(a);}
	private void wl(int a,int v) throws M68kCpuException {memory.writeLong(a,v);}

	// ================================================================== storage kinds

	/** A memory storage over a buffer of the program's (Q3MemoryStorage_NewBuffer with a buffer). */
	final class GuestBuffer extends Q3.Storage {
		int buffer,valid,size;
		GuestBuffer(int buffer,int valid,int size){super(Q3.MEMORY_STORAGE);this.buffer=buffer;this.valid=valid;this.size=size;}
		@Override int size(){return valid;}
		@Override int read(int offset,byte[] into,int at,int n) throws M68kCpuException {if(offset>=valid)return 0;n=Math.min(n,valid-offset);memory.read(buffer+offset,into,at,n);return n;}
		@Override int write(int offset,byte[] from,int at,int n) throws M68kCpuException {
			if(offset+n>size)n=Math.max(0,size-offset);
			if(n>0)memory.load(buffer+offset,from,at,n);valid=Math.max(valid,offset+n);return n;
		}
	}
	/** Handle storage: the bytes of a guest Handle (grown for writing). */
	final class HandleStore extends Q3.Storage {
		int handle,valid;
		HandleStore(int h,int valid){super(Q3.HANDLE_STORAGE);handle=h;this.valid=valid;}
		@Override int size(){return valid;}
		@Override int read(int offset,byte[] into,int at,int n) throws M68kException {
			if(handle==0||offset>=valid)return 0;int p=rl(handle);if(p==0)return 0;n=Math.min(n,valid-offset);memory.read(p+offset,into,at,n);return n;
		}
		@Override int write(int offset,byte[] from,int at,int n) throws M68kException {
			if(handle==0)return 0;
			int have=q.tb("GetHandleSize",handle);
			if(offset+n>have){q.tb("SetHandleSize",handle,Math.max(offset+n,have*2));if(q.tb("GetHandleSize",handle)<offset+n)return 0;}
			memory.load(rl(handle)+offset,from,at,n);valid=Math.max(valid,offset+n);return n;
		}
	}
	/** An open file of the program (Q3MacintoshStorage_New), read and written through the File Manager. */
	class RefStore extends Q3.Storage {
		int refNum;
		RefStore(int leaf,int ref){super(leaf);refNum=ref;}
		FileManager68k fm(){return rt.toolbox.files.fileManager;}
		@Override int size(){return (int)Math.max(0,fm().referenceLength(refNum));}
		@Override int read(int offset,byte[] into,int at,int n) throws Exception {byte[] b=fm().readReference(refNum,offset,n);if(b==null)return 0;System.arraycopy(b,0,into,at,b.length);return b.length;}
		@Override int write(int offset,byte[] from,int at,int n) throws Exception {return fm().writeReference(refNum,offset,java.util.Arrays.copyOfRange(from,at,at+n))?n:0;}
	}
	/** An FSSpec (Q3FSSpecStorage_New): opened with FSpOpenDF while a file uses it. */
	final class SpecStore extends RefStore {
		final byte[] spec;boolean opened;
		SpecStore(byte[] spec){super(Q3.FSSPEC_STORAGE,0);this.spec=spec;}
		@Override void open(boolean write) throws Exception {
			if(opened)return;
			int p=q.alloc(80);
			try{
				memory.load(p,spec);
				int err=q.tb("FSpOpenDF",p,write?3:1,p+72);
				if(err==-43&&write){q.tb("FSpCreate",p,Q3.fc("????"),Q3.fc("3DMF"),0);err=q.tb("FSpOpenDF",p,3,p+72);}
				if(err!=0)throw new java.io.IOException("FSpOpenDF "+err);
				refNum=(short)memory.readWord(p+72);opened=true;
				if(write)fm().writeReference(refNum,0,new byte[0]);
			}finally{q.free(p);}
		}
		@Override void close() throws Exception {
			if(!opened)return;opened=false;
			int pb=q.alloc(80);try{memory.writeWord(pb+24,refNum);q.tb("PBCloseSync",pb);}finally{q.free(pb);}
		}
		@Override int size(){return opened?super.size():0;}
	}
	/** A memory storage's image the program can read (Q3MemoryStorage_GetBuffer of our own buffer), kept per storage. */
	private final Map<Q3.Storage,int[]> exposed=new java.util.WeakHashMap<Q3.Storage,int[]>();

	// ================================================================== files

	static final int MODE_NORMAL=0,MODE_STREAM=1,MODE_DATABASE=2,MODE_TEXT=4;
	/** A file (TQ3FileObject): its storage and, while open, the reader or writer. */
	static final class FileObj extends Q3.Obj {
		Q3.Storage storage;boolean open,writing;int mode;Q3Metafile.Reader reader;Q3Metafile.Writer writer;int readInGroup;int idle,idleData;
		FileObj(){super(Q3.FILE);}
		@Override void dropRefs(){Q3.release(storage);storage=null;if(reader!=null)reader.close();reader=null;super.dropRefs();}
	}
	private FileObj file(PpcQuickDraw3D.A a,int k) throws M68kCpuException {return q.get(a.i(k),FileObj.class);}

	void define(){
		// ---- storage
		q.def("Q3Storage_GetType",a->{Q3.Obj o=q.obj(a.i(0));a.ret(o instanceof Q3.Storage?o.typeAt(2):0);});
		q.def("Q3Storage_GetSize",a->{Q3.Storage s=q.get(a.i(0),Q3.Storage.class);if(s==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),s.size());a.ok(true);});
		q.def("Q3Storage_GetData",a->{Q3.Storage s=q.get(a.i(0),Q3.Storage.class);int off=a.i(1),n=a.i(2),dst=a.i(3),got=a.i(4);if(s==null||dst==0||n<0){a.ok(false);return;}
			byte[] b=new byte[n];int r=s.read(off,b,0,n);memory.load(dst,b,0,Math.max(0,r));if(got!=0)wl(got,Math.max(0,r));a.ok(true);});
		q.def("Q3Storage_SetData",a->{Q3.Storage s=q.get(a.i(0),Q3.Storage.class);int off=a.i(1),n=a.i(2),src=a.i(3),put=a.i(4);if(s==null||src==0||n<0){a.ok(false);return;}
			int w=s.write(off,memory.readBytes(src,n),0,n);s.edited();if(put!=0)wl(put,w);a.ok(w==n);});
		q.def("Q3MemoryStorage_GetType",a->{Q3.Obj o=q.obj(a.i(0));a.ret(o!=null&&o.is(Q3.MEMORY_STORAGE)?o.typeAt(3):0);});
		q.def("Q3MemoryStorage_New",a->{int buf=a.i(0),n=a.i(1);a.ret(q.made(new Q3.ArrayStorage(Q3.MEMORY_STORAGE,buf==0||n<=0?new byte[0]:memory.readBytes(buf,n))));});
		q.def("Q3MemoryStorage_Set",a->{Q3.Obj o=q.obj(a.i(0));int buf=a.i(1),n=a.i(2);
			if(!(o instanceof Q3.ArrayStorage)){q.post(PpcQuickDraw3D.ERR_INVALID_OBJECT);a.ok(false);return;}
			Q3.ArrayStorage s=(Q3.ArrayStorage)o;s.data=buf==0||n<=0?new byte[0]:memory.readBytes(buf,n);s.length=s.data.length;s.edited();a.ok(true);});
		q.def("Q3MemoryStorage_NewBuffer",a->{int buf=a.i(0),valid=a.i(1),size=a.i(2);
			a.ret(q.made(buf==0?new Q3.ArrayStorage(Q3.MEMORY_STORAGE,new byte[0]):new GuestBuffer(buf,valid,size)));});
		q.def("Q3MemoryStorage_SetBuffer",a->{Q3.Obj o=q.obj(a.i(0));int buf=a.i(1),valid=a.i(2),size=a.i(3);
			if(o instanceof GuestBuffer){GuestBuffer g=(GuestBuffer)o;g.buffer=buf;g.valid=valid;g.size=size;g.edited();a.ok(true);return;}
			if(o instanceof Q3.ArrayStorage){Q3.ArrayStorage s=(Q3.ArrayStorage)o;s.data=buf==0?new byte[0]:memory.readBytes(buf,valid);s.length=s.data.length;s.edited();a.ok(true);return;}
			a.ok(false);});
		q.def("Q3MemoryStorage_GetBuffer",a->{Q3.Obj o=q.obj(a.i(0));int bp=a.i(1),vp=a.i(2),sp=a.i(3);
			if(o instanceof GuestBuffer){GuestBuffer g=(GuestBuffer)o;if(bp!=0)wl(bp,g.buffer);if(vp!=0)wl(vp,g.valid);if(sp!=0)wl(sp,g.size);a.ok(true);return;}
			if(o instanceof Q3.ArrayStorage){Q3.ArrayStorage s=(Q3.ArrayStorage)o;
				int[] e=exposed.get(s);if(e==null||e[1]<s.length){if(e!=null)q.free(e[0]);e=new int[]{q.alloc(Math.max(4,s.length)),Math.max(4,s.length)};exposed.put(s,e);}
				memory.load(e[0],s.data,0,s.length);
				if(bp!=0)wl(bp,e[0]);if(vp!=0)wl(vp,s.length);if(sp!=0)wl(sp,e[1]);a.ok(true);return;}
			a.ok(false);});
		q.def("Q3HandleStorage_New",a->a.ret(q.made(new HandleStore(a.i(0),a.i(1)))));
		q.def("Q3HandleStorage_Set",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof HandleStore)){a.ok(false);return;}HandleStore h=(HandleStore)o;h.handle=a.i(1);h.valid=a.i(2);h.edited();a.ok(true);});
		q.def("Q3HandleStorage_Get",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof HandleStore)){a.ok(false);return;}HandleStore h=(HandleStore)o;if(a.i(1)!=0)wl(a.i(1),h.handle);if(a.i(2)!=0)wl(a.i(2),h.valid);a.ok(true);});
		q.def("Q3MacintoshStorage_New",a->a.ret(q.made(new RefStore(Q3.MAC_STORAGE,(short)a.i(0)))));
		q.def("Q3MacintoshStorage_Set",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof RefStore)||o instanceof SpecStore){a.ok(false);return;}((RefStore)o).refNum=(short)a.i(1);o.edited();a.ok(true);});
		q.def("Q3MacintoshStorage_Get",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof RefStore)||a.i(1)==0){a.ok(false);return;}memory.writeWord(a.i(1),((RefStore)o).refNum);a.ok(true);});
		q.def("Q3MacintoshStorage_GetType",a->{Q3.Obj o=q.obj(a.i(0));a.ret(o!=null&&o.is(Q3.MAC_STORAGE)?o.typeAt(3):0);});
		q.def("Q3FSSpecStorage_New",a->{int fs=a.i(0);if(fs==0){a.ret(0);return;}a.ret(q.made(new SpecStore(memory.readBytes(fs,70))));});
		q.def("Q3FSSpecStorage_Set",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof SpecStore)||a.i(1)==0){a.ok(false);return;}System.arraycopy(memory.readBytes(a.i(1),70),0,((SpecStore)o).spec,0,70);o.edited();a.ok(true);});
		q.def("Q3FSSpecStorage_Get",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof SpecStore)||a.i(1)==0){a.ok(false);return;}memory.load(a.i(1),((SpecStore)o).spec);a.ok(true);});
		q.def("Q3UnixPathStorage_New",a->{q.post(PpcQuickDraw3D.ERR_NOT_SUPPORTED);a.ret(0);});
		// ---- files
		q.def("Q3File_New",a->a.ret(q.made(new FileObj())));
		q.def("Q3File_GetStorage",a->{FileObj f=file(a,0);if(f==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.storage==null?0:q.newRef(f.storage));a.ok(true);});
		q.def("Q3File_SetStorage",a->{FileObj f=file(a,0);if(f==null){a.ok(false);return;}if(f.open){q.post(PpcQuickDraw3D.ERR_FILE_IS_OPEN);a.ok(false);return;}
			Q3.Obj s=q.obj(a.i(1));f.storage=Q3.replace(f.storage,s instanceof Q3.Storage?(Q3.Storage)s:null);a.ok(true);});
		q.def("Q3File_OpenRead",a->{FileObj f=file(a,0);int modeOut=a.i(1);if(f==null){a.ok(false);return;}
			if(f.storage==null){q.post(PpcQuickDraw3D.ERR_NO_STORAGE);a.ok(false);return;}
			if(f.open){q.post(-28443);a.ok(false);return;}
			f.storage.open(false);
			byte[] data=f.storage.bytes();
			Q3Metafile.Reader r=Q3Metafile.read(data,t->q.elementSize(t));
			if(r==null){f.storage.close();q.post(PpcQuickDraw3D.ERR_INVALID_METAFILE);a.ok(false);return;}
			f.reader=r;f.open=true;f.writing=false;f.mode=(r.text?MODE_TEXT:0)|(r.mode&3);
			if(modeOut!=0)wl(modeOut,f.mode);
			a.ok(true);});
		q.def("Q3File_OpenWrite",a->{FileObj f=file(a,0);int mode=a.i(1);if(f==null){a.ok(false);return;}
			if(f.storage==null){q.post(PpcQuickDraw3D.ERR_NO_STORAGE);a.ok(false);return;}
			if(f.open){q.post(-28443);a.ok(false);return;}
			f.storage.open(true);
			f.writer=new Q3Metafile.Writer((mode&MODE_TEXT)!=0);f.open=true;f.writing=true;f.mode=mode;a.ok(true);});
		q.def("Q3File_IsOpen",a->{FileObj f=file(a,0);if(f==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.open?1:0);a.ok(true);});
		q.def("Q3File_GetMode",a->{FileObj f=file(a,0);if(f==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.mode);a.ok(true);});
		q.def("Q3File_GetVersion",a->{FileObj f=file(a,0);if(f==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.reader!=null?f.reader.version:0x00010006);a.ok(true);});
		q.def("Q3File_Close",a->{FileObj f=file(a,0);if(f==null){a.ok(false);return;}a.ok(close(f));});
		q.def("Q3File_Cancel",a->{FileObj f=file(a,0);if(f==null){a.ok(false);return;}if(f.reader!=null)f.reader.close();f.reader=null;f.writer=null;f.open=false;if(f.storage!=null)f.storage.close();a.ok(true);});
		q.def("Q3View_StartWriting",a->{Q3View v=q.view(a,0);FileObj f=file(a,1);if(v==null||f==null){a.ok(false);return;}
			if(!f.open||!f.writing||f.writer==null){q.post(PpcQuickDraw3D.ERR_FILE_NOT_OPEN);a.ok(false);return;}
			final Q3Metafile.Writer w=f.writer;
			if(!v.startWriting((o,view)->w.write(o))){q.post(v.error);a.ok(false);return;}a.ok(true);});
		q.def("Q3View_EndWriting",a->{Q3View v=q.view(a,0);if(v==null){a.ret(Q3View.STATUS_ERROR);return;}a.ret(v.endWriting());});
		q.def("Q3File_GetNextObjectType",a->{FileObj f=file(a,0);Q3Metafile.Node n=f==null||f.reader==null?null:f.reader.peek();a.ret(n==null?0:typeOfNode(f.reader,n));});
		q.def("Q3File_IsNextObjectOfType",a->{FileObj f=file(a,0);Q3Metafile.Node n=f==null||f.reader==null?null:f.reader.peek();int t=a.i(1);
			if(n==null){a.ret(0);return;}int nt=typeOfNode(f.reader,n);int[] chain=Q3.chainOf(nt);boolean is=nt==t;if(chain!=null)for(int c:chain)if(c==t)is=true;a.ret(is?1:0);});
		q.def("Q3File_ReadObject",a->{FileObj f=file(a,0);if(f==null){a.ret(0);return;}
			if(!f.open||f.writing||f.reader==null){q.post(PpcQuickDraw3D.ERR_FILE_NOT_OPEN);a.ret(0);return;}
			Q3.Obj o=f.reader.readNext();
			if(o==null&&!f.reader.hasMore())q.post(PpcQuickDraw3D.ERR_END_OF_FILE);
			a.ret(o==null?0:q.made(o));});
		q.def("Q3File_SkipObject",a->{FileObj f=file(a,0);if(f==null||f.reader==null||!f.reader.hasMore()){a.ok(false);return;}f.reader.next++;a.ok(true);});
		q.def("Q3File_IsEndOfData",a->{FileObj f=file(a,0);a.ret(f==null||f.reader==null||!f.reader.hasMore()?1:0);});
		q.def("Q3File_IsEndOfContainer",a->{FileObj f=file(a,0);a.ret(f==null||f.reader==null||!f.reader.hasMore()?1:0);});
		q.def("Q3File_IsEndOfFile",a->{FileObj f=file(a,0);a.ret(f==null||f.reader==null||!f.reader.hasMore()?1:0);});
		q.def("Q3File_MarkAsExternalReference",a->a.ok(true));
		q.def("Q3File_GetExternalReferences",a->a.ret(0));
		q.def("Q3Shared_ClearEditTracking",a->a.ok(true));
		q.def("Q3Shared_GetEditTrackingState",a->a.ret(0));
		q.def("Q3File_SetReadInGroup",a->{FileObj f=file(a,0);if(f==null){a.ok(false);return;}f.readInGroup=a.i(1);a.ok(true);});
		q.def("Q3File_GetReadInGroup",a->{FileObj f=file(a,0);if(f==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),f.readInGroup);a.ok(true);});
		q.def("Q3File_SetIdleMethod",a->{FileObj f=file(a,0);if(f==null){a.ok(false);return;}f.idle=a.i(1);f.idleData=a.i(2);a.ok(true);});
		// ---- the primitives of custom classes' read and write methods: this library runs no such methods
		for(String n:new String[]{"Q3NewLine_Write","Q3Uns8_Read","Q3Uns8_Write","Q3Uns16_Read","Q3Uns16_Write","Q3Uns32_Read","Q3Uns32_Write","Q3Int8_Read","Q3Int8_Write",
			"Q3Int16_Read","Q3Int16_Write","Q3Int32_Read","Q3Int32_Write","Q3Uns64_Read","Q3Uns64_Write","Q3Int64_Read","Q3Int64_Write","Q3Float32_Read","Q3Float32_Write",
			"Q3Float64_Read","Q3Float64_Write","Q3String_Read","Q3String_Write","Q3RawData_Read","Q3RawData_Write","Q3Point2D_Read","Q3Point2D_Write","Q3Point3D_Read",
			"Q3Point3D_Write","Q3RationalPoint3D_Read","Q3RationalPoint3D_Write","Q3RationalPoint4D_Read","Q3RationalPoint4D_Write","Q3Vector2D_Read","Q3Vector2D_Write",
			"Q3Vector3D_Read","Q3Vector3D_Write","Q3Matrix4x4_Read","Q3Matrix4x4_Write","Q3Tangent2D_Read","Q3Tangent2D_Write","Q3Tangent3D_Read","Q3Tangent3D_Write",
			"Q3Comment_Write","Q3XView_SubmitWriteData","Q3XView_SubmitSubObjectData"})
			q.def(n,a->{q.post(PpcQuickDraw3D.ERR_FILE_MODE);a.ok(false);});
		q.def("Q3Size_Pad",a->a.ret((a.i(0)+3)&~3));
		// ---- unknown objects
		q.def("Q3Unknown_GetType",a->{Q3.Obj o=q.obj(a.i(0));a.ret(o instanceof Q3.Unknown?o.leaf():0);});
		q.def("Q3Unknown_GetDirtyState",a->{if(a.i(1)!=0)wl(a.i(1),0);a.ok(a.i(1)!=0);});
		q.def("Q3Unknown_SetDirtyState",a->a.ok(true));
		q.def("Q3UnknownText_GetData",a->{Q3.Obj o=q.obj(a.i(0));int p=a.i(1);if(!(o instanceof Q3.Unknown)||p==0){a.ok(false);return;}Q3.Unknown u=(Q3.Unknown)o;
			wl(p,cstring(u.name==null?"":u.name));wl(p+4,cstring(""));a.ok(true);});
		q.def("Q3UnknownText_EmptyData",a->{int p=a.i(0);if(p==0){a.ok(false);return;}q.free(rl(p));q.free(rl(p+4));wl(p,0);wl(p+4,0);a.ok(true);});
		q.def("Q3UnknownBinary_GetData",a->{Q3.Obj o=q.obj(a.i(0));int p=a.i(1);if(!(o instanceof Q3.Unknown)||p==0){a.ok(false);return;}Q3.Unknown u=(Q3.Unknown)o;
			byte[] c=u.contents==null?new byte[0]:u.contents;wl(p,u.objectType);wl(p+4,c.length);wl(p+8,u.byteOrder);int b=q.alloc(c.length);memory.load(b,c);wl(p+12,b);a.ok(true);});
		q.def("Q3UnknownBinary_EmptyData",a->{int p=a.i(0);if(p==0){a.ok(false);return;}q.free(rl(p+12));wl(p+12,0);a.ok(true);});
		q.def("Q3UnknownBinary_GetTypeString",a->{Q3.Obj o=q.obj(a.i(0));if(!(o instanceof Q3.Unknown)||a.i(1)==0){a.ok(false);return;}wl(a.i(1),cstring(Q3.fcName(((Q3.Unknown)o).objectType)));a.ok(true);});
		q.def("Q3UnknownBinary_EmptyTypeString",a->{int p=a.i(0);if(p==0){a.ok(false);return;}q.free(rl(p));wl(p,0);a.ok(true);});
		// ---- view hints
		q.def("Q3ViewHints_New",a->{Q3View v=q.view(a,0);Q3.Plain h=new Q3.Plain(Q3.VIEW_HINTS);
			if(v!=null){if(v.renderer!=null)h.props.put("renderer",Q3.ref(v.renderer));if(v.camera!=null)h.props.put("camera",Q3.ref(v.camera));if(v.lightGroup!=null)h.props.put("lights",Q3.ref(v.lightGroup));}
			a.ret(q.made(h));});
		hintObject("Renderer","renderer");hintObject("Camera","camera");hintObject("LightGroup","lights");hintObject("AttributeSet","attributes");
		hintInt("DimensionsState","dimsValid");hintInt("MaskState","maskValid");hintInt("ClearImageMethod","clearMethod");
		q.def("Q3ViewHints_SetDimensions",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null){a.ok(false);return;}h.props.put("width",a.i(1));h.props.put("height",a.i(2));a.ok(true);});
		q.def("Q3ViewHints_GetDimensions",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null){a.ok(false);return;}
			if(a.i(1)!=0)wl(a.i(1),(Integer)h.props.getOrDefault("width",0));if(a.i(2)!=0)wl(a.i(2),(Integer)h.props.getOrDefault("height",0));a.ok(true);});
		q.def("Q3ViewHints_SetMask",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null){a.ok(false);return;}h.props.put("mask",q.readBitmap(a.i(1)));a.ok(true);});
		q.def("Q3ViewHints_GetMask",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null||a.i(1)==0){a.ok(false);return;}q.writeBitmap(a.i(1),(Q3.Bitmap)h.props.get("mask"));a.ok(true);});
		q.def("Q3ViewHints_SetClearImageColor",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null||a.i(1)==0){a.ok(false);return;}h.props.put("clearColor",q.rfs(a.i(1),4));a.ok(true);});
		q.def("Q3ViewHints_GetClearImageColor",a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null||a.i(1)==0){a.ok(false);return;}
			float[] c=(float[])h.props.get("clearColor");q.wfs(a.i(1),c==null?new float[]{1,1,1,1}:c,0,4);a.ok(true);});
	}
	private void hintObject(String n,String key){
		q.def("Q3ViewHints_Set"+n,a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null){a.ok(false);return;}Q3.Obj o=q.obj(a.i(1));
			Object old=h.props.put(key,Q3.ref(o));if(old instanceof Q3.Obj)Q3.release((Q3.Obj)old);a.ok(true);});
		q.def("Q3ViewHints_Get"+n,a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null||a.i(1)==0){a.ok(false);return;}Object o=h.props.get(key);
			wl(a.i(1),o instanceof Q3.Obj?q.newRef((Q3.Obj)o):0);a.ok(true);});
	}
	private void hintInt(String n,String key){
		q.def("Q3ViewHints_Set"+n,a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null){a.ok(false);return;}h.props.put(key,a.i(1));a.ok(true);});
		q.def("Q3ViewHints_Get"+n,a->{Q3.Plain h=q.get(a.i(0),Q3.Plain.class);if(h==null||a.i(1)==0){a.ok(false);return;}wl(a.i(1),(Integer)h.props.getOrDefault(key,0));a.ok(true);});
	}
	private int cstring(String s) throws M68kCpuException {byte[] b=s.getBytes(PpcQuickDraw3D.MAC_ROMAN);int p=q.alloc(b.length+1);memory.load(p,b);return p;}
	/** The type Q3File_GetNextObjectType tells: a container's or group's own type. */
	private int typeOfNode(Q3Metafile.Reader r,Q3Metafile.Node n){
		if(n.refId>=0){Q3Metafile.Node t=r.resolve(n.refId);return t==null?0:typeOfNode(r,t);}
		if(n.is("bgng")&&!n.subs.isEmpty())return n.subs.get(0).type;
		return n.type;
	}
	/** Closes a file: a written one's bytes go to its storage. */
	private boolean close(FileObj f) throws Exception {
		if(!f.open){q.post(PpcQuickDraw3D.ERR_FILE_NOT_OPEN);return false;}
		boolean ok=true;
		try{
			if(f.writing&&f.writer!=null){
				byte[] b=f.writer.finish();
				if(f.storage instanceof Q3.ArrayStorage){Q3.ArrayStorage s=(Q3.ArrayStorage)f.storage;s.data=b;s.length=b.length;}
				else ok=f.storage.write(0,b,0,b.length)==b.length;
				f.storage.edited();
			}
		}finally{
			if(f.reader!=null)f.reader.close();
			f.reader=null;f.writer=null;f.open=false;f.storage.close();
		}
		return ok;
	}
}
