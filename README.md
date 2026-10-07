# NuTorrent

**Fast. Minimal. Private. Transparent.**

`NuTorrent` is a native Android BitTorrent client designed for lawful, peer-to-peer distribution of open-source software, Linux distributions, public datasets, and user-owned media.

The name "NuTorrent" is derived from the Greek alphabet letter Nu (*ν* / *Ν*).

---

## Current Status: v1.0 Production Release

`NuTorrent` v1.0 delivers a fully production-ready, release-grade native Android BitTorrent client:

- **BitTorrent Protocol Engine**: Bencode decoder/encoder, BitTorrent v1 SHA-1 hashing, HTTP/HTTPS and UDP (BEP 15) trackers, DHT Kademlia discovery (BEP 5), PEX peer exchange (BEP 11), Local Peer Discovery (BEP 14), and BitTorrent extension handshake (BEP 10) with metadata exchange (BEP 9).
- **BEP 6 Fast Extension**: Implementation of `AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, and `RejectRequest` wire messages, accelerating seeder bitfield exchange and permitting fast piece acquisition even during choke states.
- **Inbound Peer Server & UPnP IGD (BEP 3)**: Pure-Kotlin SSDP router discovery and UPnP port mapping with a dedicated TCP `ServerSocket` listener (`6881–6889`), providing full "Connectable" peer status for incoming swarm connections behind NAT.
- **Swarm Acceleration & Peer Cycling**: Dynamic Wi-Fi peer limit scaling (up to 60 connections) and proactive cycling of stalled choked connections to maximize unchoked seed throughput.
- **System Integration & Share Flow**: Native Android Share (`ACTION_SEND`) and Open With handling for `.torrent` files and `magnet:` URIs; clipboard magnet detection with 1-tap addition.
- **1-Tap File Viewing & Secure Sharing**: Integrated `FileProvider` with MIME type mapping to open completed downloads directly in external players (VLC, MX Player, Photos) or share files via the Android system share sheet (`ACTION_SEND`), resolving Android 11+ Scoped Storage restrictions.
- **Completed Seeding Lifecycle**: Automatic transition from leecher to seeder upon 100% download completion, highlighted with "Completed • Seeding" badges and dedicated Completed tab filtering.
- **Bandwidth Rate Limiting**: Token-bucket algorithm providing smooth and configurable upload/download speed limits with lockless atomic byte management.
- **Local Peer Discovery (BEP 14)**: High-speed local peer discovery over multicast UDP (`239.192.152.143:6771`), establishing gigabit LAN transfers without tracker queries.
- **Dual-Stack IPv4 & IPv6**: Transparent endpoint parsing and peer communication across both IPv4 and IPv6 networks.
- **Network & Metered Awareness**: Automatic transition handling between Wi-Fi and mobile data; configurable "Download only on Wi-Fi" protection and animated offline status indicators.
- **Battery & Power Save Safeguards**: Intelligent throttling and pausing when battery is below 15% or Android Power Save mode is active; auto-resumes when plugged in.
- **Off-Peak Download Scheduler**: Custom time-of-day scheduler supporting midnight-wrapping transfer windows (e.g. 23:00 to 07:00).
- **Sequential Download Mode**: Prioritized sequential piece acquisition with continuous byte-range verification tracking for media playback and streaming preview.
- **Smart Storage Safety**: Proactive disk space verification with a 15 MB emergency threshold, preventing out-of-disk crashes; canonical path resolution preventing path traversal.
- **On-Demand Force Recheck**: Manual SHA-1 integrity re-verification for recovering interrupted downloads or validating pre-existing files on disk.
- **Hierarchical File Tree & Categories**: Collapsible directory browser with per-file verified streaming progress, custom category tagging (`Linux ISOs`, `Software`, `Media`, etc.), and multi-criteria sorting (Name, Progress, Speed, Size, Date Added).
- **Foreground Transfer Service**: Android Foreground Service with low-overhead notification updates, transfer rates, ETA, and notification actions (`Pause All` / `Resume All`).
- **Release Optimization & AAB**: Configured ProGuard/R8 code/resource shrinking generating production APKs (11.0 MB) and Android App Bundles (10.7 MB).

---

## Technology Stack

- **Language**: Kotlin 2.0.21
- **UI Toolkit**: Jetpack Compose + Material 3
- **Architecture**: Clean Architecture + MVVM + Repository Pattern
- **Concurrency**: Kotlin Coroutines + Flow (`StateFlow`, `combine`, `SupervisorJob`)
- **Persistence**: Room Database (`nutorrent.db`) v4 with safe automated migrations via KSP
- **Networking**:
  - HTTP/HTTPS Trackers: `HttpURLConnection` with binary percent-encoding
  - UDP Trackers (BEP 15): `DatagramSocket` binary packet codec
  - DHT (BEP 5): Kademlia UDP RPC client
  - Local Peer Discovery (BEP 14): UDP Multicast listener and beacon (`239.192.152.143:6771`)
  - Inbound Peer Server: `ServerSocket` demultiplexing by info-hash (`IncomingPeerServer`)
  - UPnP Port Mapping: SSDP UDP multicast + SOAP HTTP XML IGD control (`UpnpClient`)
  - Peer Wire: Raw TCP Sockets with length-prefixed streaming codec, BEP 6 fast extension, BEP 10 extensions, and dual-stack IPv4/IPv6 support
- **Rate Limiting**: Custom token-bucket hierarchical `BandwidthManager`
- **Background Execution**: Android Foreground Service with `dataSync` service type, action buttons (`Pause All` / `Resume All`), and real-time status notifications
- **Storage & File Sharing**: Android Storage Access Framework (SAF) + Java RandomAccessFile offset mapping with pre-write disk buffer validation, plus Android `FileProvider` (`content://com.nutorrent.app.fileprovider/...`) with MIME resolution

