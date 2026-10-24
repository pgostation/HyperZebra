/** Cursor Manager: in-VM cursor state, mirrored to the host cursor of hosted applications and externals. */
final class CursorManager68k {
	private static final int CURSOR_SIZE=68;
	/** Debug (-Dhz68k.cursorlog=true): logs each SetCursor with the first image bytes and the hot spot, and HideCursor/ShowCursor with the level. */
	private static final boolean CURSOR_LOG=Boolean.getBoolean("hz68k.cursorlog");
	/** Pseudo trap number for the JInitCrsr vector: the second half of InitCursor (cursor shown, not obscured, image kept). */
	static final int INIT_CRSR=-1;
	/**
	 * The low-memory cursor jump vectors and the trap each one stands for: JHideCursor $800, JShowCursor $804,
	 * JShieldCursor $808, JInitCrsr $814, JSetCrsr $818, JCrsrObscure $81C. The traps jump through them with their Pascal
	 * arguments still on the stack, and some programs call them directly (Spectre calls JInitCrsr on each click outside a
	 * game; it was 0). JScrnAddr $80C / JScrnSize $810 stay no-ops.
	 */
	static final int[][] JUMP_VECTORS={{0x800,0x852},{0x804,0x853},{0x808,0x855},{0x80c,0},{0x810,0},{0x814,INIT_CRSR},{0x818,0x851},{0x81c,0x856}};
	private final M68kMemory memory;
	private Mac68kApi macApi;
	private final byte[] cursor=new byte[CURSOR_SIZE];
	private int hideLevel;
	private boolean obscured;
	private int shieldRect,shieldOffset;

	CursorManager68k(M68kMemory memory) {this.memory=memory;}
	CursorManager68k(M68kMemory memory,Mac68kApi macApi) {this.memory=memory;this.macApi=macApi;}
	private MacAppHost host(){Toolbox68k tb=macApi==null?null:macApi.application();return tb==null||!tb.hosted()?null:tb.host();}

	boolean handle(M68kCpu cpu,int trap) throws M68kCpuException {
		switch(trap) {
			case 0x850:initCursor();{MacAppHost h=host();if(h!=null){h.setCursorHidden(false);h.setCursor(java.awt.Cursor.getDefaultCursor());}}return true;
			case 0x851:return setCursor(cpu);
			case 0x852:hideLevel++;if(CURSOR_LOG)System.err.println("[68K cursor] t="+System.currentTimeMillis()%100000+" HideCursor level "+hideLevel);{MacAppHost h=host();if(h!=null)h.setCursorHidden(true);}return true;
			case 0x853:if(hideLevel>0)hideLevel--;if(CURSOR_LOG)System.err.println("[68K cursor] t="+System.currentTimeMillis()%100000+" ShowCursor level "+hideLevel);{MacAppHost h=host();if(h!=null){if(hideLevel==0)h.setCursorHidden(false);h.blitEnd();/* ShieldCursor..ShowCursor brackets a game's copy of its frame into screen memory (Dark Forces) */}}return true;
			case 0x855:{MacAppHost h=host();if(h!=null)h.blitBegin();}return shieldCursor(cpu);
			case 0x856:obscured=true;return true;
			case INIT_CRSR:hideLevel=0;obscured=false;{MacAppHost h=host();if(h!=null)h.setCursorHidden(false);}return true;
			default:return false;
		}
	}

	private void initCursor() {for(int i=0;i<cursor.length;i++)cursor[i]=0;hideLevel=0;obscured=false;shieldRect=0;shieldOffset=0;}

	private boolean setCursor(M68kCpu cpu) throws M68kCpuException {
		int source=memory.readLong(cpu.a[7]);cpu.a[7]+=4;
		// SetCursor(**GetCursor(id)) with an ID the System lacks passes a nil pointer (or whatever nil dereferenced to):
		// show the arrow rather than an invisible cursor, so the program stays usable.
		boolean valid=source!=0;
		if(valid){for(int i=0;i<cursor.length;i++)cursor[i]=(byte)memory.readByte(source+i);boolean visible=false;for(int i=0;i<64&&!visible;i++)visible=cursor[i]!=0;valid=visible;}
		if(!valid){if(Mac68kApi.TRACE)System.err.println("[68K] SetCursor $"+Integer.toHexString(source)+": "+(source==0?"nil cursor":"empty cursor")+", showing the arrow");for(int i=0;i<cursor.length;i++)cursor[i]=0;}
		if(CURSOR_LOG){StringBuilder b=new StringBuilder("[68K cursor] t="+System.currentTimeMillis()%100000+" SetCursor ");for(int i=0;i<8;i++)b.append(String.format("%02x",cursor[i]&255));b.append(" hot ").append((cursor[64]&255)<<8|cursor[65]&255).append(',').append((cursor[66]&255)<<8|cursor[67]&255);System.err.println(b);}
		obscured=false;MacAppHost h=host();if(h!=null)h.setCursor(valid?MacAppHost.cursorFromData(cursor):java.awt.Cursor.getDefaultCursor());return true;
	}

	private boolean shieldCursor(M68kCpu cpu) throws M68kCpuException {
		shieldRect=memory.readLong(cpu.a[7]);shieldOffset=cpu.a[7]+4;cpu.a[7]+=8;return true;
	}

	boolean hidden() {return hideLevel>0||obscured;}
	int hideLevel() {return hideLevel;}
	byte[] cursor() {byte[] copy=new byte[cursor.length];System.arraycopy(cursor,0,copy,0,cursor.length);return copy;}
	int shieldRect() {return shieldRect;}
	int shieldOffset() {return shieldOffset;}

	static String name(int trap) {switch(trap) {
		case 0x850:return "InitCursor";case 0x851:return "SetCursor";
		case 0x852:return "HideCursor";case 0x853:return "ShowCursor";
		case 0x855:return "ShieldCursor";case 0x856:return "ObscureCursor";
		default:return null;
	}}
}
