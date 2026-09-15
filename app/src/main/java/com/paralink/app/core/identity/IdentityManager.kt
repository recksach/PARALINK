package com.paralink.app.core.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

class IdentityManager(private val context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "PARALINK_IDENTITY_RSA"
        private const val PREFS = "paralink_identity"
        private const val NODE_ID = "node_id"
    }

    fun getOrCreateNodeId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(NODE_ID, null)?.let { return it }
        ensureKeyPair()
        val id = "PLK-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        prefs.edit().putString(NODE_ID, id).apply()
        return id
    }

    private fun ensureKeyPair() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) return

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }
}
