package io.github.mschache.karoopro.data

import io.github.mschache.karoopro.calc.FuelItem
import io.github.mschache.karoopro.calc.FuelSizes
import kotlinx.serialization.Serializable

enum class Panel(val title: String) {
    RIDE("RIDE"),
    EFFORT("EFFORT"),
    CLIMB("CLIMB"),
    DESCENT("DESCENT"),
    SEGMENT("SEGMENT"),
    PACE("PACE"),
    FUEL("FUEL"),
    WEATHER("WEATHER"),
    LAP("LAP"),
    ANALYSIS("ANALYSIS"),
    RESULTS("RESULTS"),
    WORKOUT("WORKOUT"),
    BIKE("BIKE"),
    COACH("COACH"),

    /** The big number as a slide of its own: only on the Panels field, never in settings or on the full field. */
    NUMBER("NUMBER"),
    ;

    companion object {
        /** The real panels, in their default order: everything the rider can show, hide and reorder. */
        val PANELS: List<Panel> = entries.filter { it != NUMBER }
    }
}

/**
 * The value shown as the big number; tap the hero to cycle through [Settings.bigMetrics]. [pill] is
 * the short name on the selector, [metric] the catalog value it shows.
 */
enum class BigMetric(val label: String, val pill: String, val metric: Metric, val needsPower: Boolean) {
    POWER("POWER 3S", "POWER", Metric.POWER_3S, true),
    SPEED("SPEED", "SPEED", Metric.SPEED, false),
    HR("HEART RATE", "HR", Metric.HEART_RATE, false),
    POWER_10S("POWER 10S", "10S", Metric.POWER_10S, true),
    FTP_PERCENT("% FTP", "%FTP", Metric.FTP_PERCENT, true),
    WKG("W/KG", "W/KG", Metric.WKG, true),
}

/** How the zone color is applied to the hero. */
enum class ColorMode(val label: String) {
    TINT("Tint the page"),
    FILL("Fill the top"),
    NUMBER("Color the number only"),
}

enum class ThemeMode(val label: String) { AUTO("Follow Karoo"), DARK("Dark"), LIGHT("Light") }

/** How the next workout step is announced inside the field. */
enum class AlertStyle(val label: String) { WINDOW("Window over the panel"), FULL("Full field"), OFF("Off") }

@Serializable
data class PanelSetting(val panel: Panel, val enabled: Boolean = true)

