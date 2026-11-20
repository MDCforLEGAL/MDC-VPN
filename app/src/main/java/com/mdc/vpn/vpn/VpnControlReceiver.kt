package com.mdc.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Bildirimdeki "Baglantiyi Kes" aksiyonunu karsilar.
 *
 * Broadcast alicisi uygulama surecinde calisir; servis ayri bir surecte
 * (`:openvpn`) oldugu icin durdurma istegi [VpnConnectionManager] uzerinden
 * servise iletilir.
 */
class VpnControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        VpnConnectionManager.onStopRequested(context.applicationContext)
    }

    companion object {
        const val ACTION_STOP = "com.mdc.vpn.action.STOP_VPN"
    }
}
