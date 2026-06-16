package com.wifiextender.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.wifiextender.data.prefs.TokenManager
import com.wifiextender.databinding.FragmentProfileBinding
import com.wifiextender.ui.auth.LoginActivity

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tokenManager = TokenManager(requireContext())
        val user = tokenManager.getUser()

        binding.tvName.text  = user?.name ?: "—"
        binding.tvEmail.text = user?.email ?: "—"
        binding.tvRole.text  = user?.role ?: "USER"
        binding.tvAvatar.text = user?.name?.firstOrNull()?.uppercaseChar()?.toString() ?: "U"

        if (user?.role == "ADMIN") {
            binding.tvRole.setBackgroundResource(com.wifiextender.R.drawable.bg_badge_admin)
        }

        binding.tvAppVersion.text = "WiFiExtender v1.0.0"
        binding.tvBackendUrl.text = com.wifiextender.data.api.ApiConfig.getBaseUrl(requireContext())

        binding.btnLogout.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Sign Out")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton("Sign Out") { _, _ ->
                    tokenManager.clear()
                    startActivity(Intent(requireContext(), LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
