import java.awt.event.KeyEvent;

/**
 * A program's own host keys for the Mac modifier keys (the launcher's Application Settings): Command, Option and Control can
 * each be any host key - one side of a host modifier (right Ctrl) or an ordinary key - instead of the default (Command = Ctrl
 * on Windows and Linux, Command = Meta on macOS, Option = Alt, Control = Ctrl). A key given to a Mac modifier loses its own
 * meaning in the program: an ordinary key types nothing and is absent from GetKeys, a host modifier no longer stands for its
 * default Mac modifier. The Mac modifiers left at the default keep their default keys.
 * F1GP steers right with Command and brakes with Control: on Windows, where Ctrl is both, braking also steered right.
 * Stored as {@code command=X;control=CONTROL@right} (KeyEvent VK names without "VK_", an optional side).
 */
final class ModifierKeys {
	static final int COMMAND=0,OPTION=1,CONTROL=2;
	static final String[] NAMES={"command","option","control"};
	/** The event-record modifier bits: cmdKey, optionKey, controlKey. */
	static final int[] BITS={0x100,0x800,0x1000};
	/** The host key of each Mac modifier, -1 for the default keys. */
	final int[] vk={-1,-1,-1};
	/** Its side (KeyEvent.KEY_LOCATION_LEFT, RIGHT or NUMPAD), or KEY_LOCATION_UNKNOWN for any. */
	final int[] location=new int[3];

	boolean isEmpty(){return vk[0]<0&&vk[1]<0&&vk[2]<0;}
	void set(int modifier,int keyCode,int keyLocation){vk[modifier]=keyCode;location[modifier]=keyCode<0?KeyEvent.KEY_LOCATION_UNKNOWN:sided(keyLocation);}
	private static int sided(int l){return l==KeyEvent.KEY_LOCATION_LEFT||l==KeyEvent.KEY_LOCATION_RIGHT||l==KeyEvent.KEY_LOCATION_NUMPAD?l:KeyEvent.KEY_LOCATION_UNKNOWN;}

	String format(){
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<3;i++){if(vk[i]<0)continue;if(sb.length()>0)sb.append(';');sb.append(NAMES[i]).append('=').append(GamepadHost.vkName(vk[i])).append(sideName(location[i]));}
		return sb.toString();
	}
	static ModifierKeys parse(String s){
		ModifierKeys m=new ModifierKeys();if(s==null)return m;
		for(String item:s.split(";")){
			int eq=item.indexOf('=');if(eq<0)continue;
			String name=item.substring(0,eq).trim().toLowerCase(java.util.Locale.ROOT),value=item.substring(eq+1).trim();
			int at=value.indexOf('@'),l=KeyEvent.KEY_LOCATION_UNKNOWN;
			if(at>=0){String side=value.substring(at+1).trim().toLowerCase(java.util.Locale.ROOT);value=value.substring(0,at).trim();
				l=side.equals("left")?KeyEvent.KEY_LOCATION_LEFT:side.equals("right")?KeyEvent.KEY_LOCATION_RIGHT:side.equals("numpad")?KeyEvent.KEY_LOCATION_NUMPAD:l;}
			int code=GamepadHost.vkOf(value);if(code<=0)continue;
			for(int i=0;i<3;i++)if(NAMES[i].equals(name))m.set(i,code,l);
		}
		return m;
	}
	private static String sideName(int l){return l==KeyEvent.KEY_LOCATION_LEFT?"@left":l==KeyEvent.KEY_LOCATION_RIGHT?"@right":l==KeyEvent.KEY_LOCATION_NUMPAD?"@numpad":"";}

	/** Whether the host key is given to a Mac modifier (and so types nothing itself). */
	boolean isSource(int keyCode,int keyLocation){
		for(int i=0;i<3;i++)if(vk[i]==keyCode&&(location[i]==KeyEvent.KEY_LOCATION_UNKNOWN||location[i]==keyLocation))return true;
		return false;
	}
	/** The host key's packed form in a held-keys set. */
	static int held(int keyCode,int keyLocation){return keyCode<<3|keyLocation&7;}
	/** Shift, Command, Option and Control (event-record bits) while the {@code held} host keys are down. */
	int modifiers(Iterable<Integer> held,boolean mac){
		int m=0;
		for(int k:held){
			int code=k>>>3,l=k&7;boolean source=false;
			for(int i=0;i<3;i++)if(vk[i]==code&&(location[i]==KeyEvent.KEY_LOCATION_UNKNOWN||location[i]==l)){m|=BITS[i];source=true;}
			if(source)continue;
			switch(code){
				case KeyEvent.VK_SHIFT:m|=0x200;break;
				case KeyEvent.VK_META:if(vk[COMMAND]<0)m|=0x100;break;
				case KeyEvent.VK_ALT:if(vk[OPTION]<0)m|=0x800;break;
				case KeyEvent.VK_CONTROL:if(vk[CONTROL]<0)m|=0x1000;if(!mac&&vk[COMMAND]<0)m|=0x100;break;
				default:break;
			}
		}
		return m;
	}

	/** The key's name for the settings dialog: "Right Ctrl", "X", "Numpad Enter". */
	static String keyName(int keyCode,int keyLocation){
		String base=GMenu.isMacOSX()?GamepadHost.Binding.keyText(keyCode):KeyEvent.getKeyText(keyCode);
		int l=sided(keyLocation);
		if(l==KeyEvent.KEY_LOCATION_LEFT)return GPreferencesDialog.dialogText("Left")+" "+base;
		if(l==KeyEvent.KEY_LOCATION_RIGHT)return GPreferencesDialog.dialogText("Right")+" "+base;
		if(l==KeyEvent.KEY_LOCATION_NUMPAD&&!base.toLowerCase(java.util.Locale.ROOT).contains("num"))return GPreferencesDialog.dialogText("Numpad")+" "+base;
		return base;
	}
	/** The default host key of a Mac modifier on this platform, for "follow the default" entries. */
	static String defaultKeyName(int modifier){
		boolean mac=GMenu.isMacOSX();
		switch(modifier){
			case COMMAND:return mac?GamepadHost.Binding.keyText(KeyEvent.VK_META):KeyEvent.getKeyText(KeyEvent.VK_CONTROL);
			case OPTION:return mac?GamepadHost.Binding.keyText(KeyEvent.VK_ALT):KeyEvent.getKeyText(KeyEvent.VK_ALT);
			default:return mac?GamepadHost.Binding.keyText(KeyEvent.VK_CONTROL):KeyEvent.getKeyText(KeyEvent.VK_CONTROL);
		}
	}
}
