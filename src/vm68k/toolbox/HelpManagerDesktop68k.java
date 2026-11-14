import java.util.ArrayList;
/** Balloon Help on the desktop: the parts of HelpManager68k that need Swing and the host's fonts (styled text drawn in its fonts, the balloon taken down when the pointer leaves its hot rectangle). */
class HelpManagerDesktop68k extends HelpManager68k {
	HelpManagerDesktop68k(Toolbox68k tb){super(tb);}
	/**
	 * HMShowBalloon's alternateRect is the balloon's hot rectangle (global): the Help Manager takes the balloon down once the
	 * pointer leaves it. XTernal Collection's BalloonHelp (from mouseEnter handlers) left every balloon up for good.
	 */
	@Override void removeWhenLeft(int[] hotRect){
		stopTracking();if(java.awt.GraphicsEnvironment.isHeadless())return;
		final VmHost host=tb.host();
		javax.swing.Timer timer=new javax.swing.Timer(150,null);
		timer.addActionListener(e->{
			if(!balloonUp){timer.stop();return;}
			java.awt.PointerInfo info=java.awt.MouseInfo.getPointerInfo();if(info==null)return;
			java.awt.Point p=host.toGuest(info.getLocation());
			if(p.y<hotRect[0]||p.x<hotRect[1]||p.y>=hotRect[2]||p.x>=hotRect[3]){timer.stop();if(balloonUp){balloonUp=false;host.hideBalloon();}}
		});
		tracking=timer;timer.start();
	}
	@Override void stopTracking(){javax.swing.Timer t=tracking;tracking=null;if(t!=null)t.stop();}
	private volatile javax.swing.Timer tracking;
	/** The width a balloon's text is wrapped to. */
	private static final int BALLOON_TEXT_WIDTH=200;
	/**
	 * Styled text as the balloon shows it: the bytes in the fonts, sizes, faces and colours of a StScrpRec (scrpNStyles, then
	 * 20-byte ScrpSTElements: startChar, height, ascent, font, face, filler, size, RGB colour), wrapped at the balloon's width.
	 */
	@Override java.awt.image.BufferedImage styledText(byte[] bytes,byte[] scrap){
		int n=scrap.length<2?0:MacApp68k.u2(scrap,0);
		java.util.List<String> parts=new ArrayList<String>();java.util.List<int[]> styles=new ArrayList<int[]>();
		for(int i=0;i<Math.max(1,n);i++){
			int o=2+i*20;boolean has=i<n&&o+20<=scrap.length;
			int start=has?Math.min(bytes.length,MacApp68k.u4(scrap,o)):0,end=i+1<n&&o+40<=scrap.length?Math.min(bytes.length,MacApp68k.u4(scrap,o+20)):bytes.length;
			if(end<=start)continue;
			parts.add(tb.decode(java.util.Arrays.copyOfRange(bytes,start,end)));
			styles.add(has?new int[]{(short)MacApp68k.u2(scrap,o+8),scrap[o+10]&0x7f,(short)MacApp68k.u2(scrap,o+12),MacApp68k.u2(scrap,o+14),MacApp68k.u2(scrap,o+16),MacApp68k.u2(scrap,o+18)}:new int[]{0,0,12,0,0,0});
		}
		StringBuilder all=new StringBuilder();for(String p:parts)all.append(p);
		String text=all.toString().replace('\n','\r');
		if(text.isEmpty())text=" ";
		java.util.List<java.text.AttributedString> lines=new ArrayList<java.text.AttributedString>();
		java.util.List<int[]> lineRanges=new ArrayList<int[]>();
		for(int a=0;a<=text.length();){int b=text.indexOf('\r',a);if(b<0)b=text.length();lineRanges.add(new int[]{a,b});a=b+1;if(b==text.length())break;}
		int[] runStart=new int[parts.size()+1];for(int i=0;i<parts.size();i++)runStart[i+1]=runStart[i]+parts.get(i).length();
		java.awt.image.BufferedImage scratch=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D sg=scratch.createGraphics();java.awt.font.FontRenderContext frc=sg.getFontRenderContext();sg.dispose();
		java.util.List<java.awt.font.TextLayout> layouts=new ArrayList<java.awt.font.TextLayout>();java.util.List<Integer> blank=new ArrayList<Integer>();
		int height=0,width=0;
		for(int[] lr:lineRanges){
			if(lr[1]==lr[0]){int[] st=styles.isEmpty()?new int[]{0,0,12}:styles.get(Math.max(0,runAt(runStart,lr[0])));int h=Fonts68k.font(st[0],st[2],st[1]).getSize()+4;layouts.add(null);blank.add(h);height+=h;continue;}
			java.text.AttributedString as=new java.text.AttributedString(text.substring(lr[0],lr[1]));
			for(int i=0;i<parts.size();i++){int a=Math.max(runStart[i],lr[0]),b=Math.min(runStart[i+1],lr[1]);if(b<=a)continue;int[] st=styles.get(i);
				as.addAttribute(java.awt.font.TextAttribute.FONT,Fonts68k.font(st[0],st[2],st[1]),a-lr[0],b-lr[0]);
				as.addAttribute(java.awt.font.TextAttribute.FOREGROUND,new java.awt.Color(st[3]>>8,st[4]>>8,st[5]>>8),a-lr[0],b-lr[0]);
				if((st[1]&4)!=0)as.addAttribute(java.awt.font.TextAttribute.UNDERLINE,java.awt.font.TextAttribute.UNDERLINE_ON,a-lr[0],b-lr[0]);}
			java.awt.font.LineBreakMeasurer m=new java.awt.font.LineBreakMeasurer(as.getIterator(),frc);
			while(m.getPosition()<lr[1]-lr[0]){java.awt.font.TextLayout l=m.nextLayout(BALLOON_TEXT_WIDTH);layouts.add(l);blank.add(0);
				height+=(int)Math.ceil(l.getAscent()+l.getDescent()+l.getLeading());width=Math.max(width,(int)Math.ceil(l.getAdvance()));}
		}
		java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(Math.max(1,width),Math.max(1,height),java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g=image.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		float y=0;for(int i=0;i<layouts.size();i++){java.awt.font.TextLayout l=layouts.get(i);if(l==null){y+=blank.get(i);continue;}y+=l.getAscent();l.draw(g,0,y);y+=l.getDescent()+l.getLeading();}
		g.dispose();
		return image;
	}
	private static int runAt(int[] runStart,int at){for(int i=0;i+1<runStart.length;i++)if(at<runStart[i+1])return i;return runStart.length-2;}
}
