package com.hemnaath.skipcounter.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AudioEngine: Real-time audio processing for skip detection.
 *
 * Algorithm (upgraded to frequency-domain analysis):
 * 1. Capture PCM buffer from AudioRecord (~46ms chunks)
 * 2. Calculate RMS (loudness) — first gate
 * 3. Run FFT on buffer — convert time-domain to frequency-domain
 * 4. Find dominant frequency in spectrum
 * 5. Check if dominant frequency falls in rope-impact range (80–500 Hz) — second gate
 * 6. If both gates pass AND cooldown expired → count skip
 *
 * Why FFT?
 * Threshold alone answers "how loud?" but not "what kind of sound?"
 * A door slam and a rope hit can have identical RMS values.
 * FFT lets us fingerprint the acoustic signature of a rope impact,
 * distinguishing it from ambient noise at the same amplitude.
 *
 * Threading: All audio processing runs on IO dispatcher (background thread).
 * UI updates flow through LiveData (lifecycle-aware, thread-safe).
 */
class AudioEngine {

    // ==================== Configuration ====================
    private val SAMPLE_RATE = 44100          // Hz (CD quality)
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // FFT requires power-of-2 sample count
    // 2048 samples at 44100 Hz = ~46ms per buffer
    // Frequency resolution = 44100 / 2048 = ~21.5 Hz per bin
    private val FFT_SIZE = 2048

    private val CALIBRATION_DURATION_MS = 2000L  // 2 seconds baseline measurement

    private val COOLDOWN_MS = 300L  // Min ms between counted skips

    // Rope impact on hard floor (tile/concrete): dominant frequency 80–500 Hz
    // Below 80 Hz: footsteps, bass rumble, HVAC
    // Above 500 Hz: voice, claps, high-frequency ambient noise
    private val ROPE_FREQ_MIN = 80f   // Hz
    private val ROPE_FREQ_MAX = 500f  // Hz

    // ==================== State ====================
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var skipCount = 0
    private var lastSkipTimeMs = 0L
    private var threshold = 0f
    private var baselineAmplitude = 0f

    // LiveData for UI observers
    private val _skipCountLiveData = MutableLiveData<Int>(0)
    val skipCountLiveData: LiveData<Int> = _skipCountLiveData

    private val _calibrationProgressLiveData = MutableLiveData<Int>(0)
    val calibrationProgressLiveData: LiveData<Int> = _calibrationProgressLiveData

    // ==================== Public API ====================

