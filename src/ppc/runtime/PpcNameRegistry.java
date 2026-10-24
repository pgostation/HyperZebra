import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NameRegistryLib, the Mac OS 8/9 Name Registry (NameRegistry.h; not part of Carbon, so programs look the library up with
 * GetSharedLibrary first). The 68K side has no _NameRegistryDispatch ($ABE9): a PowerPC program's calls land here.
 * <p>
 * The registry is the device tree a Power Macintosh builds from Open Firmware. This one holds the root ("Devices") and its
 * "device-tree" entry with no hardware below and no properties - in particular no "local-mac-address": Acrobat Reader 5's
 * Web Buy plug-in searches the whole registry for that property to make a machine ID, and a search that finds nothing is
 * the answer a Mac without Ethernet gives. Programs may add entries and properties of their own; they live for the session.
 * <p>
 * A RegEntryID is 16 bytes: the entry number in the first word, a tag in the second (all zero after RegistryEntryIDInit).
 * Entry and property iterators are opaque tokens.
 */
final class PpcNameRegistry {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcNameRegistry(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	static final int NO_ERR=0,PARAM_ERR=-50,
		NR_INVALID_NODE=-2538,NR_NOT_FOUND=-2539,NR_NAME_ERR=-2541,NR_DATA_TRUNCATED=-2543,NR_PATH_NOT_FOUND=-2550,
		NR_PATH_BUFFER_TOO_SMALL=-2551,NR_INVALID_ENTRY_ITERATION_OP=-2552,NR_PROPERTY_ALREADY_EXISTS=-2553;
	static final int ITER_CONTINUE=1,ITER_ROOT=2,ITER_PARENTS=3,ITER_CHILDREN=4,ITER_DESCENDANTS=5,ITER_SIBLING=6;
	/** The second word of a valid RegEntryID ('nrEn'). */
	private static final int ID_TAG=0x6e72456e;
	static final int MAX_ENTRY_NAME=47,MAX_PROPERTY_NAME=31;

	private static final class Entry {
		final int number;final String name;final Entry parent;final List<Entry> children=new ArrayList<Entry>();
		final Map<String,byte[]> properties=new LinkedHashMap<String,byte[]>();final Map<String,Integer> propertyModifiers=new HashMap<String,Integer>();
		int modifiers;boolean deleted;
		Entry(int number,String name,Entry parent){this.number=number;this.name=name;this.parent=parent;}
	}
	/** An entry iterator: its position ({@code start}: the entry set or last found) and the entries of the current relationship. */
	private static final class EntryIterator {Entry start;int relationship=ITER_DESCENDANTS;List<Entry> list;int next;}
	private static final class PropertyIterator {List<String> names;int next;}

	private final Map<Integer,Entry> entries=new HashMap<Integer,Entry>();
	private final Map<Integer,EntryIterator> entryIterators=new HashMap<Integer,EntryIterator>();
	private final Map<Integer,PropertyIterator> propertyIterators=new HashMap<Integer,PropertyIterator>();
	private int nextEntry=1,nextToken=0x4e520001;
	private final Entry root;
	{root=add("Devices",null);add("device-tree",root);}
	private Entry add(String name,Entry parent){Entry e=new Entry(nextEntry++,name,parent);entries.put(e.number,e);if(parent!=null)parent.children.add(e);return e;}

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}

