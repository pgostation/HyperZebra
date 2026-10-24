import java.util.HashMap;
import java.util.Map;

/**
 * StdCLib's file streams for PowerPC programs: fopen/fread/fwrite/fseek and friends on the File Manager (so paths,
 * volumes and the sandbox are the VM's own), with stdin/stdout/stderr on the host console.
 *
 * <p>The FILE blocks are MPW's (stdio.h, align=power: _cnt, _ptr, _base, _end, _size, _flag, _file = 24 bytes) in
 * {@code _iob[40]}. {@code _cnt} is kept at 0 so the getc/putc macros always land in {@code _filbuf}/{@code _flsbuf};
 * the feof/ferror/clearerr macros read and write {@code _flag}, which these functions keep current.
 * Names are Macintosh paths (":Maelstrom Fonts" is relative to the default directory). freopen on the standard streams
 * keeps them on the console.
 */
final class PpcStdio {
	private PpcStdio(){}
	private static final String LIB="StdCLib";
	static final int FILE_SIZE=24,NFILE=40;
	private static final int IOREAD=0x01,IOWRT=0x02,IOEOF=0x10,IOERR=0x20,IORW=0x80;
	private static final int EOF=-1;
	private static final int CHUNK=4096;

	/** An open file stream: its File Manager reference number, logical position and a read-ahead buffer. */
	private static final class Stream {
		int refNum;boolean read,write,append;long pos;
		byte[] buf=new byte[0];long bufStart;int pushback=-1;
	}

	/** Per-runtime stream state. */
	private static final class State {
		final Map<Integer,Stream> streams=new HashMap<Integer,Stream>();
		final Map<Integer,Integer> descriptors=new HashMap<Integer,Integer>(); // open()'s descriptors -> FILE

		int pb,io; // a parameter block and an I/O buffer in VM memory
	}

