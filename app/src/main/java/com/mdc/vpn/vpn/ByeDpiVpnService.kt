package com.mdc.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mdc.vpn.MainActivity
import com.mdc.vpn.R
import com.mdc.vpn.byedpi.ByeDpiRuntime
import com.mdc.vpn.byedpi.ByeDpiSettings
import hev.htproxy.TProxyService
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ByeDPI (ciadpi) + hev-socks5-tunnel tabanli DPI atlatma servisi.
 *
 * Akis:
 *  1. `ciadpi` yerel bir SOCKS5 dinleyicisi (127.0.0.1:1080) baslatilir.
 *  2. [VpnService.Builder] ile tun acilir; uygulamanin kendisi tunun disinda
 *     birakilir, boylece ciadpi'ye dogrudan erisilebilir.
 *  3. hev-socks5-tunnel tun dosya tanimlayicisini SOCKS5'e baglar.
 *
 * Bu bir VPN degildir: trafik sifrelenmez, yalnizca DPI filtrelerini
 * atlatmak icin TCP parcalama/desync uygulanir.
 */
class ByeDpiVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var proxy: Process? = null
    private var outputThread: Thread? = null

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        val args = intent?.getStringArrayListExtra(EXTRA_ARGS) ?: ByeDpiSettings.args(this)
        startForegroundNotification(getString(R.string.byedpi_starting))
        startTunnel(args)
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun startTunnel(args: List<String>) {
        if (ByeDpiRuntime.running) {
            Log.i(TAG, "ByeDPI already running")
            return
        }
        stopping = false
        ByeDpiRuntime.clearError()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fail(getString(R.string.byedpi_unsupported))
            return
        }

        val binary = File(applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!binary.isFile) {
            fail(getString(R.string.byedpi_error_binary))
            return
        }

        try {
            val config = writeHevConfig()

            val builder = Builder()
                .setSession(getString(R.string.byedpi_session))
                .addAddress("10.10.10.10", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            runCatching { builder.addDisallowedApplication(packageName) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

            val fd = builder.establish()
                ?: run {
                    fail(getString(R.string.error_vpn_permission_denied))
                    return
                }
            tun = fd

            val command = mutableListOf(binary.absolutePath, "-i", "127.0.0.1", "-p", PORT.toString())
            command.addAll(args)
            Log.i(TAG, "Starting ciadpi: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            proxy = process
            startOutputReader(process)

            if (!waitForPort(PORT, 3_000)) {
                fail(getString(R.string.byedpi_error_proxy))
                return
            }

            val started = TProxyService.TProxyStartService(config.absolutePath, fd.fd)
            if (!started) {
                fail(getString(R.string.byedpi_error_tunnel))
                return
            }

            ByeDpiRuntime.started()
            updateNotification(getString(R.string.byedpi_notification_text))
            Log.i(TAG, "ByeDPI tunnel is up")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start ByeDPI", error)
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun writeHevConfig(): File {
        val config = """
            |misc:
            |  task-stack-size: 81920
            |socks5:
            |  mtu: 8500
            |  address: 127.0.0.1
            |  port: $PORT
            |  udp: udp
        """.trimMargin()
        val file = File(cacheDir, "hev-socks5.yml")
        file.writeText(config)
        return file
    }

    private fun startOutputReader(process: Process) {
        outputThread = Thread({
            try {
                BufferedReader(process.inputStream.reader()).useLines { lines ->
                    lines.forEach { line -> if (line.isNotBlank()) Log.i(TAG, "ciadpi: $line") }
                }
            } catch (ignored: IOException) {
                // Surec kapatildiginda akis kapanir.
            }
        }, "ciadpi-output").apply { isDaemon = true }
        outputThread?.start()

        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (!stopping && ByeDpiRuntime.running) {
                Log.w(TAG, "ciadpi exited with $code")
                fail(getString(R.string.byedpi_error_proxy_stopped))
            }
        }, "ciadpi-watch").apply { isDaemon = true }.start()
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (proxy?.isAlive != true) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    return true
                }
            } catch (ignored: IOException) {
                Thread.sleep(120)
            }
        }
        return false
    }

    // ----------------------------------------------------------------- stop

    private fun stopTunnel() {
        stopping = true
        try {
            runCatching { TProxyService.TProxyStopService() }
        } catch (error: Throwable) {
            Log.w(TAG, "TProxyStopService failed", error)
        }
        proxy?.destroy()
        proxy = null
        outputThread = null
        tun?.close()
        tun = null
        ByeDpiRuntime.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, "ByeDPI error: $message")
        ByeDpiRuntime.failed(message)
        stopTunnel()
    }

    // --------------------------------------------------------- notification

    private fun startForegroundNotification(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ByeDpiVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_action_disconnect), stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val TAG = "ByeDpiVpnService"
        private const val PORT = 1080
        private const val CHANNEL_ID = "mdc_byedpi_status"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.mdc.vpn.byedpi.action.START"
        private const val ACTION_STOP = "com.mdc.vpn.byedpi.action.STOP"
        private const val EXTRA_ARGS = "com.mdc.vpn.byedpi.extra.ARGS"

        /** Servisi baslatir; tun kurulana kadar durum CONNECTING kalir. */
        fun start(context: Context, args: List<String>) {
            val intent = Intent(context, ByeDpiVpnService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_ARGS, ArrayList(args))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ByeDpiVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
