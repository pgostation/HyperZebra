import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/**
 * Whole numeric repeat loops of the browser player as one Wasm function (TalkWasmStatements does single statements).
 * Entered from the interpreter's own loop drivers once a loop is hot (TTalk.compiledCount/compiledLoop, after a pass):
 * every variable is read ONCE as a number, the remaining passes run natively, and only the variables assigned are
 * written back through the interpreter's storage rules. No per-statement crossing of the JS boundary, no text.
 *
 * Accepted body: put/get/add/subtract/multiply/divide of numeric expressions (TalkWasmStatements' parser, plus bare copies
 * of a variable or a literal), if/else if/else with numeric comparisons joined by and/or, nested repeat with/n times/
 * while/until/forever, exit repeat, next repeat. Anything else rejects the whole loop before it runs.
 *
 * The module stores every variable into its memory at the start of each pass (a checkpoint). A guard failure inside a
 * pass (fraction where an integer is needed, overflow, -0, a real mod, a bound that is no 32-bit integer...) returns NaN:
 * the checkpoint is written back and the interpreter goes on from that pass (count loops: RESUME at the pass's counter;
 * while/until/forever have no state). As only variables change, running the failed pass again repeats no side effect.
 * A slice of work ends at a pass boundary so that input polling (statementTick) and user abort still happen.
 */
final class TalkWasmLoops {
    private TalkWasmLoops() {}
    static int compiled, executed, rejected, fallbacks, slices;
    static final int THRESHOLD = Math.max(1, Integer.getInteger("hz.talk.wasm.loopthreshold", 8));
    /** Statements per slice between input polls (~1 ms native), and the most one pass may take before it is interpreted */
    static final double SLICE = Integer.getInteger("hz.talk.wasm.loopslice", 250000), HARD = 20000000;
    /** compiledCount's answer "go on interpreting at pass resumeAt" */
    static final Result RESUME = new Result();
    /** -Dhz.talk.wasm.looptrace=true: says where a compiled loop gave the pass back to the interpreter */
    private static final boolean TRACE = Boolean.getBoolean("hz.talk.wasm.looptrace");
    static int resumeAt;
    /** io: 0 counter, 1 bound, 3 fuel, 4 undo log's byte offset, 5 its length in entries; then per variable number, real,
     *  format; per variable whether its text at entry is a whole number's own text (f0); per array of pixels its byte
     *  offset, rows, columns, first and last cell written (g0); per literal whether it is a whole number's text (l0) */
    private static final int HEAD = 6, MAX_VARS = 64, MAX_LINES = 400;
    /** Formats at or below TEXT: a variable holding text, char (code & 0xffff) of literal (code >> 16), code = TEXT - format */
    private static final double TEXT = -1000000;
    /** A pixel read from an array (real - 1 = ARGB, its text TalkPixels.pixelText), and item n of a pixel (its digits).
     *  A variable whose number is NaN and real >= 1 holds a canonical pixel's text (from an array, or at entry). */
    private static final double PIXEL = -3e9, ITEM = -3.5e9;
    private static final int WITH = 0, TIMES = 1, WHILE = 2, UNTIL = 3, FOREVER = 4;

    static final class Cache {
        final ArrayList<String> script;
        final Region[] lines;
        Cache(ArrayList<String> script) { this.script = script; lines = new Region[script.size()]; }
    }
    private static final class Region {
        final ArrayList<String> words;
        int count, cooldown, runs, bails;
        boolean done;
        Plan plan;
        Region(ArrayList<String> words) { this.words = words; }
    }
    private static final class Plan {
        final TalkWasm.Code code;
        final String[] vars, literals, commands, functions, words;
        /** Written before anything reads it on every pass: its value at entry is never used, so it may be text then */
        final boolean[] writeFirst;
        /** Variables whose arrays of pixels the loop reads and writes as px[y][x] (TalkPixels) */
        final String[] grids;
        final boolean pixels;
        /** The loop's own counter (repeat with): set at the start of every pass */
        final String counter;
        final int f0, g0, l0;
        Plan(TalkWasm.Code code, Compiler c) {
            this.code = code;
            vars = c.vars.keySet().toArray(new String[0]);
            literals = c.literals.toArray(new String[0]);
            commands = c.commands.toArray(new String[0]);
            functions = c.functions.toArray(new String[0]);
            words = c.words.toArray(new String[0]);
            writeFirst = new boolean[vars.length];
            for (int j = 0; j < vars.length; j++) writeFirst[j] = c.writeFirst.contains(vars[j]);
            grids = c.grids.keySet().toArray(new String[0]);
            pixels = c.pixels;
            counter = c.topCounter;
            f0 = c.f0; g0 = c.g0; l0 = c.l0;
        }
    }

    static Result count(TTalk t, OObject object, ArrayList<String> script, int line, int endRepeat, int from, int to,
            MemoryData memory) throws xTalkException {
        return enter(t, object, script, line, endRepeat, from, to, true, memory);
    }
    /** Before a count loop's first pass: only a loop compiled already (no warm-up counting here). RESUME: interpret from resumeAt. */
    static Result start(TTalk t, OObject object, ArrayList<String> script, int line, int endRepeat, int from, int to,
            MemoryData memory) throws xTalkException {
        if (object == null || script != object.scriptList || object.wasmLoops == null || object.wasmLoops.script != script
                || line >= object.wasmLoops.lines.length) return null;
        Region region = object.wasmLoops.lines[line];
        if (region == null || !region.done || region.plan == null || object.stringList == null || region.words != object.stringList[line]) return null;
        return enter(t, object, script, line, endRepeat, from, to, true, memory);
    }
    static Result loop(TTalk t, OObject object, ArrayList<String> script, int line, int endRepeat, MemoryData memory)
            throws xTalkException {
        return enter(t, object, script, line, endRepeat, 0, 0, false, memory);
    }

    /**
     * Whether this loop is this compiler's: 1 it has the loop as Wasm, 0 it has given it up (or cannot run here), -1 not decided
     * yet (it decides after the interpreter's first passes). TalkJit's ahead-of-time code leaves a loop that is not 0 to the
     * interpreter, which runs it through here (TalkJit.wasmLoop).
     */
    static int taken(OObject object, ArrayList<String> script, int line) {
        if (TalkWasm.backend == null || object == null || script != object.scriptList || "false".equals(System.getProperty("hz.talk.wasm"))) return 0;
        if (object.stringList == null || line >= object.stringList.length || object.stringList[line] == null) return -1;
        Cache cache = object.wasmLoops;
        if (cache == null || cache.script != script || line >= cache.lines.length) return -1;
        Region region = cache.lines[line];
        if (region == null || region.words != object.stringList[line] || !region.done) return -1;
        return region.plan != null ? 1 : 0;
    }

    private static boolean slow(TTalk t, OObject object, MemoryData memory) {
        return TalkWasm.backend == null || memory == null || TTalk.stop || TTalk.abandonedThread() || TTalk.tracemode != 0
                || "false".equals(System.getProperty("hz.talk.wasm")) || object.checkpoints != null
                || StackSpeed.limited() || TTalk.numFormat != TTalk.DEFAULT_NUMBER_FORMAT_INSTANCE
                || t.sendPending() || UiHost.get().xcmd68kHasSessions();
    }

    private static Result enter(TTalk t, OObject object, ArrayList<String> script, int line, int endRepeat, int from, int to,
            boolean counted, MemoryData memory) throws xTalkException {
        if (slow(t, object, memory) || object.stringList == null || line >= object.stringList.length) return null;
        ArrayList<String> words = object.stringList[line];
        if (words == null) return null;
        Cache cache = object.wasmLoops;
        if (cache == null || cache.script != script || cache.lines.length != script.size()) object.wasmLoops = cache = new Cache(script);
        Region region = cache.lines[line];
        if (region == null || region.words != words) cache.lines[line] = region = new Region(words);
        if (!region.done) {
            if (++region.count < THRESHOLD) return null;
            region.done = true;
            Plan plan = null;
            try { plan = new Compiler(object, script, line, endRepeat, memory, counted).compile(); }
            catch (TalkWasmStatements.Reject | NumberFormatException | xTalkException | IndexOutOfBoundsException e) { plan = null; trace(line, e); }
            catch (RuntimeException e) { plan = null; trace(line, e); } // compilation unavailable (browser CSP)
            region.plan = plan;
            if (plan == null) rejected++; else compiled++;
        }
        Plan p = region.plan;
        if (p == null) return null;
        if (region.cooldown > 0) { region.cooldown--; return null; }
        if (!pathClear(p, object)) return null;
        int n = p.vars.length;
        double[] io = new double[p.l0 + p.literals.length];
        String[] texts = new String[n];
        boolean[] raw = new boolean[n];
        if (!read(p, memory, io, texts, raw)) return null;
        Grids grids = null;
        if (p.grids.length > 0 && (grids = grids(p, memory, io)) == null) return null;
        io[0] = from; io[1] = to; io[3] = SLICE;
        region.runs++;
        boolean written = false;
        while (true) {
            int at = (int) io[0];
            double status;
            try { status = grids == null ? p.code.runMemory(io) : p.code.runShared(io); }
            catch (RuntimeException e) {
                if (grids != null) { evictAll(); region.plan = null; } // what the memory holds is not known: never again here
                fallbacks++; region.cooldown = 64; return written && counted ? resume(at) : null;
            }
            writeBack(p, memory, io, texts, raw);
            if (grids != null) written(p, grids, io);
            written = true;
            if (status == 0 || status == 1) { // the loop ended or exit repeat
                executed++;
                TTalk.lastResult = new Result();
                Result r = new Result();
                r.ret = 0;
                return r;
            }
            if (status == 2) { // a slice ended at a pass boundary
                slices++;
                if (TTalk.statementTick != null) TTalk.statementTick.run();
                if (slow(t, object, memory) || !pathClear(p, object) || !read(p, memory, io, texts, raw)
                        || p.grids.length > 0 && (grids = grids(p, memory, io)) == null)
                    return counted ? resume((int) io[0]) : null;
                io[3] = SLICE;
                continue;
            }
            if (TRACE) System.err.println("TalkWasmLoops: fallback in the loop at line " + (line + 1) + " pass " + (int) io[0] + " " + java.util.Arrays.toString(p.vars) + " " + java.util.Arrays.toString(io));
            fallbacks++; region.bails++; region.cooldown = 64;
            if (region.bails >= 8 && region.bails * 4 > region.runs) region.plan = null; // mostly fails: leave it interpreted
            return counted ? resume((int) io[0]) : null;
        }
    }

