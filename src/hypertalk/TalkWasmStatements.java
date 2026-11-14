import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Numeric statements inside arbitrary HyperTalk, including on handlers and dynamic do/send texts.
 * Runs inside doScriptLines AFTER its scope, input, debugger and speed checks. A rejected statement simply
 * continues down the original interpreter path. No expression with an unknown/impure call is partially run.
 * Values are read from MemoryData on every invocation, and a write is committed only after the entire native
 * expression succeeds. This makes a guard failure local to this statement, even after earlier side effects.
 */
final class TalkWasmStatements {
    private TalkWasmStatements() {}
    static int compiled, executed, rejected, fallbacks;
    static final int THRESHOLD = Math.max(1, Integer.getInteger("hz.talk.wasm.statementthreshold", 8));
    private static final Set<String> FUNCTIONS = Set.of("abs", "sqrt", "trunc", "round", "min", "max", "sum", "average");

    static final class Cache {
        final ArrayList<String> script;
        final Entry[] lines;
        Cache(ArrayList<String> script) { this.script = script; lines = new Entry[script.size()]; }
    }
    private static final class Entry {
        final String source;
        final ArrayList<String> words;
        final ArrayList<TTalk.wordType> types;
        Plan plan;
        int count;
        boolean attempted;
        Entry(String source, ArrayList<String> words, ArrayList<TTalk.wordType> types) {
            this.source = source; this.words = words; this.types = types;
        }
    }
    private static final class Plan {
        final String command, destination;
        final String[] variables, functions;
        final TalkWasm.Code code;
        Plan(String command, String destination, Parser parser, TalkWasm.Code code) {
            this.command = command; this.destination = destination; this.code = code;
            variables = parser.inputs.keySet().toArray(new String[0]);
            functions = parser.functions.toArray(new String[0]);
        }
    }
    static final class Reject extends RuntimeException {}
    static void need(boolean ok) { if (!ok) throw new Reject(); }

    static Result enter(TTalk talk, OObject object, OObject target, ArrayList<String> script, int line,
            MemoryData memory, ArrayList<String> words, ArrayList<TTalk.wordType> types) throws xTalkException {
        if (TalkWasm.backend == null || memory == null || TTalk.stop || TTalk.abandonedThread() || TTalk.tracemode != 0
                || "false".equals(System.getProperty("hz.talk.wasm")) || object != null && object.checkpoints != null
                || StackSpeed.limited() || TTalk.numFormat != TTalk.DEFAULT_NUMBER_FORMAT_INSTANCE
                || talk.sendPending() || UiHost.get().xcmd68kHasSessions()) return null;
        Cache cache;
        if (object != null && object.scriptList == script) {
            cache = object.wasmStatements;
            if (cache == null || cache.script != script) object.wasmStatements = cache = new Cache(script);
        } else {
            if (memory.wasmStatements == null) memory.wasmStatements = new java.util.IdentityHashMap<>();
            cache = memory.wasmStatements.get(script);
            if (cache == null) {
                if (memory.wasmStatements.size() >= 32) memory.wasmStatements.clear();
                memory.wasmStatements.put(script, cache = new Cache(script));
            }
        }
        Entry entry = cache.lines[line];
        if (entry == null || entry.source != script.get(line) || entry.words != words || entry.types != types) {
            cache.lines[line] = entry = new Entry(script.get(line), words, types);
        }
        if (!entry.attempted && ++entry.count >= THRESHOLD) {
            entry.attempted = true;
            entry.plan = plan(words, types);
            if (entry.plan == null) rejected++; else compiled++;
        }
        Plan plan = entry.plan;
        if (plan == null) return null;
        if (!plan.command.equals("return") && TTalk.commandHandlerKind(plan.command, object) != 0) return null;
        // Arithmetic commands can themselves send a rewritten put to a user handler.
        if (!plan.command.equals("put") && !plan.command.equals("get") && !plan.command.equals("return")
                && TTalk.commandHandlerKind("put", object) != 0) return null;
        if (plan.functions.length > 0) {
            for (OObject path = object; path != null; path = path.parent) if (path.scriptList == null) return null;
            OStack stack = HZ.stack();
            for (OObject path = stack == null ? null : stack.curCard; path != null; path = path.parent) if (path.scriptList == null) return null;
        }
        for (String function : plan.functions)
            if (TTalk.commandHandlerKind(function, object) != 0 || UiHost.get().tXcmdExternalOwner(function, "function") != null) return null;
        double[] args = new double[plan.variables.length * 2];
        for (int i = 0; i < plan.variables.length; i++) {
            String name = plan.variables[i];
            if (memory.notDeclared != null && memory.notDeclared.contains(name)) return null;
            String text = TTalk.getVariable(memory, name);
            if (text == null) return null; // an unset variable is its name, not numeric zero
            try { args[i*2] = number(text); }
            catch (NumberFormatException e) { return null; }
            if (!Double.isFinite(args[i*2])) return null;
            args[i*2+1] = TTalk.isRealNumber(text) ? 1 : 0;
        }
        double value;
        try { value = plan.code.run(args); }
        catch (RuntimeException e) { fallbacks++; return null; }
        if (!Double.isFinite(value)) { fallbacks++; return null; }
        String raw = plan.code.kind() == 2 ? TTalk.formatRealNumber(value) : TTalk.formatRawNumber(value);
        Result result = new Result();
        if (plan.command.equals("return")) {
            result.ret = 3;
            result.numeric = memory.isFunction;
            result.theResult = memory.isFunction ? raw : TTalk.applyNumberFormat(raw);
        } else {
            TTalk.putNativeNumber(memory, plan.destination, raw);
        }
        executed++;
        return result;
    }

