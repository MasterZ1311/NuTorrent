# NuTorrent — ITERATION 4 REPORT
## End-to-End Magnet Resolution, DHT Swarm Discovery & Production Hardening

**Project:** NuTorrent  
**Package:** `com.nutorrent.app`  
**Status:** Complete & Verified  
**Date:** October 2026  

---

## 1. Executive Summary

Iteration 4 delivers end-to-end magnet link resolution and Mainline DHT swarm discovery for **NuTorrent**, completing the client's autonomous peer-to-peer distribution capabilities without requiring pre-downloaded `.torrent` files.

The engineering effort focused on:
1. Building the **BEP 9 / BEP 10 Magnet Metadata Fetching Engine** (`MagnetMetadataFetcher`).
2. Integrating the **Mainline DHT Subsystem** (`DhtNode`) with parallel $\alpha = 4$ lookups directly into `TorrentSession` and magnet resolution flows.
3. Enabling **Parallel Multi-Tracker Announcements** across both HTTP and BEP 15 UDP trackers.
4. Hardening **Room Database** v4 schema transitions and lifecycle state management.
5. Providing full unit and diagnostic integration test suites with 100% test pass rates.

---

## 2. Key Architecture & Subsystem Implementations

### 2.1 BEP 9 & BEP 10 Magnet Metadata Pipeline
- **`MagnetMetadataFetcher.kt`**:
  - Manages standalone peer connection pools dedicated to retrieving the torrent `info` dictionary before file downloading begins.
  - Negotiates BEP 10 extended handshakes to discover peer `ut_metadata` IDs and total `metadata_size`.
  - Downloads 16 KiB metadata pieces concurrently across connected peers.
  - Reassembles received pieces and validates the raw buffer against the expected 20-byte SHA-1 info-hash (`TorrentId`).
- **`MetainfoParser.parseFromInfoBytes()`**:
  - Directly decodes verified bencoded `info` dictionary byte arrays into immutable `TorrentMetadata` domain models.
  - Reconstructs canonical `.torrent` byte arrays (`MetainfoParser.createTorrentBytesFromInfoDict()`) for local caching and Room database persistence.

### 2.2 Mainline DHT (BEP 5) Peer Discovery Integration
- **Concurrent Lookup ($\alpha = 4$)**:
  - `DhtNode.findPeers()` refactored to use reactive `channelFlow` with parallel batches of 4 closest contacts.
  - Queries return compact peer values and write tokens, which trigger automatic `sendAnnouncePeer` callbacks.
- **Bootstrapping Resilience**:
  - Expanded bootstrap router pool (`router.bittorrent.com`, `dht.transmissionbt.com`, `router.utorrent.com`, `dht.libtorrent.org`, `dht.aelitis.com`).
  - Added parallel bootstrap dispatch during node startup.
- **Session Integration**:
  - `TorrentSession` queries DHT peers periodically via `queryDhtForPeers()`, feeding discovered endpoints directly into `PeerConnectionManager`.

### 2.3 Parallel Tracker Announcements & Fallback Pool
- **Asynchronous Coroutine Announcing**:
  - Tracker tier lists announced in parallel across `Dispatchers.IO` using structured coroutine scopes.
  - Failures in individual slow trackers do not block responses from faster trackers.
- **Public Fallback Trackers**:
  - Added built-in high-availability tracker list (`openbittorrent`, `opentrackr`, `stealth.si`, `explodie.org`, `torrent.eu.org`) for magnet links lacking tracker parameters.

### 2.4 Foreground Service Polish & Diagnostics
- **Metadata State Notification**:
  - When fetching metadata, `TorrentDownloadService` displays an indeterminate progress bar with `"Fetching metadata from peers..."` description.
- **Safe Lifecycle Management**:
  - Added `isServiceRunning` volatile guard preventing redundant or crashing service restarts.
  - Safe error trapping on `Context.startForegroundService()`.

---

## 3. Verification & Test Coverage

All automated test suites pass cleanly via `./gradlew testDebugUnitTest`:

```
BUILD SUCCESSFUL in 37s
25 actionable tasks: 25 up-to-date
```

### Verified Test Suites
- **`MagnetMetadataFetcherTest`**: Verifies BEP 9 piece reception, SHA-1 verification, info-dict parsing, and full `.torrent` reconstruction.
- **`MetainfoParserTest`**: Verifies `parseFromInfoBytes` and canonical `.torrent` byte creation.
- **`PieceSystemTest` & `AdvancedSchedulerAndPriorityTest`**: Validates rarest-first piece scheduling, sequential mode, and file priorities.
- **`TorrentSessionRecheckTest`**: Validates force recheck pipeline on existing files.
- **`RealTorrentDiagnosticTest` & `DiagnosticDownloadTest`**: Validates tracker socket communication and handshake parsing.

---

## 4. Conclusion

With the completion of Iteration 4, **NuTorrent** provides a complete, modern, and privacy-preserving BitTorrent client supporting both `.torrent` files and `magnet:` links with DHT, LPD, PEX, dual-stack IPv4/IPv6, bandwidth governors, and offline/battery safeguards.
