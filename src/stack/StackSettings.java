import java.io.File;

/**
 * A HyperZebra stack's own settings (the launcher's and the File menu's "Individual Stack Settings…", GAppSettingsDialog for a
 * stack): its zoom, its game controller bindings, the host keys for Command, Option and Control, Control-key shortcuts
 * (macOS), its speed limit (StackSpeed) and its access outside its folders (Sandbox). They are kept in LauncherStore like a program's, by the stack's
 * identity (its folder), and null follows the shared value, as for programs.
 * <p>The open stack's values are copied here when it opens ({@link #apply}) and again when its settings change
 * ({@link #changed}); the card window reads them from here (PCARD.scalePercent, GUI's keys, GamepadHost). The access
 * policy and the allowed places are Sandbox's, by the stack's requester name (Sandbox.stackOpened registers it).
 */
final class StackSettings {
	private StackSettings(){}

	/** The open stack's zoom (DisplayZoom choice), or null to follow the settings. */
	static volatile Integer zoom;
	/** The open stack's host keys for Command, Option and Control, or null for the default keys. */
	static volatile ModifierKeys modifierKeys;
	/** Control-arrow and the other system shortcuts go to the open stack while Control is held (macOS); null = no (the standard for stacks). */
	static volatile Boolean controlKeys;
	/** The identity of the stack these values are for (null: none open). */
	private static volatile String current;

	/** The stored settings of a stack (its folder, _stack.xml or file), or null. */
	static LauncherStore.AppSettings of(File stack){
		if(stack==null||!LauncherStore.enabled)return null;
		try{return LauncherStore.get().settingsFor(stack);}catch(RuntimeException e){e.printStackTrace();return null;}
	}

	/** A stack opens in the card window: its own values apply from now on (before its window is sized). */
	static void apply(OStack stack){
		File f=StackTranslation.fileOf(stack);
		current=f==null?null:LauncherStore.identity(f);
		set(of(f));
	}
	/** The card window shows no stack any more: the shared values apply again. */
	static void clear(){current=null;set(null);}
	private static void set(LauncherStore.AppSettings s){
		zoom=s==null||s.screenScale==null||!DisplayZoom.valid(s.screenScale)?null:s.screenScale;
		ModifierKeys keys=s==null||s.modifierKeys==null?null:ModifierKeys.parse(s.modifierKeys);
		modifierKeys=keys==null||keys.isEmpty()?null:keys;
		controlKeys=s==null?null:s.controlKeys;
		StackSpeed.set(s);
	}
	/** Whether {@code stack} is the stack open in the card window. */
	static boolean isOpen(File stack){String id=stack==null?null:LauncherStore.identity(stack);return id!=null&&id.equals(current);}

	/** The stack's settings were stored (its dialog's OK): the open stack takes them at once, the zoom too. */
	static void changed(File stack){
		if(!isOpen(stack))return;
		Integer oldZoom=zoom;
		set(of(stack));
		GUI.clearModifierKeys(); // keys held under the old assignment are not held under the new one
		if(!java.util.Objects.equals(oldZoom,zoom))PCARD.applyZoomLive();
	}

	/** The game controller bindings of the open stack, or null to follow the preference. */
	static String gamepadMapping(){
		PCARD pc=PCARD.pc;
		if(current==null||pc==null||pc.stack==null)return null;
		LauncherStore.AppSettings s=of(StackTranslation.fileOf(pc.stack));
		return s==null?null:s.gamepadMapping;
	}

	/** The Mac modifier (GUI.key index: 12 Option, 13 Control, 14 Command) the open stack gave this host key, or -1. */
	static int ownModifierIndex(int keyCode,int keyLocation){
		ModifierKeys own=modifierKeys;
		if(own==null)return -1;
		for(int i=0;i<3;i++)if(own.vk[i]==keyCode&&(own.location[i]==java.awt.event.KeyEvent.KEY_LOCATION_UNKNOWN||own.location[i]==keyLocation))return GUI_INDEX[i];
		return -1;
	}
	/** Whether the open stack has its own key for this Mac modifier (GUI.key index 12, 13 or 14): the default key no longer counts for it. */
	static boolean hasOwnKey(int guiIndex){
		ModifierKeys own=modifierKeys;
		if(own==null)return false;
		for(int i=0;i<3;i++)if(GUI_INDEX[i]==guiIndex)return own.vk[i]>=0;
		return false;
	}
	/** ModifierKeys.COMMAND, OPTION, CONTROL as GUI.key indexes. */
	private static final int[] GUI_INDEX={14,12,13};
}