    static double number(String text) { return text.trim().isEmpty() ? 0 : TTalk.parseNumber(text); }
    static boolean variable(String name) {
        if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*") || TTalk.OBJECT_WORDS.contains(name.toLowerCase(Locale.ROOT))) return false;
        try { TTalk.checkVariableName(name); return true; } catch (xTalkException e) { return false; }
    }
    private static Plan plan(ArrayList<String> words, ArrayList<TTalk.wordType> types) {
        if (words.size() > 128 || words.size() < 2) return null;
        try {
            String command = words.get(0).toLowerCase(Locale.ROOT), destination = null;
            int start = 1, end = words.size();
            Parser parser;
            Node expression;
            if (command.equals("put")) {
                need(end >= 4 && words.get(end-2).equals("into") && types.get(end-2) != TTalk.wordType.STRING);
                destination = words.get(end-1).toLowerCase(Locale.ROOT);
                need(types.get(end-1) == TTalk.wordType.VARIABLE && variable(destination));
                end -= 2;
                parser = new Parser(words, types, start, end);
                expression = parser.parse();
            } else if (command.equals("get") || command.equals("return")) {
                destination = command.equals("get") ? "it" : null;
                parser = new Parser(words, types, start, end);
                expression = parser.parse();
            } else {
                String separator, operator;
                switch (command) {
                    case "add": separator = "to"; operator = "+"; break;
                    case "subtract": separator = "from"; operator = "-"; break;
                    case "multiply": separator = "by"; operator = "*"; break;
                    case "divide": separator = "by"; operator = "/"; break;
                    default: return null;
                }
                int join = -1;
                for (int i=1;i<end;i++) if (words.get(i).equals(separator) && types.get(i) != TTalk.wordType.STRING) join = i;
                need(join > 1);
                int dest = command.equals("add") || command.equals("subtract") ? end-1 : 1;
                need(dest == 1 ? join == 2 : join == end-2);
                destination = words.get(dest).toLowerCase(Locale.ROOT);
                need(types.get(dest) == TTalk.wordType.VARIABLE && variable(destination));
                parser = new Parser(words, types, dest == 1 ? join+1 : 1, dest == 1 ? end : join);
                Node operand = parser.parse(), previous = parser.input(destination);
                expression = new Operation(operator, previous, operand);
            }
            // A bare literal/copy retains its exact spelling and raw-number status in the interpreter.
            need(expression.computed());
            byte[] module = new Emitter(parser.inputs.size()*2).module(expression);
            TalkWasm.Code code = TalkWasm.backend.compile(module);
            return code == null ? null : new Plan(command, destination, parser, code);
        } catch (Reject | NumberFormatException e) { return null; }
        catch (RuntimeException e) { return null; } // unavailable compilation, including browser CSP
    }

