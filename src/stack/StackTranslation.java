import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Translation of what a HyperZebra stack shows: button names, pop-up choices and the text of fields, with the same
 * translators and cache as programs in the VM (VMTranslation). Only the drawing changes: the stack's data - what
 * scripts read and write, what is saved - stays as it is, and a field being edited shows its own text.
 *
 * Off unless turned on for the stack (its LauncherStore settings): "Resource Translation…" of a stack (the launcher's menus,
 * File > Resource Translation…) translates all its texts into the cache and turns it on; File > Translation Settings… switches it.
 * A field's text is translated line by line (a HyperCard line is a paragraph), so line numbers stay what scripts see:
 * the clickLine of a list field and the line highlighted are the stack's own.
 * <p>
 * A stack's own translations (StackLanguages: Languages/<tag>.po in the stack) come first and need no switch: a text they
 * have is shown in HyperZebra's language, whatever the machine translation is set to; they also translate the cells and
 * column names of table fields and the labels of outline fields, which the machine translation leaves as they are.
 */
final class StackTranslation {
	private StackTranslation(){}

	// ---------------------------------------------------------------------------------------------------------------
	// Settings

	/** Switches set in this session (tests and -Dhz.launcher=false keep them here too), by stack identity. */
	private static final Map<String,Boolean> switched=new HashMap<String,Boolean>();

	/** The settings of a stack (its folder or _stack.xml): off unless turned on for it; the target and engine of the Mac application settings. */
	static VMTranslation.Config configFor(File stack){
		String id=stack==null?null:UiHost.get().launcherStoreIdentity(stack);
		LauncherStore.AppSettings s=id==null||!LauncherStore.enabled?null:LauncherStore.get().settingsFor(stack);
		Boolean sw;synchronized(switched){sw=id==null?null:switched.get(id);}
		boolean on=sw!=null?sw:s!=null&&Boolean.TRUE.equals(s.translate);
		String to=s!=null&&s.translateTo!=null?s.translateTo:PCARD.translateTo68k;
		String from=s!=null&&s.translateFrom!=null?s.translateFrom:VMTranslation.AUTO;
		String engine=s!=null&&s.translateEngine!=null?s.translateEngine:PCARD.translateEngine68k;
		String forced=System.getProperty("hz.translate.stack"); // -Dhz.translate.stack=ja (every stack, to Japanese) or =off
		if(forced!=null){if(forced.equals("off"))on=false;else{on=true;to=forced;}}
		return new VMTranslation.Config(on,from,to,engine); // on also without a translator here: what an AI chat translated (LlmTranslation) is shown
	}

	/** Turns the stack's translation on or off (kept in its launcher settings) and redraws it when it is open. */
	static void setOn(File stack,boolean on){
		String id=UiHost.get().launcherStoreIdentity(stack);
		if(id==null)return;
		synchronized(switched){switched.put(id,on);}
		if(LauncherStore.enabled){
			LauncherStore.AppSettings s=LauncherStore.get().settingsFor(stack);
			if(s==null)s=new LauncherStore.AppSettings();
			s.translate=on?Boolean.TRUE:null;
			LauncherStore.get().putSettings(stack,s);
		}
		settingsChanged();
	}

	static boolean isOn(File stack){return stack!=null&&configFor(stack).on;}

	/** The stack's file for its settings: its folder (a folder stack) or its file; null for a stack not saved yet. */
	static File fileOf(OStack st){
		if(st==null||st.path==null||st.path.isEmpty())return null;
		return new File(st.path);
	}

