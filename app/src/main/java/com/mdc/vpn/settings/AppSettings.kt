package com.mdc.vpn.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Kalici kullanici ayarlari.
 *
 * Dil tercihi Android 13 oncesinde de calisabilmesi icin kendimiz saklayip
 * uygulama acilisinda [AppCompatDelegate] uzerinden uyguluyoruz; Android 13+
 * ayni degeri sistemin "uygulama dili" ayariyla da paylasir.
 */
object AppSettings {

    private const val PREFS = "mdc_vpn_settings"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_CHECK_UPDATES = "check_updates"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_AD_BLOCK = "ad_block"
    private const val KEY_LEGACY_OPENVPN = "legacy_openvpn"

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_TURKISH = "tr"
    const val LANGUAGE_ENGLISH = "en"

    fun autoConnect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONNECT, false)

    fun setAutoConnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun checkUpdatesOnLaunch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_UPDATES, true)

    fun setCheckUpdatesOnLaunch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_UPDATES, enabled).apply()
    }

    /**
     * Eski OpenVPN sunuculari icin yapilandirma uyumlastirmasi acik mi?
     * (BF-CBC, ns-cert-type, tls-remote, LZO, TLS 1.0 gibi direktifler)
     */
    fun legacyOpenVpn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEGACY_OPENVPN, true)

    fun setLegacyOpenVpn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_OPENVPN, enabled).apply()
    }

    /** Uygulama ici tarayicide reklam/izleyici engelleme acik mi? */
    fun adBlock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AD_BLOCK, true)

    fun setAdBlock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AD_BLOCK, enabled).apply()
    }

    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM

    fun setLanguage(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
        applyLanguage(context)
    }

    /** Kayitli dil tercihini AppCompat'a uygular; [MdcVpnApp] acilista cagirir. */
    fun applyLanguage(context: Context) {
        val tag = language(context)
        val locales = if (tag == LANGUAGE_SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
