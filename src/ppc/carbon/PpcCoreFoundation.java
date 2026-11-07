import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CoreFoundation for PowerPC programs, as CarbonLib (and later CarbonFrameworkLib) carried it on Mac OS 8.6-9: the CFType
 * object model (retain counts, type IDs, equality, hashing, descriptions), allocators, strings, and - further down - the
 * other types. Every object lives on the host side behind a small guest block, so the program holds a real, distinct
 * pointer; the block's second word is the type ID (a debugging aid - the types are opaque to programs).
 * <p>
 * CFStringEncoding numbers are TextEncodings (0 Mac OS Roman, 1 MacJapanese, $08000100 UTF-8 ...), converted by the Text
 * Encoding Converter of {@link PpcTextServices}. A CFRange argument arrives as its two fields, location and length, in
 * consecutive argument slots; a function returning a CFRange (or another structure) by value gets a hidden pointer to the
 * result first, in r3.
 */
final class PpcCoreFoundation {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcCoreFoundation(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	private PpcTextServices text(){return rt.libraries.textServices;}

	// type IDs (fixed here; CF hands them out at run time, programs only compare them)
	static final int TYPE_TYPE=1,TYPE_ALLOCATOR=2,TYPE_STRING=7,TYPE_BOOLEAN=21,TYPE_NUMBER=22,TYPE_DATA=19,TYPE_ARRAY=18,TYPE_DICTIONARY=17,
		TYPE_SET=16,TYPE_BAG=15,TYPE_DATE=24,TYPE_TIME_ZONE=25,TYPE_URL=26,TYPE_BUNDLE=27,TYPE_CHARACTER_SET=28,TYPE_PLUG_IN=29,
		TYPE_PLUG_IN_INSTANCE=30,TYPE_UUID=31,TYPE_RUN_LOOP=32,TYPE_NULL=33;
	static final int NOT_FOUND=-1;
	static final int ENCODING_MAC_ROMAN=0,ENCODING_UNICODE=0x0100,ENCODING_UTF8=0x08000100,ENCODING_ASCII=0x0600,ENCODING_ISO_LATIN1=0x0201,ENCODING_INVALID=0xffffffff;
	static final int COMPARE_CASE_INSENSITIVE=1,COMPARE_BACKWARDS=4,COMPARE_ANCHORED=8,COMPARE_NONLITERAL=16,COMPARE_LOCALIZED=32,COMPARE_NUMERICALLY=64;

	// ------------------------------------------------------------------ objects

	/** A CF object: its guest address, type, retain count and allocator. */
	abstract static class CFObject {
		int address,references=1,allocator;final int type;
		boolean constant; // CFSTR strings, the booleans, the predefined allocators: never freed
		CFObject(int type){this.type=type;}
		/** Called once the last reference is gone: release what it holds. */
		void dispose(PpcCoreFoundation cf) throws M68kException {}
		/** Guest blocks the object handed out (character buffers), freed with it. */
		List<Integer> buffers;
	}
	private final Map<Integer,CFObject> objects=new HashMap<Integer,CFObject>();
	/** Registers {@code o} behind a new guest block; returns its address (one reference). */
	int register(CFObject o) throws M68kCpuException {
		int p=rt.allocate(16,4);memory.writeLong(p,0x687a4346);memory.writeLong(p+4,o.type); // 'hzCF', type ID
		o.address=p;objects.put(p,o);return p;
	}
	CFObject object(int ref){return ref==0?null:objects.get(ref);}
	@SuppressWarnings("unchecked")
	<T extends CFObject> T object(int ref,Class<T> kind){CFObject o=object(ref);return kind.isInstance(o)?(T)o:null;}
	boolean isObject(int ref){return objects.containsKey(ref);}
	int retain(int ref){CFObject o=object(ref);if(o!=null&&!o.constant)o.references++;return ref;}
	void release(int ref) throws M68kException {
		CFObject o=object(ref);if(o==null||o.constant)return;
		if(--o.references>0)return;
		objects.remove(ref);
		o.dispose(this);
		if(o.buffers!=null)for(int b:o.buffers)if(memory.isAllocated(b))memory.free(b);
		if(memory.isAllocated(ref))memory.free(ref);
	}
	/** A guest block owned by {@code o} (freed when it is). */
	int buffer(CFObject o,int size) throws M68kCpuException {int p=rt.allocate(size,4);if(o.buffers==null)o.buffers=new ArrayList<Integer>();o.buffers.add(p);return p;}

	/** CFEqual: same type and same value (strings by characters, numbers by value, collections element by element). */
	boolean equal(int a,int b) throws M68kException {
		if(a==b)return true;
		CFObject x=object(a),y=object(b);if(x==null||y==null)return false;
		if(x instanceof CFNumber&&y instanceof CFNumber)return ((CFNumber)x).compareTo((CFNumber)y)==0;
		if(x.type!=y.type)return false;
		if(x instanceof CFString)return ((CFString)x).text.toString().equals(((CFString)y).text.toString());
		if(x instanceof CFData)return java.util.Arrays.equals(((CFData)x).bytes(this),((CFData)y).bytes(this));
		if(x instanceof ValueEquality)return ((ValueEquality)x).sameValue(this,y);
		return false;
	}
	/** CFHash: equal objects hash alike. */
	int hash(int ref) throws M68kException {
		CFObject o=object(ref);if(o==null)return ref;
		if(o instanceof CFString)return ((CFString)o).text.toString().hashCode();
		if(o instanceof CFNumber){CFNumber n=(CFNumber)o;return n.isFloat()?Double.hashCode(n.doubleValue()):Long.hashCode(n.longValue());}
		if(o instanceof CFData)return java.util.Arrays.hashCode(((CFData)o).bytes(this));
		if(o instanceof ValueEquality)return ((ValueEquality)o).valueHash(this);
		return ref;
	}
	/** Types whose CFEqual compares contents (collections, dates, URLs ...). */
	interface ValueEquality { boolean sameValue(PpcCoreFoundation cf,CFObject other) throws M68kException; int valueHash(PpcCoreFoundation cf) throws M68kException; }

	/** The text CFCopyDescription gives (and CFShow prints). */
	String describe(int ref) throws M68kException {
		CFObject o=object(ref);
		if(o==null)return ref==0?"(null)":String.format("<%08X>",ref);
		if(o instanceof CFString)return ((CFString)o).text.toString();
		if(o instanceof CFNumber)return ((CFNumber)o).isFloat()?Double.toString(((CFNumber)o).doubleValue()):Long.toString(((CFNumber)o).longValue());
		if(o instanceof CFBoolean)return ((CFBoolean)o).value?"true":"false";
		if(o instanceof Describable)return ((Describable)o).description(this);
		return String.format("<%s %08X>",typeName(o.type),ref);
	}
	interface Describable { String description(PpcCoreFoundation cf) throws M68kException; }
	static String typeName(int type){
		switch(type){
			case TYPE_TYPE:return "CFType";case TYPE_ALLOCATOR:return "CFAllocator";case TYPE_STRING:return "CFString";case TYPE_BOOLEAN:return "CFBoolean";
			case TYPE_NUMBER:return "CFNumber";case TYPE_DATA:return "CFData";case TYPE_ARRAY:return "CFArray";case TYPE_DICTIONARY:return "CFDictionary";
			case TYPE_SET:return "CFSet";case TYPE_BAG:return "CFBag";case TYPE_DATE:return "CFDate";case TYPE_TIME_ZONE:return "CFTimeZone";
			case TYPE_URL:return "CFURL";case TYPE_BUNDLE:return "CFBundle";case TYPE_CHARACTER_SET:return "CFCharacterSet";case TYPE_PLUG_IN:return "CFPlugIn";
			case TYPE_PLUG_IN_INSTANCE:return "CFPlugInInstance";case TYPE_UUID:return "CFUUID";case TYPE_RUN_LOOP:return "CFRunLoop";case TYPE_NULL:return "CFNull";
			default:return "CFType";
		}
	}

	// ------------------------------------------------------------------ allocators

	/** An allocator: a predefined one, or one the program made with its own context (callbacks run in the program). */
	static final class CFAllocator extends CFObject {
		final String name;int context; // a copy of the CFAllocatorContext (or 0)
		CFAllocator(String name){super(TYPE_ALLOCATOR);this.name=name;}
	}
	private int systemAllocator,nullAllocator,mallocAllocator,defaultAllocator;
	/** kCFAllocatorUseContext is not an object: a marker value for CFAllocatorCreate. */
	static final int ALLOCATOR_USE_CONTEXT=0x0227;
	private int predefinedAllocator(String name) throws M68kCpuException {CFAllocator a=new CFAllocator(name);a.constant=true;return register(a);}
	private void makeAllocators() throws M68kCpuException {
		if(systemAllocator!=0)return;
		systemAllocator=predefinedAllocator("kCFAllocatorSystemDefault");mallocAllocator=predefinedAllocator("kCFAllocatorMalloc");nullAllocator=predefinedAllocator("kCFAllocatorNull");
		defaultAllocator=systemAllocator;
	}
	/** The allocator a creating call names (NULL = the default). */
	private int allocatorOf(int ref) throws M68kCpuException {makeAllocators();return ref==0?defaultAllocator:ref;}
	/** CFAllocatorAllocate on {@code allocator}: a guest Ptr, or the program's allocate callback. */
	int allocate(int allocator,int size,int hint) throws M68kException {
		CFAllocator a=object(allocatorOf(allocator),CFAllocator.class);
		if(size<=0||a!=null&&a.address==nullAllocator)return 0;
		if(a!=null&&a.context!=0){int fn=memory.readLong(a.context+24);if(fn!=0)return rt.cpu.call(fn,size,hint,memory.readLong(a.context+4));} // allocate(size, hint, info)
		return rt.allocate(size,4);
	}
	void deallocate(int allocator,int p) throws M68kException {
		if(p==0)return;CFAllocator a=object(allocatorOf(allocator),CFAllocator.class);
		if(a!=null&&a.address==nullAllocator)return;
		if(a!=null&&a.context!=0){int fn=memory.readLong(a.context+32);if(fn!=0)rt.cpu.call(fn,p,memory.readLong(a.context+4));return;} // deallocate(ptr, info)
		if(memory.isAllocated(p))memory.free(p);
	}

	// ------------------------------------------------------------------ strings

	/** A string: its characters, mutable or not; guest buffers handed out by the Ptr calls are cached per encoding. */
	static final class CFString extends CFObject {
		final StringBuilder text;final boolean mutable;
		Map<Integer,Integer> cStrings,pStrings;int characters;
		/** For CFStringCreateMutableWithExternalCharactersNoCopy: the program's UniChar buffer, kept in step. */
		int external,externalCapacity;
		CFString(String text,boolean mutable){super(TYPE_STRING);this.text=new StringBuilder(text);this.mutable=mutable;}
	}
	/** The text of a CFString, or null. */
	String text(int ref){CFString s=object(ref,CFString.class);return s==null?null:s.text.toString();}
	/** Replaces a CFString's text. */
	void setText(int ref,String value) throws M68kCpuException {CFString s=object(ref,CFString.class);if(s!=null){s.text.setLength(0);s.text.append(value);changed(s);}}
	int newString(String value) throws M68kCpuException {return register(new CFString(value==null?"":value,false));}
	int newMutableString(String value) throws M68kCpuException {return register(new CFString(value==null?"":value,true));}
	private CFString string(int ref){return object(ref,CFString.class);}
	/** A mutable string changed: the program's external buffer follows. */
	private void changed(CFString s) throws M68kCpuException {
		if(s.external!=0){int n=Math.min(s.text.length(),s.externalCapacity);for(int i=0;i<n;i++)memory.writeWord(s.external+2*i,s.text.charAt(i));}
	}
	/** CFSTR(): one immortal string per distinct C string (compared by bytes, as the compilers pool them). */
	private final Map<String,Integer> constantStrings=new HashMap<String,Integer>();
	int constantString(byte[] bytes) throws M68kCpuException {
		String key=new String(bytes,StandardCharsets.ISO_8859_1);Integer v=constantStrings.get(key);if(v!=null)return v;
		CFString s=new CFString(decode(ENCODING_MAC_ROMAN,bytes),false);s.constant=true;int p=register(s);constantStrings.put(key,p);return p;
	}
	String decode(int encoding,byte[] bytes){
		if(encoding==ENCODING_INVALID)encoding=text().systemEncoding();
		if(encoding==0x0bff)return new String(bytes,StandardCharsets.US_ASCII); // kCFStringEncodingNonLossyASCII
		if(encoding==0x0b01)return new String(bytes,StandardCharsets.ISO_8859_1); // NextStep Latin: close enough
		return text().decode(encoding,bytes);
	}
	byte[] encode(int encoding,String value){
		if(encoding==ENCODING_INVALID)encoding=text().systemEncoding();
		if(encoding==0x0bff){StringBuilder sb=new StringBuilder();for(char ch:value.toCharArray())if(ch<128)sb.append(ch);else sb.append(String.format("\\u%04x",(int)ch));return sb.toString().getBytes(StandardCharsets.US_ASCII);}
		if(encoding==0x0b01)return value.getBytes(StandardCharsets.ISO_8859_1);
		return text().encode(encoding,value);
	}
	/** True when every character of {@code value} survives a round trip through {@code encoding}. */
	boolean lossless(int encoding,String value){try{return decode(encoding,encode(encoding,value)).equals(value);}catch(RuntimeException e){return false;}}
	private String unichars(int p,int n) throws M68kCpuException {StringBuilder sb=new StringBuilder(Math.max(0,n));for(int i=0;i<n;i++)sb.append((char)memory.readWord(p+2*i));return sb.toString();}
	/** A CFRange argument (location, length) clipped to a string of {@code length}. */
	private static int[] range(int location,int length,int total){int a=Math.max(0,Math.min(location,total));int b=Math.max(a,Math.min(total,location+length));return new int[]{a,b};}
	private void writeRange(int p,int location,int length) throws M68kCpuException {if(p!=0){memory.writeLong(p,location);memory.writeLong(p+4,length);}}

	/** CFStringCompare with its flags (case insensitive, backwards is ignored for ordering, numerically compares digit runs as numbers). */
	static int compare(String a,String b,int flags){
		if((flags&COMPARE_NUMERICALLY)!=0){
			int i=0,j=0;
			while(i<a.length()&&j<b.length()){
				char x=a.charAt(i),y=b.charAt(j);
				if(Character.isDigit(x)&&Character.isDigit(y)){
					int si=i,sj=j;while(i<a.length()&&Character.isDigit(a.charAt(i)))i++;while(j<b.length()&&Character.isDigit(b.charAt(j)))j++;
					java.math.BigInteger m=new java.math.BigInteger(a.substring(si,i)),n=new java.math.BigInteger(b.substring(sj,j));
					int k=m.compareTo(n);if(k!=0)return k<0?-1:1;continue;
				}
				int k=(flags&COMPARE_CASE_INSENSITIVE)!=0?Character.compare(Character.toLowerCase(Character.toUpperCase(x)),Character.toLowerCase(Character.toUpperCase(y))):Character.compare(x,y);
				if(k!=0)return k<0?-1:1;i++;j++;
			}
			int k=(a.length()-i)-(b.length()-j);return k<0?-1:k>0?1:0;
		}
		if((flags&COMPARE_LOCALIZED)!=0){java.text.Collator c=java.text.Collator.getInstance();c.setStrength((flags&COMPARE_CASE_INSENSITIVE)!=0?java.text.Collator.SECONDARY:java.text.Collator.TERTIARY);int k=c.compare(a,b);return k<0?-1:k>0?1:0;}
		int k=(flags&COMPARE_CASE_INSENSITIVE)!=0?a.compareToIgnoreCase(b):a.compareTo(b);return k<0?-1:k>0?1:0;
	}
	/** CFStringFindWithOptions over [from, to): the found range {location, length}, or null. */
	static int[] find(String s,String what,int from,int to,int flags){
		if(what.isEmpty())return null;
		boolean ci=(flags&COMPARE_CASE_INSENSITIVE)!=0,back=(flags&COMPARE_BACKWARDS)!=0,anchored=(flags&COMPARE_ANCHORED)!=0;
		int n=what.length();
		if(back){for(int i=to-n;i>=from;i--){if(s.regionMatches(ci,i,what,0,n))return new int[]{i,n};if(anchored)break;}}
		else{for(int i=from;i+n<=to;i++){if(s.regionMatches(ci,i,what,0,n))return new int[]{i,n};if(anchored)break;}}
		return null;
	}
	/** Format with CF's %@ (an object's description) on top of the C library's printf. */
	private String format(int fmtRef,PpcStdCLib.VarArgs args) throws M68kException {
		String f=text(fmtRef);if(f==null)return "";
		StringBuilder out=new StringBuilder();int i=0;
		while(i<f.length()){
			int pct=f.indexOf('%',i);if(pct<0){out.append(f,i,f.length());break;}
			out.append(f,i,pct);
			int j=pct+1;while(j<f.length()&&"-+ #0123456789.*hlqLz".indexOf(f.charAt(j))>=0)j++;
			if(j>=f.length()){out.append(f.substring(pct));break;}
			char conv=f.charAt(j);String spec=f.substring(pct,j+1);
			if(conv=='@'){out.append(describe(args.nextInt()));}
			else if(conv=='C'){out.append((char)(args.nextInt()&0xffff));} // unichar
			else if(conv=='S'){int p=args.nextInt();if(p!=0)for(int k=0;k<65536;k++){int ch=memory.readWord(p+2*k);if(ch==0)break;out.append((char)ch);}}
			else if(conv=='%'){out.append('%');}
			else{ // one conversion through the C library, reading its own arguments (the '*' ones too)
				byte[] one=PpcStdCLib.format(rt,encode(ENCODING_MAC_ROMAN,spec),args);
				out.append(decode(conv=='s'||conv=='P'||conv=='c'?text().systemEncoding():ENCODING_MAC_ROMAN,one));
			}
			i=j+1;
		}
		return out.toString();
	}
	/** Lower/upper/capitalized forms (CFStringLowercase ...). */
	private static String capitalize(String s){StringBuilder sb=new StringBuilder(s.length());boolean start=true;for(char ch:s.toCharArray()){sb.append(start?Character.toTitleCase(ch):Character.toLowerCase(ch));start=!Character.isLetterOrDigit(ch)&&ch!='\'';}return sb.toString();}
	private static boolean isNewline(char ch){return ch=='\n'||ch=='\r'||ch==0x2028||ch==0x2029||ch==0x85;}

	// ------------------------------------------------------------------ booleans, numbers, data

	static final class CFBoolean extends CFObject {final boolean value;CFBoolean(boolean value){super(TYPE_BOOLEAN);this.value=value;constant=true;}}
	private int trueRef,falseRef;
	int bool(boolean v) throws M68kCpuException {if(trueRef==0){trueRef=register(new CFBoolean(true));falseRef=register(new CFBoolean(false));}return v?trueRef:falseRef;}

	/** A number: its CFNumberType (1-16) and value, integer or floating. */
	static final class CFNumber extends CFObject implements Comparable<CFNumber> {
		final int numberType;final long integer;final double real;
		CFNumber(int numberType,long integer,double real){super(TYPE_NUMBER);this.numberType=numberType;this.integer=integer;this.real=real;}
		boolean isFloat(){return numberType==5||numberType==6||numberType==12||numberType==13;}
		double doubleValue(){return isFloat()?real:integer;}
		long longValue(){return isFloat()?(long)real:integer;}
		@Override public int compareTo(CFNumber o){if(isFloat()||o.isFloat())return Double.compare(doubleValue(),o.doubleValue());return Long.compare(integer,o.integer);}
	}
	/** Byte size of a CFNumberType's C value. */
	static int numberSize(int type){switch(type){case 1:case 7:return 1;case 2:case 8:return 2;case 4:case 11:case 6:case 13:return 8;default:return 4;}}
	int newNumber(int type,int valuePtr) throws M68kCpuException {
		switch(type){
			case 1:case 7:return register(new CFNumber(type,(byte)memory.readByte(valuePtr),0));
			case 2:case 8:return register(new CFNumber(type,(short)memory.readWord(valuePtr),0));
			case 4:case 11:return register(new CFNumber(type,((long)memory.readLong(valuePtr)<<32)|(memory.readLong(valuePtr+4)&0xffffffffL),0));
			case 5:case 12:return register(new CFNumber(type,0,Float.intBitsToFloat(memory.readLong(valuePtr))));
			case 6:case 13:return register(new CFNumber(type,0,Double.longBitsToDouble(((long)memory.readLong(valuePtr)<<32)|(memory.readLong(valuePtr+4)&0xffffffffL))));
			default:return register(new CFNumber(type,memory.readLong(valuePtr),0));
		}
	}
	/** CFNumberGetValue: the value as {@code type} at {@code out}; false when it had to be rounded or truncated. */
	boolean numberValue(CFNumber n,int type,int out) throws M68kCpuException {
		double d=n.doubleValue();long v=n.longValue();boolean exact;
		switch(type){
			case 1:case 7:memory.writeByte(out,(int)v);exact=!n.isFloat()&&v==(byte)v;break;
			case 2:case 8:memory.writeWord(out,(int)v);exact=!n.isFloat()&&v==(short)v;break;
			case 4:case 11:memory.writeLong(out,(int)(v>>>32));memory.writeLong(out+4,(int)v);exact=!n.isFloat()||d==v;break;
			case 5:case 12:memory.writeLong(out,Float.floatToRawIntBits((float)d));exact=(float)d==d;break;
			case 6:case 13:{long b=Double.doubleToRawLongBits(d);memory.writeLong(out,(int)(b>>>32));memory.writeLong(out+4,(int)b);exact=!(!n.isFloat()&&(long)d!=v);break;}
			default:memory.writeLong(out,(int)v);exact=!n.isFloat()&&v==(int)v||n.isFloat()&&d==(int)v;break;
		}
		return exact;
	}
	private int nanRef,posInfRef,negInfRef;

	/** Bytes in guest memory (CFDataGetBytePtr hands out the pointer; a mutable data object grows by reallocating). */
	static final class CFData extends CFObject implements Describable {
		int bytes,length,capacity;final boolean mutable;boolean noCopy;int bytesDeallocator;
		CFData(boolean mutable){super(TYPE_DATA);this.mutable=mutable;}
		byte[] bytes(PpcCoreFoundation cf) throws M68kCpuException {return length==0?new byte[0]:cf.memory.readBytes(bytes,length);}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {if(noCopy){if(bytesDeallocator!=0&&bytesDeallocator!=cf.nullAllocator)cf.deallocate(bytesDeallocator,bytes);}else if(bytes!=0&&cf.memory.isAllocated(bytes))cf.memory.free(bytes);}
		@Override public String description(PpcCoreFoundation cf) throws M68kException {StringBuilder sb=new StringBuilder("<");byte[] b=bytes(cf);for(int i=0;i<b.length;i++){if(i>0&&i%4==0)sb.append(' ');sb.append(String.format("%02x",b[i]&0xff));}return sb.append('>').toString();}
	}
	int newData(byte[] b,boolean mutable) throws M68kCpuException {
		CFData d=new CFData(mutable);d.capacity=Math.max(16,b.length);d.bytes=rt.allocate(d.capacity,4);d.length=b.length;memory.load(d.bytes,b);return register(d);
	}
	/** Grows a mutable data object to hold {@code length} bytes (new bytes zeroed). */
	void setDataLength(CFData d,int length) throws M68kCpuException {
		length=Math.max(0,length);
		if(length>d.capacity){int cap=Math.max(length,d.capacity*2);int p=rt.allocate(cap,4);if(d.length>0)memory.copy(d.bytes,p,d.length);if(!d.noCopy&&d.bytes!=0&&memory.isAllocated(d.bytes))memory.free(d.bytes);d.bytes=p;d.capacity=cap;d.noCopy=false;}
		else if(length>d.length)memory.fill(d.bytes+d.length,length-d.length,0);
		d.length=length;
	}
	/** Replaces bytes [location, location+length) of {@code d} with {@code with}. */
	void replaceBytes(CFData d,int location,int length,byte[] with) throws M68kCpuException {
		byte[] old=d.bytes(this);location=Math.max(0,Math.min(location,old.length));length=Math.max(0,Math.min(length,old.length-location));
		byte[] b=new byte[old.length-length+with.length];
		System.arraycopy(old,0,b,0,location);System.arraycopy(with,0,b,location,with.length);System.arraycopy(old,location+length,b,location+with.length,old.length-location-length);
		setDataLength(d,b.length);if(b.length>0)memory.load(d.bytes,b);
	}

	// ------------------------------------------------------------------ definitions

	void define(PpcLibraries l){
		defineTypes(l);defineStrings(l);defineScalars(l);defineCollections(l);defineTime(l);defineURLs(l);defineBundles(l);definePreferences(l);definePropertyLists(l);defineCharacterSets(l);defineMisc(l);defineXML(l);defineCarbon16(l);
	}

	private void defineTypes(PpcLibraries l){
		l.register(null,"CFRetain",(r,c)->ret(retain(arg(0))));
		l.register(null,"CFRelease",(r,c)->release(arg(0)));
		l.register(null,"CFGetRetainCount",(r,c)->{CFObject o=object(arg(0));ret(o==null?0:o.constant?Integer.MAX_VALUE:o.references);});
		l.register(null,"CFGetTypeID",(r,c)->{CFObject o=object(arg(0));ret(o==null?0:o.type);});
		l.register(null,"CFGetAllocator",(r,c)->{CFObject o=object(arg(0));ret(o==null||o.allocator==0?allocatorOf(0):o.allocator);});
		l.register(null,"CFEqual",(r,c)->ret(equal(arg(0),arg(1))?1:0));
		l.register(null,"CFHash",(r,c)->ret(hash(arg(0))));
		l.register(null,"CFCopyDescription",(r,c)->ret(newString(describe(arg(0)))));
		l.register(null,"CFCopyTypeIDDescription",(r,c)->ret(newString(typeName(arg(0)))));
		l.register(null,"CFShow",(r,c)->System.err.println("[ppc "+rt.app.name+" CFShow] "+describe(arg(0))));
		l.register(null,"CFShowStr",(r,c)->{CFString s=string(arg(0));System.err.println("[ppc "+rt.app.name+" CFShowStr] "+(s==null?"(not a string)":"Length "+s.text.length()+(s.mutable?", mutable":"")+": \""+s.text+"\""));});
		// allocators
		l.register(null,"CFAllocatorGetTypeID",(r,c)->ret(TYPE_ALLOCATOR));
		l.register(null,"CFAllocatorGetDefault",(r,c)->ret(allocatorOf(0)));
		l.register(null,"CFAllocatorSetDefault",(r,c)->{makeAllocators();if(arg(0)!=0&&object(arg(0),CFAllocator.class)!=null){retain(arg(0));defaultAllocator=arg(0);}});
		l.register(null,"CFAllocatorCreate",(r,c)->{ // (allocator, context): the context's callbacks {version, info, retain, release, copyDescription, allocate, reallocate, deallocate, preferredSize}
			CFAllocator a=new CFAllocator("custom");a.context=r.allocate(36,4);memory.copy(arg(1),a.context,36);
			int retainFn=memory.readLong(a.context+8),info=memory.readLong(a.context+4);if(retainFn!=0&&info!=0)memory.writeLong(a.context+4,rt.cpu.call(retainFn,info));
			ret(register(a));});
		l.register(null,"CFAllocatorAllocate",(r,c)->ret(allocate(arg(0),arg(1),arg(2))));
		l.register(null,"CFAllocatorDeallocate",(r,c)->deallocate(arg(0),arg(1)));
		l.register(null,"CFAllocatorReallocate",(r,c)->{ // (allocator, ptr, newsize, hint)
			int p=arg(1),size=arg(2);
			if(p==0){ret(allocate(arg(0),size,arg(3)));return;}
			if(size<=0){deallocate(arg(0),p);ret(0);return;}
			CFAllocator a=object(allocatorOf(arg(0)),CFAllocator.class);
			if(a!=null&&a.context!=0){int fn=memory.readLong(a.context+28);ret(fn==0?0:rt.cpu.call(fn,p,size,arg(3),memory.readLong(a.context+4)));return;}
			int n=memory.isAllocated(p)?Math.min(size,rt.toolbox("GetPtrSize",p)):size;int q=r.allocate(size,4);memory.copy(p,q,n);if(memory.isAllocated(p))memory.free(p);ret(q);});
		l.register(null,"CFAllocatorGetPreferredSizeForSize",(r,c)->ret(arg(1)));
		l.register(null,"CFAllocatorGetContext",(r,c)->{CFAllocator a=object(allocatorOf(arg(0)),CFAllocator.class);int out=arg(1);if(out==0)return;
			if(a!=null&&a.context!=0)memory.copy(a.context,out,36);else memory.fill(out,36,0);});
		l.registerData("kCFAllocatorDefault",()->variable(0));
		l.registerData("kCFAllocatorSystemDefault",()->{makeAllocators();return variable(systemAllocator);});
		l.registerData("kCFAllocatorMalloc",()->{makeAllocators();return variable(mallocAllocator);});
		l.registerData("kCFAllocatorNull",()->{makeAllocators();return variable(nullAllocator);});
		l.registerData("kCFAllocatorUseContext",()->variable(ALLOCATOR_USE_CONTEXT));
	}
	/** A data item that is a variable holding {@code value} (kCFAllocatorNull, kCFBooleanTrue ... are CF references the program reads). */
	int variable(int value) throws M68kCpuException {int p=rt.allocate(4,4);memory.writeLong(p,value);return p;}
	/** A data item that is a constant CFString variable (kCFBundleIdentifierKey ...). */
	int stringVariable(String value) throws M68kCpuException {return variable(constantString(value.getBytes(StandardCharsets.ISO_8859_1)));}

	private void defineStrings(PpcLibraries l){
		l.register(null,"CFStringGetTypeID",(r,c)->ret(TYPE_STRING));
		l.register(null,"__CFStringMakeConstantString",(r,c)->{int p=arg(0);ret(p==0?0:constantString(r.cBytes(p)));});
		// creation
		l.register(null,"CFStringCreateWithPascalString",(r,c)->{int p=arg(1);ret(p==0?0:newString(decode(arg(2),r.pBytes(p))));});
		l.register(null,"CFStringCreateWithPascalStringNoCopy",(r,c)->{int p=arg(1);ret(p==0?0:newString(decode(arg(2),r.pBytes(p))));});
		l.register(null,"CFStringCreateWithCString",(r,c)->{int p=arg(1);ret(p==0?0:newString(decode(arg(2),r.cBytes(p))));});
		l.register(null,"CFStringCreateWithCStringNoCopy",(r,c)->{int p=arg(1);ret(p==0?0:newString(decode(arg(2),r.cBytes(p))));});
		l.register(null,"CFStringCreateWithCharacters",(r,c)->{int p=arg(1),n=arg(2);ret(newString(p==0?"":unichars(p,n)));});
		l.register(null,"CFStringCreateWithCharactersNoCopy",(r,c)->{int p=arg(1),n=arg(2);ret(newString(p==0?"":unichars(p,n)));});
		l.register(null,"CFStringCreateWithBytes",(r,c)->{ // (alloc, bytes, numBytes, encoding, isExternalRepresentation)
			int p=arg(1),n=arg(2),enc=arg(3);byte[] b=p==0||n<=0?new byte[0]:memory.readBytes(p,n);
			if((enc&0xffff)==0x100&&(arg(4)&0xff)!=0&&b.length>=2&&((b[0]&0xff)==0xff&&(b[1]&0xff)==0xfe)){ret(newString(new String(b,2,b.length-2,StandardCharsets.UTF_16LE)));return;} // a little-endian BOM
			if((enc&0xffff)==0x100&&b.length>=2&&(b[0]&0xff)==0xfe&&(b[1]&0xff)==0xff)b=java.util.Arrays.copyOfRange(b,2,b.length);
			ret(newString(decode(enc,b)));});
		l.register(null,"CFStringCreateWithSubstring",(r,c)->{CFString s=string(arg(1));if(s==null){ret(0);return;}int[] k=range(arg(2),arg(3),s.text.length());ret(newString(s.text.substring(k[0],k[1])));});
		l.register(null,"CFStringCreateCopy",(r,c)->{CFString s=string(arg(1));if(s==null){ret(0);return;}if(!s.mutable){ret(retain(arg(1)));return;}ret(newString(s.text.toString()));});
		l.register(null,"CFStringCreateMutable",(r,c)->ret(newMutableString("")));
		l.register(null,"CFStringCreateMutableCopy",(r,c)->{CFString s=string(arg(2));ret(newMutableString(s==null?"":s.text.toString()));});
		l.register(null,"CFStringCreateMutableWithExternalCharactersNoCopy",(r,c)->{ // (alloc, chars, numChars, capacity, externalCharactersAllocator)
			int p=arg(1),n=arg(2);CFString s=new CFString(p==0?"":unichars(p,n),true);s.external=p;s.externalCapacity=arg(3);ret(register(s));});
		l.register(null,"CFStringSetExternalCharactersNoCopy",(r,c)->{CFString s=string(arg(0));if(s==null)return;int p=arg(1),n=arg(2);s.text.setLength(0);if(p!=0)s.text.append(unichars(p,n));s.external=p;s.externalCapacity=arg(3);});
		l.register(null,"CFStringCreateWithFormat",(r,c)->ret(newString(format(arg(2),new PpcStdCLib.VarArgs(r,3)))));
		l.register(null,"CFStringCreateWithFormatAndArguments",(r,c)->ret(newString(format(arg(2),new PpcStdCLib.VarArgs(r,arg(3),true)))));
		l.register(null,"CFStringCreateExternalRepresentation",(r,c)->{CFString s=string(arg(1));if(s==null){ret(0);return;} // (alloc, string, encoding, lossByte)
			int enc=arg(2);String t=s.text.toString();if((arg(3)&0xff)==0&&!lossless(enc,t)){ret(0);return;}
			byte[] b=encode(enc,t);if((enc&0xffff)==0x100&&((enc>>>26)&0x3f)!=2){byte[] w=new byte[b.length+2];w[0]=(byte)0xfe;w[1]=(byte)0xff;System.arraycopy(b,0,w,2,b.length);b=w;}
			ret(newData(b,false));});
		l.register(null,"CFStringCreateFromExternalRepresentation",(r,c)->{CFData d=object(arg(1),CFData.class);if(d==null){ret(0);return;}byte[] b=d.bytes(this);int enc=arg(2);
			if((enc&0xffff)==0x100&&b.length>=2&&(b[0]&0xff)==0xff&&(b[1]&0xff)==0xfe){ret(newString(new String(b,2,b.length-2,StandardCharsets.UTF_16LE)));return;}
			if((enc&0xffff)==0x100&&b.length>=2&&(b[0]&0xff)==0xfe&&(b[1]&0xff)==0xff)b=java.util.Arrays.copyOfRange(b,2,b.length);
			ret(newString(decode(enc,b)));});
		// access
		l.register(null,"CFStringGetLength",(r,c)->{CFString s=string(arg(0));ret(s==null?0:s.text.length());});
		l.register(null,"CFStringGetCharacterAtIndex",(r,c)->{CFString s=string(arg(0));int i=arg(1);ret(s==null||i<0||i>=s.text.length()?0:s.text.charAt(i));});
		l.register(null,"CFStringGetCharacters",(r,c)->{CFString s=string(arg(0));if(s==null||arg(3)==0)return;int[] k=range(arg(1),arg(2),s.text.length());
			for(int i=k[0];i<k[1];i++)memory.writeWord(arg(3)+2*(i-k[0]),s.text.charAt(i));});
		l.register(null,"CFStringGetCharactersPtr",(r,c)->{CFString s=string(arg(0));if(s==null||s.mutable){ret(0);return;}
			if(s.characters==0){s.characters=buffer(s,2*s.text.length()+2);for(int i=0;i<s.text.length();i++)memory.writeWord(s.characters+2*i,s.text.charAt(i));}ret(s.characters);});
		l.register(null,"CFStringGetCString",(r,c)->{CFString s=string(arg(0));int buffer=arg(1),size=arg(2);if(s==null||buffer==0||size<=0){ret(0);return;}
			String t=s.text.toString();int enc=arg(3);byte[] b=encode(enc,t);boolean fits=b.length<size&&lossless(enc,t);int n=Math.min(b.length,size-1);
			memory.load(buffer,java.util.Arrays.copyOf(b,n));memory.writeByte(buffer+n,0);ret(fits?1:0);});
		l.register(null,"CFStringGetPascalString",(r,c)->{CFString s=string(arg(0));int buffer=arg(1),size=arg(2);if(s==null||buffer==0||size<=0){ret(0);return;}
			String t=s.text.toString();int enc=arg(3);byte[] b=encode(enc,t);int n=Math.min(b.length,Math.min(255,size-1));
			memory.writeByte(buffer,n);memory.load(buffer+1,java.util.Arrays.copyOf(b,n));ret(n==b.length&&lossless(enc,t)?1:0);});
		l.register(null,"CFStringGetCStringPtr",(r,c)->{CFString s=string(arg(0));int enc=arg(1);if(s==null||s.mutable||!lossless(enc,s.text.toString())){ret(0);return;}
			if(s.cStrings==null)s.cStrings=new HashMap<Integer,Integer>();Integer p=s.cStrings.get(enc);
			if(p==null){byte[] b=encode(enc,s.text.toString());p=buffer(s,b.length+1);memory.load(p,b);s.cStrings.put(enc,p);}ret(p);});
		l.register(null,"CFStringGetPascalStringPtr",(r,c)->{CFString s=string(arg(0));int enc=arg(1);if(s==null||s.mutable||!lossless(enc,s.text.toString())){ret(0);return;}
			byte[] b=encode(enc,s.text.toString());if(b.length>255){ret(0);return;}
			if(s.pStrings==null)s.pStrings=new HashMap<Integer,Integer>();Integer p=s.pStrings.get(enc);
			if(p==null){p=buffer(s,b.length+2);memory.writeByte(p,b.length);memory.load(p+1,b);s.pStrings.put(enc,p);}ret(p);});
		l.register(null,"CFStringGetBytes",(r,c)->{ // (string, range(2), encoding, lossByte, isExternalRepresentation, buffer, maxBufLen, *usedBufLen): characters converted
			CFString s=string(arg(0));if(s==null){ret(0);return;}int[] k=range(arg(1),arg(2),s.text.length());int enc=arg(3),loss=arg(4)&0xff;boolean ext=(arg(5)&0xff)!=0;int buf=arg(6),max=arg(7),used=arg(8);
			java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();boolean unicode16=(enc&0xffff)==0x100&&((enc>>>26)&0x3f)!=2;
			if(ext&&unicode16){out.write(0xfe);out.write(0xff);}
			int converted=0;
			for(int i=k[0];i<k[1];i++){
				String ch=s.text.substring(i,i+1);byte[] b=encode(enc,ch);
				if(!lossless(enc,ch)){if(loss==0)break;b=unicode16?new byte[]{0,(byte)loss}:new byte[]{(byte)loss};}
				if(buf!=0&&out.size()+b.length>max)break;
				out.write(b,0,b.length);converted++;
			}
			byte[] b=out.toByteArray();if(buf!=0)memory.load(buf,b);if(used!=0)memory.writeLong(used,b.length);ret(converted);});
		// encodings
		l.register(null,"CFStringGetSystemEncoding",(r,c)->ret(text().systemEncoding()));
		l.register(null,"CFStringGetFastestEncoding",(r,c)->ret(ENCODING_UNICODE));
		l.register(null,"CFStringGetSmallestEncoding",(r,c)->{CFString s=string(arg(0));String t=s==null?"":s.text.toString();int sys=text().systemEncoding();
			ret(lossless(ENCODING_ASCII,t)?ENCODING_ASCII:lossless(sys,t)?sys:ENCODING_UNICODE);});
		l.register(null,"CFStringGetMaximumSizeForEncoding",(r,c)->{int n=arg(0),e=arg(1)&0xffff;ret(e==0x100?n*((arg(1)>>>26&0x3f)==2?3:2):e==1||e==0x0a01||e==0x0920||e==0x0820?n*3:n);});
		l.register(null,"CFStringIsEncodingAvailable",(r,c)->ret(encodingNames().containsKey(arg(0))?1:0));
		l.register(null,"CFStringGetListOfAvailableEncodings",(r,c)->{
			if(availableEncodings==0){List<Integer> e=new ArrayList<Integer>(encodingNames().keySet());availableEncodings=r.allocate(4*e.size()+4,4);for(int i=0;i<e.size();i++)memory.writeLong(availableEncodings+4*i,e.get(i));memory.writeLong(availableEncodings+4*e.size(),ENCODING_INVALID);}
			ret(availableEncodings);});
		l.register(null,"CFStringGetNameOfEncoding",(r,c)->{String[] n=encodingNames().get(arg(0));ret(n==null?0:constantString(n[0].getBytes(StandardCharsets.ISO_8859_1)));});
		l.register(null,"CFStringConvertEncodingToIANACharSetName",(r,c)->{String[] n=encodingNames().get(arg(0));ret(n==null?0:constantString(n[1].getBytes(StandardCharsets.ISO_8859_1)));});
		l.register(null,"CFStringConvertIANACharSetNameToEncoding",(r,c)->{String want=text(arg(0));int found=ENCODING_INVALID;
			if(want!=null)for(Map.Entry<Integer,String[]> e:encodingNames().entrySet())if(e.getValue()[1].equalsIgnoreCase(want.trim())){found=e.getKey();break;}
			if(want!=null&&found==ENCODING_INVALID){String w=want.trim().toLowerCase();if(w.equals("utf8"))found=ENCODING_UTF8;else if(w.equals("sjis")||w.equals("x-sjis")||w.equals("ms_kanji"))found=0x0a01;else if(w.equals("latin1"))found=ENCODING_ISO_LATIN1;else if(w.equals("ascii")||w.equals("us-ascii"))found=ENCODING_ASCII;}
			ret(found);});
		l.register(null,"CFStringConvertEncodingToNSStringEncoding",(r,c)->{int e=arg(0);int[][] map={{0x600,1},{0x08000100,4},{0x201,5},{0x0a01,8},{0x100,10},{0x0500,12},{0,30},{0x0920,3},{0x0820,21}};int v=0;for(int[] m:map)if(m[0]==e)v=m[1];ret(v==0?(0x80000000|e):v);});
		l.register(null,"CFStringConvertNSStringEncodingToEncoding",(r,c)->{int e=arg(0);int[][] map={{0x600,1},{0x08000100,4},{0x201,5},{0x0a01,8},{0x100,10},{0x0500,12},{0,30},{0x0920,3},{0x0820,21}};int v=ENCODING_INVALID;for(int[] m:map)if(m[1]==e)v=m[0];if((e&0x80000000)!=0)v=e&0x7fffffff;ret(v);});
		l.register(null,"CFStringConvertEncodingToWindowsCodepage",(r,c)->{int e=arg(0)&0xffff;ret(e==0x0500?1252:e==0x0a01?932:e==0x0201?28591:e==0x0600?20127:e==0x100?1200:arg(0)==ENCODING_UTF8?65001:e==0x0920?51932:e==0x0820?50220:e==0?10000:e==1?10001:ENCODING_INVALID);});
		l.register(null,"CFStringConvertWindowsCodepageToEncoding",(r,c)->{int p=arg(0);ret(p==1252?0x0500:p==932?0x0a01:p==28591?0x0201:p==20127?0x0600:p==1200?0x100:p==65001?ENCODING_UTF8:p==51932?0x0920:p==50220?0x0820:p==10000?0:p==10001?1:ENCODING_INVALID);});
		// comparing and searching
		l.register(null,"CFStringCompare",(r,c)->{String a=text(arg(0)),b=text(arg(1));ret(compare(a==null?"":a,b==null?"":b,arg(2)));});
		l.register(null,"CFStringCompareWithOptions",(r,c)->{String a=text(arg(0)),b=text(arg(1));if(a==null)a="";if(b==null)b="";int[] k=range(arg(2),arg(3),a.length());ret(compare(a.substring(k[0],k[1]),b,arg(4)));});
		l.register(null,"CFStringFindWithOptions",(r,c)->{ // (string, find, range(2), options, *result)
			String s=text(arg(0)),w=text(arg(1));if(s==null||w==null){ret(0);return;}int[] k=range(arg(2),arg(3),s.length());
			int[] f=find(s,w,k[0],k[1],arg(4));if(f!=null)writeRange(arg(5),f[0],f[1]);ret(f!=null?1:0);});
		l.register(null,"CFStringFind",(r,c)->{ // CFRange result by value: (*result, string, find, options)
			String s=text(arg(1)),w=text(arg(2));int[] f=s==null||w==null?null:find(s,w,0,s.length(),arg(3));
			writeRange(arg(0),f==null?NOT_FOUND:f[0],f==null?0:f[1]);ret(arg(0));});
		l.register(null,"CFStringHasPrefix",(r,c)->{String s=text(arg(0)),p=text(arg(1));ret(s!=null&&p!=null&&!p.isEmpty()&&s.startsWith(p)?1:0);});
		l.register(null,"CFStringHasSuffix",(r,c)->{String s=text(arg(0)),p=text(arg(1));ret(s!=null&&p!=null&&!p.isEmpty()&&s.endsWith(p)?1:0);});
		l.register(null,"CFStringCreateArrayWithFindResults",(r,c)->{ // (alloc, string, find, range(2), options): an array of CFRange blocks, or NULL when none
			String s=text(arg(1)),w=text(arg(2));if(s==null||w==null){ret(0);return;}int[] k=range(arg(3),arg(4),s.length());int flags=arg(5);
			List<Integer> found=new ArrayList<Integer>();boolean back=(flags&COMPARE_BACKWARDS)!=0;int from=k[0],to=k[1];
			while(true){int[] f=find(s,w,from,to,flags);if(f==null)break;int p=r.allocate(8,4);writeRange(p,f[0],f[1]);found.add(p);if(back)to=f[0];else from=f[0]+f[1];if((flags&COMPARE_ANCHORED)!=0)break;}
			ret(found.isEmpty()?0:rangeArray(found));});
		l.register(null,"CFStringGetLineBounds",(r,c)->{ // (string, range(2), *lineBeginIndex, *lineEndIndex, *contentsEndIndex)
			String s=text(arg(0));if(s==null)return;int[] k=range(arg(1),arg(2),s.length());
			int begin=k[0];while(begin>0&&!isNewline(s.charAt(begin-1)))begin--;
			int end=Math.max(k[1],k[0]);if(end>k[0]&&end<=s.length()&&isNewline(s.charAt(end-1))){}else{while(end<s.length()&&!isNewline(s.charAt(end)))end++;}
			int contents=end;if(end<s.length()&&isNewline(s.charAt(end))){if(s.charAt(end)=='\r'&&end+1<s.length()&&s.charAt(end+1)=='\n')end+=2;else end++;}
			else if(end>0&&end==k[1]&&k[1]>k[0]&&isNewline(s.charAt(end-1))){contents=end-1;if(contents>0&&s.charAt(contents)=='\n'&&s.charAt(contents-1)=='\r')contents--;}
			if(arg(3)!=0)memory.writeLong(arg(3),begin);if(arg(4)!=0)memory.writeLong(arg(4),end);if(arg(5)!=0)memory.writeLong(arg(5),contents);});
		l.register(null,"CFStringGetIntValue",(r,c)->{String s=text(arg(0));ret(s==null?0:(int)leadingNumber(s,false));});
		l.register(null,"CFStringGetDoubleValue",(r,c)->{String s=text(arg(0));rt.retD(s==null?0:leadingNumber(s,true));});
		// mutation
		l.register(null,"CFStringAppend",(r,c)->{CFString s=string(arg(0));String a=text(arg(1));if(s!=null&&a!=null){s.text.append(a);changed(s);}});
		l.register(null,"CFStringAppendCharacters",(r,c)->{CFString s=string(arg(0));if(s==null||arg(1)==0)return;s.text.append(unichars(arg(1),arg(2)));changed(s);});
		l.register(null,"CFStringAppendPascalString",(r,c)->{CFString s=string(arg(0));if(s!=null&&arg(1)!=0){s.text.append(decode(arg(2),r.pBytes(arg(1))));changed(s);}});
		l.register(null,"CFStringAppendCString",(r,c)->{CFString s=string(arg(0));if(s!=null&&arg(1)!=0){s.text.append(decode(arg(2),r.cBytes(arg(1))));changed(s);}});
		l.register(null,"CFStringAppendFormat",(r,c)->{CFString s=string(arg(0));if(s!=null){s.text.append(format(arg(2),new PpcStdCLib.VarArgs(r,3)));changed(s);}});
		l.register(null,"CFStringAppendFormatAndArguments",(r,c)->{CFString s=string(arg(0));if(s!=null){s.text.append(format(arg(2),new PpcStdCLib.VarArgs(r,arg(3),true)));changed(s);}});
		l.register(null,"CFStringInsert",(r,c)->{CFString s=string(arg(0));String a=text(arg(2));if(s!=null&&a!=null){s.text.insert(Math.max(0,Math.min(arg(1),s.text.length())),a);changed(s);}});
		l.register(null,"CFStringDelete",(r,c)->{CFString s=string(arg(0));if(s==null)return;int[] k=range(arg(1),arg(2),s.text.length());s.text.delete(k[0],k[1]);changed(s);});
		l.register(null,"CFStringReplace",(r,c)->{CFString s=string(arg(0));String a=text(arg(3));if(s==null)return;int[] k=range(arg(1),arg(2),s.text.length());s.text.replace(k[0],k[1],a==null?"":a);changed(s);});
		l.register(null,"CFStringReplaceAll",(r,c)->{CFString s=string(arg(0));String a=text(arg(1));if(s==null)return;s.text.setLength(0);if(a!=null)s.text.append(a);changed(s);});
		l.register(null,"CFStringPad",(r,c)->{ // (string, padString, length, indexIntoPad)
			CFString s=string(arg(0));if(s==null)return;String pad=text(arg(1));int n=arg(2),at=arg(3);
			if(n<s.text.length())s.text.setLength(Math.max(0,n));else if(pad!=null&&!pad.isEmpty())for(int i=s.text.length(),k=at;i<n;i++,k++)s.text.append(pad.charAt(Math.floorMod(k,pad.length())));
			changed(s);});
		l.register(null,"CFStringTrim",(r,c)->{CFString s=string(arg(0));String t=text(arg(1));if(s==null||t==null||t.isEmpty())return;String v=s.text.toString();
			while(v.startsWith(t))v=v.substring(t.length());while(v.endsWith(t))v=v.substring(0,v.length()-t.length());s.text.setLength(0);s.text.append(v);changed(s);});
		l.register(null,"CFStringTrimWhitespace",(r,c)->{CFString s=string(arg(0));if(s==null)return;String v=s.text.toString();int a=0,b=v.length();
			while(a<b&&(Character.isWhitespace(v.charAt(a))||Character.isSpaceChar(v.charAt(a))))a++;while(b>a&&(Character.isWhitespace(v.charAt(b-1))||Character.isSpaceChar(v.charAt(b-1))))b--;
			s.text.setLength(0);s.text.append(v,a,b);changed(s);});
		l.register(null,"CFStringLowercase",(r,c)->{CFString s=string(arg(0));if(s==null)return;String v=s.text.toString().toLowerCase(java.util.Locale.ROOT);s.text.setLength(0);s.text.append(v);changed(s);});
		l.register(null,"CFStringUppercase",(r,c)->{CFString s=string(arg(0));if(s==null)return;String v=s.text.toString().toUpperCase(java.util.Locale.ROOT);s.text.setLength(0);s.text.append(v);changed(s);});
		l.register(null,"CFStringCapitalize",(r,c)->{CFString s=string(arg(0));if(s==null)return;String v=capitalize(s.text.toString());s.text.setLength(0);s.text.append(v);changed(s);});
	}
	private int availableEncodings;
	private Map<Integer,String[]> encodingNames;
	/** The encodings this layer converts: name (CFStringGetNameOfEncoding) and IANA charset name. */
	private Map<Integer,String[]> encodingNames(){
		if(encodingNames==null){
			encodingNames=new java.util.LinkedHashMap<Integer,String[]>();
			encodingNames.put(0,new String[]{"Western (Mac OS Roman)","macintosh"});encodingNames.put(1,new String[]{"Japanese (Mac OS)","x-mac-japanese"});
			encodingNames.put(0x0a01,new String[]{"Japanese (Shift JIS)","shift_jis"});encodingNames.put(0x0920,new String[]{"Japanese (EUC)","euc-jp"});
			encodingNames.put(0x0820,new String[]{"Japanese (ISO 2022-JP)","iso-2022-jp"});encodingNames.put(0x0201,new String[]{"Western (ISO Latin 1)","iso-8859-1"});
			encodingNames.put(0x0500,new String[]{"Western (Windows Latin 1)","windows-1252"});encodingNames.put(0x0600,new String[]{"Western (ASCII)","us-ascii"});
			encodingNames.put(0x0100,new String[]{"Unicode (UTF-16)","utf-16"});encodingNames.put(ENCODING_UTF8,new String[]{"Unicode (UTF-8)","utf-8"});
			encodingNames.put(0x0b01,new String[]{"Western (NextStep)","x-nextstep"});encodingNames.put(0x0bff,new String[]{"Non-lossy ASCII","x-nonlossyascii"});
		}
		return encodingNames;
	}
	/** CFStringGetIntValue/GetDoubleValue: the number at the start of the string (after white space), 0 when none. */
	static double leadingNumber(String s,boolean real){
		java.util.regex.Matcher m=java.util.regex.Pattern.compile(real?"^\\s*([+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)":"^\\s*([+-]?\\d+)").matcher(s);
		if(!m.find())return 0;
		try{if(real)return Double.parseDouble(m.group(1));long v=Long.parseLong(m.group(1));return v>Integer.MAX_VALUE?Integer.MAX_VALUE:v<Integer.MIN_VALUE?Integer.MIN_VALUE:v;}catch(NumberFormatException e){return real?0:(m.group(1).startsWith("-")?Integer.MIN_VALUE:Integer.MAX_VALUE);}
	}
	/** An immutable array of CFRange blocks (CFStringCreateArrayWithFindResults): the array owns and frees them. Filled in with the collections. */
	private int rangeArray(List<Integer> ranges) throws M68kException {return newPointerArray(ranges,true);}

	private void defineScalars(PpcLibraries l){
		// booleans
		l.register(null,"CFBooleanGetTypeID",(r,c)->ret(TYPE_BOOLEAN));
		l.register(null,"CFBooleanGetValue",(r,c)->{CFBoolean b=object(arg(0),CFBoolean.class);ret(b!=null&&b.value?1:0);});
		l.registerData("kCFBooleanTrue",()->variable(bool(true)));
		l.registerData("kCFBooleanFalse",()->variable(bool(false)));
		// numbers
		l.register(null,"CFNumberGetTypeID",(r,c)->ret(TYPE_NUMBER));
		l.register(null,"CFNumberCreate",(r,c)->{int t=arg(1),p=arg(2);ret(p==0||t<1||t>16?0:newNumber(t,p));});
		l.register(null,"CFNumberGetType",(r,c)->{CFNumber n=object(arg(0),CFNumber.class);ret(n==null?0:n.numberType);});
		l.register(null,"CFNumberGetByteSize",(r,c)->{CFNumber n=object(arg(0),CFNumber.class);ret(n==null?0:numberSize(n.numberType));});
		l.register(null,"CFNumberIsFloatType",(r,c)->{CFNumber n=object(arg(0),CFNumber.class);ret(n!=null&&n.isFloat()?1:0);});
		l.register(null,"CFNumberGetValue",(r,c)->{CFNumber n=object(arg(0),CFNumber.class);if(n==null||arg(2)==0){ret(0);return;}ret(numberValue(n,arg(1),arg(2))?1:0);});
		l.register(null,"CFNumberCompare",(r,c)->{CFNumber a=object(arg(0),CFNumber.class),b=object(arg(1),CFNumber.class);ret(a==null||b==null?0:Integer.signum(a.compareTo(b)));});
		l.registerData("kCFNumberNaN",()->{if(nanRef==0){CFNumber n=new CFNumber(13,0,Double.NaN);n.constant=true;nanRef=register(n);}return variable(nanRef);});
		l.registerData("kCFNumberPositiveInfinity",()->{if(posInfRef==0){CFNumber n=new CFNumber(13,0,Double.POSITIVE_INFINITY);n.constant=true;posInfRef=register(n);}return variable(posInfRef);});
		l.registerData("kCFNumberNegativeInfinity",()->{if(negInfRef==0){CFNumber n=new CFNumber(13,0,Double.NEGATIVE_INFINITY);n.constant=true;negInfRef=register(n);}return variable(negInfRef);});
		// data
		l.register(null,"CFDataGetTypeID",(r,c)->ret(TYPE_DATA));
		l.register(null,"CFDataCreate",(r,c)->{int p=arg(1),n=arg(2);ret(newData(p==0||n<=0?new byte[0]:memory.readBytes(p,n),false));});
		l.register(null,"CFDataCreateWithBytesNoCopy",(r,c)->{ // (alloc, bytes, length, bytesDeallocator): the program's bytes in place
			CFData d=new CFData(false);d.bytes=arg(1);d.length=Math.max(0,arg(2));d.capacity=d.length;d.noCopy=true;d.bytesDeallocator=allocatorOf(arg(3));ret(register(d));});
		l.register(null,"CFDataCreateCopy",(r,c)->{CFData d=object(arg(1),CFData.class);ret(d==null?0:newData(d.bytes(this),false));});
		l.register(null,"CFDataCreateMutable",(r,c)->ret(newData(new byte[0],true)));
		l.register(null,"CFDataCreateMutableCopy",(r,c)->{CFData d=object(arg(2),CFData.class);ret(newData(d==null?new byte[0]:d.bytes(this),true));});
		l.register(null,"CFDataGetLength",(r,c)->{CFData d=object(arg(0),CFData.class);ret(d==null?0:d.length);});
		l.register(null,"CFDataGetBytePtr",(r,c)->{CFData d=object(arg(0),CFData.class);ret(d==null?0:d.bytes);});
		l.register(null,"CFDataGetMutableBytePtr",(r,c)->{CFData d=object(arg(0),CFData.class);ret(d==null||!d.mutable?0:d.bytes);});
		l.register(null,"CFDataGetBytes",(r,c)->{CFData d=object(arg(0),CFData.class);if(d==null||arg(3)==0)return;int[] k=range(arg(1),arg(2),d.length);if(k[1]>k[0])memory.copy(d.bytes+k[0],arg(3),k[1]-k[0]);});
		l.register(null,"CFDataSetLength",(r,c)->{CFData d=object(arg(0),CFData.class);if(d!=null&&d.mutable)setDataLength(d,arg(1));});
		l.register(null,"CFDataIncreaseLength",(r,c)->{CFData d=object(arg(0),CFData.class);if(d!=null&&d.mutable)setDataLength(d,d.length+Math.max(0,arg(1)));});
		l.register(null,"CFDataAppendBytes",(r,c)->{CFData d=object(arg(0),CFData.class);if(d==null||!d.mutable||arg(2)<=0)return;int old=d.length;setDataLength(d,old+arg(2));memory.copy(arg(1),d.bytes+old,arg(2));});
		l.register(null,"CFDataReplaceBytes",(r,c)->{CFData d=object(arg(0),CFData.class);if(d==null||!d.mutable)return;replaceBytes(d,arg(1),arg(2),arg(3)==0||arg(4)<=0?new byte[0]:memory.readBytes(arg(3),arg(4)));});
		l.register(null,"CFDataDeleteBytes",(r,c)->{CFData d=object(arg(0),CFData.class);if(d==null||!d.mutable)return;replaceBytes(d,arg(1),arg(2),new byte[0]);});
	}

	// ------------------------------------------------------------------ collections

	/**
	 * A collection's callbacks, copied from the program's structure when the collection is made: retain, release,
	 * copyDescription, equal and hash as transition vectors (0 = none: values are not retained, compared by address).
	 * CF's own callbacks (kCFTypeArrayCallBacks ...) are native routines here, recognised and run in Java.
	 */
	static final class Callbacks {
		int retain,release,describe,equal,hash;
		static final Callbacks NONE=new Callbacks();
	}
	/** The native routines behind CF's predefined callback structures (0 until {@link #callbackVectors}). */
	private int retainTV,releaseTV,describeTV,equalTV,hashTV,copyStringTV;
	private void callbackVectors() throws M68kCpuException {
		if(retainTV!=0)return;
		retainTV=rt.nativeVector("__CFTypeCollectionRetain",(r,c)->ret(retain(arg(1))));
		releaseTV=rt.nativeVector("__CFTypeCollectionRelease",(r,c)->release(arg(1)));
		describeTV=rt.nativeVector("CFCopyDescription",(r,c)->ret(newString(describe(arg(0)))));
		equalTV=rt.nativeVector("CFEqual",(r,c)->ret(equal(arg(0),arg(1))?1:0));
		hashTV=rt.nativeVector("CFHash",(r,c)->ret(hash(arg(0))));
		copyStringTV=rt.nativeVector("__CFStringCollectionCopy",(r,c)->{CFString s=string(arg(1));ret(s==null?retain(arg(1)):s.mutable?newString(s.text.toString()):retain(arg(1)));});
	}
	/** A callbacks structure in guest memory: version 0, then the given routines ({@code withHash}: 6 words, else 5). */
	private int callbackStructure(boolean copyStrings,boolean withHash) throws M68kCpuException {
		callbackVectors();int p=rt.allocate(withHash?24:20,4);
		memory.writeLong(p+4,copyStrings?copyStringTV:retainTV);memory.writeLong(p+8,releaseTV);memory.writeLong(p+12,describeTV);memory.writeLong(p+16,equalTV);if(withHash)memory.writeLong(p+20,hashTV);
		return p;
	}
	private Callbacks callbacks(int p,boolean withHash) throws M68kCpuException {
		if(p==0)return Callbacks.NONE;
		Callbacks k=new Callbacks();k.retain=memory.readLong(p+4);k.release=memory.readLong(p+8);k.describe=memory.readLong(p+12);k.equal=memory.readLong(p+16);if(withHash)k.hash=memory.readLong(p+20);
		return k;
	}
	/** CF's own type callbacks (the ones collections made from Java use). */
	private Callbacks typeCallbacks() throws M68kCpuException {callbackVectors();Callbacks k=new Callbacks();k.retain=retainTV;k.release=releaseTV;k.describe=describeTV;k.equal=equalTV;k.hash=hashTV;return k;}
	int cbRetain(Callbacks k,int allocator,int v) throws M68kException {if(k.retain==0)return v;if(k.retain==retainTV)return retain(v);if(k.retain==copyStringTV){CFString s=string(v);return s!=null&&s.mutable?newString(s.text.toString()):retain(v);}return rt.cpu.call(k.retain,allocator,v);}
	void cbRelease(Callbacks k,int allocator,int v) throws M68kException {if(k.release==0)return;if(k.release==releaseTV){release(v);return;}rt.cpu.call(k.release,allocator,v);}
	boolean cbEqual(Callbacks k,int a,int b) throws M68kException {if(a==b)return true;if(k.equal==0)return false;if(k.equal==equalTV)return equal(a,b);return (rt.cpu.call(k.equal,a,b)&0xff)!=0;}
	int cbHash(Callbacks k,int v) throws M68kException {if(k.hash==0)return v;if(k.hash==hashTV)return hash(v);return rt.cpu.call(k.hash,v);}
	String cbDescribe(Callbacks k,int v) throws M68kException {
		if(k.describe==0)return String.format("<%08X>",v);if(k.describe==describeTV)return describe(v);
		int s=rt.cpu.call(k.describe,v);String t=text(s);if(s!=0)release(s);return t==null?String.format("<%08X>",v):t;
	}

	/** A CFArray (mutable or not). */
	final class CFArray extends CFObject implements ValueEquality,Describable {
		final List<Integer> values=new ArrayList<Integer>();final Callbacks callbacks;final boolean mutable;boolean ownsBlocks;
		CFArray(Callbacks callbacks,boolean mutable){super(TYPE_ARRAY);this.callbacks=callbacks;this.mutable=mutable;}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {for(int v:values){cbRelease(callbacks,allocator,v);if(ownsBlocks&&memory.isAllocated(v))memory.free(v);}values.clear();}
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject other) throws M68kException {CFArray o=(CFArray)other;if(o.values.size()!=values.size())return false;for(int i=0;i<values.size();i++)if(!cbEqual(callbacks,values.get(i),o.values.get(i)))return false;return true;}
		@Override public int valueHash(PpcCoreFoundation cf){return values.size();}
		@Override public String description(PpcCoreFoundation cf) throws M68kException {StringBuilder sb=new StringBuilder("(");for(int i=0;i<values.size();i++){if(i>0)sb.append(", ");sb.append(cbDescribe(callbacks,values.get(i)));}return sb.append(")").toString();}
		void add(int index,int v) throws M68kException {values.add(index,cbRetain(callbacks,allocator,v));}
		void remove(int index) throws M68kException {int v=values.remove(index);cbRelease(callbacks,allocator,v);}
	}
	int newArray(Callbacks k,boolean mutable) throws M68kCpuException {return register(new CFArray(k,mutable));}
	/** A new immutable array of CF objects (retained), as CF-typed collections hold them. */
	int newObjectArray(List<Integer> refs) throws M68kException {CFArray a=new CFArray(typeCallbacks(),false);int p=register(a);for(int v:refs)a.add(a.values.size(),v);return p;}
	/** An immutable CFArray of plain pointers (no callbacks); {@code owned}: the array frees the blocks with itself. */
	int newPointerArray(List<Integer> values,boolean owned) throws M68kException {CFArray a=new CFArray(Callbacks.NONE,false);a.values.addAll(values);a.ownsBlocks=owned;return register(a);}
	CFArray array(int ref){return object(ref,CFArray.class);}
	/** Indices [a, b) of a CFRange argument over a collection of {@code size}. */
	private int[] span(int location,int length,int size){return range(location,length,size);}

