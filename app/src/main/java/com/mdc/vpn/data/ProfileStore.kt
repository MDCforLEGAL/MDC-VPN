package com.mdc.vpn.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mdc.vpn.R

/** Desteklenen tunel cekirdekleri. */
enum class VpnEngine {
    OPENVPN,
    WIREGUARD
}

/** Uygulamanin baglanmak icin kullandigi yapilandirma. */
data class VpnProfile(
    val displayName: String,
    val config: String,
    val host: String,
    val port: Int,
    /** OpenVPN icin tasima protokolu (`udp` / `tcp-client`). */
    val protocol: String,
    val isCustom: Boolean,
    val engine: VpnEngine
) {
    val endpoint: String get() = if (port > 0) "$host:$port" else host

    /** Yapilandirma yuklenmis mi? (WireGuard icin dosya icе aktarilmamis olabilir.) */
    val isConfigured: Boolean get() = config.isNotBlank()
}

/**
 * Profil deposu.
 *
 * - OpenVPN varsayilani: `assets/default.ovpn`
 * - OpenVPN ozel profili: `files/profiles/custom.ovpn`
 * - WireGuard ozel profili: `files/profiles/custom.conf`
 *
 * Profiller her zaman uygulamanin ozel alanina kopyalanir; boylece kullanici
 * dosyayi sildiginde veya SD karti cikardiginda tunel yapilandirmasi bozulmaz.
 */
object ProfileStore {

    private const val DEFAULT_OVPN_ASSET = "default.ovpn"
    private const val PREFS = "mdc_vpn_profiles"
    private const val KEY_CUSTOM_NAME = "custom_profile_name"
    private const val KEY_WIREGUARD_NAME = "wireguard_profile_name"
    private const val KEY_ENGINE = "engine"
    private const val MAX_CONFIG_BYTES = 512 * 1024

    private const val DEFAULT_PORT = 1194
    private const val DEFAULT_WIREGUARD_PORT = 51820

    /** Secili tunel cekirdegi; varsayilan OpenVPN. */
    fun engine(context: Context): VpnEngine = when (prefs(context).getString(KEY_ENGINE, null)) {
        VpnEngine.WIREGUARD.name -> VpnEngine.WIREGUARD
        else -> VpnEngine.OPENVPN
    }

