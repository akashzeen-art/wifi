package com.wifiextender.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wifiextender.data.prefs.TokenManager
import com.wifiextender.ui.dashboard.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.wifiextender.R.layout.activity_splash)
        val tokenManager = TokenManager(this)

        lifecycleScope.launch {
            delay(1200)
            val intent = if (tokenManager.isLoggedIn())
                Intent(this@SplashActivity, MainActivity::class.java)
            else
                Intent(this@SplashActivity, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
