# Magnet Links & DHT Architecture — NuTorrent

**Client Identifier:** `-NU1000-`  
**Specification Version:** v1.0 Production  
**Package:** `com.nutorrent.app.torrent.metadata`, `com.nutorrent.app.torrent.dht`  

---

## 1. Overview

Magnet links are URIs containing a cryptographic info-hash rather than full torrent metainfo files. Because a magnet link lacks file lists, piece sizes, and piece hashes, `NuTorrent` must discover peers through multiple discovery channels and retrieve the `.torrent` metainfo dynamically over the peer wire before data transfer can begin.

This document describes the end-to-end magnet link resolution pipeline and the underlying Mainline DHT (BEP 5) subsystem.

---

## 2. Magnet URI Structure & Parsing

Magnet URIs follow RFC 3986 format:
```
magnet:?xt=urn:btih:<info_hash>&dn=<display_name>&tr=<tracker_url_1>&tr=<tracker_url_2>
```

### 2.1 Parsing Engine (`MetainfoParser.kt`)
- **`xt` (Exact Topic)**: Contains the 20-byte info-hash encoded either as:
  - 40-character hexadecimal string (e.g. `c12fe1c06bba254a9d1b00f12f3f12d8283b1234`)
  - 32-character Base32 string (decoded using RFC 4648 Base32 alphabet without padding).
- **`dn` (Display Name)**: Extracted and sanitized as the provisional display name.
- **`tr` (Trackers)**: Decoded tracker announce URLs added to the initial tracker list.
- **`rawUri`**: Preserved in Room database `TorrentEntity` until metainfo is fully resolved.

---

## 3. High-Performance Discovery Pipeline

When a magnet link is added:

```
[Magnet Link Added]
         │
         ├─────────────────────────────────────────┐
         ▼                                         ▼
[Trackers: Initial + Fallbacks]            [Mainline DHT: findPeers]
  - Announced via HTTP & UDP                 - Kademlia iterative lookup
  - Parallel announce queries                - alpha = 4 concurrent batches
         │                                         │
         └────────────────────┬────────────────────┘
                              │ Discovered Peer Endpoints
                              ▼
                 [MagnetMetadataFetcher Pool]
                              │
                              ▼
                 [TCP Socket Connect & Handshake]
                              │
                              ▼
                 [BEP 10 Extended Handshake]
                              │
                              ▼
                 [BEP 9 ut_metadata Piece Requests]
                              │
                              ▼
                 [SHA-1 Verification & Room DB Promotion]
```

### 3.1 Fallback Trackers
In addition to user-provided trackers, `MagnetMetadataFetcher` automatically announces to public fallback trackers to maximize peer discovery:
- `udp://tracker.opentrackr.org:1337/announce`
- `udp://open.stealth.si:80/announce`
- `udp://tracker.torrent.eu.org:451/announce`
- `udp://explodie.org:6969/announce`
- `udp://tracker.openbittorrent.com:6969/announce`
- `http://tracker.opentrackr.org:1337/announce`

Announces execute concurrently across `Dispatchers.IO` with automatic 30-second periodic re-announcing if fewer than 5 peer connections are active.

---

## 4. BEP 10 & BEP 9 Metadata Exchange

### 4.1 Extension Handshake Negotiation
1. During the standard 68-byte BitTorrent handshake, the client sets the BEP 10 extension bit in the reserved byte array (`reserved[5] |= 0x10`).
2. Upon connecting to a peer that also supports extensions, the client sends an Extended Handshake message (`extended ID = 0`):
   ```bencode
   d
     1:m d
       11:ut_metadata i1e
       6:ut_pex       i2e
     e
   e
   ```
3. The remote peer responds with its own extended handshake, which specifies its local message ID for `ut_metadata` and the total `metadata_size` in bytes.

### 4.2 BEP 9 `ut_metadata` Protocol
Metadata is partitioned into 16 KiB (16,384 bytes) pieces:
$$\text{totalPieces} = \left\lceil \frac{\text{metadata\_size}}{16384} \right\rceil$$

