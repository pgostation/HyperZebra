import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** The field's text as an outline (no Swing: also used by the HyperTalk properties and the tests). */
final class FieldOutlineData {
	private FieldOutlineData(){}

	static final class Node {
		/** What the outline shows. */
		final String label;
		/** Stays the same while the text changes around the node (the open nodes are found again by it). */
		final String id;
		/** The node's line in the text (1-based); 0 for JSON. */
		final int line;
		/** The JSON path ("weather.days.2"); empty for indented text. */
		final String path;
		/** The JSON value (Map, List, a HyperTalk text or null); null for indented text. */
		final Object value;
		/** 0 for the top level. */
		final int level;
		final List<Node> children = new ArrayList<Node>();
		Node(String label, String id, int line, String path, Object value, int level){
			this.label = label; this.id = id; this.line = line; this.path = path; this.value = value; this.level = level;
		}
		@Override public String toString(){ return label; }
		/** What the selectedText and the clickText give: the label, or for JSON the value (objects and arrays as collections). */
		String text() throws xTalkException {
			if(line>0) return label;
			if(value==null) return "";
			return TalkJson.result(value);
		}
	}

	static final class Tree {
		final Node root;
		final boolean json;
		Tree(Node root, boolean json){ this.root = root; this.json = json; }
		/** The node at a JSON path or null. */
		Node find(String path){ return find(root, path); }
		private static Node find(Node node, String path){
			for(Node child : node.children){
				if(child.path.equals(path)) return child;
				if(path.startsWith(child.path+".")){ Node n = find(child, path); if(n!=null) return n; }
			}
			return null;
		}
		/** The node of a line (indented text) or null. */
		Node atLine(int line){ return atLine(root, line); }
		private static Node atLine(Node node, int line){
			for(Node child : node.children){
				if(child.line==line) return child;
				Node n = atLine(child, line);
				if(n!=null) return n;
			}
			return null;
		}
	}

	/** JSON when the text is a JSON object or array (or a HyperTalk collection), else indented text. */
	static Tree parse(String text){
		if(text==null) text = "";
		if(TalkCollection.isCollection(text)){
			try{ return json(fromCollection(text)); }catch(xTalkException e){ /* show it as text */ }
		}
		String t = text.trim();
		if(t.startsWith("{") || t.startsWith("[")){
			try{
				Object root = TalkJson.parse(t);
				if(root instanceof Map<?,?> || root instanceof List<?>) return json(root);
			}catch(xTalkException e){ /* not JSON: an indented outline that starts with a bracket */ }
		}
		return indented(text);
	}

	private static Object fromCollection(String value) throws xTalkException {
		if(!TalkCollection.isCollection(value)) return value;
		TalkCollection c = TalkCollection.decode(value);
		if(c.isArray()){
			ArrayList<Object> list = new ArrayList<Object>();
			for(String item : c.items) list.add(fromCollection(item));
			return list;
		}
		LinkedHashMap<String,Object> map = new LinkedHashMap<String,Object>();
		for(Map.Entry<String,String> e : c.entries.entrySet()) map.put(e.getKey(), fromCollection(e.getValue()));
		return map;
	}

	private static Tree json(Object root){
		Node top = new Node("", "", 0, "", root, -1);
		members(top, root);
		return new Tree(top, true);
	}

	private static void members(Node parent, Object value){
		if(value instanceof Map<?,?>){
			for(Map.Entry<?,?> e : ((Map<?,?>)value).entrySet()) member(parent, String.valueOf(e.getKey()), e.getValue());
		}
		else if(value instanceof List<?>){
			List<?> list = (List<?>)value;
			for(int i=0; i<list.size(); i++) member(parent, Integer.toString(i+1), list.get(i));
		}
	}

	private static void member(Node parent, String key, Object value){
		String path = parent.path.isEmpty() ? key : parent.path+"."+key;
		Node n = new Node(key+describe(value), path, 0, path, value, parent.level+1);
		parent.children.add(n);
		members(n, value);
	}

	/** ": value" for a scalar, " {3}" for an object of three members, " [2]" for an array of two. */
	private static String describe(Object value){
		if(value instanceof Map<?,?>) return " {"+((Map<?,?>)value).size()+"}";
		if(value instanceof List<?>) return " ["+((List<?>)value).size()+"]";
		if(value==null) return ": null";
		String text;
		try{ text = TalkJson.result(value); }catch(xTalkException e){ text = String.valueOf(value); }
		return ": "+text.replace("\r\n", "\n").replace('\r', '\n').replace('\n', '⏎');
	}

