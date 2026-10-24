import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HyperZebra stacks as the HyperCard stack files 68K externals take them for. An external names a stack by the path "the long
 * name of this stack" gives (the .hzb without its extension) or by the .hzb file a dialog answered; on a Mac that is a file of
 * type 'STAK' whose resource fork holds the stack's resources. There is no such file here when no HyperCard stack was left
 * beside the .hzb, and a .hzb is a ZIP whose resource fork HyperZebra never reads, so G-XCollection's MtCRX ("Error : OpenRF
 * (Src) = -43"), MtRsrcTypeList and MtSaveTextRsrc failed on pathThis(), and its installer wrote the XCMD it installed into a
 * fork of the ZIP.
 * <p>
 * The external's File Manager hands it a stand-in for such a path instead ({@link FileManager68k}): a file with no data, typed
 * 'STAK'/'WILD', whose resource fork is the stack's resources as {@link Mac68kResourceManager#forStack} shows them. Everything
 * the external does with it - GetFInfo, OpenRF, OpenResFile, AddResource, UpdateResFile - then works as on a Mac, and what it
 * changed in the fork goes into the stack when the external returns ({@link #sync}), stored the way {@link
 * Mac68kResourceManager#storeChanges} stores resources added to an open stack; a stack read on the side is saved then. The
 * stand-ins live in a hidden folder of HyperZebra's state folder (the access gate does not ask about them; it is asked about
 * the stack itself instead).
 */
final class StackFile68k {
	private StackFile68k(){}

	/** One stack's stand-in file: what its fork held when made or last synced, and the stack it stands for. */
	static final class Shadow {
		final File bundle,file;OStack stack;byte[] baseline;
		private Shadow(File bundle,File file,OStack stack,byte[] baseline){this.bundle=bundle;this.file=file;this.stack=stack;this.baseline=baseline;}
	}

	private static final String FOLDER=".stack-files";
	private static long serial;

	/**
	 * The HyperZebra stack {@code path} names for an external (its packed .hzb file or .hzb folder, canonical), or null: a .hzb
	 * file, or a path that names nothing where "path.hzb" is a stack. A HyperCard stack file of that name is the file itself.
	 */
	static File stackAt(File path){
		if(path==null)return null;
		try{
			File parent=path.getParentFile();
			// a .hzb folder's own file (answer file of type STAK lists _stack.xml)
			if(path.isFile()&&(path.getName().equals("_stack.xml")||path.getName().equals("toc.xml"))&&parent!=null&&StackBundle.hasHzbExtension(parent))return parent.getCanonicalFile();
			if(path.isFile())return StackBundle.hasHzbExtension(path)&&StackBundle.isPacked(path)?path.getCanonicalFile():null;
			if(path.exists()||path.getParentFile()==null)return null;
			File hzb=new File(path.getParentFile(),path.getName()+".hzb");
			if(hzb.isFile()&&StackBundle.isPacked(hzb))return hzb.getCanonicalFile();
			if(hzb.isDirectory()&&(new File(hzb,"_stack.xml").isFile()||new File(hzb,"toc.xml").isFile()))return hzb.getCanonicalFile();
		}catch(IOException|SecurityException e){}
		return null;
	}

	/** A new stand-in for the stack in {@code bundle}, its file named {@code name}; null when the stack cannot be read. */
	static Shadow make(File bundle,String name,String requester){
		if(!Sandbox.allowFile(bundle,Sandbox.Access.READ,requester))return null;
		OStack stack=stackFor(bundle);
		if(stack==null)return null;
		try{
			File dir;synchronized(StackFile68k.class){dir=new File(root(),Long.toString(System.currentTimeMillis())+"-"+(++serial));}
			if(!dir.mkdirs())return null;
			File file=new File(dir,name);
			Files.write(file.toPath(),new byte[0]);
			ResourceFile68k fork=new ResourceFile68k(file);
			for(ResourceFile68k.Resource r:Mac68kResourceManager.forStack(stack).all())fork.add(new ResourceFile68k.Resource(r.type,r.id,r.name,r.attributes,r.data()));
			fork.write();
			FinderInfo68k.write(file,new byte[]{'S','T','A','K','W','I','L','D',0,0,0,0,0,0,0,0});
			file.setLastModified(bundle.lastModified());
			byte[] written=ResourceFile68k.readForkBytes(file,null);
			return new Shadow(bundle,file,stack,written==null?new byte[0]:written);
		}catch(IOException e){
			System.err.println("[xcmd] no stand-in file for the stack "+bundle+": "+e);
			return null;
		}
	}

	/**
	 * What the external changed in the stand-in's fork since it was made (or last synced) goes into the stack: resources
	 * added, changed, renumbered, renamed or removed. A stack that cannot be modified, or that the access gate keeps from being
	 * written, is left as it is. True when the stack changed.
	 */
	static boolean sync(Shadow s,String requester){
		// a stand-in that is gone (deleted, renamed away) says nothing about the stack's resources: read as an empty fork it removed
		// all of them (Rinaldi's EraseFile of a stack in use wiped its XCMDs and icons from the .hzb)
		if(!s.file.isFile())return false;
		byte[] now;
		try{now=ResourceFile68k.readForkBytes(s.file,null);}catch(IOException e){return false;}
		if(now==null)now=new byte[0];
		if(Arrays.equals(now,s.baseline))return false;
		s.baseline=now;
		ResourceFile68k written=new ResourceFile68k(s.file);
		try{if(now.length>=16)written.parse(now);}catch(IOException e){System.err.println("[xcmd] the stand-in of "+s.bundle+" has no readable resource map: "+e.getMessage());return false;}
		// the stack was closed while the external ran: ReadCat's InstallRC does "push card", "go to stack X", adds its XFCN to X's
		// fork and "pop card"s back before it returns, so X is read again on the side and the change is stored and saved there
		// (the closed one has no resources any more: "The installation is complete." and nothing was installed)
		if(s.stack.rsrc==null){OStack again=stackFor(s.bundle);if(again==null||again.rsrc==null)return false;s.stack=again;}
		ResourceFile68k live=Mac68kResourceManager.forStack(s.stack);
		if(live.stack==null)return false; // can't modify
		if(!Sandbox.allowFile(s.bundle,Sandbox.Access.WRITE,requester))return false;
		for(ResourceFile68k.Resource r:live.all()){
			ResourceFile68k.Resource w=written.get(r.type,r.id);
			if(w==null){live.remove(r);continue;}
			if(!Arrays.equals(w.data(),r.data())||!java.util.Objects.equals(w.name,r.name)){r.setData(w.data());r.name=w.name;r.changed=true;}
			r.attributes=w.attributes&~(ResourceFile68k.RES_CHANGED|ResourceFile68k.RES_COMPRESSED);
		}
		for(ResourceFile68k.Resource w:written.all())if(live.get(w.type,w.id)==null){
			ResourceFile68k.Resource n=new ResourceFile68k.Resource(w.type,w.id,w.name,w.attributes&~(ResourceFile68k.RES_CHANGED|ResourceFile68k.RES_COMPRESSED),w.data());n.changed=true;live.add(n);}
		if(!Mac68kResourceManager.storeChanges(live,ResourceFile68k.Resource::data))return false;
		// the stack on show is written by its own autosave; one in use or read on the side is written now
		if(s.stack.saveXML==null){try{XMLwrite.writeOnce(s.stack);}catch(RuntimeException e){System.err.println("[xcmd] the stack "+s.bundle+" was not saved: "+e);}}
		if(Mac68kApi.TRACE)System.err.println("[xcmd] stand-in of "+s.bundle+" stored into the stack");
		return true;
	}

	/** Removes a stand-in nothing has open any more. */
	static void discard(Shadow s){
		File dir=s.file.getParentFile();
		s.file.delete();
		File[] left=dir==null?null:dir.listFiles();
		if(left!=null)for(File f:left)f.delete();
		if(dir!=null)dir.delete();
	}

	/** The stack in {@code bundle}: the one on show, in use or Home, else the stack read on the side. */
	private static OStack stackFor(File bundle){
		if(PCARD.pc!=null){
			List<OStack> open=new ArrayList<OStack>();
			if(PCARD.pc.stack!=null){open.add(PCARD.pc.stack);if(PCARD.pc.stack.usingStacks!=null)open.addAll(PCARD.pc.stack.usingStacks);}
			if(PCARD.home!=null)open.add(PCARD.home);
			for(OStack s:open)if(s!=null&&s.rsrc!=null&&bundle.equals(bundleOf(s)))return s;
			OStack side=TTalk.resolveStackObject(bundle.getPath());
			if(side!=null&&side.rsrc!=null&&bundle.equals(bundleOf(side)))return side; // not one closed meanwhile (no resources)
		}
		OStack s=new OStack(PCARD.pc);
		String path=bundle.isDirectory()?new File(bundle,new File(bundle,"_stack.xml").isFile()?"_stack.xml":"toc.xml").getPath():bundle.getPath();
		return s.openStackFileInThread(path,true)?s:null;
	}

	/** The packed .hzb file or the folder a stack is kept in (canonical), or null. */
	static File bundleOf(OStack stack){
		File f=stack==null?null:StackBundle.host(stack.file!=null?stack.file:stack.path!=null?new File(stack.path):null);
		if(f==null)return null;
		if((f.getName().equals("_stack.xml")||f.getName().equals("toc.xml"))&&f.getParentFile()!=null)f=f.getParentFile();
		try{return f.getCanonicalFile();}catch(IOException e){return f.getAbsoluteFile();}
	}

	/** The hidden folder the stand-ins are made in; stand-ins left by a HyperZebra that did not quit go when it is first used. */
	private static boolean cleaned;
	private static synchronized File root(){
		File root=new File(AppPaths.stateDirectory(),FOLDER);
		if(!cleaned){
			cleaned=true;
			File[] old=root.listFiles();
			long before=System.currentTimeMillis()-24L*3600*1000; // not another HyperZebra's, still in use
			if(old!=null)for(File d:old)if(d.lastModified()<before){File[] in=d.listFiles();if(in!=null)for(File f:in)f.delete();d.delete();}
		}
		root.mkdirs();
		return root;
	}
}
