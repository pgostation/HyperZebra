import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The two VIAs and the SCC of a Macintosh II, for programs that program the chips themselves (VBase $1D4, SCCRd $1D8,
 * SCCWr $1DC). 1989-era MIDI sequencers (Opcode Vision 1.x) do: they close the serial driver, run the SCC of the modem or
 * printer port at the MIDI interface's 1 MHz clock and send each byte from their own transmit interrupt (Lvl2DT), and
 * clock the music from a VIA timer interrupt (VIA2 Timer 2 through Via2DT on a Mac II, VIA1 Timer 1 through Lvl1DT on a
 * compact Mac).
 *
 * <p>Layout as on the Mac II: VIA1 at the base, VIA2 $2000 above it (registers $200 apart, the Mac II decodes only the
 * register bits, so VIA2 is also VIA1 + $2000 for programs that address it that way), the SCC at base + $4000 for both reads
 * and writes (channel B control +0, A control +2, B data +4, A data +6).
 *
 * <p>The timers count down at the VIA clock (783.36 kHz) by the host clock; flags are kept as the 6522 keeps them. Interrupts
 * are delivered at the Toolbox's polls ({@link #service}, from runTimers), only through a vector the program installed
 * (the ROM tables are empty here) and only while the interrupt mask allows the level (VIA1 1, VIA2 2, SCC 4). A VIA handler
 * gets A1 = its VIA, an SCC handler A0/A1 = the channel's control register, as the ROM dispatchers hand them.
 *
 * <p>SCC data written to channel A (modem port) or B (printer port) is MIDI: the bytes are assembled into messages
 * (running status, System Exclusive, real-time bytes in between) and sent to MidiHost68k's port; a byte takes 320 us
 * (31250 baud) before the transmit buffer is empty again. Host MIDI input arrives as received bytes.
 *
 * <p>The compact Mac's sound buffer (SoundBase) is in the same region, at base + {@link #SOUND_OFFSET}: writes to it are
 * played ({@link SoundBuffer68k}), switched by VIA1 port B bit 7 and with the volume of port A bits 0-2.
 */
final class MacIo68k implements M68kMemory.IoDevice, M68kCpu.InterruptSource {
	static final int VIA2_OFFSET=0x2000,SCC_OFFSET=0x4000,SOUND_OFFSET=0x4008,LENGTH=SOUND_OFFSET+SoundBuffer68k.BYTES;
	/** VIA input clock: the 68000's 7.8336 MHz / 10. */
	private static final double VIA_HZ=783360;
	/** One MIDI byte at 31250 baud (10 bits). */
	private static final long BYTE_NANOS=320_000;
	/** Level-1 (VIA1) dispatch table, Mac II level-2 (VIA2) dispatch table, SCC dispatch table. */
	private static final int LVL1DT=0x192,VIA2DT=0xd70,LVL2DT=0x1b2;
	static final boolean TRACE=Boolean.getBoolean("hz68k.iotrace");

	private final Toolbox68k tb;
	private final M68kMemory memory;
	final int base;
	private final Via via1=new Via("VIA1"),via2=new Via("VIA2");
	private final Scc scc=new Scc();

	final SoundBuffer68k sound;
	/** VIA1 port B and port A (register 1 or 15) as last written: sound off (bit 7) and volume (bits 0-2). */
	private int portB,portA;

	MacIo68k(Toolbox68k tb,M68kMemory memory,int base){
		this.tb=tb;this.memory=memory;this.base=base;
		sound=new SoundBuffer68k(memory,base+SOUND_OFFSET,SoundMixer68k.get());
		// as the ROM leaves them: sound off, the main sound and screen buffers selected, the volume from parameter RAM (SPVolCtl)
		int volume;try{volume=memory.readByte(0x208)&7;}catch(M68kCpuException e){volume=3;}
		setPorts(0x80,0x48|volume);
		memory.raw()[base]=(byte)portB;memory.raw()[base+0x200]=memory.raw()[base+0x1e00]=(byte)portA;
	}
	private void setPorts(int b,int a){portB=b;portA=a;sound.setPorts(b,a);}