	/** Each non-empty line a node; its leading tabs give its level under the nearest line above with fewer tabs. */
	static Tree indented(String text){
		Node root = new Node("", "", 0, "", null, -1);
		if(text.isEmpty()) return new Tree(root, false);
		String[] lines = FieldTableData.lines(text);
		ArrayList<Node> parents = new ArrayList<Node>();
		ArrayList<Integer> tabs = new ArrayList<Integer>();
		parents.add(root);
		tabs.add(-1);
		for(int i=0; i<lines.length; i++){
			String line = lines[i];
			if(line.trim().isEmpty()) continue;
			int depth = 0;
			while(depth<line.length() && line.charAt(depth)=='\t') depth++;
			while(tabs.size()>1 && tabs.get(tabs.size()-1)>=depth){ parents.remove(parents.size()-1); tabs.remove(tabs.size()-1); }
			Node parent = parents.get(parents.size()-1);
			String label = line.substring(depth);
			if(label.equals(NEW_NODE)) label = "";
			//the id: the labels from the top (a repeated label under the same parent gets its count)
			String base = parent.id+"\u0001"+label;
			String id = base;
			for(int k=2; hasChildId(parent, id); k++) id = base+"\u0002"+k;
			Node n = new Node(label, id, i+1, "", null, parent.level+1);
			parent.children.add(n);
			parents.add(n);
			tabs.add(depth);
		}
		return new Tree(root, false);
	}

	static Set<String> ids(Node node){
		Set<String> ids = new HashSet<String>();
		addIds(node, ids);
		return ids;
	}
	private static void addIds(Node node, Set<String> ids){
		for(Node c : node.children){ ids.add(c.id); addIds(c, ids); }
	}
	static boolean sharesNode(Node node, Set<String> ids){
		for(Node c : node.children) if(ids.contains(c.id) || sharesNode(c, ids)) return true;
		return false;
	}

	private static boolean hasChildId(Node parent, String id){
		for(Node c : parent.children) if(c.id.equals(id)) return true;
		return false;
	}

	/** The label of a new node's line while it is being typed (not empty, so the line is a node; shown as nothing). */
	static final String NEW_NODE = "\u200B";

	/** The leading tabs of a line (1-based). */
	static int depth(String text, int line){
		String[] lines = FieldTableData.lines(text);
		if(line<1 || line>lines.length) return 0;
		int depth = 0;
		while(depth<lines[line-1].length() && lines[line-1].charAt(depth)=='\t') depth++;
		return depth;
	}

	/** The last line of a node and its children. */
	static int lastLine(Node n){
		int last = n.line;
		for(Node c : n.children) last = Math.max(last, lastLine(c));
		return last;
	}

	/** The text with a new line (1-based; one past the last adds it at the end) of depth tabs and a label. */
	static String insertLine(String text, int line, int depth, String label){
		String v = label.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		List<String> lines = new ArrayList<String>(text.isEmpty() ? new ArrayList<String>() : Arrays.asList(FieldTableData.lines(text)));
		StringBuilder sb = new StringBuilder();
		for(int i=0; i<depth; i++) sb.append('\t');
		lines.add(Math.max(0, Math.min(line-1, lines.size())), sb.append(v).toString());
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}

	/** The text without one line (1-based). */
	static String deleteLine(String text, int line){
		String[] lines = FieldTableData.lines(text);
		if(text.isEmpty() || line<1 || line>lines.length) return text;
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		List<String> rest = new ArrayList<String>(Arrays.asList(lines));
		rest.remove(line-1);
		if(rest.isEmpty()) return "";
		return String.join("\n", rest)+(finalReturn ? "\n" : "");
	}

	/** Lines first to last (1-based) with delta tabs more (fewer, when negative, as far as they have tabs). */
	static String shift(String text, int first, int last, int delta){
		String[] lines = FieldTableData.lines(text);
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		for(int i=Math.max(1, first); i<=Math.min(last, lines.length); i++){
			String line = lines[i-1];
			if(delta>0){
				StringBuilder sb = new StringBuilder();
				for(int k=0; k<delta; k++) sb.append('\t');
				lines[i-1] = sb.append(line).toString();
			}
			else{
				int cut = 0;
				while(cut<-delta && cut<line.length() && line.charAt(cut)=='\t') cut++;
				lines[i-1] = line.substring(cut);
			}
		}
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}

	/** The text with the label of one line (1-based) replaced, keeping its tabs; returns and tabs in the label become spaces. */
	static String setLabel(String text, int line, String label){
		String v = label.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
		String[] lines = FieldTableData.lines(text);
		if(line<1 || line>lines.length) return text;
		boolean finalReturn = text.endsWith("\n") || text.endsWith("\r");
		String old = lines[line-1];
		int depth = 0;
		while(depth<old.length() && old.charAt(depth)=='\t') depth++;
		lines[line-1] = old.substring(0, depth)+v;
		return String.join("\n", lines)+(finalReturn ? "\n" : "");
	}
}
