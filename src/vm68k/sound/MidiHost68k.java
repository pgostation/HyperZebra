import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.SysexMessage;
import javax.sound.midi.Transmitter;

/**
 * The host side of the two serial ports a MIDI interface would hang off: what the MIDI Manager's "Apple MIDI Driver"
 * stand-in ({@link MidiManager68k}) sends to its Modem Port / Printer Port goes to a host MIDI output (the Java
 * synthesizer by default, or a CoreMIDI/ALSA/WinMM device by name), and what a host MIDI input receives comes back as
 * MIDI arriving at that port. One instance for all VMs: a device is opened once and shared, as the ports of one
 * Macintosh would be.
 * <p>
 * Preferences (PCARD.midiOutput68k/midiInput68k, "-Dhz68k.midiout=" / "-Dhz68k.midiin=" override them): the value is
 * {@link #SYNTH} (outputs: the Java synthesizer), {@link #NONE}, {@link #AUTO} (inputs: the first hardware input) or a
 * device name as {@link #outputDeviceNames()} / {@link #inputDeviceNames()} list them.
 */
final class MidiHost68k {
	static final int MODEM=0,PRINTER=1;
	static final String SYNTH="synth",NONE="none",AUTO="auto";
	/** Streams of raw MIDI arriving from the host (a complete message each: status byte first, system exclusive whole). */
	interface Listener { void received(int port,byte[] message); }

	private static final String[] outputChoice={SYNTH,SYNTH},inputChoice={AUTO,NONE};
	/** What each port is opened on now (null = nothing opened yet), to reopen only when the choice changed. */
	private static final String[] openedOutput=new String[2],openedInput=new String[2],openedInputName=new String[2];
	private static final Receiver[] receivers=new Receiver[2];
	private static final MidiDevice[] outputDevices=new MidiDevice[2],inputDevices=new MidiDevice[2];
	private static final Transmitter[] transmitters=new Transmitter[2];
	private static Synthesizer synthesizer;
	private static final List<Listener> listeners=new CopyOnWriteArrayList<Listener>();
	/** Test hook: when set, every port's output goes here instead of a device (see App68kMidiManagerTest). */
	static volatile Receiver testReceiver;

	static{
		String out=System.getProperty("hz68k.midiout"),in=System.getProperty("hz68k.midiin");
		if(out!=null)outputChoice[MODEM]=out;if(in!=null)inputChoice[MODEM]=in;
	}

	private MidiHost68k(){}

	static synchronized String outputChoice(int port){return outputChoice[port];}
	static synchronized String inputChoice(int port){return inputChoice[port];}
	/** Changes what a port is connected to; open devices follow at the next use (outputs) or at once (inputs being listened to). */
	static synchronized void setOutputChoice(int port,String choice){outputChoice[port]=choice==null?SYNTH:choice;}
	static synchronized void setInputChoice(int port,String choice){inputChoice[port]=choice==null?NONE:choice;if(!listeners.isEmpty())openInputs();}

