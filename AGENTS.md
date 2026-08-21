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

GX2 has a conservative tracked-register cache for redundant immediate command-buffer state writes. Keep it disabled for display-list recording, restrict it to the main GX2 core, and invalidate it around context loads, raw/display-list command submission, and HLE special states. Uniform payload comparisons must remain byte-copy based rather than raw `uint32*` loads so Android ARM64 never faults on under-aligned guest pointers.

Default to correctness and stability. Risky performance toggles must stay off by default, clearly labeled, and preferably session-only from the OSD. Measure changes with Cemu logs, `adb shell dumpsys display`, KGSL counters, and repeatable game scenes before treating them as wins.

## Upstream Cemu Is Worth Mining

`https://github.com/cemu-project/Cemu` is well-written and actively developed, and this
fork is far enough behind that upstream is a standing source of both fixes and speedups.
Check it before writing anything non-trivial - the odds are good that a cleaner version
already exists there.

As of 2026-08-20 upstream `main` was **95 commits ahead** of this branch (2026-04-22 to
2026-08-18) while `sapphire/android-port` and `ssimco/android-port` had nothing new. Do
not assume the Android forks are current with upstream; they lag.

A full merge is a project rather than a routine sync. A trial merge produced **23
conflicts over 205 changed files**, concentrated exactly where this fork's identity lives:
`VulkanRenderer*` (Android/dual-screen), the emulated controllers (Star Fox gyro work),
`GameProfile` (the per-game override fields), and four `CMakeLists.txt` plus `vcpkg.json`.
The build-system churn comes from upstream's SDL2 to SDL3 migration, a vcpkg bump, wxWidgets
3.3.3 and fmt 12.1 - all of which Android disables or does not care about. Prefer
**cherry-picking focused series** over merging everything.

Worth taking, in rough value order:
- The **Latte/Vulkan performance batch** (~15 commits): shader lookup caching whole sets,
  incremental state update checking, widened fast draw conditions,
  `VK_EXT_attachment_feedback_loop`, omitting unused `FragCoordScale`, reworked interval
  tree for the vertex/uniform cache, skipping zero-size readback barriers.
- `880d2b3b` Vulkan: make `vertexPipelineStoresAndAtomics` optional - may matter on Turnip.
- Correctness: rare buffer-cache corruption, shader error-state caching, a texture copy
  edge case, a crash during title shutdown, an input button-mapping race, and
  `65a37336` ih264d colour inaccuracy **on aarch64**.

Already taken: `3a1d2573` "AArch64: Restore code size after processing jumps".

**Also check open PRs**, not just merged commits - several are directly relevant to this
fork: `#1909` "Add Android port" (SSimco), `#1992` "Add native Windows ARM64 support",
`#1759` "Vulkan: Rework inter-renderpass barrier code", `#1650` "various improvements &
cleanup for shader compilation", `#2007` "Latte: mirror small 1D-tiled render targets back
to guest memory". Upstream PRs are a preview of what will land and are often directly
adaptable.

## ARM64 Reference Manuals

`docs/reference/` holds primary-source vendor documentation so optimization claims can be checked against a manual instead of against folklore. `arm/` has the Arm Architecture Reference Manual (A-profile) plus the software optimization guides for all four core types in the Thor's Snapdragon 8 Gen 2 (1x Cortex-X3, 2x A715, 2x A710, 3x A510), `adreno/` has the Adreno/Qualcomm mobile best-practice text, and `snapdragon/` has the 8 Gen 2 product brief. Each directory has a `README.md` describing what the files are and how to re-fetch them.

The PDFs are deliberately **gitignored** (`docs/reference/**/*.pdf`) because the Arm ARM alone is 69 MB and would trip GitHub's large-file warning in a fork that syncs against upstream Cemu. Only the READMEs and notes are tracked, so a fresh clone will have the explanations but not the PDFs.

Use the right manual for the question. The Arm ARM is the architecture: what an instruction is *defined* to do, including exact NaN and saturation behavior — reach for it on correctness questions. The per-core software optimization guides are the microarchitecture: latency, throughput, and which issue pipe — reach for those on performance questions. Neither answers the other's question. `Read` cannot render these PDFs; use pypdf.

## Device Test Etiquette

