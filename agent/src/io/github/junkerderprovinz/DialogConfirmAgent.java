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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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

    // --- v3 theming: FlatLaf custom-defaults source instead of patching JD's jar ----
    // Our colour overrides live in /opt/JDownloader/flatlaf-defaults/ and are hooked in
    // via FlatLaf's OFFICIAL API (registerCustomDefaultsSource). JD's flatlaf.jar stays
    // stock, so its integrity check never complains and a self-update cannot reset the
    // chrome theme. If registration wins the race against JD's setLookAndFeel, the first
    // frame is already themed; otherwise applyCustomDefaults() does exactly ONE polite
    // LAF re-apply once the main window is stable (the same once-after-stable pattern
    // enforceDarkChrome() has used safely for months). The legacy UIManager remap in
    // enforceDarkChrome() still runs afterwards as polish + fallback.
    private static final java.io.File DEFAULTS_DIR = new java.io.File("/opt/JDownloader/flatlaf-defaults");
    private static Instrumentation INSTRUMENTATION;
    private static boolean defaultsRegistered = false;
    private static boolean lafRefreshDone     = false;
    private static int     lafStableTicks     = 0;
    private static int     registrationWait   = 0;
    private static int     classScanTicks     = 0;

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
        INSTRUMENTATION = inst;
        exposeFlatlafToSystemLoader();
        // Put the light package-expander icons back BEFORE JD's GUI resolves them
        // (premain runs before JD's main()); the tick loop keeps them in place.
        restoreExpanderIcons();
        writeFile(PID_FILE, Long.toString(ProcessHandle.current().pid()));
        Thread t = new Thread(DialogConfirmAgent::watch, "jd-dialog-agent");
        t.setDaemon(true);
        t.start();
    }

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
            new java.io.File("/opt/JDownloader/themes-default/flat/org/jdownloader/images");
    private static final java.io.File EXPANDER_DST_DIR =
            new java.io.File("/config/JDownloader/themes/flat/org/jdownloader/images");
    // tree_plus/tree_minus: JD doesn't ship them (see above). exttable/lockColumn +
    // widthLocked: JD DOES ship them but black (fill #000000), invisible on the dark
    // header; the image carries light #b0b0b0 versions in themes-default, but JD's
    // runtime re-provision drops the light copy back to black — same race, same heal.
    private static final String[] EXPANDER_ICONS = {
        "tree_plus.svg", "tree_minus.svg",
        "exttable/lockColumn.svg", "exttable/widthLocked.svg",
    };

    private static void restoreExpanderIcons() {
        for (String name : EXPANDER_ICONS) {
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

    // --------------------------------------------- flatlaf on the system classpath

    /**
     * JD's launcher hosts JD in-process and wires libs/laf/flatlaf.jar only into its
     * own JDLauncherClassLoader (addURL). But UIManager.setLookAndFeel(String) loads
     * the LAF class via SwingUtilities.loadSystemClass, which resolved against the
     * APP classloader here (CI probe 29295757806 stack trace) -> permanent
     * ClassNotFoundException: com.formdev.flatlaf.FlatDarkLaf -> light GUI, even
     * with a perfectly valid, launcher-wired jar. Fix at the sanctioned agent API:
     * append the jar to the SYSTEM classloader search so the by-name load succeeds
     * no matter which context classloader the EDT carries. The launcher loader is
     * parent-first, so JD code resolves the same single copy - no split classes.
     * Retried each tick until the jar exists (fresh installs write it later).
     */
    private static final java.io.File FLATLAF_JAR =
            new java.io.File("/config/JDownloader/libs/laf/flatlaf.jar");
    private static boolean flatlafExposed = false;

    private static void exposeFlatlafToSystemLoader() {
        if (flatlafExposed || INSTRUMENTATION == null || !FLATLAF_JAR.isFile()) return;
        try {
            // the JarFile constructor validates the zip; a truncated install throws
            // and we simply retry on a later tick (the container boot heal replaces it)
            INSTRUMENTATION.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(FLATLAF_JAR));
            flatlafExposed = true;
            System.out.println("[jd-dialog-agent] appended flatlaf.jar to the system classloader (LAF-by-name can resolve now)");
        } catch (Throwable ignore) { }
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
        exposeFlatlafToSystemLoader();
        restoreExpanderIcons();
        handleDialogs();
        registerDefaultsSource();
        applyCustomDefaults();
        enforceDarkChrome();
        retintProgressBars();
        widenSpeedEditors();
        growSpeedMeter();
        replaceSpeedGraph();
        if (isHighlighter()) {          // jd-highlighter-only polish (JD-hardcoded / accent bits)
            styleSidebar();
            stripSectionUnderlines();
            recolorMainTabs();
            monoChromeIcons();
            cardSettingsSections();
            borderlessConfigTables();
            unifyConfigFields();
            recolorDialogs();
            dimModalBackdrops();
        }
        if (++lafTick >= 12) {   // every ~5s (ticks run every 400ms)
            lafTick = 0;
            writeLafMarker();
            if (GEO_DEBUG) dumpGeometry();
            if (isHighlighter()) { logHlPolish(); logIconInventory(); logConfigFields(); }   // polish + icon names + config fields
        }
    }

    // Opt-in geometry logging (JD_DEBUG_GEO=1). Off by default so a box test / the
    // forum reporter get clean logs; flip it on to re-diagnose layout regressions.
    private static final boolean GEO_DEBUG =
            "1".equals(System.getenv("JD_DEBUG_GEO"))
            || "true".equalsIgnoreCase(System.getenv("JD_DEBUG_GEO"));

    // ---------------------------------------------------------- geometry probe

    /**
     * Diagnostic ground truth for the persistent half-height-graph reports: every
     * ~5s dump the real pixel geometry around the speed graph to stdout, so
     * `docker logs` shows what the layout ACTUALLY did instead of what we assume.
     * One compact line per window that contains a SpeedMeterPanel.
     */
    private static void dumpGeometry() {
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                JComponent nat = findSpeedMeter(w);
                if (nat == null) continue;
                StringBuilder sb = new StringBuilder("[jd-dialog-agent] GEO win=");
                sb.append(w.getClass().getSimpleName()).append(b(w));
                java.awt.Insets in = w.getInsets();
                sb.append(" insets=").append(in.top).append('/').append(in.left)
                  .append('/').append(in.bottom).append('/').append(in.right);
                if (w instanceof Frame) sb.append(" undec=").append(((Frame) w).isUndecorated());
                try {
                    Object mb = w.getClass().getMethod("getJMenuBar").invoke(w);
                    if (mb instanceof Component) sb.append(" menubar=").append(b((Component) mb));
                } catch (Exception ignore) { }
                Container tb = nat.getParent();
                List<String> chain = new ArrayList<>();
                for (Container p = tb; p != null && p != w; p = p.getParent()) {
                    chain.add(p.getClass().getSimpleName() + b(p));
                }
                Collections.reverse(chain);
                sb.append(" chain=").append(String.join(">", chain));
                LayoutManager lm = (tb == null) ? null : tb.getLayout();
                if (lm != null && lm.getClass().getName().contains("MigLayout")) {
                    sb.append(" lm@").append(Integer.toHexString(System.identityHashCode(lm)))
                      .append(" grown=").append(GROWN_LAYOUTS.contains(lm));
                    try {
                        sb.append(" rows=").append(lm.getClass().getMethod("getRowConstraints").invoke(lm));
                    } catch (Exception e) { sb.append(" rows=?"); }
                } else if (lm != null) {
                    sb.append(" lm=").append(lm.getClass().getSimpleName());
                }
                if (tb instanceof JComponent) {
                    Dimension p = tb.getPreferredSize();
                    sb.append(" tbPref=").append(p.width).append('x').append(p.height);
                }
                Container cp = (tb == null) ? null : tb.getParent();
                LayoutManager plm = (cp == null) ? null : cp.getLayout();
                if (plm != null && plm.getClass().getName().contains("MigLayout")) {
                    try {
                        Object cc = plm.getClass()
                                .getMethod("getComponentConstraints", Component.class)
                                .invoke(plm, tb);
                        sb.append(" tbCC=\"").append(cc).append('"');
                    } catch (Exception e) { sb.append(" tbCC=?"); }
                } else if (plm != null) {
                    sb.append(" cpLm=").append(plm.getClass().getSimpleName());
                }
                sb.append(" native[vis=").append(nat.isVisible()).append(' ').append(b(nat)).append(']');
                if (ownGraph == null) {
                    sb.append(" own=null");
                } else {
                    sb.append(" own[parent=").append(ownGraph.getParent() == tb ? "toolbar"
                              : String.valueOf(ownGraph.getParent()))
                      .append(" showing=").append(ownGraph.isShowing())
                      .append(' ').append(b(ownGraph)).append(']');
                }
                System.out.println(sb);
            }
        } catch (Exception ignore) { }
    }

    private static String b(Component c) {
        java.awt.Rectangle r = c.getBounds();
        return "(" + r.x + "," + r.y + " " + r.width + "x" + r.height + ")";
    }

    private static JComponent findSpeedMeter(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && ch.getClass().getName().endsWith(".SpeedMeterPanel")) {
                return (JComponent) ch;
            }
            if (ch instanceof Container) {
                JComponent r = findSpeedMeter((Container) ch);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------ v3 custom defaults source

    /** True once JD's MAIN window (not a splash) is showing. */
    private static boolean mainWindowShowing() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400) return true;
        }
        return false;
    }

    /**
     * Register /opt/JDownloader/flatlaf-defaults as a FlatLaf custom-defaults source —
     * through the classloader that actually loaded FlatLaf (JD's, not ours). Tried as
     * soon as the FlatLaf class exists in the JVM: if that beats JD's setLookAndFeel,
     * the very first frame renders with our colours and no re-apply is needed.
     */
    private static void registerDefaultsSource() {
        if (defaultsRegistered || !DEFAULTS_DIR.isDirectory()) return;
        Class<?> flatLaf = null;
        // Shortcut: an active FlatLaf LAF hands us the right classloader directly.
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf != null && laf.getClass().getName().toLowerCase().contains("flat")) {
            try {
                flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            } catch (Throwable ignore) { }
        }
        // Pre-LAF: scan loaded classes (every 4th tick, first ~5 min only — FlatLaf
        // loads within seconds of JD's GUI bootstrap when it is installed; the throttle
        // caps the EDT cost of getAllLoadedClasses() in the no-FlatLaf degenerate case).
        if (flatLaf == null && INSTRUMENTATION != null && classScanTicks < 750) {
            if ((++classScanTicks % 4) != 0) return;
            for (Class<?> c : INSTRUMENTATION.getAllLoadedClasses()) {
                if ("com.formdev.flatlaf.FlatLaf".equals(c.getName())) { flatLaf = c; break; }
            }
        }
        if (flatLaf == null) return;
        try {
            flatLaf.getMethod("registerCustomDefaultsSource", java.io.File.class)
                   .invoke(null, DEFAULTS_DIR);
            defaultsRegistered = true;
            String ver = flatLaf.getPackage() != null
                    ? flatLaf.getPackage().getImplementationVersion() : null;
            System.out.println("[jd-dialog-agent] registered custom defaults source "
                    + DEFAULTS_DIR + " (FlatLaf " + (ver != null ? ver : "?") + ")");
        } catch (Throwable e) {
            // API missing (ancient/renamed FlatLaf)? Give up cleanly — the legacy
            // UIManager remap in enforceDarkChrome() still delivers a dark chrome.
            defaultsRegistered = true;
            lafRefreshDone     = true;
            System.out.println("[jd-dialog-agent] registerCustomDefaultsSource unavailable ("
                    + e.getClass().getSimpleName() + ") — legacy chrome remap only");
        }
    }

    /**
     * Make the registered defaults take effect exactly once. If registration won the
     * race against JD's setLookAndFeel, the sentinel (Panel.background == #161616)
     * already matches and nothing needs to happen. Otherwise re-apply the CURRENT LAF
     * once (fresh instance -> getDefaults() re-reads the custom source) and refresh all
     * windows — only after the main window is stable, the exact gate that has kept
     * enforceDarkChrome() clear of the CircleProgressBarUI boot-loop for months.
     */
    private static void applyCustomDefaults() {
        if (lafRefreshDone) return;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        if (defaultsRegistered) {
            Color bg = UIManager.getColor("Panel.background");
            if (bg != null && (bg.getRGB() & 0xFFFFFF) == 0x161616) {
                // Registration beat JD's LAF apply — defaults are already live.
                lafRefreshDone = true;
                System.out.println("[jd-dialog-agent] custom defaults active from first paint (no re-apply needed)");
                return;
            }

            // PREFERRED path: JD applies its LAF seconds BEFORE it builds the GUI.
            // While no frame exists yet, re-applying the LAF is a pure defaults swap —
            // there is no live component tree to update (updateUI deliberately NOT
            // called), so nothing can be corrupted, and every component JD builds
            // next is created with our colours from the start. Hot-swapping the LAF
            // on the LIVE frame instead (the old one-shot) broke JD's repaint: JD
            // itself never swaps a LAF at runtime — it always restarts — because its
            // AppWork components don't survive updateUI cleanly (ghosted/overlapping
            // panels when switching tabs).
            if (Frame.getFrames().length == 0) {
                try {
                    UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
                    System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                            + " with custom defaults (pre-GUI, no components yet)");
                } catch (Throwable e) {
                    System.out.println("[jd-dialog-agent] pre-GUI LAF re-apply failed ("
                            + e.getClass().getSimpleName() + ") — legacy chrome remap only");
                }
                lafRefreshDone = true;
                return;
            }
        }

        // Frames already exist (or registration is still pending) — gate everything
        // below on the main window being shown and stable.
        if (!mainWindowShowing()) { lafStableTicks = 0; return; }
        if (++lafStableTicks < 4) return;   // ~1.6 s after the main frame shows

        if (!defaultsRegistered) {
            // Registration hasn't happened yet (class scan still looking) — wait up to
            // ~30 s of stable GUI, then fall back to the legacy remap alone.
            if (++registrationWait < 75) return;
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] defaults source never registered — legacy chrome remap only");
            return;
        }

        // RARE fallback: the pre-GUI window was missed. A live re-apply must refresh
        // the existing tree (updateUI) and can leave repaint artifacts in JD's custom
        // components — logged loudly so field reports identify this path.
        try {
            UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
            Class<?> flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            flatLaf.getMethod("updateUI").invoke(null);
            System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                    + " with custom defaults (LIVE one-shot — missed the pre-GUI window)");
        } catch (Throwable e) {
            System.out.println("[jd-dialog-agent] LAF re-apply failed ("
                    + e.getClass().getSimpleName() + ") — legacy chrome remap only");
        }
        lafRefreshDone = true;   // one attempt, never a loop — remap polish runs next
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
                hideNativeInLayout(parent, nativePanel);
                nativePanel.setVisible(false);
            }
            if (!ourPresent) {
                // a JD updateToolbar() rebuild re-added the (still hidden) native panel
                // WITHOUT hidemode 3: at default hidemode 0 an invisible component still
                // reserves its up-to-300px cell and squeezes our graph aside. ourPresent
                // is false exactly once per rebuild (removeAll dropped us), so re-apply
                // the exclusion here before adding our graph back.
                hideNativeInLayout(parent, nativePanel);
                parent.add(ownGraph, "width 32:300:300,pushy,growy");
                parent.revalidate();
                parent.repaint();
                System.out.println("[jd-dialog-agent] replaced the speed graph (native math overflows above ~34 MiB/s)");
            }
        } catch (Exception ignore) { }
    }

    /**
     * Exclude the hidden native panel from the layout (hidemode 3) while keeping it
     * alive for its fetcher thread, localized strings and the speed-limit menu.
     */
    private static void hideNativeInLayout(Container parent, JComponent nativePanel) {
        LayoutManager lm = parent.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method m = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            m.invoke(lm, nativePanel, "width 32:300:300,pushy,growy,hidemode 3");
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
     * "pushy,growy" and follows, the 32px tool buttons stay centered (the toolbar is
     * docked NORTH, so the frame grants it its preferred height - no clipping).
     *
     * Guard granularity matters: JD's updateToolbar() rebuild does removeAll() and
     * installs a brand-NEW MigLayout instance hardcoded back to "[grow,32!]", so a
     * per-component guard (client property) blocks forever after the first rebuild,
     * while re-applying on a height heuristic fights JD's layout every tick. Grow
     * exactly ONCE PER LayoutManager INSTANCE instead: each rebuild's fresh MigLayout
     * gets grown once, an already-grown instance is left alone.
     */
    private static final int SPEEDMETER_ROW_PX = 64;
    private static final Set<LayoutManager> GROWN_LAYOUTS =
            Collections.newSetFromMap(new WeakHashMap<LayoutManager, Boolean>());

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
        LayoutManager lm = tb.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        if (GROWN_LAYOUTS.add(lm)) {
            try {
                Method m = lm.getClass().getMethod("setRowConstraints", Object.class);
                m.invoke(lm, "[grow," + SPEEDMETER_ROW_PX + "!]");
                tb.revalidate();
                tb.repaint();
                System.out.println("[jd-dialog-agent] grew the speed graph row to " + SPEEDMETER_ROW_PX + "px");
            } catch (Exception ignore) {
                // setRowConstraints absent -> leave the toolbar as-is; the marker stays
                // so the same broken instance isn't retried every 400ms tick
            }
        }
        pinToolbarHeight(tb);
    }

    /**
     * Growing the toolbar's OWN row is not enough: the CI geometry probe showed the
     * content pane keeps granting the toolbar its pre-grow strip (row=[grow,64!],
     * tbPref=68, but MainToolBar bounds stuck at 36px -> the graph's bottom half is
     * clipped). JD adds the toolbar to the frame with "dock NORTH" (a MigLayout dock
     * whose measurement does not follow the child's later growth), so pin the height
     * explicitly in the PARENT's component constraint for the toolbar. Idempotent:
     * skipped once the current constraint already carries our height pin.
     */
    private static void pinToolbarHeight(JComponent tb) {
        Container cp = tb.getParent();
        LayoutManager plm = (cp == null) ? null : cp.getLayout();
        if (plm == null || !plm.getClass().getName().contains("MigLayout")) return;
        try {
            Method gc = plm.getClass().getMethod("getComponentConstraints", Component.class);
            Object cur = gc.invoke(plm, tb);
            String cc = (cur == null) ? "" : cur.toString();
            if (cc.contains("height ")) return;   // already pinned
            int ph = tb.getPreferredSize().height; // toolbar's own grown row + gaps
            if (ph < SPEEDMETER_ROW_PX) ph = SPEEDMETER_ROW_PX;
            String pinned = (cc.isEmpty() ? "" : cc + ",") + "height " + ph + "!";
            Method sc = plm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            sc.invoke(plm, tb, pinned);
            Window win = SwingUtilities.getWindowAncestor(tb);
            if (win != null) { win.invalidate(); win.validate(); win.repaint(); }
            System.out.println("[jd-dialog-agent] pinned the toolbar strip to " + ph
                    + "px in the content pane (was: \"" + cc + "\")");
        } catch (Exception ignore) {
            // parent constraint not reachable -> the row grow alone has to do
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

    // ------------------------------------------------------ borderless config tables (round 14)

    private static final java.util.Set<Integer> LOGGED_TABLES = new java.util.HashSet<>();

    /**
     * The AppWork ExtTables inside Settings config panels ignore FlatLaf's Table.* / TableHeader.*
     * keys (which only theme the LAF's own JTables and JD's download/linkgrabber link tables via
     * colorfor*). They paint their own lighter-grey header band, column separators and an outer
     * frame, and the scroll viewport sits as a darker inset inside the card — the "still many
     * lines" that the .properties never reached. Flatten each config-panel table at the instance
     * level: kill the grid, blend the header into the card colour, drop the scrollpane frame, and
     * accent the boolean checkmarks. A one-time diagnostic dumps the real runtime structure so any
     * bit that resists (e.g. a hard-coded header-renderer colour) shows up in the boot log.
     */
    private static void borderlessConfigTables() {
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
     * Flat dark header renderer. Diag showed the config table's columns use a per-column
     * ExtTableHeaderRenderer that paints its own grey band regardless of the header background, so
     * we replace it. This runs in the render path (getTableCellRendererComponent per paint), so JD's
     * live repaints can't override it (a tick-based header.setBackground did — CI caught it, live not).
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

    private static void flattenConfigTable(JTable t) {
        try {
            t.setShowGrid(false);
            t.setIntercellSpacing(new Dimension(0, 0));
            // UrlOrderTable forces showHorizontalLines=true; blend the line into the card so it vanishes.
            if (!DIALOG_BG.equals(t.getGridColor())) t.setGridColor(DIALOG_BG);

            javax.swing.table.JTableHeader h = t.getTableHeader();
            if (h != null) {
                if (!DIALOG_BG.equals(h.getBackground())) h.setBackground(DIALOG_BG);
                h.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                javax.swing.table.TableCellRenderer dr = h.getDefaultRenderer();
                if (dr instanceof JComponent) {
                    ((JComponent) dr).setBackground(DIALOG_BG);
                    ((JComponent) dr).setBorder(javax.swing.BorderFactory.createEmptyBorder());
                }
            }
            // Replace the per-column ExtTableHeaderRenderer with the flat dark one.
            javax.swing.table.TableColumnModel hcm = t.getColumnModel();
            for (int i = 0; i < hcm.getColumnCount(); i++) {
                javax.swing.table.TableColumn col = hcm.getColumn(i);
                if (col.getHeaderRenderer() != DARK_HEADER) col.setHeaderRenderer(DARK_HEADER);
            }
            Container sp = SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, t);
            if (sp instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane s = (javax.swing.JScrollPane) sp;
                s.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                s.setViewportBorder(javax.swing.BorderFactory.createEmptyBorder());
            }
            accentTableCheckmarks(t);
            logTableStructureOnce(t, h, sp);
        } catch (Throwable ignore) { }
    }

    /** Best-effort accent on boolean-column checkmarks (+ the diagnostic reveals the real renderer). */
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

    private static void logTableStructureOnce(JTable t, javax.swing.table.JTableHeader h, Container sp) {
        try {
            int id = System.identityHashCode(t);
            synchronized (LOGGED_TABLES) { if (!LOGGED_TABLES.add(id)) return; }
            StringBuilder sb = new StringBuilder("[jd-dialog-agent] HL table-diag: ");
            sb.append(t.getClass().getName())
              .append(" grid=").append(t.getShowHorizontalLines()).append("/").append(t.getShowVerticalLines());
            if (h != null) {
                sb.append(" hdr=").append(h.getClass().getSimpleName())
                  .append(" hdrBg=").append(h.getBackground())
                  .append(" hdrDef=").append(h.getDefaultRenderer() == null ? "-" : h.getDefaultRenderer().getClass().getSimpleName());
            }
            if (sp instanceof javax.swing.JScrollPane)
                sb.append(" spBorder=").append(String.valueOf(((javax.swing.JScrollPane) sp).getBorder()));
            javax.swing.table.TableColumnModel cm = t.getColumnModel();
            for (int i = 0; i < Math.min(cm.getColumnCount(), 6); i++) {
                javax.swing.table.TableColumn col = cm.getColumn(i);
                javax.swing.table.TableCellRenderer cr = col.getCellRenderer();
                javax.swing.table.TableCellRenderer hr = col.getHeaderRenderer();
                sb.append(" c").append(i).append("=").append(cr == null ? "-" : cr.getClass().getSimpleName());
                if (hr != null) sb.append("/h:").append(hr.getClass().getSimpleName());
            }
            javax.swing.table.TableCellRenderer hdrDef = (h != null) ? h.getDefaultRenderer() : null;
            if (hdrDef != null) sb.append(" hdrDefRend=").append(hdrDef.getClass().getName());
            writeDiag(sb.toString());   // System.out is swallowed after JD's redirect; use the file channel
        } catch (Throwable ignore) { }
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
        if (!lafRefreshDone) return;   // ORDER: run only after the one-shot LAF re-apply
                                        // (a later setLookAndFeel would wipe this remap)
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
            // jd-highlighter EXCEPTION: keep the theme's accent Menu/MenuBar/MenuItem
            // selection — else an OPENED menu goes grey (its selectionForeground stays
            // dark -> unreadable dark-on-grey). ks.contains("menu") also covers
            // CheckBoxMenuItem / RadioButtonMenuItem.selectionBackground.
            if (ks.contains("selectionbackground")) {
                if (!(isHighlighter() && ks.contains("menu")))
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
        // jd-highlighter keeps its accent (focus/selection) and its own #1e1e1e header;
        // only plain Dark greys the FlatLaf accent + darkens the header here.
        if (!isHighlighter()) {
            d.put("Component.accentColor", SEL);   // FlatLaf derives focus/selection from this
            d.put("TableHeader.background", HEADER);
        }
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

    // ----------------------------------------------- jd-highlighter-only polish
    // Two things the user wants that JD HARDCODES, so no theme key can reach them:
    // the Settings sidebar's fixed 35px row height and the <u> underline baked into
    // every Settings section title. Gated to jd-highlighter — the presence of the
    // RENDERED FlatDarkLaf.properties (written by jdownloader-theme.sh only for
    // JD_THEME=jd-highlighter) marks the theme — so plain Dark stays faithful to
    // stock JD. Both are best-effort: any failure leaves JD exactly as it was.

    private static final java.io.File HL_MARKER =
            new java.io.File(DEFAULTS_DIR, "FlatDarkLaf.properties");

    private static boolean isHighlighter() { return HL_MARKER.isFile(); }

    // The user's accent, read once from the rendered FlatLaf defaults (jdownloader-theme.sh
    // writes "@accentColor = #rrggbb" into HL_MARKER). NOT from UIManager: enforceDarkChrome
    // greys a blue accent in the defaults. Falls back to JD_ACCENT env, then electric yellow.
    private static Color   accentCache = null;
    private static boolean accentRead  = false;
    private static Color accentColor() {
        if (accentRead) return accentCache;
        String hex = null;
        try {
            for (String line : java.nio.file.Files.readAllLines(HL_MARKER.toPath())) {
                String s = line.trim();
                if (s.startsWith("@accentColor")) {
                    int eq = s.indexOf('=');
                    if (eq > 0) { hex = s.substring(eq + 1).trim(); break; }
                }
            }
        } catch (Throwable ignore) { }
        if (hex == null || hex.isEmpty()) {
            String env = System.getenv("JD_ACCENT");
            hex = (env != null && !env.isEmpty()) ? env : "#ffee00";
        }
        if (!hex.startsWith("#")) hex = "#" + hex;
        try { accentCache = Color.decode(hex); }
        catch (Throwable e) { accentCache = new Color(0xff, 0xee, 0x00); }
        accentRead = true;
        return accentCache;
    }

    /** Dark-on-accent text colour (matches the tabs' selected foreground). */
    private static Color accentFg() {
        Color sel = UIManager.getColor("TabbedPane.selectedForeground"); // = @@ACCENT_FG@@ at render time
        if (sel != null) return new Color(sel.getRGB());
        Color a = accentColor();
        double lum = 0.299 * a.getRed() + 0.587 * a.getGreen() + 0.114 * a.getBlue();
        return lum > 140 ? new Color(0x16, 0x16, 0x16) : new Color(0xf4, 0xf4, 0xf4);
    }

    private static javax.swing.ListCellRenderer<?> asRenderer(Object o) {
        return (o instanceof javax.swing.ListCellRenderer) ? (javax.swing.ListCellRenderer<?>) o : null;
    }

    private static final int SIDEBAR_ROW_PX = 66;   // native is ~53 in this JD build; must exceed it
    private static final int SIDEBAR_TOP_PAD = 6;   // top inset on the RenderLabel to center it in the row
    private static final Color SIDEBAR_TEXT = new Color(0xf4, 0xf4, 0xf4);  // normal sidebar label colour
    private static boolean SIDEBAR_DIAG_DONE = false;   // one-time renderer dump (round 14)

    /**
     * Append a diagnostic line to /config/hl-diag.txt. Tick-time System.out is swallowed once JD
     * redirects it (the round-14 diag never reached docker logs OR JD's log files), so a file is
     * the only reliable channel from tick code back to a CI probe (which cats it). Best-effort.
     */
    private static void writeDiag(String line) {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("/config/hl-diag.txt"),
                    ("[hl-diag] " + line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignore) { }
    }

    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> TINT_LIGHT = new java.util.WeakHashMap<>();
    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> TINT_DARK  = new java.util.WeakHashMap<>();
    /**
     * A single-tone silhouette of an icon (every opaque pixel -> tint, alpha kept), cached by source
     * icon + tone. In the render path (the sidebar cell wrapper) this forces JD's colourful icons to
     * render MONO regardless of JD's icon cache — the file-level SVG recolour can never win because JD
     * reads its icons once at install and caches the Icon objects. Light tone = the normal mono glyph;
     * dark tone (accentFg) = the flip on the accent hover row so it stays visible on the light accent.
     */
    private static javax.swing.Icon tintIcon(javax.swing.Icon orig, Color tint, Component c) {
        try {
            if (tint == null) return orig;
            java.util.Map<javax.swing.Icon, javax.swing.Icon> cache =
                    (tint.getRGB() == SIDEBAR_TEXT.getRGB()) ? TINT_LIGHT : TINT_DARK;
            synchronized (cache) {
                javax.swing.Icon cached = cache.get(orig);
                if (cached != null) return cached;
            }
            int w = orig.getIconWidth(), h = orig.getIconHeight();
            if (w <= 0 || h <= 0) return orig;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            orig.paintIcon(c, g, 0, 0);
            g.dispose();
            int rgb = (tint.getRed() << 16) | (tint.getGreen() << 8) | tint.getBlue();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = argb >>> 24;
                    if (a != 0) img.setRGB(x, y, (a << 24) | rgb);
                }
            }
            javax.swing.Icon ti = new javax.swing.ImageIcon(img);
            synchronized (cache) { cache.put(orig, ti); }
            return ti;
        } catch (Throwable t) { return orig; }
    }

    // ---- Tabler chrome-icon replacement (render path) ------------------------------------------
    // The theme swaps JD's built-in chrome glyphs for a single clean line-art set (Tabler Icons,
    // MIT). JD icons expose their name via getIdentifier().getKey() (proven round 30: works for
    // both org.jdownloader.images.AbstractIcon and IdentifierWrapperIcon; the plain getKey() is
    // absent on wrapper icons, so ALWAYS go through getIdentifier().getKey()). The matching Tabler
    // PNG is shipped in the image at /opt/jd-tabler/png/<key>-<size>.png (white #f4f4f4 line art at
    // 16/18/20/24/32). We load the nearest size, then reuse tintIcon() so the light tone is exact and
    // the accent hover flip works unchanged. Icons with no name (HighDPIIcon, bare ImageIcon) or no
    // mapping fall back to a mono tint of JD's own glyph — nothing ever renders colourful.
    private static final String TABLER_DIR = "/opt/jd-tabler/png";
    private static final int[] TABLER_SIZES = { 16, 18, 20, 24, 32 };
    private static final Object TABLER_NONE = new Object();
    private static final java.util.Map<String, Object> TABLER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // Resolved-key cache: iconKey() is called on every sidebar cell paint (and the hover listener
    // repaints the whole list on each mouseMoved), so the reflection must not run per paint. JD's
    // icon objects have stable identity (it caches them), so a WeakHashMap keyed on the icon gives an
    // O(1) hit after the first resolve. KEY_NONE marks icons that carry no name so we never re-reflect.
    private static final java.util.Map<javax.swing.Icon, String> KEY_CACHE = new java.util.WeakHashMap<>();
    private static final String KEY_NONE = " noKey";

    /** The JD icon's short name (e.g. "reconnect", "logo/myjdownloader") or null if it carries none.
     *  Wrapper/merged icons (ExtMergedIcon = base glyph + a state badge, ScaledIcon, ...) report a null
     *  key, so we recurse into their component icons and take the first that resolves — that recovers
     *  the toolbar toggles + any composite chrome glyph. Cached per icon. */
    private static String iconKey(javax.swing.Icon ic) {
        if (ic == null) return null;
        synchronized (KEY_CACHE) {
            String c = KEY_CACHE.get(ic);
            if (c != null) return (c == KEY_NONE) ? null : c;
        }
        String result = resolveKey(ic, 0);
        synchronized (KEY_CACHE) { KEY_CACHE.put(ic, result == null ? KEY_NONE : result); }
        return result;
    }

    private static String resolveKey(javax.swing.Icon ic, int depth) {
        if (ic == null || depth > 3) return null;
        try {   // 1. direct: getIdentifier().getKey()
            java.lang.reflect.Method m = ic.getClass().getMethod("getIdentifier");
            try { m.setAccessible(true); } catch (Throwable ignore) { }
            Object id = m.invoke(ic);
            if (id != null) {
                java.lang.reflect.Method gk = id.getClass().getMethod("getKey");
                try { gk.setAccessible(true); } catch (Throwable ignore) { }
                Object k = gk.invoke(id);
                if (k != null) { String s = k.toString(); if (!s.isEmpty() && !"null".equals(s)) return s; }
            }
        } catch (Throwable ignore) { }
        // 2. unwrap: scan instance fields for a component icon (merged/scaled/wrapped) and recurse
        for (Class<?> cl = ic.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
            for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    String k = keyFromValue(f.get(ic), depth);
                    if (k != null) return k;
                } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    private static String keyFromValue(Object v, int depth) {
        if (v instanceof javax.swing.Icon) return resolveKey((javax.swing.Icon) v, depth + 1);
        if (v instanceof Object[]) {
            for (Object o : (Object[]) v) if (o instanceof javax.swing.Icon) {
                String k = resolveKey((javax.swing.Icon) o, depth + 1); if (k != null) return k;
            }
        } else if (v instanceof java.util.Collection) {
            for (Object o : (java.util.Collection<?>) v) if (o instanceof javax.swing.Icon) {
                String k = resolveKey((javax.swing.Icon) o, depth + 1); if (k != null) return k;
            }
        }
        return null;
    }

    /** White Tabler base icon for a JD key at exactly w x h (nearest shipped size, scaled), cached. */
    private static javax.swing.Icon tablerBase(String key, int w, int h) {
        if (key == null || w <= 0 || h <= 0) return null;
        int want = Math.max(w, h);
        String safe = key.replace('/', '_').replace('\\', '_');
        String ck = safe + "@" + w + "x" + h;
        Object cached = TABLER_CACHE.get(ck);
        if (cached == TABLER_NONE) return null;
        if (cached instanceof javax.swing.Icon) return (javax.swing.Icon) cached;
        try {
            int best = TABLER_SIZES[0];
            for (int s : TABLER_SIZES) if (Math.abs(s - want) < Math.abs(best - want)) best = s;
            java.io.File f = new java.io.File(TABLER_DIR, safe + "-" + best + ".png");
            if (!f.isFile()) { TABLER_CACHE.put(ck, TABLER_NONE); return null; }
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(f);
            if (src == null) { TABLER_CACHE.put(ck, TABLER_NONE); return null; }
            javax.swing.Icon icon;
            if (src.getWidth() == w && src.getHeight() == h) {
                icon = new javax.swing.ImageIcon(src);
            } else {
                java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = dst.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, w, h, null);
                g.dispose();
                icon = new javax.swing.ImageIcon(dst);
            }
            TABLER_CACHE.put(ck, icon);
            return icon;
        } catch (Throwable t) { TABLER_CACHE.put(ck, TABLER_NONE); return null; }
    }

    /**
     * The chrome-icon render primitive used at every sweep site: swap JD's glyph for the mapped
     * Tabler icon (tinted to the given tone), or, when there is no name / no mapped asset, mono-tint
     * JD's own glyph. Same signature as tintIcon() so the call sites are a drop-in.
     */
    private static javax.swing.Icon tablerIcon(javax.swing.Icon orig, Color tint, Component c) {
        try {
            javax.swing.Icon base = tablerBase(iconKey(orig), orig.getIconWidth(), orig.getIconHeight());
            if (base != null) return tintIcon(base, tint, c);
        } catch (Throwable ignore) { }
        return tintIcon(orig, tint, c);
    }

    /**
     * Mono the visible chrome icons that the sidebar wrapper does not cover — the toolbar buttons.
     * JD caches its icons, so (like the sidebar) the only thing that renders mono is replacing the
     * Icon on the component itself. Set a light mono icon + a dark rollover, so the glyph stays
     * visible both on the dark toolbar and on the accent hover fill. Tracked via a client property
     * so it is set once and only re-applied if JD resets the icon (never compounds).
     */
    private static void monoChromeIcons() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) monoChromeIn(w, false);
        }
    }

    private static void monoChromeIn(Container c, boolean inConfig) {
        boolean cfg = inConfig || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component child : c.getComponents()) {
            // ALL buttons (JD's toolbar is not a JToolBar, so scoping to JToolBar missed the play
            // button) + section-header labels inside config panels (the coloured section icons).
            if (child instanceof javax.swing.AbstractButton) monoButtonIcon((javax.swing.AbstractButton) child);
            else if (child instanceof javax.swing.JLabel) monoLabelIcon((javax.swing.JLabel) child, cfg);
            if (child instanceof Container) monoChromeIn((Container) child, cfg);
        }
    }

    // Toolbar TOGGLE buttons carry a name-less ExtMergedIcon (base glyph + a state badge), so map the
    // button's Action class (descriptive + not localized) to a JD icon key that HAS a Tabler PNG.
    private static final java.util.Map<String, String> ACTION_ICON = new java.util.HashMap<>();
    static {
        ACTION_ICON.put("PauseDownloadsAction",          "media-playback-pause");
        ACTION_ICON.put("ClipBoardToggleAction",         "clipboard");
        ACTION_ICON.put("AutoReconnectToggleAction",     "reconnect");
        ACTION_ICON.put("GlobalPremiumSwitchToggleAction", "premium");
        ACTION_ICON.put("SilentModeToggleAction",        "silentmode");
    }

    private static void monoButtonIcon(javax.swing.AbstractButton b) {
        try {
            javax.swing.Icon cur = b.getIcon();
            if (cur == null) return;
            if (cur == b.getClientProperty("jdp.monoBtn")) return;   // already our mono icon
            javax.swing.Icon mono = tablerForButton(b, cur, SIDEBAR_TEXT);
            if (mono == cur) return;
            b.setIcon(mono);
            b.putClientProperty("jdp.monoBtn", mono);
            b.setRolloverIcon(tablerForButton(b, cur, accentFg()));  // dark glyph for the accent hover fill
            // Toggle buttons keep a distinct icon per state; swap each. Only runs on the (re)mono pass
            // (the getIcon()==jdp.monoBtn guard returns early otherwise), so no churn.
            // The rollover / selected / pressed states all paint the accent background (via the
            // ToggleButton.* + Button.* hover keys), so their glyphs go DARK to stay readable on it.
            javax.swing.Icon si = b.getSelectedIcon();
            if (si != null && si != mono) b.setSelectedIcon(tablerForButton(b, si, accentFg()));
            javax.swing.Icon rsi = b.getRolloverSelectedIcon();
            if (rsi != null && rsi != mono) b.setRolloverSelectedIcon(tablerForButton(b, rsi, accentFg()));
            javax.swing.Icon pi = b.getPressedIcon();
            if (pi != null && pi != mono) b.setPressedIcon(tablerForButton(b, pi, accentFg()));
            // Leave disabledIcon null so Swing derives a grey version of our mono icon.
            b.setContentAreaFilled(true);   // so FlatLaf's ToggleButton.hoverBackground actually paints
        } catch (Throwable ignore) { }
    }

    /** Like tablerIcon, but for a button: when the icon carries no name (a merged toggle glyph), pick
     *  the Tabler glyph from the button's Action class instead of monoing a rough merged blob. */
    private static javax.swing.Icon tablerForButton(javax.swing.AbstractButton b, javax.swing.Icon icon, Color tone) {
        if (iconKey(icon) == null) {
            javax.swing.Action act = b.getAction();
            if (act != null) {
                String jk = ACTION_ICON.get(act.getClass().getSimpleName());
                if (jk != null) {
                    javax.swing.Icon base = tablerBase(jk, icon.getIconWidth(), icon.getIconHeight());
                    if (base != null) return tintIcon(base, tone, b);
                }
            }
        }
        return tablerIcon(icon, tone, b);
    }

    // Unify the config-panel input backgrounds. The round-32 diagnostic showed JD's AppWork inputs
    // ignore @componentBackground and carry mixed fills (#121212 / #1e1e1e / #161616) — the "mal
    // heller mal dunkler als die card" inconsistency. Force ONE recessed fill for text/combo/spinner
    // and ONE raised fill for real buttons, so a card reads as: field #1a1a1a < card #242424 < button
    // #2a2a2a. Checkboxes/radios keep their transparent fill. Guarded on value so it is a no-op once set.
    private static final Color FIELD_BG   = new Color(0x1a, 0x1a, 0x1a);
    private static final Color BTN_CFG_BG = new Color(0x2a, 0x2a, 0x2a);
    private static void unifyConfigFields() {
        for (Window w : Window.getWindows()) if (w.isShowing()) unifyFieldsIn(w, false);
    }
    private static void unifyFieldsIn(Container c, boolean inCfg) {
        boolean cfg = inCfg || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component ch : c.getComponents()) {
            if (cfg) {
                try {
                    if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                            || ch instanceof javax.swing.JSpinner) {
                        if (!FIELD_BG.equals(ch.getBackground())) {
                            ch.setBackground(FIELD_BG);
                            if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                        }
                    } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                        if (!BTN_CFG_BG.equals(ch.getBackground())) ch.setBackground(BTN_CFG_BG);
                    }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) unifyFieldsIn((Container) ch, cfg);
        }
    }
    private static boolean isCheckLike(Component ch) {
        if (ch instanceof javax.swing.JCheckBox || ch instanceof javax.swing.JRadioButton) return true;
        String n = ch.getClass().getSimpleName().toLowerCase();
        return n.contains("check") || n.contains("radio") || n.contains("toggle");
    }

    private static void monoLabelIcon(javax.swing.JLabel l, boolean cfg) {
        try {
            javax.swing.Icon cur = l.getIcon();
            if (cur == null) return;
            if (cur == l.getClientProperty("jdp.monoLbl")) return;
            if (l.getClientProperty("jdp.tabOrig") != null) return;   // tab labels are owned by recolorMainTabs (tone-aware)
            // Inside a config panel we mono every label icon (section headers etc.). OUTSIDE one, only
            // touch KNOWN chrome icons (those with a mapped Tabler asset) — this reaches the main-tab
            // row and status-bar chrome without recolouring content icons (hoster favicons, file-type
            // thumbnails) which carry no mapped key.
            if (!cfg) {
                String key = iconKey(cur);
                if (key == null || tablerBase(key, cur.getIconWidth(), cur.getIconHeight()) == null) return;
            }
            javax.swing.Icon mono = tablerIcon(cur, SIDEBAR_TEXT, l);
            if (mono != cur) { l.setIcon(mono); l.putClientProperty("jdp.monoLbl", mono); }
        } catch (Throwable ignore) { }
    }

    // ---- ROUND 30 DIAGNOSTIC: can we read a JD icon's NAME at render time? -----------------
    // To swap JD's icons for a clean Tabler set we must know, per rendered component, WHICH JD
    // icon it is showing (the file name, e.g. "reconnect"). AppWork icons usually implement
    // org.appwork.swing.components.IDIcon (getIdentifier() -> IconIdentifier with a key), and JD
    // ImageIcons often carry the resource path in getDescription(). Dump whatever is present so we
    // know which handle to key the Tabler lookup on. Reflective + fully defensive: never throws.
    private static String iconId(javax.swing.Icon ic) {
        if (ic == null) return "null";
        StringBuilder sb = new StringBuilder(ic.getClass().getName());
        try {
            if (ic instanceof javax.swing.ImageIcon) {
                Object d = ((javax.swing.ImageIcon) ic).getDescription();
                if (d != null) sb.append(" desc=").append(d);
            }
        } catch (Throwable ignore) { }
        for (String mn : new String[] { "getIdentifier", "getKey", "getName", "getPath", "getResource" }) {
            try {
                java.lang.reflect.Method m = ic.getClass().getMethod(mn);
                Object r = m.invoke(ic);
                if (r != null) {
                    sb.append(' ').append(mn).append('=').append(r);
                    try {   // IconIdentifier itself may carry the key
                        java.lang.reflect.Method gk = r.getClass().getMethod("getKey");
                        sb.append("(key=").append(gk.invoke(r)).append(')');
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable ignore) { }
        }
        sb.append(' ').append(ic.getIconWidth()).append('x').append(ic.getIconHeight());
        return sb.toString();
    }

    // ---- ROUND 32 DIAGNOSTIC: which config-panel fields/buttons ignore the FlatLaf keys? ----------
    // Standard FlatLaf inputs already honour @componentBackground=#1e1e1e + focusWidth=0. The
    // "fields mal heller mal dunkler" + "blue focus frame" come from AppWork CUSTOM inputs that
    // bypass those keys. Dump every input in a config panel (class, bg, border class) so we know
    // which types + borders to fix in the render path next round.
    private static final java.util.Set<String> FIELD_SEEN = new java.util.HashSet<>();
    private static boolean fieldHeader = false;
    private static void logConfigFields() {
        if (FIELD_SEEN.size() > 200) return;
        try {
            for (Window w : Window.getWindows()) if (w.isShowing()) logFieldsIn(w, false);
        } catch (Throwable ignore) { }
    }
    private static void logFieldsIn(Container c, boolean inCfg) {
        boolean cfg = inCfg || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component ch : c.getComponents()) {
            if (cfg && (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                    || ch instanceof javax.swing.JSpinner || ch instanceof javax.swing.AbstractButton)) {
                try {
                    Color bg = ch.getBackground();
                    javax.swing.border.Border b = (ch instanceof JComponent) ? ((JComponent) ch).getBorder() : null;
                    String rec = "FIELD " + ch.getClass().getName()
                            + " bg=" + (bg == null ? "null" : Integer.toHexString(bg.getRGB() & 0xffffff))
                            + " opaque=" + (ch instanceof JComponent && ((JComponent) ch).isOpaque())
                            + " border=" + (b == null ? "null" : b.getClass().getName());
                    if (FIELD_SEEN.add(ch.getClass().getName() + "|" + rec.hashCode())) {
                        if (!fieldHeader) { writeDiag("=== CONFIG FIELDS (round 32) ==="); fieldHeader = true; }
                        writeDiag(rec);
                    }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) logFieldsIn((Container) ch, cfg);
        }
    }

    private static boolean iconInvHeader = false;
    private static final java.util.Set<String> ICON_SEEN = new java.util.HashSet<>();
    /** Walk every showing window and dump each button/label icon's identity to hl-diag. Runs on the
     *  5s cadence (not once) so it captures the main toolbar early AND the config-panel icons after
     *  Settings opens; ICON_SEEN dedups and caps the volume. */
    private static void logIconInventory() {
        if (ICON_SEEN.size() > 400) return;
        try {
            if (!iconInvHeader) { writeDiag("=== ICON INVENTORY (round 30 name-detection) ==="); iconInvHeader = true; }
            for (Window w : Window.getWindows()) if (w.isShowing()) logIconInvIn(w);
        } catch (Throwable ignore) { }
    }

    private static void logIconInvIn(Container c) {
        for (Component child : c.getComponents()) {
            try {
                javax.swing.Icon ic = null; String txt = null;
                if (child instanceof javax.swing.AbstractButton) {
                    javax.swing.AbstractButton b = (javax.swing.AbstractButton) child;
                    ic = b.getClientProperty("jdp.monoBtn") == b.getIcon() ? null : b.getIcon();
                    txt = b.getText(); if (txt == null || txt.isEmpty()) txt = b.getToolTipText();
                } else if (child instanceof javax.swing.JLabel) {
                    javax.swing.JLabel l = (javax.swing.JLabel) child;
                    ic = l.getClientProperty("jdp.monoLbl") == l.getIcon() ? null : l.getIcon();
                    txt = l.getText();
                }
                if (ic != null) {
                    String id = iconId(ic);
                    if (ICON_SEEN.add(id)) writeDiag("ICON [" + (txt == null ? "" : txt) + "] " + id);
                }
            } catch (Throwable ignore) { }
            if (child instanceof Container) logIconInvIn((Container) child);
        }
    }

    /**
     * Raise the Settings sidebar's row height. JD gives every entry a fixed size from a
     * shared public-static Dimension on the list's cell renderer
     * (jd...settings.sidebar.TreeRenderer.DIMENSION = new Dimension(0, 35)); there is no
     * List.cellHeight / UIManager key. Mutating that one Dimension object raises every
     * row at once. Reached through a LIVE renderer instance found in the tree, so the
     * static field resolves in JD's own classloader regardless of the agent's.
     */
    private static void styleSidebar() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && styleSidebarIn(w)) return;
        }
    }

    private static boolean styleSidebarIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JList) {
                javax.swing.JList<?> list = (javax.swing.JList<?>) child;
                // Resolve the REAL JD renderer, whether or not our accent-hover wrapper is installed:
                // if the live renderer is our wrapper, the JD one is stashed; otherwise it's live
                // (fresh, or JD reset it on a sidebar rebuild).
                javax.swing.ListCellRenderer<?> cur  = list.getCellRenderer();
                javax.swing.ListCellRenderer<?> wrap = asRenderer(list.getClientProperty(SB_WRAP));
                javax.swing.ListCellRenderer<?> r =
                        (cur == wrap && wrap != null) ? asRenderer(list.getClientProperty(SB_ORIG_RENDERER)) : cur;
                if (r != null && r.getClass().getName().endsWith("sidebar.TreeRenderer")) {
                    // Icon+label vertical centering is done in the accent-hover wrapper below
                    // (installSidebarAccentHover). It MUST run downstream of JD's per-cell
                    // configuration: JD's TreeRenderer.getListCellRendererComponent reconfigures the
                    // shared component every call, so any alignment set on the renderer instance here
                    // is overwritten before each paint (that is why round 14 was a no-op).
                    // Roomier rows: bump the shared static DIMENSION height. Idempotent (the
                    // < guard stops once it reaches SIDEBAR_ROW_PX), so it survives sidebar rebuilds.
                    try {
                        Field f = r.getClass().getField("DIMENSION");   // public static final Dimension
                        Object dim = f.get(null);
                        if (dim instanceof Dimension && ((Dimension) dim).height < SIDEBAR_ROW_PX) {
                            ((Dimension) dim).height = SIDEBAR_ROW_PX;
                            // a variable-height JList caches cell heights; the DIMENSION change alone
                            // does NOT invalidate that cache. Toggling fixedCellHeight fires the
                            // property change that forces the UI to recompute, then back to variable.
                            list.setFixedCellHeight(SIDEBAR_ROW_PX);
                            list.setFixedCellHeight(-1);
                            list.revalidate();
                            list.repaint();
                            System.out.println("[jd-dialog-agent] jd-highlighter: settings sidebar row height -> "
                                    + SIDEBAR_ROW_PX + "px");
                        }
                    } catch (Throwable t) {
                        System.out.println("[jd-dialog-agent] sidebar height bump failed: " + t);
                    }
                    // Accent hover: JD's own sidebar overlay is only a faint ~10% tint. Wrap the
                    // renderer so the hovered (non-selected) row paints FULLY in the accent, matching
                    // the table row-hover. Idempotent + survives sidebar rebuilds.
                    installSidebarAccentHover(list, r);
                    clearSidebarBorders(list);   // drop the long vertical line at the sidebar's edge
                    return true;
                }
            }
            if (child instanceof Container && styleSidebarIn((Container) child)) return true;
        }
        return false;
    }

    /** Remove the border that draws a long vertical line down the settings sidebar's edge: clear the
     *  enclosing JScrollPane's border + viewport border, and any 1px-wide MatteBorder on the ancestors
     *  between the list and that scroll pane (JD draws the divider there). Idempotent. */
    private static void clearSidebarBorders(Component list) {
        for (Component p = list.getParent(); p != null; p = p.getParent()) {
            if (p instanceof JComponent) {
                JComponent jc = (JComponent) p;
                if (jc.getBorder() != null) jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            }
            if (p instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sp = (javax.swing.JScrollPane) p;
                if (sp.getViewportBorder() != null) sp.setViewportBorder(null);
                break;   // the scroll pane is the sidebar's outer edge; stop here
            }
        }
    }

    private static final String SB_ORIG_RENDERER = "jdp.sbOrigRenderer";
    private static final String SB_WRAP          = "jdp.sbWrap";
    private static final String SB_HOVER_ROW     = "jdp.sbHoverRow";
    private static final String SB_LISTENERS     = "jdp.sbListeners";
    private static final String SB_BTN           = "jdp.sbButton";
    private static final Color  SB_BASE          = new Color(0x16, 0x16, 0x16);  // deep sidebar base the tiles float on
    private static final Color  SB_BTN_BG        = new Color(0x24, 0x24, 0x24);  // button tile on the #161616 sidebar
    private static final int    SB_BTN_GAP_V = 3, SB_BTN_GAP_H = 8, SB_BTN_ARC = 12;

    /** CC-style: each sidebar row is a rounded button tile floating on the dark sidebar, not a
     *  continuous bar. One reused instance wraps JD's (reconfigured) cell component each render; it
     *  paints the tile behind the transparent content. Selected/hovered = accent fill, else a subtle
     *  #242424 tile. Reused, so no per-cell allocation. */
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
     * Paint the hovered (non-selected) settings-sidebar row fully in the accent, matching the
     * table row-hover. JD's built-in sidebar overlay is only a ~10% tint, so instead we wrap the
     * JD cell renderer and recolour just the hovered row. Own hover tracking via a MouseMotion
     * listener + a client-property row index. All state hangs off the JList so it is idempotent
     * and a rebuilt sidebar (fresh JList) simply re-installs. JD reconfigures the shared renderer
     * component on every call, so overriding the hovered row never leaks to the other rows.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installSidebarAccentHover(final javax.swing.JList list, javax.swing.ListCellRenderer jdRenderer) {
        // Always (re)stash the live JD renderer so the wrapper delegates to the current one.
        list.putClientProperty(SB_ORIG_RENDERER, jdRenderer);
        javax.swing.ListCellRenderer wrap = (javax.swing.ListCellRenderer) list.getClientProperty(SB_WRAP);
        if (wrap == null) {
            wrap = new javax.swing.ListCellRenderer() {
                public Component getListCellRendererComponent(javax.swing.JList l, Object v, int idx, boolean sel, boolean foc) {
                    javax.swing.ListCellRenderer real = (javax.swing.ListCellRenderer) l.getClientProperty(SB_ORIG_RENDERER);
                    Component comp = real.getListCellRendererComponent(l, v, idx, sel, foc);
                    // Vertically center the icon+label in the tall (66px) row. JD's TreeRenderer is a
                    // MigLayout panel wrapping a single RenderLabel that MigLayout top-anchors
                    // (diag). A MigLayout "aligny center" constraint (round 16) did NOT move the grid
                    // (measured still ~6px top-biased). Instead push the RenderLabel's content down
                    // with a top inset: measured ~3px above / ~14px below, so a SIDEBAR_TOP_PAD top
                    // border re-centers it — a JLabel's preferred size includes its border, so
                    // MigLayout re-lays the (now taller) label lower. Applied each call but only when
                    // not already set, so it survives any per-cell border reset by JD.
                    if (comp instanceof Container) {
                        try {
                            Component[] kids = ((Container) comp).getComponents();
                            if (kids.length >= 1 && kids[0] instanceof javax.swing.JComponent) {
                                javax.swing.JComponent rl = (javax.swing.JComponent) kids[0];
                                boolean ok = (rl.getBorder() instanceof javax.swing.border.EmptyBorder)
                                        && ((javax.swing.border.EmptyBorder) rl.getBorder()).getBorderInsets().top == SIDEBAR_TOP_PAD;
                                if (!ok) {
                                    rl.setBorder(javax.swing.BorderFactory.createEmptyBorder(SIDEBAR_TOP_PAD, 0, 0, 0));
                                    ((Container) comp).revalidate();
                                }
                            }
                        } catch (Throwable ignore) { }
                    }
                    if (!SIDEBAR_DIAG_DONE) {
                        SIDEBAR_DIAG_DONE = true;
                        String kids = "";
                        if (comp instanceof Container) {
                            Component[] cc = ((Container) comp).getComponents();
                            StringBuilder kb = new StringBuilder();
                            for (int i = 0; i < Math.min(cc.length, 6); i++) kb.append(cc[i].getClass().getSimpleName()).append(",");
                            kids = " kids[" + cc.length + "]=" + kb;
                        }
                        writeDiag("sidebar comp=" + comp.getClass().getName()
                                + " isJLabel=" + (comp instanceof javax.swing.JLabel)
                                + " layout=" + (comp instanceof Container && ((Container) comp).getLayout() != null
                                        ? ((Container) comp).getLayout().getClass().getSimpleName() : "-")
                                + kids);
                    }
                    Object h = l.getClientProperty(SB_HOVER_ROW);
                    int hoverRow = (h instanceof Integer) ? ((Integer) h).intValue() : -1;
                    // Hover recolour + child-label foreground. The TreeRenderer (and its child
                    // RenderLabel that holds the text) is SHARED across cells and JD does NOT reset
                    // the child's foreground per cell, so set it on EVERY row — else the dark hover
                    // colour bleeds onto rows painted after the hovered one (dark text on dark =
                    // invisible labels). Read JD's per-row foreground first (light for normal, its
                    // own for selected), override to the dark accent-fg only for the hovered row.
                    // Set the row foreground on EVERY row. JD resets neither the panel's nor the
                    // child RenderLabel's foreground per cell, so reading comp.getForeground() just
                    // returns our own leaked value (round 19's mistake). Use a FIXED light colour for
                    // normal + selected rows, the dark accent-fg only on the hovered non-selected row.
                    // Selected OR hovered -> accent tile (dark glyph/text); otherwise a subtle #242424
                    // tile with light text. comp is made non-opaque so the SidebarButton tile shows.
                    boolean accentBg = sel || idx == hoverRow;
                    Color rowFg = accentBg ? accentFg() : SIDEBAR_TEXT;
                    if (comp instanceof javax.swing.JComponent) ((javax.swing.JComponent) comp).setOpaque(false);
                    comp.setForeground(rowFg);
                    boolean hovAcc = accentBg;
                    if (comp instanceof Container) {
                        for (Component k : ((Container) comp).getComponents()) {
                            k.setForeground(rowFg);
                            // Mono the sidebar icon in the render path (this wrapper) so it renders
                            // single-tone regardless of JD's icon cache (the file-level SVG recolour
                            // can't win — JD caches its icons). Light tone normally; dark tone (the
                            // flip) on the accent hover row so it stays visible on the light accent.
                            // JD re-sets the original icon every cell, so this never compounds/leaks.
                            if (k instanceof javax.swing.JLabel) {
                                javax.swing.JLabel kl = (javax.swing.JLabel) k;
                                javax.swing.Icon ic = kl.getIcon();
                                if (ic != null) {
                                    if (ICON_SEEN.size() <= 400) {   // round-30 name detection for the sidebar cells
                                        String sid = "SIDEBAR[" + (kl.getText() == null ? "" : kl.getText()) + "] " + iconId(ic);
                                        if (ICON_SEEN.add(sid)) writeDiag(sid);
                                    }
                                    kl.setIcon(tablerIcon(ic, hovAcc ? accentFg() : SIDEBAR_TEXT, kl));
                                }
                            }
                        }
                    }
                    // Wrap JD's (now transparent) cell in the reused rounded tile so the sidebar reads
                    // as CC-style stacked buttons, not a continuous bar. Accent fill on selected/hover,
                    // subtle #242424 tile otherwise.
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
     * Strip the underline from Settings section titles. JD builds each title as
     * "&lt;html&gt;&lt;u&gt;&lt;b&gt;name&lt;/b&gt;&lt;/u&gt;&lt;/html&gt;" (Header.java); the underline is baked into
     * the markup and no colour key reaches it. Remove the &lt;u&gt; tags so the titles read as
     * clean bold text. Idempotent: once stripped the text no longer matches. Re-runs each
     * tick so a settings page rebuilt on navigation is cleaned again within ~400ms.
     */
    private static void stripSectionUnderlines() {
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

    // --- main tabs: readable text on the accent selected tab ------------------
    // FlatLaf's TabbedPane.selectedForeground is defeated when JD sets a per-tab foreground
    // / a custom tab component / an HTML title (JD recolours the LinkGrabber tab for "new
    // links"). So set the per-tab foreground ourselves: dark on the accent selected tab,
    // light on the rest, reusing the theme's own TabbedPane colours from UIManager. Re-run
    // each tick (idempotent) so it survives rebuilds + selection changes.
    private static void recolorMainTabs() {
        Color selFg = UIManager.getColor("TabbedPane.selectedForeground");   // accent_fg (dark)
        Color norFg = UIManager.getColor("TabbedPane.foreground");           // light
        if (selFg == null || norFg == null) return;
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) recolorTabsIn(w, selFg, norFg);
        }
    }

    private static final String TAB_HOVER_IDX = "jdp.tabHoverIdx";
    private static void recolorTabsIn(Container c, Color selFg, Color norFg) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JTabbedPane) {
                javax.swing.JTabbedPane tp = (javax.swing.JTabbedPane) child;
                // Respect the CURRENT hover (tracked by the listener) so the 400ms tick does not
                // reset the hovered tab back to light (that fight left the hover text unreadable).
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                applyTabForegrounds(tp, selFg, norFg, (hv instanceof Integer) ? (Integer) hv : -1);
                installTabHoverListener(tp, selFg, norFg);
            }
            if (child instanceof Container) recolorTabsIn((Container) child, selFg, norFg);
        }
    }

    /** Dark text on tabs with an accent background (SELECTED or HOVERED), light on the rest.
     *  JD's custom tab components bypass FlatLaf's hover/selectedForeground, so we own it. */
    private static void applyTabForegrounds(final javax.swing.JTabbedPane tp, Color selFg, Color norFg, int hover) {
        int sel = tp.getSelectedIndex();
        boolean pillInstalled = false;
        for (int i = 0; i < tp.getTabCount(); i++) {
            boolean accentBg = (i == sel || i == hover);
            Color want = new Color((accentBg ? selFg : norFg).getRGB());  // plain Color: app-set, honoured
            if (!want.equals(tp.getForegroundAt(i))) tp.setForegroundAt(i, want);
            Color iconTone = accentBg ? accentFg() : SIDEBAR_TEXT;        // dark glyph on the accent tab, light otherwise
            javax.swing.Icon slot = tp.getIconAt(i);                      // JD may set the icon via setIconAt(...)
            if (slot != null) {
                String pk = "jdp.tabIcOrig." + i;
                javax.swing.Icon o = (javax.swing.Icon) tp.getClientProperty(pk);
                if (o == null && iconKey(slot) != null) { o = slot; tp.putClientProperty(pk, o); }
                if (o != null) { javax.swing.Icon nw = tablerIcon(o, iconTone, tp); if (nw != tp.getIconAt(i)) tp.setIconAt(i, nw); }
            }
            Component tc = tp.getTabComponentAt(i);   // custom tab component (JLabel etc.)
            if (tc != null) {
                setLabelFg(tc, want);
                tablerTabIcons(tc, iconTone);
                // Install the inset accent-pill border once; it grows each tab's preferred width.
                if (tc instanceof javax.swing.JComponent
                        && !(((javax.swing.JComponent) tc).getBorder() instanceof PillBorder)) {
                    ((javax.swing.JComponent) tc).setBorder(new PillBorder());
                    pillInstalled = true;
                }
            }
        }
        if (pillInstalled) {
            // FlatLaf caches tab widths and a plain revalidate() during the tick did not re-measure
            // (the label clipped: "Downloads" -> "Download"). Re-SET each tab component + revalidate,
            // deferred to the next EDT cycle, which forces FlatTabbedPaneUI to recompute the widths
            // from the now-bordered components — so the pill has room and nothing clips.
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    try {
                        for (int i = 0; i < tp.getTabCount(); i++) {
                            Component t = tp.getTabComponentAt(i);
                            if (t != null) { t.invalidate(); tp.setTabComponentAt(i, t); }
                        }
                        tp.revalidate();
                        tp.repaint();
                    } catch (Throwable ignore) { }
                }
            });
        }
    }

    /** The main-tab pill: an inset rounded accent fill painted behind the tab's content, only when
     *  THIS tab is selected or hovered. Self-determining (finds its own tab index + the pane's
     *  selected/hover state each paint), so it is correct on the very first paint — no 400ms flash.
     *  paintBorder runs after the component background and BEFORE the children, so the icon+text sit
     *  on top of the pill. Its insets pad the content inside the pill; the FlatLaf tabInsets margin
     *  around this component then reads as the dark gap between pills. */
    private static final class PillBorder implements javax.swing.border.Border {
        private static final int PAD_V = 4, PAD_H = 10, ARC = 12;
        public boolean isBorderOpaque() { return false; }
        public java.awt.Insets getBorderInsets(Component c) { return new java.awt.Insets(PAD_V, PAD_H, PAD_V, PAD_H); }
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            try {
                javax.swing.JTabbedPane tp = null;
                for (Component p = c.getParent(); p != null; p = p.getParent())
                    if (p instanceof javax.swing.JTabbedPane) { tp = (javax.swing.JTabbedPane) p; break; }
                if (tp == null) return;
                int idx = -1;
                for (int i = 0; i < tp.getTabCount(); i++) if (tp.getTabComponentAt(i) == c) { idx = i; break; }
                if (idx < 0) return;
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                int hover = (hv instanceof Integer) ? ((Integer) hv).intValue() : -1;
                if (idx != tp.getSelectedIndex() && idx != hover) return;
                Color acc = accentColor();
                if (acc == null) return;
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(acc);
                g2.fillRoundRect(x, y, w, h, ARC, ARC);
                g2.dispose();
            } catch (Throwable ignore) { }
        }
    }

    /** Tabler-swap the chrome icon on a tab's custom component (JLabel), tone-aware. A stored original
     *  (client prop "jdp.tabOrig") means the light/dark flip on selection always re-tints from JD's
     *  original glyph, never from an already-tinted icon (no unbounded re-tint / churn). */
    private static void tablerTabIcons(Component c, Color tone) {
        if (c instanceof javax.swing.JLabel) {
            javax.swing.JLabel l = (javax.swing.JLabel) c;
            javax.swing.Icon o = (javax.swing.Icon) l.getClientProperty("jdp.tabOrig");
            if (o == null) {
                javax.swing.Icon cur = l.getIcon();
                if (cur != null && iconKey(cur) != null) { o = cur; l.putClientProperty("jdp.tabOrig", o); }
            }
            if (o != null) { javax.swing.Icon nw = tablerIcon(o, tone, l); if (l.getIcon() != nw) l.setIcon(nw); }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) tablerTabIcons(ch, tone);
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
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(idx));   // so the tick keeps it dark
                applyTabForegrounds(tp, selFg, norFg, idx);
                if (!Integer.valueOf(idx).equals(prev)) tp.repaint();        // repaint the pills on hover change
            }
            @Override public void mouseExited(MouseEvent e) {
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(-1));
                applyTabForegrounds(tp, selFg, norFg, -1);
                tp.repaint();
            }
        };
        tp.addMouseMotionListener(h);
        tp.addMouseListener(h);
        // Colour on selection change too, so clicking a tab flips its text/icon to the dark accent
        // tone IMMEDIATELY instead of on the next 400ms tick (that lag was the "dark text only renders
        // on click / render problem"). Keeps the current hover so a hovered tab does not reset.
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

    // --- settings sections as cards (CC-style) --------------------------------
    // Install a Border on each AbstractConfigPanel that paints a rounded #1e1e1e band behind
    // every section (between successive Header y's) and insets the rows so they float inside a
    // padded card. Border paints AFTER the panel background + BEFORE the children, so the band
    // sits behind the rows with no re-parenting and no layout mutation (re-parenting is fatal:
    // JD rebuilds the panel on every navigation). Idempotent (a fresh panel re-gets the border).
    private static void cardSettingsSections() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) cardSectionsIn(w);
        }
    }

    private static void cardSectionsIn(Container c) {
        for (Component child : c.getComponents()) {
            // JD's settings pages are SUBCLASSES of AbstractConfigPanel (GeneralSettingsConfigPanel,
            // ...), so match the superclass chain, not the exact class name.
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
                hideSeparators(panel);   // remove the "title ----" section lines; cards give structure
                spreadSections(panel);   // add a vertical gap between sections so cards sit apart
            }
            if (child instanceof Container) cardSectionsIn((Container) child);
        }
    }

    private static boolean isConfigPanel(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass())
            if (k.getName().endsWith(".AbstractConfigPanel")) return true;
        return false;
    }

    private static void hideSeparators(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JSeparator) { if (ch.isVisible()) ch.setVisible(false); }
            else if (ch instanceof Container) hideSeparators((Container) ch);
        }
    }

    private static final class SectionCardBorder implements javax.swing.border.Border {
        private final javax.swing.border.Border original;
        private static final Color CARD = new Color(0x24, 0x24, 0x24);   // surface: base < field < card
        private static final int MARGIN = 10, INNER = 12, ARC = 14;      // equal MARGIN gap on all sides
        SectionCardBorder(javax.swing.border.Border original) { this.original = original; }

        public boolean isBorderOpaque() { return false; }

        public java.awt.Insets getBorderInsets(Component c) {
            // rows sit MARGIN+INNER from the panel edge = INNER inside the card, MARGIN outside it
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
                    int left = x + MARGIN, right = x + w - MARGIN;   // equal side margins
                    for (int i = 0; i < headers.size(); i++) {
                        int hTop = headers.get(i).getY();
                        int hNext = (i + 1 < headers.size()) ? headers.get(i + 1).getY() : Integer.MAX_VALUE;
                        // enclose ONLY this section's rows; the injected gap-above-header
                        // (spreadSections) then shows as an equal gap between the cards.
                        int contentBottom = hTop + headers.get(i).getHeight();
                        for (Component ch : p.getComponents()) {
                            if (!ch.isVisible()) continue;
                            int cy = ch.getY();
                            if (cy >= hTop && cy < hNext) contentBottom = Math.max(contentBottom, cy + ch.getHeight());
                        }
                        int top = hTop - INNER, bottom = contentBottom + INNER;
                        if (bottom - top > 6)
                            g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                                    left, top, right - left, bottom - top, ARC, ARC));
                    }
                } finally { g2.dispose(); }
            }
            if (original != null) original.paintBorder(c, g, x, y, w, h);
        }
    }

    // Inject a vertical gap ABOVE each section (except the first) via the config panel's
    // MigLayout, so the cards sit clearly apart. Idempotent (skips a header that already
    // carries our gaptop) + re-applies after a JD rebuild. Same setComponentConstraints
    // reflection the toolbar-grow already uses.
    private static void spreadSections(JComponent panel) {
        LayoutManager lm = panel.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method gc = lm.getClass().getMethod("getComponentConstraints", Component.class);
            Method sc = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            boolean first = true, changed = false;
            for (Component ch : panel.getComponents()) {
                if (!ch.getClass().getName().endsWith(".Header")) continue;
                if (first) { first = false; continue; }   // no gap above the first section
                Object cur = gc.invoke(lm, ch);
                String cc = (cur == null) ? "" : cur.toString();
                if (cc.contains("gaptop")) continue;       // already spread
                sc.invoke(lm, ch, (cc.isEmpty() ? "" : cc + ",") + "gaptop 34");   // = MARGIN + 2*INNER
                changed = true;
            }
            if (changed) { panel.revalidate(); panel.repaint(); }
        } catch (Throwable ignore) { }
    }

    // --- pop-up dialogs: lighter content + dimmed backdrop -------------------
    // (1) recolour each shown dialog to the #242424 surface (styleDialogContent) so it lifts off the
    //     #161616 chrome by shade; inputs/buttons get the same unified fills as the settings cards.
    private static final Color DIALOG_BG = new Color(0x24, 0x24, 0x24);   // surface (matches cards)

    private static void recolorDialogs() {
        for (Window w : Window.getWindows()) {
            if (!(w instanceof Dialog) || !w.isShowing()) continue;
            try {
                if (w instanceof javax.swing.RootPaneContainer)
                    styleDialogContent(((javax.swing.RootPaneContainer) w).getContentPane());
            } catch (Throwable ignore) { }
        }
    }

    /** Make EVERY AppWork dialog read like the rest of the theme: dark surface, one recessed fill for
     *  inputs, one raised fill for buttons, and the decorative "framed/etched" borders removed (the
     *  "eckig mit Rahmen, unterschiedliche Farben" complaint). Run every tick, fully idempotent
     *  (guarded on value), so late-built dialog content is caught too. Icons are mono'd by the
     *  monoChrome sweep which already walks dialog windows. */
    private static void styleDialogContent(Container c) {
        for (Component ch : c.getComponents()) {
            try {
                if (ch instanceof javax.swing.JPanel || ch instanceof javax.swing.JOptionPane
                        || ch instanceof javax.swing.Box || ch instanceof javax.swing.JScrollPane) {
                    if (!DIALOG_BG.equals(ch.getBackground())) ch.setBackground(DIALOG_BG);
                    stripFramingBorder((JComponent) ch);
                } else if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                        || ch instanceof javax.swing.JSpinner) {
                    if (!FIELD_BG.equals(ch.getBackground())) {
                        ch.setBackground(FIELD_BG);
                        if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                    }
                } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                    if (!BTN_CFG_BG.equals(ch.getBackground())) ch.setBackground(BTN_CFG_BG);
                } else if (ch instanceof JComponent) {
                    stripFramingBorder((JComponent) ch);
                }
            } catch (Throwable ignore) { }
            if (ch instanceof Container) styleDialogContent((Container) ch);
        }
    }

    /** Remove a decorative frame (Titled/Etched/Line/Bevel/Matte, or an AppWork dialog border that
     *  draws the grey rectangle around a pop-up) so the dialog reads by shade, not by lines; leave
     *  EmptyBorder/compound padding intact. */
    private static void stripFramingBorder(JComponent jc) {
        javax.swing.border.Border b = jc.getBorder();
        if (b == null || b instanceof javax.swing.border.EmptyBorder) return;
        String cn = b.getClass().getName();
        if (b instanceof javax.swing.border.TitledBorder || b instanceof javax.swing.border.EtchedBorder
                || b instanceof javax.swing.border.LineBorder || b instanceof javax.swing.border.BevelBorder
                || b instanceof javax.swing.border.MatteBorder
                || cn.startsWith("org.appwork") || cn.contains("LineBorder") || cn.contains("Etched"))
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
    }

    // (2) dim the OWNER window behind a modal pop-up so it stands out (no lines/blur). A
    //     translucent glass pane on the owner darkens only the owner; the modal dialog is a
    //     separate top-level window and stays bright. Polled + self-healing: removed as soon
    //     as no modal child is showing, so an abnormally-disposed dialog can't leak the overlay.
    private static final java.util.Map<Window, Component> DIMMED =
            new java.util.WeakHashMap<Window, Component>();

    private static void dimModalBackdrops() {
        boolean modal = false;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isShowing()
                    && ((Dialog) w).getModalityType() != Dialog.ModalityType.MODELESS) { modal = true; break; }
        }
        // Dim the MAIN frame (the big visible one). A modal dialog's DECLARED owner is often a
        // hidden shared frame, so dimming that showed nothing (why the backdrop looked absent).
        // The dialog is a separate window and stays bright above the dimmed main frame.
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
                // Frosted backdrop: snapshot the frame content NOW (it is frozen behind the modal),
                // box-blur it, and paint the blur + a dark veil. Snapshot is one-shot (modal open),
                // never per-tick. Falls back to a flat dark fill if the snapshot/blur fails.
                final java.awt.image.BufferedImage blur = frostSnapshot(rp);
                JComponent dim = new JComponent() {
                    protected void paintComponent(Graphics g) {
                        if (blur != null) g.drawImage(blur, 0, 0, getWidth(), getHeight(), null);
                        g.setColor(new Color(0, 0, 0, blur != null ? 110 : 175));   // lighter veil over the blur = frosted, not black
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

    /** Paint the root pane's layered content (everything below the glass) to an image and box-blur it
     *  at half resolution, so the modal backdrop reads as frosted glass. One-shot per modal open. */
    private static java.awt.image.BufferedImage frostSnapshot(javax.swing.JRootPane rp) {
        try {
            int w = rp.getWidth(), h = rp.getHeight();
            if (w <= 0 || h <= 0) return null;
            int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);   // half-res: cheaper + blurrier
            java.awt.image.BufferedImage snap = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = snap.createGraphics();
            g.scale(sw / (double) w, sh / (double) h);
            rp.getLayeredPane().printAll(g);   // content below the glass pane (no recursion)
            g.dispose();
            return boxBlur(boxBlur(snap, 4), 4);   // two passes ~= a soft gaussian
        } catch (Throwable t) { return null; }
    }

    /** Separable box blur (horizontal then vertical), radius r, on an INT_RGB image. */
    private static java.awt.image.BufferedImage boxBlur(java.awt.image.BufferedImage src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] tmp = new int[in.length];
        blurPass(in, tmp, w, h, r);   // horizontal
        blurPass(tmp, in, h, w, r);   // vertical (transposed dims)
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
                dst[x * h + y] = (rr << 16) | (gg << 8) | bb;   // transpose: [x*h + y]
            }
        }
    }

    // --- diagnostics: what does the jd-highlighter sweep actually find? ------
    private static void logHlPolish() {
        try {
            int[] n = {0, 0, 0};   // tabbedPanes, configPanels, combos
            int[] sidebarH = {-1}; // -1 none, -2 list-but-no-DIMENSION, >0 the DIMENSION height
            for (Window w : Window.getWindows()) {
                if (w.isShowing()) countHl(w, n, sidebarH);
            }
            System.out.println("[jd-dialog-agent] hl-polish: tabbedPanes=" + n[0]
                    + " configPanels=" + n[1]
                    + " sidebar=" + (sidebarH[0] == -1 ? "none" : sidebarH[0] == -2 ? "list-no-DIMENSION" : sidebarH[0] + "px")
                    + " combos=" + n[2]);
        } catch (Throwable ignore) { }
    }

    private static void countHl(Container c, int[] n, int[] sidebarH) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JTabbedPane) n[0]++;
            if (ch instanceof JComponent && isConfigPanel(ch.getClass())) n[1]++;
            if (ch instanceof javax.swing.JComboBox) n[2]++;
            if (ch instanceof javax.swing.JList) {
                javax.swing.ListCellRenderer<?> r = ((javax.swing.JList<?>) ch).getCellRenderer();
                if (r != null && r.getClass().getName().endsWith("TreeRenderer")) {
                    try {
                        Object dim = r.getClass().getField("DIMENSION").get(null);
                        if (dim instanceof Dimension) sidebarH[0] = ((Dimension) dim).height;
                    } catch (Throwable ignore) { sidebarH[0] = -2; }
                }
            }
            if (ch instanceof Container) countHl((Container) ch, n, sidebarH);
        }
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
            //
            // CRITICAL narrowing (community PR #2, @ahmed-abdelrazek): the LAF NAME also
            // appears in purely informational dialogs — most notably Help -> About, which
            // lists the active look and feel. Matching on the LAF name ALONE made the
            // agent mistake the About dialog for this prompt, find no restart button, and
            // fire the "no known button -> request restart" fallback below — so every time
            // a user opened About, JD restarted and the desktop went black. Require an
            // actual restart/apply INTENT in the body, and never touch an About-type dialog.
            if (w instanceof Dialog) {
                String body = collectText(w).toLowerCase();
                String lower = title.toLowerCase();
                boolean isInfoDialog = lower.contains("about") || lower.contains("über");
                boolean mentionsLaf = body.contains("look and feel")
                        || body.contains("look-and-feel") || body.contains("flatlaf");
                boolean wantsRestart = body.contains("restart") || body.contains("neu start")
                        || body.contains("neustart") || body.contains("relaunch")
                        || body.contains("apply") || body.contains("übernehmen")
                        || body.contains("anwenden");
                if (!isInfoDialog && mentionsLaf && wantsRestart) {
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
