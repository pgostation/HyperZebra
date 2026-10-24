import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.CRC32;

/**
 * A program translated the way Mac programs were localized: its menus, dialog and alert items, window and control titles
 * and string lists rewritten in the target language, as a localizer did with ResEdit, with the dialog items made larger
 * where a translation needs more room (a button wider, a static text taller, the dialog with them). The program then
 * lays out and wraps the translated texts itself, instead of a translation being squeezed into the box of the original.
 *
 * The program file is never changed. The translated resources are kept in a resource file of their own, one per program
 * and language (Translations/Resources), made by "Resource Translation…" ({@link #build}) from the translations it gathered,
 * and laid over the program's resources in memory when it starts ({@link #forLaunch}). A resource the program writes
 * back to its own file goes there as the original (ResourceFile68k.Resource.fileData), unless the program changed it
 * itself; its fork is backed up first then ({@link #backUp}). Turning them off in the program's settings, or deleting
 * them, gives the original program back.
 *
 * Each translated resource carries a checksum of the original it was made from: one the program (or a newer version of
 * it) has changed since is left as it is. Texts the System cannot show (a language outside its script), that look like
 * file names or keys a program looks things up by, or whose ^0 / %d placeholders did not come through, stay original -
 * the translation of what the program shows (VMTranslation) still handles those as they are drawn.
 */
final class ResourceTranslation {
	private ResourceTranslation(){}

	/** Languages the System's own scripts can show: Japanese (a KanjiTalk System), and those Mac OS Roman holds. */
	private static final Set<String> ROMAN_LANGUAGES=new HashSet<String>(Arrays.asList("en","fr","de","es","it","pt","nl","sv","da","nb","fi"));
	private static final String META="hzTR";
	private static final int META_ID=128;
	/** The resources whose texts are translated. */
	private static final Set<String> TYPES=new HashSet<String>(Arrays.asList("MENU","cmnu","STR#","STR ","WIND","DLOG","CNTL","DITL"));
	/** The resources a program's file is recognised by (its dialogs and menus, which it does not rewrite as it runs). */
	private static final Set<String> SHAPE=new HashSet<String>(Arrays.asList("MENU","cmnu","DITL","DLOG","ALRT","WIND","CNTL"));

	static boolean supports(String to){return to!=null&&(to.startsWith("ja")||ROMAN_LANGUAGES.contains(to.split("-")[0]));}

	/** The System's script the translated resources are written in: Japanese for Japanese, else the program's own. */
	static StackTextEncoding encodingFor(String to,StackTextEncoding program){return to.startsWith("ja")?StackTextEncoding.JAPANESE:program;}

	static File directory(){return new File(VMTranslation.cacheDirectory(),"Resources");}

	// ---------------------------------------------------------------------------------------------------------------
	// Which file belongs to a program

	/**
	 * A program's mark: a checksum of its menus and dialogs, so a copy or a moved program finds its translated resources;
	 * a program without any is known by its path.
	 */
	static String fingerprint(File program,ResourceFile68k rf){
		CRC32 crc=new CRC32();int n=0;
		List<ResourceFile68k.Resource> shape=new ArrayList<ResourceFile68k.Resource>();
		for(ResourceFile68k.Resource r:rf.all())if(SHAPE.contains(ResourceFile68k.typeName(r.type)))shape.add(r);
		shape.sort(Comparator.<ResourceFile68k.Resource>comparingInt(r->r.type).thenComparingInt(r->r.id));
		for(ResourceFile68k.Resource r:shape){
			byte[] d;try{d=r.originalData();}catch(RuntimeException e){continue;}
			crc.update(ResourceFile68k.typeName(r.type).getBytes(StandardCharsets.ISO_8859_1));crc.update(r.id>>>8);crc.update(r.id);crc.update(d);n++;
		}
		if(n==0){String id=LauncherStore.identity(program);crc.update((id==null?program.getPath():id).getBytes(StandardCharsets.UTF_8));}
		return String.format("%08x",crc.getValue());
	}

	private static String suffix(String fingerprint,String to){return "-"+fingerprint+"."+to+".rsrc";}

	/** The program's translated resources into {@code to}, or null. */
	static File fileFor(File program,ResourceFile68k rf,String to){return find(fingerprint(program,rf),to);}
	private static File find(String fingerprint,String to){
		File[] files=directory().listFiles((d,n)->n.endsWith(suffix(fingerprint,to)));
		return files==null||files.length==0?null:files[0];
	}

	/** Every language the program has translated resources in (the program's settings). */
	static List<String> languages(File program){
		List<String> out=new ArrayList<String>();
		ResourceFile68k rf;try{rf=ResourceFile68k.open(program);}catch(IOException e){return out;}
		String mark="-"+fingerprint(program,rf)+".";
		File[] files=directory().listFiles((d,n)->n.contains(mark)&&n.endsWith(".rsrc"));
		if(files!=null)for(File f:files){String n=f.getName();out.add(n.substring(n.lastIndexOf(mark)+mark.length(),n.length()-5));}
		Collections.sort(out);
		return out;
	}

	/** The program runs with its translated resources from its next launch: they are on, and so is its translation. */
	static void use(File program){
		if(!LauncherStore.enabled)return;
		LauncherStore.AppSettings s=LauncherStore.get().settingsFor(program);
		if(s==null)s=new LauncherStore.AppSettings();
		s.translatedResources=null;
		if(!VMTranslation.settingsFor(program).on)s.translate=Boolean.TRUE;
		LauncherStore.get().putSettings(program,s);
	}

	/** Deletes the program's translated resources (all languages); the program runs with its own from its next launch. */
	static void delete(File program){
		ResourceFile68k rf;try{rf=ResourceFile68k.open(program);}catch(IOException e){return;}
		String mark="-"+fingerprint(program,rf)+".";
		File[] files=directory().listFiles((d,n)->n.contains(mark)&&n.endsWith(".rsrc"));
		if(files!=null)for(File f:files)f.delete();
	}

	private static String crc(byte[] d){CRC32 c=new CRC32();c.update(d);return String.format("%08x",c.getValue());}

	// ---------------------------------------------------------------------------------------------------------------
	// Making them (after translating ahead)

	/** What {@link #build} did. */
	static final class Built {
		/** Resources rewritten, texts translated, texts left original, dialogs made larger, dialogs that had no room to grow. */
		int resources,texts,kept,grown,cramped;
		File file;
		/** The System language the program has to run under for them and the one its settings choose, when they differ. */
		String needsLanguage;
		/** Why nothing was written (the language cannot be written into resources), or null. */
		String unsupported;
	}

