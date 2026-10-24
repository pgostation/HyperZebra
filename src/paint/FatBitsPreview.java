import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.WeakHashMap;

import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

//FatBitsと同時に表示する実寸ビュー。拡大側を編集しながら、同じ画像を
//補間なしの100%で確認できる。ダイアログを閉じてもFatBits自体は継続する。
class FatBitsPreview {
	private static final Map<PCARDFrame,FatBitsPreview> previews=new WeakHashMap<PCARDFrame,FatBitsPreview>();
	final PCARDFrame owner;
	final JDialog dialog;
	final PreviewPanel panel;
	final JScrollPane scroll;

	private FatBitsPreview(final PCARDFrame owner){
		this.owner=owner;
		dialog=new JDialog(owner,PCARD.pc==null ? "100% Preview" : PCARD.pc.intl.getText("100% Preview"),false);
		panel=new PreviewPanel();
		BufferedImage image=owner.getSurface();
		panel.setPreferredSize(new Dimension(image.getWidth(),image.getHeight()));
		scroll=new JScrollPane(panel);
		dialog.add(scroll,BorderLayout.CENTER);
		dialog.setSize(240,180);
		dialog.setLocation(owner.getX()+Math.max(0,owner.getWidth()-250),owner.getY()+40);
		dialog.addWindowListener(new WindowAdapter(){
			@Override public void windowClosed(WindowEvent e){ previews.remove(owner); }
		});
	}

	static void show(final PCARDFrame owner){
		if(owner==null || java.awt.GraphicsEnvironment.isHeadless()) return;
		if(!SwingUtilities.isEventDispatchThread()){
			SwingUtilities.invokeLater(()->show(owner)); return;
		}
		if(!owner.isShowing()) return; //テストの見えないウインドウには出さない
		FatBitsPreview p=previews.get(owner);
		if(p==null){ p=new FatBitsPreview(owner); previews.put(owner,p); }
		p.sync(); p.dialog.setVisible(true); p.dialog.toFront();
	}

	static void hide(final PCARDFrame owner){
		if(owner==null) return;
		if(!SwingUtilities.isEventDispatchThread()){
			SwingUtilities.invokeLater(()->hide(owner)); return;
		}
		FatBitsPreview p=previews.remove(owner);
		if(p!=null) p.dialog.dispose();
	}

	static void repaintFor(final PCARDFrame owner){
		FatBitsPreview p=previews.get(owner);
		if(p!=null){ p.sync(); p.panel.repaint(); }
	}

	void sync(){
		BufferedImage image=owner.getSurface();
		if(image==null) return;
		Dimension wanted=new Dimension(image.getWidth(),image.getHeight());
		if(!wanted.equals(panel.getPreferredSize())){
			panel.setPreferredSize(wanted); panel.revalidate();
		}
		Point wantedPoint=new Point(Math.max(0,(int)owner.bitLeft),Math.max(0,(int)owner.bitTop));
		if(!scroll.getViewport().getViewPosition().equals(wantedPoint)) scroll.getViewport().setViewPosition(wantedPoint);
	}

	class PreviewPanel extends JPanel{
		private static final long serialVersionUID=1L;
		@Override protected void paintComponent(Graphics g){
			super.paintComponent(g);
			paintPreview(owner,g);
		}
	}

	//実寸の絵: バックグラウンド、サーフェース、浮動選択、ストローク中のプレビューの順(テストからも呼ぶ)
	static void paintPreview(PCARDFrame owner, Graphics g){
		{
			if(!owner.isEditingBackground() && owner.bgImg!=null) g.drawImage(owner.bgImg,0,0,null);
			BufferedImage surface=owner.getSurface();
			//浮動選択(持ち上げた絵)も重ねる。サーフェースからは消えているので、描かないと
			//選択した範囲が透明な穴に見えてしまう。合成モード/不透明度は拡大側(MyPanel)と同じ結果を出す
			FloatingSelection f=owner.floating;
			BufferedImage flow=null; Point offset=null; BufferedImage surfaceToDraw=surface;
			if(f!=null && f.image!=null && surface!=null){
				offset=f.position();
				flow=f.image;
				if(owner.blendMode!=MyPanel.blendMode.Copy || owner.blendLevel!=100){
					BufferedImage blended=new BufferedImage(flow.getWidth(),flow.getHeight(),BufferedImage.TYPE_INT_ARGB);
					if(owner.blendMode==MyPanel.blendMode.AlphaChannel){
						//AlphaChannelモードはサーフェース自身のアルファを書き換えるので、複製に対して行い、その複製を見せる
						BufferedImage copy=new BufferedImage(surface.getWidth(),surface.getHeight(),BufferedImage.TYPE_INT_ARGB);
						Graphics2D cg=copy.createGraphics(); cg.setComposite(AlphaComposite.Src); cg.drawImage(surface,0,0,null); cg.dispose();
						MyPanel.makeBlendImage(blended,flow,copy,offset,owner.blendMode,owner.blendLevel);
						surfaceToDraw=copy;
					}
					else MyPanel.makeBlendImage(blended,flow,surface,offset,owner.blendMode,owner.blendLevel);
					flow=blended;
				}
			}
			if(surfaceToDraw!=null) g.drawImage(surfaceToDraw,0,0,null);
			if(flow!=null){
				Graphics2D g2=(Graphics2D)g.create();
				if(owner.selectaf!=null) g2.transform(owner.selectaf);
				g2.drawImage(flow,offset.x,offset.y,null);
				g2.dispose();
			}
			if(owner.previewVisible && owner.previewBuf!=null){
				Graphics2D g2=(Graphics2D)g.create();
				g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
					Math.max(0f,Math.min(1f,owner.previewAlpha))));
				g2.drawImage(owner.previewBuf,0,0,null); g2.dispose();
			}
		}
	}
}
