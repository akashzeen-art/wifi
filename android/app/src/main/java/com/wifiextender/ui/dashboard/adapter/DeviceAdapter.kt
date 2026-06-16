package com.wifiextender.ui.dashboard.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wifiextender.R
import com.wifiextender.data.model.Device
import com.wifiextender.databinding.ItemDeviceBinding
import com.wifiextender.utils.DeviceNameResolver

class DeviceAdapter(
    private val onBlockToggle: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            val label = DeviceNameResolver.formatDeviceLabel(
                deviceName = device.deviceName,
                vendor = device.vendor,
                ip = device.ipAddress,
                mac = device.macAddress
            )
            binding.tvDeviceName.text = label
            binding.tvMacAddress.text = device.macAddress
            binding.tvIpAddress.text = "IP: ${device.ipAddress ?: "—"}"
            binding.tvDeviceType.text = device.deviceType

            val vendor = device.vendor?.trim().orEmpty()
            if (vendor.isNotEmpty() && !label.contains(vendor, ignoreCase = true)) {
                binding.tvVendor.text = vendor
                binding.tvVendor.visibility = View.VISIBLE
            } else {
                binding.tvVendor.text = ""
                binding.tvVendor.visibility = View.GONE
            }

            val (statusText, statusColor) = when {
                device.blocked -> Pair("Blocked", R.color.red_500)
                device.online  -> Pair("Online",  R.color.green_500)
                else           -> Pair("Offline", R.color.gray_400)
            }
            binding.tvStatus.text = statusText
            binding.tvStatus.setTextColor(ContextCompat.getColor(binding.root.context, statusColor))
            binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(binding.root.context, statusColor))
            binding.tvBandwidth.text = "${formatBytes(device.bytesReceived)} ↓  ${formatBytes(device.bytesSent)} ↑"

            binding.btnBlock.text = if (device.blocked) "Unblock" else "Block"
            binding.btnBlock.setBackgroundColor(
                ContextCompat.getColor(binding.root.context,
                    if (device.blocked) R.color.green_500 else R.color.red_500)
            )
            binding.btnBlock.setOnClickListener { onBlockToggle(device) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1024        -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object DiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(a: Device, b: Device) = a.macAddress == b.macAddress
        override fun areContentsTheSame(a: Device, b: Device) = a == b
    }
}
