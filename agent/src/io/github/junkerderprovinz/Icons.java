package io.github.junkerderprovinz;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;

import static io.github.junkerderprovinz.Cards.*;
import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.LafDefaults.*;
import static io.github.junkerderprovinz.Menus.*;
import static io.github.junkerderprovinz.Settings.*;
import static io.github.junkerderprovinz.Tables.*;

/** Tabler icon lookup, tinting and the caches behind them. */
final class Icons {

    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> SCALED_ICONS = new java.util.WeakHashMap<>();

    // A disabled ExtButton paints a disabledIcon that JD derives from the original glyph, so it needs its
    // own tinted version.
    static final Color DISABLED_TONE = PAL_DISABLED;

    private static final java.util.Map<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>> TINTS =
            new java.util.WeakHashMap<>();
    /**
     * A single-tone silhouette of an icon, cached per tone. JD caches its Icon objects at startup, so
     * recolouring the SVG files is not enough; this runs in the render path instead.
     */
    static javax.swing.Icon tintIcon(javax.swing.Icon orig, Color tint, Component c) {
        try {
            if (tint == null) return orig;
            synchronized (TINTS) {
                java.util.Map<Integer, javax.swing.Icon> tones = TINTS.get(orig);
                javax.swing.Icon cached = tones != null ? tones.get(tint.getRGB()) : null;
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
            synchronized (TINTS) { TINTS.computeIfAbsent(orig, k -> new java.util.HashMap<>(4)).put(tint.getRGB(), ti); }
            return ti;
        } catch (Throwable t) { return orig; }
    }

    // JD's chrome glyphs are replaced with Tabler Icons (MIT), shipped as light PNGs named after the JD
    // icon key in a few sizes. The key comes from getIdentifier().getKey(), since wrapper icons lack a
    // plain getKey(). Icons without a key or a matching PNG get a mono tint of their own glyph.
    private static final String TABLER_DIR = DESKTOP
            ? new java.io.File(HL_HOME, "jd-tabler/png").getPath() : "/opt/jd-tabler/png";
    private static final int[] TABLER_SIZES = { 16, 18, 20, 24, 32 };
    private static final Object TABLER_NONE = new Object();
    private static final java.util.Map<String, Object> TABLER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // iconKey runs on every sidebar paint, and JD caches its icon objects, so the reflective lookup is
    // cached per icon. KEY_NONE marks icons without a name.
    private static final java.util.Map<javax.swing.Icon, String> KEY_CACHE = new java.util.WeakHashMap<>();
    private static final String KEY_NONE = "\u0000noKey";

    /** The JD icon's name, such as "reconnect" or "logo/myjdownloader", or null. Merged and scaled icons
     *  report no key, so the first of their parts that has one is used. */
    static String iconKey(javax.swing.Icon ic) {
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
        try {
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

    /** The Tabler icon for a JD key at w x h, scaled from the nearest shipped size. */
    static javax.swing.Icon tablerBase(String key, int w, int h) {
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

    /** The Tabler twin of a JD glyph in the given tone, or a tinted copy of the glyph when there is none. */
    static javax.swing.Icon tablerIcon(javax.swing.Icon orig, Color tint, Component c) {
        try {
            javax.swing.Icon base = tablerBase(iconKey(orig), orig.getIconWidth(), orig.getIconHeight());
            if (base != null) return tintIcon(base, tint, c);
        } catch (Throwable ignore) { }
        return tintIcon(orig, tint, c);
    }

    static javax.swing.Icon scaleIconTo(javax.swing.Icon ic, int size) {
        try {
            int iw = Math.max(1, ic.getIconWidth()), ih = Math.max(1, ic.getIconHeight());
            java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D sg = src.createGraphics();
            ic.paintIcon(null, sg, 0, 0);
            sg.dispose();
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, size, size, null);
            g.dispose();
            return new javax.swing.ImageIcon(dst);
        } catch (Throwable t) { return ic; }
    }

    // Tinting and scaling on every paint makes the sidebar hover sluggish, so results are cached per tone.
    private static final java.util.Map<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>> SB_ICON_CACHE = new java.util.WeakHashMap<>();
    /** Sidebar tile icon at 32px, the largest shipped Tabler size, since upscaling a PNG blurs it.
     *  {@code factor} is unused. */
    static javax.swing.Icon tablerIconScaled(javax.swing.Icon orig, Color tint, Component c, double factor) {
        Integer tkey = Integer.valueOf(tint.getRGB());
        synchronized (SB_ICON_CACHE) {
            java.util.Map<Integer, javax.swing.Icon> m = SB_ICON_CACHE.get(orig);
            if (m != null) { javax.swing.Icon hit = m.get(tkey); if (hit != null) return hit; }
        }
        javax.swing.Icon out;
        try {
            javax.swing.Icon base = tablerBase(iconKey(orig), 32, 32);
            if (base == null) base = orig;
            // Some JD sidebar icons are smaller, such as the 20px Advanced Settings one.
            if (base.getIconWidth() != 32 || base.getIconHeight() != 32) base = scaleIconTo(base, 32);
            out = tintIcon(base, tint, c);
        } catch (Throwable t) { out = tintIcon(orig, tint, c); }
        synchronized (SB_ICON_CACHE) {
            SB_ICON_CACHE.computeIfAbsent(orig, k -> new java.util.HashMap<>()).put(tkey, out);
        }
        return out;
    }

    /**
     * Mono icons for the chrome outside the sidebar, mainly the toolbar. JD caches its icons, so the Icon is
     * replaced on the component: a light one plus a dark rollover for the accent hover.
     */
    static void monoChromeIcons() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) monoChromeIn(w, false);
        }
    }

