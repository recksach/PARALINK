import Foundation

enum TranslationStatus: Int, Codable { case none = 0, pending = 1, translated = 2, unavailable = 3 }

struct ChatMessage: Identifiable, Codable {
    let id: String
    let senderId: String
    let senderName: String
    let text: String
    let timestamp: Int64
    let incoming: Bool
    var originalText: String
    var translatedText: String?
    var detectedLanguage: String?
    var translationStatus: TranslationStatus
    var peerId: String?
}

struct VoiceMessage: Identifiable, Codable {
    let id: String
    let senderId: String
    let senderName: String
    let wavBase64: String
    let durationMs: Int64
    let timestamp: Int64
    let incoming: Bool
    var peerId: String?
}

struct RadarNode: Identifiable, Equatable {
    let id: String
    var name: String
    var lastSeen: Int64
    var lat: Double
    var lon: Double
    var gold: Bool
    var badge: String?
    static func == (lhs: RadarNode, rhs: RadarNode) -> Bool { lhs.id == rhs.id && lhs.name == rhs.name && lhs.lat == rhs.lat }
}

struct TxEntry: Identifiable, Codable {
    let id: String
    let ts: Int64
    let kind: String
    let amount: Double
    let peerId: String
    let note: String
}

struct ShopItem: Identifiable {
    let id: String
    let title: String
    let price: Double
    let icon: String
}