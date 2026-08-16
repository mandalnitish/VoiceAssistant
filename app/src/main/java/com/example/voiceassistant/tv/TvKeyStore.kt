package com.example.voiceassistant.tv

import android.content.Context
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Generates (once) and holds the RSA key pair + self-signed certificate used
 * for mutual TLS with the TV, and builds the SSLContext for talking to it.
 *
 * NOTE - this used to live in the Android Keystore (hardware-backed), which is
 * the "right" place for a private key in general. It was moved out on purpose:
 * the TLS handshake's CertificateVerify step needs a raw RSA private-key
 * operation, and Conscrypt performs that via a Cipher-based upcall into the
 * Keystore. On this device's secure hardware, an RSA key with the SIGN +
 * ENCRYPT/DECRYPT purpose combination that trick needs is rejected outright
 * with "KeyStoreException: Incompatible padding mode" - regardless of which
 * padding/digest combination is declared on the key. That's a hardware/KeyMint
 * limitation, not something fixable from the KeyGenParameterSpec.
 *
 * So instead: a plain software RSA key, generated once and persisted to a
 * PKCS12 file in this app's private storage (same trust boundary the old
 * PC-side tv-bridge had with its tv_key.pem - not hardware-protected, but
 * this is just a local-network pairing identity, not sensitive data).
 */
object TvKeyStore {

    private const val ALIAS = "tv_remote_client_key"

    // Protects the PKCS12 file. The file already lives in this app's private
    // storage (inaccessible to other apps without root), so this password
    // isn't adding a second layer of secrecy - it's just what the KeyStore
    // API requires to open/save a PKCS12 entry.
    private const val STORE_PASSWORD = "tv-remote-local-identity"

    @Volatile private var cached: KeyStore? = null

    private fun storeFile(context: Context) = File(context.filesDir, "tv_remote_identity.p12")

    private fun loadOrCreate(context: Context): KeyStore {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = storeFile(context)
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                file.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
            } else {
                ks.load(null)
                generateAndStore(ks, file)
            }
            cached = ks
            return ks
        }
    }

    private fun generateAndStore(ks: KeyStore, file: File) {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val subject = X500Principal("CN=voice-assistant")
        val notBefore = Calendar.getInstance().time
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }.time
        val serial = BigInteger(64, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        ks.setKeyEntry(ALIAS, keyPair.private, STORE_PASSWORD.toCharArray(), arrayOf(cert))
        file.outputStream().use { ks.store(it, STORE_PASSWORD.toCharArray()) }
    }

    fun clientCertificate(context: Context): X509Certificate =
        loadOrCreate(context).getCertificate(ALIAS) as X509Certificate

    /**
     * Builds an SSLContext that presents our identity cert for mutual TLS and trusts
     * whatever certificate the TV presents (trust-on-first-use - the TV's cert isn't
     * CA-signed; this mirrors the PC bridge's verify_mode = CERT_NONE). Fine on a
     * private home network; these ports should never be exposed to the internet.
     *
     * Pinned to TLS 1.2 rather than the generic "TLS" (which also offers 1.3) - this
     * pairing protocol predates 1.3, and 1.2 is what it's been verified against here.
     */
    fun buildSslContext(context: Context): SSLContext {
        val ks = loadOrCreate(context)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, STORE_PASSWORD.toCharArray())
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(arrayOf(alwaysOfferOurCert(kmf, context)), arrayOf(trustAll), null)
        return ctx
    }

    /**
     * Wraps the stock X509KeyManager so it ALWAYS offers our client certificate,
     * no matter which issuers the server's CertificateRequest claims to accept.
     *
     * This matters here because our cert is self-signed ("CN=voice-assistant"),
     * so its issuer never matches whatever the TV's pairing service advertises.
     * The default KeyManager's alias-matching logic treats that as "no usable
     * certificate" and silently sends an empty certificate list instead - the TV
     * then aborts the handshake, which is what surfaces as the generic
     * "Read error: ssl=...: Failure in SSL library, usually a protocol error"
     * (a TLSV1_CERTIFICATE_REQUIRED alert under the hood). Forcing our alias
     * unconditionally is the standard fix for mutual TLS against servers like
     * this that don't send (or don't care about) a proper trusted-issuer list.
     */
    private fun alwaysOfferOurCert(kmf: KeyManagerFactory, context: Context): X509ExtendedKeyManager {
        val delegate = kmf.keyManagers.first { it is X509KeyManager } as X509KeyManager
        return object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String = ALIAS

            override fun chooseEngineClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                engine: SSLEngine?
            ): String = ALIAS

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                delegate.getCertificateChain(ALIAS) ?: arrayOf(clientCertificate(context))

            override fun getPrivateKey(alias: String?) = delegate.getPrivateKey(ALIAS)

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf(ALIAS)

            // Server-side selection is never used by this client, but the interface requires it.
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null
        }
    }
}