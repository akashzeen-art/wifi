package com.wifiextender.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.data.api.RetrofitClient
import com.wifiextender.data.prefs.TokenManager
import com.wifiextender.databinding.ActivityRegisterBinding
import com.wifiextender.ui.dashboard.MainActivity

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var tokenManager: TokenManager
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenManager = TokenManager(this)
        RetrofitClient.init(tokenManager, this)

        binding.btnRegister.setOnClickListener {
            val name     = binding.etName.text.toString().trim()
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            val confirm  = binding.etConfirmPassword.text.toString()

            if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                showError("Please fill in all fields"); return@setOnClickListener
            }
            if (password != confirm) {
                showError("Passwords do not match"); return@setOnClickListener
            }
            if (password.length < 6) {
                showError("Password must be at least 6 characters"); return@setOnClickListener
            }

            viewModel.register(name, email, password) { access, refresh, user ->
                tokenManager.saveTokens(access, refresh)
                tokenManager.saveUser(user)
            }
        }

        binding.tvLogin.setOnClickListener { finish() }

        viewModel.state.observe(this) { state ->
            when (state) {
                is AuthState.Loading -> setLoading(true)
                is AuthState.Success -> {
                    setLoading(false)
                    startActivity(Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is AuthState.Error -> {
                    setLoading(false)
                    showError(state.message)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
    }
}
