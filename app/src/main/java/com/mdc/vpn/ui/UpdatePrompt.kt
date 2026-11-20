package com.mdc.vpn.ui

import android.app.ProgressDialog
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdc.vpn.R
import com.mdc.vpn.update.ApkInstaller
import com.mdc.vpn.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Uygulama ici guncelleme akisi: GitHub Releases kontrolu, onay diyalogu,
 * indirme ve sistem kurucusunu acma. Ana ekran ve ayarlar ekrani ayni akisi
 * kullanir; [manual] false iken yalnizca guncelleme varsa kullaniciya sorulur.
 */
object UpdatePrompt {

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
                runCatching { ApkInstaller.install(activity, file) }
                    .onFailure { error ->
                        toast(
                            activity,
                            activity.getString(
                                R.string.update_download_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
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
