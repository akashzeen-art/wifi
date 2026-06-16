package com.wifiextender.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.R
import com.wifiextender.databinding.FragmentDevicesBinding
import com.wifiextender.ui.dashboard.adapter.DeviceAdapter
import com.wifiextender.utils.HotspotManager
import org.json.JSONObject

class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var adapter: DeviceAdapter
    private lateinit var hotspotManager: HotspotManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        hotspotManager = HotspotManager(requireContext())

        adapter = DeviceAdapter { device ->
            viewModel.toggleBlock(device.id)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener {
            refreshDevices()
        }

        viewModel.devices.observe(viewLifecycleOwner) { devices ->
            binding.swipeRefresh.isRefreshing = false
            val online = devices.filter { it.online && !it.blocked }
            adapter.submitList(devices.sortedByDescending { it.online && !it.blocked })
            binding.tvOnline.text = "Online: ${online.size}"
            binding.tvBlocked.text = "Blocked: ${devices.count { it.blocked }}"
            binding.tvEmpty.visibility = if (online.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.subscription.observe(viewLifecycleOwner) { sub ->
            val max = sub?.plan?.maxDevices ?: sub?.maxDevices ?: 0
            val unlimited = sub?.plan?.unlimitedDevices == true || max < 0
            binding.tvDeviceLimit.text = when {
                unlimited -> "Plan limit: ∞ Unlimited devices"
                max > 0 -> "Plan limit: $max device${if (max != 1) "s" else ""}"
                else -> "Plan limit: Subscribe to a plan"
            }
            binding.tvDeviceLimit.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (unlimited || max > 0) R.color.brand_primary else R.color.text_secondary
                )
            )
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                Snackbar.make(binding.root, err, Snackbar.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.upgradeRequired.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                showUpgradeDialog(parseUpgradeMessage(msg))
                viewModel.clearUpgradeRequired()
            }
        }

        viewModel.loadHome()
        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    private fun refreshDevices() {
        if (_binding == null) return
        binding.swipeRefresh.isRefreshing = true
        if (hotspotManager.isHotspotOn()) {
            hotspotManager.ensurePhoneSsidListener()
            if (!hasLocationPermission()) {
                requestLocationPermission()
                Snackbar.make(
                    binding.root,
                    "Enable Location permission & turn on Location in Settings to detect hotspot devices",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            viewModel.scanAndReportDevices(requireContext())
        } else {
            viewModel.loadDevices()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun parseUpgradeMessage(raw: String): String {
        return try {
            val json = JSONObject(raw)
            json.optString("message", raw)
        } catch (_: Exception) {
            raw
        }.replace("UPGRADE_REQUIRED: ", "")
    }

    private fun showUpgradeDialog(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ Device Limit Reached")
            .setMessage("$message\n\nUpgrade your plan to connect more devices.")
            .setPositiveButton("Upgrade Plan") { _, _ ->
                val nav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
                nav?.selectedItemId = R.id.nav_subscription
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            2001
        )
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
