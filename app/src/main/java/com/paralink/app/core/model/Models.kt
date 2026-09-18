package com.paralink.app.core.model

import java.io.Serializable

const val STATUS_NONE = "NONE"
const val STATUS_PENDING = "PENDING"
const val STATUS_TRANSLATED = "TRANSLATED"
const val STATUS_UNAVAILABLE = "UNAVAILABLE"

data class Peer(
    val nodeId: String,
    val displayName: String,
    val deviceAddress: String = "",
    val signalLevel: Int = 0,
    val transport: String = "Wi-Fi Direct",
    val connected: Boolean = false,
    val address: String? = null,
    val language: String? = null
) : Serializable

data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val incoming: Boolean,
    val originalText: String = text,
    val translatedText: String? = null,
    val detectedLanguage: String? = null,
    val translationStatus: String = STATUS_NONE,
    val peerId: String? = null
) : Serializable

data class VoiceMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val wavBase64: String,
    val durationMs: Long,
    val timestamp: Long,
    val incoming: Boolean,
    val transcript: String? = null,
    val translatedText: String? = null,
    val peerId: String? = null
) : Serializable

data class Route(
    val destination: String,
    val hops: List<String>,
    val cost: Double
) : Serializable