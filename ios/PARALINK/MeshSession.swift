import Foundation
import Network
import Combine

final class MeshSession {
    struct Ev {
        enum EvType { case peers, connected, message, voice, token, disconnected, error }
        let type: EvType
        let nodeId: String?
        let nodeName: String?
        let text: String?
        let wavB64: String?
        let durationMs: Int64
        let amount: Double
        let note: String?
    }

    private let identity: IdentityStore
    private let crypto: MeshCrypto
    private let queue = DispatchQueue(label: "paralink.mesh", qos: .userInitiated)
    private let main = DispatchQueue.main

    private var listener: NWListener?
    private var peers: [PeerLink] = []
    private var networkKeys: [String: String] = [:]
    private var pairwise: [String: SymmetricKey] = [:]
    private var lastSeen: [String: Int64] = [:]
    private var nodeNames: [String: String] = [:]
    private var nodeAddr: [String: String] = [:]
    private var nodeCoords: [String: (Double, Double)] = [:]
    private var seenIds = Set<String>()
    private var currentChannel = "*"
    private var myLat: Double = 0
    private var myLon: Double = 0
    private var deepScan = false
    private var nodeStyle: [String: (gold: Bool, badge: String?)] = [:]
    var onEvent: ((Ev) -> Void)?

    init(identity: IdentityStore) {
        self.identity = identity
        self.crypto = MeshCrypto(identity: identity)
    }

    var myName: String { identity.myName }

    // MARK: - lifecycle

