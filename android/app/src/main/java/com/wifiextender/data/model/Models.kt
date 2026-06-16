package com.wifiextender.data.model

import com.google.gson.annotations.SerializedName

// ── Auth ──────────────────────────────────────────────────────────────────────

data class LoginRequest(val email: String, val password: String)

data class RegisterRequest(val name: String, val email: String, val password: String)

data class RefreshRequest(val refreshToken: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserInfo
)

data class UserInfo(
    val id: Long,
    val name: String,
    val email: String,
    val role: String,
    val active: Boolean
)

// ── Plans ─────────────────────────────────────────────────────────────────────

data class Plan(
    val id: Long,
    val name: String,
    val description: String?,
    val price: Double,
    val planType: String,
    val durationDays: Int?,
    val maxDevices: Int,
    val unlimitedDevices: Boolean,
    val lifetime: Boolean,
    val active: Boolean,
    val popular: Boolean,
    val featureList: List<String>
)

// ── Subscriptions ─────────────────────────────────────────────────────────────

data class Subscription(
    val id: Long,
    val userId: Long,
    val userName: String,
    val userEmail: String,
    val plan: PlanSummary?,
    val status: String,
    val active: Boolean,
    val expired: Boolean,
    val lifetime: Boolean,
    val startsAt: String?,
    val expiresAt: String?,
    val activatedAt: String?,
    val maxDevices: Int
)

data class PlanSummary(
    val id: Long,
    val name: String,
    val planType: String,
    val price: Double,
    val maxDevices: Int,
    val unlimitedDevices: Boolean
)

// ── Licenses ──────────────────────────────────────────────────────────────────

data class License(
    val id: Long,
    val licenseKey: String,
    val status: String,
    val planName: String?,
    val machineId: String?,
    val machineLabel: String?,
    val bound: Boolean,
    val activatedAt: String?,
    val expiresAt: String?,
    val lifetime: Boolean,
    val expired: Boolean
)

// ── Devices ───────────────────────────────────────────────────────────────────

data class Device(
    val id: Long,
    val macAddress: String,
    val deviceName: String?,
    val deviceType: String,
    val ipAddress: String?,
    val vendor: String?,
    val signalStrength: Int?,
    val blocked: Boolean,
    val online: Boolean,
    val bytesSent: Long,
    val bytesReceived: Long,
    val totalBytes: Long,
    val lastSeen: String?,
    val connectedAt: String?
)

data class DeviceStats(
    val total: Long,
    val online: Long,
    val blocked: Long,
    val offline: Long,
    val totalBytesSent: Long,
    val totalBytesReceived: Long
)

data class DeviceReport(
    val macAddress: String,
    val deviceName: String?,
    val ipAddress: String?,
    val deviceType: String = "UNKNOWN",
    val vendor: String? = null,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val online: Boolean = true
)

data class BulkDeviceReport(
    val devices: List<DeviceReport>
)

// ── Admin Stats ───────────────────────────────────────────────────────────────

data class AdminStats(
    val totalUsers: Long,
    val totalPlans: Long,
    val activePlans: Long,
    val totalSubscriptions: Long,
    val activeSubscriptions: Long,
    val pendingSubscriptions: Long,
    val activeLicenses: Long,
    val activeHotspots: Long,
    val connectedDevices: Long,
    val totalRevenue: Double,
    val totalPayments: Long
)

// ── Error ─────────────────────────────────────────────────────────────────────

data class ApiError(
    val status: Int,
    val error: String,
    val message: String
)
