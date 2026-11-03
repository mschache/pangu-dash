package io.github.mschache.karoopro.ui.field

import io.github.mschache.karoopro.AppGraph
import io.github.mschache.karoopro.calc.ClimbView
import io.github.mschache.karoopro.calc.DescentView
import io.github.mschache.karoopro.calc.FuelSnapshot
import io.github.mschache.karoopro.calc.Gearing
import io.github.mschache.karoopro.calc.ProfilePoint
import io.github.mschache.karoopro.calc.RideResult
import io.github.mschache.karoopro.calc.SegmentState
import io.github.mschache.karoopro.calc.WorkoutState
import io.github.mschache.karoopro.coach.CoachInfo
import io.github.mschache.karoopro.coach.PlanStep
import io.github.mschache.karoopro.data.Metric
import io.github.mschache.karoopro.data.PaceState
import io.github.mschache.karoopro.data.Profile
import io.github.mschache.karoopro.data.Settings
import io.github.mschache.karoopro.data.Source
import io.github.mschache.karoopro.data.WorkoutOverlay
import io.github.mschache.karoopro.karoo.field
import io.github.mschache.karoopro.weather.WeatherData
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataType

data class Batteries(
    val frontDerailleur: BatteryStatus? = null,
    val rearDerailleur: BatteryStatus? = null,
    val shifting: BatteryStatus? = null,
    val powerMeter: BatteryStatus? = null,
)

/** Everything the field can draw, from the live Karoo or from demo data. */
interface RideSource {
    val profile: Profile
    val settings: Settings
    val powerAvailable: Boolean
    val hrAvailable: Boolean
    val shiftingAvailable: Boolean
    val workoutActive: Boolean
    fun value(metric: Metric): Double?
    fun value(source: Source.Karoo): Double?
    /** Newest [seconds] of 1 Hz history, oldest first; null entries are gaps. */
    fun history(metric: Metric, seconds: Int): List<Float?>
    fun best(seconds: Int): Double?
    val wPrimeBalance: Double?
    val wPrimeMax: Double
    val powerZoneSeconds: LongArray
    val hrZoneSeconds: LongArray
    /** Profile behind the rider within [meters]. */
    fun terrainBehind(meters: Double): List<ProfilePoint>
    /** Route profile ahead within [meters], distances relative to the rider; empty when not navigating. */
    fun terrainAhead(meters: Double): List<ProfilePoint>
    /** The climb the rider is on or the next one on the navigated route; null without one. */
    val climb: ClimbView?
    /** The descent the rider is on or the next one on the navigated route; null without one. */
    val descent: DescentView?
    /** The Strava Live Segment coming up, running or just finished; null otherwise. */
    val segment: SegmentState?
    /** Finished climbs and segments this ride, newest first. */
    val results: List<RideResult>
    /** The pacing plan's view of the navigated route; null without one. */
    val pace: PaceState?
    /** Carbs burned, eaten and drink so far this ride. */
    val fuel: FuelSnapshot
    /** The Karoo's calories per 10 minutes of riding, oldest first, the running block last. */
    val calorieBlocks: List<Double>
    val workout: WorkoutState?
    /** The planned step after the running one, when Pangu Coach's relay matched the workout. */
    val workoutNext: PlanStep?
    val overlay: WorkoutOverlay?
    val weather: WeatherData?
    val weatherStatus: io.github.mschache.karoopro.weather.WeatherStatus
    val heading: Double?
    val gearing: Gearing?
    val batteries: Batteries
    /** Pangu Coach's state; null when it isn't installed. */
    val coach: CoachInfo?
    val nowMs: Long
}

/** Reads the live hub, recorder, workout monitor and weather. */
class LiveSource(private val g: AppGraph) : RideSource {
    override val profile get() = g.hub.profile.value
    override val settings get() = g.settings.value
    override val powerAvailable get() = g.hub.isStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_POWER) || g.hub.isStreaming(DataType.Type.POWER)
    override val hrAvailable get() = g.hub.isStreaming(DataType.Type.HEART_RATE)
    override val shiftingAvailable get() = g.panels.shiftingAvailable
    override val workoutActive get() = g.workout.active
    override fun value(metric: Metric) = g.hub.value(metric)
    override fun value(source: Source.Karoo) = g.hub.value(source)
    override fun history(metric: Metric, seconds: Int): List<Float?> = g.recorder.read {
        history[metric]?.last(seconds)?.map { if (it.isNaN()) null else it.toFloat() }.orEmpty()
    }
    override fun best(seconds: Int) = g.recorder.read { best.bestFor(seconds) }
    override val wPrimeBalance get() = g.hub.value(Metric.W_PRIME)
    override val wPrimeMax get() = g.settings.value.wPrimeJoules.toDouble()
    override val powerZoneSeconds get() = g.recorder.read { powerZoneTime.seconds.copyOf() }
    override val hrZoneSeconds get() = g.recorder.read { hrZoneTime.seconds.copyOf() }
    override fun terrainBehind(meters: Double) = g.recorder.read { terrain.window(meters) }

    override fun terrainAhead(meters: Double): List<ProfilePoint> = g.routes.terrainAhead(meters)
    override val climb get() = g.routes.climbView()
    override val descent get() = g.routes.descentView()
    override val segment get() = g.segments.state.value
    override val results get() = g.results.results.value
    override val pace get() = g.pace.state()
    override val fuel get() = g.recorder.read { fuel.state }
    override val calorieBlocks get() = g.recorder.read { calorieBlocks.values() }

    override val workout get() = g.workout.state.value
    override val workoutNext get() = g.workout.next.value
    override val overlay get() = g.workout.overlay.value
    override val weather get() = g.weather.weather.value
    override val weatherStatus get() = g.weather.status.value
    override val heading get() = g.weather.heading
    override val gearing: Gearing? get() = g.gears.gearing()
    override val batteries: Batteries
        get() {
            val s = g.hub.stream(DataType.Type.SHIFTING_BATTERY).value
            fun status(field: String) = s.field(field)?.toInt()?.let { BatteryStatus.entries.getOrNull(it) }
            val powerMeter = g.hub.savedDevices.value?.devices
                ?.firstOrNull { it.enabled && DataType.Source.POWER in it.supportedDataTypes }
                ?.details?.lastBattery
            return Batteries(
                frontDerailleur = status(DataType.Field.SHIFTING_BATTERY_STATUS_FRONT_DERAILLEUR),
                rearDerailleur = status(DataType.Field.SHIFTING_BATTERY_STATUS_REAR_DERAILLEUR),
                shifting = status(DataType.Field.SHIFTING_BATTERY_STATUS),
                powerMeter = powerMeter,
            )
        }
    override val coach get() = g.coach.state.value
    override val nowMs get() = System.currentTimeMillis()
}
