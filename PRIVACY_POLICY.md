# Privacy Policy for NuTorrent

**Last Updated:** October 7, 2026

NuTorrent ("we", "our", or "the app") is an open, client-side BitTorrent client application designed with privacy as a foundational principle. We believe that your data belongs to you. This Privacy Policy describes how NuTorrent handles information when you use our Android application (`com.nutorrent.app`).

---

### 1. No Personal Data Collection

* **No Accounts or Registration**: NuTorrent does not require user accounts, logins, email addresses, phone numbers, or passwords.
* **No Analytics or Trackers**: We do not embed any third-party analytics SDKs, advertising networks, tracking libraries, or behavior profiling tools (e.g., Google Analytics, Firebase Analytics, Facebook SDK).
* **No Telemetry**: We do not collect or transmit telemetry, device identifiers (IMEI, Android ID, MAC address), or usage statistics to external servers.

---

### 2. Peer-to-Peer (BitTorrent) Network Communication

NuTorrent functions as a decentralized peer-to-peer (P2P) file transfer tool based on standard BitTorrent protocols (BEP 3, BEP 5, BEP 9, BEP 10, BEP 11):

* **IP Addresses**: When downloading or seeding torrents, your device communicates directly with public BitTorrent trackers, DHT (Distributed Hash Table) nodes, and other peers in the swarm. As an inherent requirement of peer-to-peer networking over TCP/UDP, your IP address is visible to other nodes in the swarm you choose to connect to.
* **No Proxy or Intermediary**: NuTorrent does not route your traffic through proprietary proxy servers or collect records of your swarms, downloads, or IP addresses.

---

### 3. Local Data Storage & Permissions

All application data is stored strictly on your local device:

* **Local Database**: Torrent metadata, download progress, and user preferences are saved locally in an offline Room database (`nutorrent.db`) and Android private SharedPreferences. This data never leaves your device.
* **Storage Access**: NuTorrent requests Android file/storage access solely to save downloaded files to your chosen download directory and to open `.torrent` files you select.
* **Foreground Service**: NuTorrent utilizes a foreground notification service (`FOREGROUND_SERVICE_DATA_SYNC`) to maintain active downloads in the background when requested by the user.

---

### 4. Third-Party Services

NuTorrent does not integrate with third-party cloud storage, ad networks, or data brokers. If you open downloaded files using third-party applications (such as media players or document viewers), those external applications operate under their own independent privacy policies.

---

### 5. Children's Privacy

NuTorrent is not intended for use by children under the age of 13. We do not knowingly collect any personal information from children.

---

### 6. Changes to this Policy

If we update this Privacy Policy in the future, the updated version will be posted here with an updated "Last Updated" date.

---

### 7. Contact Us

If you have questions or inquiries regarding this Privacy Policy or NuTorrent, you can reach out via GitHub Issues or contact:
* **Project Repository**: https://github.com/nutorrent/nutorrent
* **Email**: support@nutorrent.app
