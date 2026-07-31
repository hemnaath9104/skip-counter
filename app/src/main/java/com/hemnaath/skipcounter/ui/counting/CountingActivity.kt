package com.hemnaath.skipcounter.ui.counting

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemnaath.skipcounter.ui.results.ResultsActivity
import com.hemnaath.skipcounter.viewmodel.CounterViewModel

/**
 * CountingActivity: The main screen where users see live skip count and timer.
 *
 * Responsibility:
 * - Display skip count, timer, calibration progress
 * - Observe ViewModel LiveData (via Compose's observeAsState)
 * - Handle Start/Stop button interactions
 * - Navigate to ResultsActivity when session ends
 *
 * Migrated to Jetpack Compose using a HYBRID approach:
 * - UI rendering: pure Compose (CountingScreen composable)
 * - Permission request, Settings redirect, back-press handling: native Android APIs
 *   (ActivityResultLauncher, AlertDialog, OnBackPressedDispatcher)
 *
 * This hybrid pattern is intentional — these system-interop APIs already work
 * correctly and rewriting them in Compose equivalents adds migration risk with
 * no functional benefit.
 */
class CountingActivity : ComponentActivity() {

    // ViewModel (lifecycle-aware, survives screen rotation)
    private val viewModel: CounterViewModel by viewModels()

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
                    Toast.makeText(
                        this,
                        "Microphone access is required to count skips.",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            viewModel.stopSession()
        }

        // Start the counting session (or request permission first)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startSession()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            CountingScreen(
                viewModel = viewModel,
                onStopClick = { viewModel.stopSession() },
                onNavigateToResults = { navigateToResults() }
            )
        }
    }

    /**
     * Show a dialog explaining the app needs microphone access, then
     * open the app's Settings page so the user can grant it manually.
     * This is the only way to re-request the permission once Android
     * has stopped showing the system dialog (after prior denials).
     */
    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Microphone Permission Needed")
            .setMessage("SkipCounter needs microphone access to detect skips. Please enable it in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
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
            finish() // Don't keep this activity in the stack
        }
    }
}

/**
 * Composable UI for the Counting screen.
 *
 * Observes the ViewModel's LiveData directly using observeAsState(), which
 * converts each LiveData into Compose State. Compose automatically re-renders
 * only the parts of the UI that depend on values that changed — no manual
 * findViewById + .text= calls needed.
 */
@Composable
fun CountingScreen(
    viewModel: CounterViewModel,
    onStopClick: () -> Unit,
    onNavigateToResults: () -> Unit
) {
    // Convert each LiveData stream into Compose State
    val skipCount by viewModel.skipCountLiveData.observeAsState(0)
    val elapsedTime by viewModel.elapsedTimeLiveData.observeAsState("00:00")
    val calibrationProgress by viewModel.calibrationProgressLiveData.observeAsState(0)
    val sessionState by viewModel.sessionStateLiveData.observeAsState("idle")
    val sessionResult by viewModel.sessionResultsLiveData.observeAsState()

    // Side effect: navigate to Results when the session finishes.
    // LaunchedEffect re-runs only when its key (sessionResult) changes,
    // replacing the old observer-based navigateToResults() trigger.
    LaunchedEffect(sessionResult) {
        if (sessionResult != null) {
            onNavigateToResults()
        }
    }

    val isCalibrating = sessionState == "calibrating" && calibrationProgress < 100

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                text = "Skip Counter",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Calibration Progress (shown during first 2 seconds)
            if (isCalibrating) {
                Text(
                    text = "Calibrating...",
                    fontSize = 16.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LinearProgressIndicator(
                    progress = { calibrationProgress / 100f },
                    modifier = Modifier
                        .width(200.dp)
                        .padding(bottom = 24.dp)
                )
            }

            // Skip Count (Big Number)
            Text(
                text = if (sessionState == "calibrating") "0" else skipCount.toString(),
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D47A1), // holo_blue_dark
                modifier = Modifier.padding(vertical = 32.dp)
            )

            // Timer
            Text(
                text = elapsedTime,
                fontSize = 48.sp,
                color = Color.DarkGray,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Stop Button
            Button(
                onClick = onStopClick,
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C) // holo_red_dark
                )
            ) {
                Text(
                    text = "Stop",
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}