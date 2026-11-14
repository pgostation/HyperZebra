import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whole-function compiler for the browser. Emits ordinary WebAssembly, independent of TeaVM's GC heap.
 * Only side-effect-free integer functions are accepted: local assignments, arithmetic, numeric comparisons,
 * block if/else and counted repeats. Unsupported syntax rejects the WHOLE function before it runs.
 * Every intermediate must remain an integer in [-999999999,999999999]; otherwise the module returns NaN
 * and the interpreter starts the function again. No globals, host operations or calls can have happened.
 * This deliberately conservative domain preserves HyperTalk's text/number and rounding rules.
 * Modules have a finite loop budget, so a compiled call cannot monopolize the player's input loop.
 * The backend is installed only by WebMain; desktop execution keeps its existing bytecode JIT.
 */
final class TalkWasm {
    private TalkWasm() {}
    interface Code {
        double run(double[] arguments);
        int kind();
        /** A loop module (TalkWasmLoops): io is copied into its exported memory "mem" from byte 0, run() is called with no
         *  arguments, and the memory is copied back into io. */
        default double runMemory(double[] io) { throw new UnsupportedOperationException(); }
        /** A loop module with arrays of pixels (TalkWasmLoops): as runMemory in the shared memory (store), and a pass given
         *  back (NaN) or a trap calls the module's undo() first, so the cells and io are as at the start of that pass. */
        default double runShared(double[] io) { throw new UnsupportedOperationException(); }
    }
    /** The memory every loop module imports as env.mem (its io from byte 0): arrays of pixels stay in it between loops,
     *  and come back into their int[] only when the interpreter reads them (TalkPixels.Grid.pull). Null: no such memory. */
    interface Store {
        /** at least this many bytes */
        void ensure(int bytes);
        /** cells[from …] (count of them) to the byte address */
        void put(int[] cells, int from, int count, int address);
        /** the count ints at the byte address into cells[from …] */
        void get(int[] cells, int from, int count, int address);
    }
    static Store store;
    interface Backend { Code compile(byte[] module); }
    static Backend backend;
    static int compiled, executed, rejected, fallbacks;
    static final int WORK_BUDGET = 1000000;
    private static final double LIMIT = 999999999;

    static final class Cached {
        final Program program;
        final Code code;
        Cached(Program program, Code code) { this.program = program; this.code = code; }
    }

    /** null means that the unmodified interpreter must execute this invocation. */
    static Result enter(TTalk talk, HandlerEntry handler, OObject object, MemoryData memory) {
        if (backend == null || !memory.isFunction || "false".equals(System.getProperty("hz.talk.wasm"))
                || TTalk.stop || TTalk.abandonedThread() || TTalk.tracemode != 0 || object.checkpoints != null
                || StackSpeed.limited() || TTalk.numFormat != TTalk.DEFAULT_NUMBER_FORMAT_INSTANCE
                || talk.sendPending() || UiHost.get().xcmd68kHasSessions()) return null;
        Cached cached = handler.wasm;
        if (cached == null) {
            Program p = compile(handler.script, handler.start, handler.end, handler.parameters);
            Code code = null;
            if (p != null) {
                try { code = backend.compile(p.module); } catch (RuntimeException e) { /* CSP or unavailable engine */ }
            }
            handler.wasm = cached = new Cached(p, code);
            if (code == null) rejected++; else compiled++;
        }
        if (cached.code == null) return null;
        // Commands can be overridden, including by a handler installed after this module was cached.
        for (String command : cached.program.commands)
            if (TTalk.commandHandlerKind(command, object) != 0) return null;
        double[] args = new double[handler.parameters.length * 2];
        for (int i = 0; i < handler.parameters.length; i++) {
            String name = handler.parameters[i];
            int slot = memory.indexOfName(name);
            if (slot < 0) return null;
            String text = memory.valueList.get(slot);
            // Preserve spelling for a bare return/copy of an argument: 007, +3, 3.0, whitespace, -0 are rejected.
            if (text == null || !text.matches("0|-?[1-9][0-9]{0,8}")) return null;
            args[i * 2] = Double.parseDouble(text);
            args[i * 2 + 1] = memory.rawNumbers != null && memory.rawNumbers.contains(name) ? 1 : 0;
        }
        double value;
        try { value = cached.code.run(args); } catch (RuntimeException e) { fallbacks++; return null; }
        if (!Double.isFinite(value)) { fallbacks++; return null; }
        executed++;
        Result r = new Result();
        r.ret = 3;
        int kind = cached.code.kind();
        r.numeric = kind != 0;
        r.theResult = kind == 2 ? TTalk.formatRealNumber(value) : Integer.toString((int)value);
        return r;
    }

