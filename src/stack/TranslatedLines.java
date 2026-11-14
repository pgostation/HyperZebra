import java.util.ArrayList;
import java.util.List;

/**
 * A field's text as shown translated, line by line (StackTranslation.Shown on the desktop; the browser player's StackShown
 * with the stack's own Languages/*.po): each line looked up whole (returns kept as they are), the style of a translated line
 * the one at its start, and the way back from a character shown to the field's own text (clickLine, clickChunk).
 */
class TranslatedLines {
	final String text;
	/** The style runs for the shown text (null when the field has none). */
	final ArrayList<styleClass> styles;
	private final int[] oStart,oLen,tStart,tLen;
	TranslatedLines(String text,ArrayList<styleClass> styles,int[] oStart,int[] oLen,int[] tStart,int[] tLen){
		this.text=text;this.styles=styles;this.oStart=oStart;this.oLen=oLen;this.tStart=tStart;this.tLen=tLen;
	}
	TranslatedLines(TranslatedLines t){this(t.text,t.styles,t.oStart,t.oLen,t.tStart,t.tLen);}

	/** The place in the field's own text of a character shown: the same line, as far along it (the clickLine and clickChunk). */
	int original(int i){
		int lo=0,hi=tStart.length-1;
		while(lo<hi){int mid=(lo+hi+1)>>>1;if(tStart[mid]<=i)lo=mid;else hi=mid-1;}
		int k=i-tStart[lo];
		if(k>=tLen[lo])return oStart[lo]+oLen[lo]+(k-tLen[lo]); // the line's end or its return
		return oStart[lo]+(tLen[lo]==0?0:(int)((long)k*oLen[lo]/tLen[lo]));
	}

	/** Translates a text line by line with {@code lookup} (null: that line has none); null when no line has a translation. */
	static TranslatedLines of(java.util.function.UnaryOperator<String> lookup,String text,ArrayList<styleClass> styles){
		List<int[]> lines=new ArrayList<int[]>(); // {start, length, separator length}
		for(int a=0;;){
			int b=a;
			while(b<text.length()&&text.charAt(b)!='\r'&&text.charAt(b)!='\n')b++;
			int sep=b>=text.length()?0:text.charAt(b)=='\r'&&b+1<text.length()&&text.charAt(b+1)=='\n'?2:1;
			lines.add(new int[]{a,b-a,sep});
			if(sep==0)break;
			a=b+sep;
		}
		int n=lines.size();
		int[] oStart=new int[n],oLen=new int[n],tStart=new int[n],tLen=new int[n];
		boolean[] translated=new boolean[n];
		StringBuilder out=new StringBuilder(text.length()+16);
		boolean any=false;
		for(int i=0;i<n;i++){
			int[] l=lines.get(i);
			String line=text.substring(l[0],l[0]+l[1]);
			String tr=line.trim().isEmpty()?null:lookup.apply(line);
			if(tr!=null)tr=tr.replace("\r\n"," ").replace('\r',' ').replace('\n',' ');
			if(tr!=null&&!tr.equals(line)){translated[i]=true;any=true;}else tr=line;
			oStart[i]=l[0];oLen[i]=l[1];tStart[i]=out.length();tLen[i]=tr.length();
			out.append(tr).append(text,l[0]+l[1],l[0]+l[1]+l[2]);
		}
		if(!any)return null;
		return new TranslatedLines(out.toString(),stylesFor(styles,lines,translated,tStart),oStart,oLen,tStart,tLen);
	}

	/** Style runs for the shown text: a translated line takes the style at its start, a line left as it is keeps its runs. */
	private static ArrayList<styleClass> stylesFor(ArrayList<styleClass> styles,List<int[]> lines,boolean[] translated,int[] tStart){
		if(styles==null||styles.isEmpty())return styles;
		ArrayList<styleClass> out=new ArrayList<styleClass>();
		for(int i=0;i<lines.size();i++){
			int os=lines.get(i)[0],next=i+1<lines.size()?lines.get(i+1)[0]:Integer.MAX_VALUE;
			int at=-1; // the style in effect at the line's start: -1 is the field's own (no such style id)
			for(styleClass sc:styles)if(sc.textPosition<=os)at=sc.styleId;
			add(out,tStart[i],at);
			if(!translated[i])for(styleClass sc:styles)if(sc.textPosition>os&&sc.textPosition<next)add(out,tStart[i]+sc.textPosition-os,sc.styleId);
		}
		return out;
	}
	private static void add(ArrayList<styleClass> out,int position,int id){
		styleClass last=out.isEmpty()?null:out.get(out.size()-1);
		if(last!=null&&last.styleId==id)return;
		if(last!=null&&last.textPosition==position){last.styleId=id;return;}
		out.add(new styleClass(position,id));
	}
}
