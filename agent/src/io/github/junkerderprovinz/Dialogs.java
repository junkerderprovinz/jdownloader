package io.github.junkerderprovinz;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Frame;
import java.awt.RenderingHints;
import java.awt.Window;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.Settings.*;

/** Dialog colours, the updater window and the dimmed backdrop behind modal dialogs. */
final class Dialogs {

    // Dialogs use the card surface, with fields and buttons styled like the settings.
    static final Color DIALOG_BG = PAL_SURFACE;
    // The updater window sits on top of the download list card, so it needs a lighter shade than the card.
    private static final Color UPDATER_BG = new Color(0x30, 0x30, 0x30);

    static void recolorDialogs() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof javax.swing.RootPaneContainer)) continue;
            // The updater window is undecorated, transparent and not a Dialog, so it is matched by title.
            boolean isUpdater = false;
            try {
                String t = (w instanceof Frame) ? ((Frame) w).getTitle()
                        : (w instanceof Dialog) ? ((Dialog) w).getTitle() : w.getName();
                isUpdater = t != null && t.toLowerCase().contains("updat");
            } catch (Throwable ig) { }
            if (!(w instanceof Dialog) && !isUpdater) continue;
            try {
                javax.swing.RootPaneContainer rpc = (javax.swing.RootPaneContainer) w;
                // The grey frame is a border on the content pane and root pane themselves, which the
                // walk over their children never reaches.
                Color bg = isUpdater ? UPDATER_BG : DIALOG_BG;
                Container cp = rpc.getContentPane();
                if (cp instanceof JComponent) {
                    stripFramingBorder((JComponent) cp);
                    if (!bg.equals(cp.getBackground())) cp.setBackground(bg);
                    if (!((JComponent) cp).isOpaque()) ((JComponent) cp).setOpaque(true);
                }
                javax.swing.JRootPane rp = rpc.getRootPane();
                if (rp != null) {
                    stripFramingBorder(rp);
                    if (rp.getLayeredPane() != null) stripFramingBorder(rp.getLayeredPane());
                    if (isUpdater && rp instanceof JComponent) {
                        if (!bg.equals(rp.getBackground())) rp.setBackground(bg);
                        rp.setOpaque(true);
                        // JD sizes the window tightly to its content and reverts any growth, so a larger
                        // border would squeeze it. A little horizontal room is safe.
                        if (!(rp.getBorder() instanceof javax.swing.border.EmptyBorder))
                            rp.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
                    }
                }
                styleDialogContent(cp, bg);
                if (isUpdater) { roundUpdaterBar(cp); fitUpdaterWindow(w); }
                else fitDialog(w);
            } catch (Throwable ignore) { }
        }
    }

    // AppWork packs a dialog before the theme's padding applies, which pushes the button row of the
    // donation dialog below the window's edge. The window grows to its content and stays centred.
    private static void fitDialog(Window w) {
        Dimension pref = w.getPreferredSize(), size = w.getSize();
        if (pref.width <= size.width && pref.height <= size.height) return;
        java.awt.Rectangle screen = w.getGraphicsConfiguration().getBounds();
        int nw = Math.min(Math.max(pref.width, size.width), screen.width);
        int nh = Math.min(Math.max(pref.height, size.height), screen.height);
        w.setBounds(w.getX() - (nw - size.width) / 2, w.getY() - (nh - size.height) / 2, nw, nh);
        w.validate();
    }

    private static boolean isWarningRed(Color c) {
        return c != null && c.getRed() > 200 && c.getGreen() < 80 && c.getBlue() < 80;
    }

    /** Gives AppWork dialogs the theme's surface, field and button fills and removes their framing
     *  borders. Runs on every tick so content built later is caught; icons are handled by monoChromeIcons. */
    private static void styleDialogContent(Container c, Color bg) {
        for (Component ch : c.getComponents()) {
            try {
                if (ch instanceof javax.swing.JPanel || ch instanceof javax.swing.JOptionPane
                        || ch instanceof javax.swing.Box || ch instanceof javax.swing.JScrollPane) {
                    if (!bg.equals(ch.getBackground())) ch.setBackground(bg);
                    stripFramingBorder((JComponent) ch);
                } else if (ch instanceof javax.swing.text.JTextComponent
                        && !((javax.swing.text.JTextComponent) ch).isEditable()) {
                    // A read-only text component is the dialog message, not an input.
                    if (((JComponent) ch).isOpaque()) ((JComponent) ch).setOpaque(false);
                    if (!bg.equals(ch.getBackground())) ch.setBackground(bg);
                    stripFramingBorder((JComponent) ch);
                } else if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                        || ch instanceof javax.swing.JSpinner) {
                    if (!FIELD_BG.equals(ch.getBackground())) {
                        ch.setBackground(FIELD_BG);
                        if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                    }
                    if (ch instanceof javax.swing.JComboBox) styleComboPopup((javax.swing.JComboBox<?>) ch);
                } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                    javax.swing.AbstractButton ab = (javax.swing.AbstractButton) ch;
                    boolean hot = ab.getModel().isRollover() || ab.getModel().isPressed();
                    if (!hot && !BTN_CFG_BG.equals(ab.getBackground())) ab.setBackground(BTN_CFG_BG);
                    // The two hover listeners share the jdp.savedFg key, so the first hover can leave a dark
                    // label on the dark button. Resetting it at rest keeps the label readable.
                    if (!hot && !SIDEBAR_TEXT.equals(ab.getForeground())) ab.setForeground(SIDEBAR_TEXT);
                    installBtnHoverFg(ab);
                    installBtnHoverBg(ab);
                    // This FlatLaf build ignores a per-component arc, so the updater buttons are rounded by a
                    // corner mask. The original border stays inside it to keep the padding.
                    if (bg == UPDATER_BG && ab.getClientProperty("jdp.updBtnMask") == null) {
                        javax.swing.border.Border ob = ab.getBorder();
                        ab.setBorder(ob == null ? new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS)
                            : javax.swing.BorderFactory.createCompoundBorder(new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS), ob));
                        ab.putClientProperty("jdp.updBtnMask", Boolean.TRUE);
                    }
                } else if (ch instanceof javax.swing.JLabel && isWarningRed(ch.getForeground())) {
                    // AppWork marks empty required fields with a pure red label, which barely reads on the
                    // dark surface; the field placeholder already says what is missing.
                    ch.setForeground(SIDEBAR_TEXT);
                } else if (ch instanceof javax.swing.JLabel && ((javax.swing.JLabel) ch).getIcon() != null) {
                    javax.swing.JLabel l = (javax.swing.JLabel) ch;
                    if (l.getClass().getName().endsWith(".ProgressLogo")) {
                        tameProgressLogo(l);
                    } else {
                        javax.swing.Icon cur = l.getIcon();
                        if (l.getClientProperty("jdp.monoLbl") != cur) {
                            // Small icons are chrome glyphs; a large image is a logo and only loses its box.
                            javax.swing.Icon m;
                            if (cur.getIconWidth() >= 40 || cur.getIconHeight() >= 40) m = deBoxIcon(cur, l);
                            else m = tablerIcon(cur, SIDEBAR_TEXT, l);
                            if (m != cur) { l.setIcon(m); l.putClientProperty("jdp.monoLbl", m); }
                        }
                    }
                } else if (ch instanceof JComponent) {
                    stripFramingBorder((JComponent) ch);
                }
            } catch (Throwable ignore) { }
            if (ch instanceof Container) styleDialogContent((Container) ch, bg);
        }
    }

    /** The updater's ProgressLogo paints its globe from internal Image and Icon fields and ignores the theme's
     *  updaterIcon SVGs, so those fields are replaced with the JD Highlighter logo. */
    private static void tameProgressLogo(javax.swing.JLabel l) {
        try {
            if (Boolean.TRUE.equals(l.getClientProperty("jdp.updLogo"))) return;
            java.awt.image.BufferedImage logo = logoImage();
            if (logo == null) return;
            int sz = Math.max(l.getWidth(), l.getHeight());
            if (sz < 32) sz = 64; if (sz > 96) sz = 96;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(logo, 0, 0, sz, sz, null);
            g.dispose();
            javax.swing.ImageIcon icon = new javax.swing.ImageIcon(img);
            for (Class<?> k = l.getClass(); k != null && k != javax.swing.JLabel.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    try {
                        Class<?> ft = f.getType();
                        if (java.awt.Image.class.isAssignableFrom(ft)) { f.setAccessible(true); f.set(l, img); }
                        else if (javax.swing.Icon.class.isAssignableFrom(ft)) { f.setAccessible(true); f.set(l, icon); }
                    } catch (Throwable ig) { }
                }
            }
            l.setIcon(icon);
            l.setOpaque(false);
            if (!UPDATER_BG.equals(l.getBackground())) l.setBackground(UPDATER_BG);
            l.putClientProperty("jdp.updLogo", Boolean.TRUE);
            l.repaint();
        } catch (Throwable ig) { }
    }

    /** The JD Highlighter logo, decoded once from LOGO_PNG_B64. */
    private static java.awt.image.BufferedImage LOGO_IMG;
    private static boolean LOGO_TRIED;
    private static java.awt.image.BufferedImage logoImage() {
        if (LOGO_TRIED) return LOGO_IMG;
        LOGO_TRIED = true;
        try {
            byte[] png = java.util.Base64.getDecoder().decode(LOGO_PNG_B64);
            LOGO_IMG = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (Throwable ig) { LOGO_IMG = null; }
        return LOGO_IMG;
    }
    // jd-highlighter/.github/assets/icon.svg as a 128px PNG, embedded so the agent needs no SVG renderer.
    // Regenerate it when icon.svg changes.
    private static final String LOGO_PNG_B64 =
        "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";

    /** Rounds the ends of the updater's progress bar. Its UI has an arc field that this FlatLaf build
     *  ignores, and ProgressBar.arc has to stay as it is for the download rows, so the corners are masked. */
    private static void roundUpdaterBar(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JProgressBar) {
                javax.swing.JProgressBar pb = (javax.swing.JProgressBar) ch;
                if (!Boolean.TRUE.equals(pb.getClientProperty("jdp.updBar"))) {
                    pb.setBorder(new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS));
                    pb.putClientProperty("jdp.updBar", Boolean.TRUE);
                    pb.repaint();
                }
            }
            if (ch instanceof Container) roundUpdaterBar((Container) ch);
        }
    }

    /** A zero-inset border that paints a component's corners in the colour behind it, so a square component
     *  looks rounded on a uniform surface. */
    static final class RoundCornerMaskBorder implements javax.swing.border.Border {
        private final Color mask; private final int radius;   // 0 or less gives a pill
        RoundCornerMaskBorder(Color mask, int radius) { this.mask = mask; this.radius = radius; }
        public java.awt.Insets getBorderInsets(Component c) { return new java.awt.Insets(0, 0, 0, 0); }
        public boolean isBorderOpaque() { return false; }
        public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            if (w <= 0 || h <= 0) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = radius > 0 ? Math.min(radius, Math.min(w, h) / 2) : Math.min(w, h) / 2;
            java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.Rectangle(x, y, w, h));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, 2f * r, 2f * r)));
            g.setColor(mask);
            g.fill(a);
            g.dispose();
        }
    }

    /** JD centres the updater on its main frame, but Selkies resizes the X display to the browser window,
     *  so in a smaller browser the updater runs past the screen edge. It is moved back onto the screen. */
    private static void fitUpdaterWindow(Window w) {
        try {
            java.awt.Color wb = w.getBackground();
            if (wb == null || wb.getAlpha() < 255) { try { w.setBackground(UPDATER_BG); } catch (Throwable ig) { } }
            // Java can keep stale, larger GraphicsConfiguration bounds after Selkies resizes the display, so
            // the smaller of those and the current DisplayMode wins. The frame itself stays square: rounding
            // it needs setShape on an undecorated frame, and undecorating JD's updater breaks its layout.
            java.awt.GraphicsConfiguration gc = w.getGraphicsConfiguration();
            java.awt.Rectangle sc = gc.getBounds();
            int scW = sc.width, scH = sc.height;
            try { java.awt.DisplayMode dm = gc.getDevice().getDisplayMode();
                  if (dm.getWidth()  > 0) scW = Math.min(scW, dm.getWidth());
                  if (dm.getHeight() > 0) scH = Math.min(scH, dm.getHeight()); } catch (Throwable t) { }
            java.awt.Rectangle b = w.getBounds();
            final int m = 8;
            int nx = b.x, ny = b.y;
            if (nx + b.width  > sc.x + scW - m) nx = sc.x + scW - b.width  - m;
            if (ny + b.height > sc.y + scH - m) ny = sc.y + scH - b.height - m;
            if (nx < sc.x + m) nx = sc.x + m;
            if (ny < sc.y + m) ny = sc.y + m;
            if (nx != b.x || ny != b.y) w.setLocation(nx, ny);
        } catch (Throwable ig) { }
    }
    /** Removes decorative frames, including AppWork's grey dialog border, and keeps plain padding. */
    static void stripFramingBorder(JComponent jc) {
        javax.swing.border.Border b = jc.getBorder();
        if (b == null || b instanceof javax.swing.border.EmptyBorder) return;
        String cn = b.getClass().getName();
        if (b instanceof javax.swing.border.TitledBorder || b instanceof javax.swing.border.EtchedBorder
                || b instanceof javax.swing.border.LineBorder || b instanceof javax.swing.border.BevelBorder
                || b instanceof javax.swing.border.MatteBorder
                || cn.startsWith("org.appwork") || cn.contains("LineBorder") || cn.contains("Etched")
                // FlatLaf's line and scroll pane borders draw the faint frame around a pop-up.
                || cn.contains("FlatLineBorder") || cn.contains("FlatScrollPaneBorder"))
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        // Component.borderWidth=0 does not reach the round, button, text field and text area borders on
        // JD's own controls, so they become an EmptyBorder with the same insets to keep the padding.
        else if (cn.contains("FlatRoundBorder") || cn.contains("FlatButtonBorder")
                || cn.contains("FlatTextBorder") || cn.contains("FlatMarginBorder")) {
            java.awt.Insets in = b.getBorderInsets(jc);
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder(in.top, in.left, in.bottom, in.right));
        }
    }

    // A glass pane dims the main window while a modal dialog shows; the dialog is its own window and
    // stays bright. It is polled, so a dialog that went away abnormally cannot leave the overlay behind.
    private static final java.util.Map<Window, Component> DIMMED =
            new java.util.WeakHashMap<Window, Component>();

    static void dimModalBackdrops() {
        boolean modal = false;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isShowing()
                    && ((Dialog) w).getModalityType() != Dialog.ModalityType.MODELESS) { modal = true; break; }
        }
        // A modal dialog's declared owner is often a hidden shared frame, so the large visible frame
        // is dimmed instead.
        Frame main = null;
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400
                    && f instanceof javax.swing.RootPaneContainer) { main = f; break; }
        }
        if (main == null) return;
        boolean has = DIMMED.containsKey(main);
        try {
            javax.swing.JRootPane rp = ((javax.swing.RootPaneContainer) main).getRootPane();
            if (rp == null) return;
            if (modal && !has) {
                Component saved = rp.getGlassPane();
                // The frame cannot change behind the modal, so one blurred snapshot serves until it
                // closes. Without one, a darker flat veil is used.
                final java.awt.image.BufferedImage blur = frostSnapshot(rp);
                JComponent dim = new JComponent() {
                    protected void paintComponent(Graphics g) {
                        if (blur != null) g.drawImage(blur, 0, 0, getWidth(), getHeight(), null);
                        g.setColor(new Color(0, 0, 0, blur != null ? 110 : 175));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
                dim.setOpaque(false);
                rp.setGlassPane(dim);
                dim.setVisible(true);
                DIMMED.put(main, saved);
                System.out.println("[jd-dialog-agent] jd-highlighter: frosted+dimmed main window behind modal dialog");
            } else if (!modal && has) {
                Component saved = DIMMED.remove(main);
                if (saved != null) { rp.setGlassPane(saved); saved.setVisible(false); }
                else rp.getGlassPane().setVisible(false);
            }
        } catch (Throwable ignore) { }
    }

    /** The root pane's content below the glass pane, blurred at half resolution. */
    private static java.awt.image.BufferedImage frostSnapshot(javax.swing.JRootPane rp) {
        try {
            int w = rp.getWidth(), h = rp.getHeight();
            if (w <= 0 || h <= 0) return null;
            int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);
            java.awt.image.BufferedImage snap = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = snap.createGraphics();
            g.scale(sw / (double) w, sh / (double) h);
            rp.getLayeredPane().printAll(g);   // leaves out the glass pane itself
            g.dispose();
            return boxBlur(boxBlur(snap, 4), 4);   // two passes come close to a gaussian
        } catch (Throwable t) { return null; }
    }

    /** Separable box blur of radius r on an INT_RGB image. */
    private static java.awt.image.BufferedImage boxBlur(java.awt.image.BufferedImage src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] tmp = new int[in.length];
        blurPass(in, tmp, w, h, r);
        blurPass(tmp, in, h, w, r);   // transposed
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, in, 0, w);
        return out;
    }

    /** One separable pass writing transposed, so calling it twice blurs both axes. */
    private static void blurPass(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                int lo = Math.max(0, x - r), hi = Math.min(w - 1, x + r);
                for (int k = lo; k <= hi; k++) {
                    int p = src[base + k];
                    rs += (p >> 16) & 0xff; gs += (p >> 8) & 0xff; bs += p & 0xff; n++;
                }
                int rr = rs / n, gg = gs / n, bb = bs / n;
                dst[x * h + y] = (rr << 16) | (gg << 8) | bb;
            }
        }
    }
}
