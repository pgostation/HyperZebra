import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A PEF container ("Joy!peff", the Code Fragment Manager's format for PowerPC code): section headers, the loader
 * section's imports, exports and relocations, and the instantiation of the sections into guest memory (pattern-
 * initialized data unpacked, relocations applied against the section addresses and the resolved imports).
 *
 * Layout per Apple's "Mac OS Runtime Architectures", chapter 8.
 */
final class Pef {
	static final int KIND_CODE=0,KIND_UNPACKED_DATA=1,KIND_PATTERN_DATA=2,KIND_CONSTANT=3,KIND_LOADER=4,KIND_DEBUG=5,KIND_EXEC_DATA=6,KIND_EXCEPTION=7,KIND_TRACEBACK=8;
	static final int CLASS_CODE=0,CLASS_DATA=1,CLASS_TVECTOR=2,CLASS_TOC=3,CLASS_GLUE=4;
	/** Imported-library option: the library may be missing (every symbol from it is then 0). */
	static final int LIB_WEAK=0x40;
	/** Imported-symbol flag: the symbol may be missing (0). */
	static final int SYMBOL_WEAK=0x80;

	static final class Section {
		int nameOffset,defaultAddress,totalSize,unpackedSize,packedSize,containerOffset,kind,shareKind,alignment;
	}
	static final class Library {
		String name;int oldImpVersion,currentVersion,symbolCount,firstSymbol,options;
		boolean weak(){return (options&LIB_WEAK)!=0;}
	}
	static final class Symbol {
		String name;int symbolClass;boolean weak;
	}
	static final class Export {
		final String name;final int symbolClass,value,section;
		Export(String name,int symbolClass,int value,int section){this.name=name;this.symbolClass=symbolClass;this.value=value;this.section=section;}
	}

	final byte[] data;final int base,length;
	final int architecture,formatVersion,oldDefVersion,oldImpVersion,currentVersion;
	final Section[] sections;final int instantiatedCount;
	final List<Library> libraries=new ArrayList<Library>();
	final List<Symbol> symbols=new ArrayList<Symbol>();
	final List<Export> exports=new ArrayList<Export>();
	final int mainSection,mainOffset,initSection,initOffset,termSection,termOffset;
	private final int loaderStart,relocSectionCount,relocInstrOffset,loaderStringsOffset;

	Pef(byte[] data,int offset,int length) throws java.io.IOException {
		this.data=data;base=offset;this.length=length<=0?data.length-offset:length;
		if(this.length<40||u4(0)!=0x4a6f7921||u4(4)!=0x70656666)throw new java.io.IOException("not a PEF container (no Joy!peff header)");
		architecture=u4(8);
		if(architecture!=0x70777063)throw new java.io.IOException("not PowerPC code (PEF architecture "+ResourceFile68k.typeName(architecture)+")");
		formatVersion=u4(12);oldDefVersion=u4(20);oldImpVersion=u4(24);currentVersion=u4(28);
		int count=u2(32);instantiatedCount=u2(34);
		sections=new Section[count];
		int loader=-1;
		for(int i=0;i<count;i++){
			int h=40+i*28;Section s=new Section();
			s.nameOffset=u4(h);s.defaultAddress=u4(h+4);s.totalSize=u4(h+8);s.unpackedSize=u4(h+12);s.packedSize=u4(h+16);
			s.containerOffset=u4(h+20);s.kind=u1(h+24);s.shareKind=u1(h+25);s.alignment=u1(h+26);
			sections[i]=s;if(s.kind==KIND_LOADER)loader=i;
		}
		if(loader<0)throw new java.io.IOException("PEF container without a loader section");
		int l=loaderStart=sections[loader].containerOffset;
		mainSection=s4(l);mainOffset=u4(l+4);initSection=s4(l+8);initOffset=u4(l+12);termSection=s4(l+16);termOffset=u4(l+20);
		int libCount=u4(l+24),symCount=u4(l+28);relocSectionCount=u4(l+32);relocInstrOffset=u4(l+36);loaderStringsOffset=u4(l+40);
		int hashOffset=u4(l+44),hashPower=u4(l+48),exportCount=u4(l+52);
		for(int i=0;i<libCount;i++){
			int e=l+56+i*24;Library lib=new Library();
			lib.name=string(u4(e));lib.oldImpVersion=u4(e+4);lib.currentVersion=u4(e+8);lib.symbolCount=u4(e+12);lib.firstSymbol=u4(e+16);lib.options=u1(e+20);
			libraries.add(lib);
		}
		int symTable=l+56+libCount*24;
		for(int i=0;i<symCount;i++){
			int v=u4(symTable+i*4);Symbol s=new Symbol();
			s.symbolClass=(v>>>24)&0x0f;s.weak=((v>>>24)&SYMBOL_WEAK)!=0;s.name=string(v&0xffffff);
			symbols.add(s);
		}
		// exports: hash slots (2^power longs), then the key table (a long per export), then 10-byte symbol entries
		int keys=l+hashOffset+(4<<hashPower),entries=keys+4*exportCount;
		for(int i=0;i<exportCount;i++){
			int nameLength=u2(keys+4*i);
			int e=entries+10*i;int classAndName=u4(e);
			String name=string(classAndName&0xffffff,nameLength);
			exports.add(new Export(name,(classAndName>>>24)&0x0f,u4(e+4),s2(e+8)));
		}
	}

