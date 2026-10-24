import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The quick HyperTalk reference of the script editor (GScriptReference): one line per form of a
 * command, function, property, message, control structure, chunk, operator or constant, with a
 * one-line explanation. The entries are parsed from {@link #DATA}.
 *
 * <p>Data format: "## category" starts a section; each entry is
 * {@code key || syntax || English [|| extra categories]}. The explanations and the category titles
 * are translated with the catalogs (msgctxt "reference"). A key starting with "*" is a
 * HyperZebra extension (not in HyperCard). In the syntax, {@code [..]} is optional, {@code a|b}
 * chooses one (alternatives never contain spaces), {@code <x>} is a placeholder, {@code ¶} is a
 * line break, two spaces separate several forms of one entry (only the first is inserted) and a
 * backquote makes the next character literal ({@code `[} is a real bracket).
 */
final class HyperTalkReference {
	private HyperTalkReference(){}

	/** The catalogs' context (msgctxt) of the explanations and the category titles. */
	static final String CONTEXT="reference";

	/** A group of entries; "prop.button" is shown under "prop". */
	static final class Category {
		final String id,en;
		Category(String id,String en){this.id=id;this.en=en;}
		/** The title in the language in use. */
		String title(){return International.tc(CONTEXT,en);}
		boolean isSub(){return id.indexOf('.')>=0;}
	}

	static final class Entry {
		final String key,syntax,en;
		final boolean hz;
		final List<String> cats;
		final int order;
		Entry(String key,String syntax,String en,boolean hz,List<String> cats,int order){
			this.key=key;this.syntax=syntax;this.en=en;this.hz=hz;this.cats=cats;this.order=order;
		}
		/** The explanation in the language in use. */
		String description(){return International.tc(CONTEXT,en);}
		/** The syntax as shown: escapes removed, line breaks as " … ", forms apart with " · ". */
		String display(){return displaySyntax(syntax);}
		/** The text inserted into a script: see {@link HyperTalkReference#template(String)}. */
		String template(){return HyperTalkReference.template(syntax);}
		boolean in(String categoryId){
			if(categoryId==null||categoryId.isEmpty())return true;
			for(String c:cats)if(c.equals(categoryId)||c.startsWith(categoryId+"."))return true;
			return false;
		}
	}

	static final List<Category> CATEGORIES=List.of(
		new Category("cmd","Commands"),
		new Category("func","Functions"),
		new Category("ctrl","Control structures"),
		new Category("chunk","Chunks"),
		new Category("op","Operators"),
		new Category("const","Constants"),
		new Category("obj","Object references"),
		new Category("msg","Messages"),
		new Category("prop","Properties"),
		new Category("prop.button","Button"),
		new Category("prop.field","Field"),
		new Category("prop.shape","Shape"),
		new Category("prop.card","Card"),
		new Category("prop.bg","Background"),
		new Category("prop.stack","Stack"),
		new Category("prop.panel","Panel"),
		new Category("prop.window","Window & movie"),
		new Category("prop.menu","Menu"),
		new Category("prop.paint","Paint"),
		new Category("prop.global","HyperCard (global)"),
		new Category("coll","Collections, JSON, web"));

	static Category category(String id){
		for(Category c:CATEGORIES)if(c.id.equals(id))return c;
		return null;
	}

	private static List<Entry> entries;

	static synchronized List<Entry> entries(){
		if(entries==null)entries=Collections.unmodifiableList(parse(DATA));
		return entries;
	}

	static List<Entry> parse(String data){
		List<Entry> list=new ArrayList<Entry>();
		String section=null;
		for(String raw:data.split("\n")){
			String line=raw.strip();
			if(line.isEmpty())continue;
			if(line.startsWith("##")){section=line.substring(2).strip();continue;}
			String[] f=line.split("\\s*\\|\\|\\s*");
			if(f.length<3||section==null)throw new IllegalArgumentException("bad reference line: "+line);
			String key=f[0];
			boolean hz=key.startsWith("*");
			if(hz)key=key.substring(1);
			List<String> cats=new ArrayList<String>();
			cats.add(section);
			if(f.length>3)for(String c:f[3].split("[, ]+"))if(!c.isEmpty()&&!cats.contains(c))cats.add(c);
			list.add(new Entry(key,f[1],f[2],hz,List.copyOf(cats),list.size()));
		}
		return list;
	}

	/** The syntax as shown in the list. */
	static String displaySyntax(String syntax){
		StringBuilder b=new StringBuilder();
		for(int i=0;i<syntax.length();i++){
			char c=syntax.charAt(i);
			if(c=='`'&&i+1<syntax.length()){b.append(syntax.charAt(++i));continue;}
			if(c==' '&&i+1<syntax.length()&&syntax.charAt(i+1)==' '){
				// two spaces separate the forms of one entry
				while(i+1<syntax.length()&&syntax.charAt(i+1)==' ')i++;
				b.append("  ·  ");
				continue;
			}
			if(c=='¶'){
				while(i+1<syntax.length()&&(syntax.charAt(i+1)=='¶'||syntax.charAt(i+1)==' '))i++;
				while(b.length()>0&&b.charAt(b.length()-1)==' ')b.setLength(b.length()-1);
				b.append(" … ");
				continue;
			}
			b.append(c);
		}
		return b.toString();
	}

	/**
	 * The text a script gets for a syntax: optional parts dropped, the first of each set of
	 * alternatives kept and ¶ as a line break. Placeholders stay for the writer to type over.
	 */
	static String template(String syntax){
		int forms=syntax.indexOf("  ");
		if(forms>0)syntax=syntax.substring(0,forms);
		StringBuilder b=new StringBuilder();
		int depth=0;
		boolean skipAlternative=false;
		for(int i=0;i<syntax.length();i++){
			char c=syntax.charAt(i);
			if(c=='`'&&i+1<syntax.length()){
				char next=syntax.charAt(++i);
				if(depth==0&&!skipAlternative)b.append(next);
				continue;
			}
			if(c=='['){depth++;continue;}
			if(c==']'&&depth>0){depth--;continue;}
			if(depth>0)continue;
			if(c=='|'){skipAlternative=true;continue;}
			if(skipAlternative){
				if(c==' '||c=='¶'||c==','||c==')'||c=='\''||c=='"')skipAlternative=false;
				else continue;
			}
			if(c=='¶'){
				while(b.length()>0&&b.charAt(b.length()-1)==' ')b.setLength(b.length()-1);
				b.append('\n');
				while(i+1<syntax.length()&&syntax.charAt(i+1)==' ')i++;
				continue;
			}
			b.append(c);
		}
		// dropping an optional part can leave two spaces or a space before a comma
		String s=b.toString().replaceAll(" {2,}"," ").replaceAll(" +\n","\n").replaceAll(" ([,)])","$1").strip();
		// a message becomes a whole handler
		if(s.startsWith("on ")&&s.indexOf('\n')<0){
			String name=s.substring(3).strip().split("\\s+")[0];
			s=s+"\n\nend "+name;
		}
		return s;
	}

	/** Where the first placeholder of a template is, as {start, end}, or null. */
	static int[] firstPlaceholder(String template){
		java.util.regex.Matcher m=java.util.regex.Pattern.compile("<[^<>\\s][^<>]*>").matcher(template);
		while(m.find())if(!isTag(template,m.start(),m.end()))return new int[]{m.start(),m.end()};
		return null;
	}

	/** Whether {@code <…>} from start to end is an SVG tag in the syntax (it touches another tag or closes one), not a placeholder. */
	static boolean isTag(String s,int start,int end){
		return s.charAt(start+1)=='/'||(start>0&&s.charAt(start-1)=='>')||(end<s.length()&&s.charAt(end)=='<');
	}

	/**
	 * Entries of a category (null or "" for all) whose key, syntax or explanation (in English or
	 * in the language in use) contains every word of the query, the best matches first: the key
	 * itself, then keys starting with the query, then syntax words, then the syntax anywhere, then
	 * the explanations.
	 */
	static List<Entry> search(String query,String categoryId,boolean hzOnly){
		String[] terms=query==null?new String[0]:query.strip().toLowerCase(Locale.ROOT).split("\\s+");
		if(terms.length==1&&terms[0].isEmpty())terms=new String[0];
		List<Entry> hits=new ArrayList<Entry>();
		final List<Integer> scores=new ArrayList<Integer>();
		for(Entry e:entries()){
			if(hzOnly&&!e.hz)continue;
			if(!e.in(categoryId))continue;
			int total=0;
			boolean all=true;
			for(int t=0;t<terms.length;t++){
				int s=score(e,terms[t]);
				if(s<0){all=false;break;}
				total+=t==0?s*10:s;
			}
			if(!all)continue;
			// several words: an entry whose key or syntax reads as the whole phrase comes first
			if(terms.length>1){
				String phrase=String.join(" ",terms);
				String key=e.key.toLowerCase(Locale.ROOT);
				if(key.equals(phrase))total-=1000;
				else if(key.startsWith(phrase))total-=500;
				else if(e.display().toLowerCase(Locale.ROOT).contains(phrase))total-=200;
			}
			hits.add(e);
			scores.add(total);
		}
		if(terms.length==0)return hits;
		Integer[] idx=new Integer[hits.size()];
		for(int i=0;i<idx.length;i++)idx[i]=i;
		java.util.Arrays.sort(idx,(a,b)->{
			int c=Integer.compare(scores.get(a),scores.get(b));
			return c!=0?c:Integer.compare(hits.get(a).order,hits.get(b).order);
		});
		List<Entry> sorted=new ArrayList<Entry>(idx.length);
		for(Integer i:idx)sorted.add(hits.get(i));
		return sorted;
	}

	/** How well one lower-case query word matches an entry (smaller is better), or -1. */
	static int score(Entry e,String term){
		String key=e.key.toLowerCase(Locale.ROOT);
		if(key.equals(term))return 0;
		if(key.startsWith(term))return 1;
		String syntax=e.display().toLowerCase(Locale.ROOT);
		int best=-1;
		for(String w:syntax.split("[^\\p{L}\\p{N}]+")){
			if(w.equals(term))return 2;
			if(w.startsWith(term))best=3;
		}
		if(best>0)return best;
		if(key.contains(term))return 4;
		if(syntax.contains(term))return 4;
		// the explanation in English and in the language in use
		if(e.en.toLowerCase(Locale.ROOT).contains(term)||e.description().toLowerCase(Locale.ROOT).contains(term))return 5;
		return -1;
	}

	private static final java.util.Set<String> STOP_WORDS=java.util.Set.of(
		"the","of","to","in","into","a","an","at","by","for","from","with");

	/** Whether a word from a script names something in the reference (for looking up the word at the caret). */
	static boolean knows(String word){
		if(word==null||word.isBlank())return false;
		String w=word.strip().toLowerCase(Locale.ROOT);
		// the glue of every statement would list half the reference
		if(STOP_WORDS.contains(w))return false;
		for(Entry e:entries()){
			if(e.key.toLowerCase(Locale.ROOT).equals(w))return true;
			for(String s:e.display().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(s.equals(w))return true;
		}
		// other names ("also hilight") are only in the explanations
		if(w.length()>=4)for(Entry e:entries())
			for(String s:e.en.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(s.equals(w))return true;
		return false;
	}

	static final String DATA="""
## cmd
put || put <expr> [into|after|before <container>] || Puts a value into a container (the message box if omitted)
get || get <expr> || Puts a value into it
set || set [the] <property> [of <object>] to <value> || Sets a property || prop
add || add <number> to <container> || Adds to a container
subtract || subtract <number> from <container> || Subtracts from a container
multiply || multiply <container> by <number> || Multiplies a container
divide || divide <container> by <number> || Divides a container
answer || answer <text> [with <button> [or <button> [or <button>]]] || Dialog with buttons; the clicked one goes into it
answer file || answer file <prompt> [of type <type>] || Asks for a file to open (its path into it)
answer folder || answer folder <prompt> || Asks for a folder (its path into it)
answer program || answer program <prompt> [of type <type>] || Picks a program to link to: HyperZebra has none, so it is empty and the result is Cancel
ask || ask <question> [with <default>] || Text entry dialog: the answer into it, Cancel into the result
ask password || ask password <question> || Asks for a hidden entry
ask file || ask file <prompt> [with <defaultName>] || Asks for a file name to save to
beep || beep [<n>] || Beeps
flash || flash [<n>] || Flashes the screen
go || go [to] card <n> || Goes to a card by number, name or id
go next || go [to] next|prev|first|last|any [marked] [card] || Goes to the next, previous, first, last or any card
go stack || go [to] stack <name> [in a new window] || Goes to a stack
go card of stack || go [to] card <n> of stack <name> || Goes to a card of another stack
go back || go back|forth|recent [card] || Goes back or forth in the history
push || push [this|recent] card || Remembers a card
pop || pop card [into|after|before <container>] || Returns to the pushed card (or puts its name)
find || find [whole|string|words|chars|normal] <text> [in <field>] || Finds text and goes to its card
sort cards || sort [marked] cards [ascending|descending] [text|numeric|dateTime|international] by <expr> || Sorts cards
sort lines || sort lines|items of <container> [ascending|descending] [text|numeric|dateTime] [by <expr>] || Sorts lines or items (each is the line in the by expression) || chunk
mark || mark <card>  mark all cards  mark cards where <condition> || Marks cards
mark by finding || mark cards by finding <text> [in <field>] || Marks the cards where the text is found
unmark || unmark <card>  unmark all cards  unmark cards where <condition> || Unmarks cards
show || show <object> [at <point>] || Shows an object || obj
show menuBar || show menuBar|msg|titleBar|groups || Shows the menu bar, message box, …
show cards || show [all|marked|<n>] cards || Flips through cards
hide || hide <object>|menuBar|msg|titleBar|groups || Hides || obj
show picture || hide|show [card|background] picture [of <card>] || Hides or shows the card or background picture
lock screen || lock screen || Freezes the screen
unlock screen || unlock screen [with visual [effect] <effect>] || Updates the screen again (optionally with an effect)
lock messages || lock messages || Stops the open/close messages
unlock messages || unlock messages || Sends the messages again
lock recent || lock|unlock recent || Stops or resumes recording recent cards
visual || visual [effect] <effect> [very] [slow|fast] [to black|white|gray|inverse|card] || Visual effect for the next card change
wait || wait [for] <n> [ticks|seconds|milliseconds] || Waits (ticks without a unit; milliseconds may be fractional)
wait until || wait until|while <condition> || Waits for a condition (checked about every millisecond)
send || send "<message>" [to <object>] || Sends a message || ctrl
send program || send <expr> to program <app>  send <expr> to this program || Only this program works (the message goes to the current card); another program: the result is "No such program"
do || do <expr> || Runs text as a script || ctrl
doMenu || doMenu <item> [, <menu>] || Runs a menu item || prop.menu
click || click at <point> [with shiftKey|optionKey|commandKey] || Clicks
drag || drag from <point> to <point> [with shiftKey|optionKey|commandKey] || Drags
type || type <text> [with commandKey] || Types
choose || choose browse|button|field|brush|pencil|eraser|bucket|spray|line|rect|oval|curve|poly|lasso|select|text tool  choose round rect tool  choose reg poly tool || Chooses a tool
choose tool || choose tool <n> || Chooses a tool by number
*choose shape || choose shape tool || Chooses the Shape tool || prop.shape
*choose paint tool || choose magicWand|eyedropper|smudge|stamp tool || HyperZebra's paint tools: magic wand, eyedropper, smudge, clone stamp || prop.paint
select || select <button>|<field> || Selects an object
select text || select <chunk> of <field> || Selects text in a field || chunk
select after || select before|after text of <field> || Puts the insertion point
select empty || select empty || Deselects
*select shape || select shape <n> || Selects a shape || prop.shape
delete || delete <chunk> of <container> || Deletes part of a text || chunk
delete object || delete <button>|<field>|<card> || Deletes an object or card
create menu || create menu <name> || Creates a menu || prop.menu
put menu || put <items> into|after|before menu <menu> [with menuMessages <messages>] || Adds items to a menu || prop.menu
delete menu || delete menu <menu> || Deletes a menu || prop.menu
delete menuItem || delete menuItem <n> of menu <menu> || Deletes a menu item || prop.menu
reset menuBar || reset menuBar || Restores the standard menu bar || prop.menu
enable || enable|disable <button> || Enables or disables (grays out) a button
enable menuItem || enable|disable menuItem <n> of menu <menu> || Enables or disables a menu item || prop.menu
enable menu || enable|disable menu <menu> || Enables or disables a whole menu || prop.menu
create stack || create stack <name> [with background <bg>] [in a new window] || Creates a stack
save || save [this] stack as <file> || Saves the stack as a new file
edit || edit [the] script of <object> || Opens a script in the editor
start using || start using stack <name> || Adds a stack to the message path
stop using || stop using stack <name> || Removes a stack from the message path
open file || open file <file> || Opens a file for reading and writing
read || read from file <file> [at <start>] until <char>|eof || Reads a file up to a character (into it)
read for || read from file <file> [at <start>] for <n> || Reads n characters from a file (into it)
write || write <expr> to file <file> [at <start>|eof|end] || Writes to a file
close file || close file <file> || Closes a file
open || open <application> || Opens an application
open with || open <document> with <application> || Opens a document with an application
open URL || open URL <url> || Opens a page in the web browser
open movie || open movie <file> [, <style>, <loc>, <visible>] || Opens a movie window || prop.window
play movie || play|pause|rewind movie <n> || Plays, pauses or rewinds a movie || prop.window
open picture || open picture <file> || Opens a picture window (PICT, PNG, JPEG, GIF) || prop.window
close window || close window <name> || Closes a window || prop.window
close movie || close movie|picture <n> || Closes a movie or picture window || prop.window
palette || palette <name> [, <point>] || Opens a palette (PLTE resource) || prop.window
play || play <sound> [tempo <n>] [<notes>] || Plays a sound (notes make a tune)
play stop || play stop || Stops the sound
speak || speak <text> [with [voice] <voice>] || Says the text with the computer's speech engine (empty text stops it)
print card || print card  print all|marked cards  print <n> cards || Prints cards
open printing || open printing [with dialog] ¶ close printing || Prints several cards as one job
reset paint || reset paint || Resets the paint settings || prop.paint
reset printing || reset printing || Resets the print settings
convert || convert <container> [from <format>] to seconds|dateItems|date|time || Converts dates and times (long date… too)
import paint || import paint from file <file> || Imports a picture file || prop.paint
export paint || export paint to file <file> || Exports the paint picture (PNG, JPEG) || prop.paint
*export card || export card to file <file> || Saves the card as it looks (with shapes) as PNG
*feather || feather|grow|shrink [the] selection [by] <pixels> || Feathers, grows or shrinks the paint selection || prop.paint
request || request <expr> from program <app> || Only this program works (the expression's value into it); another program: it is empty and the result is "No such program"
request appleEvent || request appleEvent class|id|sender|data || There is never a current Apple event: it is empty and the result is "No current Apple event"
reply || reply <expr> || There is no Apple event to reply to: the result is "No current Apple event"
dial || dial <number> || Plays a phone number's tones
debug checkpoint || debug checkpoint || Enters the debugger here || ctrl
help || help || Opens the help
copy template || copy template <name> to stack <stack> || Copies a report template (HyperZebra reports an error in the result)
arrowKey || arrowKey left|right|up|down || Acts as an arrow key || msg
tabKey || tabKey|returnKey|enterKey || Acts as Tab, Return or Enter || msg
## func
the date || the [long|short|abbrev] date || Today's date
the time || the [long] time || The current time
the seconds || the seconds || Seconds since 1 January 1904
the ticks || the ticks || Ticks (1/60 s) since start-up
*the milliseconds || the milliseconds || Milliseconds since start-up (3 decimals); a finer clock than the ticks
random || random(<n>) || A random number from 1 to n
round || round(<n>) || Rounds to an integer
trunc || trunc(<n>) || Drops the fraction
abs || abs(<n>) || Absolute value
sqrt || sqrt(<n>) || Square root
max || max(<list>) || Largest value (comma-separated)
min || min(<list>) || Smallest value
sum || sum(<list>) || Sum
average || average(<list>) || Average
sin || sin(<n>)  cos(<n>)  tan(<n>)  atan(<n>) || Trigonometry (radians)
exp || exp(<n>)  exp1(<n>)  exp2(<n>) || Exponentials
ln || ln(<n>)  ln1(<n>)  log2(<n>) || Logarithms
annuity || annuity(<rate>, <periods>) || Annuity
compound || compound(<rate>, <periods>) || Compound interest
length || the length of <expr> || Number of characters || chunk
offset || offset(<find>, <text>) || Position of a text (0 if none) || chunk
charToNum || charToNum(<char>) || Character code
numToChar || numToChar(<n>) || Character of a code
value || value(<expr>) || Evaluates text as an expression
param || param(<n>) || The nth parameter (0 = message name) || ctrl
paramCount || the paramCount || Number of parameters || ctrl
params || the params || The whole message with its parameters || ctrl
the result || the result || Result or error of the last command || ctrl
the target || the target || The object the message was first sent to || obj
the mouse || the mouse || The mouse button (up or down)
mouseClick || the mouseClick || Whether the mouse was clicked
selectedButton || the selectedButton of [card|bg] family <n> || The highlighted button of a radio family
mouseLoc || the mouseLoc || The mouse location (h,v)
mouseH || the mouseH|mouseV || The mouse's horizontal or vertical position
clickLoc || the clickLoc || Where the last click was
clickH || the clickH|clickV || The last click's horizontal or vertical position
clickText || the clickText || The clicked word (locked field) || chunk
clickLine || the clickLine|clickChunk || The clicked line or chunk || chunk
shiftKey || the shiftKey|optionKey|commandKey|controlKey || A modifier key (up or down); commandKey is also cmdKey
foundText || the foundText|foundLine|foundChunk|foundField || What find found and where || chunk
selectedText || the selectedText [of <field>] || The selected text || prop.field, chunk
selectedLine || the selectedLine|selectedChunk|selectedField [of <field>] || The selected line, chunk or field || prop.field, chunk
selection || the selection || The selected text (a container) || chunk
selectedLoc || the selectedLoc || Location of the selection
the number || the number of <things> || How many (chunks, objects) || chunk, obj
the tool || the tool || The current tool
the sound || the sound || The sound playing (done when none)
the speech || the speech || "speaking" while speak is still talking, otherwise "done"
the voices || the voices || The speech engine's voices, one per line (empty when there is none)
screenRect || the screenRect || The screen rectangle
the stacks || the stacks || Paths of the open stacks
the destination || the destination || Path of the stack being gone to (during closeStack…); otherwise this stack
the windows || the windows || The windows
the menus || the menus || The menus || prop.menu
programs || the programs || The running programs
the clipboard || the clipboard || The text on the clipboard (empty when it holds no text)
FontExists || FontExists(<font> [, <size>]) || Whether a font is installed (the Home stack's XFCN)
systemVersion || the systemVersion || The system version
diskSpace || the diskSpace [of disk <name>] || Free disk space
heapSpace || the heapSpace|stackSpace || Free memory (for compatibility)
*systemName || the systemName || The operating system's name
*javaVersion || the javaVersion|javaVMVersion || The Java version
*matchText || matchText(<text>, <pattern> [, <var>, …]) || Whether a Java regular expression matches ((?i) ignores case); each group's text into a variable || chunk
*matchChunk || matchChunk(<text>, <pattern> [, <start>, <end>, …]) || Whether a Java regular expression matches; the first and last character of each group (of the whole match without groups) into variables, counting from 1 || chunk
*replaceText || replaceText(<text>, <pattern>, <replacement>) || Replaces every match of a Java regular expression; in the replacement \\1 or $1 is a group, ${name} a named group, \\\\ a backslash || chunk
*localizedText || localizedText(<text> [, <value1>, …]) || The text in HyperZebra's language from the stack's Languages/<tag>.po (itself when there is no translation); {1}, {2}… are replaced by the values || chunk
*JSONValue || JSONValue(<json>, <key>|<path>) || Reads a JSON value: a key, or a path "a.b.2" (arrays count from 1; a real key with dots comes first); missing or null gives empty || coll
## ctrl
on || on <message> [<param>, …] ¶ ¶ end <message> || Message handler
function || function <name> [<param>, …] ¶ return <value> ¶ end <name> || Function handler (called as name(…))
if || if <condition> then <statement> [else <statement>] || One-line if
if block || if <condition> then ¶ ¶ else ¶ ¶ end if || Multi-line if / else
else if || else if <condition> then || Another condition
repeat || repeat [forever] ¶ ¶ end repeat || Loops forever (leave with exit repeat)
repeat times || repeat [for] <n> [times] ¶ ¶ end repeat || Repeats n times
repeat with || repeat with <var> = <start> to <end> ¶ ¶ end repeat || Counts up with a variable
repeat down || repeat with <var> = <start> down to <end> ¶ ¶ end repeat || Counts down with a variable
repeat while || repeat while <condition> ¶ ¶ end repeat || Repeats while a condition is true
repeat until || repeat until <condition> ¶ ¶ end repeat || Repeats until a condition is true
*repeat for each || repeat for each item|key in <collection> ¶ ¶ end repeat || For each array value (it) or dictionary key (key) || coll
next repeat || next repeat || Goes to the next iteration
exit repeat || exit repeat || Leaves the loop
exit || exit <handler> || Leaves the handler
exit to HyperCard || exit to HyperCard || Stops all running handlers
pass || pass <message> || Passes the message on
return || return <value> || Returns a value (a command's goes to the result)
global || global <var> [, <var>] || Declares global variables
it || it || Variable that get, ask, answer, read… fill
comment || -- <comment> || Comment to the end of the line
continuation || ¬ || Continues on the next line (Option-Return)
## chunk
char || char <n> of <expr> || The nth character
word || word <n> of <expr> || The nth word (space-separated)
item || item <n> of <expr> || The nth item (itemDelimiter, comma by default)
line || line <n> of <expr> || The nth line
range || char|word|item|line <n> to <m> of <expr> || A range
ordinal || first|second|third|last|middle|any char|word|item|line of <expr> || By ordinal (up to tenth)
nested || char <n> of word <n> of line <n> of <expr> || Nested chunks
number of chunks || the number of chars|words|items|lines in <expr> || How many chunks
put chunk || put <expr> into|before|after <chunk> of <container> || Replaces or extends a chunk
chunk of field || line <n> of [card|bg] field <n> || A chunk of a field
itemDelimiter || set the itemDelimiter to <char> || The item delimiter || prop.global
## op
+ || <a> + <b>  <a> - <b>  <a> * <b>  <a> / <b> || Arithmetic
div || <a> div <b>  <a> mod <b> || Integer quotient and remainder
^ || <a> ^ <b> || Power
& || <a> & <b>  <a> && <b> || Joins text (&& adds a space)
= || <a> = <b>  <a> is <b>  <a> <> <b>  <a> is not <b> || Equal, not equal (≠ too)
< || <a> < <b>  <a> > <b>  <a> <= <b>  <a> >= <b> || Comparison (≤ ≥ too)
and || <a> and <b>  <a> or <b>  not <a> || Logic
contains || <text> contains <part> || Whether a text contains another
is in || <part> is [not] in <text> || Whether a text is in another
is within || <point> is [not] within <rect> || Whether a point is inside a rectangle
is a || <value> is [not] a|an number|integer|point|rect|date|logical || Checks a value's type
there is a || there is [not] a|an <object> || Whether an object or file exists || obj
( || (<expr>) || Groups an expression
## const
empty || empty || The empty string
true || true|false || True and false
return || return|lineFeed|formFeed || Return (CR), linefeed, form feed
tab || tab|space|quote|comma|colon || Tab, space, quote, comma, colon
pi || pi || Pi
zero || zero|one|two|three|four|five|six|seven|eight|nine|ten || Number names
up || up|down || Mouse and key states
effects || dissolve|cut|plain|checkerboard  venetian blinds  barn door open|close  iris open|close  zoom open|close|in|out || Visual effect names
effects dir || wipe|scroll left|right|up|down  shrink to top|bottom|center  stretch from top|bottom|center || Visual effects with a direction
*effects more || fade  mosaic  blur  block dissolve  radial wipe  circle open|close  cross zoom  page turn [left|right] || HyperZebra's extra visual effects (fade, mosaic, blur, page turn …)
## obj
button || [card] button <n> || A card button (btn) by number, "name" or id <n>
bg button || background button <n> || A background button (bg btn)
field || field <n> || A background field (fld; bg may be omitted)
card field || card field <n> || A card field (cd fld)
*shape || [card|background] shape <n> || A shape (number, name or id) || prop.shape
*panel || [card|background] panel <n> || A panel (number, "name" or id <n>) || prop.panel
part || part <n> || Buttons and fields numbered together
card || card <n> || A card (cd) by number, name or id
this card || this|next|prev|first|last|any card || Cards by position
marked card || marked card <n> || A marked card
background || background <n> || A background (bg, bkgnd)
stack || stack "<name>" || A stack (this stack)
of || button <n> of card <n> [of stack "<name>"] || Parts of other cards or stacks
me || me || The object that owns this script
message box || the message box || The message box (msg)
window || window "<name>"  card window || A window || prop.window
menu || menu "<name>"  menuItem <n> of menu "<name>" || A menu or menu item || prop.menu
movie || movie <n>|"<name>" || A movie window || prop.window
number of objects || the number of [card|bg] buttons|fields|shapes|cards|backgrounds || How many objects
## msg
mouseUp || on mouseUp || When the mouse button is released
mouseDown || on mouseDown || When the mouse button is pressed
mouseStillDown || on mouseStillDown || While the button is held
mouseDoubleClick || on mouseDoubleClick || Double-click
mouseEnter || on mouseEnter|mouseWithin|mouseLeave || Pointer enters, stays or leaves
openCard || on openCard|closeCard || A card opens or closes
openBackground || on openBackground|closeBackground || A background opens or closes
openStack || on openStack|closeStack || A stack opens or closes
suspendStack || on suspendStack|resumeStack || Leaving a stack that stays open in its window, and coming back to it
startUp || on startUp || At start-up
idle || on idle || Repeatedly while idle
closeField || on closeField || Editing of a field ended with changes || prop.field
keyDown || on keyDown <char> || A key is pressed
returnKey || on returnKey|enterKey|tabKey || The Return, Enter or Tab key
returnInField || on returnInField|enterInField || Return or Enter in a field || prop.field
arrowKey msg || on arrowKey <direction> || An arrow key
functionKey || on functionKey <n> || A function key
commandKeyDown || on commandKeyDown <char> || A Command-key combination
controlKey || on controlKey <n> || A Control-key combination
doMenu msg || on doMenu <item>, <menu> || A menu item is chosen || prop.menu
help msg || on help || Help is chosen
errorDialog || on errorDialog <message> || A script error while lockErrorDialogs is true (sent instead of the error dialog)
newCard || on newCard|deleteCard || A card is created or deleted
newButton || on newButton|deleteButton|newField|deleteField || A button or field is created or deleted
newBackground || on newBackground|deleteBackground|newStack || A background or stack is created
openPalette || on openPalette|closePalette || A palette opens or closes || prop.window
openMovie || on openMovie|closeMovie || A movie opens or closes || prop.window
mouseDownInMovie || on mouseDownInMovie|mouseUpInMovie || A movie is clicked || prop.window
mouseUpInPicture || on mouseUpInPicture || A picture window is clicked || prop.window
*sortTable || on sortTable <column>, <direction> || A table field was sorted by clicking a heading (column number from 1; ascending or descending) || prop.field
## prop.button
name || the [long|short|abbrev] name of <object> || Name (long includes the owner) || prop.field, prop.shape, prop.card, prop.bg, prop.stack, prop.panel, prop.window
id || the id of <object> || ID (never changes) || prop.field, prop.shape, prop.card, prop.bg, prop.panel
number || the number of <object> || Its number (order) || prop.field, prop.shape, prop.card, prop.bg, prop.panel
script || the script of <object> || The script's text || prop.field, prop.shape, prop.card, prop.bg, prop.stack, prop.panel
visible || the visible of <object> || Whether it is shown || prop.field, prop.shape, prop.panel, prop.window
rect || the rect of <object> || Rectangle (left,top,right,bottom); also rectangle || prop.field, prop.shape, prop.panel, prop.window, prop.card
loc || the loc of <object> || Centre (h,v); also location || prop.field, prop.shape, prop.panel, prop.window
left || the left|top|right|bottom of <object> || An edge's position || prop.field, prop.shape, prop.window
width || the width|height of <object> || Width or height || prop.field, prop.shape, prop.window, prop.card
topLeft || the topLeft|bottomRight of <object> || Top-left or bottom-right point (also botRight) || prop.field, prop.shape, prop.window
style || the style of <button> || Style: transparent, opaque, rectangle, roundRect, shadow, checkBox, radioButton, standard, default, oval, popup
hilite || the hilite of <button> || Whether it is highlighted (checked); also hilight, highlight
autoHilite || the autoHilite of <button> || Highlights on click
sharedHilite || the sharedHilite of <button> || Shares the highlight on all cards (bg button)
family || the family of <button> || Radio group number (0–15)
icon || the icon of <button> || Icon ID or name
showName || the showName of <button> || Whether the name is shown
titleWidth || the titleWidth of <button> || Pop-up title width
contents || put <text> into <button> || Button contents (pop-up items…)
selectedLine btn || the selectedLine|selectedText of <button> || The chosen pop-up item
enabled || the enabled of <button> || Whether it is enabled || prop.menu
textFont || the textFont|textSize|textStyle of <object> || Text font, size and style || prop.field, prop.shape
textAlign || the textAlign of <object> || Alignment: left, center, right || prop.field
partNumber || the partNumber of <object> || Number among all parts || prop.field
owner || the owner of <object> || Owner (card or background) || prop.field, prop.card
*blendingMode || the blendingMode of <button> || Blending: copy or blend (translucent)
*blendingLevel || the blendingLevel of <button> || Translucency (0–100)
*color || the color|backColor of <object> || Text or back colour (r,g,b, 0–65535 each) || prop.field
*hiliteColor || the hiliteColor of <button> || Fill colour while hilited (r,g,b; empty inverts)
*panel prop || the panel of <object> || The panel it is in (empty takes it out) || prop.field, prop.shape, prop.panel
## prop.field
lockText || the lockText of <field> || Not editable (clicks give clickText)
dontWrap || the dontWrap of <field> || No word wrap
autoTab || the autoTab of <field> || Return on the last line goes to the next field
autoSelect || the autoSelect of <field> || A list that selects whole lines
multipleLines || the multipleLines of <field> || A list allowing several lines
wideMargins || the wideMargins of <field> || Wide margins
fixedLineHeight || the fixedLineHeight of <field> || Fixed line height
showLines || the showLines of <field> || Shows ruled lines
textHeight || the textHeight of <field> || Line height
field style || the style of <field> || Style: transparent, opaque, rectangle, shadow, scrolling, table, outline
scroll || the scroll of <field> || Scroll position (pixels)
sharedText || the sharedText of <field> || Same text on all cards (bg field)
dontSearch || the dontSearch of <object> || Skipped by find || prop.card, prop.bg
chunk style || set the textStyle of <chunk> of <field> to bold|italic|underline|plain|… || Styles part of the text || chunk
*text || the text of <object> || Text (field, shape, panel) || prop.shape, prop.panel
*tableStyle || the tableStyle of <field> || Shows the text as a table, like the style table: each line a row, each tab-separated item a cell (typed into while unlocked)
*columnNames || the columnNames of <field> || Table column headings, separated by tabs, returns or commas
*columnWidths || the columnWidths of <field> || Table column widths in pixels, "120,80,200"; an empty item shares what is left
*sortable || the sortable of <field> || Sorts by clicking a heading
*gridLines || the gridLines of <field> || Table grid lines
*selectedColumn || the selectedColumn of <field> || The selected table column (number from 1; empty for none)
*selectedLines || the selectedLines of <field> || The selected table rows, "2,5,7"; set also "3", "2 to 4" or empty (selectedLine too)
*outlineStyle || the outlineStyle of <field> || Shows the text as an outline, like the style outline: JSON (read-only) or lines indented with tabs (typed into while unlocked)
*outlineLevel || the outlineLevel of <field> || Levels open at first (default 1; 0 closes all)
*selectedPath || the selectedPath of <field> || Path of the selected JSON node, "weather.days.2.name" (arrays count from 1), as JSONValue reads it || coll
## prop.shape
*fill || the fill of <shape> || Fill colour "#AARRGGBB"; set also #RGB, #RRGGBB, black, white, red, green, blue, yellow, gray or transparent; none = no fill (removes a gradient)
*stroke || the stroke of <shape> || Stroke colour (as fill; none = no stroke)
*strokeWidth || the strokeWidth of <shape> || Stroke width
*opacity || the opacity of <shape> || Opacity (0–100)
*rotation || the rotation of <shape> || Rotation (degrees)
*path || the path of <shape> || Outline (SVG path)
*kind || the kind of <shape> || Kind: rectangle, line, ellipse, regularPolygon, polygon, freehand, bezier or text (cannot be set to text)
*sides || the sides of <shape> || Sides of a regular polygon (3–100; also polySides)
*outlines || the outlines of <shape> || Outlines, a line each: width,colour (the outline… properties change the first, adding it when there is none)
*outlineWidth || the outlineWidth of <shape> || Width of the first outline
*outlineColor || the outlineColor of <shape> || Colour of the first outline
*shadows || the shadows of <shape> || Shadows, a line each: x,y,width,blur,colour (the shadow… properties change the first, adding it when there is none)
*shadowOffset || the shadowOffset of <shape> || Offset of the first shadow (x,y)
*shadowWidth || the shadowWidth of <shape> || Width of the first shadow
*shadowBlur || the shadowBlur of <shape> || Blur of the first shadow
*shadowColor || the shadowColor of <shape> || Colour of the first shadow
*gradientType || the gradientType of <shape> || Gradient (linear, radial, none)
*gradientStart || the gradientStart of <shape> || Gradient start colour
*gradientEnd || the gradientEnd of <shape> || Gradient end colour
*gradientAngle || the gradientAngle of <shape> || Linear gradient angle
*gradientPoints || the gradientPoints of <shape> || Gradient points (x1,y1,x2,y2 from 0 to 1)
*texture || the texture of <shape> || Fill image (Base64 PNG)
*stretchTexture || the stretchTexture of <shape> || Whether the image is stretched
*shape textStyle || the textStyle of <shape> || A text shape's style: bold or plain only
*filter || the filter of <shape> || Filter as SVG <filter> markup (get gives it back); empty or none removes it; unsupported primitives are dropped
*filter example || set the filter of <shape> to "<filter><feGaussianBlur stdDeviation='4'/></filter>" || Sets a filter (attribute values in single quotes inside the HyperTalk string)
*filter inputs || in='SourceGraphic|SourceAlpha|<name>'  in2='<name>'  result='<name>' || A primitive takes the previous one's output; in and in2 name SourceGraphic, SourceAlpha or an earlier result
*filter steps || feGaussianBlur  feDropShadow  feColorMatrix  feComponentTransfer  feMorphology || What Shape Info's filter steps write: Blur = feGaussianBlur; Drop shadow, Glow = feDropShadow; Saturation, Grayscale, Sepia, Hue = feColorMatrix; Brightness, Contrast, Invert, Opacity = feComponentTransfer; Thicken, Thin = feMorphology
*feGaussianBlur || <feGaussianBlur stdDeviation='<n>'/> || SVG filter: blur (one number, or x and y; up to 32)
*feDropShadow || <feDropShadow dx='<n>' dy='<n>' stdDeviation='<n>' flood-color='<colour>' flood-opacity='<n>'/> || SVG filter: drop shadow (defaults 2, 2, 2, black, 1)
*feOffset || <feOffset dx='<n>' dy='<n>'/> || SVG filter: moves the image
*feFlood || <feFlood flood-color='<colour>' flood-opacity='<n>'/> || SVG filter: fills with a colour (opacity 0–1)
*feComposite || <feComposite in2='<name>' operator='over|in|out|atop|xor'/>  <feComposite in2='<name>' operator='arithmetic' k1='<n>' k2='<n>' k3='<n>' k4='<n>'/> || SVG filter: combines in with in2 (arithmetic: k1·in·in2 + k2·in + k3·in2 + k4)
*feMerge || <feMerge><feMergeNode in='<name>'/><feMergeNode in='<name>'/></feMerge> || SVG filter: stacks its feMergeNode inputs (the last on top)
*feColorMatrix || <feColorMatrix type='saturate|hueRotate' values='<n>'/>  <feColorMatrix type='matrix' values='<matrix>'/>  <feColorMatrix type='luminanceToAlpha'/> || SVG filter: colour matrix (saturate: 0 grey, 1 unchanged; hueRotate: degrees; matrix: 20 numbers)
*feComponentTransfer || <feComponentTransfer><feFuncR|feFuncG|feFuncB|feFuncA type='linear' slope='<n>' intercept='<n>'/></feComponentTransfer>  type='gamma' amplitude='<n>' exponent='<n>' offset='<n>'  type='table|discrete' tableValues='<list>' || SVG filter: changes each channel (feFuncR/G/B/A; type identity, linear, gamma, table or discrete)
*feMorphology || <feMorphology operator='erode|dilate' radius='<n>'/> || SVG filter: thins (erode) or thickens (dilate)
*feBlend || <feBlend in2='<name>' mode='normal|multiply|screen|darken|lighten'/> || SVG filter: blends in over in2
## prop.card
marked || the marked of <card> || Whether it is marked
cantDelete || the cantDelete of <card>|<background>|<stack> || Cannot be deleted || prop.bg, prop.stack
showPict || the showPict of <card>|<background> || Whether the picture is shown || prop.bg
## prop.stack
cantModify || the cantModify of <stack> || Cannot be changed
cantAbort || the cantAbort of <stack> || Command-period cannot stop scripts
cantPeek || the cantPeek of <stack> || Cannot peek at buttons and fields
size || the size|freeSize of <stack> || File size and free space
stack version || the version of <stack> || HyperCard version of the stack
reportTemplates || the reportTemplates of <stack> || The report templates
scriptingLanguage || the scriptingLanguage of <object> || Language of the script || prop.global
## prop.panel
*panel data || the data "<key>" of <panel> || A panel value (by key) || coll
*dataKeys || the dataKeys of <panel> || The value keys (one per line) || coll
*data || the data of <panel> || All values (a dictionary) || coll
*hide panel || hide|show panel <panel> || Hides or shows the panel with its members || cmd
*create panel || create panel ["<name>"] || Makes a panel (it gets newPanel) || cmd
*delete panel || delete panel <panel> || Deletes a panel; its members stay || cmd
*put panel || put <text> into panel "<name>" || Sets the panel's text || cmd
## prop.window
zoomed || the zoomed of <window> || Whether it is zoomed
currTime || the currTime of <movie> || Playback position
duration || the duration|timeScale of <movie> || Length and time scale
rate || the rate of <movie> || Playback rate (0 = stopped)
loop || the loop|loopIsPalindrome of <movie> || Loops
soundVolume || the soundVolume of <movie> || Volume
startTime || the startTime|endTime of <movie> || Playback range
movie prop || the movie of <movie> || The movie file
movie window || the windowStyle|timeFormat|scale|audioLevel of <movie> || Movie window style, time format, scale, audio level
palette props || the hilitedButton|commands|buttonCount of window <palette> || A palette's highlighted button, commands and button count
globalLoc || the globalLoc|globalRect of <window> || A picture window's location or rectangle in screen coordinates
## prop.menu
checkMark || the checkMark of menuItem <n> of menu <menu> || Check mark
markChar || the markChar of menuItem <n> of menu <menu> || Mark character
commandChar || the commandChar of menuItem <n> of menu <menu> || Keyboard shortcut (also cmdChar)
menuMessage || the menuMessage of menuItem <n> of menu <menu> || Message sent when chosen (also menuMsg)
## prop.paint
brush || the brush || Brush shape (1–32)
pattern || the pattern || Fill pattern (1–40)
*brushPattern || the brushPattern|penPattern|fillPattern || Same as pattern (other names)
*imageData || the imageData of pattern <n> || Pixel data of a (colour) pattern
lineSize || the lineSize || Line width (1,2,3,4,6,8)
filled || the filled || Fills shapes
centered || the centered || Draws from the centre
multiple || the multiple|multiSpace || Draws repeated shapes and their spacing (1–9)
grid || the grid || Snaps to the grid
polySides || the polySides || Regular polygon sides (3–50)
powerKeys || the powerKeys || Paint shortcut keys
*brushColor || the brushColor|penColor|brushBackColor|penBackColor || Paint colours as r,g,b (0–65535 each); the back colour can be none
*brushSize || the brushSize|brushHardness|brushOpacity|brushSpacing || Brush size (1–256), hardness (0–100), opacity (1–100) and spacing (1–200)
*brushStabilizer || the brushStabilizer|brushStabilization || Stroke smoothing (0–98)
*zoom || the zoom|zoomOrigin || Zoom (25–3200 %) and the card point at the top-left (x,y)
*gradient || the gradient|gradientAngle || Paint fill (off, linear, radial or texture) and the gradient's angle in degrees
*fillTexture || the fillTexture|fillTolerance || Texture fill (Wood, Marble, Clouds, Sand, Granite, Brick, Canvas, Water, an image file or none) and bucket tolerance (0–100)
*gridSize || the gridSize || Drawing grid size (1–256)
*paintLayer || the paintLayer|paintLayerCount || Current layer (number from 1 = the card or background paint; set also by name) and layer count
*layerOpacity || the layerName|layerOpacity|layerBlend|layerVisible|layerAlphaLock|layerClip || The current layer's name, opacity (0–100), blend mode, visibility, alpha lock and clipping to the layer below
*layerBlend || the layerBlend || Blend modes: Normal, Blend, Add, Subtract, Multiply, Screen, Darken, Lighten, Difference, Hue, Color, Saturation, Luminosity
*symmetry || the symmetry|symmetryAxes|symmetryCenter || Symmetry: none, vertical, horizontal, quadrant or radial [n]; axes 2–16; centre x,y
*smudgeStrength || the smudgeStrength|stampSource || Smudge strength (1–100) and the clone stamp's source x,y (empty clears it)
## prop.global
userLevel || the userLevel || User level (1–5)
userModify || the userModify || Temporary changes in locked stacks
lockScreen || the lockScreen || Whether the screen is locked
lockMessages || the lockMessages || Whether messages are locked
lockRecent || the lockRecent || Recent cards are not recorded
lockErrorDialogs || the lockErrorDialogs || No error dialogs (errorDialog is sent)
cursor || set the cursor to <name>|<id> || Cursor (watch, busy, hand…)
numberFormat || the numberFormat || Number format ("0.00"…)
blindTyping || the blindTyping || Typing into a hidden message box
textArrows || the textArrows || Arrow keys move in text
dragSpeed || the dragSpeed || Speed of drag
editBkgnd || the editBkgnd || Editing the background
language || the language || Script language
traceDelay || the traceDelay || Trace delay
messageWatcher || the messageWatcher|variableWatcher || Watcher windows
scriptEditor || the scriptEditor|debugger || Script editor and debugger names
scriptTextFont || the scriptTextFont|scriptTextSize || Script text font and size
stacksInUse || the stacksInUse || Stacks in use
suspended || the suspended || Whether HyperCard is in the background
version || the [long] version [of <software>] || Version of HyperCard, Mac OS, QuickTime…
address || the address || HyperCard's AppleEvent address
environment || the environment || development or player
longWindowTitles || the longWindowTitles || Full paths as window titles
soundChannel || the soundChannel || The channel play uses
printMargins || the printMargins|printTextFont|printTextSize|printTextStyle|printTextAlign|printTextHeight || Print margins and text
dialingTime || the dialingTime|dialingVolume || Dial settings
*systemLang || the systemLang || The system's language
## coll
*array || put `[1, 2, 3`] into <var> || Creates an array (counts from 1)
*dictionary || put {name: "Taro", age: 20} into <var> || Creates a dictionary
*index || <var>`[<n>`] || The nth array element
*key || <var>`["<key>"`] || A dictionary value
*of dictionary || the <key> of <var> || A dictionary value (as a property)
*put after array || put <value> after <array> || Appends to an array
*delete key || delete key "<key>" of <var> || Removes a dictionary key || cmd
*delete item || delete item <n> of <array> || Removes an array element
*get URL || get URL <url> || Fetches a web page's text (into it) || cmd
*put URL || put URL <url> into <container> || Fetches web text into a container || cmd
""";
}
