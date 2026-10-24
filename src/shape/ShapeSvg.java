import java.awt.Color;
import java.awt.Font;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** SVG の基本ベクター要素を独立 Shape に分解し、一つの Panel にまとめる。 */
final class ShapeSvg {
	private static final Pattern NUMBER=Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
	private static final Pattern TRANSFORM=Pattern.compile("([A-Za-z]+)\\s*\\(([^)]*)\\)");
	private record Gradient(Color start,Color end,boolean radial,float x1,float y1,float x2,float y2){}
	private ShapeSvg(){}
	static ArrayList<OShape> read(File file,OCardBase owner) throws Exception {
		DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
		factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
		factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
		Element root=factory.newDocumentBuilder().parse(file).getDocumentElement();
		String rootName=root.getLocalName()!=null?root.getLocalName():root.getTagName();
		if(!rootName.equalsIgnoreCase("svg"))throw new IllegalArgumentException("SVGのルート要素がありません");
		ArrayList<OShape> result=new ArrayList<>();
		double[] view=nums(root.getAttribute("viewBox"));
		AffineTransform transform=new AffineTransform();
		if(view.length>=4){
			double w=length(root.getAttribute("width"),view[2]),h=length(root.getAttribute("height"),view[3]);
			if(w>0&&h>0&&view[2]>0&&view[3]>0){double scale=Math.min(w/view[2],h/view[3]);transform.translate((w-view[2]*scale)/2,(h-view[3]*scale)/2);transform.scale(scale,scale);transform.translate(-view[0],-view[1]);}
		}
		Map<String,Gradient> gradients=gradients(root);
		Map<String,Element> definitions=new HashMap<>();var nodes=root.getElementsByTagName("*");
		for(int i=0;i<nodes.getLength();i++){Element element=(Element)nodes.item(i);if(element.hasAttribute("id"))definitions.put(element.getAttribute("id"),element);}
		Map<String,SvgFilter> filters=new HashMap<>();
		for(var entry:definitions.entrySet()){
			Element element=entry.getValue();String name=element.getLocalName()!=null?element.getLocalName():element.getTagName();
			if(name.equals("filter"))filters.put(entry.getKey(),SvgFilter.fromSvg(element));
		}
		walk(root,owner,transform,new HashMap<>(),gradients,filters,definitions,result,0);
		return result;
	}
	private static void walk(Element element,OCardBase owner,AffineTransform inherited,Map<String,String> inheritedStyle,Map<String,Gradient> gradients,Map<String,SvgFilter> filters,Map<String,Element> definitions,ArrayList<OShape> output,int depth){
		String tag=element.getLocalName()!=null?element.getLocalName():element.getTagName();
		AffineTransform transform=new AffineTransform(inherited);transform.concatenate(transform(element.getAttribute("transform")));
		Map<String,String> style=new HashMap<>(inheritedStyle);
		for(String key:new String[]{"fill","stroke","stroke-width","fill-opacity","stroke-opacity","opacity","font-family","font-size","font-weight","display","visibility","filter"})
			if(element.hasAttribute(key))style.put(key,element.getAttribute(key));
			String css=element.getAttribute("style");for(String item:css.split(";")){int colon=item.indexOf(':');if(colon>0)style.put(item.substring(0,colon).trim(),item.substring(colon+1).trim());}
		if(tag.equalsIgnoreCase("use")){
			if(depth>=8)return;
			String href=element.getAttribute("href");if(href.isEmpty())href=element.getAttributeNS("http://www.w3.org/1999/xlink","href");
			if(href.startsWith("#")){
				Element target=definitions.get(href.substring(1));
				if(target!=null){AffineTransform position=new AffineTransform(transform);position.translate(length(element.getAttribute("x"),0),length(element.getAttribute("y"),0));
					if("symbol".equalsIgnoreCase(target.getLocalName())){
						for(Node child=target.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element)
							walk((Element)child,owner,position,style,gradients,filters,definitions,output,depth+1);
					}else walk(target,owner,position,style,gradients,filters,definitions,output,depth+1);
				}
			}
			return;
		}
		if(tag.equalsIgnoreCase("defs")||tag.equalsIgnoreCase("filter")||tag.equalsIgnoreCase("clipPath")||tag.equalsIgnoreCase("mask")||tag.equalsIgnoreCase("pattern")||tag.equalsIgnoreCase("symbol")||tag.equalsIgnoreCase("metadata")||tag.equalsIgnoreCase("script")||tag.equalsIgnoreCase("style"))return;
		if(!"none".equals(style.get("display")) && !"hidden".equals(style.get("visibility"))){
			Shape raw=geometry(element,tag,style);
			if(raw!=null){
				Shape shape=transform.createTransformedShape(raw);Rectangle2D bounds=shape.getBounds2D();
				if(bounds.getWidth()>0||bounds.getHeight()>0){
					int id=nextId(owner,output);
					OShape result=new OShape(owner,id);result.name=element.getAttribute("id");result.kind="bezier";
					result.left=(int)Math.floor(bounds.getX());result.top=(int)Math.floor(bounds.getY());
					result.width=Math.max(1,(int)Math.ceil(bounds.getMaxX())-result.left);result.height=Math.max(1,(int)Math.ceil(bounds.getMaxY())-result.top);
					result.pathData=ShapePath.normalized(shape,new Rectangle2D.Double(result.left,result.top,0,0));
					String fill=style.getOrDefault("fill","black"),stroke=style.getOrDefault("stroke","none");
					result.fillColor=paint(fill);result.strokeColor=paint(stroke);result.strokeWidth=(float)length(style.get("stroke-width"),1);
					result.fillColor=opacity(result.fillColor,style.get("fill-opacity"),style.get("opacity"));
					result.strokeColor=opacity(result.strokeColor,style.get("stroke-opacity"),style.get("opacity"));
					String filter=style.getOrDefault("filter","").trim();
					if(filter.startsWith("url(")&&filter.endsWith(")")){
						String ref=filter.substring(4,filter.length()-1).trim().replace("\"","").replace("'","");
						if(ref.startsWith("#"))result.svgFilter=filters.get(ref.substring(1));
					}
					if(fill.startsWith("url(#")&&fill.endsWith(")")){
						Gradient gradient=gradients.get(fill.substring(5,fill.length()-1));
						if(gradient!=null){result.gradientStart=gradient.start;result.gradientEnd=gradient.end;result.radialGradient=gradient.radial;result.gradientX1=gradient.x1;result.gradientY1=gradient.y1;result.gradientX2=gradient.x2;result.gradientY2=gradient.y2;}
					}
					if(tag.equalsIgnoreCase("image")){
						String href=element.getAttribute("href");if(href.isEmpty())href=element.getAttributeNS("http://www.w3.org/1999/xlink","href");
						int comma=href.indexOf(',');if(href.startsWith("data:image/")&&href.substring(0,Math.max(0,comma)).contains("base64")&&comma>=0)
							try{result.setTexture(java.util.Base64.getDecoder().decode(href.substring(comma+1)));result.stretchTexture=true;result.strokeColor=null;}catch(IllegalArgumentException ignored){}
					}
					output.add(result);
				}
			}
		}
		for(Node child=element.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element)
			walk((Element)child,owner,transform,style,gradients,filters,definitions,output,depth);
	}
	private static Map<String,Gradient> gradients(Element root){
		Map<String,Gradient> result=new HashMap<>();
		for(String tag:new String[]{"linearGradient","radialGradient"}){
			var nodes=root.getElementsByTagNameNS("*",tag);
			for(int i=0;i<nodes.getLength();i++){
				Element e=(Element)nodes.item(i);String id=e.getAttribute("id");if(id.isEmpty())continue;
				Color first=null,last=null;
				for(Node child=e.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element stop&&stop.getLocalName()!=null&&stop.getLocalName().equals("stop")){
					String color=stop.getAttribute("stop-color"),opacity=stop.getAttribute("stop-opacity");
					for(String item:stop.getAttribute("style").split(";")){int colon=item.indexOf(':');if(colon<0)continue;String key=item.substring(0,colon).trim(),v=item.substring(colon+1).trim();if(key.equals("stop-color"))color=v;else if(key.equals("stop-opacity"))opacity=v;}
					Color c=opacity(paint(color.isEmpty()?"black":color),opacity,null);if(first==null)first=c;last=c;
				}
				if(first==null||last==null)continue;
				boolean radial=tag.equals("radialGradient");
				float x1=(float)fraction(e.getAttribute(radial?"cx":"x1"),radial?0.5:0),y1=(float)fraction(e.getAttribute(radial?"cy":"y1"),radial?0.5:0);
				float x2=(float)fraction(e.getAttribute(radial?"r":"x2"),radial?0.5:1),y2=(float)fraction(e.getAttribute("y2"),0);
				result.put(id,new Gradient(first,last,radial,x1,y1,x2,y2));
			}
		}
		return result;
	}
	private static double fraction(String value,double fallback){if(value==null||value.isBlank())return fallback;double n=length(value,fallback);return value.contains("%")?n/100:n;}
	private static int nextId(OCardBase owner,ArrayList<OShape> output){int id=1;while(true){boolean used=false;if(owner.shapeList!=null)for(OShape s:owner.shapeList)if(s.id==id){used=true;break;}for(OShape s:output)if(s.id==id){used=true;break;}if(!used)return id;id++;}}
	private static Shape geometry(Element e,String tag,Map<String,String> style){
		try{
			switch(tag.toLowerCase()){
			case "path":return ShapePath.parse(e.getAttribute("d"));
			case "rect":{double x=length(e.getAttribute("x"),0),y=length(e.getAttribute("y"),0),w=length(e.getAttribute("width"),0),h=length(e.getAttribute("height"),0);double rx=length(e.getAttribute("rx"),0),ry=length(e.getAttribute("ry"),rx);return rx>0||ry>0?new java.awt.geom.RoundRectangle2D.Double(x,y,w,h,2*rx,2*ry):new Rectangle2D.Double(x,y,w,h);}
			case "image":return new Rectangle2D.Double(length(e.getAttribute("x"),0),length(e.getAttribute("y"),0),length(e.getAttribute("width"),0),length(e.getAttribute("height"),0));
			case "circle":{double cx=length(e.getAttribute("cx"),0),cy=length(e.getAttribute("cy"),0),r=length(e.getAttribute("r"),0);return new Ellipse2D.Double(cx-r,cy-r,r*2,r*2);}
			case "ellipse":{double cx=length(e.getAttribute("cx"),0),cy=length(e.getAttribute("cy"),0),rx=length(e.getAttribute("rx"),0),ry=length(e.getAttribute("ry"),0);return new Ellipse2D.Double(cx-rx,cy-ry,rx*2,ry*2);}
			case "line":return new Line2D.Double(length(e.getAttribute("x1"),0),length(e.getAttribute("y1"),0),length(e.getAttribute("x2"),0),length(e.getAttribute("y2"),0));
			case "polyline":case "polygon":{double[] pts=nums(e.getAttribute("points"));Path2D.Double p=new Path2D.Double();for(int i=0;i+1<pts.length;i+=2){if(i==0)p.moveTo(pts[i],pts[i+1]);else p.lineTo(pts[i],pts[i+1]);}if(tag.equalsIgnoreCase("polygon"))p.closePath();return p;}
			case "text":{String text=e.getTextContent();if(text==null||text.isEmpty())return null;int size=(int)length(style.get("font-size"),24);String family=style.getOrDefault("font-family","SansSerif");boolean bold="bold".equalsIgnoreCase(style.get("font-weight"));Font font=OObject.stackFont(family,bold?Font.BOLD:Font.PLAIN,Math.max(1,size));return font.createGlyphVector(new java.awt.font.FontRenderContext(null,true,true),text).getOutline((float)length(e.getAttribute("x"),0),(float)length(e.getAttribute("y"),0));}
			default:return null;
			}
		}catch(Exception ex){return null;}
	}
	private static AffineTransform transform(String value){AffineTransform result=new AffineTransform();Matcher m=TRANSFORM.matcher(value==null?"":value);while(m.find()){double[] n=nums(m.group(2));if(n.length==0)continue;switch(m.group(1)){
		case "translate":result.translate(n[0],n.length>1?n[1]:0);break;
		case "scale":result.scale(n[0],n.length>1?n[1]:n[0]);break;
		case "rotate":if(n.length>=3)result.rotate(Math.toRadians(n[0]),n[1],n[2]);else result.rotate(Math.toRadians(n[0]));break;
		case "skewX":result.shear(Math.tan(Math.toRadians(n[0])),0);break;
		case "skewY":result.shear(0,Math.tan(Math.toRadians(n[0])));break;
		case "matrix":if(n.length>=6)result.concatenate(new AffineTransform(n));break;
	}}return result;}
	private static double[] nums(String value){ArrayList<Double> list=new ArrayList<>();Matcher m=NUMBER.matcher(value==null?"":value);while(m.find())try{list.add(Double.parseDouble(m.group()));}catch(Exception ignored){}double[] result=new double[list.size()];for(int i=0;i<result.length;i++)result[i]=list.get(i);return result;}
	private static double length(String s,double fallback){double[] n=nums(s);return n.length>0?n[0]:fallback;}
	static Color paint(String value){if(value==null||value.equals("none")||value.startsWith("url("))return null;switch(value.toLowerCase()){
		case "black":return Color.BLACK;case "white":return Color.WHITE;case "red":return Color.RED;case "green":return Color.GREEN;case "blue":return Color.BLUE;case "yellow":return Color.YELLOW;case "gray":case "grey":return Color.GRAY;case "transparent":return new Color(0,0,0,0);
	}if(value.startsWith("rgb")){double[] n=nums(value);if(n.length>=3)return new Color(clamp(n[0]),clamp(n[1]),clamp(n[2]));}return OShape.color(value);}
	private static int clamp(double value){return Math.max(0,Math.min(255,(int)Math.round(value)));}
	private static Color opacity(Color c,String local,String global){if(c==null)return null;double alpha=length(local,1)*length(global,1);return new Color(c.getRed(),c.getGreen(),c.getBlue(),clamp(c.getAlpha()*alpha));}
}
