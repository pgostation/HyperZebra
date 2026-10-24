import java.awt.image.BufferedImage;
import java.util.ArrayList;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;


public class OCardBase extends OObject {
	OStack stack;
	BufferedImage pict;
	Boolean showPict = true;
	MyLabel label;
	OPicture picture;
	String bitmapName;
	
	boolean changed;

	//pictの上に重ねる追加のペイントレイヤー(下から順)。無ければ空。PaintLayer参照
	ArrayList<PaintLayer> layers = new ArrayList<PaintLayer>();
	//レイヤーの構成・属性が変わるたびに増える(表示側のキャッシュの鍵)
	int layerVersion;
	private BufferedImage compositeCache;
	private int compositeVersion = -1;
	private BufferedImage compositeBase;

	//レイヤーの構成・属性・画素が変わったら呼ぶ(平らにした絵のキャッシュを捨てる)
	void invalidateComposite(){
		layerVersion++;
		compositeCache = null;
	}

	//pictと(見えている)追加レイヤーを平らにした絵。追加レイヤーが無ければpictそのもの。
	//ブラウズモードの表示・サムネイル・HyperCardへの書き出しに使う。
	BufferedImage composite(){
		if(layers==null || layers.isEmpty()) return pict;
		if(compositeCache!=null && compositeVersion==layerVersion && compositeBase==pict) return compositeCache;
		int w = stack!=null ? stack.width : (pict!=null ? pict.getWidth() : 1);
		int h = stack!=null ? stack.height : (pict!=null ? pict.getHeight() : 1);
		for(PaintLayer l : layers) if(l.image!=null){ w = Math.max(w, l.image.getWidth()); h = Math.max(h, l.image.getHeight()); }
		compositeCache = PaintLayers.flatten(pict, layers, w, h);
		compositeVersion = layerVersion;
		compositeBase = pict;
		return compositeCache;
	}

	//追加レイヤーがあるか
	boolean hasLayers(){
		return layers!=null && !layers.isEmpty();
	}

	//このカード/バックグラウンドのレイヤーの画像を(まだなら)ファイルから読む
	void loadLayerImages(){
		if(layers==null || stack==null || stack.file==null) return;
		for(PaintLayer l : layers){
			if(l.image!=null || l.fileName==null) continue;
			java.io.File f = new java.io.File(stack.file.getParent(), l.fileName);
			if(!f.isFile()) continue;
			try{
				BufferedImage img = StackProtection.readImage(f);
				if(img!=null) l.image = PaintUndo.copyInto(null, img);
			}catch(java.io.IOException e){
				e.printStackTrace();
			}
		}
		invalidateComposite();
	}
	
	//ボタン、フィールド情報
	ArrayList<OObject> partsList;
	ArrayList<OButton> btnList;
	ArrayList<OField> fldList;
	ArrayList<OShape> shapeList = new ArrayList<OShape>();
	ArrayList<JScrollPane> paneList = new ArrayList<JScrollPane>();

	@Override
	public void clean(){
		//stack.mainPane.removeAll(); //すでに新しいスタックを読み込んでいるのでremoveしてはいけない
		stack = null;
		pict = null;
		label = null;
		picture = null;
		bitmapName = null;
		layers = new ArrayList<PaintLayer>();
		compositeCache = null;
		partsList = null;
		btnList = null;
		fldList = null;
		shapeList = null;
		paneList = null;
	}
	
	public OCardBase(OStack st){
		stack = st;
	}

	private boolean movingPanel;
	/** 一つの部品の移動量を、同じ Panel の残りの部品へ適用する。 */
	void movePanelPeers(OObject anchor, int dx, int dy){
		if(movingPanel || (dx==0 && dy==0) || anchor.panelId==0) return;
		movingPanel = true;
		try{
			ArrayList<OObject> members=new ArrayList<OObject>();
			if(partsList!=null) members.addAll(partsList);
			if(shapeList!=null) members.addAll(shapeList);
			//部品ごとにEDTへ同期で渡すと、数千部品のPanel(スクロールするマップ)では1回の移動に数十msかかる
			PCARD.batchOnEDT(() -> {
				for(OObject part : members){
					if(part==anchor || part.panelId!=anchor.panelId) continue;
					if(part instanceof OButton) ((OButton)part).setTopLeft(part.left+dx, part.top+dy);
					else if(part instanceof OField) ((OField)part).setTopLeft(part.left+dx, part.top+dy);
					else if(part instanceof OShape) ((OShape)part).setTopLeft(part.left+dx, part.top+dy);
				}
			});
		}finally{ movingPanel = false; }
	}

