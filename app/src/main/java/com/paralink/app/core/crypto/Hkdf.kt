package com.paralink.app.core.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hkdf {

    private const val HASH_ALGO = "HmacSHA256"

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray?, length: Int): ByteArray {
        val prk = extract(ikm, salt ?: ByteArray(32))
        return expand(prk, info ?: ByteArray(0), length)
    }

    fun extract(ikm: ByteArray, salt: ByteArray = ByteArray(32)): ByteArray {
        val mac = Mac.getInstance(HASH_ALGO)
        mac.init(SecretKeySpec(salt, HASH_ALGO))
        return mac.doFinal(ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray = ByteArray(0), length: Int): ByteArray {
        val output = ByteArrayOutputStream(length)
        var t = ByteArray(0)
        var counter = 1
        val mac = Mac.getInstance(HASH_ALGO)
        mac.init(SecretKeySpec(prk, HASH_ALGO))
        while (output.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            output.write(t)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }
}