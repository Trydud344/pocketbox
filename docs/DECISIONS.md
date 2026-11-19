# Decision Log

Per the project brief (section 0): defaults were followed unless noted here.

| # | Decision | Rationale | Date |
|---|----------|-----------|------|
| D1 | OpenCode pinned to **v2.3.2** (latest v2 release track) | OpenCode Mobile (`ai.opencode.mobilecode` 0.8.0+) requires the OpenCode V2 API contract (`GET /api/info`, `GET /api/event`, `/api/*`). Existing v1 installs are automatically upgraded in-place to v2.3.2 on launch while preserving `/root`. | 2026-11-18 |
| D2 | PRoot built from upstream source (proot-me) with the NDK, static talloc 2.4.2 via a tiny prelude header | Brief 5.1 says do not reuse Termux's prebuilt binary (Termux prefix baked in). Upstream builds cleanly with `-UHAS_LOADER_32BIT` for arm64/x86_64; `replace.h` from samba needs autotools probing, replaced with `talloc-pb-prelude.h`. | 2026-11-17 |
| D3 | Both `libproot.so` and `libproot-loader.so` are shipped | `PROOT_LOADER` env override verified in upstream source (`execve/enter.c`, `get_loader_path`). Shipping the loader avoids proot's runtime self-extraction into PROOT_TMP_DIR (more predictable, and the loader is exec'd by the traced child). | 2026-11-17 |
| D4 | Alpine **3.24.4** minirootfs, both ABIs | Current stable at build time (2026-11-05 release); SHA-256s taken from `latest-releases.yaml` on the dl-cdn. | 2026-11-17 |
| D5 | `libgcc` + `libstdc++` included in `apkBase` | Verified: the OpenCode musl binary fails to start on a bare Alpine minirootfs (`arc4random: symbol not found` etc. from libstdc++) and succeeds once Alpine's `libgcc`/`libstdc++` are present. | 2026-11-17 |
| D6 | Server = `opencode serve --hostname 127.0.0.1 --port <p>` with `OPENCODE_SERVER_USERNAME`/`OPENCODE_SERVER_PASSWORD` in the guest env (never argv) | Verified against the running binary: unauthenticated requests to API routes fail; `/global/health` works without auth on v1 (it's a plain health route); credentials passed via `env -i` into the guest. | 2026-11-17 |
| D7 | Health check accepts v1 (`/global/health`) or v2 (`/api/info`) | Future-proofing per D1; probing order v1→v2, result cached per Supervisor instance. | 2026-11-17 |
| D8 | Port default **4096** | Brief 9.3 (upstream default); AndCode uses 4097, so no collision. | 2026-11-17 |
| D9 | OpenCode self-update: not disabled via config yet | The exact config key/env could not be verified in docs before M1; risk is low because the app controls versions via the manifest and there is no TUI self-update prompt in `serve` mode. Re-verify against `opencode --help` during M1 and add `autoupdate: false` to the seeded `~/.config/opencode/opencode.json` when confirmed. | 2026-11-17 |
| D10 | x86_64 emulator as the primary automated test target; arm64-v8a is the shipping target | Brief M0 requires a real arm64 device for true numbers, but CI/dev machines are x86_64. Both ABIs are built and packaged identically from the same manifest. | 2026-11-17 |
| D11 | Passwords generated 32 chars from an unambiguous alphabet | Brief 5.4 requires ≥ 24 chars; 32 chars from a 60+ char alphabet gives ~190 bits. | 2026-11-17 |
| D12 | Credentials stored via EncryptedSharedPreferences (Keystore master key) | Brief 9.2; security-crypto 1.1.0-alpha06 is the current stable-ish artifact. | 2026-11-17 |
| D13 | Backup/restore implemented as `/root` tar.gz export/import via SAF | Brief 10/M4: covers projects, OpenCode config/auth, dotfiles. Restores into staging then replaces `/root` contents; archive contains API keys (user warned). | 2026-11-17 |
| D14 | LAN bind (`--hostname 0.0.0.0`), DNS override, and `PROOT_NO_SECCOMP` exposed in Settings | Brief 7.4/8.2.5/9.1: LAN mode is opt-in with persistent warning and requires server restart; DNS is written to guest `/etc/resolv.conf`; `PROOT_NO_SECCOMP=1` passed to the proot host env for compatibility. | 2026-11-17 |
| D15 | Opt-in Android shared internal storage mounted at `/sdcard` via PRoot `-b` | Live POSIX access to Documents, Photos, Downloads, etc. on Android 11+ requires `MANAGE_EXTERNAL_STORAGE` (sideload/F-Droid; legacy external storage on API 29). Exposes `ANDROID_STORAGE=/sdcard` in guest env, syncs a marker-delimited block in `~/.config/opencode/AGENTS.md`, and hardens `RuntimePaths.wipe()` + `BackupManager` with non-following `Files.walkFileTree` so symlinks into `/sdcard` can never cause data loss. | 2026-11-19 |

## OpenCode API facts verified against the running binary (2026-11-17)

- v1.19.6: `GET /global/health` → `{"healthy":true,"version":"1.19.6"}` (no auth required for this route).
- v2.3.0: `GET /api/info` (basic auth) → `{"version":"2.3.0","pid":...,"urls":[...]}`; `POST /api/pair` → `{"code":"...","expires_in":300}`; `GET /auth/connect/{code}` redeems pairing codes (the `opencode://connect` convention from brief 8.2.3); SSE at `GET /api/event` (authenticated, long-lived stream).
- v2 plain `/global/health` serves the web SPA (catch-all), so it is not usable as a health endpoint on v2.
