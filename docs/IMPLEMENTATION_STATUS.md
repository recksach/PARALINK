# PARALINK implementation status

## Implemented
- local device identity
- Android Keystore-backed identity foundation
- Wi-Fi Direct discovery
- capability detection for Wi-Fi Aware
- P2P connection request
- TCP server on local P2P network
- TCP peer session handling
- bidirectional text message packet format
- local persistent message store
- Compose network/radar/chat/profile/wallet/radio UI
- **BLE-only local transport (default):** Bluetooth Low Energy advertisement,
  scanning, GATT server + client, connection-less pairing. Replaces the
  Wi-Fi Direct / RFCOMM autostart path as the primary transport.
- **Authenticated link protocol:** binary "PRLK" frames, triple-ECDH
  (P-256) + HKDF session key, HMAC-SHA256 per-frame authentication,
  SAS code for manual verification, per-fragment ACK + retransmission,
  fragmentation/reassembly.
- **Bluetooth test suite (43 unit tests):** frame codec, tamper/zero-key
  rejection, fragmentation/reassembly (out-of-order, duplicates),
  AckManager lifecycle, SessionCrypto symmetric key agreement, SAS format.
- Bluetooth diagnostics screen (live devices, RSSI, MTU, event log).

## Not yet production-complete
- robust authenticated end-to-end session protocol (BLE link layer `linkKey`
  MAC is active; application-layer store-and-forward still a prototype)
- AUTH-frame-drop handshake recovery (initiator that drops an early AUTH
  frame has no re-send scheduler yet -> possible deadlock in edge cases)
- strict SAS gating (CONNECTED is auto-established after mutual AUTH
  verification; the SAS dialog is informational)
- general multi-hop routing across arbitrary Android topologies
- reliable relay/duplicate suppression/store-and-forward protocol
- file chunk transport/resume
- realtime low-latency voice calls
- video calls
- fully decentralized global census
- production blockchain/token
- offline global map data packaging

## Physical validation requirement
Run two or more real Android devices. Android **Bluetooth** behaviour differs
by manufacturer, OS build and BLE chipset. A feature is not considered done
until it is verified on physical hardware — this applies especially to the new
BLE transport (advertising service UUID filtering, GATT MTU negotiation,
peripheral-initiated connections).
