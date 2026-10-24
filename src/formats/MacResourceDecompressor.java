import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Expands System 7 compressed resources ('dcmp' 0, 1, 2 and 3).
 *
 * System 7 marks such resources with the resCompressed attribute (bit 0) and stores them behind a
 * header starting with the signature $A89F6572; the Resource Manager runs the 'dcmp' code resource
 * named in the header when the resource is loaded. Apple shipped its own applications this way
 * (including applications and the Finder), so without this a program's CODE resources are garbage.
 * No System file is available here, so the three Apple decompressors are reimplemented natively.
 * Formats 0-2 follow the description in the python-rsrcfork project (dgelessus/python-rsrcfork, MIT); format 3 (the
 * bit-stream LZ77 of System 7.5, used by the Desktop Patterns control panel's 'ppat's and icons) was worked out from the
 * behaviour of the 7.5.3 System's decompressor (facts only, no code taken over).
 */
final class MacResourceDecompressor {
	/** resCompressed resource attribute. */
	static final int RES_COMPRESSED=1;

	private MacResourceDecompressor(){}

	/** True when the bytes carry a compressed-resource header. */
	static boolean isCompressed(byte[] b){
		return b!=null&&b.length>=18&&(b[0]&0xff)==0xa8&&(b[1]&0xff)==0x9f&&b[2]=='e'&&b[3]=='r';
	}

	/** True when one of the built-in 'dcmp's (0-3) expands it; another one is the program's own (REALbasic's 'dcmp' 200), which
	 *  the 68K Resource Manager runs as it loads the resource. */
	static boolean hasBuiltInDecompressor(byte[] b){
		if(!isCompressed(b))return false;
		int compressionType=u2(b,6);
		if(compressionType==0x0801){int dcmp=(short)u2(b,14);return dcmp==0||dcmp==1;}
		if(compressionType==0x0901){int dcmp=(short)u2(b,12);return dcmp==2||dcmp==3;}
		return false;
	}

	/** The expanded resource; {@code data} itself when it is not compressed. */
	static byte[] decompress(byte[] data) throws IOException {
		if(!isCompressed(data))return data;
		int headerLength=u2(data,4),compressionType=u2(data,6),decompressedLength=u4(data,8);
		if(headerLength!=0&&headerLength!=0x12)throw new IOException("unsupported compressed resource header length "+headerLength);
		if(decompressedLength<0)throw new IOException("bad decompressed length");
		Input in=new Input(data,18);
		byte[] out;
		if(compressionType==0x0801){
			int dcmp=(short)u2(data,14);
			if(dcmp==0)out=dcmp0(in,decompressedLength);
			else if(dcmp==1)out=dcmp1(in);
			else throw new IOException("unsupported 'dcmp' "+dcmp+" for type 8 compression");
		}else if(compressionType==0x0901){
			int dcmp=(short)u2(data,12);
			if(dcmp==2)out=dcmp2(in,decompressedLength,u2(data,14),data[16]&0xff,data[17]&0xff);
			else if(dcmp==3)out=dcmp3(data,18,decompressedLength);
			else throw new IOException("unsupported 'dcmp' "+dcmp+" for type 9 compression");
		}else throw new IOException(String.format("unsupported compression type $%04x",compressionType));
		if(out.length<decompressedLength)throw new IOException("decompressed "+out.length+" bytes, header says "+decompressedLength);
		if(out.length>decompressedLength){byte[] t=new byte[decompressedLength];System.arraycopy(out,0,t,0,decompressedLength);out=t;}
		return out;
	}

	/** Headerless data of 'dcmp' 0 or 1 (a caller that passes the bytes after the header), up to its end tag. */
	static byte[] expandBody(int dcmp,byte[] body) throws IOException {
		if(dcmp==0)return dcmp0(new Input(body,0),body.length);
		if(dcmp==1)return dcmp1(new Input(body,0));
		throw new IOException("'dcmp' "+dcmp+" needs its header");
	}

	private static int u2(byte[] b,int i){return (b[i]&0xff)<<8|(b[i+1]&0xff);}
	private static int u4(byte[] b,int i){return (b[i]&0xff)<<24|(b[i+1]&0xff)<<16|(b[i+2]&0xff)<<8|(b[i+3]&0xff);}

	private static final class Input {
		final byte[] b;int pos;
		Input(byte[] b,int pos){this.b=b;this.pos=pos;}
		boolean atEnd(){return pos>=b.length;}
		int remaining(){return b.length-pos;}
		int u1() throws IOException {if(pos>=b.length)throw new IOException("compressed resource data is truncated");return b[pos++]&0xff;}
		byte[] bytes(int n) throws IOException {
			if(n<0||pos+n>b.length)throw new IOException("compressed resource data is truncated");
			byte[] r=new byte[n];System.arraycopy(b,pos,r,0,n);pos+=n;return r;
		}
		/** The variable-length integer of 'dcmp' 0 and 1: 1 byte signed, $80-$FE = (byte-$C0)<<8|next, $FF = 4-byte signed. */
		int varInt() throws IOException {
			int head=u1();
			if(head==0xff){int v=0;for(int i=0;i<4;i++)v=v<<8|u1();return v;}
			if(head>=0x80)return (short)(((head-0xc0)&0xff)<<8|u1());
			return (byte)head;
		}
	}

	private static void put2(ByteArrayOutputStream out,int v){out.write(v>>8);out.write(v);}
	private static void put4(ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}

	// ------------------------------------------------------------------ 'dcmp' 0 ("DonnBits")

	/** Two-byte values for tag bytes $4B..$FD of 'dcmp' 0 (mostly common 68K opcode words and small offsets). */
	private static final int[] TABLE0={
		0x0000,0x4eba,0x0008,0x4e75,0x000c,
		0x4ead,0x2053,0x2f0b,0x6100,0x0010,0x7000,0x2f00,0x486e,
		0x2050,0x206e,0x2f2e,0xfffc,0x48e7,0x3f3c,0x0004,0xfff8,
		0x2f0c,0x2006,0x4eed,0x4e56,0x2068,0x4e5e,0x0001,0x588f,
		0x4fef,0x0002,0x0018,0x6000,0xffff,0x508f,0x4e90,0x0006,
		0x266e,0x0014,0xfff4,0x4cee,0x000a,0x000e,0x41ee,0x4cdf,
		0x48c0,0xfff0,0x2d40,0x0012,0x302e,0x7001,0x2f28,0x2054,
		0x6700,0x0020,0x001c,0x205f,0x1800,0x266f,0x4878,0x0016,
		0x41fa,0x303c,0x2840,0x7200,0x286e,0x200c,0x6600,0x206b,
		0x2f07,0x558f,0x0028,0xfffe,0xffec,0x22d8,0x200b,0x000f,
		0x598f,0x2f3c,0xff00,0x0118,0x81e1,0x4a00,0x4eb0,0xffe8,
		0x48c7,0x0003,0x0022,0x0007,0x001a,0x6706,0x6708,0x4ef9,
		0x0024,0x2078,0x0800,0x6604,0x002a,0x4ed0,0x3028,0x265f,
		0x6704,0x0030,0x43ee,0x3f00,0x201f,0x001e,0xfff6,0x202e,
		0x42a7,0x2007,0xfffa,0x6002,0x3d40,0x0c40,0x6606,0x0026,
		0x2d48,0x2f01,0x70ff,0x6004,0x1880,0x4a40,0x0040,0x002c,
		0x2f08,0x0011,0xffe4,0x2140,0x2640,0xfff2,0x426e,0x4eb9,
		0x3d7c,0x0038,0x000d,0x6006,0x422e,0x203c,0x670c,0x2d68,
		0x6608,0x4a2e,0x4aae,0x002e,0x4840,0x225f,0x2200,0x670a,
		0x3007,0x4267,0x0032,0x2028,0x0009,0x487a,0x0200,0x2f2b,
		0x0005,0x226e,0x6602,0xe580,0x670e,0x660a,0x0050,0x3e00,
		0x660c,0x2e00,0xffee,0x206d,0x2040,0xffe0,0x5340,0x6008,
		0x0480,0x0068,0x0b7c,0x4400,0x41e8,0x4841};

	private static byte[] dcmp0(Input in,int decompressedLength) throws IOException {
		ByteArrayOutputStream out=new ByteArrayOutputStream(decompressedLength+16);
		java.util.List<byte[]> literals=new java.util.ArrayList<byte[]>();
		while(true){
			int tag=in.u1();
			if(tag<0x20){                                   // literal run of 2*n bytes; $10.. also remembers it
				int half=(tag==0x00||tag==0x10)?in.varInt():tag&0xf;
				if(half<0)throw new IOException("bad literal length "+half);
				byte[] literal=in.bytes(2*half);
				if(tag>=0x10)literals.add(literal);
				out.write(literal,0,literal.length);
			}else if(tag==0x20||tag==0x21){                  // remembered literal $28..$227
				byte[] l=literal(literals,0x28+((tag-0x20)<<8|in.u1()));out.write(l,0,l.length);
			}else if(tag==0x22){                            // remembered literal $28.. (3-byte form)
				int index=in.u1()<<8;index|=in.u1();
				byte[] l=literal(literals,0x28+index);out.write(l,0,l.length);
			}else if(tag<0x4b){                             // remembered literal 0..$27
				byte[] l=literal(literals,tag-0x23);out.write(l,0,l.length);
			}else if(tag<0xfe){                             // fixed table
				put2(out,TABLE0[tag-0x4b]);
			}else if(tag==0xfe){
				int kind=in.u1();
				switch(kind){
					case 0x00:{                                  // jump-table entries: [offset] MOVE.W #seg,-(SP) _LoadSeg
						int segment=in.varInt();
						put2(out,0x3f3c);put2(out,segment);put2(out,0xa9f0);
						int count=in.varInt();
						if(count<0)throw new IOException("bad jump table entry count "+count);
						int current=6;
						for(int i=0;i<count;i++){
							current=(current+in.varInt()-6)&0xffff;
							put2(out,current);put2(out,0x3f3c);put2(out,segment);put2(out,0xa9f0);
						}
						break;}
					case 0x01:{                                  // BSR.W d / JMP d(A5) pairs (the Finder's CODE 3)
						// BSR displacement, A5 offset step (0: every offset is read), count-1, first A5 offset; each 8-byte
						// entry sits 8 bytes further from the BSR target
						int displacement=in.varInt(),step=in.varInt(),count=in.varInt()+1,offset=in.varInt();
						if(count<=0)throw new IOException("bad BSR/JMP entry count "+count);
						for(int i=0;i<count;i++){
							if(i>0){displacement-=8;offset=step!=0?offset+step:in.varInt();}
							put2(out,0x6100);put2(out,displacement);put2(out,0x4eed);put2(out,offset);
						}
						break;}
					case 0x02:case 0x03:{                        // repeat a byte / a word
						int value=in.varInt(),count=in.varInt()+1;
						if(count<=0)throw new IOException("bad repeat count "+count);
						for(int i=0;i<count;i++){if(kind==0x03)out.write(value>>8);out.write(value);}
						break;}
					case 0x04:{                                  // words, then 8-bit signed deltas
						int current=in.varInt()&0xffff;put2(out,current);
						int count=in.varInt();
						if(count<0)throw new IOException("bad delta count "+count);
						for(int i=0;i<count;i++){current=(current+(byte)in.u1())&0xffff;put2(out,current);}
						break;}
					case 0x05:{                                  // words, then variable-length deltas
						int current=in.varInt();put2(out,current);
						int count=in.varInt();
						if(count<0)throw new IOException("bad delta count "+count);
						for(int i=0;i<count;i++){current+=in.varInt();put2(out,current);}
						break;}
					case 0x06:{                                  // longs, then variable-length deltas
						int current=in.varInt();put4(out,current);
						int count=in.varInt();
						if(count<0)throw new IOException("bad delta count "+count);
						for(int i=0;i<count;i++){current+=in.varInt();put4(out,current);}
						break;}
					default:throw new IOException(String.format("unknown 'dcmp' 0 extended code $%02x",kind));
				}
			}else break;                                     // $FF: end of data
		}
		return out.toByteArray();
	}

	private static byte[] literal(java.util.List<byte[]> literals,int index) throws IOException {
		if(index<0||index>=literals.size())throw new IOException("back-reference to literal "+index+" of "+literals.size());
		return literals.get(index);
	}

	// ------------------------------------------------------------------ 'dcmp' 1

	/** Two-byte values for tag bytes $D5..$FD of 'dcmp' 1. */
	private static final int[] TABLE1={
		0x0000,0x0001,0x0002,
		0x0003,0x2e01,0x3e01,0x0101,0x1e01,0xffff,0x0e01,0x3100,
		0x1112,0x0107,0x3332,0x1239,0xed10,0x0127,0x2322,0x0137,
		0x0706,0x0117,0x0123,0x00ff,0x002f,0x070e,0xfd3c,0x0135,
		0x0115,0x0102,0x0007,0x003e,0x05d5,0x0201,0x0607,0x0708,
		0x3001,0x0133,0x0010,0x1716,0x373e,0x3637};

	private static byte[] dcmp1(Input in) throws IOException {
		ByteArrayOutputStream out=new ByteArrayOutputStream(in.remaining()*3);
		java.util.List<byte[]> literals=new java.util.ArrayList<byte[]>();
		while(true){
			int tag=in.u1();
			if(tag<0x20){                                   // literal of 1..16 bytes; $10.. also remembers it
				byte[] literal=in.bytes((tag&0xf)+1);
				if(tag>=0x10)literals.add(literal);
				out.write(literal,0,literal.length);
			}else if(tag<0xd0){                             // remembered literal 0..$AF
				byte[] l=literal(literals,tag-0x20);out.write(l,0,l.length);
			}else if(tag==0xd0||tag==0xd1){                  // literal with a length byte; $D1 remembers it
				byte[] literal=in.bytes(in.u1());
				if(tag==0xd1)literals.add(literal);
				out.write(literal,0,literal.length);
			}else if(tag==0xd2){                            // remembered literal $B0..$1AF
				byte[] l=literal(literals,in.u1()+0xb0);out.write(l,0,l.length);
			}else if(tag>=0xd5&&tag<0xfe){                   // fixed table
				put2(out,TABLE1[tag-0xd5]);
			}else if(tag==0xfe){
				int kind=in.u1();
				if(kind!=0x02)throw new IOException(String.format("unknown 'dcmp' 1 extended code $%02x",kind));
				int value=in.varInt(),count=in.varInt()+1;  // repeat a byte
				if(count<=0)throw new IOException("bad repeat count "+count);
				for(int i=0;i<count;i++)out.write(value);
			}else if(tag==0xff)break;
			else throw new IOException(String.format("unknown 'dcmp' 1 tag byte $%02x",tag));
		}
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ 'dcmp' 3

	/**
	 * 'dcmp' 3: an MSB-first bit stream of tokens. A token opens with a length code; code 0 right after a match (or after
	 * a full 63-byte literal run, and at the start) introduces a literal run of raw 8-bit bytes, anything else is a match
	 * of code+2 bytes (code+3 when the previous token was a short literal run, which a match must follow). A match's
	 * distance code depends on how many bytes are already out: the further in, the wider its three classes.
	 */
	private static byte[] dcmp3(byte[] data,int start,int decompressedLength) throws IOException {
		Bits in=new Bits(data,start);
		byte[] out=new byte[decompressedLength+2048];
		int n=0;
		boolean literalAllowed=true;
		while(n<decompressedLength){
			int code=dcmp3Length(in);
			if(code!=0||!literalAllowed){
				int length=code+(literalAllowed?2:3);
				literalAllowed=true;
				int distance=dcmp3Distance(in,n);
				if(distance>n)throw new IOException("'dcmp' 3 distance "+distance+" before the start of the output ("+n+")");
				if(n+length>out.length)throw new IOException("'dcmp' 3 match runs past the end");
				for(int i=0;i<length;i++,n++)out[n]=out[n-distance];
			}else{
				int length=dcmp3LiteralLength(in);
				if(n+length>out.length)throw new IOException("'dcmp' 3 literal run past the end");
				for(int i=0;i<length;i++)out[n++]=(byte)in.bits(8);
				literalAllowed=length>=63;
			}
		}
		return n==out.length?out:java.util.Arrays.copyOf(out,n);
	}

	/** Match length code: up to 10 one-bits select a class, each class a run of values (0-1, 2-4, 5-10, 11-18, 19-26, 27-58, ...). */
	private static int dcmp3Length(Bits in) throws IOException {
		int ones=0;
		while(ones<10&&in.bits(1)!=0)ones++;
		switch(ones){
			case 0:return in.bits(1);
			case 1:return in.bits(1)!=0?3+in.bits(1):2;
			case 2:return in.bits(1)!=0?7+in.bits(2):5+in.bits(1);
			case 3:return 11+in.bits(3);
			case 4:return 19+in.bits(3);
			default:{int width=ones;return (1<<width)-5+in.bits(width);} // 5: 27+5 bits ... 10: 1019+10 bits
		}
	}

	/** Literal run length 1-63. */
	private static int dcmp3LiteralLength(Bits in) throws IOException {
		if(in.bits(1)==0)return 1;
		switch(in.bits(2)){
			case 0:return 2;
			case 1:return 3;
			case 2:return 4+in.bits(2);
			default:{
				int v=in.bits(4);
				if(v<=7)return 8+v;
				if(v<=11)return 16+(v-8)*4+in.bits(2);
				return 32+(v-12)*8+in.bits(3);
			}
		}
	}

	/** Upper bounds of the output position for the 15 distance-code tiers (the last tier covers the rest). */
	private static final int[] DCMP3_TIERS={10,20,40,80,160,672,1000,2688,5376,10752,21504,43008,70000,172032};

	/**
	 * Match distance for a match that starts {@code position} bytes into the output. Tier t: "0" = 1 + t bits,
	 * "10" = 1+2^t + (t+2) bits, "11" = the rest with just enough bits to reach {@code position} (at least 1, at most 18).
	 * The decompressor this mirrors uses 11 bits instead of 10 for positions 1645-1664 of tier 7; streams follow it.
	 */
	private static int dcmp3Distance(Bits in,int position) throws IOException {
		int tier=0;
		while(tier<DCMP3_TIERS.length&&position>DCMP3_TIERS[tier])tier++;
		if(in.bits(1)==0)return 1+in.bits(tier);
		int second=1+(1<<tier);
		if(in.bits(1)==0)return second+in.bits(tier+2);
		int third=second+(1<<(tier+2)),width=1;
		while(third+(1<<width)-1<position)width++;
		if(tier==7&&position>=1645&&position<=1664)width=11;
		if(width>18)width=18;
		return third+in.bits(width);
	}

	/** MSB-first bit reader; bytes past the end read as 0 (the stream's final byte is padded). */
	private static final class Bits {
		final byte[] b;int pos;long buffer;int count;
		Bits(byte[] b,int pos){this.b=b;this.pos=pos;}
		int bits(int n) throws IOException {
			if(n==0)return 0;
			while(count<n){
				if(pos>=b.length+4)throw new IOException("'dcmp' 3 data is truncated");
				buffer=buffer<<8|(pos<b.length?b[pos]&0xff:0);pos++;count+=8;
			}
			count-=n;
			return (int)(buffer>>>count)&((1<<n)-1);
		}
	}

	// ------------------------------------------------------------------ 'dcmp' 2 ("GreggyBits")

	/** Default two-byte table of 'dcmp' 2 (256 entries). */
	private static final int[] TABLE2={
		0x0000,0x0008,0x4eba,0x206e,0x4e75,0x000c,0x0004,0x7000,
		0x0010,0x0002,0x486e,0xfffc,0x6000,0x0001,0x48e7,0x2f2e,
		0x4e56,0x0006,0x4e5e,0x2f00,0x6100,0xfff8,0x2f0b,0xffff,
		0x0014,0x000a,0x0018,0x205f,0x000e,0x2050,0x3f3c,0xfff4,
		0x4cee,0x302e,0x6700,0x4cdf,0x266e,0x0012,0x001c,0x4267,
		0xfff0,0x303c,0x2f0c,0x0003,0x4ed0,0x0020,0x7001,0x0016,
		0x2d40,0x48c0,0x2078,0x7200,0x588f,0x6600,0x4fef,0x42a7,
		0x6706,0xfffa,0x558f,0x286e,0x3f00,0xfffe,0x2f3c,0x6704,
		0x598f,0x206b,0x0024,0x201f,0x41fa,0x81e1,0x6604,0x6708,
		0x001a,0x4eb9,0x508f,0x202e,0x0007,0x4eb0,0xfff2,0x3d40,
		0x001e,0x2068,0x6606,0xfff6,0x4ef9,0x0800,0x0c40,0x3d7c,
		0xffec,0x0005,0x203c,0xffe8,0xdefc,0x4a2e,0x0030,0x0028,
		0x2f08,0x200b,0x6002,0x426e,0x2d48,0x2053,0x2040,0x1800,
		0x6004,0x41ee,0x2f28,0x2f01,0x670a,0x4840,0x2007,0x6608,
		0x0118,0x2f07,0x3028,0x3f2e,0x302b,0x226e,0x2f2b,0x002c,
		0x670c,0x225f,0x6006,0x00ff,0x3007,0xffee,0x5340,0x0040,
		0xffe4,0x4a40,0x660a,0x000f,0x4ead,0x70ff,0x22d8,0x486b,
		0x0022,0x204b,0x670e,0x4aae,0x4e90,0xffe0,0xffc0,0x002a,
		0x2740,0x6702,0x51c8,0x02b6,0x487a,0x2278,0xb06e,0xffe6,
		0x0009,0x322e,0x3e00,0x4841,0xffea,0x43ee,0x4e71,0x7400,
		0x2f2c,0x206c,0x003c,0x0026,0x0050,0x1880,0x301f,0x2200,
		0x660c,0xffda,0x0038,0x6602,0x302c,0x200c,0x2d6e,0x4240,
		0xffe2,0xa9f0,0xff00,0x377c,0xe580,0xffdc,0x4868,0x594f,
		0x0034,0x3e1f,0x6008,0x2f06,0xffde,0x600a,0x7002,0x0032,
		0xffcc,0x0080,0x2251,0x101f,0x317c,0xa029,0xffd8,0x5240,
		0x0100,0x6710,0xa023,0xffce,0xffd4,0x2006,0x4878,0x002e,
		0x504f,0x43fa,0x6712,0x7600,0x41e8,0x4a6e,0x20d9,0x005a,
		0x7fff,0x51ca,0x005c,0x2e00,0x0240,0x48c7,0x6714,0x0c80,
		0x2e9f,0xffd6,0x8000,0x1000,0x4842,0x4a6b,0xffd2,0x0048,
		0x4a47,0x4ed1,0x206f,0x0041,0x600c,0x2a78,0x422e,0x3200,
		0x6574,0x6716,0x0044,0x486d,0x2008,0x486c,0x0b7c,0x2640,
		0x0400,0x0068,0x206d,0x000d,0x2a40,0x000b,0x003e,0x0220};

	private static byte[] dcmp2(Input in,int decompressedLength,int unknown,int tableCountMinus1,int flags) throws IOException {
		if((flags&~3)!=0)throw new IOException(String.format("unsupported 'dcmp' 2 flags $%02x",flags));
		int[] table;
		if((flags&1)!=0){table=new int[tableCountMinus1+1];for(int i=0;i<table.length;i++){int v=in.u1()<<8;table[i]=v|in.u1();}}
		else{if(tableCountMinus1!=0)throw new IOException("custom table size given without the custom table flag");table=TABLE2;}
		ByteArrayOutputStream out=new ByteArrayOutputStream(decompressedLength+16);
		boolean odd=(decompressedLength&1)!=0;
		if((flags&2)==0){                                   // untagged: every byte indexes the table
			while(!in.atEnd()){
				int b=in.u1();
				if(in.atEnd()&&odd){out.write(b);break;}         // odd length: last byte is a literal
				put2(out,tableIndex(table,b));
			}
		}else{                                              // tagged: a tag byte, then 8 items (bit set = table reference, clear = 2 literal bytes)
			outer:
			while(!in.atEnd()){
				int tag=in.u1();
				if(in.atEnd()&&odd){out.write(tag);break;}
				for(int bit=7;bit>=0;bit--){
					if(in.atEnd())break outer;
					if((tag&(1<<bit))!=0)put2(out,tableIndex(table,in.u1()));
					else{out.write(in.u1());if(!in.atEnd())out.write(in.u1());}
				}
			}
		}
		return out.toByteArray();
	}

	private static int tableIndex(int[] table,int index) throws IOException {
		if(index>=table.length)throw new IOException("'dcmp' 2 table reference "+index+" beyond "+table.length+" entries");
		return table[index];
	}
}