	@Override public int read(int offset){
		if(offset>=SOUND_OFFSET)return memory.raw()[base+offset]&0xff;
		if(offset>=SCC_OFFSET)return scc.read(offset-SCC_OFFSET);
		Via v=offset>=VIA2_OFFSET?via2:via1;return v.read(offset>>9&15,memory.raw()[base+offset]&0xff);
	}
	@Override public void write(int offset,int value){
		if(offset>=SOUND_OFFSET){sound.written(offset-SOUND_OFFSET);return;}
		if(offset>=SCC_OFFSET){scc.write(offset-SCC_OFFSET,value);return;}
		Via v=offset>=VIA2_OFFSET?via2:via1;int reg=offset>>9&15;v.write(reg,value);
		if(v==via1){if(reg==0)setPorts(value,portA);else if(reg==1||reg==15)setPorts(portB,value);}
	}
	/** Address of the sound buffer (SoundBase). */
	int soundBase(){return base+SOUND_OFFSET;}

	/** When the next timer expiry or transmitted byte is due, for idle waits; Long.MAX_VALUE for none. */
	long nextDueNanos(){
		long due=Math.min(via1.nextDue(),via2.nextDue());
		if(scc.a.txBusyUntil!=0)due=Math.min(due,scc.a.txBusyUntil);
		if(scc.b.txBusyUntil!=0)due=Math.min(due,scc.b.txBusyUntil);
		if(!scc.a.rx.isEmpty()||!scc.b.rx.isEmpty())due=Math.min(due,System.nanoTime());
		return due;
	}

	/** Whether the program has enabled any interrupt of these chips (only then is a lowered mask worth a look). */
	boolean armed(){return via1.ier!=0||via2.ier!=0||(scc.wr9&0x08)!=0;}
	/** The program opened its interrupt mask: anything pending is taken now. */
	@Override public void maskLowered(M68kCpu cpu) throws M68kException {service(cpu);}
	/** Handlers running (nested ones included): a handler may lower its mask (Vision's VIA2 handler drops to level 1 while it
	 *  plays the due events, so the SCC and the next timer tick can come in), and the processor's mask alone decides, as on the
	 *  real machine. The depth only guards against runaway recursion. */
	private int depth;
	/** Delivers the pending interrupts whose handlers the program installed: called at every Toolbox poll and whenever the
	 *  program lowers its interrupt mask. */
	void service(M68kCpu cpu) throws M68kException {
		if(depth>=8)return;depth++;
		try{
			long now=System.nanoTime();
			scc.update(now);
			// highest level first; each source at most once per call (a handler that leaves its flag set comes back later)
			for(int i=0;i<6&&(cpu.sr>>8&7)<4;i++){int src=scc.pendingSource();if(src<0)break;if(!dispatchScc(cpu,src))break;}
			if((cpu.sr>>8&7)<2)dispatchVia(cpu,via2,VIA2DT,now);
			if((cpu.sr>>8&7)<1)dispatchVia(cpu,via1,LVL1DT,System.nanoTime());
		}finally{depth--;}
	}
	private void dispatchVia(M68kCpu cpu,Via v,int table,long now) throws M68kException {
		int pending=v.pending(now);if(pending==0)return;
		for(int bit=6;bit>=0;bit--){
			if((pending&1<<bit)==0)continue;
			int handler=memory.readLong(table+4*bit);
			if(handler==0||handler>=memory.romBase()){continue;}
			if(TRACE)System.err.println("[io] "+v.name+" interrupt bit "+bit+" -> $"+Integer.toHexString(handler)+" T2="+(short)v.t2Counter(now)+" late="+(now-v.t2Start)/1000+"us");
			call(cpu,handler,v==via1?base:base+VIA2_OFFSET,v==via1?1:2,true);
			return;
		}
	}
	/** SCC sources in Lvl2DT order: 0 B transmit, 1 B external, 2 B receive, 4 A transmit, 5 A external, 6 A receive. */
	private boolean dispatchScc(M68kCpu cpu,int source) throws M68kException {
		int handler=memory.readLong(LVL2DT+4*source);
		if(handler==0||handler>=memory.romBase()){scc.clearSource(source);return true;} // nobody listens: as if the ROM's default reset it
		if(TRACE)System.err.println("[io] SCC interrupt "+source+" -> $"+Integer.toHexString(handler));
		int control=base+SCC_OFFSET+(source>=4?2:0);
		int before=scc.pendingSource();
		call(cpu,handler,control,4,false); // A0 = control read, A1 = control write (one address on the Mac II)
		return scc.pendingSource()!=before; // the handler did not take it: try again at the next poll
	}
	private void call(M68kCpu cpu,int handler,int a1,int level,boolean setA0) throws M68kException {
		int sr=cpu.sr;int[] d=cpu.d.clone(),a=cpu.a.clone(); // the interrupted code's registers, before anything is set up
		try{cpu.a[0]=setA0?handler:a1;cpu.a[1]=a1;cpu.sr=(sr&~0x0700)|level<<8|0x2000;tb.callRoutine(handler);}
		finally{System.arraycopy(d,0,cpu.d,0,8);System.arraycopy(a,0,cpu.a,0,8);cpu.sr=sr;}
	}

