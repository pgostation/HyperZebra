import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * The text in a program's PICT resources (68K and PowerPC programs) for Resource Translation, translated ahead with the
 * program's other texts and written into its translated resources (ResourceTranslation) as a copy of the PICT. Two kinds:
 * <ul>
 * <li>text the picture draws with QuickDraw's text opcodes (LongText, DHText...): its strings are translated in place, in
 * the System's script like the program's other strings, so the program draws them with its own fonts where they were;</li>
 * <li>text that is pixels: read with Tesseract like the pictures of a stack (PictureOcr, PictureTranslation - the same
 * readings by content in <state>/Translations/Pictures) in the picture as the VM's QuickDraw draws it with its text
 * opcodes blanked (the host's decoder when it cannot), and drawn over just before the picture's end: a clip to its frame, then each translated place as bits (a 1-bit
 * BitMap; 32-bit direct pixels for a colour place of a version 2 picture).</li>
 * </ul>
 * Reading text opcodes as pixels did not work: the program draws them with its System's fonts and widths, not the
 * reader's, so pieces of the original stood beside the translations. The program file is never changed; a PICT with nothing translated stays as it is.
 */
final class PictTranslation {
	private PictTranslation(){}

	/** An opcode of a picture: where it starts and its data ends (no pad byte), and where a text opcode's count byte is (else -1). */
	static final class Op {
		final int start,end,op,countAt;
		Op(int start,int end,int op,int countAt){this.start=start;this.end=end;this.op=op;this.countAt=countAt;}
	}

	/** A PICT as drawn on white (its text opcodes blanked), and where its translated places go. */
	static final class Pict {
		final byte[] data;final BufferedImage image;
		/** 1 or 2. */
		final int version;
		/** The picture's frame, and the space its opcodes draw in (the header's rectangle of a version 2 picture at another resolution). */
		final Rectangle frame,space;
		/** Where its end opcode starts. */
		final int end;
		/** Where its first opcode after the version starts, and its opcodes up to the end (null: one could not be stepped over). */
		final int first;final List<Op> ops;
		Pict(byte[] data,BufferedImage image,int version,Rectangle frame,Rectangle space,int end,int first,List<Op> ops){
			this.data=data;this.image=image;this.version=version;this.frame=frame;this.space=space;this.end=end;this.first=first;this.ops=ops;
		}
		/** A place in the image (the frame's pixels) in the picture's own coordinates. */
		Rectangle toPicture(Rectangle r){
			double sx=space.width/(double)frame.width,sy=space.height/(double)frame.height;
			int x0=space.x+(int)Math.floor(r.x*sx),y0=space.y+(int)Math.floor(r.y*sy);
			int x1=space.x+(int)Math.ceil((r.x+r.width)*sx),y1=space.y+(int)Math.ceil((r.y+r.height)*sy);
			return new Rectangle(x0,y0,x1-x0,y1-y0);
		}
		/** The text opcodes' strings in the program's script (a line's return left out), in order. */
		List<String> runs(StackTextEncoding encoding){
			List<String> out=new ArrayList<String>();
			if(ops==null)return out;
			for(Op o:ops)if(o.countAt>=0)out.add(run(data,o,encoding));
			return out;
		}
	}
	private static String run(byte[] d,Op o,StackTextEncoding encoding){
		int n=d[o.countAt]&255;
		while(n>0&&(d[o.countAt+n]==13||d[o.countAt+n]==10))n--;
		return encoding.decode(d,o.countAt+1,n);
	}

	private static int u2(byte[] d,int at){return (d[at]&255)<<8|d[at+1]&255;}
	private static int s2(byte[] d,int at){return (short)u2(d,at);}

	/** A PICT resource as drawn (text opcodes blanked), or null: not a picture that can be read, or too large. */
	static Pict read(byte[] d){
		if(d==null||d.length<14)return null;
		int top=s2(d,2),left=s2(d,4),bottom=s2(d,6),right=s2(d,8);
		if(right<=left||bottom<=top||(long)(right-left)*(bottom-top)>16L*1024*1024)return null;
		Rectangle frame=new Rectangle(left,top,right-left,bottom-top),space=frame;
		int v=10;
		while(v+1<d.length&&d[v]==0&&d[v+1]==0)v+=2; // NOPs before the version opcode
		int version,first;
		if(v+1<d.length&&d[v]==0x11&&d[v+1]==0x01){version=1;first=v+2;}
		else if(v+3<d.length&&d[v]==0x00&&d[v+1]==0x11&&d[v+2]==0x02&&(d[v+3]&255)==0xFF){
			version=2;first=v+4;
			int h=first;
			if(h+28<=d.length&&u2(d,h)==0x0C00&&s2(d,h+2)==-2) // an extended header: the opcodes draw in its rectangle
				space=new Rectangle(s2(d,h+16),s2(d,h+14),s2(d,h+20)-s2(d,h+16),s2(d,h+18)-s2(d,h+14)); // $0C00, -2, reserved, hRes, vRes, srcRect
			if(space.width<=0||space.height<=0)space=frame;
		}else return null;
		List<Op> ops;
		try{ops=walk(d,version,first);}catch(RuntimeException e){ops=null;}
		int end=ops!=null?ops.get(ops.size()-1).start:end(d,version);
		if(end<0)return null;
		byte[] blank=d;
		if(ops!=null)for(Op o:ops)if(o.countAt>=0){
			if(blank==d)blank=d.clone();
			Arrays.fill(blank,o.countAt+1,o.countAt+1+(d[o.countAt]&255),(byte)' ');
		}
		BufferedImage image=played(blank);
		if(image==null||image.getWidth()!=frame.width||image.getHeight()!=frame.height)image=PictureFile.loadPICT(blank);
		if(image==null||image.getWidth()!=frame.width||image.getHeight()!=frame.height)return null;
		return new Pict(d,image,version,frame,space,end,first,ops);
	}

	private static Mac68kApi player;
	/**
	 * A picture as the VM's own DrawPicture draws it on white (QuickDraw68k.renderPicture in a QuickDraw of its own, not a
	 * program's), or null when it cannot: what the program will show, shapes and all - the host's decoder leaves some out.
	 */
	static synchronized BufferedImage played(byte[] d){
		try{
			if(player==null){M68kMemory memory=new M68kMemory();player=new Mac68kApi(memory);M68kCpu cpu=new M68kCpu(memory,0x1000,player);cpu.a[7]=0x00eff000;}
			return player.quickDraw().renderPictureAtDepth(d,32);
		}catch(M68kCpuException|RuntimeException e){
			return null;
		}
	}

	/** Where the picture's end opcode is: at the end of its data (picSize is the low 16 bits of its length), or -1. */
	private static int end(byte[] d,int version){
		int picSize=u2(d,0),best=-1;
		for(int len=d.length;len>=Math.max(12,d.length-16);len--){
			boolean ends=version==1?(d[len-1]&255)==0xFF:len%2==0&&d[len-2]==0&&(d[len-1]&255)==0xFF;
			if(!ends)continue;
			int at=len-(version==1?1:2);
			if((len&0xFFFF)==picSize)return at;
			if(best<0)best=at;
		}
		return best;
	}

	/**
	 * The opcodes of a picture from {@code at} to its end opcode (the last), as QuickDraw68k's spooler steps over them
	 * (Imaging With QuickDraw A-22..A-32); null when one has no known length. Version 2 opcodes start at even offsets.
	 */
	static List<Op> walk(byte[] d,int version,int at){
		boolean v2=version==2;
		List<Op> out=new ArrayList<Op>();
		for(int guard=0;guard<1_000_000;guard++){
			if(v2&&(at&1)!=0)at++;
			if(at+(v2?2:1)>d.length)return null;
			int start=at,op=v2?u2(d,at):d[at]&255;
			at+=v2?2:1;
			if(op==0xFF){out.add(new Op(start,at,op,-1));return out;}
			int n=v2||op!=0x11?fixedLength(op):1, countAt=-1;
			if(n>=0)at+=n;
			else switch(op){
				case 0x01:case 0x70:case 0x71:case 0x72:case 0x73:case 0x74:case 0x75:case 0x76:case 0x77:
				case 0x80:case 0x81:case 0x82:case 0x83:case 0x84:case 0x85:case 0x86:case 0x87:
					at+=u2(d,at);break; // region / polygon: the size word counts itself
				case 0x28:countAt=at+4;at+=5+(d[countAt]&255);break; // LongText
				case 0x29:case 0x2a:countAt=at+1;at+=2+(d[countAt]&255);break; // DHText, DVText
				case 0x2b:countAt=at+2;at+=3+(d[countAt]&255);break; // DHDVText
				case 0xa1:at+=4+u2(d,at+2);break; // LongComment
				case 0x90:case 0x91:case 0x98:case 0x99:at=bits(d,at,op,false);break;
				case 0x9a:case 0x9b:at=bits(d,at,op,true);break;
				default:
					if(v2&&(op>=0x24&&op<=0x2f||op>=0x92&&op<=0x97||op>=0x9c&&op<=0x9f||op>=0xa2&&op<=0xaf)){at+=2+u2(d,at);break;}
					if(v2&&(op>=0xd0&&op<=0xfe||op>=0x8100)){int len=u2(d,at)<<16|u2(d,at+2);if(len<0)return null;at+=4+len;break;}
					return null;
			}
			if(at>d.length)return null;
			out.add(new Op(start,at,op,countAt));
		}
		return null;
	}
	/** Opcodes with a fixed data length, -1 for the others (QuickDraw68k.PictureSpool.fixedLength). */
	private static int fixedLength(int op){
		if(op>=0x0100&&op<=0x01ff)return 2;
		if(op>=0x0200&&op<=0x7fff)return (op>>8)*2; // HeaderOp $0C00: 24
		if(op>=0x8000&&op<=0x80ff)return 0;
		switch(op){
			case 0x00:case 0x17:case 0x18:case 0x19:case 0x1c:case 0x1e:return 0;
			case 0x04:return 1;
			case 0x03:case 0x05:case 0x08:case 0x0d:case 0x11:case 0x15:case 0x16:case 0x23:case 0xa0:return 2;
			case 0x06:case 0x07:case 0x0b:case 0x0c:case 0x0e:case 0x0f:case 0x21:return 4;
			case 0x1a:case 0x1b:case 0x1d:case 0x1f:case 0x22:return 6;
			case 0x02:case 0x09:case 0x0a:case 0x10:case 0x20:return 8;
			default:break;
		}
		if(op>=0x30&&op<=0x37||op>=0x40&&op<=0x47||op>=0x50&&op<=0x57)return 8;
		if(op>=0x38&&op<=0x3f||op>=0x48&&op<=0x4f||op>=0x58&&op<=0x5f||op>=0x78&&op<=0x7f||op>=0x88&&op<=0x8f||op>=0xb0&&op<=0xcf)return 0;
		if(op>=0x60&&op<=0x67)return 12;
		if(op>=0x68&&op<=0x6f)return 4;
		return -1;
	}
	/** Past a bits opcode's data: its header (a PixMap's colour table), rectangles and mode, mask region, then the rows. */
	private static int bits(byte[] d,int at,int op,boolean direct){
		if(direct)at+=4; // baseAddr
		int rowBytes=u2(d,at);at+=2;
		boolean pixMap=direct||(rowBytes&0x8000)!=0;rowBytes&=0x3fff;
		int top=s2(d,at),left=s2(d,at+2),bottom=s2(d,at+4),right=s2(d,at+6);at+=8;
		int packType=0;
		if(pixMap){packType=u2(d,at+2);at+=36;if(!direct){int ctSize=u2(d,at+6);at+=8+(ctSize+1)*8;}}
		at+=18; // srcRect, dstRect, mode
		if(op==0x91||op==0x99||op==0x9b)at+=u2(d,at);
		int rows=Math.max(0,bottom-top),width=Math.max(0,right-left);
		if(rowBytes<8||packType==1)return at+rows*rowBytes;
		if(direct&&packType==2)return at+rows*width*3;
		for(int r=0;r<rows;r++)at+=rowBytes>250?2+u2(d,at):1+(d[at]&255);
		return at;
	}

	/**
	 * The texts in a program's PICT resources to translate with its other texts: the strings of its text opcodes (as
	 * text), then the paragraphs Tesseract reads in its pictures (those not read before are read now: one-line labels as
	 * dialog words, longer text as text). No paragraphs without tesseract or its languages for {@code from}.
	 * @param pictureTexts given the paragraphs read, when not null (drawn, not written as strings: no script or length limit)
	 */
	static List<VMTranslation.ProgramText> texts(ResourceFile68k rf,String from,StackTextEncoding encoding,PictureTranslation.Progress progress,Set<String> pictureTexts){
		List<VMTranslation.ProgramText> out=new ArrayList<VMTranslation.ProgramText>();
		if(rf==null||"off".equals(System.getProperty("hz.translate.pictures")))return out;
		String langs=PictureTranslation.ocrLanguages(from);
		List<ResourceFile68k.Resource> picts=picts(rf);
		LinkedHashSet<String> runs=new LinkedHashSet<String>(),ui=new LinkedHashSet<String>(),text=new LinkedHashSet<String>();
		PictureTranslation.Vocabulary vocabulary=vocabulary(rf,encoding);
		for(int i=0;i<picts.size();i++){
			if(progress!=null)progress.pictures(i,picts.size());
			Pict p;
			try{p=read(picts.get(i).originalData());}catch(RuntimeException e){continue;}
			if(p==null)continue;
			for(String r:p.runs(encoding))if(VMTranslation.Piece.of(r)!=null)runs.add(r);
			List<PictureOcr.Block> blocks=langs==null?null:blocks(p,langs,true);
			if(blocks!=null)for(PictureOcr.Block b:blocks)if(PictureTranslation.worth(b.text(),from))(PictureTranslation.kindOf(b)==VMTranslation.Kind.UI?ui:text).add(vocabulary.snap(b.text()));
		}
		if(progress!=null)progress.pictures(picts.size(),picts.size());
		if(pictureTexts!=null){pictureTexts.addAll(ui);pictureTexts.addAll(text);}
		if(!runs.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.TEXT,new ArrayList<String>(runs),false));
		if(!ui.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.UI,new ArrayList<String>(ui),false));
		if(!text.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.TEXT,new ArrayList<String>(text),false));
		return out;
	}

	/** A program's texts (its resource strings and its pictures' text opcodes), which a misread line in a picture is snapped to. */
	static PictureTranslation.Vocabulary vocabulary(ResourceFile68k rf,StackTextEncoding encoding){
		PictureTranslation.Vocabulary v=PictureTranslation.Vocabulary.of(VMTranslation.programTexts(rf,encoding));
		for(ResourceFile68k.Resource r:picts(rf)){
			try{Pict p=read(r.originalData());if(p!=null)for(String s:p.runs(encoding))v.add(s);}catch(RuntimeException ignored){}
		}
		return v;
	}

	private static List<ResourceFile68k.Resource> picts(ResourceFile68k rf){
		List<ResourceFile68k.Resource> out=new ArrayList<ResourceFile68k.Resource>();
		int type=ResourceFile68k.fourcc("PICT");
		for(ResourceFile68k.Resource r:rf.all())if(r.type==type)out.add(r);
		return out;
	}

	/** What was read in a picture (read now when {@code read} and not read before), or null. */
	private static List<PictureOcr.Block> blocks(Pict p,String langs,boolean read){
		if(p.image.getWidth()<8||p.image.getHeight()<8)return null;
		String hash=PictureTranslation.hash(p.image);
		List<PictureOcr.Block> blocks=PictureTranslation.reading(hash,langs);
		if(blocks==null&&read){
			try{blocks=PictureOcr.recognize(p.image,langs);}catch(IOException e){return null;}
			PictureTranslation.store(hash,langs,blocks);
		}
		return blocks;
	}

	/**
	 * A PICT resource's data translated from what {@code t} has in the cache (nothing is sent), or null when nothing in
	 * it is translated (or it cannot be read).
	 * @param string a text opcode's string translated and written in the System's script, or null where it stays (ResourceTranslation's rules for strings)
	 * @return {translated data, number of paragraphs read in its pixels that were translated (strings count as {@code string} counts them)}
	 */
	static Object[] translated(byte[] d,VMTranslation.Translator t,StackTextEncoding encoding,PictureTranslation.Vocabulary vocabulary,java.util.function.Function<String,byte[]> string){
		Pict p=read(d);
		if(p==null)return null;
		Map<Op,byte[]> strings=new HashMap<Op,byte[]>();
		if(p.ops!=null)for(Op o:p.ops){
			if(o.countAt<0)continue;
			String run=run(d,o,encoding);
			if(VMTranslation.Piece.of(run)==null)continue;
			byte[] tr=string.apply(run);
			if(tr==null)continue;
			int count=d[o.countAt]&255,kept=count;
			while(kept>0&&(d[o.countAt+kept]==13||d[o.countAt+kept]==10))kept--;
			byte[] with=Arrays.copyOf(tr,Math.min(255,tr.length+count-kept)); // the line's return stays
			System.arraycopy(d,o.countAt+1+kept,with,Math.min(tr.length,with.length),with.length-Math.min(tr.length,with.length));
			strings.put(o,with);
		}
		List<Rectangle> wiped=new ArrayList<Rectangle>();
		BufferedImage img=null;
		String langs=PictureTranslation.ocrLanguages(t.from());
		List<PictureOcr.Block> blocks=langs==null?null:blocks(p,langs,false);
		if(blocks!=null&&!blocks.isEmpty()){
			List<String> translations=new ArrayList<String>();
			int m=0;
			for(PictureOcr.Block b:blocks){
				String text=vocabulary.snap(b.text()),tr=PictureTranslation.worth(text,t.from())?t.cached(PictureTranslation.kindOf(b),text):null;
				if(PictureTranslation.changes(text,tr))m++;else tr=null;
				translations.add(tr);
			}
			if(m>0)img=PictureTranslation.render(p.image,blocks,translations,p.version==2&&!blackAndWhite(p.image),wiped);
		}
		if(strings.isEmpty()&&img==null)return null;
		try{return new Object[]{rebuild(p,strings,img,wiped),img==null?0:wiped.size()};}catch(IOException e){return null;}
	}

	/** Every pixel black, white or not drawn. */
	static boolean blackAndWhite(BufferedImage img){
		for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++){
			int p=img.getRGB(x,y);
			if((p>>>24)<128)continue;
			p&=0xFFFFFF;
			if(p!=0&&p!=0xFFFFFF)return false;
		}
		return true;
	}

	/**
	 * The picture with its text opcodes' strings replaced ({@code strings}, by opcode) and its places drawn anew from
	 * {@code img} (the frame's pixels) before its end; version 2 opcodes kept at even offsets.
	 */
	static byte[] rebuild(Pict p,Map<Op,byte[]> strings,BufferedImage img,List<Rectangle> places) throws IOException {
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		DataOutputStream out=new DataOutputStream(bytes);
		boolean v2=p.version==2;
		byte[] d=p.data;
		if(p.ops==null)out.write(d,0,p.end);
		else{
			out.write(d,0,p.first);
			for(Op o:p.ops){
				if(o.op==0xFF)break;
				if(v2&&bytes.size()%2!=0)out.writeByte(0);
				byte[] s=strings.get(o);
				if(s==null){out.write(d,o.start,o.end-o.start);continue;}
				out.write(d,o.start,o.countAt-o.start);
				out.writeByte(s.length);out.write(s);
			}
			if(v2&&bytes.size()%2!=0)out.writeByte(0);
		}
		if(img!=null&&!places.isEmpty()){
			// the picture may have left a smaller clip: its whole space again
			op(out,v2,0x01);out.writeShort(10);rect(out,p.space);
			for(Rectangle r:places){
				if(r.isEmpty())continue;
				Rectangle dst=p.toPicture(r);
				if(!v2||blackAndWhite(img.getSubimage(r.x,r.y,r.width,r.height)))bitMap(out,v2,img,r,dst);
				else directBits(out,img,r,dst);
				if(v2&&bytes.size()%2!=0)out.writeByte(0);
			}
		}
		op(out,v2,0xFF);
		byte[] result=bytes.toByteArray();
		result[0]=(byte)(result.length>>8);result[1]=(byte)result.length; // picSize: the low 16 bits of the length
		return result;
	}

	private static void op(DataOutputStream out,boolean v2,int op) throws IOException {if(v2)out.writeShort(op);else out.writeByte(op);}
	/** A QuickDraw rectangle: top, left, bottom, right. */
	private static void rect(DataOutputStream out,Rectangle r) throws IOException {out.writeShort(r.y);out.writeShort(r.x);out.writeShort(r.y+r.height);out.writeShort(r.x+r.width);}

	/** PackBitsRect of a 1-bit BitMap (srcCopy): dark drawn pixels black, the rest white. */
	private static void bitMap(DataOutputStream out,boolean v2,BufferedImage img,Rectangle r,Rectangle dst) throws IOException {
		int rowBytes=((r.width+15)/16)*2;
		op(out,v2,0x98);
		out.writeShort(rowBytes);
		Rectangle bounds=new Rectangle(0,0,r.width,r.height);
		rect(out,bounds);rect(out,bounds);rect(out,dst);out.writeShort(0);
		for(int y=0;y<r.height;y++){
			byte[] row=new byte[rowBytes];
			for(int x=0;x<r.width;x++){
				int p=img.getRGB(r.x+x,r.y+y);
				if((p>>>24)>=128&&((p>>16&255)*30+(p>>8&255)*59+(p&255)*11)/100<128)row[x>>3]|=(byte)(0x80>>>(x&7));
			}
			packed(out,row,rowBytes);
		}
	}

	/** DirectBitsRect of 32-bit pixels (srcCopy), rows as R, G and B planes packed (as HCResourceExport.pict writes them); undrawn pixels white. */
	private static void directBits(DataOutputStream out,BufferedImage img,Rectangle r,Rectangle dst) throws IOException {
		int rowBytes=r.width*4;
		Rectangle bounds=new Rectangle(0,0,r.width,r.height);
		out.writeShort(0x9A);
		out.writeInt(0xFF); // baseAddr
		out.writeShort(0x8000|rowBytes);rect(out,bounds);
		out.writeShort(0);out.writeShort(0);out.writeInt(0); // pmVersion, packType, packSize
		out.writeInt(72<<16);out.writeInt(72<<16);
		out.writeShort(16);out.writeShort(32);out.writeShort(3);out.writeShort(8);
		out.writeInt(0);out.writeInt(0);out.writeInt(0);
		rect(out,bounds);rect(out,dst);out.writeShort(0);
		for(int y=0;y<r.height;y++){
			byte[] row=new byte[r.width*3];
			for(int x=0;x<r.width;x++){
				int p=img.getRGB(r.x+x,r.y+y);
				if((p>>>24)<128)p=0xFFFFFF;
				row[x]=(byte)(p>>16);row[x+r.width]=(byte)(p>>8);row[x+2*r.width]=(byte)p;
			}
			packed(out,row,rowBytes);
		}
	}

	/** A row as QuickDraw reads it: unpacked under 8 bytes, else PackBits with its length (a byte up to 250 bytes a row, else a word). */
	private static void packed(DataOutputStream out,byte[] row,int rowBytes) throws IOException {
		if(rowBytes<8){out.write(row);return;}
		byte[] p=packBits(row);
		if(rowBytes>250)out.writeShort(p.length);else out.writeByte(p.length);
		out.write(p);
	}
	static byte[] packBits(byte[] in){
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		int i=0;
		while(i<in.length){
			int run=1;
			while(i+run<in.length&&run<128&&in[i+run]==in[i])run++;
			if(run>=3){out.write(1-run);out.write(in[i]);i+=run;continue;}
			int start=i;
			while(i<in.length&&i-start<128){
				if(i+2<in.length&&in[i]==in[i+1]&&in[i]==in[i+2])break;
				i++;
			}
			out.write(i-start-1);out.write(in,start,i-start);
		}
		return out.toByteArray();
	}
}