    static final class Program {
        final byte[] module;
        final Set<String> commands;
        Program(byte[] module, Set<String> commands) { this.module = module; this.commands = commands; }
    }
    private static final class Unsupported extends RuntimeException {}
    private static void require(boolean condition) { if (!condition) throw new Unsupported(); }

    static Program compile(ArrayList<String> script, int start, int end, String[] parameters) {
        if (parameters == null || parameters.length > 32 || end - start > 256) return null;
        try { return new Compiler(script, start, end, parameters).compile(); }
        catch (Unsupported | NumberFormatException e) { return null; }
    }

    /** Small binary writer: all indices and section/body lengths use unsigned LEB128. */
    static final class Bytes extends ByteArrayOutputStream {
        Bytes op(int b) { write(b); return this; }
        Bytes u(int n) { do { int b = n & 127; n >>>= 7; write(b | (n == 0 ? 0 : 128)); } while (n != 0); return this; }
        Bytes data(byte[] b) { write(b, 0, b.length); return this; }
        Bytes text(String s) { byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8); return u(b.length).data(b); }
        Bytes section(int id, Bytes b) { return op(id).u(b.size()).data(b.toByteArray()); }
        Bytes number(double d) { op(0x44); long bits = Double.doubleToRawLongBits(d); for (int i=0;i<8;i++) op((int)(bits >>> (i*8)) & 255); return this; }
        Bytes get(int slot) { return op(0x20).u(slot); }
        Bytes set(int slot) { return op(0x21).u(slot); }
    }
    private static final class Var {
        final int value, tag;
        Var(int value) { this.value = value; tag = value + 1; }
    }
    private interface Expr {
        void emit(Bytes b);
        default void tag(Bytes b) { b.number(1); }
    }
    private static final class Literal implements Expr {
        final double value;
        Literal(double value) { this.value = value; }
        public void emit(Bytes b) { b.number(value); }
        public void tag(Bytes b) { b.number(0); }
    }
    private static final class Read implements Expr {
        final Var var;
        Read(Var var) { this.var = var; }
        public void emit(Bytes b) { b.get(var.value); }
        public void tag(Bytes b) { b.get(var.tag); }
    }

    private static final class Compiler {
        final ArrayList<String> lines = new ArrayList<>();
        final HashMap<String,Var> vars = new HashMap<>();
        final Set<String> commands = new HashSet<>();
        Set<String> assigned = new HashSet<>();
        final Bytes body = new Bytes();
        final int parameterSlots;
        int slots, line, depth;
        final int scratch, fuel;
        Compiler(ArrayList<String> script, int start, int end, String[] params) {
            parameterSlots = params.length * 2;
            for (String name : params) { require(!vars.containsKey(name)); variable(name); assigned.add(name); }
            scratch = slots++; fuel = slots++;
            for (int i=start;i<end;i++) {
                String text = script.get(i);
                int comment = text.indexOf("--");
                if (comment >= 0) text = text.substring(0, comment);
                text = text.trim().toLowerCase(Locale.ROOT);
                if (!text.isEmpty()) lines.add(text);
            }
            require(!lines.isEmpty());
        }
        Var variable(String name) {
            require(name.matches("[a-z][a-z0-9_]*") && !name.equals("it") && !name.equals("me"));
            try { TTalk.checkVariableName(name); } catch (xTalkException e) { throw new Unsupported(); }
            require(!TTalk.constantSet.contains(name) && !TTalk.commandSet.contains(name) && !TTalk.operatorSet.contains(name));
            Var v = vars.get(name);
            if (v == null) { require(slots < 256); v = new Var(slots); slots += 2; vars.put(name, v); }
            return v;
        }
        Program compile() {
            body.number(WORK_BUDGET).set(fuel);
            block();
            require(line == lines.size() && lines.get(lines.size()-1).startsWith("return "));
            // If control falls through, request interpretation (also makes the Wasm body type-correct).
            body.number(Double.NaN).op(0x0b);
            Bytes module = new Bytes().data(new byte[]{0,97,115,109,1,0,0,0});
            Bytes type = new Bytes().u(1).op(0x60).u(parameterSlots);
            for (int i=0;i<parameterSlots;i++) type.op(0x7c);
            type.u(1).op(0x7c);
            module.section(1, type).section(3, new Bytes().u(1).u(0));
            // A separate numeric flag avoids confusing a string-valued parameter with a computed number.
            module.section(6, new Bytes().u(1).op(0x7f).op(1).op(0x41).op(0).op(0x0b));
            module.section(7, new Bytes().u(2).text("run").op(0).u(0).text("numeric").op(3).u(0));
            Bytes code = new Bytes().u(1).u(slots - parameterSlots).op(0x7c).data(body.toByteArray());
            module.section(10, new Bytes().u(1).u(code.size()).data(code.toByteArray()));
            require(module.size() <= 65536);
            return new Program(module.toByteArray(), Set.copyOf(commands));
        }
        void block() {
            require(++depth <= 24);
            while (line < lines.size()) {
                String text = lines.get(line);
                if (text.equals("else") || text.startsWith("end ")) break;
                line++;
                charge(text.length()+1);
                if (text.startsWith("if ")) conditional(text);
                else if (text.startsWith("repeat ")) repeat(text);
                else if (text.startsWith("return ")) {
                    Expr e = expression(text.substring(7)); e.tag(body); body.op(0xaa).op(0x24).u(0);
                    e.emit(body); guard(); body.op(0x0f);
                } else assignment(text);
            }
            depth--;
        }
        void conditional(String text) {
            require(text.endsWith(" then"));
            condition(text.substring(3, text.length()-5));
            body.op(0x04).op(0x40);
            Set<String> before = new HashSet<>(assigned);
            block();
            Set<String> yes = assigned;
            assigned = new HashSet<>(before);
            if (line < lines.size() && lines.get(line).equals("else")) { line++; body.op(0x05); block(); }
            require(line < lines.size() && lines.get(line++).equals("end if"));
            assigned.retainAll(yes);
            body.op(0x0b);
        }
        void repeat(String text) {
            Set<String> before = new HashSet<>(assigned);
            Var counter;
            int bound = slots++, current = slots++;
            int step = 1;
            if (text.startsWith("repeat with ")) {
                int equals = text.indexOf('=', 12), to = text.indexOf(" down to ", 12);
                if (to >= 0) step = -1; else to = text.indexOf(" to ", 12);
                require(equals >= 0 && to > equals);
                counter = variable(text.substring(12, equals).trim());
                Expr from = expression(text.substring(equals+1, to));
                Expr end = expression(text.substring(to+(step == 1 ? 4 : 9)));
                // HyperTalk evaluates both bounds before the counter is assigned.
                end.emit(body); guard(); body.set(bound);
                from.emit(body); guard(); body.set(current);
            } else {
                String count = text.substring(7);
                if (count.endsWith(" times")) count = count.substring(0, count.length()-6);
                Expr n = expression(count);
                counter = new Var(slots); slots += 2;
                n.emit(body); guard(); body.set(bound); body.number(1).set(current);
            }
            // The counter need not exist after a zero-iteration repeat; only variables known before are safe then.
            String counterName = text.startsWith("repeat with ") ? text.substring(12, text.indexOf('=',12)).trim() : null;
            if (counterName != null) assigned.add(counterName);
            body.op(0x02).op(0x40).op(0x03).op(0x40);
            body.get(current).get(bound).op(step == 1 ? 0x64 : 0x63).op(0x0d).u(1);
            body.get(current).set(counter.value); body.number(0).set(counter.tag);
            charge(1);
            block();
            require(line < lines.size() && lines.get(line++).equals("end repeat"));
            body.get(current).number(step).op(0xa0); guard(); body.set(current);
            body.op(0x0c).u(0).op(0x0b).op(0x0b);
            assigned = before;
        }
        void assignment(String text) {
            String name, value;
            Expr e;
            if (text.startsWith("put ")) {
                int into = text.lastIndexOf(" into "); require(into > 4);
                name = text.substring(into+6).trim(); value = text.substring(4, into);
                e = expression(value); commands.add("put");
            } else {
                String[] verbs = {"add", "subtract", "multiply", "divide"};
                String[] joins = {" to ", " from ", " by ", " by "};
                int k = 0; while (k < verbs.length && !text.startsWith(verbs[k]+" ")) k++;
                require(k < verbs.length);
                int at = text.lastIndexOf(joins[k]); require(at > verbs[k].length());
                if (k < 2) { name = text.substring(at+joins[k].length()).trim(); value = text.substring(verbs[k].length()+1, at); }
                else { name = text.substring(verbs[k].length()+1, at).trim(); value = text.substring(at+joins[k].length()); }
                require(assigned.contains(name));
                Expr left = new Read(variable(name)), right = expression(value);
                e = binary(left, right, new int[]{0xa0,0xa1,0xa2,0xa3}[k]); commands.add(verbs[k]); commands.add("put");
            }
            Var v = variable(name);
            // putComputedValue keeps a raw tag only when formatting changes the text. An integer
            // arithmetic result stores ordinary text; a whole real keeps its raw ".0" and tag.
            e.tag(body); body.number(2).op(0x61).op(0xb8).number(2).op(0xa2).set(v.tag);
            e.emit(body); guard(); body.set(v.value);
            assigned.add(name);
        }
        Expr binary(Expr left, Expr right, int op) {
            return new Expr() {
                public void emit(Bytes b) { left.emit(b); right.emit(b); b.op(op); guard(b); }
                public void tag(Bytes b) {
                    if (op == 0xa3) b.number(2); // division produces a real, even when its value is whole
                    else { left.tag(b); right.tag(b); b.op(0xa5).number(2).op(0x61).op(0xb8).number(1).op(0xa0); }
                }
            };
        }
        void condition(String text) {
            Parser p = new Parser(text);
            Expr left = p.sum();
            String op = p.take();
            int code;
            switch (op) {
                case "=": case "is": code=0x61; break;
                case "<>": case "≠": code=0x62; break;
                case "<": code=0x63; break; case ">": code=0x64; break;
                case "<=": case "≤": code=0x65; break; case ">=": case "≥": code=0x66; break;
                default: throw new Unsupported();
            }
            if (op.equals("is") && p.peek().equals("not")) { p.take(); code=0x62; }
            Expr right = p.sum(); require(p.at == p.tokens.size());
            left.emit(body); right.emit(body); body.op(code);
        }
        Expr expression(String text) { Parser p = new Parser(text); Expr e = p.sum(); require(p.at == p.tokens.size()); return e; }
        final class Parser {
            final ArrayList<String> tokens = new ArrayList<>(); int at, nesting;
            Parser(String text) {
                for (int i=0;i<text.length();) {
                    char c=text.charAt(i);
                    if (Character.isWhitespace(c)) { i++; continue; }
                    int j=i+1;
                    if (c>='0' && c<='9') { while(j<text.length() && Character.isDigit(text.charAt(j))) j++; }
                    else if (c>='a' && c<='z') { while(j<text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j)=='_')) j++; }
                    else {
                        require("+-*/()=<>≤≥≠".indexOf(c)>=0);
                        if (j<text.length() && (c=='<' || c=='>') && (text.charAt(j)=='=' || c=='<' && text.charAt(j)=='>')) j++;
                    }
                    tokens.add(text.substring(i,j)); i=j;
                }
                require(tokens.size() <= 128);
            }
            String peek() { return at<tokens.size() ? tokens.get(at) : ""; }
            String take() { require(at<tokens.size()); return tokens.get(at++); }
            Expr sum() { Expr a=product(); while(peek().equals("+") || peek().equals("-")) { String op=take(); a=binary(a,product(),op.equals("+")?0xa0:0xa1); } return a; }
            Expr product() { Expr a=atom(); while(peek().equals("*") || peek().equals("/")) { String op=take(); a=binary(a,atom(),op.equals("*")?0xa2:0xa3); } return a; }
            Expr atom() {
                require(++nesting<=24);
                String t=take(); Expr e;
                if(t.equals("(")) { e=sum(); require(take().equals(")")); }
                else if(t.equals("-")) { Expr a=atom(); e=new Expr() {
                    public void emit(Bytes b) { a.emit(b); b.op(0x9a); guard(b); }
                    public void tag(Bytes b) { a.tag(b); b.number(2).op(0x61).op(0xb8).number(1).op(0xa0); }
                }; }
                else if(t.matches("0|[1-9][0-9]{0,8}")) e=new Literal(Double.parseDouble(t));
                else { require(assigned.contains(t)); e=new Read(variable(t)); }
                nesting--; return e;
            }
        }
        void charge(int cost) {
            body.get(fuel).number(cost).op(0xa1).op(0x22).u(fuel).number(0).op(0x63);
            bailIf();
        }
        void guard() { guard(body); }
        void guard(Bytes b) {
            // Keep the operand on the stack while rejecting fractions, overflow, NaN, infinity and negative zero.
            b.op(0x22).u(scratch).op(0x99).number(LIMIT).op(0x65);
            b.get(scratch).op(0x9d).get(scratch).op(0x61).op(0x71).op(0x45);
            bailIf(b);
            b.get(scratch).number(0).op(0x61).number(1).get(scratch).op(0xa3).number(0).op(0x63).op(0x71);
            bailIf(b);
            b.get(scratch);
        }
        void bailIf() { bailIf(body); }
        void bailIf(Bytes b) { b.op(0x04).op(0x40).number(Double.NaN).op(0x0f).op(0x0b); }
    }
}
