# NuTorrent — ITERATION 5 REPORT
## Swarm Acceleration, Inbound Connectability, BEP 6 Fast Extension & In-App File Access

**Project:** NuTorrent  
**Package:** `com.nutorrent`  
**Status:** Complete & Verified  
**Date:** October 2026  

---

## 1. Executive Summary

Iteration 5 completes the transfer performance and post-download user experience for **NuTorrent**:
1. **Inbound Connectability & NAT Traversal**: Implemented a pure-Kotlin TCP server (`IncomingPeerServer`) on ports `6881–6889` and an autonomous SSDP/SOAP UPnP IGD client (`UpnpClient`), granting the client full "Connectable" status in the global BitTorrent swarm.
2. **BEP 6 Fast Extension**: Implemented the full BitTorrent Fast Extension wire protocol (`AllowedFast`, `SuggestPiece`, `HaveAll`, `HaveNone`, and `RejectRequest`), eliminating bitfield transfer overhead and allowing choked fast-path piece acquisition.
3. **Swarm Acceleration & Peer Cycling**: Introduced dynamic Wi-Fi connection scaling (up to 60 connections) and proactive cycling of stalled choked peers after 30 seconds when candidate peers exist.
4. **Completed Seeding Lifecycle**: Aligned protocol semantics with UI presentation—upon reaching 100% piece verification, downloads transition into `SEEDING`, displaying `"Completed • Seeding"` badges and populating the `Completed` filter category.
5. **In-App File Opening & Secure Sharing**: Solved Android 11+ Scoped Storage restrictions (`/Android/data/` hidden in system file managers) by configuring an Android `FileProvider` and `FileOpener` utility with 1-tap "Open File", "Open Folder", and "Share" (`ACTION_SEND`) capabilities across the UI.

---

## 2. Key Architecture & Subsystem Implementations

### 2.1 Inbound Peer Server (`IncomingPeerServer.kt`)
- **Port Binding**: Binds a listening `ServerSocket` across standard BitTorrent ports (`6881–6889`), falling back gracefully to system-assigned ports if busy.
- **Handshake Demultiplexing**: Reads the initial 68-byte BitTorrent handshake within a 10-second timeout, extracts the 20-byte `info_hash`, and routes the incoming socket to its registered `TorrentSession`.
- **Swarm Advertising**: The bound port is automatically reported in all HTTP and UDP tracker announces (`port` parameter) as well as DHT `announce_peer` RPC queries.

### 2.2 Autonomous UPnP IGD Client (`UpnpClient.kt`)
- **SSDP Discovery**: Broadcasts M-SEARCH datagrams to `239.255.255.250:1900` searching for `urn:schemas-upnp-org:device:InternetGatewayDevice:1`.
- **Device Description Parsing**: Retrieves router XML descriptors and identifies `WANIPConnection` / `WANPPPConnection` control URLs.
- **SOAP Port Forwarding**: Issues `AddPortMapping` requests to open the listening TCP port on consumer routers and `DeletePortMapping` upon engine shutdown, leaving zero lingering port openings.

### 2.3 BEP 6 Fast Extension (`FastExtensionTest.kt`)
- **Reserved Bit Signaling**: Negotiated via bit `0x04` in byte 7 of the handshake reserved bytes.
- **Message Handlers**:
  - `HaveAll` (ID 14) / `HaveNone` (ID 15): Replaces full bitfields when connecting to seeders or empty leechers.
  - `AllowedFast` (ID 17): Authorizes block requests even when `isChokingUs = true`.
  - `SuggestPiece` (ID 13): Guides scheduler toward optimal swarm pieces.
  - `RejectRequest` (ID 16): Explicitly rejects in-flight requests, freeing pipeline queues without socket timeouts.

### 2.4 Swarm Acceleration & Peer Cycling
- **Stalled Peer Eviction**: Audits connections every 5 seconds. If candidate peers are waiting in the discovered pool, peers that have kept us choked for $> 30$ seconds with 0 in-flight blocks are disconnected to make room for active seeders.
- **Wi-Fi Scaling**: Automatically increases active peer connection limits from 30 up to 50–60 when connected to unmetered Wi-Fi.

### 2.5 Android FileProvider & In-App File Access
- **Scoped Storage Solution**: On Android 11+ (API 30+), system file managers restrict direct browsing of `/Android/data/com.nutorrent/files/Downloads/`.
- **`FileProvider` Setup**: Registered authority `${applicationId}.fileprovider` with `res/xml/file_paths.xml` mapping external files and storage roots.
- **`FileOpener.kt`**:
  - Dynamically detects MIME types for videos (`.mkv`, `.mp4`, `.avi`), audio (`.mp3`, `.flac`), documents (`.pdf`), and archives.
  - Dispatches `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`, launching system media players (VLC, MX Player, Google Photos).
  - Dispatches `Intent.ACTION_SEND` to share completed files to messaging apps, clouds, or other devices.
- **UI Integration**:
  - **Home Screen**: 1-tap "Open File" button on completed cards; "Completed • Seeding" status badge; accurate `Completed` tab filter.
  - **Torrent Details Screen**: "Open File" icon in top bar; "Download 100% Complete!" banner with "Open File" and "Open Folder"; "STORAGE & IDENTITY" card actions; tap-to-open and individual Play/Share buttons in the **Files Tab**.

---

## 3. Verification & Test Coverage

All automated test suites pass cleanly via `./gradlew testDebugUnitTest`:

```
BUILD SUCCESSFUL in 2m 23s
25 actionable tasks: 2 executed, 23 up-to-date
```

The APK packages cleanly via `./gradlew assembleDebug`:
```
BUILD SUCCESSFUL in 1m 9s
37 actionable tasks: 3 executed, 34 up-to-date
```

### Key Test Suites Executed
- **`FastExtensionTest`**: Verified serialization, negotiation, and choked piece requests for BEP 6.
- **`IncomingServerAndCyclingTest`**: Verified TCP socket listening, info-hash demuxing, and 30-second choked peer cycling.
- **`TorrentSessionRecheckTest`**: Verified SHA-1 piece re-verification and state restoration.
- **`MetainfoParserTest`**: Verified bencode parsing, raw info-hash extraction, and Base32 magnet decoding.
- **`PieceSystemTest` & `AdvancedSchedulerAndPriorityTest`**: Verified rarest-first scheduling and file boundaries.

---

## 4. Conclusion

With Iteration 5, **NuTorrent** achieves desktop-class BitTorrent transfer speeds through inbound connectivity, NAT traversal, and BEP 6 fast-pathing, while offering a friction-free post-download experience directly within Android's modern Scoped Storage ecosystem.
