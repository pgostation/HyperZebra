import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Keeps one stack's handlers out of another stack's code. HyperCard viruses (MerryXmas, in many stacks of the 1990s) write
 * themselves into Home's script, whose handlers then write into every stack opened; the {@link Sandbox} saw nothing of it, for
 * a script is no file: a stack read the other stack, or went there, and changed it in memory.
 * <p>
 * Installers do the same with the same commands (Window Tools, PowerMenus, HyperTalk QuickRef…), so the answer is the user's:
 * a running handler changing the code of a stack other than its own - script, protection, pasted parts - is asked about once
 * a session. A handler's own stack is free (self-modifying stacks are common), and so is what the user does: typing in the
 * message box and the script editor run no handler. The question goes by the handler that runs, not by the current stack: a
 * script that went to Home is still the stack it came from. What is only data (a field's text, the card picture) is not
 * asked about; Type It keeps the user's name in Home.
 */
final class StackGuard {
	private StackGuard(){}

	/** {@code the result} after a refusal. */
	static final String REFUSED = "Not allowed";
	/** The stack that queued the message box command the script thread is running (nothing runs a handler then). */
	static volatile OStack queuedBy;
	/** The stack of the handler running on each thread (the debugger's frames are for showing: a reset empties them). */
	private static final ThreadLocal<OStack> running = new ThreadLocal<OStack>();
	/** The same of the script thread, for the threads that work for its handlers: an external's VM. */
	private static volatile OStack scriptThreadRunning;
	private static final Set<String> PROTECTION = Set.of("cantmodify", "cantdelete", "cantabort", "cantpeek");
	private static final int HANDLERS_SHOWN = 8;

	/** A handler of {@code object} starts: what it gives back goes to {@link #leave} when it ends. */
	static OStack enter(OObject object){
		OStack caller = running.get(), stack = TTalk.stackOf(object);
		set(caller, stack);
		return caller;
	}

	static void leave(OStack caller){ set(running.get(), caller); }

	/** Nearly every call stays in its stack: only a read then, as this is a part of every handler call */
	private static void set(OStack now, OStack stack){
		if(stack==now) return;
		running.set(stack);
		if(Thread.currentThread()==TTalk.talk) scriptThreadRunning = stack;
	}

	/** The stack whose handler is running; null when none is, that is, the user acts. */
	static OStack runningStack(){
		OStack stack = running.get();
		if(stack==null && !(Thread.currentThread() instanceof TTalk)) stack = scriptThreadRunning;
		return stack!=null ? stack : queuedBy;
	}

	/** Whether {@code set the property of obj to value} may go on. */
	static boolean allowsSet(OObject obj, String property, String value){
		String p = property.toLowerCase(Locale.ROOT);
		boolean script = p.equals("script");
		if(!script && !PROTECTION.contains(p)) return true;
		OStack target = TTalk.stackOf(obj), handler = runningStack();
		if(target==null || handler==null || sameStack(handler, target)) return true;
		if(script){
			String before = obj.scriptList==null ? "" : String.join("\n", obj.scriptList);
			if(before.equals(value)) return true;
			String detail = changedHandlers(before, value);
			if(!(obj instanceof OStack)) detail = detail.isEmpty() ? obj.getShortName() : obj.getShortName()+"\n"+detail;
			return Sandbox.allowStackChange(nameOf(handler), Sandbox.CHANGE_SCRIPTS, nameOf(target), detail);
		}
		if(sameValue(obj, p, value)) return true; //MerryXmas clears cantModify first, also of a stack that does not have it
		return Sandbox.allowStackChange(nameOf(handler), Sandbox.CHANGE_PROTECTION, nameOf(target), property+" = "+value.trim());
	}

	/** Whether a script may paste a card or a part, with its scripts, into the current stack. */
	static boolean allowsPaste(){
		OStack target = PCARD.pc.stack, handler = runningStack();
		if(target==null || handler==null || sameStack(handler, target)) return true;
		return Sandbox.allowStackChange(nameOf(handler), Sandbox.PASTE_INTO, nameOf(target), null);
	}

	private static boolean sameValue(OObject obj, String property, String value){
		try{ return value.trim().equalsIgnoreCase(TUtil.getProperty(property, obj, obj, null)); }
		catch(xTalkException | RuntimeException e){ return false; }
	}

	private static boolean sameStack(OStack a, OStack b){
		if(a==b) return true;
		if(a.path==null || b.path==null) return false;
		return TTalk.stackIdentityPath(a.path).equals(TTalk.stackIdentityPath(b.path));
	}

	private static String nameOf(OStack stack){ return stack.file!=null ? Sandbox.stackRequester(stack.file) : String.valueOf(stack.name); }

	/** "Handlers added or changed: idle, merryxmas", or empty when {@code after} adds and changes none. */
	static String changedHandlers(String before, String after){
		Map<String,String> old = handlers(before);
		List<String> names = new ArrayList<String>();
		for(Map.Entry<String,String> h : handlers(after).entrySet())
			if(!h.getValue().equals(old.get(h.getKey()))) names.add(h.getKey());
		if(names.isEmpty()) return "";
		String shown = String.join(", ", names.subList(0, Math.min(HANDLERS_SHOWN, names.size())));
		return International.t("Handlers added or changed: {0}", names.size()>HANDLERS_SHOWN ? shown+", …" : shown);
	}

	/** The handlers of a script: name (lower case) → its lines. */
	private static Map<String,String> handlers(String script){
		Map<String,String> found = new LinkedHashMap<String,String>();
		String name = null;
		StringBuilder body = null;
		for(String line : script.split("\n", -1)){
			String[] words = line.trim().toLowerCase(Locale.ROOT).split("\\s+", 3);
			if(name==null){
				if(words.length>=2 && (words[0].equals("on") || words[0].equals("function"))){
					name = words[1];
					body = new StringBuilder();
				}
				else continue;
			}
			body.append(line).append('\n');
			if(words.length>=2 && words[0].equals("end") && words[1].equals(name)){
				found.putIfAbsent(name, body.toString());
				name = null;
			}
		}
		if(name!=null) found.putIfAbsent(name, body.toString());
		return found;
	}

	/** A warning with buttons, the first one the default: the index chosen, -1 when closed. */
	static int choose(String title, String text, String... options){
		int[] chosen = {-1};
		Runnable show = () -> chosen[0] = JOptionPane.showOptionDialog(PCARD.pc, text, title, JOptionPane.DEFAULT_OPTION,
				JOptionPane.WARNING_MESSAGE, null, options, options[0]);
		if(SwingUtilities.isEventDispatchThread()) show.run();
		else try{ SwingUtilities.invokeAndWait(show); }catch(Exception e){ e.printStackTrace(); }
		return chosen[0];
	}
}
