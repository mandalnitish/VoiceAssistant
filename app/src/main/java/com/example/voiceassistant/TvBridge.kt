package com.example.voiceassistant

import android.content.Context
import com.example.voiceassistant.tv.TvProtocol
import com.example.voiceassistant.tv.TvRemoteClient

/**
 * Talks directly to the TV over the local network using a native Kotlin
 * implementation of the Android TV Remote protocol v2 (see the .tv package) -
 * the same protocol the official Google TV app uses. No PC, no tv-bridge
 * server, no ADB, no developer mode. Requires one-time pairing (see
 * startPairing / submitPairingCode below), after which it stays connected
 * and reconnects automatically.
 */
object TvBridge {

    private lateinit var client: TvRemoteClient

    /** Common app package IDs / deep links for voice launch commands. */
    val KNOWN_APPS = mapOf(
        "netflix" to "com.netflix.ninja",
        "youtube" to "com.google.android.youtube.tv",
        "prime video" to "com.amazon.amazonvideo.livingroom",
        "amazon prime" to "com.amazon.amazonvideo.livingroom",
        "hotstar" to "in.startv.hotstar",
        "disney hotstar" to "in.startv.hotstar",
        "spotify" to "com.spotify.tv.android"
    )

    /** Call once, e.g. from MainActivity.onCreate, before using anything else here. */
    fun init(context: Context) {
        if (::client.isInitialized) return
        client = TvRemoteClient(context.applicationContext)
        if (client.isPaired) client.connect {}
    }

    // --- One-time setup ------------------------------------------------------

    val isPaired: Boolean get() = ::client.isInitialized && client.isPaired
    val isConnected: Boolean get() = ::client.isInitialized && client.isConnected

    /** tvHost: the TV's local IP, e.g. "192.168.1.10" (TV: Settings > Network & Internet). */
    fun startPairing(tvHost: String, onCodeShown: () -> Unit, onError: (String) -> Unit) =
        client.startPairing(tvHost, onCodeShown, onError)

    /** code: the 6-digit hex code the TV displays on screen after startPairing. */
    fun submitPairingCode(code: String, onResult: (Boolean, String) -> Unit) =
        client.submitPairingCode(code, onResult)

    // --- Everyday commands (same signatures MainActivity already calls) ------

    fun power(onResult: (Boolean, String) -> Unit) = key(TvProtocol.Key.POWER, onResult)

    /** action: up, down, or mute */
    fun volume(action: String, onResult: (Boolean, String) -> Unit) {
        val code = when (action) {
            "up" -> TvProtocol.Key.VOLUME_UP
            "down" -> TvProtocol.Key.VOLUME_DOWN
            "mute" -> TvProtocol.Key.VOLUME_MUTE
            else -> return onResult(false, "action must be up, down, or mute")
        }
        key(code, onResult)
    }

    /** direction: up, down, left, right, select, home, back, play, pause */
    fun nav(direction: String, onResult: (Boolean, String) -> Unit) {
        val code = when (direction) {
            "up" -> TvProtocol.Key.DPAD_UP
            "down" -> TvProtocol.Key.DPAD_DOWN
            "left" -> TvProtocol.Key.DPAD_LEFT
            "right" -> TvProtocol.Key.DPAD_RIGHT
            "select" -> TvProtocol.Key.DPAD_CENTER
            "home" -> TvProtocol.Key.HOME
            "back" -> TvProtocol.Key.BACK
            "play", "pause" -> TvProtocol.Key.MEDIA_PLAY_PAUSE
            else -> return onResult(false, "unknown direction '$direction'")
        }
        key(code, onResult)
    }

    /** appId: an Android package name, e.g. "com.netflix.ninja" */
    fun launchApp(appId: String, onResult: (Boolean, String) -> Unit) {
        if (!::client.isInitialized) return onResult(false, "TvBridge not initialized")
        client.sendAppLink(appId, onResult)
    }

    private fun key(code: Int, onResult: (Boolean, String) -> Unit) {
        if (!::client.isInitialized) return onResult(false, "TvBridge not initialized")
        client.sendKey(code, onResult)
    }
}