The AYN Thor is a real device someone else is also using. When running tests, launch the
game yourself and **shut the emulator down when the test is finished** - do not leave a
title running:

```sh
adb shell "am start -n info.cemu.cemu_thor/info.cemu.cemu.emulation.EmulationActivity --es info.cemu.cemu_thor.LaunchPath '/storage/2664-21DE/Roms/wiiu/Star Fox Zero (USA) (En,Fr,Es).wux'"
# ... run the test ...
adb shell am force-stop info.cemu.cemu_thor
```

Note the activity class is `info.cemu.cemu.emulation.EmulationActivity` - the Kotlin
namespace stayed `info.cemu.cemu` while the application id is `info.cemu.cemu_thor`, so
`info.cemu.cemu_thor/info.cemu.cemu_thor.…` will not resolve.

If a measurement looks odd, check whether someone is holding the device: an interactive
session will open the side menu or change scenes underneath a capture and quietly invalidate
an A/B.

## No LLVM Recompiler Backend

Decided against porting an RPCS3-style LLVM JIT backend (2026-08-20). Recording the reasons
so it does not get re-proposed:

- **The bottleneck is not guest code quality.** On-device profiling put ~40% of emulator CPU
  in host-side overhead - clock reads, `OSGetTime`, mutexes, atomics - against 40.7% in the
  JIT'd guest code. A 20% codegen win would buy ~8% overall while that 40% sits untouched.
- **RPCS3's ARM64 gains came from fighting LLVM, not from having it.** Their shipped wins
  were instruction-selection fixes (ISB spin, timer scaling, `fmax`/`fmin`, `TBL`, `USHL`,
  `UDOT`, inline `CNTVCT`). PR 18816 exists *because* LLVM emitted two junk instructions;
  they filed llvm/llvm-project#200698 and shipped a workaround meanwhile. With the
  hand-written emitter here, `slw`/`srw` and the zero-offset address path were each fixed in
  minutes.
- **Espresso is the wrong guest for it.** RPCS3 needs LLVM because SPU code is vector-heavy
  with large basic blocks where cross-block optimisation pays. Espresso is 32-bit PowerPC
  with paired singles and no vector unit, so translation is close to 1:1 and IML already does
  register allocation. The hot-block profile is diffuse - top 200 of 24221 blocks is 46% -
  which favours broad instruction-selection wins, exactly what the current backend does well.
- **The compile-time model does not fit.** RPCS3's PPU LLVM is effectively whole-module AOT at
  load, cached to disk. Cemu recompiles per function on demand and has **no persistent
  recompiler cache**, so LLVM would mean multi-minute loads or heavy in-game stutter. Building
  that cache is the more valuable project and would be the prerequisite anyway.
- Plus many months of work and ~50-100MB of LLVM in the APK.

Revisit only if a profile shows guest code quality dominating with large hot blocks.

## Profiling On Device

The release build carries `<profileable android:shell="true"/>`, so simpleperf works
against release APKs. Always profile release; debug builds are dramatically slower and
give a misleading picture.

Hardware PMU events are blocked on this device, and `-p <pid>` is refused. The invocation
that works is the software clock event with `--app`:

```sh
adb shell "simpleperf record --app info.cemu.cemu_thor -e cpu-clock -f 1000 --duration 15 -o /data/local/tmp/perf.data"
adb shell "simpleperf report -i /data/local/tmp/perf.data --sort symbol"
adb shell "simpleperf report -i /data/local/tmp/perf.data --sort dso,symbol,vaddr_in_file"   # offsets within a symbol
```

The shipped `.so` is stripped; the unstripped copy is under
`src/android/app/build/intermediates/cxx/RelWithDebInfo/*/obj/arm64-v8a/`. Many symbols
still resolve without it. To find which functions call a given libc function, disassembling
the unstripped `.so` and grouping call sites by enclosing symbol is faster and more reliable
than call-graph recording:

```sh
llvm-objdump -d libCemuAndroid.so | awk '/^[0-9a-f]+ <.*>:/ {fn=$2} /clock_gettime/ {print fn}' | sort | uniq -c | sort -rn
```

**Do not trust FPS as a performance metric for CPU work.** The scenes reachable without
playing are vsync-capped at 60, and whole-process CPU time is diluted by spinning
Latte/IOSU threads - a 3 instruction to 2 improvement in the recompiler's load path
measured as 7922 vs 7897 ticks, i.e. noise. Per-symbol profiles are the sensitive
instrument. `OSSched[core=1]` is the guest main thread and the one that matters; it burns
roughly 4x the CPU of cores 0 and 2.