	static void define(PpcLibraries l){
		final State s=new State();
		l.register(LIB,"fopen",(r,c)->r.ret(open(r,s,r.arg(0),r.arg(1),0)));
		l.register(LIB,"freopen",(r,c)->{int f=r.arg(2);
			if(isStd(r,f)){r.ret(f);return;}
			if(f!=0&&s.streams.containsKey(f))close(r,s,f);
			r.ret(open(r,s,r.arg(0),r.arg(1),f));});
		l.register(LIB,"fclose",(r,c)->{int f=r.arg(0);if(isStd(r,f)){System.out.flush();r.ret(0);return;}r.ret(close(r,s,f));});
		l.register(LIB,"fflush",(r,c)->{System.out.flush();r.ret(0);});
		l.register(LIB,"setvbuf",(r,c)->r.ret(0));
		l.register(LIB,"setbuf",(r,c)->{});
		l.register(LIB,"fread",(r,c)->{int dst=r.arg(0),size=r.arg(1),n=r.arg(2),f=r.arg(3);
			Stream st=s.streams.get(f);if(st==null||size<=0||n<=0){r.ret(0);return;}
			long want=(long)size*n;int got=read(r,s,f,st,dst,(int)Math.min(want,Integer.MAX_VALUE));
			r.ret(got/size);});
		l.register(LIB,"fwrite",(r,c)->{int src=r.arg(0),size=r.arg(1),n=r.arg(2),f=r.arg(3);
			if(size<=0||n<=0){r.ret(0);return;}
			byte[] b=r.memory.readBytes(src,size*n);
			r.ret(write(r,s,f,b)==b.length?n:0);});
		l.register(LIB,"fgetc",(r,c)->r.ret(getc(r,s,r.arg(0))));
		l.register(LIB,"getc",(r,c)->r.ret(getc(r,s,r.arg(0))));
		l.register(LIB,"_filbuf",(r,c)->r.ret(getc(r,s,r.arg(0))));
		l.register(LIB,"getchar",(r,c)->r.ret(EOF));
		l.register(LIB,"ungetc",(r,c)->{int ch=r.arg(0),f=r.arg(1);Stream st=s.streams.get(f);
			if(st==null||ch==EOF){r.ret(EOF);return;}
			st.pushback=ch&0xff;setFlag(r,f,IOEOF,false);r.ret(ch&0xff);});
		l.register(LIB,"fgets",(r,c)->{int dst=r.arg(0),n=r.arg(1),f=r.arg(2);
			if(n<=0){r.ret(0);return;}
			int i=0;
			while(i<n-1){int ch=getc(r,s,f);if(ch==EOF)break;r.memory.writeByte(dst+i++,ch);if(ch=='\r'||ch=='\n')break;}
			r.memory.writeByte(dst+i,0);r.ret(i==0?0:dst);});
		l.register(LIB,"fputc",(r,c)->{int ch=r.arg(0)&0xff;r.ret(write(r,s,r.arg(1),new byte[]{(byte)ch})==1?ch:EOF);});
		l.register(LIB,"putc",(r,c)->{int ch=r.arg(0)&0xff;r.ret(write(r,s,r.arg(1),new byte[]{(byte)ch})==1?ch:EOF);});
		l.register(LIB,"_flsbuf",(r,c)->{int ch=r.arg(0)&0xff;r.ret(write(r,s,r.arg(1),new byte[]{(byte)ch})==1?ch:EOF);});
		l.register(LIB,"fputs",(r,c)->{byte[] b=r.cBytes(r.arg(0));r.ret(write(r,s,r.arg(1),b)==b.length?0:EOF);});
		l.register(LIB,"fprintf",(r,c)->{byte[] out=PpcStdCLib.format(r,r.cBytes(r.arg(1)),new PpcStdCLib.VarArgs(r,2));r.ret(write(r,s,r.arg(0),out)==out.length?out.length:-1);});
		l.register(LIB,"vfprintf",(r,c)->{byte[] out=PpcStdCLib.format(r,r.cBytes(r.arg(1)),new PpcStdCLib.VarArgs(r,r.arg(2),true));r.ret(write(r,s,r.arg(0),out)==out.length?out.length:-1);});
		l.register(LIB,"fseek",(r,c)->{int f=r.arg(0),off=r.arg(1),whence=r.arg(2);Stream st=s.streams.get(f);
			if(st==null){r.ret(-1);return;}
			long base=whence==1?st.pos:whence==2?eof(r,s,st):0;long p=base+off;
			if(p<0){r.ret(-1);return;}
			st.pos=p;st.pushback=-1;setFlag(r,f,IOEOF,false);r.ret(0);});
		l.register(LIB,"ftell",(r,c)->{Stream st=s.streams.get(r.arg(0));r.ret(st==null?-1:(int)(st.pos-(st.pushback>=0?1:0)));});
		l.register(LIB,"rewind",(r,c)->{int f=r.arg(0);Stream st=s.streams.get(f);if(st!=null){st.pos=0;st.pushback=-1;}setFlag(r,f,IOEOF|IOERR,false);});
		l.register(LIB,"fgetpos",(r,c)->{Stream st=s.streams.get(r.arg(0));if(st==null){r.ret(-1);return;}r.memory.writeLong(r.arg(1),(int)(st.pos-(st.pushback>=0?1:0)));r.ret(0);});
		l.register(LIB,"fsetpos",(r,c)->{int f=r.arg(0);Stream st=s.streams.get(f);if(st==null){r.ret(-1);return;}st.pos=r.memory.readLong(r.arg(1));st.pushback=-1;setFlag(r,f,IOEOF,false);r.ret(0);});
		l.register(LIB,"feof",(r,c)->r.ret(r.arg(0)==0?0:r.memory.readWord(r.arg(0)+18)&IOEOF));
		l.register(LIB,"ferror",(r,c)->r.ret(r.arg(0)==0?0:r.memory.readWord(r.arg(0)+18)&IOERR));
		l.register(LIB,"clearerr",(r,c)->setFlag(r,r.arg(0),IOEOF|IOERR,false));
		l.register(LIB,"fileno",(r,c)->r.ret(r.arg(0)==0?-1:r.memory.readWord(r.arg(0)+20)));
		l.register(LIB,"remove",(r,c)->r.ret(fileCall(r,s,0xa009,r.arg(0),0)==0?0:-1)); // _Delete
		// <fcntl.h>'s file descriptors (MPW Shell 3.5's PowerPC code opens its files with open + fdopen): a descriptor names a
		// stream here; 0-2 are the standard streams. The resource fork (O_RSRC) is not offered.
		l.register(LIB,"open",(r,c)->{int path=r.arg(0),flags=r.arg(1);
			if((flags&0x10)!=0){r.ret(-1);return;} // O_RSRC
			boolean exists=fileCall(r,s,0xa00c,path,0)==0; // _GetFileInfo
			if(exists&&(flags&0x500)==0x500){r.ret(-1);return;} // O_CREAT|O_EXCL on an existing file
			if(!exists&&(flags&0x100)==0){r.ret(-1);return;}
			int access=flags&3;String mode;
			if(access==0)mode="rb";
			else if((flags&0x200)!=0||!exists)mode=access==1?"wb":"w+b";
			else mode="r+b";
			int f=open(r,s,path,mode,0);if(f==0){r.ret(-1);return;}
			if((flags&0x08)!=0){Stream st=s.streams.get(f);if(st!=null){st.append=true;st.pos=eof(r,s,st);}}
			int fd=3;while(s.descriptors.containsKey(fd))fd++;s.descriptors.put(fd,f);r.ret(fd);});
		l.register(LIB,"close",(r,c)->{int fd=r.arg(0);if(fd>=0&&fd<3){r.ret(0);return;}Integer f=s.descriptors.remove(fd);
			if(f==null){r.ret(-1);return;}r.ret(s.streams.containsKey(f)?close(r,s,f):0);});
		l.register(LIB,"fdopen",(r,c)->{int fd=r.arg(0);if(fd>=0&&fd<3){r.ret(iob(r)+fd*FILE_SIZE);return;}Integer f=s.descriptors.get(fd);r.ret(f==null?0:f);});
		l.register(LIB,"fcntl",(r,c)->{int fd=r.arg(0);r.ret(r.arg(1)==0&&(fd<3||s.descriptors.containsKey(fd))?fd:-1);}); // F_DUPFD: the same stream
		l.register(LIB,"ioctl",(r,c)->{int fd=r.arg(0),cmd=r.arg(1),arg=r.arg(2);Integer f=fd>=0&&fd<3?Integer.valueOf(iob(r)+fd*FILE_SIZE):s.descriptors.get(fd);
			if(f==null){r.ret(-1);return;}Stream st=s.streams.get(f);
			switch(cmd){
				case 0x6602:r.ret(st==null?0:-1);return; // FIOINTERACTIVE: the standard streams are the console
				case 0x6603:if(arg!=0)r.memory.writeLong(arg,CHUNK);r.ret(0);return; // FIOBUFSIZE
				case 0x6605:if(st==null){r.ret(-1);return;}if(arg!=0)r.memory.writeWord(arg,st.refNum);r.ret(0);return; // FIOREFNUM
				case 0x6606:if(st==null||arg==0){r.ret(-1);return;}setEof(r,s,st,r.memory.readLong(arg));r.ret(0);return; // FIOSETEOF
				default:r.ret(-1);
			}});
		l.register(LIB,"unlink",(r,c)->r.ret(fileCall(r,s,0xa009,r.arg(0),0)==0?0:-1));
		// faccess: F_DELETE and F_RENAME; the editor information ('e' commands) belongs to MPW Shell's windows, not to files here
		l.register(LIB,"faccess",(r,c)->{int path=r.arg(0),cmd=r.arg(1);
			if(cmd==0x6401){r.ret(fileCall(r,s,0xa009,path,0)==0?0:-1);return;}
			if(cmd==0x6402){byte[] to=pascal(r.cBytes(r.arg(2)));scratch(r,s);int q=s.io+CHUNK;r.memory.load(q,to);r.ret(fileCall(r,s,0xa00b,path,q)==0?0:-1);return;}
			r.ret(-1);});
		l.register(LIB,"rename",(r,c)->{byte[] to=pascal(r.cBytes(r.arg(1)));scratch(r,s);int p=s.io+CHUNK;r.memory.load(p,to);
			int err=fileCall(r,s,0xa00b,r.arg(0),p); // _Rename, the new name in ioMisc
			r.ret(err==0?0:-1);});
	}

