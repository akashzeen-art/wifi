package com.wifiextender.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wifiextender.data.api.RetrofitClient
import com.wifiextender.data.model.LoginRequest
import com.wifiextender.data.model.RegisterRequest
import kotlinx.coroutines.launch

sealed class AuthState {
    object Loading : AuthState()
    data class Success(val role: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {

    private val _state = MutableLiveData<AuthState>()
    val state: LiveData<AuthState> = _state

    fun login(
        email: String,
        password: String,
        serverUrl: String,
        onSave: (String, String, com.wifiextender.data.model.UserInfo) -> Unit
    ) {
        _state.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val resp = RetrofitClient.apiService.login(LoginRequest(email.trim(), password))
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    onSave(body.accessToken, body.refreshToken, body.user)
                    _state.value = AuthState.Success(body.user.role)
                } else {
                    _state.value = AuthState.Error("Invalid email or password")
                }
            } catch (e: Exception) {
                _state.value = AuthState.Error(
                    "Cannot reach server at $serverUrl\n" +
                        "• USB: adb reverse tcp:8080 tcp:8080\n" +
                        "• Same WiFi: use http://YOUR_MAC_IP:8080/api/"
                )
            }
        }
    }

    fun register(name: String, email: String, password: String,
                 onSave: (String, String, com.wifiextender.data.model.UserInfo) -> Unit) {
        _state.value = AuthState.Loading
        viewModelScope.launch {
            try {
                val resp = RetrofitClient.apiService.register(RegisterRequest(name.trim(), email.trim(), password))
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    onSave(body.accessToken, body.refreshToken, body.user)
                    _state.value = AuthState.Success(body.user.role)
                } else {
                    _state.value = AuthState.Error("Registration failed. Email may already be taken.")
                }
            } catch (e: Exception) {
                _state.value = AuthState.Error("Cannot connect to server. Check your network.")
            }
        }
    }
}
