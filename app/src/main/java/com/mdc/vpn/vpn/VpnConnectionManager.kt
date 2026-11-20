package com.mdc.vpn.vpn

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.IBinder
import com.mdc.vpn.data.VpnEngine
import com.mdc.vpn.data.VpnProfile
import com.tim.basevpn.IVPNService
import com.tim.basevpn.connection.ConnectionListener
import com.tim.basevpn.state.ConnectionState
import com.tim.openvpn.configuration.OpenVPNConfig
import com.tim.openvpn.service.OpenVPNService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/** Kullaniciya gosterilen baglanti durumlari. */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/** Anlik baglanti istatistikleri. */
data class VpnStats(
    val connectedSeconds: Long = 0L,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L
)

/**
 * VPN oturumunun tek yonetim noktasi.
 *
 * Tünelin kendisi `com.tim.openvpn.service.OpenVPNService` tarafindan ayri bir
 * surecte (`:openvpn`) yonetilir; bu sinif uygulama surecinde calisir ve
 *
 *  - servisi baslatir / durdurur,
 *  - servise baglanip AIDL durum geri cagrilarini dinler,
 *  - sistemde gercek bir VPN tasiyicisi (tun) olup olmadigini dogrular,
 *  - baglanti suresi ile indirme/yukleme hizini hesaplar.
 */
object VpnConnectionManager {

    private const val PREFS = "mdc_vpn_session"
    private const val KEY_CONNECTED_AT = "connected_at"

    /** Elle durdurma istegi olmadan bu kadar sure baglanilamazsa vazgecilir. */
    private const val CONNECT_TIMEOUT_MS = 45_000L

    /** WireGuard tunel adi (kutuphane adlarinda en fazla 15 karakter ister). */
    private const val WIREGUARD_TUNNEL_NAME = "mdcvpn"

    /** `TrafficStats` bu cihazda desteklenmiyorsa -1 doner. */
    private const val UNSUPPORTED_TRAFFIC = -1L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(VpnStats())
    val stats: StateFlow<VpnStats> = _stats.asStateFlow()

    /** Son baglanti hatasi (varsa) — arayuzde kullaniciya gosterilir. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var activeEngine: VpnEngine = VpnEngine.OPENVPN
    private var wireGuardBackend: Backend? = null

    /** WireGuard durum geri cagrilarini akisin durum modeline cevirir. */
    private val wireGuardTunnel = object : Tunnel {
        override fun getName(): String = WIREGUARD_TUNNEL_NAME

        override fun onStateChange(state: Tunnel.State) {
            scope.launch {
                when (state) {
                    Tunnel.State.UP -> applyState(VpnState.CONNECTED)
                    Tunnel.State.DOWN -> applyState(VpnState.DISCONNECTED)
                    else -> Unit
                }
            }
        }
    }

    private var app: Application? = null
    private var bound = false
    private var remote: IVPNService? = null

    private var sampler: Job? = null
    private var connectedAtMillis = 0L
    private var connectingSinceMillis = 0L
    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var inactiveTicks = 0

