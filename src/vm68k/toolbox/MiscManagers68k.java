import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The smaller Toolbox managers of Mac OS 8/9 at trap level, and the entry point of the ones with classes of their own
 * ({@link Collections68k} $ABF6, {@link ColorSync68k} $ABEE):
 * <ul>
 * <li>AVL trees (AVLTree.h, _AVLTreeDispatch $AA80, MOVE.W #words<<8|selector,D0): a balanced tree of item copies ordered by
 *     the program's compare procedure;</li>
 * <li>DebugLib (Debugging.h, $AA7E, MOVEQ #selector,D0): TaskLevel, DebugAssert (to stderr or the installed output handler) and
 *     the debug component/option registry;</li>
 * <li>the Translation Manager (Translation.h / TranslationExtensions.h, _TranslationDispatch $ABFC, MOVEQ): Macintosh Easy Open
 *     with no translators installed - a program's own types open natively, nothing translates, Finder kind strings;</li>
 * <li>Power Manager bits (Power.h): _IdleUpdate $A285, _IdleState $A485 (EnableIdle/DisableIdle/GetCPUSpeed), _SerialPower
 *     $A685, SleepQInstall $A28A / SleepQRemove $A48A - the machine never sleeps;</li>
 * <li>the Data Access Manager (_Pack13 $A82F, MOVE.W #words<<8|selector,D0) with no data source: InitDBPack succeeds, sessions
 *     cannot be started (rcDBBadDDEV), the rest report rcDBError;</li>
 * <li>absent services answering honestly: Speech Recognition ($AA56: kSRNotAvailable, outputs cleared), cursor device
 *     components ($ABE0) and SCSI Manager 4.3 ($A089 SCSIAction...).</li>
 * </ul>
 * Pascal arguments are on the stack below the result slot; each routine pops what its prototype pushes (the argument sizes
 * in some inline words of the headers disagree with the prototypes, so the prototypes decide).
 */
final class MiscManagers68k extends TrapManager68k {
	final Collections68k collections;
	final ColorSync68k colorSync;
	MiscManagers68k(Toolbox68k tb){super(tb);collections=new Collections68k(tb);colorSync=new ColorSync68k(tb);}

	/** Toolbox traps served here ({@code trap} = trap word & $FFF): true when handled. */
	boolean dispatch(M68kCpu cpu,int trap) throws M68kException {
		switch(trap){
			case 0xbf6:return collections.dispatch(cpu);
			case 0xbee:return colorSync.dispatch(cpu);
			case 0xbfc:return translation(cpu,cpu.d[0]&0xffff);
			case 0xa80:return avl(cpu,cpu.d[0]&0xff);
			case 0xa7e:return debugging(cpu,cpu.d[0]&0xffff);
			case 0xa56:return speechRecognition(cpu,cpu.d[0]&0xffff);
			case 0xbe0:return cursorComponents(cpu,cpu.d[0]&0xffff);
			case 0x82f:return dataAccess(cpu,cpu.d[0]&0xffff);
			default:return false;
		}
	}
	/** OS traps served here: the Power Manager's idle, CPU speed, serial power and sleep queue calls, SCSI Manager 4.3. */
	boolean dispatchOs(M68kCpu cpu,int opcode) throws M68kException {
		switch(opcode&0xfeff){ // the trap number and flag bits 9-10 (bit 8 only says whether A0 is kept)
			case 0xa285:cpu.d[0]=memory.readLong(0x16a);return true;                // IdleUpdate: Ticks
			case 0xa485:{int selector=cpu.d[0];                                     // IdleState
				if(selector==-1){cpu.d[0]=cpuSpeed();return true;}                  // GetCPUSpeed (MHz)
				if(selector==0)idleEnabled=true;else if(selector==1)idleEnabled=false; // EnableIdle / DisableIdle
				cpu.d[0]=NO_ERR;return true;}
			case 0xa685:cpu.d[0]=NO_ERR;return true;                                // SerialPower: AOn/BOn/AOff/BOff... - the ports are always powered
			case 0xa28a:if(cpu.a[0]!=0&&!sleepQueue.contains(cpu.a[0]))sleepQueue.add(cpu.a[0]);cpu.d[0]=NO_ERR;return true; // SleepQInstall: kept, never called (no sleep)
			case 0xa48a:sleepQueue.remove(Integer.valueOf(cpu.a[0]));cpu.d[0]=NO_ERR;return true; // SleepQRemove
			case 0xa089:{ // SCSIAction/SCSIRegisterBus/...: no SCSI Manager 4.3 (the original SCSI Manager's bus is empty)
				int pb=cpu.a[0];if(pb!=0&&(cpu.d[0]&0xffff)==1)memory.writeWord(pb+10,SCSI_FUNCTION_NOT_AVAILABLE); // scsiResult
				cpu.d[0]=SCSI_FUNCTION_NOT_AVAILABLE;return true;}
			default:return false;
		}
	}
	/** Toolbox traps that exist on this system (for GetTrapAddress/TrapAvailable); speech recognition and cursor device
	 *  components are absent, as on a Mac without them. */
	static boolean implemented(int trap){return trap==0xbf6||trap==0xbee||trap==0xbfc||trap==0xa80||trap==0xa7e||trap==0x82f;}
	static String name(int trap){
		switch(trap){case 0xbf6:return "CollectionMgr";case 0xbee:return "CMTrap";case 0xbfc:return "TranslationDispatch";case 0xa80:return "AVLTreeDispatch";
			case 0xa7e:return "DebugUtil";case 0xa56:return "SpeechRecognitionDispatch";case 0xbe0:return "CursorComponentDispatch";case 0x82f:return "Pack13";default:return null;}
	}

	static final int NO_ERR=0,PARAM_ERR=-50,MEM_FULL_ERR=-108,FNF_ERR=-43,USER_CANCELED_ERR=-128,NO_TYPE_ERR=-102,SCSI_FUNCTION_NOT_AVAILABLE=-7871;

	// ------------------------------------------------------------------ Power Manager

	private boolean idleEnabled=true;
	private final List<Integer> sleepQueue=new ArrayList<Integer>();
	/** GetCPUSpeed: the clock the emulated machine claims - a PowerPC program's G3 (1 instruction a clock, the speed
	 *  setting's rate; 233 MHz when unthrottled), a 68K program's 68040 Quadra (33 MHz at the standard 32 MIPS pace). */
	private int cpuSpeed(){
		boolean ppc=tb.app!=null&&tb.app.powerPC;
		double mips=tb.app!=null?tb.app.mips():Toolbox68k.mips;
		if(ppc)return mips<=0?PpcRuntime.REAL_MIPS:(int)Math.max(1,Math.round(mips));
		return mips<=0?33:(int)Math.max(1,Math.round(mips*33/Toolbox68k.DEFAULT_MIPS));
	}

	// ------------------------------------------------------------------ AVL trees

	static final int ERR_ITEM_ALREADY_IN_TREE=-960,ERR_NOT_VALID_TREE=-961,ERR_ITEM_NOT_FOUND_IN_TREE=-962,ERR_CAN_NOT_INSERT_WHILE_WALK=-963;
	static final int AVL_PRE_ORDER=0,AVL_IN_ORDER=1,AVL_POST_ORDER=2,AVL_IS_TREE=0,AVL_IS_LEFT_BRANCH=1,AVL_IS_RIGHT_BRANCH=2,AVL_IS_LEAF=3;
	private static final int AVL_SIGNATURE=0x41564c54; // 'AVLT' in the opaque AVLTreeStruct
	/** One item: a guest copy of the program's data. */
	private static final class AvlNode{int data,size,height=1;AvlNode left,right;}
	private static final class AvlTree{int ptr,flags,compare,sizeProc,dispose,refCon,count;AvlNode root;boolean walking;}
	private final Map<Integer,AvlTree> trees=new HashMap<Integer,AvlTree>();

	private boolean avl(M68kCpu cpu,int selector) throws M68kException {
		switch(selector){
			case 0x01:{int[] a=pop(cpu,4,4,4,4,4,4); // AVLInit(flags, compare, size, dispose, refCon, *tree)
				if(a[5]==0||a[1]==0||a[2]==0){result(cpu,2,PARAM_ERR);return true;}
				AvlTree t=new AvlTree();t.flags=a[0];t.compare=a[1];t.sizeProc=a[2];t.dispose=a[3];t.refCon=a[4];
				t.ptr=memory.allocate(36,4);memory.fill(t.ptr,36,0);memory.writeLong(t.ptr,AVL_SIGNATURE);trees.put(t.ptr,t);
				memory.writeLong(a[5],t.ptr);result(cpu,2,NO_ERR);return true;}
			case 0x02:{int[] a=pop(cpu,4,2); // AVLDispose(*tree, order): the dispose proc sees every item, post-order
				int p=a[0]==0?0:memory.readLong(a[0]);AvlTree t=trees.get(p);
				if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				disposeNodes(cpu,t,t.root,(a[1]&0xffff)==1);trees.remove(p);memory.free(p);memory.writeLong(a[0],0);result(cpu,2,NO_ERR);return true;}
			case 0x03:{int[] a=pop(cpu,4,4,2,4); // AVLWalk(tree, walkProc, order, refCon)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				if(a[1]==0){result(cpu,2,PARAM_ERR);return true;}
				t.walking=true;int err;try{err=walk(cpu,t,t.root,a[1],(a[2]&0xffff)==1,a[3],0);}finally{t.walking=false;}
				result(cpu,2,err);return true;}
			case 0x04:{int[] a=pop(cpu,4,4); // AVLCount(tree, *count)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				putLong(a[1],t.count);result(cpu,2,NO_ERR);return true;}
			case 0x05:{int[] a=pop(cpu,4,4,4,4); // AVLGetIndItem(tree, index (1-based), dataPtr, *itemSize)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				AvlNode n=a[1]>=1&&a[1]<=t.count?nth(t.root,new int[]{a[1]}):null;
				if(n==null){result(cpu,2,ERR_ITEM_NOT_FOUND_IN_TREE);return true;}
				give(n,a[2],a[3]);result(cpu,2,NO_ERR);return true;}
			case 0x06:{int[] a=pop(cpu,4,4); // AVLInsert(tree, data)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				if(t.walking){result(cpu,2,ERR_CAN_NOT_INSERT_WHILE_WALK);return true;}
				int size=call(cpu,t.sizeProc,4,t.ptr,4,a[1],4);
				if(size<0||size>(1<<24)){result(cpu,2,PARAM_ERR);return true;}
				int[] err={NO_ERR};t.root=insert(cpu,t,t.root,a[1],size,err);if(err[0]==NO_ERR)t.count++;
				result(cpu,2,err[0]);return true;}
			case 0x07:{int[] a=pop(cpu,4,4,4,4); // AVLRemove(tree, key, dataPtr, *itemSize)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				if(t.walking){result(cpu,2,ERR_CAN_NOT_INSERT_WHILE_WALK);return true;}
				AvlNode n=find(cpu,t,a[1]);if(n==null){result(cpu,2,ERR_ITEM_NOT_FOUND_IN_TREE);return true;}
				give(n,a[2],a[3]);
				if(t.dispose!=0)call(cpu,t.dispose,0,t.ptr,4,n.data,4);
				t.root=remove(cpu,t,t.root,n);t.count--;memory.free(n.data);result(cpu,2,NO_ERR);return true;}
			case 0x08:{int[] a=pop(cpu,4,4,4,4); // AVLFind(tree, key, dataPtr, *itemSize)
				AvlTree t=trees.get(a[0]);if(t==null){result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				AvlNode n=find(cpu,t,a[1]);if(n==null){result(cpu,2,ERR_ITEM_NOT_FOUND_IN_TREE);return true;}
				give(n,a[2],a[3]);result(cpu,2,NO_ERR);return true;}
			case 0x09:{int[] a=pop(cpu,4,4); // AVLGetRefcon(tree, *refCon)
				AvlTree t=trees.get(a[0]);if(t==null){putLong(a[1],0);result(cpu,2,ERR_NOT_VALID_TREE);return true;}
				putLong(a[1],t.refCon);result(cpu,2,NO_ERR);return true;}
			default:return false;
		}
	}
	private void give(AvlNode n,int dataPtr,int sizePtr) throws M68kCpuException {if(dataPtr!=0&&n.size>0)memory.copy(n.data,dataPtr,n.size);putLong(sizePtr,n.size);}
	private static int height(AvlNode n){return n==null?0:n.height;}
	private static void fix(AvlNode n){n.height=1+Math.max(height(n.left),height(n.right));}
	private static int nodeType(AvlNode n){return n.left!=null&&n.right!=null?AVL_IS_TREE:n.left!=null?AVL_IS_LEFT_BRANCH:n.right!=null?AVL_IS_RIGHT_BRANCH:AVL_IS_LEAF;}
	private int compare(M68kCpu cpu,AvlTree t,int item,AvlNode n) throws M68kException {return call(cpu,t.compare,4,t.ptr,4,item,4,n.data,4,nodeType(n),2);}
	private static AvlNode rotateRight(AvlNode n){AvlNode l=n.left;n.left=l.right;l.right=n;fix(n);fix(l);return l;}
	private static AvlNode rotateLeft(AvlNode n){AvlNode r=n.right;n.right=r.left;r.left=n;fix(n);fix(r);return r;}
	private static AvlNode balance(AvlNode n){
		fix(n);int b=height(n.left)-height(n.right);
		if(b>1){if(height(n.left.left)<height(n.left.right))n.left=rotateLeft(n.left);return rotateRight(n);}
		if(b<-1){if(height(n.right.right)<height(n.right.left))n.right=rotateRight(n.right);return rotateLeft(n);}
		return n;
	}
	private AvlNode insert(M68kCpu cpu,AvlTree t,AvlNode n,int item,int size,int[] err) throws M68kException {
		if(n==null){AvlNode m=new AvlNode();m.size=size;m.data=memory.allocate(Math.max(1,size),4);if(size>0)memory.copy(item,m.data,size);return m;}
		int c=compare(cpu,t,item,n);
		if(c==0){err[0]=ERR_ITEM_ALREADY_IN_TREE;return n;}
		if(c<0)n.left=insert(cpu,t,n.left,item,size,err);else n.right=insert(cpu,t,n.right,item,size,err);
		return balance(n);
	}
	private AvlNode find(M68kCpu cpu,AvlTree t,int key) throws M68kException {
		AvlNode n=t.root;
		while(n!=null){int c=compare(cpu,t,key,n);if(c==0)return n;n=c<0?n.left:n.right;}
		return null;
	}
	/** Removes {@code target} (found before) from the subtree, keeping it balanced; the order is the tree's own, so no compare calls. */
	private AvlNode remove(M68kCpu cpu,AvlTree t,AvlNode n,AvlNode target){
		if(n==null)return null;
		if(n==target){
			if(n.left==null)return n.right;if(n.right==null)return n.left;
			AvlNode m=n.right;while(m.left!=null)m=m.left; // the in-order successor takes n's place
			m.right=removeMin(n.right);m.left=n.left;return balance(m);
		}
		if(contains(n.left,target))n.left=remove(cpu,t,n.left,target);else n.right=remove(cpu,t,n.right,target);
		return balance(n);
	}
	private static AvlNode removeMin(AvlNode n){if(n.left==null)return n.right;n.left=removeMin(n.left);return balance(n);}
	private static boolean contains(AvlNode n,AvlNode target){if(n==null)return false;if(n==target)return true;return contains(n.left,target)||contains(n.right,target);}
	private static AvlNode nth(AvlNode n,int[] index){
		if(n==null)return null;
		AvlNode r=nth(n.left,index);if(r!=null)return r;
		if(--index[0]==0)return n;
		return nth(n.right,index);
	}
	/** AVLWalk: pre-order, in-order and post-order visits of every node; a non-zero walkProc result stops the walk. */
	private int walk(M68kCpu cpu,AvlTree t,AvlNode n,int proc,boolean rightToLeft,int refCon,int level) throws M68kException {
		if(n==null)return NO_ERR;
		int type=nodeType(n),bal=height(n.right)-height(n.left);
		int err=(short)call(cpu,proc,2,t.ptr,4,n.data,4,AVL_PRE_ORDER,2,type,2,level,4,bal,4,refCon,4);if(err!=0)return err;
		err=walk(cpu,t,rightToLeft?n.right:n.left,proc,rightToLeft,refCon,level+1);if(err!=0)return err;
		err=(short)call(cpu,proc,2,t.ptr,4,n.data,4,AVL_IN_ORDER,2,type,2,level,4,bal,4,refCon,4);if(err!=0)return err;
		err=walk(cpu,t,rightToLeft?n.left:n.right,proc,rightToLeft,refCon,level+1);if(err!=0)return err;
		return (short)call(cpu,proc,2,t.ptr,4,n.data,4,AVL_POST_ORDER,2,type,2,level,4,bal,4,refCon,4);
	}
	private void disposeNodes(M68kCpu cpu,AvlTree t,AvlNode n,boolean rightToLeft) throws M68kException {
		if(n==null)return;
		disposeNodes(cpu,t,rightToLeft?n.right:n.left,rightToLeft);disposeNodes(cpu,t,rightToLeft?n.left:n.right,rightToLeft);
		if(t.dispose!=0)call(cpu,t.dispose,0,t.ptr,4,n.data,4);
		memory.free(n.data);
	}

	// ------------------------------------------------------------------ DebugLib

	static final int DEBUGGING_DUPLICATE_SIGNATURE_ERR=-13881,DEBUGGING_DUPLICATE_OPTION_ERR=-13882,DEBUGGING_INVALID_SIGNATURE_ERR=-13883,
		DEBUGGING_INVALID_OPTION_ERR=-13884,DEBUGGING_INVALID_NAME_ERR=-13885,DEBUGGING_NO_MATCH_ERR=-13887;
	private static final int K_GET_DEBUG_OPTION=1,K_SET_DEBUG_OPTION=2;
	private static final class DebugOption{int selector;byte[] name;boolean setting;DebugOption(int s,byte[] n){selector=s;name=n;}}
	private static final class DebugComponent{int signature,callback;byte[] name;final List<DebugOption> options=new ArrayList<DebugOption>();boolean enabled;}
	private final List<DebugComponent> debugComponents=new ArrayList<DebugComponent>();
	private int assertOutputHandler;
	private DebugComponent debugComponent(int signature){for(DebugComponent c:debugComponents)if(c.signature==signature)return c;return null;}

	private boolean debugging(M68kCpu cpu,int selector) throws M68kException {
		switch(selector){
			case 0:{int[] a=pop(cpu,4,4,4,4,4,4,4,4);debugAssert(cpu,a);return true;} // DebugAssert(signature, options, assertion, exception, error, file, line, value)
			case 1:result(cpu,4,0);return true;                                    // TaskLevel: application level (no interrupt, VBL or deferred task)
			case 2:{int[] a=pop(cpu,4,4,4); // NewDebugComponent(signature, name, callback)
				if(a[1]==0){result(cpu,4,DEBUGGING_INVALID_NAME_ERR);return true;}
				if(debugComponent(a[0])!=null){result(cpu,4,DEBUGGING_DUPLICATE_SIGNATURE_ERR);return true;}
				DebugComponent c=new DebugComponent();c.signature=a[0];c.name=pascalBytes(a[1]);c.callback=a[2];debugComponents.add(c);
				result(cpu,4,NO_ERR);return true;}
			case 3:{int[] a=pop(cpu,4,4,4); // NewDebugOption(signature, optionSelectorNum, name)
				DebugComponent c=debugComponent(a[0]);
				if(c==null){result(cpu,4,DEBUGGING_INVALID_SIGNATURE_ERR);return true;}
				if(a[2]==0){result(cpu,4,DEBUGGING_INVALID_NAME_ERR);return true;}
				for(DebugOption o:c.options)if(o.selector==a[1]){result(cpu,4,DEBUGGING_DUPLICATE_OPTION_ERR);return true;}
				c.options.add(new DebugOption(a[1],pascalBytes(a[2])));result(cpu,4,NO_ERR);return true;}
			case 4:{int[] a=pop(cpu,4);DebugComponent c=debugComponent(a[0]); // DisposeDebugComponent(signature)
				if(c==null){result(cpu,4,DEBUGGING_INVALID_SIGNATURE_ERR);return true;}
				debugComponents.remove(c);result(cpu,4,NO_ERR);return true;}
			case 5:{int[] a=pop(cpu,4,4,4); // GetDebugComponentInfo(index (1-based), *signature, name)
				if(a[0]<1||a[0]>debugComponents.size()){result(cpu,4,DEBUGGING_NO_MATCH_ERR);return true;}
				DebugComponent c=debugComponents.get(a[0]-1);putLong(a[1],c.signature);putPascal(a[2],c.name,255);result(cpu,4,NO_ERR);return true;}
			case 6:{int[] a=pop(cpu,4,4,4,4,4); // GetDebugOptionInfo(index (0 = kComponentDebugOption), signature, *selector, name, *setting)
				DebugComponent c=debugComponent(a[1]);
				if(c==null){result(cpu,4,DEBUGGING_INVALID_SIGNATURE_ERR);return true;}
				if(a[0]<0||a[0]>c.options.size()){result(cpu,4,DEBUGGING_NO_MATCH_ERR);return true;}
				int selectorNum=a[0]==0?0:c.options.get(a[0]-1).selector;byte[] name=a[0]==0?c.name:c.options.get(a[0]-1).name;
				boolean setting=optionSetting(cpu,c,a[0],K_GET_DEBUG_OPTION,false);
				putLong(a[2],selectorNum);putPascal(a[3],name,255);putBoolean(a[4],setting);result(cpu,4,NO_ERR);return true;}
			case 7:{int[] a=pop(cpu,4,4,2); // SetDebugOptionValue(signature, optionSelectorNum, setting)
				DebugComponent c=debugComponent(a[0]);
				if(c==null){result(cpu,4,DEBUGGING_INVALID_SIGNATURE_ERR);return true;}
				int index=-1;if(a[1]==0)index=0;else for(int i=0;i<c.options.size();i++)if(c.options.get(i).selector==a[1])index=i+1;
				if(index<0){result(cpu,4,DEBUGGING_INVALID_OPTION_ERR);return true;}
				optionSetting(cpu,c,index,K_SET_DEBUG_OPTION,bool(a[2]));result(cpu,4,NO_ERR);return true;}
			case 8:{int[] a=pop(cpu,4);assertOutputHandler=a[0];return true;}    // InstallDebugAssertOutputHandler(handler)
			default:return false;
		}
	}
	/** Gets or sets an option through the component's callback (the component keeps the setting), else the value kept here. */
	private boolean optionSetting(M68kCpu cpu,DebugComponent c,int index,int command,boolean value) throws M68kException {
		int selectorNum=index==0?0:c.options.get(index-1).selector;
		if(c.callback!=0){
			int b=memory.allocate(2,2);
			try{memory.writeByte(b,value?1:0);call(cpu,c.callback,0,selectorNum,4,command,4,b,4);value=memory.readByte(b)!=0;}finally{memory.free(b);}
		}
		if(command==K_SET_DEBUG_OPTION||c.callback!=0){if(index==0)c.enabled=value;else c.options.get(index-1).setting=value;}
		return c.callback!=0?value:index==0?c.enabled:c.options.get(index-1).setting;
	}
	/** DebugAssert: the message DebugLib builds, to the installed output handler, else to stderr (no debugger here). */
	private void debugAssert(M68kCpu cpu,int[] a) throws M68kException {
		DebugComponent c=debugComponent(a[0]);
		StringBuilder m=new StringBuilder();
		if(c!=null)m.append(roman(c.name)).append(": ");
		m.append("Assertion failed: ").append(a[2]==0?"":tb.encoding.decode(cBytes(a[2])));
		if(a[3]!=0)m.append(", ").append(tb.encoding.decode(cBytes(a[3])));
		if(a[4]!=0)m.append(", ").append(tb.encoding.decode(cBytes(a[4])));
		m.append(" file: ").append(a[5]==0?"":tb.encoding.decode(cBytes(a[5]))).append(", line: ").append(a[6]).append(", value: ").append(a[7]);
		if(assertOutputHandler!=0){
			int msg=block(new byte[256]);
			try{putPascal(msg,tb.encoding.encodeReplacing(m.toString()),255);call(cpu,assertOutputHandler,0,a[0],4,a[1],4,a[2],4,a[3],4,a[4],4,a[5],4,a[6],4,a[7],4,msg,4);}
			finally{memory.free(msg);}
			return;
		}
		System.err.println("[68K "+tb.name+"] DebugAssert: "+m);
	}
	private byte[] cBytes(int p) throws M68kCpuException {int n=0;while(n<4096&&memory.readByte(p+n)!=0)n++;return memory.readBytes(p,n);}

	// ------------------------------------------------------------------ Translation Manager (Macintosh Easy Open)

	static final int NO_TRANSLATION_PATH_ERR=-3030,BAD_TRANSLATION_SPEC_ERR=-3031;
	static final int DOM_CANNOT=0,DOM_NATIVE=1,DOM_WILDCARD=3;
	private static final int WILDCARD='*'<<24|'*'<<16|'*'<<8|'*';

	private boolean translation(M68kCpu cpu,int selector) throws M68kException {
		switch(selector){
			case 0x01:{int[] a=pop(cpu,4,2,4);putBoolean(a[2],false);result(cpu,2,NO_ERR);return true;} // UpdateTranslationProgress(refNum, percent, *canceled)
			case 0x02:{pop(cpu,4,4);result(cpu,2,NO_ERR);return true;}          // SetTranslationAdvertisement(refNum, advertisement)
			case 0x09:{int[] a=pop(cpu,4,2,4,4); // ExtendFileTypeList(original, count, extended, *extendedCount): no translators, the list itself
				int max=a[3]==0?0:(short)memory.readWord(a[3]),n=Math.max(0,Math.min(a[1],max));
				if(a[2]!=0&&a[0]!=0&&n>0)memory.copy(a[0],a[2],4*n);putWord(a[3],n);result(cpu,2,NO_ERR);return true;}
			case 0x0c:{pop(cpu,4,4,4);result(cpu,2,BAD_TRANSLATION_SPEC_ERR);return true;} // TranslateFile(source, destination, howToTranslate)
			case 0x0e:{pop(cpu,4,4,4,4,2);result(cpu,2,NO_TRANSLATION_PATH_ERR);return true;} // TranslateScrap(getter, refCon, format, destination, dialogID)
			case 0x16:{int[] a=pop(cpu,2,4,4,4);result(cpu,2,documentKind(a[1],a[2],a[3]));return true;} // GetDocumentKindString(vRefNum, type, creator, kind)
			// Private selectors Apple's Installer 4 calls when it finds Easy Open: $17 (resource file refNum):OSErr after installing
			// an application, $2B (DT param block):Boolean before PBDTAddAPPL - true lets it register the application as without Easy Open
			case 0x17:{pop(cpu,2);result(cpu,2,NO_ERR);return true;}
			case 0x2b:{pop(cpu,4);result(cpu,1,1);return true;}
			case 0x1c:{int[] a=pop(cpu,2,4,4); // GetFileTypesThatAppCanNativelyOpen(vRefNumHint, signature, nativeTypes[64])
				List<Integer> types=nativeTypes(a[1]);
				if(types==null){putLong(a[2],0);result(cpu,2,FNF_ERR);return true;}
				if(a[2]!=0){int n=Math.min(63,types.size());for(int i=0;i<n;i++)memory.writeLong(a[2]+4*i,types.get(i));memory.writeLong(a[2]+4*n,0);}
				result(cpu,2,NO_ERR);return true;}
			case 0x1e:{int[] a=pop(cpu,4,2,4,4,2,4,4);result(cpu,2,canDocBeOpened(a));return true;} // CanDocBeOpened
			case 0x36:{int[] a=pop(cpu,4,4);putPascal(a[1],new byte[0],31);result(cpu,2,BAD_TRANSLATION_SPEC_ERR);return true;} // GetTranslationExtensionName(spec, name)
			case 0x37:{int[] a=pop(cpu,4,4,4,4,4);putWord(a[3],DOM_CANNOT);result(cpu,2,USER_CANCELED_ERR);return true;} // GetPathFromTranslationDialog: no translator to choose
			case 0x38:{pop(cpu,4,4,2,4);result(cpu,2,0);return true;}           // GetFileTranslationPaths: 0 paths
			default:return false;
		}
	}
	/** CanDocBeOpened(document, vRefNumHint, signature, nativeTypes, onlyNative, *howToOpen, *howToTranslate): the program's own
	 *  types open natively ('****' as a wildcard); nothing can be translated. */
	private int canDocBeOpened(int[] a) throws M68kException {
		putWord(a[5],DOM_CANNOT);
		File doc;try{doc=tb.files.fileForSpec(a[0]);}catch(java.io.IOException e){return -36;}
		if(doc==null||!doc.exists())return FNF_ERR;
		int type=doc.isDirectory()?fourcc("fold"):FinderInfo68k.type(doc);
		List<Integer> types=new ArrayList<Integer>();
		if(a[3]!=0){for(int i=0;i<64;i++){int t=memory.readLong(a[3]+4*i);if(t==0)break;types.add(t);}}
		else{List<Integer> own=nativeTypes(a[2]);if(own==null)return FNF_ERR;types.addAll(own);}
		if(types.contains(type)){putWord(a[5],DOM_NATIVE);return NO_ERR;}
		if(types.contains(WILDCARD)){putWord(a[5],DOM_WILDCARD);return NO_ERR;}
		return NO_TYPE_ERR;
	}
	/** The resource file of the application with {@code signature}: the running program's own, else one found on the volumes; null when none. */
	private ResourceFile68k application(int signature){
		try{
			File file=signature!=0&&signature==programSignature()?tb.app.file:tb.files.fileManager.findApplication(signature,1);
			return file==null?null:ResourceFile68k.open(file);
		}catch(Exception e){return null;}
	}
	/** The types an application opens itself: its 'open' resource (Easy Open), else the document types of its FREFs; null when the application is not found. */
	private List<Integer> nativeTypes(int signature){
		ResourceFile68k r=application(signature);if(r==null)return null;
		List<Integer> types=new ArrayList<Integer>();
		for(ResourceFile68k.Resource open:r.ofType(fourcc("open"))){
			byte[] b=open.data();if(b==null||b.length<8||be32(b,0)!=signature)continue;
			int n=be16(b,6);for(int i=0;i<n&&8+4*i+4<=b.length;i++){int t=be32(b,8+4*i);if(!types.contains(t))types.add(t);}
		}
		if(types.isEmpty())for(ResourceFile68k.Resource fref:r.ofType(fourcc("FREF"))){
			byte[] b=fref.data();if(b==null||b.length<4)continue;int t=be32(b,0);
			if(t!=fourcc("APPL")&&t!=fourcc("appe")&&t!=fourcc("APPC")&&t!=fourcc("APPD")&&!types.contains(t))types.add(t);
		}
		return types;
	}
	/** GetDocumentKindString: the creator's 'kind' resource, "<application> document", "document"; applications are "application program". */
	private int documentKind(int type,int creator,int out) throws M68kException {
		String kind=null;
		if(type==fourcc("APPL"))kind="application program";
		else if(type==fourcc("APPD"))kind="desk accessory";
		else{
			ResourceFile68k r=application(creator);
			if(r!=null){
				for(ResourceFile68k.Resource res:r.ofType(fourcc("kind"))){
					byte[] b=res.data();if(b==null||b.length<10||be32(b,0)!=creator)continue;
					int n=be16(b,8),at=10;
					for(int i=0;i<n&&at+5<=b.length;i++){int t=be32(b,at);int len=b[at+4]&255;if(at+5+len>b.length)break;
						if(t==type){putPascal(out,java.util.Arrays.copyOfRange(b,at+5,at+5+len),63);return NO_ERR;}
						at+=5+len;if((at&1)!=0)at++;}
				}
				try{File app=applicationFile(creator);if(app!=null)kind=tb.files.fileManager.macNameOf(app)+" document";}catch(java.io.IOException ignored){}
			}
			if(kind==null)kind="document";
		}
		putPascal(out,tb.encoding.encodeReplacing(kind),63);return NO_ERR;
	}
	private File applicationFile(int creator){return creator!=0&&creator==programSignature()?tb.app.file:tb.files.fileManager.findApplication(creator,1);}

	// ------------------------------------------------------------------ Speech Recognition (not installed)

	static final int K_SR_NOT_AVAILABLE=-5100;
	/** Argument sizes by the full D0 word (MOVE.W #$wwss,D0; the headers' word counts are not always right) and the index of
	 *  the one output that is cleared, -1 for none. */
	private static final Map<Integer,int[][]> SR_CALLS=new HashMap<Integer,int[][]>();
	private static void sr(int d0,int out,int... sizes){SR_CALLS.put(d0,new int[][]{sizes,{out}});}
	static{
		sr(0x0400,0,4,4);sr(0x0201,-1,4);sr(0x0802,-1,4,4,4,4);sr(0x0803,-1,4,4,4,4);sr(0x0204,-1,4);sr(0x0405,1,4,4);sr(0x0606,1,4,4,4);
		sr(0x0607,-1,4,4,4);sr(0x0408,-1,4,4);sr(0x060a,1,4,4,4);sr(0x020c,-1,4);sr(0x020d,-1,4);sr(0x040e,-1,4,4);sr(0x040f,1,4,4);
		sr(0x0210,-1,4);sr(0x0211,-1,4);sr(0x0812,1,4,4,4,4);sr(0x0413,1,4,4);sr(0x0814,1,4,4,4,4);sr(0x0815,1,4,4,4,4);sr(0x0416,-1,4,4);
		sr(0x0417,1,4,4,4);sr(0x0218,-1,4);sr(0x0619,-1,4,4,4);sr(0x041a,-1,4,4);sr(0x081b,-1,4,4,4,4);sr(0x041c,-1,4,4);sr(0x031d,-1,4,2);
		sr(0x031e,-1,4,2);sr(0x061f,-1,4,4,4);sr(0x0620,-1,4,4,4);sr(0x0621,-1,4,4,4);sr(0x0622,-1,4,4,4);sr(0x0223,-1,4);sr(0x0224,-1,4);
		sr(0x0425,1,4,4);sr(0x0427,1,4,4,2);sr(0x0028,-1);sr(0x0328,-1,4,2);
	}
	/** Data Access Manager (DatabaseAccess.h): every routine is an OSErr function whose argument words are the selector's high
	 *  byte; there is no 'ddev' to connect to. MacApp-era programs (3DMF Viewer) call InitDBPack at start-up. */
	private boolean dataAccess(M68kCpu cpu,int d0) throws M68kException {
		int selector=d0&0xff;cpu.a[7]+=2*(d0>>>8&0xff);
		int result;
		switch(selector){
			case 0x00:case 0xff:result=NO_ERR;break;           // InitDBPack(version), DBIdle
			case 0x02:case 0x0f:case 0x11:result=-808;break;   // DBInit, DBGetNewQuery, DBStartQuery: rcDBBadDDEV
			default:result=-802;                               // rcDBError
		}
		memory.writeWord(cpu.a[7],result);return true;
	}

	private boolean speechRecognition(M68kCpu cpu,int d0) throws M68kException {
		int[][] call=SR_CALLS.get(d0);if(call==null)return false;
		int[] a=pop(cpu,call[0]);
		if(call[1][0]>=0)putLong(a[call[1][0]],0);
		if(d0==0x0224)result(cpu,1,0);else result(cpu,2,K_SR_NOT_AVAILABLE); // SRSpeechBusy: false
		return true;
	}

	// ------------------------------------------------------------------ cursor device components (none)

	static final int INVALID_COMPONENT_ID=-3000;
	private boolean cursorComponents(M68kCpu cpu,int selector) throws M68kException {
		switch(selector){
			case 0x0b:{int[] a=pop(cpu,4,4);putLong(a[1],0);result(cpu,2,INVALID_COMPONENT_ID);return true;} // OpenCursorComponent(c, *ci)
			case 0x0c:case 0x0d:case 0x0e:{pop(cpu,4);result(cpu,2,PARAM_ERR);return true;} // Close/SetCursorComponent, CursorComponentChanged(ci): never opened
			case 0x0f:{pop(cpu,4,4);result(cpu,2,PARAM_ERR);return true;}      // CursorComponentSetData(ci, data)
			default:return false;
		}
	}
}
