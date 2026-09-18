import Foundation

final class MessageStore {
    private let chatUrl: URL
    private let voiceUrl: URL
    private(set) var messages: [ChatMessage] = []
    private(set) var voices: [VoiceMessage] = []

    init() {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        chatUrl = dir.appendingPathComponent("chat.json")
        voiceUrl = dir.appendingPathComponent("voices.json")
        if let data = try? Data(contentsOf: chatUrl), let m = try? JSONDecoder().decode([ChatMessage].self, from: data) { messages = m }
        if let data = try? Data(contentsOf: voiceUrl), let v = try? JSONDecoder().decode([VoiceMessage].self, from: data) { voices = v }
    }

    func add(_ m: ChatMessage) {
        messages.append(m)
        persist()
    }

    func addVoice(_ v: VoiceMessage) {
        voices.append(v)
        persist()
    }

    func update(_ id: String, apply: (inout ChatMessage) -> Void) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        apply(&messages[idx])
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(messages) { try? data.write(to: chatUrl) }
        if let data = try? JSONEncoder().encode(voices) { try? data.write(to: voiceUrl) }
    }
}

final class LedgerStore {
    private let url: URL
    private(set) var txs: [TxEntry] = []
    private var peerCount = 0
    private var connectedSince: Int64?

    init() {
        url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("ledger.json")
        if let data = try? Data(contentsOf: url), let t = try? JSONDecoder().decode([TxEntry].self, from: data) { txs = t }
    }

    func setConnectedCount(_ n: Int, now: Int64) {
        if n > 0 && peerCount == 0 && connectedSince == nil { connectedSince = now }
        if n == 0 && peerCount > 0 { connectedSince = nil }
        peerCount = n
    }

    func balance(now: Int64) -> Double {
        var b = txs.reduce(0.0) { $0 + $1.amount }
        if let since = connectedSince {
            let mins = Double(now - since) / 60_000.0
            b += mins * AppConfig.earnPerLinkPerMin * Double(peerCount)
        }
        return b
    }

    @discardableResult
    func debit(_ amount: Double, to peer: String, note: String, now: Int64) -> Bool {
        guard amount > 0, balance(now: now) >= amount else { return false }
        txs.append(TxEntry(id: UUID().uuidString, ts: now, kind: "debit", amount: -amount, peerId: peer, note: note))
        persist()
        return true
    }

    func credit(_ amount: Double, from peer: String, note: String, now: Int64) {
        guard amount > 0 else { return }
        txs.append(TxEntry(id: UUID().uuidString, ts: now, kind: "credit", amount: amount, peerId: peer, note: note))
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(txs) { try? data.write(to: url) }
    }
}

final class ShopStore {
    private let url: URL
    private var ownedIds = Set<String>()
    let items: [ShopItem] = [
        ShopItem(id: "star", title: "★ Name badge", price: 50, icon: "star"),
        ShopItem(id: "gold", title: "Gold radar blip", price: 150, icon: "circle"),
        ShopItem(id: "boost", title: "2x beacon rate", price: 60, icon: "bolt"),
        ShopItem(id: "scan", title: "Long-range radar", price: 90, icon: "scope")
    ]

    init() {
        url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("shop.json")
        if let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(Set<String>.self, from: data) { ownedIds = s }
    }

    func owns(_ id: String) -> Bool { ownedIds.contains(id) }

    @discardableResult
    func buy(_ item: ShopItem, ledger: LedgerStore, now: Int64) -> Bool {
        guard !ownedIds.contains(item.id), ledger.debit(item.price, to: "store", note: item.id, now: now) else { return false }
        ownedIds.insert(item.id)
        if let data = try? JSONEncoder().encode(ownedIds) { try? data.write(to: url) }
        return true
    }
}