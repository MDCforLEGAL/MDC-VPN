package com.mdc.vpn.byedpi

import android.content.Context
import com.mdc.vpn.R

/** ByeDPI (ciadpi) icin hazir strateji. */
data class ByeDpiPreset(
    val id: String,
    val titleRes: Int,
    val args: List<String>
)

/**
 * Mobil DPI atlatma ayarlari.
 *
 * `ciadpi` 127.0.0.1:1080 uzerinde bir SOCKS5 proxy calistirir; hazir
 * stratejiler `hufrea/byedpi` belgelerindeki onerilen desenlerden ve
 * ByeDPIAndroid'in varsayilanlarindan uyarlanmistir.
 */
object ByeDpiSettings {

    private const val PREFS = "mdc_vpn_byedpi"
    private const val KEY_PRESET = "preset"
    private const val KEY_CUSTOM = "custom_args"

    const val CUSTOM = "custom"

    val presets: List<ByeDpiPreset> = listOf(
        ByeDpiPreset(
            "balanced",
            R.string.byedpi_preset_balanced,
            listOf("--split", "1", "--disorder", "1")
        ),
        ByeDpiPreset(
            "strong",
            R.string.byedpi_preset_strong,
            listOf("--split", "1+s", "--disorder", "3+s")
        ),
        ByeDpiPreset(
            "fake",
            R.string.byedpi_preset_fake,
            listOf("--fake", "-1", "--ttl", "8", "--split", "1")
        ),
        ByeDpiPreset(
            "tlsrec",
            R.string.byedpi_preset_tlsrec,
            listOf("--tlsrec", "1+s", "--split", "1+s")
        ),
        // Superonline (Turkcell) DPI'i SNI üzerinden resetlediği için TLS
        // ClientHello'nun SNI alanından bölünmesi gerekir; bu stratejiler
        // Superonline hatlarında yaygın çalışan varyantlardır.
        ByeDpiPreset(
            "superonline-split",
            R.string.byedpi_preset_superonline_split,
            listOf("--split", "1+s", "--disorder", "1+s")
        ),
        ByeDpiPreset(
            "superonline-oob",
            R.string.byedpi_preset_superonline_oob,
            listOf("--split", "1+s", "--oob", "1+s")
        ),
        ByeDpiPreset(
            "superonline-fake",
            R.string.byedpi_preset_superonline_fake,
            listOf("--fake", "-1", "--ttl", "6", "--split", "1+s")
        ),
        ByeDpiPreset(
            "superonline-tlsrec",
            R.string.byedpi_preset_superonline_tlsrec,
            listOf("--tlsrec", "1+s", "--split", "1+s", "--disorder", "1+s")
        ),
        ByeDpiPreset(CUSTOM, R.string.byedpi_preset_custom, emptyList())
    )

    fun presetId(context: Context): String {
        val stored = prefs(context).getString(KEY_PRESET, null)
        return if (presets.any { it.id == stored }) stored!! else presets.first().id
    }

    fun setPreset(context: Context, id: String) {
        prefs(context).edit().putString(KEY_PRESET, id).apply()
    }

    fun preset(context: Context): ByeDpiPreset =
        presets.firstOrNull { it.id == presetId(context) } ?: presets.first()

    fun customArgs(context: Context): String =
        prefs(context).getString(KEY_CUSTOM, "") ?: ""

    fun setCustomArgs(context: Context, args: String) {
        prefs(context).edit().putString(KEY_CUSTOM, args.trim()).apply()
    }

    /** `ciadpi` ikilisine verilecek argumanlar (program adi haric). */
    fun args(context: Context): List<String> {
        val preset = preset(context)
        return if (preset.id == CUSTOM) splitArgs(customArgs(context)) else preset.args
    }

    /** Ayarlar ekraninda ve profil kartinda gosterilen kisa aciklama. */
    fun summary(context: Context): String {
        val preset = preset(context)
        return if (preset.id == CUSTOM) {
            val custom = customArgs(context)
            if (custom.isBlank()) context.getString(R.string.byedpi_custom_empty) else custom
        } else {
            context.getString(preset.titleRes)
        }
    }

    /** Tirnak iceren argumanlari koruyarak bosluklara gore ayirir. */
    fun splitArgs(raw: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var escaped = false
        for (ch in raw) {
            when {
                escaped -> {
                    token.append(ch)
                    escaped = false
                }
                ch == '\\' -> escaped = true
                ch == '"' -> quoted = !quoted
                ch.isWhitespace() && !quoted -> {
                    if (token.isNotEmpty()) {
                        result.add(token.toString())
                        token.clear()
                    }
                }
                else -> token.append(ch)
            }
        }
        if (token.isNotEmpty()) result.add(token.toString())
        return result
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
