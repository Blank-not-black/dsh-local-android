# Upstream and attribution

## dsh-mobile-apk

- Repository: https://github.com/kelai141/dsh-mobile-apk
- Baseline: `main` at `23f9cbb49aae2381c4820c4d1230780f2a7d5776`
- Usage: Android shell, embedded runtime lifecycle, WebView bridge, SAF bridge, watchdog and related build conventions.
- License: MIT. The original copyright and license text remain in `LICENSE`.

This repository is an independent project. It is not an official release of `dsh-mobile-apk` and does not imply endorsement by its authors.

## dsh-Remote

- Repository: https://github.com/Blank-not-black/dsh-Remote
- Imported paths: `gateway/gateway.js`, `gateway/gateway-stats.cjs`, and `gateway/public/`.
- License copy: `LICENSES/dsh-remote-MIT.txt`.

The imported dsh-Remote files are adapted for local-only Android use. Changes to the imported files must retain their original copyright and license notices.

## Third-party runtime

The embedded DSH runtime is assembled from the Termux package ecosystem and contains components under licenses other than MIT. The Termux GPL notice is recorded in `app/src/main/assets/licenses/TERMUX-LICENSE.md`; the generic GPL/LGPL texts and package inventory are kept in `app/src/main/assets/licenses/` and copied into the runtime snapshot under `usr/share/LICENSES/`. The minimal builder also retains package-specific `usr/share/doc/<pkg>/copyright*` and other standard license filenames. New runtime components require a license entry before packaging.
