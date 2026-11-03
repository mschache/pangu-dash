package io.github.mschache.karoopro.ui.field

import io.github.mschache.karoopro.calc.ClimbResult
import io.github.mschache.karoopro.calc.ClimbView
import io.github.mschache.karoopro.calc.Climbs
import io.github.mschache.karoopro.calc.DescentView
import io.github.mschache.karoopro.calc.Descents
import io.github.mschache.karoopro.calc.ElevationProfile
import io.github.mschache.karoopro.calc.FuelSnapshot
import io.github.mschache.karoopro.calc.Gearing
import io.github.mschache.karoopro.calc.PaceSection
import io.github.mschache.karoopro.calc.ProfilePoint
import io.github.mschache.karoopro.calc.RideResult
import io.github.mschache.karoopro.calc.SegmentPhase
import io.github.mschache.karoopro.calc.SegmentReading
import io.github.mschache.karoopro.calc.SegmentResult
import io.github.mschache.karoopro.calc.SegmentState
import io.github.mschache.karoopro.calc.TargetBand
import io.github.mschache.karoopro.calc.TargetKind
import io.github.mschache.karoopro.calc.WorkoutState
import io.github.mschache.karoopro.coach.CoachInfo
import io.github.mschache.karoopro.coach.CoachMessage
import io.github.mschache.karoopro.coach.PlanStep
import io.github.mschache.karoopro.data.LapMetric
import io.github.mschache.karoopro.data.Metric
import io.github.mschache.karoopro.data.PaceState
import io.github.mschache.karoopro.data.Profile
import io.github.mschache.karoopro.data.Settings
import io.github.mschache.karoopro.data.Source
import io.github.mschache.karoopro.data.WorkoutOverlay
import io.github.mschache.karoopro.weather.HourForecast
import io.github.mschache.karoopro.weather.WeatherData
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataType
import kotlin.math.PI
import kotlin.math.sin

/**
 * A believable mid-ride snapshot for the page editor and the in-app preview. [t] (seconds) moves the
 * numbers along so the preview shows zone colors changing. [liveCoach] replaces the demo coach
 * message, so the preview shows what Pangu Coach really has; [nowMs] must then be the real time.
 */
