# Security & Trust Boundaries — NuTorrent

**Specification Version:** v1.0 Production  
**Package:** `com.nutorrent.*`  

---

## 1. Threat Model & Untrusted Inputs

In peer-to-peer BitTorrent networks, remote peers, trackers, and external `.torrent` metainfo files must be considered fundamentally untrusted and potentially adversarial. `NuTorrent` employs defensive programming practices across all subsystems to protect device security, filesystem integrity, network bandwidth, and user privacy.

---

## 2. Path Traversal & Filesystem Protection

Malicious torrent files may craft filenames designed to escape destination directories and overwrite sensitive system or application files (e.g. `../../../../etc/passwd` or `..\..\Windows\System32`).

### 2.1 Multi-Layered Filename Sanitization
All file paths extracted from `.torrent` metainfo and BEP 9 `ut_metadata` dictionaries pass through `MetainfoParser.sanitizePathComponent()`:
- **Directory Traversal Suppression**: Sequences such as `..`, `.`, `/../`, and `\..\` are stripped.
- **Illegal Character Filtering**: Reserved filesystem characters (`/`, `\`, `:`, `*`, `?`, `"`, `<`, `>`, `|`, null bytes, control codes) are replaced with safe underscores (`_`).
- **Empty & Blank Checks**: Segment names resolving to empty strings or dots are rejected with `AppError.InvalidTorrent`.

### 2.2 Canonical Path Traversal Enforcement
Before writing any byte to disk, `FileTorrentStorage` computes the canonical target path:
```kotlin
val targetPath = File(baseDirectory, file.relativePath).toPath().normalize()
if (!targetPath.startsWith(baseDirectory.toPath().normalize())) {
    throw AppError.InvalidTorrent("Malicious relative path detected outside target boundary: ${file.relativePath}")
}
```
This guarantees that files can never be written outside the user-selected destination directory.

---

## 3. Storage Safety & Resource Allocation

### 3.1 Proactive Disk Space Verification (15 MB Buffer)
Unchecked writes on nearly full storage drives can corrupt Android file systems and crash apps. `FileTorrentStorage` queries available storage space before initiating downloads:
- Enforces an **emergency safety buffer of 15 MB** above the requested torrent size.
- If free storage is below `torrentSize + 15 MB`, downloads fail safely with `AppError.StorageFull`, switching the session to `TorrentState.ERROR` without corrupting disk state.

### 3.2 Random-Access Streaming
Pay-load files are mapped via discrete byte boundaries and accessed through `RandomAccessFile`. Multi-gigabyte ISOs and archives are never read into device RAM, eliminating out-of-memory crashes on resource-constrained Android devices.

---

## 4. Memory & Parser Bounding

Adversarial peers or malicious trackers could send malformed bencoded data designed to cause stack overflows, memory exhaustion, or buffer overflows.

- **Bencode Nesting Bounding**: Recursive dictionary and list parsing in `BencodeDecoder` is capped at **32 levels**, throwing `BencodeException` if exceeded.
- **String Allocation Limits**: Individual bencoded string tokens cannot exceed **64 MiB**. Tracker responses are bounded to **1 MiB**.
- **Wire Message Length Enforcement**: Incoming peer wire messages cannot exceed **131,081 bytes** (128 KiB maximum block payload + 9-byte header). Packets with length prefixes outside `[0, 131081]` trigger immediate peer disconnect and socket closure.
- **Connection Concurrency Caps**: Total peer connections per session are bounded (default 20–30), preventing OS socket descriptor exhaustion.

---

## 5. Cryptographic Data Integrity

### 5.1 Mandatory SHA-1 Piece Verification
- No piece is ever marked complete or committed to disk as verified based on byte count or peer assertions.
- When all 16 KiB blocks of a piece arrive in memory, the client computes the 20-byte SHA-1 digest using `MessageDigest.getInstance("SHA-1")` and compares it byte-for-byte against the expected hash in the metainfo dictionary.
- If verification passes:
  1. The piece data is written to disk via `FileTorrentStorage.writePiece()`.
  2. The local bitfield is updated.
  3. `HAVE` messages are broadcast to the swarm.
