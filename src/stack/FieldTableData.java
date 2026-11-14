import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemColor;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventObject;
import java.util.List;

/** The field's text as rows of tab-delimited cells (no Swing: also used by the HyperTalk properties and the tests). */
final class FieldTableData {
	private FieldTableData(){}

	/** The lines of the text (a final return does not start another row); each split at the tabs. */
	static List<String[]> parseRows(String text){
		List<String[]> rows = new ArrayList<String[]>();
		if(text==null || text.isEmpty()) return rows;
		String[] lines = lines(text);
		for(String line : lines) rows.add(line.split("\t", -1));
		return rows;
	}

	static String[] lines(String text){
		String t = text.replace("\r\n", "\n").replace('\r', '\n');
		if(t.endsWith("\n")) t = t.substring(0, t.length()-1);
		return t.split("\n", -1);
	}

	static int columnCount(List<String[]> rows){
		int n = 0;
		for(String[] r : rows) n = Math.max(n, r.length);
		return n;
	}

	/** "120,80,200" → {120,80,200}; an empty or non-number item leaves that column to share the rest (0). */
	static int[] parseWidths(String spec){
		if(spec==null || spec.trim().isEmpty()) return new int[0];
		String[] items = spec.split(",", -1);
		int[] widths = new int[items.length];
		for(int i=0; i<items.length; i++){
			try{ widths[i] = Math.max(0, (int)Math.round(Double.parseDouble(items[i].trim()))); }
			catch(NumberFormatException e){ widths[i] = 0; }
		}
		return widths;
	}

