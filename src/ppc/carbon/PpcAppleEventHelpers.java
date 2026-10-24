import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CarbonLib's AEHelpers (AEBuild, AEPrint and AEStream, after Jens Alfke's AEGizmos), the Apple Event Manager's private
 * hash tables and the OSA debugging calls, for PowerPC Carbon programs.
 * <p>
 * <b>AEBuild</b> ({@code AEBuildDesc}, {@code AEBuildParameters}, {@code AEBuildAppleEvent} and their {@code v...} va_list
 * forms) parses the AEGizmos descriptor language into a Java tree first, so a syntax error never leaves a half-built
 * descriptor: the result is a null descriptor, the call answers {@code errAEBuildSyntaxError} (-1740) and the optional
 * {@code AEBuildError} gets the {@code aeBuildSyntax...} code and the byte offset of the offending token. The language:
 * <ul>
 * <li>{@code 123}, {@code -5}: 'long' (beyond 32 bits, and {@code 1.5}, {@code 2e3}: 'doub');</li>
 * <li>{@code "text"} or {@code “text”} (MacRoman or UTF-8 curly quotes): 'TEXT', the source bytes as they are;</li>
 * <li>{@code abcd} or {@code 'ab d'}: an enumerator ('enum'), padded with spaces to four characters;</li>
 * <li>{@code type(data)}: data coerced to {@code type} - integers are sized for 'shor' (2), 'long'/'magn'/'enum'/'type'/'keyw' (4),
 *     'bool' (1), 'comp' (8), 'doub' (8) and 'sing' (4); other targets go through AECoerceDesc and fall back to relabelling the bytes;
 *     an enumerator or hex string is relabelled; {@code type()} is empty data, {@code null()} the null descriptor;</li>
 * <li>{@code «0A0B»} (also {@code $0A0B$}): hex data, which must be coerced;</li>
 * <li>{@code [a, b]}: a list; {@code {key:a, key:b}}: a record, {@code type{...}} a record of another type ('obj ', 'rang'...);
 *     a coerced list is an error;</li>
 * <li>{@code @}: the next argument is an {@code AEDesc*} copied in; {@code type(@)}: the next argument is the data - by value for
 *     'shor', 'long', 'magn', 'enum', 'type', 'keyw' and 'bool' (a word), 'doub'/'sing'/'comp' (two words: varargs doubles), a C
 *     string for 'TEXT', a {@code (long length, void *data)} pair for anything else; {@code type(@@)}: the next argument is a
 *     Handle whose contents are the data. Arguments are taken in the order the {@code @}s appear.</li>
 * </ul>
 * Parameter strings ({@code AEBuildParameters}/{@code AEBuildAppleEvent}) are {@code key:desc} pairs separated by commas, without braces.
 * A PowerPC {@code va_list} is a pointer into the caller's parameter area, so the {@code v} forms read successive words there.
 * <p>
 * <b>AEPrintDescToHandle</b> writes the same language (MacRoman curly quotes and guillemets, so the text can be fed back to
 * AEBuildDesc): 'long' as a number, 'TEXT' as a string, 'enum' as {@code 'abcd'}, 'doub' as a decimal, lists and records with
 * their items, an Apple event as {@code 'clas'\'evid'{params, &attributes}}, anything else as {@code 'type'(«hex»)}.
 * <p>
 * <b>AEStream</b> keeps the stream's descriptor tree on the Java side, keyed by a small guest block that serves as the
 * {@code AEStreamRef}; {@code AEStreamClose} builds it. Every descriptor is made by the existing Apple Event Manager
 * (AECreateDesc, AECreateList, AEPutParamDesc, AECreateAppleEvent... through the 68K bridge), so the results are ordinary
 * descriptors every other AE call accepts. Writes in the wrong place (data with no open descriptor, a record item without a
 * key, closing a list as a record, a second top-level descriptor) answer {@code errAEStreamBadNesting} (-1737).
 * {@code AEStreamOpenEvent} works on the event's own Handle: at close the parameters are added to it (and the descriptor
 * written to the close call's {@code desc} when one is given).
 * <p>
 * <b>Hash tables</b> are CarbonLib-private (no header); the signatures inferred here are
 * {@code OSErr AENewHashTable(long numEntries, long keySize, long valueSize, Boolean inSysHeap, AEHashTable *outTable)}
 * (an out pointer found in the fourth slot with a zero/Boolean fifth one is accepted too), {@code AEDisposeHashTable(AEHashTable)}
 * (or a pointer to one, which is then cleared), {@code OSErr AEAddKeyEntry(table, const void *key, const void *value)} (adds or
 * overwrites), {@code Boolean AEGetKeyValue(table, const void *key, void *value)}, {@code OSErr AERemoveKeyEntry(table, const void *key)}
 * and {@code OSErr AEReplaceEntry(table, const void *key, const void *value)} ({@code errAEDescNotFound} when the key is absent),
 * {@code Boolean AEKeyAtValue(table, const void *value, void *key)}. Keys and values are fixed-size byte blocks compared bytewise.
 * <p>
 * <b>OSADebugger</b> calls are component calls ({@code _ComponentDispatch} selectors $0901-$0912 of OSA.h) on the scripting
 * component instance. Their outputs are cleared first; an invalid instance answers {@code badComponentInstance}, a component
 * that does not implement the selector (every AppleScript this VM can run: 1.x has no debugger) {@code badComponentSelector};
 * a component that says it can do the selector gets the call.
 */
