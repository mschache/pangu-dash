package io.github.mschache.karoopro

import io.github.mschache.karoopro.calc.HeatStrain
import org.junit.Assert.assertEquals
import org.junit.Test

class HeatStrainTest {
    @Test
    fun restIsZeroAndTheLimitIsTen() {
        assertEquals(0.0, HeatStrain.index(37.0, 60.0, 60), 1e-9)
        assertEquals(10.0, HeatStrain.index(39.5, 180.0, 60), 1e-9)
        assertEquals(10.0, HeatStrain.index(41.0, 200.0, 60), 1e-9)
    }

    @Test
    fun halfFromTemperatureHalfFromHeartRate() {
        // 38.25 °C is half way to 39.5, 120 bpm is half way from 60 to 180.
        assertEquals(5.0, HeatStrain.index(38.25, 120.0, 60), 1e-9)
        assertEquals(2.5, HeatStrain.index(38.25, 60.0, 60), 1e-9)
    }

    @Test
    fun withoutHeartRateTemperatureCountsTwice() {
        assertEquals(5.0, HeatStrain.index(38.25, null, 60), 1e-9)
    }

    @Test
    fun bands() {
        assertEquals(HeatStrain.Level.LOW, HeatStrain.level(2.9))
        assertEquals(HeatStrain.Level.MODERATE, HeatStrain.level(4.8))
        assertEquals(HeatStrain.Level.HIGH, HeatStrain.level(6.0))
    }
}
