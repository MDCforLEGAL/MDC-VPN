package com.mdc.vpn

import android.app.Application
import com.mdc.vpn.settings.AppSettings
import com.mdc.vpn.vpn.VpnConnectionManager

/**
 * Uygulama giris noktasi.
 *
 * [VpnConnectionManager] uygulama sureci olustugu anda hazirlanir; boylece
 * kullanici uygulamayi yeniden actiginda acik bir tunel varsa durum dogru
 * sekilde geri yuklenir (ornegin bildirimden "Baglantiyi Kes" dendikten sonra).
 *
 * Kayitli dil tercihi de (Turkce / English / Sistem) burada uygulanir.
 */
class MdcVpnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppSettings.applyLanguage(this)
        VpnConnectionManager.init(this)
    }
}