    fun startCounting() {
        if (isRecording) return

        skipCount = 0
        _skipCountLiveData.postValue(0)

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

    fun stopCounting(): Int {
        isRecording = false
        audioRecord?.let { recorder ->
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
            recorder.release()
        }
        audioRecord = null
        return skipCount
    }

    // ==================== Private: Initialization ====================

    private fun initializeAudioRecord() {
        val bufferSizeBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
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

    private fun calibrate() {
        val record = audioRecord ?: return
        val bufferBytes = ByteArray(FFT_SIZE * 2)
        val rmsValues = mutableListOf<Float>()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < CALIBRATION_DURATION_MS) {
            val bytesRead = record.read(bufferBytes, 0, bufferBytes.size)
            if (bytesRead <= 0) continue

            rmsValues.add(calculateRMS(bufferBytes, bytesRead))

            val progress = ((System.currentTimeMillis() - startTime) * 100
                    / CALIBRATION_DURATION_MS).toInt()
            _calibrationProgressLiveData.postValue(progress.coerceIn(0, 100))
        }

        baselineAmplitude = rmsValues.average().toFloat()

        // 2.8x baseline — tuned for tile/concrete rope impact
        // High enough to reject ambient noise, low enough to catch rope hits
        threshold = baselineAmplitude * 2.8f

        _calibrationProgressLiveData.postValue(100)
    }

    // ==================== Private: Skip Detection Loop ====================

    private fun listenForSkips() {
        val record = audioRecord ?: return
        val bufferBytes = ByteArray(FFT_SIZE * 2)

        while (isRecording) {
            val bytesRead = record.read(bufferBytes, 0, bufferBytes.size)
            if (bytesRead <= 0) continue

            val now = System.currentTimeMillis()

            // Gate 1: Is it loud enough?
            val rms = calculateRMS(bufferBytes, bytesRead)
            if (rms <= threshold) continue

            // Gate 2: Is it the right frequency? (rope impact fingerprint)
            val dominantFreq = getDominantFrequency(bufferBytes, bytesRead)
            if (dominantFreq !in ROPE_FREQ_MIN..ROPE_FREQ_MAX) continue

            // Gate 3: Has enough time passed since last skip?
            if (now - lastSkipTimeMs <= COOLDOWN_MS) continue

            // All three gates passed → SKIP DETECTED
            skipCount++
            lastSkipTimeMs = now
            _skipCountLiveData.postValue(skipCount)
        }
    }

    // ==================== Private: Signal Processing ====================

    /**
     * Calculate RMS (Root Mean Square) amplitude from PCM buffer.
     * Measures overall loudness of the audio chunk.
     *
     * Formula: RMS = √(Σ(sample²) / N)
     */
    private fun calculateRMS(bufferBytes: ByteArray, numBytes: Int): Float {
        val numSamples = numBytes / 2
        var sumOfSquares = 0.0

        for (i in 0 until numSamples) {
            val low = bufferBytes[i * 2].toInt() and 0xFF
            val high = (bufferBytes[i * 2 + 1].toInt() and 0xFF) shl 8
            val sample = (high or low).toShort().toInt()
            sumOfSquares += (sample * sample).toDouble()
        }

        return sqrt(sumOfSquares / numSamples).toFloat()
    }

    /**
     * Find the dominant frequency in the audio buffer using FFT.
     *
     * Steps:
     * 1. Convert raw PCM bytes to normalized float samples (-1.0 to 1.0)
     * 2. Apply Hann window to reduce spectral leakage
     * 3. Run Cooley-Tukey FFT (in-place, radix-2)
     * 4. Calculate magnitude of each frequency bin
     * 5. Return the frequency (Hz) of the bin with highest magnitude
     *
     * Frequency resolution = SAMPLE_RATE / FFT_SIZE = 44100 / 2048 ≈ 21.5 Hz
     * This means we can distinguish frequencies ~21 Hz apart — sufficient for
     * separating rope impacts (80-500 Hz) from footsteps (<80 Hz) and voice (>500 Hz)
     *
     * Returns: dominant frequency in Hz
     */
    private fun getDominantFrequency(bufferBytes: ByteArray, numBytes: Int): Float {
        val numSamples = minOf(numBytes / 2, FFT_SIZE)

        // Step 1: Convert PCM bytes to normalized float samples
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)  // Starts as zero (real-valued input)

        for (i in 0 until numSamples) {
            val low = bufferBytes[i * 2].toInt() and 0xFF
            val high = (bufferBytes[i * 2 + 1].toInt() and 0xFF) shl 8
            val sample = (high or low).toShort().toInt()

            // Normalize to [-1.0, 1.0] range (16-bit max = 32768)
            real[i] = sample / 32768.0
        }

        // Step 2: Apply Hann window to reduce spectral leakage
        // Without windowing, sharp buffer edges create false frequency artifacts
        // Hann window: w(n) = 0.5 * (1 - cos(2π*n / (N-1)))
        applyHannWindow(real, numSamples)

        // Step 3: Run FFT (Cooley-Tukey algorithm)
        fft(real, imag)

        // Step 4: Find bin with highest magnitude
        // Only check first half of spectrum (second half is mirror image)
        // Each bin i corresponds to frequency: i * SAMPLE_RATE / FFT_SIZE
        var maxMagnitude = 0.0
        var dominantBin = 0

        for (i in 1 until FFT_SIZE / 2) {
            val magnitude = sqrt(real[i] * real[i] + imag[i] * imag[i])
            if (magnitude > maxMagnitude) {
                maxMagnitude = magnitude
                dominantBin = i
            }
        }

        // Step 5: Convert bin index to frequency in Hz
        // Frequency = bin_index × (sample_rate / FFT_size)
        return dominantBin * SAMPLE_RATE.toFloat() / FFT_SIZE
    }

