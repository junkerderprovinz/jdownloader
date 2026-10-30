package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import static io.github.junkerderprovinz.Cards.*;
import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Menus.*;
import static io.github.junkerderprovinz.Settings.*;

/** Tool bars, main tabs and their buttons. */
final class Chrome {

    private static final Color TILE_GREY = PAL_TRACK;   // unselected main tab

    // The ToolBar and MenuBar defaults only reach components created later, and the LAF re-apply at
    // boot can fail, so the live menu bar and toolbar get the base colour directly.
    private static final Color CHROME_BASE = PAL_BASE;
    static void darkenChromeBars() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof javax.swing.JFrame) {
                javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
                if (mb != null) {
                    if (!CHROME_BASE.equals(mb.getBackground())) { mb.setBackground(CHROME_BASE); mb.setOpaque(true); }
                    // FlatMenuBarBorder draws a separator line below the menu bar. The first menu's
                    // text sits 8px inside it, so the left inset puts the text on EDGE.
                    javax.swing.border.Border mbb = mb.getBorder();
                    if (!(mbb instanceof javax.swing.border.EmptyBorder)
                            || ((javax.swing.border.EmptyBorder) mbb).getBorderInsets().left != EDGE - 8)
                        mb.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, EDGE - 8, 0, 0));
                }
            }
            darkenToolbarsIn(w);
        }
    }
    private static void darkenToolbarsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (isMainToolbar(ch.getClass()) && ch instanceof JComponent) {
                if (!CHROME_BASE.equals(ch.getBackground())) { ch.setBackground(CHROME_BASE); ((JComponent) ch).setOpaque(true); }
                Container par = ch.getParent();   // the band around the toolbar can be lighter too
                if (par instanceof JComponent && par.getBackground() != null
                        && par.getBackground().getRed() >= 0x1b && par.getBackground().getRed() <= 0x2a
                        && !CHROME_BASE.equals(par.getBackground())) {
                    par.setBackground(CHROME_BASE); ((JComponent) par).setOpaque(true);
                }
            }
            if (ch instanceof Container) darkenToolbarsIn((Container) ch);
        }
    }

    static void recolorMainTabs() {
        // On Linux FlatLaf leaves TabbedPane.selectedForeground null, because @accentColor resolves to
        // systemColor(accent), so accentFg derives the tone from the accent instead.
        Color selFg = accentFg();
        Color norFg = UIManager.getColor("TabbedPane.foreground");
        if (norFg == null) norFg = PAL_TEXT;
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) recolorTabsIn(w, selFg, norFg);
        }
    }

    private static final String TAB_HOVER_IDX = "jdp.tabHoverIdx";

    /** The hovered tab as BasicTabbedPaneUI tracks it, which still works when a custom tab component
     *  consumes the mouse events. */
    private static int rolloverTabOf(javax.swing.JTabbedPane tp) {
        try {
            java.lang.reflect.Method m = javax.swing.plaf.basic.BasicTabbedPaneUI.class.getDeclaredMethod("getRolloverTab");
            m.setAccessible(true);
            Object r = m.invoke(tp.getUI());
            if (r instanceof Integer) return ((Integer) r).intValue();
        } catch (Throwable ignore) { }
        return -1;
    }

    /** FlatTabbedPaneUI paints one background per tab and the hover colour wins over the selected one,
     *  so the selected tab would lose its accent under the mouse. Clearing hoverColor on the UI instance
     *  leaves the accent text as the hover cue. */
    private static void killTabHoverColor(javax.swing.JTabbedPane tp) {
        try {
            Object ui = tp.getUI();
            for (Class<?> k = ui.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField("hoverColor");
                    f.setAccessible(true);
                    if (f.get(ui) != null) { f.set(ui, null); tp.repaint(); }
                    return;
                } catch (NoSuchFieldException nsf) { }
            }
        } catch (Throwable ignore) { }
    }

    private static void recolorTabsIn(Container c, Color selFg, Color norFg) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JTabbedPane) {
                javax.swing.JTabbedPane tp = (javax.swing.JTabbedPane) child;
                // JD's TabHeader components consume mouse events, so the pane's own listener only sees
                // the gaps between tabs; the UI's rollover tab covers the rest.
                killTabHoverColor(tp);
                int rov = rolloverTabOf(tp);
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                int hover = (rov >= 0) ? rov : ((hv instanceof Integer) ? (Integer) hv : -1);
                applyTabForegrounds(tp, selFg, norFg, hover);
                installTabHoverListener(tp, selFg, norFg);
            }
            if (child instanceof Container) recolorTabsIn((Container) child, selFg, norFg);
        }
    }

    /** Dark text and icon on the selected tab, accent on the hovered one, light on the rest. JD's custom
     *  tab components bypass FlatLaf's selectedForeground, so the foreground is set per tab. */
    private static void applyTabForegrounds(javax.swing.JTabbedPane tp, Color selFg, Color norFg, int hover) {
        int sel = tp.getSelectedIndex();
        for (int i = 0; i < tp.getTabCount(); i++) {
            boolean isSel = (i == sel);
            boolean isHover = (hover >= 0 && i == hover && !isSel);
            Color want = new Color((isSel ? selFg : (isHover ? accentColor() : norFg)).getRGB());
            if (!want.equals(tp.getForegroundAt(i))) tp.setForegroundAt(i, want);
            // Unselected tabs get a grey tile like the sidebar; the selected tab paints
            // selectedBackground and ignores this.
            Color tabBg = isSel ? accentColor() : TILE_GREY;
            if (!tabBg.equals(tp.getBackgroundAt(i))) tp.setBackgroundAt(i, tabBg);
            Color iconTone = isSel ? accentFg() : (isHover ? accentColor() : SIDEBAR_TEXT);
            javax.swing.Icon slot = tp.getIconAt(i);
            if (slot != null) {
                String pk = "jdp.tabIcOrig." + i;
                javax.swing.Icon o = (javax.swing.Icon) tp.getClientProperty(pk);
                if (o == null && iconKey(slot) != null) { o = slot; tp.putClientProperty(pk, o); }
                if (o != null) { javax.swing.Icon nw = tablerIcon(o, iconTone, tp); if (nw != tp.getIconAt(i)) tp.setIconAt(i, nw); }
            }
            Component tc = tp.getTabComponentAt(i);
            if (tc != null) { setLabelFg(tc, want); tablerTabIcons(tc, iconTone, isSel); installTabCompHover(tp, tc, selFg, norFg); }
        }
    }

    /** JD swaps tab icons back between ticks, so the label gets a TabIcon that picks its tone at paint
     *  time from the label's foreground. {@code tone} is unused. */
    private static void tablerTabIcons(Component c, Color tone, boolean sel) {
        if (c instanceof AbstractButton) installTabCloseButton((AbstractButton) c, sel);
        else if (c instanceof javax.swing.JLabel) installTabIconWrap((javax.swing.JLabel) c);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) tablerTabIcons(ch, tone, sel);
    }

    private static void installTabIconWrap(final javax.swing.JLabel l) {
        javax.swing.Icon cur = l.getIcon();
        if (cur instanceof TabIcon) { ensureTabIconListener(l); return; }
        if (cur == null) return;
        javax.swing.Icon base = cur;
        String tk = iconKey(cur);
        if (tk != null) { javax.swing.Icon tb = tablerBase(tk, cur.getIconWidth(), cur.getIconHeight()); if (tb != null) base = tb; }
        javax.swing.Icon light  = tintSolid(base, SIDEBAR_TEXT);
        javax.swing.Icon dark   = tintSolid(base, accentFg());
        javax.swing.Icon accent = tintSolid(base, accentColor());
        if (light == base || light == null) return;
        l.putClientProperty("jdp.tabOrig", cur);
        l.setIcon(new TabIcon(light, dark, accent));
        ensureTabIconListener(l);
    }

    /** The close button of a ClosableTabHeader ignores setIcon, setOpaque and setContentAreaFilled, so
     *  CloseUI takes over its painting. */
    private static void installTabCloseButton(final AbstractButton b, boolean sel) {
        Boolean now = Boolean.valueOf(sel);
        boolean changed = !now.equals(b.getClientProperty("jdp.closeSel"));
        b.putClientProperty("jdp.closeSel", now);
        if (!(b.getUI() instanceof CloseUI)) { b.setUI(new CloseUI()); b.setBorder(null); changed = true; }
        if (changed) b.repaint();
    }

    /** Fills the button with the tab's own pill colour and centres a mono close glyph. The tab header
     *  reports the base colour, not the pill painted under it. The jdp.closeSel property tells it
     *  whether it sits on the selected tab. */
    private static final class CloseUI extends javax.swing.plaf.basic.BasicButtonUI {
        private boolean sel(JComponent c) { return Boolean.TRUE.equals(c.getClientProperty("jdp.closeSel")); }
        @Override public void update(Graphics g, JComponent c) {
            g.setColor(sel(c) ? accentColor() : TILE_GREY);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }
        @Override public void paint(Graphics g, JComponent c) {
            javax.swing.Icon x = tintSolid(tablerBase("close", 16, 16), sel(c) ? accentFg() : SIDEBAR_TEXT);
            if (x == null) return;
            int ix = (c.getWidth() - x.getIconWidth()) / 2, iy = (c.getHeight() - x.getIconHeight()) / 2;
            x.paintIcon(c, g, ix, iy);
        }
    }

    /** The overview panel's settings and close buttons derive a dark glyph on every paint and ignore
     *  setIcon, so like the tab close button they get a UI that paints their own glyph tinted. */
    static void hlInstallOverviewCornerButton(final AbstractButton b) {
        if (!(b.getUI() instanceof HlOverviewCornerUI)) {
            try { b.setUI(new HlOverviewCornerUI()); b.setBorder(null); } catch (Throwable ignore) { }
        }
        // The header behind the glyphs reports the base colour but is not painted, so any fill would be a
        // dark box on the overview card.
        if (b.isOpaque()) b.setOpaque(false);
    }
    private static final class HlOverviewCornerUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.Icon ic = b.getIcon();
            if (ic == null) return;
            boolean hot = b.getModel().isRollover() || b.getModel().isArmed();
            javax.swing.Icon m = cornerGlyphTint(b, ic, hot);
            if (m == null) return;
            int ix = (c.getWidth() - m.getIconWidth()) / 2, iy = (c.getHeight() - m.getIconHeight()) / 2;
            m.paintIcon(c, g, ix, iy);
        }
    }
    /** Caches the tinted glyph per hover state and source icon. */
    private static javax.swing.Icon cornerGlyphTint(AbstractButton b, javax.swing.Icon ic, boolean hot) {
        String pk = hot ? "jdp.ovcHot" : "jdp.ovcLit", bk = hot ? "jdp.ovcHotB" : "jdp.ovcLitB";
        Object cached = b.getClientProperty(pk);
        if (ic == b.getClientProperty(bk) && cached instanceof javax.swing.Icon) return (javax.swing.Icon) cached;
        // As bright as the table header gear next to it; EXPANDER_LIGHT would look grey.
        javax.swing.Icon m = tintSolid(ic, hot ? accentColor() : SIDEBAR_TEXT);
        if (m == null) m = ic;
        b.putClientProperty(bk, ic);
        b.putClientProperty(pk, m);
        return m;
    }

    private static boolean isSilentBell(AbstractButton b) {
        javax.swing.Action a = b.getAction();
        return a != null && "SilentModeToggleAction".equals(a.getClass().getSimpleName());
    }
    /** Rounded accent fill for the toolbar buttons. AppWork's ExtButtons have no FlatButtonBorder, so
     *  FlatLaf would fill them square. */
    private static final class RoundFillUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void update(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.ButtonModel m = b.getModel();
            boolean toggle = b instanceof javax.swing.JToggleButton;
            // On a toggle the fill shows the on state rather than hover, or switching it off under the
            // cursor would look like nothing happened. The silent mode bell is selected when notifications
            // are off, so its logic is inverted.
            boolean active = isSilentBell(b) ? !m.isSelected() : m.isSelected();
            boolean hot = active || m.isPressed() || (!toggle && m.isRollover());
            // ExtJToggleButton paints its own selection background before ui.update, so it is covered first.
            Color bg = (c.getParent() != null && c.getParent().getBackground() != null)
                    ? c.getParent().getBackground() : PAL_BASE;
            g.setColor(bg);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            if (hot) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(accentColor());
                g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), SB_BTN_ARC, SB_BTN_ARC);
                g2.dispose();
            }
            // ExtJToggleButton derives its icon from the Action's ExtMergedIcon, whose state overlay JD
            // resets faster than the tick, so mapped actions get their glyph painted here directly.
            javax.swing.Icon own = ownedToolbarGlyph(b, hot);
            if (own != null) {
                int ix = (c.getWidth() - own.getIconWidth()) / 2, iy = (c.getHeight() - own.getIconHeight()) / 2;
                own.paintIcon(c, g, ix, iy);
                return;
            }
            paint(g, c);
        }
    }

    private static final java.util.Map<String, javax.swing.Icon> OWNED_GLYPH = new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();
    /** The Tabler glyph for a mapped toolbar button, dark on the accent and light otherwise, or null. */
    private static javax.swing.Icon ownedToolbarGlyph(AbstractButton b, boolean hot) {
        javax.swing.Action a = b.getAction();
        if (a == null) return null;
        String sn = a.getClass().getSimpleName();
        String key;
        if ("SilentModeToggleAction".equals(sn)) {
            // Selected means notifications are off, shown as a crossed-out bell.
            key = b.isSelected() ? "silentmode" : "bubble";
        } else {
            key = ACTION_ICON.get(sn);
        }
        if (key == null) return null;
        javax.swing.Icon cur = b.getIcon();
        int sz = (cur != null && cur.getIconHeight() > 0) ? cur.getIconHeight() : 24;
        Color tone = !b.isEnabled() ? DISABLED_TONE : (hot ? accentFg() : SIDEBAR_TEXT);
        String ck = key + "@" + sz + "@" + tone.getRGB();
        javax.swing.Icon cached = OWNED_GLYPH.get(ck);
        if (cached != null) return cached;
        javax.swing.Icon base = tablerBase(key, sz, sz);
        if (base == null) return null;
        javax.swing.Icon tinted = tintSolid(base, tone);
        OWNED_GLYPH.put(ck, tinted);
        return tinted;
    }
    static void roundToolbarButtons() {
        for (Window w : Window.getWindows()) if (w.isShowing()) roundToolbarBtnsIn(w, false);
    }
    private static void roundToolbarBtnsIn(Container c, boolean inTb) {
        boolean tb = inTb || isMainToolbar(c.getClass());
        for (Component ch : c.getComponents()) {
            if (tb && ch instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) ch;
                if (!(b.getUI() instanceof RoundFillUI)) {
                    javax.swing.border.Border ob = b.getBorder();   // keeps JD's padding across setUI
                    b.setContentAreaFilled(false);
                    b.setOpaque(false);
                    b.setUI(new RoundFillUI());
                    if (ob != null) b.setBorder(ob);
                }
            }
            if (ch instanceof Container) roundToolbarBtnsIn((Container) ch, tb);
        }
    }

    private static void ensureTabIconListener(final javax.swing.JLabel l) {
        if (l.getClientProperty("jdp.tabIconL") != null) return;
        l.putClientProperty("jdp.tabIconL", Boolean.TRUE);
        l.addPropertyChangeListener("icon", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                if (!(l.getIcon() instanceof TabIcon)) installTabIconWrap(l);
            }
        });
    }

    /** Picks its glyph from the label's foreground at paint time; JD leaves that foreground alone. */
    private static final class TabIcon implements javax.swing.Icon {
        private final javax.swing.Icon light, dark, accent;
        TabIcon(javax.swing.Icon light, javax.swing.Icon dark, javax.swing.Icon accent) { this.light = light; this.dark = dark; this.accent = accent; }
        private javax.swing.Icon pick(Component c) {
            if (c == null) return light;
            Color fg = c.getForeground();
            if (accentFg().equals(fg)) return dark;        // selected
            if (accentColor().equals(fg)) return accent;   // hovered
            return light;
        }
        public void paintIcon(Component c, Graphics g, int x, int y) { pick(c).paintIcon(c, g, x, y); }
        public int getIconWidth() { return light.getIconWidth(); }
        public int getIconHeight() { return light.getIconHeight(); }
    }

    private static int indexOfTabComp(javax.swing.JTabbedPane tp, Component tc) {
        for (int k = 0; k < tp.getTabCount(); k++) if (tp.getTabComponentAt(k) == tc) return k;
        return -1;
    }

    /** The TabHeader components consume mouse events, so without their own listener the hover colour
     *  would only follow on the next tick. */
    private static void installTabCompHover(final javax.swing.JTabbedPane tp, final Component tc,
                                            final Color selFg, final Color norFg) {
        if (!(tc instanceof JComponent)) return;
        final JComponent jc = (JComponent) tc;
        if (jc.getClientProperty("jdp.tabCompHover") != null) return;
        jc.putClientProperty("jdp.tabCompHover", Boolean.TRUE);
        jc.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                int idx = indexOfTabComp(tp, tc);
                if (idx >= 0) { tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(idx)); applyTabForegrounds(tp, selFg, norFg, idx); }
            }
            @Override public void mouseExited(MouseEvent e) {
                if (jc.contains(e.getPoint())) return;   // moved into a child
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(-1));
                applyTabForegrounds(tp, selFg, norFg, rolloverTabOf(tp));
            }
        });
    }

    private static final String TAB_HOVER_WIRED = "jdp.tabHoverWired";
    private static void installTabHoverListener(final javax.swing.JTabbedPane tp,
                                                final Color selFg, final Color norFg) {
        if (Boolean.TRUE.equals(tp.getClientProperty(TAB_HOVER_WIRED))) return;
        tp.putClientProperty(TAB_HOVER_WIRED, Boolean.TRUE);
        MouseAdapter h = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int idx = tp.indexAtLocation(e.getX(), e.getY());
                Object prev = tp.getClientProperty(TAB_HOVER_IDX);
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(idx));   // read by the tick
                applyTabForegrounds(tp, selFg, norFg, idx);
                if (!Integer.valueOf(idx).equals(prev)) tp.repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(-1));
                applyTabForegrounds(tp, selFg, norFg, -1);
                tp.repaint();
            }
        };
        tp.addMouseMotionListener(h);
        tp.addMouseListener(h);
        // A clicked tab takes its colours right away instead of on the next tick.
        tp.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                applyTabForegrounds(tp, selFg, norFg, (hv instanceof Integer) ? (Integer) hv : -1);
            }
        });
    }

    private static void setLabelFg(Component c, Color fg) {
        if (!fg.equals(c.getForeground())) c.setForeground(fg);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) setLabelFg(ch, fg);
    }
}
