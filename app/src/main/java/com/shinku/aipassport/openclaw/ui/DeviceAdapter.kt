package com.shinku.aipassport.openclaw.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.shinku.aipassport.openclaw.R

/**
 * 已发现/已连接的 BLE 设备列表。
 */
class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {

    data class DeviceItem(
        val name: String,
        val address: String,
        val state: String,
        /** 设备固件版本(如 "固件 1.11");设备没上报时为空串,整行隐藏。 */
        val firmware: String = "",
    )

    private val items = mutableListOf<DeviceItem>()

    fun set(list: List<DeviceItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun add(item: DeviceItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.name.text = d.name
        holder.address.text = d.address
        holder.state.text = d.state
        holder.firmware.text = d.firmware
        holder.firmware.visibility = if (d.firmware.isBlank()) View.GONE else View.VISIBLE
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
        val state: TextView = view.findViewById(R.id.deviceState)
        val firmware: TextView = view.findViewById(R.id.deviceFirmware)
    }
}
