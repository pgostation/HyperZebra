import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Shape Info: a shape's properties. As in Button Info and Field Info, each change goes to the card at once and Close ends the
 * dialog; Edit > Undo takes back everything changed while it was open (one step). The preview beside the tabs shows the
 * shape scaled to fit, with its effects and filters.
 */
final class ShapeInfoPanel extends JPanel {
    final OShape shape;
    final JLabel identity;
    final JTabbedPane tabs = new JTabbedPane();
    final JTextField name;
    final javax.swing.JComboBox<PanelChooser.Item> panel;
    final JSpinner left, top, width, height, strokeWidth, fontSize, sides;
    final ColorButton fill, stroke, gradientFrom, gradientTo;
    /** Fill kinds: 0 colour, 1 linear gradient, 2 radial gradient, 3 image. */
    final JComboBox<String> fillKind;
    final ShapeFilterPanel.ValueSlider angle, radius, opacity, rotation;
    final JCheckBox visible, bold, stretchTexture;
    final JTextArea text, path;
    final JComboBox<String> font;
    final JPanel outlineRows = rows(), shadowRows = rows();
    final ArrayList<EffectRow> outlines = new ArrayList<>(), shadows = new ArrayList<>();
    final ShapeFilterPanel filters;
    final Preview preview = new Preview();
    final JButton script, editPoints, convert;
    /** Script… was clicked: the dialog closes and the script editor opens (as in Button Info). */
    boolean scriptRequested;
    /** Edit Points… was clicked: the dialog closes and the curve editor opens. */
    boolean pathEditRequested;
    /** Panel Info… beside the Panel pop-up: Shape Info closes and the panel's info opens. */
    boolean panelInfoRequested;
    /** The gradient's direction or size was edited (otherwise an imported gradient keeps its own points). */
    private boolean gradientTouched;
    /** Convert was clicked: the shape is a curve now and the dialog writes nothing more. */
    private boolean converted;
    private final int originalFillKind;
    byte[] texture;
    private final Timer refresh = new Timer(60, e -> applyLive());
    private final Runnable changed = refresh::restart;

