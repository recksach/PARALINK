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

## Not yet production-complete
- robust authenticated end-to-end session protocol (current transport is a functional prototype)
- general multi-hop routing across arbitrary Android topologies
- reliable relay/duplicate suppression/store-and-forward protocol
- file chunk transport/resume
- realtime low-latency voice calls
- video calls
- fully decentralized global census
- production blockchain/token
- offline global map data packaging

## Physical validation requirement
Run two or more real Android devices. Android Wi-Fi Direct behaviour varies by manufacturer and OS build. A feature is not considered done until it is verified on physical hardware.
