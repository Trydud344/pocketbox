# Runtime design notes

## Native layer

`libproot.so` (the proot tracer, a PIE executable built against bionic) and
`libproot-loader.so` (the freestanding loader) are built by
`runtime/native/build-proot-android.sh` from pinned upstream sources:

- `third_party/proot` — proot-me/proot master (2026-11-17), MIT/GPLv2+.
- `third_party/talloc-2.4.2` — samba talloc, LGPL (built statically).
- `third_party/talloc-pb-prelude.h` — our minimal prelude that replaces
  samba's `lib/replace` probing for the NDK toolchain.

Build facts verified with NDK r28c / API 29:

- `-UHAS_LOADER_32BIT` skips the 32-bit loader (no 32-bit support per brief).
- The loader must be embedded with llvm-objcopy **from the loader's own
  directory** so the `_binary_loader_elf_start/_end` symbol names are stable.
- `PROOT_LOADER` env (verified in `execve/enter.c`, `get_loader_path`) makes
  proot use our loader file instead of self-extracting to `PROOT_TMP_DIR`.
- Feature checks: `process_vm` and `seccomp_filter` are auto-detected at build
  time (both available on API 29+ bionic), written into `build.h`.

## Launch command

Supervisor spawns (guest credentials via `env -i`, never argv):

```
<nativeLibraryDir>/libproot.so \
  --kill-on-exit --link2symlink -0 \
  -r <filesDir>/rootfs \
  -b /dev -b /proc -b /sys \
  -b <filesDir>/rootfs/tmp:/dev/shm \
  -b <filesDir>/fake_proc/stat:/proc/stat \
  -b <filesDir>/fake_proc/loadavg:/proc/loadavg \
  -b <filesDir>/fake_proc/uptime:/proc/uptime \
  -b <filesDir>/fake_proc/version:/proc/version \
  -b <filesDir>/fake_proc/vmstat:/proc/vmstat \
  -w /root \
  /usr/bin/env -i HOME=/root USER=root LANG=C.UTF-8 TMPDIR=/tmp \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    OPENCODE_SERVER_USERNAME=<u> OPENCODE_SERVER_PASSWORD=<p> \
    /usr/local/bin/opencode serve --hostname 127.0.0.1 --port <p>
```

Host env for the proot process: `PROOT_TMP_DIR=<cacheDir>/proot-tmp`,
`PROOT_LOADER=<nativeLibraryDir>/libproot-loader.so`, `TMPDIR` same as
`PROOT_TMP_DIR` (app data dirs are exec-restricted only for app data, not for
proot's own mmap'ed loader; still keep tmp writable+searchable).

Compatibility toggle (Advanced, not exposed yet): `PROOT_NO_SECCOMP=1`.

## Fake /proc files

Android restricts `/proc/stat` etc. per-app; Bun/Node-based tools read them at
startup. The installer seeds deterministic, plausible values. They are bind-
mounted read-only into the guest.

## Health / lifecycle

- v1 server: `GET /global/health` → `{"healthy":true,"version":"1.19.6"}`
  (no auth needed for this route; auth still sent).
- v2 server: `GET /api/info` with basic auth → `{"version":"2.3.0",...}`.
- Ready check: poll every 1.5 s up to 90 s.
- Liveness: every 15 s; 3 consecutive failures (or dead process) → restart
  with backoff (2^n seconds); 5 failures within 5 min → `ERROR` state with
  last 50 log lines surfaced in the UI.
- Stop: SIGTERM, 5 s grace, SIGKILL (`--kill-on-exit` reaps the guest tree).

## Inside the container

- Home `/root`; projects dir `/root/projects` (created on demand by the user's
  git usage; OpenCode picks the workspace the client points at).
- Optional Android device storage (opt-in in Settings): when enabled and
  permitted (`MANAGE_EXTERNAL_STORAGE` on Android 11+, legacy external storage
  on Android 10), `Environment.getExternalStorageDirectory()` is bind-mounted at
  `/sdcard` via `-b <externalStorage>:/sdcard`, `ANDROID_STORAGE=/sdcard` is set
  in the guest environment, and a managed marker block is synced into
  `/root/.config/opencode/AGENTS.md` so OpenCode discovers the mount.
  `RuntimePaths.wipe()` and `BackupManager` walk directories without following
  symlinks so links pointing to `/sdcard` can never delete or archive phone
  storage.
- `/usr/local/bin/pb-doctor` prints versions, storage mount status, DNS check,
  git test, disk, date.
- DNS: `/etc/resolv.conf` seeded with 1.1.1.1 / 8.8.8.8 (Android's resolver is
  not reachable from inside PRoot). Override by editing that file from the
  Run command screen.
- Self-update: app-controlled via the manifest; OpenCode's own updater is not
  relied on (config key re-verify pending, see DECISIONS.md D9).
