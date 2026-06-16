package com.wifiextender.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wifiextender.data.api.RetrofitClient
import com.wifiextender.data.model.*
import com.wifiextender.utils.DeviceNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel : ViewModel() {

    private val api get() = RetrofitClient.apiService

    private val _subscription = MutableLiveData<Subscription?>()
    val subscription: LiveData<Subscription?> = _subscription

    private val _licenses = MutableLiveData<List<License>>()
    val licenses: LiveData<List<License>> = _licenses

    private val _deviceStats = MutableLiveData<DeviceStats?>()
    val deviceStats: LiveData<DeviceStats?> = _deviceStats

    private val _devices = MutableLiveData<List<Device>>()
    val devices: LiveData<List<Device>> = _devices

    private val _plans = MutableLiveData<List<Plan>>()
    val plans: LiveData<List<Plan>> = _plans

    private val _allSubs = MutableLiveData<List<Subscription>>()
    val allSubs: LiveData<List<Subscription>> = _allSubs

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _requestResult = MutableLiveData<String?>()
    val requestResult: LiveData<String?> = _requestResult

    // Device limit upgrade alert
    private val _upgradeRequired = MutableLiveData<String?>()
    val upgradeRequired: LiveData<String?> = _upgradeRequired

    fun loadHome() {
        _loading.value = true
        viewModelScope.launch {
            try {
                val subResp   = api.getActiveSubscription()
                val licResp   = api.getLicenses()
                val statsResp = api.getDeviceStats()
                if (subResp.isSuccessful)   _subscription.value = subResp.body()
                else _subscription.value = null
                if (licResp.isSuccessful)   _licenses.value = licResp.body() ?: emptyList()
                if (statsResp.isSuccessful) _deviceStats.value = statsResp.body()
            } catch (e: Exception) {
                _error.value = "Failed to load data"
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch {
            try {
                val resp = api.getDevices()
                if (resp.isSuccessful) _devices.value = resp.body() ?: emptyList()
                else _devices.value = emptyList()
            } catch (e: Exception) {
                _devices.value = emptyList()
            }
        }
    }

    // Direct ARP device count — no backend needed
    private val _arpDeviceCount = MutableLiveData<Int>(0)
    val arpDeviceCount: LiveData<Int> = _arpDeviceCount

    fun scanAndReportDevices(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val hotspotManager = com.wifiextender.utils.HotspotManager(context.applicationContext)
                val clients = withContext(Dispatchers.IO) { hotspotManager.readConnectedClients() }
                    .filter { client ->
                        client.macAddress != null ||
                            client.ipAddress?.let { DeviceNameResolver.isHotspotClientIp(it) } == true
                    }

                val reports = clients.mapNotNull { client ->
                    val mac = client.macAddress?.trim()?.uppercase()?.takeIf { it.length == 17 }
                        ?: client.ipAddress?.takeIf { it.isNotBlank() }?.let { DeviceNameResolver.pseudoMacFromIp(it) }
                        ?: return@mapNotNull null
                    val displayName = DeviceNameResolver.formatDeviceLabel(
                        deviceName = client.name,
                        vendor = client.vendor,
                        ip = client.ipAddress,
                        mac = mac
                    )
                    DeviceReport(
                        macAddress = mac,
                        deviceName = displayName,
                        ipAddress = client.ipAddress,
                        vendor = client.vendor ?: DeviceNameResolver.lookupVendor(mac),
                        deviceType = "UNKNOWN",
                        online = true
                    )
                }

                if (reports.isNotEmpty()) {
                    _arpDeviceCount.postValue(reports.size)
                    _devices.postValue(reports.toDeviceList())
                }

                // Empty bulk report marks every device offline on the server — skip when scan found nothing
                if (reports.isEmpty()) return@launch

                try {
                    val resp = api.reportDevicesBulk(BulkDeviceReport(reports))
                    if (!resp.isSuccessful) {
                        val errorBody = resp.errorBody()?.string() ?: ""
                        when {
                            errorBody.contains("UPGRADE_REQUIRED") || resp.code() == 403 || resp.code() == 409 ->
                                _upgradeRequired.postValue(errorBody.ifBlank { "Device limit reached for your plan." })
                            else ->
                                _error.postValue("Could not sync devices to server. Showing local scan.")
                        }
                    } else {
                        try {
                            val devResp = api.getDevices()
                            if (devResp.isSuccessful) {
                                val fromApi = devResp.body() ?: emptyList()
                                _devices.postValue(mergeDeviceNames(reports, fromApi))
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    _error.postValue("Could not sync devices to server. Showing local scan.")
                }
            } catch (_: Exception) {}
        }
    }

    private fun List<DeviceReport>.toDeviceList(): List<Device> =
        mapIndexed { index, d ->
            Device(
                id = index.toLong(),
                macAddress = d.macAddress,
                deviceName = d.deviceName ?: DeviceNameResolver.formatDeviceLabel(null, d.vendor, d.ipAddress, d.macAddress),
                deviceType = d.deviceType,
                ipAddress = d.ipAddress,
                vendor = d.vendor,
                signalStrength = null,
                blocked = false,
                online = d.online,
                bytesSent = 0,
                bytesReceived = 0,
                totalBytes = 0,
                lastSeen = null,
                connectedAt = null
            )
        }

    /** Keep richer local scan names; never wipe UI when API returns empty */
    private fun mergeDeviceNames(local: List<DeviceReport>, fromApi: List<Device>): List<Device> {
        val localDevices = local.toDeviceList()
        if (fromApi.isEmpty()) return localDevices
        if (local.isEmpty()) return fromApi

        val localByMac = local.associateBy { it.macAddress.uppercase() }
        val mergedApi = fromApi.map { apiDevice ->
            val scanned = localByMac[apiDevice.macAddress.uppercase()] ?: return@map apiDevice
            val localName = scanned.deviceName.orEmpty()
            val apiName = apiDevice.deviceName.orEmpty()
            val betterName = if (DeviceNameResolver.nameQuality(localName) > DeviceNameResolver.nameQuality(apiName)) {
                localName
            } else {
                DeviceNameResolver.formatDeviceLabel(
                    apiName, apiDevice.vendor ?: scanned.vendor, apiDevice.ipAddress, apiDevice.macAddress
                )
            }
            apiDevice.copy(
                deviceName = betterName,
                vendor = apiDevice.vendor ?: scanned.vendor,
                ipAddress = apiDevice.ipAddress ?: scanned.ipAddress,
                online = apiDevice.online || scanned.online
            )
        }

        val apiMacs = fromApi.map { it.macAddress.uppercase() }.toSet()
        val localOnly = localDevices.filter { it.macAddress.uppercase() !in apiMacs }
        return mergedApi + localOnly
    }

    fun toggleBlock(deviceId: Long) {
        viewModelScope.launch {
            try {
                val resp = api.toggleBlock(deviceId)
                if (resp.isSuccessful) {
                    val updated = resp.body() ?: return@launch
                    _devices.value = _devices.value?.map { if (it.id == deviceId) updated else it }
                }
            } catch (e: Exception) {
                _error.value = "Failed to update device"
            }
        }
    }

    fun loadPlansAndSubs() {
        viewModelScope.launch {
            try {
                val planResp = api.getPlans()
                val subResp  = api.getSubscriptions()
                val licResp  = api.getLicenses()
                val activeSub = api.getActiveSubscription()
                if (planResp.isSuccessful) _plans.value = planResp.body() ?: emptyList()
                if (subResp.isSuccessful)  _allSubs.value = subResp.body() ?: emptyList()
                if (licResp.isSuccessful)  _licenses.value = licResp.body() ?: emptyList()
                if (activeSub.isSuccessful) _subscription.value = activeSub.body()
                else _subscription.value = null
            } catch (e: Exception) {
                _error.value = "Failed to load plans"
            }
        }
    }

    fun requestPlan(planId: Long) {
        viewModelScope.launch {
            try {
                val resp = api.requestPlan(planId)
                if (resp.isSuccessful) {
                    _requestResult.value = "✅ Plan activated! Your license key is ready."
                    loadPlansAndSubs()
                    loadHome()
                } else {
                    _requestResult.value = "Failed to activate plan"
                }
            } catch (e: Exception) {
                _requestResult.value = "Network error. Please try again."
            }
        }
    }

    fun clearRequestResult() { _requestResult.value = null }
    fun clearError() { _error.value = null }
    fun clearUpgradeRequired() { _upgradeRequired.value = null }

    /** Sync real device hotspot SSID/password to backend when hotspot becomes active */
    fun syncHotspotCredentials(ssid: String, password: String, maxClients: Int) {
        if (ssid.isBlank() || password.length < 8) return
        viewModelScope.launch {
            try {
                val activeResp = api.getActiveHotspot()
                val body = mapOf(
                    "ssid" to ssid,
                    "password" to password,
                    "maxClients" to maxClients.coerceAtLeast(1)
                )
                if (activeResp.isSuccessful && activeResp.body() != null) {
                    val id = (activeResp.body()!!["id"] as? Number)?.toLong() ?: return@launch
                    api.updateHotspot(id, body)
                } else {
                    val createResp = api.createHotspot(body)
                    if (createResp.isSuccessful) {
                        val id = (createResp.body()?.get("id") as? Number)?.toLong() ?: return@launch
                        api.startHotspotById(id)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
