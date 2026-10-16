# OpenCode Pocket

[![CI](https://github.com/evilqin/opencode-pocket/actions/workflows/android.yml/badge.svg)](https://github.com/evilqin/opencode-pocket/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/evilqin/opencode-pocket?display_name=tag)](https://github.com/evilqin/opencode-pocket/releases)
[![Downloads](https://img.shields.io/github/downloads/evilqin/opencode-pocket/total)](https://github.com/evilqin/opencode-pocket/releases)
[![License](https://img.shields.io/github/license/evilqin/opencode-pocket)](https://github.com/evilqin/opencode-pocket/blob/main/LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2011%2B%20%C2%B7%20arm64-3DDC84)](https://github.com/evilqin/opencode-pocket)

> [简体中文](README.md) | **English**

Run the official [opencode](https://github.com/anomalyco/opencode) coding agent on your phone — inside a self-contained **proot + Ubuntu** container, driven by a native Android app.
**No root. No Termux. No PC.** Install, tap, chat.

> The container recipe is adapted from [DSHA (DeepSeek Harness Android launcher)](https://github.com/qiannianhuanxiang/DSHA):
> Termux's official proot binaries shipped inside the APK, the rootfs unpacked into app-private storage,
> mirror speed-tests, and a retryable step-by-step installer. This project applies the same one-tap
> experience to opencode.

---

## What it is

- **A launcher** — bundled `proot` (Termux official aarch64 build) plus a one-tap Ubuntu 24.04 rootfs installer.
- **A native client** — the chat screen talks straight to the in-container opencode server (REST + SSE):
  streaming replies, tool-call cards, permission prompts. You can also open the official Web UI.
- **A terminal** — run anything inside the container, inspect install and runtime logs.
- **Private by design** — every download uses **a mirror you pick**, API keys are written only to the app's
  private storage, zero telemetry.

---

## Features

| Module | Description |
| --- | --- |
| One-tap install | 4-step pipeline: runtime → Ubuntu rootfs → base tools (apt) → OpenCode itself; each step is retryable and resumable |
| Mirror speed test | 4 kinds of mirrors (Ubuntu / apt / Node / npm) tested with **real file** Range requests (latency + throughput), fastest wins |
| Service management | Foreground service with status + "Stop" action in the notification; health polling via `/global/health` |
| Native chat | Session list, streaming replies, collapsible reasoning, tool-call cards, permission prompts (allow once / always / deny), model & agent switching |
| Changes view | From the chat screen: files touched by the agent with `+/-` stats and a locally computed unified diff (copyable) |
| Web UI | Built-in WebView for the official `opencode web` interface; can hand off to an external browser |
| Terminal | Shell-like command execution inside the container (cwd persists), quick commands; install log & runtime log, copyable and shareable |
| Settings | API keys for 12 popular providers, default model, port, permission policy, low-memory mode, workspace, theme, maintenance tools |
| Workspace | A real folder on your phone (default `/sdcard/OpenCode`) is bind-mounted to `/root/workspace`; shared storage is **not** mounted by default |
| Doctor | 12 read-only checks (architecture / runtime / rootfs / git / opencode / DNS / storage / ports / keys / permissions …), one-tap copy of the report |
| Device shell bridge | Optional, via **Shizuku**: the in-container agent can run commands on the phone itself with `dexec` — with a dangerous-command guard and an audit log |
| Secure defaults | Random local-server password (`OPENCODE_SERVER_PASSWORD`), `share: disabled`, `autoupdate: false`, permission policy defaults to `ask`, keys only in app-private storage, no telemetry |

---

## Requirements

- Android **11 (API 30)** or newer, **arm64-v8a** device (no x86 emulators — the bundled proot and rootfs are aarch64).
- ~**1.5 GB** free storage for the first install (2 GB recommended).
- No root required. Optional: [Shizuku](https://shizuku.rikka.app/) if you want the device-shell bridge.

---

## Install

### Option 1: Grab the APK

1. Download the APK from [Releases](https://github.com/evilqin/opencode-pocket/releases).
2. Install it, open the app → "Install" → pick a mirror → wait ~5 minutes.

> Three builds are published (current: 1.4.0):
> `*-arm64.apk` — standard, unminified, most stable (recommended);
> `*-arm64-slim.apk` — R8-minified, ~4 MB, needs an on-device smoke test;
> `*-debug.apk` — debug build, most verbose logs.

### Option 2: Build from source

```bash
git clone https://github.com/evilqin/opencode-pocket.git && cd opencode-pocket

# 1) fetch the bundled proot runtime (Termux official packages, ~400 KB)
node tools/fetch-boot-runtime.mjs

# 2) unit tests + build
./gradlew :app:testDebugUnitTest    # 161 pure-logic tests (parsing / config / versions / tar safety / diff / proot args / command guard)
./gradlew :app:assembleDebug        # output: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # debug-signed by default, installable as-is

# optional: R8-minified build (~4 MB — code is rewritten, always smoke-test on a real device)
./gradlew :app:assembleRelease "-Ppocket.minify=true"

# optional: run the entire container recipe on Linux (CI runs this too, see below)
bash tools/ci-runtime-smoke.sh
```

**Releasing a new version**: bump `versionCode` / `versionName` in `app/build.gradle.kts`, push, then push a matching tag — CI verifies `tag == versionName`, **requires the container smoke test to pass**, builds and publishes the release automatically:

```bash
git tag v1.4.1 && git push origin v1.4.1
```

Environment: JDK 21+, Android SDK (platform 37 + build-tools 36.0.0), Node 20+ (only for helper scripts).
The GitHub Actions workflow builds on every push to `main` too — artifacts are in the run's Artifacts section.

Verify a build (bundled ELFs untouched, arm64-only, container scripts present):

```bash
node tools/verify-apk.mjs app/build/outputs/apk/release/app-release.apk
# [verify-apk] ok: 4 native libs byte-identical, scripts present, ABI=arm64-v8a
```

> Slow network? If the official Gradle distribution URL is unreachable, swap `distributionUrl` in
> `gradle/wrapper/gradle-wrapper.properties` for a mirror (remember to update `distributionSha256Sum`).

---

## First run

1. **Install the environment** (Setup tab)
   - Tap "Test all mirrors" to pick the fastest one;
   - Tap "Install" and keep the app in the foreground;
   - Both downloads (rootfs ~30 MB, opencode ~90 MB) support resume — just tap again if interrupted.
2. **Configure a model** (Settings → Models & keys)
   - Paste an API key for any provider (DeepSeek / Anthropic / OpenAI / OpenRouter / …);
   - Optionally set a default model as `provider/model`, e.g. `deepseek/deepseek-chat`;
   - Tap "Save & restart service".
3. **Chat** (Chat tab) or **open the Web UI** (Home → Open Web UI).
4. **Workspace** — `/sdcard/OpenCode` is mounted into the container by default. Grant "All files access"
   on the Home screen, or switch to the internal workspace (Settings → Workspace).

---

## How it works

```
┌─────────────────────────── Android App (Kotlin + Compose) ─────────────────────────────┐
│  Home / Chat / Terminal / Settings      Native client: OkHttp + SSE ←→ 127.0.0.1:4096  │
│  ServerService (foreground service: notification status + stop action, partial wakelock)│
│  Installer (step pipeline)  Repos (speed test / version discovery)  Downloader  TarExtract│
└───────────────┬────────────────────────────────────────────────────────────────────────┘
                │ ProcessBuilder (LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR)
        ┌───────▼────────┐        nativeLibraryDir (the only executable dir)
        │  proot + loader │◄────── libproot.so / libprootloader.so (bundled in APK)
        └───────┬────────┘
                │ fake root(-0) + binds (/dev /proc /sys /system /apex /sdcard ↔ /root/workspace)
     ┌──────────▼──────────┐
     │ files/linux/ubuntu  │  Ubuntu 24.04 arm64 (rootfs fetched & extracted at install time)
     │  /usr/local/bin/opencode  ← official opencode-linux-arm64 (from an npm mirror tarball)
     └──────────┬──────────┘
                │ opencode web --hostname 127.0.0.1 --port 4096
     ┌──────────▼──────────┐
     │ HTTP API + Web UI   │  the native chat tab and the WebView share the same port
     └─────────────────────┘
```

Notes from the trenches (things that bit us):

- **Android 10+ W^X** — app-private directories are non-executable. proot and its loader therefore ship as
  `lib*.so` in `jniLibs`, extracted by the system into `nativeLibraryDir` to be executed; guest programs use
  proot's userland exec (`PROOT_LOADER`), never the kernel's `execve` check.
- **SONAME mismatch** — `libtalloc.so.2` can't go into the APK directly (AGP only accepts `*.so`). Before
  launch we copy `libtalloc.so` → `files/linux/lib/libtalloc.so.2`, `libandroidshmem.so` →
  `libandroid-shmem.so` for the dynamic linker.
- **DNS** — Android has no `/etc/resolv.conf`; before each install/launch we read DNS servers from
  `ConnectivityManager` and write them into the rootfs.
- **Hard links** — the app-private dir (f2fs/ext4) supports hard links; `--link2symlink` is only enabled
  when probing fails.
- **apt inside proot** — `APT::Sandbox::User=root`, otherwise apt tries to switch to `_apt` and fails;
  sources are rewritten to a classic `sources.list` (deb822 removed to avoid double definitions).
- **Leave the system alone** — `--kill-on-exit` takes the whole process tree down with proot. Stopping the
  service kills the exact PID recorded inside the container (**never** `pkill -f opencode` — the package
  name contains "opencode", you'd kill the app itself).
- **Long options only** — builds differ in what they accept (Ubuntu's proot 5.1.0 doesn't know `-L`), so we
  use `--sysvipc`, `--bind=…` etc., and CI scans the bundled binary to re-verify every option we rely on.
- **Device shell bridge** — on Android 10+ a normal app has no shell identity and can't run system commands,
  so we use Shizuku's "user service": Shizuku loads our APK's `DeviceShellUserService` (**must extend the
  AIDL-generated `Stub`** and implement the reserved transaction `destroy() = 16777114`) into its shell/root
  process; the app talks to it over Binder. Inside the container, `dexec` (curl + random token +
  `X-Dexec-Timeout-Ms`) hits the local HTTP bridge on `127.0.0.1:3090`.
- **File safety** — tar extraction validates entry names and the **real path of parent directories**
  (symlink-escape guard); downloads verify upstream checksums where available (Ubuntu `SHA256SUMS`,
  Node `SHASUMS256.txt`, npm `dist.shasum`); deletions and size walks never follow symlinks.

---

## Verification status

What is already proven on a dev machine (Windows + JDK 25 + Gradle 9.5 + AGP 9.3.3):

- **161 JVM unit tests**, all green — server JSON parsing (samples written from the official
  `types.gen.d.ts`), `opencode.json` generation, environment mapping, version comparison
  (including `24.04.10 > 24.04.9`), upstream file parsing, Markdown, install-state merging,
  SSE parsing, settings codec, digests & file ops, tar safety boundaries (with real sym- and hard links),
  command guard, log redaction, workspace policy, unified diff, proot argument shapes.
- **APK self-check** (`tools/verify-apk.mjs`) — the 4 bundled ELFs are **byte-identical** to `jniLibs`
  (proot's entry point not rewritten), ABI is `arm64-v8a` only, container scripts present; with aapt2
  available it also checks the merged manifest and confirms the Shizuku classes exist in dex
  (especially relevant for R8 builds).
- **Mirror health check** (`tools/mirror-check.mjs`) — 25 mirror endpoints verified with real requests;
  dead ones removed.
- **Bundled runtime provenance** — everything comes from the Termux official repo
  (proot 5.1.107.96 / libtalloc 2.5.0 / libandroid-shmem 0.7); `tools/fetch-boot-runtime.mjs` checks
  sha256 and scans proot's option table to re-verify the 6 long options the app depends on.

### The container actually runs in CI (since 1.3.0)

A `smoke` job runs the whole recipe on a Linux runner with real proot — and it is a **release gate**
(the `release` job depends on it):

| Step | What it verifies |
| --- | --- |
| Download `ubuntu-base` rootfs + `SHA256SUMS` | Mirror layout unchanged, checksum path works |
| Launch with the app's exact proot args (`--sysvipc --kill-on-exit -0 --rootfs --cwd --bind`) | Arg names and value passing match real proot (**caught `-L` and `--bind x` bugs**) |
| Run the repo's `profile.sh` / `setup-env.sh` | Container scripts work; dirs / npmrc / banner / locale correct |
| Run `setup-apt.sh` + `install-tools.sh` | apt sources & keyring; **apt + dpkg really install packages under proot fake-root** |
| `git init/commit` | git works inside the container (opencode snapshots depend on it) |
| Fetch Node & opencode straight from mirrors (`SHASUMS256` / `dist.shasum`) | The "fetch binaries from mirror" path works; checksum fields exist |
| `opencode --version` | **Can the Bun binary run under proot?** (the project's biggest unknown) — ✅ yes |
| Start `serve` and `web`; health / sessions / providers / agents / SSE / 401 auth / abort / delete | The REST + SSE contract and password injection the client relies on; `web` root returns `200 text/html` (WebView approach works) |
| `dexec` when the bridge is disabled / unreachable | Error paths are readable |

> Difference from phones: CI uses the distro's proot (Ubuntu 24.04 ships **5.1.0**, from 2016) while the
> app bundles Termux's **5.1.107.96** (with `statx` / `openat2` / `faccessat2`). Programs that need newer
> syscalls may fail in CI but work on-device — e.g. Node 24's bundled npm hits
> `Cannot find module …/walk-up-path/…` in CI (the file exists and is readable). Phones are unlikely to see
> this; and even if they do, the installer no longer aborts over npm (it just logs a note).

Still needs a real device (outside CI's reach):

- Container boot on **actual Android** (W^X, the phantom-process killer, OEM battery policies);
- Shizuku user-service binding (`dexec`);
- apt installs over mobile networks, >60 min runs & SSE leaks, upgrade-signature matrix;
- R8 slim-build regression — which is why the default release is unminified.

### On-device acceptance checklist

After installing 1.4.0, this order is the fastest way through it (if something fails, send Settings → Export logs):

1. **Environment** — Setup → "Test all mirrors" → "Install" → all 4 steps pass (watch step 3 `apt` and step 4 `opencode`);
2. **Service** — Home → "Start service" → status turns green, notification shows up;
3. **Model** — Settings → Models & keys → paste a provider key → "Save & restart service";
4. **Chat** — Chat → new session → say "hi" → streaming reply;
5. **Changes view** — ask the agent to create a file (e.g. "create hello.txt in the workspace") → "Changes" (top-right of the chat) → file + line stats;
6. **Terminal** — `opencode --version`, `git --version`, `cd /root/workspace && ls`;
7. **Web UI** — Home → "Open Web UI" → the official opencode interface;
8. **Device bridge (optional)** — enable Device Shell in Settings (install & authorize Shizuku first) → `dexec 'getprop ro.product.model'`;
9. **Long run** — a 10+ minute agent task; check notification status, progress and "Stop".

---

## Release highlights

### 1.4.0 (full list in [CHANGELOG](CHANGELOG.md))

- **Security hardening** (13 findings from a dedicated audit): the command guard can't be bypassed by
  quoting / `--` / `${IFS}` / nested shells / encodings (normalization + per-segment evaluation +
  structural blocks + fail-closed); the bridge token and guard flag are read live; WebView only autofills
  credentials for the target port; workspace policy rejects `.` / `..` / `Android/data|obb` and
  canonicalizes symlinks; audit-log CR/LF injection fixed; the `dexec` token no longer travels through argv.
- **Service lifecycle**: start/stop mutual exclusion + generation counter (rapid taps can't scramble state);
  cleanup verifies `/proc/<pid>/cmdline` before killing (no PID-reuse accidents); WakeLock released on every path.
- **Installer pipeline**: `.part` files are isolated per source mirror; "success" refused when neither length
  nor checksum is available; symlink-safe writes into the rootfs; half-written CA bundles cleaned up;
  unified 2 GiB space gate; tar hard-link escape / truncation / permission-bit fixes.
- **UI/UX**: confirmations for stop-service and password rotation; chat state survives rotation, back key
  returns to the list, long-press to copy, smart auto-scroll; diff computation moved off the main thread.
- Unit tests 148 → 161.

### 1.3.0

- **The container actually runs in CI now**, as a release gate. The first run caught three real-world bugs:
  1. the short option `-L` doesn't exist in some proot builds (container would die with `unknown option`);
  2. `--bind=xxx` must be a single argv element (`--bind /dev` is rejected);
  3. missing CA certificates silently break https apt sources while `apt-get update` still returns 0.
- Long options everywhere, pinned by unit tests, with CI scanning the bundled binary.
- New **Changes** view (file list + additions/deletions + locally computed colored unified diff).
- tar extraction moved to `java.nio.file`, so symlink/hardlink guards are verifiable in unit tests.
- Unit tests 120 → 148; all GitHub Actions pinned to commit SHAs.

### 1.2.0

- **Random per-install server password**: loopback is open to every app on the device, so the opencode
  server and the device bridge now require a password / bearer token (Basic auth, username `opencode`).
- Default permission policy changed to `ask`; the whole `/sdcard` is **no longer mounted by default**.
- Dangerous-command guard + audit log for the device bridge; exported logs are redacted.
- Unit tests 50 → 120; CI gained version monotonicity checks and optional release signing.

---

## FAQ

**Service times out / exits right away?**
Check Terminal → runtime log. The most common cause is a missing API key (opencode starts but can't do
anything useful), followed by low memory. Enable "Low memory mode" in Settings — it disables file watching
and automatic LSP downloads.

**On Android 12+ the service occasionally gets killed?**
That's the system's "phantom process killer" (it limits background processes per app). Add the app to the
battery allowlist (the Home screen reminds you), or run once via adb / Shizuku:

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
```

**What is the server password for?**
The opencode server listens on `127.0.0.1`, and Android's loopback is open to **all apps on the device** —
without a password, any app could drive your agent and read your sessions. So the app generates a random
password on first launch, injects it via `OPENCODE_SERVER_PASSWORD`, and its own chat / web / doctor screens
use Basic auth (username `opencode`). Want to connect another client (e.g. `adb forward` to your PC)? Copy
the password from Settings → Service → Server password. You can also rotate it at any time (restart required).

**Can the container see my photos / WeChat files?**
Not by default. It only mounts the workspace folder you choose (`/root/workspace` ↔ a folder on your phone).
Only if you enable Settings → Workspace → "Mount all shared storage" does `/sdcard` get mounted.

**Does it run on x86 emulators?**
No. The bundled proot and rootfs are aarch64, and `ndk.abiFilters` keeps `arm64-v8a` only.

**Does opencode need Node?**
No — opencode itself is a single Bun-compiled binary. Node.js (step 4 of the installer) is optional, for
plugins, npm projects, and running node on your phone.

**How do I use the device shell (`dexec`)?**
1. Install and start [Shizuku](https://shizuku.rikka.app/) (on Android 11+ you can start it wirelessly from the device itself — no PC needed);
2. App → Settings → Device Shell → toggle on, tap "Request Shizuku permission" and allow;
3. From inside the container:

```bash
dexec 'getprop ro.product.model'    # device model
dexec 'dumpsys battery | head -20'  # battery
dexec 'pm list packages | grep chrome'  # find apps
```

The app also writes `~/.config/opencode/skills/device-shell/SKILL.md` into the container, so the opencode
agent itself knows when to reach for `dexec` (the skill loads on demand). The bridge listens on
`127.0.0.1:3090` only, a fresh random token is generated on every enable, and turning the toggle off
invalidates it.

**Chinese filenames in the workspace / huge repos?**
git inside the container is configured with `core.fileMode=false` and `safe.directory=*`. For big repos you
may want to turn snapshots off in Settings — opencode's internal git snapshots can take a lot of space.

**Can I build my own native experience on top?**
The Chat tab is a from-scratch client: `GET /event` SSE stream + `prompt_async`, no middle layer. Extend away.

---

## Project layout

```
app/src/main/java/dev/pocket/opencode/
├── core/            container & install: PocketPaths Proot GuestShell Installer Repos Downloader
│                    TarExtract FileOps (symlink-safe delete/size) ServerController NetworkBridge
│                    OpencodeConfig SettingsStore Versions UpstreamParsers (pure parsing, unit-tested)
│                    Doctor (health checks) ShizukuBridge + DeviceShellManager
├── data/            server types + REST/SSE client
├── service/         foreground service & notifications + DeviceShellServer (local HTTP bridge)
│                    + DeviceShellUserService (Shizuku-side stub)
├── ui/              Compose screens: Home / Setup / Chat / Terminal / Web / Settings / Doctor
└── util/

app/src/main/aidl/                 Binder interface for the Shizuku user service (incl. reserved destroy)
app/src/main/assets/scripts/       shell scripts executed inside the container (apt, tools, profile)
app/src/main/assets/device-shell/  the dexec command + device-shell skill template
app/src/test/                      161 JVM unit tests
tools/fetch-boot-runtime.mjs       fetch & verify Termux proot runtime into jniLibs
tools/verify-apk.mjs               APK self-check: ELFs, ABI, scripts, manifest, Shizuku classes
tools/mirror-check.mjs             mirror health check: 25 endpoints verified with real requests
tools/ci-runtime-smoke.sh          run the whole container recipe on Linux (CI release gate)
```

## Third-party components & licenses

- [opencode](https://github.com/anomalyco/opencode) (MIT) — downloaded at runtime, not bundled.
- Termux `proot` / `libtalloc` / `libandroid-shmem` (GPL-2.0 / LGPL-3.0 / MIT) — bundled; provenance and
  sha256 recorded in `app/src/main/jniLibs/boot-runtime.json` by `tools/fetch-boot-runtime.mjs`.
- Ubuntu base rootfs — downloaded on first run, under Ubuntu's own licenses.
- AndroidX, OkHttp, commons-compress, xz — see their respective licenses.

This project itself: MIT (see [LICENSE](LICENSE)).
