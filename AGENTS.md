# AGENTS.md

## Project

This checkout is `noeldvictor/Cemu-thor-experiment`, an Android-focused Cemu fork. Treat `android-port` as this personal fork's main/master branch.

## Remotes

- `origin`: `git@github.com:noeldvictor/Cemu-thor-experiment.git`
- `sapphire`: `git@github.com:SapphireRhodonite/Cemu.git`
- `ssimco`: `git@github.com:SSimco/Cemu.git`
- `upstream`: `git@github.com:cemu-project/Cemu.git`

## Update Workflow

Fetch all remotes before comparing branches:

```sh
git fetch --all --prune
```

As of this update, `origin/android-port` was identical to `sapphire/android-port` and fast-forwarded cleanly to `ssimco/android-port`. Prefer `git rev-list --left-right --count origin/android-port...ssimco/android-port` before merging so the update direction is explicit.

Do not create `codex/` branches for routine Codex development in this repo. Work directly on `android-port`, since this is a personal experiment branch rather than a shared upstream-style review flow. Do not reset, checkout, or revert user changes unless the user explicitly asks for that.

## Android Build

The Android project lives in `src/android`.

Useful commands from the repo root:

```sh
git submodule update --init --recursive
```

Useful commands from `src/android`:

```sh
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:assembleRelease
```

The app currently builds ARM64 only via `abiFilters("arm64-v8a")`. The release Android application ID is `info.cemu.cemu_thor`; debug installs as `info.cemu.cemu_thor.debug`. The Kotlin/JNI namespace remains `info.cemu.cemu` to avoid unnecessary native binding churn.

Android is Vulkan-only in this branch: `src/android/app/build.gradle.kts` passes `-DENABLE_OPENGL=OFF`, and `NativeEmulation.initializeRenderer()` creates a `VulkanRenderer`. If logs show `AdrenoVK` with `/vendor/lib64/hw/vulkan.adreno.so`, the app is using the system Qualcomm Vulkan driver rather than a custom Turnip driver.

On Windows, prefer building from a path without spaces if native dependency builds start failing; vcpkg/autotools packages can be sensitive to physical build paths with spaces. The Android Gradle build has also succeeded from the current checkout path, so do not move the repo just for housekeeping.

## Snapdragon / Adreno Performance Direction

Treat the AYN Thor as the main test device, but avoid hard-coding Thor-only display IDs, panel modes, refresh-rate quirks, model strings, or AYN control-center behavior into core emulator paths. Prefer general Snapdragon 8 Gen 2 / Adreno 740 / Android Vulkan improvements that would also make sense on other Adreno 7xx devices.

Assume Thor Base, Pro, and Max share the same emulator performance class: Snapdragon 8 Gen 2 CPU and Adreno 740 GPU. Do not split CPU/GPU behavior by Base/Pro/Max model name. The meaningful differences are RAM/storage headroom, so Base-specific work should focus on memory pressure, cache size, texture lifetime, and driver/package footprint rather than different scheduling or renderer code. Treat Thor Lite separately if it comes up; it is not the baseline for this fork.

Good optimization targets are Vulkan/Turnip driver selection, shader compilation and cache behavior, accurate barrier and GX2DrawDone sync policy, dual-surface rendering cost, release-build profiling, thermal/GPU/CPU telemetry, Android surface hints that describe fixed-rate emulator content, and lock contention in hot HLE paths. Do not bury device-specific behavior in general code; if a Thor-only workaround is truly needed, put it behind an explicit opt-in setting, document the evidence, and keep the stable default upstream-friendly.

The OSD `Display` page includes a `PAD render scale` slider for external/presentation GamePad output. Keep its default at 100%; lower values are explicit user performance choices that reduce the secondary surface buffer size while preserving aspect ratio. Android emulation surfaces should keep fixed-source 60 FPS frame-rate hints unless there is measured evidence that a game or device benefits from a different hint.

For CPU-side Android optimization, prefer system-managed ADPF/performance hint sessions for the long-lived Wii U scheduler host threads over fixed CPU affinity or model-specific big-core pinning. Android ARM64 may use targeted emulator-core compiler tuning such as `-O3` on `CemuCafe`, but avoid unsafe math flags unless a game-specific regression pass has been done.

