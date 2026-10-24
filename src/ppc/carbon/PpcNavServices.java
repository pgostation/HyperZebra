import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Navigation Services (NavigationLib, and the same calls in CarbonLib) for PowerPC programs: the classic modal calls
 * (NavGetFile, NavPutFile, NavChooseFolder ..., NavAskSaveChanges ...) and CarbonLib 1.1's dialog objects
 * (NavCreateXXXDialog, NavDialogRun, NavDialogGetReply ...). The dialogs are the host's own panels - the ones
 * {@link StandardFile68k} shows for plain SFGetFile/StandardPutFile - and alerts; the reply is a real NavReplyRecord whose
 * selection is an Apple Event list of 'fss ' FSSpecs. The program's event procedure sees the messages it expects around the
 * modal panel: kNavCBCustomize, kNavCBStart, kNavCBAccept or kNavCBCancel, kNavCBUserAction (Carbon dialogs) and
 * kNavCBTerminate, with a hidden dialog window standing in for Navigation's own (custom DITLs are appended to it).
 * Window-modal (sheet) and modeless Carbon dialogs run modally too, as they did on Mac OS 9.
 * <p>
 * {@code -Dhzppc.navreply=<answer>} answers the dialogs without showing anything (tests, automation): a host path (files
 * separated by the path separator for multiple selection; for a Save dialog an existing folder means the default name in it),
 * {@code cancel}, or for the alerts {@code save}, {@code dontsave}, {@code discard}, {@code review}; answers separated by
 * {@code ;} serve successive dialogs (the last one repeats); a Save answer may add {@code |format=<item name or number>} and
 * {@code |item<k>=0|1} (the k-th check box of the program's custom DITL). Without it a headless run answers "cancel".
 * <p>A Save dialog's Format pop-up (NavDialogOptions.popupExtension) and the check boxes a program adds with
 * kNavCtlAddControlList are shown beside the host panel (Swing's chooser then, the platform panel has no room for them); the
 * choices reach the program as Navigation's window would deliver them: kNavCBPopupMenuSelect, and clicks as kNavCBEvent. Classic (non-Carbon)
 * programs are told Navigation Services is not available unless {@code -Dhzppc.nav=true}: they fall back to Standard File.
 * <p>File previews: NavCreatePreview stores a picture in the file's resource fork with a 'pnot' 0 naming it, and CarbonLib's
 * preview calls (NavCreatePreviewReference ... NavDrawPreview) read it back and draw it, scaled to fit, into an area of the port.
 */
final class PpcNavServices {
	private final PpcRuntime rt;
	private final M68kMemory memory;
	PpcNavServices(PpcRuntime rt){this.rt=rt;this.memory=rt.memory;}

	/** NavLibraryVersion: Navigation Services 2.0 (a NumVersion), what CarbonLib ran on Mac OS 9. */
	static final int VERSION=0x02008000;
	private static final int NO_ERR=0,PARAM_ERR=-50,USER_CANCELED=-128,WRONG_STATE=-5694,WRONG_CLASS=-5695,INVALID_MESSAGE=-5698;
	private static final int TYPE_FSS=0x66737320,TYPE_ALIS=0x616c6973,TYPE_FSREF=0x66737266,TYPE_NULL=0x6e756c6c,TYPE_WILD=0x2a2a2a2a;
	// dialog kinds
	private static final int GET=0,PUT=1,CHOOSE_FILE=2,CHOOSE_FOLDER=3,CHOOSE_VOLUME=4,CHOOSE_OBJECT=5,NEW_FOLDER=6,ASK_SAVE=7,ASK_DISCARD=8,ASK_REVIEW=9;
	private static final String[] TITLES={"Open","Save","Choose a File","Choose a Folder","Choose a Volume","Choose an Item","New Folder","Save Changes","Discard Changes","Review Changes"};
	/** The same on a Japanese system (Mac OS 9 J's Navigation Services speaks Japanese there). */
	private static final String[] TITLES_JA={"開く","保存","ファイルを選択","フォルダを選択","ボリュームを選択","項目を選択","新規フォルダ","変更内容の保存","変更内容の破棄","変更内容の確認"};
	private boolean japanese(){return rt.toolbox.encoding==StackTextEncoding.JAPANESE;}
	private String kindTitle(int kind){return (japanese()?TITLES_JA:TITLES)[kind];}
	// event procedure messages (NavEventCallbackMessage)
	private static final int CB_EVENT=0,CB_CUSTOMIZE=1,CB_START=2,CB_TERMINATE=3,CB_POPUP_MENU_SELECT=8,CB_ACCEPT=9,CB_CANCEL=10,CB_USER_ACTION=12;
	// NavUserAction
	private static final int ACTION_NONE=0,ACTION_CANCEL=1,ACTION_OPEN=2,ACTION_SAVE_AS=3,ACTION_CHOOSE=4,ACTION_NEW_FOLDER=5,ACTION_SAVE_CHANGES=6,ACTION_DONT_SAVE=7,ACTION_DISCARD=8,ACTION_REVIEW_DOCUMENTS=9,ACTION_DISCARD_DOCUMENTS=10;
	// NavDialogOptionFlags
	private static final int DEFAULT_FLAGS=0xe4,NO_TYPE_POPUP=0x1,ALLOW_MULTIPLE=0x80;
	// NavDialogOptions (#pragma options align=mac68k): Str255 fields from clientName on
	private static final int O_FLAGS=2,O_LOCATION=6,O_CLIENT=10,O_TITLE=266,O_ACTION=522,O_CANCEL=778,O_SAVED=1034,O_MESSAGE=1290,O_POPUP=1550,OPTIONS_SIZE=2048;
	// NavMenuItemSpec: version, menuCreator, menuType, Str255 menuItemName, reserved[245] (511 bytes, padded to 512)
	private static final int SPEC_CREATOR=2,SPEC_TYPE=6,SPEC_NAME=10,SPEC_SIZE=512;
	// NavDialogCreationOptions: CFStringRefs
	private static final int C_FLAGS=2,C_LOCATION=6,C_CLIENT=10,C_TITLE=14,C_ACTION=18,C_CANCEL=22,C_SAVE=26,C_MESSAGE=30,C_MODALITY=42,C_PARENT=46,CREATION_SIZE=66;
	// NavReplyRecord: selection is an AEDescList (type, dataHandle); saveFileName (a CFStringRef) exists from version 1 (UI 3.4)
	private static final int R_VALID=2,R_REPLACING=3,R_SELECTION=6,R_KEYSCRIPT=14,R_TRANSLATION=16,R_SAVE_NAME=24,REPLY_SIZE=255;
	// NavCBRec: customRect, previewRect, eventData (param, itemHit), userAction (UI 3.4)
	private static final int CB_CONTEXT=2,CB_WINDOW=6,CB_CUSTOM_RECT=10,CB_PREVIEW_RECT=18,CB_PARAM=26,CB_ITEM_HIT=30,CB_USER_ACTION_FIELD=32,CBREC_SIZE=256;

	/** One Navigation dialog: a NavDialogRef (Carbon) or the context of a classic call, as its event procedure sees it. */
	private static final class Dialog {
		final int kind;final boolean carbon;int ref;
		int flags=DEFAULT_FLAGS;String clientName="",windowTitle="",actionLabel="",cancelLabel="",saveName="",message="";int modality=2,parentWindow;
		int[] types;int creator,fileType,fileCreator,askAction,documentCount;
		int eventProc,userData;
		/** The folder shown (kNavCtlSetLocation, defaultLocation) and the files chosen (or preset with kNavCtlSetSelection). */
		File location;List<File> selection=new ArrayList<File>();
		int userAction=ACTION_NONE;boolean replacing,running,ran,terminated,disposed,extensionHidden;
		/** kNavCtlAccept / kNavCtlCancel before the panel came up (from kNavCBStart): the answer without asking. */
		int preset=ACTION_NONE;
		/** The hidden dialog window and the NavCBRec handed to the event procedure; the first custom item's number less one. */
		int window,cbRec,firstControl;boolean addedControls;
		/** The program's current port when the dialog first ran: Navigation gives it back when its window goes. */
		int portBefore;
		/** NavDialogGetSaveFileName's CFString (owned by the dialog). */
		int saveNameString;
		/** The Format pop-up: the program's popupExtension items (NavMenuItemSpecs) and the one shown chosen. */
		final List<byte[]> formats=new ArrayList<byte[]>();int format;
		/** The check boxes and radio buttons of the program's custom DITLs (kNavCtlAddControlList): {item number, item type, top, left, bottom, right}, and their titles. */
		final List<int[]> customItems=new ArrayList<int[]>();final List<String> customTitles=new ArrayList<String>();
		Dialog(int kind,boolean carbon){this.kind=kind;this.carbon=carbon;}
		boolean fileDialog(){return kind<=NEW_FOLDER;}
		boolean ask(){return kind>=ASK_SAVE;}
	}
	private final Map<Integer,Dialog> dialogs=new HashMap<Integer,Dialog>();

	private int arg(int i) throws M68kCpuException {return rt.arg(i);}
	private void ret(int v){rt.ret(v);}
	/** Whether the program is a Carbon program (CarbonLib's start-up answered Gestalt 'cbon'): it has no Standard File to fall back on. */
	private boolean carbonProgram(){return rt.toolbox.gestaltValues.containsKey(0x63626f6e);}
	/** NavServicesCanRun for NavigationLib imports: Carbon programs, or anyone with -Dhzppc.nav=true. */
	private boolean available(){return carbonProgram()||Boolean.getBoolean("hzppc.nav");}

	void define(PpcLibraries l){
		// ---- the library (replaces PpcCarbon's "not available" stubs for NavServicesAvailable/CanRun/NavLibraryVersion)
		l.register(null,"NavLoad",(r,c)->ret(NO_ERR));
		l.register(null,"NavUnload",(r,c)->ret(NO_ERR));
		l.register(null,"NavLibraryVersion",(r,c)->ret(available()?VERSION:0)); // 0 keeps a classic program on Standard File
		l.register(null,"NavServicesCanRun",(r,c)->ret(available()?1:0));
		l.register(null,"NavServicesAvailable",(r,c)->ret(available()?1:0)); // 68K glue's name; CFM programs use the macro
		l.register("CarbonLib","NavLibraryVersion",(r,c)->ret(VERSION));
		l.register("CarbonLib","NavServicesCanRun",(r,c)->ret(1));
		l.register("CarbonLib","NavServicesAvailable",(r,c)->ret(1));
		l.register(null,"NavGetDefaultDialogOptions",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}defaultOptions(arg(0));ret(NO_ERR);});
		l.register(null,"NavGetDefaultDialogCreationOptions",(r,c)->{if(arg(0)==0){ret(PARAM_ERR);return;}defaultCreationOptions(arg(0));ret(NO_ERR);});

		// ---- classic modal calls: (defaultLocation, reply, dialogOptions, eventProc, ...)
		l.register(null,"NavGetFile",(r,c)->ret(classicFile(GET,arg(0),arg(1),arg(2),arg(3),arg(6),arg(7),0,0))); // previewProc arg(4), filterProc arg(5)
		l.register(null,"NavChooseFile",(r,c)->ret(classicFile(CHOOSE_FILE,arg(0),arg(1),arg(2),arg(3),arg(6),arg(7),0,0)));
		l.register(null,"NavPutFile",(r,c)->ret(classicFile(PUT,arg(0),arg(1),arg(2),arg(3),0,arg(6),arg(4),arg(5)))); // fileType, fileCreator
		l.register(null,"NavChooseFolder",(r,c)->ret(classicFile(CHOOSE_FOLDER,arg(0),arg(1),arg(2),arg(3),0,arg(5),0,0))); // filterProc arg(4)
		l.register(null,"NavChooseVolume",(r,c)->ret(classicFile(CHOOSE_VOLUME,arg(0),arg(1),arg(2),arg(3),0,arg(5),0,0)));
		l.register(null,"NavChooseObject",(r,c)->ret(classicFile(CHOOSE_OBJECT,arg(0),arg(1),arg(2),arg(3),0,arg(5),0,0)));
		l.register(null,"NavNewFolder",(r,c)->ret(classicFile(NEW_FOLDER,arg(0),arg(1),arg(2),arg(3),0,arg(4),0,0)));
		l.register(null,"NavAskSaveChanges",(r,c)->ret(classicAsk(ASK_SAVE,arg(0),arg(1),arg(2),arg(3),arg(4)))); // (options, action, *reply, eventProc, ud)
		l.register(null,"NavCustomAskSaveChanges",(r,c)->ret(classicAsk(ASK_SAVE,arg(0),-1,arg(1),arg(2),arg(3)))); // the options' message is the question
		l.register(null,"NavAskDiscardChanges",(r,c)->ret(classicAsk(ASK_DISCARD,arg(0),0,arg(1),arg(2),arg(3))));
		l.register(null,"NavDisposeReply",(r,c)->ret(disposeReply(arg(0))));
		l.register(null,"NavCompleteSave",(r,c)->ret(arg(0)==0?PARAM_ERR:NO_ERR)); // nothing to translate: the program wrote its own format
		l.register(null,"NavTranslateFile",(r,c)->ret(arg(0)==0?PARAM_ERR:NO_ERR));
		definePreviews(l);
		l.register(null,"NavCustomControl",(r,c)->ret(customControl(arg(0),arg(1),arg(2))));

		// ---- CarbonLib 1.1 dialog objects
		l.register(null,"NavCreateGetFileDialog",(r,c)->ret(create(GET,arg(0),arg(1),0,0,0,arg(2),arg(5),arg(6)))); // (options, typeList, eventProc, previewProc, filterProc, clientData, *outDialog)
		l.register(null,"NavCreateChooseFileDialog",(r,c)->ret(create(CHOOSE_FILE,arg(0),arg(1),0,0,0,arg(2),arg(5),arg(6))));
		l.register(null,"NavCreatePutFileDialog",(r,c)->ret(create(PUT,arg(0),0,arg(1),arg(2),0,arg(3),arg(4),arg(5)))); // (options, fileType, creator, eventProc, clientData, *outDialog)
		l.register(null,"NavCreateChooseFolderDialog",(r,c)->ret(create(CHOOSE_FOLDER,arg(0),0,0,0,0,arg(1),arg(3),arg(4)))); // (options, eventProc, filterProc, clientData, *outDialog)
		l.register(null,"NavCreateChooseVolumeDialog",(r,c)->ret(create(CHOOSE_VOLUME,arg(0),0,0,0,0,arg(1),arg(3),arg(4))));
		l.register(null,"NavCreateChooseObjectDialog",(r,c)->ret(create(CHOOSE_OBJECT,arg(0),0,0,0,0,arg(1),arg(4),arg(5)))); // (options, eventProc, previewProc, filterProc, clientData, *outDialog)
		l.register(null,"NavCreateNewFolderDialog",(r,c)->ret(create(NEW_FOLDER,arg(0),0,0,0,0,arg(1),arg(2),arg(3))));
		l.register(null,"NavCreateAskSaveChangesDialog",(r,c)->ret(create(ASK_SAVE,arg(0),0,0,0,arg(1),arg(2),arg(3),arg(4)))); // (options, action, eventProc, clientData, *outDialog)
		l.register(null,"NavCreateAskDiscardChangesDialog",(r,c)->ret(create(ASK_DISCARD,arg(0),0,0,0,0,arg(1),arg(2),arg(3))));
		l.register(null,"NavCreateAskReviewDocumentsDialog",(r,c)->ret(create(ASK_REVIEW,arg(0),0,0,0,arg(1),arg(2),arg(3),arg(4)))); // (options, documentCount, ...): Mac OS X 10.2's
		l.register(null,"NavDialogRun",(r,c)->{Dialog d=dialogs.get(arg(0));if(d==null){ret(PARAM_ERR);return;}if(d.running){ret(WRONG_STATE);return;}run(d,0);ret(NO_ERR);});
		l.register(null,"NavDialogDispose",(r,c)->{Dialog d=dialogs.get(arg(0));if(d!=null)dispose(d);});
		l.register(null,"NavDialogGetWindow",(r,c)->{Dialog d=dialogs.get(arg(0));ret(d==null?0:d.window);});
		l.register(null,"NavDialogGetUserAction",(r,c)->{Dialog d=dialogs.get(arg(0));ret(d==null?ACTION_NONE:d.userAction);});
		l.register(null,"NavDialogGetReply",(r,c)->{ // after the user accepted a file dialog
			Dialog d=dialogs.get(arg(0));if(d==null||arg(1)==0){ret(PARAM_ERR);return;}
			if(!d.fileDialog()){ret(WRONG_CLASS);return;}
			if(d.userAction==ACTION_NONE||d.userAction==ACTION_CANCEL){ret(WRONG_STATE);return;}
			ret(writeReply(d,arg(1)));});
		l.register(null,"NavDialogGetSaveFileName",(r,c)->{ // a CFString the dialog owns
			Dialog d=dialogs.get(arg(0));if(d==null||d.kind!=PUT){ret(0);return;}
			if(d.saveNameString!=0)rt.libraries.coreFoundation.release(d.saveNameString);
			d.saveNameString=rt.libraries.textServices.newCFString(d.saveName);ret(d.saveNameString);});
		l.register(null,"NavDialogSetSaveFileName",(r,c)->{
			Dialog d=dialogs.get(arg(0));if(d==null||arg(1)==0){ret(PARAM_ERR);return;}if(d.kind!=PUT){ret(WRONG_CLASS);return;}
			String s=rt.libraries.textServices.cfText(arg(1));if(s==null){ret(PARAM_ERR);return;}d.saveName=s;ret(NO_ERR);});
		l.register(null,"NavDialogGetSaveFileExtensionHidden",(r,c)->{Dialog d=dialogs.get(arg(0));ret(d!=null&&d.extensionHidden?1:0);});
		l.register(null,"NavDialogSetSaveFileExtensionHidden",(r,c)->{Dialog d=dialogs.get(arg(0));if(d==null){ret(PARAM_ERR);return;}d.extensionHidden=(arg(1)&0xff)!=0;ret(NO_ERR);});
		l.register(null,"NavDialogSetFilterTypeIdentifiers",(r,c)->ret(dialogs.containsKey(arg(0))?NO_ERR:PARAM_ERR)); // UTIs: the OSType list filters here
	}

	// ------------------------------------------------------------------ options

	/** NavGetDefaultDialogOptions: the default flags, the default position, and the application's name as the client name. */
	private void defaultOptions(int p) throws M68kCpuException {
		memory.fill(p,OPTIONS_SIZE,0);
		memory.writeLong(p+O_FLAGS,DEFAULT_FLAGS);memory.writeWord(p+O_LOCATION,-1);memory.writeWord(p+O_LOCATION+2,-1);
		rt.toolbox.writePascal(p+O_CLIENT,applicationName(),255);
	}
	/** NavGetDefaultDialogCreationOptions: the default flags and position, no strings, application-modal. */
	private void defaultCreationOptions(int p) throws M68kCpuException {
		memory.fill(p,CREATION_SIZE,0);
		memory.writeLong(p+C_FLAGS,DEFAULT_FLAGS);memory.writeWord(p+C_LOCATION,-1);memory.writeWord(p+C_LOCATION+2,-1);
		memory.writeLong(p+C_MODALITY,2); // kWindowModalityAppModal
	}
	private String applicationName(){String n=rt.toolbox.name;return n==null?"":n;}
	/** A classic NavDialogOptions record (Str255 fields in the program's script); null: the defaults. */
	private void readOptions(Dialog d,int p) throws M68kCpuException {
		if(p==0){d.clientName=applicationName();return;}
		d.flags=memory.readLong(p+O_FLAGS);
		d.clientName=rt.toolbox.readPascal(p+O_CLIENT);d.windowTitle=rt.toolbox.readPascal(p+O_TITLE);
		d.actionLabel=rt.toolbox.readPascal(p+O_ACTION);d.cancelLabel=rt.toolbox.readPascal(p+O_CANCEL);
		d.saveName=rt.toolbox.readPascal(p+O_SAVED);d.message=rt.toolbox.readPascal(p+O_MESSAGE);
		int extension=memory.readLong(p+O_POPUP);if(extension!=0&&(d.flags&NO_TYPE_POPUP)==0)readFormats(d,extension);
	}
	/** A NavMenuItemSpecArrayHandle: the Format pop-up's items (Script Editor: テキスト, コンパイル済みスクリプト, アプリケーション). */
	private void readFormats(Dialog d,int h) throws M68kCpuException {
		if(!rt.toolbox.api.isHandle(h))return;int p=memory.readLong(h);if(p==0)return;
		int n=Math.min(64,rt.toolbox.api.handleSize(h)/SPEC_SIZE);
		for(int i=0;i<n;i++)d.formats.add(memory.readBytes(p+i*SPEC_SIZE,SPEC_SIZE));
	}
	/** The Format item for a creator and type (creator 0: any), else -1. */
	private static int formatOf(Dialog d,int creator,int type){
		for(int i=0;i<d.formats.size();i++){byte[] f=d.formats.get(i);if(s32(f,SPEC_TYPE)==type&&(creator==0||s32(f,SPEC_CREATOR)==creator))return i;}
		return -1;
	}
	private static int s32(byte[] b,int o){return (b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|b[o+3]&255;}
	/** A NavDialogCreationOptions record (CFStrings); null: the defaults. */
	private void readCreationOptions(Dialog d,int p) throws M68kCpuException {
		if(p==0){d.clientName=applicationName();return;}
		d.flags=memory.readLong(p+C_FLAGS);
		d.clientName=cf(memory.readLong(p+C_CLIENT));d.windowTitle=cf(memory.readLong(p+C_TITLE));
		d.actionLabel=cf(memory.readLong(p+C_ACTION));d.cancelLabel=cf(memory.readLong(p+C_CANCEL));
		d.saveName=cf(memory.readLong(p+C_SAVE));d.message=cf(memory.readLong(p+C_MESSAGE));
		d.modality=memory.readLong(p+C_MODALITY);d.parentWindow=memory.readLong(p+C_PARENT);
	}
	private String cf(int ref){if(ref==0)return "";String s=rt.libraries.textServices.cfText(ref);return s==null?"":s;}
	/** A NavTypeListHandle: {componentSignature, reserved, osTypeCount, osType[]}. */
	private void readTypeList(Dialog d,int h) throws M68kCpuException {
		if(h==0)return;int p=memory.readLong(h);if(p==0)return;
		d.creator=memory.readLong(p);int n=(short)memory.readWord(p+6);
		if(n<=0)return; // no types: every file of the creator
		int[] types=new int[Math.min(n,256)];for(int i=0;i<types.length;i++)types[i]=memory.readLong(p+8+4*i);d.types=types;
	}

	// ------------------------------------------------------------------ the calls

	private Dialog newDialog(int kind,boolean carbon) throws M68kCpuException {
		Dialog d=new Dialog(kind,carbon);d.ref=rt.allocate(16,4);memory.writeLong(d.ref,0x687a4e44); // 'hzND': a NavDialogRef
		dialogs.put(d.ref,d);return d;
	}
	private void dispose(Dialog d) throws M68kException {
		if(d.disposed)return;d.disposed=true;dialogs.remove(d.ref);
		if(d.window!=0){int w=d.window;try{rt.toolbox("DisposeDialog",w);}catch(M68kException e){/* already gone */}d.window=0;
			// the event procedure focused the stand-in window (SoundApp's PowerPlant draws through it) and disposing it left the Window
			// Manager's port current: the real Navigation Services give the caller's port back. SoundApp's playlist (PowerPlant) still
			// believed its window was focused, its next ClipRect went to the other port and DrawGrowIcon drew the scroll bar lines
			// across the whole window
			int now=rt.toolbox.api.currentQuickDrawPort();
			if(now!=d.portBefore&&d.portBefore!=0&&d.portBefore!=w&&rt.toolbox.api.quickDraw().knownPort(d.portBefore))
				try{rt.toolbox("SetPort",d.portBefore);}catch(M68kException e){/* gone too */}}
		if(d.saveNameString!=0){rt.libraries.coreFoundation.release(d.saveNameString);d.saveNameString=0;}
		if(!d.running){freeBlock(d.cbRec);d.cbRec=0;} // (a dialog disposed from its own event procedure keeps its NavCBRec until run returns)
		freeBlock(d.ref);
	}
	private void freeBlock(int p){try{if(p!=0&&memory.isAllocated(p))memory.free(p);}catch(Exception e){/* not ours */}}

	/** NavGetFile, NavPutFile, NavChooseXXX, NavNewFolder: a modal dialog whose answer goes into {@code reply}; userCanceledErr when cancelled. */
	private int classicFile(int kind,int location,int reply,int options,int eventProc,int typeList,int userData,int fileType,int creator) throws M68kException {
		if(reply==0)return PARAM_ERR;
		Dialog d=newDialog(kind,false);
		try{
			readOptions(d,options);readTypeList(d,typeList);d.fileType=fileType;d.fileCreator=creator;
			d.format=Math.max(0,formatOf(d,0,fileType)); // the document's own type is the format shown first
			d.eventProc=eventProc;d.userData=userData;setLocation(d,descFile(location));
			run(d,reply);
			return d.userAction==ACTION_NONE||d.userAction==ACTION_CANCEL?USER_CANCELED:NO_ERR;
		}finally{dispose(d);}
	}
	/** NavAskSaveChanges ({@code action} >= 0), NavCustomAskSaveChanges (-1), NavAskDiscardChanges: the answer as a UInt32 into {@code reply}. */
	private int classicAsk(int kind,int options,int action,int reply,int eventProc,int userData) throws M68kException {
		if(reply==0)return PARAM_ERR;
		Dialog d=newDialog(kind,false);
		try{
			readOptions(d,options);d.askAction=action;d.eventProc=eventProc;d.userData=userData;
			run(d,0);
			int result;
			if(kind==ASK_SAVE)result=d.userAction==ACTION_SAVE_CHANGES?1:d.userAction==ACTION_DONT_SAVE?3:2; // kNavAskSaveChangesSave / DontSave / Cancel
			else result=d.userAction==ACTION_DISCARD?1:2;                                                        // kNavAskDiscardChanges / Cancel
			memory.writeLong(reply,result);
			return NO_ERR;
		}finally{dispose(d);}
	}
	/** NavCreateXXXDialog: a dialog object, run later by NavDialogRun. */
	private int create(int kind,int options,int typeList,int fileType,int creator,int count,int eventProc,int clientData,int out) throws M68kException {
		if(out==0)return PARAM_ERR;
		Dialog d=newDialog(kind,true);
		readCreationOptions(d,options);readTypeList(d,typeList);d.fileType=fileType;d.fileCreator=creator;
		if(kind==ASK_SAVE)d.askAction=count;else d.documentCount=count;
		d.eventProc=eventProc;d.userData=clientData;
		memory.writeLong(out,d.ref);return NO_ERR;
	}

	/**
	 * Runs a dialog: the event procedure's start-up messages, the host panel or alert (unless the program already accepted,
	 * cancelled or terminated it from kNavCBStart), the reply (classic calls), then the closing messages.
	 */
	private void run(Dialog d,int reply) throws M68kException {
		d.running=true;d.ran=false;d.terminated=false;d.userAction=ACTION_NONE;
		if(d.portBefore==0)d.portBefore=rt.toolbox.api.currentQuickDrawPort();
		try{
			if(d.eventProc!=0){
				if(d.window==0)d.window=hiddenWindow(d);
				if(d.cbRec==0)d.cbRec=rt.allocate(CBREC_SIZE,4);
				if(d.fileDialog()){ // negotiate the custom area until the program leaves the rectangle alone
					for(int i=0;i<4&&!d.disposed;i++){byte[] before=memory.readBytes(d.cbRec+CB_CUSTOM_RECT,8);event(d,CB_CUSTOMIZE);if(Arrays.equals(before,memory.readBytes(d.cbRec+CB_CUSTOM_RECT,8)))break;}
				}
				event(d,CB_START);
			}
			if(d.disposed)return;
			if(!d.terminated)answer(d);
			d.ran=true;d.preset=ACTION_NONE;
			if(reply!=0&&d.fileDialog())writeReply(d,reply);
			if(d.eventProc!=0&&!d.terminated){
				if(d.fileDialog())event(d,d.userAction==ACTION_CANCEL?CB_CANCEL:CB_ACCEPT);
				if(d.carbon)event(d,CB_USER_ACTION);
			}
			event(d,CB_TERMINATE);
		}finally{
			d.running=false;
			if(d.disposed){freeBlock(d.cbRec);d.cbRec=0;}
		}
	}
	/** Calls the program's NavEventProc (a UPP, or a bare TVector from Carbon code) with the NavCBRec filled in. */
	private void event(Dialog d,int message) throws M68kException {event(d,message,0,0);}
	/** {@link #event(Dialog,int)} with eventData filled in: the event or message data, and the dialog item hit. */
	private void event(Dialog d,int message,int param,int itemHit) throws M68kException {
		if(d.eventProc==0||d.disposed||d.cbRec==0)return;
		int p=d.cbRec;
		memory.writeWord(p,1);memory.writeLong(p+CB_CONTEXT,d.ref);memory.writeLong(p+CB_WINDOW,d.window);
		memory.writeLong(p+CB_PARAM,param);memory.writeWord(p+CB_ITEM_HIT,itemHit);memory.writeLong(p+CB_USER_ACTION_FIELD,d.userAction);
		int[] args={message,p,d.userData};
		if(rt.isRoutineDescriptor(d.eventProc))rt.callUniversal(d.eventProc,0x00000fc0,args); // uppNavEventProcInfo
		else rt.cpu.call(d.eventProc,args);
	}
	/** An invisible dialog standing in for Navigation's window while the event procedure runs (programs add their controls to
	 *  it, look at its port); 0 when it cannot be made. */
	/** How many items the hidden dialog has before the program's own (Navigation's buttons, list, pop-ups ...). */
	private static final int NAV_OWN_ITEMS=10;
	private int hiddenWindow(Dialog d){
		try{
			// stand-ins for Navigation's own items: kNavCtlGetFirstControlID never answers 0 on a Mac, and Script Editor 1.8
			// takes a first control ID of 0 for "no custom controls" (it ignored the clicks on its applet check boxes)
			byte[] list=new byte[2+NAV_OWN_ITEMS*14];list[1]=(byte)(NAV_OWN_ITEMS-1);
			for(int i=0;i<NAV_OWN_ITEMS;i++)list[2+i*14+12]=(byte)0x80; // disabled userItems with an empty rectangle
			int items=rt.toolbox.api.createHandle(list);
			int rect=rt.allocate(8,4);memory.writeWord(rect,60);memory.writeWord(rect+2,40);memory.writeWord(rect+4,460);memory.writeWord(rect+6,560);
			int title=rt.allocate(256,4);rt.toolbox.writePascal(title,title(d),255);
			int w=rt.toolbox("NewDialog",0,rect,title,0,5,0,0,0,items); // invisible movableDBoxProc, behind every window
			freeBlock(rect);freeBlock(title);return w;
		}catch(Exception e){return 0;}
	}
	private String title(Dialog d){
		String t=d.windowTitle.isEmpty()?kindTitle(d.kind):d.windowTitle;
		return d.message.isEmpty()||d.ask()?t:t+" - "+d.message;
	}

	// ------------------------------------------------------------------ answers

	/** The -Dhzppc.navreply answers already used, and the value they came from (a new value starts over). */
	private int scriptedUsed;private String scriptedValue;
	/** The next scripted answer, or null when the dialogs are the user's. */
	private String scripted(){
		String v=System.getProperty("hzppc.navreply");if(v==null)return null;
		if(!v.equals(scriptedValue)){scriptedValue=v;scriptedUsed=0;}
		String[] parts=v.split(";",-1);return parts[Math.min(scriptedUsed++,parts.length-1)].trim();
	}

	/** Asks the user (or the script): sets the dialog's user action and, for file dialogs, the selection. */
	private void answer(Dialog d) throws M68kException {
		StandardFile68k sf=rt.toolbox.standardFile;
		if(d.ask()){
			String s=d.preset!=ACTION_NONE?null:scripted();
			if(d.preset==ACTION_CANCEL)d.userAction=ACTION_CANCEL;
			else if(d.preset==ACTION_ACCEPT_PENDING)d.userAction=d.kind==ASK_SAVE?ACTION_SAVE_CHANGES:d.kind==ASK_DISCARD?ACTION_DISCARD:ACTION_REVIEW_DOCUMENTS;
			else if(s!=null)d.userAction=scriptedAsk(d,s.toLowerCase(java.util.Locale.ROOT));
			else d.userAction=sf.panelUnavailable()?ACTION_CANCEL:alert(d);
			return;
		}
		List<File> files;
		if(d.preset==ACTION_CANCEL)files=new ArrayList<File>();
		else if(d.preset==ACTION_ACCEPT_PENDING)files=presetAnswer(d,sf);
		else{
			String s=scripted();
			if(s!=null){String[] parts=s.split("\\|");files=scriptedFiles(d,parts[0]);if(!files.isEmpty())scriptedExtras(d,parts);}
			else if(sf.panelUnavailable())files=new ArrayList<File>();
			else{
				int panel=d.kind==PUT||d.kind==NEW_FOLDER?StandardFile68k.PANEL_SAVE:d.kind==CHOOSE_FOLDER||d.kind==CHOOSE_VOLUME?StandardFile68k.PANEL_FOLDER:d.kind==CHOOSE_OBJECT?StandardFile68k.PANEL_OBJECT:StandardFile68k.PANEL_OPEN;
				String name=d.kind==NEW_FOLDER&&d.saveName.isEmpty()?"untitled folder":d.saveName;
				StandardFile68k.PanelExtras extras=d.kind==PUT?panelExtras(d):null;
				files=sf.hostPanel(panel,title(d),d.actionLabel,location(d),name,d.kind==GET||d.kind==CHOOSE_FILE||d.kind==CHOOSE_OBJECT?filter(d):null,d.kind==GET&&(d.flags&ALLOW_MULTIPLE)!=0,extras);
				if(!files.isEmpty()&&extras!=null&&!extras.isEmpty()){
					Boolean[] wanted=new Boolean[d.customItems.size()];for(int i=0;i<wanted.length;i++)wanted[i]=extras.checked.get(i);
					applyExtras(d,extras.formats.isEmpty()?-1:extras.format,wanted);}
			}
		}
		if(d.kind==NEW_FOLDER&&!files.isEmpty()){File f=files.get(0);if(!f.isDirectory()&&!f.mkdirs())files.clear();}
		if(d.kind==CHOOSE_VOLUME)for(int i=0;i<files.size();i++)files.set(i,volumeOf(files.get(i)));
		if(d.kind!=GET&&files.size()>1)files=new ArrayList<File>(files.subList(0,1));
		if(files.isEmpty()){d.userAction=ACTION_CANCEL;return;}
		d.selection=files;
		File first=files.get(0);
		switch(d.kind){
			case GET:d.userAction=ACTION_OPEN;break;
			case PUT:d.userAction=ACTION_SAVE_AS;d.replacing=first.exists();d.saveName=rt.toolbox.files.fileManager.programName(first.getName());d.location=first.getAbsoluteFile().getParentFile();break;
			case NEW_FOLDER:d.userAction=ACTION_NEW_FOLDER;break;
			default:d.userAction=ACTION_CHOOSE;
		}
		sf.chosen(files,d.kind==PUT||d.kind==NEW_FOLDER);
	}
	/** kNavCtlAccept before the panel was shown: accept what the dialog holds (the preset selection, the save name in the folder). */
	private List<File> presetAnswer(Dialog d,StandardFile68k sf){
		List<File> files=new ArrayList<File>();
		switch(d.kind){
			case PUT:case NEW_FOLDER:if(!d.saveName.isEmpty())files.add(new File(location(d),d.saveName.replace('/',':')));break;
			case CHOOSE_FOLDER:case CHOOSE_VOLUME:if(!d.selection.isEmpty())files.addAll(d.selection);else files.add(location(d));break;
			default:files.addAll(d.selection);
		}
		return files;
	}
	/** The Save panel's additions: the Format pop-up (popupExtension) and the custom DITL's check boxes with their current state. */
	private StandardFile68k.PanelExtras panelExtras(Dialog d) throws M68kException {
		StandardFile68k.PanelExtras e=new StandardFile68k.PanelExtras(japanese()?"形式:":"Format:");
		for(byte[] f:d.formats){int n=Math.min(255,f[SPEC_NAME]&255);e.formats.add(rt.toolbox.decode(Arrays.copyOfRange(f,SPEC_NAME+1,SPEC_NAME+1+n)));}
		e.format=d.format;
		for(int i=0;i<d.customItems.size();i++){int c=itemControl(d,d.customItems.get(i)[0]);int p=c==0?0:memory.readLong(c);
			e.checks.add(d.customTitles.get(i));e.checked.add(p!=0&&memory.readWord(p+18)!=0);e.enabled.add(p==0||(memory.readByte(p+17)&255)!=255);}
		return e;
	}
	/**
	 * Tells the program what the user chose beside the panel, as Navigation's own window would have: a new Format item is a
	 * kNavCBPopupMenuSelect with the NavMenuItemSpec, and a check box whose state should change gets a click - a kNavCBEvent
	 * (mouse-down) naming its item, which the program answers by toggling it (set directly when it does not).
	 * {@code wanted}: per custom item, the state asked for (null: leave it).
	 */
	private void applyExtras(Dialog d,int format,Boolean[] wanted) throws M68kException {
		if(format>=0&&format<d.formats.size()&&format!=d.format){
			d.format=format;int spec=rt.allocate(SPEC_SIZE,4);
			if(PpcRuntime.TRACE)System.err.println("[nav] Format item "+(format+1)+" of "+d.formats.size());
			try{memory.load(spec,d.formats.get(format));event(d,CB_POPUP_MENU_SELECT,spec,0);}finally{freeBlock(spec);}
		}
		for(int i=0;i<wanted.length&&i<d.customItems.size();i++){
			if(wanted[i]==null)continue;int[] item=d.customItems.get(i);int c=itemControl(d,item[0]);if(c==0)continue;int p=memory.readLong(c);
			if(PpcRuntime.TRACE)System.err.println("[nav] item "+item[0]+" control $"+Integer.toHexString(c)+" value "+(p==0?-1:memory.readWord(p+18))+" hilite "+(p==0?-1:memory.readByte(p+17)&255)+" wanted "+wanted[i]);
			if(p==0||(memory.readByte(p+17)&255)==255||(memory.readWord(p+18)!=0)==wanted[i])continue; // inactive, or already so
			int e=rt.allocate(16,4);
			WindowManager68k.Window w=rt.toolbox.windows.window(d.window);int left=w==null?0:w.left,top=w==null?0:w.top; // the event's point is global: the program finds the item with GlobalToLocal + FindDialogItem
			try{memory.writeWord(e,1);memory.writeLong(e+2,0);memory.writeLong(e+6,rt.toolbox("TickCount"));memory.writeWord(e+10,top+(item[2]+item[4])/2);memory.writeWord(e+12,left+(item[3]+item[5])/2);memory.writeWord(e+14,0); // mouseDown
				int port=rt.toolbox.api.currentQuickDrawPort();rt.toolbox("SetPort",d.window); // Navigation's window is the current port while its events are handled
				try{event(d,CB_EVENT,e,item[0]);}finally{rt.toolbox("SetPort",port);}}finally{freeBlock(e);}
			p=memory.readLong(c);
			if(p!=0&&(memory.readWord(p+18)!=0)!=wanted[i])rt.toolbox("SetControlValue",c,wanted[i]?1:0);
		}
	}
	/** The control of a custom dialog item, 0 when there is none. */
	private int itemControl(Dialog d,int item) throws M68kException {
		if(d.window==0)return 0;int out=rt.allocate(4,4);
		try{memory.writeLong(out,0);return rt.toolbox("GetDialogItemAsControl",d.window,item,out)==NO_ERR?memory.readLong(out):0;}finally{freeBlock(out);}
	}
	/** kNavCtlAddControlList: the check boxes and radio buttons of a DITL appended after {@code count} items. */
	private void readCustomItems(Dialog d,int ditl,int count) throws M68kException {
		int p=memory.readLong(ditl);if(p==0)return;int n=(short)memory.readWord(p)+1,q=p+2;
		for(int i=0;i<n&&i<256;i++){
			int type=memory.readByte(q+12)&127,length=memory.readByte(q+13)&255;
			if(type==5||type==6){d.customItems.add(new int[]{count+i+1,type,(short)memory.readWord(q+4),(short)memory.readWord(q+6),(short)memory.readWord(q+8),(short)memory.readWord(q+10)});
				d.customTitles.add(rt.toolbox.decode(memory.readBytes(q+14,length)));}
			q+=14+length+(length&1);
		}
	}
	/** A scripted Save answer's additions after the path: {@code |format=<name or 1-based number>}, {@code |item<k>=0|1} (the k-th custom check box). */
	private void scriptedExtras(Dialog d,String[] parts) throws M68kException {
		if(parts.length<2||d.kind!=PUT)return;
		int format=-1;Boolean[] wanted=new Boolean[d.customItems.size()];
		for(int i=1;i<parts.length;i++){String[] kv=parts[i].split("=",2);if(kv.length<2)continue;String key=kv[0].trim(),value=kv[1].trim();
			if(key.equals("format")){
				try{format=Integer.parseInt(value)-1;}catch(NumberFormatException e){
					for(int k=0;k<d.formats.size();k++){byte[] f=d.formats.get(k);int n=f[SPEC_NAME]&255;if(rt.toolbox.decode(Arrays.copyOfRange(f,SPEC_NAME+1,SPEC_NAME+1+n)).equals(value))format=k;}}}
			else if(key.startsWith("item")){try{int k=Integer.parseInt(key.substring(4))-1;if(k>=0&&k<wanted.length)wanted[k]=!value.equals("0");}catch(NumberFormatException e){/* not an item */}}
		}
		applyExtras(d,format,wanted);
	}
	private List<File> scriptedFiles(Dialog d,String s){
		List<File> files=new ArrayList<File>();
		if(s.isEmpty()||s.equalsIgnoreCase("cancel"))return files;
		for(String path:s.split(java.util.regex.Pattern.quote(File.pathSeparator)))if(!path.isEmpty()){
			File f=new File(path).getAbsoluteFile();
			if(d.kind==PUT&&f.isDirectory())f=new File(f,d.saveName.isEmpty()?"untitled":d.saveName); // a folder: the default name in it
			files.add(f);
		}
		return files;
	}
	private int scriptedAsk(Dialog d,String s){
		boolean dontSave=s.equals("dontsave")||s.equals("don't save")||s.equals("dont");
		switch(d.kind){
			case ASK_SAVE:return s.equals("save")?ACTION_SAVE_CHANGES:dontSave||s.equals("discard")?ACTION_DONT_SAVE:ACTION_CANCEL;
			case ASK_DISCARD:return s.equals("discard")||dontSave?ACTION_DISCARD:ACTION_CANCEL;
			default:return s.equals("review")||s.equals("save")?ACTION_REVIEW_DOCUMENTS:s.equals("discard")||dontSave?ACTION_DISCARD_DOCUMENTS:ACTION_CANCEL;
		}
	}
	/** The ask-save / discard / review alert on the host. */
	private int alert(final Dialog d){
		final boolean ja=japanese();
		final String doc=d.saveName.isEmpty()?ja?"名称未設定":"untitled":d.saveName;
		final String text;final String[] buttons;final int[] actions;
		switch(d.kind){
			case ASK_SAVE:{
				String client=d.clientName.isEmpty()?"":d.clientName+" ";
				text=!d.message.isEmpty()?d.message:ja?(d.clientName.isEmpty()?"":d.clientName+"の")+"書類“"+doc+"”の変更内容を"+(d.askAction==1?"閉じる前に":d.askAction==2?"終了する前に":"")+"保存しますか?"
					:"Save changes to the "+client+"document “"+doc+"”"+(d.askAction==1?" before closing?":d.askAction==2?" before quitting?":"?");
				buttons=new String[]{d.actionLabel.isEmpty()?ja?"保存":"Save":d.actionLabel,ja?"保存しない":"Don’t Save",d.cancelLabel.isEmpty()?ja?"キャンセル":"Cancel":d.cancelLabel};
				actions=new int[]{ACTION_SAVE_CHANGES,ACTION_DONT_SAVE,ACTION_CANCEL};break;}
			case ASK_DISCARD:
				text=!d.message.isEmpty()?d.message:ja?"書類“"+doc+"”の変更内容を破棄して、最後に保存した状態に戻しますか?":"Discard changes to the document “"+doc+"” and revert to the last saved version?";
				buttons=new String[]{d.actionLabel.isEmpty()?ja?"破棄":"Discard":d.actionLabel,d.cancelLabel.isEmpty()?ja?"キャンセル":"Cancel":d.cancelLabel};
				actions=new int[]{ACTION_DISCARD,ACTION_CANCEL};break;
			default:
				text=!d.message.isEmpty()?d.message:ja?"変更内容を保存していない書類が"+d.documentCount+"個あります。終了する前に変更内容を確認しますか?"
					:"You have "+d.documentCount+" document"+(d.documentCount==1?"":"s")+" with unsaved changes. Do you want to review these changes before quitting?";
				buttons=new String[]{d.actionLabel.isEmpty()?ja?"変更内容を確認…":"Review Changes…":d.actionLabel,ja?"変更内容を破棄":"Discard Changes",d.cancelLabel.isEmpty()?ja?"キャンセル":"Cancel":d.cancelLabel};
				actions=new int[]{ACTION_REVIEW_DOCUMENTS,ACTION_DISCARD_DOCUMENTS,ACTION_CANCEL};
		}
		Integer chosen=rt.toolbox.host().ask(()->javax.swing.JOptionPane.showOptionDialog(rt.toolbox.host().anyWindow(),text,d.clientName.isEmpty()?kindTitle(d.kind):d.clientName,
			javax.swing.JOptionPane.DEFAULT_OPTION,javax.swing.JOptionPane.WARNING_MESSAGE,null,buttons,buttons[0]));
		return chosen==null||chosen<0||chosen>=actions.length?ACTION_CANCEL:actions[chosen];
	}
	/** The files a Get/Choose File dialog shows: its type list, or with no types its creator's files ('****' or 0: all). */
	private java.util.function.Predicate<File> filter(Dialog d){
		final StandardFile68k sf=rt.toolbox.standardFile;final int[] types=d.types;final int creator=d.creator;
		if(types!=null)return f->sf.typeListAccepts(f,types);
		if(creator==0||creator==TYPE_WILD)return null;
		return f->{if(f.isDirectory())return true;int c=rt.toolbox.files.fileCreator(f);return c==0||c==creator;};
	}
	/** The volume (HZ HD ...) a chosen folder is on; a folder on none of them stands for itself. */
	private File volumeOf(File f){
		File best=null;int bestLength=-1;
		try{String p=f.getCanonicalPath();for(File v:rt.toolbox.files.fileManager.volumes){String vp=v.getCanonicalPath();if((p.equals(vp)||p.startsWith(vp+File.separator))&&vp.length()>bestLength){best=v;bestLength=vp.length();}}}catch(java.io.IOException e){/* as chosen */}
		return best!=null?best:f;
	}
	/** The folder the dialog shows: set by the program, else where the last file dialog was left. */
	private File location(Dialog d){return d.location!=null&&d.location.isDirectory()?d.location:rt.toolbox.standardFile.panelDirectory();}
	/** defaultLocation / kNavCtlSetLocation: a folder is shown; a file is shown in its folder and selected. */
	private void setLocation(Dialog d,File f){
		if(f==null)return;
		if(f.isDirectory()){d.location=f;return;}
		d.location=f.getAbsoluteFile().getParentFile();d.selection=new ArrayList<File>(List.of(f));
	}

	// ------------------------------------------------------------------ descriptors and the reply

	/** The file an AEDesc names ('fss ', 'alis', 'fsrf'); null for anything else. */
	private File descFile(int desc){
		try{
			if(desc==0)return null;int type=memory.readLong(desc),h=memory.readLong(desc+4);if(h==0)return null;int data=memory.readLong(h);if(data==0)return null;
			switch(type){
				case TYPE_FSS:return rt.toolbox.files.fileForSpec(data);
				case TYPE_ALIS:{byte[] b=rt.toolbox.api.handleContents(h);return b==null?null:rt.toolbox.files.resolveAlias(b);}
				case TYPE_FSREF:{ // CarbonLib's FSRefs: the path through FSRefMakePath
					int tv=rt.nativeSymbol("CarbonLib","FSRefMakePath",Pef.CLASS_TVECTOR,false);int buf=rt.allocate(2048,4);
					try{if(rt.cpu.call(tv,data,buf,2048)!=0)return null;return new File(new String(rt.cBytes(buf),java.nio.charset.StandardCharsets.UTF_8));}finally{freeBlock(buf);}}
				default:return null;
			}
		}catch(Exception e){return null;}
	}
	/** A 70-byte FSSpec of {@code f} in a new guest block (the caller frees it). */
	private int fsSpec(File f) throws M68kCpuException {
		int spec=rt.allocate(70,4);
		try{rt.toolbox.files.writeFileSpec(spec,f);}catch(java.io.IOException e){/* leaves a zero FSSpec */}
		return spec;
	}
	/** A new AEDescList at {@code desc} holding an 'fss ' FSSpec of each file. */
	private int fileList(int desc,List<File> files) throws M68kException {
		int err=rt.toolbox("AECreateList",0,0,0,desc);if(err!=0)return err;
		for(File f:files){int spec=fsSpec(f);try{err=rt.toolbox("AEPutPtr",desc,0,TYPE_FSS,spec,70);}finally{freeBlock(spec);}if(err!=0)return err;}
		return NO_ERR;
	}
	/**
	 * As {@link #fileList}, for chosen folders: each FSSpec names the folder by its own directory ID with an empty name
	 * (vRefNum, dirID, ""), the form Navigation Services hands back for a chosen folder - Disk Copy 6.3 takes parID as the folder
	 * (PBGetCatInfo with ioFDirIndex -1) and imaged the folder above it. Resolved as an FSSpec it is the same folder.
	 */
	private int folderList(int desc,List<File> folders) throws M68kException {
		int err=rt.toolbox("AECreateList",0,0,0,desc);if(err!=0)return err;
		for(File f:folders){
			int spec=fsSpec(f);
			try{
				FileManager68k fm=rt.toolbox.files.fileManager;
				if(f.isDirectory()&&!fm.volumeRoot(f)){memory.writeLong(spec+2,fm.directoryIdFor(f));memory.fill(spec+6,64,0);}
			}catch(java.io.IOException e){/* the plain FSSpec */}
			try{err=rt.toolbox("AEPutPtr",desc,0,TYPE_FSS,spec,70);}finally{freeBlock(spec);}
			if(err!=0)return err;
		}
		return NO_ERR;
	}
	/** The files of an AEDescList (each item coerced to an FSSpec). */
	private List<File> listFiles(int list) throws M68kException {
		List<File> files=new ArrayList<File>();int count=rt.allocate(8,4),spec=rt.allocate(80,4),type=rt.allocate(4,4),size=rt.allocate(4,4),key=rt.allocate(4,4);
		try{
			if(rt.toolbox("AECountItems",list,count)!=0)return files;
			for(int i=1;i<=memory.readLong(count);i++){
				if(rt.toolbox("AEGetNthPtr",list,i,TYPE_FSS,key,type,spec,70,size)!=0)continue;
				try{File f=rt.toolbox.files.fileForSpec(spec);if(f!=null)files.add(f);}catch(java.io.IOException e){/* skipped */}
			}
		}finally{freeBlock(count);freeBlock(spec);freeBlock(type);freeBlock(size);freeBlock(key);}
		return files;
	}
	/** Fills a NavReplyRecord from the dialog's answer (the selection list is new: NavDisposeReply disposes of it). */
	private int writeReply(Dialog d,int reply) throws M68kException {
		boolean valid=d.userAction!=ACTION_NONE&&d.userAction!=ACTION_CANCEL;boolean carbon=d.carbon||carbonProgram();
		memory.fill(reply,REPLY_SIZE,0);
		memory.writeWord(reply,carbon?1:0); // kNavReplyRecordVersion 1 has saveFileName
		memory.writeByte(reply+R_VALID,valid?1:0);
		memory.writeByte(reply+R_REPLACING,valid&&d.kind==PUT&&d.replacing?1:0);
		memory.writeWord(reply+R_KEYSCRIPT,rt.toolbox.encoding==StackTextEncoding.JAPANESE?1:0); // smJapanese / smRoman
		memory.writeLong(reply+R_TRANSLATION,0);
		if(!valid){memory.writeLong(reply+R_SELECTION,TYPE_NULL);memory.writeLong(reply+R_SELECTION+4,0);return NO_ERR;}
		int err=d.kind==CHOOSE_FOLDER?folderList(reply+R_SELECTION,d.selection):fileList(reply+R_SELECTION,d.selection);
		if(err!=0){memory.writeByte(reply+R_VALID,0);return err;}
		if(carbon&&d.kind==PUT)memory.writeLong(reply+R_SAVE_NAME,rt.libraries.textServices.newCFString(d.saveName));
		return NO_ERR;
	}
	/** NavDisposeReply: the selection list, the translation specs and (version 1) the save file name. */
	private int disposeReply(int reply) throws M68kException {
		if(reply==0)return PARAM_ERR;
		if(memory.readLong(reply+R_SELECTION+4)!=0)rt.toolbox("AEDisposeDesc",reply+R_SELECTION);
		memory.writeLong(reply+R_SELECTION,TYPE_NULL);memory.writeLong(reply+R_SELECTION+4,0);
		int translation=memory.readLong(reply+R_TRANSLATION);if(translation!=0){rt.trap(0xa023,0,translation,0);memory.writeLong(reply+R_TRANSLATION,0);} // DisposeHandle
		if(memory.readWord(reply)>=1){int name=memory.readLong(reply+R_SAVE_NAME);if(name!=0){rt.libraries.coreFoundation.release(name);memory.writeLong(reply+R_SAVE_NAME,0);}}
		memory.writeByte(reply+R_VALID,0);
		return NO_ERR;
	}

	// ------------------------------------------------------------------ file previews ('pnot')

	private static final int RES_NOT_FOUND=-192,FNF_ERR=-43,TYPE_PNOT=0x706e6f74,TYPE_PICT=0x50494354;
	/**
	 * A NavPreviewRef of CarbonLib's (private) preview calls: the area it draws in (window-local) and the preview of the file it
	 * was set up for - the picture its 'pnot' 0 resource names (QuickTime's PreviewResourceRecord: modDate, version, resType,
	 * resID), or a picture file's own picture. Only 'PICT' previews can be drawn.
	 */
	private static final class Preview {int top,left,bottom,right;File file;byte[] picture;}
	private final Map<Integer,Preview> previews=new HashMap<Integer,Preview>();

	private void definePreviews(PpcLibraries l){
		l.register(null,"NavCreatePreview",(r,c)->ret(createPreview(arg(0),arg(1),arg(2),arg(3)))); // (AEDesc *object, OSType previewDataType, previewData, previewDataSize)
		// the preview engine (CarbonLib-private; signatures inferred: a reference made, set up for a file, given an area, drawn, disposed of)
		l.register(null,"NavCreatePreviewReference",(r,c)->{int out=arg(0);if(out==0){ret(PARAM_ERR);return;}int ref=rt.allocate(16,4);memory.writeLong(ref,0x6e765056);previews.put(ref,new Preview());memory.writeLong(out,ref);ret(NO_ERR);}); // (NavPreviewRef *) 'nvPV'
		l.register(null,"NavDisposePreviewReference",(r,c)->{int ref=arg(0);if(previews.remove(ref)==null){ret(PARAM_ERR);return;}freeBlock(ref);ret(NO_ERR);});
		l.register(null,"NavSetupPreview",(r,c)->{Preview p=previews.get(arg(0));if(p==null){ret(PARAM_ERR);return;}p.file=null;p.picture=null; // (preview, const AEDesc *object)
			File f=descFile(arg(1));if(f==null){ret(PARAM_ERR);return;}if(!f.isFile()){ret(FNF_ERR);return;}p.file=f;p.picture=readPreview(f);ret(NO_ERR);});
		l.register(null,"NavHasPreview",(r,c)->{Preview p=previews.get(arg(0));ret(p!=null&&p.picture!=null?1:0);});
		l.register(null,"NavSetPreviewArea",(r,c)->{Preview p=previews.get(arg(0));int rect=arg(1);if(p==null||rect==0){ret(PARAM_ERR);return;}
			p.top=(short)memory.readWord(rect);p.left=(short)memory.readWord(rect+2);p.bottom=(short)memory.readWord(rect+4);p.right=(short)memory.readWord(rect+6);ret(NO_ERR);});
		l.register(null,"NavGetPreviewArea",(r,c)->{Preview p=previews.get(arg(0));int rect=arg(1);if(p==null||rect==0){ret(PARAM_ERR);return;}
			memory.writeWord(rect,p.top);memory.writeWord(rect+2,p.left);memory.writeWord(rect+4,p.bottom);memory.writeWord(rect+6,p.right);ret(NO_ERR);});
		l.register(null,"NavDrawPreview",(r,c)->{Preview p=previews.get(arg(0));if(p==null){ret(PARAM_ERR);return;}ret(drawPreview(p));}); // in the current port
		l.register(null,"NavHandlePreviewEvent",(r,c)->{Preview p=previews.get(arg(0));int ev=arg(1);if(p==null||ev==0){ret(0);return;} // (preview, EventRecord*) -> handled
			int what=memory.readWord(ev);
			if(what==6&&p.picture!=null){drawPreview(p);ret(0);return;} // updateEvt: the preview drawn again; the program still updates its window
			if(what!=1){ret(0);return;}                                // only a click in the area is the preview's
			int pt=rt.allocate(4,4);try{memory.writeLong(pt,memory.readLong(ev+10));rt.toolbox("GlobalToLocal",pt);int v=(short)memory.readWord(pt),h=(short)memory.readWord(pt+2);
				ret(v>=p.top&&v<p.bottom&&h>=p.left&&h<p.right?1:0);}finally{freeBlock(pt);}});
	}

	/** The file's preview picture ('pnot' 0 naming a 'PICT', or a picture file's data fork), null when it has none that can be drawn. */
	private byte[] readPreview(File f) throws M68kException {
		int spec=fsSpec(f),saved=rt.toolbox("CurResFile");boolean open=rt.toolbox.resources!=null&&rt.toolbox.resources.isOpenFile(f);byte[] picture=null;
		try{
			int ref=(short)rt.toolbox("FSpOpenResFile",spec,1); // fsRdPerm
			if(ref!=-1)try{
				int h=rt.toolbox("Get1Resource",TYPE_PNOT,0);int p=h==0?0:memory.readLong(h);
				if(p!=0&&rt.toolbox.api.handleSize(h)>=12&&memory.readLong(p+6)==TYPE_PICT){int pic=rt.toolbox("Get1Resource",TYPE_PICT,(short)memory.readWord(p+10));if(pic!=0&&memory.readLong(pic)!=0)picture=memory.readBytes(memory.readLong(pic),rt.toolbox.api.handleSize(pic));}
			}finally{if(!open)rt.toolbox("CloseResFile",ref);}
		}finally{rt.toolbox("UseResFile",saved);freeBlock(spec);}
		if(picture==null&&rt.toolbox.files.fileType(f)==TYPE_PICT)picture=pictureFile(f);
		return picture!=null&&picture.length>=10?picture:null;
	}
	/** A picture file's picture: the data fork after its 512-byte header. */
	private static byte[] pictureFile(File f){
		try{byte[] b=java.nio.file.Files.readAllBytes(f.toPath());return b.length>522?Arrays.copyOfRange(b,512,b.length):null;}catch(java.io.IOException e){return null;}
	}
	/** DrawPicture of the preview into its area, scaled down (never up) to fit and centred; resNotFound when there is no preview. */
	private int drawPreview(Preview p) throws M68kException {
		if(p.picture==null)return RES_NOT_FOUND;
		int w=p.right-p.left,hgt=p.bottom-p.top;if(w<=0||hgt<=0)return PARAM_ERR;
		int pt=(short)((p.picture[2]&0xff)<<8|p.picture[3]&0xff),pl=(short)((p.picture[4]&0xff)<<8|p.picture[5]&0xff),pb=(short)((p.picture[6]&0xff)<<8|p.picture[7]&0xff),pr=(short)((p.picture[8]&0xff)<<8|p.picture[9]&0xff); // picFrame
		int pw=Math.max(1,pr-pl),ph=Math.max(1,pb-pt);double scale=Math.min(1.0,Math.min((double)w/pw,(double)hgt/ph));
		int dw=(int)Math.round(pw*scale),dh=(int)Math.round(ph*scale),left=p.left+(w-dw)/2,top=p.top+(hgt-dh)/2;
		int h=rt.toolbox.api.createHandle(p.picture),rect=rt.allocate(8,4);
		try{memory.writeWord(rect,top);memory.writeWord(rect+2,left);memory.writeWord(rect+4,top+dh);memory.writeWord(rect+6,left+dw);rt.toolbox("DrawPicture",h,rect);}
		finally{freeBlock(rect);rt.trap(0xa023,0,h,0);} // DisposeHandle
		return NO_ERR;
	}
	/**
	 * NavCreatePreview: the preview data becomes a resource of the file's resource fork (made if needed) with a 'pnot' 0 naming it,
	 * replacing an older preview. No data: a picture file's own picture is used; other files have no preview Navigation can make here.
	 */
	private int createPreview(int desc,int type,int data,int size) throws M68kException {
		File f=descFile(desc);if(f==null)return PARAM_ERR;if(!f.isFile())return FNF_ERR;
		byte[] bytes;
		if(data!=0){if(size<=0)return PARAM_ERR;bytes=memory.readBytes(data,size);if(type==0)type=TYPE_PICT;}
		else{bytes=rt.toolbox.files.fileType(f)==TYPE_PICT?pictureFile(f):null;if(bytes==null)return PARAM_ERR;type=TYPE_PICT;}
		int spec=fsSpec(f),saved=rt.toolbox("CurResFile"),name=rt.allocate(2,4);boolean open=rt.toolbox.resources!=null&&rt.toolbox.resources.isOpenFile(f);
		try{
			rt.toolbox("FSpCreateResFile",spec,rt.toolbox.files.fileCreator(f),rt.toolbox.files.fileType(f),0); // dupFNErr when it has one already
			int ref=(short)rt.toolbox("FSpOpenResFile",spec,3); // fsRdWrPerm
			if(ref==-1){int e=(short)rt.toolbox("ResError");return e!=0?e:-39;}
			try{
				int old=rt.toolbox("Get1Resource",TYPE_PNOT,0);
				if(old!=0){int p=memory.readLong(old);if(p!=0&&rt.toolbox.api.handleSize(old)>=12){int was=rt.toolbox("Get1Resource",memory.readLong(p+6),(short)memory.readWord(p+10));if(was!=0){rt.toolbox("RemoveResource",was);rt.trap(0xa023,0,was,0);}}
					rt.toolbox("RemoveResource",old);rt.trap(0xa023,0,old,0);}
				int id=(short)rt.toolbox("Unique1ID",type);
				int h=rt.toolbox.api.createHandle(bytes);rt.toolbox("AddResource",h,type,id,name);int e=(short)rt.toolbox("ResError");if(e!=0)return e;
				byte[] rec=new byte[12];long now=Toolbox68k.macNow(); // PreviewResourceRecord: GetDateTime's seconds since 1904
				for(int i=0;i<4;i++){rec[i]=(byte)(now>>>(24-8*i));rec[6+i]=(byte)(type>>>(24-8*i));}rec[10]=(byte)(id>>8);rec[11]=(byte)id;
				rt.toolbox("AddResource",rt.toolbox.api.createHandle(rec),TYPE_PNOT,0,name);e=(short)rt.toolbox("ResError");if(e!=0)return e;
				rt.toolbox("UpdateResFile",ref);return (short)rt.toolbox("ResError");
			}finally{if(!open)rt.toolbox("CloseResFile",ref);}
		}finally{rt.toolbox("UseResFile",saved);freeBlock(spec);freeBlock(name);}
	}

	// ------------------------------------------------------------------ NavCustomControl

	/** kNavCtlAccept seen before the panel: accept without asking. */
	private static final int ACTION_ACCEPT_PENDING=-1;
	private int customControl(int ref,int selector,int parms) throws M68kException {
		Dialog d=dialogs.get(ref);if(d==null)return PARAM_ERR;
		switch(selector){
			case 7:{ // kNavCtlGetLocation: an 'fss ' descriptor of the folder shown
				if(parms==0)return PARAM_ERR;int spec=fsSpec(location(d));
				try{return rt.toolbox("AECreateDesc",TYPE_FSS,spec,70,parms);}finally{freeBlock(spec);}}
			case 8:{File f=descFile(parms);if(f==null)return PARAM_ERR;setLocation(d,f);return NO_ERR;} // kNavCtlSetLocation
			case 9:return parms==0?PARAM_ERR:fileList(parms,d.selection);                      // kNavCtlGetSelection
			case 10:{if(parms==0)return PARAM_ERR;List<File> files=listFiles(parms);d.selection=files;if(!files.isEmpty()&&d.location==null)d.location=files.get(0).getAbsoluteFile().getParentFile();return NO_ERR;} // kNavCtlSetSelection
			case 14:{if(parms==0)return PARAM_ERR;String name=rt.toolbox.readPascal(parms);if(name.isEmpty())return PARAM_ERR;File f=new File(location(d),name.replace('/',':'));return f.isDirectory()||f.mkdir()?NO_ERR:-48;} // kNavCtlNewFolder (dupFNErr when it cannot be made)
			case 15:if(!d.ran)d.preset=ACTION_CANCEL;return NO_ERR;                            // kNavCtlCancel
			case 16:if(!d.ran)d.preset=ACTION_ACCEPT_PENDING;return NO_ERR;                    // kNavCtlAccept
			case 30:d.terminated=true;if(d.ran)return NO_ERR;d.userAction=ACTION_NONE;return NO_ERR; // kNavCtlTerminate: dismissed with no user action
			case 17:if(parms!=0)memory.writeByte(parms,0);return NO_ERR;                        // kNavCtlIsPreviewShowing: no preview area
			case 20:if(parms!=0)memory.writeWord(parms,d.firstControl);return NO_ERR;           // kNavCtlGetFirstControlID
			case 19:{ // kNavCtlAddControlList: the DITL joins the hidden dialog (AppendDITL, overlaid)
				if(parms==0)return PARAM_ERR;if(d.window==0)return NO_ERR;
				int block=rt.allocate(12,4);
				try{
					memory.writeWord(block,0x403);memory.writeLong(block+2,d.window);int count=rt.trap(0xa08b,0,block,0)&0xffff; // CountDITL
					if(!d.addedControls){d.firstControl=count;d.addedControls=true;}
					memory.writeWord(block,0x402);memory.writeWord(block+2,0);memory.writeLong(block+4,parms);memory.writeLong(block+8,d.window);rt.trap(0xa08b,0,block,0); // AppendDITL
					readCustomItems(d,parms,count);
				}finally{freeBlock(block);}
				return NO_ERR;}
			case 21:{if(parms==0)return PARAM_ERR;int i=formatOf(d,memory.readLong(parms+SPEC_CREATOR),memory.readLong(parms+SPEC_TYPE));if(i<0)return PARAM_ERR;d.format=i;return NO_ERR;} // kNavCtlSelectCustomType
			case 23:if(parms==0)return PARAM_ERR;rt.toolbox.writePascal(parms,d.saveName,255);return NO_ERR; // kNavCtlGetEditFileName
			case 24:if(parms==0)return PARAM_ERR;d.saveName=rt.toolbox.readPascal(parms);return NO_ERR;       // kNavCtlSetEditFileName
			case 0:case 1:case 2:case 3:case 4:case 5:case 6:case 11:case 12:case 13:case 18:case 22:case 25:case 26:case 27:case 28:case 29:
				return NO_ERR; // browser display, sorting, eject, custom types, action states: nothing to do for the host panel
			default:return INVALID_MESSAGE;
		}
	}
}
