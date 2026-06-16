package com.wifiextender.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.R
import com.wifiextender.databinding.FragmentHotspotBinding
import com.wifiextender.utils.ConnectedClient
import com.wifiextender.utils.HotspotApplyResult
import com.wifiextender.utils.HotspotManager

class HotspotFragment : Fragment() {

    private var _binding: FragmentHotspotBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var hotspotManager: HotspotManager

    private val handler = Handler(Looper.getMainLooper())
    private var uptimeSeconds = 0
    private var uptimeRunnable: Runnable? = null
    private var syncRunnable: Runnable? = null
    private var autoCheckRunnable: Runnable? = null
    private var isHotspotActive = false
    private var maxDevices: Int = 0
    private var unlimitedDevices = false
    private var passwordDialogShown = false
    private var lastSyncedCredentials: Pair<String, String>? = null
    private var ssidRetryRunnable: Runnable? = null
    private var passwordDialog: AlertDialog? = null
    private var ssidConfirmPending = false
    private var ssidConfirmDialog: AlertDialog? = null
    private var isFragmentResumed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHotspotBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hotspotManager = HotspotManager(requireContext())
        hotspotManager.ensurePhoneSsidListener()

        viewModel.subscription.observe(viewLifecycleOwner) { sub ->
            maxDevices = sub?.plan?.maxDevices ?: sub?.maxDevices ?: 0
            unlimitedDevices = sub?.plan?.unlimitedDevices == true || maxDevices < 0
            if (isHotspotActive) updateDeviceLimitBadge()
        }

        viewModel.loadHome()