class DemoSource(
    override val settings: Settings,
    profile: Profile,
    private val t: Long = 0,
    private val noPowerMeter: Boolean = false,
    private val overlayMode: Int = 0,
    liveCoach: CoachInfo? = null,
    override val nowMs: Long = 0L,
    /** False: no route, so nothing to go and no arrival time. */
    private val navigating: Boolean = true,
) : RideSource {
    override val profile: Profile = profile.takeIf { it.powerZones.isNotEmpty() } ?: Profile.DEFAULT
    private val ftp = this.profile.ftp.coerceAtLeast(100).toDouble()

    private fun wave(period: Double, phase: Double = 0.0) = sin(2 * PI * (t / period) + phase)

    private val power = ftp * (1.0 + 0.3 * wave(40.0))
    private val hr = 158 + 12 * wave(60.0, 1.0)
    private val speed = 9.4 + 1.2 * wave(50.0, 2.0)

    override val powerAvailable get() = !noPowerMeter
    override val hrAvailable get() = true
    override val shiftingAvailable get() = true
    override val workoutActive get() = true

    private val values: Map<Metric, Double> = mapOf(
        Metric.POWER to power,
        Metric.POWER_3S to power,
        Metric.POWER_10S to power * 0.98,
        Metric.POWER_30S to power * 0.95,
        Metric.POWER_20M to ftp * 0.94,
        Metric.POWER_1H to ftp * 0.84,
        Metric.VI to 1.06,
        Metric.KCAL_PER_HOUR to 880.0,
        Metric.PEDAL_SMOOTHNESS to 22.0,
        Metric.TORQUE_EFFECTIVENESS to 78.0,
        Metric.AVG_POWER to ftp * 0.78,
        Metric.MAX_POWER to ftp * 3.4,
        Metric.NP to ftp * 0.86,
        Metric.IF to 0.86,
        Metric.TSS to 74.0,
        Metric.FTP_PERCENT to power / ftp * 100,
        Metric.WKG to power / 72,
        Metric.WORK to 1480.0,
        Metric.CALORIES to 1415.0,
        Metric.BALANCE to 48.0,
        Metric.SPEED to speed,
        Metric.SPEED_3S to speed,
        Metric.AVG_SPEED to 8.6,
        Metric.MAX_SPEED to 17.2,
        Metric.HEART_RATE to hr,
        Metric.AVG_HR to 146.0,
        Metric.MAX_HR to 181.0,
        Metric.HR_PERCENT_MAX to hr / 190 * 100,
        Metric.HRR_PERCENT to (hr - 50) / 140 * 100,
        Metric.CORE_TEMP to 38.2,
        Metric.CADENCE to 91 + 4 * wave(20.0),
        Metric.AVG_CADENCE to 88.0,
        Metric.DISTANCE to 48_250.0,
        Metric.ELAPSED_TIME to 5_833_000.0 + t * 1000,
        Metric.TOTAL_TIME to 6_100_000.0 + t * 1000,
        Metric.CLOCK to 39_600_000.0,
        Metric.SUNSET_IN to 15_480_000.0,
        Metric.TO_GO to 31_400.0,
        Metric.ETA to 47_400_000.0,
        Metric.CLIMB_LEFT to 540.0,
        Metric.TYRE_FRONT to 4_600.0,
        Metric.TYRE_REAR to 4_900.0,
        Metric.ELEVATION to 412.0,
        Metric.ASCENT to 864.0,
        Metric.GRADE to 5.4 + wave(30.0),
        Metric.VAM to 1080.0,
        Metric.TEMPERATURE to 19.0,
        Metric.W_PRIME to 13_400.0,
        Metric.W_PRIME_EMPTY to 134_000.0,
        Metric.STAMINA to 72.0,
        Metric.STAMINA_NOW to 54.0,
        Metric.EFFORT_LEFT to 2_460_000.0,
        Metric.CARBS_BURNED to 182.0,
        Metric.CARBS_EATEN to 125.0,
        Metric.CARBS_BALANCE to -57.0,
        Metric.PACE_TARGET to ftp * 0.9,
        Metric.PACE_AHEAD to 84_000.0,
        Metric.HEADWIND to -2.6,
        Metric.CROSSWIND to 3.1,
        Metric.WIND_SPEED to 4.2,
        Metric.POWER_ZONE to 4.0,
        Metric.LAP_NUMBER to 4.0,
    ).mapValues { it.value }

    override fun value(metric: Metric): Double? {
        if (!navigating && metric in setOf(Metric.TO_GO, Metric.ETA, Metric.CLIMB_LEFT)) return null
        if (noPowerMeter && metric.name.contains("POWER") || noPowerMeter && metric in setOf(Metric.NP, Metric.IF, Metric.TSS, Metric.VI, Metric.WKG, Metric.FTP_PERCENT, Metric.WORK, Metric.W_PRIME, Metric.W_PRIME_EMPTY)) return null
        return values[metric]
    }

    private val lapValues: Map<String, Double> = buildMap {
        fun put(lm: LapMetric, cur: Double, prev: Double) {
            put(lm.current.typeId, cur)
            put(lm.previous.typeId, prev)
        }
        put(LapMetric.TIME, 512_000.0, 600_000.0)
        put(LapMetric.DISTANCE, 4_800.0, 5_000.0)
        put(LapMetric.POWER, ftp * 0.92, ftp * 0.88)
        put(LapMetric.NP, ftp * 0.95, ftp * 0.93)
        put(LapMetric.MAX_POWER, ftp * 2.1, ftp * 2.4)
        put(LapMetric.SPEED, 9.4, 8.33)
        put(LapMetric.HR, 161.0, 163.0)
        put(LapMetric.CADENCE, 90.0, 90.0)
        put(LapMetric.ASCENT, 120.0, 95.0)
        put(DataType.Type.SHIFTING_COUNT, 385.0)
    }

    override fun value(source: Source.Karoo): Double? =
        lapValues[source.typeId] ?: Metric.entries.firstOrNull { it.karoo == source }?.let { value(it) }

    override fun history(metric: Metric, seconds: Int): List<Float?> {
        val base = values[metric] ?: return emptyList()
        if (noPowerMeter && metric.name.contains("POWER")) return emptyList()
        return (0 until seconds).map { i ->
            val x = (i + t).toDouble()
            (base * (1 + 0.18 * sin(x / 23.0) + 0.09 * sin(x / 7.0) + if ((i / 60) % 3 == 1) 0.25 else 0.0)).toFloat()
        }
    }

    override fun best(seconds: Int): Double? = when (seconds) {
        5 -> ftp * 3.2
        60 -> ftp * 1.8
        300 -> ftp * 1.22
        1200 -> ftp * 1.02
        else -> null
    }.takeUnless { noPowerMeter }

    override val wPrimeBalance get() = if (noPowerMeter) null else 13_400.0
    override val wPrimeMax get() = settings.wPrimeJoules.toDouble()
    override val powerZoneSeconds get() = if (noPowerMeter) LongArray(7) else longArrayOf(820, 1640, 1210, 980, 610, 240, 60)
    override val hrZoneSeconds get() = longArrayOf(400, 1900, 2100, 1050, 210)

    override fun terrainBehind(meters: Double): List<ProfilePoint> = (0..100).map { i ->
        val d = 48_250.0 - meters + i * meters / 100
        ProfilePoint(d, 300 + 110 * (i / 100.0) + 18 * sin(i / 9.0))
    }

    override fun terrainAhead(meters: Double): List<ProfilePoint> = (0..100).map { i ->
        val d = i * meters / 100
        ProfilePoint(d, 412 + 90 * sin(i / 32.0) - 40 * (i / 100.0))
    }

    /** Two climbs; the rider is 1.6 km up the first, a 4 km climb at about 6 %. */
    override val climb: ClimbView? = ElevationProfile(DEMO_CLIMBS).let { profile ->
        Climbs.view(Climbs.detect(profile), 2_600.0 + t * 5, profile)
    }

    /** The same route's descent after its climbs, about 3 km losing 250 m; the rider is 1 km down it, the 12 % stretch ahead. */
    override val descent: DescentView? = ElevationProfile(DEMO_CLIMBS).let { profile ->
        Descents.view(Descents.detect(profile), 10_000.0 + t * 12, profile)
    }

    /** Halfway up a 1.5 km segment, 7 s ahead of the PR, 41 s behind the KOM. */
    override val segment = SegmentState(
        SegmentPhase.ON,
        SegmentReading(
            elapsed = 95_000.0 + t * 1000, prDelta = -7_000.0, komDelta = 41_000.0, pr = 254_000.0, kom = 206_000.0,
            distanceLeft = 620.0, elevationLeft = 34.0, totalDistance = 1_500.0,
        ),
    )

    /** A segment just now and the first climb a quarter of an hour ago. */
    override val results: List<RideResult> = listOf(
        SegmentResult(seconds = 131, prDelta = -7_000.0, komDelta = 41_000.0, length = 1_500.0, avgPower = ftp * 1.3, endedAt = nowMs - 60_000),
        ClimbResult(number = 1, total = 3, length = 2_400.0, gain = 168.0, seconds = 612, avgPower = ftp * 1.05, wPrimeLow = 6_200.0, endedAt = nowMs - 900_000),
    )

    override val pace: PaceState = run {
        val target = (ftp * 0.9).toInt()
        val bounds = TargetBand.bounds(TargetKind.POWER, target.toDouble(), null, null)!!
        PaceState(
            intensity = 75, target = target, low = bounds.first, high = bounds.second, output = power.toInt(),
            status = TargetBand.status(power.toInt(), bounds),
            next = PaceSection(from = 49_450.0, to = 49_700.0, grade = 0.06, power = (ftp * 0.93).toInt(), seconds = 60.0),
            toNext = 1_200.0, aheadS = 84.0, remainingS = 5_400.0, etaMs = 47_400_000,
        )
    }

    override val fuel = FuelSnapshot(burnedG = 182.0, eatenG = 125.0, drankMl = 900.0, movingS = 5_833 + t)

    /** Nine full 10-minute blocks (climbs among them), then the running one. */
    override val calorieBlocks = listOf(118.0, 142.0, 131.0, 176.0, 188.0, 124.0, 153.0, 169.0, 140.0, 61.0 + t * 0.2)

    override val workout: WorkoutState
        get() {
            val bounds = TargetBand.bounds(TargetKind.POWER, null, ftp * 1.06, ftp * 1.16)!!
            val left = (214 - t % 214).toInt()
            val out = power.toInt()
            return WorkoutState(
                stepNumber = 2, stepCount = 8, kind = TargetKind.POWER,
                low = bounds.first, high = bounds.second, output = out,
                status = TargetBand.status(out, bounds),
                stepRemainingS = left, totalRemainingS = 1_860, stepElapsedS = 300 - left,
                stepAverage = (ftp * 1.1).toInt(), inTargetPercent = 83,
                progress = (300 - left) / 300f,
            )
        }

    override val workoutNext = PlanStep(index = 2, seconds = 240, low = (ftp * 1.2).toInt(), high = (ftp * 1.2).toInt(), label = "interval 2/4")

    override val overlay: WorkoutOverlay?
        get() = when (overlayMode) {
            1 -> WorkoutOverlay.NextStep(3, 8, 5, workoutNext)
            2 -> WorkoutOverlay.NewTarget(workout.copy(stepNumber = 3))
            else -> null
        }

    override val weather = WeatherData(
        fetchedAt = 0, latitude = 0.0, longitude = 0.0,
        windSpeed = 5.0, windFromDeg = 200.0, gust = 8.3, temperature = 19.0, rainChance = 20,
        hours = (1..6).map { i ->
            HourForecast(1_760_000_000L + i * 3600, 4.0 + i * 0.4, 190.0 + i * 12, 7.0, 10 * i, 19.0 - i)
        },
    )

    override val weatherStatus = io.github.mschache.karoopro.weather.WeatherStatus.OK

    override val heading = 20.0 + 25 * sin(t / 45.0)

    override val gearing = Gearing(
        frontGear = 2, frontMax = 2, rearGear = (4 + (t / 8) % 8).toInt(), rearMax = 12,
        frontTeeth = 50, rearTeeth = listOf(34, 30, 27, 24, 21, 19, 17, 15, 14, 13, 12, 11)[(4 + (t / 8) % 8).toInt() - 1],
        frontTeethList = listOf(34, 50), rearTeethList = listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34),
    )

    override val batteries = Batteries(frontDerailleur = BatteryStatus.GOOD, rearDerailleur = BatteryStatus.OK, powerMeter = BatteryStatus.LOW)

    override val coach: CoachInfo = liveCoach ?: CoachInfo(
        configured = true, active = true, riding = true, lastCheckInAt = nowMs - 8_000, lastError = null,
        message = CoachMessage("demo", "Coach", "Nice and steady. Hold 220 W to the top, then spin easy for 2 min", nowMs - 40_000),
        acknowledged = false,
    )

    private companion object {
        val DEMO_CLIMBS = listOf(
            0.0 to 300.0, 1_000.0 to 305.0, 1_400.0 to 330.0, 2_400.0 to 395.0, 3_000.0 to 450.0, 3_600.0 to 470.0,
            4_400.0 to 545.0, 5_000.0 to 560.0, 5_600.0 to 555.0, 7_000.0 to 520.0, 7_800.0 to 600.0, 9_000.0 to 590.0,
            // Then down: 3.6 km losing 250 m, steepest in the middle.
            9_600.0 to 560.0, 10_400.0 to 500.0, 11_200.0 to 400.0, 12_000.0 to 345.0, 12_600.0 to 340.0, 13_500.0 to 342.0,
        ).map { ProfilePoint(it.first, it.second) }
    }
}
