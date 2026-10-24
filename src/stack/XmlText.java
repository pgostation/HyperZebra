import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import javax.xml.stream.XMLStreamReader;

/**
 * HyperCardのフィールドにはバイナリ(制御文字)が入っていることがある。
 * XML 1.0 では \t \n \r 以外の制御文字は数値文字参照でも書けないので、書き出し時に私用領域
 * U+E000+コード へ写像し、読み込み時に元へ戻す。
 */
final class XmlText {
	private XmlText(){}

	static final char BASE = '\uE000';

	/** 保存する XML 文字列の中の制御文字を私用領域へ */
	static String encodeControlChars(String xml){
		StringBuilder sb = null;
		for(int i=0; i<xml.length(); i++){
			char c = xml.charAt(i);
			if(c<0x20 && c!='\t' && c!='\n' && c!='\r'){
				if(sb==null){ sb = new StringBuilder(xml.length()); sb.append(xml, 0, i); }
				sb.append((char)(BASE+c));
			}
			else if(sb!=null) sb.append(c);
		}
		return sb==null ? xml : sb.toString();
	}

	/** 読み込んだ文字列の私用領域 U+E000..U+E01F を制御文字へ */
	static String decodeControlChars(String text){
		if(text==null) return null;
		StringBuilder sb = null;
		for(int i=0; i<text.length(); i++){
			char c = text.charAt(i);
			if(c>=BASE && c<BASE+0x20){
				if(sb==null){ sb = new StringBuilder(text.length()); sb.append(text, 0, i); }
				sb.append((char)(c-BASE));
			}
			else if(sb!=null) sb.append(c);
		}
		return sb==null ? text : sb.toString();
	}

	/** getText/getElementText/getAttributeValue の結果を復号する XMLStreamReader */
	static XMLStreamReader decodingReader(final XMLStreamReader reader){
		return (XMLStreamReader)Proxy.newProxyInstance(XMLStreamReader.class.getClassLoader(), new Class<?>[]{XMLStreamReader.class},
			new InvocationHandler(){
				@Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
					Object r;
					try{ r = method.invoke(reader, args); }
					catch(java.lang.reflect.InvocationTargetException e){ throw e.getCause(); }
					String n = method.getName();
					if(r instanceof String && (n.equals("getText") || n.equals("getElementText") || n.equals("getAttributeValue"))){
						return decodeControlChars((String)r);
					}
					return r;
				}
			});
	}
}
