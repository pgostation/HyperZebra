import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;



public class Rsrc {
	//表示用の同じアイコンをカード移動やスクリプトで何度も読むため、
	//ファイルごとにデコード済み画像を少量保持する。編集用getImageは常に実ファイルを読む。
	private static final long DISPLAY_IMAGE_CACHE_BYTES = 8L * 1024 * 1024;
	private static final int DISPLAY_IMAGE_CACHE_ENTRIES = 256;
	private static final LinkedHashMap<String, DisplayImageEntry> displayImageCache =
		new LinkedHashMap<String, DisplayImageEntry>(32, 0.75f, true);
	private static long displayImageCacheBytes;
	private static class DisplayImageEntry {
		final BufferedImage image;
		final long modified, length, bytes;
		DisplayImageEntry(BufferedImage image, long modified, long length){
			this.image=image;
			this.modified=modified;
			this.length=length;
			bytes=(long)image.getWidth()*image.getHeight()*4;
		}
	}
	//ファイルの種類・更新日時・大きさを1回で読む(カード移動のたびにアイコンごとに確かめるので、
	//isFile/lastModified/length の3回のシステムコールをまとめる)。無ければnull
	private static java.nio.file.attribute.BasicFileAttributes fileAttributes(File file){
		try{
			java.nio.file.attribute.BasicFileAttributes a=java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
			return a.isRegularFile() ? a : null;
		}catch(IOException | RuntimeException e){
			return null;
		}
	}
	private static synchronized void clearDisplayImageCache(){
		displayImageCache.clear();
		displayImageCacheBytes=0;
	}
	private BufferedImage cachedDisplayImage(int id, String type){
		return cachedDisplayImageFile(getFilePathAll(id, type), type);
	}
	static BufferedImage cachedDisplayImageFile(String path, String type){
		if(path==null) return null;
		File file=new File(path);
		String key=type+'\0'+path;
		synchronized(Rsrc.class){
			DisplayImageEntry cached=displayImageCache.get(key);
			java.nio.file.attribute.BasicFileAttributes a=cached!=null ? fileAttributes(file) : null;
			if(cached!=null && a!=null && cached.modified==a.lastModifiedTime().toMillis() && cached.length==a.size()) return cached.image;
			if(cached!=null){displayImageCacheBytes-=cached.bytes;displayImageCache.remove(key);}
		}
		java.nio.file.attribute.BasicFileAttributes attributes=fileAttributes(file);
		if(attributes==null) return null;
		BufferedImage image=loadImage(path, type);
		if(image==null) return null;
		DisplayImageEntry entry=new DisplayImageEntry(image,attributes.lastModifiedTime().toMillis(),attributes.size());
		if(entry.bytes<=DISPLAY_IMAGE_CACHE_BYTES) synchronized(Rsrc.class){
			DisplayImageEntry previous=displayImageCache.put(key,entry);
			if(previous!=null) displayImageCacheBytes-=previous.bytes;
			displayImageCacheBytes+=entry.bytes;
			while(displayImageCacheBytes>DISPLAY_IMAGE_CACHE_BYTES || displayImageCache.size()>DISPLAY_IMAGE_CACHE_ENTRIES){
				Map.Entry<String,DisplayImageEntry> oldest=displayImageCache.entrySet().iterator().next();
				displayImageCacheBytes-=oldest.getValue().bytes;
				displayImageCache.remove(oldest.getKey());
			}
		}
		return image;
	}
	//in the order of the HyperCard file's resource map (types as listed, each type's references as listed), kept through
	//_stack.xml: Get1IndResource/GetIndResource of an external count in that order, as on the Mac (UxMovie's
	//UxGetMyMovieList lists its 'myMV' movies so; sorted by ID it began with another movie). New resources come last.
	HashMap<String,rsrcClass> rsrcIdMap = new LinkedHashMap<String,rsrcClass>();
	private HashMap<String,rsrcClass> rsrcNameMap = new HashMap<String,rsrcClass>();
	private HashMap<String,xcmdClass> xcmdNameMap = new LinkedHashMap<String,xcmdClass>();
	ArrayList<addcolorClass> addcolorList  = new ArrayList<addcolorClass>();
	ArrayList<PlteClass> plteList  = new ArrayList<PlteClass>();
	OStack ownerstack;

	//リソースエディタの作業用コピー(workingCopy参照)だけが持つ、編集中の
	//ファイルを置く一時フォルダ。本物のRsrcではnull。作業用コピーへの変更は
	//スタックのファイル群には触れず、commitで初めてスタックに書き戻される。
	File stagingDir;
	//作業用コピーに未保存の変更があるか(本物のRsrcはownerstack.changedを使う)
	boolean dirty;
	//作業用コピーでXCMD/XFCNの登録を変えたか(変えていなければ保存で本物の登録をそのまま残す:
	//編集中に68K XCMDがスタックへ書いた外部コマンドを古い一覧で上書きしないため)
	boolean externalsEdited;
	
	
	public Rsrc(OStack owner){
		this.ownerstack = owner;
	}

	//変更があったことを記録する。作業用コピーなら自分のdirty、本物ならスタックの保存フラグ
	void markChanged(){
		clearDisplayImageCache();
		if(stagingDir!=null) dirty = true;
		else ownerstack.changed = true;
	}

	//このスタック自身のリソースファイルが置かれているフォルダ(スタックがファイルを持たなければnull)
	File ownDirectory(){
		if(ownerstack==null || ownerstack.file==null) return null;
		return new File(ownerstack.file.getParent());
	}

	//このスタック自身のリソースをファイル名で書き込む先。作業用コピーでは
	//一時フォルダ、本物ではスタックのフォルダ。エディタ類はスタックのフォルダを
	//直接組み立てず、必ずここを通す。
	File fileToWrite(String filename){
		if(stagingDir!=null) return stagedFile(filename);
		File dir = ownDirectory();
		return dir==null ? new File(filename) : new File(dir, filename);
	}

	//スタックの外のファイル(ランチャーにドロップした画像など。スタックがファイルを持たないときに
	//絶対パスで登録される)か。編集結果は保存でそのファイル自身に書き戻される。
	static boolean isExternalFile(String filename){
		return filename!=null && new File(filename).isAbsolute();
	}

	//一時フォルダ内での置き場所。絶対パスで登録されたファイルは、フォルダ名を畳み込んだ名前で
	//一時フォルダ直下に置く(new File(stagingDir, "/abs/path")では存在しないフォルダになってしまう)
	private File stagedFile(String filename){
		if(!isExternalFile(filename)) return new File(stagingDir, filename);
		File f = new File(filename);
		String parent = f.getParent()==null ? "" : f.getParent();
		return new File(stagingDir, "ext_"+Integer.toHexString(parent.hashCode())+"_"+f.getName());
	}

	//このスタック自身のリソースの実ファイルパス。作業用コピーでは、編集済みの
	//ファイルが一時フォルダにあればそちらを優先し、なければスタックのファイル。
	private String ownPath(rsrcClass r){
		return ownFilePath(r.filename);
	}

	private String ownFilePath(String filename){
		if(stagingDir!=null){
			File staged = stagedFile(filename);
			if(staged.exists()) return staged.getPath();
		}
		File dir = ownDirectory();
		return dir==null || isExternalFile(filename) ? filename : dir.getPath()+File.separatorChar+filename;
	}

	//リソースエディタ用の作業用コピー。登録情報は複製し(共有しない)、ファイルは
	//stagingDirに書かれたものが優先される。XCMD/XFCNの登録も複製する。AddColor等、
	//エディタが触らないものは共有のまま。
	Rsrc workingCopy(File stagingDir){
		Rsrc work = new Rsrc(ownerstack);
		work.stagingDir = stagingDir;
		for(rsrcClass r : rsrcIdMap.values()){
			work.rsrcIdMap.put(r.type+r.id, work.copyOf(r));
		}
		for(java.util.Map.Entry<String,rsrcClass> e : rsrcNameMap.entrySet()){
			rsrcClass r = e.getValue();
			rsrcClass copy = work.rsrcIdMap.get(r.type+r.id);
			//名前表からしか辿れない(IDが重複して上書きされた)登録も残しておく
			work.rsrcNameMap.put(e.getKey(), copy!=null && copy.filename.equals(r.filename) ? copy : work.copyOf(r));
		}
		work.xcmdNameMap = new LinkedHashMap<String,xcmdClass>();
		for(Map.Entry<String,xcmdClass> e : xcmdNameMap.entrySet()) work.xcmdNameMap.put(e.getKey(), e.getValue().copy());
		work.addcolorList = addcolorList;
		work.plteList = plteList;
		return work;
	}

	private rsrcClass copyOf(rsrcClass r){
		rsrcClass copy = new rsrcClass(r.id, r.type, r.name, r.filename,
			Integer.toString(r.hotsporleft), Integer.toString(r.hotsportop), r.optionInfo);
		copy.attributes = r.attributes;
		return copy;
	}

