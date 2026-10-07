# Architecture Specification — NuTorrent

**Client Identifier:** `-NU1000-`  
**Specification Version:** v1.0 Production  
**Package:** `com.nutorrent.app.*`  

---

## 1. System Overview

`NuTorrent` is engineered under strict Clean Architecture principles, decoupling low-level networking, BitTorrent protocol codecs, storage access, background service coordination, and reactive Material 3 UI. Concurrency is managed deterministically with Kotlin Coroutines, asynchronous Flows (`StateFlow`, `ChannelFlow`), and lockless atomic primitives.

```
┌───────────────────────────────────────────────────────────────────────────┐
│                       Presentation Layer (Compose UI)                     │
│   HomeScreen  •  AddTorrentScreen  •  TorrentDetailsScreen • Settings     │
│   PieceMapView (Canvas) • Hierarchical File Trees • Category Filters      │
│   1-Tap File Viewing & Sharing • "Completed • Seeding" Badge Tracking     │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ User Intents / StateFlow Subscriptions
┌─────────────────────────────────────▼─────────────────────────────────────┐
│                          Application & Data Layer                         │
│   TorrentRepository  •  SettingsRepository  •  NuTorrentDatabase (Room v4)    │
│   NetworkConnectivityObserver  •  BatteryMonitor  •  DownloadScheduler    │
│   FileOpener (FileProvider integration for media/file execution & sharing)│
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ Coordinates Session Lifecycle
┌─────────────────────────────────────▼─────────────────────────────────────┐
│                         Torrent Engine Subsystem                          │
│   TorrentEngine ◄──────────────────► ResourceGovernor (Queue Management)  │
│         │                                                                 │
│         ├──► DhtNode (BEP 5 Kademlia DHT Subsystem)                       │
│         ├──► LpdSubsystem (BEP 14 Local Peer Discovery)                   │
│         ├──► IncomingPeerServer (BEP 3 ServerSocket & Handshake Demux)    │
│         ├──► UpnpClient (SSDP Router Discovery & IGD Port Forwarding)     │
│         ├──► BandwidthManager (Token-Bucket Global Rate Limiter)          │
│         │                                                                 │
│         ├──► MagnetMetadataFetcher (BEP 9 / BEP 10 ut_metadata Pipeline)  │
│         │                                                                 │
│         └──► TorrentSession (Per-Torrent Download Controller)             │
│                 ├── PieceManager (Bitfields, Blocks, Fast Extension BEP 6)│
│                 ├── RarestFirstPieceScheduler (Sequential & Priorities)   │
│                 ├── PeerConnectionManager (TCP Sockets, Cycling & Backoff)│
│                 ├── UploadManager (Tit-for-Tat & Optimistic Unchoke)      │
│                 ├── TrackerClients (HTTP/HTTPS & BEP 15 UDP Trackers)     │
│                 └── SpeedCalculator (Sliding-Window Bitrate Metrics)      │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ I/O Operations & Verified Block Slices
┌─────────────────────────────────────▼─────────────────────────────────────┐
│                          Infrastructure & Storage                         │
│   FileTorrentStorage (Multi-file Offset Mapping, 15MB Safety Buffer)      │
│   Raw TCP Sockets • UDP Datagram Sockets • Multicast UDP Socket           │
│   Android FileProvider (content://com.nutorrent.app.fileprovider)         │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Layer Boundaries & Modules

### 2.1 Domain & Core (`com.nutorrent.app.core.*`)
- **Domain Models**:
  - `TorrentId`: Strongly-typed 20-byte SHA-1 info-hash wrapper with hex encoding/decoding.
  - `TorrentMetadata`: Immutable metainfo containing piece hashes, file mappings, piece size, total length, announce URLs, and private flags.
  - `TorrentFile`: Represents a single file within the torrent, including relative path, length, and contiguous byte boundaries `[startByte, endByte)`.
  - `FilePriority`: Download weighting (`HIGH`, `NORMAL`, `LOW`, `DO_NOT_DOWNLOAD`).
  - `TorrentState`: Lifecycle enum (`QUEUED`, `CHECKING`, `VERIFYING`, `DOWNLOADING`, `SEEDING`, `PAUSED`, `ERROR`, `FETCHING_METADATA`).
  - `TorrentProgress`: Real-time immutable transfer snapshot (bytes verified/downloaded/uploaded, ETA, speeds, peer counts).
  - `PeerEndpoint`: Dual-stack IPv4 and IPv6 network endpoint (`ip`, `port`).
- **File Opener & System Intent Launcher (`FileOpener.kt`)**:
  - Securely maps file extensions to standard MIME types (`video/x-matroska`, `video/mp4`, `audio/*`, `application/pdf`, etc.).
  - Resolves file targets to secure `content://` URIs via `androidx.core.content.FileProvider`.
  - Dispatches `Intent.ACTION_VIEW` and `Intent.ACTION_SEND` (Share) intents with `FLAG_GRANT_READ_URI_PERMISSION`, allowing third-party media players and file managers to read completed downloads directly.
- **Structured Logging (`Lg.kt`)**: Structured, categorized logging tagged by subsystem (`PEER`, `PIECE`, `TRACKER`, `DHT`, `STORAGE`, `ENGINE`, `SERVICE`, `NETWORK`).

### 2.2 Application & Data Layer (`com.nutorrent.app.data.*`)
- **Room Database (`NuTorrentDatabase.kt`)**:
  - Entity schema version: **v4**.
  - `TorrentEntity`: Persists info-hash, name, size, piece length, raw metainfo bytes, destination path, state, magnet flag, category, and sequential setting.
  - `TorrentFileEntity`: Per-file relative path, length, and priority settings.
  - `TrackerEntity`: Per-torrent announce URLs and tier ordering.
  - Non-destructive automated migrations:
    - `MIGRATION_1_2`: Added magnet support, uploaded byte counters, and file priorities.
    - `MIGRATION_2_3`: Added `sequentialDownload` column.
    - `MIGRATION_3_4`: Added `category` column.
- **Repository Pattern (`TorrentRepository.kt`)**:
  - Single source of truth unifying Room database entities and live `TorrentEngine` sessions.
  - Combines database updates, session progress flows, and metadata fetcher states into a unified `Flow<List<TorrentUiModel>>`.
  - Handles addition of `.torrent` files and `magnet:` links, state transitions (pause, resume, force recheck, queue move, delete).

### 2.3 Storage Engine (`com.nutorrent.app.data.storage.FileTorrentStorage`)
- **Multi-File Byte Boundary Mapping**:
  - Maps global torrent byte offsets `[offset, offset + length)` across multiple discrete target files.
  - Automatically calculates which files are touched by any given piece or block slice.
- **Pre-Flight Disk Space Verification**:
  - Validates free disk space before allocating files, enforcing a **15 MB safety buffer**.
  - If insufficient storage is available, throws `AppError.StorageFull` rather than failing mid-transfer.
- **Path Traversal Protection**:
  - Resolves each file's canonical path against the base target directory (`toPath().normalize()`).
  - Strict rejection of escape paths (e.g. `../`) or illegal characters.
- **Fast Recheck Engine**:
  - Verifies existing pieces via parallel SHA-1 hashing, skipping non-existent or empty files.

### 2.4 Torrent Engine Subsystem (`com.nutorrent.app.torrent.*`)
- **`TorrentEngine`**:
  - Top-level supervisor controlling global lifecycle, background subsystems (DHT, LPD, Governor, Incoming Server, UPnP), and session registries.
  - Manages active download queue, applying settings for max active downloads and seeds.
- **`ResourceGovernor`**:
  - Controls concurrency limits: `maxActiveDownloads` and `maxActiveSeeds`.
  - Promotes queued torrents automatically when active downloads complete or are paused.
  - Allocates peer connection budgets evenly across active sessions.
- **`IncomingPeerServer`**:
  - Pure-Kotlin TCP `ServerSocket` listener bound to standard BitTorrent ports (`6881–6889`, falling back to dynamic port).
  - Listens for inbound peer connections, parses the incoming 68-byte handshake, demultiplexes by 20-byte `info_hash`, and delegates the connected socket to the corresponding `TorrentSession`.
  - Confers full **Connectable** peer status to the client, allowing peers throughout the swarm to initiate inbound connections to us.
- **`UpnpClient`**:
  - Lightweight pure-Kotlin SSDP discovery (`239.255.255.250:1900`) for Internet Gateway Devices (IGD).
  - Automatically queries router XML device descriptors for `WANIPConnection` and `WANPPPConnection` control URLs.
  - Issues SOAP `AddPortMapping` requests to automatically map the incoming TCP download port through consumer NAT routers.
  - Cleanly unmaps forwarded ports via `DeletePortMapping` upon engine shutdown.
- **`MagnetMetadataFetcher`**:
  - Dedicated BEP 9 / BEP 10 metadata retrieval engine for magnet links.
  - Concurrently queries HTTP/UDP trackers, fallback trackers, and DHT for peers.
  - Assembles and verifies the 16 KiB info-dictionary pieces, reconstructing full `.torrent` metadata before initiating standard download sessions.
- **`DhtNode`**:
  - Mainline DHT (BEP 5) implementation running over a UDP datagram socket.
  - Implements 160-bit XOR distance routing, k-bucket routing table, parallel bootstrapping, and iterative peer discovery ($\alpha = 4$).
- **`LpdSubsystem`**:
  - Local Peer Discovery (BEP 14) broadcasting and receiving multicast packets on `239.192.152.143:6771`.
- **`BandwidthManager`**:
  - High-performance token-bucket rate limiter enforcing upload and download speed caps with atomic counters.

### 2.5 Per-Torrent Session (`TorrentSession.kt`)
Each active torrent is managed by an isolated `TorrentSession`:
- **`PieceManager`**:
  - Tracks bitfields, active piece downloaders, block completion status, and verifies 16 KiB block assemblies via SHA-1.
  - Implements BEP 6 Fast Extension support: tracks `AllowedFast` pieces per peer, fast-path downloads while choked, `HaveAll` / `HaveNone` seeder shortcuts, and `RejectRequest` handling.
- **`PieceScheduler`**: Implements rarest-first scheduling weighted by swarm availability and file priority. Supports sequential mode for media streaming.
- **`PeerConnectionManager`**:
  - Maintains TCP socket connections to swarm peers, enforces connection limits, handles disconnections and backoff cooldowns.
  - **Aggressive Peer Cycling**: Automatically rotates out peers that have remained choked for $> 30$ seconds with no in-flight blocks when replacement candidates exist in the known peer pool.
  - **Dynamic Connection Limits**: Scales peer connection limits dynamically (from 30 on mobile data up to 50–60 on unmetered Wi-Fi).
- **`UploadManager`**: Enforces BitTorrent tit-for-tat choking algorithm with 4 unchoke slots and a 30-second optimistic unchoke cycle.
- **`SpeedCalculator`**: Sliding-window bitrate calculator producing smoothed download/upload speeds and accurate ETA estimates.

### 2.6 Background Service & Hardware Observers
- **`TorrentDownloadService`**:
  - Android Foreground Service (`dataSync` type).
  - Displays real-time aggregated swarm statistics in the notification drawer.
  - Exposes interactive notification actions (`ACTION_PAUSE_ALL`, `ACTION_RESUME_ALL`, `ACTION_STOP`).
- **`NetworkConnectivityObserver`**:
  - Listens to Android `ConnectivityManager` callbacks.
  - Pauses transfers when switching to metered cellular data if "Download on Wi-Fi Only" is active; resumes when Wi-Fi is restored.
- **`BatteryMonitor`**:
  - Listens to battery level and power state broadcasts.
  - Automatically pauses transfers when battery drops below 15% or Power Save mode engages; resumes when plugged into AC/wireless power.
- **`DownloadScheduler`**:
  - Enforces user-configured off-peak transfer schedules (e.g. 23:00 to 07:00), supporting midnight-wrapping time windows.

### 2.7 OS Integration, Storage Access & Scoped Storage
- **`FileProvider` Sandboxing**:
  - Declared in `AndroidManifest.xml` with authority `${applicationId}.fileprovider` and path configuration `res/xml/file_paths.xml`.
  - Maps app-specific external storage (`/Android/data/com.nutorrent.app/files/Downloads/`) and user-selected external storage roots.
  - Generates secure content URIs (`content://...`) and attaches `FLAG_GRANT_READ_URI_PERMISSION`, completely overcoming Android 11+ (API 30+) Scoped Storage restrictions for external media players (VLC, MX Player, Google Photos).
- **Multi-File & Folder Viewing**:
  - Provides 1-tap "Open File" for single-file and primary media files in multi-file torrents.
  - Provides "Open Folder" (`DocumentsContract` / `ACTION_VIEW`) to inspect directory contents.
  - Provides per-file "Share" (`Intent.ACTION_SEND`) to export completed files to any compatible Android app.

---

## 3. Core Lifecycles & State Transitions

### 3.1 Unified Torrent Lifecycle
```
[User Input: .torrent or magnet URI]
           │
           ├──────────────────────────────┐
           ▼ (.torrent file)              ▼ (magnet: link)
   [Parse & Validate Metainfo]      [Parse Magnet URI]
           │                              │
           │                              ▼
           │                      [MAGNET_PENDING]
           │                              │
           │                              ▼
           │                     [DHT & Tracker Discovery]
           │                              │
           │                              ▼
           │                     [FETCHING_METADATA]
           │                              │ (ut_metadata piece exchange)
           │                              ▼
           │                     [Reassemble & Verify SHA-1]
           │                              │
           ├──────────────────────────────┘
           ▼
     [QUEUED / Active Check via Governor]
           │
           ▼
     [VERIFYING existing pieces] (Fast Recheck)
           │
           ▼
    [DISCOVERING_PEERS] (Trackers + DHT + LPD + PEX)
           │
           ▼
      [DOWNLOADING] ◄──────────────► [PAUSED]
           │                               ▲
           │ (All pieces verified)         │
           ▼                               │
       [SEEDING] ◄─────────────────────────┘
           │
           ▼
      [SEEDING (Completed • Seeding)]
           │
           ├────────────────────────────────────────┐
           ▼                                        ▼
  [Populate COMPLETED Tab]                 [Unlock 1-Tap Actions]
  - Filtered into "Completed"              - "Open File" via FileProvider
  - Continues seeding to swarm             - "Open Folder" / "Share File"
```

### 3.2 Magnet Metadata Resolution Pipeline
```
[Magnet URI with xt=urn:btih:...]
            │
            ▼
[MagnetMetadataFetcher Initialized]
            │
            ├──────────────────────────────────────────────┐
            ▼                                              ▼
[Announce to Trackers & Fallbacks]              [Query DHT findPeers()]
            │                                              │
            └──────────────────────┬───────────────────────┘
                                   │ Discovered Endpoints
                                   ▼
                   [Connect Peer via TCP Socket]
                                   │
                                   ▼
                   [BEP 10 Extension Handshake]
                                   │ (Peer advertises ut_metadata ID)
                                   ▼
                   [Request 16 KiB Metadata Pieces]
                                   │ (msg_type = 0)
                                   ▼
                   [Receive Metadata Pieces (msg_type = 1)]
                                   │
                                   ▼
                   [All Pieces Received? Reassemble Buffer]
                                   │
                                   ▼
                   [SHA-1 Digest == TorrentId?]
                                   │
                   ┌───────────────┴───────────────┐
                   ▼ (YES)                         ▼ (NO)
         [Parse TorrentMetadata]         [Discard & Re-fetch]
                   │
                   ▼
         [Reconstruct .torrent Bytes]
                   │
                   ▼
         [Save to Room DB & Launch Session]
```

### 3.3 Piece Download & Verification Lifecycle
```
[Peer Connection: Unchoked & Interested]
                 │
                 ▼
[PieceScheduler: Select Rarest Piece]
                 │ (File priority & sequential weighting)
                 ▼
[PieceDownloader: Generate 16 KiB Block Requests]
                 │ (Pipelined depth = 4)
                 ▼
[PeerConnection: Send Wire Request]
                 │
                 ▼
[Receive Block Data from Peer Wire]
                 │ (Feed BandwidthManager token bucket)
                 ▼
[Buffer Block in Memory]
                 │
                 ▼
[All Blocks in Piece Received?]
                 │
                 ├──────────────────────────────┐
                 ▼ (YES)                        ▼ (NO - More blocks needed)
[Compute SHA-1 Digest of Piece Buffer]   [Continue Requesting Blocks]
                 │
     ┌───────────┴───────────┐
     ▼ (Pass)                ▼ (Fail)
[Write to Storage Disk]  [Discard Buffer & Log Error]
     │                       │
     ▼                       ▼
[Mark COMPLETE in Bitfield] [Return Piece to Scheduler Pool]
     │
     ▼
[Broadcast HAVE to Swarm]
```

---

## 4. Concurrency & Threading Model

The engine operates entirely on structured concurrency, eliminating shared mutable state risks through functional pipelines and thread-safe data structures:

| Scope / Dispatcher | Usage & Responsibilities |
|:---|:---|
| **`Dispatchers.Main`** | Compose UI state consumption, user interactions, view model subscriptions. |
| **`Dispatchers.IO`** | Raw TCP peer socket streaming, incoming server socket accept loop (`IncomingPeerServer`), UPnP SSDP multicast & SOAP HTTP control (`UpnpClient`), UDP datagram I/O (trackers, DHT, LPD), Room database queries, RandomAccessFile disk reads/writes. |
| **`Dispatchers.Default`** | CPU-bound computations: SHA-1 piece hashing, Bencode encoding/decoding, bitfield bitwise operations, token-bucket replenish calculations. |
| **`ConcurrentHashMap`** | Active peer connections, active sessions registry (`sessionHandlers` by info-hash), metadata fetchers map, candidate endpoint sets. |
| **`CopyOnWriteArrayList`** | Swarm known peer lists, active tracker tier lists, queue ordering. |
| **`AtomicLong` / `AtomicBoolean`** | Upload/download byte counters, state flags (`isRunning`, `isChoked`, `isCancelled`). |
