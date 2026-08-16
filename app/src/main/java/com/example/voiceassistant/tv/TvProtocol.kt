package com.example.voiceassistant.tv

/**
 * Builders/parsers for the two protobuf schemas used by the Android TV Remote
 * protocol v2 - the same protocol the official Google TV app uses:
 *  - polo.proto (OuterMessage)      - one-time pairing handshake, port 6467
 *  - remotemessage.proto (RemoteMessage) - persistent command channel, port 6466
 *
 * Field numbers are taken directly from Google's polo.proto and the
 * community-reverse-engineered remotemessage.proto.
 */
object TvProtocol {

    // --- RemoteKeyCode (subset - remotemessage.proto RemoteKeyCode enum) ---
    object Key {
        const val HOME = 3
        const val BACK = 4
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val DPAD_CENTER = 23
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val POWER = 26
        const val MEDIA_PLAY_PAUSE = 85
        const val VOLUME_MUTE = 164
    }

    // RemoteDirection enum
    private const val DIRECTION_SHORT = 3

    // ---------------------------------------------------------------
    // polo.proto / OuterMessage  (pairing, port 6467)
    // ---------------------------------------------------------------

    private fun outerMessage(bodyFieldNumber: Int, body: ByteArray): ByteArray = ProtoWire.message { out ->
        ProtoWire.writeVarintField(out, 1, 2)   // protocol_version = 2
        ProtoWire.writeVarintField(out, 2, 200) // status = STATUS_OK
        ProtoWire.writeMessageField(out, bodyFieldNumber, body)
    }

    fun pairingRequest(clientName: String): ByteArray {
        val body = ProtoWire.message { out ->
            ProtoWire.writeStringField(out, 1, "atvremote") // service_name
            ProtoWire.writeStringField(out, 2, clientName)  // client_name
        }
        return outerMessage(10, body) // pairing_request
    }

    fun pairingOptions(): ByteArray {
        val encoding = ProtoWire.message { out ->
            ProtoWire.writeVarintField(out, 1, 3) // ENCODING_TYPE_HEXADECIMAL
            ProtoWire.writeVarintField(out, 2, 6) // symbol_length
        }
        val body = ProtoWire.message { out ->
            ProtoWire.writeMessageField(out, 1, encoding) // input_encodings
            ProtoWire.writeVarintField(out, 3, 1)          // preferred_role = ROLE_TYPE_INPUT
        }
        return outerMessage(20, body) // options
    }

    fun pairingConfiguration(): ByteArray {
        val encoding = ProtoWire.message { out ->
            ProtoWire.writeVarintField(out, 1, 3) // ENCODING_TYPE_HEXADECIMAL
            ProtoWire.writeVarintField(out, 2, 6) // symbol_length
        }
        val body = ProtoWire.message { out ->
            ProtoWire.writeMessageField(out, 1, encoding) // encoding
            ProtoWire.writeVarintField(out, 2, 1)          // client_role = ROLE_TYPE_INPUT
        }
        return outerMessage(30, body) // configuration
    }

    fun pairingSecret(secret: ByteArray): ByteArray {
        val body = ProtoWire.message { out -> ProtoWire.writeBytesField(out, 1, secret) }
        return outerMessage(40, body) // secret
    }

    /** Which phase of the pairing handshake the TV's response corresponds to. */
    enum class PairingPhase { REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, UNKNOWN, ERROR }

    fun parsePairingResponse(data: ByteArray): PairingPhase {
        val fields = ProtoWire.parse(data)
        val status = (fields[2] as? ProtoWire.Field.Varint)?.value ?: 200L
        if (status != 200L) return PairingPhase.ERROR
        return when {
            fields.containsKey(11) -> PairingPhase.REQUEST_ACK
            fields.containsKey(20) -> PairingPhase.OPTIONS
            fields.containsKey(31) -> PairingPhase.CONFIGURATION_ACK
            fields.containsKey(41) -> PairingPhase.SECRET_ACK
            else -> PairingPhase.UNKNOWN
        }
    }