    func start() {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.stopListenerQuiet()
            do {
                let l = try NWListener(using: .tcp, on: AppConfig.tcpPort)
                self.listener = l
                l.newConnectionHandler = { [weak self] conn in
                    guard let self = self else { return }
                    self.queue.async { self.peer(connection: conn, remoteHint: "incoming") }
                }
                l.start(queue: self.queue)
            } catch {
                self.emit(Ev(type: .error, nodeId: nil, nodeName: nil, text: "Server error: \(error.localizedDescription)", wavB64: nil, durationMs: 0, amount: 0, note: nil))
            }
        }
        Discoverer.shared.session = self
        Discoverer.shared.start()
    }

    func stop() {
        stopListenerQuiet()
        queue.async { [weak self] in
            guard let self = self else { return }
            for p in self.peers { p.close() }
            self.peers.removeAll()
        }
    }

    private func stopListenerQuiet() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - outgoing connections

    func connectToIp(_ address: String) {
        let clean = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        queue.async { [weak self] in
            guard let self = self else { return }
            self.connectTo(host: clean, remoteHint: "manual-\(clean)")
        }
    }

    func connectTo(host: String, port: NWEndpoint.Port, completion: ((Bool) -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self = self else { completion?(false); return }
            self.connectTo(host: host, remoteHint: "manual-\(host)")
            completion?(true)
        }
    }

    private func connectTo(host: String, remoteHint: String) {
        let conn = NWConnection(host: NWEndpoint.Host(host), port: AppConfig.tcpPort, using: .tcp)
        peer(connection: conn, remoteHint: remoteHint)
    }

    func setMyLocation(lat: Double, lon: Double) {
        if lat != 0 && lon != 0 {
            myLat = lat; myLon = lon
        }
    }

    func setDisplayName(_ name: String) { identity.setMyName(name) }
    func setChannel(_ ch: String) { currentChannel = ch.trimmingCharacters(in: .whitespaces).isEmpty ? "*" : ch.trimmingCharacters(in: .whitespaces) }
    func setDeepScan(_ on: Bool) { deepScan = on }

    // MARK: - public query

    func radarNodes() -> [RadarNode] {
        var result: [RadarNode] = []
        var ids = Set<String>()
        for (nid, _) in networkKeys { ids.insert(nid) }
        for nid in lastSeen.keys { ids.insert(nid) }
        let now = Date().timeIntervalSince1970 * 1000
        let ttl: Int64 = deepScan ? 45_000 : AppConfig.beaconTtlMs
        for nid in ids where nid != identity.nodeId && now - (lastSeen[nid] ?? 0) < ttl {
            let coord = nodeCoords[nid] ?? (0, 0)
            let style = nodeStyle[nid] ?? (false, nil)
            result.append(RadarNode(id: nid, name: nodeNames[nid] ?? nid, lastSeen: lastSeen[nid] ?? 0, lat: coord.0, lon: coord.1, gold: style.0, badge: style.1))
        }
        return result.sorted { $0.id < $1.id }
    }

    func knownPeers() -> [(id: String, name: String)] {
        Array(networkKeys.keys).filter { $0 != identity.nodeId }.sorted().map { ($0, nodeNames[$0] ?? $0) }
    }

    func knownIds() -> [String] { knownPeers().map { $0.id } }

    // MARK: - sending

    func sendText(text: String) { sendCore(kind: PacketKinds.txt, plainBody: txBody(text), target: nil, visual: text) }
    func sendTextTo(_ target: String, text: String) { sendCore(kind: PacketKinds.txt, plainBody: txBody(text), target: target, visual: text) }
    func sendVoice(wav: String, duration: Int64) { sendCore(kind: PacketKinds.voice, plainBody: voiceBody(wav, duration: duration), target: nil, visual: "voice \(duration)") }
    func sendVoiceTo(_ target: String, wav: String, duration: Int64) { sendCore(kind: PacketKinds.voice, plainBody: voiceBody(wav, duration: duration), target: target, visual: "voice \(duration)") }
    func sendToken(_ target: String, amount: Double, note: String) { sendCore(kind: PacketKinds.pay, plainBody: payBody(amount, note: note), target: target, visual: "PAY \(amount)") }

    private func txBody(_ text: String) -> String {
        let clean = text.replacingOccurrences(of: "|", with: " ")
        let name = identity.myName.replacingOccurrences(of: "|", with: "_")
        return "TXT|\(name)|\(UUID().uuidString)|\(nowMs())|\(currentChannel)|\(clean)"
    }

    private func voiceBody(_ b64: String, duration: Int64) -> String {
        let name = identity.myName.replacingOccurrences(of: "|", with: "_")
        return "VOICE|\(name)|\(UUID().uuidString)|\(nowMs())|\(currentChannel)|\(duration)|\(b64)"
    }

    private func payBody(_ amount: Double, note: String) -> String {
        let name = identity.myName.replacingOccurrences(of: "|", with: "_")
        let clean = note.replacingOccurrences(of: "|", with: " ")
        return "PAY|\(name)|\(UUID().uuidString)|\(nowMs())|\(amount)|\(clean)"
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private func sendCore(kind: String, plainBody: String, target: String?, visual: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            var targets = target.map { [$0] } ?? Array(self.networkKeys.keys.filter { $0 != self.identity.nodeId })
            if let t = target, !self.networkKeys.keys.contains(t) {
                if let addr = self.nodeAddr[t] {
                    let already = self.peers.contains { $0.remoteAddress == addr }
                    if !already { self.connectTo(host: addr, remoteHint: "direct-\(t)") }
                }
                usleep(900_000)
            }
            if targets.isEmpty {
                self.emit(Ev(type: .error, nodeId: nil, nodeName: nil, text: "No peers known yet. Open PARALINK on a nearby device first.", wavB64: nil, durationMs: 0, amount: 0, note: nil))
                return
            }
            for t in targets {
                do {
                    let key = try self.pairwiseKey(t)
                    let enc = try self.crypto.encrypt(key, plain: Data(plainBody.utf8))
                    let pkt = MeshPacket(kind: kind, id: UUID().uuidString, src: self.identity.nodeId, dst: t, ttl: AppConfig.maxTtl, payload: enc)
                    self.broadcast(pkt)
                } catch {
                    self.emit(Ev(type: .error, nodeId: nil, nodeName: nil, text: "Send failed: \(error.localizedDescription)", wavB64: nil, durationMs: 0, amount: 0, note: nil))
                }
            }
        }
    }

    // MARK: - peer plumbing

    func peer(connection: NWConnection, remoteHint: String) {
        let link = PeerLink(connection: connection, remoteHint: remoteHint)
        peers.append(link)
        link.onReady = { [weak self] in
            guard let self = self else { return }
            let hello = MeshPacket(kind: PacketKinds.hello, id: UUID().uuidString, src: self.identity.nodeId, dst: AppConfig.broadcastAll, ttl: AppConfig.maxTtl, payload: self.crypto.myPublicKeyB64)
            link.send(PacketCodec.encode(hello))
        }
        link.onLine = { [weak self] line in self?.queue.async { self?.handleLine(line) } }
        link.onStop = { [weak self] in
            guard let self = self else { return }
            self.queue.async {
                self.peers.removeAll { $0 === link }
            }
        }
        link.start(queue: queue)
    }

    func broadcast(_ packet: MeshPacket) {
        let line = PacketCodec.encode(packet)
        for p in peers where p.isReady {
            p.send(line)
        }
    }

    // MARK: - protocol

    private func handleLine(_ line: String) {
        guard let packet = PacketCodec.decode(line) else { return }
        if seenIds.contains(packet.id) { return }
        seenIds.insert(packet.id)
        if seenIds.count > 4096 { seenIds.removeAll() }
        lastSeen[packet.src] = nowMs()

        if packet.kind == PacketKinds.hello {
            networkKeys[packet.src] = packet.payload
            emit(Ev(type: .connected, nodeId: packet.src, nodeName: nodeNames[packet.src] ?? packet.src, text: nil, wavB64: nil, durationMs: 0, amount: 0, note: nil))
            emit(Ev(type: .peers, nodeId: nil, nodeName: nil, text: nil, wavB64: nil, durationMs: 0, amount: 0, note: nil))
            if RelayCore.shouldForward(packet, me: identity.nodeId) { broadcast(RelayCore.nextHop(packet)) }
            return
        }

        let mine = RelayCore.shouldDeliverLocally(packet, me: identity.nodeId)
        if mine, let keyB64 = networkKeys[packet.src] {
            do {
                let key = try pairwiseKey(packet.src)
                let plain = try crypto.decrypt(key, payloadB64: packet.payload)
                deliver(packet, plain: plain)
            } catch {
                emit(Ev(type: .error, nodeId: nil, nodeName: nil, text: "Decrypt failed: \(error.localizedDescription)", wavB64: nil, durationMs: 0, amount: 0, note: nil))
            }
        }
        if RelayCore.shouldForward(packet, me: identity.nodeId) { broadcast(RelayCore.nextHop(packet)) }
    }

    private func pairwiseKey(_ peerId: String) throws -> SymmetricKey {
        if let k = pairwise[peerId] { return k }
        guard let b64 = networkKeys[peerId] else { throw NSError(domain: "Mesh", code: 3, userInfo: [NSLocalizedDescriptionKey: "no key for \(peerId)"]) }
        let k = try crypto.sessionKey(peerPublicKeyB64: b64)
        pairwise[peerId] = k
        return k
    }

    private func onChannel(_ ch: String?) -> Bool {
        currentChannel == "*" || ch == currentChannel
    }

    private func deliver(_ packet: MeshPacket, plain: Data) {
        let text = String(data: plain, encoding: .utf8) ?? ""
        let parts = text.components(separatedBy: "|")
        switch packet.kind {
        case PacketKinds.txt:
            let channelOk = onChannel(parts[4]) || packet.dst == identity.nodeId
            guard parts.count >= 6, parts[0] == "TXT", channelOk else { return }
            nodeNames[packet.src] = parts[1]
            let body = parts.dropFirst(5).joined(separator: "|")
            emit(Ev(type: .message, nodeId: packet.src, nodeName: parts[1], text: body, wavB64: nil, durationMs: 0, amount: 0, note: nil))
        case PacketKinds.voice:
            let channelOk = onChannel(parts[4]) || packet.dst == identity.nodeId
            guard parts.count >= 7, parts[0] == "VOICE", channelOk else { return }
            nodeNames[packet.src] = parts[1]
            let dur = Int64(parts[5]) ?? 0
            let wav = parts.dropFirst(6).joined(separator: "|")
            emit(Ev(type: .voice, nodeId: packet.src, nodeName: parts[1], text: nil, wavB64: wav, durationMs: dur, amount: 0, note: nil))
        case PacketKinds.pay:
            guard parts.count >= 6, parts[0] == "PAY" else { return }
            nodeNames[packet.src] = parts[1]
            let amount = Double(parts[4]) ?? 0
            emit(Ev(type: .token, nodeId: packet.src, nodeName: parts[1], text: parts[1], wavB64: nil, durationMs: 0, amount: amount, note: parts[5]))
        default:
            break
        }
    }

    func handleBeacon(_ msg: String, fromAddr: String) {
        let parts = msg.components(separatedBy: "|")
        guard parts.count >= 4, parts[0] == "BEACON", parts[1] != identity.nodeId else { return }
        let senderId = parts[1]
        let senderName = parts[2]
        let senderPort = Int(parts[3]) ?? Int(AppConfig.tcpPort.rawValue)
        nodeNames[senderId] = senderName
        nodeAddr[senderId] = fromAddr
        if parts.count >= 7 {
            let lat = Double(parts[5]) ?? 0
            let lon = Double(parts[6]) ?? 0
            if lat != 0 && lon != 0 { nodeCoords[senderId] = (lat, lon) }
        }
        if parts.count >= 8 {
            let gold = parts[7] == "1"
            let badge: String? = parts.count >= 9 && !parts[8].isEmpty ? parts[8] : nil
            nodeStyle[senderId] = (gold, badge)
        }
        lastSeen[senderId] = nowMs()
        emit(Ev(type: .peers, nodeId: nil, nodeName: nil, text: nil, wavB64: nil, durationMs: 0, amount: 0, note: nil))
        guard !networkKeys.keys.contains(senderId) else { return }
        if nodeAddr[senderId] != nil {
            connectTo(host: fromAddr, remoteHint: "lan-\(senderId)")
        }
    }

    func makeBeacon() -> String {
        "BEACON|\(identity.nodeId)|\(identity.myName.replacingOccurrences(of: "|", with: "_"))|\(AppConfig.tcpPort.rawValue)|CH|\(myLat)|\(myLon)|0|"
    }

    private func emit(_ ev: Ev) {
        main.async { [weak self] in
            self?.onEvent?(ev)
        }
    }
}