package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.text.JTextComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Frame;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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

    // Plain (non-UIResource) colours set directly on the table progress-bar instances so a
    // later updateUI cannot override them. The fill is the LIGHT #c6c6c6 the registered
    // FlatDarkLaf.properties already uses (@accentBaseColor) — same value as the UIManager
    // fallback (installProgressBarDefaults) so the two mechanisms can never disagree into a
    // grey flash on scroll (BUG 3). % text is dark over the fill, white over the track.
    private static final Color BAR_FILL  = new Color(0xc6, 0xc6, 0xc6);
    private static final Color BAR_TRACK = new Color(0x26, 0x26, 0x26);

    // --- Kayn (JD_Plain_Dark visibility, 2026-08-04) ---
    // Neutral (no accent: plain dark has none) light-on-dark tones for the two forum fixes below.
    private static final Color K_CHECK_DISC   = new Color(0xc6, 0xc6, 0xc6);   // light disc behind the toggle check (== BAR_FILL)
    private static final Color K_CHECK_MARK   = new Color(0x16, 0x16, 0x16);   // dark check on the light disc
    private static final Color K_CORNER_LIGHT = new Color(0xd0, 0xd0, 0xd0);   // light glyph for the overview-panel corner icons
    // A 16x16 fully transparent icon so a checkbox menu item that ships NO icon (e.g. "Sidebar visible")
    // still gets an icon slot to carry the check badge.
    private static final javax.swing.Icon K_BLANK16 = new javax.swing.Icon() {
        public int getIconWidth()  { return 16; }
        public int getIconHeight() { return 16; }
        public void paintIcon(Component c, Graphics g, int x, int y) { }
    };

    // Chrome is enforced exactly ONCE per JVM, and only after JD's main window is shown
    // and stable — see enforceDarkChrome().
    private static boolean chromeDone  = false;
    private static int     stableTicks = 0;

    // --- v3 theming: FlatLaf custom-defaults source instead of patching JD's jar ----
    // Our colour overrides live in /opt/JDownloader/flatlaf-defaults/ and are hooked in
    // via FlatLaf's OFFICIAL API (registerCustomDefaultsSource). JD's flatlaf.jar stays
    // stock, so its integrity check never complains and a self-update cannot reset the
    // chrome theme. If registration wins the race against JD's setLookAndFeel, the first
    // frame is already themed; otherwise applyCustomDefaults() does exactly ONE polite
    // LAF re-apply once the main window is stable (the same once-after-stable pattern
    // enforceDarkChrome() has used safely for months). The legacy UIManager remap in
    // enforceDarkChrome() still runs afterwards as polish + fallback.
    private static final java.io.File DEFAULTS_DIR = new java.io.File("/opt/JDownloader/flatlaf-defaults");
    private static Instrumentation INSTRUMENTATION;
    private static volatile boolean defaultsRegistered = false;
    private static volatile boolean flatLafLoadHookFired = false;
    private static boolean lafRefreshDone     = false;
    private static int     lafStableTicks     = 0;
    private static int     registrationWait   = 0;
    private static int     classScanTicks     = 0;

    // --- Ground-truth markers for the container (autostart READY gate) -----------
    // The launcher used to infer "themed" from a patched flatlaf.jar on disk — which
    // says nothing about whether the LAF was actually APPLIED (a pending "restart to
    // apply" dialog left the GUI white while the banner fired). These markers are the
    // in-JVM truth: the agent's PID, and the class name of the ACTIVE look-and-feel.
    private static final java.io.File PID_FILE        = new java.io.File("/tmp/.jd-agent.pid");
    private static final java.io.File LAF_FILE        = new java.io.File("/tmp/.jd-laf-applied");
    private static final java.io.File RESTART_REQUEST = new java.io.File("/tmp/.jd-laf-restart-request");
    private static String lastLafWritten = null;
    private static int    lafTick        = 0;

    // Per-window guards: don't re-click a button more than once per 5s (a swallowed
    // click may still need a retry), and log each unmatched dialog only once.
    private static final java.util.Map<Window, Long> CLICKED_AT =
            new java.util.WeakHashMap<Window, Long>();
    private static final java.util.Set<Window> LOGGED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());
    private static final java.util.Set<Window> RESTART_REQUESTED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Window, Boolean>());

    public static void premain(String agentArgs, Instrumentation inst) {
        if (wantClassicLaf()) {
            System.out.println("[jd-dialog-agent] watching for installer dialogs (classic JDDEFAULT — no FlatLaf dark chrome)");
        } else {
            System.out.println("[jd-dialog-agent] watching for installer dialogs + enforcing dark chrome");
        }
        INSTRUMENTATION = inst;
        // BUG 4: arm the load-time bytecode guards (AppWork CircledProgressBar UI + jsyntaxpane
        // ScriptAction) BEFORE JD lazily loads them — fixes the Event Scripter script-editor under FlatLaf.
        installBytecodeGuards(inst);
        if (!wantClassicLaf()) {
            exposeFlatlafToSystemLoader();
            // Put the light package-expander icons back BEFORE JD's GUI resolves them
            // (premain runs before JD's main()); the tick loop keeps them in place.
            restoreExpanderIcons();
            // BUG 3: seed the progress-bar developer overrides + a LAF-change listener before JD
            // installs its LAF, so the light fill is the render-time fallback from the first paint
            // and is re-asserted on every reinstall (no scroll grey-flash).
            ensureLafChangeListener();
            installProgressBarDefaults();
        } else {
            exposeSyntheticaToSystemLoader();
        }
        writeFile(PID_FILE, Long.toString(ProcessHandle.current().pid()));
        Thread t = new Thread(DialogConfirmAgent::watch, "jd-dialog-agent");
        t.setDaemon(true);
        t.start();
    }

    // --- BUG 4: Event Scripter script editor won't open under FlatLaf ------------------
    // AppWork's org.appwork.swing.components.circlebar.BasicCircleProgressBarUI has a latent bug:
    // getPreferredSize(JComponent c) reads the FIELD circleBar (not the passed component), and
    // uninstallUI() nulls circleBar BEFORE uninstallListeners(). Under FlatLaf a second updateUI/
    // setUI pass runs on the transient CircledProgressBar rubber-stamp widget, leaving a UI delegate
    // whose circleBar is null; the next layout — the Event Scripter script-editor dialog's pack() —
    // calls getPreferredSize -> NPE in getValueClipPainter -> the dialog's layout aborts -> it never
    // opens ("edit does nothing"; JD then throws IllegalStateException "Dialog has not been closed yet").
    // JD's non-FlatLaf LAFs set the LAF once before the GUI is built, so the order bug never fires there.
    //
    // This is unreachable by reflection (the widget is a transient renderer, never a persistent tree
    // child) and by UIManager (CircledProgressBar.updateUI hardcodes setUI(new BasicCircleProgressBarUI())
    // and never consults a UI key). So we fix it at the root with a LOAD-TIME bytecode transform that
    // prepends a null-guard to getPreferredSize: if circleBar == null, return a 0x0 Dimension instead of
    // dereferencing null. Real bars keep circleBar set and size exactly as before; only the broken/
    // transient null ones are guarded. FAIL-SAFE: any transform error, or a future AppWork rename, leaves
    // the original bytes untouched — i.e. exactly today's behaviour, never a boot regression.
    private static final String CPB_UI = "org/appwork/swing/components/circlebar/BasicCircleProgressBarUI";
    private static final String CPB_FIELD_OWNER = "org/appwork/swing/components/circlebar/CircledProgressBar";
    private static final String CPB_FIELD_DESC = "L" + CPB_FIELD_OWNER + ";";
    // BUG 4 issue #2: jsyntaxpane's ScriptAction has a STATIC ScriptEngine `engine` that is null when the
    // JVM has no javax.script JavaScript engine (Nashorn was removed in Java 15). Its install()/getScriptFromURL()
    // then deref that null engine -> NPE while the Event Scripter's JavaScriptEditorDialog installs its code-editor
    // kit (JEditorPane.setEditorKit -> DefaultSyntaxKit.install -> addActions -> ScriptAction.install). That NPE is
    // swallowed by AppWork's fire-and-forget EDTRunner (logged to LogV3/stderr, not Log.L), so layoutDialogContent
    // aborts, the modal dialog never maps, and JD throws "Dialog has not been closed yet". Guard both methods to
    // no-op when engine is null: the (non-functional-anyway) scripted editor action is simply skipped and the
    // editor opens normally. Same load-time transform technique + fail-safe as the CircledProgressBar fix.
    private static final String SA_CLASS = "jsyntaxpane/actions/ScriptAction";
    private static final String FLAT_LAF = "com/formdev/flatlaf/FlatLaf";
    /** Classic JDDEFAULT: JDDefaultLookAndFeel lives on the App CL (via syntheticaJDCustom.jar)
     *  and cannot see Core's NewTheme — getDisabledIcon then printStackTrace-spams CNFE on every
     *  disabled icon. Force the Synthetica super path; disabled icons still render. */
    private static final String JD_DEFAULT_LAF = "org/jdownloader/gui/laf/jddefault/JDDefaultLookAndFeel";
    /** Classic painters call LAFOptions.getInstance() during early paints (settings scrollbar)
     *  before JD has initialized the singleton → WTFException spam. Guard with stock colours. */
    private static final String JD_SCROLLBAR_PAINTER = "org/jdownloader/gui/laf/jddefault/CustomScrollbarPainter";
    private static final String JD_PROGRESS_PAINTER = "org/jdownloader/gui/laf/jddefault/CustomProgressbarPainter";
    private static boolean circleBarPatchLogged = false;
    private static boolean scriptActionPatchLogged = false;
    private static boolean jdDefaultLafPatchLogged = false;
    private static boolean jdScrollbarPatchLogged = false;
    private static boolean jdProgressPatchLogged = false;

    private static void installBytecodeGuards(Instrumentation inst) {
        try {
            ClassFileTransformer t = new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                        ProtectionDomain pd, byte[] classfileBuffer) {
                    try {
                        if (CPB_UI.equals(className))  return patchCircleBarUI(classfileBuffer, loader);
                        if (SA_CLASS.equals(className)) return patchScriptAction(classfileBuffer, loader);
                        if (wantClassicLaf()) {
                            if (JD_DEFAULT_LAF.equals(className))
                                return patchJdDefaultGetDisabledIcon(classfileBuffer, loader);
                            if (JD_SCROLLBAR_PAINTER.equals(className))
                                return patchCustomScrollbarPainter(classfileBuffer, loader);
                            if (JD_PROGRESS_PAINTER.equals(className))
                                return patchCustomProgressbarPainter(classfileBuffer, loader);
                        }
                        // theme robustness: the instant FlatLaf's base class loads (before JD's
                        // setLookAndFeel), register our custom-defaults source so the FIRST paint
                        // already carries our colours -> removes the reliance on the fragile
                        // live-re-apply fallback that lost the race on cold/slow boots (grey chrome).
                        if (FLAT_LAF.equals(className)) registerDefaultsOnFlatLafLoad(loader);
                        return null;
                    } catch (Throwable err) {
                        System.out.println("[jd-dialog-agent] bytecode transform skipped for " + className
                                + " (" + err + ")");
                        return null;   // fail-safe: original bytes, no regression
                    }
                }
            };
            inst.addTransformer(t, true);
            System.out.println("[jd-dialog-agent] bytecode guards armed (BUG 4: CircledProgressBar + ScriptAction"
                    + (wantClassicLaf() ? " + classic JDDEFAULT painters" : "") + ")");
        } catch (Throwable err) {
            System.out.println("[jd-dialog-agent] could not arm bytecode guards (" + err + ")");
        }
    }

    /** getColor / getColorMouseOver: if LAFOptions not ready or colour null, return stock classic blues. */
    private static byte[] patchCustomScrollbarPainter(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        final int[] hits = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                final boolean normal = "getColor".equals(name) && "()Ljava/awt/Color;".equals(desc);
                final boolean hover = "getColorMouseOver".equals(name) && "()Ljava/awt/Color;".equals(desc);
                if (!normal && !hover) return mv;
                hits[0]++;
                final String field = normal ? "color" : "colorMouseOver";
                final String lafGetter = normal ? "getColorForScrollbarsNormalState"
                        : "getColorForScrollbarsMouseOverState";
                final int fallbackArgb = normal ? 0xFFD7E7F0 : 0xFFABC7D8;
                mv.visitCode();
                // if (this.field != null) return this.field;
                Label needFetch = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, JD_SCROLLBAR_PAINTER, field, "Ljava/awt/Color;");
                mv.visitJumpInsn(Opcodes.IFNULL, needFetch);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, JD_SCROLLBAR_PAINTER, field, "Ljava/awt/Color;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(needFetch);
                Label tryStart = new Label(), tryEnd = new Label(), catchH = new Label(), fallback = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchH, "java/lang/Throwable");
                mv.visitLabel(tryStart);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jdownloader/updatev2/gui/LAFOptions",
                        "getInstance", "()Lorg/jdownloader/updatev2/gui/LAFOptions;", false);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/jdownloader/updatev2/gui/LAFOptions",
                        lafGetter, "()Ljava/awt/Color;", false);
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitJumpInsn(Opcodes.IFNULL, fallback);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.PUTFIELD, JD_SCROLLBAR_PAINTER, field, "Ljava/awt/Color;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(catchH);
                mv.visitVarInsn(Opcodes.ASTORE, 1); // discard
                mv.visitLabel(fallback);
                // return new Color(fallbackArgb, true);  // do NOT cache — retry when LAFOptions ready
                mv.visitTypeInsn(Opcodes.NEW, "java/awt/Color");
                mv.visitInsn(Opcodes.DUP);
                mv.visitLdcInsn(Integer.valueOf(fallbackArgb));
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/Color", "<init>", "(IZ)V", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(4, 2);
                mv.visitEnd();
                return new MethodVisitor(Opcodes.ASM9) { };
            }
        };
        cr.accept(cv, 0);
        if (hits[0] == 0) {
            if (!jdScrollbarPatchLogged) { jdScrollbarPatchLogged = true;
                System.out.println("[jd-dialog-agent] CustomScrollbarPainter colour getters not found — left as-is"); }
            return null;
        }
        if (!jdScrollbarPatchLogged) { jdScrollbarPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched CustomScrollbarPainter (" + hits[0]
                    + " getters) — no LAFOptions-not-ready scrollbar crash"); }
        return cw.toByteArray();
    }

    /** getColorArray: never return null entries (LinearGradientPaint NPE). Use stock blues if LAFOptions missing. */
    private static byte[] patchCustomProgressbarPainter(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        final boolean[] hit = { false };
        // Stock classic progress gradient (aRGB) matching Vinylwalk / LAFSettings docs.
        final int[] stock = { 0x5F70CCFF, 0x5F80C7F7, 0x8078C0EF, 0x5F80C7F7, 0x5F70CCFF };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"getColorArray".equals(name) || !"()[Ljava/awt/Color;".equals(desc)) return mv;
                hit[0] = true;
                mv.visitCode();
                // if (colorArray != null) return colorArray;
                Label build = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, JD_PROGRESS_PAINTER, "colorArray", "[Ljava/awt/Color;");
                mv.visitJumpInsn(Opcodes.IFNULL, build);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, JD_PROGRESS_PAINTER, "colorArray", "[Ljava/awt/Color;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitLabel(build);
                mv.visitInsn(Opcodes.ICONST_5);
                mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/awt/Color");
                mv.visitVarInsn(Opcodes.ASTORE, 1);
                Label tryStart = new Label(), tryEnd = new Label(), catchH = new Label(), ensure = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, catchH, "java/lang/Throwable");
                mv.visitLabel(tryStart);
                for (int i = 0; i < 5; i++) {
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitLdcInsn(Integer.valueOf(i));
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/jdownloader/updatev2/gui/LAFOptions",
                            "getInstance", "()Lorg/jdownloader/updatev2/gui/LAFOptions;", false);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/jdownloader/updatev2/gui/LAFOptions",
                            "getColorForProgressbar" + (i + 1), "()Ljava/awt/Color;", false);
                    mv.visitInsn(Opcodes.AASTORE);
                }
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, ensure);
                mv.visitLabel(catchH);
                mv.visitVarInsn(Opcodes.ASTORE, 2); // ignore
                mv.visitLabel(ensure);
                // Replace any null slot with stock colour
                for (int i = 0; i < 5; i++) {
                    Label ok = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitLdcInsn(Integer.valueOf(i));
                    mv.visitInsn(Opcodes.AALOAD);
                    mv.visitJumpInsn(Opcodes.IFNONNULL, ok);
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitLdcInsn(Integer.valueOf(i));
                    mv.visitTypeInsn(Opcodes.NEW, "java/awt/Color");
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitLdcInsn(Integer.valueOf(stock[i]));
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/Color", "<init>", "(IZ)V", false);
                    mv.visitInsn(Opcodes.AASTORE);
                    mv.visitLabel(ok);
                }
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.PUTFIELD, JD_PROGRESS_PAINTER, "colorArray", "[Ljava/awt/Color;");
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(6, 3);
                mv.visitEnd();
                return new MethodVisitor(Opcodes.ASM9) { };
            }
        };
        cr.accept(cv, 0);
        if (!hit[0]) {
            if (!jdProgressPatchLogged) { jdProgressPatchLogged = true;
                System.out.println("[jd-dialog-agent] CustomProgressbarPainter.getColorArray not found — left as-is"); }
            return null;
        }
        if (!jdProgressPatchLogged) { jdProgressPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched CustomProgressbarPainter.getColorArray — no null gradient NPE"); }
        return cw.toByteArray();
    }

    /** Replace getDisabledIcon with: return super.getDisabledIcon(component, icon);
     *  Avoids Class.forName/loadClass(NewTheme) + printStackTrace spam when JDCustom is on App CL. */
    private static byte[] patchJdDefaultGetDisabledIcon(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        final boolean[] hit = { false };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"getDisabledIcon".equals(name)
                        || !"(Ljavax/swing/JComponent;Ljavax/swing/Icon;)Ljavax/swing/Icon;".equals(desc)) {
                    return mv;
                }
                hit[0] = true;
                // Emit replacement body immediately; discard the original method instructions.
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "de/javasoft/plaf/synthetica/SyntheticaLookAndFeel",
                        "getDisabledIcon",
                        "(Ljavax/swing/JComponent;Ljavax/swing/Icon;)Ljavax/swing/Icon;",
                        false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();
                return new MethodVisitor(Opcodes.ASM9) { /* drop original bytes */ };
            }
        };
        cr.accept(cv, 0);
        if (!hit[0]) {
            if (!jdDefaultLafPatchLogged) { jdDefaultLafPatchLogged = true;
                System.out.println("[jd-dialog-agent] JDDefaultLookAndFeel.getDisabledIcon not found — left as-is"); }
            return null;
        }
        if (!jdDefaultLafPatchLogged) { jdDefaultLafPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched JDDefaultLookAndFeel.getDisabledIcon → super (no NewTheme CNFE spam)"); }
        return cw.toByteArray();
    }

    /** For every entry method that dereferences the (possibly-null) circleBar field and receives the
     *  component, prepend:
     *      if (circleBar == null && c instanceof CircledProgressBar) circleBar = (CircledProgressBar) c;
     *      if (circleBar == null) return <0x0 Dimension | void>;
     *  So the delegate always rebinds to its component (real bars size/paint exactly as before) and
     *  can never NPE (broken/transient null bars degrade to zero-size / no-paint instead of crashing).
     *  Covers getPreferredSize (getMinimum/Maximum delegate to it) + paint(g,c) + update(g,c).
     *  Returns patched bytes, or null (= use original) if none of the expected methods are present. */
    private static byte[] patchCircleBarUI(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        final int[] patchedCount = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                final int cIdx;
                final boolean isVoid;
                if ("getPreferredSize".equals(name) && "(Ljavax/swing/JComponent;)Ljava/awt/Dimension;".equals(desc)) {
                    cIdx = 1; isVoid = false;
                } else if (("paint".equals(name) || "update".equals(name))
                        && "(Ljava/awt/Graphics;Ljavax/swing/JComponent;)V".equals(desc)) {
                    cIdx = 2; isVoid = true;
                } else {
                    return mv;
                }
                patchedCount[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // if (circleBar == null && c instanceof CircledProgressBar) circleBar = (CircledProgressBar) c;
                        Label afterRebind = new Label();
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitFieldInsn(Opcodes.GETFIELD, CPB_UI, "circleBar", CPB_FIELD_DESC);
                        visitJumpInsn(Opcodes.IFNONNULL, afterRebind);
                        visitVarInsn(Opcodes.ALOAD, cIdx);
                        visitTypeInsn(Opcodes.INSTANCEOF, CPB_FIELD_OWNER);
                        visitJumpInsn(Opcodes.IFEQ, afterRebind);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitVarInsn(Opcodes.ALOAD, cIdx);
                        visitTypeInsn(Opcodes.CHECKCAST, CPB_FIELD_OWNER);
                        visitFieldInsn(Opcodes.PUTFIELD, CPB_UI, "circleBar", CPB_FIELD_DESC);
                        visitLabel(afterRebind);
                        // if (circleBar == null) return <zero>;  (fallback: c was not a CircledProgressBar)
                        Label proceed = new Label();
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitFieldInsn(Opcodes.GETFIELD, CPB_UI, "circleBar", CPB_FIELD_DESC);
                        visitJumpInsn(Opcodes.IFNONNULL, proceed);
                        if (isVoid) {
                            visitInsn(Opcodes.RETURN);
                        } else {
                            visitTypeInsn(Opcodes.NEW, "java/awt/Dimension");
                            visitInsn(Opcodes.DUP);
                            visitInsn(Opcodes.ICONST_0);
                            visitInsn(Opcodes.ICONST_0);
                            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/awt/Dimension", "<init>", "(II)V", false);
                            visitInsn(Opcodes.ARETURN);
                        }
                        visitLabel(proceed);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (patchedCount[0] == 0) {
            if (!circleBarPatchLogged) { circleBarPatchLogged = true;
                System.out.println("[jd-dialog-agent] BasicCircleProgressBarUI entry methods not found —"
                        + " left as-is (AppWork changed?)"); }
            return null;   // nothing matched -> do not touch (fail-safe)
        }
        if (!circleBarPatchLogged) { circleBarPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched BasicCircleProgressBarUI (" + patchedCount[0]
                    + " methods: getPreferredSize/paint/update, circleBar null-guard) — Event Scripter editor fixed"); }
        return cw.toByteArray();
    }

    /** Guard jsyntaxpane.actions.ScriptAction.install() + getScriptFromURL() to no-op when the static
     *  ScriptEngine `engine` is null (no javax.script JS engine on modern Java — Nashorn removed in 15):
     *  prevents the NPE that aborts the Event Scripter code-editor kit install. Returns patched bytes,
     *  or null (= use original) if the expected methods are absent (jsyntaxpane changed). */
    private static byte[] patchScriptAction(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
        final int[] patchedCount = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                boolean guard =
                       ("install".equals(name)
                          && "(Ljavax/swing/JEditorPane;Ljsyntaxpane/util/Configuration;Ljava/lang/String;)V".equals(desc))
                    || ("getScriptFromURL".equals(name) && "(Ljava/lang/String;)V".equals(desc));
                if (!guard) return mv;
                patchedCount[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // if (ScriptAction.engine == null) return;
                        Label proceed = new Label();
                        visitFieldInsn(Opcodes.GETSTATIC, SA_CLASS, "engine", "Ljavax/script/ScriptEngine;");
                        visitJumpInsn(Opcodes.IFNONNULL, proceed);
                        visitInsn(Opcodes.RETURN);
                        visitLabel(proceed);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (patchedCount[0] == 0) {
            if (!scriptActionPatchLogged) { scriptActionPatchLogged = true;
                System.out.println("[jd-dialog-agent] ScriptAction methods not found — left as-is (jsyntaxpane changed?)"); }
            return null;
        }
        if (!scriptActionPatchLogged) { scriptActionPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched jsyntaxpane ScriptAction (" + patchedCount[0]
                    + " methods, null-engine guard) — Event Scripter code editor opens under FlatLaf"); }
        return cw.toByteArray();
    }

    // --- Package-expander icons (Linkgrabber + download list) --------------------
    // JD's ExtTable draws the package [+]/[-] toggle from the iconset keys
    // tree_plus / tree_minus (IconKey.ICON_PLUS/ICON_MINUS -> FileColumn, shared by
    // the download table and the linkgrabber). JD's own bundled "flat" iconset does
    // NOT contain those two files, so the image ships light-grey ones and the boot
    // script seeds them to /config/JDownloader/themes/flat/... . But JD self-updates
    // its core on every start and re-provisions that on-disk iconset dir AFTER the
    // boot seed, dropping exactly the two files it does not ship itself — so the
    // handle falls back to JD's dark bundled default and vanishes on #161616 (the
    // recurring "black [+]" report). The boot seed can't win that in-process race;
    // the agent runs in EVERY JVM via JAVA_TOOL_OPTIONS, INCLUDING the post-self-
    // update GUI JVM, so restoring the files here — once at premain (before the GUI
    // resolves them) and every tick (self-heal if JD wipes them mid-run) — puts them
    // back before FileColumn reads them, and NewTheme's disk-first lookup finds the
    // light copy. (The Swing Tree.collapsedIcon override elsewhere only covers real
    // JTrees in JD dialogs; it never touched this ExtTable handle.)
    private static final java.io.File EXPANDER_SRC_DIR =
            new java.io.File("/opt/JDownloader/themes-default/flat/org/jdownloader/images");
    private static final java.io.File EXPANDER_DST_DIR =
            new java.io.File("/config/JDownloader/themes/flat/org/jdownloader/images");
    private static final String[] EXPANDER_ICONS = { "tree_plus.svg", "tree_minus.svg" };

    private static void restoreExpanderIcons() {
        for (String name : EXPANDER_ICONS) {
            java.io.File src = new java.io.File(EXPANDER_SRC_DIR, name);
            java.io.File dst = new java.io.File(EXPANDER_DST_DIR, name);
            if (!src.isFile()) continue;                          // source missing (older image) -> nothing to do
            if (dst.isFile() && dst.length() == src.length()) continue; // already the shipped light copy
            try {
                EXPANDER_DST_DIR.mkdirs();
                java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[jd-dialog-agent] restored light package-expander icon: " + name);
            } catch (Throwable ignore) { /* best effort — retried next tick */ }
        }
    }

    // --- BUG 2: rebuild FileColumn's cached [+]/[-] merged toggle icons ---------------
    // restoreExpanderIcons() keeps the LIGHT tree_plus/tree_minus SVGs on disk, but JD's
    // FileColumn bakes the package toggle into four PRIVATE FINAL merged-icon fields in
    // its constructor and never re-reads them, AND loads the toggle with useCache=false
    // (so clearing NewTheme's cache never touches it either). Once JD resolved the DARK
    // bundled fallback (a self-update dropped the two files after the boot seed), the light
    // disk copy changes nothing on screen -> the recurring "black [+]" report. Fix: rebuild
    // the four merged icons from the now-light SVGs (useCache=false = fresh disk read),
    // exactly as FileColumn's ctor does, and overwrite the final fields on every live
    // FileColumn. A non-static final INSTANCE field is writable after setAccessible(true)
    // on JDK 21. Icons are built ONCE and cached; each tick only re-sets fields if reverted.
    private static Object[] toggleIcons = null;   // {packageOpen, packageClosed, archive, archiveOpen}

    private static void healPackageToggle() {
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                List<JTable> tables = new ArrayList<>();
                collectTables(w, tables);
                for (JTable t : tables) {
                    boolean changed = false;
                    for (Object col : extColumns(t)) {
                        if (col == null || !"FileColumn".equals(col.getClass().getSimpleName())) continue;
                        // Build the light merged icons ONCE, using JD's OWN classloader (a live
                        // FileColumn gives it to us) — Class.forName on the agent's system loader
                        // can't see org.jdownloader.*/org.appwork.* (they live on JD's launcher
                        // loader), which is why the disk-restore-only fix never fired.
                        if (toggleIcons == null) {
                            toggleIcons = buildToggleIcons(col.getClass().getClassLoader());
                            if (toggleIcons == null) return;   // not resolvable yet -> retry next tick
                        }
                        changed |= patchFileColumn(col);
                    }
                    if (changed) t.repaint();
                }
            }
        } catch (Throwable ignore) { /* best effort */ }
    }

    /** Build FileColumn's four cell icons LIGHT by composing our OWN guaranteed-light [+]/[-]
     *  boxIcon with JD's package/rar icon — instead of rebuilding from JD's tree_plus/tree_minus
     *  (which can be dark-cached) via ExtMergedIcon. Only JD's package/rar icons are read (via
     *  getIcon), so there is no dependency on the on-disk toggle SVG, a useCache overload, or
     *  ExtMergedIcon's ctor signature. Returns null until NewTheme + the package icons resolve. */
    private static boolean toggleWarnLogged = false;

    private static Object[] buildToggleIcons(ClassLoader cl) {
        try {
            Class<?> newThemeCls = Class.forName("org.jdownloader.images.NewTheme", true, cl);
            Object   newTheme    = newThemeCls.getMethod("I").invoke(null);
            Class<?> iconKey     = Class.forName("org.jdownloader.gui.IconKey", true, cl);
            String kPOpen  = (String) iconKey.getField("ICON_PACKAGE_OPEN").get(null);
            String kPClose = (String) iconKey.getField("ICON_PACKAGE_CLOSED").get(null);
            String kRar    = (String) iconKey.getField("ICON_RAR").get(null);

            // getIcon(String,int) is the current API; an older AppWork snapshot also had a
            // getIcon(String,int,boolean useCache) overload. Discover whichever exists.
            Method getIcon = null; boolean cacheArg = false;
            for (Method m : newThemeCls.getMethods()) {
                if (!"getIcon".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 2 && p[0] == String.class && p[1] == int.class) {
                    if (p.length == 3 && p[2] == boolean.class) { getIcon = m; cacheArg = true; break; }
                    if (p.length == 2 && getIcon == null) getIcon = m;
                }
            }
            if (getIcon == null) return null;

            javax.swing.Icon pOpen  = getIc(getIcon, cacheArg, newTheme, kPOpen, 16);
            javax.swing.Icon pClose = getIc(getIcon, cacheArg, newTheme, kPClose, 16);
            javax.swing.Icon rar    = getIc(getIcon, cacheArg, newTheme, kRar, 16);
            if (pOpen == null || pClose == null) return null;   // not resolvable yet -> retry

            // Order must match patchFileColumn: {packageOpen, packageClosed, archive, archiveOpen}.
            // boxIcon(true) = "+" (collapsed), boxIcon(false) = "-" (expanded).
            Object iconPackageOpen   = mergedToggle(false, pOpen);   // expanded package
            Object iconPackageClosed = mergedToggle(true,  pClose);  // collapsed package
            Object iconArchive       = mergedToggle(true,  rar);     // collapsed archive
            Object iconArchiveOpen   = mergedToggle(false, rar);     // expanded archive
            System.out.println("[jd-dialog-agent] rebuilt light package-toggle icons for FileColumn");
            return new Object[] { iconPackageOpen, iconPackageClosed, iconArchive, iconArchiveOpen };
        } catch (Throwable t) {
            if (!toggleWarnLogged) { toggleWarnLogged = true;
                System.out.println("[jd-dialog-agent] package-toggle rebuild unavailable: " + t); }
            return null;
        }
    }

    /** Invoke NewTheme.getIcon(key,size) or getIcon(key,size,false) depending on the API. */
    private static javax.swing.Icon getIc(Method m, boolean cacheArg, Object theme, String key, int size) throws Exception {
        Object r = cacheArg ? m.invoke(theme, key, size, false) : m.invoke(theme, key, size);
        return (r instanceof javax.swing.Icon) ? (javax.swing.Icon) r : null;
    }

    /** A FileColumn cell icon: our light [+]/[-] box (centered in a 16px slot) + JD's package/
     *  rar icon to its right, matching JD's ExtMergedIcon(toggle,0,0).add(pkg,16,0) geometry. */
    private static javax.swing.Icon mergedToggle(final boolean plus, final javax.swing.Icon pkg) {
        final javax.swing.Icon toggle = boxIcon(plus);   // 11x11 light [+]/[-]
        return new javax.swing.Icon() {
            public int getIconWidth()  { return 16 + (pkg != null ? pkg.getIconWidth() : 0); }
            public int getIconHeight() { return Math.max(16, pkg != null ? pkg.getIconHeight() : 0); }
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                toggle.paintIcon(c, g, x + 2, y + 2);   // center the 11px box in the 16px slot
                if (pkg != null) pkg.paintIcon(c, g, x + 16, y);
            }
        };
    }

    /** Overwrite col's four cached toggle icons if it is a FileColumn. Returns true if any
     *  field changed (needs a repaint). Identity-compared -> idempotent per tick. */
    private static boolean patchFileColumn(Object col) {
        if (col == null || !"FileColumn".equals(col.getClass().getSimpleName())) return false;
        boolean changed = false;
        changed |= setFinalIfDiff(col, "iconPackageOpen",   toggleIcons[0]);
        changed |= setFinalIfDiff(col, "iconPackageClosed", toggleIcons[1]);
        changed |= setFinalIfDiff(col, "iconArchive",       toggleIcons[2]);
        changed |= setFinalIfDiff(col, "iconArchiveOpen",   toggleIcons[3]);
        return changed;
    }

    private static boolean setFinalIfDiff(Object target, String name, Object val) {
        try {
            Field f = target.getClass().getDeclaredField(name);   // declared on FileColumn
            f.setAccessible(true);
            if (f.get(target) == val) return false;               // already ours
            f.set(target, val);                                   // non-static final instance -> OK after setAccessible
            return true;
        } catch (Throwable ignore) { return false; }
    }

    // --------------------------------------------- flatlaf on the system classpath

    /**
     * JD's launcher hosts JD in-process and wires libs/laf/flatlaf.jar only into its
     * own JDLauncherClassLoader (addURL). But UIManager.setLookAndFeel(String) loads
     * the LAF class via SwingUtilities.loadSystemClass, which resolved against the
     * APP classloader here (CI probe 29295757806 stack trace) -> permanent
     * ClassNotFoundException: com.formdev.flatlaf.FlatDarkLaf -> light GUI, even
     * with a perfectly valid, launcher-wired jar. Fix at the sanctioned agent API:
     * append the jar to the SYSTEM classloader search so the by-name load succeeds
     * no matter which context classloader the EDT carries. The launcher loader is
     * parent-first, so JD code resolves the same single copy - no split classes.
     * Retried each tick until the jar exists (fresh installs write it later).
     */
    private static final java.io.File FLATLAF_JAR =
            new java.io.File("/config/JDownloader/libs/laf/flatlaf.jar");
    private static final java.io.File LAF_DIR =
            new java.io.File("/config/JDownloader/libs/laf");
    private static boolean flatlafExposed = false;
    private static final java.util.HashSet<String> syntheticaExposed =
            new java.util.HashSet<String>();

    /** Classic official JD look (Synthetica DEFAULT). Skip FlatLaf dark-chrome work. */
    private static boolean wantClassicLaf() {
        String t = System.getenv("JD_THEME");
        if (t == null || t.trim().isEmpty()) return false;
        return "jddefault".equals(t.trim().toLowerCase());
    }

    private static void exposeFlatlafToSystemLoader() {
        if (wantClassicLaf() || flatlafExposed || INSTRUMENTATION == null || !FLATLAF_JAR.isFile()) return;
        try {
            // the JarFile constructor validates the zip; a truncated install throws
            // and we simply retry on a later tick (the container boot heal replaces it)
            INSTRUMENTATION.appendToSystemClassLoaderSearch(new java.util.jar.JarFile(FLATLAF_JAR));
            flatlafExposed = true;
            System.out.println("[jd-dialog-agent] appended flatlaf.jar to the system classloader (LAF-by-name can resolve now)");
        } catch (Throwable ignore) { }
    }

    /** Same classloader fix as FlatLaf for Synthetica / JDDefaultLookAndFeel.
     *  Include syntheticaJDCustom.jar (DEFAULT LAF assets) and every synthetica*.jar.
     *  Exposing only synthetica.jar is not enough — JD falls back to Metal. */
    private static void exposeSyntheticaToSystemLoader() {
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

    private static void writeFile(java.io.File f, String content) {
        try {
            java.nio.file.Files.write(f.toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // /tmp unwritable — markers are best-effort, the launcher has fallbacks
        }
    }

    /** Record the ACTIVE look-and-feel class every few seconds (ground truth for READY). */
    private static void writeLafMarker() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null) return;
        String cn = laf.getClass().getName();
        if (!cn.equals(lastLafWritten) || !LAF_FILE.exists()) {
            writeFile(LAF_FILE, cn);
            lastLafWritten = cn;
        }
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
        if (wantClassicLaf()) {
            exposeSyntheticaToSystemLoader();
            seedClassicLafColors();
            handleDialogs();
            // Classic still gets the compact-toolbar / speed-graph growth.
            widenSpeedEditors();
            growSpeedMeter();
            replaceSpeedGraph();
        } else {
            exposeFlatlafToSystemLoader();
            restoreExpanderIcons();
            healPackageToggle();          // BUG 2: rebuild FileColumn's cached [+]/[-] toggle icons
            handleDialogs();
            registerDefaultsSource();
            applyCustomDefaults();
            enforceDarkChrome();
            themeKaynExtras();            // Kayn: menu-toggle check state + light overview corner icons
            retintProgressBars();
            widenSpeedEditors();
            growSpeedMeter();
            replaceSpeedGraph();
        }
        if (++lafTick >= 12) {   // every ~5s (ticks run every 400ms)
            lafTick = 0;
            writeLafMarker();
            if (GEO_DEBUG) dumpGeometry();
        }
    }

    // ------------------------------------------------------ Kayn plain-dark fixes
    // Two JD_Plain_Dark visibility gaps reported on the forum (kayn), reproduced live on the container:
    //   #2  The LinkGrabber "Customize this Bottom Panel" toggle menu (ExtCheckBoxMenuItem items) shows NO
    //       on/off state: FlatLaf's MenuItem.checkIcon is null for these items (they are not JCheckBoxMenuItem),
    //       so a checked toggle is indistinguishable from an unchecked one. Overlay a neutral check badge on the
    //       item's own glyph, painted only while it is selected (paint-time state -> no listener race).
    //   #1  The download OverviewPanel corner buttons (settings wrench + close X) are a near-invisible dark grey.
    //       Those buttons IGNORE setIcon (they re-derive their glyph each paint), so own their paint with a tiny
    //       ButtonUI that draws the button's own glyph tinted light. Both are idempotent (guarded on a client
    //       property) so the ~400ms tick never re-does work -> no CPU spin.
    private static void themeKaynExtras() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) themeKaynExtrasIn(w);
        }
    }
    private static void themeKaynExtrasIn(Component c) {
        try {
            if (c instanceof javax.swing.JMenuItem) {
                markCheckToggle((javax.swing.JMenuItem) c);
            } else if (c instanceof AbstractButton && c.getClass().getName().contains(".overviewpanel.")) {
                installOverviewCornerButton((AbstractButton) c);
            }
        } catch (Throwable ignore) { }
        if (c instanceof Container) {
            for (Component ch : ((Container) c).getComponents()) themeKaynExtrasIn(ch);
        }
    }

    /** #2: give a checkbox-style toggle menu item a visible ON state on plain dark. Wrap its glyph (or a blank
     *  slot, for items that ship no icon) in a paint-time icon that draws a neutral check badge only while the
     *  item is selected. Idempotent: once our wrapper is the icon, the tick skips it. */
    private static void markCheckToggle(javax.swing.JMenuItem mi) {
        boolean checkbox = (mi instanceof javax.swing.JCheckBoxMenuItem)
                || mi.getClass().getSimpleName().contains("CheckBox");
        if (!checkbox) return;
        javax.swing.Icon cur = mi.getIcon();
        Object mine = mi.getClientProperty("jdp.kToggle");
        if (cur == mine && mine != null) return;                 // already wrapped
        javax.swing.Icon base = (cur != null) ? cur : K_BLANK16; // no-icon items still get a slot for the badge
        javax.swing.Icon wrapped = stateCheckIcon(base, mi);
        mi.putClientProperty("jdp.kToggle", wrapped);
        mi.setIcon(wrapped);
        mi.setSelectedIcon(wrapped);
    }

    /** A menu-item icon that draws a neutral check BADGE (light disc + dark check, bottom-right) only while the
     *  item is selected. Paint-time state check -> no listener/timing race. */
    private static javax.swing.Icon stateCheckIcon(final javax.swing.Icon base, final javax.swing.JMenuItem mi) {
        return new javax.swing.Icon() {
            public int getIconWidth()  { return base.getIconWidth(); }
            public int getIconHeight() { return base.getIconHeight(); }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                if (!mi.isSelected()) return;
                int w = base.getIconWidth(), h = base.getIconHeight();
                int d = Math.max(9, Math.round(w * 0.62f));
                int bx = x + w - d, by = y + h - d;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(K_CHECK_DISC);
                g2.fillOval(bx, by, d, d);
                g2.setColor(K_CHECK_MARK);
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.3f, d / 6.5f),
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                int[] xs = { bx + Math.round(d * 0.26f), bx + Math.round(d * 0.44f), bx + Math.round(d * 0.74f) };
                int[] ys = { by + Math.round(d * 0.52f), by + Math.round(d * 0.68f), by + Math.round(d * 0.34f) };
                g2.drawPolyline(xs, ys, 3);
                g2.dispose();
            }
        };
    }

    /** #1: the OverviewPanel corner buttons ignore setIcon (they re-derive a dark glyph each paint). Own their
     *  paint with a tiny ButtonUI that floods the panel background then draws the button's OWN glyph tinted light.
     *  Installed once and re-installed only if JD swaps the UI back; event-driven paint -> no tick, no spin. */
    private static void installOverviewCornerButton(final AbstractButton b) {
        if (!(b.getUI() instanceof OverviewCornerUI)) {
            try { b.setUI(new OverviewCornerUI()); b.setBorder(null); } catch (Throwable ignore) { }
        }
    }
    private static final class OverviewCornerUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void update(Graphics g, JComponent c) {
            Color bg = (c.getParent() != null && c.getParent().getBackground() != null)
                    ? c.getParent().getBackground() : BG;
            g.setColor(bg);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }
        @Override public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.Icon ic = b.getIcon();
            if (ic == null) return;
            javax.swing.Icon m = cornerGlyphTint(b, ic);
            if (m == null) return;
            int ix = (c.getWidth() - m.getIconWidth()) / 2, iy = (c.getHeight() - m.getIconHeight()) / 2;
            m.paintIcon(c, g, ix, iy);
        }
    }
    /** Cache the light tint of a corner button's glyph, keyed on the base-icon identity, so a button that hands
     *  back a fresh icon each getIcon() still only re-tints when the glyph actually changes. */
    private static javax.swing.Icon cornerGlyphTint(AbstractButton b, javax.swing.Icon ic) {
        if (ic == b.getClientProperty("jdp.kCornerBase") && b.getClientProperty("jdp.kCornerLit") instanceof javax.swing.Icon)
            return (javax.swing.Icon) b.getClientProperty("jdp.kCornerLit");
        javax.swing.Icon m = tintSolidLight(ic, K_CORNER_LIGHT);
        if (m == null) m = ic;
        b.putClientProperty("jdp.kCornerBase", ic);
        b.putClientProperty("jdp.kCornerLit", m);
        return m;
    }
    /** Recolour every non-transparent pixel of an icon to `tone`, preserving its alpha (so the glyph shape and
     *  anti-aliasing survive). Falls back to the original icon if it renders empty. */
    private static javax.swing.Icon tintSolidLight(javax.swing.Icon ic, Color tone) {
        try {
            int w = ic.getIconWidth(), h = ic.getIconHeight();
            if (w <= 0 || h <= 0) return ic;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try { ic.paintIcon(null, g, 0, 0); } catch (Throwable t) { g.dispose(); return ic; }
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

    // Opt-in geometry logging (JD_DEBUG_GEO=1). Off by default so a box test / the
    // forum reporter get clean logs; flip it on to re-diagnose layout regressions.
    private static final boolean GEO_DEBUG =
            "1".equals(System.getenv("JD_DEBUG_GEO"))
            || "true".equalsIgnoreCase(System.getenv("JD_DEBUG_GEO"));

    // ---------------------------------------------------------- geometry probe

    /**
     * Diagnostic ground truth for the persistent half-height-graph reports: every
     * ~5s dump the real pixel geometry around the speed graph to stdout, so
     * `docker logs` shows what the layout ACTUALLY did instead of what we assume.
     * One compact line per window that contains a SpeedMeterPanel.
     */
    private static void dumpGeometry() {
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                JComponent nat = findSpeedMeter(w);
                if (nat == null) continue;
                StringBuilder sb = new StringBuilder("[jd-dialog-agent] GEO win=");
                sb.append(w.getClass().getSimpleName()).append(b(w));
                java.awt.Insets in = w.getInsets();
                sb.append(" insets=").append(in.top).append('/').append(in.left)
                  .append('/').append(in.bottom).append('/').append(in.right);
                if (w instanceof Frame) sb.append(" undec=").append(((Frame) w).isUndecorated());
                try {
                    Object mb = w.getClass().getMethod("getJMenuBar").invoke(w);
                    if (mb instanceof Component) sb.append(" menubar=").append(b((Component) mb));
                } catch (Exception ignore) { }
                Container tb = nat.getParent();
                List<String> chain = new ArrayList<>();
                for (Container p = tb; p != null && p != w; p = p.getParent()) {
                    chain.add(p.getClass().getSimpleName() + b(p));
                }
                Collections.reverse(chain);
                sb.append(" chain=").append(String.join(">", chain));
                LayoutManager lm = (tb == null) ? null : tb.getLayout();
                if (lm != null && lm.getClass().getName().contains("MigLayout")) {
                    sb.append(" lm@").append(Integer.toHexString(System.identityHashCode(lm)))
                      .append(" grown=").append(GROWN_LAYOUTS.contains(lm));
                    try {
                        sb.append(" rows=").append(lm.getClass().getMethod("getRowConstraints").invoke(lm));
                    } catch (Exception e) { sb.append(" rows=?"); }
                } else if (lm != null) {
                    sb.append(" lm=").append(lm.getClass().getSimpleName());
                }
                if (tb instanceof JComponent) {
                    Dimension p = tb.getPreferredSize();
                    sb.append(" tbPref=").append(p.width).append('x').append(p.height);
                }
                Container cp = (tb == null) ? null : tb.getParent();
                LayoutManager plm = (cp == null) ? null : cp.getLayout();
                if (plm != null && plm.getClass().getName().contains("MigLayout")) {
                    try {
                        Object cc = plm.getClass()
                                .getMethod("getComponentConstraints", Component.class)
                                .invoke(plm, tb);
                        sb.append(" tbCC=\"").append(cc).append('"');
                    } catch (Exception e) { sb.append(" tbCC=?"); }
                } else if (plm != null) {
                    sb.append(" cpLm=").append(plm.getClass().getSimpleName());
                }
                sb.append(" native[vis=").append(nat.isVisible()).append(' ').append(b(nat)).append(']');
                if (ownGraph == null) {
                    sb.append(" own=null");
                } else {
                    sb.append(" own[parent=").append(ownGraph.getParent() == tb ? "toolbar"
                              : String.valueOf(ownGraph.getParent()))
                      .append(" showing=").append(ownGraph.isShowing())
                      .append(' ').append(b(ownGraph)).append(']');
                }
                System.out.println(sb);
            }
        } catch (Exception ignore) { }
    }

    private static String b(Component c) {
        java.awt.Rectangle r = c.getBounds();
        return "(" + r.x + "," + r.y + " " + r.width + "x" + r.height + ")";
    }

    private static JComponent findSpeedMeter(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && ch.getClass().getName().endsWith(".SpeedMeterPanel")) {
                return (JComponent) ch;
            }
            if (ch instanceof Container) {
                JComponent r = findSpeedMeter((Container) ch);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------ v3 custom defaults source

    /** True once JD's MAIN window (not a splash) is showing. */
    private static boolean mainWindowShowing() {
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400) return true;
        }
        return false;
    }

    /**
     * Register /opt/JDownloader/flatlaf-defaults as a FlatLaf custom-defaults source —
     * through the classloader that actually loaded FlatLaf (JD's, not ours). Tried as
     * soon as the FlatLaf class exists in the JVM: if that beats JD's setLookAndFeel,
     * the very first frame renders with our colours and no re-apply is needed.
     */
    private static void registerDefaultsSource() {
        if (defaultsRegistered || !DEFAULTS_DIR.isDirectory()) return;
        Class<?> flatLaf = null;
        // Shortcut: an active FlatLaf LAF hands us the right classloader directly.
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf != null && laf.getClass().getName().toLowerCase().contains("flat")) {
            try {
                flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            } catch (Throwable ignore) { }
        }
        // Pre-LAF: scan loaded classes (every 4th tick, first ~5 min only — FlatLaf
        // loads within seconds of JD's GUI bootstrap when it is installed; the throttle
        // caps the EDT cost of getAllLoadedClasses() in the no-FlatLaf degenerate case).
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
            // API missing (ancient/renamed FlatLaf)? Give up cleanly — the legacy
            // UIManager remap in enforceDarkChrome() still delivers a dark chrome.
            defaultsRegistered = true;
            lafRefreshDone     = true;
            System.out.println("[jd-dialog-agent] registerCustomDefaultsSource unavailable ("
                    + e.getClass().getSimpleName() + ") — legacy chrome remap only");
        }
    }

    /**
     * Race-proof registration, fired from the class-load transformer the moment FlatLaf's
     * base class is defined (well before JD builds its GUI or calls setLookAndFeel). We
     * register on a SEPARATE daemon thread so we never recurse into the in-progress class
     * definition on the defining thread: loadClass blocks until FlatLaf is fully defined,
     * then registerCustomDefaultsSource runs before JD reads the LAF defaults, so the very
     * first paint uses our colours. This removes the dependency on the fragile live-re-apply
     * fallback, which lost the race on cold/slow boots and left stock-grey FlatDarkLaf.
     */
    private static void registerDefaultsOnFlatLafLoad(final ClassLoader loader) {
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
                            + e.getClass().getSimpleName() + ") — tick-loop registration will retry");
                }
            }
        }, "jd-laf-early-register");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Make the registered defaults take effect exactly once. If registration won the
     * race against JD's setLookAndFeel, the sentinel (Panel.background == #161616)
     * already matches and nothing needs to happen. Otherwise re-apply the CURRENT LAF
     * once (fresh instance -> getDefaults() re-reads the custom source) and refresh all
     * windows — only after the main window is stable, the exact gate that has kept
     * enforceDarkChrome() clear of the CircleProgressBarUI boot-loop for months.
     */
    private static void applyCustomDefaults() {
        if (lafRefreshDone) return;
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        if (defaultsRegistered) {
            Color bg = UIManager.getColor("Panel.background");
            if (bg != null && (bg.getRGB() & 0xFFFFFF) == 0x161616) {
                // Registration beat JD's LAF apply — defaults are already live.
                lafRefreshDone = true;
                System.out.println("[jd-dialog-agent] custom defaults active from first paint (no re-apply needed)");
                return;
            }

            // PREFERRED path: JD applies its LAF seconds BEFORE it builds the GUI.
            // While no frame exists yet, re-applying the LAF is a pure defaults swap —
            // there is no live component tree to update (updateUI deliberately NOT
            // called), so nothing can be corrupted, and every component JD builds
            // next is created with our colours from the start. Hot-swapping the LAF
            // on the LIVE frame instead (the old one-shot) broke JD's repaint: JD
            // itself never swaps a LAF at runtime — it always restarts — because its
            // AppWork components don't survive updateUI cleanly (ghosted/overlapping
            // panels when switching tabs).
            if (Frame.getFrames().length == 0) {
                try {
                    UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
                    System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                            + " with custom defaults (pre-GUI, no components yet)");
                } catch (Throwable e) {
                    System.out.println("[jd-dialog-agent] pre-GUI LAF re-apply failed ("
                            + e.getClass().getSimpleName() + ") — legacy chrome remap only");
                }
                lafRefreshDone = true;
                return;
            }
        }

        // Frames already exist (or registration is still pending) — gate everything
        // below on the main window being shown and stable.
        if (!mainWindowShowing()) { lafStableTicks = 0; return; }
        if (++lafStableTicks < 4) return;   // ~1.6 s after the main frame shows

        if (!defaultsRegistered) {
            // Registration hasn't happened yet (class scan still looking) — wait up to
            // ~30 s of stable GUI, then fall back to the legacy remap alone.
            if (++registrationWait < 75) return;
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] defaults source never registered — legacy chrome remap only");
            return;
        }

        // RARE fallback: the pre-GUI window was missed. A live re-apply must refresh
        // the existing tree (updateUI) and can leave repaint artifacts in JD's custom
        // components — logged loudly so field reports identify this path.
        try {
            UIManager.setLookAndFeel((LookAndFeel) laf.getClass().getDeclaredConstructor().newInstance());
            Class<?> flatLaf = laf.getClass().getClassLoader().loadClass("com.formdev.flatlaf.FlatLaf");
            flatLaf.getMethod("updateUI").invoke(null);
            System.out.println("[jd-dialog-agent] re-applied " + laf.getClass().getSimpleName()
                    + " with custom defaults (LIVE one-shot — missed the pre-GUI window)");
        } catch (Throwable e) {
            System.out.println("[jd-dialog-agent] LAF re-apply failed ("
                    + e.getClass().getSimpleName() + ") — legacy chrome remap only");
        }
        lafRefreshDone = true;   // one attempt, never a loop — remap polish runs next
    }

    // -------------------------------------------------------- speed graph replacement

    /**
     * AppWork's Graph paints every sample as `(int)(height * value * 0.9) / max` where
     * value is the raw download speed in BYTES/s. `height * value` is an int*int
     * product that silently overflows above ~2.1e9 - i.e. from ~34 MiB/s at our 64px
     * row (~67 MiB/s at the stock 32px). Overflowed samples wrap low or clip below the
     * widget, so at gigabit speeds the graph permanently paints at a fraction of its
     * height. Nothing configurable fixes that, so we hide the native SpeedMeterPanel
     * and paint our own graph with long arithmetic: full height at any speed, same
     * look (colours from LAFOptions, texts reused from the native panel, limiter band
     * included). Mouse events are forwarded to the hidden native panel so its
     * speed-limit menu keeps working. JD's updateToolbar() rebuilds (removeAll) are
     * healed by the tick: when the native panel reappears without ours, we re-attach.
     */
    private static CarbonSpeedGraph ownGraph = null;

    private static void replaceSpeedGraph() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) replaceSpeedGraphIn(w);
        }
    }

    private static void replaceSpeedGraphIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent && child.getClass().getName().endsWith(".SpeedMeterPanel")) {
                attachOwnGraph((JComponent) child);
                return;
            }
            if (child instanceof Container) replaceSpeedGraphIn((Container) child);
        }
    }

    private static void attachOwnGraph(JComponent nativePanel) {
        try {
            Container parent = nativePanel.getParent();
            if (parent == null) return;

            boolean ourPresent = false;
            for (Component comp : parent.getComponents()) {
                if (comp instanceof CarbonSpeedGraph) { ourPresent = true; break; }
            }
            if (ownGraph == null) ownGraph = new CarbonSpeedGraph();
            ownGraph.bindNative(nativePanel);

            if (nativePanel.isVisible()) {
                hideNativeInLayout(parent, nativePanel);
                nativePanel.setVisible(false);
            }
            if (!ourPresent) {
                // a JD updateToolbar() rebuild re-added the (still hidden) native panel
                // WITHOUT hidemode 3: at default hidemode 0 an invisible component still
                // reserves its up-to-300px cell and squeezes our graph aside. ourPresent
                // is false exactly once per rebuild (removeAll dropped us), so re-apply
                // the exclusion here before adding our graph back.
                hideNativeInLayout(parent, nativePanel);
                parent.add(ownGraph, "width 32:300:300,pushy,growy");
                parent.revalidate();
                parent.repaint();
                System.out.println("[jd-dialog-agent] replaced the speed graph (native math overflows above ~34 MiB/s)");
            }
        } catch (Exception ignore) { }
    }

    /**
     * Exclude the hidden native panel from the layout (hidemode 3) while keeping it
     * alive for its fetcher thread, localized strings and the speed-limit menu.
     */
    private static void hideNativeInLayout(Container parent, JComponent nativePanel) {
        LayoutManager lm = parent.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method m = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            m.invoke(lm, nativePanel, "width 32:300:300,pushy,growy,hidemode 3");
        } catch (Exception ignore) { }
    }

    /**
     * Minimal, overflow-free speed graph: ring buffer of long samples polled from
     * DownloadWatchDog (reflection - the agent compiles against the JDK alone), the
     * same visual language as the native one (current-speed gradient polygon,
     * semi-transparent average overlay, limiter band, right-aligned text lines).
     */
    private static final class CarbonSpeedGraph extends JComponent {
        private static final int SAMPLES = 90;
        private final long[] samples  = new long[SAMPLES];
        private final long[] averages = new long[SAMPLES];
        private int  head = 0;
        private long current = 0, average = 0, limit = 0;
        private volatile JComponent nativePanel = null;

        private Color colTop    = new Color(0x3f, 0xb9, 0x3f);
        private Color colBottom = new Color(0x14, 0x46, 0x14);
        private Color colAvg    = new Color(0x86, 0xd9, 0x86);
        private Color colText   = new Color(0xf4, 0xf4, 0xf4);
        private Color colLimit  = new Color(0xd9, 0x53, 0x53);

        CarbonSpeedGraph() {
            setOpaque(false);
            loadLafColors();
            new javax.swing.Timer(500, e -> sample()).start();
            MouseAdapter fwd = new MouseAdapter() {
                private void fw(MouseEvent e) {
                    JComponent np = nativePanel;
                    if (np != null) np.dispatchEvent(SwingUtilities.convertMouseEvent(CarbonSpeedGraph.this, e, np));
                }
                @Override public void mouseClicked(MouseEvent e)  { fw(e); }
                @Override public void mousePressed(MouseEvent e)  { fw(e); }
                @Override public void mouseReleased(MouseEvent e) { fw(e); }
            };
            addMouseListener(fwd);
        }

        void bindNative(JComponent np) { this.nativePanel = np; }

        private void loadLafColors() {
            try {
                Class<?> laf = Class.forName("org.jdownloader.updatev2.gui.LAFOptions");
                Object inst = laf.getMethod("getInstance").invoke(null);
                Object top = laf.getMethod("getColorForSpeedmeterCurrentTop").invoke(inst);
                Object bot = laf.getMethod("getColorForSpeedmeterCurrentBottom").invoke(inst);
                Object avg = laf.getMethod("getColorForSpeedMeterAverage").invoke(inst);
                Object txt = laf.getMethod("getColorForSpeedMeterText").invoke(inst);
                if (top instanceof Color) colTop = (Color) top;
                if (bot instanceof Color) colBottom = (Color) bot;
                if (avg instanceof Color) colAvg = (Color) avg;
                if (txt instanceof Color) colText = (Color) txt;
            } catch (Throwable ignore) { /* fallback palette above */ }
        }

        private void sample() {
            long v = readSpeedSafe();
            long lim = readLimit();
            synchronized (samples) {
                current = v;
                limit = lim;
                samples[head] = v;
                long sum = 0;
                for (long s : samples) sum += s;
                average = sum / SAMPLES;
                averages[head] = average;
                head = (head + 1) % SAMPLES;
            }
            repaint();
        }

        /** Primary: the native panel's own public getValue() (same number the native
         *  graph would plot). Fallback: DownloadWatchDog reflection. */
        private long readSpeedSafe() {
            JComponent np = nativePanel;
            if (np != null) {
                try {
                    Object v = np.getClass().getMethod("getValue").invoke(np);
                    if (v instanceof Number) return Math.max(0L, ((Number) v).longValue());
                } catch (Throwable ignore) { }
            }
            return readSpeed();
        }

        private static long readSpeed() {
            try {
                Class<?> wd = Class.forName("jd.controlling.downloadcontroller.DownloadWatchDog");
                Object inst = wd.getMethod("getInstance").invoke(null);
                Object dsm = inst.getClass().getMethod("getDownloadSpeedManager").invoke(inst);
                Object spd = dsm.getClass().getMethod("getSpeed").invoke(dsm);
                return spd instanceof Number ? ((Number) spd).longValue() : 0L;
            } catch (Throwable t) { return 0L; }
        }

        private long readLimit() {
            JComponent np = nativePanel;
            if (np == null) return 0L;
            try {
                Object arr = np.getClass().getMethod("getLimiter").invoke(np);
                if (arr instanceof Object[]) {
                    for (Object l : (Object[]) arr) {
                        if (l == null) continue;
                        Object v = l.getClass().getMethod("getValue").invoke(l);
                        long lv = v instanceof Number ? ((Number) v).longValue() : 0L;
                        if (lv > 0) return lv;
                    }
                }
            } catch (Throwable ignore) { }
            return 0L;
        }

        private String nativeString(String method) {
            JComponent np = nativePanel;
            if (np == null) return null;
            try {
                Object s = np.getClass().getMethod(method).invoke(np);
                return s instanceof String ? (String) s : null;
            } catch (Throwable t) { return null; }
        }

        private static String fmt(long bytes) {
            if (bytes >= 1048576L) return String.format("%.2f MiB/s", bytes / 1048576.0);
            return String.format("%.0f KiB/s", bytes / 1024.0);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int w = getWidth(), h = getHeight();
                if (w <= 2 || h <= 2) return;

                final long[] snap, asnap;
                final int hd;
                final long lim, cur, avg;
                synchronized (samples) {
                    snap = samples.clone();
                    asnap = averages.clone();
                    hd = head;
                    lim = limit;
                    cur = current;
                    avg = average;
                }

                long max = 10;
                for (long v : snap) if (v > max) max = v;
                for (long v : asnap) if (v > max) max = v;
                if (lim > max) max = lim;

                // polygons, oldest -> newest, LONG math: h * value never overflows
                final Polygon poly = new Polygon();
                final Polygon apoly = new Polygon();
                poly.addPoint(0, h);
                apoly.addPoint(0, h);
                for (int x = 0; x < SAMPLES; x++) {
                    final int idx = (hd + x) % SAMPLES;
                    final int px = (int) ((long) x * w / (SAMPLES - 1));
                    poly.addPoint(px, h - (int) (h * snap[idx] * 9L / (10L * max)));
                    apoly.addPoint(px, h - (int) (h * asnap[idx] * 9L / (10L * max)));
                }
                poly.addPoint(w, h);
                apoly.addPoint(w, h);

                g2.setPaint(new GradientPaint(w / 2f, 0, colTop, w / 2f, h, colBottom));
                g2.fill(poly);
                g2.setColor(colBottom);
                g2.draw(poly);

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2.setColor(colAvg);
                g2.fill(apoly);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
                g2.draw(apoly);

                if (lim > 0) {
                    final int ly = h - (int) (h * lim * 9L / (10L * max));
                    g2.setColor(new Color(colLimit.getRed(), colLimit.getGreen(), colLimit.getBlue(), 170));
                    g2.fillRect(0, Math.max(0, ly), w, Math.max(2, h / 14));
                }

                // right-aligned texts, reusing the native panel's localized strings
                g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                final int pad = 3;
                int ty = 12;
                if (lim > 0) {
                    String ls = null;
                    try {
                        JComponent np = nativePanel;
                        if (np != null) {
                            Object arr = np.getClass().getMethod("getLimiter").invoke(np);
                            if (arr instanceof Object[] && ((Object[]) arr).length > 0 && ((Object[]) arr)[0] != null) {
                                Object s = ((Object[]) arr)[0].getClass().getMethod("getString").invoke(((Object[]) arr)[0]);
                                if (s instanceof String) ls = (String) s;
                            }
                        }
                    } catch (Throwable ignore) { }
                    if (ls == null) ls = "Limit: " + fmt(lim);
                    g2.setColor(colLimit);
                    g2.drawString(ls, w - g2.getFontMetrics().stringWidth(ls) - pad, ty);
                    ty += 13;
                }
                String as = nativeString("getAverageSpeedString");
                String cs = nativeString("getSpeedString");
                String line = ((as != null ? as : "Ø " + fmt(avg)) + "  " + (cs != null ? cs : fmt(cur))).trim();
                g2.setColor(colText);
                g2.drawString(line, w - g2.getFontMetrics().stringWidth(line) - pad, ty);
            } finally {
                g2.dispose();
            }
        }
    }

    // -------------------------------------------------------- speed graph height

    /**
     * JD's download graph (SpeedMeterPanel) lives in the MainToolBar whose single
     * MigLayout row is HARDCODED to 32px ("[grow,32!]") - there is no config key for
     * it. With the premium banner disabled the corner looks half-empty and the graph
     * cramped, so we grow the toolbar row at runtime; the speedmeter is added with
     * "pushy,growy" and follows, the 32px tool buttons stay centered (the toolbar is
     * docked NORTH, so the frame grants it its preferred height - no clipping).
     *
     * Guard granularity matters: JD's updateToolbar() rebuild does removeAll() and
     * installs a brand-NEW MigLayout instance hardcoded back to "[grow,32!]", so a
     * per-component guard (client property) blocks forever after the first rebuild,
     * while re-applying on a height heuristic fights JD's layout every tick. Grow
     * exactly ONCE PER LayoutManager INSTANCE instead: each rebuild's fresh MigLayout
     * gets grown once, an already-grown instance is left alone.
     */
    private static final int SPEEDMETER_ROW_PX = 64;
    private static final Set<LayoutManager> GROWN_LAYOUTS =
            Collections.newSetFromMap(new WeakHashMap<LayoutManager, Boolean>());

    // Opt-out of the 64px toolbar row (stock JD is 32px). Same env style as JD_DEBUG_GEO.
    private static final boolean COMPACT_TOOLBAR =
            "1".equals(System.getenv("JD_COMPACT_TOOLBAR"))
            || "true".equalsIgnoreCase(System.getenv("JD_COMPACT_TOOLBAR"));

    private static void growSpeedMeter() {
        if (COMPACT_TOOLBAR) return; // leave MainToolBar at JD's stock ~32px row
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) growSpeedMeterIn(w);
        }
    }

    private static void growSpeedMeterIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child.getClass().getName().endsWith(".SpeedMeterPanel")) {
                growToolbarRow(child.getParent());
            } else if (child instanceof Container) {
                growSpeedMeterIn((Container) child);
            }
        }
    }

    private static void growToolbarRow(Container toolbar) {
        if (!(toolbar instanceof JComponent)) return;
        JComponent tb = (JComponent) toolbar;
        LayoutManager lm = tb.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        if (GROWN_LAYOUTS.add(lm)) {
            try {
                Method m = lm.getClass().getMethod("setRowConstraints", Object.class);
                m.invoke(lm, "[grow," + SPEEDMETER_ROW_PX + "!]");
                tb.revalidate();
                tb.repaint();
                System.out.println("[jd-dialog-agent] grew the speed graph row to " + SPEEDMETER_ROW_PX + "px");
            } catch (Exception ignore) {
                // setRowConstraints absent -> leave the toolbar as-is; the marker stays
                // so the same broken instance isn't retried every 400ms tick
            }
        }
        pinToolbarHeight(tb);
    }

    /**
     * Growing the toolbar's OWN row is not enough: the CI geometry probe showed the
     * content pane keeps granting the toolbar its pre-grow strip (row=[grow,64!],
     * tbPref=68, but MainToolBar bounds stuck at 36px -> the graph's bottom half is
     * clipped). JD adds the toolbar to the frame with "dock NORTH" (a MigLayout dock
     * whose measurement does not follow the child's later growth), so pin the height
     * explicitly in the PARENT's component constraint for the toolbar. Idempotent:
     * skipped once the current constraint already carries our height pin.
     */
    private static void pinToolbarHeight(JComponent tb) {
        Container cp = tb.getParent();
        LayoutManager plm = (cp == null) ? null : cp.getLayout();
        if (plm == null || !plm.getClass().getName().contains("MigLayout")) return;
        try {
            Method gc = plm.getClass().getMethod("getComponentConstraints", Component.class);
            Object cur = gc.invoke(plm, tb);
            String cc = (cur == null) ? "" : cur.toString();
            if (cc.contains("height ")) return;   // already pinned
            int ph = tb.getPreferredSize().height; // toolbar's own grown row + gaps
            if (ph < SPEEDMETER_ROW_PX) ph = SPEEDMETER_ROW_PX;
            String pinned = (cc.isEmpty() ? "" : cc + ",") + "height " + ph + "!";
            Method sc = plm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            sc.invoke(plm, tb, pinned);
            Window win = SwingUtilities.getWindowAncestor(tb);
            if (win != null) { win.invalidate(); win.validate(); win.repaint(); }
            System.out.println("[jd-dialog-agent] pinned the toolbar strip to " + ph
                    + "px in the content pane (was: \"" + cc + "\")");
        } catch (Exception ignore) {
            // parent constraint not reachable -> the row grow alone has to do
        }
    }

    // -------------------------------------------------------- speed editor width

    /**
     * JD's speed-limit menu field (jd.gui.swing.jdgui.menu.SpeedlimitEditor) is laid
     * out with a FIXED MigLayout width: MenuEditor.getEditorWidth() hardcodes it to
     * fit "500.00 KB/s" (+30px), so a higher limit such as "10.216,00 MiB/s" is
     * clipped and the value can't be read. We relax the spinner's width constraint at
     * runtime and grow the enclosing popup so the whole value shows. The editor is
     * rebuilt every time the menu opens, so this re-applies on each open; a per-
     * instance client-property guard keeps it from relaying an already-widened editor
     * on every tick. All reflection so the agent still compiles against the JDK alone.
     */
    private static final String WIDENED = "jdp.speedWidened";

    private static void widenSpeedEditors() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) widenSpeedIn(w);
        }
    }

    private static void widenSpeedIn(Container c) {
        for (Component child : c.getComponents()) {
            if (isSpeedEditor(child.getClass())) {
                if (child instanceof Container) widenEditor((Container) child);
            } else if (child instanceof Container) {
                widenSpeedIn((Container) child);
            }
        }
    }

    /** True if the class IS or EXTENDS jd...menu.SpeedlimitEditor (JD adds it as an
     *  anonymous subclass, so we must check the whole superclass chain). */
    private static boolean isSpeedEditor(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().endsWith(".SpeedlimitEditor")) return true;
        }
        return false;
    }

    private static void widenEditor(Container editor) {
        if (!(editor instanceof JComponent)) return;
        JComponent jc = (JComponent) editor;
        if (Boolean.TRUE.equals(jc.getClientProperty(WIDENED))) return;

        JSpinner spinner = null;
        for (Component ch : editor.getComponents()) {
            if (ch instanceof JSpinner) { spinner = (JSpinner) ch; break; }
        }
        if (spinner == null) return;

        LayoutManager lm = editor.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;

        // Mirror JD's own formula (label width + 30 for the spinner arrows/insets) but
        // with a long sample so the field fits any realistic limit incl. its unit.
        int w = new JLabel("99999,99 MiB/s").getPreferredSize().width + 30;
        try {
            Method m = lm.getClass().getMethod("setComponentConstraints",
                    Component.class, Object.class);
            m.invoke(lm, spinner, "width " + w + "!");
            jc.putClientProperty(WIDENED, Boolean.TRUE);
            editor.revalidate();
            editor.repaint();
            // Grow the visible popup so the wider field is not clipped by popup bounds.
            JPopupMenu pm = (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, editor);
            if (pm != null) {
                Dimension pref = pm.getPreferredSize();
                pm.setPopupSize(pref.width, pref.height);
            }
        } catch (Exception ignore) {
            // setComponentConstraints absent / layout differs -> leave the field as-is
        }
    }

    // ------------------------------------------------------------ classic LAF colours

    /** Stock classic JD progress-bar gradient (aRGB). Defaults in LAFSettings are null —
     *  CustomProgressbarPainter then NPEs on LinearGradientPaint. JSON seed alone is not
     *  always visible to LAFOptions in time; set via cfg setters once LAFOptions exists. */
    private static final String[] CLASSIC_PROGRESS_COLORS = {
            "#5F70CCFF", "#5F80C7F7", "#8078C0EF", "#5F80C7F7", "#5F70CCFF"
    };
    private static boolean classicLafColorsSeeded = false;

    private static void seedClassicLafColors() {
        if (!wantClassicLaf() || classicLafColorsSeeded) return;
        try {
            Class<?> lafClz = Class.forName("org.jdownloader.updatev2.gui.LAFOptions");
            Object inst;
            try {
                inst = lafClz.getMethod("getInstance").invoke(null);
            } catch (Throwable notReady) {
                return; // LAFOptions Not initialized yet — retry next tick
            }
            if (inst == null) return;
            Object cfg = lafClz.getMethod("getCfg").invoke(inst);
            if (cfg == null) return;
            Class<?> cfgClz = cfg.getClass();
            for (int i = 1; i <= 5; i++) {
                String getter = "getColorForProgressbarForeground" + i;
                String setter = "setColorForProgressbarForeground" + i;
                Object cur = cfgClz.getMethod(getter).invoke(cfg);
                if (cur == null || String.valueOf(cur).trim().isEmpty()) {
                    cfgClz.getMethod(setter, String.class).invoke(cfg, CLASSIC_PROGRESS_COLORS[i - 1]);
                }
            }
            // Readable config/dialog labels (avoids all-grey Update dialog text).
            putCfgStringIfBlank(cfg, "getConfigLabelEnabledTextColor", "setConfigLabelEnabledTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getConfigLabelDisabledTextColor", "setConfigLabelDisabledTextColor", "#FFA0A0A0");
            putCfgStringIfBlank(cfg, "getColorForConfigHeaderTextColor", "setColorForConfigHeaderTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getColorForConfigPanelDescriptionText", "setColorForConfigPanelDescriptionText", "#FF808080");
            putCfgStringIfBlank(cfg, "getColorForPanelHeaderForeground", "setColorForPanelHeaderForeground", "#FF000000");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsNormalState", "setColorForScrollbarsNormalState", "#ffD7E7F0");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsMouseOverState", "setColorForScrollbarsMouseOverState", "#ffABC7D8");
            classicLafColorsSeeded = true;
            System.out.println("[jd-dialog-agent] seeded classic LAF progress/text/scrollbar colors (NPE/grey-text guard)");
        } catch (Throwable t) {
            // keep retrying until LAFOptions + cfg are usable
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

    // ------------------------------------------------------------ progress bars

    /**
     * The download-list + account-traffic progress bars are AppWork RendererProgressBars
     * (JProgressBars). Their fill colour is FlatLaf's runtime accent (ProgressBar.foreground
     * = @accentSliderColor), computed at runtime — it cannot be set via static FlatLaf
     * properties, nor reached by updateComponentTreeUI (cell renderers aren't in the tree).
     * AppWork holds the bar instances in ExtProgressColumn fields and does NOT colour them
     * per cell, so setting the colour directly on those instances sticks. We find them by
     * walking tables -> columns -> any JProgressBar-typed field and recolour them. Cheap and
     * idempotent; runs every tick so tables opened later (the account manager) are caught too.
     */
    private static void retintProgressBars() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                for (Object col : extColumns(t)) {
                    recolorBarFields(col);
                }
            }
        }
    }

    private static void collectTables(Container c, List<JTable> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTable) out.add((JTable) child);
            if (child instanceof Container) collectTables((Container) child, out);
        }
    }

    /** AppWork ExtColumn objects of a table (they hold the renderer progress bars). */
    private static List<Object> extColumns(JTable t) {
        List<Object> cols = new ArrayList<>();
        try {
            javax.swing.table.TableColumnModel cm = t.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                Object r = cm.getColumn(i).getCellRenderer();
                if (r != null) cols.add(r);
            }
        } catch (Exception ignore) { }
        try {
            Object model = t.getModel();
            Object list = model.getClass().getMethod("getColumns").invoke(model);
            if (list instanceof Collection) cols.addAll((Collection<?>) list);
        } catch (Exception ignore) { }
        return cols;
    }

    /** Set our dark fill/track on every JProgressBar-typed field of the object. */
    private static void recolorBarFields(Object col) {
        if (col == null) return;
        for (Class<?> k = col.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!JProgressBar.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object bar = f.get(col);
                    if (bar instanceof JProgressBar) {
                        JProgressBar pb = (JProgressBar) bar;
                        if (!BAR_FILL.equals(pb.getForeground())) pb.setForeground(BAR_FILL);
                        if (!BAR_TRACK.equals(pb.getBackground())) pb.setBackground(BAR_TRACK);
                    }
                } catch (Exception ignore) { }
            }
        }
    }

    // --- BUG 3: progress-bar render-time defaults (bars grey out on scroll) -----------
    // The download-list RendererProgressBars are rubber-stamp fields with a no-op repaint()
    // and a final renderer entry point, so a 400 ms tick that sets their colour loses the
    // scroll race for freshly-stamped cells. FlatProgressBarUI paints fill=getForeground()/
    // track=getBackground(), falling back to the ProgressBar.* UIManager keys whenever a bar
    // still holds the UIResource default. So set those keys via UIManager.put (DEVELOPER
    // overrides -> outrank the LAF defaults AND survive setLookAndFeel; getDefaults().put in
    // enforceDarkChrome did NOT and got wiped by every LAF reinstall) using the SAME light
    // fill the registered FlatDarkLaf.properties already intends (@accentBaseColor #c6c6c6).
    // Now the render-time fallback is always our light fill, so losing the retint race just
    // shows the same colour instead of a grey flash. recolorBarFields stays as a per-instance
    // belt-and-suspenders layer and uses the SAME light fill (BAR_FILL) so they can't disagree.
    private static void installProgressBarDefaults() {
        UIManager.put("ProgressBar.foreground",          new ColorUIResource(0xc6, 0xc6, 0xc6)); // light fill
        UIManager.put("ProgressBar.background",          new ColorUIResource(0x26, 0x26, 0x26)); // dark track
        UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(0x16, 0x16, 0x16)); // % over the fill (dark on light)
        UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over the track (white on dark)
    }

    private static boolean lafListenerAdded = false;
    /** Re-assert the progress defaults + instance colours the instant JD (or our own
     *  applyCustomDefaults) reinstalls the LAF, so a reinstall never reverts the fill to
     *  FlatLaf's stock accent for freshly-scrolled cells. Registered once; the handler only
     *  re-applies colours (never setLookAndFeel), so it cannot recurse. */
    private static void ensureLafChangeListener() {
        if (lafListenerAdded) return;
        lafListenerAdded = true;
        UIManager.addPropertyChangeListener(evt -> {
            if ("lookAndFeel".equals(evt.getPropertyName())) {
                SwingUtilities.invokeLater(() -> { installProgressBarDefaults(); retintProgressBars(); });
            }
        });
    }

    // ---------------------------------------------------------------- chrome

    /**
     * Recolour FlatLaf's UIManager colour defaults to the #161616 greyscale, exactly
     * ONCE per JVM, and only AFTER JD's main window is built, shown and stable.
     *
     * Re-creating UI delegates (updateComponentTreeUI) while JD is still packing its
     * frame makes AppWork's CircleProgressBarUI NPE during addNotify and crashes the GUI
     * into a boot loop. Waiting until a frame has been showing for a few ticks guarantees
     * pack() is finished, so our refresh never collides with it. (A JD in-process LAF
     * re-apply afterwards would revert the chrome, but JD only applies its LAF during
     * early startup; a self-update restarts the JVM, which re-runs this from scratch.)
     */
    private static void enforceDarkChrome() {
        if (chromeDone) return;
        if (!lafRefreshDone) return;   // ORDER: run only after the one-shot LAF re-apply
                                        // (a later setLookAndFeel would wipe this remap)
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null || !laf.getClass().getName().toLowerCase().contains("flat")) return;

        // Wait for JD's MAIN window (large / maximised) to be shown and stable before
        // touching any UI — not a small splash/progress frame, and never while JD is
        // still packing (that is what triggered the CircleProgressBarUI crash).
        boolean ready = false;
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400) { ready = true; break; }
        }
        if (!ready) { stableTicks = 0; return; }
        if (++stableTicks < 4) return;   // ~1.6 s after the main frame shows -> pack() done

        UIDefaults d = UIManager.getDefaults();
        List<Object> keys = new ArrayList<>(d.keySet()); // snapshot: we mutate while iterating
        for (Object key : keys) {
            Object val = d.get(key);
            if (!(val instanceof Color)) continue;
            String ks = key.toString().toLowerCase();
            // Selection backgrounds -> the visible lighter grey (no colour accent).
            if (ks.contains("selectionbackground")) {
                d.put(key, withAlpha(SEL, ((Color) val).getAlpha()));
                continue;
            }
            // Foreground / text greys must stay readable: de-blue them but NEVER darken
            // (the darken band would otherwise pull disabled/secondary greys onto the
            // background colour and make them invisible).
            boolean isText = ks.contains("foreground") || ks.contains("text")
                    || ks.contains("caret") || ks.contains("accelerator");
            Color rep = remap((Color) val, isText);
            if (rep != null) d.put(key, rep);
        }
        d.put("Component.accentColor", SEL);   // FlatLaf derives focus/selection from this
        d.put("TableHeader.background", HEADER);
        // Standard Swing JTrees (in some JD dialogs) draw dark [+]/[-] / chevrons that
        // vanish on #161616 — light them. (JD's download package toggle is NOT a Swing
        // tree; it loads theme icons tree_plus/tree_minus, shipped light in the iconset.)
        d.put("Tree.collapsedIcon", boxIcon(true));    // [+]
        d.put("Tree.expandedIcon", boxIcon(false));    // [-]
        for (String k : new String[] {
                "Tree.icon.expandedColor", "Tree.icon.collapsedColor",
                "Tree.icon.leafColor", "Tree.icon.closedColor", "Tree.icon.openColor" }) {
            d.put(k, new ColorUIResource(0xb0, 0xb0, 0xb0));
        }
        // Progress bars (download list): set via UIManager.put (developer overrides that
        // OUTRANK LAF defaults and SURVIVE setLookAndFeel), not this wipe-prone getDefaults()
        // table — the old d.put lines here reverted on every FlatLaf reinstall and caused the
        // scroll grey-flash. Single source of truth now: installProgressBarDefaults() (BUG 3).
        installProgressBarDefaults();
        chromeDone = true;   // set before the refresh so a throw can never cause a retry storm

        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;   // never refresh a window JD is still building
            try { SwingUtilities.updateComponentTreeUI(w); } catch (Exception ignore) { }
        }
        System.out.println("[jd-dialog-agent] enforced #161616 dark chrome");
    }

    /**
     * Map a FlatLaf default colour onto the Carbon greyscale.
     *   - blue accent          -> neutral grey of the same brightness (hue removed,
     *                             light/dark relationship preserved)
     *   - neutral chrome grey   -> darkened onto the #161616 scale (backgrounds/borders
     *                             only; skipped when isText so text stays readable)
     *   - everything else (light text, red/amber error colours, green) -> unchanged
     * Returns null to leave the colour as-is. Alpha is preserved.
     */
    private static Color remap(Color c, boolean isText) {
        int r = c.getRed(), g = c.getGreen(), b = c.getBlue(), a = c.getAlpha();
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int bright = (r + g + b) / 3;

        // FlatLaf's blue accent (focus, selection, sliders, scrollbar thumbs, links) ->
        // a fixed DARK grey. Mapping by brightness produced a light grey (~#737373) that
        // showed up "light grey everywhere"; use the selection grey so accents stay dark.
        if (b > r + 24 && b > g + 12 && b > 90) {
            return new ColorUIResource(new Color(SEL.getRed(), SEL.getGreen(), SEL.getBlue(), a));
        }
        // Background / border chrome greys -> darken onto the #161616 scale.
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
    private static javax.swing.Icon boxIcon(final boolean plus) {
        return new javax.swing.plaf.IconUIResource(new javax.swing.Icon() {
            public int getIconWidth()  { return 11; }
            public int getIconHeight() { return 11; }
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                g.setColor(new Color(0xb0, 0xb0, 0xb0));
                g.drawRect(x, y, 10, 10);
                g.drawLine(x + 3, y + 5, x + 7, y + 5);            // horizontal bar
                if (plus) g.drawLine(x + 5, y + 3, x + 5, y + 7);  // vertical -> plus
            }
        });
    }

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
            // prompt. Auto-OK for FlatLaf themes only — never while classic is selected.
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
                boolean mentionsLaf = body.contains("look&feel") || body.contains("look & feel")
                        || body.contains("look and feel") || body.contains("look-and-feel")
                        || body.contains("flatlaf") || body.contains("flatlaf_dark")
                        || body.contains("flatlaf_light");
                if (!isInfoDialog && notInstalled && wantsInstall && mentionsLaf) {
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
