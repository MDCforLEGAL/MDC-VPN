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
