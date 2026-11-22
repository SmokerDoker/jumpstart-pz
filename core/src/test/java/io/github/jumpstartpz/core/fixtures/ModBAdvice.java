package io.github.jumpstartpz.core.fixtures;

import net.bytebuddy.asm.Advice;

/** Test mod B: logs "B" whenever a patched method is entered. */
public final class ModBAdvice {
    private ModBAdvice() {
    }

    @Advice.OnMethodEnter
    public static void enter() {
        CallLog.add("B");
    }
}
