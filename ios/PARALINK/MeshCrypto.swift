import Foundation
import CryptoKit

final class MeshCrypto {
    private let identity: IdentityStore
    init(identity: IdentityStore) { self.identity = identity }

    var myPublicKeyB64: String { identity.publicKeyB64 }

    func sessionKey(peerPublicKeyB64: String) throws -> SymmetricKey {
        guard let peerData = Data(base64Encoded: peerPublicKeyB64) else { throw NSError(domain: "MeshCrypto", code: 1) }
        let peer = try P256.KeyAgreement.PublicKey(derRepresentation: peerData)
        let shared = try identity.privateKey.sharedSecretFromKeyAgreement(with: peer)
        let rawSecret = shared.withUnsafeBytes { Data($0) }
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: rawSecret),
            salt: Data(AppConfig.salt.utf8),
            info: Data(AppConfig.info.utf8),
            outputByteCount: 32
        )
    }

    func encrypt(_ key: SymmetricKey, plain: Data) throws -> String {
        var ivData = Data(count: 12)
        _ = ivData.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let nonce = try AES.GCM.Nonce(data: ivData)
        let sealed = try AES.GCM.seal(plain, using: key, nonce: nonce)
        var out = Data(ivData)
        out.append(sealed.ciphertext)
        out.append(sealed.tag)
        return out.base64EncodedString()
    }

    func decrypt(_ key: SymmetricKey, payloadB64: String) throws -> Data {
        guard let data = Data(base64Encoded: payloadB64), data.count > 28 else {
            throw NSError(domain: "MeshCrypto", code: 2)
        }
        let iv = data.prefix(12)
        let ct = data.dropFirst(12).dropLast(16)
        let tag = data.suffix(16)
        let nonce = try AES.GCM.Nonce(data: Data(iv))
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: Data(ct), tag: Data(tag))
        return try AES.GCM.open(box, using: key)
    }
}