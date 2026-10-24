package com.music.bitchord.playback

/**
 * The spectrum of what is coming out of the speaker right now, for the
 * player's visualizer.
 *
 * Written by the audible sink on the playback thread, read by the UI once a
 * frame. Each publish swaps in a whole new array, so a reader never sees half
 * of one frame and half of the next.
 */
object AudioSpectrum {
    /** Log-spaced bands from [MIN_HZ] to [MAX_HZ]. */
    const val BANDS = 40
    const val MIN_HZ = 40f
    const val MAX_HZ = 16_000f

    private val silent = FloatArray(BANDS)

    /** Each band's level in 0..1. */
    @Volatile
    var levels: FloatArray = silent
        private set

    fun publish(bands: FloatArray) {
        levels = bands
    }

    /** Paused, flushed or stopped: the bars fall back to rest. */
    fun clear() {
        levels = silent
    }
}
