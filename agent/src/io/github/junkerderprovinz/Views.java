package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;

import static io.github.junkerderprovinz.Cards.*;
import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Tables.*;

/** The Views popup of the download list and the docked Views panel of the linkgrabber. */
final class Views {

    // The Views popup of the download list shows its items as rounded chips instead of rows divided by
    // separators. FlatLaf still paints the accent hover on top.
    private static final String VIEWS_BADGED = "jdp.viewsBadged";

    static void badgeViewsMenu() {
        javax.swing.MenuElement[] path =
                javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
        for (javax.swing.MenuElement me : path) {
            if (!(me instanceof JPopupMenu)) continue;
            JPopupMenu pm = (JPopupMenu) me;
            if (!isViewsPopup(pm)) continue;
            hideSeparators(pm);
            for (Component ch : pm.getComponents()) {
                if (!(ch instanceof javax.swing.JMenuItem)) continue;
                javax.swing.JMenuItem mi = (javax.swing.JMenuItem) ch;
                if (mi.getClientProperty(VIEWS_BADGED) == Boolean.TRUE) continue;
                mi.setBorder(new MenuChipBorder(mi.getBorder()));
                mi.setOpaque(false);
                mi.putClientProperty(VIEWS_BADGED, Boolean.TRUE);
            }
            pm.revalidate(); pm.repaint();
        }
    }

