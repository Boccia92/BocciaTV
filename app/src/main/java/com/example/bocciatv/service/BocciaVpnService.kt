package com.example.bocciatv.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class BocciaVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (ACTION_DISCONNECT == action) {
            disconnectVpn()
            return START_NOT_STICKY
        }

        val configBase64 = intent?.getStringExtra(EXTRA_CONFIG_BASE64)
        val countryName = intent?.getStringExtra(EXTRA_COUNTRY_NAME) ?: "VPN"

        if (configBase64.isNullOrEmpty()) {
            Log.e(TAG, "No OpenVPN config provided")
            broadcastStatus(STATE_ERROR, "Configurazione VPN non valida")
            return START_NOT_STICKY
        }

        val notification = createNotification("Connessione a VPN $countryName in corso...")
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground notification: ${e.message}")
        }

        Thread {
            connectVpn(configBase64, countryName)
        }.start()

        return START_STICKY
    }

    private fun connectVpn(configBase64: String, countryName: String) {
        try {
            broadcastStatus(STATE_CONNECTING, "Connessione VPN $countryName in corso...")

            val ovpnData = Base64.decode(configBase64, Base64.DEFAULT)
            val ovpnFile = File(cacheDir, "vpn_profile.ovpn")
            FileOutputStream(ovpnFile).use { fos ->
                fos.write(ovpnData)
            }

            val builder = Builder().apply {
                addAddress("10.8.0.2", 24)
                addRoute("0.0.0.0", 0)
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")
                setSession("BocciaTV VPN - $countryName")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                }
            }

            vpnInterface?.close()
            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isConnected = true
                activeCountry = countryName
                try {
                    val connectedNotification = createNotification("VPN Connessa ($countryName)")
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, connectedNotification)
                } catch (_: Exception) {}

                broadcastStatus(STATE_CONNECTED, "VPN Connessa ($countryName)")
                Log.i(TAG, "BocciaTV VPN established successfully for $countryName")
            } else {
                broadcastStatus(STATE_ERROR, "Impossibile stabilire l'interfaccia VPN")
            }

        } catch (e: Exception) {
            Log.e(TAG, "VPN connection error: ${e.message}")
            broadcastStatus(STATE_ERROR, "Errore connessione VPN: ${e.message}")
        }
    }

    private fun disconnectVpn() {
        Thread {
            try {
                vpnInterface?.close()
                vpnInterface = null
                isConnected = false
                activeCountry = ""
                broadcastStatus(STATE_DISCONNECTED, "VPN Disconnessa")
                Log.i(TAG, "BocciaTV VPN disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface: ${e.message}")
            } finally {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                } catch (_: Exception) {}
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        disconnectVpn()
        super.onDestroy()
    }

    private fun broadcastStatus(state: String, message: String) {
        val intent = Intent(BROADCAST_VPN_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_ACTIVE_COUNTRY, activeCountry)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BocciaTV VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BocciaTV VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BocciaVpnService"
        private const val CHANNEL_ID = "bocciatv_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.example.bocciatv.VPN_CONNECT"
        const val ACTION_DISCONNECT = "com.example.bocciatv.VPN_DISCONNECT"

        const val EXTRA_CONFIG_BASE64 = "extra_config_base64"
        const val EXTRA_COUNTRY_NAME = "extra_country_name"

        const val BROADCAST_VPN_STATE = "com.example.bocciatv.VPN_STATE_CHANGED"
        const val EXTRA_STATE = "extra_vpn_state"
        const val EXTRA_MESSAGE = "extra_vpn_message"
        const val EXTRA_ACTIVE_COUNTRY = "extra_active_country"

        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_ERROR = "ERROR"

        var activeCountry: String = ""
            private set
    }
}
