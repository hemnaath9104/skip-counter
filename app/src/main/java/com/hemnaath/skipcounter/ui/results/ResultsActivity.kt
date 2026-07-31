package com.hemnaath.skipcounter.ui.results

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hemnaath.skipcounter.ui.counting.CountingActivity
import java.util.Locale

/**
 * ResultsActivity: Shows final session stats.
 *
 * Responsibility:
 * - Display skip count, duration, skips per minute
 * - Handle "Try Again" button to start a new session
 *
 * Migrated to Jetpack Compose.
 */
class ResultsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Retrieve results from Intent extras
        val skipCount = intent.getIntExtra("skipCount", 0)
        val durationSec = intent.getIntExtra("durationSec", 0)
        val skipsPerMinute = intent.getDoubleExtra("skipsPerMinute", 0.0)

        setContent {
            ResultsScreen(
                skipCount = skipCount,
                durationSec = durationSec,
                skipsPerMinute = skipsPerMinute,
                onTryAgainClick = {
                    // Start a new counting session
                    val intent = Intent(this, CountingActivity::class.java)
                    startActivity(intent)
                    finish() // Close this activity
                }
            )
        }
    }
}

/**
 * Composable UI for the Results screen.
 * Pure function of its inputs — easy to preview and test in isolation.
 */
@Composable
fun ResultsScreen(
    skipCount: Int,
    durationSec: Int,
    skipsPerMinute: Double,
    onTryAgainClick: () -> Unit
) {
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
                text = "Session Complete!",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32), // holo_green_dark
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Skip Count Stat
            StatRow(label = "Total Skips:", value = skipCount.toString())

            // Duration Stat
            StatRow(label = "Duration:", value = "$durationSec sec")

            // Skips Per Minute Stat
            StatRow(
                label = "Skips/Min:",
                value = String.format(Locale.US, "%.1f", skipsPerMinute),
                bottomPadding = 48.dp
            )

            // Try Again Button
            Button(
                onClick = onTryAgainClick,
                modifier = Modifier
                    .width(200.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32) // holo_green_dark
                )
            ) {
                Text(
                    text = "Try Again",
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Reusable row showing a stat label on the left and value on the right.
 * Mirrors the original XML's horizontal LinearLayout with weighted children.
 */
@Composable
private fun StatRow(
    label: String,
    value: String,
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding)
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            color = Color.DarkGray,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0D47A1), // holo_blue_dark
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

/**
 * Preview for Android Studio's design view.
 */
@Preview(showBackground = true)
@Composable
fun ResultsScreenPreview() {
    ResultsScreen(
        skipCount = 90,
        durationSec = 61,
        skipsPerMinute = 88.5,
        onTryAgainClick = {}
    )
}