### The guest clock is the hot path, and it is x86-shaped

Guest code polls time constantly, so everything on that path matters. `coreinit::OSGetTime()`
measured 7.8% of all emulator CPU with a further 3.6% in its spinlock's atomic swap, and
every guest `mftb` routes through the same function.

The original design accumulates deltas into a shared 128-bit accumulator under a global
`FSpinlock`, with an `mfence` and a 128-bit division per call. That exists because x86 cannot
assume the TSC is uniform across cores. **AArch64 can**: `CNTVCT_EL0` is one monotonic counter
shared by every core, so the guest tick count is a pure function of it - no shared state, no
lock, no barrier, no division. The ARM path now computes it directly with a 32.32 fixed-point
multiply (`mul`+`umulh`), and only the timer shift factor makes it stateful; that changes
rarely and is republished by the writer through a seqlock.

Do not reintroduce a global lock here. Three emulated cores were serialising on one lock to
read a counter that is already coherent between them.

Related: `_udiv128` is not an instruction on AArch64 - `precompiled.h` implements it with
`unsigned __int128`, which lowers to a `__udivti3` libcall.

### Open: psq_st quantisation disagrees with the manual on NaN, differently per backend

From the Gekko manual (paired single load/store, quantisation algorithm): the converted value
for **+Inf or NaN is the positive-overflow saturation value** for the target type - 127, 255,
32767 or 65535. Rounding is toward zero, and type 0 (F32) passes through with no conversion
at all, denormals included.

`PPCRecompilerImlGen_EmitPSQStoreCase` implements this as `FPR_FLOAT_TO_INT` followed by
`ClampInteger`, which is correct for finite overflow but not for NaN, and the two backends
diverge:

| input | Espresso (per manual) | x64 `cvttsd2si` then clamp | AArch64 `fcvtzs` then clamp |
|---|---|---|---|
| NaN, S16 | `32767` | `-32768` | `0` |

So a NaN reaching a quantised paired-single store produces three different answers. Neither
backend matches the hardware and they do not match each other.

**Unmeasured and probably rare** - NaN in paired-single geometry data is not normal - so this
is recorded rather than fixed. A fix costs instructions on a hot path (psq_l/psq_st are ~1.7%
of the executed opcode mix), so it wants a game that actually hits it first. If it is ever
fixed, fix both backends together and add a cpu-test, or the divergence just moves.

Note the F32 case is the common one and is already optimal in structure: the GQR value is
usually known at compile time, so the recompiler emits a specialised case rather than the
multi-way branch.

### Open: half of the guest main thread is in the vdso clock read

Profiling Star Fox Zero on 2026-08-20 put **23% of all emulator CPU** (and **48% of
`OSSched[core=1]`**) in `__kernel_clock_gettime`, with 99.8% of those samples on that one
thread. Samples cluster tightly at vdso offset `0x30c`, which is the `isb`+`mrs CNTVCT_EL0`
sequence - real code at an extreme call rate, not misattribution.

**The caller is not yet identified.** The vdso has no frame pointers and DWARF cannot unwind
through it. Disassembling the binary shows the only `clock_gettime` call sites are
`LatteOverlay_RenderNotifications`, `LattePerformanceMonitor_frameEnd`, `fileCache_test`,
`LatteCP_*`, `nsysnetExport_select`, `mic_updateOnAXFrame`, curl and libusb - none of which
should be hot on a guest scheduler thread. `coreinit::OSGetTime()` was ruled out by
disassembly: it compiles to `mrs CNTVCT_EL0` directly, no libc call.

Next step is to interpose or count rather than sample - e.g. a temporary counter around
suspected call sites, or bisecting by disabling the FPS overlay, the performance monitor,
and the mic/AX path in turn. Worth chasing: it is by far the largest single item in the
profile.

## ARM64 Performance Work Derived From RPCS3

`docs/research/20260820-rpcs3-arm64-optimizations-for-cemu.md` records which of RPCS3's ARM64 optimizations transfer to Cemu, with each item cross-checked against this source tree. RPCS3's measurements were taken on an AYN Odin 2, the same Snapdragon 8 Gen 2 as the Thor, so their numbers are on our silicon. Read that document before starting new ARM64 optimization work; it also lists what was checked and deliberately rejected.

