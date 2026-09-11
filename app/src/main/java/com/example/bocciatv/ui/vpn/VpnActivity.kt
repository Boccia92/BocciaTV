package com.example.bocciatv.ui.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.R
import com.example.bocciatv.data.model.VpnServer
import com.example.bocciatv.data.network.VpnGateRepository
import com.example.bocciatv.service.BocciaVpnService
import java.util.*

class VpnActivity : FragmentActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var spCountries: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnBack: Button

    private var serverList: List<VpnServer> = emptyList()
    private var selectedServer: VpnServer? = null
    private var pendingServerToConnect: VpnServer? = null

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BocciaVpnService.BROADCAST_VPN_STATE) {
                val state = intent.getStringExtra(BocciaVpnService.EXTRA_STATE)
                val message = intent.getStringExtra(BocciaVpnService.EXTRA_MESSAGE) ?: ""
                updateUiState(state, message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)

        tvStatus = findViewById(R.id.tv_vpn_status)
        pbLoading = findViewById(R.id.pb_vpn_loading)
        spCountries = findViewById(R.id.sp_countries)
        btnConnect = findViewById(R.id.btn_vpn_connect)
        btnDisconnect = findViewById(R.id.btn_vpn_disconnect)
        btnBack = findViewById(R.id.btn_vpn_back)

        btnBack.setOnClickListener { finish() }

        btnConnect.setOnClickListener {
            val server = selectedServer
            if (server == null) {
                Toast.makeText(this, "Nessun Paese selezionato", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prepareAndConnectVpn(server)
        }

        btnDisconnect.setOnClickListener {
            val disconnectIntent = Intent(this, BocciaVpnService::class.java).apply {
                action = BocciaVpnService.ACTION_DISCONNECT
            }
            startService(disconnectIntent)
        }

        loadVpnServers()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BocciaVpnService.BROADCAST_VPN_STATE)
        ContextCompat.registerReceiver(this, vpnReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val activeCountry = BocciaVpnService.activeCountry
        if (activeCountry.isNotEmpty()) {
            updateUiState(BocciaVpnService.STATE_CONNECTED, "VPN Connessa ($activeCountry)")
        } else {
            updateUiState(BocciaVpnService.STATE_DISCONNECTED, "Stato: Disconnessa")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(vpnReceiver)
        } catch (_: Exception) {}
    }

    private fun loadVpnServers() {
        pbLoading.visibility = View.VISIBLE
        tvStatus.text = "Caricamento lista server VPN..."
        tvStatus.setTextColor(Color.WHITE)

        VpnGateRepository.fetchVpnServers(
            onSuccess = { servers ->
                runOnUiThread {
                    pbLoading.visibility = View.GONE
                    serverList = servers
                    if (servers.isEmpty()) {
                        tvStatus.text = "Nessun server VPN disponibile"
                        tvStatus.setTextColor(Color.RED)
                        return@runOnUiThread
                    }

                    tvStatus.text = "Stato: Disconnessa"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))

                    val countryDisplayList = servers.map { "${it.flagEmoji} ${it.countryLong}" }
                    val adapter = ArrayAdapter(
                        this@VpnActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        countryDisplayList
                    )
                    spCountries.adapter = adapter

                    spCountries.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            selectedServer = serverList.getOrNull(position)
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {
                            selectedServer = null
                        }
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    pbLoading.visibility = View.GONE
                    tvStatus.text = "Errore: $error"
                    tvStatus.setTextColor(Color.RED)
                    Toast.makeText(this@VpnActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun prepareAndConnectVpn(server: VpnServer) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingServerToConnect = server
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService(server)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                pendingServerToConnect?.let { startVpnService(it) }
            } else {
                Toast.makeText(this, "Permesso VPN negato", Toast.LENGTH_SHORT).show()
            }
            pendingServerToConnect = null
        }
    }

    private fun startVpnService(server: VpnServer) {
        val serviceIntent = Intent(this, BocciaVpnService::class.java).apply {
            putExtra(BocciaVpnService.EXTRA_CONFIG_BASE64, server.configDataBase64)
            putExtra(BocciaVpnService.EXTRA_COUNTRY_NAME, "${server.flagEmoji} ${server.countryLong}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateUiState(state: String?, message: String) {
        when (state) {
            BocciaVpnService.STATE_CONNECTING -> {
                pbLoading.visibility = View.VISIBLE
                tvStatus.text = message.ifEmpty { "Connessione in corso..." }
                tvStatus.setTextColor(Color.parseColor("#FFC107"))
                btnConnect.visibility = View.GONE
                btnDisconnect.visibility = View.VISIBLE
            }
            BocciaVpnService.STATE_CONNECTED -> {
                pbLoading.visibility = View.GONE
                tvStatus.text = message.ifEmpty { "VPN Connessa" }
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                btnConnect.visibility = View.GONE
                btnDisconnect.visibility = View.VISIBLE
            }
            BocciaVpnService.STATE_ERROR -> {
                pbLoading.visibility = View.GONE
                tvStatus.text = message.ifEmpty { "Errore Connessione" }
                tvStatus.setTextColor(Color.RED)
                btnConnect.visibility = View.VISIBLE
                btnDisconnect.visibility = View.GONE
            }
            else -> {
                pbLoading.visibility = View.GONE
                tvStatus.text = if (message.isNotEmpty()) message else "Stato: Disconnessa"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                btnConnect.visibility = View.VISIBLE
                btnDisconnect.visibility = View.GONE
            }
        }
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 1002
    }
}
