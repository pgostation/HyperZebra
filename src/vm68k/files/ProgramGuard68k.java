import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classic Macintosh viruses spread by adding code to programs: nVIR, ANTI, ZUC, INIT 29, MDEF, CODE 252 and T4 all come down to
 * resources of a code type going into a file that was not theirs. This knows what code is and which files are programs, and
 * finds out what a resource file is about to change, so that {@link Sandbox#allowProgramChange} can be asked before it is written.
 * It goes by what is written and not by who wrote it, so a virus nobody has listed meets it too; the System file needs no
 * guard, being built in memory (ResourceManager68k) - real nVIR's INIT 32 goes in there and is gone when the program ends.
 */
final class ProgramGuard68k {
	private ProgramGuard68k(){}

	/** Resource types that run. 'CODE' includes CODE 0, the jump table viruses redirect. */
	private static final Set<Integer> CODE_TYPES=fourccs("CODE","INIT","cdev","DRVR","WDEF","MDEF","CDEF","MBDF","LDEF","PACK","PTCH","ptch","FKEY","RDEV","XCMD","XFCN","dcmp","ADBS","cfrg","thng");
	/** File types the Mac runs or loads: what these files hold is not the user's data. */
	private static final Set<Integer> PROGRAM_TYPES=fourccs("APPL","APPC","APPD","APPE","appe","INIT","cdev","RDEV","adev","thng","scri","shlb","FNDR","zsys","ZSYS","dfil","DRVR","PRER","PRES","gbly");
	private static Set<Integer> fourccs(String... names){
		Set<Integer> set=new HashSet<Integer>();
		for(String name:names)set.add(ResourceFile68k.fourcc(name));
		return set;
	}

	static boolean isCode(int type){return CODE_TYPES.contains(type);}

	/** Whether {@code file} is a program, a part of the system or an extension: by its file type, or by its code when it has none. */
	static boolean isProgram(File file){
		if(file==null||!file.isFile())return false;
		int type=FinderInfo68k.type(file);
		if(type!=0)return PROGRAM_TYPES.contains(type);
		try{return ResourceFile68k.open(file).get(ResourceFile68k.fourcc("CODE"),0)!=null;}catch(IOException|RuntimeException e){return false;}
	}

	/** What a resource file would change in its code when written, compared with what the file holds now. */
	static final class Changes {
		final List<ResourceFile68k.Resource> added=new ArrayList<ResourceFile68k.Resource>(),removed=new ArrayList<ResourceFile68k.Resource>();
		final Map<ResourceFile68k.Resource,byte[]> changed=new java.util.IdentityHashMap<ResourceFile68k.Resource,byte[]>();
		boolean empty(){return added.isEmpty()&&removed.isEmpty()&&changed.isEmpty();}
		/** Takes the changes back, so that what else the program wrote goes to the file and this does not; the program's Handles stay. */
		void revert(ResourceFile68k file,Map<Integer,ResourceFile68k.Resource> byHandle){
			for(ResourceFile68k.Resource r:added){if(r.handle!=0)byHandle.remove(r.handle);file.remove(r);}
			for(Map.Entry<ResourceFile68k.Resource,byte[]> e:changed.entrySet()){e.getKey().setData(e.getValue());e.getKey().changed=false;}
			for(ResourceFile68k.Resource r:removed)file.add(r);
			file.codeRemoved=false;
		}
	}

	/** The code about to change in {@code file}, null when none is. Reads the file only then: documents are saved all the time. */
	static Changes changes(ResourceFile68k file){
		boolean touched=file.codeRemoved;
		for(ResourceFile68k.Resource r:file.ordered)if(r.changed&&isCode(r.type)){touched=true;break;}
		if(!touched)return null;
		ResourceFile68k disk;
		try{disk=file.storage==ResourceFile68k.Storage.DATA?ResourceFile68k.openDataFork(file.file):ResourceFile68k.open(file.file);}
		catch(IOException|RuntimeException e){disk=new ResourceFile68k(file.file);}
		Changes changes=new Changes();
		for(ResourceFile68k.Resource r:file.ordered){
			if(!isCode(r.type))continue;
			ResourceFile68k.Resource old=disk.get(r.type,r.id);
			if(old==null)changes.added.add(r);
			else if(r.changed&&!Arrays.equals(r.data(),old.data()))changes.changed.put(r,old.data());
		}
		for(ResourceFile68k.Resource r:disk.ordered)if(isCode(r.type)&&file.get(r.type,r.id)==null)changes.removed.add(r);
		return changes;
	}
}
