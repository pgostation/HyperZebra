/**
 * The Cursor Device Manager: _CursorDeviceDispatch $AADB, selector in D0, Pascal arguments on the stack, OSErr result
 * (CursorDevices.h; Black Shades creates a device with CursorDeviceNewDevice and feeds it mouse deltas). The VM has one
 * built-in three-button mouse device on the main cursor; programs may add their own records. Moves only update the
 * CursorData records (the host pointer is driven by the real mouse), button calls keep the record's button state.
 */
final class CursorDevices68k {
	private final Toolbox68k tb;
	private final M68kMemory memory;
	/** Head of the device list (the built-in mouse first) and the shared cursor data. */
	private int firstDevice,cursorData;

	static final int NO_ERR=0,CURSOR_DEVICE_NOT_FOUND=-1,CURSOR_DEVICE_CURSOR_NOT_FOUND=-2,CURSOR_DEVICE_TOO_MANY_BUTTONS=-3,CURSOR_DEVICE_ALREADY_REGISTERED=-4,CURSOR_DEVICE_NOT_REGISTERED=-5,CURSOR_DEVICE_NO_MEMORY=-6;
	private static final int DEVICE_SIZE=138,DATA_SIZE=70;

	CursorDevices68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	private void ensure() throws M68kCpuException {
		if(firstDevice!=0)return;
		cursorData=memory.allocate(DATA_SIZE,4);memory.fill(cursorData,DATA_SIZE,0);
		memory.writeLong(cursorData+22,72); // screenRes
		firstDevice=newRecord(1,3,0x00c80000);
	}

	private int newRecord(int deviceClass,int buttons,int resolution) throws M68kCpuException {
		int d=memory.allocate(DEVICE_SIZE,4);memory.fill(d,DEVICE_SIZE,0);
		memory.writeLong(d+4,cursorData);                 // whichCursor
		memory.writeLong(d+20,resolution);                // resolution (units/inch)
		memory.writeByte(d+24,deviceClass);memory.writeByte(d+25,buttons);
		for(int i=0;i<8;i++)memory.writeByte(d+28+i,i<buttons?1:0); // kButtonSingleClick
		memory.writeLong(d+100,30);                       // doubleClickTime
		memory.writeLong(d+104,0x00010000);               // acceleration 1.0
		return d;
	}

	private boolean known(int device){
		for(int d=firstDevice,n=0;d!=0&&n<1000;n++){if(d==device)return true;try{d=memory.readLong(d);}catch(M68kCpuException e){return false;}}
		return false;
	}

	boolean dispatch(M68kCpu cpu) throws M68kException {
		ensure();
		int selector=cpu.d[0]&0xffff,err=NO_ERR;
		int sp=cpu.a[7];
		switch(selector){
			case 0:case 1:{ // Move / MoveTo (device, Fixed x, Fixed y)
				int dev=memory.readLong(sp+8),x=memory.readLong(sp+4),y=memory.readLong(sp);
				if(!known(dev)){err=CURSOR_DEVICE_NOT_FOUND;break;}
				int data=memory.readLong(dev+4);
				if(dev==firstDevice){ // the built-in mouse: CursorDeviceMove/MoveTo take longs (screen pixels); the pointer really moves, so a game that re-centres it every frame (Dark Forces) reads only what the real mouse moved since
					int h,v;if(selector==1){h=x;v=y;}else{java.awt.Point now=tb.hosted()?tb.host().mouseLocation():new java.awt.Point();h=now.x+x;v=now.y+y;}
					tb.warpPointer((short)h,(short)v);
					if(data!=0){memory.writeLong(data+8,h<<16);memory.writeLong(data+12,v<<16);memory.writeWord(data+16,v);memory.writeWord(data+18,h);memory.writeByte(data+20,selector==1?1:0);}
					sp+=12;break;
				}
				if(data!=0){
					if(selector==1){memory.writeLong(data+8,x);memory.writeLong(data+12,y);}
					else{memory.writeLong(data+8,memory.readLong(data+8)+x);memory.writeLong(data+12,memory.readLong(data+12)+y);}
					memory.writeWord(data+16,memory.readLong(data+12)>>16);memory.writeWord(data+18,memory.readLong(data+8)>>16); // where (v,h)
					memory.writeByte(data+20,selector==1?1:0);
				}
				sp+=12;break;
			}
			case 2:{ // Flush(device)
				int dev=memory.readLong(sp);if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;sp+=4;break;
			}
			case 3:{ // Buttons(device, short buttons)
				int dev=memory.readLong(sp+2),b=memory.readWord(sp)&0xff;
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;else memory.writeByte(dev+27,b);
				sp+=6;break;
			}
			case 4:case 5:{ // ButtonDown / ButtonUp(device)
				int dev=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;
				sp+=4;break;
			}
			case 6:{ // ButtonOp(device, short button, ButtonOpcode op, long data)
				int dev=memory.readLong(sp+8),button=memory.readWord(sp+6)&0xffff,op=memory.readWord(sp+4)&0xff,data=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;
				else if(button>=8)err=CURSOR_DEVICE_TOO_MANY_BUTTONS;
				else{memory.writeByte(dev+28+button,op);memory.writeLong(dev+68+button*4,data);}
				sp+=12;break;
			}
			case 7:{ // SetButtons(device, short count)
				int dev=memory.readLong(sp+2),n=memory.readWord(sp)&0xff;
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;else if(n>8)err=CURSOR_DEVICE_TOO_MANY_BUTTONS;else memory.writeByte(dev+25,n);
				sp+=6;break;
			}
			case 8:{ // SetAcceleration(device, Fixed)
				int dev=memory.readLong(sp+4),a=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;else memory.writeLong(dev+104,a);
				sp+=8;break;
			}
			case 9:{ // DoubleTime(device, long ticks)
				int dev=memory.readLong(sp+4),t=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;else memory.writeLong(dev+100,t);
				sp+=8;break;
			}
			case 10:{ // UnitsPerInch(device, Fixed)
				int dev=memory.readLong(sp+4),u=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;else memory.writeLong(dev+20,u);
				sp+=8;break;
			}
			case 11:{ // NextDevice(CursorDevicePtr *device): NULL gives the first one, the last one gives NULL
				int pp=memory.readLong(sp),cur=memory.readLong(pp);
				if(cur==0)memory.writeLong(pp,firstDevice);
				else if(!known(cur)){err=CURSOR_DEVICE_NOT_FOUND;memory.writeLong(pp,0);}
				else memory.writeLong(pp,memory.readLong(cur));
				sp+=4;break;
			}
			case 12:{ // NewDevice(CursorDevicePtr *device)
				int pp=memory.readLong(sp);
				int d=newRecord(1,1,0x00c80000);
				int last=firstDevice;for(int n=0;memory.readLong(last)!=0&&n<1000;n++)last=memory.readLong(last);
				memory.writeLong(last,d);memory.writeLong(pp,d);
				sp+=4;break;
			}
			case 13:{ // DisposeDevice(device): the built-in mouse stays
				int dev=memory.readLong(sp);
				if(!known(dev))err=CURSOR_DEVICE_NOT_FOUND;
				else if(dev!=firstDevice){for(int p=firstDevice;p!=0;p=memory.readLong(p))if(memory.readLong(p)==dev){memory.writeLong(p,memory.readLong(dev));break;}}
				sp+=4;break;
			}
			default:err=CURSOR_DEVICE_NOT_FOUND;break;
		}
		cpu.a[7]=sp;
		memory.writeWord(cpu.a[7],err);
		return true;
	}
}
