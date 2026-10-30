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

/** Fixes for plain dark: package expander icons, the merged toggle icons and the forum visibility gaps. */
final class PlainDark {

    // --- Kayn (JD_Plain_Dark visibility, 2026-08-04) ---
    // Neutral (no accent: plain dark has none) light-on-dark tones for the two forum fixes below.
    private static final Color K_CHECK_DISC   = new Color(0xc6, 0xc6, 0xc6);   // light disc behind the toggle check (== BAR_FILL)
    private static final Color K_CHECK_MARK   = new Color(0x16, 0x16, 0x16);   // dark check on the light disc
    private static final Color K_CORNER_LIGHT = new Color(0xd0, 0xd0, 0xd0);   // light glyph for the overview-panel corner icons
    // A 16x16 fully transparent icon so a checkbox menu item that ships NO icon (e.g. "Sidebar visible")
    // still gets an icon slot to carry the check badge.
    private static final javax.swing.Icon K_BLANK16 = new javax.swing.Icon() {
        public int getIconWidth()  { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g, int x, int y) { }
    };

    // --- Package-expander icons (Linkgrabber + download list) --------------------
    // JD's ExtTable draws the package [+]/[-] toggle from the iconset keys
    // tree_plus / tree_minus (IconKey.ICON_PLUS/ICON_MINUS -> FileColumn, shared by
    // the download table and the linkgrabber). JD's own bundled "flat" iconset does
    // NOT contain those two files, so the image ships light-grey ones and the boot
    // script seeds them to /config/JDownloader/themes/flat/... . But JD self-updates
    // its core on every start and re-provisions that on-disk iconset dir AFTER the
    // boot seed, dropping exactly the two files it does not ship itself — so the
    // handle falls back to JD's dark bundled default and vanishes on #161616 (the
    // recurring "black [+]" report). The boot seed can't win that in-process race;
    // the agent runs in EVERY JVM via JAVA_TOOL_OPTIONS, INCLUDING the post-self-
    // update GUI JVM, so restoring the files here — once at premain (before the GUI
    // resolves them) and every tick (self-heal if JD wipes them mid-run) — puts them
    // back before FileColumn reads them, and NewTheme's disk-first lookup finds the
    // light copy. (The Swing Tree.collapsedIcon override elsewhere only covers real
    // JTrees in JD dialogs; it never touched this ExtTable handle.)
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
            } catch (Throwable ignore) { /* best effort — retried next tick */ }
        }
    }

    // --- BUG 2: rebuild FileColumn's cached [+]/[-] merged toggle icons ---------------
    // restoreExpanderIcons() keeps the LIGHT tree_plus/tree_minus SVGs on disk, but JD's
    // FileColumn bakes the package toggle into four PRIVATE FINAL merged-icon fields in
    // its constructor and never re-reads them, AND loads the toggle with useCache=false
    // (so clearing NewTheme's cache never touches it either). Once JD resolved the DARK
    // bundled fallback (a self-update dropped the two files after the boot seed), the light
    // disk copy changes nothing on screen -> the recurring "black [+]" report. Fix: rebuild
    // the four merged icons from the now-light SVGs (useCache=false = fresh disk read),
    // exactly as FileColumn's ctor does, and overwrite the final fields on every live
    // FileColumn. A non-static final INSTANCE field is writable after setAccessible(true)
    // on JDK 21. Icons are built ONCE and cached; each tick only re-sets fields if reverted.
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
                        // Build the light merged icons ONCE, using JD's OWN classloader (a live
                        // FileColumn gives it to us) — Class.forName on the agent's system loader
                        // can't see org.jdownloader.*/org.appwork.* (they live on JD's launcher
                        // loader), which is why the disk-restore-only fix never fired.
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

    /** Build FileColumn's four cell icons LIGHT by composing our OWN guaranteed-light [+]/[-]
     *  boxIcon with JD's package/rar icon — instead of rebuilding from JD's tree_plus/tree_minus
     *  (which can be dark-cached) via ExtMergedIcon. Only JD's package/rar icons are read (via
     *  getIcon), so there is no dependency on the on-disk toggle SVG, a useCache overload, or
     *  ExtMergedIcon's ctor signature. Returns null until NewTheme + the package icons resolve. */
    private static boolean toggleWarnLogged = false;

    private static Object[] buildToggleIcons(ClassLoader cl) {
        try {
            Class<?> newThemeCls = Class.forName("org.jdownloader.images.NewTheme", true, cl);
            Object   newTheme    = newThemeCls.getMethod("I").invoke(null);
            Class<?> iconKey     = Class.forName("org.jdownloader.gui.IconKey", true, cl);
            String kPOpen  = (String) iconKey.getField("ICON_PACKAGE_OPEN").get(null);
            String kPClose = (String) iconKey.getField("ICON_PACKAGE_CLOSED").get(null);
            String kRar    = (String) iconKey.getField("ICON_RAR").get(null);

            // getIcon(String,int) is the current API; an older AppWork snapshot also had a
            // getIcon(String,int,boolean useCache) overload. Discover whichever exists.
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

    // ------------------------------------------------------ Kayn plain-dark fixes
    // Two JD_Plain_Dark visibility gaps reported on the forum (kayn), reproduced live on the container:
    //   #2  The LinkGrabber "Customize this Bottom Panel" toggle menu (ExtCheckBoxMenuItem items) shows NO
    //       on/off state: FlatLaf's MenuItem.checkIcon is null for these items (they are not JCheckBoxMenuItem),
    //       so a checked toggle is indistinguishable from an unchecked one. Overlay a neutral check badge on the
    //       item's own glyph, painted only while it is selected (paint-time state -> no listener race).
    //   #1  The download OverviewPanel corner buttons (settings wrench + close X) are a near-invisible dark grey.
    //       Those buttons IGNORE setIcon (they re-derive their glyph each paint), so own their paint with a tiny
    //       ButtonUI that draws the button's own glyph tinted light. Both are idempotent (guarded on a client
    //       property) so the ~400ms tick never re-does work -> no CPU spin.
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

    /** #2: give a checkbox-style toggle menu item a visible ON state on plain dark. Wrap its glyph (or a blank
     *  slot, for items that ship no icon) in a paint-time icon that draws a neutral check badge only while the
     *  item is selected. Idempotent: once our wrapper is the icon, the tick skips it. */
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

    /** A menu-item icon that draws a neutral check BADGE (light disc + dark check, bottom-right) only while the
     *  item is selected. Paint-time state check -> no listener/timing race. */
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

    /** #1: the OverviewPanel corner buttons ignore setIcon (they re-derive a dark glyph each paint). Own their
     *  paint with a tiny ButtonUI that floods the panel background then draws the button's OWN glyph tinted light.
     *  Installed once and re-installed only if JD swaps the UI back; event-driven paint -> no tick, no spin. */
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
