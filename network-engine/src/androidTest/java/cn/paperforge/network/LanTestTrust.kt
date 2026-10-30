package cn.paperforge.network

import android.net.http.SslCertificate
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.paperforge.network.lan.LanLedger
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Test-only equivalent of checking the browser certificate against the phone fingerprint. */
object LanTestTrust {
    fun factory(fingerprint: String): SSLSocketFactory {
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) {
                    throw CertificateException("No client certificates")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) {
                    val cert =
                        chain?.firstOrNull() ?: throw CertificateException("No server certificate")
                    cert.checkValidity()
                    if (LanLedger.digest(cert.encoded) != fingerprint)
                        throw CertificateException("Wrong phone identity")
                }
            }
        return SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trust), null) }
            .socketFactory
    }

    fun browser(fingerprint: String) =
        object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                val bytes =
                    SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate")
                if (bytes != null && LanLedger.digest(bytes) == fingerprint) handler.proceed()
                else handler.cancel()
            }
        }
}