	void define(PpcLibraries l){
		// RegEntryIDs
		l.register(null,"RegistryEntryIDInit",(r,c)->{int id=arg(0);if(id==0){ret(PARAM_ERR);return;}memory.fill(id,16,0);ret(NO_ERR);});
		l.register(null,"RegistryEntryIDCompare",(r,c)->{int a=arg(0),b=arg(1);boolean same=true;for(int i=0;i<16&&a!=0&&b!=0;i+=4)if(memory.readLong(a+i)!=memory.readLong(b+i))same=false;ret(same&&a!=0&&b!=0?1:0);});
		l.register(null,"RegistryEntryIDCopy",(r,c)->{int src=arg(0),dst=arg(1);if(src==0||dst==0){ret(PARAM_ERR);return;}memory.copy(src,dst,16);ret(NO_ERR);});
		l.register(null,"RegistryEntryIDDispose",(r,c)->{int id=arg(0);if(id!=0)memory.fill(id,16,0);ret(NO_ERR);});
		// entries
		l.register(null,"RegistryCStrEntryCreate",(r,c)->ret(create(arg(0),arg(1),arg(2))));
		l.register(null,"RegistryEntryDelete",(r,c)->{Entry e=entry(arg(0));if(e==null||e==root){ret(NR_INVALID_NODE);return;}delete(e);ret(NO_ERR);});
		l.register(null,"RegistryEntryCopy",(r,c)->ret(PARAM_ERR)); // driver-level call, not used by applications
		l.register(null,"RegistryCStrEntryLookup",(r,c)->ret(lookup(arg(0),arg(1),arg(2))));
		l.register(null,"RegistryEntryToPathSize",(r,c)->{Entry e=entry(arg(0));if(e==null){ret(NR_INVALID_NODE);return;}if(arg(1)!=0)memory.writeLong(arg(1),path(e).length()+1);ret(NO_ERR);});
		l.register(null,"RegistryCStrEntryToPath",(r,c)->{Entry e=entry(arg(0));if(e==null){ret(NR_INVALID_NODE);return;}String p=path(e);int out=arg(1),size=arg(2);if(out==0||size<p.length()+1){ret(NR_PATH_BUFFER_TOO_SMALL);return;}writeC(out,p);ret(NO_ERR);});
		l.register(null,"RegistryCStrEntryToName",(r,c)->{ // (entryID, parentEntry, nameComponent, done)
			Entry e=entry(arg(0));if(e==null){ret(NR_INVALID_NODE);return;}
			if(arg(1)!=0){if(e.parent!=null)writeId(arg(1),e.parent);else memory.fill(arg(1),16,0);}
			if(arg(2)!=0)writeC(arg(2),e.name);if(arg(3)!=0)memory.writeByte(arg(3),e.parent==null?1:0);ret(NO_ERR);});
		// entry iteration and search
		l.register(null,"RegistryEntryIterateCreate",(r,c)->{int cookie=arg(0);if(cookie==0){ret(PARAM_ERR);return;}EntryIterator it=new EntryIterator();it.start=root;int t=nextToken++;entryIterators.put(t,it);memory.writeLong(cookie,t);ret(NO_ERR);});
		l.register(null,"RegistryEntryIterateDispose",(r,c)->{int cookie=arg(0);if(cookie!=0){entryIterators.remove(memory.readLong(cookie));memory.writeLong(cookie,0);}ret(NO_ERR);});
		l.register(null,"RegistryEntryIterateSet",(r,c)->{EntryIterator it=iterator(arg(0));Entry e=entry(arg(1));if(it==null){ret(PARAM_ERR);return;}if(e==null){ret(NR_INVALID_NODE);return;}it.start=e;it.list=null;ret(NO_ERR);});
		l.register(null,"RegistryEntryIterate",(r,c)->ret(iterate(arg(0),arg(1),arg(2),arg(3),null,0,0,-1)));
		l.register(null,"RegistryEntrySearch",(r,c)->{int name=arg(4);ret(name==0?PARAM_ERR:iterate(arg(0),arg(1),arg(2),arg(3),readC(name),arg(5),arg(6),-1));});
		l.register(null,"RegistryEntryMod",(r,c)->ret(iterate(arg(0),arg(1),arg(2),arg(3),null,0,0,arg(4))));
		l.register(null,"RegistryEntryPropertyMod",(r,c)->ret(iterate(arg(0),arg(1),arg(2),arg(3),"",0,0,arg(4))));
		// properties
		l.register(null,"RegistryPropertyCreate",(r,c)->{Entry e=entry(arg(0));String n=name(arg(1));if(e==null){ret(NR_INVALID_NODE);return;}if(n==null){ret(NR_NAME_ERR);return;}if(e.properties.containsKey(n)){ret(NR_PROPERTY_ALREADY_EXISTS);return;}e.properties.put(n,bytes(arg(2),arg(3)));ret(NO_ERR);});
		l.register(null,"RegistryPropertyDelete",(r,c)->{Entry e=entry(arg(0));String n=name(arg(1));if(e==null){ret(NR_INVALID_NODE);return;}if(n==null||e.properties.remove(n)==null){ret(NR_NOT_FOUND);return;}e.propertyModifiers.remove(n);ret(NO_ERR);});
		l.register(null,"RegistryPropertyRename",(r,c)->{Entry e=entry(arg(0));String from=name(arg(1)),to=name(arg(2));if(e==null){ret(NR_INVALID_NODE);return;}if(from==null||!e.properties.containsKey(from)){ret(NR_NOT_FOUND);return;}if(to==null){ret(NR_NAME_ERR);return;}if(e.properties.containsKey(to)){ret(NR_PROPERTY_ALREADY_EXISTS);return;}
			Map<String,byte[]> renamed=new LinkedHashMap<String,byte[]>();for(Map.Entry<String,byte[]> p:e.properties.entrySet())renamed.put(p.getKey().equals(from)?to:p.getKey(),p.getValue());e.properties.clear();e.properties.putAll(renamed);
			Integer mods=e.propertyModifiers.remove(from);if(mods!=null)e.propertyModifiers.put(to,mods);ret(NO_ERR);});
		l.register(null,"RegistryPropertyIterateCreate",(r,c)->{Entry e=entry(arg(0));int cookie=arg(1);if(e==null){ret(NR_INVALID_NODE);return;}if(cookie==0){ret(PARAM_ERR);return;}PropertyIterator it=new PropertyIterator();it.names=new ArrayList<String>(e.properties.keySet());int t=nextToken++;propertyIterators.put(t,it);memory.writeLong(cookie,t);ret(NO_ERR);});
		l.register(null,"RegistryPropertyIterateDispose",(r,c)->{int cookie=arg(0);if(cookie!=0){propertyIterators.remove(memory.readLong(cookie));memory.writeLong(cookie,0);}ret(NO_ERR);});
		l.register(null,"RegistryPropertyIterate",(r,c)->{ // (cookie, foundProperty, done)
			int cookie=arg(0);PropertyIterator it=cookie==0?null:propertyIterators.get(memory.readLong(cookie));if(it==null){ret(PARAM_ERR);return;}
			boolean done=it.next>=it.names.size();if(!done&&arg(1)!=0)writeC(arg(1),it.names.get(it.next++));if(arg(2)!=0)memory.writeByte(arg(2),done?1:0);ret(NO_ERR);});
		l.register(null,"RegistryPropertyGetSize",(r,c)->{byte[] v=property(arg(0),arg(1));if(v==null){ret(lastError);return;}if(arg(2)!=0)memory.writeLong(arg(2),v.length);ret(NO_ERR);});
		l.register(null,"RegistryPropertyGet",(r,c)->{ // (entryID, name, value, *size): *size in = room, out = the value's size
			byte[] v=property(arg(0),arg(1));if(v==null){ret(lastError);return;}int out=arg(2),sizePtr=arg(3);if(sizePtr==0){ret(PARAM_ERR);return;}
			int room=memory.readLong(sizePtr);int n=Math.min(room,v.length);if(out!=0)for(int i=0;i<n;i++)memory.writeByte(out+i,v[i]);memory.writeLong(sizePtr,v.length);ret(room<v.length?NR_DATA_TRUNCATED:NO_ERR);});
		l.register(null,"RegistryPropertySet",(r,c)->{Entry e=entry(arg(0));String n=name(arg(1));if(e==null){ret(NR_INVALID_NODE);return;}if(n==null||!e.properties.containsKey(n)){ret(NR_NOT_FOUND);return;}e.properties.put(n,bytes(arg(2),arg(3)));ret(NO_ERR);});
		// modifiers
		l.register(null,"RegistryEntryGetMod",(r,c)->{Entry e=entry(arg(0));if(e==null){ret(NR_INVALID_NODE);return;}if(arg(1)!=0)memory.writeLong(arg(1),e.modifiers);ret(NO_ERR);});
		l.register(null,"RegistryEntrySetMod",(r,c)->{Entry e=entry(arg(0));if(e==null){ret(NR_INVALID_NODE);return;}e.modifiers=arg(1);ret(NO_ERR);});
		l.register(null,"RegistryPropertyGetMod",(r,c)->{byte[] v=property(arg(0),arg(1));if(v==null){ret(lastError);return;}Integer m=entry(arg(0)).propertyModifiers.get(readC(arg(1)));if(arg(2)!=0)memory.writeLong(arg(2),m==null?0:m);ret(NO_ERR);});
		l.register(null,"RegistryPropertySetMod",(r,c)->{byte[] v=property(arg(0),arg(1));if(v==null){ret(lastError);return;}entry(arg(0)).propertyModifiers.put(readC(arg(1)),arg(2));ret(NO_ERR);});
	}