	void shutDown(){sound.stop();scc.a.stop();scc.b.stop();if(listening){MidiHost68k.removeListener(hostListener);listening=false;}}

	// ------------------------------------------------------------------ 6522 VIA

	private final class Via {
		final String name;
		int ier,ifr,acr;
		int t1Latch=0xffff,t2LatchLow=0xff;
		long t1Start,t2Start;   // nanoTime the counter was loaded (0 = not running)
		int t1Value,t2Value;    // value loaded
		long t1Periods;         // free-running periods already flagged
		boolean t2Flagged;
		Via(String name){
			this.name=name;
			// The system leaves live counters behind even when no application has programmed a timer.
			// MacBench's clock calibration polls T1's low-byte sign transitions before its first write.
			// A zero, stopped counter makes that polling loop run forever. Keep interrupts disabled,
			// but count down and wrap just as a one-shot 6522 timer does after its first underflow.
			t1Value=t2Value=0xffff;
			t1Start=t2Start=System.nanoTime();
		}

		private long ticks(long start,long now){return (long)((now-start)*(VIA_HZ/1e9));}
		/** Brings the timer flags up to date. */
		void update(long now){
			if(t1Start!=0){long t=ticks(t1Start,now);
				if((acr&0x40)!=0){long period=t1Latch+2L;long periods=t<=t1Value?0:1+(t-t1Value-1)/period;if(periods>t1Periods){t1Periods=periods;ifr|=0x40;}}
				else if(t1Periods==0&&t>t1Value){t1Periods=1;ifr|=0x40;}}
			if(t2Start!=0&&!t2Flagged&&(acr&0x20)==0&&ticks(t2Start,now)>t2Value){t2Flagged=true;ifr|=0x20;}
		}
		int t1Counter(long now){if(t1Start==0)return t1Value;long t=ticks(t1Start,now);
			if(t<=t1Value)return (int)(t1Value-t);
			if((acr&0x40)!=0){long period=t1Latch+2L;return (int)(t1Latch-((t-t1Value-1)%period))&0xffff;}
			return (int)(t1Value-t)&0xffff;}
		int t2Counter(long now){if(t2Start==0||(acr&0x20)!=0)return t2Value;return (int)(t2Value-ticks(t2Start,now))&0xffff;}
		int pending(long now){update(now);return ifr&ier&0x7f;}
		long nextDue(){
			long due=Long.MAX_VALUE;
			if(t1Start!=0&&(ier&0x40)!=0){long first=t1Start+(long)((t1Value+1)/VIA_HZ*1e9);
				if((acr&0x40)!=0){long periodNs=(long)((t1Latch+2)/VIA_HZ*1e9);due=first+t1Periods*Math.max(periodNs,1);}else if(t1Periods==0)due=first;}
			if(t2Start!=0&&(ier&0x20)!=0&&!t2Flagged&&(acr&0x20)==0)due=Math.min(due,t2Start+(long)((t2Value+1)/VIA_HZ*1e9));
			return due;
		}
		int read(int reg,int stored){
			long now=System.nanoTime();
			switch(reg){
			case 4:update(now);ifr&=~0x40;return t1Counter(now)&0xff;
			case 5:return t1Counter(now)>>8&0xff;
			case 6:return t1Latch&0xff;
			case 7:return t1Latch>>8&0xff;
			case 8:update(now);ifr&=~0x20;return t2Counter(now)&0xff;
			case 9:return t2Counter(now)>>8&0xff;
			case 11:return acr;
			case 13:update(now);return ifr|((ifr&ier&0x7f)!=0?0x80:0);
			case 14:return ier|0x80;
			default:return stored;
			}
		}
		void write(int reg,int value){
			long now=System.nanoTime();
			switch(reg){
			case 4:case 6:t1Latch=t1Latch&0xff00|value;break;
			case 5:t1Latch=value<<8|t1Latch&0xff;t1Value=t1Latch;t1Start=now;t1Periods=0;ifr&=~0x40;wake();break;
			case 7:t1Latch=value<<8|t1Latch&0xff;ifr&=~0x40;break;
			case 8:t2LatchLow=value;break;
			case 9:update(now);t2Value=value<<8|t2LatchLow;t2Start=now;t2Flagged=false;ifr&=~0x20;wake();break;
			case 11:update(now);acr=value;break;
			case 13:ifr&=~(value&0x7f);break;
			case 14:if((value&0x80)!=0)ier|=value&0x7f;else ier&=~value;wake();
				if(TRACE)System.err.printf("[io] %s IER $%02X%n",name,ier);break;
			default:break;
			}
		}
	}
	private void wake(){tb.wakeIdle();}

