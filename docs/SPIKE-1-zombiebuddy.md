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

## Decision: our own fork

ZombieBuddy's author did not answer when asked about a deeper integration, so Jumpstart does not
wait for upstream changes. The changes live in a fork,
[SmokerDoker/zombiebuddy-jumpstart](https://github.com/SmokerDoker/zombiebuddy-jumpstart)
(branch `jumpstart/hotswap`, details in its `FORK.md`). It stays a drop-in replacement for
`ZombieBuddy.jar`: same packages, same mod API, same `mod_approvals.json`, so every existing Java
mod and BiteCheck keep working. The changes are small separate commits, so they can still be
offered upstream as a pull request.

What the fork adds:

| Change | Effect |
| --- | --- |
| `PatchEngine.applyPatches` returns a `PatchHandle` | The transformers are kept per mod jar instead of discarded. |
| Agent option `hotswap=true` | Advice-only jars get one retransformable transformer without class format changes, also for classes that load later. |
| `Hotswap` (Java and Lua: `ZombieBuddy.Hotswap`) | `off`/`on` per mod, `status`, and `load` for a jar that was not loaded at start. |
| `Hotswap.load` checks `mod_approvals.json` | A jar loads at runtime only if its SHA-256 is approved there (by ZombieBuddy, BiteCheck or Jumpstart). No prompt at runtime. |
| No self-update from upstream jars | A Zed-signed ZombieBuddy.jar from the Workshop no longer replaces the fork. |

Tested in the fork's `test_hotswap` task (Java agent with `hotswap=true`): a patch on a class
loaded after start switched off and on 50 times, and a jar loaded at runtime only after approval.

## What this means for Jumpstart

- `HotswapEngine` in this repository stays as the reference test of the mechanism; in the game,
  Jumpstart calls the fork's `Hotswap` API instead of installing its own transformers.
- Jumpstart is an ordinary Java mod on top of the fork; it writes approvals to
  `mod_approvals.json` after its BiteCheck check and then calls `Hotswap.load`.

## Open

- **Updates live:** regular mod jars are defined in ZombieBuddy's own class loader, so a newer
  version with the same class names cannot be loaded next to the old one. `Hotswap.load` refuses
  that case today. Fix: load live jars into their own class loader, or rename their classes per
  version.
- **MethodDelegation patches** stay until restart.
- **In-game test** with ZBHelloWorld: see `TESTING.md` in the fork.
