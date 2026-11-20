package com.mdc.vpn.tor

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Tor baglantisi icin Orbot entegrasyonu.
 *
 * Uygulamanin icine Tor istemcisi gomulu degil: gomulu bir Tor ikilisi ve
 * tun → SOCKS5 yonlendiricisi (tun2socks) APK'yi onlarca megabayt buyutur ve
 * native bagimlilik getirir. Bunun yerine cihazda calisan ucretsiz Orbot
 * uygulamasi kullanilir; Orbot'un VPN modu tum trafigi Tor uzerinden gecirir.
 */
object Orbot {

    private const val PACKAGE = "org.torproject.android"
    private const val ACTION_START = "org.torproject.android.intent.action.START"
    private const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
    private const val DOWNLOAD_PAGE = "https://orbot.app/"

    fun isInstalled(context: Context): Boolean {
        val manager = context.packageManager
        if (runCatching { manager.getLaunchIntentForPackage(PACKAGE) }.getOrNull() != null) return true
        return runCatching { manager.getPackageInfo(PACKAGE, 0) }.isSuccess
    }

    /**
     * Orbot'u baslatma istegini gonderir ve arayuzunu acar; kullanici orada
     * "Tor'u Baslat" ve VPN modunu etkinlestirir. Orbot arayuzu acilabildiyse
     * `true` doner.
     */
    fun start(context: Context): Boolean {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_START).apply {
                    setPackage(PACKAGE)
                    putExtra(EXTRA_PACKAGE_NAME, context.packageName)
                }
            )
        }

        val ui = runCatching { context.packageManager.getLaunchIntentForPackage(PACKAGE) }
            .getOrNull() ?: return false
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(ui) }.isSuccess
    }

    fun openDownloadPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