1. **Requesting Pieces**: The client sends a request message for each pending piece:
   ```bencode
   d
     8:msg_type i0e
     5:piece    i<index>e
   e
   ```
2. **Receiving Data**: The peer responds with a data message followed by raw piece bytes:
   ```bencode
   d
     8:msg_type   i1e
     5:piece      i<index>e
     10:total_size i<size>e
   e
   <raw_16k_piece_bytes>
   ```
3. **Handling Rejections**: If a peer rejects a piece (`msg_type = 2`), the piece is returned to the queue to be fetched from an alternate connected peer.

### 4.3 Verification & Promotion
1. Once all pieces are downloaded, `MetadataExchange` concatenates them in order.
2. The SHA-1 hash of the assembled byte buffer is computed:
   $$\text{computedHash} = \text{SHA-1}(\text{assembledBytes})$$
3. If $\text{computedHash} == \text{torrentId}$, the metadata is valid:
   - `MetainfoParser.parseFromInfoBytes()` creates the `TorrentMetadata` object (files, piece hashes, piece length).
   - `MetainfoParser.createTorrentBytesFromInfoDict()` reconstructs the canonical `.torrent` byte array.
   - The Room database entity is updated from `isMagnet = 1` to `isMagnet = 0` with full file and tracker metadata.
   - A standard `TorrentSession` is created, and the download begins.

---

## 5. Mainline DHT (BEP 5) Architecture

The client runs an autonomous Kademlia DHT node (`DhtNode`) to locate peers without relying on centralized trackers.

### 5.1 Routing Table & XOR Distance Metric
- Each node and torrent info-hash is represented as a 160-bit integer (`DhtNodeId`).
- Distance between two identifiers $x$ and $y$ is computed using the bitwise XOR operation:
  $$d(x, y) = x \oplus y$$
- Contacts are grouped into a routing table composed of $k$-buckets ($k = 8$), sorted by prefix distance to the local node ID.

### 5.2 Bootstrapping
On startup, the node asynchronously queries canonical DHT bootstrap routers:
- `router.bittorrent.com:6881`
- `dht.transmissionbt.com:6881`
- `router.utorrent.com:6881`
- `dht.libtorrent.org:25401`
- `dht.aelitis.com:6881`

The bootstrap sequence sends `find_node` queries targeting the local node ID, quickly populating the routing table with alive contacts.

### 5.3 Concurrent Iterative Lookup Algorithm
When searching for peers downloading a specific info-hash:
1. `findPeers(infoHash)` creates a reactive Kotlin coroutine `channelFlow`.
2. Initial candidates are drawn from the closest contacts in the local routing table.
3. Concurrency factor $\alpha = 4$: In each round, the 4 closest unqueried contacts receive `get_peers` queries concurrently.
4. When a contact returns `values` (compact 6-byte IPv4 peer endpoints), they are emitted immediately into the flow and passed to `PeerConnectionManager` / `MagnetMetadataFetcher`.
5. When a contact returns `nodes` (closer contacts), they are merged into the candidate list and sorted by XOR distance to the target info-hash.
6. When a contact provides a write token, `sendAnnouncePeer` is dispatched to announce our client as an active peer in the swarm.
7. The lookup continues iteratively until candidates are exhausted or a maximum round threshold is reached.

---

## 6. Testing & Diagnostic Verification

The magnet and DHT pipelines are validated through unit and integration suites:
- **`MagnetMetadataFetcherTest`**: Simulates BEP 9 piece reception, SHA-1 verification, info-dict parsing, and full `.torrent` reconstruction.
- **`DhtNodeTest`**: Validates XOR distance calculations, bucket splitting, RPC transaction matching, and timeout handling.
- **`DiagnosticDownloadTest` & `RealTorrentDiagnosticTest`**: Verifies live tracker announcements, UDP packet exchanges, and socket peer handshakes.
