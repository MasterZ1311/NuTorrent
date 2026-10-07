# Iteration 1 Audit & Iteration 2 Plan — NuTorrent

## 1. Iteration 1 Baseline Status

The repository audit confirmed that Iteration 1 provides a functional foundation:
- **Build Status**: Compiles cleanly with `./gradlew assembleDebug` (Debug APK: 16.7 MB).
- **Unit & Integration Test Suite**: 18 tests passing across Bencode, Metainfo, Tracker, Peer Protocol, Piece Engine, Storage, and an End-to-End local swarm simulation.
- **Protocol Integrity**: Exact SHA-1 info hash computed from raw `info` byte ranges; path traversal strictly sanitized; piece SHA-1 verification mandatory before storage write.
- **Code Quality**: Zero double-bang (`!!`) assertions, zero TODO/FIXME markers, zero mock or fake download data in production code.

---

## 2. Discovered Architecture Considerations & Fixes Required

1. **Info Hash Representations**:
   - `TorrentId` currently requires a 40-character hexadecimal string or 20 bytes.
   - Standard BitTorrent Magnet URIs often specify info hashes in 32-character Base32 (RFC 4648, e.g. `urn:btih:JJOLR...`).
   - *Fix*: Expand `TorrentId` and `MagnetParser` to parse both 40-hex and 32-character Base32 info hashes into 20-byte arrays seamlessly.

2. **Torrent State Transitions**:
   - `TorrentState` lacks states for magnet metadata fetching (`FETCHING_METADATA`), file verification (`CHECKING_FILES`), and active seeding (`SEEDING`).
   - *Fix*: Extend `TorrentState` with `FETCHING_METADATA` and `SEEDING`. When downloads complete, transition to `VERIFYING` -> `SEEDING`.

3. **Peer Protocol Extensions (BEP 10 & BEP 5)**:
   - Handshake reserved bytes are currently hardcoded to all zeroes.
   - For BEP 10 (Extension Protocol), byte 5 bit 0x10 must be set.
   - For BEP 5 (DHT), byte 7 bit 0x01 must be set.
   - Message ID 20 (`Extended`) needs parsing and serialization to exchange metadata (`ut_metadata`, BEP 9) and peer exchange (`ut_pex`, BEP 11).
   - *Fix*: Update `PeerHandshake`, `PeerMessage`, and `PeerWireCodec` to negotiate and frame extended messages.

4. **Multi-Torrent Coordination & Background Service**:
   - Iteration 1 `TorrentSession` managed isolated transfers.
   - Foreground service needs to aggregate multiple concurrent transfers (total download/upload rate, active count, top active transfer progress).
   - *Fix*: Introduce `TorrentManager` and centralized `ResourceGovernor` to coordinate queueing and aggregate notifications.

5. **Storage and Seeding**:
   - `FileTorrentStorage` supported reading and writing, but `PeerConnection` did not handle incoming `Request` messages to serve verified piece data back to peers.
   - *Fix*: Implement `UploadEngine` to read verified blocks from storage and reply with `Piece` messages, bounded by an upload choking algorithm.

6. **Database Schema Evolution**:
   - `TorrentEntity` must store magnet URIs, uploaded bytes, share ratio, priorities, and seeding duration.
   - *Fix*: Implement Room `Migration(1, 2)` to evolve `nutorrent.db` without dropping existing user data.

---

## 3. Iteration 2 Implementation Plan

```
[Phase 1] Magnet Parser & TorrentId Base32 Decoding
    │
[Phase 2] BEP 10 Extension Protocol & BEP 9 Metadata Exchange
    │
[Phase 3] BEP 15 UDP Tracker Client
    │
[Phase 4] BEP 5 Mainline DHT (Routing Table, Node ID, KRPC, get_peers)
    │
[Phase 5] BEP 11 Peer Exchange (PEX) & Unified Peer Discovery
    │
[Phase 6] Seeding & Upload Engine (Choking/Unchoking, Share Ratio)
    │
[Phase 7] Advanced Piece Scheduling (Rarest-First, End-Game Mode)
    │
[Phase 8] File Priorities & Boundary-Safe Storage
    │
[Phase 9] Fast File Recheck with Verification Progress
    │
[Phase 10] Multi-Torrent Manager & Resource Governor
    │
[Phase 11] Room Database Migration (v1 -> v2)
    │
[Phase 12] Android Deep Linking & Multi-Torrent Foreground Service
    │
[Phase 13] UI Upgrades (Categories, Search, Piece Map, Magnet UX)
    │
[Phase 14] Comprehensive Unit & Integration Testing
    │
[Phase 15] Documentation (ARCHITECTURE, DHT, MAGNET, SEEDING, REPORT)
```