	/**
	 * Writes the program's resources translated with what {@code t} (its translating ahead) has in the cache, replacing
	 * any made before for the language. Texts without a translation yet stay original.
	 */
	static Built build(File program,ResourceFile68k rf,VMTranslation.Translator t,StackTextEncoding programEncoding) throws IOException {
		Built built=new Built();
		String to=t.config.to;
		if(!supports(to)){built.unsupported=to;return built;}
		Builder b=new Builder(program,rf,t,programEncoding,encodingFor(to,programEncoding),built);
		Map<String,ResourceFile68k.Resource> out=b.translateAll();
		String fp=fingerprint(program,rf);
		File dir=directory();dir.mkdirs();
		File[] old=dir.listFiles((d,n)->n.endsWith(suffix(fp,to)));
		if(old!=null)for(File f:old)f.delete();
		if(out.isEmpty())return built;
		File target=new File(dir,safeName(LauncherStore.displayName(program))+suffix(fp,to)),tmp=new File(dir,target.getName()+".tmp");
		ResourceFile68k file=new ResourceFile68k(tmp);file.storage=ResourceFile68k.Storage.DATA;
		StringBuilder meta=new StringBuilder("HyperZebra translated resources 1\n");
		meta.append("program=").append(LauncherStore.displayName(program)).append('\n');
		meta.append("from=").append(t.from()).append("\nto=").append(to).append("\nengine=").append(t.config.engine).append('\n');
		meta.append("encoding=").append(b.encoding.name()).append("\nprogramEncoding=").append(programEncoding.name()).append('\n');
		meta.append("made=").append(java.time.LocalDateTime.now().withNano(0)).append('\n');
		for(ResourceFile68k.Resource r:out.values()){
			ResourceFile68k.Resource own=rf.get(r.type,r.id);
			meta.append(ResourceFile68k.typeName(r.type)).append('\t').append(r.id).append('\t').append(crc(own.originalData())).append('\n');
			file.add(r);
		}
		file.add(new ResourceFile68k.Resource(ResourceFile68k.fourcc(META),META_ID,"HyperZebra",0,meta.toString().getBytes(StandardCharsets.UTF_8)));
		file.write();
		Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
		built.file=target;built.resources=out.size();
		LauncherStore.AppSettings s=LauncherStore.enabled?LauncherStore.get().settingsFor(program):null;
		String need=systemLanguage(b.encoding);
		if(s!=null&&s.language!=null&&!s.language.equals(need))built.needsLanguage=need;
		return built;
	}

	private static String systemLanguage(StackTextEncoding e){return e==StackTextEncoding.JAPANESE?"Japanese":"English";}

	private static String safeName(String name){
		StringBuilder b=new StringBuilder();
		for(int i=0;i<name.length()&&b.length()<40;i++){char c=name.charAt(i);b.append(Character.isLetterOrDigit(c)||c==' '||c=='.'?c:'_');}
		String s=b.toString().trim();
		return s.isEmpty()?"Program":s;
	}

	/** One program's translation into resources. */
	private static final class Builder {
		final File program;final ResourceFile68k rf;final VMTranslation.Translator t;final StackTextEncoding programEncoding,encoding;final Built built;
		/** Names of the files beside the program (lower case): a string naming one is a name it looks the file up by. */
		final Set<String> nearby=new HashSet<String>();
		final Font font;final int lineHeight;

		Builder(File program,ResourceFile68k rf,VMTranslation.Translator t,StackTextEncoding programEncoding,StackTextEncoding encoding,Built built){
			this.program=program;this.rf=rf;this.t=t;this.programEncoding=programEncoding;this.encoding=encoding;this.built=built;
			File folder=program.getAbsoluteFile().getParentFile();
			if(folder!=null)collectNames(folder,2);
			font=Fonts68k.systemFont(encoding==StackTextEncoding.JAPANESE);
			int[] fi=Fonts68k.fontInfo(font);lineHeight=Math.max(1,fi[0]+fi[1]+fi[3]);
		}
		private void collectNames(File folder,int depth){
			File[] children=folder.listFiles();
			if(children==null)return;
			for(File f:children){
				String n=f.getName();
				if(n.startsWith("."))continue;
				nearby.add(LauncherStore.composed(n).toLowerCase(Locale.ROOT));
				int dot=n.lastIndexOf('.');if(dot>0)nearby.add(LauncherStore.composed(n.substring(0,dot)).toLowerCase(Locale.ROOT));
				if(depth>0&&f.isDirectory())collectNames(f,depth-1);
			}
		}

		Map<String,ResourceFile68k.Resource> translateAll(){
			Map<String,ResourceFile68k.Resource> out=new LinkedHashMap<String,ResourceFile68k.Resource>();
			List<ResourceFile68k.Resource> ditls=new ArrayList<ResourceFile68k.Resource>();
			for(ResourceFile68k.Resource r:rf.all()){
				String type=ResourceFile68k.typeName(r.type);
				if(!TYPES.contains(type)||(r.attributes&ResourceFile68k.RES_COMPRESSED)!=0)continue;
				if(type.equals("DITL")){ditls.add(r);continue;}
				byte[] d;try{d=r.originalData();}catch(RuntimeException e){continue;}
				byte[] tr;
				try{
					switch(type){
						case "MENU":tr=menu(d,4);break;
						case "cmnu":tr=menu(d,8);break;
						case "STR#":tr=stringList(d);break;
						case "STR ":tr=titleAt(d,0,VMTranslation.Kind.TEXT,true);break;
						case "WIND":tr=titleAt(d,18,VMTranslation.Kind.UI,false);break;
						case "DLOG":tr=titleAt(d,20,VMTranslation.Kind.UI,false);break;
						case "CNTL":tr=titleAt(d,22,VMTranslation.Kind.UI,false);break;
						default:tr=null;
					}
				}catch(RuntimeException e){tr=null;} // a resource that does not have the shape of its type stays as it is
				if(tr!=null&&!Arrays.equals(tr,d))out.put(r.key(),copy(r,tr));
			}
			for(ResourceFile68k.Resource r:ditls){
				try{dialog(r,out);}catch(RuntimeException e){/* left as it is */}
			}
			return out;
		}

		private static ResourceFile68k.Resource copy(ResourceFile68k.Resource r,byte[] data){
			return new ResourceFile68k.Resource(r.type,r.id,r.name,r.attributes&~(ResourceFile68k.RES_CHANGED|ResourceFile68k.RES_COMPRESSED),data);
		}

		/** The translation of a text in the System's script, or null when it stays original. */
		byte[] translate(VMTranslation.Kind kind,String text,boolean programString){
			if(VMTranslation.Piece.of(text)==null)return null;
			if(programString&&looksLikeName(text)){keep("a name",text,null);return null;}
			String tr=t.cached(kind,text);
			if(tr==null){keep("not translated",text,null);return null;}
			tr=tr.replace("\r\n","\r").replace('\n','\r'); // a Mac text's lines end with CR
			if(!keepsPlaceholders(text,tr)){keep("placeholders",text,tr);return null;}
			byte[] bytes=encode(tr,encoding);
			if(bytes==null||bytes.length>255){keep(bytes==null?"not in the script":"too long",text,tr);return null;}
			built.texts++;
			return bytes;
		}

