/** A strike's location and offset/width tables from its FontInfo (P4: out of StackStrikeFont, for the 68K VM's 'NFNT'). */
final class StrikeWidths {
	private StrikeWidths() {}
	/** The strike's location table, an entry per code, the missing glyph's and the closing one (the image's width when not imported). */
	static int[] locs(Rsrc.FontInfo info, int imageWidth){
		int n = info.lastChar-info.firstChar+3;
		int[] locs = new int[n];
		for(int i=0; i<n; i++) locs[i] = info.locs!=null && i<info.locs.length ? info.locs[i] : imageWidth;
		return locs;
	}
	/**
	 * The strike's offset/width table as words ($FFFF = no glyph), an entry per code, the missing glyph's and the closing
	 * one. Imports before Oct 23 2026 never read it ({@link #legacyWidths}): then each image is its own advance at the
	 * pen, a code without image has no glyph, except the space, a third of the widest glyph.
	 */
	static int[] owWords(Rsrc.FontInfo info, int imageWidth){
		int n = info.lastChar-info.firstChar+3;
		int[] ow = new int[n];
		boolean legacy = legacyWidths(info);
		int[] locs = legacy ? locs(info, imageWidth) : null;
		for(int i=0; i<n; i++){
			if(i==n-1){ ow[i] = 0xFFFF; continue; }
			if(legacy){
				int w = Math.max(0, locs[i+1]-locs[i]), code = info.firstChar+i;
				if(w==0 && i<n-2 && (code==' ' || code==0xCA)) w = Math.max(1, info.widMax/3);
				ow[i] = w==0 ? 0xFFFF : ((-(short)info.kernMax)&0xFF)<<8 | Math.min(255, w);
			}
			else{
				int off = info.offsets!=null && i<info.offsets.length ? info.offsets[i]&0xFF : 0xFF;
				int wid = info.widthes!=null && i<info.widthes.length ? info.widthes[i]&0xFF : 0xFF;
				ow[i] = off<<8 | wid;
			}
		}
		return ow;
	}
	/** Whether the FontInfo's offsets/widths are the location table's high/low bytes (HCResource before Oct 23 2026). */
	static boolean legacyWidths(Rsrc.FontInfo info){
		int n = info.lastChar-info.firstChar+2;
		if(info.offsets==null || info.widthes==null) return true;
		if(info.locs==null || info.locs.length!=n) return false;
		for(int i=0; i<n && i<info.offsets.length && i<info.widthes.length; i++){
			if((byte)info.offsets[i]!=(byte)(info.locs[i]>>8) || (byte)info.widthes[i]!=(byte)info.locs[i]) return false;
		}
		return true;
	}
}
