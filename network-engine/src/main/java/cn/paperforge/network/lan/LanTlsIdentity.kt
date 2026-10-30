package cn.paperforge.network.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** App-owned, non-exportable signing key. A browser must confirm the certificate fingerprint. */
internal class LanTlsIdentity
private constructor(val context: SSLContext, val fingerprint: String) {
    companion object {
        private const val alias = "paperforge-lan-tls-ec-v1"

        @Synchronized
        fun load(app: Context): LanTlsIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val valid =
                runCatching { (store.getCertificate(alias) as X509Certificate).checkValidity() }
                    .isSuccess
            if (!valid) {
                if (store.containsAlias(alias)) store.deleteEntry(alias)
                val now = System.currentTimeMillis()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                    .apply {
                        initialize(
                            KeyGenParameterSpec.Builder(
                                    alias,
                                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                                )
                                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                                .setCertificateSubject(
                                    X500Principal("CN=PaperForge Local Transfer")
                                )
                                .setCertificateSerialNumber(
                                    BigInteger(128, SecureRandom()).add(BigInteger.ONE)
                                )
                                .setCertificateNotBefore(Date(now - 86400000))
                                .setCertificateNotAfter(Date(now + 3650L * 86400000))
                                .build()
                        )
                        generateKeyPair()
                    }
                // Credentials previously sent over HTTP cannot inherit trust in the new identity.
                val ledger = LanLedger.get(app)
                ledger.peers.value.forEach { ledger.revoke(it.id) }
            }
            val chain =
                store.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
            val key = store.getKey(alias, null) as PrivateKey
            val manager =
                object : X509ExtendedKeyManager() {
                    override fun getPrivateKey(name: String?) = if (name == alias) key else null

                    override fun getCertificateChain(name: String?) =
                        if (name == alias) chain else null

                    override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                        if (type == "EC") arrayOf(alias) else null

                    override fun chooseServerAlias(
                        type: String?,
                        issuers: Array<out Principal>?,
                        socket: Socket?,
                    ) = getServerAliases(type, issuers)?.firstOrNull()

                    override fun chooseEngineServerAlias(
                        type: String?,
                        issuers: Array<out Principal>?,
                        engine: SSLEngine?,
                    ) = getServerAliases(type, issuers)?.firstOrNull()

                    override fun getClientAliases(
                        type: String?,
                        issuers: Array<out Principal>?,
                    ): Array<String>? = null

                    override fun chooseClientAlias(
                        types: Array<out String>?,
                        issuers: Array<out Principal>?,
                        socket: Socket?,
                    ): String? = null
                }
            val tls =
                SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, SecureRandom()) }
            return LanTlsIdentity(tls, LanLedger.digest(chain.first().encoded))
        }
    }
}

/**
 * TLS already authenticates records; this also rejects duplicated/stale authenticated API calls.
 */
internal class LanReplayWindow {
    private val used = linkedMapOf<String, Long>()

    @Synchronized
    fun accept(
        peer: String,
        nonce: String,
        time: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!nonce.matches(Regex("[0-9a-f]{32}")) || time < now - 300000 || time > now + 300000)
            return false
        used.entries.removeAll { it.value < now }
        val key = "$peer:$nonce"
        if (key in used || used.size >= 16384) return false
        used[key] = now + 600000
        return true
    }
}
