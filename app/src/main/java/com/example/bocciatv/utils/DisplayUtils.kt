package com.example.bocciatv.utils

import android.os.Build
import android.view.Window
import android.view.WindowManager

object DisplayUtils {
    fun maximizeRefreshRate(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                val display = window.windowManager.defaultDisplay
                @Suppress("DEPRECATION")
                val maxMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (maxMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = maxMode.modeId
                    window.attributes = params
                }
            } catch (_: Exception) {}
        }
    }
}
