package io.github.junkerderprovinz;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.Window;
import java.lang.reflect.Method;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Dialogs.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Settings.*;
import static io.github.junkerderprovinz.Tables.*;

/** Menu bar, popup and menu item styling. */
final class Menus {

    // Without a frame the fields embedded in menus (speed limit, max chunks) are hard to see, so they
    // get the button shade to read as editable.
    private static final Color MENU_FIELD_BG = PAL_BUTTON;
    private static final int   MENU_FIELD_W  = 110;

    // FlatLaf darkens a submenu's arrow only for a JMenu it sees as selected, which JD's menus do not
    // report while their submenu is open, so the arrow would stay light on the accent. The arrow of every
    // JMenu is wrapped in one that turns dark whenever the menu is hot.
    private static final class HotArrowIcon implements javax.swing.Icon {
        final javax.swing.Icon base;
        HotArrowIcon(javax.swing.Icon base) { this.base = base; }
        public int getIconWidth() { return base.getIconWidth(); }
        public int getIconHeight() { return base.getIconHeight(); }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean hot = c instanceof javax.swing.JMenu
                    && (((javax.swing.JMenu) c).isSelected() || ((javax.swing.JMenu) c).getModel().isArmed());
            (hot ? tintIcon(base, accentFg(), c) : base).paintIcon(c, g, x, y);
        }
    }
    static void fixMenuArrow() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof javax.swing.JFrame) {
                javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
                if (mb != null) for (javax.swing.MenuElement m : mb.getSubElements()) fixArrowsIn(m);
            }
            for (Component c : w instanceof Container ? ((Container) w).getComponents() : new Component[0])
                if (c instanceof javax.swing.JRootPane) fixPopupArrows((Container) c);
        }
    }
    private static void fixPopupArrows(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JPopupMenu) fixArrowsIn((JPopupMenu) ch);
            else if (ch instanceof Container) fixPopupArrows((Container) ch);
        }
    }
    private static void fixArrowsIn(javax.swing.MenuElement e) {
        if (e instanceof javax.swing.JMenu) {
            Object ui = ((javax.swing.JMenu) e).getUI();
            Object icon = fieldValue(ui, javax.swing.plaf.basic.BasicMenuItemUI.class, "arrowIcon");
            if (icon instanceof javax.swing.Icon && !(icon instanceof HotArrowIcon)) {
                HotArrowIcon hot = new HotArrowIcon((javax.swing.Icon) icon);
                setField(ui, "arrowIcon", hot);
                // FlatLaf paints through a renderer that keeps its own reference to the arrow.
                Object renderer = fieldValue(ui, ui.getClass(), "renderer");
                if (renderer == null) renderer = fieldValue(ui, ui.getClass().getSuperclass(), "renderer");
                if (renderer != null) setField(renderer, "arrowIcon", hot);
                ((javax.swing.JMenu) e).repaint();
            }
        }
        for (javax.swing.MenuElement sub : e.getSubElements()) fixArrowsIn(sub);
    }

    static void styleMenuFields() {
        for (Window w : Window.getWindows()) if (w.isShowing()) styleMenuFieldsIn(w, false);
    }
    private static void styleMenuFieldsIn(Container c, boolean inMenu) {
        boolean menu = inMenu || c instanceof javax.swing.JPopupMenu;
        for (Component ch : c.getComponents()) {
            if (menu && (ch instanceof javax.swing.JSpinner
                    || ch instanceof javax.swing.text.JTextComponent
                    || ch instanceof javax.swing.JComboBox)) {
                if (!MENU_FIELD_BG.equals(ch.getBackground())) {
                    ch.setBackground(MENU_FIELD_BG);
                    // An opaque field fills its square bounds, and FlatLaf clears the corners only in the
                    // enabled paint path, so a disabled field would stay square.
                    if (ch instanceof JComponent) ((JComponent) ch).setOpaque(false);
                }
                // One width for all menu fields so the dropdown column lines up.
                if (ch instanceof JComponent) {
                    java.awt.Dimension pr = ch.getPreferredSize();
                    if (pr != null && pr.width != MENU_FIELD_W) {
                        java.awt.Dimension d = new java.awt.Dimension(MENU_FIELD_W, pr.height);
                        ch.setPreferredSize(d); ch.setMinimumSize(d); ch.setMaximumSize(d);
                        Container par = ch.getParent();
                        if (par != null) { par.invalidate(); par.revalidate(); }
                    }
                    // JD's menu spinners have a custom UI that ignores FlatLaf's arc keys, so their corners
                    // are masked with the popup background instead.
                    JComponent jc = (JComponent) ch;
                    if (jc.getClientProperty("jdp.fieldRound") == null) {
                        jc.putClientProperty("jdp.fieldRound", Boolean.TRUE);
                        // The FlatLaf border would draw an outline; only its insets are kept as padding.
                        javax.swing.border.Border ob = jc.getBorder();
                        java.awt.Insets bi = (ob != null) ? ob.getBorderInsets(jc) : new java.awt.Insets(3, 8, 3, 8);
                        javax.swing.border.Border inner = new javax.swing.border.EmptyBorder(bi);
                        javax.swing.border.Border mask = new RoundCornerMaskBorder(menuFieldMaskColor(jc), UI_RADIUS);
                        jc.setBorder(javax.swing.BorderFactory.createCompoundBorder(mask, inner));
                    }
                }
            }
            if (ch instanceof Container) styleMenuFieldsIn((Container) ch, menu);
        }
    }

    /** Background of the enclosing popup, used to mask a menu field's corners. */
    private static Color menuFieldMaskColor(Component field) {
        for (Component p = field.getParent(); p != null; p = p.getParent()) {
            if (p instanceof javax.swing.JPopupMenu && p.getBackground() != null) return p.getBackground();
        }
        return PAL_HEADER;
    }

    // Menus would open unstyled until the next tick, so each popup is styled from a listener as it
    // becomes visible. JMenus hand out their popup before it shows, which covers the first open too.
    private static final String POPUP_WIRED = "jdp.popupWired";

    static void wireMenuPopups() {
        for (Window w : Window.getWindows()) if (w.isShowing()) wireMenuPopupsIn(w);
    }

    private static void wireMenuPopupsIn(Container c) {
        if (c instanceof javax.swing.JMenu) wirePopup(((javax.swing.JMenu) c).getPopupMenu());
        else if (c instanceof javax.swing.JPopupMenu) wirePopup((javax.swing.JPopupMenu) c);
        if (c instanceof javax.swing.JFrame) {
            javax.swing.JMenuBar mb = ((javax.swing.JFrame) c).getJMenuBar();
            if (mb != null) wireMenuPopupsIn(mb);
        }
        for (Component ch : c.getComponents())
            if (ch instanceof Container) wireMenuPopupsIn((Container) ch);
    }

    private static void wirePopup(final javax.swing.JPopupMenu pm) {
        if (pm == null || Boolean.TRUE.equals(pm.getClientProperty(POPUP_WIRED))) return;
        pm.putClientProperty(POPUP_WIRED, Boolean.TRUE);
        pm.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { stylePopupNow(pm); }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
    }

    /** Styles one popup menu. Runs on open and again on every tick while the popup is visible, because
     *  JD builds the EditorLink rows lazily and an on-open pass alone misses them. */
    private static void stylePopupNow(javax.swing.JPopupMenu pm) {
        try {
            styleMenuFieldsIn(pm, true);
            uniformMenuSpinnerWidths(pm);
            for (Component ch : pm.getComponents())
                if (ch instanceof javax.swing.JMenuItem) monoMenuItemIcon((javax.swing.JMenuItem) ch);
            for (Component ch : pm.getComponents())
                if (ch instanceof javax.swing.JMenuItem) forceMenuItemAccent((javax.swing.JMenuItem) ch);
            for (Component ch : pm.getComponents())
                if (!(ch instanceof javax.swing.JMenuItem) && ch instanceof Container) monoRowLabels((Container) ch);
            for (Component ch : pm.getComponents()) {
                if (ch instanceof JComponent && !(ch instanceof javax.swing.JMenuItem)
                        && !(ch instanceof javax.swing.JSeparator)) {
                    JComponent jc = (JComponent) ch;
                    if (jc.getClientProperty("jdp.menuRowPad") == null) {
                        jc.putClientProperty("jdp.menuRowPad", Boolean.TRUE);
                        // Editor rows draw their icon at x 7 and menu items at about 18 (MenuItem.margin),
                        // so 11px lines the two icon columns up.
                        javax.swing.border.Border pad = new javax.swing.border.EmptyBorder(3, 11, 3, 0);
                        javax.swing.border.Border old = jc.getBorder();
                        jc.setBorder(old == null ? pad : new javax.swing.border.CompoundBorder(pad, old));
                    }
                }
            }
            uniformMenuRowHeights(pm);
        } catch (Throwable ignore) { }
    }

    /** Grows the plain menu items to the height of the taller EditorLink and spinner rows. */
    private static void uniformMenuRowHeights(javax.swing.JPopupMenu pm) {
        int max = 0;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator || !ch.isVisible()) continue;
            int h = ch.getPreferredSize().height;
            if (h > max) max = h;
        }
        if (max <= 0) return;
        boolean changed = false;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator || !(ch instanceof JComponent) || !ch.isVisible()) continue;
            JComponent jc = (JComponent) ch;
            // Some of JD's standard items report the full preferred height but cap it with their own
            // maximum size, so min and max are pinned on every row to make the BoxLayout honour it.
            java.awt.Dimension mn = jc.getMinimumSize();
            if (mn != null && mn.height == max && jc.getMaximumSize() != null && jc.getMaximumSize().height == max) continue;
            java.awt.Dimension pr = ch.getPreferredSize();
            jc.setPreferredSize(new java.awt.Dimension(pr.width, max));
            jc.setMinimumSize(new java.awt.Dimension(0, max));
            jc.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, max));
            changed = true;
        }
        if (changed) { pm.revalidate(); pm.repaint(); }
        repackPopupIfClipped(pm);
    }

    /** Grows a showing popup to fit its rows. JD adds items to the settings dropdown after it is shown,
     *  so this runs on every tick; a heavyweight popup is resized through its window, a lightweight one
     *  in the layered pane. It never shrinks. */
    private static void repackPopupIfClipped(javax.swing.JPopupMenu pm) {
        try {
            if (!pm.isShowing() || pm.getHeight() <= 0) return;
            // getPreferredSize stays stale after JD adds items lazily, so the children are summed instead.
            int need = 0, wide = 0;
            for (Component ch : pm.getComponents()) {
                if (!ch.isVisible()) continue;
                java.awt.Dimension d = ch.getPreferredSize();
                if (d == null) continue;
                need += d.height; if (d.width > wide) wide = d.width;
            }
            java.awt.Insets in = pm.getInsets();
            if (in != null) { need += in.top + in.bottom; wide += in.left + in.right; }
            if (need <= 0 || (pm.getHeight() >= need && pm.getWidth() >= wide)) return;
            int nw = Math.max(pm.getWidth(), wide), nh = Math.max(pm.getHeight(), need);
            java.awt.Window win = javax.swing.SwingUtilities.getWindowAncestor(pm);
            boolean heavy = win != null && win.getClass().getName().toLowerCase().contains("popup");
            if (heavy) {
                if (win.getHeight() < nh || win.getWidth() < nw) { win.setSize(nw, nh); win.validate(); }
            } else {
                pm.setSize(nw, nh);
                java.awt.Container par = pm.getParent();
                if (par != null) { par.setSize(Math.max(par.getWidth(), nw), Math.max(par.getHeight(), nh)); par.revalidate(); }
                pm.revalidate(); pm.repaint();
            }
        } catch (Throwable ignore) { }
    }

    static void styleVisibleMenuPopups() {
        // A heavyweight popup lives inside a popup window and a lightweight one in the layered pane,
        // so walking every showing window finds both.
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) styleVisiblePopupsIn(w);
        }
    }
    private static void styleVisiblePopupsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JPopupMenu && ch.isShowing()) stylePopupNow((javax.swing.JPopupMenu) ch);
            if (ch instanceof Container) styleVisiblePopupsIn((Container) ch);
        }
    }

    // Width shared by every menu spinner: a speed value with its unit plus 30px for the arrows.
    private static int UNIFORM_FIELD_W = -1;
    static int uniformFieldW() {
        if (UNIFORM_FIELD_W < 0) UNIFORM_FIELD_W = new JLabel("999,99 MiB/s").getPreferredSize().width + 30;
        return UNIFORM_FIELD_W;
    }

    /** Gives every spinner in a menu popup the same width. JD's MenuEditor hardcodes the MigLayout
     *  cell widths, which override setPreferredSize, so the constraint itself is rewritten. */
    private static void uniformMenuSpinnerWidths(Container c) {
        boolean touched = uniformSpinnersIn(c);
        if (touched) {
            JPopupMenu pm = (c instanceof JPopupMenu) ? (JPopupMenu) c
                          : (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, c);
            if (pm != null) { pm.revalidate(); Dimension pref = pm.getPreferredSize(); pm.setPopupSize(pref.width, pref.height); }
        }
    }
    private static boolean uniformSpinnersIn(Container c) {
        boolean any = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSpinner) {
                Container par = ch.getParent();
                LayoutManager lm = (par == null) ? null : par.getLayout();
                boolean mig = lm != null && lm.getClass().getName().contains("MigLayout");
                JComponent sp = (JComponent) ch;
                if (mig && !Boolean.TRUE.equals(sp.getClientProperty("jdp.fieldW"))) {
                    try {
                        Method m = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
                        m.invoke(lm, ch, "width " + uniformFieldW() + "!");
                        sp.putClientProperty("jdp.fieldW", Boolean.TRUE);
                        par.revalidate();
                        any = true;
                    } catch (Exception ignore) { }
                }
            }
            if (ch instanceof Container) any |= uniformSpinnersIn((Container) ch);
        }
        return any;
    }

    /** Menu items paint getIcon() whatever their state, so the item gets a light and a dark icon and
     *  installMenuItemHoverIcon swaps them when it is armed. */
    static void monoMenuItemIcon(javax.swing.JMenuItem mi) {
        try {
            javax.swing.Icon cur = mi.getIcon();
            if (cur == null) return;                                   // check and radio glyphs are painted by the UI
            Object light = mi.getClientProperty("jdp.miLight");
            if (cur == light || cur == mi.getClientProperty("jdp.miDark")) { installMenuItemHoverIcon(mi); return; }
            javax.swing.Icon lo = tablerIcon(cur, SIDEBAR_TEXT, mi);
            // Extension toggles such as the event scripter have no Tabler key and a baked-in black box
            // behind the glyph, which is cleared before tinting.
            if (lo == cur) lo = tintSolid(deBoxIcon(cur, mi), SIDEBAR_TEXT, mi);
            // A fresh tablerIcon in the dark tone comes out fully transparent for some menu glyphs, so the
            // dark twin is tinted from the light one.
            javax.swing.Icon hi = (lo != cur) ? tintSolid(lo, accentFg()) : tablerIcon(cur, accentFg(), mi);
            if (lo == cur) return;
            // FlatMenuItemRenderer paints getIcon() even for checked items and popups rebuild their items on
            // every open, so the check badge is decided at paint time. A JMenu counts as selected while its
            // popup is open, so it gets none.
            if (!(mi instanceof javax.swing.JMenu)) { lo = hlStateCheckIcon(lo, mi); hi = hlStateCheckIcon(hi, mi); }
            mi.putClientProperty("jdp.miLight", lo);
            mi.putClientProperty("jdp.miDark", hi);
            mi.setIcon(lo);
            // Check box menu items paint the selected icon when checked and the pressed icon when armed.
            mi.setSelectedIcon(lo);
            mi.setPressedIcon(hi);
            mi.setIconTextGap(10);
            mi.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
            installMenuItemHoverIcon(mi);
        } catch (Throwable ignore) { }
    }

    /** Draws a check badge in the bottom-right corner while the item is selected, with colours inverted on
     *  the armed row. */
    private static javax.swing.Icon hlStateCheckIcon(final javax.swing.Icon base, final javax.swing.JMenuItem mi) {
        return new javax.swing.Icon() {
            public int getIconWidth()  { return base.getIconWidth(); }
            public int getIconHeight() { return base.getIconHeight(); }
            public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                if (!mi.isSelected()) return;
                int w = base.getIconWidth(), h = base.getIconHeight();
                int d = Math.max(9, Math.round(w * 0.66f));
                int bx = x + w - d, by = y + h - d;
                boolean armed = mi.getModel().isArmed();
                java.awt.Color disc = armed ? accentFg() : accentColor();
                java.awt.Color chk  = armed ? accentColor() : accentFg();
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(disc);
                g2.fillOval(bx, by, d, d);
                g2.setColor(chk);
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.3f, d / 6.5f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                int[] xs = { bx + Math.round(d * 0.26f), bx + Math.round(d * 0.44f), bx + Math.round(d * 0.74f) };
                int[] ys = { by + Math.round(d * 0.52f), by + Math.round(d * 0.68f), by + Math.round(d * 0.34f) };
                g2.drawPolyline(xs, ys, 3);
                g2.dispose();
            }
        };
    }

    // Some popups, such as the status bar customize menu, arm their rows in grey, where the dark icon
    // disappears. BasicMenuItemUI keeps the selection colours in fields, so they are set there.
    private static void forceMenuItemAccent(javax.swing.JMenuItem mi) {
        try {
            Object ui = mi.getUI();
            if (ui != null) {
                setUiColorField(ui, "selectionBackground", accentColor());
                setUiColorField(ui, "selectionForeground", accentFg());
            }
        } catch (Throwable ignore) { }
    }

    private static void installMenuItemHoverIcon(final javax.swing.JMenuItem mi) {
        if (mi.getClientProperty("jdp.miHoverIcon") != null) return;
        mi.putClientProperty("jdp.miHoverIcon", Boolean.TRUE);
        mi.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                javax.swing.ButtonModel m = mi.getModel();
                // A JMenu is selected but not armed while its submenu is open, and its row keeps the
                // accent then.
                boolean open = mi instanceof javax.swing.JMenu && m.isSelected();
                boolean hot = mi.isEnabled() && (open || m.isArmed());
                Object want = mi.getClientProperty(hot ? "jdp.miDark" : "jdp.miLight");
                if (want instanceof javax.swing.Icon) {
                    javax.swing.Icon wi = (javax.swing.Icon) want;
                    if (mi.getIcon() != wi) mi.setIcon(wi);
                    if (mi.getSelectedIcon() != wi) mi.setSelectedIcon(wi);
                }
                // forceMenuItemAccent only runs on the next tick, so the first hover in a fresh menu would
                // still show light text on the accent.
                Color miFg = (mi.isEnabled() && (m.isArmed() || open)) ? accentFg() : SIDEBAR_TEXT;
                if (!miFg.equals(mi.getForeground())) mi.setForeground(miFg);
            }
        });
    }

    // Only the top-level menus get the smaller left margin. Menu.margin also applies to submenus,
    // whose icons have to line up with the items next to them.
    static void flushMenuBarItems() {
        for (Window w : Window.getWindows()) {
            if (!(w instanceof javax.swing.JFrame) || !w.isShowing()) continue;
            javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
            if (mb == null) continue;
            for (int i = 0; i < mb.getMenuCount(); i++) {
                javax.swing.JMenu m = mb.getMenu(i);
                if (m == null) continue;
                java.awt.Insets mar = m.getMargin();
                if (mar == null || mar.left != 8) {
                    int t = mar == null ? 6 : mar.top, b = mar == null ? 6 : mar.bottom;
                    m.setMargin(new java.awt.Insets(t, 8, b, 12));
                }
                installMenuHoverFg(m);
            }
        }
    }
    // FlatLaf fills a hovered top-level menu with MenuBar.hoverBackground but has no matching
    // foreground key, so the label would stay light on the accent.
    private static void installMenuHoverFg(final javax.swing.JMenu m) {
        if (m.getClientProperty("jdp.menuHoverFg") != null) return;
        m.putClientProperty("jdp.menuHoverFg", Boolean.TRUE);
        // JMenu reports no rollover, so hover comes from a mouse listener and keyboard arming from the model.
        m.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { if (m.isEnabled()) m.setForeground(accentFg()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (!m.isArmed() && !m.isSelected()) m.setForeground(SIDEBAR_TEXT);
            }
        });
        m.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                javax.swing.ButtonModel mm = m.getModel();
                Color want = (mm.isArmed() || mm.isSelected()) ? accentFg() : SIDEBAR_TEXT;
                if (!want.equals(m.getForeground())) m.setForeground(want);
            }
        });
        if (!SIDEBAR_TEXT.equals(m.getForeground())) m.setForeground(SIDEBAR_TEXT);
    }
}