final class PpcAppleEventHelpers {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcAppleEventHelpers(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108;
	static final int ERR_AE_DESC_NOT_FOUND=-1701,ERR_AE_STREAM_BAD_NESTING=-1737,ERR_AE_BUILD_SYNTAX_ERROR=-1740;
	static final int BAD_COMPONENT_INSTANCE=0x80008001,BAD_COMPONENT_SELECTOR=0x80008002,COMPONENT_CAN_DO_SELECT=-3;
	// aeBuildSyntax... (AEHelpers.h)
	static final int SYNTAX_BAD_TOKEN=1,SYNTAX_BAD_EOF=2,SYNTAX_NO_EOF=3,SYNTAX_BAD_NEGATIVE=4,SYNTAX_MISSING_QUOTE=5,SYNTAX_BAD_HEX=6,SYNTAX_ODD_HEX=7,
		SYNTAX_NO_CLOSE_HEX=8,SYNTAX_UNCOERCED_HEX=9,SYNTAX_NO_CLOSE_STRING=10,SYNTAX_BAD_DESC=11,SYNTAX_BAD_DATA=12,SYNTAX_NO_CLOSE_PAREN=13,
		SYNTAX_NO_CLOSE_BRACKET=14,SYNTAX_NO_CLOSE_BRACE=15,SYNTAX_NO_KEY=16,SYNTAX_NO_COLON=17,SYNTAX_COERCED_LIST=18,SYNTAX_UNCOERCED_DOUBLE_AT=19;
	static final int TYPE_NULL=0x6e756c6c,TYPE_LONG=0x6c6f6e67,TYPE_SHOR=0x73686f72,TYPE_BOOL=0x626f6f6c,TYPE_MAGN=0x6d61676e,TYPE_COMP=0x636f6d70,TYPE_DOUB=0x646f7562,
		TYPE_SING=0x73696e67,TYPE_TEXT=0x54455854,TYPE_ENUM=0x656e756d,TYPE_TYPE=0x74797065,TYPE_KEYW=0x6b657977,TYPE_LIST=0x6c697374,TYPE_RECO=0x7265636f,
		TYPE_AEVT=0x61657674,TYPE_TRUE=0x74727565,TYPE_FALS=0x66616c73,TYPE_WILD=0x2a2a2a2a;
	static final int KEY_EVENT_CLASS=0x6576636c,KEY_EVENT_ID=0x65766964,KEY_ADDRESS=0x61646472,KEY_RETURN_ID=0x72746964,KEY_TRANSACTION=0x7472616e,KEY_OPTIONAL=0x6f70746b; // keyEventClassAttr... keyOptionalKeywordAttr
	static final int HASH_MAGIC=0x68617368; // 'hash': first long of an AEHashTable token block

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}

	void define(PpcLibraries l){defineBuild(l);definePrint(l);defineStream(l);defineHashTables(l);defineDebugger(l);}

	// ================================================================== descriptor trees

	private static final int DATA=0,DESC=1,LIST=2,RECORD=3;
	/** A descriptor to be built: plain data (possibly to be coerced from {@code from}), a copy of an existing AEDesc, a list or a record. */
	private static final class Node {
		final int kind;int type,from,desc;byte[] data;ByteArrayOutputStream open;
		final List<Node> items=new ArrayList<Node>();final List<Integer> keys=new ArrayList<Integer>();
		Node(int kind,int type){this.kind=kind;this.type=type;}
		static Node data(int type,byte[] data){Node n=new Node(DATA,type);n.data=data;return n;}
	}

	private void writeNull(int desc) throws M68kCpuException {if(desc!=0){memory.writeLong(desc,TYPE_NULL);memory.writeLong(desc+4,0);}}
	private int createDesc(int type,byte[] data,int out) throws M68kException {
		if(type==TYPE_NULL&&data.length==0){writeNull(out);return NO_ERR;}
		int p=rt.allocate(Math.max(1,data.length),4);
		try{memory.load(p,data);return (short)rt.toolbox("AECreateDesc",type,p,data.length,out);}finally{memory.free(p);}
	}
	/** Builds {@code n} into the AEDesc at {@code out} through the Apple Event Manager; returns the OSErr (out is a null descriptor on failure). */
	private int build(Node n,int out) throws M68kException {
		writeNull(out);
		switch(n.kind){
			case DATA:{
				if(n.from!=0&&n.from!=n.type){ // a literal of another type: the Apple Event Manager's coercion, else the bytes relabelled (as AEGizmos did)
					int temp=rt.allocate(8,4);
					try{if(createDesc(n.from,n.data,temp)==NO_ERR){int e=(short)rt.toolbox("AECoerceDesc",temp,n.type,out);rt.toolbox("AEDisposeDesc",temp);if(e==NO_ERR)return NO_ERR;writeNull(out);}}
					finally{memory.free(temp);}
				}
				return createDesc(n.type,n.data,out);}
			case DESC:return n.desc==0||memory.readLong(n.desc+4)==0&&memory.readLong(n.desc)==TYPE_NULL?NO_ERR:(short)rt.toolbox("AEDuplicateDesc",n.desc,out);
			default:{
				boolean record=n.kind==RECORD;
				int e=(short)rt.toolbox("AECreateList",0,0,record?1:0,out);if(e!=NO_ERR){writeNull(out);return e;}
				int item=rt.allocate(8,4);
				try{
					for(int i=0;i<n.items.size();i++){
						e=build(n.items.get(i),item);
						if(e==NO_ERR){e=(short)(record?rt.toolbox("AEPutParamDesc",out,n.keys.get(i),item):rt.toolbox("AEPutDesc",out,0,item));rt.toolbox("AEDisposeDesc",item);}
						if(e!=NO_ERR){rt.toolbox("AEDisposeDesc",out);writeNull(out);return e;}
					}
				}finally{memory.free(item);}
				if(record&&n.type!=TYPE_RECO)memory.writeLong(out,n.type); // a record of another type ('obj ', 'rang'...) keeps the record layout under its own type
				return NO_ERR;}
		}
	}
	/** Puts keyed nodes into an Apple event as parameters. */
	private int putParams(int event,List<Integer> keys,List<Node> items) throws M68kException {
		int item=rt.allocate(8,4);
		try{for(int i=0;i<items.size();i++){int e=build(items.get(i),item);if(e==NO_ERR){e=(short)rt.toolbox("AEPutParamDesc",event,keys.get(i),item);rt.toolbox("AEDisposeDesc",item);}if(e!=NO_ERR)return e;}}
		finally{memory.free(item);}
		return NO_ERR;
	}

	// ================================================================== AEBuild

	/** The varargs: successive argument slots (the direct forms) or words of a va_list. */
	private interface Args{int next() throws M68kCpuException;}
	private Args slots(int first){int[] at={first};return ()->arg(at[0]++);}
	private Args vaList(int pointer){int[] at={pointer};return ()->{int v=at[0]==0?0:memory.readLong(at[0]);at[0]+=4;return v;};}

	private static final class SyntaxError extends Exception {final int code,pos;SyntaxError(int code,int pos){super(null,null,false,false);this.code=code;this.pos=pos;}}

	private byte[] cBytes(int p) throws M68kCpuException {
		if(p==0)return new byte[0];
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		for(int i=0;i<1<<24;i++){int b=memory.readByte(p+i);if(b==0)break;out.write(b);}
		return out.toByteArray();
	}
	private static byte[] be(long v,int n){byte[] b=new byte[n];for(int i=n-1;i>=0;i--){b[i]=(byte)v;v>>=8;}return b;}

