# Spike 1: Can Jumpstart switch ZombieBuddy patches off live?

Status: mechanism proven in tests (`HotswapEngineTest`), not yet tried in the game.
ZombieBuddy source read at commit `HEAD` of [zed-0xff/ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy), November 2026.

## Result

Yes, for Advice patches, if the transformer that applied them is kept. The JVM does the hard
part: when a retransformable transformer is removed and the class is retransformed, the JVM
starts again from the class's original bytes and re-runs every remaining transformer. Removing
one mod's transformer therefore rebuilds the class without that mod and keeps every other mod.
That is the step Hotwire implements by re-running Mixin; here it comes for free.

`HotswapEngine` does exactly this: one Byte Buddy `ResettableClassFileTransformer` per mod, built
with `disableClassFormatChanges()` and `RedefinitionStrategy.RETRANSFORMATION`, reset on disable.
The tests show:

- a mod switched on and off in a class that is already loaded,
- two mods on the same method, either one switched off without touching the other,
- a class that loads only after the mod was switched on, and switching off still works,
- 200 on/off rounds in a row.

## What ZombieBuddy does today

| Finding | Where | Consequence for Jumpstart |
| --- | --- | --- |
| `Loader.getInstrumentation()` is public | `Loader.java` | Jumpstart can be an ordinary ZombieBuddy Java mod; it needs no `-javaagent` of its own. |
| Byte Buddy 1.18.14 is shipped unrelocated | `java/build.gradle` (shadow config) | Jumpstart compiles against Byte Buddy with `compileOnly` and never bundles it. |
| Patches are installed with `AgentBuilder.installOn(...)`, but the returned `ResettableClassFileTransformer` is discarded | `PatchEngine.applyPatches` | Mods that ZombieBuddy loads at start cannot be switched off from outside. |
| Targets not yet loaded are patched with `RedefinitionStrategy.DISABLED` and without `disableClassFormatChanges()` | `PatchEngine.applyPatches`, phase 1 | Those transformers are not retransformable. Their output becomes the class's base for later retransforms, so they survive Jumpstart's resets (no interference), but they can never be removed. |
| `_known_package_phases` and `_known_mains` refuse to load a package twice | `Loader.ApplyPatchesFromPackage` | Re-enabling or updating a mod through ZombieBuddy needs an API that clears this state. |
| Each mod jar gets its own class loader (`g_mod_loaders`) and is transformed first (`g_transformed_jars`, `transformAndLoadJar`) | `Loader.java`, package-private | Jumpstart must load live mods through the same transformation, or `@Patch` annotations will not mean the same thing. |
| MethodDelegation patches (`isAdvice = false`) replace method implementations and change the class format | `PatchEngine.applyPatches` | Not switchable live. They need the side-class technique from the concept, or a restart. |

## Two ways forward

**A: Upstream API in ZombieBuddy (preferred).** A small change in ZombieBuddy keeps the
transformer per mod jar and offers `unload(jar)` / `load(jar)` at runtime. Patches for
not-yet-loaded classes would be installed as retransformable with `disableClassFormatChanges()`
when a mod declares it is hot-swappable. Every Java mod then works with Jumpstart without
changes, and Jumpstart stays small. Needs a conversation with Zed first.

**B: Jumpstart owns live mods (fallback).** Mods present at start stay with ZombieBuddy. Mods
added, updated or toggled live are loaded by Jumpstart: ZombieBuddy's `transformAndLoadJar` is
called by reflection, then `HotswapEngine` applies the resulting patch classes. Works without
upstream changes, but depends on package-private internals that can change with any release.

## Next step

Try it in the game with [zb-hello-world](https://github.com/zed-0xff/ZBHelloWorld): one Advice
patch on the main menu render method that draws "Hello World from ZBHelloWorld!". Remove it
from the normal mod list, load it through Jumpstart (way B), and switch it on and off from the
debug console. When the text appears and disappears without a restart, gate 1 of the roadmap is
met.
