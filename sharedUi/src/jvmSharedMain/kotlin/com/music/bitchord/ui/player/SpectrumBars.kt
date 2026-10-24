package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.music.bitchord.playback.AudioSpectrum
import kotlinx.coroutines.delay

internal val SPECTRUM_BARS_HEIGHT = 36.dp

/**
 * The visualizer under the volume bar: one bar per [AudioSpectrum] band,
 * spanning the slider's track so the two read as one piece.
 *
 * Bars jump up to a beat and fall back slowly. Drawing is driven by a frame
 * counter read only inside the canvas, so a frame redraws the bars without
 * recomposing anything; once everything has settled at rest the loop drops to
 * a slow poll instead of holding vsync for nothing.
 */
@Composable
internal fun SpectrumBars(modifier: Modifier = Modifier) {
    val tint = LocalPlayerTint.current
    val heights = remember { FloatArray(AudioSpectrum.BANDS) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            val target = AudioSpectrum.levels
            var moving = false
            for (i in heights.indices) {
                val goal = target[i]
                val current = heights[i]
                heights[i] = if (goal > current) {
                    current + (goal - current) * (dt * RISE_PER_SECOND).coerceAtMost(1f)
                } else {
                    maxOf(goal, current - dt * FALL_PER_SECOND)
                }
                if (heights[i] > 0f || goal > 0f) moving = true
            }
            frame++
            if (!moving) delay(IDLE_POLL_MS)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(SPECTRUM_BARS_HEIGHT)
            // Under the track only, not the speaker glyphs either side of it.
            .padding(horizontal = 30.dp),
    ) {
        frame
        val count = heights.size
        val slot = size.width / count
        val barWidth = slot * 0.62f
        val minHeight = 3.dp.toPx()
        val radius = CornerRadius(barWidth / 2f)
        for (i in 0 until count) {
            val h = minHeight + (size.height - minHeight) * heights[i]
            drawRoundRect(
                color = tint.copy(alpha = 0.28f + 0.32f * heights[i]),
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
    }
}

private const val RISE_PER_SECOND = 28f
private const val FALL_PER_SECOND = 1.8f
private const val IDLE_POLL_MS = 120L