	/** A recursive-descent parser of the AEGizmos language over the format's bytes. */
	private final class Parser {
		final byte[] s;final Args args;int p;
		Parser(byte[] s,Args args){this.s=s;this.args=args;}
		int at(int i){return i<s.length?s[i]&0xff:-1;}
		int skip(){while(p<s.length){int c=s[p]&0xff;if(c==' '||c=='\t'||c=='\r'||c=='\n'||c==0xca/* MacRoman non-breaking space */)p++;else break;}return at(p);}
		boolean identStart(int c){return c>='A'&&c<='Z'||c>='a'&&c<='z'||c=='_';}
		boolean identChar(int c){return identStart(c)||c>='0'&&c<='9';}
		/** Length of a string opener at p (", “ in MacRoman or UTF-8), 0 if none; {@code close} gets the matching closer. */
		int stringOpen(int i,byte[][] close){
			int c=at(i);
			if(c=='"'){close[0]=new byte[]{'"'};return 1;}
			if(c==0xd2){close[0]=new byte[]{(byte)0xd3};return 1;}
			if(c==0xe2&&at(i+1)==0x80&&at(i+2)==0x9c){close[0]=new byte[]{(byte)0xe2,(byte)0x80,(byte)0x9d};return 3;}
			return 0;
		}
		/** Length of a hex opener at p («, $), 0 if none. */
		int hexOpen(int i,byte[][] close){
			int c=at(i);
			if(c==0xc7){close[0]=new byte[]{(byte)0xc8};return 1;}
			if(c==0xc2&&at(i+1)==0xab){close[0]=new byte[]{(byte)0xc2,(byte)0xbb};return 2;}
			if(c=='$'){close[0]=new byte[]{'$'};return 1;}
			return 0;
		}
		boolean matches(int i,byte[] t){for(int k=0;k<t.length;k++)if(at(i+k)!=(t[k]&0xff))return false;return true;}

