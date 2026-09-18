import Foundation
import Network

enum AppConfig {
    static let tcpPort: NWEndpoint.Port = 49152
    static let udpPort: UInt16 = 49151
    static let udpPortEndpoint: NWEndpoint.Port = 49151
    static let multicastGroup = "239.255.255.250"
    static let broadcastAll = "BROADCAST"
    static let maxTtl = 3
    static let salt = "PARALINK_E2E_V1"
    static let info = "PARALINK_SESSION_KEY_V1"
    static let beaconInterval: TimeInterval = 3.0
    static let probeInterval: TimeInterval = 30.0
    static let beaconTtlMs: Int64 = 20_000
    static let earnPerLinkPerMin = 0.05
    static let appVersion = "0.3.7"
}