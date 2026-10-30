package io.github.junkerderprovinz;

import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.Tables.*;

/** Look-and-feel setup: LAF jars on the system class path, FlatLaf custom defaults and the UIManager remap. */
final class LafDefaults {

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
    static final java.io.File DEFAULTS_DIR = DESKTOP
            ? new java.io.File(HL_HOME, "flatlaf-defaults")
            : new java.io.File("/opt/JDownloader/flatlaf-defaults");

    private static volatile boolean defaultsRegistered = false;
    private static volatile boolean flatLafLoadHookFired = false;
    private static boolean lafRefreshDone     = false;
    private static int     lafStableTicks     = 0;
    private static int     registrationWait   = 0;
    private static int     classScanTicks     = 0;

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
            new java.io.File(JD_HOME, "libs/laf/flatlaf.jar");
    private static final java.io.File LAF_DIR =
            new java.io.File(JD_HOME, "libs/laf");
    private static boolean flatlafExposed = false;
    private static final java.util.HashSet<String> syntheticaExposed =
            new java.util.HashSet<String>();

    static void exposeFlatlafToSystemLoader() {
        if (wantClassicLaf() || flatlafExposed || INSTRUMENTATION == null || !FLATLAF_JAR.isFile()) return;
        try {
            // the JarFile constructor validates the zip; a truncated install throws
            // and we simply retry on a later tick (the container boot heal replaces it)
            INSTRUMENTATION.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(FLATLAF_JAR));
            flatlafExposed = true;
            System.out.println("[jd-dialog-agent] appended flatlaf.jar to the system classloader (LAF-by-name can resolve now)");
        } catch (Throwable ignore) { }
    }

    /** Same classloader fix as FlatLaf for Synthetica / JDDefaultLookAndFeel.
     *  Include syntheticaJDCustom.jar (DEFAULT LAF assets) and every synthetica*.jar.
     *  Exposing only synthetica.jar is not enough — JD falls back to Metal. */
    static void exposeSyntheticaToSystemLoader() {
        if (!wantClassicLaf() || INSTRUMENTATION == null || !LAF_DIR.isDirectory()) return;
        java.io.File[] preferred = new java.io.File[] {
            new java.io.File(LAF_DIR, "synthetica.jar"),
            new java.io.File(LAF_DIR, "syntheticaJDCustom.jar"),
            new java.io.File(LAF_DIR, "syntheticaSimple2D.jar")
        };
        for (java.io.File jar : preferred) {
            appendLafJarOnce(jar);
        }
        java.io.File[] all = LAF_DIR.listFiles();
        if (all == null) return;
        for (java.io.File jar : all) {
            if (!jar.isFile()) continue;
            String n = jar.getName();
            if (n.startsWith("synthetica") && n.endsWith(".jar")) {
                appendLafJarOnce(jar);
            }
        }
    }

    private static void appendLafJarOnce(java.io.File jar) {
        if (jar == null || !jar.isFile()) return;
        String path;
        try {
            path = jar.getCanonicalPath();
        } catch (Throwable t) {
            path = jar.getAbsolutePath();
        }
        if (!syntheticaExposed.add(path)) return;
        try {
            INSTRUMENTATION.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(jar));
            System.out.println("[jd-dialog-agent] appended " + jar.getName()
                    + " to the system classloader (Synthetica LAF can resolve now)");
        } catch (Throwable t) {
            syntheticaExposed.remove(path);
        }
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
    static void registerDefaultsSource() {
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
     * Race-proof registration, fired from the class-load transformer the moment FlatLaf's
     * base class is defined (well before JD builds its GUI or calls setLookAndFeel). We
     * register on a SEPARATE daemon thread so we never recurse into the in-progress class
     * definition on the defining thread: loadClass blocks until FlatLaf is fully defined,
     * then registerCustomDefaultsSource runs before JD reads the LAF defaults, so the very
     * first paint uses our colours. This removes the dependency on the fragile live-re-apply
     * fallback, which lost the race on cold/slow boots and left stock-grey FlatDarkLaf.
     */
    static void registerDefaultsOnFlatLafLoad(final ClassLoader loader) {
        if (flatLafLoadHookFired) return;
        flatLafLoadHookFired = true;
        if (loader == null || defaultsRegistered || !DEFAULTS_DIR.isDirectory()) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Class<?> flatLaf = loader.loadClass("com.formdev.flatlaf.FlatLaf");
                    flatLaf.getMethod("registerCustomDefaultsSource", java.io.File.class)
                           .invoke(null, DEFAULTS_DIR);
                    defaultsRegistered = true;
                    System.out.println("[jd-dialog-agent] registered custom defaults source at FlatLaf class-load (race won)");
                } catch (Throwable e) {
                    System.out.println("[jd-dialog-agent] early defaults registration failed ("
                            + e.getClass().getSimpleName() + ") — tick-loop registration will retry");
                }
            }
        }, "jd-laf-early-register");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Make the registered defaults take effect exactly once. If registration won the
     * race against JD's setLookAndFeel, the sentinel (Panel.background == #161616)
     * already matches and nothing needs to happen. Otherwise re-apply the CURRENT LAF
     * once (fresh instance -> getDefaults() re-reads the custom source) and refresh all
     * windows — only after the main window is stable, the exact gate that has kept
     * enforceDarkChrome() clear of the CircleProgressBarUI boot-loop for months.
     */
    static void applyCustomDefaults() {
        if (lafRefreshDone) return;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        // Light/Default FlatLaf: never apply Carbon custom defaults — leave native LAF intact.
        if (!wantDarkLaf()) {
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] non-dark LAF (" + laf.getClass().getSimpleName()
                    + ") — skipping dark custom-defaults re-apply");
            return;
        }

        if (defaultsRegistered) {
            Color bg = UIManager.getColor("Panel.background");
            // Dark sentinel: Carbon #161616. Light: skip this check (FlatLight is never #161616).
            if (wantDarkLaf() && bg != null && (bg.getRGB() & 0xFFFFFF) == 0x161616) {
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

    // ------------------------------------------------------------ classic LAF colours

    /** Stock classic JD progress-bar gradient (aRGB). Defaults in LAFSettings are null —
     *  CustomProgressbarPainter then NPEs on LinearGradientPaint. JSON seed alone is not
     *  always visible to LAFOptions in time; set via cfg setters once LAFOptions exists. */
    private static final String[] CLASSIC_PROGRESS_COLORS = {
            "#5F70CCFF", "#5F80C7F7", "#8078C0EF", "#5F80C7F7", "#5F70CCFF"
    };
    private static boolean classicLafColorsSeeded = false;

    static void seedClassicLafColors() {
        if (!wantClassicLaf() || classicLafColorsSeeded) return;
        try {
            Class<?> lafClz = Class.forName("org.jdownloader.updatev2.gui.LAFOptions");
            Object inst;
            try {
                inst = lafClz.getMethod("getInstance").invoke(null);
            } catch (Throwable notReady) {
                return; // LAFOptions Not initialized yet — retry next tick
            }
            if (inst == null) return;
            Object cfg = lafClz.getMethod("getCfg").invoke(inst);
            if (cfg == null) return;
            // Progress keys: same guarded path as other colours (LAFSettings Foreground names).
            for (int i = 1; i <= 5; i++) {
                putCfgStringIfBlank(cfg,
                        "getColorForProgressbarForeground" + i,
                        "setColorForProgressbarForeground" + i,
                        CLASSIC_PROGRESS_COLORS[i - 1]);
            }
            // Readable config/dialog labels (avoids all-grey Update dialog text).
            putCfgStringIfBlank(cfg, "getConfigLabelEnabledTextColor", "setConfigLabelEnabledTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getConfigLabelDisabledTextColor", "setConfigLabelDisabledTextColor", "#FFA0A0A0");
            putCfgStringIfBlank(cfg, "getColorForConfigHeaderTextColor", "setColorForConfigHeaderTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getColorForConfigPanelDescriptionText", "setColorForConfigPanelDescriptionText", "#FF808080");
            putCfgStringIfBlank(cfg, "getColorForPanelHeaderForeground", "setColorForPanelHeaderForeground", "#FF000000");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsNormalState", "setColorForScrollbarsNormalState", "#ffD7E7F0");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsMouseOverState", "setColorForScrollbarsMouseOverState", "#ffABC7D8");
            // Text only — do not force speed-meter graph colours (leave JD stock green).
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterText", "setColorForSpeedMeterText", "#FF222222");
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterAverageText", "setColorForSpeedMeterAverageText", "#FF222222");
            // Latch only when all five progress colours are actually present — otherwise retry.
            Class<?> cfgClz = cfg.getClass();
            for (int i = 1; i <= 5; i++) {
                Object cur = cfgClz.getMethod("getColorForProgressbarForeground" + i).invoke(cfg);
                if (cur == null || String.valueOf(cur).trim().isEmpty()) {
                    return;
                }
            }
            classicLafColorsSeeded = true;
            System.out.println("[jd-dialog-agent] seeded classic LAF progress/text/scrollbar colors (NPE/grey-text guard)");
        } catch (Throwable t) {
            // keep retrying until LAFOptions + cfg are usable
        }
    }

    private static void putCfgStringIfBlank(Object cfg, String getter, String setter, String value) {
        try {
            Class<?> cfgClz = cfg.getClass();
            Object cur = cfgClz.getMethod(getter).invoke(cfg);
            if (cur == null || String.valueOf(cur).trim().isEmpty()) {
                cfgClz.getMethod(setter, String.class).invoke(cfg, value);
            }
        } catch (Throwable ignore) { /* optional keys */ }
    }

    // --- BUG 3: progress-bar render-time defaults (bars grey out on scroll) -----------
    // The download-list RendererProgressBars are rubber-stamp fields with a no-op repaint()
    // and a final renderer entry point, so a 400 ms tick that sets their colour loses the
    // scroll race for freshly-stamped cells. FlatProgressBarUI paints fill=getForeground()/
    // track=getBackground(), falling back to the ProgressBar.* UIManager keys whenever a bar
    // still holds the UIResource default. So set those keys via UIManager.put (DEVELOPER
    // overrides -> outrank the LAF defaults AND survive setLookAndFeel; getDefaults().put in
    // enforceDarkChrome did NOT and got wiped by every LAF reinstall) using the SAME light
    // fill the registered FlatDarkLaf.properties already intends (@accentBaseColor #c6c6c6).
    // Now the render-time fallback is always our light fill, so losing the retint race just
    // shows the same colour instead of a grey flash. recolorBarFields stays as a per-instance
    // belt-and-suspenders layer and uses the SAME light fill (BAR_FILL) so they can't disagree.
    static void installProgressBarDefaults() {
        if (wantDarkLaf() && !isHighlighter() && !DESKTOP) {
            UIManager.put("ProgressBar.foreground",          new ColorUIResource(0xc6, 0xc6, 0xc6)); // light fill
            UIManager.put("ProgressBar.background",          new ColorUIResource(0x26, 0x26, 0x26)); // dark track
            UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(0x16, 0x16, 0x16)); // % over the fill (dark on light)
            UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over the track (white on dark)
        } else {
            // Light/classic: clear developer overrides so native LAF renders correctly
            UIManager.put("ProgressBar.foreground",          null);
            UIManager.put("ProgressBar.background",          null);
            UIManager.put("ProgressBar.selectionForeground", null);
            UIManager.put("ProgressBar.selectionBackground", null);
        }
    }

    private static boolean lafListenerAdded = false;
    /** Re-assert the progress defaults + instance colours the instant JD (or our own
     *  applyCustomDefaults) reinstalls the LAF, so a reinstall never reverts the fill to
     *  FlatLaf's stock accent for freshly-scrolled cells. Registered once; the handler only
     *  re-applies colours (never setLookAndFeel), so it cannot recurse. */
    static void ensureLafChangeListener() {
        if (lafListenerAdded) return;
        lafListenerAdded = true;
        UIManager.addPropertyChangeListener(evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                if (DESKTOP) desktopFlatLaf = isFlatDark(UIManager.getLookAndFeel());
                SwingUtilities.invokeLater(() -> {
                    installProgressBarDefaults();
                    if (wantDarkLaf()) retintProgressBars();
                });
            }
        });
    }

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
    static void enforceDarkChrome() {
        if (!wantDarkLaf()) return;
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
        // jd-highlighter brings its whole palette in the template, so it has nothing to remap; darkening
        // its greys would sink the dialog surface and the scrollbar thumb into the base colour.
        List<Object> keys = isHighlighter() ? new ArrayList<>() : new ArrayList<>(d.keySet());
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
        installProgressBarDefaults();
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
    static javax.swing.Icon boxIcon(final boolean plus) {
        return new javax.swing.plaf.IconUIResource(new javax.swing.Icon() {
            public int getIconWidth()  { return 11; }
            public int getIconHeight() { return 11; }
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                g.setColor(PAL_MONO);
                g.drawRect(x, y, 10, 10);
                g.drawLine(x + 3, y + 5, x + 7, y + 5);            // horizontal bar
                if (plus) g.drawLine(x + 5, y + 3, x + 5, y + 7);  // vertical -> plus
            }
        });
    }

    // jdownloader-theme.sh renders the accent into the defaults as "@accentColor = #rrggbb".
    // UIManager is no source for it, because enforceDarkChrome greys a blue accent there.
    private static final java.io.File RENDERED_DEFAULTS =
            new java.io.File(DEFAULTS_DIR, "FlatDarkLaf.properties");
    private static Color   accentCache = null;
    private static boolean accentRead  = false;
    static Color accentColor() {
        if (accentRead) return accentCache;
        String hex = null;
        try {
            for (String line : java.nio.file.Files.readAllLines(RENDERED_DEFAULTS.toPath())) {
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

    /** Text colour on the accent, taken from the selected tab foreground. */
    static Color accentFg() {
        Color sel = UIManager.getColor("TabbedPane.selectedForeground");
        if (sel != null) return new Color(sel.getRGB());
        Color a = accentColor();
        double lum = 0.299 * a.getRed() + 0.587 * a.getGreen() + 0.114 * a.getBlue();
        return lum > 140 ? PAL_BASE : PAL_TEXT;
    }
}
