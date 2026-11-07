import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.function.ToIntFunction;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.filechooser.FileFilter;

/**
 * The Edition Manager's dialogs on the host (EditionManager68k): the publisher's "where does the edition go" panel, the
 * subscriber's edition chooser and the section options. The file panels show the edition's preview beside the list as the
 * Mac's do: 'prvw', else the 'PICT' scaled into the box, else the start of the 'TEXT', or a note for sound. All run on the
 * host's event thread; nothing here touches the program's memory.
 */
final class EditionDialogs {
	private EditionDialogs(){}

	/** The 120x120 preview box. */
	static final class Preview extends JComponent {
		private static final int BOX=120;
		private final StackTextEncoding encoding;
		private BufferedImage image;private String text,note;
		Preview(StackTextEncoding encoding){this.encoding=encoding;setPreferredSize(new Dimension(BOX+16,BOX+16));setBorder(BorderFactory.createEmptyBorder(4,8,4,4));}
		/** Shows a format's data (PICT/prvw, TEXT, sound); nothing for anything else. */
		void show(int format,byte[] data){
			image=null;text=null;note=null;
			if(data!=null&&data.length>0){
				if(format==EditionManager68k.PRVW||format==EditionManager68k.PICT){
					// as the VM's DrawPicture draws it (text and all), else the host's decoder
					image=PictTranslation.played(data);if(image==null)image=PictureFile.loadPICT(data);}
				else if(format==EditionManager68k.TEXT)text=encoding.decode(java.util.Arrays.copyOf(data,Math.min(data.length,2048))).replace('\r','\n');
				else if(format==EditionManager68k.SND)note=International.t("Sound");
			}
			repaint();
		}
		/** Shows the preview of an edition file: what its 'prvw', 'PICT', 'TEXT' or 'snd ' format holds. */
		void showEdition(File f){
			EditionManager68k.Edition e=EditionManager68k.peek(f);
			if(e==null){show(0,null);return;}
			for(int t:new int[]{EditionManager68k.PRVW,EditionManager68k.PICT,EditionManager68k.TEXT,EditionManager68k.SND})if(e.formats.containsKey(t)){show(t,e.formats.get(t));return;}
			show(0,null);
		}
		@Override protected void paintComponent(Graphics g0){
			Graphics2D g=(Graphics2D)g0.create();
			try{
				Insets in=getInsets();int x=in.left,y=in.top;
				g.setColor(Color.WHITE);g.fillRect(x,y,BOX,BOX);g.setColor(Color.GRAY);g.drawRect(x,y,BOX-1,BOX-1);
				g.clipRect(x+1,y+1,BOX-2,BOX-2);
				if(image!=null){
					// scaled to fit, keeping its proportions, centred; small pictures stay their size
					double scale=Math.min(1.0,Math.min((BOX-4)/(double)image.getWidth(),(BOX-4)/(double)image.getHeight()));
					int w=Math.max(1,(int)Math.round(image.getWidth()*scale)),h=Math.max(1,(int)Math.round(image.getHeight()*scale));
					g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g.drawImage(image,x+(BOX-w)/2,y+(BOX-h)/2,w,h,null);
				}else if(text!=null){
					g.setColor(Color.BLACK);g.setFont(getFont().deriveFont(Font.PLAIN,9f));
					int line=g.getFontMetrics().getHeight(),ty=y+2+g.getFontMetrics().getAscent();
					for(String s:text.split("\n")){if(ty>y+BOX)break;g.drawString(s,x+3,ty);ty+=line;}
				}else if(note!=null){
					g.setColor(Color.DARK_GRAY);int w=g.getFontMetrics().stringWidth(note);g.drawString(note,x+(BOX-w)/2,y+BOX/2);
				}
			}finally{g.dispose();}
		}
	}

	private static FileFilter editionFilter(ToIntFunction<File> type,int mask){
		return new FileFilter(){
			@Override public boolean accept(File f){return f.isDirectory()||isOffered(f,type,mask);}
			@Override public String getDescription(){return International.t("Editions");}};
	}
	/** An edition with one of the formats asked for: its Finder type tells, an 'edtu' (or untyped) one is looked into. */
	private static boolean isOffered(File f,ToIntFunction<File> typeOf,int mask){
		if(!f.isFile())return false;
		int type=typeOf.applyAsInt(f);
		if(type==EditionManager68k.EDTP)return (mask&EditionManager68k.MASK_PICT)!=0;
		if(type==EditionManager68k.EDTT)return (mask&EditionManager68k.MASK_TEXT)!=0;
		if(type==EditionManager68k.EDTS)return (mask&EditionManager68k.MASK_SND)!=0;
		if(type!=EditionManager68k.EDTU&&type!=0)return false;
		EditionManager68k.Edition e=EditionManager68k.peek(f);
		if(e==null)return false;
		if(type==EditionManager68k.EDTU)return true;
		// an untyped file written here: offered when it holds a format asked for, or only formats of the program's own
		return !e.formats.isEmpty()&&((e.formatsMask()&mask)!=0||e.formatsMask()==0);
	}

