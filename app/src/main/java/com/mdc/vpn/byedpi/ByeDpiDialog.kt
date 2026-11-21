package com.mdc.vpn.byedpi

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mdc.vpn.R

/** ByeDPI stratejisi secme dialogu (ana ekran ve ayarlar ortak kullanir). */
object ByeDpiDialog {

    fun show(context: Context, onChanged: () -> Unit) {
        val presets = ByeDpiSettings.presets
        val current = presets.indexOfFirst { it.id == ByeDpiSettings.presetId(context) }
        val labels = presets.map { context.getString(it.titleRes) }.toTypedArray()

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.byedpi_preset_title)
            .setSingleChoiceItems(labels, current.coerceAtLeast(0)) { dialog, which ->
                val preset = presets[which]
                if (preset.id == ByeDpiSettings.CUSTOM) {
                    dialog.dismiss()
                    showCustomEditor(context, onChanged)
                } else {
                    ByeDpiSettings.setPreset(context, preset.id)
                    dialog.dismiss()
                    onChanged()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomEditor(context: Context, onChanged: () -> Unit) {
        val density = context.resources.displayMetrics.density
        val padding = (22 * density).toInt()
        val input = EditText(context).apply {
            hint = context.getString(R.string.byedpi_custom_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(ByeDpiSettings.customArgs(context))
        }
        val container = FrameLayout(context).apply {
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.byedpi_custom_title)
            .setMessage(R.string.byedpi_custom_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ByeDpiSettings.setCustomArgs(context, input.text.toString())
                ByeDpiSettings.setPreset(context, ByeDpiSettings.CUSTOM)
                onChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
