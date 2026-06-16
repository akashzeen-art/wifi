package com.wifiextender.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.data.prefs.TokenManager
import com.wifiextender.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val user = TokenManager(requireContext()).getUser()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else      -> "Good evening"
        }
        binding.tvGreeting.text = "$greeting, ${user?.name?.split(" ")?.first()} 👋"

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadHome()
        }

        viewModel.loading.observe(viewLifecycleOwner) {
            binding.swipeRefresh.isRefreshing = it
        }

        viewModel.subscription.observe(viewLifecycleOwner) { sub ->
            if (sub != null && sub.active) {
                binding.cardSubscription.visibility = View.VISIBLE
                binding.tvPlanName.text = sub.plan?.name ?: "—"
                binding.tvPlanStatus.text = "ACTIVE"
                binding.tvPlanExpiry.text = if (sub.lifetime) "Lifetime — never expires"
                    else "Expires: ${sub.expiresAt?.take(10) ?: "—"}"
                binding.tvMaxDevices.text = if (sub.plan?.unlimitedDevices == true)
                    "∞ Unlimited devices" else "${sub.plan?.maxDevices} devices"
                binding.tvNoSub.visibility = View.GONE
            } else {
                binding.cardSubscription.visibility = View.GONE
                binding.tvNoSub.visibility = View.VISIBLE
            }
        }

        viewModel.licenses.observe(viewLifecycleOwner) { licenses ->
            val active = licenses.find { it.status == "ACTIVE" }
            if (active != null) {
                binding.cardLicense.visibility = View.VISIBLE
                binding.tvLicenseKey.text = active.licenseKey
                binding.tvLicensePlan.text = active.planName ?: "—"
                binding.tvLicenseExpiry.text = if (active.lifetime) "Lifetime"
                    else active.expiresAt?.take(10) ?: "—"
                binding.btnCopyKey.setOnClickListener {
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("License Key", active.licenseKey))
                    Snackbar.make(binding.root, "License key copied!", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                binding.cardLicense.visibility = View.GONE
            }
        }

        viewModel.deviceStats.observe(viewLifecycleOwner) { stats ->
            if (stats != null) {
                binding.tvOnlineDevices.text  = stats.online.toString()
                binding.tvBlockedDevices.text = stats.blocked.toString()
                binding.tvTotalDevices.text   = stats.total.toString()
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                Snackbar.make(binding.root, err, Snackbar.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        binding.btnStartHotspot.setOnClickListener {
            requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                com.wifiextender.R.id.bottom_nav
            ).selectedItemId = com.wifiextender.R.id.nav_hotspot
        }

        viewModel.loadHome()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