    fun setEngine(context: Context, engine: VpnEngine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    /** Secili protokolun profilini dondurur. */
    fun load(context: Context): VpnProfile = load(context, engine(context))

    fun load(context: Context, engine: VpnEngine): VpnProfile = when (engine) {
        VpnEngine.OPENVPN -> openVpnProfile(context)
        VpnEngine.WIREGUARD -> wireGuardProfile(context)
    }

    fun defaultProfile(context: Context): VpnProfile = openVpnProfile(context)

    /**
     * Dosya seciciden gelen yapilandirmayi okur, turunu algilar, dogrular ve
     * ozel alana kopyalar. Algilanan protokol secili protokol olarak kaydedilir.
     *
     * @throws IllegalArgumentException dosya okunamaz, cok buyuk veya gecersizse.
     */
    fun importFrom(context: Context, uri: Uri): VpnProfile {
        val text = readText(context, uri)
        val displayName = queryDisplayName(context, uri)
            ?: if (looksLikeWireGuard(text)) "wireguard.conf" else "custom.ovpn"

        return if (looksLikeWireGuard(text)) {
            require(isValidWireGuard(text)) { "Invalid WireGuard config" }
            val file = wireGuardFile(context)
            file.parentFile?.mkdirs()
            file.writeText(text)
            prefs(context).edit()
                .putString(KEY_WIREGUARD_NAME, displayName)
                .putString(KEY_ENGINE, VpnEngine.WIREGUARD.name)
                .apply()
            buildWireGuardProfile(context, text, displayName, isCustom = true)
        } else {
            require(isValidOpenVpn(text)) { "Invalid OpenVPN config" }
            val file = openVpnFile(context)
            file.parentFile?.mkdirs()
            file.writeText(text)
            prefs(context).edit()
                .putString(KEY_CUSTOM_NAME, displayName)
                .putString(KEY_ENGINE, VpnEngine.OPENVPN.name)
                .apply()
            buildOpenVpnProfile(context, text, displayName, isCustom = true)
        }
    }

    /** Ice aktarilan profilleri siler ve varsayilan OpenVPN profiline doner. */
    fun resetCustom(context: Context) {
        openVpnFile(context).delete()
        wireGuardFile(context).delete()
        prefs(context).edit()
            .remove(KEY_CUSTOM_NAME)
            .remove(KEY_WIREGUARD_NAME)
            .putString(KEY_ENGINE, VpnEngine.OPENVPN.name)
            .apply()
    }

    // ---------------------------------------------------------------- OpenVPN

    private fun openVpnProfile(context: Context): VpnProfile {
        val file = openVpnFile(context)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank() && isValidOpenVpn(text)) {
                val name = prefs(context).getString(KEY_CUSTOM_NAME, null)
                    ?: context.getString(R.string.profile_custom_name)
                return buildOpenVpnProfile(context, text, name, isCustom = true)
            }
        }
        val bundled = context.assets.open(DEFAULT_OVPN_ASSET).bufferedReader().use { it.readText() }
        return buildOpenVpnProfile(
            context = context,
            config = bundled,
            displayName = context.getString(R.string.profile_default_name),
            isCustom = false
        )
    }

    private fun buildOpenVpnProfile(
        context: Context,
        config: String,
        displayName: String,
        isCustom: Boolean
    ): VpnProfile {
        val remote = parseOpenVpnRemote(config)
        return VpnProfile(
            displayName = displayName,
            config = config,
            host = remote?.first ?: context.getString(R.string.profile_unknown_host),
            port = remote?.second ?: DEFAULT_PORT,
            protocol = remote?.third ?: "udp",
            isCustom = isCustom,
            engine = VpnEngine.OPENVPN
        )
    }

    /** Yapilandirmada en az bir `remote` satiri ve bir istemci tanimi olmali. */
    private fun isValidOpenVpn(config: String): Boolean {
        val lines = config.lineSequence().map { it.trim() }.toList()
        val hasRemote = lines.any { it.startsWith("remote ") }
        val hasClient = lines.any {
            it == "client" || it.startsWith("pull") || it.startsWith("dev ")
        }
        return hasRemote && hasClient
    }

    /**
     * `remote <host> [port] [proto]` satirini okur. Birden fazla `remote` varsa
     * ilki kullanilir (OpenVPN de varsayilan olarak ilkini dener).
     */
    private fun parseOpenVpnRemote(config: String): Triple<String, Int, String>? {
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

    // ------------------------------------------------------------- WireGuard

    private fun wireGuardProfile(context: Context): VpnProfile {
        val file = wireGuardFile(context)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank() && isValidWireGuard(text)) {
                val name = prefs(context).getString(KEY_WIREGUARD_NAME, null)
                    ?: context.getString(R.string.profile_wireguard_name)
                return buildWireGuardProfile(context, text, name, isCustom = true)
            }
        }
        return VpnProfile(
            displayName = context.getString(R.string.profile_wireguard_name),
            config = "",
            host = context.getString(R.string.profile_wireguard_missing),
            port = 0,
            protocol = "wireguard",
            isCustom = false,
            engine = VpnEngine.WIREGUARD
        )
    }

    private fun buildWireGuardProfile(
        context: Context,
        config: String,
        displayName: String,
        isCustom: Boolean
    ): VpnProfile {
        val endpoint = parseWireGuardEndpoint(config)
        return VpnProfile(
            displayName = displayName,
            config = config,
            host = endpoint?.first ?: context.getString(R.string.profile_unknown_host),
            port = endpoint?.second ?: DEFAULT_WIREGUARD_PORT,
            protocol = "wireguard",
            isCustom = isCustom,
            engine = VpnEngine.WIREGUARD
        )
    }

    /** `[Interface]` + `PrivateKey` + `[Peer]` + `PublicKey` + `Endpoint` zorunlu. */
    private fun isValidWireGuard(config: String): Boolean {
        val normalised = config.replace("\r\n", "\n")
        return normalised.contains("[Interface]") &&
            normalised.contains("PrivateKey", ignoreCase = true) &&
            normalised.contains("[Peer]") &&
            normalised.contains("PublicKey", ignoreCase = true) &&
            normalised.contains("Endpoint", ignoreCase = true)
    }

    private fun parseWireGuardEndpoint(config: String): Pair<String, Int>? {
        config.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("Endpoint", ignoreCase = true)) return@forEach
            val value = line.substringAfter('=', "").trim()
            if (value.isEmpty()) return@forEach
            val separator = value.lastIndexOf(':')
            if (separator <= 0) return@forEach
            val host = value.substring(0, separator)
            val port = value.substring(separator + 1).toIntOrNull() ?: DEFAULT_WIREGUARD_PORT
            return host to port
        }
        return null
    }

    /** Dosya `[Interface]` blogu veya WireGuard anahtarlari iceriyor mu? */
    private fun looksLikeWireGuard(config: String): Boolean {
        val normalised = config.replace("\r\n", "\n")
        return normalised.contains("[Interface]") ||
            (normalised.contains("PrivateKey", ignoreCase = true) &&
                normalised.contains("[Peer]"))
    }

    // ---------------------------------------------------------------- helpers

    private fun readText(context: Context, uri: Uri): String {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_CONFIG_BYTES) { "Config too large" }
            bytes.toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("Unreadable config")

        require(text.isNotBlank()) { "Empty config" }
        return text
    }

    private fun openVpnFile(context: Context) = java.io.File(context.filesDir, "profiles/custom.ovpn")

    private fun wireGuardFile(context: Context) = java.io.File(context.filesDir, "profiles/custom.conf")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()
}
