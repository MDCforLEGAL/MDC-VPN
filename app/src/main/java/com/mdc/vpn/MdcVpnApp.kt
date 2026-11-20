package com.mdc.vpn

import android.app.Application
import com.mdc.vpn.vpn.VpnConnectionManager

/**
 * Uygulama giris noktasi.
 *
 * [VpnConnectionManager] uygulama sureci olustugu anda hazirlanir; boylece
 * kullanici uygulamayi yeniden actiginda acik bir tunel varsa durum dogru
 * sekilde geri yuklenir (ornegin bildirimden "Baglantiyi Kes" dendikten sonra).
 */
class MdcVpnApp : Application() {

    override fun onCreate() {
        super.onCreate()
        VpnConnectionManager.init(this)
    }
}
