package cn.paperforge.app.pdf

import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.*
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import java.io.*
import java.security.KeyStore
import java.security.cert.*
import java.util.Calendar
import java.util.Date
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.*
import org.bouncycastle.cms.jcajce.*
import org.bouncycastle.operator.jcajce.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline detached CMS signatures. Digest, revision coverage and certificate trust are separate.
 */
object PdfCertificates {
    private val bc
        get() = CertificateVault.provider

    private fun name(value: String) = COSName.getPDFName(value)

    fun mdpPermission(doc: PDDocument): Int {
        val perms = doc.documentCatalog.cosObject.getCOSDictionary(name("Perms")) ?: return 0
        val sig = perms.getCOSDictionary(name("DocMDP")) ?: return 0
        val refs = sig.getCOSArray(name("Reference")) ?: return 0
        for (ref in refs) {
            val dict = (if (ref is COSObject) ref.`object` else ref) as? COSDictionary ?: continue
            if (dict.getNameAsString(name("TransformMethod")) == "DocMDP")
                return dict.getCOSDictionary(name("TransformParams"))?.getInt(name("P"), 2) ?: 2
        }
        return 0
    }

    suspend fun sign(input: File, output: File, options: JSONObject): JSONObject {
        require(input.canonicalFile != output.canonicalFile) { "签署输出不能覆盖原件" }
        require(PdfAssembly.sha256(input) == options.getString("sourceSha256")) {
            "原文件已变化，请重新打开签署页面"
        }
        val identity =
            CertificateVault.resolve(
                options.getString("alias"),
                options.getString("certificateSha256"),
            )
        identity.certificate.checkValidity()
        val key = CertificateVault.key(identity)
        val cms =
            CMSSignedDataStreamGenerator().apply {
                // Default JCA selection is required for non-exportable AndroidKeyStore keys.
                val signer = JcaContentSignerBuilder(CertificateVault.algorithm(key)).build(key)
                addSignerInfoGenerator(
                    JcaSignerInfoGeneratorBuilder(
                            JcaDigestCalculatorProviderBuilder().setProvider(bc).build()
                        )
                        .build(signer, identity.certificate)
                )
                addCertificates(JcaCertStore(identity.chain))
            }
        output.parentFile?.mkdirs()
        val part = File(output.path + ".part")
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                require(mdpPermission(doc) != 1) { "文档认证签名禁止后续修改" }
                require(doc.documentCatalog.getAcroForm(null)?.xfaIsDynamic() != true) {
                    "动态 XFA 需要专用表单引擎"
                }
                val sig =
                    PDSignature().apply {
                        setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                        setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                        setName(identity.subject)
                        setReason(options.optString("reason"))
                        setLocation(options.optString("location"))
                        setSignDate(Calendar.getInstance())
                    }
                val fieldName = options.optString("field")
                val field =
                    fieldName
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            doc.documentCatalog.getAcroForm(null)?.getField(it) as? PDSignatureField
                                ?: error("所选签名字段已不存在")
                        }
                require(field == null || (field.signature == null && !field.isReadOnly)) {
                    "所选签名字段不可签署"
                }
                val widget = field?.widgets?.firstOrNull()
                val rectangle = widget?.rectangle
                val appearance = widget?.appearance
                field?.value = sig
                SignatureOptions().use { settings ->
                    settings.preferredSignatureSize = 32768
                    doc.addSignature(
                        sig,
                        { content ->
                            ByteArrayOutputStream().use { result ->
                                cms.open(result, false).use { signed -> content.copyTo(signed) }
                                result.toByteArray()
                            }
                        },
                        settings,
                    )
                    if (widget != null) {
                        widget.rectangle = rectangle
                        widget.appearance = appearance
                        widget.cosObject.isNeedToBeUpdated = true
                    }
                    part.outputStream().use { doc.saveIncremental(it) }
                }
            }
            val report = verify(part)
            val signatures = report.getJSONArray("signatures")
            require(
                (0 until signatures.length()).any { index ->
                    val entry = signatures.getJSONObject(index)
                    entry.optString("integrity") == "valid" && entry.optBoolean("coversCurrentFile")
                }
            ) {
                "新签署结果未通过摘要核验"
            }
            check(part.renameTo(output)) { "无法保存签署结果" }
            return report
                .put("fileName", output.name)
                .put("originalSha256", PdfAssembly.sha256(input))
        } finally {
            part.delete()
        }
    }

    suspend fun verify(input: File): JSONObject =
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val fields = doc.signatureFields
            val signatures = JSONArray()
            doc.signatureDictionaries.forEachIndexed { index, sig ->
                val result =
                    JSONObject()
                        .put("index", index + 1)
                        .put(
                            "field",
                            fields
                                .firstOrNull { it.signature?.cosObject === sig.cosObject }
                                ?.fullyQualifiedName ?: "",
                        )
                        .put("name", sig.name ?: "")
                        .put("reason", sig.reason ?: "")
                        .put("location", sig.location ?: "")
                        .put("claimedSigningTime", sig.signDate?.timeInMillis)
                        .put("subFilter", sig.subFilter ?: "")
                        .put("integrity", "unverified")
                try {
                    val array =
                        sig.cosObject.getCOSArray(COSName.BYTERANGE) ?: error("缺少 ByteRange")
                    require(array.size() == 4) { "ByteRange 必须覆盖两段字节" }
                    val range = LongArray(4) { (array.getObject(it) as COSNumber).longValue() }
                    val a = range[1]
                    val b = range[2]
                    val end = Math.addExact(b, range[3])
                    require(
                        range[0] == 0L && a > 0 && b > a && range[3] >= 0 && end <= input.length()
                    ) {
                        "签署字节范围无效"
                    }
                    result
                        .put("signedRevisionBytes", end)
                        .put("fileBytes", input.length())
                        .put("coversCurrentFile", end == input.length())
                        .put("byteRange", JSONArray(range.toList()))
                    // Bind excluded bytes to THIS dictionary's Contents; do not accept arbitrary
                    // excluded content.
                    RandomAccessFile(input, "r").use { raw ->
                        require(b - a <= 16 * 1024 * 1024) { "签名容器过大" }
                        val gap = ByteArray((b - a).toInt())
                        raw.seek(a)
                        raw.readFully(gap)
                        require(gap.first().toInt() == '<'.code && gap.last().toInt() == '>'.code) {
                            "仅核验十六进制 Contents 编码"
                        }
                        val decoded =
                            COSString.parseHex(String(gap, 1, gap.size - 2, Charsets.US_ASCII))
                                .bytes
                        require(decoded.contentEquals(sig.contents)) { "Contents 与签名保留区不一致" }
                    }
                    require(sig.subFilter in listOf("adbe.pkcs7.detached", "ETSI.CAdES.detached")) {
                        "当前仅支持 detached CMS / CAdES 签名"
                    }
                    val encoded =
                        ASN1InputStream(sig.contents).use {
                            it.readObject()?.encoded ?: error("签名容器为空")
                        }
                    RangeInput(input, range).use { content ->
                        val parser =
                            CMSSignedDataParser(
                                JcaDigestCalculatorProviderBuilder().setProvider(bc).build(),
                                CMSTypedStream(content),
                                encoded,
                            )
                        try {
                            parser.signedContent.drain()
                            @Suppress("UNCHECKED_CAST")
                            val certificates =
                                parser.certificates
                                    as org.bouncycastle.util.Store<X509CertificateHolder>
                            val signers = JSONArray()
                            var allValid = true
                            for (signer in parser.signerInfos.signers) {
                                val holder =
                                    certificates.getMatches(null).firstOrNull {
                                        signer.sid.match(it)
                                    } ?: error("缺少签署者证书")
                                val certificate =
                                    JcaX509CertificateConverter()
                                        .setProvider(bc)
                                        .getCertificate(holder)
                                // Verify mathematical integrity independently of certificate dates
                                // or trust.
                                val valid =
                                    try {
                                        signer.verify(
                                            JcaSimpleSignerInfoVerifierBuilder()
                                                .setProvider(bc)
                                                .build(certificate.publicKey)
                                        )
                                    } catch (_: CMSException) {
                                        false
                                    }
                                allValid = allValid && valid
                                val chain =
                                    certificates.getMatches(null).map {
                                        JcaX509CertificateConverter()
                                            .setProvider(bc)
                                            .getCertificate(it)
                                    }
                                signers.put(
                                    certificateInfo(certificate, chain)
                                        .put("digestAndSignatureValid", valid)
                                        .put("digestAlgorithmOid", signer.digestAlgOID)
                                        .put("signatureAlgorithmOid", signer.encryptionAlgOID)
                                )
                            }
                            require(signers.length() > 0) { "没有签署者信息" }
                            result
                                .put("signers", signers)
                                .put("integrity", if (allValid) "valid" else "invalid")
                        } finally {
                            parser.close()
                        }
                    }
                } catch (error: Exception) {
                    result
                        .put("integrity", "unverified")
                        .put("error", error.message ?: error.javaClass.simpleName)
                }
                signatures.put(result)
            }
            JSONObject()
                .put("schema", 1)
                .put("fileName", input.name)
                .put("sourceSha256", PdfAssembly.sha256(input))
                .put("checkedAt", System.currentTimeMillis())
                .put("docMdpPermission", mdpPermission(doc))
                .put("signatures", signatures)
                .put("offline", true)
                .put("revocationChecked", false)
                .put("timestampValidated", false)
        }

    private fun certificateInfo(cert: X509Certificate, chain: List<X509Certificate>): JSONObject {
        val sha = CertificateVault.fingerprint(cert)
        val own = CertificateVault.list().any { it.fingerprint == sha }
        val now = Date()
        val validity =
            if (now < cert.notBefore) "not-yet-valid"
            else if (now > cert.notAfter) "expired" else "valid-now"
        var trust = if (own) "local-private-key" else "untrusted"
        var trustError = ""
        try {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val anchors =
                store
                    .aliases()
                    .toList()
                    .mapNotNull { store.getCertificate(it) as? X509Certificate }
                    .map { TrustAnchor(it, null) }
                    .toSet()
            val params =
                PKIXBuilderParameters(anchors, X509CertSelector().apply { certificate = cert })
                    .apply {
                        isRevocationEnabled = false
                        addCertStore(
                            CertStore.getInstance(
                                "Collection",
                                CollectionCertStoreParameters(chain),
                            )
                        )
                    }
            CertPathBuilder.getInstance("PKIX").build(params)
            trust = "system-ca"
        } catch (error: Exception) {
            trustError = error.message.orEmpty()
        }
        return JSONObject()
            .put("subject", cert.subjectX500Principal.name)
            .put("issuer", cert.issuerX500Principal.name)
            .put("serial", cert.serialNumber.toString(16))
            .put("sha256", sha)
            .put("validFrom", cert.notBefore.time)
            .put("validUntil", cert.notAfter.time)
            .put("validity", validity)
            .put("trust", trust)
            .put("trustDetail", trustError)
    }

    private class RangeInput(file: File, private val range: LongArray) : InputStream() {
        private val raw = RandomAccessFile(file, "r")
        private var position = 0L
        private val length = range[1] + range[3]

        override fun read(): Int =
            ByteArray(1).let { if (read(it, 0, 1) == -1) -1 else it[0].toInt() and 255 }

        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            if (position >= length) return -1
            val first = position < range[1]
            val remaining = if (first) range[1] - position else length - position
            raw.seek(if (first) position else range[2] + position - range[1])
            val read = raw.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("签署字节范围提前结束")
            position += read
            return read
        }

        override fun close() = raw.close()
    }
}
