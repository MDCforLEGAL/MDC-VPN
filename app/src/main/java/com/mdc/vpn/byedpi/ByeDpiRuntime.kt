package com.mdc.vpn.byedpi

/**
 * ByeDPI tünelinin servis içi durumunu uygulama sürecine taşır.
 *
 * Servis ve arayüz aynı süreçte çalıştığı için basit bir nesne yeterlidir;
 * [com.mdc.vpn.vpn.VpnConnectionManager] bağlanma sırasında buradaki hatayı
 * okuyup kullanıcıya gösterir.
 */
object ByeDpiRuntime {

    @Volatile
    var running: Boolean = false
        private set

    /** Son başlatma hatası; başarılı başlatmada temizlenir. */
    @Volatile
    var lastError: String? = null
        private set

    fun started() {
        lastError = null
        running = true
    }

    fun stopped() {
        running = false
    }

    fun failed(message: String) {
        running = false
        lastError = message
    }

    fun clearError() {
        lastError = null
    }
}
