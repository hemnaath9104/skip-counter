package com.hemnaath.skipcounter.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.hemnaath.skipcounter.audio.AudioEngine
import com.hemnaath.skipcounter.repository.SkipRepository
import kotlinx.coroutines.Dispatchers
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
 *
 * Key fixes applied:
 * - Named observers prevent observeForever memory leak
 * - viewModelScope replaces GlobalScope (auto-cancelled when ViewModel is cleared)
 * - skipsPerMinute uses seconds-based calculation for precision
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

    // Calibration progress (0-100%) shown during setup phase
    private val _calibrationProgressLiveData = MutableLiveData<Int>(0)
    val calibrationProgressLiveData: LiveData<Int> = _calibrationProgressLiveData

    // Session state: "idle" → "calibrating" → "counting" → "finished"
    private val _sessionStateLiveData = MutableLiveData<String>("idle")
    val sessionStateLiveData: LiveData<String> = _sessionStateLiveData

    // Results after session ends (null until session completes)
    private val _sessionResultsLiveData = MutableLiveData<SessionResult?>(null)
    val sessionResultsLiveData: LiveData<SessionResult?> = _sessionResultsLiveData

    // ==================== Internal State ====================
    private var startTimeMs = 0L
    private var timerJob: kotlinx.coroutines.Job? = null
    private var isSessionActive = false

    // ==================== Named Observers ====================
    // Named observers (not anonymous lambdas) so they can be removed in stopSession().
    // Without removal, every startSession() call adds another observer.
    // After 3 sessions: 3 observers all posting to _skipCountLiveData = triple counting.

    private val skipObserver = Observer<Int> { count ->
        _skipCountLiveData.postValue(count)
    }

    private val calibrationObserver = Observer<Int> { progress ->
        _calibrationProgressLiveData.postValue(progress)

        // When calibration finishes (100%), switch UI state to counting
        if (progress == 100) {
            _sessionStateLiveData.postValue("counting")
        }
    }

    // ==================== Public API ====================

    /**
     * Start a new skip counting session.
     * Call from CountingActivity when user taps "Start".
     *
     * Flow:
     * 1. Reset count and timer
     * 2. Register named observers on AudioEngine LiveData
     * 3. Start AudioEngine (includes 2-second calibration, then enters listening loop)
     * 4. Launch timer coroutine
     */
    fun startSession() {
        if (isSessionActive) return  // Guard: prevent double-start

        isSessionActive = true
        startTimeMs = System.currentTimeMillis()
        _skipCountLiveData.value = 0
        _elapsedTimeLiveData.value = "00:00"
        _sessionStateLiveData.value = "calibrating"

        // Register named observers (safe to call multiple times — same observer instance
        // is registered only once even if startSession() is called again)
        audioEngine.skipCountLiveData.observeForever(skipObserver)
        audioEngine.calibrationProgressLiveData.observeForever(calibrationObserver)

        // Start AudioEngine on IO thread (calibrates first, then listens for skips)
        audioEngine.startCounting()

        // Start timer coroutine on Main dispatcher (updates UI every 100ms)
        startTimer()
    }

    /**
     * Stop the current session and save results.
     * Call from CountingActivity when user taps "Stop" or presses back.
     *
     * Flow:
     * 1. Remove named observers (prevents leak and duplicate counting)
     * 2. Stop AudioEngine (get final skip count)
     * 3. Stop timer
     * 4. Calculate session stats
     * 5. Save to database via viewModelScope
     * 6. Post results to UI
     */
    fun stopSession() {
        if (!isSessionActive) return  // Guard: prevent double-stop

        isSessionActive = false
        _sessionStateLiveData.value = "finished"

        // Remove observers FIRST to prevent any stray skip counts after stop
        audioEngine.skipCountLiveData.removeObserver(skipObserver)
        audioEngine.calibrationProgressLiveData.removeObserver(calibrationObserver)

        // Stop AudioEngine and capture final skip count
        val finalSkipCount = audioEngine.stopCounting()

        // Stop timer coroutine
        timerJob?.cancel()

        // Calculate session duration
        val durationMs = System.currentTimeMillis() - startTimeMs
        val durationSec = durationMs / 1000

        // Skips per minute — use seconds-based calculation for precision.
        // Integer division (finalSkipCount / durationMin) would truncate:
        // 90 skips in 61 sec = 88.5/min, but integer division → 88.
        // Seconds-based: (90 × 60.0) / 61 = 88.5/min (correct)
        val skipsPerMinute = if (durationSec > 0) {
            (finalSkipCount * 60.0) / durationSec
        } else {
            0.0
        }

        // Build result object
        val result = SessionResult(
            skipCount = finalSkipCount,
            durationSec = durationSec.toInt(),
            skipsPerMinute = skipsPerMinute,
            timestamp = System.currentTimeMillis()
        )

        // Save to database on IO thread.
        // viewModelScope is tied to ViewModel lifecycle — automatically cancelled
        // when onCleared() is called. GlobalScope has no lifecycle awareness.
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSession(result)
        }

        // Post results to UI (ResultsActivity observes this)
        _sessionResultsLiveData.postValue(result)
    }

    /**
     * Reset all state for a new session.
     * Call from ResultsActivity when user taps "Try Again".
     */
    fun resetSession() {
        _skipCountLiveData.value = 0
        _elapsedTimeLiveData.value = "00:00"
        _sessionStateLiveData.value = "idle"
        _sessionResultsLiveData.value = null
        _calibrationProgressLiveData.value = 0
    }

    // ==================== Private: Timer ====================

    /**
     * Launch a coroutine that updates elapsed time every 100ms.
     * Runs on Main dispatcher so LiveData.value (not postValue) can be used.
     *
     * Uses viewModelScope — automatically cancelled when ViewModel is cleared,
     * preventing the timer from running after the user leaves the screen.
     */
    private fun startTimer() {
        timerJob = viewModelScope.launch(Dispatchers.Main) {
            while (isSessionActive) {
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                _elapsedTimeLiveData.value = formatElapsedTime(elapsedMs)
                delay(100)
            }
        }
    }

    /**
     * Convert milliseconds to MM:SS display format.
     * Example: 65000ms → "01:05"
     */
    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // ==================== Lifecycle ====================

    /**
     * Called when ViewModel is destroyed (Activity finishes, process dies).
     * Always stop audio and cancel coroutines to release mic and CPU resources.
     *
     * Note: viewModelScope coroutines are cancelled automatically by the framework
     * after onCleared() returns. The explicit timerJob?.cancel() here is defensive.
     */
    override fun onCleared() {
        super.onCleared()
        if (isSessionActive) {
            // Ensure observers are removed and resources freed
            audioEngine.skipCountLiveData.removeObserver(skipObserver)
            audioEngine.calibrationProgressLiveData.removeObserver(calibrationObserver)
            audioEngine.stopCounting()
        }
        timerJob?.cancel()
    }

    // ==================== Data Classes ====================

    /**
     * Immutable result of a completed skip counting session.
     * Used for UI display and database persistence (Phase 2).
     *
     * @param skipCount     Total skips detected in the session
     * @param durationSec   Session length in seconds
     * @param skipsPerMinute Normalized rate for comparing sessions of different lengths
     * @param timestamp     Unix epoch ms when session ended (for history ordering)
     */
    data class SessionResult(
        val skipCount: Int,
        val durationSec: Int,
        val skipsPerMinute: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
}