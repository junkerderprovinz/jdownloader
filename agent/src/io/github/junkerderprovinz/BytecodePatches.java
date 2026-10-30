package io.github.junkerderprovinz;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static io.github.junkerderprovinz.DialogConfirmAgent.*;
import static io.github.junkerderprovinz.LafDefaults.*;

/** Load-time ASM patches for AppWork, jsyntaxpane, JD and FlatLaf classes. */
final class BytecodePatches {

    // AppWork's BasicCircleProgressBarUI reads its circleBar field instead of the component it is
    // given, and uninstallUI clears that field before uninstallListeners. Under FlatLaf a second
    // setUI on the transient CircledProgressBar renderer leaves a delegate with a null circleBar,
    // so the Event Scripter editor's pack() throws and the dialog never opens. Neither reflection
    // nor UIManager reaches that renderer, so the class is patched at load time; any error in the
    // transform leaves the original bytes.
    private static final String CPB_UI = "org/appwork/swing/components/circlebar/BasicCircleProgressBarUI";
    private static final String CPB_FIELD_OWNER = "org/appwork/swing/components/circlebar/CircledProgressBar";
    private static final String CPB_FIELD_DESC = "L" + CPB_FIELD_OWNER + ";";
    // jsyntaxpane's ScriptAction keeps a static ScriptEngine that is null on a JVM without a
    // JavaScript engine (Java 15 and later), so its install() throws while the Event Scripter sets
    // up its editor kit. AppWork swallows the exception and the dialog never opens.
    private static final String SA_CLASS = "jsyntaxpane/actions/ScriptAction";
    private static final String FLAT_LAF = "com/formdev/flatlaf/FlatLaf";
    /** JDDefaultLookAndFeel sits on the app class loader and cannot see NewTheme, so its
     *  getDisabledIcon prints a ClassNotFoundException for every disabled icon. The patch calls
     *  the Synthetica super method instead. */
    private static final String JD_DEFAULT_LAF = "org/jdownloader/gui/laf/jddefault/JDDefaultLookAndFeel";
    /** Classic painters call LAFOptions.getInstance() in early paints, before JD has set up the
     *  singleton, and throw WTFException. The patch falls back to stock colours. */
    private static final String JD_SCROLLBAR_PAINTER = "org/jdownloader/gui/laf/jddefault/CustomScrollbarPainter";
    private static final String JD_PROGRESS_PAINTER = "org/jdownloader/gui/laf/jddefault/CustomProgressbarPainter";
    private static boolean circleBarPatchLogged = false;
    private static boolean scriptActionPatchLogged = false;
    private static boolean jdDefaultLafPatchLogged = false;
    private static boolean jdScrollbarPatchLogged = false;
    private static boolean jdProgressPatchLogged = false;

