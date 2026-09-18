# PARALINK — iOS

Native iOS client for PARALINK mesh. Pairs with the Android app over a shared
Wi-Fi network (or by IP) using the same wire protocol, end-to-end crypto and
radar/wallet features.

## Requirements

- Xcode 15.4+ (Xcode 16 recommended)
- iOS 15.0+ device or simulator
- iPhone (or iPad) — no Mac required for the device itself, but the build needs
  a Mac with Xcode

## Building

1. Open `ios/PARALINK.xcodeproj` in Xcode (this is the folder `<repo>/ios`).
2. Select your team in **Signing & Capabilities** (Target `PARALINK`) so a
   development certificate is created — iOS apps cannot be installed without
   at least a free personal team.
3. Connect your iPhone, select it as the run destination:
   `Product ▸ Destination ▸ <Your iPhone>`.
4. `Product ▸ Run` (⌘R). When Xcode asks, confirm "Allow" on the phone for the
   developer profile in **Settings ▸ General ▸ VPN & Device Management**.
5. First launch: the guide screen explains the flow. Allow **Microphone** (for
   voice) and, if you want radar positioning, **Location** (GPS stays optional —
   without it radar sorts blips by connection order).

That's it — the app builds itself on the device; **nothing is uploaded to the
Internet**.

The bundle id used is `com.paralink.ios` (changeable in
`PARALINK.xcodeproj/project.pbxproj`).

## Android ⇄ iOS pairing

- Both phones **must be in the same subnet** of a Wi-Fi network (same router,
  or one phone as hotspot and the other connected to it).
- Keep the **Network** screen open on both devices. The app sends beacons and
  keeps listening on the LAN; the phones discover each other automatically.
- If auto-discovery does not find the peer (some routers block multicast —
  guest networks, AP isolation):
  1. On the iPhone open **Settings ▸ Wi-Fi ▸ ⓘ** on the connected network and
     read the **IP address** (e.g. `192.168.1.20`).
  2. Enter that IP in the Android **Network screen ▸ Connect by IP** field and
     press connect. The iPhone answers on the standard port and the link gets
     established.
- Classic Bluetooth RFCOMM lives only on Android; iOS does not expose that API,
  so Android⇄iOS pairing goes over the local Wi-Fi. Android⇄Android pairing
  keeps working over Bluetooth with no Wi-Fi at all.

## What works

- Radar with real GPS positions (need Location ON) — tap a blip for a private
  chat, hold a blip to send a voice wave to that specific person.
- Private E2E encrypted chat (ECDH P-256 + HKDF-SHA256 + AES-128-GCM, same
  keys as Android).
- Voice "walkie-talkie" — hold the red button, release to send; incoming voice
  plays directly (skips the network entirely — pure peer-to-peer).
- PARA wallet: earn while the app is connected to a peer, spend in the Store.
  Tokens (PAY packets) travel the same encrypted channel.
- English / Russian UI.

## Known limitations

- "Offline translation" of incoming messages is not implemented on iOS yet
  (message text always arrives untranslated).
- Push notifications not supported (by design — nothing goes through servers).
- No manual unread badges; chat history is stored locally only.
- Safari/App Store distribution requires paid Apple Developer ($99/year) and
  an additional review — this repo is a do-it-yourself sideload build.

## Wire protocol reference

See Android sources: `app/src/main/java/com/paralink/app/core/mesh/MeshPacket.kt`
(`PKT|1|id|src|dst|ttl|kind|payload` over TCP 49152, beacon over UDP 49151,
multicast group `239.255.255.250`) and
`core/crypto/MeshCrypto.kt` + `Hkdf.kt`. Swift mirrors are
`ios/PARALINK/Packet.swift` and `MeshCrypto.swift`.