	// ------------------------------------------------------------------ helpers

	private Entry entry(int id) throws M68kCpuException {
		if(id==0||memory.readLong(id+4)!=ID_TAG)return null;
		Entry e=entries.get(memory.readLong(id));return e==null||e.deleted?null:e;
	}
	private void writeId(int id,Entry e) throws M68kCpuException {memory.fill(id,16,0);memory.writeLong(id,e.number);memory.writeLong(id+4,ID_TAG);}
	private String readC(int p) throws M68kCpuException {return rt.cString(p);}
	private void writeC(int p,String s) throws M68kCpuException {byte[] b=s.getBytes(StandardCharsets.ISO_8859_1);memory.load(p,b);memory.writeByte(p+b.length,0);}
	private byte[] bytes(int p,int n) throws M68kCpuException {if(p==0||n<=0)return new byte[0];byte[] b=new byte[n];for(int i=0;i<n;i++)b[i]=(byte)memory.readByte(p+i);return b;}
	/** A property name, null when missing or longer than kRegMaximumPropertyNameLength. */
	private String name(int p) throws M68kCpuException {if(p==0)return null;String s=readC(p);return s.isEmpty()||s.length()>MAX_PROPERTY_NAME?null:s;}
	private int lastError;
	private byte[] property(int id,int namePointer) throws M68kCpuException {
		Entry e=entry(id);if(e==null){lastError=NR_INVALID_NODE;return null;}
		byte[] v=namePointer==0?null:e.properties.get(readC(namePointer));if(v==null)lastError=NR_NOT_FOUND;return v;
	}
	private static String path(Entry e){StringBuilder sb=new StringBuilder(e.name);for(Entry p=e.parent;p!=null;p=p.parent)sb.insert(0,p.name+":");return sb.toString();}
	private static Entry child(Entry e,String name){for(Entry c:e.children)if(!c.deleted&&c.name.equals(name))return c;return null;}

