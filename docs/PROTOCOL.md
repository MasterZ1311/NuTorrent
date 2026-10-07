# BitTorrent Protocol Specification — NuTorrent

**Client Identifier:** `-NU1000-` (Azureus-style)  
**Specification Version:** v1.0 Production  
**Package:** `com.nutorrent.app.torrent.*`  

---

## 1. Executive Summary

`NuTorrent` is a native Android BitTorrent client that fully implements the BitTorrent v1 core specification alongside key BitTorrent Enhancement Proposals (BEPs). This document details all supported BEPs, message packet formats, handshake sequences, state machines, and wire codecs implemented within the engine.

---

## 2. Implemented BitTorrent Enhancement Proposals (BEPs)

| BEP | Title | Implementation Component | Status |
|:---:|:---|:---|:---:|
| **BEP 3** | The BitTorrent Protocol Specification | `BencodeDecoder`, `BencodeEncoder`, `PeerWireCodec`, `HttpTrackerClient`, `IncomingPeerServer` | **Supported** |
| **BEP 5** | DHT Protocol (Kademlia Mainline DHT) | `DhtNode`, `DhtRoutingTable`, `DhtTransactionManager`, `DhtRpcClient` | **Supported** |
| **BEP 6** | Fast Extension | `PeerWireCodec`, `PeerConnection`, `PieceManager` | **Supported** |
| **BEP 9** | Extension for Transfer of Metadata Files | `MagnetMetadataFetcher`, `MetadataExchange`, `MetainfoParser` | **Supported** |
| **BEP 10** | Extension Protocol | `ExtensionHandshake`, `PeerWireCodec`, `PeerConnection` | **Supported** |
| **BEP 11** | Peer Exchange (PEX) | `PexMessage`, `PeerConnection` | **Supported** |
| **BEP 12** | Multitracker Metadata Extension | `MetainfoParser`, `TorrentSession` (Tiered parallel announcing) | **Supported** |
| **BEP 14** | Local Peer Discovery (LPD) | `LpdSubsystem` (`239.192.152.143:6771` UDP Multicast) | **Supported** |
| **BEP 15** | UDP Tracker Protocol | `UdpTrackerClient` | **Supported** |
| **BEP 20** | Peer ID Conventions | `PeerIdGenerator` (`-NU1000-` Azureus convention) | **Supported** |
| **BEP 23** | Compact Tracker Responses | `HttpTrackerClient`, `UdpTrackerClient` (Binary 6-byte IPv4) | **Supported** |

---

## 3. BEP 3 — Core BitTorrent Protocol

### 3.1 Bencode Format
Bencode is the serialization format used for `.torrent` metainfo files, tracker responses, and DHT RPC messages.

- **Types Supported**:
  - **Strings**: `<length>:<content>` (e.g. `4:spam`)
  - **Integers**: `i<value>e` (e.g. `i42e`, `i-15e`)
  - **Lists**: `l<items>e` (e.g. `l4:spami42ee`)
  - **Dictionaries**: `d<key1><value1>...e` with lexicographically sorted keys
- **Security Boundaries**:
  - Maximum recursion nesting depth: **32 levels** (prevents `StackOverflowError`).
  - Maximum string allocation limit: **64 MiB**.
  - Strict validation: leading zeroes (`i03e`), negative zero (`i-0e`), empty integer strings (`ie`), and integer overflow trigger `BencodeException`.
