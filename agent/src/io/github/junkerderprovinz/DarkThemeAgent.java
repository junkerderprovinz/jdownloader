package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.lang.instrument.Instrumentation;

/**
 * JVM agent that forces JDownloader's UI to dark colours regardless of what
 * JD's internal theme system does. Runs in a background thread that waits
 * for the first AWT Frame to appear (= JD has initialized its main window),
 * then sets all relevant UIManager keys and forces every open window to
 * re-apply its UI.
 *
 * Hooked via JAVA_TOOL_OPTIONS=-javaagent:/opt/JDownloader/jd-dark-agent.jar
 */
public class DarkThemeAgent {

    // KDE Breeze Dark palette
    private static final Color BG          = new Color(0x232629);
    private static final Color BG_ALT      = new Color(0x1e2124);
    private static final Color BG_PANEL    = new Color(0x31363b);
    private static final Color BG_HEADER   = new Color(0x1b1e20);
    private static final Color FG          = new Color(0xeff0f1);
    private static final Color FG_INACTIVE = new Color(0xa0a8b0);
    private static final Color FG_DISABLED = new Color(0x7f8c8d);
    private static final Color SEL_BG      = new Color(0x3daee9);
    private static final Color SEL_INACT   = new Color(0x2d3237);
    private static final Color BORDER      = new Color(0x2d3237);
    private static final Color PROGRESS    = new Color(0x2d8a42);
    private static final Color LINK        = new Color(0x2980b9);

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[jd-dark-agent] premain — starting watchers");

        Thread colorThread = new Thread(DarkThemeAgent::watchAndApply, "jd-dark-agent-colors");
        colorThread.setDaemon(true);
        colorThread.start();