	/** The panels of this card or background, in the order they were made (panel 1 first). */
	ArrayList<OPanel> panelList = new ArrayList<OPanel>();

	/** panel "name", panel N (its number) or, byId, panel id N; null when there is none. */
	OPanel getPanel(String ref, boolean byId){
		if(ref==null || panelList==null) return null;
		String r = ref.trim();
		if(byId){
			try{ return panelById(Integer.parseInt(r)); }catch(NumberFormatException ignored){ return null; }
		}
		if(r.matches("[0-9]+")){
			try{ int n = Integer.parseInt(r); return n>=1 && n<=panelList.size() ? panelList.get(n-1) : null; }
			catch(NumberFormatException ignored){ return null; }
		}
		for(OPanel p : panelList) if(p.name.equalsIgnoreCase(r)) return p;
		return null;
	}
	OPanel getPanel(String name){ return getPanel(name, false); }
	/** panel "1" in quotes: a name, never a number. */
	OPanel panelNamed(String name){
		if(name==null || panelList==null) return null;
		for(OPanel p : panelList) if(p.name.equalsIgnoreCase(name.trim())) return p;
		return null;
	}
	OPanel panelById(int id){
		if(id==0 || panelList==null) return null;
		for(OPanel p : panelList) if(p.id==id) return p;
		return null;
	}
	/** The panel a button, field or shape passes its messages to (null when it is in none). */
	OPanel panelOf(OObject part){
		if(part==null || part instanceof OPanel || part.panelId==0 || part.parent!=this) return null;
		return panelById(part.panelId);
	}
	/** The id the next new button or field here gets, as HyperCard's card and background blocks keep it (the next part id): one
	 *  more than the highest id given out, never the id of a part deleted meanwhile (HC J-2.3 measured, probe NAT New Part Id;
	 *  HyperZebra used to take the lowest free id - コリントゲームミニ's pasted pins got id 1, 2…). 0 while unknown (a stack
	 *  saved before it was kept): the highest id + 1 then. */
	int nextPartId;
	/** The id for a button or field made here now (see {@link #nextPartId}). */
	int newPartId(){
		int id = Math.max(1, nextPartId);
		if(partsList!=null) for(OObject p : partsList) id = Math.max(id, p.id+1);
		if(id>32767){ id = 1; while(id<32767 && GetPartbyId(id)!=null) id++; } //HyperCard's part ids are 16-bit
		nextPartId = id+1;
		return id;
	}
	/** The next part id for the stack XML: written only when it says more than the parts do (a part with a higher id was deleted). */
	void writeNextPartId(javax.xml.stream.XMLStreamWriter writer) throws javax.xml.stream.XMLStreamException {
		int max = 0;
		if(partsList!=null) for(OObject p : partsList) max = Math.max(max, p.id);
		if(nextPartId<=max+1) return;
		writer.writeStartElement("nextPartId");
		writer.writeCharacters(Integer.toString(nextPartId));
		writer.writeEndElement();
		writer.writeCharacters("\n\t\t");
	}

