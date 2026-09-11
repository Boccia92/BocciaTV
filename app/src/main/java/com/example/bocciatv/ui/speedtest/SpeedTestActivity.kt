package com.example.bocciatv.ui.speedtest

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.bocciatv.R
import com.example.bocciatv.data.network.SpeedTestEngine
import java.util.*

class SpeedTestActivity : FragmentActivity() {

    private lateinit var tvSpeed: TextView
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvPing: TextView
    private lateinit var tvRating: TextView
    private lateinit var btnStart: Button
    private lateinit var btnBack: Button

    private var isTesting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val locale = Locale.ITALY
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speed_test)

        tvSpeed = findViewById(R.id.tv_speed_mbps)
        pbProgress = findViewById(R.id.pb_speed_test)
        tvPing = findViewById(R.id.tv_ping_ms)
        tvRating = findViewById(R.id.tv_quality_rating)
        btnStart = findViewById(R.id.btn_start_speed_test)
        btnBack = findViewById(R.id.btn_speed_test_back)

        btnBack.setOnClickListener { finish() }

        btnStart.setOnClickListener {
            if (!isTesting) {
                startTest()
            }
        }
    }

    private fun startTest() {
        isTesting = true
        btnStart.isEnabled = false
        btnStart.text = "TEST IN CORSO..."
        pbProgress.progress = 0
        tvSpeed.text = "0.0 Mbps"
        tvPing.text = "Misurazione Ping in corso..."
        tvRating.text = "Qualità Streaming: In calcolo..."
        tvRating.setTextColor(Color.WHITE)

        SpeedTestEngine.runSpeedTest(
            onProgress = { currentMbps, percent ->
                runOnUiThread {
                    tvSpeed.text = String.format(Locale.US, "%.1f Mbps", currentMbps)
                    pbProgress.progress = percent
                }
            },
            onPingMeasured = { pingMs ->
                runOnUiThread {
                    tvPing.text = "Latenza (Ping): $pingMs ms"
                }
            },
            onComplete = { result ->
                runOnUiThread {
                    isTesting = false
                    btnStart.isEnabled = true
                    btnStart.text = "RIPETI SPEED TEST"
                    pbProgress.progress = 100
                    tvSpeed.text = String.format(Locale.US, "%.1f Mbps", result.downloadMbps)
                    tvPing.text = "Latenza (Ping): ${result.pingMs} ms"
                    tvRating.text = "Qualità Streaming: ${result.qualityRating}"
                    tvRating.setTextColor(Color.parseColor(result.qualityColorHex))
                }
            },
            onError = { error ->
                runOnUiThread {
                    isTesting = false
                    btnStart.isEnabled = true
                    btnStart.text = "AVVIA SPEED TEST"
                    tvRating.text = "Errore durante il test"
                    tvRating.setTextColor(Color.RED)
                    Toast.makeText(this@SpeedTestActivity, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
