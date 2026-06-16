package com.wifiextender.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.net.Socket
import java.net.InetSocketAddress

data class HotspotInfo(
    val ssid: String,
    val password: String,
    val clientCount: Int,
    val connectedClients: List<ConnectedClient>,
    val isActive: Boolean
)

data class ConnectedClient(
    val name: String,
    val macAddress: String?,
    val ipAddress: String?,
    val vendor: String? = null
)

sealed class HotspotApplyResult {
    data object Success : HotspotApplyResult()
    data object NeedsHotspotRestart : HotspotApplyResult()
    data class Failed(val message: String) : HotspotApplyResult()
}

class HotspotManager(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hotspot_prefs", Context.MODE_PRIVATE)

    /** Latest SSID reported by the phone (SoftAp callback / system read) */
    @Volatile
    private var phoneSsidCache: String? = null

    @Volatile
    private var softApClientsCache: List<ConnectedClient> = emptyList()

    @Volatile
    private var tetheringClientsCache: List<ConnectedClient> = emptyList()

    private var softApCallbackRegistered = false
    private var tetheringCallbackRegistered = false
    private val softApCallbackExecutor: Executor by lazy {
        val thread = android.os.HandlerThread("SoftApCallback").apply { start() }
        Executor { runnable -> android.os.Handler(thread.looper).post(runnable) }
    }
    @Volatile private var lastSubnetScanAt = 0L
    @Volatile private var subnetScanCache: List<ConnectedClient> = emptyList()
    @Volatile private var stableHotspotClients: List<ConnectedClient> = emptyList()
    private val subnetScanLock = Any()
    private var systemReadBlocked: Boolean? = null
    private val deviceNamePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_DEVICE_NAMES, Context.MODE_PRIVATE)

    private fun getCachedDeviceName(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        return deviceNamePrefs.getString(mac.uppercase(), null)
    }

    private fun cacheDeviceName(mac: String?, name: String) {
        if (mac.isNullOrBlank() || !DeviceNameResolver.isRealHostname(name)) return
        deviceNamePrefs.edit().putString(mac.uppercase(), name.trim()).apply()
    }

    companion object {
        private const val TAG = "HotspotManager"
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val KEY_CONFIRMED_SSID = "confirmed_phone_ssid"
        private const val PREFS_DEVICE_NAMES = "device_name_cache"
    }

    // ── Hotspot on/off ────────────────────────────────────────────────────────

    fun isHotspotOn(): Boolean {
        return try {
            val m: Method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            m.invoke(wifiManager) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    fun ensurePhoneSsidListener() {
        ensureClientListeners()
    }

    /** Register system callbacks for connected hotspot clients (only reliable path on MIUI). */
    fun ensureClientListeners() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        registerSoftApClientCallback()
        registerTetheringClientCallback()
        refreshTetheringClientsCache()
    }

    private fun mainExecutor(): Executor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) context.mainExecutor
        else Executor { it.run() }

    private fun registerSoftApClientCallback() {
        if (softApCallbackRegistered) return
        try {
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
                when (method.name) {
                    "onStateChanged" -> {
                        val state = args?.getOrNull(0) as? Int
                        if (state == WIFI_AP_STATE_ENABLED) {
                            refreshPhoneSsidCache()
                            refreshTetheringClientsCache()
                        }
                    }
                    "onConnectedClientsChanged" -> {
                        val clients = args?.lastOrNull() as? Collection<*>
                        updateSoftApClientsCacheFromReflection(clients)
                        Log.d(TAG, "onConnectedClientsChanged: ${clients?.size ?: 0}")
                    }
                }
                null
            }
            wifiManager.javaClass
                .getMethod("registerSoftApCallback", Executor::class.java, callbackClass)
                .invoke(wifiManager, softApCallbackExecutor, callback)
            softApCallbackRegistered = true
            refreshPhoneSsidCache()
            refreshTetheringClientsCache()
            Log.d(TAG, "SoftApCallback registered")
        } catch (e: Exception) {
            Log.w(TAG, "SoftApCallback failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun registerTetheringClientCallback() {
        if (tetheringCallbackRegistered) return
        try {
            val tm = context.getSystemService("tethering") ?: return
            val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
                if (method.name == "onClientsChanged" && args != null && args.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val clients = args[0] as? Collection<*>
                    updateTetheringClientsCacheFromReflection(clients)
                    Log.d(TAG, "onClientsChanged: ${clients?.size ?: 0}")
                }
                null
            }
            (tm as Any).javaClass
                .getMethod("registerTetheringEventCallback", Executor::class.java, callbackClass)
                .invoke(tm, softApCallbackExecutor, callback)
            tetheringCallbackRegistered = true
            Log.d(TAG, "TetheringEventCallback registered")
        } catch (e: Exception) {
            Log.w(TAG, "TetheringEventCallback failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun updateTetheringClientsCache(clients: List<ConnectedClient>) {
        tetheringClientsCache = clients
        Log.d(TAG, "Tethering clients cache: ${clients.size}")
    }

    private fun updateSoftApClientsCache(clients: List<ConnectedClient>) {
        softApClientsCache = clients
        Log.d(TAG, "SoftAp clients updated: ${clients.size}")
    }

    private fun updateTetheringClientsCacheFromReflection(clients: Collection<*>?) {
        if (clients == null) return
        tetheringClientsCache = clients.mapNotNull { item -> item?.let { parseTetheredClient(it) } }
        Log.d(TAG, "Tethering clients cache: ${tetheringClientsCache.size}")
    }

    private fun updateSoftApClientsCacheFromReflection(clients: Collection<*>?) {
        if (clients == null) return
        softApClientsCache = clients.mapNotNull { item -> item?.let { parseWifiClient(it) } }
        Log.d(TAG, "SoftAp clients updated: ${softApClientsCache.size}")
    }

    private fun refreshTetheringClientsCache() {
        readClientsViaTetheringManager()?.let { clients ->
            if (clients.isNotEmpty()) {
                tetheringClientsCache = clients
                Log.d(TAG, "getTetheredClients: ${clients.size}")
            }
        }
        readClientsViaSoftApApi().let { clients ->
            if (clients.isNotEmpty()) {
                softApClientsCache = clients
                Log.d(TAG, "getConnectedSoftApClients: ${clients.size}")
            }
        }
    }

    /** MIUI/Xiaomi blocks getSoftApConfiguration for third-party apps */
    fun isSystemSsidReadBlocked(): Boolean {
        systemReadBlocked?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            systemReadBlocked = false
            return false
        }
        val blocked = try {
            wifiManager.javaClass.getMethod("getSoftApConfiguration").invoke(wifiManager)
            false
        } catch (e: java.lang.reflect.InvocationTargetException) {
            e.targetException is SecurityException
        } catch (_: SecurityException) {
            true
        } catch (e: Exception) {
            Log.w(TAG, "SSID read probe: ${e.javaClass.simpleName}")
            true
        }
        systemReadBlocked = blocked
        Log.d(TAG, "System SSID read blocked=$blocked")
        return blocked
    }

    fun getConfirmedSsid(): String = prefs.getString(KEY_CONFIRMED_SSID, "") ?: ""

    fun saveConfirmedSsid(ssid: String) {
        prefs.edit().putString(KEY_CONFIRMED_SSID, ssid).apply()
        phoneSsidCache = ssid
        saveLastSsid(ssid)
    }

    fun needsManualSsidEntry(): Boolean =
        readSsidFromPhoneInternal() == null && getConfirmedSsid().isEmpty()

    private fun refreshPhoneSsidCache() {
        readSsidFromPhoneInternal()?.let {
            phoneSsidCache = it
            saveConfirmedSsid(it)
        }
    }

    /**
     * SSID to show in the UI — auto-read from phone when possible,
     * otherwise the name the user confirmed from phone Settings (MIUI).
     */
    fun readDisplaySsid(): String {
        ensurePhoneSsidListener()
        readSsidFromPhone()?.let { return it }
        return getConfirmedSsid()
    }

    fun readSsid(): String = readDisplaySsid()

    /** Raw phone read — skips prefs entirely */
    fun readSsidFromPhone(): String? {
        phoneSsidCache?.takeIf { isValidSsid(it) }?.let { return it }
        return readSsidFromPhoneInternal()?.also { cacheAndReturn(it) }
    }

    private fun readSsidFromPhoneInternal(): String? {
        readSsidDirectApi()?.let { return it }
        readSsidFromSoftApReflection()?.let { return it }
        readSsidFromConfigDump()?.let { return it }
        readSsidFromSettings()?.let { return it }
        readSsidViaWifiApConfig()?.let { return it }
        return null
    }

    // ── Full info snapshot ────────────────────────────────────────────────────

    fun readHotspotInfo(): HotspotInfo {
        ensurePhoneSsidListener()
        val ssid = readDisplaySsid()
        val pass = readPasswordForSsid(ssid)
        val clients = readConnectedClients()
        return HotspotInfo(
            ssid             = ssid,
            password         = pass,
            clientCount      = clients.size,
            connectedClients = clients,
            isActive         = isHotspotOn()
        )
    }

    private fun cacheAndReturn(ssid: String): String {
        phoneSsidCache = ssid
        saveConfirmedSsid(ssid)
        return ssid
    }

    @Suppress("NewApi")
    private fun readSsidDirectApi(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val config = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                .invoke(wifiManager) ?: return null
            for (methodName in listOf("getSsid", "getWifiSsid")) {
                try {
                    val value = config.javaClass.getMethod(methodName).invoke(config)
                    parseSsidValue(value)?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }
            parseSsidFromConfigDump(config.toString())
        } catch (e: Exception) {
            Log.w(TAG, "readSsidDirectApi failed: ${e.message}")
            null
        }
    }

    private fun readSsidFromSoftApReflection(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val config = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                .invoke(wifiManager) ?: return null

            for (methodName in listOf("getSsid", "getWifiSsid")) {
                try {
                    val value = config.javaClass.getMethod(methodName).invoke(config)
                    parseSsidValue(value)?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }

            for (fieldName in listOf("mSSID", "SSID", "mSsid", "ssid")) {
                try {
                    val field = config.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    parseSsidValue(field.get(config))?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) { null }
    }

    /** Parse SSID from SoftApConfiguration.toString() — works on MIUI when getters return null */
    private fun readSsidFromConfigDump(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val config = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                .invoke(wifiManager) ?: return null
            parseSsidFromConfigDump(config.toString())
        } catch (_: Exception) { null }
    }

    private fun parseSsidFromConfigDump(dump: String): String? {
        val patterns = listOf(
            Regex("""mCurrentSoftApConfiguration\.SSID:\s*"?([^"\n,}]+)"?"""),
            Regex("""\bSSID[=:\s]+"?([^",}\n]+)"?""", RegexOption.IGNORE_CASE),
            Regex("""mSSID[=:\s]+"?([^",}\n]+)"?""")
        )
        for (pattern in patterns) {
            pattern.find(dump)?.groupValues?.get(1)?.cleanSsid()
                ?.takeIf { isValidSsid(it) }?.let { return it }
        }
        return null
    }

    private fun parseSsidValue(value: Any?): String? {
        if (value == null) return null
        when (value) {
            is String -> return value.cleanSsid()
            is CharSequence -> return value.toString().cleanSsid()
        }
        if (value.javaClass.name.contains("WifiSsid", ignoreCase = true)) {
            for (methodName in listOf("getUtf8Text", "toString")) {
                try {
                    val text = if (methodName == "getUtf8Text") {
                        value.javaClass.getMethod(methodName).invoke(value) as? String
                    } else value.toString()
                    text?.cleanSsid()?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }
        }
        return parseSsidFromConfigDump(value.toString())
    }

    private fun isValidSsid(ssid: String): Boolean {
        return ssid.isNotBlank() &&
            ssid != "<unknown ssid>" &&
            ssid != "null" &&
            ssid != "0x" &&
            ssid != "—" &&
            !ssid.equals("My Hotspot", ignoreCase = true) &&
            !ssid.equals("Detecting SSID...", ignoreCase = true)
    }

    private fun readSsidFromSettings(): String? {
        val keys = listOf(
            "wifi_ap_ssid", "wifi_tether_ssid", "softap_ssid", "wifi_hotspot_ssid",
            "soft_ap_wifi_ssid", "default_wifi_ap_ssid", "wifi_ap_ssid_override",
            "ap_ssid", "tether_ssid", "wifi_ap_name", "wifi_ap_ssid_config"
        )
        val readers = listOf(
            { k: String -> Settings.System.getString(context.contentResolver, k) },
            { k: String -> Settings.Global.getString(context.contentResolver, k) },
            { k: String -> Settings.Secure.getString(context.contentResolver, k) }
        )
        for (read in readers) {
            for (key in keys) {
                try {
                    read(key)?.cleanSsid()?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readSsidViaWifiApConfig(): String? {
        return try {
            val m = wifiManager.javaClass.getDeclaredMethod("getWifiApConfiguration")
            m.isAccessible = true
            val config = m.invoke(wifiManager) ?: return null
            for (fieldName in listOf("SSID", "ssid", "mSSID")) {
                try {
                    val field = config.javaClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    parseSsidValue(field.get(config))?.takeIf { isValidSsid(it) }?.let { return it }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) { null }
    }

    private fun readSoftApPassphrase(): String? {
        return try {
            val config = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                .invoke(wifiManager) ?: return null
            (config.javaClass.getMethod("getPassphrase").invoke(config) as? String)
                ?.trim()?.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    // ── Password ──────────────────────────────────────────────────────────────

    fun readPassword(): String = readPasswordForSsid(readSsid())

    fun readPasswordForSsid(ssid: String): String {
        if (ssid.isNotEmpty()) {
            readSoftApPassphrase()?.let { pass ->
                savePasswordForSsid(ssid, pass)
                return pass
            }
            readPasswordViaWifiApConfig()?.let { pass ->
                savePasswordForSsid(ssid, pass)
                return pass
            }
            prefs.getString(pwdKey(ssid), null)?.let { return it }
        }
        return prefs.getString("saved_password", "") ?: ""
    }

    @Suppress("DEPRECATION")
    private fun readPasswordViaWifiApConfig(): String? {
        return try {
            val m = wifiManager.javaClass.getDeclaredMethod("getWifiApConfiguration")
            m.isAccessible = true
            val config = m.invoke(wifiManager) ?: return null
            (config.javaClass.getDeclaredField("preSharedKey")
                .apply { isAccessible = true }.get(config) as? String)
                ?.trim()?.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    fun savePasswordForSsid(ssid: String, password: String) {
        prefs.edit()
            .putString(pwdKey(ssid), password)
            .putString("saved_password", password)
            .apply()
    }

    fun saveSsid(ssid: String) {
        phoneSsidCache = ssid
        saveLastSsid(ssid)
    }

    // ── Write to phone ────────────────────────────────────────────────────────

    fun applyHotspotConfig(ssid: String, password: String): HotspotApplyResult {
        if (ssid.isBlank()) return HotspotApplyResult.Failed("SSID is required")
        if (password.length < 8) return HotspotApplyResult.Failed("Password must be at least 8 characters")

        val wasActive = isHotspotOn()
        var applied = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applied = applyViaSoftApConfiguration(ssid, password)
        }
        if (!applied) {
            applied = applyViaWifiApConfiguration(ssid, password)
        }
        writeHotspotToSettings(ssid, password)

        saveSsid(ssid)
        savePasswordForSsid(ssid, password)
        phoneSsidCache = null
        refreshPhoneSsidCache()

        val actualSsid = readSsidFromPhone() ?: ""
        if (actualSsid.isNotEmpty() && !actualSsid.equals(ssid, ignoreCase = true)) {
            phoneSsidCache = actualSsid
            return HotspotApplyResult.Failed(
                "Phone hotspot is still \"$actualSsid\". MIUI blocks apps from renaming it — change the name in Settings."
            )
        }

        return when {
            !applied -> HotspotApplyResult.Failed(
                "Open hotspot Settings on your phone and set the name to \"$ssid\"."
            )
            wasActive -> HotspotApplyResult.NeedsHotspotRestart
            else -> HotspotApplyResult.Success
        }
    }

    fun restartHotspot(): Boolean {
        return try {
            val method = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApEnabled", java.lang.Boolean.TYPE
            )
            method.isAccessible = true
            method.invoke(wifiManager, false)
            Thread.sleep(800)
            method.invoke(wifiManager, true) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    fun openPhoneHotspotSettings(): Boolean {
        val intents = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.MiuiTetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun applyViaSoftApConfiguration(ssid: String, password: String): Boolean {
        return try {
            val softApClass = Class.forName("android.net.wifi.SoftApConfiguration")
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val currentConfig = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                .invoke(wifiManager)
            val builder = if (currentConfig != null) {
                builderClass.getConstructor(softApClass).newInstance(currentConfig)
            } else {
                builderClass.getConstructor().newInstance()
            }
            val securityType = softApClass.getField("SECURITY_TYPE_WPA2_PSK").getInt(null)
            builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
            builderClass.getMethod(
                "setPassphrase", String::class.java, Int::class.javaPrimitiveType
            ).invoke(builder, password, securityType)
            val newConfig = builderClass.getMethod("build").invoke(builder)
            val setMethod = wifiManager.javaClass.getMethod("setSoftApConfiguration", softApClass)
            setMethod.invoke(wifiManager, newConfig) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun applyViaWifiApConfiguration(ssid: String, password: String): Boolean {
        return try {
            val getMethod = wifiManager.javaClass.getDeclaredMethod("getWifiApConfiguration")
            getMethod.isAccessible = true
            val config = getMethod.invoke(wifiManager)
                ?: Class.forName("android.net.wifi.WifiConfiguration").newInstance()
            val configClass = config.javaClass
            for (fieldName in listOf("SSID", "ssid")) {
                try {
                    val field = configClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    field.set(config, "\"$ssid\"")
                    break
                } catch (_: Exception) {}
            }
            try {
                val keyField = configClass.getDeclaredField("preSharedKey")
                keyField.isAccessible = true
                keyField.set(config, password)
            } catch (_: Exception) {}
            val setMethod = wifiManager.javaClass.getDeclaredMethod(
                "setWifiApConfiguration", Class.forName("android.net.wifi.WifiConfiguration")
            )
            setMethod.isAccessible = true
            setMethod.invoke(wifiManager, config) as? Boolean ?: false
        } catch (_: Exception) { false }
    }

    private fun writeHotspotToSettings(ssid: String, password: String) {
        val ssidKeys = listOf(
            "wifi_ap_ssid", "soft_ap_wifi_ssid", "wifi_tether_ssid",
            "softap_ssid", "wifi_hotspot_ssid"
        )
        val passKeys = listOf(
            "wifi_ap_passwd", "wifi_ap_password", "wifi_ap_passphrase", "wifi_tether_password"
        )
        for (key in ssidKeys) {
            try { Settings.System.putString(context.contentResolver, key, ssid) } catch (_: Exception) {}
            try { Settings.Global.putString(context.contentResolver, key, ssid) } catch (_: Exception) {}
        }
        for (key in passKeys) {
            try { Settings.System.putString(context.contentResolver, key, password) } catch (_: Exception) {}
            try { Settings.Global.putString(context.contentResolver, key, password) } catch (_: Exception) {}
        }
    }

    private fun saveLastSsid(ssid: String) {
        prefs.edit().putString("last_ssid", ssid).apply()
    }

    private fun pwdKey(ssid: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(ssid.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "pwd_$hash"
    }

    // ── Connected clients ─────────────────────────────────────────────────────

    fun readConnectedClients(): List<ConnectedClient> {
        ensureClientListeners()
        val dumpsysHints = emptyMap<String, Pair<String?, String?>>() // dumpsys blocked on MIUI for apps
        val dhcpHints = readDhcpLeaseHints()
        val merged = LinkedHashMap<String, ConnectedClient>()

        fun enrich(raw: ConnectedClient): ConnectedClient {
            val mac = raw.macAddress?.uppercase()?.takeIf { it.length == 17 }
            val hint = mac?.let { dumpsysHints[it] }
            val dhcp = mac?.let { dhcpHints[it] }
            val hostname = listOf(raw.name, hint?.first, dhcp?.first)
                .firstOrNull { DeviceNameResolver.isRealHostname(it) }
            val ip = raw.ipAddress ?: hint?.second ?: dhcp?.second
            val vendor = DeviceNameResolver.lookupVendor(mac)
            val name = DeviceNameResolver.resolveDisplayName(
                mac = mac,
                ip = ip,
                hostname = hostname,
                cachedName = mac?.let { getCachedDeviceName(it) },
                vendor = vendor
            )
            if (hostname != null && mac != null) cacheDeviceName(mac, hostname)
            return ConnectedClient(name = name, macAddress = mac, ipAddress = ip, vendor = vendor)
        }

        fun add(raw: ConnectedClient) {
            val mac = raw.macAddress?.uppercase()?.takeIf { it.length == 17 }
            val key = mac ?: raw.ipAddress?.takeIf { it.isNotBlank() } ?: return
            val enriched = enrich(raw.copy(macAddress = mac ?: raw.macAddress))
            val existing = merged[key]
            merged[key] = if (existing == null) enriched else pickBetterClient(existing, enriched)
        }

        // 1. System API caches (TetheringEventCallback + SoftApCallback)
        tetheringClientsCache.forEach { add(it) }
        softApClientsCache.forEach { add(it) }
        refreshTetheringClientsCache()
        tetheringClientsCache.forEach { add(it) }
        softApClientsCache.forEach { add(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readClientsViaTetheringManager()?.forEach { add(it) }
        }
        readClientsViaSoftApApi().forEach { add(it) }
        readClientsViaWifiManagerProbe().forEach { add(it) }
        readClientsFromDumpsysTethering().forEach { add(it) }
        readClientsFromArp().forEach { add(it) }
        readClientsFromIpNeighOnAp().forEach { add(it) }

        // Subnet scan — last resort when APIs + ARP blocked (MIUI)
        if (merged.isEmpty() && isHotspotOn()) {
            scanHotspotSubnetClients().forEach { add(it) }
        }
        if (merged.isEmpty() && stableHotspotClients.isNotEmpty() && isHotspotOn()) {
            stableHotspotClients.forEach { add(it) }
        }

        val result = merged.values
            .filter { client ->
                val ip = client.ipAddress
                client.macAddress != null || (ip != null && DeviceNameResolver.isHotspotClientIp(ip))
            }
            .toList()

        if (result.isNotEmpty()) {
            stableHotspotClients = result
        } else if (stableHotspotClients.isNotEmpty() && isHotspotOn()) {
            Log.d(TAG, "readConnectedClients: using stable cache (${stableHotspotClients.size})")
            return stableHotspotClients
        }

        Log.d(TAG, "readConnectedClients: ${result.size} device(s) ${result.map { "${it.ipAddress}/${it.macAddress}" }} " +
            "(tethering=${tetheringClientsCache.size} softAp=${softApClientsCache.size})")
        return result
    }

    /** True when system callbacks supplied clients (not subnet guess). */
    fun hasReliableClientSource(): Boolean =
        tetheringClientsCache.isNotEmpty() || softApClientsCache.isNotEmpty()

    /** Probe only the hotspot AP subnet (wlan1 / ap0), never the phone's STA WiFi (wlan0). */
    private fun scanHotspotSubnetClients(): List<ConnectedClient> {
        synchronized(subnetScanLock) {
            val now = System.currentTimeMillis()
            if (now - lastSubnetScanAt < 45_000) {
                return subnetScanCache.ifEmpty { stableHotspotClients }
            }
            lastSubnetScanAt = now

            val localIps = getHotspotApIpv4Addresses()
            if (localIps.isEmpty()) {
                Log.d(TAG, "subnet scan: no hotspot AP interface")
                return stableHotspotClients
            }

            val prefixes = localIps.mapNotNull { ip ->
                val parts = ip.split(".")
                if (parts.size == 4) parts.take(3).joinToString(".") else null
            }.distinct()
            val skipIps = localIps.toSet()
            val found = java.util.Collections.synchronizedList(mutableListOf<ConnectedClient>())
            val pool = Executors.newFixedThreadPool(24)

            for (prefix in prefixes) {
                for (host in 1..254) {
                    val ip = "$prefix.$host"
                    if (ip in skipIps) continue
                    if (!DeviceNameResolver.isHotspotClientIp(ip)) continue
                    pool.submit {
                        if (isHostReachable(ip)) {
                            found.add(ConnectedClient(name = "", macAddress = null, ipAddress = ip))
                        }
                    }
                }
            }
            pool.shutdown()
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                pool.shutdownNow()
            }

            val result = found
                .distinctBy { it.ipAddress }
                .filter { client -> DeviceNameResolver.isHotspotClientIp(client.ipAddress ?: "") }
            subnetScanCache = result
            if (result.isNotEmpty()) {
                stableHotspotClients = result
            }
            Log.d(TAG, "subnet scan on $prefixes (ap=$localIps): ${result.size} device(s) ${result.map { it.ipAddress }}")
            return result.ifEmpty { stableHotspotClients }
        }
    }

    /** IPs on the soft-AP interface only — excludes wlan0 (connected home WiFi). */
    private fun getHotspotApIpv4Addresses(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .filter { iface -> DeviceNameResolver.isHotspotApInterface(iface.name) }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress }
                        .filter { DeviceNameResolver.isHotspotClientIp(it) }
                }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "getHotspotApIpv4Addresses: ${e.message}")
            emptyList()
        }
    }

    @Deprecated("Use getHotspotApIpv4Addresses", ReplaceWith("getHotspotApIpv4Addresses()"))
    private fun getHotspotLocalIpv4Addresses(): List<String> = getHotspotApIpv4Addresses()

    private fun isHostReachable(ip: String): Boolean {
        try {
            if (InetAddress.getByName(ip).isReachable(1200)) return true
        } catch (_: Exception) {}
        for (port in intArrayOf(443, 80, 53, 8080, 8443, 6200, 5555)) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 400) }
                return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun hasLocationForWifiClients(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            Log.w(TAG, "ACCESS_FINE_LOCATION required for hotspot client APIs")
            return false
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return true
        val on = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!on) Log.w(TAG, "Enable Location in phone Settings for device detection")
        return on
    }

    private fun readClientsFromDumpsysTethering(): List<ConnectedClient> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = buildString {
                append(proc.inputStream.bufferedReader().readText())
                append(proc.errorStream.bufferedReader().readText())
            }
            proc.waitFor()
            if (output.contains("Permission Denial", ignoreCase = true)) {
                return emptyList()
            }
            val parsed = DeviceNameResolver.parseTetheringClientInformation(output) +
                DeviceNameResolver.parseTetheringBpfHotspotIps(output)
            if (parsed.isNotEmpty()) {
                Log.d(TAG, "dumpsys tethering: ${parsed.size} client(s)")
            }
            parsed.distinctBy { it.macAddress ?: it.ipAddress }
        } catch (e: Exception) {
            Log.w(TAG, "dumpsys tethering: ${e.message}")
            emptyList()
        }
    }

    /** Try every WifiManager method that may return connected SoftAp clients. */
    private fun readClientsViaWifiManagerProbe(): List<ConnectedClient> {
        val results = mutableListOf<ConnectedClient>()
        for (method in wifiManager.javaClass.methods) {
            if (method.parameterCount != 0) continue
            val name = method.name
            if (!name.contains("Client", ignoreCase = true) &&
                !name.contains("Station", ignoreCase = true) &&
                !name.contains("SoftAp", ignoreCase = true)
            ) continue
            if (name.contains("register", ignoreCase = true) ||
                name.contains("unregister", ignoreCase = true)
            ) continue
            try {
                val value = method.invoke(wifiManager) ?: continue
                when (value) {
                    is Collection<*> -> value.mapNotNullTo(results) { it?.let { parseWifiClient(it) } }
                    is Int -> if (name.contains("Client") && value in 1..32) {
                        Log.d(TAG, "$name returned count=$value")
                    }
                }
            } catch (_: Exception) {}
        }
        return results.distinctBy { it.macAddress ?: it.ipAddress }
    }

    private fun readClientsFromIpNeighOnAp(): List<ConnectedClient> {
        val apIfaces = listOf("wlan1", "ap0", "softap0", "swlan0")
        val found = mutableListOf<ConnectedClient>()
        for (iface in apIfaces) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show", "dev", iface))
                val lines = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                lines.forEach { line -> parseIpNeighLine(line)?.let { found.add(it) } }
            } catch (_: Exception) {}
        }
        if (found.isEmpty()) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
                proc.inputStream.bufferedReader().readLines().forEach { line ->
                    parseIpNeighLine(line)?.let { found.add(it) }
                }
                proc.waitFor()
            } catch (_: Exception) {}
        }
        if (found.isNotEmpty()) Log.d(TAG, "ip neigh: ${found.size} client(s)")
        return found.distinctBy { it.macAddress ?: it.ipAddress }
    }

    private fun parseIpNeighLine(line: String): ConnectedClient? {
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null
        val ip = parts[0]
        if (!DeviceNameResolver.isHotspotClientIp(ip)) return null
        val macIdx = parts.indexOfFirst { it == "lladdr" } + 1
        if (macIdx <= 0 || macIdx >= parts.size) return null
        val mac = parts[macIdx].uppercase()
        val state = parts.last()
        if (state == "FAILED" || mac == "00:00:00:00:00:00") return null
        if (!mac.matches(Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}"""))) return null
        return ConnectedClient(name = "", macAddress = mac, ipAddress = ip, vendor = DeviceNameResolver.lookupVendor(mac))
    }

    private fun parseTetheredClient(client: Any): ConnectedClient? {
        return parseTetheredClientReflection(client)
    }

    private fun parseTetheredClientReflection(client: Any): ConnectedClient? {
        return try {
            val macRaw = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" }
                ?.invoke(client) ?: return null
            val mac = normalizeMac(macRaw.toString()) ?: return null

            val addresses = client.javaClass.methods.firstOrNull { it.name == "getAddresses" }
                ?.invoke(client) as? Collection<*>
            val ip = addresses?.firstOrNull()?.let { addr ->
                val inet = addr.javaClass.methods.firstOrNull { it.name == "getAddress" }?.invoke(addr)
                    ?: addr.javaClass.methods.firstOrNull { it.name == "getHostname" }?.invoke(addr)
                when (inet) {
                    is java.net.InetAddress -> inet.hostAddress
                    else -> inet?.toString()?.removePrefix("/")
                }
            }

            val hostname = client.javaClass.methods.firstOrNull { it.name == "getHostname" }
                ?.invoke(client) as? String

            ConnectedClient(
                name = hostname?.takeIf { DeviceNameResolver.isRealHostname(it) } ?: "",
                macAddress = mac,
                ipAddress = ip,
                vendor = DeviceNameResolver.lookupVendor(mac)
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseTetheredClient failed: ${e.message}")
            null
        }
    }

    private fun parseWifiClient(client: Any): ConnectedClient? {
        return parseWifiClientReflection(client)
    }

    private fun parseWifiClientReflection(client: Any): ConnectedClient? {
        return try {
            val macRaw = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" }
                ?.invoke(client) ?: return null
            val mac = normalizeMac(macRaw.toString()) ?: return null

            val ip = client.javaClass.methods.firstOrNull { it.name == "getInetAddress" }
                ?.invoke(client)?.let { inet ->
                    when (inet) {
                        is java.net.InetAddress -> inet.hostAddress
                        else -> inet.javaClass.methods.firstOrNull { m -> m.name == "getHostAddress" }
                            ?.invoke(inet) as? String
                    }
                }

            val hostname = client.javaClass.methods.firstOrNull {
                it.name == "getHostname" || it.name == "getName"
            }?.invoke(client) as? String

            ConnectedClient(
                name = hostname?.takeIf { DeviceNameResolver.isRealHostname(it) } ?: "",
                macAddress = mac,
                ipAddress = ip,
                vendor = DeviceNameResolver.lookupVendor(mac)
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseWifiClient failed: ${e.message}")
            null
        }
    }

    private fun normalizeMac(raw: String): String? {
        val cleaned = raw.uppercase()
            .replace("-", ":")
            .trim()
        val macRegex = Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}""")
        return macRegex.find(cleaned)?.value
    }

    /** WifiManager.getConnectedSoftApClients() — official API */
    private fun readClientsViaSoftApApi(): List<ConnectedClient> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        if (!hasLocationForWifiClients()) return emptyList()
        for (methodName in listOf("getConnectedSoftApClients", "getConnectedClients")) {
            try {
                val method = wifiManager.javaClass.getMethod(methodName)
                @Suppress("UNCHECKED_CAST")
                val clients = method.invoke(wifiManager) as? Collection<*> ?: continue
                val parsed = clients.mapNotNull { item -> item?.let { parseWifiClient(it) } }
                Log.d(TAG, "$methodName: ${parsed.size} client(s)")
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                Log.w(TAG, "$methodName: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun readClientsFromDumpsysWifi(): List<ConnectedClient> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "wifi"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            DeviceNameResolver.parseDumpsysWifiClients(output)
        } catch (_: Exception) { emptyList() }
    }

    private fun readClientsFromIpNeigh(): List<ConnectedClient> {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh", "show"))
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            lines.mapNotNull { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 5) return@mapNotNull null
                val ip = parts[0]
                if (!DeviceNameResolver.isHotspotClientIp(ip)) return@mapNotNull null
                val macIdx = parts.indexOfFirst { it == "lladdr" } + 1
                if (macIdx <= 0 || macIdx >= parts.size) return@mapNotNull null
                val mac = parts[macIdx].uppercase()
                val state = parts.last()
                if (state == "FAILED" || mac == "00:00:00:00:00:00") return@mapNotNull null
                if (!mac.matches(Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}"""))) return@mapNotNull null
                ConnectedClient(name = "", macAddress = mac, ipAddress = ip)
            }.also { clients ->
                if (clients.isNotEmpty()) Log.d(TAG, "ip neigh: ${clients.size} client(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ip neigh failed: ${e.message}")
            emptyList()
        }
    }

    private fun pickBetterClient(a: ConnectedClient, b: ConnectedClient): ConnectedClient {
        val better = if (DeviceNameResolver.nameQuality(b.name) > DeviceNameResolver.nameQuality(a.name)) b else a
        return better.copy(
            ipAddress = better.ipAddress ?: a.ipAddress,
            vendor = better.vendor ?: a.vendor
        )
    }

    private fun readDumpsysClientHints(): Map<String, Pair<String?, String?>> {
        val hints = mutableMapOf<String, Pair<String?, String?>>()
        for (cmd in listOf(
            arrayOf("dumpsys", "tethering"),
            arrayOf("dumpsys", "connectivity"),
            arrayOf("dumpsys", "wifi")
        )) {
            try {
                val proc = Runtime.getRuntime().exec(cmd)
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                DeviceNameResolver.parseDumpsysClients(output).forEach { (mac, triple) ->
                    hints[mac] = triple.first to triple.second
                }
            } catch (_: Exception) {}
        }
        return hints
    }

    private fun readDhcpLeaseHints(): Map<String, Pair<String?, String?>> {
        val paths = listOf(
            "/data/misc/dhcp/dnsmasq.leases",
            "/data/misc/wifi/hostapd/dhcp.leases",
            "/data/misc/wifi/dhcp.leases"
        )
        for (path in paths) {
            try {
                val file = java.io.File(path)
                if (!file.canRead()) continue
                return DeviceNameResolver.parseDhcpLeases(file.readText()).mapValues { it.value }
            } catch (_: Exception) {}
        }
        return emptyMap()
    }

    fun readClientCount(): Int = readConnectedClients().size

    private fun readClientsViaTetheringManager(): List<ConnectedClient>? {
        return try {
            val tm = context.getSystemService("tethering") ?: return null
            val method = (tm as Any).javaClass.methods.firstOrNull {
                it.name == "getTetheredClients" || it.name == "getConnectedTetheringClients"
            } ?: return null
            @Suppress("UNCHECKED_CAST")
            val clients = method.invoke(tm) as? Collection<*> ?: return emptyList()
            clients.mapNotNull { item -> item?.let { parseTetheredClient(it) } }
                .distinctBy { it.macAddress ?: it.ipAddress ?: it.name }
        } catch (e: Exception) {
            Log.w(TAG, "getTetheredClients: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseClientViaReflection(client: Any): ConnectedClient? {
        return try {
            val mac = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" }
                ?.invoke(client)?.toString()?.let { normalizeMac(it) }
            val addresses = client.javaClass.methods.firstOrNull { it.name == "getAddresses" }
                ?.invoke(client) as? Collection<*>
            val ip = addresses?.firstOrNull()?.let { addr ->
                addr.javaClass.methods.firstOrNull { it.name == "getHostAddress" }
                    ?.invoke(addr) as? String
            }
            val hostname = client.javaClass.methods.firstOrNull { it.name == "getHostname" }
                ?.invoke(client) as? String
            val vendor = DeviceNameResolver.lookupVendor(mac)
            if (mac.isNullOrBlank()) return null
            ConnectedClient(
                name = hostname?.takeIf { DeviceNameResolver.isRealHostname(it) } ?: "",
                macAddress = mac,
                ipAddress = ip,
                vendor = vendor
            )
        } catch (_: Exception) { null }
    }

    private fun readClientsFromArp(): List<ConnectedClient> {
        return try {
            val arpFile = java.io.File("/proc/net/arp")
            if (!arpFile.canRead()) return emptyList()
            arpFile.readLines().drop(1).mapNotNull { line ->
                val p = line.trim().split("\\s+".toRegex())
                if (p.size < 4) return@mapNotNull null
                val ip = p[0]
                val flags = p.getOrNull(2) ?: ""
                val mac = p[3]
                val iface = if (p.size >= 6) p[5] else ""
                val complete = flags == "0x2" || flags == "0x6"
                val onHotspot = DeviceNameResolver.isHotspotApInterface(iface) ||
                    DeviceNameResolver.isHotspotClientIp(ip)
                if (onHotspot && complete && mac != "00:00:00:00:00:00" && mac.length == 17) {
                    ConnectedClient(
                        name = "",
                        macAddress = mac.uppercase(),
                        ipAddress = ip,
                        vendor = DeviceNameResolver.lookupVendor(mac.uppercase())
                    )
                } else null
            }.distinctBy { it.macAddress ?: it.ipAddress }.also { clients ->
                if (clients.isNotEmpty()) Log.d(TAG, "arp: ${clients.size} client(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "arp: ${e.message}")
            emptyList()
        }
    }

    private fun isHotspotInterface(iface: String): Boolean {
        if (iface.isBlank()) return false
        val lower = iface.lowercase()
        return lower.startsWith("ap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("rndis") ||
            (lower.startsWith("wlan") && lower != "wlan0")
    }

    private fun String.cleanSsid(): String? =
        removePrefix("\"").removeSuffix("\"").trim().ifEmpty { null }
}