	/** The publisher's panel: where the new edition goes; null when cancelled. */
	static File publish(Window owner,File folder,String name,int previewFormat,byte[] preview,StackTextEncoding encoding){
		JFileChooser chooser=new JFileChooser(folder);FileChooserIcons.install(chooser);
		chooser.setDialogTitle(International.t("Publish"));chooser.setApproveButtonText(International.t("Publish"));
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.addChoosableFileFilter(editionFilter(f->FinderInfo68k.type(f),EditionManager68k.MASK_PICT|EditionManager68k.MASK_TEXT|EditionManager68k.MASK_SND));
		Preview box=new Preview(encoding);box.show(previewFormat,preview);chooser.setAccessory(box);
		chooser.setSelectedFile(new File(folder,FileManager68k.hostFileName(name.replace('/',':'))));
		while(true){
			if(chooser.showSaveDialog(owner)!=JFileChooser.APPROVE_OPTION)return null;
			File chosen=chooser.getSelectedFile();
			if(chosen==null)return null;
			if(!chosen.exists())return chosen;
			if(chosen.isDirectory())continue;
			int answer=JOptionPane.showConfirmDialog(owner,International.t("“{0}” already exists. Replace it with the new edition?",chosen.getName()),International.t("Publish"),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
			if(answer==JOptionPane.YES_OPTION)return chosen;
		}
	}

	/** The subscriber's panel: an edition with one of the formats in {@code mask}; null when cancelled. */
	static File subscribe(Window owner,File folder,File selected,int mask,ToIntFunction<File> typeOf,StackTextEncoding encoding){
		JFileChooser chooser=new JFileChooser(folder);FileChooserIcons.install(chooser);
		chooser.setDialogTitle(International.t("Subscribe To"));
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setAcceptAllFileFilterUsed(false);chooser.setFileFilter(editionFilter(typeOf,mask));
		Preview box=new Preview(encoding);chooser.setAccessory(box);
		chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY,e->box.showEdition((File)e.getNewValue()));
		if(selected!=null){chooser.setSelectedFile(selected);box.showEdition(selected);}
		return chooser.showDialog(owner,International.t("Subscribe"))==JFileChooser.APPROVE_OPTION?chooser.getSelectedFile():null;
	}

	/** The section options' answer: cancelled, or the action (SectionOptionsReply.action) and the update mode chosen. */
	static final class Options {
		boolean canceled;int action=EditionManager68k.ACTION_OK,mode;
		/** A scripted answer: "cancel", "ok", "read", "writ", "goto" or "cncl", then optionally "manual" / "auto". */
		static Options parse(String script,int mode){
			Options o=new Options();o.mode=mode;
			for(String word:script.trim().toLowerCase(java.util.Locale.ROOT).split("\\s+")){
				switch(word){
					case "cancel":o.canceled=true;break;
					case "read":o.action=EditionManager68k.ACTION_READ;break;
					case "writ":o.action=EditionManager68k.ACTION_WRITE;break;
					case "goto":o.action=EditionManager68k.ACTION_GOTO;break;
					case "cncl":o.action=EditionManager68k.ACTION_CANCEL;break;
					case "manual":o.mode=EditionManager68k.MODE_MANUAL;break;
					case "auto":o.mode=EditionManager68k.MODE_AUTOMATIC;break;
					default:break;
				}
			}
			return o;
		}
	}

