import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Exposes a HyperZebra stack's converted resource folder to 68K code as a
 * classic resource file. Entries are read lazily from the per-resource files,
 * so the shared {@link ResourceManager68k} serves XCMDs (stack resources) and
 * applications (real resource forks) through one implementation.
 */
final class Mac68kResourceManager {
	private Mac68kResourceManager(){}

	/** A read-only resource file over the stack's resources (byte-exact imported data, 68K externals, PICT data). */
	static ResourceFile68k forStack(OStack stack){
		File own=stack==null?null:(stack.file!=null?stack.file:stack.path==null?null:new File(stack.path));
		//a stack converted from a HyperCard file is that file to its externals (PBGetFCBInfo of CurResFile names it and its folder,
		//as HyperCard's open stack: HyperTint locates itself this way); the resources are the conversion's
		File raw=own==null?null:FileManager68k.convertedStackFile(own.isDirectory()?own:own.getParentFile());
		//a stack made in HyperZebra (no HyperCard file beside it) is the file "the long name" names: its .hzb without the extension
		//(OpenResFile of that path finds this file, isStackPath), so an external that asks PBGetFCBInfo where its stack is gets the
		//stack's long name, not "<stack>.hzb:_stack.xml" (Dartmouth's ResInStack compares the two before replacing a resource)
		ResourceFile68k file=new ResourceFile68k(raw!=null?raw:hyperCardSeen(own));
		//what externals add or change here goes into the stack (storeChanges), as into HyperCard's open stack: a stack that cannot be
		//modified is read-only to them too
		file.writable=stack!=null&&stack.rsrc!=null&&stack.file!=null&&!stack.cantModify;
		file.stack=file.writable?stack:null;
		file.stackOrigin=new java.util.IdentityHashMap<ResourceFile68k.Resource,Object[]>();
		if(stack==null||stack.rsrc==null)return file;
		List<ResourceFile68k.Resource> local=new ArrayList<ResourceFile68k.Resource>();
		for(Rsrc.rsrcClass resource:stack.rsrc.rsrcIdMap.values()){
			int type=classicType(resource.type);
			if(type!=0&&isClassicResourceBytes(resource.type,resource.filename)){
				int prefix=resource.type.equals("PICT")?512:0;
				ResourceFile68k.Resource r=new ResourceFile68k.Resource(type,resource.id,vmName(stack,resource.name),resourceFile(stack,resource.filename),prefix);
				r.attributes=resource.attributes&Rsrc.KEPT_ATTRIBUTES; // what the HyperCard file said (SendAppleEvent wants its PROC resSysHeap)
				local.add(r);
			}
		}
		// the PowerPC halves too, as 'xcmd'/'xfcn' (the stack's resource fork has them: XTernal Collection's FullResList listed
		// none of its 46 and CopyRes3 "xcmd" found nothing to copy)
		for(Rsrc.xcmdClass resource:stack.rsrc.getXcmdResources()){
			String platform=resource.platform==null?"":resource.platform.toLowerCase();boolean ppc=!platform.contains("68k")&&platform.contains("ppc");
			int type=resource.type.equalsIgnoreCase("command")?fourcc(ppc?"xcmd":"XCMD"):resource.type.equalsIgnoreCase("function")?fourcc(ppc?"xfcn":"XFCN"):0;
			if(type!=0&&(ppc||platform.contains("68k"))){
				ResourceFile68k.Resource r=new ResourceFile68k.Resource(type,resource.id,vmName(stack,resource.name),resourceFile(stack,resource.filename),0);
				r.attributes=resource.attributes&Rsrc.KEPT_ATTRIBUTES;local.add(r);}
		}
		// in the stack's resource map order, which Rsrc keeps from the HyperCard file (not by ID): Get1IndResource counts in it, as
		// on the Mac (UxMovie's UxGetMyMovieList listed the 'myMV' movies World, Sakana, Fan… sorted; the author's Mac: Sakana, UDI…)
		for(ResourceFile68k.Resource r:local){file.add(r);file.stackOrigin.put(r,origin(r));}
		file.dirty=false;
		return file;
	}

