package com.mdc.vpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mdc.vpn.browser.BrowserActivity
import com.mdc.vpn.data.ProfileStore
import com.mdc.vpn.data.VpnEngine
import com.mdc.vpn.data.VpnProfile
import com.mdc.vpn.databinding.ActivityMainBinding
import com.mdc.vpn.settings.AppSettings
import com.mdc.vpn.tor.Orbot
import com.mdc.vpn.ui.UpdatePrompt
import com.mdc.vpn.util.Formatters
import com.mdc.vpn.util.Links
import com.mdc.vpn.vpn.VpnConnectionManager
import com.mdc.vpn.vpn.VpnState
import com.mdc.vpn.vpn.VpnStats
import kotlinx.coroutines.launch

/**
 * Tek ekranli arayuz: durum halkasi, tek tusla baglan/kes, oturum istatistikleri,
 * protokol secimi ve yapilandirma ice aktarma.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var profile: VpnProfile? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startConnection()
            } else {
                showMessage(getString(R.string.error_vpn_permission_denied))
            }
        }

    private val profilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importProfile(uri)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Bildirim izni verilmese de tunel calisir; yalnizca durum bildirimi
            // gosterilemez.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profile = ProfileStore.load(this)
        applyProtocolSelection(ProfileStore.engine(this))
        renderProfile()

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.importButton.setOnClickListener { pickProfile() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.browseButton.setOnClickListener { openPersonalLinks() }
        binding.browserButton.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        binding.protocolToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val engine = when (checkedId) {
                R.id.openVpnButton -> VpnEngine.OPENVPN
                R.id.wireGuardButton -> VpnEngine.WIREGUARD
                R.id.torButton -> VpnEngine.TOR
                else -> return@addOnButtonCheckedListener
            }
            onProtocolSelected(engine)
        }

        observeVpnState()
        observeErrors()
        requestNotificationPermissionIfNeeded()
        maybeCheckUpdates()
        maybeAutoConnect()
    }

    override fun onStart() {
        super.onStart()
        VpnConnectionManager.bind()
    }

    override fun onResume() {
        super.onResume()
        VpnConnectionManager.refreshFromSystem()
        // Ayarlar ekranindan donuldugunde dil, profil veya protokol degismis olabilir.
        val engine = ProfileStore.engine(this)
        applyProtocolSelection(engine)
        profile = ProfileStore.load(this, engine)
        renderProfile()
    }

    override fun onStop() {
        VpnConnectionManager.unbind()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Guncelleme akisinda APK'yi Indirilenler'e kaydetmek icin istenen izin.
        UpdatePrompt.onRequestPermissionsResult(this, requestCode)
    }

    private fun observeVpnState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VpnConnectionManager.state.collect { renderState(it) } }
                launch { VpnConnectionManager.stats.collect { renderStats(it) } }
            }
        }
    }

    private fun observeErrors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnConnectionManager.lastError.collect { message ->
                    if (!message.isNullOrBlank()) {
                        showMessage(getString(R.string.error_connection_failed, message))
                        VpnConnectionManager.clearError()
                    }
                }
            }
        }
    }

    private fun renderState(state: VpnState) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val idle = ContextCompat.getColor(this, R.color.text_secondary)
        val title: String
        val subtitle: String
        val pill: String
        val accentColor: Int
        val connected: Boolean

        when (state) {
            VpnState.DISCONNECTED -> {
                title = getString(R.string.state_disconnected_title)
                subtitle = getString(R.string.state_disconnected_subtitle)
                pill = getString(R.string.pill_disconnected)
                accentColor = idle
                connected = false
            }
            VpnState.CONNECTING -> {
                title = getString(R.string.state_connecting_title)
                subtitle = getString(R.string.state_connecting_subtitle)
                pill = getString(R.string.pill_connecting)
                accentColor = accent
                connected = true
            }
            VpnState.CONNECTED -> {
                title = getString(R.string.state_connected_title)
                subtitle = getString(R.string.state_connected_subtitle)
                pill = getString(R.string.pill_connected)
                accentColor = accent
                connected = true
            }
            VpnState.DISCONNECTING -> {
                title = getString(R.string.state_disconnecting_title)
                subtitle = getString(R.string.state_disconnecting_subtitle)
                pill = getString(R.string.pill_disconnecting)
                accentColor = idle
                connected = true
            }
        }

        binding.stateTitle.text = title
        binding.stateSubtitle.text = subtitle
        binding.statusPill.text = pill
        binding.statusPill.setTextColor(accentColor)
        ViewCompat.setBackgroundTintList(
            binding.statusPill,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surface_elevated))
        )
        ImageViewCompat.setImageTintList(
            binding.stateIcon,
            ColorStateList.valueOf(if (state == VpnState.CONNECTED) accent else idle)
        )

        binding.ringView.setAccent(accentColor)
        binding.ringView.setIndeterminate(state == VpnState.CONNECTING || state == VpnState.DISCONNECTING)
        binding.ringView.setProgress(if (state == VpnState.CONNECTED) 1f else 0f)

        styleConnectButton(active = connected && state != VpnState.DISCONNECTING)
        binding.connectButton.isEnabled = state != VpnState.DISCONNECTING
        binding.connectButton.text = when (state) {
            VpnState.CONNECTED, VpnState.CONNECTING -> getString(R.string.action_disconnect)
            VpnState.DISCONNECTING -> getString(R.string.action_disconnecting)
            VpnState.DISCONNECTED -> getString(R.string.action_connect)
        }
    }

    private fun renderStats(stats: VpnStats) {
        binding.durationValue.text = Formatters.duration(stats.connectedSeconds)
        binding.downloadValue.text = Formatters.speed(stats.downloadBytesPerSecond)
        binding.uploadValue.text = Formatters.speed(stats.uploadBytesPerSecond)
    }

    private fun renderProfile() {
        val current = profile ?: return
        binding.profileName.text = current.displayName
        binding.profileServer.text = when {
            current.engine == VpnEngine.TOR ->
                getString(R.string.profile_tor_endpoint)
            current.engine == VpnEngine.WIREGUARD && !current.isConfigured ->
                getString(R.string.profile_wireguard_import_hint)
            current.engine == VpnEngine.WIREGUARD ->
                getString(R.string.profile_wireguard_endpoint, current.endpoint)
            current.isCustom ->
                getString(R.string.profile_custom_endpoint, current.endpoint)
            else ->
                getString(R.string.profile_default_endpoint, current.endpoint)
        }
    }

    private fun applyProtocolSelection(engine: VpnEngine) {
        val target = when (engine) {
            VpnEngine.WIREGUARD -> R.id.wireGuardButton
            VpnEngine.TOR -> R.id.torButton
            VpnEngine.OPENVPN -> R.id.openVpnButton
        }
        if (binding.protocolToggle.checkedButtonId != target) {
            binding.protocolToggle.check(target)
        }
    }

    private fun onProtocolSelected(engine: VpnEngine) {
        if (ProfileStore.engine(this) == engine) return
        if (VpnConnectionManager.state.value != VpnState.DISCONNECTED) {
            VpnConnectionManager.disconnect()
        }
        ProfileStore.setEngine(this, engine)
        profile = ProfileStore.load(this, engine)
        renderProfile()
    }

    private fun styleConnectButton(active: Boolean) {
        val background = ContextCompat.getColor(
            this,
            if (active) R.color.surface_elevated else R.color.accent
        )
        val foreground = ContextCompat.getColor(
            this,
            if (active) R.color.text_primary else R.color.on_accent
        )
        val stroke = ContextCompat.getColor(
            this,
            if (active) R.color.outline else R.color.accent
        )
        binding.connectButton.backgroundTintList = ColorStateList.valueOf(background)
        binding.connectButton.setTextColor(foreground)
        binding.connectButton.strokeColor = ColorStateList.valueOf(stroke)
        binding.connectButton.strokeWidth = if (active) (1 * resources.displayMetrics.density).toInt() else 0
    }

    private fun onConnectClicked() {
        when (VpnConnectionManager.state.value) {
            VpnState.DISCONNECTED -> {
                val current = profile ?: ProfileStore.load(this).also { profile = it }
                if (current.engine == VpnEngine.TOR) {
                    openTor()
                    return
                }
                if (current.engine == VpnEngine.WIREGUARD && !current.isConfigured) {
                    showMessage(getString(R.string.profile_wireguard_import_hint))
                    pickProfile()
                    return
                }
                requestVpnPermissionAndConnect()
            }
            VpnState.CONNECTING, VpnState.CONNECTED -> VpnConnectionManager.disconnect()
            VpnState.DISCONNECTING -> Unit
        }
    }

    private fun requestVpnPermissionAndConnect() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startConnection()
        }
    }

    private fun startConnection() {
        val current = profile ?: ProfileStore.load(this).also { profile = it }
        VpnConnectionManager.connect(current)
    }

    private fun pickProfile() {
        profilePicker.launch(arrayOf("*/*"))
    }

    private fun importProfile(uri: Uri) {
        runCatching { ProfileStore.importFrom(this, uri) }
            .onSuccess { imported ->
                profile = imported
                applyProtocolSelection(imported.engine)
                renderProfile()
                showMessage(getString(R.string.profile_imported, imported.displayName))
                if (imported.notes.isNotEmpty()) showLegacyNotes(imported.notes)
            }
            .onFailure {
                val isWireGuard = ProfileStore.engine(this) == VpnEngine.WIREGUARD
                showMessage(
                    getString(
                        if (isWireGuard) R.string.error_wireguard_invalid else R.string.error_profile_invalid
                    )
                )
            }
    }

    /**
     * Tor secenegi: cihazda Tor calistiran Orbot uygulamasini baslatir. Yoksa
     * ucretsiz indirme sayfasini onerir.
     */
    private fun openTor() {
        if (!Orbot.isInstalled(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tor_missing_title)
                .setMessage(R.string.tor_missing_message)
                .setPositiveButton(R.string.tor_download) { _, _ -> Orbot.openDownloadPage(this) }
                .setNegativeButton(R.string.action_later, null)
                .show()
            return
        }
        val opened = Orbot.start(this)
        showMessage(
            getString(if (opened) R.string.tor_opened else R.string.tor_open_failed)
        )
    }

    /** Ice aktarilan eski profilde hangi direktiflerin uyarlandigini gosterir. */
    private fun showLegacyNotes(notes: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.legacy_applied_title)
            .setMessage(
                getString(
                    R.string.legacy_applied_message,
                    notes.joinToString("\n") { "• $it" }
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openPersonalLinks() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Links.PERSONAL_LINKS))
        runCatching { startActivity(intent) }
            .onFailure { showMessage(getString(R.string.settings_browse_desc)) }
    }

    private fun maybeCheckUpdates() {
        if (!AppSettings.checkUpdatesOnLaunch(this)) return
        UpdatePrompt.check(this, manual = false)
    }

    private fun maybeAutoConnect() {
        if (!AppSettings.autoConnect(this)) return
        if (VpnConnectionManager.state.value != VpnState.DISCONNECTED) return
        val current = profile ?: return
        // Tor'u otomatik baslatmak Orbot'un izin akisini atlar; kullanici secer.
        if (current.engine == VpnEngine.TOR) return
        if (current.engine == VpnEngine.WIREGUARD && !current.isConfigured) return
        binding.root.postDelayed({ requestVpnPermissionAndConnect() }, 400L)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.surface_elevated))
            .setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            .show()
    }
}
