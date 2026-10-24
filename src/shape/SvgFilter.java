import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** SVG の主要なフィルタープリミティブを、同一サイズの透明画像上で順に合成する。 */
final class SvgFilter {
	static final class Primitive {
		final String type;
		final LinkedHashMap<String,String> attributes=new LinkedHashMap<>();
		final ArrayList<String> mergeInputs=new ArrayList<>();
		Primitive(String type){this.type=type;}
		String get(String name,String fallback){return attributes.getOrDefault(name,fallback);}
		@Override public boolean equals(Object other){return other instanceof Primitive p&&type.equals(p.type)&&attributes.equals(p.attributes)&&mergeInputs.equals(p.mergeInputs);}
		@Override public int hashCode(){return java.util.Objects.hash(type,attributes,mergeInputs);}
	}
	final ArrayList<Primitive> primitives=new ArrayList<>();
	SvgFilter(){}

	static SvgFilter fromSvg(Element filter){
		SvgFilter result=new SvgFilter();
		for(Node child=filter.getFirstChild();child!=null;child=child.getNextSibling()){
			if(!(child instanceof Element element))continue;
			String name=element.getLocalName()!=null?element.getLocalName():element.getTagName();
			if(!supported(name))continue;
			Primitive p=new Primitive(name);
			var attrs=element.getAttributes();
			for(int i=0;i<attrs.getLength();i++){Node a=attrs.item(i);if(!a.getNodeName().contains(":"))p.attributes.put(a.getNodeName(),a.getNodeValue());}
			for(String declaration:element.getAttribute("style").split(";")){int colon=declaration.indexOf(':');if(colon>0)p.attributes.put(declaration.substring(0,colon).trim(),declaration.substring(colon+1).trim());}
			if(name.equals("feMerge"))for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling())
				if(node instanceof Element entry&&"feMergeNode".equals(entry.getLocalName()!=null?entry.getLocalName():entry.getTagName()))p.mergeInputs.add(entry.getAttribute("in"));
			if(name.equals("feComponentTransfer"))for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling()){
				if(!(node instanceof Element function))continue;String channel=function.getLocalName()!=null?function.getLocalName():function.getTagName();
				if(!channel.matches("feFunc[RGBA]"))continue;
				var functionAttrs=function.getAttributes();for(int i=0;i<functionAttrs.getLength();i++){Node a=functionAttrs.item(i);p.attributes.put(channel+"."+a.getNodeName(),a.getNodeValue());}
			}
			result.primitives.add(p);
		}
		return result;
	}
	private static boolean supported(String name){if(name==null)return false;return switch(name){
		case "feGaussianBlur","feDropShadow","feOffset","feFlood","feComposite","feMerge","feColorMatrix","feComponentTransfer","feMorphology","feBlend" -> true;
		default -> false;
	};}
	int padding(){
		float pad=0;
		for(Primitive p:primitives){
			double[] deviation=numbers(p.get("stdDeviation","0"));float blur=deviation.length>0?(float)(Math.max(deviation[0],deviation.length>1?deviation[1]:deviation[0])*4):0;
			if(p.type.equals("feGaussianBlur")||p.type.equals("feDropShadow"))pad+=blur;
			if(p.type.equals("feMorphology")){double[] radius=numbers(p.get("radius","0"));if(radius.length>0)pad+=Math.max(radius[0],radius.length>1?radius[1]:radius[0]);}
			if(p.type.equals("feOffset")||p.type.equals("feDropShadow"))pad+=Math.max(Math.abs(number(p.get("dx","0"),0)),Math.abs(number(p.get("dy","0"),0)));
		}
		return Math.min(512,Math.max(0,(int)Math.ceil(pad+4)));
	}
	BufferedImage apply(BufferedImage source){
		BufferedImage previous=source;
		Map<String,BufferedImage> named=new LinkedHashMap<>();
		named.put("SourceGraphic",source);named.put("SourceAlpha",alpha(source));
		for(Primitive p:primitives){
			BufferedImage input=input(named,p.get("in",""),previous,source);
			BufferedImage output=switch(p.type){
				case "feGaussianBlur" -> blur(input,p.get("stdDeviation","0"));
				case "feOffset" -> offset(input,number(p.get("dx","0"),0),number(p.get("dy","0"),0));
				case "feFlood" -> flood(source.getWidth(),source.getHeight(),p);
				case "feDropShadow" -> shadow(input,p);
				case "feColorMatrix" -> matrix(input,p);
				case "feComponentTransfer" -> componentTransfer(input,p);
				case "feMorphology" -> morphology(input,p);
				case "feComposite" -> p.get("operator","over").equals("arithmetic")?arithmetic(input,input(named,p.get("in2",""),previous,source),p):composite(input,input(named,p.get("in2",""),previous,source),p);
				case "feBlend" -> blend(input,input(named,p.get("in2",""),previous,source),p.get("mode","normal"));
				case "feMerge" -> merge(named,p,previous,source);
				default -> input;
			};
			previous=output;
			String result=p.get("result","");if(!result.isEmpty())named.put(result,output);
		}
		return previous;
	}
	private static BufferedImage input(Map<String,BufferedImage> named,String ref,BufferedImage previous,BufferedImage source){
		if(ref.isEmpty())return previous;
		return named.getOrDefault(ref,empty(source.getWidth(),source.getHeight()));
	}
	private static BufferedImage empty(int w,int h){return new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);}
	private static BufferedImage alpha(BufferedImage source){
		BufferedImage out=empty(source.getWidth(),source.getHeight());
		for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)out.setRGB(x,y,source.getRGB(x,y)&0xff000000);
		return out;
	}
	private static BufferedImage offset(BufferedImage source,float dx,float dy){
		BufferedImage out=empty(source.getWidth(),source.getHeight());Graphics2D g=out.createGraphics();
		g.drawImage(source,Math.round(dx),Math.round(dy),null);g.dispose();return out;
	}
	private static BufferedImage blur(BufferedImage source,String deviation){
		String[] pair=deviation.trim().split("[ ,]+",2);
		float sx=Math.max(0,Math.min(32,number(pair.length>0?pair[0]:"0",0)));
		float sy=Math.max(0,Math.min(32,pair.length>1?number(pair[1],sx):sx));
		BufferedImage current=source;
		if(sx>0)current=convolve(current,sx,true);
		if(sy>0)current=convolve(current,sy,false);
		return current;
	}
	private static BufferedImage convolve(BufferedImage source,float sigma,boolean horizontal){
		int radius=Math.min(96,Math.max(1,(int)Math.ceil(sigma*3)));float[] weights=new float[radius*2+1];
		double sum=0;for(int i=-radius;i<=radius;i++){weights[i+radius]=(float)Math.exp(-i*i/(2*sigma*sigma));sum+=weights[i+radius];}
		for(int i=0;i<weights.length;i++)weights[i]/=sum;
		BufferedImage premultiplied=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g=premultiplied.createGraphics();g.drawImage(source,0,0,null);g.dispose();
		Kernel kernel=new Kernel(horizontal?weights.length:1,horizontal?1:weights.length,weights);
		BufferedImage filtered=new ConvolveOp(kernel,ConvolveOp.EDGE_ZERO_FILL,null).filter(premultiplied,null);
		BufferedImage out=empty(source.getWidth(),source.getHeight());g=out.createGraphics();g.drawImage(filtered,0,0,null);g.dispose();return out;
	}
	private static BufferedImage flood(int w,int h,Primitive p){
		Color color=ShapeSvg.paint(p.get("flood-color","black"));if(color==null)color=Color.BLACK;
		float opacity=Math.max(0,Math.min(1,number(p.get("flood-opacity","1"),1)));
		BufferedImage out=empty(w,h);Graphics2D g=out.createGraphics();g.setColor(new Color(color.getRed(),color.getGreen(),color.getBlue(),Math.round(color.getAlpha()*opacity)));g.fillRect(0,0,w,h);g.dispose();return out;
	}
	private static BufferedImage shadow(BufferedImage source,Primitive p){
		BufferedImage mask=blur(alpha(source),p.get("stdDeviation","2"));
		mask=offset(mask,number(p.get("dx","2"),2),number(p.get("dy","2"),2));
		Primitive flood=new Primitive("feFlood");flood.attributes.put("flood-color",p.get("flood-color","black"));flood.attributes.put("flood-opacity",p.get("flood-opacity","1"));
		return over(source,composite(flood(source.getWidth(),source.getHeight(),flood),mask,"in"));
	}
	private static BufferedImage merge(Map<String,BufferedImage> named,Primitive p,BufferedImage previous,BufferedImage source){
		BufferedImage result=empty(source.getWidth(),source.getHeight());
		for(String ref:p.mergeInputs)result=over(input(named,ref,previous,source),result);
		return result;
	}
	private static BufferedImage matrix(BufferedImage source,Primitive p){
		float[] m=new float[20];String type=p.get("type","matrix");float value=number(p.get("values","1"),1);
		if(type.equals("saturate")){m=new float[]{.213f+.787f*value,.715f-.715f*value,.072f-.072f*value,0,0,.213f-.213f*value,.715f+.285f*value,.072f-.072f*value,0,0,.213f-.213f*value,.715f-.715f*value,.072f+.928f*value,0,0,0,0,0,1,0};}
		else if(type.equals("luminanceToAlpha")){m[15]=.2126f;m[16]=.7152f;m[17]=.0722f;}
		else{
			if(type.equals("hueRotate")){double a=Math.toRadians(value),c=Math.cos(a),s=Math.sin(a);m=new float[]{(float)(.213+.787*c-.213*s),(float)(.715-.715*c-.715*s),(float)(.072-.072*c+.928*s),0,0,(float)(.213-.213*c+.143*s),(float)(.715+.285*c+.140*s),(float)(.072-.072*c-.283*s),0,0,(float)(.213-.213*c-.787*s),(float)(.715-.715*c+.715*s),(float)(.072+.928*c+.072*s),0,0,0,0,0,1,0};}
			else{double[] values=numbers(p.get("values",""));if(values.length!=20)return source;for(int i=0;i<20;i++)m[i]=(float)values[i];}
		}
		BufferedImage out=empty(source.getWidth(),source.getHeight());float[] c=new float[4];int[] v=new int[4];
		for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++){
			int argb=source.getRGB(x,y);c[0]=((argb>>>16)&255)/255f;c[1]=((argb>>>8)&255)/255f;c[2]=(argb&255)/255f;c[3]=((argb>>>24)&255)/255f;
			for(int row=0;row<4;row++)v[row]=clamp(Math.round(255*(m[row*5]*c[0]+m[row*5+1]*c[1]+m[row*5+2]*c[2]+m[row*5+3]*c[3]+m[row*5+4])));
			out.setRGB(x,y,(v[3]<<24)|(v[0]<<16)|(v[1]<<8)|v[2]);
		}return out;
	}
	private static BufferedImage componentTransfer(BufferedImage source,Primitive p){
		BufferedImage out=empty(source.getWidth(),source.getHeight());String[] channels={"feFuncR","feFuncG","feFuncB","feFuncA"};int[][] lookup=new int[4][256];
		for(int channel=0;channel<4;channel++)for(int value=0;value<256;value++)lookup[channel][value]=clamp(Math.round(255*transfer(value/255f,p,channels[channel])));
		for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++){
			int argb=source.getRGB(x,y);out.setRGB(x,y,(lookup[3][(argb>>>24)&255]<<24)|(lookup[0][(argb>>>16)&255]<<16)|(lookup[1][(argb>>>8)&255]<<8)|lookup[2][argb&255]);
		}return out;
	}
	private static BufferedImage morphology(BufferedImage source,Primitive p){
		double[] radii=numbers(p.get("radius","0"));if(radii.length==0)return source;
		int rx=Math.min(32,Math.max(0,(int)Math.round(radii[0]))),ry=Math.min(32,Math.max(0,(int)Math.round(radii.length>1?radii[1]:radii[0])));
		if(rx==0&&ry==0)return source;
		boolean dilate=p.get("operator","erode").equals("dilate");
		BufferedImage horizontal=empty(source.getWidth(),source.getHeight());
		for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)horizontal.setRGB(x,y,morphPixel(source,x,y,rx,true,dilate));
		if(ry==0)return horizontal;
		BufferedImage out=empty(source.getWidth(),source.getHeight());
		for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)out.setRGB(x,y,morphPixel(horizontal,x,y,ry,false,dilate));
		return out;
	}
	private static int morphPixel(BufferedImage source,int x,int y,int radius,boolean horizontal,boolean dilate){
		int b=dilate?0:255,g=dilate?0:255,r=dilate?0:255,a=dilate?0:255;
		for(int i=-radius;i<=radius;i++){int px=horizontal?x+i:x,py=horizontal?y:y+i;
			int argb=px>=0&&py>=0&&px<source.getWidth()&&py<source.getHeight()?source.getRGB(px,py):0;
			if(dilate){b=Math.max(b,argb&255);g=Math.max(g,(argb>>>8)&255);r=Math.max(r,(argb>>>16)&255);a=Math.max(a,(argb>>>24)&255);}
			else{b=Math.min(b,argb&255);g=Math.min(g,(argb>>>8)&255);r=Math.min(r,(argb>>>16)&255);a=Math.min(a,(argb>>>24)&255);}
		}return (a<<24)|(r<<16)|(g<<8)|b;
	}
	private static float transfer(float input,Primitive p,String channel){
		String prefix=channel+".",type=p.get(prefix+"type","identity");
		return switch(type){
			case "linear" -> number(p.get(prefix+"slope","1"),1)*input+number(p.get(prefix+"intercept","0"),0);
			case "gamma" -> number(p.get(prefix+"amplitude","1"),1)*(float)Math.pow(input,number(p.get(prefix+"exponent","1"),1))+number(p.get(prefix+"offset","0"),0);
			case "table","discrete" -> {double[] values=numbers(p.get(prefix+"tableValues",""));if(values.length==0)yield input;if(values.length==1)yield (float)values[0];
				if(type.equals("discrete"))yield (float)values[Math.min(values.length-1,(int)(input*values.length))];
				float index=input*(values.length-1);int i=Math.min(values.length-2,(int)index);yield (float)(values[i]+(values[i+1]-values[i])*(index-i));}
			default -> input;
		};
	}
	private static BufferedImage arithmetic(BufferedImage a,BufferedImage b,Primitive p){
		float k1=number(p.get("k1","0"),0),k2=number(p.get("k2","0"),0),k3=number(p.get("k3","0"),0),k4=number(p.get("k4","0"),0);
		BufferedImage out=empty(a.getWidth(),a.getHeight());int[] rgb=new int[3];
		for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){
			int ca=a.getRGB(x,y),cb=b.getRGB(x,y);float aa=((ca>>>24)&255)/255f,ab=((cb>>>24)&255)/255f;
			float alpha=Math.max(0,Math.min(1,k1*aa*ab+k2*aa+k3*ab+k4));
			for(int i=0;i<3;i++){int shift=16-i*8;float va=((ca>>>shift)&255)/255f*aa,vb=((cb>>>shift)&255)/255f*ab;
				float premul=Math.max(0,Math.min(alpha,k1*va*vb+k2*va+k3*vb+k4));rgb[i]=alpha>0?clamp(Math.round(255*premul/alpha)):0;}
			out.setRGB(x,y,(clamp(Math.round(alpha*255))<<24)|(rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);
		}return out;
	}
	private static BufferedImage composite(BufferedImage a,BufferedImage b,Primitive p){return composite(a,b,p.get("operator","over"));}
	private static BufferedImage composite(BufferedImage a,BufferedImage b,String operator){
		BufferedImage out=empty(a.getWidth(),a.getHeight());int[] rgb=new int[3];
		for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){
			int ca=a.getRGB(x,y),cb=b.getRGB(x,y);float aa=((ca>>>24)&255)/255f,ab=((cb>>>24)&255)/255f;
			float fa=1,fb=1-aa;
			switch(operator){case "in":fa=ab;fb=0;break;case "out":fa=1-ab;fb=0;break;case "atop":fa=ab;fb=1-aa;break;case "xor":fa=1-ab;fb=1-aa;break;}
			float alpha=aa*fa+ab*fb;
			for(int i=0;i<3;i++){int shift=16-i*8;float premul=((ca>>>shift)&255)*aa*fa+((cb>>>shift)&255)*ab*fb;rgb[i]=alpha>0?clamp(Math.round(premul/alpha)):0;}
			out.setRGB(x,y,(clamp(Math.round(alpha*255))<<24)|(rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);
		}return out;
	}
	private static BufferedImage over(BufferedImage top,BufferedImage bottom){
		BufferedImage out=empty(top.getWidth(),top.getHeight());Graphics2D g=out.createGraphics();g.setComposite(AlphaComposite.Src);g.drawImage(bottom,0,0,null);g.setComposite(AlphaComposite.SrcOver);g.drawImage(top,0,0,null);g.dispose();return out;
	}
	private static BufferedImage blend(BufferedImage a,BufferedImage b,String mode){
		if(mode.equals("normal"))return over(a,b);
		BufferedImage out=empty(a.getWidth(),a.getHeight());int[] rgb=new int[3];
		for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++){
			int ca=a.getRGB(x,y),cb=b.getRGB(x,y);float aa=((ca>>>24)&255)/255f,ab=((cb>>>24)&255)/255f,alpha=aa+ab-aa*ab;
			for(int i=0;i<3;i++){int shift=16-i*8;float va=((ca>>>shift)&255)/255f,vb=((cb>>>shift)&255)/255f;
				float blend=switch(mode){case "multiply" -> va*vb;case "screen" -> va+vb-va*vb;case "darken" -> Math.min(va,vb);case "lighten" -> Math.max(va,vb);default -> va;};
				float premul=aa*(1-ab)*va+ab*(1-aa)*vb+aa*ab*blend;rgb[i]=alpha>0?clamp(Math.round(255*premul/alpha)):0;
			}out.setRGB(x,y,(clamp(Math.round(alpha*255))<<24)|(rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);
		}return out;
	}
	private static int clamp(int value){return Math.max(0,Math.min(255,value));}
	private static float number(String text,float fallback){try{float value=Float.parseFloat(text.trim());return Float.isFinite(value)?value:fallback;}catch(Exception e){return fallback;}}
	private static double[] numbers(String text){String[] parts=text.trim().split("[ ,]+",-1);ArrayList<Double> values=new ArrayList<>();for(String part:parts)try{double value=Double.parseDouble(part);if(Double.isFinite(value))values.add(value);}catch(Exception ignored){}double[] out=new double[values.size()];for(int i=0;i<out.length;i++)out[i]=values.get(i);return out;}

	/** The filter as SVG markup (the filter property of a shape): a filter element whose steps are its primitives. */
	String toSvg(){
		StringBuilder b=new StringBuilder("<filter xmlns=\"http://www.w3.org/2000/svg\">");
		for(Primitive p:primitives){
			b.append('\n').append('<').append(p.type);
			LinkedHashMap<String,LinkedHashMap<String,String>> functions=new LinkedHashMap<>();
			for(var attr:p.attributes.entrySet()){
				int dot=attr.getKey().indexOf('.');
				if(dot>0)functions.computeIfAbsent(attr.getKey().substring(0,dot),k->new LinkedHashMap<>()).put(attr.getKey().substring(dot+1),attr.getValue());
				else b.append(' ').append(attr.getKey()).append("=\"").append(escape(attr.getValue())).append('"');
			}
			if(functions.isEmpty()&&p.mergeInputs.isEmpty()){b.append("/>");continue;}
			b.append('>');
			for(String input:p.mergeInputs)b.append("<feMergeNode in=\"").append(escape(input)).append("\"/>");
			for(var function:functions.entrySet()){b.append('<').append(function.getKey());for(var attr:function.getValue().entrySet())b.append(' ').append(attr.getKey()).append("=\"").append(escape(attr.getValue())).append('"');b.append("/>");}
			b.append("</").append(p.type).append('>');
		}
		return b.append("\n</filter>").toString();
	}
	private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
	/** A filter from SVG markup: a filter element, or an svg document holding one (its first). */
	static SvgFilter fromSvgText(String markup) throws xTalkException {
		Element root;
		try{
			var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
			var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
			root=builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(markup))).getDocumentElement();
		}catch(Exception e){throw new xTalkException("Cannot read the filter SVG");}
		Element filter=null;
		if("filter".equals(localName(root)))filter=root;
		else{var found=root.getElementsByTagNameNS("*","filter");if(found.getLength()==0)found=root.getElementsByTagName("filter");if(found.getLength()>0)filter=(Element)found.item(0);}
		if(filter==null)throw new xTalkException("filter requires an SVG filter element");
		return fromSvg(filter);
	}
	private static String localName(Element e){return e.getLocalName()!=null?e.getLocalName():e.getTagName();}
	void writeXML(XMLStreamWriter writer) throws XMLStreamException {
		writer.writeStartElement("svgFilter");
		for(Primitive p:primitives){writer.writeStartElement("primitive");writer.writeAttribute("name",p.type);
			for(var attr:p.attributes.entrySet())writer.writeAttribute(attr.getKey(),attr.getValue());
			for(String input:p.mergeInputs){writer.writeEmptyElement("mergeNode");writer.writeAttribute("in",input);}
			writer.writeEndElement();
		}writer.writeEndElement();
	}
	static SvgFilter readXML(XMLStreamReader reader) throws XMLStreamException {
		SvgFilter filter=new SvgFilter();Primitive current=null;
		while(reader.hasNext()){int event=reader.next();
			if(event==XMLStreamConstants.START_ELEMENT){String name=reader.getLocalName();
				if(name.equals("primitive")){String type=reader.getAttributeValue(null,"name");if(!supported(type))continue;current=new Primitive(type);
					for(int i=0;i<reader.getAttributeCount();i++){String key=reader.getAttributeLocalName(i);if(!key.equals("name"))current.attributes.put(key,reader.getAttributeValue(i));}}
				else if(name.equals("mergeNode")&&current!=null)current.mergeInputs.add(reader.getAttributeValue(null,"in"));
			}else if(event==XMLStreamConstants.END_ELEMENT){String name=reader.getLocalName();if(name.equals("primitive")&&current!=null){filter.primitives.add(current);current=null;}else if(name.equals("svgFilter"))break;}
		}return filter;
	}
	@Override public boolean equals(Object other){return other instanceof SvgFilter f&&primitives.equals(f.primitives);}
	@Override public int hashCode(){return primitives.hashCode();}
}