	private int u1(int o){return data[base+o]&0xff;}
	private int u2(int o){return (data[base+o]&0xff)<<8|data[base+o+1]&0xff;}
	private int s2(int o){return (short)u2(o);}
	private int u4(int o){return (data[base+o]&0xff)<<24|(data[base+o+1]&0xff)<<16|(data[base+o+2]&0xff)<<8|data[base+o+3]&0xff;}
	private int s4(int o){return u4(o);}
	/** A C string in the loader string table. */
	private String string(int offset){int start=loaderStart+loaderStringsOffset+offset,end=start;while(base+end<data.length&&data[base+end]!=0)end++;return macRoman(start,end-start);}
	/** An export name: not terminated, its length comes from the key table. */
	private String string(int offset,int len){return macRoman(loaderStart+loaderStringsOffset+offset,len);}
	private String macRoman(int start,int len){
		try{return new String(data,base+start,len,"MacRoman");}catch(java.io.UnsupportedEncodingException e){return new String(data,base+start,len,java.nio.charset.StandardCharsets.ISO_8859_1);}
	}

	/** Resolves each imported symbol to an address (a TVector, a data block ...) or 0 for a missing weak import. */
	interface Imports {
		int resolve(Library library,Symbol symbol,int index) throws java.io.IOException;
		/** The sections are in place, the imports not yet bound: the fragment's own exports (all but re-exported imports) are
		 *  known now, which libraries that import from this fragment while it loads need (an import cycle). */
		default void placed(Instance sections){}
	}
	/** Allocates {@code size} bytes aligned to {@code alignment} in guest memory (cleared). */
	interface Allocator { int allocate(int size,int alignment) throws M68kCpuException; }

	/** The sections of an instantiated container. */
	static final class Instance {
		final int[] sectionAddress;final Pef pef;
		/** What each imported symbol was bound to (by symbol index). */
		final int[] imports;
		Instance(Pef pef,int[] a,int[] imports){this.pef=pef;sectionAddress=a;this.imports=imports;}
		int address(int section,int offset){return section<0||section>=sectionAddress.length?0:sectionAddress[section]+offset;}
		int main(){return pef.mainSection<0?0:address(pef.mainSection,pef.mainOffset);}
		int init(){return pef.initSection<0?0:address(pef.initSection,pef.initOffset);}
		int term(){return pef.termSection<0?0:address(pef.termSection,pef.termOffset);}
		/** The value of an export: an address in its section, an absolute value (section -2), or what a re-exported import (-3,
		 *  value = the imported symbol's index) was bound to - Netscape Communicator's NSStdLib passes StdCLib's memset on that way. */
		int exportValue(Export e){return e.section==-2?e.value:e.section==-3?(e.value>=0&&e.value<imports.length?imports[e.value]:0):e.section>=0?address(e.section,e.value):0;}
		int codeStart(){for(int i=0;i<sectionAddress.length;i++)if(pef.sections[i].kind==KIND_CODE||pef.sections[i].kind==KIND_EXEC_DATA)return sectionAddress[i];return 0;}
	}

