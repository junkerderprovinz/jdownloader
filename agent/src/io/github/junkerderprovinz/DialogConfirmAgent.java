package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
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

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[jd-dialog-agent] watching for installer dialogs + enforcing dark chrome");
        Thread t = new Thread(DialogConfirmAgent::watch, "jd-dialog-agent");
        t.setDaemon(true);
        t.start();
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
    }

    // ---------------------------------------------------------------- chrome

    /**
     * Recolour FlatLaf's UIManager colour defaults to the #161616 greyscale, once
     * per look-and-feel instance. Re-runs automatically if JD re-applies the LAF
     * (a fresh UIDefaults clears our marker).
     */
    private static void enforceDarkChrome() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;
        if (Boolean.TRUE.equals(UIManager.get("jdp.darkChrome"))) return; // already done, not reset

        UIDefaults d = UIManager.getDefaults();
        List<Object> keys = new ArrayList<>(d.keySet()); // snapshot: we mutate while iterating
        for (Object key : keys) {
            Object val = d.get(key);
            if (!(val instanceof Color)) continue;
            Color rep = remap((Color) val);
            if (rep != null) d.put(key, rep);
        }
        // belt-and-suspenders for the few keys that drive the most visible surfaces
        UIManager.put("Component.accentColor", SEL);   // kills FlatLaf's blue focus/selection
        UIManager.put("TableHeader.background", HEADER);
        UIManager.put("jdp.darkChrome", Boolean.TRUE);

        for (Window w : Window.getWindows()) {
            try { SwingUtilities.updateComponentTreeUI(w); } catch (Exception ignore) { }
        }
        System.out.println("[jd-dialog-agent] enforced #161616 dark chrome");
    }

    /**
     * Map a FlatLaf default colour onto the Carbon greyscale.
     *   - blue accent           -> grey selection #525252 (no colour accent)
     *   - neutral chrome grey    -> same grey darkened onto the #161616 scale
     *   - everything else (light text, red/amber error colours, green) -> unchanged
     * Returns null to leave the colour as-is. Alpha is preserved.
     */
    private static Color remap(Color c) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue(), a = c.getAlpha();
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int bright = (r + g + b) / 3;

        // FlatLaf's blue accent (focus borders, selection, links) -> neutral grey
        if (b > r + 24 && b > g + 12 && b > 90) {
            return new ColorUIResource(new Color(SEL.getRed(), SEL.getGreen(), SEL.getBlue(), a));
        }
        // neutral-ish grey in the chrome band -> darken onto the #161616 scale.
        // band stops at 110 so light foreground/disabled-text greys stay readable.
        if ((max - min) <= 22 && bright >= 26 && bright <= 110) {
            int o = Math.max(0x12, Math.round(bright * 0.40f));
            return new ColorUIResource(new Color(o, o, o, a));
        }
        return null;
    }

    // --------------------------------------------------------------- dialogs

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
                JButton ok = findButtonByLabel(w, "OK");
                if (ok == null) ok = findButtonByLabel(w, "Ok");
                if (ok != null) {
                    ok.doClick();
                    System.out.println("[jd-dialog-agent] accepted design-update");
                    continue;
                }
            }

            // "Manage extensions" install prompt -> install now.
            if (title.contains("Erweiterungen verwalten") || title.contains("Manage Extensions")) {
                JButton install = findButtonByLabel(w, "Jetzt installieren");
                if (install == null) install = findButtonByLabel(w, "Install now");
                if (install == null) install = findButtonByLabel(w, "Install");
                if (install != null) {
                    install.doClick();
                    System.out.println("[jd-dialog-agent] accepted extension install");
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
