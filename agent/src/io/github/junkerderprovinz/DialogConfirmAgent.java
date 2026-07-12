package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.text.JTextComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Frame;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Minimal JVM agent for the JDownloader container. Two jobs, both in-process on
 * the EDT, both unavoidable from outside the JVM:
 *
 *  1. Auto-confirms JD's mandatory installer dialogs so the user never clicks them.
 *     JD FORCES these GUI confirmations whenever its window is visible
 *     (org.jdownloader.updatev2.UpdateController: "if (handler.isGuiVisible() || ...)
 *     confirm(...)"), so no config can suppress them.
 *
 *  2. Enforces a pure #161616 monochrome dark chrome. JD's content areas are dark
 *     via its native colorfor* config, but the window chrome (menu/tool bars, frames,
 *     dialogs, tabs, scrollbars) is painted from FlatLaf's own UIManager colour
 *     defaults (a mid-grey) which colorfor* cannot reach. We remap those defaults to
 *     the Carbon greyscale (grey -> darkened onto the #161616 scale, blue accent ->
 *     grey #525252; functional red/amber left alone) and refresh every window.
 *     Patching FlatLaf's jar instead would trip JD's integrity check / crash loop;
 *     an in-process UIManager override has no such failure mode.
 *
 * It runs forever (daemon) so it also handles dialogs + re-applies the chrome after
 * JD's later self-updates, not just the first install. Hooked via
 *   JAVA_TOOL_OPTIONS=-javaagent:/opt/JDownloader/jd-dialog-agent.jar
 */
public class DialogConfirmAgent {

    // --- Carbon greyscale (matches jdownloader-theme.sh content palette) ---
    private static final ColorUIResource BG     = new ColorUIResource(0x16, 0x16, 0x16);
    private static final ColorUIResource HEADER = new ColorUIResource(0x0b, 0x0b, 0x0b);
    private static final ColorUIResource SEL    = new ColorUIResource(0x52, 0x52, 0x52);

    // Plain (non-UIResource) colours set directly on the table progress-bar instances so a
    // later updateUI cannot override them. Fill must be visible on the dark track.
    private static final Color BAR_FILL  = new Color(0x55, 0x55, 0x55);
    private static final Color BAR_TRACK = new Color(0x26, 0x26, 0x26);

    // Chrome is enforced exactly ONCE per JVM, and only after JD's main window is shown
    // and stable — see enforceDarkChrome().
    private static boolean chromeDone  = false;
    private static int     stableTicks = 0;

    // --- Ground-truth markers for the container (autostart READY gate) -----------
    // The launcher used to infer "themed" from a patched flatlaf.jar on disk — which
    // says nothing about whether the LAF was actually APPLIED (a pending "restart to
    // apply" dialog left the GUI white while the banner fired). These markers are the
    // in-JVM truth: the agent's PID, and the class name of the ACTIVE look-and-feel.
    private static final java.io.File PID_FILE        = new java.io.File("/tmp/.jd-agent.pid");
    private static final java.io.File LAF_FILE        = new java.io.File("/tmp/.jd-laf-applied");
    private static final java.io.File RESTART_REQUEST = new java.io.File("/tmp/.jd-laf-restart-request");
    private static String lastLafWritten = null;
    private static int    lafTick        = 0;

    // Per-window guards: don't re-click a button more than once per 5s (a swallowed
    // click may still need a retry), and log each unmatched dialog only once.
    private static final java.util.Map<Window, Long> CLICKED_AT =
            new java.util.WeakHashMap<Window, Long>();
    private static final java.util.Set<Window> LOGGED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());
    private static final java.util.Set<Window> RESTART_REQUESTED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[jd-dialog-agent] watching for installer dialogs + enforcing dark chrome");
        writeFile(PID_FILE, Long.toString(ProcessHandle.current().pid()));
        Thread t = new Thread(DialogConfirmAgent::watch, "jd-dialog-agent");
        t.setDaemon(true);
        t.start();
    }

    private static void writeFile(java.io.File f, String content) {
        try {
            java.nio.file.Files.write(f.toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // /tmp unwritable — markers are best-effort, the launcher has fallbacks
        }
    }

    /** Record the ACTIVE look-and-feel class every few seconds (ground truth for READY). */
    private static void writeLafMarker() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null) return;
        String cn = laf.getClass().getName();
        if (!cn.equals(lastLafWritten) || !LAF_FILE.exists()) {
            writeFile(LAF_FILE, cn);
            lastLafWritten = cn;
        }
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(400);
                SwingUtilities.invokeAndWait(DialogConfirmAgent::tick);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // ignore Swing-side exceptions and keep watching
            }
        }
    }

    private static void tick() {
        handleDialogs();
        enforceDarkChrome();
        retintProgressBars();
        widenSpeedEditors();
        growSpeedMeter();
        replaceSpeedGraph();
        if (++lafTick >= 12) {   // every ~5s (ticks run every 400ms)
            lafTick = 0;
            writeLafMarker();
        }
    }

    // -------------------------------------------------------- speed graph replacement

    /**
     * AppWork's Graph paints every sample as `(int)(height * value * 0.9) / max` where
     * value is the raw download speed in BYTES/s. `height * value` is an int*int
     * product that silently overflows above ~2.1e9 - i.e. from ~34 MiB/s at our 64px
     * row (~67 MiB/s at the stock 32px). Overflowed samples wrap low or clip below the
     * widget, so at gigabit speeds the graph permanently paints at a fraction of its
     * height. Nothing configurable fixes that, so we hide the native SpeedMeterPanel
     * and paint our own graph with long arithmetic: full height at any speed, same
     * look (colours from LAFOptions, texts reused from the native panel, limiter band
     * included). Mouse events are forwarded to the hidden native panel so its
     * speed-limit menu keeps working. JD's updateToolbar() rebuilds (removeAll) are
     * healed by the tick: when the native panel reappears without ours, we re-attach.
     */
    private static CarbonSpeedGraph ownGraph = null;

    private static void replaceSpeedGraph() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) replaceSpeedGraphIn(w);
        }
    }

    private static void replaceSpeedGraphIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent && child.getClass().getName().endsWith(".SpeedMeterPanel")) {
                attachOwnGraph((JComponent) child);
                return;
            }
            if (child instanceof Container) replaceSpeedGraphIn((Container) child);
        }
    }

    private static void attachOwnGraph(JComponent nativePanel) {
        try {
            Container parent = nativePanel.getParent();
            if (parent == null) return;

            boolean ourPresent = false;
            for (Component comp : parent.getComponents()) {
                if (comp instanceof CarbonSpeedGraph) { ourPresent = true; break; }
            }
            if (ownGraph == null) ownGraph = new CarbonSpeedGraph();
            ownGraph.bindNative(nativePanel);

            if (nativePanel.isVisible()) {
                // exclude the hidden native from the layout (hidemode 3), keep it alive
                // for its fetcher thread, localized strings and the speed-limit menu.
                LayoutManager lm = parent.getLayout();
                if (lm != null && lm.getClass().getName().contains("MigLayout")) {
                    try {
                        Method m = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
                        m.invoke(lm, nativePanel, "width 32:300:300,pushy,growy,hidemode 3");
                    } catch (Exception ignore) { }
                }
                nativePanel.setVisible(false);
            }
            if (!ourPresent) {
                parent.add(ownGraph, "width 32:300:300,pushy,growy");
                parent.revalidate();
                parent.repaint();
                System.out.println("[jd-dialog-agent] replaced the speed graph (native math overflows above ~34 MiB/s)");
            }
        } catch (Exception ignore) { }
    }

    /**
     * Minimal, overflow-free speed graph: ring buffer of long samples polled from
     * DownloadWatchDog (reflection - the agent compiles against the JDK alone), the
     * same visual language as the native one (current-speed gradient polygon,
     * semi-transparent average overlay, limiter band, right-aligned text lines).
     */
    private static final class CarbonSpeedGraph extends JComponent {
        private static final int SAMPLES = 90;
        private final long[] samples  = new long[SAMPLES];
        private final long[] averages = new long[SAMPLES];
        private int  head = 0;
        private long current = 0, average = 0, limit = 0;
        private volatile JComponent nativePanel = null;

        private Color colTop    = new Color(0x3f, 0xb9, 0x3f);
        private Color colBottom = new Color(0x14, 0x46, 0x14);
        private Color colAvg    = new Color(0x86, 0xd9, 0x86);
        private Color colText   = new Color(0xf4, 0xf4, 0xf4);
        private Color colLimit  = new Color(0xd9, 0x53, 0x53);

        CarbonSpeedGraph() {
            setOpaque(false);
            loadLafColors();
            new javax.swing.Timer(500, e -> sample()).start();
            MouseAdapter fwd = new MouseAdapter() {
                private void fw(MouseEvent e) {
                    JComponent np = nativePanel;
                    if (np != null) np.dispatchEvent(SwingUtilities.convertMouseEvent(CarbonSpeedGraph.this, e, np));
                }
                @Override public void mouseClicked(MouseEvent e)  { fw(e); }
                @Override public void mousePressed(MouseEvent e)  { fw(e); }
                @Override public void mouseReleased(MouseEvent e) { fw(e); }
            };
            addMouseListener(fwd);
        }

        void bindNative(JComponent np) { this.nativePanel = np; }

        private void loadLafColors() {
            try {
                Class<?> laf = Class.forName("org.jdownloader.updatev2.gui.LAFOptions");
                Object inst = laf.getMethod("getInstance").invoke(null);
                Object top = laf.getMethod("getColorForSpeedmeterCurrentTop").invoke(inst);
                Object bot = laf.getMethod("getColorForSpeedmeterCurrentBottom").invoke(inst);
                Object avg = laf.getMethod("getColorForSpeedMeterAverage").invoke(inst);
                Object txt = laf.getMethod("getColorForSpeedMeterText").invoke(inst);
                if (top instanceof Color) colTop = (Color) top;
                if (bot instanceof Color) colBottom = (Color) bot;
                if (avg instanceof Color) colAvg = (Color) avg;
                if (txt instanceof Color) colText = (Color) txt;
            } catch (Throwable ignore) { /* fallback palette above */ }
        }

        private void sample() {
            long v = readSpeedSafe();
            long lim = readLimit();
            synchronized (samples) {
                current = v;
                limit = lim;
                samples[head] = v;
                long sum = 0;
                for (long s : samples) sum += s;
                average = sum / SAMPLES;
                averages[head] = average;
                head = (head + 1) % SAMPLES;
            }
            repaint();
        }

        /** Primary: the native panel's own public getValue() (same number the native
         *  graph would plot). Fallback: DownloadWatchDog reflection. */
        private long readSpeedSafe() {
            JComponent np = nativePanel;
            if (np != null) {
                try {
                    Object v = np.getClass().getMethod("getValue").invoke(np);
                    if (v instanceof Number) return Math.max(0L, ((Number) v).longValue());
                } catch (Throwable ignore) { }
            }
            return readSpeed();
        }

        private static long readSpeed() {
            try {
                Class<?> wd = Class.forName("jd.controlling.downloadcontroller.DownloadWatchDog");
                Object inst = wd.getMethod("getInstance").invoke(null);
                Object dsm = inst.getClass().getMethod("getDownloadSpeedManager").invoke(inst);
                Object spd = dsm.getClass().getMethod("getSpeed").invoke(dsm);
                return spd instanceof Number ? ((Number) spd).longValue() : 0L;
            } catch (Throwable t) { return 0L; }
        }

        private long readLimit() {
            JComponent np = nativePanel;
            if (np == null) return 0L;
            try {
                Object arr = np.getClass().getMethod("getLimiter").invoke(np);
                if (arr instanceof Object[]) {
                    for (Object l : (Object[]) arr) {
                        if (l == null) continue;
                        Object v = l.getClass().getMethod("getValue").invoke(l);
                        long lv = v instanceof Number ? ((Number) v).longValue() : 0L;
                        if (lv > 0) return lv;
                    }
                }
            } catch (Throwable ignore) { }
            return 0L;
        }

        private String nativeString(String method) {
            JComponent np = nativePanel;
            if (np == null) return null;
            try {
                Object s = np.getClass().getMethod(method).invoke(np);
                return s instanceof String ? (String) s : null;
            } catch (Throwable t) { return null; }
        }

        private static String fmt(long bytes) {
            if (bytes >= 1048576L) return String.format("%.2f MiB/s", bytes / 1048576.0);
            return String.format("%.0f KiB/s", bytes / 1024.0);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int w = getWidth(), h = getHeight();
                if (w <= 2 || h <= 2) return;

                final long[] snap, asnap;
                final int hd;
                final long lim, cur, avg;
                synchronized (samples) {
                    snap = samples.clone();
                    asnap = averages.clone();
                    hd = head;
                    lim = limit;
                    cur = current;
                    avg = average;
                }

                long max = 10;
                for (long v : snap) if (v > max) max = v;
                for (long v : asnap) if (v > max) max = v;
                if (lim > max) max = lim;

                // polygons, oldest -> newest, LONG math: h * value never overflows
                final Polygon poly = new Polygon();
                final Polygon apoly = new Polygon();
                poly.addPoint(0, h);
                apoly.addPoint(0, h);
                for (int x = 0; x < SAMPLES; x++) {
                    final int idx = (hd + x) % SAMPLES;
                    final int px = (int) ((long) x * w / (SAMPLES - 1));
                    poly.addPoint(px, h - (int) (h * snap[idx] * 9L / (10L * max)));
                    apoly.addPoint(px, h - (int) (h * asnap[idx] * 9L / (10L * max)));
                }
                poly.addPoint(w, h);
                apoly.addPoint(w, h);

                g2.setPaint(new GradientPaint(w / 2f, 0, colTop, w / 2f, h, colBottom));
                g2.fill(poly);
                g2.setColor(colBottom);
                g2.draw(poly);

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2.setColor(colAvg);
                g2.fill(apoly);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
                g2.draw(apoly);

                if (lim > 0) {
                    final int ly = h - (int) (h * lim * 9L / (10L * max));
                    g2.setColor(new Color(colLimit.getRed(), colLimit.getGreen(), colLimit.getBlue(), 170));
                    g2.fillRect(0, Math.max(0, ly), w, Math.max(2, h / 14));
                }

                // right-aligned texts, reusing the native panel's localized strings
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                final int pad = 3;
                int ty = 12;
                if (lim > 0) {
                    String ls = null;
                    try {
                        JComponent np = nativePanel;
                        if (np != null) {
                            Object arr = np.getClass().getMethod("getLimiter").invoke(np);
                            if (arr instanceof Object[] && ((Object[]) arr).length > 0 && ((Object[]) arr)[0] != null) {
                                Object s = ((Object[]) arr)[0].getClass().getMethod("getString").invoke(((Object[]) arr)[0]);
                                if (s instanceof String) ls = (String) s;
                            }
                        }
                    } catch (Throwable ignore) { }
                    if (ls == null) ls = "Limit: " + fmt(lim);
                    g2.setColor(colLimit);
                    g2.drawString(ls, w - g2.getFontMetrics().stringWidth(ls) - pad, ty);
                    ty += 13;
                }
                String as = nativeString("getAverageSpeedString");
                String cs = nativeString("getSpeedString");
                String line = ((as != null ? as : "Ø " + fmt(avg)) + "  " + (cs != null ? cs : fmt(cur))).trim();
                g2.setColor(colText);
                g2.drawString(line, w - g2.getFontMetrics().stringWidth(line) - pad, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    // -------------------------------------------------------- speed graph height

    /**
     * JD's download graph (SpeedMeterPanel) lives in the MainToolBar whose single
     * MigLayout row is HARDCODED to 32px ("[grow,32!]") - there is no config key for
     * it. With the premium banner disabled the corner looks half-empty and the graph
     * cramped, so we grow the toolbar row at runtime; the speedmeter is added with
     * "pushy,growy" and follows, the 32px tool buttons stay centered. Same reflection
     * pattern as widenSpeedEditors(); a client-property guard keeps it one-shot per
     * toolbar instance (the row constraint survives JD's updateToolbar() rebuilds
     * because the LayoutManager object is kept).
     */
    private static final int    SPEEDMETER_ROW_PX = 64;
    private static final String GROWN             = "jdp.speedMeterGrown";

    private static void growSpeedMeter() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) growSpeedMeterIn(w);
        }
    }

    private static void growSpeedMeterIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child.getClass().getName().endsWith(".SpeedMeterPanel")) {
                growToolbarRow(child.getParent());
            } else if (child instanceof Container) {
                growSpeedMeterIn((Container) child);
            }
        }
    }

    private static void growToolbarRow(Container toolbar) {
        if (!(toolbar instanceof JComponent)) return;
        JComponent tb = (JComponent) toolbar;
        if (Boolean.TRUE.equals(tb.getClientProperty(GROWN))) return;

        LayoutManager lm = tb.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method m = lm.getClass().getMethod("setRowConstraints", Object.class);
            m.invoke(lm, "[grow," + SPEEDMETER_ROW_PX + "!]");
            tb.putClientProperty(GROWN, Boolean.TRUE);
            tb.revalidate();
            tb.repaint();
            System.out.println("[jd-dialog-agent] grew the speed graph row to " + SPEEDMETER_ROW_PX + "px");
        } catch (Exception ignore) {
            // setRowConstraints absent / layout differs -> leave the toolbar as-is
        }
    }

    // -------------------------------------------------------- speed editor width

    /**
     * JD's speed-limit menu field (jd.gui.swing.jdgui.menu.SpeedlimitEditor) is laid
     * out with a FIXED MigLayout width: MenuEditor.getEditorWidth() hardcodes it to
     * fit "500.00 KB/s" (+30px), so a higher limit such as "10.216,00 MiB/s" is
     * clipped and the value can't be read. We relax the spinner's width constraint at
     * runtime and grow the enclosing popup so the whole value shows. The editor is
     * rebuilt every time the menu opens, so this re-applies on each open; a per-
     * instance client-property guard keeps it from relaying an already-widened editor
     * on every tick. All reflection so the agent still compiles against the JDK alone.
     */
    private static final String WIDENED = "jdp.speedWidened";

    private static void widenSpeedEditors() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) widenSpeedIn(w);
        }
    }

    private static void widenSpeedIn(Container c) {
        for (Component child : c.getComponents()) {
            if (isSpeedEditor(child.getClass())) {
                if (child instanceof Container) widenEditor((Container) child);
            } else if (child instanceof Container) {
                widenSpeedIn((Container) child);
            }
        }
    }

    /** True if the class IS or EXTENDS jd...menu.SpeedlimitEditor (JD adds it as an
     *  anonymous subclass, so we must check the whole superclass chain). */
    private static boolean isSpeedEditor(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().endsWith(".SpeedlimitEditor")) return true;
        }
        return false;
    }

    private static void widenEditor(Container editor) {
        if (!(editor instanceof JComponent)) return;
        JComponent jc = (JComponent) editor;
        if (Boolean.TRUE.equals(jc.getClientProperty(WIDENED))) return;

        JSpinner spinner = null;
        for (Component ch : editor.getComponents()) {
            if (ch instanceof JSpinner) { spinner = (JSpinner) ch; break; }
        }
        if (spinner == null) return;

        LayoutManager lm = editor.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;

        // Mirror JD's own formula (label width + 30 for the spinner arrows/insets) but
        // with a long sample so the field fits any realistic limit incl. its unit.
        int w = new JLabel("99999,99 MiB/s").getPreferredSize().width + 30;
        try {
            Method m = lm.getClass().getMethod("setComponentConstraints",
                    Component.class, Object.class);
            m.invoke(lm, spinner, "width " + w + "!");
            jc.putClientProperty(WIDENED, Boolean.TRUE);
            editor.revalidate();
            editor.repaint();
            // Grow the visible popup so the wider field is not clipped by popup bounds.
            JPopupMenu pm = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, editor);
            if (pm != null) {
                Dimension pref = pm.getPreferredSize();
                pm.setPopupSize(pref.width, pref.height);
            }
        } catch (Exception ignore) {
            // setComponentConstraints absent / layout differs -> leave the field as-is
        }
    }

    // ------------------------------------------------------------ progress bars

    /**
     * The download-list + account-traffic progress bars are AppWork RendererProgressBars
     * (JProgressBars). Their fill colour is FlatLaf's runtime accent (ProgressBar.foreground
     * = @accentSliderColor), computed at runtime — it cannot be set via static FlatLaf
     * properties, nor reached by updateComponentTreeUI (cell renderers aren't in the tree).
     * AppWork holds the bar instances in ExtProgressColumn fields and does NOT colour them
     * per cell, so setting the colour directly on those instances sticks. We find them by
     * walking tables -> columns -> any JProgressBar-typed field and recolour them. Cheap and
     * idempotent; runs every tick so tables opened later (the account manager) are caught too.
     */
    private static void retintProgressBars() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                for (Object col : extColumns(t)) {
                    recolorBarFields(col);
                }
            }
        }
    }

    private static void collectTables(Container c, List<JTable> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTable) out.add((JTable) child);
            if (child instanceof Container) collectTables((Container) child, out);
        }
    }

    /** AppWork ExtColumn objects of a table (they hold the renderer progress bars). */
    private static List<Object> extColumns(JTable t) {
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
                        if (!BAR_FILL.equals(pb.getForeground())) pb.setForeground(BAR_FILL);
                        if (!BAR_TRACK.equals(pb.getBackground())) pb.setBackground(BAR_TRACK);
                    }
                } catch (Exception ignore) { }
            }
        }
    }

    // ---------------------------------------------------------------- chrome

    /**
     * Recolour FlatLaf's UIManager colour defaults to the #161616 greyscale, exactly
     * ONCE per JVM, and only AFTER JD's main window is built, shown and stable.
     *
     * Re-creating UI delegates (updateComponentTreeUI) while JD is still packing its
     * frame makes AppWork's CircleProgressBarUI NPE during addNotify and crashes the GUI
     * into a boot loop. Waiting until a frame has been showing for a few ticks guarantees
     * pack() is finished, so our refresh never collides with it. (A JD in-process LAF
     * re-apply afterwards would revert the chrome, but JD only applies its LAF during
     * early startup; a self-update restarts the JVM, which re-runs this from scratch.)
     */
    private static void enforceDarkChrome() {
        if (chromeDone) return;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        // Wait for JD's MAIN window (large / maximised) to be shown and stable before
        // touching any UI — not a small splash/progress frame, and never while JD is
        // still packing (that is what triggered the CircleProgressBarUI crash).
        boolean ready = false;
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400) { ready = true; break; }
        }
        if (!ready) { stableTicks = 0; return; }
        if (++stableTicks < 4) return;   // ~1.6 s after the main frame shows -> pack() done

        UIDefaults d = UIManager.getDefaults();
        List<Object> keys = new ArrayList<>(d.keySet()); // snapshot: we mutate while iterating
        for (Object key : keys) {
            Object val = d.get(key);
            if (!(val instanceof Color)) continue;
            String ks = key.toString().toLowerCase();
            // Selection backgrounds -> the visible lighter grey (no colour accent).
            if (ks.contains("selectionbackground")) {
                d.put(key, withAlpha(SEL, ((Color) val).getAlpha()));
                continue;
            }
            // Foreground / text greys must stay readable: de-blue them but NEVER darken
            // (the darken band would otherwise pull disabled/secondary greys onto the
            // background colour and make them invisible).
            boolean isText = ks.contains("foreground") || ks.contains("text")
                    || ks.contains("caret") || ks.contains("accelerator");
            Color rep = remap((Color) val, isText);
            if (rep != null) d.put(key, rep);
        }
        d.put("Component.accentColor", SEL);   // FlatLaf derives focus/selection from this
        d.put("TableHeader.background", HEADER);
        // Standard Swing JTrees (in some JD dialogs) draw dark [+]/[-] / chevrons that
        // vanish on #161616 — light them. (JD's download package toggle is NOT a Swing
        // tree; it loads theme icons tree_plus/tree_minus, shipped light in the iconset.)
        d.put("Tree.collapsedIcon", boxIcon(true));    // [+]
        d.put("Tree.expandedIcon", boxIcon(false));    // [-]
        for (String k : new String[] {
                "Tree.icon.expandedColor", "Tree.icon.collapsedColor",
                "Tree.icon.leafColor", "Tree.icon.closedColor", "Tree.icon.openColor" }) {
            d.put(k, new ColorUIResource(0xb0, 0xb0, 0xb0));
        }
        // Progress bars (download list + account traffic) are FlatLaf JProgressBars
        // (AppWork RendererProgressBar; ExtProgressColumn sets no colours), so these
        // UIManager keys win. The blue->grey sweep had turned the fill light; force a
        // dark track + medium-grey fill + white % text so it is neither washed-out nor
        // white-on-white.
        d.put("ProgressBar.background",          new ColorUIResource(0x26, 0x26, 0x26)); // track
        d.put("ProgressBar.foreground",          new ColorUIResource(0x4d, 0x4d, 0x4d)); // fill
        d.put("ProgressBar.selectionForeground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over fill
        d.put("ProgressBar.selectionBackground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over track
        chromeDone = true;   // set before the refresh so a throw can never cause a retry storm

        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;   // never refresh a window JD is still building
            try { SwingUtilities.updateComponentTreeUI(w); } catch (Exception ignore) { }
        }
        System.out.println("[jd-dialog-agent] enforced #161616 dark chrome");
    }

    /**
     * Map a FlatLaf default colour onto the Carbon greyscale.
     *   - blue accent          -> neutral grey of the same brightness (hue removed,
     *                             light/dark relationship preserved)
     *   - neutral chrome grey   -> darkened onto the #161616 scale (backgrounds/borders
     *                             only; skipped when isText so text stays readable)
     *   - everything else (light text, red/amber error colours, green) -> unchanged
     * Returns null to leave the colour as-is. Alpha is preserved.
     */
    private static Color remap(Color c, boolean isText) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue(), a = c.getAlpha();
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int bright = (r + g + b) / 3;

        // FlatLaf's blue accent (focus, selection, sliders, scrollbar thumbs, links) ->
        // a fixed DARK grey. Mapping by brightness produced a light grey (~#737373) that
        // showed up "light grey everywhere"; use the selection grey so accents stay dark.
        if (b > r + 24 && b > g + 12 && b > 90) {
            return new ColorUIResource(new Color(SEL.getRed(), SEL.getGreen(), SEL.getBlue(), a));
        }
        // Background / border chrome greys -> darken onto the #161616 scale.
        if (!isText && (max - min) <= 22 && bright >= 26 && bright <= 110) {
            int o = Math.max(0x12, Math.round(bright * 0.40f));
            return new ColorUIResource(new Color(o, o, o, a));
        }
        return null;
    }

    private static ColorUIResource withAlpha(Color c, int a) {
        return new ColorUIResource(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
    }

    /** A light [+]/[-] expand-handle icon. Swing's Tree.expandedIcon/collapsedIcon are
     *  drawn dark and vanish on #161616; JD's ExtTable uses those for package rows. */
    private static javax.swing.Icon boxIcon(final boolean plus) {
        return new javax.swing.plaf.IconUIResource(new javax.swing.Icon() {
            public int getIconWidth()  { return 11; }
            public int getIconHeight() { return 11; }
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                g.setColor(new Color(0xb0, 0xb0, 0xb0));
                g.drawRect(x, y, 10, 10);
                g.drawLine(x + 3, y + 5, x + 7, y + 5);            // horizontal bar
                if (plus) g.drawLine(x + 5, y + 3, x + 5, y + 7);  // vertical -> plus
            }
        });
    }

    // --------------------------------------------------------------- dialogs

    private static boolean clickAllowed(Window w) {
        Long t = CLICKED_AT.get(w);
        return t == null || System.currentTimeMillis() - t > 5000L;
    }

    private static void markClicked(Window w) {
        CLICKED_AT.put(w, Long.valueOf(System.currentTimeMillis()));
    }

    /** First button matching any of the labels, in order of preference. */
    private static JButton findButtonByLabels(Container c, String... labels) {
        for (String label : labels) {
            JButton b = findButtonByLabel(c, label);
            if (b != null) return b;
        }
        return null;
    }

    /** Condense a dialog's text to a single loggable line (whitespace-squashed, capped). */
    private static String condense(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 400 ? t.substring(0, 400) + "…" : t;
    }

    private static void handleDialogs() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;

            String title;
            if (w instanceof Frame) {
                title = nullToEmpty(((Frame) w).getTitle());
            } else if (w instanceof Dialog) {
                title = nullToEmpty(((Dialog) w).getTitle());
            } else {
                continue;
            }

            // "Tray isn't supported!" error (belt-and-suspenders): dispose silently.
            if (title.equalsIgnoreCase("Error") || title.equalsIgnoreCase("Fehler")) {
                String text = collectText(w);
                if (text.contains("Tray isn't supported") || text.contains("Tray wird nicht unterst")) {
                    w.setVisible(false);
                    w.dispose();
                    System.out.println("[jd-dialog-agent] dismissed tray-error dialog");
                    continue;
                }
            }

            // FLATLAF_DARK design install prompt -> OK (installs + registers the design).
            if (title.contains("Design-Update") || title.contains("Design Update")) {
                JButton ok = findButtonByLabels(w, "OK", "Ok");
                if (ok != null && clickAllowed(w)) {
                    ok.doClick();
                    markClicked(w);
                    System.out.println("[jd-dialog-agent] accepted design-update");
                    continue;
                }
            }

            // "Manage extensions" install prompt -> install now.
            if (title.contains("Erweiterungen verwalten") || title.contains("Manage Extensions")) {
                JButton install = findButtonByLabels(w, "Jetzt installieren", "Install now", "Install");
                if (install != null && clickAllowed(w)) {
                    install.doClick();
                    markClicked(w);
                    System.out.println("[jd-dialog-agent] accepted extension install");
                    continue;
                }
            }

            // Look-and-feel changed -> "restart to apply" prompt ("You have changed the
            // look and feel to FlatLaf Dark ..."). Left unanswered it blocks the first
            // start with a WHITE GUI (the LAF is registered but never applied). Matched
            // on the BODY text (locale-tolerant), answered with the restart-AFFIRMING
            // button — plain "OK" often just dismisses without restarting. Deliberate
            // side effect: a user changing the LAF manually also gets the JD restart
            // (which is what "apply" needs anyway).
            if (w instanceof Dialog) {
                String body = collectText(w).toLowerCase();
                if (body.contains("look and feel") || body.contains("look-and-feel")
                        || body.contains("flatlaf")) {
                    JButton confirm = findButtonByLabels(w,
                            "Yes", "Ja", "Restart", "Neustart", "Restart now",
                            "Jetzt neu starten", "OK", "Ok");
                    if (confirm != null && clickAllowed(w)) {
                        confirm.doClick();
                        markClicked(w);
                        System.out.println("[jd-dialog-agent] confirmed look-and-feel restart dialog"
                                + " (title=\"" + title + "\", button=\"" + confirm.getText() + "\")");
                        continue;
                    }
                    if (confirm == null && RESTART_REQUESTED.add(w)) {
                        // No recognisable button — ask the launcher for a polite restart
                        // (the LAF is already recorded in JD's config, a restart applies it).
                        writeFile(RESTART_REQUEST, "laf-dialog-without-known-button");
                        System.out.println("[jd-dialog-agent] LAF dialog has no known button — "
                                + "requested a container-side JD restart. title=\"" + title
                                + "\" text=\"" + condense(collectText(w)) + "\"");
                        continue;
                    }
                    continue;
                }

                // Unmatched dialog: log it ONCE with its verbatim title + text, so the
                // next "new forced dialog" bug report carries the exact strings we need
                // to match it — instead of guessing from a user's paraphrase.
                if (LOGGED.add(w)) {
                    System.out.println("[jd-dialog-agent] unmatched dialog: title=\"" + title
                            + "\" text=\"" + condense(collectText(w)) + "\"");
                }
            }
        }
    }

    private static String collectText(Container c) {
        StringBuilder sb = new StringBuilder();
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                sb.append(nullToEmpty(((JLabel) child).getText())).append(' ');
            } else if (child instanceof JTextComponent) {
                sb.append(nullToEmpty(((JTextComponent) child).getText())).append(' ');
            } else if (child instanceof AbstractButton) {
                sb.append(nullToEmpty(((AbstractButton) child).getText())).append(' ');
            }
            if (child instanceof Container) {
                sb.append(collectText((Container) child));
            }
        }
        return sb.toString();
    }

    private static JButton findButtonByLabel(Container c, String label) {
        for (Component child : c.getComponents()) {
            if (child instanceof JButton) {
                JButton b = (JButton) child;
                if (label.equalsIgnoreCase(nullToEmpty(b.getText()).trim())) {
                    return b;
                }
            }
            if (child instanceof Container) {
                JButton b = findButtonByLabel((Container) child, label);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
