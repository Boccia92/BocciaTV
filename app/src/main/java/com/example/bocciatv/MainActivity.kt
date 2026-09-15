package com.example.bocciatv

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.Category
import com.example.bocciatv.data.model.UserAuth
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.ui.content.ContentActivity
import com.example.bocciatv.ui.settings.SettingsActivity
import com.example.bocciatv.utils.DisplayUtils
import com.example.bocciatv.utils.UpdateManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi
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

        val btnLive = findViewById<Button>(R.id.btn_live)
        btnLive.setOnClickListener { start(ContentActivity.TYPE_LIVE) }
        btnLive.requestFocus()

        findViewById<Button>(R.id.btn_vod).setOnClickListener { start(ContentActivity.TYPE_VOD) }
        findViewById<Button>(R.id.btn_series).setOnClickListener { start(ContentActivity.TYPE_SERIES) }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            showRefreshDialogAndExecute()
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Automatic initial check/refresh on app startup
        Toast.makeText(this, "Controllo e aggiornamento lista in corso...", Toast.LENGTH_SHORT).show()
        refreshAccountInfo(onResult = { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this@MainActivity, "Lista aggiornata!", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Check for app updates on startup
        UpdateManager.checkForUpdates(this, isSilent = true)

        // Exit confirmation dialog
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Esci")
                    .setMessage("Vuoi uscire dall'applicazione?")
                    .setPositiveButton("Sì") { _, _ ->
                        finishAffinity()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        findViewById<Button>(R.id.btn_live)?.requestFocus()
        UpdateManager.checkForUpdates(this, isSilent = true)
    }

    private fun showRefreshDialogAndExecute() {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            setTitle("Aggiornamento Lista")
            setMessage("Aggiornamento della lista in corso...")
            setCancelable(false)
            show()
        }

        Log.d("SYNC_DEBUG", "Database locale azzerato")
        prefs.clearCache()

        NetworkModule.api.getCategories(prefs.user, prefs.pass, "get_live_categories").enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                val cats = response.body() ?: emptyList()
                val catNames = cats.mapNotNull { it.name }.joinToString(", ")
                Log.d("SYNC_DEBUG", "Nuove categorie ricevute: $catNames")

                refreshAccountInfo(onResult = { success ->
                    runOnUiThread {
                        if (progressDialog.isShowing) {
                            progressDialog.dismiss()
                        }
                        val msg = if (success) "Lista aggiornata con successo!" else "Errore durante l'aggiornamento della lista."
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Aggiornamento Lista")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                })
            }
            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                runOnUiThread {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Errore di connessione durante l'aggiornamento", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun updateExpiryUI() {
        val exp = if (prefs.expDate.isEmpty()) "Verifica in corso..." else prefs.expDate
        tvExp.text = "Scadenza: $exp"
    }

    private fun refreshAccountInfo(onResult: ((Boolean) -> Unit)? = null) {
        NetworkModule.api.authenticate(prefs.user, prefs.pass).enqueue(object : Callback<UserAuth> {
            override fun onResponse(call: Call<UserAuth>, response: Response<UserAuth>) {
                val auth = response.body()
                if (response.isSuccessful && auth?.userInfo != null) {
                    val rawExp = auth.userInfo.expDate
                    val formattedDate = formatExpiryDate(rawExp)
                    prefs.expDate = formattedDate
                    runOnUiThread { updateExpiryUI() }
                    onResult?.invoke(true)
                } else {
                    onResult?.invoke(false)
                }
            }
            override fun onFailure(call: Call<UserAuth>, t: Throwable) {
                Log.e("BocciaTV", "Refresh Error: ${t.message}")
                onResult?.invoke(false)
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
