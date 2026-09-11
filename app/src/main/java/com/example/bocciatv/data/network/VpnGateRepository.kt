package com.example.bocciatv.data.network

import android.util.Log
import com.example.bocciatv.data.model.VpnServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object VpnGateRepository {

    private const val TAG = "VpnGateRepository"
    private const val API_URL = "https://www.vpngate.net/api/iphone/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun fetchVpnServers(onSuccess: (List<VpnServer>) -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to fetch VPN Gate servers: ${e.message}")
                onError("Impossibile scaricare la lista server VPN")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    onError("Errore risposta server VPNGate: ${response.code}")
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    val rawServers = mutableListOf<VpnServer>()
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue
                        if (currentLine.startsWith("*") || currentLine.startsWith("#") || currentLine.isBlank()) {
                            continue
                        }

                        val tokens = currentLine.split(",")
                        if (tokens.size >= 15) {
                            val hostName = tokens[0].trim()
                            val ip = tokens[1].trim()
                            val score = tokens[2].trim().toLongOrNull() ?: 0L
                            val ping = tokens[3].trim().toIntOrNull() ?: 999
                            val speed = tokens[4].trim().toLongOrNull() ?: 0L
                            val countryLong = tokens[5].trim()
                            val countryShort = tokens[6].trim()
                            val configBase64 = tokens[14].trim()

                            if (configBase64.isNotEmpty() && ip.isNotEmpty() && countryLong.isNotEmpty()) {
                                rawServers.add(
                                    VpnServer(
                                        hostName = hostName,
                                        ip = ip,
                                        score = score,
                                        ping = ping,
                                        speed = speed,
                                        countryLong = countryLong,
                                        countryShort = countryShort,
                                        configDataBase64 = configBase64
                                    )
                                )
                            }
                        }
                    }

                    // Group by country and sort so Italy IT is first, followed by alphabetically
                    val bestByCountry = rawServers
                        .groupBy { it.countryShort.uppercase() }
                        .mapValues { entry -> entry.value.maxByOrNull { it.score }!! }
                        .values
                        .sortedWith { a, b ->
                            val aIsIt = a.countryShort.uppercase() == "IT"
                            val bIsIt = b.countryShort.uppercase() == "IT"
                            when {
                                aIsIt && !bIsIt -> -1
                                !aIsIt && bIsIt -> 1
                                else -> a.countryLong.compareTo(b.countryLong)
                            }
                        }

                    onSuccess(bestByCountry)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing VPNGate CSV: ${e.message}")
                    onError("Errore durante l'elaborazione dei server VPN")
                }
            }
        })
    }
}
