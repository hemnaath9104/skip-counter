package com.hemnaath.skipcounter.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * AudioEngine: Real-time audio processing for skip detection.
 *
 * Responsibility: Capture microphone input, analyze amplitude, detect rope impacts.
 * Does NOT handle UI or persistence — just raw detection logic.
 *
 * Algorithm:
 * 1. Capture PCM buffer from AudioRecord (~20ms chunks)
 * 2. Calculate RMS (loudness) of that buffer
 * 3. Check if RMS > THRESHOLD and cooldown expired → count skip
 *
 * Threading: All audio processing runs on IO dispatcher (background thread).
 * UI updates flow through LiveData (lifecycle-aware, thread-safe).
 */
class AudioEngine {

    // ==================== Configuration ====================
    private val SAMPLE_RATE = 44100  // Hz (CD quality)
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO  // Single microphone stream
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT  // 16-bit samples (standard)
    private val BUFFER_SIZE_SAMPLES = 2048  // Samples per buffer (~46ms at 44.1kHz)
    private val CALIBRATION_DURATION_MS = 3000  // 3 seconds to establish baseline

    private val COOLDOWN_MS = 120L  // Min time between consecutive skips (rope vibration duration)

    // ==================== State ====================
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var skipCount = 0
    private var lastSkipTimeMs = 0L

    private var threshold = 0f  // Dynamically set during calibration
    private var baselineAmplitude = 0f  // Average RMS during calibration

    // LiveData for UI observers (ViewModel listens to this)
    private val _skipCountLiveData = MutableLiveData<Int>(0)
    val skipCountLiveData: LiveData<Int> = _skipCountLiveData

    private val _calibrationProgressLiveData = MutableLiveData<Int>(0)
    val calibrationProgressLiveData: LiveData<Int> = _calibrationProgressLiveData

    // ==================== Public API ====================

