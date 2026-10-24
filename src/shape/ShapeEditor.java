import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.util.ArrayList;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/** Shape の作成・編集とベジェ制御点エディタ。 */
final class ShapeEditor {
	static String currentKind="ellipse";
	static OShape selected;
	private static final String[] KINDS={"rectangle","line","ellipse","regularPolygon","polygon","freehand","bezier","text"};
	private static final String[] KIND_LABELS={"Rectangle","Line","Oval","Regular Polygon","Polygon","Freehand","Bézier","Text"};
	private ShapeEditor(){}
	/** 種類メニューの項目名(長方形・テキストは一般の訳、ほかはシェイプ情報の訳)。 */
	private static String kindLabel(int i){
		String kind=KINDS[i];
		return kind.equals("rectangle")||kind.equals("text")?International.t(KIND_LABELS[i]):International.tc("shape",KIND_LABELS[i]);
	}
	static void create(){
		if(owner()==null)return;
		TBButtonListener.ChangeTool("Shape",null);
		AuthoringUndo.Snapshot before=AuthoringUndo.capture();
		OShape shape=add(currentKind,Math.max(0,(PCARD.pc.stack.width-160)/2),Math.max(0,(PCARD.pc.stack.height-100)/2),160,100);
		ShapeTool.select(shape);
		AuthoringUndo.commit(before);
	}
	static OShape add(String kind,int x,int y,int width,int height){
		OCardBase owner=owner();
		if(owner==null)return null;
		OShape shape=OShape.create(owner,nextId(owner),kind);
		shape.setSize(width,height);shape.left=x;shape.top=y;
		owner.shapeList.add(shape);owner.changed=true;OCard.reloadCurrentCard();
		ShapeTool.refresh();
		return shape;
	}
	static void showKinds(java.awt.Component invoker){
		javax.swing.JPopupMenu popup=new javax.swing.JPopupMenu();
		for(int i=0;i<KINDS.length;i++){
			final String kind=KINDS[i];
			javax.swing.JCheckBoxMenuItem item=new javax.swing.JCheckBoxMenuItem(kindLabel(i),kind.equals(currentKind));
			item.addActionListener(e->{currentKind=kind;TBButtonListener.ChangeTool("Shape",null);});popup.add(item);
		}
		popup.show(invoker,0,invoker.getHeight());
	}
	static void importSvg(){
		OCardBase owner=owner();if(owner==null)return;
		JFileChooser chooser=new JFileChooser();chooser.setDialogTitle(International.tc("shape","Import SVG"));
		if(chooser.showOpenDialog(PCARD.pc)!=JFileChooser.APPROVE_OPTION)return;
		File file=chooser.getSelectedFile();String base=file.getName().replaceFirst("(?i)\\.svg$","");
		String panel=JOptionPane.showInputDialog(PCARD.pc,International.tc("shape","Imported Panel name"),base);
		if(panel==null)return;
		try{
			ArrayList<OShape> shapes=ShapeSvg.read(file,owner);
			if(shapes.isEmpty())throw new IllegalArgumentException("表示できるベクター要素がありません");
			AuthoringUndo.Snapshot before=AuthoringUndo.capture();
			//the imported shapes go into a new panel of that name (a panel is made like a button: Objects > New Panel)
			OPanel group=owner.newPanel(panel.trim().isEmpty()?base:panel.trim());
			for(OShape shape:shapes)shape.panelId=group.id;
			owner.shapeList.addAll(shapes);owner.changed=true;OCard.reloadCurrentCard();
			TBButtonListener.ChangeTool("Shape",null);ShapeTool.select(shapes.get(0));PCARD.pc.mainPane.repaint();
			AuthoringUndo.commit(before);
			//as after New Panel: Panel Info shows the new panel and the shapes in it
			AuthDialog.openAuthDialog(PCARD.pc,"panel",group);
		}catch(Exception e){JOptionPane.showMessageDialog(PCARD.pc,International.tc("shape","Could not import SVG: ")+e.getMessage(),International.tc("shape","Import SVG"),JOptionPane.ERROR_MESSAGE);}
	}
	static void deleteSelected(){if(!active(selected))return;AuthoringUndo.Snapshot before=AuthoringUndo.capture();OCardBase owner=(OCardBase)selected.parent;owner.shapeList.remove(selected);owner.changed=true;if(selected.view!=null&&selected.view.getParent()!=null)selected.view.getParent().remove(selected.view);ShapeTool.select(null);PCARD.pc.mainPane.repaint();AuthoringUndo.commit(before);}
	static boolean active(OShape shape){if(shape==null||PCARD.pc==null||PCARD.pc.stack==null||PCARD.pc.stack.curCard==null||!(shape.parent instanceof OCardBase))return false;OCard card=PCARD.pc.stack.curCard;return (shape.parent==card&&!PaintTool.editBackground||shape.parent==card.getBg())&&((OCardBase)shape.parent).shapeList.contains(shape);}
	static OCardBase owner(){if(PCARD.pc==null||PCARD.pc.stack==null||PCARD.pc.stack.curCard==null)return null;return PaintTool.editBackground?PCARD.pc.stack.curCard.bg:PCARD.pc.stack.curCard;}
	private static int nextId(OCardBase owner){int id=1;while(true){boolean used=false;for(OShape s:owner.shapeList)if(s.id==id){used=true;break;}if(!used)return id;id++;}}
	static void copySelected(){
		if(!active(selected))return;
		try{
			java.io.StringWriter text=new java.io.StringWriter();
			javax.xml.stream.XMLStreamWriter writer=javax.xml.stream.XMLOutputFactory.newFactory().createXMLStreamWriter(text);
			writer.writeStartElement("hyperzebra-shape");selected.writeXML(writer);writer.writeEndElement();writer.close();
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text.toString()),null);
		}catch(Exception e){throw new IllegalStateException(e);}
	}
	static void paste(){
		if(owner()==null)return;
		try{
			Object data=java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
			if(!(data instanceof String text)||!text.startsWith("<hyperzebra-shape>"))return;
			javax.xml.stream.XMLInputFactory factory=javax.xml.stream.XMLInputFactory.newFactory();
			factory.setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD,false);
			factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
			javax.xml.stream.XMLStreamReader reader=factory.createXMLStreamReader(new java.io.StringReader(text));
			OShape shape=null;
			try{while(reader.hasNext())if(reader.next()==javax.xml.stream.XMLStreamConstants.START_ELEMENT&&reader.getLocalName().equals("shape")){shape=OShape.readXML(reader,owner());break;}}
			finally{reader.close();}
			if(shape==null)return;
			AuthoringUndo.Snapshot before=AuthoringUndo.capture();
			shape.id=nextId(owner());shape.panelId=0;shape.left+=12;shape.top+=12;shape.layer=Integer.MAX_VALUE;
			owner().shapeList.add(shape);owner().changed=true;OCard.reloadCurrentCard();ShapeTool.select(shape);
			AuthoringUndo.commit(before);
		}catch(java.awt.datatransfer.UnsupportedFlavorException e){/* Clipboard contains another kind of object. */}
		catch(Exception e){JOptionPane.showMessageDialog(PCARD.pc,International.tc("shape","Could not paste shape: ")+e.getMessage());}
	}
	static void reorder(boolean forward){
		if(!active(selected))return;
		// One step in the stacking the shape shares with the buttons and fields.
		((OCardBase)selected.parent).stepInStack(selected,forward);
		OCard.reloadCurrentCard();ShapeTool.refresh();
	}
	static boolean open(OShape shape){
		if(!active(shape))return false;
		// Like Button Info: changes go to the card as they are made, Close ends it, Undo takes back the whole visit.
		AuthoringUndo.Snapshot before=AuthoringUndo.capture();
		ShapeInfoPanel editor=new ShapeInfoPanel(shape);
		try{
			String close=International.t("Close");
			JOptionPane.showOptionDialog(PCARD.pc,editor,International.tc("shape","Shape Info"),JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,new Object[]{close},close);
			editor.finish();
			if(editor.pathEditRequested)editBezier(shape);
		}finally{AuthoringUndo.commit(before);}
		if(editor.scriptRequested)ScriptEditor.openScriptEditor(PCARD.pc,shape);
		if(editor.panelInfoRequested&&shape.panel()!=null)AuthDialog.openAuthDialog(PCARD.pc,"panel",shape.panel());
		return true;
	}
	static void editBezier(OShape shape){
		String before=shape.pathData;PathCanvas canvas=new PathCanvas(shape);
		JCheckBox freehand=new JCheckBox(International.tc("shape","Draw freehand curve"));canvas.freehand=freehand;
		JPanel panel=new JPanel(new java.awt.BorderLayout());panel.add(canvas,java.awt.BorderLayout.CENTER);panel.add(freehand,java.awt.BorderLayout.SOUTH);
		int result=JOptionPane.showConfirmDialog(PCARD.pc,panel,International.tc("shape","Bézier editor: drag anchor and control points"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
		if(result==JOptionPane.OK_OPTION){Shape path=canvas.path();Rectangle2D b=path.getBounds2D();if(!b.isEmpty()){
			int bx=(int)Math.floor(b.getX()),by=(int)Math.floor(b.getY());shape.left+=bx;shape.top+=by;shape.width=Math.max(1,(int)Math.ceil(b.getMaxX())-bx);shape.height=Math.max(1,(int)Math.ceil(b.getMaxY())-by);
			shape.pathData=ShapePath.normalized(path,new Rectangle2D.Double(bx,by,0,0));shape.kind="bezier";shape.repaintView();((OCardBase)shape.parent).changed=true;
		}}else shape.pathData=before;
	}
	private static final class Segment{int type;double[] p;Segment(int type,double[] p){this.type=type;this.p=p;}}
	private static final class PathCanvas extends JPanel{
		final OShape shape;final ArrayList<Segment> segments=new ArrayList<>();JCheckBox freehand;int selectedSegment=-1,selectedPoint=-1;double scale,ox,oy;
		PathCanvas(OShape shape){this.shape=shape;setPreferredSize(new Dimension(620,430));double[] v=new double[6];for(PathIterator it=shape.geometry().getPathIterator(null);!it.isDone();it.next()){int t=it.currentSegment(v),n=t==0||t==1?2:t==2?4:t==3?6:0;segments.add(new Segment(t,java.util.Arrays.copyOf(v,n)));}
			MouseAdapter mouse=new MouseAdapter(){
				@Override public void mousePressed(MouseEvent e){if(freehand.isSelected()){segments.clear();addPoint(0,e);return;}double best=15*15;for(int i=0;i<segments.size();i++){Segment s=segments.get(i);for(int j=0;j<s.p.length;j+=2){double dx=e.getX()-(ox+s.p[j]*scale),dy=e.getY()-(oy+s.p[j+1]*scale),d=dx*dx+dy*dy;if(d<best){best=d;selectedSegment=i;selectedPoint=j;}}}}
			@Override public void mouseDragged(MouseEvent e){if(freehand.isSelected()){addPoint(1,e);return;}if(selectedSegment>=0){Segment s=segments.get(selectedSegment);s.p[selectedPoint]=(e.getX()-ox)/scale;s.p[selectedPoint+1]=(e.getY()-oy)/scale;repaint();}}
			@Override public void mouseReleased(MouseEvent e){if(freehand.isSelected())smoothFreehand();selectedSegment=-1;selectedPoint=-1;}
			};addMouseListener(mouse);addMouseMotionListener(mouse);
		}
		void addPoint(int type,MouseEvent e){double x=(e.getX()-ox)/scale,y=(e.getY()-oy)/scale;
			if(type==1&&!segments.isEmpty()){double[] last=segments.get(segments.size()-1).p;if(Math.hypot(x-last[0],y-last[1])*scale<2)return;}
			segments.add(new Segment(type,new double[]{x,y}));repaint();}
		void smoothFreehand(){if(segments.size()<3)return;ArrayList<Segment> samples=new ArrayList<>(segments);segments.clear();segments.add(samples.get(0));
			for(int i=0;i+1<samples.size();i++){double[] prev=samples.get(Math.max(0,i-1)).p,a=samples.get(i).p,b=samples.get(i+1).p,next=samples.get(Math.min(samples.size()-1,i+2)).p;
				segments.add(new Segment(PathIterator.SEG_CUBICTO,new double[]{a[0]+(b[0]-prev[0])/6,a[1]+(b[1]-prev[1])/6,b[0]-(next[0]-a[0])/6,b[1]-(next[1]-a[1])/6,b[0],b[1]}));}
			repaint();}
		Shape path(){Path2D.Double p=new Path2D.Double();for(Segment s:segments){switch(s.type){case 0:p.moveTo(s.p[0],s.p[1]);break;case 1:p.lineTo(s.p[0],s.p[1]);break;case 2:p.quadTo(s.p[0],s.p[1],s.p[2],s.p[3]);break;case 3:p.curveTo(s.p[0],s.p[1],s.p[2],s.p[3],s.p[4],s.p[5]);break;case 4:p.closePath();break;}}return p;}
		@Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);scale=Math.min((getWidth()-40.0)/Math.max(1,shape.width),(getHeight()-40.0)/Math.max(1,shape.height));ox=20;oy=20;g.translate(ox,oy);g.scale(scale,scale);g.setColor(new Color(230,230,230));g.fillRect(0,0,shape.width,shape.height);g.setColor(Color.DARK_GRAY);g.setStroke(new BasicStroke((float)(2/scale)));g.draw(path());for(Segment s:segments)for(int j=0;j<s.p.length;j+=2){g.setColor(j+2<s.p.length?Color.BLUE:Color.RED);double radius=4/scale;g.fill(new java.awt.geom.Ellipse2D.Double(s.p[j]-radius,s.p[j+1]-radius,radius*2,radius*2));}g.dispose();}
	}
}
