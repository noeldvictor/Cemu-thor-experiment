# AGENTS.md

## Project

This checkout is `noeldvictor/Cemu_thor`, an Android-focused Cemu fork. The default fork branch is `android-port`.

## Remotes

- `origin`: `git@github.com:noeldvictor/Cemu_thor.git`
- `sapphire`: `git@github.com:SapphireRhodonite/Cemu.git`
- `ssimco`: `git@github.com:SSimco/Cemu.git`
- `upstream`: `git@github.com:cemu-project/Cemu.git`

## Update Workflow

Fetch all remotes before comparing branches:

```sh
git fetch --all --prune
```

As of this update, `origin/android-port` was identical to `sapphire/android-port` and fast-forwarded cleanly to `ssimco/android-port`. Prefer `git rev-list --left-right --count origin/android-port...ssimco/android-port` before merging so the update direction is explicit.

Use a `codex/` branch for agent work. Do not reset, checkout, or revert user changes unless the user explicitly asks for that.

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

On Windows, prefer building from a path without spaces. A junction such as `C:\Users\leanerdesigner\Documents\Cemu_thor_build` pointing to the checkout works; vcpkg/autotools packages can fail when the physical build path contains spaces.

## Device Install

Use `adb devices` to confirm the AYN Thor is connected, then install the APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The local named debug APK copy used during Thor testing is:

```sh
C:\Users\leanerdesigner\Documents\Cemu_thor_build\src\android\app\build\outputs\apk\debug\cemu_thor-debug.apk
```

It is also useful to push a copy to the device:

```sh
adb push cemu_thor-debug.apk /sdcard/Download/cemu_thor-debug.apk
```

## AYN Thor Dual Screen

The Thor has two Android displays. During testing, the top/main display appeared as `displayId=0`, and the lower/presentation screen appeared as `displayId=4` with `FLAG_PRESENTATION`.

Keep the Sapphire dual-screen presentation work intact. The PAD screen should be able to render through `PadPresentation` on the external/presentation display, not merely as a second `SurfaceView` inside the main activity. The emulation side menu includes `External PAD screen`, `Swap screens`, and `Rotate external screen left`; PAD visible and external PAD default to enabled for this Thor-focused build.

The Android Back key is intentionally mapped to the same in-game menu toggle as the hotkey action, and predictive back callbacks are enabled in the Android manifest. The emulation side menu also includes a `Show FPS` toggle; enabling it turns on Cemu's native overlay at the top-left corner when the overlay was previously disabled and clears non-FPS overlay stats so copied desktop settings do not unexpectedly show CPU/RAM/debug overlays during gameplay.

When smoke-testing dual screen, `dumpsys window windows` should show a `info.cemu.cemu_thor.debug` window on `mDisplayId=4` while `EmulationActivity` is on `displayId=0`.

## Existing Cemu Data Copy

The original Android Cemu package on the test Thor was `info.cemu.cemu`. To copy its external files into the debug Thor package:

```sh
adb shell am force-stop info.cemu.cemu_thor.debug
adb shell "run-as info.cemu.cemu_thor.debug sh -c 'cp -a /sdcard/Android/data/info.cemu.cemu/files/. /sdcard/Android/data/info.cemu.cemu_thor.debug/files/'"
```

This copies settings, keys, saves, shader cache, graphic packs, and `mlc01`. Android SAF grants do not transfer between packages, so copied `content://` game-folder permissions may fail in `cemu_thor`; direct `/storage/...` paths from the title cache can still work, and users may need to reselect their game folder inside `cemu_thor`.

## Custom Turnip Drivers

The Android custom driver screen has an opt-in Turnip download action. It can install the recommended Turnip package or list recent Turnip ZIP variants from `K11MCH1/AdrenoToolsDrivers` so a specific community-recommended build can be selected. Downloads install through the normal custom driver metadata validator and are selected automatically.
