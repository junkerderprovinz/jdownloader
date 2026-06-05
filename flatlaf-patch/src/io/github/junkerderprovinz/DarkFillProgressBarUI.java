package io.github.junkerderprovinz;

import java.awt.Color;
import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import com.formdev.flatlaf.ui.FlatProgressBarUI;

/**
 * Keeps the progress-bar fill dark-grey in EVERY row state.
 *
 * JDownloader's download list paints its progress column with a FlatLaf JProgressBar, and
 * FlatProgressBarUI takes the fill colour from progressBar.getForeground(). On a selected
 * or mouse-over row, AppWork's ExtColumn highlighter sets that foreground to the row's
 * (light) text colour, so the fill turned light on those rows — there is no properties-only
 * fix for this. We force a fixed dark-grey fill right before FlatLaf paints, so the bar
 * stays dark regardless of the row's selection/hover foreground. The % text keeps using
 * ProgressBar.selectionForeground (light), so it stays readable on the dark fill.
 *
 * Registered via "ProgressBarUI" in the patched FlatDarkLaf.properties and injected into
 * flatlaf.jar (see patch-flatlaf-dark.py) so it loads in FlatLaf's own classloader.
 */
public class DarkFillProgressBarUI extends FlatProgressBarUI {

    // Carbon monochrome: visible on the #262626 track and distinct from the #525252
    // selected-row background, while still clearly "dark grey".
    private static final Color FILL = new Color(0x5A, 0x5A, 0x5A);

    @SuppressWarnings("unused") // invoked reflectively by Swing's UIManager
    public static ComponentUI createUI(JComponent c) {
        return new DarkFillProgressBarUI();
    }

    @Override
    protected void paintDeterminate(Graphics g, JComponent c) {
        forceFill();
        super.paintDeterminate(g, c);
    }

    @Override
    protected void paintIndeterminate(Graphics g, JComponent c) {
        forceFill();
        super.paintIndeterminate(g, c);
    }

    private void forceFill() {
        // Only assign when it differs, so on-screen bars do not churn repaints.
        if (progressBar != null && !FILL.equals(progressBar.getForeground())) {
            progressBar.setForeground(FILL);
        }
    }
}
