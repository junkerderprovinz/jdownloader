package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.LafDefaults.*;

/**
 * The accent colour and the dark or light variant, chosen in Settings › User Interface. Both are
 * stored next to the theme and take effect when JD starts again, the way JD's own language
 * choice does.
 */
final class Appearance {

    // The container keeps the choice in /config so it survives image updates; the desktop keeps it
    // in the folder the installer set up.
    static final File STORE = DESKTOP ? new File(HL_HOME) : new File("/config/.jd-highlighter");
    static final String DEFAULT_ACCENT = "#ffee00";

    private Appearance() { }

    static String storedVariant() {
        String v = read("variant");
        return "light".equals(v) || "dark".equals(v) ? v : null;
    }

    static String storedAccent() {
        String a = read("accent");
        return a != null && a.matches("#[0-9a-fA-F]{6}") ? a.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static String read(String name) {
        try {
            File f = new File(STORE, name);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : null;
        } catch (Throwable t) { return null; }
    }

    private static void write(String name, String value) throws java.io.IOException {
        Files.createDirectories(STORE.toPath());
        Files.write(new File(STORE, name).toPath(), (value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Adds the two rows under the language row of every open User Interface page. */
    static void addRows() {
        for (Window w : Window.getWindows()) if (w.isShowing()) addRowsIn(w);
    }

    private static void addRowsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && ch.getClass().getName().endsWith(".settings.panels.GUISettings")) {
                addRowsTo((JComponent) ch);
            } else if (ch instanceof Container) {
                addRowsIn((Container) ch);
            }
        }
    }

    // The container offers its four own looks and stores the pick as the theme, which replaces JD_THEME;
    // the desktop package only brings the two JD Highlighter variants. JD's classic look stays reachable
    // through JD_THEME alone.
    private static final String[] THEMES = { "jd-highlighter-dark", "jd-highlighter-light", "plain-dark", "Light" };
    // Theme names are product names, so they stay the same in every language.
    private static final String[] THEME_NAMES = { "JD Highlighter Dark", "JD Highlighter Light", "JD Plain Dark", "JD Plain Light" };

    private static void addRowsTo(JComponent panel) {
        if (panel.getClientProperty("jdp.appearanceRows") != null) return;
        Component[] kids = panel.getComponents();
        int lang = -1;
        for (int i = 0; i < kids.length; i++) {
            if (kids[i].getClass().getName().endsWith(".components.SettingsButton")) { lang = i; break; }
        }
        if (lang < 1) return;
        panel.putClientProperty("jdp.appearanceRows", Boolean.TRUE);
        boolean de = german();
        Object labelCc = constraints(panel, kids[lang - 1]);
        Object fieldCc = constraints(panel, kids[lang]);
        trimClickHint((AbstractButton) kids[lang]);

        // A button like the language row next to it, which opens the list of looks as a menu.
        final int count = DESKTOP ? 2 : THEMES.length;
        final int now = currentTheme();
        final JButton theme = new JButton(now < 0 ? (de ? "Auswählen" : "Choose") : THEME_NAMES[now]);
        theme.addActionListener(e -> {
            javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
            for (int i = 0; i < count; i++) {
                final int pick = i;
                // The current look is set in bold; a radio dot would push its name off the centre.
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(THEME_NAMES[i]);
                item.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                if (i == now) item.setFont(item.getFont().deriveFont(java.awt.Font.BOLD));
                item.addActionListener(a -> {
                    if (pick == now) return;
                    if (DESKTOP) save("variant", pick == 1 ? "light" : "dark");
                    else save("theme", THEMES[pick]);
                });
                menu.add(item);
            }
            menu.setPopupSize(theme.getWidth(), menu.getPreferredSize().height);
            menu.show(theme, 0, theme.getHeight() + 4);
        });

        int at = lang + 1;
        if (HIGHLIGHTER) {
            final JButton accent = new JButton();
            final String current = storedAccent() != null ? storedAccent() : String.format("#%06x", accentColor().getRGB() & 0xffffff);
            accent.setText(current);
            javax.swing.Icon swatch = new Swatch(Color.decode(current));
            SITE_LOGOS.add(swatch);
            accent.setIcon(swatch);
            accent.addActionListener(e -> {
                Color picked = pickColor(SwingUtilities.getWindowAncestor(accent), Color.decode(current), de);
                if (picked == null) return;
                String hex = String.format("#%06x", picked.getRGB() & 0xffffff);
                if (hex.equals(current)) return;
                save("accent", hex);
            });
            panel.add(rowLabel(de ? "Akzentfarbe" : "Accent colour"), labelCc, at++);
            panel.add(accent, fieldCc, at++);
        }
        panel.add(rowLabel(de ? "Erscheinungsbild" : "Appearance"), labelCc, at++);
        panel.add(theme, fieldCc, at);
        panel.revalidate();
        panel.repaint();
    }

    // JD's classic look is not in the list, so it has no entry to mark.
    private static int currentTheme() {
        if (DESKTOP) return LIGHT ? 1 : 0;
        String t = System.getenv().getOrDefault("JD_THEME", "").trim();
        for (int i = 0; i < THEMES.length; i++) if (THEMES[i].equalsIgnoreCase(t)) return i;
        return -1;
    }

    // JD labels the language button "German (click to change)"; a button already says that it can be clicked.
    private static void trimClickHint(AbstractButton b) {
        String t = b.getText();
        if (t != null && t.matches(".*\\S\\s+\\([^()]*\\)")) b.setText(t.replaceFirst("\\s+\\([^()]*\\)$", ""));
    }

    // Swing's own chooser dialog puts OK on the left and adds a preview of sample text; this one keeps
    // the swatches, HSV and RGB with the hex field, and puts the confirming button on the right.
    private static Color pickColor(Window owner, Color current, boolean de) {
        final javax.swing.JColorChooser chooser = new javax.swing.JColorChooser(current);
        chooser.setPreviewPanel(new javax.swing.JPanel());
        java.util.List<javax.swing.colorchooser.AbstractColorChooserPanel> keep = new java.util.ArrayList<>();
        for (javax.swing.colorchooser.AbstractColorChooserPanel p : chooser.getChooserPanels()) {
            // HSV, HSL, RGB and CMYK share one panel class, so they differ only by name.
            String name = String.valueOf(p.getDisplayName()).toUpperCase(java.util.Locale.ROOT);
            if (!name.contains("CMYK") && !name.contains("HSL")) keep.add(p);
        }
        chooser.setChooserPanels(keep.toArray(new javax.swing.colorchooser.AbstractColorChooserPanel[0]));

        final javax.swing.JDialog dialog = new javax.swing.JDialog(owner, de ? "Akzentfarbe" : "Accent colour",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        final Color[] result = { null };
        JButton cancel = new JButton(de ? "Abbrechen" : "Cancel");
        JButton apply = new JButton(de ? "Übernehmen" : "Apply");
        cancel.addActionListener(e -> dialog.dispose());
        apply.addActionListener(e -> { result[0] = chooser.getColor(); dialog.dispose(); });
        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(apply);
        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout(0, 12));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.add(chooser, java.awt.BorderLayout.CENTER);
        content.add(buttons, java.awt.BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(apply);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }

    private static JLabel rowLabel(String text) {
        return new JLabel("<html>" + text + "</html>");
    }

    private static Object constraints(Container panel, Component c) {
        try {
            Object lm = panel.getLayout();
            return lm.getClass().getMethod("getComponentConstraints", Component.class).invoke(lm, c);
        } catch (Throwable t) { return null; }
    }

    private static void save(String name, String value) {
        boolean de = german();
        try {
            write(name, value);
            if (DESKTOP) Desktop.apply();
        } catch (Throwable t) {
            javax.swing.JOptionPane.showMessageDialog(null, (de ? "Konnte nicht gespeichert werden: " : "Could not be saved: ") + t.getMessage());
            return;
        }
        String[] options = de ? new String[] { "Später", "Jetzt neu starten" } : new String[] { "Later", "Restart now" };
        int r = javax.swing.JOptionPane.showOptionDialog(null,
                de ? "Die Änderung wird nach einem Neustart von JDownloader sichtbar."
                   : "The change shows after JDownloader restarts.",
                "JD Highlighter", javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE,
                null, options, options[1]);
        if (r == 1) restart();
    }

    // The container applies the theme before each JD start, so JD exits and the start loop brings it
    // back; the desktop asks JD for its own restart.
    private static void restart() {
        try {
            ClassLoader cl = jdLoader();
            Class<?> rc = Class.forName("org.jdownloader.updatev2.RestartController", true, cl);
            Object ctl = rc.getMethod("getInstance").invoke(null);
            if (DESKTOP) {
                Object req = Class.forName("org.jdownloader.updatev2.SmartRlyRestartRequest", true, cl).getConstructor().newInstance();
                rc.getMethod("asyncRestart", Class.forName("org.jdownloader.updatev2.RestartRequest", true, cl)).invoke(ctl, req);
            } else {
                Object req = Class.forName("org.jdownloader.updatev2.SmartRlyExitRequest", true, cl).getConstructor().newInstance();
                rc.getMethod("exitAsynch", Class.forName("org.appwork.shutdown.ShutdownRequest", true, cl)).invoke(ctl, req);
            }
        } catch (Throwable t) {
            System.out.println("[jd-dialog-agent] restart after an appearance change failed (" + t + ")");
        }
    }

    static ClassLoader jdLoader() {
        for (Window w : Window.getWindows()) if (w.getClass().getName().startsWith("jd.")) return w.getClass().getClassLoader();
        return Thread.currentThread().getContextClassLoader();
    }

    private static boolean german() {
        try {
            Class<?> tf = Class.forName("org.appwork.txtresource.TranslationFactory", true, jdLoader());
            Object lang = tf.getMethod("getDesiredLanguage").invoke(null);
            if (lang != null) return lang.toString().toLowerCase(java.util.Locale.ROOT).startsWith("de");
        } catch (Throwable ignore) { }
        return "de".equals(java.util.Locale.getDefault().getLanguage());
    }

    /** A rounded square in the chosen colour, shown on the accent button. */
    private static final class Swatch implements javax.swing.Icon {
        private final Color color;
        Swatch(Color color) { this.color = color; }
        public int getIconWidth() { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillRoundRect(x, y, 16, 16, 6, 6);
            g.setColor(PAL_DIVIDER);
            g.drawRoundRect(x, y, 15, 15, 6, 6);
            g.dispose();
        }
    }

    /** On the desktop nothing runs before JD starts, so the agent writes what the installer would. */
    static final class Desktop {
        private Desktop() { }

        static void apply() throws Exception {
            String variant = storedVariant() != null ? storedVariant() : (LIGHT ? "light" : "dark");
            String accent = storedAccent() != null ? storedAccent() : DEFAULT_ACCENT;
            String laf = "light".equals(variant) ? "FlatLightLaf" : "FlatDarkLaf";
            File tpl = new File(STORE, "templates");
            render(new File(tpl, laf + ".properties.tmpl"), new File(STORE, "flatlaf-defaults/" + laf + ".properties"), accent);
            render(new File(tpl, laf + ".json"), new File(JD_HOME, "cfg/laf/" + laf + ".json"), accent);
            setLookAndFeel("light".equals(variant) ? "FLATLAF_LIGHT" : "FLATLAF_DARK");
        }

        private static void render(File tmpl, File out, String accent) throws java.io.IOException {
            String fg = accentFg(accent);
            String text = new String(Files.readAllBytes(tmpl.toPath()), StandardCharsets.UTF_8)
                    .replace("@@ACCENT_HEX@@", accent.substring(1)).replace("@@ACCENT_FG_HEX@@", fg.substring(1))
                    .replace("@@ACCENT_FG@@", fg).replace("@@ACCENT@@", accent);
            Files.createDirectories(out.toPath().getParent());
            Files.write(out.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }

        // The same WCAG rule as the installers: dark text on a light accent, light text otherwise.
        private static String accentFg(String hex) {
            Color c = Color.decode(hex);
            double l = 0.2126 * lin(c.getRed()) + 0.7152 * lin(c.getGreen()) + 0.0722 * lin(c.getBlue());
            return l >= 0.5 ? "#161616" : "#f4f4f4";
        }

        private static double lin(int v) {
            double c = v / 255.0;
            return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }

        // JD writes its settings when it shuts down, so the look and feel goes through its config API
        // rather than into the file.
        private static void setLookAndFeel(String type) throws Exception {
            ClassLoader cl = jdLoader();
            Class<?> settings = Class.forName("org.jdownloader.settings.GraphicalUserInterfaceSettings", true, cl);
            Class<?> lafType = Class.forName("org.jdownloader.updatev2.gui.LookAndFeelType", true, cl);
            Object cfg = Class.forName("org.appwork.storage.config.JsonConfig", true, cl).getMethod("create", Class.class).invoke(null, settings);
            @SuppressWarnings({ "unchecked", "rawtypes" })
            Object value = Enum.valueOf((Class) lafType, type);
            settings.getMethod("setLookAndFeelTheme", lafType).invoke(cfg, value);
        }
    }
}
