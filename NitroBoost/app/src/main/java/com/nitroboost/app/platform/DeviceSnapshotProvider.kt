package com.nitroboost.app.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.nitroboost.app.core.telemetry.DeviceSnapshot

/** Best-effort static device/display metadata from public Android APIs only. */
object DeviceSnapshotProvider {

    @Suppress("DEPRECATION")
    fun read(context: Context): DeviceSnapshot {
        val display = try {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        } catch (_: Exception) {
            null
        }
        var width: Int? = null
        var height: Int? = null
        if (display != null) {
            try {
                val metrics = DisplayMetrics()
                display.getRealMetrics(metrics)
                width = metrics.widthPixels.takeIf { it > 0 }
                height = metrics.heightPixels.takeIf { it > 0 }
            } catch (_: Exception) {
                // Fall back to the display's current mode below.
            }
            if (width == null || height == null) {
                try {
                    val mode = display.mode
                    width = width ?: mode.physicalWidth.takeIf { it > 0 }
                    height = height ?: mode.physicalHeight.takeIf { it > 0 }
                } catch (_: Exception) {
                    // Resolution is optional on ROMs that hide the display.
                }
            }
        }
        val refreshRate = display?.refreshRate
            ?.toDouble()
            ?.takeIf { it.isFinite() && it > 0.0 }

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        } else null
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socModel = socModel,
            androidApi = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            gpuModel = null,
            renderer = null,
            displayWidthPx = width,
            displayHeightPx = height,
            refreshRateHz = refreshRate
        )
    }
}
