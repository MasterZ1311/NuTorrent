# Developer & Contributor Guide — NuTorrent

**Client Identifier:** `-NU1000-`  
**Specification Version:** v1.0 Production  
**Package:** `com.nutorrent.app`  

---

## 1. Project Overview & Tech Stack

`NuTorrent` is a modern, privacy-focused Android BitTorrent client written entirely in Kotlin using Jetpack Compose.

### Technology Stack
- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose + Material 3
- **Architecture**: MVVM + Clean Architecture + Repository Pattern
- **Concurrency**: Kotlin Coroutines & Flow (`StateFlow`, `ChannelFlow`, `SupervisorJob`)
- **Persistence**: Android Room SQLite (`nutorrent.db`) v4 with automated migrations via KSP
- **Networking**:
  - Raw TCP Sockets for Peer Wire communication
  - UDP Datagram Sockets for BEP 15 Trackers & BEP 5 DHT
  - Multicast UDP for BEP 14 Local Peer Discovery
  - `HttpURLConnection` for HTTP/HTTPS Trackers
- **Build System**: Gradle 8.x with Kotlin DSL (`build.gradle.kts`)

---

## 2. Source Tree Layout

```
app/src/main/java/com/nutorrent/app
│
├── core
│   ├── error          # AppError sealed hierarchy (InvalidTorrent, StorageFull, etc.)
│   ├── model          # TorrentId, TorrentMetadata, TorrentFile, TorrentPiece, PeerEndpoint
│   └── util           # FormatUtils, FileOpener (FileProvider integration), Lg (Structured Logger)
│
├── data
│   ├── database       # NuTorrentDatabase (v4), TorrentDao, TorrentEntity, FileEntity, TrackerEntity
│   ├── repository     # TorrentRepository, SettingsRepository
│   └── storage        # FileTorrentStorage (RandomAccessFile, offset mapping, 15MB buffer)
│
├── service            # TorrentDownloadService (Foreground Service with dataSync)
│
├── torrent
│   ├── bencode        # BencodeDecoder, BencodeEncoder, BencodeElement
│   ├── dht            # DhtNode, DhtRoutingTable, DhtNodeId, DhtTransactionManager
│   ├── download       # TorrentSession, SpeedCalculator
│   ├── engine         # TorrentEngine, ResourceGovernor, BandwidthManager
│   ├── lpd            # LpdSubsystem (BEP 14 UDP Multicast)
│   ├── metadata       # MagnetMetadataFetcher (BEP 9/10 Pipeline)
│   ├── metainfo       # MetainfoParser, MagnetUri, Base32
│   ├── network        # IncomingPeerServer (BEP 3 ServerSocket), UpnpClient (SSDP & IGD SOAP)
│   ├── peer           # PeerConnection, PeerConnectionManager, PeerWireCodec, ExtensionModels
│   ├── piece          # PieceDownloader, PieceManager, PieceScheduler (Rarest-First)
│   ├── tracker        # HttpTrackerClient, UdpTrackerClient, PeerIdGenerator
│   └── upload         # UploadManager (Tit-for-Tat & Optimistic unchoking)
│
└── ui
    ├── addtorrent     # AddTorrentScreen with metainfo preview
    ├── components     # PieceMapView (Canvas), PieceProgressBar, MetricChip, StatusBadge
    ├── details        # TorrentDetailsScreen (Pieces, Files, Peers, Trackers)
    ├── home           # HomeScreen (Card list, search, categories, magnet dialog, 1-tap open)
    ├── navigation     # Screen sealed class, AppNavHost
    ├── settings       # SettingsScreen (Queue limits, Wi-Fi only, path picker)
    └── theme          # Theme, Color, Type
```

---

## 3. Building & Running

### Prerequisites
- **JDK**: Version 17 or 21 (configured as `JAVA_HOME`)
- **Android SDK**: API 34+ with Platforms 34, 35, and Build-Tools installed

### Build Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Build Production Release Bundle (AAB)
```bash
./gradlew bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`

---

## 4. Testing Suite

The repository features comprehensive unit and integration tests across protocol, storage, scheduling, and database subsystems:

```bash
# Run all unit and integration tests
./gradlew testDebugUnitTest
```

