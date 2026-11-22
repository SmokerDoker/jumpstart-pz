package io.github.jumpstartpz.core;

import java.util.Objects;

/**
 * One Advice patch of a mod: which method of which class it changes, and the class holding the
 * {@code @Advice.OnMethodEnter} / {@code @Advice.OnMethodExit} code.
 *
 * <p>This mirrors what a ZombieBuddy {@code @Patch(className, methodName)} with
 * {@code isAdvice = true} declares. MethodDelegation patches change the class layout and cannot be
 * switched off live yet, so they have no spec here.
 */
public record PatchSpec(String targetClass, String targetMethod, Class<?> advice) {
    public PatchSpec {
        Objects.requireNonNull(targetClass, "targetClass");
        Objects.requireNonNull(targetMethod, "targetMethod");
        Objects.requireNonNull(advice, "advice");
    }
}
