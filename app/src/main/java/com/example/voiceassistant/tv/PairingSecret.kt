package com.example.voiceassistant.tv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

/**
 * Reproduces the pairing-code check from Google's TV pairing protocol:
 *
 *   SHA256(clientModulus || clientExponent || serverModulus || serverExponent || codeTail)
 *
 * ...and compares it against the checksum byte encoded in the first byte of
 * the 6-hex-digit code the TV displays. This is the same algorithm used by
 * every reference implementation of this reverse-engineered protocol.
 */
object PairingSecret {

    private fun modulusBytes(n: BigInteger): ByteArray {
        var hex = n.toString(16).uppercase()
        if (hex.length % 2 != 0) hex = "0$hex"
        return hexToBytes(hex)
    }

    /** The exponent is always hex-encoded with one extra leading zero nibble. */
    private fun exponentBytes(e: BigInteger): ByteArray {
        var hex = "0" + e.toString(16).uppercase()
        if (hex.length % 2 != 0) hex = "0$hex"
        return hexToBytes(hex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /**
     * @return the 32-byte SHA-256 digest to send as the pairing secret, or null if the
     * 6-hex-digit [pairingCode] shown on the TV doesn't check out against these certs
     * (wrong code, typo, or wrong TV).
     */
    fun computeSecret(clientCert: X509Certificate, serverCert: X509Certificate, pairingCode: String): ByteArray? {
        if (pairingCode.length != 6 || pairingCode.any { Character.digit(it, 16) == -1 }) return null
        val clientKey = clientCert.publicKey as RSAPublicKey
        val serverKey = serverCert.publicKey as RSAPublicKey

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(modulusBytes(clientKey.modulus))
        digest.update(exponentBytes(clientKey.publicExponent))
        digest.update(modulusBytes(serverKey.modulus))
        digest.update(exponentBytes(serverKey.publicExponent))
        digest.update(hexToBytes(pairingCode.substring(2)))
        val result = digest.digest()

        val expectedChecksum = pairingCode.substring(0, 2).toInt(16)
        return if ((result[0].toInt() and 0xFF) == expectedChecksum) result else null
    }
}
