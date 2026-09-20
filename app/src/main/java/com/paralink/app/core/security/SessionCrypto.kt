package com.paralink.app.core.security

import com.paralink.app.core.crypto.Hkdf
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

/**
 * Per-link transport security for PARALINK.
 *
 * Uses a standard authenticated triple-ECDH (P-256):
 *
 *   Dh1 = ECDH(ourStatic , peerEphemeral)
 *   Dh2 = ECDH(ourEphemeral, peerStatic)
 *   Dh3 = ECDH(ourEphemeral, peerEphemeral)
 *   master = Dh1 || Dh2 || Dh3
 *   linkKey = HKDF(master, salt = nonceA||nonceB, info = "PARALINK_LINK_V1")
 *
 * The static P-256 keypair lives in the Android Keystore (see MeshCrypto) and
 * authenticates us; ephemeral keys give forward secrecy for the transport.
 * The derived link key MACs every frame (AUTH_TAG) and authenticates the peer
 * during the handshake (AUTH frames).
 */
class SessionCrypto {

    companion object {
        private const val EC = "EC"
        private const val CURVE = "secp256r1"
        private const val LINK_INFO = "PARALINK_LINK_V1"
        private const val SAS_INFO = "PARALINK_SAS_V1"

        private fun pubKeyFromB64(b64: String): PublicKey =
            KeyFactory.getInstance(EC).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

        private fun ecdh(priv: PrivateKey, peerPub: PublicKey): ByteArray {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(priv)
            ka.doPhase(peerPub, true)
            return ka.generateSecret()
        }
    }

    data class EphemeralKeyPair(val privateKey: PrivateKey, val publicKeyB64: String) {
        val publicKey: PublicKey
            get() = pubKeyFromB64(publicKeyB64)
    }

    /** Fresh ephemeral P-256 key pair (in memory, never persisted). */
    fun newEphemeral(): EphemeralKeyPair {
        val generator = KeyPairGenerator.getInstance(EC)
        generator.initialize(java.security.spec.ECGenParameterSpec(CURVE))
        val kp: KeyPair = generator.generateKeyPair()
        return EphemeralKeyPair(kp.private, Base64.getEncoder().encodeToString(kp.public.encoded))
    }

    /**
     * Triple-ECDH session key derivation.
     *
     * @param ourStaticPrivate our Keystore-bound static private key
     * @param ourEphemeral     our freshly generated ephemeral keys
     * @param peerStaticB64    peer static public key (base64, X.509)
     * @param peerEphemeralB64 peer ephemeral public key (base64, X.509)
     * @param nonceA           initiator nonce (16 bytes)
     * @param nonceB           responder nonce (16 bytes)
     */
    fun linkKey(
        ourStaticPrivate: PrivateKey,
        ourEphemeral: EphemeralKeyPair,
        peerStaticB64: String,
        peerEphemeralB64: String,
        nonceA: ByteArray,
        nonceB: ByteArray
    ): ByteArray {
        val peerStatic = runCatching { pubKeyFromB64(peerStaticB64) }.getOrThrow()
        val peerEph = runCatching { pubKeyFromB64(peerEphemeralB64) }.getOrThrow()

        val dh1 = ecdh(ourStaticPrivate, peerEph)
        val dh2 = ecdh(ourEphemeral.privateKey, peerStatic)
        val dh3 = ecdh(ourEphemeral.privateKey, peerEph)

        // The three secrets are symmetric, but their *concatenation order* is
        // not: peer A computes [SA·EB, EA·SB, EA·EB] while B computes
        // [SB·EA, EB·SA, EB·EA]. Sort canonically so both ends derive the
        // exact same master (otherwise every handshake would fail).
        val master = listOf(dh1, dh2, dh3)
            .sortedWith { a, b -> compareBytes(a, b) }
            .fold(ByteArray(0)) { acc, s -> acc + s }
        val salt = nonceA + nonceB
        return Hkdf.derive(master, salt, LINK_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    /**
     * Short authentication string shown to both users to defeat MITM.
     * Derived from the link key and both nonces, then reduced to 6 digits.
     * Format example: "482 913".
     */
    fun sas(linkKey: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String {
        val material = linkKey + nonceA + nonceB
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(material)
        val first6 = ((d[0].toLong() and 0xFF) shl 16) or ((d[1].toLong() and 0xFF) shl 8) or (d[2].toLong() and 0xFF)
        // 20 bits -> 0..999999 for two strict 3-digit groups
        val v = (first6 and 0xFFFFFL) % 1000000
        return "%03d %03d".format(v / 1000, v % 1000)
    }

    /** HMAC proof that we know [linkKey] for this session. */
    fun authProof(linkKey: ByteArray, sessionId: Int, myNodeId: String, peerNodeId: String): ByteArray {
        val info = "PARALINK_LINK_AUTH|$sessionId|$myNodeId|$peerNodeId".toByteArray(Charsets.UTF_8)
        return com.paralink.app.core.protocol.ProtocolSecurity.mac(linkKey, info)
    }
}