---

## Architecture Overview

```
com.nutorrent.app
│
├── core
│   ├── model          # Domain models (TorrentId, TorrentMetadata, TorrentFile, TorrentPiece, FilePriority, etc.)
│   └── util           # Formatting utilities (bytes, speeds, durations), FileOpener, and structured logging (Lg)
│
├── data
│   ├── database       # Room SQLite database (nutorrent.db) v4 with entities, DAOs, and migrations
│   ├── repository     # TorrentRepository and SettingsRepository
│   └── storage        # FileTorrentStorage with multi-file byte boundary mapping and pre-flight space check
│
├── torrent
│   ├── bencode        # BencodeElement, strict BencodeDecoder, canonical BencodeEncoder
│   ├── metainfo       # MetainfoParser with raw info-hash extraction, MagnetUri, and Base32 decoder
│   ├── metadata       # MagnetMetadataFetcher (BEP 9/10 ut_metadata reassembly pipeline)
│   ├── tracker        # HttpTrackerClient, UdpTrackerClient, PeerIdGenerator, and announce models
│   ├── dht            # DhtNode, DhtRoutingTable, DhtNodeId (160-bit XOR metric), DhtRpcClient
│   ├── lpd            # LpdSubsystem (BEP 14 UDP Multicast beacon & listener)
│   ├── network        # IncomingPeerServer (BEP 3 ServerSocket), UpnpClient (SSDP & IGD SOAP)
│   ├── peer           # PeerWireCodec, PeerMessage, PeerHandshake, ExtensionModels, MetadataExchange, PexMessage, PeerConnection, PeerConnectionManager
│   ├── piece          # PieceDownloader, RarestFirstPieceScheduler, PieceManager
│   ├── upload         # UploadManager (4 unchoke slots + 30s optimistic unchoke)
│   ├── download       # TorrentSession, SpeedCalculator
│   └── engine         # TorrentEngine with ResourceGovernor and Queue Manager
│
├── service            # TorrentDownloadService (Foreground Service with aggregated multi-torrent speeds)
│
└── ui
    ├── theme          # Colors (Electric Cyan & Slate), Material 3 Dark/Light themes
    ├── components     # PieceProgressBar, PieceMapView, StatusBadge, MetricChip, StatItem
    ├── navigation     # Screen definitions and backstack handler
    ├── home           # HomeScreen with categories, search, queue controls, magnet dialog, and active cards
    ├── addtorrent     # AddTorrentScreen with metainfo preview and folder selector
    ├── details        # TorrentDetailsScreen with Pieces map, Files priority controls, Peers, and Trackers
    ├── settings       # SettingsScreen with peer limits, queue settings, wifi-only mode, and themes
    └── MainActivity   # Main activity hosting Compose UI, deep link filters, and SAF pickers
```

