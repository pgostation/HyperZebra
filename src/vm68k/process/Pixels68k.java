/** Window and screen buffer rows as packed RGB (P4: out of MacAppHost, which the browser player has none of). */
final class Pixels68k {
	private Pixels68k() {}
	private static final int[] RGB555=new int[32768];
	static{for(int v=0;v<32768;v++)RGB555[v]=(v>>>10&31)*255/31<<16|(v>>>5&31)*255/31<<8|(v&31)*255/31;}
	/** Expands one row of a window buffer (xRGB, 16-bit or indexed through {@code clut}) to packed RGB at {@code row[offset]}. */
	static void readPixels(byte[] raw,int rowStart,int depth,int[] clut,int width,int[] row,int offset){
		if(depth==32){for(int x=0,o=rowStart,p=offset;x<width;x++,o+=4,p++)row[p]=(raw[o+1]&0xff)<<16|(raw[o+2]&0xff)<<8|(raw[o+3]&0xff);return;}
		if(depth==16){for(int x=0,o=rowStart,p=offset;x<width;x++,o+=2,p++){int v=(raw[o]&0xff)<<8|(raw[o+1]&0xff);row[p]=RGB555[v&0x7fff];}return;}
		int[] table=clut==null?new int[1<<depth]:clut;int mask=(1<<depth)-1;
		if(depth==8){for(int x=0,o=rowStart,p=offset;x<width;x++,o++,p++){int v=raw[o]&0xff;row[p]=v<table.length?table[v]:0;}return;}
		if(depth==4){int x=0,p=offset,o=rowStart;for(;x+1<width;x+=2,o++){int b=raw[o]&0xff;int hi=b>>>4,lo=b&15;row[p++]=hi<table.length?table[hi]:0;row[p++]=lo<table.length?table[lo]:0;}
			if(x<width){int v=(raw[o]&0xff)>>>4;row[p]=v<table.length?table[v]:0;}return;}
		for(int x=0;x<width;x++){int bit=x*depth,value=(raw[rowStart+(bit>>>3)]&0xff)>>>(8-depth-(bit&7))&mask;row[offset+x]=value<table.length?table[value]:0;}
	}
	static void readPixels(byte[] raw,int rowStart,int depth,int[] clut,int width,int[] row){readPixels(raw,rowStart,depth,clut,width,row,0);}
}
