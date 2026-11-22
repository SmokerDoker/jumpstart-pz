package io.github.jumpstartpz.core.fixtures;

import net.bytebuddy.asm.Advice;

/** Test mod A: logs "A" whenever a patched method is entered. */
public final class ModAAdvice {
    private ModAAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter() {
        CallLog.add("A");
    }
}