@Serializable
data class Settings(
    val panels: List<PanelSetting> = Panel.PANELS.map { PanelSetting(it) },
    val autoOpenWorkout: Boolean = true,
    val autoOpenBike: Boolean = true,
    /** Open the Coach panel when a Pangu Coach message arrives. */
    val autoOpenCoach: Boolean = true,
    /** Open the Climb panel just before a climb on the navigated route, and go back at the top. */
    val autoOpenClimb: Boolean = true,
    /** Open the Descent panel when a descent on the navigated route starts, and go back at the bottom. */
    val autoOpenDescent: Boolean = true,
    /** Open the Segment panel when a Strava Live Segment comes up, and go back after its result. */
    val autoOpenSegment: Boolean = true,
    /** Beep at the smallest and largest cog (Di2/AXS). */
    val gearLimitBeep: Boolean = true,
    /** Fueling: carbs and drink to take in per hour, the reminders, and what a tap on the Fuel panel logs. */
    val carbsPerHour: Int = 60,
    val drinkMlPerHour: Int = 500,
    val fuelReminders: Boolean = true,
    val tapLogs: FuelItem = FuelItem.GEL,
    val gelCarbs: Int = 25,
    val barCarbs: Int = 40,
    val bottleCarbs: Int = 30,
    val bottleMl: Int = 500,
    /** Pacing: the target on the flat as % of FTP, and the bike for the speed model. */
    val paceIntensity: Int = 75,
    val bikeKg: Int = 9,
    val cda: Double = 0.32,
    /** The Dash Strip field: its values, and whether climbs, segments and workouts swap theirs in. */
    val stripMetrics: List<Metric> = listOf(Metric.W_PRIME, Metric.STAMINA_NOW, Metric.HEADWIND, Metric.CARBS_BALANCE),
    val stripContext: Boolean = true,
    val bigMetric: BigMetric = BigMetric.POWER,
    /** The big numbers a tap on the hero cycles through, in order. */
    val bigMetrics: List<BigMetric> = listOf(BigMetric.POWER, BigMetric.SPEED, BigMetric.HR),
    /** The two cards under the big number; null shows the two of power, speed and HR not on top. */
    val heroCards: List<Metric>? = null,
    val colorMode: ColorMode = ColorMode.TINT,
    val theme: ThemeMode = ThemeMode.AUTO,
    val rideTiles: List<Metric> = listOf(Metric.ELAPSED_TIME, Metric.DISTANCE, Metric.CADENCE, Metric.CALORIES),
    /** The cadence band drawn on a cadence tile. */
    val cadenceLow: Int = 85,
    val cadenceHigh: Int = 95,
    /** A slim line over the big number with what the Karoo's own top bar lacks: sunset and temperature. */
    val statusLine: Boolean = true,
    val lapFields: List<LapMetric> = listOf(LapMetric.TIME, LapMetric.POWER, LapMetric.SPEED, LapMetric.HR),
    /** Critical power for W' balance in watts; null uses the FTP from the Karoo profile. */
    val criticalPower: Int? = null,
    val wPrimeJoules: Int = 20_000,
    val alertStyle: AlertStyle = AlertStyle.WINDOW,
    /** Also show a Karoo pop-up so the next step is announced on pages without Pangu Dash. */
    val systemAlert: Boolean = true,
    val alertBeep: Boolean = true,
    val countdownBeeps: Boolean = true,
    val weatherEnabled: Boolean = true,
    /** Map layers while navigating: wind on the route ahead, climbs by grade, wind arrows. */
    val mapWindRoute: Boolean = true,
    val mapClimbs: Boolean = true,
    /** Descents by grade as a blue band; off by default so the map stays as it was. */
    val mapDescents: Boolean = false,
    val mapWindArrows: Boolean = true,
) {
    /** Panel order with any panels added in a newer version appended. */
    val orderedPanels: List<PanelSetting>
        get() = panels.filter { it.panel != Panel.NUMBER }.distinctBy { it.panel } +
            Panel.PANELS.filter { p -> panels.none { it.panel == p } }.map { PanelSetting(it) }

    fun normalized(): Settings {
        val cycle = BigMetric.entries.filter { it in bigMetrics }.ifEmpty { Settings().bigMetrics }
        return copy(
            panels = orderedPanels,
            bigMetrics = cycle,
            bigMetric = bigMetric.takeIf { it in cycle } ?: cycle.first(),
            heroCards = heroCards?.filter { it.selectable }?.takeIf { it.size >= 2 }?.take(2),
            rideTiles = (rideTiles + Settings().rideTiles).take(4),
            cadenceLow = cadenceLow.coerceIn(CADENCE_MIN, CADENCE_MAX - 5),
            cadenceHigh = cadenceHigh.coerceIn(cadenceLow.coerceIn(CADENCE_MIN, CADENCE_MAX - 5) + 5, CADENCE_MAX),
            lapFields = (lapFields + Settings().lapFields).take(4),
            wPrimeJoules = wPrimeJoules.coerceIn(1_000, 100_000),
            criticalPower = criticalPower?.takeIf { it in 50..1000 },
            carbsPerHour = carbsPerHour.coerceIn(0, 150),
            drinkMlPerHour = drinkMlPerHour.coerceIn(0, 1500),
            gelCarbs = gelCarbs.coerceIn(5, 100),
            barCarbs = barCarbs.coerceIn(5, 150),
            bottleCarbs = bottleCarbs.coerceIn(0, 150),
            bottleMl = bottleMl.coerceIn(100, 1500),
            paceIntensity = paceIntensity.coerceIn(40, 110),
            bikeKg = bikeKg.coerceIn(3, 40),
            cda = cda.coerceIn(0.15, 0.6),
            stripMetrics = stripMetrics.filter { it.selectable }.distinct().take(STRIP_MAX).ifEmpty { Settings().stripMetrics },
        )
    }

    val fuelSizes: FuelSizes get() = FuelSizes(gelCarbs, barCarbs, bottleCarbs, bottleMl)

    /** The big numbers the tap cycles through now: power-based ones need a power meter. */
    fun bigCycle(hasPower: Boolean): List<BigMetric> =
        bigMetrics.filter { hasPower || !it.needsPower }.ifEmpty { listOf(BigMetric.SPEED) }

    /**
     * The two cards under the big number [big]. By default the two of power, speed and HR not on top;
     * a chosen card that repeats the big number gives way to the next of those.
     */
    fun cardsFor(big: BigMetric): List<Metric> {
        val fallback = listOf(Metric.POWER_3S, Metric.SPEED, Metric.HEART_RATE)
        val onTop = if (big.needsPower) Metric.POWER_3S else big.metric
        val chosen = heroCards ?: return fallback.filter { it != onTop }
        fun repeats(m: Metric) = m == big.metric || (big == BigMetric.POWER && m == Metric.POWER)
        val out = ArrayList<Metric>()
        for (m in chosen) {
            out += if (!repeats(m)) m else fallback.firstOrNull { it != onTop && it !in chosen && it !in out } ?: m
        }
        return out
    }

    /** The big number on screen: the chosen one, or speed (else the first left) when it needs a missing power meter. */
    fun shownBigMetric(hasPower: Boolean): BigMetric {
        val cycle = bigCycle(hasPower)
        return bigMetric.takeIf { it in cycle } ?: BigMetric.SPEED.takeIf { it in cycle } ?: cycle.first()
    }

    companion object {
        const val STRIP_MAX = 6
        const val CADENCE_MIN = 40
        const val CADENCE_MAX = 140
    }
}
