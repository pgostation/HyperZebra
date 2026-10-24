import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A bzip2 decompressor (the chunks of UDBZ disk images): Huffman-coded MTF/run-length symbols, the inverse Burrows-Wheeler
 * transform and the final run-length stage, as the format is documented. Concatenated streams are read one after another;
 * the long-obsolete "randomised" blocks are refused. CRCs are not checked (a damaged chunk gives damaged sectors).
 */
final class Bzip2 {
	private Bzip2(){}

	private static final int MAX_GROUPS=6,MAX_ALPHA=258,MAX_CODE_LENGTH=23,GROUP_SIZE=50;

	private static final class Bits {
		final byte[] in;int position;int buffer,count;
		Bits(byte[] in){this.in=in;}
		int read(int n) throws IOException {
			while(count<n){if(position>=in.length)throw new IOException("bzip2 data ends early");buffer=(buffer<<8)|(in[position++]&0xff);count+=8;}
			int v=(buffer>>>(count-n))&((1<<n)-1);count-=n;buffer&=(1<<count)-1;return v;
		}
		boolean bit() throws IOException {return read(1)!=0;}
		boolean atEnd(){return position>=in.length&&count<8;}
		void alignToByte(){count-=count%8;buffer&=(1<<count)-1;}
	}

	/** Decompresses {@code in}; {@code expected} (or less) bytes come back, more are an error. */
	static byte[] decompress(byte[] in,int expected) throws IOException {
		ByteArrayOutputStream out=new ByteArrayOutputStream(Math.max(16,expected));
		Bits bits=new Bits(in);
		while(!bits.atEnd()){
			if(bits.count%8!=0)bits.alignToByte();
			if(bits.atEnd())break;
			int b=bits.read(8);if(b==0)continue; // padding between streams
			if(b!='B'||bits.read(8)!='Z'||bits.read(8)!='h')throw new IOException("not bzip2 data");
			int level=bits.read(8)-'0';if(level<1||level>9)throw new IOException("bad bzip2 block size");
			int[] tt=new int[level*100000];
			while(true){
				long magic=((long)bits.read(24)<<24)|bits.read(24);
				if(magic==0x177245385090L){bits.read(32);break;}
				if(magic!=0x314159265359L)throw new IOException("bad bzip2 block");
				bits.read(32); // block CRC
				block(bits,tt,out);
				if(out.size()>expected)throw new IOException("bzip2 chunk longer than expected");
			}
		}
		byte[] result=out.toByteArray();
		return result.length==expected?result:java.util.Arrays.copyOf(result,expected);
	}

