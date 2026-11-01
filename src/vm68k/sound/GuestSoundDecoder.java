import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A compressed sound format that no native decoder here knows (QDesign Music 'QDM2', Qualcomm PureVoice 'Qclp' ...), expanded
 * by the installed system software's own sound decompressor component ('sdec' of that format - QuickTime 6's are PowerPC
 * code), the way Sound Manager 3 expands what a channel is given: the decompressor's source is a component implemented here
 * that hands it the compressed samples (SoundComponentData), and SoundComponentGetSourceData on the decompressor pulls the
 * expanded ones. One decoder per channel and format; its state runs on from buffer to buffer (QuickTime's continuation
 * buffers), as the Sound Manager keeps a channel's decompressor.
 */
final class GuestSoundDecoder {
	static final int SDEC=0x73646563,CMFA=0x636d6661,TWOS=0x74776f73,SOWT=0x736f7774,RAW=0x72617720;
	// SoundComponents.h selectors
	private static final int SET_SOURCE=2,GET_SOURCE_DATA=4,SET_OUTPUT=5,GET_INFO=0x103,SET_INFO=0x104,PLAY_SOURCE_BUFFER=0x108,WAVE=0x77617665;
	static final int DCFG=0x64636667;
	private static final int SCD_SIZE=28; // SoundComponentData: flags.l format.l numChannels.w sampleSize.w sampleRate.l sampleCount.l buffer.l reserved.l
	/** ExtendedSoundComponentData: the SoundComponentData (flags kExtendedSoundData), then recordSize, extendedFlags, bufferSize,
	 *  frameCount, frameSizesArray, commonFrameSize, extensionsPtr, extensionsSize, bufferFlags, bufferFlagsMask. */
	private static final int EXTENDED_SIZE=SCD_SIZE+40,EXTENDED_SOUND_DATA=1<<14,BUFFER_SIZE_VALID=2,EXTENSIONS_VALID=16;
	static final boolean TRACE=Boolean.getBoolean("hz68k.sndtrace");

	/** The source component of a VM's Component Manager: GetSourceData answers the record of the instance's decoder. */
	private static final class Source implements Components68k.Native {
		final M68kMemory memory;final Map<Integer,Integer> records=new HashMap<Integer,Integer>();Components68k.Component component;
		Source(M68kMemory memory){this.memory=memory;}
		@Override public int call(int instance,int what,int params) throws M68kException {
			Integer record=records.get(instance);int out=memory.readLong(params); // params[0]: the last argument
			switch(what){
				case GET_SOURCE_DATA:case SET_OUTPUT:if(out!=0)memory.writeLong(out,record==null?0:record);return 0; // the data are what we deliver
				case GET_INFO:return 0x80008002; // badComponentSelector: the decompressor knows its format
				default:return 0;
			}
		}
	}
	private static final Map<Components68k,Source> SOURCES=new WeakHashMap<Components68k,Source>();
	private static synchronized Source source(Toolbox68k tb){
		Source s=SOURCES.get(tb.components);
		if(s==null){s=new Source(tb.memory);s.component=tb.components.registerNative(0x73696674,0x687a736d,0x687a6562,0,s);SOURCES.put(tb.components,s);} // 'sift' 'hzsm' 'hzeb'
		return s;
	}

	/**
	 * What SndSetInfo 'dcfg' tells a channel about the compressed sound QuickTime 6's sound media handler will send: an
	 * ExtendedSoundComponentData (format, channels, sample size, rate; kExtendedSoundExtensionsValid with the sound description's
	 * extension atoms - QDesign Music's 'QDCA'/'QDCP' in its 'wave' atom). Copied, atoms included: they are QuickTime's memory.
	 */
	static final class Configuration {
		final byte[] record,extensions,compressionInfo;
		private Configuration(byte[] record,byte[] extensions,byte[] compressionInfo){this.record=record;this.extensions=extensions;this.compressionInfo=compressionInfo;}
		int format(){return u4(record,4);}
		int channels(){return Math.max(1,(short)u2(record,8));}
		int sampleSize(){return (short)u2(record,10);}
		int rate(){return u4(record,12);}
		static Configuration read(M68kMemory memory,int p,byte[] compressionInfo) throws M68kCpuException {
			byte[] r=memory.readBytes(p,EXTENDED_SIZE);byte[] ext=null;
			if((u4(r,0)&EXTENDED_SOUND_DATA)!=0&&(u4(r,SCD_SIZE+4)&EXTENSIONS_VALID)!=0){int at=u4(r,SCD_SIZE+24),size=u4(r,SCD_SIZE+28);if(at!=0&&size>0&&size<1<<20)ext=memory.readBytes(at,size);}
			if(ext==null&&(u4(r,0)&EXTENDED_SOUND_DATA)==0)java.util.Arrays.fill(r,SCD_SIZE,EXTENDED_SIZE,(byte)0); // a plain SoundComponentData
			return new Configuration(r,ext,compressionInfo);
		}
	}
	private static int u2(byte[] b,int o){return (b[o]&0xff)<<8|b[o+1]&0xff;}
	private static int u4(byte[] b,int o){return (b[o]&0xff)<<24|(b[o+1]&0xff)<<16|(b[o+2]&0xff)<<8|b[o+3]&0xff;}