The structural limit is worth knowing up front: Cemu's guest is Espresso, a 750CL derivative with **no VMX** — its only SIMD is paired-singles. RPCS3 and Xenia both emulate PowerPC *with* AltiVec, so their vector items (`VPERM`/`TBL`, `vmaxfp`/`fmax`, `EOR3`/`BCAX`, 16-bit `SMULL` multiplies, `UDOT`/`SDOT`) have no guest-side counterpart here. SVE/SVE2 items are doubly irrelevant: Qualcomm shipped the 8 Gen 2 as ARMv9 without SVE. What transfers is host-side.

Invariants established by that work, worth not breaking:

- `_udiv128` is **not** an instruction on AArch64. `src/Common/precompiled.h` implements it with `unsigned __int128`, which lowers to a `__udivti3` software-division libcall. Keep 128-bit division off hot paths; `PPCTimer_getFromRDTSC()` takes a 64-bit fast path precisely because guest `mftb` routes through it under a process-global spinlock.
- On AArch64 `__rdtsc()` is `CNTVCT_EL0`, and its rate is exposed exactly in `CNTFRQ_EL0` (typically 19200000 on Qualcomm). Do not reintroduce frequency calibration by measurement on ARM; it costs 3 seconds of startup to approximate a number the hardware states exactly.
- Every write to a 32-bit IML GPR in `BackendAArch64` goes through a W-form instruction, so the upper half of the X alias is always zero. The one-instruction `slw`/`srw` lowering depends on this; if a 64-bit write to a 32-bit register is ever introduced, that lowering breaks.
- `g_CPUFeatures.arm` carries runtime AArch64 feature bits from `getauxval(AT_HWCAP)`. Use it rather than assuming, and rather than gating on CPU name strings — that mistake is what excluded every Qualcomm core from RPCS3's fast paths.
- The build uses `-moutline-atomics` so LSE atomics dispatch at runtime. Do not switch to `-march=...+lse` as a default; it `SIGILL`s on pre-ARMv8.1 hardware. An LSE-required build is an opt-in experiment.
- `texDataHash2` is a within-process change detector, never serialized. The AVX2, NEON, and scalar hash paths in `LatteTexture_CalculateTextureDataHash` already produce different values from each other and that is fine. Keep NEON loads byte-pointer based (`vld1q_u8`); guest texture addresses are not host-aligned.

