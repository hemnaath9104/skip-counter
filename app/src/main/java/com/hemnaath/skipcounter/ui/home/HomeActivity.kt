package com.hemnaath.skipcounter.ui.home

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.hemnaath.skipcounter.R
import com.hemnaath.skipcounter.ui.counting.CountingActivity

/**
 * HomeActivity: Entry point of the app.
 * Simple screen with title and "Start" button.
 *
 * Responsibility:
 * - Display welcome screen
 * - Navigate to CountingActivity when user taps "Start"
 */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val startButton: Button = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            // Navigate to CountingActivity
            val intent = Intent(this, CountingActivity::class.java)
            startActivity(intent)
            // Don't finish() - keep home in stack so back button returns here
        }
    }
}