		/** A text stays original (-Dhz68k.resourcetranslation.trace=true says which and why). */
		private void keep(String why,String text,String translation){
			built.kept++;
			if(Boolean.getBoolean("hz68k.resourcetranslation.trace"))System.err.println("[translated resources] kept ("+why+"): "+text+(translation==null?"":" -> "+translation));
		}

		/** A string the program may look a file or a setting up by: a file beside it, a path, a key, a four-letter code. */
		boolean looksLikeName(String text){
			String s=text.trim();
			if(nearby.contains(s.toLowerCase(Locale.ROOT)))return true;
			if(s.matches("(?i)(https?|ftp|file|news)://.*|(?i)mailto:.*"))return true;
			String bare=s.replaceAll("(\\.\\.\\.|…|:)$",""); // a menu item's ellipsis, a label's colon
			if(!bare.contains(" ")&&(bare.matches(".*[.:\\\\@_=<>{}\\[\\]$#|].*")||bare.startsWith("/")||bare.contains("//")))return true; // "Brightness/Contrast" is a label
			return s.matches("[A-Z0-9 #*]{4}")||text.length()==4&&text.matches("[A-Za-z0-9]+ +|[A-Z]{2,}[a-z0-9]+|[a-z]+[A-Z].*"); // a four-letter code: TIFF, PICT, "PIC ", "GIFf"
		}

		/** A pascal string at {@code at} written to {@code out}, translated when it can be; returns where the next field starts. */
		private int pascal(byte[] d,int at,VMTranslation.Kind kind,boolean programString,ByteArrayOutputStream out){
			int n=d[at]&255;
			if(at+1+n>d.length)throw new IndexOutOfBoundsException("string past the end");
			byte[] tr=n==0?null:translate(kind,programEncoding.decode(d,at+1,n),programString);
			if(tr==null)tr=kept(Arrays.copyOfRange(d,at+1,at+1+n));
			out.write(tr.length);out.write(tr,0,tr.length);
			return at+1+n;
		}

		/**
		 * A text left untranslated, in the System's script it runs under: an English program's "…" or "©" (Mac OS Roman $C9,
		 * $A9) would read as Shift-JIS lead bytes under the Japanese System its translation needs. As it was when the scripts
		 * are the same, it has no such characters or they cannot be written.
		 */
		byte[] kept(byte[] bytes){
			if(encoding==programEncoding)return bytes;
			boolean ascii=true;for(byte b:bytes)if(b<0){ascii=false;break;}
			if(ascii)return bytes;
			try{byte[] moved=encoding.encode(programEncoding.decode(bytes));return moved.length>255?bytes:moved;} // exactly (a name keeps its accents or stays as it was)
			catch(CharacterCodingException e){return bytes;}
		}

		/** A MENU (or MacApp's cmnu: {@code extra} 8, a command number after each item's 4 bytes): title and items. */
		byte[] menu(byte[] d,int extra){
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			out.write(d,0,14);
			int at=pascal(d,14,VMTranslation.Kind.MENU,false,out);
			while(at<d.length&&d[at]!=0){
				at=pascal(d,at,VMTranslation.Kind.MENU,false,out);
				if(at+extra>d.length)throw new IndexOutOfBoundsException("item past the end");
				out.write(d,at,extra);at+=extra;
			}
			out.write(d,at,d.length-at);
			return out.toByteArray();
		}

		byte[] stringList(byte[] d){
			int n=(d[0]&255)<<8|d[1]&255,at=2;
			// a list that is mostly codes and names ("pict", "tiff", "PICT.PICT"...) is a table the program looks things up in: kept whole
			int names=0,codes=0,texts=0;
			for(int i=0,p=2;i<n&&p<d.length;i++){int len=d[p]&255;String e=programEncoding.decode(d,p+1,Math.min(len,d.length-p-1));p+=1+len;
				if(VMTranslation.Piece.of(e)==null)continue;texts++;if(looksLikeName(e))names++;if(e.length()==4&&!e.contains(" "))codes++;}
			if(texts>=4&&(names*4>=texts*3||codes*4>=texts*3)){keep("a list of names",texts+" entries",null);return null;} // "pict", "tiff", "moov"...: four-letter codes
			Map<Integer,String> wrapped=wrappedLines(d);
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			out.write(d,0,2);
			for(int i=0;i<n&&at<d.length;i++){
				String share=wrapped.get(i);
				if(share==null){at=pascal(d,at,VMTranslation.Kind.TEXT,true,out);continue;}
				int len=d[at]&255;
				byte[] tr=encode(share,encoding);
				if(tr==null||tr.length>255){keep(tr==null?"not in the script":"too long",programEncoding.decode(d,at+1,len),share);tr=kept(Arrays.copyOfRange(d,at+1,at+1+len));}
				else built.texts++;
				out.write(tr.length);out.write(tr,0,tr.length);at+=1+len;
			}
			out.write(d,at,d.length-at);
			return out.toByteArray();
		}