		Node parseDesc() throws SyntaxError,M68kCpuException {
			int c=skip();byte[][] close=new byte[1][];
			if(c<0)throw new SyntaxError(SYNTAX_BAD_EOF,p);
			if(c=='[')return parseList();
			if(c=='{')return parseRecord(TYPE_RECO);
			if(c=='@'){int start=p++;if(at(p)=='@')throw new SyntaxError(SYNTAX_UNCOERCED_DOUBLE_AT,start);Node n=new Node(DESC,0);n.desc=args.next();return n;}
			if(c=='-'||c>='0'&&c<='9')return number(0);
			if(stringOpen(p,close)>0)return Node.data(TYPE_TEXT,string());
			if(hexOpen(p,close)>0)throw new SyntaxError(SYNTAX_UNCOERCED_HEX,p);
			if(c=='\''||identStart(c)){
				int type=ident();int d=skip();
				if(d=='(')return coerced(type);
				if(d=='{')return parseRecord(type);
				if(d=='[')throw new SyntaxError(SYNTAX_COERCED_LIST,p);
				return Node.data(TYPE_ENUM,be(type,4));
			}
			throw new SyntaxError(c==':'||c==','||c==')'||c==']'||c=='}'?SYNTAX_BAD_DESC:SYNTAX_BAD_TOKEN,p);
		}
		/** A four-character code: 'quoted' or a bare identifier, padded with spaces. */
		int ident() throws SyntaxError {
			int start=p;StringBuilder b=new StringBuilder();
			if(at(p)=='\''){p++;while(true){int c=at(p);if(c<0)throw new SyntaxError(SYNTAX_MISSING_QUOTE,start);p++;if(c=='\'')break;b.append((char)c);}}
			else while(identChar(at(p)))b.append((char)at(p++));
			if(b.length()>4)throw new SyntaxError(SYNTAX_BAD_TOKEN,start);
			while(b.length()<4)b.append(' ');
			return b.charAt(0)<<24|b.charAt(1)<<16|b.charAt(2)<<8|b.charAt(3);
		}
		/** A number literal: an integer ('long') or, with a fraction/exponent or beyond 32 bits, a double ('doub'). */
		Node number(int coerceTo) throws SyntaxError {
			int start=p;if(at(p)=='-'){p++;if(!(at(p)>='0'&&at(p)<='9'))throw new SyntaxError(SYNTAX_BAD_NEGATIVE,start);}
			while(at(p)>='0'&&at(p)<='9')p++;
			boolean real=false;
			if(at(p)=='.'&&at(p+1)>='0'&&at(p+1)<='9'){real=true;p++;while(at(p)>='0'&&at(p)<='9')p++;}
			if((at(p)=='e'||at(p)=='E')&&(at(p+1)>='0'&&at(p+1)<='9'||(at(p+1)=='-'||at(p+1)=='+')&&at(p+2)>='0'&&at(p+2)<='9')){real=true;p+=2;while(at(p)>='0'&&at(p)<='9')p++;}
			String text=new String(s,start,p-start,java.nio.charset.StandardCharsets.ISO_8859_1);
			if(!real){try{long v=Long.parseLong(text);if(v==(int)v||coerceTo==TYPE_COMP||coerceTo==TYPE_MAGN&&v>=0&&v<=0xffffffffL)return integer(v,coerceTo);}catch(NumberFormatException e){}}
			return real(Double.parseDouble(text),coerceTo);
		}
		Node integer(long v,int to){
			switch(to){
				case 0:case TYPE_LONG:return Node.data(TYPE_LONG,be(v,4));
				case TYPE_SHOR:return Node.data(to,be(v,2));
				case TYPE_BOOL:return Node.data(to,new byte[]{(byte)(v!=0?1:0)});
				case TYPE_MAGN:case TYPE_ENUM:case TYPE_TYPE:case TYPE_KEYW:return Node.data(to,be(v,4));
				case TYPE_COMP:return Node.data(to,be(v,8));
				case TYPE_DOUB:return Node.data(to,be(Double.doubleToLongBits(v),8));
				case TYPE_SING:return Node.data(to,be(Float.floatToIntBits(v),4));
				default:{Node n=Node.data(to,be(v,4));n.from=TYPE_LONG;return n;}
			}
		}
		Node real(double v,int to){
			switch(to){
				case 0:case TYPE_DOUB:return Node.data(TYPE_DOUB,be(Double.doubleToLongBits(v),8));
				case TYPE_SING:return Node.data(to,be(Float.floatToIntBits((float)v),4));
				case TYPE_LONG:return Node.data(to,be((long)v,4));
				case TYPE_SHOR:return Node.data(to,be((long)v,2));
				default:{Node n=Node.data(to,be(Double.doubleToLongBits(v),8));n.from=TYPE_DOUB;return n;}
			}
		}
		byte[] string() throws SyntaxError {
			byte[][] close=new byte[1][];int start=p;p+=stringOpen(p,close);int from=p;
			while(true){if(p>=s.length)throw new SyntaxError(SYNTAX_NO_CLOSE_STRING,start);if(matches(p,close[0]))break;p++;}
			byte[] b=java.util.Arrays.copyOfRange(s,from,p);p+=close[0].length;return b;
		}
		byte[] hex() throws SyntaxError {
			byte[][] close=new byte[1][];int start=p;p+=hexOpen(p,close);
			ByteArrayOutputStream out=new ByteArrayOutputStream();int digits=0,acc=0;
			while(true){
				if(p>=s.length)throw new SyntaxError(SYNTAX_NO_CLOSE_HEX,start);
				if(matches(p,close[0])){p+=close[0].length;break;}
				int c=at(p);
				if(c==' '||c=='\t'||c=='\r'||c=='\n'){p++;continue;}
				int v=Character.digit(c,16);if(v<0||c>127)throw new SyntaxError(SYNTAX_BAD_HEX,p);
				acc=acc<<4|v;if(++digits%2==0){out.write(acc);acc=0;}p++;
			}
			if(digits%2!=0)throw new SyntaxError(SYNTAX_ODD_HEX,start);
			return out.toByteArray();
		}
		/** {@code type(...)}: p is at the '('. */
		Node coerced(int type) throws SyntaxError,M68kCpuException {
			p++;int c=skip();byte[][] close=new byte[1][];Node n;
			if(c==')'){p++;return Node.data(type,new byte[0]);}
			if(c<0)throw new SyntaxError(SYNTAX_BAD_EOF,p);
			if(c=='@'){p++;if(at(p)=='@'){p++;int h=args.next();byte[] b=h==0?null:rt.toolbox.api.handleContents(h);n=Node.data(type,b==null?new byte[0]:b);}else n=argument(type);}
			else if(c=='-'||c>='0'&&c<='9')n=number(type);
			else if(stringOpen(p,close)>0){n=Node.data(type,string());if(type!=TYPE_TEXT)n.from=TYPE_TEXT;}
			else if(hexOpen(p,close)>0)n=Node.data(type,hex());
			else if(c=='\''||identStart(c))n=Node.data(type,be(ident(),4));
			else throw new SyntaxError(SYNTAX_BAD_DATA,p);
			c=skip();
			if(c<0)throw new SyntaxError(SYNTAX_BAD_EOF,p);
			if(c!=')')throw new SyntaxError(SYNTAX_NO_CLOSE_PAREN,p);
			p++;return n;
		}
		/** {@code type(@)}: the data from the next argument(s), by the conventions of AEGizmos. */
		Node argument(int type) throws M68kCpuException {
			switch(type){
				case TYPE_SHOR:return Node.data(type,be(args.next(),2));
				case TYPE_LONG:case TYPE_MAGN:case TYPE_ENUM:case TYPE_TYPE:case TYPE_KEYW:return Node.data(type,be(args.next(),4));
				case TYPE_BOOL:return Node.data(type,new byte[]{(byte)(args.next()!=0?1:0)});
				case TYPE_DOUB:case TYPE_SING:case TYPE_COMP:{long hi=args.next()&0xffffffffL,lo=args.next()&0xffffffffL;long v=hi<<32|lo;
					return type==TYPE_SING?Node.data(type,be(Float.floatToIntBits((float)Double.longBitsToDouble(v)),4)):Node.data(type,be(v,8));}
				case TYPE_TEXT:return Node.data(type,cBytes(args.next()));
				default:{int size=args.next(),ptr=args.next();return Node.data(type,size<=0||ptr==0?new byte[0]:memory.readBytes(ptr,Math.min(size,1<<24)));}
			}
		}
		Node parseList() throws SyntaxError,M68kCpuException {
			Node n=new Node(LIST,TYPE_LIST);p++;
			if(skip()==']'){p++;return n;}
			while(true){
				n.items.add(parseDesc());
				int c=skip();
				if(c==','){p++;continue;}
				if(c==']'){p++;return n;}
				throw new SyntaxError(c<0?SYNTAX_BAD_EOF:SYNTAX_NO_CLOSE_BRACKET,p);
			}
		}
		Node parseRecord(int type) throws SyntaxError,M68kCpuException {
			Node n=new Node(RECORD,type);p++;
			if(skip()=='}'){p++;return n;}
			while(true){
				keyed(n);
				int c=skip();
				if(c==','){p++;continue;}
				if(c=='}'){p++;return n;}
				throw new SyntaxError(c<0?SYNTAX_BAD_EOF:SYNTAX_NO_CLOSE_BRACE,p);
			}
		}
		/** One {@code key:desc} item into {@code n}. */
		void keyed(Node n) throws SyntaxError,M68kCpuException {
			int c=skip();
			if(c<0)throw new SyntaxError(SYNTAX_BAD_EOF,p);
			if(c!='\''&&!identStart(c))throw new SyntaxError(SYNTAX_NO_KEY,p);
			int key=ident();
			c=skip();if(c<0)throw new SyntaxError(SYNTAX_BAD_EOF,p);
			if(c!=':')throw new SyntaxError(SYNTAX_NO_COLON,p);
			p++;n.keys.add(key);n.items.add(parseDesc());
		}
		/** A whole format: one descriptor. */
		Node descriptor() throws SyntaxError,M68kCpuException {Node n=parseDesc();if(skip()>=0)throw new SyntaxError(SYNTAX_NO_EOF,p);return n;}
		/** A parameter list: {@code key:desc, ...} without braces (possibly empty). */
		Node parameters() throws SyntaxError,M68kCpuException {
			Node n=new Node(RECORD,TYPE_RECO);
			if(skip()<0)return n;
			while(true){keyed(n);int c=skip();if(c<0)return n;if(c!=',')throw new SyntaxError(SYNTAX_NO_EOF,p);p++;}
		}
	}

	private void buildError(int errorPtr,int code,int pos) throws M68kCpuException {if(errorPtr!=0){memory.writeLong(errorPtr,code);memory.writeLong(errorPtr+4,pos);}}

