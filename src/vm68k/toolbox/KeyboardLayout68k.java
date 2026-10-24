import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keyboard the System reports (KbdType, Gestalt 'kbd ') and its 'KCAP' picture, which Key Caps draws: an extended
 * US keyboard (function row, main block, navigation cluster, arrows, keypad), laid out here on a 20-pixel key pitch.
 * The key codes are the Macintosh virtual key codes that the host keyboard is translated to and that the 'KCHR'
 * turns into characters.
 * <p>
 * 'KCAP' (MPW SysTypes.r): boundsRect, textRect, the number of shape arrays, then for each array the count - 1 of its
 * shape points (the bottom-right corner of a key relative to its top-left), the count - 1 of its keys, and per key a
 * modifier byte, the key code and (dv, dh) from the previous key of the array (the first from the keyboard's origin).
 */
final class KeyboardLayout68k {
	private KeyboardLayout68k(){}

	/** KbdType ($21E) and the 'KCAP' ID: the ADB Apple Extended Keyboard's handler ID. */
	static final int KEYBOARD_TYPE=2;
	/** Gestalt 'kbd ': gestaltExtADBKbd. */
	static final int GESTALT_KEYBOARD=4;

	private static final int PITCH=20,TOP=50,LEFT=10;

	/** One key: code, row/column position in pixels (from the keyboard's origin) and size in pixels. */
	private record Key(int code,int v,int h,int height,int width){}

	private static List<Key> keys(){
		List<Key> k=new ArrayList<Key>();
		// function row: Esc, F1-F4, F5-F8, F9-F12 in groups, F13-F15 over the navigation cluster
		int v=TOP;
		k.add(new Key(0x35,v,LEFT,1,1));
		int[][] functionGroups={{0x7a,0x78,0x63,0x76},{0x60,0x61,0x62,0x64},{0x65,0x6d,0x67,0x6f}};
		int h=LEFT+2*PITCH;
		for(int[] group:functionGroups){for(int code:group){k.add(new Key(code,v,h,1,1));h+=PITCH;}h+=PITCH/2;}
		int navigation=LEFT+15*PITCH+PITCH/2,keypad=navigation+3*PITCH+PITCH/2;
		for(int i=0;i<3;i++)k.add(new Key(new int[]{0x69,0x6b,0x71}[i],v,navigation+i*PITCH,1,1));
		// main block: widths in quarter keys, 15 keys wide in all
		int[][][] rows={
			{{0x32,4},{0x12,4},{0x13,4},{0x14,4},{0x15,4},{0x17,4},{0x16,4},{0x1a,4},{0x1c,4},{0x19,4},{0x1d,4},{0x1b,4},{0x18,4},{0x33,8}},
			{{0x30,6},{0x0c,4},{0x0d,4},{0x0e,4},{0x0f,4},{0x11,4},{0x10,4},{0x20,4},{0x22,4},{0x1f,4},{0x23,4},{0x21,4},{0x1e,4},{0x2a,6}},
			{{0x39,7},{0x00,4},{0x01,4},{0x02,4},{0x03,4},{0x05,4},{0x04,4},{0x26,4},{0x28,4},{0x25,4},{0x29,4},{0x27,4},{0x24,9}},
			{{0x38,9},{0x06,4},{0x07,4},{0x08,4},{0x09,4},{0x0b,4},{0x2d,4},{0x2e,4},{0x2b,4},{0x2f,4},{0x2c,4},{0x38,11}},
			{{0x3b,6},{0x3a,5},{0x37,6},{0x31,26},{0x37,6},{0x3a,5},{0x3b,6}}};
		int rowTop=TOP+PITCH+PITCH/2;
		for(int r=0;r<rows.length;r++){
			int quarter=0;
			for(int[] key:rows[r]){k.add(new Key(key[0],rowTop+r*PITCH,LEFT+quarter*PITCH/4,1,-key[1]));quarter+=key[1];}
		}
		// navigation cluster and arrows
		int[] navigationCodes={0x72,0x73,0x74,0x75,0x77,0x79};
		for(int i=0;i<6;i++)k.add(new Key(navigationCodes[i],rowTop+(i/3)*PITCH,navigation+(i%3)*PITCH,1,1));
		k.add(new Key(0x7e,rowTop+3*PITCH,navigation+PITCH,1,1));
		k.add(new Key(0x7b,rowTop+4*PITCH,navigation,1,1));k.add(new Key(0x7d,rowTop+4*PITCH,navigation+PITCH,1,1));k.add(new Key(0x7c,rowTop+4*PITCH,navigation+2*PITCH,1,1));
		// keypad: Clear = / *, 7 8 9 -, 4 5 6 +, 1 2 3 Enter (two rows), 0 (two keys wide) .
		int[][] pad={{0x47,0x51,0x4b,0x43},{0x59,0x5b,0x5c,0x4e},{0x56,0x57,0x58,0x45},{0x53,0x54,0x55}};
		for(int r=0;r<pad.length;r++)for(int c=0;c<pad[r].length;c++)k.add(new Key(pad[r][c],rowTop+r*PITCH,keypad+c*PITCH,1,1));
		k.add(new Key(0x4c,rowTop+3*PITCH,keypad+3*PITCH,2,1));
		k.add(new Key(0x52,rowTop+4*PITCH,keypad,1,2));
		k.add(new Key(0x41,rowTop+4*PITCH,keypad+2*PITCH,1,1));
		// sizes: whole keys (height, width) or, for a negative width, quarter keys of the main block
		List<Key> sized=new ArrayList<Key>();
		for(Key key:k){int width=key.width<0?-key.width*PITCH/4:key.width*PITCH;sized.add(new Key(key.code,key.v,key.h,key.height*PITCH,width));}
		return sized;
	}

	/** The 'KCAP' resource for {@link #KEYBOARD_TYPE}. */
	static byte[] kcap(){
		List<Key> all=keys();
		int right=0,bottom=0;for(Key k:all){right=Math.max(right,k.h+k.width);bottom=Math.max(bottom,k.v+k.height);}
		int width=right+1+LEFT,height=bottom+1+LEFT;
		Map<Long,List<Key>> byShape=new LinkedHashMap<Long,List<Key>>();
		for(Key k:all)byShape.computeIfAbsent((long)k.height<<32|k.width,s->new ArrayList<Key>()).add(k);
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		int top=60,left=16;
		word(out,top);word(out,left);word(out,top+height);word(out,left+width);   // boundsRect: the Key Caps window
		word(out,12);word(out,40);word(out,36);word(out,width-40);                 // textRect: the typed text above the keys
		word(out,byShape.size());
		for(List<Key> keys:byShape.values()){
			word(out,0);word(out,keys.get(0).height+1);word(out,keys.get(0).width+1); // one shape point: the key's bottom-right, borders shared with its neighbours
			word(out,keys.size()-1);
			int v=0,h=0;
			for(Key k:keys){out.write(0);out.write(k.code);word(out,k.v-v);word(out,k.h-h);v=k.v;h=k.h;}
		}
		return out.toByteArray();
	}

	private static void word(ByteArrayOutputStream out,int value){out.write(value>>8);out.write(value);}
}
