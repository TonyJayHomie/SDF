# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo shape — read this before assuming a normal codebase

This repo is **not a code project**. It contains:

- `READ` — a 768 KB chat transcript log. Single git commit ("Create READ").
- `.git/`

There is no source tree, no build system, no tests, no package manifest. Any binary, smali, manifest, or library you need to inspect or modify lives **outside the git tree** in the ephemeral cloud container's upload area:

- `/root/.claude/uploads/06f329f9-…/` — primary working set. Contains:
  - `simwheel_xapk_part_{1,2,3}_of_3.zip` — concatenate the `.bin` payloads → single XAPK → base `com.ik.simwheel.apk` + `config.arm64_v8a.apk` + `config.armeabi_v7a.apk` + density splits. **This is the v5.2.2 base.**
  - `5c053610-SimWheelPS4_6.apk` — v6 reference build (feature spec only; do not ship v6 bytes).
  - `f3774ac8-SimWheel_Controller_Mapping_v5_1.apk` — older "overlay" attempt (do not use as base).
  - `1f4f9a31-SimWheel_Controller_NoOverlay_signed.apk`, `60a86233-SimWheel_Controller_Onscreen_signed.apk` — earlier mod attempts (reference only).
  - `af6345fc-SimWheeel_Receiver_main.zip` — PC receiver C++ source.
  - `9f614c30-SimWheel.PC.Receiver.3.0.1.zip` — PC receiver binary.

The git working branch for shipping built artifacts is **`claude/intelligent-volta-kV9FQ`**. Final APK + matching `.idsig` belong there; never push to `main`.

## What the project actually is

The user is modifying the Play Store app **SimWheel Connect** (`com.ik.simwheel`, versionCode 29 — v5.2.2) to accept a PS4 controller paired to the phone, route the controller's analog L2/R2 and gyro to the phone's existing UDP stream, and have the PC receiver react. Reverse-engineering and surgical APK patching, not application development.

## Hard rules (stated repeatedly by the user — do not relitigate)

These are non-negotiable and not subject to "but it would be simpler if…":

1. **v5.2.2 is the ONLY base.** Do not start from v5_1, NoOverlay, Onscreen, v6, or any other build.
2. **v6 is reference only.** No v6 bytes ship in the final APK. Features get reimplemented inside v5.2.2's existing `com.ik.simwheel.*` packages.
3. **v5.2.2's Flutter gyro path stays untouched.** `libapp.so`, `dev.fluttercommunity.plus/sensors/accelerometer`, the gyro_steering/gyro_filter Dart code — do not modify. v5.2.2's gyro reads 90° as 90°; v6's reads 90° as 750°. This is why v6 is excluded.
4. **L2/R2 stay analog all the way to the PC.** No keyboard-key translation, no binary thresholds, no ACTION_DOWN/UP synthesis from a trigger value crossing 0.5.
5. **No overlay.** No `addContentView`, no transparent `FrameLayout` on top of `FlutterView`.
6. **No parallel UDP.** v5.2.2's Dart-side UDP sender is the only emitter. Controller input must reach the receiver by driving the on-screen sliders (so Dart's existing emitter picks up the new slider value), not by opening a second socket.

When these rules collide with each other (and they often do), use `AskUserQuestion` rather than picking one silently.

## Hard-won facts about the v5.2.2 binary

Several wrong turns are documented in `READ`. Future-proof these:

- **`MainActivity` extends obfuscated `Ly1/c;` which inherits from `Landroid/app/Activity;`.**
- `Ly1/c.onContentChanged` is declared **`final`** (killed v11 of an earlier attempt). Override on `MainActivity` is impossible. `Ly1/c.onPostResume` is also final.
- `dispatchKeyEvent`, `dispatchGenericMotionEvent`, `dispatchTouchEvent`, `onKeyDown`, `onKeyUp`, `onGenericMotionEvent` are **not** declared on `Ly1/c`. Safe to override on `MainActivity` — but `invoke-super` must target `Landroid/app/Activity;->…`, **not** `Ly1/c;->…`, or you get `NoSuchMethodError` at runtime (smali assembles fine; this is a verifier failure).
- `AndroidManifest.xml` carries `android:requiredSplitTypes="base__abi,base__density"`. A standalone (non-Play-Store-split) install fails with `INSTALL_FAILED_MISSING_SPLIT` unless this is blanked. Edit the **binary** manifest in place — do not apktool-rebuild it (see next point).
- **Do not apktool-rebuild resources.** apktool's `apktool b` re-codes `resources.arsc` and silently downgrades `compileSdkVersion` from 35 → 33, and libapp.so has hardcoded resource IDs that will shift. Use surgical `zip -j` replacement of `classes.dex` + `AndroidManifest.xml`. `resources.arsc` MD5 must equal the Play Store baseline.
- **PairIP licensing must be neutered** after re-signing or the app crashes on launch: `LicenseContentProvider.onCreate` → `const/4 v0,0x1; return v0`; `LicenseClient.checkLicense` → `return-void`. Touch nothing else PairIP-related.
- **Native libs must be Stored (no compression) AND 4-byte page-aligned** since the manifest declares `extractNativeLibs=false`. Verify with `unzip -lv | grep lib/` (method=Stored, 0%) and `zipalign -c -p 4 -v`.
- **Signing:** v1+v2+v3 with `apksigner`. Keystore at `~/.android/sdf.keystore` if present.

