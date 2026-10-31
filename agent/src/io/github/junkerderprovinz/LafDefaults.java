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

    private static boolean chromeDone  = false;
    private static int     stableTicks = 0;

    // The colour overrides go in through FlatLaf's registerCustomDefaultsSource rather than a
    // patched flatlaf.jar, so JD's integrity check and self-update leave them alone.
    static final java.io.File DEFAULTS_DIR = DESKTOP
            ? new java.io.File(HL_HOME, "flatlaf-defaults")
            : new java.io.File("/opt/JDownloader/flatlaf-defaults");

    private static volatile boolean defaultsRegistered = false;
    private static volatile boolean flatLafLoadHookFired = false;
    private static boolean lafRefreshDone     = false;
    private static int     lafStableTicks     = 0;
    private static int     registrationWait   = 0;
    private static int     classScanTicks     = 0;

    /**
     * JD's launcher wires flatlaf.jar only into its own JDLauncherClassLoader, but
     * UIManager.setLookAndFeel(String) loads the LAF class through the system class loader,
     * so the jar is appended to the system search too. The launcher loader is parent-first,
     * so JD still resolves a single copy of each class.
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
            // A truncated jar throws here and is tried again on a later tick.
            INSTRUMENTATION.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(FLATLAF_JAR));
            flatlafExposed = true;
            System.out.println("[jd-dialog-agent] appended flatlaf.jar to the system classloader (LAF-by-name can resolve now)");
        } catch (Throwable ignore) { }
    }

    /** The same class loader fix for Synthetica. With synthetica.jar alone JD falls back to
     *  Metal, so syntheticaJDCustom.jar and every other synthetica jar go in as well. */
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

    /** True once JD's main window, not a splash, is showing. */
    private static boolean mainWindowShowing() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400) return true;
        }
        return false;
    }

    /**
     * Registers DEFAULTS_DIR as a custom defaults source through the class loader that loaded
     * FlatLaf. When this beats JD's setLookAndFeel, the first frame already has our colours.
     */
    static void registerDefaultsSource() {
        if (defaultsRegistered || !DEFAULTS_DIR.isDirectory()) return;
        Class<?> flatLaf = null;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf != null && laf.getClass().getName().toLowerCase().contains("flat")) {
            try {
                flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            } catch (Throwable ignore) { }
        }
        // getAllLoadedClasses() is costly on the EDT, so scan every 4th tick and stop after
        // about five minutes; an installed FlatLaf loads within seconds.
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
            // An old FlatLaf without the API still gets a dark chrome from enforceDarkChrome().
            defaultsRegistered = true;
            lafRefreshDone     = true;
            System.out.println("[jd-dialog-agent] registerCustomDefaultsSource unavailable ("
                    + e.getClass().getSimpleName() + "): legacy chrome remap only");
        }
    }

    /**
     * Registers the defaults as soon as the class-load transformer sees FlatLaf defined, well
     * before JD calls setLookAndFeel. It runs on its own thread because loading FlatLaf on the
     * defining thread would recurse into the definition still in progress.
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
                            + e.getClass().getSimpleName() + "), tick-loop registration will retry");
                }
            }
        }, "jd-laf-early-register");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Makes the registered defaults take effect once. When registration beat JD's
     * setLookAndFeel, Panel.background is already #161616 and nothing happens. Otherwise
     * a fresh instance of the current LAF is applied, before the GUI exists where possible
     * and else once the main window is stable, the same gate enforceDarkChrome() uses.
     */
    static void applyCustomDefaults() {
        if (lafRefreshDone) return;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        if (!wantDarkLaf()) {
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] non-dark LAF (" + laf.getClass().getSimpleName()
                    + "): skipping dark custom-defaults re-apply");
            return;
        }

        if (defaultsRegistered) {
            Color bg = UIManager.getColor("Panel.background");
            if (wantDarkLaf() && bg != null && (bg.getRGB() & 0xFFFFFF) == (PAL_BASE.getRGB() & 0xFFFFFF)) {
                lafRefreshDone = true;
                System.out.println("[jd-dialog-agent] custom defaults active from first paint (no re-apply needed)");
                return;
            }

            // Before JD builds its GUI, applying the LAF again only swaps the defaults. On a
            // live frame it needs updateUI, which AppWork's components do not survive cleanly
            // (JD itself restarts to change the LAF).
            if (Frame.getFrames().length == 0) {
                try {
                    UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
                    System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                            + " with custom defaults (pre-GUI, no components yet)");
                } catch (Throwable e) {
                    System.out.println("[jd-dialog-agent] pre-GUI LAF re-apply failed ("
                            + e.getClass().getSimpleName() + "): legacy chrome remap only");
                }
                lafRefreshDone = true;
                return;
            }
        }

        if (!mainWindowShowing()) { lafStableTicks = 0; return; }
        if (++lafStableTicks < 4) return;   // ~1.6 s after the main frame shows

        if (!defaultsRegistered) {
            // Give the class scan about 30 s of stable GUI before settling for the remap.
            if (++registrationWait < 75) return;
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] defaults source never registered: legacy chrome remap only");
            return;
        }

        // The pre-GUI window was missed. A live re-apply can leave repaint artefacts in JD's
        // own components, so the log names this path.
        try {
            UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
            Class<?> flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            flatLaf.getMethod("updateUI").invoke(null);
            System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                    + " with custom defaults (live one-shot, missed the pre-GUI window)");
        } catch (Throwable e) {
            System.out.println("[jd-dialog-agent] LAF re-apply failed ("
                    + e.getClass().getSimpleName() + "): legacy chrome remap only");
        }
        lafRefreshDone = true;   // one attempt, enforceDarkChrome runs next
    }

    /** Stock classic JD progress-bar gradient (aRGB). The LAFSettings defaults are null, which
     *  makes CustomProgressbarPainter throw in LinearGradientPaint, and the JSON seed does not
     *  always reach LAFOptions in time, so the colours go in through the cfg setters. */
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
                return; // LAFOptions is not ready yet, retry next tick
            }
            if (inst == null) return;
            Object cfg = lafClz.getMethod("getCfg").invoke(inst);
            if (cfg == null) return;
            for (int i = 1; i <= 5; i++) {
                putCfgStringIfBlank(cfg,
                        "getColorForProgressbarForeground" + i,
                        "setColorForProgressbarForeground" + i,
                        CLASSIC_PROGRESS_COLORS[i - 1]);
            }
            // Without these the Update dialog text is all grey.
            putCfgStringIfBlank(cfg, "getConfigLabelEnabledTextColor", "setConfigLabelEnabledTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getConfigLabelDisabledTextColor", "setConfigLabelDisabledTextColor", "#FFA0A0A0");
            putCfgStringIfBlank(cfg, "getColorForConfigHeaderTextColor", "setColorForConfigHeaderTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getColorForConfigPanelDescriptionText", "setColorForConfigPanelDescriptionText", "#FF808080");
            putCfgStringIfBlank(cfg, "getColorForPanelHeaderForeground", "setColorForPanelHeaderForeground", "#FF000000");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsNormalState", "setColorForScrollbarsNormalState", "#ffD7E7F0");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsMouseOverState", "setColorForScrollbarsMouseOverState", "#ffABC7D8");
            // Text only; the speed meter graph keeps JD's stock green.
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterText", "setColorForSpeedMeterText", "#FF222222");
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterAverageText", "setColorForSpeedMeterAverageText", "#FF222222");
            // Latch only once all five progress colours are set.
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
            // keep retrying until LAFOptions and its cfg are usable
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

    // The download list's progress bars are rubber-stamp renderers, so a tick that recolours
    // them loses the race against freshly stamped cells while scrolling. FlatProgressBarUI
    // falls back to the ProgressBar.* keys while a bar holds a UIResource colour, so those keys
    // are set with UIManager.put, which outranks the LAF defaults and survives setLookAndFeel.
    // The fill matches FlatDarkLaf.properties and BAR_FILL, so the layers never disagree.
    static void installProgressBarDefaults() {
        if (wantDarkLaf() && !isHighlighter() && !DESKTOP) {
            UIManager.put("ProgressBar.foreground",          new ColorUIResource(0xc6, 0xc6, 0xc6)); // light fill
            UIManager.put("ProgressBar.background",          new ColorUIResource(0x26, 0x26, 0x26)); // dark track
            UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(0x16, 0x16, 0x16)); // % over the fill (dark on light)
            UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over the track (white on dark)
        } else {
            // Light and classic drop the overrides so the LAF's own colours apply.
            UIManager.put("ProgressBar.foreground",          null);
            UIManager.put("ProgressBar.background",          null);
            UIManager.put("ProgressBar.selectionForeground", null);
            UIManager.put("ProgressBar.selectionBackground", null);
        }
    }

    private static boolean lafListenerAdded = false;
    /** Restores the progress colours whenever the LAF is reinstalled, so freshly scrolled cells
     *  never fall back to FlatLaf's stock accent. The handler does not call setLookAndFeel, so
     *  it cannot recurse. */
    static void ensureLafChangeListener() {
        if (lafListenerAdded) return;
        lafListenerAdded = true;
        UIManager.addPropertyChangeListener(evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                if (DESKTOP) desktopFlatLaf = isThemeLaf(UIManager.getLookAndFeel());
                SwingUtilities.invokeLater(() -> {
                    installProgressBarDefaults();
                    if (wantDarkLaf()) retintProgressBars();
                });
            }
        });
    }

    /**
     * Recolours FlatLaf's UIManager defaults to the #161616 greyscale once per JVM, after JD's
     * main window has been showing for a few ticks. Recreating UI delegates while JD still
     * packs its frame makes AppWork's CircleProgressBarUI throw in addNotify and crashes the
     * GUI into a boot loop.
     */
    static void enforceDarkChrome() {
        if (!wantDarkLaf()) return;
        if (chromeDone) return;
        if (!lafRefreshDone) return;   // a later setLookAndFeel would wipe this remap
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

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
            if (ks.contains("selectionbackground")) {
                d.put(key, withAlpha(SEL, ((Color) val).getAlpha()));
                continue;
            }
            // Text greys lose their blue but keep their brightness, or disabled text would
            // sink into the background.
            boolean isText = ks.contains("foreground") || ks.contains("text")
                    || ks.contains("caret") || ks.contains("accelerator");
            Color rep = remap((Color) val, isText);
            if (rep != null) d.put(key, rep);
        }
        // jd-highlighter keeps its own accent and #1e1e1e header.
        if (!isHighlighter()) {
            d.put("Component.accentColor", SEL);   // FlatLaf derives focus/selection from this
            d.put("TableHeader.background", HEADER);
        }
        // Swing's tree handles in some JD dialogs are drawn dark and vanish on #161616. The
        // package toggle in the download list is no Swing tree and uses the light theme icons.
        d.put("Tree.collapsedIcon", boxIcon(true));    // [+]
        d.put("Tree.expandedIcon", boxIcon(false));    // [-]
        for (String k : new String[] {
                "Tree.icon.expandedColor", "Tree.icon.collapsedColor",
                "Tree.icon.leafColor", "Tree.icon.closedColor", "Tree.icon.openColor" }) {
            d.put(k, new ColorUIResource(PAL_MONO));
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
     * Maps a FlatLaf default colour onto the Carbon greyscale, keeping its alpha. Blue accents
     * become the selection grey and neutral chrome greys are darkened unless isText; for
     * anything else it returns null and the colour stays as it is.
     */
    private static Color remap(Color c, boolean isText) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue(), a = c.getAlpha();
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int bright = (r + g + b) / 3;

        // Mapped by brightness, the accent would come out as a light grey all over the chrome.
        if (b > r + 24 && b > g + 12 && b > 90) {
            return new ColorUIResource(new Color(SEL.getRed(), SEL.getGreen(), SEL.getBlue(), a));
        }
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
            new java.io.File(DEFAULTS_DIR, LIGHT ? "FlatLightLaf.properties" : "FlatDarkLaf.properties");
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
            String stored = Appearance.storedAccent();
            hex = stored != null ? stored : Appearance.DEFAULT_ACCENT;
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
        return lum > 140 ? INK_DARK : INK_LIGHT;
    }

    /** The accent as hover text or glyph on the surface. A light accent does not read on the light
     *  palette's white cards, so there it is darkened. */
    static Color hoverInk() {
        Color a = accentColor();
        if (!LIGHT || INK_LIGHT.equals(accentFg())) return a;
        return new Color(a.getRed() * 55 / 100, a.getGreen() * 55 / 100, a.getBlue() * 55 / 100);
    }
}