Known open items from that pass, still unmeasured: the CP idle spin count of 80 in `LatteCommandProcessor.cpp` is a bare x86-tuned constant and should be re-derived from `CNTFRQ_EL0` (RPCS3's equivalent fix was their single largest measured win), and `fctiwz` on ARM64 returns 0 for NaN where Espresso returns `0x80000000` — write a differential test before adding any fixup, since applying a saturation fixup on the wrong architecture is exactly how RPCS3 created a crash bug.

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

`Performance` also includes a developer-only guest PPC hot-block profiler. It is off by default and instruments Android ARM64 recompiler enterable segments with a gated runtime check. Use `Profile guest PPC blocks`, run the slow/crashy scene, then tap `Dump guest profile`; dumps are written under the app files directory at `dump/recompiler/hot_blocks_YYYYMMDD_HHMMSS.txt`. The dump includes an HLE detail column for `0x0400xxxx` import stubs, so hot `unknown` blocks can be mapped back to GX2/coreinit/etc. This is a targeting tool for later Star Fox HLE/recompiler work, not a speedup by itself, and it should stay off during normal performance testing.

Star Fox Zero USA v16 has a guarded tiny-wrapper inliner in `src/Cafe/GamePatch.cpp`. It only runs for RPX hash `0x3768054d` with `prj_030` CRC `0x33864358`, verifies each target is a one-instruction wrapper followed by `blr`, then replaces direct `bl wrapper` calls with the wrapped PPC instruction before recompilation. Treat this as a measured Star Fox optimization from the hot-block profiler, not a general-purpose RPL optimizer.

Android ARM64 recompiled HLE calls bind known HLE function pointers at compile time and use a lightweight known-call wrapper instead of looking up every HLE ID at runtime. Keep the fallback path for unsupported `0xFFD0` stubs and late/null HLE entries.

When smoke-testing dual screen, `dumpsys window windows` should show a `info.cemu.cemu_thor` or `info.cemu.cemu_thor.debug` window on the presentation display while `EmulationActivity` is on the main display. On the test Thor, that was usually `mDisplayId=4` for the lower screen and `displayId=0` for the top screen; do not hard-code those IDs.

## Star Fox Zero Recompiler Smoke Test

Star Fox Zero USA (`00050000101b0400`) is a useful Android ARM64 recompiler stress test. The launch path tested on the Thor was `/storage/2664-21DE/Roms/wiiu/Star Fox Zero (USA) (En,Fr,Es).wux`.

If the game is slow or exits cleanly with status 1, check `/sdcard/Android/data/info.cemu.cemu_thor.debug/files/log.txt` or `/sdcard/Android/data/info.cemu.cemu_thor/files/log.txt` for `PPCRecompiler: Unsupported instruction`. Known ARM64 gaps fixed in this fork include indexed paired-single load/store (`psq_lx`/`psq_stx`), `ps_nabs`, `dcbzl`, `mfspr SPR_UPIR`, and `subfme`. A healthy startup smoke test should keep the process alive and show zero unsupported recompiler instructions after launch.

Star Fox Zero also hit Android native `signal 7` crashes under laser/explosion load. One crash path symbolicated around texture-cache cleanup, so the Android texture cleanup scanner now snapshots the texture list and validates that entries are still registered before dereferencing them. The later reproducible shooting crash was a `SIGBUS` alignment fault in the HLE/coreinit atomic path: do not cast emulated Wii U RAM to host `std::atomic<T>` on Android. Use locked `memory_readU32/U64` and `memory_writeU32/U64` helpers instead, and keep fixed-width guest-memory helpers `memcpy`-based so ARM never performs under-aligned host loads/stores against guest memory. Do not disable `LatteTC_HasTextureChanged()` on Android as a crash workaround; it causes lighting/texture flicker. Keep the texture hash scan enabled and alignment-safe.

The built-in Star Fox Zero USA profile is `bin/gameProfiles/default/00050000101b0400.ini`. It uses multi-core recompiler, a modestly larger thread quantum, async compile, accurate barriers on, and GX2DrawDone full sync off as the first per-game performance win. Game profiles can override `asyncCompile`, `accurateBarriers`, and `gx2DrawDoneSync`; runtime code should read these through `ActiveSettings` instead of directly from `GetConfig()` when emulation is active.

Built-in Cemu Thor Star Fox Zero cheat packs live under `bin/graphicPacks/cemuThorBuiltin`. `StarFoxZero_SuperShot` is the validated USA v16 bomb decrement patch. `StarFoxZero_InfiniteLife` uses the safer USA v16 shield/life store patch at `0x024F6A4C`, which NOPs only the current-life commit after damage math so normal hit reactions, warning UI, and mission scripts still run. Avoid returning early from the broader player damage dispatcher at `0x024F9388`; that was too invasive for mission stability.

The Star Fox Zero USA profile auto-loads `bin/controllerProfiles/CemuThor_StarFoxZero_StarFox64ish.xml` on Thor. This profile maps the physical right stick to VPAD motion aiming at 0.35 sensitivity with pitch/Y inverted, R2 to laser/charge shot, A to transform/confirm, B to smart bomb, X/Y to VPAD right-stick up/down for boost/brake, L/R to VPAD right-stick left/right for bank/barrel roll, L2 to target view, Select to recenter aim, and stick clicks to VPAD B/X for U-turn/somersault. The Star Fox-only Controller Help OSD has an `R2 fires laser` toggle that live-swaps VPAD A/ZR for users who prefer the opposite A/R2 layout, plus a `Device gyro aiming` toggle that switches between right-stick synthetic gyro and the handheld's Android device sensors. When enabling device gyro, keep the Android `Device` controller explicitly attached to VPAD 0 because profile loads can otherwise leave the right-stick gyro disabled without a live sensor source. Keep boost/brake/acrobatic maneuvers on buttons so the physical right stick stays dedicated to aiming. Button-to-axis mappings depend on `ControllerBase::get_axis_value()` treating pressed non-axis buttons as a full axis press.

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
