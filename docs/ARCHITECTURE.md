# PARALINK Architecture

## Non-negotiable principle

Core communication does not depend on remote infrastructure.

Internet is optional.

## Layers

### Presentation
Jetpack Compose screens and state.

### Identity
Local node identity and Android Keystore.

### Security
Session establishment, encryption, signatures, replay protection.

### Transport
A common interface for Wi-Fi Aware, Wi-Fi Direct, local-only Wi-Fi, Bluetooth and optional internet transport.

BLUETOOTH is the current primary local transport. It advertises a GATT
service, scans for that service UUID, and runs a full link/handshake stack
(`connectivity/bluetooth/`): bidirectional GATT characteristics, MTU
negotiation, binary "PRLK" frames, HMAC-authenticated tagging, per-fragment
ACK + retransmission, and a SAS code shown on both ends for visual
verification. The older Wi-Fi Direct / RFCOMM path remains in the codebase
but is no longer auto-started.

### Mesh
Discovery, topology, route selection, forwarding, queueing and store-forward.

### Data
Local messages, files, peers, routes, wallet records and network summaries.

### Economy
Local credits/ledger only in early releases.

## Dependency direction

```text
UI → domain/core → mesh → transport
```

Never allow a UI component to call Android Wi-Fi APIs directly.

## Node lifecycle

```text
START
  ↓
LOAD IDENTITY
  ↓
CAPABILITY DETECTION
  ↓
DISCOVERY
  ↓
PEER HANDSHAKE
  ↓
SESSION
  ↓
TOPOLOGY UPDATE
  ↓
ROUTING
  ↓
APPLICATION TRAFFIC
```

## Failure model

Everything is expected to fail intermittently:

- device disappears
- radio changes state
- app backgrounded
- route breaks
- packet is duplicated
- packet is lost
- device battery becomes low
- capability is unavailable

The mesh layer must be resilient to all of these.

## Protocol evolution

Protocol messages must contain a version.

Do not break an old installed client unnecessarily.

New capabilities should be negotiated.

## Global statistics

Never make the global statistics service a dependency of local communication.

The future online/global layer is an accelerator and visualization layer, not the network core.