## PC receiver protocol — what UDP packets must look like

Receiver source: `SimWheeel_Receiver/Receiver.cpp`. Listens UDP/JSON on port **4567**. Hard requirements observed from the actual parser:

- **`dx` and `dy` are required keys** for any non-discover packet. The receiver calls `j.at("dx").get<double>()` which throws when missing. Send `dx:0, dy:0` if you have no mouse data.
- **Clutch key is `"clutch "` with a trailing space.** Not `"clutch"`. A historic typo that is now part of the wire protocol.
- `zaxis` is optional but if absent, `dx`/`dy` must be present.
- Discovery uses `discover` / `discover_reply`. Other recognized fields include `phoneName`, `steering`, `throttle`, `brake`, `horn`, `reverse`, `drive`, `parking`, `praking` (sic), `light`, `indL`, `indR`, numeric button keys `1..8`.
- A receiver-side fix exists in `READ` (search "receiver_minimal_patch.diff") that accepts the trailing-space-less `clutch`, allows `throttle`/`brake` without `steering`, and tolerates missing `zaxis` when `dx`/`dy` are also missing. Apply only if the user explicitly asks for receiver changes.

## How to make controller analog reach the PC (the architecture)

Given the hard rules above, the only working architecture is:

1. Override `MainActivity.dispatchKeyEvent` + `dispatchGenericMotionEvent` to capture gamepad source events.
2. Hand them to a controller-input class living in `com.ik.simwheel.controller.*` (new package; not the `com.sdf.simwheelps4.*` namespace v6 uses).
3. Apply dead-zone/curve/range from a Settings store, producing analog floats.
4. Inject `MotionEvent` ACTION_DOWN/MOVE/UP via `Activity.dispatchTouchEvent` at coordinates the user calibrated for the on-screen throttle/brake sliders — Flutter sliders move, the existing Dart UDP sender packetizes them with all the fields the receiver demands (including `dx:0, dy:0, "clutch "`).
5. Re-entrancy guard so injected touches don't loop back through the override.

A `Calibration` and `Mapping` activity are added programmatically (no XML layouts, no string changes — `resources.arsc` must stay byte-identical).

## Commands

There is no project-level toolchain configured. The build steps documented above run from the cloud container using `apktool`, `baksmali`/`smali`, `zipalign`, `apksigner`, `aapt2`, and `dexdump`. The user has approved running these as needed in plan execution — do not invent makefile-style commands.

Verification commands worth keeping in muscle memory after every build:

- `apksigner verify --verbose <out.apk>` — must report v1 + v2 + v3 PASS
- `aapt2 dump xmltree <out.apk> --file AndroidManifest.xml | grep -E "requiredSplitTypes|<activity"`
- `unzip -lv <out.apk> | grep lib/` — every `.so` Stored, 0%
- `md5sum <(unzip -p <out.apk> resources.arsc) <(unzip -p <play-store-base.apk> resources.arsc)` — must match
- `baksmali list classes <out.apk>` — confirm new package is present and v6's `com/sdf/simwheelps4/*` is absent

## Working style for this repo

- The user runs into long-tail Android bugs (final-method override, requiredSplitTypes, hardcoded receiver keys, `clutch ` typo) and has burnt many sessions on them. Surface these failure modes proactively rather than rediscovering them.
- Don't claim a build is tested if you didn't actually install it on an emulator and exercise the UI. Say "built but not runtime-verified" instead.
- Read the full instruction before acting — the user's messages frequently contain corrections to earlier statements in the same message.
- Treat "VERBATIM" and "NOT A SINGLE DEVIATION" as binding. When two such directives conflict, ask.
