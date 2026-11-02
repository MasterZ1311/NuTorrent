# Iteration 1 Engineering Report — NuTorrent

## 1. Executive Summary

Iteration 1 of **NuTorrent** has been engineered, implemented, verified, and tested. The application delivers a native, functional Android BitTorrent client foundation covering the end-to-end BitTorrent protocol lifecycle:
- Parsing `.torrent` files with a strict Bencode decoder
- Computing authoritative 20-byte SHA-1 info hashes from raw `info` byte ranges
- Announcing to HTTP and HTTPS trackers with binary percent-encoding
- Parsing compact IPv4 peer swarms
- Managing outbound TCP peer connections with handshake validation
- Operating peer wire messaging (choke, unchoke, interested, have, bitfield, request, piece, cancel)
- Downloading in 16 KiB blocks via Rarest-First scheduling
- Reconstructing pieces and validating SHA-1 piece hashes
- Writing multi-file and single-file data across file boundaries via random access
- Presenting real-time transfers in a Material 3 Jetpack Compose UI
- Maintaining active transfers in an Android Foreground Service with ongoing notification
- Persisting state in Room (`nutorrent.db`)

---

## 2. Test Execution & Results

### Unit & Integration Test Suite
All 18 tests across 6 test suites passed:
1. `BencodeTest`:
   - Valid integer formats (positive, negative, zero, Long.MAX_VALUE)
   - Invalid integer formats (leading zero `i03e`, negative zero `i-0e`, non-numeric, overflow)
   - String decoding (UTF-8, binary byte array, empty string)
   - Malformed string rejection (leading zeroes, truncated strings, missing colon)
   - Lists and dictionary nesting
   - Nesting depth recursion limit (32 levels)
   - Exact `info` slice byte range extraction
   - Canonical Bencode encoder roundtrip
2. `MetainfoParserTest`:
   - Single-file torrent parsing and SHA-1 info-hash calculation
   - Multi-file torrent parsing with file offset mapping
   - Path traversal sanitization and rejection
   - Piece count mismatch rejection
3. `HttpTrackerClientTest`:
   - PeerIdGenerator Azureus-style format and cryptographic randomness
   - Binary byte URL percent-encoding
   - Compact IPv4 peer parsing (IP and port in network byte order)
   - Dictionary peer list parsing
   - Tracker `failure reason` error mapping
4. `PeerWireProtocolTest`:
   - Handshake serialization and parsing (68 bytes)
   - Protocol identifier validation
   - Framing and roundtrip for KeepAlive, Choke, Unchoke, Interested, NotInterested, Have, Bitfield, Request, Piece, Cancel
   - Oversized message length rejection (> 131,081 bytes)
5. `PieceSystemTest`:
   - Piece block splitting (16 KiB blocks with final block remainder)
   - Full piece block assembly and SHA-1 verification
   - Corrupt piece detection, discarding, and state reset
   - Rarest-First scheduler selecting pieces with minimum peer availability
6. `FileTorrentStorageTest`:
   - Single-file random-access write and read
   - Multi-file piece boundary crossing (writing a piece spanning across File 1 and File 2, and reading across boundary)
   - Verification of stored pieces
7. `TorrentEndToEndIntegrationTest`:
   - Deterministic end-to-end integration test with local simulated peer socket server and mock tracker
   - Tested: Metainfo parse -> tracker announce -> peer connection -> handshake -> bitfield -> unchoke -> block request pipeline -> piece assembly -> SHA-1 piece verification -> disk storage write -> byte-for-byte validation against original data.

---

## 3. Implemented Capabilities vs Unsupported Features

