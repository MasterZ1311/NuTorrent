<p align="center">
  <img src="docs/assets/logo-512.png" alt="NuTorrent Logo" width="160" height="160" />
</p>

# NuTorrent

**Fast. Minimal. Private. Transparent.**

NuTorrent is an advanced, release-grade native Android BitTorrent client engineered entirely in pure Kotlin without native C/C++ dependencies or third-party torrent wrappers. Designed for lawful, peer-to-peer distribution of open-source software, operating system images, public datasets, and user-owned media, NuTorrent pairs a high-performance network engine with a modern, kinetic Jetpack Compose user experience.

The name "NuTorrent" is derived from the Greek letter Nu (v / N), representing frequency and transfer velocity in classical physics.

---

## Launch Showcase

<p align="center">
  <a href="brag-output/brag.mp4">
    <img src="brag-output/brag.jpg" alt="NuTorrent Launch Showcase" width="100%" />
  </a>
</p>

[Watch the NuTorrent Launch Video (MP4)](brag-output/brag.mp4)

---

## Executive Summary

Most mobile BitTorrent clients rely on monolithic native C++ libraries (such as libtorrent) wrapped through the Java Native Interface (JNI). NuTorrent takes an uncompromising approach: the entire BitTorrent engine—spanning wire codecs, Bencode parsers, Kademlia DHT routing tables, UPnP IGD control pipelines, and multi-file storage mappers—is written from scratch in pure, idiomatic Kotlin using non-blocking Coroutines, Flow pipelines, and lockless atomic primitives.

The result is a client that is lightweight (10.7 MB Android App Bundle), memory-efficient, auditable, and resilient against memory-corruption vulnerabilities common to C-based networking engines.

---

## Key Technical Highlights

