package com.mdc.vpn.update

import android.content.Context
import android.os.Build
import com.mdc.vpn.util.TlsSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Uygulama ici guncelleme.
 *
 * Surum bilgisi GitHub Releases uzerinden okunur. Is akisi her yayinda
 * `v1.0.<run_number>` etiketi ve `MDC-VPN.apk` dosyasini urettigi icin uzak
 * `versionCode` degeri de ayni kurala gore (1000 + run_number) hesaplanabilir.
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/MDCforLEGAL/MDC-VPN/releases/latest"
    private const val VERSION_CODE_BASE = 1000
    private const val USER_AGENT = "MDC-VPN-Android"

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String?,
        val pageUrl: String
    )

    sealed interface Result {
        data object UpToDate : Result
        data class Available(val info: ReleaseInfo) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun checkLatest(context: Context, currentVersionCode: Int): Result =
        withContext(Dispatchers.IO) {
        runCatching {
            val body = get(context, API_URL)
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            val versionName = tag.removePrefix("v").ifBlank { json.optString("name") }
            val remoteCode = versionCodeOf(tag)

            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").ifBlank { null }
                        if (apkUrl != null) break
                    }
                }
            }

            val info = ReleaseInfo(
                versionName = versionName,
                versionCode = remoteCode,
                apkUrl = apkUrl,
                pageUrl = json.optString("html_url")
            )

            if (remoteCode > currentVersionCode && apkUrl != null) {
                Result.Available(info)
            } else {
                Result.UpToDate
            }
        }.getOrElse { error ->
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * APK'yi `cacheDir/updates` altina indirir. [onProgress] 0-100 arasi yuzde
     * degerini bildirir; icerik uzunlugu bilinmiyorsa cagrilmaz.
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val url = info.apkUrl ?: throw IllegalStateException("APK url missing")
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val target = File(directory, "MDC-VPN-${info.versionName}.apk")

        val connection = open(context, url).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            var copied = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) {
                            onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            require(target.length() > 0L) { "Empty download" }
            target
        } finally {
            connection.disconnect()
        }
    }

    /**
     * HTTPS baglantisi kurar. Eski cihazlarda guncel kok sertifikalar ve
     * TLS 1.2 kullanilabilmesi icin [TlsSupport] soket fabrikasi uygulanir.
     */
    private fun open(context: Context, url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            TlsSupport.socketFactory(context)?.let { connection.sslSocketFactory = it }
        }
        return connection
    }

    private fun get(context: Context, url: String): String {
        val connection = open(context, url).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** `v1.0.7` benzeri etiketten `1000 + 7` degerini uretir. */
    private fun versionCodeOf(tag: String): Int {
        val lastSegment = tag.trim().removePrefix("v").substringAfterLast('.')
        val runNumber = lastSegment.toIntOrNull() ?: return 0
        return VERSION_CODE_BASE + runNumber
    }
}
