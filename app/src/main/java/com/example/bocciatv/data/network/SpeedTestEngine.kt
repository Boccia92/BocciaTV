package com.example.bocciatv.data.network

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit

object SpeedTestEngine {

    private const val TAG = "SpeedTestEngine"
    private const val PING_URL = "http://latteax.securitysc.shop/"

    // Alternative reliable URLs for speed testing (Cloudflare CDN 10MB file, Hetzner, OVH, GitHub)
    private val TEST_FILE_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=10000000",
        "https://speed.hetzner.de/10MB.bin",
        "https://proof.ovh.net/files/10Mb.dat",
        "https://github.com/Boccia92/BocciaTV/releases/download/v2.7/bocciatv.apk"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class SpeedResult(
        val pingMs: Long,
        val downloadMbps: Double,
        val qualityRating: String,
        val qualityColorHex: String
    )

    fun runSpeedTest(
        onProgress: (currentMbps: Double, percent: Int) -> Unit,
        onPingMeasured: (pingMs: Long) -> Unit,
        onComplete: (SpeedResult) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                // 1. Measure Ping Latency to IPTV Server (with fallback)
                val pingMs: Long
                val pingStartTime = SystemClock.elapsedRealtime()
                val pingRequest = Request.Builder()
                    .url(PING_URL)
                    .header("User-Agent", "IPTVSmartersPro/3.0.0 (Linux; Android TV)")
                    .build()

                pingMs = try {
                    val pingCall = client.newCall(pingRequest).execute()
                    pingCall.close()
                    val pingEndTime = SystemClock.elapsedRealtime()
                    (pingEndTime - pingStartTime).coerceAtLeast(10)
                } catch (e: Exception) {
                    Log.w(TAG, "Primary ping failed: ${e.message}")
                    val fallbackStart = SystemClock.elapsedRealtime()
                    try {
                        val cfReq = Request.Builder().url("https://1.1.1.1/").build()
                        val cfCall = client.newCall(cfReq).execute()
                        cfCall.close()
                        (SystemClock.elapsedRealtime() - fallbackStart).coerceAtLeast(10)
                    } catch (_: Exception) {
                        45L
                    }
                }
                onPingMeasured(pingMs)

                // 2. Measure Download Speed with fallback candidate URLs
                var response: Response? = null
                for (testUrl in TEST_FILE_URLS) {
                    try {
                        val request = Request.Builder()
                            .url(testUrl)
                            .header("User-Agent", "IPTVSmartersPro/3.0.0 (Linux; Android TV)")
                            .build()

                        val res = client.newCall(request).execute()
                        if (res.isSuccessful && res.body != null) {
                            response = res
                            break
                        } else {
                            res.close()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to connect to test URL $testUrl: ${e.message}")
                    }
                }

                if (response == null) {
                    onError("Impossibile connettersi al server per lo Speed Test")
                    return@Thread
                }

                val responseBody = response.body!!
                val contentLength = responseBody.contentLength().coerceAtLeast(1)
                val inputStream: InputStream = responseBody.byteStream()
                val buffer = ByteArray(32768)

                var bytesRead: Int
                var totalBytesRead = 0L
                val startTime = SystemClock.elapsedRealtime()

                var lastProgressReportTime = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    totalBytesRead += bytesRead
                    val currentTime = SystemClock.elapsedRealtime()
                    val elapsedTimeSec = (currentTime - startTime) / 1000.0

                    if (currentTime - lastProgressReportTime > 120 && elapsedTimeSec > 0) {
                        lastProgressReportTime = currentTime
                        val currentMbps = ((totalBytesRead * 8.0) / (elapsedTimeSec * 1_000_000.0))
                        val percent = if (contentLength > 1) {
                            ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        onProgress(currentMbps, percent)
                    }
                }

                inputStream.close()
                response.close()

                val totalTimeSec = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                val finalMbps = if (totalTimeSec > 0) {
                    ((totalBytesRead * 8.0) / (totalTimeSec * 1_000_000.0))
                } else {
                    0.0
                }

                val (rating, colorHex) = when {
                    finalMbps >= 25.0 -> Pair("Eccellente (4K Ultra HD)", "#4CAF50")
                    finalMbps >= 12.0 -> Pair("Buona (Full HD 1080p)", "#8BC34A")
                    finalMbps >= 5.0 -> Pair("Sufficiente (HD 720p)", "#FFC107")
                    else -> Pair("Lenta (Buffering / Rallentamenti)", "#FF5252")
                }

                val result = SpeedResult(
                    pingMs = pingMs,
                    downloadMbps = finalMbps,
                    qualityRating = rating,
                    qualityColorHex = colorHex
                )

                onComplete(result)

            } catch (e: Exception) {
                Log.e(TAG, "Speed test failed: ${e.message}")
                onError("Errore durante l'esecuzione dello Speed Test")
            }
        }.start()
    }
}
