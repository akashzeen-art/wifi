package com.wifiextender.data.api

import com.wifiextender.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/me")
    suspend fun me(): Response<UserInfo>

    // ── Plans ─────────────────────────────────────────────────────────────────
    @GET("plans")
    suspend fun getPlans(): Response<List<Plan>>

    @GET("plans/{id}")
    suspend fun getPlan(@Path("id") id: Long): Response<Plan>

    // ── Subscriptions ─────────────────────────────────────────────────────────
    @GET("subscriptions")
    suspend fun getSubscriptions(): Response<List<Subscription>>

    @GET("subscriptions/active")
    suspend fun getActiveSubscription(): Response<Subscription>

    @POST("subscriptions/request/{planId}")
    suspend fun requestPlan(@Path("planId") planId: Long): Response<Subscription>

    @GET("subscriptions/licenses")
    suspend fun getLicenses(): Response<List<License>>

    // ── Licenses ──────────────────────────────────────────────────────────────
    @GET("licenses")
    suspend fun getMyLicenses(): Response<List<License>>

    // ── Devices ───────────────────────────────────────────────────────────────
    @GET("devices")
    suspend fun getDevices(): Response<List<Device>>

    @GET("devices/stats")
    suspend fun getDeviceStats(): Response<DeviceStats>

    @PUT("devices/{id}/block")
    suspend fun toggleBlock(@Path("id") id: Long): Response<Device>

    @POST("devices/report")
    suspend fun reportDevice(@Body body: DeviceReport): Response<Device>

    @POST("devices/report/bulk")
    suspend fun reportDevicesBulk(@Body body: BulkDeviceReport): Response<List<Device>>

    // ── Hotspots ─────────────────────────────────────────────────────────────
    @GET("hotspots")
    suspend fun getHotspots(): Response<List<Map<String, Any>>>

    @GET("hotspots/active")
    suspend fun getActiveHotspot(): Response<Map<String, Any>>

    @POST("hotspots")
    suspend fun createHotspot(@Body body: Map<String, Any>): Response<Map<String, Any>>

    @POST("hotspots/{id}/start")
    suspend fun startHotspotById(@Path("id") id: Long): Response<Map<String, Any>>

    @POST("hotspots/{id}/stop")
    suspend fun stopHotspotById(@Path("id") id: Long): Response<Map<String, Any>>

    @PUT("hotspots/{id}")
    suspend fun updateHotspot(
        @Path("id") id: Long,
        @Body body: Map<String, Any>
    ): Response<Map<String, Any>>

    // ── Admin ─────────────────────────────────────────────────────────────────
    @GET("admin/stats")
    suspend fun getAdminStats(): Response<AdminStats>

    @GET("admin/users")
    suspend fun getAdminUsers(): Response<Map<String, Any>>

    @GET("admin/subscriptions")
    suspend fun getAdminSubscriptions(): Response<List<Subscription>>

    @POST("admin/subscriptions/{id}/activate")
    suspend fun activateSubscription(@Path("id") id: Long): Response<Subscription>

    @POST("admin/subscriptions/{id}/disable")
    suspend fun disableSubscription(@Path("id") id: Long): Response<Subscription>
}