	// ------------------------------------------------------------------ Z8530 SCC

	private final class Scc {
		final Channel a=new Channel(MidiHost68k.MODEM,"A"),b=new Channel(MidiHost68k.PRINTER,"B");
		int wr9;
		int read(int offset){
			Channel c=(offset&2)!=0?a:b;
			if((offset&4)!=0)return c.readData();
			int reg=c.pointer;c.pointer=0;
			switch(reg){
			case 0:return c.rr0();
			case 1:return 0x01;                                     // all sent
			case 2:return c==a?c.wr[2]:modifiedVector();
			case 3:return c==a?ip():0;
			case 12:return c.wr[12];case 13:return c.wr[13];
			case 15:return c.wr[15];
			default:return 0;
			}
		}
		void write(int offset,int value){
			Channel c=(offset&2)!=0?a:b;
			if((offset&4)!=0){c.writeData(value);return;}
			int reg=c.pointer;c.pointer=0;
			if(reg==0){
				c.pointer=value&7;
				switch(value>>3&7){
				case 1:c.pointer|=8;break;                               // point high
				case 2:c.extIP=false;break;                              // reset external/status interrupts
				case 5:c.txIP=false;break;                               // reset Tx interrupt pending
				default:break;                                           // error reset, reset highest IUS, enable int on next Rx
				}
				return;
			}
			if(reg==8){c.writeData(value);return;}
			if(reg==9){wr9=value;if((value&0xc0)==0xc0){a.reset();b.reset();}else if((value&0x80)!=0)a.reset();else if((value&0x40)!=0)b.reset();return;}
			if(reg==2){a.wr[2]=b.wr[2]=value;return;}
			c.wr[reg]=value;
			if(reg==1&&(value&0x18)!=0)listen();
			if(TRACE)System.err.printf("[io] SCC %s WR%d $%02X%n",c.name,reg,value);
		}
		/** RR3 of channel A: B ext 0, B tx 1, B rx 2, A ext 3, A tx 4, A rx 5. */
		int ip(){return (b.extIP?1:0)|(b.txIP?2:0)|(b.rxIP()?4:0)|(a.extIP?8:0)|(a.txIP?0x10:0)|(a.rxIP()?0x20:0);}
		int pendingSource(){
			if((wr9&0x08)==0)return -1; // master interrupt enable
			if(a.rxIP())return 6;if(a.txIP)return 4;if(a.extIP)return 5;
			if(b.rxIP())return 2;if(b.txIP)return 0;if(b.extIP)return 1;
			return -1;
		}
		void clearSource(int s){Channel c=s>=4?a:b;switch(s&3){case 0:c.txIP=false;break;case 1:c.extIP=false;break;default:c.rx.clear();}}
		int modifiedVector(){
			int v=b.wr[2];if((wr9&0x01)==0)return v; // VIS off: the vector as written
			int s=pendingSource();int status=s<0?3:s; // no interrupt: "B special" code 3 per the 8530
			return (wr9&0x10)!=0?(v&0x8f)|(reverse3(status)<<4):(v&0xf1)|(status<<1);
		}
		private int reverse3(int x){return (x&1)<<2|(x&2)|(x&4)>>2;}
		void update(long now){a.update(now);b.update(now);}
	}

