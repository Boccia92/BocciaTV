package com.example.bocciatv.ui.settings

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.ui.login.LoginActivity
import com.example.bocciatv.utils.UpdateManager
import java.util.*

class SettingsActivity : FragmentActivity() {

    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PrefsManager(this)

        findViewById<TextView>(R.id.tv_user_info).text = "Username: ${prefs.user}"
        findViewById<TextView>(R.id.tv_exp_info).text = "Scadenza: ${prefs.expDate}"

        findViewById<Button>(R.id.btn_logout).setOnClickListener {
            prefs.clear()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener {
            finish()
        }

        Toast.makeText(this, "Controllo aggiornamenti...", Toast.LENGTH_SHORT).show()
        UpdateManager.checkForUpdates(this, isSilent = false)
    }
}
