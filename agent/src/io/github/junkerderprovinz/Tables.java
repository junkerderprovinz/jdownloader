package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.github.junkerderprovinz.Cards.*;
import static io.github.junkerderprovinz.Chrome.*;
import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Dialogs.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Settings.*;

/** Download and linkgrabber tables: status and row icons, headers, progress bars and config tables. */
final class Tables {

    // The width-lock padlock comes from ExtTableHeaderRenderer's private lockedWidth field, which no
    // getIcon call returns. Swapped on every tick so it survives header rebuilds; replacements are marked.
    static void fixWidthLockIcon() {
        if (!isHighlighter()) return;
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                List<JTable> tabs = new ArrayList<JTable>();
                collectTables(w, tabs);
                for (JTable t : tabs) {
                    javax.swing.table.TableColumnModel cm = t.getColumnModel();
                    for (int i = 0; i < cm.getColumnCount(); i++) {
                        Object hr = cm.getColumn(i).getHeaderRenderer();
                        if (hr instanceof HoverHeaderRenderer) hr = ((HoverHeaderRenderer) hr).base;
                        swapLockedWidth(hr);
                    }
                }
            }
        } catch (Throwable ignore) { }
    }
    private static void swapLockedWidth(Object hr) {
        if (hr == null || !hr.getClass().getName().contains("ExtTableHeaderRenderer")) return;
        for (Class<?> c = hr.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field f;
            try { f = c.getDeclaredField("lockedWidth"); } catch (NoSuchFieldException e) { continue; }
            try {
                f.setAccessible(true);
                Object v = f.get(hr);
                if (!(v instanceof javax.swing.Icon)) return;
                javax.swing.Icon ic = (javax.swing.Icon) v;
                if (EXT_MONO_MARK.containsKey(ic)) return;
                int s = Math.max(8, Math.min(ic.getIconWidth(), ic.getIconHeight()));
                javax.swing.Icon base = tablerBase("lock", s, s);
                if (base == null) return;
                javax.swing.Icon lock = tintIcon(base, EXPANDER_LIGHT, null);
                f.set(hr, lock);
                EXT_MONO_MARK.put(lock, Boolean.TRUE);
            } catch (Throwable ignore) { }
            return;
        }
    }

    /**
     * The download list and account traffic bars are AppWork RendererProgressBars. Their fill is
     * FlatLaf's accent computed at runtime, and as cell renderers they sit outside the component
     * tree. AppWork keeps the bar instances in ExtProgressColumn fields and does not colour them
     * per cell, so a colour set on those instances sticks. It runs on every tick to catch tables
     * opened later, such as the account manager.
     */
    static void retintProgressBars() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                for (Object col : extColumns(t)) {
                    recolorBarFields(col);
                    recolorExpanderFields(col);
                }
            }
        }
    }

    static void collectTables(Container c, List<JTable> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTable) out.add((JTable) child);
            if (child instanceof Container) collectTables((Container) child, out);
        }
    }

    /** AppWork ExtColumn objects of a table (they hold the renderer progress bars). */
    static List<Object> extColumns(JTable t) {
        List<Object> cols = new ArrayList<>();
        try {
            javax.swing.table.TableColumnModel cm = t.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                Object r = cm.getColumn(i).getCellRenderer();
                if (r != null) cols.add(r);
            }
        } catch (Exception ignore) { }
        try {
            Object model = t.getModel();
            Object list = model.getClass().getMethod("getColumns").invoke(model);
            if (list instanceof Collection) cols.addAll((Collection<?>) list);
        } catch (Exception ignore) { }
        return cols;
    }

    /** FileColumn indents its rows through the normalBorder and leftGapBorder fields, about 5px left of
     *  the column header; both are moved right so the content lines up with the header. */
    private static final int NAME_INDENT = 5;
    static void indentNameColumns() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables)
                for (Object col : extColumns(t))
                    if (col != null && col.getClass().getName().endsWith(".FileColumn")) {
                        bumpBorderLeft(col, "normalBorder", 0, NAME_INDENT);
                        bumpBorderLeft(col, "leftGapBorder", 32, NAME_INDENT);
                    }
        }
    }

    /** Widens the left inset of the EmptyBorder in field {@code name}, only while it still has its original
     *  value so repeated passes do not add up. */
    private static void bumpBorderLeft(Object col, String name, int baseLeft, int add) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> k = col.getClass(); k != null && k != Object.class && f == null; k = k.getSuperclass())
                try { f = k.getDeclaredField(name); } catch (NoSuchFieldException nsf) { }
            if (f == null) return;
            f.setAccessible(true);
            Object b = f.get(col);
            if (!(b instanceof javax.swing.border.EmptyBorder)) return;
            java.awt.Insets in = ((javax.swing.border.EmptyBorder) b).getBorderInsets();
            if (in.left != baseLeft) return;
            f.set(col, javax.swing.BorderFactory.createEmptyBorder(in.top, baseLeft + add, in.bottom, in.right));
        } catch (Throwable ignore) { }
    }

    /** Set our dark fill/track on every JProgressBar-typed field of the object. */
    private static void recolorBarFields(Object col) {
        if (col == null) return;
        for (Class<?> k = col.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!JProgressBar.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object bar = f.get(col);
                    if (bar instanceof JProgressBar) {
                        JProgressBar pb = (JProgressBar) bar;
                        // Set on the instance so it beats FlatLaf's per-cell default.
                        Color fill = isHighlighter() ? accentColor() : BAR_FILL;
                        if (!fill.equals(pb.getForeground())) pb.setForeground(fill);
                        if (!BAR_TRACK.equals(pb.getBackground())) pb.setBackground(BAR_TRACK);
                        // On the hovered row the cell background is the accent, which would show through a
                        // transparent track and hide the light percentage text.
                        if (isHighlighter() && !pb.isOpaque()) pb.setOpaque(true);
                        // BasicProgressBarUI draws the percentage in selectionForeground over the fill and in
                        // selectionBackground over the track. The UI caches both at install time, before the
                        // UIManager keys are set, so they are written into the UI instance.
                        if (isHighlighter()) {
                            Object ui = pb.getUI();
                            if (ui != null) {
                                setUiColorField(ui, "selectionForeground", accentFg());
                                setUiColorField(ui, "selectionBackground", SIDEBAR_TEXT);
                            }
                        }
                    }
                } catch (Exception ignore) { }
            }
        }
    }

    static void setUiColorField(Object ui, String name, Color val) {
        for (Class<?> k = ui.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                if (!val.equals(f.get(ui))) f.set(ui, val);
                return;
            } catch (NoSuchFieldException nsf) {
                // declared in a superclass
            } catch (Throwable t) {
                return;
            }
        }
    }

    // JD re-provisions its black expander and column-lock glyphs, and the restore on disk loses that race,
    // so the Icon fields JD paints from are tinted light in memory on every tick.
    static final Color EXPANDER_LIGHT = PAL_MONO;
    // Replacements this agent made, so they are not tinted again on every tick.
    private static final java.util.Map<javax.swing.Icon, Boolean> EXT_MONO_MARK =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, Boolean>());
    private static void recolorExpanderFields(Object col) {
        if (col == null) return;
        for (Class<?> k = col.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!javax.swing.Icon.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object o = f.get(col);
                    if (!(o instanceof javax.swing.Icon)) continue;
                    javax.swing.Icon ic = (javax.swing.Icon) o;
                    if (EXT_MONO_MARK.containsKey(ic)) continue;
                    String key = iconKey(ic);
                    javax.swing.Icon repl = null;
                    if (key != null && (key.contains("tree_plus") || key.contains("tree_minus")
                            || key.contains("lockColumn") || key.contains("widthLocked"))) {
                        repl = tintSolid(ic, EXPANDER_LIGHT);
                    }
                    // Status glyphs stay untouched here: cleanTaskIcon matches them against these fields by identity.
                    if (repl != null && repl != ic) { f.set(col, repl); EXT_MONO_MARK.put(repl, Boolean.TRUE); }
                } catch (Throwable ignore) { }
            }
        }
    }

    /** Paints the light icon on a normal row and the dark one when the cell background is the accent. */
    static final class RowFlipIcon implements javax.swing.Icon {
        private final javax.swing.Icon light, dark;
        RowFlipIcon(javax.swing.Icon l, javax.swing.Icon d) { light = l; dark = d; }
        public int getIconWidth() { return light.getIconWidth(); }
        public int getIconHeight() { return light.getIconHeight(); }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean hot = false;
            try {
                Color bg = (c != null) ? c.getBackground() : null;
                if (bg != null) {
                    Color acc = accentColor();
                    hot = Math.abs(bg.getRed() - acc.getRed()) < 40 && Math.abs(bg.getGreen() - acc.getGreen()) < 40
                            && Math.abs(bg.getBlue() - acc.getBlue()) < 40;
                }
            } catch (Throwable ignore) { }
            (hot ? dark : light).paintIcon(c, g, x, y);
        }
    }

    /** Maps a status glyph to a Tabler key, or null. The visible glyph is usually a MergedIcon that
     *  fillColumnHelper composed, so after the field and key lookups its parts are classified. */
    static String classifyStatus(Object owner, javax.swing.Icon original) {
        String result = keyFromOwnerFields(owner, original);
        if (result == null) result = normalizeStatusKey(iconKey(original));
        if (result == null) {
            java.util.List<String> parts = new java.util.ArrayList<String>();
            collectSubIconKeys(owner, original, parts, 0);
            result = pickDominant(parts);
        }
        return result;
    }

    /** The Tabler key for an icon that is one of the owning column's status fields, else null. */
    private static String keyFromOwnerFields(Object owner, javax.swing.Icon original) {
        if (owner == null || original == null) return null;
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!javax.swing.Icon.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(owner) == original) { String k = statusKeyForField(f.getName()); if (k != null) return k; }
                } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    /** Classifies the parts of a composite icon. MergedIcon keeps them in a TreeSet of wrapper entries rather
     *  than as plain Icons, so JD's own holder types are followed too, with a depth limit. */
    private static void collectSubIconKeys(Object owner, Object node, java.util.List<String> out, int depth) {
        if (node == null || depth > 6) return;
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); consumeValue(owner, f.get(node), node, out, depth); }
                catch (Throwable ignore) { }
            }
        }
    }

    private static void consumeValue(Object owner, Object v, Object node, java.util.List<String> out, int depth) {
        if (v == null || v == node) return;
        if (v instanceof javax.swing.Icon) {
            javax.swing.Icon sub = (javax.swing.Icon) v;
            String k = keyFromOwnerFields(owner, sub);
            if (k == null) k = normalizeStatusKey(iconKey(sub));
            if (k != null) out.add(k);
            else collectSubIconKeys(owner, sub, out, depth + 1);
        } else if (v instanceof Object[]) {
            for (Object o : (Object[]) v) consumeValue(owner, o, node, out, depth);
        } else if (v instanceof java.util.Collection) {
            for (Object o : (java.util.Collection<?>) v) consumeValue(owner, o, node, out, depth);
        } else {
            String vc = v.getClass().getName();
            if (vc.startsWith("org.jdownloader.") || vc.startsWith("org.appwork.") || vc.startsWith("jd."))
                collectSubIconKeys(owner, v, out, depth + 1);
        }
    }

    /** When a row merges several states, an error wins over success and success over progress. */
    private static String pickDominant(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        for (String want : new String[] { "error", "true", "extract", "wait", "run", "help" })
            if (keys.contains(want)) return want;
        return keys.get(0);
    }

    private static String statusKeyForField(String n) {
        if (n == null) return null;
        if (n.contains("Failed")) return "error";                       // download ok but extraction failed
        if (n.startsWith("false")) return "error";                      // download failed
        if (n.contains("Extracted") || n.startsWith("true") || n.startsWith("ok")) return "true";
        if (n.equals("extracting")) return "extract";
        if (n.equals("startingIcon")) return "run";
        if (n.equals("finalizingIcon")) return "wait";
        if (n.equals("trueIcon")) return "true";
        if (n.equals("online")) return "true";                          // AvailabilityColumn
        if (n.equals("offline")) return "error";
        if (n.equals("unknown")) return "help";
        if (n.equals("mixed")) return "true";
        if (n.startsWith("iconArchive")) return "extract";              // FileColumn
        if (n.equals("iconPackageOpen")) return "folder_open";
        if (n.startsWith("iconPackage")) return "folder_add";           // collapsed, so the folder shows a plus
        return null;
    }

    private static String normalizeStatusKey(String ik) {
        if (ik == null) return null;
        String s = ik.toLowerCase();
        if (s.contains("extract")) {                        // before the generic checks below
            if (s.contains("error") || s.contains("fail")) return "error";
            if (s.contains("ok")) return "true";
            return "extract";
        }
        if (s.contains("error") || s.contains("false") || s.contains("offline") || s.contains("fail")) return "error";
        if (s.contains("ok") || s.contains("true") || s.contains("finish") || s.contains("online")) return "true";
        if (s.contains("wait") || s.contains("final") || s.contains("hourglass")) return "wait";
        if (s.contains("start") || s.equals("run")) return "run";
        if (s.contains("help") || s.contains("unknown") || s.contains("question")) return "help";
        return null;
    }

    // The list icons are recoloured in a wrapping cell renderer, since JD's live repaints would undo any
    // change made to the icons themselves. The hoster column keeps its coloured favicons.
    static void monoTableRowIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                if (inConfigPanel(t)) continue;                       // handled by wrapExtCellRenderer
                javax.swing.table.TableColumnModel cm = t.getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    javax.swing.table.TableColumn tc = cm.getColumn(i);
                    javax.swing.table.TableCellRenderer cur = tc.getCellRenderer();
                    if (cur == null || cur instanceof MonoIconRenderer) continue;
                    String cn = cur.getClass().getName().toLowerCase();
                    if (cn.contains("favicon") || cn.contains("hoster")) continue;
                    tc.setCellRenderer(new MonoIconRenderer(cur));
                }
            }
        }
    }
    private static boolean inConfigPanel(Component c) {
        for (Container p = c.getParent(); p != null; p = p.getParent())
            if (isConfigPanel(p.getClass())) return true;
        return false;
    }
    // Keyless status glyphs are shared, so caching their tint saves an image per row on every repaint.
    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> ROW_MONO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, javax.swing.Icon>());
    static final class MonoIconRenderer implements javax.swing.table.TableCellRenderer {
        private final javax.swing.table.TableCellRenderer orig;
        MonoIconRenderer(javax.swing.table.TableCellRenderer o) { orig = o; }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            Component c = orig.getTableCellRendererComponent(t, v, sel, foc, row, col);
            // The task column returns a custom component with the glyph on an inner label, so the whole
            // returned tree is walked.
            monoRowIconsIn(c, sel, Boolean.TRUE.equals(t.getClientProperty("jdp.extCfg")));
            return c;
        }
    }
    private static void monoRowIconsIn(Component c, boolean sel, boolean cfg) {
        javax.swing.Icon ic = null;
        if (c instanceof javax.swing.JLabel) ic = ((javax.swing.JLabel) c).getIcon();
        else if (c instanceof javax.swing.AbstractButton) ic = ((javax.swing.AbstractButton) c).getIcon();
        if (ic != null) {
            javax.swing.Icon mono = cfg && isMergedIcon(ic) ? monoMergedIcon(ic, c.getForeground()) : monoRowIcon(ic, sel);
            if (mono != ic) {
                if (c instanceof javax.swing.JLabel) ((javax.swing.JLabel) c).setIcon(mono);
                else ((javax.swing.AbstractButton) c).setIcon(mono);
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoRowIconsIn(ch, sel, cfg);
    }
    /** A keyed glyph becomes its Tabler twin, a keyless one a mono tint of its own shape. */
    private static javax.swing.Icon monoRowIcon(javax.swing.Icon ic, boolean sel) {
        if (isSiteLogo(ic)) return ic;
        Color tone = sel ? accentFg() : SIDEBAR_TEXT;
        if (iconKey(ic) != null) return tablerIcon(ic, tone, null);
        if (sel) return tintSolid(ic, tone);
        javax.swing.Icon m = ROW_MONO.get(ic);
        if (m == null) { m = tintSolid(ic, tone); ROW_MONO.put(ic, m); }
        return m;
    }

    private static boolean isMergedIcon(javax.swing.Icon ic) {
        for (Class<?> k = ic.getClass(); k != null; k = k.getSuperclass())
            if (k.getName().equals("org.appwork.swing.components.ExtMergedIcon")) return true;
        return false;
    }
    // The help and reset buttons of the advanced settings are one MergedIcon, and a silhouette of it turns
    // the help disc into a blank dot. Each part gets its Tabler twin in the cell text colour, and parts JD
    // greyed out stay dim.
    private static final java.util.Map<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>> MERGED_MONO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>>());
    private static javax.swing.Icon monoMergedIcon(javax.swing.Icon ic, Color fg) {
        if (isSiteLogo(ic)) return ic;
        Color tone = fg != null ? fg : SIDEBAR_TEXT;
        Integer tk = Integer.valueOf(tone.getRGB());
        java.util.Map<Integer, javax.swing.Icon> per = MERGED_MONO.computeIfAbsent(ic, k -> new java.util.concurrent.ConcurrentHashMap<>());
        javax.swing.Icon hit = per.get(tk);
        if (hit != null) return hit;
        javax.swing.Icon out;
        try {
            Class<?> ext = ic.getClass();
            while (!ext.getName().equals("org.appwork.swing.components.ExtMergedIcon")) ext = ext.getSuperclass();
            java.util.Collection<?> parts = (java.util.Collection<?>) fieldValue(ic, ext, "entries");
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(ic.getIconWidth(), ic.getIconHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            for (Object e : parts) {
                javax.swing.Icon part = (javax.swing.Icon) e.getClass().getField("icon").get(e);
                String key = iconKey(part);
                boolean off = key != null && key.endsWith("_disabled");
                if (off) key = key.substring(0, key.length() - "_disabled".length());
                javax.swing.Icon base = tablerBase(key, part.getIconWidth(), part.getIconHeight());
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, off ? 0.4f : 1f));
                tintSolid(base != null ? base : part, tone).paintIcon(null, g,
                        e.getClass().getField("x").getInt(e), e.getClass().getField("y").getInt(e));
            }
            g.dispose();
            out = new javax.swing.ImageIcon(img);
        } catch (Throwable t) { out = tintSolid(ic, tone); }
        per.put(tk, out);
        return out;
    }

    // Site favicons keep their colours. Generic renderers such as the plugin settings list show them too,
    // so the check looks at the icon class and any icon it wraps rather than at the renderer.
    static boolean isSiteLogo(javax.swing.Icon ic) { return isSiteLogoIn(ic, 0); }
    private static boolean isSiteLogoIn(javax.swing.Icon ic, int depth) {
        if (ic == null || depth > 3) return false;
        String cn = ic.getClass().getName().toLowerCase();
        if (cn.contains("favicon") || cn.contains("domaininfo")) return true;
        for (Class<?> cl = ic.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass())
            for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(ic);
                    if (v instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) v, depth + 1)) return true;
                    if (v instanceof Object[]) for (Object o : (Object[]) v)
                        if (o instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) o, depth + 1)) return true;
                    if (v instanceof java.util.Collection) for (Object o : (java.util.Collection<?>) v)
                        if (o instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) o, depth + 1)) return true;
                } catch (Throwable ignore) { }
            }
        return false;
    }

    // Mono icons in the settings tables (packagizer, extensions, advanced). The jdp.extCfg mark set here
    // is what wrapExtCellRenderer reads.
    static void monoConfigTableIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                boolean cfg = inConfigPanel(t);
                if (!Boolean.valueOf(cfg).equals(t.getClientProperty("jdp.extCfg")))
                    t.putClientProperty("jdp.extCfg", Boolean.valueOf(cfg));
                if (!cfg) continue;
                javax.swing.table.TableColumnModel cm = t.getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    javax.swing.table.TableColumn tc = cm.getColumn(i);
                    javax.swing.table.TableCellRenderer cur = tc.getCellRenderer();
                    if (cur == null) { try { cur = t.getCellRenderer(0, i); } catch (Throwable ig) { cur = null; } }
                    if (cur == null || cur instanceof MonoIconRenderer) continue;
                    String cn = cur.getClass().getName().toLowerCase();
                    if (cn.contains("favicon") || cn.contains("hoster") || cn.contains("domain")) continue;
                    tc.setCellRenderer(new MonoIconRenderer(cur));
                }
                java.util.Set<Class<?>> seen = new java.util.HashSet<Class<?>>();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    Class<?> cc; try { cc = t.getColumnClass(i); } catch (Throwable ig) { continue; }
                    if (!seen.add(cc)) continue;
                    javax.swing.table.TableCellRenderer def = t.getDefaultRenderer(cc);
                    if (def == null || def instanceof MonoIconRenderer) continue;
                    String dn = def.getClass().getName().toLowerCase();
                    if (dn.contains("favicon") || dn.contains("hoster") || dn.contains("domain")) continue;
                    t.setDefaultRenderer(cc, new MonoIconRenderer(def));
                }
            }
        }
    }

    // The status bar glyphs in the bottom-right corner are keyless ImageIcons that no other pass reaches.
    // Only that corner band is touched, so content icons stay as they are.
    static void monoCornerIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            int winW = w.getWidth(), winH = w.getHeight();
            if (winW < 400 || winH < 400) continue;             // the main frame only
            monoCornerIn(w, w, winW, winH);
        }
    }
    private static void monoCornerIn(Component c, Window win, int winW, int winH) {
        try {
            javax.swing.Icon ic = (c instanceof javax.swing.JLabel) ? ((javax.swing.JLabel) c).getIcon()
                    : (c instanceof AbstractButton) ? ((AbstractButton) c).getIcon() : null;
            if (ic != null && iconKey(ic) == null && !isSiteLogo(ic) && c.getParent() != null) {
                java.awt.Point pt = javax.swing.SwingUtilities.convertPoint(c.getParent(), c.getLocation(), win);
                boolean rightCol = pt.x + c.getWidth() > winW - 90;
                // The overview panel's settings and close buttons derive their glyph on every paint and ignore
                // setIcon, so they get their own ButtonUI instead.
                if (rightCol && (c instanceof AbstractButton) && c.getClass().getName().contains(".overviewpanel.")) {
                    hlInstallOverviewCornerButton((AbstractButton) c);
                } else if (rightCol && pt.y > winH - 40) {
                    if (c instanceof javax.swing.JLabel) {
                        javax.swing.JLabel l = (javax.swing.JLabel) c;
                        if (ic != l.getClientProperty("jdp.monoCorner")) {
                            javax.swing.Icon m = tintSolid(ic, EXPANDER_LIGHT);
                            if (m != ic) { l.setIcon(m); l.putClientProperty("jdp.monoCorner", m); }
                        }
                    } else if (c instanceof AbstractButton) {
                        AbstractButton b = (AbstractButton) c;
                        if (ic != b.getClientProperty("jdp.monoCorner")) {
                            javax.swing.Icon m = tintSolid(ic, EXPANDER_LIGHT);
                            if (m != ic) { b.setIcon(m); b.putClientProperty("jdp.monoCorner", m); }
                        }
                    }
                }
            }
        } catch (Throwable ig) { }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoCornerIn(ch, win, winW, winH);
    }

    // A taller column header for the two main tables, with the hovered title in the accent. A fixed
    // preferred size on the header freezes its width, so the cells get vertical padding instead and
    // JTableHeader grows to the tallest one.
    static void growTableHeaders() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                String sn = t.getClass().getSimpleName();
                if (!(sn.contains("DownloadsTable") || sn.contains("LinkGrabberTable"))) continue;
                javax.swing.table.JTableHeader h = t.getTableHeader();
                if (h == null) continue;
                installHeaderHover(h);
                wrapHeaderColumnRenderers(h);
            }
        }
    }
    private static void installHeaderHover(final javax.swing.table.JTableHeader h) {
        if (h.getClientProperty("jdp.hdrHover") != null) return;
        h.putClientProperty("jdp.hdrHover", Boolean.TRUE);
        h.putClientProperty("jdp.hoverCol", Integer.valueOf(-1));
        h.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int col = h.columnAtPoint(e.getPoint());
                Object cur = h.getClientProperty("jdp.hoverCol");
                if (!(cur instanceof Integer) || ((Integer) cur).intValue() != col) {
                    h.putClientProperty("jdp.hoverCol", Integer.valueOf(col));
                    h.repaint();
                }
            }
        });
        h.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                h.putClientProperty("jdp.hoverCol", Integer.valueOf(-1));
                h.repaint();
            }
        });
        javax.swing.table.TableCellRenderer base = h.getDefaultRenderer();
        if (base != null && !(base instanceof HoverHeaderRenderer))
            h.setDefaultRenderer(new HoverHeaderRenderer(base, h));
    }
    // ExtTable paints the header through per-column renderers and never uses the default one. Runs on every
    // tick so rebuilt or reordered columns are wrapped again.
    private static void wrapHeaderColumnRenderers(javax.swing.table.JTableHeader h) {
        try {
            javax.swing.table.TableColumnModel cm = h.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                javax.swing.table.TableColumn tc = cm.getColumn(i);
                javax.swing.table.TableCellRenderer hr = tc.getHeaderRenderer();
                if (hr == null || hr instanceof HoverHeaderRenderer) continue;
                tc.setHeaderRenderer(new HoverHeaderRenderer(hr, h));
            }
        } catch (Throwable ignore) { }
    }
    private static final class HoverHeaderRenderer implements javax.swing.table.TableCellRenderer {
        final javax.swing.table.TableCellRenderer base;   // read by fixWidthLockIcon
        private final javax.swing.table.JTableHeader hdr;
        HoverHeaderRenderer(javax.swing.table.TableCellRenderer b, javax.swing.table.JTableHeader h) { base = b; hdr = h; }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int col) {
            Component c = base.getTableCellRendererComponent(t, v, s, f, r, col);
            try {
                Object hc = hdr.getClientProperty("jdp.hoverCol");
                boolean hot = (hc instanceof Integer) && ((Integer) hc).intValue() == col;
                if (c != null) c.setForeground(hot ? accentColor() : SIDEBAR_TEXT);
                if (c instanceof JComponent) {
                    JComponent jc = (JComponent) c;
                    javax.swing.border.Border cur = jc.getBorder();
                    java.awt.Insets in = (cur == null) ? new java.awt.Insets(0, 0, 0, 0) : cur.getBorderInsets(jc);
                    if (in.top < 6)
                        jc.setBorder(new javax.swing.border.EmptyBorder(6, Math.max(in.left, 6), 6, Math.max(in.right, 6)));
                }
            } catch (Throwable ig) { }
            return c;
        }
    }

    /**
     * The ExtTables in the settings panels ignore FlatLaf's Table and TableHeader keys and paint their
     * own header band, grid and frame, so each one is flattened at the instance level.
     */
    static void borderlessConfigTables() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) borderlessTablesIn(w, false);
        }
    }

    private static void borderlessTablesIn(Container c, boolean inConfig) {
        boolean nowConfig = inConfig || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component child : c.getComponents()) {
            if (nowConfig && child instanceof JTable) flattenConfigTable((JTable) child);
            if (child instanceof Container) borderlessTablesIn((Container) child, nowConfig);
        }
    }

    /**
     * Replaces the per-column ExtTableHeaderRenderer, which paints its own grey band whatever the header
     * background is. Being a renderer, it survives JD's repaints where a setBackground on the header does not.
     */
    private static final javax.swing.table.TableCellRenderer DARK_HEADER = new javax.swing.table.TableCellRenderer() {
        private final javax.swing.table.DefaultTableCellRenderer base = new javax.swing.table.DefaultTableCellRenderer();
        public Component getTableCellRendererComponent(JTable tbl, Object v, boolean s, boolean f, int r, int col) {
            Component c = base.getTableCellRendererComponent(tbl, v, s, f, r, col);
            c.setBackground(DIALOG_BG);
            c.setForeground(SIDEBAR_TEXT);
            if (c instanceof JComponent) {
                ((JComponent) c).setOpaque(true);
                ((JComponent) c).setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8));
            }
            return c;
        }
    };

    // The setters below revalidate and repaint even when nothing changes, and this runs on every tick.
    private static final javax.swing.border.Border NO_BORDER = javax.swing.BorderFactory.createEmptyBorder();
    private static final Dimension NO_SPACING = new Dimension(0, 0);
    private static void flattenConfigTable(JTable t) {
        try {
            if (t.getShowHorizontalLines() || t.getShowVerticalLines()) t.setShowGrid(false);
            if (!NO_SPACING.equals(t.getIntercellSpacing())) t.setIntercellSpacing(NO_SPACING);
            // UrlOrderTable forces horizontal lines on, so they take the card colour.
            if (!DIALOG_BG.equals(t.getGridColor())) t.setGridColor(DIALOG_BG);

            javax.swing.table.JTableHeader h = t.getTableHeader();
            if (h != null) {
                if (!DIALOG_BG.equals(h.getBackground())) h.setBackground(DIALOG_BG);
                if (h.getBorder() != NO_BORDER) h.setBorder(NO_BORDER);
                javax.swing.table.TableCellRenderer dr = h.getDefaultRenderer();
                if (dr instanceof JComponent) {
                    ((JComponent) dr).setBackground(DIALOG_BG);
                    ((JComponent) dr).setBorder(NO_BORDER);
                }
            }
            javax.swing.table.TableColumnModel hcm = t.getColumnModel();
            for (int i = 0; i < hcm.getColumnCount(); i++) {
                javax.swing.table.TableColumn col = hcm.getColumn(i);
                if (col.getHeaderRenderer() != DARK_HEADER) col.setHeaderRenderer(DARK_HEADER);
            }
            Container sp = SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, t);
            if (sp instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane s = (javax.swing.JScrollPane) sp;
                if (s.getBorder() != NO_BORDER) s.setBorder(NO_BORDER);
                if (s.getViewportBorder() != NO_BORDER) s.setViewportBorder(NO_BORDER);
                // The table background fills the viewport, so the card reaches down to the sidebar's bottom.
                if (!t.getFillsViewportHeight()) t.setFillsViewportHeight(true);
                javax.swing.JViewport vp = s.getViewport();
                if (vp != null && t.getBackground() != null
                        && !t.getBackground().equals(vp.getBackground())) {
                    vp.setBackground(t.getBackground());
                }
            }
            accentTableCheckmarks(t);
        } catch (Throwable ignore) { }
    }

    /** Accent colour for checkmarks in boolean columns, where the renderer is a JCheckBox. */
    private static void accentTableCheckmarks(JTable t) {
        try {
            Color acc = accentColor();
            if (acc == null) return;
            javax.swing.table.TableColumnModel cm = t.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                javax.swing.table.TableCellRenderer r = cm.getColumn(i).getCellRenderer();
                if (r instanceof javax.swing.JCheckBox) ((javax.swing.JCheckBox) r).setForeground(acc);
            }
        } catch (Throwable ignore) { }
    }
}