	private final class Channel {
		final int port;final String name;
		final int[] wr=new int[16];int pointer;
		boolean txIP,extIP;long txBusyUntil;
		final ConcurrentLinkedQueue<Integer> rx=new ConcurrentLinkedQueue<Integer>();
		private final byte[] message=new byte[3];private int status,have,need;
		private java.io.ByteArrayOutputStream sysex;
		Channel(int port,String name){this.port=port;this.name=name;}
		void reset(){java.util.Arrays.fill(wr,0);pointer=0;txIP=extIP=false;}
		boolean rxIP(){int mode=wr[1]>>3&3;return mode!=0&&!rx.isEmpty();}
		int rr0(){update(System.nanoTime());return (rx.isEmpty()?0:0x01)|(txBusyUntil==0?0x04:0)|0x20|0x08;} // CTS/DCD high: the interface's clock is there
		int readData(){Integer v=rx.poll();return v==null?0:v;}
		void update(long now){if(txBusyUntil!=0&&now>=txBusyUntil){txBusyUntil=0;if((wr[1]&0x02)!=0)txIP=true;}}
		void writeData(int value){
			txIP=false;long now=System.nanoTime();
			txBusyUntil=Math.max(now,txBusyUntil)+BYTE_NANOS;
			wake();
			midiByte(value&0xff);
		}
		/** MIDI parsing of the outgoing byte stream: complete messages go to the host port. */
		private void midiByte(int x){
			if(x>=0xf8){send(new byte[]{(byte)x});return;}               // real-time: anywhere, even inside SysEx
			if(sysex!=null){
				if(x==0xf7){sysex.write(x);send(sysex.toByteArray());sysex=null;return;}
				if(x<0x80){sysex.write(x);return;}
				sysex.write(0xf7);send(sysex.toByteArray());sysex=null;       // a status ends it
			}
			if(x==0xf0){sysex=new java.io.ByteArrayOutputStream();sysex.write(x);status=0;return;}
			if(x>=0x80){
				status=x;have=0;
				need=x>=0xf0?(x==0xf1||x==0xf3?1:x==0xf2?2:0):(x&0xf0)==0xc0||(x&0xf0)==0xd0?1:2;
				if(need==0){send(new byte[]{(byte)x});if(x>=0xf0)status=0;}
				return;
			}
			if(status==0)return;                                              // data without a status
			message[++have]=(byte)x;
			if(have==need){byte[] m=new byte[need+1];m[0]=(byte)status;System.arraycopy(message,1,m,1,need);send(m);have=0;if(status>=0xf0)status=0;}
		}
		private boolean used;
		private void send(byte[] m){used=true;if(TRACE)System.err.println("[io] MIDI out "+name+": "+MidiHost68k.hex(m));MidiHost68k.send(port,m);}
		void stop(){ // notes left sounding when the program quits mid-song
			if(used)for(int ch=0;ch<16;ch++)MidiHost68k.send(port,new byte[]{(byte)(0xb0|ch),123,0});
		}
	}

	private boolean listening;
	private final MidiHost68k.Listener hostListener=(port,bytes)->{
		Channel c=port==MidiHost68k.MODEM?scc.a:scc.b;
		for(byte x:bytes)c.rx.add(x&0xff);
		wake();
	};
	private void listen(){if(!listening){listening=true;MidiHost68k.addListener(hostListener);}}
}
