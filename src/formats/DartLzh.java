import java.io.IOException;

/**
 * The "best" compression of DART disk images ({@link DiskImage.DartSource}): Yoshizaki and Okumura's LZHUF - LZSS with literals
 * and lengths in an adaptive Huffman code, the top 6 bits of a 12-bit distance in a static one - as the public-domain LZHUF.C
 * has it, with no 4-byte length in front and a window starting with zeros instead of spaces. Every chunk starts afresh and
 * holds at most 20960 bytes, so the frequency at the root stays far below the 32768 at which LZHUF halves them all; that
 * step is not here.
 */
final class DartLzh {
	private static final int N=4096,F=60,THRESHOLD=2,N_CHAR=256-THRESHOLD+F,T=2*N_CHAR-1,R=T-1;
	/** The static code of a distance's top 6 bits, by the first 8 bits read: its value and its length in bits. */
	private static final byte[] D_CODE=new byte[256],D_LENGTH=new byte[256];
	static{
		int at=0,code=0,length=3;
		for(int codes:new int[]{1,3,8,12,24,16}){ // how many codes there are of 3, 4 ... 8 bits
			for(int c=0;c<codes;c++,code++)
				for(int k=1<<(8-length);k>0;k--,at++){D_CODE[at]=(byte)code;D_LENGTH[at]=(byte)length;}
			length++;
		}
	}

	private final byte[] in;private int position,buffer,count;
	/** The tree: leaves are T + symbol; a node's two sons are son[node] and son[node] + 1; freq[T] is a sentinel. */
	private final int[] freq=new int[T+1],son=new int[T],prnt=new int[T+N_CHAR];

	private DartLzh(byte[] in){
		this.in=in;
		for(int i=0;i<N_CHAR;i++){freq[i]=1;son[i]=i+T;prnt[i+T]=i;}
		for(int i=0,j=N_CHAR;j<=R;i+=2,j++){freq[j]=freq[i]+freq[i+1];son[j]=i;prnt[i]=prnt[i+1]=j;}
		freq[T]=0xffff;prnt[R]=0;
	}

	/** The first {@code size} bytes of a compressed chunk. IOException when the data ends before them. */
	static byte[] decompress(byte[] in,int size) throws IOException {
		DartLzh lzh=new DartLzh(in);
		byte[] out=new byte[size],window=new byte[N];
		int r=N-F,o=0;
		while(o<size){
			int c=lzh.symbol();
			if(c<256){out[o++]=(byte)c;window[r]=(byte)c;r=(r+1)&(N-1);continue;}
			int from=(r-lzh.distance()-1)&(N-1);
			for(int n=c-255+THRESHOLD;n>0&&o<size;n--){
				byte b=window[from];from=(from+1)&(N-1);
				out[o++]=b;window[r]=b;r=(r+1)&(N-1);
			}
		}
		return out;
	}

	private int bit() throws IOException {
		if(count==0){
			if(position>=in.length)throw new IOException("DART chunk ends early");
			buffer=in[position++];count=8;
		}
		return (buffer>>>--count)&1;
	}
	private int symbol() throws IOException {
		int c=son[R];
		while(c<T)c=son[c+bit()];
		c-=T;update(c);return c;
	}
	private int distance() throws IOException {
		int i=0;
		for(int k=0;k<8;k++)i=i<<1|bit();
		int top=D_CODE[i]<<6;
		for(int k=D_LENGTH[i]-2;k>0;k--)i=i<<1|bit();
		return top|i&0x3f;
	}
	/** Counts {@code symbol} up to the root, keeping the nodes in order of frequency. */
	private void update(int symbol){
		int c=prnt[symbol+T];
		do{
			int k=++freq[c];
			int l=c+1;
			if(k>freq[l]){
				while(k>freq[++l]);
				l--;
				freq[c]=freq[l];freq[l]=k;
				int i=son[c];prnt[i]=l;if(i<T)prnt[i+1]=l;
				int j=son[l];son[l]=i;
				prnt[j]=c;if(j<T)prnt[j+1]=c;
				son[c]=j;
				c=l;
			}
		}while((c=prnt[c])!=0);
	}
}
