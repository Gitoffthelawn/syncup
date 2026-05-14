<p align="center">
  <img src="mobile-app/assets/icon.png" alt="syncup" width="220"/>
</p>

<h1 align="center">SyncUp</h1>

<p align="center">
  An open-source Syncthing client for iPhone and Android, powered by the Syncthing daemon via gomobile.
</p>

<p align="center">
  <a href="https://github.com/siddarthkay/syncthing-app/actions/workflows/ci.yml"><img src="https://github.com/siddarthkay/syncthing-app/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MPL--2.0-blue.svg" alt="License: MPL-2.0"></a>
</p>

<p align="center">
  <a href="https://f-droid.org/en/packages/com.siddarthkay.syncup/"><img src="https://img.shields.io/badge/F--Droid-Get%20it%20on-1976D2?logo=f-droid&logoColor=white" alt="Get it on F-Droid"></a>
  <a href="https://apps.apple.com/us/app/syncup-client/id6762605078"><img src="https://img.shields.io/badge/App%20Store-Download-000?logo=apple&logoColor=white" alt="Download on the App Store"></a>
</p>

---

Syncthing's official Android client was archived in December 2024.
No single client ran on both phones from one codebase, so I built
this. The daemon runs in-process via
[`gomobile`](https://pkg.go.dev/golang.org/x/mobile), with a React
Native UI scaffolded from
[react-native-go](https://github.com/siddarthkay/react-native-go).

## Screenshots

| Status | Folders | Devices | Settings |
|--------|---------|---------|----------|
| ![Status](docs/screenshots/status.png) | ![Folders](docs/screenshots/folders.png) | ![Devices](docs/screenshots/devices.png) | ![Settings](docs/screenshots/settings.png) |

<details>
<summary>Cross-platform sync demo</summary>

<p align="center">
  <img src="docs/screenshots/cross-platform-sync.png" alt="iOS, Android, and desktop syncthing nodes sharing the same folder" width="800"/>
</p>

</details>

## Features

### Sync core
- **In-process daemon.** The Go daemon lives inside the app via `gomobile`. No subprocess, no IPC, no service-restart juggling.
- **Real-time UI.** Long-polling on `/rest/events` keeps folder state, config, and incoming offers fresh in about a second.
- **QR pairing, both ways.** Show your device QR or scan a peer's. The 56-character device ID never has to be typed.
- **Auto-accept folders.** Trust a peer and any folder they share gets added automatically. Untrusted offers show up as accept or ignore cards.
- **Onboarding tour.** First-launch coach marks walk new users through adding a device, a folder, and reading sync state.
- **Search.** Find any file across folders, preview it inline (images, text, markdown, PDF).

### Storage and platform
- **SAF on Android.** Sync into folders outside the app sandbox (Documents, SD card, USB drive).
- **Auto-start on boot.** Android picks up where it left off after a reboot.
- **External automation.** Start, stop, or rescan from Tasker, MacroDroid, or any `am broadcast` caller. Off by default, see [docs/AUTOMATION.md](docs/AUTOMATION.md).
- **Port fallback.** If `8384` is taken, SyncUp picks the next free port.
- **Reproducible Android builds.** F-Droid metadata in `fastlane/`, armv7a + arm64 APKs.

### File management
- **Folder browser** with thumbnails, file preview, and per-folder statistics.
- **Versioning and ignore editors.** Edit `.stignore` and versioning policy from the folder detail screen.
- **Conflict resolver.** Pick a version, or use the markdown-aware 3-way merge for `.md` conflicts.
- **Transfers view** and **recent changes** for what just moved.

### Capture and backup
- **Quick capture.** Open the camera, snap, and drop the photo straight into a synced folder.
- **Photo backup.** Background upload of new photos and videos into a chosen folder, with flat, by-date, or by-year/month layouts.

### Obsidian
- **Obsidian vault preset.** Picks the right rescan interval, watcher setting, and ignore patterns so `workspace.json` stops causing conflicts. Apply retroactively when `.obsidian/` is detected. Setup guide: [docs/OBSIDIAN.md](docs/OBSIDIAN.md).

## Install

- **Android:** [F-Droid](https://f-droid.org/en/packages/com.siddarthkay.syncup/)
- **iOS:** [App Store](https://apps.apple.com/us/app/syncup-client/id6762605078)

Release process lives in [docs/RELEASE.md](docs/RELEASE.md). Currently at v1.1.13.

## Architecture

The React Native UI talks to the embedded daemon over its REST API at
`127.0.0.1:8384`. A `TurboModule` implemented in Swift and Kotlin
handles daemon lifecycle, preferences, and sandbox filesystem helpers.
The Go side (`backend/wrapper.go`) wraps
`github.com/syncthing/syncthing/lib/syncthing` and is bound through
`gomobile`.

Folders, devices, and events are not re-exported as `gomobile` types.
That path runs into the marshaller's constraints quickly. Everything
outside lifecycle calls goes through `fetch('/rest/...')` instead,
which has been simpler to work with.

## Build

Every `make` target runs inside a Nix shell automatically, so you don't
need to install Go, Node, or JDK yourself. Prerequisites:

- **[Nix](https://nixos.org/download/)** (with flakes enabled)
- **Xcode 16+** (iOS, macOS only)
- **Android SDK** with **NDK r27** (install via Android Studio)

### Release builds

```
make setup         # install Go toolchain + Node deps
make ios           # Go backend + iOS simulator build
make android       # Go backend + debug-signed APK
make sim-ios       # build + install + launch on simulator
make sim-android   # build + install + launch on emulator
make test          # Go + Android + iOS + JS tests
make clean
```

A cold build takes about six minutes for iOS and three for Android on
an M2. Subsequent builds are faster once `gomobile` is cached.

### Dev builds

For iterative development with hot reload:

```
make dev-ios       # build Go backend + start Expo dev client (iOS)
make dev-android   # build Go backend + start Expo dev client (Android)
```

JS/TS changes reload instantly. Go changes require restarting the
dev target.

### Lint and typecheck

```
cd mobile-app
yarn lint
yarn typecheck
```

CI runs lint, typecheck, and `go vet -tags noassets ./...`.

## Background sync

Android keeps the daemon running in the background while the system
allows it. Settings exposes wifi-only and charging-only toggles that
drive the run-condition monitor. The app also restarts on boot.

iOS is more constrained. Apple does not permit continuous background
execution for apps outside the VoIP and audio categories, and this
app does not qualify. It registers two `BGTaskScheduler` jobs and gets
roughly one to two hours of opportunistic sync per day, sometimes with
multi-hour gaps between runs.

If you want a node that is genuinely always online, run Syncthing on a
desktop or server that stays up 24/7. Your phone remains opportunistic
on its own, but it reconciles against a complete copy whenever it
wakes up, instead of depending on other peers being online at the same
time.

Android folders live under app-scoped external storage at
`/storage/emulated/0/Android/data/com.siddarthkay.syncup/...` by
default, or anywhere SAF can reach. App-scoped folders are deleted
when the app is uninstalled; SAF folders are not.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Inspiration

- [pixelspark/sushitrain](https://github.com/pixelspark/sushitrain)
- [researchxxl/syncthing-android](https://github.com/researchxxl/syncthing-android)
- [siddarthkay/react-native-go](https://github.com/siddarthkay/react-native-go)

## Support the project

If this is useful to you, a GitHub star is the signal I watch to
decide what's worth continuing. Sponsorships cover the Apple developer
account and testing devices, and they let me spend time on the harder
iOS background work instead of billable client work.
- [Star on GitHub](https://github.com/siddarthkay/syncthing-app)
- [Sponsor on GitHub](https://github.com/sponsors/siddarthkay)

## License

MPL-2.0. See [LICENSE](LICENSE).
