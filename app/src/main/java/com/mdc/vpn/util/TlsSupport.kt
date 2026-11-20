package com.mdc.vpn.util

import android.content.Context
import com.mdc.vpn.R
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Eski Android surumleri (5.x / 6.x) 2016 sonrasi yayinlanan kok sertifikalari
 * tanimadigi icin GitHub API'sine ve indirme sunucularina TLS el sikismasi
 * basarisiz oluyor ("sertifika" hatasi).
 *
 * Burada sistem kok deposuna ek olarak uygulamayla gelen guncel kok
 * sertifikalar ([R.raw.trusted_roots]) kullanilir ve destekleniyorsa TLS 1.2 /
 * 1.3 etkinlestirilir. Cihaz hicbir sertifika dogrulamasini atlamaz; yalnizca
 * bilinen-genel kok listesi genisletilir.
 */
object TlsSupport {

    @Volatile
    private var cached: SSLSocketFactory? = null

    /** Uygulama genelinde paylasilan TLS soket fabrikasi (bir kez kurulur). */
    fun socketFactory(context: Context): SSLSocketFactory? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching { build(context.applicationContext) }
                .onSuccess { cached = it }
                .getOrNull()
        }
    }

    private fun build(context: Context): SSLSocketFactory {
        val keyStore = runCatching {
            KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        }.getOrElse {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        }

        context.resources.openRawResource(R.raw.trusted_roots).use { input ->
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(input)
            certificates.forEachIndexed { index, certificate ->
                if (certificate is X509Certificate) {
                    runCatching { keyStore.setCertificateEntry("mdc-bundled-$index", certificate) }
                }
            }
        }

        val trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagerFactory.trustManagers, null)
        return ProtocolSocketFactory(sslContext.socketFactory)
    }

    /**
     * Sarmalayici: eski cihazlarda varsayilan olarak kapali gelen TLS 1.2
     * (ve varsa 1.3) protokolunu acar. Sertifika dogrulamasi degismez.
     */
    private class ProtocolSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            upgrade(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            upgrade(delegate.createSocket(host, port))

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket = upgrade(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            upgrade(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = upgrade(delegate.createSocket(address, port, localAddress, localPort))

        private fun upgrade(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.orEmpty()
                val enabled = supported.filter { it == "TLSv1.3" || it == "TLSv1.2" }
                if (enabled.isNotEmpty()) {
                    socket.enabledProtocols = enabled.toTypedArray()
                }
            }
            return socket
        }
    }
}
