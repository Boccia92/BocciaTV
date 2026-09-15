package com.example.bocciatv.ui.login

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.MainActivity
import com.example.bocciatv.R
import com.example.bocciatv.data.local.PrefsManager
import com.example.bocciatv.data.model.UserAuth
import com.example.bocciatv.data.network.NetworkModule
import com.example.bocciatv.utils.UpdateManager
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoginActivity : FragmentActivity() {
    private lateinit var prefs: PrefsManager
    private var isPassVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Set Italian Locale as default for the whole app
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = PrefsManager(this)

        if (prefs.user.isNotEmpty() && prefs.pass.isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Check for app updates on startup
        UpdateManager.checkForUpdates(this, isSilent = true)

        val etUser = findViewById<EditText>(R.id.et_user)
        val etPass = findViewById<EditText>(R.id.et_pass)
        val btnLogin = findViewById<Button>(R.id.btn_login)
        val btnShowPass = findViewById<ImageButton>(R.id.btn_show_pass)

        btnShowPass.setOnClickListener {
            isPassVisible = !isPassVisible
            if (isPassVisible) {
                etPass.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnShowPass.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                etPass.transformationMethod = PasswordTransformationMethod.getInstance()
                btnShowPass.setImageResource(android.R.drawable.ic_menu_view)
            }
            etPass.setSelection(etPass.text.length)
        }

        btnLogin.setOnClickListener {
            val u = etUser.text.toString().trim()
            val p = etPass.text.toString().trim()

            if (u.isEmpty() || p.isEmpty()) {
                Toast.makeText(this, "Inserisci le credenziali", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            NetworkModule.api.authenticate(u, p).enqueue(object : Callback<UserAuth> {
                override fun onResponse(call: Call<UserAuth>, response: Response<UserAuth>) {
                    val errorMessage = evaluateAuthResponse(response)
                    if (errorMessage != null) {
                        Toast.makeText(this@LoginActivity, errorMessage, Toast.LENGTH_LONG).show()
                    } else {
                        val auth = response.body()!!
                        prefs.user = u
                        prefs.pass = p
                        prefs.expDate = formatExpiryDate(auth.userInfo?.expDate)

                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    }
                }
                override fun onFailure(call: Call<UserAuth>, t: Throwable) {
                    val errorMsg = if (t is UnknownHostException || t is SocketTimeoutException || t is IOException) {
                        "Server non disponibile o offline"
                    } else {
                        "Errore di connessione: ${t.message}"
                    }
                    Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            })
        }
    }

    private fun evaluateAuthResponse(response: Response<UserAuth>): String? {
        if (!response.isSuccessful) {
            return if (response.code() >= 500) {
                "Server non disponibile o offline"
            } else {
                "Credenziali non corrette"
            }
        }

        val auth = response.body()
        val userInfo = auth?.userInfo

        if (userInfo == null || userInfo.username.isNullOrEmpty()) {
            return "Credenziali non corrette"
        }

        val status = userInfo.status ?: ""
        if (status.equals("Expired", ignoreCase = true) || status.equals("Disabled", ignoreCase = true)) {
            val expStr = formatExpiryDate(userInfo.expDate)
            return "Account scaduto (Scadenza: $expStr). Rinnova il tuo abbonamento per continuare."
        }

        val rawExp = userInfo.expDate
        if (rawExp != null && rawExp != "null" && rawExp != "") {
            val timestamp = rawExp.toString().toLongOrNull()
            if (timestamp != null && timestamp > 0) {
                val expMillis = if (timestamp > 1000000000000L) timestamp else timestamp * 1000
                if (expMillis < System.currentTimeMillis()) {
                    val expStr = formatExpiryDate(rawExp)
                    return "Account scaduto il $expStr. Rinnova il tuo abbonamento per continuare."
                }
            }
        }

        return null
    }

    private fun formatExpiryDate(rawExp: Any?): String {
        if (rawExp == null || rawExp == "null" || rawExp == "") return "Illimitata"
        return try {
            val timestamp = rawExp.toString().toLongOrNull()
            if (timestamp != null && timestamp > 0) {
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
}