    /**
     * Apply Hann window function to PCM samples.
     *
     * Why windowing?
     * FFT assumes the signal repeats infinitely. Real audio buffers have
     * discontinuities at the edges (buffer start/end don't connect smoothly).
     * These edges cause "spectral leakage" — energy bleeds across frequency bins,
     * making it hard to identify the true dominant frequency.
     *
     * Hann window tapers the signal to zero at both edges, eliminating the
     * discontinuity and producing a clean frequency spectrum.
     *
     * Formula: w(n) = 0.5 × (1 − cos(2π × n / (N − 1)))
     */
    private fun applyHannWindow(samples: DoubleArray, numSamples: Int) {
        for (i in 0 until numSamples) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (numSamples - 1)))
            samples[i] *= window
        }
    }

    /**
     * Cooley-Tukey FFT — in-place, radix-2, Decimation In Time (DIT).
     *
     * This is the most widely used FFT algorithm, published in 1965.
     * It recursively divides the DFT into smaller DFTs, reducing complexity
     * from O(N²) to O(N log N) — critical for real-time audio processing.
     *
     * For N=2048 samples:
     * - Naive DFT: 2048² = 4,194,304 operations
     * - FFT:       2048 × log₂(2048) = 2048 × 11 = 22,528 operations
     * - Speedup:   ~186× faster
     *
     * Input:  real[] and imag[] arrays of length FFT_SIZE (power of 2)
     * Output: real[] and imag[] are overwritten with frequency-domain data
     *         Magnitude of bin i = √(real[i]² + imag[i]²)
     *
     * Algorithm steps:
     * 1. Bit-reversal permutation (reorders input for in-place computation)
     * 2. Butterfly operations across log₂(N) stages
     *    Each stage combines pairs of frequency bins using twiddle factors (W)
     *    W = e^(-j2π/N) = cos(2π/N) - j×sin(2π/N)
     */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size

        // Step 1: Bit-reversal permutation
        // Reorders elements so that in-place butterfly operations work correctly
        // Example for N=8: index 1 (001) ↔ index 4 (100), index 3 (011) ↔ index 6 (110)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit

            if (i < j) {
                // Swap real parts
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal

                // Swap imaginary parts
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }

        // Step 2: Butterfly operations
        // Process log₂(N) stages, each combining adjacent frequency bins
        // len: current sub-FFT size (2, 4, 8, 16, ..., N)
        var len = 2
        while (len <= n) {
            // Twiddle factor: W_len = e^(-j2π/len) = cos(-2π/len) + j×sin(-2π/len)
            val angleStep = -2.0 * PI / len
            val wRealStep = cos(angleStep)  // Real part increment per step
            val wImagStep = sin(angleStep)  // Imaginary part increment per step

            // Process each sub-FFT of this size
            var start = 0
            while (start < n) {
                var wReal = 1.0  // Current twiddle factor real part (starts at W^0 = 1)
                var wImag = 0.0  // Current twiddle factor imaginary part

                // Butterfly: combine element at position k with element at k + len/2
                for (k in start until start + len / 2) {
                    val evenReal = real[k]
                    val evenImag = imag[k]

                    // Multiply odd element by twiddle factor (complex multiplication)
                    // (a + jb)(c + jd) = (ac - bd) + j(ad + bc)
                    val oddReal = real[k + len / 2] * wReal - imag[k + len / 2] * wImag
                    val oddImag = real[k + len / 2] * wImag + imag[k + len / 2] * wReal

                    // Butterfly combine: even + odd and even - odd
                    real[k] = evenReal + oddReal
                    imag[k] = evenImag + oddImag
                    real[k + len / 2] = evenReal - oddReal
                    imag[k + len / 2] = evenImag - oddImag

                    // Advance twiddle factor by one step (complex multiplication)
                    val newWReal = wReal * wRealStep - wImag * wImagStep
                    wImag = wReal * wImagStep + wImag * wRealStep
                    wReal = newWReal
                }
                start += len
            }
            len = len shl 1  // Double the sub-FFT size for next stage
        }
    }

    // ==================== Debugging ====================

    fun getDebugInfo(): String {
        return """
            |Baseline Amplitude: $baselineAmplitude
            |Threshold: $threshold (${2.8f}x baseline)
            |Rope Frequency Range: ${ROPE_FREQ_MIN}–${ROPE_FREQ_MAX} Hz
            |FFT Size: $FFT_SIZE samples (resolution: ~${SAMPLE_RATE / FFT_SIZE} Hz/bin)
            |Cooldown: ${COOLDOWN_MS}ms
            |Skip Count: $skipCount
            |Is Recording: $isRecording
            |Last Skip: ${System.currentTimeMillis() - lastSkipTimeMs}ms ago
        """.trimMargin()
    }
}