	private static void block(Bits bits,int[] tt,ByteArrayOutputStream out) throws IOException {
		if(bits.bit())throw new IOException("randomised bzip2 blocks are not supported");
		int origin=bits.read(24);
		// the bytes used in the block
		int[] seqToUnseq=new int[256];int inUse=0;
		int ranges=bits.read(16);
		for(int i=0;i<16;i++)if((ranges&(0x8000>>>i))!=0){int used=bits.read(16);for(int j=0;j<16;j++)if((used&(0x8000>>>j))!=0)seqToUnseq[inUse++]=i*16+j;}
		if(inUse==0)throw new IOException("bad bzip2 block (no symbols)");
		int alphaSize=inUse+2;
		int groups=bits.read(3),selectors=bits.read(15);
		if(groups<2||groups>MAX_GROUPS||selectors<1)throw new IOException("bad bzip2 tables");
		// selectors, move-to-front coded
		int[] mtfGroups=new int[groups];for(int i=0;i<groups;i++)mtfGroups[i]=i;
		int[] selector=new int[selectors];
		for(int i=0;i<selectors;i++){
			int j=0;while(bits.bit()){j++;if(j>=groups)throw new IOException("bad bzip2 selector");}
			int v=mtfGroups[j];System.arraycopy(mtfGroups,0,mtfGroups,1,j);mtfGroups[0]=v;selector[i]=v;
		}
		// code lengths per table, delta coded; then decoding tables
		int[][] limit=new int[groups][MAX_CODE_LENGTH+1],base=new int[groups][MAX_CODE_LENGTH+1],perm=new int[groups][MAX_ALPHA];int[] minLength=new int[groups];
		for(int t=0;t<groups;t++){
			int[] length=new int[alphaSize];int current=bits.read(5);
			for(int i=0;i<alphaSize;i++){
				while(true){if(current<1||current>20)throw new IOException("bad bzip2 code length");if(!bits.bit())break;current+=bits.bit()?-1:1;}
				length[i]=current;
			}
			int min=32,max=0;for(int l:length){min=Math.min(min,l);max=Math.max(max,l);}
			minLength[t]=min;
			int pp=0;for(int l=min;l<=max;l++)for(int j=0;j<alphaSize;j++)if(length[j]==l)perm[t][pp++]=j;
			int[] b=base[t],lim=limit[t];
			for(int l:length)b[l+1]++;
			for(int i=1;i<=MAX_CODE_LENGTH;i++)b[i]+=b[i-1];
			int vec=0;
			for(int i=min;i<=max;i++){vec+=b[i+1]-b[i];lim[i]=vec-1;vec<<=1;}
			for(int i=max+1;i<=MAX_CODE_LENGTH;i++)lim[i]=Integer.MAX_VALUE;
			for(int i=min+1;i<=max;i++)b[i]=((lim[i-1]+1)<<1)-b[i];
		}
		// symbols: runs of the front byte (RUNA/RUNB, bijective base 2) and move-to-front positions
		int eob=inUse+1;int[] mtf=new int[256];for(int i=0;i<256;i++)mtf[i]=i;
		int[] counts=new int[256];int n=0;
		int groupIndex=-1,groupLeft=0,table=0;
		int run=0,runWeight=1;
		while(true){
			if(groupLeft==0){groupIndex++;if(groupIndex>=selectors)throw new IOException("bzip2 selectors run out");table=selector[groupIndex];groupLeft=GROUP_SIZE;}
			groupLeft--;
			int length=minLength[table],code=bits.read(length);
			while(code>limit[table][length]){length++;if(length>20)throw new IOException("bad bzip2 code");code=(code<<1)|bits.read(1);}
			int symbol=perm[table][code-base[table][length]];
			if(symbol<=1){run+=(symbol+1)*runWeight;runWeight<<=1;if(run>tt.length)throw new IOException("bzip2 run too long");continue;}
			if(run>0){
				int value=seqToUnseq[mtf[0]];
				if(n+run>tt.length)throw new IOException("bzip2 block too long");
				counts[value]+=run;while(run-->0)tt[n++]=value;
				run=0;runWeight=1;
			}
			if(symbol==eob)break;
			int index=symbol-1,v=mtf[index];System.arraycopy(mtf,0,mtf,1,index);mtf[0]=v;
			int value=seqToUnseq[v];
			if(n>=tt.length)throw new IOException("bzip2 block too long");
			counts[value]++;tt[n++]=value;
		}
		if(origin>=n)throw new IOException("bad bzip2 origin");
		// inverse Burrows-Wheeler: link each position to the next
		int[] next=new int[256];for(int i=0,sum=0;i<256;i++){next[i]=sum;sum+=counts[i];}
		for(int i=0;i<n;i++){int value=tt[i]&0xff;tt[next[value]++]|=i<<8;}
		int position=tt[origin]>>>8;
		// final stage: four equal bytes are followed by a count of more
		int last=-1,same=0;
		for(int i=0;i<n;i++){
			int entry=tt[position];int value=entry&0xff;position=entry>>>8;
			if(same==4){for(int k=0;k<value;k++)out.write(last);same=0;last=-1;continue;}
			out.write(value);
			if(value==last)same++;else{last=value;same=1;}
		}
	}
}