    private static void monoChromeIn(Container c, boolean inChrome) {
        // Config panels and the main toolbar hold no content icons such as favicons, so every label icon
        // inside them can go mono.
        boolean chrome = inChrome
                || (c instanceof JComponent && isConfigPanel(c.getClass()))
                || isMainToolbar(c.getClass());
        for (Component child : c.getComponents()) {
            // Menu items ignore rollover icons, so they need their own handling before the
            // AbstractButton branch.
            if (child instanceof javax.swing.JMenuItem) monoMenuItemIcon((javax.swing.JMenuItem) child);
            else if (child instanceof javax.swing.AbstractButton) monoButtonIcon((javax.swing.AbstractButton) child);
            else if (child instanceof javax.swing.JLabel) monoLabelIcon((javax.swing.JLabel) child, chrome);
            if (child instanceof Container) monoChromeIn((Container) child, chrome);
        }
    }

    // Toolbar toggle buttons carry a nameless ExtMergedIcon, so their Action class name picks the icon key.
    static final java.util.Map<String, String> ACTION_ICON = new java.util.HashMap<>();
    static {
        ACTION_ICON.put("PauseDownloadsAction",          "media-playback-pause");
        ACTION_ICON.put("ClipBoardToggleAction",         "clipboard");
        ACTION_ICON.put("AutoReconnectToggleAction",     "auto-reconnect");
        ACTION_ICON.put("GlobalPremiumSwitchToggleAction", "premium");
        ACTION_ICON.put("SilentModeToggleAction",        "silentmode");
        ACTION_ICON.put("StartDownloadsAction",          "media-playback-start");
        ACTION_ICON.put("StartStopDownloadsAction",      "media-playback-start");
        ACTION_ICON.put("StopDownloadsAction",           "media-playback-stop");
        ACTION_ICON.put("ForcedDownloadsAction",         "media-playback-start_forced");
        ACTION_ICON.put("ReconnectAction",               "reconnect");
        // The "update" glyph is a circular arrow like reconnect, so update gets a box.
        ACTION_ICON.put("UpdateAction",                  "package_new");
        ACTION_ICON.put("MyJDownloaderAction",           "logo/myjdownloader");
        ACTION_ICON.put("SettingsAction",                "settings");
        ACTION_ICON.put("AddLinksAction",                "add");
        ACTION_ICON.put("AddContainerAction",            "addContainer");
        // The linkgrabber's start button paints a keyless merge of play and add.
        ACTION_ICON.put("ConfirmSelectionBarAction",     "media-playback-start");
    }

    // ExtButton derives its private disabledIcon from the first icon it paints, keeps it and ignores
    // setDisabledIcon, so the field is cleared to make it derive again from the mono icon.
    private static boolean invalidateExtDisabledIcon(javax.swing.AbstractButton b) {
        try {
            Class<?> c = b.getClass();
            while (c != null && !"org.appwork.swing.components.ExtButton".equals(c.getName())) c = c.getSuperclass();
            if (c == null) return false;
            java.lang.reflect.Field f = c.getDeclaredField("disabledIcon");
            f.setAccessible(true);
            f.set(b, null);
            return true;
        } catch (Throwable ignore) { return false; }
    }

