import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QT atom containers (QTNewAtomContainer, QTInsertChild, QTFindChildByID...) of the native Movie
 * Toolbox. A container is an ordinary Handle holding the real on-disk layout - a 'sean' root atom
 * whose children follow it depth first, every atom being a 20-byte header (size, type, id,
 * reserved, child count, reserved) followed by its children or its leaf data - so containers read
 * from files (QuickTime Preferences, sprite samples) and containers built here look the same, and
 * a QTAtom is what it is on a real Mac: the byte offset of the atom inside the Handle.
 *
 * Every call parses the Handle into a tree, works on the tree and writes it back; offsets are then
 * recomputed exactly as QuickTime lays them out, so atoms the program still holds stay valid as long
 * as nothing before them moved (the documented rule).
 */
final class QTAtoms68k {
	static final int SEAN=0x7365616e,HEADER=20;
	static final int CANNOT_FIND_ATOM=-2101,NOT_LEAF_ATOM=-2102,ATOM_INDEX_INVALID=-2104,DUPLICATE_ATOM=-2105,INVALID_ATOM=-2106,INVALID_CONTAINER=-2107,CANNOT_BE_LEAF=-2109,PARAM_ERR=-50;

	static final class Atom {
		int type,id;byte[] data=new byte[0];final List<Atom> children=new ArrayList<Atom>();Atom parent;int offset;
		Atom(int type,int id){this.type=type;this.id=id;}
		Atom copy(){Atom a=new Atom(type,id);a.data=data.clone();for(Atom c:children){Atom cc=c.copy();cc.parent=a;a.children.add(cc);}return a;}
		int size(){int n=HEADER;if(children.isEmpty())n+=data.length;else for(Atom c:children)n+=c.size();return n;}
	}

	private final Toolbox68k tb;
	private final M68kMemory memory;

