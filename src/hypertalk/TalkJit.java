import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Compiles hot blocks of HyperTalk to Java bytecode (hidden classes), which HotSpot then compiles to machine code. TTalk stays
 * the definition of the language: this only takes what it can show to be the same as the interpreter, and everything else
 * goes back to it.
 * <p>A block is what TTalk.doScript runs: a handler body, a repeat body, an if branch. One that was entered
 * {@link #THRESHOLD} times is compiled, the blocks in it as well, so a long loop gets its body compiled while it runs. Only an
 * object's own script is compiled (do and send texts are not).
 * <ul>
 * <li>Each block is a static method with the shape of TTalk.doScriptLines: the same Result, the same ret codes, one try/catch
 *     around it that ends in handleScriptError with the statement's line. Control flow (if, repeat, exit, next) is compiled.
 * <li>Any other statement runs as doScriptLines(line, line): the interpreter itself, with its debugger, speed limit and error
 *     handling, at about the speed it had.
 * <li>The fast statements (put into a variable or a chunk of one, get, add... to a variable, put before/after, return) compute
 *     only with pure helpers on the value strings the interpreter would have. Those give null for anything out of the ordinary
 *     (no such variable, text in arithmetic, an error); nothing has been changed by then, so the statement simply runs in the
 *     interpreter, which does what it always does, error message included.
 * <li>A function that is not in {@link #PURE_FUNCTIONS} is called as the interpreter calls it, and its statement goes on compiled.
 *     Such a call cannot be undone: if the interpreter has to finish that statement, TTalk.funcReplay gives it the calls
 *     already made (see {@link Sim#checkCallOrder}).
 * </ul>
 * <b>Values are strings, numbers only where the string cannot be told:</b> a value that passes through text expressions is the
 * string Evalution would have, as number to text and back is lossy by design (raw and formatted numbers, "13.0" as a real,
 * interned integer literals). But the string of a computed number follows from its double and whether it is a real
 * ({@link #norm}, {@link #rawText}), and it reads back as the same, so arithmetic, comparisons and the math functions work on
 * doubles ({@link Node#emitNumber}), which is how the interpreter computes as well, and a variable given a computed number holds
 * it in {@link Frame#numbers} and gets its string when anything else wants to see it ({@link #sync}, before every way out of
 * compiled code, and {@link #writeBack}).
 * <p><b>How an expression is read:</b> {@link Sim} runs the passes of Evalution2 over the words at compile time, in the same order
 * and with the same scans, giving a tree instead of a value. Whatever a pass would treat specially is rejected, and the
 * statement stays with the interpreter. Evaluation order does not matter as all the nodes are pure.
 * <p><b>What can invalidate it:</b> the script changing (OObject.jit is dropped), a handler on the message path taking over
 * put/add/... ({@link #pathClear}), a stack's externals changing, and the states in {@link #slow}: a compiled block checks
 * them before each statement and the interpreter takes over from there on.
 * <p>Settings: PCARD.talkJit (Preferences), -Dhz.talk.jit=false, -Dhz.talk.jitthreshold=N, -Dhz.talk.jittrace=true.
 */
final class TalkJit {
	private TalkJit(){}

	private static final boolean FORCED_OFF = "false".equals(System.getProperty("hz.talk.jit"));
	/** Whether hot blocks are compiled and run compiled. */
	static volatile boolean enabled = !FORCED_OFF;
	static void setEnabled(boolean on){ enabled = on && !FORCED_OFF; }

	static final int THRESHOLD = Math.max(1, Integer.getInteger("hz.talk.jitthreshold", 30));
	static final boolean TRACE = Boolean.getBoolean("hz.talk.jittrace");
	/** Compiled methods over this many bytes continue in another method: HotSpot leaves methods above 8000 interpreted. */
	private static final int SEGMENT_BYTES = 5500;
	private static final int MAX_EXPRESSION_DEPTH = 40;

	/** Bumped when a script changes (its handlers with it), for {@link #pathClear}. */
	static volatile int scriptEpoch;

	static volatile int compiled, failures;
	private static int failureReports;

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	/** Generated classes implement this. */
	interface Code { Result run(Frame f) throws xTalkException; }

	/** What is known of one script. */
	static final class ScriptCode {
		final ArrayList<String> scriptList;
		final Rsrc rsrc;
		final int xcmdGeneration;
		/** by first line */
		final Block[] blocks;
		/** The repeat statements that go on compiled (TTalk.compiledLoop), by first line */
		final Block[] loops;
		/** The variable names of its compiled code: a variable is a number in the code, {@link Frame#slots} finds it. */
		final ArrayList<String> names = new ArrayList<String>();
		final HashMap<String,Integer> nameIndex = new HashMap<String,Integer>();
		final ArrayList<Object> constants = new ArrayList<Object>();

		ScriptCode(ArrayList<String> scriptList, Rsrc rsrc, int xcmdGeneration){
			this.scriptList = scriptList;
			this.rsrc = rsrc;
			this.xcmdGeneration = xcmdGeneration;
			blocks = new Block[scriptList.size()];
			loops = new Block[scriptList.size()];
		}

		private final HashMap<String,Boolean> notDeclared = new HashMap<String,Boolean>();

		/** Whether no line can make the word a variable through a fast statement, which adds nothing to the handler's set:
		 *  it never stands where ScriptEditor.checkWordsLine2 declares one, after into, to... (comments and quoted text count too). */
		boolean neverDeclared(String word){
			return notDeclared.computeIfAbsent(word, w -> {
				for(String line : scriptList){
					String[] words = line.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+");
					for(int i=1; i<words.length; i++){
						if(words[i].equals(w) && (VARIABLE_MAKERS.contains(words[i-1]) || DECLARING_LINES.contains(words[0]))) return false;
					}
				}
				return true;
			});
		}

		int variable(String name){
			String lower = name.toLowerCase();
			Integer i = nameIndex.get(lower);
			if(i==null){ i = names.size(); names.add(lower); nameIndex.put(lower, i); }
			return i;
		}
	}

	private static final java.util.Set<String> VARIABLE_MAKERS = java.util.Set.of("into", "after", "before", "to", "from");
	/** Words of the language that are not in TTalk.commandSet */
	private static final java.util.Set<String> NO_MESSAGES = java.util.Set.of("end", "on", "function", "exit", "next", "pass", "return", "repeat", "if", "version");
	/** Parameters and the like */
	private static final java.util.Set<String> DECLARING_LINES = java.util.Set.of("on", "function", "global");

	/** One block's run count and code. */
	private static final class Block {
		final int end;
		int count;
		Code code;
		boolean failed;
		Block(int end){ this.end = end; }
	}

	private static final int[] NO_INTS = new int[0];
	private static final double[] NO_NUMBERS = new double[0];

	/** The state of a running handler that compiled code needs; one per MemoryData (a handler's call). */
	static final class Frame {
		final TTalk talk;
		final ScriptCode sc;
		final OObject object, target;
		final String message;
		final ArrayList<String> scriptList;
		final MemoryData mem;
		final String[] params;
		final int mode;
		/** Where each variable of {@link ScriptCode#names} is: 0 unknown, n>0 local n-1, n<0 global -n-1. */
		int[] slots;
		int version, globalVersion;
		/** The calls of the statement being run, for the interpreter if it has to finish it (TTalk.funcReplay): name, params, Result */
		ArrayList<Object[]> calls;
		/** By variable: {@link #NUMBER} and the rest, and the number of one that has it. Only while compiled code runs: see {@link #sync}. */
		int[] tags = NO_INTS;
		double[] numbers = NO_NUMBERS;
		/** The variables with a tag */
		int[] tagged = NO_INTS;
		int taggedCount;
		/** The number {@link #parse} read */
		double number;
		/** {@link #slow} and {@link #pathClear} have been asked and nothing has happened since; functions: {@link #functionsClear} */
		boolean checked, functions;
		/** {@link #tick} */
		int passes;
		/** A fast statement's Result is owed to TTalk.lastResult */
		boolean done;
		/** Where a counted loop goes on from (TTalk.compiledCount) */
		int loopFrom, loopTo;

		Frame(TTalk talk, ScriptCode sc, OObject object, OObject target, String message, MemoryData mem, String[] params, int mode){
			this.talk = talk;
			this.sc = sc;
			this.object = object;
			this.target = target;
			this.message = message;
			this.scriptList = sc.scriptList;
			this.mem = mem;
			this.params = params;
			this.mode = mode;
			slots = new int[Math.max(8, sc.names.size())];
			version = mem.variableVersion;
			globalVersion = TTalk.globalData.variableVersion;
		}

		boolean same(ScriptCode sc, OObject object, OObject target, String message, String[] params, int mode){
			return this.sc==sc && this.object==object && this.target==target && this.message==message && this.params==params && this.mode==mode;
		}
	}

	// ------------------------------------------------------------------ entering compiled code

	/** Runs the block if it is compiled; null: run it in the interpreter. */
	static Result enter(TTalk talk, OObject object, OObject target, String message, ArrayList<String> scriptList, int start, int end,
			MemoryData mem, String[] params, int mode) throws xTalkException {
		if(start>end || mem==null || mode!=0 || TTalk.evalDepth!=0) return null;
		ScriptCode sc = scriptCode(object, scriptList);
		Block block = sc.blocks[start];
		if(block==null || block.end!=end) sc.blocks[start] = block = new Block(end);
		return run(block, sc, talk, object, target, message, start, end, mem, params, mode, false, 0, 0);
	}

	/** The repeat statement from {@code line} to {@code endRepeat} as a block; see TTalk.compiledLoop */
	static Result enterLoop(TTalk talk, OObject object, OObject target, String message, ArrayList<String> scriptList, int line, int endRepeat,
			MemoryData mem, String[] params, int mode) throws xTalkException {
		if(mem==null || mode!=0 || TTalk.evalDepth!=0) return null;
		ScriptCode sc = scriptCode(object, scriptList);
		Block block = sc.loops[line];
		if(block==null || block.end!=endRepeat) sc.loops[line] = block = new Block(endRepeat);
		return run(block, sc, talk, object, target, message, line, endRepeat, mem, params, mode, false, 0, 0);
	}

	/** The rest of a repeat with or repeat n times; see TTalk.compiledCount */
	static Result enterCount(TTalk talk, OObject object, OObject target, String message, ArrayList<String> scriptList, int line, int endRepeat, int from, int to,
			MemoryData mem, String[] params, int mode) throws xTalkException {
		if(mem==null || mode!=0 || TTalk.evalDepth!=0) return null;
		ScriptCode sc = scriptCode(object, scriptList);
		Block block = sc.loops[line];
		if(block==null || block.end!=endRepeat) sc.loops[line] = block = new Block(endRepeat);
		return run(block, sc, talk, object, target, message, line, endRepeat, mem, params, mode, true, from, to);
	}

	private static ScriptCode scriptCode(OObject object, ArrayList<String> scriptList){
		ScriptCode sc = object.jit;
		Rsrc rsrc = PCARD.pc!=null && PCARD.pc.stack!=null ? PCARD.pc.stack.rsrc : null;
		if(sc==null || sc.scriptList!=scriptList || sc.blocks.length!=scriptList.size() || sc.rsrc!=rsrc || sc.xcmdGeneration!=Rsrc.xcmdGeneration)
			object.jit = sc = new ScriptCode(scriptList, rsrc, Rsrc.xcmdGeneration);
		return sc;
	}

	private static Result run(Block block, ScriptCode sc, TTalk talk, OObject object, OObject target, String message, int start, int end,
			MemoryData mem, String[] params, int mode, boolean counted, int from, int to) throws xTalkException {
		Code code = block.code;
		if(code==null){
			if(block.failed || ++block.count<THRESHOLD) return null;
			code = compile(sc, block, object, start, end, mem, counted);
			if(code==null) return null;
		}
		if(slow(object)) return null;
		Frame frame = mem.jitFrame;
		if(frame==null || !frame.same(sc, object, target, message, params, mode))
			mem.jitFrame = frame = new Frame(talk, sc, object, target, message, mem, params, mode);
		frame.loopFrom = from;
		frame.loopTo = to;
		Result r = null;
		try{ r = code.run(frame); }
		finally{ sync(frame); }
		return r==NOTHING ? new Result() : r;
	}

	private static Code compile(ScriptCode sc, Block block, OObject object, int start, int end, MemoryData mem, boolean counted) {
		long t0 = System.nanoTime();
		try{
			Gen gen = new Gen(sc, object, mem);
			gen.goesOnAt = counted ? start : -1;
			byte[] bytes = gen.generate(start, end);
			Class<?> cls = LOOKUP.defineHiddenClass(bytes, true).lookupClass();
			Code code = (Code)cls.getDeclaredConstructor().newInstance();
			block.code = code;
			compiled++;
			if(TRACE) System.err.printf("[talk jit] %s lines %d-%d: %d bytes, %d fast, %d interpreted, %d us%n", object.getShortName(), start+1, end+1,
				bytes.length, gen.fastStatements, gen.slowStatements, (System.nanoTime()-t0)/1000);
			return code;
		}catch(Throwable t){
			block.failed = true;
			failures++;
			if(failureReports++<10) System.err.println("[talk jit] cannot compile lines "+(start+1)+"-"+(end+1)+" (interpreted): "+t);
			if(TRACE) t.printStackTrace();
			return null;
		}
	}

	// ------------------------------------------------------------------ guards

	/** The interpreter has to run the statements: an abort, the debugger, the speed limit, a numberFormat of the script. */
	private static boolean slow(OObject object){
		return !enabled || TTalk.stop || TTalk.tracemode!=0 || object.checkpoints!=null || TTalk.numFormat!=TTalk.DEFAULT_NUMBER_FORMAT_INSTANCE ||
			StackSpeed.limited() || TTalk.abandonedThread();
	}
	/** Before a control line: compiled code goes on only when false. */
	static boolean slow(Frame f) throws xTalkException {
		if(f.checked) return false;
		if(slow(f.object)) return true;
		check(f);
		return false;
	}

	/** Before a fast statement: nothing in {@link #slow} and nobody takes over the command. */
	static boolean fast(Frame f) throws xTalkException { return f.checked || check(f); }

	/** All these change by what compiled code does not do itself, so the answer holds until it leaves ({@link #sync}) or {@link #tick} wants a new one. */
	private static boolean check(Frame f) throws xTalkException {
		if(slow(f.object)) return false;
		if(Xcmd68k.hasSessionsHint()){
			sync(f);
			Xcmd68k.pumpIfDue();
		}
		if(f.talk.sendPending() || !pathClear(f.object)) return false;
		f.functions = pathState.functionsClear;
		f.checked = true;
		return true;
	}

	/** Each pass of a compiled loop: every so often it asks again, for an abort or the debugger */
	static void tick(Frame f){
		if(--f.passes<0){
			f.passes = PASSES_CHECKED;
			f.checked = false;
		}
	}
	private static final int PASSES_CHECKED = 255;

	/** Before an expression that calls built-in functions directly: nobody takes over those either. */
	static boolean functionsClear(Frame f){
		if(f.checked) return f.functions;
		pathClear(f.object);
		return pathState.functionsClear;
	}

	private static final String[] COMMANDS = {"put", "get", "add", "subtract", "multiply", "divide"};
	/** Built-in functions without side effects: run directly, and again in the interpreter if need be (a second random is as good
	 *  as the first: Math.random, nothing a script can seed). */
	private static final java.util.Set<String> PURE_FUNCTIONS = java.util.Set.of("abs", "annuity", "atan", "average", "compound", "cos", "exp", "exp1", "exp2",
		"ln", "ln1", "log2", "max", "min", "random", "round", "sin", "sqrt", "sum", "tan", "trunc", "length", "offset", "chartonum", "numtochar");
	/** the ticks and the like: functions of the "the" pass of evalProperties, which calls them without a message */
	private static final java.util.Set<String> CLOCK_FUNCTIONS = java.util.Set.of("ticks", "seconds", "secs", "milliseconds");
	private record PathState(OObject object, OStack stack, OCard card, int epoch, int xcmdGeneration, OStack[] using, boolean clear, boolean functionsClear) {}
	private static PathState pathState;
	private static final OStack[] NO_STACKS = new OStack[0];

	/** No handler on the message path and no XCMD catches the commands fast statements run (as CommandExecStatement looks). */
	private static boolean pathClear(OObject object){
		PathState s = pathState;
		PCARD pc = PCARD.pc;
		OStack stack = pc==null ? null : pc.stack;
		OCard card = stack==null ? null : stack.curCard;
		ArrayList<OStack> using = stack==null ? null : stack.usingStacks;
		if(s!=null && s.object==object && s.stack==stack && s.card==card && s.epoch==scriptEpoch && s.xcmdGeneration==Rsrc.xcmdGeneration && sameStacks(s.using, using))
			return s.clear;
		int epoch = scriptEpoch, generation = Rsrc.xcmdGeneration;
		boolean clear = true;
		for(int i=0; clear && i<COMMANDS.length; i++) clear = TTalk.commandHandlerKind(COMMANDS[i], object)==0;
		OStack[] copy = using==null || using.isEmpty() ? NO_STACKS : using.toArray(new OStack[0]);
		boolean functions = functionsClear(object, stack);
		pathState = new PathState(object, stack, card, epoch, generation, copy, clear, functions);
		return clear;
	}

	/** The message of a function call goes along the path of commandHandlerKind when the running handler's stack is the current one
	 *  or in use by it; elsewhere it has a path of its own. */
	private static boolean functionsClear(OObject object, OStack stack){
		OObject owner = object;
		while(owner!=null && !(owner instanceof OStack)) owner = owner.parent;
		if(owner!=stack && (stack==null || stack.usingStacks==null || !stack.usingStacks.contains(owner))) return false;
		for(String name : PURE_FUNCTIONS){
			if(TTalk.commandHandlerKind(name, object)!=0 || TXcmd.externalOwner(name, "function")!=null) return false;
		}
		return true;
	}

	private static boolean sameStacks(OStack[] a, ArrayList<OStack> b){
		int n = b==null ? 0 : b.size();
		if(a.length!=n) return false;
		for(int i=0; i<n; i++) if(a[i]!=b.get(i)) return false;
		return true;
	}

	// ------------------------------------------------------------------ the interpreter, called from compiled code

	/**
	 * Compiled code is about to leave, for the interpreter or for good: the variables get their strings, and whatever looks at
	 * them finds what the interpreter would have left (a handler it calls, the debugger, do, an XCMD). When it comes back to
	 * compiled code all this has been done again by whatever compiled code ran in between, so nothing is held then: a variable has
	 * a tag only in a stretch of compiled code without a way out, and two handlers never hold a global at the same time.
	 */
	static void sync(Frame f) throws xTalkException {
		f.checked = false;
		if(f.done){
			f.done = false;
			TTalk.lastResult = new Result();
		}
		int n = f.taggedCount;
		if(n==0) return;
		f.taggedCount = 0;
		int[] tags = f.tags, tagged = f.tagged;
		for(int i=0; i<n; i++){
			int v = tagged[i];
			if((tags[v]&DIRTY)!=0) writeBack(f, v, slot(f, v));
			tags[v] = 0;
		}
	}

	/** A statement of one line, in the interpreter. */
	static Result stmt(Frame f, int line) throws xTalkException {
		sync(f);
		if(f.calls!=null){ TTalk.setCompiledCalls(f.calls); f.calls = null; }
		return f.talk.doScriptLines(f.object, f.target, f.message, f.scriptList, line, line, f.mem, f.params, f.mode);
	}

	/** The rest of the block, in the interpreter. */
	static Result rest(Frame f, int line, int end) throws xTalkException {
		sync(f);
		return f.talk.doScriptLines(f.object, f.target, f.message, f.scriptList, line, end, f.mem, f.params, f.mode);
	}

	/** A block that is not to run compiled just now, as TTalk.doScript without looking for compiled code. */
	static Result block(Frame f, int start, int end) throws xTalkException {
		if(StackSpeed.limited()) StackSpeed.block();
		return rest(f, start, end);
	}

	/** The catch of doScriptLines: the error at statement {@code stmt}, and if the debugger goes on, the lines after {@code resume}. */
	static Result caught(Frame f, Throwable t, int stmt, int resume, int end) throws xTalkException {
		f.calls = null;
		sync(f);
		if(t instanceof xTalkException e) f.talk.handleScriptError(e, f.object, f.scriptList, stmt, f.mem, f.mode);
		else{
			if(!(t instanceof RuntimeException)) throw (Error)t;
			t.printStackTrace();
			String statement = stmt>=0 && stmt<f.scriptList.size() ? f.scriptList.get(stmt).trim() : "";
			f.talk.handleScriptError(new xTalkException("Cannot interpret {0}", statement), f.object, f.scriptList, stmt, f.mem, f.mode);
		}
		return resume<end ? rest(f, resume+1, end) : new Result();
	}

	static String eval(Frame f, int line, String text) throws xTalkException {
		sync(f);
		TTalk.setStatementSource(f.scriptList.get(line));
		TTalk.lexicalScope(f.mem, f.object, f.scriptList, line);
		if(f.calls==null) return TTalk.Evalution(text, f.mem, f.object, f.target);
		ArrayList<Object[]> calls = f.calls;
		f.calls = null;
		return TTalk.evalutionAfterCalls(text, f.mem, f.object, f.target, calls);
	}

	static boolean cond(Frame f, int line, String text, String context) throws xTalkException {
		return TTalk.conditionIsTrue(eval(f, line, text), context);
	}

	static Result inline(Frame f, int line, int constant) throws xTalkException {
		sync(f);
		TTalk.setStatementSource(f.scriptList.get(line));
		if(f.calls!=null){ TTalk.setCompiledCalls(f.calls); f.calls = null; }
		return f.talk.runInlineBranch((TTalk.IfBranch)f.sc.constants.get(constant), f.object, f.target, f.message, f.mem, f.params);
	}

	static int bound(String value) throws xTalkException {
		try{ return value.trim().isEmpty() ? 0 : TTalk.wholeNumber(value); }
		catch(NumberFormatException e){ throw new xTalkException("repeat with requires integer bounds"); }
	}

	static int times(String value) throws xTalkException {
		try{ return value.trim().isEmpty() ? 0 : (int)Math.floor(Double.parseDouble(value.trim())); }
		catch(NumberFormatException e){ throw new xTalkException("The repeat count is not a number: {0}", value); }
	}

	static boolean isMessage(Frame f, String word){ return 0==word.compareToIgnoreCase(f.message); }

	/** The Result of a statement that has none to speak of: compiled code changes a copy of it ({@link #own}) */
	static final Result NOTHING = new Result();

	static Result nothing(){ return NOTHING; }

	/** The end of a fast statement */
	static Result done(Frame f){
		f.done = true;
		return NOTHING;
	}

	/** The Result to give a ret */
	static Result own(Result r){ return r==NOTHING ? new Result() : r; }

	/** return value: a function hands a computed number on raw */
	static Result returned(Frame f, String value, boolean numeric){
		Result r = new Result();
		if(numeric){
			if(f.mem.isFunction){ r.theResult = value; r.numeric = true; }
			else r.theResult = formatted(value);
		}
		else r.theResult = value;
		r.ret = 3;
		return r;
	}

	static void noCalls(Frame f){ f.calls = null; }

	static Result fresh(){ return new Result(); }

	/** The value of a compiled condition: 1 true, 0 false, -1 not a boolean (the interpreter reports it). */
	static int truth(String value){
		if(value=="true") return 1;
		if(value=="false") return 0;
		if(value==null) return -1;
		if(value.equalsIgnoreCase("true")) return 1;
		return value.equalsIgnoreCase("false") ? 0 : -1;
	}

	// ------------------------------------------------------------------ variables

	private static int slot(Frame f, int v){
		MemoryData m = f.mem;
		int[] slots = f.slots;
		if(f.version!=m.variableVersion || f.globalVersion!=TTalk.globalData.variableVersion){
			f.version = m.variableVersion;
			f.globalVersion = TTalk.globalData.variableVersion;
			Arrays.fill(slots, 0);
		}
		if(v>=slots.length) f.slots = slots = Arrays.copyOf(slots, Math.max(v+8, f.sc.names.size()));
		int s = slots[v];
		if(s!=0) return s;
		return slots[v] = resolve(m, f.sc.names.get(v));
	}

	/** Where TTalk.getVariable finds the variable. */
	private static int resolve(MemoryData m, String name){
		if(m.namesInSet){ //the first look at each variable of each call: a handler has few, and its own first
			int local = m.indexOfName(name);
			if(local>=0) return local+1;
		}
		MemoryData g = TTalk.globalData;
		boolean seesGlobal = m.seesGlobal(name) && g.treeset.contains(name);
		if(!m.treeset.contains(name) && !seesGlobal) return 0;
		int local = m.indexOfName(name);
		if(local>=0) return local+1;
		if(!seesGlobal) return 0;
		int global = g.indexOfName(name);
		return global>=0 ? -global-1 : 0;
	}

	private static String value(Frame f, int s){
		return s>0 ? f.mem.valueList.get(s-1) : TTalk.globalData.valueList.get(-s-1);
	}

	/** Its string, if it is holding a number just now */
	private static void see(Frame f, int v, int s) throws xTalkException {
		if(v<f.tags.length && (f.tags[v]&DIRTY)!=0) writeBack(f, v, s);
	}

	/** A variable next to an arithmetic operator: as it is, a computed number with its precision. */
	static String arith(Frame f, int v) throws xTalkException {
		int s = slot(f, v);
		if(s==0) return null;
		see(f, v, s);
		return value(f, s);
	}

	/** A variable used as text: a computed number is formatted. */
	static String text(Frame f, int v) throws xTalkException {
		int s = slot(f, v);
		if(s==0) return null;
		see(f, v, s);
		String value = value(f, s);
		return isRaw(f, v, s) ? TTalk.applyNumberFormat(value) : value;
	}

	/** A variable that is a whole expression: a computed number is left to the interpreter. */
	static String lone(Frame f, int v) throws xTalkException {
		int s = slot(f, v);
		if(s==0) return null;
		see(f, v, s);
		return isRaw(f, v, s) ? null : value(f, s);
	}

	private static boolean isRaw(Frame f, int v, int s){
		java.util.HashSet<String> raw = s>0 ? f.mem.rawNumbers : TTalk.globalData.rawNumbers;
		return raw!=null && !raw.isEmpty() && raw.contains(f.sc.names.get(v));
	}

	/** TTalk.setVariable */
	private static void store(Frame f, int v, int s, String value) throws xTalkException {
		if(v<f.tags.length && f.tags[v]!=0) f.tags[v] = LISTED;
		String name = f.sc.names.get(v);
		MemoryData m = f.mem, g = TTalk.globalData;
		if(s==0){ TTalk.setVariable(m, name, value); return; }
		if(m.rawNumbers!=null) m.rawNumbers.remove(name);
		if(g.rawNumbers!=null && m.seesGlobal(name)) g.rawNumbers.remove(name);
		if(s>0) m.valueList.set(s-1, value);
		else g.valueList.set(-s-1, value);
	}

	/** TTalk.putComputedValue for a value that is the raw result of an arithmetic expression */
	private static void storeComputed(Frame f, int v, int s, String raw) throws xTalkException {
		String value = formatted(raw);
		if(raw.equals(value)){ store(f, v, s, value); return; }
		store(f, v, s, raw);
		String name = f.sc.names.get(v);
		MemoryData owner = s>0 ? f.mem : TTalk.globalData;
		if(owner.rawNumbers==null) owner.rawNumbers = new java.util.HashSet<String>();
		owner.rawNumbers.add(name);
	}

	/** put … into an existing variable; false: not one. */
	static boolean assign(Frame f, int v, String value, boolean numeric) throws xTalkException {
		int s = slot(f, v);
		if(s==0 || TalkCollection.isCollection(value(f, s))) return false;
		if(numeric) storeComputed(f, v, s, value);
		else store(f, v, s, value);
		return true;
	}

	/** get: into it, which need not exist yet */
	static void getInto(Frame f, int v, String value, boolean numeric) throws xTalkException {
		int s = slot(f, v);
		if(!numeric){ store(f, v, s, value); return; }
		if(s==0){
			TTalk.setVariable(f.mem, "it", value);
			s = slot(f, v);
		}
		storeComputed(f, v, s, value);
	}

	/** The counter of repeat with */
	static void counter(Frame f, int v, int value) throws xTalkException {
		if(TTalk.numFormat!=TTalk.DEFAULT_NUMBER_FORMAT_INSTANCE){
			sync(f);
			TTalk.setNumberVariable(f.mem, f.sc.names.get(v), Integer.toString(value));
			return;
		}
		int[] tags = f.tags;
		if(v<tags.length && (tags[v]&NUMBER)!=0){
			f.numbers[v] = value;
			tags[v] = NUMBER|DIRTY;
			return;
		}
		if(slot(f, v)==0) store(f, v, 0, Integer.toString(value));
		tag(f, v, value, NUMBER|DIRTY);
	}

	/** put … into a chunk of an existing variable (TTalk.setChunkVariable); false: not one. */
	static boolean putChunk(Frame f, int v, String mode, String first, String last, String value) throws xTalkException {
		if(first==null || !chunkNumber(first) || last!=null && !chunkNumber(last)) return false;
		String old = text(f, v);
		if(old==null || TalkCollection.isCollection(old)) return false;
		int start = Integer.parseInt(first);
		int end = last==null ? start : Integer.parseInt(last);
		store(f, v, slot(f, v), TTalk.replaceChunk(mode, start, end, value, old));
		return true;
	}

	/** put … after/before an existing variable (the interpreter runs put (dest) & hzputaftervalue into dest); false: not one. */
	static boolean append(Frame f, int v, int valueVariable, String value, boolean after) throws xTalkException {
		String old = text(f, v);
		if(old==null || TalkCollection.isCollection(old) || TalkCollection.isCollection(value)) return false;
		int s = slot(f, valueVariable);
		store(f, valueVariable, s, value);
		s = slot(f, v);
		if(s==0) return false;
		store(f, v, s, after ? old+value : value+old);
		return true;
	}

	// ------------------------------------------------------------------ numbers
	// The kind of a number, what the rest of it follows from: REAL as TTalk.isRealNumber says of its string; ODD it is not the
	// number comparisons see (blank is 0 only in arithmetic, "+3" is text there); PLAIN no ".0" although a real, which is only
	// 2147483648 made by integers. A variable's tag is its kind with NUMBER, DIRTY when the variable's string is not up to date.

	private static final int REAL = 1, ODD = 2, PLAIN = 4, KIND = 7, NUMBER = 8, DIRTY = 16;
	/** On the list of tagged variables, holding nothing */
	private static final int LISTED = 32;

	/** The kind of a computed number, as isRealNumber will find it in its string: an integer beyond 32 bits is a real, -0 is written alike for both */
	static int norm(double value, int real){
		if(real==0) return value>=-2147483648d && value<=2147483647d ? 0 : REAL|PLAIN;
		return value!=0 || 1/value>0 ? REAL : 0;
	}

	/** The kind of the result of integers or reals a and b, as isRealNumber(a) || isRealNumber(b) ? formatRealNumber : formatRawNumber */
	static int kind(double value, int a, int b){ return norm(value, (a|b)&REAL); }

	/** The raw string of a computed number */
	static String rawText(double value, int kind){
		if(kind==0 && value!=0) return wholeText((long)value);
		return (kind&(REAL|PLAIN))==REAL ? TTalk.formatRealNumber(value) : TTalk.formatRawNumber(value);
	}

	/** The kind of s as an operand; -1: the interpreter sees to that */
	private static int kindOf(String s){
		if(s.trim().isEmpty()) return ODD;
		if(infinitySign(s)) return -1;
		double value;
		try{ value = TTalk.parseNumber(s); }
		catch(NumberFormatException e){ return -1; }
		boolean real = TTalk.isRealNumber(s);
		if(!real && (value!=Math.rint(value) || Math.abs(value)>2147483648d)) return -1; //"0x1p-1": mod and the like count on it being an integer
		return (real ? REAL : 0)|(TTalk.mayBeNumber(s) ? 0 : ODD);
	}

	private static double valueOf(String s){ return s.trim().isEmpty() ? 0 : TTalk.parseNumber(s); }

	/** Reads text as a number into {@link Frame#number}: gives its kind or -1. comparing: only a number a comparison takes for one. */
	static int parse(Frame f, String s, boolean comparing){
		if(s==null) return -1;
		long whole = smallInt(s);
		if(whole!=NO_INT){
			f.number = whole;
			return 0;
		}
		if(comparing && !TTalk.mayBeNumber(s)) return -1;
		int kind = kindOf(s);
		if(kind<0) return -1;
		f.number = valueOf(s);
		return kind;
	}

	private static void tag(Frame f, int v, double value, int bits){
		int[] tags = f.tags;
		if(v>=tags.length){
			int n = Math.max(v+8, f.sc.names.size());
			f.tags = tags = Arrays.copyOf(tags, n);
			f.numbers = Arrays.copyOf(f.numbers, n);
		}
		if(tags[v]==0){
			if(f.taggedCount==f.tagged.length) f.tagged = Arrays.copyOf(f.tagged, f.taggedCount+8);
			f.tagged[f.taggedCount++] = v;
		}
		f.numbers[v] = value;
		tags[v] = bits;
	}

	/** The variable's number: where it is, {@link #numberOf}; gives its kind or -1 */
	static int numbered(Frame f, int v) throws xTalkException {
		int[] tags = f.tags;
		if(v<tags.length){
			int tag = tags[v];
			if((tag&NUMBER)!=0) return tag&KIND;
		}
		return load(f, v, false);
	}

	/** The same for a comparison */
	static int compared(Frame f, int v) throws xTalkException {
		int[] tags = f.tags;
		if(v<tags.length){
			int tag = tags[v];
			if((tag&NUMBER)!=0) return (tag&ODD)!=0 ? -1 : tag&KIND;
		}
		return load(f, v, true);
	}

	static double numberOf(Frame f, int v){ return f.numbers[v]; }

	private static int load(Frame f, int v, boolean comparing){
		int s = slot(f, v);
		if(s==0) return -1;
		int kind = parse(f, value(f, s), comparing);
		if(kind>=0) tag(f, v, f.number, kind|NUMBER);
		return kind;
	}

	/** The string of a variable holding a computed number */
	private static void writeBack(Frame f, int v, int s) throws xTalkException {
		int tag = f.tags[v];
		storeComputed(f, v, s, rawText(f.numbers[v], tag&KIND));
		f.tags[v] = tag&~DIRTY;
	}

	/** put … into an existing variable, a computed number */
	static boolean assignNumber(Frame f, int v, double value, int kind) throws xTalkException {
		int[] tags = f.tags;
		if(v<tags.length && (tags[v]&NUMBER)!=0){
			f.numbers[v] = value;
			tags[v] = kind|NUMBER|DIRTY;
			return true;
		}
		int s = slot(f, v);
		if(s==0 || TalkCollection.isCollection(value(f, s))) return false;
		tag(f, v, value, kind|NUMBER|DIRTY);
		return true;
	}

	/** get: a computed number */
	static void getNumber(Frame f, int v, double value, int kind) throws xTalkException {
		if(slot(f, v)==0) TTalk.setVariable(f.mem, "it", "");
		tag(f, v, value, kind|NUMBER|DIRTY);
	}

	static Result returnedNumber(Frame f, double value, int kind){ return returned(f, rawText(value, kind), true); }

	/** put variable into variable: a computed number stays one */
	static boolean copy(Frame f, int to, int from) throws xTalkException {
		int tag = from<f.tags.length ? f.tags[from] : 0;
		if((tag&DIRTY)!=0) return assignNumber(f, to, f.numbers[from], tag&KIND);
		int s = slot(f, from);
		if(s==0) return false;
		boolean raw = isRaw(f, from, s);
		if(!assign(f, to, value(f, s), raw)) return false;
		if((tag&NUMBER)!=0 && !raw) tag(f, to, f.numbers[from], tag);
		return true;
	}

	/** return variable: a computed number stays one for a function; null: no such variable */
	static Result returnedVariable(Frame f, int v) throws xTalkException {
		int s = slot(f, v);
		if(s==0) return null;
		see(f, v, s);
		return returned(f, value(f, s), isRaw(f, v, s));
	}

	/** x mod y, y not 0 */
	static double modNumber(double x, double y, int a, int b){
		if(((a|b)&REAL)==0) return Math.copySign((double)((int)x%(int)y), x); //kind 0 is a 32-bit integer or -0; the sign of the remainder, -0 too, is that of x
		double value = Math.IEEEremainder(x, y);
		if(value<0) return value+y;
		return value==0 ? 0 : value;
	}

	/** x div y, y not 0 */
	static double divNumber(double x, double y){
		double value = x/y;
		return value<0 ? Math.ceil(value) : Math.floor(value);
	}

	static boolean isLess(double x, double y, int kinds){ return (kinds&REAL)==0 ? x<y : !TTalk.nearlyEqual(x, y) && x<y; }
	static boolean isLessOrSame(double x, double y, int kinds){ return (kinds&REAL)==0 ? x<=y : TTalk.nearlyEqual(x, y) || x<=y; }
	static boolean isMore(double x, double y, int kinds){ return (kinds&REAL)==0 ? x>y : !TTalk.nearlyEqual(x, y) && x>y; }
	static boolean isMoreOrSame(double x, double y, int kinds){ return (kinds&REAL)==0 ? x>=y : TTalk.nearlyEqual(x, y) || x>=y; }
	static boolean isSame(double x, double y, int kinds){ return (kinds&REAL)==0 ? x==y : TTalk.nearlyEqual(x, y); }
	static boolean isOther(double x, double y, int kinds){ return !isSame(x, y, kinds); }

	// The math functions of TUtil.callSystemFunction1, with the value numericFunctionValue makes of their text

	/** What TUtil.CallSystemFunction notes of any function */
	static void noteFunction(){ BuiltinCommandMessage.noteProperty(""); }

	static double fnSqrt(double v){ return Math.sqrt(v); }
	static double fnSin(double v){ return Math.sin(v); }
	static double fnCos(double v){ return Math.cos(v); }
	static double fnTan(double v){ return Math.tan(v); }
	static double fnAtan(double v){ return Math.atan(v); }
	static double fnExp(double v){ return Math.exp(v); }
	static double fnExp1(double v){ return Math.exp(v)-1.0; }
	static double fnExp2(double v){ return Math.pow(2, v); }
	static double fnLn(double v){ return Math.log(v); }
	static double fnLn1(double v){ return Math.log(1.0+v); }
	static double fnLog2(double v){ return Math.log(v)/Math.log(2); }
	static double fnAbs(double v){ return v-(int)v==0 ? (int)(v>0 ? v : -v) : v>0 ? v : -v; }
	static double fnTrunc(double v){ return int32(v<0 ? Math.ceil(v) : Math.floor(v)); }
	static double fnRound(double v){ return int32(Math.rint(v)); }
	/** computed: v's kind, if it is a computed number: of the math functions only this one takes the argument as text, formatted. NaN: the interpreter sees to it. */
	static double fnRandom(double v, int computed){
		if((computed&REAL)!=0){
			try{ v = Double.parseDouble(TTalk.applyNumberFormat(rawText(v, computed))); }
			catch(NumberFormatException e){ return Double.NaN; }
		}
		if(v!=v) return v;
		long n = (long)Math.floor(v);
		return n<1 ? 1 : 1+(long)(Math.random()*n);
	}
	static double fnMax(double a, double b){ return whole(b>a ? b : a); }
	static double fnMin(double a, double b){ return whole(b<a ? b : a); }

	/** int32Saturated and saneIntegerOf; -0 goes as text gives it, as 0 */
	private static double int32(double value){
		if(value!=value) return -2147483648d;
		return Math.max(-2147483648d, Math.min(2147483647d, value))+0.0;
	}

	/** v-(int)v == 0 ? Integer.toString((int)v) : Double.toString(v) */
	private static double whole(double v){ return v-(int)v==0 ? (int)v : v; }

	/** The kind of an integer or real function's value; of abs, an integer when it is whole */
	static int realKind(double value){ return norm(value, 1); }
	static int integerKind(double value){ return norm(value, 0); }
	static int absKind(double value){ return norm(value, value==Math.rint(value) ? 0 : 1); }

	// ------------------------------------------------------------------ values (compiled expressions call these)
	// null in, null out. Each gives what the pass of Evalution2 gives; what it reports as an error is null here.

	private static final long NO_INT = Long.MIN_VALUE;

	/** An integer of at most 9 digits, as parseNumber reads it and isRealNumber does not take for a real ("-0" excluded: -0.0). */
	private static long smallInt(String s){
		int n = s.length();
		if(n==0 || n>10) return NO_INT;
		int i = 0;
		boolean minus = s.charAt(0)=='-';
		if(minus){ i = 1; if(n==1) return NO_INT; }
		if(n-i>9) return NO_INT;
		long value = 0;
		for(; i<n; i++){
			char c = s.charAt(i);
			if(c<'0' || c>'9') return NO_INT;
			value = value*10 + (c-'0');
		}
		if(minus){
			if(value==0) return NO_INT;
			return -value;
		}
		return value;
	}

	/** Small numbers are what most computed text is: arguments, indexes. None of these strings is the interned one, as of a
	 *  computed number (TTalk.isIntegerLiteralToken). */
	private static final String[] WHOLE_TEXTS = new String[1280];
	static {
		for(int i=0; i<WHOLE_TEXTS.length; i++){
			String text = Integer.toString(i-256);
			text.intern();
			WHOLE_TEXTS[i] = new String(text);
		}
	}

	private static String wholeText(long value){
		return value>=-256 && value<1024 ? WHOLE_TEXTS[(int)value+256] : Long.toString(value);
	}

	private static String blankZero(String s){ return s.trim().isEmpty() ? "0" : s; }

	private static boolean infinitySign(String s){ return s.equals("∞") || s.equals("�"); }

	static String add(String a, String b){
		if(a==null || b==null) return null;
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT) return wholeText(x+y);
		return addSlow(a, b, false);
	}

	static String sub(String a, String b){
		if(a==null || b==null) return null;
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT) return wholeText(x-y);
		return addSlow(a, b, true);
	}

	private static String addSlow(String a, String b, boolean minus){
		a = blankZero(a); b = blankZero(b);
		if(infinitySign(a) || infinitySign(b)) return null;
		try{
			double x = TTalk.parseNumber(a), y = TTalk.parseNumber(b);
			double value = minus ? x-y : x+y;
			return TTalk.isRealNumber(a) || TTalk.isRealNumber(b) ? TTalk.formatRealNumber(value) : TTalk.formatRawNumber(value);
		}catch(NumberFormatException e){ return null; }
	}

	static String mul(String a, String b){
		if(a==null || b==null) return null;
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT){
			long value = x*y;
			if(value!=0 && Math.abs(value)<1_000_000_000_000_000L) return wholeText(value); //0 may be -0.0
		}
		a = blankZero(a); b = blankZero(b);
		if(infinitySign(a) || infinitySign(b)) return null;
		try{
			double value = TTalk.parseNumber(a)*TTalk.parseNumber(b);
			return TTalk.isRealNumber(a) || TTalk.isRealNumber(b) ? TTalk.formatRealNumber(value) : TTalk.formatRawNumber(value);
		}catch(NumberFormatException e){ return null; }
	}

	static String div(String a, String b){
		if(a==null || b==null) return null;
		a = blankZero(a); b = blankZero(b);
		if(infinitySign(a) || infinitySign(b)) return null;
		try{ return TTalk.formatRealNumber(TTalk.parseNumber(a)/TTalk.parseNumber(b)); }
		catch(NumberFormatException e){ return null; }
	}

	/** x div y */
	static String idiv(String a, String b){
		if(a==null || b==null) return null;
		a = blankZero(a); b = blankZero(b);
		if(infinitySign(a) || infinitySign(b)) return null;
		try{
			double y = TTalk.parseNumber(b);
			if(y==0) return null;
			double value = TTalk.parseNumber(a)/y;
			return TTalk.formatRawNumber(value<0 ? Math.ceil(value) : Math.floor(value));
		}catch(NumberFormatException e){ return null; }
	}

	static String mod(String a, String b){
		if(a==null || b==null) return null;
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT && y!=0){
			long value = x%y;
			if(value!=0 || x>=0) return wholeText(value); //a negative x gives -0.0 when it divides evenly
		}
		a = blankZero(a); b = blankZero(b);
		if(infinitySign(a) || infinitySign(b)) return null;
		try{
			if(TTalk.parseNumber(b)==0) return null;
			return TTalk.hyperCardMod(a, b);
		}catch(NumberFormatException e){ return null; }
	}

	static String pow(String a, String b){
		if(a==null || b==null) return null;
		a = blankZero(a); b = blankZero(b);
		if(b.equals("∞")) return null;
		try{ return TTalk.formatRealNumber(Math.pow(TTalk.parseNumber(a), TTalk.parseNumber(b))); }
		catch(NumberFormatException e){ return null; }
	}

	static String neg(String s){
		if(s==null) return null;
		boolean empty = s.trim().isEmpty();
		try{
			double value = empty ? 0 : -1.0*(Double.valueOf(s));
			return TTalk.isRealNumber(s) ? TTalk.formatRealNumber(value) : TTalk.formatRawNumber(value);
		}catch(NumberFormatException e){ return null; }
	}

	/** applyNumberFormat under the default format */
	static String formatted(String s){
		if(s==null) return null;
		int n = s.length();
		if(n>=1 && n<=15){
			char c = s.charAt(0);
			int i = 0;
			if(c=='-'){ i = 1; if(n==1) return TTalk.applyNumberFormat(s); c = s.charAt(1); }
			if(c>'0' && c<='9' || c=='0' && n==1){
				for(i++; i<n; i++){
					c = s.charAt(i);
					if(c<'0' || c>'9') return TTalk.applyNumberFormat(s);
				}
				return s;
			}
		}
		return TTalk.applyNumberFormat(s);
	}

	static String cat(String a, String b){
		if(a==null || b==null || TalkCollection.isCollection(a) || TalkCollection.isCollection(b)) return null;
		return a+b;
	}

	/** && */
	static String cat2(String a, String b){
		if(a==null || b==null || TalkCollection.isCollection(a) || TalkCollection.isCollection(b)) return null;
		return a+" "+b;
	}

	private static String bool(boolean value){ return value ? "true" : "false"; }

	/** op: 0 <, 1 <=, 2 >, 3 >= */
	private static String order(String a, String b, int op){
		if(a==null || b==null) return null;
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT){
			switch(op){
			case 0: return bool(x<y);
			case 1: return bool(x<=y);
			case 2: return bool(x>y);
			default: return bool(x>=y);
			}
		}
		if(a.equals("")) a = "0";
		if(b.equals("")) b = "0";
		if(TTalk.mayBeNumber(a) && TTalk.mayBeNumber(b)){
			try{
				double p = TTalk.parseNumber(a), q = TTalk.parseNumber(b);
				boolean same = TTalk.nearlyEqual(p, q);
				switch(op){
				case 0: return bool(!same && p<q);
				case 1: return bool(same || p<=q);
				case 2: return bool(!same && p>q);
				default: return bool(same || p>=q);
				}
			}catch(NumberFormatException e){ /* text below */ }
		}
		int cmp = TTalk.compareText(a, b);
		switch(op){
		case 0: return bool(cmp<0);
		case 1: return bool(cmp<=0);
		case 2: return bool(cmp>0);
		default: return bool(cmp>=0);
		}
	}
	static String lt(String a, String b){ return order(a, b, 0); }
	static String le(String a, String b){ return order(a, b, 1); }
	static String gt(String a, String b){ return order(a, b, 2); }
	static String ge(String a, String b){ return order(a, b, 3); }

	private static boolean equal(String a, String b){
		long x = smallInt(a), y = smallInt(b);
		if(x!=NO_INT && y!=NO_INT) return x==y;
		if(TTalk.mayBeNumber(a) && TTalk.mayBeNumber(b)){
			try{ return TTalk.nearlyEqual(TTalk.parseNumber(a), TTalk.parseNumber(b)); }
			catch(NumberFormatException e){ /* text below */ }
		}
		return TTalk.sameText(a, b);
	}
	static String eq(String a, String b){ return a==null || b==null ? null : bool(equal(a, b)); }
	static String ne(String a, String b){ return a==null || b==null ? null : bool(!equal(a, b)); }

	/** a contains b */
	static String contains(String a, String b){ return a==null || b==null ? null : bool(TTalk.containsText(a, b)); }
	/** a is not in b */
	static String notIn(String a, String b){ return a==null || b==null ? null : bool(!TTalk.containsText(b, a)); }

	private static int logical(String s){
		if(s.equalsIgnoreCase("true")) return 1;
		return s.equalsIgnoreCase("false") ? 0 : -1;
	}
	static String and(String a, String b){
		if(a==null || b==null) return null;
		int x = logical(a), y = logical(b);
		return x<0 || y<0 ? null : bool(x==1 && y==1);
	}
	static String or(String a, String b){
		if(a==null || b==null) return null;
		int x = logical(a), y = logical(b);
		return x<0 || y<0 ? null : bool(x==1 || y==1);
	}
	static String not(String s){
		if(s==null) return null;
		int x = logical(s);
		return x<0 ? null : bool(x==0);
	}

	/** A number copy of a quoted number (see TTalk.isQuotedNumberLiteral): a value is not the literal's token */
	static String copy(String s){ return new String(s); }

	static String chunk(String mode, String first, String container){
		if(first==null || container==null || TalkCollection.isCollection(container) || !chunkNumber(first)) return null;
		return TTalk.getChunk(mode, first, null, container);
	}

	static String chunkRange(String mode, String first, String last, String container){
		if(first==null || last==null || container==null || TalkCollection.isCollection(container) || !chunkNumber(first) || !chunkNumber(last)) return null;
		return TTalk.getChunk(mode, first, last, container);
	}

	private static boolean chunkNumber(String s){ return smallInt(s)!=NO_INT; }

	/** the number of lines of value */
	static String count(String mode, String value){
		if(value==null || TalkCollection.isCollection(value)) return null;
		return Integer.toString(TTalk.getNumberOfChunk(mode, value));
	}

	/** the first|…|last chunk of value (evalRelativeChunkAt) */
	static String relative(String mode, String which, String value){
		if(value==null || TalkCollection.isCollection(value)) return null;
		int ordinal = TTalk.ordinalChunkNumber(which);
		if(which.equalsIgnoreCase("last") || which.equalsIgnoreCase("middle")){
			int chunk = TTalk.getNumberOfChunk(mode+"s", value);
			if(chunk<=0) return "";
			return TTalk.getChunk(mode, Integer.toString(which.equalsIgnoreCase("last") ? chunk : chunk/2+1), "", value);
		}
		return ordinal>0 ? TTalk.getChunk(mode, Integer.toString(ordinal), "", value) : null;
	}

	/** The arguments as evalBracketsAndCalls passes them: computed numbers formatted (mask), their values noted for those who want them */
	private static void arguments(String[] values, int mask){
		String[] raw = null;
		for(int i=0; mask!=0 && i<values.length; i++, mask>>>=1){
			if((mask&1)==0) continue;
			String value = values[i], text = formatted(value);
			if(!text.equals(value)){
				if(raw==null) raw = new String[values.length];
				raw[i] = value;
			}
			values[i] = text;
		}
		TTalk.noteRawArgs(values, raw);
	}

	/** A built-in function of PURE_FUNCTIONS */
	static String function(Frame f, String name, String[] values, int mask){
		for(String v : values) if(v==null) return null;
		if(values.length==1 && (name=="max" || name=="min" || name=="sum" || name=="average") && TTalk.plainNumberItems(values[0], -1)==null) return null;
		try{
			arguments(values, mask);
			Result r = TUtil.CallSystemFunction(name, values, f.target, f.mem, true);
			if(r==null || r.theResult==null) return null;
			if(!TTalk.numericFuncSet.contains(name)) return r.theResult;
			return TTalk.isNumericFunctionResult(name, r.theResult) ? TTalk.numericFunctionValue(name, r.theResult) : null;
		}catch(xTalkException | RuntimeException e){ return null; }
	}

	/** In the mask of {@link #call}: a name of TTalk.numericFuncSet */
	private static final int NUMERIC_NAME = 1<<31;

	/** Any other function: the message goes along the path, as in evalBracketsAndCalls. Result.numeric: a computed number */
	static Result call(Frame f, int line, String name, String[] values, int mask) throws xTalkException {
		sync(f);
		ScriptDebugger.atLine(f.mem, line);
		arguments(values, mask);
		Result r = f.talk.callFunction(name, values, f.object, f.target, f.mem);
		if(f.calls==null) f.calls = new ArrayList<Object[]>(2);
		f.calls.add(new Object[]{name, null, r});
		if(r!=null && r.ret==-1){
			f.calls = null;
			TTalk.exitingToHyperCard = true;
			throw new ExitToHyperCard();
		}
		Result value = new Result();
		value.theResult = "";
		if(r!=null && r.theResult!=null){
			if(r.numeric){ value.theResult = r.theResult; value.numeric = true; }
			else if(mask<0 && TTalk.isNumericFunctionResult(name, r.theResult)){ value.theResult = TTalk.numericFunctionValue(name, r.theResult); value.numeric = true; }
			else value.theResult = r.theResult;
		}
		return value;
	}

	/** A statement that is a message to whoever takes it; null: an argument has no value here, and nothing has happened */
	static Result command(Frame f, int line, String name, String[] values, int mask, int words) throws xTalkException {
		for(String v : values) if(v==null) return null;
		sync(f);
		ScriptDebugger.atLine(f.mem, line);
		TTalk.setStatementSource(f.scriptList.get(line));
		TTalk.lexicalScope(f.mem, f.object, f.scriptList, line);
		arguments(values, mask);
		Words w = (Words)f.sc.constants.get(words);
		return f.talk.compiledCommand(name, values, f.object, f.target, f.mem, w.words, w.types);
	}

	/** The same as the one-line then/else statement of an if: an error goes through the handling of that line's own run (TTalk.runInlineBranch), on to the if */
	static Result inlineCommand(Frame f, int line, String name, String[] values, int mask, int words, int branch) throws xTalkException {
		ArrayList<String> lines = ((TTalk.IfBranch)f.sc.constants.get(branch)).inlineLines();
		try{ return command(f, line, name, values, mask, words); }
		catch(xTalkException e){ f.talk.handleScriptError(e, f.object, lines, 0, f.mem, 1); }
		catch(RuntimeException e){
			e.printStackTrace();
			f.talk.handleScriptError(new xTalkException("Cannot interpret {0}", lines.get(0).trim()), f.object, lines, 0, f.mem, 1);
		}
		return new Result();
	}

	/** global a, b */
	static void declare(Frame f, int names) throws xTalkException {
		if(f.taggedCount>0) sync(f); //a local of that name in a number was not the global
		for(String name : (String[])f.sc.constants.get(names)) TTalk.declareGlobal(f.mem, name);
	}

	/** A call's value used as text */
	static String shown(Result r){ return r.numeric ? formatted(r.theResult) : r.theResult; }

	/** The number of a call's value as an argument: the interpreter sees to one that a format would change */
	static String argument(Result r){
		if(!r.numeric) return r.theResult;
		String text = formatted(r.theResult);
		return text.equals(r.theResult) ? text : null;
	}

	/** put a call's value into a variable */
	static boolean assignCall(Frame f, int v, Result r) throws xTalkException { return assign(f, v, r.theResult, r.numeric); }
	static void getCall(Frame f, int v, Result r) throws xTalkException { getInto(f, v, r.theResult, r.numeric); }
	static Result returnedCall(Frame f, Result r){ return returned(f, r.theResult, r.numeric); }

	static boolean notVariable(Frame f, String word){ return !f.mem.treeset.contains(word); }

	/** the ticks */
	static String clock(Frame f, String name){
		try{
			Result r = TUtil.CallSystemFunction(name, new String[0], f.target, f.mem, true);
			if(r==null || r.theResult==null || !TTalk.isNumericFunctionResult(name, r.theResult)) return null;
			return TTalk.numericFunctionValue(name, r.theResult);
		}catch(xTalkException | RuntimeException e){ return null; }
	}

	/** the itemDelimiter and the like */
	static String property(Frame f, String name){
		try{ return TUtil.CallSystemFunction(name, null, f.target, f.mem, true).theResult; }
		catch(xTalkException | RuntimeException e){ return null; }
	}

	// ------------------------------------------------------------------ expression trees

	/** Not something this compiler takes: the statement stays with the interpreter. */
	private static final class Reject extends Exception {
		private static final long serialVersionUID = 1L;
		Reject(String why){ super(why, null, false, TRACE); }
	}

	private abstract static class Node {
		/** a computed number: formatted when it becomes text */
		boolean numeric(){ return false; }
		int depth(){ return 1; }
		abstract void emit(Gen g, JitClassFile.Code c) throws Reject;
		/** The number registers from n on that {@link #emitNumber} and {@link #emitTruth} use */
		int registers(){ return 1; }
		/** Register n = its number and kind; to fail if it has none, with nothing changed. The stack is empty and nothing here
		 *  branches but to fail, as a register does not last over a label. comparing: as a comparison reads it. */
		void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject {
			c.var(JitClassFile.ALOAD, 0);
			emit(g, c);
			c.iconst(comparing ? 1 : 0);
			c.invokestatic(RT, "parse", "(L"+FRAME+";L"+STR+";Z)I");
			g.kindOrFail(c, fail, n);
			c.var(JitClassFile.ALOAD, 0);
			c.getfield(FRAME, "number", "D");
			c.var(JitClassFile.DSTORE, Gen.number(n));
		}
		/** The kind of register n = 1 or 0 by its value as a condition; to fail if it is no boolean. As {@link #emitNumber}. */
		void emitTruth(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n) throws Reject {
			emit(g, c);
			c.invokestatic(RT, "truth", "(L"+STR+";)I");
			g.kindOrFail(c, fail, n);
		}
		/** {@link #emitTruth} is worth trying before the strings: a comparison of numbers is in it */
		boolean truthByNumbers(){ return false; }
	}

	private static final class Lit extends Node {
		final String text;
		final boolean copy;
		/** As a number */
		final int kind;
		Lit(String text, boolean copy){
			this.text = text;
			this.copy = copy;
			kind = kindOf(text);
		}
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject {
			g.ldc(c, text);
			if(copy) c.invokestatic(RT, "copy", "(L"+STR+";)L"+STR+";");
		}
		@Override void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject {
			if(kind<0 || comparing && (kind&ODD)!=0){
				super.emitNumber(g, c, fail, n, comparing);
				return;
			}
			c.dconst(valueOf(text));
			c.var(JitClassFile.DSTORE, Gen.number(n));
			c.iconst(kind);
			c.var(JitClassFile.ISTORE, Gen.kind(n));
		}
	}

	private static final int LONE = 0, ARITH = 1, TEXT = 2;

	private static final class Var extends Node {
		final int index;
		int use;
		Var(int index, int use){ this.index = index; this.use = use; }
		@Override void emit(Gen g, JitClassFile.Code c){
			c.var(JitClassFile.ALOAD, 0);
			c.iconst(index);
			c.invokestatic(RT, use==LONE ? "lone" : use==ARITH ? "arith" : "text", "(L"+FRAME+";I)L"+STR+";");
		}
		@Override void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject {
			if(use==ARITH) emitHeld(g, c, fail, n, comparing);
			else super.emitNumber(g, c, fail, n, comparing);
		}
		/** The number as it is, a computed one with its precision */
		void emitHeld(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing){
			c.var(JitClassFile.ALOAD, 0);
			c.iconst(index);
			c.invokestatic(RT, comparing ? "compared" : "numbered", "(L"+FRAME+";I)I");
			g.kindOrFail(c, fail, n);
			c.var(JitClassFile.ALOAD, 0);
			c.iconst(index);
			c.invokestatic(RT, "numberOf", "(L"+FRAME+";I)D");
			c.var(JitClassFile.DSTORE, Gen.number(n));
		}
	}

	/** (…) */
	private static final class Paren extends Node {
		final Node inner;
		Paren(Node inner){ this.inner = inner; }
		@Override boolean numeric(){ return inner.numeric(); }
		@Override int depth(){ return inner.depth(); }
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject { inner.emit(g, c); }
		@Override int registers(){ return inner.registers(); }
		@Override void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject { inner.emitNumber(g, c, fail, n, comparing); }
		@Override void emitTruth(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n) throws Reject { inner.emitTruth(g, c, fail, n); }
		@Override boolean truthByNumbers(){ return inner.truthByNumbers(); }
	}

	/** A helper of String arguments */
	private static class Call extends Node {
		final String method;
		final boolean numeric;
		final Node[] args;
		final String prefix;
		Call(String method, boolean numeric, String prefix, Node... args){ this.method = method; this.numeric = numeric; this.prefix = prefix; this.args = args; }
		@Override boolean numeric(){ return numeric; }
		@Override int depth(){
			int d = 1, k = prefix!=null ? 1 : 0;
			for(Node n : args){ d = Math.max(d, k+n.depth()); k++; }
			return d;
		}
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject {
			StringBuilder descriptor = new StringBuilder("(");
			if(prefix!=null){ g.ldc(c, prefix); descriptor.append('L').append(STR).append(';'); }
			for(Node n : args){ n.emit(g, c); descriptor.append('L').append(STR).append(';'); }
			c.invokestatic(RT, method, descriptor.append(")L").append(STR).append(';').toString());
		}
	}

	/** Arithmetic: on strings where the value goes on as text, on numbers where nothing can see them in between. */
	private static final class Arith extends Call {
		Arith(String method, Node... args){ super(method, true, null, args); }
		@Override int registers(){ return args.length==1 ? args[0].registers() : Math.max(args[0].registers(), 1+args[1].registers()); }
		@Override void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject {
			args[0].emitNumber(g, c, fail, n, false);
			int d = Gen.number(n), k = Gen.kind(n);
			if(args.length==1){ //neg: blank is +0 to it, what is more left to the interpreter
				c.var(JitClassFile.ILOAD, k);
				c.iconst(ODD);
				c.op(JitClassFile.IAND);
				c.jump(JitClassFile.IFNE, fail);
				c.var(JitClassFile.DLOAD, d);
				c.op(JitClassFile.DNEG);
				c.var(JitClassFile.DSTORE, d);
				c.var(JitClassFile.DLOAD, d);
				c.var(JitClassFile.ILOAD, k);
				c.op(JitClassFile.ICONST_0);
				c.invokestatic(RT, "kind", "(DII)I");
				c.var(JitClassFile.ISTORE, k);
				return;
			}
			args[1].emitNumber(g, c, fail, n+1, false);
			int d2 = Gen.number(n+1), k2 = Gen.kind(n+1);
			if(method=="mod" || method=="idiv"){
				c.var(JitClassFile.DLOAD, d2);
				c.op(JitClassFile.DCONST_0);
				c.op(JitClassFile.DCMPL);
				c.jump(JitClassFile.IFEQ, fail);
			}
			c.var(JitClassFile.DLOAD, d);
			c.var(JitClassFile.DLOAD, d2);
			switch(method){
			case "add": c.op(JitClassFile.DADD); break;
			case "sub": c.op(JitClassFile.DSUB); break;
			case "mul": c.op(JitClassFile.DMUL); break;
			case "div": c.op(JitClassFile.DDIV); break;
			case "pow": c.invokestatic("java/lang/Math", "pow", "(DD)D"); break;
			case "idiv": c.invokestatic(RT, "divNumber", "(DD)D"); break;
			default:
				c.var(JitClassFile.ILOAD, k);
				c.var(JitClassFile.ILOAD, k2);
				c.invokestatic(RT, "modNumber", "(DDII)D");
				break;
			}
			c.var(JitClassFile.DSTORE, d);
			c.var(JitClassFile.DLOAD, d);
			if(method=="div" || method=="pow"){
				c.op(JitClassFile.ICONST_0+1);
				c.invokestatic(RT, "norm", "(DI)I");
			}
			else if(method=="idiv"){
				c.op(JitClassFile.ICONST_0);
				c.invokestatic(RT, "norm", "(DI)I");
			}
			else{
				c.var(JitClassFile.ILOAD, k);
				c.var(JitClassFile.ILOAD, k2);
				c.invokestatic(RT, "kind", "(DII)I");
			}
			c.var(JitClassFile.ISTORE, k);
		}
	}

	/** A number of a comparison: worth trying only with what is a number more often than not */
	private static boolean comparesAsNumber(Node n){
		if(n.numeric()) return true;
		Node p = strip(n);
		if(p instanceof Var v) return v.use==ARITH;
		return p instanceof Lit l && l.kind>=0 && (l.kind&ODD)==0;
	}

	/** A comparison: on numbers if both sides turn out to be numbers a comparison reads as such, or else on strings */
	private static final class Compare extends Call {
		Compare(String method, Node a, Node b){ super(method, false, null, a, b); }
		@Override boolean truthByNumbers(){ return comparesAsNumber(args[0]) && comparesAsNumber(args[1]); }
		@Override int registers(){ return Math.max(args[0].registers(), 1+args[1].registers()); }
		@Override void emitTruth(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n) throws Reject {
			if(!truthByNumbers()){
				super.emitTruth(g, c, fail, n);
				return;
			}
			args[0].emitNumber(g, c, fail, n, true);
			args[1].emitNumber(g, c, fail, n+1, true);
			c.var(JitClassFile.DLOAD, Gen.number(n));
			c.var(JitClassFile.DLOAD, Gen.number(n+1));
			c.var(JitClassFile.ILOAD, Gen.kind(n));
			c.var(JitClassFile.ILOAD, Gen.kind(n+1));
			c.op(JitClassFile.IOR);
			String name;
			switch(method){
			case "lt": name = "isLess"; break;
			case "le": name = "isLessOrSame"; break;
			case "gt": name = "isMore"; break;
			case "ge": name = "isMoreOrSame"; break;
			case "eq": name = "isSame"; break;
			default: name = "isOther"; break;
			}
			c.invokestatic(RT, name, "(DDI)Z");
			c.var(JitClassFile.ISTORE, Gen.kind(n));
		}
	}

	/** and, or, not */
	private static final class Logic extends Call {
		Logic(String method, Node... args){ super(method, false, null, args); }
		@Override boolean truthByNumbers(){ return args[0].truthByNumbers() || args.length>1 && args[1].truthByNumbers(); }
		@Override int registers(){ return args.length==1 ? args[0].registers() : Math.max(args[0].registers(), 1+args[1].registers()); }
		@Override void emitTruth(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n) throws Reject {
			int k = Gen.kind(n);
			args[0].emitTruth(g, c, fail, n);
			if(args.length==1){
				c.var(JitClassFile.ILOAD, k);
				c.op(JitClassFile.ICONST_0+1);
				c.op(JitClassFile.IXOR);
			}
			else{ //both sides: one that is no boolean is an error whatever the other says
				args[1].emitTruth(g, c, fail, n+1);
				c.var(JitClassFile.ILOAD, k);
				c.var(JitClassFile.ILOAD, Gen.kind(n+1));
				c.op(method=="and" ? JitClassFile.IAND : JitClassFile.IOR);
			}
			c.var(JitClassFile.ISTORE, k);
		}
	}

	/** A value computed before the rest of the expression (an expression with calls goes in the interpreter's order) */
	private static final class Temp extends Node {
		final int slot;
		final Node inner;
		Temp(int slot, Node inner){ this.slot = slot; this.inner = inner; }
		@Override boolean numeric(){ return inner.numeric(); }
		@Override void emit(Gen g, JitClassFile.Code c){ c.var(JitClassFile.ALOAD, slot); }
	}

	/** The value of a call: whether it is a computed number shows when it has been made */
	private static final class CallValue extends Node {
		final int slot;
		String use = "raw";
		CallValue(int slot){ this.slot = slot; }
		@Override void emit(Gen g, JitClassFile.Code c){
			c.var(JitClassFile.ALOAD, slot);
			if(use.equals("raw")) c.getfield(RESULT, "theResult", "L"+STR+";");
			else c.invokestatic(RT, use, "(L"+RESULT+";)L"+STR+";");
		}
	}

	/** The functions of a number with a method here, by the method that gives the kind of the value */
	private static final java.util.Map<String,String> NUMBER_FUNCTIONS = java.util.Map.ofEntries(
		java.util.Map.entry("sqrt", "realKind"), java.util.Map.entry("sin", "realKind"), java.util.Map.entry("cos", "realKind"),
		java.util.Map.entry("tan", "realKind"), java.util.Map.entry("atan", "realKind"), java.util.Map.entry("exp", "realKind"),
		java.util.Map.entry("exp1", "realKind"), java.util.Map.entry("exp2", "realKind"), java.util.Map.entry("ln", "realKind"),
		java.util.Map.entry("ln1", "realKind"), java.util.Map.entry("log2", "realKind"), java.util.Map.entry("abs", "absKind"),
		java.util.Map.entry("trunc", "integerKind"), java.util.Map.entry("round", "integerKind"), java.util.Map.entry("random", "integerKind"));
	private static final java.util.Set<String> PAIR_FUNCTIONS = java.util.Set.of("max", "min");

	/** A built-in function called directly */
	private static final class Function extends Node {
		final String name;
		final Node[] args;
		final int mask;
		Function(String name, Node[] args, int mask){ this.name = name; this.args = args; this.mask = mask; }
		@Override boolean numeric(){ return TTalk.numericFuncSet.contains(name); }
		@Override int depth(){ return arrayDepth(args)+2; }
		boolean ofNumbers(){ return args.length==1 ? NUMBER_FUNCTIONS.containsKey(name) : args.length==2 && PAIR_FUNCTIONS.contains(name); }
		@Override int registers(){
			if(!ofNumbers()) return 1;
			return args.length==1 ? args[0].registers() : Math.max(args[0].registers(), 1+args[1].registers());
		}
		/** An argument as the function reads it: at full precision, also a variable holding a computed number */
		private void argument(Gen g, JitClassFile.Code c, JitClassFile.Label fail, Node arg, int n) throws Reject {
			if(arg instanceof Var v && v.use==LONE && name!="random") v.emitHeld(g, c, fail, n, false);
			else arg.emitNumber(g, c, fail, n, false);
		}
		@Override void emitNumber(Gen g, JitClassFile.Code c, JitClassFile.Label fail, int n, boolean comparing) throws Reject {
			if(!ofNumbers()){
				super.emitNumber(g, c, fail, n, comparing);
				return;
			}
			argument(g, c, fail, args[0], n);
			int d = Gen.number(n);
			if(args.length==2){
				argument(g, c, fail, args[1], n+1);
				c.var(JitClassFile.DLOAD, d);
				c.var(JitClassFile.DLOAD, Gen.number(n+1));
				c.invokestatic(RT, name.equals("max") ? "fnMax" : "fnMin", "(DD)D");
			}
			else c.var(JitClassFile.DLOAD, d);
			if(name=="random"){
				if(args[0].numeric()) c.var(JitClassFile.ILOAD, Gen.kind(n));
				else c.op(JitClassFile.ICONST_0);
				c.invokestatic(RT, "fnRandom", "(DI)D");
				c.var(JitClassFile.DSTORE, d);
				c.var(JitClassFile.DLOAD, d);
				c.var(JitClassFile.DLOAD, d);
				c.op(JitClassFile.DCMPL);
				c.jump(JitClassFile.IFNE, fail);
			}
			else{
				if(args.length==1) c.invokestatic(RT, "fn"+Character.toUpperCase(name.charAt(0))+name.substring(1), "(D)D");
				c.var(JitClassFile.DSTORE, d);
			}
			c.invokestatic(RT, "noteFunction", "()V");
			c.var(JitClassFile.DLOAD, d);
			c.invokestatic(RT, args.length==2 ? "realKind" : NUMBER_FUNCTIONS.get(name), "(D)I");
			c.var(JitClassFile.ISTORE, Gen.kind(n));
		}
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject {
			c.var(JitClassFile.ALOAD, 0);
			g.ldc(c, name);
			g.array(c, args);
			c.iconst(mask);
			c.invokestatic(RT, "function", "(L"+FRAME+";L"+STR+";[L"+STR+";I)L"+STR+";");
		}
	}

	private static int arrayDepth(Node[] args){
		int d = 1;
		for(Node n : args) d = Math.max(d, 3+n.depth());
		return d;
	}

	/** the itemDelimiter */
	private static final class Property extends Node {
		final String name;
		Property(String name){ this.name = name; }
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject {
			c.var(JitClassFile.ALOAD, 0);
			g.ldc(c, name);
			c.invokestatic(RT, "property", "(L"+FRAME+";L"+STR+";)L"+STR+";");
		}
	}

	/** the ticks */
	private static final class Clock extends Node {
		final String name;
		Clock(String name){ this.name = name; }
		@Override boolean numeric(){ return true; }
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject {
			c.var(JitClassFile.ALOAD, 0);
			g.ldc(c, name);
			c.invokestatic(RT, "clock", "(L"+FRAME+";L"+STR+";)L"+STR+";");
		}
	}

	/** The tree under its brackets, if the expression's value comes out of it as a number: no text on the way */
	/** The arguments of a statement that is a message */
	private static final class Command extends Node {
		final String name;
		final Words words;
		final Node[] args;
		final int mask;
		Command(String name, Words words, Node[] args, int mask){ this.name = name; this.words = words; this.args = args; this.mask = mask; }
		@Override int depth(){ return arrayDepth(args)+4; }
		@Override void emit(Gen g, JitClassFile.Code c) throws Reject { throw new Reject("no value"); }
	}

	private static Node numberRoot(Node n){
		while(n instanceof Paren q) n = q.inner;
		return (n instanceof Arith || n instanceof Function f && f.ofNumbers()) && n.registers()<=Gen.NUMBER_REGISTERS ? n : null;
	}

	private static Node strip(Node n){
		while(true){
			if(n instanceof Paren q) n = q.inner;
			else if(n instanceof Temp t) n = t.inner;
			else return n;
		}
	}

	/** An operand of arithmetic: variables as they are */
	private static Node arithOperand(Node n){
		if(strip(n) instanceof Var v) v.use = ARITH;
		return n;
	}

	/** A value that becomes text: formatted if it is a computed number */
	private static Node textOperand(Node n){
		Node p = strip(n);
		if(p instanceof Var v){ v.use = TEXT; return n; }
		if(p instanceof CallValue v){ v.use = "shown"; return n; }
		return n.numeric() ? new Call("formatted", false, null, n) : n;
	}

	/** What is computed before the rest of an expression */
	private abstract static class Step {}
	private static final class ValueStep extends Step {
		final Temp temp;
		ValueStep(Temp temp){ this.temp = temp; }
	}
	private static final class CallStep extends Step {
		final int slot, mask;
		final String name;
		final Node[] args;
		CallStep(int slot, String name, Node[] args, int mask){ this.slot = slot; this.name = name; this.args = args; this.mask = mask; }
	}

	/** An expression's tree and what has to happen before it */
	private static final class Expression {
		final Node node;
		final ArrayList<Step> steps;
		final boolean functions;
		/** Function names that are read as functions only while the handler has no variable of that name (see Sim.ofFunctions) */
		final ArrayList<String> notVariables;
		Expression(Node node, ArrayList<Step> steps, boolean functions, ArrayList<String> notVariables){
			this.node = node;
			this.steps = steps;
			this.functions = functions;
			this.notVariables = notVariables;
		}
		Expression(Node node, Sim sim){ this(node, sim.steps, sim.functions, sim.notVariables); }
		Expression with(Node other){ return new Expression(other, steps, functions, notVariables); }
		int depth(){
			int d = node.depth();
			for(Step s : steps){
				if(s instanceof ValueStep v) d = Math.max(d, v.temp.inner.depth());
				else d = Math.max(d, arrayDepth(((CallStep)s).args)+3);
			}
			return d;
		}
	}

	// ------------------------------------------------------------------ the passes of Evalution2, at compile time

	private static final java.util.Set<String> SPECIAL_WORDS = java.util.Set.of(
		"the", "of", "in", "to", "from", "by", "into", "before", "after", "at", "with", "for", "then", "else",
		"not", "and", "or", "is", "there", "contains", "within", "div", "mod",
		"char", "character", "chars", "characters", "word", "words", "item", "items", "line", "lines",
		"first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth", "last", "mid", "middle", "any",
		"next", "prev", "previous", "this", "number", "id", "me", "target", "selection", "msg", "message", "menu", "menuitem",
		"stack", "window", "url", "file", "data", "box", "titlebar", "menubar", "hypercard", "round", "hzglobalme0",
		"foundline", "foundchunk", "foundtext", "foundfield", "selectedline", "selectedlines", "selectedchunk", "selectedfield", "selectedtext", "clipboard");

	private static boolean plainVariable(String word){
		if(word.isEmpty()) return false;
		char c = word.charAt(0);
		if(!Character.isLetter(c) && c!='_') return false;
		return !SPECIAL_WORDS.contains(word) && !TTalk.isCardWord(word) && !TTalk.isBackgroundWord(word) && !TTalk.isPartWord(word) && !TTalk.isBareGlobalProperty(word);
	}

	private static String constantValue(String word) throws Reject {
		switch(word){
		case "down": return "down";
		case "empty": return "";
		case "false": return "false";
		case "formfeed": return "\f";
		case "carriagereturn": return "\r";
		case "linefeed": return "\r";
		case "pi": return "3.14159265358979323846";
		case "quote": return "\"";
		case "return": return "\n";
		case "colon": return ":";
		case "space": return " ";
		case "tab": return "\t";
		case "comma": return ",";
		case "true": return "true";
		case "up": return "up";
		case "zero": return "0";
		case "one": return "1";
		case "two": return "2";
		case "three": return "3";
		case "four": return "4";
		case "five": return "5";
		case "six": return "6";
		case "seven": return "7";
		case "eight": return "8";
		case "nine": return "9";
		case "ten": return "10";
		default: throw new Reject("constant "+word);
		}
	}

	/** One expression's words; the same scans as the passes, with a tree in place of the value of each word still in play. */
	private static final class Sim {
		final ScriptCode sc;
		final ArrayList<String> words;
		final TTalk.wordType[] types;
		final Node[] nodes;
		/** There are calls: everything goes in the order of the passes, and what comes before a call is computed before it */
		final boolean ordered;
		final ArrayList<Step> steps = new ArrayList<Step>();
		boolean functions;
		final ArrayList<String> notVariables = new ArrayList<String>();
		private int values, calls;
		/** The values that have gone into calls made */
		private final java.util.HashSet<Temp> consumed = new java.util.HashSet<Temp>();

		Sim(ScriptCode sc, ArrayList<String> words, TTalk.wordType[] types){
			this.sc = sc;
			this.words = new ArrayList<String>(words);
			this.types = types.clone();
			nodes = new Node[types.length];
			boolean any = false;
			for(int i=1; i<types.length; i++)
				if(types[i]==TTalk.wordType.LFUNC && !PURE_FUNCTIONS.contains(words.get(i-1))) any = true;
			ordered = any;
		}

		Expression expression(int start, int end) throws Reject {
			return new Expression(eval(start, end), this);
		}

		private Node hoist(Node n) throws Reject {
			if(!ordered || strip(n) instanceof Lit) return n;
			if(values==Gen.VALUE_TEMPS) throw new Reject("too many values");
			Temp t = new Temp(Gen.VALUE_TEMP+values++, n);
			steps.add(new ValueStep(t));
			return t;
		}

		private String word(int i){ return words.get(i); }
		private boolean is(int i, String w){ return word(i).equalsIgnoreCase(w); }
		private boolean value(int i){ return types[i]==TTalk.wordType.STRING; }
		private void nop(int from, int to){ for(int k=from; k<=to; k++){ types[k] = TTalk.wordType.NOP; nodes[k] = null; } }

		/** The tree of the value word i */
		private Node node(int i) throws Reject {
			if(!value(i)) throw new Reject("no value: "+word(i));
			Node n = nodes[i];
			if(n!=null) return n;
			String s = word(i);
			if(TTalk.literalNumber(s, false)) throw new Reject("literal "+s);
			return new Lit(s, false);
		}

		/** Evalution2 */
		Node eval(int start, int end) throws Reject {
			while(end>=start && types[end]==TTalk.wordType.NOP) end--;
			if(end<start) throw new Reject("nothing to evaluate");
			if(start==end && value(start)){
				Node n = nodes[start];
				if(n!=null) return n;
				String s = word(start);
				if(TTalk.literalNumber(s, false)) throw new Reject("literal "+s);
				return new Lit(s, TTalk.isQuotedNumberLiteral(s));
			}
			check(start, end);
			brackets(start, end);
			constantsAndVariables(start, end);
			properties(start, end);
			chunks(start, end);
			ofFunctions(start, end);
			for(int i=start; i<=end; i++){
				if(types[i]==TTalk.wordType.NOP || value(i) || types[i]==TTalk.wordType.OPERATOR || types[i]==TTalk.wordType.OPERATOR_SUB) continue;
				throw new Reject("word left: "+word(i));
			}
			unary(start, end);
			arithmetic(start, end);
			comparisons(start, end);
			logic(start, end);
			for(int i=start+1; i<=end; i++) if(types[i]!=TTalk.wordType.NOP) throw new Reject("left over: "+word(i));
			return node(start);
		}

		/** Only words whose passes are here */
		private void check(int start, int end) throws Reject {
			for(int i=start; i<=end; i++){
				String w = word(i);
				switch(types[i]){
				case NOP: case STRING: case CONST: case LBRACKET: case RBRACKET:
					break;
				case OPERATOR: case OPERATOR_SUB: case CHUNK: case OF_CHUNK:
					break;
				case X: case VARIABLE:
					//"of"/"to"/"in" of a chunk
					if(!plainVariable(w) && !w.equals("of") && !w.equals("to") && !w.equals("in") && !w.equals("the") && !w.equals("number") &&
							!w.equals("chars") && !w.equals("characters") && !w.equals("items") && !w.equals("words") && !w.equals("lines"))
						throw new Reject("word "+w);
					break;
				case FUNC: case USER_FUNC: case XFCN:
					if(i>start && types[i-1]==TTalk.wordType.THE_FUNC && CLOCK_FUNCTIONS.contains(w)) break;
					if(i==end || types[i+1]!=TTalk.wordType.LFUNC && !(PURE_FUNCTIONS.contains(w) && word(i+1).equals("of"))) throw new Reject("function "+w);
					break;
				case THE_FUNC: case OF_FUNC: case OF_PROP: case LFUNC: case RFUNC: case COMMA_FUNC: case COMMA:
					break;
				case PROPERTY:
					if(!w.equals("the") && !w.equals("number") && !w.equals("itemdelimiter")) throw new Reject("property "+w);
					break;
				default:
					throw new Reject("word type "+types[i]+" "+w);
				}
			}
		}

		private void brackets(int start, int end) throws Reject {
			for(int i=start; i<=end; i++){
				if(types[i]==TTalk.wordType.LFUNC && i==start){
					int close = close(i, end);
					types[i] = TTalk.wordType.LBRACKET;
					types[close] = TTalk.wordType.RBRACKET;
				}
				if(types[i]==TTalk.wordType.LBRACKET){
					int j = close(i, end);
					Node value = new Paren(eval(i+1, j-1));
					types[i] = TTalk.wordType.STRING;
					nodes[i] = hoist(value);
					words.set(i, "(");
					nop(i+1, j);
					i = j;
				}
				else if(types[i]==TTalk.wordType.LFUNC) i = call(i, start, end);
				else if(types[i]==TTalk.wordType.RBRACKET || types[i]==TTalk.wordType.RFUNC || types[i]==TTalk.wordType.COMMA || types[i]==TTalk.wordType.COMMA_FUNC)
					throw new Reject(word(i));
			}
		}

		/** The ) of the ( at i */
		private int close(int i, int end) throws Reject {
			int depth = 0;
			for(int j=i+1; j<=end; j++){
				if(types[j]==TTalk.wordType.LBRACKET || types[j]==TTalk.wordType.LFUNC) depth++;
				else if(types[j]==TTalk.wordType.RBRACKET || types[j]==TTalk.wordType.RFUNC){
					if(depth==0) return j;
					depth--;
				}
			}
			throw new Reject("no )");
		}

		/** name ( … ) with the ( at i: gives its last word */
		private int call(int i, int start, int end) throws Reject {
			String name = word(i-1);
			TTalk.wordType type = types[i-1];
			if(type!=TTalk.wordType.FUNC && type!=TTalk.wordType.USER_FUNC && type!=TTalk.wordType.XFCN) throw new Reject("call of "+name);
			if(name.equals("number") || name.equals("selectedbutton") || name.equals("matchtext") || name.equals("matchchunk") || name.equals("of")) throw new Reject("call of "+name);
			if(i-2>=start && (is(i-2, "the") || is(i-2, "of"))) throw new Reject("the "+name);
			int j = close(i, end);
			ArrayList<Node> args = new ArrayList<Node>();
			int mask = 0, mark = steps.size();
			if(j>i+1){
				int nest = 0, n = i+1;
				for(int m=i+1; m<=j; m++){
					if(types[m]==TTalk.wordType.LBRACKET || types[m]==TTalk.wordType.LFUNC) nest++;
					else if(m<j && (types[m]==TTalk.wordType.RBRACKET || types[m]==TTalk.wordType.RFUNC)) nest--;
					if(nest==0 && (types[m]==TTalk.wordType.COMMA_FUNC || types[m]==TTalk.wordType.COMMA) || m==j){
						if(n>m-1) throw new Reject("empty argument");
						Node value = eval(n, m-1);
						if(strip(value) instanceof CallValue v) v.use = "argument";
						else if(value.numeric()){
							if(args.size()>=31) throw new Reject("many arguments");
							mask |= 1<<args.size();
						}
						args.add(hoist(value));
						n = m+1;
					}
				}
			}
			Node[] arguments = args.toArray(new Node[0]);
			if(PURE_FUNCTIONS.contains(name) && arguments.length>0){
				functions = true;
				//below a declaration of that name the interpreter has no such function
				if(!sc.neverDeclared(name)) notVariables.add(name);
				nodes[i-1] = hoist(new Function(name, arguments, mask));
			}
			else{
				if(!ordered) throw new Reject("call of "+name);
				if(calls==Gen.CALL_TEMPS) throw new Reject("too many calls");
				checkCallOrder(name, mark, arguments);
				int slot = Gen.CALL_TEMP+calls++;
				steps.add(new CallStep(slot, name, arguments, mask));
				nodes[i-1] = new CallValue(slot);
			}
			types[i-1] = TTalk.wordType.STRING;
			nop(i, j);
			return j;
		}

		/**
		 * If the interpreter has to finish the expression it evaluates it again, but for the calls already made (TTalk.funcReplay),
		 * whose arguments it skips. So what the call about to be made could change must not have been read in the expression
		 * outside the arguments: (a) + f() is left to the interpreter altogether. And a call's own arguments must not start with
		 * a call of the same name: the interpreter takes the first call on the list for the one it has reached.
		 */
		private void checkCallOrder(String name, int mark, Node[] arguments) throws Reject {
			boolean called = false;
			for(int k=0; k<steps.size(); k++){
				Step step = steps.get(k);
				if(step instanceof CallStep call){
					if(k>=mark){
						called = true;
						if(call.name.equals(name)) throw new Reject("nested call of "+name);
					}
				}
				else{
					Temp temp = ((ValueStep)step).temp;
					boolean reads = !(strip(temp) instanceof CallValue);
					if(reads && (k<mark ? !arguments(temp) : called)) throw new Reject("a value read before a call");
				}
			}
			for(Node n : arguments) if(n instanceof Temp t) consumed.add(t);
		}

		private boolean arguments(Temp temp){ return consumed.contains(temp); }

		private void properties(int start, int end) throws Reject {
			for(int i=end-1; i>=start; i--){
				if(types[i]==TTalk.wordType.PROPERTY && word(i)=="the" && word(i+1)=="itemdelimiter" && (i+2>end || !is(i+2, "of")) && (i+3>end || !is(i+3, "of"))){
					nodes[i] = new Property(word(i+1));
					types[i] = TTalk.wordType.STRING;
					nop(i+1, i+1);
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.THE_FUNC) continue;
				if(i>end-1 || !CLOCK_FUNCTIONS.contains(word(i+1))) throw new Reject("the "+(i<end ? word(i+1) : ""));
				nodes[i] = new Clock(word(i+1));
				types[i] = TTalk.wordType.STRING;
				nop(i+1, i+1);
			}
		}

		private void ofFunctions(int start, int end) throws Reject {
			for(int i=end; i>=start; i--){
				if(types[i]!=TTalk.wordType.FUNC) continue;
				if(i+2>end || !is(i+1, "of") || !value(i+2) || !PURE_FUNCTIONS.contains(word(i))) throw new Reject("function "+word(i));
				Node arg = node(i+2);
				int mask = 0;
				if(strip(arg) instanceof CallValue v) v.use = "argument";
				else if(strip(arg) instanceof Var v){ if(v.use==ARITH) throw new Reject("variable next to arithmetic as an argument"); }
				else if(arg.numeric()) mask = 1;
				functions = true;
				Node value = new Function(word(i), new Node[]{arg}, mask);
				int at = i;
				if(i-1>=start && is(i-1, "the") && !value(i-1)) at = i-1;
				//without "the" a declared variable of that name stands in the way (TTalk.lexicalScope). That is a question for the
				//running handler; and a fast statement reads nothing, so a word that could get there through one is left to the
				//interpreter altogether
				else{
					if(!sc.neverDeclared(word(i))) throw new Reject("function "+word(i)+" may be declared a variable");
					notVariables.add(word(i));
				}
				nodes[at] = hoist(value);
				types[at] = TTalk.wordType.STRING;
				nop(at+1, i+2);
				i = at;
			}
		}

		private void constantsAndVariables(int start, int end) throws Reject {
			for(int i=start; i<=end; i++){
				if(types[i]==TTalk.wordType.CONST){
					nodes[i] = new Lit(constantValue(word(i)), false);
					types[i] = TTalk.wordType.STRING;
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.X && types[i]!=TTalk.wordType.VARIABLE) continue;
				String w = word(i);
				if(!plainVariable(w)) continue; //the word of a chunk
				int use = start==end ? LONE : TTalk.nextToArithmetic(words, types, i, start, end) ? ARITH : TEXT;
				nodes[i] = new Var(sc.variable(w), use);
				types[i] = TTalk.wordType.STRING;
			}
		}

		private boolean isChunkWord(int i){
			String s = word(i);
			return s=="char" || s=="character" || s=="item" || s=="word" || s=="line";
		}

		/** The container of a chunk from word {@code from} (evalChunks): what follows up to the first operator. */
		private int containerEnd(int from, int end){
			int k;
			for(k=from; k<=end; k++){
				TTalk.wordType t = types[k];
				if(t!=TTalk.wordType.NOP && t!=TTalk.wordType.STRING && t!=TTalk.wordType.X && t!=TTalk.wordType.VARIABLE) break;
			}
			return k;
		}

		private void chunks(int start, int end) throws Reject {
			for(int i=end; i>=start; i--){
				if(types[i]!=TTalk.wordType.CHUNK) continue;
				if(TTalk.isRelativeChunkSpecifier(word(i)) && i<end && types[i+1]==TTalk.wordType.CHUNK) continue; //first of first word
				if(!isChunkWord(i)) throw new Reject("chunk "+word(i));
				if(i>start && TTalk.isRelativeChunkSpecifier(word(i-1))){
					relativeChunk(i, start, end);
					continue;
				}
				Node first = null, last = null;
				int j;
				for(j=i+2; j<end; j++){
					if(types[j]==TTalk.wordType.NOP) continue;
					if(types[j]==TTalk.wordType.CHUNK) throw new Reject("chunk in a chunk number");
					if(value(j) && nodes[j]==null && (is(j, "of") || is(j, "to"))) throw new Reject("literal of/to");
					if(!value(j) && is(j, "to")){
						first = eval(i+1, j-1);
						for(int k=j+1; k<end; k++){
							if(types[k]==TTalk.wordType.NOP) continue;
							if(types[k]==TTalk.wordType.CHUNK) throw new Reject("chunk in a chunk number");
							if(!value(k) && is(k, "of")){
								last = eval(j+1, k-1);
								j = k;
								break;
							}
						}
						if(last==null) throw new Reject("chunk to without of");
						break;
					}
					if(!value(j) && is(j, "of")){
						first = eval(i+1, j-1);
						break;
					}
				}
				if(first==null) throw new Reject("chunk without of");
				int k = containerEnd(j+2, end);
				int live = 0;
				for(int m=j+1; m<k; m++) if(types[m]!=TTalk.wordType.NOP) live++;
				if(live!=1 && !(live==2 && types[j+1]==TTalk.wordType.OPERATOR)) throw new Reject("chunk container");
				Node container = eval(j+1, k-1);
				nodes[i] = last==null ? new Call("chunk", false, word(i), first, container) : new Call("chunkRange", false, word(i), first, last, container);
				types[i] = TTalk.wordType.STRING;
				nop(i+1, k-1);
			}
			//the number of chars|items|words|lines of|in …
			for(int i=end; i>=start+2; i--){
				if(types[i]==TTalk.wordType.NOP || value(i)) continue;
				String s = word(i);
				if(s=="chars" || s=="characters" || s=="items" || s=="words" || s=="lines"){
					if(!(is(i-1, "of") && is(i-2, "number") && i+1<=end && (is(i+1, "of") || is(i+1, "in")))) throw new Reject("plural chunk");
					int k = containerEnd(i+2, end);
					if(k>end) k = end; else k--;
					int live = 0;
					for(int m=i+2; m<=k; m++) if(types[m]!=TTalk.wordType.NOP) live++;
					if(live!=1) throw new Reject("number of container");
					Node value = eval(i+2, k);
					int at = i-2;
					if(i-3>=start && is(i-3, "the")) at = i-3;
					nodes[at] = new Call("count", false, s, value);
					types[at] = TTalk.wordType.STRING;
					nop(at+1, k);
					i = at;
				}
			}
		}

		/** first word of x (evalRelativeChunkAt); i is the word */
		private void relativeChunk(int i, int start, int end) throws Reject {
			String which = word(i-1);
			if(which.equalsIgnoreCase("any")) throw new Reject("any");
			if(TTalk.isChunkNumberAt(words, types, i-1, start)) throw new Reject("chunk as a chunk number");
			int k = containerEnd(i+2, end);
			if(k>end) k = end; else k--;
			int live = 0;
			for(int m=i+2; m<=k; m++) if(types[m]!=TTalk.wordType.NOP) live++;
			if(live!=1 || !is(i+1, "of")) throw new Reject("relative chunk container");
			Node value = eval(i+2, k);
			int at = i-1;
			if(i-2>=start && is(i-2, "the")) at = i-2;
			nodes[at] = new Call("relative", false, word(i), new Lit(which, false), value);
			types[at] = TTalk.wordType.STRING;
			nop(at+1, k);
		}

		private void unary(int start, int end) throws Reject {
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.OPERATOR) continue;
				String op = word(i);
				if(op=="-"){
					if(i==start || types[i-1]==TTalk.wordType.OPERATOR || types[i-1]==TTalk.wordType.OPERATOR_SUB){
						if(i>end-1) throw new Reject("- at the end");
						nodes[i] = new Arith("neg", arithOperand(node(i+1)));
						types[i] = TTalk.wordType.STRING;
						nop(i+1, i+1);
					}
				}
				else if(op=="not"){
					if(i==start || (types[i-1]!=TTalk.wordType.OPERATOR && types[i-1]!=TTalk.wordType.OPERATOR_SUB) ||
							is(i-1, "and") || is(i-1, "or") || is(i-1, "&")){
						if(i>end-1) throw new Reject("not at the end");
						nodes[i] = new Logic("not", node(i+1));
						types[i] = TTalk.wordType.STRING;
						nop(i+1, i+1);
					}
				}
				else if(op=="within" || op.equals("there")) throw new Reject(op);
			}
		}

		private int left(int i, int start) throws Reject {
			int j = i-1;
			for(; j>=start; j--) if(types[j]==TTalk.wordType.STRING || types[j]==TTalk.wordType.X) return j;
			throw new Reject("no left operand");
		}

		private void binary(int i, int j, int lastUsed, Node value){
			nodes[j] = value;
			types[j] = TTalk.wordType.STRING;
			nop(i, lastUsed);
		}

		private void arithmetic(int start, int end) throws Reject {
			for(int i=end; i>=start; i--){
				if(types[i]==TTalk.wordType.OPERATOR && word(i)=="^"){
					if(i>end-1) throw new Reject("^ at the end");
					int j = left(i, start);
					binary(i, j, i+1, new Arith("pow", arithOperand(node(j)), arithOperand(node(i+1))));
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.OPERATOR) continue;
				String op = word(i);
				if(op=="*" || op=="/" || op=="div" || op=="mod"){
					if(i>end-1) throw new Reject(op+" at the end");
					int j = left(i, start);
					String helper = op=="*" ? "mul" : op=="/" ? "div" : op=="div" ? "idiv" : "mod";
					binary(i, j, i+1, new Arith(helper, arithOperand(node(j)), arithOperand(node(i+1))));
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.OPERATOR) continue;
				String op = word(i);
				if(op=="+" || op=="-"){
					if(i>end-1) throw new Reject(op+" at the end");
					int j = left(i, start);
					binary(i, j, i+1, new Arith(op=="+" ? "add" : "sub", arithOperand(node(j)), arithOperand(node(i+1))));
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]==TTalk.wordType.OPERATOR && word(i)=="&"){
					if(i>end-1) throw new Reject("& at the end");
					int j = left(i, start);
					if(word(i+1)=="&"){
						if(i+2>end) throw new Reject("&& at the end");
						binary(i, j, i+2, new Call("cat2", false, null, textOperand(node(j)), textOperand(node(i+2))));
					}
					else binary(i, j, i+1, new Call("cat", false, null, textOperand(node(j)), textOperand(node(i+1))));
				}
			}
		}

		private boolean nextIs(int i, String w){ return !value(i) && is(i, w); }

		private void comparisons(int start, int end) throws Reject {
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.OPERATOR) continue;
				String op = word(i);
				if(op=="<" || op==">" || op=="≤" || op=="≥"){
					if(i>end-1) throw new Reject("comparison at the end");
					int at = i;
					boolean orEqual = op=="≤" || op=="≥";
					if(types[i+1]==TTalk.wordType.OPERATOR_SUB){
						if(i+1>end-1) throw new Reject("comparison at the end");
						if(op=="<" && word(i+1)==">") continue;
						if(word(i+1)!="=") throw new Reject("comparison "+word(i+1));
						orEqual = true;
						at = i+1;
					}
					int j = left(i, start);
					String helper = (op=="<" || op=="≤") ? (orEqual ? "le" : "lt") : (orEqual ? "ge" : "gt");
					binary(i, j, at+1, new Compare(helper, node(j), node(at+1)));
					i = at+1;
				}
				else if(op=="is"){
					if(i>end-1) continue;
					if(nextIs(i+1, "in")){
						if(i+1>end-1) throw new Reject("is in at the end");
						int j = left(i, start);
						binary(i, j, i+2, new Call("contains", false, null, textOperand(node(i+2)), textOperand(node(j))));
					}
					else if(nextIs(i+1, "a") || nextIs(i+1, "an") || nextIs(i+1, "no")) throw new Reject("is a");
					else if(nextIs(i+1, "not")){
						if(i>end-2) continue;
						if(nextIs(i+2, "in")){
							if(i+2>end-1) throw new Reject("is not in at the end");
							int j = left(i, start);
							binary(i, j, i+3, new Call("notIn", false, null, textOperand(node(j)), textOperand(node(i+3))));
						}
						else if(nextIs(i+2, "a") || nextIs(i+2, "an")) throw new Reject("is not a");
					}
				}
				else if(op=="contains"){
					if(i+1>end) throw new Reject("contains at the end");
					int j = left(i, start);
					binary(i, j, i+1, new Call("contains", false, null, textOperand(node(j)), textOperand(node(i+1))));
				}
			}
			for(int i=start; i<=end; i++){
				if(types[i]!=TTalk.wordType.OPERATOR) continue;
				String op = word(i);
				if(op=="≠"){
					if(i>end-1) throw new Reject("≠ at the end");
					int j = left(i, start);
					binary(i, j, i+1, new Compare("ne", node(j), node(i+1)));
				}
				else if(i+1<=end && !value(i+1) && (op.equals("is") && word(i+1).equals("not") || op.equals("<") && word(i+1).equals(">"))){
					if(i+2>end) throw new Reject("<> at the end");
					int j = left(i, start);
					binary(i, j, i+2, new Compare("ne", node(j), node(i+2)));
				}
				else if(op=="=" || op=="is"){
					if(i>end-1) throw new Reject("= at the end");
					int j = left(i, start);
					binary(i, j, i+1, new Compare("eq", node(j), node(i+1)));
				}
			}
		}

		private void logic(int start, int end) throws Reject {
			for(String op : new String[]{"and", "or"}){
				for(int i=start; i<=end; i++){
					if(types[i]==TTalk.wordType.OPERATOR && word(i)==op){
						if(i>end-1) throw new Reject(op+" at the end");
						int j = left(i, start);
						binary(i, j, i+1, new Logic(op, node(j), node(i+1)));
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ statements

	private static final String RT = "TalkJit", FRAME = "TalkJit$Frame", RESULT = "Result", STR = "java/lang/String";

	/** What a fast statement does with its value. */
	private enum Kind { PUT, GET, AFTER, BEFORE, CHUNK, RETURN, COMMAND, GLOBAL }

	private static final class Plan {
		final Kind kind;
		final int variable, valueVariable;
		final Expression value;
		String mode;
		Node first, last;
		/** of a global statement */
		String[] names;
		Plan(Kind kind, int variable, int valueVariable, Expression value){ this.kind = kind; this.variable = variable; this.valueVariable = valueVariable; this.value = value; }
	}

	/** The words of a line or an expression */
	private static final class Words {
		final ArrayList<String> words;
		final ArrayList<TTalk.wordType> types;
		Words(ArrayList<String> words, ArrayList<TTalk.wordType> types){ this.words = words; this.types = types; }
		int size(){ return words.size(); }
		String get(int i){ return words.get(i); }
		TTalk.wordType[] typeArray(){ return types.toArray(new TTalk.wordType[0]); }
	}

	// ------------------------------------------------------------------ code generation

	private static final class Gen {
		final ScriptCode sc;
		final OObject object;
		final ArrayList<String> script;
		final TreeSet<String> known;
		final JitClassFile cf = new JitClassFile("TalkJitCode", "java/lang/Object", "TalkJit$Code");
		final HashMap<Long,String> blockMethods = new HashMap<Long,String>();
		int methods, fastStatements, slowStatements;
		/** A repeat with or repeat n times line that goes on from Frame.loopFrom (TTalk.compiledCount), or -1 */
		int goesOnAt = -1;

		Gen(ScriptCode sc, OObject object, MemoryData mem){
			this.sc = sc;
			this.object = object;
			script = sc.scriptList;
			known = new TreeSet<String>(mem.treeset);
		}

		byte[] generate(int start, int end) throws Reject {
			String entry = blockMethod(start, end);
			JitClassFile.Code init = cf.method(0x0001, "<init>", "()V");
			init.objectLocal("TalkJitCode");
			init.var(JitClassFile.ALOAD, 0);
			init.invokespecial("java/lang/Object", "<init>", "()V");
			init.op(JitClassFile.RETURN);
			if(!init.finish()) throw new Reject("<init>");
			JitClassFile.Code run = cf.method(0x0001, "run", "(L"+FRAME+";)L"+RESULT+";");
			run.objectLocal("TalkJitCode");
			run.objectLocal(FRAME);
			run.var(JitClassFile.ALOAD, 1);
			run.invokestatic(RT, "nothing", "()L"+RESULT+";");
			run.invokestatic("TalkJitCode", entry, BLOCK);
			run.op(JitClassFile.ARETURN);
			if(!run.finish()) throw new Reject("run");
			return cf.toBytes();
		}

		private static final String BLOCK = "(L"+FRAME+";L"+RESULT+";)L"+RESULT+";";
		private static final int F = 0, R = 1, STMT = 2, RESUME = 3, V = 4, A = 5, B = 6, T = 7, V2 = 8, CAUGHT = 9;
		static final int VALUE_TEMP = 10, VALUE_TEMPS = 12, CALL_TEMP = VALUE_TEMP+VALUE_TEMPS, CALL_TEMPS = 6;
		/** The registers of an expression on numbers: a double and an int each, scratch locals */
		static final int NUMBER_REGISTERS = 14, FIRST_REGISTER = CALL_TEMP+CALL_TEMPS;
		static int number(int n){ return FIRST_REGISTER+3*n; }
		static int kind(int n){ return FIRST_REGISTER+3*n+2; }

		/** Register n's kind = the int on the stack; to fail when it is -1 */
		void kindOrFail(JitClassFile.Code c, JitClassFile.Label fail, int n){
			c.var(JitClassFile.ISTORE, kind(n));
			c.var(JitClassFile.ILOAD, kind(n));
			c.jump(JitClassFile.IFLT, fail);
		}

		void array(JitClassFile.Code c, Node[] values) throws Reject {
			c.iconst(values.length);
			c.op2(JitClassFile.ANEWARRAY, cf.classRef(STR));
			for(int i=0; i<values.length; i++){
				c.op(JitClassFile.DUP);
				c.iconst(i);
				values[i].emit(this, c);
				c.op(JitClassFile.AASTORE);
			}
		}

		void ldc(JitClassFile.Code c, String s) throws Reject {
			int index = cf.string(s);
			if(index<0) throw new Reject("long text");
			c.ldc(index);
		}

		/** The method of the block, made if need be. */
		String blockMethod(int start, int end) throws Reject {
			long key = ((long)start<<32)|end;
			String name = blockMethods.get(key);
			if(name==null){
				name = "b"+(methods++);
				blockMethods.put(key, name);
				new Method(name, start, end, true).generate(start);
			}
			return name;
		}

		/** One generated method: a block or (segment) the part of one that did not fit into the method before. */
		private final class Method {
			final JitClassFile.Code c;
			final int start, end;
			final boolean entry;
			final JitClassFile.Label tryStart, tryEnd;
			boolean open = true;
			int statementLine;
			/** While the fast form of a one-line then/else is made: its IfBranch in ScriptCode.constants, or -1 */
			int inlineBranch = -1;

			Method(String name, int start, int end, boolean entry){
				this.start = start;
				this.end = end;
				this.entry = entry;
				c = cf.method(0x0008, name, BLOCK);
				tryStart = c.label();
				tryEnd = c.label();
			}

			void generate(int from) throws Reject {
				c.objectLocal(FRAME);
				c.objectLocal(RESULT);
				c.intLocal();
				c.intLocal();
				c.objectLocal(STR);
				c.intLocal();
				c.intLocal();
				c.intLocal();
				c.objectLocal(STR);
				c.objectLocal("java/lang/Throwable");
				for(int i=0; i<VALUE_TEMPS; i++) c.objectLocal(STR);
				for(int i=0; i<CALL_TEMPS; i++) c.objectLocal(RESULT);
				if(c.scratchLocals(3*NUMBER_REGISTERS)!=FIRST_REGISTER) throw new Reject("locals");
				for(int local : new int[]{STMT, RESUME, A, B, T}){ c.op(JitClassFile.ICONST_0); c.var(JitClassFile.ISTORE, local); }
				for(int local : new int[]{V, V2, CAUGHT}){ c.op(JitClassFile.ACONST_NULL); c.var(JitClassFile.ASTORE, local); }
				for(int i=0; i<VALUE_TEMPS+CALL_TEMPS; i++){ c.op(JitClassFile.ACONST_NULL); c.var(JitClassFile.ASTORE, VALUE_TEMP+i); }
				if(entry && start!=goesOnAt){
					//as doScriptLines starts: the block is entered only when it may run compiled
					JitClassFile.Label ok = c.label();
					guard(ok);
					c.var(JitClassFile.ALOAD, F);
					c.iconst(start);
					c.iconst(end);
					c.invokestatic(RT, "block", "(L"+FRAME+";II)L"+RESULT+";");
					c.op(JitClassFile.ARETURN);
					c.bind(ok);
				}
				c.bind(tryStart);
				c.op(JitClassFile.NOP);
				int line = from;
				String next = null;
				int nextLine = -1;
				while(line<=end && open){
					if(c.pos()>SEGMENT_BYTES){
						next = "s"+(methods++);
						nextLine = line;
						break;
					}
					line = statement(line);
				}
				c.bind(tryEnd);
				if(open){
					if(next!=null){
						c.var(JitClassFile.ALOAD, F);
						c.var(JitClassFile.ALOAD, R);
						c.invokestatic("TalkJitCode", next, BLOCK);
					}
					else c.var(JitClassFile.ALOAD, R);
					c.op(JitClassFile.ARETURN);
				}
				JitClassFile.Label handler = c.label();
				c.handler(tryStart, tryEnd, handler, "xTalkException");
				c.handler(tryStart, tryEnd, handler, "java/lang/RuntimeException");
				c.bindHandler(handler);
				c.var(JitClassFile.ASTORE, CAUGHT);
				c.var(JitClassFile.ALOAD, F);
				c.var(JitClassFile.ALOAD, CAUGHT);
				c.var(JitClassFile.ILOAD, STMT);
				c.var(JitClassFile.ILOAD, RESUME);
				c.iconst(end);
				c.invokestatic(RT, "caught", "(L"+FRAME+";Ljava/lang/Throwable;III)L"+RESULT+";");
				c.op(JitClassFile.ARETURN);
				if(!c.finish()) throw new Reject("method too long");
				if(next!=null) new Method(next, start, end, false).generate(nextLine);
			}

			/** Jumps to {@code ok} unless the interpreter has to take over. */
			private void guard(JitClassFile.Label ok){
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "slow", "(L"+FRAME+";)Z");
				c.jump(JitClassFile.IFEQ, ok);
			}

			private void marks(int stmt, int resume){
				statementLine = stmt;
				c.iconst(stmt);
				c.var(JitClassFile.ISTORE, STMT);
				c.iconst(resume);
				c.var(JitClassFile.ISTORE, RESUME);
			}

			/** return rest(line, end): this block goes on in the interpreter. */
			private void restHere(int line){
				c.var(JitClassFile.ALOAD, F);
				c.iconst(line);
				c.iconst(end);
				c.invokestatic(RT, "rest", "(L"+FRAME+";II)L"+RESULT+";");
				c.op(JitClassFile.ARETURN);
			}

			/** A control line goes on compiled only when the interpreter need not stop at it. */
			private void controlLine(int line){
				JitClassFile.Label ok = c.label();
				guard(ok);
				restHere(line);
				c.bind(ok);
			}

			private void bailToInterpreter(int line){
				restHere(line);
				open = false;
			}

			/** Generates the statement at {@code line}; gives the line to go on with. */
			private int statement(int line) throws Reject {
				Words w = lineWords(line);
				if(w==null){
					if(line==goesOnAt) throw new Reject("repeat not read");
					bailToInterpreter(line);
					return line;
				}
				if(w.size()==0) return line+1;
				String first = w.get(0);
				if(first=="repeat") return repeat(line, w);
				if(first=="if") return ifStatement(line, w);
				if(first=="exit" || first=="next" || first=="pass"){ exitStatement(line, w); return line+1; }
				marks(line, line);
				Plan plan = plan(w);
				if(plan==null){
					slowStatement(line, first=="return");
					return line+1;
				}
				fastStatements++;
				JitClassFile.Label slow = c.label(), next = c.label();
				fast(plan, slow);
				if(plan.kind==Kind.RETURN) c.op(JitClassFile.ARETURN);
				else{
					if(plan.kind!=Kind.COMMAND && plan.kind!=Kind.GLOBAL) done();
					c.jump(JitClassFile.GOTO, next);
				}
				c.bind(slow);
				slowCall(line, plan.kind==Kind.RETURN);
				c.bind(next);
				return line+1;
			}

			private void done(){
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "done", "(L"+FRAME+";)L"+RESULT+";");
				c.var(JitClassFile.ASTORE, R);
			}

			private void slowStatement(int line, boolean control){
				marks(line, line);
				slowCall(line, control);
			}

			/** The statement in the interpreter. control: return, end... whose ret ends the block, as there. */
			private void slowCall(int line, boolean control){
				slowStatements++;
				c.var(JitClassFile.ALOAD, F);
				c.iconst(line);
				c.invokestatic(RT, "stmt", "(L"+FRAME+";I)L"+RESULT+";");
				c.var(JitClassFile.ASTORE, R);
				endOnRet(control);
			}

			private void endOnRet(boolean anyRet){
				JitClassFile.Label go = c.label();
				c.var(JitClassFile.ALOAD, R);
				c.getfield(RESULT, "ret", "I");
				if(anyRet) c.jump(JitClassFile.IFEQ, go);
				else{
					c.iconst(-1);
					c.jump(JitClassFile.IF_ICMPNE, go);
				}
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ARETURN);
				c.bind(go);
			}

			private void room(Expression e){
				c.maxStack = Math.max(c.maxStack, e.depth()*2+12);
			}

			/** What comes before the expression's tree; to fail if the interpreter has to evaluate the expression. */
			private void steps(Expression e, JitClassFile.Label fail) throws Reject {
				if(e.functions){
					c.var(JitClassFile.ALOAD, F);
					c.invokestatic(RT, "functionsClear", "(L"+FRAME+";)Z");
					c.jump(JitClassFile.IFEQ, fail);
				}
				for(String word : e.notVariables){
					c.var(JitClassFile.ALOAD, F);
					ldc(c, word);
					c.invokestatic(RT, "notVariable", "(L"+FRAME+";L"+STR+";)Z");
					c.jump(JitClassFile.IFEQ, fail);
				}
				if(e.steps.isEmpty()) return;
				noCalls();
				for(Step step : e.steps){
					if(step instanceof ValueStep v){
						v.temp.inner.emit(Gen.this, c);
						c.var(JitClassFile.ASTORE, v.temp.slot);
						c.var(JitClassFile.ALOAD, v.temp.slot);
						c.jump(JitClassFile.IFNULL, fail);
					}
					else{
						CallStep call = (CallStep)step;
						c.var(JitClassFile.ALOAD, F);
						c.iconst(statementLine);
						ldc(c, call.name);
						array(c, call.args);
						c.iconst(TTalk.numericFuncSet.contains(call.name) ? call.mask|NUMERIC_NAME : call.mask);
						c.invokestatic(RT, "call", "(L"+FRAME+";IL"+STR+";[L"+STR+";I)L"+RESULT+";");
						c.var(JitClassFile.ASTORE, call.slot);
					}
				}
			}

			private void noCalls(){
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "noCalls", "(L"+FRAME+";)V");
			}

			/** V = the tree's value; to fail on null. */
			private void valueInV(Node node, JitClassFile.Label fail) throws Reject {
				node.emit(Gen.this, c);
				c.var(JitClassFile.ASTORE, V);
				c.var(JitClassFile.ALOAD, V);
				c.jump(JitClassFile.IFNULL, fail);
			}

			/** Computes the plan's value and does the store; goes to slow when the interpreter has to do it after all. RETURN leaves the Result on the stack. */
			private void fast(Plan p, JitClassFile.Label slow) throws Reject {
				room(p.value);
				if(p.first!=null) c.maxStack = Math.max(c.maxStack, (p.first.depth()+(p.last==null ? 0 : p.last.depth()))*2+12);
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "fast", "(L"+FRAME+";)Z");
				c.jump(JitClassFile.IFEQ, slow);
				steps(p.value, slow);
				Node node = p.value.node;
				if(p.kind==Kind.GLOBAL){
					sc.constants.add(p.names);
					c.var(JitClassFile.ALOAD, F);
					c.iconst(sc.constants.size()-1);
					c.invokestatic(RT, "declare", "(L"+FRAME+";I)V");
					nothing();
					return;
				}
				if(node instanceof Command m){
					sc.constants.add(m.words);
					c.var(JitClassFile.ALOAD, F);
					c.iconst(statementLine);
					ldc(c, m.name);
					array(c, m.args);
					c.iconst(m.mask);
					c.iconst(sc.constants.size()-1);
					if(inlineBranch>=0){
						c.iconst(inlineBranch);
						c.invokestatic(RT, "inlineCommand", "(L"+FRAME+";IL"+STR+";[L"+STR+";III)L"+RESULT+";");
					}
					else c.invokestatic(RT, "command", "(L"+FRAME+";IL"+STR+";[L"+STR+";II)L"+RESULT+";");
					c.var(JitClassFile.ASTORE, CALL_TEMP);
					c.var(JitClassFile.ALOAD, CALL_TEMP);
					c.jump(JitClassFile.IFNULL, slow);
					c.var(JitClassFile.ALOAD, CALL_TEMP);
					c.var(JitClassFile.ASTORE, R);
					endOnRet(false);
					return;
				}
				Node number = p.kind==Kind.PUT || p.kind==Kind.GET || p.kind==Kind.RETURN ? numberRoot(node) : null;
				if(number!=null){
					number.emitNumber(Gen.this, c, slow, 0, false);
					c.var(JitClassFile.ALOAD, F);
					if(p.kind!=Kind.RETURN) c.iconst(p.variable);
					c.var(JitClassFile.DLOAD, number(0));
					c.var(JitClassFile.ILOAD, kind(0));
					switch(p.kind){
					case PUT:
						c.invokestatic(RT, "assignNumber", "(L"+FRAME+";IDI)Z");
						c.jump(JitClassFile.IFEQ, slow);
						break;
					case GET:
						c.invokestatic(RT, "getNumber", "(L"+FRAME+";IDI)V");
						break;
					default:
						c.invokestatic(RT, "returnedNumber", "(L"+FRAME+";DI)L"+RESULT+";");
						break;
					}
					if(!p.value.steps.isEmpty()) noCalls();
					return;
				}
				if(node instanceof Var v && v.use==LONE && p.kind!=Kind.CHUNK && p.kind!=Kind.AFTER && p.kind!=Kind.BEFORE){
					c.var(JitClassFile.ALOAD, F);
					if(p.kind==Kind.RETURN){
						c.iconst(v.index);
						c.invokestatic(RT, "returnedVariable", "(L"+FRAME+";I)L"+RESULT+";");
						c.var(JitClassFile.ASTORE, CALL_TEMP);
						c.var(JitClassFile.ALOAD, CALL_TEMP);
						c.jump(JitClassFile.IFNULL, slow);
						c.var(JitClassFile.ALOAD, CALL_TEMP);
					}
					else{
						c.iconst(p.variable);
						c.iconst(v.index);
						c.invokestatic(RT, "copy", "(L"+FRAME+";II)Z");
						c.jump(JitClassFile.IFEQ, slow);
					}
					if(!p.value.steps.isEmpty()) noCalls();
					return;
				}
				boolean call = strip(node) instanceof CallValue;
				if(call && (p.kind==Kind.PUT || p.kind==Kind.GET || p.kind==Kind.RETURN)){
					c.var(JitClassFile.ALOAD, F);
					if(p.kind!=Kind.RETURN) c.iconst(p.variable);
					c.var(JitClassFile.ALOAD, ((CallValue)strip(node)).slot);
					switch(p.kind){
					case PUT:
						c.invokestatic(RT, "assignCall", "(L"+FRAME+";IL"+RESULT+";)Z");
						c.jump(JitClassFile.IFEQ, slow);
						break;
					case GET:
						c.invokestatic(RT, "getCall", "(L"+FRAME+";IL"+RESULT+";)V");
						break;
					default:
						c.invokestatic(RT, "returnedCall", "(L"+FRAME+";L"+RESULT+";)L"+RESULT+";");
						break;
					}
					if(!p.value.steps.isEmpty()) noCalls();
					return;
				}
				valueInV(node, slow);
				switch(p.kind){
				case PUT:
					c.var(JitClassFile.ALOAD, F);
					c.iconst(p.variable);
					c.var(JitClassFile.ALOAD, V);
					c.iconst(node.numeric() ? 1 : 0);
					c.invokestatic(RT, "assign", "(L"+FRAME+";IL"+STR+";Z)Z");
					c.jump(JitClassFile.IFEQ, slow);
					break;
				case GET:
					c.var(JitClassFile.ALOAD, F);
					c.iconst(p.variable);
					c.var(JitClassFile.ALOAD, V);
					c.iconst(node.numeric() ? 1 : 0);
					c.invokestatic(RT, "getInto", "(L"+FRAME+";IL"+STR+";Z)V");
					break;
				case RETURN:
					c.var(JitClassFile.ALOAD, F);
					c.var(JitClassFile.ALOAD, V);
					c.iconst(node.numeric() ? 1 : 0);
					c.invokestatic(RT, "returned", "(L"+FRAME+";L"+STR+";Z)L"+RESULT+";");
					break;
				case CHUNK:
					p.first.emit(Gen.this, c);
					c.var(JitClassFile.ASTORE, V2);
					c.var(JitClassFile.ALOAD, V2);
					c.jump(JitClassFile.IFNULL, slow);
					c.var(JitClassFile.ALOAD, F);
					c.iconst(p.variable);
					ldc(c, p.mode);
					c.var(JitClassFile.ALOAD, V2);
					if(p.last!=null) p.last.emit(Gen.this, c);
					else c.op(JitClassFile.ACONST_NULL);
					c.var(JitClassFile.ALOAD, V);
					c.invokestatic(RT, "putChunk", "(L"+FRAME+";IL"+STR+";L"+STR+";L"+STR+";L"+STR+";)Z");
					c.jump(JitClassFile.IFEQ, slow);
					break;
				default:
					c.var(JitClassFile.ALOAD, F);
					c.iconst(p.variable);
					c.iconst(p.valueVariable);
					c.var(JitClassFile.ALOAD, V);
					c.iconst(p.kind==Kind.AFTER ? 1 : 0);
					c.invokestatic(RT, "append", "(L"+FRAME+";IIL"+STR+";Z)Z");
					c.jump(JitClassFile.IFEQ, slow);
					break;
				}
				if(!p.value.steps.isEmpty()) noCalls();
			}

			private void exitStatement(int line, Words w) throws Reject {
				String first = w.get(0);
				marks(line, line);
				controlLine(line);
				boolean second = w.size()>=2;
				if(first=="next"){
					if(second && w.get(1).equalsIgnoreCase("repeat")){ retEnd(4); return; }
				}
				else if(first=="pass"){
					if(second) messageEnd(line, w.get(1), 1, false);
					else bailToInterpreter(line);
					return;
				}
				else if(second && w.get(1).equalsIgnoreCase("repeat")){ retEnd(2); return; }
				else if(second){ messageEnd(line, w.get(1), 3, true); return; }
				bailToInterpreter(line);
			}

			private void ownResult(){
				c.var(JitClassFile.ALOAD, R);
				c.invokestatic(RT, "own", "(L"+RESULT+";)L"+RESULT+";");
				c.var(JitClassFile.ASTORE, R);
			}

			private void retEnd(int ret){
				ownResult();
				c.var(JitClassFile.ALOAD, R);
				c.iconst(ret);
				c.putfield(RESULT, "ret", "I");
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ARETURN);
				open = false;
			}

			/** exit word / pass word: when word is the running message; otherwise the interpreter sees to it */
			private void messageEnd(int line, String word, int ret, boolean clear) throws Reject {
				JitClassFile.Label other = c.label();
				c.var(JitClassFile.ALOAD, F);
				ldc(c, word);
				c.invokestatic(RT, "isMessage", "(L"+FRAME+";L"+STR+";)Z");
				c.jump(JitClassFile.IFEQ, other);
				ownResult();
				c.var(JitClassFile.ALOAD, R);
				c.iconst(ret);
				c.putfield(RESULT, "ret", "I");
				if(clear){
					c.var(JitClassFile.ALOAD, R);
					ldc(c, "");
					c.putfield(RESULT, "theResult", "L"+STR+";");
				}
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ARETURN);
				c.bind(other);
				restHere(line);
				open = false;
			}

			// ---- if

			private int ifStatement(int line, Words w) throws Reject {
				marks(line, end);
				controlLine(line);
				TTalk.IfStructure structure;
				try{ structure = TTalk.cachedIfStructure(object, script, line, end, w.words, w.types); }
				catch(xTalkException e){
					bailToInterpreter(line);
					return line;
				}
				marks(line, structure.endLine);
				structure(line, structure);
				endOnRet(true);
				return structure.endLine+1;
			}

			/** Leaves the statement's Result in R */
			private void structure(int line, TTalk.IfStructure structure) throws Reject {
				JitClassFile.Label done = c.label();
				boolean closed = false;
				for(TTalk.IfBranch branch : structure.branches){
					JitClassFile.Label taken = c.label(), skipped = c.label();
					if(branch.condition!=null) condition(line, branch.condition, "if", taken, skipped);
					else closed = true;
					c.bind(taken);
					body(line, branch);
					c.jump(JitClassFile.GOTO, done);
					c.bind(skipped);
					if(closed) break;
				}
				if(!closed) nothing();
				c.bind(done);
			}

			private void body(int line, TTalk.IfBranch branch) throws Reject {
				if(branch.nested!=null) structure(line, branch.nested);
				else if(branch.inlineScript!=null){
					if(branch.inlineScript.isEmpty()) nothing();
					else inline(line, branch);
				}
				else if(branch.bodyStart>=0 && branch.bodyStart<=branch.bodyEnd) callBlock(branch.bodyStart, branch.bodyEnd);
				else nothing();
			}

			private void nothing(){
				c.invokestatic(RT, "nothing", "()L"+RESULT+";");
				c.var(JitClassFile.ASTORE, R);
			}

			private void callBlock(int first, int last) throws Reject {
				String method = blockMethod(first, last);
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "nothing", "()L"+RESULT+";");
				c.invokestatic("TalkJitCode", method, BLOCK);
				c.var(JitClassFile.ASTORE, R);
			}

			private void inline(int line, TTalk.IfBranch branch) throws Reject {
				ArrayList<String> lines = branch.inlineLines();
				Words w = lines.size()==1 ? tokens(lines.get(0), true) : null;
				JitClassFile.Label slow = c.label(), next = c.label();
				if(w!=null && w.size()>0){
					String first = w.get(0);
					if((first=="exit" || first=="next") && w.size()>=2 && w.get(1).equalsIgnoreCase("repeat")){
						//a Result of its own, as the doScriptLines of the then part makes
						JitClassFile.Label ok = c.label();
						guard(ok);
						c.jump(JitClassFile.GOTO, slow);
						c.bind(ok);
						c.invokestatic(RT, "fresh", "()L"+RESULT+";");
						c.op(JitClassFile.DUP);
						c.iconst(first=="exit" ? 2 : 4);
						c.putfield(RESULT, "ret", "I");
						c.op(JitClassFile.ARETURN);
					}
					else{
						Plan plan = first=="repeat" || first=="if" ? null : plan(w);
						//an error of a global is the one of a line of its own (runInlineBranch)
						if(plan!=null && plan.kind!=Kind.GLOBAL){
							fastStatements++;
							sc.constants.add(branch);
							inlineBranch = sc.constants.size()-1;
							fast(plan, slow);
							inlineBranch = -1;
							if(plan.kind==Kind.RETURN) c.op(JitClassFile.ARETURN);
							else{
								if(plan.kind!=Kind.COMMAND) done();
								c.jump(JitClassFile.GOTO, next);
							}
						}
					}
				}
				c.bind(slow);
				sc.constants.add(branch);
				slowStatements++;
				c.var(JitClassFile.ALOAD, F);
				c.iconst(line);
				c.iconst(sc.constants.size()-1);
				c.invokestatic(RT, "inline", "(L"+FRAME+";II)L"+RESULT+";");
				c.var(JitClassFile.ASTORE, R);
				c.bind(next);
			}

			/** Goes to taken or skipped by the value of the expression text, as conditionIsTrue(Evalution(text)). */
			private void condition(int line, String text, String context, JitClassFile.Label taken, JitClassFile.Label skipped) throws Reject {
				Expression e = expression(text);
				JitClassFile.Label generic = c.label();
				if(e!=null){
					//again and again for repeat while, and after an else if the conditions before it may have run handlers
					c.var(JitClassFile.ALOAD, F);
					c.invokestatic(RT, "slow", "(L"+FRAME+";)Z");
					c.jump(JitClassFile.IFNE, generic);
					room(e);
					steps(e, generic);
					if(e.node.truthByNumbers() && e.node.registers()<=NUMBER_REGISTERS){
						JitClassFile.Label strings = c.label();
						e.node.emitTruth(Gen.this, c, strings, 0);
						if(!e.steps.isEmpty()) noCalls();
						c.var(JitClassFile.ILOAD, kind(0));
						c.jump(JitClassFile.IFNE, taken);
						c.jump(JitClassFile.GOTO, skipped);
						c.bind(strings);
					}
					textOperand(e.node).emit(Gen.this, c);
					c.invokestatic(RT, "truth", "(L"+STR+";)I");
					c.var(JitClassFile.ISTORE, T);
					c.var(JitClassFile.ILOAD, T);
					c.jump(JitClassFile.IFLT, generic);
					if(!e.steps.isEmpty()) noCalls();
					c.var(JitClassFile.ILOAD, T);
					c.jump(JitClassFile.IFNE, taken);
					c.jump(JitClassFile.GOTO, skipped);
				}
				c.bind(generic);
				c.var(JitClassFile.ALOAD, F);
				c.iconst(line);
				ldc(c, text);
				ldc(c, context);
				c.invokestatic(RT, "cond", "(L"+FRAME+";IL"+STR+";L"+STR+";)Z");
				c.jump(JitClassFile.IFNE, taken);
				c.jump(JitClassFile.GOTO, skipped);
			}

			/** V = the value of the expression text, as Evalution(text). */
			private void valueOf(int line, String text) throws Reject {
				Expression e = expression(text);
				JitClassFile.Label ok = c.label(), generic = c.label();
				if(e!=null){
					room(e);
					steps(e, generic);
					valueInV(textOperand(e.node), generic);
					if(!e.steps.isEmpty()) noCalls();
					c.jump(JitClassFile.GOTO, ok);
				}
				c.bind(generic);
				c.var(JitClassFile.ALOAD, F);
				c.iconst(line);
				ldc(c, text);
				c.invokestatic(RT, "eval", "(L"+FRAME+";IL"+STR+";)L"+STR+";");
				c.var(JitClassFile.ASTORE, V);
				c.bind(ok);
			}

			// ---- repeat

			/** Goes on from the Frame's count (TTalk.compiledCount): nothing of it may start again now */
			private void goOn(){
				c.var(JitClassFile.ALOAD, F);
				c.getfield(FRAME, "loopFrom", "I");
				c.var(JitClassFile.ISTORE, A);
				c.var(JitClassFile.ALOAD, F);
				c.getfield(FRAME, "loopTo", "I");
				c.var(JitClassFile.ISTORE, B);
			}

			private int repeat(int line, Words w) throws Reject {
				marks(line, line);
				if(line!=goesOnAt) controlLine(line);
				int endRepeat;
				try{ endRepeat = TTalk.findEndRepeat(object, script, line, end); }
				catch(xTalkException e){
					if(line==goesOnAt) throw new Reject("repeat");
					bailToInterpreter(line);
					return line;
				}
				marks(line, endRepeat);
				int size = w.size();
				ArrayList<String> s = w.words;
				JitClassFile.Label loop = c.label(), exit = c.label();
				if(line==goesOnAt && (size<=1 || s.get(1).startsWith("--") || s.get(1).equalsIgnoreCase("forever") || s.get(1).equalsIgnoreCase("while") || s.get(1).equalsIgnoreCase("until")))
					throw new Reject("no count");
				if(size<=1 || s.get(1).startsWith("--") || s.get(1).equalsIgnoreCase("forever")){
					c.bind(loop);
					loopBody(line+1, endRepeat-1, exit);
					c.jump(JitClassFile.GOTO, loop);
				}
				else if(size>=6 && s.get(1).equalsIgnoreCase("for") && s.get(2).equalsIgnoreCase("each")){
					if(line==goesOnAt) throw new Reject("no count");
					bailToInterpreter(line);
					return line;
				}
				else if(s.get(1).equalsIgnoreCase("with")){
					if(!repeatWith(line, w, endRepeat, loop, exit)){
						if(line==goesOnAt) throw new Reject("repeat with");
						bailToInterpreter(line);
						return line;
					}
				}
				else if(s.get(1).equalsIgnoreCase("while") || s.get(1).equalsIgnoreCase("until")){
					boolean until = s.get(1).equalsIgnoreCase("until");
					String prm = conditionText(w, 2);
					JitClassFile.Label run = c.label();
					c.bind(loop);
					//the end of the loop when the condition says so: Result stays as it is
					condition(line, prm, until ? "repeat until" : "repeat while", until ? exit : run, until ? run : exit);
					c.bind(run);
					loopBody(line+1, endRepeat-1, exit);
					c.jump(JitClassFile.GOTO, loop);
				}
				else{
					int next = 1;
					if(s.get(next).equalsIgnoreCase("for")) next++;
					StringBuilder prm = new StringBuilder();
					while(next<size && !s.get(next).startsWith("--") && !s.get(next).equalsIgnoreCase("times")){
						prm.append(' ').append(w.types.get(next)==TTalk.wordType.STRING ? "\""+s.get(next)+"\"" : s.get(next));
						next++;
					}
					if(line==goesOnAt) goOn();
					else{
						valueOf(line, prm.toString());
						c.var(JitClassFile.ALOAD, V);
						c.invokestatic(RT, "times", "(L"+STR+";)I");
						c.var(JitClassFile.ISTORE, B);
						c.op(JitClassFile.ICONST_0);
						c.var(JitClassFile.ISTORE, A);
					}
					c.bind(loop);
					c.var(JitClassFile.ILOAD, A);
					c.var(JitClassFile.ILOAD, B);
					c.jump(JitClassFile.IF_ICMPGE, exit);
					loopBody(line+1, endRepeat-1, exit);
					c.iinc(A, 1);
					c.jump(JitClassFile.GOTO, loop);
				}
				c.bind(exit);
				//as the end of the repeat in doScriptLines
				JitClassFile.Label go = c.label(), leave = c.label();
				c.var(JitClassFile.ALOAD, R);
				c.getfield(RESULT, "ret", "I");
				c.var(JitClassFile.ISTORE, T);
				c.var(JitClassFile.ILOAD, T);
				c.iconst(-1);
				c.jump(JitClassFile.IF_ICMPEQ, leave);
				c.var(JitClassFile.ILOAD, T);
				c.iconst(1);
				c.jump(JitClassFile.IF_ICMPEQ, leave);
				c.var(JitClassFile.ILOAD, T);
				c.iconst(3);
				c.jump(JitClassFile.IF_ICMPNE, go);
				c.bind(leave);
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ARETURN);
				c.bind(go);
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ICONST_0);
				c.putfield(RESULT, "ret", "I");
				return endRepeat+1;
			}

			/** Runs the loop's body once; at a ret that ends the loop, to exit */
			private void loopBody(int first, int last, JitClassFile.Label exit) throws Reject {
				c.var(JitClassFile.ALOAD, F);
				c.invokestatic(RT, "tick", "(L"+FRAME+";)V");
				callBlock(first, last);
				JitClassFile.Label go = c.label();
				c.var(JitClassFile.ALOAD, R);
				c.getfield(RESULT, "ret", "I");
				c.var(JitClassFile.ISTORE, T);
				c.var(JitClassFile.ILOAD, T);
				c.jump(JitClassFile.IFEQ, go);
				c.var(JitClassFile.ILOAD, T);
				c.iconst(4);
				c.jump(JitClassFile.IF_ICMPNE, exit);
				c.var(JitClassFile.ALOAD, R);
				c.op(JitClassFile.ICONST_0);
				c.putfield(RESULT, "ret", "I");
				c.bind(go);
			}

			private String conditionText(Words w, int next){
				StringBuilder prm = new StringBuilder();
				for(; next<w.size() && !w.get(next).startsWith("--"); next++)
					prm.append(' ').append(w.types.get(next)==TTalk.wordType.STRING ? "\""+w.get(next)+"\"" : w.get(next));
				return prm.toString();
			}

			private boolean repeatWith(int line, Words w, int endRepeat, JitClassFile.Label loop, JitClassFile.Label exit) throws Reject {
				ArrayList<String> s = w.words;
				if(s.size()<7 || !s.get(3).equals("=")) return false;
				boolean down = false;
				int separator = -1;
				for(int next=4; next<s.size(); next++){
					if(s.get(next).equalsIgnoreCase("to")){ separator = next; break; }
					if(s.get(next).equalsIgnoreCase("down") && next+1<s.size() && s.get(next+1).equalsIgnoreCase("to")){
						down = true;
						separator = next;
						break;
					}
				}
				if(separator<=4) return false;
				int rangeEndStart = separator+(down ? 2 : 1);
				if(rangeEndStart>=s.size()) return false;
				String name = s.get(2);
				if(name.isEmpty()) return false;
				if(line==goesOnAt) goOn();
				else{
					valueOf(line, TTalk.combine(4, separator, s, w.types));
					//both bounds are evaluated before they are read
					c.var(JitClassFile.ALOAD, V);
					c.var(JitClassFile.ASTORE, V2);
					valueOf(line, TTalk.combine(rangeEndStart, s.size(), s, w.types));
					c.var(JitClassFile.ALOAD, V2);
					c.invokestatic(RT, "bound", "(L"+STR+";)I");
					c.var(JitClassFile.ISTORE, A);
					c.var(JitClassFile.ALOAD, V);
					c.invokestatic(RT, "bound", "(L"+STR+";)I");
					c.var(JitClassFile.ISTORE, B);
				}
				int variable = sc.variable(name);
				c.bind(loop);
				c.var(JitClassFile.ILOAD, A);
				c.var(JitClassFile.ILOAD, B);
				c.jump(down ? JitClassFile.IF_ICMPLT : JitClassFile.IF_ICMPGT, exit);
				c.var(JitClassFile.ALOAD, F);
				c.iconst(variable);
				c.var(JitClassFile.ILOAD, A);
				c.invokestatic(RT, "counter", "(L"+FRAME+";II)V");
				loopBody(line+1, endRepeat-1, exit);
				c.iinc(A, down ? -1 : 1);
				c.jump(JitClassFile.GOTO, loop);
				return true;
			}
		}

		// ---- reading statements

		/** The words of the line as the interpreter has them, or would have them; null: not known. */
		@SuppressWarnings("unchecked")
		private Words lineWords(int line){
			if(object.stringList!=null && line<object.stringList.length && object.stringList[line]!=null)
				return new Words(object.stringList[line], object.typeList[line]);
			return makesFunctionVariable(script.get(line)) ? null : tokens(script.get(line), true);
		}

		/**
		 * The first reading of a line adds the words it takes for variables to the handler's set (fullresolution), and the next lines
		 * read depend on that: after put 5 into random, random of 3 is no call. Compiled code does not read lines.
		 * For plain names that changes nothing, so only a line that makes a function's or property's name a variable, or a word
		 * of the language, goes to the interpreter when it has never run.
		 */
		private boolean makesFunctionVariable(String text){
			TreeSet<String> set = new TreeSet<String>(known);
			TTalk.fullresolution(text, new ArrayList<String>(), new ArrayList<TTalk.wordType>(), set, true);
			set.removeAll(known);
			for(String word : set){
				if(!plainVariable(word) || TTalk.funcSet.contains(word) || TTalk.propertySet.contains(word)) return true;
			}
			return false;
		}

		/** The words of a text not yet run. A word that is a variable or not by what the handler has declared so far: the
		 *  same either way, or the text is left alone. */
		private Words tokens(String text, boolean isCmd){
			Words a = new Words(new ArrayList<String>(), new ArrayList<TTalk.wordType>());
			Words b = new Words(new ArrayList<String>(), new ArrayList<TTalk.wordType>());
			TTalk.fullresolution(text, a.words, a.types, new TreeSet<String>(known), isCmd);
			TTalk.fullresolution(text, b.words, b.types, new TreeSet<String>(), isCmd);
			if(a.size()!=b.size()) return null;
			for(int i=0; i<a.size(); i++){
				if(!a.get(i).equals(b.get(i))) return null;
				TTalk.wordType p = a.types.get(i), q = b.types.get(i);
				if(p!=q && !((p==TTalk.wordType.X || p==TTalk.wordType.VARIABLE) && (q==TTalk.wordType.X || q==TTalk.wordType.VARIABLE))) return null;
			}
			return a;
		}

		/** The tree of a whole expression text, or null */
		private Expression expression(String text){
			try{
				Words w = tokens(text, false);
				if(w==null || w.size()==0) return null;
				Expression e = new Sim(sc, w.words, w.typeArray()).expression(0, w.size()-1);
				return e.depth()>MAX_EXPRESSION_DEPTH ? null : e;
			}catch(Reject e){
				if(TRACE) System.err.println("[talk jit] left to the interpreter: "+text+" ("+e.getMessage()+")");
				return null;
			}
		}

		/** The fast form of a statement, or null */
		private Plan plan(Words w){
			Plan plan = readPlan(w);
			if(plan==null || plan.value.depth()>MAX_EXPRESSION_DEPTH) return null;
			if(plan.first!=null && plan.first.depth()+(plan.last==null ? 0 : plan.last.depth())>MAX_EXPRESSION_DEPTH) return null;
			return plan;
		}

		private Plan readPlan(Words w){
			try{
				String first = w.get(0);
				int last = w.size()-1;
				if(w.types.get(last)!=TTalk.wordType.STRING && w.get(last).equalsIgnoreCase("else")) return null;
				if(first=="put") return planPut(w);
				if(first=="return") return w.size()>=2 ? new Plan(Kind.RETURN, -1, -1, new Sim(sc, w.words, w.typeArray()).expression(1, w.size()-1)) : null;
				if(first=="get") return planGet(w);
				if(first=="add" || first=="subtract") return planAddSubtract(w, first=="add");
				if(first=="multiply" || first=="divide") return planMultiplyDivide(w, first=="multiply");
				if(first=="global") return planGlobal(w);
				return planCommand(w);
			}catch(Reject e){
				if(TRACE) System.err.println("[talk jit] left to the interpreter: "+w.words+" ("+e.getMessage()+")");
				return null;
			}
		}

		private Plan planGlobal(Words w) throws Reject {
			ArrayList<String> names = new ArrayList<String>();
			for(int i=1; i<w.size(); i++){
				String word = w.get(i);
				if(word.equals(",") && w.types.get(i)!=TTalk.wordType.STRING) continue;
				if(w.types.get(i)==TTalk.wordType.STRING || !plainVariable(word) || word.equalsIgnoreCase("me")) return null;
				names.add(word);
			}
			Plan plan = new Plan(Kind.GLOBAL, -1, -1, new Expression(new Lit("", false), new ArrayList<Step>(), false, new ArrayList<String>()));
			plan.names = names.toArray(new String[0]);
			return plan;
		}

		/** A word that can be nothing but a message: it goes up the message path as it is */
		private boolean messageWord(Words w){
			String first = w.get(0);
			TTalk.wordType type = w.types.get(0);
			if(type==TTalk.wordType.STRING || type==TTalk.wordType.CONST || type==TTalk.wordType.OPERATOR) return false;
			return plainVariable(first) && !NO_MESSAGES.contains(first) && !TTalk.commandSet.contains(first) && !TTalk.funcSet.contains(first) && !TTalk.propertySet.contains(first);
		}

		/** word a, b: as CommandExecStatement divides it */
		private Plan planCommand(Words w) throws Reject {
			if(!messageWord(w)) return null;
			ArrayList<String> s = w.words;
			TTalk.wordType[] t = w.typeArray();
			if(new Sim(sc, s, t).ordered) return null;
			if(s.size()>1 && t[1]==TTalk.wordType.OPERATOR && !s.get(1).equals("-") && !s.get(1).equals("not")) return null;
			ArrayList<Node> args = new ArrayList<Node>();
			ArrayList<String> notVariables = new ArrayList<String>();
			boolean functions = false;
			int mask = 0, nest = 0, from = 1;
			for(int i=1; s.size()>1 && i<=s.size(); i++){
				if(i<s.size() && (t[i]==TTalk.wordType.LFUNC || t[i]==TTalk.wordType.LBRACKET)) nest++;
				if(i<s.size() && (t[i]==TTalk.wordType.RBRACKET || t[i]==TTalk.wordType.RFUNC)) nest--;
				if(i<s.size() && (t[i]!=TTalk.wordType.COMMA || nest!=0)) continue;
				if(from>i-1) args.add(new Lit("", false));
				else{
					Expression e = new Sim(sc, s, t).expression(from, i-1);
					if(args.size()>=31) return null;
					if(e.node.numeric()) mask |= 1<<args.size();
					args.add(e.node);
					functions |= e.functions;
					notVariables.addAll(e.notVariables);
				}
				from = i+1;
			}
			Node command = new Command(s.get(0), w, args.toArray(new Node[0]), mask);
			return new Plan(Kind.COMMAND, -1, -1, new Expression(command, new ArrayList<Step>(), functions, notVariables));
		}

		private boolean destination(Words w, int i){
			TTalk.wordType t = w.types.get(i);
			return (t==TTalk.wordType.X || t==TTalk.wordType.VARIABLE) && plainVariable(w.get(i)) && !TTalk.OBJECT_WORDS.contains(w.get(i));
		}

		private Plan planPut(Words w) throws Reject {
			ArrayList<String> s = w.words;
			TTalk.wordType[] t = w.typeArray();
			if(s.size()<4 || t[1]!=TTalk.wordType.STRING && s.get(1).equalsIgnoreCase("url")) return null;
			int next = 2, depth = 0;
			String direction = null;
			for(; next<s.size(); next++){
				if(t[next]==TTalk.wordType.LBRACKET || t[next]==TTalk.wordType.LFUNC) depth++;
				else if((t[next]==TTalk.wordType.RBRACKET || t[next]==TTalk.wordType.RFUNC) && depth>0) depth--;
				String d = s.get(next);
				if(depth==0 && t[next]!=TTalk.wordType.STRING && (d.equalsIgnoreCase("into") || d.equalsIgnoreCase("before") || d.equalsIgnoreCase("after"))){
					direction = d.toLowerCase(Locale.ROOT);
					break;
				}
			}
			if(direction==null || next<2) return null;
			if(next!=s.size()-2) return direction.equals("into") ? planPutChunk(w, t, next) : null;
			if(!destination(w, next+1)) return null;
			int variable = sc.variable(s.get(next+1));
			Expression value = new Sim(sc, s, t).expression(1, next-1);
			if(direction.equals("into")) return new Plan(Kind.PUT, variable, -1, value);
			return new Plan(direction.equals("after") ? Kind.AFTER : Kind.BEFORE, variable, sc.variable("hzputaftervalue"),
				value.with(textOperand(value.node)));
		}

		/** put value into char|word|item|line n [to m] of variable */
		private Plan planPutChunk(Words w, TTalk.wordType[] t, int into) throws Reject {
			ArrayList<String> s = w.words;
			int chunk = into+1, last = s.size()-1;
			if(t[chunk]!=TTalk.wordType.CHUNK || last-chunk<3 || !destination(w, last) || !s.get(last-1).equals("of")) return null;
			String mode = s.get(chunk);
			if(mode!="char" && mode!="character" && mode!="item" && mode!="word" && mode!="line") return null;
			int to = -1;
			for(int k=chunk+1; k<last-1; k++){
				String word = s.get(k);
				if(word.equalsIgnoreCase("of") || word.equalsIgnoreCase("any") || t[k]==TTalk.wordType.CHUNK) return null;
				if(word.equalsIgnoreCase("to")){
					if(to>=0 || t[k]==TTalk.wordType.STRING) return null;
					to = k;
				}
			}
			Sim value = new Sim(sc, s, t), number = new Sim(sc, s, t);
			if(value.ordered || number.ordered) return null;
			Node first = number.eval(chunk+1, (to>=0 ? to : last-1)-1);
			Node end = to>=0 ? number.eval(to+1, last-2) : null;
			Node text = textOperand(value.eval(1, into-1));
			number.notVariables.addAll(value.notVariables);
			Plan plan = new Plan(Kind.CHUNK, sc.variable(s.get(last)), -1, new Expression(text, value.steps, value.functions || number.functions, number.notVariables));
			plan.mode = mode;
			plan.first = first;
			plan.last = end;
			return plan;
		}

		private Plan planGet(Words w) throws Reject {
			ArrayList<String> s = w.words;
			TTalk.wordType[] t = w.typeArray();
			if(s.size()<2 || t[1]!=TTalk.wordType.STRING && s.get(1).equalsIgnoreCase("url")) return null;
			return new Plan(Kind.GET, sc.variable("it"), -1, new Sim(sc, s, t).expression(1, s.size()-1));
		}

		/** add v to dest / subtract v from dest: put ( dest ) + ( v ) into dest */
		private Plan planAddSubtract(Words w, boolean add) throws Reject {
			ArrayList<String> s = w.words;
			TTalk.wordType[] t = w.typeArray();
			int next = 1;
			for(; next<s.size(); next++){
				if(add ? s.get(next).equalsIgnoreCase("to") && t[next]!=TTalk.wordType.STRING && !TTalk.chunkRangeTo(s, t, next) : s.get(next).equalsIgnoreCase("from")) break;
			}
			if(next<2 || next!=s.size()-2 || !destination(w, next+1)) return null;
			Sim sim = new Sim(sc, s, t);
			if(sim.ordered) return null;
			Node operand = new Paren(sim.eval(1, next-1));
			int variable = sc.variable(s.get(next+1));
			Node value = new Arith(add ? "add" : "sub", new Var(variable, ARITH), arithOperand(operand));
			return new Plan(Kind.PUT, variable, -1, new Expression(value, sim));
		}

		/** multiply dest by v: put ( v ) * dest into dest; divide dest by v: put ( dest ) / ( v ) into dest */
		private Plan planMultiplyDivide(Words w, boolean multiply) throws Reject {
			ArrayList<String> s = w.words;
			TTalk.wordType[] t = w.typeArray();
			if(s.size()<4 || !s.get(2).equalsIgnoreCase("by") || !destination(w, 1)) return null;
			Sim sim = new Sim(sc, s, t);
			if(sim.ordered) return null;
			Node operand = new Paren(sim.eval(3, s.size()-1));
			int variable = sc.variable(s.get(1));
			Node dest = new Var(variable, ARITH);
			Node value = multiply ? new Arith("mul", arithOperand(operand), dest) : new Arith("div", dest, arithOperand(operand));
			return new Plan(Kind.PUT, variable, -1, new Expression(value, sim));
		}
	}
}