	final int format,channels;
	/** What the decompressor delivers (from its last output record). */
	int outputChannels;private int outputRate;
	private final Toolbox68k tb;private final M68kMemory memory;private final Source source;private final Configuration configuration;
	private final int decoder,sourceInstance,record,requested,pointer,extensions;
	private int samplesPerPacket=1;

	private GuestSoundDecoder(Toolbox68k tb,Source source,int decoder,int sourceInstance,int format,int channels,Configuration configuration) throws M68kException {
		this.tb=tb;memory=tb.memory;this.source=source;this.decoder=decoder;this.sourceInstance=sourceInstance;this.format=format;this.channels=channels;this.configuration=configuration;
		outputChannels=channels;
		record=memory.allocate(EXTENDED_SIZE,4);requested=memory.allocate(SCD_SIZE,4);pointer=memory.allocate(20,4);
		extensions=configuration!=null&&configuration.extensions!=null?memory.allocate(configuration.extensions.length,4):0;
		if(extensions!=0)memory.load(extensions,configuration.extensions);
		memory.fill(record,EXTENDED_SIZE,0);source.records.put(sourceInstance,record);
	}

	/**
	 * Opens the 'sdec' component of {@code format} with a source of ours; null when none is registered or it refuses.
	 * {@code configuration} (may be null): the channel's 'dcfg', which takes the place of the header's format details.
	 */
	static GuestSoundDecoder open(Toolbox68k tb,int format,int channels,int sampleSize,int rate,Configuration configuration) throws M68kException {
		if(configuration!=null&&configuration.format()!=format)configuration=null; // a description of another sound
		Components68k.Component c=tb.components.find(SDEC,format);if(c==null)return null;
		int decoder=tb.components.open(c);if(decoder==0)return null;
		Source source=source(tb);int sourceInstance=tb.components.open(source.component);
		GuestSoundDecoder g=new GuestSoundDecoder(tb,source,decoder,sourceInstance,format,configuration!=null?configuration.channels():channels,configuration);
		int sourceRate=configuration!=null&&configuration.rate()!=0?configuration.rate():rate;
		g.fillRecord(sampleSize,rate,0,0,0);
		int err=tb.components.call(decoder,SET_SOURCE,new int[]{0,4},new int[]{sourceInstance,4});
		if(err==0&&configuration!=null&&configuration.compressionInfo!=null){g.memory.load(g.pointer,configuration.compressionInfo);
			int r=tb.components.call(decoder,SET_INFO,new int[]{0,4},new int[]{CMFA,4},new int[]{g.pointer,4});
			if(TRACE)System.err.println("[snd] sdec/"+ResourceFile68k.typeName(format)+" SetInfo 'cmfa' "+java.util.HexFormat.of().formatHex(configuration.compressionInfo)+" -> "+r);}
		// siDecompressionParams: the decompressor's own configuration atoms (the contents of the description's 'wave' atom)
		if(err==0&&g.extensions!=0){int params=g.extensions,size=configuration.extensions.length;
			if(size>=8&&u4(configuration.extensions,4)==WAVE){params+=8;}
			int r=tb.components.call(decoder,SET_INFO,new int[]{0,4},new int[]{WAVE,4},new int[]{params,4});
			if(TRACE)System.err.println("[snd] sdec/"+ResourceFile68k.typeName(format)+" SetInfo 'wave' -> "+r);}
		// CompressionInfo of the format: samplesPerPacket (a compressed header's numFrames counts packets)
		g.memory.fill(g.pointer,20,0);
		if(tb.components.call(decoder,GET_INFO,new int[]{0,4},new int[]{CMFA,4},new int[]{g.pointer,4})==0){int n=g.memory.readWord(g.pointer+10);if(n>0)g.samplesPerPacket=n;}
		g.writeRecord(g.requested,TWOS,16,sourceRate,0,0);
		if(err==0)err=tb.components.call(decoder,SET_OUTPUT,new int[]{g.requested,4},new int[]{g.pointer,4});
		if(TRACE)System.err.println("[snd] sdec/"+ResourceFile68k.typeName(format)+" opened: SetSource/SetOutput "+err+", "+g.samplesPerPacket+" samples a packet"+(configuration!=null?", configured ("+(configuration.extensions==null?0:configuration.extensions.length)+" bytes of atoms)":""));
		// PlaySourceBuffer starts the sound as the Sound Manager does at a buffer's start: QDesign Music's decompressor sets its
		// decoder up there (from the 'wave' parameters) and answered silence without it
		if(err==0){int pb=g.memory.allocate(64,4);
			try{g.memory.fill(pb,64,0);g.memory.writeLong(pb,64);g.memory.load(pb+4,g.memory.readBytes(g.record,SCD_SIZE));g.memory.writeLong(pb+32,0x10000); // recordSize, desc, rateMultiplier 1.0
				err=tb.components.call(decoder,PLAY_SOURCE_BUFFER,new int[]{0,4},new int[]{pb,4},new int[]{0,4});}
			finally{g.memory.free(pb);}
			if(TRACE)System.err.println("[snd] sdec/"+ResourceFile68k.typeName(format)+" PlaySourceBuffer -> "+err);}
		if(err!=0){g.close();return null;}
		return g;
	}
	private void writeRecord(int scd,int fmt,int sampleSize,int rate,int count,int buffer) throws M68kCpuException {
		memory.writeLong(scd,0);memory.writeLong(scd+4,fmt);memory.writeWord(scd+8,channels);memory.writeWord(scd+10,sampleSize);
		memory.writeLong(scd+12,rate);memory.writeLong(scd+16,count);memory.writeLong(scd+20,buffer);memory.writeLong(scd+24,0);
	}
	/** The source record: the channel's configuration (extensions pointing at our copy of its atoms) or the header's details,
	 *  with this buffer's count, address and byte count. */
	private void fillRecord(int sampleSize,int rate,int count,int buffer,int bufferSize) throws M68kCpuException {
		memory.fill(record,EXTENDED_SIZE,0);
		int flags=0,extendedFlags=0;
		if(configuration!=null){memory.load(record,configuration.record);flags=memory.readLong(record);extendedFlags=memory.readLong(record+SCD_SIZE+4);}
		else writeRecord(record,format,sampleSize,rate,0,0);
		memory.writeLong(record+16,count);memory.writeLong(record+20,buffer);
		if(extensions!=0){memory.writeLong(record+SCD_SIZE+24,extensions);memory.writeLong(record+SCD_SIZE+28,configuration.extensions.length);extendedFlags|=EXTENSIONS_VALID;}
		else extendedFlags&=~EXTENSIONS_VALID;
		if(bufferSize>0){memory.writeLong(record+SCD_SIZE+8,bufferSize);extendedFlags|=BUFFER_SIZE_VALID;}
		if(extendedFlags!=0){flags|=EXTENDED_SOUND_DATA;memory.writeLong(record+SCD_SIZE,EXTENDED_SIZE);}
		memory.writeLong(record,flags);memory.writeLong(record+SCD_SIZE+4,extendedFlags);
	}
	/** The rate of what the decompressor delivers, else {@code fallback}. */
	double outputRate(double fallback){return outputRate!=0?Integer.toUnsignedLong(outputRate)/65536.0:fallback;}

