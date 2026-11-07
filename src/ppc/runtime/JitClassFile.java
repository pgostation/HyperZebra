import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A small class file writer for the code {@link PpcJit} generates: one class with a constant pool, methods with their code,
 * labels, an exception table and a StackMapTable. Written here (not java.lang.classfile) because the sources build with
 * --release 17.
 *
 * The generated methods keep one type per local variable for their whole length and an empty operand stack at every label,
 * so every stack map frame is the same full frame (an exception handler's has the exception on the stack): {@link Code}
 * records where frames go and writes them all from the one list of local types.
 */
final class JitClassFile {
	// ------------------------------------------------------------------ byte buffer

	/** A growable big-endian byte buffer. */
	static final class Bytes {
		byte[] b=new byte[256];int n;
		void u1(int v){if(n==b.length)b=Arrays.copyOf(b,b.length*2);b[n++]=(byte)v;}
		void u2(int v){u1(v>>>8);u1(v);}
		void u4(int v){u2(v>>>16);u2(v);}
		void u8(long v){u4((int)(v>>>32));u4((int)v);}
		void put(Bytes o){for(int i=0;i<o.n;i++)u1(o.b[i]);}
		void setU2(int at,int v){b[at]=(byte)(v>>>8);b[at+1]=(byte)v;}
		void setU4(int at,int v){setU2(at,v>>>16);setU2(at+2,v);}
		byte[] toArray(){return Arrays.copyOf(b,n);}
	}

	// ------------------------------------------------------------------ constant pool

	private final Bytes pool=new Bytes();
	private int poolCount=1;
	private final Map<String,Integer> poolIndex=new HashMap<String,Integer>();
	private int entry(String key,int size,java.util.function.Consumer<Bytes> body){
		Integer i=poolIndex.get(key);if(i!=null)return i;
		int index=poolCount;body.accept(pool);poolCount+=size;poolIndex.put(key,index);return index;
	}
	int utf8(String s){return entry("U"+s,1,p->{p.u1(1);byte[] u=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);p.u2(u.length);for(byte x:u)p.u1(x);});} // ASCII names only
	int classRef(String internalName){int name=utf8(internalName);return entry("C"+internalName,1,p->{p.u1(7);p.u2(name);});}
	/** A String constant (any text; the class file's modified UTF-8), or -1 when it does not fit (65535 bytes). */
	int string(String s){
		byte[] u=modifiedUtf8(s);if(u==null)return -1;
		int text=entry("T"+s,1,p->{p.u1(1);p.u2(u.length);for(byte x:u)p.u1(x);});
		return entry("S"+s,1,p->{p.u1(8);p.u2(text);});
	}
	private static byte[] modifiedUtf8(String s){
		Bytes b=new Bytes();
		for(int i=0;i<s.length();i++){
			char c=s.charAt(i);
			if(c>=1&&c<=0x7f)b.u1(c);
			else if(c<=0x7ff){b.u1(0xc0|c>>6);b.u1(0x80|c&0x3f);}
			else{b.u1(0xe0|c>>12);b.u1(0x80|c>>6&0x3f);b.u1(0x80|c&0x3f);}
			if(b.n>65535)return null;
		}
		return b.toArray();
	}
	int integer(int v){return entry("I"+v,1,p->{p.u1(3);p.u4(v);});}
	int longConst(long v){return entry("J"+v,2,p->{p.u1(5);p.u8(v);});}
	int doubleConst(double v){long bits=Double.doubleToRawLongBits(v);return entry("D"+bits,2,p->{p.u1(6);p.u8(bits);});}
	private int nameAndType(String name,String descriptor){int n=utf8(name),d=utf8(descriptor);return entry("N"+name+":"+descriptor,1,p->{p.u1(12);p.u2(n);p.u2(d);});}
	int field(String owner,String name,String descriptor){int c=classRef(owner),nt=nameAndType(name,descriptor);return entry("F"+owner+"."+name+":"+descriptor,1,p->{p.u1(9);p.u2(c);p.u2(nt);});}
	int method(String owner,String name,String descriptor){int c=classRef(owner),nt=nameAndType(name,descriptor);return entry("M"+owner+"."+name+descriptor,1,p->{p.u1(10);p.u2(c);p.u2(nt);});}
	int interfaceMethod(String owner,String name,String descriptor){int c=classRef(owner),nt=nameAndType(name,descriptor);return entry("Y"+owner+"."+name+descriptor,1,p->{p.u1(11);p.u2(c);p.u2(nt);});}

	// ------------------------------------------------------------------ class

	private static final class MethodData {int access,name,descriptor;Code code;}
	private final List<MethodData> methods=new ArrayList<MethodData>();
	private final String thisName,superName;private final String[] interfaces;

	JitClassFile(String thisName,String superName,String... interfaces){this.thisName=thisName;this.superName=superName;this.interfaces=interfaces;}

	/** A method of the class; its code is written into the returned builder. */
	Code method(int access,String name,String descriptor){
		MethodData m=new MethodData();m.access=access;m.name=utf8(name);m.descriptor=utf8(descriptor);m.code=new Code(this);methods.add(m);return m.code;
	}

	byte[] toBytes(){
		int thisIndex=classRef(thisName),superIndex=classRef(superName);
		int[] interfaceIndex=new int[interfaces.length];for(int i=0;i<interfaces.length;i++)interfaceIndex[i]=classRef(interfaces[i]);
		int codeName=utf8("Code"),frameName=utf8("StackMapTable");
		Bytes body=new Bytes(); // everything after the constant pool (which the attributes still add to)
		body.u2(0x0030); // ACC_FINAL | ACC_SUPER
		body.u2(thisIndex);body.u2(superIndex);
		body.u2(interfaceIndex.length);for(int i:interfaceIndex)body.u2(i);
		body.u2(0); // fields
		body.u2(methods.size());
		for(MethodData m:methods){
			body.u2(m.access);body.u2(m.name);body.u2(m.descriptor);
			Bytes frames=m.code.stackMapTable();
			body.u2(1); // the Code attribute
			Code c=m.code;
			int attributeLength=2+2+4+c.out.n+2+8*c.handlers.size()+2+(frames==null?0:6+frames.n);
			body.u2(codeName);body.u4(attributeLength);
			body.u2(c.maxStack);body.u2(c.maxLocals());
			body.u4(c.out.n);body.put(c.out);
			body.u2(c.handlers.size());for(int[] h:c.handlers){body.u2(h[0]);body.u2(h[1]);body.u2(h[2]);body.u2(h[3]);}
			if(frames==null)body.u2(0);
			else{body.u2(1);body.u2(frameName);body.u4(frames.n);body.put(frames);}
		}
		body.u2(0); // class attributes
		Bytes out=new Bytes();
		out.u4(0xcafebabe);out.u2(0);out.u2(61); // Java 17 class files
		out.u2(poolCount);out.put(pool);out.put(body);
		return out.toArray();
	}

	// ------------------------------------------------------------------ code

	/** A branch target. */
	static final class Label {int pos=-1;final int id;Label(int id){this.id=id;}}

	/** Verification types of locals (stack map frames). */
	static final int T_INT=1,T_DOUBLE=3,T_LONG=4,OBJECT=0x10000;

	/** One method's bytecode. Branches are 16-bit: the methods PpcJit makes stay far below 32 KB. */
	static final class Code {
		final JitClassFile cf;
		final Bytes out=new Bytes();
		int maxStack=24;
		/** Local slots: their verification type (T_INT, T_LONG, T_DOUBLE, or OBJECT + a class constant index) by slot;
		 *  the second slot of a long or double holds 0. */
		private final List<Integer> locals=new ArrayList<Integer>();
		private int scratch;
		/** Frame offsets: false = empty stack, true = exception handler (the caught exception on the stack). */
		private final TreeMap<Integer,Boolean> frames=new TreeMap<Integer,Boolean>();
		private int handlerType;
		final List<int[]> handlers=new ArrayList<int[]>();
		private final List<int[]> fixups=new ArrayList<int[]>(); // {opcode position, offset position, size, label id}
		private final List<Label> labels=new ArrayList<Label>();
		/** Control cannot reach the current position by falling through (after goto, return, a switch). */
		private boolean dead;

		Code(JitClassFile cf){this.cf=cf;}

		// ---- locals
		/** Declares an object local (the parameters too, in order). */
		int objectLocal(String internalName){int slot=locals.size();locals.add(OBJECT+cf.classRef(internalName));return slot;}
		int intLocal(){int slot=locals.size();locals.add(T_INT);return slot;}
		int longLocal(){int slot=locals.size();locals.add(T_LONG);locals.add(0);return slot;}
		int doubleLocal(){int slot=locals.size();locals.add(T_DOUBLE);locals.add(0);return slot;}
		/** Keeps {@code slots} slots above the declared locals for values that do not live across a label: no frame mentions them.
		 *  Gives the first one; every local is declared by then. */
		int scratchLocals(int slots){scratch=slots;return locals.size();}
		int maxLocals(){return locals.size()+scratch;}

		// ---- emission
		int pos(){return out.n;}
		private void live(){if(dead){frames.putIfAbsent(out.n,Boolean.FALSE);dead=false;}}
		void op(int opcode){live();out.u1(opcode);if(opcode==GOTO||opcode==IRETURN||opcode==ARETURN||opcode==RETURN||opcode==ATHROW)dead=true;}
		void op1(int opcode,int v){live();out.u1(opcode);out.u1(v);}
		void op2(int opcode,int v){live();out.u1(opcode);out.u2(v);}
		/** iload/istore/lload/... of {@code slot} (wide above 255). */
		void var(int opcode,int slot){
			live();
			if(slot<4&&opcode>=ILOAD&&opcode<=ALOAD){out.u1(0x1a+(opcode-ILOAD)*4+slot);return;}
			if(slot<4&&opcode>=ISTORE&&opcode<=ASTORE){out.u1(0x3b+(opcode-ISTORE)*4+slot);return;}
			if(slot>255){out.u1(WIDE);out.u1(opcode);out.u2(slot);return;}
			out.u1(opcode);out.u1(slot);
		}
		void iinc(int slot,int delta){
			live();
			if(slot<=255&&delta>=-128&&delta<=127){out.u1(IINC);out.u1(slot);out.u1(delta);return;}
			out.u1(WIDE);out.u1(IINC);out.u2(slot);out.u2(delta);
		}
		void iconst(int v){
			if(v>=-1&&v<=5)op(ICONST_0+v);
			else if(v>=-128&&v<=127)op1(BIPUSH,v&0xff);
			else if(v>=-32768&&v<=32767)op2(SIPUSH,v&0xffff);
			else ldc(cf.integer(v));
		}
		void lconst(long v){if(v==0)op(LCONST_0);else if(v==1)op(LCONST_1);else op2(LDC2_W,cf.longConst(v));}
		void dconst(double v){if(Double.doubleToRawLongBits(v)==0)op(DCONST_0);else if(v==1.0)op(DCONST_1);else op2(LDC2_W,cf.doubleConst(v));}
		void ldc(int index){if(index<=255)op1(LDC,index);else op2(LDC_W,index);}
		void getfield(String owner,String name,String descriptor){op2(GETFIELD,cf.field(owner,name,descriptor));}
		void putfield(String owner,String name,String descriptor){op2(PUTFIELD,cf.field(owner,name,descriptor));}
		void invokevirtual(String owner,String name,String descriptor){op2(INVOKEVIRTUAL,cf.method(owner,name,descriptor));}
		void invokestatic(String owner,String name,String descriptor){op2(INVOKESTATIC,cf.method(owner,name,descriptor));}
		void invokespecial(String owner,String name,String descriptor){op2(INVOKESPECIAL,cf.method(owner,name,descriptor));}

		// ---- labels
		Label label(){Label l=new Label(labels.size());labels.add(l);return l;}
		/** Places {@code l} here; a frame goes with it (the operand stack must be empty). */
		void bind(Label l){if(l.pos>=0)throw new IllegalStateException("label bound twice");l.pos=out.n;frames.put(out.n,frames.getOrDefault(out.n,Boolean.FALSE));dead=false;}
		boolean bound(Label l){return l.pos>=0;}
		/** A conditional or unconditional (GOTO) branch to {@code l}. */
		void jump(int opcode,Label l){
			live();int at=out.n;out.u1(opcode);fixups.add(new int[]{at,out.n,2,l.id});out.u2(0);
			if(opcode==GOTO)dead=true;
		}
		/** lookupswitch over {@code keys} (any order). */
		void lookupswitch(int[] keys,Label[] targets,Label dflt){
			live();
			Integer[] order=new Integer[keys.length];for(int i=0;i<order.length;i++)order[i]=i;
			Arrays.sort(order,(a,b)->Integer.compare(keys[a],keys[b]));
			int at=out.n;out.u1(LOOKUPSWITCH);while((out.n&3)!=0)out.u1(0);
			fixups.add(new int[]{at,out.n,4,dflt.id});out.u4(0);
			out.u4(keys.length);
			for(Integer i:order){out.u4(keys[i]);fixups.add(new int[]{at,out.n,4,targets[i].id});out.u4(0);}
			dead=true;
		}
		/** Exception handler over [start, end) at {@code handler} for {@code catchType} (several types may share a handler: its
		 *  frame holds a Throwable). */
		void handler(Label start,Label end,Label handler,String catchType){
			handlerType=cf.classRef("java/lang/Throwable");
			handlers.add(new int[]{start.id,end.id,handler.id,cf.classRef(catchType)});
		}
		/** Starts an exception handler here (after {@link #handler}). */
		void bindHandler(Label l){l.pos=out.n;frames.put(out.n,Boolean.TRUE);dead=false;}

		/** Resolves the branches and the handler table; returns false when an offset does not fit (the code is too long). */
		boolean finish(){
			for(int[] f:fixups){
				Label l=labels.get(f[3]);if(l.pos<0)throw new IllegalStateException("unbound label");
				int offset=l.pos-f[0];
				if(f[2]==2){if(offset<-32768||offset>32767)return false;out.setU2(f[1],offset);}else out.setU4(f[1],offset);
			}
			for(int i=0;i<handlers.size();i++){int[] h=handlers.get(i);
				handlers.set(i,new int[]{labels.get(h[0]).pos,labels.get(h[1]).pos,labels.get(h[2]).pos,h[3]});}
			return true;
		}

		/** The StackMapTable: a full frame at every recorded offset. Null without frames. */
		Bytes stackMapTable(){
			frames.remove(out.n); // a label at the very end (nothing follows it)
			if(frames.isEmpty())return null;
			Bytes t=new Bytes();t.u2(frames.size());
			int previous=-1;
			for(Map.Entry<Integer,Boolean> e:frames.entrySet()){
				int offset=e.getKey();
				t.u1(255);t.u2(previous<0?offset:offset-previous-1);previous=offset;
				int count=0;for(int i=0;i<locals.size();i++)if(locals.get(i)!=0)count++;
				t.u2(count);
				for(int i=0;i<locals.size();i++){int type=locals.get(i);if(type==0)continue;
					if(type<OBJECT)t.u1(type);else{t.u1(7);t.u2(type-OBJECT);}}
				if(e.getValue()){t.u2(1);t.u1(7);t.u2(handlerType);}else t.u2(0);
			}
			return t;
		}
	}

	// ------------------------------------------------------------------ opcodes

	static final int NOP=0x00,ACONST_NULL=0x01,ICONST_0=0x03,LCONST_0=0x09,LCONST_1=0x0a,DCONST_0=0x0e,DCONST_1=0x0f,BIPUSH=0x10,SIPUSH=0x11,LDC=0x12,LDC_W=0x13,LDC2_W=0x14,
		ILOAD=0x15,LLOAD=0x16,FLOAD=0x17,DLOAD=0x18,ALOAD=0x19,IALOAD=0x2e,LALOAD=0x2f,
		ISTORE=0x36,LSTORE=0x37,FSTORE=0x38,DSTORE=0x39,ASTORE=0x3a,IASTORE=0x4f,LASTORE=0x50,
		POP=0x57,POP2=0x58,DUP=0x59,DUP2=0x5c,SWAP=0x5f,
		IADD=0x60,LADD=0x61,DADD=0x63,ISUB=0x64,LSUB=0x65,DSUB=0x67,IMUL=0x68,LMUL=0x69,DMUL=0x6b,DDIV=0x6f,INEG=0x74,DNEG=0x77,
		ISHL=0x78,LSHL=0x79,ISHR=0x7a,LSHR=0x7b,IUSHR=0x7c,LUSHR=0x7d,IAND=0x7e,LAND=0x7f,IOR=0x80,LOR=0x81,IXOR=0x82,LXOR=0x83,IINC=0x84,
		I2L=0x85,I2D=0x87,L2I=0x88,F2D=0x8d,D2F=0x90,I2B=0x91,I2S=0x93,DCMPL=0x97,DCMPG=0x98,
		IFEQ=0x99,IFNE=0x9a,IFLT=0x9b,IFGE=0x9c,IFGT=0x9d,IFLE=0x9e,IF_ICMPEQ=0x9f,IF_ICMPNE=0xa0,IF_ICMPLT=0xa1,IF_ICMPGE=0xa2,IF_ICMPGT=0xa3,IF_ICMPLE=0xa4,IF_ACMPEQ=0xa5,IF_ACMPNE=0xa6,IFNULL=0xc6,IFNONNULL=0xc7,
		GOTO=0xa7,LOOKUPSWITCH=0xab,IRETURN=0xac,ARETURN=0xb0,RETURN=0xb1,GETFIELD=0xb4,PUTFIELD=0xb5,INVOKEVIRTUAL=0xb6,INVOKESPECIAL=0xb7,INVOKESTATIC=0xb8,
		ANEWARRAY=0xbd,ATHROW=0xbf,WIDE=0xc4,AASTORE=0x53;
	/** The opposite condition of an if opcode (IFEQ..IF_ICMPLE). */
	static int negate(int opcode){return opcode>=IFEQ&&opcode<=IF_ICMPLE?((opcode-IFEQ)^1)+IFEQ:opcode;}
}