Avoid reintroducing single global locks in hot emulation paths. The coreinit atomic HLE uses striped locks keyed by guest memory address so unrelated atomics from multicore games do not serialize while still avoiding ARM under-aligned host atomic loads/stores. Vulkan dual-screen presentation tracks the previous submitted command buffer per swapchain; using one shared marker can make the GamePad present wait for the TV present from the same frame and waste GPU/CPU overlap.

Default to correctness and stability. Risky performance toggles must stay off by default, clearly labeled, and preferably session-only from the OSD. Measure changes with Cemu logs, `adb shell dumpsys display`, KGSL counters, and repeatable game scenes before treating them as wins.

## Device Install

Use `adb devices` to confirm the AYN Thor is connected, then install the APK:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use release builds for performance testing. Debug builds can be dramatically slower on Star Fox Zero because native code is unoptimized and debug-only logs/assertions are active. The default release artifact is `src/android/app/build/outputs/apk/release/app-release.apk`.

It is also useful to push a copy to the device:

```sh
adb push app/build/outputs/apk/release/app-release.apk /sdcard/Download/cemu-thor-experiment-release.apk
```

## AYN Thor Dual Screen

The Thor has two Android displays. During testing, the top/main display appeared as `displayId=0`, and the lower/presentation screen appeared as `displayId=4` with `FLAG_PRESENTATION`.

Keep the Sapphire dual-screen presentation work intact. The PAD screen should be able to render through `PadPresentation` on the external/presentation display, not merely as a second `SurfaceView` inside the main activity. The emulation side menu includes `External PAD screen`, `Swap screens`, and `Rotate external screen left`; PAD visible and external PAD default to enabled for this Thor-focused build.

The Android Back key is intentionally mapped to the same in-game menu toggle as the hotkey action, and predictive back callbacks are enabled in the Android manifest. The emulation side menu also includes a `Show FPS` toggle; enabling it turns on Cemu's native overlay at the top-left corner when the overlay was previously disabled and clears non-FPS overlay stats so copied desktop settings do not unexpectedly show CPU/RAM/debug overlays during gameplay.

The emulation side menu is a two-panel drawer: the left rail selects `Display`, `Performance`, `Audio`, `Controls`, or `Tools`, and the right panel shows that section's controls. `Performance` includes `Show FPS`, `Async shader compile`, and session-only risky speed toggles for skipping GX2DrawDone sync and accurate Vulkan barriers. Keep those risky toggles off by default; they may improve FPS in some scenes but can destabilize games and should not be silently persisted. `Audio` includes `GamePad audio` and a `GamePad volume` slider.

`Performance` also includes a developer-only guest PPC hot-block profiler. It is off by default and instruments Android ARM64 recompiler enterable segments with a gated runtime check. Use `Profile guest PPC blocks`, run the slow/crashy scene, then tap `Dump guest profile`; dumps are written under the app files directory at `dump/recompiler/hot_blocks_YYYYMMDD_HHMMSS.txt`. This is a targeting tool for later Star Fox HLE/recompiler work, not a speedup by itself, and it should stay off during normal performance testing.

Star Fox Zero USA v16 has a guarded tiny-wrapper inliner in `src/Cafe/GamePatch.cpp`. It only runs for RPX hash `0x3768054d` with `prj_030` CRC `0x33864358`, verifies each target is a one-instruction wrapper followed by `blr`, then replaces direct `bl wrapper` calls with the wrapped PPC instruction before recompilation. Treat this as a measured Star Fox optimization from the hot-block profiler, not a general-purpose RPL optimizer.

When smoke-testing dual screen, `dumpsys window windows` should show a `info.cemu.cemu_thor` or `info.cemu.cemu_thor.debug` window on the presentation display while `EmulationActivity` is on the main display. On the test Thor, that was usually `mDisplayId=4` for the lower screen and `displayId=0` for the top screen; do not hard-code those IDs.

## Star Fox Zero Recompiler Smoke Test

Star Fox Zero USA (`00050000101b0400`) is a useful Android ARM64 recompiler stress test. The launch path tested on the Thor was `/storage/2664-21DE/Roms/wiiu/Star Fox Zero (USA) (En,Fr,Es).wux`.

If the game is slow or exits cleanly with status 1, check `/sdcard/Android/data/info.cemu.cemu_thor.debug/files/log.txt` or `/sdcard/Android/data/info.cemu.cemu_thor/files/log.txt` for `PPCRecompiler: Unsupported instruction`. Known ARM64 gaps fixed in this fork include indexed paired-single load/store (`psq_lx`/`psq_stx`), `ps_nabs`, `dcbzl`, `mfspr SPR_UPIR`, and `subfme`. A healthy startup smoke test should keep the process alive and show zero unsupported recompiler instructions after launch.