    private static void trace(int line, Exception e) {
        if (!TRACE) return;
        StackTraceElement[] at = e.getStackTrace();
        System.err.println("TalkWasmLoops: the loop at line " + (line + 1) + " stays interpreted: " + e + (at.length > 1 ? " at " + at[1] : ""));
    }
    private static Result resume(int pass) { resumeAt = pass; return RESUME; }

    /** No handler or XCMD of the path catches a command or function the loop uses */
    private static boolean pathClear(Plan p, OObject object) {
        for (String command : p.commands) if (TTalk.commandHandlerKind(command, object) != 0) return false;
        if (p.functions.length > 0) {
            for (OObject path = object; path != null; path = path.parent) if (path.scriptList == null) return false;
            OStack stack = HZ.stack();
            for (OObject path = stack == null ? null : stack.curCard; path != null; path = path.parent) if (path.scriptList == null) return false;
            for (String function : p.functions)
                if (TTalk.commandHandlerKind(function, object) != 0 || UiHost.get().tXcmdExternalOwner(function, "function") != null) return false;
        }
        return true;
    }

    /** Plain decimal text only: a comparison does not read "", " 5", "+3", "1e3" as numbers the way arithmetic does */
    static boolean plain(String s) {
        int n = s.length(), i = s.startsWith("-") ? 1 : 0, digits = 0;
        boolean point = false;
        if (n > 400) return false;
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits++;
            else if (c == '.' && !point && digits > 0 && i + 1 < n) point = true;
            else return false;
        }
        return digits > 0;
    }

    /** The text Integer.toString gives (no sign on 0, no leading zero, no space, point or +): what a whole number reads as */
    static boolean wholeText(String s) {
        int n = s.length(), i = n > 0 && s.charAt(0) == '-' ? 1 : 0;
        if (n == i || n - i > 10 || s.charAt(i) == '0' && (n > i + 1 || i == 1)) return false;
        for (int k = i; k < n; k++) { char c = s.charAt(k); if (c < '0' || c > '9') return false; }
        long v = Long.parseLong(s);
        return v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE;
    }

    // ---- arrays of pixels in the shared memory (TalkWasm.store): put there once, read back only when the interpreter reads ----
    /** Bytes for io at the start of the memory (any loop's io fits: the compiler checks) */
    static final int IO_BYTES = 1 << 20;
    private static final long LIMIT = 1L << 30;
    private static final ArrayList<TalkPixels.Grid> resident = new ArrayList<>();
    private static long residentTop = IO_BYTES;

    /** Every array back into its int[], and out of the memory */
    static void evictAll() {
        for (TalkPixels.Grid r : resident) {
            try { r.pull(); } catch (RuntimeException e) { r.wasmFirst = Integer.MAX_VALUE; r.wasmLast = -1; }
            r.base = -1; r.javaFirst = Integer.MAX_VALUE; r.javaLast = -1;
        }
        resident.clear();
        residentTop = IO_BYTES;
    }
    /** A new memory (a test's new engine): nothing is in it, and what was there is gone */
    static void forgetResident() {
        for (TalkPixels.Grid r : resident) { r.base = -1; r.javaFirst = r.wasmFirst = Integer.MAX_VALUE; r.javaLast = r.wasmLast = -1; }
        resident.clear();
        residentTop = IO_BYTES;
    }
    private static long bytes(TalkPixels.Grid g) { return ((long) g.cells.length * 4 + 15) & ~15L; }
    /** These arrays in the memory, current (what the interpreter changed put there); false: they do not fit */
    private static boolean place(TalkPixels.Grid[] gs) {
        long need = 0;
        for (TalkPixels.Grid g : gs) if (g.base < 0) need += bytes(g);
        if (residentTop + need > LIMIT || resident.size() + gs.length > 32) {
            evictAll();
            need = 0;
            for (TalkPixels.Grid g : gs) need += bytes(g);
            if (IO_BYTES + need > LIMIT) return false;
        }
        TalkWasm.Store store = TalkWasm.store;
        for (TalkPixels.Grid g : gs) {
            if (g.base < 0) {
                g.base = (int) residentTop;
                residentTop += bytes(g);
                resident.add(g);
                store.ensure((int) residentTop);
                store.put(g.cells, 0, g.cells.length, g.base);
                g.javaFirst = Integer.MAX_VALUE; g.javaLast = -1;
            } else if (g.javaLast >= g.javaFirst) {
                store.put(g.cells, g.javaFirst, g.javaLast - g.javaFirst + 1, g.base + g.javaFirst * 4);
                g.javaFirst = Integer.MAX_VALUE; g.javaLast = -1;
            }
        }
        return true;
    }

    /** The arrays of pixels of a loop's variables */
    private static final class Grids {
        final LiveNode[] nodes; final TalkPixels.Grid[] grids;
        Grids(int n) { nodes = new LiveNode[n]; grids = new TalkPixels.Grid[n]; }
    }
    private static Grids grids(Plan p, MemoryData memory, double[] io) {
        if (TalkWasm.store == null) return null;
        int n = p.grids.length;
        Grids s = new Grids(n);
        long cells = 0;
        for (int g = 0; g < n; g++) {
            LiveNode node;
            try { node = TTalk.liveCollection(memory, p.grids[g]); } catch (xTalkException e) { return null; }
            TalkPixels.Grid grid = node == null ? null : node.pixelGrid();
            if (grid == null || !grid.plain()) return null;
            for (int k = 0; k < g; k++) if (s.grids[k] == grid) return null;
            s.nodes[g] = node; s.grids[g] = grid;
            cells += grid.cells.length;
        }
        if (!place(s.grids)) return null;
        for (int g = 0; g < n; g++) {
            TalkPixels.Grid grid = s.grids[g];
            io[p.g0 + 5 * g] = grid.base; io[p.g0 + 5 * g + 1] = grid.h; io[p.g0 + 5 * g + 2] = grid.w;
            io[p.g0 + 5 * g + 3] = grid.cells.length; io[p.g0 + 5 * g + 4] = -1;
        }
        // the undo log of a pass given back: (cell's address, its value before) for each write since the pass began
        long log = cells + 4096, at = residentTop;
        if (at + log * 8 > 2_000_000_000L) return null;
        TalkWasm.store.ensure((int) (at + log * 8));
        io[4] = at; io[5] = log;
        return s;
    }
    /** After a run: the cells written stay in the memory until the interpreter reads them; the rows' text is made again then */
    private static void written(Plan p, Grids s, double[] io) {
        for (int g = 0; g < s.grids.length; g++) {
            TalkPixels.Grid grid = s.grids[g];
            int first = (int) io[p.g0 + 5 * g + 3], last = (int) Math.min(io[p.g0 + 5 * g + 4], grid.cells.length - 1);
            if (last < first) continue;
            if (first < grid.wasmFirst) grid.wasmFirst = first;
            if (last > grid.wasmLast) grid.wasmLast = last;
            s.nodes[g].pixelsWritten(first, last);
        }
    }

    private static boolean read(Plan p, MemoryData memory, double[] io, String[] texts, boolean[] raw) {
        for (String w : p.words) if (memory.treeset.contains(w) || memory.seesGlobal(w)) return false; // put 5 into pi / abs
        if (p.pixels && !",".equals(TTalk.itemDelimiter)) return false;
        for (int k = 0; k < p.literals.length; k++) io[p.l0 + k] = wholeText(p.literals[k]) ? 1 : 0;
        for (int j = 0; j < p.vars.length; j++) {
            String name = p.vars[j];
            if (memory.notDeclared != null && memory.notDeclared.contains(name)) return false;
            String text = TTalk.getVariable(memory, name);
            if (text == null) { // an unset variable is its name: only one every pass sets before reading it may be unset
                if (!p.writeFirst[j] && !name.equals(p.counter)) return false;
                io[HEAD + 3 * j] = Double.NaN; io[HEAD + 3 * j + 1] = 0; io[HEAD + 3 * j + 2] = 0; io[p.f0 + j] = 0;
                texts[j] = null; raw[j] = false;
                continue;
            }
            io[p.f0 + j] = wholeText(text) ? 1 : 0;
            if (!plain(text)) {
                long pixel = p.pixels ? TalkPixels.canonical(text) : -1;
                if (pixel >= 0) { // a pixel's text: read by item n of, put into an array
                    io[HEAD + 3 * j] = Double.NaN; io[HEAD + 3 * j + 1] = pixel + 1; io[HEAD + 3 * j + 2] = 0;
                    texts[j] = text; raw[j] = false;
                    continue;
                }
                if (!p.writeFirst[j]) return false; // text stays interpreted
                io[HEAD + 3 * j] = Double.NaN; io[HEAD + 3 * j + 1] = 0; io[HEAD + 3 * j + 2] = 0;
                texts[j] = text; raw[j] = false;
                continue;
            }
            io[HEAD + 3 * j] = TTalk.parseNumber(text);
            io[HEAD + 3 * j + 1] = TTalk.isRealNumber(text) ? 1 : 0;
            io[HEAD + 3 * j + 2] = 0;
            texts[j] = text;
            raw[j] = TTalk.isRawNumberVariable(memory, name);
        }
        return true;
    }

    /** Formats: 0 unchanged, 1 computed integer form, 2 computed real form, 3 repeat counter, 4+k literal k, -1-k the text
     *  variable k had when the slice began (a bare copy) */
    private static void writeBack(Plan p, MemoryData memory, double[] io, String[] texts, boolean[] raw) throws xTalkException {
        for (int j = 0; j < p.vars.length; j++) {
            double format = io[HEAD + 3 * j + 2], value = io[HEAD + 3 * j];
            if (format == 0) continue;
            String name = p.vars[j];
            if (format == PIXEL) TTalk.setVariable(memory, name, TalkPixels.pixelText((int) (long) (io[HEAD + 3 * j + 1] - 1)));
            else if (format == ITEM) TTalk.setVariable(memory, name, Integer.toString((int) value));
            else if (format == 1) TTalk.putNativeNumber(memory, name, TTalk.formatRawNumber(value));
            else if (format == 2) TTalk.putNativeNumber(memory, name, TTalk.formatRealNumber(value));
            else if (format == 3) TTalk.setNumberVariable(memory, name, Integer.toString((int) value));
            else if (format <= TEXT) { // char i of literal k
                long code = (long) (TEXT - format);
                String literal = p.literals[(int) (code >> 16)];
                int i = (int) (code & 0xffff);
                TTalk.setVariable(memory, name, literal.substring(i, i + 1));
            }
            else if (format >= 4) {
                String literal = p.literals[(int) format - 4];
                // a quoted number's value is a copy of its token (the token itself marks a quoted literal)
                TTalk.setVariable(memory, name, TTalk.isQuotedNumberLiteral(literal) ? new String(literal) : literal);
            } else {
                int k = (int) (-1 - format);
                if (texts[k] == null) continue; // (a copy of an unset variable: not possible, it is set before it is read)
                if (raw[k]) TTalk.putNativeNumber(memory, name, texts[k]); else TTalk.setVariable(memory, name, texts[k]);
            }
        }
    }

    // ---- the loop as a tree ----
    private interface Stmt {}
    private static final class Assign implements Stmt {
        final Var dest; final TalkWasmStatements.Node expression;
        Assign(Var dest, TalkWasmStatements.Node expression) { this.dest = dest; this.expression = expression; }
    }
    private static final class If implements Stmt {
        final ArrayList<Cond> conditions = new ArrayList<>(); // null: else
        final ArrayList<ArrayList<Stmt>> bodies = new ArrayList<>();
    }
    private static final class Loop implements Stmt {
        int kind; boolean down; Var counter; TalkWasmStatements.Node from, to; Cond condition; ArrayList<Stmt> body;
    }
    private static final class Jump implements Stmt {
        final boolean exit;
        Jump(boolean exit) { this.exit = exit; }
    }
    /** put char n of "literal" into v / get char n of "literal": a text value, written back as that one character */
    private static final class CharOf implements Stmt {
        final Var dest; final TalkWasmStatements.Node index; final String literal;
        CharOf(Var dest, TalkWasmStatements.Node index, String literal) { this.dest = dest; this.index = index; this.literal = literal; }
    }
    private interface Cond {}
    private static final class Compare implements Cond {
        final String op; final TalkWasmStatements.Node left, right;
        Compare(String op, TalkWasmStatements.Node left, TalkWasmStatements.Node right) { this.op = op; this.left = left; this.right = right; }
    }
    private static final class Logic implements Cond {
        final boolean and; final Cond left, right;
        Logic(boolean and, Cond left, Cond right) { this.and = and; this.left = left; this.right = right; }
    }
    /** put <pixel> into px[y][x]: a pixel value, a canonical literal, or r & "," & g & "," & b & "," & a */
    private static final class PutPixel implements Stmt {
        final GridVar grid; TalkWasmStatements.Node y, x, value; long constant = -1; TalkWasmStatements.Node[] parts;
        PutPixel(GridVar grid) { this.grid = grid; }
    }
    private static final class GridVar {
        final String name; final int index; int base, rows, columns;
        GridVar(String name, int index) { this.name = name; this.index = index; }
    }
    private static final class Var {
        final String name; final int index; TalkWasmStatements.Value v;
        Var(String name, int index) { this.name = name; this.index = index; }
    }
    private static final class Tokens {
        final ArrayList<String> w; final ArrayList<TTalk.wordType> t;
        Tokens(ArrayList<String> w, ArrayList<TTalk.wordType> t) { this.w = w; this.t = t; }
    }
    private static final class LoopLabels {
        final boolean top; final int breakLabel, nextLabel;
        LoopLabels(boolean top, int breakLabel, int nextLabel) { this.top = top; this.breakLabel = breakLabel; this.nextLabel = nextLabel; }
    }

    private static void need(boolean ok) { TalkWasmStatements.need(ok); }

    private static final class Compiler {
        final OObject object;
        final ArrayList<String> script;
        final int line, endRepeat;
        final MemoryData memory;
        final boolean counted;
        final LinkedHashMap<String, Var> vars = new LinkedHashMap<>();
        final ArrayList<Object[]> inputs = new ArrayList<>();
        final ArrayList<String> literals = new ArrayList<>();
        final HashSet<String> commands = new HashSet<>(), functions = new HashSet<>(), words = new HashSet<>();
        final IdentityHashMap<TalkWasmStatements.Value, Var> byValue = new IdentityHashMap<>();
        final TalkWasmStatements.Emitter e = new TalkWasmStatements.Emitter(0);
        final ArrayList<Integer> labels = new ArrayList<>();
        final ArrayList<LoopLabels> loops = new ArrayList<>();
        int statements, fuel, counter, bound;
        final HashSet<String> read = new HashSet<>(), writeFirst = new HashSet<>();
        HashSet<String> touched, reads;
        boolean topLevel;
        final LinkedHashMap<String, GridVar> grids = new LinkedHashMap<>();
        /** Pixels are read or written (item n of, px[y][x]): the module has canon() and the undo log */
        boolean pixels;
        int f0, g0, l0, logBase, logCap;
        String topCounter;
        /** Variables some statement puts a value into (not as a loop's counter), and the counters of the loops being emitted */
        final HashSet<String> assigned = new HashSet<>();
        final ArrayList<String> counters = new ArrayList<>();

        Compiler(OObject object, ArrayList<String> script, int line, int endRepeat, MemoryData memory, boolean counted) {
            this.object = object; this.script = script; this.line = line; this.endRepeat = endRepeat;
            this.memory = memory; this.counted = counted;
        }

        Plan compile() throws xTalkException {
            need(endRepeat - line <= MAX_LINES && endRepeat > line);
            touched = new HashSet<>();
            Loop top = header(new Tokens(object.stringList[line], object.typeList[line]), false);
            if (top.kind == WITH) topCounter = top.counter.name;
            read.addAll(touched);
            need(counted == (top.kind == WITH || top.kind == TIMES));
            topLevel = true;
            top.body = block(line + 1, endRepeat - 1);
            markAssigned(top.body);
            need(vars.size() <= MAX_VARS && grids.size() <= 8 && (HEAD + 4 * MAX_VARS + 40 + 2 * statements) * 8 < IO_BYTES);
            for (String g : grids.keySet()) need(!vars.containsKey(g)); // an array of pixels is no number or text here
            for (Var v : vars.values()) { v.v = e.local(); byValue.put(v.v, v); }
            for (Object[] in : inputs) ((TalkWasmStatements.Input) in[0]).value = vars.get((String) in[1]).v;
            f0 = HEAD + 3 * vars.size(); g0 = f0 + vars.size(); l0 = g0 + 5 * grids.size();
            fuel = e.slots++; counter = e.slots++; bound = e.slots++;
            TalkWasm.Bytes b = e.b;
            load(0, counter); load(1, bound); load(3, fuel);
            for (Var v : vars.values()) {
                load(HEAD + 3 * v.index, v.v.number); load(HEAD + 3 * v.index + 1, v.v.real); load(HEAD + 3 * v.index + 2, v.v.format);
            }
            if (pixels) { logBase = e.slots++; logCap = e.slots++; load(4, logBase); load(5, logCap); }
            for (GridVar g : grids.values()) {
                g.base = e.slots++; g.rows = e.slots++; g.columns = e.slots++;
                load(g0 + 5 * g.index, g.base); load(g0 + 5 * g.index + 1, g.rows); load(g0 + 5 * g.index + 2, g.columns);
            }
            b.op(0x03).op(0x40); int topLabel = push();
            checkpoint();
            switch (top.kind) {
                case WITH: b.get(counter).get(bound).op(top.down ? 0x63 : 0x64); returnIf(0); break;
                case TIMES: b.get(counter).get(bound).op(0x66); returnIf(0); break;
                case WHILE: condition(top.condition); b.op(0x45); returnIf(0); break;
                case UNTIL: condition(top.condition); returnIf(0); break;
                default: break;
            }
            b.get(fuel).number(0).op(0x65); returnIf(2);
            charge();
            if (top.kind == WITH) setCounter(top.counter, counter);
            b.op(0x02).op(0x40); int next = push();
            loops.add(new LoopLabels(true, -1, next));
            if (top.kind == WITH) counters.add(top.counter.name);
            body(top.body);
            if (top.kind == WITH) counters.remove(counters.size() - 1);
            loops.remove(loops.size() - 1);
            b.op(0x0b); pop();
            if (top.kind == WITH || top.kind == TIMES) b.get(counter).number(top.down ? -1 : 1).op(0xa0).set(counter);
            br(topLabel);
            b.op(0x0b); pop();
            b.number(Double.NaN).op(0x0b);
            need((long) (l0 + literals.size()) * 8 <= IO_BYTES);
            TalkWasm.Bytes module = new TalkWasm.Bytes().data(new byte[]{0, 97, 115, 109, 1, 0, 0, 0});
            // types: run () -> f64, pow (f64 f64) -> f64, random () -> f64, canon (f64 f64 f64 i32) -> i32, undo () -> ();
            // functions 0 pow, 1 random, 2 run, 3 canon, 4 undo (the last two with pixels only)
            module.section(1, new TalkWasm.Bytes().u(5).op(0x60).u(0).u(1).op(0x7c).op(0x60).u(2).op(0x7c).op(0x7c).u(1).op(0x7c)
                    .op(0x60).u(0).u(1).op(0x7c).op(0x60).u(4).op(0x7c).op(0x7c).op(0x7c).op(0x7f).u(1).op(0x7f).op(0x60).u(0).u(0));
            // the memory is the one all loop modules share (env.mem): io from byte 0, then arrays of pixels (TalkWasm.Store)
            module.section(2, new TalkWasm.Bytes().u(3).text("math").text("pow").op(0).u(1).text("math").text("random").op(0).u(2)
                    .text("env").text("mem").op(2).op(0).u(1));
            module.section(3, pixels ? new TalkWasm.Bytes().u(3).u(0).u(3).u(4) : new TalkWasm.Bytes().u(1).u(0));
            if (pixels) module.section(6, new TalkWasm.Bytes().u(1).op(0x7f).op(1).op(0x41).op(0).op(0x0b)); // the log's length
            module.section(7, pixels ? new TalkWasm.Bytes().u(3).text("run").op(0).u(2).text("mem").op(2).u(0).text("undo").op(0).u(4)
                    : new TalkWasm.Bytes().u(2).text("run").op(0).u(2).text("mem").op(2).u(0));
            TalkWasm.Bytes code = new TalkWasm.Bytes().u(1).u(e.slots).op(0x7c).data(b.toByteArray());
            TalkWasm.Bytes bodies = new TalkWasm.Bytes().u(pixels ? 3 : 1).u(code.size()).data(code.toByteArray());
            if (pixels) {
                TalkWasm.Bytes canon = canonBody(), undo = undoBody();
                bodies.u(canon.size()).data(canon.toByteArray()).u(undo.size()).data(undo.toByteArray());
            }
            module.section(10, bodies);
            need(module.size() <= 65536);
            TalkWasm.Code compiled = TalkWasm.backend.compile(module.toByteArray());
            return compiled == null ? null : new Plan(compiled, this);
        }

        // ---- parsing ----
        Tokens tokens(int l) throws xTalkException {
            if (l < object.stringList.length && object.stringList[l] != null) return new Tokens(object.stringList[l], object.typeList[l]);
            return tokenize(script.get(l), true); // not run yet: as the interpreter would split it now, without declaring anything
        }
        Tokens tokenize(String text, boolean command) throws xTalkException {
            MemoryData scratch = new MemoryData();
            scratch.treeset = new TreeSet<>(memory.treeset);
            scratch.notDeclared = memory.notDeclared;
            ArrayList<String> w = new ArrayList<>();
            ArrayList<TTalk.wordType> t = new ArrayList<>();
            TTalk.tokenize(text, w, t, scratch, command);
            return new Tokens(w, t);
        }
        Var var(String name) {
            name = name.toLowerCase(java.util.Locale.ROOT);
            if (touched != null) touched.add(name);
            need(name.equals("it") || TalkWasmStatements.variable(name));
            need(TTalk.funcSet == null || !TTalk.funcSet.contains(name));
            need(TTalk.propertySet == null || !TTalk.propertySet.contains(name));
            Var v = vars.get(name);
            if (v == null) vars.put(name, v = new Var(name, vars.size()));
            return v;
        }
        // ---- arrays of pixels (TalkPixels): px[y][x], item n of a pixel, put … into px[y][x] ----
        boolean is(Tokens k, int i, String word) { return k.t.get(i) != TTalk.wordType.STRING && k.w.get(i).equals(word); }
        boolean is(TalkWasmStatements.Parser p, int i, String word) { return i < p.end && p.types.get(i) != TTalk.wordType.STRING && p.words.get(i).equals(word); }
        GridVar grid(String name) {
            name = name.toLowerCase(java.util.Locale.ROOT);
            need(!name.equals("it") && TalkWasmStatements.variable(name));
            need(TTalk.funcSet == null || !TTalk.funcSet.contains(name));
            need(TTalk.propertySet == null || !TTalk.propertySet.contains(name));
            GridVar g = grids.get(name);
            if (g == null) grids.put(name, g = new GridVar(name, grids.size()));
            pixels = true;
            return g;
        }
        /** The ] closing the [ at open, before end */
        int close(Tokens k, int open, int end) {
            int depth = 0;
            for (int i = open; i < end; i++) {
                if (is(k, i, "[")) depth++;
                else if (is(k, i, "]") && --depth == 0) return i;
            }
            throw new TalkWasmStatements.Reject();
        }
        /** The [ opening the ] at close, after from */
        int open(Tokens k, int from, int close) {
            int depth = 0;
            for (int i = close; i > from; i--) {
                if (is(k, i, "]")) depth++;
                else if (is(k, i, "[") && --depth == 0) return i;
            }
            throw new TalkWasmStatements.Reject();
        }
        TalkWasmStatements.Node sub(Tokens k, int from, int to) {
            need(from < to);
            TalkWasmStatements.Parser p = parser(k, from, to, false);
            TalkWasmStatements.Node n = p.parse();
            register(p);
            return n;
        }
        /** The parser's own atoms of loop modules */
        TalkWasmStatements.Node access(TalkWasmStatements.Parser p) {
            if (p.types.get(p.at) == TTalk.wordType.STRING) return null;
            Tokens k = new Tokens(p.words, p.types);
            if (p.types.get(p.at) == TTalk.wordType.X && !is(p, p.at + 1, "(") && !is(p, p.at + 1, "[")
                    && !(p.at + 1 < p.end && p.words.get(p.at + 1).equalsIgnoreCase("of"))) {
                // a condition's first word (if x = 3 then …): the tokenizer leaves it unresolved; the interpreter reads the variable
                String name = p.words.get(p.at).toLowerCase(java.util.Locale.ROOT);
                if (memory.treeset.contains(name) && TalkWasmStatements.variable(name)) { p.at++; return p.input(name); }
                return null;
            }
            if (p.words.get(p.at).equalsIgnoreCase("item") && p.types.get(p.at) == TTalk.wordType.CHUNK) {
                // item <1-4> of <variable> | of <array>[y][x]: the one pixel's component
                need(p.at + 3 < p.end + 1 && p.at + 2 < p.end && p.words.get(p.at + 2).equalsIgnoreCase("of") && p.types.get(p.at + 2) != TTalk.wordType.STRING);
                String index = p.words.get(p.at + 1).toLowerCase(java.util.Locale.ROOT);
                TTalk.wordType type = p.types.get(p.at + 1);
                int n = type == TTalk.wordType.STRING ? (index.matches("[1-4]") ? index.charAt(0) - '0' : 0)
                        : type == TTalk.wordType.CONST ? java.util.List.of("one", "two", "three", "four").indexOf(index) + 1 : 0;
                need(n >= 1);
                p.at += 3;
                need(p.at < p.end);
                TalkWasmStatements.Node base = element(p, k);
                if (base == null) {
                    String name = p.words.get(p.at).toLowerCase(java.util.Locale.ROOT);
                    need(p.types.get(p.at) == TTalk.wordType.VARIABLE && TalkWasmStatements.variable(name));
                    base = p.input(name);
                    p.at++;
                }
                pixels = true;
                return new Item(base, n);
            }
            return element(p, k);
        }
        /** <variable>[y][x], or null when the parser is at no such access */
        TalkWasmStatements.Node element(TalkWasmStatements.Parser p, Tokens k) {
            if (p.types.get(p.at) != TTalk.wordType.VARIABLE || !is(p, p.at + 1, "[")) return null;
            GridVar g = grid(p.words.get(p.at));
            int open1 = p.at + 1, close1 = close(k, open1, p.end);
            need(is(p, close1 + 1, "["));
            int open2 = close1 + 1, close2 = close(k, open2, p.end);
            need(!is(p, close2 + 1, "["));
            TalkWasmStatements.Node y = sub(k, open1 + 1, close1), x = sub(k, open2 + 1, close2);
            p.at = close2 + 1;
            return new PixelAt(g, y, x);
        }
        Stmt putPixel(Tokens k, int from, int to) {
            int close2 = to - 1, open2 = open(k, from, close2);
            need(open2 - 1 > from && is(k, open2 - 1, "]"));
            int close1 = open2 - 1, open1 = open(k, from, close1);
            need(open1 - 2 > from && k.t.get(open1 - 1) == TTalk.wordType.VARIABLE && is(k, open1 - 2, "into"));
            PutPixel s = new PutPixel(grid(k.w.get(open1 - 1)));
            commands.add("put");
            int first = from + 1, last = open1 - 2;
            need(first < last);
            ArrayList<Integer> joins = new ArrayList<>();
            int depth = 0;
            for (int i = first; i < last; i++) {
                if (is(k, i, "(")) depth++; else if (is(k, i, ")")) depth--;
                else if (depth == 0 && is(k, i, "&")) joins.add(i);
                need(!is(k, i, "&&"));
            }
            if (!joins.isEmpty()) {
                // r & "," & g & "," & b & "," & a, also v & "," & v & "," & v & ",255", r & comma & …: each of the four
                // components one whole-number expression or literal digits, the commas from literals
                ArrayList<TalkWasmStatements.Node> parts = new ArrayList<>();
                TalkWasmStatements.Node part = null;
                StringBuilder digits = new StringBuilder();
                boolean any = false; // the current component has something
                int start = first;
                for (int piece = 0; piece <= joins.size(); piece++) {
                    int end = piece < joins.size() ? joins.get(piece) : last;
                    need(end > start);
                    String text = null;
                    if (end == start + 1 && k.t.get(start) == TTalk.wordType.STRING) text = k.w.get(start);
                    else if (end == start + 1 && k.t.get(start) == TTalk.wordType.CONST && k.w.get(start).equalsIgnoreCase("comma")) text = ",";
                    if (text != null) {
                        for (int i = 0; i < text.length(); i++) {
                            char ch = text.charAt(i);
                            if (ch == ',') {
                                need(any);
                                parts.add(part != null ? part : new TalkWasmStatements.Constant(digits.toString()));
                                part = null; digits.setLength(0); any = false;
                            } else {
                                need(ch >= '0' && ch <= '9' && part == null);
                                digits.append(ch); any = true;
                            }
                        }
                    } else {
                        need(!any);
                        part = expression(k, start, end); any = true;
                    }
                    start = end + 1;
                }
                need(any);
                parts.add(part != null ? part : new TalkWasmStatements.Constant(digits.toString()));
                need(parts.size() == 4);
                s.parts = parts.toArray(new TalkWasmStatements.Node[0]);
            } else {
                if (last == first + 1 && k.t.get(first) == TTalk.wordType.STRING) { s.constant = TalkPixels.canonical(k.w.get(first)); need(s.constant >= 0); }
                else {
                    s.value = expression(k, first, last);
                    need(s.value instanceof TalkWasmStatements.Input || s.value instanceof PixelAt);
                }
            }
            s.y = sub(k, open1 + 1, close1);
            s.x = sub(k, open2 + 1, close2);
            return s;
        }

        /** item n of a pixel: its red, green, blue or alpha, a whole number 0-255 whose text is its digits */
        final class Item implements TalkWasmStatements.Node {
            final TalkWasmStatements.Node base; final int n;
            Item(TalkWasmStatements.Node base, int n) { this.base = base; this.n = n; }
            public TalkWasmStatements.Value emit(TalkWasmStatements.Emitter em) {
                TalkWasm.Bytes b = e.b;
                TalkWasmStatements.Value v = base.emit(e);
                if (!(base instanceof PixelAt)) { b.get(v.number).get(v.number).op(0x61).get(v.real).number(1).op(0x63).op(0x72); e.bail(); } // no pixel's text
                TalkWasmStatements.Value out = e.local();
                b.get(v.real).number(1).op(0xa1).op(0xab);
                int shift = n == 1 ? 16 : n == 2 ? 8 : n == 3 ? 0 : 24;
                if (shift > 0) b.op(0x41).u(shift).op(0x76);
                if (n != 4) { b.op(0x41); sleb(b, 255); b.op(0x71); }
                b.op(0xb8).set(out.number).number(0).set(out.real).number(0).set(out.format);
                return out;
            }
        }
        /** px[y][x]: the pixel's text, as NaN and real = ARGB + 1 */
        final class PixelAt implements TalkWasmStatements.Node {
            final GridVar grid; final TalkWasmStatements.Node y, x;
            PixelAt(GridVar grid, TalkWasmStatements.Node y, TalkWasmStatements.Node x) { this.grid = grid; this.y = y; this.x = x; }
            public boolean computed() { return false; }
            public TalkWasmStatements.Value emit(TalkWasmStatements.Emitter em) {
                TalkWasm.Bytes b = e.b;
                int a = address(grid, y, x);
                TalkWasmStatements.Value out = e.local();
                b.number(Double.NaN).set(out.number);
                b.get(a).op(0xab).op(0x28).u(2).u(0).op(0xb8).number(1).op(0xa0).set(out.real);
                b.number(0).set(out.format);
                return out;
            }
        }
        /** A local holding the whole number a value's text is (Integer.toString's form, 0 to 2^31-1); else the pass is given back */
        int whole(TalkWasmStatements.Node n) {
            TalkWasm.Bytes b = e.b;
            int out = e.slots++;
            if (n instanceof TalkWasmStatements.Constant c) {
                need(c.text != null && wholeText(c.text) && c.number >= 0);
                b.number(c.number).set(out);
                return out;
            }
            need(!(n instanceof PixelAt));
            if (staticWhole(n)) { // y - 1, x + dx…: whole numbers of counters and literals (callers check the range, so no overflow matters)
                emitWhole(n);
                b.set(out);
                b.number(1).get(out).op(0xa6).number(0).op(0x63); e.bail(); // -0 (0 * -1): its text is "-0"
                return out;
            }
            TalkWasmStatements.Value v = n.emit(e);
            if (n instanceof Item) { b.get(v.number).set(out); return out; }
            if (n instanceof TalkWasmStatements.Input && staticWhole(n)) {
                b.get(v.number).set(out); // the counter of a loop round it, set by nothing else: a whole number (format 3)
                return out;
            }
            // an integer computed here (kind 0: a 32-bit integer or -0, whose text is "-0") is its digits
            b.get(v.real).number(0).op(0x61).number(1).get(v.number).op(0xa6).number(0).op(0x64).op(0x71);
            if (n instanceof TalkWasmStatements.Input) {
                // a variable: computed integer (format 1), a counter, an item; else what its text is (canon)
                b.get(v.format).number(1).op(0x61).op(0x71);
                b.get(v.format).number(3).op(0x61).op(0x72).get(v.format).number(ITEM).op(0x61).op(0x72);
                b.op(0x04).op(0x7c).get(v.number).op(0x05);
                b.get(v.number).get(v.real).get(v.format).op(0x41); sleb(b, byValue.get(v).index);
                b.op(0x10).u(3).op(0xb7).op(0x0b).set(out);
            } else {
                b.op(0x04).op(0x7c).get(v.number).op(0x05).number(-1).op(0x0b).set(out); // computed: its raw text
            }
            b.get(out).number(0).op(0x63); e.bail();
            return out;
        }
        /** A whole number whose text is its digits whatever happens: a counter of a loop round it that nothing else sets, a
         *  whole literal, and + - * of those (32-bit integers in the interpreter; a result outside 32 bits fails the range
         *  check of keys and components, so the difference there never shows) */
        boolean staticWhole(TalkWasmStatements.Node n) { return staticWhole(n, 0); }
        boolean staticWhole(TalkWasmStatements.Node n, int depth) {
            if (depth > 4) return false;
            if (n instanceof TalkWasmStatements.Input in) {
                Var var = byValue.get(in.value);
                return var != null && counters.contains(var.name) && !assigned.contains(var.name);
            }
            if (n instanceof TalkWasmStatements.Constant c) return c.text != null && wholeText(c.text) && Math.abs(c.number) < 1e9;
            if (n instanceof TalkWasmStatements.Operation o)
                return o.right != null && (o.operator.equals("+") || o.operator.equals("-") || o.operator.equals("*"))
                        && staticWhole(o.left, depth + 1) && staticWhole(o.right, depth + 1);
            return false;
        }
        void emitWhole(TalkWasmStatements.Node n) {
            TalkWasm.Bytes b = e.b;
            if (n instanceof TalkWasmStatements.Input in) { b.get(in.value.number); return; }
            if (n instanceof TalkWasmStatements.Constant c) { b.number(c.number); return; }
            TalkWasmStatements.Operation o = (TalkWasmStatements.Operation) n;
            emitWhole(o.left); emitWhole(o.right);
            b.op(o.operator.equals("+") ? 0xa0 : o.operator.equals("-") ? 0xa1 : 0xa2);
        }
        void range(int local, double low, double high) { e.b.get(local).number(low).op(0x63).get(local).number(high).op(0x64).op(0x72); e.bail(); }
        void upTo(int local, int high) { e.b.get(local).number(1).op(0x63).get(local).get(high).op(0x64).op(0x72); e.bail(); }
        /** The byte address of px[y][x] (the keys read in order, each a whole number within the array) */
        int address(GridVar g, TalkWasmStatements.Node yNode, TalkWasmStatements.Node xNode) {
            int y = whole(yNode);
            upTo(y, g.rows);
            int x = whole(xNode);
            upTo(x, g.columns);
            int a = e.slots++;
            e.b.get(y).number(1).op(0xa1).get(g.columns).op(0xa2).get(x).op(0xa0).number(1).op(0xa1).number(4).op(0xa2).get(g.base).op(0xa0).set(a);
            return a;
        }
        /** The log's next entry's address (an i32) */
        void logEntry() { e.b.op(0x23).u(0).op(0x41).op(8).op(0x6c).get(logBase).op(0xab).op(0x6a); }
        void putPixel(PutPixel s) {
            TalkWasm.Bytes b = e.b;
            int packed = e.slots++;
            if (s.constant >= 0) b.number((double) s.constant).set(packed);
            else if (s.value != null) {
                TalkWasmStatements.Value v = s.value.emit(e);
                b.get(v.number).get(v.number).op(0x61).get(v.real).number(1).op(0x63).op(0x72); e.bail();
                b.get(v.real).number(1).op(0xa1).set(packed);
            } else {
                int[] c = new int[4];
                for (int i = 0; i < 4; i++) { c[i] = whole(s.parts[i]); range(c[i], 0, 255); }
                b.get(c[3]).number(16777216).op(0xa2).get(c[0]).number(65536).op(0xa2).op(0xa0)
                        .get(c[1]).number(256).op(0xa2).op(0xa0).get(c[2]).op(0xa0).set(packed); // ARGB
            }
            int a = address(s.grid, s.y, s.x);
            b.op(0x23).u(0).op(0xb8).get(logCap).op(0x66); e.bail(); // the log is full: this pass is the interpreter's
            logEntry(); b.get(a).op(0xab).op(0x36).u(2).u(0);
            logEntry(); b.get(a).op(0xab).op(0x28).u(2).u(0).op(0x36).u(2).u(4);
            b.op(0x23).u(0).op(0x41).op(1).op(0x6a).op(0x24).u(0);
            b.get(a).op(0xab).get(packed).op(0xab).op(0x36).u(2).u(0);
            // written: the whole array counts as written (the interpreter reads it back once, when it reads it)
            int slot = g0 + 5 * s.grid.index;
            b.op(0x41).op(0).number(0).op(0x39).u(3).u((slot + 3) * 8);
            b.op(0x41).op(0).number(1e18).op(0x39).u(3).u((slot + 4) * 8);
        }
        /** canon(number, real, format, variable) -> the whole number whose text the value has (wholeText), else -1. format 1 with
         *  real 0: computed, an integer's digits; 3 a counter, ITEM digits; 0 the variable's text at entry, -1-k variable k's,
         *  4+k literal k's (f0/l0 flags) */
        TalkWasm.Bytes canonBody() {
            TalkWasm.Bytes c = new TalkWasm.Bytes().u(0);
            c.get(2).number(1).op(0x61).op(0x04).op(0x7f);
              c.get(1).number(0).op(0x61);
            c.op(0x05);
              c.get(2).number(3).op(0x61).get(2).number(ITEM).op(0x61).op(0x72).op(0x04).op(0x7f);
                c.op(0x41).op(1);
              c.op(0x05);
                c.get(2).number(0).op(0x61).op(0x04).op(0x7f);
                  c.get(3).op(0x41).op(8).op(0x6c).op(0x2b).u(3).u(f0 * 8).number(0).op(0x62);
                c.op(0x05);
                  c.get(2).number(-1).op(0x65).get(2).number(-vars.size()).op(0x66).op(0x71).op(0x04).op(0x7f);
                    c.number(-1).get(2).op(0xa1).op(0xaa).op(0x41).op(8).op(0x6c).op(0x2b).u(3).u(f0 * 8).number(0).op(0x62);
                  c.op(0x05);
                    c.get(2).number(4).op(0x66).get(2).number(4 + literals.size()).op(0x63).op(0x71).op(0x04).op(0x7f);
                      c.get(2).number(4).op(0xa1).op(0xaa).op(0x41).op(8).op(0x6c).op(0x2b).u(3).u(l0 * 8).number(0).op(0x62);
                    c.op(0x05).op(0x41).op(0).op(0x0b);
                  c.op(0x0b);
                c.op(0x0b);
              c.op(0x0b);
            c.op(0x0b);
            c.get(0).get(0).op(0x9d).op(0x61).op(0x71);
            c.get(0).number(0).op(0x66).op(0x71);
            c.get(0).number(2147483647d).op(0x65).op(0x71);
            c.number(1).get(0).op(0xa6).number(0).op(0x64).op(0x71); // not -0
            c.op(0x04).op(0x7f).get(0).op(0xaa).op(0x05).op(0x41).op(0x7f).op(0x0b);
            c.op(0x0b);
            return c;
        }
        /** undo(): the cells written since the pass began get their values back, last first */
        TalkWasm.Bytes undoBody() {
            TalkWasm.Bytes c = new TalkWasm.Bytes().u(0);
            c.op(0x02).op(0x40).op(0x03).op(0x40);
            c.op(0x23).u(0).op(0x45).op(0x0d).u(1);
            c.op(0x23).u(0).op(0x41).op(1).op(0x6b).op(0x24).u(0);
            Runnable entry = () -> c.op(0x23).u(0).op(0x41).op(8).op(0x6c).op(0x41).op(0).op(0x2b).u(3).u(4 * 8).op(0xab).op(0x6a);
            entry.run(); c.op(0x28).u(2).u(0);
            entry.run(); c.op(0x28).u(2).u(4);
            c.op(0x36).u(2).u(0);
            c.op(0x0c).u(0);
            c.op(0x0b).op(0x0b).op(0x0b);
            return c;
        }

        TalkWasmStatements.Parser parser(Tokens k, int from, int to, boolean comparison) {
            for (int i = from; i < to; i++) {
                String w = k.w.get(i);
                if (k.t.get(i) == TTalk.wordType.STRING) need(plain(w)); // "1e3", " 5" compare as text
                else if (k.t.get(i) == TTalk.wordType.CONST) { words.add(w); need(!comparison || !w.equals("empty")); }
            }
            TalkWasmStatements.Parser p = new TalkWasmStatements.Parser(k.w, k.t, from, to);
            p.random = true;
            p.extra = this::access;
            return p;
        }
        void register(TalkWasmStatements.Parser p) {
            if (reads != null) for (String name : p.inputs.keySet()) reads.add(name.toLowerCase(java.util.Locale.ROOT));
            for (java.util.Map.Entry<String, TalkWasmStatements.Input> in : p.inputs.entrySet()) {
                var(in.getKey());
                inputs.add(new Object[]{in.getValue(), in.getKey().toLowerCase(java.util.Locale.ROOT)});
            }
            functions.addAll(p.functions);
            words.addAll(p.functions);
        }
        TalkWasmStatements.Node expression(Tokens k, int from, int to) {
            need(from < to);
            TalkWasmStatements.Parser p = parser(k, from, to, false);
            TalkWasmStatements.Node n = p.parse();
            register(p);
            return n;
        }
        /** Text the interpreter evaluates as a string of its own (Evalution(String)): tokenized again, as an expression */
        TalkWasmStatements.Node expression(String text) throws xTalkException {
            Tokens k = tokenize(text, false);
            return expression(k, 0, k.w.size());
        }
        Cond condition(String text) throws xTalkException {
            Tokens k = tokenize(text, false);
            need(!k.w.isEmpty());
            TalkWasmStatements.Parser p = parser(k, 0, k.w.size(), true);
            Cond c = or(p);
            need(p.at == k.w.size());
            register(p);
            return c;
        }
        Cond or(TalkWasmStatements.Parser p) {
            Cond c = and(p);
            while (p.is("or")) { p.at++; c = new Logic(false, c, and(p)); }
            return c;
        }
        Cond and(TalkWasmStatements.Parser p) {
            Cond c = relation(p);
            while (p.is("and")) { p.at++; c = new Logic(true, c, relation(p)); }
            return c;
        }
        Cond relation(TalkWasmStatements.Parser p) {
            if (p.is("(")) { // (a < b) and … : a condition in parentheses, otherwise an arithmetic one
                int at = p.at, depth = p.depth;
                try {
                    p.at++;
                    Cond c = or(p);
                    need(p.is(")"));
                    p.at++;
                    if (!p.is("=") && !p.is("<") && !p.is(">") && !p.is("is") && !p.is("≠") && !p.is("≤") && !p.is("≥")) return c;
                } catch (TalkWasmStatements.Reject r) { /* arithmetic in parentheses */ }
                p.at = at; p.depth = depth;
            }
            TalkWasmStatements.Node left = p.sum();
            String op = relop(p);
            TalkWasmStatements.Node right = p.sum();
            return new Compare(op, left, right);
        }
        String relop(TalkWasmStatements.Parser p) {
            if (p.is("=")) { p.at++; return "="; }
            if (p.is("≠")) { p.at++; return "<>"; }
            if (p.is("≤")) { p.at++; return "<="; }
            if (p.is("≥")) { p.at++; return ">="; }
            if (p.is("<")) {
                p.at++;
                if (p.is("=")) { p.at++; return "<="; }
                if (p.is(">")) { p.at++; return "<>"; }
                return "<";
            }
            if (p.is(">")) { p.at++; if (p.is("=")) { p.at++; return ">="; } return ">"; }
            if (p.is("is")) {
                p.at++;
                boolean not = p.is("not");
                if (not) p.at++;
                need(!p.is("in") && !p.is("a") && !p.is("an") && !p.is("within") && !p.is("no") && !p.is("among") && !p.is("not"));
                return not ? "<>" : "=";
            }
            throw new TalkWasmStatements.Reject();
        }

        /** The repeat line: the same reading as doScriptLines */
        Loop header(Tokens k, boolean nested) throws xTalkException {
            ArrayList<String> w = k.w;
            Loop loop = new Loop();
            need(!w.isEmpty() && w.get(0).equals("repeat"));
            for (int i = 1; i < w.size(); i++) need(!w.get(i).startsWith("--"));
            if (w.size() == 1 || w.size() == 2 && w.get(1).equalsIgnoreCase("forever")) { loop.kind = FOREVER; return loop; }
            String second = w.get(1).toLowerCase(java.util.Locale.ROOT);
            need(!second.equals("for") || w.size() < 3 || !w.get(2).equalsIgnoreCase("each"));
            if (second.equals("with")) {
                need(w.size() >= 7 && w.get(3).equals("="));
                int separator = -1;
                for (int next = 4; next < w.size(); next++) {
                    if (w.get(next).equalsIgnoreCase("to")) { separator = next; break; }
                    if (w.get(next).equalsIgnoreCase("down") && next + 1 < w.size() && w.get(next + 1).equalsIgnoreCase("to")) {
                        loop.down = true; separator = next; break;
                    }
                }
                need(separator > 4);
                int rangeEnd = separator + (loop.down ? 2 : 1);
                need(rangeEnd < w.size());
                loop.kind = WITH;
                loop.counter = var(w.get(2));
                if (nested) {
                    loop.from = expression(TTalk.combine(4, separator, w, k.t));
                    loop.to = expression(TTalk.combine(rangeEnd, w.size(), w, k.t));
                }
                return loop;
            }
            if (second.equals("while") || second.equals("until")) {
                loop.kind = second.equals("while") ? WHILE : UNTIL;
                loop.condition = condition(text(k, 2, w.size()));
                return loop;
            }
            loop.kind = TIMES;
            int next = 1;
            if (w.get(next).equalsIgnoreCase("for")) next++;
            int last = next;
            while (last < w.size() && !w.get(last).equalsIgnoreCase("times")) last++;
            need(last > next && (last == w.size() || last == w.size() - 1));
            if (nested) loop.from = expression(text(k, next, last));
            return loop;
        }
        /** repeat while's condition text as doScriptLines builds it (quoted words in quotes again) */
        String text(Tokens k, int from, int to) {
            StringBuilder s = new StringBuilder();
            for (int i = from; i < to; i++)
                s.append(' ').append(k.t.get(i) == TTalk.wordType.STRING ? "\"" + k.w.get(i) + "\"" : k.w.get(i));
            return s.toString();
        }

        ArrayList<Stmt> block(int first, int last) throws xTalkException {
            ArrayList<Stmt> out = new ArrayList<>();
            boolean top = topLevel;
            topLevel = false;
            for (int l = first; l <= last; l++) {
                if (top) { touched = new HashSet<>(); reads = new HashSet<>(); }
                int before = out.size();
                try { l = line(l, last, out); }
                finally {
                    if (top) { // the body's own statements in order: who is written before anything reads it
                        for (int i = before; i < out.size(); i++) {
                            Stmt s = out.get(i);
                            Var dest = s instanceof Assign a ? a.dest : s instanceof CharOf c ? c.dest : null;
                            if (dest != null) {
                                read.addAll(reads);
                                if (!read.contains(dest.name)) writeFirst.add(dest.name);
                                read.add(dest.name);
                            } else read.addAll(touched);
                        }
                        read.addAll(touched); // an if/loop writes only perhaps: what it touches counts as read
                        touched = null; reads = null;
                    }
                }
            }
            return out;
        }
        /** One line (a whole if or repeat structure); the last line it took */
        int line(int l, int last, ArrayList<Stmt> out) throws xTalkException {
            {
                Tokens k = tokens(l);
                if (k.w.isEmpty()) return l;
                need(++statements <= MAX_LINES);
                String w0 = k.w.get(0);
                need(k.t.get(0) != TTalk.wordType.STRING);
                if (w0.equals("repeat")) {
                    int end = TTalk.findEndRepeat(object, script, l, last);
                    need(end > l && end <= last);
                    Loop loop = header(k, true);
                    loop.body = block(l + 1, end - 1);
                    out.add(loop);
                    l = end;
                } else if (w0.equals("if")) {
                    TTalk.IfStructure s = TTalk.cachedIfStructure(object, script, l, last, k.w, k.t);
                    need(s.endLine >= l && s.endLine <= last && !s.branches.isEmpty());
                    If node = new If();
                    for (TTalk.IfBranch branch : s.branches) {
                        need(branch.nested == null);
                        node.conditions.add(branch.condition == null ? null : condition(branch.condition));
                        ArrayList<Stmt> body;
                        if (branch.inlineScript != null) body = inline(branch);
                        else if (branch.bodyStart >= 0 && branch.bodyStart <= branch.bodyEnd) body = block(branch.bodyStart, branch.bodyEnd);
                        else body = new ArrayList<>();
                        node.bodies.add(body);
                    }
                    out.add(node);
                    l = s.endLine;
                } else out.add(statement(k, 0, k.w.size()));
            }
            return l;
        }
        ArrayList<Stmt> inline(TTalk.IfBranch branch) throws xTalkException {
            ArrayList<Stmt> out = new ArrayList<>();
            for (String text : branch.inlineLines()) {
                Tokens k = tokenize(text, true);
                if (k.w.isEmpty()) continue;
                need(++statements <= MAX_LINES);
                out.add(statement(k, 0, k.w.size()));
            }
            return out;
        }
        Stmt statement(Tokens k, int from, int to) {
            ArrayList<String> w = k.w;
            ArrayList<TTalk.wordType> t = k.t;
            need(to - from >= 2 && t.get(from) != TTalk.wordType.STRING);
            need(t.get(to - 1) == TTalk.wordType.STRING || !w.get(to - 1).equalsIgnoreCase("else"));
            String command = w.get(from).toLowerCase(java.util.Locale.ROOT);
            switch (command) {
                case "exit": case "next":
                    need(to - from == 2 && w.get(from + 1).equalsIgnoreCase("repeat"));
                    return new Jump(command.equals("exit"));
                case "put": {
                    if (to - from >= 8 && is(k, to - 1, "]")) return putPixel(k, from, to);
                    need(to - from >= 4 && w.get(to - 2).equals("into") && t.get(to - 2) != TTalk.wordType.STRING);
                    need(t.get(to - 1) == TTalk.wordType.VARIABLE);
                    Var dest = var(w.get(to - 1));
                    commands.add("put");
                    Stmt text = charOf(k, from + 1, to - 2, dest);
                    return text != null ? text : new Assign(dest, expression(k, from + 1, to - 2));
                }
                case "get": {
                    commands.add("get");
                    Var it = var("it");
                    Stmt text = charOf(k, from + 1, to, it);
                    return text != null ? text : new Assign(it, expression(k, from + 1, to));
                }
                default: break;
            }
            String separator, operator;
            switch (command) {
                case "add": separator = "to"; operator = "+"; break;
                case "subtract": separator = "from"; operator = "-"; break;
                case "multiply": separator = "by"; operator = "*"; break;
                case "divide": separator = "by"; operator = "/"; break;
                default: throw new TalkWasmStatements.Reject();
            }
            int join = -1;
            for (int i = from + 1; i < to; i++) if (w.get(i).equals(separator) && t.get(i) != TTalk.wordType.STRING) join = i;
            need(join > from + 1);
            int dest = command.equals("add") || command.equals("subtract") ? to - 1 : from + 1;
            need(dest == from + 1 ? join == from + 2 : join == to - 2);
            need(t.get(dest) == TTalk.wordType.VARIABLE);
            Var d = var(w.get(dest));
            TalkWasmStatements.Parser p = parser(k, dest == from + 1 ? join + 1 : from + 1, dest == from + 1 ? to : join, false);
            TalkWasmStatements.Node operand = p.parse(), previous = p.input(d.name);
            register(p);
            commands.add(command); commands.add("put"); // an arithmetic command may be rewritten as a put to a handler
            return new Assign(d, new TalkWasmStatements.Operation(operator, previous, operand));
        }

        /** char <numeric expression> of "<literal>" over the whole range, else null */
        Stmt charOf(Tokens k, int from, int to, Var dest) {
            if (to - from < 4 || k.t.get(from) != TTalk.wordType.CHUNK || !k.w.get(from).equals("char")) return null;
            need(k.t.get(to - 1) == TTalk.wordType.STRING && k.w.get(to - 2).equals("of") && k.t.get(to - 2) != TTalk.wordType.STRING);
            String literal = k.w.get(to - 1);
            need(!literal.isEmpty() && literal.length() <= 4096);
            for (int i = 0; i < literal.length(); i++) need(!Character.isSurrogate(literal.charAt(i)) && literal.charAt(i) != '\r' && literal.charAt(i) != '\n');
            int depth = 0;
            for (int i = from + 1; i < to - 2; i++) { // the index has no other chunk or of
                String w = k.w.get(i);
                if (k.t.get(i) == TTalk.wordType.STRING) continue;
                if (w.equals("(")) depth++; else if (w.equals(")")) depth--;
                need(k.t.get(i) != TTalk.wordType.CHUNK && (depth > 0 || !w.equals("of")));
            }
            TalkWasmStatements.Parser p = parser(new Tokens(k.w, k.t), from + 1, to - 2, false);
            TalkWasmStatements.Node index = p.parse();
            register(p);
            return new CharOf(dest, index, literal);
        }

        // ---- emitting ----
        int literal(String text) {
            for (int i = 0; i < literals.size(); i++) if (literals.get(i) == text) return i;
            literals.add(text);
            return literals.size() - 1;
        }
        void charOf(CharOf c) {
            TalkWasm.Bytes b = e.b;
            TalkWasmStatements.Value n = c.index.emit(e);
            // an integer 1…length (the interpreter's other cases: empty, rounding of a formatted real) stay interpreted
            b.get(n.real).number(0).op(0x62).get(n.number).number(1).op(0x63).op(0x72).get(n.number).number(c.literal.length()).op(0x64).op(0x72);
            b.get(n.number).get(n.number).op(0x62).op(0x72); // a text variable as the index
            e.bail();
            int k = literal(c.literal);
            need(k < 32768);
            TalkWasmStatements.Value d = c.dest.v;
            b.number(Double.NaN).set(d.number).number(0).set(d.real);
            b.number(TEXT - k * 65536.0 + 1).get(n.number).op(0xa1).set(d.format); // TEXT - (k<<16 | n-1)
        }
        int push() { labels.add(0); return labels.size() - 1; }
        void pop() { labels.remove(labels.size() - 1); }
        void br(int label) { e.b.op(0x0c).u(labels.size() - 1 - label); }
        void brIf(int label) { e.b.op(0x0d).u(labels.size() - 1 - label); }
        void returnIf(double status) { e.b.op(0x04).op(0x40).number(status).op(0x0f).op(0x0b); }
        void load(int slot, int local) { e.b.op(0x41).op(0).op(0x2b).u(3).u(slot * 8).set(local); }
        void store(int slot, int local) { e.b.op(0x41).op(0).get(local).op(0x39).u(3).u(slot * 8); }
        void checkpoint() {
            if (pixels) e.b.op(0x41).op(0).op(0x24).u(0); // a new pass: nothing to undo
            store(0, counter);
            for (Var v : vars.values()) {
                store(HEAD + 3 * v.index, v.v.number); store(HEAD + 3 * v.index + 1, v.v.real); store(HEAD + 3 * v.index + 2, v.v.format);
            }
        }
        void charge() { e.b.get(fuel).number(1).op(0xa1).set(fuel); }
        void setCounter(Var v, int local) { e.b.get(local).set(v.v.number).number(0).set(v.v.real).number(3).set(v.v.format); }
        /** A loop bound must be a whole 32-bit number, as wholeNumber/floor read the formatted text */
        void whole(int local) {
            e.b.get(local).op(0x9d).get(local).op(0x62).get(local).op(0x99).number(2147483647d).op(0x64).op(0x72);
            e.bail();
        }
        void body(ArrayList<Stmt> list) { for (Stmt s : list) statement(s); }
        void markAssigned(ArrayList<Stmt> list) {
            for (Stmt s : list) {
                if (s instanceof Assign a) assigned.add(a.dest.name);
                else if (s instanceof CharOf c) assigned.add(c.dest.name);
                else if (s instanceof If i) for (ArrayList<Stmt> b : i.bodies) markAssigned(b);
                else if (s instanceof Loop l) markAssigned(l.body);
            }
        }
        void statement(Stmt s) {
            TalkWasm.Bytes b = e.b;
            if (s instanceof Assign a) { charge(); assign(a); }
            else if (s instanceof If i) { charge(); branch(i, 0); }
            else if (s instanceof Loop l) loop(l);
            else if (s instanceof CharOf c) { charge(); charOf(c); }
            else if (s instanceof PutPixel p) { charge(); putPixel(p); }
            else {
                Jump j = (Jump) s;
                LoopLabels in = loops.get(loops.size() - 1);
                if (j.exit && in.top) { checkpoint(); b.number(1).op(0x0f); }
                else br(j.exit ? in.breakLabel : in.nextLabel);
            }
        }
        void branch(If i, int at) {
            if (at >= i.conditions.size()) return;
            Cond c = i.conditions.get(at);
            if (c == null) { body(i.bodies.get(at)); return; }
            condition(c);
            e.b.op(0x04).op(0x40); push();
            body(i.bodies.get(at));
            if (at + 1 < i.conditions.size()) { e.b.op(0x05); branch(i, at + 1); }
            e.b.op(0x0b); pop();
        }
        void assign(Assign a) {
            TalkWasm.Bytes b = e.b;
            TalkWasmStatements.Value d = a.dest.v;
            if (a.expression instanceof TalkWasmStatements.Input in) { // put x into y: y gets x's text as it is
                TalkWasmStatements.Value s = in.value;
                Var source = byValue.get(s);
                b.get(s.number).set(d.number).get(s.real).set(d.real);
                b.number(-1 - source.index).get(s.format).get(s.format).number(0).op(0x61).op(0x1b).set(d.format);
            } else if (a.expression instanceof TalkWasmStatements.Constant c) {
                need(c.text != null);
                int k = literals.indexOf(c.text);
                if (k < 0 || literals.get(k) != c.text) { literals.add(c.text); k = literals.size() - 1; }
                b.number(c.number).set(d.number).number(c.real ? 1 : 0).set(d.real).number(4 + k).set(d.format);
            } else if (a.expression instanceof PixelAt || a.expression instanceof Item) { // put px[y][x] into p / put item 1 of p into r
                TalkWasmStatements.Value v = a.expression.emit(e);
                b.get(v.number).set(d.number).get(v.real).set(d.real).number(a.expression instanceof Item ? ITEM : PIXEL).set(d.format);
            } else {
                TalkWasmStatements.Value v = a.expression.emit(e);
                b.get(v.number).set(d.number).get(v.real).set(d.real).get(v.format).number(1).op(0xa0).set(d.format);
            }
        }
        /** Leaves an i32: the interpreter's numeric comparison (nearlyEqual for =, and around < >) */
        void condition(Cond c) {
            TalkWasm.Bytes b = e.b;
            if (c instanceof Logic l) {
                condition(l.left); condition(l.right);
                b.op(l.and ? 0x71 : 0x72);
                return;
            }
            Compare k = (Compare) c;
            TalkWasmStatements.Value x = k.left.emit(e), y = k.right.emit(e);
            b.get(x.number).get(x.number).op(0x62).get(y.number).get(y.number).op(0x62).op(0x72); e.bail(); // text compares as text
            // same = x==y || |x-y| <= 4*ulp(max(|x|,|y|))
            b.get(x.number).get(y.number).op(0x61);
            b.get(x.number).get(y.number).op(0xa1).op(0x99);
            b.get(x.number).op(0x99).get(y.number).op(0x99).op(0xa5);
            b.op(0xbd).op(0x42); sleb(b, 0x7FF0000000000000L); b.op(0x83).op(0xbf);
            b.number(Math.scalb(1.0, -52)).op(0xa2).number(Double.MIN_VALUE).op(0xa5).number(4).op(0xa2);
            b.op(0x65).op(0x72);
            switch (k.op) {
                case "=": break;
                case "<>": b.op(0x45); break;
                case "<": b.op(0x45).get(x.number).get(y.number).op(0x63).op(0x71); break;
                case ">": b.op(0x45).get(x.number).get(y.number).op(0x64).op(0x71); break;
                case "<=": b.get(x.number).get(y.number).op(0x65).op(0x72); break;
                case ">=": b.get(x.number).get(y.number).op(0x66).op(0x72); break;
                default: throw new TalkWasmStatements.Reject();
            }
        }
        static void sleb(TalkWasm.Bytes b, long v) {
            while (true) {
                int byt = (int) (v & 0x7f);
                v >>= 7;
                boolean done = v == 0 && (byt & 0x40) == 0 || v == -1 && (byt & 0x40) != 0;
                b.op(done ? byt : byt | 0x80);
                if (done) return;
            }
        }
        void loop(Loop l) {
            TalkWasm.Bytes b = e.b;
            int c = e.slots++, end = e.slots++;
            if (l.kind == WITH) {
                TalkWasmStatements.Value from = l.from.emit(e);
                b.get(from.number).set(c); whole(c);
                TalkWasmStatements.Value to = l.to.emit(e);
                b.get(to.number).set(end); whole(end);
            } else if (l.kind == TIMES) {
                TalkWasmStatements.Value n = l.from.emit(e);
                b.get(n.number).set(end); whole(end);
                b.number(0).set(c);
            }
            b.op(0x02).op(0x40); int breakLabel = push();
            b.op(0x03).op(0x40); int topLabel = push();
            switch (l.kind) {
                case WITH: b.get(c).get(end).op(l.down ? 0x63 : 0x64); brIf(breakLabel); break;
                case TIMES: b.get(c).get(end).op(0x66); brIf(breakLabel); break;
                case WHILE: condition(l.condition); b.op(0x45); brIf(breakLabel); break;
                case UNTIL: condition(l.condition); brIf(breakLabel); break;
                default: break;
            }
            charge();
            b.get(fuel).number(-HARD).op(0x63); e.bail(); // one pass of the outer loop may not go on for ever natively
            if (l.kind == WITH) setCounter(l.counter, c);
            b.op(0x02).op(0x40); int next = push();
            loops.add(new LoopLabels(false, breakLabel, next));
            if (l.kind == WITH) counters.add(l.counter.name);
            body(l.body);
            if (l.kind == WITH) counters.remove(counters.size() - 1);
            loops.remove(loops.size() - 1);
            b.op(0x0b); pop();
            if (l.kind == WITH || l.kind == TIMES) b.get(c).number(l.down ? -1 : 1).op(0xa0).set(c);
            br(topLabel);
            b.op(0x0b); pop();
            b.op(0x0b); pop();
        }
    }
}
