// COMPILE-ONLY STUB — deliberately NOT shipped in the image.
//
// It lets DarkFillProgressBarUI compile against FlatLaf's FlatProgressBarUI without
// pulling FlatLaf into the build. At runtime the compiled DarkFillProgressBarUI.class is
// injected into JD's own flatlaf.jar, where this name resolves to the REAL
// com.formdev.flatlaf.ui.FlatProgressBarUI (which actually paints). Only the io.github.*
// class is copied into the image; this stub's .class is discarded.
//
// The paint* methods and the protected `progressBar` field come from the JDK's
// javax.swing.plaf.basic.BasicProgressBarUI (stable since Java 1.4), so the bytecode is
// compatible with any FlatLaf 2.x/3.x that subclasses it.
package com.formdev.flatlaf.ui;

import java.awt.Graphics;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicProgressBarUI;

public class FlatProgressBarUI extends BasicProgressBarUI {
    @Override
    protected void paintDeterminate(Graphics g, JComponent c) { }

    @Override
    protected void paintIndeterminate(Graphics g, JComponent c) { }
}