	/** RegistryCStrEntryLookup: a ':'-separated path from {@code searchPoint} (from the root when that is NULL or not an entry;
	 *  the root's own name may start an absolute path). */
	private int lookup(int searchPoint,int pathPointer,int found) throws M68kCpuException {
		if(pathPointer==0||found==0)return PARAM_ERR;
		Entry at=entry(searchPoint);if(at==null)at=root;
		String[] parts=readC(pathPointer).split(":");int i=0;
		if(at==root&&parts.length>0&&parts[0].equals(root.name))i=1;
		for(;i<parts.length;i++){if(parts[i].isEmpty())continue;Entry c=child(at,parts[i]);if(c==null)return NR_PATH_NOT_FOUND;at=c;}
		writeId(found,at);return NO_ERR;
	}
	/** RegistryCStrEntryCreate: the last component of {@code namePointer} under the entry its other components name. */
	private int create(int parentId,int namePointer,int newEntry) throws M68kCpuException {
		if(namePointer==0||newEntry==0)return PARAM_ERR;
		Entry parent=entry(parentId);if(parent==null)parent=root;
		String[] parts=readC(namePointer).split(":");
		for(int i=0;i<parts.length-1;i++){if(parts[i].isEmpty())continue;Entry c=child(parent,parts[i]);if(c==null)return NR_PATH_NOT_FOUND;parent=c;}
		String name=parts.length==0?"":parts[parts.length-1];
		if(name.isEmpty()||name.length()>MAX_ENTRY_NAME)return NR_NAME_ERR;
		Entry e=child(parent,name);if(e==null)e=add(name,parent);
		writeId(newEntry,e);return NO_ERR;
	}
	private void delete(Entry e){e.deleted=true;for(Entry c:e.children)delete(c);if(e.parent!=null)e.parent.children.remove(e);}

