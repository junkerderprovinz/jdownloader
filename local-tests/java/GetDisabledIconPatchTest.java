import java.lang.reflect.Method;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Local-only ASM check for review #2: INVOKESPECIAL must use ClassReader.getSuperName().
 */
public final class GetDisabledIconPatchTest {
    private GetDisabledIconPatchTest() {}

    static byte[] fakeJdDefault(String superInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "org/jdownloader/gui/laf/jddefault/JDDefaultLookAndFeel",
                null, superInternal, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getDisabledIcon",
                "(Ljavax/swing/JComponent;Ljavax/swing/Icon;)Ljavax/swing/Icon;", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mirrors DialogConfirmAgent.patchJdDefaultGetDisabledIcon owner selection. */
    static byte[] patch(byte[] original) {
        ClassReader cr = new ClassReader(original);
        final String superName = cr.getSuperName();
        if (superName == null || superName.isEmpty() || "java/lang/Object".equals(superName)) {
            return null;
        }
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] hit = { false };
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!"getDisabledIcon".equals(name)) {
                    return mv;
                }
                hit[0] = true;
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "getDisabledIcon",
                        "(Ljavax/swing/JComponent;Ljavax/swing/Icon;)Ljavax/swing/Icon;", false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();
                return new MethodVisitor(Opcodes.ASM9) {};
            }
        };
        cr.accept(cv, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    static String findInvokeSpecialOwner(byte[] bytes) {
        final String[] owner = { null };
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                        if (opcode == Opcodes.INVOKESPECIAL && "getDisabledIcon".equals(n)) {
                            owner[0] = o;
                        }
                    }
                };
            }
        }, 0);
        return owner[0];
    }

    public static void main(String[] args) {
        int failures = 0;

        byte[] patched = patch(fakeJdDefault("com/example/CustomSyntheticaSuper"));
        if (patched == null) {
            System.err.println("FAIL: patch returned null for valid super");
            failures++;
        } else {
            String owner = findInvokeSpecialOwner(patched);
            if (!"com/example/CustomSyntheticaSuper".equals(owner)) {
                System.err.println("FAIL: expected INVOKESPECIAL owner CustomSyntheticaSuper, got " + owner);
                failures++;
            } else {
                System.out.println("OK: INVOKESPECIAL uses getSuperName()=" + owner);
            }
        }

        if (patch(fakeJdDefault("java/lang/Object")) != null) {
            System.err.println("FAIL: Object super should skip patch");
            failures++;
        } else {
            System.out.println("OK: unexpected Object super -> null (fail-safe)");
        }

        // Hard-coded Synthetica owner must NOT appear when super differs
        byte[] patched2 = patch(fakeJdDefault("de/javasoft/plaf/synthetica/OtherSuper"));
        String owner2 = findInvokeSpecialOwner(patched2);
        if ("de/javasoft/plaf/synthetica/SyntheticaLookAndFeel".equals(owner2)) {
            System.err.println("FAIL: still hard-coded SyntheticaLookAndFeel");
            failures++;
        } else if (!"de/javasoft/plaf/synthetica/OtherSuper".equals(owner2)) {
            System.err.println("FAIL: wrong owner " + owner2);
            failures++;
        } else {
            System.out.println("OK: non-default super preserved (" + owner2 + ")");
        }

        if (failures != 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("GetDisabledIconPatchTest: all passed");
    }
}
