import Foundation
import Network
import Darwin

final class PeerLink {
    let remoteAddress: String
    private let connection: NWConnection
    private var buffer = Data()
    private(set) var isReady = false

    var onReady: (() -> Void)?
    var onLine: ((String) -> Void)?
    var onStop: (() -> Void)?

    init(connection: NWConnection, remoteHint: String) {
        self.connection = connection
        self.remoteAddress = remoteHint
    }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            switch state {
            case .ready:
                self.isReady = true
                self.onReady?()
                self.beginReceiving()
            case .failed, .cancelled:
                self.teardown()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func beginReceiving() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let d = data, !d.isEmpty { self.handleIncoming(d) }
            if error != nil || isComplete || data == nil && !isComplete {
                self.teardown()
                return
            }
            self.beginReceiving()
        }
    }

    private func handleIncoming(_ data: Data) {
        buffer.append(data)
        while let idx = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer[buffer.startIndex..<idx]
            if !lineData.isEmpty, let line = String(data: lineData, encoding: .utf8) {
                onLine?(line)
            }
            buffer.removeSubrange(buffer.startIndex...idx)
        }
    }

    func send(_ line: String) {
        guard isReady else { return }
        connection.send(content: Data(line.utf8) + Data([0x0A]), completion: .contentProcessed { _ in })
    }

    func teardown() {
        connection.cancel()
        let stop = onStop
        onStop = nil
        stop?()
    }

    func close() { teardown() }
}

struct LanInterface {
    let ip: String
    let mask: String
}

final class Discoverer {
    static let shared = Discoverer()
    weak var session: MeshSession?
    private var multicast: NWConnectionGroup?
    private var udpListener: NWListener?
    private var probeTimer: Timer?

    func bind(session: MeshSession) { self.session = session }

    func start() {
        guard probeTimer == nil else { return }
        startMulticast()
        startUdpListener()
        let t = Timer(timeInterval: AppConfig.probeInterval, repeats: true) { [weak self] _ in
            self?.probe()
        }
        t.tolerance = 5
        RunLoop.main.add(t, forMode: .common)
        probeTimer = t
        probe()
    }

    private func startMulticast() {
        guard multicast == nil, let group = try? NWMulticastGroup(for: [.hostPort(host: NWEndpoint.Host(AppConfig.multicastGroup), port: AppConfig.udpPortEndpoint)]) else { return }
        let cg = NWConnectionGroup(with: group, using: .udp)
        multicast = cg
        cg.setReceiveHandler(maximumMessageSize: 4096) { [weak self] data, context, _ in
            guard let self = self, let d = data, let line = String(data: d, encoding: .utf8) else { return }
            let source = context?.metadata.remoteEndpoint.map { endpointHost($0) } ?? ""
            self.session?.handleBeacon(line, fromAddr: source)
        }
        cg.start(queue: .global(qos: .background))
    }

    private func startUdpListener() {
        guard udpListener == nil else { return }
        guard let l = try? NWListener(using: .udp, on: AppConfig.udpPortEndpoint) else { return }
        udpListener = l
        l.newConnectionHandler = { [weak self] conn in
            conn.start(queue: .global(qos: .utility))
            conn.receiveMessage { data, context, _, _ in
                if let d = data, let line = String(data: d, encoding: .utf8) {
                    let source = context?.metadata.remoteEndpoint.map { endpointHost($0) } ?? ""
                    self?.session?.handleBeacon(line, fromAddr: source)
                }
                conn.cancel()
            }
        }
        l.start(queue: .global(qos: .utility))
    }

    private func endpointHost(_ ep: NWEndpoint) -> String {
        switch ep {
        case .hostPort(let host, _): return "\(host)"
        case .ip(let address, _): return address.debugDescription
        default: return ""
        }
    }

    func probe() {
        guard let session = session else { return }
        let beacon = session.makeBeacon()
        multicast?.send(content: Data(beacon.utf8)) { _ in }
        guard let iface = localInterfaces().first else { return }
        let ipParts = iface.ip.split(separator: ".").compactMap { Int($0) }
        guard ipParts.count == 4 else { return }
        for host in 1...254 where host != ipParts[3] {
            let candidate = "\(ipParts[0]).\(ipParts[1]).\(ipParts[2]).\(host)"
            sendUnicast(beacon, to: candidate)
        }
    }

    private func sendUnicast(_ payload: String, to host: String) {
        let conn = NWConnection(host: NWEndpoint.Host(host), port: AppConfig.udpPortEndpoint, using: .udp)
        _ = conn
        conn.start(queue: .global(qos: .utility))
        conn.send(content: Data(payload.utf8), completion: .contentProcessed { _ in conn.cancel() })
    }
}

func localInterfaces() -> [LanInterface] {
    var result: [LanInterface] = []
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
    defer { freeifaddrs(first) }
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let p = ptr {
        ptr = p.pointee.ifa_next
        let name = String(cString: p.pointee.ifa_name)
        guard name.hasPrefix("en") || name.hasPrefix("pdp") else { continue }
        guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
        var mask = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if let ma = p.pointee.ifa_netmask {
            _ = getnameinfo(ma, socklen_t(ma.pointee.sa_len), &mask, socklen_t(mask.count), nil, 0, NI_NUMERICHOST)
        }
        let ip = String(cString: host)
        let mc = String(cString: mask)
        if isPrivateIPv4(ip), !mc.isEmpty {
            result.append(LanInterface(ip: ip, mask: mc))
        }
    }
    return result
}

func isPrivateIPv4(_ ip: String) -> Bool {
    ip.hasPrefix("192.168.") || ip.hasPrefix("10.") || ip.hasPrefix("172.")
}