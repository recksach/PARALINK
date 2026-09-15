# OPENCODE MASTER PROMPT — PARALINK

You are continuing development of PARALINK, an Android application intended to become a self-contained, infrastructure-independent communication mesh.

## Absolute product rule

The core application MUST NOT require any external server, API, cloud, Firebase, DNS, authentication server, remote license check, remote key server, or mandatory update service.

Internet must be optional. If internet disappears, the core system must continue to work as far as the device hardware and Android platform permit:

- peer discovery
- pairing
- encrypted local messaging
- file transfer
- push-to-talk
- voice communication
- local routing
- multi-hop relay
- store-and-forward
- local network map/radar
- local identity

No core code may directly depend on a cloud endpoint.

## Product identity

Name: PARALINK
Package: `com.paralink.app`
Platform: Android
Style: dark radar / premium messenger / futuristic network visualization

## Primary architecture

```text
UI
 ↓
Domain
 ↓
Mesh Core
 ↓
Transport Abstraction
 ├─ Wi-Fi Aware
 ├─ Wi-Fi Direct
 ├─ Local-only Wi-Fi
 ├─ Bluetooth discovery/control
 └─ Optional internet transport
```

Never hard-wire application logic to one transport.

## Device identity

Each installation creates a local identity.

Must include:

- Node ID
- public identity key
- private key stored in Android Keystore
- display name
- avatar/profile metadata
- capabilities

Never use a phone number, public IP, IMEI or MAC address as the permanent user identity.

## Security

Do not invent cryptographic algorithms.

Use Android Keystore and reviewed cryptographic primitives/libraries.

End-to-end encryption is required for actual production messages.

Relay nodes must not need access to plaintext content.

Do not send private keys to remote infrastructure.

## Mesh protocol

Implement real packet envelopes with:

- protocol version
- packet id
- source node
- destination node
- previous hop
- TTL
- timestamp/epoch
- priority
- payload type
- route metadata
- encrypted payload

Protect against duplicate packets using packet ids.

Implement route discovery and route recovery.

The first routing implementation may be BFS over an in-memory topology for testing, but production must move to a link-quality-aware multi-hop routing protocol with:

- hop count
- latency
- loss rate
- link quality
- bandwidth
- node availability
- battery-aware relay policy

## Offline-first behavior

All critical communication state must be local.

Queued messages/files must survive app restarts.

When a destination disappears, use store-and-forward.

When a new route becomes available, continue delivery.

## Discovery

Implement capability detection for:

1. Wi-Fi Aware
2. Wi-Fi Direct
3. local-only Wi-Fi
4. Bluetooth discovery/control

Android versions and hardware vary. Never assume support.

Request only the permissions needed for the active feature.

## Wi-Fi Direct

Implement:

- discovery
- peer list
- pairing/connect
- group information
- local socket transport
- connection lifecycle
- reconnect handling

The current project already contains the first discovery layer.

## Wi-Fi Aware

Implement a production `WifiAwareTransport` after verifying device compatibility.

Required lifecycle:

```text
attach
→ publish/subscribe
→ discovery
→ secure handshake
→ network specifier / local data path
→ socket or transport stream
→ teardown
```

## Multi-hop mesh

Three-phone acceptance test:

```text
A      B      C
●──────●──────●
```

A and C must be unable to communicate directly but must communicate through B when B is an enabled relay.

Four-phone route recovery:

```text
A → B → C
 \
  → D → C
```

If B disappears, routing must move to D when valid.

## Store-and-forward

A relay may store encrypted packets locally when the destination is temporarily unreachable.

Storage must use quotas and expiration.

Packets require TTL/expiry.

## Messaging

Production chat must support:

- text
- delivery states
- local queue
- direct or relayed delivery
- encrypted local persistence
- duplicate suppression
- resend/resume

## Files

Files must be chunked.

Each chunk requires integrity validation.

Transfers must resume after interruption.

Do not keep multiple unnecessary copies.

## Push-to-talk

Implement press-and-hold voice capture.

Use Opus or another well-supported voice codec.

Release = finalize/send.

Support relay transport.

