package io.github.jumpstartpz.core;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Switches the Advice patches of mods on and off while the game runs.
 *
 * <p>Every mod gets its own retransformable class file transformer. Switching a mod off removes
 * that transformer and retransforms the classes it touched. The JVM then rebuilds each class from
 * its original bytes and runs every remaining transformer again, so the patches of all other mods
 * stay in place. This is the "rebuild without the mod" step Hotwire does with Mixin, done by the
 * JVM itself.
 *
 * <p>All transformers are built with {@link AgentBuilder#disableClassFormatChanges()}: a patch may
 * only change method bodies, never add fields or methods. That keeps every class retransformable,
 * including classes that are loaded only after the mod was switched on.
 */
public final class HotswapEngine {
    private final Instrumentation instrumentation;
    private final Map<String, ResettableClassFileTransformer> active = new LinkedHashMap<>();

    public HotswapEngine(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("this JVM agent cannot retransform classes");
        }
    }

    /** Applies the mod's patches to loaded classes now and to other target classes when they load. */
    public synchronized void enable(String modId, List<PatchSpec> patches) {
        Objects.requireNonNull(modId, "modId");
        if (active.containsKey(modId)) {
            throw new HotswapException(modId + " is already on");
        }
        if (patches.isEmpty()) {
            throw new HotswapException(modId + " has no patches");
        }

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        AgentBuilder builder = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new ErrorCollector(errors));

        for (Map.Entry<String, List<PatchSpec>> target : byTargetClass(patches).entrySet()) {
            List<PatchSpec> specs = List.copyOf(target.getValue());
            builder = builder.type(named(target.getKey())).transform((typeBuilder, type, loader, module, domain) -> {
                var result = typeBuilder;
                for (PatchSpec spec : specs) {
                    result = result.visit(Advice.to(spec.advice()).on(named(spec.targetMethod())));
                }
                return result;
            });
        }

        ResettableClassFileTransformer transformer = builder.installOn(instrumentation);
        if (!errors.isEmpty()) {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            throw new HotswapException(modId + " could not be switched on: " + String.join("; ", errors));
        }
        active.put(modId, transformer);
    }

    /** Removes the mod's patches from every class; the patches of all other mods stay. */
    public synchronized void disable(String modId) {
        ResettableClassFileTransformer transformer = active.get(modId);
        if (transformer == null) {
            throw new HotswapException(modId + " is not on");
        }
        if (!transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)) {
            throw new HotswapException(modId + " could not be switched off: its transformer was not registered");
        }
        active.remove(modId);
    }

    public synchronized boolean isEnabled(String modId) {
        return active.containsKey(modId);
    }

    /** Mods that are on, in the order they were switched on. */
    public synchronized Set<String> enabledMods() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(active.keySet()));
    }

    private static Map<String, List<PatchSpec>> byTargetClass(List<PatchSpec> patches) {
        Map<String, List<PatchSpec>> grouped = new LinkedHashMap<>();
        for (PatchSpec patch : patches) {
            grouped.computeIfAbsent(patch.targetClass(), k -> new ArrayList<>()).add(patch);
        }
        return grouped;
    }

    private static final class ErrorCollector extends AgentBuilder.Listener.Adapter {
        private final List<String> errors;

        ErrorCollector(List<String> errors) {
            this.errors = errors;
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
            errors.add(typeName + ": " + throwable);
        }
    }
}
