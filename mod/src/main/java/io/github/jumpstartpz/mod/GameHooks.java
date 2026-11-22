package io.github.jumpstartpz.mod;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Patches the game for the {@link SecureScreen}, with three tiny bytecode changes:
 * <ul>
 *   <li>{@code zombie.ui.UIManager.render()} calls {@link SecureScreen#onRender()} before it returns,
 *       after every Lua window and OnPostUIDraw;</li>
 *   <li>{@code zombie.ui.UIManager.update()} and {@code zombie.input.GameKeyboard.update()} return
 *       right away while {@link SecureScreen#blocksInput()} is true.</li>
 * </ul>
 * Written directly as instructions rather than as Byte Buddy advice: advice needs the advice class
 * file, and ZombieBuddy defines a mod's classes without serving their class files.
 */
final class GameHooks {
    private static final String SCREEN = "io/github/jumpstartpz/mod/SecureScreen";

    private GameHooks() {
    }

    /** Patches the classes now if they are loaded, otherwise when they load; throws if a patch fails now. */
    static void install(Instrumentation instrumentation) {
        // The inserted calls are resolved by the game classes' loader (the one of projectzomboid.jar);
        // it must find this very SecureScreen, or every frame would throw NoClassDefFoundError.
        try {
            ClassLoader gameLoader = Class.forName("zombie.input.GameKeyboard", false, ClassLoader.getSystemClassLoader()).getClassLoader();
            if (Class.forName(SecureScreen.class.getName(), false, gameLoader) != SecureScreen.class) {
                throw new IllegalStateException("the game sees another SecureScreen class");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game cannot see Jumpstart's classes", e);
        }
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable throwable) {
                        errors.add(typeName + ": " + throwable);
                    }

                    @Override
                    public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded,
                                                 net.bytebuddy.dynamic.DynamicType dynamicType) {
                        System.out.println("[Jumpstart] secure screen hooks in " + type.getName() + (loaded ? "" : " (as it loads)"));
                    }
                })
                .type(named("zombie.ui.UIManager")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .readerFlags(ClassReader.EXPAND_FRAMES)
                                .method(named("render").and(takesArguments(0)), GameHooks::callScreenOnReturn)
                                .method(named("update").and(takesArguments(0)), GameHooks::returnWhileBlocked)))
                .type(named("zombie.input.GameKeyboard")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .readerFlags(ClassReader.EXPAND_FRAMES)
                                .method(named("update").and(takesArguments(0)), GameHooks::returnWhileBlocked)))
                .installOn(instrumentation);
        // UIManager may load only later; then it is patched as it loads (and a failure there only
        // means the screen never draws, so it never opens and never blocks input)
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", errors));
        }
    }

    /** Before every return: SecureScreen.onRender(). */
    private static MethodVisitor callScreenOnReturn(TypeDescription type, MethodDescription method, MethodVisitor mv,
                                                    Implementation.Context context, TypePool pool, int writerFlags, int readerFlags) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitInsn(int opcode) {
                if (opcode == Opcodes.RETURN) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN, "onRender", "()V", false);
                }
                super.visitInsn(opcode);
            }
        };
    }

    /** At the start of a static no-argument void method: if (SecureScreen.blocksInput()) return; */
    private static MethodVisitor returnWhileBlocked(TypeDescription type, MethodDescription method, MethodVisitor mv,
                                                   Implementation.Context context, TypePool pool, int writerFlags, int readerFlags) {
        if (!method.isStatic() || !method.getReturnType().represents(void.class)) {
            return mv;
        }
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label go = new Label();
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN, "blocksInput", "()Z", false);
                super.visitJumpInsn(Opcodes.IFEQ, go);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(go);
                super.visitFrame(Opcodes.F_NEW, 0, new Object[0], 0, new Object[0]);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
            }
        };
    }
}
