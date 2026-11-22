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

    private static final String NET = "io/github/jumpstartpz/mod/net/Net";
    private static final String EVENTS = "zombie/Lua/LuaEventManager";
    private static final String TRIGGER3 = "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String TRIGGER4 = "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

    /**
     * Client: the secure screen's hooks, plus Jumpstart's channel: in GameClient.receiveClientCommand
     * (the packet with a server command) the call that hands the command to Lua's OnServerCommand
     * goes to net.Net.clientEvent, which keeps Jumpstart's own commands away from Lua.
     */
    static void install(Instrumentation instrumentation) {
        checkVisible(SecureScreen.class);
        run(instrumentation, new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("zombie.ui.UIManager")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .readerFlags(ClassReader.EXPAND_FRAMES)
                                .method(named("render").and(takesArguments(0)), GameHooks::callScreenOnReturn)
                                .method(named("update").and(takesArguments(0)), GameHooks::returnWhileBlocked)))
                .type(named("zombie.input.GameKeyboard")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .readerFlags(ClassReader.EXPAND_FRAMES)
                                .method(named("update").and(takesArguments(0)), GameHooks::returnWhileBlocked)))
                .type(named("zombie.network.GameClient")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("receiveClientCommand"), (t, m, mv, c, p, w, r) -> redirect(mv, TRIGGER3, "clientEvent")))));
    }

    /**
     * Server: Jumpstart's channel (GameServer.receiveClientCommand hands Jumpstart's commands to
     * net.Net.serverEvent instead of Lua's OnClientCommand) and a tick (ServerMap.preupdate).
     */
    static void installServer(Instrumentation instrumentation) {
        checkVisible(io.github.jumpstartpz.mod.net.Net.class);
        run(instrumentation, new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("zombie.network.GameServer")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("receiveClientCommand"), (t, m, mv, c, p, w, r) -> redirect(mv, TRIGGER4, "serverEvent"))))
                .type(named("zombie.network.ServerMap")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("preupdate").and(takesArguments(0)), (t, m, mv, c, p, w, r) -> new MethodVisitor(Opcodes.ASM9, mv) {
                                    @Override
                                    public void visitCode() {
                                        super.visitCode();
                                        super.visitMethodInsn(Opcodes.INVOKESTATIC, NET, "serverTick", "()V", false);
                                    }
                                }))));
    }

    private static void run(Instrumentation instrumentation, AgentBuilder builder) {
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        builder.with(new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader loader, JavaModule module, boolean loaded, Throwable throwable) {
                errors.add(typeName + ": " + throwable);
            }

            @Override
            public void onTransformation(TypeDescription type, ClassLoader loader, JavaModule module, boolean loaded,
                                         net.bytebuddy.dynamic.DynamicType dynamicType) {
                System.out.println("[Jumpstart] hooks in " + type.getName() + (loaded ? "" : " (as it loads)"));
            }
        }).installOn(instrumentation);
        // classes that load only later are patched as they load; a failure there only means the hook
        // never runs (no screen, no channel), never a broken game
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", errors));
        }
    }

    /** The inserted calls are resolved by the game's class loader; it must find this very class. */
    private static void checkVisible(Class<?> ours) {
        try {
            ClassLoader gameLoader = Class.forName("zombie.core.Core", false, ClassLoader.getSystemClassLoader()).getClassLoader();
            if (Class.forName(ours.getName(), false, gameLoader) != ours) {
                throw new IllegalStateException("the game sees another " + ours.getSimpleName());
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game cannot see Jumpstart's classes", e);
        }
    }

    /** Replaces LuaEventManager.triggerEvent(desc) with Net.target(desc): same arguments, same stack. */
    private static MethodVisitor redirect(MethodVisitor mv, String descriptor, String target) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                if (opcode == Opcodes.INVOKESTATIC && owner.equals(EVENTS) && name.equals("triggerEvent") && desc.equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, NET, target, descriptor, false);
                } else {
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                }
            }
        };
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