	/** AEBuildDesc(dst, error, src, ...) / vAEBuildDesc. */
	private int buildDesc(int dst,int error,int src,Args args) throws M68kException {
		writeNull(dst);buildError(error,0,0);
		if(dst==0||src==0)return PARAM_ERR;
		Node n;
		try{n=new Parser(cBytes(src),args).descriptor();}catch(SyntaxError e){buildError(error,e.code,e.pos);return ERR_AE_BUILD_SYNTAX_ERROR;}
		return build(n,dst);
	}
	/** AEBuildParameters(event, error, format, ...) / vAEBuildParameters: the event gets nothing when the format is wrong. */
	private int buildParameters(int event,int error,int format,Args args) throws M68kException {
		buildError(error,0,0);
		if(event==0||format==0)return PARAM_ERR;
		Node n;
		try{n=new Parser(cBytes(format),args).parameters();}catch(SyntaxError e){buildError(error,e.code,e.pos);return ERR_AE_BUILD_SYNTAX_ERROR;}
		return putParams(event,n.keys,n.items);
	}
	/** Creates an Apple event addressed by {@code (addressType, addressData, addressLength)} into {@code result}. */
	private int createEvent(int cls,int id,int addressType,int addressData,int addressLength,int returnId,int transaction,int result) throws M68kException {
		int target=rt.allocate(8,4);
		try{
			int e=(short)rt.toolbox("AECreateDesc",addressType,addressData,addressData==0?0:Math.max(0,addressLength),target);if(e!=NO_ERR)return e;
			e=(short)rt.toolbox("AECreateAppleEvent",cls,id,target,(short)returnId,transaction,result);
			rt.toolbox("AEDisposeDesc",target);
			if(e!=NO_ERR)writeNull(result);
			return e;
		}finally{memory.free(target);}
	}
	/** AEBuildAppleEvent(class, id, addressType, addressData, addressLength, returnID, transactionID, result, error, format, ...). */
	private int buildAppleEvent(int[] a,Args args) throws M68kException {
		int result=a[7],error=a[8],format=a[9];
		writeNull(result);buildError(error,0,0);
		if(result==0)return PARAM_ERR;
		Node n;
		try{n=new Parser(cBytes(format),args).parameters();}catch(SyntaxError e){buildError(error,e.code,e.pos);return ERR_AE_BUILD_SYNTAX_ERROR;}
		int e=createEvent(a[0],a[1],a[2],a[3],a[4],a[5],a[6],result);if(e!=NO_ERR)return e;
		e=putParams(result,n.keys,n.items);
		if(e!=NO_ERR){rt.toolbox("AEDisposeDesc",result);writeNull(result);}
		return e;
	}

	private void defineBuild(PpcLibraries l){
		l.register(null,"AEBuildDesc",(r,c)->{int dst=arg(0),err=arg(1),src=arg(2);ret(buildDesc(dst,err,src,slots(3)));});
		l.register(null,"vAEBuildDesc",(r,c)->{int dst=arg(0),err=arg(1),src=arg(2);ret(buildDesc(dst,err,src,vaList(arg(3))));});
		l.register(null,"AEBuildParameters",(r,c)->{int ev=arg(0),err=arg(1),fmt=arg(2);ret(buildParameters(ev,err,fmt,slots(3)));});
		l.register(null,"vAEBuildParameters",(r,c)->{int ev=arg(0),err=arg(1),fmt=arg(2);ret(buildParameters(ev,err,fmt,vaList(arg(3))));});
		l.register(null,"AEBuildAppleEvent",(r,c)->{int[] a=new int[10];for(int i=0;i<10;i++)a[i]=arg(i);ret(buildAppleEvent(a,slots(10)));});
		l.register(null,"vAEBuildAppleEvent",(r,c)->{int[] a=new int[10];for(int i=0;i<10;i++)a[i]=arg(i);ret(buildAppleEvent(a,vaList(arg(10))));});
	}

	// ================================================================== AEPrintDescToHandle

	private static final byte[] HEX="0123456789ABCDEF".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
	private static void ascii(ByteArrayOutputStream out,String s){for(int i=0;i<s.length();i++)out.write(s.charAt(i));}
	private static void code(ByteArrayOutputStream out,int v){out.write('\'');out.write(v>>>24);out.write(v>>>16&255);out.write(v>>>8&255);out.write(v&255);out.write('\'');}
	private static void hex(ByteArrayOutputStream out,byte[] b){out.write(0xc7);for(byte x:b){out.write(HEX[(x>>4)&15]);out.write(HEX[x&15]);}out.write(0xc8);}
	private static long num(byte[] b){long v=0;for(byte x:b)v=v<<8|(x&0xff);return v;}

	/** 0 plain, 1 list, 2 record, 3 Apple event - by what the Apple Event Manager answers for the descriptor. */
	private int kind(int desc) throws M68kException {
		int type=memory.readLong(desc);if(type==TYPE_AEVT)return 3;if(memory.readLong(desc+4)==0)return 0;
		int n=rt.allocate(4,4);int e=(short)rt.toolbox("AECountItems",desc,n);memory.free(n);
		return e!=NO_ERR?0:type==TYPE_LIST?1:2;
	}
	private byte[] dataOf(int desc) throws M68kException {int h=memory.readLong(desc+4);byte[] b=h==0?null:rt.toolbox.api.handleContents(h);return b==null?new byte[0]:b;}
	private int attributeType(int event,int key) throws M68kException {
		int type=rt.allocate(4,4),buf=rt.allocate(4,4),size=rt.allocate(4,4);
		try{memory.writeLong(buf,0);int e=(short)rt.toolbox("AEGetAttributePtr",event,key,TYPE_TYPE,type,buf,4,size);return e==NO_ERR?memory.readLong(buf):0x3f3f3f3f;}
		finally{memory.free(type);memory.free(buf);memory.free(size);}
	}
	/** The items of a list, record or event: params (and for events the explicit attributes, marked '&'). */
	private void printItems(ByteArrayOutputStream out,int desc,boolean keyed,int depth) throws M68kException {
		int count=rt.allocate(4,4),key=rt.allocate(4,4),item=rt.allocate(8,4);
		try{
			rt.toolbox("AECountItems",desc,count);int n=memory.readLong(count);boolean first=true;
			for(int i=1;i<=n;i++){
				writeNull(item);if((short)rt.toolbox("AEGetNthDesc",desc,i,TYPE_WILD,key,item)!=NO_ERR)continue;
				if(!first)ascii(out,", ");first=false;
				if(keyed){code(out,memory.readLong(key));out.write(':');}
				print(out,item,depth+1);rt.toolbox("AEDisposeDesc",item);
			}
			if(memory.readLong(desc)==TYPE_AEVT){ // the attributes a program put on the event (the header's class, ID, address, return and transaction IDs are left out)
				AppleEvents68k.Record rec=rt.toolbox.appleEvents.recordOf(memory.readLong(desc+4));
				List<Integer> attrs=rec==null?new ArrayList<Integer>():new ArrayList<Integer>(rec.attributes.keySet());
				for(int a:attrs){
					if(a==KEY_EVENT_CLASS||a==KEY_EVENT_ID||a==KEY_ADDRESS||a==KEY_RETURN_ID||a==KEY_TRANSACTION)continue;
					writeNull(item);if((short)rt.toolbox("AEGetAttributeDesc",desc,a,TYPE_WILD,item)!=NO_ERR)continue;
					if(!first)ascii(out,", ");first=false;
					out.write('&');code(out,a);out.write(':');print(out,item,depth+1);rt.toolbox("AEDisposeDesc",item);
				}
			}
		}finally{memory.free(count);memory.free(key);memory.free(item);}
	}
	private void print(ByteArrayOutputStream out,int desc,int depth) throws M68kException {
		int type=memory.readLong(desc);
		if(depth>64){code(out,type);ascii(out,"(...)");return;}
		switch(kind(desc)){
			case 3:code(out,attributeType(desc,KEY_EVENT_CLASS));out.write('\\');code(out,attributeType(desc,KEY_EVENT_ID));out.write('{');printItems(out,desc,true,depth);out.write('}');return;
			case 1:out.write('[');printItems(out,desc,false,depth);out.write(']');return;
			case 2:if(type!=TYPE_RECO)code(out,type);out.write('{');printItems(out,desc,true,depth);out.write('}');return;
			default:break;
		}
		byte[] b=dataOf(desc);
		if(type==TYPE_LONG&&b.length==4){ascii(out,Integer.toString((int)num(b)));return;}
		if(type==TYPE_ENUM&&b.length==4){code(out,(int)num(b));return;}
		if((type==TYPE_TYPE||type==TYPE_KEYW)&&b.length==4){code(out,type);out.write('(');code(out,(int)num(b));out.write(')');return;}
		if(type==TYPE_SHOR&&b.length==2){code(out,type);ascii(out,"("+(short)num(b)+")");return;}
		if(type==TYPE_DOUB&&b.length==8){double v=Double.longBitsToDouble(num(b));if(!Double.isNaN(v)&&!Double.isInfinite(v)){ascii(out,Double.toString(v));return;}}
		if(type==TYPE_TEXT){boolean plain=true;for(byte x:b){int c=x&0xff;if(c==0xd3||c<0x20&&c!='\r'&&c!='\n'&&c!='\t'){plain=false;break;}}
			if(plain){out.write(0xd2);out.write(b,0,b.length);out.write(0xd3);return;}}
		code(out,type);out.write('(');if(b.length>0)hex(out,b);out.write(')');
	}

