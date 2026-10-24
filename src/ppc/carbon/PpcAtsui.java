import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ATSUI (ATSUnicode.h), the Font Manager's font access (the FM calls of Fonts.h) and ATS for fonts (ATSFont.h) for
 * PowerPC programs.
 * <p>
 * Styles keep every attribute as ATSUI does: the raw bytes of each tag set, font features and variations beside them.
 * Text layouts hold the program's UniChar text (a pointer or a handle), a style per character, layout and line
 * controls and soft line breaks. Text is measured and drawn with QuickDraw's text calls in the program's encoding, one
 * style run at a time, so it looks like the program's other text: an ATSUFontID is a FOND family number (with an
 * intrinsic style in bits 16-23, see {@link #fontOf}), the same number an FMFont is here, so fonts from the Font
 * Manager, GetFNum and ATSUI mix freely (an ATSFontRef is that number too, an ATSFontFamilyRef the family number). Line width, flush and justification factors and truncation apply when a line is
 * laid out, and tab stops place tabs; rotation and bidirectional text do not exist here. Glyph info, glyph metrics and
 * outlines come from the host font behind a style (see {@link #glyphFont}); ATSUI's private glyph-vector layer is absent.
 * <p>
 * The font list is what 68K programs see (the classic families of {@link Fonts68k} and the suitcases of the Fonts
 * folder, {@link InstalledFonts68k}), plus suitcases a program activates with FMActivateFonts. Names, variations,
 * features and tracking come from a family's 'sfnt' tables when it has one; host-substituted families get synthesized
 * Macintosh names and no tables.
 */
final class PpcAtsui {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	private PpcTextServices text;
	PpcAtsui(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}
	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}

	static final int NO_ERR=0,PARAM_ERR=-50,FNF_ERR=-43,
		INVALID_LAYOUT=-8790,INVALID_STYLE=-8791,INVALID_RANGE=-8792,INVALID_FONT=-8796,INVALID_VALUE=-8797,INVALID_SIZE=-8798,
		INVALID_TAG=-8799,INVALID_CACHE=-8800,NOT_SET=-8801,LINE_BREAK_IN_WORD=-8808,NO_CORRESPONDING_FONT=-8795,
		INVALID_FALLBACKS=-8810,UNSUPPORTED_STREAM=-8811,BAD_STREAM=-8812,BUFFER_TOO_SMALL=-8813,
		FM_ITERATION_COMPLETED=-980,FM_INVALID_FAMILY=-981,FM_INVALID_FONT=-982,FM_TABLE_ACCESS=-984,FM_CONTAINER_ACCESS=-985,FONT_NOT_OUTLINE=-32615;
	/** kATSUFromTextBeginning, kATSUToTextEnd, kATSUClearAll, kATSUUseGrafPortPenLoc (all $FFFFFFFF). */
	static final int ALL=-1;
	static final int USE_LINE_CONTROL_WIDTH=0x7fffffff,FRACT_ONE=0x40000000;
	private static final int SFNT=0x73666e74,FOND=0x464f4e44,USTL=0x7573746c,TRUE_TYPE=0x74727565,BITMAP=0x6269746d;
	private static final int FONT_TAG=261,SIZE_TAG=262,COLOR_TAG=263;

	// ------------------------------------------------------------------ objects

	/** The host side of each ATSUI object, by the guest block the program holds as its reference. */
	private final Map<Integer,Object> objects=new HashMap<Integer,Object>();
	private int newObject(Object o) throws M68kCpuException {int p=rt.allocate(8,4);objects.put(p,o);return p;}
	private void dispose(int ref){if(objects.remove(ref)!=null)memory.free(ref);}
	Style style(int ref){Object o=objects.get(ref);return o instanceof Style?(Style)o:null;}
	private Layout layout(int ref){Object o=objects.get(ref);return o instanceof Layout?(Layout)o:null;}

	/** An ATSUStyle: attribute tag -> value bytes, feature (type<<16 | selector without its on/off bit) -> selector, axis -> value. */
	static final class Style {
		final LinkedHashMap<Integer,byte[]> attributes=new LinkedHashMap<Integer,byte[]>();
		final LinkedHashMap<Integer,Integer> features=new LinkedHashMap<Integer,Integer>();
		final LinkedHashMap<Integer,Integer> variations=new LinkedHashMap<Integer,Integer>();
		int refCon;
		void copyFrom(Style s){attributes.clear();features.clear();variations.clear();overwrite(s);}
		void overwrite(Style s){attributes.putAll(s.attributes);features.putAll(s.features);variations.putAll(s.variations);}
		void underwrite(Style s){for(Map.Entry<Integer,byte[]> e:s.attributes.entrySet())attributes.putIfAbsent(e.getKey(),e.getValue());
			for(Map.Entry<Integer,Integer> e:s.features.entrySet())features.putIfAbsent(e.getKey(),e.getValue());
			for(Map.Entry<Integer,Integer> e:s.variations.entrySet())variations.putIfAbsent(e.getKey(),e.getValue());}
		boolean empty(){return attributes.isEmpty()&&features.isEmpty()&&variations.isEmpty();}
		/** Whether every setting of this style is in {@code s} with the same value. */
		boolean in(Style s){
			for(Map.Entry<Integer,byte[]> e:attributes.entrySet())if(!java.util.Arrays.equals(e.getValue(),s.attributes.get(e.getKey())))return false;
			return s.features.entrySet().containsAll(features.entrySet())&&s.variations.entrySet().containsAll(variations.entrySet());
		}
		int intValue(int tag,int otherwise){byte[] v=attributes.get(tag);return v==null||v.length<4?otherwise:be32(v,0);}
		boolean flag(int tag){byte[] v=attributes.get(tag);return v!=null&&v.length>0&&v[0]!=0;}
	}
	/** An ATSUTextLayout. */
	private static final class Layout {
		int text;boolean handle;int offset,length,total;
		/** The style of each character of [0, total). */
		int[] styles=new int[0];
		final LinkedHashMap<Integer,byte[]> controls=new LinkedHashMap<Integer,byte[]>();
		final Map<Integer,LinkedHashMap<Integer,byte[]>> lineControls=new HashMap<Integer,LinkedHashMap<Integer,byte[]>>();
		final TreeSet<Integer> softBreaks=new TreeSet<Integer>();
		int refCon;boolean transientMatching;
		/** ATSUSetTabArray's tab stops: {Fixed position, ATSUTabType}, in position order. */
		List<int[]> tabs=new ArrayList<int[]>();
		int end(){return offset+length;}
		Layout copy(){Layout l=new Layout();l.tabs=new ArrayList<int[]>(tabs);l.text=text;l.handle=handle;l.offset=offset;l.length=length;l.total=total;l.styles=styles.clone();l.controls.putAll(controls);
			for(Map.Entry<Integer,LinkedHashMap<Integer,byte[]>> e:lineControls.entrySet())l.lineControls.put(e.getKey(),new LinkedHashMap<Integer,byte[]>(e.getValue()));
			l.softBreaks.addAll(softBreaks);l.refCon=refCon;l.transientMatching=transientMatching;return l;}
	}
	/** An ATSUFontFallbacks object. */
	private static final class Fallbacks {int[] fonts=new int[0];int method;}
	private static final class MemorySetting {}

	static int be32(byte[] b,int o){return (b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
	static int be16(byte[] b,int o){return (b[o]&255)<<8|(b[o+1]&255);}
	private static byte[] bytes32(int v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
	private void writeLongIf(int address,int value) throws M68kCpuException {if(address!=0)memory.writeLong(address,value);}
	private void writeWordIf(int address,int value) throws M68kCpuException {if(address!=0)memory.writeWord(address,value);}
	private void writeByteIf(int address,int value) throws M68kCpuException {if(address!=0)memory.writeByte(address,value);}

	// ------------------------------------------------------------------ attribute tags

	/** The value size of an Apple tag: layout and line controls 1-13, style attributes 256-283; -1 for others. */
	private static int tagSize(int tag){
		switch(tag){
			case 1:case 2:case 4:case 5:case 7:case 8:case 9:case 11:case 12:case 13:return 4;
			case 3:return 1;case 6:return 128;case 10:return 2; // BslnBaselineRecord, RegionCode
			case 256:case 257:case 258:case 259:case 260:case 276:case 277:case 278:case 279:case 280:case 281:return 1; // Booleans
			case 263:return 6;case 264:case 265:return 2;case 275:return 80; // RGBColor, RegionCode, vertical type, ATSJustPriorityWidthDeltaOverrides
			default:return tag>=261&&tag<=283?4:-1;
		}
	}
	/** The font a program gets when a style sets none: the application font of the system script. */
	private int defaultFont(){return Fonts68k.japaneseSystem||text.systemEncoding()==1?Fonts68k.JAPANESE_SYSTEM_FONT:1;}
	/** What ATSUGetAttribute reports for a tag nobody set. */
	private byte[] defaultValue(int tag){
		if(tag==FONT_TAG)return bytes32(defaultFont());
		if(tag==SIZE_TAG)return bytes32(12<<16);
		int n=tagSize(tag);return n<0?null:new byte[n];
	}
	/**
	 * ATSUSetAttributes / ATSUSetLayoutControls / ATSUSetLineControls: (count, tags[], sizes[], values[]) into {@code into}.
	 * Apple tags must be of the kind ({@code line}: controls, else style attributes) and at least their size; client tags
	 * (above 65535) keep what they are given. Everything is checked before anything is set.
	 */
	private int setTagged(LinkedHashMap<Integer,byte[]> into,boolean line,int count,int tags,int sizes,int values) throws M68kCpuException {
		if(count>0&&(tags==0||sizes==0||values==0))return PARAM_ERR;
		int[] t=new int[Math.max(0,count)];byte[][] v=new byte[t.length][];
		for(int i=0;i<t.length;i++){
			t[i]=memory.readLong(tags+4*i);int size=memory.readLong(sizes+4*i),p=memory.readLong(values+4*i);
			if(t[i]>=0&&t[i]<=0xffff){
				int want=tagSize(t[i]);if(want<0||line!=(t[i]<256))return INVALID_TAG; // includes kATSUCGContextTag: Mac OS X only
				if(size<want)return INVALID_SIZE;size=want;
			}
			if(p==0)return PARAM_ERR;
			v[i]=memory.readBytes(p,Math.max(0,size));
			if(t[i]==FONT_TAG&&!fontExists(be32(v[i],0)))return INVALID_FONT;
		}
		for(int i=0;i<t.length;i++)into.put(t[i],v[i]);
		return NO_ERR;
	}
	/** ATSUGetAttribute and the control getters: the value (or the default, with kATSUNotSetErr) into oValue, its size into oActualSize. */
	private int getTagged(Map<Integer,byte[]> from,int tag,int expected,int value,int actual) throws M68kCpuException {
		byte[] v=from==null?null:from.get(tag);int status=NO_ERR;
		if(v==null){status=NOT_SET;v=tag>=0&&tag<=0xffff?defaultValue(tag):new byte[0];if(v==null)return INVALID_TAG;}
		if(value!=0)memory.load(value,java.util.Arrays.copyOf(v,Math.min(v.length,Math.max(0,expected))));
		writeLongIf(actual,v.length);
		return status;
	}
	/** ATSUGetAllAttributes and the controls': ATSUAttributeInfo {tag, size} pairs. */
	private int getAllTagged(Map<Integer,byte[]> from,int infos,int max,int count) throws M68kCpuException {
		int i=0;if(from!=null)for(Map.Entry<Integer,byte[]> e:from.entrySet()){if(infos!=0&&i<max){memory.writeLong(infos+8*i,e.getKey());memory.writeLong(infos+8*i+4,e.getValue().length);}i++;}
		writeLongIf(count,i);return NO_ERR;
	}
	private int clearTagged(Map<Integer,byte[]> from,int count,int tags) throws M68kCpuException {
		if(from==null)return NO_ERR;
		if(count==ALL){from.clear();return NO_ERR;}
		for(int i=0;i<count&&tags!=0;i++)from.remove(memory.readLong(tags+4*i));
		return NO_ERR;
	}

	// ------------------------------------------------------------------ style data (handles and streams)

	/**
	 * A style as bytes: {size, attributes, features, variations} (4 longs), then each attribute {tag, size, value padded
	 * to 4}, each feature {type, selector} (2 words), each variation {axis, value}. The font is its ATSUFontID.
	 */
	private static byte[] flatten(Style s){
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		for(Map.Entry<Integer,byte[]> e:s.attributes.entrySet()){byte[] v=e.getValue();out.writeBytes(bytes32(e.getKey()));out.writeBytes(bytes32(v.length));out.writeBytes(v);out.writeBytes(new byte[(4-v.length%4)%4]);}
		for(Map.Entry<Integer,Integer> e:s.features.entrySet()){int type=e.getKey()>>>16,f=e.getValue();out.write(type>>>8);out.write(type);out.write(f>>>8);out.write(f);}
		for(Map.Entry<Integer,Integer> e:s.variations.entrySet()){out.writeBytes(bytes32(e.getKey()));out.writeBytes(bytes32(e.getValue()));}
		byte[] body=out.toByteArray();ByteArrayOutputStream all=new ByteArrayOutputStream();
		all.writeBytes(bytes32(16+body.length));all.writeBytes(bytes32(s.attributes.size()));all.writeBytes(bytes32(s.features.size()));all.writeBytes(bytes32(s.variations.size()));all.writeBytes(body);
		return all.toByteArray();
	}
	/** The style flattened at {@code at} (null if the data is not one). */
	private static Style unflatten(byte[] d,int at){
		if(at+16>d.length)return null;
		int size=be32(d,at),na=be32(d,at+4),nf=be32(d,at+8),nv=be32(d,at+12);int end=at+size,p=at+16;
		if(size<16||end>d.length||na<0||nf<0||nv<0)return null;
		Style s=new Style();
		for(int i=0;i<na;i++){if(p+8>end)return null;int tag=be32(d,p),n=be32(d,p+4);if(n<0||p+8+n>end)return null;s.attributes.put(tag,java.util.Arrays.copyOfRange(d,p+8,p+8+n));p+=8+n+(4-n%4)%4;}
		for(int i=0;i<nf;i++){if(p+4>end)return null;int type=be16(d,p),sel=be16(d,p+2);s.features.put(type<<16|(sel&0xfffe),sel);p+=4;}
		for(int i=0;i<nv;i++){if(p+8>end)return null;s.variations.put(be32(d,p),be32(d,p+4));p+=8;}
		return s;
	}
	private byte[] handleBytes(int h) throws M68kException {int size=rt.trap(0xa025,0,h,0);int p=memory.readLong(h);return size<=0||p==0?new byte[0]:memory.readBytes(p,size);} // _GetHandleSize
	private int setHandleBytes(int h,byte[] b) throws M68kException {int err=(short)rt.trap(0xa024,b.length,h,0);if(err!=0)return err;memory.load(memory.readLong(h),b);return NO_ERR;} // _SetHandleSize

	// ------------------------------------------------------------------ layouts: text and runs

	private int textBase(Layout l) throws M68kCpuException {return l.handle?(l.text==0?0:memory.readLong(l.text)):l.text;}
	private char charAt(Layout l,int i) throws M68kCpuException {int b=textBase(l);return b==0?' ':(char)memory.readWord(b+2*i);}
	private String chars(Layout l,int start,int end) throws M68kCpuException {
		int b=textBase(l);StringBuilder sb=new StringBuilder(Math.max(0,end-start));for(int i=start;i<end;i++)sb.append(b==0?' ':(char)memory.readWord(b+2*i));return sb.toString();
	}
	private void setText(Layout l,int text,boolean handle,int offset,int length,int total){
		l.text=text;l.handle=handle;l.offset=Math.max(0,offset);l.length=Math.max(0,length);l.total=Math.max(total,l.offset+l.length);
		if(l.styles.length!=l.total){int[] s=java.util.Arrays.copyOf(l.styles,l.total);int fill=l.styles.length>0?l.styles[l.styles.length-1]:0;for(int i=l.styles.length;i<s.length;i++)s[i]=fill;l.styles=s;}
		l.softBreaks.clear();
	}
	/** The characters [start, start+count) of a layout with kATSUFromTextBeginning / kATSUToTextEnd resolved, or null when outside the layout. */
	private static int[] range(Layout l,int start,int count){
		int s=start==ALL?l.offset:start;int e=count==ALL?l.end():s+count;
		if(s<l.offset||s>l.end()||count!=ALL&&count<0||e>l.end())return null;
		return new int[]{s,e};
	}
	/** Style runs of [start,end): {runStart, runEnd, styleRef}. */
	private static List<int[]> runs(Layout l,int start,int end){
		List<int[]> runs=new ArrayList<int[]>();
		for(int i=start;i<end;){int s=i<l.styles.length?l.styles[i]:0,j=i+1;while(j<end&&(j<l.styles.length?l.styles[j]:0)==s)j++;runs.add(new int[]{i,j,s});i=j;}
		return runs;
	}
	private void applyRuns(Layout l,int start,int lengths,int styles,int runCount) throws M68kCpuException {
		int at=start;
		for(int i=0;i<runCount&&lengths!=0&&styles!=0;i++){int len=memory.readLong(lengths+4*i),s=memory.readLong(styles+4*i);if(len==ALL)len=l.end()-at;for(int k=0;k<len&&at<l.styles.length;k++)l.styles[at++]=s;}
	}
	/** The line containing {@code offset}: {start, end} by the soft line breaks. */
	private static int[] line(Layout l,int offset){
		Integer b=l.softBreaks.floor(offset);int start=b==null||b<l.offset?l.offset:b;
		Integer n=l.softBreaks.higher(start);int end=n==null||n>l.end()?l.end():n;
		return new int[]{start,end};
	}
	private int control(Layout l,int lineStart,int tag){
		LinkedHashMap<Integer,byte[]> lc=l.lineControls.get(lineStart);byte[] v=lc==null?null:lc.get(tag);if(v==null)v=l.controls.get(tag);
		return v==null?0:v.length>=4?be32(v,0):v.length==2?(short)be16(v,0):v[0];
	}
	private static boolean hardBreak(char c){return c==13||c==10||c==0x2028||c==0x2029;}
	private static boolean space(char c){return c==' '||c==0x3000||c==9||c==0xa0;}

	// ------------------------------------------------------------------ measuring and drawing through QuickDraw

	/** The current port while measuring or drawing, with what is put back afterwards. */
	private final class Session {int port,savedPort,font,face,size,pen;byte[] fore;}
	private int privatePort;
	private int currentPort() throws M68kException {int p=rt.allocate(4,4);rt.toolbox("GetPort",p);int v=memory.readLong(p);memory.free(p);return v;}
	/**
	 * Takes the current port for measuring (a private offscreen port when there is none) or drawing (null when there is
	 * no port): its text state, pen and foreground colour are put back by {@link #leave}.
	 */
	private Session enter(boolean drawing) throws M68kException {
		Session s=new Session();s.savedPort=currentPort();s.port=s.savedPort;
		if(s.port==0){if(drawing)return null;if(privatePort==0){privatePort=rt.allocate(108,4);rt.toolbox("OpenCPort",privatePort);}s.port=privatePort;rt.toolbox("SetPort",privatePort);}
		s.font=(short)memory.readWord(s.port+68);s.face=memory.readByte(s.port+70);s.size=(short)memory.readWord(s.port+74);
		int p=rt.allocate(8,4);rt.toolbox("GetPen",p);s.pen=memory.readLong(p);rt.toolbox("GetForeColor",p);s.fore=memory.readBytes(p,6);memory.free(p);
		return s;
	}
	private void leave(Session s) throws M68kException {
		if(s==null)return;
		rt.toolbox("TextFont",s.font);rt.toolbox("TextFace",s.face);rt.toolbox("TextSize",s.size);
		int p=rt.allocate(8,4);memory.load(p,s.fore);rt.toolbox("RGBForeColor",p);memory.free(p);
		rt.toolbox("MoveTo",(short)s.pen,s.pen>>16);
		if(s.savedPort!=0&&s.savedPort!=s.port)rt.toolbox("SetPort",s.savedPort);
	}
	/** Sets the port's text state (and with {@code color} the foreground) to a style; null is the default style. */
	private void apply(Style s,boolean color) throws M68kException {
		int font=s==null?defaultFont():s.intValue(FONT_TAG,defaultFont()),size=s==null?12<<16:s.intValue(SIZE_TAG,12<<16);
		int face=(font>>>16)&0xff;
		if(s!=null){if(s.flag(256))face|=1;if(s.flag(257))face|=2;if(s.flag(258))face|=4;if(s.flag(259))face|=32;if(s.flag(260))face|=64;}
		rt.toolbox("TextFont",(short)font);rt.toolbox("TextSize",Math.max(1,(size+0x8000)>>16));rt.toolbox("TextFace",face);
		if(color){byte[] c=s==null?null:s.attributes.get(COLOR_TAG);int p=rt.allocate(8,4);if(c!=null)memory.load(p,c);rt.toolbox("RGBForeColor",p);memory.free(p);} // unset colour: black, as ATSUI's default
	}
	/** {ascent, descent, widMax, leading} of the port's current text state. */
	private int[] fontInfo() throws M68kException {int p=rt.allocate(8,4);rt.toolbox("GetFontInfo",p);int[] i={(short)memory.readWord(p),(short)memory.readWord(p+2),(short)memory.readWord(p+4),(short)memory.readWord(p+6)};memory.free(p);return i;}

	/** Characters measured: Fixed advances, the program-encoding bytes to draw for each, and the line metrics of their styles. */
	private static final class Measure {int[] advance;byte[][] bytes;int ascent,descent,leading;}
	/**
	 * Measures [start,end) of a layout, each style run in its style (a session must be open). A surrogate pair's bytes go
	 * with its first half; line breaks measure and draw nothing; a tab is a space. Imposed widths, tracking and the
	 * with-stream shifts of a style add to its characters' advances.
	 */
	private Measure measure(Layout l,int start,int end) throws M68kException {
		Measure m=new Measure();int n=Math.max(0,end-start);m.advance=new int[n];m.bytes=new byte[n][];
		int enc=text.systemEncoding();boolean any=false;
		for(int[] run:runs(l,start,end)){
			Style s=style(run[2]);apply(s,false);int[] info=fontInfo();
			m.ascent=Math.max(m.ascent,info[0]);m.descent=Math.max(m.descent,info[1]);m.leading=Math.max(m.leading,info[3]);any=true;
			ByteArrayOutputStream all=new ByteArrayOutputStream();int[] at=new int[run[1]-run[0]+1];
			for(int i=run[0];i<run[1];i++){
				char c=charAt(l,i);byte[] b;
				if(hardBreak(c)||c<0x20&&c!=9||Character.isLowSurrogate(c))b=new byte[0];
				else if(Character.isHighSurrogate(c)&&i+1<run[1])b=text.encode(enc,""+c+charAt(l,i+1));
				else b=text.encode(enc,c==9?" ":String.valueOf(c));
				m.bytes[i-start]=b;at[i-run[0]]=all.size();all.writeBytes(b);
			}
			at[at.length-1]=all.size();byte[] b=all.toByteArray();
			int[] locs=new int[b.length+1];
			if(b.length>0){int buf=rt.allocate(b.length+2,2),out=rt.allocate(2*b.length+2,2);memory.load(buf,b);rt.toolbox("MeasureText",b.length,buf,out);
				for(int k=0;k<=b.length;k++)locs[k]=(short)memory.readWord(out+2*k);memory.free(buf);memory.free(out);}
			int impose=s==null?0:s.intValue(266,0),extra=s==null?0:s.intValue(270,0)+s.intValue(267,0)+s.intValue(268,0);
			for(int i=run[0];i<run[1];i++){
				int k=i-run[0];int w=(locs[at[k+1]]-locs[at[k]])<<16;
				if(m.bytes[i-start].length>0){if(impose!=0)w=impose;w+=extra;}
				m.advance[i-start]=w;
			}
		}
		if(!any){Style s=style(l.styles.length>0?l.styles[Math.min(Math.max(0,start),l.styles.length-1)]:0);apply(s,false);int[] info=fontInfo();m.ascent=info[0];m.descent=info[1];m.leading=info[3];}
		return m;
	}
	/** A line laid out: the Fixed x of each character's left edge from the line origin (x[n] = the end), flush and justification applied. */
	private static final class Line {int start,end;int[] x;Measure m;int width;}
	private Line lay(Layout l,int start,int end) throws M68kException {
		Line line=new Line();line.start=start;line.end=end;line.m=measure(l,start,end);int n=end-start;int[] adv=line.m.advance.clone();
		tabbed(l,start,adv);
		int content=n;while(content>0&&(space(charAt(l,start+content-1))||hardBreak(charAt(l,start+content-1))))content--; // trailing white space hangs
		int width=0;for(int i=0;i<content;i++)width+=adv[i];
		int lineWidth=control(l,start,1),flush=control(l,start,5),just=control(l,start,4);int shift=0;
		int la=control(l,start,8),ld=control(l,start,9);if(la>0)line.m.ascent=(la+0x8000)>>16;if(ld>0)line.m.descent=(ld+0x8000)>>16;
		if(lineWidth>0){
			int gap=lineWidth-width;
			boolean last=end>=l.end()||n>0&&hardBreak(charAt(l,end-1));
			if(just>0&&gap>0&&!last){int spaces=0;for(int i=0;i<content;i++)if(space(charAt(l,start+i)))spaces++;
				if(spaces>0){int each=(int)((long)gap*just/FRACT_ONE/spaces);for(int i=0;i<content;i++)if(space(charAt(l,start+i))){adv[i]+=each;width+=each;}gap=lineWidth-width;}}
			if(flush!=0)shift=(int)((long)gap*flush/FRACT_ONE);
		}
		line.x=new int[n+1];line.x[0]=shift;for(int i=0;i<n;i++)line.x[i+1]=line.x[i]+adv[i];
		line.width=width;return line;
	}
	/**
	 * Tab characters of the line starting at {@code start} reach the layout's next tab stop (ATSUSetTabArray): a left
	 * stop starts the text after the tab at it, a right stop ends that text (up to the next tab or line end) at it, a
	 * centre stop centres it there and a decimal stop puts its first '.' or ',' there. Without stops, or past the last
	 * one, a tab stays as wide as a space. {@code adv} (Fixed advances from {@code start}) is adjusted in place.
	 */
	private void tabbed(Layout l,int start,int[] adv) throws M68kCpuException {
		if(l.tabs.isEmpty())return;
		int x=0;
		for(int i=0;i<adv.length;i++){
			if(charAt(l,start+i)!=9){x+=adv[i];continue;}
			int[] stop=null;for(int[] t:l.tabs)if(t[0]>x){stop=t;break;}
			if(stop==null){x+=adv[i];continue;}
			int segment=0,toDecimal=-1;
			for(int j=i+1;j<adv.length;j++){char c=charAt(l,start+j);if(c==9||hardBreak(c))break;if(toDecimal<0&&(c=='.'||c==','))toDecimal=segment;segment+=adv[j];}
			int target=stop[1]==1?stop[0]-segment/2:stop[1]==2?stop[0]-segment:stop[1]==3?stop[0]-(toDecimal<0?segment:toDecimal):stop[0]; // kATSUCenterTab, kATSURightTab, kATSUDecimalTab, kATSULeftTab
			adv[i]=Math.max(0,target-x);x+=adv[i];
		}
	}
	private static int round(int fixed){return (fixed+0x8000)>>16;}
	/**
	 * Draws one line with its origin at the Fixed point (x,y) (a session is open). With kATSULineTruncationTag and a
	 * line width the text does not fit, characters go from the end, the start or the middle and an ellipsis takes their place.
	 */
	private void drawLine(Layout l,Line line,int x,int y) throws M68kException {
		int n=line.end-line.start;int lineWidth=control(l,line.start,1),truncation=control(l,line.start,12);
		boolean[] keep=new boolean[n];java.util.Arrays.fill(keep,true);int ellipsisAt=-1,pen=x+line.x[0];
		if(truncation>=1&&truncation<=3&&lineWidth>0&&line.width>lineWidth){
			Style es=style(l.styles[line.start]);int ell=width("\u2026",es),budget=lineWidth-ell,used=0;
			int content=n;while(content>0&&(space(charAt(l,line.start+content-1))||hardBreak(charAt(l,line.start+content-1))))content--;
			int a=0,b=content;int[] adv=line.m.advance;
			if(truncation==2)while(a<content&&used+adv[a]<=budget)used+=adv[a++];                  // kATSUTruncateEnd
			else if(truncation==1){b=content;while(b>0&&used+adv[b-1]<=budget)used+=adv[--b];}      // kATSUTruncateStart
			else{boolean front=true;while(a<b){int i=front?a:b-1;if(used+adv[i]>budget)break;used+=adv[i];if(front)a++;else b--;front=!front;}} // kATSUTruncateMiddle
			if(truncation==2)b=content;
			for(int i=0;i<n;i++)keep[i]=i<a||i>=b&&i<content;
			ellipsisAt=a;pen=x+(int)((long)(lineWidth-used-ell)*control(l,line.start,5)/FRACT_ONE);
		}
		for(int i=0;i<=n;i++){
			if(i==ellipsisAt){Style s=style(l.styles[line.start+Math.min(i,n-1)]);apply(s,true);
				drawBytes(text.encode(text.systemEncoding(),"\u2026"),pen,y-(s==null?0:s.intValue(269,0)));pen+=width("\u2026",s);}
			if(i==n)break;
			if(!keep[i])continue;
			// a stretch of kept characters of one style at their measured advances goes out in one DrawText
			int j=i;Style s=style(l.styles[line.start+i]);boolean plain=s==null||s.intValue(266,0)==0&&s.intValue(270,0)==0&&s.intValue(267,0)==0&&s.intValue(268,0)==0;
			ByteArrayOutputStream b=new ByteArrayOutputStream();int w=0;
			do{b.writeBytes(line.m.bytes[j]);w+=line.x[j+1]-line.x[j];j++;}
			while(plain&&j<n&&keep[j]&&j!=ellipsisAt&&l.styles[line.start+j]==l.styles[line.start+i]&&line.x[j]-line.x[j-1]==line.m.advance[j-1]&&line.x[j+1]-line.x[j]==line.m.advance[j]);
			apply(s,true);
			drawBytes(b.toByteArray(),pen,y-(s==null?0:s.intValue(269,0)));pen+=w;i=j-1;
		}
	}
	private void drawBytes(byte[] b,int x,int y) throws M68kException {
		if(b.length==0)return;int p=rt.allocate(b.length+2,2);memory.load(p,b);rt.toolbox("MoveTo",round(x),round(y));rt.toolbox("DrawText",p,0,b.length);memory.free(p);
	}
	/** The Fixed width of a string in a style (a session is open). */
	private int width(String s,Style style) throws M68kException {
		apply(style,false);byte[] b=text.encode(text.systemEncoding(),s);if(b.length==0)return 0;int p=rt.allocate(b.length+2,2);memory.load(p,b);int w=(short)rt.toolbox("TextWidth",p,0,b.length);memory.free(p);return w<<16;
	}
	/** The lines of [start,end): split at the soft line breaks. */
	private List<int[]> lines(Layout l,int start,int end){
		List<int[]> lines=new ArrayList<int[]>();int s=start;
		for(int b:l.softBreaks.subSet(start,false,end,false)){lines.add(new int[]{s,b});s=b;}
		lines.add(new int[]{s,end});return lines;
	}
	/** Draws [start,end) with the first line's origin at (x,y) (kATSUUseGrafPortPenLoc: the pen); later lines go below it. */
	private int draw(Layout l,int start,int end,int x,int y) throws M68kException {
		Session s=enter(true);if(s==null)return NO_ERR;
		try{
			if(x==ALL||y==ALL){x=((short)s.pen)<<16;y=(s.pen>>16)<<16;}
			boolean first=true;int baseline=y,previousDescent=0;
			for(int[] ln:lines(l,start,end)){
				Line line=lay(l,ln[0],ln[1]);
				if(!first)baseline+=(previousDescent+line.m.leading+line.m.ascent)<<16;
				drawLine(l,line,x,baseline);previousDescent=line.m.descent;first=false;
			}
		}finally{leave(s);}
		return NO_ERR;
	}
	/** The QuickDraw rectangle {top, left, bottom, right} of the characters [a,b) of the line containing a, from the line origin (x,y). */
	private int[] highlightRect(Layout l,int a,int b,int x,int y) throws M68kException {
		int[] ln=line(l,a);Session s=enter(false);
		try{Line line=lay(l,ln[0],ln[1]);int from=line.x[a-ln[0]],to=line.x[Math.min(b,ln[1])-ln[0]];
			return new int[]{round(y)-line.m.ascent,round(x+Math.min(from,to)),round(y)+line.m.descent,round(x+Math.max(from,to))};}
		finally{leave(s);}
	}
	private int rect(int[] r) throws M68kCpuException {int p=rt.allocate(8,2);memory.writeWord(p,r[0]);memory.writeWord(p+2,r[1]);memory.writeWord(p+4,r[2]);memory.writeWord(p+6,r[3]);return p;}

	/**
	 * ATSUBreakLine: where the line starting at {@code start} ends within {@code width} (Fixed; 0 or less: no limit) -
	 * after a hard break, else at the last line-break opportunity that fits (trailing spaces hang), else inside a word.
	 * Returns {break offset, status}.
	 */
	private int[] breakLine(Layout l,int start,int width) throws M68kException {
		int end=l.end();if(start>=end)return new int[]{end,NO_ERR};
		Session s=enter(false);Measure m;try{m=measure(l,start,end);}finally{leave(s);}
		String t=chars(l,start,end);int pos=0;
		for(int i=0;i<t.length();i++){
			char c=t.charAt(i);
			if(hardBreak(c)){int b=start+i+1;if(c==13&&i+1<t.length()&&t.charAt(i+1)==10)b++;return new int[]{Math.min(end,b),NO_ERR};}
			if(width>0&&pos+m.advance[i]>width&&!space(c)){
				BreakIterator it=BreakIterator.getLineInstance();it.setText(t);int b=it.preceding(i+1);
				if(b>0&&b<=i)return new int[]{start+b,NO_ERR};
				return new int[]{start+Math.max(1,i),LINE_BREAK_IN_WORD};
			}
			pos+=m.advance[i];
		}
		return new int[]{end,NO_ERR};
	}
	private int lineWidthFor(Layout l,int start,int width){return width==USE_LINE_CONTROL_WIDTH?control(l,start,1):width;}
	/** The next or previous cursor position from {@code from} by character, cluster or word; {@code from} itself at the text's ends. */
	private int cursor(Layout l,int from,int type,boolean forward) throws M68kCpuException {
		String t=chars(l,l.offset,l.end());int i=from-l.offset;
		if(forward&&i>=t.length())return l.end();if(!forward&&i<=0)return l.offset;
		BreakIterator it=type==2?BreakIterator.getWordInstance():type==1||type==3?BreakIterator.getCharacterInstance():null;
		if(it==null){int j=forward?i+1:i-1;if(forward&&j<t.length()&&Character.isLowSurrogate(t.charAt(j)))j++;if(!forward&&j>0&&Character.isLowSurrogate(t.charAt(j)))j--;return l.offset+j;}
		it.setText(t);int j=forward?it.following(i):it.preceding(i);
		if(type==2&&forward)while(j!=BreakIterator.DONE&&j<t.length()&&Character.isWhitespace(t.charAt(j-1)))j=it.following(j); // to the end of the next word
		if(type==2&&!forward)while(j!=BreakIterator.DONE&&j>0&&Character.isWhitespace(t.charAt(j)))j=it.preceding(j);
		return l.offset+(j==BreakIterator.DONE?(forward?t.length():0):j);
	}

	/**
	 * Draws text wrapped into a box, as TXNDrawUnicodeTextBox does: {@code box} is a QuickDraw Rect; {@code options}
	 * TXNTextBoxOptionsData (flushness, justification, kTXNDontDrawTextMask, kTXNDontUpdateBoxRectMask). Returns an OSStatus.
	 */
	int drawTextBox(String s,int box,int styleRef,int options) throws M68kException {
		if(box==0)return PARAM_ERR;
		int top=(short)memory.readWord(box),left=(short)memory.readWord(box+2),right=(short)memory.readWord(box+6);
		int tags=options==0?0:memory.readLong(options),flush=options==0?0:memory.readLong(options+4),just=options==0?0:memory.readLong(options+8);
		int buf=rt.allocate(2*s.length()+2,2);for(int i=0;i<s.length();i++)memory.writeWord(buf+2*i,s.charAt(i));
		Layout l=new Layout();setText(l,buf,false,0,s.length(),s.length());java.util.Arrays.fill(l.styles,styleRef);
		l.controls.put(1,bytes32((right-left)<<16));
		if((tags&1)!=0)l.controls.put(5,bytes32(flush));if((tags&2)!=0)l.controls.put(4,bytes32(just));
		try{
			List<int[]> lines=new ArrayList<int[]>();int at=0;
			while(at<s.length()){int b=breakLine(l,at,(right-left)<<16)[0];if(b<=at)b=at+1;lines.add(new int[]{at,b});at=b;}
			if(lines.isEmpty())lines.add(new int[]{0,0});
			int longest=0,y=top;Session session=(tags&64)!=0?enter(false):enter(true);if(session==null)session=enter(false);boolean draw=(tags&64)==0&&session.port!=privatePort;
			try{for(int[] ln:lines){Line line=lay(l,ln[0],ln[1]);y+=line.m.ascent;if(draw)drawLine(l,line,left<<16,y<<16);y+=line.m.descent+line.m.leading;longest=Math.max(longest,round(line.width));}}
			finally{leave(session);}
			if((tags&32)==0){memory.writeWord(box+4,y);memory.writeWord(box+6,left+longest);} // kTXNDontUpdateBoxRectMask
		}finally{memory.free(buf);}
		return NO_ERR;
	}

	// ------------------------------------------------------------------ fonts

	/** A font family as the Font Manager lists it: its FOND number and name, and the suitcase it comes from (if any). */
	static final class Family {
		final int id;final String name;final File file;final ResourceFile68k resources;final List<int[]> association;final int generation;
		Family(int id,String name,File file,ResourceFile68k resources,List<int[]> association,int generation){this.id=id;this.name=name;this.file=file;this.resources=resources;this.association=association;this.generation=generation;}
		/** The styles with a font of their own (a FOND's association entries), plain at least. */
		List<Integer> styles(){Set<Integer> s=new TreeSet<Integer>();s.add(0);for(int[] a:association)s.add(a[1]&0xff);return new ArrayList<Integer>(s);}
		/** The 'sfnt' of a style (or of plain), or null. */
		byte[] sfnt(int style){
			if(resources==null)return null;byte[] plain=null;
			for(int[] a:association)if(a[0]==0){ResourceFile68k.Resource r=resources.get(SFNT,a[2]);if(r==null)continue;if((a[1]&0xff)==style)return r.data();if(a[1]==0)plain=r.data();}
			return style==0?plain:null;
		}
	}
	/** Suitcases activated by FMActivateFonts: their families and the reference numbers they are open under. */
	private final List<Family> activated=new ArrayList<Family>();
	private final Map<File,Integer> activatedFiles=new HashMap<File,Integer>();
	private int generation=1;

	/** The families a program can use: the classic table and the Fonts folder's suitcases (as font menus list them), then activated ones. */
	List<Family> families(){
		List<Family> list=new ArrayList<Family>();Set<Integer> seen=new LinkedHashSet<Integer>();
		for(String n:Fonts68k.allNames()){
			int id=Fonts68k.numberOf(n);if(!seen.add(id))continue;
			InstalledFonts68k.Family f=InstalledFonts68k.family(id);if(f==null||!f.name.equalsIgnoreCase(n))f=InstalledFonts68k.familyNamed(n);
			list.add(f!=null?new Family(id,n,f.file,f.resources,f.association,1):new Family(id,n,null,null,new ArrayList<int[]>(),1));
		}
		for(Family f:activated)if(seen.add(f.id))list.add(f);
		return list;
	}
	/** The family numbered {@code id}: a listed one, else one only the program's own resources define; null when nobody does. */
	Family family(int id){
		id=(short)id;
		for(Family f:families())if(f.id==id)return f;
		String name=Fonts68k.nameOf(id);return name.isEmpty()?null:new Family(id,name,null,null,new ArrayList<int[]>(),1);
	}
	/** The FMFont / ATSUFontID of a family's style: the family number, the style in bits 16-23 when the style has a font of its own. */
	static int fontOf(int family,int style){return (family&0xffff)|(style&0xff)<<16;}
	private boolean fontExists(int font){return (font>>>24)==0&&family(font&0xffff)!=null;}
	/** Every font: each family's styles. */
	private List<Integer> fonts(){List<Integer> l=new ArrayList<Integer>();for(Family f:families())for(int s:f.styles())l.add(fontOf(f.id,s));return l;}
	/** The script of a family by its number's range (16384 up: 512 per non-Roman script). */
	static int script(int family){family=(short)family;return family>=16384?(family-16384)/512+1:0;}
	/** A family's text encoding (FMGetFontFamilyTextEncoding): its script's, Symbol and Zapf Dingbats their own. */
	static int familyEncoding(Family f){if(f.name.equalsIgnoreCase("Symbol"))return 33;if(f.name.equalsIgnoreCase("Zapf Dingbats"))return 34;return script(f.id);}

	/** The table {@code tag} of an sfnt, or null. */
	static byte[] table(byte[] sfnt,int tag){
		if(sfnt==null||sfnt.length<12)return null;int n=be16(sfnt,4);
		for(int i=0;i<n&&12+16*i+16<=sfnt.length;i++){int e=12+16*i;if(be32(sfnt,e)!=tag)continue;int off=be32(sfnt,e+8),len=be32(sfnt,e+12);
			if(off<0||len<0||off+len>sfnt.length)return null;return java.util.Arrays.copyOfRange(sfnt,off,off+len);}
		return null;
	}
	private byte[] fontTable(int font,String tag){Family f=family(font&0xffff);return f==null?null:table(f.sfnt((font>>>16)&0xff),ResourceFile68k.fourcc(tag));}
	/** A font's names: {nameCode, platform, script, language} and the name's bytes. From its 'name' table, else synthesized. */
	private static final class FontName {final int code,platform,script,language;final byte[] bytes;FontName(int c,int p,int s,int l,byte[] b){code=c;platform=p;script=s;language=l;bytes=b;}}
	private List<FontName> names(int font){
		List<FontName> names=new ArrayList<FontName>();Family f=family(font&0xffff);if(f==null)return names;
		byte[] t=fontTable(font,"name");
		if(t!=null&&t.length>=6){int n=be16(t,2),strings=be16(t,4);
			for(int i=0;i<n&&6+12*i+12<=t.length;i++){int r=6+12*i;int len=be16(t,r+8),off=strings+be16(t,r+10);if(off+len>t.length)continue;
				names.add(new FontName(be16(t,r+6),be16(t,r),be16(t,r+2),be16(t,r+4),java.util.Arrays.copyOfRange(t,off,off+len)));}
			if(!names.isEmpty())return names;}
		int style=(font>>>16)&0xff;String styleName=style==0?"Regular":((style&1)!=0?"Bold":"")+((style&3)==3?" ":"")+((style&2)!=0?"Italic":"");
		if(styleName.isEmpty())styleName="Style "+style;
		String full=style==0?f.name:f.name+" "+styleName;int script=script(f.id),language=script==1?11:0;int enc=familyEncoding(f);
		String[] values={null,f.name,styleName,"HyperZebra: "+full,full,"1.0",full.replace(" ","")};
		for(int code=1;code<values.length;code++)names.add(new FontName(code,1,script,language,text.encode(enc,values[code])));
		names.add(new FontName(1,0,3,0,f.name.getBytes(StandardCharsets.UTF_16BE)));names.add(new FontName(4,0,3,0,full.getBytes(StandardCharsets.UTF_16BE))); // Unicode platform, Unicode 2.0 semantics
		return names;
	}
	private static boolean matches(FontName n,int code,int platform,int script,int language){
		return (code==ALL||n.code==code)&&(platform==ALL||n.platform==platform)&&(script==ALL||n.script==script)&&(language==ALL||n.language==language);
	}
	/** The text of a name record (for comparisons that tolerate another encoding than the record's). */
	private String nameText(FontName n){if(n.platform==0||n.platform==3)return new String(n.bytes,StandardCharsets.UTF_16BE);return text.decode(n.platform==1?n.script:0,n.bytes);}

	/** 'fvar': axes {tag, min, default, max, nameID} and instances {nameID, coordinates[]}. */
	private static final class Variations {final List<int[]> axes=new ArrayList<int[]>();final List<int[]> instances=new ArrayList<int[]>();}
	private Variations variations(int font){
		Variations v=new Variations();byte[] t=fontTable(font,"fvar");if(t==null||t.length<16)return v;
		int data=be16(t,4),axisCount=be16(t,8),axisSize=be16(t,10),instanceCount=be16(t,12),instanceSize=be16(t,14);
		for(int i=0;i<axisCount;i++){int a=data+i*axisSize;if(a+20>t.length)break;v.axes.add(new int[]{be32(t,a),be32(t,a+4),be32(t,a+8),be32(t,a+12),be16(t,a+18)});}
		int base=data+axisCount*axisSize;
		for(int i=0;i<instanceCount;i++){int a=base+i*instanceSize;if(a+4+4*axisCount>t.length)break;int[] inst=new int[1+axisCount];inst[0]=be16(t,a);for(int k=0;k<axisCount;k++)inst[1+k]=be32(t,a+4+4*k);v.instances.add(inst);}
		return v;
	}
	/** 'feat': per feature type {type, nameIndex, exclusive (0/1), default selector index, selectors..., selector name indexes...}. */
	private List<int[]> features(int font){
		List<int[]> l=new ArrayList<int[]>();byte[] t=fontTable(font,"feat");if(t==null||t.length<12)return l;int n=be16(t,4);
		for(int i=0;i<n&&12+12*i+12<=t.length;i++){int r=12+12*i;int type=be16(t,r),count=be16(t,r+2),table=be32(t,r+4),flags=be16(t,r+8),name=(short)be16(t,r+10);
			int[] f=new int[4+2*count];f[0]=type;f[1]=name;f[2]=(flags&0x8000)!=0?1:0;f[3]=(flags&0x4000)!=0?flags&0xff:0;
			for(int k=0;k<count&&table+4*k+4<=t.length;k++){f[4+k]=be16(t,table+4*k);f[4+count+k]=(short)be16(t,table+4*k+2);}
			l.add(f);}
		return l;
	}
	/** 'trak': the horizontal (or vertical) tracks {track value, nameIndex}. */
	private List<int[]> tracks(int font,boolean vertical){
		List<int[]> l=new ArrayList<int[]>();byte[] t=fontTable(font,"trak");if(t==null||t.length<12)return l;int data=be16(t,vertical?8:6);if(data==0||data+8>t.length)return l;
		int n=be16(t,data);for(int i=0;i<n&&data+8+8*i+8<=t.length;i++){int e=data+8+8*i;l.add(new int[]{be32(t,e),(short)be16(t,e+4)});}
		return l;
	}

	// ---- Font Manager iterators: the caller's 64-byte record holds our token in its first long

	private static final int ITERATOR_MAGIC=0x685a4649; // 'hZFI'
	private static final class Iteration {List<int[]> items=new ArrayList<int[]>();int index;}
	private final Map<Integer,Iteration> iterations=new HashMap<Integer,Iteration>();
	private int nextIteration=1;
	private Iteration iteration(int record) throws M68kCpuException {if(record==0||memory.readLong(record+4)!=ITERATOR_MAGIC)return null;return iterations.get(memory.readLong(record));}
	private void startIteration(int record,Iteration it) throws M68kCpuException {
		Iteration old=iteration(record);int token=old!=null?memory.readLong(record):nextIteration++;
		iterations.put(token,it);memory.writeLong(record,token);memory.writeLong(record+4,ITERATOR_MAGIC);
	}
	/** Whether a font of a family passes an FMFilter (null filter: everything); {@code font} -1 asks for the family as a whole. */
	private boolean passes(int filter,int refCon,Family f,int font) throws M68kException {
		if(filter==0)return true;
		int selector=memory.readLong(filter+4),value=memory.readLong(filter+8);
		switch(selector){
			case 1:{if(value!=TRUE_TYPE)return false;if(font!=-1)return f.sfnt((font>>>16)&0xff)!=null;for(int s:f.styles())if(f.sfnt(s)!=null)return true;return false;} // font technology
			case 2:{File c=specFile(filter+8);return c!=null&&f.file!=null&&same(c,f.file);}                                              // container
			case 3:return f.generation==value;                                                                                               // generation
			case 4:return rt.callUniversal(value,0x3b0,new int[]{f.id,refCon})==NO_ERR;                                                     // family callback (noErr keeps it)
			case 5:{if(font==-1)return true;return rt.callUniversal(value,0x3f0,new int[]{font,refCon})==NO_ERR;}                          // font callback
			default:return true;                                                                                                             // directory: every font is in the one
		}
	}
	private static boolean same(File a,File b){try{return a.getCanonicalFile().equals(b.getCanonicalFile());}catch(IOException e){return a.equals(b);}}
	/** The host file an FSSpec names, or null. */
	private File specFile(int spec) throws M68kCpuException {
		try{FileManager68k fm=rt.toolbox.files.fileManager;File dir=fm.directoryForId(memory.readLong(spec+2),(short)memory.readWord(spec));
			return dir==null?null:fm.resolveBytes(dir,rt.pBytes(spec+6));}catch(IOException|RuntimeException e){return null;}
	}

	/** FMActivateFonts: the suitcase's families join the list and its resources the program's chain (for QuickDraw to draw them). */
	private int activate(int spec) throws M68kException {
		File file=spec==0?null:specFile(spec);if(file==null||!file.isFile())return FNF_ERR;
		for(File f:activatedFiles.keySet())if(same(f,file))return NO_ERR;
		ResourceFile68k rf;try{rf=ResourceFile68k.open(file);}catch(IOException|RuntimeException e){return FM_CONTAINER_ACCESS;}
		List<Family> found=new ArrayList<Family>();
		for(ResourceFile68k.Resource fond:rf.ofType(FOND)){byte[] d=fond.data();if(d.length<54)continue;List<int[]> assoc=new ArrayList<int[]>();
			int n=(short)MacApp68k.u2(d,52)+1;for(int i=0;i<n&&54+i*6+5<d.length;i++)assoc.add(new int[]{MacApp68k.u2(d,54+i*6),MacApp68k.u2(d,56+i*6),(short)MacApp68k.u2(d,58+i*6)});
			found.add(new Family(fond.id,fond.name==null?"":fond.name,file,rf,assoc,generation+1));}
		if(found.isEmpty())return FM_CONTAINER_ACCESS;
		int saved=rt.toolbox("CurResFile");int ref=(short)rt.toolbox("FSpOpenResFile",spec,1);rt.toolbox("UseResFile",saved); // fsRdPerm
		generation++;activated.addAll(found);activatedFiles.put(file,ref);
		return NO_ERR;
	}
	private int deactivate(int spec) throws M68kException {
		File file=spec==0?null:specFile(spec);if(file==null)return FNF_ERR;
		for(Map.Entry<File,Integer> e:activatedFiles.entrySet())if(same(e.getKey(),file)){
			if(e.getValue()>0)rt.toolbox("CloseResFile",e.getValue());
			activated.removeIf(f->same(f.file,file));activatedFiles.remove(e.getKey());generation++;return NO_ERR;}
		return FM_CONTAINER_ACCESS;
	}

	// ------------------------------------------------------------------ glyphs (ATSUI's low-level calls)

	/**
	 * Glyphs are the host's: a style's glyph font is the family's own TrueType outlines (its 'sfnt', through
	 * {@link java.awt.Font#createFont}) when it has them, else the host font QuickDraw substitutes for the family. A
	 * GlyphID is that font's glyph code, so ATSUGetGlyphInfo, the metrics and the outline calls agree with each other;
	 * the text itself still draws through QuickDraw. Coordinates are QuickDraw's: points, y growing downwards from the
	 * baseline.
	 */
	private static final java.awt.font.FontRenderContext GLYPH_FRC=new java.awt.font.FontRenderContext(null,true,true);
	private static final int DELETED_GLYPH=0xffff,GLYPH_INFO_SIZE=28,GLYPH_IS_WHITE_SPACE=0x00040000,GLYPH_HAS_IMPOSED_WIDTH=0x10,UNIMP_ERR=-4;
	/** Host fonts made from a family style's 'sfnt' (family | style << 16); null where there is none or Java refused it. */
	private final Map<Integer,java.awt.Font> sfntFonts=new HashMap<Integer,java.awt.Font>();
	/** Glyph codes beyond 16 bits (a composite host font's slot in the high byte), by "font name/GlyphID handed out". */
	private final Map<String,Integer> wideGlyphs=new HashMap<String,Integer>();
	/** The host glyph font of a style at its size in points (null: the default style). */
	java.awt.Font glyphFont(Style s){
		int font=s==null?defaultFont():s.intValue(FONT_TAG,defaultFont());float size=(s==null?12<<16:s.intValue(SIZE_TAG,12<<16))/65536f;if(!(size>0))size=12;
		int intrinsic=(font>>>16)&0xff,face=intrinsic;if(s!=null){if(s.flag(256))face|=1;if(s.flag(257))face|=2;}
		int javaStyle=((face&1)!=0?java.awt.Font.BOLD:0)|((face&2)!=0?java.awt.Font.ITALIC:0);
		Family f=family(font&0xffff);java.awt.Font base=null;int key=(font&0xffff)|intrinsic<<16;
		if(f!=null){
			if(sfntFonts.containsKey(key))base=sfntFonts.get(key);
			else{byte[] sf=f.sfnt(intrinsic);if(sf!=null)try{base=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,new java.io.ByteArrayInputStream(sf));}catch(Exception|Error e){base=null;}sfntFonts.put(key,base);}
		}
		if(base!=null)return (face&~intrinsic&3)!=0?base.deriveFont(javaStyle,size):base.deriveFont(size); // a face the sfnt lacks: Java's synthetic style
		return new java.awt.Font(Fonts68k.familyFor(font&0xffff),javaStyle,1).deriveFont(size);
	}
	private int glyphId(java.awt.Font f,int code){if(code>=0&&code<DELETED_GLYPH)return code;int id=code&0xffff;if(id==DELETED_GLYPH)id--;wideGlyphs.put(f.getFontName()+"/"+id,code);return id;}
	private int glyphCode(java.awt.Font f,int id){Integer w=wideGlyphs.get(f.getFontName()+"/"+id);return w!=null?w:id;}
	private java.awt.font.GlyphVector glyph(java.awt.Font f,int id){return f.createGlyphVector(GLYPH_FRC,new int[]{glyphCode(f,id)});}
	/** The GlyphIDs of characters [start,end) of a layout, one per character: a surrogate pair's glyph on its first half, deleted glyphs for its second and for line breaks and controls. */
	private int[] glyphIds(Layout l,int start,int end) throws M68kCpuException {
		int[] ids=new int[Math.max(0,end-start)];
		for(int[] run:runs(l,start,end)){
			java.awt.Font f=glyphFont(style(run[2]));
			for(int i=run[0];i<run[1];i++){
				char c=charAt(l,i);
				if(hardBreak(c)||c<0x20&&c!=9||Character.isLowSurrogate(c)&&i>start&&Character.isHighSurrogate(charAt(l,i-1))){ids[i-start]=DELETED_GLYPH;continue;}
				String ch=Character.isHighSurrogate(c)&&i+1<end&&Character.isLowSurrogate(charAt(l,i+1))?""+c+charAt(l,i+1):c==9?" ":String.valueOf(c);
				ids[i-start]=glyphId(f,f.createGlyphVector(GLYPH_FRC,ch).getGlyphCode(0));
			}
		}
		return ids;
	}
	private static int floatBits(double v){return Float.floatToIntBits((float)v);}
	/** The address of the {@code i}th GlyphID of an array whose entries are {@code stride} bytes apart (0: packed UInt16s). */
	private static int glyphAt(int array,int stride,int i){return array+(stride==0?2:stride)*i;}

	/** Calls one of the program's outline callbacks (TVECTOR UPPs: the routine itself, or a routine descriptor). */
	private int callback(int upp,int... args) throws M68kException {
		int procInfo=0x31;for(int i=0;i<args.length;i++)procInfo|=3<<(6+2*i); // kCStackBased, 4-byte result, 4-byte arguments
		return rt.isRoutineDescriptor(upp)?rt.callUniversal(upp,procInfo,args):rt.cpu.call(upp,args);
	}
	/** A glyph's outline as path segments {type, x1, y1, x2, y2, x3, y3} (PathIterator types), every contour closed. */
	private List<double[]> outline(java.awt.Font f,int id){
		List<double[]> segments=new ArrayList<double[]>();
		java.awt.geom.PathIterator it=glyph(f,id).getGlyphOutline(0).getPathIterator(null);double[] c=new double[6];boolean open=false;
		for(;!it.isDone();it.next()){
			int type=it.currentSegment(c);
			if(type==java.awt.geom.PathIterator.SEG_MOVETO&&open)segments.add(new double[]{java.awt.geom.PathIterator.SEG_CLOSE});
			if(type==java.awt.geom.PathIterator.SEG_MOVETO)open=true;else if(type==java.awt.geom.PathIterator.SEG_CLOSE){if(!open)continue;open=false;}
			segments.add(new double[]{type,c[0],c[1],c[2],c[3],c[4],c[5]});
		}
		if(open)segments.add(new double[]{java.awt.geom.PathIterator.SEG_CLOSE});
		return segments;
	}
	/** kATSCubicCurveType (1) when the glyph font's outlines use cubic curves (PostScript outlines), else kATSQuadCurveType (2). */
	private int curveType(java.awt.Font f){
		java.awt.font.GlyphVector gv=f.createGlyphVector(GLYPH_FRC,"O@8");
		for(int g=0;g<gv.getNumGlyphs();g++)for(java.awt.geom.PathIterator it=gv.getGlyphOutline(g).getPathIterator(null);!it.isDone();it.next())if(it.currentSegment(new double[6])==java.awt.geom.PathIterator.SEG_CUBICTO)return 1;
		return 2;
	}
	/**
	 * Walks a glyph's outline through the program's callbacks: quadratic (newPath, line(pt1, pt2), curve(pt1, control,
	 * pt2), closePath) or cubic (moveTo, lineTo, curveTo(c1, c2, end), closePath). The other kind of curve is converted
	 * (a cubic as two quadratics, a quadratic raised exactly); a contour ends with a line back to its start when it does
	 * not end there. The first callback that answers non-zero stops the walk; its answer goes to *oCallbackResult.
	 */
	private int walkOutline(java.awt.Font f,int id,boolean cubic,int[] procs,int data,int resultOut) throws M68kException {
		for(int p:procs)if(p==0)return PARAM_ERR;
		List<double[]> segments=outline(f,id);int pts=rt.allocate(24,4);int result=NO_ERR;
		try{
			double cx=0,cy=0,sx=0,sy=0;
			for(double[] seg:segments){
				int type=(int)seg[0];
				switch(type){
					case java.awt.geom.PathIterator.SEG_MOVETO:
						sx=cx=seg[1];sy=cy=seg[2];result=cubic?callback(procs[0],point(pts,0,cx,cy),data):callback(procs[0],data);break;
					case java.awt.geom.PathIterator.SEG_LINETO:
						result=line(cubic,procs,pts,data,cx,cy,seg[1],seg[2]);cx=seg[1];cy=seg[2];break;
					case java.awt.geom.PathIterator.SEG_QUADTO:
						if(cubic)result=callback(procs[2],point(pts,0,cx+2*(seg[1]-cx)/3,cy+2*(seg[2]-cy)/3),point(pts,8,seg[3]+2*(seg[1]-seg[3])/3,seg[4]+2*(seg[2]-seg[4])/3),point(pts,16,seg[3],seg[4]),data);
						else result=callback(procs[2],point(pts,0,cx,cy),point(pts,8,seg[1],seg[2]),point(pts,16,seg[3],seg[4]),data);
						cx=seg[3];cy=seg[4];break;
					case java.awt.geom.PathIterator.SEG_CUBICTO:
						if(cubic)result=callback(procs[2],point(pts,0,seg[1],seg[2]),point(pts,8,seg[3],seg[4]),point(pts,16,seg[5],seg[6]),data);
						else for(double[] q:cubicToQuads(cx,cy,seg)){result=callback(procs[2],point(pts,0,q[0],q[1]),point(pts,8,q[2],q[3]),point(pts,16,q[4],q[5]),data);if(result!=NO_ERR)break;}
						cx=seg[5];cy=seg[6];break;
					default: // close
						if(cx!=sx||cy!=sy){result=line(cubic,procs,pts,data,cx,cy,sx,sy);if(result!=NO_ERR)break;cx=sx;cy=sy;}
						result=callback(procs[3],data);break;
				}
				if(result!=NO_ERR)break;
			}
		}finally{memory.free(pts);}
		writeLongIf(resultOut,result);return NO_ERR;
	}
	private int line(boolean cubic,int[] procs,int pts,int data,double x0,double y0,double x1,double y1) throws M68kException {
		return cubic?callback(procs[1],point(pts,0,x1,y1),data):callback(procs[1],point(pts,0,x0,y0),point(pts,8,x1,y1),data);
	}
	/** Writes a Float32Point {x, y} at pts+at and returns its address. */
	private int point(int pts,int at,double x,double y) throws M68kCpuException {memory.writeLong(pts+at,floatBits(x));memory.writeLong(pts+at+4,floatBits(y));return pts+at;}
	/** A cubic from (x0,y0) split at its middle, each half as one quadratic {start, control, end}. */
	private static double[][] cubicToQuads(double x0,double y0,double[] seg){
		double x1=seg[1],y1=seg[2],x2=seg[3],y2=seg[4],x3=seg[5],y3=seg[6];
		double ax=(x0+x1)/2,ay=(y0+y1)/2,bx=(x1+x2)/2,by=(y1+y2)/2,cx=(x2+x3)/2,cy=(y2+y3)/2,abx=(ax+bx)/2,aby=(ay+by)/2,bcx=(bx+cx)/2,bcy=(by+cy)/2,mx=(abx+bcx)/2,my=(aby+bcy)/2;
		return new double[][]{{x0,y0,(3*(ax+abx)-(x0+mx))/4,(3*(ay+aby)-(y0+my))/4,mx,my},{mx,my,(3*(bcx+cx)-(mx+x3))/4,(3*(bcy+cy)-(my+y3))/4,x3,y3}};
	}
	/**
	 * ATSUGlyphGetCurvePaths' ATSUCurvePaths: {contours, then per contour {vectors, controlBits[(vectors+31)/32],
	 * Float32Point vector[vectors]}} - quadratic contours whose set control bits (most significant bit first) mark
	 * off-curve points, closed implicitly.
	 */
	private byte[] curvePaths(java.awt.Font f,int id){
		List<List<double[]>> contours=new ArrayList<List<double[]>>();List<double[]> c=null;double cx=0,cy=0;
		for(double[] seg:outline(f,id)){
			switch((int)seg[0]){
				case java.awt.geom.PathIterator.SEG_MOVETO:c=new ArrayList<double[]>();contours.add(c);c.add(new double[]{seg[1],seg[2],0});cx=seg[1];cy=seg[2];break;
				case java.awt.geom.PathIterator.SEG_LINETO:c.add(new double[]{seg[1],seg[2],0});cx=seg[1];cy=seg[2];break;
				case java.awt.geom.PathIterator.SEG_QUADTO:c.add(new double[]{seg[1],seg[2],1});c.add(new double[]{seg[3],seg[4],0});cx=seg[3];cy=seg[4];break;
				case java.awt.geom.PathIterator.SEG_CUBICTO:for(double[] q:cubicToQuads(cx,cy,seg)){c.add(new double[]{q[2],q[3],1});c.add(new double[]{q[4],q[5],0});}cx=seg[5];cy=seg[6];break;
				default:if(c!=null&&c.size()>1){double[] a=c.get(0),z=c.get(c.size()-1);if(z[2]==0&&a[0]==z[0]&&a[1]==z[1])c.remove(c.size()-1);}break;
			}
		}
		ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(bytes32(contours.size()));
		for(List<double[]> k:contours){
			out.writeBytes(bytes32(k.size()));int[] bits=new int[(k.size()+31)/32];
			for(int i=0;i<k.size();i++)if(k.get(i)[2]!=0)bits[i>>5]|=1<<(31-(i&31));
			for(int b:bits)out.writeBytes(bytes32(b));
			for(double[] v:k){out.writeBytes(bytes32(floatBits(v[0])));out.writeBytes(bytes32(floatBits(v[1])));}
		}
		return out.toByteArray();
	}

	private void defineGlyphs(PpcLibraries l){
		// ATSUGetGlyphInfo(layout, lineStart, lineLength, *ioBufferSize, ATSUGlyphInfoArray*): {layout, numGlyphs, ATSUGlyphInfo {glyphID,
		// reserved, layoutFlags, charIndex, style, Float32 deltaY, Float32 idealX, SInt16 screenX, SInt16 caretX}[]} - one glyph per
		// character, x from the line origin as ATSUDrawText places them; a NULL array asks for the size
		l.register(null,"ATSUGetGlyphInfo",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}int sizeP=arg(3),out=arg(4);if(sizeP==0){ret(PARAM_ERR);return;}
			int n=span[1]-span[0],need=8+GLYPH_INFO_SIZE*n;
			if(out==0){memory.writeLong(sizeP,need);ret(NO_ERR);return;}
			int fit=Math.max(0,Math.min(n,(memory.readLong(sizeP)-8)/GLYPH_INFO_SIZE));if(memory.readLong(sizeP)<8){memory.writeLong(sizeP,need);ret(BUFFER_TOO_SMALL);return;}
			int[] ids=glyphIds(t,span[0],span[1]);int[] x=new int[n];
			Session s=enter(false);
			try{for(int[] ln:lines(t,span[0],span[1])){Line line=lay(t,ln[0],ln[1]);for(int i=ln[0];i<ln[1];i++)x[i-span[0]]=line.x[i-ln[0]];}}finally{leave(s);}
			memory.writeLong(out,arg(0));memory.writeLong(out+4,fit);
			for(int i=0;i<fit;i++){int g=out+8+GLYPH_INFO_SIZE*i,at=span[0]+i;int st=at<t.styles.length?t.styles[at]:0;Style style=style(st);char ch=charAt(t,at);
				int flags=(space(ch)||hardBreak(ch)?GLYPH_IS_WHITE_SPACE:0)|(style!=null&&style.intValue(266,0)!=0?GLYPH_HAS_IMPOSED_WIDTH:0)|(Character.isHighSurrogate(ch)&&ids[i]!=DELETED_GLYPH?4:2); // kATSGlyphInfoByteSizeMask: the bytes of text behind the glyph
				memory.writeWord(g,ids[i]);memory.writeWord(g+2,0);memory.writeLong(g+4,flags);memory.writeLong(g+8,at);memory.writeLong(g+12,st);
				memory.writeLong(g+16,floatBits(style==null?0:-style.intValue(269,0)/65536.0));memory.writeLong(g+20,floatBits(x[i]/65536.0));memory.writeWord(g+24,round(x[i]));memory.writeWord(g+26,round(x[i]));}
			memory.writeLong(sizeP,8+GLYPH_INFO_SIZE*fit);ret(NO_ERR);});
		// ATSUDrawGlyphInfo(ATSUGlyphInfoArray*, Float32Point location by value): each glyph's character drawn in its style at
		// location + (idealX, deltaY), so positions a program adjusted are honoured (QuickDraw draws characters, not glyph IDs)
		l.register(null,"ATSUDrawGlyphInfo",(r,c)->{int a=arg(0);if(a==0){ret(PARAM_ERR);return;}Layout t=layout(memory.readLong(a));if(t==null){ret(INVALID_LAYOUT);return;}
			float x0=Float.intBitsToFloat(arg(1)),y0=Float.intBitsToFloat(arg(2));int n=memory.readLong(a+4);
			Session s=enter(true);if(s==null){ret(NO_ERR);return;}
			try{for(int i=0;i<n;i++){int g=a+8+GLYPH_INFO_SIZE*i;if(memory.readWord(g)==DELETED_GLYPH)continue;int at=memory.readLong(g+8);if(at<t.offset||at>=t.end())continue;
				char ch=charAt(t,at);if(hardBreak(ch)||ch<0x20&&ch!=9)continue;
				String str=Character.isHighSurrogate(ch)&&at+1<t.end()?""+ch+charAt(t,at+1):ch==9?" ":String.valueOf(ch);
				Style st=style(memory.readLong(g+12));apply(st,true);
				drawBytes(text.encode(text.systemEncoding(),str),(int)Math.round((x0+Float.intBitsToFloat(memory.readLong(g+20)))*65536.0),(int)Math.round((y0+Float.intBitsToFloat(memory.readLong(g+16)))*65536.0));}}
			finally{leave(s);}
			ret(NO_ERR);});
		// metrics: (style, count, GlyphID glyphs[], ByteOffset stride between them (0: packed), out[]) - ATSGlyphIdealMetrics {advance,
		// sideBearing, otherSideBearing} (Float32Points) and ATSGlyphScreenMetrics {deviceAdvance, topLeft, UInt32 height, width,
		// sideBearing, otherSideBearing} (with two Booleans for anti-aliasing before the array: the host decides that)
		l.register(null,"ATSUGlyphGetIdealMetrics",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}int n=arg(1),ids=arg(2),stride=arg(3),out=arg(4);if(n>0&&(ids==0||out==0)){ret(PARAM_ERR);return;}
			java.awt.Font f=glyphFont(st);
			for(int i=0;i<n;i++){java.awt.font.GlyphMetrics m=glyph(f,memory.readWord(glyphAt(ids,stride,i))).getGlyphMetrics(0);int o=out+24*i;
				memory.writeLong(o,floatBits(m.getAdvanceX()));memory.writeLong(o+4,floatBits(m.getAdvanceY()));memory.writeLong(o+8,floatBits(m.getLSB()));memory.writeLong(o+12,0);memory.writeLong(o+16,floatBits(m.getRSB()));memory.writeLong(o+20,0);}
			ret(NO_ERR);});
		l.register(null,"ATSUGlyphGetScreenMetrics",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}int n=arg(1),ids=arg(2),stride=arg(3),out=arg(6);if(n>0&&(ids==0||out==0)){ret(PARAM_ERR);return;}
			java.awt.Font f=glyphFont(st);
			for(int i=0;i<n;i++){java.awt.font.GlyphVector gv=glyph(f,memory.readWord(glyphAt(ids,stride,i)));java.awt.Rectangle b=gv.getGlyphPixelBounds(0,GLYPH_FRC,0,0);
				int advance=Math.round(gv.getGlyphMetrics(0).getAdvanceX());int o=out+40*i;
				memory.writeLong(o,floatBits(advance));memory.writeLong(o+4,0);memory.writeLong(o+8,floatBits(b.x));memory.writeLong(o+12,floatBits(b.y));
				memory.writeLong(o+16,b.height);memory.writeLong(o+20,b.width);memory.writeLong(o+24,floatBits(b.x));memory.writeLong(o+28,0);memory.writeLong(o+32,floatBits(advance-(b.x+b.width)));memory.writeLong(o+36,0);}
			ret(NO_ERR);});
		l.register(null,"ATSUGetNativeCurveType",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}if(arg(1)==0){ret(PARAM_ERR);return;}memory.writeWord(arg(1),curveType(glyphFont(st)));ret(NO_ERR);});
		// outlines: (style, glyph, four callbacks, callbackData, *callbackResult); ATSUGlyphGetCurvePaths(style, glyph, *ioBufferSize, ATSUCurvePaths*)
		l.register(null,"ATSUGlyphGetQuadraticPaths",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}ret(walkOutline(glyphFont(st),arg(1)&0xffff,false,new int[]{arg(2),arg(3),arg(4),arg(5)},arg(6),arg(7)));});
		l.register(null,"ATSUGlyphGetCubicPaths",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}ret(walkOutline(glyphFont(st),arg(1)&0xffff,true,new int[]{arg(2),arg(3),arg(4),arg(5)},arg(6),arg(7)));});
		l.register(null,"ATSUGlyphGetCurvePaths",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}int sizeP=arg(2),out=arg(3);if(sizeP==0){ret(PARAM_ERR);return;}
			byte[] b=curvePaths(glyphFont(st),arg(1)&0xffff);int room=memory.readLong(sizeP);memory.writeLong(sizeP,b.length);
			if(out==0){ret(NO_ERR);return;}if(room<b.length){ret(BUFFER_TOO_SMALL);return;}memory.load(out,b);ret(NO_ERR);});
		// the outline callbacks' UPPs are TVECTOR_UPP_TYPE: the routine itself; Invoke takes the UPP after the callback's arguments
		String[][] upps={{"ATSQuadraticLine","3"},{"ATSQuadraticCurve","4"},{"ATSQuadraticNewPath","1"},{"ATSQuadraticClosePath","1"},
			{"ATSCubicMoveTo","2"},{"ATSCubicLineTo","2"},{"ATSCubicCurveTo","4"},{"ATSCubicClosePath","1"}};
		for(String[] u:upps){final int n=Integer.parseInt(u[1]);
			l.register(null,"New"+u[0]+"UPP",(r,c)->ret(arg(0)));
			l.register(null,"Dispose"+u[0]+"UPP",(r,c)->{});
			l.register(null,"Invoke"+u[0]+"UPP",(r,c)->{int[] a=new int[n];for(int i=0;i<n;i++)a[i]=arg(i);ret(arg(n)==0?PARAM_ERR:callback(arg(n),a));});}
		// tab stops: ATSUSetTabArray(layout, ATSUTab tabs[] {Fixed position, UInt16 type} (6 bytes each), count) - none clears them;
		// ATSUGetTabArray(layout, maxCount, tabs[], *count)
		l.register(null,"ATSUSetTabArray",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int tabs=arg(1),n=arg(2);
			List<int[]> list=new ArrayList<int[]>();
			for(int i=0;i<n&&tabs!=0;i++){int pos=memory.readLong(tabs+6*i),type=memory.readWord(tabs+6*i+4);if(type>3||pos<0){ret(INVALID_VALUE);return;}list.add(new int[]{pos,type});}
			list.sort((a,b)->Integer.compare(a[0],b[0]));t.tabs=list;ret(NO_ERR);});
		l.register(null,"ATSUGetTabArray",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int max=arg(1),out=arg(2);
			for(int i=0;i<Math.min(max,t.tabs.size())&&out!=0;i++){memory.writeLong(out+6*i,t.tabs.get(i)[0]);memory.writeWord(out+6*i+4,t.tabs.get(i)[1]);}
			writeLongIf(arg(3),t.tabs.size());ret(NO_ERR);});
		// style groups (ATSUnicodeDirectAccess.h: ATSUGetStyleGroup(style, ATSStyleGroupPtr*), ATSUDisposeStyleGroup(group)): an opaque
		// snapshot of the style for the direct-access calls; nothing here reads it back
		l.register(null,"ATSUGetStyleGroup",(r,c)->{Style st=style(arg(0));if(st==null){ret(INVALID_STYLE);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			StyleGroup g=new StyleGroup();g.style.copyFrom(st);memory.writeLong(arg(1),newObject(g));ret(NO_ERR);});
		l.register(null,"ATSUDisposeStyleGroup",(r,c)->{if(!(objects.get(arg(0)) instanceof StyleGroup)){ret(PARAM_ERR);return;}dispose(arg(0));ret(NO_ERR);});
		// CarbonLib-private entry points of the glyph-vector layer ATSUI is built on (ATSGlyphVector and friends, ATSUGetStyleIndex,
		// ATSUConvertCharToGlyphs, ATSULayoutParagraph, ATSULoadGlyphVector): no header documents their parameters or the
		// ATSGlyphVector layout, and this ATSUI lays text out through QuickDraw without one - unimpErr, nothing written
		for(String name:new String[]{"ATSInitializeGlyphVector","ATSClearGlyphVector","ATSGrowGlyphVector","ATSLayoutText","ATSApplyBaselines","ATSApplyDevicePositioning",
			"ATSApplyJustification","ATSApplyKerning","ATSApplyMorph","ATSApplyPositions","ATSApplyTracking","ATSCalculateBiDiLevels","ATSDrawGlyphs","ATSGetStyleTransform",
			"ATSSetStyleTransform","ATSUGetStyleIndex","ATSUConvertCharToGlyphs","ATSULayoutParagraph","ATSULoadGlyphVector"})
			l.register(null,name,(r,c)->ret(UNIMP_ERR));
		// Fonts.h (10.x): FMGetFontContainerFromFontFamilyInstance(family, style, size, FSSpec*) - the suitcase the family comes from;
		// FMGetFontFamilyResource(family, style, size, bufferSize, buffer, *size) - its 'FOND'
		l.register(null,"FMGetFontContainerFromFontFamilyInstance",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;}if(arg(3)==0){ret(PARAM_ERR);return;}
			if(f.file==null){ret(FM_CONTAINER_ACCESS);return;}ret(rt.toolbox.files.fileManager.writeFSSpec(arg(3),f.file)==0?NO_ERR:FM_CONTAINER_ACCESS);});
		l.register(null,"FMGetFontFamilyResource",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;}
			byte[] d=fondData(f);if(d==null){ret(FM_INVALID_FAMILY);writeLongIf(arg(5),0);return;}
			if(arg(4)!=0)memory.load(arg(4),java.util.Arrays.copyOf(d,Math.min(d.length,Math.max(0,arg(3)))));writeLongIf(arg(5),d.length);ret(NO_ERR);});
	}
	/** An ATSStyleGroupPtr: a copy of the style it was made from. */
	private static final class StyleGroup {final Style style=new Style();}
	/** A family's 'FOND' as the Resource Manager finds it (the program's chain first, then the family's suitcase), or null. */
	private byte[] fondData(Family f) throws M68kException {
		byte[] d=null;int h=rt.toolbox("GetResource",FOND,f.id);if(h!=0&&memory.readLong(h)!=0)d=handleBytes(h);
		if(d==null&&f.resources!=null){ResourceFile68k.Resource res=f.resources.get(FOND,f.id);if(res!=null)d=res.data();}
		return d;
	}

	// ------------------------------------------------------------------ definitions

	void define(PpcLibraries l){
		text=l.textServices;text.atsui=this;
		defineStyles(l);defineLayouts(l);defineDrawing(l);defineFonts(l);defineFontManager(l);defineAts(l);defineGlyphs(l);
	}

	private void defineStyles(PpcLibraries l){
		l.register(null,"ATSUCreateStyle",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),newObject(new Style()));ret(NO_ERR);});
		l.register(null,"ATSUCreateAndCopyStyle",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}Style n=new Style();n.copyFrom(s);n.refCon=s.refCon;memory.writeLong(arg(1),newObject(n));ret(NO_ERR);});
		l.register(null,"ATSUDisposeStyle",(r,c)->{if(style(arg(0))==null){ret(INVALID_STYLE);return;}dispose(arg(0));ret(NO_ERR);});
		l.register(null,"ATSUSetStyleRefCon",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}s.refCon=arg(1);ret(NO_ERR);});
		l.register(null,"ATSUGetStyleRefCon",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}writeLongIf(arg(1),s.refCon);ret(NO_ERR);});
		l.register(null,"ATSUCompareStyles",(r,c)->{Style a=style(arg(0)),b=style(arg(1));if(a==null||b==null){ret(INVALID_STYLE);return;}
			boolean ab=a.in(b),ba=b.in(a);writeWordIf(arg(2),ab&&ba?2:ba?1:ab?3:0);ret(NO_ERR);}); // kATSUStyleEquals / Contains / ContainedBy / Unequal
		l.register(null,"ATSUCopyAttributes",(r,c)->{Style a=style(arg(0)),b=style(arg(1));if(a==null||b==null){ret(INVALID_STYLE);return;}b.copyFrom(a);ret(NO_ERR);});
		l.register(null,"ATSUOverwriteAttributes",(r,c)->{Style a=style(arg(0)),b=style(arg(1));if(a==null||b==null){ret(INVALID_STYLE);return;}b.overwrite(a);ret(NO_ERR);});
		l.register(null,"ATSUUnderwriteAttributes",(r,c)->{Style a=style(arg(0)),b=style(arg(1));if(a==null||b==null){ret(INVALID_STYLE);return;}b.underwrite(a);ret(NO_ERR);});
		l.register(null,"ATSUClearStyle",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}s.attributes.clear();s.features.clear();s.variations.clear();ret(NO_ERR);});
		l.register(null,"ATSUStyleIsEmpty",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}writeByteIf(arg(1),s.empty()?1:0);ret(NO_ERR);});
		l.register(null,"ATSUSetAttributes",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}ret(setTagged(s.attributes,false,arg(1),arg(2),arg(3),arg(4)));});
		l.register(null,"ATSUGetAttribute",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}ret(getTagged(s.attributes,arg(1),arg(2),arg(3),arg(4)));});
		l.register(null,"ATSUGetAllAttributes",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}ret(getAllTagged(s.attributes,arg(1),arg(2),arg(3)));});
		l.register(null,"ATSUClearAttributes",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}ret(clearTagged(s.attributes,arg(1),arg(2)));});
		// baseline deltas (BslnBaselineRecord, 32 Fixed) of the style's font, relative to the class asked for: Roman 0,
		// ideographic centre, ideographic low, hanging, math - from the QuickDraw ascent and descent
		l.register(null,"ATSUCalculateBaselineDeltas",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}int out=arg(2);if(out==0){ret(PARAM_ERR);return;}
			Session session=enter(false);int[] info;try{apply(s,false);info=fontInfo();}finally{leave(session);}
			int a=info[0]<<16,d=info[1]<<16;int[] v=new int[32];v[1]=(a-d)/2;v[2]=-d;v[3]=a*4/5;v[4]=a/2;int base=arg(1)&0xff;int ref=base<32?v[base]:0;
			for(int i=0;i<32;i++)memory.writeLong(out+4*i,i<5?v[i]-ref:0);ret(NO_ERR);});
		// font features (type, selector as UInt16 arrays) and variations (axis, Fixed value)
		l.register(null,"ATSUSetFontFeatures",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}
			for(int i=0;i<arg(1);i++){int type=memory.readWord(arg(2)+2*i),sel=memory.readWord(arg(3)+2*i);s.features.put(type<<16|(sel&0xfffe),sel);}ret(NO_ERR);});
		l.register(null,"ATSUGetFontFeature",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}int i=arg(1);if(i<0||i>=s.features.size()){ret(NOT_SET);return;}
			Map.Entry<Integer,Integer> e=new ArrayList<Map.Entry<Integer,Integer>>(s.features.entrySet()).get(i);writeWordIf(arg(2),e.getKey()>>>16);writeWordIf(arg(3),e.getValue());ret(NO_ERR);});
		l.register(null,"ATSUGetAllFontFeatures",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}int i=0;
			for(Map.Entry<Integer,Integer> e:s.features.entrySet()){if(i<arg(1)){writeWordIf(arg(2)==0?0:arg(2)+2*i,e.getKey()>>>16);writeWordIf(arg(3)==0?0:arg(3)+2*i,e.getValue());}i++;}
			writeLongIf(arg(4),i);ret(NO_ERR);});
		l.register(null,"ATSUClearFontFeatures",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}if(arg(1)==ALL){s.features.clear();ret(NO_ERR);return;}
			for(int i=0;i<arg(1);i++){int type=memory.readWord(arg(2)+2*i),sel=memory.readWord(arg(3)+2*i);
				if(sel==0xffff)s.features.keySet().removeIf(k->(k>>>16)==type);else s.features.remove(type<<16|(sel&0xfffe));} // kATSUNoSelector: every selector of the type
			ret(NO_ERR);});
		l.register(null,"ATSUSetVariations",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}for(int i=0;i<arg(1);i++)s.variations.put(memory.readLong(arg(2)+4*i),memory.readLong(arg(3)+4*i));ret(NO_ERR);});
		l.register(null,"ATSUGetFontVariationValue",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}Integer v=s.variations.get(arg(1));writeLongIf(arg(2),v==null?0:v);ret(v==null?NOT_SET:NO_ERR);});
		l.register(null,"ATSUGetAllFontVariations",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}int i=0;
			for(Map.Entry<Integer,Integer> e:s.variations.entrySet()){if(i<arg(1)){writeLongIf(arg(2)==0?0:arg(2)+4*i,e.getKey());writeLongIf(arg(3)==0?0:arg(3)+4*i,e.getValue());}i++;}
			writeLongIf(arg(4),i);ret(NO_ERR);});
		l.register(null,"ATSUClearFontVariations",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}if(arg(1)==ALL)s.variations.clear();else for(int i=0;i<arg(1);i++)s.variations.remove(memory.readLong(arg(2)+4*i));ret(NO_ERR);});
		// style data in a handle, and style runs as a 'ustl' stream
		l.register(null,"ATSUCopyToHandle",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}if(arg(1)==0){ret(PARAM_ERR);return;}ret(setHandleBytes(arg(1),flatten(s)));});
		l.register(null,"ATSUPasteFromHandle",(r,c)->{Style s=style(arg(0));if(s==null){ret(INVALID_STYLE);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			Style p=unflatten(handleBytes(arg(1)),0);if(p==null){ret(INVALID_CACHE);return;}s.copyFrom(p);ret(NO_ERR);});
		// ATSUFlattenStyleRunsToStream(format, options, runCount, ATSUStyleRunInfo runs[] {length, styleIndex}, styleCount, styles[], bufferSize, buffer, *actualSize)
		// stream: {version 2, size, offsetToTextLayouts 0, offsetToStyleRuns, offsetToStyleList}, runs {count, runs...}, styles {count, flattened styles...}
		l.register(null,"ATSUFlattenStyleRunsToStream",(r,c)->{if(arg(0)!=USTL){ret(UNSUPPORTED_STREAM);return;}
			int nRuns=arg(2),runs=arg(3),nStyles=arg(4),styles=arg(5),size=arg(6),buffer=arg(7);
			ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(new byte[20]);
			int runsAt=out.size();out.writeBytes(bytes32(nRuns));for(int i=0;i<nRuns;i++){out.writeBytes(bytes32(memory.readLong(runs+8*i)));out.writeBytes(bytes32(memory.readLong(runs+8*i+4)));}
			int stylesAt=out.size();out.writeBytes(bytes32(nStyles));
			for(int i=0;i<nStyles;i++){Style s=style(memory.readLong(styles+4*i));if(s==null){ret(INVALID_STYLE);return;}out.writeBytes(flatten(s));}
			byte[] b=out.toByteArray();System.arraycopy(bytes32(2),0,b,0,4);System.arraycopy(bytes32(b.length),0,b,4,4);System.arraycopy(bytes32(runsAt),0,b,12,4);System.arraycopy(bytes32(stylesAt),0,b,16,4);
			writeLongIf(arg(8),b.length);
			if(buffer!=0){if(size<b.length){ret(BUFFER_TOO_SMALL);return;}memory.load(buffer,b);}
			ret(NO_ERR);});
		// ATSUUnflattenStyleRunsFromStream(format, options, bufferSize, buffer, maxRuns, maxStyles, runs[], styles[] (new ATSUStyles), *runCount, *styleCount)
		l.register(null,"ATSUUnflattenStyleRunsFromStream",(r,c)->{if(arg(0)!=USTL){ret(UNSUPPORTED_STREAM);return;}
			int size=arg(2),buffer=arg(3),maxRuns=arg(4),maxStyles=arg(5),runs=arg(6),styles=arg(7);if(buffer==0||size<20){ret(BAD_STREAM);return;}
			byte[] b=memory.readBytes(buffer,size);if(be32(b,0)!=2||be32(b,4)>size){ret(BAD_STREAM);return;}
			int runsAt=be32(b,12),stylesAt=be32(b,16);if(runsAt<20||runsAt+4>size||stylesAt<20||stylesAt+4>size){ret(BAD_STREAM);return;}
			int nRuns=be32(b,runsAt),nStyles=be32(b,stylesAt);if(nRuns<0||runsAt+4+8L*nRuns>size||nStyles<0){ret(BAD_STREAM);return;}
			List<Style> parsed=new ArrayList<Style>();int at=stylesAt+4;
			for(int i=0;i<nStyles;i++){Style s=unflatten(b,at);if(s==null){ret(BAD_STREAM);return;}parsed.add(s);at+=be32(b,at);}
			if(runs!=0)for(int i=0;i<Math.min(nRuns,maxRuns);i++){memory.writeLong(runs+8*i,be32(b,runsAt+4+8*i));memory.writeLong(runs+8*i+4,be32(b,runsAt+8+8*i));}
			if(styles!=0)for(int i=0;i<Math.min(nStyles,maxStyles);i++)memory.writeLong(styles+4*i,newObject(parsed.get(i)));
			writeLongIf(arg(8),nRuns);writeLongIf(arg(9),nStyles);ret(NO_ERR);});
		// memory settings: ATSUI's allocations are the host's here, so any setting is accepted and remembered
		l.register(null,"ATSUCreateMemorySetting",(r,c)->{if(arg(2)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(2),newObject(new MemorySetting()));ret(NO_ERR);});
		l.register(null,"ATSUSetCurrentMemorySetting",(r,c)->{if(arg(0)!=0&&!(objects.get(arg(0)) instanceof MemorySetting)){ret(PARAM_ERR);return;}currentMemorySetting=arg(0);ret(NO_ERR);});
		l.register(null,"ATSUGetCurrentMemorySetting",(r,c)->ret(currentMemorySetting));
		l.register(null,"ATSUDisposeMemorySetting",(r,c)->{if(!(objects.get(arg(0)) instanceof MemorySetting)){ret(PARAM_ERR);return;}if(currentMemorySetting==arg(0))currentMemorySetting=0;dispose(arg(0));ret(NO_ERR);});
	}
	private int currentMemorySetting;
	private int[] fallbackFonts=new int[0];private int fallbackMethod;

	private void defineLayouts(PpcLibraries l){
		// (text, offset, length, total, runCount, runLengths[], styles[], *layout); the handle form takes a UniCharArrayHandle
		l.register(null,"ATSUCreateTextLayoutWithTextPtr",(r,c)->{if(arg(7)==0){ret(PARAM_ERR);return;}Layout t=new Layout();setText(t,arg(0),false,arg(1),arg(2),arg(3));
			applyRuns(t,t.offset,arg(5),arg(6),arg(4));memory.writeLong(arg(7),newObject(t));ret(NO_ERR);});
		l.register(null,"ATSUCreateTextLayoutWithTextHandle",(r,c)->{if(arg(7)==0){ret(PARAM_ERR);return;}Layout t=new Layout();setText(t,arg(0),true,arg(1),arg(2),arg(3));
			applyRuns(t,t.offset,arg(5),arg(6),arg(4));memory.writeLong(arg(7),newObject(t));ret(NO_ERR);});
		l.register(null,"ATSUCreateTextLayout",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),newObject(new Layout()));ret(NO_ERR);});
		l.register(null,"ATSUCreateAndCopyTextLayout",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}memory.writeLong(arg(1),newObject(t.copy()));ret(NO_ERR);});
		l.register(null,"ATSUDisposeTextLayout",(r,c)->{if(layout(arg(0))==null){ret(INVALID_LAYOUT);return;}dispose(arg(0));ret(NO_ERR);});
		l.register(null,"ATSUSetTextLayoutRefCon",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}t.refCon=arg(1);ret(NO_ERR);});
		l.register(null,"ATSUGetTextLayoutRefCon",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}writeLongIf(arg(1),t.refCon);ret(NO_ERR);});
		l.register(null,"ATSUSetTextPointerLocation",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}setText(t,arg(1),false,arg(2),arg(3),arg(4));ret(NO_ERR);});
		l.register(null,"ATSUSetTextHandleLocation",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}setText(t,arg(1),true,arg(2),arg(3),arg(4));ret(NO_ERR);});
		l.register(null,"ATSUGetTextLocation",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, void**, Boolean* isHandle, *offset, *length, *total)
			writeLongIf(arg(1),t.text);writeByteIf(arg(2),t.handle?1:0);writeLongIf(arg(3),t.offset);writeLongIf(arg(4),t.length);writeLongIf(arg(5),t.total);ret(NO_ERR);});
		l.register(null,"ATSUTextMoved",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}if(arg(1)==0){ret(PARAM_ERR);return;}t.text=arg(1);t.handle=false;ret(NO_ERR);});
		l.register(null,"ATSUTextInserted",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // the inserted characters take the style before them
			int at=arg(1),n=arg(2);if(at<t.offset||at>t.end()||n<0){ret(INVALID_RANGE);return;}
			int fill=t.styles.length==0?0:t.styles[Math.max(0,Math.min(at>t.offset?at-1:at,t.styles.length-1))];
			int[] s=new int[t.styles.length+n];System.arraycopy(t.styles,0,s,0,at);java.util.Arrays.fill(s,at,at+n,fill);System.arraycopy(t.styles,at,s,at+n,t.styles.length-at);
			t.styles=s;t.length+=n;t.total+=n;shiftLines(t,at,n);ret(NO_ERR);});
		l.register(null,"ATSUTextDeleted",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}
			int at=arg(1)==ALL?t.offset:arg(1),n=arg(2)==ALL?t.end()-at:arg(2);if(at<t.offset||n<0||at+n>t.end()){ret(INVALID_RANGE);return;}
			int[] s=new int[t.styles.length-n];System.arraycopy(t.styles,0,s,0,at);System.arraycopy(t.styles,at+n,s,at,t.styles.length-at-n);
			t.styles=s;t.length-=n;t.total-=n;t.softBreaks.removeIf(b->b>at&&b<=at+n);t.lineControls.keySet().removeIf(b->b>at&&b<=at+n);shiftLines(t,at+n,-n);ret(NO_ERR);});
		l.register(null,"ATSUSetRunStyle",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}if(arg(1)!=0&&style(arg(1))==null){ret(INVALID_STYLE);return;}
			int[] span=range(t,arg(2),arg(3));if(span==null){ret(INVALID_RANGE);return;}for(int i=span[0];i<span[1]&&i<t.styles.length;i++)t.styles[i]=arg(1);ret(NO_ERR);});
		l.register(null,"ATSUGetRunStyle",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, offset, *style, *runStart, *runLength)
			int at=arg(1);if(at<t.offset||at>=Math.max(t.end(),t.offset+1)||at>=t.styles.length){ret(INVALID_RANGE);return;}
			int s=t.styles[at],a=at,b=at+1;while(a>t.offset&&t.styles[a-1]==s)a--;while(b<t.end()&&t.styles[b]==s)b++;
			writeLongIf(arg(2),s);writeLongIf(arg(3),a);writeLongIf(arg(4),b-a);ret(NO_ERR);});
		l.register(null,"ATSUGetContinuousAttributes",(r,c)->{Layout t=layout(arg(0));Style out=style(arg(3));if(t==null){ret(INVALID_LAYOUT);return;}if(out==null){ret(INVALID_STYLE);return;}
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}
			out.attributes.clear();out.features.clear();out.variations.clear();
			List<int[]> rs=runs(t,span[0],Math.max(span[1],Math.min(span[0]+1,t.styles.length)));if(rs.isEmpty()){ret(NO_ERR);return;}
			Style first=style(rs.get(0)[2]);if(first==null){ret(NO_ERR);return;}out.copyFrom(first);
			for(int[] run:rs){Style s=style(run[2]);if(s==null){out.copyFrom(new Style());break;}
				out.attributes.entrySet().removeIf(e->!java.util.Arrays.equals(e.getValue(),s.attributes.get(e.getKey())));
				out.features.entrySet().retainAll(s.features.entrySet());out.variations.entrySet().retainAll(s.variations.entrySet());}
			ret(NO_ERR);});
		// layout controls (the whole layout) and line controls (per line start)
		l.register(null,"ATSUSetLayoutControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(setTagged(t.controls,true,arg(1),arg(2),arg(3),arg(4)));});
		l.register(null,"ATSUGetLayoutControl",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(getTagged(t.controls,arg(1),arg(2),arg(3),arg(4)));});
		l.register(null,"ATSUGetAllLayoutControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(getAllTagged(t.controls,arg(1),arg(2),arg(3)));});
		l.register(null,"ATSUClearLayoutControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(clearTagged(t.controls,arg(1),arg(2)));});
		l.register(null,"ATSUCopyLayoutControls",(r,c)->{Layout a=layout(arg(0)),b=layout(arg(1));if(a==null||b==null){ret(INVALID_LAYOUT);return;}b.controls.clear();b.controls.putAll(a.controls);ret(NO_ERR);});
		l.register(null,"ATSUSetLineControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}
			LinkedHashMap<Integer,byte[]> lc=t.lineControls.computeIfAbsent(arg(1),k->new LinkedHashMap<Integer,byte[]>());ret(setTagged(lc,true,arg(2),arg(3),arg(4),arg(5)));});
		l.register(null,"ATSUGetLineControl",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(getTagged(t.lineControls.get(arg(1)),arg(2),arg(3),arg(4),arg(5)));});
		l.register(null,"ATSUGetAllLineControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(getAllTagged(t.lineControls.get(arg(1)),arg(2),arg(3),arg(4)));});
		l.register(null,"ATSUClearLineControls",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(clearTagged(t.lineControls.get(arg(1)),arg(2),arg(3)));});
		l.register(null,"ATSUCopyLineControls",(r,c)->{Layout a=layout(arg(0)),b=layout(arg(2));if(a==null||b==null){ret(INVALID_LAYOUT);return;}
			LinkedHashMap<Integer,byte[]> from=a.lineControls.get(arg(1));if(from==null)b.lineControls.remove(arg(3));else b.lineControls.put(arg(3),new LinkedHashMap<Integer,byte[]>(from));ret(NO_ERR);});
		// soft line breaks
		l.register(null,"ATSUSetSoftLineBreak",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int b=arg(1);if(b<=t.offset||b>t.end()){ret(INVALID_RANGE);return;}if(b<t.end())t.softBreaks.add(b);ret(NO_ERR);});
		l.register(null,"ATSUGetSoftLineBreaks",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, start, length, max, breaks[], *count)
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}int i=0;
			for(int b:t.softBreaks.subSet(span[0],false,span[1],true)){if(i<arg(3)&&arg(4)!=0)memory.writeLong(arg(4)+4*i,b);i++;}
			writeLongIf(arg(5),i);ret(NO_ERR);});
		l.register(null,"ATSUClearSoftLineBreaks",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}t.softBreaks.subSet(span[0],true,span[1],true).clear();ret(NO_ERR);});
		l.register(null,"ATSUBreakLine",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, lineStart, Fixed width, useAsSoftBreak, *lineBreak)
			int start=arg(1)==ALL?t.offset:arg(1);if(start<t.offset||start>t.end()){ret(INVALID_RANGE);return;}
			int[] b=breakLine(t,start,lineWidthFor(t,start,arg(2)));if((arg(3)&0xff)!=0&&b[0]<t.end())t.softBreaks.add(b[0]);writeLongIf(arg(4),b[0]);ret(b[1]);});
		l.register(null,"ATSUBatchBreakLines",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, start, length, Fixed width, *breakCount)
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}t.softBreaks.subSet(span[0],false,span[1],true).clear();int n=0;
			for(int at=span[0];at<span[1];){int b=breakLine(t,at,lineWidthFor(t,at,arg(3)))[0];if(b<=at)b=at+1;if(b>=span[1])break;t.softBreaks.add(b);n++;at=b;}
			writeLongIf(arg(4),n);ret(NO_ERR);});
		// cursor movement: (layout, oldOffset, movementType, *newOffset)
		for(String name:new String[]{"ATSUNextCursorPosition","ATSURightwardCursorPosition","ATSUPreviousCursorPosition","ATSULeftwardCursorPosition"}){
			boolean forward=name.contains("Next")||name.contains("Right");
			l.register(null,name,(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int from=arg(1);if(from<t.offset||from>t.end()){ret(INVALID_RANGE);return;}
				writeLongIf(arg(3),cursor(t,from,arg(2)&0xffff,forward));ret(NO_ERR);});
		}
		l.register(null,"ATSUSetTransientFontMatching",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}t.transientMatching=(arg(1)&0xff)!=0;ret(NO_ERR);});
		l.register(null,"ATSUGetTransientFontMatching",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}writeByteIf(arg(1),t.transientMatching?1:0);ret(NO_ERR);});
		// font matching: QuickDraw's text drawing already falls back per character, so the assigned fonts cover the range
		l.register(null,"ATSUMatchFontsToText",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}
			Style s=span[0]<t.styles.length?style(t.styles[span[0]]):null;writeLongIf(arg(3),s==null?defaultFont():s.intValue(FONT_TAG,defaultFont()));writeLongIf(arg(4),span[1]);writeLongIf(arg(5),0);ret(NO_ERR);});
		l.register(null,"ATSUClearLayoutCache",(r,c)->ret(layout(arg(0))==null?INVALID_LAYOUT:NO_ERR));
		l.register(null,"ATSUIdle",(r,c)->ret(layout(arg(0))==null?INVALID_LAYOUT:NO_ERR));
		// font fallbacks: the global list and ATSUFontFallbacks objects (kept; QuickDraw's own fallback does the work)
		l.register(null,"ATSUSetFontFallbacks",(r,c)->{int n=arg(0);int[] f=new int[Math.max(0,n)];for(int i=0;i<f.length;i++){f[i]=memory.readLong(arg(1)+4*i);if(!fontExists(f[i])){ret(INVALID_FONT);return;}}
			fallbackFonts=f;fallbackMethod=arg(2)&0xffff;ret(NO_ERR);});
		l.register(null,"ATSUGetFontFallbacks",(r,c)->{for(int i=0;i<Math.min(arg(0),fallbackFonts.length)&&arg(1)!=0;i++)memory.writeLong(arg(1)+4*i,fallbackFonts[i]);
			writeWordIf(arg(2),fallbackMethod);writeLongIf(arg(3),fallbackFonts.length);ret(NO_ERR);});
		l.register(null,"ATSUCreateFontFallbacks",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),newObject(new Fallbacks()));ret(NO_ERR);});
		l.register(null,"ATSUDisposeFontFallbacks",(r,c)->{if(!(objects.get(arg(0)) instanceof Fallbacks)){ret(INVALID_FALLBACKS);return;}dispose(arg(0));ret(NO_ERR);});
		l.register(null,"ATSUSetObjFontFallbacks",(r,c)->{Object o=objects.get(arg(0));if(!(o instanceof Fallbacks)){ret(INVALID_FALLBACKS);return;}
			int[] f=new int[Math.max(0,arg(1))];for(int i=0;i<f.length;i++){f[i]=memory.readLong(arg(2)+4*i);if(!fontExists(f[i])){ret(INVALID_FONT);return;}}((Fallbacks)o).fonts=f;((Fallbacks)o).method=arg(3)&0xffff;ret(NO_ERR);});
		l.register(null,"ATSUGetObjFontFallbacks",(r,c)->{Object o=objects.get(arg(0));if(!(o instanceof Fallbacks)){ret(INVALID_FALLBACKS);return;}Fallbacks f=(Fallbacks)o;
			for(int i=0;i<Math.min(arg(1),f.fonts.length)&&arg(2)!=0;i++)memory.writeLong(arg(2)+4*i,f.fonts[i]);writeWordIf(arg(3),f.method);writeLongIf(arg(4),f.fonts.length);ret(NO_ERR);});
	}
	/** Soft breaks and line controls after {@code at} move by {@code n} (text inserted or deleted). */
	private static void shiftLines(Layout t,int at,int n){
		TreeSet<Integer> b=new TreeSet<Integer>();for(int x:t.softBreaks)b.add(x>at||n<0&&x>=at?x+n:x);t.softBreaks.clear();for(int x:b)if(x>t.offset&&x<t.end())t.softBreaks.add(x);
		Map<Integer,LinkedHashMap<Integer,byte[]>> lc=new HashMap<Integer,LinkedHashMap<Integer,byte[]>>();for(Map.Entry<Integer,LinkedHashMap<Integer,byte[]>> e:t.lineControls.entrySet())lc.put(e.getKey()>at||n<0&&e.getKey()>=at?e.getKey()+n:e.getKey(),e.getValue());
		t.lineControls.clear();t.lineControls.putAll(lc);
	}

	/**
	 * ATSUPositionToOffset / ATSUPositionToCursorOffset: the offset nearest the Fixed {@code x} on the line starting at
	 * *ioOffset (the layout's first line when it is not given). {@code type} -1: any character boundary (a surrogate pair
	 * stays whole); a movement type: the nearer of the type's cursor positions around it.
	 */
	private int positionToOffset(Layout t,int x,int ioOffset,int leadingOut,int secondaryOut,int type) throws M68kException {
		int start=ioOffset!=0?memory.readLong(ioOffset):t.offset;if(start<t.offset||start>t.end())start=t.offset;int[] ln=line(t,start);
		Session s=enter(false);Line line;try{line=lay(t,ln[0],ln[1]);}finally{leave(s);}
		int n=ln[1]-ln[0];while(n>0&&hardBreak(charAt(t,ln[0]+n-1)))n--; // a click past the end lands before the line's break
		int at=ln[0]+n;boolean leading=false;
		for(int i=0;i<n;i++){int mid=(line.x[i]+line.x[i+1])/2;if(x<line.x[i+1]){at=ln[0]+i+(x<mid?0:1);leading=x<mid;break;}}
		if(at<ln[1]&&at>ln[0]&&Character.isLowSurrogate(charAt(t,at)))at--;
		if(type>=0){int before=snap(t,at,type,false),after=snap(t,at,type,true);
			if(before!=at){if(after>ln[0]+n)after=before;int xb=line.x[Math.max(0,before-ln[0])],xa=line.x[Math.min(n,after-ln[0])];at=Math.abs(x-xb)<=Math.abs(xa-x)?before:after;leading=at==after;}}
		writeLongIf(ioOffset,at);writeByteIf(leadingOut,leading?1:0);writeLongIf(secondaryOut,at);return NO_ERR;
	}
	/** ATSUOffsetToPosition / ATSUOffsetToCursorPosition: carets {x, y, deltaX, deltaY} (Fixed, from the line origin) at an offset ({@code type} >= 0: snapped back to that movement type's cursor position). */
	private int offsetToPosition(Layout t,int offset,int main,int second,int split,int type) throws M68kException {
		if(offset<t.offset||offset>t.end())return INVALID_RANGE;
		if(type>=0)offset=snap(t,offset,type,false);
		int[] ln=line(t,offset);
		Session s=enter(false);Line line;try{line=lay(t,ln[0],ln[1]);}finally{leave(s);}
		int x=line.x[offset-ln[0]];
		for(int caret:new int[]{main,second})if(caret!=0){memory.writeLong(caret,x);memory.writeLong(caret+4,-line.m.ascent<<16);memory.writeLong(caret+8,x);memory.writeLong(caret+12,line.m.descent<<16);}
		writeByteIf(split,0);return NO_ERR;
	}
	/**
	 * The cursor position of movement type {@code type} (kATSUByCharacter 0, kATSUByTypographicCluster 1, kATSUByWord 2,
	 * kATSUByCluster 3) at or before ({@code forward}: at or after) {@code offset}.
	 */
	private int snap(Layout t,int offset,int type,boolean forward) throws M68kCpuException {
		String s=chars(t,t.offset,t.end());int i=Math.max(0,Math.min(s.length(),offset-t.offset));
		if(type==0){if(i>0&&i<s.length()&&Character.isLowSurrogate(s.charAt(i))&&Character.isHighSurrogate(s.charAt(i-1)))i+=forward?1:-1;return t.offset+i;}
		BreakIterator it=type==2?BreakIterator.getWordInstance():BreakIterator.getCharacterInstance();it.setText(s);
		if(it.isBoundary(i))return t.offset+i;
		int j=forward?it.following(i):it.preceding(i);return t.offset+(j==BreakIterator.DONE?(forward?s.length():0):j);
	}

	private void defineDrawing(PpcLibraries l){
		l.register(null,"ATSUDrawText",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, lineStart, lineLength, Fixed x, Fixed y): the baseline origin
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}ret(draw(t,span[0],span[1],arg(3),arg(4)));});
		l.register(null,"ATSUMeasureText",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, lineStart, lineLength, *before, *after, *ascent, *descent): typographic, one line
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}
			Session s=enter(false);Measure m;int[] adv;try{m=measure(t,span[0],span[1]);adv=m.advance.clone();tabbed(t,span[0],adv);}finally{leave(s);}
			int width=0;for(int w:adv)width+=w;
			writeLongIf(arg(3),0);writeLongIf(arg(4),width);writeLongIf(arg(5),m.ascent<<16);writeLongIf(arg(6),m.descent<<16);ret(NO_ERR);});
		l.register(null,"ATSUMeasureTextImage",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, lineStart, lineLength, Fixed x, Fixed y, Rect*)
			int[] span=range(t,arg(1),arg(2));if(span==null){ret(INVALID_RANGE);return;}int x=arg(3),y=arg(4),out=arg(5);
			int top=Integer.MAX_VALUE,left=Integer.MAX_VALUE,bottom=Integer.MIN_VALUE,right=Integer.MIN_VALUE;int baseline=round(y),previousDescent=0;boolean first=true;
			Session s=enter(false);
			try{for(int[] ln:lines(t,span[0],span[1])){Line line=lay(t,ln[0],ln[1]);if(!first)baseline+=previousDescent+line.m.leading+line.m.ascent;first=false;previousDescent=line.m.descent;
				top=Math.min(top,baseline-line.m.ascent);bottom=Math.max(bottom,baseline+line.m.descent);left=Math.min(left,round(x+line.x[0]));right=Math.max(right,round(x+line.x[0]+line.width));}}
			finally{leave(s);}
			if(out!=0){memory.writeWord(out,top);memory.writeWord(out+2,left);memory.writeWord(out+4,bottom);memory.writeWord(out+6,right);}ret(NO_ERR);});
		l.register(null,"ATSUGetGlyphBounds",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, Fixed x, Fixed y, start, length, boundsType, max, ATSTrapezoid[], *count): one trapezoid per style run of the line
			int[] span=range(t,arg(3),arg(4));if(span==null){ret(INVALID_RANGE);return;}int x=arg(1),y=arg(2),max=arg(6),out=arg(7);
			int[] ln=line(t,span[0]);int end=Math.min(span[1],ln[1]);Session s=enter(false);List<int[]> boxes=new ArrayList<int[]>();
			try{Line line=lay(t,ln[0],ln[1]);for(int[] run:runs(t,span[0],Math.max(end,span[0]))){apply(style(run[2]),false);int[] info=fontInfo();
				boxes.add(new int[]{x+line.x[run[0]-ln[0]],x+line.x[run[1]-ln[0]],y-(info[0]<<16),y+(info[1]<<16)});}}
			finally{leave(s);}
			for(int i=0;i<Math.min(max,boxes.size())&&out!=0;i++){int[] b=boxes.get(i);int p=out+32*i; // upperLeft, upperRight, lowerRight, lowerLeft
				memory.writeLong(p,b[0]);memory.writeLong(p+4,b[2]);memory.writeLong(p+8,b[1]);memory.writeLong(p+12,b[2]);memory.writeLong(p+16,b[1]);memory.writeLong(p+20,b[3]);memory.writeLong(p+24,b[0]);memory.writeLong(p+28,b[3]);}
			writeLongIf(arg(8),boxes.size());ret(NO_ERR);});
		l.register(null,"ATSUPositionToOffset",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, Fixed x, Fixed y, *ioOffset (in: the line start), *isLeading, *secondary)
			ret(positionToOffset(t,arg(1),arg(3),arg(4),arg(5),-1));});
		l.register(null,"ATSUOffsetToPosition",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;} // (layout, offset, isLeading, *main, *second, *isSplit): caret {x, y, deltaX, deltaY} from the line origin
			ret(offsetToPosition(t,arg(1),arg(3),arg(4),arg(5),-1));});
		// the same at cursor positions of a movement type (ATSUnicode 10.2): (layout, x, y, movementType, *ioPrimaryOffset, *isLeading, *secondary)
		// and (layout, offset, isLeading, movementType, *mainCaret, *secondCaret, *caretIsSplit)
		l.register(null,"ATSUPositionToCursorOffset",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(positionToOffset(t,arg(1),arg(4),arg(5),arg(6),arg(3)&0xffff));});
		l.register(null,"ATSUOffsetToCursorPosition",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}ret(offsetToPosition(t,arg(1),arg(4),arg(5),arg(6),arg(3)&0xffff));});
		// highlighting: the range's rectangle on the line containing its start, from the line origin (x, y)
		l.register(null,"ATSUHighlightText",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int[] span=range(t,arg(3),arg(4));if(span==null){ret(INVALID_RANGE);return;}
			if(currentPort()==0){ret(NO_ERR);return;}
			int p=rect(highlightRect(t,span[0],span[1],arg(1),arg(2)));memory.writeByte(0x938,memory.readByte(0x938)&0x7f);rt.toolbox("InvertRect",p);memory.free(p);ret(NO_ERR);}); // HiliteMode: the highlight colour
		l.register(null,"ATSUUnhighlightText",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int[] span=range(t,arg(3),arg(4));if(span==null){ret(INVALID_RANGE);return;}
			if(currentPort()==0){ret(NO_ERR);return;}
			int[] box=highlightRect(t,span[0],span[1],arg(1),arg(2));int p=rect(box);int saved=rt.toolbox("NewRgn"),clip=rt.toolbox("NewRgn");
			rt.toolbox("GetClip",saved);rt.toolbox("RectRgn",clip,p);rt.toolbox("SectRgn",saved,clip,clip);rt.toolbox("EraseRect",p);rt.toolbox("SetClip",clip); // erase, then redraw the line inside the rectangle
			try{int[] ln=line(t,span[0]);draw(t,ln[0],ln[1],arg(1),arg(2));}finally{rt.toolbox("SetClip",saved);rt.toolbox("DisposeRgn",saved);rt.toolbox("DisposeRgn",clip);memory.free(p);}
			ret(NO_ERR);});
		l.register(null,"ATSUGetTextHighlight",(r,c)->{Layout t=layout(arg(0));if(t==null){ret(INVALID_LAYOUT);return;}int[] span=range(t,arg(3),arg(4));if(span==null){ret(INVALID_RANGE);return;}
			if(arg(5)==0){ret(PARAM_ERR);return;}int p=rect(highlightRect(t,span[0],span[1],arg(1),arg(2)));rt.toolbox("RectRgn",arg(5),p);memory.free(p);ret(NO_ERR);});
	}

	private void defineFonts(PpcLibraries l){
		l.register(null,"ATSUFontCount",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(0),fonts().size());ret(NO_ERR);});
		l.register(null,"ATSUGetFontIDs",(r,c)->{List<Integer> f=fonts();for(int i=0;i<Math.min(arg(1),f.size())&&arg(0)!=0;i++)memory.writeLong(arg(0)+4*i,f.get(i));writeLongIf(arg(2),f.size());ret(NO_ERR);});
		l.register(null,"ATSUFONDtoFontID",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(NO_CORRESPONDING_FONT);return;} // this ATSUI numbers its fonts like their FONDs
			int style=arg(1)&0xff;writeLongIf(arg(2),fontOf(f.id,f.styles().contains(style)?style:0));ret(NO_ERR);});
		l.register(null,"ATSUFontIDtoFOND",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeWordIf(arg(1),arg(0)&0xffff);writeWordIf(arg(2),(arg(0)>>>16)&0xff);ret(NO_ERR);});
		l.register(null,"ATSUCountFontNames",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeLongIf(arg(1),names(arg(0)).size());ret(NO_ERR);});
		// ATSUGetIndFontName(font, index, maxLength, name, *actualLength, *code, *platform, *script, *language)
		l.register(null,"ATSUGetIndFontName",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}List<FontName> n=names(arg(0));int i=arg(1);if(i<0||i>=n.size()){ret(PARAM_ERR);return;}
			FontName f=n.get(i);if(arg(3)!=0)memory.load(arg(3),java.util.Arrays.copyOf(f.bytes,Math.min(f.bytes.length,Math.max(0,arg(2)))));
			writeLongIf(arg(4),f.bytes.length);writeLongIf(arg(5),f.code);writeLongIf(arg(6),f.platform);writeLongIf(arg(7),f.script);writeLongIf(arg(8),f.language);ret(NO_ERR);});
		// ATSUFindFontName(font, code, platform, script, language, maxLength, name, *actualLength, *index): kFontNo... (-1) matches anything
		l.register(null,"ATSUFindFontName",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}List<FontName> n=names(arg(0));
			for(int i=0;i<n.size();i++){FontName f=n.get(i);if(!matches(f,arg(1),arg(2),arg(3),arg(4)))continue;
				if(arg(6)!=0)memory.load(arg(6),java.util.Arrays.copyOf(f.bytes,Math.min(f.bytes.length,Math.max(0,arg(5)))));writeLongIf(arg(7),f.bytes.length);writeLongIf(arg(8),i);ret(NO_ERR);return;}
			ret(NOT_SET);});
		// ATSUFindFontFromName(name, length, code, platform, script, language, *font): the bytes as the record has them, else the same text
		l.register(null,"ATSUFindFontFromName",(r,c)->{if(arg(0)==0||arg(6)==0){ret(PARAM_ERR);return;}byte[] want=memory.readBytes(arg(0),Math.max(0,arg(1)));
			int platform=arg(3),script=arg(4);
			String wanted=platform==0||platform==3?new String(want,StandardCharsets.UTF_16BE):text.decode(platform==1&&script!=ALL?script:text.systemEncoding(),want);
			for(int pass=0;pass<2;pass++)for(int font:fonts())for(FontName f:names(font))
				if(matches(f,arg(2),platform,script,arg(5))&&(pass==0?java.util.Arrays.equals(f.bytes,want):nameText(f).equalsIgnoreCase(wanted))){memory.writeLong(arg(6),font);ret(NO_ERR);return;}
			memory.writeLong(arg(6),0);ret(INVALID_FONT);});
		// variations, instances, features and tracking from the font's 'fvar', 'feat' and 'trak' tables (none for bitmap and host fonts)
		l.register(null,"ATSUCountFontVariations",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeLongIf(arg(1),variations(arg(0)).axes.size());ret(NO_ERR);});
		l.register(null,"ATSUGetIndFontVariation",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}List<int[]> a=variations(arg(0)).axes;int i=arg(1);if(i<0||i>=a.size()){ret(PARAM_ERR);return;}
			writeLongIf(arg(2),a.get(i)[0]);writeLongIf(arg(3),a.get(i)[1]);writeLongIf(arg(4),a.get(i)[3]);writeLongIf(arg(5),a.get(i)[2]);ret(NO_ERR);}); // (font, index, *axis, *min, *max, *default)
		l.register(null,"ATSUGetFontVariationNameCode",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}for(int[] a:variations(arg(0)).axes)if(a[0]==arg(1)){writeLongIf(arg(2),a[4]);ret(NO_ERR);return;}ret(INVALID_VALUE);});
		l.register(null,"ATSUCountFontInstances",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeLongIf(arg(1),variations(arg(0)).instances.size());ret(NO_ERR);});
		l.register(null,"ATSUGetFontInstance",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}Variations v=variations(arg(0));int i=arg(1);if(i<0||i>=v.instances.size()){ret(PARAM_ERR);return;}
			int[] inst=v.instances.get(i);int n=inst.length-1; // (font, index, max, axes[], values[], *count)
			for(int k=0;k<Math.min(n,arg(2));k++){writeLongIf(arg(3)==0?0:arg(3)+4*k,v.axes.get(k)[0]);writeLongIf(arg(4)==0?0:arg(4)+4*k,inst[1+k]);}
			writeLongIf(arg(5),n);ret(NO_ERR);});
		l.register(null,"ATSUGetFontInstanceNameCode",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}Variations v=variations(arg(0));int i=arg(1);if(i<0||i>=v.instances.size()){ret(PARAM_ERR);return;}writeLongIf(arg(2),v.instances.get(i)[0]);ret(NO_ERR);});
		l.register(null,"ATSUCountFontFeatureTypes",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeLongIf(arg(1),features(arg(0)).size());ret(NO_ERR);});
		l.register(null,"ATSUGetFontFeatureTypes",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}List<int[]> f=features(arg(0));
			for(int i=0;i<Math.min(arg(1),f.size())&&arg(2)!=0;i++)memory.writeWord(arg(2)+2*i,f.get(i)[0]);writeLongIf(arg(3),f.size());ret(NO_ERR);});
		l.register(null,"ATSUCountFontFeatureSelectors",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}int[] f=feature(arg(0),arg(1)&0xffff);if(f==null){ret(INVALID_VALUE);return;}writeLongIf(arg(2),(f.length-4)/2);ret(NO_ERR);});
		// ATSUGetFontFeatureSelectors(font, type, max, selectors[], Boolean onByDefault[], *count, *isExclusive)
		l.register(null,"ATSUGetFontFeatureSelectors",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}int[] f=feature(arg(0),arg(1)&0xffff);if(f==null){ret(INVALID_VALUE);return;}int n=(f.length-4)/2;
			for(int k=0;k<Math.min(n,arg(2));k++){writeWordIf(arg(3)==0?0:arg(3)+2*k,f[4+k]);writeByteIf(arg(4)==0?0:arg(4)+k,f[2]==1?(k==f[3]?1:0):(f[4+k]&1)==0&&k==f[3]?1:0);}
			writeLongIf(arg(5),n);writeByteIf(arg(6),f[2]);ret(NO_ERR);});
		l.register(null,"ATSUGetFontFeatureNameCode",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}int[] f=feature(arg(0),arg(1)&0xffff);if(f==null){ret(INVALID_VALUE);return;}
			int sel=arg(2)&0xffff;if(sel==0xffff){writeLongIf(arg(3),f[1]);ret(NO_ERR);return;}int n=(f.length-4)/2; // kATSUNoSelector: the type's own name
			for(int k=0;k<n;k++)if(f[4+k]==sel){writeLongIf(arg(3),f[4+n+k]);ret(NO_ERR);return;}ret(INVALID_VALUE);});
		l.register(null,"ATSUCountFontTracking",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}writeLongIf(arg(2),tracks(arg(0),(arg(1)&0xffff)!=0).size());ret(NO_ERR);});
		l.register(null,"ATSUGetIndFontTracking",(r,c)->{if(!fontExists(arg(0))){ret(INVALID_FONT);return;}List<int[]> t=tracks(arg(0),(arg(1)&0xffff)!=0);int i=arg(2);if(i<0||i>=t.size()){ret(PARAM_ERR);return;}
			writeLongIf(arg(3),t.get(i)[0]);writeLongIf(arg(4),t.get(i)[1]);ret(NO_ERR);});
	}
	private int[] feature(int font,int type){for(int[] f:features(font))if(f[0]==type)return f;return null;}

	private void defineFontManager(PpcLibraries l){
		// iterators: (filter, refCon, options, *iterator); FMGetNext... returns kFMIterationCompleted at the end
		l.register(null,"FMCreateFontFamilyIterator",(r,c)->ret(familyIteration(arg(0),arg(1),arg(3))));
		l.register(null,"FMResetFontFamilyIterator",(r,c)->ret(familyIteration(arg(0),arg(1),arg(3))));
		l.register(null,"FMCreateFontIterator",(r,c)->ret(fontIteration(arg(0),arg(1),arg(3))));
		l.register(null,"FMResetFontIterator",(r,c)->ret(fontIteration(arg(0),arg(1),arg(3))));
		l.register(null,"FMCreateFontFamilyInstanceIterator",(r,c)->ret(instanceIteration((short)arg(0),arg(1))));
		l.register(null,"FMResetFontFamilyInstanceIterator",(r,c)->ret(instanceIteration((short)arg(0),arg(1))));
		for(String name:new String[]{"FMDisposeFontFamilyIterator","FMDisposeFontIterator","FMDisposeFontFamilyInstanceIterator"})
			l.register(null,name,(r,c)->{if(iteration(arg(0))==null){ret(PARAM_ERR);return;}iterations.remove(memory.readLong(arg(0)));memory.writeLong(arg(0)+4,0);ret(NO_ERR);});
		l.register(null,"FMGetNextFontFamily",(r,c)->{Iteration it=iteration(arg(0));if(it==null){ret(PARAM_ERR);return;}if(it.index>=it.items.size()){ret(FM_ITERATION_COMPLETED);return;}
			writeWordIf(arg(1),it.items.get(it.index++)[0]);ret(NO_ERR);});
		l.register(null,"FMGetNextFont",(r,c)->{Iteration it=iteration(arg(0));if(it==null){ret(PARAM_ERR);return;}if(it.index>=it.items.size()){ret(FM_ITERATION_COMPLETED);return;}
			writeLongIf(arg(1),it.items.get(it.index++)[0]);ret(NO_ERR);});
		l.register(null,"FMGetNextFontFamilyInstance",(r,c)->{Iteration it=iteration(arg(0));if(it==null){ret(PARAM_ERR);return;}if(it.index>=it.items.size()){ret(FM_ITERATION_COMPLETED);return;} // (*it, *font, *style, *size)
			int[] i=it.items.get(it.index++);writeLongIf(arg(1),i[0]);writeWordIf(arg(2),i[1]);writeWordIf(arg(3),i[2]);ret(NO_ERR);});
		// families
		l.register(null,"FMGetFontFamilyFromName",(r,c)->{String name=rt.toolbox.readPascal(arg(0));
			for(Family f:families())if(f.name.equalsIgnoreCase(name)){ret(f.id);return;}
			int id=Fonts68k.numberOf(name);ret(id!=0&&Fonts68k.nameOf(id).equalsIgnoreCase(name)?id:-1);}); // kInvalidFontFamily
		l.register(null,"FMGetFontFamilyName",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			byte[] b=text.encode(familyEncoding(f),f.name);int n=Math.min(255,b.length);memory.writeByte(arg(1),n);memory.load(arg(1)+1,java.util.Arrays.copyOf(b,n));ret(NO_ERR);});
		l.register(null,"FMGetFontFamilyTextEncoding",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;}writeLongIf(arg(1),familyEncoding(f));ret(NO_ERR);});
		l.register(null,"FMGetFontFamilyGeneration",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;}writeLongIf(arg(1),f.generation);ret(NO_ERR);});
		l.register(null,"FMGetGeneration",(r,c)->ret(generation));
		// fonts: an FMFont is family | style << 16 (the ATSUFontID of the same font)
		l.register(null,"FMGetFontFromFontFamilyInstance",(r,c)->{Family f=family((short)arg(0));if(f==null){ret(FM_INVALID_FAMILY);return;} // (family, style, *font, *intrinsicStyle)
			int style=arg(1)&0xff;int intrinsic=f.styles().contains(style)?style:(style&1)!=0&&f.styles().contains(1)?1:(style&2)!=0&&f.styles().contains(2)?2:0;
			writeLongIf(arg(2),fontOf(f.id,intrinsic));writeWordIf(arg(3),intrinsic);ret(NO_ERR);});
		l.register(null,"FMGetFontFamilyInstanceFromFont",(r,c)->{if(!fontExists(arg(0))){ret(FM_INVALID_FONT);return;}writeWordIf(arg(1),arg(0)&0xffff);writeWordIf(arg(2),(arg(0)>>>16)&0xff);ret(NO_ERR);});
		l.register(null,"FMGetFontGeneration",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(FM_INVALID_FONT);return;}writeLongIf(arg(1),f.generation);ret(NO_ERR);});
		l.register(null,"FMGetFontFormat",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(FM_INVALID_FONT);return;} // 'true' with an sfnt, 'bitm' for bitmaps and host substitutes
			writeLongIf(arg(1),f.sfnt((arg(0)>>>16)&0xff)!=null?TRUE_TYPE:BITMAP);ret(NO_ERR);});
		l.register(null,"FMGetFontContainer",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(FM_INVALID_FONT);return;}if(f.file==null){ret(FM_CONTAINER_ACCESS);return;}
			if(arg(1)==0){ret(PARAM_ERR);return;}int err=rt.toolbox.files.fileManager.writeFSSpec(arg(1),f.file);ret(err==0?NO_ERR:FM_CONTAINER_ACCESS);});
		// sfnt access: (font, length, buffer, *actual) - the offset table and directory; (font, tag, offset, length, buffer, *actual) - one table
		l.register(null,"FMGetFontTableDirectory",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(FM_INVALID_FONT);return;}
			byte[] s=f.sfnt((arg(0)>>>16)&0xff);if(s==null||s.length<12){ret(FONT_NOT_OUTLINE);return;}int n=Math.min(s.length,12+16*be16(s,4));
			if(arg(2)!=0)memory.load(arg(2),java.util.Arrays.copyOf(s,Math.min(n,Math.max(0,arg(1)))));writeLongIf(arg(3),n);ret(NO_ERR);});
		l.register(null,"FMGetFontTable",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(FM_INVALID_FONT);return;}
			byte[] s=f.sfnt((arg(0)>>>16)&0xff);if(s==null){ret(FONT_NOT_OUTLINE);return;}byte[] t=table(s,arg(1));if(t==null){ret(FM_TABLE_ACCESS);return;}
			int off=Math.max(0,arg(2)),len=arg(4)==0?t.length-off:Math.min(Math.max(0,arg(3)),t.length-off);if(off>t.length){ret(PARAM_ERR);return;}
			if(arg(4)!=0)memory.load(arg(4),java.util.Arrays.copyOfRange(t,off,off+Math.max(0,len)));writeLongIf(arg(5),Math.max(0,len));ret(NO_ERR);});
		l.register(null,"FMActivateFonts",(r,c)->ret(activate(arg(0))));
		l.register(null,"FMDeactivateFonts",(r,c)->ret(deactivate(arg(0))));
	}
	private int familyIteration(int filter,int refCon,int record) throws M68kException {
		if(record==0)return PARAM_ERR;if(filter!=0&&memory.readLong(filter)!=0)return PARAM_ERR; // kFMCurrentFilterFormat
		Iteration it=new Iteration();for(Family f:families())if(passes(filter,refCon,f,-1))it.items.add(new int[]{f.id});
		startIteration(record,it);return NO_ERR;
	}
	private int fontIteration(int filter,int refCon,int record) throws M68kException {
		if(record==0)return PARAM_ERR;if(filter!=0&&memory.readLong(filter)!=0)return PARAM_ERR;
		Iteration it=new Iteration();for(Family f:families())for(int s:f.styles()){int font=fontOf(f.id,s);if(passes(filter,refCon,f,font))it.items.add(new int[]{font});}
		startIteration(record,it);return NO_ERR;
	}
	/** A family's instances {font, style, size}: its FOND's association entries (size 0: outlines), or one scalable plain font. */
	private int instanceIteration(int family,int record) throws M68kException {
		if(record==0)return PARAM_ERR;Family f=family(family);if(f==null)return FM_INVALID_FAMILY;
		Iteration it=new Iteration();for(int[] a:f.association)it.items.add(new int[]{fontOf(f.id,a[1]),a[1],a[0]});
		if(it.items.isEmpty())it.items.add(new int[]{fontOf(f.id,0),0,0});
		startIteration(record,it);return NO_ERR;
	}

	// ---- ATS (ATSFont.h): the same fonts, an ATSFontRef being the FMFont and an ATSFontFamilyRef the family number

	/** Containers ATSFontActivateFromFileSpecification handed out: token -> suitcase. */
	private final Map<Integer,File> containers=new HashMap<Integer,File>();
	private int nextContainer=1;
	/** ATSFontIterator / ATSFontFamilyIterator objects: guest blocks keyed to their iteration. */
	private final Map<Integer,Iteration> atsIterators=new HashMap<Integer,Iteration>();
	/** Whether a family (font -1) or a font passes an ATSFontFilter {version, selector, value}. */
	private boolean atsPasses(int filter,int refCon,Family f,int font) throws M68kException {
		if(filter==0)return true;
		int selector=memory.readLong(filter+4),value=memory.readLong(filter+8);
		switch(selector){
			case 3:return f.generation==value;                                                               // kATSFontFilterSelectorGeneration
			case 7:return f.id==(short)value;                                                                // kATSFontFilterSelectorFontFamily
			case 8:return rt.cpu.call(value,new int[]{f.id,refCon},null)==NO_ERR;                            // family applier (a C function)
			case 9:return font==-1||rt.cpu.call(value,new int[]{font,refCon},null)==NO_ERR;                  // font applier
			default:return true;
		}
	}
	private Iteration atsIteration(int filter,int refCon,boolean fontsToo) throws M68kException {
		Iteration it=new Iteration();
		for(Family f:families()){
			if(!fontsToo){if(atsPasses(filter,refCon,f,-1))it.items.add(new int[]{f.id});continue;}
			for(int s:f.styles()){int font=fontOf(f.id,s);if(atsPasses(filter,refCon,f,font))it.items.add(new int[]{font});}
		}
		return it;
	}
	/** ATS...IteratorCreate / Reset (context, filter, refCon, options, *iterator). */
	private int atsIterator(boolean fonts,boolean reset) throws M68kException {
		int out=arg(4);if(out==0)return PARAM_ERR;int filter=arg(1);if(filter!=0&&memory.readLong(filter)!=0)return PARAM_ERR; // kATSFontFilterCurrentVersion
		Iteration it=atsIteration(filter,arg(2),fonts);int ref=reset?memory.readLong(out):0;
		if(ref==0||!atsIterators.containsKey(ref)){ref=rt.allocate(8,4);memory.writeLong(out,ref);}
		atsIterators.put(ref,it);return NO_ERR;
	}
	/** A font's name of {@code code} (the full name 4, the PostScript name 6) as text; the family name composes the rest. */
	private String fontName(int font,int code){
		for(FontName n:names(font))if(n.code==code&&(n.platform==1||n.platform==0||n.platform==3))return nameText(n);
		Family f=family(font&0xffff);return f==null?null:f.name;
	}
	/** Metrics of a 1-point font (ATSFontMetrics: version, then 14 Float32), from QuickDraw at 100 points. */
	private int fontMetrics(int font,int out,boolean vertical) throws M68kException {
		if(!fontExists(font)||out==0)return fontExists(font)?PARAM_ERR:-982; // kATSInvalidFontAccess
		Session s=enter(false);float[] v=new float[14];
		try{
			rt.toolbox("TextFont",(short)font);rt.toolbox("TextSize",100);rt.toolbox("TextFace",(font>>>16)&0xff);int[] info=fontInfo();
			byte[] letters="abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);int p=rt.allocate(32,2);memory.load(p,letters);int avg=(short)rt.toolbox("TextWidth",p,0,26);memory.free(p);
			float ascent=info[0]/100f,descent=info[1]/100f,leading=info[3]/100f,max=info[2]/100f;
			if(vertical){v[0]=0.5f;v[1]=-0.5f;v[2]=0;v[3]=ascent+descent;v[4]=ascent+descent;}
			else{v[0]=ascent;v[1]=-descent;v[2]=leading;v[3]=avg/2600f;v[4]=max;}
			v[5]=0;v[6]=0;v[7]=v[4]/10;v[8]=v[4]/12;v[9]=ascent*0.72f;v[10]=ascent*0.5f;v[11]=((font>>>16)&2)!=0?-12f:0f;v[12]=-descent/2;v[13]=0.05f;
		}finally{leave(s);}
		memory.writeLong(out,1);for(int i=0;i<14;i++)memory.writeLong(out+4+4*i,Float.floatToIntBits(v[i]));
		return NO_ERR;
	}

	private void defineAts(PpcLibraries l){
		l.register(null,"ATSGetGeneration",(r,c)->ret(generation));
		// (FSSpec*, context, format, reserved, options, *container)
		l.register(null,"ATSFontActivateFromFileSpecification",(r,c)->{int err=activate(arg(0));if(err!=NO_ERR){ret(err);return;}
			File file=specFile(arg(0));int ref=nextContainer++;containers.put(ref,file);writeLongIf(arg(5),ref);ret(NO_ERR);});
		l.register(null,"ATSFontActivateFromMemory",(r,c)->{writeLongIf(arg(6),0);ret(-985);}); // kATSInvalidFontContainerAccess: fonts draw through QuickDraw from files only
		l.register(null,"ATSFontDeactivate",(r,c)->{File file=containers.remove(arg(0));if(file==null){ret(-985);return;}
			int spec=rt.allocate(80,2);try{rt.toolbox.files.fileManager.writeFSSpec(spec,file);deactivate(spec);ret(NO_ERR);}finally{memory.free(spec);}}); // a suitcase of the Fonts folder stays active
		l.register(null,"ATSFontFamilyApplyFunction",(r,c)->{for(Family f:families()){int err=rt.cpu.call(arg(0),new int[]{f.id,arg(1)},null);if(err!=NO_ERR){ret(err);return;}}ret(NO_ERR);});
		l.register(null,"ATSFontApplyFunction",(r,c)->{for(int font:fonts()){int err=rt.cpu.call(arg(0),new int[]{font,arg(1)},null);if(err!=NO_ERR){ret(err);return;}}ret(NO_ERR);});
		l.register(null,"ATSFontFamilyIteratorCreate",(r,c)->ret(atsIterator(false,false)));
		l.register(null,"ATSFontFamilyIteratorReset",(r,c)->ret(atsIterator(false,true)));
		l.register(null,"ATSFontIteratorCreate",(r,c)->ret(atsIterator(true,false)));
		l.register(null,"ATSFontIteratorReset",(r,c)->ret(atsIterator(true,true)));
		for(String name:new String[]{"ATSFontFamilyIteratorNext","ATSFontIteratorNext"})
			l.register(null,name,(r,c)->{Iteration it=atsIterators.get(arg(0));if(it==null){ret(PARAM_ERR);return;}if(it.index>=it.items.size()){ret(-980);return;} // kATSIterationCompleted
				writeLongIf(arg(1),it.items.get(it.index++)[0]);ret(NO_ERR);});
		for(String name:new String[]{"ATSFontFamilyIteratorRelease","ATSFontIteratorRelease"})
			l.register(null,name,(r,c)->{int p=arg(0),ref=p==0?0:memory.readLong(p);if(atsIterators.remove(ref)==null){ret(PARAM_ERR);return;}memory.free(ref);memory.writeLong(p,0);ret(NO_ERR);});
		// families
		l.register(null,"ATSFontFamilyFindFromName",(r,c)->{String name=text.cfText(arg(0));if(name!=null)for(Family f:families())if(f.name.equalsIgnoreCase(name)){ret(f.id);return;}ret(0);});
		l.register(null,"ATSFontFamilyFindFromQuickDrawName",(r,c)->{String name=rt.toolbox.readPascal(arg(0));for(Family f:families())if(f.name.equalsIgnoreCase(name)){ret(f.id);return;}ret(0);});
		l.register(null,"ATSFontFamilyGetGeneration",(r,c)->{Family f=family(arg(0));ret(f==null?0:f.generation);});
		l.register(null,"ATSFontFamilyGetEncoding",(r,c)->{Family f=family(arg(0));ret(f==null?0xffff:familyEncoding(f));}); // kTextEncodingUnknown
		l.register(null,"ATSFontFamilyGetName",(r,c)->{Family f=family(arg(0));if(f==null){ret(-981);return;}if(arg(2)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(2),text.newCFString(f.name));ret(NO_ERR);});
		l.register(null,"ATSFontFamilyGetQuickDrawName",(r,c)->{Family f=family(arg(0));if(f==null){ret(-981);return;}if(arg(1)==0){ret(PARAM_ERR);return;}
			byte[] b=text.encode(familyEncoding(f),f.name);int n=Math.min(255,b.length);memory.writeByte(arg(1),n);memory.load(arg(1)+1,java.util.Arrays.copyOf(b,n));ret(NO_ERR);});
		// fonts
		l.register(null,"ATSFontFindFromName",(r,c)->{String name=text.cfText(arg(0));if(name!=null)for(int font:fonts())if(name.equalsIgnoreCase(fontName(font,4))){ret(font);return;}ret(0);});
		l.register(null,"ATSFontFindFromPostScriptName",(r,c)->{String name=text.cfText(arg(0));if(name!=null)for(int font:fonts())if(name.equals(fontName(font,6))){ret(font);return;}ret(0);});
		l.register(null,"ATSFontFindFromContainer",(r,c)->{File file=containers.get(arg(0));if(file==null){ret(-985);return;} // (container, options, count, fonts[], *count)
			List<Integer> found=new ArrayList<Integer>();for(Family f:families())if(f.file!=null&&same(f.file,file))for(int s:f.styles())found.add(fontOf(f.id,s));
			for(int i=0;i<Math.min(arg(2),found.size())&&arg(3)!=0;i++)memory.writeLong(arg(3)+4*i,found.get(i));writeLongIf(arg(4),found.size());ret(NO_ERR);});
		l.register(null,"ATSFontGetGeneration",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;ret(f==null?0:f.generation);});
		l.register(null,"ATSFontGetName",(r,c)->{if(!fontExists(arg(0))){ret(-982);return;}if(arg(2)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(2),text.newCFString(fontName(arg(0),4)));ret(NO_ERR);});
		l.register(null,"ATSFontGetPostScriptName",(r,c)->{if(!fontExists(arg(0))){ret(-982);return;}if(arg(2)==0){ret(PARAM_ERR);return;}memory.writeLong(arg(2),text.newCFString(fontName(arg(0),6)));ret(NO_ERR);});
		l.register(null,"ATSFontGetTableDirectory",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(-982);return;}
			byte[] s=f.sfnt((arg(0)>>>16)&0xff);if(s==null||s.length<12){ret(-984);return;}int n=Math.min(s.length,12+16*be16(s,4));
			if(arg(2)!=0)memory.load(arg(2),java.util.Arrays.copyOf(s,Math.min(n,Math.max(0,arg(1)))));writeLongIf(arg(3),n);ret(NO_ERR);});
		l.register(null,"ATSFontGetTable",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(-982);return;} // (font, tag, offset, bufferSize, buffer, *size)
			byte[] t=table(f.sfnt((arg(0)>>>16)&0xff),arg(1));if(t==null){ret(-984);return;}int off=Math.max(0,arg(2));if(off>t.length){ret(PARAM_ERR);return;}
			int len=arg(4)==0?t.length-off:Math.min(Math.max(0,arg(3)),t.length-off);if(arg(4)!=0)memory.load(arg(4),java.util.Arrays.copyOfRange(t,off,off+len));writeLongIf(arg(5),len);ret(NO_ERR);});
		l.register(null,"ATSFontGetHorizontalMetrics",(r,c)->ret(fontMetrics(arg(0),arg(2),false)));
		l.register(null,"ATSFontGetVerticalMetrics",(r,c)->ret(fontMetrics(arg(0),arg(2),true)));
		l.register(null,"ATSFontGetFileSpecification",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(-982);return;}if(f.file==null){ret(-985);return;}
			if(arg(1)==0){ret(PARAM_ERR);return;}ret(rt.toolbox.files.fileManager.writeFSSpec(arg(1),f.file)==0?NO_ERR:-985);});
		l.register(null,"ATSFontGetFontFamilyResource",(r,c)->{Family f=fontExists(arg(0))?family(arg(0)&0xffff):null;if(f==null){ret(-982);return;} // the 'FOND' as the Resource Manager finds it
			byte[] d=fondData(f);if(d==null){ret(-982);return;}
			if(arg(2)!=0)memory.load(arg(2),java.util.Arrays.copyOf(d,Math.min(d.length,Math.max(0,arg(1)))));writeLongIf(arg(3),d.length);ret(NO_ERR);});
	}
}
