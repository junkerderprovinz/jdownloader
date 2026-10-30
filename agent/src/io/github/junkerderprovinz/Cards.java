package io.github.junkerderprovinz;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.Window;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Dialogs.*;
import static io.github.junkerderprovinz.Settings.*;
import static io.github.junkerderprovinz.Tables.*;

/** Rounded cards around the main tables, the bottom bar and the settings sections. */
final class Cards {

    private static final Color MAIN_CARD = PAL_SURFACE;
    private static final int   CARD_GAP  = 10;
    private static final int   CARD_ARC  = UI_RADIUS;
    // The left line that menu, toolbar, tabs, cards and the bottom bar all start on.
    static final int   EDGE      = 16;
    private static final String CARD_WHOLE = "jdp.cardWhole";

    // The overview under the lists, the button bar below it and the linkgrabber's views column become
    // cards like the list itself, all on the same left and right edge.
    static void cardBottomArea() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<Component> found = new ArrayList<>();
            collectBySuffix(w, found, "OverviewHeaderScrollPane", "SearchField", "LinkGrabberSideBarHeader");
            for (Component c : found) {
                String n = c.getClass().getName();
                if (n.endsWith("OverviewHeaderScrollPane")) cardOverview((javax.swing.JScrollPane) c);
                else if (n.endsWith("SearchField")) cardBottomBar(c.getParent());
                else cardSideBar(c);
            }
        }
    }
    private static void collectBySuffix(Container c, List<Component> out, String... suffixes) {
        for (Component ch : c.getComponents()) {
            String n = ch.getClass().getName();
            for (String s : suffixes) if (n.endsWith(s)) { out.add(ch); break; }
            if (ch instanceof Container) collectBySuffix((Container) ch, out, suffixes);
        }
    }
    private static void cardOverview(javax.swing.JScrollPane sp) {
        if (sp.getClientProperty(CARD_WHOLE) == null) {
            sp.putClientProperty(CARD_WHOLE, Boolean.TRUE);
            sp.setOpaque(true);
            sp.setBorder(new CardBorder(new java.awt.Insets(0, 0, 0, 0), new java.awt.Insets(6, 12, 8, 12)));
            Container holder = sp.getParent();
            if (holder instanceof JComponent)
                ((JComponent) holder).setBorder(javax.swing.BorderFactory.createEmptyBorder(0, EDGE, CARD_GAP, EDGE));
            if (holder != null) holder.revalidate();
        }
        if (!MAIN_CARD.equals(sp.getBackground())) sp.setBackground(MAIN_CARD);
        for (javax.swing.JViewport vp : new javax.swing.JViewport[] { sp.getViewport(), sp.getColumnHeader() }) {
            if (vp == null) continue;
            if (!MAIN_CARD.equals(vp.getBackground())) vp.setBackground(MAIN_CARD);
            Component view = vp.getView();
            if (view != null && view.isOpaque() && !MAIN_CARD.equals(view.getBackground())) view.setBackground(MAIN_CARD);
        }
        installCardCornerOverlay(sp);
    }
    // In the linkgrabber the start button under the views column is a bar of its own on the same row.
    private static void cardBottomBar(Container bar) {
        if (!(bar instanceof JComponent) || bar.getParent() == null) return;
        for (Component c : bar.getParent().getComponents()) {
            if (!(c instanceof javax.swing.JPanel) || !c.isVisible()) continue;
            if (c != bar && (c.getY() != bar.getY() || c.getHeight() != bar.getHeight())) continue;
            javax.swing.JPanel p = (javax.swing.JPanel) c;
            roundButtonsIn(p);
            if (p.getBorder() instanceof CardBorder) continue;
            boolean right = c.getX() > bar.getX();
            CardBorder card = new CardBorder(new java.awt.Insets(0, right ? 0 : EDGE, CARD_GAP, EDGE), new java.awt.Insets(6, 8, 6, 8));
            p.setOpaque(false);
            p.setBorder(card);
            java.awt.Insets in = card.getBorderInsets(p);
            growFixedHeight(p, 24 + in.top + in.bottom);
            p.revalidate();
        }
    }
    // JD pins the bar with a MigLayout "height 24!", which leaves no room for the card's padding.
    private static void growFixedHeight(Component c, int h) {
        try {
            Object lm = c.getParent().getLayout();
            Object cc = lm.getClass().getMethod("getComponentConstraints", Component.class).invoke(lm, c);
            if (!(cc instanceof String) || !((String) cc).matches("(?s).*height\\s*\\d+!.*")) return;
            String want = ((String) cc).replaceAll("height\\s*\\d+!", "height " + h + "!");
            lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class).invoke(lm, c, want);
        } catch (Throwable ignore) { }
    }
    private static void cardSideBar(Component header) {
        Container c = SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, header);
        if (!(c instanceof javax.swing.JScrollPane)) return;
        javax.swing.JScrollPane sp = (javax.swing.JScrollPane) c;
        if (sp.getClientProperty("jdp.sideCard") == null) {
            sp.putClientProperty("jdp.sideCard", Boolean.TRUE);
            sp.setOpaque(true);
            sp.setBackground(PAL_BASE);
            sp.setBorder(new CardBorder(new java.awt.Insets(CARD_GAP, 0, CARD_GAP, EDGE), new java.awt.Insets(0, 0, 0, 0)));
            sp.revalidate();
        }
        for (javax.swing.JViewport vp : new javax.swing.JViewport[] { sp.getViewport(), sp.getColumnHeader() }) {
            if (vp == null) continue;
            if (!MAIN_CARD.equals(vp.getBackground())) vp.setBackground(MAIN_CARD);
            if (vp.getView() != null) baseToCard(vp.getView());
        }
        installCardCornerOverlay(sp);
    }
    // The views column is built from JD panels in the base colour; the filter tables in it bring their
    // own colours and are left alone.
    private static void baseToCard(Component c) {
        if (c instanceof JTable) return;
        if (PAL_BASE.equals(c.getBackground())) c.setBackground(MAIN_CARD);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) baseToCard(ch);
    }
    /** Paints a rounded card behind a component's children, inside a margin of base colour. JD's header
     *  scrollpanes fill their title strip across the whole width first, which the margin paints over. */
    private static final class CardBorder implements javax.swing.border.Border {
        private final java.awt.Insets margin, pad;
        CardBorder(java.awt.Insets margin, java.awt.Insets pad) { this.margin = margin; this.pad = pad; }
        public boolean isBorderOpaque() { return false; }
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(margin.top + pad.top, margin.left + pad.left,
                    margin.bottom + pad.bottom, margin.right + pad.right);
        }
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(PAL_BASE);
                g2.fillRect(x, y, w, h);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(MAIN_CARD);
                g2.fillRoundRect(x + margin.left, y + margin.top, w - margin.left - margin.right,
                        h - margin.top - margin.bottom, UI_ARC, UI_ARC);
            } finally { g2.dispose(); }
        }
    }

    // The download and linkgrabber tables sit on a lighter card with a margin of base colour around it,
    // like the settings cards.
    static void cardMainTables() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                String cn = t.getClass().getName();
                if (!(cn.endsWith("DownloadsTable") || cn.endsWith("LinkGrabberTable"))) continue;
                javax.swing.JScrollPane sp = null;
                for (Container p = t.getParent(); p != null; p = p.getParent())
                    if (p instanceof javax.swing.JScrollPane) { sp = (javax.swing.JScrollPane) p; break; }
                if (sp == null) continue;
                if (!MAIN_CARD.equals(t.getBackground())) t.setBackground(MAIN_CARD);
                // Without filling the viewport the card ends at the last row, far above the bottom
                // corner masks, and its bottom stays square.
                try { if (!t.getFillsViewportHeight()) t.setFillsViewportHeight(true); } catch (Throwable ig) { }
                javax.swing.JViewport vp = sp.getViewport();
                if (vp != null && !MAIN_CARD.equals(vp.getBackground())) { vp.setBackground(MAIN_CARD); vp.setOpaque(true); }
                javax.swing.JViewport ch = sp.getColumnHeader();
                if (ch != null && !MAIN_CARD.equals(ch.getBackground())) ch.setBackground(MAIN_CARD);
                // The scrollbar column and JD's column chooser above it belong to the card, so its right
                // edge lines up with the cards below.
                Component corner = sp.getCorner(javax.swing.ScrollPaneConstants.UPPER_RIGHT_CORNER);
                if (corner != null && !MAIN_CARD.equals(corner.getBackground())) corner.setBackground(MAIN_CARD);
                javax.swing.JScrollBar vsb = sp.getVerticalScrollBar();
                if (vsb != null && vsb.getClientProperty("FlatLaf.style") == null)
                    vsb.putClientProperty("FlatLaf.style", "track: #242424; hoverTrackColor: #242424");
                if (vsb != null && !MAIN_CARD.equals(vsb.getBackground())) vsb.setBackground(MAIN_CARD);
                if (sp.getClientProperty("jdp.mainCard") == null) {
                    sp.setOpaque(true);
                    sp.setBackground(PAL_BASE);
                    sp.setBorder(javax.swing.BorderFactory.createEmptyBorder(CARD_GAP, EDGE, CARD_GAP, EDGE));
                    sp.putClientProperty("jdp.mainCard", Boolean.TRUE);
                    sp.revalidate(); sp.repaint();
                }
                installCardCornerOverlay(sp);
            }
        }
    }

    /** Rounds the card by painting its four corners in the base colour on top of the opaque table, the
     *  only way to round a live Swing table. */
    private static void installCardCornerOverlay(javax.swing.JScrollPane sp) {
        try {
            if (!sp.isShowing()) return;
            // The masks live on a layer of the layered pane: a sibling of the scrollpane gets overdrawn by
            // every row repaint, a higher layer is repainted on top of any dirty region.
            javax.swing.JRootPane rp = javax.swing.SwingUtilities.getRootPane(sp);
            if (rp == null) return;
            javax.swing.JLayeredPane lp = rp.getLayeredPane();
            if (lp == null) return;
            Object existing = sp.getClientProperty("jdp.cardCorner");
            CardCornerOverlay ov;
            if (existing instanceof CardCornerOverlay && ((CardCornerOverlay) existing).getParent() == lp) {
                ov = (CardCornerOverlay) existing;
            } else {
                if (existing instanceof CardCornerOverlay && ((CardCornerOverlay) existing).getParent() != null)
                    ((CardCornerOverlay) existing).getParent().remove((CardCornerOverlay) existing);
                ov = new CardCornerOverlay();
                lp.add(ov, javax.swing.JLayeredPane.PALETTE_LAYER);   // above the content, below popups
                sp.putClientProperty("jdp.cardCorner", ov);
            }
            ov.sp = sp;
            java.awt.Rectangle b = javax.swing.SwingUtilities.convertRectangle(sp.getParent(), sp.getBounds(), lp);
            if (b.width <= 0 || b.height <= 0) return;
            // Swing repaints the layer over any dirty region below it, so the overlay itself only
            // needs a repaint when it moves.
            if (!ov.getBounds().equals(b)) {
                ov.setBounds(b);
                lp.setLayer(ov, javax.swing.JLayeredPane.PALETTE_LAYER);
                ov.repaint();
            }
        } catch (Throwable ignore) { }
    }
    private static final class CardCornerOverlay extends JComponent {
        javax.swing.JScrollPane sp;
        CardCornerOverlay() { setOpaque(false); }
        @Override public boolean contains(int x, int y) { return false; }   // lets mouse events through to the table
        @Override protected void paintComponent(Graphics g) {
            // A hidden or replaced card leaves its overlay behind, which then must not paint.
            if (sp == null || !sp.isShowing()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(PAL_BASE);
                int r = CARD_ARC;
                // The card is the viewport plus the column header. The scrollbar and JD's column-control
                // corner take a column on the right, so the scrollpane bounds minus the margin would miss it.
                int L = CARD_GAP, T = CARD_GAP, R = getWidth() - CARD_GAP, B = getHeight() - CARD_GAP;
                try {
                    javax.swing.JViewport vp = (sp != null) ? sp.getViewport() : null;
                    // A card that pads its content from the inside fills its whole scrollpane.
                    if (sp != null && Boolean.TRUE.equals(sp.getClientProperty(CARD_WHOLE))) {
                        L = 0; T = 0; R = getWidth(); B = getHeight();
                    } else if (vp != null) {
                        java.awt.Rectangle vb = vp.getBounds();   // overlay and scrollpane share coordinates
                        L = vb.x; R = vb.x + vb.width; B = vb.y + vb.height;
                        javax.swing.JViewport ch = sp.getColumnHeader();
                        T = (ch != null) ? ch.getBounds().y : vb.y;
                        javax.swing.JScrollBar vsb = sp.getVerticalScrollBar();
                        if (Boolean.TRUE.equals(sp.getClientProperty("jdp.mainCard")) && vsb != null && vsb.isVisible())
                            R = Math.max(R, vsb.getX() + vsb.getWidth());
                    }
                } catch (Throwable ignore) { }
                paintCorner(g2, L, T, r, true, true);
                paintCorner(g2, R, T, r, false, true);
                paintCorner(g2, L, B, r, true, false);
                paintCorner(g2, R, B, r, false, false);
            } finally { g2.dispose(); }
        }
        private void paintCorner(Graphics2D g2, int cx, int cy, int r, boolean left, boolean top) {
            int x = left ? cx : cx - r, y = top ? cy : cy - r;
            int ecx = cx + (left ? r : -r), ecy = cy + (top ? r : -r);
            java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.Rectangle(x, y, r, r));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(ecx - r, ecy - r, 2 * r, 2 * r)));
            g2.fill(a);
        }
    }

    // Lines the first toolbar glyph up with the content of the list card. JD's MainToolBar switches between
    // a MigLayout with its own leading inset and DefaultToolBarLayout, so both the border and the layout
    // inset are set.
    static void alignToolbarLeft() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            java.util.List<Container> tbs = new java.util.ArrayList<Container>();
            findToolbars(w, tbs);
            for (Container tb : tbs) {
                if (!(tb instanceof JComponent)) continue;
                JComponent tbj = (JComponent) tb;
                Component first = null;
                for (Component ch : tb.getComponents()) if (ch.getWidth() > 0 && (first == null || ch.getX() < first.getX())) first = ch;
                if (first == null) continue;
                boolean changed = false;
                // The first button's hover pill starts on EDGE with the tabs and cards.
                final int TB_LEFT = EDGE;
                java.awt.Insets bi = tbj.getInsets();
                if (bi != null && bi.left != TB_LEFT) {
                    tbj.setBorder(javax.swing.BorderFactory.createEmptyBorder(bi.top, TB_LEFT, bi.bottom, bi.right));
                    changed = true;
                }
                Object lm = tb.getLayout();
                if (lm != null && lm.getClass().getName().contains("MigLayout") && first.getX() > CARD_GAP + 1) {
                    try {
                        lm.getClass().getMethod("setLayoutConstraints", Object.class).invoke(lm, "ins 0 0 0 0");
                        try { lm.getClass().getMethod("invalidateLayout", Container.class).invoke(lm, tb); } catch (Throwable ig) { }
                        changed = true;
                    } catch (Throwable t) { }
                }
                if (changed) { tb.revalidate(); tb.repaint(); }
            }
        }
    }
    private static void findToolbars(Container c, java.util.List<Container> out) {
        for (Component ch : c.getComponents()) {
            if (isMainToolbar(ch.getClass()) && ch instanceof Container) out.add((Container) ch);
            if (ch instanceof Container) findToolbars((Container) ch, out);
        }
    }

    // Settings sections as cards. A Border on each AbstractConfigPanel paints a rounded band behind
    // every section, between successive headers. A border paints after the background and before the
    // children, so nothing has to be re-parented, which would break because JD rebuilds the panel on
    // every navigation.
    static void cardSettingsSections() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) cardSectionsIn(w);
        }
    }

    private static void cardSectionsIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent && isConfigPanel(child.getClass())) {
                JComponent panel = (JComponent) child;
                if (!(panel.getBorder() instanceof SectionCardBorder)) {
                    try {
                        panel.setBorder(new SectionCardBorder(panel.getBorder()));
                        panel.revalidate();
                        panel.repaint();
                        System.out.println("[jd-dialog-agent] jd-highlighter: carded settings panel "
                                + child.getClass().getSimpleName());
                    } catch (Throwable ignore) { }
                }
                hideSeparators(panel);   // the cards replace the section lines
                spreadSections(panel);
            }
            if (child instanceof Container) cardSectionsIn((Container) child);
        }
    }

    static boolean isConfigPanel(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass())
            if (k.getName().endsWith(".AbstractConfigPanel")) return true;
        return false;
    }

    static boolean isMainToolbar(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass())
            if (k.getName().endsWith(".MainToolBar")) return true;
        return false;
    }

    static void hideSeparators(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JSeparator) { if (ch.isVisible()) ch.setVisible(false); }
            else if (ch instanceof Container) hideSeparators((Container) ch);
        }
    }

    private static final class SectionCardBorder implements javax.swing.border.Border {
        private final javax.swing.border.Border original;
        private static final Color CARD = PAL_SURFACE;
        private static final int MARGIN = 10, INNER = 12, ARC = UI_ARC;
        SectionCardBorder(javax.swing.border.Border original) { this.original = original; }

        public boolean isBorderOpaque() { return false; }

        public java.awt.Insets getBorderInsets(Component c) {
            // MARGIN outside the card plus INNER inside it
            int p = MARGIN + INNER;
            java.awt.Insets in = (original != null) ? original.getBorderInsets(c)
                    : new java.awt.Insets(0, 0, 0, 0);
            return new java.awt.Insets(in.top + p, in.left + p, in.bottom + p, in.right + p);
        }

        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (!(c instanceof Container)) return;
            Container p = (Container) c;
            List<Component> headers = new ArrayList<>();
            for (Component ch : p.getComponents())
                if (ch.isVisible() && ch.getClass().getName().endsWith(".Header")) headers.add(ch);
            if (!headers.isEmpty()) {
                headers.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(CARD);
                    int left = x + MARGIN, right = x + w - MARGIN;
                    for (int i = 0; i < headers.size(); i++) {
                        int hTop = headers.get(i).getY();
                        int hNext = (i + 1 < headers.size()) ? headers.get(i + 1).getY() : Integer.MAX_VALUE;
                        // Only this section's rows, so the gap from spreadSections shows between cards.
                        int contentBottom = hTop + headers.get(i).getHeight();
                        for (Component ch : p.getComponents()) {
                            if (!ch.isVisible()) continue;
                            int cy = ch.getY();
                            if (cy >= hTop && cy < hNext) contentBottom = Math.max(contentBottom, cy + ch.getHeight());
                        }
                        // The first card starts level with the first sidebar tile, and the last one
                        // reaches the bottom of the panel like the sidebar does.
                        int top = (i == 0) ? y + SB_BTN_GAP_V : hTop - INNER;
                        int bottom = (i == headers.size() - 1)
                                ? Math.max(contentBottom + INNER, y + h - MARGIN)
                                : (contentBottom + INNER);
                        if (bottom - top > 6)
                            g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                                    left, top, right - left, bottom - top, ARC, ARC));
                    }
                } finally { g2.dispose(); }
            }
            if (original != null) original.paintBorder(c, g, x, y, w, h);
        }
    }

    // A gap above every section but the first, set through the panel's MigLayout constraints.
    private static void spreadSections(JComponent panel) {
        LayoutManager lm = panel.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method gc = lm.getClass().getMethod("getComponentConstraints", Component.class);
            Method sc = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            boolean first = true, changed = false;
            for (Component ch : panel.getComponents()) {
                if (!ch.getClass().getName().endsWith(".Header")) continue;
                if (first) { first = false; continue; }
                Object cur = gc.invoke(lm, ch);
                String cc = (cur == null) ? "" : cur.toString();
                if (cc.contains("gaptop")) continue;
                sc.invoke(lm, ch, (cc.isEmpty() ? "" : cc + ",") + "gaptop 34");   // = MARGIN + 2*INNER
                changed = true;
            }
            if (changed) { panel.revalidate(); panel.repaint(); }
        } catch (Throwable ignore) { }
    }
}