	/** The highest panel id given out here since the card or background was read (a deleted panel's id is not given again). */
	private int lastPanelId;
	/** A new panel at the end of the list, with an id no panel here has had (one more than the highest). */
	OPanel newPanel(String name){
		int id = lastPanelId+1;
		for(OPanel p : panelList) id = Math.max(id, p.id+1);
		lastPanelId = id;
		OPanel p = new OPanel(this, id, name);
		panelList.add(p);
		changed = true;
		return p;
	}
	/** Deletes a panel: its members stay on the card or background, out of any panel (shown again if it was hidden). */
	void deletePanel(OPanel panel){
		if(panel==null || !panelList.contains(panel)) return;
		ArrayList<OObject> members = panel.members();
		panelList.remove(panel);
		for(OObject part : members) part.setPanel(null);
		changed = true;
	}
	/** The panel elements, after the parts and shapes of a card or background. */
	void writePanels(javax.xml.stream.XMLStreamWriter writer) throws javax.xml.stream.XMLStreamException {
		for(OPanel p : panelList){
			p.writeXML(writer);
			writer.writeCharacters("\n\t\t");
		}
	}
	void readPanel(javax.xml.stream.XMLStreamReader reader) throws javax.xml.stream.XMLStreamException {
		OPanel p = OPanel.readXML(reader, this);
		if(p.id<=0 || panelById(p.id)!=null){
			int id = 1;
			for(OPanel q : panelList) id = Math.max(id, q.id+1);
			p.id = id;
		}
		panelList.add(p);
	}
	OShape getShape(String ref, boolean byId){
		if(shapeList==null)return null;
		if(byId){
			try{int id=Integer.parseInt(ref);for(OShape shape:shapeList)if(shape.id==id)return shape;}catch(NumberFormatException ignored){}
			return null;
		}
		try{int number=Integer.parseInt(ref);return number>=1&&number<=shapeList.size()?shapeList.get(number-1):null;}catch(NumberFormatException ignored){}
		for(OShape shape:shapeList)if(shape.name.equalsIgnoreCase(ref))return shape;
		return null;
	}

	//部品は手前のものから順にmainPaneの末尾へ足していく(先に足したものほど手前)。
	//シェイプもボタン・フィールドと同じ重なり順に入る(stacking)
	static void buidParts(OCardBase cdbase){
		ArrayList<OObject> order = cdbase.stacking();
		for(int i=order.size()-1; i>=0; i--){
			OObject obj = order.get(i);
			if(obj instanceof OShape){
				((OShape)obj).build();
			}
			else if(obj.getClass()==OButton.class){
				OButton.buildOButton((OButton)obj);
			}
			else{
				OField.buildOField((OField)obj);
			}
		}
	}

	/**
	 * partsList as it was when the shapes' layers were last worked out. A shape's layer (OShape.layer) is the number of parts beneath
	 * it; a part deleted since then leaves the count, so a shape keeps its place among the parts that remain. null until first used
	 * (a card that was just read: its layers are taken as they are).
	 */
	ArrayList<OObject> layeredParts;

	/** Brings the shapes' layers up to date with partsList (see layeredParts). */
	void normalizeLayers(){
		if(partsList==null || shapeList==null) return;
		if(layeredParts!=null && !layeredParts.equals(partsList)){
			for(OShape shape : shapeList){
				int below = Math.min(Math.max(0, shape.layer), layeredParts.size()), kept = 0;
				for(int i=0; i<below; i++) if(partsList.contains(layeredParts.get(i))) kept++;
				shape.layer = kept;
			}
		}
		for(OShape shape : shapeList) shape.layer = Math.min(Math.max(0, shape.layer), partsList.size());
		layeredParts = new ArrayList<OObject>(partsList);
	}

	/** Buttons, fields and shapes from back to front (shapes of the same layer in shapeList order). */
	ArrayList<OObject> stacking(){
		ArrayList<OObject> order = new ArrayList<OObject>();
		if(partsList==null) return order;
		normalizeLayers();
		int n = partsList.size();
		for(int slot=0; slot<=n; slot++){
			if(shapeList!=null) for(OShape shape : shapeList) if(shape.layer==slot) order.add(shape);
			if(slot<n) order.add(partsList.get(slot));
		}
		return order;
	}

