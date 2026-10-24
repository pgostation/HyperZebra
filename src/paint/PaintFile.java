import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

//ペイントの読み込み/書き出し(FileメニューのImport Paint…/Export Paint…と、
//HyperTalkのimport paint from file / export paint to file)。
//PNG/JPEG/GIF/BMP(ImageIO)と、読み込みだけPICT(PictureFile.loadPICT)。
//JPEGには透明が無いので白の上に合成して書く。
class PaintFile {
	static final FileNameExtensionFilter IMAGE_FILTER =
		new FileNameExtensionFilter("Picture (png, jpeg, gif, bmp, pict)", "png", "jpg", "jpeg", "gif", "bmp", "pict", "pct");
	static final FileNameExtensionFilter PNG_FILTER = new FileNameExtensionFilter("PNG", "png");
	static final FileNameExtensionFilter JPEG_FILTER = new FileNameExtensionFilter("JPEG", "jpg", "jpeg");

	//拡張子からImageIOの形式名(分からなければnull)
	static String formatOf(String path){
		String n = path.toLowerCase(Locale.ROOT);
		if(n.endsWith(".png")) return "png";
		if(n.endsWith(".jpg") || n.endsWith(".jpeg")) return "jpeg";
		if(n.endsWith(".gif")) return "gif";
		if(n.endsWith(".bmp")) return "bmp";
		return null;
	}

	//画像をファイルへ書く。拡張子が無ければ.pngを付ける。透明を持てない形式は白の上に合成する。
	static File write(BufferedImage img, File file) throws IOException {
		String format = formatOf(file.getName());
		if(format==null){
			file = new File(file.getParentFile(), file.getName()+".png");
			format = "png";
		}
		BufferedImage out = img;
		if(!format.equals("png") && !format.equals("gif")){
			out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = out.createGraphics();
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, out.getWidth(), out.getHeight());
			g.drawImage(img, 0, 0, null);
			g.dispose();
		}
		if(!ImageIO.write(out, format, file)) throw new IOException("unsupported format: "+format);
		return file;
	}

	//画像ファイルを読む(ImageIOで読めなければPICTとして試す)。読めなければnull。
	static BufferedImage read(File file) throws IOException {
		BufferedImage img = null;
		try{
			img = ImageIO.read(file);
		}catch(IOException e){
			//PICTかもしれない
		}
		if(img==null) img = PictureFile.loadPICT(file.getPath());
		return img;
	}

	//書き出す絵: 今のサーフェース(カード/バックグラウンドのピクチャ)に、浮いている
	//選択範囲があればそれを重ねたもの。
	static BufferedImage exportImage(PCARDFrame frame){
		BufferedImage s = frame.getSurface();
		if(s==null) return null;
		BufferedImage out = PaintUndo.copyInto(null, s);
		FloatingSelection f = frame.floating;
		if(f!=null){
			Graphics2D g = out.createGraphics();
			g.drawImage(MyPanel.makeBlendImage(f.image), f.x, f.y, null);
			g.dispose();
		}
		return out;
	}

	//Export Paint…: 保存先を聞いて書き出す
	static void exportDialog(PCARDFrame frame){
		BufferedImage img = exportImage(frame);
		if(img==null) return;
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Export Paint…").replace("…",""));
		chooser.setDialogType(JFileChooser.SAVE_DIALOG);
		chooser.addChoosableFileFilter(PNG_FILTER);
		chooser.addChoosableFileFilter(JPEG_FILTER);
		chooser.setFileFilter(PNG_FILTER);
		String name = "Paint";
		if(frame==PCARD.pc && PCARD.pc.stack!=null && PCARD.pc.stack.name!=null){
			name = PCARD.pc.stack.name.replaceAll("\\.(xstk|hzb)$", "");
		}
		chooser.setSelectedFile(new File(name+".png"));
		if(PCARD.showFileChooser(chooser, frame, true)!=JFileChooser.APPROVE_OPTION) return;
		File f = chooser.getSelectedFile();
		if(formatOf(f.getName())==null){
			f = new File(f.getParentFile(), f.getName()+(chooser.getFileFilter()==JPEG_FILTER ? ".jpg" : ".png"));
		}
		try{
			write(img, f);
		}catch(IOException ex){
			ex.printStackTrace();
			javax.swing.JOptionPane.showMessageDialog(frame, ex.getMessage());
		}
	}

	//Import Paint…: 画像を選んで浮動選択として置く(HyperCardと同じく、読み込んだ
	//絵は選択範囲になり、ドラッグで位置を決めてから確定する)
	static void importDialog(PCARDFrame frame){
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(PCARD.pc.intl.getText("Import Paint…").replace("…",""));
		chooser.setFileFilter(IMAGE_FILTER);
		if(PCARD.showFileChooser(chooser, frame, false)!=JFileChooser.APPROVE_OPTION) return;
		File f = chooser.getSelectedFile();
		BufferedImage img = null;
		try{
			img = read(f);
		}catch(IOException ex){
			ex.printStackTrace();
		}
		if(img==null){
			javax.swing.JOptionPane.showMessageDialog(frame, PCARD.pc.intl.getDialogText("Not a picture file"));
			return;
		}
		PaintTool.owner = frame;
		PaintTool.pasteAsSelection(img);
	}
}
