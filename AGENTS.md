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

The app currently builds ARM64 only via `abiFilters("arm64-v8a")`. The Android application ID is `info.cemu.cemu_thor`; the Kotlin/JNI namespace remains `info.cemu.cemu` to avoid unnecessary native binding churn.

On Windows, prefer building from a path without spaces. A junction such as `C:\Users\leanerdesigner\Documents\Cemu_thor_build` pointing to the checkout works; vcpkg/autotools packages can fail when the physical build path contains spaces.

## Device Install

Use `adb devices` to confirm the AYN Thor is connected, then install the APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Custom Turnip Drivers

The Android custom driver screen has an opt-in Turnip download action. It can install the recommended Turnip package or list recent Turnip ZIP variants from `K11MCH1/AdrenoToolsDrivers` so a specific community-recommended build can be selected. Downloads install through the normal custom driver metadata validator and are selected automatically.
