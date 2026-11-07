import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User data lists of the native Movie Toolbox (NewUserData, AddUserData, GetUserData, CountUserDataType...): a UserData is a
 * Handle the program only passes back, its items live here - typed byte arrays, numbered from 1 within their type, as in a
 * 'udta' atom. Text items keep QuickTime's layout, one [length, region code, text] entry per language (AddUserDataText).
 * PutUserDataIntoHandle / NewUserDataFromHandle use the atom form (size, type, data per item). Avid VideoShop 3.0J makes a
 * user data list at start-up (NewUserData was missing: the program stopped there).
 */
final class QTUserData68k {
	static final int USER_DATA_ITEM_NOT_FOUND=-2026,PARAM_ERR=-50;

	static final class Item {final int type;byte[] data;Item(int type,byte[] data){this.type=type;this.data=data;}}

	private final Toolbox68k tb;
	private final M68kMemory memory;
	private final Map<Integer,List<Item>> lists=new HashMap<Integer,List<Item>>();

	QTUserData68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** A new, empty user data list (also what GetMovieUserData / GetTrackUserData hand out). */
	int create() throws M68kCpuException {int h=tb.api.createHandle(new byte[4]);lists.put(h,new ArrayList<Item>());return h;}
	void dispose(int ud){if(lists.remove(ud)!=null)tb.api.disposeHandleAndFree(ud);}

