import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A QuickDraw region as a list of horizontal bands, each holding sorted, disjoint [left,right) intervals.
 * Regions live in guest memory in the ROM's own format (rgnSize, rgnBBox, then for non-rectangular regions
 * the scan-line list: y, x inversion points..., $7FFF; ...; $7FFF), so programs that look at rgnSize (10 =
 * rectangular) or walk the data see the real thing; {@link #decode}/{@link #encode} convert.
 */
final class QdRegion {
	static final int END=0x7fff;

	/** A band [top,bottom) with interval endpoints xs = {l0,r0,l1,r1,...}, sorted and disjoint. */
	static final class Band {
		final int top,bottom;final int[] xs;
		Band(int top,int bottom,int[] xs){this.top=top;this.bottom=bottom;this.xs=xs;}
	}

	final List<Band> bands;
	private QdRegion(List<Band> bands){this.bands=bands;}

	static QdRegion empty(){return new QdRegion(new ArrayList<Band>());}
	static QdRegion rect(int top,int left,int bottom,int right){
		QdRegion r=empty();if(bottom>top&&right>left)r.bands.add(new Band(top,bottom,new int[]{left,right}));return r;
	}

	/**
	 * A region from per-row interval lists: {@code rows.get(i)} holds the sorted, disjoint endpoints {l0,r0,l1,r1,...}
	 * of row {@code top+i} (an empty array for a row with no pixels); equal consecutive rows become one band.
	 */
	static QdRegion fromRows(int top,List<int[]> rows){
		QdRegion r=empty();int i=0;
		while(i<rows.size()){int[] xs=rows.get(i);if(xs.length==0){i++;continue;}int j=i+1;while(j<rows.size()&&Arrays.equals(rows.get(j),xs))j++;r.bands.add(new Band(top+i,top+j,xs));i=j;}
		return r;
	}

	boolean isEmpty(){return bands.isEmpty();}
	/** True when the region is a single rectangle (stored with rgnSize 10 and no scan-line data). */
	boolean isRect(){return bands.size()==1&&bands.get(0).xs.length==2;}

	/** Bounding box as {top,left,bottom,right}; all zero when empty. */
	int[] bounds(){
		if(bands.isEmpty())return new int[]{0,0,0,0};
		int left=Integer.MAX_VALUE,right=Integer.MIN_VALUE;
		for(Band b:bands){left=Math.min(left,b.xs[0]);right=Math.max(right,b.xs[b.xs.length-1]);}
		return new int[]{bands.get(0).top,left,bands.get(bands.size()-1).bottom,right};
	}

	boolean contains(int h,int v){
		for(Band b:bands){if(v<b.top)return false;if(v>=b.bottom)continue;
			for(int i=0;i<b.xs.length;i+=2)if(h>=b.xs[i]&&h<b.xs[i+1])return true;return false;}
		return false;
	}

	/** Every rectangle of the region as {top,left,bottom,right}. */
	List<int[]> rects(){
		List<int[]> out=new ArrayList<int[]>();
		for(Band b:bands)for(int i=0;i<b.xs.length;i+=2)out.add(new int[]{b.top,b.xs[i],b.bottom,b.xs[i+1]});
		return out;
	}

	QdRegion offset(int dh,int dv){
		QdRegion r=empty();
		for(Band b:bands){int[] xs=b.xs.clone();for(int i=0;i<xs.length;i++)xs[i]+=dh;r.bands.add(new Band(b.top+dv,b.bottom+dv,xs));}
		return r;
	}

	boolean sameShape(QdRegion other){
		if(bands.size()!=other.bands.size())return false;
		for(int i=0;i<bands.size();i++){Band a=bands.get(i),b=other.bands.get(i);if(a.top!=b.top||a.bottom!=b.bottom||!Arrays.equals(a.xs,b.xs))return false;}
		return true;
	}

	// ------------------------------------------------------------------ set operations

	static final int SECT=0,UNION=1,DIFF=2,XOR=3;

	static QdRegion combine(QdRegion a,QdRegion b,int operation){
		// y breakpoints of both, then one band per interval with the 1-D combination of the two interval lists
		int[] ys=breakpoints(a,b);
		QdRegion r=empty();
		for(int i=0;i+1<ys.length;i++){
			int top=ys[i],bottom=ys[i+1];if(bottom<=top)continue;
			int[] xa=intervalsAt(a,top),xb=intervalsAt(b,top);
			int[] xs=combine1d(xa,xb,operation);
			if(xs.length>0)r.append(top,bottom,xs);
		}
		return r;
	}

	private static int[] breakpoints(QdRegion a,QdRegion b){
		int[] ys=new int[(a.bands.size()+b.bands.size())*2];int n=0;
		for(Band band:a.bands){ys[n++]=band.top;ys[n++]=band.bottom;}
		for(Band band:b.bands){ys[n++]=band.top;ys[n++]=band.bottom;}
		Arrays.sort(ys,0,n);int m=0;for(int i=0;i<n;i++)if(m==0||ys[m-1]!=ys[i])ys[m++]=ys[i];
		return Arrays.copyOf(ys,m);
	}

	private static final int[] NONE=new int[0];
	private static int[] intervalsAt(QdRegion r,int y){for(Band b:r.bands){if(y<b.top)return NONE;if(y<b.bottom)return b.xs;}return NONE;}

	/** Combines two sorted interval lists; inside(a,b) decides membership per operation. */
	static int[] combine1d(int[] a,int[] b,int operation){
		int[] xs=new int[a.length+b.length];int n=0;
		for(int x:a)xs[n++]=x;for(int x:b)xs[n++]=x;
		Arrays.sort(xs);
		int[] out=new int[xs.length+2];int m=0;boolean was=false;
		for(int i=0;i<xs.length;i++){
			int x=xs[i];while(i+1<xs.length&&xs[i+1]==x)i++; // duplicate endpoints: evaluate once
			boolean inA=inside(a,x),inB=inside(b,x),now;
			switch(operation){case SECT:now=inA&&inB;break;case UNION:now=inA||inB;break;case DIFF:now=inA&&!inB;break;default:now=inA!=inB;break;}
			if(now!=was){out[m++]=x;was=now;}
		}
		return Arrays.copyOf(out,m);
	}

	private static boolean inside(int[] xs,int x){for(int i=0;i<xs.length;i+=2)if(x>=xs[i]&&x<xs[i+1])return true;return false;}

	/** Appends a band, merging it with the previous one when the shape continues unchanged. */
	private void append(int top,int bottom,int[] xs){
		if(!bands.isEmpty()){Band last=bands.get(bands.size()-1);
			if(last.bottom==top&&Arrays.equals(last.xs,xs)){bands.set(bands.size()-1,new Band(last.top,bottom,xs));return;}}
		bands.add(new Band(top,bottom,xs));
	}

	/**
	 * InsetRgn: shrinks (positive) or grows (negative) the region, horizontally by dh and vertically by dv (signs may differ).
	 * Exact for rectilinear shapes: the erosion (dilation) by a (2dh+1)x(2dv+1) box, done one axis at a time - the
	 * intersection (union) of the region shifted by -d..d - with the shifts doubled up, so a large inset costs log(d) steps
	 * (the old per-band approximation turned a large oval inset into a short rectangle).
	 */
	QdRegion inset(int dh,int dv){
		if(dh==0&&dv==0)return this;
		QdRegion r=this;
		if(dh!=0){int a=Math.abs(dh);r=r.span(2*a+1,true,dh>0?SECT:UNION).offset(-a,0);}
		if(dv!=0){int a=Math.abs(dv);r=r.span(2*a+1,false,dv>0?SECT:UNION).offset(0,-a);}
		return r;
	}
	/** The region combined ({@code operation}) with itself shifted by 1..n-1 along one axis. */
	private QdRegion span(int n,boolean horizontal,int operation){
		if(n<=1)return this;
		int half=n/2;QdRegion h=span(half,horizontal,operation);
		QdRegion r=combine(h,horizontal?h.offset(half,0):h.offset(0,half),operation);
		if((n&1)!=0)r=combine(r,horizontal?offset(n-1,0):offset(0,n-1),operation);
		return r;
	}

	// ------------------------------------------------------------------ guest memory format

	/** Reads the region record at {@code data} (rgnSize, bbox, scan lines). */
	static QdRegion decode(M68kMemory memory,int data) throws M68kCpuException {
		int size=memory.readWord(data);
		int top=(short)memory.readWord(data+2),left=(short)memory.readWord(data+4),bottom=(short)memory.readWord(data+6),right=(short)memory.readWord(data+8);
		if(size<=10)return rect(top,left,bottom,right);
		QdRegion r=empty();int p=data+10,end=data+size;
		int[] current=NONE;int previousY=0;boolean first=true;
		while(p+2<=end){
			int y=(short)memory.readWord(p);p+=2;if(y==END)break;
			List<Integer> flips=new ArrayList<Integer>();
			while(p+2<=end){int x=(short)memory.readWord(p);p+=2;if(x==END)break;flips.add(x);}
			if(!first&&current.length>0&&y>previousY)r.append(previousY,y,current);
			int[] f=new int[flips.size()];for(int i=0;i<f.length;i++)f[i]=flips.get(i);
			current=combine1d(current,pairs(f),XOR);
			previousY=y;first=false;
		}
		// a band still open at the end runs to the bottom of the box: its closing scan line would be y = 32767, the end mark
		if(current.length>0&&bottom>previousY)r.append(previousY,bottom,current);
		return r;
	}

	/** Inversion points as intervals: consecutive pairs (an odd trailing point closes at $7FFF). */
	private static int[] pairs(int[] flips){
		Arrays.sort(flips);
		if((flips.length&1)!=0){flips=Arrays.copyOf(flips,flips.length+1);flips[flips.length-1]=END;}
		return flips;
	}

	/** The region record bytes: rectangular regions are 10 bytes, others carry the scan-line list. */
	byte[] encode(){
		int[] b=bounds();
		List<Integer> words=new ArrayList<Integer>();
		// 32767 ($7FFF) ends both an inversion list and the region, so it is never written as a coordinate: an inversion point
		// there is implied (an odd list closes at $7FFF, see pairs) and a band reaching y = 32767 is closed by the end of the
		// record (decode). Written out, DiffRgn(wide open, rect) - Interarchy's clip around its address field - read back empty.
		if(!isRect()&&!isEmpty()){
			int[] previous=NONE;int previousBottom=Integer.MIN_VALUE;
			for(Band band:bands){
				if(previousBottom!=Integer.MIN_VALUE&&band.top!=previousBottom&&previous.length>0){ // gap: close the previous band
					scanLine(words,previousBottom,previous);previous=NONE;}
				int[] flips=combine1d(previous,band.xs,XOR);
				if(flips.length>0)scanLine(words,band.top,flips);
				previous=band.xs;previousBottom=band.bottom;
			}
			if(previous.length>0&&previousBottom<END)scanLine(words,previousBottom,previous);
			words.add(END);
		}
		byte[] out=new byte[10+words.size()*2];
		putWord(out,0,out.length);putWord(out,2,b[0]);putWord(out,4,b[1]);putWord(out,6,b[2]);putWord(out,8,b[3]);
		for(int i=0;i<words.size();i++)putWord(out,10+i*2,words.get(i));
		return out;
	}

	/** One scan line of inversion points, without a point at $7FFF (implied); nothing when that leaves none. */
	private static void scanLine(List<Integer> words,int y,int[] xs){
		int n=0;for(int x:xs)if(x<END)n++;if(n==0)return;
		words.add(y);for(int x:xs)if(x<END)words.add(x);words.add(END);
	}
	private static void putWord(byte[] b,int at,int v){b[at]=(byte)(v>>8);b[at+1]=(byte)v;}
}
