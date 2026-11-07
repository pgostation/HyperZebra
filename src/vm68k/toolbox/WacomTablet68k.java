/**
 * The classic Wacom tablet driver ".Wacom" for programs in the VM, fed from the host's pen tablet ({@link TabletInput}).
 *
 * <p>Tablet-aware programs of the System 7 era (Fractal Design Painter 1.0/2.0) open ".Wacom" and call Status csCode 20,
 * which returns in csParam a pointer to the driver's tablet record; they check its signature 'TBLT' and then read the
 * record directly - Painter from a VBL task every tick. The layout, as Painter 1.0/2.0/4.0 read it (measured from their code):
 * <pre>
 *  +36 long   'TBLT'
 *  +40 byte   the device in proximity: bits 5..4 = 11 for the pen's eraser end (Painter 4 then swaps to its eraser)
 *  +41 byte   capability flags: bit 1 = tilt in +80/+82 (bit 5 = given as azimuth/altitude instead of X/Y; not used here)
 *  +42 word   highest pressure value
 *  +52 byte   state: bits 7..6 = 10 while the pen is in proximity (anything else = use the mouse)
 *  +53 byte   lowest pressure value that counts as pressure (signed)
 *  +62 word   pressure (0 .. +42)
 *  +64 long   time of the sample (Ticks)
 *  +68 long   pen X, +72 long pen Y, in tablet units
 *  +80 word   tilt X, +82 word tilt Y: signed degrees (Painter reads the low byte; X positive to the right, Y toward the user)
 *  +84..+90   tablet area (x min, y min, x max, y max), words
 *  +92..+98   the screen area it maps onto (x min, y min, x max, y max), global coordinates
 * </pre>
 * The pen position is the program's pointer (the host maps the pen to the pointer already), so the tablet area is the
 * screen. The record is refreshed at each tick ({@link #refresh}), as the real driver updates it from its interrupt. When
 * the pen comes near or the other end is turned to the tablet, the driver posts an osEvt whose message is mouseMovedMessage
 * ($FA in the high byte) with the bits $0C: Painter 4 looks at +40 only then.
 */
final class WacomTablet68k {
	static final String NAME=".Wacom";
	private static final int RECORD_SIZE=128,SIGNATURE=0x54424C54; // 'TBLT'
	private static final int MAX_PRESSURE=255,CAN_TILT=0x02,DEVICE_PEN=0x10,DEVICE_ERASER=0x30,MAX_TILT=100;
	/** osEvt message: mouseMovedMessage with the tablet bits (what Painter 4 checks before it reads the device at +40) */
	static final int PROXIMITY_MESSAGE=0xFA00000C;
	private static final int STATUS_RECORD=20,CONTROL_ERR=-17,STATUS_ERR=-18;
	private final Toolbox68k tb;
	private M68kMemory memory;
	private int record;

	/** Made with the Toolbox (a field initializer): its memory is read at the first call. */
	WacomTablet68k(Toolbox68k tb){this.tb=tb;}

	/** Whether ".Wacom" opens: the host has a pen tablet reader (macOS/Windows with Java 22+), -Dhz68k.tablet=false turns it off. */
	static boolean available(){
		if("false".equals(System.getProperty("hz68k.tablet")))return false;
		TabletInput.start();
		return TabletInput.available;
	}

	/** Control/Status on the driver (trap $A004/$A005); returns the result code. */
	int call(int pb,int trap) throws M68kCpuException {
		memory=tb.memory;
		int csCode=(short)memory.readWord(pb+26);
		if(trap==0x05&&csCode==STATUS_RECORD){memory.writeLong(pb+28,record());return 0;}
		if(Mac68kApi.TRACE)System.err.println("[68K] .Wacom "+(trap==0x04?"Control":"Status")+" csCode "+csCode+" (not supported)");
		return trap==0x04?CONTROL_ERR:trap==0x05?STATUS_ERR:0;
	}

	/** The tablet record, made on first use. */
	private int record() throws M68kCpuException {
		if(record!=0)return record;
		int r=memory.allocate(RECORD_SIZE,4);
		for(int i=0;i<RECORD_SIZE;i+=4)memory.writeLong(r+i,0);
		memory.writeLong(r+36,SIGNATURE);
		memory.writeByte(r+41,CAN_TILT);
		memory.writeWord(r+42,MAX_PRESSURE);
		memory.writeByte(r+53,0);
		record=r;
		writeAreas();
		refresh(tb.tickCount());
		return r;
	}

	/** Tablet area = screen area: the main screen's gdRect (MainDevice $8A4), global coordinates. */
	private void writeAreas() throws M68kCpuException {
		int device=memory.readLong(0x8a4),gd=device==0?0:memory.readLong(device);
		int top=0,left=0,bottom=480,right=640;
		if(gd!=0){top=(short)memory.readWord(gd+34);left=(short)memory.readWord(gd+36);bottom=(short)memory.readWord(gd+38);right=(short)memory.readWord(gd+40);}
		memory.writeWord(record+84,left);memory.writeWord(record+86,top);memory.writeWord(record+88,right);memory.writeWord(record+90,bottom);
		memory.writeWord(record+92,left);memory.writeWord(record+94,top);memory.writeWord(record+96,right);memory.writeWord(record+98,bottom);
	}

	/** At each new tick: the pen's state and end, pressure, tilt and place (the pointer, Mouse $830) and the time. */
	void refresh(int tick){
		if(record==0)return;
		try{
			boolean near=TabletInput.penNear,eraser=near&&TabletInput.penNearEraser;
			int device=near?(eraser?DEVICE_ERASER:DEVICE_PEN):0;
			memory.writeByte(record+40,device);
			if(device!=lastDevice){
				// the pen came near, left, or turned round: tell the program (it reads +40 then); not for leaving
				if(device!=0&&tb.events!=null)tb.events.postOsEvent(PROXIMITY_MESSAGE);
				lastDevice=device;
			}
			float[] tilt=near?TabletInput.latestTilt():null;
			if(tilt!=null)lastTilt=tilt;
			if(!near)lastTilt=null;
			memory.writeWord(record+80,lastTilt==null?0:clampTilt(lastTilt[0]));
			memory.writeWord(record+82,lastTilt==null?0:clampTilt(lastTilt[1]));
			float p=near?TabletInput.latest():0f;
			if(Float.isNaN(p))p=lastPressure;else lastPressure=p;
			int mouse=memory.readLong(0x830),v=mouse>>16,h=(short)mouse;
			memory.writeByte(record+52,near?0x80:0);
			memory.writeWord(record+62,near?Math.round(Math.max(0f,Math.min(1f,p))*MAX_PRESSURE):0);
			memory.writeLong(record+64,tick);
			memory.writeLong(record+68,h);memory.writeLong(record+72,v);
		}catch(M68kCpuException ignored){}
	}
	private float lastPressure;
	private int lastDevice;
	private float[] lastTilt;
	private static int clampTilt(float degrees){return Math.max(-MAX_TILT,Math.min(MAX_TILT,Math.round(degrees)));}
}