	/** Sorts values[from, to) with {@code comparator} (a CFComparatorFunction TVector, 0 = by CFString order / address); stable (merge sort). */
	private void sort(List<Integer> values,int from,int to,int comparator,int context) throws M68kException {
		int n=to-from;if(n<2)return;
		int[] a=new int[n];for(int i=0;i<n;i++)a[i]=values.get(from+i);
		int[] tmp=new int[n];
		for(int width=1;width<n;width*=2)for(int lo=0;lo<n-width;lo+=2*width){
			int mid=lo+width,hi=Math.min(lo+2*width,n),i=lo,j=mid,k=lo;
			while(i<mid&&j<hi)tmp[k++]=compareValues(a[j],a[i],comparator,context)<0?a[j++]:a[i++];
			while(i<mid)tmp[k++]=a[i++];while(j<hi)tmp[k++]=a[j++];
			System.arraycopy(tmp,lo,a,lo,hi-lo);
		}
		for(int i=0;i<n;i++)values.set(from+i,a[i]);
	}
	private int compareValues(int x,int y,int comparator,int context) throws M68kException {
		if(comparator==0){String a=text(x),b=text(y);return a!=null&&b!=null?compare(a,b,0):Integer.compareUnsigned(x,y);}
		return rt.cpu.call(comparator,x,y,context);
	}