    private val stateListener = object : ConnectionListener() {
        override fun stateChanged(state: ConnectionState?) {
            val target = state?.let(::mapState) ?: return
            scope.launch { applyState(target) }
        }

        override fun trafficUpdate(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {
            // OVPN3 cekirdegi bu geri cagriyi kullanmiyor; hizlar TrafficStats'tan
            // hesaplandigi icin burada yapilacak bir sey yok.
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val vpnService = service?.let { IVPNService.Stub.asInterface(it) } ?: return
            remote = vpnService
            runCatching { vpnService.registerCallback(stateListener) }
            refreshFromSystem()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            bound = false
        }
    }

    fun init(application: Application) {
        if (app != null) return
        app = application

        // Surec oldurulup yeniden olustuysa acik tüneli geri yukle.
        val savedAt = prefs()?.getLong(KEY_CONNECTED_AT, 0L) ?: 0L
        if (savedAt > 0L && isTunnelUp(application)) {
            connectedAtMillis = savedAt
            _state.value = VpnState.CONNECTED
            startSampler()
        } else {
            clearPersistedSession()
        }
    }

    /** Arayuzde gosterilen son hatayi temizler. */
    fun clearError() {
        _lastError.value = null
    }

    /** Aktivite on plandayken servise baglanir (durum geri cagrilari icin). */
    fun bind() {
        val context = app ?: return
        if (bound) return
        val intent = Intent(context, OpenVPNService::class.java)
        bound = runCatching { context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
    }

    /** Aktivite arka plana gectiginde baglantiyi birakir; tünel etkilenmez. */
    fun unbind() {
        val context = app ?: return
        if (!bound) return
        runCatching { remote?.unregisterCallback(stateListener) }
        runCatching { context.unbindService(serviceConnection) }
        remote = null
        bound = false
    }

    /** Uygulama tekrar one geldiginde gercek tünel durumunu sistemden okur. */
    fun refreshFromSystem() {
        val context = app ?: return
        if (!isTunnelUp(context)) return
        when (_state.value) {
            VpnState.CONNECTING, VpnState.CONNECTED -> applyState(VpnState.CONNECTED)
            VpnState.DISCONNECTED -> {
                // Servis baska bir surecten calisiyor olabilir (ornegin aktivite
                // yeniden olusturuldu); acik tüneli sahiplen.
                if (connectedAtMillis == 0L) connectedAtMillis = System.currentTimeMillis()
                applyState(VpnState.CONNECTED)
            }
            VpnState.DISCONNECTING -> Unit
        }
    }

    /** Profili kullanarak tüneli baslatir (protokol profile gore secilir). */
    fun connect(profile: VpnProfile) {
        when (profile.engine) {
            VpnEngine.WIREGUARD -> connectWireGuard(profile)
            VpnEngine.OPENVPN -> connectOpenVpn(profile)
            // Tor tuneli Orbot uygulamasi tarafindan kurulur.
            VpnEngine.TOR -> _lastError.value =
                app?.getString(com.mdc.vpn.R.string.error_tor_not_ready)
                    ?: "Tor is provided by Orbot"
        }
    }

    /** OpenVPN 3 (OVPN3) cekirdegi ile baglanir. */
    private fun connectOpenVpn(profile: VpnProfile) {
        val context = app ?: return
        if (_state.value == VpnState.CONNECTING || _state.value == VpnState.CONNECTED) return

        activeEngine = VpnEngine.OPENVPN
        _lastError.value = null

        val config = OpenVPNConfig().copy(
            name = profile.displayName,
            host = profile.host,
            port = profile.port,
            type = profile.protocol,
            configuration = profile.config
        )

        applyState(VpnState.CONNECTING)
        startSampler()

        runCatching {
            OpenVPNService.startService(
                context,
                config,
                MdcVpnNotification::class.java.name,
                emptyArray()
            )
        }.onFailure {
            applyState(VpnState.DISCONNECTED)
        }

        // Servis ayaga kalktiktan sonra baglanip durum geri cagrilarini dinle.
        scope.launch {
            delay(700)
            bind()
            refreshFromSystem()
        }
    }

    /** WireGuard cekirdegi ile baglanir. `setState` bloklayan bir cagridir. */
    private fun connectWireGuard(profile: VpnProfile) {
        val context = app ?: return
        if (_state.value == VpnState.CONNECTING || _state.value == VpnState.CONNECTED) return
        if (!profile.isConfigured) {
            _lastError.value = "Empty WireGuard configuration"
            return
        }

        activeEngine = VpnEngine.WIREGUARD
        _lastError.value = null
        applyState(VpnState.CONNECTING)
        startSampler()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val config = Config.parse(BufferedReader(StringReader(profile.config)))
                val backend = wireGuardBackend ?: GoBackend(context).also { wireGuardBackend = it }
                backend.setState(wireGuardTunnel, Tunnel.State.UP, config)
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _lastError.value = error.message ?: error.javaClass.simpleName
                    applyState(VpnState.DISCONNECTED)
                }
            }
        }
    }

    private fun stopWireGuard() {
        val backend = wireGuardBackend
        applyState(VpnState.DISCONNECTING)
        scope.launch(Dispatchers.IO) {
            runCatching { backend?.setState(wireGuardTunnel, Tunnel.State.DOWN, null) }
            withContext(Dispatchers.Main) { applyState(VpnState.DISCONNECTED) }
        }
    }

    /** Tüneli kapatir. */
    fun disconnect() {
        when (activeEngine) {
            VpnEngine.WIREGUARD -> stopWireGuard()
            VpnEngine.OPENVPN -> stopTunnel(app)
            VpnEngine.TOR -> Unit
        }
    }

    /** Bildirimdeki "Baglantiyi Kes" aksiyonundan cagrilir. */
    fun onStopRequested(context: Context) {
        stopTunnel(context)
    }

    private fun stopTunnel(context: Context?) {
        val target = context ?: app ?: return
        if (_state.value == VpnState.DISCONNECTED && !isTunnelUp(target)) return

        applyState(VpnState.DISCONNECTING)
        runCatching { remote?.stopVPN() }
        runCatching { OpenVPNService.stopService(target) }

        // Servis kapanisi bazen gecikmeli bildirilir; UI'i kilitlemeyelim.
        scope.launch {
            delay(8_000)
            if (_state.value == VpnState.DISCONNECTING) applyState(VpnState.DISCONNECTED)
        }
    }

    private fun applyState(next: VpnState) {
        if (_state.value == next) return
        _state.value = next
        when (next) {
            VpnState.CONNECTING -> {
                connectingSinceMillis = System.currentTimeMillis()
                inactiveTicks = 0
            }
            VpnState.CONNECTED -> {
                connectedAtMillis = System.currentTimeMillis()
                persistSession(connectedAtMillis)
                inactiveTicks = 0
                lastRxBytes = -1L
                lastTxBytes = -1L
            }
            VpnState.DISCONNECTED -> {
                connectedAtMillis = 0L
                connectingSinceMillis = 0L
                inactiveTicks = 0
                _stats.value = VpnStats()
                clearPersistedSession()
                stopSampler()
            }
            VpnState.DISCONNECTING -> Unit
        }
    }

    private fun mapState(state: ConnectionState): VpnState = when (state) {
        ConnectionState.CONNECTED -> VpnState.CONNECTED
        ConnectionState.CONNECTING, ConnectionState.READYFORCONNECT -> VpnState.CONNECTING
        ConnectionState.DISCONNECTING -> VpnState.DISCONNECTING
        ConnectionState.DISCONNECTED,
        ConnectionState.PERMISSION_NOT_GRANTED,
        ConnectionState.IDLE -> VpnState.DISCONNECTED
    }

    private fun startSampler() {
        if (sampler?.isActive == true) return
        sampler = scope.launch {
            while (isActive) {
                delay(1_000)
                when (_state.value) {
                    VpnState.CONNECTING -> {
                        if (isTunnelUp(app)) {
                            applyState(VpnState.CONNECTED)
                        } else if (System.currentTimeMillis() - connectingSinceMillis > CONNECT_TIMEOUT_MS) {
                            // Sunucuya ulasilamadi: servisi kapat ve kullaniciya don.
                            app?.let { context -> runCatching { OpenVPNService.stopService(context) } }
                            applyState(VpnState.DISCONNECTED)
                            return@launch
                        }
                    }
                    VpnState.CONNECTED -> {
                        if (isTunnelUp(app)) {
                            inactiveTicks = 0
                            updateStats()
                        } else if (++inactiveTicks >= 2) {
                            applyState(VpnState.DISCONNECTED)
                            return@launch
                        }
                    }
                    VpnState.DISCONNECTING -> {
                        if (!isTunnelUp(app)) {
                            applyState(VpnState.DISCONNECTED)
                            return@launch
                        }
                    }
                    VpnState.DISCONNECTED -> return@launch
                }
            }
        }
    }

    private fun stopSampler() {
        sampler?.cancel()
        sampler = null
    }

    /**
     * Hiz gostergesi cihaz genelindeki toplam trafik farkindan hesaplanir. VPN
     * acikken tum trafik tun arayuzunden gectigi icin bu deger tünel trafigini
     * yansitir; kapaliyken zaten guncellenmez.
     */
    private fun updateStats() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        var down = 0L
        var up = 0L
        if (rx != UNSUPPORTED_TRAFFIC && lastRxBytes >= 0 && rx >= lastRxBytes) {
            down = rx - lastRxBytes
        }
        if (tx != UNSUPPORTED_TRAFFIC && lastTxBytes >= 0 && tx >= lastTxBytes) {
            up = tx - lastTxBytes
        }
        if (rx != UNSUPPORTED_TRAFFIC) lastRxBytes = rx
        if (tx != UNSUPPORTED_TRAFFIC) lastTxBytes = tx

        val elapsed = if (connectedAtMillis > 0L) {
            (System.currentTimeMillis() - connectedAtMillis) / 1000
        } else {
            0L
        }
        _stats.value = VpnStats(
            connectedSeconds = elapsed,
            downloadBytesPerSecond = down,
            uploadBytesPerSecond = up
        )
    }

    /** Cihazda gercek bir VPN tüneli (tun) aktif mi? */
    private fun isTunnelUp(context: Context?): Boolean {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persistSession(startedAt: Long) {
        prefs()?.edit()?.putLong(KEY_CONNECTED_AT, startedAt)?.apply()
    }

    private fun clearPersistedSession() {
        prefs()?.edit()?.remove(KEY_CONNECTED_AT)?.apply()
    }
}
