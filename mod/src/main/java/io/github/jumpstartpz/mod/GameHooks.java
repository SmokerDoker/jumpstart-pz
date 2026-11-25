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
        checkVisible(FileJournal.class);
        checkVisible(ModDataJournal.class);
        checkVisible(LuaStartJournal.class);
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
                // Lua can move the mouse cursor (Mouse.setXY): not while the screen is open, so nothing can
                // put the cursor on a button the player is about to click
                .type(named("zombie.input.Mouse")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .readerFlags(ClassReader.EXPAND_FRAMES)
                                .method(named("setXY").and(takesArguments(2)), GameHooks::returnWhileBlocked)))
                .type(named("zombie.network.GameClient")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("receiveClientCommand"), (t, m, mv, c, p, w, r) -> redirect(mv, TRIGGER3, "clientEvent"))))
                // clean removal: which mod's Lua writes which file, and makes which ModData table
                .type(named("zombie.Lua.LuaManager$GlobalObject")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("getFileWriter").and(takesArguments(3)),
                                        (t, m, mv, c, p, w, r) -> callOnEntry(mv, FILES, "onFileWriter", "(Ljava/lang/String;)V", 0))
                                .method(named("getModFileWriter").and(takesArguments(4)),
                                        (t, m, mv, c, p, w, r) -> callOnEntry(mv, FILES, "onModFileWriter",
                                                "(Ljava/lang/String;Ljava/lang/String;)V", 0, 1))))
                // switching off a mod that came with the save: what its Lua changed while the game loaded it
                .type(named("zombie.Lua.LuaManager")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("LoadDirBase").and(takesArguments(String.class, boolean.class)),
                                        (t, m, mv, c, p, w, r) -> callOnEntry(mv, START, "onLoadDir", "()V"))))
                .type(named("se.krka.kahlua.j2se.KahluaTableImpl")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("rawset").and(takesArguments(Object.class, Object.class)),
                                        (t, m, mv, c, p, w, r) -> callOnEntry(mv, START, "onSet",
                                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", 0, 1, 2))))
                .type(named("zombie.world.moddata.GlobalModData")).transform((builder, type, loader, module, domain) -> builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .method(named("getOrCreate").and(takesArguments(String.class))
                                                .or(named("create").and(takesArguments(String.class)))
                                                .or(named("add").and(takesArguments(2))),
                                        (t, m, mv, c, p, w, r) -> callOnEntry(mv, MODDATA, "onKey", "(Ljava/lang/String;)V", 1)))));
    }

    private static final String FILES = "io/github/jumpstartpz/mod/FileJournal";
    private static final String MODDATA = "io/github/jumpstartpz/mod/ModDataJournal";
    private static final String START = "io/github/jumpstartpz/mod/LuaStartJournal";

    /** At the method's start: target(the given reference arguments). No jump, so no new stack map frame. */
    private static MethodVisitor callOnEntry(MethodVisitor mv, String owner, String name, String descriptor, int... slots) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                for (int slot : slots) {
                    super.visitVarInsn(Opcodes.ALOAD, slot);
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                super.visitMaxs(Math.max(maxStack, slots.length), maxLocals);
            }
        };
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

    /** A parameter's type as a stack map frame entry. */
    static Object frameType(TypeDescription t) {
        if (t.represents(long.class)) {
            return Opcodes.LONG;
        }
        if (t.represents(double.class)) {
            return Opcodes.DOUBLE;
        }
        if (t.represents(float.class)) {
            return Opcodes.FLOAT;
        }
        if (t.isPrimitive()) {
            return Opcodes.INTEGER; // int, boolean, byte, char, short
        }
        return t.getInternalName();
    }

    /** At the start of a static void method: if (SecureScreen.blocksInput()) return; */
    private static MethodVisitor returnWhileBlocked(TypeDescription type, MethodDescription method, MethodVisitor mv,
                                                   Implementation.Context context, TypePool pool, int writerFlags, int readerFlags) {
        if (!method.isStatic() || !method.getReturnType().represents(void.class)) {
            return mv;
        }
        // the frame at the jump target lists the method's parameters, which the code after it reads
        Object[] locals = method.getParameters().asTypeList().asErasures().stream().map(GameHooks::frameType).toArray();
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                Label go = new Label();
                super.visitMethodInsn(Opcodes.INVOKESTATIC, SCREEN, "blocksInput", "()Z", false);
                super.visitJumpInsn(Opcodes.IFEQ, go);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(go);
                super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
            }
        };
    }
}
