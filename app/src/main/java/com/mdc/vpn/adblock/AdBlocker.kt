package com.mdc.vpn.adblock

import android.content.Context
import com.mdc.vpn.R

/**
 * AdGuard DNS filtre listesinden uretilen alan adi listesiyle reklam ve izleyici
 * engelleme.
 *
 * Liste `res/raw/adguard_hosts.txt` icinde alfabetik sirali tutulur. Tamami tek
 * bir `ByteArray` olarak okunup ikili arama ile sorgulandigi icin 190 binden
 * fazla alan adi yalnizca ~4 MB yer kaplar; ayni liste bir `HashSet` ile ~10 MB
 * olurdu (eski cihazlarda bu fark onemli).
 */
object AdBlocker {

    private const val NEWLINE = 10

    @Volatile
    private var table: Table? = null

    /** [host] veya ust alan adlarindan biri listede varsa `true`. */
    fun isBlocked(context: Context, host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val data = table ?: synchronized(this) {
            table ?: Table.load(context).also { table = it }
        }
        var candidate = host.lowercase()
        while (true) {
            if (data.contains(candidate)) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
            // Tek etiketli son parca (ornegin "com") hicbir zaman listelenmez.
            if (!candidate.contains('.')) return false
        }
    }

    /** `url` icindeki ana makine adini dondurur; ayristirilamazsa `null`. */
    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()
    }

    private class Table(private val data: ByteArray, private val starts: IntArray) {

        fun contains(name: String): Boolean {
            var low = 0
            var high = starts.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val result = compare(starts[mid], name)
                when {
                    result < 0 -> low = mid + 1
                    result > 0 -> high = mid - 1
                    else -> return true
                }
            }
            return false
        }

        /** `data[start]` ile baslayan satiri [name] ile sozluk sirasina gore karsilastirir. */
        private fun compare(start: Int, name: String): Int {
            var index = start
            var position = 0
            while (position < name.length) {
                if (index >= data.size) return -1
                val byte = data[index].toInt() and 0xFF
                if (byte == NEWLINE) return -1
                val expected = name[position].code
                if (byte != expected) return if (byte < expected) -1 else 1
                index++
                position++
            }
            val ended = index >= data.size || (data[index].toInt() and 0xFF) == NEWLINE
            return if (ended) 0 else -1
        }

        companion object {
            fun load(context: Context): Table {
                val data = context.resources
                    .openRawResource(R.raw.adguard_hosts)
                    .use { it.readBytes() }

                var lineCount = 0
                for (byte in data) if ((byte.toInt() and 0xFF) == NEWLINE) lineCount++
                if (data.isNotEmpty() && (data[data.size - 1].toInt() and 0xFF) != NEWLINE) lineCount++

                val starts = IntArray(lineCount)
                var cursor = 0
                var index = 0
                for (position in data.indices) {
                    if ((data[position].toInt() and 0xFF) == NEWLINE) {
                        starts[index++] = cursor
                        cursor = position + 1
                    }
                }
                if (cursor < data.size) starts[index] = cursor
                return Table(data, starts)
            }
        }
    }
}
