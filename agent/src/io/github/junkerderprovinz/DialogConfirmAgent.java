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

    // The jd-highlighter desktop installer sets jd.highlighter.home to <JD>/.jd-highlighter.
    // There JD's dialogs are left to the user and no container markers are written.
    private static final String HL_HOME = System.getProperty("jd.highlighter.home", "");
    private static final boolean DESKTOP = !HL_HOME.isEmpty();
    private static final java.io.File JD_HOME = DESKTOP
            ? new java.io.File(HL_HOME).getAbsoluteFile().getParentFile()
            : new java.io.File("/config/JDownloader");

    // --- Carbon greyscale (matches jdownloader-theme.sh content palette) ---
    private static final ColorUIResource BG     = new ColorUIResource(0x16, 0x16, 0x16);
    private static final ColorUIResource HEADER = new ColorUIResource(0x0b, 0x0b, 0x0b);
    private static final ColorUIResource SEL    = new ColorUIResource(0x52, 0x52, 0x52);

    // Neutral greys of the theme. The template .properties and the JD colorfor* JSON mirror these
    // values; the accent is the one configurable colour and is read from the LAF at runtime.
    private static final Color PAL_BASE      = new Color(0x16, 0x16, 0x16); // window chrome
    private static final Color PAL_FIELD     = new Color(0x1a, 0x1a, 0x1a); // input fill
    private static final Color PAL_HEADER    = new Color(0x1e, 0x1e, 0x1e); // table and section headers
    private static final Color PAL_TRACK     = new Color(0x26, 0x26, 0x26); // progress track, unselected tile
    private static final Color PAL_SURFACE   = new Color(0x24, 0x24, 0x24); // cards, dialogs, chips, lists
    private static final Color PAL_BUTTON    = new Color(0x2a, 0x2a, 0x2a); // buttons, menu fields
    private static final Color PAL_DIVIDER   = new Color(0x39, 0x39, 0x39); // scrollbar thumb, dividers
    private static final Color PAL_SELECTION = new Color(0x52, 0x52, 0x52); // row selection
    private static final Color PAL_MONO      = new Color(0xb0, 0xb0, 0xb0); // mono icons
    private static final Color PAL_DISABLED  = new Color(0x6f, 0x6f, 0x6f); // disabled text
    private static final Color PAL_TEXT      = new Color(0xf4, 0xf4, 0xf4); // text, light icons

    // Plain (non-UIResource) colours set directly on the table progress-bar instances so a
    // later updateUI cannot override them. Plain Dark uses the light #c6c6c6 of the
    // registered FlatDarkLaf.properties, the same value installProgressBarDefaults puts
    // into UIManager, so the two never disagree into a grey flash on scroll.
    private static final Color BAR_FILL  = new Color(0xc6, 0xc6, 0xc6);
    private static final Color BAR_TRACK = PAL_TRACK;

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
    private static final java.io.File DEFAULTS_DIR = DESKTOP
            ? new java.io.File(HL_HOME, "flatlaf-defaults")
            : new java.io.File("/opt/JDownloader/flatlaf-defaults");
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

    // When JD restarts itself it copies -javaagent into the new command line, and the inherited
    // JAVA_TOOL_OPTIONS loads the agent a second time. Two instances patch every class twice.
    private static boolean started;

    public static void premain(String agentArgs, Instrumentation inst) {
        if (started) return;
        started = true;
        if (wantClassicLaf()) {
            System.out.println("[jd-dialog-agent] watching for installer dialogs (classic JDDEFAULT — no FlatLaf dark chrome)");
        } else if (wantDarkLaf()) {
            System.out.println("[jd-dialog-agent] watching for installer dialogs + enforcing dark chrome");
        } else {
            System.out.println("[jd-dialog-agent] watching for installer dialogs (Flat Light — no dark chrome remap)");
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
            // and is re-asserted on every reinstall (no scroll grey-flash). Dark fills only.
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
                        } else if (HIGHLIGHTER) {
                            if (TASK_COL.equals(className) || AVAIL_COL.equals(className) || FILE_COL.equals(className))
                                return patchStatusColumn(classfileBuffer, loader, className);
                            if (FLAT_TABUI.equals(className)) return patchTabbedPaneUI(classfileBuffer, loader);
                            if (NEW_THEME.equals(className)) return patchNewTheme(classfileBuffer, loader);
                            // Config-panel ExtTables ignore renderers set on them, so the
                            // render path itself is wrapped.
                            if (EXT_TABLE.equals(className)) return patchExtTableRenderer(classfileBuffer, loader);
                            if (FLAT_MI_RENDERER.equals(className)) return patchMenuItemRenderer(classfileBuffer, loader);
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

    private static ClassWriter lafClassWriter(ClassReader cr, final ClassLoader loader) {
        return new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
    }

    /** getColor / getColorMouseOver: if LAFOptions not ready or colour null, return stock classic blues. */
    private static byte[] patchCustomScrollbarPainter(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = lafClassWriter(cr, loader);
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
        ClassWriter cw = lafClassWriter(cr, loader);
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
                // Replace any null slot with stock colour; track whether any fallback was used
                // (local 2 = usedFallback int 0/1). Only cache colorArray when ALL slots came
                // from LAFOptions — same fail-open retry as scrollbar painters.
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitVarInsn(Opcodes.ISTORE, 2);
                for (int i = 0; i < 5; i++) {
                    Label ok = new Label();
                    mv.visitVarInsn(Opcodes.ALOAD, 1);
                    mv.visitLdcInsn(Integer.valueOf(i));
                    mv.visitInsn(Opcodes.AALOAD);
                    mv.visitJumpInsn(Opcodes.IFNONNULL, ok);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitVarInsn(Opcodes.ISTORE, 2);
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
                Label skipCache = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitJumpInsn(Opcodes.IFNE, skipCache);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitFieldInsn(Opcodes.PUTFIELD, JD_PROGRESS_PAINTER, "colorArray", "[Ljava/awt/Color;");
                mv.visitLabel(skipCache);
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
     *  Avoids Class.forName/loadClass(NewTheme) + printStackTrace spam when JDCustom is on App CL.
     *  INVOKESPECIAL must target the real superclass from the class bytes (getSuperName),
     *  not a hard-coded SyntheticaLookAndFeel — a wrong owner fails verification. */
    private static byte[] patchJdDefaultGetDisabledIcon(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        final String superName = cr.getSuperName();
        if (superName == null || superName.isEmpty() || "java/lang/Object".equals(superName)) {
            if (!jdDefaultLafPatchLogged) { jdDefaultLafPatchLogged = true;
                System.out.println("[jd-dialog-agent] JDDefaultLookAndFeel: unexpected superName="
                        + superName + " — skip getDisabledIcon patch (keep original bytes)"); }
            return null;
        }
        ClassWriter cw = lafClassWriter(cr, loader);
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
                        superName,
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
            System.out.println("[jd-dialog-agent] patched JDDefaultLookAndFeel.getDisabledIcon → super "
                    + superName + " (no NewTheme CNFE spam)"); }
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
        ClassWriter cw = lafClassWriter(cr, loader);
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
                } else if ("uninstallUI".equals(name) && "(Ljavax/swing/JComponent;)V".equals(desc)) {
                    // On a LAF re-apply uninstallListeners calls circleBar.removePropertyChangeListener; with a
                    // null circleBar that throws and leaves the animation timer running, so rebind it here too.
                    cIdx = 1; isVoid = true;
                } else {
                    return mv;
                }
                final boolean isPaint = "paint".equals(name);
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
                        // if (paintCleanRing(g, c)) return;
                        // paintCleanRing returns false outside Highlighter, which leaves JD's own painting.
                        if (isPaint) {
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitVarInsn(Opcodes.ALOAD, cIdx);
                            visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "paintCleanRing",
                                    "(Ljava/awt/Graphics;Ljava/awt/Component;)Z", false);
                            Label jdPaint = new Label();
                            visitJumpInsn(Opcodes.IFEQ, jdPaint);
                            visitInsn(Opcodes.RETURN);
                            visitLabel(jdPaint);
                        }
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
        ClassWriter cw = lafClassWriter(cr, loader);
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

    // The status columns return pre-composited coloured glyphs from getIcon(AbstractNode), and
    // fillColumnHelper builds fresh MergedIcons on every call, so no field sweep can reach them. The
    // return value goes through cleanTaskIcon instead, which JD's parent-first class loaders resolve
    // from the agent jar on the system class path.
    private static final String TASK_COL  = "org/jdownloader/gui/views/downloads/columns/TaskColumn";
    private static final String AVAIL_COL = "org/jdownloader/gui/views/downloads/columns/AvailabilityColumn";
    private static final String FILE_COL  = "org/jdownloader/gui/views/downloads/columns/FileColumn";
    private static final String NEW_THEME = "org/jdownloader/images/NewTheme";
    private static final String EXT_TABLE = "org/appwork/swing/exttable/ExtTable";
    // FlatLaf paints a hovered top-level menu's text in the menu bar's foreground and switches to
    // selectionForeground only when the item is armed, which rollover is not, so the hover text ends
    // up light on the accent.
    private static final String FLAT_MI_RENDERER = "com/formdev/flatlaf/ui/FlatMenuItemRenderer";
    private static final String STATUS_GETICON_DESC =
            "(Ljd/controlling/packagecontroller/AbstractNode;)Ljavax/swing/Icon;";
    private static final String AGENT_INTERNAL = "io/github/junkerderprovinz/DialogConfirmAgent";
    private static byte[] patchStatusColumn(byte[] original, final ClassLoader loader, final String cn) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }
        };
        final int[] n = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!("getIcon".equals(name) && STATUS_GETICON_DESC.equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN) {
                            // return cleanTaskIcon(this, icon);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "cleanTaskIcon",
                                    "(Ljava/lang/Object;Ljavax/swing/Icon;)Ljavax/swing/Icon;", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] " + cn + ".getIcon(AbstractNode) not found, status icons left as-is");
            return null;   // null leaves the class unchanged
        }
        System.out.println("[jd-dialog-agent] patched " + cn + ".getIcon -> clean Tabler status icons");
        return cw.toByteArray();
    }

    // FlatLaf paints tab backgrounds edge to edge and the agent cannot subclass FlatTabbedPaneUI, so
    // paintTabBackground gets its fill rect inset at entry to leave a gap between the tabs.
    private static final String FLAT_TABUI = "com/formdev/flatlaf/ui/FlatTabbedPaneUI";
    private static final int TAB_GAP = 4;
    public static int tabGap() { return isHighlighter() ? TAB_GAP : 0; }
    private static byte[] patchTabbedPaneUI(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }
        };
        final int[] n = { 0 };
        final java.util.List<String> methods = new java.util.ArrayList<String>();
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                methods.add(name + desc);
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!("paintTabBackground".equals(name) && "(Ljava/awt/Graphics;IIIIIIZ)V".equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // x = x + tabGap();
                        super.visitVarInsn(Opcodes.ILOAD, 4);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "tabGap", "()I", false);
                        super.visitInsn(Opcodes.IADD);
                        super.visitVarInsn(Opcodes.ISTORE, 4);
                        // w = w - 2 * tabGap();
                        super.visitVarInsn(Opcodes.ILOAD, 6);
                        super.visitInsn(Opcodes.ICONST_2);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "tabGap", "()I", false);
                        super.visitInsn(Opcodes.IMUL);
                        super.visitInsn(Opcodes.ISUB);
                        super.visitVarInsn(Opcodes.ISTORE, 6);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] FlatTabbedPaneUI.paintTabBackground not found, methods: " + methods);
            return null;
        }
        System.out.println("[jd-dialog-agent] patched FlatTabbedPaneUI.paintTabBackground -> tab gap");
        return cw.toByteArray();
    }

    // Prepends to FlatMenuItemRenderer.isArmedOrSelected:
    //   if (menuItem instanceof JMenu && ((JMenu) menuItem).isTopLevelMenu() && menuItem.getModel().isRollover())
    //       return true;
    private static byte[] patchMenuItemRenderer(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }
        };
        final int[] n = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!("isArmedOrSelected".equals(name) && "(Ljavax/swing/JMenuItem;)Z".equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label orig = new Label();
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitTypeInsn(Opcodes.INSTANCEOF, "javax/swing/JMenu");
                        visitJumpInsn(Opcodes.IFEQ, orig);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitTypeInsn(Opcodes.CHECKCAST, "javax/swing/JMenu");
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "javax/swing/JMenu", "isTopLevelMenu", "()Z", false);
                        visitJumpInsn(Opcodes.IFEQ, orig);
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "javax/swing/AbstractButton", "getModel",
                                "()Ljavax/swing/ButtonModel;", false);
                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "javax/swing/ButtonModel", "isRollover", "()Z", true);
                        visitJumpInsn(Opcodes.IFEQ, orig);
                        visitInsn(Opcodes.ICONST_1);
                        visitInsn(Opcodes.IRETURN);
                        visitLabel(orig);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] FlatMenuItemRenderer.isArmedOrSelected not found, menu hover text left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched FlatMenuItemRenderer.isArmedOrSelected -> dark hover text on menubar menus");
        return cw.toByteArray();
    }


    // JD caches chrome icons such as the column lock in memory at startup, before the light files are
    // restored on disk, and they are not column fields the reflective sweep could reach. Routing
    // NewTheme.getIcon through cleanChromeIcon catches them at lookup; the hook returns early for other keys.
    private static byte[] patchNewTheme(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }
        };
        final int[] n = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                boolean twoArg = "getIcon".equals(name) && "(Ljava/lang/String;I)Ljavax/swing/Icon;".equals(desc);
                boolean oneArg = "getIcon".equals(name) && "(Ljava/lang/String;)Ljavax/swing/Icon;".equals(desc);
                if (!twoArg && !oneArg) return mv;
                boolean stat = (access & Opcodes.ACC_STATIC) != 0;
                final int keyIdx = stat ? 0 : 1;
                final int sizeIdx = twoArg ? (stat ? 1 : 2) : -1;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, keyIdx);
                            if (sizeIdx >= 0) super.visitVarInsn(Opcodes.ILOAD, sizeIdx);
                            else super.visitInsn(Opcodes.ICONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "cleanChromeIcon",
                                    "(Ljavax/swing/Icon;Ljava/lang/String;I)Ljavax/swing/Icon;", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] NewTheme.getIcon(String,int) not found, chrome icons left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched NewTheme.getIcon -> light chrome icons");
        return cw.toByteArray();
    }

    // Config-panel ExtTables render from their own ExtColumns and ignore renderers set through
    // TableColumn or setDefaultRenderer. wrapExtCellRenderer only wraps tables the tick marked as config
    // panels (jdp.extCfg), so the busy download and linkgrabber tables pass straight through.
    private static byte[] patchExtTableRenderer(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() { return loader != null ? loader : super.getClassLoader(); }
        };
        final int[] n = { 0 };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!("getCellRenderer".equals(name) && "(II)Ljavax/swing/table/TableCellRenderer;".equals(desc)))
                    return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN) {
                            // return wrapExtCellRenderer(this, renderer);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitInsn(Opcodes.SWAP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "wrapExtCellRenderer",
                                    "(Ljava/lang/Object;Ljavax/swing/table/TableCellRenderer;)Ljavax/swing/table/TableCellRenderer;", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] ExtTable.getCellRenderer(II) not found, config table icons left as-is");
            return null;   // null leaves the class unchanged
        }
        System.out.println("[jd-dialog-agent] patched ExtTable.getCellRenderer -> mono config table icons");
        return cw.toByteArray();
    }

    // The width-lock padlock comes from ExtTableHeaderRenderer's private lockedWidth field, which no
    // getIcon call returns. Swapped on every tick so it survives header rebuilds; replacements are marked.
    private static void fixWidthLockIcon() {
        if (!isHighlighter()) return;
        try {
            for (Window w : Window.getWindows()) {
                if (!w.isShowing()) continue;
                List<JTable> tabs = new ArrayList<JTable>();
                collectTables(w, tabs);
                for (JTable t : tabs) {
                    javax.swing.table.TableColumnModel cm = t.getColumnModel();
                    for (int i = 0; i < cm.getColumnCount(); i++) {
                        Object hr = cm.getColumn(i).getHeaderRenderer();
                        if (hr instanceof HoverHeaderRenderer) hr = ((HoverHeaderRenderer) hr).base;
                        swapLockedWidth(hr);
                    }
                }
            }
        } catch (Throwable ignore) { }
    }
    private static void swapLockedWidth(Object hr) {
        if (hr == null || !hr.getClass().getName().contains("ExtTableHeaderRenderer")) return;
        for (Class<?> c = hr.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field f;
            try { f = c.getDeclaredField("lockedWidth"); } catch (NoSuchFieldException e) { continue; }
            try {
                f.setAccessible(true);
                Object v = f.get(hr);
                if (!(v instanceof javax.swing.Icon)) return;
                javax.swing.Icon ic = (javax.swing.Icon) v;
                if (EXT_MONO_MARK.containsKey(ic)) return;
                int s = Math.max(8, Math.min(ic.getIconWidth(), ic.getIconHeight()));
                javax.swing.Icon base = tablerBase("lock", s, s);
                if (base == null) return;
                javax.swing.Icon lock = tintIcon(base, EXPANDER_LIGHT, null);
                f.set(hr, lock);
                EXT_MONO_MARK.put(lock, Boolean.TRUE);
            } catch (Throwable ignore) { }
            return;
        }
    }

    private static final java.util.Map<String, javax.swing.Icon> CHROME_CLEAN =
            new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();
    /** Called from NewTheme.getIcon; replaces the column-lock and extract glyphs and passes everything else through. */
    public static javax.swing.Icon cleanChromeIcon(javax.swing.Icon original, String key, int size) {
        try {
            if (original == null || key == null || !isHighlighter()) return original;
            String k = key.toLowerCase();
            String tab; Color tint;
            if (k.contains("lock") || k.contains("columnbutton") || k.contains("widthlocked")) {
                tab = "lock"; tint = EXPANDER_LIGHT;
            } else if (k.equals("extract")) {
                tab = "extract"; tint = SIDEBAR_TEXT;
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
            DESKTOP ? new java.io.File(HL_HOME, "icons")
                    : new java.io.File("/opt/JDownloader/themes-default/flat/org/jdownloader/images");
    private static final java.io.File EXPANDER_DST_DIR =
            new java.io.File(JD_HOME, "themes/flat/org/jdownloader/images");
    // JD ships the two exttable lock icons black, which vanishes on the jd-highlighter
    // header. The image carries light copies, and JD's re-provisioning puts the black ones
    // back, so they need the same heal as the expanders.
    private static final String[] EXPANDER_ICONS = {
        "tree_plus.svg", "tree_minus.svg",
        "exttable/lockColumn.svg", "exttable/widthLocked.svg",
    };

    private static void restoreExpanderIcons() {
        for (String name : EXPANDER_ICONS) {
            if (name.indexOf('/') >= 0 && !isHighlighter()) continue;
            java.io.File src = new java.io.File(EXPANDER_SRC_DIR, name);
            java.io.File dst = new java.io.File(EXPANDER_DST_DIR, name);
            if (!src.isFile()) continue;                          // source missing (older image) -> nothing to do
            if (dst.isFile() && dst.length() == src.length()) continue; // already the shipped light copy
            try {
                dst.getParentFile().mkdirs();                     // name may carry a subdir (exttable/)
                java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[jd-dialog-agent] restored light theme icon: " + name);
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
            new java.io.File(JD_HOME, "libs/laf/flatlaf.jar");
    private static final java.io.File LAF_DIR =
            new java.io.File(JD_HOME, "libs/laf");
    private static boolean flatlafExposed = false;
    private static final java.util.HashSet<String> syntheticaExposed =
            new java.util.HashSet<String>();

    /** Classic official JD look (Synthetica DEFAULT). Skip FlatLaf dark-chrome work. */
    private static boolean wantClassicLaf() {
        String t = System.getenv("JD_THEME");
        if (t == null || t.trim().isEmpty()) return false;
        return "jddefault".equals(t.trim().toLowerCase());
    }

    /** Carbon #161616 chrome / dark progress retints are Dark-only. Light keeps FlatLight. */
    private static boolean wantDarkLaf() {
        if (wantClassicLaf()) return false;
        String t = System.getenv("JD_THEME");
        if (t == null || t.trim().isEmpty()) return true; // container default = Dark
        String n = t.trim().toLowerCase();
        if ("light".equals(n) || "jd_plain".equals(n)) return false;
        if (n.contains("dark")) return true;
        return true;
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

    // Each step runs on its own, so one that throws on an odd component cannot starve the rest.
    // JD re-renders popup rows lazily, which is why open popups are styled again on every tick.
    private static final Runnable[] HL_STEPS = {
        DialogConfirmAgent::styleSidebar,
        DialogConfirmAgent::stripSectionUnderlines,
        DialogConfirmAgent::recolorMainTabs,
        DialogConfirmAgent::flushMenuBarItems,
        DialogConfirmAgent::darkenChromeBars,
        DialogConfirmAgent::monoChromeIcons,
        DialogConfirmAgent::roundToolbarButtons,
        DialogConfirmAgent::badgeViewsMenu,
        DialogConfirmAgent::badgeViewsPanel,
        DialogConfirmAgent::fixMenuArrow,
        DialogConfirmAgent::styleMenuFields,
        DialogConfirmAgent::wireMenuPopups,
        DialogConfirmAgent::styleVisibleMenuPopups,
        DialogConfirmAgent::cardSettingsSections,
        DialogConfirmAgent::borderlessConfigTables,
        DialogConfirmAgent::unifyConfigFields,
        DialogConfirmAgent::monoTableRowIcons,
        DialogConfirmAgent::indentNameColumns,
        DialogConfirmAgent::cardMainTables,
        DialogConfirmAgent::cardBottomArea,
        DialogConfirmAgent::alignToolbarLeft,
        DialogConfirmAgent::monoConfigTableIcons,
        DialogConfirmAgent::fixWidthLockIcon,
        DialogConfirmAgent::stylePropertiesPanel,
        DialogConfirmAgent::monoSectionHeaders,
        DialogConfirmAgent::monoCornerIcons,
        DialogConfirmAgent::growTableHeaders,
        DialogConfirmAgent::recolorDialogs,
        DialogConfirmAgent::dimModalBackdrops,
    };

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
            registerDefaultsSource();     // FlatDarkLaf.properties OR FlatLightLaf.properties
            applyCustomDefaults();
            // Dark chrome MUST NOT run on FlatLightLaf — left black panels on Light installs.
            if (wantDarkLaf()) {
                enforceDarkChrome();
                if (!isHighlighter()) themeKaynExtras();        // menu-toggle check and overview corner icons
                retintProgressBars();
            }
            widenSpeedEditors();
            growSpeedMeter();
            replaceSpeedGraph();
            if (isHighlighter()) {
                for (Runnable step : HL_STEPS) {
                    try { step.run(); } catch (Throwable ignore) { }
                }
            }
        }
        if (++lafTick >= 12) {   // every ~5s (ticks run every 400ms)
            lafTick = 0;
            if (!DESKTOP) writeLafMarker();
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

        // Light/Default FlatLaf: never apply Carbon custom defaults — leave native LAF intact.
        if (!wantDarkLaf()) {
            lafRefreshDone = true;
            System.out.println("[jd-dialog-agent] non-dark LAF (" + laf.getClass().getSimpleName()
                    + ") — skipping dark custom-defaults re-apply");
            return;
        }

        if (defaultsRegistered) {
            Color bg = UIManager.getColor("Panel.background");
            // Dark sentinel: Carbon #161616. Light: skip this check (FlatLight is never #161616).
            if (wantDarkLaf() && bg != null && (bg.getRGB() & 0xFFFFFF) == 0x161616) {
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
        private Color colText   = PAL_TEXT;
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
                if (txt instanceof Color) {
                    colText = (Color) txt;
                } else {
                    colText = speedGraphTextColor();
                }
            } catch (Throwable ignore) {
                colText = speedGraphTextColor();
            }
        }

        private Color speedGraphTextColor() {
            LookAndFeel laf = UIManager.getLookAndFeel();
            if (laf != null) {
                String cn = laf.getClass().getName().toLowerCase();
                if (cn.contains("flatdark") || (cn.contains("dark") && !cn.contains("light"))) {
                    return new Color(0xf4, 0xf4, 0xf4);
                }
            } else if (wantDarkLaf()) {
                return new Color(0xf4, 0xf4, 0xf4);
            }
            Color label = UIManager.getColor("Label.foreground");
            return label != null ? label : new Color(0x1a, 0x1a, 0x1a);
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

    // Without a frame the fields embedded in menus (speed limit, max chunks) are hard to see, so they
    // get the button shade to read as editable.
    private static final Color MENU_FIELD_BG = PAL_BUTTON;
    private static final Color TILE_GREY = PAL_TRACK;   // unselected main tab
    private static final int   MENU_FIELD_W  = 110;

    // FlatLaf darkens a submenu's arrow only for a JMenu it sees as selected, which JD's menus do not
    // report while their submenu is open, so the arrow would stay light on the accent. The arrow of every
    // JMenu is wrapped in one that turns dark whenever the menu is hot.
    private static final class HotArrowIcon implements javax.swing.Icon {
        final javax.swing.Icon base;
        HotArrowIcon(javax.swing.Icon base) { this.base = base; }
        public int getIconWidth() { return base.getIconWidth(); }
        public int getIconHeight() { return base.getIconHeight(); }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean hot = c instanceof javax.swing.JMenu
                    && (((javax.swing.JMenu) c).isSelected() || ((javax.swing.JMenu) c).getModel().isArmed());
            (hot ? tintIcon(base, accentFg(), c) : base).paintIcon(c, g, x, y);
        }
    }
    private static void fixMenuArrow() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof javax.swing.JFrame) {
                javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
                if (mb != null) for (javax.swing.MenuElement m : mb.getSubElements()) fixArrowsIn(m);
            }
            for (Component c : w instanceof Container ? ((Container) w).getComponents() : new Component[0])
                if (c instanceof javax.swing.JRootPane) fixPopupArrows((Container) c);
        }
    }
    private static void fixPopupArrows(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JPopupMenu) fixArrowsIn((JPopupMenu) ch);
            else if (ch instanceof Container) fixPopupArrows((Container) ch);
        }
    }
    private static void fixArrowsIn(javax.swing.MenuElement e) {
        if (e instanceof javax.swing.JMenu) {
            Object ui = ((javax.swing.JMenu) e).getUI();
            Object icon = fieldValue(ui, javax.swing.plaf.basic.BasicMenuItemUI.class, "arrowIcon");
            if (icon instanceof javax.swing.Icon && !(icon instanceof HotArrowIcon)) {
                HotArrowIcon hot = new HotArrowIcon((javax.swing.Icon) icon);
                setField(ui, "arrowIcon", hot);
                // FlatLaf paints through a renderer that keeps its own reference to the arrow.
                Object renderer = fieldValue(ui, ui.getClass(), "renderer");
                if (renderer == null) renderer = fieldValue(ui, ui.getClass().getSuperclass(), "renderer");
                if (renderer != null) setField(renderer, "arrowIcon", hot);
                ((javax.swing.JMenu) e).repaint();
            }
        }
        for (javax.swing.MenuElement sub : e.getSubElements()) fixArrowsIn(sub);
    }
    private static Object fieldValue(Object o, Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) { return null; }
    }
    private static void setField(Object o, String name, Object value) {
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

    private static void styleMenuFields() {
        for (Window w : Window.getWindows()) if (w.isShowing()) styleMenuFieldsIn(w, false);
    }
    private static void styleMenuFieldsIn(Container c, boolean inMenu) {
        boolean menu = inMenu || c instanceof javax.swing.JPopupMenu;
        for (Component ch : c.getComponents()) {
            if (menu && (ch instanceof javax.swing.JSpinner
                    || ch instanceof javax.swing.text.JTextComponent
                    || ch instanceof javax.swing.JComboBox)) {
                if (!MENU_FIELD_BG.equals(ch.getBackground())) {
                    ch.setBackground(MENU_FIELD_BG);
                    // An opaque field fills its square bounds, and FlatLaf clears the corners only in the
                    // enabled paint path, so a disabled field would stay square.
                    if (ch instanceof JComponent) ((JComponent) ch).setOpaque(false);
                }
                // One width for all menu fields so the dropdown column lines up.
                if (ch instanceof JComponent) {
                    java.awt.Dimension pr = ch.getPreferredSize();
                    if (pr != null && pr.width != MENU_FIELD_W) {
                        java.awt.Dimension d = new java.awt.Dimension(MENU_FIELD_W, pr.height);
                        ch.setPreferredSize(d); ch.setMinimumSize(d); ch.setMaximumSize(d);
                        Container par = ch.getParent();
                        if (par != null) { par.invalidate(); par.revalidate(); }
                    }
                    // JD's menu spinners have a custom UI that ignores FlatLaf's arc keys, so their corners
                    // are masked with the popup background instead.
                    JComponent jc = (JComponent) ch;
                    if (jc.getClientProperty("jdp.fieldRound") == null) {
                        jc.putClientProperty("jdp.fieldRound", Boolean.TRUE);
                        // The FlatLaf border would draw an outline; only its insets are kept as padding.
                        javax.swing.border.Border ob = jc.getBorder();
                        java.awt.Insets bi = (ob != null) ? ob.getBorderInsets(jc) : new java.awt.Insets(3, 8, 3, 8);
                        javax.swing.border.Border inner = new javax.swing.border.EmptyBorder(bi);
                        javax.swing.border.Border mask = new RoundCornerMaskBorder(menuFieldMaskColor(jc), UI_RADIUS);
                        jc.setBorder(javax.swing.BorderFactory.createCompoundBorder(mask, inner));
                    }
                }
            }
            if (ch instanceof Container) styleMenuFieldsIn((Container) ch, menu);
        }
    }

    /** Background of the enclosing popup, used to mask a menu field's corners. */
    private static Color menuFieldMaskColor(Component field) {
        for (Component p = field.getParent(); p != null; p = p.getParent()) {
            if (p instanceof javax.swing.JPopupMenu && p.getBackground() != null) return p.getBackground();
        }
        return PAL_HEADER;
    }

    // Menus would open unstyled until the next tick, so each popup is styled from a listener as it
    // becomes visible. JMenus hand out their popup before it shows, which covers the first open too.
    private static final String POPUP_WIRED = "jdp.popupWired";

    private static void wireMenuPopups() {
        for (Window w : Window.getWindows()) if (w.isShowing()) wireMenuPopupsIn(w);
    }

    private static void wireMenuPopupsIn(Container c) {
        if (c instanceof javax.swing.JMenu) wirePopup(((javax.swing.JMenu) c).getPopupMenu());
        else if (c instanceof javax.swing.JPopupMenu) wirePopup((javax.swing.JPopupMenu) c);
        if (c instanceof javax.swing.JFrame) {
            javax.swing.JMenuBar mb = ((javax.swing.JFrame) c).getJMenuBar();
            if (mb != null) wireMenuPopupsIn(mb);
        }
        for (Component ch : c.getComponents())
            if (ch instanceof Container) wireMenuPopupsIn((Container) ch);
    }

    private static void wirePopup(final javax.swing.JPopupMenu pm) {
        if (pm == null || Boolean.TRUE.equals(pm.getClientProperty(POPUP_WIRED))) return;
        pm.putClientProperty(POPUP_WIRED, Boolean.TRUE);
        pm.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { stylePopupNow(pm); }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
    }

    /** Styles one popup menu. Runs on open and again on every tick while the popup is visible, because
     *  JD builds the EditorLink rows lazily and an on-open pass alone misses them. */
    private static void stylePopupNow(javax.swing.JPopupMenu pm) {
        try {
            styleMenuFieldsIn(pm, true);
            uniformMenuSpinnerWidths(pm);
            for (Component ch : pm.getComponents())
                if (ch instanceof javax.swing.JMenuItem) monoMenuItemIcon((javax.swing.JMenuItem) ch);
            for (Component ch : pm.getComponents())
                if (ch instanceof javax.swing.JMenuItem) forceMenuItemAccent((javax.swing.JMenuItem) ch);
            for (Component ch : pm.getComponents())
                if (!(ch instanceof javax.swing.JMenuItem) && ch instanceof Container) monoRowLabels((Container) ch);
            for (Component ch : pm.getComponents()) {
                if (ch instanceof JComponent && !(ch instanceof javax.swing.JMenuItem)
                        && !(ch instanceof javax.swing.JSeparator)) {
                    JComponent jc = (JComponent) ch;
                    if (jc.getClientProperty("jdp.menuRowPad") == null) {
                        jc.putClientProperty("jdp.menuRowPad", Boolean.TRUE);
                        // Editor rows draw their icon at x 7 and menu items at about 18 (MenuItem.margin),
                        // so 11px lines the two icon columns up.
                        javax.swing.border.Border pad = new javax.swing.border.EmptyBorder(3, 11, 3, 0);
                        javax.swing.border.Border old = jc.getBorder();
                        jc.setBorder(old == null ? pad : new javax.swing.border.CompoundBorder(pad, old));
                    }
                }
            }
            uniformMenuRowHeights(pm);
        } catch (Throwable ignore) { }
    }

    /** Grows the plain menu items to the height of the taller EditorLink and spinner rows. */
    private static void uniformMenuRowHeights(javax.swing.JPopupMenu pm) {
        int max = 0;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator || !ch.isVisible()) continue;
            int h = ch.getPreferredSize().height;
            if (h > max) max = h;
        }
        if (max <= 0) return;
        boolean changed = false;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator || !(ch instanceof JComponent) || !ch.isVisible()) continue;
            JComponent jc = (JComponent) ch;
            // Some of JD's standard items report the full preferred height but cap it with their own
            // maximum size, so min and max are pinned on every row to make the BoxLayout honour it.
            java.awt.Dimension mn = jc.getMinimumSize();
            if (mn != null && mn.height == max && jc.getMaximumSize() != null && jc.getMaximumSize().height == max) continue;
            java.awt.Dimension pr = ch.getPreferredSize();
            jc.setPreferredSize(new java.awt.Dimension(pr.width, max));
            jc.setMinimumSize(new java.awt.Dimension(0, max));
            jc.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, max));
            changed = true;
        }
        if (changed) { pm.revalidate(); pm.repaint(); }
        repackPopupIfClipped(pm);
    }

    /** Grows a showing popup to fit its rows. JD adds items to the settings dropdown after it is shown,
     *  so this runs on every tick; a heavyweight popup is resized through its window, a lightweight one
     *  in the layered pane. It never shrinks. */
    private static void repackPopupIfClipped(javax.swing.JPopupMenu pm) {
        try {
            if (!pm.isShowing() || pm.getHeight() <= 0) return;
            // getPreferredSize stays stale after JD adds items lazily, so the children are summed instead.
            int need = 0, wide = 0;
            for (Component ch : pm.getComponents()) {
                if (!ch.isVisible()) continue;
                java.awt.Dimension d = ch.getPreferredSize();
                if (d == null) continue;
                need += d.height; if (d.width > wide) wide = d.width;
            }
            java.awt.Insets in = pm.getInsets();
            if (in != null) { need += in.top + in.bottom; wide += in.left + in.right; }
            if (need <= 0 || (pm.getHeight() >= need && pm.getWidth() >= wide)) return;
            int nw = Math.max(pm.getWidth(), wide), nh = Math.max(pm.getHeight(), need);
            java.awt.Window win = javax.swing.SwingUtilities.getWindowAncestor(pm);
            boolean heavy = win != null && win.getClass().getName().toLowerCase().contains("popup");
            if (heavy) {
                if (win.getHeight() < nh || win.getWidth() < nw) { win.setSize(nw, nh); win.validate(); }
            } else {
                pm.setSize(nw, nh);
                java.awt.Container par = pm.getParent();
                if (par != null) { par.setSize(Math.max(par.getWidth(), nw), Math.max(par.getHeight(), nh)); par.revalidate(); }
                pm.revalidate(); pm.repaint();
            }
        } catch (Throwable ignore) { }
    }

    private static void styleVisibleMenuPopups() {
        // A heavyweight popup lives inside a popup window and a lightweight one in the layered pane,
        // so walking every showing window finds both.
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) styleVisiblePopupsIn(w);
        }
    }
    private static void styleVisiblePopupsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JPopupMenu && ch.isShowing()) stylePopupNow((javax.swing.JPopupMenu) ch);
            if (ch instanceof Container) styleVisiblePopupsIn((Container) ch);
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

        // Plain Dark mirrors JD's formula (label width + 30 for the arrows) with a long
        // sample; jd-highlighter gives every menu spinner the same width.
        int w = isHighlighter() ? uniformFieldW()
                : new JLabel("99999,99 MiB/s").getPreferredSize().width + 30;
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
            // Progress keys: same guarded path as other colours (LAFSettings Foreground names).
            for (int i = 1; i <= 5; i++) {
                putCfgStringIfBlank(cfg,
                        "getColorForProgressbarForeground" + i,
                        "setColorForProgressbarForeground" + i,
                        CLASSIC_PROGRESS_COLORS[i - 1]);
            }
            // Readable config/dialog labels (avoids all-grey Update dialog text).
            putCfgStringIfBlank(cfg, "getConfigLabelEnabledTextColor", "setConfigLabelEnabledTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getConfigLabelDisabledTextColor", "setConfigLabelDisabledTextColor", "#FFA0A0A0");
            putCfgStringIfBlank(cfg, "getColorForConfigHeaderTextColor", "setColorForConfigHeaderTextColor", "#FF202020");
            putCfgStringIfBlank(cfg, "getColorForConfigPanelDescriptionText", "setColorForConfigPanelDescriptionText", "#FF808080");
            putCfgStringIfBlank(cfg, "getColorForPanelHeaderForeground", "setColorForPanelHeaderForeground", "#FF000000");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsNormalState", "setColorForScrollbarsNormalState", "#ffD7E7F0");
            putCfgStringIfBlank(cfg, "getColorForScrollbarsMouseOverState", "setColorForScrollbarsMouseOverState", "#ffABC7D8");
            // Text only — do not force speed-meter graph colours (leave JD stock green).
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterText", "setColorForSpeedMeterText", "#FF222222");
            putCfgStringIfBlank(cfg, "getColorForSpeedMeterAverageText", "setColorForSpeedMeterAverageText", "#FF222222");
            // Latch only when all five progress colours are actually present — otherwise retry.
            Class<?> cfgClz = cfg.getClass();
            for (int i = 1; i <= 5; i++) {
                Object cur = cfgClz.getMethod("getColorForProgressbarForeground" + i).invoke(cfg);
                if (cur == null || String.valueOf(cur).trim().isEmpty()) {
                    return;
                }
            }
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
    // Width shared by every menu spinner: a speed value with its unit plus 30px for the arrows.
    private static int UNIFORM_FIELD_W = -1;
    private static int uniformFieldW() {
        if (UNIFORM_FIELD_W < 0) UNIFORM_FIELD_W = new JLabel("999,99 MiB/s").getPreferredSize().width + 30;
        return UNIFORM_FIELD_W;
    }

    /** Gives every spinner in a menu popup the same width. JD's MenuEditor hardcodes the MigLayout
     *  cell widths, which override setPreferredSize, so the constraint itself is rewritten. */
    private static void uniformMenuSpinnerWidths(Container c) {
        boolean touched = uniformSpinnersIn(c);
        if (touched) {
            JPopupMenu pm = (c instanceof JPopupMenu) ? (JPopupMenu) c
                          : (JPopupMenu) SwingUtilities.getAncestorOfClass(JPopupMenu.class, c);
            if (pm != null) { pm.revalidate(); Dimension pref = pm.getPreferredSize(); pm.setPopupSize(pref.width, pref.height); }
        }
    }
    private static boolean uniformSpinnersIn(Container c) {
        boolean any = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JSpinner) {
                Container par = ch.getParent();
                LayoutManager lm = (par == null) ? null : par.getLayout();
                boolean mig = lm != null && lm.getClass().getName().contains("MigLayout");
                JComponent sp = (JComponent) ch;
                if (mig && !Boolean.TRUE.equals(sp.getClientProperty("jdp.fieldW"))) {
                    try {
                        Method m = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
                        m.invoke(lm, ch, "width " + uniformFieldW() + "!");
                        sp.putClientProperty("jdp.fieldW", Boolean.TRUE);
                        par.revalidate();
                        any = true;
                    } catch (Exception ignore) { }
                }
            }
            if (ch instanceof Container) any |= uniformSpinnersIn((Container) ch);
        }
        return any;
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
                    recolorExpanderFields(col);
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

    /** FileColumn indents its rows through the normalBorder and leftGapBorder fields, about 5px left of
     *  the column header; both are moved right so the content lines up with the header. */
    private static final int NAME_INDENT = 5;
    private static void indentNameColumns() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables)
                for (Object col : extColumns(t))
                    if (col != null && col.getClass().getName().endsWith(".FileColumn")) {
                        bumpBorderLeft(col, "normalBorder", 0, NAME_INDENT);
                        bumpBorderLeft(col, "leftGapBorder", 32, NAME_INDENT);
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
        if (wantDarkLaf() && !isHighlighter() && !DESKTOP) {
            UIManager.put("ProgressBar.foreground",          new ColorUIResource(0xc6, 0xc6, 0xc6)); // light fill
            UIManager.put("ProgressBar.background",          new ColorUIResource(0x26, 0x26, 0x26)); // dark track
            UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(0x16, 0x16, 0x16)); // % over the fill (dark on light)
            UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(0xf4, 0xf4, 0xf4)); // % over the track (white on dark)
        } else {
            // Light/classic: clear developer overrides so native LAF renders correctly
            UIManager.put("ProgressBar.foreground",          null);
            UIManager.put("ProgressBar.background",          null);
            UIManager.put("ProgressBar.selectionForeground", null);
            UIManager.put("ProgressBar.selectionBackground", null);
        }
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
                if (DESKTOP) desktopFlatLaf = isFlatDark(UIManager.getLookAndFeel());
                SwingUtilities.invokeLater(() -> {
                    installProgressBarDefaults();
                    if (wantDarkLaf()) retintProgressBars();
                });
            }
        });
    }
    // Corner radius shared by every rounded element of the theme. RoundRectangle2D and fillRoundRect
    // take the diameter, hence UI_ARC.
    static final int UI_RADIUS = 8;
    static final int UI_ARC    = 2 * UI_RADIUS;
    private static final Color MAIN_CARD = PAL_SURFACE;
    private static final int   CARD_GAP  = 10;
    private static final int   CARD_ARC  = UI_RADIUS;
    // The left line that menu, toolbar, tabs, cards and the bottom bar all start on.
    private static final int   EDGE      = 16;
    private static final String CARD_WHOLE = "jdp.cardWhole";

    // The overview under the lists becomes a card like the list itself, and it and the button bar
    // below keep to the same left and right edge.
    private static void cardBottomArea() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<Component> found = new ArrayList<>();
            collectBySuffix(w, found, "OverviewHeaderScrollPane", "SearchField");
            for (Component c : found) {
                if (c.getClass().getName().endsWith("OverviewHeaderScrollPane")) cardOverview((javax.swing.JScrollPane) c);
                else padBottomBar(c.getParent());
            }
        }
    }
    private static void collectBySuffix(Container c, List<Component> out, String... suffixes) {
        for (Component ch : c.getComponents()) {
            String n = ch.getClass().getName();
            for (String s : suffixes) if (n.endsWith(s)) { out.add(ch); break; }
            if (ch instanceof Container) collectBySuffix((Container) ch, out, suffixes);
        }
    }
    private static void cardOverview(javax.swing.JScrollPane sp) {
        if (sp.getClientProperty(CARD_WHOLE) == null) {
            sp.putClientProperty(CARD_WHOLE, Boolean.TRUE);
            sp.setOpaque(true);
            sp.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 12, 8, 12));
            Container holder = sp.getParent();
            if (holder instanceof JComponent)
                ((JComponent) holder).setBorder(javax.swing.BorderFactory.createEmptyBorder(0, EDGE, CARD_GAP, EDGE));
            if (holder != null) holder.revalidate();
        }
        if (!MAIN_CARD.equals(sp.getBackground())) sp.setBackground(MAIN_CARD);
        for (javax.swing.JViewport vp : new javax.swing.JViewport[] { sp.getViewport(), sp.getColumnHeader() }) {
            if (vp == null) continue;
            if (!MAIN_CARD.equals(vp.getBackground())) vp.setBackground(MAIN_CARD);
            Component view = vp.getView();
            if (view != null && view.isOpaque() && !MAIN_CARD.equals(view.getBackground())) view.setBackground(MAIN_CARD);
        }
        installCardCornerOverlay(sp);
    }
    private static void padBottomBar(Container bar) {
        if (!(bar instanceof JComponent) || ((JComponent) bar).getClientProperty("jdp.edge") != null) return;
        ((JComponent) bar).putClientProperty("jdp.edge", Boolean.TRUE);
        ((JComponent) bar).setBorder(javax.swing.BorderFactory.createEmptyBorder(0, EDGE, CARD_GAP, EDGE));
        bar.revalidate();
    }

    // The download and linkgrabber tables sit on a lighter card with a margin of base colour around it,
    // like the settings cards.
    private static void cardMainTables() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                String cn = t.getClass().getName();
                if (!(cn.endsWith("DownloadsTable") || cn.endsWith("LinkGrabberTable"))) continue;
                javax.swing.JScrollPane sp = null;
                for (Container p = t.getParent(); p != null; p = p.getParent())
                    if (p instanceof javax.swing.JScrollPane) { sp = (javax.swing.JScrollPane) p; break; }
                if (sp == null) continue;
                if (!MAIN_CARD.equals(t.getBackground())) t.setBackground(MAIN_CARD);
                // Without filling the viewport the card ends at the last row, far above the bottom
                // corner masks, and its bottom stays square.
                try { if (!t.getFillsViewportHeight()) t.setFillsViewportHeight(true); } catch (Throwable ig) { }
                javax.swing.JViewport vp = sp.getViewport();
                if (vp != null && !MAIN_CARD.equals(vp.getBackground())) { vp.setBackground(MAIN_CARD); vp.setOpaque(true); }
                javax.swing.JViewport ch = sp.getColumnHeader();
                if (ch != null && !MAIN_CARD.equals(ch.getBackground())) ch.setBackground(MAIN_CARD);
                if (sp.getClientProperty("jdp.mainCard") == null) {
                    sp.setOpaque(true);
                    sp.setBackground(PAL_BASE);
                    sp.setBorder(javax.swing.BorderFactory.createEmptyBorder(CARD_GAP, EDGE, CARD_GAP, EDGE));
                    sp.putClientProperty("jdp.mainCard", Boolean.TRUE);
                    sp.revalidate(); sp.repaint();
                }
                installCardCornerOverlay(sp);
            }
        }
    }

    /** Rounds the card by painting its four corners in the base colour on top of the opaque table, the
     *  only way to round a live Swing table. */
    private static void installCardCornerOverlay(javax.swing.JScrollPane sp) {
        try {
            if (!sp.isShowing()) return;
            // The masks live on a layer of the layered pane: a sibling of the scrollpane gets overdrawn by
            // every row repaint, a higher layer is repainted on top of any dirty region.
            javax.swing.JRootPane rp = javax.swing.SwingUtilities.getRootPane(sp);
            if (rp == null) return;
            javax.swing.JLayeredPane lp = rp.getLayeredPane();
            if (lp == null) return;
            Object existing = sp.getClientProperty("jdp.cardCorner");
            CardCornerOverlay ov;
            if (existing instanceof CardCornerOverlay && ((CardCornerOverlay) existing).getParent() == lp) {
                ov = (CardCornerOverlay) existing;
            } else {
                if (existing instanceof CardCornerOverlay && ((CardCornerOverlay) existing).getParent() != null)
                    ((CardCornerOverlay) existing).getParent().remove((CardCornerOverlay) existing);
                ov = new CardCornerOverlay();
                lp.add(ov, javax.swing.JLayeredPane.PALETTE_LAYER);   // above the content, below popups
                sp.putClientProperty("jdp.cardCorner", ov);
            }
            ov.sp = sp;
            java.awt.Rectangle b = javax.swing.SwingUtilities.convertRectangle(sp.getParent(), sp.getBounds(), lp);
            if (b.width <= 0 || b.height <= 0) return;
            // Swing repaints the layer over any dirty region below it, so the overlay itself only
            // needs a repaint when it moves.
            if (!ov.getBounds().equals(b)) {
                ov.setBounds(b);
                lp.setLayer(ov, javax.swing.JLayeredPane.PALETTE_LAYER);
                ov.repaint();
            }
        } catch (Throwable ignore) { }
    }
    private static final class CardCornerOverlay extends JComponent {
        javax.swing.JScrollPane sp;
        CardCornerOverlay() { setOpaque(false); }
        @Override public boolean contains(int x, int y) { return false; }   // lets mouse events through to the table
        @Override protected void paintComponent(Graphics g) {
            // A hidden or replaced card leaves its overlay behind, which then must not paint.
            if (sp == null || !sp.isShowing()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(PAL_BASE);
                int r = CARD_ARC;
                // The card is the viewport plus the column header. The scrollbar and JD's column-control
                // corner take a column on the right, so the scrollpane bounds minus the margin would miss it.
                int L = CARD_GAP, T = CARD_GAP, R = getWidth() - CARD_GAP, B = getHeight() - CARD_GAP;
                try {
                    javax.swing.JViewport vp = (sp != null) ? sp.getViewport() : null;
                    // A card that pads its content from the inside fills its whole scrollpane.
                    if (sp != null && Boolean.TRUE.equals(sp.getClientProperty(CARD_WHOLE))) {
                        L = 0; T = 0; R = getWidth(); B = getHeight();
                    } else if (vp != null) {
                        java.awt.Rectangle vb = vp.getBounds();   // overlay and scrollpane share coordinates
                        L = vb.x; R = vb.x + vb.width; B = vb.y + vb.height;
                        javax.swing.JViewport ch = sp.getColumnHeader();
                        T = (ch != null) ? ch.getBounds().y : vb.y;
                    }
                } catch (Throwable ignore) { }
                paintCorner(g2, L, T, r, true, true);
                paintCorner(g2, R, T, r, false, true);
                paintCorner(g2, L, B, r, true, false);
                paintCorner(g2, R, B, r, false, false);
            } finally { g2.dispose(); }
        }
        private void paintCorner(Graphics2D g2, int cx, int cy, int r, boolean left, boolean top) {
            int x = left ? cx : cx - r, y = top ? cy : cy - r;
            int ecx = cx + (left ? r : -r), ecy = cy + (top ? r : -r);
            java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.Rectangle(x, y, r, r));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(ecx - r, ecy - r, 2 * r, 2 * r)));
            g2.fill(a);
        }
    }


    /** Widens the left inset of the EmptyBorder in field {@code name}, only while it still has its original
     *  value so repeated passes do not add up. */
    private static void bumpBorderLeft(Object col, String name, int baseLeft, int add) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> k = col.getClass(); k != null && k != Object.class && f == null; k = k.getSuperclass())
                try { f = k.getDeclaredField(name); } catch (NoSuchFieldException nsf) { }
            if (f == null) return;
            f.setAccessible(true);
            Object b = f.get(col);
            if (!(b instanceof javax.swing.border.EmptyBorder)) return;
            java.awt.Insets in = ((javax.swing.border.EmptyBorder) b).getBorderInsets();
            if (in.left != baseLeft) return;
            f.set(col, javax.swing.BorderFactory.createEmptyBorder(in.top, baseLeft + add, in.bottom, in.right));
        } catch (Throwable ignore) { }
    }

    // Lines the first toolbar glyph up with the content of the list card. JD's MainToolBar switches between
    // a MigLayout with its own leading inset and DefaultToolBarLayout, so both the border and the layout
    // inset are set.
    private static void alignToolbarLeft() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            java.util.List<Container> tbs = new java.util.ArrayList<Container>();
            findToolbars(w, tbs);
            for (Container tb : tbs) {
                if (!(tb instanceof JComponent)) continue;
                JComponent tbj = (JComponent) tb;
                Component first = null;
                for (Component ch : tb.getComponents()) if (ch.getWidth() > 0 && (first == null || ch.getX() < first.getX())) first = ch;
                if (first == null) continue;
                boolean changed = false;
                // The buttons are transparent and the glyph sits about 12px inside each, so this border
                // puts the first glyph on EDGE.
                final int TB_LEFT = EDGE - 12;
                java.awt.Insets bi = tbj.getInsets();
                if (bi != null && bi.left != TB_LEFT) {
                    tbj.setBorder(javax.swing.BorderFactory.createEmptyBorder(bi.top, TB_LEFT, bi.bottom, bi.right));
                    changed = true;
                }
                Object lm = tb.getLayout();
                if (lm != null && lm.getClass().getName().contains("MigLayout") && first.getX() > CARD_GAP + 1) {
                    try {
                        lm.getClass().getMethod("setLayoutConstraints", Object.class).invoke(lm, "ins 0 0 0 0");
                        try { lm.getClass().getMethod("invalidateLayout", Container.class).invoke(lm, tb); } catch (Throwable ig) { }
                        changed = true;
                    } catch (Throwable t) { }
                }
                if (changed) { tb.revalidate(); tb.repaint(); }
            }
        }
    }
    private static void findToolbars(Container c, java.util.List<Container> out) {
        for (Component ch : c.getComponents()) {
            if (isMainToolbar(ch.getClass()) && ch instanceof Container) out.add((Container) ch);
            if (ch instanceof Container) findToolbars((Container) ch, out);
        }
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
                        // Set on the instance so it beats FlatLaf's per-cell default.
                        Color fill = isHighlighter() ? accentColor() : BAR_FILL;
                        if (!fill.equals(pb.getForeground())) pb.setForeground(fill);
                        if (!BAR_TRACK.equals(pb.getBackground())) pb.setBackground(BAR_TRACK);
                        // On the hovered row the cell background is the accent, which would show through a
                        // transparent track and hide the light percentage text.
                        if (isHighlighter() && !pb.isOpaque()) pb.setOpaque(true);
                        // BasicProgressBarUI draws the percentage in selectionForeground over the fill and in
                        // selectionBackground over the track. The UI caches both at install time, before the
                        // UIManager keys are set, so they are written into the UI instance.
                        if (isHighlighter()) {
                            Object ui = pb.getUI();
                            if (ui != null) {
                                setUiColorField(ui, "selectionForeground", accentFg());
                                setUiColorField(ui, "selectionBackground", SIDEBAR_TEXT);
                            }
                        }
                    }
                } catch (Exception ignore) { }
            }
        }
    }

    private static void setUiColorField(Object ui, String name, Color val) {
        for (Class<?> k = ui.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                if (!val.equals(f.get(ui))) f.set(ui, val);
                return;
            } catch (NoSuchFieldException nsf) {
                // declared in a superclass
            } catch (Throwable t) {
                return;
            }
        }
    }

    // JD re-provisions its black expander and column-lock glyphs, and the restore on disk loses that race,
    // so the Icon fields JD paints from are tinted light in memory on every tick.
    private static final Color EXPANDER_LIGHT = PAL_MONO;
    // Replacements this agent made, so they are not tinted again on every tick.
    private static final java.util.Map<javax.swing.Icon, Boolean> EXT_MONO_MARK =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, Boolean>());
    private static void recolorExpanderFields(Object col) {
        if (col == null) return;
        for (Class<?> k = col.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!javax.swing.Icon.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object o = f.get(col);
                    if (!(o instanceof javax.swing.Icon)) continue;
                    javax.swing.Icon ic = (javax.swing.Icon) o;
                    if (EXT_MONO_MARK.containsKey(ic)) continue;
                    String key = iconKey(ic);
                    javax.swing.Icon repl = null;
                    if (key != null && (key.contains("tree_plus") || key.contains("tree_minus")
                            || key.contains("lockColumn") || key.contains("widthLocked"))) {
                        repl = tintSolid(ic, EXPANDER_LIGHT);
                    }
                    // Status glyphs stay untouched here: cleanTaskIcon matches them against these fields by identity.
                    if (repl != null && repl != ic) { f.set(col, repl); EXT_MONO_MARK.put(repl, Boolean.TRUE); }
                } catch (Throwable ignore) { }
            }
        }
    }

    // Called from the patched status-column getIcon. Classifies the glyph the column is about to return and
    // swaps in a mono Tabler icon of the same meaning; an unknown glyph is tinted mono instead.
    private static final java.util.Map<javax.swing.Icon, String> STATUS_CLASS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, String>());
    private static final java.util.Map<String, javax.swing.Icon> STATUS_CLEAN =
            new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();

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

    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> STATUS_FLIP =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, javax.swing.Icon>());
    /** Paints the light icon on a normal row and the dark one when the cell background is the accent. */
    private static final class RowFlipIcon implements javax.swing.Icon {
        private final javax.swing.Icon light, dark;
        RowFlipIcon(javax.swing.Icon l, javax.swing.Icon d) { light = l; dark = d; }
        public int getIconWidth() { return light.getIconWidth(); }
        public int getIconHeight() { return light.getIconHeight(); }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean hot = false;
            try {
                Color bg = (c != null) ? c.getBackground() : null;
                if (bg != null) {
                    Color acc = accentColor();
                    hot = Math.abs(bg.getRed() - acc.getRed()) < 40 && Math.abs(bg.getGreen() - acc.getGreen()) < 40
                            && Math.abs(bg.getBlue() - acc.getBlue()) < 40;
                }
            } catch (Throwable ignore) { }
            (hot ? dark : light).paintIcon(c, g, x, y);
        }
    }

    /** Maps a status glyph to a Tabler key, or null. The visible glyph is usually a MergedIcon that
     *  fillColumnHelper composed, so after the field and key lookups its parts are classified. */
    private static String classifyStatus(Object owner, javax.swing.Icon original) {
        String result = keyFromOwnerFields(owner, original);
        if (result == null) result = normalizeStatusKey(iconKey(original));
        if (result == null) {
            java.util.List<String> parts = new java.util.ArrayList<String>();
            collectSubIconKeys(owner, original, parts, 0);
            result = pickDominant(parts);
        }
        return result;
    }

    /** The Tabler key for an icon that is one of the owning column's status fields, else null. */
    private static String keyFromOwnerFields(Object owner, javax.swing.Icon original) {
        if (owner == null || original == null) return null;
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!javax.swing.Icon.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(owner) == original) { String k = statusKeyForField(f.getName()); if (k != null) return k; }
                } catch (Throwable ignore) { }
            }
        }
        return null;
    }

    /** Classifies the parts of a composite icon. MergedIcon keeps them in a TreeSet of wrapper entries rather
     *  than as plain Icons, so JD's own holder types are followed too, with a depth limit. */
    private static void collectSubIconKeys(Object owner, Object node, java.util.List<String> out, int depth) {
        if (node == null || depth > 6) return;
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try { f.setAccessible(true); consumeValue(owner, f.get(node), node, out, depth); }
                catch (Throwable ignore) { }
            }
        }
    }

    private static void consumeValue(Object owner, Object v, Object node, java.util.List<String> out, int depth) {
        if (v == null || v == node) return;
        if (v instanceof javax.swing.Icon) {
            javax.swing.Icon sub = (javax.swing.Icon) v;
            String k = keyFromOwnerFields(owner, sub);
            if (k == null) k = normalizeStatusKey(iconKey(sub));
            if (k != null) out.add(k);
            else collectSubIconKeys(owner, sub, out, depth + 1);
        } else if (v instanceof Object[]) {
            for (Object o : (Object[]) v) consumeValue(owner, o, node, out, depth);
        } else if (v instanceof java.util.Collection) {
            for (Object o : (java.util.Collection<?>) v) consumeValue(owner, o, node, out, depth);
        } else {
            String vc = v.getClass().getName();
            if (vc.startsWith("org.jdownloader.") || vc.startsWith("org.appwork.") || vc.startsWith("jd."))
                collectSubIconKeys(owner, v, out, depth + 1);
        }
    }

    /** When a row merges several states, an error wins over success and success over progress. */
    private static String pickDominant(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) return null;
        for (String want : new String[] { "error", "true", "extract", "wait", "run", "help" })
            if (keys.contains(want)) return want;
        return keys.get(0);
    }

    private static String statusKeyForField(String n) {
        if (n == null) return null;
        if (n.contains("Failed")) return "error";                       // download ok but extraction failed
        if (n.startsWith("false")) return "error";                      // download failed
        if (n.contains("Extracted") || n.startsWith("true") || n.startsWith("ok")) return "true";
        if (n.equals("extracting")) return "extract";
        if (n.equals("startingIcon")) return "run";
        if (n.equals("finalizingIcon")) return "wait";
        if (n.equals("trueIcon")) return "true";
        if (n.equals("online")) return "true";                          // AvailabilityColumn
        if (n.equals("offline")) return "error";
        if (n.equals("unknown")) return "help";
        if (n.equals("mixed")) return "true";
        if (n.startsWith("iconArchive")) return "extract";              // FileColumn
        if (n.equals("iconPackageOpen")) return "folder_open";
        if (n.startsWith("iconPackage")) return "folder_add";           // collapsed, so the folder shows a plus
        return null;
    }

    private static String normalizeStatusKey(String ik) {
        if (ik == null) return null;
        String s = ik.toLowerCase();
        if (s.contains("extract")) {                        // before the generic checks below
            if (s.contains("error") || s.contains("fail")) return "error";
            if (s.contains("ok")) return "true";
            return "extract";
        }
        if (s.contains("error") || s.contains("false") || s.contains("offline") || s.contains("fail")) return "error";
        if (s.contains("ok") || s.contains("true") || s.contains("finish") || s.contains("online")) return "true";
        if (s.contains("wait") || s.contains("final") || s.contains("hourglass")) return "wait";
        if (s.contains("start") || s.equals("run")) return "run";
        if (s.contains("help") || s.contains("unknown") || s.contains("question")) return "help";
        return null;
    }

    // The list icons are recoloured in a wrapping cell renderer, since JD's live repaints would undo any
    // change made to the icons themselves. The hoster column keeps its coloured favicons.
    private static void monoTableRowIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                if (inConfigPanel(t)) continue;                       // handled by wrapExtCellRenderer
                javax.swing.table.TableColumnModel cm = t.getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    javax.swing.table.TableColumn tc = cm.getColumn(i);
                    javax.swing.table.TableCellRenderer cur = tc.getCellRenderer();
                    if (cur == null || cur instanceof MonoIconRenderer) continue;
                    String cn = cur.getClass().getName().toLowerCase();
                    if (cn.contains("favicon") || cn.contains("hoster")) continue;
                    tc.setCellRenderer(new MonoIconRenderer(cur));
                }
            }
        }
    }
    private static boolean inConfigPanel(Component c) {
        for (Container p = c.getParent(); p != null; p = p.getParent())
            if (isConfigPanel(p.getClass())) return true;
        return false;
    }
    // Keyless status glyphs are shared, so caching their tint saves an image per row on every repaint.
    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> ROW_MONO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, javax.swing.Icon>());
    private static final class MonoIconRenderer implements javax.swing.table.TableCellRenderer {
        private final javax.swing.table.TableCellRenderer orig;
        MonoIconRenderer(javax.swing.table.TableCellRenderer o) { orig = o; }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            Component c = orig.getTableCellRendererComponent(t, v, sel, foc, row, col);
            // The task column returns a custom component with the glyph on an inner label, so the whole
            // returned tree is walked.
            monoRowIconsIn(c, sel, Boolean.TRUE.equals(t.getClientProperty("jdp.extCfg")));
            return c;
        }
    }
    private static void monoRowIconsIn(Component c, boolean sel, boolean cfg) {
        javax.swing.Icon ic = null;
        if (c instanceof javax.swing.JLabel) ic = ((javax.swing.JLabel) c).getIcon();
        else if (c instanceof javax.swing.AbstractButton) ic = ((javax.swing.AbstractButton) c).getIcon();
        if (ic != null) {
            javax.swing.Icon mono = cfg && isMergedIcon(ic) ? monoMergedIcon(ic, c.getForeground()) : monoRowIcon(ic, sel);
            if (mono != ic) {
                if (c instanceof javax.swing.JLabel) ((javax.swing.JLabel) c).setIcon(mono);
                else ((javax.swing.AbstractButton) c).setIcon(mono);
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoRowIconsIn(ch, sel, cfg);
    }
    /** A keyed glyph becomes its Tabler twin, a keyless one a mono tint of its own shape. */
    private static javax.swing.Icon monoRowIcon(javax.swing.Icon ic, boolean sel) {
        if (isSiteLogo(ic)) return ic;
        Color tone = sel ? accentFg() : SIDEBAR_TEXT;
        if (iconKey(ic) != null) return tablerIcon(ic, tone, null);
        if (sel) return tintSolid(ic, tone);
        javax.swing.Icon m = ROW_MONO.get(ic);
        if (m == null) { m = tintSolid(ic, tone); ROW_MONO.put(ic, m); }
        return m;
    }

    private static boolean isMergedIcon(javax.swing.Icon ic) {
        for (Class<?> k = ic.getClass(); k != null; k = k.getSuperclass())
            if (k.getName().equals("org.appwork.swing.components.ExtMergedIcon")) return true;
        return false;
    }
    // The help and reset buttons of the advanced settings are one MergedIcon, and a silhouette of it turns
    // the help disc into a blank dot. Each part gets its Tabler twin in the cell text colour, and parts JD
    // greyed out stay dim.
    private static final java.util.Map<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>> MERGED_MONO =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>>());
    private static javax.swing.Icon monoMergedIcon(javax.swing.Icon ic, Color fg) {
        if (isSiteLogo(ic)) return ic;
        Color tone = fg != null ? fg : SIDEBAR_TEXT;
        Integer tk = Integer.valueOf(tone.getRGB());
        java.util.Map<Integer, javax.swing.Icon> per = MERGED_MONO.computeIfAbsent(ic, k -> new java.util.concurrent.ConcurrentHashMap<>());
        javax.swing.Icon hit = per.get(tk);
        if (hit != null) return hit;
        javax.swing.Icon out;
        try {
            Class<?> ext = ic.getClass();
            while (!ext.getName().equals("org.appwork.swing.components.ExtMergedIcon")) ext = ext.getSuperclass();
            java.util.Collection<?> parts = (java.util.Collection<?>) fieldValue(ic, ext, "entries");
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(ic.getIconWidth(), ic.getIconHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            for (Object e : parts) {
                javax.swing.Icon part = (javax.swing.Icon) e.getClass().getField("icon").get(e);
                String key = iconKey(part);
                boolean off = key != null && key.endsWith("_disabled");
                if (off) key = key.substring(0, key.length() - "_disabled".length());
                javax.swing.Icon base = tablerBase(key, part.getIconWidth(), part.getIconHeight());
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, off ? 0.4f : 1f));
                tintSolid(base != null ? base : part, tone).paintIcon(null, g,
                        e.getClass().getField("x").getInt(e), e.getClass().getField("y").getInt(e));
            }
            g.dispose();
            out = new javax.swing.ImageIcon(img);
        } catch (Throwable t) { out = tintSolid(ic, tone); }
        per.put(tk, out);
        return out;
    }

    // Site favicons keep their colours. Generic renderers such as the plugin settings list show them too,
    // so the check looks at the icon class and any icon it wraps rather than at the renderer.
    private static boolean isSiteLogo(javax.swing.Icon ic) { return isSiteLogoIn(ic, 0); }
    private static boolean isSiteLogoIn(javax.swing.Icon ic, int depth) {
        if (ic == null || depth > 3) return false;
        String cn = ic.getClass().getName().toLowerCase();
        if (cn.contains("favicon") || cn.contains("domaininfo")) return true;
        for (Class<?> cl = ic.getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass())
            for (java.lang.reflect.Field f : cl.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(ic);
                    if (v instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) v, depth + 1)) return true;
                    if (v instanceof Object[]) for (Object o : (Object[]) v)
                        if (o instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) o, depth + 1)) return true;
                    if (v instanceof java.util.Collection) for (Object o : (java.util.Collection<?>) v)
                        if (o instanceof javax.swing.Icon && isSiteLogoIn((javax.swing.Icon) o, depth + 1)) return true;
                } catch (Throwable ignore) { }
            }
        return false;
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
    // Mono icons in the settings tables (packagizer, extensions, advanced). The jdp.extCfg mark set here
    // is what wrapExtCellRenderer reads.
    private static void monoConfigTableIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                boolean cfg = inConfigPanel(t);
                if (!Boolean.valueOf(cfg).equals(t.getClientProperty("jdp.extCfg")))
                    t.putClientProperty("jdp.extCfg", Boolean.valueOf(cfg));
                if (!cfg) continue;
                javax.swing.table.TableColumnModel cm = t.getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    javax.swing.table.TableColumn tc = cm.getColumn(i);
                    javax.swing.table.TableCellRenderer cur = tc.getCellRenderer();
                    if (cur == null) { try { cur = t.getCellRenderer(0, i); } catch (Throwable ig) { cur = null; } }
                    if (cur == null || cur instanceof MonoIconRenderer) continue;
                    String cn = cur.getClass().getName().toLowerCase();
                    if (cn.contains("favicon") || cn.contains("hoster") || cn.contains("domain")) continue;
                    tc.setCellRenderer(new MonoIconRenderer(cur));
                }
                java.util.Set<Class<?>> seen = new java.util.HashSet<Class<?>>();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    Class<?> cc; try { cc = t.getColumnClass(i); } catch (Throwable ig) { continue; }
                    if (!seen.add(cc)) continue;
                    javax.swing.table.TableCellRenderer def = t.getDefaultRenderer(cc);
                    if (def == null || def instanceof MonoIconRenderer) continue;
                    String dn = def.getClass().getName().toLowerCase();
                    if (dn.contains("favicon") || dn.contains("hoster") || dn.contains("domain")) continue;
                    t.setDefaultRenderer(cc, new MonoIconRenderer(def));
                }
            }
        }
    }

    // The properties panel below the download and linkgrabber lists draws hairlines, separators and framed
    // fields on a slightly different background. It is flattened onto the base colour, with its fields
    // styled like the settings pages.
    private static void stylePropertiesPanel() {
        for (Window w : Window.getWindows()) if (w.isShowing()) stylePropsIn(w);
    }
    private static boolean isPropertiesRoot(Class<?> k) {
        String n = k.getName();
        return n.contains(".properties.")
                && (n.endsWith("ScrollPane") || n.endsWith("BasePanel"));
    }
    private static void stylePropsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container && isPropertiesRoot(ch.getClass())) {
                styleOneProperties((Container) ch);
            } else if (ch instanceof Container) {
                stylePropsIn((Container) ch);
            }
        }
    }
    private static void styleOneProperties(Container root) {
        try {
            clearLinesIn(root, 0);
            hideSeparators(root);
            unifyFieldsIn(root, true);
            pinPropsBg(root, 0);
        } catch (Throwable ignore) { }
    }
    // Fields and cards already carry their own colour and are left alone.
    private static void pinPropsBg(Component c, int depth) {
        if (depth > 14) return;
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            boolean field = (c instanceof javax.swing.text.JTextComponent) || (c instanceof javax.swing.JComboBox)
                    || (c instanceof javax.swing.JSpinner) || (c instanceof AbstractButton);
            if (!field) {
                Color bg = jc.getBackground();
                if (bg != null && !BASE_BG.equals(bg) && !FIELD_BG.equals(bg) && !DIALOG_BG.equals(bg)) {
                    jc.setBackground(BASE_BG);
                    if (!jc.isOpaque() && (c instanceof javax.swing.JPanel)) jc.setOpaque(true);
                }
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) pinPropsBg(ch, depth + 1);
    }

    // monoChromeIn reaches a section header only when an ancestor is recognised as a config panel, which the
    // extensions and packagizer panels are not, so headers are found by class name as well.
    private static void monoSectionHeaders() {
        for (Window w : Window.getWindows()) if (w.isShowing()) monoSectionHeadersIn(w);
    }
    private static void monoSectionHeadersIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch.getClass().getName().endsWith(".Header")) monoHeaderIcons(ch);
            else if (ch instanceof Container) monoSectionHeadersIn((Container) ch);
        }
    }
    private static void monoHeaderIcons(Component c) {
        if (c instanceof javax.swing.JLabel) monoLabelIcon((javax.swing.JLabel) c, true);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoHeaderIcons(ch);
    }

    // The status bar glyphs in the bottom-right corner are keyless ImageIcons that no other pass reaches.
    // Only that corner band is touched, so content icons stay as they are.
    private static void monoCornerIcons() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            int winW = w.getWidth(), winH = w.getHeight();
            if (winW < 400 || winH < 400) continue;             // the main frame only
            monoCornerIn(w, w, winW, winH);
        }
    }
    private static void monoCornerIn(Component c, Window win, int winW, int winH) {
        try {
            javax.swing.Icon ic = (c instanceof javax.swing.JLabel) ? ((javax.swing.JLabel) c).getIcon()
                    : (c instanceof AbstractButton) ? ((AbstractButton) c).getIcon() : null;
            if (ic != null && iconKey(ic) == null && !isSiteLogo(ic) && c.getParent() != null) {
                java.awt.Point pt = javax.swing.SwingUtilities.convertPoint(c.getParent(), c.getLocation(), win);
                boolean rightCol = pt.x + c.getWidth() > winW - 90;
                // The overview panel's settings and close buttons derive their glyph on every paint and ignore
                // setIcon, so they get their own ButtonUI instead.
                if (rightCol && (c instanceof AbstractButton) && c.getClass().getName().contains(".overviewpanel.")) {
                    hlInstallOverviewCornerButton((AbstractButton) c);
                } else if (rightCol && pt.y > winH - 40) {
                    if (c instanceof javax.swing.JLabel) {
                        javax.swing.JLabel l = (javax.swing.JLabel) c;
                        if (ic != l.getClientProperty("jdp.monoCorner")) {
                            javax.swing.Icon m = tintSolid(ic, EXPANDER_LIGHT);
                            if (m != ic) { l.setIcon(m); l.putClientProperty("jdp.monoCorner", m); }
                        }
                    } else if (c instanceof AbstractButton) {
                        AbstractButton b = (AbstractButton) c;
                        if (ic != b.getClientProperty("jdp.monoCorner")) {
                            javax.swing.Icon m = tintSolid(ic, EXPANDER_LIGHT);
                            if (m != ic) { b.setIcon(m); b.putClientProperty("jdp.monoCorner", m); }
                        }
                    }
                }
            }
        } catch (Throwable ig) { }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) monoCornerIn(ch, win, winW, winH);
    }

    // A taller column header for the two main tables, with the hovered title in the accent. A fixed
    // preferred size on the header freezes its width, so the cells get vertical padding instead and
    // JTableHeader grows to the tallest one.
    private static void growTableHeaders() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            List<JTable> tables = new ArrayList<>();
            collectTables(w, tables);
            for (JTable t : tables) {
                String sn = t.getClass().getSimpleName();
                if (!(sn.contains("DownloadsTable") || sn.contains("LinkGrabberTable"))) continue;
                javax.swing.table.JTableHeader h = t.getTableHeader();
                if (h == null) continue;
                installHeaderHover(h);
                wrapHeaderColumnRenderers(h);
            }
        }
    }
    private static void installHeaderHover(final javax.swing.table.JTableHeader h) {
        if (h.getClientProperty("jdp.hdrHover") != null) return;
        h.putClientProperty("jdp.hdrHover", Boolean.TRUE);
        h.putClientProperty("jdp.hoverCol", Integer.valueOf(-1));
        h.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int col = h.columnAtPoint(e.getPoint());
                Object cur = h.getClientProperty("jdp.hoverCol");
                if (!(cur instanceof Integer) || ((Integer) cur).intValue() != col) {
                    h.putClientProperty("jdp.hoverCol", Integer.valueOf(col));
                    h.repaint();
                }
            }
        });
        h.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                h.putClientProperty("jdp.hoverCol", Integer.valueOf(-1));
                h.repaint();
            }
        });
        javax.swing.table.TableCellRenderer base = h.getDefaultRenderer();
        if (base != null && !(base instanceof HoverHeaderRenderer))
            h.setDefaultRenderer(new HoverHeaderRenderer(base, h));
    }
    // ExtTable paints the header through per-column renderers and never uses the default one. Runs on every
    // tick so rebuilt or reordered columns are wrapped again.
    private static void wrapHeaderColumnRenderers(javax.swing.table.JTableHeader h) {
        try {
            javax.swing.table.TableColumnModel cm = h.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                javax.swing.table.TableColumn tc = cm.getColumn(i);
                javax.swing.table.TableCellRenderer hr = tc.getHeaderRenderer();
                if (hr == null || hr instanceof HoverHeaderRenderer) continue;
                tc.setHeaderRenderer(new HoverHeaderRenderer(hr, h));
            }
        } catch (Throwable ignore) { }
    }
    private static final class HoverHeaderRenderer implements javax.swing.table.TableCellRenderer {
        final javax.swing.table.TableCellRenderer base;   // read by fixWidthLockIcon
        private final javax.swing.table.JTableHeader hdr;
        HoverHeaderRenderer(javax.swing.table.TableCellRenderer b, javax.swing.table.JTableHeader h) { base = b; hdr = h; }
        public Component getTableCellRendererComponent(JTable t, Object v, boolean s, boolean f, int r, int col) {
            Component c = base.getTableCellRendererComponent(t, v, s, f, r, col);
            try {
                Object hc = hdr.getClientProperty("jdp.hoverCol");
                boolean hot = (hc instanceof Integer) && ((Integer) hc).intValue() == col;
                if (c != null) c.setForeground(hot ? accentColor() : SIDEBAR_TEXT);
                if (c instanceof JComponent) {
                    JComponent jc = (JComponent) c;
                    javax.swing.border.Border cur = jc.getBorder();
                    java.awt.Insets in = (cur == null) ? new java.awt.Insets(0, 0, 0, 0) : cur.getBorderInsets(jc);
                    if (in.top < 6)
                        jc.setBorder(new javax.swing.border.EmptyBorder(6, Math.max(in.left, 6), 6, Math.max(in.right, 6)));
                }
            } catch (Throwable ig) { }
            return c;
        }
    }

    /**
     * The ExtTables in the settings panels ignore FlatLaf's Table and TableHeader keys and paint their
     * own header band, grid and frame, so each one is flattened at the instance level.
     */
    private static void borderlessConfigTables() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) borderlessTablesIn(w, false);
        }
    }

    private static void borderlessTablesIn(Container c, boolean inConfig) {
        boolean nowConfig = inConfig || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component child : c.getComponents()) {
            if (nowConfig && child instanceof JTable) flattenConfigTable((JTable) child);
            if (child instanceof Container) borderlessTablesIn((Container) child, nowConfig);
        }
    }

    /**
     * Replaces the per-column ExtTableHeaderRenderer, which paints its own grey band whatever the header
     * background is. Being a renderer, it survives JD's repaints where a setBackground on the header does not.
     */
    private static final javax.swing.table.TableCellRenderer DARK_HEADER = new javax.swing.table.TableCellRenderer() {
        private final javax.swing.table.DefaultTableCellRenderer base = new javax.swing.table.DefaultTableCellRenderer();
        public Component getTableCellRendererComponent(JTable tbl, Object v, boolean s, boolean f, int r, int col) {
            Component c = base.getTableCellRendererComponent(tbl, v, s, f, r, col);
            c.setBackground(DIALOG_BG);
            c.setForeground(SIDEBAR_TEXT);
            if (c instanceof JComponent) {
                ((JComponent) c).setOpaque(true);
                ((JComponent) c).setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8));
            }
            return c;
        }
    };

    // The setters below revalidate and repaint even when nothing changes, and this runs on every tick.
    private static final javax.swing.border.Border NO_BORDER = javax.swing.BorderFactory.createEmptyBorder();
    private static final Dimension NO_SPACING = new Dimension(0, 0);
    private static void flattenConfigTable(JTable t) {
        try {
            if (t.getShowHorizontalLines() || t.getShowVerticalLines()) t.setShowGrid(false);
            if (!NO_SPACING.equals(t.getIntercellSpacing())) t.setIntercellSpacing(NO_SPACING);
            // UrlOrderTable forces horizontal lines on, so they take the card colour.
            if (!DIALOG_BG.equals(t.getGridColor())) t.setGridColor(DIALOG_BG);

            javax.swing.table.JTableHeader h = t.getTableHeader();
            if (h != null) {
                if (!DIALOG_BG.equals(h.getBackground())) h.setBackground(DIALOG_BG);
                if (h.getBorder() != NO_BORDER) h.setBorder(NO_BORDER);
                javax.swing.table.TableCellRenderer dr = h.getDefaultRenderer();
                if (dr instanceof JComponent) {
                    ((JComponent) dr).setBackground(DIALOG_BG);
                    ((JComponent) dr).setBorder(NO_BORDER);
                }
            }
            javax.swing.table.TableColumnModel hcm = t.getColumnModel();
            for (int i = 0; i < hcm.getColumnCount(); i++) {
                javax.swing.table.TableColumn col = hcm.getColumn(i);
                if (col.getHeaderRenderer() != DARK_HEADER) col.setHeaderRenderer(DARK_HEADER);
            }
            Container sp = SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, t);
            if (sp instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane s = (javax.swing.JScrollPane) sp;
                if (s.getBorder() != NO_BORDER) s.setBorder(NO_BORDER);
                if (s.getViewportBorder() != NO_BORDER) s.setViewportBorder(NO_BORDER);
                // The table background fills the viewport, so the card reaches down to the sidebar's bottom.
                if (!t.getFillsViewportHeight()) t.setFillsViewportHeight(true);
                javax.swing.JViewport vp = s.getViewport();
                if (vp != null && t.getBackground() != null
                        && !t.getBackground().equals(vp.getBackground())) {
                    vp.setBackground(t.getBackground());
                }
            }
            accentTableCheckmarks(t);
        } catch (Throwable ignore) { }
    }

    /** Accent colour for checkmarks in boolean columns, where the renderer is a JCheckBox. */
    private static void accentTableCheckmarks(JTable t) {
        try {
            Color acc = accentColor();
            if (acc == null) return;
            javax.swing.table.TableColumnModel cm = t.getColumnModel();
            for (int i = 0; i < cm.getColumnCount(); i++) {
                javax.swing.table.TableCellRenderer r = cm.getColumn(i).getCellRenderer();
                if (r instanceof javax.swing.JCheckBox) ((javax.swing.JCheckBox) r).setForeground(acc);
            }
        } catch (Throwable ignore) { }
    }

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
        if (!wantDarkLaf()) return;
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
        // jd-highlighter brings its whole palette in the template, so it has nothing to remap; darkening
        // its greys would sink the dialog surface and the scrollbar thumb into the base colour.
        List<Object> keys = isHighlighter() ? new ArrayList<>() : new ArrayList<>(d.keySet());
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
        // jd-highlighter keeps its accent (focus/selection) and its own #1e1e1e header;
        // only plain Dark greys the FlatLaf accent + darkens the header here.
        if (!isHighlighter()) {
            d.put("Component.accentColor", SEL);   // FlatLaf derives focus/selection from this
            d.put("TableHeader.background", HEADER);
        }
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
                g.setColor(PAL_MONO);
                g.drawRect(x, y, 10, 10);
                g.drawLine(x + 3, y + 5, x + 7, y + 5);            // horizontal bar
                if (plus) g.drawLine(x + 5, y + 3, x + 5, y + 7);  // vertical -> plus
            }
        });
    }

    // jd-highlighter polish for what JD hardcodes and no theme key reaches. Plain Dark
    // keeps stock JD, so every hook checks isHighlighter() first and leaves JD as it was
    // on any failure. The desktop install is always jd-highlighter.

    private static final boolean HIGHLIGHTER = DESKTOP
            || System.getenv().getOrDefault("JD_THEME", "").toLowerCase(java.util.Locale.ROOT).contains("highlight");

    // A desktop JD can still run another look and feel (FlatLaf arrives with JD's own
    // design download), and the polish on top of it would leave a half-themed window.
    // tick() records the active one; the container always runs FlatLaf here.
    private static volatile boolean desktopFlatLaf = false;

    private static boolean isHighlighter() { return HIGHLIGHTER && (!DESKTOP || desktopFlatLaf); }

    private static boolean isFlatDark(LookAndFeel laf) {
        try {
            return laf != null && laf.getClass().getName().startsWith("com.formdev.flatlaf.")
                    && Boolean.TRUE.equals(laf.getClass().getMethod("isDark").invoke(laf));
        } catch (Throwable t) { return false; }
    }

    // jdownloader-theme.sh renders the accent into the defaults as "@accentColor = #rrggbb".
    // UIManager is no source for it, because enforceDarkChrome greys a blue accent there.
    private static final java.io.File RENDERED_DEFAULTS =
            new java.io.File(DEFAULTS_DIR, "FlatDarkLaf.properties");
    private static Color   accentCache = null;
    private static boolean accentRead  = false;
    private static Color accentColor() {
        if (accentRead) return accentCache;
        String hex = null;
        try {
            for (String line : java.nio.file.Files.readAllLines(RENDERED_DEFAULTS.toPath())) {
                String s = line.trim();
                if (s.startsWith("@accentColor")) {
                    int eq = s.indexOf('=');
                    if (eq > 0) { hex = s.substring(eq + 1).trim(); break; }
                }
            }
        } catch (Throwable ignore) { }
        if (hex == null || hex.isEmpty()) {
            String env = System.getenv("JD_ACCENT");
            hex = (env != null && !env.isEmpty()) ? env : "#ffee00";
        }
        if (!hex.startsWith("#")) hex = "#" + hex;
        try { accentCache = Color.decode(hex); }
        catch (Throwable e) { accentCache = new Color(0xff, 0xee, 0x00); }
        accentRead = true;
        return accentCache;
    }

    /** Text colour on the accent, taken from the selected tab foreground. */
    private static Color accentFg() {
        Color sel = UIManager.getColor("TabbedPane.selectedForeground");
        if (sel != null) return new Color(sel.getRGB());
        Color a = accentColor();
        double lum = 0.299 * a.getRed() + 0.587 * a.getGreen() + 0.114 * a.getBlue();
        return lum > 140 ? PAL_BASE : PAL_TEXT;
    }

    private static javax.swing.ListCellRenderer<?> asRenderer(Object o) {
        return (o instanceof javax.swing.ListCellRenderer) ? (javax.swing.ListCellRenderer<?>) o : null;
    }

    private static final int SIDEBAR_ROW_PX = 66;   // must exceed JD's own row height of about 53
    private static final double SIDEBAR_ICON_SCALE = 1.4;
    private static final java.util.Map<javax.swing.Icon, javax.swing.Icon> SCALED_ICONS = new java.util.WeakHashMap<>();
    private static final Color SIDEBAR_TEXT = PAL_TEXT;
    // A disabled ExtButton paints a disabledIcon that JD derives from the original glyph, so it needs its
    // own tinted version.
    private static final Color DISABLED_TONE = PAL_DISABLED;

    private static final java.util.Map<javax.swing.Icon, java.util.Map<Integer, javax.swing.Icon>> TINTS =
            new java.util.WeakHashMap<>();
    /**
     * A single-tone silhouette of an icon, cached per tone. JD caches its Icon objects at startup, so
     * recolouring the SVG files is not enough; this runs in the render path instead.
     */
    private static javax.swing.Icon tintIcon(javax.swing.Icon orig, Color tint, Component c) {
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
    private static final String KEY_NONE = " noKey";

    /** The JD icon's name, such as "reconnect" or "logo/myjdownloader", or null. Merged and scaled icons
     *  report no key, so the first of their parts that has one is used. */
    private static String iconKey(javax.swing.Icon ic) {
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
    private static javax.swing.Icon tablerBase(String key, int w, int h) {
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
    private static javax.swing.Icon tablerIcon(javax.swing.Icon orig, Color tint, Component c) {
        try {
            javax.swing.Icon base = tablerBase(iconKey(orig), orig.getIconWidth(), orig.getIconHeight());
            if (base != null) return tintIcon(base, tint, c);
        } catch (Throwable ignore) { }
        return tintIcon(orig, tint, c);
    }

    private static javax.swing.Icon scaleIconTo(javax.swing.Icon ic, int size) {
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
    private static javax.swing.Icon tablerIconScaled(javax.swing.Icon orig, Color tint, Component c, double factor) {
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
    private static void monoChromeIcons() {
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
    private static final java.util.Map<String, String> ACTION_ICON = new java.util.HashMap<>();
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
            if (cur == null) return;
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

    /** Menu items paint getIcon() whatever their state, so the item gets a light and a dark icon and
     *  installMenuItemHoverIcon swaps them when it is armed. */
    private static void monoMenuItemIcon(javax.swing.JMenuItem mi) {
        try {
            javax.swing.Icon cur = mi.getIcon();
            if (cur == null) return;                                   // check and radio glyphs are painted by the UI
            Object light = mi.getClientProperty("jdp.miLight");
            if (cur == light || cur == mi.getClientProperty("jdp.miDark")) { installMenuItemHoverIcon(mi); return; }
            javax.swing.Icon lo = tablerIcon(cur, SIDEBAR_TEXT, mi);
            // Extension toggles such as the event scripter have no Tabler key and a baked-in black box
            // behind the glyph, which is cleared before tinting.
            if (lo == cur) lo = tintSolid(deBoxIcon(cur, mi), SIDEBAR_TEXT, mi);
            // A fresh tablerIcon in the dark tone comes out fully transparent for some menu glyphs, so the
            // dark twin is tinted from the light one.
            javax.swing.Icon hi = (lo != cur) ? tintSolid(lo, accentFg()) : tablerIcon(cur, accentFg(), mi);
            if (lo == cur) return;
            // FlatMenuItemRenderer paints getIcon() even for checked items and popups rebuild their items on
            // every open, so the check badge is decided at paint time. A JMenu counts as selected while its
            // popup is open, so it gets none.
            if (!(mi instanceof javax.swing.JMenu)) { lo = hlStateCheckIcon(lo, mi); hi = hlStateCheckIcon(hi, mi); }
            mi.putClientProperty("jdp.miLight", lo);
            mi.putClientProperty("jdp.miDark", hi);
            mi.setIcon(lo);
            // Check box menu items paint the selected icon when checked and the pressed icon when armed.
            mi.setSelectedIcon(lo);
            mi.setPressedIcon(hi);
            mi.setIconTextGap(10);
            mi.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
            installMenuItemHoverIcon(mi);
        } catch (Throwable ignore) { }
    }

    /** Draws a check badge in the bottom-right corner while the item is selected, with colours inverted on
     *  the armed row. */
    private static javax.swing.Icon hlStateCheckIcon(final javax.swing.Icon base, final javax.swing.JMenuItem mi) {
        return new javax.swing.Icon() {
            public int getIconWidth()  { return base.getIconWidth(); }
            public int getIconHeight() { return base.getIconHeight(); }
            public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
                base.paintIcon(c, g, x, y);
                if (!mi.isSelected()) return;
                int w = base.getIconWidth(), h = base.getIconHeight();
                int d = Math.max(9, Math.round(w * 0.66f));
                int bx = x + w - d, by = y + h - d;
                boolean armed = mi.getModel().isArmed();
                java.awt.Color disc = armed ? accentFg() : accentColor();
                java.awt.Color chk  = armed ? accentColor() : accentFg();
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(disc);
                g2.fillOval(bx, by, d, d);
                g2.setColor(chk);
                g2.setStroke(new java.awt.BasicStroke(Math.max(1.3f, d / 6.5f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                int[] xs = { bx + Math.round(d * 0.26f), bx + Math.round(d * 0.44f), bx + Math.round(d * 0.74f) };
                int[] ys = { by + Math.round(d * 0.52f), by + Math.round(d * 0.68f), by + Math.round(d * 0.34f) };
                g2.drawPolyline(xs, ys, 3);
                g2.dispose();
            }
        };
    }

    // Some popups, such as the status bar customize menu, arm their rows in grey, where the dark icon
    // disappears. BasicMenuItemUI keeps the selection colours in fields, so they are set there.
    private static void forceMenuItemAccent(javax.swing.JMenuItem mi) {
        try {
            Object ui = mi.getUI();
            if (ui != null) {
                setUiColorField(ui, "selectionBackground", accentColor());
                setUiColorField(ui, "selectionForeground", accentFg());
            }
        } catch (Throwable ignore) { }
    }

    private static void installMenuItemHoverIcon(final javax.swing.JMenuItem mi) {
        if (mi.getClientProperty("jdp.miHoverIcon") != null) return;
        mi.putClientProperty("jdp.miHoverIcon", Boolean.TRUE);
        mi.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                javax.swing.ButtonModel m = mi.getModel();
                // FlatLaf paints a dark slot behind the icon of a checked item, so that glyph stays light
                // even when armed. A JMenu is selected but not armed while its submenu is open,
                // and its row keeps the accent then.
                boolean open = mi instanceof javax.swing.JMenu && m.isSelected();
                boolean hot = mi.isEnabled() && (open || m.isArmed() && (mi instanceof javax.swing.JMenu || !m.isSelected()));
                Object want = mi.getClientProperty(hot ? "jdp.miDark" : "jdp.miLight");
                if (want instanceof javax.swing.Icon) {
                    javax.swing.Icon wi = (javax.swing.Icon) want;
                    if (mi.getIcon() != wi) mi.setIcon(wi);
                    if (mi.getSelectedIcon() != wi) mi.setSelectedIcon(wi);
                }
                // forceMenuItemAccent only runs on the next tick, so the first hover in a fresh menu would
                // still show light text on the accent.
                Color miFg = (mi.isEnabled() && (m.isArmed() || open)) ? accentFg() : SIDEBAR_TEXT;
                if (!miFg.equals(mi.getForeground())) mi.setForeground(miFg);
            }
        });
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
        return tablerIcon(icon, tone, b);
    }

    // JD's AppWork inputs ignore @componentBackground and come in several fills, so the settings get one
    // recessed fill for fields and one raised fill for buttons, darker and lighter than the card.
    private static final Color FIELD_BG   = PAL_FIELD;
    private static final Color BTN_CFG_BG = PAL_BUTTON;
    private static final Color BASE_BG    = PAL_BASE;
    private static void unifyConfigFields() {
        for (Window w : Window.getWindows()) if (w.isShowing()) unifyFieldsIn(w, false);
    }

    // Some JD combo box popups hard-code a blue selection. Their JList is reachable through the UI's
    // accessible child.
    private static void styleComboPopup(javax.swing.JComboBox<?> cb) {
        try {
            javax.accessibility.Accessible a = cb.getUI().getAccessibleChild(cb, 0);
            if (a instanceof javax.swing.plaf.basic.ComboPopup) {
                javax.swing.JList<?> list = ((javax.swing.plaf.basic.ComboPopup) a).getList();
                Color acc = accentColor();
                if (list != null && acc != null && !acc.equals(list.getSelectionBackground())) {
                    list.setSelectionBackground(acc);
                    list.setSelectionForeground(accentFg());
                }
            }
        } catch (Throwable ignore) { }
    }

    private static void unifyFieldsIn(Container c, boolean inCfg) {
        boolean cfg = inCfg || (c instanceof JComponent && isConfigPanel(c.getClass()));
        for (Component ch : c.getComponents()) {
            if (cfg) {
                try {
                    if (ch instanceof javax.swing.text.JTextComponent
                            && !((javax.swing.text.JTextComponent) ch).isEditable()) {
                        // A read-only text component is a description, not an input, so it sits on
                        // the card without a field fill.
                        if (((JComponent) ch).isOpaque()) ((JComponent) ch).setOpaque(false);
                        if (!DIALOG_BG.equals(ch.getBackground())) ch.setBackground(DIALOG_BG);
                        stripFramingBorder((JComponent) ch);
                    } else if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                            || ch instanceof javax.swing.JSpinner) {
                        if (!FIELD_BG.equals(ch.getBackground())) {
                            ch.setBackground(FIELD_BG);
                            if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                        }
                        // borderWidth=0 only reaches FlatBorder, and the settings pages are not dialogs,
                        // so recolorDialogs never strips their AppWork frames.
                        stripFramingBorder((JComponent) ch);
                        if (ch instanceof javax.swing.JComboBox) styleComboPopup((javax.swing.JComboBox<?>) ch);
                    } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                        javax.swing.AbstractButton ab = (javax.swing.AbstractButton) ch;
                        // A hovered button carries the accent from installBtnHoverBg.
                        boolean hot = ab.getModel().isRollover() || ab.getModel().isPressed()
                                || Boolean.TRUE.equals(ab.getClientProperty("jdp.hovered"));
                        if (!hot && !BTN_CFG_BG.equals(ab.getBackground())) ab.setBackground(BTN_CFG_BG);
                        installBtnHoverFg(ab);
                        installBtnHoverBg(ab);
                        stripFramingBorder((JComponent) ch);
                    } else if (isCheckLike(ch) && ch instanceof JComponent) {
                        // The check glyph keeps its own CheckBox.icon border.
                        stripFramingBorder((JComponent) ch);
                    }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) unifyFieldsIn((Container) ch, cfg);
        }
    }
    private static boolean isCheckLike(Component ch) {
        if (ch instanceof javax.swing.JCheckBox || ch instanceof javax.swing.JRadioButton) return true;
        String n = ch.getClass().getSimpleName().toLowerCase();
        return n.contains("check") || n.contains("radio") || n.contains("toggle");
    }

    /** Dark text on a button while its accent hover fill shows. */
    private static void installBtnHoverFg(final javax.swing.AbstractButton b) {
        if (b.getClientProperty("jdp.hoverFg") != null) return;
        b.putClientProperty("jdp.hoverFg", Boolean.TRUE);
        b.setRolloverEnabled(true);   // off by default on JButton, so JD's SettingsButton reports no rollover
        b.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            private boolean applied = false;
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean roll = b.isEnabled() && (b.getModel().isRollover() || b.getModel().isPressed());
                if (roll && !applied) {
                    b.putClientProperty("jdp.savedFg", b.getForeground());
                    b.setForeground(accentFg());
                    applied = true;
                } else if (!roll && applied) {
                    Object s = b.getClientProperty("jdp.savedFg");
                    if (s instanceof Color) b.setForeground((Color) s);
                    applied = false;
                }
            }
        });
    }

    /** Accent background on a settings button while it is hovered or pressed. */
    private static void installBtnHoverBg(final javax.swing.AbstractButton b) {
        if (b.getClientProperty("jdp.hoverBg") != null) return;
        b.putClientProperty("jdp.hoverBg", Boolean.TRUE);
        b.setRolloverEnabled(true);
        // JD's SettingsButton is an ExtButton whose onRollOver only turns on contentAreaFilled, so FlatLaf
        // paints its grey default hover. A per-component style sets the accent instead.
        try {
            Color acc0 = accentColor();
            if (acc0 != null) {
                String hx = String.format("#%06x", acc0.getRGB() & 0xffffff);
                b.putClientProperty("FlatLaf.style", "hoverBackground: " + hx + "; pressedBackground: " + hx);
            }
        } catch (Throwable ignore) { }
        // For custom UIs that ignore the style. contentAreaFilled is restored on exit so a transparent
        // ExtButton does not keep a fill.
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (!b.isEnabled()) return;
                Color acc = accentColor();
                if (acc == null) return;
                b.putClientProperty("jdp.hovered", Boolean.TRUE);
                b.putClientProperty("jdp.savedCAF", Boolean.valueOf(b.isContentAreaFilled()));
                Object fg = b.getClientProperty("jdp.savedFg");
                if (!(fg instanceof Color)) b.putClientProperty("jdp.savedFg", b.getForeground());
                b.setContentAreaFilled(true);
                if (b instanceof JComponent) ((JComponent) b).setOpaque(true);
                b.setBackground(acc);
                b.setForeground(accentFg());
                b.repaint();
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                b.putClientProperty("jdp.hovered", null);
                Object caf = b.getClientProperty("jdp.savedCAF");
                if (caf instanceof Boolean) b.setContentAreaFilled((Boolean) caf);
                b.setBackground(BTN_CFG_BG);
                Object s = b.getClientProperty("jdp.savedFg");
                if (s instanceof Color) b.setForeground((Color) s);
                b.putClientProperty("jdp.savedFg", null);
                b.repaint();
            }
        });
        b.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            private boolean applied = false;
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                boolean roll = b.isEnabled() && (b.getModel().isRollover() || b.getModel().isPressed());
                if (roll && !applied) {
                    Color acc = accentColor();
                    if (acc != null) {
                        b.setBackground(acc);
                        if (b instanceof JComponent) ((JComponent) b).setOpaque(true);
                        b.setContentAreaFilled(true);
                    }
                    applied = true;
                } else if (!roll && applied) {
                    // The mouse listener may still hold the accent.
                    if (!Boolean.TRUE.equals(b.getClientProperty("jdp.hovered"))) b.setBackground(BTN_CFG_BG);
                    applied = false;
                }
            }
        });
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

    private static void monoLabelIcon(javax.swing.JLabel l, boolean cfg) {
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
    private static void monoRowLabels(Container c) {
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

    /**
     * Raise the Settings sidebar's row height. JD gives every entry a fixed size from a
     * shared public-static Dimension on the list's cell renderer
     * (jd...settings.sidebar.TreeRenderer.DIMENSION = new Dimension(0, 35)); there is no
     * List.cellHeight / UIManager key. Mutating that one Dimension object raises every
     * row at once. It is reached through a renderer instance found in the tree, so the
     * static field resolves in JD's own classloader regardless of the agent's.
     */

    private static void styleSidebar() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && styleSidebarIn(w)) return;
        }
    }

    private static boolean styleSidebarIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JList) {
                javax.swing.JList<?> list = (javax.swing.JList<?>) child;
                // JD's own renderer, stashed while the hover wrapper is installed.
                javax.swing.ListCellRenderer<?> cur  = list.getCellRenderer();
                javax.swing.ListCellRenderer<?> wrap = asRenderer(list.getClientProperty(SB_WRAP));
                javax.swing.ListCellRenderer<?> r =
                        (cur == wrap && wrap != null) ? asRenderer(list.getClientProperty(SB_ORIG_RENDERER)) : cur;
                if (r != null && r.getClass().getName().endsWith("sidebar.TreeRenderer")) {
                    // Centering happens in the hover wrapper, because TreeRenderer reconfigures its
                    // shared component on every call and would undo alignment set here.
                    // The tiles share the full height of the sidebar and only fall back to their minimum
                    // height, with a scrollbar, when the window is too low for that.
                    javax.swing.JScrollPane ssp = (javax.swing.JScrollPane)
                            javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, list);
                    int rows = list.getModel().getSize();
                    int viewH = ssp != null ? ssp.getViewport().getHeight() : 0;
                    int rowH = rows > 0 && viewH > 0 ? Math.max(SIDEBAR_ROW_PX, viewH / rows) : SIDEBAR_ROW_PX;
                    try {
                        Field f = r.getClass().getField("DIMENSION");
                        Object dim = f.get(null);
                        if (dim instanceof Dimension && ((Dimension) dim).height != rowH) ((Dimension) dim).height = rowH;
                        // The JList caches row heights, and the advanced settings row with its 20px icon
                        // would stay short, so the list gets a fixed cell height.
                        if (list.getFixedCellHeight() != rowH) {
                            list.setFixedCellHeight(rowH);
                            list.revalidate();
                            list.repaint();
                        }
                    } catch (Throwable t) {
                        System.out.println("[jd-dialog-agent] sidebar height bump failed: " + t);
                    }
                    installSidebarAccentHover(list, r);
                    clearSidebarBorders(list);
                    int policy = rows * SIDEBAR_ROW_PX > viewH
                            ? javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                            : javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER;
                    if (ssp != null && ssp.getVerticalScrollBarPolicy() != policy) ssp.setVerticalScrollBarPolicy(policy);
                    // JD rebuilds the panel on selection; styling it right after avoids an unstyled flash
                    // until the next tick.
                    if (list.getClientProperty("jdp.navRetheme") == null) {
                        list.putClientProperty("jdp.navRetheme", Boolean.TRUE);
                        list.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
                            public void valueChanged(javax.swing.event.ListSelectionEvent e) {
                                if (e.getValueIsAdjusting()) return;
                                javax.swing.SwingUtilities.invokeLater(new Runnable() { public void run() {
                                    try { if (isHighlighter()) {
                                        stripSectionUnderlines(); cardSettingsSections();
                                        borderlessConfigTables(); unifyConfigFields(); monoChromeIcons();
                                    } } catch (Throwable ignore) { }
                                } });
                            }
                        });
                    }
                    return true;
                }
            }
            if (child instanceof Container && styleSidebarIn((Container) child)) return true;
        }
        return false;
    }

    /** Clears the divider lines of the settings view, starting from its root. */
    private static void clearSidebarBorders(Component list) {
        Component root = list;
        for (int d = 0; d < 6 && root.getParent() != null; d++) root = root.getParent();
        clearLinesIn(root, 0);
    }

    private static void clearLinesIn(Component c, int depth) {
        if (depth > 12) return;
        if (c instanceof JComponent) {
            JComponent jc = (JComponent) c;
            javax.swing.border.Border b = jc.getBorder();
            if (b instanceof javax.swing.border.MatteBorder) {
                clearMatte(jc);
                installBorderGuard(jc);
            } else if (b instanceof javax.swing.border.LineBorder || b instanceof javax.swing.border.EtchedBorder) {
                jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                installBorderGuard(jc);
            }
            if (c instanceof javax.swing.JScrollPane) {
                javax.swing.JScrollPane sp = (javax.swing.JScrollPane) c;
                sp.setViewportBorder(null);
                // The scrollbar gutter would show as a lighter strip next to the sidebar.
                Color base = PAL_BASE;
                if (!base.equals(sp.getBackground())) sp.setBackground(base);
                if (sp.getViewport() != null && !base.equals(sp.getViewport().getBackground()))
                    sp.getViewport().setBackground(base);
                javax.swing.JScrollBar vsb = sp.getVerticalScrollBar();
                if (vsb != null) {
                    if (!base.equals(vsb.getBackground())) vsb.setBackground(base);
                    if (!vsb.isOpaque()) vsb.setOpaque(true);
                }
            }
            // The gap between sidebar and content is the split divider or a panel in @componentBackground.
            // Fields and cards fall outside this shade band and keep their colour.
            Color base2 = PAL_BASE;
            if (jc instanceof javax.swing.JSplitPane) {
                jc.setBackground(base2);
                try {
                    java.awt.Component dvd = ((javax.swing.plaf.basic.BasicSplitPaneUI)
                            ((javax.swing.JSplitPane) jc).getUI()).getDivider();
                    if (dvd != null) dvd.setBackground(base2);
                } catch (Throwable ignore) { }
            } else if (jc.getBackground() != null) {
                Color pb = jc.getBackground();
                if (pb.getRed() >= 0x1b && pb.getRed() <= 0x21
                        && pb.getGreen() >= 0x1b && pb.getGreen() <= 0x21
                        && pb.getBlue() >= 0x1b && pb.getBlue() <= 0x21)
                    jc.setBackground(base2);
            }
        }
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) clearLinesIn(ch, depth + 1);
    }

    private static void clearMatte(JComponent jc) {
        javax.swing.border.Border b = jc.getBorder();
        if (b instanceof javax.swing.border.MatteBorder) {
            java.awt.Insets in = ((javax.swing.border.MatteBorder) b).getBorderInsets(jc);
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder(in.top, in.left, in.bottom, in.right));
        }
    }

    /** JD puts divider borders back after they are cleared, such as the sidebar's in ConfigSidebar, so
     *  they are cleared again whenever the border changes. */
    private static void installBorderGuard(final JComponent jc) {
        if (jc.getClientProperty("jdp.borderGuard") != null) return;
        jc.putClientProperty("jdp.borderGuard", Boolean.TRUE);
        jc.addPropertyChangeListener("border", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                javax.swing.border.Border nb = jc.getBorder();
                if (nb instanceof javax.swing.border.MatteBorder) clearMatte(jc);
                else if (nb instanceof javax.swing.border.LineBorder || nb instanceof javax.swing.border.EtchedBorder)
                    jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
            }
        });
    }

    private static final String SB_ORIG_RENDERER = "jdp.sbOrigRenderer";
    private static final String SB_WRAP          = "jdp.sbWrap";
    private static final String SB_HOVER_ROW     = "jdp.sbHoverRow";
    private static final String SB_LISTENERS     = "jdp.sbListeners";
    private static final String SB_BTN           = "jdp.sbButton";
    private static final Color  SB_BASE          = PAL_BASE;
    private static final Color  SB_BTN_BG        = PAL_SURFACE;
    private static final int    SB_BTN_GAP_V = 3, SB_BTN_GAP_H = 8, SB_BTN_ARC = UI_ARC;

    /** Paints each sidebar row as a rounded tile behind JD's transparent cell component. One instance
     *  is reused for every cell. */
    private static final class SidebarButton extends JComponent {
        private Component content;
        private Color fill;
        SidebarButton() {
            setLayout(new java.awt.BorderLayout());
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(SB_BTN_GAP_V, SB_BTN_GAP_H, SB_BTN_GAP_V, SB_BTN_GAP_H));
        }
        void set(Component c, Color f) {
            if (content != c) { removeAll(); content = c; add(c, java.awt.BorderLayout.CENTER); }
            fill = f;
        }
        protected void paintComponent(Graphics g) {
            if (fill != null) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                java.awt.Insets in = getInsets();
                g2.setColor(fill);
                g2.fillRoundRect(in.left, in.top, getWidth() - in.left - in.right,
                        getHeight() - in.top - in.bottom, SB_BTN_ARC, SB_BTN_ARC);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Wraps the settings sidebar renderer so the hovered row is filled with the accent like a table row;
     * JD's own hover is only a faint tint. All state hangs off the JList, so a rebuilt sidebar simply
     * installs it again.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installSidebarAccentHover(final javax.swing.JList list, javax.swing.ListCellRenderer jdRenderer) {
        list.putClientProperty(SB_ORIG_RENDERER, jdRenderer);
        javax.swing.ListCellRenderer wrap = (javax.swing.ListCellRenderer) list.getClientProperty(SB_WRAP);
        if (wrap == null) {
            wrap = new javax.swing.ListCellRenderer() {
                public Component getListCellRendererComponent(javax.swing.JList l, Object v, int idx, boolean sel, boolean foc) {
                    javax.swing.ListCellRenderer real = (javax.swing.ListCellRenderer) l.getClientProperty(SB_ORIG_RENDERER);
                    Component comp = real.getListCellRendererComponent(l, v, idx, sel, foc);
                    Object h = l.getClientProperty(SB_HOVER_ROW);
                    int hoverRow = (h instanceof Integer) ? ((Integer) h).intValue() : -1;
                    // The renderer component and its RenderLabel are shared between cells and JD
                    // resets neither foreground, so every row sets its own or the hover colour leaks
                    // into the rows painted after it.
                    boolean accentBg = sel || idx == hoverRow;
                    Color rowFg = accentBg ? accentFg() : SIDEBAR_TEXT;
                    if (comp instanceof javax.swing.JComponent) ((javax.swing.JComponent) comp).setOpaque(false);
                    comp.setForeground(rowFg);
                    boolean hovAcc = accentBg;
                    if (comp instanceof Container) {
                        for (Component k : ((Container) comp).getComponents()) {
                            k.setForeground(rowFg);
                            // JD sets the original icon again for every cell, so replacing it here
                            // never compounds.
                            if (k instanceof javax.swing.JLabel) {
                                javax.swing.JLabel kl = (javax.swing.JLabel) k;
                                javax.swing.Icon ic = kl.getIcon();
                                if (ic != null) {
                                    Color sbTone = hovAcc ? accentFg() : SIDEBAR_TEXT;
                                    String sbLc = (kl.getText() == null) ? "" : kl.getText().toLowerCase();
                                    String origKey = iconKey(ic);
                                    // The tray and advanced tiles are matched by icon key as well, since the
                                    // text is blank on rows that are not hovered.
                                    String sbOverride = (sbLc.contains("tray") || "minimize".equals(origKey)) ? "bottombar"
                                                      : (sbLc.contains("advanced") || "advancedConfig".equals(origKey)) ? "wizard" : null;
                                    javax.swing.Icon sbIcon = null;
                                    if (sbOverride != null) {
                                        javax.swing.Icon ov = tablerBase(sbOverride, 32, 32);
                                        if (ov != null) {
                                            if (ov.getIconWidth() != 32 || ov.getIconHeight() != 32) ov = scaleIconTo(ov, 32);
                                            sbIcon = tintIcon(ov, sbTone, kl);
                                        }
                                    }
                                    if (sbIcon == null) sbIcon = tablerIconScaled(ic, sbTone, kl, SIDEBAR_ICON_SCALE);
                                    kl.setIcon(sbIcon);
                                }
                                // The sidebar shows only centred glyphs; the hovered and the selected
                                // row show their name below the icon. JD sets the text again on every
                                // render, so blanking it here is safe.
                                boolean reveal = (idx == hoverRow) || sel;
                                if (!reveal && kl.getText() != null && kl.getText().length() > 0) kl.setText("");
                                if (kl.getHorizontalAlignment() != javax.swing.SwingConstants.CENTER)
                                    kl.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
                                if (kl.getHorizontalTextPosition() != javax.swing.SwingConstants.CENTER)
                                    kl.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
                                int wantVpos = reveal ? javax.swing.SwingConstants.BOTTOM
                                                      : javax.swing.SwingConstants.CENTER;
                                if (kl.getVerticalTextPosition() != wantVpos) kl.setVerticalTextPosition(wantVpos);
                                if (kl.getVerticalAlignment() != javax.swing.SwingConstants.CENTER)
                                    kl.setVerticalAlignment(javax.swing.SwingConstants.CENTER);
                                kl.setIconTextGap(2);
                                // JD's MigLayout anchors the RenderLabel at the top, so a top inset centres
                                // it. The border is only replaced when the inset changes, to avoid a
                                // repaint loop.
                                int innerH = Math.max(SIDEBAR_ROW_PX, l.getFixedCellHeight()) - 2 * SB_BTN_GAP_V;
                                java.awt.Insets kcur = (kl.getBorder() instanceof javax.swing.border.EmptyBorder)
                                        ? ((javax.swing.border.EmptyBorder) kl.getBorder()).getBorderInsets(kl)
                                        : new java.awt.Insets(0, 0, 0, 0);
                                int contentH = kl.getPreferredSize().height - kcur.top - kcur.bottom;
                                int topPad = Math.max(0, (innerH - contentH) / 2);
                                if (!(kl.getBorder() instanceof javax.swing.border.EmptyBorder) || kcur.top != topPad) {
                                    kl.setBorder(javax.swing.BorderFactory.createEmptyBorder(topPad, 0, 0, 0));
                                    if (comp instanceof Container) ((Container) comp).revalidate();
                                }
                            }
                        }
                    }
                    SidebarButton btn = (SidebarButton) l.getClientProperty(SB_BTN);
                    if (btn == null) { btn = new SidebarButton(); l.putClientProperty(SB_BTN, btn); }
                    Color acc = accentColor();
                    btn.set(comp, accentBg && acc != null ? acc : SB_BTN_BG);
                    return btn;
                }
            };
            list.putClientProperty(SB_WRAP, wrap);
        }
        if (list.getCellRenderer() != wrap) list.setCellRenderer(wrap);
        if (!SB_BASE.equals(list.getBackground())) { list.setBackground(SB_BASE); list.setOpaque(true); }
        if (list.getClientProperty(SB_LISTENERS) == null) {
            list.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    int idx = list.locationToIndex(e.getPoint());
                    try {
                        if (idx >= 0) {
                            java.awt.Rectangle b = list.getCellBounds(idx, idx);
                            if (b == null || !b.contains(e.getPoint())) idx = -1;
                        }
                    } catch (Throwable ignore) { idx = -1; }
                    Object cur = list.getClientProperty(SB_HOVER_ROW);
                    int prev = (cur instanceof Integer) ? ((Integer) cur).intValue() : -1;
                    if (prev != idx) { list.putClientProperty(SB_HOVER_ROW, Integer.valueOf(idx)); list.repaint(); }
                }
            });
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseExited(java.awt.event.MouseEvent e) {
                    list.putClientProperty(SB_HOVER_ROW, Integer.valueOf(-1));
                    list.repaint();
                }
            });
            list.putClientProperty(SB_LISTENERS, Boolean.TRUE);
        }
    }

    /**
     * Strips the underline from settings section titles, which Header.java bakes into the HTML
     * markup where no colour key reaches it.
     */
    private static void stripSectionUnderlines() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) stripUnderlinesIn(w);
        }
    }

    private static void stripUnderlinesIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                JLabel lbl = (JLabel) child;
                String t = lbl.getText();
                if (t != null && t.length() >= 7) {
                    String low = t.toLowerCase();
                    if (low.startsWith("<html") && low.contains("<u>")) {
                        lbl.setText(t.replace("<u>", "").replace("</u>", "")
                                     .replace("<U>", "").replace("</U>", ""));
                    }
                }
            }
            if (child instanceof Container) stripUnderlinesIn((Container) child);
        }
    }

    // Only the top-level menus get the smaller left margin. Menu.margin also applies to submenus,
    // whose icons have to line up with the items next to them.
    private static void flushMenuBarItems() {
        for (Window w : Window.getWindows()) {
            if (!(w instanceof javax.swing.JFrame) || !w.isShowing()) continue;
            javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
            if (mb == null) continue;
            for (int i = 0; i < mb.getMenuCount(); i++) {
                javax.swing.JMenu m = mb.getMenu(i);
                if (m == null) continue;
                java.awt.Insets mar = m.getMargin();
                if (mar == null || mar.left != 8) {
                    int t = mar == null ? 6 : mar.top, b = mar == null ? 6 : mar.bottom;
                    m.setMargin(new java.awt.Insets(t, 8, b, 12));
                }
                installMenuHoverFg(m);
            }
        }
    }
    // FlatLaf fills a hovered top-level menu with MenuBar.hoverBackground but has no matching
    // foreground key, so the label would stay light on the accent.
    private static void installMenuHoverFg(final javax.swing.JMenu m) {
        if (m.getClientProperty("jdp.menuHoverFg") != null) return;
        m.putClientProperty("jdp.menuHoverFg", Boolean.TRUE);
        // JMenu reports no rollover, so hover comes from a mouse listener and keyboard arming from the model.
        m.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { if (m.isEnabled()) m.setForeground(accentFg()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (!m.isArmed() && !m.isSelected()) m.setForeground(SIDEBAR_TEXT);
            }
        });
        m.getModel().addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                javax.swing.ButtonModel mm = m.getModel();
                Color want = (mm.isArmed() || mm.isSelected()) ? accentFg() : SIDEBAR_TEXT;
                if (!want.equals(m.getForeground())) m.setForeground(want);
            }
        });
        if (!SIDEBAR_TEXT.equals(m.getForeground())) m.setForeground(SIDEBAR_TEXT);
    }

    // The ToolBar and MenuBar defaults only reach components created later, and the LAF re-apply at
    // boot can fail, so the live menu bar and toolbar get the base colour directly.
    private static final Color CHROME_BASE = PAL_BASE;
    private static void darkenChromeBars() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (w instanceof javax.swing.JFrame) {
                javax.swing.JMenuBar mb = ((javax.swing.JFrame) w).getJMenuBar();
                if (mb != null) {
                    if (!CHROME_BASE.equals(mb.getBackground())) { mb.setBackground(CHROME_BASE); mb.setOpaque(true); }
                    // FlatMenuBarBorder draws a separator line below the menu bar. The first menu's
                    // text sits 8px inside it, so the left inset puts the text on EDGE.
                    javax.swing.border.Border mbb = mb.getBorder();
                    if (!(mbb instanceof javax.swing.border.EmptyBorder)
                            || ((javax.swing.border.EmptyBorder) mbb).getBorderInsets().left != EDGE - 8)
                        mb.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, EDGE - 8, 0, 0));
                }
            }
            darkenToolbarsIn(w);
        }
    }
    private static void darkenToolbarsIn(Container c) {
        for (Component ch : c.getComponents()) {
            if (isMainToolbar(ch.getClass()) && ch instanceof JComponent) {
                if (!CHROME_BASE.equals(ch.getBackground())) { ch.setBackground(CHROME_BASE); ((JComponent) ch).setOpaque(true); }
                Container par = ch.getParent();   // the band around the toolbar can be lighter too
                if (par instanceof JComponent && par.getBackground() != null
                        && par.getBackground().getRed() >= 0x1b && par.getBackground().getRed() <= 0x2a
                        && !CHROME_BASE.equals(par.getBackground())) {
                    par.setBackground(CHROME_BASE); ((JComponent) par).setOpaque(true);
                }
            }
            if (ch instanceof Container) darkenToolbarsIn((Container) ch);
        }
    }

    private static void recolorMainTabs() {
        // On Linux FlatLaf leaves TabbedPane.selectedForeground null, because @accentColor resolves to
        // systemColor(accent), so accentFg derives the tone from the accent instead.
        Color selFg = accentFg();
        Color norFg = UIManager.getColor("TabbedPane.foreground");
        if (norFg == null) norFg = PAL_TEXT;
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) recolorTabsIn(w, selFg, norFg);
        }
    }

    private static final String TAB_HOVER_IDX = "jdp.tabHoverIdx";

    /** The hovered tab as BasicTabbedPaneUI tracks it, which still works when a custom tab component
     *  consumes the mouse events. */
    private static int rolloverTabOf(javax.swing.JTabbedPane tp) {
        try {
            java.lang.reflect.Method m = javax.swing.plaf.basic.BasicTabbedPaneUI.class.getDeclaredMethod("getRolloverTab");
            m.setAccessible(true);
            Object r = m.invoke(tp.getUI());
            if (r instanceof Integer) return ((Integer) r).intValue();
        } catch (Throwable ignore) { }
        return -1;
    }

    /** FlatTabbedPaneUI paints one background per tab and the hover colour wins over the selected one,
     *  so the selected tab would lose its accent under the mouse. Clearing hoverColor on the UI instance
     *  leaves the accent text as the hover cue. */
    private static void killTabHoverColor(javax.swing.JTabbedPane tp) {
        try {
            Object ui = tp.getUI();
            for (Class<?> k = ui.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = k.getDeclaredField("hoverColor");
                    f.setAccessible(true);
                    if (f.get(ui) != null) { f.set(ui, null); tp.repaint(); }
                    return;
                } catch (NoSuchFieldException nsf) { }
            }
        } catch (Throwable ignore) { }
    }

    private static void recolorTabsIn(Container c, Color selFg, Color norFg) {
        for (Component child : c.getComponents()) {
            if (child instanceof javax.swing.JTabbedPane) {
                javax.swing.JTabbedPane tp = (javax.swing.JTabbedPane) child;
                // JD's TabHeader components consume mouse events, so the pane's own listener only sees
                // the gaps between tabs; the UI's rollover tab covers the rest.
                killTabHoverColor(tp);
                int rov = rolloverTabOf(tp);
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                int hover = (rov >= 0) ? rov : ((hv instanceof Integer) ? (Integer) hv : -1);
                applyTabForegrounds(tp, selFg, norFg, hover);
                installTabHoverListener(tp, selFg, norFg);
            }
            if (child instanceof Container) recolorTabsIn((Container) child, selFg, norFg);
        }
    }

    /** Dark text and icon on the selected tab, accent on the hovered one, light on the rest. JD's custom
     *  tab components bypass FlatLaf's selectedForeground, so the foreground is set per tab. */
    private static void applyTabForegrounds(javax.swing.JTabbedPane tp, Color selFg, Color norFg, int hover) {
        int sel = tp.getSelectedIndex();
        for (int i = 0; i < tp.getTabCount(); i++) {
            boolean isSel = (i == sel);
            boolean isHover = (hover >= 0 && i == hover && !isSel);
            Color want = new Color((isSel ? selFg : (isHover ? accentColor() : norFg)).getRGB());
            if (!want.equals(tp.getForegroundAt(i))) tp.setForegroundAt(i, want);
            // Unselected tabs get a grey tile like the sidebar; the selected tab paints
            // selectedBackground and ignores this.
            Color tabBg = isSel ? accentColor() : TILE_GREY;
            if (!tabBg.equals(tp.getBackgroundAt(i))) tp.setBackgroundAt(i, tabBg);
            Color iconTone = isSel ? accentFg() : (isHover ? accentColor() : SIDEBAR_TEXT);
            javax.swing.Icon slot = tp.getIconAt(i);
            if (slot != null) {
                String pk = "jdp.tabIcOrig." + i;
                javax.swing.Icon o = (javax.swing.Icon) tp.getClientProperty(pk);
                if (o == null && iconKey(slot) != null) { o = slot; tp.putClientProperty(pk, o); }
                if (o != null) { javax.swing.Icon nw = tablerIcon(o, iconTone, tp); if (nw != tp.getIconAt(i)) tp.setIconAt(i, nw); }
            }
            Component tc = tp.getTabComponentAt(i);
            if (tc != null) { setLabelFg(tc, want); tablerTabIcons(tc, iconTone, isSel); installTabCompHover(tp, tc, selFg, norFg); }
        }
    }

    /** JD swaps tab icons back between ticks, so the label gets a TabIcon that picks its tone at paint
     *  time from the label's foreground. {@code tone} is unused. */
    private static void tablerTabIcons(Component c, Color tone, boolean sel) {
        if (c instanceof AbstractButton) installTabCloseButton((AbstractButton) c, sel);
        else if (c instanceof javax.swing.JLabel) installTabIconWrap((javax.swing.JLabel) c);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) tablerTabIcons(ch, tone, sel);
    }

    private static void installTabIconWrap(final javax.swing.JLabel l) {
        javax.swing.Icon cur = l.getIcon();
        if (cur instanceof TabIcon) { ensureTabIconListener(l); return; }
        if (cur == null) return;
        javax.swing.Icon base = cur;
        String tk = iconKey(cur);
        if (tk != null) { javax.swing.Icon tb = tablerBase(tk, cur.getIconWidth(), cur.getIconHeight()); if (tb != null) base = tb; }
        javax.swing.Icon light  = tintSolid(base, SIDEBAR_TEXT);
        javax.swing.Icon dark   = tintSolid(base, accentFg());
        javax.swing.Icon accent = tintSolid(base, accentColor());
        if (light == base || light == null) return;
        l.putClientProperty("jdp.tabOrig", cur);
        l.setIcon(new TabIcon(light, dark, accent));
        ensureTabIconListener(l);
    }

    /** The close button of a ClosableTabHeader ignores setIcon, setOpaque and setContentAreaFilled, so
     *  CloseUI takes over its painting. */
    private static void installTabCloseButton(final AbstractButton b, boolean sel) {
        Boolean now = Boolean.valueOf(sel);
        boolean changed = !now.equals(b.getClientProperty("jdp.closeSel"));
        b.putClientProperty("jdp.closeSel", now);
        if (!(b.getUI() instanceof CloseUI)) { b.setUI(new CloseUI()); b.setBorder(null); changed = true; }
        if (changed) b.repaint();
    }

    /** Fills the button with the tab's own pill colour and centres a mono close glyph. The tab header
     *  reports the base colour, not the pill painted under it. The jdp.closeSel property tells it
     *  whether it sits on the selected tab. */
    private static final class CloseUI extends javax.swing.plaf.basic.BasicButtonUI {
        private boolean sel(JComponent c) { return Boolean.TRUE.equals(c.getClientProperty("jdp.closeSel")); }
        @Override public void update(Graphics g, JComponent c) {
            g.setColor(sel(c) ? accentColor() : TILE_GREY);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }
        @Override public void paint(Graphics g, JComponent c) {
            javax.swing.Icon x = tintSolid(tablerBase("close", 16, 16), sel(c) ? accentFg() : SIDEBAR_TEXT);
            if (x == null) return;
            int ix = (c.getWidth() - x.getIconWidth()) / 2, iy = (c.getHeight() - x.getIconHeight()) / 2;
            x.paintIcon(c, g, ix, iy);
        }
    }

    /** The overview panel's settings and close buttons derive a dark glyph on every paint and ignore
     *  setIcon, so like the tab close button they get a UI that paints their own glyph tinted. */
    private static void hlInstallOverviewCornerButton(final AbstractButton b) {
        if (!(b.getUI() instanceof HlOverviewCornerUI)) {
            try { b.setUI(new HlOverviewCornerUI()); b.setBorder(null); } catch (Throwable ignore) { }
        }
    }
    private static final class HlOverviewCornerUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void update(Graphics g, JComponent c) {
            Color bg = (c.getParent() != null && c.getParent().getBackground() != null)
                    ? c.getParent().getBackground() : PAL_BASE;
            g.setColor(bg);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            paint(g, c);
        }
        @Override public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.Icon ic = b.getIcon();
            if (ic == null) return;
            boolean hot = b.getModel().isRollover() || b.getModel().isArmed();
            javax.swing.Icon m = cornerGlyphTint(b, ic, hot);
            if (m == null) return;
            int ix = (c.getWidth() - m.getIconWidth()) / 2, iy = (c.getHeight() - m.getIconHeight()) / 2;
            m.paintIcon(c, g, ix, iy);
        }
    }
    /** Caches the tinted glyph per hover state and source icon. */
    private static javax.swing.Icon cornerGlyphTint(AbstractButton b, javax.swing.Icon ic, boolean hot) {
        String pk = hot ? "jdp.ovcHot" : "jdp.ovcLit", bk = hot ? "jdp.ovcHotB" : "jdp.ovcLitB";
        Object cached = b.getClientProperty(pk);
        if (ic == b.getClientProperty(bk) && cached instanceof javax.swing.Icon) return (javax.swing.Icon) cached;
        // As bright as the table header gear next to it; EXPANDER_LIGHT would look grey.
        javax.swing.Icon m = tintSolid(ic, hot ? accentColor() : SIDEBAR_TEXT);
        if (m == null) m = ic;
        b.putClientProperty(bk, ic);
        b.putClientProperty(pk, m);
        return m;
    }

    private static boolean isSilentBell(AbstractButton b) {
        javax.swing.Action a = b.getAction();
        return a != null && "SilentModeToggleAction".equals(a.getClass().getSimpleName());
    }
    /** Rounded accent fill for the toolbar buttons. AppWork's ExtButtons have no FlatButtonBorder, so
     *  FlatLaf would fill them square. */
    private static final class RoundFillUI extends javax.swing.plaf.basic.BasicButtonUI {
        @Override public void update(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            javax.swing.ButtonModel m = b.getModel();
            boolean toggle = b instanceof javax.swing.JToggleButton;
            // On a toggle the fill shows the on state rather than hover, or switching it off under the
            // cursor would look like nothing happened. The silent mode bell is selected when notifications
            // are off, so its logic is inverted.
            boolean active = isSilentBell(b) ? !m.isSelected() : m.isSelected();
            boolean hot = active || m.isPressed() || (!toggle && m.isRollover());
            // ExtJToggleButton paints its own selection background before ui.update, so it is covered first.
            Color bg = (c.getParent() != null && c.getParent().getBackground() != null)
                    ? c.getParent().getBackground() : PAL_BASE;
            g.setColor(bg);
            g.fillRect(0, 0, c.getWidth(), c.getHeight());
            if (hot) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(accentColor());
                g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), SB_BTN_ARC, SB_BTN_ARC);
                g2.dispose();
            }
            // ExtJToggleButton derives its icon from the Action's ExtMergedIcon, whose state overlay JD
            // resets faster than the tick, so mapped actions get their glyph painted here directly.
            javax.swing.Icon own = ownedToolbarGlyph(b, hot);
            if (own != null) {
                int ix = (c.getWidth() - own.getIconWidth()) / 2, iy = (c.getHeight() - own.getIconHeight()) / 2;
                own.paintIcon(c, g, ix, iy);
                return;
            }
            paint(g, c);
        }
    }

    private static final java.util.Map<String, javax.swing.Icon> OWNED_GLYPH = new java.util.concurrent.ConcurrentHashMap<String, javax.swing.Icon>();
    /** The Tabler glyph for a mapped toolbar button, dark on the accent and light otherwise, or null. */
    private static javax.swing.Icon ownedToolbarGlyph(AbstractButton b, boolean hot) {
        javax.swing.Action a = b.getAction();
        if (a == null) return null;
        String sn = a.getClass().getSimpleName();
        String key;
        if ("SilentModeToggleAction".equals(sn)) {
            // Selected means notifications are off, shown as a crossed-out bell.
            key = b.isSelected() ? "silentmode" : "bubble";
        } else {
            key = ACTION_ICON.get(sn);
        }
        if (key == null) return null;
        javax.swing.Icon cur = b.getIcon();
        int sz = (cur != null && cur.getIconHeight() > 0) ? cur.getIconHeight() : 24;
        Color tone = !b.isEnabled() ? DISABLED_TONE : (hot ? accentFg() : SIDEBAR_TEXT);
        String ck = key + "@" + sz + "@" + tone.getRGB();
        javax.swing.Icon cached = OWNED_GLYPH.get(ck);
        if (cached != null) return cached;
        javax.swing.Icon base = tablerBase(key, sz, sz);
        if (base == null) return null;
        javax.swing.Icon tinted = tintSolid(base, tone);
        OWNED_GLYPH.put(ck, tinted);
        return tinted;
    }
    private static void roundToolbarButtons() {
        for (Window w : Window.getWindows()) if (w.isShowing()) roundToolbarBtnsIn(w, false);
    }
    private static void roundToolbarBtnsIn(Container c, boolean inTb) {
        boolean tb = inTb || isMainToolbar(c.getClass());
        for (Component ch : c.getComponents()) {
            if (tb && ch instanceof AbstractButton) {
                AbstractButton b = (AbstractButton) ch;
                if (!(b.getUI() instanceof RoundFillUI)) {
                    javax.swing.border.Border ob = b.getBorder();   // keeps JD's padding across setUI
                    b.setContentAreaFilled(false);
                    b.setOpaque(false);
                    b.setUI(new RoundFillUI());
                    if (ob != null) b.setBorder(ob);
                }
            }
            if (ch instanceof Container) roundToolbarBtnsIn((Container) ch, tb);
        }
    }
    /** A silhouette of the icon in {@code tone}, keeping its alpha. Works for keyless icons too. */
    private static javax.swing.Icon tintSolid(javax.swing.Icon ic, Color tone) { return tintSolid(ic, tone, null); }
    /** JD's AbstractIcon composites paint nothing without a component, so callers pass the host in {@code c}.
     *  Returns the original icon if the result would be empty. */
    private static javax.swing.Icon tintSolid(javax.swing.Icon ic, Color tone, Component c) {
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
    private static javax.swing.Icon deBoxIcon(javax.swing.Icon ic, Component c) {
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

    private static void ensureTabIconListener(final javax.swing.JLabel l) {
        if (l.getClientProperty("jdp.tabIconL") != null) return;
        l.putClientProperty("jdp.tabIconL", Boolean.TRUE);
        l.addPropertyChangeListener("icon", new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent e) {
                if (!(l.getIcon() instanceof TabIcon)) installTabIconWrap(l);
            }
        });
    }

    /** Picks its glyph from the label's foreground at paint time; JD leaves that foreground alone. */
    private static final class TabIcon implements javax.swing.Icon {
        private final javax.swing.Icon light, dark, accent;
        TabIcon(javax.swing.Icon light, javax.swing.Icon dark, javax.swing.Icon accent) { this.light = light; this.dark = dark; this.accent = accent; }
        private javax.swing.Icon pick(Component c) {
            if (c == null) return light;
            Color fg = c.getForeground();
            if (accentFg().equals(fg)) return dark;        // selected
            if (accentColor().equals(fg)) return accent;   // hovered
            return light;
        }
        public void paintIcon(Component c, Graphics g, int x, int y) { pick(c).paintIcon(c, g, x, y); }
        public int getIconWidth() { return light.getIconWidth(); }
        public int getIconHeight() { return light.getIconHeight(); }
    }

    private static int indexOfTabComp(javax.swing.JTabbedPane tp, Component tc) {
        for (int k = 0; k < tp.getTabCount(); k++) if (tp.getTabComponentAt(k) == tc) return k;
        return -1;
    }

    /** The TabHeader components consume mouse events, so without their own listener the hover colour
     *  would only follow on the next tick. */
    private static void installTabCompHover(final javax.swing.JTabbedPane tp, final Component tc,
                                            final Color selFg, final Color norFg) {
        if (!(tc instanceof JComponent)) return;
        final JComponent jc = (JComponent) tc;
        if (jc.getClientProperty("jdp.tabCompHover") != null) return;
        jc.putClientProperty("jdp.tabCompHover", Boolean.TRUE);
        jc.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                int idx = indexOfTabComp(tp, tc);
                if (idx >= 0) { tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(idx)); applyTabForegrounds(tp, selFg, norFg, idx); }
            }
            @Override public void mouseExited(MouseEvent e) {
                if (jc.contains(e.getPoint())) return;   // moved into a child
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(-1));
                applyTabForegrounds(tp, selFg, norFg, rolloverTabOf(tp));
            }
        });
    }

    private static final String TAB_HOVER_WIRED = "jdp.tabHoverWired";
    private static void installTabHoverListener(final javax.swing.JTabbedPane tp,
                                                final Color selFg, final Color norFg) {
        if (Boolean.TRUE.equals(tp.getClientProperty(TAB_HOVER_WIRED))) return;
        tp.putClientProperty(TAB_HOVER_WIRED, Boolean.TRUE);
        MouseAdapter h = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int idx = tp.indexAtLocation(e.getX(), e.getY());
                Object prev = tp.getClientProperty(TAB_HOVER_IDX);
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(idx));   // read by the tick
                applyTabForegrounds(tp, selFg, norFg, idx);
                if (!Integer.valueOf(idx).equals(prev)) tp.repaint();
            }
            @Override public void mouseExited(MouseEvent e) {
                tp.putClientProperty(TAB_HOVER_IDX, Integer.valueOf(-1));
                applyTabForegrounds(tp, selFg, norFg, -1);
                tp.repaint();
            }
        };
        tp.addMouseMotionListener(h);
        tp.addMouseListener(h);
        // A clicked tab takes its colours right away instead of on the next tick.
        tp.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent e) {
                Object hv = tp.getClientProperty(TAB_HOVER_IDX);
                applyTabForegrounds(tp, selFg, norFg, (hv instanceof Integer) ? (Integer) hv : -1);
            }
        });
    }

    private static void setLabelFg(Component c, Color fg) {
        if (!fg.equals(c.getForeground())) c.setForeground(fg);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) setLabelFg(ch, fg);
    }

    // Settings sections as cards. A Border on each AbstractConfigPanel paints a rounded band behind
    // every section, between successive headers. A border paints after the background and before the
    // children, so nothing has to be re-parented, which would break because JD rebuilds the panel on
    // every navigation.
    private static void cardSettingsSections() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing()) cardSectionsIn(w);
        }
    }

    private static void cardSectionsIn(Container c) {
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent && isConfigPanel(child.getClass())) {
                JComponent panel = (JComponent) child;
                if (!(panel.getBorder() instanceof SectionCardBorder)) {
                    try {
                        panel.setBorder(new SectionCardBorder(panel.getBorder()));
                        panel.revalidate();
                        panel.repaint();
                        System.out.println("[jd-dialog-agent] jd-highlighter: carded settings panel "
                                + child.getClass().getSimpleName());
                    } catch (Throwable ignore) { }
                }
                hideSeparators(panel);   // the cards replace the section lines
                spreadSections(panel);
            }
            if (child instanceof Container) cardSectionsIn((Container) child);
        }
    }

    private static boolean isConfigPanel(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass())
            if (k.getName().endsWith(".AbstractConfigPanel")) return true;
        return false;
    }

    private static boolean isMainToolbar(Class<?> k) {
        for (; k != null && k != Object.class; k = k.getSuperclass())
            if (k.getName().endsWith(".MainToolBar")) return true;
        return false;
    }

    private static void hideSeparators(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JSeparator) { if (ch.isVisible()) ch.setVisible(false); }
            else if (ch instanceof Container) hideSeparators((Container) ch);
        }
    }

    // The Views popup of the download list shows its items as rounded chips instead of rows divided by
    // separators. FlatLaf still paints the accent hover on top.
    private static final String VIEWS_BADGED = "jdp.viewsBadged";

    private static void badgeViewsMenu() {
        javax.swing.MenuElement[] path =
                javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
        for (javax.swing.MenuElement me : path) {
            if (!(me instanceof JPopupMenu)) continue;
            JPopupMenu pm = (JPopupMenu) me;
            if (!isViewsPopup(pm)) continue;
            hideSeparators(pm);
            for (Component ch : pm.getComponents()) {
                if (!(ch instanceof javax.swing.JMenuItem)) continue;
                javax.swing.JMenuItem mi = (javax.swing.JMenuItem) ch;
                if (mi.getClientProperty(VIEWS_BADGED) == Boolean.TRUE) continue;
                mi.setBorder(new MenuChipBorder(mi.getBorder()));
                mi.setOpaque(false);
                mi.putClientProperty(VIEWS_BADGED, Boolean.TRUE);
            }
            pm.revalidate(); pm.repaint();
        }
    }

    // A Views popup holds only check box and radio items plus at least one separator.
    private static boolean isViewsPopup(JPopupMenu pm) {
        boolean allChecky = pm.getComponentCount() > 0;
        int seps = 0;
        for (Component ch : pm.getComponents()) {
            if (ch instanceof javax.swing.JSeparator) { seps++; continue; }
            if (!(ch instanceof javax.swing.JCheckBoxMenuItem
                    || ch instanceof javax.swing.JRadioButtonMenuItem)) allChecky = false;
        }
        return allChecky && seps >= 1;
    }

    /** Rounded chip behind a menu item. Like SectionCardBorder it paints before the text, so the
     *  selection highlight lands on top. */
    private static final class MenuChipBorder implements javax.swing.border.Border {
        private final javax.swing.border.Border original;
        private static final Color CHIP = PAL_SURFACE;
        private static final int GAP = 3, PAD_V = 2, PAD_H = 6, ARC = UI_ARC;
        MenuChipBorder(javax.swing.border.Border o) { this.original = o; }
        public boolean isBorderOpaque() { return false; }
        public java.awt.Insets getBorderInsets(Component c) {
            java.awt.Insets in = (original != null) ? original.getBorderInsets(c)
                    : new java.awt.Insets(0, 0, 0, 0);
            return new java.awt.Insets(in.top + GAP + PAD_V, in.left + GAP + PAD_H,
                    in.bottom + GAP + PAD_V, in.right + GAP + PAD_H);
        }
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CHIP);
                g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                        x + GAP, y + GAP, w - 2 * GAP, h - 2 * GAP, ARC, ARC));
            } finally { g2.dispose(); }
            if (original != null) original.paintBorder(c, g, x, y, w, h);
        }
    }

    private static final class SectionCardBorder implements javax.swing.border.Border {
        private final javax.swing.border.Border original;
        private static final Color CARD = PAL_SURFACE;
        private static final int MARGIN = 10, INNER = 12, ARC = UI_ARC;
        SectionCardBorder(javax.swing.border.Border original) { this.original = original; }

        public boolean isBorderOpaque() { return false; }

        public java.awt.Insets getBorderInsets(Component c) {
            // MARGIN outside the card plus INNER inside it
            int p = MARGIN + INNER;
            java.awt.Insets in = (original != null) ? original.getBorderInsets(c)
                    : new java.awt.Insets(0, 0, 0, 0);
            return new java.awt.Insets(in.top + p, in.left + p, in.bottom + p, in.right + p);
        }

        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (!(c instanceof Container)) return;
            Container p = (Container) c;
            List<Component> headers = new ArrayList<>();
            for (Component ch : p.getComponents())
                if (ch.isVisible() && ch.getClass().getName().endsWith(".Header")) headers.add(ch);
            if (!headers.isEmpty()) {
                headers.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(CARD);
                    int left = x + MARGIN, right = x + w - MARGIN;
                    for (int i = 0; i < headers.size(); i++) {
                        int hTop = headers.get(i).getY();
                        int hNext = (i + 1 < headers.size()) ? headers.get(i + 1).getY() : Integer.MAX_VALUE;
                        // Only this section's rows, so the gap from spreadSections shows between cards.
                        int contentBottom = hTop + headers.get(i).getHeight();
                        for (Component ch : p.getComponents()) {
                            if (!ch.isVisible()) continue;
                            int cy = ch.getY();
                            if (cy >= hTop && cy < hNext) contentBottom = Math.max(contentBottom, cy + ch.getHeight());
                        }
                        // The first card starts level with the first sidebar tile, and the last one
                        // reaches the bottom of the panel like the sidebar does.
                        int top = (i == 0) ? y + SB_BTN_GAP_V : hTop - INNER;
                        int bottom = (i == headers.size() - 1)
                                ? Math.max(contentBottom + INNER, y + h - MARGIN)
                                : (contentBottom + INNER);
                        if (bottom - top > 6)
                            g2.fill(new java.awt.geom.RoundRectangle2D.Float(
                                    left, top, right - left, bottom - top, ARC, ARC));
                    }
                } finally { g2.dispose(); }
            }
            if (original != null) original.paintBorder(c, g, x, y, w, h);
        }
    }

    // A gap above every section but the first, set through the panel's MigLayout constraints.
    private static void spreadSections(JComponent panel) {
        LayoutManager lm = panel.getLayout();
        if (lm == null || !lm.getClass().getName().contains("MigLayout")) return;
        try {
            Method gc = lm.getClass().getMethod("getComponentConstraints", Component.class);
            Method sc = lm.getClass().getMethod("setComponentConstraints", Component.class, Object.class);
            boolean first = true, changed = false;
            for (Component ch : panel.getComponents()) {
                if (!ch.getClass().getName().endsWith(".Header")) continue;
                if (first) { first = false; continue; }
                Object cur = gc.invoke(lm, ch);
                String cc = (cur == null) ? "" : cur.toString();
                if (cc.contains("gaptop")) continue;
                sc.invoke(lm, ch, (cc.isEmpty() ? "" : cc + ",") + "gaptop 34");   // = MARGIN + 2*INNER
                changed = true;
            }
            if (changed) { panel.revalidate(); panel.repaint(); }
        } catch (Throwable ignore) { }
    }

    // Dialogs use the card surface, with fields and buttons styled like the settings.
    private static final Color DIALOG_BG = PAL_SURFACE;
    // The updater window sits on top of the download list card, so it needs a lighter shade than the card.
    private static final Color UPDATER_BG = new Color(0x30, 0x30, 0x30);

    private static void recolorDialogs() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof javax.swing.RootPaneContainer)) continue;
            // The updater window is undecorated, transparent and not a Dialog, so it is matched by title.
            boolean isUpdater = false;
            try {
                String t = (w instanceof Frame) ? ((Frame) w).getTitle()
                        : (w instanceof Dialog) ? ((Dialog) w).getTitle() : w.getName();
                isUpdater = t != null && t.toLowerCase().contains("updat");
            } catch (Throwable ig) { }
            if (!(w instanceof Dialog) && !isUpdater) continue;
            try {
                javax.swing.RootPaneContainer rpc = (javax.swing.RootPaneContainer) w;
                // The grey frame is a border on the content pane and root pane themselves, which the
                // walk over their children never reaches.
                Color bg = isUpdater ? UPDATER_BG : DIALOG_BG;
                Container cp = rpc.getContentPane();
                if (cp instanceof JComponent) {
                    stripFramingBorder((JComponent) cp);
                    if (!bg.equals(cp.getBackground())) cp.setBackground(bg);
                    if (!((JComponent) cp).isOpaque()) ((JComponent) cp).setOpaque(true);
                }
                javax.swing.JRootPane rp = rpc.getRootPane();
                if (rp != null) {
                    stripFramingBorder(rp);
                    if (rp.getLayeredPane() != null) stripFramingBorder(rp.getLayeredPane());
                    if (isUpdater && rp instanceof JComponent) {
                        if (!bg.equals(rp.getBackground())) rp.setBackground(bg);
                        rp.setOpaque(true);
                        // JD sizes the window tightly to its content and reverts any growth, so a larger
                        // border would squeeze it. A little horizontal room is safe.
                        if (!(rp.getBorder() instanceof javax.swing.border.EmptyBorder))
                            rp.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 8));
                    }
                }
                styleDialogContent(cp, bg);
                if (isUpdater) { roundUpdaterBar(cp); fitUpdaterWindow(w); }
                else fitDialog(w);
            } catch (Throwable ignore) { }
        }
    }

    // AppWork packs a dialog before the theme's padding applies, which pushes the button row of the
    // donation dialog below the window's edge. The window grows to its content and stays centred.
    private static void fitDialog(Window w) {
        Dimension pref = w.getPreferredSize(), size = w.getSize();
        if (pref.width <= size.width && pref.height <= size.height) return;
        java.awt.Rectangle screen = w.getGraphicsConfiguration().getBounds();
        int nw = Math.min(Math.max(pref.width, size.width), screen.width);
        int nh = Math.min(Math.max(pref.height, size.height), screen.height);
        w.setBounds(w.getX() - (nw - size.width) / 2, w.getY() - (nh - size.height) / 2, nw, nh);
        w.validate();
    }

    /** Gives AppWork dialogs the theme's surface, field and button fills and removes their framing
     *  borders. Runs on every tick so content built later is caught; icons are handled by monoChromeIcons. */
    private static void styleDialogContent(Container c, Color bg) {
        for (Component ch : c.getComponents()) {
            try {
                if (ch instanceof javax.swing.JPanel || ch instanceof javax.swing.JOptionPane
                        || ch instanceof javax.swing.Box || ch instanceof javax.swing.JScrollPane) {
                    if (!bg.equals(ch.getBackground())) ch.setBackground(bg);
                    stripFramingBorder((JComponent) ch);
                } else if (ch instanceof javax.swing.text.JTextComponent
                        && !((javax.swing.text.JTextComponent) ch).isEditable()) {
                    // A read-only text component is the dialog message, not an input.
                    if (((JComponent) ch).isOpaque()) ((JComponent) ch).setOpaque(false);
                    if (!bg.equals(ch.getBackground())) ch.setBackground(bg);
                    stripFramingBorder((JComponent) ch);
                } else if (ch instanceof javax.swing.text.JTextComponent || ch instanceof javax.swing.JComboBox
                        || ch instanceof javax.swing.JSpinner) {
                    if (!FIELD_BG.equals(ch.getBackground())) {
                        ch.setBackground(FIELD_BG);
                        if (ch instanceof JComponent) ((JComponent) ch).setOpaque(true);
                    }
                    if (ch instanceof javax.swing.JComboBox) styleComboPopup((javax.swing.JComboBox<?>) ch);
                } else if (ch instanceof javax.swing.AbstractButton && !isCheckLike(ch)) {
                    javax.swing.AbstractButton ab = (javax.swing.AbstractButton) ch;
                    boolean hot = ab.getModel().isRollover() || ab.getModel().isPressed();
                    if (!hot && !BTN_CFG_BG.equals(ab.getBackground())) ab.setBackground(BTN_CFG_BG);
                    // The two hover listeners share the jdp.savedFg key, so the first hover can leave a dark
                    // label on the dark button. Resetting it at rest keeps the label readable.
                    if (!hot && !SIDEBAR_TEXT.equals(ab.getForeground())) ab.setForeground(SIDEBAR_TEXT);
                    installBtnHoverFg(ab);
                    installBtnHoverBg(ab);
                    // This FlatLaf build ignores a per-component arc, so the updater buttons are rounded by a
                    // corner mask. The original border stays inside it to keep the padding.
                    if (bg == UPDATER_BG && ab.getClientProperty("jdp.updBtnMask") == null) {
                        javax.swing.border.Border ob = ab.getBorder();
                        ab.setBorder(ob == null ? new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS)
                            : javax.swing.BorderFactory.createCompoundBorder(new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS), ob));
                        ab.putClientProperty("jdp.updBtnMask", Boolean.TRUE);
                    }
                } else if (ch instanceof javax.swing.JLabel && ((javax.swing.JLabel) ch).getIcon() != null) {
                    javax.swing.JLabel l = (javax.swing.JLabel) ch;
                    if (l.getClass().getName().endsWith(".ProgressLogo")) {
                        tameProgressLogo(l);
                    } else {
                        javax.swing.Icon cur = l.getIcon();
                        if (l.getClientProperty("jdp.monoLbl") != cur) {
                            // Small icons are chrome glyphs; a large image is a logo and only loses its box.
                            javax.swing.Icon m;
                            if (cur.getIconWidth() >= 40 || cur.getIconHeight() >= 40) m = deBoxIcon(cur, l);
                            else m = tablerIcon(cur, SIDEBAR_TEXT, l);
                            if (m != cur) { l.setIcon(m); l.putClientProperty("jdp.monoLbl", m); }
                        }
                    }
                } else if (ch instanceof JComponent) {
                    stripFramingBorder((JComponent) ch);
                }
            } catch (Throwable ignore) { }
            if (ch instanceof Container) styleDialogContent((Container) ch, bg);
        }
    }

    /** The updater's ProgressLogo paints its globe from internal Image and Icon fields and ignores the theme's
     *  updaterIcon SVGs, so those fields are replaced with the JD Highlighter logo. */
    private static void tameProgressLogo(javax.swing.JLabel l) {
        try {
            if (Boolean.TRUE.equals(l.getClientProperty("jdp.updLogo"))) return;
            java.awt.image.BufferedImage logo = logoImage();
            if (logo == null) return;
            int sz = Math.max(l.getWidth(), l.getHeight());
            if (sz < 32) sz = 64; if (sz > 96) sz = 96;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(sz, sz, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(logo, 0, 0, sz, sz, null);
            g.dispose();
            javax.swing.ImageIcon icon = new javax.swing.ImageIcon(img);
            for (Class<?> k = l.getClass(); k != null && k != javax.swing.JLabel.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    try {
                        Class<?> ft = f.getType();
                        if (java.awt.Image.class.isAssignableFrom(ft)) { f.setAccessible(true); f.set(l, img); }
                        else if (javax.swing.Icon.class.isAssignableFrom(ft)) { f.setAccessible(true); f.set(l, icon); }
                    } catch (Throwable ig) { }
                }
            }
            l.setIcon(icon);
            l.setOpaque(false);
            if (!UPDATER_BG.equals(l.getBackground())) l.setBackground(UPDATER_BG);
            l.putClientProperty("jdp.updLogo", Boolean.TRUE);
            l.repaint();
        } catch (Throwable ig) { }
    }

    /** The JD Highlighter logo, decoded once from LOGO_PNG_B64. */
    private static java.awt.image.BufferedImage LOGO_IMG;
    private static boolean LOGO_TRIED;
    private static java.awt.image.BufferedImage logoImage() {
        if (LOGO_TRIED) return LOGO_IMG;
        LOGO_TRIED = true;
        try {
            byte[] png = java.util.Base64.getDecoder().decode(LOGO_PNG_B64);
            LOGO_IMG = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        } catch (Throwable ig) { LOGO_IMG = null; }
        return LOGO_IMG;
    }
    // jd-highlighter/.github/assets/icon.svg as a 128px PNG, embedded so the agent needs no SVG renderer.
    // Regenerate it when icon.svg changes.
    private static final String LOGO_PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAX30lEQVR4Xu2dCXQUVdbHO3vSWUCWTgxBMWwConhAhkVQUUFBBQRZZBXZRGWXfRNUwAV1RlC+79MZdz931DkO6uh8gqAgKquKgLIoAgYQGFAE3nf/1amk+r5X1VXprk4nU/ec30lOulJV/f633rvvvqV8Ps8888wzzzzzzDPPPPPMM88888wzzzzzzLMKbTk5OdWITkQfYsy0CRn3P7og9fX5s9OXTx3vfww8MC995pSx6f1Gj8ipz//fs4pjmSTwQOK57Ozs9URRZmbmKUJw2rfJEH9dnCx+2+sT4mAoh39IEFtWJ576cFny0aWPpH09e5J/6ZRx6YX8Yp7FgeXn59euWbPmoBo1arxBgh/nQoej7nl+sfThFHHqgOwInE2rEk8//mDa7v69Mx8666yzqvB78SxGVqVKlbMCgcBI4hNCGCFnEFWrVhXkDJLYVrRumSHW/DNREt2MbesSxaPzUw/XLcx5hG4pm9+jZy4YiduZRH6V+I0Lr6J69eqCmgNJbDOqVMkUD9+XIs4UyYKbsWtjgujSKeMMXWdzVlZWD37PnkVuiST8TSToV1xgu6BWoCpbkECS6Cp635guju2RxTYDzcfsyana/1LNs5dqoAH8S3jm3BJJvIG5ublfc0HLChzBbq3QoX2GFgxysa146rFkOnfw/+ka28npLuVfyjMbRgFdCxJrDRcwmsARwsUJbf6UIQ5858wJXn06mc4b/H/UOOQEKwoKCqrx7+iZwoqDuyXEKS6YW1SrVs2yaUB38d8/ykJb8bclyXTO0nOQo50kh5tOXzGBf2fPio2q+utJkH1coFhANY5lbdCnR7qtbqKRedODMYGR/Pzs/XdP8QJFbikkwkPEGS6MGzRv3lxcc8014uabbxZjxowRc+fO1X7Py8uzdILJY1Ilka04/YtP9O2ZHnIO1AobVlAN8VjKZ7wQ/iPt7LPPPpdE+ZSL5BYdOnQQ999/v5K77rpLNGrUyDJAfOuFJEloK37dmSCaNPKHnOOjtxLFppU+sfyVpKNPLvwPzh9QkNeeqv2DXCS3OP/888W9994rCW8En8NJzGKC2gV+8eMWZ0HhZx8kavkF/RwLZqdoDvDNp8SapN8fnJtzCS+bSm8kSHfiBBfJTcaNGycJrmLGjBlaTGDmBJ07pmvVOxfaiqnjSuOByy5N1xwA/LjJJ3ZuSDwz/o7M/ryMKq3RUz8sEMMoX+f666+XxFYxfvx47Xh0E7n4Ok8sSpFEtuLYngTRsH6wKUBtsOmToAOAE9TD2Lc1QUy4I2MSL6tKZ1Swk7gwsQJBnp1aYMSIESX/g+whFx/kn+0X+7c6awqWPZ9U8v8IBHUH+P7L4OdHdiWIyWMzFvIyqzRW/ORLwtDfRZMmTcSFF14ofRZtGjZsKO677z5JdCPz588XF198ccn/mAWFwwenSSKH46ZuwV7Bqn8klDgAOLIr+Pmh7xPEbUPSxvOyq/AWCLb5WrXfoEED0a5dO9G7d28xevTokMBs7Nixom3btppTcPGiRadOnSTROXfffbc455xztOPN4gF06Va/56xXsOPLBFG1arA3YXSA7etKj9mzOUH0753Zj5dhhTVE+1SQJ84991xx5513SoWtYurUqaJjx46CuomSgJEC57K6jwULFmjXNv4Phpe5A4B2bTIcjRyCO4aliWceD3UAcHR36THfrEk80693RitelhXOqN2tY+zqoR1G4SL5wgtexYQJE7T/4SJGChJA/FoAzUPr1q2l4zGIZJYkevtFZ7UAho+XPJAsOYAeC+isfDfpj+bNfRV60gkyfMokD5oAXvgcOEnjxo1LYgT0z/v06aMFckja4CkeOHCg6NWrl7jhhhtEq1atbDUddevWFQsXLpSuh2r/oosuko7XwZgBFx9gIonTWgABIXcAwMccFj+Qsp8XaoWxQDC9KxUkuO666yQBOOiOQexwQRscBV28WrVqSddRAQeYOXNmyDmmTZumBYj8WI5ZLfDm88mSyFag28fFBzvXhx6HfMP429Ne5WUb90ZP4g0Bi9x+//79JSGdcs8994ju3buL2rVrS+cPBwJR3QngaHXq1JGOUYGAkIsPWjbPcJwcgtjcAQCfpPrLtgQxbGBGd17GcWvFQ7qWo3pWQVg4UCPcdNNNAkElP68T4AQDBgywXXPomHULncYCx3bL4oOfNsvHzp2aepyKNpWXdVxaIDieLxWczuWXX65sg+0AwQoLC6VzxhKzWqBjh3RJuHBs+1x2gK9XUdXPYopZk1LheM/yso47w0weKqTTvNB0LrnkEi3RwoW1Q7du3aTz6aDPjiHdzp07i/z8fOnzaGNWC6z7yP6sYnBwh+wA4NAO2QHo/Gf8fn8LXubxZJi8aTqNq2nTplq7zYW1g5X4SBzNmjWr5Ng5c+a47ghmPQKn2UH0HjAqyB1gxxehx+kTTokdvNDjxqhgBvKC0qlXr16ISE5AYkaVEEJ+YOjQodLxOsacvhuoegTVq2WKou3Oxgj2fSM7APjt59JjZkwsHU2k6w7mZR8Plmg2exfV8+TJkyWBnIA8AD9vuK4kHMfNeMFsoOjRBc5GCn/fK4sP9m4pPWbcqFIHyMrKQm4gvuYWFs/blwoJ1TDy+1wcpyBwNJ4XwtppTuAk/J6iBbKDqjGC5s0yJJHDgbEA7gDfrA42Efh8xC1pIdegGCSu5g8kUIF8yQsIWbnhw4dLopSFnj17hpx78ODB0jEqEBjy+4om1OWVHAA4WWIGirbJDgDQVcTn/XuFOgA53m4uQrlZ8XItqXCQpOGClBUkazCZs6CgQBuqtepG4rNhw4ZpQSe/p2hjNmlk9AhnweAf+31is2GSiM5Pxc1Aty6hk0sBxQLxsegkEFyrF1IwzZo109pgLk40MKv6cT3UDJjYye/HTVTBYK18v/jdEMTZYedXsgOgh4DP2rbKkK5B1/2IaxFzw4qXAFuoidTs9OnTJYHcAsIjtYzMHhcnFpgFg++85CwzePgH2QHAv/f4RIN6oTOLix3gANcj5hYILtEOKZBBgwZJIrlJ3759XRkytguCQS4OGNjHWTOA7N+WVbID7N3sIyeTz1+1alUk3HK5JjG1AFuf3759e0mgWIDxfeQauDixQtUMBGpmSsO74VANEKE3wM9d7AAItMdyTWJm2JkjYBjxwzCr3Yke0QTTuDEnIBZBnxlmzQDG/bnIVqhSwxtWqh0A2Ui69iquS8wM27IYCwGRNxfHLaZMmSJuvPHGmEwktYNZMzCkv7NmAL0B7gCrlydI5wUYlKJr/0GOkMO1iYnRxf9mLISypnrtAtG7du2qrfDhAsQDqmbg7DznvQGeFFr2fKJ0XgCnw3XJEbpwbWJidAO79C+PyN+qbx4pEydOLJmlG6+YNQMfvuWsGdj/bagD/GVhsnROZCAN136Ia+O6kfj1jF8ec+m4aNECT36kkz9igdk8gbtGO1tVfOKnUAcYPig0C6hwgC+4Pq4bXbS/8ctbrbqNBAR4CC55YccrqrGBCxo7Hxv41jBE3LihnATCfATDdU8XFBRkcI1cNaoB5hm/OHL1XLxImT17drkld8qK2djAt2udjQ3s2RQUH+lhfi6A5oZd+0KukatG/c+XjTeAsXcuYCRgBDFegz0rzCaKLH7A2RAxZgTBAda8r+4BFHcBS8BoLNfIVaOLrjfegJ1Fl3ZAT4IP/VYkzLqDPW5wNl/w5L6gA7z8ZOmCUiN6D0CHHsgZXCM3Ddu3HTfegNksX1TjaMcxIQTH8Pn4Opgn2KNHD8czdOMNMwcY0DvN8R5DW9f4xNiR8j5DLADUid2EUfK2AL+B22+/PURQRO78GKDapQPNR0Vr680wawLeey1RHPlBFtmK3Rt9olFDeRAIcQa/LmnyL66Ta0YXrMtvgMcASM3yY3SMPQbUDPzzioxqtnDNGpnasK5xipcdftqibv8xBsCvS6zjOrlmVM014zdw6623hjgAJm/wY4wMGTJEOw6xA/+somJW/c+cGNwTyLgE3A5YIaTvOmrDAbZynVwz+qLt+A0Yp2hhQCjc8Kw+Z2DUqFHSZxUVs0zg2g+C6wDRpXO6fEw1EYTlAHT2cp1cM9UUMKzY0R3glltu4TenBNlDu8dWBFRjAdgizjjGb9wDwA7G2cA6JkHgMa6TaxYI7vYRcgOYlKE7AJZw88/NqCzBn1ka+Km/hO4DsO9bWWQrXvwfeRwAFI8EGvmD6+Sa0cU68gLAYk3dATBFK16GaWOFKvjDbmBfrw7N6//ANoEIx44v1SOBmIzK7uEw18k1o4u15gWANh21gD4RFNG9ajVPZcTs6Z8xIRj8GUFzwEW2AusC6pwrdwUVgWDspolTn/MCXgg6ePIxdAsnQK3AP6+MqJ5+sH6FehMIjPZxoa3o1V2eEs4DQYrLtnCdXLPi/X2lgtBBDwArcpDdw/Rw/nllwuzp79o5XTm7BxzcLotsxfxZKdL5FYFg7DafxtuyeEGoQMIHGUE3V+mWN2ZP/6fvB0f/kM7lDoDtYbnIVrz7ino8gAWCy7lOrhpd8AAvDBVwAuwHyP9eGTBbFdT9utKBH4jNHeC7tbLIVvz8rTojyEYEl3CNXDW64EpeIGZcccUVYTODFRFVvx+bRxo3idCHdY2UJSFUv64cCBrnBFBcNoZr5KrRBZ/kBWIFHKAyNQVmWb9BfUNnAWONP3cAoC/6tIu+xawR46AQBYHXcI1ctUAZNn1WpYcx3Qs9h5YtW2oLP/nn8Qhy/qqpX9gcYucGeXMI7PnDHeDAd7LIVsydJmcEUQPp90QP5HlcI1eNLngdLxi7QHDMETAOHoF58+Zp8UK8ZwfNpn1hHx8uHPhesehz9wb5OCtef1adESy+J6SBE7lGrlrxVnBl2vMfkz6sVg5jejn2FWjRooX0v+WNWeB3fgO/9k4ALhz4WbEFDHoH/Dgrtn6uzggW9wRi2wPQjWqBz3kB2QXr/bnwKrAl7JVXXhk3WUVV4Aes9gj81WTVr5NAELOJalSXr4tYhJqkyVybmBgVyP28gOzSr18/SWwrsI8vloK5uedPOMyqfrxmlgtmRJ/fx3EaCF7aWh4axj1RbNWSaxMTI8+7lheSXTAPgItsBzQdGEKO9WCTWdWPN4dgf38uFkcVCP6yTT7OiqED5QUiVCNhFDCJaxMTIwfICrDJoXbA3kHRWEWMqePYdVzVu4gmZjN9wEtP2tskescXsgPsdBgIPjhPTgmX+yYRVEAv8AILB7aA52JGAmYaY+zBjW4kxDdr97FxExfJDOz1wx3g47fD1xxGEGfwe6AmYCnXJKZWlmbgqquukkSMBldffbV0rUgxa/eRmXOyISRf97+Z+FOLdHHcwcjgd+tCewJZWVlnqPzzuCaxtiQqqL284KzASx64eNEAW9Pwa0WCWbYP7/z59H3zqF8FdgoxOsBzTwSfZid7C2N0EdfW74Nqpu1cjHKxgMWLIVRghJCLFw3M1iKUBbP5/WDJg86WeQF043TxN67wibzcYG7/6cftxRA6zZqW9gSodoqP9wxSNVQ/YDMphGXebu0jgPNiDwHEAugyIteA5sbpGAQiflWqF/BcvxP0TaFHDy9N6+KNovw4K3p2DY4J0P39RkWfzLUoNyMneJEXpAq8kIkLF03QvCBnYPwbehxYvWxnmblZdw/gDWHH9sii2AUvhfrwjdChXWwAyY+zAm8wx//l5OQ8zjUoV6OuXdOAxStidJAGHjlypCRcLEAOAQtYzGYpmc3uARc09mvv+eGCOGHDigRRrzB0WBcpZH6cFUsfTsHTf5KKPI1rUO5GhfgmL1QV6Lcb1xGUB2geunTpos1VwEgkxh2wzZyq7ccbwxGBczGcgFU+bVrKmTzMHzi6275jvfd6Ep7+Z3jZx4XRE9Q8YPG2EA42fOLClAd4YxgcEruNAvyOHgUyjsOH9ROff+SsnVYxfYI8nKuz9kP7zrXti8QzVNRZvOzjxkjYpVxoK/AElvUVMtEAg0268JxhQ/uI9StzJBGc8o9Xk7QnnQuv89xS+z2Bk/t9qP7j14r3DrY1X1AnkpdIlRVcD/sVcNF1hg/rIzatjlx8TBApqCVP5zIyZ4rDGuaIrwYv97gy6hEM4SKHA+0xF8ktsNs4NrXkouuMHNFHbP40Sy54h2B/wMvayu0+5+aeDruWh32mL44SB3z5dEw7UeQbQj/vJV6i39fSz3f4sW4aXiCxgoscDrxfwGqiSDRAsghdRS66zl0TelI766xrZsbIIfLonQq8dJL/ryVFvnH081riDnHIt4h+LiM2ESekY3UO+WL74kksHsk1vDTaLtjvNxqvmOHgqb/tttskwY3Mn9dZFG13luI144G58sidGZhL6GRyiGOKfFuFiPGUMVhumNfHmoHhYqwn4AmdsoAaBV0+Y5Sv4r8XtxUn99nvjlnxxnPJlkGfih/WR+faJozk2sTMSNBFXGC7IK1rXHDqBAR5kyZN0rpyXGwjQ2/tK957s5AXWJn5+O9JyqlbOrkBv2jfJkOMujVN/HlhitZD2L3JVfH3i92+2G4iyQyvkLe9iEQFmgXVLGIV6FKia4cdS7jYnHvmXC92bXQYgFnw2QeJIlAzGPGXg9BqDvlmc0FibugaYvUqF9YJeD08F9sI3haKbp1VgKczeHB/8frzTbXhVanAysj6FYlUY/nFXxcnl4/Qao6Lo76aXI9yMbxcoqZhd3GnIE1rzBXgd+xBiIARG05xkc1YtLCT2PGVw6g7DMjkIV2MJx/bun2zxn5mz2Viu14wnOXl5TUmMX/h4toBU7OwFyH2HsBgElK1XFwrZk7rLr74uCYvoIj5v3eSSqp9HSR+Vi2PTm8iAk6LX331uQblbjVq1Li8WrVqv2LQRQVm4WD3C4DpWFh+rY/No2fAhQ3HlEk9xQdvFTrepdMOmKOHLhwP9AD+vux5+yneqFPke52XfdxYdnb2vbzA7BKuO6ezaGFHsfajvJJXr0abR+aniOxs+f6M4PMnFjmfPRQVinxteLnHlVEBjSXO8EILB2b3mFX/o27rJZ57spXYudHZGLsTkN7l7/ENB9787ZYjmvAJL++4NKoJulMBneQFFg6sBdBrgonju4un/6u1+GpFjahG9Sp2bUwQHdqHz+2rwMujnL47qMzEOu0biVEbX5/a+J95ganAnrvYOXP87ani/TcLxI9bopOzt8NbLyRpr4Pl96SDOAVxi9lcQtClU7o4ssvlLmJ5pX0jMQoMqTLIfhdbqwwfnKaBavOhe1LEs08kay9d+nGLywVnAuYAqnbrNIKFI/p+Pfhp5QStLsnAli+n+XWiSPmlfSO15s2yRj31WPIpVwdGHPDPZUnaVq9cRC4+uqjGLiucwGQ10RmqJd7Yt9VXSOffwq8XBco97Rux+f3+/G5dMtZt/KT8kilY8WNnKFexWWNI3sLoBPT7HurWlozji8O+s+haH/NrR0Q8pH2jZXl5/mvnTEk96mT5VaQgSHt0QYq24peLbQRVvGKrViUU4xwhZvHvBxPf+9JJtJf5fZSR+En7RtFS6p3nnzZrUuqJSKdkW4Eu2mvPJGuvd+Nic1RVvgmbiH6+MEu3EbBR4PYwv6cyEF9p3yhb6jkF2ZMnjUk9jq6Y4suXCWQH//epZNG8WXjh8dQrXtWmYh2BblgC/xJWRk4wXiB9q7hPG8Rn2tcFS8zPz+ozoHf6Dgjn9NXsOuiKYYHFhReEFx7YeOqLiCW5ubmt+A07MXKCXnR/v/H7DUs8p33dsvT09MLatTIfGjU0/eDy15JsJVk2rEwUY0amSYM3Vlg89b8Sr+Bpb9KkSSq/v7IaxQSX0b0e5PduSbynfd02ekIbnFOQNatn14yv501LPYXVM3rCZceXCWLhnBRt4iUX1wpE+OypP0Ysp79NLt6Tx7VkCwnahO59pyS0moqR9o2hJcMhqlfPanTniKye9Pvb1H7vpZ/HuMgq6LhDFOGvJrEXY/tVbIBBPwv5Rdy24und6xWCh1KR0r5xYsnUfNQB5BiX6/j9/hYkfnV+cHka1QQ5xAeS6DoVMe3rmTMjgVNI7Gcl8YNU3LSvZ/aNnCCBxJ7PxK/4aV/PnBmJPoo4pTlAZUr7embfSPiu5AC/VMa0r2c2jcQP8L955plnnnnmmWeeeeaZZ5555plnnnnmmWee2bP/BwAqlPS5flauAAAAAElFTkSuQmCC";

    /** Rounds the ends of the updater's progress bar. Its UI has an arc field that this FlatLaf build
     *  ignores, and ProgressBar.arc has to stay as it is for the download rows, so the corners are masked. */
    private static void roundUpdaterBar(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof javax.swing.JProgressBar) {
                javax.swing.JProgressBar pb = (javax.swing.JProgressBar) ch;
                if (!Boolean.TRUE.equals(pb.getClientProperty("jdp.updBar"))) {
                    pb.setBorder(new RoundCornerMaskBorder(UPDATER_BG, UI_RADIUS));
                    pb.putClientProperty("jdp.updBar", Boolean.TRUE);
                    pb.repaint();
                }
            }
            if (ch instanceof Container) roundUpdaterBar((Container) ch);
        }
    }

    /** A zero-inset border that paints a component's corners in the colour behind it, so a square component
     *  looks rounded on a uniform surface. */
    static final class RoundCornerMaskBorder implements javax.swing.border.Border {
        private final Color mask; private final int radius;   // 0 or less gives a pill
        RoundCornerMaskBorder(Color mask, int radius) { this.mask = mask; this.radius = radius; }
        public java.awt.Insets getBorderInsets(Component c) { return new java.awt.Insets(0, 0, 0, 0); }
        public boolean isBorderOpaque() { return false; }
        public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
            if (w <= 0 || h <= 0) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int r = radius > 0 ? Math.min(radius, Math.min(w, h) / 2) : Math.min(w, h) / 2;
            java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.Rectangle(x, y, w, h));
            a.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(x, y, w, h, 2f * r, 2f * r)));
            g.setColor(mask);
            g.fill(a);
            g.dispose();
        }
    }

    /** JD centres the updater on its main frame, but Selkies resizes the X display to the browser window,
     *  so in a smaller browser the updater runs past the screen edge. It is moved back onto the screen. */
    private static void fitUpdaterWindow(Window w) {
        try {
            java.awt.Color wb = w.getBackground();
            if (wb == null || wb.getAlpha() < 255) { try { w.setBackground(UPDATER_BG); } catch (Throwable ig) { } }
            // Java can keep stale, larger GraphicsConfiguration bounds after Selkies resizes the display, so
            // the smaller of those and the current DisplayMode wins. The frame itself stays square: rounding
            // it needs setShape on an undecorated frame, and undecorating JD's updater breaks its layout.
            java.awt.GraphicsConfiguration gc = w.getGraphicsConfiguration();
            java.awt.Rectangle sc = gc.getBounds();
            int scW = sc.width, scH = sc.height;
            try { java.awt.DisplayMode dm = gc.getDevice().getDisplayMode();
                  if (dm.getWidth()  > 0) scW = Math.min(scW, dm.getWidth());
                  if (dm.getHeight() > 0) scH = Math.min(scH, dm.getHeight()); } catch (Throwable t) { }
            java.awt.Rectangle b = w.getBounds();
            final int m = 8;
            int nx = b.x, ny = b.y;
            if (nx + b.width  > sc.x + scW - m) nx = sc.x + scW - b.width  - m;
            if (ny + b.height > sc.y + scH - m) ny = sc.y + scH - b.height - m;
            if (nx < sc.x + m) nx = sc.x + m;
            if (ny < sc.y + m) ny = sc.y + m;
            if (nx != b.x || ny != b.y) w.setLocation(nx, ny);
        } catch (Throwable ig) { }
    }
    /** Removes decorative frames, including AppWork's grey dialog border, and keeps plain padding. */
    private static void stripFramingBorder(JComponent jc) {
        javax.swing.border.Border b = jc.getBorder();
        if (b == null || b instanceof javax.swing.border.EmptyBorder) return;
        String cn = b.getClass().getName();
        if (b instanceof javax.swing.border.TitledBorder || b instanceof javax.swing.border.EtchedBorder
                || b instanceof javax.swing.border.LineBorder || b instanceof javax.swing.border.BevelBorder
                || b instanceof javax.swing.border.MatteBorder
                || cn.startsWith("org.appwork") || cn.contains("LineBorder") || cn.contains("Etched")
                // FlatLaf's line and scroll pane borders draw the faint frame around a pop-up.
                || cn.contains("FlatLineBorder") || cn.contains("FlatScrollPaneBorder"))
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        // Component.borderWidth=0 does not reach the round, button, text field and text area borders on
        // JD's own controls, so they become an EmptyBorder with the same insets to keep the padding.
        else if (cn.contains("FlatRoundBorder") || cn.contains("FlatButtonBorder")
                || cn.contains("FlatTextBorder") || cn.contains("FlatMarginBorder")) {
            java.awt.Insets in = b.getBorderInsets(jc);
            jc.setBorder(javax.swing.BorderFactory.createEmptyBorder(in.top, in.left, in.bottom, in.right));
        }
    }

    // A glass pane dims the main window while a modal dialog shows; the dialog is its own window and
    // stays bright. It is polled, so a dialog that went away abnormally cannot leave the overlay behind.
    private static final java.util.Map<Window, Component> DIMMED =
            new java.util.WeakHashMap<Window, Component>();

    private static void dimModalBackdrops() {
        boolean modal = false;
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && w.isShowing()
                    && ((Dialog) w).getModalityType() != Dialog.ModalityType.MODELESS) { modal = true; break; }
        }
        // A modal dialog's declared owner is often a hidden shared frame, so the large visible frame
        // is dimmed instead.
        Frame main = null;
        for (Frame f : Frame.getFrames()) {
            if (f.isShowing() && f.getWidth() > 600 && f.getHeight() > 400
                    && f instanceof javax.swing.RootPaneContainer) { main = f; break; }
        }
        if (main == null) return;
        boolean has = DIMMED.containsKey(main);
        try {
            javax.swing.JRootPane rp = ((javax.swing.RootPaneContainer) main).getRootPane();
            if (rp == null) return;
            if (modal && !has) {
                Component saved = rp.getGlassPane();
                // The frame cannot change behind the modal, so one blurred snapshot serves until it
                // closes. Without one, a darker flat veil is used.
                final java.awt.image.BufferedImage blur = frostSnapshot(rp);
                JComponent dim = new JComponent() {
                    protected void paintComponent(Graphics g) {
                        if (blur != null) g.drawImage(blur, 0, 0, getWidth(), getHeight(), null);
                        g.setColor(new Color(0, 0, 0, blur != null ? 110 : 175));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
                dim.setOpaque(false);
                rp.setGlassPane(dim);
                dim.setVisible(true);
                DIMMED.put(main, saved);
                System.out.println("[jd-dialog-agent] jd-highlighter: frosted+dimmed main window behind modal dialog");
            } else if (!modal && has) {
                Component saved = DIMMED.remove(main);
                if (saved != null) { rp.setGlassPane(saved); saved.setVisible(false); }
                else rp.getGlassPane().setVisible(false);
            }
        } catch (Throwable ignore) { }
    }

    /** The root pane's content below the glass pane, blurred at half resolution. */
    private static java.awt.image.BufferedImage frostSnapshot(javax.swing.JRootPane rp) {
        try {
            int w = rp.getWidth(), h = rp.getHeight();
            if (w <= 0 || h <= 0) return null;
            int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);
            java.awt.image.BufferedImage snap = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = snap.createGraphics();
            g.scale(sw / (double) w, sh / (double) h);
            rp.getLayeredPane().printAll(g);   // leaves out the glass pane itself
            g.dispose();
            return boxBlur(boxBlur(snap, 4), 4);   // two passes come close to a gaussian
        } catch (Throwable t) { return null; }
    }

    /** Separable box blur of radius r on an INT_RGB image. */
    private static java.awt.image.BufferedImage boxBlur(java.awt.image.BufferedImage src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] tmp = new int[in.length];
        blurPass(in, tmp, w, h, r);
        blurPass(tmp, in, h, w, r);   // transposed
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, in, 0, w);
        return out;
    }

    /** One separable pass writing transposed, so calling it twice blurs both axes. */
    private static void blurPass(int[] src, int[] dst, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                int lo = Math.max(0, x - r), hi = Math.min(w - 1, x + r);
                for (int k = lo; k <= hi; k++) {
                    int p = src[base + k];
                    rs += (p >> 16) & 0xff; gs += (p >> 8) & 0xff; bs += p & 0xff; n++;
                }
                int rr = rs / n, gg = gs / n, bb = bs / n;
                dst[x * h + y] = (rr << 16) | (gg << 8) | bb;
            }
        }
    }

    /** The innermost non-popup container that holds the Views sections. */
    private static Container findViewsHost(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof Container) {
                Container r = findViewsHost((Container) ch);
                if (r != null) return r;
            }
        }
        if (!(c instanceof JPopupMenu) && subtreeHasAllViewsItems(c)) return c;
        return null;
    }
    /** Counts Views section headers rather than matching their titles, which are localised. */
    private static boolean subtreeHasAllViewsItems(Container c) {
        int[] n = { 0 };
        countViewsHeaders(c, n);
        return n[0] >= 2;
    }
    private static void countViewsHeaders(Container c, int[] n) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && isViewsHeader(ch.getClass())) n[0]++;
            if (ch instanceof Container) countViewsHeaders((Container) ch, n);
        }
    }

    // The docked Views panel of the linkgrabber shows its section headers as chips, like the Views popup,
    // without the separator lines between them.
    private static void badgeViewsPanel() {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            Container host = findViewsHost(w);
            if (host == null) continue;
            monoViewsTables(host);
            boolean changed = badgeViewsHeaders(host);
            changed |= stripHeaderScrollDividers(host);
            changed |= flattenViewsGrids(host);
            hideSeparators(host);
            if (changed) { host.revalidate(); host.repaint(); }
        }
    }

    /** Returns true if a header got its chip in this pass. */
    private static boolean badgeViewsHeaders(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && isViewsHeader(ch.getClass())) {
                JComponent header = (JComponent) ch;
                if (header.getClientProperty(VIEWS_BADGED) != Boolean.TRUE) {
                    // MenuChipBorder paints its inner border on top of the chip, so a separator line goes.
                    javax.swing.border.Border ob = header.getBorder();
                    if (ob instanceof javax.swing.border.MatteBorder || ob instanceof javax.swing.border.LineBorder
                            || ob instanceof javax.swing.border.EtchedBorder) ob = null;
                    header.setBorder(new MenuChipBorder(ob));
                    header.setOpaque(false);
                    header.putClientProperty(VIEWS_BADGED, Boolean.TRUE);
                    changed = true;
                }
            }
            if (ch instanceof Container) changed |= badgeViewsHeaders((Container) ch);
        }
        return changed;
    }

    /** The package check keeps out org.jdownloader.extensions.Header from the settings pages. */
    private static boolean isViewsHeader(Class<?> k) {
        String cn = k.getName();
        return (cn.endsWith(".Header") && cn.startsWith("org.jdownloader.gui.views.components."))
                || cn.endsWith(".CustomFilterHeader");   // the custom views section
    }

    /** A HeaderScrollPane draws its divider as a border. Returns true if one was removed. */
    private static boolean stripHeaderScrollDividers(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JComponent && ch.getClass().getName().endsWith(".HeaderScrollPane")) {
                JComponent hsp = (JComponent) ch;
                javax.swing.border.Border b = hsp.getBorder();
                if (b != null && !(b instanceof javax.swing.border.EmptyBorder)) {
                    hsp.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                    changed = true;
                }
            }
            if (ch instanceof Container) changed |= stripHeaderScrollDividers((Container) ch);
        }
        return changed;
    }

    // Mono file-type icons in the Views tables; the hoster table keeps its coloured favicons.
    private static void monoViewsTables(Container host) {
        try {
            java.util.List<JTable> tabs = new java.util.ArrayList<JTable>();
            collectTables(host, tabs);
            for (JTable t : tabs) {
                if (t.getRowCount() == 0) continue;
                boolean hoster = false;
                try {
                    javax.swing.Icon i0 = firstIcon(t.prepareRenderer(t.getCellRenderer(0, 0), 0, 0));
                    if (i0 != null && i0.getClass().getName().toLowerCase().contains("domain")) hoster = true;
                } catch (Throwable ig) { }
                if (hoster) continue;
                javax.swing.table.TableColumnModel cm = t.getColumnModel();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    javax.swing.table.TableColumn tc = cm.getColumn(i);
                    javax.swing.table.TableCellRenderer cur = tc.getCellRenderer();
                    if (cur == null) { try { cur = t.getCellRenderer(0, i); } catch (Throwable ig) { cur = null; } }
                    if (cur == null || cur instanceof MonoIconRenderer) continue;
                    String cn = cur.getClass().getName().toLowerCase();
                    if (cn.contains("favicon") || cn.contains("hoster") || cn.contains("domain")) continue;
                    tc.setCellRenderer(new MonoIconRenderer(cur));
                }
                // Some Views tables override getCellRenderer and ignore the column renderer.
                java.util.Set<Class<?>> seen = new java.util.HashSet<Class<?>>();
                for (int i = 0; i < cm.getColumnCount(); i++) {
                    Class<?> cc; try { cc = t.getColumnClass(i); } catch (Throwable ig) { continue; }
                    if (!seen.add(cc)) continue;
                    javax.swing.table.TableCellRenderer def = t.getDefaultRenderer(cc);
                    if (def == null || def instanceof MonoIconRenderer) continue;
                    String dn = def.getClass().getName().toLowerCase();
                    if (dn.contains("favicon") || dn.contains("hoster") || dn.contains("domain")) continue;
                    t.setDefaultRenderer(cc, new MonoIconRenderer(def));
                }
            }
        } catch (Throwable ignore) { }
    }
    private static javax.swing.Icon firstIcon(Component c) {
        if (c instanceof javax.swing.JLabel) return ((javax.swing.JLabel) c).getIcon();
        if (c instanceof AbstractButton) return ((AbstractButton) c).getIcon();
        if (c instanceof Container)
            for (Component ch : ((Container) c).getComponents()) { javax.swing.Icon i = firstIcon(ch); if (i != null) return i; }
        return null;
    }

    /** Hides the grid of the Views tables in their own background, since they sit on the base colour
     *  rather than a card. JD can turn the lines back on, so this runs every tick and reports changes. */
    private static boolean flattenViewsGrids(Container c) {
        boolean changed = false;
        for (Component ch : c.getComponents()) {
            if (ch instanceof JTable) {
                JTable t = (JTable) ch;
                try {
                    if (t.getShowHorizontalLines() || t.getShowVerticalLines()) { t.setShowGrid(false); changed = true; }
                    Color bg = t.getBackground();
                    if (bg != null && !bg.equals(t.getGridColor())) { t.setGridColor(bg); changed = true; }
                    Dimension sp = t.getIntercellSpacing();
                    if (sp == null || sp.width != 0 || sp.height != 0) { t.setIntercellSpacing(new Dimension(0, 0)); changed = true; }
                } catch (Throwable ignore) { }
            }
            if (ch instanceof Container) changed |= flattenViewsGrids((Container) ch);
        }
        return changed;
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
