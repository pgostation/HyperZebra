import java.util.HashMap;
import java.util.Map;

/**
 * The Standard Image Compression Dialog component ('scdi'/'imag'), natively, for the native Image Compression Manager: an
 * external sees QuickTime 4.0.3 (ImageCompression68k.EXTERNAL_ICM_VERSION) and expects it. Caliban's CompressPict does
 * OpenDefaultComponent('scdi','imag'), SCSetTestImagePictHandle, SCRequestImageSettings and SCCompressPicture; with no
 * such component it answered "Error: OS error #-43" (its stale error variable) and never compressed.
 * The settings (SCSpatialSettings, SCTemporalSettings, SCDataRateSettings ...) are kept per instance and read and written by
 * SCGetInfo/SCSetInfo as QuickTime does; SCRequestImageSettings shows a small host dialog (compressor and quality; none when
 * headless: the settings stay as they are, as if OK were clicked). The compression is {@link PictureCompression68k}'s.
 * With the real QuickTime extension installed its own 'scdi' serves instead (this one is registered only without it).
 */
final class StandardCompression68k implements Components68k.Native {
	static final int SCDI=0x73636469,IMAG=0x696d6167,APPL=0x6170706c;
	static final int SPTL=0x7370746c,TPRL=0x7470726c,DRAT=0x64726174,CLUT=0x636c7574,PROG=0x70726f67,XPRC=0x78707263,PREF=0x70726566,
		SSTA=0x73737461,SEQU=0x73657175,WNDW=0x776e6477,CFLG=0x63666c67,CDEC=0x63646563,KSIM=0x6b73696d;
	static final int USER_CANCELLED=1,TYPE_NOT_FOUND=-8971,BAD_SELECTOR=0x80008002,PARAM_ERR=-50;
	static final int SMC=0x736d6320,RLE=0x726c6520;
	/** The compressors the dialog offers (all of them PictureCompression68k can write), by their QuickTime names. */
	static final int[] CODECS={PictureCompression68k.JPEG,PictureCompression68k.PNG,SMC,RLE,PictureCompression68k.RAW};
	static final String[] CODEC_NAMES={"Photo - JPEG","PNG","Graphics","Animation","None"};

	/** One instance's settings: SCSpatialSettings, SCTemporalSettings, SCDataRateSettings and the rest SCSetInfo stores. */
	static final class Settings {
		int codecType=PictureCompression68k.JPEG,codec,depth,spatialQuality=PictureCompression68k.CODEC_NORMAL_QUALITY;
		int temporalQuality,frameRate,keyFrameRate;
		int dataRate,frameDuration,minSpatialQuality,minTemporalQuality;
		int colorTable,preferenceFlags,compressFlags,windowPosition=-1,codecFlags,codecSettings,forceKey;
		int[] progress=new int[2],extendedProcs=new int[3];
	}
	private final Map<Integer,Settings> instances=new HashMap<Integer,Settings>();
	private final Toolbox68k tb;
	private final M68kMemory memory;
	StandardCompression68k(Toolbox68k tb){this.tb=tb;memory=tb.memory;}

	static void register(Components68k components,Toolbox68k tb){components.registerNative(SCDI,IMAG,APPL,0,new StandardCompression68k(tb));}

	Settings settings(int ci){return instances.computeIfAbsent(ci,k->new Settings());}
	@Override public void closed(int ci){instances.remove(ci);}