	/** Takes a back-to-front order of this card's (or background's) parts and shapes as the new stacking. */
	void applyStacking(java.util.List<OObject> order){
		ArrayList<OObject> parts = new ArrayList<OObject>();
		ArrayList<OShape> shapes = new ArrayList<OShape>();
		for(OObject o : order){
			if(o instanceof OShape){ ((OShape)o).layer = parts.size(); shapes.add((OShape)o); }
			else parts.add(o);
		}
		partsList.clear(); partsList.addAll(parts);
		if(shapeList!=null){ shapeList.clear(); shapeList.addAll(shapes); }
		if(btnList!=null){
			ArrayList<OButton> b = new ArrayList<OButton>();
			for(OObject o : partsList) if(o instanceof OButton && btnList.contains(o)) b.add((OButton)o);
			for(OButton o : btnList) if(!b.contains(o)) b.add(o);
			btnList.clear(); btnList.addAll(b);
		}
		if(fldList!=null){
			ArrayList<OField> f = new ArrayList<OField>();
			for(OObject o : partsList) if(o instanceof OField && fldList.contains(o)) f.add((OField)o);
			for(OField o : fldList) if(!f.contains(o)) f.add(o);
			fldList.clear(); fldList.addAll(f);
		}
		layeredParts = new ArrayList<OObject>(partsList);
		changed = true;
	}

	/** Bring Closer / Send Farther: a button, field or shape swaps with the next one in the whole stacking. */
	void stepInStack(OObject item, boolean closer){
		ArrayList<OObject> order = stacking();
		int from = order.indexOf(item), to = from+(closer ? 1 : -1);
		if(from<0 || to<0 || to>=order.size()) return;
		java.util.Collections.swap(order, from, to);
		applyStacking(order);
	}
	
	public OButton GetBtnbyId(int btnId) {
		for(int i=0; i<btnList.size(); i++){
			if(btnId==btnList.get(i).id)
				return btnList.get(i);
		}
		return null;
	}
	
	public OButton GetBtnbyNum(int number) {
		if(number-1>=0 && number-1<btnList.size())
		{
			return btnList.get(number-1);
		}
		return null;
	}
	
	public OButton GetBtn(String name) {
		for(int i=0; i<btnList.size(); i++){
			if(0==btnList.get(i).name.compareToIgnoreCase(name))
				return btnList.get(i);
		}
		return null;
	}

	public OField GetFldbyId(int fldId) {
		for(int i=0; i<fldList.size(); i++){
			if(fldId==fldList.get(i).id)
				return fldList.get(i);
		}
		return null;
	}
	
	public OField GetFldbyNum(int number) {
		if(number-1>=0 && number-1<fldList.size())
		{
			return fldList.get(number-1);
		}
		return null;
	}
	
	public OField GetFld(String name) {
		for(int i=0; i<fldList.size(); i++){
			if(0==fldList.get(i).name.compareToIgnoreCase(name))
				return fldList.get(i);
		}
		return null;
	}

	public OObject GetPartbyId(int pid) {
		for(int i=0; i<partsList.size(); i++){
			if(pid==partsList.get(i).id)
				return partsList.get(i);
		}
		return null;
	}

	
	OBackground getBg(){
		OBackground bg = null;
		if(this.objectType.equals("background")) bg = (OBackground)this;
		else if(this.objectType.equals("card")) {
			OCard cd = (OCard)this;
			if(cd.bg != null) {bg = cd.bg;}
			else { bg = cd.stack.GetBackgroundbyId(cd.bgid); }
		}
		return bg;
	}
	
	public OButton GetBgBtnbyId(int btnId) {
		OBackground bg = getBg();
		if(bg==null) return null;
		for(int i=0; i<bg.btnList.size(); i++){
			if(btnId==bg.btnList.get(i).id)
				return bg.btnList.get(i);
		}
		return null;
	}
	
	public OButton GetBgBtnbyNum(int number) {
		OBackground bg = getBg();
		if(bg==null) return null;
		if(number-1>=0 && number-1<bg.btnList.size())
		{
			return bg.btnList.get(number-1);
		}
		return null;
	}
	
	public OButton GetBgBtn(String name) {
		OBackground bg = getBg();
		if(bg==null) return null;
		for(int i=0; i<bg.btnList.size(); i++){
			if(0==bg.btnList.get(i).name.compareToIgnoreCase(name))
				return bg.btnList.get(i);
		}
		return null;
	}