        Thread dialogThread = new Thread(DarkThemeAgent::watchDialogs, "jd-dark-agent-dialogs");
        dialogThread.setDaemon(true);
        dialogThread.start();
    }

    // -------------------------------------------------------------------------
    // Dialog handling — runs from the start, dismisses or accepts JD's startup
    // popups so the user never sees them.
    //
    //   * "Tray isn't supported!" Error dialog → dispose silently
    //   * "JDownloader Design-Update" install prompt → click OK so the
    //     FLATLAF_DARK design is registered and never asked about again
    // -------------------------------------------------------------------------
    private static void watchDialogs() {
        long deadline = System.currentTimeMillis() + 600_000L; // 10 min watch window
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(400);
                SwingUtilities.invokeAndWait(DarkThemeAgent::handleDialogs);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // keep going on any swing-side exception
            }
        }
    }

    private static void handleDialogs() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;

            String title = "";
            if (w instanceof Frame) {
                title = nullToEmpty(((Frame) w).getTitle());
            } else if (w instanceof Dialog) {
                title = nullToEmpty(((Dialog) w).getTitle());
            } else {
                continue;
            }

            // Tray "isn't supported" error: dispose silently
            if (title.equalsIgnoreCase("Error") || title.equalsIgnoreCase("Fehler")) {
                String text = collectText(w);
                if (text.contains("Tray isn't supported") ||
                    text.contains("Tray wird nicht unterst")) {
                    w.setVisible(false);
                    w.dispose();
                    System.out.println("[jd-dark-agent] dismissed tray-error dialog");
                    continue;
                }
            }

            // FLATLAF Design-Update install prompt: click OK
            if (title.contains("Design-Update") || title.contains("Design Update")) {
                JButton ok = findButtonByLabel(w, "OK");
                if (ok == null) ok = findButtonByLabel(w, "Ok");
                if (ok != null) {
                    ok.doClick();
                    System.out.println("[jd-dark-agent] accepted FLATLAF design-update");
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

    private static void watchAndApply() {
        long deadline = System.currentTimeMillis() + 120_000L;
        int appliedCount = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }
            Frame[] frames = Frame.getFrames();
            if (frames.length == 0) {
                continue;
            }
            try {
                SwingUtilities.invokeAndWait(DarkThemeAgent::applyColors);
                appliedCount++;
                System.out.println("[jd-dark-agent] colours applied (pass " + appliedCount + ", frames=" + frames.length + ")");
                if (appliedCount >= 3) {
                    return;
                }
            } catch (Exception e) {
                System.err.println("[jd-dark-agent] apply failed: " + e);
            }
        }
        System.out.println("[jd-dark-agent] deadline reached (no further updates)");
    }

    private static void applyColors() {
        // Tables (download list, link grabber)
        put("Table.background",                 BG);
        put("Table.foreground",                 FG);
        put("Table.alternateRowColor",          BG_ALT);
        put("Table.gridColor",                  BORDER);
        put("Table.selectionBackground",        SEL_BG);
        put("Table.selectionForeground",        FG);
        put("Table.selectionInactiveBackground", SEL_INACT);
        put("Table.focusCellBackground",        SEL_INACT);
        put("Table.dropCellBackground",         SEL_BG);
        put("TableHeader.background",           BG_HEADER);
        put("TableHeader.foreground",           FG);
        put("TableHeader.separatorColor",       BORDER);

        // Lists
        put("List.background",                  BG);
        put("List.foreground",                  FG);
        put("List.selectionBackground",         SEL_BG);
        put("List.selectionForeground",         FG);
        put("List.selectionInactiveBackground", SEL_INACT);

        // Trees
        put("Tree.background",                  BG);
        put("Tree.foreground",                  FG);
        put("Tree.selectionBackground",         SEL_BG);
        put("Tree.selectionForeground",         FG);

        // Panels, viewports, scrollpanes
        put("Panel.background",                 BG);
        put("Panel.foreground",                 FG);
        put("Viewport.background",              BG);
        put("Viewport.foreground",              FG);
        put("ScrollPane.background",            BG);
        put("SplitPane.background",             BG);
        put("SplitPaneDivider.background",      BG_HEADER);

        // Text components
        put("TextField.background",             BG_PANEL);
        put("TextField.foreground",             FG);
        put("TextArea.background",              BG_PANEL);
        put("TextArea.foreground",              FG);
        put("EditorPane.background",            BG_PANEL);
        put("EditorPane.foreground",            FG);
        put("PasswordField.background",         BG_PANEL);
        put("PasswordField.foreground",         FG);
        put("FormattedTextField.background",    BG_PANEL);
        put("FormattedTextField.foreground",    FG);

        // Tabs
        put("TabbedPane.background",            BG);
        put("TabbedPane.foreground",            FG);
        put("TabbedPane.selectedBackground",    BG_PANEL);
        put("TabbedPane.hoverColor",            BG_PANEL);

        // Toolbar, menus
        put("ToolBar.background",               BG);
        put("ToolBar.foreground",               FG);
        put("MenuBar.background",               BG_HEADER);
        put("MenuBar.foreground",               FG);
        put("Menu.background",                  BG_HEADER);
        put("Menu.foreground",                  FG);
        put("MenuItem.background",              BG_HEADER);
        put("MenuItem.foreground",              FG);
        put("PopupMenu.background",             BG_HEADER);
        put("PopupMenu.foreground",             FG);

        // Buttons, controls
        put("Button.background",                BG_PANEL);
        put("Button.foreground",                FG);
        put("Button.hoverBackground",           SEL_BG);
        put("ToggleButton.background",          BG_PANEL);
        put("ToggleButton.foreground",          FG);
        put("ComboBox.background",              BG_PANEL);
        put("ComboBox.foreground",              FG);
        put("CheckBox.background",              BG);
        put("CheckBox.foreground",              FG);
        put("RadioButton.background",           BG);
        put("RadioButton.foreground",           FG);
        put("Spinner.background",               BG_PANEL);
        put("Spinner.foreground",               FG);

        // Progress
        put("ProgressBar.background",           BG_PANEL);
        put("ProgressBar.foreground",           PROGRESS);

        // Dialogs, root pane
        put("OptionPane.background",            BG);
        put("OptionPane.foreground",            FG);
        put("RootPane.background",              BG);
        put("Frame.background",                 BG);
        put("Dialog.background",                BG);

        // Generic
        put("control",                          BG);
        put("controlText",                      FG);
        put("text",                             FG);
        put("textInactiveText",                 FG_INACTIVE);
        put("textHighlight",                    SEL_BG);
        put("textHighlightText",                FG);
        put("info",                             BG_PANEL);
        put("infoText",                         FG);

        // Force every open window to re-resolve UI defaults
        for (Frame f : Frame.getFrames()) {
            try {
                SwingUtilities.updateComponentTreeUI(f);
                f.repaint();
            } catch (Throwable t) {
                // ignore individual frame failures, keep going
            }
        }
    }

    private static void put(String key, Color value) {
        UIManager.put(key, value);
    }
}