---

## Technical Documentation

Detailed architectural and protocol specifications are maintained in the [`docs/`](docs/) directory:

- 📐 [**Architecture Specification**](docs/ARCHITECTURE.md) — Comprehensive layer boundaries, state machines, concurrency model, and data flows.
- 📡 [**Protocol Specification**](docs/PROTOCOL.md) — Complete BitTorrent specifications for BEP 3, 5, 6, 9, 10, 11, 14, 15, UPnP, and rate limiting.
- 🔒 [**Security & Trust Boundaries**](docs/SECURITY.md) — Threat model, canonical path traversal protections, disk safety thresholds, FileProvider sandboxing, and privacy guarantees.
- 🧲 [**Magnet & DHT Architecture**](docs/MAGNET_AND_DHT.md) — End-to-end magnet resolution, fallback trackers, BEP 9 piece reassembly, and Mainline DHT Kademlia lookup.
- 🛠️ [**Developer & Contributor Guide**](docs/DEVELOPER_GUIDE.md) — Setup instructions, testing suites, Room database migrations (v1 → v4), and logging subsystems.
- 🚀 [**Iteration 4 Report**](docs/ITERATION_4_REPORT.md) — End-to-end magnet resolution, DHT swarm discovery, and production hardening.
- ⚡ [**Iteration 5 Report**](docs/ITERATION_5_REPORT.md) — Swarm acceleration, inbound connectability (UPnP IGD & ServerSocket), BEP 6 fast extension, completed seeding lifecycle, and in-app file viewing.

---

## How to Build

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (API 34 or higher with Platform 34/35/36 installed)
- Android SDK Command-line Tools / Build-tools

### Build APK
Run the Gradle wrapper:
```bash
./gradlew assembleDebug
```
The resulting debug APK is located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## How to Run Tests

Run all unit and integration test suites:
```bash
./gradlew testDebugUnitTest
```

The test suite covers:
- Strict Bencode parsing and encoding roundtrips
- Base32 decoding and RFC Magnet URI parsing
- BEP 10 Extension protocol handshake and BEP 9 `ut_metadata` serialization
- BEP 6 Fast Extension (`AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, `RejectRequest`)
- BEP 15 UDP Tracker requests, responses, and error codes
- BEP 5 DHT 160-bit XOR distance metric, k-bucket routing table, and RPC client
- BEP 11 PEX compact peer format serialization
- Inbound TCP server handshake demuxing and aggressive peer cycling
- Upload/seeding engine choking rotation and optimistic unchoking
- Rarest-first piece scheduling, file priority weighting, and end-game duplicate suppression
- Multi-file random access storage across piece boundaries and disk space validation
- Room database schema migration v1 to v4
- Multi-torrent queue ordering, limits, and automatic promotion
- Deterministic End-to-End download simulation using a local peer socket server

---

## Privacy Policy & Considerations

- **No Device Identifiers**: Peer IDs (`-NU1000-`) are generated cryptographically with `SecureRandom` per session and contain no device IDs, IMEIs, or MAC addresses.
- **Zero Third-Party SDKs**: No analytics SDKs, advertising frameworks, or cloud crash trackers.
- **Transparency**: BitTorrent by design broadcasts your IP address to other peers in the swarm. `NuTorrent` does not claim "anonymous torrenting".

---

## Legal-Use Statement

`NuTorrent` is a lawful peer-to-peer file transfer client. It does not include torrent search engines, indexing scrapers, or directories of copyrighted material. Users are solely responsible for ensuring their downloads comply with applicable copyright and local laws.