	/** A HyperZebra stack on disk (a folder with _stack.xml or toc.xml, or one of those files): what can be translated ahead. */
	static boolean isStackFile(File f){
		if(f==null)return false;
		if(UiHost.get().stackBundleIsPacked(f))return true;
		if(f.isDirectory())return new File(f,"_stack.xml").isFile()||new File(f,"toc.xml").isFile();
		return f.isFile()&&(f.getName().equals("_stack.xml")||f.getName().equals("toc.xml"));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The translator of one stack

	/** The source language found for each stack (identity): a stack opened again shows its translations at once. */
	private static final Map<String,String> detected=new HashMap<String,String>();

	static final class State {
		final OStack stack;final File file;
		/** Null when the stack's translation is off. */
		final VMTranslation.Translator translator;
		/** Counts the arrivals of translations: what was worked out for a field before is worked out again. */
		volatile int generation;
		/** Whether the text in its pictures is translated too (PictureTranslation; its settings, read once). */
		final boolean pictures;
		private PictureTranslation.Vocabulary vocabulary;
		/** The stack's texts, which a misread line in a picture is snapped to (made when first wanted). */
		synchronized PictureTranslation.Vocabulary vocabulary(){
			if(vocabulary==null)vocabulary=PictureTranslation.Vocabulary.of(stackTexts(stack));
			return vocabulary;
		}
		State(OStack stack){
			this.stack=stack;file=fileOf(stack);
			pictures=file!=null&&UiHost.get().pictureTranslationEnabledFor(file);
			VMTranslation.Config c=file==null?null:configFor(file);
			if(c==null||!c.on){translator=null;return;}
			String id=UiHost.get().launcherStoreIdentity(file),from;
			synchronized(detected){from=detected.get(id);}
			translator=new VMTranslation.Translator(c,from,this::arrived);
			translator.offline=VMTranslation.unavailableReason()!=null; // the cache only
			if(translator.from()==null){
				Thread t=new Thread(()->{
					String lang=language(stack,stackTexts(stack));
					synchronized(detected){detected.put(id,lang);}
					translator.setFrom(lang);
					arrived();
				},"Stack translation");
				t.setDaemon(true);Platform.get().start(t);
			}
		}
		/** Translations came (or the language was found): the stack is drawn again. */
		void arrived(){
			generation++;
			javax.swing.SwingUtilities.invokeLater(()->{
				if(translator!=null&&translator.closed)return;
				javax.swing.JComponent pane=PCARD.pc!=null&&HZ.stack()==stack?PCARD.pc.mainPane:stack.pcard!=null?stack.pcard.mainPane:null;
				if(pane!=null)pane.repaint();
			});
		}
	}

	private static final Map<OStack,State> states=Collections.synchronizedMap(new WeakHashMap<OStack,State>());

	static State state(OStack st){
		if(st==null)return null;
		synchronized(states){
			State s=states.get(st);
			if(s==null){s=new State(st);states.put(st,s);}
			return s;
		}
	}

	/** The settings changed (the switch, a translation ahead finished): every stack reads them again and is redrawn. */
	static void settingsChanged(){
		List<State> old;
		synchronized(states){old=new ArrayList<State>(states.values());states.clear();}
		for(State s:old){
			if(s.translator!=null)s.translator.closed=true;
			OStack st=s.stack;
			javax.swing.SwingUtilities.invokeLater(()->{
				javax.swing.JComponent pane=PCARD.pc!=null&&HZ.stack()==st?PCARD.pc.mainPane:st.pcard!=null?st.pcard.mainPane:null;
				if(pane!=null)pane.repaint();
			});
		}
	}

	/** The stack's own translations into HyperZebra's language for a part of a stack (StackLanguages), or null: none, or an authoring tool chosen (the originals are edited). */
	private static Map<String,String> ownFor(OCardBase base){
		if(Host.get().authToolActive())return null;
		OStack st=base!=null&&base.stack!=null?base.stack:Host.get().hasCardWindow()?HZ.stack():null;
		Map<String,String> e=StackLanguages.entries(st);
		return e.isEmpty()?null:e;
	}

	/** The stack's own translations (StackLanguages) into HyperZebra's language, or null when it has none. */
	static Map<String,String> ownTranslations(OStack st){
		Map<String,String> e=StackLanguages.entries(st);
		return e.isEmpty()?null:e;
	}

	/** The language a stack is written in: found from its texts when it was translated, else Japanese for a Japanese stack, English for others. */
	static String languageGuess(OStack st){
		File f=fileOf(st);
		String lang=null;
		if(f!=null)synchronized(detected){lang=detected.get(UiHost.get().launcherStoreIdentity(f));}
		if(lang!=null)return lang;
		return st!=null&&st.legacyTextEncoding==StackTextEncoding.JAPANESE?"ja":"en";
	}

	/** The translator for a part of a stack, or null: translation off, the language still being found, or an authoring tool chosen (the originals are edited). */
	private static VMTranslation.Translator translatorFor(OCardBase base){
		if(Host.get().authToolActive())return null;
		OStack st=base!=null&&base.stack!=null?base.stack:Host.get().hasCardWindow()?HZ.stack():null;
		State s=state(st);
		if(s==null||s.translator==null||!s.translator.active())return null;
		return s.translator;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// What the stack's parts show

	/** The name a button shows: its translation, or the name itself (with the classic font's glyphs at control codes, ClassicGlyphs). */
	static String buttonName(OButton b,String name){
		if(b==null||name==null||name.isEmpty())return name;
		Map<String,String> own=ownFor(b.card);
		String s=own==null?null:StackLanguages.lookup(own,name);
		if(s==null){
			VMTranslation.Translator t=translatorFor(b.card);
			s=t==null?null:t.lookup(VMTranslation.Kind.UI,name);
		}
		return ClassicGlyphs.map(b.textFont,s==null?name:s);
	}

	/** A choice of a pop-up button as it is shown (its selected value stays the original). */
	static String popupItem(OButton b,String item){
		if(b==null||item==null||item.isEmpty())return item;
		Map<String,String> own=ownFor(b.card);
		String s=own==null?null:StackLanguages.lookup(own,item);
		if(s==null){
			VMTranslation.Translator t=translatorFor(b.card);
			s=t==null?null:t.lookup(VMTranslation.Kind.MENU,item);
		}
		return s==null?item:s;
	}

	/** A cell of a table field or a label of an outline field as shown: the stack's own translation only (StackLanguages). */
	static String cell(OField f,String text){
		if(f==null||text==null||text.isEmpty())return text;
		Map<String,String> own=ownFor(baseOf(f));
		String s=own==null?null:StackLanguages.lookup(own,text);
		return s==null?text:s;
	}
	private static OCardBase baseOf(OField f){
		return f.card!=null?f.card:f.parent instanceof OCardBase?(OCardBase)f.parent:null;
	}

	/** A field's text as shown (TranslatedLines: the browser player shows the stack's own translations with it too) */
	static final class Shown extends TranslatedLines {
		private Shown(TranslatedLines t){super(t);}
		static Shown of(java.util.function.UnaryOperator<String> lookup,String text,ArrayList<styleClass> styles){TranslatedLines t=TranslatedLines.of(lookup,text,styles);return t==null?null:new Shown(t);}
	}

	private static final Object NOTHING=new Object();
	/** The last text worked out for each field: {text, styles, generation, Shown or NOTHING}. Fields are drawn often. */
	private static final Map<OField,Object[]> memo=Collections.synchronizedMap(new WeakHashMap<OField,Object[]>());

	/** The field's text as shown, or null when nothing in it is translated (yet). */
	static Shown field(OField f,String text){
		if(f==null||text==null||text.isEmpty()||f.tableStyle||f.outlineStyle)return null;
		OCardBase base=baseOf(f);
		Map<String,String> own=ownFor(base);
		VMTranslation.Translator t=translatorFor(base);
		if(t==null&&own==null)return null;
		State s=state(base!=null&&base.stack!=null?base.stack:HZ.stack());
		int generation=s.generation;
		Object[] m=memo.get(f);
		if(m!=null&&m[0].equals(text)&&m[1]==f.styleList&&(Integer)m[2]==generation&&m[4]==own&&m[5]==t)return m[3]==NOTHING?null:(Shown)m[3];
		Shown shown=Shown.of(lookup(own,t,VMTranslation.Kind.TEXT),text,f.styleList);
		memo.put(f,new Object[]{text,f.styleList,generation,shown==null?NOTHING:shown,own,t});
		return shown;
	}

	/** A line's translation: the stack's own first, then the machine translator's (either may be null). */
	private static java.util.function.UnaryOperator<String> lookup(Map<String,String> own,VMTranslation.Translator t,VMTranslation.Kind kind){
		return line->{
			String s=own==null?null:StackLanguages.lookup(own,line);
			return s!=null||t==null?s:t.lookup(kind,line);
		};
	}

	/** Translates a field's text line by line (returns kept as they are); null when no line has a translation. */
	static Shown translate(VMTranslation.Translator t,String text,ArrayList<styleClass> styles){return Shown.of(lookup(null,t,VMTranslation.Kind.TEXT),text,styles);}

	// ---------------------------------------------------------------------------------------------------------------
	// Dialogs a stack's scripts open (answer, ask, UxAnswer)

	/**
	 * What a dialog opened by a stack's script shows: its prompt line by line (as a field's text) and its button names,
	 * translated as dialog words (first in the translator's queue: someone is waiting), the originals until the
	 * translations come. Only what is shown changes: a button still answers with its own name (it), and the text typed
	 * into an ask dialog is never translated. The authoring tools do not matter here: nothing in a dialog is edited.
	 */
	static final class Dialog {
		private final State state;
		/** The stack's own translations (StackLanguages), or null. */
		private final Map<String,String> own;
		private int seen;
		/** @param st the stack whose script opens the dialog (null: the open stack) */
		Dialog(OStack st){
			OStack s=st!=null?st:Host.get().hasCardWindow()?HZ.stack():null;
			state=state(s);
			Map<String,String> e=StackLanguages.entries(s);
			own=e.isEmpty()?null:e;
			seen=state==null?0:state.generation;
		}
		private VMTranslation.Translator translator(){
			return state==null||state.translator==null||!state.translator.active()?null:state.translator;
		}
		/** The prompt as shown now. */
		String prompt(String text){
			VMTranslation.Translator t=translator();
			if(t==null&&own==null||text==null||text.isEmpty())return text;
			Shown shown=Shown.of(lookup(own,t,VMTranslation.Kind.UI),text,null);
			return shown==null?text:shown.text;
		}
		/** A button's name as shown now. */
		String button(String name){
			VMTranslation.Translator t=translator();
			if(t==null&&own==null||name==null||name.isEmpty())return name;
			String s=lookup(own,t,VMTranslation.Kind.UI).apply(name);
			return s==null?name:s;
		}
		/** Runs {@code update} on the event thread each time translations arrived (or the stack's language was found) while
		 *  the dialog is open; null when the stack is not translated. The caller stops the timer when the dialog closes. */
		javax.swing.Timer follow(Runnable update){
			if(state==null||state.translator==null)return null;
			javax.swing.Timer timer=new javax.swing.Timer(150,e->{
				int g=state.generation;
				if(g!=seen){seen=g;update.run();}
			});
			timer.start();
			return timer;
		}
	}

	/** A text of a dialog shown once as it is (a file chooser's title): its translation when there is one now. */
	static String dialogText(OStack st,String text){return new Dialog(st).prompt(text);}

	// ---------------------------------------------------------------------------------------------------------------
	// A stack's texts

	/**
	 * What a stack shows that can be translated, for translating ahead: the names of its buttons that show their names
	 * and pop-up choices (dialog words), then the lines of its fields - shared and card fields, and each card's text of
	 * its background fields. Each text once.
	 */
	static List<VMTranslation.ProgramText> stackTexts(OStack st){return stackTexts(st,null);}
	/** @param templates gets the dialog lines put together at run time, as templates ("{1}位です！", addDialogTexts); they
	 *  are among the dialog texts too (translated ahead as templates) */
	private static List<VMTranslation.ProgramText> stackTexts(OStack st,Set<String> templates){
		if(templates==null)templates=new LinkedHashSet<String>();
		LinkedHashSet<String> names=new LinkedHashSet<String>(),choices=new LinkedHashSet<String>(),lines=new LinkedHashSet<String>(),
			dialogs=new LinkedHashSet<String>();
		List<OCardBase> bases=new ArrayList<OCardBase>();
		try{bases.addAll(new ArrayList<OBackground>(st.bgCacheList));bases.addAll(new ArrayList<OCard>(st.cdCacheList));}catch(RuntimeException ignored){}
		addDialogTexts(dialogs,templates,st.scriptList);
		for(OCardBase base:bases){
			try{
				addDialogTexts(dialogs,templates,base.scriptList);
				for(OButton b:new ArrayList<OButton>(base.btnList)){
					if(b.style==8){if(b.getText()!=null)for(String c:b.getText().split("\r\n|\r|\n"))add(choices,c);}
					if(b.showName)add(names,b.name);
					addDialogTexts(dialogs,templates,b.scriptList);
				}
				for(OField f:new ArrayList<OField>(base.fldList)){
					addDialogTexts(dialogs,templates,f.scriptList);
					if(f.tableStyle||f.outlineStyle)continue;
					if(base instanceof OCard||f.sharedText)addLines(lines,f.getText());
				}
				if(base instanceof OCard){
					OCard cd=(OCard)base;
					for(OBgFieldData d:new ArrayList<OBgFieldData>(cd.bgfldList)){
						OField f=cd.bg==null?null:cd.bg.GetFldbyId(d.id);
						if(f!=null&&(f.sharedText||f.tableStyle||f.outlineStyle))continue;
						addLines(lines,d.text);
					}
				}
			}catch(RuntimeException ignored){} // a script changing the stack meanwhile: what was read is enough
		}
		dialogs.addAll(templates);
		List<VMTranslation.ProgramText> out=new ArrayList<VMTranslation.ProgramText>();
		if(!names.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.UI,new ArrayList<String>(names)));
		if(!choices.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.MENU,new ArrayList<String>(choices)));
		if(!lines.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.TEXT,new ArrayList<String>(lines),false));
		if(!dialogs.isEmpty())out.add(new VMTranslation.ProgramText(VMTranslation.Kind.UI,new ArrayList<String>(dialogs),false));
		return out;
	}

	/**
	 * The translations of a stack's texts the cache has (what Resource Translation… and AI Chat… made), by the language they
	 * are in: text → translation, in the stack's order. For Export for Web, which puts them in the copy as Languages/<tag>.po
	 * (StackLanguages): the browser has no cache and no translator. Empty when the stack's translation is off.
	 */
	static Map<String,Map<String,String>> cachedTranslations(OStack st){
		Map<String,Map<String,String>> out=new LinkedHashMap<String,Map<String,String>>();
		File file=fileOf(st);
		if(file==null)return out;
		VMTranslation.Config c=configFor(file);
		if(!c.on)return out;
		String from=VMTranslation.AUTO.equals(c.from)?languageGuess(st):c.from;
		List<VMTranslation.ProgramText> texts=null;
		Set<String> templates=new LinkedHashSet<String>();
		for(String to:VMTranslation.TARGETS){
			if(VMTranslation.sameLanguage(from,to)||!VMTranslation.hasCache(from,to))continue;
			if(texts==null)texts=stackTexts(st,templates);
			VMTranslation.Translator t=new VMTranslation.Translator(new VMTranslation.Config(true,from,to,c.engine),from,null);
			Map<String,String> m=new LinkedHashMap<String,String>();
			for(VMTranslation.ProgramText g:texts)for(String line:g.lines){
				if(line==null||line.trim().isEmpty()||m.containsKey(line))continue;
				String s=t.cached(g.kind,line);
				if(s!=null&&!s.equals(line)&&(!templates.contains(line)||StackLanguages.Template.keepsValues(line,s)))m.put(line,s);
			}
			addShownLines(m,templates,from,to);
			if(!m.isEmpty())out.put(to,m);
		}
		return out;
	}

	/**
	 * The dialog lines put together at run time ("あ" & x & "い") that were shown translated on this computer: the cache
	 * has them as they were shown, with the values of the time. Each cached dialog text that one of the patterns matches.
	 */
	private static void addShownLines(Map<String,String> m,Set<String> templates,String from,String to){
		if(templates.isEmpty())return;
		List<StackLanguages.Template> patterns=new ArrayList<StackLanguages.Template>();
		for(String t:templates){StackLanguages.Template p=StackLanguages.Template.of(t,t);if(p!=null)patterns.add(p);}
		for(String engine:VMTranslation.ENGINES)for(Map.Entry<String,String> e:VMTranslation.cache(from,to,engine).entrySet()){
			String key=e.getKey();
			if(!key.startsWith("U\t"))continue; // dialog words
			String text=key.substring(2);
			if(m.containsKey(text)||templates.contains(text))continue; // (a template's own translation went through keepsValues)
			for(StackLanguages.Template p:patterns){
				if(p.values(text)==null)continue;
				String s=VMTranslation.cleanup(text,e.getValue());
				if(s!=null&&!s.equals(text))m.put(text,s);
				break;
			}
		}
	}

	/** answer or ask as a command of a line (also after then/else) and what follows it. */
	private static final java.util.regex.Pattern DIALOG_COMMAND=java.util.regex.Pattern.compile("(?i)(?:^|\\bthen\\b|\\belse\\b)\\s*(answer|ask)\\b(.*)");

	/**
	 * The prompts and button names of the answer and ask commands of a script, where they are written out (quoted,
	 * joined with &, && and return): the lines its dialogs will show. A prompt put together at run time is translated
	 * when it is shown. Not the default text of ask (typed over) nor answer program.
	 */
	static void addDialogTexts(Set<String> out,List<String> script){addDialogTexts(out,null,script);}
	/** @param templates gets a template ("{1}位です！") for each line of a prompt with a value in it (a variable, a function), or null */
	static void addDialogTexts(Set<String> out,Set<String> templates,List<String> script){
		if(script==null)return;
		List<String> lines;
		try{lines=new ArrayList<String>(script);}catch(RuntimeException e){return;}
		StringBuilder joined=new StringBuilder();
		for(String raw:lines){
			if(raw==null)continue;
			String line=withoutComment(raw);
			String t=line.stripTrailing();
			if(t.endsWith("\u00AC")){joined.append(t,0,t.length()-1).append(' ');continue;} // ¬ continues the line
			joined.append(line);
			addDialogLine(out,templates,joined.toString());
			joined.setLength(0);
		}
		if(joined.length()>0)addDialogLine(out,templates,joined.toString());
	}
	private static String withoutComment(String line){
		boolean quoted=false;
		for(int i=0;i+1<line.length();i++){
			char c=line.charAt(i);
			if(c=='"')quoted=!quoted;
			else if(!quoted&&c=='-'&&line.charAt(i+1)=='-')return line.substring(0,i);
		}
		return line;
	}
	private static void addDialogLine(Set<String> out,Set<String> templates,String line){
		if(line.indexOf('"')<0)return;
		java.util.regex.Matcher m=DIALOG_COMMAND.matcher(line);
		if(!m.find())return;
		boolean answer=m.group(1).equalsIgnoreCase("answer");
		List<String> tokens=tokens(m.group(2));
		int i=0;
		boolean file=false;
		String first=i<tokens.size()?tokens.get(i).toLowerCase():"";
		if(answer&&first.equals("program"))return;
		if(first.equals("file")||answer&&first.equals("folder")){file=true;i++;}
		if(!answer&&first.equals("password")){i++;if(i<tokens.size()&&tokens.get(i).equalsIgnoreCase("clear"))i++;}
		int with=tokens.size();
		for(int k=i;k<tokens.size();k++){
			String w=tokens.get(k);
			if(w.equalsIgnoreCase("with")||file&&w.equalsIgnoreCase("of")&&k+1<tokens.size()&&tokens.get(k+1).equalsIgnoreCase("type")){with=k;break;}
		}
		addPrompt(out,templates,tokens.subList(i,with));
		if(!answer||file||with>=tokens.size()||!tokens.get(with).equalsIgnoreCase("with"))return; // ask's default text is typed over
		int from=with+1;
		for(int k=from;k<=tokens.size();k++){
			if(k==tokens.size()||tokens.get(k).equalsIgnoreCase("or")||tokens.get(k).equals(",")){
				String name=literal(tokens.subList(from,k));
				if(name!=null)add(out,name);
				from=k+1;
			}
		}
	}
	/**
	 * The lines of a prompt: written out whole, or line by line where only some of its lines are (the others have a value
	 * in them: "　　-操作方法-" & return & LRKEY & "で左右に動き、"). A line with values becomes a template, its written
	 * parts with {1}, {2}... for the values (the same expression the same number: "{1}でジャンプ…{1}を長く…"), when its
	 * written parts have something to translate.
	 */
	private static void addPrompt(Set<String> out,Set<String> templates,List<String> tokens){
		String whole=literal(tokens);
		if(whole!=null){addLines(out,whole);return;}
		int from=0;
		for(int k=0;k<=tokens.size();k++){
			String w=k<tokens.size()?tokens.get(k).toLowerCase():null;
			if(w!=null&&!w.equals("return")&&!w.equals("cr")&&!w.equals("linefeed")&&!w.equals("lf"))continue;
			List<String> part=tokens.subList(from,k);
			from=k+1;
			String text=literal(part);
			if(text!=null){addLines(out,text);continue;}
			if(templates==null)continue;
			StringBuilder tpl=new StringBuilder(),written=new StringBuilder(),value=new StringBuilder();
			List<String> values=new ArrayList<String>();
			boolean ok=true;
			for(int j=0;j<=part.size();j++){
				String t=j<part.size()?part.get(j):null,v=t==null?null:t.toLowerCase();
				boolean quoted=t!=null&&t.length()>=2&&t.startsWith("\"")&&t.endsWith("\"");
				boolean joiner=t!=null&&(v.equals("&")||v.equals("&&")||v.equals("space")||v.equals("empty"));
				if(t!=null&&!quoted&&!joiner){value.append(v).append(' ');continue;}
				if(value.length()>0){
					String key=value.toString().trim();value.setLength(0);
					int n=values.indexOf(key);
					if(n<0){values.add(key);n=values.size()-1;}
					tpl.append('{').append(n+1).append('}');
				}
				if(t==null)break;
				if(quoted){
					String q=t.substring(1,t.length()-1);
					if(q.indexOf('{')>=0||q.indexOf('}')>=0)ok=false; // (braces would read as values)
					tpl.append(q);written.append(q);
				}
				else if(v.equals("&&")||v.equals("space"))tpl.append(' ');
			}
			if(ok&&written.toString().trim().length()>=2&&VMTranslation.Piece.of(written.toString())!=null)templates.add(tpl.toString().trim());
		}
	}
	/** Quoted strings (with their quotes), words, && and single characters. */
	private static List<String> tokens(String s){
		List<String> out=new ArrayList<String>();
		for(int i=0;i<s.length();){
			char c=s.charAt(i);
			if(Character.isWhitespace(c)){i++;continue;}
			int j=i+1;
			if(c=='"'){while(j<s.length()&&s.charAt(j)!='"')j++;if(j<s.length())j++;}
			else if(Character.isLetterOrDigit(c)||c=='_'){while(j<s.length()&&(Character.isLetterOrDigit(s.charAt(j))||s.charAt(j)=='_'||s.charAt(j)=='.'))j++;}
			else if(c=='&'&&j<s.length()&&s.charAt(j)=='&')j++;
			out.add(s.substring(i,j));
			i=j;
		}
		return out;
	}
	/** The value of an expression made of quoted strings only (joined with &, &&, return, space): null for anything else. */
	private static String literal(List<String> tokens){
		StringBuilder b=new StringBuilder();
		boolean any=false;
		for(String t:tokens){
			String w=t.toLowerCase();
			if(t.length()>=2&&t.startsWith("\"")&&t.endsWith("\"")){b.append(t,1,t.length()-1);any=true;}
			else if(w.equals("&"));
			else if(w.equals("&&")||w.equals("space"))b.append(' ');
			else if(w.equals("return")||w.equals("cr"))b.append('\r');
			else if(w.equals("linefeed")||w.equals("lf"))b.append('\n');
			else if(w.equals("empty"));
			else return null;
		}
		return any?b.toString():null;
	}
	private static void add(Set<String> out,String text){
		if(text!=null&&VMTranslation.Piece.of(text)!=null)out.add(text);
	}
	private static void addLines(Set<String> out,String text){
		if(text==null||text.isEmpty())return;
		for(String line:text.split("\r\n|\r|\n"))add(out,line);
	}

	/** The language of a stack's texts (kana make it Japanese; otherwise macOS decides). Waits for the helper. */
	static String language(OStack st,List<VMTranslation.ProgramText> texts){
		List<String> sample=new ArrayList<String>();
		for(VMTranslation.ProgramText g:texts)for(String line:g.lines){if(sample.size()>=300)break;sample.add(line);}
		return VMTranslation.programLanguage(sample,st.legacyTextEncoding);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Translating ahead

	/**
	 * Starts translating all of a stack's texts into the cache ("Resource Translation…" of a stack), into the language and with
	 * the engine of its settings: the open stack itself when it is this one (with its changes), else the stack read from
	 * its folder. Finds the language first (waits for the helper), then reads the text in its pictures (PictureTranslation).
	 */
	static VMTranslation.Ahead ahead(File file) throws IOException{return ahead(file,false,false,null);}
	static VMTranslation.Ahead ahead(File file,PictureTranslation.Progress pictures) throws IOException{return ahead(file,false,false,pictures);}
	/**
	 * @param collect the texts are not sent but collected (Translator.collecting) for an AI chat (LlmTranslation)
	 * @param again texts translated already go again too
	 * @param pictures told how far reading the text in the stack's pictures is (PictureTranslation), or null
	 */
	static VMTranslation.Ahead ahead(File file,boolean collect,boolean again,PictureTranslation.Progress pictures) throws IOException{
		OStack st=openStack(file);
		if(st==null)st=read(file);
		if(st==null)throw new IOException("The stack cannot be read.");
		VMTranslation.Config c=configFor(file);
		List<VMTranslation.ProgramText> texts=stackTexts(st);
		String from=VMTranslation.AUTO.equals(c.from)?language(st,texts):c.from;
		texts.addAll(PictureTranslation.aheadTexts(st,file,from,pictures)); // the text in its pictures (when tesseract is here)
		String id=UiHost.get().launcherStoreIdentity(file);
		synchronized(detected){detected.put(id,from);}
		VMTranslation.Translator t=new VMTranslation.Translator(new VMTranslation.Config(true,from,c.to,c.engine),from,null);
		t.maxInFlight=Integer.getInteger("hz.translate.ahead.batches",VMTranslation.Ahead.AHEAD_BATCHES);
		t.splitOnFailure=true;
		if(collect)t.collecting=new ArrayList<Object[]>();
		t.again=again;
		int count=0;for(VMTranslation.ProgramText g:texts)count+=g.lines.size();
		int sent=t.prefetchProgram(texts,Integer.MAX_VALUE);
		return new VMTranslation.Ahead(t,from,c.to,c.engine,count,sent);
	}

	/** The open stack when it is the one in {@code file}, else null. */
	static OStack openStack(File file){
		OStack cur=Host.get().hasCardWindow()?HZ.stack():null;
		File f=fileOf(cur);
		return f!=null&&UiHost.get().launcherStoreIdentity(f).equals(UiHost.get().launcherStoreIdentity(file))?cur:null;
	}

	/** A folder stack read on the side (nothing is shown, run or written); null when it cannot be read. */
	static OStack read(File file){
		if(UiHost.get().stackBundleIsPacked(file))try{file=StackBundle.open(file);}catch(java.io.IOException e){return null;} // a stack file: its working folder
		File xml=file;
		if(file.isDirectory()){xml=new File(file,"_stack.xml");if(!xml.isFile())xml=new File(file,"toc.xml");}
		if(!isStackFile(xml))return null;
		OStack s=new OStack(null);
		s.scriptList=new ArrayList<String>();
		s.file=xml;s.path=xml.getPath();
		try{
			if(!XMLRead.readToc(xml.getPath(),s)||!StackProtection.unlockOnOpen(s))return null;
		}catch(RuntimeException e){return null;}
		return s;
	}
}
