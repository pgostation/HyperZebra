import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * The Font Manager's synthetic text styles (IM I-152, IM Text 4-14): what QuickDraw does to a run of plain glyphs
 * when the face asks for a style the font has no strike for. Bold smears every glyph one pixel to the right (done
 * while the glyphs are drawn, since the smear is per glyph); italic shifts each row right one pixel for every two
 * rows above the baseline and left below it; underline is a line one pixel under the baseline, broken by one pixel
 * either side of a descender; outline hollows the ink, leaving its one-pixel rim; shadow adds a copy of that rim one
 * pixel right and down. Each style also widens the character advances by a fixed number of pixels ({@link #extra}):
 * bold, outline and extend one, shadow two, condense minus one - so outlined Geneva touches, as it did.
 * <p>
 * The pipeline works on a coverage image (TYPE_BYTE_BINARY for bitmapped strikes, TYPE_BYTE_GRAY for anti-aliased
 * host text: the operations are morphological, so grey levels come through as grey rims).
 */
final class TextStyle68k {
	static final int BOLD=1,ITALIC=2,UNDERLINE=4,OUTLINE=8,SHADOW=16,CONDENSE=32,EXTEND=64;
	/** The style bits that change how a run is drawn beyond what a host font's own bold/italic faces give. */
	static final int SYNTHETIC=UNDERLINE|OUTLINE|SHADOW|CONDENSE|EXTEND;
	private TextStyle68k(){}

	/** Pixels added to every non-empty character advance for the face (the Font Manager's default style extras). */
	static int extra(int face){
		int e=0;
		if((face&BOLD)!=0)e++;
		if((face&OUTLINE)!=0)e++;
		if((face&SHADOW)!=0)e+=2;
		if((face&CONDENSE)!=0)e--;
		if((face&EXTEND)!=0)e++;
		return e;
	}
	/** Whether the ink of a styled run can spill outside its advance box (needs margins in the mask). */
	/** Whether the face draws hollow text (outline or shadow), whose inside QuickDraw clears. */
	static boolean hollow(int face){return (face&(OUTLINE|SHADOW))!=0;}
	static boolean spills(int face){return (face&(ITALIC|OUTLINE|SHADOW))!=0;}
	/** Margins {left, top, right, bottom} the styled ink may need around the plain glyph box of a font with the given ascent and descent. */
	static int[] margins(int face,int ascent,int descent){
		int left=0,top=0,right=0,bottom=0;
		if((face&ITALIC)!=0){right+=Math.max(0,ascent)/2;left+=Math.max(0,descent)/2;}
		if((face&(OUTLINE|SHADOW))!=0){left++;top++;right++;bottom++;}
		if((face&SHADOW)!=0){right++;bottom++;}
		if((face&UNDERLINE)!=0)bottom=Math.max(bottom,2-Math.max(0,descent)); // the underline row sits at baseline+1
		return new int[]{left,top,right,bottom};
	}

	/**
	 * Applies italic, underline, outline and shadow to a run drawn plain (and bold-smeared) into {@code image}, in place.
	 * @param baseline the row of the baseline within the image
	 * @param textLeft the column where the first advance box starts (the pen position)
	 * @param textWidth the sum of the advances (the underline's extent)
	 */
	static void apply(BufferedImage image,int face,int baseline,int textLeft,int textWidth){apply(image,face,baseline,textLeft,textWidth,false);}
	/**
	 * @param interior leave out the outline/shadow step: the solid ink the rim goes around, which QuickDraw clears
	 *        (paints with the background) inside hollow text, so outlined text over black shows white letters
	 */
	static void apply(BufferedImage image,int face,int baseline,int textLeft,int textWidth,boolean interior){apply(image,face,baseline,textLeft,textWidth,interior,1);}
	/**
	 * @param scale the image is the run drawn at {@code scale} times the size (the smooth zoom's planes; baseline, textLeft and
	 *        textWidth are in its pixels): the underline, the gaps around descenders, the outline's rim and the shadow's offset
	 *        are {@code scale} pixels instead of one, and italic slants the same (half a pixel per row)
	 */
	static void apply(BufferedImage image,int face,int baseline,int textLeft,int textWidth,boolean interior,int scale){
		if(interior)face&=~(OUTLINE|SHADOW);
		if((face&(ITALIC|UNDERLINE|OUTLINE|SHADOW))==0)return;
		int k=Math.max(1,scale);
		int w=image.getWidth(),h=image.getHeight();boolean binary=image.getType()==BufferedImage.TYPE_BYTE_BINARY;
		WritableRaster raster=image.getRaster();int[] cov=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int s=raster.getSample(x,y,0);cov[y*w+x]=binary?(s!=0?255:0):s;}
		if((face&ITALIC)!=0){
			int[] skewed=new int[w*h];
			for(int y=0;y<h;y++){int shift=(baseline-y)/2;for(int x=0;x<w;x++){int sx=x-shift;if(sx>=0&&sx<w)skewed[y*w+x]=cov[y*w+sx];}}
			cov=skewed;
		}
		if((face&UNDERLINE)!=0){
			// the descenders are looked for in the ink before the line: testing the row while drawing it found the line's own
			// last pixel and drew every other one
			int row=baseline+k,left=Math.max(0,textLeft),right=Math.min(w,textLeft+textWidth);
			if(row>=0&&row<h&&left<right){boolean[] descender=new boolean[w];
				for(int y=row;y<Math.min(h,row+k);y++)for(int x=0;x<w;x++)if(cov[y*w+x]!=0)for(int nx=Math.max(0,x-k);nx<=Math.min(w-1,x+k);nx++)descender[nx]=true;
				for(int y=row;y<Math.min(h,row+k);y++)for(int x=left;x<right;x++)if(!descender[x])cov[y*w+x]=255;}
		}
		if((face&(OUTLINE|SHADOW))!=0){
			int[] grown=dilate(cov,w,h,k),rim=new int[w*h];
			for(int i=0;i<w*h;i++)rim[i]=Math.max(0,grown[i]-cov[i]);
			if((face&SHADOW)!=0){
				int[] shadowed=rim.clone();
				for(int y=k;y<h;y++)for(int x=k;x<w;x++){int v=Math.max(0,rim[(y-k)*w+x-k]-cov[y*w+x]);if(v>shadowed[y*w+x])shadowed[y*w+x]=v;}
				rim=shadowed;
			}
			cov=rim;
		}
		for(int y=0;y<h;y++)for(int x=0;x<w;x++)raster.setSample(x,y,0,binary?(cov[y*w+x]>=128?1:0):cov[y*w+x]);
	}
	/** The coverage grown by {@code r} pixels every way (the maximum over a (2r+1)-square, rows then columns). */
	private static int[] dilate(int[] cov,int w,int h,int r){
		int[] rows=new int[w*h],out=new int[w*h];
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int max=0;for(int nx=Math.max(0,x-r);nx<=Math.min(w-1,x+r);nx++)max=Math.max(max,cov[y*w+nx]);rows[y*w+x]=max;}
		for(int y=0;y<h;y++)for(int x=0;x<w;x++){int max=0;for(int ny=Math.max(0,y-r);ny<=Math.min(h-1,y+r);ny++)max=Math.max(max,rows[ny*w+x]);out[y*w+x]=max;}
		return out;
	}
}
