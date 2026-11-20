package com.mdc.vpn.util

import java.util.Locale

/** Arayuzde gosterilen sayisal degerler icin kucuk yardimci fonksiyonlar. */
object Formatters {

    /** Saniye cinsinden sureyi `SS:dd:ss` (saat:dakika:saniye) bicimine cevirir. */
    fun duration(totalSeconds: Long): String {
        val safeSeconds = if (totalSeconds < 0L) 0L else totalSeconds
        val hours = safeSeconds / 3600
        val minutes = (safeSeconds % 3600) / 60
        val seconds = safeSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /** Saniyedeki bayt degerini okunabilir hiz metnine cevirir. */
    fun speed(bytesPerSecond: Long): String {
        val value = if (bytesPerSecond < 0L) 0L else bytesPerSecond
        return when {
            value < 1024L -> "$value B/s"
            value < 1024L * 1024L -> String.format(Locale.getDefault(), "%.0f KB/s", value / 1024.0)
            value < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB/s", value / (1024.0 * 1024.0))
            else -> String.format(Locale.getDefault(), "%.2f GB/s", value / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /** `.ovpn` dosyasindan okunan sunucu bilgisini `host:port` olarak dondurur. */
    fun endpoint(host: String, port: Int): String = "$host:$port"
}
