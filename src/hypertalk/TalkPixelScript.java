import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The script of imageFilter and the screenFilter: HyperTalk run once for every pixel, on numbers only, so that any
 * filter can be written in a stack (grayscale, blur, a wave, a mosaic...) and still run at the speed of a built-in one.
 *
 * <p>It starts with r, g, b, a = the pixel (0-255), x, y = where it is (from 0 at the top left), w, h = the size of the
 * image, width, height = the size of the result (the same unless imageFilter is given one), scale = the screen's
 * magnification (1 for images). It may read any pixel of the image: red(x, y), green(x, y), blue(x, y), alpha(x, y),
 * lum(x, y) (edges repeat, between pixels is blended), and take one whole: get pixel x, y (outside the image: clear).
 * What r, g, b, a hold at the end is the new pixel.
 *
 * <p>Statements: put … into, add … to, subtract … from, multiply … by, divide … by, get pixel, if … then [… else …]
 * on one line or as a block to end if, repeat with v = a [down] to b / repeat n [times] / repeat while|until c / repeat,
 * to end repeat, exit repeat, next repeat, -- comments. Lines may also be separated by ";". Operators and functions
 * as in HyperTalk: + - * / div mod ^, = is <> is not < > <= >=, and or not, abs min max sqrt sin cos tan atan exp ln
 * log2 round trunc random, pi, true, false. Other variables start at 0 for every pixel.
 */
final class TalkPixelScript {
	static final int X = 0, Y = 1, W = 2, H = 3, WIDTH = 4, HEIGHT = 5, R = 6, G = 7, B = 8, A = 9, SCALE = 10, FIRST = 11;
	private static final String[] BUILT_IN = {"x", "y", "w", "h", "width", "height", "r", "g", "b", "a", "scale"};
	/** random(k) (not ThreadLocalRandom: TeaVM's cannot be made, its Random constructor calls the setSeed it refuses) */
	private static final java.util.Random RANDOM = new java.util.Random();
	/** Steps of loops one pixel may take: a loop that does not end stops with an error, the card window is not frozen */
	static final long STEPS_PER_PIXEL = 200_000;

	final String source;
	private final Stmt body;
	private final int slots;

	private TalkPixelScript(String source, Stmt body, int slots) { this.source = source; this.body = body; this.slots = slots; }

	// ---- running ----

	private static final class Ctx {
		final double[] v;
		final int[] src;
		final int sw, sh;
		long budget;
		Ctx(int slots, int[] src, int sw, int sh) { v = new double[slots]; this.src = src; this.sw = sw; this.sh = sh; }
	}

