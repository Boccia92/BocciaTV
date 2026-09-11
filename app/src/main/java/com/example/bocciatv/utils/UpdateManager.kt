package com.example.bocciatv.utils

import android.R
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.data.model.UpdateInfo
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val UPDATE_URL = "https://raw.githubusercontent.com/Boccia92/BocciaTV/main/bocciatv_update.json"
    private const val USER_AGENT = "IPTVSmartersPro/3.0.0 (Linux; Android TV)"
    private const val TAG = "UpdateManager"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun checkForUpdates(activity: FragmentActivity, isSilent: Boolean = true) {
        val urlWithTimestamp = "$UPDATE_URL?t=${System.currentTimeMillis()}"
        val request = Request.Builder()
            .url(urlWithTimestamp)
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache, no-store")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Update check failed: ${e.message}")
                if (!isSilent) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Errore durante il controllo aggiornamenti", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    if (!isSilent) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Nessun aggiornamento disponibile", Toast.LENGTH_SHORT).show()
                        }
                    }
                    return
                }

                try {
                    val updateInfo = Gson().fromJson(body, UpdateInfo::class.java)
                    val currentVersion = getCurrentVersionCode(activity)

                    if (updateInfo != null && updateInfo.versionCode > currentVersion) {
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                showUpdateDialog(activity, updateInfo)
                            }
                        }
                    } else if (!isSilent) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "L'app è già aggiornata (v$currentVersion)", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing update info: ${e.message}")
                    if (!isSilent) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "Errore nell'analisi dei dati di aggiornamento", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }

    private fun showUpdateDialog(activity: FragmentActivity, update: UpdateInfo) {
        AlertDialog.Builder(activity, R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nuovo Aggiornamento!")
            .setMessage("È disponibile la versione ${update.versionCode}.\n\nNovità:\n${update.releaseNotes}")
            .setPositiveButton("Aggiorna") { _, _ ->
                downloadAndInstallApk(activity, update.apkUrl)
            }
            .setNegativeButton("Più tardi", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallApk(activity: FragmentActivity, apkUrl: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(activity, R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            setTitle("Download In Corso")
            setMessage("Download dell'aggiornamento in corso...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }

        val request = Request.Builder()
            .url(apkUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity.runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(activity, "Errore durante il download dell'aggiornamento", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body
                if (!response.isSuccessful || responseBody == null) {
                    activity.runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        Toast.makeText(activity, "Errore download: risposta server non valida", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val apkFile = File(activity.externalCacheDir ?: activity.cacheDir, "bocciatv_update.apk")
                try {
                    val contentLength = responseBody.contentLength()
                    val inputStream: InputStream = responseBody.byteStream()
                    val outputStream = FileOutputStream(apkFile)

                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val progress = ((totalBytesRead * 100) / contentLength).toInt()
                            activity.runOnUiThread {
                                progressDialog.progress = progress
                            }
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()

                    activity.runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        installApk(activity, apkFile)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving downloaded APK: ${e.message}")
                    activity.runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.dismiss()
                        Toast.makeText(activity, "Errore durante il salvataggio del file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun installApk(context: Context, apkFile: File) {
        if (!apkFile.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "Abilita l'autorizzazione 'Installa app sconosciute' per BocciaTV", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open install permissions settings: ${e.message}")
            }
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer intent: ${e.message}")
            Toast.makeText(context, "Impossibile avviare l'installazione", Toast.LENGTH_SHORT).show()
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }
}
