import java.awt.*;
import java.util.ArrayList;
import java.util.Locale;
import javax.swing.*;

/**
 * The Filters tab of Shape Info: filter steps applied from top to bottom (an SvgFilter chain, each step taking the previous one's
 * result), with sliders for their settings. Steps made here are tagged (hz-kind / hz-values) so they come back as the same controls;
 * plain SVG primitives that fit a step (a blur, a drop shadow, saturate, hue-rotate, morphology) are shown as that step. A filter
 * imported from SVG that routes results between primitives (in / in2 / result) is kept whole as one step that can only be removed.
 */
final class ShapeFilterPanel extends JPanel {
    /** The catalog context of Shape Info's texts; null for the general ones; "shape strength" for an effect's Amount (not a quantity). */
    private static final String SHAPE = "shape", GENERAL = null, STRENGTH = "shape strength";

    /** A kind of step: its name, its settings (label, range, default) and whether it has a colour. */
    enum Kind {
        BLUR(GENERAL, "Blur", null, p("Radius", 0, 30, 4)),
        SHADOW(SHAPE, "Drop shadow", new Color(0, 0, 0, 128), p("X", -40, 40, 6), p("Y", -40, 40, 6), p(GENERAL, "Blur", 0, 30, 4)),
        GLOW(SHAPE, "Glow", new Color(255, 214, 79), p("Size", 0, 30, 6)),
        SATURATE(GENERAL, "Saturation", null, p("Amount %", 0, 300, 160)),
        GRAYSCALE(SHAPE, "Grayscale", null, p(STRENGTH, "Amount %", 0, 100, 100)),
        SEPIA(SHAPE, "Sepia", null, p(STRENGTH, "Amount %", 0, 100, 100)),
        HUE(GENERAL, "Hue", null, p("Angle °", -180, 180, 90)),
        BRIGHTNESS(SHAPE, "Brightness", null, p("Amount %", 0, 300, 130)),
        CONTRAST(SHAPE, "Contrast", null, p("Amount %", 0, 300, 150)),
        INVERT(SHAPE, "Invert", null, p(STRENGTH, "Amount %", 0, 100, 100)),
        OPACITY(SHAPE, "Opacity", null, p("Amount %", 0, 100, 60)),
        DILATE(SHAPE, "Thicken", null, p("Radius", 0, 20, 2)),
        ERODE(SHAPE, "Thin", null, p("Radius", 0, 20, 2));
        final String context, en; final Color color; final Param[] params;
        Kind(String context, String en, Color color, Param... params) { this.context = context; this.en = en; this.color = color; this.params = params; }
        String label() { return International.tc(context, en); }
        String tag() { return name().toLowerCase(Locale.ROOT); }
    }
    record Param(String context, String en, int min, int max, int value) {
        String label() { return International.tc(context, en); }
    }
    private static Param p(String en, int min, int max, int value) { return new Param(SHAPE, en, min, max, value); }
    private static Param p(String context, String en, int min, int max, int value) { return new Param(context, en, min, max, value); }