	//作業用コピーの内容をこの(本物の)Rsrcとスタックのフォルダへ書き戻す。
	//消えた登録のファイルはゴミ箱へ、一時フォルダのファイルはスタックのフォルダへ移り、
	//登録情報はコピーの内容に置き換わる。
	void commit(Rsrc work) throws IOException {
		File dir = ownDirectory();
		//消えた(または別のファイル名になった)登録の古いファイルをゴミ箱へ
		for(rsrcClass old : new ArrayList<rsrcClass>(rsrcIdMap.values())){
			rsrcClass now = work.rsrcIdMap.get(old.type+old.id);
			if(now!=null && now.filename.equals(old.filename)) continue;
			if(dir==null || isExternalFile(old.filename)) continue; //スタックの外のファイルはユーザのもの: 消さない
			File oldFile = new File(dir, old.filename);
			//同じファイルを別の登録(IDだけ変えたもの等)がまだ使っていればそのまま
			if(oldFile.exists() && !work.isFileUsed(old.filename)) AppPaths.moveResourceToTrash(oldFile);
		}
		//編集済みのファイルをスタックのフォルダへ(スタックの外のファイルはそのファイル自身へ書き戻す)
		if(work.stagingDir!=null && work.stagingDir.isDirectory()){
			for(rsrcClass r : work.rsrcIdMap.values()){
				File staged = work.stagedFile(r.filename);
				if(!staged.exists()) continue;
				File target;
				if(isExternalFile(r.filename)) target = new File(r.filename);
				else if(dir!=null) target = new File(dir, r.filename);
				else continue;
				java.nio.file.Files.move(staged.toPath(), target.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		//登録情報を置き換える
		rsrcIdMap.clear();
		rsrcNameMap.clear();
		for(rsrcClass r : work.rsrcIdMap.values()){
			rsrcIdMap.put(r.type+r.id, copyOf(r));
		}
		for(java.util.Map.Entry<String,rsrcClass> e : work.rsrcNameMap.entrySet()){
			rsrcClass r = e.getValue();
			rsrcClass mine = rsrcIdMap.get(r.type+r.id);
			rsrcNameMap.put(e.getKey(), mine!=null && mine.filename.equals(r.filename) ? mine : copyOf(r));
		}
		if(work.externalsEdited) commitExternals(work, dir);
		ownerstack.changed = true;
		work.dirty = false;
		clearDisplayImageCache();
	}

	//XCMD/XFCNの登録を作業用コピーの内容にする(消えたもののファイルはゴミ箱へ、編集分はスタックのフォルダへ)
	private void commitExternals(Rsrc work, File dir) throws IOException {
		for(xcmdClass old : new ArrayList<xcmdClass>(xcmdNameMap.values())){
			if(dir==null || isExternalFile(old.filename) || work.isFileUsed(old.filename)) continue;
			File oldFile = new File(dir, old.filename);
			if(oldFile.exists()) AppPaths.moveResourceToTrash(oldFile);
		}
		if(work.stagingDir!=null && work.stagingDir.isDirectory()){
			for(xcmdClass x : work.xcmdNameMap.values()){
				File staged = work.stagedFile(x.filename);
				if(!staged.exists()) continue;
				File target;
				if(isExternalFile(x.filename)) target = new File(x.filename);
				else if(dir!=null) target = new File(dir, x.filename);
				else continue;
				java.nio.file.Files.move(staged.toPath(), target.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		}
		LinkedHashMap<String,xcmdClass> map = new LinkedHashMap<String,xcmdClass>();
		for(Map.Entry<String,xcmdClass> e : work.xcmdNameMap.entrySet()) map.put(e.getKey(), e.getValue().copy());
		xcmdNameMap = map; //a new map: the script thread looking an external up never sees it half filled
		xcmdGeneration++;
		work.externalsEdited = false;
	}

	private boolean isFileUsed(String filename){
		for(rsrcClass r : rsrcIdMap.values()) if(r.filename.equals(filename)) return true;
		for(xcmdClass x : xcmdNameMap.values()) if(x.filename.equals(filename)) return true;
		return false;
	}

	//エディタが書き込むファイル名。ほかのリソースが同じ名前のファイルを使っていれば(リソース情報でIDを
	//変えたリソースは元のファイル名のまま)、そのリソースを上書きしないよう番号を足した名前にする
	String freeFileName(String preferred, rsrcClass self){
		int dot = preferred.lastIndexOf('.');
		String base = dot>0 ? preferred.substring(0, dot) : preferred;
		String ext = dot>0 ? preferred.substring(dot) : "";
		String name = preferred;
		for(int n=2; isFileUsedByOther(name, self); n++) name = base+"-"+n+ext;
		return name;
	}

	private boolean isFileUsedByOther(String filename, rsrcClass self){
		for(rsrcClass r : rsrcIdMap.values()) if(r!=self && filename.equals(r.filename)) return true;
		for(xcmdClass x : xcmdNameMap.values()) if(filename.equals(x.filename)) return true;
		return false;
	}

	//IDや名前の変更(リソース情報ダイアログ、エディタのID欄)。両方の表を付け直す。
	//資源マップでの位置はそのまま(Mac の SetResInfo と同じ: 番号を変えても Get1IndResource の順は変わらない)
	void setIdAndName(rsrcClass r, int id, String name){
		int place = rsrcIdMap.get(r.type+r.id)==r ? indexOf(r.type, r.id) : -1;
		if(rsrcIdMap.get(r.type+r.id)==r) rsrcIdMap.remove(r.type+r.id);
		if(rsrcNameMap.get(r.type+r.name.toLowerCase())==r) rsrcNameMap.remove(r.type+r.name.toLowerCase());
		r.id = id;
		r.name = name;
		rsrcIdMap.put(r.type+r.id, r);
		rsrcNameMap.put(r.type+r.name.toLowerCase(), r);
		if(place>=0) moveTo(r.type, r.id, place);
		markChanged();
	}

	/** Resources of a _stack.xml written before the map order was kept (in no particular order): in ID order, as externals
	 *  were shown them then; the external commands too. */
	void sortById(){
		ArrayList<Map.Entry<String,rsrcClass>> entries = new ArrayList<Map.Entry<String,rsrcClass>>(rsrcIdMap.entrySet());
		entries.sort((a, b) -> Integer.compare(a.getValue().id, b.getValue().id));
		LinkedHashMap<String,rsrcClass> ordered = new LinkedHashMap<String,rsrcClass>();
		for(Map.Entry<String,rsrcClass> e : entries) ordered.put(e.getKey(), e.getValue());
		rsrcIdMap = ordered;
		ArrayList<Map.Entry<String,xcmdClass>> xcmds = new ArrayList<Map.Entry<String,xcmdClass>>(xcmdNameMap.entrySet());
		xcmds.sort((a, b) -> Integer.compare(a.getValue().id, b.getValue().id));
		LinkedHashMap<String,xcmdClass> xordered = new LinkedHashMap<String,xcmdClass>();
		for(Map.Entry<String,xcmdClass> e : xcmds) xordered.put(e.getKey(), e.getValue());
		xcmdNameMap = xordered;
	}

	/** Where the resource of {@code type} and {@code id} is in the resource map order (-1 when there is none). */
	int indexOf(String type, int id){
		String key = type+id;
		int i = 0;
		for(String k : rsrcIdMap.keySet()){ if(k.equals(key)) return i; i++; }
		return -1;
	}

	/** Puts the resource of {@code type} and {@code id} at {@code index} of the resource map order (a resource stored
	 *  again in its old place: an external's ChangedResource, a new ID or name keep its place in the map, as on the Mac). */
	void moveTo(String type, int id, int index){
		String key = type+id;
		rsrcClass r = rsrcIdMap.get(key);
		if(r==null || index<0) return;
		java.util.List<Map.Entry<String,rsrcClass>> entries = new ArrayList<Map.Entry<String,rsrcClass>>(rsrcIdMap.entrySet());
		Map.Entry<String,rsrcClass> mine = null;
		for(Map.Entry<String,rsrcClass> e : entries) if(e.getKey().equals(key)){ mine = e; break; }
		entries.remove(mine);
		entries.add(Math.min(index, entries.size()), mine);
		LinkedHashMap<String,rsrcClass> ordered = new LinkedHashMap<String,rsrcClass>();
		for(Map.Entry<String,rsrcClass> e : entries) ordered.put(e.getKey(), e.getValue());
		rsrcIdMap = ordered; //a new map: lookups from other threads never see it emptied
	}

	//Inside Macintoshいわく、'cicn'リソースをアイコン('ICON'リソース)と同じ
	//リソースIDで用意しておくと、メニューマネージャーやダイアログマネージャーは
	//カラーモニタを使っているユーザーには白黒のICONではなくカラーのcicnを
	//表示する。カーソルの'CURS'/'crsr'も同じ関係にある。HyperCard自体は名前で
	//アイコン/カーソルを探すときICON/CURSしか見なかったが、このエミュレータでは
	//cicn/crsrだけを持つリソースも名前で見つけられるように、ICON/CURSを名前で
	//探すときは常にまず同じ名前でcicn/crsrを探し、見つかればそちらを優先する。
	private static String colorCounterpartType(String type){
		if(type.equals("ICON")) return "cicn";
		if(type.equals("CURS")) return "crsr";
		return null;
	}

	private static rsrcClass lookupByName(HashMap<String,rsrcClass> map, String name, String type){
		String colorType = colorCounterpartType(type);
		rsrcClass r = colorType==null ? null : map.get(colorType+name.toLowerCase());
		if(r==null) r = map.get(type+name.toLowerCase());
		return r;
	}

	//このスタック内のリソースを検索
	public int getRsrcId1(String name, String type){
		rsrcClass r = rsrcNameMap.get(type+name.toLowerCase());
		if(r!=null) return r.id;
		return 0;
	}

	public String getFileName1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.filename;
		return null;
	}

	public String getName1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.name;
		return null;
	}
	
	public Point getHotSpot1(int id){
		rsrcClass r = rsrcIdMap.get("CURS"+Integer.toString(id));
		Point p = new Point(r.hotsporleft, r.hotsportop);
		return p;
	}
	
	public rsrcClass getResource1(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		return r;
	}
	
	
	

	//usingしているスタックすべてのリソースを検索
	public int getRsrcIdAll(String name, String type){
		rsrcClass r = getResourceByNameAll(name, type);
		if(r!=null) return r.id;
		return 0;
	}

	/** The id of the 'font' (FONT/NFNT) strike for the font named {@code name} at {@code size}, searching
	 *  this stack and the stacks in use; 0 when none. Old fonts name their size-0 FONT (family*128) after
	 *  the family; newer ones (FreeFighters -NEXT-'s "test2.r") name only a FOND whose association table
	 *  gives the strike id, and whose resource id differs from its ffFamID (FOND 5000, FONT 16416 = 128*128+32). */
	public int fontStrikeIdAll(String name, int size){
		int base = getRsrcIdAll(name, "font");
		if(base!=0 && getResourceAll(base+size, "font")!=null) return base+size;
		Found fond = foundByNameAll(name, "FOND");
		if(fond!=null){
			byte[] b = null;
			try { b = java.nio.file.Files.readAllBytes(new File(fond.path()).toPath()); }
			catch(IOException | RuntimeException e){ }
			if(b!=null && b.length>=54){
				int famID = (short)(((b[2]&0xFF)<<8)|(b[3]&0xFF));
				int count = (((b[52]&0xFF)<<8)|(b[53]&0xFF))+1;
				int plain = 0;
				for(int i=0; i<count && 54+i*6+6<=b.length; i++){
					int o = 54+i*6;
					int sz = ((b[o]&0xFF)<<8)|(b[o+1]&0xFF);
					int style = ((b[o+2]&0xFF)<<8)|(b[o+3]&0xFF);
					int id = (short)(((b[o+4]&0xFF)<<8)|(b[o+5]&0xFF));
					if(sz==size && getResourceAll(id, "font")!=null){
						if(style==0) return id;
						if(plain==0) plain = id;
					}
				}
				if(plain!=0) return plain;
				if(getResourceAll(famID*128+size, "font")!=null) return famID*128+size;
			}
		}
		return base!=0 ? base+size : 0;
	}

	private rsrcClass getResourceByNameAll(String name, String type){
		if(name==null || type==null) return null;
		rsrcClass r = lookupByName(rsrcNameMap, name, type);
		if(r!=null) return r;
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = lookupByName(ownerstack.usingStacks.get(i).rsrc.rsrcNameMap, name, type);
			if(r!=null) return r;
		}
		return null;
	}

	/** The resource named {@code name} in this stack or a stack in use (as GetNamedResource); null when there is none. */
	public rsrcClass findByNameAll(String name, String type){
		return getResourceByNameAll(name, type);
	}

	/** A resource found by name together with the stack (Rsrc) whose folder holds its file. */
	private static final class Found {
		final Rsrc owner;
		final rsrcClass r;
		Found(Rsrc owner, rsrcClass r){ this.owner = owner; this.r = r; }
		String path(){ return owner.ownPath(r); }
	}

	private Found foundByNameAll(String name, String type){
		if(name==null || type==null) return null;
		rsrcClass r = lookupByName(rsrcNameMap, name, type);
		if(r!=null) return new Found(this, r);
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			Rsrc using = ownerstack.usingStacks.get(i).rsrc;
			if(using==null) continue;
			r = lookupByName(using.rsrcNameMap, name, type);
			if(r!=null) return new Found(using, r);
		}
		return null;
	}

	/**
	 * For display by name (PICT buttons, picture "name", set cursor to "name"): the resource found by name, used as it is.
	 * Looking its ID up again from this stack found another resource whenever a stack in use (Home) had the name and this
	 * stack the ID (秘密結社ゲーム's play "Boing" played its own snd 129). For ICON/CURS a cicn/crsr of the name, or of the
	 * found resource's ID in the same file, wins as in {@link #getDisplayImage(int, String)}.
	 */
	private Found displayByNameAll(String name, String type){
		Found found = foundByNameAll(name, type);
		if(found==null) return null;
		String colorType = colorCounterpartType(type);
		if(colorType!=null && !found.r.type.equals(colorType)){
			rsrcClass color = found.owner.rsrcIdMap.get(colorType+Integer.toString(found.r.id));
			if(color!=null) return new Found(found.owner, color);
		}
		return found;
	}

	/** The hot spot of the cursor named {@code name} (its crsr first, as {@link #getDisplayImage(String, String)}); null when none. */
	public Point getDisplayHotSpot(String name){
		Found found = displayByNameAll(name, "CURS");
		return found==null ? null : new Point(found.r.hotsporleft, found.r.hotsportop);
	}

	public String getFilePathAll(String name, String type){
		rsrcClass r = getResourceByNameAll(name, type);
		if(r==null) return null;
		if(r==lookupByName(rsrcNameMap, name, type)){
			return ownPath(r);
		}
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			OStack usingStack = ownerstack.usingStacks.get(i);
			if(r==lookupByName(usingStack.rsrc.rsrcNameMap, name, type)){
				String parentpath = usingStack.file==null ? "" :
					usingStack.file.getParent()+File.separatorChar;
				return parentpath+r.filename;
			}
		}
		return null;
	}