    // ---------------------------------------------------------------
    // remotemessage.proto / RemoteMessage  (commands, port 6466)
    // ---------------------------------------------------------------

    fun keyInject(keyCode: Int, direction: Int = DIRECTION_SHORT): ByteArray {
        val body = ProtoWire.message { out ->
            ProtoWire.writeVarintField(out, 1, keyCode.toLong())
            ProtoWire.writeVarintField(out, 2, direction.toLong())
        }
        return ProtoWire.message { out -> ProtoWire.writeMessageField(out, 10, body) } // remote_key_inject
    }

    fun appLinkLaunch(appLinkOrId: String): ByteArray {
        val link = if (appLinkOrId.contains("://")) appLinkOrId else "market://launch?id=$appLinkOrId"
        val body = ProtoWire.message { out -> ProtoWire.writeStringField(out, 1, link) }
        return ProtoWire.message { out -> ProtoWire.writeMessageField(out, 90, body) } // remote_app_link_launch_request
    }

    fun pingResponse(val1: Long): ByteArray {
        val body = ProtoWire.message { out -> ProtoWire.writeVarintField(out, 1, val1) }
        return ProtoWire.message { out -> ProtoWire.writeMessageField(out, 9, body) } // remote_ping_response
    }

    fun configureAck(activeFeatures: Int): ByteArray {
        val deviceInfo = ProtoWire.message { out ->
            ProtoWire.writeVarintField(out, 3, 1)          // unknown1
            ProtoWire.writeStringField(out, 4, "1")         // unknown2
            ProtoWire.writeStringField(out, 5, "atvremote") // package_name
            ProtoWire.writeStringField(out, 6, "1.0.0")     // app_version
        }
        val body = ProtoWire.message { out ->
            ProtoWire.writeVarintField(out, 1, activeFeatures.toLong()) // code1
            ProtoWire.writeMessageField(out, 2, deviceInfo)              // device_info
        }
        return ProtoWire.message { out -> ProtoWire.writeMessageField(out, 1, body) } // remote_configure
    }

    fun setActiveAck(activeFeatures: Int): ByteArray {
        val body = ProtoWire.message { out -> ProtoWire.writeVarintField(out, 1, activeFeatures.toLong()) }
        return ProtoWire.message { out -> ProtoWire.writeMessageField(out, 2, body) } // remote_set_active
    }

    /** What kind of RemoteMessage was received from the TV. */
    sealed class RemoteEvent {
        data class Configure(val code1: Int) : RemoteEvent()
        object SetActive : RemoteEvent()
        data class PingRequest(val val1: Long) : RemoteEvent()
        data class Start(val started: Boolean) : RemoteEvent()
        object Other : RemoteEvent()
    }

    fun parseRemoteMessage(data: ByteArray): RemoteEvent {
        val fields = ProtoWire.parse(data)
        (fields[1] as? ProtoWire.Field.Bytes)?.let { // remote_configure
            val inner = ProtoWire.parse(it.value)
            val code1 = (inner[1] as? ProtoWire.Field.Varint)?.value?.toInt() ?: 0
            return RemoteEvent.Configure(code1)
        }
        if (fields[2] is ProtoWire.Field.Bytes) return RemoteEvent.SetActive // remote_set_active
        (fields[8] as? ProtoWire.Field.Bytes)?.let { // remote_ping_request
            val inner = ProtoWire.parse(it.value)
            val val1 = (inner[1] as? ProtoWire.Field.Varint)?.value ?: 0L
            return RemoteEvent.PingRequest(val1)
        }
        (fields[40] as? ProtoWire.Field.Bytes)?.let { // remote_start
            val inner = ProtoWire.parse(it.value)
            val started = ((inner[1] as? ProtoWire.Field.Varint)?.value ?: 0L) != 0L
            return RemoteEvent.Start(started)
        }
        return RemoteEvent.Other
    }
}
