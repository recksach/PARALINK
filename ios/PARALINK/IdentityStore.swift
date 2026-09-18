import Foundation
import CryptoKit
import Security

final class IdentityStore {
    static let shared = IdentityStore()
    private let ud = UserDefaults.standard
    let nodeId: String
    var myName: String
    let privateKey: P256.KeyAgreement.PrivateKey

    private init() {
        let existingId = ud.string(forKey: "paralink.nodeId")
        let nid = existingId ?? {
            let n = "NODE-" + UUID().uuidString.prefix(8).uppercased()
            ud.set(n, forKey: "paralink.nodeId")
            return n
        }()
        self.nodeId = nid
        self.myName = ud.string(forKey: "paralink.myName") ?? nid

        if let data = IdentityStore.loadKey(), let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: data) {
            privateKey = k
        } else {
            let k = P256.KeyAgreement.PrivateKey()
            IdentityStore.saveKey(k.rawRepresentation)
            privateKey = k
        }
    }

    func setMyName(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        myName = clean
        ud.set(clean, forKey: "paralink.myName")
    }

    var publicKeyB64: String { privateKey.publicKey.derRepresentation.base64EncodedString() }

    private static func saveKey(_ data: Data) {
        let tag = "com.paralink.ios.meshkey".data(using: .utf8)!
        let add: [CFString: Any] = [kSecClass: kSecClassKey, kSecAttrApplicationTag: tag, kSecValueData: data]
        SecItemDelete(add as CFDictionary)
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func loadKey() -> Data? {
        let tag = "com.paralink.ios.meshkey".data(using: .utf8)!
        let q: [CFString: Any] = [kSecClass: kSecClassKey, kSecAttrApplicationTag: tag, kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let d = item as? Data else { return nil }
        return d
    }
}