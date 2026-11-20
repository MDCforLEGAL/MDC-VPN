package com.mdc.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mdc.vpn.MainActivity
import com.mdc.vpn.R
import com.tim.notification.VpnServiceNotification

/**
 * Kalici (foreground) VPN bildirimi.
 *
 * Kutuphane bu sinifi `Class.forName(...)` + `(Service, NotificationManager)`
 * imzali kurucu ile olusturur; bu yuzden sinif adi
 * [VpnConnectionManager] icinde metin olarak verilir ve ProGuard kurallarinda
 * korunur.
 */
class MdcVpnNotification(
    private val service: Service,
    private val notificationManager: NotificationManager
) : VpnServiceNotification {

    private val startedAt: Long = System.currentTimeMillis()

    override fun withTimer(): Boolean = false

    override fun start() {
        createChannelIfNeeded()
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            createNotification(""),
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    override fun stop() {
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    override fun createNotification(description: String): Notification {
        val openApp = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            service,
            REQUEST_OPEN_APP,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            service,
            REQUEST_STOP,
            Intent(service, VpnControlReceiver::class.java).setAction(VpnControlReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = description.ifBlank { service.getString(R.string.notification_text) }

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setColor(ContextCompat.getColor(service, R.color.accent))
            .setContentTitle(service.getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, service.getString(R.string.notification_action_disconnect), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = service.getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "mdc_vpn_status"
        const val NOTIFICATION_ID = 1001
        const val REQUEST_OPEN_APP = 10
        const val REQUEST_STOP = 11

        /**
         * `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` sabiti API 34 ile
         * geldi; ancak deger 29+ surumlerde de manifestteki ayni bit ile
         * eslestigi icin sabit olarak kullanilabilir.
         */
        const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 0x40000000
    }
}