	/** {@code p} points at the arguments: the last declared one first (Pascal order), the instance above them. */
	@Override public int call(int ci,int what,int p) throws M68kException {
		Settings s=settings(ci);
		switch(what){
			case 0x01:{ // SCGetCompressionExtended(ci, SCParams *params, Point where, filterProc, refCon, customName, ...): the old SCParams form
				int size=memory.readByte(p-3),params=memory.readLong(p+size-4);if(params==0)return PARAM_ERR;
				s.codecType=memory.readLong(params+4);s.codec=memory.readLong(params+8);s.spatialQuality=memory.readLong(params+12);s.temporalQuality=memory.readLong(params+16);
				s.depth=(short)memory.readWord(params+20);s.frameRate=memory.readLong(params+22);s.keyFrameRate=memory.readLong(params+26);
				if(!request(s))return USER_CANCELLED;
				memory.writeLong(params+4,s.codecType);memory.writeLong(params+8,s.codec);memory.writeLong(params+12,s.spatialQuality);memory.writeLong(params+16,s.temporalQuality);
				memory.writeWord(params+20,s.depth);memory.writeLong(params+22,s.frameRate);memory.writeLong(params+26,s.keyFrameRate);return 0;}
			case 0x02:case 0x03:return 0; // SCPositionRect(ci, Rect*, Point*) / SCPositionDialog(ci, id, Point*): the host dialog places itself
			case 0x04:case 0x05:case 0x06:return 0; // SCSetTestImagePictHandle / PictFile / PixMap: the test image is only the dialog's preview
			case 0x07:{int r=memory.readLong(p);if(r==0)return PARAM_ERR;int[] b=mainDeviceRect(); // SCGetBestDeviceRect(ci, Rect*)
				for(int i=0;i<4;i++)memory.writeWord(r+2*i,b[i]);return 0;}
			case 0x0a:case 0x0e:return request(s)?0:USER_CANCELLED; // SCRequestImageSettings / SCRequestSequenceSettings
			case 0x0b:return compressImage(s,memory.readLong(p+12),memory.readLong(p+8),memory.readLong(p+4),memory.readLong(p)); // SCCompressImage(ci, src, srcRect, ImageDescriptionHandle*, Handle*)
			case 0x0c:{int src=memory.readLong(p+4),dst=memory.readLong(p); // SCCompressPicture(ci, srcPicture, dstPicture)
				byte[] picture=src==0?null:tb.api.handleContents(src);if(picture==null||dst==0)return PARAM_ERR;
				int[] err={0};byte[] result=PictureCompression68k.compress(tb,picture,s.spatialQuality,s.codecType,err);if(result==null)return err[0];
				tb.api.loadHandle(dst,result);return 0;}
			case 0x0d:{int src=(short)memory.readWord(p+2),dst=(short)memory.readWord(p); // SCCompressPictureFile(ci, srcRefNum, dstRefNum): the 512-byte header is kept
				byte[][] header={null};byte[] picture=PictureCompression68k.pictureOfFile(tb,src,header);
				if(picture==null)return tb.files.fileManager.referenceLength(src)<0?-51:PictureCompression68k.CODEC_ERR;
				int[] err={0};byte[] result=PictureCompression68k.compress(tb,picture,s.spatialQuality,s.codecType,err);if(result==null)return err[0];
				return PictureCompression68k.writePictureFile(tb,dst,header[0],result);}
			case 0x12:case 0x13:case 0x14:{Settings d=new Settings();s.codecType=d.codecType;s.codec=0;s.depth=0;s.spatialQuality=d.spatialQuality; // SCDefaultPictHandle/PictFile/PixMapSettings(ci, src, motion)
				if((short)memory.readWord(p)!=0){s.temporalQuality=PictureCompression68k.CODEC_NORMAL_QUALITY;s.frameRate=0;s.keyFrameRate=0;}return 0;}
			case 0x15:return getInfo(s,memory.readLong(p+4),memory.readLong(p)); // SCGetInfo(ci, infoType, void*)
			case 0x16:return setInfo(s,memory.readLong(p+4),memory.readLong(p)); // SCSetInfo(ci, infoType, void*)
			case 0x18:s.compressFlags=memory.readLong(p);return 0; // SCSetCompressFlags(ci, flags)
			case 0x19:{int out=memory.readLong(p);if(out!=0)memory.writeLong(out,s.compressFlags);return 0;} // SCGetCompressFlags(ci, long*)
			case 0x1a:{int out=memory.readLong(p);if(out==0)return PARAM_ERR; // SCGetSettingsAsText(ci, Handle*)
				memory.writeLong(out,tb.api.createHandle(describe(s).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));return 0;}
			default:return BAD_SELECTOR;
		}
	}

