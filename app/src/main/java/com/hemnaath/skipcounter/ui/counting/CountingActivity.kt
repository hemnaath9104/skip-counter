package com.hemnaath.skipcounter.ui.counting

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.hemnaath.skipcounter.R
import com.hemnaath.skipcounter.ui.results.ResultsActivity
import com.hemnaath.skipcounter.viewmodel.CounterViewModel

/**
 * CountingActivity: The main screen where users see live skip count and timer.
 *
 * Responsibility:
 * - Display skip count, timer, calibration progress
 * - Observe ViewModel LiveData
 * - Handle Start/Stop button interactions
 * - Navigate to ResultsActivity when session ends
 */
class CountingActivity : AppCompatActivity() {

    // ViewModel (lifecycle-aware, survives screen rotation)
    private val viewModel: CounterViewModel by viewModels()

    // UI Views
    private lateinit var skipCountDisplay: TextView
    private lateinit var timerDisplay: TextView
    private lateinit var calibrationProgress: ProgressBar
    private lateinit var calibrationContainer: android.widget.LinearLayout
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_counting)

        // Initialize UI views
        skipCountDisplay = findViewById(R.id.skipCountDisplay)
        timerDisplay = findViewById(R.id.timerDisplay)
        calibrationProgress = findViewById(R.id.calibrationProgress)
        calibrationContainer = findViewById(R.id.calibrationContainer)
        stopButton = findViewById(R.id.stopButton)

        // Start the counting session
        viewModel.startSession()

        // ==================== LiveData Observers ====================

        // Observer 1: Skip Count
        viewModel.skipCountLiveData.observe(this) { count ->
            skipCountDisplay.text = count.toString()
        }

        // Observer 2: Timer
        viewModel.elapsedTimeLiveData.observe(this) { time ->
            timerDisplay.text = time
        }

        // Observer 3: Calibration Progress
        viewModel.calibrationProgressLiveData.observe(this) { progress ->
            calibrationProgress.progress = progress

            // Hide calibration UI when done (progress == 100%)
            if (progress >= 100) {
                calibrationContainer.visibility = android.view.View.GONE
            }
        }

        // Observer 4: Session State
        viewModel.sessionStateLiveData.observe(this) { state ->
            when (state) {
                "calibrating" -> {
                    skipCountDisplay.text = "0"
                    calibrationContainer.visibility = android.view.View.VISIBLE
                }
                "counting" -> {
                    calibrationContainer.visibility = android.view.View.GONE
                }
                "finished" -> {
                    // Session ended, navigate to results
                    navigateToResults()
                }
            }
        }

        // Observer 5: Results (when session finishes)
        viewModel.sessionResultsLiveData.observe(this) { result ->
            if (result != null) {
                navigateToResults()
            }
        }

        // ==================== Button Listeners ====================

        stopButton.setOnClickListener {
            viewModel.stopSession()
        }
    }

    /**
     * Navigate to ResultsActivity with final stats.
     * Pass the results via Intent extras.
     */
    private fun navigateToResults() {
        val results = viewModel.sessionResultsLiveData.value
        if (results != null) {
            val intent = Intent(this, ResultsActivity::class.java)
            intent.putExtra("skipCount", results.skipCount)
            intent.putExtra("durationSec", results.durationSec)
            intent.putExtra("skipsPerMinute", results.skipsPerMinute)
            startActivity(intent)
            finish()  // Don't keep this activity in the stack
        }
    }

    /**
     * Called when user presses back button.
     * Warn them that stopping will end the session.
     */
    override fun onBackPressed() {
        // Optional: Show a dialog asking "Are you sure you want to stop?"
        // For now, just stop the session
        viewModel.stopSession()
    }
}