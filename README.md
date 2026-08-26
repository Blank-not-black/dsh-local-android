# DSH for Android

An independent Android edition of DeepSeek Harness (DSH), designed to run DSH
locally on an Android device and present it through the dsh-Remote interface.

This repository is intentionally built from two upstream bases:

- The Android shell, embedded runtime lifecycle, snapshot extraction, SAF
  integration, foreground service, watchdog, and native bridge are based on
  [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk). The pinned
  baseline and copied files are recorded in [UPSTREAM.md](UPSTREAM.md).
- The local gateway and Web UI are imported from
  [dsh-Remote](https://github.com/Blank-not-black/dsh-Remote) and adapted for
  loopback-only Android use.

The project is not an official release of either upstream project. Changes to
the Android runtime should remain traceable to the dsh-mobile-apk baseline;
changes to the gateway and UI should remain traceable to dsh-Remote. See
[AGENTS.md](AGENTS.md) before changing code.

## Current architecture

Startup is deliberately split into four layers:

```text
Install / detection
        │
        ▼
DSH Engine  ── http://127.0.0.1:3080
        │
        ▼
Local Gateway ── http://127.0.0.1:8787
        │
        ▼
DSH for Android UI (WebView)
```

`BackendSupervisor` owns only the order, readiness checks, and failure
attribution. The layer boundaries are:

1. Install/detection prepares and validates the embedded runtime and Android
   permissions.
2. DSH Engine starts `dsh web` and owns the engine process, foreground service,
   watchdog, and `engine.log`.
3. Local Gateway serves the dsh-Remote UI, proxies API/WebSocket traffic to the
   engine, and exposes the local file API. It listens on loopback only and
   writes `gateway.log`.
4. The UI is loaded only after the gateway health check succeeds. It does not
   start or supervise either backend process.

The engine and gateway communicate over loopback HTTP/WebSocket because this
keeps the existing DSH protocol reusable. Remote server lists, token pairing,
network polling, and public announcement/update checks are disabled in local
mode; they are not part of the Android local runtime contract.

## Repository layout

```text
dsh-local-android/
├── app/
│   └── src/main/
│       ├── java/com/dshmobile/shell/
│       │   ├── BackendSupervisor.kt       # four-layer startup coordinator
│       │   ├── EmbeddedProcess.kt         # Android ELF/linker launch helper
│       │   ├── EngineManager.kt            # DSH engine lifecycle
│       │   ├── EngineService.kt            # engine foreground service/watchdog
│       │   ├── LocalGatewayManager.kt      # local gateway lifecycle/assets
│       │   ├── MainActivity.kt              # guide view and WebView handoff
│       │   ├── GatewayProbe.kt              # 127.0.0.1:8787 health probe
│       │   └── ...                          # upstream shell and Android bridge
│       └── assets/                          # runtime snapshot and licenses
├── gateway/
│   ├── gateway.js                          # dsh-Remote gateway, local mode
│   ├── gateway-stats.cjs
│   └── public/                             # dsh-Remote UI snapshot
├── tests/
│   ├── local-gateway.test.mjs
│   └── ui-local-mode.test.mjs
├── docs/LOCAL_ARCHITECTURE.md               # current architecture contract
├── docs/design.md                           # current technical design
├── UPSTREAM.md                              # source baseline and attribution
└── AGENTS.md                                # development rules and test gate
```

## Build

The project currently targets Android API 34 at runtime and compiles with API
36. Use JDK 21 for the local Gradle environment. The embedded runtime snapshot
is large and is intentionally not committed to Git; place the matching
snapshot at `app/src/main/assets/snapshot.tar.xz` before building.

The current phone build is pinned to an arm64 snapshot. An x86_64 emulator
requires an x86_64 snapshot and matching hash; do not mix runtime ABIs.

```sh
export JAVA_HOME=/home/blank/Android/jdk21
export GRADLE_USER_HOME="$PWD/.gradle-home"

./gradlew testDebugUnitTest --no-daemon
node --test tests/*.test.mjs
node --check gateway/gateway.js
node --check gateway/public/app.js
./gradlew assembleDebug --no-daemon
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Use an arm64 physical device for the current snapshot build. An x86_64
emulator cannot run an arm64 embedded Node runtime.

## Diagnostics

The app intentionally reports failures by layer instead of showing one generic
startup error. The guide screen can expose the separate backend logs:

- `engine.log`: runtime extraction, embedded Node, and `dsh web` startup;
- `gateway.log`: local gateway startup, upstream probe, and gateway requests.

The engine warning below is normally non-fatal:

```text
Cannot load "@napi-rs/canvas" package
Cannot polyfill DOMMatrix / ImageData / Path2D
```

It comes from optional PDF rendering support in `pdfjs-dist`. The local gateway
failure and engine failure are separate conditions; inspect the corresponding
log before changing dependencies. The expected endpoints are:

```text
DSH Engine:    http://127.0.0.1:3080
Local Gateway: http://127.0.0.1:8787/health?probe=live
```

## Permissions and local files

The Android shell keeps the upstream SAF bridge and requests access through the
system file picker. File access must follow Android-granted URIs or the
explicitly selected local workspace; the gateway must not turn loopback mode
into unrestricted remote file access.

## License and attribution

This repository is MIT licensed. The Android shell/runtime portions derived
from dsh-mobile-apk retain the upstream copyright and MIT notice. The imported
dsh-Remote gateway/UI is accompanied by its MIT notice in
`LICENSES/dsh-remote-MIT.txt`. Other bundled runtime components retain their
own notices under `app/src/main/assets/licenses/`.

For the exact source baseline, copied paths, and attribution rules, read
[UPSTREAM.md](UPSTREAM.md).
