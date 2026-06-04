NeoTerm
=======
![](https://img.shields.io/badge/language-Kotlin-green.svg)
![](https://img.shields.io/badge/license-GPLv3-000000.svg)
![](https://img.shields.io/badge/arch-arm64--v8a-blue.svg)

A modern-designed Android terminal emulator — now with a full **proot Linux
distro runtime**, an **embedded X11 server**, and **native Android audio**, all
in a single APK.

### Our Pledge

Originally, NeoTerm was designed as a front end for Termux. This fork grows it
into a self-contained Linux environment on Android: run a real distro (apt/apk/
pacman), launch graphical apps on a built-in X server, and get sound in and out
— without rooting the device or installing any companion app.

---

## Highlights

- **Self-contained Linux distros in proot** — no root required.
- **Embedded X11 server** (Termux:X11 built into the APK) for graphical apps.
- **Native Android audio** — output *and* microphone via a bundled PulseAudio.
- **Single APK** — proot binary, X server, and audio server are all baked in.
- A modern, multi-tab terminal UI with extra keys, color schemes and fonts.

> **Architecture:** the Linux runtime requires **arm64-v8a (aarch64)**. The
> terminal UI itself runs on Android 8.0+ (minSdk 26).

---

## Linux distro runtime (proot)

NeoTerm ships a bundled `proot` and can install and run full Linux root
filesystems entirely in app-private storage:

| Distro            | Package manager |
|-------------------|-----------------|
| Ubuntu 24.04 LTS  | `apt`           |
| Kali Linux (rolling) | `apt`        |
| Alpine Linux 3.20 | `apk`           |
| Arch Linux        | `pacman`        |

- Runs under proot (ptrace-based fake chroot): a fake `root` (uid/gid 0) inside,
  no real namespaces or device root needed.
- Kernel/pseudo filesystems (`/dev`, `/proc`, `/sys`, `/dev/pts`, …) are bound
  into the guest, plus a writable `/dev/shm` (needed by browsers and many apps).
- External storage is mapped to `/sdcard` when available.
- Package operations (update / upgrade / install / search) are exposed through
  the UI and mapped to each distro's native package manager.

## Graphical apps — embedded X11

The X server is **built into NeoTerm** (the Termux:X11 native server runs
in its own process); there is **no separate app to install**.

- Start/stop the X server from NeoTerm; its status is shown in NeoTerm's own
  notification (no second notification).
- Guest sessions automatically export `DISPLAY=:0`, so GUI apps just work.
- A lightweight stack (xterm, openbox, fonts, xkb data) installs with one tap
  via the built-in "X11 environment" setup.
- Sensible defaults are pre-set for running desktop apps under proot
  (e.g. browser sandbox flags, no xdg-desktop-portal stalls).

## Audio — output and microphone

Audio is provided by a **PulseAudio cross-built for Android** and bundled in the
APK. It runs as the app (no root, no proot) and is reachable from the distro via
`PULSE_SERVER=127.0.0.1:4713`.

- **Output:** a callback-clocked **AAudio sink** (`neoterm`) — smooth, drift-free
  playback for both terminal and X11 apps. Audio starts with the app, so even
  plain CLI tools (mpv, `paplay`, …) have sound.
- **Microphone (opt-in):** an **AAudio source** (`neoterm_mic`) captures the
  device mic for recording apps in the distro (`parecord`, browser WebRTC, …).
  - Toggle it under **Settings → General → Microphone** (off by default).
  - Enabling it requests the `RECORD_AUDIO` permission and loads the source
    live; disabling unloads it — no app restart needed.
  - Tuned for usable loudness out of the box (VOICE_RECOGNITION preset + a
    default software gain); fine-tune live with `pactl set-source-volume`.

## Terminal features

- Multiple sessions in tabs, swipe to switch, full-screen mode.
- Customizable extra-keys row, color schemes and fonts.
- **Volume keys as special keys** (optional): Volume Down → Ctrl, Volume Up → Fn
  (Settings → General).
- Word-based or character-based IME, auto-completion, back-key-as-Escape, and
  more in Settings.

## Building

The release APK is produced by CI (`.github/workflows/build-neoterm.yml`), which
also cross-compiles the bundled proot binary and the Android PulseAudio (see
`audio/build-pulseaudio.sh`). The distro root filesystems and the proot binary
are published as release assets and downloaded on demand by the app.

### Help & Documentation

View on [GitBook](https://neoterm.gitbooks.io/neoterm-wiki/content) ·
View on [GitHub](https://github.com/NeoTerm/NeoTerm-Wiki)

### License

GPLv3.
