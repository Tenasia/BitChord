package com.music.bitchord.playback.audio

import androidx.media3.common.C
import com.music.bitchord.playback.AudioSpectrum
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Listens to the finished DSP output and works out the spectrum the
 * visualizer draws.
 *
 * Analysed where the sink hands blocks on, which is well ahead of the
 * speaker — AudioTrack holds a quarter of a second or more. So each spectrum
 * is kept with the presentation time it belongs to, and only published once
 * the sink's playback position reaches it: the bars move with what is heard,
 * not with what was decoded.
 *
 * Owned by one sink and only touched from the playback thread.
 */
class SpectrumTap {

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val cosTable = FloatArray(FFT_SIZE / 2) { i -> cos(-2.0 * PI * i / FFT_SIZE).toFloat() }
    private val sinTable = FloatArray(FFT_SIZE / 2) { i -> sin(-2.0 * PI * i / FFT_SIZE).toFloat() }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    /** The last [FFT_SIZE] mono samples, oldest at [historyIndex]. */
    private val history = FloatArray(FFT_SIZE)
    private var historyIndex = 0
    private var sinceHop = 0

    private var sampleRate = 0
    private val bandLo = IntArray(AudioSpectrum.BANDS)
    private val bandHi = IntArray(AudioSpectrum.BANDS)
    private val bandTiltDb = FloatArray(AudioSpectrum.BANDS)

    private val slotTimeUs = LongArray(SLOTS) { C.TIME_UNSET }
    private val slotBands = Array(SLOTS) { FloatArray(AudioSpectrum.BANDS) }
    private var slotNext = 0
    private var publishedTimeUs = C.TIME_UNSET

    fun configure(sampleRate: Int) {
        if (sampleRate != this.sampleRate) {
            this.sampleRate = sampleRate
            val binHz = sampleRate.toFloat() / FFT_SIZE
            val maxHz = minOf(AudioSpectrum.MAX_HZ, sampleRate / 2f)
            val ratio = maxHz / AudioSpectrum.MIN_HZ
            for (b in 0 until AudioSpectrum.BANDS) {
                val loHz = AudioSpectrum.MIN_HZ * ratio.pow(b.toFloat() / AudioSpectrum.BANDS)
                val hiHz = AudioSpectrum.MIN_HZ * ratio.pow((b + 1f) / AudioSpectrum.BANDS)
                val lo = floor(loHz / binHz).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
                bandLo[b] = lo
                bandHi[b] = ceil(hiHz / binHz).toInt().coerceIn(lo + 1, FFT_SIZE / 2)
                // Music loses energy as it climbs; lean the top up so the
                // right half of the bars is not permanently asleep.
                val centreHz = sqrt(loHz * hiHz)
                bandTiltDb[b] = TILT_DB_PER_OCTAVE * (ln(centreHz / 1000f) / ln(2f))
            }
        }
        reset()
    }

    fun reset() {
        history.fill(0f)
        historyIndex = 0
        sinceHop = 0
        slotTimeUs.fill(C.TIME_UNSET)
        slotNext = 0
        publishedTimeUs = C.TIME_UNSET
    }

    /** Feeds a processed block whose first frame plays at [blockTimeUs]. */
    fun analyze(block: AudioBlock, blockTimeUs: Long) {
        if (sampleRate <= 0 || blockTimeUs == C.TIME_UNSET) return
        val channels = block.channelCount
        val samples = block.samples
        var i = 0
        for (frame in 0 until block.frameCount) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[i++]
            history[historyIndex] = sum / channels
            historyIndex = (historyIndex + 1) % FFT_SIZE
            if (++sinceHop >= HOP) {
                sinceHop = 0
                store(blockTimeUs + (frame + 1) * 1_000_000L / sampleRate)
            }
        }
    }

    /** The speaker has reached [positionUs]: publish the spectrum that belongs there. */
    fun onPosition(positionUs: Long) {
        var best = -1
        var bestTime = Long.MIN_VALUE
        for (s in 0 until SLOTS) {
            val t = slotTimeUs[s]
            if (t != C.TIME_UNSET && t <= positionUs && t > bestTime) {
                best = s
                bestTime = t
            }
        }
        if (best < 0 || bestTime == publishedTimeUs) return
        // Too stale to be what is playing — a seek the slots have not caught up with.
        if (positionUs - bestTime > STALE_US) return
        publishedTimeUs = bestTime
        AudioSpectrum.publish(slotBands[best].copyOf())
    }

    private fun store(timeUs: Long) {
        for (n in 0 until FFT_SIZE) {
            re[n] = history[(historyIndex + n) % FFT_SIZE] * window[n]
            im[n] = 0f
        }
        fft()
        val bands = slotBands[slotNext]
        // Hann's coherent gain is a half, so a full-scale sine peaks at N/4.
        val fullScale = FFT_SIZE / 4f
        for (b in 0 until AudioSpectrum.BANDS) {
            var peak = 0f
            for (k in bandLo[b] until bandHi[b]) {
                val mag = re[k] * re[k] + im[k] * im[k]
                if (mag > peak) peak = mag
            }
            val db = 20f * log10(sqrt(peak) / fullScale + 1e-9f) + bandTiltDb[b]
            bands[b] = ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0f, 1f)
        }
        slotTimeUs[slotNext] = timeUs
        slotNext = (slotNext + 1) % SLOTS
    }

    /** In-place iterative radix-2 FFT over [re] and [im]. */
    private fun fft() {
        var j = 0
        for (i in 1 until FFT_SIZE) {
            var bit = FFT_SIZE shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len / 2
            val step = FFT_SIZE / len
            var start = 0
            while (start < FFT_SIZE) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val FFT_SIZE = 2048

        /** ~21ms at 48kHz: a fresh spectrum for most display frames. */
        const val HOP = 1024

        /** Enough slots to cover the deepest AudioTrack buffer at [HOP]. */
        private const val SLOTS = 96

        private const val STALE_US = 250_000L
        private const val FLOOR_DB = -66f
        private const val CEILING_DB = -12f
        private const val TILT_DB_PER_OCTAVE = 3f
    }
}
