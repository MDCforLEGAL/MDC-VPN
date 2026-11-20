package com.mdc.vpn

import android.Manifest
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
import com.google.android.material.snackbar.Snackbar
import com.mdc.vpn.data.ProfileStore
import com.mdc.vpn.data.VpnProfile
import com.mdc.vpn.databinding.ActivityMainBinding
import com.mdc.vpn.util.Formatters
import com.mdc.vpn.vpn.VpnConnectionManager
import com.mdc.vpn.vpn.VpnState
import com.mdc.vpn.vpn.VpnStats
import kotlinx.coroutines.launch

/**
 * Tek ekranli arayuz: durum halkasi, tek tusla baglan/kes, oturum istatistikleri
 * ve `.ovpn` profil secimi.
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
            // Bildirim izni verilmese de tünel calisir; yalnizca durum bildirimi
            // gosterilemez.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profile = ProfileStore.load(this)
        renderProfile()

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.importButton.setOnClickListener { pickProfile() }

        observeVpnState()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        VpnConnectionManager.bind()
    }

    override fun onResume() {
        super.onResume()
        VpnConnectionManager.refreshFromSystem()
    }

    override fun onStop() {
        VpnConnectionManager.unbind()
        super.onStop()
    }

    private fun observeVpnState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VpnConnectionManager.state.collect { renderState(it) } }
                launch { VpnConnectionManager.stats.collect { renderStats(it) } }
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
        binding.profileServer.text = getString(
            if (current.isCustom) R.string.profile_custom_endpoint else R.string.profile_default_endpoint,
            current.endpoint
        )
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
            VpnState.DISCONNECTED -> requestVpnPermissionAndConnect()
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
                renderProfile()
                showMessage(getString(R.string.profile_imported, imported.displayName))
            }
            .onFailure {
                showMessage(getString(R.string.error_profile_invalid))
            }
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
