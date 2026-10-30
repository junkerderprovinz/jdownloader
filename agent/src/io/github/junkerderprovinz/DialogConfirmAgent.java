package io.github.junkerderprovinz;

import javax.swing.JComponent;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

import static io.github.junkerderprovinz.BytecodePatches.*;
import static io.github.junkerderprovinz.Icons.*;
import static io.github.junkerderprovinz.InstallerDialogs.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.PlainDark.*;
import static io.github.junkerderprovinz.Settings.*;
import static io.github.junkerderprovinz.SpeedMeter.*;
import static io.github.junkerderprovinz.Tables.*;

/**
 * JVM agent for the JDownloader container, loaded through JAVA_TOOL_OPTIONS. It confirms the
 * installer dialogs JD forces whenever its window is visible, which no config can suppress,
 * and darkens the window chrome FlatLaf paints from its own UIManager defaults, out of reach
 * of JD's colorfor* settings. It keeps running, so it also covers the JVM after a self-update.
 */
public class DialogConfirmAgent {

    // The jd-highlighter desktop installer sets jd.highlighter.home to <JD>/.jd-highlighter.
    // There JD's dialogs are left to the user and no container markers are written.
    static final String HL_HOME = System.getProperty("jd.highlighter.home", "");
    static final boolean DESKTOP = !HL_HOME.isEmpty();
    static final java.io.File JD_HOME = DESKTOP
            ? new java.io.File(HL_HOME).getAbsoluteFile().getParentFile()
            : new java.io.File("/config/JDownloader");

    // jd-highlighter polish for what JD hardcodes and no theme key reaches. Plain Dark
    // keeps stock JD, so every hook checks isHighlighter() first and leaves JD as it was
    // on any failure. The desktop install is always jd-highlighter.

    static final boolean HIGHLIGHTER = DESKTOP
            || System.getenv().getOrDefault("JD_THEME", "").toLowerCase(java.util.Locale.ROOT).contains("highlight");

    // A desktop JD can still run another look and feel (FlatLaf arrives with JD's own
    // design download), and the polish on top of it would leave a half-themed window.
    // tick() records the active one; the container always runs FlatLaf here.
    static volatile boolean desktopFlatLaf = false;

    // Carbon greyscale, matching the content palette in jdownloader-theme.sh.
    static final ColorUIResource BG     = new ColorUIResource(0x16, 0x16, 0x16);
    static final ColorUIResource HEADER = new ColorUIResource(0x0b, 0x0b, 0x0b);
    static final ColorUIResource SEL    = new ColorUIResource(0x52, 0x52, 0x52);

    // Neutral greys of the theme. The template .properties and the JD colorfor* JSON mirror these
    // values; the accent is the one configurable colour and is read from the LAF at runtime.
    static final Color PAL_BASE      = new Color(0x16, 0x16, 0x16); // window chrome
    static final Color PAL_FIELD     = new Color(0x1a, 0x1a, 0x1a); // input fill
    static final Color PAL_HEADER    = new Color(0x1e, 0x1e, 0x1e); // table and section headers
    static final Color PAL_TRACK     = new Color(0x26, 0x26, 0x26); // progress track, unselected tile
    static final Color PAL_SURFACE   = new Color(0x24, 0x24, 0x24); // cards, dialogs, chips, lists
    static final Color PAL_BUTTON    = new Color(0x2a, 0x2a, 0x2a); // buttons, menu fields
    static final Color PAL_DIVIDER   = new Color(0x39, 0x39, 0x39); // scrollbar thumb, dividers
    static final Color PAL_SELECTION = new Color(0x52, 0x52, 0x52); // row selection
    static final Color PAL_MONO      = new Color(0xb0, 0xb0, 0xb0); // mono icons
    static final Color PAL_DISABLED  = new Color(0x6f, 0x6f, 0x6f); // disabled text
    static final Color PAL_TEXT      = new Color(0xf4, 0xf4, 0xf4); // text, light icons