    interface Node {
        Value emit(Emitter e);
        default boolean computed() { return true; }
    }
    static final class Value {
        final int number, real, format;
        Value(int number, int real, int format) { this.number = number; this.real = real; this.format = format; }
    }
    static final class Constant implements Node {
        final double number;
        final boolean real;
        /** The script's own token (a quoted or unquoted literal), null for a constant word such as pi */
        final String text;
        Constant(String text) { this(text, true); }
        Constant(String text, boolean literal) { number = number(text); real = TTalk.isRealNumber(text); need(Double.isFinite(number)); this.text = literal ? text : null; }
        public boolean computed() { return false; }
        public Value emit(Emitter e) {
            Value v = e.local(); e.b.number(number).set(v.number).number(real ? 1 : 0).set(v.real); return v;
        }
    }
    static final class Input implements Node {
        Value value;
        Input(int slot) { value = new Value(slot*2, slot*2+1, slot*2+1); }
        public boolean computed() { return false; }
        public Value emit(Emitter e) { return value; }
    }
    static final class Operation implements Node {
        final String operator;
        final Node left, right;
        Operation(String operator, Node left, Node right) { this.operator=operator; this.left=left; this.right=right; }
        public Value emit(Emitter e) {
            Value a=left.emit(e), c=right == null ? null : right.emit(e), out=e.local();
            TalkWasm.Bytes b=e.b;
            if(operator.equals("mod")) {
                // Only HyperCard's integer mod (truncated remainder): a real operand or a zero divisor goes to the interpreter.
                // Integers within 32 bits divide exactly enough here for trunc(a/c) to be the true quotient.
                b.get(a.real).number(0).op(0x62).get(c.real).number(0).op(0x62).op(0x72).get(c.number).number(0).op(0x61).op(0x72);
                e.bail();
                b.get(a.number).get(a.number).get(c.number).op(0xa3).op(0x9d).get(c.number).op(0xa2).op(0xa1);
                b.set(out.number);
                // Java's % keeps the dividend's sign on a zero remainder (-450 mod -3 = -0): the interpreter's text "-0"
                b.get(out.number).number(0).op(0x61).number(1).get(a.number).op(0xa6).number(0).op(0x63).op(0x71); e.bail();
                e.guard(out);
                b.number(0).number(1).op(0x61);
                e.kind(out);
                return out;
            }
            b.get(a.number);
            if(c!=null) b.get(c.number);
            switch(operator) {
                case "+": b.op(0xa0); break; case "-": b.op(0xa1); break;
                case "*": b.op(0xa2); break; case "/": b.op(0xa3); break;
                case "div": b.op(0xa3).op(0x9d); break;
                case "^": b.op(0x10).u(0); break; // Math.pow import
                case "neg": b.op(0x9a); break;
                default: throw new Reject();
            }
            b.set(out.number);
            if("+-*/".contains(operator)) e.finite(out); else e.guard(out);
            if(operator.equals("/") || operator.equals("^")) b.number(1).number(1).op(0x61);
            else if(operator.equals("div")) b.number(0).number(1).op(0x61);
            else {
                b.get(a.real).number(0).op(0x62);
                if(c!=null) b.get(c.real).number(0).op(0x62).op(0x72);
            }
            e.kind(out);
            return out;
        }
    }
    static final class Function implements Node {
        final String name;
        final ArrayList<Node> arguments;
        Function(String name, ArrayList<Node> arguments) { this.name=name; this.arguments=arguments; }
        public Value emit(Emitter e) {
            ArrayList<Value> args=new ArrayList<>();
            for(Node n:arguments) args.add(n.emit(e));
            TalkWasm.Bytes b=e.b;
            Value out=e.local();
            if(name.equals("abs")) { b.get(args.get(0).number).number(-2147483648d).op(0x61); e.bail(); }
            b.get(args.get(0).number);
            switch(name) {
                case "abs": b.op(0x99); break; case "sqrt": b.op(0x9f); break;
                case "trunc": b.op(0x9d); break; case "round": b.op(0x9e); break; // ties to even, as HzMath.rint
                case "min": case "max": case "sum": case "average":
                    for(int i=1;i<args.size();i++) b.get(args.get(i).number).op(name.equals("min") ? 0xa4 : name.equals("max") ? 0xa5 : 0xa0);
                    if(name.equals("average")) b.number(args.size()).op(0xa3);
                    break;
                default: throw new Reject();
            }
            if(name.equals("round") || name.equals("trunc")) b.number(-2147483648d).op(0xa5).number(2147483647d).op(0xa4);
            b.set(out.number); e.guard(out);
            b.number(name.equals("sqrt") || name.equals("min") || name.equals("max") || name.equals("sum") || name.equals("average") ? 1 : 0).number(1).op(0x61);
            e.kind(out);
            return out;
        }
    }
    /** HyperCard's random: 1 + floor(Math.random * n) for an integer n >= 1, else 1 (TUtil). A real argument is read by the
     *  interpreter from its formatted text: that is left to the interpreter. */
    static final class Random implements Node {
        final Node argument;
        Random(Node argument) { this.argument = argument; }
        public Value emit(Emitter e) {
            Value a = argument.emit(e), out = e.local();
            TalkWasm.Bytes b = e.b;
            b.get(a.real).number(0).op(0x62); e.bail();
            b.number(1);
            b.op(0x10).u(1).get(a.number).op(0xa2).op(0x9c).number(1).op(0xa0);
            b.get(a.number).number(1).op(0x63).op(0x1b);
            b.set(out.number); e.guard(out);
            b.number(0).number(1).op(0x61);
            e.kind(out);
            return out;
        }
    }
    static final class Parser {
        final ArrayList<String> words;
        final ArrayList<TTalk.wordType> types;
        final LinkedHashMap<String,Input> inputs=new LinkedHashMap<>();
        final java.util.HashSet<String> functions=new java.util.HashSet<>();
        final int end;
        int at, depth;
        /** random(n) / random of n: only in loop modules, which import math.random as function 1 (TalkWasmLoops) */
        boolean random;
        /** Loop modules: an atom of their own (item n of p, px[y][x]), or null to read it as usual; it moves at past it */
        java.util.function.Function<Parser, Node> extra;
        Parser(ArrayList<String> words, ArrayList<TTalk.wordType> types, int start, int end) { this.words=words; this.types=types; at=start; this.end=end; }
        boolean is(String text) { return at<end && types.get(at)!=TTalk.wordType.STRING && words.get(at).equals(text); }
        Input input(String name) { return inputs.computeIfAbsent(name, n -> new Input(inputs.size())); }
        Node parse() { Node n=sum(); need(at==end); return n; }
        Node sum() {
            Node n=product();
            while(is("+") || is("-")) { String op=words.get(at++); n=new Operation(op,n,product()); }
            return n;
        }
        Node product() {
            Node n=power();
            while(is("*") || is("/") || is("div") || is("mod")) { String op=words.get(at++); n=new Operation(op,n,power()); }
            return n;
        }
        Node power() {
            need(++depth<=24);
            Node n=atom();
            if(is("^")) { at++; n=new Operation("^",n,power()); }
            depth--; return n;
        }
        Node atom() {
            need(++depth<=24 && at<end);
            Node n;
            if(is("(")) { at++; n=sum(); need(is(")")); at++; }
            else if(is("-")) {
                at++; // Consecutive unary operators are not reduced recursively by TTalk's left-to-right pass.
                need(!is("-") && !is("+")); n=new Operation("neg",atom(),null);
            } else if(extra!=null && (n=extra.apply(this))!=null) {
                // the loop compiler's own atom
            } else {
                String text=words.get(at), lower=text.toLowerCase(Locale.ROOT);
                TTalk.wordType type=types.get(at++);
                if(type==TTalk.wordType.STRING) n=new Constant(text);
                else if(random && lower.equals("random") && type==TTalk.wordType.FUNC && (is("(") || is("of"))) {
                    Node arg;
                    if(is("(")) { at++; arg=sum(); need(is(")")); at++; }
                    else { at++; need(!is("-") && !is("+")); arg=atom(); need(!is("^")); }
                    functions.add("random"); n=new Random(arg);
                }
                else if(at<end && is("(") && FUNCTIONS.contains(lower) && (type==TTalk.wordType.FUNC || type==TTalk.wordType.USER_FUNC || type==TTalk.wordType.XFCN)) {
                    at++; ArrayList<Node> args=new ArrayList<>();
                    need(!is(")"));
                    do { args.add(sum()); if(!is(",")) break; at++; } while(true);
                    need(is(")")); at++;
                    need(args.size()==1 || lower.equals("min") || lower.equals("max") || lower.equals("sum") || lower.equals("average"));
                    functions.add(lower); n=new Function(lower,args);
                } else if(type==TTalk.wordType.CONST) {
                    String value;
                    switch(lower) {
                        case "empty": value="0"; break; case "pi": value="3.14159265358979323846"; break;
                        case "zero": value="0"; break; case "one": value="1"; break; case "two": value="2"; break;
                        case "three": value="3"; break; case "four": value="4"; break; case "five": value="5"; break;
                        case "six": value="6"; break; case "seven": value="7"; break; case "eight": value="8"; break;
                        case "nine": value="9"; break; case "ten": value="10"; break;
                        default: throw new Reject();
                    }
                    n=new Constant(value, false);
                } else { need(type==TTalk.wordType.VARIABLE && variable(lower)); n=input(lower); }
            }
            depth--; return n;
        }
    }
    static final class Emitter {
        final TalkWasm.Bytes b=new TalkWasm.Bytes();
        final int parameters;
        int slots;
        Emitter(int parameters) { this.parameters=parameters; slots=parameters; }
        Value local() { int number=slots++, real=slots++, format=slots++; return new Value(number,real,format); }
        void bail() { b.op(0x04).op(0x40).number(Double.NaN).op(0x0f).op(0x0b); }
        void guard(Value v) {
            finite(v);
            b.get(v.number).number(0).op(0x61).number(1).get(v.number).op(0xa3).number(0).op(0x63).op(0x71); bail();
        }
        /** + - * / are IEEE doubles in the interpreter as well, and -0 reads back from its raw text "-0" as -0: only an
         *  infinity or NaN needs the interpreter (2*a*b with a<0, b=0 in a Mandelbrot loop) */
        void finite(Value v) { b.get(v.number).op(0x99).number(Double.MAX_VALUE).op(0x65).op(0x45); bail(); }
        /** An i32 requested-real flag is on the stack. Raw values outside signed i32 or with a fraction read back as real. */
        void kind(Value v) {
            b.op(0xb8).op(0x22).u(v.format).number(0).op(0x62);
            b.get(v.number).number(-2147483648d).op(0x63).op(0x72);
            b.get(v.number).number(2147483647d).op(0x64).op(0x72);
            b.get(v.number).op(0x9d).get(v.number).op(0x62).op(0x72);
            // -0 is written "-0" even as a real (formatRealNumber), and that text reads back as an integer
            b.get(v.number).number(0).op(0x61).number(1).get(v.number).op(0xa6).number(0).op(0x63).op(0x71).op(0x45).op(0x71);
            b.op(0xb8).set(v.real);
        }
        byte[] module(Node expression) {
            Value result=expression.emit(this);
            b.get(result.format).number(1).op(0xa0).op(0xaa).op(0x24).u(0).get(result.number).op(0x0b);
            TalkWasm.Bytes module=new TalkWasm.Bytes().data(new byte[]{0,97,115,109,1,0,0,0});
            TalkWasm.Bytes types=new TalkWasm.Bytes().u(2).op(0x60).u(parameters);
            for(int i=0;i<parameters;i++) types.op(0x7c);
            types.u(1).op(0x7c).op(0x60).u(2).op(0x7c).op(0x7c).u(1).op(0x7c);
            module.section(1,types);
            module.section(2,new TalkWasm.Bytes().u(1).text("math").text("pow").op(0).u(1));
            module.section(3,new TalkWasm.Bytes().u(1).u(0));
            module.section(6,new TalkWasm.Bytes().u(1).op(0x7f).op(1).op(0x41).u(0).op(0x0b));
            module.section(7,new TalkWasm.Bytes().u(2).text("run").op(0).u(1).text("numeric").op(3).u(0));
            TalkWasm.Bytes code=new TalkWasm.Bytes().u(1).u(slots-parameters).op(0x7c).data(b.toByteArray());
            module.section(10,new TalkWasm.Bytes().u(1).u(code.size()).data(code.toByteArray()));
            need(module.size()<=65536);
            return module.toByteArray();
        }
    }
}
