package com.paralink.app.core.storage

import android.content.Context
import com.paralink.app.core.model.ChatMessage
import com.paralink.app.core.model.VoiceMessage
import com.paralink.app.core.model.STATUS_NONE
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class LocalMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("paralink_messages", Context.MODE_PRIVATE)
    private val messages = CopyOnWriteArrayList<ChatMessage>()
    private val voices = CopyOnWriteArrayList<VoiceMessage>()

    init { load() }

    fun all(): List<ChatMessage> = messages.sortedBy { it.timestamp }

    fun allVoices(): List<VoiceMessage> = voices.sortedBy { it.timestamp }

    @Synchronized
    fun add(message: ChatMessage) {
        if (messages.none { it.id == message.id }) {
            messages.add(message)
            persist()
        }
    }

    @Synchronized
    fun update(message: ChatMessage) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            messages[index] = message
            persist()
        }
    }

    @Synchronized
    fun addVoice(voice: VoiceMessage) {
        if (voices.none { it.id == voice.id }) {
            voices.add(voice)
            persist()
        }
    }

    @Synchronized
    fun updateVoice(voice: VoiceMessage) {
        val index = voices.indexOfFirst { it.id == voice.id }
        if (index >= 0) {
            voices[index] = voice
            persist()
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
        voices.clear()
        persist()
    }

    private fun load() {
        val raw = prefs.getString("data", "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                messages.add(ChatMessage(
                    id = o.getString("id"),
                    senderId = o.getString("senderId"),
                    senderName = o.getString("senderName"),
                    text = o.getString("text"),
                    timestamp = o.getLong("timestamp"),
                    incoming = o.getBoolean("incoming"),
                    originalText = o.optString("originalText", o.getString("text")),
                    translatedText = o.optString("translatedText", "").takeIf { it.isNotEmpty() },
                    detectedLanguage = o.optString("detectedLanguage", "").takeIf { it.isNotEmpty() },
                    translationStatus = o.optString("translationStatus", STATUS_NONE)
                ))
            }
        }
        val rawV = prefs.getString("voice", "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(rawV)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                voices.add(VoiceMessage(
                    id = o.getString("id"),
                    senderId = o.getString("senderId"),
                    senderName = o.getString("senderName"),
                    wavBase64 = o.getString("wavBase64"),
                    durationMs = o.optLong("durationMs"),
                    timestamp = o.getLong("timestamp"),
                    incoming = o.getBoolean("incoming"),
                    transcript = o.optString("transcript", "").takeIf { it.isNotEmpty() },
                    translatedText = o.optString("translatedText", "").takeIf { it.isNotEmpty() }
                ))
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("senderId", it.senderId); put("senderName", it.senderName)
                put("text", it.text); put("originalText", it.originalText)
                put("timestamp", it.timestamp); put("incoming", it.incoming)
                put("translatedText", it.translatedText ?: ""); put("detectedLanguage", it.detectedLanguage ?: "")
                put("translationStatus", it.translationStatus)
            })
        }
        prefs.edit().putString("data", arr.toString()).apply()

        val v = JSONArray()
        voices.forEach {
            v.put(JSONObject().apply {
                put("id", it.id); put("senderId", it.senderId); put("senderName", it.senderName)
                put("wavBase64", it.wavBase64); put("durationMs", it.durationMs)
                put("timestamp", it.timestamp); put("incoming", it.incoming)
                put("transcript", it.transcript ?: ""); put("translatedText", it.translatedText ?: "")
            })
        }
        prefs.edit().putString("voice", v.toString()).apply()
    }
}