    // Plain (non-UIResource) colours set directly on the table progress-bar instances so a
    // later updateUI cannot override them. Plain Dark uses the light #c6c6c6 of the
    // registered FlatDarkLaf.properties, the same value installProgressBarDefaults puts
    // into UIManager, so the two never disagree into a grey flash on scroll.
    static final Color BAR_FILL  = new Color(0xc6, 0xc6, 0xc6);
    static final Color BAR_TRACK = PAL_TRACK;

    // Corner radius shared by every rounded element of the theme. RoundRectangle2D and fillRoundRect
    // take the diameter, hence UI_ARC.
    static final int UI_RADIUS = 8;
    static final int UI_ARC    = 2 * UI_RADIUS;

    // Markers for the autostart READY gate: the agent's PID and the class of the look and feel
    // that is actually applied, which no file on disk can tell.
    private static final java.io.File PID_FILE        = new java.io.File("/tmp/.jd-agent.pid");
    private static final java.io.File LAF_FILE        = new java.io.File("/tmp/.jd-laf-applied");
    private static String lastLafWritten = null;
    private static int    lafTick        = 0;

    // When JD restarts itself it copies -javaagent into the new command line, and the inherited
    // JAVA_TOOL_OPTIONS loads the agent a second time. Two instances patch every class twice.
    private static boolean started;
    static Instrumentation INSTRUMENTATION;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (started) return;
        started = true;
        if (wantClassicLaf()) {
            System.out.println("[jd-dialog-agent] watching for installer dialogs (classic JDDEFAULT, no FlatLaf dark chrome)");
        } else if (wantDarkLaf()) {
            System.out.println("[jd-dialog-agent] watching for installer dialogs + enforcing dark chrome");
        } else {
            System.out.println("[jd-dialog-agent] watching for installer dialogs (Flat Light, no dark chrome remap)");
        }
        INSTRUMENTATION = inst;
        // The guards for AppWork's CircledProgressBar UI and jsyntaxpane's ScriptAction have to be
        // in place before JD loads those classes, or the Event Scripter editor breaks under FlatLaf.
        installBytecodeGuards(inst);
        if (!wantClassicLaf()) {
            exposeFlatlafToSystemLoader();
            // premain runs before JD's main(), so the icons are back before the GUI resolves them.
            restoreExpanderIcons();
            // Set up before JD installs its LAF, so the light progress fill holds from the first
            // paint and after every reinstall.
            ensureLafChangeListener();
            installProgressBarDefaults();
        } else {
            exposeSyntheticaToSystemLoader();
        }
        // The runtime name is pid@host. ProcessHandle would be shorter but needs Java 9, and the desktop
        // build targets Java 8.
        if (!DESKTOP) writeFile(PID_FILE, java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        Thread t = new Thread(DialogConfirmAgent::watch, "jd-dialog-agent");
        t.setDaemon(true);
        t.start();
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(400);
                SwingUtilities.invokeAndWait(TICK_TIMING ? DialogConfirmAgent::timedTick : DialogConfirmAgent::tick);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // ignore Swing-side exceptions and keep watching
            }
        }
    }

    // -Djd.agent.tickTiming=true logs the average cost of a tick and of each jd-highlighter step
    // every 25 ticks (10 s).
    private static final boolean TICK_TIMING = Boolean.getBoolean("jd.agent.tickTiming");
    private static long tickNanos;
    private static int  timedTicks;

    private static void timedTick() {
        long start = System.nanoTime();
        tick();
        tickNanos += System.nanoTime() - start;
        if (++timedTicks < 25) return;
        System.out.println(String.format(java.util.Locale.ROOT, "[jd-dialog-agent] tick %.3f ms average over %d ticks, %s",
                tickNanos / 1e6 / timedTicks, timedTicks, HighlighterPass.report()));
        tickNanos = 0;
        timedTicks = 0;
    }

    private static void tick() {
        if (wantClassicLaf()) {
            exposeSyntheticaToSystemLoader();
            seedClassicLafColors();
            handleDialogs();
            // Classic still gets the compact-toolbar / speed-graph growth.
            widenSpeedEditors();
            growSpeedMeter();
            replaceSpeedGraph();
        } else {
            // The container fixes are made for FlatLaf Dark, so a desktop in another look and feel
            // is left alone entirely.
            if (DESKTOP) {
                desktopFlatLaf = isFlatDark(UIManager.getLookAndFeel());
                if (!desktopFlatLaf) return;
            }
            exposeFlatlafToSystemLoader();
            restoreExpanderIcons();
            if (!isHighlighter()) healPackageToggle();   // rebuilds FileColumn's cached [+]/[-] toggle icons
            if (!DESKTOP) handleDialogs();
            registerDefaultsSource();     // FlatDarkLaf.properties or FlatLightLaf.properties
            applyCustomDefaults();
            // On FlatLightLaf the dark chrome would leave black panels.
            if (wantDarkLaf()) {
                enforceDarkChrome();
                if (!isHighlighter()) themeKaynExtras();        // menu-toggle check and overview corner icons
                retintProgressBars();
            }
            widenSpeedEditors();
            growSpeedMeter();
            replaceSpeedGraph();
            if (isHighlighter()) HighlighterPass.run(TICK_TIMING);
        }
        if (++lafTick >= 12) {   // every ~5s (ticks run every 400ms)
            lafTick = 0;
            if (!DESKTOP) writeLafMarker();
            if (GEO_DEBUG) dumpGeometry();
        }
    }

    /** Records the active look-and-feel class for the READY gate. */
    private static void writeLafMarker() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null) return;
        String cn = laf.getClass().getName();
        if (!cn.equals(lastLafWritten) || !LAF_FILE.exists()) {
            writeFile(LAF_FILE, cn);
            lastLafWritten = cn;
        }
    }

    static void writeFile(java.io.File f, String content) {
        try {
            java.nio.file.Files.write(f.toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // the markers are best effort, the launcher has fallbacks
        }
    }

    /** Classic official JD look (Synthetica DEFAULT). Skip FlatLaf dark-chrome work. */
    static boolean wantClassicLaf() {
        String t = System.getenv("JD_THEME");
        if (t == null || t.trim().isEmpty()) return false;
        return "jddefault".equals(t.trim().toLowerCase());
    }

    /** Carbon #161616 chrome / dark progress retints are Dark-only. Light keeps FlatLight. */
    static boolean wantDarkLaf() {
        if (wantClassicLaf()) return false;
        String t = System.getenv("JD_THEME");
        if (t == null || t.trim().isEmpty()) return true; // container default = Dark
        String n = t.trim().toLowerCase();
        if ("light".equals(n) || "jd_plain".equals(n)) return false;
        if (n.contains("dark")) return true;
        return true;
    }

    static boolean isHighlighter() { return HIGHLIGHTER && (!DESKTOP || desktopFlatLaf); }

    static boolean isFlatDark(LookAndFeel laf) {
        try {
            return laf != null && laf.getClass().getName().startsWith("com.formdev.flatlaf.")
                    && Boolean.TRUE.equals(laf.getClass().getMethod("isDark").invoke(laf));
        } catch (Throwable t) { return false; }
    }

    static Object fieldValue(Object o, Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) { return null; }
    }
    static void setField(Object o, String name, Object value) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException next) {
            } catch (Throwable t) {
                return;
            }
        }
    }

    // Entry points for the patched JD and FlatLaf classes, which call them by name from other packages.
    // They have to stay public static on this public class.
    public static int tabGap() { return isHighlighter() ? TAB_GAP : 0; }

    private static final java.util.Map<String, javax.swing.Icon> CHROME_CLEAN =
            new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();
    /** Called from NewTheme.getIcon; replaces a few chrome glyphs and passes everything else through. */
    public static javax.swing.Icon cleanChromeIcon(javax.swing.Icon original, String key, int size) {
        try {
            if (original == null || key == null || !isHighlighter()) return original;
            String k = key.toLowerCase();
            String tab; Color tint;
            if (k.contains("lock") || k.contains("columnbutton") || k.contains("widthlocked")) {
                tab = "lock"; tint = EXPANDER_LIGHT;
            } else if (k.equals("extract")) {
                tab = "extract"; tint = SIDEBAR_TEXT;
            } else if (k.equals("search") || k.equals("image")) {
                // Search fields and the hoster chooser's logo column paint a fresh AbstractIcon, which
                // nothing else reaches.
                tab = k; tint = SIDEBAR_TEXT;
            } else {
                return original;
            }
            int w = original.getIconWidth() > 0 ? original.getIconWidth() : size;
            int h = original.getIconHeight() > 0 ? original.getIconHeight() : size;
            int s = Math.min(w, h); if (s <= 0) s = Math.max(w, h);
            String ck = tab + "@" + s;
            javax.swing.Icon cached = CHROME_CLEAN.get(ck);
            if (cached != null) return cached;
            javax.swing.Icon base = tablerBase(tab, s, s);
            javax.swing.Icon clean = (base != null) ? tintIcon(base, tint, null) : tintSolid(original, tint);
            CHROME_CLEAN.put(ck, clean);
            return clean;
        } catch (Throwable t) { return original; }
    }

    /** Called from the patched FavIcons.createDefaultFavIcon. JD letters the stand-in logo of a hoster
     *  without a favicon in black on the panel header colour, which is dark here, so the letters turn
     *  light and the tile a step lighter than the cards. */
    public static java.awt.Image lightFavIcon(java.awt.Image img) {
        try {
            if (!isHighlighter() || !(img instanceof java.awt.image.BufferedImage)) return img;
            java.awt.image.BufferedImage src = (java.awt.image.BufferedImage) img;
            int w = src.getWidth(), h = src.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int tile = PAL_SURFACE.getRed(), text = SIDEBAR_TEXT.getRed(), lit = 0x33;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y), a = p >>> 24;
                if (a == 0) continue;
                // The letters are black blended into the tile, so their share is how far below the tile they are.
                float ink = Math.max(0f, Math.min(1f, (tile - ((p >> 16) & 0xff)) / (float) tile));
                int v = Math.round(lit + (text - lit) * ink);
                out.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
            return out;
        } catch (Throwable t) { return img; }
    }

    private static volatile java.lang.reflect.Method favIconCall;
    private static volatile boolean logoRepaintQueued;
    /** Called from the patched DomainInfo.setFavIcon; repaints the open windows once per batch of logos. */
    public static void favIconArrived() {
        if (logoRepaintQueued || !isHighlighter()) return;
        logoRepaintQueued = true;
        SwingUtilities.invokeLater(() -> {
            logoRepaintQueued = false;
            for (java.awt.Window w : java.awt.Window.getWindows()) if (w.isShowing()) w.repaint();
        });
    }

    /** Called from patched GUI classes in place of DomainInfo.getFavIcon(fetch). */
    public static javax.swing.Icon chooserFavIcon(Object domainInfo, boolean fetch) {
        try {
            java.lang.reflect.Method m = favIconCall;
            if (m == null) {
                favIconCall = m = domainInfo.getClass().getMethod("getFavIcon", boolean.class);
                // ASM loads DomainInfo itself while it patches the first caller, and the JVM does not run
                // a transformer for classes loaded inside one, so the arrival hook is added afterwards.
                if (INSTRUMENTATION != null && INSTRUMENTATION.isModifiableClass(domainInfo.getClass()))
                    INSTRUMENTATION.retransformClasses(domainInfo.getClass());
            }
            javax.swing.Icon ic = (javax.swing.Icon) m.invoke(domainInfo, fetch || isHighlighter());
            if (ic != null) SITE_LOGOS.add(ic);
            return ic;
        } catch (Throwable t) { return null; }
    }
    /** Logos handed out for hosters, which keep their colours in every renderer. */
    static final java.util.Set<javax.swing.Icon> SITE_LOGOS =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<javax.swing.Icon, Boolean>()));

    /** Called from the patched CustomizeableActionBar.createDropdownImage: the glyph in the text colour and
     *  a small chevron in place of JD's arrow. */
    public static javax.swing.ImageIcon monoDropdownIcon(javax.swing.ImageIcon orig, boolean up, java.awt.Image glyph) {
        try {
            if (orig == null || glyph == null || !isHighlighter()) return orig;
            int w = orig.getIconWidth(), h = orig.getIconHeight();
            int gw = glyph.getWidth(null), gh = glyph.getHeight(null);
            if (w <= gw || h <= 0 || gw <= 0 || gh <= 0) return orig;
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            tintSolid(new javax.swing.ImageIcon(glyph), SIDEBAR_TEXT).paintIcon(null, g, 0, (h - gh) / 2);
            g.setColor(SIDEBAR_TEXT);
            g.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            int cx = gw + (w - gw) / 2, cy = h / 2, d = 2, tip = up ? -1 : 1;
            g.drawPolyline(new int[] { cx - d, cx, cx + d }, new int[] { cy - tip, cy + tip, cy - tip }, 3);
            g.dispose();
            return new javax.swing.ImageIcon(out);
        } catch (Throwable t) { return orig; }
    }

    // JD's status rings (update check, extraction, link crawler) are CircledProgressBars that paint
    // coloured animated glyphs, and the update globe draws through a custom path that cannot be
    // recoloured. The patched BasicCircleProgressBarUI.paint calls this to draw an accent arc instead:
    // nothing while idle, a spinner when indeterminate, a filling arc otherwise.
    public static boolean paintCleanRing(java.awt.Graphics g0, Component c) {
        try {
            if (!isHighlighter() || !(g0 instanceof Graphics2D) || c == null) return false;
            Object model = null; boolean indet = false;
            try { model = c.getClass().getMethod("getModel").invoke(c); } catch (Throwable ig) { }
            try { Object b = c.getClass().getMethod("isIndeterminate").invoke(c); indet = Boolean.TRUE.equals(b); } catch (Throwable ig) { }
            int val = 0, min = 0, max = 0;
            if (model != null) {
                try {
                    val = (Integer) model.getClass().getMethod("getValue").invoke(model);
                    min = (Integer) model.getClass().getMethod("getMinimum").invoke(model);
                    max = (Integer) model.getClass().getMethod("getMaximum").invoke(model);
                } catch (Throwable ig) { }
            }
            boolean active = c.isEnabled() && (indet || (val > min && val < max));
            Graphics2D g = (Graphics2D) g0;
            int w = c.getWidth(), h = c.getHeight();
            int d = Math.min(w, h) - 5;
            if (d < 6) return true;
            int x = (w - d) / 2, y = (h - d) / 2;
            if (active) {
                Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                java.awt.Stroke os = g.getStroke();
                g.setStroke(new java.awt.BasicStroke(Math.max(1.6f, d / 9f),
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g.setColor(PAL_DIVIDER);
                g.drawOval(x, y, d, d);
                g.setColor(accentColor());
                if (indet) {
                    int start = (int) ((System.currentTimeMillis() / 4) % 360);
                    g.drawArc(x, y, d, d, 90 - start, -110);
                } else {
                    int extent = (int) (360.0 * (val - min) / Math.max(1, max - min));
                    g.drawArc(x, y, d, d, 90, -extent);
                }
                g.setStroke(os);
                if (aa != null) g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, aa);
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    // Called from the patched status-column getIcon. Classifies the glyph the column is about to return and
    // swaps in a mono Tabler icon of the same meaning; an unknown glyph is tinted mono instead.
    private static final java.util.Map<javax.swing.Icon, String> STATUS_CLASS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, String>());
    private static final java.util.Map<String, javax.swing.Icon> STATUS_CLEAN =
            new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();

    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> STATUS_FLIP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, javax.swing.Icon>());

    public static javax.swing.Icon cleanTaskIcon(Object owner, javax.swing.Icon original) {
        try {
            if (original == null || !isHighlighter()) return original;
            String key = STATUS_CLASS.get(original);
            if (key == null) { key = classifyStatus(owner, original); STATUS_CLASS.put(original, key == null ? "" : key); }
            int w = original.getIconWidth(), h = original.getIconHeight();
            if (w <= 0 || h <= 0) return original;
            if (key == null || key.isEmpty()) {
                javax.swing.Icon fl = STATUS_FLIP.get(original);
                if (fl == null) { fl = new RowFlipIcon(tintSolid(original, SIDEBAR_TEXT), tintSolid(original, accentFg()));
                    STATUS_FLIP.put(original, fl); }
                return fl;
            }
            // Tabler glyphs are square and JD's package icon is not, so the glyph uses the shorter side.
            int s = Math.min(w, h); if (s <= 0) s = Math.max(w, h);
            String ck = key + "@" + s;
            javax.swing.Icon clean = STATUS_CLEAN.get(ck);
            if (clean == null) {
                javax.swing.Icon base = tablerBase(key, s, s);
                // The hovered row has an accent background with dark text, so the glyph turns dark there too.
                javax.swing.Icon light = (base != null) ? tintIcon(base, SIDEBAR_TEXT, null) : tintSolid(original, SIDEBAR_TEXT);
                javax.swing.Icon dark  = (base != null) ? tintIcon(base, accentFg(), null)   : tintSolid(original, accentFg());
                clean = new RowFlipIcon(light, dark);
                STATUS_CLEAN.put(ck, clean);
            }
            return clean;
        } catch (Throwable t) { return original; }
    }

    // One wrapper per ExtColumn renderer, so the getCellRenderer hook does not allocate on every paint.
    private static final java.util.Map<javax.swing.table.TableCellRenderer, javax.swing.table.TableCellRenderer> EXT_WRAP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.table.TableCellRenderer, javax.swing.table.TableCellRenderer>());
    /** Called from the patched ExtTable.getCellRenderer; wraps the renderer only for tables the tick marked
     *  as config panels. */
    public static javax.swing.table.TableCellRenderer wrapExtCellRenderer(Object table, javax.swing.table.TableCellRenderer r) {
        try {
            if (r == null || r instanceof MonoIconRenderer || !isHighlighter()) return r;
            if (!(table instanceof JComponent)) return r;
            Object cfg = ((JComponent) table).getClientProperty("jdp.extCfg");
            if (!(cfg instanceof Boolean) || !((Boolean) cfg)) return r;
            String cn = r.getClass().getName().toLowerCase();
            if (cn.contains("favicon") || cn.contains("hoster") || cn.contains("domain")) return r;
            javax.swing.table.TableCellRenderer w = EXT_WRAP.get(r);
            if (w == null) { w = new MonoIconRenderer(r); EXT_WRAP.put(r, w); }
            return w;
        } catch (Throwable ig) { return r; }
    }

    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> SEARCH_MONO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, javax.swing.Icon>());
    /** Called from the patched LinktablesSearchCategory.getIcon. */
    public static javax.swing.Icon monoSearchIcon(javax.swing.Icon ic) {
        try {
            if (ic == null || !isHighlighter()) return ic;
            javax.swing.Icon m = SEARCH_MONO.get(ic);
            if (m == null) { m = tablerIcon(ic, SIDEBAR_TEXT, null); SEARCH_MONO.put(ic, m); }
            return m;
        } catch (Throwable t) { return ic; }
    }
}
