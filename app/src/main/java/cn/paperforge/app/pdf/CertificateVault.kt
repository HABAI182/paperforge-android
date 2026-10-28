package cn.paperforge.app.pdf

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** User document certificates are independent of the application's update signing key. */
object CertificateVault {
    val provider by lazy { BouncyCastleProvider() }
    private const val PREFIX = "paperforge-document-"

    data class Identity(val alias: String, val chain: List<X509Certificate>) {
        val certificate
            get() = chain.first()

        val fingerprint
            get() = fingerprint(certificate)

        val subject
            get() = certificate.subjectX500Principal.name

        val validNow
            get() = runCatching { certificate.checkValidity() }.isSuccess
    }

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun fingerprint(cert: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
            "%02x".format(it)
        }

    fun list(): List<Identity> {
        val ks = store()
        return ks.aliases()
            .toList()
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { alias ->
                ks.getCertificateChain(alias)
                    ?.map { it as X509Certificate }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Identity(alias, it) }
            }
            .sortedBy { it.subject }
    }

    fun resolve(alias: String, fingerprint: String): Identity =
        list().firstOrNull { it.alias == alias && it.fingerprint == fingerprint }
            ?: list().firstOrNull { it.fingerprint == fingerprint }
            ?: error("本机没有这张签署证书的私钥，请重新导入相同的 P12 / PFX 证书")

    fun key(identity: Identity): PrivateKey =
        store().getKey(identity.alias, null) as? PrivateKey ?: error("证书私钥不可用")

    fun create(name: String, years: Int = 5): Identity {
        require(name.isNotBlank()) { "请填写证书姓名或名称" }
        require(years in 1..30) { "有效期请填写 1 至 30 年" }
        val alias = PREFIX + UUID.randomUUID()
        val now = Date(System.currentTimeMillis() - 60_000)
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, years) }.time
        val subject =
            X500Principal(
                org.bouncycastle.asn1.x500
                    .X500NameBuilder()
                    .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, name.trim())
                    .build()
                    .encoded
            )
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                        )
                        .setKeySize(2048)
                        .setDigests(
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512,
                        )
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(subject)
                        .setCertificateSerialNumber(
                            BigInteger(128, SecureRandom()).add(BigInteger.ONE)
                        )
                        .setCertificateNotBefore(now)
                        .setCertificateNotAfter(end)
                        .build()
                )
                generateKeyPair()
            }
            return list().first { it.alias == alias }
        } catch (error: Exception) {
            store().deleteEntry(alias)
            throw error
        }
    }

    /** Password is used only in this call; neither password nor PKCS#12 bytes are persisted. */
    fun import(input: InputStream, password: CharArray): List<Identity> {
        val imported = mutableListOf<Identity>()
        try {
            val p12 = KeyStore.getInstance("PKCS12", provider).apply { load(input, password) }
            for (sourceAlias in p12.aliases().toList().filter { p12.isKeyEntry(it) }) {
                val key = p12.getKey(sourceAlias, password) as? PrivateKey ?: continue
                val chain =
                    p12.getCertificateChain(sourceAlias)
                        ?.map { it as X509Certificate }
                        ?.takeIf { it.isNotEmpty() } ?: continue
                require(key.algorithm in listOf("RSA", "EC")) { "仅支持 RSA / EC 私钥" }
                require(chain.first().keyUsage?.getOrNull(0) != false) { "此证书不允许数字签署" }
                val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val algorithm = algorithm(key)
                val signed =
                    Signature.getInstance(algorithm, provider).run {
                        initSign(key)
                        update(challenge)
                        sign()
                    }
                check(
                    Signature.getInstance(algorithm, provider).run {
                        initVerify(chain.first())
                        update(challenge)
                        verify(signed)
                    }
                ) {
                    "私钥与证书不匹配"
                }
                val existing = list().firstOrNull { it.fingerprint == fingerprint(chain.first()) }
                if (existing != null) {
                    imported += existing
                    continue
                }
                val alias = PREFIX + UUID.randomUUID()
                val protection =
                    KeyProtection.Builder(
                            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                        )
                        .setDigests(
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512,
                        )
                        .apply {
                            if (key.algorithm == "RSA")
                                setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        }
                        .build()
                store()
                    .setEntry(
                        alias,
                        KeyStore.PrivateKeyEntry(key, chain.toTypedArray()),
                        protection,
                    )
                imported += Identity(alias, chain)
            }
            require(imported.isNotEmpty()) { "文件中没有可导入的 RSA / EC 私钥证书" }
            return imported
        } finally {
            password.fill('\u0000')
        }
    }

    fun remove(alias: String) {
        require(alias.startsWith(PREFIX))
        store().deleteEntry(alias)
    }

    fun algorithm(key: PrivateKey): String =
        when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> error("不支持的签署密钥 ${key.algorithm}")
        }
}
