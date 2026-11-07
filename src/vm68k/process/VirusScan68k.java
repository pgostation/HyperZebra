import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Finds the classic Macintosh viruses that show in a file's resources, before a program or an extension runs, and repairs the
 * ones whose repair is certain. The jump table test comes from a real nVIR infection; the resource numbers are what
 * the anti-virus tools of the time reported, and none of them is in a clean file of the sample archives (55,000 resource forks).
 * Viruses that show only inside the code (ANTI, ZUC, T4) are not found here: they meet {@link ProgramGuard68k} when they spread.
 * The counterpart for stacks is {@link StackVirusScan}.
 */
final class VirusScan68k {
	private VirusScan68k(){}

	enum Choice{CANCEL,REPAIR,RUN}
	/** Tests answer here. */
	static volatile java.util.function.BiFunction<File,Finding,Choice> decider;
	/** The programs the user chose to run as they are, for this session: not asked about again. */
	private static final Set<String> kept=Collections.synchronizedSet(new HashSet<String>());

	private static final int CODE=ResourceFile68k.fourcc("CODE"),INIT=ResourceFile68k.fourcc("INIT");
	/** INITs of these numbers are the viruses of those names when a program carries them: programs have no INITs of their own. */
	private static final int[] PROGRAM_INITS={6,10,17,29,32,252,1984,9403};
	private static final int ENTRY=0x3f3c,LOAD_SEG=0xa9f0,TABLE_OFFSET=16;

	/** A virus found: its name, and what repairing it takes when that is certain. */
	static final class Finding {
		final String name;
		/** nVIR and its clones: the resource type holding the virus, its segment and the jump table entry it replaced; else null. */
		final Integer holder;final int segment;final byte[] entry;
		Finding(String name){this(name,null,0,null);}
		Finding(String name,Integer holder,int segment,byte[] entry){this.name=name;this.holder=holder;this.segment=segment;this.entry=entry;}
		boolean repairable(){return holder!=null;}
	}

	/** The virus in a file called {@code name}, or null. */
	static Finding scan(ResourceFile68k rf,String name){
		Finding found=jumpTableVirus(rf);
		if(found!=null)return found;
		boolean program=rf.get(CODE,0)!=null;
		if(program){
			for(int id:PROGRAM_INITS)if(rf.get(INIT,id)!=null)return new Finding("INIT "+id);
			if(rf.get(CODE,9811)!=null)return new Finding("CODE 9811");
			if(rf.get(ResourceFile68k.fourcc("MDEF"),0)!=null)return new Finding("MDEF");
			if(rf.get(ResourceFile68k.fourcc("MBDF"),0)!=null)return new Finding("MBDF");
		}
		if(rf.get(INIT,1984)!=null)return new Finding("INIT 1984");
		if("Scores".equals(name)&&!rf.ofType(INIT).isEmpty())return new Finding("Scores");
		// viruses that live in the Finder's Desktop file: nothing else keeps code there
		if("Desktop".equals(name))for(String type:new String[]{"WDEF","CDEF","INIT"})if(!rf.ofType(ResourceFile68k.fourcc(type)).isEmpty())return new Finding(type);
		if(name!=null&&name.equals("\u0001Graphics Accelerator")&&rf.get(INIT,33)!=null)return new Finding("SevenDust");
		return null;
	}

	/**
	 * nVIR and its clones (Hpat, AIDS, MEV#, nFLU...): the program's first jump table entry leads to the virus's segment, and resource 2
	 * of the virus's type keeps the entry it replaced, 8 bytes in the form of an unloaded entry. A copy of these resources
	 * without the redirected entry is what cleaning left behind in many old programs and is not a virus: nothing runs it.
	 */
	private static Finding jumpTableVirus(ResourceFile68k rf){
		ResourceFile68k.Resource table=rf.get(CODE,0);
		if(table==null)return null;
		byte[] code=table.data();
		if(code.length<TABLE_OFFSET+8||u2(code,TABLE_OFFSET+2)!=ENTRY||u2(code,TABLE_OFFSET+6)!=LOAD_SEG)return null;
		int segment=u2(code,TABLE_OFFSET+4);
		if(segment==1||rf.get(CODE,segment)==null)return null;
		for(ResourceFile68k.Resource r:rf.ordered){
			if(r.id!=2||r.type==CODE||r.length()!=8)continue;
			byte[] entry=r.data();
			if(u2(entry,2)!=ENTRY||u2(entry,6)!=LOAD_SEG||u2(entry,4)==segment||rf.get(CODE,u2(entry,4))==null)continue;
			String type=ResourceFile68k.typeName(r.type);
			return new Finding(type.equals("nVIR")?type:"nVIR ("+type+")",r.type,segment,entry);
		}
		return null;
	}
	private static int u2(byte[] b,int at){return (b[at]&0xff)<<8|b[at+1]&0xff;}