        viewModel.upgradeRequired.observe(viewLifecycleOwner) { msg ->
            if (msg != null && isHotspotActive) {
                val text = try {
                    org.json.JSONObject(msg).optString("message", msg)
                } catch (_: Exception) { msg }
                    .replace("UPGRADE_REQUIRED: ", "")
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("⚠️ Device Limit Reached")
                    .setMessage("$text\n\nUpgrade your plan to connect more devices.")
                    .setPositiveButton("Upgrade Plan") { _, _ ->
                        activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                            com.wifiextender.R.id.bottom_nav
                        )?.selectedItemId = com.wifiextender.R.id.nav_subscription
                    }
                    .setNegativeButton("OK", null)
                    .show()
                viewModel.clearUpgradeRequired()
            }
        }

        viewModel.devices.observe(viewLifecycleOwner) { devices ->
            if (_binding == null || !isHotspotActive) return@observe
            val online = devices.filter { it.online && !it.blocked }
            updateConnectedDisplay(online.map {
                ConnectedClient(
                    name = com.wifiextender.utils.DeviceNameResolver.formatDeviceLabel(
                        it.deviceName, it.vendor, it.ipAddress, it.macAddress
                    ),
                    macAddress = it.macAddress,
                    ipAddress = it.ipAddress,
                    vendor = it.vendor
                )
            })
        }

        if (hotspotManager.isHotspotOn()) {
            isHotspotActive = true
            refreshCredentialsQuietly()
            setUiStarted()
            startUptimeTimer()
            startSyncPolling()
            if (hotspotManager.getConfirmedSsid().isEmpty()) {
                handler.postDelayed({ requestSsidConfirm() }, 500)
            }
        } else {
            setUiStopped()
        }

        binding.btnToggleHotspot.setOnClickListener {
            if (isHotspotActive) {
                openHotspotSettings()
                startAutoCheckOff()
            } else {
                ssidConfirmPending = true
                openHotspotSettings()
                startAutoCheckOn()
            }
        }

        binding.tvPassword.setOnClickListener {
            val ssid = resolveCurrentSsid()
            if (ssid.isEmpty()) {
                requestSsidConfirm()
            } else {
                passwordDialogShown = false
                requestPasswordIfNeeded(ssid)
            }
        }
        binding.tvPassword.setOnLongClickListener {
            showPasswordEditDialog()
            true
        }

        binding.btnEditPassword.setOnClickListener {
            showPasswordEditDialog()
        }

        binding.tvSsid.setOnClickListener {
            ssidConfirmPending = true
            openHotspotSettings()
        }
        binding.tvSsid.setOnLongClickListener {
            requestSsidConfirm()
            true
        }

        binding.btnSyncSsid.setOnClickListener {
            requestSsidConfirm()
        }

        binding.btnCopySsid.setOnClickListener {
            copyToClipboard("SSID", binding.tvSsid.text.toString())
        }
        binding.btnCopyPassword.setOnClickListener {
            copyToClipboard("Password", binding.tvPassword.text.toString())
        }

        updateWifiSource()
    }

    // ── Credential sync ───────────────────────────────────────────────────────

    private fun resolveCurrentSsid(): String {
        val confirmed = hotspotManager.getConfirmedSsid()
        if (confirmed.isNotEmpty()) return confirmed
        hotspotManager.readSsidFromPhone()?.let { return it }
        return binding.tvSsid.text.toString().let { text ->
            if (isPlaceholderSsid(text)) "" else text.trim()
        }
    }

    private fun resolveCurrentPassword(): String =
        binding.tvPassword.text.toString().let {
            if (it.startsWith("Tap to enter")) "" else it.trim()
        }

    private fun isPlaceholderSsid(text: String): Boolean =
        text in listOf("Detecting SSID...", "Tap SYNC to enter name", "—", "")

    private fun isFragmentReady(): Boolean =
        _binding != null && isAdded && isFragmentResumed

    /** Long-press / EDIT — update saved hotspot password */
    private fun showPasswordEditDialog() {
        if (!isFragmentReady()) return
        val ssid = resolveCurrentSsid()
        if (ssid.isEmpty()) {
            showSnack("Set hotspot name first")
            requestSsidConfirm()
            return
        }
        passwordDialogShown = false
        showPasswordDialog(ssid, resolveCurrentPassword(), isEdit = true)
    }
    private fun requestSsidConfirm(prefill: String = hotspotManager.getConfirmedSsid()) {
        if (!isFragmentReady()) return
        if (ssidConfirmDialog?.isShowing == true) return
        if (!hotspotManager.isHotspotOn() && prefill.isEmpty()) {
            showSnack("Turn on hotspot first")
            return
        }
        showSsidConfirmDialog(prefill)
    }

    /** Show password dialog only after SSID is known — never stack with SSID dialog */
    private fun requestPasswordIfNeeded(ssid: String) {
        if (!isFragmentReady() || ssid.isEmpty()) return
        if (ssidConfirmDialog?.isShowing == true) return
        if (passwordDialog?.isShowing == true) return

        val pass = hotspotManager.readPasswordForSsid(ssid)
        if (pass.length >= 8) {
            binding.tvPassword.text = pass
            passwordDialogShown = false
            syncToBackendIfNeeded(ssid, pass)
            return
        }
        if (!passwordDialogShown) {
            passwordDialogShown = true
            showPasswordSetupDialog(ssid)
        }
    }

    /** Refresh SSID/password on screen without opening any dialogs */
    private fun refreshCredentialsQuietly() {
        if (_binding == null) return
        ssidRetryRunnable?.let { handler.removeCallbacks(it) }

        val autoSsid = hotspotManager.readSsidFromPhone()
        val displaySsid = autoSsid ?: hotspotManager.getConfirmedSsid()

        if (displaySsid.isNotEmpty()) {
            applyCredentials(displaySsid, showPasswordDialog = false)
        } else if (hotspotManager.isHotspotOn()) {
            binding.tvSsid.text = "Tap SYNC to enter name"
            binding.tvPassword.text = "Tap to enter password →"
        }
    }

    /** Retry SSID read — SoftAp config may not be ready the instant hotspot turns on */
    private fun syncCredentialsWithRetry(showPasswordDialog: Boolean = true) {
        if (_binding == null) return
        ssidRetryRunnable?.let { handler.removeCallbacks(it) }
        binding.tvSsid.text = "Detecting SSID..."
        var attempts = 0
        ssidRetryRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                attempts++
                val autoSsid = hotspotManager.readSsidFromPhone()
                val displaySsid = autoSsid ?: hotspotManager.getConfirmedSsid()
                if (displaySsid.isNotEmpty()) {
                    applyCredentials(displaySsid, showPasswordDialog)
                    ssidRetryRunnable = null
                } else if (attempts < 12) {
                    handler.postDelayed(this, 400)
                } else {
                    binding.tvSsid.text = "Tap SYNC to enter name"
                    binding.tvPassword.text = "Tap to enter password →"
                    ssidRetryRunnable = null
                }
            }
        }
        handler.post(ssidRetryRunnable!!)
    }

    private fun showSsidConfirmDialog(current: String) {
        if (!isFragmentReady()) return
        ssidConfirmDialog?.dismiss()
        passwordDialog?.dismiss()

        val dialogView = layoutInflater.inflate(R.layout.dialog_hotspot_ssid, null)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_message)
        val ssidInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_ssid)
        val ssidLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_ssid)

        messageView.text = if (hotspotManager.isSystemSsidReadBlocked()) {
            "Your phone blocks apps from reading the hotspot name automatically.\n\n" +
                "Open hotspot Settings, check the Network name, then type it exactly below."
        } else {
            "Enter the hotspot network name shown in your phone Settings."
        }
        ssidInput.setText(current)
        ssidInput.setSelection(ssidInput.text?.length ?: 0)

        ssidConfirmDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { ssidConfirmDialog = null }
            .create()

        ssidConfirmDialog?.setOnShowListener {
            val saveBtn = ssidConfirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@setOnShowListener
            saveBtn.setOnClickListener {
                val ssid = ssidInput.text.toString().trim()
                if (ssid.isEmpty()) {
                    ssidLayout.error = "Name is required"
                    return@setOnClickListener
                }
                ssidLayout.error = null
                hotspotManager.saveConfirmedSsid(ssid)
                binding.tvSsid.text = ssid
                ssidConfirmDialog?.dismiss()
                showSnack("✅ Hotspot name saved: $ssid")
                handler.postDelayed({ requestPasswordIfNeeded(ssid) }, 250)
            }
        }
        ssidConfirmDialog?.show()
    }

    private fun applyCredentials(ssid: String, showPasswordDialog: Boolean) {
        if (_binding == null) return
        val autoSsid = hotspotManager.readSsidFromPhone()
        val resolvedSsid = autoSsid ?: ssid.ifEmpty { hotspotManager.readDisplaySsid() }
        if (resolvedSsid.isNotEmpty()) {
            binding.tvSsid.text = resolvedSsid
        } else {
            binding.tvSsid.text = "—"
        }

        val password = if (resolvedSsid.isNotEmpty()) {
            hotspotManager.readPasswordForSsid(resolvedSsid)
        } else {
            hotspotManager.readPassword()
        }

        if (password.isNotEmpty()) {
            binding.tvPassword.text = password
            passwordDialogShown = false
            if (resolvedSsid.isNotEmpty()) syncToBackendIfNeeded(resolvedSsid, password)
        } else if (showPasswordDialog && !passwordDialogShown && resolvedSsid.isNotEmpty()) {
            requestPasswordIfNeeded(resolvedSsid)
        } else {
            binding.tvPassword.text = "Tap to enter password →"
        }

        updateConnectedDisplay(hotspotManager.readConnectedClients())
    }

    /** Force UI to match phone hotspot name — never opens dialogs (polling-safe) */
    private fun syncUiFromPhone() {
        if (_binding == null) return
        val autoSsid = hotspotManager.readSsidFromPhone()
        val displaySsid = autoSsid ?: hotspotManager.getConfirmedSsid()
        if (displaySsid.isEmpty()) return
        binding.tvSsid.text = displaySsid
        val pass = hotspotManager.readPasswordForSsid(displaySsid)
        if (pass.isNotEmpty()) {
            binding.tvPassword.text = pass
            syncToBackendIfNeeded(displaySsid, pass)
        }
    }

    private fun syncCredentials() = syncCredentialsWithRetry()

    private fun syncToBackendIfNeeded(ssid: String, password: String, force: Boolean = false) {
        if (password.length < 8) return
        val credentials = ssid to password
        if (!force && credentials == lastSyncedCredentials) return
        lastSyncedCredentials = credentials
        val limit = hotspotMaxClients()
        viewModel.syncHotspotCredentials(ssid, password, limit)
    }

    private fun hotspotMaxClients(): Int = when {
        unlimitedDevices || maxDevices < 0 -> 50
        maxDevices > 0 -> maxDevices
        else -> 10
    }

    private fun savePasswordLocally(ssid: String, password: String) {
        hotspotManager.savePasswordForSsid(ssid, password)
        binding.tvPassword.text = password
        passwordDialogShown = false
        lastSyncedCredentials = null
        syncToBackendIfNeeded(ssid, password, force = true)
    }

    private fun updateConnectedDisplay(clients: List<ConnectedClient>) {
        if (_binding == null) return
        val count = clients.size
        binding.tvConnectedCount.text = "$count device${if (count != 1) "s" else ""} connected"
        binding.tvConnectedNames.text = if (clients.isEmpty()) {
            "No devices connected yet"
        } else {
            clients.joinToString("\n") { client ->
                val label = com.wifiextender.utils.DeviceNameResolver.formatDeviceLabel(
                    client.name, client.vendor, client.ipAddress, client.macAddress ?: ""
                )
                "• $label"
            }
        }
        binding.tvConnectedNames.visibility = View.VISIBLE
        enforceDeviceLimit(count)
    }

    private fun applyAndSaveCredentials(ssid: String, password: String) {
        if (_binding == null) return
        savePasswordLocally(ssid, password)

        val result = hotspotManager.applyHotspotConfig(ssid, password)
        handler.postDelayed({
            if (_binding == null) return@postDelayed
            when (result) {
                is HotspotApplyResult.Success ->
                    showSnack("✅ Password updated on phone and app!")
                is HotspotApplyResult.NeedsHotspotRestart ->
                    showHotspotRestartDialog(ssid)
                is HotspotApplyResult.Failed -> {
                    showSnack("✅ Password saved in app. Also update it in phone Settings → Hotspot.")
                    if (hotspotManager.isSystemSsidReadBlocked()) {
                        hotspotManager.openPhoneHotspotSettings()
                    }
                }
            }
        }, 400)
    }

    private fun showHotspotRestartDialog(ssid: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Restart hotspot")
            .setMessage(
                "Hotspot name was set to \"$ssid\" on your phone.\n\n" +
                    "Turn hotspot OFF then ON in settings (or tap Restart below) so devices see the new name."
            )
            .setPositiveButton("Restart now") { _, _ ->
                if (hotspotManager.restartHotspot()) {
                    showSnack("✅ Hotspot restarted with new name!")
                    handler.postDelayed({ syncCredentialsWithRetry(showPasswordDialog = false) }, 2000)
                } else {
                    showSnack("Please toggle hotspot off/on in Settings")
                    openHotspotSettings()
                }
            }
            .setNegativeButton("Open Settings") { _, _ -> openHotspotSettings() }
            .show()
    }

    private fun showPasswordSetupDialog(detectedSsid: String) {
        showPasswordDialog(detectedSsid, "", isEdit = false)
    }

    private fun showPasswordDialog(ssid: String, currentPassword: String, isEdit: Boolean) {
        if (!isFragmentReady()) return
        if (ssid.isEmpty()) return
        if (ssidConfirmDialog?.isShowing == true) return
        passwordDialog?.dismiss()

        val dialogView = layoutInflater.inflate(R.layout.dialog_hotspot_password, null)
        val titleView = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        val messageView = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_message)
        val networkView = dialogView.findViewById<android.widget.TextView>(R.id.tv_network_name)
        val miuiHint = dialogView.findViewById<android.widget.TextView>(R.id.tv_miui_hint)
        val passInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_password)
        val passLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_password)

        titleView.text = if (isEdit) "Update Password" else "Set Hotspot Password"
        messageView.text = if (isEdit) {
            "Change the password clients use to connect to your hotspot."
        } else {
            "Enter the password for your hotspot network."
        }
        networkView.text = ssid
        if (hotspotManager.isSystemSsidReadBlocked()) {
            miuiHint.visibility = View.VISIBLE
        }
        passInput.setText(currentPassword)
        if (currentPassword.isNotEmpty()) {
            passInput.setSelection(currentPassword.length)
        }

        passwordDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton(if (isEdit) "Cancel" else "Skip") { _, _ ->
                if (!isEdit) passwordDialogShown = false
            }
            .setOnDismissListener { passwordDialog = null }
            .create()

        passwordDialog?.setOnShowListener {
            val saveBtn = passwordDialog?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return@setOnShowListener
            saveBtn.setOnClickListener {
                val pass = passInput.text.toString()
                when {
                    pass.length < 8 -> passLayout.error = "At least 8 characters required"
                    else -> {
                        passLayout.error = null
                        passwordDialog?.dismiss()
                        if (isEdit) {
                            applyAndSaveCredentials(ssid, pass)
                        } else {
                            savePasswordLocally(ssid, pass)
                            showSnack("✅ Password saved!")
                        }
                    }
                }
            }
        }

        passwordDialog?.show()
    }

    // ── Polling every 3s ──────────────────────────────────────────────────────

    private fun startSyncPolling() {
        stopSyncPolling()
        syncRunnable = object : Runnable {
            override fun run() {
                if (_binding == null || !isHotspotActive) return
                syncUiFromPhone()
                viewModel.scanAndReportDevices(requireContext())
                handler.postDelayed(this, 15000)
            }
        }
        handler.postDelayed(syncRunnable!!, 2000)
    }

    private fun stopSyncPolling() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        syncRunnable = null
    }

    // ── Device limit ──────────────────────────────────────────────────────────

    private fun enforceDeviceLimit(connectedCount: Int) {
        if (_binding == null) return
        binding.tvDeviceLimit.visibility = View.VISIBLE
        when {
            unlimitedDevices -> {
                binding.tvDeviceLimit.text = "Devices: $connectedCount / ∞"
                binding.tvDeviceLimit.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
            }
            maxDevices > 0 -> {
                val atLimit = connectedCount >= maxDevices
                binding.tvDeviceLimit.text =
                    if (atLimit) "⚠️ Device limit reached ($connectedCount / $maxDevices)"
                    else "Devices: $connectedCount / $maxDevices"
                binding.tvDeviceLimit.setTextColor(ContextCompat.getColor(requireContext(),
                    if (atLimit) R.color.red_500 else R.color.brand_primary))
            }
            else -> {
                binding.tvDeviceLimit.text = "Devices: $connectedCount (no active plan)"
                binding.tvDeviceLimit.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
        }
    }

    private fun updateDeviceLimitBadge() {
        if (_binding == null || !isHotspotActive) return
        val onlineCount = viewModel.devices.value
            ?.count { it.online && !it.blocked }
            ?: hotspotManager.readClientCount()
        enforceDeviceLimit(onlineCount)
    }

    // ── Auto-check: wait until hotspot ON / OFF ───────────────────────────────

    private fun startAutoCheckOn() {
        autoCheckRunnable?.let { handler.removeCallbacks(it) }
        binding.btnToggleHotspot.text = "Waiting for hotspot..."
        binding.btnToggleHotspot.isEnabled = false
        var checks = 0
        autoCheckRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                checks++
                if (hotspotManager.isHotspotOn()) {
                    isHotspotActive = true
                    passwordDialogShown = false
                    lastSyncedCredentials = null
                    refreshCredentialsQuietly()
                    setUiStarted()
                    startUptimeTimer()
                    startSyncPolling()
                    autoCheckRunnable = null
                } else if (checks < 40) {
                    handler.postDelayed(this, 1500)
                } else {
                    binding.btnToggleHotspot.text = "▶  Start Hotspot"
                    binding.btnToggleHotspot.isEnabled = true
                    autoCheckRunnable = null
                }
            }
        }
        handler.postDelayed(autoCheckRunnable!!, 1500)
    }

    private fun startAutoCheckOff() {
        autoCheckRunnable?.let { handler.removeCallbacks(it) }
        var checks = 0
        autoCheckRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                checks++
                if (!hotspotManager.isHotspotOn()) {
                    isHotspotActive = false
                    lastSyncedCredentials = null
                    setUiStopped()
                    stopUptimeTimer()
                    stopSyncPolling()
                    autoCheckRunnable = null
                } else if (checks < 20) {
                    handler.postDelayed(this, 1500)
                } else {
                    autoCheckRunnable = null
                }
            }
        }
        handler.postDelayed(autoCheckRunnable!!, 1500)
    }

    override fun onResume() {
        super.onResume()
        isFragmentResumed = true
        updateWifiSource()
        val hotspotOn = hotspotManager.isHotspotOn()

        when {
            !isHotspotActive && hotspotOn -> {
                isHotspotActive = true
                autoCheckRunnable?.let { handler.removeCallbacks(it) }
                passwordDialogShown = false
                lastSyncedCredentials = null
                refreshCredentialsQuietly()
                setUiStarted()
                if (uptimeRunnable == null) startUptimeTimer()
                if (syncRunnable == null) startSyncPolling()
            }
            isHotspotActive && !hotspotOn -> {
                isHotspotActive = false
                lastSyncedCredentials = null
                ssidConfirmPending = false
                ssidRetryRunnable?.let { handler.removeCallbacks(it) }
                ssidConfirmDialog?.dismiss()
                passwordDialog?.dismiss()
                setUiStopped()
                stopUptimeTimer()
                stopSyncPolling()
            }
            isHotspotActive -> syncUiFromPhone()
        }

        if (ssidConfirmPending && hotspotOn) {
            ssidConfirmPending = false
            handler.postDelayed({ requestSsidConfirm() }, 350)
        }
    }

    override fun onPause() {
        isFragmentResumed = false
        super.onPause()
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun setUiStarted() {
        if (_binding == null) return
        binding.btnToggleHotspot.isEnabled = true
        binding.btnToggleHotspot.text = "⏹  Stop Hotspot"
        binding.btnToggleHotspot.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.red_500))
        binding.tvHotspotStatus.text = "🟢 Hotspot Active"
        binding.tvHotspotStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.green_500))
        binding.cardHotspotInfo.visibility = View.VISIBLE
    }

    private fun setUiStopped() {
        if (_binding == null) return
        binding.btnToggleHotspot.isEnabled = true
        binding.btnToggleHotspot.text = "▶  Start Hotspot"
        binding.btnToggleHotspot.setBackgroundColor(
            ContextCompat.getColor(requireContext(), R.color.brand_primary))
        binding.tvHotspotStatus.text = "⚫ Hotspot Inactive"
        binding.tvHotspotStatus.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.text_secondary))
        binding.cardHotspotInfo.visibility = View.GONE
        binding.tvDeviceLimit.visibility = View.GONE
        binding.tvConnectedCount.text = "0 devices connected"
        binding.tvConnectedNames.text = ""
        binding.tvConnectedNames.visibility = View.GONE
        binding.tvUptime.text = "00:00:00"
        uptimeSeconds = 0
    }

    private fun updateWifiSource() {
        if (_binding == null) return
        try {
            val wm = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid
                ?.removePrefix("\"")?.removeSuffix("\"") ?: ""
            if (ssid.isNotEmpty() && ssid != "<unknown ssid>") {
                binding.tvWifiSource.text = ssid
            }
        } catch (_: Exception) {}
    }

    private fun startUptimeTimer() {
        uptimeSeconds = 0
        uptimeRunnable = object : Runnable {
            override fun run() {
                if (_binding == null) return
                uptimeSeconds++
                val h = uptimeSeconds / 3600
                val m = (uptimeSeconds % 3600) / 60
                val s = uptimeSeconds % 60
                binding.tvUptime.text = "%02d:%02d:%02d".format(h, m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(uptimeRunnable!!)
    }

    private fun stopUptimeTimer() {
        uptimeRunnable?.let { handler.removeCallbacks(it) }
        uptimeRunnable = null
    }

    private fun openHotspotSettings() {
        if (!hotspotManager.openPhoneHotspotSettings()) {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
        showSnack("$label copied!")
    }

    private fun showSnack(msg: String) {
        if (_binding != null) Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        stopUptimeTimer()
        stopSyncPolling()
        ssidRetryRunnable?.let { handler.removeCallbacks(it) }
        autoCheckRunnable?.let { handler.removeCallbacks(it) }
        passwordDialog?.dismiss()
        ssidConfirmDialog?.dismiss()
        super.onDestroyView()
        _binding = null
    }
}