	private EntryIterator iterator(int cookie) throws M68kCpuException {return cookie==0?null:entryIterators.get(memory.readLong(cookie));}
	private static void descendants(Entry e,List<Entry> out){for(Entry c:e.children)if(!c.deleted){out.add(c);descendants(c,out);}}
	private List<Entry> related(Entry start,int relationship){
		List<Entry> out=new ArrayList<Entry>();
		switch(relationship){
			case ITER_ROOT:out.add(root);break;
			case ITER_PARENTS:for(Entry p=start.parent;p!=null;p=p.parent)out.add(p);break;
			case ITER_CHILDREN:for(Entry c:start.children)if(!c.deleted)out.add(c);break;
			case ITER_DESCENDANTS:descendants(start,out);break;
			case ITER_SIBLING:if(start.parent!=null)for(Entry c:start.parent.children)if(!c.deleted&&c!=start)out.add(c);break;
			default:return null;
		}
		return out;
	}
	/**
	 * RegistryEntryIterate/Search/Mod: the next entry in {@code relationship} to the iterator's position (kRegIterContinue goes on
	 * with the previous relationship) - for a search one with property {@code property} (and that value when {@code value} is not
	 * NULL; "" = any property whose modifiers match), for the Mod calls one whose modifiers include {@code modifiers}. When none
	 * is left, *done is set and the result is still noErr.
	 */
	private int iterate(int cookie,int relationship,int found,int donePointer,String property,int value,int valueSize,int modifiers) throws M68kCpuException {
		EntryIterator it=iterator(cookie);if(it==null)return PARAM_ERR;
		if(relationship!=ITER_CONTINUE||it.list==null){
			List<Entry> list=related(it.start,relationship==ITER_CONTINUE?it.relationship:relationship);
			if(list==null)return NR_INVALID_ENTRY_ITERATION_OP;
			if(relationship!=ITER_CONTINUE){it.relationship=relationship;}
			it.list=list;it.next=0;
		}
		byte[] wanted=property!=null&&!property.isEmpty()&&value!=0?bytes(value,valueSize):null;
		while(it.next<it.list.size()){
			Entry e=it.list.get(it.next++);if(e.deleted)continue;
			if(!matches(e,property,wanted,modifiers))continue;
			it.start=e; // the iterator's position: a new relationship starts from here
			if(found!=0)writeId(found,e);
			if(donePointer!=0)memory.writeByte(donePointer,0);
			return NO_ERR;
		}
		if(donePointer!=0)memory.writeByte(donePointer,1);
		return NO_ERR;
	}
	private static boolean matches(Entry e,String property,byte[] value,int modifiers){
		if(property==null)return modifiers==-1||(e.modifiers&modifiers)==modifiers;
		if(property.isEmpty()){for(Integer m:e.propertyModifiers.values())if((m&modifiers)==modifiers)return true;return false;}
		byte[] v=e.properties.get(property);
		return v!=null&&(value==null||java.util.Arrays.equals(v,value));
	}
}