	/** Puts the program back as it was: the original entry into the jump table, the virus's segment and resources out. */
	static void repair(ResourceFile68k rf,Finding found){
		ResourceFile68k.Resource table=rf.get(CODE,0);
		byte[] code=table.data().clone();
		System.arraycopy(found.entry,0,code,TABLE_OFFSET,8);
		table.setData(code);table.changed=true;
		rf.remove(rf.get(CODE,found.segment));
		for(ResourceFile68k.Resource r:rf.ofType(found.holder))rf.remove(r);
	}

	/**
	 * A program is about to start: the file to run it from, {@code rf} unless a repair wrote it anew.
	 * An IOException says it stays closed, for the launcher to show.
	 */
	static ResourceFile68k beforeLaunch(File file,ResourceFile68k rf) throws IOException {
		if(Sandbox.allowsEverything())return rf;
		Finding found=scan(rf,file.getName());
		if(found==null)return rf;
		String key=Sandbox.canonical(file);
		if(kept.contains(key))return rf;
		switch(ask(file,found)){
			case RUN:kept.add(key);return rf;
			case REPAIR:{
				if(!found.repairable())break;
				keepOriginal(file);
				repair(rf,found);
				rf.write();
				return ResourceFile68k.open(file);
			}
			default:break;
		}
		throw new IOException(text("It holds a virus and was not opened."));
	}

	/** A copy of the infected file goes to Deleted Files, for the case the repair took something the program needed. */
	private static void keepOriginal(File file) throws IOException {
		File folder=new File(DeletedFiles.folder(),"Repairing");
		if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("cannot create "+folder);
		File copy=new File(folder,file.getName());
		Files.copy(file.toPath(),copy.toPath(),StandardCopyOption.REPLACE_EXISTING);
		byte[] fork=ResourceFile68k.readForkBytes(file,new ResourceFile68k(file));
		if(fork!=null&&fork.length>0)ResourceFile68k.writeForkBytes(copy,fork,ResourceFile68k.Storage.NONE);
		byte[] info=FinderInfo68k.read(file);
		if(info!=null)FinderInfo68k.write(copy,info);
		DeletedFiles.keep(copy,"VirusScan68k");
		folder.delete();
	}

	private static Choice ask(File file,Finding found){
		java.util.function.BiFunction<File,Finding,Choice> d=decider;
		if(d!=null){Choice c=d.apply(file,found);if(c!=null)return c;}
		if(java.awt.GraphicsEnvironment.isHeadless()||"deny".equalsIgnoreCase(System.getProperty("hz.sandbox",""))){
			System.err.println("[virus] not opened: "+file+" holds "+found.name);return Choice.CANCEL;}
		final Choice[] answer={Choice.CANCEL};
		Runnable show=()->answer[0]=dialog(file,found);
		if(SwingUtilities.isEventDispatchThread())show.run();
		else try{SwingUtilities.invokeAndWait(show);}catch(Exception e){e.printStackTrace();}
		return answer[0];
	}
	private static Choice dialog(File file,Finding found){
		String message=International.t("\"{0}\" is infected with the virus {1}.",file.getName(),found.name)+"\n\n"+text(found.repairable()
			?"Repairing puts the program back as it was; the infected file is kept in Deleted Files."
			:"HyperZebra cannot repair this file. If you open it anyway, changes it tries to make to other programs are still refused.");
		String cancel=text("Don't Open"),repair=text("Repair and Open"),run=text("Open Anyway");
		String[] options=found.repairable()?new String[]{cancel,repair,run}:new String[]{cancel,run};
		int choice=JOptionPane.showOptionDialog(PCARD.pc,message,text("Virus Found"),JOptionPane.DEFAULT_OPTION,JOptionPane.WARNING_MESSAGE,null,options,cancel);
		if(choice<0||choice==0)return Choice.CANCEL;
		return found.repairable()&&choice==1?Choice.REPAIR:Choice.RUN;
	}
	private static String text(String english){return PCARD.pc!=null&&PCARD.pc.intl!=null?PCARD.pc.intl.getDialogText(english):english;}
}