	/** Allocates and fills the instantiated sections, resolves the imports and applies the relocations. */
	Instance instantiate(M68kMemory memory,Allocator allocator,Imports imports) throws java.io.IOException,M68kCpuException {return instantiate(memory,allocator,imports,0);}
	/**
	 * As above for a container the program holds in memory at {@code inPlace} (GetMemFragment; 0 = none): its plain code
	 * sections run where they lie, as CFM runs them. FutureBASIC's runtime branches from its code section into bytes that
	 * follow it in its 'FCOD' container, which a copy of the section alone does not have.
	 */
	Instance instantiate(M68kMemory memory,Allocator allocator,Imports imports,int inPlace) throws java.io.IOException,M68kCpuException {
		int[] address=new int[sections.length];
		for(int i=0;i<instantiatedCount&&i<sections.length;i++){
			Section s=sections[i];
			if(s.kind==KIND_LOADER||s.kind==KIND_DEBUG)continue;
			if(inPlace!=0&&s.kind==KIND_CODE&&s.packedSize==s.unpackedSize&&s.totalSize<=s.unpackedSize){address[i]=inPlace+s.containerOffset;continue;}
			int size=Math.max(s.totalSize,Math.max(s.unpackedSize,4));
			int align=Math.max(16,1<<Math.min(12,s.alignment));
			int at=allocator.allocate(size,align);address[i]=at;
			byte[] contents;
			if(s.kind==KIND_PATTERN_DATA)contents=unpackPattern(s);
			else{int n=Math.min(s.packedSize,s.unpackedSize);if(s.kind==KIND_CODE||s.kind==KIND_CONSTANT||s.kind==KIND_UNPACKED_DATA||s.kind==KIND_EXEC_DATA)n=s.unpackedSize==0?s.packedSize:Math.min(s.unpackedSize,s.packedSize);
				contents=java.util.Arrays.copyOfRange(data,base+s.containerOffset,base+s.containerOffset+Math.max(0,n));}
			memory.load(at,contents);
		}
		imports.placed(new Instance(this,address,new int[0]));
		int[] resolved=new int[symbols.size()];
		for(Library lib:libraries)for(int i=0;i<lib.symbolCount;i++){int index=lib.firstSymbol+i;resolved[index]=imports.resolve(lib,symbols.get(index),index);}
		relocate(memory,address,resolved);
		return new Instance(this,address,resolved);
	}

	/** Unpacks a pattern-initialized data section. */
	byte[] unpackPattern(Section s) throws java.io.IOException {
		byte[] out=new byte[Math.max(s.unpackedSize,0)];int o=0;
		int p=base+s.containerOffset,end=p+s.packedSize;
		int[] cursor=new int[1];
		while(p<end){
			int b=data[p++]&0xff;int opcode=b>>>5,count=b&0x1f;
			cursor[0]=p;if(count==0){count=arg(cursor);}p=cursor[0];
			switch(opcode){
				case 0:o+=count;break; // zero
				case 1:System.arraycopy(data,p,out,o,count);p+=count;o+=count;break; // block copy
				case 2:{cursor[0]=p;int repeat=arg(cursor)+1;p=cursor[0];for(int i=0;i<repeat;i++){System.arraycopy(data,p,out,o,count);o+=count;}p+=count;break;} // repeated block
				case 3:{cursor[0]=p;int custom=arg(cursor),repeat=arg(cursor);p=cursor[0];int common=p;p+=count; // interleave with a common block
					for(int i=0;i<repeat;i++){System.arraycopy(data,common,out,o,count);o+=count;System.arraycopy(data,p,out,o,custom);p+=custom;o+=custom;}
					System.arraycopy(data,common,out,o,count);o+=count;break;}
				case 4:{cursor[0]=p;int custom=arg(cursor),repeat=arg(cursor);p=cursor[0]; // interleave with zeros
					for(int i=0;i<repeat;i++){o+=count;System.arraycopy(data,p,out,o,custom);p+=custom;o+=custom;}
					o+=count;break;}
				default:throw new java.io.IOException("bad pattern-initialized data opcode "+opcode);
			}
		}
		return out;
	}
	private int arg(int[] cursor){int v=0;for(;;){int b=data[cursor[0]++]&0xff;v=(v<<7)|(b&0x7f);if((b&0x80)==0)return v;}}

