package com.wifiextender.data.api

import com.wifiextender.BuildConfig
import com.wifiextender.data.model.RefreshRequest
import com.wifiextender.data.prefs.TokenManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private lateinit var tokenManager: TokenManager
    private lateinit var appContext: android.content.Context

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedApi: ApiService? = null

    fun init(tm: TokenManager, context: android.content.Context) {
        tokenManager = tm
        appContext = context.applicationContext
    }

    fun resetApi() {
        cachedBaseUrl = null
        cachedApi = null
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = tokenManager.getAccessToken()
                val req = if (token != null)
                    chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
                else chain.request()
                val response = chain.proceed(req)

                if (response.code == 401 && !req.url.encodedPath.contains("/auth/")) {
                    response.close()
                    val refreshToken = tokenManager.getRefreshToken()
                        ?: return@addInterceptor chain.proceed(req)
                    try {
                        val json = "{\"refreshToken\":\"$refreshToken\"}"
                        val refreshReq = Request.Builder()
                            .url("${ApiConfig.getBaseUrl(appContext)}auth/refresh")
                            .post(json.toRequestBody("application/json".toMediaType()))
                            .build()
                        val refreshResp = OkHttpClient.Builder().build().newCall(refreshReq).execute()
                        if (refreshResp.isSuccessful) {
                            val body = JSONObject(refreshResp.body!!.string())
                            val newAccess  = body.getString("accessToken")
                            val newRefresh = body.getString("refreshToken")
                            tokenManager.saveTokens(newAccess, newRefresh)
                            val newReq = req.newBuilder()
                                .header("Authorization", "Bearer $newAccess").build()
                            return@addInterceptor chain.proceed(newReq)
                        }
                    } catch (_: Exception) {}
                    tokenManager.clear()
                    return@addInterceptor chain.proceed(req)
                }
                response
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val apiService: ApiService
        get() {
            val baseUrl = ApiConfig.getBaseUrl(appContext)
            if (cachedApi == null || cachedBaseUrl != baseUrl) {
                cachedBaseUrl = baseUrl
                cachedApi = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(ApiService::class.java)
            }
            return cachedApi!!
        }
}
