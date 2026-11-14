import java.awt.event.KeyEvent;

/** Java key codes as Mac virtual key codes (P4: out of MacAppHost; the KeyEvent constants are numbers in the class file). */
final class KeyCodes68k {
	private KeyCodes68k() {}
	static int macKeyCode(int vk,int location){
		switch(vk){
			case KeyEvent.VK_A:return 0;case KeyEvent.VK_S:return 1;case KeyEvent.VK_D:return 2;case KeyEvent.VK_F:return 3;case KeyEvent.VK_H:return 4;case KeyEvent.VK_G:return 5;
			case KeyEvent.VK_Z:return 6;case KeyEvent.VK_X:return 7;case KeyEvent.VK_C:return 8;case KeyEvent.VK_V:return 9;case KeyEvent.VK_B:return 11;case KeyEvent.VK_Q:return 12;
			case KeyEvent.VK_W:return 13;case KeyEvent.VK_E:return 14;case KeyEvent.VK_R:return 15;case KeyEvent.VK_Y:return 16;case KeyEvent.VK_T:return 17;
			case KeyEvent.VK_1:return 18;case KeyEvent.VK_2:return 19;case KeyEvent.VK_3:return 20;case KeyEvent.VK_4:return 21;case KeyEvent.VK_6:return 22;case KeyEvent.VK_5:return 23;
			case KeyEvent.VK_EQUALS:return 24;case KeyEvent.VK_9:return 25;case KeyEvent.VK_7:return 26;case KeyEvent.VK_MINUS:return 27;case KeyEvent.VK_8:return 28;case KeyEvent.VK_0:return 29;
			case KeyEvent.VK_CLOSE_BRACKET:return 30;case KeyEvent.VK_O:return 31;case KeyEvent.VK_U:return 32;case KeyEvent.VK_OPEN_BRACKET:return 33;case KeyEvent.VK_I:return 34;case KeyEvent.VK_P:return 35;
			case KeyEvent.VK_ENTER:return location==KeyEvent.KEY_LOCATION_NUMPAD?76:36;case KeyEvent.VK_L:return 37;case KeyEvent.VK_J:return 38;case KeyEvent.VK_QUOTE:return 39;case KeyEvent.VK_K:return 40;
			case KeyEvent.VK_SEMICOLON:return 41;case KeyEvent.VK_BACK_SLASH:return 42;case KeyEvent.VK_COMMA:return 43;case KeyEvent.VK_SLASH:return 44;case KeyEvent.VK_N:return 45;case KeyEvent.VK_M:return 46;
			case KeyEvent.VK_PERIOD:return 47;case KeyEvent.VK_TAB:return 48;case KeyEvent.VK_SPACE:return 49;case KeyEvent.VK_BACK_QUOTE:return 50;case KeyEvent.VK_BACK_SPACE:return 51;
			case KeyEvent.VK_ESCAPE:return 53;case KeyEvent.VK_META:return 55;case KeyEvent.VK_SHIFT:return 56;case KeyEvent.VK_CAPS_LOCK:return 57;case KeyEvent.VK_ALT:return 58;case KeyEvent.VK_CONTROL:return 59;
			case KeyEvent.VK_DECIMAL:return 65;case KeyEvent.VK_MULTIPLY:return 67;case KeyEvent.VK_ADD:return 69;case KeyEvent.VK_CLEAR:return 71;case KeyEvent.VK_DIVIDE:return 75;case KeyEvent.VK_SUBTRACT:return 78;
			case KeyEvent.VK_NUMPAD0:return 82;case KeyEvent.VK_NUMPAD1:return 83;case KeyEvent.VK_NUMPAD2:return 84;case KeyEvent.VK_NUMPAD3:return 85;case KeyEvent.VK_NUMPAD4:return 86;
			case KeyEvent.VK_NUMPAD5:return 87;case KeyEvent.VK_NUMPAD6:return 88;case KeyEvent.VK_NUMPAD7:return 89;case KeyEvent.VK_NUMPAD8:return 91;case KeyEvent.VK_NUMPAD9:return 92;
			case KeyEvent.VK_F5:return 96;case KeyEvent.VK_F6:return 97;case KeyEvent.VK_F7:return 98;case KeyEvent.VK_F3:return 99;case KeyEvent.VK_F8:return 100;case KeyEvent.VK_F9:return 101;
			case KeyEvent.VK_F11:return 103;case KeyEvent.VK_F13:return 105;case KeyEvent.VK_F14:return 107;case KeyEvent.VK_F10:return 109;case KeyEvent.VK_F12:return 111;case KeyEvent.VK_F15:return 113;
			case KeyEvent.VK_HELP:case KeyEvent.VK_INSERT:return 114;case KeyEvent.VK_HOME:return 115;case KeyEvent.VK_PAGE_UP:return 116;case KeyEvent.VK_DELETE:return 117;case KeyEvent.VK_F4:return 118;
			case KeyEvent.VK_END:return 119;case KeyEvent.VK_F2:return 120;case KeyEvent.VK_PAGE_DOWN:return 121;case KeyEvent.VK_F1:return 122;
			case KeyEvent.VK_LEFT:return 123;case KeyEvent.VK_RIGHT:return 124;case KeyEvent.VK_DOWN:return 125;case KeyEvent.VK_UP:return 126;
			default:return -1;
		}
	}