	private void definePrint(PpcLibraries l){
		l.register(null,"AEPrintDescToHandle",(r,c)->{ // (const AEDesc *desc, Handle *result): the handle holds the characters, no terminator
			int desc=arg(0),result=arg(1);
			if(result!=0)memory.writeLong(result,0);
			if(desc==0||result==0){ret(PARAM_ERR);return;}
			ByteArrayOutputStream out=new ByteArrayOutputStream();print(out,desc,0);byte[] b=out.toByteArray();
			int h=rt.toolbox("NewHandle",b.length);if(h==0){ret(MEM_FULL_ERR);return;}
			if(b.length>0)memory.load(memory.readLong(h),b);
			memory.writeLong(result,h);ret(NO_ERR);});
	}

	// ================================================================== AEStream

	/** An AEStreamRef's state: the tree under construction and, for event streams, the event (an 8-byte AEDesc block of ours). */
	private final class Stream {
		final Node root=new Node(RECORD,TYPE_RECO); // top level: params of an event stream, else at most one descriptor
		final ArrayDeque<Node> open=new ArrayDeque<Node>();
		final List<Integer> owned=new ArrayList<Integer>(); // AEDesc blocks of copies made by AEStreamWriteAEDesc
		final List<Integer> optional=new ArrayList<Integer>();
		int event,eventDesc; // our AEDesc block holding the event; the caller's AEDesc (AEStreamOpenEvent) or 0 (AEStreamCreateEvent)
		Integer key;
		Node container(){return open.isEmpty()?root:open.peek();}
	}
	private final Map<Integer,Stream> streams=new HashMap<Integer,Stream>();

	/** Adds a finished or opening node where the stream stands; returns the OSErr. */
	private int add(Stream s,Node n){
		Node c=s.container();
		if(c.kind==DATA)return ERR_AE_STREAM_BAD_NESTING;
		if(c==s.root&&s.event==0){if(!c.items.isEmpty()||s.key!=null)return ERR_AE_STREAM_BAD_NESTING;c.keys.add(0);c.items.add(n);return NO_ERR;}
		if(c.kind==RECORD){if(s.key==null)return ERR_AE_STREAM_BAD_NESTING;c.keys.add(s.key);c.items.add(n);s.key=null;return NO_ERR;}
		if(s.key!=null)return ERR_AE_STREAM_BAD_NESTING; // a list item has no key
		c.items.add(n);return NO_ERR;
	}
	private int openNode(Stream s,Node n){int e=add(s,n);if(e==NO_ERR)s.open.push(n);return e;}
	private int closeNode(Stream s,int kind){
		if(s.open.isEmpty()||s.open.peek().kind!=kind||kind!=DATA&&s.key!=null)return ERR_AE_STREAM_BAD_NESTING;
		Node n=s.open.pop();if(kind==DATA){n.data=n.open.toByteArray();n.open=null;}return NO_ERR;
	}
	private int writeKey(Stream s,int key){
		Node c=s.container();
		if(s.key!=null||c.kind!=RECORD||c==s.root&&s.event==0)return ERR_AE_STREAM_BAD_NESTING;
		s.key=key;return NO_ERR;
	}
	private Node bytes(int type,int data,int length) throws M68kCpuException {return Node.data(type,data==0||length<=0?new byte[0]:memory.readBytes(data,Math.min(length,1<<24)));}
	private Node opened(int type){Node n=new Node(DATA,type);n.open=new ByteArrayOutputStream();return n;}
	private int newStream(Stream s) throws M68kCpuException {int ref=rt.allocate(8,4);memory.writeLong(ref,0x41457374);memory.writeLong(ref+4,0);streams.put(ref,s);return ref;} // 'AEst'
	private void releaseStream(int ref,Stream s) throws M68kException {
		streams.remove(ref);
		for(int d:s.owned){rt.toolbox("AEDisposeDesc",d);memory.free(d);}
		if(s.event!=0)memory.free(s.event);
		memory.free(ref);
	}
	/** AEStreamClose(ref, desc): builds the stream into {@code desc} (when given) and disposes of the stream either way. */
	private int closeStream(int ref,int desc) throws M68kException {
		Stream s=streams.get(ref);if(s==null){writeNull(desc);return PARAM_ERR;}
		try{
			if(desc==0){if(s.event!=0&&s.eventDesc==0)rt.toolbox("AEDisposeDesc",s.event);return NO_ERR;} // only disposing; an event of AEStreamOpenEvent stays its owner's
			writeNull(desc);
			if(!s.open.isEmpty()||s.key!=null){if(s.event!=0&&s.eventDesc==0)rt.toolbox("AEDisposeDesc",s.event);return ERR_AE_STREAM_BAD_NESTING;}
			if(s.event==0)return s.root.items.isEmpty()?NO_ERR:build(s.root.items.get(0),desc);
			int e=putParams(s.event,s.root.keys,s.root.items);
			if(e==NO_ERR&&!s.optional.isEmpty())e=addOptional(s.event,s.optional);
			if(e!=NO_ERR){if(s.eventDesc==0)rt.toolbox("AEDisposeDesc",s.event);return e;}
			memory.copy(s.event,desc,8);return NO_ERR;
		}finally{releaseStream(ref,s);}
	}
	/** Adds keywords to the event's keyOptionalKeywordAttr list (creating it). */
	private int addOptional(int event,List<Integer> keys) throws M68kException {
		int list=rt.allocate(8,4),kw=rt.allocate(4,4);
		try{
			writeNull(list);
			if((short)rt.toolbox("AEGetAttributeDesc",event,KEY_OPTIONAL,TYPE_LIST,list)!=NO_ERR){int e=(short)rt.toolbox("AECreateList",0,0,0,list);if(e!=NO_ERR)return e;}
			for(int k:keys){memory.writeLong(kw,k);rt.toolbox("AEPutPtr",list,0,TYPE_KEYW,kw,4);}
			int e=(short)rt.toolbox("AEPutAttributeDesc",event,KEY_OPTIONAL,list);rt.toolbox("AEDisposeDesc",list);return e;
		}finally{memory.free(list);memory.free(kw);}
	}

