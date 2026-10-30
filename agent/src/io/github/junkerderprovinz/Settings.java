package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Window;
import java.lang.reflect.Field;

import static io.github.junkerderprovinz.Cards.*;
import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Dialogs.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Tables.*;

/** The settings sidebar, properties panel and config field styling. */
final class Settings {

    // The properties panel below the download and linkgrabber lists draws hairlines, separators and framed
    // fields on a slightly different background. It is flattened onto the base colour, with its fields
    // styled like the settings pages.
    static void stylePropertiesPanel() {
        for (Window w : Window.getWindows()) if (w.isShowing()) stylePropsIn(w);
    }
    private static boolean isPropertiesRoot(Class<?> k) {
        String n = k.getName();
        return n.contains(".properties.")
                && (n.endsWith("ScrollPane") || n.endsWith("BasePanel"));
    }
    private static void stylePropsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container && isPropertiesRoot(ch.getClass())) {
                styleOneProperties((Container) ch);
            } else if (ch instanceof Container) {
                stylePropsIn((Container) ch);
            }
        }
    }
    private static void styleOneProperties(Container root) {
        try {
            clearLinesIn(root, 0);
            hideSeparators(root);
            unifyFieldsIn(root, true);
            pinPropsBg(root, 0);
        } catch (Throwable ignore) { }
    }
    // Fields and cards already carry their own colour and are left alone.
    private static void pinPropsBg(Component c, int depth) {
        if (depth > 14) return;
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            boolean field = (c instanceof javax.swing.text.JTextComponent) || (c instanceof javax.swing.JComboBox)
                    || (c instanceof javax.swing.JSpinner) || (c instanceof AbstractButton);
            if (!field) {
                Color bg = jc.getBackground();
                if (bg != null && !BASE_BG.equals(bg) && !FIELD_BG.equals(bg) && !DIALOG_BG.equals(bg)) {
                    jc.setBackground(BASE_BG);
                    if (!jc.isOpaque() && (c instanceof javax.swing.JPanel)) jc.setOpaque(true);
                }
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) pinPropsBg(ch, depth + 1);
    }

    // monoChromeIn reaches a section header only when an ancestor is recognised as a config panel, which the
    // extensions and packagizer panels are not, so headers are found by class name as well.
    static void monoSectionHeaders() {
        for (Window w : Window.getWindows()) if (w.isShowing()) monoSectionHeadersIn(w);
    }
    private static void monoSectionHeadersIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch.getClass().getName().endsWith(".Header")) monoHeaderIcons(ch);
            else if (ch instanceof Container) monoSectionHeadersIn((Container) ch);
        }
    }
    private static void monoHeaderIcons(Component c) {
        if (c instanceof javax.swing.JLabel) monoLabelIcon((javax.swing.JLabel) c, true);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoHeaderIcons(ch);
    }

    private static javax.swing.ListCellRenderer<?> asRenderer(Object o) {
        return (o instanceof javax.swing.ListCellRenderer) ? (javax.swing.ListCellRenderer<?>) o : null;
    }

    private static final int SIDEBAR_ROW_PX = 66;   // must exceed JD's own row height of about 53
    private static final double SIDEBAR_ICON_SCALE = 1.4;
    static final Color SIDEBAR_TEXT = PAL_TEXT;

    // JD's AppWork inputs ignore @componentBackground and come in several fills, so the settings get one
    // recessed fill for fields and one raised fill for buttons, darker and lighter than the card.
    static final Color FIELD_BG   = PAL_FIELD;
    static final Color BTN_CFG_BG = PAL_BUTTON;
    static final Color BASE_BG    = PAL_BASE;
    static void unifyConfigFields() {
        for (Window w : Window.getWindows()) if (w.isShowing()) unifyFieldsIn(w, false);
    }

    // Some JD combo box popups hard-code a blue selection. Their JList is reachable through the UI's
    // accessible child.
    static void styleComboPopup(javax.swing.JComboBox<?> cb) {
        try {
            javax.accessibility.Accessible a = cb.getUI().getAccessibleChild(cb, 0);
            if (a instanceof javax.swing.plaf.basic.ComboPopup) {
                javax.swing.JList<?> list = ((javax.swing.plaf.basic.ComboPopup) a).getList();
                Color acc = accentColor();
                if (list != null && acc != null && !acc.equals(list.getSelectionBackground())) {
                    list.setSelectionBackground(acc);
                    list.setSelectionForeground(accentFg());
                }
            }
        } catch (Throwable ignore) { }
    }

    private static void unifyFieldsIn(Container c, boolean inCfg) {
        boolean cfg = inCfg || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component ch : c.getComponents()) {
            if (cfg) {
                try {
                    if (ch instanceof javax.swing.text.JTextComponent
                            && !((javax.swing.text.JTextComponent) ch).isEditable()) {
                        // A read-only text component is a description, not an input, so it sits on
                        // the card without a field fill.
                        if (((JComponent) ch).isOpaque()) ((JComponent) ch).setOpaque(false);
                        if (!DIALOG_BG.equals(ch.getBackground())) ch.setBackground(DIALOG_BG);
                        stripFramingBorder((JComponent) ch);
                    } else if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                            || ch instanceof javax.swing.JSpinner) {
                        if (!FIELD_BG.equals(ch.getBackground())) {
                            ch.setBackground(FIELD_BG);
                            if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                        }
                        // borderWidth=0 only reaches FlatBorder, and the settings pages are not dialogs,
                        // so recolorDialogs never strips their AppWork frames.
                        stripFramingBorder((JComponent) ch);
                        if (ch instanceof javax.swing.JComboBox) styleComboPopup((javax.swing.JComboBox<?>) ch);
                    } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                        javax.swing.AbstractButton ab = (javax.swing.AbstractButton) ch;
                        // A hovered button carries the accent from installBtnHoverBg.
                        boolean hot = ab.getModel().isRollover() || ab.getModel().isPressed()
                                || Boolean.TRUE.equals(ab.getClientProperty("jdp.hovered"));
                        if (!hot && !BTN_CFG_BG.equals(ab.getBackground())) ab.setBackground(BTN_CFG_BG);
                        installBtnHoverFg(ab);
                        installBtnHoverBg(ab);
                        stripFramingBorder((JComponent) ch);
                    } else if (isCheckLike(ch) && ch instanceof JComponent) {
                        // The check glyph keeps its own CheckBox.icon border.
                        stripFramingBorder((JComponent) ch);
                    }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) unifyFieldsIn((Container) ch, cfg);
        }
    }
    static boolean isCheckLike(Component ch) {
        if (ch instanceof javax.swing.JCheckBox || ch instanceof javax.swing.JRadioButton) return true;
        String n = ch.getClass().getSimpleName().toLowerCase();
        return n.contains("check") || n.contains("radio") || n.contains("toggle");
    }

    /** Dark text on a button while its accent hover fill shows. */
    static void installBtnHoverFg(final javax.swing.AbstractButton b) {
        if (b.getClientProperty("jdp.hoverFg") != null) return;
        b.putClientProperty("jdp.hoverFg", Boolean.TRUE);
        b.setRolloverEnabled(true);   // off by default on JButton, so JD's SettingsButton reports no rollover
        b.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            private boolean applied = false;
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean roll = b.isEnabled() && (b.getModel().isRollover() || b.getModel().isPressed());
                if (roll && !applied) {
                    b.putClientProperty("jdp.savedFg", b.getForeground());
                    b.setForeground(accentFg());
                    applied = true;
                } else if (!roll && applied) {
                    Object s = b.getClientProperty("jdp.savedFg");
                    if (s instanceof Color) b.setForeground((Color) s);
                    applied = false;
                }
            }
        });
    }

    /** Accent background on a settings button while it is hovered or pressed. */
    static void installBtnHoverBg(final javax.swing.AbstractButton b) {
        if (b.getClientProperty("jdp.hoverBg") != null) return;
        b.putClientProperty("jdp.hoverBg", Boolean.TRUE);
        b.setRolloverEnabled(true);
        // JD's SettingsButton is an ExtButton whose onRollOver only turns on contentAreaFilled, so FlatLaf
        // paints its grey default hover. A per-component style sets the accent instead.
        try {
            Color acc0 = accentColor();
            if (acc0 != null) {
                String hx = String.format("#%06x", acc0.getRGB() & 0xffffff);
                b.putClientProperty("FlatLaf.style", "hoverBackground: " + hx + "; pressedBackground: " + hx);
            }
        } catch (Throwable ignore) { }
        // For custom UIs that ignore the style. contentAreaFilled is restored on exit so a transparent
        // ExtButton does not keep a fill.
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) return;
                Color acc = accentColor();
                if (acc == null) return;
                b.putClientProperty("jdp.hovered", Boolean.TRUE);
                b.putClientProperty("jdp.savedCAF", Boolean.valueOf(b.isContentAreaFilled()));
                Object fg = b.getClientProperty("jdp.savedFg");
                if (!(fg instanceof Color)) b.putClientProperty("jdp.savedFg", b.getForeground());
                b.setContentAreaFilled(true);
                if (b instanceof JComponent) ((JComponent) b).setOpaque(true);
                b.setBackground(acc);
                b.setForeground(accentFg());
                b.repaint();
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                b.putClientProperty("jdp.hovered", null);
                Object caf = b.getClientProperty("jdp.savedCAF");
                if (caf instanceof Boolean) b.setContentAreaFilled((Boolean) caf);
                b.setBackground(BTN_CFG_BG);
                Object s = b.getClientProperty("jdp.savedFg");
                if (s instanceof Color) b.setForeground((Color) s);
                b.putClientProperty("jdp.savedFg", null);
                b.repaint();
            }
        });
        b.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            private boolean applied = false;
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean roll = b.isEnabled() && (b.getModel().isRollover() || b.getModel().isPressed());
                if (roll && !applied) {
                    Color acc = accentColor();
                    if (acc != null) {
                        b.setBackground(acc);
                        if (b instanceof JComponent) ((JComponent) b).setOpaque(true);
                        b.setContentAreaFilled(true);
                    }
                    applied = true;
                } else if (!roll && applied) {
                    // The mouse listener may still hold the accent.
                    if (!Boolean.TRUE.equals(b.getClientProperty("jdp.hovered"))) b.setBackground(BTN_CFG_BG);
                    applied = false;
                }
            }
        });
    }

    /**
     * Raises the settings sidebar rows. JD sizes all of them from one static Dimension on TreeRenderer
     * and has no UIManager key for it, so that object is changed in place. It is reached through a
     * renderer instance from the tree, which resolves it in JD's own class loader.
     */
    static void styleSidebar() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && styleSidebarIn(w)) return;
        }
    }

    private static boolean styleSidebarIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JList) {
                javax.swing.JList<?> list = (javax.swing.JList<?>) child;
                // JD's own renderer, stashed while the hover wrapper is installed.
                javax.swing.ListCellRenderer<?> cur  = list.getCellRenderer();
                javax.swing.ListCellRenderer<?> wrap = asRenderer(list.getClientProperty(SB_WRAP));
                javax.swing.ListCellRenderer<?> r =
                        (cur == wrap && wrap != null) ? asRenderer(list.getClientProperty(SB_ORIG_RENDERER)) : cur;
                if (r != null && r.getClass().getName().endsWith("sidebar.TreeRenderer")) {
                    // Centering happens in the hover wrapper, because TreeRenderer reconfigures its
                    // shared component on every call and would undo alignment set here.
                    // The tiles share the full height of the sidebar and only fall back to their minimum
                    // height, with a scrollbar, when the window is too low for that.
                    javax.swing.JScrollPane ssp = (javax.swing.JScrollPane)
                            javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, list);
                    int rows = list.getModel().getSize();
                    int viewH = ssp != null ? ssp.getViewport().getHeight() : 0;
                    int rowH = rows > 0 && viewH > 0 ? Math.max(SIDEBAR_ROW_PX, viewH / rows) : SIDEBAR_ROW_PX;
                    try {
                        Field f = r.getClass().getField("DIMENSION");
                        Object dim = f.get(null);
                        if (dim instanceof Dimension && ((Dimension) dim).height != rowH) ((Dimension) dim).height = rowH;
                        // The JList caches row heights, and the advanced settings row with its 20px icon
                        // would stay short, so the list gets a fixed cell height.
                        if (list.getFixedCellHeight() != rowH) {
                            list.setFixedCellHeight(rowH);
                            list.revalidate();
                            list.repaint();
                        }
                    } catch (Throwable t) {
                        System.out.println("[jd-dialog-agent] sidebar height bump failed: " + t);
                    }
                    installSidebarAccentHover(list, r);
                    clearSidebarBorders(list);
                    int policy = rows * SIDEBAR_ROW_PX > viewH
                            ? javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                            : javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER;
                    if (ssp != null && ssp.getVerticalScrollBarPolicy() != policy) ssp.setVerticalScrollBarPolicy(policy);
                    // JD rebuilds the panel on selection; styling it right after avoids an unstyled flash
                    // until the next tick.
                    if (list.getClientProperty("jdp.navRetheme") == null) {
                        list.putClientProperty("jdp.navRetheme", Boolean.TRUE);
                        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
                            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                                if (e.getValueIsAdjusting()) return;
                                javax.swing.SwingUtilities.invokeLater(new Runnable() { public void run() {
                                    try { if (isHighlighter()) {
                                        stripSectionUnderlines(); cardSettingsSections();
                                        borderlessConfigTables(); unifyConfigFields(); monoChromeIcons();
                                    } } catch (Throwable ignore) { }
                                } });
                            }
                        });
                    }
                    return true;
                }
            }
            if (child instanceof Container && styleSidebarIn((Container) child)) return true;
        }
        return false;
    }

    /** Clears the divider lines of the settings view, starting from its root. */
    private static void clearSidebarBorders(Component list) {
        Component root = list;
        for (int d = 0; d < 6 && root.getParent() != null; d++) root = root.getParent();
        clearLinesIn(root, 0);
    }

    private static void clearLinesIn(Component c, int depth) {
        if (depth > 12) return;
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            javax.swing.border.Border b = jc.getBorder();
            if (b instanceof javax.swing.border.MatteBorder) {
                clearMatte(jc);
                installBorderGuard(jc);
            } else if (b instanceof javax.swing.border.LineBorder || b instanceof javax.swing.border.EtchedBorder) {
                jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                installBorderGuard(jc);
            }
            if (c instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sp = (javax.swing.JScrollPane) c;
                sp.setViewportBorder(null);
                // The scrollbar gutter would show as a lighter strip next to the sidebar.
                Color base = PAL_BASE;
                if (!base.equals(sp.getBackground())) sp.setBackground(base);
                if (sp.getViewport() != null && !base.equals(sp.getViewport().getBackground()))
                    sp.getViewport().setBackground(base);
                javax.swing.JScrollBar vsb = sp.getVerticalScrollBar();
                if (vsb != null) {
                    if (!base.equals(vsb.getBackground())) vsb.setBackground(base);
                    if (!vsb.isOpaque()) vsb.setOpaque(true);
                }
            }
            // The gap between sidebar and content is the split divider or a panel in @componentBackground.
            // Fields and cards fall outside this shade band and keep their colour.
            Color base2 = PAL_BASE;
            if (jc instanceof javax.swing.JSplitPane) {
                jc.setBackground(base2);
                try {
                    java.awt.Component dvd = ((javax.swing.plaf.basic.BasicSplitPaneUI)
                            ((javax.swing.JSplitPane) jc).getUI()).getDivider();
                    if (dvd != null) dvd.setBackground(base2);
                } catch (Throwable ignore) { }
            } else if (jc.getBackground() != null) {
                Color pb = jc.getBackground();
                if (pb.getRed() >= 0x1b && pb.getRed() <= 0x21
                        && pb.getGreen() >= 0x1b && pb.getGreen() <= 0x21
                        && pb.getBlue() >= 0x1b && pb.getBlue() <= 0x21)
                    jc.setBackground(base2);
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) clearLinesIn(ch, depth + 1);
    }

    private static void clearMatte(JComponent jc) {
        javax.swing.border.Border b = jc.getBorder();
        if (b instanceof javax.swing.border.MatteBorder) {
            java.awt.Insets in = ((javax.swing.border.MatteBorder) b).getBorderInsets(jc);
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder(in.top, in.left, in.bottom, in.right));
        }
    }

    /** JD puts divider borders back after they are cleared, such as the sidebar's in ConfigSidebar, so
     *  they are cleared again whenever the border changes. */
    private static void installBorderGuard(final JComponent jc) {
        if (jc.getClientProperty("jdp.borderGuard") != null) return;
        jc.putClientProperty("jdp.borderGuard", Boolean.TRUE);
        jc.addPropertyChangeListener("border", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                javax.swing.border.Border nb = jc.getBorder();
                if (nb instanceof javax.swing.border.MatteBorder) clearMatte(jc);
                else if (nb instanceof javax.swing.border.LineBorder || nb instanceof javax.swing.border.EtchedBorder)
                    jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            }
        });
    }

    private static final String SB_ORIG_RENDERER = "jdp.sbOrigRenderer";
    private static final String SB_WRAP          = "jdp.sbWrap";
    private static final String SB_HOVER_ROW     = "jdp.sbHoverRow";
    private static final String SB_LISTENERS     = "jdp.sbListeners";
    private static final String SB_BTN           = "jdp.sbButton";
    private static final Color  SB_BASE          = PAL_BASE;
    private static final Color  SB_BTN_BG        = PAL_SURFACE;
    static final int    SB_BTN_GAP_V = 3, SB_BTN_GAP_H = 8, SB_BTN_ARC = UI_ARC;

    /** Paints each sidebar row as a rounded tile behind JD's transparent cell component. One instance
     *  is reused for every cell. */
    private static final class SidebarButton extends JComponent {
        private Component content;
        private Color fill;
        SidebarButton() {
            setLayout(new java.awt.BorderLayout());
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(SB_BTN_GAP_V, SB_BTN_GAP_H, SB_BTN_GAP_V, SB_BTN_GAP_H));
        }
        void set(Component c, Color f) {
            if (content != c) { removeAll(); content = c; add(c, java.awt.BorderLayout.CENTER); }
            fill = f;
        }
        protected void paintComponent(Graphics g) {
            if (fill != null) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                java.awt.Insets in = getInsets();
                g2.setColor(fill);
                g2.fillRoundRect(in.left, in.top, getWidth() - in.left - in.right,
                        getHeight() - in.top - in.bottom, SB_BTN_ARC, SB_BTN_ARC);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Wraps the settings sidebar renderer so the hovered row is filled with the accent like a table row;
     * JD's own hover is only a faint tint. All state hangs off the JList, so a rebuilt sidebar simply
     * installs it again.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installSidebarAccentHover(final javax.swing.JList list, javax.swing.ListCellRenderer jdRenderer) {
        list.putClientProperty(SB_ORIG_RENDERER, jdRenderer);
        javax.swing.ListCellRenderer wrap = (javax.swing.ListCellRenderer) list.getClientProperty(SB_WRAP);
        if (wrap == null) {
            wrap = new javax.swing.ListCellRenderer() {
                public Component getListCellRendererComponent(javax.swing.JList l, Object v, int idx, boolean sel, boolean foc) {
                    javax.swing.ListCellRenderer real = (javax.swing.ListCellRenderer) l.getClientProperty(SB_ORIG_RENDERER);
                    Component comp = real.getListCellRendererComponent(l, v, idx, sel, foc);
                    Object h = l.getClientProperty(SB_HOVER_ROW);
                    int hoverRow = (h instanceof Integer) ? ((Integer) h).intValue() : -1;
                    // The renderer component and its RenderLabel are shared between cells and JD
                    // resets neither foreground, so every row sets its own or the hover colour leaks
                    // into the rows painted after it.
                    boolean accentBg = sel || idx == hoverRow;
                    Color rowFg = accentBg ? accentFg() : SIDEBAR_TEXT;
                    if (comp instanceof javax.swing.JComponent) ((javax.swing.JComponent) comp).setOpaque(false);
                    comp.setForeground(rowFg);
                    boolean hovAcc = accentBg;
                    if (comp instanceof Container) {
                        for (Component k : ((Container) comp).getComponents()) {
                            k.setForeground(rowFg);
                            // JD sets the original icon again for every cell, so replacing it here
                            // never compounds.
                            if (k instanceof javax.swing.JLabel) {
                                javax.swing.JLabel kl = (javax.swing.JLabel) k;
                                javax.swing.Icon ic = kl.getIcon();
                                if (ic != null) {
                                    Color sbTone = hovAcc ? accentFg() : SIDEBAR_TEXT;
                                    String sbLc = (kl.getText() == null) ? "" : kl.getText().toLowerCase();
                                    String origKey = iconKey(ic);
                                    // The tray and advanced tiles are matched by icon key as well, since the
                                    // text is blank on rows that are not hovered.
                                    String sbOverride = (sbLc.contains("tray") || "minimize".equals(origKey)) ? "bottombar"
                                                      : (sbLc.contains("advanced") || "advancedConfig".equals(origKey)) ? "wizard" : null;
                                    javax.swing.Icon sbIcon = null;
                                    if (sbOverride != null) {
                                        javax.swing.Icon ov = tablerBase(sbOverride, 32, 32);
                                        if (ov != null) {
                                            if (ov.getIconWidth() != 32 || ov.getIconHeight() != 32) ov = scaleIconTo(ov, 32);
                                            sbIcon = tintIcon(ov, sbTone, kl);
                                        }
                                    }
                                    if (sbIcon == null) sbIcon = tablerIconScaled(ic, sbTone, kl, SIDEBAR_ICON_SCALE);
                                    kl.setIcon(sbIcon);
                                }
                                // The sidebar shows only centred glyphs; the hovered and the selected
                                // row show their name below the icon. JD sets the text again on every
                                // render, so blanking it here is safe.
                                boolean reveal = (idx == hoverRow) || sel;
                                if (!reveal && kl.getText() != null && kl.getText().length() > 0) kl.setText("");
                                if (kl.getHorizontalAlignment() != javax.swing.SwingConstants.CENTER)
                                    kl.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                                if (kl.getHorizontalTextPosition() != javax.swing.SwingConstants.CENTER)
                                    kl.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
                                int wantVpos = reveal ? javax.swing.SwingConstants.BOTTOM
                                                      : javax.swing.SwingConstants.CENTER;
                                if (kl.getVerticalTextPosition() != wantVpos) kl.setVerticalTextPosition(wantVpos);
                                if (kl.getVerticalAlignment() != javax.swing.SwingConstants.CENTER)
                                    kl.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
                                kl.setIconTextGap(2);
                                // JD's MigLayout anchors the RenderLabel at the top, so a top inset centres
                                // it. The border is only replaced when the inset changes, to avoid a
                                // repaint loop.
                                int innerH = Math.max(SIDEBAR_ROW_PX, l.getFixedCellHeight()) - 2 * SB_BTN_GAP_V;
                                java.awt.Insets kcur = (kl.getBorder() instanceof javax.swing.border.EmptyBorder)
                                        ? ((javax.swing.border.EmptyBorder) kl.getBorder()).getBorderInsets(kl)
                                        : new java.awt.Insets(0, 0, 0, 0);
                                int contentH = kl.getPreferredSize().height - kcur.top - kcur.bottom;
                                int topPad = Math.max(0, (innerH - contentH) / 2);
                                if (!(kl.getBorder() instanceof javax.swing.border.EmptyBorder) || kcur.top != topPad) {
                                    kl.setBorder(javax.swing.BorderFactory.createEmptyBorder(topPad, 0, 0, 0));
                                    if (comp instanceof Container) ((Container) comp).revalidate();
                                }
                            }
                        }
                    }
                    SidebarButton btn = (SidebarButton) l.getClientProperty(SB_BTN);
                    if (btn == null) { btn = new SidebarButton(); l.putClientProperty(SB_BTN, btn); }
                    Color acc = accentColor();
                    btn.set(comp, accentBg && acc != null ? acc : SB_BTN_BG);
                    return btn;
                }
            };
            list.putClientProperty(SB_WRAP, wrap);
        }
        if (list.getCellRenderer() != wrap) list.setCellRenderer(wrap);
        if (!SB_BASE.equals(list.getBackground())) { list.setBackground(SB_BASE); list.setOpaque(true); }
        if (list.getClientProperty(SB_LISTENERS) == null) {
            list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    int idx = list.locationToIndex(e.getPoint());
                    try {
                        if (idx >= 0) {
                            java.awt.Rectangle b = list.getCellBounds(idx, idx);
                            if (b == null || !b.contains(e.getPoint())) idx = -1;
                        }
                    } catch (Throwable ignore) { idx = -1; }
                    Object cur = list.getClientProperty(SB_HOVER_ROW);
                    int prev = (cur instanceof Integer) ? ((Integer) cur).intValue() : -1;
                    if (prev != idx) { list.putClientProperty(SB_HOVER_ROW, Integer.valueOf(idx)); list.repaint(); }
                }
            });
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseExited(java.awt.event.MouseEvent e) {
                    list.putClientProperty(SB_HOVER_ROW, Integer.valueOf(-1));
                    list.repaint();
                }
            });
            list.putClientProperty(SB_LISTENERS, Boolean.TRUE);
        }
    }

    /**
     * Strips the underline from settings section titles, which Header.java bakes into the HTML
     * markup where no colour key reaches it.
     */
    static void stripSectionUnderlines() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) stripUnderlinesIn(w);
        }
    }

    private static void stripUnderlinesIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                JLabel lbl = (JLabel) child;
                String t = lbl.getText();
                if (t != null && t.length() >= 7) {
                    String low = t.toLowerCase();
                    if (low.startsWith("<html") && low.contains("<u>")) {
                        lbl.setText(t.replace("<u>", "").replace("</u>", "")
                                     .replace("<U>", "").replace("</U>", ""));
                    }
                }
            }
            if (child instanceof Container) stripUnderlinesIn((Container) child);
        }
    }
}
