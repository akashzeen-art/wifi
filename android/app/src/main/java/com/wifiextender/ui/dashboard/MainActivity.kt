package com.wifiextender.ui.dashboard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.wifiextender.R
import com.wifiextender.data.api.RetrofitClient
import com.wifiextender.data.prefs.TokenManager
import com.wifiextender.databinding.ActivityMainBinding
import com.wifiextender.ui.auth.LoginActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenManager = TokenManager(this)
        RetrofitClient.init(tokenManager, this)

        if (!tokenManager.isLoggedIn()) {
            goToLogin(); return
        }

        // Request permissions for WiFi/hotspot features
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = perms.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }

        com.wifiextender.utils.HotspotManager(applicationContext).ensureClientListeners()

        // Create fragment instances once
        val homeFragment         = HomeFragment()
        val hotspotFragment      = HotspotFragment()
        val devicesFragment      = DevicesFragment()
        val subscriptionFragment = SubscriptionFragment()
        val profileFragment      = ProfileFragment()

        // Add all fragments, show only home
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, homeFragment, "home")
            .add(R.id.fragment_container, hotspotFragment, "hotspot")
            .add(R.id.fragment_container, devicesFragment, "devices")
            .add(R.id.fragment_container, subscriptionFragment, "subscription")
            .add(R.id.fragment_container, profileFragment, "profile")
            .hide(hotspotFragment)
            .hide(devicesFragment)
            .hide(subscriptionFragment)
            .hide(profileFragment)
            .commit()

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fm = supportFragmentManager.beginTransaction()
            listOf(homeFragment, hotspotFragment, devicesFragment, subscriptionFragment, profileFragment)
                .forEach { fm.hide(it) }
            when (item.itemId) {
                R.id.nav_home         -> fm.show(homeFragment)
                R.id.nav_hotspot      -> fm.show(hotspotFragment)
                R.id.nav_devices      -> fm.show(devicesFragment)
                R.id.nav_subscription -> fm.show(subscriptionFragment)
                R.id.nav_profile      -> fm.show(profileFragment)
            }
            fm.commit()
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun logout() {
        tokenManager.clear()
        goToLogin()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