	private static boolean isStd(PpcRuntime r,int f) throws M68kCpuException {int iob=iob(r);return f>=iob&&f<iob+3*FILE_SIZE;}
	private static int iob(PpcRuntime r) throws M68kCpuException {return r.libraries.data(LIB,"_iob");}
	private static void setFlag(PpcRuntime r,int f,int bits,boolean on) throws M68kCpuException {
		if(f==0)return;int v=r.memory.readWord(f+18);r.memory.writeWord(f+18,on?v|bits:v&~bits);
	}
	private static byte[] pascal(byte[] c){int n=Math.min(c.length,255);byte[] p=new byte[n+1];p[0]=(byte)n;System.arraycopy(c,0,p,1,n);return p;}

	private static void scratch(PpcRuntime r,State s) throws M68kCpuException {
		if(s.pb==0){s.pb=r.allocate(128,8);s.io=r.allocate(CHUNK+256,8);}
	}
	/** Runs a File Manager call on name {@code path} (a C string) in the default directory; {@code misc} goes in ioMisc. */
	private static int fileCall(PpcRuntime r,State s,int trap,int path,int misc) throws M68kException {
		scratch(r,s);int pb=s.pb;M68kMemory m=r.memory;
		m.fill(pb,128,0);
		byte[] name=pascal(r.cBytes(path));m.load(s.io,name);
		m.writeLong(pb+18,s.io);m.writeLong(pb+28,misc);
		return (short)r.trap(trap,0,pb,0);
	}

