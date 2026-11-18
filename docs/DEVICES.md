# Device matrix (manual tests, M5)

Record results per device per release. Fill the phantom-process column from
soak tests (screen off 30+ min, brief G3).

| Device | Android | ABI | RAM | Install | Server healthy | Client pairing | 30 min screen-off | Phantom kills | Notes |
|--------|---------|-----|-----|---------|----------------|----------------|-------------------|---------------|-------|
| Pixel (stock) | 14 | arm64 | 8 GB | | | | | | reference device |
| Pixel (stock) | latest | arm64 | 8 GB | | | | | | |
| Samsung Galaxy S | 14 (OneUI) | arm64 | 8 GB | | | | | | check battery manager |
| Xiaomi/POCO | 13/14 (MIUI/HyperOS) | arm64 | 8–12 GB | | | | | | dontkillmyapp.com guidance |
| Low-RAM device | 12 | arm64 | ≤ 4 GB | | | | | | brief: warn < 4 GB |
| Emulator | 34 (API) | x86_64 | 8 GB | | | | n/a | n/a | dev/CI smoke |

Test checklist per row:
- Fresh install → healthy server (M1 acceptance).
- Interrupted install (airplane mode mid-download) → resume works.
- Kill server process (`adb shell am kill` or crash) → supervisor recovers.
- Swipe app away → server keeps running (foreground service).
- Rotate password → restarts, new pairing info works, old fails.
- Backup → reset → restore (M4, when implemented) → working runtime.
- Airplane mode toggle while idle and during a session.
- App update (adb install -r) while running → service restarts cleanly.
