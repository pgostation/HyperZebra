import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;


public class XMLwrite {
	OStack stack;
	saveThread saveThread;
	
	public XMLwrite(OStack stack){
		this.stack = stack;
		
		saveThread = new saveThread();
		saveThread.start();
	}
	
	public void saveStackNow(){
		if(stack.cantModify) return;
		String fname = stack.file.getName();
		if(fname.substring(fname.length()-4).equals(".xml")
				&& stack.file.exists() && !stack.file.canWrite()) return;
		
		XMLOutputFactory factory = XMLOutputFactory.newInstance();
		 
        StringWriter stringWriter = new StringWriter();
        try {
        	XMLStreamWriter writer = factory.createXMLStreamWriter(stringWriter);
        	
			writer.writeStartDocument("utf-8", "1.0");
            writer.writeCharacters("\n");
			//writer.writeDTD("<!DOCTYPE stackfile PUBLIC \"-//Apple, Inc.//DTD stackfile V 2.0//EN\" \"\" >");
            writer.writeCharacters("\n");
	        writer.writeStartElement("stackfile");
            writer.writeCharacters("\n\t");

	        stack.writeXML(writer);
	        stack.changed = false;
	        stack.writeFontXML(writer);
	        stack.writeStyleXML(writer);
			for(int i=0 ;i<stack.bgCacheList.size(); i++){
				stack.bgCacheList.get(i).writeXML(writer);
				stack.bgCacheList.get(i).changed = false;
			}
			/*for(int i=0 ;i<stack.cdCacheList.size(); i++){
				stack.cdCacheList.get(i).writeXML(writer);
				stack.cdCacheList.get(i).changed = false;
			}*/ //これでは順番が狂ってしまう
			for(int i=0 ;i<stack.cardIdList.size(); i++){
				int id = stack.cardIdList.get(i);
				for(int j=0 ;j<stack.cdCacheList.size(); j++){
					if(stack.cdCacheList.get(j).id == id){
						stack.cdCacheList.get(j).writeXML(writer);
						stack.cdCacheList.get(j).changed = false;
						break;
					}
				}
			}
	        stack.rsrc.writeXML(writer);
	        stack.rsrc.writePLTEXML(writer);
	        stack.rsrc.writeAddColorXML(writer);
	        stack.rsrc.writeXcmdXML(writer);
	 
	        writer.writeEndDocument();
	 
	        writer.close();

			File file = stack.file;
			if(!stack.file.getName().substring(fname.length()-4).equals(".xml")){
				file = new File(stack.file.getParent()+File.separatorChar+"_stack.xml");
			}
			if(!file.exists() || file.canWrite()){
				try {
					FileOutputStream stream = new FileOutputStream(file);
					stream.write(stringWriter.toString().getBytes("utf-8"));
					stream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		} catch (XMLStreamException e) {
			e.printStackTrace();
		}
	}
	
	class saveThread extends Thread{
		//暇なときは変更を監視
		public void run(){
			setName("saveXMLThread");
			setPriority(MIN_PRIORITY);
			
			if(!stack.file.exists()){
				//xmlファイルがないときは即作成
				saveStackNow();
			}
			
			while(true){
				try{
					sleep(1000);//1秒1回
				} catch (InterruptedException e) {
			        //this.interrupt();
				}
				//if(isInterrupted()) break;
				if(PCARD.pc !=stack.pcard) break;
				if(stack.cdCacheList==null) break;
				
				if(TTalk.idle){
					if(stack.changed){
						saveStackNow();
						continue;
					}
					for(int i=0 ;i<stack.cdCacheList.size(); i++){
						if(stack.cdCacheList.get(i).changed){
							saveStackNow();
							continue;
						}
					}
					for(int i=0 ;i<stack.bgCacheList.size(); i++){
						if(stack.bgCacheList.get(i).changed){
							saveStackNow();
							continue;
						}
					}
				}
			}
		}
	}
}