	private static int open(PpcRuntime r,State s,int path,int modeP,int reuse) throws M68kException {
		return open(r,s,path,new String(r.cBytes(modeP),java.nio.charset.StandardCharsets.ISO_8859_1),reuse);
	}
	private static int open(PpcRuntime r,State s,int path,String mode,int reuse) throws M68kException {
		char kind=mode.isEmpty()?'r':mode.charAt(0);boolean plus=mode.indexOf('+')>=0;
		if(kind!='r'&&kind!='w'&&kind!='a')return 0;
		M68kMemory m=r.memory;
		int err;
		if(kind!='r'){
			err=fileCall(r,s,0xa008,path,0); // _Create (dupFNErr when it exists)
			if(err!=0&&err!=-48)return 0;
			if(err==0&&mode.indexOf('b')<0)setTypeCreator(r,s,path); // text streams are MPW text files; binary ones keep _Create's blank type
		}
		int perm=kind=='r'&&!plus?1:3; // fsRdPerm, fsRdWrPerm
		scratch(r,s);
		m.fill(s.pb,128,0);m.load(s.io,pascal(r.cBytes(path)));m.writeLong(s.pb+18,s.io);m.writeByte(s.pb+27,perm);
		err=(short)r.trap(0xa000,0,s.pb,0); // _Open
		if(err!=0)return 0;
		int refNum=(short)m.readWord(s.pb+24);
		Stream st=new Stream();st.refNum=refNum;st.read=kind=='r'||plus;st.write=kind!='r'||plus;st.append=kind=='a';
		if(kind=='w')setEof(r,s,st,0);
		if(st.append)st.pos=eof(r,s,st);
		int f=reuse;
		if(f==0){int iob=iob(r);for(int i=3;i<NFILE;i++){int at=iob+i*FILE_SIZE;if(m.readWord(at+18)==0&&!s.streams.containsKey(at)){f=at;break;}}}
		if(f==0){closeRef(r,s,refNum);return 0;}
		m.fill(f,FILE_SIZE,0);
		m.writeWord(f+18,plus?IORW|IOREAD|IOWRT:kind=='r'?IOREAD:IOWRT);
		m.writeWord(f+20,refNum);
		s.streams.put(f,st);
		return f;
	}
	private static void setTypeCreator(PpcRuntime r,State s,int path) throws M68kException {
		M68kMemory m=r.memory;
		if(fileCall(r,s,0xa00c,path,0)!=0)return; // _GetFileInfo (ioFDirIndex 0: by name)
		m.writeLong(s.pb+32,0x54455854);m.writeLong(s.pb+36,0x4d505320); // 'TEXT' 'MPS ', as MPW's StdCLib makes them
		r.trap(0xa00d,0,s.pb,0); // _SetFileInfo
	}
	private static int close(PpcRuntime r,State s,int f) throws M68kException {
		Stream st=s.streams.remove(f);if(st==null)return EOF;
		int err=closeRef(r,s,st.refNum);
		r.memory.fill(f,FILE_SIZE,0);
		return err==0?0:EOF;
	}
	private static int closeRef(PpcRuntime r,State s,int refNum) throws M68kException {
		scratch(r,s);r.memory.fill(s.pb,128,0);r.memory.writeWord(s.pb+24,refNum);
		return (short)r.trap(0xa001,0,s.pb,0); // _Close
	}
	private static long eof(PpcRuntime r,State s,Stream st) throws M68kException {
		scratch(r,s);r.memory.fill(s.pb,128,0);r.memory.writeWord(s.pb+24,st.refNum);
		r.trap(0xa011,0,s.pb,0); // _GetEOF
		return r.memory.readLong(s.pb+28)&0xffffffffL;
	}
	private static void setEof(PpcRuntime r,State s,Stream st,long size) throws M68kException {
		scratch(r,s);r.memory.fill(s.pb,128,0);r.memory.writeWord(s.pb+24,st.refNum);r.memory.writeLong(s.pb+28,(int)size);
		r.trap(0xa012,0,s.pb,0); // _SetEOF
		st.buf=new byte[0];
	}