	/** A key-value table: entries in insertion order, found by hash then the key callbacks' equality. */
	final class CFDictionary extends CFObject implements ValueEquality,Describable {
		final List<int[]> entries=new ArrayList<int[]>(); // {key, value, hash}
		final Callbacks keys,values;final boolean mutable;final int kind; // TYPE_DICTIONARY, TYPE_SET (no values), TYPE_BAG (value = count)
		CFDictionary(int kind,Callbacks keys,Callbacks values,boolean mutable){super(kind);this.kind=kind;this.keys=keys;this.values=values;this.mutable=mutable;}
		int[] find(int key) throws M68kException {int h=cbHash(keys,key);for(int[] e:entries)if(e[2]==h&&cbEqual(keys,e[0],key))return e;return null;}
		/** Adds, replaces or sets {@code key}: mode 0 add if absent, 1 replace if present, 2 set. */
		void put(int key,int value,int mode) throws M68kException {
			int[] e=find(key);
			if(e!=null){
				if(mode==0){if(kind==TYPE_BAG)e[1]++;return;}
				if(kind==TYPE_DICTIONARY){int old=e[1];e[1]=cbRetain(values,allocator,value);cbRelease(values,allocator,old);}
				else if(kind==TYPE_SET&&mode>=1){int old=e[0];e[0]=cbRetain(keys,allocator,key);cbRelease(keys,allocator,old);}
				return;
			}
			if(mode==1)return;
			entries.add(new int[]{cbRetain(keys,allocator,key),kind==TYPE_DICTIONARY?cbRetain(values,allocator,value):1,cbHash(keys,key)});
		}
		void remove(int key) throws M68kException {int[] e=find(key);if(e==null)return;if(kind==TYPE_BAG&&--e[1]>0)return;entries.remove(e);cbRelease(keys,allocator,e[0]);if(kind==TYPE_DICTIONARY)cbRelease(values,allocator,e[1]);}
		void clear() throws M68kException {List<int[]> old=new ArrayList<int[]>(entries);entries.clear();for(int[] e:old){cbRelease(keys,allocator,e[0]);if(kind==TYPE_DICTIONARY)cbRelease(values,allocator,e[1]);}}
		int count(){if(kind!=TYPE_BAG)return entries.size();int n=0;for(int[] e:entries)n+=e[1];return n;}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {clear();}
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject other) throws M68kException {
			CFDictionary o=(CFDictionary)other;if(o.entries.size()!=entries.size())return false;
			for(int[] e:entries){int[] f=o.find(e[0]);if(f==null)return false;if(kind==TYPE_DICTIONARY&&!cbEqual(values,e[1],f[1]))return false;if(kind==TYPE_BAG&&e[1]!=f[1])return false;}
			return true;}
		@Override public int valueHash(PpcCoreFoundation cf){return entries.size();}
		@Override public String description(PpcCoreFoundation cf) throws M68kException {
			StringBuilder sb=new StringBuilder(kind==TYPE_DICTIONARY?"{":"(");boolean first=true;
			for(int[] e:entries){if(!first)sb.append(kind==TYPE_DICTIONARY?"; ":", ");first=false;sb.append(cbDescribe(keys,e[0]));if(kind==TYPE_DICTIONARY)sb.append(" = ").append(cbDescribe(values,e[1]));}
			return sb.append(kind==TYPE_DICTIONARY?"}":")").toString();}
	}
	CFDictionary dictionary(int ref){CFDictionary d=object(ref,CFDictionary.class);return d!=null&&d.kind==TYPE_DICTIONARY?d:null;}
	private CFDictionary set(int ref,int kind){CFDictionary d=object(ref,CFDictionary.class);return d!=null&&d.kind==kind?d:null;}
	/** A new mutable dictionary with CF-typed keys and values (property lists, bundles' info). */
	int newTypeDictionary() throws M68kCpuException {return register(new CFDictionary(TYPE_DICTIONARY,typeCallbacks(),typeCallbacks(),true));}
	/** dictionary[key] (a CFString key) or 0. */
	int lookup(int dict,String key) throws M68kException {CFDictionary d=dictionary(dict);if(d==null)return 0;int k=newString(key);try{int[] e=d.find(k);return e==null?0:e[1];}finally{release(k);}}
	/** Sets dictionary[key] (a CFString key) to {@code value} (retained by the dictionary). */
	void put(int dict,String key,int value) throws M68kException {CFDictionary d=dictionary(dict);if(d==null)return;int k=newString(key);try{d.put(k,value,2);}finally{release(k);}}
	/** Calls a CFxxxApplierFunction for every value (arrays, sets, bags) or key/value pair (dictionaries). */
	private void apply(int applier,int context,int[]... items) throws M68kException {for(int[] item:items){if(item.length==1)rt.cpu.call(applier,item[0],context);else rt.cpu.call(applier,item[0],item[1],context);}}

	private void defineCollections(PpcLibraries l){
		l.registerData("kCFTypeArrayCallBacks",()->callbackStructure(false,false));
		l.registerData("kCFTypeDictionaryKeyCallBacks",()->callbackStructure(false,true));
		l.registerData("kCFCopyStringDictionaryKeyCallBacks",()->callbackStructure(true,true));
		l.registerData("kCFTypeDictionaryValueCallBacks",()->callbackStructure(false,false));
		l.registerData("kCFTypeSetCallBacks",()->callbackStructure(false,true));
		l.registerData("kCFCopyStringSetCallBacks",()->callbackStructure(true,true));
		l.registerData("kCFTypeBagCallBacks",()->callbackStructure(false,true));
		l.registerData("kCFCopyStringBagCallBacks",()->callbackStructure(true,true));
		// ---- arrays
		l.register(null,"CFArrayGetTypeID",(r,c)->ret(TYPE_ARRAY));
		l.register(null,"CFArrayCreate",(r,c)->{ // (alloc, values, numValues, callBacks)
			CFArray a=new CFArray(callbacks(arg(3),false),false);a.allocator=arg(0);int p=register(a);
			for(int i=0;i<arg(2);i++)a.add(i,memory.readLong(arg(1)+4*i));ret(p);});
		l.register(null,"CFArrayCreateCopy",(r,c)->{CFArray s=array(arg(1));if(s==null){ret(0);return;}CFArray a=new CFArray(s.callbacks,false);int p=register(a);for(int v:s.values)a.add(a.values.size(),v);ret(p);});
		l.register(null,"CFArrayCreateMutable",(r,c)->{CFArray a=new CFArray(callbacks(arg(2),false),true);a.allocator=arg(0);ret(register(a));});
		l.register(null,"CFArrayCreateMutableCopy",(r,c)->{CFArray s=array(arg(2));CFArray a=new CFArray(s==null?Callbacks.NONE:s.callbacks,true);int p=register(a);if(s!=null)for(int v:s.values)a.add(a.values.size(),v);ret(p);});
		l.register(null,"CFArrayGetCount",(r,c)->{CFArray a=array(arg(0));ret(a==null?0:a.values.size());});
		l.register(null,"CFArrayGetCountOfValue",(r,c)->{CFArray a=array(arg(0));if(a==null){ret(0);return;}int[] k=span(arg(1),arg(2),a.values.size());int n=0;for(int i=k[0];i<k[1];i++)if(cbEqual(a.callbacks,a.values.get(i),arg(3)))n++;ret(n);});
		l.register(null,"CFArrayContainsValue",(r,c)->{CFArray a=array(arg(0));if(a==null){ret(0);return;}int[] k=span(arg(1),arg(2),a.values.size());for(int i=k[0];i<k[1];i++)if(cbEqual(a.callbacks,a.values.get(i),arg(3))){ret(1);return;}ret(0);});
		l.register(null,"CFArrayGetValueAtIndex",(r,c)->{CFArray a=array(arg(0));int i=arg(1);ret(a==null||i<0||i>=a.values.size()?0:a.values.get(i));});
		l.register(null,"CFArrayGetValues",(r,c)->{CFArray a=array(arg(0));if(a==null||arg(3)==0)return;int[] k=span(arg(1),arg(2),a.values.size());for(int i=k[0];i<k[1];i++)memory.writeLong(arg(3)+4*(i-k[0]),a.values.get(i));});
		l.register(null,"CFArrayApplyFunction",(r,c)->{CFArray a=array(arg(0));if(a==null||arg(3)==0)return;int[] k=span(arg(1),arg(2),a.values.size());List<Integer> copy=new ArrayList<Integer>(a.values.subList(k[0],k[1]));for(int v:copy)rt.cpu.call(arg(3),v,arg(4));});
		l.register(null,"CFArrayGetFirstIndexOfValue",(r,c)->{CFArray a=array(arg(0));if(a==null){ret(NOT_FOUND);return;}int[] k=span(arg(1),arg(2),a.values.size());for(int i=k[0];i<k[1];i++)if(cbEqual(a.callbacks,a.values.get(i),arg(3))){ret(i);return;}ret(NOT_FOUND);});
		l.register(null,"CFArrayGetLastIndexOfValue",(r,c)->{CFArray a=array(arg(0));if(a==null){ret(NOT_FOUND);return;}int[] k=span(arg(1),arg(2),a.values.size());for(int i=k[1]-1;i>=k[0];i--)if(cbEqual(a.callbacks,a.values.get(i),arg(3))){ret(i);return;}ret(NOT_FOUND);});
		l.register(null,"CFArrayBSearchValues",(r,c)->{ // (array, range(2), value, comparator, context): the index where value is or would go
			CFArray a=array(arg(0));if(a==null){ret(0);return;}int[] k=span(arg(1),arg(2),a.values.size());int lo=k[0],hi=k[1];
			while(lo<hi){int mid=(lo+hi)>>>1;int cmp=compareValues(a.values.get(mid),arg(3),arg(4),arg(5));if(cmp<0)lo=mid+1;else hi=mid;}ret(lo);});
		l.register(null,"CFArrayAppendValue",(r,c)->{CFArray a=array(arg(0));if(a!=null&&a.mutable)a.add(a.values.size(),arg(1));});
		l.register(null,"CFArrayInsertValueAtIndex",(r,c)->{CFArray a=array(arg(0));if(a!=null&&a.mutable)a.add(Math.max(0,Math.min(arg(1),a.values.size())),arg(2));});
		l.register(null,"CFArraySetValueAtIndex",(r,c)->{CFArray a=array(arg(0));int i=arg(1);if(a==null||!a.mutable||i<0||i>a.values.size())return;if(i==a.values.size()){a.add(i,arg(2));return;}int v=cbRetain(a.callbacks,a.allocator,arg(2));int old=a.values.set(i,v);cbRelease(a.callbacks,a.allocator,old);});
		l.register(null,"CFArrayRemoveValueAtIndex",(r,c)->{CFArray a=array(arg(0));int i=arg(1);if(a!=null&&a.mutable&&i>=0&&i<a.values.size())a.remove(i);});
		l.register(null,"CFArrayRemoveAllValues",(r,c)->{CFArray a=array(arg(0));if(a!=null&&a.mutable)while(!a.values.isEmpty())a.remove(a.values.size()-1);});
		l.register(null,"CFArrayReplaceValues",(r,c)->{ // (array, range(2), newValues, newCount)
			CFArray a=array(arg(0));if(a==null||!a.mutable)return;int[] k=span(arg(1),arg(2),a.values.size());
			List<Integer> added=new ArrayList<Integer>();for(int i=0;i<arg(4);i++)added.add(cbRetain(a.callbacks,a.allocator,memory.readLong(arg(3)+4*i)));
			List<Integer> removed=new ArrayList<Integer>(a.values.subList(k[0],k[1]));a.values.subList(k[0],k[1]).clear();a.values.addAll(k[0],added);
			for(int v:removed)cbRelease(a.callbacks,a.allocator,v);});
		l.register(null,"CFArrayExchangeValuesAtIndices",(r,c)->{CFArray a=array(arg(0));int i=arg(1),j=arg(2);if(a==null||i<0||j<0||i>=a.values.size()||j>=a.values.size())return;int v=a.values.get(i);a.values.set(i,a.values.get(j));a.values.set(j,v);});
		l.register(null,"CFArraySortValues",(r,c)->{CFArray a=array(arg(0));if(a==null)return;int[] k=span(arg(1),arg(2),a.values.size());sort(a.values,k[0],k[1],arg(3),arg(4));});
		l.register(null,"CFArrayAppendArray",(r,c)->{CFArray a=array(arg(0)),b=array(arg(1));if(a==null||b==null||!a.mutable)return;int[] k=span(arg(2),arg(3),b.values.size());for(int v:new ArrayList<Integer>(b.values.subList(k[0],k[1])))a.add(a.values.size(),v);});
		// ---- dictionaries
		l.register(null,"CFDictionaryGetTypeID",(r,c)->ret(TYPE_DICTIONARY));
		l.register(null,"CFDictionaryCreate",(r,c)->{ // (alloc, keys, values, numValues, keyCallBacks, valueCallBacks)
			CFDictionary d=new CFDictionary(TYPE_DICTIONARY,callbacks(arg(4),true),callbacks(arg(5),false),false);d.allocator=arg(0);int p=register(d);
			for(int i=0;i<arg(3);i++)d.put(memory.readLong(arg(1)+4*i),memory.readLong(arg(2)+4*i),2);ret(p);});
		l.register(null,"CFDictionaryCreateCopy",(r,c)->{CFDictionary s=dictionary(arg(1));if(s==null){ret(0);return;}CFDictionary d=new CFDictionary(TYPE_DICTIONARY,s.keys,s.values,false);int p=register(d);for(int[] e:s.entries)d.put(e[0],e[1],2);ret(p);});
		l.register(null,"CFDictionaryCreateMutable",(r,c)->{CFDictionary d=new CFDictionary(TYPE_DICTIONARY,callbacks(arg(2),true),callbacks(arg(3),false),true);d.allocator=arg(0);ret(register(d));});
		l.register(null,"CFDictionaryCreateMutableCopy",(r,c)->{CFDictionary s=dictionary(arg(2));CFDictionary d=new CFDictionary(TYPE_DICTIONARY,s==null?Callbacks.NONE:s.keys,s==null?Callbacks.NONE:s.values,true);int p=register(d);if(s!=null)for(int[] e:s.entries)d.put(e[0],e[1],2);ret(p);});
		l.register(null,"CFDictionaryGetCount",(r,c)->{CFDictionary d=dictionary(arg(0));ret(d==null?0:d.entries.size());});
		l.register(null,"CFDictionaryGetCountOfKey",(r,c)->{CFDictionary d=dictionary(arg(0));ret(d!=null&&d.find(arg(1))!=null?1:0);});
		l.register(null,"CFDictionaryGetCountOfValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d==null){ret(0);return;}int n=0;for(int[] e:d.entries)if(cbEqual(d.values,e[1],arg(1)))n++;ret(n);});
		l.register(null,"CFDictionaryContainsKey",(r,c)->{CFDictionary d=dictionary(arg(0));ret(d!=null&&d.find(arg(1))!=null?1:0);});
		l.register(null,"CFDictionaryContainsValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null)for(int[] e:d.entries)if(cbEqual(d.values,e[1],arg(1))){ret(1);return;}ret(0);});
		l.register(null,"CFDictionaryGetValue",(r,c)->{CFDictionary d=dictionary(arg(0));int[] e=d==null?null:d.find(arg(1));ret(e==null?0:e[1]);});
		l.register(null,"CFDictionaryGetValueIfPresent",(r,c)->{CFDictionary d=dictionary(arg(0));int[] e=d==null?null:d.find(arg(1));if(e!=null&&arg(2)!=0)memory.writeLong(arg(2),e[1]);ret(e!=null?1:0);});
		l.register(null,"CFDictionaryGetKeysAndValues",(r,c)->{CFDictionary d=dictionary(arg(0));if(d==null)return;for(int i=0;i<d.entries.size();i++){if(arg(1)!=0)memory.writeLong(arg(1)+4*i,d.entries.get(i)[0]);if(arg(2)!=0)memory.writeLong(arg(2)+4*i,d.entries.get(i)[1]);}});
		l.register(null,"CFDictionaryApplyFunction",(r,c)->{CFDictionary d=dictionary(arg(0));if(d==null||arg(1)==0)return;apply(arg(1),arg(2),new ArrayList<int[]>(d.entries).stream().map(e->new int[]{e[0],e[1]}).toArray(int[][]::new));});
		l.register(null,"CFDictionaryAddValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null&&d.mutable)d.put(arg(1),arg(2),0);});
		l.register(null,"CFDictionarySetValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null&&d.mutable)d.put(arg(1),arg(2),2);});
		l.register(null,"CFDictionaryReplaceValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null&&d.mutable)d.put(arg(1),arg(2),1);});
		l.register(null,"CFDictionaryRemoveValue",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null&&d.mutable)d.remove(arg(1));});
		l.register(null,"CFDictionaryRemoveAllValues",(r,c)->{CFDictionary d=dictionary(arg(0));if(d!=null&&d.mutable)d.clear();});
		// ---- sets and bags (same table, values unused / counted)
		for(final int kind:new int[]{TYPE_SET,TYPE_BAG}){
			final String n=kind==TYPE_SET?"CFSet":"CFBag";
			l.register(null,n+"GetTypeID",(r,c)->ret(kind));
			l.register(null,n+"Create",(r,c)->{CFDictionary d=new CFDictionary(kind,callbacks(arg(3),true),Callbacks.NONE,false);d.allocator=arg(0);int p=register(d);for(int i=0;i<arg(2);i++)d.put(memory.readLong(arg(1)+4*i),0,0);ret(p);});
			l.register(null,n+"CreateCopy",(r,c)->{CFDictionary s=set(arg(1),kind);if(s==null){ret(0);return;}CFDictionary d=new CFDictionary(kind,s.keys,Callbacks.NONE,false);int p=register(d);for(int[] e:s.entries)for(int i=0;i<(kind==TYPE_BAG?e[1]:1);i++)d.put(e[0],0,0);ret(p);});
			l.register(null,n+"CreateMutable",(r,c)->{CFDictionary d=new CFDictionary(kind,callbacks(arg(2),true),Callbacks.NONE,true);d.allocator=arg(0);ret(register(d));});
			l.register(null,n+"CreateMutableCopy",(r,c)->{CFDictionary s=set(arg(2),kind);CFDictionary d=new CFDictionary(kind,s==null?Callbacks.NONE:s.keys,Callbacks.NONE,true);int p=register(d);if(s!=null)for(int[] e:s.entries)for(int i=0;i<(kind==TYPE_BAG?e[1]:1);i++)d.put(e[0],0,0);ret(p);});
			l.register(null,n+"GetCount",(r,c)->{CFDictionary d=set(arg(0),kind);ret(d==null?0:d.count());});
			l.register(null,n+"GetCountOfValue",(r,c)->{CFDictionary d=set(arg(0),kind);int[] e=d==null?null:d.find(arg(1));ret(e==null?0:kind==TYPE_BAG?e[1]:1);});
			l.register(null,n+"ContainsValue",(r,c)->{CFDictionary d=set(arg(0),kind);ret(d!=null&&d.find(arg(1))!=null?1:0);});
			l.register(null,n+"GetValue",(r,c)->{CFDictionary d=set(arg(0),kind);int[] e=d==null?null:d.find(arg(1));ret(e==null?0:e[0]);});
			l.register(null,n+"GetValueIfPresent",(r,c)->{CFDictionary d=set(arg(0),kind);int[] e=d==null?null:d.find(arg(1));if(e!=null&&arg(2)!=0)memory.writeLong(arg(2),e[0]);ret(e!=null?1:0);});
			l.register(null,n+"GetValues",(r,c)->{CFDictionary d=set(arg(0),kind);if(d==null||arg(1)==0)return;int i=0;for(int[] e:d.entries)for(int k=0;k<(kind==TYPE_BAG?e[1]:1);k++)memory.writeLong(arg(1)+4*i++,e[0]);});
			l.register(null,n+"ApplyFunction",(r,c)->{CFDictionary d=set(arg(0),kind);if(d==null||arg(1)==0)return;List<int[]> items=new ArrayList<int[]>();for(int[] e:d.entries)for(int k=0;k<(kind==TYPE_BAG?e[1]:1);k++)items.add(new int[]{e[0]});apply(arg(1),arg(2),items.toArray(new int[0][]));});
			l.register(null,n+"AddValue",(r,c)->{CFDictionary d=set(arg(0),kind);if(d!=null&&d.mutable)d.put(arg(1),0,0);});
			l.register(null,n+"ReplaceValue",(r,c)->{CFDictionary d=set(arg(0),kind);if(d!=null&&d.mutable)d.put(arg(1),0,1);});
			l.register(null,n+"SetValue",(r,c)->{CFDictionary d=set(arg(0),kind);if(d!=null&&d.mutable)d.put(arg(1),0,2);});
			l.register(null,n+"RemoveValue",(r,c)->{CFDictionary d=set(arg(0),kind);if(d!=null&&d.mutable)d.remove(arg(1));});
			l.register(null,n+"RemoveAllValues",(r,c)->{CFDictionary d=set(arg(0),kind);if(d!=null&&d.mutable)d.clear();});
		}
	}
	// ------------------------------------------------------------------ dates and time zones

	/** Seconds from 1970 to CF's reference date, 1 Jan 2001 00:00:00 GMT. */
	static final double SINCE_1970=978307200.0,SINCE_1904=3061152000.0;
	static double now(){return System.currentTimeMillis()/1000.0-SINCE_1970;}
	static final class CFDate extends CFObject implements ValueEquality {
		final double at;CFDate(double at){super(TYPE_DATE);this.at=at;}
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject o){return ((CFDate)o).at==at;}
		@Override public int valueHash(PpcCoreFoundation cf){return Double.hashCode(at);}
	}
	static final class CFTimeZone extends CFObject implements ValueEquality,Describable {
		final java.util.TimeZone zone;CFTimeZone(java.util.TimeZone zone){super(TYPE_TIME_ZONE);this.zone=zone;}
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject o){return ((CFTimeZone)o).zone.getID().equals(zone.getID());}
		@Override public int valueHash(PpcCoreFoundation cf){return zone.getID().hashCode();}
		@Override public String description(PpcCoreFoundation cf){return zone.getID();}
	}
	int newDate(double at) throws M68kCpuException {return register(new CFDate(at));}
	private java.util.TimeZone defaultZone;
	private java.util.TimeZone zone(int ref){CFTimeZone z=object(ref,CFTimeZone.class);return z!=null?z.zone:java.util.TimeZone.getTimeZone("GMT");}
	/** A calendar at absolute time {@code at} in {@code zone}. */
	private static java.util.GregorianCalendar calendar(double at,java.util.TimeZone zone){
		java.util.GregorianCalendar g=new java.util.GregorianCalendar(zone);g.setGregorianChange(new java.util.Date(Long.MIN_VALUE));
		g.setTimeInMillis((long)Math.floor((at+SINCE_1970)*1000));return g;
	}
	/** A CFGregorianDate's fields from four argument slots {year, month|day|hour|minute, second (2 words)}. */
	private double[] gregorian(int slot) throws M68kCpuException {
		int year=arg(slot),packed=arg(slot+1);long bits=((long)arg(slot+2)<<32)|(arg(slot+3)&0xffffffffL);
		return new double[]{year,(byte)(packed>>>24),(byte)(packed>>>16),(byte)(packed>>>8),(byte)packed,Double.longBitsToDouble(bits)};
	}
	private double absoluteTime(double[] d,java.util.TimeZone zone){
		java.util.GregorianCalendar g=new java.util.GregorianCalendar(zone);g.setGregorianChange(new java.util.Date(Long.MIN_VALUE));g.clear();
		g.set((int)d[0],(int)d[1]-1,(int)d[2],(int)d[3],(int)d[4],0);
		return g.getTimeInMillis()/1000.0-SINCE_1970+d[5];
	}
	private void writeGregorian(int p,double at,java.util.TimeZone zone) throws M68kCpuException {
		java.util.GregorianCalendar g=calendar(at,zone);
		double seconds=g.get(java.util.Calendar.SECOND)+(at-Math.floor(at));
		memory.writeLong(p,g.get(java.util.Calendar.YEAR)*(g.get(java.util.Calendar.ERA)==java.util.GregorianCalendar.BC?-1:1));
		memory.writeByte(p+4,g.get(java.util.Calendar.MONTH)+1);memory.writeByte(p+5,g.get(java.util.Calendar.DAY_OF_MONTH));memory.writeByte(p+6,g.get(java.util.Calendar.HOUR_OF_DAY));memory.writeByte(p+7,g.get(java.util.Calendar.MINUTE));
		long b=Double.doubleToRawLongBits(seconds);memory.writeLong(p+8,(int)(b>>>32));memory.writeLong(p+12,(int)b);
	}
	private void writeDouble(int p,double v) throws M68kCpuException {long b=Double.doubleToRawLongBits(v);memory.writeLong(p,(int)(b>>>32));memory.writeLong(p+4,(int)b);}
	private int doubleData(double v) throws M68kCpuException {int p=rt.allocate(8,8);writeDouble(p,v);return p;}

	private void defineTime(PpcLibraries l){
		l.registerData("kCFAbsoluteTimeIntervalSince1970",()->doubleData(SINCE_1970));
		l.registerData("kCFAbsoluteTimeIntervalSince1904",()->doubleData(SINCE_1904));
		l.register(null,"CFAbsoluteTimeGetCurrent",(r,c)->r.retD(now()));
		l.register(null,"CFDateGetTypeID",(r,c)->ret(TYPE_DATE));
		l.register(null,"CFDateCreate",(r,c)->ret(newDate(r.farg(0))));             // (alloc, at: slots 1-2 and f1)
		l.register(null,"CFDateGetAbsoluteTime",(r,c)->{CFDate d=object(arg(0),CFDate.class);r.retD(d==null?0:d.at);});
		l.register(null,"CFDateGetTimeIntervalSinceDate",(r,c)->{CFDate a=object(arg(0),CFDate.class),b=object(arg(1),CFDate.class);r.retD(a==null||b==null?0:a.at-b.at);});
		l.register(null,"CFDateCompare",(r,c)->{CFDate a=object(arg(0),CFDate.class),b=object(arg(1),CFDate.class);ret(a==null||b==null?0:Double.compare(a.at,b.at));});
		l.register(null,"CFGregorianDateIsValid",(r,c)->{ // (gdate: 4 slots, unitFlags): years 1, months 2, days 4, hours 8, minutes 16, seconds 32
			double[] d=gregorian(0);int f=arg(4);boolean ok=true;
			if((f&2)!=0&&(d[1]<1||d[1]>12))ok=false;
			if((f&4)!=0){int max=new java.util.GregorianCalendar((int)d[0],Math.max(0,Math.min(11,(int)d[1]-1)),1).getActualMaximum(java.util.Calendar.DAY_OF_MONTH);if(d[2]<1||d[2]>max)ok=false;}
			if((f&8)!=0&&(d[3]<0||d[3]>23))ok=false;if((f&16)!=0&&(d[4]<0||d[4]>59))ok=false;if((f&32)!=0&&(d[5]<0||d[5]>=60))ok=false;
			ret(ok?1:0);});
		l.register(null,"CFGregorianDateGetAbsoluteTime",(r,c)->r.retD(absoluteTime(gregorian(0),zone(arg(4)))));
		l.register(null,"CFAbsoluteTimeGetGregorianDate",(r,c)->{writeGregorian(arg(0),r.farg(0),zone(arg(3)));ret(arg(0));}); // (*result, at: slots 1-2, tz)
		l.register(null,"CFAbsoluteTimeAddGregorianUnits",(r,c)->{ // (at: slots 0-1, tz, units: 7 slots {years, months, days, hours, minutes, seconds (2)})
			java.util.GregorianCalendar g=calendar(r.farg(0),zone(arg(2)));double frac=r.farg(0)-Math.floor(r.farg(0));
			g.add(java.util.Calendar.YEAR,arg(3));g.add(java.util.Calendar.MONTH,arg(4));g.add(java.util.Calendar.DAY_OF_MONTH,arg(5));g.add(java.util.Calendar.HOUR_OF_DAY,arg(6));g.add(java.util.Calendar.MINUTE,arg(7));
			double seconds=Double.longBitsToDouble(((long)arg(8)<<32)|(arg(9)&0xffffffffL));
			r.retD(g.getTimeInMillis()/1000.0-SINCE_1970+frac-(frac>0&&g.getTimeInMillis()%1000!=0?0:0)+seconds);});
		l.register(null,"CFAbsoluteTimeGetDifferenceAsGregorianUnits",(r,c)->{ // (*result, at1: slots 1-2, at2: slots 3-4, tz, unitFlags)
			double a1=r.farg(0),a2=r.farg(1);java.util.TimeZone z=zone(arg(5));int flags=arg(6);
			java.util.GregorianCalendar from=calendar(a2,z),to=calendar(a1,z);int sign=a1<a2?-1:1;if(sign<0){java.util.GregorianCalendar t=from;from=to;to=t;}
			int[] fields={java.util.Calendar.YEAR,java.util.Calendar.MONTH,java.util.Calendar.DAY_OF_MONTH,java.util.Calendar.HOUR_OF_DAY,java.util.Calendar.MINUTE};int[] out=new int[5];
			for(int i=0;i<5;i++){if((flags&(1<<i))==0)continue;int n=0;while(true){java.util.GregorianCalendar t=(java.util.GregorianCalendar)from.clone();t.add(fields[i],n+1);if(t.after(to))break;n++;if(n>100000)break;}from.add(fields[i],n);out[i]=n*sign;}
			double seconds=(flags&32)!=0?sign*((to.getTimeInMillis()-from.getTimeInMillis())/1000.0):0;
			int p=arg(0);for(int i=0;i<5;i++)memory.writeLong(p+4*i,out[i]);writeDouble(p+20,seconds);ret(p);});
		l.register(null,"CFAbsoluteTimeGetDayOfWeek",(r,c)->{int d=calendar(r.farg(0),zone(arg(2))).get(java.util.Calendar.DAY_OF_WEEK);ret(d==java.util.Calendar.SUNDAY?7:d-1);}); // 1 Monday .. 7 Sunday
		l.register(null,"CFAbsoluteTimeGetDayOfYear",(r,c)->ret(calendar(r.farg(0),zone(arg(2))).get(java.util.Calendar.DAY_OF_YEAR)));
		l.register(null,"CFAbsoluteTimeGetWeekOfYear",(r,c)->{java.util.GregorianCalendar g=calendar(r.farg(0),zone(arg(2)));g.setFirstDayOfWeek(java.util.Calendar.MONDAY);g.setMinimalDaysInFirstWeek(4);ret(g.get(java.util.Calendar.WEEK_OF_YEAR));}); // ISO 8601
		// time zones
		l.register(null,"CFTimeZoneGetTypeID",(r,c)->ret(TYPE_TIME_ZONE));
		l.register(null,"CFTimeZoneCopySystem",(r,c)->ret(register(new CFTimeZone(java.util.TimeZone.getDefault()))));
		l.register(null,"CFTimeZoneResetSystem",(r,c)->{});
		l.register(null,"CFTimeZoneCopyDefault",(r,c)->ret(register(new CFTimeZone(defaultZone!=null?defaultZone:java.util.TimeZone.getDefault()))));
		l.register(null,"CFTimeZoneSetDefault",(r,c)->{CFTimeZone z=object(arg(0),CFTimeZone.class);if(z!=null)defaultZone=z.zone;});
		l.register(null,"CFTimeZoneCopyKnownNames",(r,c)->{List<Integer> names=new ArrayList<Integer>();for(String id:java.util.TimeZone.getAvailableIDs())if(id.contains("/")&&!id.startsWith("Etc/")&&!id.startsWith("SystemV/"))names.add(newString(id));int a=newObjectArray(names);for(int n:names)release(n);ret(a);});
		l.register(null,"CFTimeZoneCreateWithName",(r,c)->{String n=text(arg(1));if(n==null){ret(0);return;}java.util.TimeZone z=java.util.TimeZone.getTimeZone(n);
			if(z.getID().equals("GMT")&&!n.equals("GMT")&&!n.equals("UTC")){if((arg(2)&0xff)==0){ret(0);return;}String id=java.time.ZoneId.SHORT_IDS.get(n.toUpperCase());if(id==null){ret(0);return;}z=java.util.TimeZone.getTimeZone(id);}
			ret(register(new CFTimeZone(z)));});
		l.register(null,"CFTimeZoneCreateWithTimeIntervalFromGMT",(r,c)->{int s=(int)r.farg(0);ret(register(new CFTimeZone(new java.util.SimpleTimeZone(s*1000,String.format("GMT%+03d%02d",s/3600,Math.abs(s/60)%60)))));});
		l.register(null,"CFTimeZoneCreate",(r,c)->{String n=text(arg(1));ret(n==null?0:register(new CFTimeZone(java.util.TimeZone.getTimeZone(n))));});
		l.register(null,"CFTimeZoneGetName",(r,c)->{CFTimeZone z=object(arg(0),CFTimeZone.class);ret(z==null?0:constantString(z.zone.getID().getBytes(StandardCharsets.ISO_8859_1)));});
		l.register(null,"CFTimeZoneGetData",(r,c)->{CFTimeZone z=object(arg(0),CFTimeZone.class);ret(z==null?0:newData(z.zone.getID().getBytes(StandardCharsets.ISO_8859_1),false));}); // CF returns the zone's TZif data; here its name
		l.register(null,"CFTimeZoneGetSecondsFromGMT",(r,c)->{java.util.TimeZone z=zone(arg(0));r.retD(z.getOffset((long)((r.farg(0)+SINCE_1970)*1000))/1000.0);}); // (tz, at: slots 1-2)
		l.register(null,"CFTimeZoneIsDaylightSavingTime",(r,c)->{java.util.TimeZone z=zone(arg(0));ret(z.inDaylightTime(new java.util.Date((long)((r.farg(0)+SINCE_1970)*1000)))?1:0);});
		l.register(null,"CFTimeZoneCopyAbbreviation",(r,c)->{java.util.TimeZone z=zone(arg(0));boolean dst=z.inDaylightTime(new java.util.Date((long)((r.farg(0)+SINCE_1970)*1000)));ret(newString(z.getDisplayName(dst,java.util.TimeZone.SHORT,java.util.Locale.US)));});
		l.register(null,"CFTimeZoneCopyAbbreviationDictionary",(r,c)->{int d=newTypeDictionary();for(String k:new String[]{"GMT","UTC","EST","EDT","CST","CDT","MST","MDT","PST","PDT","JST","CET","CEST","BST"}){String id=k.equals("GMT")||k.equals("UTC")?"GMT":java.time.ZoneId.SHORT_IDS.getOrDefault(k,k);int v=newString(id);put(d,k,v);release(v);}ret(d);});
		l.register(null,"CFTimeZoneSetAbbreviationDictionary",(r,c)->{});
	}

	// ------------------------------------------------------------------ URLs

	/** A URL: its string as given and the base it is relative to (0 = absolute). */
	final class CFURL extends CFObject implements ValueEquality,Describable {
		final String string;final int base;int stringRef;
		CFURL(String string,int base){super(TYPE_URL);this.string=string;this.base=base;}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {if(base!=0)release(base);if(stringRef!=0)release(stringRef);}
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject o) throws M68kException {CFURL u=(CFURL)o;return u.string.equals(string)&&(u.base==base||equal(u.base,base));}
		@Override public int valueHash(PpcCoreFoundation cf){return string.hashCode();}
		@Override public String description(PpcCoreFoundation cf){return absolute();}
		/** The absolute URL string (resolved against the base). */
		String absolute(){
			if(base==0)return string;CFURL b=url(base);if(b==null)return string;
			try{return new java.net.URI(b.absolute()).resolve(new java.net.URI(string)).toString();}catch(Exception e){return b.absolute()+string;}
		}
		java.net.URI uri(){try{return new java.net.URI(absolute());}catch(Exception e){return null;}}
	}
	CFURL url(int ref){return object(ref,CFURL.class);}
	int newURL(String string,int base) throws M68kCpuException {if(base!=0)retain(base);return register(new CFURL(string,base));}
	/** Percent-escapes a path component for a file URL. */
	private static String escape(String s){
		StringBuilder sb=new StringBuilder();byte[] b=s.getBytes(StandardCharsets.UTF_8);
		for(byte x:b){int v=x&0xff;if(v>0x20&&v<0x7f&&"%#?<>\"{}|\\^[]`; ".indexOf(v)<0)sb.append((char)v);else sb.append(String.format("%%%02X",v));}
		return sb.toString();
	}
	private static String unescape(String s){
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(ch=='%'&&i+2<s.length()){try{out.write(Integer.parseInt(s.substring(i+1,i+3),16));i+=2;continue;}catch(NumberFormatException ignored){}}byte[] b=String.valueOf(ch).getBytes(StandardCharsets.UTF_8);out.write(b,0,b.length);}
		return new String(out.toByteArray(),StandardCharsets.UTF_8);
	}
	/**
	 * The file URL of a host file, as CarbonLib wrote them on Mac OS 9: "file://localhost/" + the volume name + the path,
	 * each component percent-escaped (a directory ends with "/").
	 */
	String fileURLString(java.io.File f,boolean directory){
		List<String> parts=hfsComponents(f);StringBuilder sb=new StringBuilder("file://localhost");
		for(String p:parts)sb.append('/').append(escape(p));
		if(directory||f.isDirectory())sb.append('/');
		return sb.toString();
	}
	/** Volume name and the Mac names of the path from the volume's root down to {@code f} (the host path when it is on no volume). */
	List<String> hfsComponents(java.io.File f){
		List<String> parts=new ArrayList<String>();FileManager68k fm=rt.toolbox.files.fileManager;
		try{
			java.io.File c=f.getCanonicalFile();
			if(!fm.onVolume(c)){for(java.nio.file.Path p:c.toPath())parts.add(p.toString());return parts;}
			java.io.File x=c;
			while(x!=null&&!fm.volumeRoot(x)){parts.add(0,fm.macNameOf(x));x=x.getParentFile();}
			parts.add(0,fm.volumeNameOf(c));
		}catch(java.io.IOException e){parts.add(f.getName());}
		return parts;
	}
	/** The host file an HFS path ("Volume:Folder:File") names, or null. */
	java.io.File fileForHFSPath(String path){
		try{String p=path.indexOf(':')<0?path+":":path;return rt.toolbox.files.fileManager.resolveClassicPath(rt.app.file.getAbsoluteFile().getParentFile(),p);}catch(Exception e){return null;}
	}
	/** The host file a file URL names (volume-relative as above, or a host path that exists), or null. */
	java.io.File fileOf(CFURL u){
		java.net.URI uri=u.uri();if(uri==null||!"file".equalsIgnoreCase(uri.getScheme()))return null;
		String raw=uri.getRawPath();if(raw==null)return null;
		List<String> parts=new ArrayList<String>();for(String p:raw.split("/"))if(!p.isEmpty())parts.add(unescape(p));
		return fileForComponents(parts,raw);
	}
	private java.io.File fileForComponents(List<String> parts,String posix){
		if(parts.isEmpty())return null;
		StringBuilder hfs=new StringBuilder();for(int i=0;i<parts.size();i++){if(i>0)hfs.append(':');hfs.append(parts.get(i).replace(':','/'));}
		if(parts.size()==1)hfs.append(':');
		java.io.File f=fileForHFSPath(hfs.toString());
		if(f!=null)return f;
		java.io.File host=new java.io.File(unescape(posix));
		return host.isAbsolute()&&rt.toolbox.files.fileManager.onVolume(host)?host:null;
	}
	/** A path in a CFURLPathStyle (0 POSIX "/Volume/a/b", 1 HFS "Volume:a:b", 2 Windows) from a URL. */
	String fileSystemPath(CFURL u,int style){
		java.net.URI uri=u.uri();if(uri==null)return null;
		String raw=uri.getRawPath();if(raw==null)return null;
		List<String> parts=new ArrayList<String>();for(String p:raw.split("/"))if(!p.isEmpty())parts.add(unescape(p));
		boolean dir=raw.endsWith("/")&&raw.length()>1;
		if(style==1){StringBuilder sb=new StringBuilder();for(int i=0;i<parts.size();i++){if(i>0)sb.append(':');sb.append(parts.get(i).replace(':','/'));}if(parts.size()==1||dir)sb.append(':');return sb.toString();}
		if(style==2){StringBuilder sb=new StringBuilder();for(String p:parts){if(sb.length()>0)sb.append('\\');sb.append(p);}return sb.toString();}
		StringBuilder sb=new StringBuilder();for(String p:parts)sb.append('/').append(p);if(sb.length()==0)sb.append('/');
		return sb.toString();
	}
	/** A file URL string from a path in a path style (relative paths stay relative, for a base). */
	String urlForPath(String path,int style,boolean directory){
		List<String> parts=new ArrayList<String>();boolean relative;
		if(style==1){relative=path.startsWith(":")||path.indexOf(':')<0;for(String p:path.split(":"))if(!p.isEmpty())parts.add(p.replace('/',':'));}
		else if(style==2){relative=!(path.length()>1&&path.charAt(1)==':')&&!path.startsWith("\\");for(String p:path.split("\\\\"))if(!p.isEmpty())parts.add(p);}
		else{relative=!path.startsWith("/");for(String p:path.split("/"))if(!p.isEmpty())parts.add(p);}
		StringBuilder sb=new StringBuilder(relative?"":"file://localhost");
		for(int i=0;i<parts.size();i++){if(i>0||!relative)sb.append('/');sb.append(escape(parts.get(i)));}
		if(directory&&(sb.length()==0||sb.charAt(sb.length()-1)!='/'))sb.append('/');
		return sb.toString();
	}
	private static String lastComponent(String path){String p=path.endsWith("/")&&path.length()>1?path.substring(0,path.length()-1):path;int i=p.lastIndexOf('/');return i<0?p:p.substring(i+1);}

	private void defineURLs(PpcLibraries l){
		l.register(null,"CFURLGetTypeID",(r,c)->ret(TYPE_URL));
		l.register(null,"CFURLCreateWithString",(r,c)->{String s=text(arg(1));if(s==null){ret(0);return;}try{new java.net.URI(s);}catch(Exception e){ret(0);return;}ret(newURL(s,arg(2)));});
		l.register(null,"CFURLCreateWithBytes",(r,c)->{int p=arg(1),n=arg(2);String s=decode(arg(3),p==0||n<=0?new byte[0]:memory.readBytes(p,n));try{new java.net.URI(s);}catch(Exception e){ret(0);return;}ret(newURL(s,arg(4)));});
		l.register(null,"CFURLCreateData",(r,c)->{CFURL u=url(arg(1));if(u==null){ret(0);return;}String s=u.string;if((arg(3)&0xff)!=0)s=s.replace(" ","%20");ret(newData(encode(arg(2),s),false));});
		l.register(null,"CFURLCreateWithFileSystemPath",(r,c)->{String p=text(arg(1));if(p==null){ret(0);return;}ret(newURL(urlForPath(p,arg(2),(arg(3)&0xff)!=0),0));}); // (alloc, path, pathStyle, isDirectory)
		l.register(null,"CFURLCreateWithFileSystemPathRelativeToBase",(r,c)->{String p=text(arg(1));if(p==null){ret(0);return;}ret(newURL(urlForPath(p,arg(2),(arg(3)&0xff)!=0),arg(4)));});
		l.register(null,"CFURLCreateFromFileSystemRepresentation",(r,c)->{int p=arg(1),n=arg(2);String path=new String(p==0||n<=0?new byte[0]:memory.readBytes(p,n),StandardCharsets.UTF_8);ret(newURL(urlForPath(path,0,(arg(3)&0xff)!=0),0));});
		l.register(null,"CFURLGetFileSystemRepresentation",(r,c)->{ // (url, resolveAgainstBase, buffer, maxBufLen): the POSIX path in UTF-8
			CFURL u=url(arg(0));if(u==null||arg(2)==0){ret(0);return;}String p=fileSystemPath(u,0);if(p==null){ret(0);return;}
			byte[] b=p.getBytes(StandardCharsets.UTF_8);if(b.length+1>arg(3)){ret(0);return;}memory.load(arg(2),b);memory.writeByte(arg(2)+b.length,0);ret(1);});
		l.register(null,"CFURLCreateStringWithFileSystemPath",(r,c)->{CFURL u=url(arg(1));String p=u==null?null:fileSystemPath(u,arg(2));ret(p==null?0:newString(p));}); // the old name of CFURLCopyFileSystemPath (alloc, url, style, resolveAgainstBase)
		l.register(null,"CFURLCopyFileSystemPath",(r,c)->{CFURL u=url(arg(0));String p=u==null?null:fileSystemPath(u,arg(1));ret(p==null?0:newString(p));});
		l.register(null,"CFURLCopyAbsoluteURL",(r,c)->{CFURL u=url(arg(0));if(u==null){ret(0);return;}if(u.base==0){ret(retain(arg(0)));return;}ret(newURL(u.absolute(),0));});
		l.register(null,"CFURLGetString",(r,c)->{CFURL u=url(arg(0));if(u==null){ret(0);return;}if(u.stringRef==0)u.stringRef=newString(u.string);ret(u.stringRef);});
		l.register(null,"CFURLGetBaseURL",(r,c)->{CFURL u=url(arg(0));ret(u==null?0:u.base);});
		l.register(null,"CFURLCanBeDecomposed",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x!=null&&(x.getRawPath()!=null)?1:0);});
		l.register(null,"CFURLCopyScheme",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getScheme()==null?0:newString(x.getScheme()));});
		l.register(null,"CFURLCopyNetLocation",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getRawAuthority()==null?0:newString(x.getRawAuthority()));});
		l.register(null,"CFURLCopyPath",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getRawPath()==null?0:newString(x.getRawPath()));});
		l.register(null,"CFURLCopyStrictPath",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String p=x==null?null:x.getRawPath();boolean abs=p!=null&&p.startsWith("/");if(arg(1)!=0)memory.writeByte(arg(1),abs?1:0);ret(p==null?0:newString(abs?p.substring(1):p));});
		l.register(null,"CFURLHasDirectoryPath",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x!=null&&x.getRawPath()!=null&&x.getRawPath().endsWith("/")?1:0);});
		l.register(null,"CFURLCopyResourceSpecifier",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();if(x==null){ret(0);return;}String s=(x.getRawQuery()!=null?"?"+x.getRawQuery():"")+(x.getRawFragment()!=null?"#"+x.getRawFragment():"");ret(x.isOpaque()?newString(x.getRawSchemeSpecificPart()):s.isEmpty()?0:newString(s));});
		l.register(null,"CFURLCopyHostName",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getHost()==null?0:newString(x.getHost()));});
		l.register(null,"CFURLGetPortNumber",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null?-1:x.getPort());});
		l.register(null,"CFURLCopyUserName",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String i=x==null?null:x.getUserInfo();ret(i==null?0:newString(i.contains(":")?i.substring(0,i.indexOf(':')):i));});
		l.register(null,"CFURLCopyPassword",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String i=x==null?null:x.getUserInfo();ret(i==null||!i.contains(":")?0:newString(i.substring(i.indexOf(':')+1)));});
		l.register(null,"CFURLCopyParameterString",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String p=x==null?null:x.getRawPath();int i=p==null?-1:p.indexOf(';');ret(i<0?0:newString(p.substring(i+1)));});
		l.register(null,"CFURLCopyQueryString",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getRawQuery()==null?0:newString(x.getRawQuery()));});
		l.register(null,"CFURLCopyFragment",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();ret(x==null||x.getRawFragment()==null?0:newString(x.getRawFragment()));});
		l.register(null,"CFURLCopyLastPathComponent",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String p=x==null?null:x.getRawPath();ret(p==null?0:newString(unescape(lastComponent(p))));});
		l.register(null,"CFURLCopyPathExtension",(r,c)->{CFURL u=url(arg(0));java.net.URI x=u==null?null:u.uri();String p=x==null?null:x.getRawPath();if(p==null){ret(0);return;}String last=unescape(lastComponent(p));int i=last.lastIndexOf('.');ret(i<=0?0:newString(last.substring(i+1)));});
		l.register(null,"CFURLCreateCopyAppendingPathComponent",(r,c)->{CFURL u=url(arg(1));String comp=text(arg(2));if(u==null||comp==null){ret(0);return;}String a=u.absolute();if(!a.endsWith("/"))a+="/";ret(newURL(a+escape(comp)+((arg(3)&0xff)!=0?"/":""),0));});
		l.register(null,"CFURLCreateCopyDeletingLastPathComponent",(r,c)->{CFURL u=url(arg(1));if(u==null){ret(0);return;}String a=u.absolute();String t=a.endsWith("/")?a.substring(0,a.length()-1):a;int i=t.lastIndexOf('/');ret(newURL(i<0?"../":t.substring(0,i+1),0));});
		l.register(null,"CFURLCreateCopyAppendingPathExtension",(r,c)->{CFURL u=url(arg(1));String ext=text(arg(2));if(u==null||ext==null){ret(0);return;}String a=u.absolute();boolean dir=a.endsWith("/");if(dir)a=a.substring(0,a.length()-1);ret(newURL(a+"."+escape(ext)+(dir?"/":""),0));});
		l.register(null,"CFURLCreateCopyDeletingPathExtension",(r,c)->{CFURL u=url(arg(1));if(u==null){ret(0);return;}String a=u.absolute();boolean dir=a.endsWith("/");if(dir)a=a.substring(0,a.length()-1);int slash=a.lastIndexOf('/'),dot=a.lastIndexOf('.');if(dot>slash+1)a=a.substring(0,dot);ret(newURL(a+(dir?"/":""),0));});
		l.register(null,"CFURLCreateStringByReplacingPercentEscapes",(r,c)->{String s=text(arg(1)),keep=text(arg(2));if(s==null){ret(0);return;}if(keep==null){ret(retain(arg(1)));return;}
			StringBuilder out=new StringBuilder();for(int i=0;i<s.length();i++){char ch=s.charAt(i);if(ch=='%'&&i+2<s.length()){String e=s.substring(i,i+3);String d=unescape(e);if(d.length()==1&&keep.indexOf(d.charAt(0))>=0){out.append(e);i+=2;continue;}
				int j=i;java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();while(j+2<s.length()+0&&s.charAt(j)=='%'){try{b.write(Integer.parseInt(s.substring(j+1,j+3),16));}catch(Exception ex){break;}j+=3;}
				if(b.size()>0){out.append(new String(b.toByteArray(),StandardCharsets.UTF_8));i=j-1;continue;}}out.append(ch);}
			ret(newString(out.toString()));});
		l.register(null,"CFURLCreateStringByAddingPercentEscapes",(r,c)->{ // (alloc, string, leaveUnescaped, toEscape, encoding)
			String s=text(arg(1));if(s==null){ret(0);return;}String keep=text(arg(2)),force=text(arg(3));StringBuilder out=new StringBuilder();
			for(char ch:s.toCharArray()){boolean legal=ch>0x20&&ch<0x7f&&"%\"<>[\\]^`{|} ".indexOf(ch)<0;if(keep!=null&&keep.indexOf(ch)>=0)legal=true;if(force!=null&&force.indexOf(ch)>=0)legal=false;
				if(legal)out.append(ch);else for(byte b:encode(arg(4),String.valueOf(ch)))out.append(String.format("%%%02X",b&0xff));}
			ret(newString(out.toString()));});
		l.register(null,"CFURLCreateFromFSRef",(r,c)->{java.io.File f=rt.libraries.carbon.fsRefFile(arg(1));ret(f==null?0:newURL(fileURLString(f,f.isDirectory()),0));});
		l.register(null,"CFURLGetFSRef",(r,c)->{CFURL u=url(arg(0));java.io.File f=u==null?null:fileOf(u);if(f==null||!f.exists()){ret(0);return;}rt.libraries.carbon.writeFSRef(arg(1),f);ret(1);});
		// the resource calls: file URLs only
		l.register(null,"CFURLCreateDataAndPropertiesFromResource",(r,c)->{ // (alloc, url, *resourceData, *properties, desiredProperties (array), *errorCode)
			CFURL u=url(arg(1));java.io.File f=u==null?null:fileOf(u);int err=0;
			if(u==null||u.uri()==null||!"file".equalsIgnoreCase(u.uri().getScheme()))err=-10;          // kCFURLUnknownSchemeError
			else if(f==null||!f.exists())err=-12;                                                       // kCFURLResourceNotFoundError
			else if(!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))err=-15;                 // kCFURLResourceAccessViolationError
			if(err==0&&arg(2)!=0){if(f.isDirectory())memory.writeLong(arg(2),newData(new byte[0],false));else try{memory.writeLong(arg(2),newData(java.nio.file.Files.readAllBytes(f.toPath()),false));}catch(java.io.IOException e){err=-12;}}
			if(err==0&&arg(3)!=0)memory.writeLong(arg(3),resourceProperties(f,arg(4)));
			if(arg(5)!=0)memory.writeLong(arg(5),err);ret(err==0?1:0);});
		l.register(null,"CFURLCreatePropertyFromResource",(r,c)->{ // (alloc, url, property, *errorCode)
			CFURL u=url(arg(1));java.io.File f=u==null?null:fileOf(u);String prop=text(arg(2));int v=0,err=0;
			if(f==null)err=-12;else{int props=resourceProperties(f,0);int[] e=dictionary(props)==null?null:dictionary(props).find(arg(2));if(e!=null)v=retain(e[1]);else err=-17;release(props);} // kCFURLUnknownPropertyKeyError
			if(prop!=null&&prop.equals("kCFURLFileExists")&&v==0){v=bool(false);err=0;}
			if(arg(3)!=0)memory.writeLong(arg(3),err);ret(v);});
		l.register(null,"CFURLWriteDataAndPropertiesToResource",(r,c)->{ // (url, data, properties, *errorCode)
			CFURL u=url(arg(0));java.io.File f=u==null?null:fileOf(u);int err=0;
			if(f==null){String p=u==null?null:fileSystemPath(u,1);if(p!=null){int colon=p.lastIndexOf(':',p.length()-2);java.io.File parent=colon<0?null:fileForHFSPath(p.substring(0,colon+1));if(parent!=null)f=new java.io.File(parent,p.substring(colon+1).replace(':',' ').replace('/',':'));}}
			if(f==null)err=-12;else if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))err=-15;
			else{CFData d=object(arg(1),CFData.class);try{if(u.string.endsWith("/"))f.mkdirs();else java.nio.file.Files.write(f.toPath(),d==null?new byte[0]:d.bytes(this));}catch(java.io.IOException e){err=-15;}}
			if(arg(3)!=0)memory.writeLong(arg(3),err);ret(err==0?1:0);});
		l.register(null,"CFURLDestroyResource",(r,c)->{CFURL u=url(arg(0));java.io.File f=u==null?null:fileOf(u);int err=f==null||!f.exists()?-12:!Sandbox.allowFile(f,Sandbox.Access.DELETE,rt.toolbox.name)?-15:DeletedFiles.delete(f,rt.toolbox.name)?0:-15;if(arg(1)!=0)memory.writeLong(arg(1),err);ret(err==0?1:0);});
		for(String[] k:new String[][]{{"kCFURLFileExists","kCFURLFileExists"},{"kCFFileURLExists","kCFURLFileExists"},{"kCFURLFileDirectoryContents","kCFURLFileDirectoryContents"},{"kCFFileURLDirectoryContents","kCFURLFileDirectoryContents"},
			{"kCFURLFileLength","kCFURLFileLength"},{"kCFFileURLSize","kCFURLFileLength"},{"kCFURLFileLastModificationTime","kCFURLFileLastModificationTime"},{"kCFFileURLLastModificationTime","kCFURLFileLastModificationTime"},
			{"kCFURLFilePOSIXMode","kCFURLFilePOSIXMode"},{"kCFFileURLPOSIXMode","kCFURLFilePOSIXMode"},{"kCFURLFileOwnerID","kCFURLFileOwnerID"},{"kCFURLHTTPStatusCode","kCFURLHTTPStatusCode"},{"kCFHTTPURLStatusCode","kCFURLHTTPStatusCode"},
			{"kCFURLHTTPStatusLine","kCFURLHTTPStatusLine"},{"kCFHTTPURLStatusLine","kCFURLHTTPStatusLine"}}){final String v=k[1];l.registerData(k[0],()->stringVariable(v));}
	}
	/** The kCFURLFile... properties of a file as a dictionary (all of them; {@code wanted} is ignored). */
	private int resourceProperties(java.io.File f,int wanted) throws M68kException {
		int d=newTypeDictionary();
		int v=bool(f.exists());put(d,"kCFURLFileExists",v);
		if(f.exists()){
			if(f.isDirectory()){String[] names=f.list();List<Integer> urls=new ArrayList<Integer>();if(names!=null){java.util.Arrays.sort(names);for(String n:names){if(n.startsWith("."))continue;java.io.File x=new java.io.File(f,n);urls.add(newURL(fileURLString(x,x.isDirectory()),0));}}
				int a=newObjectArray(urls);for(int u:urls)release(u);put(d,"kCFURLFileDirectoryContents",a);release(a);}
			int p=rt.allocate(8,4);memory.writeLong(p,(int)(f.length()>>>32));memory.writeLong(p+4,(int)f.length());int n=newNumber(4,p);put(d,"kCFURLFileLength",n);release(n);
			int t=newDate(f.lastModified()/1000.0-SINCE_1970);put(d,"kCFURLFileLastModificationTime",t);release(t);
			memory.writeLong(p,f.isDirectory()?0755:0644);n=newNumber(3,p);put(d,"kCFURLFilePOSIXMode",n);release(n);memory.free(p);
		}
		return d;
	}
	// ------------------------------------------------------------------ bundles

	/**
	 * A bundle: a package folder ("X.app" with Contents/Info.plist, Contents/Resources, Contents/MacOSClassic or MacOS) or,
	 * for the main bundle of a program that is a single file, the program's folder with the program's 'plst' 0 resource as
	 * its information dictionary - as CarbonLib treated unpackaged applications on Mac OS 9.
	 */
	final class CFBundle extends CFObject implements Describable {
		final java.io.File directory;final java.io.File singleFile;final boolean main;
		int info,localInfo;PpcRuntime.Fragment fragment;boolean loaded;int plugIn;
		CFBundle(java.io.File directory,java.io.File singleFile,boolean main){super(TYPE_BUNDLE);this.directory=directory;this.singleFile=singleFile;this.main=main;constant=main;}
		boolean packaged(){return new java.io.File(directory,"Contents").isDirectory();}
		java.io.File contents(){return packaged()?new java.io.File(directory,"Contents"):directory;}
		java.io.File resources(){return packaged()?new java.io.File(contents(),"Resources"):directory;}
		@Override public String description(PpcCoreFoundation cf){return "CFBundle "+directory;}
	}
	private final List<CFBundle> bundles=new ArrayList<CFBundle>();
	private CFBundle mainBundle;
	CFBundle mainBundle() throws M68kException {
		if(mainBundle==null){
			java.io.File app=rt.app.file.getAbsoluteFile(),dir=app.getParentFile(),pkg=null;
			// .../X.app/Contents/MacOS(Classic)/program -> X.app
			if(dir!=null&&(dir.getName().equals("MacOS")||dir.getName().equals("MacOSClassic"))&&dir.getParentFile()!=null&&dir.getParentFile().getName().equals("Contents"))pkg=dir.getParentFile().getParentFile();
			mainBundle=pkg!=null?new CFBundle(pkg,null,true):new CFBundle(dir,app,true);
			register(mainBundle);bundles.add(mainBundle);mainBundle.loaded=true;
		}
		return mainBundle;
	}
	private CFBundle bundle(int ref){return object(ref,CFBundle.class);}
	/** A bundle's Resources folder (the program's folder for an unpackaged main bundle), null for no bundle. */
	java.io.File bundleResources(int ref){CFBundle b=bundle(ref);return b==null?null:b.resources();}
	/** The bundle for a folder (made once per folder), or null when the folder does not exist. */
	private CFBundle bundleFor(java.io.File dir) throws M68kException {
		if(dir==null||!dir.isDirectory())return null;
		CFBundle m=mainBundle();try{if(m.directory.getCanonicalFile().equals(dir.getCanonicalFile()))return m;for(CFBundle b:bundles)if(b.directory.getCanonicalFile().equals(dir.getCanonicalFile())){b.references++;return b;}}catch(java.io.IOException ignored){}
		CFBundle b=new CFBundle(dir,null,false);register(b);bundles.add(b);return b;
	}
	/**
	 * The information dictionary: Contents/Info-macos.plist (CarbonLib on Mac OS 9 prefers it; DragThing 4.5 has no other)
	 * or Contents/Info.plist, else (a single-file program) its 'plst' 0 resource, else empty.
	 */
	int infoDictionary(CFBundle b) throws M68kException {
		if(b.info!=0)return b.info;
		byte[] xml=null;
		java.io.File plist=infoPlist(b.contents());
		if(b.packaged()&&plist!=null)try{xml=java.nio.file.Files.readAllBytes(plist.toPath());}catch(java.io.IOException ignored){}
		if(xml==null&&b.main){ResourceFile68k.Resource res=rt.toolbox.resources.find(0x706c7374,(short)0);if(res!=null)xml=rt.toolbox.resources.data(res);}
		Object parsed=xml==null?null:parsePropertyList(xml);
		b.info=parsed instanceof Map?toCF(parsed,true):newTypeDictionary();
		CFObject o=object(b.info);if(o!=null)o.constant=true;
		return b.info;
	}
	/** A package's Contents/Info-macos.plist (the Mac OS 9 one), else Contents/Info.plist, else null. */
	static java.io.File infoPlist(java.io.File contents){
		for(String name:new String[]{"Info-macos.plist","Info.plist"}){java.io.File f=new java.io.File(contents,name);if(f.isFile())return f;}
		return null;
	}
	private String infoString(CFBundle b,String key) throws M68kException {return text(lookup(infoDictionary(b),key));}
	/** The .lproj folders to search, most preferred first: the system's language, English, the development region, any. */
	private List<java.io.File> localizations(CFBundle b) throws M68kException {
		List<java.io.File> out=new ArrayList<java.io.File>();java.io.File res=b.resources();
		List<String> names=new ArrayList<String>();
		if(rt.toolbox.encoding==StackTextEncoding.JAPANESE){names.add("Japanese");names.add("ja");names.add("ja_JP");}
		String dev=infoString(b,"CFBundleDevelopmentRegion");if(dev!=null)names.add(dev);
		names.add("English");names.add("en");names.add("en_US");names.add("Base");
		for(String n:names){java.io.File f=new java.io.File(res,n+".lproj");if(f.isDirectory()&&!out.contains(f))out.add(f);}
		java.io.File[] all=res.listFiles();if(all!=null){java.util.Arrays.sort(all);for(java.io.File f:all)if(f.getName().endsWith(".lproj")&&f.isDirectory()&&!out.contains(f))out.add(f);}
		return out;
	}
	/** A resource file of the bundle: Resources/[sub/]name.type, then the localized folders. */
	private java.io.File resource(CFBundle b,String name,String type,String sub) throws M68kException {
		String file=type==null||type.isEmpty()?name:name+"."+type;
		List<java.io.File> dirs=new ArrayList<java.io.File>();dirs.add(b.resources());dirs.addAll(localizations(b));
		for(java.io.File d:dirs){java.io.File f=new java.io.File(sub==null||sub.isEmpty()?d:new java.io.File(d,sub),file);if(f.exists())return f;}
		return null;
	}
	private List<java.io.File> resourcesOfType(CFBundle b,String type,String sub) throws M68kException {
		List<java.io.File> out=new ArrayList<java.io.File>();List<String> seen=new ArrayList<String>();
		List<java.io.File> dirs=new ArrayList<java.io.File>();dirs.add(b.resources());dirs.addAll(localizations(b));
		for(java.io.File d:dirs){java.io.File[] fs=(sub==null||sub.isEmpty()?d:new java.io.File(d,sub)).listFiles();if(fs==null)continue;java.util.Arrays.sort(fs);
			for(java.io.File f:fs)if(!f.getName().startsWith(".")&&(type==null||type.isEmpty()||f.getName().endsWith("."+type))&&!seen.contains(f.getName())){seen.add(f.getName());out.add(f);}}
		return out;
	}
	/** The bundle's executable: Contents/MacOSClassic/<CFBundleExecutable>, else Contents/MacOS/..., else the program file. */
	private java.io.File executable(CFBundle b) throws M68kException {
		if(b.singleFile!=null)return b.singleFile;
		String exe=infoString(b,"CFBundleExecutable");if(exe==null)return null;
		for(String d:new String[]{"MacOSClassic","MacOS"}){java.io.File f=new java.io.File(new java.io.File(b.contents(),d),exe);if(f.exists())return f;}
		return null;
	}
	/** Loads the bundle's code fragment (a PEF in the executable's data fork); false when it has none (a Mach-O executable). */
	private boolean load(CFBundle b) throws M68kException {
		if(b.main||b.loaded)return true;
		java.io.File exe=executable(b);if(exe==null||!exe.isFile())return false;
		try{
			byte[] data=PpcRuntime.dataFork(exe);
			int at=-1;for(int i=0;i+8<=data.length&&i<4096;i+=4)if(data[i]=='J'&&data[i+1]=='o'&&data[i+2]=='y'&&data[i+3]=='!'){at=i;break;}
			if(at<0)return false;
			b.fragment=rt.loadFragment(exe.getName(),exe,data,at,data.length-at);b.loaded=true;return true;
		}catch(java.io.IOException e){System.err.println("[ppc "+rt.app.name+"] CFBundleLoadExecutable "+exe+": "+e.getMessage());return false;}
	}
	/** An exported symbol of a loaded bundle (the main bundle: the application's exports). */
	private int symbol(CFBundle b,String name) throws M68kException {
		if(name==null||!load(b))return 0;
		PpcRuntime.Fragment f=b.main?rt.application:b.fragment;if(f==null)return 0;
		Integer v=f.exports.get(name);return v==null?0:v;
	}
	/** Localizable strings: a .strings file (old-style property list: "key" = "value";) of the bundle, parsed once per table. */
	private final Map<String,Map<String,Object>> stringTables=new HashMap<String,Map<String,Object>>();
	@SuppressWarnings("unchecked")
	private Map<String,Object> stringTable(CFBundle b,String table) throws M68kException {
		String key=b.directory+"/"+table;Map<String,Object> t=stringTables.get(key);if(t!=null)return t;
		t=new java.util.LinkedHashMap<String,Object>();
		java.io.File f=resource(b,table,"strings",null);
		if(f!=null)try{Object o=parsePropertyList(java.nio.file.Files.readAllBytes(f.toPath()));if(o instanceof Map)t=(Map<String,Object>)o;}catch(java.io.IOException ignored){}
		stringTables.put(key,t);return t;
	}
	private int urlOf(java.io.File f) throws M68kCpuException {return f==null?0:newURL(fileURLString(f,f.isDirectory()),0);}
	private CFBundle bundleOfURL(int ref) throws M68kException {CFURL u=url(ref);return u==null?null:bundleFor(fileOf(u));}

	private void defineBundles(PpcLibraries l){
		for(String[] k:new String[][]{{"kCFBundleInfoDictionaryVersionKey","CFBundleInfoDictionaryVersion"},{"kCFBundleExecutableKey","CFBundleExecutable"},{"kCFBundleIdentifierKey","CFBundleIdentifier"},
			{"kCFBundleVersionKey","CFBundleVersion"},{"kCFBundleDevelopmentRegionKey","CFBundleDevelopmentRegion"},{"kCFBundleNameKey","CFBundleName"},{"kCFBundleLocalizationsKey","CFBundleLocalizations"},
			{"kCFPlugInDynamicRegistrationKey","CFPlugInDynamicRegistration"},{"kCFPlugInDynamicRegisterFunctionKey","CFPlugInDynamicRegisterFunction"},{"kCFPlugInUnloadFunctionKey","CFPlugInUnloadFunction"},
			{"kCFPlugInFactoriesKey","CFPlugInFactories"},{"kCFPlugInTypesKey","CFPlugInTypes"}}){final String v=k[1];l.registerData(k[0],()->stringVariable(v));}
		l.register(null,"CFBundleGetTypeID",(r,c)->ret(TYPE_BUNDLE));
		l.register(null,"CFBundleGetMainBundle",(r,c)->ret(mainBundle().address));
		l.register(null,"CFBundleCreate",(r,c)->{CFBundle b=bundleOfURL(arg(1));ret(b==null?0:b.address);});
		l.register(null,"CFBundleCreateBundlesFromDirectory",(r,c)->{ // (alloc, directoryURL, bundleType extension or NULL)
			CFURL u=url(arg(1));java.io.File d=u==null?null:fileOf(u);String type=text(arg(2));List<Integer> found=new ArrayList<Integer>();
			java.io.File[] fs=d==null?null:d.listFiles();if(fs!=null){java.util.Arrays.sort(fs);for(java.io.File f:fs)if(f.isDirectory()&&(type==null||f.getName().endsWith("."+type))&&new java.io.File(f,"Contents").isDirectory()){CFBundle b=bundleFor(f);if(b!=null)found.add(b.address);}}
			int a=newObjectArray(found);for(int b:found)release(b);ret(a);});
		l.register(null,"CFBundleGetBundleWithIdentifier",(r,c)->{String id=text(arg(0));mainBundle();if(id!=null)for(CFBundle b:bundles)if(id.equals(infoString(b,"CFBundleIdentifier"))){ret(b.address);return;}ret(0);});
		l.register(null,"CFBundleGetAllBundles",(r,c)->{mainBundle();List<Integer> all=new ArrayList<Integer>();for(CFBundle b:bundles)all.add(b.address);ret(newObjectArray(all));});
		l.register(null,"CFBundleCopyBundleURL",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(b.directory));});
		l.register(null,"CFBundleCopyExecutableURL",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(executable(b)));});
		l.register(null,"CFBundleCopyResourcesDirectoryURL",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(b.resources()));});
		l.register(null,"CFBundleCopySupportFilesDirectoryURL",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(b.contents()));});
		for(String[] k:new String[][]{{"CFBundleCopyBuiltInPlugInsURL","PlugIns"},{"CFBundleCopyPrivateFrameworksURL","Frameworks"},{"CFBundleCopySharedFrameworksURL","SharedFrameworks"},{"CFBundleCopySharedSupportURL","SharedSupport"}}){
			final String sub=k[1];l.register(null,k[0],(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:newURL(fileURLString(new java.io.File(b.contents(),sub),true),0));});}
		l.register(null,"CFBundleGetIdentifier",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:lookup(infoDictionary(b),"CFBundleIdentifier"));});
		l.register(null,"CFBundleGetInfoDictionary",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:infoDictionary(b));});
		l.register(null,"CFBundleGetLocalInfoDictionary",(r,c)->{CFBundle b=bundle(arg(0));if(b==null){ret(0);return;}if(b.localInfo==0){b.localInfo=toCF(stringTable(b,"InfoPlist"),false);CFObject o=object(b.localInfo);if(o!=null)o.constant=true;}ret(b.localInfo);});
		l.register(null,"CFBundleGetValueForInfoDictionaryKey",(r,c)->{CFBundle b=bundle(arg(0));if(b==null){ret(0);return;}
			String k=text(arg(1));Object local=k==null?null:stringTable(b,"InfoPlist").get(k);if(local instanceof String){int s=newString((String)local);ret(s);return;} // (a leak per call, like a constant)
			CFDictionary d=dictionary(infoDictionary(b));int[] e=d==null?null:d.find(arg(1));ret(e==null?0:e[1]);});
		l.register(null,"CFBundleGetDevelopmentRegion",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:lookup(infoDictionary(b),"CFBundleDevelopmentRegion"));});
		l.register(null,"CFBundleCopyInfoDictionaryInDirectory",(r,c)->{CFURL u=url(arg(0));java.io.File d=u==null?null:fileOf(u);java.io.File p=d==null?null:infoPlist(new java.io.File(d,"Contents"));
			if(p==null){ret(0);return;}try{Object o=parsePropertyList(java.nio.file.Files.readAllBytes(p.toPath()));ret(o instanceof Map?toCF(o,false):0);}catch(java.io.IOException e){ret(0);}});
		l.register(null,"CFBundleGetPackageInfo",(r,c)->{CFBundle b=bundle(arg(0));int[] tc=b==null?new int[]{0x3f3f3f3f,0x3f3f3f3f}:packageInfo(b.directory,b);if(arg(1)!=0)memory.writeLong(arg(1),tc[0]);if(arg(2)!=0)memory.writeLong(arg(2),tc[1]);});
		l.register(null,"CFBundleGetPackageInfoInDirectory",(r,c)->{CFURL u=url(arg(0));java.io.File d=u==null?null:fileOf(u);int[] tc=d==null?null:packageInfo(d,null);if(tc!=null){if(arg(1)!=0)memory.writeLong(arg(1),tc[0]);if(arg(2)!=0)memory.writeLong(arg(2),tc[1]);}ret(tc!=null?1:0);});
		l.register(null,"CFBundleGetVersionNumber",(r,c)->{CFBundle b=bundle(arg(0));String v=b==null?null:infoString(b,"CFBundleVersion");ret(versionNumber(v));});
		l.register(null,"CFBundleCopyResourceURL",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(resource(b,text(arg(1)),text(arg(2)),text(arg(3)))));});
		l.register(null,"CFBundleCopyResourceURLForLocalization",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:urlOf(resource(b,text(arg(1)),text(arg(2)),text(arg(3)))));});
		l.register(null,"CFBundleCopyResourceURLsOfType",(r,c)->{CFBundle b=bundle(arg(0));if(b==null){ret(0);return;}List<Integer> urls=new ArrayList<Integer>();for(java.io.File f:resourcesOfType(b,text(arg(1)),text(arg(2))))urls.add(urlOf(f));int a=newObjectArray(urls);for(int u:urls)release(u);ret(a);});
		l.register(null,"CFBundleCopyResourceURLsOfTypeForLocalization",(r,c)->{CFBundle b=bundle(arg(0));if(b==null){ret(0);return;}List<Integer> urls=new ArrayList<Integer>();for(java.io.File f:resourcesOfType(b,text(arg(1)),text(arg(2))))urls.add(urlOf(f));int a=newObjectArray(urls);for(int u:urls)release(u);ret(a);});
		l.register(null,"CFBundleCopyResourceURLInDirectory",(r,c)->{CFBundle b=bundleOfURL(arg(0));if(b==null){ret(0);return;}int u=urlOf(resource(b,text(arg(1)),text(arg(2)),text(arg(3))));if(b!=mainBundle)release(b.address);ret(u);});
		l.register(null,"CFBundleCopyResourceURLsOfTypeInDirectory",(r,c)->{CFBundle b=bundleOfURL(arg(0));if(b==null){ret(0);return;}List<Integer> urls=new ArrayList<Integer>();for(java.io.File f:resourcesOfType(b,text(arg(1)),text(arg(2))))urls.add(urlOf(f));int a=newObjectArray(urls);for(int u:urls)release(u);if(b!=mainBundle)release(b.address);ret(a);});
		l.register(null,"CFBundleCopyLocalizedString",(r,c)->{ // (bundle, key, value, tableName): the table's string, else value, else key
			CFBundle b=bundle(arg(0));String key=text(arg(1));String table=text(arg(3));
			Object v=b==null||key==null?null:stringTable(b,table==null||table.isEmpty()?"Localizable":table).get(key);
			if(v instanceof String){ret(newString((String)v));return;}
			String dflt=text(arg(2));ret(dflt!=null&&!dflt.isEmpty()?retain(arg(2)):arg(1)!=0?retain(arg(1)):newString(""));});
		l.register(null,"CFBundleCopyBundleLocalizations",(r,c)->{CFBundle b=bundle(arg(0));List<Integer> names=new ArrayList<Integer>();if(b!=null)for(java.io.File f:localizations(b))names.add(newString(f.getName().substring(0,f.getName().length()-6)));int a=newObjectArray(names);for(int n:names)release(n);ret(a);});
		l.register(null,"CFBundleCopyPreferredLocalizationsFromArray",(r,c)->{CFArray a=array(arg(0));List<Integer> out=new ArrayList<Integer>();if(a!=null&&!a.values.isEmpty()){String want=rt.toolbox.encoding==StackTextEncoding.JAPANESE?"Japanese":"English";int pick=a.values.get(0);for(int v:a.values){String s=text(v);if(s!=null&&(s.equals(want)||s.startsWith(want.substring(0,2).toLowerCase())))pick=v;}out.add(pick);}ret(newObjectArray(out));});
		l.register(null,"CFBundleLoadExecutable",(r,c)->{CFBundle b=bundle(arg(0));ret(b!=null&&load(b)?1:0);});
		l.register(null,"CFBundleIsExecutableLoaded",(r,c)->{CFBundle b=bundle(arg(0));ret(b!=null&&(b.main||b.loaded)?1:0);});
		l.register(null,"CFBundleUnloadExecutable",(r,c)->{}); // fragments stay: CFM programs keep the TVectors they looked up
		l.register(null,"CFBundleGetFunctionPointerForName",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:symbol(b,text(arg(1))));});
		l.register(null,"CFBundleGetDataPointerForName",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:symbol(b,text(arg(1))));});
		l.register(null,"CFBundleGetFunctionPointersForNames",(r,c)->{CFBundle b=bundle(arg(0));CFArray names=array(arg(1));if(names==null||arg(2)==0)return;for(int i=0;i<names.values.size();i++)memory.writeLong(arg(2)+4*i,b==null?0:symbol(b,text(names.values.get(i))));});
		l.register(null,"CFBundleOpenBundleResourceMap",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?-1:openBundleResources(b));});
		l.register(null,"CFBundleOpenBundleResourceFiles",(r,c)->{CFBundle b=bundle(arg(0));int ref=b==null?-1:openBundleResources(b);if(arg(1)!=0)memory.writeWord(arg(1),ref);if(arg(2)!=0)memory.writeWord(arg(2),-1);ret(ref<0?-192:0);});
		l.register(null,"CFBundleCloseBundleResourceMap",(r,c)->{int ref=(short)arg(1);if(ref>0)rt.toolbox("CloseResFile",ref);});
		l.register(null,"CFBundleGetPlugIn",(r,c)->{CFBundle b=bundle(arg(0));ret(b==null?0:b.plugIn);});
	}
	/** The bundle's resource file: Resources/<executable>.rsrc or a .rsrc in Resources, else the executable's resource fork (the main bundle: the program's own, already open). */
	private int openBundleResources(CFBundle b) throws M68kException {
		if(b.main&&b.singleFile!=null)return (short)memory.readWord(0x900); // CurApRefNum
		List<java.io.File> candidates=new ArrayList<java.io.File>();String exe=infoString(b,"CFBundleExecutable");
		if(exe!=null)candidates.add(new java.io.File(b.resources(),exe+".rsrc"));candidates.addAll(resourcesOfType(b,"rsrc",null));java.io.File x=executable(b);if(x!=null)candidates.add(x);
		for(java.io.File f:candidates){if(!f.exists())continue;int spec=rt.allocate(80,4);try{rt.libraries.makeFSSpec(f,spec);int ref=(short)rt.toolbox("FSpOpenResFile",spec,1);if(ref>0)return ref;}catch(M68kException e){}finally{memory.free(spec);}}
		return -1;
	}
	/** A package's type and creator: Contents/PkgInfo, else the Info.plist, else '????'. */
	private int[] packageInfo(java.io.File dir,CFBundle b) throws M68kException {
		java.io.File pkg=new java.io.File(new java.io.File(dir,"Contents"),"PkgInfo");
		if(pkg.isFile())try{byte[] d=java.nio.file.Files.readAllBytes(pkg.toPath());if(d.length>=8)return new int[]{u4(d,0),u4(d,4)};}catch(java.io.IOException ignored){}
		if(b==null&&!new java.io.File(dir,"Contents").isDirectory())return null;
		CFBundle x=b!=null?b:bundleFor(dir);String t=x==null?null:infoString(x,"CFBundlePackageType"),s=x==null?null:infoString(x,"CFBundleSignature");
		return new int[]{fourcc(t),fourcc(s)};
	}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}
	private static int fourcc(String s){if(s==null||s.length()!=4)return 0x3f3f3f3f;byte[] b=s.getBytes(StandardCharsets.ISO_8859_1);return u4(b,0);}
	/** "1.2.3b4" as the 'vers' style number CFBundleGetVersionNumber returns: major (BCD), minor.bug, stage, non-release. */
	static int versionNumber(String v){
		if(v==null)return 0;java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:([dab]|f|fc)(\\d+))?").matcher(v.trim());if(!m.find())return 0;
		int major=Math.min(99,Integer.parseInt(m.group(1))),minor=m.group(2)==null?0:Math.min(9,Integer.parseInt(m.group(2))),bug=m.group(3)==null?0:Math.min(9,Integer.parseInt(m.group(3)));
		int stage=m.group(4)==null?0x80:m.group(4).equals("d")?0x20:m.group(4).equals("a")?0x40:m.group(4).equals("b")?0x60:0x80,build=m.group(5)==null?0:Math.min(255,Integer.parseInt(m.group(5)));
		return ((major/10)<<28)|((major%10)<<24)|(minor<<20)|(bug<<16)|(stage<<8)|build;
	}

	// ------------------------------------------------------------------ preferences

	/** Preferences per application ID, as property-list values, loaded from and written to "<ID>.plist" in the Preferences folder. */
	private final Map<String,Map<String,Object>> preferences=new HashMap<String,Map<String,Object>>();
	private final java.util.Set<String> dirtyPreferences=new java.util.HashSet<String>();
	/** The application ID kCFPreferencesCurrentApplication stands for: the main bundle's identifier, else the program's name. */
	private String currentApplication() throws M68kException {String id=text(lookup(infoDictionary(mainBundle()),"CFBundleIdentifier"));return id!=null&&!id.isEmpty()?id:rt.app.name;}
	private String applicationID(int ref) throws M68kException {
		String id=text(ref);
		if(id==null||id.equals("kCFPreferencesCurrentApplication"))return currentApplication();
		if(id.equals("kCFPreferencesAnyApplication"))return ".GlobalPreferences";
		return id;
	}
	private java.io.File preferencesFile(String id){
		java.io.File dir=new java.io.File(rt.toolbox.files.systemFolder,"Preferences");
		return new java.io.File(dir,id.replace('/',':').replace(java.io.File.separatorChar,'_')+".plist");
	}
	@SuppressWarnings("unchecked")
	private Map<String,Object> preferences(String id){
		Map<String,Object> p=preferences.get(id);if(p!=null)return p;
		p=new java.util.LinkedHashMap<String,Object>();java.io.File f=preferencesFile(id);
		if(f.isFile()&&Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))try{Object o=parsePropertyList(java.nio.file.Files.readAllBytes(f.toPath()));if(o instanceof Map)p.putAll((Map<String,Object>)o);}catch(java.io.IOException ignored){}
		preferences.put(id,p);return p;
	}
	/** Writes an application's preferences (CFPreferencesAppSynchronize); false when the file cannot be written. */
	private boolean synchronize(String id){
		if(!dirtyPreferences.remove(id))return true;
		java.io.File f=preferencesFile(id);
		if(!Sandbox.allowFile(f,Sandbox.Access.WRITE,rt.toolbox.name))return false;
		try{
			if(preferences(id).isEmpty()){java.nio.file.Files.deleteIfExists(f.toPath());return true;} // nothing left: no file
			f.getParentFile().mkdirs();java.nio.file.Files.write(f.toPath(),writePropertyList(preferences(id)));return true;
		}catch(java.io.IOException e){return false;}
	}
	private int copyPreference(String key,String id) throws M68kException {
		Object v=preferences(id).get(key);if(v==null&&!id.equals(".GlobalPreferences"))v=preferences(".GlobalPreferences").get(key);
		return v==null?0:toCF(v,false);
	}
	private void setPreference(String key,int value,String id) throws M68kException {
		if(key==null)return;Map<String,Object> p=preferences(id);
		if(value==0)p.remove(key);else{Object v=toJava(value);if(v!=null)p.put(key,v);}
		dirtyPreferences.add(id);
	}

	private void definePreferences(PpcLibraries l){
		for(String k:new String[]{"kCFPreferencesAnyApplication","kCFPreferencesCurrentApplication","kCFPreferencesAnyHost","kCFPreferencesCurrentHost","kCFPreferencesAnyUser","kCFPreferencesCurrentUser"})l.registerData(k,()->stringVariable(k));
		l.register(null,"CFPreferencesCopyAppValue",(r,c)->{String k=text(arg(0));ret(k==null?0:copyPreference(k,applicationID(arg(1))));});
		l.register(null,"CFPreferencesCopyValue",(r,c)->{String k=text(arg(0));ret(k==null?0:copyPreference(k,applicationID(arg(1))));});
		l.register(null,"CFPreferencesGetAppBooleanValue",(r,c)->{ // (key, app, *keyExistsAndHasValidFormat)
			String k=text(arg(0));Object v=k==null?null:preferences(applicationID(arg(1))).get(k);boolean ok=true;boolean b=false;
			if(v instanceof Boolean)b=(Boolean)v;else if(v instanceof Number)b=((Number)v).doubleValue()!=0;else if(v instanceof String){String s=((String)v).trim();if(s.equalsIgnoreCase("yes")||s.equalsIgnoreCase("true"))b=true;else if(s.equalsIgnoreCase("no")||s.equalsIgnoreCase("false"))b=false;else ok=false;}else ok=false;
			if(arg(2)!=0)memory.writeByte(arg(2),ok?1:0);ret(ok&&b?1:0);});
		l.register(null,"CFPreferencesGetAppIntegerValue",(r,c)->{
			String k=text(arg(0));Object v=k==null?null:preferences(applicationID(arg(1))).get(k);boolean ok=true;long n=0;
			if(v instanceof Number)n=((Number)v).longValue();else if(v instanceof String){try{n=Long.parseLong(((String)v).trim());}catch(NumberFormatException e){ok=false;}}else ok=false;
			if(arg(2)!=0)memory.writeByte(arg(2),ok?1:0);ret(ok?(int)n:0);});
		l.register(null,"CFPreferencesSetAppValue",(r,c)->setPreference(text(arg(0)),arg(1),applicationID(arg(2))));
		l.register(null,"CFPreferencesSetValue",(r,c)->setPreference(text(arg(0)),arg(1),applicationID(arg(2))));
		l.register(null,"CFPreferencesAppSynchronize",(r,c)->ret(synchronize(applicationID(arg(0)))?1:0));
		l.register(null,"CFPreferencesSynchronize",(r,c)->ret(synchronize(applicationID(arg(0)))?1:0));
		l.register(null,"CFPreferencesAppValueIsForced",(r,c)->ret(0));
		l.register(null,"CFPreferencesAddSuitePreferencesToApp",(r,c)->{});
		l.register(null,"CFPreferencesRemoveSuitePreferencesFromApp",(r,c)->{});
		l.register(null,"CFPreferencesCopyKeyList",(r,c)->{Map<String,Object> p=preferences(applicationID(arg(0)));if(p.isEmpty()){ret(0);return;}List<Integer> keys=new ArrayList<Integer>();for(String k:p.keySet())keys.add(newString(k));int a=newObjectArray(keys);for(int k:keys)release(k);ret(a);});
		l.register(null,"CFPreferencesCopyApplicationList",(r,c)->{java.io.File dir=new java.io.File(rt.toolbox.files.systemFolder,"Preferences");List<Integer> ids=new ArrayList<Integer>();
			String[] names=dir.list();if(names!=null){java.util.Arrays.sort(names);for(String n:names)if(n.endsWith(".plist")&&!n.startsWith("."))ids.add(newString(n.substring(0,n.length()-6)));}
			int a=newObjectArray(ids);for(int i:ids)release(i);ret(a);});
		l.register(null,"CFPreferencesCopyMultiple",(r,c)->{ // (keysToFetch or NULL, app, user, host)
			String id=applicationID(arg(1));int d=newTypeDictionary();CFArray keys=array(arg(0));
			for(Map.Entry<String,Object> e:preferences(id).entrySet()){boolean want=keys==null;if(keys!=null)for(int k:keys.values)if(e.getKey().equals(text(k)))want=true;if(!want)continue;int v=toCF(e.getValue(),false);put(d,e.getKey(),v);release(v);}
			ret(d);});
		l.register(null,"CFPreferencesSetMultiple",(r,c)->{ // (keysToSet dictionary, keysToRemove array, app, user, host)
			String id=applicationID(arg(2));CFDictionary d=dictionary(arg(0));if(d!=null)for(int[] e:d.entries)setPreference(text(e[0]),e[1],id);
			CFArray rm=array(arg(1));if(rm!=null)for(int k:rm.values)setPreference(text(k),0,id);});
	}
	/** Writes back what the program set but never synchronized, as CF did when a program quit (called at shut-down). */
	void flushPreferences(){for(String id:new ArrayList<String>(dirtyPreferences))synchronize(id);}
	// ------------------------------------------------------------------ property lists

	/** A date in a Java-side property list (absolute time). */
	static final class PlistDate {final double at;PlistDate(double at){this.at=at;}}
	/**
	 * A property list from its XML form (the Apple plist DTD) or the old ASCII form ({ key = value; } / ( a, b ) / "strings"
	 * / <hex data>, also the bare "key" = "value"; form of .strings files); UTF-16 text is recognised by its byte order mark.
	 * Values: String, Long, Double, Boolean, byte[], PlistDate, List, Map. Null when the text is not a property list.
	 */
	static Object parsePropertyList(byte[] bytes){
		String text;
		if(bytes.length>=2&&(bytes[0]&0xff)==0xfe&&(bytes[1]&0xff)==0xff)text=new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
		else if(bytes.length>=2&&(bytes[0]&0xff)==0xff&&(bytes[1]&0xff)==0xfe)text=new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
		else if(bytes.length>=3&&(bytes[0]&0xff)==0xef&&(bytes[1]&0xff)==0xbb&&(bytes[2]&0xff)==0xbf)text=new String(bytes,3,bytes.length-3,StandardCharsets.UTF_8);
		else text=new String(bytes,StandardCharsets.UTF_8);
		String t=text.trim();
		if(t.startsWith("<?xml")||t.startsWith("<!DOCTYPE")||t.startsWith("<plist")){
			try{
				javax.xml.parsers.DocumentBuilderFactory f=XmlSafe.dom();
				f.setValidating(false);f.setNamespaceAware(false);f.setExpandEntityReferences(false);
				org.w3c.dom.Document doc=f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(text)));
				org.w3c.dom.Element root=doc.getDocumentElement();
				if(root.getTagName().equals("plist")){for(org.w3c.dom.Node n=root.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof org.w3c.dom.Element)return xmlValue((org.w3c.dom.Element)n);return null;}
				return xmlValue(root);
			}catch(Exception e){return null;}
		}
		try{
			AsciiPlist p=new AsciiPlist(text);p.skip();
			if(p.at<p.s.length()&&p.s.charAt(p.at)!='{'&&p.s.charAt(p.at)!='('&&p.s.charAt(p.at)!='<'){ // a .strings file: key = value; pairs without braces
				Map<String,Object> m=new java.util.LinkedHashMap<String,Object>();
				while(true){p.skip();if(p.at>=p.s.length())break;String k=p.string();p.skip();Object v=k;if(p.peek()=='='){p.at++;v=p.value();}p.skip();if(p.peek()==';')p.at++;m.put(k,v);}
				return m;
			}
			Object v=p.value();p.skip();return p.at>=p.s.length()?v:v;
		}catch(RuntimeException e){return null;}
	}
	private static Object xmlValue(org.w3c.dom.Element e){
		String tag=e.getTagName(),body=e.getTextContent();
		switch(tag){
			case "string":return body;
			case "integer":try{return Long.parseLong(body.trim());}catch(NumberFormatException x){return new java.math.BigInteger(body.trim()).longValue();}
			case "real":return Double.parseDouble(body.trim());
			case "true":return Boolean.TRUE;case "false":return Boolean.FALSE;
			case "data":return java.util.Base64.getMimeDecoder().decode(body.replaceAll("\\s",""));
			case "date":try{return new PlistDate(java.time.Instant.parse(body.trim()).toEpochMilli()/1000.0-SINCE_1970);}catch(Exception x){return new PlistDate(0);}
			case "array":{List<Object> l=new ArrayList<Object>();for(org.w3c.dom.Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof org.w3c.dom.Element)l.add(xmlValue((org.w3c.dom.Element)n));return l;}
			case "dict":{Map<String,Object> m=new java.util.LinkedHashMap<String,Object>();String key=null;
				for(org.w3c.dom.Node n=e.getFirstChild();n!=null;n=n.getNextSibling()){if(!(n instanceof org.w3c.dom.Element))continue;org.w3c.dom.Element x=(org.w3c.dom.Element)n;if(x.getTagName().equals("key"))key=x.getTextContent();else if(key!=null){m.put(key,xmlValue(x));key=null;}}
				return m;}
			default:return body;
		}
	}
	/** The old ASCII (OpenStep) property list syntax. */
	private static final class AsciiPlist {
		final String s;int at;AsciiPlist(String s){this.s=s;}
		char peek(){return at<s.length()?s.charAt(at):0;}
		void skip(){while(at<s.length()){char ch=s.charAt(at);if(Character.isWhitespace(ch)){at++;continue;}if(s.startsWith("//",at)){while(at<s.length()&&s.charAt(at)!='\n')at++;continue;}if(s.startsWith("/*",at)){int e=s.indexOf("*/",at+2);at=e<0?s.length():e+2;continue;}break;}}
		Object value(){
			skip();char ch=peek();
			if(ch=='{'){at++;Map<String,Object> m=new java.util.LinkedHashMap<String,Object>();while(true){skip();if(peek()=='}'){at++;return m;}String k=string();skip();if(peek()!='=')throw new IllegalArgumentException("=");at++;Object v=value();skip();if(peek()==';')at++;m.put(k,v);}}
			if(ch=='('){at++;List<Object> l=new ArrayList<Object>();while(true){skip();if(peek()==')'){at++;return l;}l.add(value());skip();if(peek()==',')at++;}}
			if(ch=='<'){at++;java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();StringBuilder hex=new StringBuilder();while(at<s.length()&&s.charAt(at)!='>'){char h=s.charAt(at++);if(Character.digit(h,16)>=0)hex.append(h);}at++;for(int i=0;i+1<hex.length();i+=2)b.write(Integer.parseInt(hex.substring(i,i+2),16));return b.toByteArray();}
			return string();
		}
		String string(){
			skip();if(peek()=='"'){at++;StringBuilder sb=new StringBuilder();while(at<s.length()){char ch=s.charAt(at++);if(ch=='"')break;if(ch=='\\'&&at<s.length()){char e=s.charAt(at++);switch(e){case 'n':sb.append('\n');break;case 't':sb.append('\t');break;case 'r':sb.append('\r');break;case 'U':case 'u':{sb.append((char)Integer.parseInt(s.substring(at,at+4),16));at+=4;break;}
				default:if(e>='0'&&e<='7'){int v=e-'0';for(int k=0;k<2&&at<s.length()&&s.charAt(at)>='0'&&s.charAt(at)<='7';k++)v=v*8+s.charAt(at++)-'0';sb.append((char)v);}else sb.append(e);}}else sb.append(ch);}return sb.toString();}
			int start=at;while(at<s.length()&&(Character.isLetterOrDigit(s.charAt(at))||"_$+/:.-".indexOf(s.charAt(at))>=0))at++;
			if(at==start)throw new IllegalArgumentException("string expected at "+at);return s.substring(start,at);
		}
	}
	/** The XML form of a Java-side property list. */
	static byte[] writePropertyList(Object value){
		StringBuilder sb=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n");
		xml(sb,value,0);sb.append("</plist>\n");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}
	@SuppressWarnings("unchecked")
	private static void xml(StringBuilder sb,Object v,int depth){
		String in="\t".repeat(depth);
		if(v instanceof Map){sb.append(in).append("<dict>\n");for(Map.Entry<String,Object> e:((Map<String,Object>)v).entrySet()){sb.append(in).append("\t<key>").append(escapeXml(e.getKey())).append("</key>\n");xml(sb,e.getValue(),depth+1);}sb.append(in).append("</dict>\n");}
		else if(v instanceof List){sb.append(in).append("<array>\n");for(Object o:(List<Object>)v)xml(sb,o,depth+1);sb.append(in).append("</array>\n");}
		else if(v instanceof Boolean)sb.append(in).append((Boolean)v?"<true/>\n":"<false/>\n");
		else if(v instanceof Double||v instanceof Float)sb.append(in).append("<real>").append(v).append("</real>\n");
		else if(v instanceof Number)sb.append(in).append("<integer>").append(((Number)v).longValue()).append("</integer>\n");
		else if(v instanceof byte[])sb.append(in).append("<data>\n").append(in).append(java.util.Base64.getEncoder().encodeToString((byte[])v)).append("\n").append(in).append("</data>\n");
		else if(v instanceof PlistDate)sb.append(in).append("<date>").append(java.time.Instant.ofEpochMilli((long)((((PlistDate)v).at+SINCE_1970)*1000)).toString().replaceAll("\\.\\d+Z$","Z")).append("</date>\n");
		else sb.append(in).append("<string>").append(escapeXml(String.valueOf(v))).append("</string>\n");
	}
	private static String escapeXml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
	/** A Java-side property list as new CF objects (one reference); {@code mutable} containers when asked. */
	@SuppressWarnings("unchecked")
	int toCF(Object v,boolean mutable) throws M68kException {
		if(v instanceof String)return mutable?newMutableString((String)v):newString((String)v);
		if(v instanceof Boolean)return bool((Boolean)v);
		if(v instanceof Double||v instanceof Float){int p=rt.allocate(8,4);writeDouble(p,((Number)v).doubleValue());int n=newNumber(13,p);memory.free(p);return n;}
		if(v instanceof Number){long x=((Number)v).longValue();int p=rt.allocate(8,4);int n;if(x==(int)x){memory.writeLong(p,(int)x);n=newNumber(3,p);}else{memory.writeLong(p,(int)(x>>>32));memory.writeLong(p+4,(int)x);n=newNumber(4,p);}memory.free(p);return n;}
		if(v instanceof byte[])return newData((byte[])v,mutable);
		if(v instanceof PlistDate)return newDate(((PlistDate)v).at);
		if(v instanceof List){CFArray a=new CFArray(typeCallbacks(),mutable);int p=register(a);for(Object o:(List<Object>)v){int x=toCF(o,mutable);a.add(a.values.size(),x);release(x);}return p;}
		if(v instanceof Map){CFDictionary d=new CFDictionary(TYPE_DICTIONARY,typeCallbacks(),typeCallbacks(),mutable);int p=register(d);for(Map.Entry<String,Object> e:((Map<String,Object>)v).entrySet()){int k=newString(e.getKey()),x=toCF(e.getValue(),mutable);d.put(k,x,2);release(k);release(x);}return p;}
		return newString(String.valueOf(v));
	}
	/** A CF property list object as a Java-side value (null when it is not one). */
	Object toJava(int ref) throws M68kException {
		CFObject o=object(ref);
		if(o instanceof CFString)return ((CFString)o).text.toString();
		if(o instanceof CFBoolean)return ((CFBoolean)o).value;
		if(o instanceof CFNumber){CFNumber n=(CFNumber)o;return n.isFloat()?(Object)n.doubleValue():(Object)n.longValue();}
		if(o instanceof CFData)return ((CFData)o).bytes(this);
		if(o instanceof CFDate)return new PlistDate(((CFDate)o).at);
		if(o instanceof CFArray){List<Object> l=new ArrayList<Object>();for(int v:((CFArray)o).values){Object x=toJava(v);if(x==null)return null;l.add(x);}return l;}
		if(o instanceof CFDictionary&&((CFDictionary)o).kind==TYPE_DICTIONARY){Map<String,Object> m=new java.util.LinkedHashMap<String,Object>();for(int[] e:((CFDictionary)o).entries){String k=text(e[0]);Object x=toJava(e[1]);if(k==null||x==null)return null;m.put(k,x);}return m;}
		return null;
	}

	private void definePropertyLists(PpcLibraries l){
		l.register(null,"CFPropertyListCreateFromXMLData",(r,c)->{ // (alloc, xmlData, mutabilityOption, *errorString)
			CFData d=object(arg(1),CFData.class);Object v=d==null?null:parsePropertyList(d.bytes(this));
			if(v==null){if(arg(3)!=0)memory.writeLong(arg(3),newString("Unable to parse the property list"));ret(0);return;}
			if(arg(3)!=0)memory.writeLong(arg(3),0);ret(toCF(v,arg(2)!=0));});
		l.register(null,"CFPropertyListCreateXMLData",(r,c)->{Object v=toJava(arg(1));ret(v==null?0:newData(writePropertyList(v),false));});
		l.register(null,"CFPropertyListCreateDeepCopy",(r,c)->{Object v=toJava(arg(1));ret(v==null?0:toCF(v,arg(2)!=0));});
		l.register(null,"CFPropertyListIsValid",(r,c)->ret(toJava(arg(0))!=null?1:0));
	}

	// ------------------------------------------------------------------ character sets

	/** A set of UTF-16 code units: a predefined predicate, or explicit bits; inverted or not. */
	static final class CFCharacterSet extends CFObject {
		java.util.BitSet bits;int predefined;boolean inverted;final boolean mutable;
		CFCharacterSet(boolean mutable){super(TYPE_CHARACTER_SET);this.mutable=mutable;}
		boolean contains(int ch){boolean in=bits!=null?bits.get(ch&0xffff):predefinedMember(predefined,ch);return in!=inverted;}
		/** Makes the set explicit (for the mutating calls). */
		void materialize(){if(bits!=null&&!inverted)return;java.util.BitSet b=new java.util.BitSet(65536);for(int i=0;i<65536;i++)if(contains(i))b.set(i);bits=b;inverted=false;predefined=0;}
	}
	/** Membership in the predefined sets (kCFCharacterSetControl 1 ... kCFCharacterSetCapitalizedLetter 13). */
	static boolean predefinedMember(int set,int ch){
		switch(set){
			case 1:return Character.getType(ch)==Character.CONTROL||Character.getType(ch)==Character.FORMAT;
			case 2:return ch==' '||ch=='\t'||Character.getType(ch)==Character.SPACE_SEPARATOR;
			case 3:return Character.isWhitespace(ch)||Character.isSpaceChar(ch)||ch==0x85;
			case 4:return Character.isDigit(ch);
			case 5:return Character.isLetter(ch)||Character.getType(ch)==Character.NON_SPACING_MARK||Character.getType(ch)==Character.COMBINING_SPACING_MARK||Character.getType(ch)==Character.ENCLOSING_MARK;
			case 6:return Character.isLowerCase(ch);
			case 7:return Character.isUpperCase(ch)||Character.isTitleCase(ch);
			case 8:{int t=Character.getType(ch);return t==Character.NON_SPACING_MARK||t==Character.COMBINING_SPACING_MARK||t==Character.ENCLOSING_MARK;}
			case 9:return !java.text.Normalizer.normalize(String.valueOf((char)ch),java.text.Normalizer.Form.NFD).equals(String.valueOf((char)ch));
			case 10:return Character.isLetterOrDigit(ch)||predefinedMember(8,ch);
			case 11:{int t=Character.getType(ch);return t==Character.CONNECTOR_PUNCTUATION||t==Character.DASH_PUNCTUATION||t==Character.START_PUNCTUATION||t==Character.END_PUNCTUATION||t==Character.INITIAL_QUOTE_PUNCTUATION||t==Character.FINAL_QUOTE_PUNCTUATION||t==Character.OTHER_PUNCTUATION;}
			case 12:return !Character.isDefined(ch)||(ch>=0xfffe);
			case 13:return Character.isTitleCase(ch);
			default:return false;
		}
	}
	private CFCharacterSet charSet(int ref){return object(ref,CFCharacterSet.class);}
	private final Map<Integer,Integer> predefinedSets=new HashMap<Integer,Integer>();

	private void defineCharacterSets(PpcLibraries l){
		l.register(null,"CFCharacterSetGetTypeID",(r,c)->ret(TYPE_CHARACTER_SET));
		l.register(null,"CFCharacterSetGetPredefined",(r,c)->{int id=arg(0);Integer v=predefinedSets.get(id);if(v==null){CFCharacterSet s=new CFCharacterSet(false);s.predefined=id;s.constant=true;v=register(s);predefinedSets.put(id,v);}ret(v);});
		l.register(null,"CFCharacterSetCreateWithCharactersInRange",(r,c)->{CFCharacterSet s=new CFCharacterSet(false);s.bits=new java.util.BitSet(65536);int a=Math.max(0,arg(1)),b=Math.min(65536,arg(1)+arg(2));if(b>a)s.bits.set(a,b);ret(register(s));});
		l.register(null,"CFCharacterSetCreateWithCharactersInString",(r,c)->{CFCharacterSet s=new CFCharacterSet(false);s.bits=new java.util.BitSet(65536);String t=text(arg(1));if(t!=null)for(char ch:t.toCharArray())s.bits.set(ch);ret(register(s));});
		l.register(null,"CFCharacterSetCreateWithBitmapRepresentation",(r,c)->{CFCharacterSet s=new CFCharacterSet(false);s.bits=new java.util.BitSet(65536);CFData d=object(arg(1),CFData.class);if(d!=null){byte[] b=d.bytes(this);for(int i=0;i<Math.min(b.length,8192)*8;i++)if((b[i>>3]>>(i&7)&1)!=0)s.bits.set(i);}ret(register(s));});
		l.register(null,"CFCharacterSetCreateBitmapRepresentation",(r,c)->{CFCharacterSet s=charSet(arg(1));byte[] b=new byte[8192];if(s!=null)for(int i=0;i<65536;i++)if(s.contains(i))b[i>>3]|=1<<(i&7);ret(newData(b,false));});
		l.register(null,"CFCharacterSetCreateMutable",(r,c)->{CFCharacterSet s=new CFCharacterSet(true);s.bits=new java.util.BitSet(65536);ret(register(s));});
		l.register(null,"CFCharacterSetCreateMutableCopy",(r,c)->{CFCharacterSet o=charSet(arg(1));CFCharacterSet s=new CFCharacterSet(true);s.bits=new java.util.BitSet(65536);if(o!=null)for(int i=0;i<65536;i++)if(o.contains(i))s.bits.set(i);ret(register(s));});
		l.register(null,"CFCharacterSetCreateCopy",(r,c)->{CFCharacterSet o=charSet(arg(1));CFCharacterSet s=new CFCharacterSet(false);s.bits=new java.util.BitSet(65536);if(o!=null)for(int i=0;i<65536;i++)if(o.contains(i))s.bits.set(i);ret(register(s));});
		l.register(null,"CFCharacterSetCreateInvertedSet",(r,c)->{CFCharacterSet o=charSet(arg(1));CFCharacterSet s=new CFCharacterSet(false);s.bits=new java.util.BitSet(65536);if(o!=null)for(int i=0;i<65536;i++)if(!o.contains(i))s.bits.set(i);ret(register(s));});
		l.register(null,"CFCharacterSetIsCharacterMember",(r,c)->{CFCharacterSet s=charSet(arg(0));ret(s!=null&&s.contains(arg(1)&0xffff)?1:0);});
		l.register(null,"CFCharacterSetAddCharactersInRange",(r,c)->{CFCharacterSet s=charSet(arg(0));if(s==null||!s.mutable)return;s.materialize();int a=Math.max(0,arg(1)),b=Math.min(65536,arg(1)+arg(2));if(b>a)s.bits.set(a,b);});
		l.register(null,"CFCharacterSetRemoveCharactersInRange",(r,c)->{CFCharacterSet s=charSet(arg(0));if(s==null||!s.mutable)return;s.materialize();int a=Math.max(0,arg(1)),b=Math.min(65536,arg(1)+arg(2));if(b>a)s.bits.clear(a,b);});
		l.register(null,"CFCharacterSetAddCharactersInString",(r,c)->{CFCharacterSet s=charSet(arg(0));String t=text(arg(1));if(s==null||!s.mutable||t==null)return;s.materialize();for(char ch:t.toCharArray())s.bits.set(ch);});
		l.register(null,"CFCharacterSetRemoveCharactersInString",(r,c)->{CFCharacterSet s=charSet(arg(0));String t=text(arg(1));if(s==null||!s.mutable||t==null)return;s.materialize();for(char ch:t.toCharArray())s.bits.clear(ch);});
		l.register(null,"CFCharacterSetUnion",(r,c)->{CFCharacterSet s=charSet(arg(0)),o=charSet(arg(1));if(s==null||o==null||!s.mutable)return;s.materialize();for(int i=0;i<65536;i++)if(o.contains(i))s.bits.set(i);});
		l.register(null,"CFCharacterSetIntersect",(r,c)->{CFCharacterSet s=charSet(arg(0)),o=charSet(arg(1));if(s==null||o==null||!s.mutable)return;s.materialize();for(int i=0;i<65536;i++)if(!o.contains(i))s.bits.clear(i);});
		l.register(null,"CFCharacterSetInvert",(r,c)->{CFCharacterSet s=charSet(arg(0));if(s!=null&&s.mutable)s.inverted=!s.inverted;});
		l.register(null,"CFStringFindCharacterFromSet",(r,c)->{ // (string, set, range(2), options, *result)
			String s=text(arg(0));CFCharacterSet set=charSet(arg(1));if(s==null||set==null){ret(0);return;}int[] k=range(arg(2),arg(3),s.length());boolean back=(arg(4)&COMPARE_BACKWARDS)!=0,anchored=(arg(4)&COMPARE_ANCHORED)!=0;
			for(int n=0;n<k[1]-k[0];n++){int i=back?k[1]-1-n:k[0]+n;if(set.contains(s.charAt(i))){writeRange(arg(5),i,1);ret(1);return;}if(anchored)break;}
			ret(0);});
	}

	// ------------------------------------------------------------------ UUIDs, plug-ins, null, string arrays

	static final class CFUUID extends CFObject implements ValueEquality,Describable {
		final byte[] bytes;CFUUID(byte[] bytes){super(TYPE_UUID);this.bytes=bytes;constant=true;} // UUIDs are uniqued and never go away
		@Override public boolean sameValue(PpcCoreFoundation cf,CFObject o){return java.util.Arrays.equals(((CFUUID)o).bytes,bytes);}
		@Override public int valueHash(PpcCoreFoundation cf){return java.util.Arrays.hashCode(bytes);}
		@Override public String description(PpcCoreFoundation cf){return uuidString(bytes);}
	}
	private final Map<String,Integer> uuids=new HashMap<String,Integer>();
	int uuid(byte[] b) throws M68kCpuException {String k=uuidString(b);Integer v=uuids.get(k);if(v==null){v=register(new CFUUID(b.clone()));uuids.put(k,v);}return v;}
	static String uuidString(byte[] b){StringBuilder sb=new StringBuilder();for(int i=0;i<16;i++){if(i==4||i==6||i==8||i==10)sb.append('-');sb.append(String.format("%02X",b[i]&0xff));}return sb.toString();}
	static byte[] uuidBytes(String s){if(s==null)return null;String h=s.replace("-","").trim();if(h.length()!=32)return null;byte[] b=new byte[16];try{for(int i=0;i<16;i++)b[i]=(byte)Integer.parseInt(h.substring(2*i,2*i+2),16);}catch(NumberFormatException e){return null;}return b;}
	/** Registered plug-in factories: factory UUID -> {plug-in (0 = registered by function), function TVector or name}. */
	private final Map<Integer,Object[]> factories=new java.util.LinkedHashMap<Integer,Object[]>();
	private final Map<Integer,List<Integer>> plugInTypes=new HashMap<Integer,List<Integer>>();
	static final class CFPlugIn extends CFObject {final int bundle;CFPlugIn(int bundle){super(TYPE_PLUG_IN);this.bundle=bundle;}}
	/** A plug-in instance: instance data, the factory's name and the function that hands out its interfaces. */
	final class CFPlugInInstance extends CFObject {
		int data,deallocate,factoryName,getInterface;CFPlugInInstance(){super(TYPE_PLUG_IN_INSTANCE);}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {if(deallocate!=0)rt.cpu.call(deallocate,data);if(factoryName!=0)release(factoryName);if(memory.isAllocated(data))memory.free(data);}
	}
	private int nullRef;

	private void defineMisc(PpcLibraries l){
		// null
		l.register(null,"CFNullGetTypeID",(r,c)->ret(TYPE_NULL));
		l.registerData("kCFNull",()->{if(nullRef==0){CFObject n=new CFObject(TYPE_NULL){};n.constant=true;nullRef=register(n);}return variable(nullRef);});
		// string arrays
		l.register(null,"CFStringCreateArrayBySeparatingStrings",(r,c)->{String s=text(arg(1)),sep=text(arg(2));if(s==null||sep==null||sep.isEmpty()){ret(0);return;}
			List<Integer> parts=new ArrayList<Integer>();int from=0;while(true){int i=s.indexOf(sep,from);parts.add(newString(i<0?s.substring(from):s.substring(from,i)));if(i<0)break;from=i+sep.length();}
			int a=newObjectArray(parts);for(int p:parts)release(p);ret(a);});
		l.register(null,"CFStringCreateByCombiningStrings",(r,c)->{CFArray a=array(arg(1));String sep=text(arg(2));StringBuilder sb=new StringBuilder();if(a!=null)for(int i=0;i<a.values.size();i++){if(i>0&&sep!=null)sb.append(sep);String t=text(a.values.get(i));if(t!=null)sb.append(t);}ret(newString(sb.toString()));});
		// UUIDs
		l.register(null,"CFUUIDGetTypeID",(r,c)->ret(TYPE_UUID));
		l.register(null,"CFUUIDCreate",(r,c)->{java.util.UUID u=java.util.UUID.randomUUID();java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(16);b.putLong(u.getMostSignificantBits());b.putLong(u.getLeastSignificantBits());ret(uuid(b.array()));});
		l.register(null,"CFUUIDCreateWithBytes",(r,c)->{byte[] b=new byte[16];for(int i=0;i<16;i++)b[i]=(byte)arg(1+i);ret(uuid(b));}); // (alloc, 16 byte arguments)
		l.register(null,"CFUUIDGetConstantUUIDWithBytes",(r,c)->{byte[] b=new byte[16];for(int i=0;i<16;i++)b[i]=(byte)arg(1+i);ret(uuid(b));});
		l.register(null,"CFUUIDCreateFromUUIDBytes",(r,c)->{byte[] b=new byte[16];for(int i=0;i<16;i++)b[i]=(byte)(arg(1+i/4)>>>(24-8*(i%4)));ret(uuid(b));}); // CFUUIDBytes by value: 4 slots
		l.register(null,"CFUUIDCreateFromString",(r,c)->{byte[] b=uuidBytes(text(arg(1)));ret(b==null?0:uuid(b));});
		l.register(null,"CFUUIDCreateString",(r,c)->{CFUUID u=object(arg(1),CFUUID.class);ret(u==null?0:newString(uuidString(u.bytes)));});
		l.register(null,"CFUUIDGetUUIDBytes",(r,c)->{CFUUID u=object(arg(1),CFUUID.class);if(u!=null)memory.load(arg(0),u.bytes);ret(arg(0));}); // CFUUIDBytes result by value: hidden pointer
		// plug-ins: a bundle whose Info.plist lists CFPlugInFactories {factory UUID: function name} and CFPlugInTypes {type UUID: [factory UUIDs]}
		l.register(null,"CFPlugInGetTypeID",(r,c)->ret(TYPE_PLUG_IN));
		l.register(null,"CFPlugInCreate",(r,c)->{CFBundle b=bundleOfURL(arg(1));if(b==null){ret(0);return;}if(b.plugIn!=0){ret(retain(b.plugIn));return;}
			CFPlugIn p=new CFPlugIn(b.address);int ref=register(p);b.plugIn=ref;
			CFDictionary f=dictionary(lookup(infoDictionary(b),"CFPlugInFactories"));if(f!=null)for(int[] e:f.entries){byte[] u=uuidBytes(text(e[0]));if(u!=null)factories.put(uuid(u),new Object[]{ref,text(e[1])});}
			CFDictionary t=dictionary(lookup(infoDictionary(b),"CFPlugInTypes"));if(t!=null)for(int[] e:t.entries){byte[] u=uuidBytes(text(e[0]));CFArray fs=array(e[1]);if(u==null||fs==null)continue;List<Integer> list=plugInTypes.computeIfAbsent(uuid(u),k->new ArrayList<Integer>());for(int x:fs.values){byte[] fu=uuidBytes(text(x));if(fu!=null&&!list.contains(uuid(fu)))list.add(uuid(fu));}}
			ret(ref);});
		l.register(null,"CFPlugInGetBundle",(r,c)->{CFPlugIn p=object(arg(0),CFPlugIn.class);ret(p==null?0:p.bundle);});
		l.register(null,"CFPlugInSetLoadOnDemand",(r,c)->{});
		l.register(null,"CFPlugInIsLoadOnDemand",(r,c)->ret(1));
		l.register(null,"CFPlugInFindFactoriesForPlugInType",(r,c)->{List<Integer> f=plugInTypes.get(arg(0));ret(newObjectArray(f==null?new ArrayList<Integer>():f));});
		l.register(null,"CFPlugInFindFactoriesForPlugInTypeInPlugIn",(r,c)->{List<Integer> f=plugInTypes.get(arg(0));List<Integer> out=new ArrayList<Integer>();if(f!=null)for(int x:f){Object[] e=factories.get(x);if(e!=null&&(Integer)e[0]==arg(1))out.add(x);}ret(newObjectArray(out));});
		l.register(null,"CFPlugInInstanceCreate",(r,c)->{ // (alloc, factoryUUID, typeUUID): the factory function's result (an interface pointer)
			Object[] e=factories.get(arg(1));if(e==null){ret(0);return;}int fn;
			if(e[1] instanceof Integer)fn=(Integer)e[1];else{CFPlugIn p=object((Integer)e[0],CFPlugIn.class);CFBundle b=p==null?null:bundle(p.bundle);fn=b==null?0:symbol(b,(String)e[1]);}
			ret(fn==0?0:rt.cpu.call(fn,arg(0),arg(2)));});
		l.register(null,"CFPlugInRegisterFactoryFunction",(r,c)->{factories.put(arg(0),new Object[]{0,arg(1)});ret(1);});
		l.register(null,"CFPlugInRegisterFactoryFunctionByName",(r,c)->{factories.put(arg(0),new Object[]{arg(1),text(arg(2))});ret(1);});
		l.register(null,"CFPlugInUnregisterFactory",(r,c)->ret(factories.remove(arg(0))!=null?1:0));
		l.register(null,"CFPlugInRegisterPlugInType",(r,c)->{List<Integer> list=plugInTypes.computeIfAbsent(arg(1),k->new ArrayList<Integer>());if(!list.contains(arg(0)))list.add(arg(0));ret(1);});
		l.register(null,"CFPlugInUnregisterPlugInType",(r,c)->{List<Integer> list=plugInTypes.get(arg(1));ret(list!=null&&list.remove(Integer.valueOf(arg(0)))?1:0);});
		l.register(null,"CFPlugInAddInstanceForFactory",(r,c)->{});
		// plug-in instances: an interface implemented by a factory with instance data of its own
		l.register(null,"CFPlugInInstanceGetTypeID",(r,c)->ret(TYPE_PLUG_IN_INSTANCE));
		l.register(null,"CFPlugInInstanceCreateWithInstanceDataSize",(r,c)->{ // (alloc, instanceDataSize, deallocateInstanceFunction, factoryName, getInterfaceFunction)
			CFPlugInInstance i=new CFPlugInInstance();i.data=r.allocate(Math.max(4,arg(1)),8);i.deallocate=arg(2);i.factoryName=arg(3)==0?0:retain(arg(3));i.getInterface=arg(4);ret(register(i));});
		l.register(null,"CFPlugInInstanceGetInstanceData",(r,c)->{CFPlugInInstance i=object(arg(0),CFPlugInInstance.class);ret(i==null?0:i.data);});
		l.register(null,"CFPlugInInstanceGetFactoryName",(r,c)->{CFPlugInInstance i=object(arg(0),CFPlugInInstance.class);ret(i==null?0:i.factoryName);});
		l.register(null,"CFPlugInInstanceGetInterfaceFunctionTable",(r,c)->{ // (instance, interfaceName, *ftbl): the instance's getInterface function answers
			CFPlugInInstance i=object(arg(0),CFPlugInInstance.class);if(i==null||i.getInterface==0){ret(0);return;}ret(rt.cpu.call(i.getInterface,arg(0),arg(1),arg(2))&0xff);});
		l.register(null,"CFPlugInRemoveInstanceForFactory",(r,c)->{});
	}
	// ------------------------------------------------------------------ trees and XML

	/** A tree node: its context (info and the callbacks to retain, release and describe it), parent and children. */
	final class CFTree extends CFObject implements Describable {
		int context;CFTree parent;final List<CFTree> children=new ArrayList<CFTree>();
		CFTree(){super(TYPE_TREE);}
		int info() throws M68kCpuException {return context==0?0:memory.readLong(context+4);}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {
			for(CFTree c:new ArrayList<CFTree>(children)){c.parent=null;release(c.address);}children.clear();
			releaseInfo(this);if(context!=0&&memory.isAllocated(context))memory.free(context);
		}
		@Override public String description(PpcCoreFoundation cf) throws M68kException {CFXMLNode n=nodeOf(this);return n!=null?"<CFXMLTree "+n.typeCode+" "+n.string+">":"<CFTree "+children.size()+" children>";}
	}
	static final int TYPE_TREE=34,TYPE_XML_NODE=35,TYPE_XML_PARSER=36;
	private CFTree tree(int ref){return object(ref,CFTree.class);}
	/** Copies a CFTreeContext {version, info, retain, release, copyDescription} in, retaining the info. */
	private void setContext(CFTree t,int ctx) throws M68kException {
		releaseInfo(t);
		if(ctx==0){if(t.context!=0)memory.fill(t.context,20,0);return;}
		if(t.context==0)t.context=rt.allocate(20,4);
		memory.copy(ctx,t.context,20);
		int retainFn=memory.readLong(t.context+8),info=memory.readLong(t.context+4);
		if(retainFn!=0&&info!=0)memory.writeLong(t.context+4,retainFn==retainTreeTV?retain(info):rt.cpu.call(retainFn,info));
	}
	private void releaseInfo(CFTree t) throws M68kException {
		if(t.context==0)return;int releaseFn=memory.readLong(t.context+12),info=memory.readLong(t.context+4);
		if(releaseFn!=0&&info!=0){if(releaseFn==releaseTreeTV)release(info);else rt.cpu.call(releaseFn,info);}
		memory.writeLong(t.context+4,0);
	}
	/** The CF-typed tree context CFXMLTrees use: info = the CFXMLNode, retained and released as a CF object. */
	private int retainTreeTV,releaseTreeTV;
	private int cfTreeContext(int node) throws M68kCpuException {
		if(retainTreeTV==0){retainTreeTV=rt.nativeVector("__CFXMLTreeRetain",(r,c)->ret(retain(arg(0))));releaseTreeTV=rt.nativeVector("__CFXMLTreeRelease",(r,c)->release(arg(0)));}
		int ctx=rt.allocate(20,4);memory.writeLong(ctx+4,node);memory.writeLong(ctx+8,retainTreeTV);memory.writeLong(ctx+12,releaseTreeTV);return ctx;
	}
	private void addChild(CFTree parent,CFTree child,int index) throws M68kException {
		if(child.parent!=null){child.parent.children.remove(child);}else retain(child.address); // the parent holds a reference
		child.parent=parent;parent.children.add(Math.max(0,Math.min(index,parent.children.size())),child);
	}
	private void detach(CFTree child) throws M68kException {if(child.parent==null)return;child.parent.children.remove(child);child.parent=null;release(child.address);}

	/** An XML node: type code, string (element name, text ...), version, and its type's info structure in guest memory. */
	final class CFXMLNode extends CFObject {
		final int typeCode,version;final String string;int stringRef,info;final List<Integer> infoObjects=new ArrayList<Integer>();
		CFXMLNode(int typeCode,String string,int version){super(TYPE_XML_NODE);this.typeCode=typeCode;this.string=string==null?"":string;this.version=version;}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {if(stringRef!=0)release(stringRef);for(int o:infoObjects)release(o);if(info!=0&&memory.isAllocated(info))memory.free(info);}
	}
	private CFXMLNode xmlNode(int ref){return object(ref,CFXMLNode.class);}
	private CFXMLNode nodeOf(CFTree t) throws M68kCpuException {return t==null?null:xmlNode(t.info());}
	static final int XML_DOCUMENT=1,XML_ELEMENT=2,XML_ATTRIBUTE=3,XML_PROCESSING_INSTRUCTION=4,XML_COMMENT=5,XML_TEXT=6,XML_CDATA=7,XML_DOCUMENT_TYPE=11,XML_WHITESPACE=12;
	/** A node with its info structure: an element's {attributes, attributeOrder, isEmpty}, a document's {sourceURL, encoding}, a processing instruction's {dataString}. */
	int newXMLNode(int type,String string,int version,Map<String,String> attributes,boolean empty,String data) throws M68kException {
		CFXMLNode n=new CFXMLNode(type,string,version);int p=register(n);
		switch(type){
			case XML_ELEMENT:{
				int dict=newTypeDictionary();List<Integer> order=new ArrayList<Integer>();
				if(attributes!=null)for(Map.Entry<String,String> a:attributes.entrySet()){int v=newString(a.getValue());put(dict,a.getKey(),v);release(v);order.add(newString(a.getKey()));}
				int arr=newObjectArray(order);for(int o:order)release(o);
				n.info=rt.allocate(12,4);memory.writeLong(n.info,dict);memory.writeLong(n.info+4,arr);memory.writeByte(n.info+8,empty?1:0);n.infoObjects.add(dict);n.infoObjects.add(arr);break;}
			case XML_DOCUMENT:{n.info=rt.allocate(8,4);memory.writeLong(n.info+4,ENCODING_UTF8);break;}
			case XML_PROCESSING_INSTRUCTION:{n.info=rt.allocate(4,4);int s=newString(data==null?"":data);memory.writeLong(n.info,s);n.infoObjects.add(s);break;}
			case XML_DOCUMENT_TYPE:{n.info=rt.allocate(8,4);break;}
			default:break;
		}
		return p;
	}
	/** Java-side attributes of an element node, from its info. */
	private Map<String,String> attributesOf(CFXMLNode n) throws M68kException {
		Map<String,String> m=new java.util.LinkedHashMap<String,String>();if(n.typeCode!=XML_ELEMENT||n.info==0)return m;
		CFDictionary d=dictionary(memory.readLong(n.info));CFArray order=array(memory.readLong(n.info+4));
		if(order!=null&&d!=null)for(int k:order.values){int[] e=d.find(k);if(e!=null)m.put(text(k),text(e[1]));}
		else if(d!=null)for(int[] e:d.entries)m.put(text(e[0]),text(e[1]));
		return m;
	}
	/** Parses XML into a DOM (no validation, no external DTD). */
	private static org.w3c.dom.Document parseXML(byte[] data) throws Exception {
		javax.xml.parsers.DocumentBuilderFactory f=XmlSafe.dom();
		f.setValidating(false);f.setNamespaceAware(false);f.setExpandEntityReferences(true);f.setCoalescing(false);
		return f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(data));
	}
	/** Walks a DOM, calling {@code visitor} for every node CF's parser reports (skipping white space when asked). */
	private interface XmlVisitor { Object create(org.w3c.dom.Node n,int type,String string,Map<String,String> attributes,boolean empty,String data) throws M68kException; void add(Object parent,Object child) throws M68kException; void end(Object node,int type) throws M68kException; }
	private void walk(org.w3c.dom.Node n,Object parent,XmlVisitor v,boolean skipWhitespace,boolean skipMeta) throws M68kException {
		for(org.w3c.dom.Node c=n.getFirstChild();c!=null;c=c.getNextSibling()){
			int type;String string;Map<String,String> attrs=null;boolean empty=false;String data=null;
			switch(c.getNodeType()){
				case org.w3c.dom.Node.ELEMENT_NODE:{type=XML_ELEMENT;string=c.getNodeName();attrs=new java.util.LinkedHashMap<String,String>();org.w3c.dom.NamedNodeMap m=c.getAttributes();for(int i=0;i<m.getLength();i++)attrs.put(m.item(i).getNodeName(),m.item(i).getNodeValue());empty=!c.hasChildNodes();break;}
				case org.w3c.dom.Node.TEXT_NODE:{string=c.getNodeValue();type=string.trim().isEmpty()?XML_WHITESPACE:XML_TEXT;if(type==XML_WHITESPACE&&skipWhitespace)continue;break;}
				case org.w3c.dom.Node.CDATA_SECTION_NODE:type=XML_CDATA;string=c.getNodeValue();break;
				case org.w3c.dom.Node.COMMENT_NODE:if(skipMeta)continue;type=XML_COMMENT;string=c.getNodeValue();break;
				case org.w3c.dom.Node.PROCESSING_INSTRUCTION_NODE:if(skipMeta)continue;type=XML_PROCESSING_INSTRUCTION;string=c.getNodeName();data=c.getNodeValue();break;
				case org.w3c.dom.Node.DOCUMENT_TYPE_NODE:if(skipMeta)continue;type=XML_DOCUMENT_TYPE;string=c.getNodeName();break;
				default:continue;
			}
			Object node=v.create(c,type,string,attrs,empty,data);
			if(node==null)continue;
			v.add(parent,node);
			if(type==XML_ELEMENT)walk(c,node,v,skipWhitespace,skipMeta);
			v.end(node,type);
		}
	}
	/** A CFXMLTree from a DOM: a tree whose nodes' infos are CFXMLNodes. */
	private int xmlTree(org.w3c.dom.Document doc,int version,int options,int sourceURL) throws M68kException {
		int docNode=newXMLNode(XML_DOCUMENT,"",version,null,false,null);CFXMLNode dn=xmlNode(docNode);if(sourceURL!=0){memory.writeLong(dn.info,retain(sourceURL));dn.infoObjects.add(sourceURL);}
		CFTree root=new CFTree();register(root);int ctx=cfTreeContext(docNode);setContext(root,ctx);memory.free(ctx);release(docNode);
		walk(doc,root,new XmlVisitor(){
			@Override public Object create(org.w3c.dom.Node n,int type,String string,Map<String,String> attributes,boolean empty,String data) throws M68kException {
				int node=newXMLNode(type,string,version,attributes,empty,data);CFTree t=new CFTree();register(t);int c=cfTreeContext(node);setContext(t,c);memory.free(c);release(node);return t;}
			@Override public void add(Object parent,Object child) throws M68kException {addChild((CFTree)parent,(CFTree)child,Integer.MAX_VALUE);release(((CFTree)child).address);}
			@Override public void end(Object node,int type){}
		},(options&8)!=0,(options&2)!=0);
		return root.address;
	}
	/** Serializes a CFXMLTree. */
	private void writeXML(CFTree t,StringBuilder sb) throws M68kException {
		CFXMLNode n=nodeOf(t);
		if(n==null||n.typeCode==XML_DOCUMENT){for(CFTree c:t.children)writeXML(c,sb);return;}
		switch(n.typeCode){
			case XML_ELEMENT:{sb.append('<').append(n.string);for(Map.Entry<String,String> a:attributesOf(n).entrySet())sb.append(' ').append(a.getKey()).append("=\"").append(escapeXml(a.getValue()).replace("\"","&quot;")).append('"');
				if(t.children.isEmpty()&&memory.readByte(n.info+8)!=0){sb.append("/>");break;}sb.append('>');for(CFTree c:t.children)writeXML(c,sb);sb.append("</").append(n.string).append('>');break;}
			case XML_TEXT:case XML_WHITESPACE:sb.append(escapeXml(n.string));break;
			case XML_CDATA:sb.append("<![CDATA[").append(n.string).append("]]>");break;
			case XML_COMMENT:sb.append("<!--").append(n.string).append("-->");break;
			case XML_PROCESSING_INSTRUCTION:sb.append("<?").append(n.string);{String d=text(memory.readLong(n.info));if(d!=null&&!d.isEmpty())sb.append(' ').append(d);}sb.append("?>");break;
			case XML_DOCUMENT_TYPE:sb.append("<!DOCTYPE ").append(n.string).append('>');break;
			default:break;
		}
	}
	/** An XML parser object: the data, options and the program's callbacks and context. */
	final class CFXMLParser extends CFObject {
		byte[] data;int source,options,version,callbacks,context,status,line;String error;int document;boolean aborted;
		CFXMLParser(){super(TYPE_XML_PARSER);}
		@Override void dispose(PpcCoreFoundation cf) throws M68kException {if(source!=0)release(source);if(context!=0){int rel=memory.readLong(context+12),info=memory.readLong(context+4);if(rel!=0&&info!=0)rt.cpu.call(rel,info);}}
	}
	/** Runs a parser: the DOM walked in document order, each node offered to createXMLStructure, linked with addChild and closed with endXMLStructure. */
	private boolean parse(CFXMLParser p) throws M68kException {
		org.w3c.dom.Document doc;
		try{doc=parseXML(p.data);}catch(Exception e){p.status=-1;p.error=e.getMessage();
			int handleError=memory.readLong(p.callbacks+20);if(handleError!=0)rt.cpu.call(handleError,p.address,5,p.context==0?0:memory.readLong(p.context+4)); // kCFXMLErrorMalformedDocument... (reported, the parse fails anyway)
			return false;}
		final int info=p.context==0?0:memory.readLong(p.context+4);
		final int create=memory.readLong(p.callbacks+4),add=memory.readLong(p.callbacks+8),end=memory.readLong(p.callbacks+12);
		int docNode=newXMLNode(XML_DOCUMENT,"",p.version,null,false,null);
		if(p.source!=0){CFXMLNode dn=xmlNode(docNode);memory.writeLong(dn.info,retain(p.source));dn.infoObjects.add(p.source);}
		int top=create==0?0:rt.cpu.call(create,p.address,docNode,info);release(docNode);
		p.document=top;
		if(top==0)return !p.aborted;
		walk(doc,top,new XmlVisitor(){
			@Override public Object create(org.w3c.dom.Node n,int type,String string,Map<String,String> attributes,boolean empty,String data) throws M68kException {
				if(p.aborted)return null;int node=newXMLNode(type,string,p.version,attributes,empty,data);int s=create==0?0:rt.cpu.call(create,p.address,node,info);release(node);return s==0?null:(Object)s;}
			@Override public void add(Object parent,Object child) throws M68kException {if(!p.aborted&&add!=0)rt.cpu.call(add,p.address,(Integer)parent,(Integer)child,info);}
			@Override public void end(Object node,int type) throws M68kException {if(!p.aborted&&end!=0)rt.cpu.call(end,p.address,(Integer)node,info);}
		},(p.options&8)!=0,(p.options&2)!=0);
		if(end!=0&&!p.aborted)rt.cpu.call(end,p.address,top,info);
		return !p.aborted;
	}
	private byte[] urlData(int url) throws M68kCpuException {CFURL u=url(url);java.io.File f=u==null?null:fileOf(u);if(f==null||!f.isFile()||!Sandbox.allowFile(f,Sandbox.Access.READ,rt.toolbox.name))return null;try{return java.nio.file.Files.readAllBytes(f.toPath());}catch(java.io.IOException e){return null;}}

	private void defineXML(PpcLibraries l){
		// ---- trees
		l.register(null,"CFTreeGetTypeID",(r,c)->ret(TYPE_TREE));
		l.register(null,"CFTreeCreate",(r,c)->{CFTree t=new CFTree();int p=register(t);setContext(t,arg(1));ret(p);});
		l.register(null,"CFTreeGetContext",(r,c)->{CFTree t=tree(arg(0));if(arg(1)==0)return;if(t==null||t.context==0)memory.fill(arg(1),20,0);else memory.copy(t.context,arg(1),20);});
		l.register(null,"CFTreeSetContext",(r,c)->{CFTree t=tree(arg(0));if(t!=null)setContext(t,arg(1));});
		l.register(null,"CFTreeGetParent",(r,c)->{CFTree t=tree(arg(0));ret(t==null||t.parent==null?0:t.parent.address);});
		l.register(null,"CFTreeGetNextSibling",(r,c)->{CFTree t=tree(arg(0));if(t==null||t.parent==null){ret(0);return;}int i=t.parent.children.indexOf(t);ret(i+1<t.parent.children.size()?t.parent.children.get(i+1).address:0);});
		l.register(null,"CFTreeGetFirstChild",(r,c)->{CFTree t=tree(arg(0));ret(t==null||t.children.isEmpty()?0:t.children.get(0).address);});
		l.register(null,"CFTreeGetChildCount",(r,c)->{CFTree t=tree(arg(0));ret(t==null?0:t.children.size());});
		l.register(null,"CFTreeGetChildAtIndex",(r,c)->{CFTree t=tree(arg(0));int i=arg(1);ret(t==null||i<0||i>=t.children.size()?0:t.children.get(i).address);});
		l.register(null,"CFTreeGetChildren",(r,c)->{CFTree t=tree(arg(0));if(t==null||arg(1)==0)return;for(int i=0;i<t.children.size();i++)memory.writeLong(arg(1)+4*i,t.children.get(i).address);});
		l.register(null,"CFTreeApplyFunctionToChildren",(r,c)->{CFTree t=tree(arg(0));if(t==null||arg(1)==0)return;for(CFTree ch:new ArrayList<CFTree>(t.children))rt.cpu.call(arg(1),ch.address,arg(2));});
		l.register(null,"CFTreeFindRoot",(r,c)->{CFTree t=tree(arg(0));while(t!=null&&t.parent!=null)t=t.parent;ret(t==null?0:t.address);});
		l.register(null,"CFTreeInsertSibling",(r,c)->{CFTree t=tree(arg(0)),s=tree(arg(1));if(t==null||s==null||t.parent==null)return;addChild(t.parent,s,t.parent.children.indexOf(t)+1);});
		l.register(null,"CFTreeAppendChild",(r,c)->{CFTree t=tree(arg(0)),s=tree(arg(1));if(t!=null&&s!=null)addChild(t,s,Integer.MAX_VALUE);});
		l.register(null,"CFTreePrependChild",(r,c)->{CFTree t=tree(arg(0)),s=tree(arg(1));if(t!=null&&s!=null)addChild(t,s,0);});
		l.register(null,"CFTreeRemove",(r,c)->{CFTree t=tree(arg(0));if(t!=null)detach(t);});
		l.register(null,"CFTreeRemoveAllChildren",(r,c)->{CFTree t=tree(arg(0));if(t!=null)for(CFTree ch:new ArrayList<CFTree>(t.children))detach(ch);});
		l.register(null,"CFTreeSortChildren",(r,c)->{CFTree t=tree(arg(0));if(t==null)return; // (tree, comparator, context): the comparator sees the children's infos
			List<Integer> infos=new ArrayList<Integer>();Map<Integer,List<CFTree>> byInfo=new HashMap<Integer,List<CFTree>>();for(CFTree ch:t.children){int i=ch.info();infos.add(i);byInfo.computeIfAbsent(i,k->new ArrayList<CFTree>()).add(ch);}
			sort(infos,0,infos.size(),arg(1),arg(2));t.children.clear();for(int i:infos)t.children.add(byInfo.get(i).remove(0));});
		// ---- XML nodes and trees
		l.register(null,"CFXMLNodeGetTypeID",(r,c)->ret(TYPE_XML_NODE));
		l.register(null,"CFXMLNodeCreate",(r,c)->{ // (alloc, xmlType, dataString, additionalInfoPtr, version)
			int type=arg(1),info=arg(3);String s=text(arg(2));Map<String,String> attrs=null;boolean empty=false;String data=null;
			if(type==XML_ELEMENT&&info!=0){attrs=new java.util.LinkedHashMap<String,String>();CFDictionary d=dictionary(memory.readLong(info));CFArray order=array(memory.readLong(info+4));
				if(order!=null&&d!=null)for(int k:order.values){int[] e=d.find(k);if(e!=null)attrs.put(text(k),text(e[1]));}empty=memory.readByte(info+8)!=0;}
			if(type==XML_PROCESSING_INSTRUCTION&&info!=0)data=text(memory.readLong(info));
			ret(newXMLNode(type,s,arg(4),attrs,empty,data));});
		l.register(null,"CFXMLNodeCreateCopy",(r,c)->{CFXMLNode n=xmlNode(arg(1));if(n==null){ret(0);return;}ret(newXMLNode(n.typeCode,n.string,n.version,attributesOf(n),n.typeCode==XML_ELEMENT&&memory.readByte(n.info+8)!=0,n.typeCode==XML_PROCESSING_INSTRUCTION?text(memory.readLong(n.info)):null));});
		l.register(null,"CFXMLNodeGetTypeCode",(r,c)->{CFXMLNode n=xmlNode(arg(0));ret(n==null?0:n.typeCode);});
		l.register(null,"CFXMLNodeGetString",(r,c)->{CFXMLNode n=xmlNode(arg(0));if(n==null){ret(0);return;}if(n.stringRef==0)n.stringRef=newString(n.string);ret(n.stringRef);});
		l.register(null,"CFXMLNodeGetInfoPtr",(r,c)->{CFXMLNode n=xmlNode(arg(0));ret(n==null?0:n.info);});
		l.register(null,"CFXMLNodeGetVersion",(r,c)->{CFXMLNode n=xmlNode(arg(0));ret(n==null?0:n.version);});
		l.register(null,"CFXMLTreeCreateWithNode",(r,c)->{if(xmlNode(arg(1))==null){ret(0);return;}CFTree t=new CFTree();int p=register(t);int ctx=cfTreeContext(arg(1));setContext(t,ctx);memory.free(ctx);ret(p);});
		l.register(null,"CFXMLTreeGetNode",(r,c)->{CFTree t=tree(arg(0));ret(t==null?0:t.info());});
		l.register(null,"CFXMLTreeCreateFromData",(r,c)->{ // (alloc, xmlData, dataSource, parseOptions, versionOfNodes)
			CFData d=object(arg(1),CFData.class);if(d==null){ret(0);return;}try{ret(xmlTree(parseXML(d.bytes(this)),arg(4),arg(3),arg(2)));}catch(M68kException e){throw e;}catch(Exception e){ret(0);}});
		l.register(null,"CFXMLTreeCreateWithDataFromURL",(r,c)->{byte[] b=urlData(arg(1));if(b==null){ret(0);return;}try{ret(xmlTree(parseXML(b),arg(3),arg(2),arg(1)));}catch(M68kException e){throw e;}catch(Exception e){ret(0);}});
		l.register(null,"CFXMLTreeCreateXMLData",(r,c)->{CFTree t=tree(arg(1));if(t==null){ret(0);return;}StringBuilder sb=new StringBuilder();writeXML(t,sb);ret(newData(sb.toString().getBytes(StandardCharsets.UTF_8),false));});
		// ---- the event-driven parser
		l.register(null,"CFXMLParserGetTypeID",(r,c)->ret(TYPE_XML_PARSER));
		l.register(null,"CFXMLParserCreate",(r,c)->{ // (alloc, xmlData, dataSource, parseOptions, versionOfNodes, callBacks, context)
			CFData d=object(arg(1),CFData.class);if(d==null||arg(5)==0){ret(0);return;}CFXMLParser p=new CFXMLParser();p.data=d.bytes(this);p.source=arg(2)==0?0:retain(arg(2));p.options=arg(3);p.version=arg(4);
			p.callbacks=r.allocate(24,4);memory.copy(arg(5),p.callbacks,24);if(arg(6)!=0){p.context=r.allocate(20,4);memory.copy(arg(6),p.context,20);int ret=memory.readLong(p.context+8),info=memory.readLong(p.context+4);if(ret!=0&&info!=0)memory.writeLong(p.context+4,rt.cpu.call(ret,info));}
			ret(register(p));});
		l.register(null,"CFXMLParserCreateWithDataFromURL",(r,c)->{ // (alloc, dataSource, parseOptions, versionOfNodes, callBacks, context)
			byte[] b=urlData(arg(1));if(b==null||arg(4)==0){ret(0);return;}CFXMLParser p=new CFXMLParser();p.data=b;p.source=retain(arg(1));p.options=arg(2);p.version=arg(3);
			p.callbacks=r.allocate(24,4);memory.copy(arg(4),p.callbacks,24);if(arg(5)!=0){p.context=r.allocate(20,4);memory.copy(arg(5),p.context,20);}ret(register(p));});
		l.register(null,"CFXMLParserParse",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p!=null&&parse(p)?1:0);});
		l.register(null,"CFXMLParserGetDocument",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p==null?0:p.document);});
		l.register(null,"CFXMLParserGetStatusCode",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p==null?0:p.status);});
		l.register(null,"CFXMLParserCopyErrorDescription",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p==null||p.error==null?0:newString(p.error));});
		l.register(null,"CFXMLParserGetLocation",(r,c)->ret(0));
		l.register(null,"CFXMLParserGetLineNumber",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p==null?0:p.line);});
		l.register(null,"CFXMLParserAbort",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);if(p==null)return;p.aborted=true;p.status=arg(1);p.error=text(arg(2));});
		l.register(null,"CFXMLParserGetSourceURL",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);ret(p==null?0:p.source);});
		l.register(null,"CFXMLParserGetCallBacks",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);if(p!=null&&arg(1)!=0)memory.copy(p.callbacks,arg(1),24);});
		l.register(null,"CFXMLParserGetContext",(r,c)->{CFXMLParser p=object(arg(0),CFXMLParser.class);if(arg(1)==0)return;if(p==null||p.context==0)memory.fill(arg(1),20,0);else memory.copy(p.context,arg(1),20);});
		// odds and ends of the 3.4 stub libraries
		l.register(null,"__CFRangeMake",(r,c)->{writeRange(arg(0),arg(1),arg(2));ret(arg(0));}); // CFRange by value: hidden result pointer
		l.register(null,"CFURLCreateFromFileSystemRepresentationRelativeToBase",(r,c)->{int p=arg(1),n=arg(2);String path=new String(p==0||n<=0?new byte[0]:memory.readBytes(p,n),StandardCharsets.UTF_8);ret(newURL(urlForPath(path,0,(arg(3)&0xff)!=0),arg(4)));});
		l.register(null,"CFBundleCopyAuxiliaryExecutableURL",(r,c)->{CFBundle b=bundle(arg(0));String n=text(arg(1));if(b==null||n==null){ret(0);return;}for(String d:new String[]{"MacOSClassic","MacOS"}){java.io.File f=new java.io.File(new java.io.File(b.contents(),d),n);if(f.exists()){ret(urlOf(f));return;}}ret(0);});
		l.registerData("_FE_DFL_ENV",()->rt.allocate(4,4)); // fenv_t: all exceptions clear, round to nearest
	}
	// ------------------------------------------------------------------ CarbonLib 1.6 additions

	/** A new mutable CFArray of CF objects (retained), for the helper classes. */
	int newMutableObjectArray(List<Integer> refs) throws M68kException {CFArray a=new CFArray(typeCallbacks(),true);int p=register(a);for(int v:refs)a.add(a.values.size(),v);return p;}
	/** CFPreferencesCopyAppValue for the helper classes ({@code appID} a CFString, kCFPreferencesCurrentApplication or 0). */
	int copyAppPreference(String key,int appID) throws M68kException {return copyPreference(key,applicationID(appID));}

	/**
	 * The Script Manager languages (index = LangCode): ISO abbreviation, the old localization name, ScriptCode, the Mac text
	 * encoding and the default RegionCode (-1 none).
	 */
	private static final Object[][] LANGUAGES={
		{"en","English",0,0,0},{"fr","French",0,0,1},{"de","German",0,0,3},{"it","Italian",0,0,4},{"nl","Dutch",0,0,5},{"sv","Swedish",0,0,7},{"es","Spanish",0,0,8},
		{"da","Danish",0,0,9},{"pt","Portuguese",0,0,10},{"no","Norwegian",0,0,12},{"he","Hebrew",5,5,13},{"ja","Japanese",1,1,14},{"ar","Arabic",4,4,16},{"fi","Finnish",0,0,17},
		{"el","Greek",6,6,20},{"is","Icelandic",0,0x25,21},{"mt","Maltese",0,0,22},{"tr","Turkish",0,0x23,24},{"hr","Croatian",0,0x24,68},{"zh_TW","TChinese",2,2,53},
		{"ur","Urdu",4,4,34},{"hi","Hindi",9,9,33},{"th","Thai",21,21,54},{"ko","Korean",3,3,51},{"lt","Lithuanian",29,29,41},{"pl","Polish",29,29,42},{"hu","Hungarian",29,29,43},
		{"et","Estonian",29,29,44},{"lv","Latvian",29,29,45},{"se","Sami",0,0,46},{"fo","Faroese",0,0x25,47},{"fa","Farsi",4,0x8c,48},{"ru","Russian",7,7,49},{"zh_CN","SChinese",25,25,52},
		{"nl_BE","Flemish",0,0,6},{"ga","Irish",0,0,50},{"sq","Albanian",0,0,-1},{"ro","Romanian",0,0x26,39},{"cs","Czech",29,29,56},{"sk","Slovak",29,29,57},{"sl","Slovenian",0,0x24,66},
		{"yi","Yiddish",5,5,-1},{"sr","Serbian",7,7,65},{"mk","Macedonian",7,7,67},{"bg","Bulgarian",7,7,72},{"uk","Ukrainian",7,0x98,62}};
	/** RegionCode -> locale (Script.h's verXXX comments); -1-free indices only. */
	private static final Map<Integer,String> REGIONS=new HashMap<Integer,String>();
	static{String[] r={"0 en_US","1 fr_FR","2 en_GB","3 de_DE","4 it_IT","5 nl_NL","6 nl_BE","7 sv_SE","8 es_ES","9 da_DK","10 pt_PT","11 fr_CA","12 no_NO","13 he_IL","14 ja_JP","15 en_AU",
		"16 ar","17 fi_FI","18 fr_CH","19 de_CH","20 el_GR","21 is_IS","22 mt_MT","24 tr_TR","33 hi_IN","34 ur_PK","36 it_CH","39 ro_RO","41 lt_LT","42 pl_PL","43 hu_HU","44 et_EE",
		"45 lv_LV","46 se","47 fo_FO","48 fa_IR","49 ru_RU","50 ga_IE","51 ko_KR","52 zh_CN","53 zh_TW","54 th_TH","56 cs_CZ","57 sk_SK","62 uk_UA","65 sr_YU","66 sl_SI","67 mk_MK",
		"68 hr_HR","71 pt_BR","72 bg_BG"};
		for(String s:r){int sp=s.indexOf(' ');REGIONS.put(Integer.parseInt(s.substring(0,sp)),s.substring(sp+1));}}
	/** The language of a localization name ("English", "en", "en_US", "en-US", "zh_TW" ...), -1 when unknown. */
	private static int languageOf(String name){
		String n=name.replace('-','_');
		for(int i=0;i<LANGUAGES.length;i++)if(n.equalsIgnoreCase((String)LANGUAGES[i][1]))return i;
		if(n.equalsIgnoreCase("Chinese"))return 19; // the old name of Traditional Chinese localizations
		for(int i=0;i<LANGUAGES.length;i++)if(n.equalsIgnoreCase((String)LANGUAGES[i][0]))return i;
		String lang=n.contains("_")?n.substring(0,n.indexOf('_')):n;String rest=n.length()>lang.length()?n.substring(lang.length()+1):"";
		if(lang.equalsIgnoreCase("zh"))return rest.equalsIgnoreCase("CN")||rest.equalsIgnoreCase("SG")||rest.startsWith("Hans")?33:19;
		if(lang.equalsIgnoreCase("iw"))return 10;if(lang.equalsIgnoreCase("nb"))return 9;
		for(int i=0;i<LANGUAGES.length;i++)if(lang.equalsIgnoreCase((String)LANGUAGES[i][0]))return i;
		return -1;
	}
	/** The region of a localization name with a country part ("en_GB"), else the language's own; -1 when none. */
	private static int regionOf(String name,int language){
		String n=name.replace('-','_');
		for(Map.Entry<Integer,String> e:REGIONS.entrySet())if(e.getValue().equalsIgnoreCase(n))return e.getKey();
		return language>=0?(Integer)LANGUAGES[language][4]:-1;
	}
	/** The language a script or Mac text encoding stands for (Roman: English), -1 when none. */
	private static int languageOfEncoding(int script,int encoding){
		for(int pass=0;pass<2;pass++){int want=pass==0?encoding:script;if(want<0)continue;for(int i=0;i<LANGUAGES.length;i++)if((Integer)LANGUAGES[i][pass==0?3:2]==want)return i;}
		return -1;
	}
	/** CFStringGetMostCompatibleMacStringEncoding: the Mac script encoding closest to {@code e} (TextEncoding numbers); Unicode for the Unicode forms. */
	static int mostCompatibleMacEncoding(int e){
		int v=e&0xffff;
		if((v&0xff00)==0)return v;                                  // a Mac encoding already
		if((v&0x0f00)==0x0100)return ENCODING_UNICODE;              // UTF-16, UTF-8, UTF-32 ...
		switch(v){
			case 0x0201:case 0x0203:case 0x020a:case 0x020e:case 0x020f:case 0x0500:case 0x0400:case 0x0410:case 0x0415:case 0x0418:case 0x041a:
			case 0x0600:case 0x0a04:case 0x0c01:case 0x0c02:return 0;        // Latin-1/3/6/8/9, cp1252, DOS Latin US/1/Portuguese/Canadian French/Nordic, ASCII, EBCDIC: Mac Roman
			case 0x0202:case 0x0204:case 0x020d:case 0x0501:case 0x0507:case 0x0406:case 0x0412:return 29; // Latin-2/4/7, cp1250, cp1257, DOS Baltic/Latin-2: Central European
			case 0x0205:case 0x0502:case 0x0413:case 0x041b:case 0x0a02:return 7; // ISO Cyrillic, cp1251, DOS Cyrillic/Russian, KOI8-R
			case 0x0a08:return 0x98;                                       // KOI8-U: Mac Ukrainian
			case 0x0206:case 0x0506:case 0x0419:return 4;                  // Arabic
			case 0x0207:case 0x0503:case 0x0405:case 0x0411:case 0x041c:return 6; // Greek
			case 0x0208:case 0x0505:case 0x0417:return 5;                  // Hebrew
			case 0x0209:case 0x0504:case 0x0414:return 0x23;               // Latin-5, cp1254, DOS Turkish: Mac Turkish
			case 0x0416:return 0x25;                                       // DOS Icelandic: Mac Icelandic
			case 0x0210:return 0x26;                                       // Latin-10: Mac Romanian
			case 0x020b:case 0x041d:return 21;                             // Thai
			case 0x0508:case 0x0a07:return 30;                             // Vietnamese
			case 0x0420:case 0x0820:case 0x0821:case 0x0822:case 0x0823:case 0x0920:case 0x0a01:
			case 0x0620:case 0x0621:case 0x0622:case 0x0623:case 0x0624:case 0x0628:return 1; // DOS Japanese, ISO-2022-JP, EUC-JP, Shift-JIS, JIS X0201/0208/0212: Mac Japanese
			case 0x0421:case 0x0830:case 0x0831:case 0x0930:case 0x0a05:case 0x0630:case 0x0631:case 0x0632:return 25; // GB family, HZ: Mac Simplified Chinese
			case 0x0423:case 0x0931:case 0x0a03:case 0x0a06:case 0x0a09:case 0x0651:return 2; // Big5 family, EUC-TW, CNS 11643: Mac Traditional Chinese
			case 0x0422:case 0x0840:case 0x0940:case 0x0510:case 0x0640:case 0x0641:return 3; // DOS Korean, ISO-2022-KR, EUC-KR, Johab, KS C 5601: Mac Korean
			default:return ENCODING_INVALID;
		}
	}

	private void defineCarbon16(PpcLibraries l){
		l.register(null,"CFBundleGetDataPointersForNames",(r,c)->{CFBundle b=bundle(arg(0));CFArray names=array(arg(1));if(names==null||arg(2)==0)return;for(int i=0;i<names.values.size();i++)memory.writeLong(arg(2)+4*i,b==null?0:symbol(b,text(names.values.get(i))));});
		l.register(null,"CFStringGetMostCompatibleMacStringEncoding",(r,c)->ret(mostCompatibleMacEncoding(arg(0))));
		// CFBundlePriv.h: localization names <-> Script Manager codes
		l.register(null,"CFBundleGetLocalizationInfoForLocalization",(r,c)->{ // (name or NULL, *languageCode, *regionCode, *scriptCode, *stringEncoding) -> Boolean
			String name=arg(0)==0?(rt.toolbox.encoding==StackTextEncoding.JAPANESE?"ja_JP":"en_US"):text(arg(0)); // NULL: the system's own localization
			int lang=name==null?-1:languageOf(name),region=name==null?-1:regionOf(name,lang);if(lang<0&&region>=0)lang=languageOf(REGIONS.get(region));
			int script=lang<0?-1:(Integer)LANGUAGES[lang][2],encoding=lang<0?ENCODING_INVALID:(Integer)LANGUAGES[lang][3];
			if(arg(1)!=0)memory.writeLong(arg(1),lang);if(arg(2)!=0)memory.writeLong(arg(2),region);if(arg(3)!=0)memory.writeLong(arg(3),script);if(arg(4)!=0)memory.writeLong(arg(4),encoding);
			ret(lang>=0||region>=0?1:0);});
		l.register(null,"CFBundleCopyLocalizationForLocalizationInfo",(r,c)->{ // (languageCode, regionCode, scriptCode, stringEncoding) -> a new name, the region's locale first
			int lang=arg(0),region=arg(1),script=arg(2),encoding=arg(3);String name=REGIONS.get(region);
			if(name==null&&lang>=0&&lang<LANGUAGES.length)name=(String)LANGUAGES[lang][0];
			if(name==null){int l2=languageOfEncoding(script,encoding==ENCODING_INVALID?-1:encoding);if(l2>=0)name=(String)LANGUAGES[l2][0];}
			ret(name==null?0:newString(name));});
		// the user: Mac OS 9 has one, the owner Sharing Setup names ('STR ' -16096); the startup disk is the home
		l.register(null,"CFGetUserName",(r,c)->{if(userName==0){userName=newString(ownerName());object(userName).constant=true;}ret(userName);}); // the Get rule
		l.register(null,"CFCopyHomeDirectoryURLForUser",(r,c)->{String who=arg(0)==0?null:text(arg(0));
			if(who!=null&&!who.equals(ownerName())){ret(0);return;} // no other users on this Mac
			java.io.File home=rt.toolbox.files.systemFolder.getParentFile();ret(home==null?0:newURL(fileURLString(home,true),0));});
		l.register(null,"CFCopySearchPathForDirectoriesInDomains",(r,c)->{ // (CFSearchPathDirectory, CFSearchPathDomainMask, expandTilde): the Mac OS 9 folders that answer
			int dir=arg(0),domains=arg(1);List<Integer> urls=new ArrayList<Integer>();List<java.io.File> found=new ArrayList<java.io.File>();
			if((dir==1||dir==100)&&(domains&2)!=0)found.add(rt.toolbox.files.specialFolder(0x61707073,false)); // kCFApplicationDirectory, kCFAllApplicationsDirectory: local "Applications"
			if((dir==9)&&(domains&1)!=0)found.add(rt.toolbox.files.specialFolder(0x646f6373,false));             // kCFDocumentDirectory: the user's Documents
			if((dir==5||dir==101)&&(domains&8)!=0)found.add(rt.toolbox.files.systemFolder);                     // kCFLibraryDirectory, kCFAllLibrariesDirectory: the System Folder of the system domain
			for(java.io.File f:found)if(f!=null&&f.isDirectory())urls.add(newURL(fileURLString(f,true),0));
			int a=newObjectArray(urls);for(int u:urls)release(u);ret(a);});
	}
	private int userName;
	/** The owner's name, as Sharing Setup keeps it in the System file ('STR ' -16096); "" without one. */
	private String ownerName() throws M68kException {
		int h=rt.toolbox("GetString",-16096);int p=h==0?0:memory.readLong(h);if(p==0)return "";
		byte[] b=rt.toolbox.readPascalBytes(p);return decode(text().systemEncoding(),java.util.Arrays.copyOfRange(b,1,b.length));
	}

}