		/**
		 * Lines of a string list the author wrapped by hand (a story's paragraphs, see VMTranslation.Translator.runEnd):
		 * index -> its share of the paragraph's translation, laid out again over the same lines at the width they take, so
		 * a longer language fills them evenly instead of running past the longest one. Paragraphs not translated whole are
		 * left out (their lines go one by one). A page with empty lines at its end lends them to the paragraphs that need
		 * more room (each line moves down; the page's last text stays last).
		 */
		private Map<Integer,String> wrappedLines(byte[] d){
			Map<Integer,String> out=new HashMap<Integer,String>();
			if(!VMTranslation.Translator.cjk(t.from()))return out;
			List<String> lines=strings(d,programEncoding);
			double widest=VMTranslation.Translator.widest(lines);
			int end=lines.size();while(end>0&&lines.get(end-1).isBlank())end--;
			List<Block> blocks=new ArrayList<Block>();
			boolean reflow=widest>=40&&end<lines.size()&&end>0;
			for(int i=0;i<end;i++){
				int j=VMTranslation.Translator.runEnd(lines,i,widest);
				Block block=block(lines,i,j);
				if(block.text==null&&(j-i>1||!lines.get(i).isBlank()))reflow=false; // a line kept as it is: the page stays as it is laid out
				blocks.add(block);
				i=j-1;
			}
			if(reflow){
				int spare=lines.size()-end;
				while(spare>0){ // the lines go to the paragraph squeezed most
					Block most=null;double worst=1;
					for(Block block:blocks){double need=block.text==null?0:block.needs(widest)/(double)block.lines;if(need>worst){worst=need;most=block;}}
					if(most==null)break;
					most.lines++;spare--;
				}
				int slot=0;
				for(Block block:blocks){block.layOut(widest,slot,out);slot+=block.lines;}
				for(;slot<lines.size();slot++)out.put(slot,lines.get(slot).isBlank()?lines.get(slot):"");
				return out;
			}
			for(Block block:blocks)if(block.end-block.start>1&&block.text!=null)block.layOut(widest,block.start,out);
			return out;
		}
		/** Lines {@code i} to {@code j} of a page with their translation as one text (null when there is none). */
		private Block block(List<String> lines,int i,int j){
			Block block=new Block(i,j,lines.subList(i,j));
			StringBuilder joined=new StringBuilder();
			for(int k=i;k<j;k++){
				VMTranslation.Piece p=VMTranslation.Piece.of(lines.get(k));
				if(p==null)return block;
				if(k==i)block.lead=p.lead;else if(k==i+1)block.next=p.lead;
				if(k==j-1)block.trail=p.trail;
				joined.append(p.core);
			}
			String first=lines.get(i);
			if(j-i==1)block.next=first.strip().matches("([0-9]{1,3})?[「『].*")?"\u3000":""; // a speech goes on under its first character
			if(j-i==1&&looksLikeName(first))return block;
			String tr=t.cached(VMTranslation.Kind.TEXT,joined.toString());
			if(tr==null||tr.equals(joined.toString()))return block;
			if(j-i==1){VMTranslation.Piece p=VMTranslation.Piece.of(first);tr=tr.strip();if(!keepsPlaceholders(p.core,tr))return block;}
			// a speaker's number the program reads off the line's start (a face to show) must stay in front
			String code=first.matches("[0-9]{1,3}\\D.*")?first.replaceAll("^([0-9]+).*","$1"):null;
			if(code!=null&&!tr.startsWith(code))return block;
			block.text=tr.replace('\n',' ').replace('\r',' ');
			return block;
		}
		/** Lines of a page that go together: one paragraph or one line. */
		private static final class Block {
			final int start,end;final List<String> original;int lines;
			String text,lead="",next="",trail="";
			Block(int start,int end,List<String> original){this.start=start;this.end=end;this.original=original;lines=end-start;}
			/** The lines its translation takes at the page's width. */
			int needs(double width){return VMTranslation.Translator.lineCount(text,width-indent());}
			/** Columns its lines start further in (a paragraph's first-line space, a speech's hanging indent). */
			double indent(){return Math.max(VMTranslation.Translator.columns(lead),VMTranslation.Translator.columns(next));}
			void layOut(double width,int slot,Map<Integer,String> out){
				if(text==null){for(int k=0;k<original.size();k++)out.put(slot+k,original.get(k));return;} // an empty line
				List<String> shares=VMTranslation.Translator.share(text,lines,width-indent());
				for(int k=0;k<lines;k++)out.put(slot+k,(k==0?lead:next)+shares.get(k)+(k==lines-1?trail:""));
			}
		}

		/** A resource with one title (a pascal string at {@code at}) and whatever follows it (a position word). */
		byte[] titleAt(byte[] d,int at,VMTranslation.Kind kind,boolean programString){
			if(d.length<=at)return null;
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			out.write(d,0,at);
			int end=pascal(d,at,kind,programString,out);
			// a window's or dialog's positioning word after the title is word-aligned: the old pad byte goes, a new one comes
			boolean align=kind==VMTranslation.Kind.UI;
			int rest=end;
			if(align&&(end&1)!=0&&(d.length-end)%2==1)rest=end+1;
			if(align&&rest<d.length&&(out.size()&1)!=0)out.write(0);
			out.write(d,rest,d.length-rest);
			return out.toByteArray();
		}

		// -----------------------------------------------------------------------------------------------------------
		// Dialog item lists: translated, then laid out again where a translation needs more room

		void dialog(ResourceFile68k.Resource r,Map<String,ResourceFile68k.Resource> out){
			byte[] d=r.originalData();
			Ditl ditl=Ditl.parse(d);
			boolean any=false;
			for(Item item:ditl.items){
				int kind=item.type&0x7f;
				if(kind==16&&item.data.length>0){byte[] k=kept(item.data);if(k!=item.data){item.data=k;any=true;}continue;} // edit text: never translated, only in the System's script
				if(kind!=4&&kind!=5&&kind!=6&&kind!=8||item.data.length==0)continue;
				String text=programEncoding.decode(item.data);
				byte[] tr=translate(VMTranslation.Kind.UI,text,false);
				if(tr==null){byte[] k=kept(item.data);if(k!=item.data){item.data=k;any=true;}continue;}
				item.original=text;item.translated=encoding.decode(tr);item.data=tr;any=true;
				item.pad=kind==4?16:kind==8?0:22;
			}
			for(Item item:ditl.items){ // a control item from a 'CNTL' (moved to the item's rectangle when the dialog is made): its title as translated there
				if((item.type&0x7f)!=7||item.data.length<2)continue;
				ResourceFile68k.Resource own=rf.get(ResourceFile68k.fourcc("CNTL"),s2(item.data,0));
				ResourceFile68k.Resource done=own==null?null:out.get(own.key());
				if(done==null)continue;
				byte[] od=own.originalData(),td=done.data();
				int proc=s2(od,18);
				if(od.length<=22||td.length<=22||(proc&~8)>2)continue; // buttons, check boxes and radio buttons (useWFont or not) only
				item.original=programEncoding.decode(od,23,Math.min(od[22]&255,od.length-23));item.translated=encoding.decode(td,23,Math.min(td[22]&255,td.length-23));
				item.pad=(proc&7)==0?16:22;any=true;
			}
			if(!any)return;
			Font[] fonts=itemFonts(r.id,ditl.items.size());
			for(int k=0;k<fonts.length;k++)ditl.items.get(k).font=fonts[k];
			// the windows the list is shown in: their sizes grow with it
			List<ResourceFile68k.Resource> windows=new ArrayList<ResourceFile68k.Resource>();
			for(ResourceFile68k.Resource w:rf.all()){
				String type=ResourceFile68k.typeName(w.type);
				byte[] wd;try{wd=w.originalData();}catch(RuntimeException e){continue;}
				if(type.equals("DLOG")&&wd.length>=20&&s2(wd,18)==r.id||type.equals("ALRT")&&wd.length>=10&&s2(wd,8)==r.id)windows.add(w);
			}
			int[] size=null;
			if(!windows.isEmpty()){byte[] wd=windows.get(0).originalData();size=new int[]{s2(wd,6)-s2(wd,2),s2(wd,4)-s2(wd,0)};}
			int[] grown=new Layout(ditl,size).run();
			out.put(r.key(),copy(r,ditl.build()));
			if(grown!=null&&size!=null&&(grown[0]!=size[0]||grown[1]!=size[1])){
				built.grown++;
				for(ResourceFile68k.Resource w:windows){ // bounds: top, left, bottom, right
					ResourceFile68k.Resource done=out.get(w.key());
					byte[] wd=(done!=null?done.data():w.originalData()).clone();
					put2(wd,4,s2(wd,4)+grown[1]-size[1]);
					put2(wd,6,s2(wd,6)+grown[0]-size[0]);
					out.put(w.key(),copy(w,wd));
				}
			}
		}

