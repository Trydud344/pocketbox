# Client compatibility matrix (seeded)

To be filled during Milestone 0 on real devices. A client must:
(a) allow cleartext HTTP to `127.0.0.1`, (b) support HTTP basic auth, (c) handle SSE.

| Client | Package ID | Version | Cleartext loopback | Basic auth | SSE | Session create | Tool call | Result |
|--------|------------|---------|--------------------|-----------|-----|----------------|-----------|--------|
| OpenCode Mobile (official) | `ai.opencode.mobilecode` | 0.8.0 (380) | YES | YES | YES | YES | YES | Requires OpenCode v2 contract (`/api/info`) |
| OpenCode Mobile (community) | `cc.agentlabs.opencode` | — | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | |
| OpenCode Mobile (Play) | `app.getopencode` | — | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | |
| OConnector | (APK) | — | UNTESTED | UNTESTED | UNTESTED | UNTESTED | UNTESTED | |
| OpenCode Web UI (v2 built-in SPA) | — | 2.3.0 | YES (same app) | YES | YES | YES | YES | Works against v2 server; v1 has no SPA |

Notes:
- The server binds `127.0.0.1` only, so "another device" clients need the optional
  LAN mode (not built in v1 prototype) or a tunnel.
- If a client refuses cleartext to loopback, record it here and see DECISIONS.md D1
  and the brief's G2 mitigations (upstream PR first; TLS proxy only with owner approval).