	public OField GetBgFldbyId(int fldId) {
		OBackground bg = getBg();
		if(bg==null) return null;
		for(int i=0; i<bg.fldList.size(); i++){
			if(fldId==bg.fldList.get(i).id)
				return bg.fldList.get(i);
		}
		return null;
	}
	
	public OField GetBgFldbyNum(int number) {
		OBackground bg = getBg();
		if(bg==null) return null;
		if(number-1>=0 && number-1<bg.fldList.size())
		{
			return bg.fldList.get(number-1);
		}
		return null;
	}
	
	public OField GetBgFld(String name) {
		OBackground bg = getBg();
		if(bg==null) return null;
		for(int i=0; i<bg.fldList.size(); i++){
			if(0==bg.fldList.get(i).name.compareToIgnoreCase(name))
				return bg.fldList.get(i);
		}
		return null;
	}
	
	public int GetNumberof(OButton obj) {
		for(int number=0; number < btnList.size(); number++)
		{
			if(btnList.get(number).id==obj.id){
				return number+1;
			}
		}
		return 0;
	}
	
	public int GetNumberof(OField obj) {
		for(int number=0; number < fldList.size(); number++)
		{
			if(fldList.get(number).id==obj.id){
				return number+1;
			}
		}
		return 0;
	}
	
	//set the partNumber of <part> to N: 部品の重なり順(partsList)を変える。
	//ボタン/フィールドそれぞれの番号(btnList/fldList)もその順に合わせる。
	//順番が変わったときだけ true
	public boolean setPartNumber(OObject part, int number) {
		if(partsList==null || !partsList.contains(part)) return false;
		int count = partsList.size();
		if(number<1) number = 1;
		if(number>count) number = count;
		if(partsList.indexOf(part)==number-1) return false;
		partsList.remove(part);
		partsList.add(number-1, part);
		if(btnList!=null){
			ArrayList<OButton> b = new ArrayList<OButton>();
			for(OObject o : partsList) if(o instanceof OButton && btnList.contains(o)) b.add((OButton)o);
			for(OButton o : btnList) if(!b.contains(o)) b.add(o);
			btnList.clear(); btnList.addAll(b);
		}
		if(fldList!=null){
			ArrayList<OField> f = new ArrayList<OField>();
			for(OObject o : partsList) if(o instanceof OField && fldList.contains(o)) f.add((OField)o);
			for(OField o : fldList) if(!f.contains(o)) f.add(o);
			fldList.clear(); fldList.addAll(f);
		}
		changed = true;
		return true;
	}

	/**
	 * Puts the shown components of this card's (or background's) parts in the stacking() order without taking them off mainPane.
	 * Rebuilding the card for set the partNumber took the pressed button's component out of the window when a mouseDown handler
	 * did it (Rescue George UR brings the clicked piece forward), so the release went to the frame and mouseUp never came.
	 * false when the components do not match the parts; the caller then rebuilds the card.
	 */
	boolean restackComponents(){
		PCARD pc = PCARD.pc;
		if(pc==null || pc.mainPane==null || pc.stack==null || pc.stack.curCard==null) return true;
		OCard cur = pc.stack.curCard;
		if(this!=cur && this!=cur.bg) return !(id==(this instanceof OBackground ? cur.bgid : cur.id)); //見えていないカード
		boolean[] done = {false};
		Runnable run = () -> done[0] = restackComponentsOnEDT(pc.mainPane);
		if(SwingUtilities.isEventDispatchThread()) run.run();
		else{
			try{ SwingUtilities.invokeAndWait(run); }
			catch(Exception e){ return false; }
		}
		return done[0];
	}