		/** Where a translation needs more room than its item: buttons and boxes wider, static text taller, the window with them. */
		final class Layout {
			final boolean window;int width,height;
			/** The items laid out: those in the window. Items the program keeps outside it (shown when it makes the window larger
			 *  itself, as GraphicConverter's GIF options do) stay where they are and do not make the window grow. */
			final List<Item> items=new ArrayList<Item>(),outside=new ArrayList<Item>();
			Layout(Ditl ditl,int[] size){
				window=size!=null;
				int right=0,bottom=0;for(Item i:ditl.items){right=Math.max(right,i.right);bottom=Math.max(bottom,i.bottom);}
				width=window?size[0]:right;height=window?size[1]:bottom;
				for(Item i:ditl.items)(i.left<width&&i.top<height&&i.right>0&&i.bottom>0?items:outside).add(i);
			}

			/** The new {width, height} of the window, or null when the items stay where they are. */
			int[] run(){
				int[][] before=new int[items.size()][];
				for(int k=0;k<items.size();k++)before[k]=items.get(k).rect();
				int minLeft=Integer.MAX_VALUE,maxRight=0,maxBottom=0;
				for(Item i:items){minLeft=Math.min(minLeft,i.left);maxRight=Math.max(maxRight,i.right);maxBottom=Math.max(maxBottom,i.bottom);}
				int leftMargin=Math.max(4,minLeft),rightMargin=Math.max(4,width-maxRight),bottomMargin=Math.max(4,height-maxBottom);
				int w0=width,h0=height;
				// a user item framing a button (the default button's outline drawn around it) goes with the button
				Map<Item,Item> frames=new IdentityHashMap<Item,Item>();
				for(Item u:items)if((u.type&0x7f)==0)for(Item b:items)if(b.pad>0&&b!=u&&u.left<=b.left&&u.top<=b.top&&u.right>=b.right&&u.bottom>=b.bottom
					&&b.left-u.left<=8&&b.top-u.top<=8&&u.right-b.right<=8&&u.bottom-b.bottom<=8){frames.put(u,b);break;}
				Map<Item,int[]> framedAt=new IdentityHashMap<Item,int[]>();
				for(Map.Entry<Item,Item> e:frames.entrySet())framedAt.put(e.getKey(),e.getValue().rect());
				// both ways; where that would overlap, only taller texts, then only wider buttons; else the items stay where they
				// were (the text wraps or is cut there)
				for(int way=0;way<3;way++){
					for(int k=0;k<items.size();k++)items.get(k).set(before[k]);
					width=w0;height=h0;
					if(way!=1)widen(rightMargin,leftMargin);
					if(way!=2)heighten(rightMargin,bottomMargin);
					for(Map.Entry<Item,Item> e:frames.entrySet()){
						Item u=e.getKey(),b=e.getValue();int[] was=framedAt.get(u),k=before[items.indexOf(u)];
						u.top=b.top-(was[0]-k[0]);u.left=b.left-(was[1]-k[1]);u.bottom=b.bottom+(k[2]-was[2]);u.right=b.right+(k[3]-was[3]);
					}
					if(fits(before))break;
					if(way==2){
						built.cramped++;
						for(int k=0;k<items.size();k++)items.get(k).set(before[k]);
						return null;
					}
				}
				return width==w0&&height==h0&&same(before)?null:new int[]{width,height};
			}
			private boolean same(int[][] before){for(int k=0;k<items.size();k++)if(!Arrays.equals(before[k],items.get(k).rect()))return false;return true;}

			/** Buttons, check boxes and radio buttons as wide as their titles: those from the left half grow rightwards, pushing
			 *  what is right of them in their row, those in the right half leftwards; the window widens when the two sides meet. */
			private void widen(int rightMargin,int leftMargin){
				int middle=width/2;
				Map<Item,Boolean> right=new IdentityHashMap<Item,Boolean>();
				Map<Item,int[]> was=new IdentityHashMap<Item,int[]>();
				for(Item i:items){right.put(i,i.left>=middle);was.put(i,i.rect());} // right-anchored: wholly in the right half (OK and Cancel); a row across the middle is anchored left
				List<Item> grow=new ArrayList<Item>();
				Map<Item,Integer> by=new IdentityHashMap<Item,Integer>();
				for(Item i:items){
					if(i.translated==null||i.pad==0)continue;
					int pad=i.pad;
					int d=Fonts68k.width(font,i.translated)+pad-(i.right-i.left); // also where the original was too wide already: under the Japanese System its texts are wider
					if(d>0){grow.add(i);by.put(i,d);}
				}
				if(grow.isEmpty())return;
				List<Item> leftSide=new ArrayList<Item>(),rightSide=new ArrayList<Item>();
				for(Item i:grow)(right.get(i)?rightSide:leftSide).add(i);
				leftSide.sort(Comparator.comparingInt(i->i.left));
				rightSide.sort(Comparator.comparingInt(i->-i.right));
				for(Item i:leftSide){
					int d=by.get(i),edge=i.right;i.right+=d;
					for(Item o:items)if(o!=i&&!right.get(o)&&band(o,i)&&o.left>=edge-2){o.left+=d;o.right+=d;}
				}
				for(Item i:rightSide){
					int d=by.get(i),edge=i.left;i.left-=d;
					for(Item o:items)if(o!=i&&right.get(o)&&band(o,i)&&o.right<=edge+2){o.left-=d;o.right-=d;}
				}
				// where a left item now runs into a right one of its row, the right side moves over and the window widens
				int over=0;
				for(Item a:items)if(!right.get(a))for(Item b:items)if(right.get(b)&&band(a,b)){
					int[] ra=was.get(a),rb=was.get(b);
					if(ra[3]>rb[1])continue; // they overlapped already
					int gap=Math.max(4,Math.min(rb[1]-ra[3],12));
					over=Math.max(over,a.right+gap-b.left);
				}
				if(over>0&&window){for(Item b:items)if(right.get(b)){b.left+=over;b.right+=over;}width+=over;}
				if(!window)return;
				int minLeft=Integer.MAX_VALUE;for(Item i:items)minLeft=Math.min(minLeft,i.left);
				if(minLeft<leftMargin){int d=leftMargin-minLeft;for(Item i:items){i.left+=d;i.right+=d;}width+=d;}
				int maxRight=0;for(Item i:items)maxRight=Math.max(maxRight,i.right);
				if(maxRight>width-rightMargin)width=maxRight+rightMargin;
			}

