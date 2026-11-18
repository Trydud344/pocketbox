# Pocketbox

An Android app that hosts a private **OpenCode server** on your phone — inside an
Alpine Linux userland (PRoot, no root required) — so you can drive it from any
OpenCode remote-access client app on the same device at `http://127.0.0.1:<port>`.

Pocketbox is deliberately **not a chat client**. It is a runtime host: install,
start, supervise, pair, back up.

> **Disclaimer:** Pocketbox is not affiliated with or endorsed by OpenCode
> (anomalyco) or any AI provider. Bring your own provider access. No telemetry,
> ever.

## Status

Prototype covering brief milestones M1–M4 (code-side): runtime installer
(resumable, checksum-verified), foreground service + supervisor with health
checks/backoff, dashboard + pairing, logs, settings, QS tile, run-command
screen, backup/restore of `/root`, extra package packs (node/python/build),
DNS/bind-mode/`PROOT_NO_SECCOMP` toggles, and auth/config file import.

Milestone 0 (feasibility) facts were verified on 2026-11-17 against upstream
sources and the actual binaries — see `docs/DECISIONS.md` for the numbers and
routes, including:

- OpenCode **v2.3.2** musl binary runs inside the Alpine 3.24.4 userland once
  `libgcc`/`libstdc++` are present (`GET /api/info` and `POST /api/pair` satisfy
  the OpenCode V2 client contract required by the OpenCode mobile app).
- Legacy v1 (`1.19.6`, `/global/health`) is also supported by the supervisor
  health check, and existing v1 installations are upgraded in-place to v2.3.2.

## Repo layout

```
app/                 UI (Compose), DI, foreground service, boot receiver, QS tile
runtime/             RuntimeBackend pieces: Installer, Supervisor, manifest
runtime/native/      proot + loader NDK build script, pinned sources, talloc prelude
docs/                DECISIONS.md, CLIENTS.md, RUNTIME.md, DEVICES.md
scripts/             manifest updater (fetch latest versions + checksums)
fastlane/            F-Droid style metadata
```

## Building

Requirements: JDK 17, Android SDK (API 36), NDK r28c.

1. Build the native PRoot binaries for both ABIs (once, or after proot updates):

   ```
   ./runtime/native/build-proot-android.sh arm64-v8a
   ./runtime/native/build-proot-android.sh x86_64
   ```

   Outputs land in `runtime/build/android/<abi>/libproot.so` and
   `libproot-loader.so`. The app's Gradle build copies them into jniLibs
   automatically (task `copyProotBinaries`) and packages them so Android
   extracts them to `nativeLibraryDir`, where executing binaries is allowed.

2. Build the APK:

   ```
   ./gradlew :app:assembleDebug
   ```

> Note: per request, this codebase was authored without running compile/test
> steps in this session. A prior build pass during development produced a debug
> APK; after further code changes, run a build to validate.

The manifest `runtime/src/main/assets/runtime-manifest.json` pins Alpine and
OpenCode versions with SHA-256 checksums. To refresh versions, run
`scripts/update-manifest.sh` and re-verify the checksums it prints.

## How it works

- **Install** (one tap): downloads the pinned Alpine minirootfs + OpenCode
  tarballs (HTTP Range resume, SHA-256 verified), extracts to
  `filesDir/rootfs`, seeds `/etc/resolv.conf`, fake `/proc` files, installs
  `apkBase` packages (`git`, `ripgrep`, `curl`, `openssh-client`,
  `ca-certificates`, `libgcc`, `libstdc++`, …) inside PRoot, installs the
  OpenCode binary, and smoke-tests `opencode --version`.
- **Run**: a `specialUse` foreground service owns the Supervisor, which spawns
  `libproot.so … opencode serve --hostname 127.0.0.1 --port 4096` with
  `OPENCODE_SERVER_USERNAME`/`OPENCODE_SERVER_PASSWORD` in the guest
  environment only (never on the command line), polls the health endpoint
  (v1: `/global/health`, v2: `/api/info`) up to 90 s, then monitors liveness
  every 15 s with exponential backoff restarts (error state after 5 crashes
  in 5 minutes with diagnostics).
- **Pair**: the pairing screen shows URL / username / password with
  sensitive-clipboard copy (auto-clear 60 s), QR for a second device, and
  launch buttons for detected client apps.

## Security notes

- Server binds `127.0.0.1` only; password (32 random chars) is mandatory and
  stored in Keystore-backed EncryptedSharedPreferences.
- PRoot is a compatibility layer, not a security sandbox. The real boundary is
  the Android app sandbox. API keys stored by OpenCode sit as plain files in
  the app-private rootfs.
- Downloads only from pinned HTTPS URLs with pinned SHA-256s.
- Cleartext HTTP is permitted by the network security config **only** for
  `127.0.0.1`/`localhost`/`::1`.

## Licenses

App code MIT. PRoot is GPLv2, libtalloc is LGPL (built statically, notices and
source offer in the About screen / `fastlane` metadata). OpenCode is MIT.
See `docs/RUNTIME.md` for details.
