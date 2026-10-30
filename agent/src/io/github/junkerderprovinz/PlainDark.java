package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JTable;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Tables.*;

/** Fixes for plain dark: package expander icons, the merged toggle icons, the menu toggles and the overview corner buttons. */
final class PlainDark {

    // Plain dark has no accent, so the toggle check and the corner glyphs use neutral greys.
    private static final Color K_CHECK_DISC   = new Color(0xc6, 0xc6, 0xc6);   // light disc behind the toggle check (== BAR_FILL)
    private static final Color K_CHECK_MARK   = new Color(0x16, 0x16, 0x16);   // dark check on the light disc
    private static final Color K_CORNER_LIGHT = new Color(0xd0, 0xd0, 0xd0);   // light glyph for the overview-panel corner icons
    // Gives a checkbox menu item without an icon, such as "Sidebar visible", a slot for the check badge.
    private static final javax.swing.Icon K_BLANK16 = new javax.swing.Icon() {
        public int getIconWidth()  { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g, int x, int y) { }
    };

    // The download list and the linkgrabber draw the package toggle from the iconset's
    // tree_plus and tree_minus. JD's bundled flat iconset lacks both, so the image ships light
    // copies, but JD's self-update re-provisions the iconset directory after the boot seed and
    // drops them, leaving a dark default that vanishes on #161616. The agent runs in every JVM,
    // including the one after a self-update, so it puts them back at premain and on each tick.
    private static final java.io.File EXPANDER_SRC_DIR =
            DESKTOP ? new java.io.File(HL_HOME, "icons")
                    : new java.io.File("/opt/JDownloader/themes-default/flat/org/jdownloader/images");
    private static final java.io.File EXPANDER_DST_DIR =
            new java.io.File(JD_HOME, "themes/flat/org/jdownloader/images");
    // JD ships the two exttable lock icons black, which vanishes on the jd-highlighter
    // header. The image carries light copies, and JD's re-provisioning puts the black ones
    // back, so they need the same heal as the expanders.
    private static final String[] EXPANDER_ICONS = {
        "tree_plus.svg", "tree_minus.svg",
        "exttable/lockColumn.svg", "exttable/widthLocked.svg",
    };

    static void restoreExpanderIcons() {
        for (String name : EXPANDER_ICONS) {
            if (name.indexOf('/') >= 0 && !isHighlighter()) continue;
            java.io.File src = new java.io.File(EXPANDER_SRC_DIR, name);
            java.io.File dst = new java.io.File(EXPANDER_DST_DIR, name);
            if (!src.isFile()) continue;                          // source missing (older image) -> nothing to do
            if (dst.isFile() && dst.length() == src.length()) continue; // already the shipped light copy
            try {
                dst.getParentFile().mkdirs();                     // name may carry a subdir (exttable/)
                java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[jd-dialog-agent] restored light theme icon: " + name);
            } catch (Throwable ignore) { /* retried next tick */ }
        }
    }

    // FileColumn bakes the package toggle into four private final fields in its constructor,
    // bypassing NewTheme's cache, so once it has picked up the dark fallback the restored SVGs
    // change nothing on screen. The fields are overwritten on every live FileColumn instead;
    // a final instance field is writable after setAccessible(true).
    private static Object[] toggleIcons = null;   // {packageOpen, packageClosed, archive, archiveOpen}

