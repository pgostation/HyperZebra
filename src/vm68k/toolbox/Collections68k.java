import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Collection Manager (Collections.h), _CollectionMgr $ABF6 with the selector in D0 (MOVEQ) and Pascal arguments on the
 * stack. A Collection is an opaque guest block standing for Java data: items keyed by tag and id, each with a copy of its data
 * and 32 attribute bits (the high word the manager's: kCollectionLockBit 31 - locked items cannot be replaced or removed,
 * kCollectionPersistenceBit 30 - only persistent items are flattened; the low word the program's). Items are kept sorted by
 * tag (unsigned) then id, which is also their index order and the order the flattened form requires.
 * <p>
 * The flattened form is the one Collections.r documents as the 'flac' resource: version $00010000, the default attributes, the
 * item count, per item {tag, id, attributes, offset of its data from the start of the data block}, the data block's size, then
 * per item {size, data, word alignment}. GetNewCollection reads a 'cltn' resource (count, then {tag, id, attributes, wstring}
 * aligned to words). The exception procedure is called with (collection, status) whenever a call fails, and its result is
 * what that call returns.
 */
final class Collections68k extends TrapManager68k {
	Collections68k(Toolbox68k tb){super(tb);}

	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108;
	static final int COLLECTION_ITEM_LOCKED_ERR=-5750,COLLECTION_ITEM_NOT_FOUND_ERR=-5751,COLLECTION_INDEX_RANGE_ERR=-5752,COLLECTION_VERSION_ERR=-5753;
	static final int LOCK_MASK=0x80000000,PERSISTENCE_MASK=0x40000000,DEFAULT_ATTRIBUTES=0x40000000;
	static final int FLATTENED_VERSION=0x00010000;

	static final class Item {
		final int tag,id;int attributes;byte[] data;
		Item(int tag,int id,int attributes,byte[] data){this.tag=tag;this.id=id;this.attributes=attributes;this.data=data;}
		boolean locked(){return (attributes&LOCK_MASK)!=0;}
	}
	/** A collection's contents; {@code owners} counts CloneCollection references. */
	static final class Collection {
		final int token;final List<Item> items=new ArrayList<Item>();int owners=1,defaultAttributes=DEFAULT_ATTRIBUTES,exceptionProc;
		Collection(int token){this.token=token;}
		/** Index of (tag, id) in the sorted list, or -(insertion point)-1. */
		int search(int tag,int id){
			int lo=0,hi=items.size()-1;
			while(lo<=hi){int mid=(lo+hi)>>>1;Item m=items.get(mid);int c=compare(m.tag,m.id,tag,id);if(c==0)return mid;if(c<0)lo=mid+1;else hi=mid-1;}
			return -lo-1;
		}
		Item get(int tag,int id){int i=search(tag,id);return i>=0?items.get(i):null;}
		List<Integer> tags(){List<Integer> t=new ArrayList<Integer>();for(Item i:items)if(t.isEmpty()||t.get(t.size()-1)!=i.tag)t.add(i.tag);return t;}
		List<Item> tagged(int tag){List<Item> l=new ArrayList<Item>();for(Item i:items)if(i.tag==tag)l.add(i);return l;}
	}
	static int compare(int tagA,int idA,int tagB,int idB){int c=Integer.compareUnsigned(tagA,tagB);return c!=0?c:Integer.compare(idA,idB);}

	private final Map<Integer,Collection> collections=new HashMap<Integer,Collection>();
	/** The Java side of a Collection token (null for NIL or an unknown value). */
	Collection collection(int token){return collections.get(token);}
	/** A new empty collection (NewCollection); also for the Window Manager's collection calls. */
	Collection newCollection() throws M68kCpuException {int token=memory.allocate(8,4);memory.writeLong(token,fourcc("cltn"));memory.writeLong(token+4,0);Collection c=new Collection(token);collections.put(token,c);return c;}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		int selector=cpu.d[0]&0xffff;
		switch(selector){
			case 0x00:{Collection c=newCollection();result(cpu,4,c.token);return true;} // NewCollection
			case 0x01:{int[] a=pop(cpu,4);Collection c=collection(a[0]);if(c!=null&&--c.owners<=0)dispose(c);return true;} // DisposeCollection
			case 0x02:{int[] a=pop(cpu,4);Collection c=collection(a[0]);if(c!=null)c.owners++;result(cpu,4,c==null?0:c.token);return true;} // CloneCollection
			case 0x03:{int[] a=pop(cpu,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.owners);return true;} // CountCollectionOwners
			case 0x04:{int[] a=pop(cpu,4,4);Collection s=collection(a[0]),d=collection(a[1]); // CopyCollection(src, dst): dst becomes a copy, returned
				if(s!=null&&d!=null&&s!=d){d.items.clear();for(Item i:s.items)d.items.add(new Item(i.tag,i.id,i.attributes,i.data.clone()));d.defaultAttributes=s.defaultAttributes;}
				result(cpu,4,d==null?0:d.token);return true;}
			case 0x05:{int[] a=pop(cpu,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.defaultAttributes);return true;} // GetCollectionDefaultAttributes
			case 0x06:{int[] a=pop(cpu,4,4,4);Collection c=collection(a[0]);if(c!=null)c.defaultAttributes=c.defaultAttributes&~a[1]|a[2]&a[1];return true;} // SetCollectionDefaultAttributes(c, which, new)
			case 0x07:{int[] a=pop(cpu,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.items.size());return true;} // CountCollectionItems
			case 0x08:{int[] a=pop(cpu,4,4,4,4,4); // AddCollectionItem(c, tag, id, itemSize, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[3]<0||a[3]>0&&a[4]==0){fail(cpu,c,PARAM_ERR);return true;}
				fail(cpu,c,add(c,a[1],a[2],memory.readBytes(a[4],a[3])));return true;}
			case 0x09:{int[] a=pop(cpu,4,4,4,4,4); // GetCollectionItem(c, tag, id, *itemSize, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=c.get(a[1],a[2]);if(i==null){fail(cpu,c,COLLECTION_ITEM_NOT_FOUND_ERR);return true;}
				give(i,a[3],a[4]);result(cpu,2,NO_ERR);return true;}
			case 0x0a:{int[] a=pop(cpu,4,4,4); // RemoveCollectionItem(c, tag, id)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				int i=c.search(a[1],a[2]);fail(cpu,c,i<0?COLLECTION_ITEM_NOT_FOUND_ERR:removeAt(c,i));return true;}
			case 0x0b:{int[] a=pop(cpu,4,4,4,4,4); // SetCollectionItemInfo(c, tag, id, whichAttributes, newAttributes)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=c.get(a[1],a[2]);if(i==null){fail(cpu,c,COLLECTION_ITEM_NOT_FOUND_ERR);return true;}
				i.attributes=i.attributes&~a[3]|a[4]&a[3];result(cpu,2,NO_ERR);return true;}
			case 0x0c:{int[] a=pop(cpu,4,4,4,4,4,4); // GetCollectionItemInfo(c, tag, id, *index, *itemSize, *attributes)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				int i=c.search(a[1],a[2]);if(i<0){fail(cpu,c,COLLECTION_ITEM_NOT_FOUND_ERR);return true;}
				Item item=c.items.get(i);putLong(a[3],i+1);putLong(a[4],item.data.length);putLong(a[5],item.attributes);result(cpu,2,NO_ERR);return true;}
			case 0x0d:{int[] a=pop(cpu,4,4,4,4); // ReplaceIndexedCollectionItem(c, index, itemSize, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[2]<0||a[2]>0&&a[3]==0){fail(cpu,c,PARAM_ERR);return true;}
				fail(cpu,c,replace(c,a[1],memory.readBytes(a[3],a[2])));return true;}
			case 0x0e:{int[] a=pop(cpu,4,4,4,4); // GetIndexedCollectionItem(c, index, *itemSize, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=indexed(c,a[1]);if(i==null){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				give(i,a[2],a[3]);result(cpu,2,NO_ERR);return true;}
			case 0x0f:{int[] a=pop(cpu,4,4); // RemoveIndexedCollectionItem(c, index)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				fail(cpu,c,a[1]<1||a[1]>c.items.size()?COLLECTION_INDEX_RANGE_ERR:removeAt(c,a[1]-1));return true;}
			case 0x10:{int[] a=pop(cpu,4,4,4,4); // SetIndexedCollectionItemInfo(c, index, which, new)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=indexed(c,a[1]);if(i==null){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				i.attributes=i.attributes&~a[2]|a[3]&a[2];result(cpu,2,NO_ERR);return true;}
			case 0x11:{int[] a=pop(cpu,4,4,4,4,4,4); // GetIndexedCollectionItemInfo(c, index, *tag, *id, *itemSize, *attributes)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=indexed(c,a[1]);if(i==null){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				putLong(a[2],i.tag);putLong(a[3],i.id);putLong(a[4],i.data.length);putLong(a[5],i.attributes);result(cpu,2,NO_ERR);return true;}
			case 0x12:{int[] a=pop(cpu,4,4);Collection c=collection(a[0]);result(cpu,1,c!=null&&!c.tagged(a[1]).isEmpty()?1:0);return true;} // CollectionTagExists
			case 0x13:{int[] a=pop(cpu,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.tags().size());return true;} // CountCollectionTags
			case 0x14:{int[] a=pop(cpu,4,4,4); // GetIndexedCollectionTag(c, tagIndex, *tag)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				List<Integer> tags=c.tags();if(a[1]<1||a[1]>tags.size()){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				putLong(a[2],tags.get(a[1]-1));result(cpu,2,NO_ERR);return true;}
			case 0x15:{int[] a=pop(cpu,4,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.tagged(a[1]).size());return true;} // CountTaggedCollectionItems
			case 0x16:{int[] a=pop(cpu,4,4,4,4,4); // GetTaggedCollectionItem(c, tag, whichItem, *itemSize, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				List<Item> l=c.tagged(a[1]);if(a[2]<1||a[2]>l.size()){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				give(l.get(a[2]-1),a[3],a[4]);result(cpu,2,NO_ERR);return true;}
			case 0x17:{int[] a=pop(cpu,4,4,4,4,4,4,4); // GetTaggedCollectionItemInfo(c, tag, whichItem, *id, *index, *itemSize, *attributes)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				List<Item> l=c.tagged(a[1]);if(a[2]<1||a[2]>l.size()){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				Item i=l.get(a[2]-1);putLong(a[3],i.id);putLong(a[4],c.items.indexOf(i)+1);putLong(a[5],i.data.length);putLong(a[6],i.attributes);result(cpu,2,NO_ERR);return true;}
			case 0x18:{int[] a=pop(cpu,4,4,4);Collection c=collection(a[0]); // PurgeCollection(c, which, matching): unlocked items whose attributes match
				if(c!=null)c.items.removeIf(i->!i.locked()&&(i.attributes&a[1])==(a[2]&a[1]));return true;}
			case 0x19:{int[] a=pop(cpu,4,4);Collection c=collection(a[0]);if(c!=null)c.items.removeIf(i->i.tag==a[1]&&!i.locked());return true;} // PurgeCollectionTag
			case 0x1a:{int[] a=pop(cpu,4);Collection c=collection(a[0]);if(c!=null)c.items.clear();return true;} // EmptyCollection
			case 0x1b:{int[] a=pop(cpu,4,4,4); // FlattenCollection(c, flattenProc, refCon): the persistent items
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				fail(cpu,c,flattenTo(cpu,a[1],a[2],flatten(c,PERSISTENCE_MASK,PERSISTENCE_MASK)));return true;}
			case 0x1c:{int[] a=pop(cpu,4,4,4,4,4); // FlattenPartialCollection(c, flattenProc, refCon, which, matching)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				fail(cpu,c,flattenTo(cpu,a[1],a[2],flatten(c,a[3],a[4])));return true;}
			case 0x1d:{int[] a=pop(cpu,4,4,4); // UnflattenCollection(c, flattenProc, refCon)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				fail(cpu,c,unflattenFrom(cpu,c,a[1],a[2]));return true;}
			case 0x1e:{int[] a=pop(cpu,4);Collection c=collection(a[0]);result(cpu,4,c==null?0:c.exceptionProc);return true;} // GetCollectionExceptionProc
			case 0x1f:{int[] a=pop(cpu,4,4);Collection c=collection(a[0]);if(c!=null)c.exceptionProc=a[1];return true;} // SetCollectionExceptionProc
			case 0x20:{int[] a=pop(cpu,2);result(cpu,4,getNewCollection(a[0]));return true;} // GetNewCollection(collectionID): 'cltn' resource
			case 0x21:{int[] a=pop(cpu,4,4,4,4); // AddCollectionItemHdl(c, tag, id, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[3]==0){fail(cpu,c,PARAM_ERR);return true;}
				fail(cpu,c,add(c,a[1],a[2],handleBytes(a[3])));return true;}
			case 0x22:{int[] a=pop(cpu,4,4,4,4); // GetCollectionItemHdl(c, tag, id, itemData): the Handle is resized to the data
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=c.get(a[1],a[2]);if(i==null){fail(cpu,c,COLLECTION_ITEM_NOT_FOUND_ERR);return true;}
				if(a[3]==0){fail(cpu,c,PARAM_ERR);return true;}
				setHandle(a[3],i.data);result(cpu,2,NO_ERR);return true;}
			case 0x23:{int[] a=pop(cpu,4,4,4); // ReplaceIndexedCollectionItemHdl(c, index, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[2]==0){fail(cpu,c,PARAM_ERR);return true;}
				fail(cpu,c,replace(c,a[1],handleBytes(a[2])));return true;}
			case 0x24:{int[] a=pop(cpu,4,4,4); // GetIndexedCollectionItemHdl(c, index, itemData)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				Item i=indexed(c,a[1]);if(i==null){fail(cpu,c,COLLECTION_INDEX_RANGE_ERR);return true;}
				if(a[2]==0){fail(cpu,c,PARAM_ERR);return true;}
				setHandle(a[2],i.data);result(cpu,2,NO_ERR);return true;}
			case 0x25:{int[] a=pop(cpu,4,4); // FlattenCollectionToHdl(c, flattened)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[1]==0){fail(cpu,c,PARAM_ERR);return true;}
				setHandle(a[1],flatten(c,PERSISTENCE_MASK,PERSISTENCE_MASK));result(cpu,2,NO_ERR);return true;}
			case 0x26:{int[] a=pop(cpu,4,4); // UnflattenCollectionFromHdl(c, flattened)
				Collection c=collection(a[0]);if(c==null){result(cpu,2,PARAM_ERR);return true;}
				if(a[1]==0){fail(cpu,c,PARAM_ERR);return true;}
				fail(cpu,c,unflatten(c,handleBytes(a[1])));return true;}
			default:return false;
		}
	}

	/** Stores an OSErr result; a failure first goes through the collection's exception procedure, whose answer is returned. */
	private void fail(M68kCpu cpu,Collection c,int status) throws M68kException {
		if(status!=NO_ERR&&c!=null&&c.exceptionProc!=0)status=(short)call(cpu,c.exceptionProc,2,c.token,4,status,2);
		result(cpu,2,status);
	}
	private void dispose(Collection c){collections.remove(c.token);memory.free(c.token);}
	private static Item indexed(Collection c,int index){return index>=1&&index<=c.items.size()?c.items.get(index-1):null;}
	/** GetCollectionItem & co.: the size to *itemSize, all of the data to itemData (each only when not NIL). */
	private void give(Item i,int sizePtr,int dataPtr) throws M68kCpuException {putLong(sizePtr,i.data.length);if(dataPtr!=0&&i.data.length>0)memory.load(dataPtr,i.data);}
	/** Adds an item or replaces the data of the one with that tag and id (keeping its attributes); locked items stay. */
	int add(Collection c,int tag,int id,byte[] data){
		int i=c.search(tag,id);
		if(i>=0){Item item=c.items.get(i);if(item.locked())return COLLECTION_ITEM_LOCKED_ERR;item.data=data;return NO_ERR;}
		c.items.add(-i-1,new Item(tag,id,c.defaultAttributes,data));return NO_ERR;
	}
	private static int replace(Collection c,int index,byte[] data){
		Item i=indexed(c,index);if(i==null)return COLLECTION_INDEX_RANGE_ERR;
		if(i.locked())return COLLECTION_ITEM_LOCKED_ERR;
		i.data=data;return NO_ERR;
	}
	private static int removeAt(Collection c,int i){if(c.items.get(i).locked())return COLLECTION_ITEM_LOCKED_ERR;c.items.remove(i);return NO_ERR;}

	/** The flattened ('flac') form of the items whose attributes match ({@code attributes & which == matching & which}). */
	static byte[] flatten(Collection c,int which,int matching){
		List<Item> l=new ArrayList<Item>();for(Item i:c.items)if((i.attributes&which)==(matching&which))l.add(i);
		int dataSize=0;for(Item i:l)dataSize+=4+(i.data.length+1&~1);
		byte[] out=new byte[12+16*l.size()+4+dataSize];
		put32(out,0,FLATTENED_VERSION);put32(out,4,c.defaultAttributes);put32(out,8,l.size());
		int table=12,data=12+16*l.size()+4,offset=0;put32(out,data-4,dataSize);
		for(Item i:l){
			put32(out,table,i.tag);put32(out,table+4,i.id);put32(out,table+8,i.attributes);put32(out,table+12,offset+4);table+=16;
			put32(out,data+offset,i.data.length);System.arraycopy(i.data,0,out,data+offset+4,i.data.length);offset+=4+(i.data.length+1&~1);
		}
		return out;
	}
	/** Adds the items of a flattened collection (replacing items with the same tag and id); the default attributes come along. */
	static int unflatten(Collection c,byte[] b){
		if(b.length<16)return PARAM_ERR;
		if(be32(b,0)!=FLATTENED_VERSION)return COLLECTION_VERSION_ERR;
		int count=be32(b,8);if(count<0||12L+16L*count+4>b.length)return PARAM_ERR;
		int data=12+16*count+4,dataSize=be32(b,data-4);if(dataSize<0||(long)data+dataSize>b.length)return PARAM_ERR;
		List<Item> items=new ArrayList<Item>();
		for(int n=0;n<count;n++){
			int t=12+16*n,offset=be32(b,t+12);
			if(offset<4||offset>dataSize)return PARAM_ERR;
			int size=be32(b,data+offset-4);if(size<0||offset+(long)size>dataSize)return PARAM_ERR;
			items.add(new Item(be32(b,t),be32(b,t+4),be32(b,t+8),java.util.Arrays.copyOfRange(b,data+offset,data+offset+size)));
		}
		c.defaultAttributes=be32(b,4);
		for(Item i:items){int at=c.search(i.tag,i.id);if(at>=0)c.items.set(at,i);else c.items.add(-at-1,i);}
		return NO_ERR;
	}
	/** Hands the flattened data to the program's CollectionFlattenUPP (size, data, refCon) in pieces: header, item table,
	 *  data size, then each item's data. */
	private int flattenTo(M68kCpu cpu,int proc,int refCon,byte[] flat) throws M68kException {
		if(proc==0)return PARAM_ERR;
		int count=be32(flat,8),tableEnd=12+16*count+4;
		List<int[]> pieces=new ArrayList<int[]>();pieces.add(new int[]{0,12});if(count>0)pieces.add(new int[]{12,16*count});pieces.add(new int[]{tableEnd-4,4});
		for(int at=tableEnd;at<flat.length;){int size=be32(flat,at);int n=4+(size+1&~1);pieces.add(new int[]{at,n});at+=n;}
		int buffer=memory.allocate(Math.max(1,flat.length),4);
		try{
			memory.load(buffer,flat);
			for(int[] p:pieces){int err=(short)call(cpu,proc,2,p[1],4,buffer+p[0],4,refCon,4);if(err!=NO_ERR)return err;}
			return NO_ERR;
		}finally{memory.free(buffer);}
	}
	/** Reads a flattened collection through the program's CollectionFlattenUPP, which fills (size, data) from its source. */
	private int unflattenFrom(M68kCpu cpu,Collection c,int proc,int refCon) throws M68kException {
		if(proc==0)return PARAM_ERR;
		java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
		byte[] header=read(cpu,proc,refCon,12);if(header==null)return lastReadError;out.write(header,0,12);
		if(be32(header,0)!=FLATTENED_VERSION)return COLLECTION_VERSION_ERR;
		int count=be32(header,8);if(count<0||count>1<<20)return PARAM_ERR;
		byte[] table=read(cpu,proc,refCon,16*count+4);if(table==null)return lastReadError;out.write(table,0,table.length);
		int dataSize=be32(table,16*count);if(dataSize<0||dataSize>1<<28)return PARAM_ERR;
		byte[] data=read(cpu,proc,refCon,dataSize);if(data==null)return lastReadError;out.write(data,0,data.length);
		return unflatten(c,out.toByteArray());
	}
	private int lastReadError;
	private byte[] read(M68kCpu cpu,int proc,int refCon,int size) throws M68kException {
		if(size==0)return new byte[0];
		int buffer=memory.allocate(size,4);
		try{int err=(short)call(cpu,proc,2,size,4,buffer,4,refCon,4);if(err!=NO_ERR){lastReadError=err;return null;}return memory.readBytes(buffer,size);}
		finally{memory.free(buffer);}
	}
	/** GetNewCollection: a new collection from the 'cltn' resource {@code id} (NIL when there is none). */
	private int getNewCollection(int id) throws M68kCpuException {
		ResourceFile68k.Resource r=tb.resources.find(fourcc("cltn"),id);
		byte[] b=r==null?null:r.data();if(b==null||b.length<4)return 0;
		Collection c=newCollection();
		int count=be32(b,0),at=4;
		for(int n=0;n<count&&at+14<=b.length;n++){
			int tag=be32(b,at),itemId=be32(b,at+4),attributes=be32(b,at+8),len=be16(b,at+12);at+=14;
			if(at+len>b.length)break;
			int i=c.search(tag,itemId);Item item=new Item(tag,itemId,attributes,java.util.Arrays.copyOfRange(b,at,at+len));
			if(i>=0)c.items.set(i,item);else c.items.add(-i-1,item);
			at+=len;if((at&1)!=0)at++;
		}
		return c.token;
	}
}
