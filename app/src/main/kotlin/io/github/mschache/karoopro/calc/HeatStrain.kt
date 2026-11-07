package io.github.mschache.karoopro.calc

/**
 * Heat strain from a CORE body temperature sensor, on CORE's 0–10 Heat Strain Index scale.
 *
 * Uses the Physiological Strain Index (Moran et al. 1998): half from how far core temperature has
 * risen from 37.0 °C toward 39.5 °C, half from how far heart rate has risen from rest toward
 * 180 bpm. Without heart rate the temperature part counts twice.
 */
object HeatStrain {
    const val BASE_CORE_C = 37.0
    const val MAX_CORE_C = 39.5
    const val MAX_HR = 180.0

    /** Where the core temperature bar starts and ends, in °C. */
    const val BAR_LOW_C = 36.5
    const val BAR_HIGH_C = 40.0

    fun index(coreC: Double, hr: Double?, restingHr: Int): Double {
        val temp = ((coreC - BASE_CORE_C) / (MAX_CORE_C - BASE_CORE_C)).coerceIn(0.0, 1.0)
        val rest = restingHr.coerceIn(30, 120).toDouble()
        val heart = hr?.takeIf { it > 0 }?.let { ((it - rest) / (MAX_HR - rest)).coerceIn(0.0, 1.0) } ?: temp
        return 5 * temp + 5 * heart
    }

    enum class Level { LOW, MODERATE, HIGH }

    /** CORE's bands: up to 3 low, up to 6 moderate, above that high. */
    fun level(hsi: Double): Level = when {
        hsi < 3 -> Level.LOW
        hsi < 6 -> Level.MODERATE
        else -> Level.HIGH
    }

    /** The core temperature's place on the bar, 0..1. */
    fun barFraction(coreC: Double): Float = ((coreC - BAR_LOW_C) / (BAR_HIGH_C - BAR_LOW_C)).coerceIn(0.0, 1.0).toFloat()
}