    static void healPackageToggle() {
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                List<JTable> tables = new ArrayList<>();
                collectTables(w, tables);
                for (JTable t : tables) {
                    boolean changed = false;
                    for (Object col : extColumns(t)) {
                        if (col == null || !"FileColumn".equals(col.getClass().getSimpleName())) continue;
                        // JD's classes live on its launcher loader, which the agent's system
                        // loader cannot see, so the loader comes from a live FileColumn.
                        if (toggleIcons == null) {
                            toggleIcons = buildToggleIcons(col.getClass().getClassLoader());
                            if (toggleIcons == null) return;   // not resolvable yet -> retry next tick
                        }
                        changed |= patchFileColumn(col);
                    }
                    if (changed) t.repaint();
                }
            }
        } catch (Throwable ignore) { /* best effort */ }
    }

    /** Builds FileColumn's four cell icons from our light boxIcon and JD's package and rar icons,
     *  so neither JD's possibly dark tree_plus/tree_minus nor ExtMergedIcon's constructor is
     *  involved. Returns null until NewTheme and the package icons resolve. */
    private static boolean toggleWarnLogged = false;

    private static Object[] buildToggleIcons(ClassLoader cl) {
        try {
            Class<?> newThemeCls = Class.forName("org.jdownloader.images.NewTheme", true, cl);
            Object   newTheme    = newThemeCls.getMethod("I").invoke(null);
            Class<?> iconKey     = Class.forName("org.jdownloader.gui.IconKey", true, cl);
            String kPOpen  = (String) iconKey.getField("ICON_PACKAGE_OPEN").get(null);
            String kPClose = (String) iconKey.getField("ICON_PACKAGE_CLOSED").get(null);
            String kRar    = (String) iconKey.getField("ICON_RAR").get(null);

            // Older AppWork builds also have getIcon(String, int, boolean useCache).
            Method getIcon = null; boolean cacheArg = false;
            for (Method m : newThemeCls.getMethods()) {
                if (!"getIcon".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 2 && p[0] == String.class && p[1] == int.class) {
                    if (p.length == 3 && p[2] == boolean.class) { getIcon = m; cacheArg = true; break; }
                    if (p.length == 2 && getIcon == null) getIcon = m;
                }
            }
            if (getIcon == null) return null;

            javax.swing.Icon pOpen  = getIc(getIcon, cacheArg, newTheme, kPOpen, 16);
            javax.swing.Icon pClose = getIc(getIcon, cacheArg, newTheme, kPClose, 16);
            javax.swing.Icon rar    = getIc(getIcon, cacheArg, newTheme, kRar, 16);
            if (pOpen == null || pClose == null) return null;   // not resolvable yet -> retry

            // Order must match patchFileColumn: {packageOpen, packageClosed, archive, archiveOpen}.
            // boxIcon(true) = "+" (collapsed), boxIcon(false) = "-" (expanded).
            Object iconPackageOpen   = mergedToggle(false, pOpen);   // expanded package
            Object iconPackageClosed = mergedToggle(true,  pClose);  // collapsed package
            Object iconArchive       = mergedToggle(true,  rar);     // collapsed archive
            Object iconArchiveOpen   = mergedToggle(false, rar);     // expanded archive
            System.out.println("[jd-dialog-agent] rebuilt light package-toggle icons for FileColumn");
            return new Object[] { iconPackageOpen, iconPackageClosed, iconArchive, iconArchiveOpen };
        } catch (Throwable t) {
            if (!toggleWarnLogged) { toggleWarnLogged = true;
                System.out.println("[jd-dialog-agent] package-toggle rebuild unavailable: " + t); }
            return null;
        }
    }

    /** Invoke NewTheme.getIcon(key,size) or getIcon(key,size,false) depending on the API. */
    private static javax.swing.Icon getIc(Method m, boolean cacheArg, Object theme, String key, int size) throws Exception {
        Object r = cacheArg ? m.invoke(theme, key, size, false) : m.invoke(theme, key, size);
        return (r instanceof javax.swing.Icon) ? (javax.swing.Icon) r : null;
    }

    /** A FileColumn cell icon: our light [+]/[-] box (centered in a 16px slot) + JD's package/
     *  rar icon to its right, matching JD's ExtMergedIcon(toggle,0,0).add(pkg,16,0) geometry. */
    private static javax.swing.Icon mergedToggle(final boolean plus, final javax.swing.Icon pkg) {
        final javax.swing.Icon toggle = boxIcon(plus);   // 11x11 light [+]/[-]
        return new javax.swing.Icon() {
            public int getIconWidth()  { return 16 + (pkg != null ? pkg.getIconWidth() : 0); }
            public int getIconHeight() { return Math.max(16, pkg != null ? pkg.getIconHeight() : 0); }
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                toggle.paintIcon(c, g, x + 2, y + 2);   // center the 11px box in the 16px slot
                if (pkg != null) pkg.paintIcon(c, g, x + 16, y);
            }
        };
    }

    /** Overwrite col's four cached toggle icons if it is a FileColumn. Returns true if any
     *  field changed (needs a repaint). Identity-compared -> idempotent per tick. */
    private static boolean patchFileColumn(Object col) {
        if (col == null || !"FileColumn".equals(col.getClass().getSimpleName())) return false;
        boolean changed = false;
        changed |= setFinalIfDiff(col, "iconPackageOpen",   toggleIcons[0]);
        changed |= setFinalIfDiff(col, "iconPackageClosed", toggleIcons[1]);
        changed |= setFinalIfDiff(col, "iconArchive",       toggleIcons[2]);
        changed |= setFinalIfDiff(col, "iconArchiveOpen",   toggleIcons[3]);
        return changed;
    }

    private static boolean setFinalIfDiff(Object target, String name, Object val) {
        try {
            Field f = target.getClass().getDeclaredField(name);   // declared on FileColumn
            f.setAccessible(true);
            if (f.get(target) == val) return false;               // already ours
            f.set(target, val);                                   // non-static final instance -> OK after setAccessible
            return true;
        } catch (Throwable ignore) { return false; }
    }

    // Two visibility gaps on plain dark. The linkgrabber's bottom panel toggles are no
    // JCheckBoxMenuItem, so FlatLaf draws no check for them; they get a badge while selected.
    // The overview panel corner buttons rebuild a dark glyph on each paint and ignore setIcon,
    // so a small ButtonUI tints that glyph light. A client property guards both, so the tick
    // repeats no work.
    static void themeKaynExtras() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) themeKaynExtrasIn(w);
        }
    }
    private static void themeKaynExtrasIn(Component c) {
        try {
            if (c instanceof javax.swing.JMenuItem) {
                markCheckToggle((javax.swing.JMenuItem) c);
            } else if (c instanceof AbstractButton && c.getClass().getName().contains(".overviewpanel.")) {
                installOverviewCornerButton((AbstractButton) c);
            }
        } catch (Throwable ignore) { }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) themeKaynExtrasIn(ch);
        }
    }

    /** Wraps a checkbox menu item's glyph, or a blank slot when it has none, in an icon that draws
     *  a check badge while the item is selected. */
    private static void markCheckToggle(javax.swing.JMenuItem mi) {
        boolean checkbox = (mi instanceof javax.swing.JCheckBoxMenuItem)
                || mi.getClass().getSimpleName().contains("CheckBox");
        if (!checkbox) return;
        javax.swing.Icon cur = mi.getIcon();
        Object mine = mi.getClientProperty("jdp.kToggle");
        if (cur == mine && mine != null) return;                 // already wrapped
        javax.swing.Icon base = (cur != null) ? cur : K_BLANK16; // no-icon items still get a slot for the badge
        javax.swing.Icon wrapped = stateCheckIcon(base, mi);
        mi.putClientProperty("jdp.kToggle", wrapped);
        mi.setIcon(wrapped);
        mi.setSelectedIcon(wrapped);
    }

    /** Adds a check badge (light disc, dark check, bottom right) while the item is selected. Reading
     *  the state at paint time avoids a listener race. */
    private static javax.swing.Icon stateCheckIcon(final javax.swing.Icon base, final javax.swing.JMenuItem mi) {
        return new javax.swing.Icon() {
            public int getIconWidth()  { return base.getIconWidth(); }
            public int getIconHeight() { return base.getIconHeight(); }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                if (!mi.isSelected()) return;
                int w = base.getIconWidth(), h = base.getIconHeight();
                int d = Math.max(9, Math.round(w * 0.62f));
                int bx = x + w - d, by = y + h - d;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(K_CHECK_DISC);
                g2.fillOval(bx, by, d, d);
                g2.setColor(K_CHECK_MARK);
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.3f, d / 6.5f),
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                int[] xs = { bx + Math.round(d * 0.26f), bx + Math.round(d * 0.44f), bx + Math.round(d * 0.74f) };
                int[] ys = { by + Math.round(d * 0.52f), by + Math.round(d * 0.68f), by + Math.round(d * 0.34f) };
                g2.drawPolyline(xs, ys, 3);
                g2.dispose();
            }
        };
    }

    /** The corner buttons ignore setIcon, so a small ButtonUI fills the panel background and draws the
     *  button's own glyph tinted light. It is installed again only when JD swaps the UI back. */
    private static void installOverviewCornerButton(final AbstractButton b) {
        if (!(b.getUI() instanceof OverviewCornerUI)) {
            try { b.setUI(new OverviewCornerUI()); b.setBorder(null); } catch (Throwable ignore) { }
        }
    }
    private static final class OverviewCornerUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void update(Graphics g, JComponent c) {
            Color bg = (c.getParent() != null && c.getParent().getBackground() != null)
                    ? c.getParent().getBackground() : BG;
            g.setColor(bg);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }
        @Override public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.Icon ic = b.getIcon();
            if (ic == null) return;
            javax.swing.Icon m = cornerGlyphTint(b, ic);
            if (m == null) return;
            int ix = (c.getWidth() - m.getIconWidth()) / 2, iy = (c.getHeight() - m.getIconHeight()) / 2;
            m.paintIcon(c, g, ix, iy);
        }
    }
    /** Cache the light tint of a corner button's glyph, keyed on the base-icon identity, so a button that hands
     *  back a fresh icon each getIcon() still only re-tints when the glyph actually changes. */
    private static javax.swing.Icon cornerGlyphTint(AbstractButton b, javax.swing.Icon ic) {
        if (ic == b.getClientProperty("jdp.kCornerBase") && b.getClientProperty("jdp.kCornerLit") instanceof javax.swing.Icon)
            return (javax.swing.Icon) b.getClientProperty("jdp.kCornerLit");
        javax.swing.Icon m = tintSolidLight(ic, K_CORNER_LIGHT);
        if (m == null) m = ic;
        b.putClientProperty("jdp.kCornerBase", ic);
        b.putClientProperty("jdp.kCornerLit", m);
        return m;
    }
    /** Recolour every non-transparent pixel of an icon to `tone`, preserving its alpha (so the glyph shape and
     *  anti-aliasing survive). Falls back to the original icon if it renders empty. */
    private static javax.swing.Icon tintSolidLight(javax.swing.Icon ic, Color tone) {
        try {
            int w = ic.getIconWidth(), h = ic.getIconHeight();
            if (w <= 0 || h <= 0) return ic;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try { ic.paintIcon(null, g, 0, 0); } catch (Throwable t) { g.dispose(); return ic; }
            g.dispose();
            int rgb = tone.getRGB() & 0x00ffffff;
            boolean any = false;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int a = (img.getRGB(x, y) >>> 24);
                if (a != 0) { img.setRGB(x, y, (a << 24) | rgb); any = true; }
            }
            if (!any) return ic;
            return new javax.swing.ImageIcon(img);
        } catch (Throwable t) { return ic; }
    }
}
