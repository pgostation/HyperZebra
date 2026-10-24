import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;

/** Launcher-only styling; never changes the look and feel of stacks or emulated applications. */
final class LauncherAppearance {
    private final File file;
    String theme = "light";
    int accentIndex = 0;
    boolean compact;
    /** The dock's icon size: 32 (the classic icon dot by dot), 48 or 64 (each dot doubled). */
    int dockSize = 48;
    /** The dock along the left edge (a column) instead of the bottom (a row). */
    boolean dockLeft;
    /**
     * The dock filling the window as a grid that scrolls vertically (for many registered items); {@link #dockLeft} is then
     * ignored. A passing view switched from the dock's own button, never saved: the launcher always opens with the dock
     * at its edge (a saved "dockSide=grid" of earlier versions reads as the bottom).
     */
    boolean dockGrid;
    /** Tabs along the dock (DragThing's): each holds its own items (LauncherStore.DockTab); off, the dock is the first tab. */
    boolean dockTabs;
    /** The window's last position and size (null: not saved yet). */
    Rectangle window;
    static final int[] ACCENTS = {0x0067D9, 0x7551C8, 0xB53973, 0x157A63, 0xAE570B};

    LauncherAppearance(File file) {
        this.file = file;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) { p.load(in); }
        catch (IOException | IllegalArgumentException ignored) { }
        theme = "dark".equals(p.getProperty("theme")) ? "dark" : "light";
        try { accentIndex = Integer.parseInt(p.getProperty("accent", "0")); } catch (NumberFormatException ignored) { }
        if (accentIndex < 0 || accentIndex >= ACCENTS.length) accentIndex = 0;
        compact = Boolean.parseBoolean(p.getProperty("compact", "false"));
        try { dockSize = Integer.parseInt(p.getProperty("dockSize", "48")); } catch (NumberFormatException ignored) { }
        if (dockSize == 40) dockSize = 32; else if (dockSize == 60) dockSize = 64; // the earlier sizes
        if (dockSize != 32 && dockSize != 48 && dockSize != 64) dockSize = 48;
        dockLeft = "left".equals(p.getProperty("dockSide"));
        dockTabs = Boolean.parseBoolean(p.getProperty("dockTabs", "false"));
        try {
            if (p.getProperty("window.x") != null)
                window = new Rectangle(Integer.parseInt(p.getProperty("window.x")), Integer.parseInt(p.getProperty("window.y")),
                    Integer.parseInt(p.getProperty("window.width")), Integer.parseInt(p.getProperty("window.height")));
        } catch (NumberFormatException ignored) { window = null; }
        if (window != null && (window.width < 100 || window.height < 60)) window = null;
    }
    void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("theme", theme); p.setProperty("accent", String.valueOf(accentIndex));
        p.setProperty("compact", String.valueOf(compact)); p.setProperty("dockSize", String.valueOf(dockSize));
        p.setProperty("dockSide", dockLeft ? "left" : "bottom");
        p.setProperty("dockTabs", String.valueOf(dockTabs));
        if (window != null) {
            p.setProperty("window.x", String.valueOf(window.x)); p.setProperty("window.y", String.valueOf(window.y));
            p.setProperty("window.width", String.valueOf(window.width)); p.setProperty("window.height", String.valueOf(window.height));
        }
        Path parent = file.toPath().toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, "appearance-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "HyperZebra launcher appearance"); }
            try { Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    boolean dark() { return "dark".equals(theme); }
    Color background() { return new Color(dark() ? 0x20232B : 0xEFF2F8); }
    Color surface() { return new Color(dark() ? 0x2C303A : 0xFCFDFE); }
    Color foreground() { return new Color(dark() ? 0xF2F3F7 : 0x222735); }
    Color secondary() { return new Color(dark() ? 0xAFB7C6 : 0x626C7D); }
    Color line() { return new Color(dark() ? 0x444B59 : 0xDCE1EA); }
    Color accent() { return new Color(ACCENTS[accentIndex]); }
    Color selection() { return mix(surface(), accent(), dark() ? .38f : .12f); }
    Color error() { return new Color(dark() ? 0xFF9DA9 : 0xB4233C); }
    static Color mix(Color a, Color b, float amount) {
        return new Color((int)(a.getRed()*(1-amount)+b.getRed()*amount), (int)(a.getGreen()*(1-amount)+b.getGreen()*amount), (int)(a.getBlue()*(1-amount)+b.getBlue()*amount));
    }
    private static Color alpha(Color color, int opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }
    private void glow(Graphics2D g, float x, float y, float radius, Color color, int opacity, int width, int height) {
        g.setPaint(new RadialGradientPaint(x, y, Math.max(1f, radius), new float[]{0f, 1f},
            new Color[]{alpha(color, opacity), alpha(color, 0)}));
        g.fillRect(0, 0, width, height);
    }
    static Graphics2D smooth(Graphics graphics) {
        Graphics2D g = (Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }
    JPanel canvas() {
        return new JPanel(new BorderLayout(0, 14)) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                int w = getWidth(), h = getHeight();
                g.setPaint(new GradientPaint(0, 0, mix(background(), accent(), .12f), w, h, background()));
                g.fillRect(0, 0, w, h);
                glow(g, w*.12f, h*.18f, w*.85f, accent(), dark() ? 76 : 72, w, h);
                glow(g, w*.95f, h*.5f, w*.7f, new Color(0xB4A0EE), dark() ? 48 : 86, w, h);
                glow(g, w*.35f, h*1.05f, w*.75f, new Color(0x7DDDD4), dark() ? 40 : 80, w, h);
                g.dispose();
            }
        };
    }
    JPanel surfacePanel(LayoutManager layout) { return surfacePanel(layout, null); }
    /** A glass surface tinted with a colour (the dock takes its selected tab's: {@code tint} answers it, or null for none). */
    JPanel surfacePanel(LayoutManager layout, java.util.function.Supplier<Color> tint) {
        JPanel panel = new JPanel(layout) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = smooth(graphics);
                g.setColor(new Color(0, 0, 0, dark() ? 28 : 8));
                g.fillRoundRect(0, 2, getWidth()-1, getHeight()-3, 24, 24);
                g.setPaint(new GradientPaint(0, 0, alpha(dark() ? mix(surface(), Color.WHITE, .07f) : Color.WHITE, dark() ? 195 : 210),
                    getWidth()*.35f, Math.max(1, getHeight()), alpha(surface(), dark() ? 140 : 150)));
                g.fillRoundRect(0, 0, getWidth()-1, getHeight()-3, 24, 24);
                Color color = tint==null ? null : tint.get();
                if (color != null) {
                    g.setPaint(new GradientPaint(0, 0, alpha(color, dark() ? 92 : 70), 0, Math.max(1, getHeight()), alpha(color, dark() ? 130 : 105)));
                    g.fillRoundRect(0, 0, getWidth()-1, getHeight()-3, 24, 24);
                }
                g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, dark() ? 62 : 225),
                    0, Math.max(1, getHeight()), alpha(line(), 125)));
                g.drawRoundRect(0, 0, getWidth()-1, getHeight()-3, 24, 24);
                g.dispose();
            }
        };
        panel.setOpaque(false);
        return panel;
    }
    void styleTree(Component c) {
        if (c instanceof JComboBox) return; // its arrow button is a JButton of its own: restyling it cut the top of the pop-ups in the settings pages
        if (c instanceof JPanel) ((JPanel)c).setOpaque(false);
        if (c instanceof JLabel || c instanceof JTextArea) c.setForeground(foreground());
        if (c instanceof JComponent && Boolean.TRUE.equals(((JComponent)c).getClientProperty("launcher.secondary"))) c.setForeground(secondary());
        if (c instanceof JCheckBox || c instanceof JRadioButton) { c.setForeground(foreground()); ((JComponent)c).setOpaque(false); } // keep their marks
        else if (c instanceof AbstractButton && !(c instanceof JMenuItem)) styleButton((AbstractButton)c);
        if (c instanceof JScrollPane) {
            JScrollPane s = (JScrollPane)c;
            s.setOpaque(false); s.getViewport().setOpaque(false); s.getViewport().setBackground(surface());
            s.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            s.getVerticalScrollBar().setUnitIncrement(24);
            styleScrollBar(s.getVerticalScrollBar()); styleScrollBar(s.getHorizontalScrollBar());
        }
        if (c instanceof Container) for (Component child : ((Container)c).getComponents()) styleTree(child);
    }
    private void styleScrollBar(JScrollBar bar) {
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
            private JButton emptyButton() {
                JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0)); b.setMaximumSize(new Dimension(0, 0)); return b;
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                // The parent glass surface supplies the track background.
            }
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle r) {
                Graphics2D g = smooth(graphics); g.setColor(mix(surface(), secondary(), isThumbRollover() ? .8f : .45f));
                g.fillRoundRect(r.x+3, r.y+3, Math.max(2,r.width-6), Math.max(2,r.height-6), 8, 8); g.dispose();
            }
        });
        bar.setOpaque(false);
        bar.setPreferredSize(bar.getOrientation()==JScrollBar.VERTICAL ? new Dimension(12, 0) : new Dimension(0, 12));
    }
    /**
     * A tab of the side column (the middle's pages): the selected one is filled and carries an accent bar on its left,
     * like a sidebar's selection; the others are bare text until the pointer is over them.
     */
    private void paintTab(Graphics graphics, AbstractButton button) {
        Graphics2D g = smooth(graphics);
        int w = button.getWidth(), h = button.getHeight();
        boolean on = button.isSelected(), hover = button.getModel().isRollover(), pressed = button.getModel().isPressed();
        if (on || hover || pressed) {
            Color fill = on ? selection() : alpha(selection(), 150);
            if (pressed) fill = mix(fill, foreground(), .10f);
            g.setColor(fill);
            g.fillRoundRect(1, 1, w-3, h-3, 12, 12);
        }
        if (on) { g.setColor(accent()); g.fillRoundRect(3, 6, 4, h-13, 4, 4); }
        if (button.hasFocus()) { g.setColor(accent()); g.setStroke(new BasicStroke(1.5f)); g.drawRoundRect(1, 1, w-3, h-3, 12, 12); }
        g.dispose();
        button.setForeground(on ? (dark() ? Color.WHITE : mix(foreground(), accent(), .35f)) : foreground());
    }
    void styleButton(AbstractButton b) {
        Font font = b.getFont();
        b.setUI(new BasicButtonUI() {
            @Override public void paint(Graphics graphics, JComponent c) {
                AbstractButton button = (AbstractButton)c;
                if (Boolean.TRUE.equals(button.getClientProperty("launcher.tab"))) { paintTab(graphics, button); super.paint(graphics, c); return; }
                boolean primary = Boolean.TRUE.equals(button.getClientProperty("launcher.primary")) || (button.isSelected() && !(button instanceof JToggleButton)); // a side button whose page is up
                boolean dock = Boolean.TRUE.equals(button.getClientProperty("launcher.dock"));
                boolean active = button.isSelected() || button.getModel().isRollover();
                Graphics2D g = smooth(graphics);
                Color fill = primary ? accent() : active ? selection() : surface();
                if (button.getModel().isPressed()) fill = mix(fill, foreground(), .12f);
                if (!dock || active || button.hasFocus()) {
                    Color top = primary ? mix(fill, Color.WHITE, .16f) : alpha(mix(fill, Color.WHITE, dark() ? .08f : .35f), 220);
                    Color bottom = primary ? mix(fill, Color.BLACK, .07f) : alpha(fill, active ? 205 : 145);
                    g.setPaint(new GradientPaint(0, 0, top, 0, Math.max(1, c.getHeight()), bottom));
                    g.fillRoundRect(1, 1, c.getWidth()-3, c.getHeight()-3, dock ? 20 : 14, dock ? 20 : 14);
                    g.setColor(button.hasFocus() || primary ? accent() : line());
                    g.setStroke(new BasicStroke(button.hasFocus() ? 2f : 1f));
                    g.drawRoundRect(1, 1, c.getWidth()-3, c.getHeight()-3, dock ? 20 : 14, dock ? 20 : 14);
                }
                g.dispose();
                button.setForeground(primary ? Color.WHITE : foreground());
                super.paint(graphics, c);
            }
        });
        b.setFont(font);
        b.setOpaque(false); b.setContentAreaFilled(false); b.setBorderPainted(false); b.setFocusPainted(false);
        b.setRolloverEnabled(true); b.setForeground(foreground());
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (Boolean.TRUE.equals(b.getClientProperty("launcher.tab"))) b.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 10)); // room for the selection bar
        else if (!Boolean.TRUE.equals(b.getClientProperty("launcher.dock"))) b.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
    }
}
