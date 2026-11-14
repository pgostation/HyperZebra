import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Export for Web: TalkJit ahead of time. Every block of every script of the exported stacks that the interpreter may enter
 * (a handler's body, a repeat's body, an if branch's block, a repeat as compiledLoop/compiledCount take it) is compiled with
 * TalkJit's own Gen, the desktop JIT's compiler, in its ahead-of-time mode, into one ordinary class per script, TalkAot0,
 * TalkAot1…, and TalkAotIndex, whose install() registers them all. TeaVM then builds these with the core into the stack's
 * player.wasm (WebAotBuild), so compiled code calls the interpreter's runtime directly: no JavaScript between them, numbers as
 * doubles until something needs their text, and whatever Gen does not take is the interpreter's, statement by statement.
 * <p>What the code depends on is checked in the player before it runs (TalkJit.Aot): the script's text exactly, the words of
 * each text Gen read, the constants made again from the script. Anything different and that script is interpreted.
 */
final class TalkJitAot {
	private TalkJitAot(){}

	/** The classes made (name: class file bytes, TalkAotIndex last) and what was taken */
	static final class Output {
		final LinkedHashMap<String,byte[]> classes = new LinkedHashMap<String,byte[]>();
		int scripts, entries, taken, fast, slow;
		@Override public String toString(){ return scripts+" scripts, "+taken+" of "+entries+" blocks compiled ("+fast+" fast statements, "+slow+" interpreted)"; }
	}

	/** Compiles the scripts (OObject.setScript texts); the class names start with prefix (TalkAot for the player) */
	static Output compile(Collection<String> scripts, String prefix){
		Output out = new Output();
		ArrayList<String> installs = new ArrayList<String>();
		for(String text : new LinkedHashSet<String>(scripts)){
			if(text==null || !mayHaveHandlers(text)) continue;
			String name = prefix+installs.size();
			byte[] bytes = compileScript(text, name, out);
			if(bytes==null) continue;
			out.classes.put(name, bytes);
			installs.add(name);
		}
		out.classes.put(prefix+"Index", index(prefix+"Index", installs));
		return out;
	}

	/** Writes the classes into dir (dir/TalkAot0.class…) */
	static Output compile(Collection<String> scripts, File dir) throws IOException {
		Output out = compile(scripts, "TalkAot");
		Files.createDirectories(dir.toPath());
		for(Map.Entry<String,byte[]> e : out.classes.entrySet()) Files.write(new File(dir, e.getKey()+".class").toPath(), e.getValue());
		return out;
	}

	private static boolean mayHaveHandlers(String text){
		String lower = text.toLowerCase(Locale.ROOT);
		return lower.contains("on ") || lower.contains("function ");
	}

	/** The words of a line without its comment, as the handler lines are read */
	private static String[] lineWords(String line){
		int comment = line.indexOf("--");
		String s = (comment>=0 ? line.substring(0, comment) : line).trim();
		return s.isEmpty() ? new String[0] : s.split("[ \\t\\u3000]+");
	}

	private static byte[] compileScript(String text, String className, Output out){
		OObject object = new OObject();
		object.setScript(text);
		ArrayList<String> script = object.scriptList;
		TalkJit.ScriptCode sc = new TalkJit.ScriptCode(script, null, Rsrc.xcmdGeneration);
		TalkJit.Gen gen = new TalkJit.Gen(sc, object, null, className, true);
		// the entries: (counted, start, end), handlers first so that their blocks are made with them
		ArrayList<int[]> entries = new ArrayList<int[]>();
		ArrayList<int[]> inner = new ArrayList<int[]>();
		for(int i=0; i<script.size(); i++){
			String[] w = lineWords(script.get(i));
			if(w.length<2 || !(w[0].equalsIgnoreCase("on") || w[0].equalsIgnoreCase("function"))) continue;
			int end = -1;
			for(int j=i+1; j<script.size() && end<0; j++){
				String[] e = lineWords(script.get(j));
				if(e.length>=2 && e[0].equalsIgnoreCase("end") && e[1].equalsIgnoreCase(w[1])) end = j;
			}
			if(end<0 || i+1>end-1) continue;
			entries.add(new int[]{0, i+1, end-1});
			innerBlocks(object, script, i+1, end-1, inner);
		}
		entries.addAll(inner);
		LinkedHashMap<String,int[]> unique = new LinkedHashMap<String,int[]>();
		for(int[] e : entries) unique.putIfAbsent(e[0]+","+e[1]+","+e[2], e);
		ArrayList<int[]> list = new ArrayList<int[]>(unique.values());
		String[] methods = new String[list.size()];
		int taken = 0;
		for(int i=0; i<list.size(); i++){
			int[] e = list.get(i);
			methods[i] = gen.entryMethod(e[0]!=0, e[1], e[2]);
			if(methods[i]!=null) taken++;
		}
		out.entries += list.size();
		if(taken==0) return null;
		StringBuilder data = new StringBuilder();
		TalkJit.Fields.put(data, "hzaot1");
		TalkJit.Fields.put(data, sc.names.size());
		for(String n : sc.names) TalkJit.Fields.put(data, n);
		TalkJit.Fields.put(data, gen.aotStrings.size());
		for(String s : gen.aotStrings) TalkJit.Fields.put(data, s);
		TalkJit.Fields.put(data, list.size());
		for(int[] e : list){ TalkJit.Fields.put(data, e[0]); TalkJit.Fields.put(data, e[1]); TalkJit.Fields.put(data, e[2]); }
		TalkJit.Fields.put(data, gen.recipes.size());
		for(String[] recipe : gen.recipes){
			for(String f : recipe) if(f==null) return null;
			TalkJit.Fields.put(data, recipe[0]);
			if(recipe[0].equals("N") || recipe[0].equals("V")) TalkJit.Fields.put(data, recipe.length-1);
			for(int k=1; k<recipe.length; k++) TalkJit.Fields.put(data, recipe[k]);
		}
		LinkedHashMap<String,Integer> lineOf = new LinkedHashMap<String,Integer>();
		for(int i=script.size()-1; i>=0; i--) lineOf.put(script.get(i), i);
		TalkJit.Fields.put(data, gen.prints.size());
		for(Map.Entry<String,Long> p : gen.prints.entrySet()){
			String key = p.getKey(), read = key.substring(1);
			Integer line = lineOf.get(read);
			TalkJit.Fields.put(data, key.substring(0, 1));
			TalkJit.Fields.put(data, line!=null ? "L"+line : "T"+read);
			TalkJit.Fields.put(data, p.getValue());
		}
		try{
			int[] starts = new int[list.size()];
			for(int i=0; i<starts.length; i++) starts[i] = list.get(i)[1];
			byte[] bytes = gen.finishAot(methods, starts, text, data.toString());
			out.scripts++;
			out.taken += taken;
			out.fast += gen.fastStatements;
			out.slow += gen.slowStatements;
			return bytes;
		}catch(TalkJit.Reject e){
			return null;
		}
	}

	/** The repeats (body, and the loop itself as compiledLoop or compiledCount enter it) and the if branches' blocks inside a block */
	private static void innerBlocks(OObject object, ArrayList<String> script, int start, int end, ArrayList<int[]> out){
		for(int line=start; line<=end; line++){
			String[] w = lineWords(script.get(line));
			if(w.length==0) continue;
			if(w[0].equalsIgnoreCase("repeat")){
				int endRepeat;
				try{ endRepeat = TTalk.findEndRepeat(object, script, line, end); }
				catch(xTalkException e){ continue; }
				if(endRepeat<=line || endRepeat>end) continue;
				String how = w.length>1 ? w[1].toLowerCase(Locale.ROOT) : "forever";
				boolean state = how.equals("forever") || how.equals("while") || how.equals("until") || how.startsWith("--");
				boolean each = how.equals("for") && w.length>2 && w[2].equalsIgnoreCase("each");
				if(!each) out.add(new int[]{state ? 0 : 1, line, endRepeat});
				if(line+1<=endRepeat-1) out.add(new int[]{0, line+1, endRepeat-1});
			}
			else if(w[0].equalsIgnoreCase("if")){
				try{
					TalkJit.Words words = TalkJit.words(script.get(line), true);
					ArrayList<TTalk.IfBranch> branches = new ArrayList<TTalk.IfBranch>();
					TalkJit.ifBranches(TTalk.cachedIfStructure(object, script, line, end, words.words, words.types), branches);
					for(TTalk.IfBranch b : branches) if(b.bodyStart>=0 && b.bodyStart<=b.bodyEnd) out.add(new int[]{0, b.bodyStart, b.bodyEnd});
				}catch(xTalkException | RuntimeException e){ /* the interpreter reports it */ }
			}
		}
	}

	/** TalkAotIndex: install() calls each script class's install() */
	private static byte[] index(String name, ArrayList<String> installs){
		JitClassFile cf = new JitClassFile(name, "java/lang/Object");
		ArrayList<String> parts = new ArrayList<String>();
		for(int at=0; at<installs.size(); at+=8000){
			String part = "install"+parts.size();
			parts.add(part);
			JitClassFile.Code c = cf.method(0x0009, part, "()V");
			for(int i=at; i<Math.min(installs.size(), at+8000); i++) c.invokestatic(installs.get(i), "install", "()V");
			c.op(JitClassFile.RETURN);
			c.finish();
		}
		JitClassFile.Code c = cf.method(0x0009, "install", "()V");
		for(String part : parts) c.invokestatic(name, part, "()V");
		c.op(JitClassFile.RETURN);
		c.finish();
		return cf.toBytes();
	}
}
