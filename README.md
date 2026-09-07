# Cemu for AYN Thor Experiment

![Cemu for AYN Thor Experiment banner](docs/assets/cemu-thor-experiment-banner.png)

A personal Android fork of [Cemu](https://github.com/cemu-project/Cemu) aimed at one
device: the AYN Thor dual-screen handheld (Snapdragon 8 Gen 2, Adreno 740). It is built
with AI assistance, tested on one Thor, and tuned around Star Fox Zero. It is messy,
practical, and unsupported.

**No support, no issue tracker.** There is no guarantee of stability, correctness,
compatibility, or performance. If that is a problem, use upstream Cemu or another Android
fork. Otherwise: fork it, patch it, break it, fix it.

This repository contains no games, keys, firmware dumps, system files, or copyrighted
game assets.

## At a glance

| | |
|---|---|
| Target device | AYN Thor Base / Pro / Max (Snapdragon 8 Gen 2, Adreno 740). Thor Lite is not a target. |
| Platform | Android, ARM64 only, Vulkan only |
| Package id | `info.cemu.cemu_thor` (release), `info.cemu.cemu_thor.debug` (debug) |
| Branch | `android-port` is the main branch of this fork |
| Upstream base | Tracks upstream Cemu by selective cherry-pick, not full merges; last sync 2026-09-07 |
| Working guide | [AGENTS.md](AGENTS.md) holds the build, device, profiling, and design notes. `CLAUDE.md` points to it. |

Thor Base, Pro, and Max share the same CPU and GPU, so they share the same emulator code
paths. The extra RAM and storage on Pro/Max is headroom for caches and libraries, not a
reason for different scheduling or renderer behaviour.

## Screenshots

![Star Fox Zero running on Cemu for AYN Thor Experiment](docs/screenshots/starfox-running.png)

![Star Fox Zero OSD Controller Help](docs/screenshots/starfox-osd-controller-help.png)

## What this fork changes

**Dual screen.** The GamePad screen can render on the Thor's lower display through an
Android presentation window, not just as a second view inside the main activity. The
in-game menu has `External PAD screen`, `Swap screens`, and `Rotate external screen left`.
Dual-screen presentation tracks the previous command buffer per swapchain so the two
presents overlap on the GPU. This builds on SapphireRhodonite's dual-screen work.

**In-game menu.** A two-panel side drawer with `Display`, `Performance`, `Audio`,
`Controls`, and `Tools` sections. It has an FPS overlay toggle, async shader compile,
GamePad audio and volume, a PAD render-scale slider for the secondary surface, and
session-only risky speed toggles (skip GX2DrawDone sync, skip accurate Vulkan barriers)
that are off by default and never persisted. The Android Back key opens the menu.

**Snapdragon and ARM64 performance.** Android performance-hint (ADPF) sessions for the
scheduler threads, fixed 60 Hz surface hints, a lock-free guest clock on AArch64,
striped locks in the coreinit atomic HLE, a redundant-state cache for GX2 register writes,
NEON texture hashing, runtime AArch64 feature detection, and a stream of AArch64
recompiler backend improvements (logical immediates, redundant compare elimination,
single-instruction shifts, compile-time bound HLE calls). See the performance section
below for what has been measured.

**Star Fox Zero.** A built-in USA v16 game profile with per-game performance defaults, a
controller profile that keeps the physical right stick for gyro-style aiming, an OSD
Controller Help page with live `R2 fires laser` and `Device gyro aiming` toggles, a guarded
tiny-wrapper inliner for the game's RPX, and built-in Infinite Life and Super Shot cheat
packs.

**Drivers.** An opt-in Turnip driver downloader in the custom driver screen that can install
recent community Turnip builds for A/B testing against the stock Adreno driver.

**Recompiler and crash fixes.** AArch64 recompiler coverage for instructions the
Android port was missing (`psq_lx`/`psq_stx`, `ps_nabs`, `dcbzl`, `subfme`, and others),
alignment-safe guest memory access so ARM never faults on under-aligned host loads, and
texture-cache cleanup fixes found under Star Fox laser and explosion load.

**Branding.** A separate package id, icon, name, screenshots, and docs so this build is
visibly not upstream Cemu.

## Star Fox Zero controls

The Thor is not a Wii U GamePad, so the bundled profile is opinionated:

| Input | Action |
|---|---|
| Right stick | Gyro-style cockpit aim |
| R2 | Laser / charge shot |
| A | Transform / confirm |
| B | Smart bomb |
| X / Y | Boost / brake (emulated right-stick up/down) |
| L / R | Bank / barrel roll (emulated right-stick left/right) |
| L2 | Target view |
| Select | Recenter aim |
| L3 / R3 | U-turn / somersault |

The Controller Help OSD can swap A and R2, and can switch aiming from the right stick to
the Thor's motion sensors.

## Building

```sh
git clone git@github.com:noeldvictor/Cemu-thor-experiment.git
cd Cemu-thor-experiment
git submodule update --init --recursive
cd src/android
./gradlew.bat :app:assembleRelease
```

The release APK lands at `src/android/app/build/outputs/apk/release/app-release.apk`.
Install with `adb install -r`. Use release builds for anything performance related. Debug
builds are dramatically slower and exist for UI and smoke testing only.

The Android project is Vulkan-only (`-DENABLE_OPENGL=OFF`) and builds `arm64-v8a` only.
The Kotlin/JNI namespace stays `info.cemu.cemu` so native bindings match the Android port
this fork tracks.

## Performance work

Measured on the Thor, Star Fox Zero loading phase, release build, three runs each
(details and method in [AGENTS.md](AGENTS.md)):

| | before | after |
|---|---|---|
| CPU over 20 s of loading | 3583 / 3619 / 3641 ticks | 2526 / 2549 / 2489 ticks (-30%) |
| Startup, init to title running | 3.76 s | 0.86 s |

Primary-source manuals used to check this work live under `docs/reference/`: the Arm
Architecture Reference Manual, the Cortex-X3 / A715 / A710 / A510 software optimisation
guides, Qualcomm's Adreno best-practice guide, and the PowerPC 750CL and Gekko manuals.
The PDFs are gitignored; each directory's README says how to fetch them.
`docs/research/` records which optimisations from other ARM64 emulators transfer to Cemu
and which were checked and rejected.

## Relationship to upstream

Upstream Cemu is actively developed and this fork is well behind its `main`. Rather than a
full merge, which conflicts exactly where this fork's identity lives (Vulkan dual-screen,
emulated controllers, game profiles, build system), upstream fixes and speedups are
cherry-picked in focused series. The build-system churn upstream (SDL3, wxWidgets, fmt)
is not taken because Android disables those components.

## Credits

- [Cemu](https://github.com/cemu-project/Cemu), the Wii U emulator this is built on.
- [SSimco/Cemu](https://github.com/SSimco/Cemu), the Android port this branch tracks.
- [SapphireRhodonite/Cemu](https://github.com/SapphireRhodonite/Cemu), the Android
  dual-screen work that made a Thor build worth attempting.

## License

Cemu is licensed under the [Mozilla Public License 2.0](LICENSE.txt). Dependency and
source-file exceptions follow the upstream license notes. This fork's branding, docs, and
screenshots are part of the experiment; the emulator code remains under the applicable
upstream licenses.