	/** Checks a columnWidths value: numbers (or empty items) separated by commas. */
	static String normalizeWidths(String spec) throws xTalkException {
		if(spec==null || spec.trim().isEmpty()) return "";
		String[] items = spec.split(",", -1);
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<items.length; i++){
			String item = items[i].trim();
			if(i>0) sb.append(',');
			if(item.isEmpty()) continue;
			try{
				double w = Double.parseDouble(item);
				if(w<0) throw new NumberFormatException();
				sb.append((int)Math.round(w));
			}catch(NumberFormatException e){
				throw new xTalkException("columnWidths requires comma-separated widths such as \"120,80,200\"");
			}
		}
		return sb.toString();
	}

	/** Column names separated by tabs, or else by commas (a list typed in a script); a return also separates them. */
	static String[] parseNames(String spec){
		if(spec==null || spec.isEmpty()) return new String[0];
		String s = spec.replace("\r\n", "\n").replace('\r', '\n');
		String sep = s.indexOf('\t')>=0 ? "\t" : s.indexOf('\n')>=0 ? "\n" : ",";
		if(s.endsWith(sep)) s = s.substring(0, s.length()-1);
		return s.split(sep, -1);
	}

	/** Puts a value into item column of line row (1-based), adding lines and tabs as needed; tabs and returns in it become spaces. */
	static String setCell(String text, int row, int column, String value){
		if(text==null) text = "";
		String v = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		List<String> lines = new ArrayList<String>(text.isEmpty() ? new ArrayList<String>() : Arrays.asList(lines(text)));
		while(lines.size()<row) lines.add("");
		List<String> cells = new ArrayList<String>(Arrays.asList(lines.get(row-1).split("\t", -1)));
		while(cells.size()<column) cells.add("");
		cells.set(column-1, v);
		lines.set(row-1, String.join("\t", cells));
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}

	/**
	 * The text with an empty cell put in at a column (1-based) of every row that reaches it (a shorter row shows the column
	 * empty as it is; when none does, the first row is made to). Text without rows gets one empty row of the columns, the new
	 * one too, so that the column shows.
	 */
	static String insertColumn(String text, int column, int columns){
		if(text==null || text.isEmpty()) return "\t".repeat(Math.max(columns, column-1));
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		String[] lines = lines(text);
		boolean reached = false;
		for(int i=0; i<lines.length; i++){
			List<String> cells = new ArrayList<String>(Arrays.asList(lines[i].split("\t", -1)));
			if(cells.size()<column) continue;
			cells.add(column-1, "");
			lines[i] = String.join("\t", cells);
			reached = true;
		}
		//a column after the last cells (at the end): the first row reaches it, so that the table has it
		if(!reached) lines[0] = lines[0]+"\t".repeat(column-lines[0].split("\t", -1).length);
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}

	/** The text without one column (1-based): that cell taken out of every row that has it. */
	static String deleteColumn(String text, int column){
		if(text==null || text.isEmpty()) return "";
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		String[] lines = lines(text);
		for(int i=0; i<lines.length; i++){
			List<String> cells = new ArrayList<String>(Arrays.asList(lines[i].split("\t", -1)));
			if(cells.size()<column) continue;
			cells.remove(column-1);
			lines[i] = String.join("\t", cells);
		}
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}

	/** columnNames with an empty name put in at a column (1-based), in the same separators; no names stay no names. */
	static String insertName(String spec, int column){ return editList(spec, nameSeparator(spec), column, true); }
	static String deleteName(String spec, int column){ return editList(spec, nameSeparator(spec), column, false); }
	/** columnWidths with an empty width (a share of the rest) put in at a column, or a column's width taken out. */
	static String insertWidth(String spec, int column){ return editList(spec, ",", column, true); }
	static String deleteWidth(String spec, int column){ return editList(spec, ",", column, false); }

	private static String nameSeparator(String spec){
		String s = spec==null ? "" : spec.replace("\r\n", "\n").replace('\r', '\n');
		return s.indexOf('\t')>=0 ? "\t" : s.indexOf('\n')>=0 ? "\n" : ",";
	}

	private static String editList(String spec, String sep, int column, boolean insert){
		if(spec==null || spec.isEmpty()) return "";
		String s = spec.replace("\r\n", "\n").replace('\r', '\n');
		boolean finalSep = s.endsWith(sep);
		if(finalSep) s = s.substring(0, s.length()-1);
		List<String> items = new ArrayList<String>(Arrays.asList(s.split(java.util.regex.Pattern.quote(sep), -1)));
		if(items.size()<column) return spec;
		if(insert) items.add(column-1, "");
		else items.remove(column-1);
		//nothing left (the only name or width taken out): no names, no widths
		if(items.stream().allMatch(String::isEmpty)) return "";
		return String.join(sep, items)+(finalSep ? sep : "");
	}

	/**
	 * The new order of the rows (indexes into rows) sorted by one column: numerically when every non-empty cell of it is a
	 * number, else as text ignoring case; empty cells go last; rows that compare equal keep their order.
	 */
	static int[] sortOrder(List<String[]> rows, int column, boolean ascending){
		boolean numeric = true, any = false;
		for(String[] r : rows){
			String c = cell(r, column).trim();
			if(c.isEmpty()) continue;
			any = true;
			if(number(c)==null){ numeric = false; break; }
		}
		final boolean byNumber = numeric && any;
		java.text.Collator collator = java.text.Collator.getInstance();
		collator.setStrength(java.text.Collator.SECONDARY);
		Integer[] order = new Integer[rows.size()];
		for(int i=0; i<order.length; i++) order[i] = i;
		Arrays.sort(order, (a, b) -> {
			String x = cell(rows.get(a), column).trim(), y = cell(rows.get(b), column).trim();
			if(x.isEmpty() || y.isEmpty()) return x.isEmpty()==y.isEmpty() ? 0 : x.isEmpty() ? 1 : -1;
			int c = byNumber ? Double.compare(number(x), number(y)) : collator.compare(x, y);
			return ascending ? c : -c;
		});
		int[] result = new int[order.length];
		for(int i=0; i<order.length; i++) result[i] = order[i];
		return result;
	}

	/** The text with its lines in a new order (from sortOrder). */
	static String reorder(String text, int[] order){
		if(text==null || text.isEmpty()) return "";
		String[] lines = lines(text);
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<order.length; i++){
			if(i>0) sb.append('\n');
			sb.append(lines[order[i]]);
		}
		return sb.toString()+(finalReturn ? "\n" : "");
	}

	private static String cell(String[] row, int column){
		return column<row.length ? row[column] : "";
	}

	private static Double number(String s){
		try{ return Double.valueOf(s); }
		catch(NumberFormatException e){ return null; }
	}
}