- **Implementation**: [`BencodeDecoder.kt`](file:///e:/Github/Torrent/app/src/main/java/com/nutorrent/app/torrent/bencode/BencodeDecoder.kt), [`BencodeEncoder.kt`](file:///e:/Github/Torrent/app/src/main/java/com/nutorrent/app/torrent/bencode/BencodeEncoder.kt).

### 3.2 Info Hash Extraction
- Info hashes are 20-byte SHA-1 digests of the bencoded `info` dictionary.
- `MetainfoParser` extracts the exact raw byte slice of the `info` dictionary directly from the `.torrent` input stream. This guarantees identical info-hash calculation regardless of non-canonical key order in the input file.
- Canonical fallback re-encoding is provided if raw boundaries are unavailable.

### 3.3 Peer Wire Framing & Handshake
Each TCP peer connection starts with a 68-byte handshake:

```
┌───────────────┬──────────────────────┬──────────────────┬───────────────────┬───────────────────┐
│ Length (1B)   │ Protocol String (19B)│ Reserved Bits(8B)│ Info Hash (20B)   │ Peer ID (20B)     │
│ 0x13          │ "BitTorrent protocol"│ 0x00...0x10...   │ SHA-1 digest      │ -NU1000-...       │
└───────────────┴──────────────────────┴──────────────────┴───────────────────┴───────────────────┘
```

- **BEP 10 Extension Support**: Bit 20 from the right (`reserved[5] & 0x10 != 0`) indicates extension support.
- **Message Framing**: After the handshake, all messages follow a 4-byte big-endian length prefix:
  - `Length == 0`: Keep-Alive (sent every 120s of inactivity)
  - `Length > 0`: 1-byte Message ID followed by payload

### 3.4 Wire Message Types

| ID | Name | Payload | Description |
|:---:|:---|:---|:---|
| — | `Keep-Alive` | None | Keeps connection alive during idle periods |
| `0` | `Choke` | None | Peer will not service requests |
| `1` | `Unchoke` | None | Peer permits incoming block requests |
| `2` | `Interested` | None | Notifies peer of desire for its pieces |
| `3` | `Not Interested` | None | Notifies peer that no pieces are currently desired |
| `4` | `Have` | `uint32 pieceIndex` | Advertises single verified piece |
| `5` | `Bitfield` | `byte[] bitfield` | Sent immediately after handshake with full piece map |
| `6` | `Request` | `uint32 index, uint32 begin, uint32 length` | Asks peer for a 16 KiB block |
| `7` | `Piece` | `uint32 index, uint32 begin, byte[] block` | Delivers requested block payload |
| `8` | `Cancel` | `uint32 index, uint32 begin, uint32 length` | Cancels pending block request |
| `13`| `Suggest Piece` | `uint32 index` | BEP 6 suggestion for piece to download |
| `14`| `Have All` | None | BEP 6 announcement that peer has all pieces (seeder) |
| `15`| `Have None` | None | BEP 6 announcement that peer has no pieces (fresh leecher) |
| `16`| `Reject Request` | `uint32 index, uint32 begin, uint32 length` | BEP 6 rejection of block request |
| `17`| `Allowed Fast` | `uint32 index` | BEP 6 piece permitted to be requested even while choked |
| `20`| `Extended` | `uint8 extId, byte[] payload` | BEP 10 extension wrapper |

### 3.5 Inbound Server Socket & Info-Hash Demuxing
In compliance with BEP 3's bidirectional connectivity specification:
- `IncomingPeerServer` binds a listening `ServerSocket` across standard BitTorrent ports (`6881–6889`, with system-assigned fallback).
- When a remote peer connects, the server reads the initial 68-byte handshake within a 10-second timeout.
- It extracts the 20-byte `info_hash` and matches it against registered active sessions.
- If a matching `TorrentSession` is found, the socket is transferred to `PeerConnectionManager.acceptIncomingPeer()`, allowing the remote peer to immediately request pieces or provide unchoked blocks.
- If the `info_hash` is unknown or the session is full, the socket is closed cleanly.
- The active listening port is advertised in all tracker announce requests (`port` parameter) and DHT `announce_peer` queries.

---

## 4. BEP 10 & BEP 9 — Extension Protocol & Magnet Metadata Exchange

### 4.1 BEP 10 Extension Handshake
When both peers advertise extension support in the 68-byte handshake, the client sends an Extended Handshake message (`extended ID = 0`):

```bencode
d
  1:m d
    11:ut_metadata i1e
    6:ut_pex       i2e
  e
  13:metadata_size i16384e
e
```

- The `m` dictionary dynamically assigns local message IDs for sub-protocols.
- `metadata_size` advertises the byte size of the torrent `info` dictionary (omitted during initial magnet discovery).

### 4.2 BEP 9 `ut_metadata` Exchange
For magnet links (`magnet:?xt=urn:btih:...`), the client does not initially possess the `.torrent` metainfo. It downloads the `info` dictionary directly from peers over the wire:

- **Piece Size**: Fixed at 16 KiB (16,384 bytes). Total pieces = `ceil(metadata_size / 16384)`.
- **Message Types**:
  - `msg_type = 0` (**Request**): `d8:msg_typei0e5:piecei0ee`
  - `msg_type = 1` (**Data**): `d8:msg_typei1e5:piecei0e10:total_sizei16384ee<raw_bytes>`
  - `msg_type = 2` (**Reject**): `d8:msg_typei2e5:piecei0ee`
- **Reconstruction & Verification**:
  1. `MagnetMetadataFetcher` requests each piece concurrently across available peers.
  2. Assembled pieces are concatenated in order into a contiguous byte buffer.
  3. The buffer is verified against the 20-byte `TorrentId` SHA-1 hash.
  4. On match, `MetainfoParser.parseFromInfoBytes()` generates the `TorrentMetadata` domain model and persists the reconstructed `.torrent` file to disk.

---

## 5. BEP 5 — Mainline DHT (Kademlia)

The client implements an autonomous Mainline DHT node operating over UDP:

- **Node ID**: 160-bit identifier (`DhtNodeId`) generated with cryptographic entropy.
- **Metric**: XOR distance `d(x, y) = x ^ y` with bitwise comparison.
- **Routing Table**: K-bucket architecture (bucket size $k = 8$), splitting dynamically when full and holding the local node ID prefix.
- **Bootstrap Nodes**:
  - `router.bittorrent.com:6881`
  - `dht.transmissionbt.com:6881`
  - `router.utorrent.com:6881`
  - `dht.libtorrent.org:25401`
  - `dht.aelitis.com:6881`
- **RPC Queries**:
  - `ping`: Liveness check returning node ID.
  - `find_node`: Searches for closest contacts to a 160-bit target ID.
  - `get_peers`: Queries for peers with `info_hash`. Returns `values` (compact peer list) or `nodes` (closer contacts). Includes opaque write token.
  - `announce_peer`: Announces local port to swarm nodes holding write tokens.
- **Iterative Lookup**: Concurrency factor $\alpha = 4$, parallel querying with candidate distance sorting, and reactive peer emission via Kotlin coroutine `ChannelFlow`.

---

## 6. BEP 15 — UDP Tracker Protocol

UDP trackers avoid HTTP overhead and connection handshakes using binary datagram packets:

```
1. Connection Request:
   Offset  Size    Value
   0       int64   0x41727101980 (Protocol Magic ID)
   8       int32   0 (Action: connect)
   12      int32   transaction_id

2. Connection Response:
   0       int32   0 (Action: connect)
   4       int32   transaction_id
   8       int64   connection_id

3. Announce Request:
   0       int64   connection_id
   8       int32   1 (Action: announce)
   12      int32   transaction_id
   16      byte[20] info_hash
   36      byte[20] peer_id
   56      int64   downloaded
   64      int64   left
   72      int64   uploaded
   80      int32   event (0: none, 1: completed, 2: started, 3: stopped)
   84      int32   ip_address (0 default)
   88      int32   key
   92      int32   num_want (-1 default)
   96      uint16  port
```

- Automatic exponential backoff and timeout retransmission ($15 \times 2^n$ seconds up to 8 attempts).
- Responses deliver 6-byte compact IPv4 endpoints (`4 bytes IP + 2 bytes port`).

---

## 7. BEP 14 — Local Peer Discovery (LPD)

- **Multicast Group**: `239.192.152.143:6771` (IPv4).
- **Packet Structure**:
  ```http
  BT-SEARCH * HTTP/1.1
  Host: 239.192.152.143:6771
  Port: <client_port>
  Infohash: <hex_encoded_info_hash>


  ```
- **Behavior**: Transmits periodic search beacons every 5 minutes and listens continuously for local swarm peers on the LAN subnet, bypassing trackers for high-speed local transfers.

---

## 8. BEP 11 — Peer Exchange (PEX)

- Exchanged via BEP 10 extended message `ut_pex`.
- Payload format:
  - `added`: Compact 6-byte IPv4 addresses of newly discovered peers.
  - `added.f`: 1-byte peer flags (`0x01` encryption preferred, `0x02` seed).
  - `dropped`: Compact 6-byte IPv4 addresses of disconnected peers.
- Exchanged every 60 seconds with active peers to maintain high swarm connectivity.

---

## 9. Rate Limiting & Flow Control

- **Token Bucket Algorithm**: Lockless hierarchical bandwidth governor (`BandwidthManager`).
- **Granularity**: Replenishes byte tokens per millisecond based on user-configured limits (`KB/s`).
- **Scope**: Applied at both global and per-torrent levels across download and upload channels.
- **Block Pipelining**: Up to 8 outstanding 16 KiB block requests pipelined per unchoked peer. On choke or connection drop, pending requests are automatically returned to the download queue.

---

## 10. BEP 6 — Fast Extension

- **Negotiation**: Signaled during the 68-byte handshake by setting bit `0x04` in byte 7 of the reserved bits array (`reserved[7] |= 0x04`). When both client and remote peer advertise BEP 6 support:
  - **`HaveAll` (ID 14)**: Replaces a full bitfield when a seeder connects, eliminating unnecessary bitfield packet transmission. The receiver immediately marks all swarm pieces available (`PeerBitfield.setAll(true)`).
  - **`HaveNone` (ID 15)**: Notifies the remote peer that the sender starts with zero pieces without sending an all-zero bitfield.
  - **`SuggestPiece` (ID 13)**: Advisory message recommending a piece to download.
  - **`RejectRequest` (ID 16)**: Explicitly rejects an outstanding block request (`index`, `begin`, `length`), freeing up request pipeline capacity without waiting for socket timeouts.
  - **`AllowedFast` (ID 17)**: Grants permission to request blocks from the specified piece index even while the peer is choking us (`isChokingUs = true`). `PieceManager` specifically pipelines requests for allowed fast pieces during choked states.

---

## 11. Swarm Acceleration & NAT Traversal

- **Incoming TCP Server Socket (`IncomingPeerServer`)**:
  - Listens on TCP port range `6881–6889` (fallback to ephemeral port).
  - Enables full **Connectable** peer status: peers throughout the global swarm can initiate inbound connections to us.
  - Demultiplexes incoming connections by the 20-byte `info_hash` exchanged during the peer handshake, routing sockets to their respective `TorrentSession`.
  - Advertises the bound listening port in all tracker announce requests (`port` parameter) and DHT `announce_peer` queries.

- **UPnP IGD Port Forwarding (`UpnpClient`)**:
  - Implements lightweight, pure-Kotlin SSDP discovery (`239.255.255.250:1900`) for Internet Gateway Devices (IGD).
  - Automatically queries router XML device descriptors for `WANIPConnection` and `WANPPPConnection` control URLs.
  - Issues SOAP `AddPortMapping` requests to automatically map the incoming TCP download port through consumer NAT routers.
  - Cleanly unmaps forwarded ports via `DeletePortMapping` upon engine shutdown.

- **Aggressive Peer Cycling**:
  - Periodically audits active peer connections (every 5 seconds).
  - When queued peer candidates are waiting in the pool, any peer that has kept us choked for more than 30 seconds with no in-flight blocks is gracefully disconnected and cycled out.
  - Promptly fills freed connection slots with fresh candidates from the known peer pool, maximizing the probability of finding unchoked seeders.

- **Dynamic Wi-Fi Peer Limit Scaling**:
  - Automatically raises the maximum per-session peer connection limit from 30 up to 50–60 when connected to Wi-Fi.
  - Conserves mobile cellular bandwidth and battery while unleashing full hardware throughput on unmetered local networks.

---

## 12. Seeding Lifecycle & Swarm Reciprocity

- **Transition Semantics**: In accordance with the standard BitTorrent state model, when a client completes downloading 100% of verified piece hashes:
  - The client transitions from **Leecher** to **Seeder** (`TorrentState.SEEDING`).
  - The engine broadcasts `Have` (or `HaveAll` via BEP 6) to all active peer connections.
  - The client ceases issuing `Request` messages and begins serving incoming `Request` blocks from connected leechers via `UploadManager`'s 4-slot tit-for-tat choking algorithm with 30-second optimistic unchoke cycles.
- **Client Presentation & File Access**:
  - The torrent is badged as `"Completed • Seeding"` in the user interface.
  - It automatically populates the **Completed** category filter.
  - 1-tap "Open File" and "Share" actions are unlocked via Android `FileProvider`, allowing immediate media playback or file export while the client continues seeding in the background.


