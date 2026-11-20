package com.mdc.vpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.mdc.vpn.data.ProfileStore
import com.mdc.vpn.data.VpnEngine
import com.mdc.vpn.databinding.ActivitySettingsBinding
import com.mdc.vpn.settings.AppSettings
import com.mdc.vpn.ui.UpdatePrompt
import com.mdc.vpn.util.Links
import com.mdc.vpn.vpn.VpnConnectionManager
import com.mdc.vpn.vpn.VpnState

/**
 * Uygulama ayarlari: otomatik baglanma, protokol secimi, guncelleme denetimi,
 * dil, profil sifirlama ve dis baglantilar.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.autoConnectSwitch.isChecked = AppSettings.autoConnect(this)
        binding.autoConnectSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setAutoConnect(this, checked)
        }

        binding.updateSwitch.isChecked = AppSettings.checkUpdatesOnLaunch(this)
        binding.updateSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setCheckUpdatesOnLaunch(this, checked)
        }

        binding.resetProfileRow.setOnClickListener { resetProfile() }
        binding.checkNowRow.setOnClickListener { UpdatePrompt.check(this, manual = true) }
        binding.browseRow.setOnClickListener { openPersonalLinks() }

        binding.engineToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val engine = when (checkedId) {
                R.id.settingsOpenVpnButton -> VpnEngine.OPENVPN
                R.id.settingsWireGuardButton -> VpnEngine.WIREGUARD
                else -> return@addOnButtonCheckedListener
            }
            if (ProfileStore.engine(this) == engine) return@addOnButtonCheckedListener
            if (VpnConnectionManager.state.value != VpnState.DISCONNECTED) {
                VpnConnectionManager.disconnect()
            }
            ProfileStore.setEngine(this, engine)
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val tag = when (checkedId) {
                R.id.langSystemButton -> AppSettings.LANGUAGE_SYSTEM
                R.id.langTurkishButton -> AppSettings.LANGUAGE_TURKISH
                R.id.langEnglishButton -> AppSettings.LANGUAGE_ENGLISH
                else -> return@addOnButtonCheckedListener
            }
            if (AppSettings.language(this) != tag) AppSettings.setLanguage(this, tag)
        }
    }

    override fun onResume() {
        super.onResume()
        syncSelections()
        renderInfo()
    }

    private fun syncSelections() {
        val engineId = if (ProfileStore.engine(this) == VpnEngine.WIREGUARD) {
            R.id.settingsWireGuardButton
        } else {
            R.id.settingsOpenVpnButton
        }
        if (binding.engineToggle.checkedButtonId != engineId) {
            binding.engineToggle.check(engineId)
        }

        val languageId = when (AppSettings.language(this)) {
            AppSettings.LANGUAGE_TURKISH -> R.id.langTurkishButton
            AppSettings.LANGUAGE_ENGLISH -> R.id.langEnglishButton
            else -> R.id.langSystemButton
        }
        if (binding.languageToggle.checkedButtonId != languageId) {
            binding.languageToggle.check(languageId)
        }
    }

    private fun renderInfo() {
        binding.versionValue.text = getString(
            R.string.settings_version_value,
            UpdatePrompt.versionName(this),
            UpdatePrompt.versionCode(this)
        )
        binding.packageValue.text = packageName
    }

    private fun resetProfile() {
        if (VpnConnectionManager.state.value != VpnState.DISCONNECTED) {
            VpnConnectionManager.disconnect()
        }
        ProfileStore.resetCustom(this)
        syncSelections()
        showMessage(getString(R.string.settings_reset_done))
    }

    private fun openPersonalLinks() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Links.PERSONAL_LINKS))
        runCatching { startActivity(intent) }
            .onFailure { showMessage(getString(R.string.settings_browse_desc)) }
    }

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_SHORT)
            .show()
    }
}