	/** A host device and the name it is offered under (the JDK's, or CoreMIDI's when the JDK only knows "Unknown name"). */
	private static final class Entry { final MidiDevice.Info info;final String name;Entry(MidiDevice.Info info,String name){this.info=info;this.name=name;} }
	/** Names of the host's MIDI outputs (hardware and virtual ports; Java's own synthesizer and sequencer are not listed). */
	static List<String> outputDeviceNames(){List<String> names=new ArrayList<String>();for(Entry e:devices(true))names.add(e.name);return names;}
	static List<String> inputDeviceNames(){List<String> names=new ArrayList<String>();for(Entry e:devices(false))names.add(e.name);return names;}
	/**
	 * The host's MIDI devices of one direction, named: on macOS the JDK's CoreMIDI provider calls every device "Unknown
	 * name", so the names come from CoreMIDI itself (ffm/NativeCoreMidi, matched by index - both enumerate the endpoints in
	 * CoreMIDI's order) when that helper is available; a name that repeats gets a number.
	 */
	private static List<Entry> devices(boolean output){
		List<Entry> entries=new ArrayList<Entry>();
		try{
			List<MidiDevice.Info> infos=new ArrayList<MidiDevice.Info>();
			for(MidiDevice.Info info:MidiSystem.getMidiDeviceInfo()){
				MidiDevice device;
				try{device=MidiSystem.getMidiDevice(info);}catch(Exception e){continue;}
				if(device instanceof Synthesizer||device instanceof javax.sound.midi.Sequencer)continue;
				if(output?device.getMaxReceivers()==0:device.getMaxTransmitters()==0)continue;
				infos.add(info);
			}
			List<String> nativeNames=null;boolean unknown=false;
			for(MidiDevice.Info info:infos)if(info.getName().equals("Unknown name"))unknown=true;
			if(unknown)nativeNames=coreMidiNames(output);
			List<String> used=new ArrayList<String>();
			for(int i=0;i<infos.size();i++){
				String name=infos.get(i).getName();
				if(nativeNames!=null&&i<nativeNames.size()&&nativeNames.size()==infos.size()&&!nativeNames.get(i).isEmpty())name=nativeNames.get(i);
				String unique=name;for(int n=2;used.contains(unique);n++)unique=name+" ("+n+")";
				used.add(unique);entries.add(new Entry(infos.get(i),unique));
			}
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[midi] devices not listed: "+e);}
		return entries;
	}
	private static java.lang.reflect.Method coreMidiNames;private static boolean coreMidiLooked;
	@SuppressWarnings("unchecked")
	private static List<String> coreMidiNames(boolean output){
		if(!coreMidiLooked){coreMidiLooked=true;try{coreMidiNames=Class.forName("NativeCoreMidi").getMethod("names",boolean.class);}catch(Throwable t){if(Mac68kApi.TRACE)System.err.println("[midi] CoreMIDI names not available: "+t);}}
		if(coreMidiNames==null)return null;
		try{return (List<String>)coreMidiNames.invoke(null,output);}catch(Throwable t){return null;}
	}
	private static MidiDevice deviceNamed(String name,boolean output){
		for(Entry e:devices(output))if(e.name.equals(name)){try{return MidiSystem.getMidiDevice(e.info);}catch(Exception x){if(Mac68kApi.TRACE)System.err.println("[midi] device "+name+" not opened: "+x);return null;}}
		return null;
	}

	// ------------------------------------------------------------------ output

	/** Sends one complete MIDI message (status first) out of a port; dropped when the port goes nowhere. */
	static void send(int port,byte[] message){
		if(message.length==0)return;
		MidiMessage m;
		try{
			int status=message[0]&0xff;
			if(status==0xf0||status==0xf7){SysexMessage s=new SysexMessage();s.setMessage(message,message.length);m=s;}
			else{ShortMessage s=new ShortMessage();s.setMessage(status,message.length>1?message[1]&0x7f:0,message.length>2?message[2]&0x7f:0);m=s;}
		}catch(InvalidMidiDataException e){if(Mac68kApi.TRACE)System.err.println("[midi] bad message "+hex(message)+": "+e.getMessage());return;}
		Receiver test=testReceiver;
		if(test!=null){test.send(m,-1);return;}
		Receiver r=receiver(port);
		if(r!=null)try{r.send(m,-1);}catch(RuntimeException e){if(Mac68kApi.TRACE)System.err.println("[midi] send failed: "+e);}
	}
	/** A receiver of its own into the Java synthesizer (the native QuickTime Note Allocator / Tune Player play through it). */
	static synchronized Receiver synthesizerReceiver() throws Exception {return synthesizer().getReceiver();}
	private static synchronized Receiver receiver(int port){
		String choice=outputChoice[port];
		if(!choice.equals(openedOutput[port])){closeOutput(port);openOutput(port,choice);}
		return receivers[port];
	}
	private static void openOutput(int port,String choice){
		openedOutput[port]=choice;
		try{
			if(choice.equals(NONE))return;
			if(choice.equals(SYNTH)){receivers[port]=synthesizer().getReceiver();return;}
			MidiDevice device=deviceNamed(choice,true);
			if(device==null){if(Mac68kApi.TRACE)System.err.println("[midi] output "+choice+" not found: the port goes nowhere");return;}
			if(!device.isOpen())device.open();
			outputDevices[port]=device;receivers[port]=device.getReceiver();
			if(Mac68kApi.TRACE)System.err.println("[midi] "+(port==MODEM?"modem":"printer")+" port output -> "+choice);
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[midi] output "+choice+" not opened: "+e);}
	}
	private static void closeOutput(int port){
		Receiver r=receivers[port];receivers[port]=null;
		if(r!=null&&outputDevices[port]!=null)try{r.close();}catch(Exception ignored){}
		MidiDevice d=outputDevices[port];outputDevices[port]=null;
		if(d!=null&&d!=outputDevices[1-port])try{d.close();}catch(Exception ignored){}
		openedOutput[port]=null;
	}
	/**
	 * The Java synthesizer, opened with a short output buffer where the JDK lets us ask for one (Gervill's default of
	 * 120 ms is fine for a movie soundtrack, not for a keyboard played through M): its open(line, properties) is in an
	 * unexported package, reached by reflection when the manifest's Add-Exports opens it, else the default open.
	 */
	private static Synthesizer synthesizer() throws Exception {
		if(synthesizer!=null&&synthesizer.isOpen())return synthesizer;
		Synthesizer s=MidiSystem.getSynthesizer();
		boolean opened=false;
		try{
			Map<String,Object> properties=new HashMap<String,Object>();
			properties.put("latency",Long.valueOf(30000L)); // microseconds
			properties.put("jitter correction",Boolean.FALSE);
			java.lang.reflect.Method open=s.getClass().getMethod("open",javax.sound.sampled.SourceDataLine.class,Map.class);
			open.invoke(s,null,properties);opened=true;
		}catch(Throwable e){if(Mac68kApi.TRACE)System.err.println("[midi] low-latency synthesizer open not available ("+e+"); default latency");}
		if(!opened)s.open();
		synthesizer=s;return s;
	}

	// ------------------------------------------------------------------ input

	/** The first listener opens the inputs the preferences name; they close again with the last listener. */
	static synchronized void addListener(Listener l){
		if(!listeners.contains(l))listeners.add(l);
		openInputs();
	}
	static synchronized void removeListener(Listener l){
		listeners.remove(l);
		if(listeners.isEmpty())for(int port=0;port<2;port++)closeInput(port);
	}
	private static void openInputs(){
		for(int port=0;port<2;port++){
			String choice=inputChoice[port];
			if(choice.equals(openedInput[port]))continue;
			closeInput(port);openInput(port,choice);
		}
	}
	private static void openInput(final int port,String choice){
		openedInput[port]=choice;
		try{
			if(choice.equals(NONE))return;
			MidiDevice device=null;String name=choice;
			if(choice.equals(AUTO)){for(String n:inputDeviceNames()){if(port==PRINTER&&inputDevices[MODEM]!=null&&n.equals(openedInputName[MODEM]))continue;device=deviceNamed(n,false);if(device!=null){name=n;break;}}}
			else device=deviceNamed(choice,false);
			if(device==null){if(Mac68kApi.TRACE)System.err.println("[midi] no input for the "+(port==MODEM?"modem":"printer")+" port ("+choice+")");return;}
			if(!device.isOpen())device.open();
			Transmitter t=device.getTransmitter();
			t.setReceiver(new Receiver(){
				@Override public void send(MidiMessage message,long timeStamp){byte[] bytes=message.getMessage();if(bytes.length==0)return;for(Listener l:listeners)l.received(port,bytes);}
				@Override public void close(){}
			});
			inputDevices[port]=device;transmitters[port]=t;openedInputName[port]=name;
			if(Mac68kApi.TRACE)System.err.println("[midi] "+(port==MODEM?"modem":"printer")+" port input <- "+name);
		}catch(Exception e){if(Mac68kApi.TRACE)System.err.println("[midi] input "+choice+" not opened: "+e);}
	}
	private static void closeInput(int port){
		Transmitter t=transmitters[port];transmitters[port]=null;
		if(t!=null)try{t.close();}catch(Exception ignored){}
		MidiDevice d=inputDevices[port];inputDevices[port]=null;
		if(d!=null&&d!=inputDevices[1-port])try{d.close();}catch(Exception ignored){}
		openedInput[port]=null;openedInputName[port]=null;
	}
	/** Feeds a message in as if the port's input had received it (tests, and anything else on the host that wants to play). */
	static void inject(int port,byte[] message){for(Listener l:listeners)l.received(port,message);}

	static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02X ",x&0xff));return s.toString().trim();}
}