    /**
     * Start the skip detection process.
     * 1. Calibrates by listening to environment for 3 seconds
     * 2. Launches continuous listening loop
     *
     * Call from ViewModel when user taps "Start".
     */
    fun startCounting() {
        if (isRecording) return  // Already running

        skipCount = 0
        _skipCountLiveData.postValue(0)

        // Launch on IO dispatcher (background thread, optimized for I/O)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                initializeAudioRecord()
                calibrate()
                listenForSkips()
            } catch (e: Exception) {
                e.printStackTrace()
                stopCounting()
            }
        }
    }

    /**
     * Stop listening and return final skip count.
     * Call from ViewModel when user taps "Stop".
     */
    fun stopCounting(): Int {

        isRecording = false

        audioRecord?.let { recorder ->

            if (recorder.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING) {

                recorder.stop()
            }

            recorder.release()
        }

        audioRecord = null

        return skipCount
    }

    // ==================== Private: Initialization ====================

    /**
     * Set up AudioRecord to read from microphone.
     * This is a one-time setup before calibration.
     */
    private fun initializeAudioRecord() {

        val bufferSizeBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSizeBytes
        )


        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {

            audioRecord?.release()
            audioRecord = null

            throw IllegalStateException("AudioRecord initialization failed")
        }


        audioRecord?.startRecording()

        isRecording = true
    }

    // ==================== Private: Calibration ====================

    /**
     * Calibration phase: Listen for 3 seconds and establish a noise baseline.
     * This makes the algorithm adaptive to different environments.
     *
     * Example:
     * - Quiet room: baseline ≈ 50, threshold = 100 (2x baseline)
     * - Noisy gym: baseline ≈ 200, threshold = 400
     *
     * Both environments get similar detection behavior because threshold scales.
     */
    private fun calibrate() {
        val record = audioRecord ?: return
        val bufferBytes = ByteArray(BUFFER_SIZE_SAMPLES * 2)
        val rmsValues = mutableListOf<Float>()

        val startTimeMs = System.currentTimeMillis()

        // Read buffers for 3 seconds, collect RMS values
        while (System.currentTimeMillis() - startTimeMs < CALIBRATION_DURATION_MS) {
            val bytesRead = record.read(bufferBytes, 0, bufferBytes.size)
            if (bytesRead <= 0) continue

            // Convert bytes to 16-bit samples and calculate RMS
            val rms = calculateRMS(bufferBytes, bytesRead)
            rmsValues.add(rms)

            // Update UI with calibration progress (0-100%)
            val progress = ((System.currentTimeMillis() - startTimeMs) * 100 / CALIBRATION_DURATION_MS).toInt()
            _calibrationProgressLiveData.postValue(progress.coerceIn(0, 100))
        }

        // Baseline = average RMS during environment sampling
        baselineAmplitude = rmsValues.average().toFloat()

        // Threshold = baseline + safety margin (2x baseline means we need significant spike)
        threshold = baselineAmplitude * 2.0f

        _calibrationProgressLiveData.postValue(100)
    }

    // ==================== Private: Skip Detection Loop ====================

    /**
     * Main listening loop: Continuously read buffers and check for skips.
     * Runs until stopCounting() is called.
     */
    private fun listenForSkips() {
        val record = audioRecord ?: return
        val bufferBytes = ByteArray(BUFFER_SIZE_SAMPLES * 2)
        val currentTimeMs = System.currentTimeMillis()

        while (isRecording) {
            // Read next buffer from microphone
            val bytesRead = record.read(bufferBytes, 0, bufferBytes.size)
            if (bytesRead <= 0) continue

            // Calculate RMS (loudness) of this buffer
            val rms = calculateRMS(bufferBytes, bytesRead)

            // Check: Is this a skip?
            // Conditions:
            // 1. RMS is above threshold (loud enough to be rope impact)
            // 2. Enough time passed since last skip (cooldown prevents double-counting)
            val now = System.currentTimeMillis()
            val timeSinceLastSkip = now - lastSkipTimeMs

            if (rms > threshold && timeSinceLastSkip > COOLDOWN_MS) {
                // SKIP DETECTED!
                skipCount++
                lastSkipTimeMs = now

                // Notify UI (thread-safe via LiveData)
                _skipCountLiveData.postValue(skipCount)
            }
        }
    }

    // ==================== Private: Signal Processing ====================

    /**
     * Calculate RMS (Root Mean Square) amplitude from PCM buffer.
     * RMS is the standard measure of audio loudness.
     *
     * Formula: RMS = √(sum of sample² / num_samples)
     *
     * Why RMS over peak amplitude?
     * - Peak amplitude is too noisy (one loud sample ≠ sustained sound)
     * - RMS averages across the buffer, giving stable "loudness" measurement
     *
     * Input: bufferBytes = raw PCM 16-bit samples (little-endian)
     * Output: RMS value (0 = silent, higher = louder)
     */
    private fun calculateRMS(bufferBytes: ByteArray, numBytes: Int): Float {
        // PCM 16-bit: 2 bytes per sample, signed integer
        // Combine two bytes (little-endian): sample = byte[1] << 8 | byte[0]
        val numSamples = numBytes / 2
        var sumOfSquares = 0.0

        for (i in 0 until numSamples) {
            // Extract 16-bit sample from byte pair (little-endian)
            val low = bufferBytes[i * 2].toInt() and 0xFF
            val high = (bufferBytes[i * 2 + 1].toInt() and 0xFF) shl 8
            val sample = (high or low).toShort().toInt()  // Convert to signed int

            // Accumulate sum of squares
            sumOfSquares += (sample * sample).toDouble()
        }

        // RMS = √(average of squares) = √(sum of squares / num_samples)
        val rms = sqrt(sumOfSquares / numSamples).toFloat()
        return rms
    }

    // ==================== Debugging ====================

    /**
     * Optional: Log current state for debugging.
     * Called from ViewModel if needed.
     */
    fun getDebugInfo(): String {
        return """
            |Baseline Amplitude: $baselineAmplitude
            |Threshold: $threshold
            |Skip Count: $skipCount
            |Is Recording: $isRecording
            |Last Skip: ${System.currentTimeMillis() - lastSkipTimeMs}ms ago
        """.trimMargin()
    }
}
