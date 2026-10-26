package com.inkpalmplus

import kotlin.math.abs

/**
 * The adaptive-brightness decision, with no Android in it (so it's unit-tested): lux readings in, a new scale out
 * (or nothing). Smooths the lux (EMA, [ALPHA]); a new scale is chosen only after the smoothed lux has stayed more
 * than 15% away from the one behind the applied scale for 2 s (brighter) or 4 s (darker), and only if the scale moves
 * by [MIN_STEP] or more. [Light] does the ramp and the LEDs.
 */
class AutoLux {
    class Result(val scale: Float, val ramp: Boolean)

    /** The smoothed lux, NaN before the first reading. */
    var smoothed = Float.NaN
        private set
    private var appliedLux = Float.NaN
    private var appliedScale = -1f
    private var since = 0L
    private var dir = 0
    private var holdUntil = 0L

    /** Screen on, light toggled, auto turned on: ignore readings for [HOLD_MS], then take the first one as it is. */
    fun restart(now: Long) {
        smoothed = Float.NaN
        appliedLux = Float.NaN
        dir = 0
        holdUntil = now + HOLD_MS
    }

    /** One lux reading at [now] (ms), with the sensitivity [bias]; the new scale to go to, or null to stay. */
    fun sample(now: Long, lux: Float, bias: Int): Result? {
        if (now < holdUntil || lux.isNaN()) return null
        if (smoothed.isNaN()) {
            smoothed = lux; appliedLux = lux
            appliedScale = Light.luxScale(lux, bias)
            return Result(appliedScale, ramp = false)
        }
        smoothed += ALPHA * (lux - smoothed)
        val off = (smoothed - appliedLux) / maxOf(appliedLux, 1f)
        val d = if (off > FAR) 1 else if (off < -FAR) -1 else 0
        if (d == 0) { dir = 0; return null }
        if (d != dir) { dir = d; since = now }
        val wait = if (d > 0) UP_MS else DOWN_MS
        if (now - since < wait) return null
        val target = Light.luxScale(smoothed, bias)
        if (abs(target - appliedScale) < MIN_STEP) return null
        appliedLux = smoothed
        appliedScale = target
        dir = 0
        return Result(target, ramp = true)
    }

    /** The sensitivity changed: the smoothed lux through the new curve, at once. Null before a reading. */
    fun force(bias: Int): Float? {
        if (smoothed.isNaN()) return null
        appliedLux = smoothed
        appliedScale = Light.luxScale(smoothed, bias)
        dir = 0
        return appliedScale
    }

    companion object {
        const val ALPHA = 0.3f
        const val FAR = 0.15f
        const val UP_MS = 2_000L
        const val DOWN_MS = 4_000L
        const val HOLD_MS = 500L
        const val MIN_STEP = 0.03f
    }
}