			/** Static texts as tall as their wrapped translations: wider first where the row has room, then taller - into the
			 *  room below, then moving what is below down and the window with it. */
			private void heighten(int rightMargin,int bottomMargin){
				List<Item> sorted=new ArrayList<Item>(items);
				sorted.sort(Comparator.comparingInt(i->i.top));
				for(Item i:sorted){
					if(i.translated==null||(i.type&0x7f)!=8)continue;
					String tr=measured(i.translated);
					Font f=i.font!=null?i.font:font;int lh=i.font!=null?lineHeight(f):lineHeight;
					int had=i.bottom-i.top; // the room it has (the original may not fit either: the Japanese System's lines are taller)
					int need=lines(tr,i.right-i.left,f)*lh;
					if(need<=had)continue;
					int free=window?width-rightMargin:width;
					for(Item o:items)if(o!=i&&band(o,i)&&o.left>=i.right-2)free=Math.min(free,o.left-4);
					if(free>i.right){
						int widest=i.left+longestLine(tr,f)+4,to=Math.min(free,widest);
						if(to>i.right&&lines(tr,to-i.left,f)<lines(tr,i.right-i.left,f)){
							i.right=to;need=lines(tr,i.right-i.left,f)*lh;
							if(need<=had)continue;
						}
					}
					int d=need-had;
					int room=window?height-bottomMargin:height;
					for(Item o:items)if(o!=i&&across(o,i)&&o.top>=i.bottom-2)room=Math.min(room,o.top-4);
					int use=Math.max(0,Math.min(d,room-i.bottom));
					int edge=i.bottom;
					i.bottom+=use;d-=use;
					if(d>0&&window){
						for(Item o:items)if(o!=i&&o.top>=edge-2){o.top+=d;o.bottom+=d;}
						i.bottom+=d;height+=d;
					}
				}
			}

			/** No two items overlap that did not before, the items stay inside the window, and it is not larger than a screen. */
			private boolean fits(int[][] before){
				if(width>1000||height>700)return false;
				for(Item o:outside)if(o.left<width&&o.top<height&&o.right>0&&o.bottom>0)return false; // the larger window would show them
				for(int a=0;a<items.size();a++){
					Item i=items.get(a);
					if(i.left<0||i.top<0||i.right>width||i.bottom>height){int[] b=before[a];if(!(b[1]<0||b[0]<0||b[3]>width||b[2]>height))return false;}
					for(int c=a+1;c<items.size();c++){
						Item o=items.get(c);
						if(overlap(i.rect(),o.rect())&&!overlap(before[a],before[c]))return false;
					}
				}
				return true;
			}
		}

		/**
		 * The font each item of a list is drawn in, as DialogManager68k styles static text: the list's 'ictb' (per item TextEdit
		 * style flags and a style record) or its 'dftb' (Appearance font table); null for the system font.
		 */
		Font[] itemFonts(int id,int count){
			Font[] out=new Font[count];
			boolean japanese=encoding==StackTextEncoding.JAPANESE;
			ResourceFile68k.Resource ictb=rf.get(ResourceFile68k.fourcc("ictb"),id);
			if(ictb!=null)try{
				byte[] d=ictb.originalData();
				for(int i=0;i<count&&i*4+4<=d.length;i++){
					int flags=u2(d,i*4),offset=u2(d,i*4+2);
					if(flags==0||(flags&7)==0||offset+6>d.length)continue;
					int font=(flags&1)!=0?s2(d,offset):0;
					if((flags&0x8000)!=0){int at=font&0xffff;font=at<d.length?Fonts68k.numberOf(programEncoding.decode(d,at+1,Math.min(d[at]&255,d.length-at-1))):0;}
					out[i]=styled(font,(flags&4)!=0?s2(d,offset+4):12,(flags&2)!=0?u2(d,offset+2)&0xff:0);
				}
			}catch(RuntimeException ignored){}
			ResourceFile68k.Resource dftb=rf.get(ResourceFile68k.fourcc("dftb"),id);
			if(dftb!=null)try{
				byte[] d=dftb.originalData();
				if(d.length<4||u2(d,0)!=0)return out;
				int n=u2(d,2),at=4,small=japanese?Fonts68k.JAPANESE_SYSTEM_FONT:Fonts68k.numberOf("Geneva");
				for(int i=0;i<n&&at+2<=d.length;i++){
					int type=u2(d,at);at+=2;if(type!=1)continue;if(at+25>d.length)break;
					int flags=u2(d,at),font=s2(d,at+2),size=s2(d,at+4),face=u2(d,at+6),nameLength=d[at+24]&255;
					String name=nameLength==0?null:programEncoding.decode(d,at+25,Math.min(nameLength,d.length-at-25));at+=25+nameLength;
					if(i>=count||out[i]!=null)continue;
					int[] f={0,12,0};boolean meta=false;
					if((flags&0x80)!=0){meta=true;f=font==1||font==6?new int[]{small,10,0}:font==2?new int[]{small,10,1}:font==3?new int[]{small,japanese?12:10,0}:font==4?new int[]{0,12,1}:font==5?new int[]{small,12,0}:f;}
					else if((flags&1)!=0){if(font<0){meta=true;f=font==-2?new int[]{small,10,0}:font==-3?new int[]{small,10,1}:font==-4?new int[]{small,japanese?12:10,0}:f;}else f[0]=font;}
					if(name!=null&&(flags&1)!=0&&!meta)f[0]=Fonts68k.numberOf(name);
					if(!meta||(flags&0x200)!=0){if((flags&0x100)!=0)f[1]+=size;else if((flags&4)!=0&&size>0)f[1]=size;if((flags&2)!=0)f[2]=face&0x7f;}
					out[i]=styled(f[0],f[1],f[2]);
				}
			}catch(RuntimeException ignored){}
			return out;
		}
		/** A font as the program's System has it (a Japanese System's 0, 1 and 3 are Osaka); null for the system font itself. */
		private Font styled(int id,int size,int face){
			if(size<=0)size=12;
			if(id==0&&size==12&&face==0)return null;
			if(encoding==StackTextEncoding.JAPANESE&&(id==0||id==1||id==3))id=Fonts68k.JAPANESE_SYSTEM_FONT;
			return Fonts68k.font(id,size,face);
		}
		private static int lineHeight(Font f){int[] fi=Fonts68k.fontInfo(f);return Math.max(1,fi[0]+fi[1]+fi[3]);}

