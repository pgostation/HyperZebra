import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * BinHex 4.0 decoding (".hqx": the text form Mac files travelled the net in). The text between the colons is a 6-bit
 * encoding of a run-length compressed stream holding the file name, type, creator, Finder flags, both forks and three
 * CRCs. The result is handed over as a MacBinary container, which every reader here understands, so a BinHex file can
 * be turned into something the 68K VM opens without any Mac program's help (the expander programs themselves arrive
 * as .hqx).
 */
final class BinHex {
	private BinHex(){}
	private static final String ALPHABET="!\"#$%&'()*+,-012345689@ABCDEFGHIJKLMNPQRSTUVXYZ[`abcdefhijklmpqr";
	private static final int[] VALUE=new int[128];
	static{java.util.Arrays.fill(VALUE,-1);for(int i=0;i<ALPHABET.length();i++)VALUE[ALPHABET.charAt(i)]=i;}

	/** Whether the file starts like a BinHex 4.0 file (its banner line, or a colon-delimited body). */
	static boolean looksLikeBinHex(File f){
		if(f==null||!f.isFile())return false;
		try(java.io.InputStream in=Files.newInputStream(f.toPath())){
			String head=new String(in.readNBytes(64),StandardCharsets.ISO_8859_1);
			return head.contains("(This file must be converted with BinHex")||head.trim().startsWith(":");
		}catch(IOException e){return false;}
	}

	/** Decodes the file into a MacBinary container (name, type, creator, flags, both forks); CRC mismatches are an error. */
	static ResourceFile68k.MacBinary decode(File f) throws IOException {
		String text=new String(Files.readAllBytes(f.toPath()),StandardCharsets.ISO_8859_1);
		int start=text.indexOf("(This file must be converted with BinHex");
		if(start>=0)start=text.indexOf(':',start);else start=text.indexOf(':');
		if(start<0)throw new IOException("not a BinHex file: "+f.getName());
		int end=text.indexOf(':',start+1);
		if(end<0)throw new IOException("BinHex data is not closed by a colon: "+f.getName());
		// 6-bit characters → bytes
		ByteArrayOutputStream packed=new ByteArrayOutputStream();
		int acc=0,bits=0;
		for(int i=start+1;i<end;i++){
			char c=text.charAt(i);
			if(c=='\r'||c=='\n'||c=='\t'||c==' ')continue;
			int v=c<128?VALUE[c]:-1;
			if(v<0)throw new IOException("bad BinHex character '"+c+"' in "+f.getName());
			acc=(acc<<6)|v;bits+=6;
			if(bits>=8){bits-=8;packed.write((acc>>>bits)&0xff);}
		}
		// run-length: 0x90 n repeats the previous byte n-1 more times; 0x90 0x00 is a literal 0x90
		byte[] rle=packed.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();
		int last=-1;
		for(int i=0;i<rle.length;i++){
			int b=rle[i]&0xff;
			if(b==0x90&&i+1<rle.length){
				int n=rle[++i]&0xff;
				if(n==0){out.write(0x90);last=0x90;}
				else for(int k=1;k<n;k++)out.write(last);
			}else{out.write(b);last=b;}
		}
		byte[] d=out.toByteArray();
		int at=0;
		int nameLength=d[at++]&0xff;
		byte[] name=java.util.Arrays.copyOfRange(d,at,at+nameLength);at+=nameLength;
		at++; // version
		int type=u4(d,at),creator=u4(d,at+4),flags=u2(d,at+8);at+=10;
		int dataLength=u4(d,at),resourceLength=u4(d,at+4);at+=8;
		int headerCrc=u2(d,at);at+=2;
		if(crc(d,0,at-2)!=headerCrc)throw new IOException("BinHex header CRC mismatch: "+f.getName());
		if(dataLength<0||resourceLength<0||at+dataLength+2+resourceLength+2>d.length)throw new IOException("BinHex data is truncated: "+f.getName());
		byte[] data=java.util.Arrays.copyOfRange(d,at,at+dataLength);at+=dataLength;
		if(crc(data,0,data.length)!=u2(d,at))throw new IOException("BinHex data fork CRC mismatch: "+f.getName());
		at+=2;
		byte[] rsrc=java.util.Arrays.copyOfRange(d,at,at+resourceLength);at+=resourceLength;
		if(crc(rsrc,0,rsrc.length)!=u2(d,at))throw new IOException("BinHex resource fork CRC mismatch: "+f.getName());
		ResourceFile68k.MacBinary mb=new ResourceFile68k.MacBinary();
		mb.header[1]=(byte)nameLength;System.arraycopy(name,0,mb.header,2,nameLength);
		putInt(mb.header,65,type);putInt(mb.header,69,creator);
		mb.header[73]=(byte)(flags>>8);mb.header[101]=(byte)flags; // Finder flags: high byte, low byte (MacBinary II)
		mb.header[122]=(byte)129;mb.header[123]=(byte)129; // MacBinary II version written / needed
		mb.nameBytes=name;mb.name=StackTextEncoding.ROMAN.decode(name);mb.type=type;mb.creator=creator;
		mb.dataFork=data;mb.resourceFork=rsrc;
		return mb;
	}

	/** Decodes into a MacBinary file beside the source (named after the file inside, ".bin" appended) and returns it. */
	static File decodeToMacBinary(File source,File folder) throws IOException {
		ResourceFile68k.MacBinary mb=decode(source);
		String name=mb.name.replace('/',':');
		File target=new File(folder,name+".bin");
		mb.write(target);
		return target;
	}

	/** CRC-16/XMODEM (polynomial $1021, initial 0), as BinHex 4.0 computes it. */
	static int crc(byte[] b,int from,int to){
		int crc=0;
		for(int i=from;i<to;i++){
			crc^=(b[i]&0xff)<<8;
			for(int k=0;k<8;k++)crc=(crc&0x8000)!=0?((crc<<1)^0x1021)&0xffff:(crc<<1)&0xffff;
		}
		return crc;
	}
	private static int u4(byte[] b,int at){return (b[at]&0xff)<<24|(b[at+1]&0xff)<<16|(b[at+2]&0xff)<<8|(b[at+3]&0xff);}
	private static int u2(byte[] b,int at){return (b[at]&0xff)<<8|(b[at+1]&0xff);}
	private static void putInt(byte[] b,int at,int v){b[at]=(byte)(v>>>24);b[at+1]=(byte)(v>>>16);b[at+2]=(byte)(v>>>8);b[at+3]=(byte)v;}
}