	QTAtoms68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	/** Handles the atom container and preference selectors of the Movie Toolbox; false for any other selector. */
	boolean dispatch(M68kCpu cpu,int selector) throws M68kException {
		int sp=cpu.a[7];
		switch(selector){
			case 0x020c:{int ptr=memory.readLong(sp);cpu.a[7]+=4;int h=tb.api.createHandle(serialize(new Atom(SEAN,1)));if(ptr!=0)memory.writeLong(ptr,h);memory.writeWord(cpu.a[7],0);return true;} // QTNewAtomContainer(container*)
			case 0x020d:{int h=memory.readLong(sp);cpu.a[7]+=4;if(h!=0)tb.api.disposeHandleAndFree(h);memory.writeWord(cpu.a[7],0);return true;} // QTDisposeAtomContainer
			case 0x020e:{int current=memory.readLong(sp),parent=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTGetNextChildType(container, parent, currentChildType): the type after currentChildType (0 = first)
				Atom p=find(parse(h),parent);int result=0;
				if(p!=null){List<Integer> types=new ArrayList<Integer>();for(Atom c:p.children)if(!types.contains(c.type))types.add(c.type);
					int i=current==0?-1:types.indexOf(current);if(i+1<types.size())result=types.get(i+1);}
				memory.writeLong(cpu.a[7],result);return true;}
			case 0x020f:{int type=memory.readLong(sp),parent=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTCountChildrenOfType
				Atom p=find(parse(h),parent);int n=0;if(p!=null)for(Atom c:p.children)if(c.type==type)n++;memory.writeWord(cpu.a[7],n);return true;}
			case 0x0210:{int idPtr=memory.readLong(sp),index=(short)memory.readWord(sp+4),type=memory.readLong(sp+6),parent=memory.readLong(sp+10),h=memory.readLong(sp+14);cpu.a[7]+=18; // QTFindChildByIndex(container, parent, type, index, id*)
				Atom p=find(parse(h),parent);Atom found=null;int n=0;
				if(p!=null)for(Atom c:p.children){if(type!=0&&c.type!=type)continue;if(++n==index){found=c;break;}}
				if(found!=null&&idPtr!=0)memory.writeLong(idPtr,found.id);memory.writeLong(cpu.a[7],found==null?0:found.offset);return true;}
			case 0x021d:{int indexPtr=memory.readLong(sp),id=memory.readLong(sp+4),type=memory.readLong(sp+8),parent=memory.readLong(sp+12),h=memory.readLong(sp+16);cpu.a[7]+=20; // QTFindChildByID(container, parent, type, id, index*)
				Atom p=find(parse(h),parent);Atom found=null;int n=0;
				if(p!=null)for(Atom c:p.children){if(c.type!=type)continue;n++;if(c.id==id){found=c;break;}}
				if(found!=null&&indexPtr!=0)memory.writeWord(indexPtr,n);memory.writeLong(cpu.a[7],found==null?0:found.offset);return true;}
			case 0x0200:{int nextPtr=memory.readLong(sp),current=memory.readLong(sp+4),parent=memory.readLong(sp+8),h=memory.readLong(sp+12);cpu.a[7]+=16; // QTNextChildAnyType(container, parent, currentChild, next*)
				Atom p=find(parse(h),parent);int next=0;
				if(p!=null){int i=-1;if(current!=0)for(int k=0;k<p.children.size();k++)if(p.children.get(k).offset==current)i=k;if(i+1<p.children.size())next=p.children.get(i+1).offset;}
				if(nextPtr!=0)memory.writeLong(nextPtr,next);memory.writeWord(cpu.a[7],p==null?CANNOT_FIND_ATOM:0);return true;}
			case 0x0211:{int data=memory.readLong(sp),size=memory.readLong(sp+4),atom=memory.readLong(sp+8),h=memory.readLong(sp+12);cpu.a[7]+=16; // QTSetAtomData(container, atom, size, data)
				Atom root=parse(h);Atom a=find(root,atom);int err;
				if(a==null)err=CANNOT_FIND_ATOM;else if(!a.children.isEmpty())err=NOT_LEAF_ATOM;else{a.data=size>0&&data!=0?memory.readBytes(data,size):new byte[0];store(h,root);err=0;}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0212:{int target=memory.readLong(sp),atom=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTCopyAtomDataToHandle
				Atom a=find(parse(h),atom);int err;
				if(a==null)err=CANNOT_FIND_ATOM;else if(!a.children.isEmpty())err=NOT_LEAF_ATOM;else if(target==0)err=PARAM_ERR;else{tb.api.loadHandle(target,a.data);err=0;}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0213:{int actualPtr=memory.readLong(sp),target=memory.readLong(sp+4),size=memory.readLong(sp+8);boolean lessOk=memory.readByte(sp+12)!=0;int atom=memory.readLong(sp+14),h=memory.readLong(sp+18);cpu.a[7]+=22; // QTCopyAtomDataToPtr(container, atom, sizeOrLessOK, size, target, actual*)
				Atom a=find(parse(h),atom);int err=0;
				if(a==null)err=CANNOT_FIND_ATOM;else if(!a.children.isEmpty())err=NOT_LEAF_ATOM;
				else{int n=Math.min(size,a.data.length);if(!lessOk&&a.data.length!=size&&a.data.length>size)n=size;if(target!=0&&n>0)memory.load(target,java.util.Arrays.copyOf(a.data,n));if(actualPtr!=0)memory.writeLong(actualPtr,a.data.length);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0232:{int idPtr=memory.readLong(sp),typePtr=memory.readLong(sp+4),atom=memory.readLong(sp+8),h=memory.readLong(sp+12);cpu.a[7]+=16; // QTGetAtomTypeAndID
				Atom a=find(parse(h),atom);if(a!=null){if(typePtr!=0)memory.writeLong(typePtr,a.type);if(idPtr!=0)memory.writeLong(idPtr,a.id);}
				memory.writeWord(cpu.a[7],a==null?CANNOT_FIND_ATOM:0);return true;}
			case 0x0214:{int targetPtr=memory.readLong(sp),atom=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTCopyAtom(container, atom, target*): a new container whose root holds a copy of the atom
				Atom a=find(parse(h),atom);int err=0;
				if(a==null)err=CANNOT_FIND_ATOM;else{Atom root=new Atom(SEAN,1);if(atom==0){for(Atom c:a.children){Atom cc=c.copy();cc.parent=root;root.children.add(cc);}}else{Atom cc=a.copy();cc.parent=root;root.children.add(cc);}
					int nh=tb.api.createHandle(serialize(root));if(targetPtr!=0)memory.writeLong(targetPtr,nh);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0215:{int h=memory.readLong(sp);cpu.a[7]+=4;if(h!=0)tb.api.setHandleState(h,tb.api.handleState(h)|0x80);memory.writeWord(cpu.a[7],0);return true;} // QTLockContainer
			case 0x0217:{int h=memory.readLong(sp);cpu.a[7]+=4;if(h!=0)tb.api.setHandleState(h,tb.api.handleState(h)&~0x80);memory.writeWord(cpu.a[7],0);return true;} // QTUnlockContainer
			case 0x0216:{int dataPtr=memory.readLong(sp),sizePtr=memory.readLong(sp+4),atom=memory.readLong(sp+8),h=memory.readLong(sp+12);cpu.a[7]+=16; // QTGetAtomDataPtr(container, atom, size*, ptr*): points into the (locked) Handle
				Atom a=find(parse(h),atom);int err=0;
				if(a==null)err=CANNOT_FIND_ATOM;else if(!a.children.isEmpty())err=NOT_LEAF_ATOM;else{if(sizePtr!=0)memory.writeLong(sizePtr,a.data.length);if(dataPtr!=0)memory.writeLong(dataPtr,memory.readLong(h)+a.offset+HEADER);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0218:{int newPtr=memory.readLong(sp),data=memory.readLong(sp+4),size=memory.readLong(sp+8),index=(short)memory.readWord(sp+12),id=memory.readLong(sp+14),type=memory.readLong(sp+18),parent=memory.readLong(sp+22),h=memory.readLong(sp+26);cpu.a[7]+=30; // QTInsertChild(container, parent, type, id, index, size, data, newAtom*)
				Atom root=parse(h);Atom p=find(root,parent);int err=0;
				if(p==null)err=CANNOT_FIND_ATOM;
				else if(p.data.length>0&&p.children.isEmpty())err=CANNOT_BE_LEAF; // a leaf with data cannot take children
				else{
					if(id==0){id=1;for(Atom c:p.children)if(c.type==type&&c.id>=id)id=c.id+1;} // id 0 asks for a unique id
					boolean dup=false;for(Atom c:p.children)if(c.type==type&&c.id==id)dup=true;
					if(dup)err=DUPLICATE_ATOM;
					else{
						int sameType=0;for(Atom c:p.children)if(c.type==type)sameType++;
						if(index<0||index>sameType+1)err=ATOM_INDEX_INVALID;
						else{Atom a=new Atom(type,id);a.parent=p;a.data=size>0&&data!=0?memory.readBytes(data,size):new byte[0];
							int at=p.children.size(); // index 0 = after the last atom of that type; n = as the nth atom of that type
							if(index>0){int seen=0;at=p.children.size();for(int k=0;k<p.children.size();k++){if(p.children.get(k).type==type&&++seen==index){at=k;break;}}}
							p.children.add(at,a);store(h,root);if(newPtr!=0)memory.writeLong(newPtr,a.offset);}}}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0219:{int childrenH=memory.readLong(sp),parent=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTInsertChildren(container, parent, childrenContainer): the other container's root children are appended
				Atom root=parse(h);Atom p=find(root,parent);int err=0;
				if(p==null)err=CANNOT_FIND_ATOM;else if(p.data.length>0&&p.children.isEmpty())err=CANNOT_BE_LEAF;
				else{Atom other=parse(childrenH);for(Atom c:other.children){Atom cc=c.copy();cc.parent=p;p.children.add(cc);}store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x021a:{int atom=memory.readLong(sp),h=memory.readLong(sp+4);cpu.a[7]+=8; // QTRemoveAtom
				Atom root=parse(h);Atom a=find(root,atom);int err=0;
				if(a==null||a.parent==null)err=a==null?CANNOT_FIND_ATOM:INVALID_ATOM;else{a.parent.children.remove(a);store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x021b:{int atom=memory.readLong(sp),h=memory.readLong(sp+4);cpu.a[7]+=8; // QTRemoveChildren
				Atom root=parse(h);Atom a=find(root,atom);int err=0;
				if(a==null)err=CANNOT_FIND_ATOM;else{a.children.clear();store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x021c:{int repAtom=memory.readLong(sp),repH=memory.readLong(sp+4),atom=memory.readLong(sp+8),h=memory.readLong(sp+12);cpu.a[7]+=16; // QTReplaceAtom(target, targetAtom, replacementContainer, replacementAtom)
				Atom root=parse(h);Atom a=find(root,atom);Atom r=find(parse(repH),repAtom);int err=0;
				if(a==null||r==null)err=CANNOT_FIND_ATOM;else if(a.parent==null)err=INVALID_ATOM;
				else{Atom copy=r.copy();copy.parent=a.parent;a.parent.children.set(a.parent.children.indexOf(a),copy);store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x01ff:{int atom2=memory.readLong(sp),atom1=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTSwapAtoms
				Atom root=parse(h);Atom a=find(root,atom1),b=find(root,atom2);int err=0;
				if(a==null||b==null)err=CANNOT_FIND_ATOM;else if(a.parent==null||b.parent==null)err=INVALID_ATOM;
				else{List<Atom> pa=a.parent.children,pb=b.parent.children;int ia=pa.indexOf(a),ib=pb.indexOf(b);Atom ap=a.parent;pa.set(ia,b);pb.set(ib,a);a.parent=b.parent;b.parent=ap;store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x0231:{int id=memory.readLong(sp),atom=memory.readLong(sp+4),h=memory.readLong(sp+8);cpu.a[7]+=12; // QTSetAtomID
				Atom root=parse(h);Atom a=find(root,atom);int err=0;
				if(a==null)err=CANNOT_FIND_ATOM;else{a.id=id;store(h,root);}
				memory.writeWord(cpu.a[7],err);return true;}
			case 0x02ef:{int atom=memory.readLong(sp),h=memory.readLong(sp+4);cpu.a[7]+=8;Atom a=find(parse(h),atom);memory.writeLong(cpu.a[7],a==null||a.parent==null?0:a.parent.offset);return true;} // QTGetAtomParent
			case 0x02d4:{int ptr=memory.readLong(sp),type=memory.readLong(sp+4);cpu.a[7]+=8; // GetQuickTimePreference(type, container*): a new container holding the atoms of that type from the preferences file
				Atom all=parseBytes(preferencesBytes());Atom root=new Atom(SEAN,1);
				for(Atom c:all.children)if(c.type==type){Atom cc=c.copy();cc.parent=root;root.children.add(cc);}
				int h=tb.api.createHandle(serialize(root));if(ptr!=0)memory.writeLong(ptr,h);memory.writeWord(cpu.a[7],0);return true;}
			case 0x02d5:{int h=memory.readLong(sp),type=memory.readLong(sp+4);cpu.a[7]+=8; // SetQuickTimePreference(type, container): the container's root atoms replace those of that type in the file
				Atom all=parseBytes(preferencesBytes());Atom given=parse(h);
				for(java.util.Iterator<Atom> it=all.children.iterator();it.hasNext();)if(it.next().type==type)it.remove();
				for(Atom c:given.children){Atom cc=c.copy();cc.parent=all;all.children.add(cc);}
				memory.writeWord(cpu.a[7],savePreferences(serialize(all))?0:-36);return true;}
			case 0x0273:{int ptr=memory.readLong(sp);cpu.a[7]+=4; // private: the whole QuickTime Preferences file as one atom container
				int h=tb.api.createHandle(serialize(parseBytes(preferencesBytes())));if(ptr!=0)memory.writeLong(ptr,h);memory.writeWord(cpu.a[7],0);return true;}
			case 0x0274:{int h=memory.readLong(sp);cpu.a[7]+=4;memory.writeWord(cpu.a[7],savePreferences(serialize(parse(h)))?0:-36);return true;} // private: stores the whole preferences container
			default:return false;
		}
	}

	/** The "QuickTime Preferences" file of the virtual System Folder: a 12-byte header followed by one 'sean' atom container. */
	static final int PREFERENCES_HEADER=12;
	private java.io.File preferencesFile(){
		java.io.File folder=tb.files.specialFolder(0x70726566,true); // pref
		return folder==null?null:new java.io.File(folder,"QuickTime Preferences");
	}
	/** The container bytes stored in the preferences file (with or without the header), or null when there is none. */
	private byte[] preferencesBytes(){
		java.io.File f=preferencesFile();
		if(f==null||!f.isFile())return null;
		try{byte[] b=java.nio.file.Files.readAllBytes(f.toPath());
			if(b.length>=PREFERENCES_HEADER+HEADER&&u4(b,PREFERENCES_HEADER+4)==SEAN)return java.util.Arrays.copyOfRange(b,PREFERENCES_HEADER,b.length);
			if(b.length>=HEADER&&u4(b,4)==SEAN)return b;
		}catch(java.io.IOException ignored){}
		return null;
	}
	private boolean savePreferences(byte[] container){
		java.io.File f=preferencesFile();if(f==null)return false;
		try{byte[] out=new byte[PREFERENCES_HEADER+container.length];System.arraycopy(container,0,out,PREFERENCES_HEADER,container.length);
			java.nio.file.Files.write(f.toPath(),out);
			byte[] info=new byte[16];info[0]='p';info[1]='r';info[2]='e';info[3]='f';info[4]='T';info[5]='V';info[6]='O';info[7]='D'; // 'pref' of 'TVOD' (QuickTime)
			FinderInfo68k.write(f,info);
			return true;}
		catch(java.io.IOException e){return false;}
	}
	/** Parses container bytes (null or garbage give an empty root). */
	static Atom parseBytes(byte[] b){
		Atom root=new Atom(SEAN,1);
		if(b==null||b.length<HEADER)return root;
		int size=u4(b,0);
		if(size<HEADER||size>b.length)return root;
		root.type=u4(b,4);root.id=u4(b,8);
		parseChildren(b,root,0,size);
		layout(root,0);
		return root;
	}

	/** The atom at that offset, kParentAtomIsContainer (0) being the root. */
	private static Atom find(Atom root,int offset){
		if(root==null)return null;if(offset==0)return root;return findIn(root,offset);
	}
	private static Atom findIn(Atom a,int offset){
		for(Atom c:a.children){if(c.offset==offset)return c;Atom r=findIn(c,offset);if(r!=null)return r;}
		return null;
	}

	/** Parses the Handle's bytes; a Handle that does not hold a container (fresh NewHandle, garbage) yields an empty root so lookups simply fail. */
	Atom parse(int handle) throws M68kCpuException {return parseBytes(handle==0?null:tb.api.handleContents(handle));}
	private static void parseChildren(byte[] b,Atom a,int at,int end){
		int count=u2(b,at+14);int pos=at+HEADER;
		if(count==0){a.data=java.util.Arrays.copyOfRange(b,pos,end);return;}
		for(int i=0;i<count&&pos+HEADER<=end;i++){
			int size=u4(b,pos);if(size<HEADER||pos+size>end)break;
			Atom c=new Atom(u4(b,pos+4),u4(b,pos+8));c.parent=a;a.children.add(c);
			parseChildren(b,c,pos,pos+size);
			pos+=size;
		}
	}
	/** Assigns offsets the way the atoms will be written: header, then children in order. */
	private static int layout(Atom a,int at){
		a.offset=at;int pos=at+HEADER;
		if(a.children.isEmpty())return pos+a.data.length;
		for(Atom c:a.children)pos=layout(c,pos);
		return pos;
	}
	static byte[] serialize(Atom root){
		layout(root,0);ByteArrayOutputStream out=new ByteArrayOutputStream();write(out,root);return out.toByteArray();
	}
	private static void write(ByteArrayOutputStream out,Atom a){
		int size=a.size();
		put4(out,size);put4(out,a.type);put4(out,a.id);put2(out,0);put2(out,a.children.size());put4(out,0);
		if(a.children.isEmpty())out.write(a.data,0,a.data.length);else for(Atom c:a.children)write(out,c);
	}
	/** Writes the tree back into the container Handle (resized to fit). */
	private void store(int handle,Atom root) throws M68kCpuException {
		byte[] b=serialize(root);
		if(tb.api.handleSize(handle)!=b.length)tb.api.resizeHandle(handle,b.length);
		memory.load(memory.readLong(handle),b);
	}

	private static int u2(byte[] b,int i){return (b[i]&0xff)<<8|(b[i+1]&0xff);}
	private static int u4(byte[] b,int i){return (b[i]&0xff)<<24|(b[i+1]&0xff)<<16|(b[i+2]&0xff)<<8|(b[i+3]&0xff);}
	private static void put2(ByteArrayOutputStream out,int v){out.write(v>>>8);out.write(v);}
	private static void put4(ByteArrayOutputStream out,int v){out.write(v>>>24);out.write(v>>>16);out.write(v>>>8);out.write(v);}
}