	/** A browser's key code (DOM keyCode) as Java's VK number where the two differ (punctuation, Delete, Insert, Meta). */
	static int vkOfDom(int code){
		switch(code){
			case 46:return KeyEvent.VK_DELETE;case 45:return KeyEvent.VK_INSERT;case 91:case 92:case 93:case 224:return KeyEvent.VK_META;
			case 186:case 59:return KeyEvent.VK_SEMICOLON;case 187:case 61:return KeyEvent.VK_EQUALS;case 188:return KeyEvent.VK_COMMA;
			case 189:case 173:return KeyEvent.VK_MINUS;case 190:return KeyEvent.VK_PERIOD;case 191:return KeyEvent.VK_SLASH;
			case 219:return KeyEvent.VK_OPEN_BRACKET;case 220:return KeyEvent.VK_BACK_SLASH;case 221:return KeyEvent.VK_CLOSE_BRACKET;
			case 12:return KeyEvent.VK_CLEAR;
			// Return/Enter: the browser's 13, Java's VK_ENTER is 10 (the page sends no character for it: Return in Favorite
			// Story's UxAnswer dialog reached the external as no key at all)
			case 13:return KeyEvent.VK_ENTER;
			default:return code;
		}
	}
	/** The Mac key code of a browser key (location 1: the numeric keypad), -1 when there is none. */
	static int macKeyCodeOfDom(int code,int location){return macKeyCode(vkOfDom(code),location==1?KeyEvent.KEY_LOCATION_NUMPAD:KeyEvent.KEY_LOCATION_STANDARD);}
	/** The Mac character a key types (as MacAppHost.macChar: the special keys' codes, then the character if it is one byte), -1 for none. */
	static int macCharOfDom(int code,int location,int ch){
		switch(vkOfDom(code)){
			case KeyEvent.VK_ENTER:return location==1?3:13;
			case KeyEvent.VK_TAB:return 9;case KeyEvent.VK_BACK_SPACE:return 8;case KeyEvent.VK_DELETE:return 0x7f;case KeyEvent.VK_ESCAPE:return 0x1b;
			case KeyEvent.VK_LEFT:return 0x1c;case KeyEvent.VK_RIGHT:return 0x1d;case KeyEvent.VK_UP:return 0x1e;case KeyEvent.VK_DOWN:return 0x1f;
			case KeyEvent.VK_HOME:return 1;case KeyEvent.VK_END:return 4;case KeyEvent.VK_PAGE_UP:return 0x0b;case KeyEvent.VK_PAGE_DOWN:return 0x0c;case KeyEvent.VK_HELP:case KeyEvent.VK_INSERT:return 5;
			case KeyEvent.VK_CLEAR:return 0x1b;case KeyEvent.VK_SHIFT:case KeyEvent.VK_CONTROL:case KeyEvent.VK_ALT:case KeyEvent.VK_META:case KeyEvent.VK_CAPS_LOCK:return -1;
			default:break;
		}
		int vk=vkOfDom(code);
		if(vk>=KeyEvent.VK_F1&&vk<=KeyEvent.VK_F12)return 0x10;
		return ch>0&&ch<0x80?ch:-1;
	}
	/** The player's modifier bits (PlayerHost SHIFT 1, OPTION 2, COMMAND 4, CONTROL 8, CAPS_LOCK 16) as an event's modifiers word, the button up. */
	static int macModifiers(int player,boolean buttonDown){
		int m=buttonDown?0:0x80;
		if((player&4)!=0)m|=0x100;if((player&1)!=0)m|=0x200;if((player&16)!=0)m|=0x400;if((player&2)!=0)m|=0x800;if((player&8)!=0)m|=0x1000;
		return m;
	}
}
