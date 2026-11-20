package com.mdc.vpn.data

/**
 * Eski (OpenVPN 2.x donemi) sunucular icin yapilandirma uyumlastirmasi.
 *
 * OVPN3 cekirdegi bazi eski direktifleri artik kabul etmiyor:
 *  - `BF-CBC`, `CAST5`, `DES`, `RC2` gibi zayif sifreleyiciler derlenmemis,
 *  - `ns-cert-type` OpenVPN 2.4'te kaldirildi,
 *  - `tls-remote` yerine `verify-x509-name` geldi,
 *  - LZO/LZ4 sikistirmasi cekirdekte yok,
 *  - TLS 1.0/1.1 sunucu sertifikalari icin `tls-cert-profile legacy` gerekiyor.
 *
 * Donusum yalnizca bu eski direktifleri modern karsiliklarina cevirir; modern
 * bir profilde degisiklik yapmaz.
 */
object OpenVpnCompat {

    /** Uyarlanmis yapilandirma ve yapilan degisikliklerin listesi. */
    data class Result(val config: String, val changes: List<String>) {
        val modified: Boolean get() = changes.isNotEmpty()
    }

    private const val FALLBACK_CIPHER = "AES-256-CBC"

    /** OVPN3'te derli olmayan / artik desteklenmeyen sifreleyiciler. */
    private val LEGACY_CIPHERS = setOf(
        "bf-cbc",
        "cast5-cbc",
        "des-cbc",
        "des-ede3-cbc",
        "rc2-cbc",
        "rc2-40-cbc",
        "rc2-64-cbc",
        "idea-cbc",
        "seed-cbc",
        "none"
    )

    private val WHITESPACE = Regex("\\s+")

    /** Eski direktif iceren bir profil mi? (ayarlar ekranindaki bilgi icin) */
    fun looksLegacy(config: String): Boolean =
        config.lineSequence().any { line ->
            val tokens = line.trim().split(WHITESPACE)
            if (tokens.isEmpty()) return@any false
            when (tokens[0].lowercase()) {
                "ns-cert-type", "tls-remote", "comp-lzo", "compress" -> true
                "cipher" -> tokens.getOrNull(1)?.lowercase() in LEGACY_CIPHERS
                else -> false
            }
        }

    fun applyLegacy(config: String): Result {
        if (config.isBlank()) return Result(config, emptyList())

        val changes = mutableListOf<String>()
        val output = mutableListOf<String>()
        var legacyTls = false
        var hasCertProfile = false
        var hasCompressionRule = false

        val normalised = config.replace("\r\n", "\n").removePrefix("\uFEFF")
        for (rawLine in normalised.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                output += line
                continue
            }

            val tokens = trimmed.split(WHITESPACE)
            when (tokens[0].lowercase()) {
                "cipher" -> {
                    val value = tokens.getOrNull(1)?.lowercase()
                    if (value != null && value in LEGACY_CIPHERS) {
                        output += "cipher $FALLBACK_CIPHER"
                        changes += "cipher ${tokens[1]} → $FALLBACK_CIPHER"
                    } else {
                        output += line
                    }
                }

                "ns-cert-type" -> {
                    val value = tokens.getOrNull(1)?.lowercase()
                    if (value == "server" || value == "client") {
                        output += "remote-cert-tls $value"
                        changes += "ns-cert-type $value → remote-cert-tls $value"
                    } else {
                        changes += "ns-cert-type kaldırıldı"
                    }
                }

                "tls-remote" -> {
                    val name = tokens.getOrNull(1)
                    if (name.isNullOrBlank()) {
                        changes += "tls-remote kaldırıldı"
                    } else {
                        output += "verify-x509-name $name name"
                        changes += "tls-remote $name → verify-x509-name"
                    }
                }

                "comp-lzo", "compress" -> {
                    if (!hasCompressionRule) {
                        output += "allow-compression no"
                        hasCompressionRule = true
                    }
                    changes += "${tokens[0]} kaldırıldı → allow-compression no"
                }

                "allow-compression" -> {
                    hasCompressionRule = true
                    output += line
                }

                "tls-cert-profile" -> {
                    hasCertProfile = true
                    output += line
                }

                "tls-version-min" -> {
                    val version = tokens.getOrNull(1)
                    if (version == "1.0" || version == "1.1") legacyTls = true
                    output += line
                }

                else -> output += line
            }
        }

        // TLS 1.0/1.1 isteyen sunucular genelde SHA-1 imzali eski sertifika
        // kullanir; OVPN3 bunlari ancak legacy profiliyle kabul eder.
        if (legacyTls && !hasCertProfile) {
            output += "tls-cert-profile legacy"
            changes += "tls-version-min ≤ 1.1 → tls-cert-profile legacy eklendi"
        }

        if (changes.isEmpty()) return Result(config, emptyList())
        return Result(output.joinToString("\n").trimEnd() + "\n", changes)
    }
}
