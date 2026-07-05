package com.druk.lmplayground.tools

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Reports this device's hardware/system specs. All fields are readable without
 * any runtime permission. Mildly sensitive (device model, RAM, battery) so the
 * Settings row carries a "reads device specs" note; the tool stays OFF by
 * default (opt-in) like every tool.
 */
class HardwareInfoTool(private val context: Context) : Tool {
    override val name = "device_info"
    override val description = "Get this device's hardware and system information: manufacturer and model, Android version, chipset (SoC), CPU core count, supported ABIs, total and available RAM, internal storage total/free, and battery level and charging state. Useful to reason about what can run on this device."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        return try {
            val out = JSONObject()
            out.put("manufacturer", Build.MANUFACTURER)
            out.put("model", Build.MODEL)
            out.put("device", Build.DEVICE)
            out.put("android_version", Build.VERSION.RELEASE)
            out.put("api_level", Build.VERSION.SDK_INT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                out.put("soc_manufacturer", Build.SOC_MANUFACTURER)
                out.put("soc_model", Build.SOC_MODEL)
            }
            out.put("cpu_cores", Runtime.getRuntime().availableProcessors())
            out.put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))

            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                out.put("ram_total", humanBytes(mi.totalMem))
                out.put("ram_available", humanBytes(mi.availMem))
                out.put("ram_low", mi.lowMemory)
            }

            val stat = StatFs(context.filesDir.absolutePath)
            out.put("storage_total", humanBytes(stat.blockCountLong * stat.blockSizeLong))
            out.put("storage_free", humanBytes(stat.availableBlocksLong * stat.blockSizeLong))

            (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.let { bm ->
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (level in 0..100) out.put("battery_percent", level)
                out.put("battery_charging", bm.isCharging)
            }
            out.toString()
        } catch (e: Exception) {
            """{"error":"${(e.message ?: "Could not read device info").replace("\"", "'")}"}"""
        }
    }

    /** Human-readable size with 1 decimal (base-1024). */
    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }
}
