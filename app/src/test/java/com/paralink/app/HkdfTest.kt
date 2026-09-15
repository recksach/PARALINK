package com.paralink.app

import com.paralink.app.core.crypto.Hkdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HkdfTest {

    private fun fromHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun rfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(expected, okm.toHex())
    }

    @Test
    fun expandTruncatesToLength() {
        val prk = ByteArray(32) { 3 }
        val out = Hkdf.expand(prk, ByteArray(5), 7)
        assertEquals(7, out.size)
    }

    @Test
    fun extractDefaultZeroSalt() {
        val ikm = ByteArray(16) { 1 }
        assertEquals(32, Hkdf.extract(ikm).size)
        assertEquals(32, Hkdf.extract(ikm, ByteArray(32)).size)
    }

    @Test
    fun sha256OutputSize() {
        val okm = Hkdf.derive("input key material".toByteArray(), null, null, 32)
        assertEquals(32, okm.size)
        assertTrue(okm.any { it.toInt() != 0 })
    }
}