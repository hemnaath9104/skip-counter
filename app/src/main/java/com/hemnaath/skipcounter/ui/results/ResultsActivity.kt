package com.hemnaath.skipcounter.ui.results

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hemnaath.skipcounter.R
import com.hemnaath.skipcounter.ui.counting.CountingActivity

/**
 * ResultsActivity: Shows final session stats.
 *
 * Responsibility:
 * - Display skip count, duration, skips per minute
 * - Handle "Try Again" button to start a new session
 */
class ResultsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        // Retrieve results from Intent extras
        val skipCount = intent.getIntExtra("skipCount", 0)
        val durationSec = intent.getIntExtra("durationSec", 0)
        val skipsPerMinute = intent.getDoubleExtra("skipsPerMinute", 0.0)

        // Update UI with results
        findViewById<TextView>(R.id.skipCountResult).text = skipCount.toString()
        findViewById<TextView>(R.id.durationResult).text = "$durationSec sec"
        findViewById<TextView>(R.id.skipsPerMinuteResult).text = String.format("%.1f", skipsPerMinute)

        // "Try Again" button
        val tryAgainButton: Button = findViewById(R.id.tryAgainButton)
        tryAgainButton.setOnClickListener {
            // Start a new counting session
            val intent = Intent(this, CountingActivity::class.java)
            startActivity(intent)
            finish()  // Close this activity
        }
    }
}