	private interface StreamOp{int run(Stream s) throws M68kException;}
	private void stream(PpcLibraries l,String name,StreamOp op){l.register(null,name,(r,c)->{Stream s=streams.get(arg(0));ret(s==null?PARAM_ERR:op.run(s));});}

	private void defineStream(PpcLibraries l){
		l.register(null,"AEStreamOpen",(r,c)->ret(newStream(new Stream())));
		l.register(null,"AEStreamClose",(r,c)->ret(closeStream(arg(0),arg(1))));
		stream(l,"AEStreamOpenDesc",s->openNode(s,opened(arg(1))));
		stream(l,"AEStreamWriteData",s->{Node c=s.container();if(c.kind!=DATA||c.open==null)return ERR_AE_STREAM_BAD_NESTING;int n=arg(2);if(n<0)return PARAM_ERR;if(n>0&&arg(1)!=0)c.open.write(memory.readBytes(arg(1),Math.min(n,1<<24)),0,Math.min(n,1<<24));return NO_ERR;});
		stream(l,"AEStreamCloseDesc",s->closeNode(s,DATA));
		stream(l,"AEStreamWriteDesc",s->add(s,bytes(arg(1),arg(2),arg(3))));
		stream(l,"AEStreamWriteAEDesc",s->{int src=arg(1);if(src==0)return PARAM_ERR;
			int copy=rt.allocate(8,4);writeNull(copy);int e=(short)rt.toolbox("AEDuplicateDesc",src,copy);if(e!=NO_ERR){memory.free(copy);return e;}
			Node n=new Node(DESC,0);n.desc=copy;e=add(s,n);if(e!=NO_ERR){rt.toolbox("AEDisposeDesc",copy);memory.free(copy);return e;}
			s.owned.add(copy);return NO_ERR;});
		stream(l,"AEStreamOpenList",s->openNode(s,new Node(LIST,TYPE_LIST)));
		stream(l,"AEStreamCloseList",s->closeNode(s,LIST));
		stream(l,"AEStreamOpenRecord",s->openNode(s,new Node(RECORD,arg(1))));
		stream(l,"AEStreamSetRecordType",s->{Node c=s.container();if(c.kind!=RECORD||c==s.root)return ERR_AE_STREAM_BAD_NESTING;c.type=arg(1);return NO_ERR;});
		stream(l,"AEStreamCloseRecord",s->closeNode(s,RECORD));
		stream(l,"AEStreamWriteKey",s->writeKey(s,arg(1)));
		stream(l,"AEStreamWriteKeyDesc",s->{int e=writeKey(s,arg(1));return e!=NO_ERR?e:add(s,bytes(arg(2),arg(3),arg(4)));});
		stream(l,"AEStreamOpenKeyDesc",s->{int e=writeKey(s,arg(1));return e!=NO_ERR?e:openNode(s,opened(arg(2)));});
		stream(l,"AEStreamOptionalParam",s->{if(s.event==0)return ERR_AE_STREAM_BAD_NESTING;if(!s.optional.contains(arg(1)))s.optional.add(arg(1));return NO_ERR;});
		l.register(null,"AEStreamCreateEvent",(r,c)->{ // (class, id, targetType, targetData, targetLength, returnID, transactionID): NULL when the event cannot be made
			int[] a=new int[7];for(int i=0;i<7;i++)a[i]=arg(i);
			int ev=rt.allocate(8,4);writeNull(ev);
			if(createEvent(a[0],a[1],a[2],a[3],a[4],a[5],a[6],ev)!=NO_ERR){memory.free(ev);ret(0);return;}
			Stream s=new Stream();s.event=ev;ret(newStream(s));});
		l.register(null,"AEStreamOpenEvent",(r,c)->{ // (AppleEvent *event): the stream adds its parameters to this event's Handle at close
			int event=arg(0);if(event==0||memory.readLong(event)!=TYPE_AEVT||memory.readLong(event+4)==0){ret(0);return;}
			int ev=rt.allocate(8,4);memory.copy(event,ev,8);
			Stream s=new Stream();s.event=ev;s.eventDesc=event;ret(newStream(s));});
	}

	// ================================================================== private hash tables

	/** An AEHashTable: fixed-size keys and values, compared as bytes. */
	private static final class HashTable {final int keySize,valueSize;final LinkedHashMap<ByteBuffer,byte[]> entries=new LinkedHashMap<ByteBuffer,byte[]>();HashTable(int k,int v){keySize=k;valueSize=v;}}
	private final Map<Integer,HashTable> tables=new HashMap<Integer,HashTable>();
	private HashTable table(int t){return tables.get(t);}
	private ByteBuffer key(HashTable t,int p) throws M68kCpuException {return ByteBuffer.wrap(memory.readBytes(p,t.keySize));}

