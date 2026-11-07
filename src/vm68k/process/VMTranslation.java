import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Translates what 68K and PowerPC programs show (menus, dialogs, alerts, window titles, controls and the text they draw)
 * with the translators macOS has on the Mac itself: the Translation framework (the Translate app's models) and the
 * Apple Intelligence model (Foundation Models). Nothing is sent over the network. Text the user can edit is never
 * translated (VMTranslationHooks decides what is editable).
 *
 * The translators are Swift-only, so they run in the helper program hz-translate (native/translate/HZTranslate.swift,
 * built by build.sh): one JSON request per line on its standard input, one reply per line on its standard output.
 * Lookups never wait: a text not translated yet is shown as it is, queued, translated in a batch, and the windows that
 * showed it are redrawn when the translation arrives. Translations are kept per language pair under
 * HyperZebra HD/../Translations so the next start shows them at once.
 *
 * Usable on macOS 26 and later only; elsewhere {@link #unavailableReason()} says why and the settings are disabled.
 */
final class VMTranslation {
	private VMTranslation(){}

	/** What a text is: menus and window/dialog texts are short and have their own standard terms; TEXT is anything drawn. */
	enum Kind { MENU, UI, TEXT;
		String wire(){return this==MENU?"menu":this==UI?"ui":"text";}
	}

	/** Engines: the Translation framework for everything; Apple Intelligence for short UI strings only; or for everything. */
	static final String ENGINE_TRANSLATION="translation", ENGINE_MIXED="mixed", ENGINE_LLM="llm";
	static final String[] ENGINES={ENGINE_TRANSLATION,ENGINE_MIXED,ENGINE_LLM};
	/** The languages offered in the settings: those of the Translation framework (macOS 26). */
	static final String[] TARGETS={"ja","en","zh-Hans","zh-Hant","ko","fr","de","es","it","pt","nl","ru","pl","sv","da","nb","fi","tr","uk","ar","he","hi","th","vi","id","ms"};
	/** The source language is found from the program's own texts. */
	static final String AUTO="auto";

	/** The settings in effect for one program: the program's own (LauncherStore.AppSettings) over the global ones (PCARD). */
	static final class Config {
		final boolean on; final String from, to, engine;
		Config(boolean on,String from,String to,String engine){this.on=on;this.from=from;this.to=to;this.engine=engine;}
	}

	/**
	 * Translating what a running program shows, as it draws it: off - programs are translated only through their translated
	 * resources (ResourceTranslation, made by "Resource Translation…"). -Dhz68k.translate.live=true turns it on (tests, checks).
	 */
	static boolean live(){return Boolean.getBoolean("hz68k.translate.live");}

	static Config configFor(File program){
		Config c=settingsFor(program);
		return new Config(c.on&&unavailableReason()==null,c.from,c.to,c.engine);
	}
	/** The same whether or not this computer can translate (a program's translated resources need no translator to run). */
	static Config settingsFor(File program){
		LauncherStore.AppSettings s=program==null||!LauncherStore.enabled?null:LauncherStore.get().settingsFor(program);
		boolean on=s!=null&&s.translate!=null?s.translate:PCARD.translate68k;
		String to=s!=null&&s.translateTo!=null?s.translateTo:PCARD.translateTo68k;
		String from=s!=null&&s.translateFrom!=null?s.translateFrom:AUTO;
		String engine=s!=null&&s.translateEngine!=null?s.translateEngine:PCARD.translateEngine68k;
		String forced=System.getProperty("hz68k.translate"); // -Dhz68k.translate=ja (on, to Japanese) or =off
		if(forced!=null){if(forced.equals("off"))on=false;else{on=true;to=forced;}}
		String forcedEngine=System.getProperty("hz68k.translateengine"); // -Dhz68k.translateengine=translation|mixed|llm
		if(forcedEngine!=null&&Arrays.asList(ENGINES).contains(forcedEngine))engine=forcedEngine;
		return new Config(on,from,to,engine);
	}

	/** The language the user reads: HyperZebra's own language when it is one the translators know, else English. */
	static String defaultTarget(){
		String sys=Locale.getDefault().getLanguage();
		return sys==null||sys.isEmpty()?"en":sys.equals("zh")?("TW".equals(Locale.getDefault().getCountry())||"HK".equals(Locale.getDefault().getCountry())?"zh-Hant":"zh-Hans"):sys;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Availability

	private static String unavailable;
	private static boolean unavailableChecked;

	/** Why translation cannot be used on this computer (shown in the settings, which are then disabled), or null. */
	static synchronized String unavailableReason(){
		if(unavailableChecked)return unavailable;
		unavailableChecked=true;
		String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
		if(System.getProperty("hz.translate.helper")!=null)unavailable=helper()==null?"The translation helper (hz-translate) is missing: build it with build.sh on a Mac with Xcode or the Command Line Tools.":null; // a given helper (tests): any system
		else if(!os.contains("mac"))unavailable="Translation needs macOS 26 or later (it uses the translator built into macOS).";
		else if(macMajor()<26)unavailable="Translation needs macOS 26 or later.";
		else if(helper()==null)unavailable="The translation helper (hz-translate) is missing: build it with build.sh on a Mac with Xcode or the Command Line Tools.";
		return unavailable;
	}

	/** Whether this OS can have macOS's translators at all (macOS, or a given helper): elsewhere the settings and items that
	 *  need them are not shown, instead of saying they are not available. */
	static boolean osHasTranslators(){
		String p=System.getProperty("hz.translate.helper");
		if(p!=null&&!"none".equals(p))return true;
		return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("mac");
	}

	static int macMajor(){
		String v=System.getProperty("os.version","0");
		try{return Integer.parseInt(v.split("\\.")[0]);}catch(NumberFormatException e){return 0;}
	}

	/** The helper program: -Dhz.translate.helper, beside the jar (build/, a packaged app) or the working directory. */
	static File helper(){
		List<File> candidates=new ArrayList<File>();
		String p=System.getProperty("hz.translate.helper");
		if("none".equals(p))return null; // as on a computer without the translators (Resource Translation with AI Chat…, tests)
		if(p!=null)candidates.add(new File(p));
		try{
			File jar=new File(VMTranslation.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			File dir=jar.isFile()?jar.getParentFile():jar;
			candidates.add(new File(dir,"hz-translate"));
			if(dir.getParentFile()!=null)candidates.add(new File(dir.getParentFile(),"hz-translate"));
		}catch(Exception ignored){}
		candidates.add(new File("build/hz-translate"));
		candidates.add(new File("hz-translate"));
		for(File f:candidates)if(f.isFile()&&f.canExecute())return f;
		return null;
	}

	/** One language pair's state: "installed", "supported" (not downloaded), "unsupported"; and the Apple Intelligence model's. */
	static final class Status {
		final String translation, llm;
		/** The fast (low-latency) model: "installed", "supported" (can be downloaded) or "unsupported" (macOS before 26.4,
		 *  where every translation is that model's). Without it macOS translates with its high-fidelity model, about 4 texts a second. */
		final String fast;
		Status(String t,String l){this(t,l,"unsupported");}
		Status(String t,String l,String f){translation=t;llm=l;fast=f;}
		boolean translationReady(){return "installed".equals(translation);}
		/** The fast model can be downloaded and is not yet: translating a program ahead takes many times longer. */
		boolean fastMissing(){return "supported".equals(fast);}
		boolean llmReady(){return "available".equals(llm);}
	}

	private static volatile Boolean llmUsable;private static volatile long llmCheckedAt;
	/**
	 * Whether the Apple Intelligence model answers on this Mac (asked again every few minutes: it can be turned on or
	 * finish downloading meanwhile); false while the first answer is coming.
	 */
	static boolean llmUsable(){
		long now=System.currentTimeMillis();
		if(now-llmCheckedAt>300_000){
			llmCheckedAt=now;
			Thread t=new Thread(()->{Status st=status("en","ja");llmUsable=st!=null&&st.llmReady();},"Translation status");
			t.setDaemon(true);t.start();
		}
		Boolean b=llmUsable;return b!=null&&b;
	}

	/** Asks the helper (waits up to a few seconds; null when it does not answer). */
	static Status status(String from,String to){
		Map<?,?> r=helperClient().call(req("status").put("from",from).put("to",to),8000);
		if(r==null)return null;
		return new Status(String.valueOf(r.get("translation")),String.valueOf(r.get("llm")),r.get("fast")==null?"unsupported":String.valueOf(r.get("fast")));
	}

	/** The languages the Translation framework knows (codes such as "en", "ja", "zh-Hans"), or a short default list. */
	static List<String> languages(){
		List<String> out=new ArrayList<String>();
		if(unavailableReason()==null){
			Map<?,?> r=helperClient().call(req("languages"),8000);
			if(r!=null&&r.get("languages") instanceof List)for(Object o:(List<?>)r.get("languages"))out.add(String.valueOf(o));
		}
		if(out.isEmpty())out.addAll(Arrays.asList("en","ja","fr","de","es","it","ko","zh-Hans","zh-Hant"));
		Collections.sort(out,Comparator.comparing(VMTranslation::languageName));
		return out;
	}

	/** Shows macOS's download sheet for the languages of a pair (the helper puts up a small window for it). */
	static void prepare(String from,String to){
		helperClient().send(req("prepare").put("from",from).put("to",to),null);
	}

	/** A language code's name in HyperZebra's language. */
	static String languageName(String code){
		Locale ui=Locale.forLanguageTag(International.language());
		if(code.startsWith("zh-"))return International.t(code.endsWith("Hant")?"Chinese (Traditional)":"Chinese (Simplified)");
		String n=Locale.forLanguageTag(code).getDisplayLanguage(ui);
		return n==null||n.isEmpty()?code:n;
	}

	/** User-facing text of an engine choice. */
	static String engineLabel(String engine){
		switch(engine){
			case ENGINE_MIXED:return GPreferencesDialog.dialogText("Natural menu words (partly Apple Intelligence)");
			case ENGINE_LLM:return GPreferencesDialog.dialogText("Most natural, slow (all Apple Intelligence)");
			default:return GPreferencesDialog.dialogText("Recommended: fast (translation model)");
		}
	}
	/** An engine in a few words (translating ahead says which one is at work). */
	static String engineShortName(String engine){
		switch(engine){
			case ENGINE_MIXED:return GPreferencesDialog.dialogText("partly Apple Intelligence");
			case ENGINE_LLM:return "Apple Intelligence";
			default:return GPreferencesDialog.dialogText("translation model");
		}
	}
	/** When to choose an engine, in plain words (shown under the choice). */
	static String engineHelp(String engine){
		switch(engine){
			case ENGINE_MIXED:return GPreferencesDialog.dialogText("When menu and button words come out odd. Needs Apple Intelligence.");
			case ENGINE_LLM:return GPreferencesDialog.dialogText("Most natural, but slow; not for games. Needs Apple Intelligence.");
			default:return GPreferencesDialog.dialogText("Start with this. Fast, on any Mac.");
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The translator of one running program

	/** Called when translations arrive (on the helper's reader thread): the program redraws what showed the originals. */
	interface Redraw { void translationsArrived(); }

	static final class Translator {
		final Config config;
		private volatile String from;
		private final Redraw redraw;
		private final Set<String> pending=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
		private final List<String>[] queued,prefetched;
		private boolean flushScheduled;
		/** Set when the program finished or another translator took over: what was not sent yet is dropped. */
		volatile boolean closed;

		@SuppressWarnings("unchecked")
		Translator(Config config,String detectedFrom,Redraw redraw){
			this.config=config;this.redraw=redraw;
			this.from=AUTO.equals(config.from)?detectedFrom:config.from;
			queued=new List[Kind.values().length];prefetched=new List[Kind.values().length];
			for(int i=0;i<queued.length;i++){queued[i]=new ArrayList<String>();prefetched[i]=new ArrayList<String>();}
		}

		/**
		 * The program's own texts (resource strings by kind, string lists in their order) translated in the background after
		 * everything shown, so a text is ready before it is first drawn - a program that draws its text once into an offscreen
		 * world shows it translated from the start. Lines the author wrapped by hand (a string list's next entry continues
		 * after a full-width space, as Japanese games lay out their story) are translated as one sentence and the translation
		 * is shared out over the lines: "...良い子で聞い" / "　ていられるかしら？" alone came out as "...listen." / "Can I have it?".
		 */
		void prefetchProgram(List<ProgramText> texts){prefetchProgram(texts,6000);}
		/** The same with a limit on the texts looked at; returns how many were sent to be translated (not cached yet). */
		int prefetchProgram(List<ProgramText> texts,int limit){
			if(!active())return 0;
			int n=0,sent=0;
			for(ProgramText group:texts){
				List<String> lines=group.lines;
				boolean join=group.kind==Kind.TEXT&&group.joinWrapped&&cjk(from);
				double widest=join?widest(lines):0;
				for(int i=0;i<lines.size();i++){
					int j=join?runEnd(lines,i,widest):i+1;
					if(j-i>1){
						List<String> parts=new ArrayList<String>();StringBuilder joined=new StringBuilder();
						for(int k=i;k<j;k++){Piece p=Piece.of(lines.get(k));if(p==null){parts=null;break;}parts.add(p.core);joined.append(p.core);}
						if(parts!=null){joins.put(joined.toString(),parts);for(String part:parts)joinedIn.put(part,joined.toString());if(prefetchOne(group.kind,joined.toString()))sent++;n++;i=j-1;continue;}
					}
					if(prefetchOne(group.kind,lines.get(i)))sent++;n++;
					if(n>limit)return sent;
				}
			}
			return sent;
		}
		/** Joined lines (as sent) -> the lines' own texts, to share the translation out (prefetchProgram). */
		private final Map<String,List<String>> joins=new ConcurrentHashMap<String,List<String>>();
		/** A hand-wrapped line -> the joined text it is translated in. */
		private final Map<String,String> joinedIn=new ConcurrentHashMap<String,String>();
		private boolean prefetchOne(Kind kind,String text){
			Piece p=Piece.of(text);
			if(p==null||p.core.length()>1000||translatedAlready(p)||Glossary.lookup(from,config.to,p.core,kind)!=null)return false;
			if(offline&&collecting==null)return false;
			String engine=engineFor(kind);
			if(!again&&cache(from,config.to,engine).containsKey(kindKey(kind,p.core)))return false;
			// the Translation framework translates a text the same as a menu item, a dialog's or a string: once for all of them
			// (lookups take another kind's translation); Apple Intelligence is told the kind, so its texts go per kind
			if(engine.equals(ENGINE_TRANSLATION))for(Kind other:Kind.values())if(other!=kind&&engineFor(other).equals(ENGINE_TRANSLATION)
				&&(pending.contains(other.ordinal()+"\u0000"+p.core)||!again&&cache(from,config.to,ENGINE_TRANSLATION).containsKey(kindKey(other,p.core))))return false;
			// texts that differ only in their numbers ("16 Colors", "256 Colors"): one goes, the others take its translation
			// with their own numbers - unless the numbers did not come through as they were (then they go too, arrived)
			String template=numberTemplate(p.core);
			if(template!=null){
				String tk=kind.ordinal()+"\u0000"+template;
				if(!again&&cache(from,config.to,engine).containsKey(kindKey(kind,template)))return false;
				List<String> siblings=templateSiblings.get(tk);
				if(siblings!=null){synchronized(siblings){siblings.add(text);}return false;}
				templateSiblings.put(tk,new ArrayList<String>());
			}
			String key=kind.ordinal()+"\u0000"+p.core;
			if(!pending.add(key))return false;
			List<Object[]> c=collecting;
			if(c!=null){synchronized(c){c.add(new Object[]{kind,p.core});}return true;} // to be translated elsewhere (accept)
			synchronized(this){prefetched[kind.ordinal()].add(p.core);}
			schedule();
			return true;
		}
		/**
		 * Set before prefetchProgram: the texts to translate are put here ({kind, text}) instead of being sent to the
		 * translators - an AI chat translates them (LlmTranslation) and {@link #accept} keeps what it gave back.
		 */
		volatile List<Object[]> collecting;
		/** Texts translated already go again too (translated anew in an AI chat): what comes back replaces them. */
		volatile boolean again;
		/** Nothing is sent: this computer has no translator (a stack shows what is in the cache, from an AI chat). */
		volatile boolean offline;
		/** Whether a text is lines joined into one sentence (prefetchProgram): its translation is shared out over them. */
		boolean joined(String text){return joins.containsKey(text);}
		/**
		 * Keeps translations made elsewhere (an AI chat) of texts collected by prefetchProgram ({@link #collecting}), as if
		 * the translators had given them back: joined lines are shared out, texts with other numbers take them.
		 */
		void accept(Kind kind,List<String> texts,List<String> translations){
			String f=from,to=config.to,engine=engineFor(kind);
			if(again&&engine.equals(ENGINE_TRANSLATION))for(String t:texts)for(Kind other:Kind.values()) // the old translation another kind of the same text took
				if(other!=kind&&engineFor(other).equals(ENGINE_TRANSLATION))cache(f,to,ENGINE_TRANSLATION).remove(kindKey(other,t));
			Map<String,Object> reply=new HashMap<String,Object>();
			reply.put("texts",translations);
			arrived(kind,texts,reply,f,to,engine);
		}
		private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> resend=new java.util.concurrent.ConcurrentLinkedQueue<Object[]>();
		/** A held-back text sent by itself (its template's translation lost its numbers). */
		private void prefetchOneAlone(Kind kind,String text){
			Piece p=Piece.of(text);if(p==null)return;
			if(!pending.add(kind.ordinal()+"\u0000"+p.core))return;
			synchronized(this){prefetched[kind.ordinal()].add(p.core);}
			schedule();
		}
		/** Texts held back because a text with the same number template was sent (kind + template -> texts), see prefetchOne. */
		private final Map<String,List<String>> templateSiblings=new ConcurrentHashMap<String,List<String>>();
		/** Texts sent or waiting to be sent that have not come back yet (translating ahead: the progress). */
		int waiting(){return pending.size();}
		/** Texts the translators gave nothing back for. */
		final java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger();
		static boolean cjk(String language){return language!=null&&(language.startsWith("ja")||language.startsWith("zh")||language.startsWith("ko"));}
		/** The next line goes on with this one's sentence: it starts with a full-width space and this one has not ended. */
		static boolean continues(String line,String next){return continues(line,next,0);}
		/**
		 * The same in a list whose longest line is {@code widest} columns: a line that fills the width the author wrapped at
		 * and has not ended goes on in the next one unless that starts a paragraph or a speech (a full-width space, "「",
		 * a speaker's number before "「") - a story laid out as running paragraphs ("...そして北部地" / "方は...").
		 */
		static boolean continues(String line,String next,double widest){
			String t=line.strip();
			if(t.isEmpty()||next.isEmpty()||"。」』！？!?）)▼".indexOf(t.charAt(t.length()-1))>=0)return false;
			if(next.charAt(0)=='\u3000')return true;
			return widest>=40&&columns(line)>=widest-4&&!next.matches("[「『（(].*|[0-9０-９]{1,3}[「『].*")&&!next.isBlank();
		}
		/** The end (exclusive) of the hand-wrapped lines that start at {@code i}: i+1 when the line stands alone. */
		static int runEnd(List<String> lines,int i,double widest){
			int j=i+1;
			while(j<lines.size()&&continues(lines.get(j-1),lines.get(j),widest))j++;
			return j;
		}
		/** The longest line of a list in columns (a speaker's number before a line is not drawn: the program shows a face). */
		static double widest(List<String> lines){double w=0;for(String l:lines)w=Math.max(w,columns(l.replaceFirst("^[0-9]{1,3}(?=\\D)","")));return w;}
		/**
		 * A text's width in columns as a Japanese System lays it out: a full-width character takes 2, a half-width one 1;
		 * letters of a translation into a language with spaces 1.1, as a proportional font's average letter.
		 */
		static double columns(String s){
			double w=0;
			for(int i=0;i<s.length();){
				int cp=s.codePointAt(i);i+=Character.charCount(cp);
				boolean wide=cp>=0x2190&&cp<0xa000||cp>=0xac00&&cp<0xd7a4||cp>=0xf900&&cp<0xfb00||cp>=0xff00&&cp<0xff61||cp>=0xffe0&&cp<0xffe7||cp>0xffff&&VMTranslation.cjk(cp); // arrows, shapes, kana ("ー" too), kanji, hangul, full-width forms
				w+=wide?2:cp>=0xff61&&cp<=0xff9f||cp<0x80&&!Character.isLetter(cp)?1:1.1;
			}
			return w;
		}
		/**
		 * Shares a translation of joined lines out over them as a paragraph: wrapped at the width the longest of them takes
		 * (in columns) - wider only when it needs more lines than there are, then all the lines alike - so a short last line
		 * takes its share too ("Fulmoon and Ethan..." / "I was doing my best." both full rather than one too long).
		 * Lines it does not need stay empty.
		 */
		static List<String> share(String translation,List<String> parts){
			double box=1;for(String p:parts)box=Math.max(box,columns(p));
			return share(translation,parts.size(),box);
		}
		/** A translation laid out over {@code n} lines {@code box} columns wide (wider when it does not fit): exactly n lines. */
		static List<String> share(String translation,int n,double box){
			String t=translation.replaceAll("\\s+"," ").trim();
			List<String> units=units(t);
			double lo=Math.max(1,box),hi=Math.max(lo,columns(t)+1);
			if(wrapColumns(units,lo).size()>n){
				for(int k=0;k<30&&hi-lo>0.5;k++){double mid=(lo+hi)/2;if(wrapColumns(units,mid).size()>n)lo=mid;else hi=mid;}
				lo=hi;
			}
			List<String> lines=wrapColumns(units,lo);
			List<String> out=new ArrayList<String>();
			for(int i=0;i<n;i++)out.add(i<lines.size()?lines.get(i):"");
			for(int i=n;i<lines.size();i++)out.set(n-1,out.get(n-1)+" "+lines.get(i)); // only when the binary search gave up: never loses text
			return out;
		}
		/** The lines a translation takes wrapped at {@code box} columns. */
		static int lineCount(String translation,double box){return wrapColumns(units(translation.replaceAll("\\s+"," ").trim()),Math.max(1,box)).size();}
		/** Units (see {@link VMTranslation#units}) wrapped into lines of at most {@code width} columns. */
		private static List<String> wrapColumns(List<String> units,double width){
			List<String> lines=new ArrayList<String>();
			StringBuilder line=new StringBuilder();
			for(String unit:units){
				if(line.length()>0&&columns((line+unit).replaceAll("\\s+$",""))>width&&!closing(unit)){
					lines.add(line.toString().replaceAll("\\s+$",""));
					line.setLength(0);
					unit=unit.replaceAll("^\\s+","");
				}
				line.append(unit);
			}
			if(line.length()>0||lines.isEmpty())lines.add(line.toString().replaceAll("\\s+$",""));
			return lines;
		}

		/** Ports (windows, offscreen worlds) the user types into: their text is never translated. Shared by the program's translators. */
		private Set<Integer> editablePorts=Collections.newSetFromMap(new ConcurrentHashMap<Integer,Boolean>());
		boolean editable(int port){return port!=0&&editablePorts.contains(port);}
		/** True when the port was not known to be an editing one yet. */
		boolean markEditable(int port){return port!=0&&editablePorts.add(port);}
		/**
		 * Ports the program drew translatable text into (shown translated, or waiting for the translation): a redraw for
		 * translation erases these windows first, as the Window Manager erases what it exposes - a program that draws its
		 * text over what is there would otherwise draw the translation over the original.
		 */
		private Set<Integer> textPorts=Collections.newSetFromMap(new ConcurrentHashMap<Integer,Boolean>());
		void textDrawnIn(int port){if(port!=0)textPorts.add(port);}
		Set<Integer> textPorts(){return textPorts;}
		/** Keeps what another translator of the same program found (the settings changed while it ran): its editing and text ports. */
		void shareEditable(Translator previous){if(previous!=null){editablePorts=previous.editablePorts;textPorts=previous.textPorts;}}

		private final char[] typedChars=new char[16];private final long[] typedAt=new long[16];private int typedNext;
		/** A character the user typed into the program (a keyDown or autoKey without Command). */
		synchronized void typed(char c){typedChars[typedNext]=c;typedAt[typedNext]=System.nanoTime();typedNext=(typedNext+1)%typedChars.length;}
		/**
		 * Whether the text carries the last two characters typed (in the last second and a half) side by side: an editor
		 * drawing what the user types. One character is not enough - a game drawing "Lives" after the S key is no editor.
		 */
		synchronized boolean typedInto(String text){
			long now=System.nanoTime();
			int last=(typedNext+typedChars.length-1)%typedChars.length,before=(last+typedChars.length-1)%typedChars.length;
			if(typedAt[last]==0||typedAt[before]==0||now-typedAt[before]>1_500_000_000L)return false;
			return text.contains(""+typedChars[before]+typedChars[last]);
		}

		private volatile Set<String> known=Collections.emptySet();
		/** The program's own resource strings (programStrings): titles and items from them are its, not the user's. */
		void setKnown(Collection<String> strings){Set<String> k=new HashSet<String>();for(String t:strings)k.add(t.trim());known=k;}
		boolean knows(String text){return text!=null&&known.contains(text.trim());}

		private volatile Set<String> translatedAlready=Collections.emptySet();
		/** Texts in the target language already (the program's translated resources, ResourceTranslation): never sent again. */
		void setTranslatedAlready(Collection<String> texts){Set<String> s=new HashSet<String>();for(String t:texts){Piece p=Piece.of(t);if(p!=null)s.add(p.core);}translatedAlready=s;}
		private boolean translatedAlready(Piece p){return translatedAlready.contains(p.core);}

		/** The source language in use (null while it is being found). */
		String from(){return from;}
		void setFrom(String language){from=language;}

		boolean active(){
			String f=from;
			return config.on&&f!=null&&!f.isEmpty()&&!sameLanguage(f,config.to);
		}

		/**
		 * The translation of a text, or null: when nothing is translated (off, same language, nothing to translate) and
		 * when the translation is still coming (it is then queued and the program is told to redraw once it is here).
		 */
		String lookup(Kind kind,String text){return lookup(kind,text,0);}
		/** @param port where the text is drawn (0: not drawn by the program itself): erased when its translation arrives */
		String lookup(Kind kind,String text,int port){
			if(!active()||text==null)return null;
			Piece p=Piece.of(text);
			if(p==null||translatedAlready(p))return null;
			String f=from;
			String g=Glossary.lookup(f,config.to,p.core,kind);
			if(g!=null)return p.wrap(g);
			Map<String,String> cache=cache(f,config.to,engineFor(kind));
			String cached=cache.get(kindKey(kind,p.core));
			for(Kind other:Kind.values())if(cached==null&&other!=kind)cached=cache(f,config.to,engineFor(other)).get(kindKey(other,p.core)); // a string list's text shown in a dialog
			if(cached!=null){cached=cleanup(p.core,cached);return cached.equals(p.core)?null:p.wrap(cached);} // cleaned again: kept from before a rule
			// A text with numbers in it ("Score: 1250", "3 files") is translated once with its first numbers; the others
			// take that translation with their own numbers - a counter must not send every value to the translator.
			String template=numberTemplate(p.core);
			if(template!=null){
				String t=cache.get(kindKey(kind,template));
				if(t!=null){String filled=fillNumbers(t,p.core);return filled==null?null:p.wrap(cleanup(p.core,filled));}
				if(!templatesAsked.add(kind.ordinal()+"\u0000"+template))return null; // asked with other numbers: wait for that
			}
			String joined=joinedIn.get(p.core);
			if(joined!=null&&pending.contains(kind.ordinal()+"\u0000"+joined)){ // a hand-wrapped line: its sentence is being translated whole
				if(port!=0)waitingPorts.computeIfAbsent(kind.ordinal()+"\u0000"+p.core,k->Collections.newSetFromMap(new ConcurrentHashMap<Integer,Boolean>())).add(port);
				queue(kind,joined,0); // asked already: moved ahead of the texts translated ahead of time
				return null;
			}
			queue(kind,p.core,port);
			return null;
		}

		/**
		 * The translation the glossary or the cache has for a text, without asking for one (null: none, or the same text):
		 * what a program's translated resources are made of. A text of another kind that has one takes it ("Open" of a
		 * dialog from the menu's), so does a text whose numbers differ from a translated one's.
		 */
		String cached(Kind kind,String text){
			String f=from;
			if(f==null||text==null)return null;
			Piece p=Piece.of(text);
			if(p==null)return null;
			String g=Glossary.lookup(f,config.to,p.core,kind);
			if(g!=null)return p.wrap(g);
			List<Kind> kinds=new ArrayList<Kind>();kinds.add(kind);for(Kind k:Kind.values())if(k!=kind)kinds.add(k);
			String template=numberTemplate(p.core);
			for(int pass=0;pass<(template==null?1:2);pass++)for(Kind k:kinds){
				List<String> engines=new ArrayList<String>();engines.add(engineFor(k));for(String e:ENGINES)if(!engines.contains(e))engines.add(e);
				for(String e:engines){
					String t=cache(f,config.to,e).get(kindKey(k,pass==0?p.core:template));
					if(t==null)continue;
					if(pass==1){t=fillNumbers(t,p.core);if(t==null)continue;}
					t=cleanup(p.core,t); // again: translations kept before a rule was added
					return t.equals(p.core)?null:p.wrap(t);
				}
			}
			return null;
		}

		/** Whether a translation of the text is on its way (asked for, or its number template is): it will be shown when it comes. */
		boolean awaiting(Kind kind,String text){
			Piece p=Piece.of(text);if(p==null||!active()||translatedAlready(p))return false;
			if(pending.contains(kind.ordinal()+"\u0000"+p.core))return true;
			String joined=joinedIn.get(p.core);
			if(joined!=null&&pending.contains(kind.ordinal()+"\u0000"+joined))return true;
			String template=numberTemplate(p.core);
			return template!=null&&templatesAsked.contains(kind.ordinal()+"\u0000"+template);
		}

		/** Starts translating texts the program will show soon (its menus when they are set up, a dialog's items). */
		void prefetch(Kind kind,Collection<String> texts){
			if(!active())return;
			for(String t:texts)lookup(kind,t);
		}

		/** The engine for a kind of text: Apple Intelligence only when chosen for it and usable on this Mac now. */
		String engineFor(Kind kind){
			boolean llm=ENGINE_LLM.equals(config.engine)||ENGINE_MIXED.equals(config.engine)&&kind!=Kind.TEXT;
			return llm&&llmUsable()?ENGINE_LLM:ENGINE_TRANSLATION;
		}

		private final Set<String> templatesAsked=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
		/** Ports waiting for each queued text (kind + text), and the ports whose texts arrived since the last redraw. */
		private final Map<String,Set<Integer>> waitingPorts=new ConcurrentHashMap<String,Set<Integer>>();
		private final Set<Integer> arrivedPorts=Collections.newSetFromMap(new ConcurrentHashMap<Integer,Boolean>());
		/** The ports to erase for this redraw: those that showed originals of the translations that arrived. */
		/** Erases the port at the next redraw for translation (it became an editing port: its originals come back). */
		void eraseOnRedraw(int port){if(port!=0)arrivedPorts.add(port);}
		Set<Integer> takeArrivedPorts(){Set<Integer> out=new HashSet<Integer>(arrivedPorts);arrivedPorts.removeAll(out);return out;}
		/** When a failing text may be asked again (a missing language, a refusal): not at every frame a game draws it. */
		private final Map<String,Long> failedUntil=new ConcurrentHashMap<String,Long>();

		private void queue(Kind kind,String core,int port){
			if(offline)return;
			String key=kind.ordinal()+"\u0000"+core;
			if(port!=0)waitingPorts.computeIfAbsent(key,k->Collections.newSetFromMap(new ConcurrentHashMap<Integer,Boolean>())).add(port);
			Long retry=failedUntil.get(key);
			if(retry!=null&&System.currentTimeMillis()<retry)return;
			if(!pending.add(key)){
				wanted.add(key);
				synchronized(this){if(prefetched[kind.ordinal()].remove(core))queued[kind.ordinal()].add(core);} // shown now: ahead of the rest
				return;
			}
			wanted.add(key);
			synchronized(this){queued[kind.ordinal()].add(core);}
			schedule();
		}
		private void schedule(){
			synchronized(this){
				if(flushScheduled)return;
				flushScheduled=true;
			}
			// A short pause gathers what one window draws into one batch (one menu, one dialog), which also gives the
			// translators the texts' neighbours.
			timer.schedule(this::flush,40,TimeUnit.MILLISECONDS);
		}

		/** What is seen first goes first: a dialog or alert, then the text in windows, then the menus (seen when opened). */
		private static final Kind[] PRIORITY={Kind.UI,Kind.TEXT,Kind.MENU};
		/** A batch is with the translator: the next one goes when it comes back (a pile of menu items sent at once kept an
		 *  alert's sentence waiting behind them in macOS's translation service for half a minute). */
		private int inFlight;
		/** Batches out at once: one for a running program; translating ahead, with nothing on the screen waiting, sends more. */
		volatile int maxInFlight=1;

		private void flush(){
			Kind kind=null;List<String> batch=null;
			synchronized(this){
				flushScheduled=false;
				if(inFlight>=maxInFlight)return;
				if(closed)return;
				for(List<String>[] lists:java.util.List.of(queued,prefetched)){ // what is shown first, then the program's texts ahead of time
					for(Kind k:PRIORITY){
						List<String> q=lists[k.ordinal()];
						if(q.isEmpty())continue;
						int n=Math.min(q.size(),engineFor(k).equals(ENGINE_LLM)?24:48);
						batch=new ArrayList<String>(q.subList(0,n));q.subList(0,n).clear();kind=k;
						break;
					}
					if(batch!=null)break;
				}
				if(batch==null)return;
				inFlight++;
			}
			translate(kind,batch);
			if(maxInFlight>1)schedule(); // the next batch goes too
		}
		/** Texts asked for while a request for them (ahead of time) was already out: their arrival is shown. */
		private final Set<String> wanted=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
		/** The batch in flight came back (or gave up): the next one goes. */
		private void done(){
			synchronized(this){inFlight--;}
			flush();
		}

		private void translate(Kind kind,List<String> texts){translate(kind,texts,this::done);}
		/** Sends a batch; {@code finished} runs once its texts came back or were given up. */
		private void translate(Kind kind,List<String> texts,Runnable finished){
			String f=from,to=config.to,engine=engineFor(kind);
			long timeout=engine.equals(ENGINE_LLM)?90_000:45_000;
			Json r=req("translate").put("engine",engine).put("from",f).put("to",to).put("kind",kind.wire()).put("texts",texts);
			helperClient().send(r,timeout,reply->{
				if(arrived(kind,texts,reply,f,to,engine)){finished.run();return;}
				if(engine.equals(ENGINE_LLM)){ // the model refused or miscounted: the Translation framework does the batch instead
					helperClient().send(req("translate").put("engine",ENGINE_TRANSLATION).put("from",f).put("to",to).put("texts",texts),45_000,
						fallback->{if(!arrived(kind,texts,fallback,f,to,engine))giveUp(kind,texts,finished);else finished.run();});
				}else giveUp(kind,texts,finished);
			});
		}
		/** Translating ahead: a batch that failed is tried again in halves, so one text the translator chokes on (a request
		 *  that timed out) costs itself, not the 47 others sent with it. A running program gives up (it asks again later). */
		volatile boolean splitOnFailure;
		private void giveUp(Kind kind,List<String> texts,Runnable finished){
			if(splitOnFailure&&texts.size()>1&&!closed){
				int h=texts.size()/2;
				List<String> first=new ArrayList<String>(texts.subList(0,h)),second=new ArrayList<String>(texts.subList(h,texts.size()));
				translate(kind,first,()->{if(closed){failed(kind,second);finished.run();}else translate(kind,second,finished);});
				return;
			}
			failed(kind,texts);finished.run();
		}

		/** Keeps the translations of a batch (cached under {@code engine}); false when the reply has none. */
		private boolean arrived(Kind kind,List<String> texts,Map<?,?> reply,String f,String to,String engine){
			Object got=reply==null?null:reply.get("texts");
			if(!(got instanceof List)||((List<?>)got).size()!=texts.size())return false;
			Map<String,String> cache=cache(f,to,engine);
			List<?> out=(List<?>)got;
			boolean shown=false; // something on the screen waits for these (not only translations ahead of time)
			for(int i=0;i<texts.size();i++){
				String original=texts.get(i),t=cleanup(original,String.valueOf(out.get(i)));
				cache.put(kindKey(kind,original),t);
				List<String> parts=joins.remove(original);
				if(parts!=null){List<String> shared=share(t,parts);for(int k=0;k<parts.size();k++){cache.put(kindKey(kind,parts.get(k)),shared.get(k));joinedIn.remove(parts.get(k));}}
				String template=numberTemplate(original);
				if(template!=null){
					String tt=numberTemplate(original,t);
					if(tt!=null){if(again)cache.put(kindKey(kind,template),tt);else cache.putIfAbsent(kindKey(kind,template),tt);}
					List<String> siblings=templateSiblings.remove(kind.ordinal()+"\u0000"+template);
					if(siblings!=null&&tt==null)synchronized(siblings){for(String sib:siblings)resend.add(new Object[]{kind,sib});} // the numbers moved: each goes itself
				}
				String key=kind.ordinal()+"\u0000"+original;
				pending.remove(key);
				if(wanted.remove(key))shown=true;
				Set<Integer> ports=waitingPorts.remove(key);
				if(ports!=null){arrivedPorts.addAll(ports);shown=true;}
				if(parts!=null)for(String part:parts)if(wanted.remove(kind.ordinal()+"\u0000"+part)|waitingPorts.remove(kind.ordinal()+"\u0000"+part)!=null)shown=true;
			}
			saveLater(f,to,engine);
			for(Object[] r;(r=resend.poll())!=null;)prefetchOneAlone((Kind)r[0],(String)r[1]);
			if(redraw!=null&&shown)redraw.translationsArrived();
			return true;
		}
		private void failed(Kind kind,List<String> texts){
			long retry=System.currentTimeMillis()+60_000;
			failures.addAndGet(texts.size());
			for(String t:texts){
				String key=kind.ordinal()+"\u0000"+t;pending.remove(key);failedUntil.put(key,retry);
				String template=numberTemplate(t);
				List<String> siblings=template==null?null:templateSiblings.remove(kind.ordinal()+"\u0000"+template);
				if(siblings!=null)failures.addAndGet(siblings.size()); // held back for it: not translated either
			}
		}
	}

	// A number run in a text and the mark that stands for it in a template (kept by the cache like any text).
	private static final java.util.regex.Pattern NUMBER=java.util.regex.Pattern.compile("\\d+");
	private static final char NUMBER_MARK='\u0001';

	/** The text with each run of digits replaced by a mark ("Score: \u0001"), or null when it has no digits. */
	static String numberTemplate(String text){
		java.util.regex.Matcher m=NUMBER.matcher(text);
		if(!m.find())return null;
		return m.replaceAll(String.valueOf(NUMBER_MARK));
	}
	/**
	 * The translation as a template: each number of the original, in order, found in the translation and replaced by the
	 * mark; null when a number did not come through as it was (the translator wrote it in words or reordered them).
	 */
	static String numberTemplate(String original,String translation){
		java.util.regex.Matcher m=NUMBER.matcher(original);
		StringBuilder out=new StringBuilder();int at=0;
		while(m.find()){
			int i=translation.indexOf(m.group(),at);
			if(i<0)return null;
			// a whole number only: "12" must not be found inside "312"
			if(i>0&&Character.isDigit(translation.charAt(i-1))||i+m.group().length()<translation.length()&&Character.isDigit(translation.charAt(i+m.group().length())))return null;
			out.append(translation,at,i).append(NUMBER_MARK);
			at=i+m.group().length();
		}
		return out.append(translation.substring(at)).toString();
	}
	/** A template with the numbers of {@code text} put in, in order; null when the counts differ. */
	static String fillNumbers(String template,String text){
		java.util.regex.Matcher m=NUMBER.matcher(text);
		StringBuilder out=new StringBuilder();
		for(int i=0;i<template.length();i++){
			char c=template.charAt(i);
			if(c!=NUMBER_MARK){out.append(c);continue;}
			if(!m.find())return null;
			out.append(m.group());
		}
		return m.find()?null:out.toString();
	}

	/** Finds the language of a program's texts (its menus and strings); "" when unsure. Waits for the helper. */
	static String detect(Collection<String> texts){
		if(texts.isEmpty()||unavailableReason()!=null)return "";
		Map<?,?> r=helperClient().call(req("detect").put("texts",new ArrayList<String>(texts)),8000);
		return r==null?"":String.valueOf(r.get("language"));
	}

	/**
	 * The texts a program carries in its resources (menus, strings, dialog and alert items, window and control titles),
	 * decoded in its System's encoding: what its language is found from.
	 */
	static List<String> programStrings(ResourceFile68k rf,StackTextEncoding encoding,int limit){
		List<String> out=new ArrayList<String>();
		if(rf==null)return out;
		for(ResourceFile68k.Resource r:rf.all()){
			if(out.size()>=limit)break;
			resourceStrings(r,encoding,out);
		}
		out.removeIf(t->Piece.of(t)==null);
		return out;
	}
	/**
	 * The script a program's own texts are written in, read from them (StackEncodingDetector), not the System it runs under:
	 * an English program under the Japanese System of a Japanese HyperZebra has Mac OS Roman "©", "…" and curly quotes,
	 * which read as half-width katakana in Shift-JIS (Compact Pro's "Copying…" went to the translator as "Copyingﾉ").
	 * Texts that do not decide (plain ASCII) keep {@code system}.
	 */
	static StackTextEncoding programTextEncoding(ResourceFile68k rf,StackTextEncoding system){
		List<byte[]> samples=new ArrayList<byte[]>();
		for(String s:programStrings(rf,StackTextEncoding.ROMAN,4000)){
			byte[] b;try{b=StackTextEncoding.ROMAN.encode(s);}catch(java.nio.charset.CharacterCodingException e){continue;} // its bytes again
			for(byte c:b)if(c<0){samples.add(b);break;}
		}
		StackTextEncoding found=StackEncodingDetector.fromText(samples);
		return found==StackTextEncoding.JAPANESE||found==StackTextEncoding.ROMAN?found:system;
	}
	/** The texts of one resource (MENU, STR#, STR, WIND, DLOG, CNTL, DITL), in order; nothing for other types. */
	static void resourceStrings(ResourceFile68k.Resource r,StackTextEncoding encoding,List<String> out){
		byte[] d;
		try{d=r.originalData();}catch(RuntimeException e){return;} // under a translation (ResourceTranslation): the program's own texts
		if(d==null||(r.attributes&ResourceFile68k.RES_COMPRESSED)!=0)return;
		try{
			switch(ResourceFile68k.typeName(r.type)){
				case "MENU":case "cmnu":{ // MacApp's 'cmnu' is a MENU with a command number after each item
					int at=14,extra=r.type==ResourceFile68k.fourcc("cmnu")?8:4;at=pascal(d,at,encoding,out);
					while(at<d.length&&d[at]!=0){at=pascal(d,at,encoding,out);at+=extra;}
					break;}
				case "STR#":{int n=(d[0]&255)<<8|d[1]&255,at=2;for(int i=0;i<n&&at<d.length;i++)at=pascal(d,at,encoding,out);break;}
				case "STR ":pascal(d,0,encoding,out);break;
				case "WIND":if(d.length>18)pascal(d,18,encoding,out);break;
				case "DLOG":if(d.length>20)pascal(d,20,encoding,out);break;
				case "CNTL":if(d.length>22)pascal(d,22,encoding,out);break;
				case "DITL":{
					int n=((d[0]&255)<<8|d[1]&255)+1,at=2;
					for(int i=0;i<n&&at+14<=d.length;i++){
						int kind=d[at+12]&0x7f,len=d[at+13]&255;
						if((kind>=4&&kind<=6||kind==8)&&at+14+len<=d.length&&len>0)out.add(encoding.decode(d,at+14,len));
						at+=14+len+(len&1);
					}
					break;}
				default:break;
			}
		}catch(RuntimeException ignored){}
	}
	private static int pascal(byte[] d,int at,StackTextEncoding encoding,List<String> out){
		if(at>=d.length)return d.length;
		int n=Math.min(d[at]&255,d.length-at-1);
		if(n>0)out.add(encoding.decode(d,at+1,n));
		return at+1+n;
	}

	/** The kind a resource's texts are translated as (Ahead and ResourceTranslation must agree): menus, string lists, the rest UI. */
	static Kind kindOf(int type){String t=ResourceFile68k.typeName(type);return t.equals("MENU")||t.equals("cmnu")?Kind.MENU:t.startsWith("STR")?Kind.TEXT:Kind.UI;}

	/** Texts of one kind in their order (a string list's entries, a menu's items) for Translator.prefetchProgram. */
	static final class ProgramText {
		final Kind kind;final List<String> lines;
		/** Lines wrapped by hand may go on in the next one (a string list); a stack field's lines are paragraphs. */
		final boolean joinWrapped;
		ProgramText(Kind kind,List<String> lines){this(kind,lines,true);}
		ProgramText(Kind kind,List<String> lines,boolean joinWrapped){this.kind=kind;this.lines=lines;this.joinWrapped=joinWrapped;}
	}
	/** The program's resource strings grouped for prefetching: dialogs and alerts first, then menus, then string lists. */
	static List<ProgramText> programTexts(ResourceFile68k rf,StackTextEncoding encoding){
		List<ProgramText> ui=new ArrayList<ProgramText>(),menus=new ArrayList<ProgramText>(),text=new ArrayList<ProgramText>();
		if(rf==null)return ui;
		for(ResourceFile68k.Resource r:rf.all()){
			String type=ResourceFile68k.typeName(r.type);
			List<String> lines=new ArrayList<String>();
			resourceStrings(r,encoding,lines); // a string list keeps its empty entries: a hand-wrapped line follows the one before it
			if(lines.isEmpty())continue;
			boolean menu=type.equals("MENU")||type.equals("cmnu");
			(menu?menus:type.startsWith("STR")?text:ui).add(new ProgramText(menu?Kind.MENU:type.startsWith("STR")?Kind.TEXT:Kind.UI,lines));
		}
		ui.addAll(menus);ui.addAll(text);
		return ui;
	}

	/** A program's language: kana in its texts make it Japanese; otherwise macOS decides (English when unsure). */
	static String programLanguage(List<String> strings,StackTextEncoding encoding){
		int kana=0;
		for(String t:strings)for(int i=0;i<t.length();i++){
			Character.UnicodeBlock b=Character.UnicodeBlock.of(t.charAt(i));
			if(b==Character.UnicodeBlock.HIRAGANA||b==Character.UnicodeBlock.KATAKANA){kana++;break;}
		}
		if(kana>0&&kana*5>=Math.min(strings.size(),50))return "ja";
		String d=detect(strings.size()>200?strings.subList(0,200):strings);
		if(d!=null&&!d.isEmpty())return d.startsWith("zh")?d:d.split("-")[0];
		return encoding==StackTextEncoding.JAPANESE?"ja":"en";
	}

	/**
	 * Translating a program's resource strings before it runs (the launcher's "Resource Translation…"), into the language and
	 * with the engine of its settings, whether its translation is on or not: the translations go into the cache the
	 * program reads when it runs, so its texts appear translated at once. {@link #start} finds the language (waits for
	 * the helper) and sends the texts; {@link Translator#waiting} counts down to 0.
	 */
	static final class Ahead {
		/** Batches out at once while translating ahead (-Dhz.translate.ahead.batches=): two overlap the round trips; many
		 *  (8-12) jammed macOS's translationd for minutes - every request, also other programs', timed out. */
		static final int AHEAD_BATCHES=2;
		final Translator translator;final String from,to,engine;
		/** The program's texts, and how many of them were sent (the others are cached already or need no translation). */
		final int texts,sent;
		Ahead(Translator t,String from,String to,String engine,int texts,int sent){translator=t;this.from=from;this.to=to;this.engine=engine;this.texts=texts;this.sent=sent;}
		/** A program translated ahead (null for a stack): its file, its resources and the encoding they were read in. */
		File program;ResourceFile68k resources;StackTextEncoding encoding;
		/** The texts read in its pictures: drawn, not written into resources (no script or length limit). */
		Set<String> pictureTexts=Collections.emptySet();
		/** Writes the program's translated resources from what is translated now (ResourceTranslation). */
		ResourceTranslation.Built makeResources() throws IOException{return ResourceTranslation.build(program,resources,translator,encoding);}
		boolean sameLanguage(){return !translator.active();}
		static Ahead start(File program) throws IOException{return start(program,false,false,null);}
		static Ahead start(File program,PictureTranslation.Progress pictures) throws IOException{return start(program,false,false,pictures);}
		/**
		 * @param collect the texts are not sent but collected (Translator.collecting) for an AI chat (LlmTranslation)
		 * @param again texts translated already go again too
		 * @param pictures told how far reading the text in its PICT resources is (PictTranslation), or null
		 */
		static Ahead start(File program,boolean collect,boolean again,PictureTranslation.Progress pictures) throws IOException{
			ResourceFile68k rf=ResourceFile68k.open(program);
			LauncherStore.AppSettings settings=LauncherStore.enabled?LauncherStore.get().settingsFor(program):null;
			StackTextEncoding encoding=programTextEncoding(rf,Toolbox68k.applicationEncoding(rf,settings!=null?settings.language:null));
			Config c=configFor(program);
			List<String> strings=programStrings(rf,encoding,4000);
			String from=AUTO.equals(c.from)?programLanguage(strings,encoding):c.from;
			Translator t=new Translator(new Config(true,from,c.to,c.engine),from,null);
			t.maxInFlight=Integer.getInteger("hz.translate.ahead.batches",AHEAD_BATCHES);
			t.splitOnFailure=true;
			if(collect)t.collecting=new ArrayList<Object[]>();
			t.again=again;
			List<ProgramText> texts=programTexts(rf,encoding);
			Set<String> inPictures=new HashSet<String>();
			if(ResourceTranslation.supports(c.to)&&PictureTranslation.enabledFor(program))texts.addAll(PictTranslation.texts(rf,from,encoding,pictures,inPictures)); // the text in its PICT resources
			int count=0;for(ProgramText g:texts)for(String line:g.lines)if(Piece.of(line)!=null)count++;
			int sent=t.prefetchProgram(texts,Integer.MAX_VALUE);
			Ahead a=new Ahead(t,from,c.to,c.engine,count,sent);
			a.pictureTexts=inPictures;
			a.program=program;a.resources=rf;a.encoding=encoding;
			return a;
		}
	}

	static boolean sameLanguage(String a,String b){
		if(a==null||b==null)return false;
		String x=a.toLowerCase(Locale.ROOT),y=b.toLowerCase(Locale.ROOT);
		if(x.equals(y))return true;
		if(x.startsWith("zh")||y.startsWith("zh"))return false; // Simplified and Traditional are different targets
		return x.split("-")[0].equals(y.split("-")[0]);
	}

	/** Menu items and dialog strings share a cache with drawn text but not their translations (a menu's "Open" vs a sentence). */
	private static String kindKey(Kind kind,String text){return kind==Kind.MENU?"M\t"+text:kind==Kind.UI?"U\t"+text:"T\t"+text;}

	/** What the translators returned, fixed where they habitually differ from the original's shape. */
	static String cleanup(String original,String translated){
		if(translated==null)return original;
		String t=translated.replace("\r\n","\n");
		if(original.endsWith("…")&&t.endsWith("..."))t=t.substring(0,t.length()-3)+"…"; // the original's own ellipsis character
		if(original.endsWith("…")&&!t.endsWith("…"))t=t+"…";
		if(original.endsWith("...")&&t.endsWith("…"))t=t.substring(0,t.length()-1)+"...";
		if(!original.contains("\n"))t=t.replace('\n',' ');
		// the Translation framework puts a blank line between the lines of a text ("Lemke Software\nGermany" ->
		// "レムケソフトウェア\n\nドイツ"): as many lines as the original, where it has no blank ones
		else if(!original.matches("(?s).*\n[ \t]*\n.*"))t=t.replaceAll("\n(?:[ \t]*\n)+","\n");
		// a heading or name from a language without articles ("プロローグ" -> "a prologue"): no article of its own
		if(t.matches("(?i)(a|an|the) \\S+( \\S+)?")&&!original.matches(".*\\p{IsLatin}.*"))t=t.substring(t.indexOf(' ')+1);
		return t;
	}

	/**
	 * A text split into what is translated (core) and what is kept (the spaces around it, a Mac menu's trailing "…"
	 * stays part of the core). Null when there is nothing to translate: no letters, a single character, a number.
	 */
	static final class Piece {
		final String lead,core,trail;
		private Piece(String l,String c,String t){lead=l;core=c;trail=t;}
		static Piece of(String text){
			if(text.length()<2||text.length()>4000)return null;
			int a=0,b=text.length();
			while(a<b&&Character.isWhitespace(text.charAt(a)))a++;
			while(b>a&&Character.isWhitespace(text.charAt(b-1)))b--;
			String core=text.substring(a,b).replace('\r','\n');
			int letters=0;
			for(int i=0;i<core.length();i++){
				char c=core.charAt(i);
				if(Character.isLetter(c))letters++;
			}
			if(letters==0||core.length()<2&&!isIdeograph(core))return null;
			if(letters==1&&core.length()<=2&&!isIdeograph(core))return null; // "A", "x:", a shortcut letter
			return new Piece(text.substring(0,a),core,text.substring(b));
		}
		String wrap(String translated){
			if(lead.isEmpty()&&trail.isEmpty()&&core.indexOf('\n')<0)return translated;
			return lead+translated+trail;
		}
		private static boolean isIdeograph(String s){
			for(int i=0;i<s.length();i++)if(Character.UnicodeScript.of(s.charAt(i))==Character.UnicodeScript.HAN)return true;
			return false;
		}
	}

	/**
	 * A translation broken into lines of at most {@code width} pixels in {@code font}: at spaces, between any two Chinese,
	 * Japanese or Korean characters, never before closing punctuation ("、", "。", ")" ...) nor after an opening one;
	 * a word longer than the line stays whole (the drawing squeezes it). Line breaks of the text are kept.
	 */
	static List<String> wrap(String text,java.awt.Font font,int width){
		List<String> lines=new ArrayList<String>();
		for(String paragraph:text.replace("\r\n","\n").replace('\r','\n').split("\n",-1)){
			List<String> units=units(paragraph);
			StringBuilder line=new StringBuilder();
			for(String unit:units){
				if(line.length()>0&&Fonts68k.width(font,(line+unit).replaceAll("\\s+$",""))>width&&!closing(unit)){
					lines.add(line.toString().replaceAll("\\s+$",""));
					line.setLength(0);
					unit=unit.replaceAll("^\\s+","");
				}
				line.append(unit);
			}
			lines.add(line.toString().replaceAll("\\s+$",""));
		}
		return lines;
	}
	/** Pieces a line may break after: a word with its spaces, or one CJK character (with an opening bracket before it). */
	private static List<String> units(String text){
		List<String> out=new ArrayList<String>();
		StringBuilder u=new StringBuilder();
		for(int i=0;i<text.length();){
			int cp=text.codePointAt(i);String c=new String(Character.toChars(cp));i+=c.length();
			if(cjk(cp)){
				if(u.length()>0&&!opening(u.charAt(u.length()-1))){out.add(u.toString());u.setLength(0);}
				u.append(c);
				if(!opening(cp)){out.add(u.toString());u.setLength(0);}
			}else if(Character.isWhitespace(cp)){u.append(c);if(i>=text.length()||!Character.isWhitespace(text.charAt(i))){out.add(u.toString());u.setLength(0);}}
			else{
				if(u.length()>0&&Character.isWhitespace(u.charAt(u.length()-1))){out.add(u.toString());u.setLength(0);}
				u.append(c);
			}
		}
		if(u.length()>0)out.add(u.toString());
		return out;
	}
	private static boolean cjk(int cp){
		Character.UnicodeScript sc=Character.UnicodeScript.of(cp);
		return sc==Character.UnicodeScript.HAN||sc==Character.UnicodeScript.HIRAGANA||sc==Character.UnicodeScript.KATAKANA||sc==Character.UnicodeScript.HANGUL
			||cp>=0x3000&&cp<0x3040||cp>=0xff00&&cp<0xffef;
	}
	private static boolean opening(int cp){return "([{「『（［｛〈《【〔‘“".indexOf(cp)>=0;}
	private static boolean closing(String unit){
		if(unit.isEmpty())return false;
		return "、。，．・：；？！ー…‥）」』］｝〉》】〕’”,.;:!?)]}%".indexOf(unit.codePointAt(0))>=0
			||"ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ々".indexOf(unit.codePointAt(0))>=0;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Cache (per language pair and engine, kept on disk)

	private static final Map<String,Map<String,String>> caches=new ConcurrentHashMap<String,Map<String,String>>();
	private static final Set<String> dirty=Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());
	private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{
		Thread t=new Thread(r,"VMTranslation");t.setDaemon(true);return t;
	});

	/** How many translated runs and boxes were drawn into programs' ports (tests and tracing). */
	static final java.util.concurrent.atomic.AtomicInteger drawn=new java.util.concurrent.atomic.AtomicInteger();
	/** Runs replaced in place after their translations came, and offscreen areas copied on to windows for them (tests and tracing). */
	static final java.util.concurrent.atomic.AtomicInteger replacedInPlace=new java.util.concurrent.atomic.AtomicInteger(),copiedOn=new java.util.concurrent.atomic.AtomicInteger();

	/** Runs {@code r} on the translation timer after {@code ms} milliseconds. */
	static void later(long ms,Runnable r){timer.schedule(r,ms,TimeUnit.MILLISECONDS);}

	static File cacheDirectory(){return new File(AppPaths.stateDirectory(),"Translations");}

	private static String cacheName(String from,String to,String engine){
		return (from+"_"+to+"_"+engine).replaceAll("[^A-Za-z0-9_-]","");
	}

	static Map<String,String> cache(String from,String to,String engine){
		String name=cacheName(from,to,engine);
		return caches.computeIfAbsent(name,n->{
			Map<String,String> m=new ConcurrentHashMap<String,String>();
			File f=new File(cacheDirectory(),n+".tsv");
			if(f.isFile()&&!Boolean.getBoolean("hz.translate.nocache")){
				try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){
					for(String line;(line=r.readLine())!=null;){
						int a=line.indexOf('\t'),b=a<0?-1:line.indexOf('\t',a+1);
						if(b<0)continue;
						m.put(line.substring(0,a+1)+unescape(line.substring(a+1,b)),unescape(line.substring(b+1)));
					}
				}catch(IOException ignored){}
			}
			return m;
		});
	}

	private static void saveLater(String from,String to,String engine){
		String name=cacheName(from,to,engine);
		if(dirty.add(name))timer.schedule(()->save(name),2,TimeUnit.SECONDS);
	}

	private static void save(String name){
		dirty.remove(name);
		Map<String,String> m=caches.get(name);
		if(m==null||Boolean.getBoolean("hz.translate.nocache"))return;
		File dir=cacheDirectory();
		dir.mkdirs();
		File f=new File(dir,name+".tsv"),tmp=new File(dir,name+".tsv.tmp");
		try(Writer w=new OutputStreamWriter(new FileOutputStream(tmp),StandardCharsets.UTF_8)){
			for(Map.Entry<String,String> e:new TreeMap<String,String>(m).entrySet())
				w.write(e.getKey().substring(0,2)+escape(e.getKey().substring(2))+"\t"+escape(e.getValue())+"\n");
		}catch(IOException e){tmp.delete();return;}
		if(!tmp.renameTo(f)){f.delete();tmp.renameTo(f);}
	}

	/** Writes the caches with new translations now (translating ahead finished or was stopped). */
	static void saveNow(){for(String name:new ArrayList<String>(dirty))save(name);}

	/** Forgets every translation (the settings' "Clear Translations" button). */
	static void clearCache(){
		caches.clear();
		File[] files=cacheDirectory().listFiles((d,n)->n.endsWith(".tsv"));
		if(files!=null)for(File f:files)f.delete();
	}

	// A line is the kind letter, a tab, the text and a tab before the translation; the texts' own tabs and line breaks are escaped.
	private static String escape(String s){
		StringBuilder b=new StringBuilder(s.length()+8);
		for(int i=0;i<s.length();i++){
			char c=s.charAt(i);
			if(c=='\\')b.append("\\\\");else if(c=='\t')b.append("\\t");else if(c=='\n')b.append("\\n");else if(c=='\r')b.append("\\r");else b.append(c);
		}
		return b.toString();
	}
	private static String unescape(String s){
		if(s.indexOf('\\')<0)return s;
		StringBuilder b=new StringBuilder(s.length());
		for(int i=0;i<s.length();i++){
			char c=s.charAt(i);
			if(c=='\\'&&i+1<s.length()){
				char n=s.charAt(++i);
				b.append(n=='t'?'\t':n=='n'?'\n':n=='r'?'\r':n);
			}else b.append(c);
		}
		return b.toString();
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Standard Mac OS terms: short menu and button words translators get wrong out of context ("Quit" -> 辞める).

	static final class Glossary {
		private Glossary(){}
		/** Columns: en, ja (the words of Mac OS 9 / KanjiTalk 7.5 and their English System counterparts). */
		private static final String[][] TERMS={
			{"File","ファイル"},{"Edit","編集"},{"View","表示"},{"Special","特別"},{"Help","ヘルプ"},{"Window","ウインドウ"},
			{"Windows","ウインドウ"},{"Font","フォント"},{"Size","サイズ"},{"Style","スタイル"},{"Format","フォーマット"},
			{"Options","オプション"},{"Tools","ツール"},{"Go","移動"},{"Apple","アップル"},
			{"New","新規"},{"Open","開く"},{"Open…","開く…"},{"Close","閉じる"},{"Close Window","ウインドウを閉じる"},
			{"Save","保存"},{"Save As…","別名で保存…"},{"Save a Copy…","複製を保存…"},{"Revert","復帰"},{"Revert to Saved","保存した状態に戻す"},
			{"Page Setup…","用紙設定…"},{"Print…","プリント…"},{"Print","プリント"},{"Print One","1部プリント"},
			{"Quit","終了"},{"Exit","終了"},{"Undo","取り消し"},{"Redo","やり直し"},{"Cut","カット"},{"Copy","コピー"},
			{"Paste","ペースト"},{"Clear","消去"},{"Delete","削除"},{"Select All","すべてを選択"},{"Show Clipboard","クリップボードを表示"},
			{"Find…","検索…"},{"Find","検索"},{"Find Again","次を検索"},{"Replace…","置換…"},{"Preferences…","初期設定…"},
			{"Preferences","初期設定"},{"Plain Text","標準"},{"Plain","標準"},{"Bold","ボールド"},{"Italic","イタリック"},
			{"Underline","アンダーライン"},{"Outline","アウトライン"},{"Shadow","シャドウ"},{"Condense","字間を詰める"},{"Extend","字間を広げる"},
			{"OK","OK"},{"Cancel","キャンセル"},{"Yes","はい"},{"No","いいえ"},{"Don't Save","保存しない"},{"Don’t Save","保存しない"},
			{"Continue","続ける"},{"Stop","停止"},{"Retry","再試行"},{"Ignore","無視"},{"Done","終了"},{"Apply","適用"},
			{"Default","デフォルト"},{"Defaults","デフォルト"},{"Eject","取り出す"},{"Desktop","デスクトップ"},{"Drive","ドライブ"},
			{"Restart","再起動"},{"Shut Down","システム終了"},{"Empty Trash…","ゴミ箱を空に…"},{"Get Info","情報を見る"},
			{"Duplicate","複製"},{"Show Balloons","バルーンヘルプ表示"},{"Hide Balloons","バルーンヘルプ非表示"},
			{"About Balloon Help…","バルーンヘルプについて…"},{"Hide Others","ほかを隠す"},{"Show All","すべてを表示"},
			{"Next","次へ"},{"Previous","前へ"},{"Back","戻る"},{"Forward","進む"},{"Pause","一時停止"},{"Resume","再開"},
			{"New Game","新規ゲーム"},{"Sound","サウンド"},{"Sound On","サウンドオン"},{"Sound Off","サウンドオフ"},
			{"Insert","挿入"},{"Sort","並べ替え"},{"Rename","名称変更"},{"Settings","設定"},{"Setup","設定"},
			{"Import","読み込み"},{"Import…","読み込み…"},{"Export","書き出し"},{"Export…","書き出し…"},{"Picture","ピクチャ"},{"Pictures","ピクチャ"},
			{"Document","書類"},{"Documents","書類"},{"Close All","すべてを閉じる"},{"Save All","すべてを保存"},{"Open Recent","最近使った項目を開く"},
			{"Next Page","次のページ"},{"Previous Page","前のページ"},{"Find and Replace…","検索と置換…"},{"Show","表示"},{"Hide","隠す"},
			{"Zoom In","拡大"},{"Zoom Out","縮小"},{"Actual Size","実際のサイズ"},{"Rotate","回転"},{"Flip Horizontal","左右反転"},{"Flip Vertical","上下反転"},
			{"Invert Selection","選択範囲を反転"},{"Select None","選択解除"},{"Deselect","選択解除"},{"Slide Show","スライドショー"},{"Slideshow","スライドショー"},
			{"Untitled","名称未設定"},{"Quit Without Saving","保存しないで終了"},{"Abort","中止"},{"Install","インストール"},{"Remove","削除"},
		};
		/** The words of Mac OS 9 in German, French, Spanish and Italian (en, de, fr, es, it): the standard menus and buttons. */
		private static final String[][] EUROPEAN={
			{"File","Ablage","Fichier","Archivo","Archivio"},{"Edit","Bearbeiten","Édition","Edición","Composizione"},
			{"View","Darstellung","Présentation","Visualización","Vista"},{"Window","Fenster","Fenêtre","Ventana","Finestra"},
			{"Help","Hilfe","Aide","Ayuda","Aiuto"},{"Font","Schrift","Police","Tipo de letra","Font"},{"Size","Größe","Taille","Tamaño","Dimensioni"},
			{"Style","Stil","Style","Estilo","Stile"},{"New","Neu","Nouveau","Nuevo","Nuovo"},{"Open…","Öffnen…","Ouvrir…","Abrir…","Apri…"},
			{"Close","Schließen","Fermer","Cerrar","Chiudi"},{"Save","Sichern","Enregistrer","Guardar","Salva"},
			{"Save As…","Sichern unter…","Enregistrer sous…","Guardar como…","Salva con nome…"},
			{"Page Setup…","Papierformat…","Format d'impression…","Ajustar página…","Formato di stampa…"},
			{"Print…","Drucken…","Imprimer…","Imprimir…","Stampa…"},{"Quit","Beenden","Quitter","Salir","Esci"},
			{"Undo","Widerrufen","Annuler","Deshacer","Annulla"},{"Redo","Wiederholen","Rétablir","Rehacer","Ripeti"},
			{"Cut","Ausschneiden","Couper","Cortar","Taglia"},{"Copy","Kopieren","Copier","Copiar","Copia"},
			{"Paste","Einsetzen","Coller","Pegar","Incolla"},{"Clear","Löschen","Effacer","Borrar","Cancella"},
			{"Select All","Alles auswählen","Tout sélectionner","Seleccionar todo","Seleziona tutto"},
			{"Find…","Suchen…","Rechercher…","Buscar…","Trova…"},{"Preferences…","Einstellungen…","Préférences…","Preferencias…","Preferenze…"},
			{"OK","OK","OK","Aceptar","OK"},{"Cancel","Abbrechen","Annuler","Cancelar","Annulla"},{"Yes","Ja","Oui","Sí","Sì"},{"No","Nein","Non","No","No"},
			{"Don't Save","Nicht sichern","Ne pas enregistrer","No guardar","Non salvare"},{"Continue","Fortfahren","Continuer","Continuar","Continua"},
			{"Plain Text","Normal","Normal","Normal","Normale"},{"Bold","Fett","Gras","Negrita","Grassetto"},{"Italic","Kursiv","Italique","Cursiva","Corsivo"},
			{"Underline","Unterstrichen","Souligné","Subrayado","Sottolineato"},
		};
		private static final String[] EUROPEAN_LANGUAGES={"en","de","fr","es","it"};
		private static final Map<String,Map<String,String>> pairs=new HashMap<String,Map<String,String>>();

		/** The table of one direction ("en>ja", "ja>en", "en>de", "de>en", "fr>de" ...), made on first use. */
		private static synchronized Map<String,String> pair(String from,String to){
			String key=from+">"+to;
			Map<String,String> m=pairs.get(key);
			if(m!=null)return m;
			m=new HashMap<String,String>();
			if(from.equals("en")&&to.equals("ja"))for(String[] t:TERMS)m.put(norm(t[0]),t[1]);
			else if(from.equals("ja")&&to.equals("en")){
				for(String[] t:TERMS)m.putIfAbsent(norm(t[1]),t[0]);
				// Japanese words of older KanjiTalk menus the table above does not use.
				String[][] more={{"終わる","Quit"},{"やり直し","Undo"},{"取り消す","Undo"},{"書類","Document"},{"印刷…","Print…"},{"印刷","Print"},
					{"ページ設定…","Page Setup…"},{"別名で保存…","Save As…"},{"保存しないで終了","Quit Without Saving"},{"中止","Cancel"}};
				for(String[] t:more)m.put(norm(t[0]),t[1]);
			}else{
				int f=Arrays.asList(EUROPEAN_LANGUAGES).indexOf(from),t=Arrays.asList(EUROPEAN_LANGUAGES).indexOf(to);
				if(f>=0&&t>=0)for(String[] row:EUROPEAN)m.putIfAbsent(norm(row[f]),row[t]);
				else if(f>=0&&to.equals("ja")){Map<String,String> enJa=pair("en","ja");for(String[] row:EUROPEAN){String j=enJa.get(norm(row[0]));if(j!=null)m.putIfAbsent(norm(row[f]),j);}}
				else if(from.equals("ja")&&t>=0){Map<String,String> jaEn=pair("ja","en");Map<String,String> enX=pair("en",to);for(Map.Entry<String,String> e:jaEn.entrySet()){String x=enX.get(norm(e.getValue()));if(x!=null)m.putIfAbsent(e.getKey(),x);}}
			}
			pairs.put(key,m);
			return m;
		}
		private static String norm(String s){return s.replace("...","…").replace('’','\'').trim();}
		private static String base(String language){return language.split("-")[0];}

		/**
		 * The standard translation of a menu or dialog word, or null. A whole drawn run that is such a word ("Quit" drawn by a
		 * program's own menu definition) takes it too; words inside longer text are never replaced one by one.
		 */
		static String lookup(String from,String to,String text,Kind kind){
			if(text.length()>40||from==null||to==null)return null;
			String t=pair(base(from),base(to)).get(norm(text));
			if(t==null)return null;
			if(text.endsWith("...")&&t.endsWith("…"))t=t.substring(0,t.length()-1)+"...";
			return t;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The helper process

	private static HelperClient client;

	static synchronized HelperClient helperClient(){
		if(client==null)client=new HelperClient();
		return client;
	}

	/** A request being written: a JSON object with an "op". */
	static Json req(String op){return new Json().put("op",op);}

	static final class Json {
		private final StringBuilder b=new StringBuilder("{");
		Json put(String key,Object value){
			if(b.length()>1)b.append(',');
			quote(b,key);b.append(':');
			if(value instanceof Number)b.append(value);
			else if(value instanceof Collection){
				b.append('[');boolean first=true;
				for(Object o:(Collection<?>)value){if(!first)b.append(',');first=false;quote(b,String.valueOf(o));}
				b.append(']');
			}
			else quote(b,String.valueOf(value));
			return this;
		}
		String text(){return b+"}";}
		private static void quote(StringBuilder b,String s){
			b.append('"');
			for(int i=0;i<s.length();i++){
				char c=s.charAt(i);
				switch(c){
					case '"':b.append("\\\"");break;
					case '\\':b.append("\\\\");break;
					case '\n':b.append("\\n");break;
					case '\r':b.append("\\r");break;
					case '\t':b.append("\\t");break;
					default:if(c<0x20)b.append(String.format("\\u%04x",(int)c));else b.append(c);
				}
			}
			b.append('"');
		}
	}

	interface Reply { void received(Map<?,?> reply); }

	static final class HelperClient {
		private Process process;
		private Writer in;
		private final Map<Integer,Reply> waiting=new ConcurrentHashMap<Integer,Reply>();
		private int nextId=1;
		private long failedAt;

		/** Sends a request; the reply (null when the helper is gone) goes to {@code reply} on the reader thread. */
		void send(Json request,Reply reply){send(request,0,reply);}
		/** The same; after {@code timeoutMs} (0: never) the reply is null and a late answer is dropped. */
		void send(Json request,long timeoutMs,Reply reply){
			int id;
			synchronized(this){
				if(!running()){if(reply!=null)reply.received(null);return;}
				id=nextId++;
				if(reply!=null)waiting.put(id,reply);
				try{in.write(request.put("id",id).text());in.write('\n');in.flush();}
				catch(IOException e){waiting.remove(id);stop();if(reply!=null)reply.received(null);return;}
			}
			if(reply!=null&&timeoutMs>0)timer.schedule(()->{Reply r=waiting.remove(id);if(r!=null)r.received(null);},timeoutMs,TimeUnit.MILLISECONDS);
		}

		/** Sends and waits for the reply (null after {@code timeoutMs}). */
		Map<?,?> call(Json request,long timeoutMs){
			CompletableFuture<Map<?,?>> f=new CompletableFuture<Map<?,?>>();
			send(request,f::complete);
			try{return f.get(timeoutMs,TimeUnit.MILLISECONDS);}catch(Exception e){return null;}
		}

		private boolean running(){
			if(process!=null&&process.isAlive())return true;
			if(System.currentTimeMillis()-failedAt<10000)return false; // a helper that just died is not restarted in a loop
			File exe=helper();
			if(exe==null){failedAt=System.currentTimeMillis();return false;}
			try{
				process=new ProcessBuilder(exe.getAbsolutePath()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
				in=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
				Process p=process;
				Thread reader=new Thread(()->read(p),"VMTranslation helper");
				reader.setDaemon(true);reader.start();
				return true;
			}catch(IOException e){failedAt=System.currentTimeMillis();process=null;return false;}
		}

		private void read(Process p){
			try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
				for(String line;(line=r.readLine())!=null;){
					Object o;
					try{o=TalkJson.parse(line);}catch(xTalkException e){continue;}
					if(!(o instanceof Map))continue;
					Map<?,?> m=(Map<?,?>)o;
					int id;
					try{id=Integer.parseInt(String.valueOf(m.get("id")));}catch(NumberFormatException e){continue;}
					Reply reply=waiting.remove(id);
					if(reply!=null)try{reply.received(m.containsKey("error")?null:m);}catch(RuntimeException ignored){}
				}
			}catch(IOException ignored){}
			synchronized(this){
				if(process==p){process=null;failedAt=System.currentTimeMillis();}
			}
			for(Integer id:new ArrayList<Integer>(waiting.keySet())){Reply reply=waiting.remove(id);if(reply!=null)reply.received(null);}
		}

		synchronized void stop(){
			if(process!=null){process.destroy();process=null;}
		}
	}
}