	/**
	 * Publisher Options / Subscriber Options: the edition the section names, when Send/Get happens (on save or automatically,
	 * or by hand) with the button for doing it now, the latest edition's date, and Cancel Publisher/Subscriber (asked first)
	 * and, for a subscriber, Open Publisher. {@code editionTime} is host milliseconds, 0 when unknown.
	 */
	static Options options(Window owner,boolean publisher,String where,int mode,long editionTime,boolean editionThere){
		Options answer=new Options();answer.canceled=true;answer.mode=mode;
		JDialog dialog=optionsDialog(owner,publisher,where,mode,editionTime,editionThere,answer);
		dialog.setVisible(true);
		return answer;
	}
	/** The options dialog, packed and not shown yet; its buttons fill in {@code answer} and close it. */
	static JDialog optionsDialog(Window owner,boolean publisher,String where,int mode,long editionTime,boolean editionThere,Options answer){
		JDialog dialog=new JDialog(owner,(publisher?International.t("Publisher Options"):International.t("Subscriber Options")),java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		JPanel content=new JPanel(new GridBagLayout());content.setBorder(BorderFactory.createEmptyBorder(12,14,10,14));
		GridBagConstraints c=new GridBagConstraints();c.anchor=GridBagConstraints.WEST;c.insets=new Insets(3,0,3,8);
		c.gridx=0;c.gridy=0;content.add(new JLabel((publisher?International.t("Publisher to:"):International.t("Subscriber to:"))),c);
		c.gridx=1;c.gridwidth=2;c.fill=GridBagConstraints.HORIZONTAL;c.weightx=1;
		JLabel path=new JLabel(where.isEmpty()?International.t("(edition not found)"):where);path.setFont(path.getFont().deriveFont(Font.BOLD));content.add(path,c);
		c.gridwidth=1;c.fill=GridBagConstraints.NONE;c.weightx=0;
		c.gridx=0;c.gridy=1;content.add(new JLabel((publisher?International.t("Send Editions:"):International.t("Get Editions:"))),c);
		JRadioButton first=new JRadioButton((publisher?International.t("On Save"):International.t("Automatically")),mode==EditionManager68k.MODE_AUTOMATIC),manual=new JRadioButton(International.t("Manually"),mode!=EditionManager68k.MODE_AUTOMATIC);
		ButtonGroup group=new ButtonGroup();group.add(first);group.add(manual);
		JPanel radios=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));radios.add(first);radios.add(javax.swing.Box.createHorizontalStrut(10));radios.add(manual);
		c.gridx=1;content.add(radios,c);
		JButton now=new JButton((publisher?International.t("Send Edition Now"):International.t("Get Edition Now")));now.setEnabled(publisher||editionThere);
		c.gridx=2;c.anchor=GridBagConstraints.EAST;content.add(now,c);c.anchor=GridBagConstraints.WEST;
		c.gridx=0;c.gridy=2;content.add(new JLabel(International.t("Latest Edition:")),c);
		c.gridx=1;c.gridwidth=2;content.add(new JLabel(editionTime==0?"—":DateFormat.getDateTimeInstance(DateFormat.FULL,DateFormat.SHORT,java.util.Locale.forLanguageTag(International.language())).format(new Date(editionTime))),c);
		JPanel buttons=new JPanel(new BorderLayout());buttons.setBorder(BorderFactory.createEmptyBorder(12,0,0,0));
		JPanel left=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)),right=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
		JButton cancelSection=new JButton((publisher?International.t("Cancel Publisher"):International.t("Cancel Subscriber")));left.add(cancelSection);
		JButton openPublisher=null;
		if(!publisher){openPublisher=new JButton(International.t("Open Publisher"));openPublisher.setEnabled(editionThere);left.add(javax.swing.Box.createHorizontalStrut(6));left.add(openPublisher);}
		JButton cancel=new JButton(International.t("Cancel")),ok=new JButton("OK");right.add(cancel);right.add(ok);
		buttons.add(left,BorderLayout.WEST);buttons.add(right,BorderLayout.EAST);
		c.gridx=0;c.gridy=3;c.gridwidth=3;c.fill=GridBagConstraints.HORIZONTAL;content.add(buttons,c);
		Runnable done=dialog::dispose;
		java.util.function.IntConsumer finish=action->{answer.canceled=false;answer.action=action;answer.mode=first.isSelected()?EditionManager68k.MODE_AUTOMATIC:EditionManager68k.MODE_MANUAL;done.run();};
		ok.addActionListener(e->finish.accept(EditionManager68k.ACTION_OK));
		now.addActionListener(e->finish.accept(publisher?EditionManager68k.ACTION_WRITE:EditionManager68k.ACTION_READ));
		if(openPublisher!=null)openPublisher.addActionListener(e->finish.accept(EditionManager68k.ACTION_GOTO));
		cancelSection.addActionListener(e->{
			int sure=JOptionPane.showConfirmDialog(dialog,(publisher?International.t("Cancel this publisher? The document stops sending editions."):International.t("Cancel this subscriber? The document stops getting editions.")),dialog.getTitle(),JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE);
			if(sure==JOptionPane.OK_OPTION)finish.accept(EditionManager68k.ACTION_CANCEL);});
		cancel.addActionListener(e->done.run());
		dialog.getRootPane().setDefaultButton(ok);
		dialog.getRootPane().registerKeyboardAction(e->done.run(),javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
		dialog.setContentPane(content);dialog.pack();dialog.setResizable(false);dialog.setLocationRelativeTo(owner);
		return dialog;
	}
}
