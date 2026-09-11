package com.example.bocciatv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.UserAuth
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.content.ContentActivity
import com.example.bocciatv.ui.settings.SettingsActivity
import com.example.bocciatv.ui.vpn.VpnActivity
import com.example.bocciatv.utils.UpdateManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FragmentActivity() {
    private lateinit var prefs: PrefsManager
    private lateinit var tvExp: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PrefsManager(this)

        tvExp = findViewById(R.id.tv_exp)
        updateExpiryUI()

        findViewById<Button>(R.id.btn_live).setOnClickListener { start(ContentActivity.TYPE_LIVE) }
        findViewById<Button>(R.id.btn_vod).setOnClickListener { start(ContentActivity.TYPE_VOD) }
        findViewById<Button>(R.id.btn_series).setOnClickListener { start(ContentActivity.TYPE_SERIES) }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            Toast.makeText(this, "Aggiornamento in corso...", Toast.LENGTH_SHORT).show()
            refreshAccountInfo()
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_vpn).setOnClickListener {
            startActivity(Intent(this, VpnActivity::class.java))
        }

        // Silent refresh at startup to ensure date is correct
        refreshAccountInfo()

        // Check for app updates on startup
        UpdateManager.checkForUpdates(this, isSilent = true)
    }

    private fun updateExpiryUI() {
        val exp = if (prefs.expDate.isEmpty()) "Verifica in corso..." else prefs.expDate
        tvExp.text = "Scadenza: $exp"
    }

    private fun refreshAccountInfo() {
        NetworkModule.api.authenticate(prefs.user, prefs.pass).enqueue(object : Callback<UserAuth> {
            override fun onResponse(call: Call<UserAuth>, response: Response<UserAuth>) {
                val auth = response.body()
                if (response.isSuccessful && auth?.userInfo != null) {
                    val rawExp = auth.userInfo.expDate
                    val formattedDate = formatExpiryDate(rawExp)
                    prefs.expDate = formattedDate
                    runOnUiThread { updateExpiryUI() }
                }
            }
            override fun onFailure(call: Call<UserAuth>, t: Throwable) {
                Log.e("BocciaTV", "Refresh Error: ${t.message}")
            }
        })
    }

    private fun formatExpiryDate(rawExp: Any?): String {
        if (rawExp == null || rawExp == "null" || rawExp == "") return "Illimitata"
        
        return try {
            val timestamp = rawExp.toString().toLongOrNull()
            if (timestamp != null && timestamp > 0) {
                // If it's a timestamp (seconds or ms)
                val date = if (timestamp > 1000000000000L) Date(timestamp) else Date(timestamp * 1000)
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date)
            } else if (rawExp.toString().contains("/")) {
                rawExp.toString()
            } else {
                "Illimitata"
            }
        } catch (e: Exception) {
            rawExp.toString()
        }
    }

    private fun start(type: String) {
        val i = Intent(this, ContentActivity::class.java).apply {
            putExtra(ContentActivity.EXTRA_TYPE, type)
        }
        startActivity(i)
    }
}
