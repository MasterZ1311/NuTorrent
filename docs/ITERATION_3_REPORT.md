# NuTorrent — ITERATION 3 REPORT
## Production Polish, Performance Hardening & Advanced Android Features

**Project:** NuTorrent  
**Package:** `com.nutorrent.app`  
**Status:** Complete & Verified  
**Date:** October 2026  

---

## 1. Executive Summary

Iteration 3 elevated **NuTorrent** from a functional BitTorrent engine into a robust, high-performance, and polished Android BitTorrent client. The work focused on production hardening across network management, battery awareness, storage reliability, media streaming capabilities, and refined user experience.

All 6 development cycles were completed and verified with zero compilation errors, 100% unit and integration test pass rates, and clean Android debug APK generation.

---

## 2. Iteration 3 Architecture & Feature Breakdown

### Cycle 1: Network Engine Hardening & Hierarchical Bandwidth Limiter
- **Token Bucket Rate Limiting (`BandwidthManager.kt`)**:
  - Implemented lockless atomic rate-limiting for global download and upload channels.
  - Dynamically throttles throughput to user-configured limits (`KB/s`) with sub-millisecond precision.
- **Dual-Stack Endpoint Support (`PeerEndpoint.kt`)**:
  - Full support for IPv4 and IPv6 endpoints (bracketed format `[2001:db8::1]:6881`).
  - Added clean parsing and stringification across peer wire, trackers, and local discovery.
- **Peer Scoring & Connection Pruning (`PeerConnection.kt`)**:
  - Dynamic peer health scoring penalizing slow transfer rates, protocol timeouts, and choking behavior.
  - Automated pruning of idle/stale connections to make room for faster swarm peers.
  - Exponential backoff and cooldown periods for failed peer handshakes to prevent reconnection storms.

### Cycle 2: Local Peer Discovery (BEP 14) & Connectivity Observer
- **BEP 14 Local Peer Discovery (`LpdSubsystem.kt`)**:
  - Multicast UDP client and listener broadcasting to `239.192.152.143:6771`.
  - Sends standard `BT-SEARCH * HTTP/1.1` messages with `Host`, `Port`, and `Infohash` headers.
  - Automatically discovers peers on the same local LAN/Wi-Fi network for high-speed, zero-internet transfers.
- **Reactive Network Observer (`NetworkConnectivityObserver.kt`)**:
  - Reactive `Flow<NetworkStatus>` leveraging Android `ConnectivityManager.NetworkCallback`.
  - Automatically distinguishes unmetered Wi-Fi vs. metered mobile data connections.
  - Enables "Download on Wi-Fi Only" safety pausing and automatic resume upon Wi-Fi reconnection.

### Cycle 3: Sequential Piece Scheduling & Streaming Range Verification
- **Sequential Scheduling (`RarestFirstPieceScheduler.kt`)**:
  - Toggleable sequential piece acquisition mode ordering requests sequentially from piece 0 to piece $N$.
  - Preserves file priority weighting while enforcing sequential playback readiness.
- **Continuous Byte Range Tracking (`PieceManager.kt`)**:
  - Added `availableVerifiedRange(fileIndex)` calculating contiguous verified bytes from offset 0 of each file.
  - Displays real-time streaming readiness percentage per file in the UI.
- **Room Schema Migration 2 → 3 (`MIGRATION_2_3`)**:
  - Added `sequentialDownload INTEGER NOT NULL DEFAULT 0` column to `torrents` table.

### Cycle 4: Smart Storage Safety & On-Demand Integrity Recheck
- **Disk Space Safety Threshold (`FileTorrentStorage.kt`)**:
  - Proactive pre-write disk space verification with a 15 MB emergency safety buffer.
  - Catches low-disk conditions before file system write failures occur.
  - Transitioned gracefully into `TorrentState.ERROR` with `AppError.StorageFull` instead of crashing.
- **Force Recheck Pipeline (`TorrentSession.kt`, `TorrentEngine.kt`)**:
  - On-demand SHA-1 re-verification for existing data on disk.
  - Allows recovering from interrupted transfers or verifying externally copied files.

### Cycle 5: Battery Awareness & Time-of-Day Download Scheduler
- **Battery & Power Save Monitoring (`BatteryMonitor.kt`)**:
  - Listens to Android system broadcasts `ACTION_BATTERY_CHANGED` and `ACTION_POWER_SAVE_MODE_CHANGED`.
  - Automatically pauses active swarms when battery level drops below 15% or Power Save mode engages.
  - Resumes transfers when the device is plugged into AC/wireless power.
- **Download Time-of-Day Scheduler (`DownloadScheduler.kt`)**:
  - Configurable start/stop schedule windows supporting same-day and midnight-wrapping intervals (e.g., 23:00 to 07:00 off-peak hours).
  - Dynamically pauses and resumes active torrent sessions via `TorrentEngine.applyScheduler()`.

### Cycle 6: UI Polish, Hierarchical File Trees & Categories System
- **Hierarchical Collapsible Folder Tree (`TorrentDetailsScreen.kt`)**:
  - Files grouped by folder prefix into collapsible directory cards.
  - Per-file streaming verification percentage (`% verified`) and priority toggle (`HIGH`, `NORMAL`, `LOW`, `SKIP`).
- **Interactive Tracker Controls**:
  - Added "Announce Now" manual re-announce trigger in Trackers tab invoking `TorrentSession.reannounce()`.
- **Category System**:
  - Added category tagging (`Linux ISOs`, `Software`, `Media`, `Documents`, `General`) with database persistence in Room v4 (`MIGRATION_3_4`).
  - Search filter in `HomeScreen.kt` indexing torrent names, info hashes, category tags, and contained file names.
- **Notification Quick Actions (`TorrentDownloadService.kt`)**:
  - Added `ACTION_RESUME_ALL` notification action button alongside `ACTION_PAUSE_ALL`.

---

## 3. Database Migration History

| Version | Description | Migration Implementation |
|---------|-------------|--------------------------|
| v1 | Initial release with basic torrent download metadata | `NuTorrentDatabase` creation |
| v2 | Magnet link support, uploaded byte tracking, and file priorities | `MIGRATION_1_2`: `ALTER TABLE torrents ADD COLUMN isMagnet INTEGER...` |
| v3 | Sequential download toggle | `MIGRATION_2_3`: `ALTER TABLE torrents ADD COLUMN sequentialDownload INTEGER NOT NULL DEFAULT 0` |
| v4 | Custom category tagging | `MIGRATION_3_4`: `ALTER TABLE torrents ADD COLUMN category TEXT NOT NULL DEFAULT ''` |

All migrations are fully tested and non-destructive.

---

## 4. Verification & Test Results

### Build Verification
- **Gradle Tasks Executed**:
  - `.\gradlew.bat --console=plain --no-daemon testDebugUnitTest` -> **PASSED (100% success)**
  - `.\gradlew.bat --console=plain --no-daemon assembleDebug` -> **PASSED (`app-debug.apk` built)**
- **Total Test Suites**: 12 comprehensive unit and integration test suites covering Bencode, Magnet links, DHT, UDP Trackers, PEX, Piece Scheduling, File Storage, Bandwidth Limiting, LPD, and Download Simulation.