	private int getInfo(Settings s,int type,int info) throws M68kException {
		if(info==0)return PARAM_ERR;
		switch(type){
			case SPTL:memory.writeLong(info,s.codecType);memory.writeLong(info+4,s.codec);memory.writeWord(info+8,s.depth);memory.writeLong(info+10,s.spatialQuality);return 0;
			case TPRL:memory.writeLong(info,s.temporalQuality);memory.writeLong(info+4,s.frameRate);memory.writeLong(info+8,s.keyFrameRate);return 0;
			case DRAT:memory.writeLong(info,s.dataRate);memory.writeLong(info+4,s.frameDuration);memory.writeLong(info+8,s.minSpatialQuality);memory.writeLong(info+12,s.minTemporalQuality);return 0;
			case CLUT:memory.writeLong(info,s.colorTable==0?0:tb.api.createHandle(tb.api.handleContents(s.colorTable)));return 0; // a copy the caller disposes
			case PROG:memory.writeLong(info,s.progress[0]);memory.writeLong(info+4,s.progress[1]);return 0;
			case XPRC:for(int i=0;i<3;i++)memory.writeLong(info+4*i,s.extendedProcs[i]);return 0;
			case PREF:memory.writeLong(info,s.preferenceFlags);return 0;
			case SSTA:memory.writeLong(info,tb.api.createHandle(state(s)));return 0; // a new Handle the caller disposes
			case SEQU:memory.writeLong(info,0);return 0;
			case WNDW:memory.writeLong(info,s.windowPosition);return 0;
			case CFLG:memory.writeWord(info,s.codecFlags);return 0;
			case CDEC:memory.writeLong(info,s.codecSettings==0?0:tb.api.createHandle(tb.api.handleContents(s.codecSettings)));return 0;
			case KSIM:memory.writeLong(info,s.forceKey);return 0;
			default:return TYPE_NOT_FOUND;
		}
	}
	private int setInfo(Settings s,int type,int info) throws M68kException {
		if(info==0)return PARAM_ERR;
		switch(type){
			case SPTL:s.codecType=memory.readLong(info);s.codec=memory.readLong(info+4);s.depth=(short)memory.readWord(info+8);s.spatialQuality=memory.readLong(info+10);return 0;
			case TPRL:s.temporalQuality=memory.readLong(info);s.frameRate=memory.readLong(info+4);s.keyFrameRate=memory.readLong(info+8);return 0;
			case DRAT:s.dataRate=memory.readLong(info);s.frameDuration=memory.readLong(info+4);s.minSpatialQuality=memory.readLong(info+8);s.minTemporalQuality=memory.readLong(info+12);return 0;
			case CLUT:{int h=memory.readLong(info);s.colorTable=h==0?0:tb.api.createHandle(tb.api.handleContents(h));return 0;}
			case PROG:s.progress[0]=memory.readLong(info);s.progress[1]=memory.readLong(info+4);return 0;
			case XPRC:for(int i=0;i<3;i++)s.extendedProcs[i]=memory.readLong(info+4*i);return 0;
			case PREF:s.preferenceFlags=memory.readLong(info);return 0;
			case SSTA:{int h=memory.readLong(info);byte[] b=h==0?null:tb.api.handleContents(h);return b!=null&&restore(s,b)?0:PARAM_ERR;}
			case SEQU:return 0;
			case WNDW:s.windowPosition=memory.readLong(info);return 0;
			case CFLG:s.codecFlags=(short)memory.readWord(info);return 0;
			case CDEC:{int h=memory.readLong(info);s.codecSettings=h==0?0:tb.api.createHandle(tb.api.handleContents(h));return 0;}
			case KSIM:s.forceKey=memory.readLong(info);return 0;
			default:return TYPE_NOT_FOUND;
		}
	}
	/** The settings state ('ssta'): opaque to programs, which only hand it back to SCSetInfo (saved in their preferences). */
	private static final int STATE_MAGIC=0x687a7363; // 'hzsc'
	private static byte[] state(Settings s){
		java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(4+4*13);
		b.putInt(STATE_MAGIC).putInt(s.codecType).putInt(s.codec).putInt(s.depth).putInt(s.spatialQuality).putInt(s.temporalQuality).putInt(s.frameRate).putInt(s.keyFrameRate)
			.putInt(s.dataRate).putInt(s.frameDuration).putInt(s.minSpatialQuality).putInt(s.minTemporalQuality).putInt(s.preferenceFlags).putInt(s.codecFlags);
		return b.array();
	}
	private static boolean restore(Settings s,byte[] bytes){
		if(bytes.length<4+4*13)return false;java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes);if(b.getInt()!=STATE_MAGIC)return false;
		s.codecType=b.getInt();s.codec=b.getInt();s.depth=b.getInt();s.spatialQuality=b.getInt();s.temporalQuality=b.getInt();s.frameRate=b.getInt();s.keyFrameRate=b.getInt();
		s.dataRate=b.getInt();s.frameDuration=b.getInt();s.minSpatialQuality=b.getInt();s.minTemporalQuality=b.getInt();s.preferenceFlags=b.getInt();s.codecFlags=b.getInt();return true;
	}

	/** SCCompressImage: the pixels of {@code srcRect} of the PixMap compressed; new Handles for the ImageDescription and the data. */
	private int compressImage(Settings s,int src,int srcRect,int descOut,int dataOut) throws M68kException {
		int pixMap=src==0?0:memory.readLong(src);if(pixMap==0||srcRect==0||descOut==0||dataOut==0)return PARAM_ERR;
		int top=(short)memory.readWord(srcRect),left=(short)memory.readWord(srcRect+2),bottom=(short)memory.readWord(srcRect+4),right=(short)memory.readWord(srcRect+6);
		int w=right-left,h=bottom-top;if(w<=0||h<=0)return PARAM_ERR;
		int[] rgb=tb.api.quickDraw().readBitmapRgb(pixMap,top,left,bottom,right);byte[] data,description;
		if(ImageCodecs68k.supported(s.codecType)){data=ImageCodecs68k.encode(s.codecType,rgb,w,h,s.spatialQuality);description=data==null?null:ImageCodecs68k.description(s.codecType,w,h,s.spatialQuality,data.length);}
		else if(s.codecType==PictureCompression68k.PNG){java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,w,h,rgb,0,w);
			data=PictureCompression68k.encode(image,s.codecType,s.spatialQuality);description=data==null?null:PictureCompression68k.description(s.codecType,w,h,s.spatialQuality,data.length);}
		else return PictureCompression68k.NO_CODEC_ERR;
		if(data==null)return PictureCompression68k.CODEC_ERR;
		memory.writeLong(descOut,tb.api.createHandle(description));memory.writeLong(dataOut,tb.api.createHandle(data));return 0;
	}

	/** The main screen's gdRect, or 640x480. */
	private int[] mainDeviceRect() throws M68kCpuException {
		int device=memory.readLong(0x8a4),gd=device==0?0:memory.readLong(device);
		if(gd==0)return new int[]{0,0,480,640};
		int[] r=new int[4];for(int i=0;i<4;i++)r[i]=(short)memory.readWord(gd+34+2*i);
		return r[2]>r[0]&&r[3]>r[1]?r:new int[]{0,0,480,640};
	}

	static String describe(Settings s){
		int i=indexOf(s.codecType);String name=i<0?QuickTimeFile.typeName(s.codecType):CODEC_NAMES[i];
		return name+", "+Math.round(Math.min(s.spatialQuality,PictureCompression68k.CODEC_LOSSLESS_QUALITY)*100.0/PictureCompression68k.CODEC_LOSSLESS_QUALITY)+"%";
	}
	private static int indexOf(int codec){for(int i=0;i<CODECS.length;i++)if(CODECS[i]==codec)return i;return -1;}

	/** Tests and scripted runs: when set, SCRequestImageSettings answers with it (true = OK) instead of showing the dialog. */
	static volatile Boolean scriptedAnswer;
	/**
	 * The settings dialog: the compressor and its quality (CodecQ 0..codecLosslessQuality). False = Cancel. Headless (tests,
	 * the probe harness without a screen) it answers OK with the settings unchanged; the probe harness clicks the default OK.
	 */
	boolean request(Settings s){
		if(scriptedAnswer!=null)return scriptedAnswer;
		if(java.awt.GraphicsEnvironment.isHeadless())return true;
		javax.swing.JComboBox<String> codecs=new javax.swing.JComboBox<String>(CODEC_NAMES);int i=indexOf(s.codecType);codecs.setSelectedIndex(i<0?0:i);
		javax.swing.JSlider quality=new javax.swing.JSlider(0,100,(int)Math.round(Math.min(s.spatialQuality,PictureCompression68k.CODEC_LOSSLESS_QUALITY)*100.0/PictureCompression68k.CODEC_LOSSLESS_QUALITY));
		quality.setMajorTickSpacing(25);quality.setPaintTicks(true);
		javax.swing.JPanel panel=new javax.swing.JPanel(new java.awt.GridLayout(2,2,8,8));
		panel.add(new javax.swing.JLabel(International.t("Compressor:")));panel.add(codecs);
		panel.add(new javax.swing.JLabel(International.t("Quality:")));panel.add(quality);
		java.awt.Component parent=tb.app==null?PCARD.pc:null; // an external's dialog over the card window
		int answer=javax.swing.JOptionPane.showConfirmDialog(parent,panel,International.t("Compression Settings"),javax.swing.JOptionPane.OK_CANCEL_OPTION,javax.swing.JOptionPane.PLAIN_MESSAGE);
		if(answer!=javax.swing.JOptionPane.OK_OPTION)return false;
		s.codecType=CODECS[Math.max(0,codecs.getSelectedIndex())];s.codec=0;
		s.spatialQuality=(int)Math.round(quality.getValue()*PictureCompression68k.CODEC_LOSSLESS_QUALITY/100.0);
		return true;
	}
}