	private void defineHashTables(PpcLibraries l){
		l.register(null,"AENewHashTable",(r,c)->{ // (numEntries, keySize, valueSize, Boolean inSysHeap, AEHashTable *outTable)
			int entries=arg(0),keySize=arg(1),valueSize=arg(2);
			int out=arg(4)==0&&(arg(3)&~0xff)!=0?arg(3):arg(4); // an out pointer in the fourth slot (no heap flag) is taken too
			if(out==0){ret(PARAM_ERR);return;}
			memory.writeLong(out,0);
			if(keySize<=0||keySize>0x10000||valueSize<0||valueSize>0x10000||entries<0){ret(PARAM_ERR);return;}
			int t=rt.allocate(16,4);memory.writeLong(t,HASH_MAGIC);memory.writeLong(t+4,keySize);memory.writeLong(t+8,valueSize);memory.writeLong(t+12,entries);
			tables.put(t,new HashTable(keySize,valueSize));memory.writeLong(out,t);ret(NO_ERR);});
		l.register(null,"AEDisposeHashTable",(r,c)->{ // (AEHashTable) - or a pointer to one, which is cleared
			int t=arg(0);
			if(tables.remove(t)!=null){memory.writeLong(t,0);memory.free(t);ret(NO_ERR);return;}
			if(t!=0&&tables.containsKey(memory.readLong(t))){int inner=memory.readLong(t);tables.remove(inner);memory.writeLong(inner,0);memory.free(inner);memory.writeLong(t,0);ret(NO_ERR);return;}
			ret(PARAM_ERR);});
		l.register(null,"AEAddKeyEntry",(r,c)->{HashTable t=table(arg(0));if(t==null||arg(1)==0||arg(2)==0&&t.valueSize>0){ret(PARAM_ERR);return;}
			t.entries.put(key(t,arg(1)),memory.readBytes(arg(2),t.valueSize));ret(NO_ERR);});
		l.register(null,"AEReplaceEntry",(r,c)->{HashTable t=table(arg(0));if(t==null||arg(1)==0||arg(2)==0&&t.valueSize>0){ret(PARAM_ERR);return;}
			ByteBuffer k=key(t,arg(1));if(!t.entries.containsKey(k)){ret(ERR_AE_DESC_NOT_FOUND);return;}
			t.entries.put(k,memory.readBytes(arg(2),t.valueSize));ret(NO_ERR);});
		l.register(null,"AEGetKeyValue",(r,c)->{HashTable t=table(arg(0));if(t==null||arg(1)==0){ret(0);return;}
			byte[] v=t.entries.get(key(t,arg(1)));if(v==null){ret(0);return;}
			if(arg(2)!=0&&v.length>0)memory.load(arg(2),v);ret(1);});
		l.register(null,"AERemoveKeyEntry",(r,c)->{HashTable t=table(arg(0));if(t==null||arg(1)==0){ret(PARAM_ERR);return;}
			ret(t.entries.remove(key(t,arg(1)))==null?ERR_AE_DESC_NOT_FOUND:NO_ERR);});
		l.register(null,"AEKeyAtValue",(r,c)->{HashTable t=table(arg(0));if(t==null||arg(1)==0){ret(0);return;}
			byte[] want=memory.readBytes(arg(1),t.valueSize);
			for(Map.Entry<ByteBuffer,byte[]> e:t.entries.entrySet())if(java.util.Arrays.equals(e.getValue(),want)){if(arg(2)!=0)memory.load(arg(2),e.getKey().array());ret(1);return;}
			ret(0);});
	}

	// ================================================================== OSA debugging

	/**
	 * An OSADebugger call: outputs cleared ({@code outs} = {argument slot, 4 for a reference/ID or 8 for an AEDesc}), then the
	 * component's answer to kComponentCanDoSelect decides whether the selector goes to it.
	 */
	private void debugger(PpcLibraries l,String name,int selector,int paramWords,int... outs){
		l.register(null,name,(r,c)->{
			for(int i=0;i<outs.length;i+=2){int p=arg(outs[i]);if(p!=0){if(outs[i+1]==8)writeNull(p);else memory.writeLong(p,0);}}
			int ci=arg(0);if(ci==0){ret(BAD_COMPONENT_INSTANCE);return;}
			int[] params=new int[paramWords];for(int i=0;i<paramWords;i++)params[i]=arg(1+i);
			Components68k components=rt.toolbox.components;
			int can=components.call(ci,COMPONENT_CAN_DO_SELECT,new int[]{selector,2});
			if(can==BAD_COMPONENT_INSTANCE){ret(BAD_COMPONENT_INSTANCE);return;}
			if(can==0||can==BAD_COMPONENT_SELECTOR){ret(BAD_COMPONENT_SELECTOR);return;} // no AppleScript debugger in this component
			int[][] args=new int[paramWords][];for(int i=0;i<paramWords;i++)args[i]=new int[]{params[i],4};
			ret(components.call(ci,selector,args));});
	}

	private void defineDebugger(PpcLibraries l){
		// kOSADebugger... selectors (OSA.h): sessions $0901-$0905, breakpoints $0910-$0912, call frames $0906-$090B
		debugger(l,"OSADebuggerCreateSession",0x0901,3,3,4);        // (ci, OSAID inScript, OSAID inContext, OSADebugSessionRef *outSession)
		debugger(l,"OSADebuggerGetSessionState",0x0902,2,2,8);      // (ci, session, AERecord *outState)
		debugger(l,"OSADebuggerSessionStep",0x0903,2);              // (ci, session, OSADebugStepKind)
		debugger(l,"OSADebuggerDisposeSession",0x0904,1);           // (ci, session)
		debugger(l,"OSADebuggerGetStatementRanges",0x0905,2,2,8);   // (ci, session, AEDescList *outStatementRangeArray)
		debugger(l,"OSADebuggerGetBreakpoint",0x0910,3,3,4);        // (ci, session, UInt32 inSrcOffset, OSAID *outBreakpoint)
		debugger(l,"OSADebuggerSetBreakpoint",0x0911,3);            // (ci, session, UInt32 inSrcOffset, OSAID inBreakpoint)
		debugger(l,"OSADebuggerGetDefaultBreakpoint",0x0912,2,2,4); // (ci, session, OSAID *outBreakpoint)
		debugger(l,"OSADebuggerGetCurrentCallFrame",0x0906,2,2,4);  // (ci, session, OSADebugCallFrameRef *outCallFrame)
		debugger(l,"OSADebuggerGetCallFrameState",0x0907,2,2,8);    // (ci, callFrame, AERecord *outState)
		debugger(l,"OSADebuggerGetVariable",0x0908,3,3,4);          // (ci, callFrame, const AEDesc *inVariableName, OSAID *outVariable)
		debugger(l,"OSADebuggerSetVariable",0x0909,3);              // (ci, callFrame, const AEDesc *inVariableName, OSAID inVariable)
		debugger(l,"OSADebuggerGetPreviousCallFrame",0x090A,2,2,4); // (ci, currentFrame, OSADebugCallFrameRef *outPrevFrame)
		debugger(l,"OSADebuggerDisposeCallFrame",0x090B,1);         // (ci, callFrame)
		debugger(l,"OSADebuggerCountVariables",0x090C,2,2,4);       // not in UI 3.4: inferred (ci, callFrame, long *outVariableCount), the next selector
	}
}
