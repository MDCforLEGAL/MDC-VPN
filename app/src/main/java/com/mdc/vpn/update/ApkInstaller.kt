package com.mdc.vpn.update

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Indirilen APK'yi kurar.
 *
 * Kurulum `PackageInstaller` oturumu ile yapilir; boylece sonuc (basarili,
 * imza uyusmazligi, Play Protect engeli) ogrenilebilir. Oturum acilamazsa
 * sistem kurucusu (ACTION_VIEW) devreye girer.
 */
object ApkInstaller {

    /** Kurulum sonucunun gonderildigi yayin aksiyonu. */
    const val ACTION_INSTALL_STATUS = "com.mdc.vpn.action.INSTALL_STATUS"

    /** Eski Android surumlerinde APK'yi Indirilenler'e kaydetmek icin izin kodu. */
    const val PERMISSION_REQUEST_STORAGE = 4211

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val DOWNLOAD_FOLDER = "MDC-VPN"
    private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

    /** Android 8+ icin "bilinmeyen uygulama kurulumu" izni verilmis mi? */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Kullaniciyi bu uygulama icin kurulum izni ekranina goturur. */
    fun openPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Indirilen APK, yuklu uygulamayla ayni imzaya sahip mi?
     *
     * Farkli imza ile Android guncellemeyi reddeder ("paket çakıştı"); bu
     * durumda eski surumun kaldirilmasi gerekir. Bilgi okunamazsa `true`
     * donulur ve kurulum normal akisa birakilir.
     */
    fun signatureMatches(context: Context, apk: File): Boolean {
        val installed = installedSignatures(context)
        val archive = archiveSignatures(context, apk)
        if (installed.isEmpty() || archive.isEmpty()) return true
        return installed.any { it in archive }
    }

    /** APK'yi kurar; [onResult] hata mesajini dondurur (basarida `null`). */
    fun install(context: Context, apk: File, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        if (!commitSession(app, apk, onResult)) {
            runCatching { launchInstaller(app, apk) }
                .onFailure { error -> onResult(error.message ?: "installer-unavailable") }
        }
    }

    /**
     * APK'yi kaldirma isleminden sonra da kalacak sekilde Indirilenler
     * klasorune kaydeder. Kaydedilen dosya adini, basarisizsa `null` dondurur.
     */
    fun saveToDownloads(context: Context, apk: File): String? {
        val name = apk.name
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_APK)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER"
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                } ?: return null
                name
            } else {
                if (needsStoragePermission(context)) return null
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    DOWNLOAD_FOLDER
                )
                if (!directory.exists() && !directory.mkdirs()) return null
                val target = File(directory, name)
                apk.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                name
            }
        }.getOrNull()
    }

    /** Android 9 ve oncesinde genel klasore yazmak icin izin gerekiyor mu? */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    /** Sistemin kaldirma onay ekranini acar. */
    fun uninstall(context: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Play Protect ayarlarini acar. Uygulamalar Play Protect'i programatik
     * olarak kapatamaz; kullaniciya dogru ekrani gostermek en fazla bu kadar.
     */
    fun openPlayProtectSettings(context: Context) {
        val candidates = listOf(
            Intent("com.google.android.gms.security.settings.VerifyAppsSettingsActivity"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    fun clearCache(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    // ---------------------------------------------------------------- kurulum

    private fun commitSession(
        context: Context,
        apk: File,
        onResult: (String?) -> Unit
    ): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val receiver = InstallStatusReceiver(context, onResult)
        receiver.register(ACTION_INSTALL_STATUS)
        return try {
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("MDC-VPN.apk", 0L, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                    flags
                )
                session.commit(pending.intentSender)
            }
            true
        } catch (error: Throwable) {
            receiver.abandon()
            false
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ---------------------------------------------------------------- imzalar

    private fun installedSignatures(context: Context): List<String> {
        val manager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = manager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signing = info.signingInfo ?: return emptyList()
                val signers = if (signing.hasMultipleSigners()) {
                    signing.apkContentsSigners
                } else {
                    signing.signingCertificateHistory
                }
                signers.map { sha256(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val info = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { sha256(it.toByteArray()) } ?: emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun archiveSignatures(context: Context, apk: File): List<String> {
        val manager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = manager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: return emptyList()
                val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
                signers.map { sha256(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val info = manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                    ?: return emptyList()
                @Suppress("DEPRECATION")
                info.signatures?.map { sha256(it.toByteArray()) } ?: emptyList()
            }
        }.getOrElse { emptyList() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    // ------------------------------------------------------- kurulum sonucu

    private class InstallStatusReceiver(
        private val context: Context,
        private val onFinished: (String?) -> Unit
    ) : BroadcastReceiver() {

        private val handler = Handler(Looper.getMainLooper())
        private var settled = false

        fun register(action: String) {
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    this,
                    IntentFilter(action),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }
            handler.postDelayed({ abandon() }, INSTALL_TIMEOUT_MS)
        }

        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_SUCCESS -> finish(null)

                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        finish(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                        return
                    }
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { context.startActivity(confirm) }.isFailure) {
                        finish(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    }
                }

                else -> finish(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "status ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)}"
                )
            }
        }

        private fun finish(error: String?) {
            if (settled) return
            settled = true
            release()
            onFinished(error)
        }

        /** Sonuc gelmeden vazgecilirse dinleyiciyi kaldirir. */
        fun abandon() {
            if (settled) return
            settled = true
            release()
        }

        private fun release() {
            handler.removeCallbacksAndMessages(null)
            runCatching { context.unregisterReceiver(this) }
        }
    }
}