		/** A dialog text measured with its placeholders (^0-^3) standing for a short name, as the original's are. */
		private static String measured(String text){return text.replaceAll("\\^[0-3]","XXXXXXXX");}

		/** How many lines TextBox breaks a text into at {@code width}: as TextEdit68k does, after a space or a two-byte character. */
		int lines(String text,int width,Font font){
			boolean japanese=encoding==StackTextEncoding.JAPANESE;
			int count=1,start=0,lastBreak=-1;
			for(int i=0;i<text.length();){
				char c=text.charAt(i);
				if(c=='\r'||c=='\n'){count++;i++;start=i;lastBreak=-1;continue;}
				if(Fonts68k.width(font,text.substring(start,i+1))>Math.max(8,width)&&i>start){
					int at=lastBreak>start?lastBreak:i;
					start=at;i=at;lastBreak=-1;count++;continue;
				}
				if(c==' ')lastBreak=i+1;else if(japanese&&c>=0x80&&!(c>=0xff61&&c<=0xff9f))lastBreak=i+1;
				i++;
			}
			return count;
		}
		private int longestLine(String text,Font font){int w=0;for(String line:text.split("[\r\n]"))w=Math.max(w,Fonts68k.width(font,line));return w;}
	}

	private static boolean band(Item a,Item b){return a.top<b.bottom&&b.top<a.bottom;}
	private static boolean across(Item a,Item b){return a.left<b.right&&b.left<a.right;}
	private static boolean overlap(int[] a,int[] b){return a[0]<b[2]&&b[0]<a[2]&&a[1]<b[3]&&b[1]<a[3];}

	/** One item of a 'DITL': its rectangle (top, left, bottom, right), type and data, the texts when it is translated. */
	static final class Item {
		byte[] head;int top,left,bottom,right;int type;byte[] data;
		String original,translated;
		/** The font its text is drawn in ('ictb'/'dftb' of the list), null: the system font. */
		Font font;
		/** Room a button-like item needs beside its title (0: not one): a push button's ends, a box or circle before it. */
		int pad;
		int[] rect(){return new int[]{top,left,bottom,right};}
		void set(int[] r){top=r[0];left=r[1];bottom=r[2];right=r[3];}
	}

	static final class Ditl {
		final List<Item> items=new ArrayList<Item>();
		byte[] tail=new byte[0];
		static Ditl parse(byte[] d){
			Ditl ditl=new Ditl();
			int n=s2(d,0)+1,at=2;
			for(int i=0;i<n&&at+14<=d.length;i++){
				Item item=new Item();
				item.head=Arrays.copyOfRange(d,at,at+4);
				item.top=s2(d,at+4);item.left=s2(d,at+6);item.bottom=s2(d,at+8);item.right=s2(d,at+10);
				item.type=d[at+12]&255;
				int len=d[at+13]&255;
				if(at+14+len>d.length)throw new IndexOutOfBoundsException("item past the end");
				item.data=Arrays.copyOfRange(d,at+14,at+14+len);
				ditl.items.add(item);
				at+=14+len+(len&1);
			}
			if(at<d.length)ditl.tail=Arrays.copyOfRange(d,at,d.length);
			return ditl;
		}
		byte[] build(){
			ByteArrayOutputStream out=new ByteArrayOutputStream();
			int n=items.size()-1;out.write(n>>8);out.write(n);
			for(Item i:items){
				out.write(i.head,0,4);
				for(int v:new int[]{i.top,i.left,i.bottom,i.right}){out.write(v>>8);out.write(v);}
				out.write(i.type);out.write(i.data.length);out.write(i.data,0,i.data.length);
				if((i.data.length&1)!=0)out.write(0);
			}
			out.write(tail,0,tail.length);
			return out.toByteArray();
		}
	}

	private static int u2(byte[] d,int at){return (d[at]&255)<<8|d[at+1]&255;}
	private static int s2(byte[] d,int at){return (short)((d[at]&255)<<8|d[at+1]&255);}
	private static void put2(byte[] d,int at,int v){d[at]=(byte)(v>>8);d[at+1]=(byte)v;}

	/** The text in the System's script; letters it has no accented forms of lose their accents; null when it cannot be written. */
	static byte[] encode(String text,StackTextEncoding encoding){
		text=Normalizer.normalize(text,Normalizer.Form.NFC); // バ as one character: a decomposed ハ + ゛ has no Shift-JIS form
		try{return encoding.encode(text);}catch(CharacterCodingException e){/* below */}
		StringBuilder b=new StringBuilder();
		String nfd=Normalizer.normalize(text,Normalizer.Form.NFD);
		for(int i=0;i<nfd.length();i++){ // accents off Latin letters only (never a kana's voicing mark)
			char c=nfd.charAt(i);
			if(Character.getType(c)==Character.NON_SPACING_MARK&&b.length()>0&&Character.UnicodeScript.of(b.charAt(b.length()-1))==Character.UnicodeScript.LATIN)continue;
			b.append(c);
		}
		String plain=Normalizer.normalize(b.toString(),Normalizer.Form.NFC);
		if(encoding==StackTextEncoding.JAPANESE)plain=plain.replace('•','・').replace("®","(R)").replace("™","(TM)").replace('·','・'); // KanjiTalk's own forms
		try{return encoding.encode(plain);}catch(CharacterCodingException e){return null;}
	}

