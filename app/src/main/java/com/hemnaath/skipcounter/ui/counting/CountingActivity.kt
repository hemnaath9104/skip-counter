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
import android.content.pm.PackageManager
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts

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

    private val audioPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                viewModel.startSession()
            } else {
                // Check if permission was permanently denied (user won't see the
                // system dialog again — must go to Settings manually)
                val permanentlyDenied = !shouldShowRequestPermissionRationale(
                    android.Manifest.permission.RECORD_AUDIO
                )

                if (permanentlyDenied) {
                    showSettingsDialog()
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "Microphone access is required to count skips.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_counting)
        onBackPressedDispatcher.addCallback(this) {
            viewModel.stopSession()
        }

        // Initialize UI views
        skipCountDisplay = findViewById(R.id.skipCountDisplay)
        timerDisplay = findViewById(R.id.timerDisplay)
        calibrationProgress = findViewById(R.id.calibrationProgress)
        calibrationContainer = findViewById(R.id.calibrationContainer)
        stopButton = findViewById(R.id.stopButton)

        // Start the counting session
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {

            viewModel.startSession()

        } else {

            audioPermission.launch(
                android.Manifest.permission.RECORD_AUDIO
            )
        }

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
     * Show a dialog explaining the app needs microphone access, then
     * open the app's Settings page so the user can grant it manually.
     * This is the only way to re-request the permission once Android
     * has stopped showing the system dialog (after prior denials).
     */
    private fun showSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Microphone Permission Needed")
            .setMessage("SkipCounter needs microphone access to detect skips. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
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
}