    static String kindName(String kind) {
        return switch (kind) {
            case "rectangle" -> International.t("Rectangle");
            case "line" -> International.tc("shape", "Line");
            case "ellipse" -> International.tc("shape", "Oval");
            case "regularPolygon" -> International.tc("shape", "Regular polygon");
            case "polygon" -> International.tc("shape", "Polygon");
            case "freehand" -> International.tc("shape", "Freehand");
            case "bezier" -> International.tc("shape", "Bézier curve");
            case "text" -> International.t("Text");
            default -> kind;
        };
    }
    ShapeInfoPanel(OShape shape) {
        super(new BorderLayout(12, 12));
        this.shape = shape;
        refresh.setRepeats(false);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        OCardBase owner = (OCardBase) shape.parent;
        String scope = owner instanceof OCard ? International.tc("shape", "Card") : International.tc("shape", "Background");
        identity = new JLabel(scope + "  ·  " + International.tc("shape", "Shape number") + " " + shape.getNumber()
            + "  ·  " + International.tc("shape", "Shape ID") + " " + shape.id);
        identity.setToolTipText(International.tc("shape", "The number follows shape order within its owner. The ID stays the same."));
        JPanel titles = new JPanel(new BorderLayout(0, 4));
        JLabel kind = new JLabel(kindName(shape.kind));
        kind.setFont(kind.getFont().deriveFont(Font.BOLD, 16f));
        titles.add(kind, BorderLayout.NORTH); titles.add(identity, BorderLayout.SOUTH);
        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.add(titles, BorderLayout.CENTER);
        script = new JButton(International.t("Script…"));
        script.setToolTipText(International.tc("shape", "Close Shape Info and edit the shape's script (mouseUp and other messages)."));
        script.addActionListener(e -> { scriptRequested = true; close(); });
        JPanel scriptButton = new JPanel(new GridBagLayout()); // centred beside the two heading lines
        scriptButton.add(script);
        heading.add(scriptButton, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER); add(preview, BorderLayout.EAST);

        // Shape: name, place and size.
        name = new JTextField(shape.name, 24); panel = PanelChooser.create(shape);
        left = integer(shape.left, null); top = integer(shape.top, null);
        width = integer(shape.width, 1); height = integer(shape.height, 1);
        sides = integer(shape.sides, 3);
        visible = new JCheckBox(International.tc("shape", "Show shape"), shape.getVisible());
        opacity = new ShapeFilterPanel.ValueSlider(0, 100, Math.round(shape.opacity * 100), changed);
        rotation = new ShapeFilterPanel.ValueSlider(-180, 180, Math.round(shape.rotation), changed);
        rotation.setToolTipText(International.tc("shape", "Also drag the round handle above the selected shape with the Shape tool."));
        Form general = new Form();
        general.row(International.t("Name"), name);
        // The panel and, for a shape in one (as an imported SVG's are), the way to the whole group: Panel Info lists its members.
        JButton panelInfo = new JButton(International.t("Panel Info…"));
        panelInfo.setEnabled(shape.panel() != null);
        panel.addActionListener(e -> panelInfo.setEnabled(PanelChooser.selected(panel) != null));
        panelInfo.addActionListener(e -> { panelInfoRequested = true; close(); });
        JPanel panelRow = new JPanel(new BorderLayout(6, 0));
        panelRow.add(panel, BorderLayout.CENTER); panelRow.add(panelInfo, BorderLayout.EAST);
        general.row(International.tc("shape", "Panel"), panelRow);
        general.row(International.tc("shape", "Position"), pair(International.t("Left"), left, International.tc("shape", "Top"), top));
        general.row(International.tc("shape", "Size"), pair(International.tc("shape", "Width"), width, International.tc("shape", "Height"), height));
        if (shape.kind.equals("regularPolygon")) general.row(International.tc("shape", "Sides"), sides);
        general.row(International.tc("shape", "Rotation °"), rotation);
        general.row(International.tc("shape", "Opacity %"), opacity);
        general.row("", visible);
        tabs.addTab(International.tc("shape", "Shape"), general);

        // Fill and stroke.
        fill = new ColorButton(shape.fillColor); stroke = new ColorButton(shape.strokeColor);
        strokeWidth = decimal(shape.strokeWidth, 0.0);
        gradientFrom = new ColorButton(shape.gradientStart != null ? shape.gradientStart : shape.fillColor != null ? shape.fillColor : Color.WHITE);
        gradientTo = new ColorButton(shape.gradientEnd != null ? shape.gradientEnd : new Color(0x1976d2));
        fillKind = new JComboBox<>(new String[]{International.tc("shape fill", "Color"), International.tc("shape", "Linear gradient"), International.tc("shape", "Radial gradient"), International.tc("shape", "Image")});
        originalFillKind = shape.texturePng != null ? 3 : shape.gradientStart != null && shape.gradientEnd != null ? (shape.radialGradient ? 2 : 1) : 0;
        fillKind.setSelectedIndex(originalFillKind);
        double a = Math.toDegrees(Math.atan2(shape.gradientY2 - shape.gradientY1, shape.gradientX2 - shape.gradientX1));
        angle = new ShapeFilterPanel.ValueSlider(-180, 180, Math.round(a), () -> { gradientTouched = true; changed.run(); });
        radius = new ShapeFilterPanel.ValueSlider(10, 150, shape.radialGradient ? Math.round(shape.gradientX2 * 100) : 70, () -> { gradientTouched = true; changed.run(); });
        texture = shape.texturePng;
        JLabel textureName = new JLabel(texture == null ? International.tc("shape", "No image") : International.tc("shape", "Image set"));
        JButton chooseTexture = new JButton(International.tc("shape", "Choose image…"));
        JButton clearTexture = new JButton(International.tc("shape", "Remove"));
        clearTexture.setEnabled(texture != null);
        stretchTexture = new JCheckBox(International.tc("shape", "Stretch image to fit"), shape.stretchTexture);
        chooseTexture.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try {
                byte[] bytes = Files.readAllBytes(chooser.getSelectedFile().toPath());
                if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) throw new IllegalArgumentException(International.tc("shape", "Choose an image file."));
                texture = bytes; textureName.setText(chooser.getSelectedFile().getName()); clearTexture.setEnabled(true); changed.run();
            } catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), International.tc("shape", "Texture"), JOptionPane.ERROR_MESSAGE); }
        });
        clearTexture.addActionListener(e -> { texture = null; textureName.setText(International.tc("shape", "No image")); clearTexture.setEnabled(false); changed.run(); });
        // Only the chosen kind's settings are shown (and take room).
        JPanel fillDetail = new JPanel(new BorderLayout());
        // One gradient panel for both kinds (a component has one parent): the angle for linear, the size for radial.
        JLabel fromLabel = new JLabel(), toLabel = new JLabel();
        JPanel colors = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        colors.add(fromLabel); colors.add(gradientFrom); colors.add(toLabel); colors.add(gradientTo);
        JPanel angleRow = labeled(International.tc("shape", "Angle °"), angle), sizeRow = labeled(International.tc("shape", "Size %"), radius);
        JPanel gradient = rows();
        for (JComponent c : new JComponent[]{colors, angleRow, sizeRow}) { c.setAlignmentX(LEFT_ALIGNMENT); gradient.add(c); }
        Form image = new Form(0); image.row(International.tc("shape", "Image"), textureName); image.row("", pair("", chooseTexture, "", clearTexture)); image.row("", stretchTexture);
        Component[] fillCards = {pair("", fill, "", new JLabel()), gradient, gradient, image};
        Runnable showFill = () -> {
            int k = fillKind.getSelectedIndex();
            fromLabel.setText(k == 2 ? International.tc("shape", "Center") : International.tc("shape", "From")); toLabel.setText(k == 2 ? International.tc("shape", "Edge") : International.tc("shape", "To"));
            angleRow.setVisible(k == 1); sizeRow.setVisible(k == 2);
            fillDetail.removeAll(); fillDetail.add(fillCards[k]); fillDetail.revalidate(); fillDetail.repaint();
        };
        fillKind.addActionListener(e -> { showFill.run(); changed.run(); });
        showFill.run();
        // Picking a single colour means a plain fill (it replaces an imported gradient, as the swatch always did).
        fill.onChange = () -> { if (fillKind.getSelectedIndex() != 0) fillKind.setSelectedIndex(0); changed.run(); };
        stroke.onChange = changed; gradientFrom.onChange = changed; gradientTo.onChange = changed;
        Form paint = new Form();
        paint.row(International.tc("shape", "Fill"), fillKind);
        paint.row("", fillDetail);
        paint.row(International.tc("shape", "Stroke"), pair(International.tc("shape", "Color"), stroke, International.tc("shape stroke", "Width"), strokeWidth));
        tabs.addTab(International.tc("shape", "Fill & Stroke"), paint);

        // Text shapes.
        text = new JTextArea(shape.text, 3, 26); text.setLineWrap(true); text.setWrapStyleWord(true);
        font = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        font.setEditable(true); font.setSelectedItem(shape.fontFamily);
        fontSize = integer(shape.fontSize, 1);
        bold = new JCheckBox(International.t("Bold"), shape.bold);
        convert = new JButton(International.tc("shape", "Convert text to Bézier outlines"));
        convert.addActionListener(e -> {
            finish();
            if (shape.kind.equals("text")) { shape.convertTextToBezier(); ShapeTool.refresh(); }
            converted = true; // the text settings no longer describe the shape
            close();
        });
        if (shape.kind.equals("text")) {
            Form lettering = new Form();
            lettering.row(International.t("Text"), new JScrollPane(text));
            lettering.row(International.t("Font"), font);
            lettering.row(International.tc("shape font", "Size"), pair("", fontSize, "", bold));
            lettering.row("", convert);
            lettering.row("", new JLabel(International.tc("shape", "Converted text can be edited as a curve.")));
            tabs.addTab(International.t("Text"), lettering);
        }

        // Outlines and shadows.
        for (OShape.Outline o : shape.outlines) addOutline(o.width, o.color);
        for (OShape.Shadow s : shape.shadows) addShadow(s.dx, s.dy, s.blur, s.spread, s.color);
        JPanel effects = rows();
        effects.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        effects.add(effectSection(International.tc("shape", "Outlines"), outlineRows, () -> addOutline(3, Color.BLACK)));
        effects.add(Box.createVerticalStrut(12));
        effects.add(effectSection(International.tc("shape", "Shadows"), shadowRows, () -> addShadow(4, 4, 2, widestOutline(), new Color(0, 0, 0, 160))));
        JScrollPane effectScroll = new JScrollPane(effects);
        effectScroll.setBorder(BorderFactory.createEmptyBorder());
        effectScroll.getVerticalScrollBar().setUnitIncrement(20);
        tabs.addTab(International.tc("shape", "Effects"), effectScroll);

        filters = new ShapeFilterPanel(shape.svgFilter, changed);
        tabs.addTab(International.tc("shape", "Filters"), filters);

        // Curve.
        path = new JTextArea(shape.pathData, 6, 32);
        path.setLineWrap(true); path.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        editPoints = new JButton(International.tc("shape", "Edit Points…"));
        editPoints.setToolTipText(International.tc("shape", "Close Shape Info and drag the curve's anchor and control points."));
        editPoints.addActionListener(e -> { pathEditRequested = true; close(); });
        Form curves = new Form();
        curves.row("", editPoints);
        if (!shape.pathData.isEmpty()) {
            curves.row(International.tc("shape", "Path"), new JScrollPane(path));
            curves.row("", new JLabel(International.tc("shape", "SVG path data (advanced)")));
        } else {
            curves.row("", new JLabel(International.tc("shape", "Editing points converts this shape to a Bézier curve.")));
        }
        tabs.addTab(International.tc("shape", "Curve"), curves);

        for (JSpinner sp : new JSpinner[]{left, top, width, height, strokeWidth, fontSize, sides}) sp.addChangeListener(e -> changed.run());
        for (javax.swing.text.JTextComponent t : new javax.swing.text.JTextComponent[]{name, text, path})
            t.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { changed.run(); }
            });
        font.addActionListener(e -> changed.run());
        panel.addActionListener(e -> changed.run());
        for (JCheckBox c : new JCheckBox[]{bold, stretchTexture, visible}) c.addItemListener(e -> changed.run());
        setPreferredSize(new Dimension(880, 500));
        preview.setDraft(shape); // nothing is written until something is changed
    }

    /** Closes the Shape Info dialog (Script…, Panel Info…, Edit Points… and Convert). */
    private void close() {
        JOptionPane pane = (JOptionPane) SwingUtilities.getAncestorOfClass(JOptionPane.class, this);
        if (pane != null) pane.setValue(JOptionPane.CLOSED_OPTION);
    }

    /** When the dialog closes: numbers still being typed count, as in Button Info. */
    void finish() {
        refresh.stop();
        for (JSpinner s : new JSpinner[]{left, top, width, height, strokeWidth, fontSize, sides}) commit(s);
        for (EffectRow row : outlines) for (JSpinner s : new JSpinner[]{row.a, row.b, row.c, row.d}) commit(s);
        for (EffectRow row : shadows) for (JSpinner s : new JSpinner[]{row.a, row.b, row.c, row.d}) commit(s);
        applyLive();
    }
    private static void commit(JSpinner s) {
        try { s.commitEdit(); } catch (java.text.ParseException e) { /* not a number: the last value stays */ }
    }

    /** Writes the settings into the shape on the card. */
    void applyLive() {
        if (converted) return;
        String newPath = path.getText().trim();
        try { applyTo(shape); }
        catch (RuntimeException e) { return; } // settings that cannot be used yet leave the shape as it was
        shape.repaintView(); ShapeTool.refresh(); preview.setDraft(shape);
        // A new size scales the curve and new text fits its size: the fields follow the shape.
        if (integerValue(width) != shape.width) width.setValue(shape.width);
        if (integerValue(height) != shape.height) height.setValue(shape.height);
        if (!shape.pathData.isEmpty() && !shape.pathData.equals(newPath)) path.setText(shape.pathData);
    }

    /** Writes the settings into a shape. */
    private void applyTo(OShape s) {
        int w = integerValue(width), h = integerValue(height);
        boolean textChanged = shape.kind.equals("text") && (!shape.text.equals(text.getText())
            || !shape.fontFamily.equals(font.getSelectedItem()) || shape.fontSize != integerValue(fontSize) || shape.bold != bold.isSelected());
        boolean sizeUnchanged = w == shape.width && h == shape.height;
        s.name = name.getText(); s.setPanel(PanelChooser.selected(panel));
        s.text = text.getText(); s.fontFamily = String.valueOf(font.getSelectedItem());
        s.fontSize = integerValue(fontSize); s.bold = bold.isSelected(); s.sides = integerValue(sides);
        s.fillColor = fill.color; s.strokeColor = stroke.color; s.strokeWidth = floatValue(strokeWidth);
        int kind = fillKind.getSelectedIndex();
        if (kind == 1 || kind == 2) {
            s.gradientStart = gradientFrom.color; s.gradientEnd = gradientTo.color; s.radialGradient = kind == 2;
            if (gradientTouched || kind != originalFillKind) {
                if (kind == 1) {
                    double r = Math.toRadians(angle.value()), dx = Math.cos(r) / 2, dy = Math.sin(r) / 2;
                    s.gradientX1 = (float) (0.5 - dx); s.gradientY1 = (float) (0.5 - dy); s.gradientX2 = (float) (0.5 + dx); s.gradientY2 = (float) (0.5 + dy);
                } else {
                    if (kind != originalFillKind) { s.gradientX1 = 0.5f; s.gradientY1 = 0.5f; }
                    s.gradientX2 = (float) (radius.value() / 100);
                }
            }
        } else { s.gradientStart = null; s.gradientEnd = null; }
        byte[] image = kind == 3 ? texture : null;
        if (image != s.texturePng) s.setTexture(image);
        s.stretchTexture = stretchTexture.isSelected();
        s.pathData = path.getText().trim();
        s.outlines.clear(); for (EffectRow r : outlines) s.outlines.add(new OShape.Outline(floatValue(r.a), r.color.color));
        s.shadows.clear(); for (EffectRow r : shadows) s.shadows.add(new OShape.Shadow(floatValue(r.a), floatValue(r.b), floatValue(r.c), floatValue(r.d), r.color.color));
        s.svgFilter = filters.result();
        s.opacity = (float) (opacity.value() / 100);
        // The slider keeps whole degrees; a finer angle dragged on the card stays unless the slider was moved.
        if (Math.round(shape.rotation) != (int) rotation.value()) s.rotation = OShape.normalizeAngle(rotation.value());
        s.setSize(w, h); s.setTopLeft(integerValue(left), integerValue(top)); s.setVisible(visible.isSelected());
        if (textChanged && sizeUnchanged) {
            Rectangle2D bounds = s.naturalTextBounds();
            s.setSize(Math.max(1, (int)Math.ceil(bounds.getWidth())), Math.max(1, (int)Math.ceil(bounds.getHeight())));
        }
    }

    /** The shape on a checkerboard, scaled to fit, with its effects and filters. */
    static final class Preview extends JPanel {
        OShape draft;
        Preview() {
            setPreferredSize(new Dimension(250, 250));
            setToolTipText(International.tc("shape", "The shape scaled to fit, with its effects and filters."));
            getAccessibleContext().setAccessibleName(International.t("Preview"));
        }
        void setDraft(OShape s) { draft = s; repaint(); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight() - 22;
            for (int y = 0; y < h; y += 10) for (int x = 0; x < w; x += 10) {
                g.setColor(((x + y) / 10) % 2 == 0 ? Color.WHITE : new Color(228, 228, 228)); g.fillRect(x, y, Math.min(10, w - x), Math.min(10, h - y));
            }
            g.setColor(new Color(0, 0, 0, 60)); g.drawRect(0, 0, w - 1, h - 1);
            g.setColor(UIManager.getColor("Label.disabledForeground") == null ? Color.GRAY : UIManager.getColor("Label.disabledForeground"));
            g.setFont(getFont() == null ? g.getFont() : getFont().deriveFont(11f));
            String label = International.t("Preview");
            g.drawString(label, (w - g.getFontMetrics().stringWidth(label)) / 2, h + 16);
            if (draft != null && draft.width > 0 && draft.height > 0) {
                int pad = draft.padding();
                double scale = Math.min(1, Math.min((w - 16.0) / (draft.width + 2 * pad), (h - 16.0) / (draft.height + 2 * pad)));
                g.clipRect(1, 1, w - 2, h - 2);
                g.translate((w - draft.width * scale) / 2, (h - draft.height * scale) / 2);
                g.scale(scale, scale);
                if (!draft.getVisible()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
                draft.render(g);
            }
            g.dispose();
        }
    }

    void addOutline(float width, Color color) { addEffect(new EffectRow(false, width, 0, 0, 0, color), outlines, outlineRows); }
    void addShadow(float x, float y, float blur, float spread, Color color) { addEffect(new EffectRow(true, x, y, blur, spread, color), shadows, shadowRows); }
    /** A new shadow is as wide as the widest outline, so it falls behind the whole outlined text. */
    private float widestOutline() { float w = 0; for (EffectRow r : outlines) w = Math.max(w, floatValue(r.a)); return w; }
    private void addEffect(EffectRow row, ArrayList<EffectRow> list, JPanel parent) {
        list.add(row); parent.add(row);
        row.remove.addActionListener(e -> { list.remove(row); parent.remove(row); parent.revalidate(); parent.repaint(); changed.run(); });
        for (JSpinner sp : new JSpinner[]{row.a, row.b, row.c, row.d}) sp.addChangeListener(e -> changed.run());
        row.color.onChange = changed;
        parent.revalidate(); parent.repaint();
        changed.run();
    }
    private static JPanel effectSection(String title, JPanel content, Runnable add) {
        JPanel section = new JPanel(new BorderLayout(0, 6)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        section.setAlignmentX(LEFT_ALIGNMENT);
        JPanel heading = new JPanel(new BorderLayout());
        JLabel label = new JLabel(title); label.setFont(label.getFont().deriveFont(Font.BOLD));
        JButton button = new JButton(International.tc("shape", "Add")); button.addActionListener(e -> add.run());
        heading.add(label, BorderLayout.WEST); heading.add(button, BorderLayout.EAST);
        section.add(heading, BorderLayout.NORTH); section.add(content, BorderLayout.CENTER);
        return section;
    }
    static final class EffectRow extends JPanel {
        final JSpinner a, b, c, d;
        final ColorButton color;
        final JButton remove = new JButton("−");
        EffectRow(boolean shadow, float x, float y, float blur, float spread, Color initial) {
            super(new FlowLayout(FlowLayout.LEFT, 5, 4));
            a = decimal(x, shadow ? null : 0.0); b = decimal(y, null); c = decimal(blur, 0.0); d = decimal(spread, 0.0);
            color = new ColorButton(initial); color.setPreferredSize(new Dimension(shadow ? 64 : 88, 28));
            add(new JLabel(shadow ? International.tc("shape", "X") : International.tc("shape stroke", "Width"))); add(a);
            if (shadow) {
                add(new JLabel(International.tc("shape", "Y"))); add(b);
                JLabel width = new JLabel(International.tc("shape stroke", "Width")); add(width); add(d);
                width.setToolTipText(International.tc("shape", "Widens the shadow on every side, like an outline of this width."));
                add(new JLabel(International.t("Blur"))); add(c);
            }
            add(color); add(remove); remove.setToolTipText(International.tc("shape", "Remove layer"));
            setAlignmentX(LEFT_ALIGNMENT); setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        }
        void commit() throws java.text.ParseException { a.commitEdit(); b.commitEdit(); c.commitEdit(); d.commitEdit(); }
    }
    private static JPanel labeled(String title, Component c) {
        JPanel p = new JPanel(new BorderLayout(8, 0)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        p.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JLabel l = new JLabel(title); l.setPreferredSize(new Dimension(80, l.getPreferredSize().height));
        p.add(l, BorderLayout.WEST); p.add(c, BorderLayout.CENTER); return p;
    }
    private static JPanel rows() { JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); return p; }
    private static JSpinner integer(int value, Integer min) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Integer.valueOf(value), min, null, Integer.valueOf(1)));
        s.setPreferredSize(new Dimension(86, 28)); return s;
    }
    private static JSpinner decimal(float value, Double min) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(Double.valueOf(value), min, null, Double.valueOf(1)));
        s.setEditor(new JSpinner.NumberEditor(s, "0.##")); s.setPreferredSize(new Dimension(68, 28)); return s;
    }
    private static int integerValue(JSpinner s) { return ((Number)s.getValue()).intValue(); }
    private static float floatValue(JSpinner s) { return ((Number)s.getValue()).floatValue(); }
    private static JPanel pair(String a, Component first, String b, Component second) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        if (!a.isEmpty()) p.add(new JLabel(a)); p.add(first);
        if (!b.isEmpty()) p.add(new JLabel(b)); p.add(second); return p;
    }
    private static final class Form extends JPanel {
        int row;
        Form() { this(12); }
        Form(int margin) { super(new GridBagLayout()); setBorder(BorderFactory.createEmptyBorder(margin, margin, margin, margin)); }
        void row(String title, Component field) {
            GridBagConstraints c = new GridBagConstraints(); c.gridy = row++; c.insets = new Insets(0, 0, 10, 12);
            c.anchor = GridBagConstraints.LINE_END; add(new JLabel(title), c);
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 10, 0); add(field, c);
            // A trailing spacer keeps the form anchored to the top as the dialog grows.
            c.gridy = row; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
            if (spacer != null) remove(spacer); spacer = new JPanel(); add(spacer, c);
        }
        JPanel spacer;
    }

    /** Quick swatch palette, including no paint, with an optional full color chooser. */
    static final class ColorButton extends JButton {
        Color color;
        boolean modified;
        /** Called when the colour is chosen (not for the initial one). */
        Runnable onChange;
        static final int[] COLORS = {0x000000,0x444444,0x777777,0xaaaaaa,0xcccccc,0xeeeeee,0xffffff,
            0x7f1d1d,0xc62828,0xef5350,0xff8a80,0xf8bbd0,0xc2185b,0x880e4f,
            0x7c2d12,0xe65100,0xff9800,0xffcc80,0xffeb3b,0xfff59d,0x827717,
            0x1b5e20,0x388e3c,0x66bb6a,0xa5d6a7,0x00897b,0x80cbc4,0x004d40,
            0x0d47a1,0x1976d2,0x42a5f5,0x90caf9,0x00acc1,0x80deea,0x006064,
            0x311b92,0x673ab7,0x9575cd,0xd1c4e9,0x8e24aa,0xce93d8,0x4a148c};
        ColorButton(Color initial) {
            setColor(initial); modified = false; setHorizontalAlignment(LEFT); setPreferredSize(new Dimension(130, 28));
            addActionListener(e -> palette().show(this, 0, getHeight()));
            getAccessibleContext().setAccessibleName(International.tc("shape", "Choose color"));
        }
        void setColor(Color value) {
            color = value; modified = true;
            setIcon(new Icon() {
                public int getIconWidth() { return 28; } public int getIconHeight() { return 16; }
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    for (int dy=0; dy<16; dy+=4) for (int dx=0; dx<28; dx+=4) {
                        g.setColor(((dx+dy)/4)%2==0 ? Color.WHITE : new Color(205,205,205)); g.fillRect(x+dx,y+dy,4,4);
                    }
                    if (color != null) { g.setColor(color); g.fillRect(x,y,28,16); }
                    else { g.setColor(Color.RED); g.drawLine(x,y+15,x+27,y); }
                    g.setColor(Color.GRAY); g.drawRect(x,y,27,15);
                }
            });
            setText(value == null ? International.t("None") : "▾");
            setToolTipText(value == null ? International.tc("shape", "No color") : String.format("#%08X", value.getRGB()));
            repaint();
            if (onChange != null) onChange.run();
        }
        JPopupMenu palette() {
            JPopupMenu popup = new JPopupMenu();
            JPanel swatches = new JPanel(new GridLayout(6, 7, 3, 3));
            swatches.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            for (int rgb : COLORS) {
                Color sample = new Color(rgb);
                JButton button = new JButton(new Icon() {
                    public int getIconWidth() { return 22; } public int getIconHeight() { return 22; }
                    public void paintIcon(Component c, Graphics g, int x, int y) { g.setColor(sample); g.fillRect(x,y,22,22); }
                });
                button.setMargin(new Insets(2,2,2,2)); button.setToolTipText(String.format("#%06X", rgb));
                button.getAccessibleContext().setAccessibleName(button.getToolTipText());
                button.addActionListener(e -> { setColor(sample); popup.setVisible(false); }); swatches.add(button);
            }
            popup.add(swatches); popup.addSeparator();
            JMenuItem none = new JMenuItem(International.tc("shape", "No color"));
            none.addActionListener(e -> { setColor(null); popup.setVisible(false); }); popup.add(none);
            JMenuItem more = new JMenuItem(International.tc("shape", "More colors…"));
            more.addActionListener(e -> { popup.setVisible(false); customColor(); }); popup.add(more);
            return popup;
        }
        private void customColor() {
            Color initial = color == null ? Color.BLACK : color;
            JColorChooser chooser = OklabColorChooserPanel.createChooser(new Color(initial.getRed(),initial.getGreen(),initial.getBlue()));
            ColorCompareSwatch compare = new ColorCompareSwatch(chooser); compare.setOriginal(chooser.getColor());
            JSpinner opacity = new JSpinner(new SpinnerNumberModel(initial.getAlpha(), 0, 255, 1));
            JPanel content = new JPanel(new BorderLayout(0, 8)); content.add(chooser, BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout(12, 0)); south.add(compare, BorderLayout.WEST);
            south.add(pair(International.tc("shape", "Opacity (0–255)"), opacity, "", new JLabel()), BorderLayout.CENTER);
            content.add(south, BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, content, International.tc("shape", "Choose color"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                try { opacity.commitEdit(); } catch (java.text.ParseException e) { return; }
                Color c = chooser.getColor(); setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), integerValue(opacity)));
            }
        }
    }
}
