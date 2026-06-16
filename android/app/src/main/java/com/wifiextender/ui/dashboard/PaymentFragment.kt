package com.wifiextender.ui.dashboard

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import com.wifiextender.BuildConfig
import com.wifiextender.databinding.FragmentPaymentBinding

class PaymentFragment : Fragment() {

    private var _binding: FragmentPaymentBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()

    companion object {
        private const val ARG_PLAN_ID = "planId"
        fun newInstance(planId: Long) = PaymentFragment().apply {
            arguments = Bundle().apply { putLong(ARG_PLAN_ID, planId) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val planId = arguments?.getLong(ARG_PLAN_ID) ?: return

        // Derive frontend URL from BASE_URL (same host, port 5173)
        val baseUrl = BuildConfig.BASE_URL // e.g. http://192.168.1.15:8080/api/
        val host = baseUrl.removePrefix("http://").removePrefix("https://").substringBefore(":")
        val frontendUrl = "http://$host:5173"

        binding.btnClose.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPaymentSuccess() {
                    requireActivity().runOnUiThread {
                        Snackbar.make(binding.root, "✅ Payment successful! Subscription activated.", Snackbar.LENGTH_LONG).show()
                        viewModel.loadHome()
                        viewModel.loadPlansAndSubs()
                        parentFragmentManager.popBackStack()
                    }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    // If redirected to dashboard after payment success
                    if (url.contains("/dashboard/subscription") || url.contains("payment/success")) {
                        requireActivity().runOnUiThread {
                            Snackbar.make(binding.root, "✅ Payment successful! Subscription activated.", Snackbar.LENGTH_LONG).show()
                            viewModel.loadHome()
                            viewModel.loadPlansAndSubs()
                            parentFragmentManager.popBackStack()
                        }
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // Inject the stored JWT so the web page is already logged in
                    val token = com.wifiextender.data.prefs.TokenManager(requireContext()).getAccessToken() ?: return
                    view.evaluateJavascript(
                        """
                        localStorage.setItem('accessToken', '$token');
                        if (window.__zustand_auth_store_set) {
                            window.__zustand_auth_store_set();
                        }
                        """.trimIndent(), null
                    )
                }
            }

            loadUrl("$frontendUrl/payment?planId=$planId")
        }
    }

    override fun onDestroyView() {
        binding.webView.destroy()
        super.onDestroyView()
        _binding = null
    }
}
