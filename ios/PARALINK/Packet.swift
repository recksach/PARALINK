import Foundation

struct MeshPacket {
    let kind: String
    let id: String
    let src: String
    let dst: String
    let ttl: Int
    let payload: String
}

enum PacketKinds {
    static let hello = "HELLO"
    static let txt = "TXT"
    static let voice = "VOICE"
    static let pay = "PAY"
}

enum PacketCodec {
    private static let marker = "PKT"
    private static let version = "1"

    static func encode(_ p: MeshPacket) -> String {
        [Self.marker, Self.version, p.id, p.src, p.dst, "\(p.ttl)", p.kind, p.payload].joined(separator: "|")
    }

    static func decode(_ line: String) -> MeshPacket? {
        let prefix = Self.marker + "|"
        guard line.hasPrefix(prefix) else { return nil }
        let parts = line.components(separatedBy: "|")
        guard parts.count >= 8, let ttl = Int(parts[5]) else { return nil }
        let payload = parts.dropFirst(7).joined(separator: "|")
        return MeshPacket(kind: parts[6], id: parts[2], src: parts[3], dst: parts[4], ttl: ttl, payload: payload)
    }
}

enum RelayCore {
    static let broadcast = AppConfig.broadcastAll

    static func shouldDeliverLocally(_ packet: MeshPacket, me: String) -> Bool {
        packet.dst == AppConfig.broadcastAll || packet.dst == me
    }

    static func shouldForward(_ packet: MeshPacket, me: String) -> Bool {
        packet.dst != me && packet.ttl > 0
    }

    static func nextHop(_ packet: MeshPacket) -> MeshPacket {
        MeshPacket(kind: packet.kind, id: packet.id, src: packet.src, dst: packet.dst, ttl: packet.ttl - 1, payload: packet.payload)
    }
}