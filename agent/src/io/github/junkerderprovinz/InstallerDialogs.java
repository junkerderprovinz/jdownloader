package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;

/** Confirms JD's installer and restart dialogs in the container. */
final class InstallerDialogs {

    private static final java.io.File RESTART_REQUEST = new java.io.File("/tmp/.jd-laf-restart-request");

    // Per-window guards: don't re-click a button more than once per 5s (a swallowed
    // click may still need a retry), and log each unmatched dialog only once.
    private static final java.util.Map<Window, Long> CLICKED_AT =
            new java.util.WeakHashMap<Window, Long>();
    private static final java.util.Set<Window> LOGGED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());
    private static final java.util.Set<Window> RESTART_REQUESTED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());

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

    static void handleDialogs() {
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

            // Design-Update: accept for FlatLaf themes; refuse FlatLaf prompts when classic.
            if (title.contains("Design-Update") || title.contains("Design Update")) {
                String body = collectText(w).toLowerCase();
                boolean mentionsFlat = body.contains("flatlaf") || body.contains("flat laf")
                        || title.toLowerCase().contains("flat");
                if (wantClassicLaf() && mentionsFlat) {
                    JButton cancel = findButtonByLabels(w, "Cancel", "Abbrechen", "No", "Nein", "Later", "Später");
                    if (cancel != null && clickAllowed(w)) {
                        cancel.doClick();
                        markClicked(w);
                        System.out.println("[jd-dialog-agent] refused FlatLaf design-update (classic JDDEFAULT)");
                        continue;
                    }
                    w.setVisible(false);
                    w.dispose();
                    System.out.println("[jd-dialog-agent] dismissed FlatLaf design-update (classic JDDEFAULT)");
                    continue;
                }
                JButton ok = findButtonByLabels(w, "OK", "Ok");
                if (ok != null && clickAllowed(w)) {
                    ok.doClick();
                    markClicked(w);
                    System.out.println("[jd-dialog-agent] accepted design-update"
                            + (wantClassicLaf() ? " (classic/Synthetica path)" : ""));
                    continue;
                }
            }

            // After leaving JDDEFAULT, FlatLaf may be re-enabled on disk but JD still
            // thinks FLATLAF_DARK/LIGHT is "not installed" and shows a one-shot install
            // prompt. Accept FlatLaf install prompts (any FlatLaf wording); never while
            // classic is selected, never on About.
            if (w instanceof Dialog && !wantClassicLaf()) {
                String body = collectText(w).toLowerCase();
                boolean isInfoDialog = title.toLowerCase().contains("about")
                        || title.toLowerCase().contains("über");
                boolean notInstalled = body.contains("not installed")
                        || body.contains("nicht installiert")
                        || body.contains("is not installed");
                boolean wantsInstall = body.contains("install it now")
                        || body.contains("installieren")
                        || body.contains("do you want to install");
                boolean mentionsFlatLafTheme = body.contains("flatlaf_dark")
                        || body.contains("flatlaf_light")
                        || body.contains("flatlaf dark")
                        || body.contains("flatlaf light")
                        || body.contains("flatlaf");
                if (!isInfoDialog && notInstalled && wantsInstall && mentionsFlatLafTheme) {
                    JButton ok = findButtonByLabels(w, "OK", "Ok", "Yes", "Ja", "Install", "Installieren");
                    if (ok != null && clickAllowed(w)) {
                        ok.doClick();
                        markClicked(w);
                        System.out.println("[jd-dialog-agent] accepted Look&Feel install (switch back to FlatLaf)");
                        continue;
                    }
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
                    // Classic theme: do not confirm a restart into FlatLaf.
                    if (wantClassicLaf() && body.contains("flatlaf")) {
                        JButton cancel = findButtonByLabels(w, "Cancel", "Abbrechen", "No", "Nein");
                        if (cancel != null && clickAllowed(w)) {
                            cancel.doClick();
                            markClicked(w);
                            System.out.println("[jd-dialog-agent] refused FlatLaf restart (classic JDDEFAULT)");
                            continue;
                        }
                        w.setVisible(false);
                        w.dispose();
                        System.out.println("[jd-dialog-agent] dismissed FlatLaf restart (classic JDDEFAULT)");
                        continue;
                    }
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
