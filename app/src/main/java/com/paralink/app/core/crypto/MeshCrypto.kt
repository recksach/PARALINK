package com.paralink.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MeshCrypto(context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "PARALINK_MESH_ECDH"
        private const val SALT = "PARALINK_E2E_V1"
        private const val INFO = "PARALINK_SESSION_KEY_V1"

        private fun keySpec(bytes: ByteArray): SecretKeySpec = SecretKeySpec(bytes, "AES")
    }

    init {
        ensureKeyPair()
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_AGREE_KEY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    fun myPublicKeyB64(): String {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val b64 = Base64.getEncoder().encodeToString(entry.certificate.publicKey.encoded)
        return b64
    }

    /** Static P-256 private key (Keystore) used for link-level handshake auth. */
    fun myStaticPrivateKey(): PrivateKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
    }

    fun sessionKey(peerPublicKeyB64: String): SecretKeySpec {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = (ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey
        val peerBytes = Base64.getDecoder().decode(peerPublicKeyB64)
        val peerKey: PublicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerBytes))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerKey, true)
        val shared = agreement.generateSecret()

        val key = Hkdf.derive(shared, SALT.toByteArray(Charsets.UTF_8), INFO.toByteArray(Charsets.UTF_8), 32)
        return keySpec(key)
    }

    fun encrypt(key: SecretKeySpec, plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(key: SecretKeySpec, payloadB64: String): ByteArray {
        val data = Base64.getDecoder().decode(payloadB64)
        if (data.size < 13) throw IllegalArgumentException("payload too short")
        val iv = data.copyOfRange(0, 12)
        val body = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}