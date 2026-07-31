package com.hemnaath.skipcounter.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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

/**
 * HomeActivity: Entry point of the app.
 * Simple screen with title and "Start" button.
 *
 * Responsibility:
 * - Display welcome screen
 * - Navigate to CountingActivity when user taps "Start"
 *
 * Migrated to Jetpack Compose. Uses ComponentActivity (not AppCompatActivity)
 * since pure-Compose screens don't need the AppCompat view hierarchy.
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HomeScreen(
                onStartClick = {
                    // Navigate to CountingActivity
                    val intent = Intent(this, CountingActivity::class.java)
                    startActivity(intent)
                    // Don't finish() - keep home in stack so back button returns here
                }
            )
        }
    }
}

/**
 * Composable UI for the Home screen.
 * Pure function of its inputs — easy to preview and test in isolation.
 *
 * @param onStartClick Called when the user taps the Start button.
 */
@Composable
fun HomeScreen(onStartClick: () -> Unit) {
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
            // App Title
            Text(
                text = "Skip Counter",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D47A1), // matches android:holo_blue_dark
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Subtitle
            Text(
                text = "Count skips using sound detection",
                fontSize = 16.sp,
                color = Color.DarkGray,
                modifier = Modifier.padding(bottom = 64.dp)
            )

            // Start Button
            Button(
                onClick = onStartClick,
                modifier = Modifier
                    .width(240.dp)
                    .height(60.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32) // matches android:holo_green_dark
                )
            ) {
                Text(
                    text = "Start",
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Preview for Android Studio's design view.
 * Lets you see the screen without running the app.
 */
@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    HomeScreen(onStartClick = {})
}