	public String getFilePathAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) {
			return ownPath(r);
		}
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get(type+Integer.toString(id));
			if(r!=null) {
				String parentpath = (ownerstack.usingStacks.get(i).file.getParent()+File.separatorChar);
				return parentpath+r.filename;
			}
		}
		return null;
	}

	public String getNameAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r.name;
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get(type+Integer.toString(id));
			if(r!=null) return r.name;
		}
		return null;
	}
	
	public Point getHotSpotAll(int id){
		rsrcClass r = rsrcIdMap.get("CURS"+Integer.toString(id));
		if(r!=null){
			Point p = new Point(r.hotsporleft, r.hotsportop);
			return p;
		}
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get("CURS"+Integer.toString(id)); //by ID (was the name map: never found)
			if(r!=null) return new Point(r.hotsporleft, r.hotsportop);
		}
		return null;
	}

	//getDisplayImage(id,"CURS")がcrsrを優先して返す表示箇所用に、ホット
	//スポットも同じ優先順位(crsrがあればそちら、無ければCURS)で返す。
	public Point getDisplayHotSpot(int id){
		rsrcClass r = rsrcIdMap.get("crsr"+Integer.toString(id));
		if(r!=null) return new Point(r.hotsporleft, r.hotsportop);
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get("crsr"+Integer.toString(id));
			if(r!=null) return new Point(r.hotsporleft, r.hotsportop);
		}
		return getHotSpotAll(id);
	}
	
	//The stacks in use are searched in message-path order: started stacks first, Home (kept last by start using) after them.
	//Searching from the end found Home's ICON 1001–1007 before FROMINE VR2's engine stack's own (wrong logo).
	public rsrcClass getResourceAll(int id, String type){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r!=null) return r;
		for(int i=0; i<ownerstack.usingStacks.size(); i++){
			r = ownerstack.usingStacks.get(i).rsrc.rsrcIdMap.get(type+Integer.toString(id));
			if(r!=null) return r;
		}
		return null;
	}
	
	
	
	
	//昔のバージョンが_stack.xmlに書き出した<type>は、PICT/ICON/snd /PAT#/CURSを
	//picture/icon/sound/pat#/cursorという別名に変換したものだった(HCResource.
	//readResourceData参照)。現在はもう変換せずPICT/ICON/SND/PAT#/CURSのまま
	//保存する(書き込みはwriteXMLOneRsrc参照)が、既存スタックを開けなく
	//ならないよう、読み込み時だけ旧表記を新表記に変換しておく。
	private static String legacyToCurrentType(String type){
		if(type.equals("picture")) return "PICT";
		if(type.equals("icon")) return "ICON";
		if(type.equals("sound")) return "SND";
		if(type.equals("pat#")) return "PAT#";
		if(type.equals("cursor")) return "CURS";
		return type;
	}

	public XMLStreamReader readXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", typeStr = "", nameStr = "", fnameStr = "", leftStr = "0", topStr = "0";
        int attrs = 0;
        OptionInfo info = null;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ idStr = reader.getElementText(); }
	            	else if(elm.equals("type")){ typeStr = legacyToCurrentType(reader.getElementText()); }
	            	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
	            	else if(elm.equals("file")){ fnameStr = reader.getElementText(); }
	            	else if(elm.equals("left")){ leftStr = reader.getElementText(); }
	            	else if(elm.equals("top")){ topStr = reader.getElementText(); }
	            	else if(elm.equals("hotspot")){  }
	            	else if(elm.equals("attrs")){ attrs = parseAttributes(reader.getElementText()); }
	            	else if(elm.equals("fontinfo")){
	            		info = new FontInfo();
	            		reader = readFontInfoXML(reader, (FontInfo)info); }
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamConstants.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("media") || elm.equals("resource")){
	            		addResource(Integer.valueOf(idStr), typeStr, nameStr, fnameStr, leftStr, topStr, info);
	            		if(attrs!=0){ rsrcClass added = getResource1(Integer.valueOf(idStr), typeStr); if(added!=null) added.attributes = attrs; }
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}
	
	public XMLStreamReader readFontInfoXML(XMLStreamReader reader, FontInfo info) throws Exception {
        int locCount = 0, offsetCount = 0, widthCount = 0;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("firstChar")){ info.firstChar = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("lastChar")){ info.lastChar = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fontType")){ info.fontType = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("widMax")){ info.widMax = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("kernMax")){ info.kernMax = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("nDescent")){ info.nDescent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fRectWidth")){ info.fRectWidth = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("fRectHeight")){ info.fRectHeight = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("owTLoc")){ info.owTLoc = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("ascent")){ info.ascent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("descent")){ info.descent = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	else if(elm.equals("leading")){ info.leading = (char)(int)(Integer.valueOf(reader.getElementText())); }
	            	//an entry per code, the missing glyph's and a closing one; files from before Oct 23 2026 lack the closing one
	            	else if(elm.equals("loc")){
	            		if(info.locs==null) info.locs = new int[info.lastChar-info.firstChar+3];
	            		int v = Integer.valueOf(reader.getElementText());
	            		if(locCount<info.locs.length) info.locs[locCount++] = v;
	            	}
	            	else if(elm.equals("offset")){
	            		if(info.offsets==null) info.offsets = new int[info.lastChar-info.firstChar+3];
	            		int v = Integer.valueOf(reader.getElementText());
	            		if(offsetCount<info.offsets.length) info.offsets[offsetCount++] = v;
	            	}
	            	else if(elm.equals("width")){
	            		if(info.widthes==null) info.widthes = new int[info.lastChar-info.firstChar+3];
	            		int v = Integer.valueOf(reader.getElementText());
	            		if(widthCount<info.widthes.length) info.widthes[widthCount++] = v;
	            	}
	            	else{
	            		System.out.println("Local Name: " + reader.getLocalName());
	            		System.out.println("Element Text: " + reader.getElementText());
	            	}
	            }
	            if (eventType == XMLStreamConstants.END_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("fontinfo")){
	            		if(info.locs!=null && locCount<info.locs.length) info.locs = java.util.Arrays.copyOf(info.locs, locCount);
	            		if(info.offsets!=null && offsetCount<info.offsets.length) info.offsets = java.util.Arrays.copyOf(info.offsets, offsetCount);
	            		if(info.widthes!=null && widthCount<info.widthes.length) info.widthes = java.util.Arrays.copyOf(info.widthes, widthCount);
	            		break;
	            	}
	            }
		    } catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
	        }
	    }
	    return reader;
	}

	
	// XML保存
	public void writeXML(XMLStreamWriter writer) throws XMLStreamException {
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		
		while(it.hasNext()){
        	writer.writeCharacters("\t");
        	rsrcClass rsrc = it.next();
        	
        	rsrc.writeXMLOneRsrc(writer);
		}
	}
	
	class rsrcClass{
		int id;
		String type;
		String name;
		String filename;
		int hotsporleft;
		int hotsportop;
		OptionInfo optionInfo;
		/** The Resource Manager attributes it had in the HyperCard file ({@link #KEPT_ATTRIBUTES}), as GetResAttrs gives them. */
		int attributes;
		public rsrcClass(int id, String type, String name, String filename, String left, String top, OptionInfo optionInfo){
			this.id = id;
			this.type = type;
			this.name = name;
			this.filename = filename;
			this.hotsporleft = Integer.valueOf(left);
			this.hotsportop = Integer.valueOf(top);
			this.optionInfo = optionInfo;
		}
		

		public void writeXMLOneRsrc(XMLStreamWriter writer) throws XMLStreamException
		{
			writer.writeStartElement("media");
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("type");
		        writer.writeCharacters(this.type);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("name");
		        writer.writeCharacters(this.name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	writer.writeStartElement("file");
		        writer.writeCharacters(this.filename);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t");

		        //only when set: a stack without them reads as before, and an older HyperZebra skips the element
		        if((this.attributes&KEPT_ATTRIBUTES)!=0){
		        	writer.writeCharacters("\t");
		        	writer.writeStartElement("attrs");
		        	writer.writeCharacters(Integer.toString(this.attributes&KEPT_ATTRIBUTES));
		        	writer.writeEndElement();
		        	writer.writeCharacters("\n\t");
		        }
		        
		        //CURSだけでなくcrsr(カラーカーソル)もホットスポットを持つ。crsrの
		        //ホットスポットは.dataの生データ内(offset84)にも入っているが、
		        //XMLへ書き出さないとスタックを開き直したときにreadXMLが(0,0)で
		        //登録してしまい、getDisplayHotSpotが間違った位置を返す。
		        if(this.type.equals("CURS") || this.type.equals("crsr")){
		        	writer.writeCharacters("\t");
		        	writer.writeStartElement("hotspot");
			        writer.writeCharacters("\n\t\t\t");
			        {
			        	writer.writeStartElement("left");
				        writer.writeCharacters(Integer.toString(this.hotsporleft));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
				        
			        	writer.writeStartElement("top");
				        writer.writeCharacters(Integer.toString(this.hotsportop));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
			        }
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		
		    	FontInfo fontinfo = (FontInfo)this.optionInfo;
		        if(this.type.equals("font") && fontinfo!=null){
		        	writer.writeCharacters("\t");
		        	writer.writeStartElement("fontinfo");
			        writer.writeCharacters("\n\t\t\t");
			        {
			        	
			        	writer.writeStartElement("fontType");
				        writer.writeCharacters(Integer.toString(fontinfo.fontType));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("firstChar");
				        writer.writeCharacters(Integer.toString(fontinfo.firstChar));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("lastChar");
				        writer.writeCharacters(Integer.toString(fontinfo.lastChar));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("widMax");
				        writer.writeCharacters(Integer.toString(fontinfo.widMax));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("kernMax");
				        writer.writeCharacters(Integer.toString(fontinfo.kernMax));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("nDescent");
				        writer.writeCharacters(Integer.toString(fontinfo.nDescent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("fRectWidth");
				        writer.writeCharacters(Integer.toString(fontinfo.fRectWidth));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("fRectHeight");
				        writer.writeCharacters(Integer.toString(fontinfo.fRectHeight));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("owTLoc");
				        writer.writeCharacters(Integer.toString(fontinfo.owTLoc));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("ascent");
				        writer.writeCharacters(Integer.toString(fontinfo.ascent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("descent");
				        writer.writeCharacters(Integer.toString(fontinfo.descent));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
			        	writer.writeStartElement("leading");
				        writer.writeCharacters(Integer.toString(fontinfo.leading));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t");
				        
				        for(int j=0; fontinfo.locs!=null&&j<fontinfo.locs.length; j++){
				        	writer.writeStartElement("loc");
					        writer.writeCharacters(Integer.toString(fontinfo.locs[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
				        
				        for(int j=0; fontinfo.offsets!=null&&j<fontinfo.offsets.length; j++){
				        	writer.writeStartElement("offset");
					        writer.writeCharacters(Integer.toString(fontinfo.offsets[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
				        
				        for(int j=0; fontinfo.widthes!=null&&j<fontinfo.widthes.length; j++){
				        	writer.writeStartElement("width");
					        writer.writeCharacters(Integer.toString(fontinfo.widthes[j]));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t");
				        }
			        }
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t");
		        }
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
		
	}
	
	class OptionInfo{
		
	}

	/**
	 * The resource attributes a converted resource keeps from its HyperCard file: resSysHeap, resPurgeable, resLocked,
	 * resProtected, resPreload. Externals look at them with GetResAttrs (SendAppleEvent installs its 'PROC' coercion handler only
	 * when the PROC is resSysHeap; without it every send failed -1700). resChanged and resCompressed say nothing about the stored
	 * bytes (kept expanded) and are not kept.
	 */
	static final int KEPT_ATTRIBUTES = 0x7C;
	static int parseAttributes(String text){
		try{ return Integer.parseInt(text.trim())&KEPT_ATTRIBUTES; }catch(NumberFormatException e){ return 0; }
	}
	

	public int getNewResourceId(String type){
		return getNewResourceId(type, 1000);
	}
	
	public int getNewResourceId(String type, int baseid){
		int id = baseid;
		while(true){
			if(null == getFilePathAll(id, type) && id>=-32768 && id <= Integer.MAX_VALUE){
				break;
			}
			id++;
			if(id<-32768 || id>=32768) id = 1;;
		}
		return id;
	}
	
	public void addResource(int id, String type, String name, String path){
		rsrcClass rsrc = new rsrcClass(id, type, name, path, "0", "0", null);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		markChanged();
	}
	
	public void addResource(int id, String type, String name, String filename, String leftStr, String topStr){
		rsrcClass rsrc = new rsrcClass(id, type, name, filename, leftStr, topStr, null);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		markChanged();
	}
	
	public void addResource(int id, String type, String name, String filename, String leftStr, String topStr, OptionInfo info){
		rsrcClass rsrc = new rsrcClass(id, type, name, filename, leftStr, topStr, info);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
		markChanged();
	}
	
	public void addResource(rsrcClass r){
		rsrcIdMap.put(r.type+r.id, r);
		rsrcNameMap.put(r.type+r.name.toLowerCase(), r);
		markChanged();
	}
	
	public boolean deleteResource(String type, int id){
		rsrcClass r = rsrcIdMap.get(type+Integer.toString(id));
		if(r==null) return false;
		if(stagingDir!=null){
			//作業用コピー: スタックのファイルには触れず、一時フォルダの編集分だけ捨てる
			stagedFile(r.filename).delete();
		}
		else if(ownerstack.file!=null){
			File source = new File(this.ownerstack.file.getParent(),r.filename);
			try {
				if(source.exists()) AppPaths.moveResourceToTrash(source);
			} catch(java.io.IOException e) {
				System.err.println("Could not move resource to trash: "+e.getMessage());
				return false;
			}
		}
		rsrcIdMap.remove(type+r.id);
		//the name entry may be another resource's of the same name (many unnamed icons; umich Icon Invasion's XCMD removes
		//them): only this one's goes, and a namesake left takes the name
		String nameKey = type+r.name.toLowerCase();
		if(rsrcNameMap.get(nameKey)==r){
			rsrcNameMap.remove(nameKey);
			for(rsrcClass o : rsrcIdMap.values()){
				if(o.type.equals(type) && o.name.equalsIgnoreCase(r.name)){ rsrcNameMap.put(nameKey, o); break; }
			}
		}
		markChanged();
		return true;
	}

	
	
	public int getRsrcCount(String type){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				i++;
			}
		}
		return i;
	}
	
	public int getRsrcCountAll(String type){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				i++;
			}
		}
		for(int j=0; j<ownerstack.usingStacks.size(); j++){
			OStack rsrcstack = ownerstack.usingStacks.get(j);
			it = rsrcstack.rsrc.rsrcIdMap.values().iterator();
			while(it.hasNext()){
				rsrc = it.next();
				if(rsrc.type.equals(type)){
					if((rsrcstack.file.getParent()+File.separatorChar+rsrc.filename)
							.equals(getFilePathAll(rsrc.id, type)))
					{
						i++;
					}
				}
			}
		}
		return i;
	}

	public rsrcClass getRsrcByIndex(String type, int index){
		Iterator<rsrcClass> it = rsrcIdMap.values().iterator();
		int i=0;
		rsrcClass rsrc;
		while(it.hasNext()){
			rsrc = it.next();
			if(rsrc.type.equals(type)){
				if(i==index){
					return rsrc;
				}
				i++;
			}
		}
		return null;
	}
	

	//----------------
	// for icon
	//----------------
	public BufferedImage getImage(int id){
		return loadImage(this.getFilePathAll(id, "ICON"), "ICON");
	}

	//ICON/cicn/CURS/PAT#/ppatは、インポート時にPNGへ変換せず生データ(拡張子.data)
	//のまま保存されている(HCResource.readResourceData参照)。ここで初めて、
	//実際に必要になったタイミングで画像へデコードする。それ以外の拡張子
	//(既に編集されてPNGになっている、または元々PICT/PBM形式等)は従来通り
	//画像読み込みライブラリにまかせる。
	private static BufferedImage loadImage(String path, String type){
		if(path==null) return null;
		BufferedImage bi = null;
		try{
			if(path.toLowerCase().endsWith(".data")){
				byte[] b = java.nio.file.Files.readAllBytes(new File(path).toPath());
				if("ICON".equals(type)) bi = HCResource.decodeICON(b);
				else if("cicn".equals(type)) bi = HCResource.decodeCicn(b);
				else if("CURS".equals(type)) bi = HCResource.decodeCURS(b);
				else if("crsr".equals(type)) bi = HCResource.decodeCrsr(b);
				else if("PAT#".equals(type)) bi = HCResource.decodePAT(b);
				else if("ppat".equals(type)) bi = HCResource.decodePpat(b);
				else if("Pdat".equals(type)) bi = PictureFile.loadPICT(b); //ColorizeHC's pictures: PICT data without the 512-byte file header
				if(bi!=null) return bi;
				//未知の型で.data拡張子の場合はここで諦めず、念のため下の
				//通常の画像読み込みも試す(下のtry内で失敗してもnullを返すだけ)
			}
			bi = PictureFile.loadPbm(path);
			if(bi==null){
				bi = javax.imageio.ImageIO.read(new File(path));
			}
			if(bi==null){
				bi = PictureFile.loadPICT(path);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return bi;
	}


	//----------------
	// for PICT etc
	//----------------
	public BufferedImage getImage(int id, String type){
		return loadImage(this.getFilePathAll(id, type), type);
	}

	public BufferedImage getImage(String name, String type){
		Found found = foundByNameAll(name, type);
		return found==null ? null : loadImage(found.path(), found.r.type); //a cicn found for "ICON" is decoded as a cicn
	}

	//ボタンのアイコン表示やthe cursorプロパティ等、HyperCard互換の"表示"箇所
	//専用。Inside Macintoshの説明どおり、同じIDのcicn/crsrがあればカラー版を
	//優先して返す(無ければ通常通りICON/CURS自体を返す)。リソースエディタが
	//特定の型を明示して開いている個別リソースの編集対象までカラー版に
	//すり替えてしまうと、編集中の中身と食い違ってしまう(例えばICONエディタが
	//cicnの絵を読み込んでしまい、保存時にモノクロ化して元のICONを上書きする)
	//ため、getImage自体は変更せず、この専用メソッドだけをそうした表示箇所から
	//呼ぶようにする。
	//名前で指定する表示用の画像(PICTボタン: カード移動のたびに PICT を読み直していた)。getDisplayImage と同じく
	//共有のキャッシュの画像を返すので、呼び出し側は描き込まないこと
	public BufferedImage getDisplayImage(String name, String type){
		Found found = displayByNameAll(name, type);
		return found==null ? null : cachedDisplayImageFile(found.path(), found.r.type);
	}

	public BufferedImage getDisplayImage(int id, String type){
		String colorType = colorCounterpartType(type);
		if(colorType!=null){
			BufferedImage colorImage = cachedDisplayImage(id, colorType);
			if(colorImage!=null) return colorImage;
		}
		return cachedDisplayImage(id, type);
	}

	/**
	 * HyperCard's built-in cursors (hand 128, watch 4, …) come from GetCursor, which finds a CURS of that ID in the stack or a
	 * stack in use before HyperCard's own; Home's is HyperCard's (its CURS 128 is the usual hand), so Home is skipped and the
	 * caller keeps the host cursor. Returns {image, hot spot} (crsr first), or null when no such stack has one.
	 */
	Object[] stackCursor(int id){
		int count = ownerstack==null || ownerstack.usingStacks==null ? 0 : ownerstack.usingStacks.size();
		for(int i=-1; i<count; i++){
			OStack stack = i<0 ? ownerstack : ownerstack.usingStacks.get(i);
			if(stack==null || stack==PCARD.home) continue;
			Rsrc owner = i<0 ? this : stack.rsrc;
			if(owner==null) continue;
			rsrcClass r = owner.rsrcIdMap.get("crsr"+id);
			if(r==null) r = owner.rsrcIdMap.get("CURS"+id);
			if(r==null) continue;
			BufferedImage bi = cachedDisplayImageFile(owner.ownPath(r), r.type);
			if(bi!=null) return new Object[]{bi, new Point(r.hotsporleft, r.hotsportop)};
		}
		return null;
	}

	//----------------
	// for FONT
	//----------------
	class FontInfo extends OptionInfo{
		int fontType;
		char firstChar;
		char lastChar;
		int widMax;
		int kernMax;
		int nDescent;
		int fRectWidth;
		int fRectHeight;
		int owTLoc;
		int ascent;
		int descent;
		int leading;
		int[] locs;
		int[] offsets;
		int[] widthes;
	}
	
	public void addFontResource(int id, String type, String name, String path, FontInfo info){
		rsrcClass rsrc = new rsrcClass(id, type, name, path, "0", "0", info);
		rsrcIdMap.put(type+id, rsrc);
		rsrcNameMap.put(type+name.toLowerCase(), rsrc);
	}
	
	//----------------
	// XCMD
	//----------------
	public int getxcmdId(String name, String type){
		xcmdClass r = getXcmd(name, type);
		if(r!=null) return r.id;
		return 0;
	}

	/** Whether the stack has this XCMD/XFCN (its id may be 0 or negative: TheAppleAnimation's AMPlay is XCMD -8002). */
	/** Whether this resource set has any XCMD/XFCN at all (a cheap test before looking a name up) */
	boolean hasAnyXcmd(){ return !xcmdNameMap.isEmpty(); }

	public boolean hasXcmd(String name, String type){
		return getXcmd(name, type)!=null;
	}

	//同名のXCMDが68K版とPowerPC版の両方あるスタックでは
	//68K版を返す。68K版がなければPPC版(HyperCard 2.3 のネイティブ外部コマンド: VM の Mixed Mode で PowerPC コードを実行する)
	public xcmdClass getXcmd(String name, String type){
		xcmdClass r = xcmdNameMap.get(xcmdKey(type, "mac68k", name));
		if(r!=null) return r;
		//HyperTalk の字句解析はコマンド位置・関数位置の単語ごとにここを呼ぶので、68K版が無いときの全件走査の結果を
		//登録の世代つきで覚えておく(字句解析はスクリプトのスレッドとエディタの EDT の両方から来る)
		int generation = xcmdGeneration;
		String lookupKey = type+"\u0000"+name.toLowerCase();
		XcmdLookup cached = xcmdLookup.get(lookupKey);
		if(cached != null && cached.generation == generation) return cached.xcmd;
		String prefix = type+"\u0000";
		String suffix = "\u0000"+name.toLowerCase();
		xcmdClass found = null;
		for(xcmdClass x: xcmdNameMap.values()){
			String key = xcmdKey(x.type, x.platform, x.name);
			if(key.startsWith(prefix) && key.endsWith(suffix)){ found = x; break; }
		}
		if(xcmdLookup.size() > 4096) xcmdLookup.clear();
		xcmdLookup.put(lookupKey, new XcmdLookup(generation, found));
		return found;
	}

	/** XCMD/XFCN の登録が変わるたびに増える(どの Rsrc でも)。字句解析のキャッシュ(TTalk.fullresolution)と getXcmd の検索結果の有効性の確認に使う */
	static volatile int xcmdGeneration;
	private record XcmdLookup(int generation, xcmdClass xcmd) {}
	private final java.util.concurrent.ConcurrentHashMap<String,XcmdLookup> xcmdLookup = new java.util.concurrent.ConcurrentHashMap<String,XcmdLookup>();

	//68K版とPowerPC版は別のリソース(XCMD/xcmd)なので両方とも保持する(書き出しでも両方戻す)
	private static String xcmdKey(String type, String platform, String name){
		String p = platform==null ? "" : platform.toLowerCase();
		p = p.contains("68k") ? "mac68k" : p.contains("ppc") ? "macppc" : p;
		return type+"\u0000"+p+"\u0000"+name.toLowerCase();
	}

	public void addXcmd(xcmdClass xcmd){
		xcmdNameMap.put(xcmdKey(xcmd.type, xcmd.platform, xcmd.name), xcmd);
		xcmdGeneration++;
	}

	/** Removes the external {@code type} ("command"/"function") of that platform ("68k"/"ppc") and ID; false when there is none. */
	boolean removeXcmd(String type, String platform, int id){
		boolean removed = xcmdNameMap.values().removeIf(x -> x.id==id && x.type.equalsIgnoreCase(type) && x.platform!=null && x.platform.toLowerCase().contains(platform));
		if(removed){ xcmdGeneration++; markChanged(); }
		return removed;
	}

	ArrayList<xcmdClass> getXcmdResources(){
		return new ArrayList<xcmdClass>(xcmdNameMap.values());
	}

	//----- リソースエディタでのXCMD/XFCN: Macのリソースタイプ(XCMD/XFCN = 68K、xcmd/xfcn = PowerPC)で扱う -----

	static boolean isExternalMacType(String type){
		return type.equals("XCMD") || type.equals("XFCN") || type.equals("xcmd") || type.equals("xfcn");
	}

	/** The resource type of an external in the stack's resource fork: XCMD/XFCN for 68K code, xcmd/xfcn for PowerPC. */
	static String externalMacType(xcmdClass x){
		String t = "function".equalsIgnoreCase(x.type) ? "XFCN" : "XCMD";
		String p = x.platform==null ? "" : x.platform.toLowerCase();
		return !p.contains("68k") && p.contains("ppc") ? t.toLowerCase() : t;
	}

	/** The externals of one resource type (XCMD, XFCN, xcmd, xfcn) in the resource map order. */
	ArrayList<xcmdClass> externalsOfType(String macType){
		ArrayList<xcmdClass> list = new ArrayList<xcmdClass>();
		for(xcmdClass x : xcmdNameMap.values()) if(externalMacType(x).equals(macType)) list.add(x);
		return list;
	}

	xcmdClass getExternal(String macType, int id){
		for(xcmdClass x : xcmdNameMap.values()) if(x.id==id && externalMacType(x).equals(macType)) return x;
		return null;
	}

	/** The external of that resource type with this name (HyperCard calls externals by name: one per name and type). */
	xcmdClass getExternalNamed(String macType, String name){
		return xcmdNameMap.get(xcmdKey(externalType(macType), externalPlatform(macType), name));
	}

	static String externalType(String macType){ return macType.equalsIgnoreCase("XFCN") ? "function" : "command"; }
	static String externalPlatform(String macType){ return Character.isUpperCase(macType.charAt(0)) ? "mac68k" : "macppc"; }

	/** The file of an external: in a working copy the edited (staged) one first. */
	String externalPath(xcmdClass x){
		return ownFilePath(x.filename);
	}

	/** A new external of that resource type (in the working copy: written back by Save); one of the same name goes. */
	xcmdClass putExternal(String macType, int id, String name, String filename, int size){
		xcmdClass x = new xcmdClass(Integer.toString(id), externalType(macType), name, filename, externalPlatform(macType), Integer.toString(size));
		addXcmd(x);
		externalsEdited = true;
		markChanged();
		return x;
	}

	/** Removes an external (in a working copy its edited file goes; the stack's own at Save). */
	boolean deleteExternal(xcmdClass x){
		String key = xcmdKey(x.type, x.platform, x.name);
		if(xcmdNameMap.get(key)!=x) return false;
		xcmdNameMap.remove(key);
		if(stagingDir!=null && !isFileUsed(x.filename)) stagedFile(x.filename).delete();
		xcmdGeneration++;
		externalsEdited = true;
		markChanged();
		return true;
	}

	/** A new ID or name of an external; its place in the resource map stays (as SetResInfo leaves it). */
	void setExternalIdAndName(xcmdClass x, int id, String name){
		LinkedHashMap<String,xcmdClass> map = new LinkedHashMap<String,xcmdClass>();
		for(Map.Entry<String,xcmdClass> e : xcmdNameMap.entrySet()){
			if(e.getValue()==x){
				x.id = id;
				x.name = name;
				map.put(xcmdKey(x.type, x.platform, x.name), x);
			}
			else map.put(e.getKey(), e.getValue());
		}
		xcmdNameMap = map;
		xcmdGeneration++;
		externalsEdited = true;
		markChanged();
	}
	
	public XMLStreamReader readXCMDXML(XMLStreamReader reader) throws Exception {
        String idStr = "0", typeStr = "", nameStr = "", pathStr = "", platformStr = "", sizeStr = "";
        int attrs = 0;
        int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
        	String attr = reader.getAttributeLocalName(i);
        	if(attr.equals("id")){ idStr = reader.getAttributeValue(i); }
        	else if(attr.equals("type")){ typeStr = reader.getAttributeValue(i); }
        	else if(attr.equals("platform")){ platformStr = reader.getAttributeValue(i); }
        	else if(attr.equals("name")){ nameStr = reader.getAttributeValue(i); }
        	else if(attr.equals("size")){ sizeStr = reader.getAttributeValue(i); }
        	else if(attr.equals("file")){ pathStr = reader.getAttributeValue(i); }
        	else if(attr.equals("attrs")){ attrs = parseAttributes(reader.getAttributeValue(i)); }
        }
        //xcmdList.add(new xcmdClass(idStr, typeStr, nameStr, pathStr, platformStr, sizeStr));
        xcmdClass xcmd = new xcmdClass(idStr, typeStr, nameStr, pathStr, platformStr, sizeStr);
        xcmd.attributes = attrs;
        addXcmd(xcmd);
        return reader;
	}

	
	public void writeXcmdXML(XMLStreamWriter writer) throws XMLStreamException {
		Iterator<xcmdClass> it = xcmdNameMap.values().iterator();
		xcmdClass xcmd;
		while(it.hasNext()){
        	writer.writeCharacters("\t");
			xcmd = it.next();
        	writer.writeEmptyElement("externalcommand");
        	writer.writeAttribute("type", xcmd.type);
        	writer.writeAttribute("platform", xcmd.platform);
        	writer.writeAttribute("id", Integer.toString(xcmd.id));
        	writer.writeAttribute("size", Integer.toString(xcmd.size));
        	writer.writeAttribute("name", xcmd.name);
        	writer.writeAttribute("file", xcmd.filename);
        	if((xcmd.attributes&KEPT_ATTRIBUTES)!=0) writer.writeAttribute("attrs", Integer.toString(xcmd.attributes&KEPT_ATTRIBUTES));
	        writer.writeCharacters("\n");
		}
	}
	
	
	static class xcmdClass{
		int id;
		String type; //command or function
		String name;
		String filename;
		String platform;
		int size;
		/** Resource attributes from the HyperCard file, as {@link rsrcClass#attributes}. */
		int attributes;
		public xcmdClass(String id, String type, String name, String filename, String platform, String size){
			this.id = Integer.valueOf(id);
			this.type = type;
			this.name = name;
			this.filename = filename;
			this.platform = platform;
			this.size = Integer.valueOf(size);
		}
		xcmdClass copy(){
			xcmdClass copy = new xcmdClass(Integer.toString(id), type, name, filename, platform, Integer.toString(size));
			copy.attributes = attributes;
			return copy;
		}
	}
	

	//-------------
	//AddColor関連
	//-------------
	public XMLStreamReader readAddColorXML(XMLStreamReader reader, boolean isBg) throws Exception {
        String idStr = "0";
        addcolorClass addColorOwner = null;
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("id")){ 
		        		idStr = reader.getElementText(); 
		                addColorOwner = new addcolorClass(idStr, isBg);
		        	}
		        	else if(elm.equals("addcolorobject")){ reader = readAddColorObjXML(reader, addColorOwner); }
			    	else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("addcolorbackground")){
			    		break;
			    	}
			    	if(elm2.equals("addcolorcard")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
			}
		}
	    addcolorList.add(addColorOwner);
	    return reader;
	}
	

	public XMLStreamReader readAddColorObjXML(XMLStreamReader reader, addcolorClass parent) throws Exception {
        String typeStr = "", nameStr = "";
        boolean transparent = false, file = false;
        boolean visible = true;
        Rectangle rect = new Rectangle();
        int id=0,bevel=0,red=0,green=0,blue=0; 
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("type")){ typeStr = reader.getElementText(); }
		        	else if(elm.equals("id")){ id = Integer.valueOf(reader.getElementText()); }
		        	//coordinates are signed 16-bit; stacks imported before that was read so keep 65525 for -11
		        	else if(elm.equals("top")){ rect.y = (short)(int)Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("left")){ rect.x = (short)(int)Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("bottom")){ rect.height = (short)(int)Integer.valueOf(reader.getElementText()) - rect.y; }
		        	else if(elm.equals("right")){ rect.width = (short)(int)Integer.valueOf(reader.getElementText()) - rect.x; }
		        	else if(elm.equals("transparent")){ transparent = XMLRead.bool(reader);reader.next(); }
		        	else if(elm.equals("file")){ file = XMLRead.bool(reader);reader.next(); }
		        	else if(elm.equals("name")){ nameStr = reader.getElementText(); }
		        	else if(elm.equals("visible")){ visible = XMLRead.bool(reader);reader.next(); }
		        	else if(elm.equals("rect")){  }
		        	else if(elm.equals("bevel")){ bevel = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("color")){  }
		        	else if(elm.equals("red")){ red = Integer.valueOf(reader.getElementText())/256; }
		        	else if(elm.equals("green")){ green = Integer.valueOf(reader.getElementText())/256; }
		        	else if(elm.equals("blue")){ blue = Integer.valueOf(reader.getElementText())/256; }
		            else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("addcolorobject")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
			}
		}
        if(typeStr.equals("picture")){
        	parent.addPictObject(nameStr, rect, transparent, visible, file);
        }
        if(typeStr.equals("rectangle")){
        	parent.addRectObject(rect, bevel, new Color(red, green, blue), visible);
        }
        if(typeStr.equals("button")){
        	parent.addBtnObject(id, bevel, new Color(red,green, blue), visible);
        }
        if(typeStr.equals("field")){
        	parent.addFldObject(id, bevel, new Color(red,green, blue), visible);
        }
	    return reader;
	}
	
	
	public static void setEnabledACObj(int number, boolean enabled){
		//addcolorClassを探す
		ArrayList<addcolorClass> cdList = PCARD.pc.stack.rsrc.addcolorList;
		addcolorClass accd = null;
		for(int i=0; i<cdList.size(); i++){
			if(PCARD.pc.stack.curCard.id == cdList.get(i).id){
				accd = cdList.get(i);
				break;
			}
		}
		if(accd==null) return;
		
		if(number>=0 && number<accd.objList.size()){
			accd.objList.get(number).visible = enabled;
		}
	}
	
	/** AddColor changeObjectColor,bg|cd,<number>,"r,g,b" (16-bit values): the colour of the current card's or background's number'th
	 *  colour object (rectangle/button/field) from now on - held in memory only, the stack's own colour stays as it was saved
	 *  (変数コントローラー's Sample: the sliders change the screen colour; the next colorCard draws it). */
	public static void setColorACObj(boolean bg, int number, Color color){
		OCard card = PCARD.pc.stack.curCard;
		if(card==null) return;
		for(addcolorClass c : PCARD.pc.stack.rsrc.addcolorList){
			boolean mine = bg ? c.isBg && card.bg!=null && c.id==card.bg.id : !c.isBg && c.id==card.id;
			if(!mine) continue;
			if(number>=0 && number<c.objList.size() && !(c.objList.get(number) instanceof PictObject)) c.objList.get(number).runColor = color;
			return;
		}
	}

	class addcolorClass{
		int id;
		boolean isBg;
		ArrayList<addcolorObjClass> objList  = new ArrayList<addcolorObjClass>();
		
		public addcolorClass(String id, boolean isBg){
			this.id = Integer.valueOf(id);
			this.isBg = isBg;
		}
		
		public void addPictObject(String name, Rectangle rect, boolean transparent, boolean visible, boolean file){
			objList.add(new PictObject(name, rect, transparent, visible, file));
		}
		public void addRectObject(Rectangle rect, int bevel, Color color, boolean visible){
			objList.add(new RectObject(rect, bevel, color, visible));
		}
		public void addBtnObject(int id, int bevel, Color color, boolean visible){
			objList.add(new BtnObject(id, bevel, color, visible));
		}
		public void addFldObject(int id, int bevel, Color color, boolean visible){
			objList.add(new FldObject(id, bevel, color, visible));
		}

		public void writeAddColorXMLOne(XMLStreamWriter writer) throws XMLStreamException {
			if(isBg){
				writer.writeStartElement("addcolorbackground");
			}else{
				writer.writeStartElement("addcolorcard");
			}
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	for(int i=0; i<objList.size(); i++){
			    	writer.writeStartElement("addcolorobject");

			    	addcolorObjClass obj = objList.get(i);
			    	PictObject picto = null;
			    	RectObject recto = null;
			    	BtnObject btno = null;
			    	FldObject fldo = null;;
			    	
			    	writer.writeStartElement("type");
			    	if(obj.getClass()==PictObject.class){
			    		writer.writeCharacters("picture");
			    		picto = (PictObject)obj;
			    	}
			    	else if(obj.getClass()==RectObject.class){
			    		writer.writeCharacters("rectangle");
			    		recto = (RectObject)obj;
			    	}
			    	else if(obj.getClass()==BtnObject.class){
			    		writer.writeCharacters("button");
			    		btno = (BtnObject)obj;
			    	}
			    	else if(obj.getClass()==FldObject.class){
			    		writer.writeCharacters("field");
			    		fldo = (FldObject)obj;
			    	}
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");

			        int id = 0;
			        if(btno!=null) id = btno.id;
			        else if(fldo!=null) id = fldo.id;
			        if(id>0){
				    	writer.writeStartElement("id");
				        writer.writeCharacters(Integer.toString(id));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			    	
			        Rectangle rect = null;
			        if(picto!=null) rect = picto.rect;
			        else if(recto!=null) rect = recto.rect;
			        if(rect!=null){
				    	writer.writeStartElement("rect");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("left");
					        writer.writeCharacters(Integer.toString(rect.x));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("top");
					        writer.writeCharacters(Integer.toString(rect.y));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("right");
					        writer.writeCharacters(Integer.toString(rect.x+rect.width));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("bottom");
					        writer.writeCharacters(Integer.toString(rect.y+rect.height));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }

			        if(picto!=null){
				    	writer.writeStartElement("name");
				        writer.writeCharacters(picto.name);
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
				        
				    	writer.writeStartElement("transparent");
				        //他のbool要素と同じ <true /> 形式で書く(以前はテキストで書いて
				        //いたため、読み込み側のXMLRead.boolと噛み合っていなかった)
				        writer.writeCharacters(" ");
				        writer.writeEmptyElement(picto.transparent?"true":"false");
				        writer.writeCharacters(" ");
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
				        if(picto.file){
					    	writer.writeStartElement("file");
					        writer.writeCharacters(" ");
					        writer.writeEmptyElement("true");
					        writer.writeCharacters(" ");
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				        }
			        }
			        
			        if(picto==null) {
				    	writer.writeStartElement("bevel");
				        writer.writeCharacters(Integer.toString(obj.bevel));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			    	writer.writeStartElement("visible");
			        writer.writeCharacters(" ");
			        writer.writeEmptyElement(Boolean.toString(obj.visible)+" ");
			        writer.writeCharacters(" ");
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t\t");
			        
			        if(picto==null) {
				    	writer.writeStartElement("color");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("red");
					        writer.writeCharacters(Integer.toString(obj.color.getRed()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("green");
					        writer.writeCharacters(Integer.toString(obj.color.getGreen()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("blue");
					        writer.writeCharacters(Integer.toString(obj.color.getBlue()*256));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t");
		    	}
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
	}
	
	class addcolorObjClass{
		int bevel;
		Color color;
		/** the colour set by AddColor changeObjectColor: drawn instead of `color`, never saved */
		Color runColor;
		boolean visible;
		
		public addcolorObjClass(int bevel, Color color, boolean visible){
			this.bevel = bevel;
			this.color = color;
			this.visible = visible;
		}
	}
	
	class PictObject extends addcolorObjClass {
		String name;
		boolean transparent;
		Rectangle rect;
		/** AddColor object type 5: a PICT file (in the stack's folder, or a path) rather than a PICT resource (type 4) */
		boolean file;

		public PictObject(String name, Rectangle rect, boolean transparent, boolean visible, boolean file) {
			super(0, null, visible);
			this.name = name;
			this.rect = rect;
			this.transparent = transparent;
			this.file = file;
		}
	}
	
	class RectObject extends addcolorObjClass {
		Rectangle rect;

		public RectObject(Rectangle rect, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.rect = rect;
		}
	}
	
	class BtnObject extends addcolorObjClass {
		int id;

		public BtnObject(int id, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.id = id;
		}
	}
	
	class FldObject extends addcolorObjClass {
		int id;

		public FldObject(int id, int bevel, Color color, boolean visible) {
			super(bevel, color, visible);
			this.id = id;
		}
	}
	

	// XML保存
	public void writeAddColorXML(XMLStreamWriter writer) throws XMLStreamException {
		ArrayList<addcolorClass> cdList = addcolorList; //(以前はPCARD.pc.stackのものを書いていた: 表示中でないスタックの保存で食い違う)
		for(int i=0; i<cdList.size(); i++){
			cdList.get(i).writeAddColorXMLOne(writer);
		}
	}
	
	
	
	//-------------
	//PLTE関連
	//-------------
	public XMLStreamReader readPlteXML(XMLStreamReader reader) throws Exception {
        int id = 0;
        String name="";
        int WindowDef = 0;
        boolean clearHilite = true;
        Integer hiliteType = null;
        int btnType=0;
        int pictId=0;
        Point pictHV = new Point(0,0);
        PlteClass plteOwner = null;
        
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
	            	if(elm.equals("id")){ 
		        		id = Integer.valueOf(reader.getElementText()); 
		        	}
		        	else if(elm.equals("name")){ 
		        		name = reader.getElementText(); 
		        	}
		        	else if(elm.equals("windowdefinition")){ 
		        		WindowDef = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("clearhilite")){
		        		clearHilite = XMLRead.bool(reader);reader.next();
		        	}
		        	else if(elm.equals("hilitetype")){
		        		hiliteType = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("buttontype")){ 
		        		String typeStr = reader.getElementText();
		        		if(typeStr.equals("solid")){
		        			btnType = 0;
		        		}else if(typeStr.equals("frame")){
		        			btnType = 1;
		        		}
		        	}
		        	else if(elm.equals("pictureid")){ 
		        		pictId = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("horizontaloffset")){ 
		        		pictHV.x = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("verticaloffset")){ 
		        		pictHV.y = Integer.valueOf(reader.getElementText());
		        	}
		        	else if(elm.equals("paletteobject")){
		                if(plteOwner==null){
		                	plteOwner = new PlteClass(id, name, WindowDef, clearHilite, btnType, pictId, pictHV);
		                	if(hiliteType!=null) plteOwner.setHiliteType(hiliteType);
		                }
		        		reader = readPlteObjXML(reader, plteOwner); }
			    	else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("palette")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
			}
		}
	    if(plteOwner==null){
	    	//ボタンのないパレット
	    	plteOwner = new PlteClass(id, name, WindowDef, clearHilite, btnType, pictId, pictHV);
	    	if(hiliteType!=null) plteOwner.setHiliteType(hiliteType);
	    }
	    plteList.add(plteOwner);
	    return reader;
	}
	

	public XMLStreamReader readPlteObjXML(XMLStreamReader reader, PlteClass parent) throws Exception {
        String message = "";
        Rectangle rect = new Rectangle();
        //int id=0; 
        while (reader.hasNext()) {
    	    try {
	            int eventType = reader.next();
	            if (eventType == XMLStreamConstants.START_ELEMENT) {
	            	String elm = reader.getLocalName();
		        	if(elm.equals("id")){ /*id =*/ Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("top")){ rect.y = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("left")){ rect.x = Integer.valueOf(reader.getElementText()); }
		        	else if(elm.equals("bottom")){ rect.height = Integer.valueOf(reader.getElementText()) - rect.y; }
		        	else if(elm.equals("right")){ rect.width = Integer.valueOf(reader.getElementText()) - rect.x; }
		        	else if(elm.equals("message")){ message = reader.getElementText(); }
		        	else if(elm.equals("rect")){  }else{
			    		System.out.println("Local Name: " + reader.getLocalName());
			    		System.out.println("Element Text: " + reader.getElementText());
			    	}
	            }
			    if (eventType == XMLStreamConstants.END_ELEMENT) {
			    	String elm2 = reader.getLocalName();
			    	if(elm2.equals("paletteobject")){
			    		break;
			    	}
			    }
			} catch (Exception ex) {
			    if(XMLRead.readError(ex)) break;
			}
		}

        parent.addRectBtn(rect, message);
        	
	    return reader;
	}
	
	
	class PlteClass{
		int id;
		String name;
		int windowDef;
		/** PLTEのType(ハイライトの種類): -1=押している間だけ、0=押したまま残る、n=残る＋最初からn番目が点灯 */
		int hiliteType;
		/** hiliteTypeが0以外(旧XMLとの互換用。hiliteTypeが正) */
		boolean clearHilite;
		/** Hilite Mode: 0=ボタン全体を反転、1=枠だけ反転 */
		int btnType;
		int pictId;
		Point pictHV;
		
		ArrayList<plteBtnObject> objList  = new ArrayList<plteBtnObject>();
		
		public PlteClass(int id, String name, int windowDef, boolean clearHilite, int btnType, int pictId, Point pictHV){
			this.id = id;
			this.name = name;
			this.windowDef = windowDef;
			this.clearHilite = clearHilite;
			this.hiliteType = clearHilite ? -1 : 0;
			this.btnType = btnType;
			this.pictId = pictId;
			this.pictHV = pictHV;
		}

		void setHiliteType(int type){
			hiliteType = type;
			clearHilite = type!=0;
		}
		
		public void addRectBtn(Rectangle rect, String message){
			objList.add(new plteBtnObject(rect, message));
		}

		public void writePlteXMLOne(XMLStreamWriter writer) throws XMLStreamException {
			writer.writeStartElement("palette");
		    writer.writeCharacters("\n\t\t");
		    {
		    	writer.writeStartElement("id");
		        writer.writeCharacters(Integer.toString(this.id));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("name");
		        writer.writeCharacters(this.name);
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("windowdefinition");
		        writer.writeCharacters(Integer.toString(this.windowDef));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("clearhilite");
		        writer.writeCharacters(" ");
		        writer.writeEmptyElement(Boolean.toString(this.clearHilite)+" ");
		        writer.writeCharacters(" ");
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("hilitetype");
		        writer.writeCharacters(Integer.toString(this.hiliteType));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("buttontype");
		        writer.writeCharacters(this.btnType==1 ? "frame" : "solid");
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("pictureid");
		        writer.writeCharacters(Integer.toString(this.pictId));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("verticaloffset");
		        writer.writeCharacters(Integer.toString(this.pictHV.y));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		        
		    	writer.writeStartElement("horizontaloffset");
		        writer.writeCharacters(Integer.toString(this.pictHV.x));
		        writer.writeEndElement();
		        writer.writeCharacters("\n\t\t");
		
		    	for(int i=0; i<objList.size(); i++){
			    	writer.writeStartElement("paletteobject");
			        writer.writeCharacters("\n\t\t\t");
			        
			    	plteBtnObject obj = objList.get(i);

			        {
				    	writer.writeStartElement("id");
				        writer.writeCharacters(Integer.toString(i+1));
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			    	
			        Rectangle rect = obj.rect;
			        
			        {
				    	writer.writeStartElement("rect");
				        writer.writeCharacters("\n\t\t\t\t");
				    	{
					    	writer.writeStartElement("left");
					        writer.writeCharacters(Integer.toString(rect.x));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("top");
					        writer.writeCharacters(Integer.toString(rect.y));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("right");
					        writer.writeCharacters(Integer.toString(rect.x+rect.width));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t\t");
					        
					    	writer.writeStartElement("bottom");
					        writer.writeCharacters(Integer.toString(rect.y+rect.height));
					        writer.writeEndElement();
					        writer.writeCharacters("\n\t\t\t");
				    	}
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }

			        {
				    	writer.writeStartElement("message");
				        writer.writeCharacters(obj.message);
				        writer.writeEndElement();
				        writer.writeCharacters("\n\t\t\t");
			        }
			        
			        writer.writeEndElement();
			        writer.writeCharacters("\n\t\t");
		    	}
		    }
		    writer.writeEndElement();
		    writer.writeCharacters("\n");
		}
	}
	

	// XML保存
	public void writePLTEXML(XMLStreamWriter writer) throws XMLStreamException {
		ArrayList<PlteClass> plteList = this.plteList;
		for(int i=0; i<plteList.size(); i++){
			plteList.get(i).writePlteXMLOne(writer);
		}
	}
	
	
	class plteBtnObject {
		Rectangle rect;
		String message;

		public plteBtnObject(Rectangle rect, String message) {
			this.rect = rect;
			this.message = message;
		}
	}
}
