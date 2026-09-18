import Foundation
import CoreLocation
import Combine
import SwiftUI

final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let lm = CLLocationManager()
    var onUpdate: ((Double, Double, Double) -> Void)?
    private var lastLat = 0.0
    private var lastLon = 0.0

    override init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBest
        lm.distanceFilter = 10
    }

    func start() {
        lm.requestWhenInUseAuthorization()
        if CLLocationManager.locationServicesEnabled() { lm.startUpdatingLocation() }
        if CLLocationManager.headingAvailable() { lm.startUpdatingHeading() }
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let l = locs.last else { return }
        lastLat = l.coordinate.latitude
        lastLon = l.coordinate.longitude
        onUpdate?(lastLat, lastLon, m.heading?.trueHeading ?? 0)
    }

    func locationManager(_ m: CLLocationManager, didUpdateHeading h: CLHeading) {
        onUpdate?(lastLat, lastLon, h.trueHeading)
    }
}

final class AppModel: ObservableObject {
    let identity = IdentityStore.shared
    let session: MeshSession
    let messages = MessageStore()
    let ledger = LedgerStore()
    let shop = ShopStore()
    let voice = VoiceIO()

    @Published var tab = 0
    @Published var myName: String
    @Published var chatWith: String?
    @Published var radarNodes: [RadarNode] = []
    @Published var lastError: String?
    @Published var wallet: Double = 0
    @Published var heading: Double = 0
    @Published var myLat: Double = 0
    @Published var myLon: Double = 0
    @Published var connectedCount = 0
    @Published var playingVoiceId: String?

    private let loc = LocationProvider()
    private var earnTimer: Timer?
    private var firstRunShown = false

    init() {
        myName = identity.myName
        session = MeshSession(identity: identity)
        session.onEvent = { [weak self] ev in self?.handle(ev) }
        firstRunShown = !UserDefaults.standard.bool(forKey: "paralink.firstRunDone")
    }

    var showGuide: Bool { firstRunShown }
    func markGuided() {
        firstRunShown = false
        UserDefaults.standard.set(true, forKey: "paralink.firstRunDone")
    }

    func start() {
        session.start()
        Discoverer.shared.bind(session: session)
        loc.onUpdate = { [weak self] lat, lon, head in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.myLat = lat
                self.myLon = lon
                self.heading = head
                self.session.setMyLocation(lat: lat, lon: lon)
            }
        }
        loc.start()

        let t = Timer(timeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tick()
        }
        t.tolerance = 0.5
        RunLoop.main.add(t, forMode: .common)
        earnTimer = t
        tick()
    }

    private func tick() {
        refresh()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        ledger.setConnectedCount(connectedCount, now: now)
        wallet = ledger.balance(now: now)
    }

    func refresh() {
        radarNodes = session.radarNodes()
        connectedCount = session.knownIds().count
    }

    private func handle(_ ev: MeshSession.Ev) {
        switch ev.type {
        case .peers:
            refresh()
        case .connected:
            connectedCount = session.knownIds().count
            refresh()
        case .disconnected:
            refresh()
        case .error:
            lastError = ev.text
        case .message:
            let id = UUID().uuidString
            let text = ev.text ?? ""
            guard !text.isEmpty else { return }
            let msg = ChatMessage(id: id, senderId: ev.nodeId ?? "?", senderName: ev.nodeName ?? ev.nodeId ?? "?", text: text, timestamp: Int64(Date().timeIntervalSince1970 * 1000), incoming: true, originalText: text, translatedText: nil, detectedLanguage: nil, translationStatus: .none, peerId: nil)
            messages.add(msg)
            messages.update(id) { m in
                m.translationStatus = .unavailable
                m.translatedText = Lang.text("translation_unavailable")
            }
        case .voice:
            let wav = ev.wavB64 ?? ""
            guard !wav.isEmpty else { return }
            messages.addVoice(VoiceMessage(id: UUID().uuidString, senderId: ev.nodeId ?? "?", senderName: ev.nodeName ?? ev.nodeId ?? "?", wavBase64: wav, durationMs: ev.durationMs, timestamp: Int64(Date().timeIntervalSince1970 * 1000), incoming: true, peerId: nil))
        case .token:
            if ev.amount > 0 {
                ledger.credit(ev.amount, from: ev.nodeId ?? "?", note: ev.note ?? "", now: Int64(Date().timeIntervalSince1970 * 1000))
                wallet = ledger.balance(now: Int64(Date().timeIntervalSince1970 * 1000))
            }
        }
    }

    // MESSAGE: outgoing
    func sendText(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let id = UUID().uuidString
        messages.add(ChatMessage(id: id, senderId: identity.nodeId, senderName: myName, text: text, timestamp: Int64(Date().timeIntervalSince1970 * 1000), incoming: false, originalText: text, translatedText: nil, detectedLanguage: nil, translationStatus: .none, peerId: chatWith))
        if let peer = chatWith { session.sendTextTo(peer, text: text) } else { session.sendText(text: text) }
    }

    func sendVoiceTo(_ peerId: String?, _ result: (wavB64: String, durationMs: Int64)) {
        let (wav, dur) = result
        guard !wav.isEmpty, dur >= 300 else { return }
        messages.addVoice(VoiceMessage(id: UUID().uuidString, senderId: identity.nodeId, senderName: myName, wavBase64: wav, durationMs: dur, timestamp: Int64(Date().timeIntervalSince1970 * 1000), incoming: false, peerId: peerId))
        if let peer = peerId { session.sendVoiceTo(peer, wav: wav, duration: dur) } else { session.sendVoice(wav: wav, duration: dur) }
    }

    func transfer(to peer: String, amount: Double, note: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if ledger.debit(amount, to: peer, note: note, now: now) {
            session.sendToken(peer, amount: amount, note: note)
            wallet = ledger.balance(now: now)
        }
    }

    func buy(_ item: ShopItem) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if shop.buy(item, ledger: ledger, now: now) {
            wallet = ledger.balance(now: now)
        }
    }

    func rename(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        identity.setMyName(clean)
        myName = clean
        session.setDisplayName(clean)
    }

    func play(_ v: VoiceMessage) {
        if playingVoiceId == v.id {
            voice.stopPlayback()
            playingVoiceId = nil
            return
        }
        voice.stopPlayback()
        voice.play(wavBase64: v.wavBase64) { [weak self] in
            DispatchQueue.main.async { self?.playingVoiceId = nil }
        }
        playingVoiceId = v.id
    }

    func chatName(for id: String) -> String {
        radarNodes.first(where: { $0.id == id })?.name ?? session.knownPeers().first(where: { $0.id == id })?.name ?? id
    }
}