package com.mdc.vpn.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mdc.vpn.R

/** Uygulamanin baglanmak icin kullandigi OpenVPN yapilandirmasi. */
data class VpnProfile(
    val displayName: String,
    val config: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val isCustom: Boolean
) {
    val endpoint: String get() = "$host:$port"
}

/**
 * Profil deposu.
 *
 * - Gömülü varsayilan profil: `assets/default.ovpn`
 * - Kullanicinin dosya seciciden ekledigi profil: `files/profiles/custom.ovpn`
 *
 * Profiller her zaman uygulamanin ozel alanina kopyalanir; boylece kullanici
 * dosyayi sildiginde veya SD karti cikardiginda tünel yapilandirmasi bozulmaz.
 */
object ProfileStore {

    private const val DEFAULT_ASSET = "default.ovpn"
    private const val PREFS = "mdc_vpn_profiles"
    private const val KEY_CUSTOM_NAME = "custom_profile_name"
    private const val MAX_CONFIG_BYTES = 512 * 1024

    fun load(context: Context): VpnProfile {
        val file = customFile(context)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank() && isValidConfig(text)) {
                val name = prefs(context).getString(KEY_CUSTOM_NAME, null)
                return buildProfile(
                    context = context,
                    config = text,
                    displayName = name ?: context.getString(R.string.profile_custom_name),
                    isCustom = true
                )
            }
        }
        return defaultProfile(context)
    }

    fun defaultProfile(context: Context): VpnProfile {
        val text = context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
        return buildProfile(
            context = context,
            config = text,
            displayName = context.getString(R.string.profile_default_name),
            isCustom = false
        )
    }

    /**
     * Dosya seciciden gelen `.ovpn` dosyasini okur, dogrular ve ozel alana kopyalar.
     *
     * @throws IllegalArgumentException dosya okunamaz, cok buyuk veya gecersizse.
     */
    fun importFrom(context: Context, uri: Uri): VpnProfile {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_CONFIG_BYTES) { "Config too large" }
            bytes.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Unreadable config")

        require(isValidConfig(text)) { "Invalid OpenVPN config" }

        val displayName = queryDisplayName(context, uri) ?: "custom.ovpn"
        val file = customFile(context)
        file.parentFile?.mkdirs()
        file.writeText(text)
        prefs(context).edit().putString(KEY_CUSTOM_NAME, displayName).apply()

        return buildProfile(context, text, displayName, isCustom = true)
    }

    /** Ozel profili siler ve gömülü varsayilan profile geri doner. */
    fun resetToDefault(context: Context) {
        customFile(context).delete()
        prefs(context).edit().remove(KEY_CUSTOM_NAME).apply()
    }

    private fun customFile(context: Context) = java.io.File(context.filesDir, "profiles/custom.ovpn")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun buildProfile(
        context: Context,
        config: String,
        displayName: String,
        isCustom: Boolean
    ): VpnProfile {
        val remote = parseRemote(config)
        return VpnProfile(
            displayName = displayName,
            config = config,
            host = remote?.first ?: context.getString(R.string.profile_unknown_host),
            port = remote?.second ?: DEFAULT_PORT,
            protocol = remote?.third ?: "udp",
            isCustom = isCustom
        )
    }

    /** Yapilandirmada en az bir `remote` satiri ve bir istemci tanimi olmali. */
    private fun isValidConfig(config: String): Boolean {
        val lines = config.lineSequence().map { it.trim() }.toList()
        val hasRemote = lines.any { it.startsWith("remote ") }
        val hasClient = lines.any { it == "client" || it.startsWith("pull") || it.startsWith("dev ") }
        return hasRemote && hasClient
    }

    /**
     * `remote <host> [port] [proto]` satirini okur. Birden fazla `remote` varsa
     * ilki kullanilir (OpenVPN de varsayilan olarak ilkini dener).
     */
    private fun parseRemote(config: String): Triple<String, Int, String>? {
        config.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("remote ")) return@forEach
            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 2) return@forEach
            val host = tokens[1]
            if (host == "random" || host == "remote_host") return@forEach
            val port = tokens.getOrNull(2)?.toIntOrNull() ?: DEFAULT_PORT
            val proto = when {
                tokens.getOrNull(3)?.contains("tcp") == true -> "tcp-client"
                else -> "udp"
            }
            return Triple(host, port, proto)
        }
        return null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    private const val DEFAULT_PORT = 1194
}
