package com.example.bocciatv

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BocciaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTraceStr = sw.toString()

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)
                val dateStr = dateFormat.format(Date())

                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val versionName = packageInfo.versionName ?: "Unknown"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }

                val crashReport = """
                    Data/Ora: $dateStr
                    Versione App: $versionName ($versionCode)
                    Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
                    Versione Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
                    
                    Stack Trace:
                    $stackTraceStr
                """.trimIndent()

                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(crashReport)
                Log.e("BocciaApp", "Crash salvato in last_crash.txt")
            } catch (e: Exception) {
                Log.e("BocciaApp", "Errore salvataggio crash: ${e.message}")
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
