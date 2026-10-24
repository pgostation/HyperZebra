import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * HyperTalk speak / the voices / the speech without any speech library: the text goes to the speech engine
 * the operating system already has (macOS say, Windows SAPI through PowerShell, Linux spd-say or espeak-ng).
 * Nothing found = silent, as before. The text and the voice are written to the program's standard input in UTF-8
 * (never on the command line), so quotes, dashes and Japanese text pass through unchanged.
 */
final class Speech {
	private Speech(){}

	private enum Engine { NONE, SAY, SAPI, SPD, ESPEAK }

	private static final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Speech"); t.setDaemon(true); return t;
	});
	private static volatile Process current;
	private static volatile int pending;
	/** Bumped by {@link #stopAll}: texts queued before it are skipped. */
	private static volatile int generation;
	private static Engine engine;
	private static List<String> voiceCache;

	private static synchronized Engine engine(){
		if(engine==null){
			String os = System.getProperty("os.name","").toLowerCase(Locale.ROOT);
			if(os.contains("mac")) engine = onPath("say") ? Engine.SAY : Engine.NONE;
			else if(os.contains("win")) engine = Engine.SAPI;
			else if(onPath("spd-say")) engine = Engine.SPD;
			else if(onPath("espeak-ng") || onPath("espeak")) engine = Engine.ESPEAK;
			else engine = Engine.NONE;
		}
		return engine;
	}

	private static boolean onPath(String program){
		String path = System.getenv("PATH");
		if(path==null) return false;
		for(String dir : path.split(File.pathSeparator)) if(new File(dir, program).canExecute()) return true;
		return false;
	}

	private static String espeak(){ return onPath("espeak-ng") ? "espeak-ng" : "espeak"; }

	/** Program name shown in the sandbox confirmation, or null when there is no engine. */
	static String programName(){
		switch(engine()){
			case SAY: return "say";
			case SAPI: return "powershell";
			case SPD: return "spd-say";
			case ESPEAK: return espeak();
			default: return null;
		}
	}

	/** speak: queued behind what is being said, returns at once. An empty text stops the speech. */
	static void speak(String text, String voice){
		if(engine()==Engine.NONE) return;
		if(text.trim().isEmpty()){ stop(); return; }
		final String t = text, v = voice==null ? "" : voice.trim();
		synchronized(Speech.class){ pending++; }
		final int queuedIn = generation;
		queue.execute(() -> {
			try{ if(queuedIn==generation) run(t, v); }
			catch(Exception ignored){ /* no engine at run time: silent */ }
			finally{ synchronized(Speech.class){ pending--; } }
		});
	}

	static void stop(){
		Process p = current;
		if(p!=null) p.destroy();
	}

	/** Stops what is being said and drops what is queued behind it (the stack closed). */
	static void stopAll(){
		generation++;
		stop();
	}

	/** the speech: "speaking" while something is being said or queued, "done" otherwise. */
	static String speechState(){
		return pending>0 ? "speaking" : "done";
	}

	private static void run(String text, String voice) throws Exception {
		List<String> cmd = new ArrayList<String>();
		String input = text;
		switch(engine()){
			case SAY:
				cmd.add("say"); if(!voice.isEmpty()){ cmd.add("-v"); cmd.add(voice); }
				break;
			case SAPI:
				cmd.add("powershell"); cmd.add("-NoProfile"); cmd.add("-NonInteractive"); cmd.add("-Command");
				cmd.add("[Console]::InputEncoding=[Text.Encoding]::UTF8;"
					+ "Add-Type -AssemblyName System.Speech;"
					+ "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
					+ "$v=[Console]::In.ReadLine();"
					+ "if($v){try{$s.SelectVoice($v)}catch{}};"
					+ "$s.Speak([Console]::In.ReadToEnd())");
				input = voice + "\n" + text;
				break;
			case SPD:
				cmd.add("spd-say"); cmd.add("-w"); if(!voice.isEmpty()){ cmd.add("-y"); cmd.add(voice); }
				cmd.add("--"); cmd.add(text); input = null;
				break;
			default:
				cmd.add(espeak()); if(!voice.isEmpty()){ cmd.add("-v"); cmd.add(voice); }
				break;
		}
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process p = pb.start();
		current = p;
		try(OutputStream os = p.getOutputStream()){
			if(input!=null) os.write(input.getBytes(StandardCharsets.UTF_8));
		}catch(IOException ignored){ /* the engine exited or was stopped */ }
		p.waitFor();
		if(current==p) current = null;
	}

	/** speak … with male|female|neuter|robotic voice */
	static boolean isGenderWord(String w){
		return w.equalsIgnoreCase("male") || w.equalsIgnoreCase("female") || w.equalsIgnoreCase("neuter") || w.equalsIgnoreCase("robotic");
	}

	/** The first installed voice of that kind (the classic MacinTalk names first), or "" for the engine's default. */
	static String voiceForGender(String gender){
		String[] names;
		switch(gender.toLowerCase(Locale.ROOT)){
			case "male": names = new String[]{"Fred", "Ralph", "Albert", "Daniel", "Alex"}; break;
			case "female": names = new String[]{"Victoria", "Kathy", "Samantha", "Karen", "Zira"}; break;
			case "robotic": names = new String[]{"Zarvox", "Trinoids"}; break;
			default: names = new String[]{"Whisper"}; break;
		}
		List<String> installed = Arrays.asList(voices().split("\n"));
		for(String n : names) for(String v : installed) if(v.equalsIgnoreCase(n) || v.toLowerCase(Locale.ROOT).startsWith(n.toLowerCase(Locale.ROOT)+" ")) return v;
		return "";
	}

	/** the voices: one name per line (HyperCard returns them return-separated), empty when there is no engine. */
	static synchronized String voices(){
		if(voiceCache==null){
			List<String> v = new ArrayList<String>();
			try{
				switch(engine()){
					case SAY:
						for(String line : output("say", "-v", "?")){
							int i = line.indexOf("  ");
							if(i>0) v.add(line.substring(0, i).trim());
						}
						break;
					case SAPI:
						v.addAll(output("powershell", "-NoProfile", "-NonInteractive", "-Command",
							"[Console]::OutputEncoding=[Text.Encoding]::UTF8;Add-Type -AssemblyName System.Speech;"
							+ "(New-Object System.Speech.Synthesis.SpeechSynthesizer).GetInstalledVoices()"
							+ "|ForEach-Object{$_.VoiceInfo.Name}"));
						break;
					case SPD: {
						List<String> lines = output("spd-say", "-L");
						for(int i=1; i<lines.size(); i++){
							String[] f = lines.get(i).trim().split("\\s+");
							if(f.length>0 && !f[0].isEmpty()) v.add(f[0]);
						}
						break;
					}
					case ESPEAK: {
						List<String> lines = output(espeak(), "--voices");
						for(int i=1; i<lines.size(); i++){
							String[] f = lines.get(i).trim().split("\\s+");
							if(f.length>3) v.add(f[3]);
						}
						break;
					}
					default: break;
				}
			}catch(Exception ignored){ /* no list: no voices */ }
			voiceCache = v;
		}
		return String.join("\n", voiceCache);
	}

	private static List<String> output(String... cmd) throws Exception {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		p.getOutputStream().close();
		List<String> lines = new ArrayList<String>();
		try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))){
			String s;
			while((s = r.readLine())!=null) if(!s.trim().isEmpty()) lines.add(s.replaceAll("\\s+$", ""));
		}
		if(!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
		return lines;
	}
}
