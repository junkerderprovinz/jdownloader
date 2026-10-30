package io.github.junkerderprovinz;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.UIManager;

/** Runs the jd-highlighter steps, the walks over the whole component tree only when something changed. */
final class HighlighterPass {

    // JD also changes things that no AWT event reports, such as an icon set through setIcon or a
    // rebuilt column model, so every step still runs on every fifth tick (2 s).
    private static final int FULL_PASS_TICKS = 5;

    private static final class Step {
        final String name;
        final boolean everyTick;
        final Runnable run;
        long nanos;
        int runs;

        Step(String name, boolean everyTick, Runnable run) {
            this.name = name;
            this.everyTick = everyTick;
            this.run = run;
        }
    }

    private static Step everyTick(String name, Runnable run) { return new Step(name, true, run); }
    private static Step onChange(String name, Runnable run) { return new Step(name, false, run); }

    // Each step runs on its own, so one that throws on an odd component cannot starve the rest.
    // Steps for hover and selection, menus, open popups and dialogs run on every tick, and so do
    // those whose work JD undoes without changing the tree: rebuilt header renderers, the column
    // lock glyph and the grid lines of the views panel. JD re-renders popup rows lazily, which is
    // why open popups are styled again on every tick.
    private static final Step[] STEPS = {
        onChange("styleSidebar", Settings::styleSidebar),
        onChange("stripSectionUnderlines", Settings::stripSectionUnderlines),
        everyTick("recolorMainTabs", Chrome::recolorMainTabs),
        everyTick("flushMenuBarItems", Menus::flushMenuBarItems),
        onChange("darkenChromeBars", Chrome::darkenChromeBars),
        onChange("monoChromeIcons", Icons::monoChromeIcons),
        onChange("roundToolbarButtons", Chrome::roundToolbarButtons),
        everyTick("badgeViewsMenu", Views::badgeViewsMenu),
        everyTick("badgeViewsPanel", Views::badgeViewsPanel),
        everyTick("fixMenuArrow", Menus::fixMenuArrow),
        everyTick("styleMenuFields", Menus::styleMenuFields),
        everyTick("wireMenuPopups", Menus::wireMenuPopups),
        everyTick("styleVisibleMenuPopups", Menus::styleVisibleMenuPopups),
        onChange("cardSettingsSections", Cards::cardSettingsSections),
        onChange("borderlessConfigTables", Tables::borderlessConfigTables),
        onChange("unifyConfigFields", Settings::unifyConfigFields),
        onChange("monoTableRowIcons", Tables::monoTableRowIcons),
        onChange("indentNameColumns", Tables::indentNameColumns),
        onChange("cardMainTables", Cards::cardMainTables),
        onChange("cardBottomArea", Cards::cardBottomArea),
        onChange("alignToolbarLeft", Cards::alignToolbarLeft),
        onChange("monoConfigTableIcons", Tables::monoConfigTableIcons),
        everyTick("fixWidthLockIcon", Tables::fixWidthLockIcon),
        onChange("stylePropertiesPanel", Settings::stylePropertiesPanel),
        onChange("monoSectionHeaders", Settings::monoSectionHeaders),
        onChange("monoCornerIcons", Tables::monoCornerIcons),
        everyTick("growTableHeaders", Tables::growTableHeaders),
        everyTick("recolorDialogs", Dialogs::recolorDialogs),
        everyTick("dimModalBackdrops", Dialogs::dimModalBackdrops),
    };

    private static boolean listening;
    private static volatile boolean changed = true;
    private static int quietTicks;
    private static int fullPasses;

    static void run(boolean timed) {
        listen();
        boolean full = changed || ++quietTicks >= FULL_PASS_TICKS;
        if (full) {
            // Cleared before the steps run, so the changes they make themselves trigger one more pass.
            changed = false;
            quietTicks = 0;
            fullPasses++;
        }
        for (Step s : STEPS) {
            if (!full && !s.everyTick) continue;
            long start = timed ? System.nanoTime() : 0;
            try { s.run.run(); } catch (Throwable ignore) { }
            if (timed) {
                s.nanos += System.nanoTime() - start;
                s.runs++;
            }
        }
    }

    // Installed from the first tick rather than premain, because the toolkit must not start before
    // JD's main has set its AWT properties.
    private static void listen() {
        if (listening) return;
        listening = true;
        long mask = AWTEvent.CONTAINER_EVENT_MASK | AWTEvent.HIERARCHY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK
                | AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> { if (marksChange(e)) changed = true; }, mask);
        UIManager.addPropertyChangeListener(e -> changed = true);
    }

    // Scrolling moves components and the pointer enters and leaves them all the time, which changes
    // nothing the steps style. A finished click or key press can, through a toggle or a column menu.
    private static boolean marksChange(AWTEvent e) {
        switch (e.getID()) {
            case ComponentEvent.COMPONENT_MOVED:
            case MouseEvent.MOUSE_ENTERED:
            case MouseEvent.MOUSE_EXITED:
            case MouseEvent.MOUSE_PRESSED:
            case MouseEvent.MOUSE_CLICKED:
            case KeyEvent.KEY_PRESSED:
            case KeyEvent.KEY_TYPED:
                return false;
            default:
                return true;
        }
    }

    /** Average cost of each step since the last report, most expensive first, and resets the counters. */
    static String report() {
        Step[] byCost = STEPS.clone();
        java.util.Arrays.sort(byCost, (a, b) -> Long.compare(b.nanos, a.nanos));
        StringBuilder sb = new StringBuilder();
        sb.append(fullPasses).append(" full passes");
        for (Step s : byCost) {
            if (s.runs == 0) continue;
            sb.append(String.format(java.util.Locale.ROOT, "%n  %-24s %7.3f ms x%d", s.name, s.nanos / 1e6 / s.runs, s.runs));
            s.nanos = 0;
            s.runs = 0;
        }
        fullPasses = 0;
        return sb.toString();
    }
}