	/** Handles the user data selectors of the Movie Toolbox; false for any other selector. */
	boolean dispatch(M68kCpu cpu,int selector) throws M68kException {
		int sp=cpu.a[7];
		switch(selector){
			case 0x012f:{int ptr=memory.readLong(sp);cpu.a[7]+=4;int ud=create();if(ptr!=0)memory.writeLong(ptr,ud);memory.writeWord(cpu.a[7],0);return true;} // NewUserData(VAR theUserData)
			case 0x0130:{int ud=memory.readLong(sp);cpu.a[7]+=4;boolean known=lists.containsKey(ud);dispose(ud);memory.writeWord(cpu.a[7],known?0:PARAM_ERR);return true;} // DisposeUserData(theUserData)
			case 0x0131:{int ptr=memory.readLong(sp),h=memory.readLong(sp+4);cpu.a[7]+=8; // NewUserDataFromHandle(h, VAR theUserData)
				int ud=create();byte[] b=h==0?null:tb.api.handleContents(h);
				if(b!=null)for(int at=0;at+8<=b.length;){int size=u4(b,at);if(size<8||at+size>b.length)break;byte[] data=new byte[size-8];System.arraycopy(b,at+8,data,0,data.length);lists.get(ud).add(new Item(u4(b,at+4),data));at+=size;}
				if(ptr!=0)memory.writeLong(ptr,ud);memory.writeWord(cpu.a[7],0);return true;}
			case 0x0132:{int h=memory.readLong(sp),ud=memory.readLong(sp+4);cpu.a[7]+=8;List<Item> items=lists.get(ud); // PutUserDataIntoHandle(theUserData, h)
				if(items==null||h==0){memory.writeWord(cpu.a[7],PARAM_ERR);return true;}
				ByteArrayOutputStream out=new ByteArrayOutputStream();for(Item i:items){put4(out,8+i.data.length);put4(out,i.type);out.write(i.data,0,i.data.length);}
				tb.api.loadHandle(h,out.toByteArray());memory.writeWord(cpu.a[7],0);return true;}
			case 0x009f:{int type=memory.readLong(sp),data=memory.readLong(sp+4),ud=memory.readLong(sp+8);cpu.a[7]+=12;List<Item> items=lists.get(ud); // AddUserData(theUserData, data, udType)
				byte[] b=data==0?null:tb.api.handleContents(data);
				if(items==null||b==null){memory.writeWord(cpu.a[7],PARAM_ERR);return true;}
				items.add(new Item(type,b));memory.writeWord(cpu.a[7],0);return true;}
			case 0x009e:{int index=memory.readLong(sp),type=memory.readLong(sp+4),data=memory.readLong(sp+8),ud=memory.readLong(sp+12);cpu.a[7]+=16; // GetUserData(theUserData, data, udType, index)
				Item i=item(ud,type,index);if(i!=null&&data!=0)tb.api.loadHandle(data,i.data);
				memory.writeWord(cpu.a[7],i==null?USER_DATA_ITEM_NOT_FOUND:0);return true;}
			case 0x00a0:{int index=memory.readLong(sp),type=memory.readLong(sp+4),ud=memory.readLong(sp+8);cpu.a[7]+=12; // RemoveUserData(theUserData, udType, index)
				Item i=item(ud,type,index);if(i!=null)lists.get(ud).remove(i);memory.writeWord(cpu.a[7],i==null?USER_DATA_ITEM_NOT_FOUND:0);return true;}
			case 0x014b:{int type=memory.readLong(sp),ud=memory.readLong(sp+4);cpu.a[7]+=8;int n=0;List<Item> items=lists.get(ud); // CountUserDataType(theUserData, udType) : short
				if(items!=null)for(Item i:items)if(i.type==type)n++;memory.writeWord(cpu.a[7],n);return true;}
			case 0x01a5:{int type=memory.readLong(sp),ud=memory.readLong(sp+4);cpu.a[7]+=8;List<Item> items=lists.get(ud); // GetNextUserDataType(theUserData, udType) : the type after udType (0: the first), 0 after the last
				List<Integer> types=new ArrayList<Integer>();if(items!=null)for(Item i:items)if(!types.contains(i.type))types.add(i.type);
				int at=type==0?-1:types.indexOf(type);memory.writeLong(cpu.a[7],at+1<types.size()&&(type==0||at>=0)?types.get(at+1):0);return true;}
			case 0x0126:{int index=memory.readLong(sp),type=memory.readLong(sp+4),size=memory.readLong(sp+8),data=memory.readLong(sp+12),ud=memory.readLong(sp+16);cpu.a[7]+=20; // GetUserDataItem(theUserData, data, size, udType, index)
				Item i=item(ud,type,index);if(i!=null&&data!=0)memory.load(data,i.data,0,Math.min(Math.max(0,size),i.data.length));
				memory.writeWord(cpu.a[7],i==null?USER_DATA_ITEM_NOT_FOUND:0);return true;}
			case 0x012e:{int index=memory.readLong(sp),type=memory.readLong(sp+4),size=memory.readLong(sp+8),data=memory.readLong(sp+12),ud=memory.readLong(sp+16);cpu.a[7]+=20; // SetUserDataItem(theUserData, data, size, udType, index): replaces that item, adds one past the last
				List<Item> items=lists.get(ud);if(items==null||size<0||data==0&&size>0){memory.writeWord(cpu.a[7],PARAM_ERR);return true;}
				byte[] b=memory.readBytes(data,size);Item i=item(ud,type,index);if(i!=null)i.data=b;else items.add(new Item(type,b));
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x014c:{int region=(short)memory.readWord(sp),index=memory.readLong(sp+2),type=memory.readLong(sp+6),data=memory.readLong(sp+10),ud=memory.readLong(sp+14);cpu.a[7]+=18; // AddUserDataText(theUserData, data, udType, index, itlRegionTag)
				List<Item> items=lists.get(ud);byte[] text=data==0?null:tb.api.handleContents(data);
				if(items==null||text==null){memory.writeWord(cpu.a[7],PARAM_ERR);return true;}
				Item i=item(ud,type,index);if(i==null){i=new Item(type,new byte[0]);items.add(i);}
				ByteArrayOutputStream out=new ByteArrayOutputStream();for(int[] e:textEntries(i.data))if(e[1]!=region)out.write(i.data,e[0]-4,e[2]+4);
				put2(out,text.length);put2(out,region);out.write(text,0,text.length);i.data=out.toByteArray();
				memory.writeWord(cpu.a[7],0);return true;}
			case 0x014d:{int region=(short)memory.readWord(sp),index=memory.readLong(sp+2),type=memory.readLong(sp+6),data=memory.readLong(sp+10),ud=memory.readLong(sp+14);cpu.a[7]+=18; // GetUserDataText(theUserData, data, udType, index, itlRegionTag)
				Item i=item(ud,type,index);int[] found=null;if(i!=null)for(int[] e:textEntries(i.data))if(e[1]==region){found=e;break;}
				if(found!=null&&data!=0){byte[] text=new byte[found[2]];System.arraycopy(i.data,found[0],text,0,text.length);tb.api.loadHandle(data,text);}
				memory.writeWord(cpu.a[7],found==null?USER_DATA_ITEM_NOT_FOUND:0);return true;}
			case 0x014e:{int region=(short)memory.readWord(sp),index=memory.readLong(sp+2),type=memory.readLong(sp+6),ud=memory.readLong(sp+10);cpu.a[7]+=14; // RemoveUserDataText(theUserData, udType, index, itlRegionTag)
				Item i=item(ud,type,index);boolean removed=false;
				if(i!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();for(int[] e:textEntries(i.data)){if(e[1]==region){removed=true;continue;}out.write(i.data,e[0]-4,e[2]+4);}i.data=out.toByteArray();}
				memory.writeWord(cpu.a[7],removed?0:USER_DATA_ITEM_NOT_FOUND);return true;}
			default:return false;
		}
	}

	/** The index-th item (from 1) of that type in the list, or null. */
	private Item item(int ud,int type,int index){
		List<Item> items=lists.get(ud);if(items==null||index<1)return null;
		int n=0;for(Item i:items)if(i.type==type&&++n==index)return i;
		return null;
	}
	/** The language entries of a text item: {text offset, region code, text length} each. */
	private static List<int[]> textEntries(byte[] b){
		List<int[]> entries=new ArrayList<int[]>();
		for(int at=0;at+4<=b.length;){int length=(b[at]&255)<<8|b[at+1]&255;if(at+4+length>b.length)break;entries.add(new int[]{at+4,(short)((b[at+2]&255)<<8|b[at+3]&255),length});at+=4+length;}
		return entries;
	}
	private static int u4(byte[] b,int at){return (b[at]&255)<<24|(b[at+1]&255)<<16|(b[at+2]&255)<<8|b[at+3]&255;}
	private static void put4(ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}
	private static void put2(ByteArrayOutputStream out,int v){out.write(v>>>8);out.write(v);}
}