    /** One row: an editable step, a primitive kept as it is, or the whole imported graph. */
    final class Step extends JPanel {
        final Kind kind;
        final ValueSlider[] values;
        final ShapeInfoPanel.ColorButton color;
        final ArrayList<SvgFilter.Primitive> kept;
        Step(Kind kind, double[] settings, Color initial) {
            super(new BorderLayout(0, 4));
            this.kind = kind; kept = null;
            values = new ValueSlider[kind.params.length];
            JPanel body = new JPanel(new GridBagLayout());
            for (int i = 0; i < values.length; i++) {
                Param p = kind.params[i];
                values[i] = new ValueSlider(p.min, p.max, settings != null && i < settings.length ? settings[i] : p.value, changed);
                row(body, i, p.label(), values[i]);
            }
            color = kind.color == null ? null : new ShapeInfoPanel.ColorButton(initial == null ? kind.color : initial);
            if (color != null) { color.onChange = changed; row(body, values.length, International.tc(SHAPE, "Color"), color); }
            build(kind.label(), body);
        }
        Step(String title, String note, ArrayList<SvgFilter.Primitive> kept) {
            super(new BorderLayout(0, 4));
            kind = null; values = new ValueSlider[0]; color = null; this.kept = kept;
            JLabel label = new JLabel(note); label.setForeground(UIManager.getColor("Label.disabledForeground"));
            JPanel body = new JPanel(new BorderLayout()); body.add(label);
            build(title, body);
        }
        private void row(JPanel body, int y, String title, Component c) {
            GridBagConstraints g = new GridBagConstraints(); g.gridy = y; g.anchor = GridBagConstraints.LINE_END; g.insets = new Insets(2, 0, 2, 8);
            body.add(new JLabel(title), g);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.LINE_START; g.insets = new Insets(2, 0, 2, 0);
            body.add(c, g);
        }
        private void build(String title, JPanel body) {
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0, 0, 0, 40)),
                BorderFactory.createEmptyBorder(8, 4, 8, 4)));
            JPanel head = new JPanel(new BorderLayout());
            JLabel name = new JLabel(title); name.setFont(name.getFont().deriveFont(Font.BOLD));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
            JButton up = small("▲", International.tc(SHAPE, "Earlier")), down = small("▼", International.tc(SHAPE, "Later")), remove = small("✕", International.t("Remove"));
            up.addActionListener(e -> moveStep(this, -1)); down.addActionListener(e -> moveStep(this, 1)); remove.addActionListener(e -> removeStep(this));
            buttons.add(up); buttons.add(down); buttons.add(remove);
            head.add(name, BorderLayout.WEST); head.add(buttons, BorderLayout.EAST);
            add(head, BorderLayout.NORTH); add(body, BorderLayout.CENTER);
            setAlignmentX(LEFT_ALIGNMENT);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        double value(int i) { return values[i].value(); }
        /** The SVG primitives of this step. */
        ArrayList<SvgFilter.Primitive> primitives() {
            if (kept != null) return kept;
            ArrayList<SvgFilter.Primitive> out = new ArrayList<>();
            SvgFilter.Primitive p;
            switch (kind) {
                case BLUR -> { p = prim("feGaussianBlur"); p.attributes.put("stdDeviation", num(value(0))); }
                case SHADOW -> { p = prim("feDropShadow"); p.attributes.put("dx", num(value(0))); p.attributes.put("dy", num(value(1))); p.attributes.put("stdDeviation", num(value(2))); flood(p); }
                case GLOW -> { p = prim("feDropShadow"); p.attributes.put("dx", "0"); p.attributes.put("dy", "0"); p.attributes.put("stdDeviation", num(value(0))); flood(p); }
                case SATURATE -> { p = prim("feColorMatrix"); p.attributes.put("type", "saturate"); p.attributes.put("values", num(value(0) / 100)); }
                case GRAYSCALE -> { p = prim("feColorMatrix"); p.attributes.put("type", "saturate"); p.attributes.put("values", num(1 - value(0) / 100)); }
                case SEPIA -> { p = prim("feColorMatrix"); p.attributes.put("type", "matrix"); p.attributes.put("values", sepia(value(0) / 100)); }
                case HUE -> { p = prim("feColorMatrix"); p.attributes.put("type", "hueRotate"); p.attributes.put("values", num(value(0))); }
                case BRIGHTNESS -> { p = prim("feComponentTransfer"); for (String c : RGB) { p.attributes.put(c + ".type", "linear"); p.attributes.put(c + ".slope", num(value(0) / 100)); } }
                case CONTRAST -> { p = prim("feComponentTransfer"); double k = value(0) / 100;
                    for (String c : RGB) { p.attributes.put(c + ".type", "linear"); p.attributes.put(c + ".slope", num(k)); p.attributes.put(c + ".intercept", num(0.5 - 0.5 * k)); } }
                case INVERT -> { p = prim("feComponentTransfer"); double a = value(0) / 100;
                    for (String c : RGB) { p.attributes.put(c + ".type", "table"); p.attributes.put(c + ".tableValues", num(a) + " " + num(1 - a)); } }
                case OPACITY -> { p = prim("feComponentTransfer"); p.attributes.put("feFuncA.type", "linear"); p.attributes.put("feFuncA.slope", num(value(0) / 100)); }
                case DILATE, ERODE -> { p = prim("feMorphology"); p.attributes.put("operator", kind == Kind.DILATE ? "dilate" : "erode"); p.attributes.put("radius", num(value(0))); }
                default -> throw new IllegalStateException();
            }
            p.attributes.put("hz-kind", kind.tag());
            StringBuilder v = new StringBuilder();
            for (int i = 0; i < values.length; i++) v.append(i == 0 ? "" : ",").append(num(value(i)));
            p.attributes.put("hz-values", v.toString());
            out.add(p);
            return out;
        }
        private void flood(SvgFilter.Primitive p) {
            Color c = color.color == null ? new Color(0, 0, 0, 0) : color.color;
            p.attributes.put("flood-color", String.format("#%06X", c.getRGB() & 0xFFFFFF));
            p.attributes.put("flood-opacity", num(c.getAlpha() / 255.0));
        }
    }

    private static final String[] RGB = {"feFuncR", "feFuncG", "feFuncB"};
    private static SvgFilter.Primitive prim(String type) { return new SvgFilter.Primitive(type); }
    static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
    }
    private static String sepia(double a) {
        double[] s = {.393, .769, .189, 0, 0, .349, .686, .168, 0, 0, .272, .534, .131, 0, 0, 0, 0, 0, 1, 0};
        double[] id = {1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 20; i++) b.append(i == 0 ? "" : " ").append(num(id[i] + (s[i] - id[i]) * a));
        return b.toString();
    }
    private static JButton small(String text, String tip) {
        JButton b = new JButton(text); b.setToolTipText(tip); b.getAccessibleContext().setAccessibleName(tip);
        b.putClientProperty("JButton.buttonType", "square");
        b.setMargin(new Insets(0, 0, 0, 0)); b.setFocusable(false); b.setPreferredSize(new Dimension(30, 24)); return b;
    }

    final ArrayList<Step> steps = new ArrayList<>();
    private final JPanel list = new JPanel();
    private final JLabel empty = new JLabel();
    private final Runnable changed;
    final JButton add = new JButton(International.tc(SHAPE, "Add Filter ▾"));

    ShapeFilterPanel(SvgFilter filter, Runnable onChange) {
        super(new BorderLayout(0, 8));
        changed = onChange == null ? () -> {} : onChange;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        JLabel help = new JLabel(International.tc(SHAPE, "Filters are applied from top to bottom."));
        top.add(help, BorderLayout.CENTER); top.add(add, BorderLayout.EAST);
        add.addActionListener(e -> menu().show(add, 0, add.getHeight()));
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        empty.setText(International.tc(SHAPE, "No filters. Choose Add Filter to blur, recolor or add a glow."));
        empty.setForeground(UIManager.getColor("Label.disabledForeground"));
        empty.setAlignmentX(LEFT_ALIGNMENT);
        JPanel holder = new JPanel(new BorderLayout()); holder.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder); scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(top, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER);
        read(filter);
        relist();
    }

    private JPopupMenu menu() {
        JPopupMenu m = new JPopupMenu();
        Kind[][] groups = {{Kind.BLUR, Kind.SHADOW, Kind.GLOW},
            {Kind.SATURATE, Kind.GRAYSCALE, Kind.SEPIA, Kind.HUE, Kind.BRIGHTNESS, Kind.CONTRAST, Kind.INVERT, Kind.OPACITY},
            {Kind.DILATE, Kind.ERODE}};
        for (int g = 0; g < groups.length; g++) {
            if (g > 0) m.addSeparator();
            for (Kind k : groups[g]) { JMenuItem item = new JMenuItem(k.label()); item.addActionListener(e -> addStep(k)); m.add(item); }
        }
        return m;
    }

    void addStep(Kind kind) { steps.add(new Step(kind, null, null)); relist(); changed.run(); }
    private void moveStep(Step step, int by) {
        int i = steps.indexOf(step), j = i + by;
        if (i < 0 || j < 0 || j >= steps.size()) return;
        java.util.Collections.swap(steps, i, j); relist(); changed.run();
    }
    private void removeStep(Step step) { steps.remove(step); relist(); changed.run(); }
    private void relist() {
        list.removeAll();
        if (steps.isEmpty()) list.add(empty);
        for (Step s : steps) list.add(s);
        list.revalidate(); list.repaint();
    }

    /** The filter the rows describe, or null when there are none. */
    SvgFilter result() {
        SvgFilter f = new SvgFilter();
        for (Step s : steps) f.primitives.addAll(s.primitives());
        return f.primitives.isEmpty() ? null : f;
    }

    private void read(SvgFilter filter) {
        if (filter == null || filter.primitives.isEmpty()) return;
        boolean graph = false;
        for (SvgFilter.Primitive p : filter.primitives)
            if (!p.get("in", "").isEmpty() || !p.get("in2", "").isEmpty() || !p.get("result", "").isEmpty() || !p.mergeInputs.isEmpty()) graph = true;
        if (graph) {
            steps.add(new Step(International.tc(SHAPE, "Imported SVG filter"),
                International.tc(SHAPE, "{0} connected steps, kept as imported.", filter.primitives.size()),
                new ArrayList<>(filter.primitives)));
            return;
        }
        for (SvgFilter.Primitive p : filter.primitives) {
            Step step = recognise(p);
            if (step == null) {
                ArrayList<SvgFilter.Primitive> one = new ArrayList<>(); one.add(p);
                step = new Step(p.type, International.tc(SHAPE, "SVG filter primitive, kept as imported."), one);
            }
            steps.add(step);
        }
    }
    private Step recognise(SvgFilter.Primitive p) {
        String tag = p.get("hz-kind", "");
        if (!tag.isEmpty()) {
            for (Kind k : Kind.values()) if (k.tag().equals(tag)) {
                String[] parts = p.get("hz-values", "").split(",");
                double[] v = new double[parts.length];
                try { for (int i = 0; i < parts.length; i++) v[i] = Double.parseDouble(parts[i].trim()); } catch (NumberFormatException e) { v = null; }
                return new Step(k, v, k.color == null ? null : floodColor(p));
            }
        }
        switch (p.type) {
            case "feGaussianBlur": return new Step(Kind.BLUR, new double[]{first(p.get("stdDeviation", "0"))}, null);
            case "feDropShadow": {
                double dx = first(p.get("dx", "2")), dy = first(p.get("dy", "2")), blur = first(p.get("stdDeviation", "2"));
                return dx == 0 && dy == 0 ? new Step(Kind.GLOW, new double[]{blur}, floodColor(p)) : new Step(Kind.SHADOW, new double[]{dx, dy, blur}, floodColor(p));
            }
            case "feColorMatrix": {
                String type = p.get("type", "matrix");
                if (type.equals("saturate")) return new Step(Kind.SATURATE, new double[]{first(p.get("values", "1")) * 100}, null);
                if (type.equals("hueRotate")) return new Step(Kind.HUE, new double[]{first(p.get("values", "0"))}, null);
                return null;
            }
            case "feMorphology": return new Step(p.get("operator", "erode").equals("dilate") ? Kind.DILATE : Kind.ERODE, new double[]{first(p.get("radius", "0"))}, null);
            default: return null;
        }
    }
    private static double first(String text) {
        try { return Double.parseDouble(text.trim().split("[ ,]+")[0]); } catch (Exception e) { return 0; }
    }
    private static Color floodColor(SvgFilter.Primitive p) {
        Color c = ShapeSvg.paint(p.get("flood-color", "black"));
        if (c == null) c = Color.BLACK;
        double o = Math.max(0, Math.min(1, first(p.get("flood-opacity", "1"))));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(c.getAlpha() * o));
    }

    /** A slider with a number field beside it; both edit one whole-number setting. */
    static final class ValueSlider extends JPanel {
        final JSlider slider;
        final JSpinner number;
        private boolean syncing;
        ValueSlider(int min, int max, double value, Runnable onChange) {
            super(new BorderLayout(6, 0));
            int v = (int) Math.round(Math.max(min, Math.min(max, value)));
            slider = new JSlider(min, max, v);
            number = new JSpinner(new SpinnerNumberModel(v, min, max, 1));
            number.setPreferredSize(new Dimension(64, number.getPreferredSize().height));
            slider.addChangeListener(e -> { if (syncing) return; syncing = true; number.setValue(slider.getValue()); syncing = false; if (onChange != null) onChange.run(); });
            number.addChangeListener(e -> { if (syncing) return; syncing = true; slider.setValue(((Number) number.getValue()).intValue()); syncing = false; if (onChange != null) onChange.run(); });
            add(slider, BorderLayout.CENTER); add(number, BorderLayout.EAST);
        }
        double value() { return slider.getValue(); }
        void setValue(int v) { slider.setValue(v); }
    }
}