- If verification fails:
  1. The memory buffer is immediately wiped.
  2. The piece state is reset in `PieceManager`.
  3. The corrupt piece is quarantined and scheduled for re-download from alternate peers.

### 5.2 BEP 9 Magnet Metadata Integrity
During magnet link downloads, metadata pieces received via `ut_metadata` are assembled into a single byte buffer. The client hashes the assembled buffer with SHA-1 and verifies that it exactly equals the 20-byte `TorrentId` (info-hash) from the magnet link before parsing the `info` dictionary.

---

## 6. Privacy & Device Anonymity

`NuTorrent` is built for user privacy and minimal digital footprint:

- **Ephemeral Cryptographic Peer IDs**: Peer identifiers follow the Azureus style (`-NU1000-`) followed by 12 cryptographically random alphanumeric characters generated with Java `SecureRandom`. Peer IDs are regenerated per app run and contain no IMEI, Android ID, MAC address, serial number, or device identifiers.
- **Zero Third-Party SDKs & Telemetry**: The app contains zero analytics SDKs (no Google Analytics, Firebase Analytics, Mixpanel), zero advertising libraries, and zero cloud crash reporters.
- **Local-Only Persistence**: All torrent state, logs, and settings are stored locally on the device in Room SQLite (`nutorrent.db`) and Android `SharedPreferences`. No user data is ever transmitted to remote telemetry servers.
- **Transparent P2P Architecture**: Because BitTorrent inherently exposes client IP addresses to swarm peers and trackers, `NuTorrent` does not claim false "anonymous torrenting" and encourages lawful, transparent peer-to-peer usage.

---

## 7. FileProvider & Android Scoped Storage Boundaries

On modern Android versions (Android 11+ / API 30+), Scoped Storage restricts direct file system browsing inside `/Android/data/` for privacy protection. To allow users to play media and view documents securely:

### 7.1 Sandboxed URI Delegation
- All file opening and sharing flows utilize `androidx.core.content.FileProvider` under authority `${applicationId}.fileprovider`.
- Permitted directories are explicitly declared in `res/xml/file_paths.xml`:
  - `<external-files-path path="Downloads" ... />` for standard app download trees.
  - `<external-path path="." ... />` for user-selected SAF root folders.
- Internal private directories (databases, shared preferences, secure caches) are strictly excluded from the provider configuration, preventing third-party access to app state.

### 7.2 Read-Only Intent Permission Grants
- External viewers (video players, document readers, audio players) are granted transient, read-only permissions via `Intent.FLAG_GRANT_READ_URI_PERMISSION`.
- No third-party viewer is ever granted `FLAG_GRANT_WRITE_URI_PERMISSION`, ensuring external applications cannot corrupt or truncate downloaded torrent payloads.
- Bypasses `FileUriExposedException` and adheres to Android's strict principle of least privilege.

---

## 8. Inbound Server Socket & UPnP Port Security

Operating a listening TCP server on mobile devices requires robust defenses against external probes:

### 8.1 Strict BitTorrent Handshake Enforcement
- `IncomingPeerServer` binds to ports `6881–6889` and reads incoming connection headers within a strict 10-second timeout.
- The connection must strictly match the 68-byte BitTorrent handshake (`0x13` + `"BitTorrent protocol"`).
- Any non-BitTorrent packet, HTTP request, port scan probe, or malformed data immediately terminates the socket connection.
- Handshakes specifying an `info_hash` not currently registered in active torrent sessions are dropped immediately, preventing socket pool exhaustion and connection relaying.

### 8.2 Clean UPnP Port Lease Lifecycle
- `UpnpClient` only requests router port forwarding (`AddPortMapping`) when active torrent sessions are running.
- Upon engine shutdown or session termination, `DeletePortMapping` is called over SOAP HTTP to cleanly remove the forwarded NAT rule, leaving zero exposed ports on user routers when the app is idle.