	/**
	 * What externals did to a stack's resource file ({@link #forStack}) since it was made or last stored goes into the stack, as
	 * HyperCard writes its open stack's resource fork: resources added or changed are stored like imported ones (MtMakeIcon's
	 * ICON is then a button icon, MtMakePictRsrc's PICT a picture by name), renumbered or removed ones leave it. {@code data}
	 * gives a resource's bytes as the program has them. True when the stack changed.
	 */
	static boolean storeChanges(ResourceFile68k file,java.util.function.Function<ResourceFile68k.Resource,byte[]> data){
		OStack stack=file.stack;
		if(stack==null||stack.rsrc==null||stack.file==null||file.stackOrigin==null)return false;
		boolean any=false;
		// a resource stored again (changed, renumbered or renamed) keeps its place in the stack's resource map, as SetResInfo and
		// ChangedResource leave it in the Mac's: the order Get1IndResource counts in does not change
		java.util.Map<ResourceFile68k.Resource,Integer> place=new java.util.IdentityHashMap<ResourceFile68k.Resource,Integer>();
		for(java.util.Iterator<java.util.Map.Entry<ResourceFile68k.Resource,Object[]>> it=file.stackOrigin.entrySet().iterator();it.hasNext();){
			java.util.Map.Entry<ResourceFile68k.Resource,Object[]> e=it.next();ResourceFile68k.Resource r=e.getKey();Object[] was=e.getValue();
			int type=(Integer)was[0],id=(Integer)was[1];
			boolean gone=file.get(r.type,r.id)!=r,moved=!gone&&(r.id!=id||!java.util.Objects.equals(r.name,was[2]));
			// SetResAttrs alone: the stack keeps the new attributes, the bytes stay where they are
			if(!gone&&!moved&&!r.changed&&attributesOf(r)!=(Integer)was[3]){setStackAttributes(stack,ResourceFile68k.typeName(type),id,attributesOf(r));e.setValue(origin(r));any=true;continue;}
			if(!gone&&!moved)continue;
			if(moved)place.put(r,stack.rsrc.indexOf(hzType(ResourceFile68k.typeName(type)),id));
			removeFromStack(stack,type,id);it.remove();any=true; // a renumbered or renamed one is stored again below
		}
		for(ResourceFile68k.Resource r:file.all()){
			if(file.stackOrigin.containsKey(r)&&!r.changed)continue;
			byte[] bytes=data.apply(r);if(bytes==null)continue;
			String type=ResourceFile68k.typeName(r.type);
			if(file.stackOrigin.containsKey(r)||stackHas(stack,type,r.id)){
				if(!place.containsKey(r))place.put(r,stack.rsrc.indexOf(hzType(type),r.id));
				removeFromStack(stack,r.type,r.id);
			}
			HCResource.storeResource(stack,bytes,type,r.id,stackName(stack,r.name),attributesOf(r));
			Integer at=place.get(r);if(at!=null&&at>=0)stack.rsrc.moveTo(hzType(type),r.id,at);
			file.stackOrigin.put(r,origin(r));r.changed=false;any=true;
		}
		file.dirty=false;
		if(any)stack.changed=true;
		return any;
	}
	/** What a resource was when it came from (or went into) the stack: type, ID, name, kept attributes. */
	private static Object[] origin(ResourceFile68k.Resource r){return new Object[]{r.type,r.id,r.name,attributesOf(r)};}
	private static int attributesOf(ResourceFile68k.Resource r){return r.attributes&Rsrc.KEPT_ATTRIBUTES;}
	private static void setStackAttributes(OStack stack,String type,int id,int attributes){
		if(Rsrc.isExternalMacType(type)){Rsrc.xcmdClass x=stack.rsrc.getExternal(type,id);if(x!=null)x.attributes=attributes;}
		else{Rsrc.rsrcClass r=stack.rsrc.getResource1(id,HCResource.storedType(type));if(r!=null)r.attributes=attributes;}
		stack.changed=true;
	}
	private static boolean stackHas(OStack stack,String type,int id){
		if(type.equalsIgnoreCase("XCMD")||type.equalsIgnoreCase("XFCN")){String platform=type.equals("XCMD")||type.equals("XFCN")?"68k":"ppc";
			for(Rsrc.xcmdClass x:stack.rsrc.getXcmdResources())if(x.id==id&&x.type.equalsIgnoreCase(type.equalsIgnoreCase("XCMD")?"command":"function")&&x.platform!=null&&x.platform.toLowerCase().contains(platform))return true;return false;}
		return stack.rsrc.rsrcIdMap.containsKey(hzType(type)+id);
	}
	private static void removeFromStack(OStack stack,int type,int id){
		String name=ResourceFile68k.typeName(type);
		if(name.equals("XCMD")||name.equals("XFCN"))stack.rsrc.removeXcmd(name.equals("XCMD")?"command":"function","68k",id);
		else if(name.equals("xcmd")||name.equals("xfcn"))stack.rsrc.removeXcmd(name.equals("xcmd")?"command":"function","ppc",id);
		else stack.rsrc.deleteResource(hzType(name),id);
	}
	/** The stack file {@code own} (_stack.xml, its .hzb folder or a packed .hzb) as a HyperCard file: "<folder>/<name>" without ".hzb". */
	static File hyperCardSeen(File own){
		File host=own==null?null:StackBundle.host(own);if(host==null)return own;
		if((host.getName().equals("_stack.xml")||host.getName().equals("toc.xml"))&&host.getParentFile()!=null)host=host.getParentFile();
		if(!StackBundle.hasHzbExtension(host)||host.getParentFile()==null)return own;
		return new File(host.getParentFile(),host.getName().substring(0,host.getName().length()-4));
	}
	/** Whether {@code file} is where "the long name" of {@code stack} points: its stack file or folder without ".hzb". */
	static boolean isStackPath(OStack stack,File file){
		File host=StackBundle.host(stack.file);if(host==null||file==null)return false;
		if((host.getName().equals("_stack.xml")||host.getName().equals("toc.xml"))&&host.getParentFile()!=null)host=host.getParentFile();
		String path=host.getAbsolutePath().replaceFirst("(?i)\\.hzb$","");
		return java.text.Normalizer.normalize(path,java.text.Normalizer.Form.NFC).equals(java.text.Normalizer.normalize(file.getAbsolutePath(),java.text.Normalizer.Form.NFC));
	}
	/** The stack's own name for a classic resource type ('snd ' is "SND", as the import stores it). */
	private static String hzType(String type){return type.equals("snd ")?"SND":type;}
	/** A resource name from the VM (Mac Roman reading of the bytes, see {@link #vmName}) as the stack keeps it. */
	private static String stackName(OStack stack,String name){
		if(name==null||name.isEmpty())return name;
		StackTextEncoding encoding=stack.legacyTextEncoding!=null?stack.legacyTextEncoding:StackTextEncoding.ROMAN;
		if(encoding==StackTextEncoding.ROMAN)return name;
		byte[] b=StackTextEncoding.ROMAN.encodeReplacing(name);
		return encoding.decode(b,0,b.length);
	}

