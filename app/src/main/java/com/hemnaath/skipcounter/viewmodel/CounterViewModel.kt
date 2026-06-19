package com.hemnaath.skipcounter.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.hemnaath.skipcounter.audio.AudioEngine
import com.hemnaath.skipcounter.repository.SkipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CounterViewModel: Business logic for skip counting session.
 *
 * Responsibility:
 * - Manage skip count and timer state
 * - Coordinate AudioEngine (start/stop detection)
 * - Coordinate SkipRepository (save sessions)
 * - Expose LiveData for UI observers
 *
 * Does NOT: touch UI directly, handle audio processing (that's AudioEngine)
 *
 * Flow:
 * UI calls startSession() → ViewModel starts AudioEngine + timer → Updates LiveData
 * UI calls stopSession() → ViewModel stops AudioEngine, saves to DB → Final results
 */
class CounterViewModel(application: Application) : AndroidViewModel(application) {

    // ==================== Dependencies ====================
    private val audioEngine = AudioEngine()
    private val repository = SkipRepository(application)

    // ==================== UI State (LiveData) ====================
    // Skip count (updated by AudioEngine callback)
    private val _skipCountLiveData = MutableLiveData<Int>(0)
    val skipCountLiveData: LiveData<Int> = _skipCountLiveData

    // Timer: elapsed time in MM:SS format
    private val _elapsedTimeLiveData = MutableLiveData<String>("00:00")
    val elapsedTimeLiveData: LiveData<String> = _elapsedTimeLiveData

    // Calibration progress (0-100%) shown during setup
    private val _calibrationProgressLiveData = MutableLiveData<Int>(0)
    val calibrationProgressLiveData: LiveData<Int> = _calibrationProgressLiveData

    // Session state: "idle", "calibrating", "counting", "finished"
    private val _sessionStateLiveData = MutableLiveData<String>("idle")
    val sessionStateLiveData: LiveData<String> = _sessionStateLiveData

    // Results after session ends
    private val _sessionResultsLiveData = MutableLiveData<SessionResult?>(null)
    val sessionResultsLiveData: LiveData<SessionResult?> = _sessionResultsLiveData

    // ==================== Internal State ====================
    private var startTimeMs = 0L  // When session started
    private var timerJob: kotlinx.coroutines.Job? = null  // Timer coroutine handle
    private var isSessionActive = false

    // ==================== Public API ====================

    /**
     * Start a new skip counting session.
     * Call from CountingScreen when user taps "Start".
     *
     * Flow:
     * 1. Reset count and timer
     * 2. Start AudioEngine (includes 3-second calibration)
     * 3. Launch timer coroutine
     * 4. Update UI state
     */
    fun startSession() {
        if (isSessionActive) return  // Already counting

        isSessionActive = true
        startTimeMs = System.currentTimeMillis()
        _skipCountLiveData.value = 0
        _elapsedTimeLiveData.value = "00:00"
        _sessionStateLiveData.value = "calibrating"

        // Observe AudioEngine's skip count (it posts updates as skips are detected)
        audioEngine.skipCountLiveData.observeForever { count ->
            _skipCountLiveData.postValue(count)
        }

        // Observe calibration progress
        audioEngine.calibrationProgressLiveData.observeForever { progress ->
            _calibrationProgressLiveData.postValue(progress)

            // When calibration finishes (100%), switch state to counting
            if (progress == 100) {
                _sessionStateLiveData.postValue("counting")
            }
        }

        // Start AudioEngine (blocks until calibration done, then enters listening loop)
        audioEngine.startCounting()

        // Start timer coroutine (updates every 100ms)
        startTimer()
    }

    /**
     * Stop the current session and save results.
     * Call from CountingScreen when user taps "Stop".
     *
     * Flow:
     * 1. Stop AudioEngine (get final skip count)
     * 2. Stop timer
     * 3. Calculate session stats (duration, skips/min)
     * 4. Save to database
     * 5. Post results to UI
     */
    fun stopSession() {
        if (!isSessionActive) return  // Not counting

        isSessionActive = false
        _sessionStateLiveData.value = "finished"

        // Stop audio engine and get final count
        val finalSkipCount = audioEngine.stopCounting()

        // Stop timer
        timerJob?.cancel()

        // Calculate stats
        val durationMs = System.currentTimeMillis() - startTimeMs
        val durationSec = durationMs / 1000
        val durationMin = durationSec / 60

        // Skips per minute (avoid division by zero)
        val skipsPerMinute = if (durationMin > 0) {
            finalSkipCount / durationMin
        } else {
            finalSkipCount  // If less than 1 minute, report count as-is
        }

        // Create result object
        val result = SessionResult(
            skipCount = finalSkipCount,
            durationSec = durationSec.toInt(),
            skipsPerMinute = skipsPerMinute.toDouble()
        )

        // Save to database (on IO thread)
        GlobalScope.launch(Dispatchers.IO) {
            repository.saveSession(result)
        }

        // Post results to UI
        _sessionResultsLiveData.postValue(result)
    }

    /**
     * Reset state for a new session.
     * Call from ResultsScreen when user taps "Try Again".
     */
    fun resetSession() {
        _skipCountLiveData.value = 0
        _elapsedTimeLiveData.value = "00:00"
        _sessionStateLiveData.value = "idle"
        _sessionResultsLiveData.value = null
    }

    // ==================== Private: Timer ====================

    /**
     * Launch a coroutine that updates elapsed time every 100ms.
     * Converts milliseconds to MM:SS format for UI display.
     */
    private fun startTimer() {
        timerJob = GlobalScope.launch(Dispatchers.Main) {
            while (isSessionActive) {
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                val formattedTime = formatElapsedTime(elapsedMs)
                _elapsedTimeLiveData.postValue(formattedTime)
                delay(100)  // Update every 100ms
            }
        }
    }

    /**
     * Convert milliseconds to MM:SS format.
     * Example: 65000ms → "01:05"
     */
    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // ==================== Cleanup ====================

    /**
     * Called when ViewModel is destroyed (Activity closed, app backgrounded, etc.)
     * Always stop audio and timers to release resources.
     */
    override fun onCleared() {
        super.onCleared()
        if (isSessionActive) {
            stopSession()
        }
        audioEngine.stopCounting()
        timerJob?.cancel()
    }

    // ==================== Data Class ====================

    /**
     * Result of a completed skip counting session.
     * Posted to UI and saved to database.
     */
    data class SessionResult(
        val skipCount: Int,
        val durationSec: Int,
        val skipsPerMinute: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
}
