import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Object Support Library (the object-specifier selectors of _Pack8, System 7.5's "OSL in system"): accessor table,
 * AEResolve walking an object specifier's container chain, calling the program's accessors and evaluating whose-clause
 * tests through its compare/count callbacks, token disposal through its dispose-token callback. Programs that link the
 * old AEObjectSupportLib.o instead install their own selector dispatcher (keySelectProc), which {@link AppleEvents68k}
 * chains to before any of this runs.
 */
final class AEObjects68k {
	static final int TYPE_OBJ=0x6f626a20,TYPE_EXAMINED=0x65786d6e,TYPE_CURRENT_CONTAINER=0x63636e74,TYPE_RANGE=0x72616e67,TYPE_LOGICAL=0x6c6f6769,TYPE_COMP=0x636d7064,TYPE_ABSOLUTE=0x6162736f;
	static final int KEY_WANT=0x77616e74,KEY_FROM=0x66726f6d,KEY_FORM=0x666f726d,KEY_SELD=0x73656c64,KEY_START=0x73746172,KEY_STOP=0x73746f70,KEY_OPERATOR=0x72656c6f,KEY_TERMS=0x7465726d,KEY_LOGICAL=0x6c6f6763,KEY_OBJ1=0x6f626a31,KEY_OBJ2=0x6f626a32;
	static final int FORM_INDEX=0x696e6478,FORM_TEST=0x74657374,FORM_RANGE=0x72616e67,FORM_WHOSE=0x77686f73;
	static final int AND=0x414e4420,OR=0x4f522020,NOT=0x4e4f5420;
	static final int ERR_NOT_OBJ_SPEC=-1727,ERR_NO_SUCH_OBJECT=-1728,ERR_ACCESSOR_NOT_FOUND=-1723,ERR_BAD_TEST_KEY=-1729,ERR_NO_SUCH_LOGICAL=-1730,ERR_BAD_KEY_FORM=-1732,ERR_NOT_HANDLED=-1708;
	static final int FLAG_DO_WHOSE=1,FLAG_HANDLE_SIMPLE_RANGES=0x20;

	/** A token descriptor and whether this library made it (and so must dispose it). */
	private static final class Token {final int type,handle;final boolean owned;Token(int type,int handle,boolean owned){this.type=type;this.handle=handle;this.owned=owned;}}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final AppleEvents68k ae;
	private final Map<Long,int[]> accessors=new HashMap<Long,int[]>(); // (desiredClass<<32|containerType) -> {proc, refcon}
	private int compareProc,countProc,disposeTokenProc,getMarkTokenProc,markProc,adjustMarksProc,getErrDescProc;

	AEObjects68k(Toolbox68k tb,AppleEvents68k ae){this.tb=tb;memory=tb.memory;this.ae=ae;}

	private static long key(int cls,int container){return ((long)cls<<32)|(container&0xffffffffL);}

	/** The OSL selectors of _Pack8; returns the OSErr (or null when the selector is not one of them). */
	Integer pack8(M68kCpu cpu,int selector,int sp) throws M68kException {
		switch(selector){
			case 0x0e35:getErrDescProc=memory.readLong(sp);adjustMarksProc=memory.readLong(sp+4);markProc=memory.readLong(sp+8);getMarkTokenProc=memory.readLong(sp+12);disposeTokenProc=memory.readLong(sp+16);countProc=memory.readLong(sp+20);compareProc=memory.readLong(sp+24);return 0;
			case 0x0536:{int tokenPtr=memory.readLong(sp),flags=(short)memory.readWord(sp+4),spec=memory.readLong(sp+6);return resolve(cpu,spec,flags,tokenPtr);}
			case 0x0937:{int refcon=memory.readLong(sp+2),proc=memory.readLong(sp+6),container=memory.readLong(sp+10),cls=memory.readLong(sp+14);accessors.put(key(cls,container),new int[]{proc,refcon});return 0;}
			case 0x0738:{int container=memory.readLong(sp+6),cls=memory.readLong(sp+10);return accessors.remove(key(cls,container))==null?ERR_ACCESSOR_NOT_FOUND:0;}
			case 0x0939:{int refconPtr=memory.readLong(sp+2),procPtr=memory.readLong(sp+6),container=memory.readLong(sp+10),cls=memory.readLong(sp+14);int[] a=accessors.get(key(cls,container));
				if(a==null)return ERR_ACCESSOR_NOT_FOUND;if(procPtr!=0)memory.writeLong(procPtr,a[0]);if(refconPtr!=0)memory.writeLong(refconPtr,a[1]);return 0;}
			case 0x023a:{int tokenPtr=memory.readLong(sp);return disposeToken(cpu,tokenPtr);}
			case 0x0c3b:{int tokenPtr=memory.readLong(sp),keyData=memory.readLong(sp+4),form=memory.readLong(sp+8),containerClass=memory.readLong(sp+12),containerToken=memory.readLong(sp+16),cls=memory.readLong(sp+20);
				int[] container=ae.readDesc(containerToken),data=ae.readDesc(keyData);return callAccessor(cpu,cls,container,containerClass,form,data,tokenPtr);}
			default:return null;
		}
	}

	private int[] lookup(int cls,int container){
		int[] a=accessors.get(key(cls,container));if(a==null)a=accessors.get(key(cls,AppleEvents68k.TYPE_WILD));if(a==null)a=accessors.get(key(AppleEvents68k.TYPE_WILD,container));if(a==null)a=accessors.get(key(AppleEvents68k.TYPE_WILD,AppleEvents68k.TYPE_WILD));return a;
	}
	/** pascal OSErr accessor(desiredClass, container, containerClass, form, selectionData, VAR value, refcon): the token lands at {@code tokenPtr}. */
	private int callAccessor(M68kCpu cpu,int cls,int[] container,int containerClass,int form,int[] data,int tokenPtr) throws M68kException {
		int[] a=lookup(cls,containerClass);if(a==null){if(tokenPtr!=0)ae.writeDesc(tokenPtr,AppleEvents68k.TYPE_NULL,0);return ERR_ACCESSOR_NOT_FOUND;}
		int[] d=cpu.d.clone(),regs=cpu.a.clone();int saved=cpu.a[7];
		cpu.a[7]-=24;int containerDesc=cpu.a[7],dataDesc=containerDesc+8,value=containerDesc+16;
		ae.writeDesc(containerDesc,container[0],container[1]);ae.writeDesc(dataDesc,data[0],data[1]);ae.writeDesc(value,AppleEvents68k.TYPE_NULL,0);
		try{
			cpu.pushWord(0);int slot=cpu.a[7];
			cpu.pushLong(cls);cpu.pushLong(containerDesc);cpu.pushLong(containerClass);cpu.pushLong(form);cpu.pushLong(dataDesc);cpu.pushLong(value);cpu.pushLong(a[1]);
			cpu.callPascal(a[0],28);
			int err=(short)memory.readWord(slot);int[] token=ae.readDesc(value);
			if(tokenPtr!=0)ae.writeDesc(tokenPtr,err==0?token[0]:AppleEvents68k.TYPE_NULL,err==0?token[1]:0);
			if(err!=0&&token[1]!=0)ae.disposeDesc(value);
			if(Mac68kApi.TRACE)System.err.println("[68K] OSL accessor "+AppleEvents68k.fourcc(cls)+" in "+AppleEvents68k.fourcc(containerClass)+" form "+AppleEvents68k.fourcc(form)+" -> "+err+(err==0?" token "+AppleEvents68k.fourcc(token[0]):""));
			return err;
		}finally{cpu.a[7]=saved;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}

	/** AEDisposeToken: the program's dispose-token callback (each item of a list of tokens in turn), else AEDisposeDesc. */
	int disposeToken(M68kCpu cpu,int tokenPtr) throws M68kException {
		if(tokenPtr==0)return 0;int[] d=ae.readDesc(tokenPtr);
		AppleEvents68k.Record r=ae.recordOf(d[1]);
		if(r!=null&&r.isList){for(int[] item:new ArrayList<int[]>(r.list))disposeTokenDesc(cpu,item);r.list.clear();ae.disposeDesc(tokenPtr);return 0;}
		if(disposeTokenProc==0||d[0]==AppleEvents68k.TYPE_NULL){ae.disposeDesc(tokenPtr);return 0;}
		int[] regs=cpu.a.clone(),dr=cpu.d.clone();int saved=cpu.a[7];
		try{cpu.pushWord(0);int slot=cpu.a[7];cpu.pushLong(tokenPtr);cpu.callPascal(disposeTokenProc,4);int err=(short)memory.readWord(slot);
			if(err!=0)ae.disposeDesc(tokenPtr);else ae.writeDesc(tokenPtr,AppleEvents68k.TYPE_NULL,0);return err;}
		finally{cpu.a[7]=saved;System.arraycopy(dr,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}
	private void disposeTokenDesc(M68kCpu cpu,int[] d) throws M68kException {
		int temp=memory.allocate(8,2);try{ae.writeDesc(temp,d[0],d[1]);disposeToken(cpu,temp);}finally{memory.free(temp);}
	}
	private void release(M68kCpu cpu,Token t) throws M68kException {if(t!=null&&t.owned&&t.handle!=0)disposeTokenDesc(cpu,new int[]{t.type,t.handle});}

	/** AEResolve(objectSpecifier, callbackFlags, VAR theToken). */
	private int resolve(M68kCpu cpu,int spec,int flags,int tokenPtr) throws M68kException {
		int[] d=ae.readDesc(spec);
		if(tokenPtr!=0)ae.writeDesc(tokenPtr,AppleEvents68k.TYPE_NULL,0);
		if(d[0]!=TYPE_OBJ)return ERR_NOT_OBJ_SPEC;
		Token[] out=new Token[1];
		int err=resolveSpecifier(cpu,d,flags,null,out);
		if(err!=0)return err;
		Token t=out[0];
		if(t.owned){if(tokenPtr!=0)ae.writeDesc(tokenPtr,t.type,t.handle);}
		else{int[] copy=ae.copyDescriptor(new int[]{t.type,t.handle});if(tokenPtr!=0)ae.writeDesc(tokenPtr,copy[0],copy[1]);}
		return 0;
	}

	/** Resolves one object specifier record given the token the 'exmn' type stands for (null outside a whose clause). */
	private int resolveSpecifier(M68kCpu cpu,int[] spec,int flags,Token examined,Token[] out) throws M68kException {
		AppleEvents68k.Record r=ae.recordOf(spec[1]);
		if(r==null||!r.isRecord)return ERR_NOT_OBJ_SPEC;
		int[] want=r.items.get(KEY_WANT),from=r.items.get(KEY_FROM),form=r.items.get(KEY_FORM),seld=r.items.get(KEY_SELD);
		if(want==null||form==null||seld==null)return ERR_NOT_OBJ_SPEC;
		int cls=longOf(want),keyForm=longOf(form);
		Token[] containerOut=new Token[1];
		int err=resolveContainer(cpu,from,flags,examined,containerOut);if(err!=0)return err;
		Token container=containerOut[0];
		try{
			int[] data=seld;boolean disposeData=false;Token start=null,stop=null;
			if(keyForm==FORM_RANGE&&seld[0]==TYPE_RANGE&&(flags&FLAG_HANDLE_SIMPLE_RANGES)==0){
				// the boundary specifiers (whose containers are 'ccnt', the current container) become tokens for the accessor
				AppleEvents68k.Record range=ae.recordOf(seld[1]);
				if(range==null)return ERR_NOT_OBJ_SPEC;
				Token[] s=new Token[1];
				err=resolveBoundary(cpu,range.items.get(KEY_START),flags,examined,container,s);if(err!=0)return err;start=s[0];
				err=resolveBoundary(cpu,range.items.get(KEY_STOP),flags,examined,container,s);if(err!=0){release(cpu,start);return err;}stop=s[0];
				AppleEvents68k.Record tokens=new AppleEvents68k.Record();tokens.isRecord=true;
				tokens.items.put(KEY_START,ae.copyDescriptor(new int[]{start.type,start.handle}));tokens.items.put(KEY_STOP,ae.copyDescriptor(new int[]{stop.type,stop.handle}));
				data=new int[]{TYPE_RANGE,ae.adopt(tokens)};disposeData=true;
			}
			if(keyForm==FORM_TEST&&(flags&FLAG_DO_WHOSE)==0){
				err=resolveTest(cpu,cls,container,seld,flags,out);
				return err;
			}
			int temp=memory.allocate(8,2);
			try{
				err=callAccessor(cpu,cls,new int[]{container.type,container.handle},container.type,keyForm,data,temp);
				if(err==0){int[] t=ae.readDesc(temp);out[0]=new Token(t[0],t[1],true);}
			}finally{memory.free(temp);if(disposeData)ae.disposeDescriptor(data);release(cpu,start);release(cpu,stop);}
			return err;
		}finally{release(cpu,container);}
	}

	/** The container part of a specifier: null -> the application (typeNull token), 'obj ' -> resolved, 'exmn' -> the object under test. */
	private int resolveContainer(M68kCpu cpu,int[] from,int flags,Token examined,Token[] out) throws M68kException {
		if(from==null||from[0]==AppleEvents68k.TYPE_NULL){out[0]=new Token(AppleEvents68k.TYPE_NULL,0,false);return 0;}
		if(from[0]==TYPE_OBJ)return resolveSpecifier(cpu,from,flags,examined,out);
		if(from[0]==TYPE_EXAMINED){if(examined==null)return ERR_NOT_OBJ_SPEC;out[0]=new Token(examined.type,examined.handle,false);return 0;}
		out[0]=new Token(from[0],from[1],false);return 0; // already a token (or some other value the accessor understands)
	}
	/** A range boundary: an object specifier whose container 'ccnt' means the range's own container. */
	private int resolveBoundary(M68kCpu cpu,int[] bound,int flags,Token examined,Token container,Token[] out) throws M68kException {
		if(bound==null)return ERR_NOT_OBJ_SPEC;
		if(bound[0]!=TYPE_OBJ){out[0]=new Token(bound[0],bound[1],false);return 0;}
		AppleEvents68k.Record r=ae.recordOf(bound[1]);if(r==null)return ERR_NOT_OBJ_SPEC;
		int[] from=r.items.get(KEY_FROM);
		if(from!=null&&from[0]==TYPE_CURRENT_CONTAINER){
			// substitute the container token for 'ccnt' while resolving this one
			int[] saved=from;r.items.put(KEY_FROM,new int[]{container.type,container.handle});
			try{return resolveSpecifier(cpu,bound,flags,examined,out);}finally{r.items.put(KEY_FROM,saved);}
		}
		return resolveSpecifier(cpu,bound,flags,examined,out);
	}

	/** formTest: every element of the class in the container is fetched by index and tried against the test; the result is a list of the tokens that pass. */
	private int resolveTest(M68kCpu cpu,int cls,Token container,int[] test,int flags,Token[] out) throws M68kException {
		if(countProc==0||compareProc==0)return ERR_NOT_HANDLED;
		int count=count(cpu,cls,container);if(count<0)return count;
		AppleEvents68k.Record matches=new AppleEvents68k.Record();matches.isList=true;int listHandle=ae.adopt(matches);
		int temp=memory.allocate(8,2);
		try{
			for(int i=1;i<=count;i++){
				int index=ae.createHandleOf(longBytes(i));int[] data={AppleEvents68k.TYPE_LONG,index};
				int err=callAccessor(cpu,cls,new int[]{container.type,container.handle},container.type,FORM_INDEX,data,temp);
				tb.api.disposeHandleAndFree(index);
				if(err!=0){ae.disposeDesc(temp);continue;}
				int[] t=ae.readDesc(temp);Token element=new Token(t[0],t[1],true);
				int[] verdict=new int[1];err=evaluate(cpu,test,element,flags,verdict);
				if(err!=0){release(cpu,element);ae.disposeDescriptor(new int[]{AppleEvents68k.TYPE_LIST,listHandle});return err;}
				if(verdict[0]!=0)matches.list.add(new int[]{element.type,element.handle});else release(cpu,element);
			}
		}finally{memory.free(temp);}
		out[0]=new Token(AppleEvents68k.TYPE_LIST,listHandle,true);
		return 0;
	}
	/** pascal OSErr count(desiredType, containerClass, container, VAR result). */
	private int count(M68kCpu cpu,int cls,Token container) throws M68kException {
		int[] d=cpu.d.clone(),regs=cpu.a.clone();int saved=cpu.a[7];
		cpu.a[7]-=12;int desc=cpu.a[7],result=desc+8;ae.writeDesc(desc,container.type,container.handle);memory.writeLong(result,0);
		try{cpu.pushWord(0);int slot=cpu.a[7];cpu.pushLong(cls);cpu.pushLong(container.type);cpu.pushLong(desc);cpu.pushLong(result);cpu.callPascal(countProc,16);
			int err=(short)memory.readWord(slot);return err!=0?err:Math.max(0,memory.readLong(result));}
		finally{cpu.a[7]=saved;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}
	/** A comparison ('cmpd': operator, obj1, obj2) or logical ('logi': operator, terms) descriptor tried against one element. */
	private int evaluate(M68kCpu cpu,int[] test,Token examined,int flags,int[] verdict) throws M68kException {
		AppleEvents68k.Record r=ae.recordOf(test[1]);if(r==null)return ERR_BAD_TEST_KEY;
		if(test[0]==TYPE_LOGICAL){
			int[] op=r.items.get(KEY_LOGICAL),terms=r.items.get(KEY_TERMS);if(op==null||terms==null)return ERR_NO_SUCH_LOGICAL;
			AppleEvents68k.Record list=ae.recordOf(terms[1]);if(list==null||!list.isList)return ERR_NO_SUCH_LOGICAL;
			int operator=longOf(op);boolean result=operator==AND;
			for(int[] term:list.list){int[] v=new int[1];int err=evaluate(cpu,term,examined,flags,v);if(err!=0)return err;
				if(operator==AND)result&=v[0]!=0;else if(operator==OR)result|=v[0]!=0;else if(operator==NOT)result=v[0]==0;else return ERR_NO_SUCH_LOGICAL;}
			verdict[0]=result?1:0;return 0;
		}
		if(test[0]!=TYPE_COMP)return ERR_BAD_TEST_KEY;
		int[] op=r.items.get(KEY_OPERATOR),o1=r.items.get(KEY_OBJ1),o2=r.items.get(KEY_OBJ2);if(op==null||o1==null||o2==null)return ERR_BAD_TEST_KEY;
		Token[] t1=new Token[1],t2=new Token[1];
		int err=resolveOperand(cpu,o1,flags,examined,t1);if(err!=0)return err;
		err=resolveOperand(cpu,o2,flags,examined,t2);if(err!=0){release(cpu,t1[0]);return err;}
		try{return compare(cpu,longOf(op),t1[0],t2[0],verdict);}
		finally{release(cpu,t1[0]);release(cpu,t2[0]);}
	}
	private int resolveOperand(M68kCpu cpu,int[] operand,int flags,Token examined,Token[] out) throws M68kException {
		if(operand[0]==TYPE_OBJ)return resolveSpecifier(cpu,operand,flags,examined,out);
		if(operand[0]==TYPE_EXAMINED){out[0]=new Token(examined.type,examined.handle,false);return 0;}
		out[0]=new Token(operand[0],operand[1],false);return 0;
	}
	/** pascal OSErr compare(oper, obj1, obj2, VAR result: Boolean). */
	private int compare(M68kCpu cpu,int oper,Token a,Token b,int[] verdict) throws M68kException {
		int[] d=cpu.d.clone(),regs=cpu.a.clone();int saved=cpu.a[7];
		cpu.a[7]-=18;int d1=cpu.a[7],d2=d1+8,result=d1+16;ae.writeDesc(d1,a.type,a.handle);ae.writeDesc(d2,b.type,b.handle);memory.writeWord(result,0);
		try{cpu.pushWord(0);int slot=cpu.a[7];cpu.pushLong(oper);cpu.pushLong(d1);cpu.pushLong(d2);cpu.pushLong(result);cpu.callPascal(compareProc,16);
			int err=(short)memory.readWord(slot);verdict[0]=memory.readByte(result)!=0?1:0;return err;}
		finally{cpu.a[7]=saved;System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(regs,0,cpu.a,0,8);}
	}

	private int longOf(int[] desc) throws M68kCpuException {byte[] b=desc[1]==0?null:tb.api.handleContents(desc[1]);return b==null||b.length<4?0:MacApp68k.u4(b,0);}
	private static byte[] longBytes(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
}