### Implemented in Iteration 1:
- [x] Bencode decoder and canonical encoder (BEP 3)
- [x] Single-file and multi-file torrent parsing
- [x] Authoritative SHA-1 info hash computation
- [x] HTTP and HTTPS tracker client (compact IPv4 & dictionary peers)
- [x] Azureus-style Peer ID generator (`-NU1000-`) without device identifiers
- [x] Outbound TCP peer connection manager with concurrency bounds
- [x] BitTorrent v1 peer handshake (68 bytes)
- [x] Fundamental peer wire messages (KeepAlive, Choke, Unchoke, Interested, NotInterested, Have, Bitfield, Request, Piece, Cancel)
- [x] 16 KiB block downloader with pipelining
- [x] Rarest-First piece scheduler
- [x] Mandatory SHA-1 piece verification
- [x] Random-access storage engine mapping pieces across file boundaries
- [x] Modern Android SAF integration
- [x] Room database (`nutorrent.db`) for torrent and file persistence
- [x] Android Foreground Service with live notification (speed, ETA, progress %, pause action)
- [x] Network monitoring (WiFi vs cellular handling, offline detection)
- [x] Material 3 UI: Home, Add Torrent Preview, Torrent Details (Overview, Files, Peers, Trackers), Settings (network, wifi-only, keep awake, theme)
- [x] Defensive security against directory traversal, unbounded allocations, and corrupt swarms

### Explicitly Excluded from Iteration 1 (Per Specification):
- DHT (BEP 5)
- UDP Trackers (BEP 15)
- Magnet link metadata exchange (BEP 9)
- Peer Exchange (PEX)
- Local Peer Discovery (LSD)
- Protocol Encryption (MSE/PE)
- UPnP / NAT-PMP port forwarding
- Seeding / upload choke algorithms (Iteration 1 focuses on downloading client)
- BitTorrent v2 (BEP 52)

---

## 4. Key Implementation Files

- Bencode: `app/src/main/java/com/nutorrent/torrent/bencode/BencodeDecoder.kt`, `BencodeEncoder.kt`, `BencodeElement.kt`
- Metainfo: `app/src/main/java/com/nutorrent/torrent/metainfo/MetainfoParser.kt`
- Tracker: `app/src/main/java/com/nutorrent/torrent/tracker/HttpTrackerClient.kt`, `PeerIdGenerator.kt`
- Peer Wire: `app/src/main/java/com/nutorrent/torrent/peer/PeerWireProtocol.kt`, `PeerWireCodec.kt`, `PeerConnection.kt`, `PeerConnectionManager.kt`
- Piece & Download: `app/src/main/java/com/nutorrent/torrent/piece/PieceDownloader.kt`, `PieceScheduler.kt`, `PieceManager.kt`, `TorrentSession.kt`, `SpeedCalculator.kt`, `TorrentEngine.kt`
- Storage: `app/src/main/java/com/nutorrent/data/storage/TorrentStorage.kt`
- Persistence: `app/src/main/java/com/nutorrent/data/database/NuTorrentDatabase.kt`, `TorrentDao.kt`, `Entities.kt`, `TorrentRepository.kt`
- Service: `app/src/main/java/com/nutorrent/service/TorrentDownloadService.kt`
- UI: `app/src/main/java/com/nutorrent/ui/MainActivity.kt`, `HomeScreen.kt`, `AddTorrentScreen.kt`, `TorrentDetailsScreen.kt`, `SettingsScreen.kt`

---

## 5. Iteration 2 Recommendations

1. **UDP Tracker Support (BEP 15)**: Implement binary UDP tracker protocol using Java DatagramSockets with connection ID timeouts and exponential retries.
2. **Magnet Links & Metadata Extension (BEP 9 / BEP 10)**: Add magnet URI parsing (`urn:btih:...`) and extension protocol to fetch `.torrent` metainfo directly from connected peers.
3. **Endgame Mode in Scheduler**: When the final few blocks of a torrent remain, request missing blocks from all available unchoked peers and cancel duplicate requests upon receipt.
4. **Active Seeding & Choking Algorithm**: Implement optimistic unchoking and proportional tit-for-tat upload bandwidth allocation.
