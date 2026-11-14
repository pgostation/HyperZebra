import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** What a VM host tells the VM (EventManager68k): mouse, keys, menus, windows (moved out of MacAppHost for P4). */
interface VmHostListener {
	void postMouse(boolean down,int globalH,int globalV,int modifiers,long when);
	void postKey(int what,int keyCode,int charCode,int modifiers);
	void postMenuSelection(int menuId,int item);
	void postMenuKey(int menuId,int item,int charCode,int modifiers);
	/** The user opened menu {@code menuId} of the host bar: the program may update it first (its MBarHook, see
	 *  MenuManager68k.prepareOpening); true when it did so within {@code timeoutMillis}. */
	default boolean menuOpening(int menuId,long timeoutMillis){return false;}
	void postActivate(int window,boolean active);
	void postUpdate(int window);
	void postWindowMoved(int window,int globalLeft,int globalTop);
	/** The host moved a window onto the display by itself (MacAppHost.keepOnDisplay), not the user. */
	default void postWindowPlaced(int window,int globalLeft,int globalTop){postWindowMoved(window,globalLeft,globalTop);}
	void postWindowResized(int window,int width,int height);
	void postGoAway(int window);
	/** The host item that stands for tearing a picture menu off (see {@link MenuModel68k#tearOff}) was chosen. */
	void postTearOff(int menuId);
	void postZoom(int window);
	void postOpenDocuments(List<File> files);
	/** Files dragged from the host's file manager over a window (DragManager68k.EXTERNAL_MOVE/EXIT/DROP, global coordinates;
	 *  {@code files} null while the host does not tell them during the drag). A drop no receive handler takes opens the files. */
	default void postExternalDrag(int phase,int globalH,int globalV,List<File> files,int modifiers){if(phase==DragManager68k.EXTERNAL_DROP&&files!=null&&!files.isEmpty())postOpenDocuments(files);}
	void postQuit();
	/** A dialog of the program now waits for the user (ModalDialog/Alert, a file dialog): HyperZebra's quit notes it (EventManager68k). */
	default void dialogShown(){}
	void postSuspendResume(boolean resume);
	/** Mouse wheel over a window: {@code steps} lines (positive = down/right) toward the nearest scroll bar. */
	void postWheel(int globalH,int globalV,int steps,boolean horizontal);
	/** Global {x, y, height} of the active TextEdit insertion point, or null (input method windows go there). */
	int[] textCaret();
	/** Text from the host input method for the Text Services Manager's active document: {@code committed} is final,
	 *  {@code composing} still being entered ({@code styles}: one TSM hilite style per character of it, {@code caret}
	 *  the caret's character index within it). True when an inline-input document took it - the host then neither
	 *  types the text as key events nor shows its own composition window. */
	boolean postInputMethodText(String committed,String composing,int[] styles,int caret);
}
