import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * HyperZebra Help: a window with the topics on the left and the explanation of the
 * chosen one on the right (the launcher's Help menu). The topics are plain English
 * text, kept in {@link #topics()} and shown translated by the catalog (msgctxt "help");
 * a new topic is one more entry there.
 */
final class GHelpWindow extends JFrame {
	private static final long serialVersionUID=1L;

	/** One explanation: an id the menus open it by, its title and body in English (translated when shown). */
	static final class Topic {
		final String id,titleEn,bodyEn;
		Topic(String id,String titleEn,String bodyEn){this.id=id;this.titleEn=titleEn;this.bodyEn=bodyEn;}
		String title(){return International.tc("help",titleEn);}
		String body(){return International.tc("help",bodyEn);}
		@Override public String toString(){return title();}
	}

	static final String TOPIC_PAINT_USAGE="paint-usage",TOPIC_PAINT_FEATURES="paint-features";
	static final String TOPIC_APPLEDOUBLE="appledouble",TOPIC_FORKS="forks",TOPIC_TRANSFER="transfer";
	/** What HyperZebra adds to HyperCard and HyperTalk: the overview, then one topic for each addition. */
	static final String TOPIC_EXTENSIONS="extensions", TOPIC_PANELS="panels", TOPIC_SHAPES="shapes", TOPIC_FIELD_VIEWS="field-views", TOPIC_COLLECTIONS="collections-json", TOPIC_REGEX="regex";

	private static GHelpWindow instance;
	private final JList<Topic> list;
	private final JTextArea body;
	private final JLabel heading;

	/** Shows the help window (one for the session) on the given topic, or on the first topic without one. */
	static void show(final String topicId){
		SwingUtilities.invokeLater(new Runnable(){public void run(){
			if(instance==null||!instance.isDisplayable())instance=new GHelpWindow();
			instance.select(topicId);
			instance.setVisible(true);
			instance.toFront();
		}});
	}

	private GHelpWindow(){
		super(International.t("HyperZebra Help"));
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		list=new JList<Topic>(topics().toArray(new Topic[0]));
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
		JScrollPane listScroll=new JScrollPane(list);
		listScroll.setMinimumSize(new Dimension(200,100));
		heading=new JLabel();
		heading.setFont(heading.getFont().deriveFont(Font.BOLD,16f));
		heading.setBorder(BorderFactory.createEmptyBorder(8,10,6,10));
		body=new JTextArea();
		body.setEditable(false);
		body.setLineWrap(true);
		body.setWrapStyleWord(true);
		body.setFont(new JLabel().getFont().deriveFont(13f));
		body.setBorder(BorderFactory.createEmptyBorder(0,10,10,10));
		javax.swing.JPanel right=new javax.swing.JPanel(new BorderLayout());
		right.add(heading,BorderLayout.NORTH);
		JScrollPane bodyScroll=new JScrollPane(body);
		bodyScroll.setBorder(BorderFactory.createEmptyBorder());
		right.add(bodyScroll,BorderLayout.CENTER);
		JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,listScroll,right);
		split.setDividerLocation(260);
		split.setBorder(BorderFactory.createEmptyBorder());
		getContentPane().add(split,BorderLayout.CENTER);
		list.addListSelectionListener(new ListSelectionListener(){public void valueChanged(ListSelectionEvent e){if(!e.getValueIsAdjusting())showSelected();}});
		setSize(840,540);
		setLocationRelativeTo(null);
	}

	private void select(String topicId){
		int index=0;
		if(topicId!=null)for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i).id.equals(topicId)){index=i;break;}
		list.setSelectedIndex(index);
		showSelected();
	}

	private void showSelected(){
		Topic topic=list.getSelectedValue();
		if(topic==null)return;
		heading.setText(topic.title());
		body.setText(topic.body());
		body.setCaretPosition(0);
	}

	/** Every topic, in the order of the list. */
	static List<Topic> topics(){
		List<Topic> topics=new ArrayList<Topic>();
		topics.add(new Topic("launcher",
			"Using the launcher",
			"The launcher is the starting point for opening stacks and classic Mac applications.\n"+
			"\n"+
			"Opening files\n"+
			"Choose Open File… or drop a file onto the launcher. Recently opened applications, stacks an"+
			"d documents appear in the history. Use the history filters to narrow the list; double-click an "+
			"entry (or select it and press Enter) to open it. A document opens with the application recorded for "+
			"it. If a moved file is marked Not found, use Locate… in its context menu to select its new "+
			"location.\n"+
			"\n"+
			"Using the dock\n"+
			"Right-click an application or stack in the history and choose Add to Dock to keep it available. "+
			"Click a dock icon once to launch it or bring its window forward. A dot marks a running item. Drag "+
			"icons to change their order. Dropping a file onto the launcher opens it; to keep an item in the "+
			"dock, use Add to Dock.\n"+
			"Drop a document onto an application's dock icon or history row to open it with that application. An "+
			"application's dock context menu also provides Recent Documents, Application Settings… and Q"+
			"uit. Remove from Dock and Remove from History remove entries, not the original files.\n"+
			"A running application's context menu (dock or history) also has Force Quit…, which stops a program that no longer "+
			"responds without letting it save. The dock's square button (or View > Show the Dock in the Whole Window, Cmd-D) "+
			"spreads the dock over the window for a while; the button again, Escape or any tab puts it back.\n"+
			"\n"+
			"Settings and returning to the launcher\n"+
			"The buttons under Open File… are tabs: History, Settings, Mac Application Settings, Translation, Appearance and About each show "+
			"their page and stay selected (View > Cmd-1 to Cmd-6 and the arrow keys switch too).\n"+
			"Settings controls language, zoom and access confirmation. Mac Application Settings controls "+
			"the compatibility environment for 68K and PowerPC programs - speed (a 68040 Macintosh or a G3 iMac for "+
			"PowerPC programs), screen colours and size; an application's own settings can override shared values. Translation sets the language and engine for programs "+
			"(Resource Translation…) and stacks (File > Translation Settings…); a program or stack can have its own (Translation Settings…). Appearance "+
			"changes the theme, list density and dock size/position.\n"+
			"Tools > Open Home Stack opens the HyperCard-compatible Home stack, Tools > Resource Editor… opens the resources "+
			"of a chosen file, and Tools > Running Programs Info… shows each running program's CPU usage, speed, memory, "+
			"screen size, colours and 24-bit mode. Open HyperZebra HD opens the folder used by the compatibility environment. From a stack, choose Go "+
			"> Show Launcher to return. Close Stack closes the current stack. Quit (Cmd-Q) in a stack window also closes the stack and returns to the launcher; "+
			"Quit in the launcher exits HyperZebra."));
		topics.add(new Topic("mac-compatibility",
			"Classic Mac compatibility layer",
			"HyperZebra includes a compatibility layer that executes classic Macintosh 68K and PowerPC code and "+
			"supplies Mac Toolbox services. It is used by 68K and PowerPC applications and by HyperCard XCMD/XFCN "+
			"resources. Open an application from the launcher as you would a stack. A PowerPC application runs "+
			"on an emulated PowerPC 601/603 with the Code Fragment Manager, Mixed Mode and the system libraries "+
			"(InterfaceLib, MathLib, StdCLib and others). A fat application runs its 68K code.\n"+
			"\n"+
			"The layer provides windows, menus, dialogs, drawing, fonts, sound and file access. It also bridges "+
			"text and pictures with the host clipboard. Supported text and picture file conversions allow "+
			"classic applications to work with host files; preferences control UTF-8 text and PNG picture "+
			"output.\n"+
			"\n"+
			"The virtual System Folder is inside HyperZebra HD. It contains Preferences, Extensions and Control "+
			"Panels. Supported extensions placed in Extensions load when an application starts. Use Open "+
			"HyperZebra HD in the launcher to inspect these files. Preserve resource forks when copying classic "+
			"applications and extensions; see the resource-fork and file-transfer help topics.\n"+
			"\n"+
			"If an application displays incorrectly or runs at an unsuitable speed, try its Application "+
			"Settings… (right-click the application in the dock or the history, or the Application Settings… button "+
			"of Mac Application Settings; it need not be in the dock): screen colours, screen size, zoom, wide file dialogs and spe"+
			"ed. Restart the application to check settings used at launch.\n"+
			"\n"+
			"Compatibility is not complete. CarbonLib, Open Transport and QuickDraw 3D are available, but applications "+
			"that rely on unimplemented calls or direct hardware access may fail or display incorrectly. An extension "+
			"or XCMD can also require services that have not been implemented. HyperZebra decodes some movie formats "+
			"itself; the next topic explains when FFmpeg or classic QuickTime can help."));
		topics.add(new Topic("movies",
			"Movie playback: built-in codecs, FFmpeg and QuickTime",
			"HyperZebra's built-in decoders play several classic QuickTime video and sound formats, as well as "+
			"QuickTime Music, without extra software. This includes the QTMovie and Movie XCMDs used by stacks. "+
			"FFmpeg on the host or classic 68K QuickTime in the compatibility environment can play additional "+
			"formats. Available codecs depend on the playback method and movie format.\n"+
			"\n"+
			"Using FFmpeg\n"+
			"Install FFmpeg for your host OS and make the ffmpeg command available on the PATH inherited by "+
			"HyperZebra. Check with ffmpeg -version in a terminal, then restart HyperZebra. A desktop-launched "+
			"application may have a different PATH from your terminal; if detection fails, check the environment "+
			"used to launch HyperZebra.\n"+
			"For formats the built-in decoders cannot handle, FFmpeg extracts movie frames and audio for playback. The first opening may take time while "+
			"conversion runs, and converted assets use disk space.\n"+
			"\n"+
			"Using classic QuickTime\n"+
			"Run a compatible 68K QuickTime installer in HyperZebra. QuickTime 4.0.3 for 68K is supported by "+
			"this environment. Its installer places QuickTime(TM) and related files in the virtual System "+
			"Folder's Extensions folder. Restart HyperZebra after installation.\n"+
			"This means QuickTime installed inside HyperZebra's virtual Mac environment. Merely having a "+
			"QuickTime Player application on the host OS does not provide these extensions.\n"+
			"When installed, classic QuickTime is tried first for the stack's QTMovie/Movie XCMDs. If it cannot "+
			"open a movie, HyperZebra tries its built-in decoders, then FFmpeg for unsupported formats.\n"+
			"\n"+
			"If playback fails\n"+
			"For formats that need them, check that FFmpeg is visible to HyperZebra or that the QuickTime "+
			"extensions are in the virtual System Folder. Keep a movie's resource fork and any referenced media "+
			"files together. Some codecs "+
			"remain unsupported even with these components; for example, classic QuickTime has no 68K QDesign "+
			"Music decoder. Missing video or sound does not necessarily mean the stack itself is broken."));
		topics.add(new Topic("hypercard",
			"HyperCard compatibility",
			"HyperZebra can import HyperCard stacks and run supported HyperTalk scripts. Cards, backgrounds, "+
			"buttons, fields, pictures and resources form the basis of the converted stack; you can browse and "+
			"edit it in HyperZebra.\n"+
			"\n"+
			"Opening a stack\n"+
			"Choose the original stack with Open File… or drop it onto the launcher. When asked for the "+
			"text encoding, choose the encoding used to create the stack (for example, Japanese or Mac OS "+
			"Roman). A wrong choice can produce garbled text.\n"+
			"Import creates a .hzb stack file beside the original stack. It holds the converted stack and its resources "+
			"in one file that you can double-click, copy or send like any other document. Open the .hzb file for "+
			"subsequent work. Stacks saved as .hzb folders by earlier versions still open, and are saved as folders. Keep a "+
			"backup of the original HyperCard stack as well.\n"+
			"\n"+
			"Scripts and external commands\n"+
			"HyperTalk commands, functions, properties and message handling are implemented in HyperZebra. "+
			"Supported 68K XCMD/XFCN resources run through the Mac compatibility layer, and some external "+
			"commands have built-in implementations. Compatibility varies with the commands, callbacks and "+
			"Toolbox functions used. A stack opening successfully does not guarantee that every script or "+
			"external command will work.\n"+
			"Movie XCMDs can use the built-in decoders, FFmpeg or classic QuickTime; see Movie playback. Missing sounds, icons or external "+
			"commands may indicate a lost resource fork; see the file-transfer topics.\n"+
			"\n"+
			"Returning to HyperCard\n"+
			"Use File > Export HyperCard Stack… to write a HyperCard-format stack. Check any warnings sh"+
			"own after export and test the result in the target HyperCard environment. Features and data do not "+
			"necessarily make a lossless round trip; retain the original and the .hzb working copy.\n"+
			"\n"+
			"Beyond HyperCard\n"+
			"HyperZebra also adds shapes, panels with data and scripts, table and outline fields, collections, JSON and "+
			"regular expressions to HyperCard and HyperTalk. See \"Beyond HyperCard: overview\" and the topics after it."));
		topics.add(new Topic(TOPIC_EXTENSIONS,
			"Beyond HyperCard: overview",
			"HyperZebra runs HyperCard stacks as they are and adds a few things HyperCard and HyperTalk never had. The idea behind them: a few kinds of objects you can see and change at any time, rather than many special controls. Scripts that do not use the additions behave as in HyperCard.\n"+
			"\n"+
			"Objects\n"+
			"  Shapes: vector rectangles, ovals, polygons, curves and text with colours, gradients, filters and scripts, stacked together with buttons and fields. See \"Shapes\".\n"+
			"  Panels: groups of buttons, fields and shapes that move, show and hide together. A panel keeps its own text, named values (Panel Data) and a script that receives its members' messages. See \"Panels\".\n"+
			"  Table and outline fields: a field's text shown as a sortable table, or as an outline of JSON or tab-indented lines. See \"Table and outline fields\".\n"+
			"\n"+
			"HyperTalk\n"+
			"  Collections: arrays [1, 2, 3] and dictionaries {name: \"Taro\"}.\n"+
			"  The web and JSON: get URL, put URL … into, JSONValue(it, \"weather.temperature\").\n"+
			"  See \"Collections, the web and JSON\".\n"+
			"  Regular expressions: matchText, matchChunk and replaceText. See \"Regular expressions\".\n"+
			"  export card to file \"card.png\" saves the card, shapes included, as an image (also File > Export Card Image…).\n"+
			"\n"+
			"Writing scripts\n"+
			"  A script error offers Debug: the debugger stops on the failing statement with the handler's local variables still there. The Variable Watcher shows the call stack and lets you change values while the script is paused. In the script editor, Edit > Find in Stack… (Shift-Command-F) searches every script of the stack, including shapes and panels.\n"+
			"  When you need a pattern or a JSON path, you can ask an AI assistant for just that piece and paste it into your own script: the functions here take the forms such assistants already know (LiveCode's regular-expression functions, dotted JSON paths).\n"+
			"\n"+
			"Painting\n"+
			"  Layers, full colour, anti-aliasing, gradients and selections that scale and rotate: see \"Using the paint tools\" and \"Paint feature list\".\n"+
			"\n"+
			"HyperCard itself\n"+
			"  HyperCard does not know these additions. A stack that relies on them works fully only in HyperZebra; File > Export HyperCard Stack… keeps what HyperCard can hold (a table or outline field keeps its text, for example) and leaves the rest out. Keep the .hzb file as the working copy."));
		topics.add(new Topic(TOPIC_PANELS,
			"Panels",
			"A panel is an object of a card or background, like a button or a field. It holds buttons, fields and shapes of that card (or background) so they move, hide and show together, and it has a name, a number, an ID, contents, named values (data) and a script of its own.\n"+
			"\n"+
			"Making a panel\n"+
			"  Objects > New Panel makes a panel named \"New Panel\" on the card (on the background while you edit it). If a button, field or shape is selected, it goes into the new panel, and Panel Info opens. A script makes one with create panel; the panel gets the newPanel message.\n"+
			"    create panel \"Player\"\n"+
			"    set the panel of button \"Reset\" to \"Player\"\n"+
			"    set the panel of field \"Score\" to \"Player\"\n"+
			"  In Button Info, Field Info and Shape Info, the Panel pop-up chooses the panel a part belongs to (None takes it out). A part can only be in a panel of its own card or background. A panel stays, even with no members, until you delete it.\n"+
			"\n"+
			"Panel Info\n"+
			"  Objects > Panel Info… opens it for the panel of the selected part (or lets you choose one of the panels of the card and its background). It shows the name, number and ID and how many members the panel has, and has Visible, Script…, Content…, Data… and Delete Panel. As in the other info windows, changes apply at once, and Edit > Undo takes them back. Data… edits the named values as a Key/Value table. Delete Panel deletes the panel only: its members stay on the card.\n"+
			"  Panel Info lists the members of the panel. With the Shape tool, selecting a shape there selects it on the card. Double-click a member (or select it and click Info…) for its own info; Remove from Panel takes it out and leaves it on the card. Panel Info… beside the Panel pop-up in Button, Field and Shape Info goes back to the panel, and with the Shape tool, double-clicking a shape that is in a panel opens Panel Info.\n"+
			"\n"+
			"Referring to a panel\n"+
			"  As to a button: panel \"Player\", panel 2 (by number; \"2\" in quotes is a name), panel id 3, card panel \"Player\", background panel \"Player\", panel \"Player\" of card 5. A plain panel \"Player\" is the card's, else the background's.\n"+
			"    get the number of panels               -- also card panels, bkgnd panels\n"+
			"    get the name of panel 1                -- card panel \"Player\"\n"+
			"    get the id of panel \"Player\"\n"+
			"    get the panel of button \"Reset\"        -- Player\n"+
			"    set the name of panel \"Player\" to \"Hero\"   -- the members stay in it\n"+
			"    delete panel \"Hero\"\n"+
			"\n"+
			"Moving, showing and hiding\n"+
			"    set the loc of panel \"Player\" to 200,100\n"+
			"    hide panel \"Player\"\n"+
			"    show panel \"Player\"\n"+
			"  The rect, left, top, width and height of a panel are those of the rectangle around its members; setting its loc, topLeft, left, top, right or bottom moves them. Dragging a member moves the whole panel. A panel has its own visible: hiding it hides its members without changing theirs, so a member you hid on its own stays hidden when the panel is shown again.\n"+
			"\n"+
			"Contents and data\n"+
			"  A panel keeps contents, like a button, and named values that stay apart from the built-in properties:\n"+
			"    put \"Taro\" into panel \"Player\"\n"+
			"    put line 1 of panel \"Player\" into field \"Name\"\n"+
			"    set the data \"score\" of panel \"Player\" to 100\n"+
			"    put the data \"score\" of panel \"Player\" + 1 into x\n"+
			"    put \"Taro\" into the data[\"name\"] of panel \"Player\"\n"+
			"    put \" pts\" after the data \"score\" of panel \"Player\"\n"+
			"    get the dataKeys of panel \"Player\"          -- one key per line\n"+
			"    put the data of panel \"Player\" into d        -- a dictionary: d[\"score\"]\n"+
			"    repeat for each key in the data of panel \"Player\"\n"+
			"    set the data of panel \"Player\" to {score: 0, name: \"\"}\n"+
			"    set the data of panel \"Player\" to it        -- a JSON object also works\n"+
			"    set the data \"score\" of panel \"Player\" to empty   -- removes the key\n"+
			"  Keys ignore case. the data \"score\" is always the panel's value, and the width, the visible and the rest are always properties, so a reader can tell them apart. Everything is saved with the card or background.\n"+
			"\n"+
			"The panel's script\n"+
			"  Messages a member does not handle go to its panel before the card:\n"+
			"    button (field, shape) → panel → card → background → stack\n"+
			"  In the panel's handlers, me is the panel and the target is the member that got the message; pass goes on to the card. Functions, and handlers that replace built-in commands, also serve the members.\n"+
			"    on mouseUp\n"+
			"      if the short name of the target is \"Reset\" then\n"+
			"        set the data \"score\" of me to 0\n"+
			"        put 0 into field \"Score\"\n"+
			"      end if\n"+
			"      pass mouseUp\n"+
			"    end mouseUp\n"+
			"  Open the script with Script… in Panel Info or with edit script of panel \"Player\", or set it with set the script of panel. send \"reset\" to panel \"Player\" sends a message to the panel itself."));
		topics.add(new Topic(TOPIC_SHAPES,
			"Shapes",
			"Shapes are vector drawings that live on a card or background beside its buttons and fields: rectangles, lines, ovals, regular polygons, polygons, freehand curves, Bézier curves and text. Unlike the paint picture they stay editable.\n"+
			"\n"+
			"Drawing and editing\n"+
			"  Choose the Shape tool in the tool palette (or Tool > Shape). Double-click or right-click its icon to choose the kind. Objects > New Shape makes one; Command-drag (Ctrl-drag on Windows and Linux) draws one at the dragged size. Click to select, drag to move, drag a corner to resize (Shift keeps the proportions), drag the round handle above it to rotate (Shift: 15° steps). Arrow keys move it, Command/Ctrl-arrow resizes it, Delete removes it. Edit > Background puts shapes on the background.\n"+
			"  Double-click a shape for Shape Info: Shape (name, panel, position, size, rotation, opacity), Fill & Stroke (colour, linear or radial gradient, image), Effects (outlines; shadows with offset, width and blur), Filters (blur, glow, colour adjustments and more, applied top to bottom), Text, and Curve (Edit Points… to drag anchor and control points).\n"+
			"  A shape in a panel (as the shapes of an imported SVG are) shows the frame and name of its panel when selected. Double-click it for Panel Info, which lists all the parts in the panel; Option-double-click (Alt-double-click on Windows and Linux) opens the shape's own Shape Info.\n"+
			"  Shapes share one stacking order with buttons and fields: Objects > Bring Closer and Send Farther move them past parts of any kind.\n"+
			"\n"+
			"SVG and images\n"+
			"  File > Import SVG… turns paths, basic shapes, text, images, gradients and many filters into editable shapes grouped in one panel. File > Export Card Image… (or export card to file \"card.png\") saves the card with its shapes as PNG.\n"+
			"\n"+
			"Scripts\n"+
			"  A shape has a script like a button (Script… in Shape Info, Shift-double-click with the Shape tool, Command-Option-click with the Browse tool, edit script of shape \"Logo\"). With the Browse tool it gets mouseDown, mouseStillDown, mouseUp, mouseDoubleClick, mouseEnter, mouseWithin and mouseLeave. Only what it draws takes clicks: its fill (even a transparent one), stroke and outlines; with no fill, clicks inside go through to the parts beneath.\n"+
			"    on mouseUp\n"+
			"      set the fill of me to \"#FFFF3366\"\n"+
			"    end mouseUp\n"+
			"\n"+
			"HyperTalk\n"+
			"    set the fill of shape \"Logo\" to \"#FF3366CC\"      -- #AARRGGBB, a colour name or none\n"+
			"    set the stroke of shape \"Logo\" to \"black\"\n"+
			"    set the strokeWidth of shape \"Logo\" to 2\n"+
			"    set the opacity of shape \"Logo\" to 50            -- 0 to 100\n"+
			"    set the rotation of shape \"Logo\" to 30\n"+
			"    set the text of shape \"Headline\" to \"NEW\"        -- text shapes\n"+
			"    set the textFont of shape \"Headline\" to \"Osaka\"  -- also textSize, textStyle (bold or plain)\n"+
			"    set the outlineWidth of shape \"Headline\" to 6     -- the first outline; also outlineColor\n"+
			"    set the shadowWidth of shape \"Headline\" to 6      -- the first shadow; also shadowOffset (x,y), shadowBlur, shadowColor\n"+
			"    set the shadows of shape \"Headline\" to \"5,5,6,2,#A0000000\"  -- a line per shadow: x,y,width,blur,colour\n"+
			"    set the outlines of shape \"Headline\" to \"6,black\"          -- a line per outline: width,colour\n"+
			"    set the gradientType of shape \"Logo\" to radial    -- linear, radial or none; gradientStart, gradientEnd, gradientAngle, gradientPoints\n"+
			"    set the kind of shape \"Logo\" to ellipse          -- also sides, texture (Base64 PNG), stretchTexture, filter (SVG <filter>)\n"+
			"    set the loc of shape \"Logo\" to 100,100\n"+
			"    hide shape \"Logo\"\n"+
			"    get the number of shape \"Headline\"               -- follows the stacking order\n"+
			"    get the id of shape \"Headline\"                   -- never changes\n"+
			"    select shape \"Headline\"\n"+
			"  Shapes are addressed by name, number or id, as card shape … or background shape … when needed."));
		topics.add(new Topic(TOPIC_FIELD_VIEWS,
			"Table and outline fields",
			"A field's text can be shown as a table or as an outline instead of lines of text. The text stays the data: chunk expressions, put and saving work on it as before, and a script that changes the text changes the view. Choose Table or Outline view as the style in Field Info, or set the style of the field to table or outline in a script.\n"+
			"\n"+
			"Tables\n"+
			"  Each line is a row and each tab-delimited item a cell.\n"+
			"    set the style of field \"People\" to table\n"+
			"    set the columnNames of field \"People\" to \"Name,Score,City\"\n"+
			"    set the columnWidths of field \"People\" to \"120,80,200\"   -- empty items share the rest\n"+
			"    set the sortable of field \"People\" to true               -- click a header to sort\n"+
			"    set the gridLines of field \"People\" to false\n"+
			"    set the itemDelimiter to tab\n"+
			"    put \"Kyoto\" into item 3 of line 2 of field \"People\"\n"+
			"  Clicking a header sorts the rows (again: the other way round) and rewrites the text, then sends sortTable column, direction. While lockText is false the cells are typed into: typing replaces a selected cell, a double-click edits it, Return and Tab go on to the next cell, the empty row at the bottom takes a new line, a right-click (or Control-click) on a cell or a heading inserts or deletes a column, and closeField is sent after each change. Select rows by clicking (several with multipleLines) or with set the selectedLine of field \"People\" to \"2 to 4\"; the selectedColumn and the clickText (the clicked cell) are available too.\n"+
			"\n"+
			"Outlines\n"+
			"    set the style of field \"Data\" to outline\n"+
			"  JSON (an object or array, or a HyperTalk collection) opens into its members, shown as \"city: Tokyo\", \"weather {2}\" (an object of two members) and \"days [2]\" (an array of two); it is read-only. Other text is an outline of lines indented with tabs: each line is a node under the nearest line above with fewer tabs. While lockText is false the outline is typed into: typing replaces the selected node's label (Return or F2 edits it), Return while typing adds a node below (Return on an empty one stops), Tab and Shift-Tab indent and outdent a node with its children, and Delete removes a node (its children stay); each change rewrites the lines and sends closeField.\n"+
			"    set the outlineLevel of field \"Data\" to 2    -- levels open at first (default 1, 0 closes all)\n"+
			"  Nodes you open stay open when a script changes the text.\n"+
			"  The selection belongs to the field:\n"+
			"    the selectedLine of field \"Data\"    -- indented text: \"line 4 of card field 1\"\n"+
			"    the selectedText of field \"Data\"    -- the label, or the JSON value\n"+
			"    the selectedPath of field \"Data\"    -- JSON: \"weather.days.2.name\" (array items from 1)\n"+
			"    set the selectedPath of field \"Data\" to \"weather.temp\"\n"+
			"  The selectedPath is the path JSONValue reads, so a click handler can fetch what was picked:\n"+
			"    on mouseUp\n"+
			"      put JSONValue(me, the selectedPath of me) into field \"Value\"\n"+
			"    end mouseUp\n"+
			"  Clicks send the usual mouse messages; the clickText is the clicked node's label (JSON: its value) and the clickLine its line.\n"+
			"\n"+
			"Example\n"+
			"    get URL \"https://example.com/weather.json\"\n"+
			"    put it into field \"Data\"\n"+
			"    set the style of field \"Data\" to outline"));
		topics.add(new Topic(TOPIC_COLLECTIONS,
			"Collections, the web and JSON",
			"Collections\n"+
			"  HyperTalk variables can hold arrays and dictionaries as well as text.\n"+
			"    put [10, 20, 30] into numbers                 -- indexes start at 1\n"+
			"    put {name: \"Taro\", age: 20} into person       -- keys: bare names or \"quoted\"\n"+
			"    put numbers[2] into x                         -- 20\n"+
			"    put item 2 of numbers into x                  -- the same\n"+
			"    put person[\"name\"] into y\n"+
			"    put the name of person into y                 -- the same\n"+
			"    put 40 after numbers                          -- adds an item\n"+
			"    put \"Jiro\" into person[\"name\"]\n"+
			"    set the age of person to 21\n"+
			"    delete item 1 of numbers\n"+
			"    delete key \"age\" of person\n"+
			"    repeat for each item in numbers               -- the value is in it\n"+
			"      add it to total\n"+
			"    end repeat\n"+
			"    repeat for each key in person                 -- the key is in key\n"+
			"      put key & \":\" && person[key] & return after field \"Info\"\n"+
			"    end repeat\n"+
			"  Literals can be nested and a dictionary literal can span several lines. put numbers shows a collection in the message box as [10, 20, 30].\n"+
			"\n"+
			"The web\n"+
			"    get URL \"https://example.com/data.json\"             -- the text into it\n"+
			"    put URL \"https://example.com/news.txt\" into field \"News\"\n"+
			"  HTTP and HTTPS only; up to five redirects are followed and responses over 16 MB are refused. HyperZebra asks before a stack uses the network (Settings: access confirmation). open URL \"https://…\" shows a page in your web browser instead.\n"+
			"\n"+
			"JSON\n"+
			"    get URL \"https://example.com/weather.json\"\n"+
			"    put JSONValue(it, \"city\") into field \"City\"\n"+
			"    put JSONValue(it, \"weather.temperature\") into field \"Temp\"   -- a path\n"+
			"    put JSONValue(it, \"days.2.name\") into field \"Tomorrow\"      -- arrays from 1\n"+
			"  JSONValue takes a key of the top-level object, or an index of a top-level array. A key the object does not have is read as a path through nested objects and arrays, separated by dots (a real key containing a dot wins). Missing keys and null give empty. An object or array comes back as a collection, which JSONValue also reads, so you can go step by step:\n"+
			"    put JSONValue(it, \"weather\") into w\n"+
			"    put JSONValue(w, \"temperature\") into t\n"+
			"  To look at a JSON reply, put it into a field with outlineStyle on; its selectedPath is the path to use with JSONValue (see \"Table and outline fields\")."));
		topics.add(new Topic(TOPIC_REGEX,
			"Regular expressions",
			"Three functions search and replace text with regular expressions. They have LiveCode's names and arguments, so examples written for LiveCode (or by an AI assistant asked for LiveCode-style code) work as they are. You do not need to learn the pattern language to use them: asking for \"a regular expression that finds a date like 2026-09-26\" is enough to get one.\n"+
			"\n"+
			"matchText(text, pattern)\n"+
			"  true when the pattern occurs somewhere in the text.\n"+
			"    if matchText(field \"Mail\", \"[0-9]{3}-[0-9]{4}\") then answer \"It has a postal code.\"\n"+
			"\n"+
			"matchText(text, pattern, var1, var2, …)\n"+
			"  Also puts the text of each parenthesised group into the variables, in order. The variables must be plain names. They become empty when nothing matches, and so does a group that took no part.\n"+
			"    if matchText(field \"Mail\", \"(\\d{4})-(\\d\\d)-(\\d\\d)\", y, m, d) then\n"+
			"      put y & \"年\" & m & \"月\" & d & \"日\" into field \"Date\"\n"+
			"    end if\n"+
			"    get matchText(line 1 of field \"Log\", \"name=([^;]+)\", who)\n"+
			"\n"+
			"matchChunk(text, pattern, start1, end1, …)\n"+
			"  Puts the character positions (start and end) of each group into pairs of variables, or of the whole match when the pattern has no groups. Use them with char … to … of:\n"+
			"    if matchChunk(field \"Text\", \"(?i)hypercard\", s, e) then\n"+
			"      select char s to e of field \"Text\"\n"+
			"    end if\n"+
			"\n"+
			"replaceText(text, pattern, replacement)\n"+
			"  Replaces every match. In the replacement, \\1 or $1 is group 1, ${name} a named group and \\\\ a backslash; a $ that is not followed by a group that exists stays as it is, so \"$5.00\" is safe.\n"+
			"    put replaceText(field \"Log\", \"\\s+\", space) into field \"Log\"\n"+
			"    put replaceText(\"2026-09-26\", \"(\\d+)-(\\d+)-(\\d+)\", \"\\3/\\2/\\1\") into d   -- 26/09/2026\n"+
			"\n"+
			"Patterns\n"+
			"  Java regular expressions, close to those of PCRE, Perl and JavaScript:\n"+
			"    .  any character          \\d  a digit          \\s  a space, tab or return\n"+
			"    *  0 or more   +  1 or more   ?  0 or 1   {3}  three times   {2,4}  2 to 4 times\n"+
			"    [abc]  one of these       [^;]  anything but ;   ( … )  a group\n"+
			"    ^ $  start and end of the text     a|b  a or b\n"+
			"    (?i)  at the start: ignore case    (?<year>\\d+)  a named group\n"+
			"  Japanese text works as it is. A pattern with a mistake stops the script with an error that says where. Command-period stops a match that takes too long."));
		topics.add(new Topic(TOPIC_PAINT_USAGE,
			"Using the paint tools",
			"Starting and finishing\n"+
			"Choose Tool > Show ToolBar (Command-T; Ctrl-T on Windows and Linux), then click a paint tool. You can also "+
			"choose a tool directly from the Tool menu. Choose Browse when you have finished painting. Paint changes are "+
			"stored with the stack; use File > Export Paint… when you also want a separate image file.\n"+
			"\n"+
			"The card picture is edited by default. Choose Edit > Background (Command/Ctrl-B) to edit the shared "+
			"background picture instead. Paint > Layers > Show Layers (Command/Ctrl-L) opens the layer palette and lets "+
			"you choose the exact layer to edit.\n"+
			"\n"+
			"Colours and tool settings\n"+
			"The bottom of the tool palette contains foreground and background colours, a pattern, a gradient or texture, "+
			"and transparency. Click a colour swatch for a quick palette or Other Colors…. Click the pattern or gradient "+
			"control to select its fill. The transparency control changes the opacity of drawing.\n"+
			"Right-click or double-click a tool button to open its available settings, such as brush size, hardness and "+
			"spacing; line width; fill style; spray size; selection or bucket tolerance; and polygon sides. The Eraser is "+
			"the exception: right-click it for its size, because double-clicking it clears the picture. Double-click Type "+
			"to choose the font.\n"+
			"\n"+
			"Drawing\n"+
			"Drag on the card with Pencil, Brush, Eraser, Line, Spray, a shape, Smudge or Stamp. Shift constrains lines and "+
			"shape proportions, and keeps freehand strokes horizontal or vertical. Several freehand and fill tools use "+
			"Option/Alt as a temporary eyedropper. Brush and Spray use Command/Ctrl for the background colour, while shape "+
			"tools use it to reverse the pattern. Command/Ctrl-click with Pencil toggles FatBits. For Stamp, "+
			"Option/Alt-click first to set the source, then paint elsewhere. Click and drag with Type to make an input "+
			"area, type the text, then click outside it to place the text.\n"+
			"Use Paint > Antialias for smooth edges. The Options menu provides grid snapping, centred or filled shapes, "+
			"multiple drawing and symmetry. Paint > Zoom or FatBits enlarges pixels for detailed work; hold Space and drag "+
			"to pan a zoomed picture.\n"+
			"\n"+
			"Selecting and moving a picture\n"+
			"Drag with Select for a rectangle, Lasso for a free outline, or Magic Wand for adjoining similar colours. "+
			"Shift-drag outside an existing selection to add an area; Option/Alt-drag there to subtract one. Drag inside "+
			"the floating selection to move it, or Option/Alt-drag to leave a copy. Drag the square handles to scale it and "+
			"the round handle to rotate it; Shift preserves proportions or snaps rotation to 15-degree steps. Clicking "+
			"outside the selection or choosing another tool commits it. Delete/Clear Selection removes it.\n"+
			"Cut, Copy and Paste Picture exchange the selection with the system clipboard. Paint menu commands can resize, "+
			"rotate, flip, fill, filter and blend the floating selection. Use Undo Paint (Command/Ctrl-Z) and Redo Paint "+
			"(Shift-Command/Ctrl-Z) whenever necessary."));
		topics.add(new Topic(TOPIC_PAINT_FEATURES,
			"Paint feature list",
			"Selection tools\n"+
			"  Select — selects a rectangular area.\n"+
			"  Lasso — selects an irregular object while excluding connected white space around it.\n"+
			"  Magic Wand — selects adjoining similar colours; Option/Alt selects matching colours across the picture.\n"+
			"\n"+
			"Drawing and text tools\n"+
			"  Pencil — draws one-pixel lines; clicking a foreground-coloured pixel draws with the background colour.\n"+
			"  Brush — paints soft or hard strokes with adjustable size, opacity, spacing and stabilisation.\n"+
			"  Eraser — erases to transparency or reveals the background picture.\n"+
			"  Line — draws a straight line; Shift constrains its angle.\n"+
			"  Spray — sprays foreground or background colour with an adjustable radius.\n"+
			"  Rectangle / Rounded Rectangle / Oval — draws outlined or filled geometric shapes.\n"+
			"  Curve — draws a freehand path, open or filled.\n"+
			"  Type — places styled, coloured text using the current font settings.\n"+
			"  Regular Polygon — draws a regular polygon with an adjustable number of sides.\n"+
			"  Free Polygon — places vertices one click at a time; double-click or click the first point to close it.\n"+
			"  Eyedropper — picks a colour from the picture.\n"+
			"  Smudge — drags and mixes existing pixels with adjustable size, strength, hardness and spacing.\n"+
			"  Stamp — clone-paints pixels from an Option/Alt-clicked source.\n"+
			"  Bucket — fills a connected colour area with a colour, pattern, gradient or texture.\n"+
			"\n"+
			"Colour and fill controls\n"+
			"  Foreground/background colours; reusable black-and-white patterns; linear and radial gradients; built-in or "+
			"imported textures; drawing transparency; antialiasing. Patterns and textures can also be made from a selection "+
			"or imported and exported.\n"+
			"\n"+
			"Selection and image editing\n"+
			"  Cut/copy/paste and clear; select all; add to or subtract from a selection; feather, grow, shrink, reverse and "+
			"expand; scale, rotate, slant, distort, stretch and perspective; horizontal/vertical flip; opaque/transparent "+
			"conversion; fill, invert, pickup, darken, lighten, tint and blend; colour conversion, filters and blending modes.\n"+
			"\n"+
			"Layers and drawing aids\n"+
			"  Card Paint and Background Paint are the base pictures. Additional layers can be created, duplicated, deleted, "+
			"renamed, reordered, merged and flattened. Each added layer supports visibility, opacity, alpha lock, blending "+
			"mode and clipping to the layer below.\n"+
			"  Zoom from 25% to 3200% (plus custom zoom), FatBits, grid snapping with adjustable spacing, drawing from the "+
			"centre, filled shapes, repeated drawing with adjustable spacing, vertical/horizontal/quadrant/radial symmetry "+
			"with an adjustable centre, multiple Undo/Redo steps, and optional HyperCard Power Keys."));
		topics.add(new Topic(TOPIC_FORKS,
			"Resource forks on Windows and Linux",
			"Classic Macintosh files have two parts: the data fork and the resource fork. Stacks, 68K programs, fonts, "+
			"sounds and XCMDs keep most of what matters in the resource fork, and the Finder keeps a file's type and "+
			"creator codes (its Finder info) beside it in the file system.\n\n"+
			"On Linux and for ordinary Windows files, HyperZebra looks for the resource fork and Finder info in an "+
			"AppleDouble sidecar: a file named \"._\" plus the file's own name, in the same folder "+
			"(for \"MyStack\" the sidecar is \"._MyStack\"). The sidecar holds the Finder info and the resource fork; "+
			"the data fork stays in the original file. Windows also reads and updates the AFP_Resource and AFP_AfpInfo "+
			"NTFS streams when a Mac copied the file to an SMB share with those streams.\n\n"+
			"HyperZebra also understands the two other ways a Mac file can carry its fork:\n"+
			"  - A \"__MACOSX\" folder, which the Finder's Compress command puts into a zip. Expanded by Windows Explorer "+
			"it sits beside the files; HyperZebra finds \"__MACOSX/._MyStack\" (and deeper mirrors) on its own, and a .zip "+
			"opened in HyperZebra is expanded with the sidecars put in place.\n"+
			"  - MacBinary (.bin) files, which pack both forks and the Finder info into one file.\n\n"+
			"New files HyperZebra writes on Windows and Linux - a saved stack, a document made by a 68K program with a resource "+
			"fork - get a sidecar automatically. Existing Windows files with AFP streams keep using those streams. On macOS "+
			"the file system's own fork is used instead, unless HyperZebra "+
			"is started with -Dhz.appledouble=true."));
		topics.add(new Topic(TOPIC_APPLEDOUBLE,
			"Convert to AppleDouble (Tools menu)",
			"Tools > Convert to AppleDouble… (macOS only) writes the \"._name\" sidecar for files that keep their resource "+
			"fork and Finder info in the Mac file system, so the files can be taken to Windows or Linux and still open in "+
			"HyperZebra there.\n\n"+
			"Choose one or more files or folders. A folder is walked completely, including its subfolders. For each file "+
			"with a resource fork or Finder info, a \"._name\" file is written in the same folder (an existing one is "+
			"replaced; one that is already up to date is left alone). A folder's own Finder info - a custom icon, a label - "+
			"gets a sidecar too. Files with neither are skipped: plain text, PNG, JPEG and other host files need nothing. "+
			"Existing \"._\" files, .DS_Store, __MACOSX folders and symbolic links are ignored. The original files are not "+
			"changed in any way. A summary is shown when the run is over.\n\n"+
			"The sidecar is a snapshot: edit the stack or program again on the Mac and the sidecar is stale. Run the "+
			"conversion right before the transfer.\n\n"+
			"Then copy the files together with their \"._\" companions. The Finder hides names starting with a dot: "+
			"press Cmd-Shift-. in a Finder window to see them, or copy the whole folder, which takes the sidecars along. "+
			"Zip made by the Finder's Compress command carries the forks as a __MACOSX folder anyway; the sidecars are "+
			"for everything else - scp, rsync, a web upload, a cloud drive, a USB stick formatted for Windows.\n\n"+
			"The same sidecars are also what HyperZebra itself writes on Windows and Linux, and what it reads back on "+
			"macOS when no native fork is present, so a folder can move back and forth."));
		topics.add(new Topic("disk-images",
			"Disk images (.img, .dsk, .smi, .image)",
			"Open or drop a classic Macintosh disk image and HyperZebra mounts it for Mac programs: Disk Copy 4.2 images "+
			"(.image, .dc42), Disk Copy 6 images (.img), self-mounting images (.smi, also as .smi.bin) and bare HFS, HFS Plus or MFS "+
			"volumes (.dsk, .hfv, an HFS CD saved as .toast or .iso), .dmg files (stored, zlib or bzip2 compressed) and ISO 9660 CDs "+
			"(.iso, .cdr). The kind is found from the file's contents, so the name "+
			"does not matter.\n\n"+
			"The disk is locked, like a CD: programs can open and read everything on it but cannot change it, and nothing is "+
			"ever written back into the image. Programs started afterwards see the disk among their volumes, and programs "+
			"already running see it arrive as if a disk had been inserted. When the disk holds a single program (or a single "+
			"stack), the dialog offers to launch it; Show Contents opens the folder that holds the disk's files.\n\n"+
			"File > Disk Images lists the mounted disks. Unmount takes a disk away from programs launched from then on (the ones "+
			"running keep it until they quit) and deletes the files HyperZebra extracted for it - at once, or when the last "+
			"program using the disk quits. Quitting HyperZebra deletes them too, and Delete Extracted Disk Data… unmounts every "+
			"disk and deletes all of them (it shows how much room they take); the image files themselves are never touched. Extract Disk Image to Folder… writes the contents of an image into a new folder "+
			"beside it as ordinary files, with their resource forks and Finder info, which you can change.\n\n"+
			"Not supported yet: .dmg files compressed with LZFSE or LZMA (made on Mac OS X 10.11 and later) and APFS volumes."));
		topics.add(new Topic(TOPIC_TRANSFER,
			"Taking files from a Mac to Windows or Linux",
			"1. On the Mac, open the launcher and choose Tools > Convert to AppleDouble…, then pick the stacks, programs "+
			"or the folder that holds them.\n"+
			"2. Copy the folder (or the files together with their \"._\" companions) to the other computer by any means.\n"+
			"3. On Windows or Linux, open the files in HyperZebra as usual. The sidecar beside each file supplies its "+
			"resource fork and its type and creator.\n\n"+
			"Checks when a file does not open:\n"+
			"  - Is the \"._name\" file in the same folder as the file, with exactly the same name after \"._\"? A file "+
			"renamed on the other side needs its sidecar renamed as well (HyperZebra renames both when it does the renaming).\n"+
			"  - Was the file zipped by the Finder? Then the forks are in the __MACOSX folder of the expanded zip - keep that "+
			"folder, or open the .zip itself in HyperZebra.\n"+
			"  - Files that came without a fork at all - from an old FTP transfer, say - cannot be repaired by the conversion; "+
			"the fork was lost on the way. A MacBinary (.bin) or a StuffIt archive of the original would still have it.\n\n"+
			"The other direction needs nothing: a file written by HyperZebra on Windows or Linux, with its sidecar, opens on "+
			"macOS as it is."));
		return topics;
	}
}