- **Pure Kotlin Protocol Engine**: Implements the BitTorrent wire protocol, length-prefixed streaming framing, Bencode parser/encoder with 32-level recursion guards, and raw SHA-1 byte-slice info-hash extraction.
- **True Inbound Peer Connectivity (BEP 3 & UPnP IGD)**: Built-in SSDP discovery and SOAP XML router control automatically open NAT ports, while a dedicated TCP `ServerSocket` listener on ports 6881-6889 accepts incoming swarm connections and demultiplexes handshakes across active sessions.
- **Mainline DHT Subsystem (BEP 5)**: Complete Kademlia implementation featuring 160-bit XOR metric calculation, 8-node k-bucket routing tables, token-authenticated peer storage, and automatic neighbor maintenance.
- **Sub-Second Magnet Resolution (BEP 9 & BEP 10)**: Fast metadata exchange over extension channels (`ut_metadata`) with piece index hashing and validation, assembling torrent metainfo on the fly without preliminary file downloads.
- **BEP 6 Fast Extension Support**: Implements `AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, and `RejectRequest` wire messages, accelerating piece acquisition and bitfield synchronization even during peer choke cycles.
- **Hierarchical Storage Engine & Path Sandboxing**: Byte-level offset mapping across multi-file torrents backed by direct offset pointer indexing, strict path normalization preventing directory traversal attacks, and pre-flight space verification with an emergency 15 MB safety threshold.
- **Dynamic Bandwidth Management**: Token-bucket rate limiter providing lockless, configurable byte-level throttling across simultaneous uploads and downloads.
- **Local Peer Discovery (BEP 14)**: Multicast UDP beacon and listener on `239.192.152.143:6771` for gigabit transfer rates across local network swarms without tracker dependencies.
- **Kinetic Jetpack Compose UI**: Speed-coupled shimmer download progress bars with plasma leading-edge spark, magnetic intake flux animations, completion aurora bloom transitions, and interactive 3D parallax tilt cards powered by hardware-accelerated graphics layers.
- **Absolute Privacy Guarantees**: Cryptographically generated session peer IDs (`-NU1000-`), zero tracking SDKs, zero advertising frameworks, and zero analytical telemetry.

---

## BitTorrent Enhancement Proposals (BEP) Compliance Matrix

NuTorrent strictly adheres to the official BitTorrent Enhancement Proposals to ensure seamless interoperability with major desktop and server clients (qBittorrent, Transmission, libtorrent, Deluge).

| BEP | Standard Title | Subsystem Component | Operational Scope |
|:---|:---|:---|:---|
| **BEP 3** | The BitTorrent Protocol Specification | `PeerWireCodec`, `IncomingPeerServer` | Core framing, handshake demuxing, 16 KiB block pipelining, inbound TCP listener (6881-6889) |
| **BEP 5** | DHT Protocol (Kademlia) | `DhtNode`, `DhtRoutingTable` | Distributed node discovery, 160-bit XOR distance metric, k-bucket refresh cycles, `get_peers` / `announce_peer` |
| **BEP 6** | Fast Extension | `PieceManager`, `PeerConnection` | Choke-state piece retrieval via `AllowedFast`, seeder announcement via `HaveAll` / `HaveNone`, request rejections |
| **BEP 9** | Metadata Transfer Protocol | `MagnetMetadataFetcher`, `MetadataExchange` | Transfer of `.torrent` metainfo via `ut_metadata` extension messages for magnet links |
| **BEP 10** | Extension Protocol | `ExtensionHandshake`, `PeerWireCodec` | Extensible payload framing, dictionary negotiation, dynamic ID mapping (`ut_metadata`, `ut_pex`) |
| **BEP 11** | Peer Exchange (PEX) | `PexMessage`, `PeerConnectionManager` | Swarm expansion through peer-to-peer endpoint exchange, IPv4 compact byte mapping |
| **BEP 12** | Multitracker Metadata Extension | `MetainfoParser`, `TorrentSession` | Multi-tier announce URI grouping, failover retry sequences, backup tracker rotation |
| **BEP 14** | Local Peer Discovery (LPD) | `LpdSubsystem` | Multicast UDP (`239.192.152.143:6771`) beacon and listener for LAN swarm acceleration |
| **BEP 15** | UDP Tracker Protocol | `UdpTrackerClient` | Low-overhead binary packet exchange, transaction ID validation, connection token caching |
| **BEP 20** | Peer ID Conventions | `PeerIdGenerator` | Azureus-style client identification (`-NU1000-` + 12 cryptographic random bytes) |
| **BEP 23** | Compact Tracker Responses | `HttpTrackerClient`, `UdpTrackerClient` | Compact 6-byte binary IPv4 peer list parsing |

---

## Architecture and System Design

NuTorrent is structured under strict Clean Architecture boundaries, cleanly separating domain logic, network protocol state machines, persistent storage, and UI presentation.

```
com.nutorrent
|
+-- core
|   +-- model          Domain models (TorrentId, TorrentMetadata, TorrentFile, TorrentPiece, FilePriority, AppError)
|   +-- device         System hardware monitors (BatteryMonitor, NetworkConnectivityObserver)
|   +-- util           Formatters, FileOpener (FileProvider integration), structured logging (Lg), link decoders
|
+-- data
|   +-- database       Room SQLite database (nutorrent.db) schema v4 with automated migrations
|   +-- repository     TorrentRepository (single source of truth) and SettingsRepository
|   +-- storage        FileTorrentStorage, FastResumeManager, StorageCalculator, space validation
|
+-- torrent
|   +-- bencode        BencodeElement, strict BencodeDecoder, canonical BencodeEncoder
|   +-- metainfo       MetainfoParser with raw info-hash slice extraction, MagnetUri parser, Base32 codec
|   +-- metadata       MagnetMetadataFetcher (ut_metadata reassembly pipeline)
|   +-- tracker        HttpTrackerClient, UdpTrackerClient, TieredTrackerAnnouncer, CommunityTrackersSync
|   +-- dht            DhtNode, DhtRoutingTable (160-bit XOR metric), DhtRpcClient, transaction management
|   +-- lpd            LpdSubsystem (multicast UDP beacon and listener)
|   +-- network        IncomingPeerServer (ServerSocket 6881-6889), UpnpClient (SSDP & IGD SOAP control)
|   +-- peer           PeerWireCodec, PeerMessage, PeerHandshake, ExtensionModels, PeerConnectionManager
|   +-- piece          PieceDownloader, RarestFirstPieceScheduler, PieceManager, block verification
|   +-- upload         UploadManager (4 unchoke slots, 30s optimistic unchoke cycle, tit-for-tat)
|   +-- download       TorrentSession controller, SpeedCalculator (sliding-window transfer metrics)
|   +-- engine         TorrentEngine orchestrator, ResourceGovernor queue scheduler
|
+-- service            TorrentDownloadService (Android Foreground Service with aggregated notifications)
|
+-- ui
    +-- theme          Modern Electric Cyan (#00D2FF) and Slate palette, dynamic Material 3 Dark/Light themes
    +-- components     PieceProgressBar, PieceMapView (Canvas), StatusBadge, MetricChip, StatItem
    +-- navigation     Screen definitions and backstack handler with AnimatedContent transitions
    +-- home           HomeScreen with category filtering, search, queue controls, magnet dialog, active cards
    +-- addtorrent     AddTorrentScreen with metainfo preview, file trees, and folder picker
    +-- details        TorrentDetailsScreen with piece visualizer, file priority toggles, peers, trackers
    +-- settings       SettingsScreen with peer limits, network mode, scheduler, storage, and diagnostics
    +-- MainActivity   Single-activity host with deep-link filters, intent extractors, and SAF permission handling
```

---

## Engine Deep Dives

### 1. Peer Wire Protocol & Inbound Server Socket
NuTorrent manages peer network I/O through raw TCP streaming sockets governed by coroutine dispatchers:
- **Handshake Protocol**: Emits and validates standard 68-byte handshakes with extension bitflag negotiation.
- **Inbound Server Socket**: Automatically binds to ports `6881-6889`. When a peer initiates an inbound connection, the server inspects the initial handshake within a 10-second timeout, extracts the 20-byte `info_hash`, demultiplexes it against active sessions, and hands off the socket to the session manager.
- **UPnP Port Forwarding**: The integrated `UpnpClient` broadcasts SSDP M-SEARCH packets over UDP multicast (`239.255.255.250:1900`), resolves the Internet Gateway Device (IGD) control URL, and sends SOAP XML payloads to establish dynamic WAN-to-LAN port mappings.
- **Peer Wire Cycling**: Choked or unresponsive peer connections are tracked and cycled aggressively on Wi-Fi (supporting up to 60 concurrent peer sockets) to maintain maximum bandwidth saturation.

### 2. Mainline Kademlia DHT Subsystem
NuTorrent maintains an independent DHT node for trackerless swarm discovery:
- **XOR Distance Metric**: Calculates tree distance using unsigned big-endian 160-bit XOR operations.
- **K-Bucket Routing Table**: Organizes contacts into 160 buckets of capacity k=8. Stale nodes are pinged automatically; responsive nodes are preserved while dead contacts are pruned.
- **RPC Transactions**: Handles query dispatch, retry backoff, and response correlation (`ping`, `find_node`, `get_peers`, `announce_peer`) with cryptographically signed token verification.

### 3. Piece Scheduling & Dual Download Modes
- **Rarest-First Piece Scheduler**: Maintains an atomic piece frequency map across all active peer bitfields. Pieces with the lowest availability in the swarm are prioritized to maximize swarm diversity and health.
- **Sequential Streaming Mode**: When enabled, the scheduler flips to an index-ascending piece queue with continuous byte-range verification, allowing instant media previews and continuous video playback while the transfer proceeds.
- **End-Game Optimization**: When the remaining block count drops below a defined threshold, pending block requests are broadcast to all unchoked peers; the first arriving block cancels redundant requests across the network.

### 4. Storage Architecture & Multi-File Byte Boundary Mapping
- **Contiguous Byte Offset Projection**: Torrents containing hundreds of files are viewed as a single, contiguous byte stream. The `FileTorrentStorage` component calculates exact byte overlaps `[offset, offset + length)` and distributes incoming 16 KiB block slices across target files via Java `RandomAccessFile` offset pointers.
- **Disk Safety Buffer**: Prior to writing any payload, available storage is checked against the required allocation. A strict **15 MB safety buffer** prevents filesystem exhaustion and data corruption.
- **Canonical Path Traversal Shield**: File targets are resolved against root download paths and verified using canonical path normalization. Malicious relative paths (e.g. `../../etc/shadow`) are rejected before disk interaction.
- **Fast Resume Metadata**: Verified bitfields and piece states are committed via Room database transactions, allowing instant resumption across application restarts without full file re-verification.

### 5. Kinetic User Interface & Motion Design
NuTorrent delivers a responsive, visual presentation built on Jetpack Compose and Material 3:
- **Speed-Coupled Shimmer Progress Bar**: The transfer progress bar reflects real-time download velocity. Shimmer sweep speeds dynamically adapt from 2800 ms (trickle) to 500 ms (high throughput > 10 MB/s), paired with a pulsing plasma leading-edge spark.
- **Completion Transmutation Bloom**: As verification completes at 100%, the electric cyan progress bar smoothly morphs into an emerald green aurora (#10B981) accompanied by haptic feedback.
- **Clipboard Magnetic Flux Indicator**: Detected magnet links trigger an expanding electromagnetic pulse ring that attracts peer attention with spring-physics entry and dismissal.
- **3D Parallax Perspective Tilt**: Cards calculate real-time tilt transformations on touch and drag gestures (`rotationX`, `rotationY`, `cameraDistance = 14f * density`) with smooth spring return.
- **Canvas Piece Map**: Visualizes full swarm piece verification in a multi-column canvas grid with pulse animations for active piece acquisitions.
- **Fluid Screen Navigation**: Inter-screen movements use `AnimatedContent` spring transitions (vertical elevation slides with coordinate scaling and fade dissipation).

---

## Security, Privacy, and Sandboxing

NuTorrent is built around a zero-trust model regarding untrusted remote peers:

- **Bencode Recursion Depth Limit**: Prevents stack overflow attacks from adversarial torrent files by capping nesting at 32 levels.
- **Memory String Clamping**: Limits individual bencode byte string allocations to 64 MiB to guard against heap-exhaustion Denial-of-Service attacks.
- **Scoped Storage & FileProvider**: Complies fully with Android 11+ (API 30+) Scoped Storage mandates. Files are stored within managed storage directories and served to external players (VLC, MX Player) using sandboxed `content://com.nutorrent.fileprovider` URIs with temporary, read-only intent flags.
- **Zero Telemetry**: No third-party network requests are made outside of explicit BitTorrent operations (trackers, DHT nodes, peers). No Google Analytics, no Firebase Crashlytics, and no advertising networks.

---

## Technology Stack

| Layer | Technology | Purpose |
|:---|:---|:---|
| **Language** | Kotlin 2.0.21 | Primary application code, coroutines, and functional extensions |
| **UI Framework** | Jetpack Compose (BOM 2024.12.01) | Declarative reactive user interface and custom Canvas graphics |
| **Design System** | Material 3 (Material Design 3) | Modern system theming, dynamic colors, elevation, and typography |
| **Concurrency** | Kotlin Coroutines & Flow | Non-blocking asynchronous network pipelines and state observation |
| **Local Persistence** | SQLite via Android Room 2.6.1 | Torrent sessions, tracker configurations, piece bitfields, and settings |
| **Database Compiler** | Google KSP (Kotlin Symbol Processing) | Annotation processing for type-safe database schemas and DAOs |
| **Low-Level I/O** | Java NIO & BSD Sockets | Raw TCP streaming sockets, UDP datagrams, and multicast groups |
| **Storage Access** | Android Storage Access Framework (SAF) | Secure user folder selections and external storage permissions |
| **Target Platform** | Android 14 / 15 / 16 (API 26 to 36) | Wide device compatibility from Android 8.0 through modern releases |

---

## Repository Structure

```
.
+-- app/
|   +-- src/
|       +-- main/
|       |   +-- java/com/nutorrent/
|       |   |   +-- core/                Domain primitives, models, utilities
|       |   |   +-- data/                Room database, DAO specifications, storage engine
|       |   |   +-- service/             Android Foreground Transfer Service
|       |   |   +-- torrent/             BitTorrent protocol codecs, DHT, trackers, wire engine
|       |   |   +-- ui/                  Jetpack Compose screens, components, theme definitions
|       |   +-- res/                     Vector drawables, app icons, launcher manifests
|       +-- test/                        Comprehensive unit and integration test suites
+-- docs/                                Exhaustive technical specifications and design reports
|   +-- ARCHITECTURE.md                  Layer boundaries, data flow diagrams, concurrency model
|   +-- PROTOCOL.md                      Complete BEP protocol codecs, packet framing, state machines
|   +-- SECURITY.md                      Trust boundaries, path traversal guards, threat model
|   +-- MAGNET_AND_DHT.md                Magnet pipeline, ut_metadata, and Kademlia routing architecture
|   +-- DEVELOPER_GUIDE.md               Environment setup, contribution standards, debugging tools
|   +-- ITERATION_1_REPORT.md            Initial protocol codecs, bencode, and wire validation
|   +-- ITERATION_2_REPORT.md            Storage boundary mapping, piece scheduler, and tests
|   +-- ITERATION_3_REPORT.md            Jetpack Compose UI, foreground service, and database
|   +-- ITERATION_4_REPORT.md            End-to-end magnet resolution, DHT discovery, and hardening
|   +-- ITERATION_5_REPORT.md            Inbound connectability, UPnP IGD, BEP 6 fast extension
+-- release/                             Production AAB and release artifact outputs
+-- build.gradle.kts                     Root build script
+-- settings.gradle.kts                  Repository settings and plugin configurations
+-- gradlew / gradlew.bat                Gradle wrapper executables
```

---

## Building and Verification

### Prerequisites
- Java Development Kit (JDK) 17 or JDK 21
- Android SDK with Platform 34/35/36 and Build-Tools installed
- Environment variable `ANDROID_HOME` pointing to your Android SDK directory

### Build Debug APK
```bash
./gradlew assembleDebug
```
The compiled debug APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Build Production App Bundle (AAB)
```bash
./gradlew bundleRelease
```
The optimized, ProGuard/R8-shrunk Android App Bundle will be generated at:
```
app/build/outputs/bundle/release/app-release.aab
```

### Run the Test Suite
NuTorrent maintains comprehensive automated test coverage spanning all protocol codecs, DHT routing algorithms, and multi-file storage transactions:
```bash
./gradlew testDebugUnitTest
```

The test suite validates:
- Bencode parsing and encoding round-trips with depth overflow checks.
- RFC-compliant Magnet URI parsing and Base32 decoding.
- BEP 10 extension handshake and BEP 9 `ut_metadata` reassembly pipelines.
- BEP 6 Fast Extension wire messages (`AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, `RejectRequest`).
- BEP 15 binary UDP tracker requests, responses, and connection timeout retries.
- BEP 5 Kademlia DHT 160-bit XOR distance metrics, k-bucket routing tables, and RPC transactions.
- BEP 11 PEX compact peer format serialization.
- Inbound TCP server handshake demultiplexing and connection cycling.
- Tit-for-tat choking rotation with 30-second optimistic unchoke algorithms.
- Rarest-first piece scheduling, file priority weights, and end-game duplicate suppression.
- Storage byte boundary offsets, multi-file boundary crossing, and disk safety buffer enforcement.
- Automated Room database migrations across all schema revisions (v1 to v4).
- Deterministic end-to-end download transfers verified against a simulated local socket peer server.

---

## Technical Documentation Index

For in-depth architectural specifications and implementation guides, refer to the technical documents in the `docs/` directory:

- [Architecture Specification](docs/ARCHITECTURE.md) — Layer boundaries, state machines, concurrency design, and data flows.
- [Protocol Specification](docs/PROTOCOL.md) — Detailed specifications for BEP 3, 5, 6, 9, 10, 11, 12, 14, 15, UPnP, and rate limiting.
- [Security and Trust Boundaries](docs/SECURITY.md) — Threat model, canonical path protection, disk space buffers, and Scoped Storage sandboxing.
- [Magnet and DHT Architecture](docs/MAGNET_AND_DHT.md) — End-to-end magnet resolution, fallback trackers, and Mainline Kademlia DHT.
- [Developer and Contributor Guide](docs/DEVELOPER_GUIDE.md) — Workspace setup, testing workflows, Room migrations, and logging subsystems.
- [Iteration 4 Engineering Report](docs/ITERATION_4_REPORT.md) — Magnet resolution, DHT swarm discovery, and production hardening.
- [Iteration 5 Engineering Report](docs/ITERATION_5_REPORT.md) — Swarm acceleration, inbound connectability (UPnP IGD), BEP 6 fast extension, and seeding lifecycle.

---

## Privacy Policy

- **No Device Identifiers**: Peer IDs (`-NU1000-`) are generated cryptographically per session using `SecureRandom` and contain no device IDs, hardware serial numbers, IMEIs, or MAC addresses.
- **Zero Third-Party SDKs**: NuTorrent includes no analytics frameworks, advertising libraries, or cloud error reporting SDKs.
- **Network Transparency**: BitTorrent is an open peer-to-peer protocol that broadcasts your IP address to other peers in the swarm. NuTorrent makes no claims of anonymity and operates with complete network transparency.

---

## Legal Notice

NuTorrent is an open-source, lawful peer-to-peer file transfer client intended for downloading open-source software, Linux distribution images, public datasets, and user-owned content. NuTorrent does not host, index, search, or promote copyrighted content. Users are solely responsible for ensuring that their downloads comply with applicable local and international copyright laws.