	/** Runs the relocation instructions of every section that has some. */
	private void relocate(M68kMemory memory,int[] sectionAddress,int[] imports) throws java.io.IOException,M68kCpuException {
		// what a section reference adds: where the section went less its default address - the linker wrote pointers into a
		// section as if it lay there (MPW Shell 3.5's PowerPC data section has default address $6750: its TOC word is $8000)
		int[] delta=new int[sectionAddress.length];
		for(int i=0;i<delta.length;i++)delta[i]=sectionAddress[i]==0?0:sectionAddress[i]-(i<sections.length?sections[i].defaultAddress:0);
		for(int h=0;h<relocSectionCount;h++){
			int e=loaderStart+56+libraries.size()*24+symbols.size()*4+h*12;
			int section=u2(e),count=u4(e+4),first=u4(e+8);
			int instructions=loaderStart+relocInstrOffset+first;
			int where=sectionAddress[section];
			int sectC=delta.length>0?delta[0]:0,sectD=delta.length>1?delta[1]:0;
			int importIndex=0;
			int i=0;
			// repeat bookkeeping: instruction index to resume at, remaining repeats
			int repeatStart=-1,repeatEnd=-1,repeatsLeft=0;
			while(i<count){
				int at=i;
				int op=u2(instructions+2*i);i++;
				if((op&0xc000)==0){ // RelocBySectDWithSkip
					int skip=(op>>>6)&0xff,n=op&0x3f;where+=skip*4;
					for(int k=0;k<n;k++){add(memory,where,sectD);where+=4;}
				}else if((op&0xe000)==0x4000){ // run instructions
					int sub=(op>>>9)&0xf,n=(op&0x1ff)+1;
					switch(sub){
						case 0:for(int k=0;k<n;k++){add(memory,where,sectC);where+=4;}break; // RelocBySectC
						case 1:for(int k=0;k<n;k++){add(memory,where,sectD);where+=4;}break; // RelocBySectD
						case 2:for(int k=0;k<n;k++){add(memory,where,sectC);add(memory,where+4,sectD);where+=12;}break; // RelocTVector12
						case 3:for(int k=0;k<n;k++){add(memory,where,sectC);add(memory,where+4,sectD);where+=8;}break; // RelocTVector8
						case 4:for(int k=0;k<n;k++){add(memory,where,sectD);where+=8;}break; // RelocVTable8
						case 5:for(int k=0;k<n;k++){addImport(memory,where,imports,importIndex++);where+=4;}break; // RelocImportRun
						default:throw new java.io.IOException("bad relocation opcode $"+Integer.toHexString(op));
					}
				}else if((op&0xe000)==0x6000){ // small index instructions
					int sub=(op>>>9)&0xf,index=op&0x1ff;
					switch(sub){
						case 0:addImport(memory,where,imports,index);where+=4;importIndex=index+1;break; // RelocSmByImport
						case 1:sectC=delta[index];break; // RelocSmSetSectC
						case 2:sectD=delta[index];break; // RelocSmSetSectD
						case 3:add(memory,where,delta[index]);where+=4;break; // RelocSmBySection
						default:throw new java.io.IOException("bad relocation opcode $"+Integer.toHexString(op));
					}
				}else if((op&0xf000)==0x8000){ // RelocIncrPosition
					where+=(op&0x0fff)+1;
				}else if((op&0xf000)==0x9000){ // RelocSmRepeat
					int blocks=((op>>>8)&0xf)+1,times=(op&0xff)+1;
					if(repeatEnd!=at){repeatStart=at-blocks;repeatEnd=at;repeatsLeft=times;}
					if(repeatsLeft-->0){i=repeatStart;continue;}
					repeatEnd=-1;
				}else if((op&0xfc00)==0xa000){ // RelocSetPosition
					int offset=((op&0x3ff)<<16)|u2(instructions+2*i);i++;where=sectionAddress[section]+offset;
				}else if((op&0xfc00)==0xa400){ // RelocLgByImport
					int index=((op&0x3ff)<<16)|u2(instructions+2*i);i++;addImport(memory,where,imports,index);where+=4;importIndex=index+1;
				}else if((op&0xfc00)==0xb000){ // RelocLgRepeat
					int blocks=((op>>>6)&0xf)+1;int times=((op&0x3f)<<16)|u2(instructions+2*i);i++;
					if(repeatEnd!=at){repeatStart=at-blocks;repeatEnd=at;repeatsLeft=times;}
					if(repeatsLeft-->0){i=repeatStart;continue;}
					repeatEnd=-1;
				}else if((op&0xfc00)==0xb400){ // RelocLgSetOrBySection
					int sub=(op>>>6)&0xf;int index=((op&0x3f)<<16)|u2(instructions+2*i);i++;
					switch(sub){
						case 0:add(memory,where,delta[index]);where+=4;break;
						case 1:sectC=delta[index];break;
						case 2:sectD=delta[index];break;
						default:throw new java.io.IOException("bad relocation opcode $"+Integer.toHexString(op));
					}
				}else throw new java.io.IOException("bad relocation opcode $"+Integer.toHexString(op));
			}
		}
	}
	/** Where each import was stored, by guest address: crash reports name the import a bad jump came through. */
	final Map<Integer,String> importSlots=new java.util.HashMap<Integer,String>();
	private void addImport(M68kMemory memory,int where,int[] imports,int index) throws M68kCpuException {
		importSlots.put(where,symbols.get(index).name+(imports[index]==0?" (unresolved)":""));
		add(memory,where,imports[index]);
	}
	private static void add(M68kMemory memory,int where,int value) throws M68kCpuException {if(value!=0)memory.writeLong(where,memory.readLong(where)+value);}

	/** The exports by name. */
	Map<String,Export> exportMap(){Map<String,Export> m=new LinkedHashMap<String,Export>();for(Export e:exports)m.put(e.name,e);return m;}
}