    private static void monoButtonIcon(javax.swing.AbstractButton b) {
        try {
            javax.swing.Icon cur = b.getIcon();
            if (cur == null || SITE_LOGOS.contains(cur)) return;
            // Once per mono icon, and before the early return below.
            Object mb0 = b.getClientProperty("jdp.monoBtn");
            if (mb0 instanceof javax.swing.Icon && b.getClientProperty("jdp.disInval") != mb0
                    && invalidateExtDisabledIcon(b))
                b.putClientProperty("jdp.disInval", mb0);
            // JD derives a new disabled icon from the original glyph on every enabled-state change, so
            // the stored mono one is put back on every tick.
            Object md = b.getClientProperty("jdp.monoDisabled");
            if (md instanceof javax.swing.Icon && b.getDisabledIcon() != md) b.setDisabledIcon((javax.swing.Icon) md);
            // ExtButton derives the disabled icon from the Action's SMALL_ICON, which JD resets to the original
            // glyph on state changes such as the update check, so it is pointed back at the mono icon here.
            if (mb0 instanceof javax.swing.Icon) {
                javax.swing.Action ra = b.getAction();
                if (ra != null && ra.getValue(javax.swing.Action.SMALL_ICON) != mb0) {
                    ra.putValue(javax.swing.Action.SMALL_ICON, (javax.swing.Icon) mb0);
                    invalidateExtDisabledIcon(b);
                }
            }
            // JD sets the selected icon of a toggle lazily on first activation, so the state icons are
            // checked on every tick.
            monoToggleStateIcon(b, "jdp.monoSel", true);
            monoToggleStateIcon(b, "jdp.monoRSel", false);
            if (cur == b.getClientProperty("jdp.monoBtn")) return;
            javax.swing.Icon mono = tablerForButton(b, cur, SIDEBAR_TEXT);
            if (mono == cur) {
                // No key to look up, possibly an animated icon such as the updater's, which a one-off
                // tint would cover for a single frame only.
                mono = new LiveMonoIcon(cur, SIDEBAR_TEXT);
            }
            b.setIcon(mono);
            b.putClientProperty("jdp.monoBtn", mono);
            // Hover, pressed and selected states paint the accent background, so their glyphs are dark.
            b.setRolloverIcon(tablerForButton(b, cur, accentFg()));
            // AppWork's ExtButton fills its hover itself and leaves rollover off, which hides this icon.
            b.setRolloverEnabled(true);
            javax.swing.Icon pi = b.getPressedIcon();
            if (pi != null && pi != mono) b.setPressedIcon(tablerForButton(b, pi, accentFg()));
            javax.swing.Icon dim = tintSolid(mono, DISABLED_TONE);
            b.setDisabledIcon(dim);
            b.putClientProperty("jdp.monoDisabled", dim);
            // ExtButton derives its disabled icon from the Action rather than the button.
            javax.swing.Action ba = b.getAction();
            if (ba != null && ba.getValue(javax.swing.Action.SMALL_ICON) != mono)
                ba.putValue(javax.swing.Action.SMALL_ICON, mono);
            b.setContentAreaFilled(true);   // otherwise FlatLaf's ToggleButton.hoverBackground does not paint
            installBtnIconListener(b);
        } catch (Throwable ignore) { }
    }