    // A Views popup holds only check box and radio items plus at least one separator.
    private static boolean isViewsPopup(JPopupMenu pm) {
        boolean allChecky = pm.getComponentCount() > 0;
        int seps = 0;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator) { seps++; continue; }
            if (!(ch instanceof javax.swing.JCheckBoxMenuItem
                    || ch instanceof javax.swing.JRadioButtonMenuItem)) allChecky = false;
        }
        return allChecky && seps >= 1;
    }

    /** Rounded chip behind a menu item. Like SectionCardBorder it paints before the text, so the
     *  selection highlight lands on top. */
    private static final class MenuChipBorder implements javax.swing.border.Border {
        private final javax.swing.border.Border original;
        private static final Color CHIP = PAL_SURFACE;
        private static final int GAP = 3, PAD_V = 2, PAD_H = 6, ARC = UI_ARC;
        MenuChipBorder(javax.swing.border.Border o) { this.original = o; }
        public boolean isBorderOpaque() { return false; }
        public java.awt.Insets getBorderInsets(Component c) {
            java.awt.Insets in = (original != null) ? original.getBorderInsets(c)
                    : new java.awt.Insets(0, 0, 0, 0);
            return new java.awt.Insets(in.top + GAP + PAD_V, in.left + GAP + PAD_H,
                    in.bottom + GAP + PAD_V, in.right + GAP + PAD_H);
        }
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CHIP);
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                        x + GAP, y + GAP, w - 2 * GAP, h - 2 * GAP, ARC, ARC));
            } finally { g2.dispose(); }
            if (original != null) original.paintBorder(c, g, x, y, w, h);
        }
    }

    /** The innermost non-popup container that holds the Views sections. */
    private static Container findViewsHost(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                Container r = findViewsHost((Container) ch);
                if (r != null) return r;
            }
        }
        if (!(c instanceof JPopupMenu) && subtreeHasAllViewsItems(c)) return c;
        return null;
    }
    /** Counts Views section headers rather than matching their titles, which are localised. */
    private static boolean subtreeHasAllViewsItems(Container c) {
        int[] n = { 0 };
        countViewsHeaders(c, n);
        return n[0] >= 2;
    }
    private static void countViewsHeaders(Container c, int[] n) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && isViewsHeader(ch.getClass())) n[0]++;
            if (ch instanceof Container) countViewsHeaders((Container) ch, n);
        }
    }

    // The docked Views panel of the linkgrabber shows its section headers as chips, like the Views popup,
    // without the separator lines between them.
    static void badgeViewsPanel() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            Container host = findViewsHost(w);
            if (host == null) continue;
            monoViewsTables(host);
            boolean changed = badgeViewsHeaders(host);
            changed |= stripHeaderScrollDividers(host);
            changed |= flattenViewsGrids(host);
            hideSeparators(host);
            if (changed) { host.revalidate(); host.repaint(); }
        }
    }

    /** Returns true if a header got its chip in this pass. */
    private static boolean badgeViewsHeaders(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && isViewsHeader(ch.getClass())) {
                JComponent header = (JComponent) ch;
                if (header.getClientProperty(VIEWS_BADGED) != Boolean.TRUE) {
                    // MenuChipBorder paints its inner border on top of the chip, so a separator line goes.
                    javax.swing.border.Border ob = header.getBorder();
                    if (ob instanceof javax.swing.border.MatteBorder || ob instanceof javax.swing.border.LineBorder
                            || ob instanceof javax.swing.border.EtchedBorder) ob = null;
                    header.setBorder(new MenuChipBorder(ob));
                    header.setOpaque(false);
                    header.putClientProperty(VIEWS_BADGED, Boolean.TRUE);
                    changed = true;
                }
            }
            if (ch instanceof Container) changed |= badgeViewsHeaders((Container) ch);
        }
        return changed;
    }

    /** The package check keeps out org.jdownloader.extensions.Header from the settings pages. */
    private static boolean isViewsHeader(Class<?> k) {
        String cn = k.getName();
        return (cn.endsWith(".Header") && cn.startsWith("org.jdownloader.gui.views.components."))
                || cn.endsWith(".CustomFilterHeader");   // the custom views section
    }

    /** A HeaderScrollPane draws its divider as a border. Returns true if one was removed. */
    private static boolean stripHeaderScrollDividers(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && ch.getClass().getName().endsWith(".HeaderScrollPane")) {
                JComponent hsp = (JComponent) ch;
                javax.swing.border.Border b = hsp.getBorder();
                if (b != null && !(b instanceof javax.swing.border.EmptyBorder)) {
                    hsp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                    changed = true;
                }
            }
            if (ch instanceof Container) changed |= stripHeaderScrollDividers((Container) ch);
        }
        return changed;
    }

    // Mono file-type icons in the Views tables; the hoster table keeps its coloured favicons.
    private static void monoViewsTables(Container host) {
        try {
            java.util.List<JTable> tabs = new java.util.ArrayList<JTable>();
            collectTables(host, tabs);
            for (JTable t : tabs) {
                if (t.getRowCount() == 0) continue;
                boolean hoster = false;
                try {
                    javax.swing.Icon i0 = firstIcon(t.prepareRenderer(t.getCellRenderer(0, 0), 0, 0));
                    if (i0 != null && i0.getClass().getName().toLowerCase().contains("domain")) hoster = true;
                } catch (Throwable ig) { }
                if (hoster) continue;
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
                // Some Views tables override getCellRenderer and ignore the column renderer.
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
        } catch (Throwable ignore) { }
    }
    private static javax.swing.Icon firstIcon(Component c) {
        if (c instanceof javax.swing.JLabel) return ((javax.swing.JLabel) c).getIcon();
        if (c instanceof AbstractButton) return ((AbstractButton) c).getIcon();
        if (c instanceof Container)
            for (Component ch : ((Container) c).getComponents()) { javax.swing.Icon i = firstIcon(ch); if (i != null) return i; }
        return null;
    }

    /** Hides the grid of the Views tables in their own background, since they sit on the base colour
     *  rather than a card. JD can turn the lines back on, so this runs every tick and reports changes. */
    private static boolean flattenViewsGrids(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JTable) {
                JTable t = (JTable) ch;
                try {
                    if (t.getShowHorizontalLines() || t.getShowVerticalLines()) { t.setShowGrid(false); changed = true; }
                    Color bg = t.getBackground();
                    if (bg != null && !bg.equals(t.getGridColor())) { t.setGridColor(bg); changed = true; }
                    Dimension sp = t.getIntercellSpacing();
                    if (sp == null || sp.width != 0 || sp.height != 0) { t.setIntercellSpacing(new Dimension(0, 0)); changed = true; }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) changed |= flattenViewsGrids((Container) ch);
        }
        return changed;
    }
}