    static void installBytecodeGuards(Instrumentation inst) {
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
                            if (SEARCH_CAT.equals(className)) return patchSearchCategory(classfileBuffer, loader);
                            if (FAV_ICONS.equals(className)) return patchFavIcons(classfileBuffer, loader);
                            if (ACTION_BAR.equals(className)) return patchDropdownImage(classfileBuffer, loader);
                            if (HOSTER_CHOOSER_ICON.equals(className)) return patchHosterChooserIcon(classfileBuffer, loader);
                            if (FLAT_MI_RENDERER.equals(className)) return patchMenuItemRenderer(classfileBuffer, loader);
                        }
                        // Registered before JD's setLookAndFeel, the defaults reach the first paint.
                        if (FLAT_LAF.equals(className)) registerDefaultsOnFlatLafLoad(loader);
                        return null;
                    } catch (Throwable err) {
                        System.out.println("[jd-dialog-agent] bytecode transform skipped for " + className
                                + " (" + err + ")");
                        return null;   // keep the original bytes
                    }
                }
            };
            inst.addTransformer(t, true);
            System.out.println("[jd-dialog-agent] bytecode guards armed (CircledProgressBar + ScriptAction"
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

    /** getColor and getColorMouseOver return stock classic blues while LAFOptions is not ready or has no colour. */
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
                // return new Color(fallbackArgb, true); uncached, so the next call asks LAFOptions again
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
                System.out.println("[jd-dialog-agent] CustomScrollbarPainter colour getters not found, left as-is"); }
            return null;
        }
        if (!jdScrollbarPatchLogged) { jdScrollbarPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched CustomScrollbarPainter (" + hits[0]
                    + " getters): no LAFOptions-not-ready scrollbar crash"); }
        return cw.toByteArray();
    }

    /** getColorArray fills null entries with stock blues, since LinearGradientPaint throws on a null colour. */
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
                // Null slots get the stock colour and local 2 flags the fallback. colorArray is
                // cached only when all five came from LAFOptions, so a later call tries again.
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
                System.out.println("[jd-dialog-agent] CustomProgressbarPainter.getColorArray not found, left as-is"); }
            return null;
        }
        if (!jdProgressPatchLogged) { jdProgressPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched CustomProgressbarPainter.getColorArray: no null gradient NPE"); }
        return cw.toByteArray();
    }

    /** Replaces getDisabledIcon with return super.getDisabledIcon(component, icon). INVOKESPECIAL
     *  names the superclass read from the class bytes, because a hard-coded SyntheticaLookAndFeel
     *  fails verification when it is the wrong owner. */
    private static byte[] patchJdDefaultGetDisabledIcon(byte[] original, final ClassLoader loader) {
        ClassReader cr = new ClassReader(original);
        final String superName = cr.getSuperName();
        if (superName == null || superName.isEmpty() || "java/lang/Object".equals(superName)) {
            if (!jdDefaultLafPatchLogged) { jdDefaultLafPatchLogged = true;
                System.out.println("[jd-dialog-agent] JDDefaultLookAndFeel: unexpected superName="
                        + superName + ", skip getDisabledIcon patch (keep original bytes)"); }
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
                System.out.println("[jd-dialog-agent] JDDefaultLookAndFeel.getDisabledIcon not found, left as-is"); }
            return null;
        }
        if (!jdDefaultLafPatchLogged) { jdDefaultLafPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched JDDefaultLookAndFeel.getDisabledIcon -> super "
                    + superName + " (no NewTheme CNFE spam)"); }
        return cw.toByteArray();
    }

    /** Prepends to getPreferredSize, paint, update and uninstallUI:
     *      if (circleBar == null && c instanceof CircledProgressBar) circleBar = (CircledProgressBar) c;
     *      if (circleBar == null) return <0x0 Dimension | void>;
     *  Real bars rebind to their component and behave as before, a bar without one sizes to zero
     *  and paints nothing. Returns null, keeping the original, when none of the methods exists. */
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
                System.out.println("[jd-dialog-agent] BasicCircleProgressBarUI entry methods not found,"
                        + " left as-is (AppWork changed?)"); }
            return null;
        }
        if (!circleBarPatchLogged) { circleBarPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched BasicCircleProgressBarUI (" + patchedCount[0]
                    + " methods: getPreferredSize/paint/update, circleBar null-guard): Event Scripter editor fixed"); }
        return cw.toByteArray();
    }

    /** Makes ScriptAction.install() and getScriptFromURL() return early while the static engine is
     *  null, so the Event Scripter editor kit installs. Returns null, keeping the original, when
     *  the methods are absent. */
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
                System.out.println("[jd-dialog-agent] ScriptAction methods not found, left as-is (jsyntaxpane changed?)"); }
            return null;
        }
        if (!scriptActionPatchLogged) { scriptActionPatchLogged = true;
            System.out.println("[jd-dialog-agent] patched jsyntaxpane ScriptAction (" + patchedCount[0]
                    + " methods, null-engine guard): Event Scripter code editor opens under FlatLaf"); }
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
    private static final String SEARCH_CAT = "org/jdownloader/gui/views/components/LinktablesSearchCategory";
    private static final String FAV_ICONS = "jd/controlling/faviconcontroller/FavIcons";
    private static final String HOSTER_CHOOSER_ICON = "jd/gui/swing/dialog/HosterChooserTableModel$3";
    private static final String ACTION_BAR = "org/jdownloader/gui/views/downloads/bottombar/CustomizeableActionBar";
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
    static final int TAB_GAP = 4;

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

    // The search field under the lists paints its category icon straight from getIcon on every paint,
    // so the icon is swapped where it is returned.
    private static byte[] patchSearchCategory(byte[] original, final ClassLoader loader) {
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
                if (!("getIcon".equals(name) && "()Ljavax/swing/Icon;".equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN)
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "monoSearchIcon",
                                    "(Ljavax/swing/Icon;)Ljavax/swing/Icon;", false);
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] LinktablesSearchCategory.getIcon not found, search icons left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched LinktablesSearchCategory.getIcon -> mono search icons");
        return cw.toByteArray();
    }

    // The stand-in logo for a hoster without a favicon is drawn once per size and cached, so it is
    // recoloured where it is created.
    private static byte[] patchFavIcons(byte[] original, final ClassLoader loader) {
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
                if (!("createDefaultFavIcon".equals(name)
                        && "(Lorg/appwork/utils/logging2/LogSource;Ljava/lang/String;II)Ljava/awt/Image;".equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN)
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "lightFavIcon",
                                    "(Ljava/awt/Image;)Ljava/awt/Image;", false);
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] FavIcons.createDefaultFavIcon not found, stand-in logos left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched FavIcons.createDefaultFavIcon -> light stand-in logos");
        return cw.toByteArray();
    }

    // The bottom bar glues a tiny arrow onto a menu button's glyph as one image, which the mono pass
    // would turn into a bar, so the image is rebuilt where it is made.
    private static byte[] patchDropdownImage(byte[] original, final ClassLoader loader) {
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
                if (!("createDropdownImage".equals(name) && "(ZLjava/awt/Image;)Ljavax/swing/ImageIcon;".equals(desc))) return mv;
                n[0]++;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int op) {
                        if (op == Opcodes.ARETURN) {
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "monoDropdownIcon",
                                    "(Ljavax/swing/ImageIcon;ZLjava/awt/Image;)Ljavax/swing/ImageIcon;", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] CustomizeableActionBar.createDropdownImage not found, dropdown glyphs left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched CustomizeableActionBar.createDropdownImage -> mono dropdown glyphs");
        return cw.toByteArray();
    }

    // The account dialog's hoster list asks for a logo without leave to fetch it, so it only ever shows
    // logos some other view loaded before. The call is routed through the agent, which lets it fetch.
    private static byte[] patchHosterChooserIcon(byte[] original, final ClassLoader loader) {
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
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                        if (op == Opcodes.INVOKEVIRTUAL && "org/jdownloader/DomainInfo".equals(owner)
                                && "getFavIcon".equals(mName) && "(Z)Ljavax/swing/Icon;".equals(mDesc)) {
                            n[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_INTERNAL, "chooserFavIcon",
                                    "(Ljava/lang/Object;Z)Ljavax/swing/Icon;", false);
                            return;
                        }
                        super.visitMethodInsn(op, owner, mName, mDesc, itf);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        if (n[0] == 0) {
            System.out.println("[jd-dialog-agent] HosterChooserTableModel favicon call not found, account dialog logos left as-is");
            return null;
        }
        System.out.println("[jd-dialog-agent] patched HosterChooserTableModel -> account dialog loads hoster logos");
        return cw.toByteArray();
    }
}
