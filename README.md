# AniGooli [![Build APKs](https://github.com/Arthur-001/AniGooli/actions/workflows/build-apk.yaml/badge.svg)](https://github.com/Arthur-001/AniGooli/actions/workflows/build-apk.yaml)

**AniGooli** is a native, lightweight Android anime client combining **[Animite](https://github.com/imashnake0/Animite)** (Material 3 AniList/MAL client) with **[ani-cli](https://github.com/pystardust/ani-cli)** streaming and offline downloads.

## Features
- **Self-Updating Streaming Engine**: Executes a remote `ani-cli` extractor script inside Android's sandboxed `JavaScriptEngine` and tracks upstream `pystardust/ani-cli` daily via GitHub Actions — updating streaming sources without rebuilding the APK.
- **Pro Touch-Responsive Player + MPV**: Media3 ExoPlayer with horizontal scrub-seek, left-side brightness drag, right-side volume drag, double-tap ±10s, 2x long-press, pinch-to-zoom, resolution selector (`1080p` default with highest-available fallback), **Japanese (EN Sub ON)** & **English Dub** with per-anime audio/subtitle memory, and one-tap **Open in External MPV**.
- **Smart Single-Video Pre-Buffering & Native Downloads**: Single-slot disk-backed pre-buffering (`500 MB` – `4 GB`, default `1 GB`) with crash-safe memory bounds; downloading reuses already pre-buffered segments/ranges without re-downloading. Manage and delete downloaded episodes under **Library → Downloads**.
- **Offline Personal Anime List**: Add anime from AniList or create custom manual entries, manage custom & default genres (CRUD), switch 2/3/4 column card views (all preferences persisted), and export/restore fault-tolerant JSON/MAL/AniList backups with interactive per-item conflict resolution (`Keep`, `Skip`, `Keep All`, `Discard All`).
- **Ordered Franchise Relations**: Multi-hop chronological **Main Seasons & Movies**, separate **OVAs & Specials**, and separate **Mangas** with persisted **Oldest ↔ Newest** sorting.
- **Release Notifications**: Background checks for new episodes, seasons, movies, OVAs, and mangas for anime in your offline list or watch history.
- **Title Language**: Defaults to **English**, with **Japanese** and **Japanese-English (Romaji)** options in Settings.

## Downloading APKs (Cloud-Built on GitHub Actions)
1. Open the **[Actions](https://github.com/Arthur-001/AniGooli/actions/workflows/build-apk.yaml)** tab and select the latest green run, or download from **[Releases](https://github.com/Arthur-001/AniGooli/releases)**.
2. Download `anigooli-debug-apk` or `anigooli-release-apk` and install on Android 10+ (`minSdk 29`).

## Credits & License
- Built atop **[imashnake0/Animite](https://github.com/imashnake0/Animite)** (Apache License 2.0 — see [LICENSE.txt](LICENSE.txt)).
- Streaming scraping logic adapted from **[pystardust/ani-cli](https://github.com/pystardust/ani-cli)** (GPL-3.0 upstream reference).