## Voice/video

Production goal:

- audio call
- video call
- adaptive bitrate
- graceful downgrade to audio-only

A multi-hop video call is NOT a prerequisite for MVP. Prioritize robust text/file/PTT communication first.

## Radar UI

Central user node.

Nearby nodes appear as circles.

Show connection state and approximate distance only when a valid measurement is available.

Never fabricate precision.

## World map

The world map is a separate visualization layer.

It must not be required for local communication.

Do not expose exact public user coordinates.

Global map must use aggregated cells/regions and anonymous counts.

Small networks use coarse regions.

The global map should continue to work as a local/embedded map even when internet is absent.

## Global user count

A fully offline, partitioned world has no mathematically reliable global total unless network partitions exchange state.

Therefore implement:

- local cluster counts
- merged network summaries
- epochs
- deduplication
- signed/validated summaries

Do not pretend an exact global count exists when the network is partitioned.

## Map data

Do not depend on Google Maps.

Use a self-contained map layer such as MapLibre with embedded/local vector or simplified world data.

The base APK should include enough geography for offline network visualization without requiring a tile server.

## Wallet

First stage: local `PARA Credits` ledger architecture.

Must work offline.

Never imply monetary value in the local credits before a real audited token system exists.

Wallet primitives:

- local key ownership
- receive
- send
- address
- activity
- local transaction log

## Tokenization

Do NOT add a live exchange/listing as part of the MVP.

First build:

```text
network
→ usage
→ contribution measurement
→ anti-abuse
→ local credits
→ audited ledger
```

Only later evaluate blockchain/token deployment.

## Relay rewards

Relay rewards must resist Sybil abuse.

Never simply reward unlimited node creation.

Require rate limits, contribution measurements and anti-abuse mechanisms.

## Privacy

Global/public layer must never publish by default:

- exact GPS
- IP
- MAC
- IMEI
- serial
- phone number
- hidden device identifiers

Discovery visibility is user-controlled.

Relay participation is user-controlled.

## Power management

Relay mode must have user-selectable policies:

OFF
LOW
BALANCED
HIGH
MAXIMUM

Optionally do not relay below a user-defined battery threshold.

## Android background rules

Follow current Android foreground-service and background execution rules. Never attempt to bypass Android security restrictions.

Camera/microphone/network background access must be explicit and policy-compliant.

## UI requirements

Visual language:

- dark navy background
- electric blue
- cyan highlights
- radar circles
- glowing nodes
- minimal premium messenger interface
- smooth but battery-conscious animations

Do not make it look like a cheap crypto mining/tap-to-earn application.

Primary tabs:

- Network
- Chat
- Radio
- Wallet
- Profile

## Developer mode

Add a developer panel capable of simulating:

- 10 nodes
- 50 nodes
- 100 nodes
- 500 nodes
- 1000 virtual nodes

and simulate:

- node failure
- packet loss
- route changes
- latency
- bandwidth changes
- battery state

## Acceptance milestones

### M1 — Two devices

Without internet:

- discover
- pair
- establish secure session
- text message
- file transfer
- voice message

### M2 — Three devices

A ↔ B ↔ C

A and C communicate via B.

### M3 — Store-forward

Disconnect C, queue encrypted message at B, reconnect C, deliver automatically.

### M4 — Route recovery

Break B, route through D.

### M5 — PTT

Press-to-talk voice over direct and relay transport.

### M6 — Radar

Show real nearby nodes and link state.

### M7 — Offline world map

Embedded geography + anonymous local network visualization.

### M8 — Wallet foundation

Local key/address/transaction model.

### M9 — Simulator

Virtual 1000-node tests.

## Build requirements

Must build:

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

No manual source edits after checkout.

Add unit tests for protocol and routing.

Add instrumentation tests for Android permissions and discovery lifecycle.

## Definition of done for a release

A release is only considered complete after testing on at least two physical Android devices.

Never mark a networking feature "complete" based only on compilation.

The code must distinguish clearly between:

- implemented
- experimental
- simulator-only
- planned

Do not fake functionality with placeholder UI once implementation starts.
