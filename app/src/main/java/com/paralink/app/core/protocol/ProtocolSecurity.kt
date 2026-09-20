package com.paralink.app.core.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Standard HMAC-SHA256 based integrity for PARALINK link frames.
 * Uses only reviewed primitives (HmacSHA256), never a home-grown MAC.
 */
object ProtocolSecurity {

    private const val HMAC = "HmacSHA256"
    private const val TAG_BYTES = 16

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }

    /** 16-byte truncated tag used in each frame. */
    fun frameTag(key: ByteArray, headerAndPayload: ByteArray): ByteArray =
        mac(key, headerAndPayload).copyOf(TAG_BYTES)

    /** Constant-time tag comparison. */
    fun tagsEqual(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    /** Builds the full wire frame with its auth tag under [key]. */
    fun sealed(frame: ParalinkFrame, key: ByteArray): ByteArray {
        val head = FrameCodec.headerAndPayload(frame)
        return FrameCodec.build(frame, frameTag(key, head))
    }

    /**
     * Parses and authenticates a wire frame. Returns null when the structure
     * is invalid or the auth tag does not match [key] (zero-key ok pre-session).
     */
    fun unseal(bytes: ByteArray, key: ByteArray): ParalinkFrame? {
        val parsed = FrameCodec.parse(bytes) ?: return null
        val expected = frameTag(key, FrameCodec.headerAndPayload(parsed.first))
        if (!tagsEqual(expected, parsed.second)) return null
        return parsed.first
    }
}