package com.mdc.vpn.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdc.vpn.R
import com.mdc.vpn.update.ApkInstaller
import com.mdc.vpn.update.UpdateChecker
import kotlinx.coroutines.launch
import java.io.File

/**
 * Uygulama ici guncelleme akisi.
 *
 * 1. GitHub Releases kontrol edilir ve APK indirilir.
 * 2. Indirilen APK'nin imzasi yuklu uygulamayla karsilastirilir. Imzalar
 *    uyusmuyorsa Android "paket çakıştı" hatasi verir; bu durumda APK
 *    Indirilenler'e kaydedilir, eski surumun kaldirilmasi istenir.
 * 3. Aksi halde kurulum oturumu acilir; Play Protect gibi engeller kullaniciya
 *    anlasilir bir mesajla bildirilir.
 */
object UpdatePrompt {

    private var pendingApk: File? = null
    private var pendingInfo: UpdateChecker.ReleaseInfo? = null

    fun check(activity: AppCompatActivity, manual: Boolean) {
        activity.lifecycleScope.launch {
            when (val result = UpdateChecker.checkLatest(activity, versionCode(activity))) {
                is UpdateChecker.Result.Available -> show(activity, result.info)
                is UpdateChecker.Result.UpToDate -> if (manual) {
                    toast(
                        activity,
                        activity.getString(R.string.update_up_to_date, versionName(activity))
                    )
                }
                is UpdateChecker.Result.Failed -> if (manual) {
                    toast(
                        activity,
                        activity.getString(R.string.update_check_failed, result.reason)
                    )
                }
            }
        }
    }

    fun show(activity: AppCompatActivity, info: UpdateChecker.ReleaseInfo) {
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title))
            .setMessage(activity.getString(R.string.update_available_message, info.versionName))
            .setPositiveButton(activity.getString(R.string.action_update)) { _, _ ->
                download(activity, info)
            }
            .setNegativeButton(activity.getString(R.string.action_later), null)
            .show()
    }

    /** Play Protect uyarisi hakkinda bilgi ve ayarlarina kisayol. */
    fun showPlayProtectHelp(activity: AppCompatActivity) {
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.play_protect_title))
            .setMessage(activity.getString(R.string.play_protect_message))
            .setPositiveButton(activity.getString(R.string.play_protect_settings)) { _, _ ->
                ApkInstaller.openPlayProtectSettings(activity)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Eski Android surumlerinde APK'yi Indirilenler'e kaydetmek icin istenen
     * depolama izninin sonucunu tasiyan ekranlardan cagrilir.
     */
    fun onRequestPermissionsResult(activity: AppCompatActivity, requestCode: Int) {
        if (requestCode != ApkInstaller.PERMISSION_REQUEST_STORAGE) return
        prepareConflictDialog(activity)
    }

    @Suppress("DEPRECATION")
    private fun download(activity: AppCompatActivity, info: UpdateChecker.ReleaseInfo) {
        if (!ApkInstaller.canInstall(activity)) {
            toast(activity, activity.getString(R.string.update_permission_needed))
            ApkInstaller.openPermissionSettings(activity)
            return
        }

        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.update_downloading, 0))
            setCancelable(false)
            show()
        }

        activity.lifecycleScope.launch {
            runCatching {
                UpdateChecker.download(activity, info) { percent ->
                    activity.runOnUiThread {
                        if (progress.isShowing) {
                            progress.setMessage(
                                activity.getString(R.string.update_downloading, percent)
                            )
                        }
                    }
                }
            }.onSuccess { file ->
                if (progress.isShowing) progress.dismiss()
                installOrRemoveOld(activity, info, file)
            }.onFailure { error ->
                if (progress.isShowing) progress.dismiss()
                toast(
                    activity,
                    activity.getString(
                        R.string.update_download_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
    }

    private fun installOrRemoveOld(
        activity: AppCompatActivity,
        info: UpdateChecker.ReleaseInfo,
        file: File
    ) {
        if (ApkInstaller.signatureMatches(activity, file)) {
            install(activity, info, file)
        } else {
            pendingApk = file
            pendingInfo = info
            if (ApkInstaller.needsStoragePermission(activity)) {
                toast(activity, activity.getString(R.string.update_storage_permission))
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    ApkInstaller.PERMISSION_REQUEST_STORAGE
                )
            } else {
                prepareConflictDialog(activity)
            }
        }
    }

    private fun install(
        activity: AppCompatActivity,
        info: UpdateChecker.ReleaseInfo,
        file: File
    ) {
        ApkInstaller.install(activity, file) { error ->
            activity.runOnUiThread {
                if (activity.isFinishing || error == null) return@runOnUiThread
                val conflict = error.contains("INCOMPATIBLE", ignoreCase = true) ||
                    error.contains("ALREADY_EXISTS", ignoreCase = true)
                if (conflict) {
                    pendingApk = file
                    pendingInfo = info
                    prepareConflictDialog(activity)
                } else {
                    showInstallBlocked(activity, error)
                }
            }
        }
    }

    /** APK'yi kalici bir konuma kaydedip "eski surumu kaldir" diyalogunu acar. */
    private fun prepareConflictDialog(activity: AppCompatActivity) {
        val file = pendingApk ?: return
        val saved = ApkInstaller.saveToDownloads(activity, file)
        showConflict(activity, pendingInfo, saved)
    }

    private fun showConflict(
        activity: AppCompatActivity,
        info: UpdateChecker.ReleaseInfo?,
        savedName: String?
    ) {
        if (activity.isFinishing) return
        val fallbackUrl = info?.pageUrl?.takeIf { it.isNotBlank() }
            ?: info?.apkUrl
            ?: ""
        val message = if (savedName != null) {
            activity.getString(R.string.update_conflict_message, savedName)
        } else {
            activity.getString(R.string.update_conflict_message_link, fallbackUrl)
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_conflict_title))
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.update_conflict_remove)) { _, _ ->
                ApkInstaller.uninstall(activity)
            }
            .setNegativeButton(activity.getString(R.string.action_later), null)

        if (savedName == null && fallbackUrl.isNotEmpty()) {
            builder.setNeutralButton(activity.getString(R.string.update_conflict_copy)) { _, _ ->
                copyToClipboard(activity, fallbackUrl)
            }
        }
        builder.show()
    }

    private fun showInstallBlocked(activity: AppCompatActivity, error: String) {
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.install_blocked_title))
            .setMessage(activity.getString(R.string.install_blocked_message, error))
            .setPositiveButton(activity.getString(R.string.play_protect_settings)) { _, _ ->
                ApkInstaller.openPlayProtectSettings(activity)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(activity: AppCompatActivity, text: String) {
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        manager.setPrimaryClip(ClipData.newPlainText("MDC VPN", text))
        toast(activity, activity.getString(R.string.update_conflict_copied))
    }

    fun versionCode(activity: AppCompatActivity): Int = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(1)

    fun versionName(activity: AppCompatActivity): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }
            .getOrNull()
            ?: "1.0.0"

    private fun toast(activity: AppCompatActivity, text: String) {
        if (activity.isFinishing) return
        Toast.makeText(activity.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}