	/** Expands {@code packets} packets at {@code data} (a compressed sound header's samples) of {@code bufferSize} bytes (0: not
	 *  known); 16-bit big-endian PCM, interleaved. */
	byte[] decode(int data,int packets,int sampleSize,int rate,int bufferSize) throws M68kException {
		fillRecord(sampleSize,rate,packets*samplesPerPacket,data,bufferSize);
		ByteArrayOutputStream out=new ByteArrayOutputStream();
		for(int i=0;i<100000;i++){
			memory.writeLong(pointer,0);
			int err=tb.components.call(decoder,GET_SOURCE_DATA,new int[]{pointer,4});
			int scd=memory.readLong(pointer);
			if(err!=0||scd==0)break;
			int n=memory.readLong(scd+16),ch=Math.max(1,(short)memory.readWord(scd+8)),bits=(short)memory.readWord(scd+10),fmt=memory.readLong(scd+4),buffer=memory.readLong(scd+20);
			if(n<=0||buffer==0)break;
			outputChannels=ch;outputRate=memory.readLong(scd+12);
			byte[] b=memory.readBytes(buffer,n*ch*(bits/8));
			if(bits==8){for(byte x:b){int v=fmt==TWOS?x:(x&0xff)-128;out.write(v);out.write(0);}} // 'raw ' is offset binary, 'twos' signed
			else if(bits==16&&fmt==SOWT){for(int k=0;k+1<b.length;k+=2){out.write(b[k+1]);out.write(b[k]);}}
			else if(bits==16)out.write(b,0,b.length);
			else break;
		}
		if(TRACE)System.err.println("[snd] sdec/"+ResourceFile68k.typeName(format)+": "+packets+" packets ("+bufferSize+" bytes) -> "+out.size()/(2*outputChannels)+" frames");
		return out.toByteArray();
	}

	void close(){
		try{tb.components.close(decoder);tb.components.close(sourceInstance);}catch(M68kException ignored){}
		source.records.remove(sourceInstance);
		try{memory.free(record);memory.free(requested);memory.free(pointer);if(extensions!=0)memory.free(extensions);}catch(RuntimeException ignored){}
	}
}
