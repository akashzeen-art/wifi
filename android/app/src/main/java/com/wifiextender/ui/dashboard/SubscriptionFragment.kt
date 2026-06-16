package com.wifiextender.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.databinding.FragmentSubscriptionBinding
import com.wifiextender.ui.dashboard.adapter.PlanAdapter

class SubscriptionFragment : Fragment() {

    private var _binding: FragmentSubscriptionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private var planAdapter: PlanAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSubscriptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvPlans.layoutManager = LinearLayoutManager(requireContext())

        binding.swipeRefresh.setOnRefreshListener { viewModel.loadPlansAndSubs() }

        viewModel.allSubs.observe(viewLifecycleOwner) { subs ->
            binding.swipeRefresh.isRefreshing = false
            val active = subs.find { it.active }
            if (active != null) {
                binding.cardActiveSub.visibility = View.VISIBLE
                binding.tvActivePlan.text    = active.plan?.name ?: "—"
                binding.tvActiveStatus.text  = "ACTIVE"
                binding.tvActiveExpiry.text  = if (active.lifetime) "Lifetime — never expires"
                    else "Expires: ${active.expiresAt?.take(10) ?: "—"}"
                binding.tvActiveDevices.text = if (active.plan?.unlimitedDevices == true)
                    "∞ Unlimited devices" else "${active.plan?.maxDevices} devices"
            } else {
                binding.cardActiveSub.visibility = View.GONE
            }
            val currentPlanId = active?.plan?.id
            planAdapter = PlanAdapter(currentPlanId) { plan -> confirmRequest(plan) }
            binding.rvPlans.adapter = planAdapter
            viewModel.plans.value?.let { planAdapter?.submitList(it) }
        }

        viewModel.licenses.observe(viewLifecycleOwner) { licenses ->
            val active = licenses.find { it.status == "ACTIVE" }
            if (active != null) {
                binding.cardLicense.visibility   = View.VISIBLE
                binding.tvLicenseKey.text        = active.licenseKey
                binding.tvLicensePlan.text       = active.planName ?: "—"
                binding.tvLicenseExpiry.text     = if (active.lifetime) "Lifetime" else active.expiresAt?.take(10) ?: "—"
                binding.tvLicenseBound.text      = if (active.bound)
                    "Bound to: ${active.machineLabel ?: active.machineId?.take(12)}"
                    else "Not activated on any device yet"
                binding.btnCopyLicense.setOnClickListener {
                    val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("License Key", active.licenseKey))
                    Snackbar.make(binding.root, "License key copied!", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                binding.cardLicense.visibility = View.GONE
            }
        }

        viewModel.plans.observe(viewLifecycleOwner) { plans ->
            planAdapter?.submitList(plans)
        }

        viewModel.requestResult.observe(viewLifecycleOwner) { msg ->
            if (msg != null) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                viewModel.clearRequestResult()
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { err ->
            if (err != null) {
                Snackbar.make(binding.root, err, Snackbar.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.loadPlansAndSubs()
    }

    private fun confirmRequest(plan: com.wifiextender.data.model.Plan) {
        if (plan.planType == "FREE_TRIAL" || plan.price == 0.0) {
            // Free plans activate directly
            AlertDialog.Builder(requireContext())
                .setTitle("Activate ${plan.name}?")
                .setMessage(
                    "Duration: ${plan.durationDays} days\n" +
                    "Devices: ${if (plan.unlimitedDevices) "Unlimited" else plan.maxDevices.toString()}\n\n" +
                    "Your license key will be generated instantly."
                )
                .setPositiveButton("Activate") { _, _ -> viewModel.requestPlan(plan.id) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Paid plans open WebView payment screen
            parentFragmentManager.beginTransaction()
                .replace(com.wifiextender.R.id.fragment_container, PaymentFragment.newInstance(plan.id))
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