	/** Reads up to {@code n} bytes at the stream's position straight into VM memory at {@code dst}; answers the count. */
	private static int read(PpcRuntime r,State s,int f,Stream st,int dst,int n) throws M68kException {
		if(!st.read){setFlag(r,f,IOERR,true);return 0;}
		int done=0;
		if(st.pushback>=0&&n>0){r.memory.writeByte(dst,st.pushback);st.pushback=-1;done=1;}
		while(done<n){
			if(!fill(r,s,st)){setFlag(r,f,IOEOF,true);break;}
			int off=(int)(st.pos-st.bufStart),k=Math.min(st.buf.length-off,n-done);
			r.memory.load(dst+done,st.buf,off,k);done+=k;st.pos+=k;
		}
		return done;
	}
	/** Makes the read-ahead buffer cover the position; false at the end of the file. */
	private static boolean fill(PpcRuntime r,State s,Stream st) throws M68kException {
		if(st.pos>=st.bufStart&&st.pos<st.bufStart+st.buf.length)return true;
		scratch(r,s);M68kMemory m=r.memory;
		m.fill(s.pb,128,0);m.writeWord(s.pb+24,st.refNum);m.writeLong(s.pb+32,s.io);m.writeLong(s.pb+36,CHUNK);
		m.writeWord(s.pb+44,1);m.writeLong(s.pb+46,(int)st.pos); // fsFromStart
		r.trap(0xa002,0,s.pb,0); // _Read (eofErr with a short count at the end)
		int got=m.readLong(s.pb+40);
		if(got<=0){st.buf=new byte[0];return false;}
		st.buf=m.readBytes(s.io,got);st.bufStart=st.pos;
		return true;
	}
	private static int getc(PpcRuntime r,State s,int f) throws M68kException {
		Stream st=s.streams.get(f);
		if(f!=0)r.memory.writeLong(f,0); // _cnt: the macro took it to -1
		if(st==null||!st.read){if(f!=0)setFlag(r,f,IOEOF,true);return EOF;}
		if(st.pushback>=0){int ch=st.pushback;st.pushback=-1;return ch;}
		if(!fill(r,s,st)){setFlag(r,f,IOEOF,true);return EOF;}
		return st.buf[(int)(st.pos++-st.bufStart)]&0xff;
	}
	/** Writes {@code b} to stream {@code f} (the console for the standard streams); answers the count written. */
	private static int write(PpcRuntime r,State s,int f,byte[] b) throws M68kException {
		if(f!=0&&isStd(r,f)){r.memory.writeLong(f,0);PpcStdCLib.console(r,b);return b.length;}
		Stream st=s.streams.get(f);
		if(f!=0)r.memory.writeLong(f,0);
		if(st==null||!st.write){if(f!=0)setFlag(r,f,IOERR,true);return 0;}
		if(st.append)st.pos=eof(r,s,st);
		if(st.pushback>=0){st.pos--;st.pushback=-1;}
		st.buf=new byte[0];
		scratch(r,s);M68kMemory m=r.memory;
		int done=0;
		while(done<b.length){
			int k=Math.min(CHUNK,b.length-done);
			m.load(s.io,b,done,k);
			m.fill(s.pb,128,0);m.writeWord(s.pb+24,st.refNum);m.writeLong(s.pb+32,s.io);m.writeLong(s.pb+36,k);
			m.writeWord(s.pb+44,1);m.writeLong(s.pb+46,(int)st.pos);
			int err=(short)r.trap(0xa003,0,s.pb,0); // _Write
			int wrote=m.readLong(s.pb+40);done+=Math.max(0,wrote);st.pos+=Math.max(0,wrote);
			if(err!=0||wrote<k){setFlag(r,f,IOERR,true);break;}
		}
		return done;
	}
}
