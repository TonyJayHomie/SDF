# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository actually contains

This repo is **not** the SimWheel Android/Flutter app. It contains:

- `SimWheeel_Receiver_-main.zip` — the **Windows PC-side receiver** for the "SimWheel Connect" app (Google Play `com.ik.simwheel`). Written in C++ (Visual Studio 2022, `SimWheeel_Receiver.sln` + `SimWheeel_Receiver.vcxproj`). Single translation unit: `SimWheeel_Receiver/Receiver.cpp` (~780 lines).
- `READ` — a large text dump (the original `READ.md`/notes copy). Not source.

The Android app referenced on the Play Store badge in the upstream `README.md` is built with **Flutter** and is **closed-source** — its source is **not in this repo**. Do not attempt to "edit the existing APK": there is no Android source tree here to edit, and the published APK belongs to a third-party developer (decompile/repackage is out of scope and a copyright issue). If the task requires Android changes, write a new project from scratch in a sibling folder and be explicit that it is a separate app.

## How the system works end-to-end (read this before changing anything)

The phone is the master; the PC is a dumb input sink:

1. Phone broadcasts a UDP JSON `{"type":"discover","phoneName":...}` packet.
2. PC binds **UDP port 4567** (`Receiver.cpp`, hard-coded), parses the JSON with nlohmann/json, and replies with `{"type":"discover_reply","name":<computer name>,"connection":<wifi|ethernet>}`.
3. First time an IP sends a control packet, the PC prompts the operator (`y/n`) at the console; the IP is then kept in `allowedIPs` or `blockedIPs` for the process lifetime (in-memory only, not persisted).
4. Control packets are JSON objects keyed by what the phone is sending. The receiver looks at fields **by name**, removes them from `j`, and falls through to whatever is left:
   - `steering` (degrees, divided by `userRange` for vJoy X), `throttle`, `brake` → vJoy axes X/Y/Z via `MapToVJoyAxis()`.
   - `clutch ` (yes, with the trailing space — match it exactly) → vJoy RX.
   - `zaxis` → vJoy RZ; **if `zaxis` is absent**, the code reads `dx`/`dy` and drives the mouse instead. Keep this branch behavior when adding fields.
   - Buttons / keyboard come through the `customKeyToVK()` mapping: 200–225 = A–Z, 300–309 = 0–9, 400–411 = F1–F12, plus special ranges; 500/501/503 are left/right/middle mouse handled by `MouseClick()`.
5. vJoy device id is **hard-coded to 1** (`UINT vJoyId = 1`). The program will refuse to start if vJoy isn't enabled / device 1 isn't configured / not owned by this process — the error messages tell the user what to click in `vJoyConf`.

`g_Dash` is a global struct used by `UpdateDashboard()` to redraw the console UI; updating a field flips `g_Dash.initialized` to force a redraw.

## Build / run

The repo's source is delivered as a zip; extract before working on it:

```
unzip -o SimWheeel_Receiver_-main.zip -d <workdir>
```

The C++ project is Windows-only. It depends on:
- vJoy SDK (`vJoyInterface.h`, links `vJoyInterface.lib`) — install vJoy and point the project's include/lib dirs at the SDK.
- nlohmann/json (header-only, `<nlohmann/json.hpp>`).
- Winsock2, iphlpapi (pragma-linked in source).

Build from a Windows machine with Visual Studio 2022:
```
msbuild SimWheeel_Receiver.sln /p:Configuration=Release /p:Platform=x64
```
or open `SimWheeel_Receiver.sln` in the IDE. There are **no tests, no linter config, no CI** in this repo.

Runtime: needs vJoy installed, Device 1 enabled in `vJoyConf`, and the listening port (UDP 4567) reachable from the phone. The PC console prompts (`y/n`) for the first packet from each new IP — running it headless / piping stdin will hang on that prompt.

## Things to be careful about when editing `Receiver.cpp`

- Don't change UDP port 4567 or the JSON field names without coordinating with the phone app — the phone app is closed-source and shipped, so the wire protocol is effectively frozen.
- `j.erase(...)` calls matter: the `dx`/`dy` mouse branch only runs when `zaxis` is absent. Adding a new optional field means erasing it before that fall-through.
- `clutch ` has a trailing space — preserve it.
- `MapToVJoyAxis` expects normalized `[-1,1]`; steering uses `steering / userRange` instead. Don't unify these without checking how the phone formats the steering value (degrees up to ~2520).
- vJoy `SetBtn` / `SetAxis` failures are silent in some paths — if you add new outputs, log on first failure to make field debugging possible.

## Working branch

Active development branch in this clone is `claude/intelligent-volta-kV9FQ` (per repo setup instructions). Push there, not `main`.
