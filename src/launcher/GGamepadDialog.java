import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;

/**
 * The game controller bindings (GamepadHost.Mapping): a table of every control - buttons, hat, stick axes in both
 * directions - with the key, mouse click or pointer motion it stands for. Pressing a control on the pad selects its row
 * (the engine is in capture mode meanwhile: nothing is typed); a key pressed in the "Key" field binds the selected row,
 * the buttons beside it bind the mouse click, the pointer axes or nothing. OK returns the mapping, Cancel null.
 * Used for the preference (GPreferencesDialog VM form) and for one program's own bindings (GAppSettingsDialog).
 */
final class GGamepadDialog extends JDialog {
	private static final long serialVersionUID=1L;
	private final GamepadHost.Mapping mapping;
	private final List<String> controls=GamepadHost.allControls();
	private final JTable table;private final Model model=new Model();
	private final JTextField keyField;private final JLabel status;
	private boolean ok;
	private final GamepadHost.ControlListener controlListener=new GamepadHost.ControlListener(){
		public void controlPressed(GamepadHost.Device device,final String control){
			SwingUtilities.invokeLater(new Runnable(){public void run(){selectControl(control);}});
		}
	};
	private final Timer statusTimer;

	private GGamepadDialog(Window owner,String title,GamepadHost.Mapping initial){
		super(owner,title,ModalityType.APPLICATION_MODAL);
		mapping=initial.copy();
		getContentPane().setLayout(new BorderLayout());

		JPanel top=new JPanel(new BorderLayout());
		top.setBorder(BorderFactory.createEmptyBorder(12,16,4,16));
		JLabel intro=new JLabel(GPreferencesDialog.dialogText("Press a control on the controller to select it, then press the key it should type."));
		top.add(intro,BorderLayout.NORTH);
		status=new JLabel(" ");
		status.setFont(status.getFont().deriveFont(status.getFont().getSize2D()-1f));
		top.add(status,BorderLayout.SOUTH);
		getContentPane().add(top,BorderLayout.NORTH);

		table=new JTable(model);
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getColumnModel().getColumn(0).setPreferredWidth(160);
		table.getColumnModel().getColumn(1).setPreferredWidth(200);
		JScrollPane scroll=new JScrollPane(table);
		scroll.setPreferredSize(new Dimension(380,260));
		scroll.setBorder(BorderFactory.createEmptyBorder(0,16,0,16));
		getContentPane().add(scroll,BorderLayout.CENTER);

		JPanel bottom=new JPanel(new BorderLayout());
		JPanel assign=new JPanel(new FlowLayout(FlowLayout.LEFT));
		assign.setBorder(BorderFactory.createEmptyBorder(4,12,0,12));
		assign.add(new JLabel(GPreferencesDialog.dialogText("Key:")));
		keyField=new JTextField(GPreferencesDialog.dialogText("Press a key"),10);
		keyField.setEditable(false);
		keyField.addKeyListener(new KeyAdapter(){
			@Override public void keyPressed(KeyEvent e){
				e.consume();
				if(e.getKeyCode()==KeyEvent.VK_UNDEFINED)return;
				bindSelected(GamepadHost.Binding.key(e.getKeyCode()));
			}
			@Override public void keyTyped(KeyEvent e){e.consume();}
		});
		assign.add(keyField);
		assign.add(button("Mouse click",new ActionListener(){public void actionPerformed(ActionEvent e){bindSelected(GamepadHost.Binding.click);}}));
		assign.add(button("Pointer left/right",new ActionListener(){public void actionPerformed(ActionEvent e){bindSelected(GamepadHost.Binding.mouseH);}}));
		assign.add(button("Pointer up/down",new ActionListener(){public void actionPerformed(ActionEvent e){bindSelected(GamepadHost.Binding.mouseV);}}));
		assign.add(button("None",new ActionListener(){public void actionPerformed(ActionEvent e){bindSelected(null);}}));
		bottom.add(assign,BorderLayout.NORTH);
		JPanel buttons=new JPanel(new BorderLayout());
		buttons.setBorder(BorderFactory.createEmptyBorder(4,12,8,12));
		JPanel left=new JPanel(new FlowLayout(FlowLayout.LEFT));
		left.add(button("Defaults",new ActionListener(){public void actionPerformed(ActionEvent e){
			mapping.bindings.clear();mapping.bindings.putAll(GamepadHost.Mapping.defaults().bindings);model.fireTableDataChanged();}}));
		buttons.add(left,BorderLayout.WEST);
		JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT));
		right.add(button("Cancel",new ActionListener(){public void actionPerformed(ActionEvent e){dispose();}}));
		JButton okButton=button("OK",new ActionListener(){public void actionPerformed(ActionEvent e){ok=true;dispose();}});
		right.add(okButton);
		getRootPane().setDefaultButton(okButton);
		buttons.add(right,BorderLayout.EAST);
		bottom.add(buttons,BorderLayout.SOUTH);
		getContentPane().add(bottom,BorderLayout.SOUTH);

		statusTimer=new Timer(500,new ActionListener(){public void actionPerformed(ActionEvent e){refreshStatus();}});
		addWindowListener(new WindowAdapter(){
			@Override public void windowOpened(WindowEvent e){
				GamepadHost host=GamepadHost.get();
				if(host!=null){host.engine.addListener(controlListener);host.engine.setCaptureOnly(true);}
				refreshStatus();statusTimer.start();
			}
			@Override public void windowClosed(WindowEvent e){
				statusTimer.stop();
				GamepadHost host=GamepadHost.get();
				if(host!=null){host.engine.removeListener(controlListener);host.engine.setCaptureOnly(false);}
			}
		});
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		pack();
		if(owner!=null&&owner.isShowing())setLocationRelativeTo(owner);else setLocationRelativeTo(null);
	}
	private static JButton button(String text,ActionListener l){JButton b=new JButton(GPreferencesDialog.dialogText(text));b.addActionListener(l);return b;}

	private void refreshStatus(){
		List<String> names=GamepadHost.deviceNames();
		String text;
		if(!GamepadHost.available())text=GPreferencesDialog.dialogText("Controllers cannot be read here (Java 22 or later is needed).");
		else if(names.isEmpty())text=GPreferencesDialog.dialogText("No controller connected.");
		else{StringBuilder sb=new StringBuilder();for(String n:names){if(sb.length()>0)sb.append(", ");sb.append(n);}text=GPreferencesDialog.dialogText("Connected:")+" "+sb;}
		if(!text.equals(status.getText()))status.setText(text);
	}
	private void selectControl(String control){
		int row=controls.indexOf(control);if(row<0)return;
		table.setRowSelectionInterval(row,row);
		table.scrollRectToVisible(table.getCellRect(row,0,true));
		keyField.requestFocusInWindow();
	}
	private void bindSelected(GamepadHost.Binding b){
		int row=table.getSelectedRow();if(row<0)return;
		mapping.put(controls.get(row),b);
		model.fireTableRowsUpdated(row,row);
	}

	private final class Model extends AbstractTableModel {
		private static final long serialVersionUID=1L;
		@Override public int getRowCount(){return controls.size();}
		@Override public int getColumnCount(){return 2;}
		@Override public String getColumnName(int c){return GPreferencesDialog.dialogText(c==0?"Pad control":"Types");}
		@Override public Object getValueAt(int r,int c){
			String control=controls.get(r);
			if(c==0)return GamepadHost.describeControl(control);
			GamepadHost.Binding b=mapping.get(control);return b==null?"":b.describe();
		}
	}

	/** Edits a copy of {@code initial}; the result on OK, null on Cancel. */
	static GamepadHost.Mapping show(Window owner,String title,GamepadHost.Mapping initial){
		GGamepadDialog d=new GGamepadDialog(owner,title,initial);
		GPictWindow.prepareForModalDialog(d);
		d.setVisible(true);
		GPictWindow.restorePictureWindows();
		return d.ok?d.mapping:null;
	}
}