Star Fox Zero also hit Android native `signal 7` crashes under laser/explosion load. One crash path symbolicated around texture-cache cleanup, so the Android texture cleanup scanner now snapshots the texture list and validates that entries are still registered before dereferencing them. The later reproducible shooting crash was a `SIGBUS` alignment fault in the HLE/coreinit atomic path: do not cast emulated Wii U RAM to host `std::atomic<T>` on Android. Use locked `memory_readU32/U64` and `memory_writeU32/U64` helpers instead, and keep fixed-width guest-memory helpers `memcpy`-based so ARM never performs under-aligned host loads/stores against guest memory. Do not disable `LatteTC_HasTextureChanged()` on Android as a crash workaround; it causes lighting/texture flicker. Keep the texture hash scan enabled and alignment-safe.

The built-in Star Fox Zero USA profile is `bin/gameProfiles/default/00050000101b0400.ini`. It uses multi-core recompiler, a modestly larger thread quantum, async compile, accurate barriers on, and GX2DrawDone full sync off as the first per-game performance win. Game profiles can override `asyncCompile`, `accurateBarriers`, and `gx2DrawDoneSync`; runtime code should read these through `ActiveSettings` instead of directly from `GetConfig()` when emulation is active.

Built-in Cemu Thor Star Fox Zero cheat packs live under `bin/graphicPacks/cemuThorBuiltin`. `StarFoxZero_SuperShot` is the validated USA v16 bomb decrement patch. `StarFoxZero_InfiniteLife` uses the safer USA v16 shield/life store patch at `0x024F6A4C`, which NOPs only the current-life commit after damage math so normal hit reactions, warning UI, and mission scripts still run. Avoid returning early from the broader player damage dispatcher at `0x024F9388`; that was too invasive for mission stability.

The Star Fox Zero USA profile auto-loads `bin/controllerProfiles/CemuThor_StarFoxZero_StarFox64ish.xml` on Thor. This profile maps the physical right stick to VPAD motion aiming at 0.35 sensitivity with pitch/Y inverted, R2 to laser/charge shot, A to transform/confirm, B to smart bomb, X/Y to VPAD right-stick up/down for boost/brake, L/R to VPAD right-stick left/right for bank/barrel roll, L2 to target view, Select to recenter aim, and stick clicks to VPAD B/X for U-turn/somersault. The Star Fox-only Controller Help OSD has an `R2 fires laser` toggle that live-swaps VPAD A/ZR for users who prefer the opposite A/R2 layout. Keep boost/brake/acrobatic maneuvers on buttons so the physical right stick stays dedicated to aiming. Button-to-axis mappings depend on `ControllerBase::get_axis_value()` treating pressed non-axis buttons as a full axis press.

If Android reports `Process info.cemu.cemu_thor:EmulationProcess exited cleanly (1)`, it can still be Cemu's fatal handler exiting after writing to `log.txt`. Newer builds preserve the previous run as `/sdcard/Android/data/info.cemu.cemu_thor/files/log.previous.txt` before truncating `log.txt`, so check that file first after an unexpected reset.

## Existing Cemu Data Copy

The original Android Cemu package on the test Thor was `info.cemu.cemu`. To copy its external files into the debug Thor package:

```sh
adb shell am force-stop info.cemu.cemu_thor.debug
adb shell "run-as info.cemu.cemu_thor.debug sh -c 'cp -a /sdcard/Android/data/info.cemu.cemu/files/. /sdcard/Android/data/info.cemu.cemu_thor.debug/files/'"
```

This copies settings, keys, saves, shader cache, graphic packs, and `mlc01`. Android SAF grants do not transfer between packages, so copied `content://` game-folder permissions may fail in `cemu_thor`; direct `/storage/...` paths from the title cache can still work, and users may need to reselect their game folder inside `cemu_thor`.

## Custom Turnip Drivers

The Android custom driver screen has an opt-in Turnip download action. It can install the recommended Turnip package or list recent Turnip ZIP variants from `K11MCH1/AdrenoToolsDrivers`, `StevenMXZ/Adreno-Tools-Drivers`, and `The412Banner/Banners-Turnip` so a specific community-recommended build can be selected. Downloads install through the normal custom driver metadata validator and are selected automatically.