	private static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final int x, y;
		Stop(int x, int y) { super(null, null, false, false); this.x = x; this.y = y; }
	}

	/** The script over src, giving an image of outW x outH (src's own size for a filter) */
	BufferedImage run(BufferedImage src, int outW, int outH, double scale) throws xTalkException {
		int sw = src.getWidth(), sh = src.getHeight();
		int[] in = ((DataBufferInt)src.getRaster().getDataBuffer()).getData();
		BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
		int[] o = ((DataBufferInt)out.getRaster().getDataBuffer()).getData();
		boolean script = !java.awt.EventQueue.isDispatchThread();//a script's call: Command-period stops it (TTalk.stop)
		try {
			IntStream.range(0, outH).parallel().forEach(row -> {
				if(script && TTalk.stop) throw new Stop(-1, row);
				Ctx c = new Ctx(slots, in, sw, sh);
				double[] v = c.v;
				for(int x=0; x<outW; x++) {
					java.util.Arrays.fill(v, FIRST, v.length, 0);
					v[X] = x; v[Y] = row; v[W] = sw; v[H] = sh; v[WIDTH] = outW; v[HEIGHT] = outH; v[SCALE] = scale;
					if(x < sw && row < sh) {
						int p = in[row*sw + x];
						v[R] = p>>16&255; v[G] = p>>8&255; v[B] = p&255; v[A] = p>>>24;
					}else { v[R] = v[G] = v[B] = v[A] = 0; }
					c.budget = STEPS_PER_PIXEL;
					try { body.run(c); }
					catch(Stop s){ throw new Stop(x, row); }
					o[row*outW + x] = c255(v[A])<<24 | c255(v[R])<<16 | c255(v[G])<<8 | c255(v[B]);
				}
			});
		}catch(RuntimeException e){
			Throwable t = e;
			while(t != null && !(t instanceof Stop)) t = t.getCause();
			if(t == null) throw e;
			Stop s = (Stop)t;
			if(s.x < 0) throw new xTalkException("The image script was interrupted");
			throw new xTalkException("The image script runs too long at the pixel {0},{1}", s.x, s.y);
		}
		return out;
	}

	private static int c255(double v) { return v > 0 ? v < 255 ? (int)(v + 0.5) : 255 : 0; }//NaN is 0

	/** A channel of the image at fx, fy: edges repeat, between pixels blended. shift 16 red, 8 green, 0 blue, 24 alpha */
	private static double sample(Ctx c, double fx, double fy, int shift) {
		int sw = c.sw, sh = c.sh;
		if(fx != fx || fy != fy) return 0;
		int ix = (int)Math.floor(fx), iy = (int)Math.floor(fy);
		double tx = fx - ix, ty = fy - iy;
		if(tx == 0 && ty == 0) return c.src[clamp(iy, sh)*sw + clamp(ix, sw)] >>> shift & 255;
		int x0 = clamp(ix, sw), x1 = clamp(ix+1, sw), y0 = clamp(iy, sh), y1 = clamp(iy+1, sh);
		double top = (c.src[y0*sw + x0] >>> shift & 255) * (1-tx) + (c.src[y0*sw + x1] >>> shift & 255) * tx;
		double bottom = (c.src[y1*sw + x0] >>> shift & 255) * (1-tx) + (c.src[y1*sw + x1] >>> shift & 255) * tx;
		return top * (1-ty) + bottom * ty;
	}

	private static int clamp(int i, int n) { return i < 0 ? 0 : i >= n ? n-1 : i; }

	/** get pixel fx, fy: the four channels, clear outside the image */
	private static void take(Ctx c, double fx, double fy) {
		double[] v = c.v;
		if(!(fx > -1 && fy > -1 && fx < c.sw && fy < c.sh)) { v[R] = v[G] = v[B] = v[A] = 0; return; }
		v[R] = sample(c, fx, fy, 16); v[G] = sample(c, fx, fy, 8); v[B] = sample(c, fx, fy, 0); v[A] = sample(c, fx, fy, 24);
		if(fx < 0 || fy < 0 || fx > c.sw-1 || fy > c.sh-1) {//the edge fades out
			double k = Math.min(1, Math.min(fx+1, c.sw-fx)) * Math.min(1, Math.min(fy+1, c.sh-fy));
			v[A] *= k;
		}
	}

	// ---- the compiled tree ----

	private interface Num { double v(Ctx c); }
	private interface Stmt { int run(Ctx c); }//0 on, 1 exit repeat, 2 next repeat
	private static final int ON = 0, EXIT = 1, NEXT = 2;

	private static Stmt block(ArrayList<Stmt> list) {
		Stmt[] s = list.toArray(new Stmt[0]);
		if(s.length == 1) return s[0];
		return c -> {
			for(Stmt t : s) { int r = t.run(c); if(r != ON) return r; }
			return ON;
		};
	}

	// ---- compiling ----

	private static final LinkedHashMap<String,TalkPixelScript> compiled = new LinkedHashMap<>(32, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String,TalkPixelScript> e) { return size() > 32; }
	};

	static synchronized TalkPixelScript compile(String source) throws xTalkException {
		TalkPixelScript hit = compiled.get(source);
		if(hit != null) return hit;
		Compiler k = new Compiler(source);
		Stmt body = k.lines(0, null);
		if(k.at < k.lines.size()) throw k.wrong(k.at);
		TalkPixelScript script = new TalkPixelScript(source, body, k.names.size());
		compiled.put(source, script);
		return script;
	}

	private static final class Line {
		final int number;
		final String text;
		final String[] t;
		Line(int number, String text, String[] t) { this.number = number; this.text = text; this.t = t; }
	}

	private static final class Compiler {
		final ArrayList<Line> lines = new ArrayList<>();
		final HashMap<String,Integer> names = new HashMap<>();
		int at;
		//within the line being read
		String[] t;
		int p;
		Line line;

		Compiler(String source) throws xTalkException {
			for(String b : BUILT_IN) names.put(b, names.size());
			String[] physical = source.split("\r\n|\r|\n", -1);
			for(int n=0; n<physical.length; n++) {
				String text = physical[n];
				int comment = text.indexOf("--");
				if(comment >= 0) text = text.substring(0, comment);
				for(String part : text.split(";")) {
					String[] tokens = tokens(part, n+1);
					if(tokens.length > 0) lines.add(new Line(n+1, part.trim(), tokens));
				}
			}
		}

		xTalkException wrong(int index) {
			Line l = index < lines.size() ? lines.get(index) : lines.isEmpty() ? new Line(1, "", new String[0]) : lines.get(lines.size()-1);
			return new xTalkException("Can't understand the image script at line {0}: {1}", l.number, l.text);
		}
		xTalkException wrong() { return new xTalkException("Can't understand the image script at line {0}: {1}", line.number, line.text); }

		private static String[] tokens(String s, int number) throws xTalkException {
			ArrayList<String> out = new ArrayList<>();
			int i = 0, n = s.length();
			while(i < n) {
				char c = s.charAt(i);
				if(Character.isWhitespace(c) || c == '¬') { i++; continue; }
				if(Character.isDigit(c) || c == '.' && i+1 < n && Character.isDigit(s.charAt(i+1))) {
					int j = i;
					while(j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
					if(j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j+1 < n && (Character.isDigit(s.charAt(j+1)) ||
							(s.charAt(j+1) == '-' || s.charAt(j+1) == '+') && j+2 < n && Character.isDigit(s.charAt(j+2)))) {
						j += 2;
						while(j < n && Character.isDigit(s.charAt(j))) j++;
					}
					out.add(s.substring(i, j)); i = j; continue;
				}
				if(Character.isLetter(c) || c == '_') {
					int j = i;
					while(j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
					out.add(s.substring(i, j).toLowerCase(Locale.ROOT)); i = j; continue;
				}
				if(i+1 < n) {
					String two = s.substring(i, i+2);
					if(two.equals("<=") || two.equals(">=") || two.equals("<>")) { out.add(two); i += 2; continue; }
				}
				if("+-*/^()=<>,≤≥≠".indexOf(c) >= 0) { out.add(String.valueOf(c)); i++; continue; }
				throw new xTalkException("Can't understand the image script at line {0}: {1}", number, s.trim());
			}
			return out.toArray(new String[0]);
		}

		// -- statements --

		/** Lines from {@code at} up to one that starts with one of the words in {@code ends} (left for the caller) */
		Stmt lines(int depth, String[] ends) throws xTalkException {
			ArrayList<Stmt> list = new ArrayList<>();
			while(at < lines.size()) {
				Line l = lines.get(at);
				if(ends != null) for(String e : ends) if(l.t[0].equals(e)) return block(list);
				list.add(statement());
			}
			if(ends != null) throw wrong(lines.size()-1);//no end if / end repeat
			return list.isEmpty() ? c -> ON : block(list);
		}

		private void begin(int index) { line = lines.get(index); t = line.t; p = 0; }
		private boolean is(String w) { return p < t.length && t[p].equals(w); }
		private void expect(String w) throws xTalkException { if(!is(w)) throw wrong(); p++; }
		private void end() throws xTalkException { if(p != t.length) throw wrong(); }
		private String name() throws xTalkException {
			if(p >= t.length || !Character.isLetter(t[p].charAt(0)) || KEYWORDS.contains(t[p])) throw wrong();
			return t[p++];
		}
		private int slot(String n) { return names.computeIfAbsent(n, k -> names.size()); }

		Stmt statement() throws xTalkException {
			begin(at++);
			Stmt s = simple();
			return s;
		}

		/** One statement from p (if and repeat may take the lines after) */
		private Stmt simple() throws xTalkException {
			if(p >= t.length) throw wrong();
			String w = t[p++];
			switch(w) {
				case "put": {
					Num e = expr(); expect("into"); int v = slot(name()); end();
					return c -> { c.v[v] = e.v(c); return ON; };
				}
				case "add": {
					Num e = expr(); expect("to"); int v = slot(name()); end();
					return c -> { c.v[v] += e.v(c); return ON; };
				}
				case "subtract": {
					Num e = expr(); expect("from"); int v = slot(name()); end();
					return c -> { c.v[v] -= e.v(c); return ON; };
				}
				case "multiply": case "divide": {
					int v = slot(name()); expect("by"); Num e = expr(); end();
					if(w.equals("multiply")) return c -> { c.v[v] *= e.v(c); return ON; };
					return c -> { c.v[v] /= e.v(c); return ON; };
				}
				case "get": {
					expect("pixel"); Num fx = expr(); expect(","); Num fy = expr(); end();
					return c -> { take(c, fx.v(c), fy.v(c)); return ON; };
				}
				case "exit": expect("repeat"); end(); return c -> EXIT;
				case "next": expect("repeat"); end(); return c -> NEXT;
				case "if": return ifStatement();
				case "repeat": return repeat();
				default: p--; throw wrong();
			}
		}

		private Stmt ifStatement() throws xTalkException {
			Num cond = expr();
			expect("then");
			Stmt yes, no = null;
			if(p < t.length) {
				//if c then s [else s] on one line, or else on the next
				int elseAt = indexOf("else");
				String[] whole = t;
				if(elseAt >= 0) t = java.util.Arrays.copyOfRange(whole, 0, elseAt);
				yes = simple();
				if(elseAt >= 0) { t = whole; p = elseAt + 1; no = simple(); }
				else if(at < lines.size() && lines.get(at).t[0].equals("else")) no = elseLine();
			}else {
				yes = lines(1, new String[]{"else", "end"});
				if(lines.get(at).t[0].equals("else")) no = elseLine();
				else endOf("if");
			}
			Stmt y = yes, n = no;
			if(n == null) return c -> cond.v(c) != 0 ? y.run(c) : ON;
			return c -> cond.v(c) != 0 ? y.run(c) : n.run(c);
		}

		/** The else line at {@code at}: else s, else if …, or else then lines to end if */
		private Stmt elseLine() throws xTalkException {
			begin(at++);
			p = 1;
			if(p < t.length) return simple();//else s, else if c then …
			Stmt s = lines(1, new String[]{"end"});
			endOf("if");
			return s;
		}

		private void endOf(String what) throws xTalkException {
			begin(at++);
			p = 1;
			expect(what);
			end();
		}

		private int indexOf(String w) {
			for(int i=p; i<t.length; i++) if(t[i].equals(w)) return i;
			return -1;
		}

		private Stmt repeat() throws xTalkException {
			Stmt body;
			if(is("with")) {
				p++;
				int v = slot(name());
				expect("=");
				Num from = expr();
				boolean down = is("down");
				if(down) p++;
				expect("to");
				Num to = expr();
				end();
				body = loopBody();
				Stmt b = body;
				return c -> {
					double last = to.v(c);
					for(double i = from.v(c); down ? i >= last : i <= last; i += down ? -1 : 1) {
						c.v[v] = i;
						if(--c.budget < 0) throw new Stop(0, 0);
						int r = b.run(c);
						if(r == EXIT) break;
						i = c.v[v];//the loop variable may be changed in the loop
					}
					return ON;
				};
			}
			if(is("while") || is("until")) {
				boolean until = is("until");
				p++;
				Num cond = expr();
				end();
				Stmt b = loopBody();
				return c -> {
					while((cond.v(c) != 0) != until) {
						if(--c.budget < 0) throw new Stop(0, 0);
						if(b.run(c) == EXIT) break;
					}
					return ON;
				};
			}
			if(p == t.length) {
				Stmt b = loopBody();
				return c -> {
					while(true) {
						if(--c.budget < 0) throw new Stop(0, 0);
						if(b.run(c) == EXIT) break;
					}
					return ON;
				};
			}
			if(is("for")) p++;
			Num count = expr();
			if(is("times")) p++;
			end();
			Stmt b = loopBody();
			return c -> {
				long n = (long)Math.floor(count.v(c));
				for(long i=0; i<n; i++) {
					if(--c.budget < 0) throw new Stop(0, 0);
					if(b.run(c) == EXIT) break;
				}
				return ON;
			};
		}

		private Stmt loopBody() throws xTalkException {
			Stmt b = lines(1, new String[]{"end"});
			endOf("repeat");
			return b;
		}

		// -- expressions (HyperTalk's order: or, and, = <>, < >, + -, * / div mod, ^, - not) --

		private static final java.util.Set<String> KEYWORDS = java.util.Set.of("into", "to", "from", "by", "then", "else", "end",
			"and", "or", "not", "is", "div", "mod", "if", "repeat", "put", "add", "subtract", "multiply", "divide", "get", "pixel",
			"with", "down", "while", "until", "times", "for", "exit", "next");

		Num expr() throws xTalkException { return or(); }

		private Num or() throws xTalkException {
			Num a = and();
			while(is("or")) { p++; Num l = a, r = and(); a = c -> l.v(c) != 0 || r.v(c) != 0 ? 1 : 0; }
			return a;
		}

		private Num and() throws xTalkException {
			Num a = equality();
			while(is("and")) { p++; Num l = a, r = equality(); a = c -> l.v(c) != 0 && r.v(c) != 0 ? 1 : 0; }
			return a;
		}

		private Num equality() throws xTalkException {
			Num a = relation();
			while(true) {
				boolean not;
				if(is("=")) { p++; not = false; }
				else if(is("<>") || is("≠")) { p++; not = true; }
				else if(is("is")) { p++; not = is("not"); if(not) p++; }
				else return a;
				Num l = a, r = relation();
				a = not ? c -> l.v(c) != r.v(c) ? 1 : 0 : c -> l.v(c) == r.v(c) ? 1 : 0;
			}
		}

		private Num relation() throws xTalkException {
			Num a = additive();
			while(true) {
				String op = p < t.length ? t[p] : "";
				if(!(op.equals("<") || op.equals(">") || op.equals("<=") || op.equals(">=") || op.equals("≤") || op.equals("≥"))) return a;
				p++;
				Num l = a, r = additive();
				switch(op) {
					case "<": a = c -> l.v(c) < r.v(c) ? 1 : 0; break;
					case ">": a = c -> l.v(c) > r.v(c) ? 1 : 0; break;
					case "<=": case "≤": a = c -> l.v(c) <= r.v(c) ? 1 : 0; break;
					default: a = c -> l.v(c) >= r.v(c) ? 1 : 0;
				}
			}
		}

		private Num additive() throws xTalkException {
			Num a = multiplicative();
			while(is("+") || is("-")) {
				boolean plus = t[p++].equals("+");
				Num l = a, r = multiplicative();
				a = plus ? c -> l.v(c) + r.v(c) : c -> l.v(c) - r.v(c);
			}
			return a;
		}

		private Num multiplicative() throws xTalkException {
			Num a = power();
			while(is("*") || is("/") || is("div") || is("mod")) {
				String op = t[p++];
				Num l = a, r = power();
				switch(op) {
					case "*": a = c -> l.v(c) * r.v(c); break;
					case "/": a = c -> l.v(c) / r.v(c); break;
					case "div": a = c -> { double d = r.v(c); return d == 0 ? 0 : (double)(long)(l.v(c) / d); }; break;
					default: a = c -> { double d = r.v(c); return d == 0 ? 0 : l.v(c) % d; };
				}
			}
			return a;
		}

		private Num power() throws xTalkException {
			Num a = unary();
			while(is("^")) { p++; Num l = a, r = unary(); a = c -> Math.pow(l.v(c), r.v(c)); }
			return a;
		}

		private Num unary() throws xTalkException {
			if(is("-")) { p++; Num e = unary(); return c -> -e.v(c); }
			if(is("not")) { p++; Num e = unary(); return c -> e.v(c) == 0 ? 1 : 0; }
			return atom();
		}

		private Num atom() throws xTalkException {
			if(p >= t.length) throw wrong();
			String w = t[p++];
			if(w.equals("(")) { Num e = expr(); expect(")"); return e; }
			char first = w.charAt(0);
			if(Character.isDigit(first) || first == '.') {
				double d;
				try { d = Double.parseDouble(w); }catch(NumberFormatException e){ throw wrong(); }
				return c -> d;
			}
			if(!Character.isLetter(first) && first != '_') { p--; throw wrong(); }
			if(is("(")) return call(w);
			switch(w) {
				case "pi": return c -> Math.PI;
				case "true": return c -> 1;
				case "false": case "empty": case "zero": return c -> 0;
				case "one": return c -> 1;
				default:
			}
			if(KEYWORDS.contains(w)) { p--; throw wrong(); }
			int v = slot(w);
			return c -> c.v[v];
		}

		private Num call(String f) throws xTalkException {
			expect("(");
			ArrayList<Num> args = new ArrayList<>();
			if(!is(")")) {
				args.add(expr());
				while(is(",")) { p++; args.add(expr()); }
			}
			expect(")");
			Num[] a = args.toArray(new Num[0]);
			int n = a.length;
			switch(f) {
				case "red": case "green": case "blue": case "alpha": {
					if(n != 2) throw wrong();
					int shift = f.equals("red") ? 16 : f.equals("green") ? 8 : f.equals("blue") ? 0 : 24;
					Num ax = a[0], ay = a[1];
					return c -> sample(c, ax.v(c), ay.v(c), shift);
				}
				case "lum": {
					if(n != 2) throw wrong();
					Num ax = a[0], ay = a[1];
					return c -> { double fx = ax.v(c), fy = ay.v(c); return sample(c, fx, fy, 16)*0.299 + sample(c, fx, fy, 8)*0.587 + sample(c, fx, fy, 0)*0.114; };
				}
				case "min": case "max": {
					if(n == 0) throw wrong();
					boolean min = f.equals("min");
					return c -> {
						double m = a[0].v(c);
						for(int i=1; i<a.length; i++) { double v = a[i].v(c); m = min ? Math.min(m, v) : Math.max(m, v); }
						return m;
					};
				}
				case "random": {
					if(n != 1) throw wrong();
					Num e = a[0];
					return c -> { long k = (long)Math.floor(e.v(c)); return k < 1 ? 0 : 1 + RANDOM.nextLong(k); };
				}
				default:
			}
			if(n != 1) throw wrong();
			Num e = a[0];
			switch(f) {
				case "abs": return c -> Math.abs(e.v(c));
				case "sqrt": return c -> Math.sqrt(e.v(c));
				case "sin": return c -> Math.sin(e.v(c));
				case "cos": return c -> Math.cos(e.v(c));
				case "tan": return c -> Math.tan(e.v(c));
				case "atan": return c -> Math.atan(e.v(c));
				case "exp": return c -> Math.exp(e.v(c));
				case "ln": return c -> Math.log(e.v(c));
				case "log2": return c -> Math.log(e.v(c)) / Math.log(2);
				case "round": return c -> Math.rint(e.v(c));//HyperCard's round: halves to even
				case "trunc": return c -> (double)(long)e.v(c);
				default: throw wrong();
			}
		}
	}
}