### Key Test Suites
- **`BencodeTest`**: Roundtrip serialization and edge cases (leading zeros, nesting bounds, deep trees).
- **`MetainfoParserTest`**: Single-file and multi-file torrent parsing, Base32 decoding, raw `info` slice hashing, and info-byte parsing.
- **`MagnetMetadataFetcherTest`**: BEP 9 piece-wise download, SHA-1 verification, and full metainfo reconstruction.
- **`FastExtensionTest`**: BEP 6 `AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, and `RejectRequest` wire codecs and choked fast-path handling.
- **`IncomingServerAndCyclingTest`**: TCP `IncomingPeerServer` port binding, handshake info-hash demuxing, and `PeerConnectionManager` aggressive peer cycling.
- **`DiagnosticDownloadTest` & `RealTorrentDiagnosticTest`**: Live network socket tests for HTTP/UDP trackers, compact endpoint decoding, and handshake extraction.
- **`PieceSystemTest` & `AdvancedSchedulerAndPriorityTest`**: Rarest-first selection, sequential ordering, file boundary protection, and file priority weighting (`HIGH`, `NORMAL`, `LOW`, `DO_NOT_DOWNLOAD`).
- **`FileTorrentStorageTest` & `StorageOffsetMappingTest`**: Multi-file byte offset mapping, boundary spans, and disk space pre-check.
- **`DhtNodeTest` & `DhtRoutingTableTest`**: XOR distance calculations, bucket splitting, RPC transaction matching, and timeout handling.
- **`DatabaseMigrationTest`**: Validates Room database schema transitions v1 $\rightarrow$ v2 $\rightarrow$ v3 $\rightarrow$ v4 without data loss.
- **`PeerConnectionManagerTest`**: Tests connection pool limits, pending connection tracking, and error backoffs.

---

## 5. Room Database Migrations

The database (`nutorrent.db`) manages torrent states, file trees, and tracker lists across app upgrades:

| Version | Added Capabilities | Migration Details |
|:---:|:---|:---|
| **v1** | Initial Release | Base `TorrentEntity`, `TorrentFileEntity`, `TrackerEntity` |
| **v2** | Magnet & Priority Support | Added `isMagnet`, `uploadedBytes`, and file priority columns |
| **v3** | Streaming Mode | Added `sequentialDownload INTEGER NOT NULL DEFAULT 0` column |
| **v4** | Categories & Organization | Added `category TEXT NOT NULL DEFAULT ''` column |

All migrations are declared in `NuTorrentDatabase.kt` and applied automatically upon database initialization.

---

## 6. Structured Logging Subsystems

Logging is routed through `com.nutorrent.app.core.util.Lg` with category filtering:

| Category | Typical Events |
|:---|:---|
| `PEER` | Socket connections, handshakes, choke/unchoke transitions, wire messages |
| `PIECE` | Block requests, block arrivals, piece SHA-1 verification results |
| `TRACKER` | HTTP/UDP announces, scraped statistics, peer discovery counts |
| `DHT` | Bootstrap status, RPC queries (`ping`, `find_node`, `get_peers`, `announce_peer`) |
| `NETWORK` | Inbound TCP server socket events, UPnP SSDP router discovery, XML parsing, port forwarding (`AddPortMapping` / `DeletePortMapping`) |
| `STORAGE` | RandomAccessFile operations, disk space threshold checks, force recheck progress |
| `ENGINE` | Session creation, queue promotions, governor limits, bandwidth adjustments |
| `SERVICE` | Foreground service lifecycle, notification updates, action intents |

---

## 7. Android System Integration & Lifecycle

### Foreground Transfer Service
`TorrentDownloadService` ensures persistent background transfers:
- Uses `foregroundServiceType="dataSync"` in `AndroidManifest.xml`.
- Maintains notification with real-time transfer rates and progress.
- Responds to notification action intents: `ACTION_PAUSE_ALL`, `ACTION_RESUME_ALL`, and `ACTION_STOP`.
- Dynamically switches to indeterminate progress when a single torrent is in `FETCHING_METADATA` state.

### Battery & Connectivity Monitoring
- `BatteryMonitor` monitors battery level and power-save status via Android broadcasts. Transfers automatically pause when battery $< 15\%$ or power save is active.
- `NetworkConnectivityObserver` monitors Wi-Fi vs. cellular connections via `ConnectivityManager.NetworkCallback`. When "Download on Wi-Fi Only" is toggled, transfers pause immediately on mobile data.

### File Viewing, Sharing & Scoped Storage (`FileProvider`)
- **`FileProvider` Registration**: Defined in `AndroidManifest.xml` with authority `${applicationId}.fileprovider` and paths in `res/xml/file_paths.xml`.
- **`FileOpener.kt`**: Maps file extensions to MIME types (`video/mp4`, `video/x-matroska`, `audio/*`, `application/pdf`, etc.).
- **Permissions**: Generates `content://` URIs with `Intent.FLAG_GRANT_READ_URI_PERMISSION`, allowing third-party video players (VLC, MX Player) and image viewers to read downloaded files directly from `/Android/data/` without encountering `FileUriExposedException` or Scoped Storage blocks.
- **Actions**:
  - `openFile()`: Launches `Intent.ACTION_VIEW` for the primary media file or single completed file.
  - `openFolder()`: Launches directory intent to inspect downloaded files.
  - `shareFile()`: Launches `Intent.ACTION_SEND` (system share sheet) to export the file to other applications.