	private boolean restackComponentsOnEDT(java.awt.Container pane){
		ArrayList<java.awt.Component> front = new ArrayList<java.awt.Component>();
		ArrayList<OObject> order = stacking();
		for(int i=order.size()-1; i>=0; i--){
			OObject o = order.get(i);
			ArrayList<java.awt.Component> comps = new ArrayList<java.awt.Component>();
			if(o instanceof OShape){ if(((OShape)o).view!=null) comps.add(((OShape)o).view); }
			else if(o instanceof OButton){
				OButton b = (OButton)o;
				if(b.getComponent()!=null) comps.add(b.getComponent());
				if(b.popupNameLabel!=null) comps.add(b.popupNameLabel);
			}
			else if(o instanceof OField){ if(((OField)o).getComponent()!=null) comps.add(((OField)o).getComponent()); }
			for(java.awt.Component c : comps) if(c.getParent()!=pane) return false; //Panelの中など
			comps.sort(java.util.Comparator.comparingInt(pane::getComponentZOrder));
			front.addAll(comps);
		}
		java.util.HashSet<java.awt.Component> ours = new java.util.HashSet<java.awt.Component>(front);
		if(ours.size()!=front.size()) return false;
		//ほかの部品(背景の部品、カードのラベルなど)の位置はそのままで、この部品たちの占める位置に新しい順で入れる
		ArrayList<java.awt.Component> desired = new ArrayList<java.awt.Component>();
		int k = 0;
		for(java.awt.Component c : pane.getComponents()) desired.add(ours.contains(c) ? front.get(k++) : c);
		if(k!=front.size()) return false;
		//同じ親の中での setComponentZOrder は removeNotify しないので、押されているボタンもそのまま
		for(int i=0; i<desired.size(); i++){
			if(pane.getComponentZOrder(desired.get(i))!=i) pane.setComponentZOrder(desired.get(i), i);
		}
		if(!PCARD.lockedScreen) pane.repaint();
		return true;
	}

	public int GetNumberofParts(OObject obj) {
		for(int number=0; number < partsList.size(); number++)
		{
			if(partsList.get(number).id==obj.id &&
				partsList.get(number).objectType.equals(obj.objectType)){
				return number+1;
			}
		}
		return 0;
	}
	
	public void removeData(){
		//mainPaneの子コンポーネント除去はEDT上で行わないと、描画中のAWTスレッドと
		//競合してArrayIndexOutOfBoundsExceptionが起きることがある。
		//removeDataはスクリプト実行スレッドとEDTの両方から呼ばれ得るため、
		//すでにEDT上ならそのまま実行し、そうでなければinvokeAndWaitで同期的に実行する
		//(このあとの処理が「除去済み」を前提にしているためinvokeLaterでは不可)
		if(SwingUtilities.isEventDispatchThread()){
			PCARDFrame.pc.mainPane.removeAll();
		}else{
			try{
				SwingUtilities.invokeAndWait(new Runnable(){
					public void run(){
						PCARDFrame.pc.mainPane.removeAll();
					}
				});
			}catch(Exception e){
				PCARDFrame.pc.mainPane.removeAll();
			}
		}
		for(int i=0; i<btnList.size(); i++) {
			if(btnList.get(i).btn!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).btn);
				btnList.get(i).btn.btnData = null;
				btnList.get(i).btn=null;
			}
			if(btnList.get(i).chkbox!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).chkbox);
				btnList.get(i).chkbox.btnData = null;
				btnList.get(i).chkbox=null;
			}
			if(btnList.get(i).radio!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).radio);
				btnList.get(i).radio.btnData = null;
				btnList.get(i).radio=null;
			}
			if(btnList.get(i).popup!=null) {
				//PCARD.pc.mainPane.remove(btnList.get(i).popup);
				btnList.get(i).popup.btnData = null;
				btnList.get(i).popup=null;
			}
			//btnList.get(i).card = null;
			//btnList.get(i).bkgd = null;
		}
		for(int i=0; i<fldList.size(); i++) {
			if(fldList.get(i).fld!=null){
				//PCARD.pc.mainPane.remove(fldList.get(i).fld);
				fldList.get(i).fld.fldData = null;
				fldList.get(i).fld=null;
			}
			//fldList.get(i).card = null;
			//fldList.get(i).bkgd = null;
		}
		//(removeAll済みなので、参照だけ捨てる。以前はremove(i)しながら回して
		//半分しか消えていなかった)
		paneList.clear();
		if(label!=null) {
			//PCARD.pc.mainPane.remove(label);
			label.cd = null;
			label=null;
			pict=null;
			picture=null;
		}
		for(int i=0;i<OButton.btnGroup.length; i++){
			OButton.btnGroup[i] = null;
		}
	}
}
