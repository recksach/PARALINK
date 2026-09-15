package com.paralink.app.core.language

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmLength: Int,
    val durationMillis: Long
)

object WavCodec {

    const val DEFAULT_SAMPLE_RATE = 16000

    fun encodePcmToWav(pcm: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize

        d.writeBytes("RIFF")
        d.writeInt(Integer.reverseBytes(totalSize))
        d.writeBytes("WAVE")
        d.writeBytes("fmt ")
        d.writeInt(Integer.reverseBytes(16))
        d.writeShort(Integer.reverseBytes(1))
        d.writeShort(Integer.reverseBytes(channels))
        d.writeInt(Integer.reverseBytes(sampleRate))
        d.writeInt(Integer.reverseBytes(byteRate))
        d.writeShort(Integer.reverseBytes(blockAlign))
        d.writeShort(Integer.reverseBytes(bitsPerSample))
        d.writeBytes("data")
        d.writeInt(Integer.reverseBytes(dataSize))
        d.write(pcm)
        d.flush()
        return out.toByteArray()
    }

    fun parseWav(data: ByteArray): WavInfo {
        val inStream = DataInputStream(ByteArrayInputStream(data))
        val magic = ByteArray(4)
        inStream.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "RIFF") {
            return WavInfo(DEFAULT_SAMPLE_RATE, 1, 16, 0, 0)
        }
        inStream.readInt()
        val wave = ByteArray(4)
        inStream.readFully(wave)
        while (true) {
            val chunk = ByteArray(4)
            inStream.readFully(chunk)
            val size = readLeInt(inStream)
            val name = String(chunk, Charsets.US_ASCII)
            if (name == "fmt ") {
                val format = readLeShort(inStream).toInt()
                var channels = readLeShort(inStream).toInt()
                val rate = readLeInt(inStream)
                inStream.readInt()
                inStream.readShort()
                var bits = readLeShort(inStream).toInt()
                if (size > 16) inStream.skip((size - 16).toLong())
                if (format != 1) {
                    channels = 1
                    bits = 16
                }
                return readData(inStream, WavInfo(rate, channels, bits, 0, 0))
            }
            inStream.skip(size.toLong())
        }
    }

    private fun readData(inStream: DataInputStream, info: WavInfo): WavInfo {
        val chunk = ByteArray(4)
        inStream.readFully(chunk)
        val size = readLeInt(inStream)
        if (String(chunk, Charsets.US_ASCII) == "data") {
            return WavInfo(info.sampleRate, info.channels, info.bitsPerSample, size, durationMillis(info.sampleRate, size, info.channels, info.bitsPerSample))
        }
        inStream.skip(size.toLong())
        return readData(inStream, info)
    }

    private fun readLeInt(inStream: DataInputStream): Int = Integer.reverseBytes(inStream.readInt())

    private fun readLeShort(inStream: DataInputStream): Short = Integer.reverseBytes(inStream.readShort().toInt()).toShort()

    fun extractPcm(data: ByteArray): ByteArray {
        val info = parseWav(data)
        val pcmStart = findDataOffset(data)
        return data.copyOfRange(pcmStart, pcmStart + info.pcmLength)
    }

    private fun findDataOffset(data: ByteArray): Int {
        var offset = 12
        while (offset + 8 <= data.size) {
            val name = String(data, offset, 4, Charsets.US_ASCII)
            val chunkSize = intFromLe(data, offset + 4)
            if (name == "data") return offset + 8
            offset += 8 + chunkSize
        }
        return offset
    }

    private fun intFromLe(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun durationMillis(sampleRate: Int, pcmLength: Int, channels: Int, bitsPerSample: Int): Long {
        if (sampleRate <= 0) return 0
        return pcmLength * 1000L / (sampleRate * channels * bitsPerSample / 8)
    }
}