	private static final java.util.regex.Pattern PRINTF=java.util.regex.Pattern.compile("%[-+ #0]*\\d*(?:\\.\\d+)?[hlL]?[diouxXeEfgGcsp%]");
	/** Whether the translation kept the original's ^0-^3 (ParamText), printf conversions and AppendMenu separators. */
	static boolean keepsPlaceholders(String original,String translation){
		for(String p:new String[]{"^0","^1","^2","^3"})if(count(original,p)!=count(translation,p))return false;
		if(count(original,";")>0&&count(original,";")!=count(translation,";"))return false;
		return printf(original).equals(printf(translation));
	}
	private static int count(String s,String part){int n=0;for(int i=s.indexOf(part);i>=0;i=s.indexOf(part,i+part.length()))n++;return n;}
	private static List<String> printf(String s){
		List<String> out=new ArrayList<String>();
		java.util.regex.Matcher m=PRINTF.matcher(s);
		while(m.find())out.add(m.group());
		Collections.sort(out);
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Using them (a program starts)

	/** The translated resources laid over a running program's. */
	static final class Overlay {
		final File file;final String to;final StackTextEncoding encoding;
		/** The translated texts (the live translation leaves them alone), and how many resources were laid over. */
		final List<String> texts=new ArrayList<String>();
		int resources;
		/**
		 * A translated line of a page of text (a string list of lines the author wrapped, like a story) -> the longest
		 * original line of its list: the width the program leaves for its lines, the drawing squeezes a longer one into it.
		 * Also under the line without its first 1-3 characters (a speaker's number the program draws as a face).
		 */
		final Map<String,String> lineBoxes=new HashMap<String,String>();
		Overlay(File file,String to,StackTextEncoding encoding){this.file=file;this.to=to;this.encoding=encoding;}
		/** The longest original line of the page a drawn translated line comes from, or null. */
		String lineBox(String drawn){return drawn==null||lineBoxes.isEmpty()?null:lineBoxes.get(drawn);}
		void addPage(List<String> original,List<String> translated){
			double widest=VMTranslation.Translator.widest(original);
			if(widest<40||original.size()!=translated.size())return; // names, labels: not lines of a page
			String box=""; // a speaker's number before a line is not drawn (the program shows a face instead)
			for(String o:original){o=o.replaceFirst("^[0-9]{1,3}(?=\\D)","");if(VMTranslation.Translator.columns(o)>VMTranslation.Translator.columns(box))box=o;}
			for(int i=0;i<translated.size();i++){
				String tr=translated.get(i);
				if(tr.equals(original.get(i)))continue;
				for(int k=0;k<=3&&tr.length()-k>=4;k++)lineBoxes.putIfAbsent(tr.substring(k),box);
			}
		}
		/** The language of the System the program runs under with them ("English"/"Japanese"). */
		String systemLanguage(){return ResourceTranslation.systemLanguage(encoding);}
	}

	/**
	 * Lays the program's translated resources over {@code rf} when it is translated, it has some for the language it is
	 * translated into and its settings use them; null otherwise (nothing changed). The program's System must be in their
	 * script: a System language chosen for the program that differs keeps them off.
	 */
	static Overlay forLaunch(File program,ResourceFile68k rf,LauncherStore.AppSettings settings){
		if("off".equals(System.getProperty("hz68k.resourcetranslation"))||program==null||rf==null)return null;
		if(settings!=null&&Boolean.FALSE.equals(settings.translatedResources))return null;
		VMTranslation.Config c=VMTranslation.settingsFor(program);
		if(!c.on)return null;
		File file;try{file=fileFor(program,rf,c.to);}catch(RuntimeException e){return null;}
		if(file==null)return null;
		try{
			ResourceFile68k translated=ResourceFile68k.openDataFork(file);
			ResourceFile68k.Resource m=translated.get(ResourceFile68k.fourcc(META),META_ID);
			if(m==null)return null;
			Map<String,String> meta=new HashMap<String,String>(),sums=new HashMap<String,String>();
			for(String line:new String(m.data(),StandardCharsets.UTF_8).split("\n")){
				int eq=line.indexOf('=');String[] f=line.split("\t");
				if(f.length==3)sums.put(f[0]+"\t"+f[1],f[2]);else if(eq>0)meta.put(line.substring(0,eq),line.substring(eq+1));
			}
			StackTextEncoding encoding=encodingNamed(meta.get("encoding")),programEncoding=encodingNamed(meta.get("programEncoding"));
			if(encoding==null||programEncoding==null)return null;
			String forced=System.getProperty("hz68k.language");
			String chosen=forced!=null?(Toolbox68k.applicationEncoding()==StackTextEncoding.JAPANESE?"Japanese":"English"):settings!=null?settings.language:null;
			if(chosen!=null&&!chosen.equals(systemLanguage(encoding))){
				System.err.println("[68K] translated resources "+file.getName()+" need a "+systemLanguage(encoding)+" System; the program's is "+chosen+": not used");
				return null;
			}
			Overlay overlay=new Overlay(file,c.to,encoding);
			for(ResourceFile68k.Resource r:translated.all()){
				if(r.type==ResourceFile68k.fourcc(META))continue;
				ResourceFile68k.Resource own=rf.get(r.type,r.id);
				String sum=sums.get(ResourceFile68k.typeName(r.type)+"\t"+r.id);
				if(own==null||sum==null||own.original!=null||!sum.equals(crc(own.data())))continue; // changed since it was translated
				List<String> was=new ArrayList<String>(),now=new ArrayList<String>();
				VMTranslation.resourceStrings(own,programEncoding,was);
				if(r.type==ResourceFile68k.fourcc("STR#"))overlay.addPage(strings(own.data(),programEncoding),strings(r.data(),encoding));
				own.translate(r.data());
				VMTranslation.resourceStrings(new ResourceFile68k.Resource(r.type,r.id,null,0,r.data()),encoding,now);
				for(String text:now)if(!was.contains(text))overlay.texts.add(text);
				overlay.resources++;
			}
			if(overlay.resources==0)return null;
			if(Mac68kApi.TRACE)System.err.println("[68K] translated resources "+file.getName()+": "+overlay.resources+" resources ("+systemLanguage(encoding)+" System)");
			return overlay;
		}catch(IOException|RuntimeException e){
			System.err.println("[68K] translated resources "+file.getName()+" cannot be read: "+e);
			return null;
		}
	}
	/** A string list's entries. */
	private static List<String> strings(byte[] d,StackTextEncoding encoding){
		List<String> out=new ArrayList<String>();
		int n=d.length<2?0:(d[0]&255)<<8|d[1]&255;
		for(int i=0,p=2;i<n&&p<d.length;i++){int len=d[p]&255;out.add(encoding.decode(d,p+1,Math.min(len,d.length-p-1)));p+=1+len;}
		return out;
	}
	private static StackTextEncoding encodingNamed(String name){
		if(name==null)return null;
		try{return StackTextEncoding.valueOf(name);}catch(IllegalArgumentException e){return null;}
	}

	/** Programs whose own file was backed up already in this session. */
	private static final Set<String> backedUp=Collections.synchronizedSet(new HashSet<String>());

	/**
	 * The program is about to write a translated resource it changed itself into its own file: the fork as it is on the
	 * disk now is copied to Translations/Resources/Backups first (once a session), so the original texts can be had back.
	 */
	static void backUp(ResourceFile68k file){
		if(file.file==null||!backedUp.add(LauncherStore.identity(file.file)))return;
		try{
			byte[] fork=ResourceFile68k.readForkBytes(file.file,null);
			if(fork==null)return;
			File dir=new File(directory(),"Backups");dir.mkdirs();
			String stamp=java.time.LocalDateTime.now().withNano(0).toString().replace(':','-');
			File copy=new File(dir,safeName(file.file.getName())+" "+stamp+".rsrc");
			Files.write(copy.toPath(),fork);
			System.err.println("[68K] "+file.file.getName()+" changes a translated resource of its own: its resources were backed up to "+copy);
		}catch(IOException e){System.err.println("[68K] cannot back up the resources of "+file.file.getName()+": "+e.getMessage());}
	}
}
