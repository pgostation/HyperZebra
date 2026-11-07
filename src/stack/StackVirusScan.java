import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the HyperCard viruses known by their handlers in a stack that opens, and takes them out if the user says so. There is
 * one: MerryXmas, in stacks all over the archives of the 1990s, marks its entry handlers ("on idle --merryxmas") and adds the
 * handler merryxmas and the function lastlines to the stack's script. Only live lines count: the vaccines of the time keep the
 * same lines commented out as a blocker (Spell-It) or in fields. A virus nobody listed here meets the {@link StackGuard}.
 */
final class StackVirusScan {
	private StackVirusScan(){}

	private static final Pattern ENTRY = Pattern.compile("(?i)^\\s*on\\s+(idle|openbackground|closebackground)\\s*--\\s*merryxmas\\s*$");
	private static final Pattern BODY = Pattern.compile("(?i)^\\s*(?:on\\s+(merryxmas)\\s+key|function\\s+(lastlines)\\s+afterline\\s*,\\s*stuff)\\s*$");
	/** Tests answer here: true removes the virus. */
	static volatile Predicate<OStack> decider;
	/** The stacks the user chose to keep as they are, for this session: they are not asked about again. */
	private static final Set<String> kept = Collections.synchronizedSet(new HashSet<String>());

	static boolean infected(List<String> script){
		if(script==null) return false;
		for(String line : script) if(ENTRY.matcher(line).matches()) return true;
		return false;
	}

	/** The script without the virus's handlers and the blank lines after them. */
	static ArrayList<String> cleaned(List<String> script){
		ArrayList<String> out = new ArrayList<String>();
		for(int i=0; i<script.size(); i++){
			int end = handlerEnd(script, i);
			if(end<0){ out.add(script.get(i)); continue; }
			i = end;
			while(i+1<script.size() && script.get(i+1).trim().isEmpty()) i++;
		}
		return out;
	}

	/** The line ending the virus's handler that starts at {@code line}, or -1 when none starts there. */
	private static int handlerEnd(List<String> script, int line){
		String name = null;
		Matcher entry = ENTRY.matcher(script.get(line)), body = BODY.matcher(script.get(line));
		if(entry.matches()) name = entry.group(1);
		else if(body.matches()) name = body.group(1)!=null ? body.group(1) : body.group(2);
		if(name==null) return -1;
		Pattern end = Pattern.compile("(?i)^\\s*end\\s+"+name+"\\b.*$");
		for(int i=line+1; i<script.size(); i++) if(end.matcher(script.get(i)).matches()) return i;
		return -1;
	}

	/** A stack whose handlers are about to run has been read. */
	static void stackOpened(OStack stack){
		if(stack==null || !infected(stack.scriptList)) return;
		String key = stack.path==null ? String.valueOf(stack.name) : stack.path;
		if(kept.contains(key)) return;
		if(!removes(stack)){ kept.add(key); return; }
		stack.setScript(String.join("\n", cleaned(stack.scriptList)));
		stack.userEdited = true; //a cantModify stack is saved too: what the user decided
		if(stack.saveXML==null && stack.file!=null) XMLwrite.withoutAutosave(stack).saveStackNow(); //a stack in use, Home: nothing autosaves it
	}

	private static boolean removes(OStack stack){
		Predicate<OStack> d = decider;
		if(d!=null) return d.test(stack);
		if(Sandbox.allowsEverything() || java.awt.GraphicsEnvironment.isHeadless()){
			System.err.println("[virus] MerryXmas in "+stack.path);
			return false;
		}
		return StackGuard.choose(International.t("Virus Found"),
				International.t("The stack \"{0}\" carries the MerryXmas virus, which copies itself into the Home stack and into every stack you open.", String.valueOf(stack.name)),
				International.t("Remove the Virus"), International.t("Open As Is"))==0;
	}
}
