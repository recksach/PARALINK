package com.paralink.app.core.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.paralink.app.core.language.WavCodec
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

object WavPlayer {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Synchronized
    fun play(wavB64: String, onDone: () -> Unit = {}) {
        stop()
        val wav = runCatching { Base64.getDecoder().decode(wavB64) }.getOrNull() ?: return
        val info = WavCodec.parseWav(wav)
        if (info.pcmLength <= 0) return
        val pcm = WavCodec.extractPcm(wav)
        val channel = if (info.channels > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (info.bitsPerSample > 16) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val t = AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(AudioAttributesUsage.COMMUNICATION)
                .setContentType(AudioAttributesContentType.SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(info.sampleRate)
                .setChannelMask(channel)
                .setEncoding(encoding)
                .build())
            .setBufferSizeInBytes(maxOf(pcm.size, 8192))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        thread = Thread {
            try {
                while (t.playState == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(50)
            } catch (e: InterruptedException) { }
            runCatching { t.release() }
            onDone()
        }.apply { start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        thread = null
    }

    private val AudioAttributesUsage = android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
    private val AudioAttributesContentType = android.media.AudioAttributes.CONTENT_TYPE_SPEECH
}

class PttRecorder {

    private val recording = AtomicBoolean(false)
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()
    private var startedAt = 0L

    fun start() {
        if (recording.getAndSet(true)) return
        buffer.reset()
        startedAt = System.currentTimeMillis()
        thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                WavCodec.DEFAULT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    WavCodec.DEFAULT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 4, 8192)
                )
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                recording.set(false)
                return@Thread
            }
            runCatching { record.startRecording() }
            val chunk = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (recording.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) out.write(chunk, 0, read)
            }
            runCatching { record.stop() }
            runCatching { record.release() }
            val pcm = out.toByteArray()
            synchronized(this) {
                buffer.reset()
                buffer.write(WavCodec.encodePcmToWav(pcm))
            }
        }.apply { start() }
    }

    fun stop(): Pair<String, Long> {
        if (!recording.getAndSet(false)) {
            return "" to 0L
        }
        thread?.join(2000)
        val wav = synchronized(this) { buffer.toByteArray() }
        val duration = System.currentTimeMillis() - startedAt
        return if (wav.isNotEmpty()) Base64.getEncoder().encodeToString(wav) to duration else "" to 0L
    }
}