	/**
	 * A resource name as the 68K Resource Manager keeps it: the stack's original bytes seen as Mac Roman
	 * ({@link ResourceManager68k#resourceName}). The converted stack stores names as text, decoded with the stack's encoding,
	 * so a Japanese name ("りんご", the AnimationMaker scene TheAppleAnimation loads with AMLoad) is encoded back first.
	 */
	static String vmName(OStack stack,String name){
		if(name==null||name.isEmpty())return name;
		StackTextEncoding encoding=stack.legacyTextEncoding!=null?stack.legacyTextEncoding:StackTextEncoding.ROMAN;
		if(encoding==StackTextEncoding.ROMAN)return name;
		byte[] b=encoding.encodeReplacing(name);
		return StackTextEncoding.ROMAN.decode(b,0,b.length);
	}

	static int classicType(String type) {
		if(type==null)return 0;
		if(type.equals("PICT"))return fourcc("PICT");
		if(type.equals("ICON"))return fourcc("ICON");
		if(type.equals("CURS"))return fourcc("CURS");
		if(type.equals("PAT#"))return fourcc("PAT#");
		if(type.equals("SND"))return fourcc("snd ");
		// font(ビットマップ画像へ変換済み)は、もうclassicなリソースバイト列を
		// 持っていない。
		if(type.equals("font"))return 0;
		return type.length()==4?fourcc(type):0;
	}

	//ICON/cicn/CURS/crsr/PAT#/ppat/PICTは、インポート時にはMacの生データの
	//まま(拡張子.data、PICTだけ.pict)保存され、リソースエディタで編集した
	//あとも同じ形式のまま保存し直される(HCResource.java、IconEditor.java
	//参照)。ただし、まだこのエミュレータが対応していない別の手段(例えば
	//外部ツールでファイルを直接置き換える等)で不正な拡張子になっている
	//場合に備えて、拡張子を確認したうえで68Kコードへ渡す。
	static boolean isClassicResourceBytes(String type,String filename) {
		if(filename==null)return false;
		String lower=filename.toLowerCase();
		if(type.equals("PICT"))return lower.endsWith(".pict");
		if(type.equals("ICON")&&lower.endsWith(".png"))return true; // HyperCard's own icons, kept as pictures in Home (ResourceFile68k makes the bits)
		// a sound kept as AIFF/WAV (HyperZebra's Home has HyperCard's Harpsichord, Boing and flute so) becomes a 'snd ' (ResourceFile68k.sndBits)
		if(type.equals("SND")&&ResourceFile68k.Resource.isAudioFile(new File(lower)))return true;
		if(type.equals("ICON")||type.equals("CURS")||type.equals("cicn")||
			type.equals("crsr")||type.equals("PAT#")||type.equals("ppat")||
			type.equals("SND"))return lower.endsWith(".data");
		return true;
	}

	static File resourceFile(OStack owner,String filename) {
		File stackFile=owner.file!=null?owner.file:new File(owner.path==null?"":owner.path);
		File directory=stackFile.isDirectory()?stackFile:stackFile.getParentFile();
		return directory==null?new File(filename):new File(directory,filename);
	}

	private static int fourcc(String value) {
		return (value.charAt(0)&255)<<24|(value.charAt(1)&255)<<16|
			(value.charAt(2)&255)<<8|(value.charAt(3)&255);
	}
}