    /** JD's animated updater swaps the button icon faster than the tick, so a new icon is turned mono as
     *  soon as it is set. The button's own setIcon fires this too and is skipped by the identity check. */
    private static void installBtnIconListener(final javax.swing.AbstractButton b) {
        if (b.getClientProperty("jdp.btnIconL") != null) return;
        b.putClientProperty("jdp.btnIconL", Boolean.TRUE);
        b.addPropertyChangeListener("icon", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                if (b.getIcon() != b.getClientProperty("jdp.monoBtn")) monoButtonIcon(b);
            }
        });
        // A toggle's state icons change through their own properties, which the "icon" listener misses.
        java.beans.PropertyChangeListener sl = new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                monoToggleStateIcon(b, "jdp.monoSel", true);
                monoToggleStateIcon(b, "jdp.monoRSel", false);
            }
        };
        b.addPropertyChangeListener("selectedIcon", sl);
        b.addPropertyChangeListener("rolloverSelectedIcon", sl);
        // JD rebuilds the state icon when the toggle flips, and the property events can miss the base icon.
        b.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent e) {
                monoToggleStateIcon(b, "jdp.monoSel", true);
                monoToggleStateIcon(b, "jdp.monoRSel", false);
                if (b.getIcon() != b.getClientProperty("jdp.monoBtn")) monoButtonIcon(b);
            }
        });
    }

    /** Dark mono glyph for a toggle's selected or rollover-selected icon, which sits on the accent fill. */
    private static void monoToggleStateIcon(javax.swing.AbstractButton b, String prop, boolean selected) {
        try {
            javax.swing.Icon cur = selected ? b.getSelectedIcon() : b.getRolloverSelectedIcon();
            if (cur == null || cur == b.getClientProperty(prop)) return;
            javax.swing.Icon m = tablerForButton(b, cur, accentFg());
            if (m == cur) m = new LiveMonoIcon(cur, accentFg());
            if (selected) b.setSelectedIcon(m); else b.setRolloverSelectedIcon(m);
            b.putClientProperty(prop, m);
        } catch (Throwable ignore) { }
    }

    /** Like tablerIcon, but the button's Action class picks the glyph when it is in ACTION_ICON. */
    private static javax.swing.Icon tablerForButton(javax.swing.AbstractButton b, javax.swing.Icon icon, Color tone) {
        // The Action mapping wins over the icon's own key, because animated buttons such as the updater
        // carry a keyed frame icon on every tick.
        javax.swing.Action act = b.getAction();
        if (act != null) {
            String jk = ACTION_ICON.get(act.getClass().getSimpleName());
            if (jk != null) {
                javax.swing.Icon base = tablerBase(jk, icon.getIconWidth(), icon.getIconHeight());
                if (base != null) return tintIcon(base, tone, b);
            }
        }
        // A merged glyph such as the linkgrabber's add-and-start reports no key of its own.
        if (isMergedIcon(icon)) return monoMergedIcon(icon, tone);
        return tablerIcon(icon, tone, b);
    }

    // Settings section headers carry nameless scaled icons, so the header title picks the Tabler key.
    // titleToKey matches by substring, so longer keys come before the shorter ones they contain.
    private static final String[][] TITLE_MAP = {
        {"download folder", "download"}, {"download management", "downloadmanagment"}, {"autostart", "start"},
        {"linkgrabber", "linkgrabber"}, {"file writing", "save"}, {"file access", "folder"},
        {"miscellaneous", "wrench"}, {"reconnect", "reconnect"}, {"connection manager", "proxy_rotate"},
        {"connection", "network-idle"}, {"account", "premium"}, {"basic auth", "basicauth"},
        {"plugin", "plugin"}, {"captcha solver", "order"}, {"captcha", "ocr"}, {"notification", "bubble"},
        {"packagizer", "packagizer"}, {"archive extractor", "extract"}, {"extract", "extract"},
        {"folder watch", "folder_add"}, {"advanced", "advancedConfig"}, {"extension", "extension"},
        {"general", "home"}, {"user interface", "gui"}, {"my.jdownloader", "logo/myjdownloader"},
        {"proxy", "proxy"}, {"password", "password"}, {"tray", "minimize"}, {"solver", "order"},
        {"downloadlink address", "link"}, {"window management", "desktop"}, {"menus and toolbars", "menu"},
        // German titles
        {"allgemein", "home"}, {"benutzeroberfläche", "gui"}, {"anzeige der download", "link"},
        {"menüs und werkzeug", "menu"}, {"fenstermanagement", "desktop"}, {"benachrichtigung", "bubble"},
        {"verschiedenes", "wrench"}, {"erweiterte einstellungen", "advancedConfig"}, {"erweiterungen", "extension"},
        {"archiventpacker", "extract"}, {"archiv", "extract"}, {"zielordner", "folder"},
        {"passwortliste", "password"}, {"paketverwalter", "packagizer"}, {"ordnerüberwachung", "folder_add"},
        {"infosymbol", "info"}, {"profieinstellungen", "advancedConfig"}, {"benutzeraccount", "premium"},
        {"mein benutzer", "premium"}, {"verbindung", "network-idle"}, {"passwort", "password"},
        {"downloadverzeichnis", "downloadpath"}, {"downloadverwaltung", "downloadmanagment"},
        {"automatisch starten", "media-playback-start"}, {"linksammler", "linkgrabber"},
        {"schreiben von datei", "save"}, {"standardauthentifizierung", "basicauth"},
        {"linkfilter", "filter"}, {"ereignis", "event"}, {"skripter", "event"},
    };
    private static String titleToKey(String text) {
        if (text == null) return null;
        String t = text.toLowerCase().replaceAll("<[^>]*>", " ").trim();
        for (String[] m : TITLE_MAP) if (t.contains(m[0])) return m[1];
        return null;
    }

    static void monoLabelIcon(javax.swing.JLabel l, boolean cfg) {
        try {
            javax.swing.Icon cur = l.getIcon();
            if (cur == null) return;
            if (cur == l.getClientProperty("jdp.monoLbl")) return;
            if (l.getClientProperty("jdp.tabOrig") != null) return;   // recolorMainTabs owns tab labels
            if (isSiteLogo(cur)) return;
            // Outside config panels only icons with a Tabler twin are touched, which leaves content icons
            // such as favicons and file thumbnails alone.
            if (!cfg) {
                String key = iconKey(cur);
                if (key == null || tablerBase(key, cur.getIconWidth(), cur.getIconHeight()) == null) return;
            }
            // A nameless header icon takes its glyph from the title, which often sits in a sibling label.
            if (cfg && iconKey(cur) == null) {
                String title = l.getText();
                if (title == null || title.replaceAll("<[^>]*>", " ").trim().isEmpty()) {
                    Container par = l.getParent();
                    if (par != null) for (Component sib : par.getComponents())
                        if (sib instanceof javax.swing.JLabel && sib != l) {
                            String st = ((javax.swing.JLabel) sib).getText();
                            if (st != null && !st.replaceAll("<[^>]*>", " ").trim().isEmpty()) { title = st; break; }
                        }
                }
                String jk = titleToKey(title);
                javax.swing.Icon base = (jk != null) ? tablerBase(jk, cur.getIconWidth(), cur.getIconHeight()) : null;
                if (base != null) {
                    javax.swing.Icon t = tintIcon(base, SIDEBAR_TEXT, l);
                    l.setIcon(t); l.putClientProperty("jdp.monoLbl", t);
                    return;
                }
                // Unknown title: tint the glyph itself. JD's AbstractIcon composites need the label to paint.
                javax.swing.Icon solid = tintSolid(cur, SIDEBAR_TEXT, l);
                if (solid != cur) { l.setIcon(solid); l.putClientProperty("jdp.monoLbl", solid); }
                return;
            }
            javax.swing.Icon mono = tablerIcon(cur, SIDEBAR_TEXT, l);
            if (mono != cur) { l.setIcon(mono); l.putClientProperty("jdp.monoLbl", mono); return; }
            // A keyed icon without a Tabler PNG, such as the speed limit glyph, is tinted instead.
            if (cfg) {
                javax.swing.Icon solid = tintSolid(cur, SIDEBAR_TEXT, l);
                if (solid != cur) { l.setIcon(solid); l.putClientProperty("jdp.monoLbl", solid); }
            }
        } catch (Throwable ignore) { }
    }

    // Custom menu rows such as SpeedlimitEditorLink are not JMenuItems, so monoMenuItemIcon misses them.
    static void monoRowLabels(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JLabel && ((javax.swing.JLabel) ch).getIcon() != null) {
                javax.swing.JLabel jl = (javax.swing.JLabel) ch;
                monoLabelIcon(jl, true);
                // Same icon-text gap as the menu items, so the text column lines up too.
                if (jl.getText() != null && !jl.getText().isEmpty() && jl.getIconTextGap() != 10) jl.setIconTextGap(10);
            }
            if (ch instanceof Container) monoRowLabels((Container) ch);
        }
    }

    /** A silhouette of the icon in {@code tone}, keeping its alpha. Works for keyless icons too. */
    static javax.swing.Icon tintSolid(javax.swing.Icon ic, Color tone) { return tintSolid(ic, tone, null); }
    /** JD's AbstractIcon composites paint nothing without a component, so callers pass the host in {@code c}.
     *  Returns the original icon if the result would be empty. */
    static javax.swing.Icon tintSolid(javax.swing.Icon ic, Color tone, Component c) {
        try {
            int w = ic.getIconWidth(), h = ic.getIconHeight();
            if (w <= 0 || h <= 0) return ic;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try { ic.paintIcon(c, g, 0, 0); } catch (Throwable nullPaint) { ic.paintIcon(null, g, 0, 0); }
            g.dispose();
            int rgb = tone.getRGB() & 0x00ffffff;
            boolean any = false;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int a = (img.getRGB(x, y) >>> 24);
                if (a != 0) { img.setRGB(x, y, (a << 24) | rgb); any = true; }
            }
            if (!any) return ic;
            return new javax.swing.ImageIcon(img);
        } catch (Throwable t) { return ic; }
    }

    /** Removes a black box baked in behind a logo, such as the updater's, by flood-filling the near-black
     *  region from the corners. Icons whose corners are not opaque near-black come back unchanged. */
    static javax.swing.Icon deBoxIcon(javax.swing.Icon ic, Component c) {
        try {
            int w = ic.getIconWidth(), h = ic.getIconHeight();
            if (w <= 0 || h <= 0) return ic;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try { ic.paintIcon(c, g, 0, 0); } catch (Throwable t) { ic.paintIcon(null, g, 0, 0); }
            g.dispose();
            int[] corners = { img.getRGB(0, 0), img.getRGB(w - 1, 0), img.getRGB(0, h - 1), img.getRGB(w - 1, h - 1) };
            for (int cr : corners) {
                if ((cr >>> 24) < 200) return ic;
                int r = (cr >> 16) & 0xff, gg = (cr >> 8) & 0xff, b = cr & 0xff;
                if (r > 40 || gg > 40 || b > 40) return ic;
            }
            java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
            boolean[] seen = new boolean[w * h];
            int[][] starts = { {0, 0}, {w - 1, 0}, {0, h - 1}, {w - 1, h - 1} };
            for (int[] s : starts) { int idx = s[1] * w + s[0]; if (!seen[idx]) { seen[idx] = true; q.add(s); } }
            while (!q.isEmpty()) {
                int[] p = q.poll(); int x = p[0], y = p[1];
                int px = img.getRGB(x, y);
                int r = (px >> 16) & 0xff, gg = (px >> 8) & 0xff, b = px & 0xff;
                if ((px >>> 24) < 40) continue;
                if (r > 60 || gg > 60 || b > 60) continue;         // edge of the logo
                img.setRGB(x, y, px & 0x00ffffff);
                int[][] nb = { {x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1} };
                for (int[] nn : nb) {
                    int nx = nn[0], ny = nn[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int nidx = ny * w + nx;
                    if (!seen[nidx]) { seen[nidx] = true; q.add(nn); }
                }
            }
            return new javax.swing.ImageIcon(img);
        } catch (Throwable t) { return ic; }
    }

    /** Tints the wrapped icon on every paint, so animated icons such as the updater's stay mono on every
     *  frame. That costs a render per paint and is only used where tablerForButton finds no key. */
    private static final class LiveMonoIcon implements javax.swing.Icon {
        private final javax.swing.Icon base;
        private final Color tone;
        LiveMonoIcon(javax.swing.Icon base, Color tone) { this.base = base; this.tone = tone; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            try {
                int w = base.getIconWidth(), h = base.getIconHeight();
                if (w <= 0 || h <= 0) return;
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D ig = img.createGraphics();
                base.paintIcon(c, ig, 0, 0);
                ig.dispose();
                int rgb = tone.getRGB() & 0x00ffffff;
                for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
                    int a = (img.getRGB(xx, yy) >>> 24);
                    if (a != 0) img.setRGB(xx, yy, (a << 24) | rgb);
                }
                g.drawImage(img, x, y, null);
            } catch (Throwable t) { base.paintIcon(c, g, x, y); }
        }
        public int getIconWidth() { return base.getIconWidth(); }
        public int getIconHeight() { return base.getIconHeight(); }
    }

    /** The icon scaled by {@code factor}, cached per source icon. */
    private static javax.swing.Icon scaleIcon(javax.swing.Icon ic, double factor) {
        try {
            if (ic == null || factor == 1.0) return ic;
            synchronized (SCALED_ICONS) {
                javax.swing.Icon cached = SCALED_ICONS.get(ic);
                if (cached != null) return cached;
            }
            int w = ic.getIconWidth(), h = ic.getIconHeight();
            if (w <= 0 || h <= 0) return ic;
            int nw = (int) Math.round(w * factor), nh = (int) Math.round(h * factor);
            java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D sg = src.createGraphics();
            ic.paintIcon(null, sg, 0, 0);
            sg.dispose();
            java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = dst.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            javax.swing.Icon scaled = new javax.swing.ImageIcon(dst);
            synchronized (SCALED_ICONS) { SCALED_ICONS.put(ic, scaled); }
            return scaled;
        } catch (Throwable t) { return ic; }
    }
}
