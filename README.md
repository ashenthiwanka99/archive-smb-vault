# Archive

> A private photo & file vault over SMB.

An Android app for browsing, backing up, and streaming files from a home NAS over
SMB — a private, self-hosted alternative to cloud photo/file services. Built with
Quasar (Vue 3) and Capacitor, with a native Android SMB layer.

## Features

- **File browser** — navigate SMB shares with grid/list views, breadcrumbs, search,
  and starred items
- **Gallery backup** — automatically back up your photos to the NAS, with history,
  progress tracking, and scheduled/boot-triggered workers
- **Duplicate detection** — find and clean up duplicate files
- **Music player** — stream audio straight from the NAS (mini + full-screen player)
- **Uploads / downloads** with progress and a cleanup queue
- **Storage breakdown** and recents/activity views
- **Share target** — share files into the app from other apps to push them to the NAS
- **Biometric lock** + PIN fallback for app security
- **Multiple shares** ("shelves") with per-share credentials kept in secure storage

## Tech stack

| Layer | Tech |
|-------|------|
| Framework | Quasar 2 / Vue 3 (Composition API) |
| State | Pinia + persisted state |
| Native shell | Capacitor 8 (Android) |
| Protocol | SMB (native Android plugin + streaming server) |
| Security | Capacitor Preferences for credentials, native biometric |

App ID: `com.mrdon.server`

## Architecture

Credentials are **never** stored in the persisted Pinia/localStorage state. They live in
a separate secure-credentials abstraction (Capacitor Preferences), keyed by share ID, and
are fetched on demand. The SMB work happens in a native Android plugin
(`SmbPlugin` / `SmbStreamServer`) so large files can be streamed rather than buffered in
the WebView.

```
src/
├── pages/        # Screens (FileBrowser, GalleryBackup, Storage, Settings, …)
├── components/   # FileGrid, MusicPlayerFull, BackupStatusCard, BiometricLock, …
├── stores/       # Pinia stores (smb, backup, gallery, downloads, player, …)
├── plugins/      # JS bridges to native plugins (SmbPlugin, BackupPlugin, ShareTarget)
└── utils/        # secureCredentials, sanitize, validation, helpers
src-capacitor/    # Capacitor Android shell + native Java (SMB, backup workers, plugins)
```

## Getting started

### Prerequisites
- Node.js 18+
- Android Studio (for device builds)
- An SMB share on your network (e.g. a NAS or Samba server)

### Install
```bash
npm install
```

### Run in the browser (UI only)
```bash
npm run dev
```

### Run / build on Android
```bash
npm run dev:android     # live-reload on a connected device
npm run build:android   # production build
```

On first launch you add a "shelf" pointing at your SMB share (host, share name,
username, password). Credentials go into secure storage, not the app's persisted state.

## Signing & secrets

The release keystore (`*.jks`), its password file, and related signing material are
**gitignored and excluded** from this repository — they must never be committed. Keep a
secure offline backup of the keystore; if it is lost, you cannot ship updates to an
already-installed copy of the app.

## License

Personal project — all rights reserved unless stated otherwise.
