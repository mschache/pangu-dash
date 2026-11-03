package io.github.mschache.karoopro.ui.field

import io.github.mschache.karoopro.calc.ClimbResult
import io.github.mschache.karoopro.calc.Fmt
import io.github.mschache.karoopro.calc.FuelTracker
import io.github.mschache.karoopro.calc.LapCompare
import io.github.mschache.karoopro.calc.SegmentPhase
import io.github.mschache.karoopro.calc.SegmentResult
import io.github.mschache.karoopro.calc.SegmentTracker
import io.github.mschache.karoopro.calc.TargetStatus
import io.github.mschache.karoopro.calc.Trend
import io.github.mschache.karoopro.calc.Wind
import io.github.mschache.karoopro.calc.ZoneColors
import io.github.mschache.karoopro.calc.ZoneKind
import io.github.mschache.karoopro.calc.ZoneMath
import io.github.mschache.karoopro.calc.bucketAverages
import io.github.mschache.karoopro.coach.CoachStatus
import io.github.mschache.karoopro.coach.CoachText
import io.github.mschache.karoopro.coach.PlanText
import io.github.mschache.karoopro.data.AlertStyle
import io.github.mschache.karoopro.data.BigMetric
import io.github.mschache.karoopro.data.ColorMode
import io.github.mschache.karoopro.data.Metric
import io.github.mschache.karoopro.data.Panel
import io.github.mschache.karoopro.data.SegmentMonitor
import io.github.mschache.karoopro.data.Settings
import io.github.mschache.karoopro.data.Source
import io.github.mschache.karoopro.data.ThemeMode
import io.github.mschache.karoopro.data.WorkoutOverlay
import io.hammerhead.karooext.models.DataType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object FieldModelBuilder {
    private val hourFormat = DateTimeFormatter.ofPattern("H").withZone(ZoneId.systemDefault())
    private val SHIFT_COUNT = Source.Karoo(DataType.Type.SHIFTING_COUNT, DataType.Field.SHIFTING_COUNT)

    fun palette(theme: ThemeMode, systemDark: Boolean): Palette = when (theme) {
        ThemeMode.AUTO -> if (systemDark) Palette.DARK else Palette.LIGHT
        ThemeMode.DARK -> Palette.DARK
        ThemeMode.LIGHT -> Palette.LIGHT
    }

    fun build(
        src: RideSource,
        panel: Panel,
        panels: List<Panel>,
        widthDp: Float,
        heightDp: Float,
        density: Float,
        interactive: Boolean,
        systemDark: Boolean,
    ): FieldModel {
        val s = src.settings
        val p = palette(s.theme, systemDark)
        val hero = hero(src, s, p)
        val pageBg = if (s.colorMode == ColorMode.TINT) hero.background else p.background
        return FieldModel(
            palette = p,
            widthDp = widthDp,
            heightDp = heightDp,
            density = density,
            hero = hero,
            panels = panels,
            panel = panel,
            content = content(src, panel, p),
            overlay = overlay(src, s),
            interactive = interactive,
            pageBackground = pageBg,
        )
    }

    /** Karoo data types a frame of [panel] reads; the field keeps them streaming. */
    fun requiredTypes(panel: Panel, s: Settings): Set<String> = buildSet {
        listOf(
            Metric.POWER, Metric.POWER_3S, Metric.SPEED, Metric.HEART_RATE, Metric.AVG_POWER,
            Metric.AVG_SPEED, Metric.MAX_SPEED, Metric.AVG_HR, Metric.HR_PERCENT_MAX,
        ).mapNotNullTo(this) { it.karoo?.typeId }
        s.bigMetrics.mapNotNullTo(this) { it.metric.karoo?.typeId }
        s.heroCards?.mapNotNullTo(this) { it.karoo?.typeId }
        if (s.statusLine) listOf(Metric.CLOCK, Metric.SUNSET_IN, Metric.TEMPERATURE).mapNotNullTo(this) { it.karoo?.typeId }
        when (panel) {
            Panel.RIDE -> {
                s.rideTiles.mapNotNullTo(this) { it.karoo?.typeId }
                // Time and distance tiles show the way along a navigated route.
                listOf(Metric.DISTANCE, Metric.TO_GO, Metric.ETA, Metric.CLOCK).mapNotNullTo(this) { it.karoo?.typeId }
            }
            Panel.EFFORT -> {
                listOf(Metric.GRADE, Metric.VAM, Metric.ASCENT, Metric.DISTANCE).mapNotNullTo(this) { it.karoo?.typeId }
                add(DataType.Type.DISTANCE_TO_DESTINATION)
            }
            Panel.LAP -> {
                add(DataType.Type.LAP_NUMBER)
                s.lapFields.forEach {
                    add(it.current.typeId)
                    add(it.previous.typeId)
                }
            }
            Panel.ANALYSIS -> listOf(Metric.NP, Metric.TSS, Metric.IF, Metric.ASCENT, Metric.WORK).mapNotNullTo(this) { it.karoo?.typeId }
            Panel.CLIMB -> {
                listOf(Metric.GRADE, Metric.VAM, Metric.DISTANCE).mapNotNullTo(this) { it.karoo?.typeId }
                add(DataType.Type.DISTANCE_TO_DESTINATION)
            }
            Panel.DESCENT -> {
                listOf(Metric.GRADE, Metric.SPEED, Metric.DISTANCE).mapNotNullTo(this) { it.karoo?.typeId }
                add(DataType.Type.DISTANCE_TO_DESTINATION)
            }
            Panel.BIKE -> {
                add(DataType.Type.SHIFTING_GEARS)
                add(DataType.Type.SHIFTING_BATTERY)
                add(DataType.Type.SHIFTING_COUNT)
                add(DataType.Type.SMOOTHED_3S_AVERAGE_PEDAL_POWER_BALANCE)
                add(DataType.Type.CADENCE)
            }
            Panel.SEGMENT -> addAll(SegmentMonitor.TYPES)
            Panel.PACE -> {
                listOf(Metric.ELAPSED_TIME, Metric.DISTANCE).mapNotNullTo(this) { it.karoo?.typeId }
                add(DataType.Type.DISTANCE_TO_DESTINATION)
            }
            Panel.FUEL, Panel.RESULTS -> Unit
            Panel.WEATHER, Panel.WORKOUT, Panel.COACH, Panel.NUMBER -> Unit
        }
    }

    /** One slide of the Panels field ([panels] includes [Panel.NUMBER] first). */
    fun compact(src: RideSource, panel: Panel, panels: List<Panel>, density: Float, interactive: Boolean, systemDark: Boolean): CompactModel {
        val s = src.settings
        val p = palette(s.theme, systemDark)
        if (panel == Panel.NUMBER) {
            val hero = hero(src, s, p)
            val bg = if (s.colorMode == ColorMode.NUMBER) p.background else hero.background
            return CompactModel(p, density, panels, panel, hero.big.label, CompactNumber(hero), bg, interactive)
        }
        val content = content(src, panel, p)
        return CompactModel(p, density, panels, panel, compactTitle(panel, content), compactBody(content, p), p.background, interactive)
    }

    /** The slide's name: the panel's, or the panel's own headline where it says more ("CLIMB 2/5"). */
    fun compactTitle(panel: Panel, content: PanelContent): String = when (content) {
        is ClimbContent -> content.title.takeIf { content.message == null } ?: panel.title
        is SegmentContent -> content.title.takeIf { content.message == null } ?: panel.title
        is PaceContent -> content.title.takeIf { content.message == null } ?: panel.title
        is LapContent -> content.title
        is WorkoutContent -> content.state?.let { "STEP ${it.stepNumber}/${it.stepCount}" } ?: panel.title
        else -> panel.title
    }

    /** A panel's content as a few values for the Panels field, most important first. */
    fun compactBody(content: PanelContent, p: Palette): CompactBody {
        fun cell(s: Stat, color: Int? = s.accent) = StripCell(s.label, s.value, s.unit, color)
        fun aheadColor(ahead: Boolean?) = when (ahead) {
            true -> p.good
            false -> p.bad
            null -> null
        }
        return when (content) {
            NumberContent -> CompactCells(emptyList())
            is RideContent -> CompactCells(content.tiles.map { StripCell(it.label, it.value, it.unit) })
            is EffortContent -> CompactCells(
                listOf(cell(content.wPrime)) + content.stats.map { cell(it) },
                bar = content.wPrimeFraction,
                barColor = p.accent,
            )
            is ClimbContent -> content.message?.let { CompactCells(emptyList(), message = it) }
                ?: CompactCells(
                    (content.big + content.stats).map { cell(it) } +
                        listOfNotNull(
                            content.effort?.let {
                                if (content.descent) StripCell("STEEPEST", it.removePrefix("STEEPEST "), "", if (content.effortWarning) p.bad else null)
                                else StripCell("W′", it.removePrefix("W′ "), "", if (content.effortWarning) p.bad else null)
                            },
                        ),
                    bar = content.rider,
                    barColor = if (content.effortWarning) p.bad else p.accent,
                )
            is SegmentContent -> content.message?.let { CompactCells(emptyList(), message = it) }
                ?: CompactCells(
                    listOf(cell(content.main, aheadColor(content.mainAhead)), cell(content.second, aheadColor(content.secondAhead))) + content.stats.map { cell(it) },
                    bar = content.progress,
                    barColor = p.accent,
                )
            is PaceContent -> content.message?.let { CompactCells(emptyList(), message = it) }
                ?: CompactCells(listOf(StripCell("TARGET", content.target, "", statusColor(content.status, p))) + content.stats.map { cell(it) })
            is FuelContent -> CompactCells(
                listOf(cell(content.eaten), cell(content.burned), cell(content.balance, if (content.onTrack) p.good else p.warn)) +
                    StripCell("NEXT", if (content.eatDue) "EAT" else if (content.drinkDue) "DRINK" else content.eat, "", if (content.eatDue || content.drinkDue) p.warn else null),
                bar = content.targetFraction?.coerceIn(0f, 1f),
                barColor = if (content.onTrack) p.good else p.warn,
            )
            is WeatherContent -> content.message?.let { CompactCells(emptyList(), message = it) }
                ?: CompactCells(listOf(StripCell("WIND", content.component, content.unit, windColor(content.kind, p))) + content.stats.drop(1).map { cell(it) })
            is LapContent -> CompactCells(
                content.fields.map { f ->
                    val (arrow, color) = when (f.trend) {
                        Trend.UP -> " ▲" to p.good
                        Trend.DOWN -> " ▼" to p.bad
                        else -> "" to null
                    }
                    StripCell(f.label + arrow, f.value, f.unit, color)
                },
            )
            is AnalysisContent -> CompactCells(content.best.map { cell(it) } + content.totals.map { cell(it) })
            is ResultsContent -> if (content.rows.isEmpty()) CompactCells(emptyList(), message = "No results yet")
            else CompactCells(content.rows.map { StripCell(it.title, it.main, "", aheadColor(it.good)) })
            is WorkoutContent -> content.state?.let { w ->
                val color = statusColor(w.status, p)
                val target = if (w.low != null && w.high != null) "${w.low}–${w.high}" else Fmt.NONE
                CompactCells(
                    listOf(
                        StripCell("TARGET", target, content.unit, color),
                        StripCell("NOW", w.output?.toString() ?: Fmt.NONE, content.unit, color),
                        StripCell("LEFT", w.stepRemainingS?.let { Fmt.seconds(it.toLong()) } ?: "OPEN", ""),
                        StripCell("IN TARGET", w.inTargetPercent?.toString() ?: Fmt.NONE, "%"),
                    ),
                    bar = w.progress,
                    barColor = color,
                )
            } ?: CompactCells(emptyList(), message = "No workout running")
            is BikeContent -> if (content.gearing == null) CompactCells(emptyList(), message = "Pair Di2 or AXS to see your gears")
            else CompactCells(listOf(StripCell("GEARS", content.gears, ""), cell(content.balance)) + content.stats.map { cell(it) })
            is CoachContent -> CompactText(content.status, content.warning, content.age, content.text, content.placeholder, content.unread, content.hint)
        }
    }

    private fun statusColor(s: TargetStatus, p: Palette) = when (s) {
        TargetStatus.PUSH -> p.push
        TargetStatus.EASE -> p.bad
        TargetStatus.IN_RANGE -> p.good
        TargetStatus.NO_DATA -> p.dim
    }

    private fun windColor(kind: io.github.mschache.karoopro.calc.WindKind?, p: Palette) = when (kind) {
        io.github.mschache.karoopro.calc.WindKind.HEAD -> p.bad
        io.github.mschache.karoopro.calc.WindKind.TAIL -> p.good
        io.github.mschache.karoopro.calc.WindKind.CROSS -> p.warn
        null -> p.dim
    }

    private fun hero(src: RideSource, s: Settings, p: Palette): HeroModel {
        val profile = src.profile
        val u = profile.units
        val power = src.value(Metric.POWER_3S) ?: src.value(Metric.POWER)
        val power10 = src.value(Metric.POWER_10S)
        val speed = src.value(Metric.SPEED)
        val hr = src.value(Metric.HEART_RATE)
        val ftp = profile.ftp.coerceAtLeast(1)
        val avgPower = src.value(Metric.AVG_POWER)
        // No power meter: start on speed, colored by heart rate.
        val big = s.shownBigMetric(src.powerAvailable)
        val powerZone = ZoneMath.zoneOf(power, profile.powerZones)
        val hrZone = ZoneMath.zoneOf(hr, profile.hrZones)
        val (zone, kind) = when (big) {
            BigMetric.POWER, BigMetric.FTP_PERCENT, BigMetric.WKG -> powerZone to ZoneKind.POWER
            BigMetric.POWER_10S -> ZoneMath.zoneOf(power10, profile.powerZones) to ZoneKind.POWER
            BigMetric.SPEED -> if (src.powerAvailable) powerZone to ZoneKind.POWER else hrZone to ZoneKind.HR
            BigMetric.HR -> hrZone to ZoneKind.HR
        }
        val zoneCount = if (kind == ZoneKind.POWER) profile.powerZones.size else profile.hrZones.size
        val zoneColor = zone?.let { ZoneColors.color(kind, it, zoneCount) }
        val zoneColors = (1..zoneCount).map { ZoneColors.color(kind, it, zoneCount) }
        fun powerColor() = powerZone?.let { ZoneColors.color(ZoneKind.POWER, it, profile.powerZones.size) }
        fun hrColor() = hrZone?.let { ZoneColors.color(ZoneKind.HR, it, profile.hrZones.size) }
        fun perKg(w: Double?) = w?.let { v -> profile.weightKg.takeIf { it > 0 }?.let { v / it } }

        val (value, unit) = when (big) {
            BigMetric.POWER -> Fmt.int(power) to "W"
            BigMetric.POWER_10S -> Fmt.int(power10) to "W"
            BigMetric.FTP_PERCENT -> Fmt.int(power?.let { it * 100 / ftp }) to "%"
            BigMetric.WKG -> Fmt.decimal(perKg(power), 1) to "W/kg"
            BigMetric.SPEED -> Fmt.speed(speed, u) to Fmt.speedUnit(u)
            BigMetric.HR -> Fmt.int(hr) to "bpm"
        }
        val stats = when (big) {
            BigMetric.POWER -> listOf(
                Stat("FTP", Fmt.int(power?.let { it * 100 / ftp }), "%"),
                Stat("AVG", Fmt.int(avgPower), "W"),
            )
            BigMetric.POWER_10S -> listOf(
                Stat("FTP", Fmt.int(power10?.let { it * 100 / ftp }), "%"),
                Stat("AVG", Fmt.int(avgPower), "W"),
            )
            BigMetric.FTP_PERCENT -> listOf(
                Stat("3S", Fmt.int(power), "W"),
                Stat("AVG", Fmt.int(avgPower?.let { it * 100 / ftp }), "%"),
            )
            BigMetric.WKG -> listOf(
                Stat("3S", Fmt.int(power), "W"),
                Stat("AVG", Fmt.decimal(perKg(avgPower), 1)),
            )
            BigMetric.SPEED -> listOf(
                Stat("AVG", Fmt.speed(src.value(Metric.AVG_SPEED), u)),
                Stat("MAX", Fmt.speed(src.value(Metric.MAX_SPEED), u)),
            )
            BigMetric.HR -> listOf(
                Stat("MAX", Fmt.int(hr?.let { it * 100 / profile.maxHr.coerceAtLeast(1) }), "%"),
                Stat("AVG", Fmt.int(src.value(Metric.AVG_HR)), "bpm"),
            )
        }
        val cards = s.cardsFor(big).map { m ->
            when (m) {
                Metric.POWER_3S -> CardModel("POWER", Fmt.int(power), "W", Fmt.int(avgPower), powerColor())
                Metric.SPEED -> CardModel("SPEED", Fmt.speed(speed, u), Fmt.speedUnit(u), Fmt.speed(src.value(Metric.AVG_SPEED), u), null)
                Metric.HEART_RATE -> CardModel("HEART", Fmt.int(hr), "bpm", Fmt.int(src.value(Metric.AVG_HR)), hrColor())
                else -> CardModel(
                    m.label, m.format(src.value(m), u), m.unit(u), null,
                    when (m.graph) {
                        Metric.POWER_3S -> powerColor()
                        Metric.HEART_RATE -> hrColor()
                        else -> null
                    },
                )
            }
        }

        var background = p.background
        var number = zoneColor ?: p.text
        var text = p.text
        var dim = p.dim
        when (s.colorMode) {
            ColorMode.TINT -> if (zoneColor != null) {
                background = ZoneColors.blend(zoneColor, p.background, if (p.dark) 0.22f else 0.38f)
                number = if (p.dark) zoneColor else p.text
            }
            ColorMode.FILL -> if (zoneColor != null) {
                background = zoneColor
                number = ZoneColors.onColor(zoneColor)
                text = number
                dim = ZoneColors.blend(number, zoneColor, 0.7f)
            }
            ColorMode.NUMBER -> if (zoneColor != null && !p.dark) {
                // Yellow and light green are unreadable on white; darken them for the light theme.
                number = ZoneColors.blend(zoneColor, 0xFF000000.toInt(), 0.72f)
            }
        }
        val status = if (!s.statusLine) null else {
            val clock = src.value(Metric.CLOCK)
            val sunsetIn = src.value(Metric.SUNSET_IN)?.takeIf { it > 0 }
            StatusLine(
                sunset = if (clock != null && sunsetIn != null) Fmt.clock(clock + sunsetIn).takeIf { it != Fmt.NONE } else null,
                temperature = (src.value(Metric.TEMPERATURE) ?: src.weather?.temperature)?.let { Fmt.temperature(it, u) + "°" },
            )
        }
        return HeroModel(
            big = big,
            options = s.bigCycle(src.powerAvailable),
            value = value,
            unit = unit,
            zone = zone,
            zoneColor = zoneColor,
            zoneColors = zoneColors,
            background = background,
            numberColor = number,
            textColor = text,
            dimColor = dim,
            stats = stats,
            cards = cards,
            status = status,
        )
    }

    /** A Ride tile: its icon and color, and the graphic that fits the value. */
    private fun tile(src: RideSource, m: Metric, p: Palette, powerColor: Int?, hrColor: Int?): TileModel {
        val s = src.settings
        val u = src.profile.units
        val look = TileStyle.look(m)
        val accent = TileStyle.color(look.family, p, powerColor, hrColor)
        val route = TileGraphics.routeFraction(src.value(Metric.DISTANCE), src.value(Metric.TO_GO))
        fun line() = m.graph?.let { TileGraphic.Line(src.history(it, 300)) }
        val graphic: TileGraphic? = when (m) {
            Metric.ELAPSED_TIME, Metric.TOTAL_TIME -> {
                val elapsed = src.value(m)
                TileGraphics.timeFraction(elapsed, src.value(Metric.CLOCK), src.value(Metric.ETA), src.nowMs)
                    ?.let { TileGraphic.Segments(TileGraphics.spread(it)) }
                    ?: TileGraphic.Segments(TileGraphics.hours(elapsed))
            }
            Metric.DISTANCE -> route?.let { TileGraphic.Route(it) } ?: line()
            Metric.TO_GO -> route?.let { TileGraphic.Route(it) }
            Metric.CALORIES -> src.calorieBlocks.takeIf { it.isNotEmpty() }?.let { b -> TileGraphic.Bars(b.takeLast(CALORIE_BARS).map { it.toFloat() }) } ?: line()
            Metric.CADENCE, Metric.AVG_CADENCE -> {
                val (min, max) = TileGraphics.cadenceScale(s.cadenceLow, s.cadenceHigh)
                TileGraphic.Band(src.value(m)?.toFloat(), s.cadenceLow.toFloat(), s.cadenceHigh.toFloat(), min, max)
            }
            else -> line()
        }
        val graphColor = if (look.family == TileFamily.OTHER) p.accent else accent
        return TileModel(m.label, m.format(src.value(m), u), m.unit(u), graphic, graphColor, look.icon, accent, showLabel = !look.named)
    }

    private const val CALORIE_BARS = 8
    /** A descent stretch at this grade or steeper is flagged on the Descent panel. */
    private const val STEEP_DESCENT_PERCENT = 10.0

    private fun content(src: RideSource, panel: Panel, p: Palette): PanelContent {
        val s = src.settings
        val profile = src.profile
        val u = profile.units
        return when (panel) {
            Panel.RIDE -> {
                val powerZone = ZoneMath.zoneOf(src.value(Metric.POWER_3S) ?: src.value(Metric.POWER), profile.powerZones)
                val hrZone = ZoneMath.zoneOf(src.value(Metric.HEART_RATE), profile.hrZones)
                RideContent(
                    s.rideTiles.map { m ->
                        tile(
                            src, m, p,
                            powerColor = powerZone?.let { ZoneColors.color(ZoneKind.POWER, it, profile.powerZones.size) },
                            hrColor = hrZone?.let { ZoneColors.color(ZoneKind.HR, it, profile.hrZones.size) },
                        )
                    },
                )
            }

            Panel.EFFORT -> {
                val usePower = src.powerAvailable || src.history(Metric.POWER_3S, 600).any { it != null }
                val metric = if (usePower) Metric.POWER_3S else Metric.HEART_RATE
                val zones = if (usePower) profile.powerZones else profile.hrZones
                val kind = if (usePower) ZoneKind.POWER else ZoneKind.HR
                val samples = src.history(metric, 600).map { it?.toDouble() ?: Double.NaN }.toDoubleArray()
                val bars = bucketAverages(samples, 600, 30).map { v ->
                    val zone = ZoneMath.zoneOf(v, zones)
                    v?.toFloat() to (zone?.let { ZoneColors.color(kind, it, zones.size) } ?: p.faint)
                }
                val scaleMax = maxOf(
                    bars.maxOfOrNull { it.first ?: 0f } ?: 0f,
                    if (usePower) profile.ftp * 1.2f else profile.maxHr.toFloat() * 0.8f,
                )
                val distance = src.value(Metric.DISTANCE)
                val behind = src.terrainBehind(5_000.0)
                val ahead = src.terrainAhead(5_000.0)
                val here = distance ?: behind.lastOrNull()?.distance ?: 0.0
                val terrain = behind.map { (it.distance - here).toFloat() to it.elevation.toFloat() } +
                    ahead.map { it.distance.toFloat() to it.elevation.toFloat() }
                val wBal = src.wPrimeBalance
                EffortContent(
                    bars = bars,
                    barMax = scaleMax,
                    wPrime = Stat("W′ BAL", Fmt.decimal(wBal?.div(1000), 1), "kJ"),
                    wPrimeFraction = wBal?.let { (it / src.wPrimeMax).toFloat().coerceIn(0f, 1f) },
                    stats = listOf(
                        Stat("GRADE", Fmt.decimal(src.value(Metric.GRADE), 1), "%"),
                        Stat("VAM", Fmt.int(src.value(Metric.VAM)), "m/h"),
                        Stat("ASCENT", Fmt.elevation(src.value(Metric.ASCENT), u), Fmt.elevationUnit(u)),
                    ),
                    terrain = terrain,
                    terrainLabel = if (ahead.isNotEmpty()) "ROUTE ±5 ${Fmt.distanceUnit(u).uppercase()}" else "LAST 5 ${Fmt.distanceUnit(u).uppercase()}",
                )
            }

            Panel.WEATHER -> weather(src, p)

            Panel.LAP -> {
                val lap = src.value(Metric.LAP_NUMBER)?.toInt()
                val hasPrevious = (lap ?: 1) > 1
                LapContent(
                    title = lap?.let { "LAP $it" + if (hasPrevious) " VS LAP ${it - 1}" else "" } ?: "LAP",
                    fields = s.lapFields.map { lm ->
                        val cur = src.value(lm.current)
                        val prev = if (hasPrevious) src.value(lm.previous) else null
                        LapFieldModel(
                            lm.label, lm.format(cur, u), lm.unit(u), prev?.let { lm.format(it, u) },
                            // A running lap's time or distance is always short of the last: no arrow for those.
                            if (hasPrevious && lm.comparable) LapCompare.trend(cur, prev) else Trend.NONE,
                            delta = lm.delta(cur, prev, u),
                        )
                    },
                )
            }

            Panel.ANALYSIS -> {
                val powerSeconds = src.powerZoneSeconds
                val usePower = powerSeconds.sum() > 0
                val seconds = if (usePower) powerSeconds else src.hrZoneSeconds
                val kind = if (usePower) ZoneKind.POWER else ZoneKind.HR
                val count = maxOf(seconds.size, if (usePower) profile.powerZones.size else profile.hrZones.size)
                val maxSec = (seconds.maxOrNull() ?: 0L).coerceAtLeast(1L)
                AnalysisContent(
                    best = listOf(5 to "5S", 60 to "1M", 300 to "5M", 1200 to "20M").map { (sec, label) ->
                        Stat(label, Fmt.int(src.best(sec)), "W")
                    },
                    zoneTitle = if (usePower) "TIME IN POWER ZONES" else "TIME IN HR ZONES",
                    zones = (1..count).map { z ->
                        val sec = seconds.getOrElse(z - 1) { 0L }
                        ZoneBar("Z$z", sec, sec.toFloat() / maxSec, ZoneColors.color(kind, z, count))
                    },
                    totals = listOf(
                        Stat("NP", Fmt.int(src.value(Metric.NP)), "W"),
                        Stat("TSS", Fmt.int(src.value(Metric.TSS))),
                        Stat("IF", Fmt.decimal(src.value(Metric.IF), 2)),
                        Stat("CLIMB", Fmt.elevation(src.value(Metric.ASCENT), u), Fmt.elevationUnit(u)),
                        Stat("WORK", Fmt.int(src.value(Metric.WORK)), "kJ"),
                    ),
                )
            }

            Panel.WORKOUT -> WorkoutContent(src.workout, src.workout?.kind?.unit.orEmpty(), src.workoutNext?.let { PlanText.summary(it) })

            Panel.BIKE -> {
                val g = src.gearing
                val b = src.batteries
                val left = src.value(Metric.BALANCE)
                BikeContent(
                    gearing = g,
                    gears = if (g == null) Fmt.NONE else buildString {
                        if (!g.isOneBy) append("F ${g.frontGear ?: "-"}/${g.frontMax ?: "-"}  ")
                        append("R ${g.rearGear ?: "-"}/${g.rearMax ?: "-"}")
                    },
                    teeth = if (g?.frontTeeth != null && g.rearTeeth != null) "${g.frontTeeth}T · ${g.rearTeeth}T" else "",
                    batteries = buildList {
                        if (b.frontDerailleur != null) add("FD" to b.frontDerailleur)
                        if (b.rearDerailleur != null) add("RD" to b.rearDerailleur)
                        if (b.frontDerailleur == null && b.rearDerailleur == null) add("SHIFT" to b.shifting)
                        add("PM" to b.powerMeter)
                    },
                    balance = Stat("BALANCE", Fmt.balance(left)),
                    balanceLeft = left?.let { (it / 100).toFloat() },
                    stats = listOf(
                        Stat("SHIFTS", Fmt.int(src.value(SHIFT_COUNT))),
                        Stat("RATIO", Fmt.decimal(g?.ratio, 2)),
                        Stat("CADENCE", Fmt.int(src.value(Metric.CADENCE)), "rpm"),
                    ),
                )
            }

            Panel.COACH -> coach(src)
            Panel.NUMBER -> NumberContent

            Panel.CLIMB -> climb(src)

            Panel.DESCENT -> descent(src)

            Panel.SEGMENT -> segment(src)

            Panel.RESULTS -> results(src)

            Panel.FUEL -> fuel(src)

            Panel.PACE -> pace(src, p)
        }
    }

    /**
     * The Dash Strip: the chosen values, with the segment gap, the workout target and the distance to
     * the top taking the first places while they matter (unless switched off).
     */
    fun strip(src: RideSource, systemDark: Boolean): StripModel {
        val s = src.settings
        val p = palette(s.theme, systemDark)
        val u = src.profile.units
        val base = s.stripMetrics.map { m ->
            val v = src.value(m)
            val color = when (m) {
                Metric.HEADWIND -> v?.let { if (it > 1) p.bad else if (it < -1) p.good else null }
                Metric.CARBS_BALANCE -> v?.let { if (it < -src.settings.gelCarbs * 2) p.warn else p.good }
                Metric.STAMINA_NOW, Metric.STAMINA -> v?.let { if (it < 25) p.bad else null }
                Metric.PACE_AHEAD -> v?.let { if (it >= 0) p.good else p.warn }
                else -> null
            }
            StripCell(m.label, m.format(v, u), m.unit(u), color)
        }
        if (!s.stripContext) return StripModel(p, base)
        val context = buildList {
            src.segment?.takeIf { it.phase != SegmentPhase.APPROACHING }?.let { seg ->
                val d = seg.reading.prDelta
                add(StripCell("VS PR", SegmentTracker.delta(d), "", d?.takeIf { it != 0.0 }?.let { if (it < 0) p.good else p.bad }))
            }
            src.workout?.let { w ->
                if (w.low != null && w.high != null) {
                    add(StripCell("TARGET", "${w.low}–${w.high}", w.kind.unit, if (w.status == TargetStatus.IN_RANGE) p.good else if (w.status == TargetStatus.NO_DATA) null else p.warn))
                }
            }
            src.climb?.progress?.takeIf { it.onClimb || it.toStart <= 500 }?.let { c ->
                add(StripCell(if (c.onClimb) "TO TOP" else "CLIMB IN", Fmt.distance(if (c.onClimb) c.toTop else c.toStart, u), Fmt.distanceUnit(u)))
            }
        }
        val keep = (base.size - context.size).coerceAtLeast(base.size.coerceAtMost(3) - context.size).coerceAtLeast(0)
        return StripModel(p, context + base.take(keep))
    }

    private fun results(src: RideSource): ResultsContent {
        val u = src.profile.units
        val km = Fmt.distanceUnit(u)
        val weight = src.profile.weightKg.takeIf { it > 0 }
        return ResultsContent(
            src.results.map { r ->
                when (r) {
                    is ClimbResult -> ResultRow(
                        title = "CLIMB ${r.number}/${r.total} · ${Fmt.distance(r.length, u)} $km · ${Fmt.decimal(r.gain / r.length * 100, 1)}%",
                        main = Fmt.seconds(r.seconds.toLong()),
                        detail = listOfNotNull(
                            r.avgPower?.let { "${Fmt.int(it)} W" },
                            r.avgPower?.let { p -> weight?.let { "${Fmt.decimal(p / it, 1)} W/kg" } },
                            r.vam?.let { "VAM ${Fmt.int(it)}" },
                        ).joinToString(" · "),
                        good = null,
                    )
                    is SegmentResult -> ResultRow(
                        title = "SEGMENT" + (r.length?.let { " · ${Fmt.distance(it, u)} $km" } ?: ""),
                        main = Fmt.seconds(r.seconds.toLong()),
                        detail = listOfNotNull(
                            r.prDelta?.let { "PR ${Fmt.delta(it)}" },
                            r.komDelta?.let { "KOM ${Fmt.delta(it)}" },
                            r.avgPower?.let { "${Fmt.int(it)} W" },
                        ).joinToString(" · "),
                        good = r.prDelta?.takeIf { it != 0.0 }?.let { it < 0 },
                    )
                }
            },
        )
    }

    private fun fuel(src: RideSource): FuelContent {
        val s = src.settings
        val tracker = FuelTracker().apply { restore(src.fuel) }
        val f = src.fuel
        val target = tracker.targetCarbs(s.carbsPerHour)
        fun minutes(seconds: Long?) = when {
            seconds == null -> null
            seconds <= 0 -> 0L
            else -> (seconds + 59) / 60
        }
        val eatMin = minutes(tracker.secondsUntilEat(s.carbsPerHour, s.gelCarbs))
        val drinkMin = minutes(tracker.secondsUntilDrink(s.drinkMlPerHour, s.bottleMl / 4))
        val balance = f.eatenG - f.burnedG
        // Burn rate now from power when there is any, else this ride's average.
        val power = src.value(Metric.POWER_3S)
        val ftp = src.profile.ftp.toDouble()
        val nowRate = if (power != null && ftp > 0) FuelTracker.carbGrams(power / 1000 * FuelTracker.KCAL_PER_KJ_WORK, power / ftp) * 3600 else null
        val avgRate = if (f.movingS > 60) f.burnedG / f.movingS * 3600 else null
        return FuelContent(
            title = "FUEL · ${s.carbsPerHour} g/h · BURN ${Fmt.int(nowRate ?: avgRate)} g/h",
            eaten = Stat("EATEN", Fmt.int(f.eatenG), "g"),
            burned = Stat("BURNED", Fmt.int(f.burnedG), "g"),
            balance = Stat("BALANCE", (if (balance >= 0) "+" else "") + Fmt.int(balance), "g"),
            onTrack = target <= 0 || f.eatenG + s.gelCarbs >= target,
            targetFraction = if (target > 0) (f.eatenG / target).toFloat() else null,
            eat = when (eatMin) {
                null -> "EAT: NO TARGET"
                0L -> "EAT NOW"
                else -> "EAT IN $eatMin MIN"
            },
            eatDue = eatMin == 0L,
            drink = when (drinkMin) {
                null -> "DRINK: NO TARGET"
                0L -> "DRINK NOW"
                else -> "DRINK IN $drinkMin MIN · ${Fmt.int(f.drankMl)} ml"
            },
            drinkDue = drinkMin == 0L,
            hint = "TAP TO LOG A ${s.tapLogs.label} (${s.fuelSizes.carbs(s.tapLogs)} g)",
        )
    }

    private fun pace(src: RideSource, palette: Palette): PaceContent {
        val u = src.profile.units
        val p = src.pace
            ?: return PaceContent("PACE", Fmt.NONE, null, null, null, TargetStatus.NO_DATA, "", emptyList(), "Navigate a route\nto pace it")
        val next = p.next
        val stamina = src.value(Metric.STAMINA)
        return PaceContent(
            title = "PACE · ${p.intensity}% FTP",
            target = "${p.target}",
            low = p.low,
            high = p.high,
            output = p.output,
            status = p.status,
            next = if (next != null && p.toNext != null) {
                "NEXT ${next.power} W IN ${Fmt.distance(p.toNext, u)} ${Fmt.distanceUnit(u).uppercase()}"
            } else {
                "STEADY TO THE END"
            },
            stats = listOf(
                Stat("VS PLAN", p.aheadS?.let { Fmt.delta(it * 1000) } ?: Fmt.NONE, accent = p.aheadS?.let { if (it >= 0) palette.good else palette.warn }),
                Stat("ETA", Fmt.clock(p.etaMs.toDouble())),
                Stat("TO GO", Fmt.seconds(p.remainingS.toLong())),
                Stat("STAMINA", Fmt.int(stamina), "%"),
            ),
        )
    }

    private fun segment(src: RideSource): SegmentContent {
        val u = src.profile.units
        val st = src.segment
            ?: return SegmentContent("SEGMENT", Stat("", Fmt.NONE), null, Stat("", Fmt.NONE), null, null, emptyList(), "No Strava Live Segment\nnearby")
        val r = st.reading
        fun ahead(ms: Double?) = ms?.takeIf { it.isFinite() && it != 0.0 }?.let { it < 0 }
        return if (st.phase == SegmentPhase.APPROACHING) {
            SegmentContent(
                title = "SEGMENT IN ${Fmt.elevation(r.distanceAway, u)} ${Fmt.elevationUnit(u)}",
                main = Stat("YOUR PR", Fmt.duration(r.pr)),
                mainAhead = null,
                second = Stat("KOM", Fmt.duration(r.kom)),
                secondAhead = null,
                progress = null,
                stats = listOf(Stat("LENGTH", Fmt.distance(r.totalDistance, u), Fmt.distanceUnit(u))),
            )
        } else {
            SegmentContent(
                title = if (st.phase == SegmentPhase.DONE) "SEGMENT DONE" else "SEGMENT",
                main = Stat("VS PR", SegmentTracker.delta(r.prDelta)),
                mainAhead = ahead(r.prDelta),
                second = Stat("VS KOM", SegmentTracker.delta(r.komDelta)),
                secondAhead = ahead(r.komDelta),
                progress = if (st.phase == SegmentPhase.DONE) 1f else r.progress,
                stats = listOf(
                    Stat("TIME", Fmt.duration(r.elapsed)),
                    Stat("LEFT", Fmt.distance(r.distanceLeft, u), Fmt.distanceUnit(u)),
                    Stat("UP", Fmt.elevation(r.elevationLeft, u), Fmt.elevationUnit(u)),
                ),
            )
        }
    }

    private fun climb(src: RideSource): ClimbContent {
        val u = src.profile.units
        val view = src.climb ?: return ClimbContent("CLIMB", null, false, emptyList(), emptyList(), emptyList(), null, "No climb ahead\non this route")
        val p = view.progress
        val c = p.climb
        val km = Fmt.distanceUnit(u)
        val vam = src.value(Metric.VAM)
        val speed = src.value(Metric.SPEED)
        val grade = Stat("GRADE", Fmt.decimal(src.value(Metric.GRADE), 1), "%")
        // Time to the top at the current climbing rate, else at the current speed.
        val topInS = when {
            vam != null && vam > 50 -> p.elevationToTop / vam * 3600
            speed != null && speed > 1 -> p.toTop / speed
            else -> null
        }
        val emptyInS = src.value(Metric.W_PRIME_EMPTY)?.div(1000)
        val big: List<Stat>
        val stats: List<Stat>
        if (p.onClimb) {
            big = listOf(
                Stat("TO TOP", Fmt.distance(p.toTop, u), km),
                Stat("UP", Fmt.elevation(p.elevationToTop, u), Fmt.elevationUnit(u)),
            )
            stats = listOf(
                Stat("AVG LEFT", Fmt.decimal(p.avgGradeLeft, 1), "%"),
                grade,
                Stat("VAM", Fmt.int(vam), "m/h"),
                Stat("TOP IN", topInS?.let { Fmt.seconds(it.toLong()) } ?: Fmt.NONE),
            )
        } else {
            big = listOf(
                Stat("LENGTH", Fmt.distance(c.length, u), km),
                Stat("GAIN", Fmt.elevation(c.gain, u), Fmt.elevationUnit(u)),
            )
            stats = listOf(
                Stat("AVG", Fmt.decimal(c.avgGrade, 1), "%"),
                Stat("MAX", Fmt.decimal(c.maxGrade, 1), "%"),
                grade,
                Stat("VAM", Fmt.int(vam), "m/h"),
            )
        }
        val lowest = view.slices.minOfOrNull { minOf(it.fromElevation, it.toElevation) } ?: 0.0
        val span = ((view.slices.maxOfOrNull { maxOf(it.fromElevation, it.toElevation) } ?: 0.0) - lowest).coerceAtLeast(1.0)
        return ClimbContent(
            title = "CLIMB ${p.number}/${p.total}" + if (p.onClimb) "" else " IN ${Fmt.distance(p.toStart, u)} ${km.uppercase()}",
            effort = when {
                !src.powerAvailable -> null
                emptyInS != null -> "W′ EMPTY IN ${Fmt.seconds(emptyInS.toLong())}"
                else -> "W′ OK"
            },
            effortWarning = emptyInS != null && (emptyInS < 60 || (p.onClimb && topInS != null && emptyInS < topInS)),
            big = big,
            stats = stats,
            bars = view.slices.map {
                ClimbBar(
                    from = (it.from / c.length).toFloat(),
                    to = (it.to / c.length).toFloat(),
                    fromHeight = ((it.fromElevation - lowest) / span).toFloat(),
                    toHeight = ((it.toElevation - lowest) / span).toFloat(),
                    grade = it.grade.toFloat(),
                )
            },
            rider = view.riderAt?.let { (it / c.length).toFloat().coerceIn(0f, 1f) },
        )
    }

    /**
     * The Climb panel's mirror image: to the bottom and the drop large, the descent's profile going
     * downhill, then four stats. Grades show negative, as the rider's grade field does.
     */
    private fun descent(src: RideSource): ClimbContent {
        val u = src.profile.units
        val view = src.descent
            ?: return ClimbContent("DESCENT", null, false, emptyList(), emptyList(), emptyList(), null, "No descent ahead\non this route", descent = true)
        val p = view.progress
        val d = p.descent
        val km = Fmt.distanceUnit(u)
        val speed = src.value(Metric.SPEED)
        val grade = Stat("GRADE", Fmt.decimal(src.value(Metric.GRADE), 1), "%")
        val speedStat = Stat("SPEED", Fmt.speed(speed, u), Fmt.speedUnit(u))
        val bottomInS = speed?.takeIf { it > 1 }?.let { p.toBottom / it }
        // The steepest stretch still ahead (a negative grade), the one to be ready for.
        val steepestAhead = view.slices.filter { it.to >= (view.riderAt ?: 0.0) }.minOfOrNull { it.grade }
        val big: List<Stat>
        val stats: List<Stat>
        if (p.onDescent) {
            big = listOf(
                Stat("TO BOTTOM", Fmt.distance(p.toBottom, u), km),
                Stat("DOWN", Fmt.elevation(p.elevationToBottom, u), Fmt.elevationUnit(u)),
            )
            stats = listOf(
                Stat("AVG LEFT", Fmt.decimal(p.avgGradeLeft?.let { -it }, 1), "%"),
                grade,
                speedStat,
                Stat("BOTTOM IN", bottomInS?.let { Fmt.seconds(it.toLong()) } ?: Fmt.NONE),
            )
        } else {
            big = listOf(
                Stat("LENGTH", Fmt.distance(d.length, u), km),
                Stat("DROP", Fmt.elevation(d.drop, u), Fmt.elevationUnit(u)),
            )
            stats = listOf(
                Stat("AVG", Fmt.decimal(-d.avgGrade, 1), "%"),
                Stat("MAX", Fmt.decimal(-d.maxGrade, 1), "%"),
                grade,
                speedStat,
            )
        }
        val lowest = view.slices.minOfOrNull { minOf(it.fromElevation, it.toElevation) } ?: 0.0
        val span = ((view.slices.maxOfOrNull { maxOf(it.fromElevation, it.toElevation) } ?: 0.0) - lowest).coerceAtLeast(1.0)
        return ClimbContent(
            title = "DESCENT ${p.number}/${p.total}" + if (p.onDescent) "" else " IN ${Fmt.distance(p.toStart, u)} ${km.uppercase()}",
            effort = steepestAhead?.takeIf { p.onDescent }?.let { "STEEPEST ${Fmt.decimal(it, 1)} %" },
            effortWarning = p.onDescent && steepestAhead != null && steepestAhead <= -STEEP_DESCENT_PERCENT,
            big = big,
            stats = stats,
            bars = view.slices.map {
                ClimbBar(
                    from = (it.from / d.length).toFloat(),
                    to = (it.to / d.length).toFloat(),
                    fromHeight = ((it.fromElevation - lowest) / span).toFloat(),
                    toHeight = ((it.toElevation - lowest) / span).toFloat(),
                    grade = it.grade.toFloat(),
                )
            },
            rider = view.riderAt?.let { (it / d.length).toFloat().coerceIn(0f, 1f) },
            descent = true,
        )
    }

    private fun coach(src: RideSource): CoachContent {
        val info = src.coach ?: return CoachContent("NOT INSTALLED", true, "", null, "Install Pangu Coach to get messages from your coach here", true, false, "")
        val now = src.nowMs
        val status = CoachStatus.of(info, now)
        val m = info.message
        return CoachContent(
            status = status.label,
            warning = status.warning,
            age = m?.let { CoachText.ago(now - it.receivedAt) }.orEmpty(),
            title = m?.title?.takeUnless { it.equals("Coach", ignoreCase = true) }?.uppercase(),
            text = m?.text ?: if (info.configured) "Messages from your coach appear here" else "Set up Pangu Coach from your computer, see its README",
            placeholder = m == null,
            unread = info.unread,
            hint = when {
                m == null -> ""
                info.acknowledged -> "READ"
                else -> "TAP TO CONFIRM"
            },
        )
    }

    private fun weather(src: RideSource, p: Palette): WeatherContent {
        val u = src.profile.units
        val w = src.weather
        val heading = src.heading
        if (!src.settings.weatherEnabled) return WeatherContent("Weather is off in Pangu Dash settings", null, Fmt.NONE, "", null, emptyList(), emptyList())
        if (w == null) {
            val msg = when (src.weatherStatus) {
                io.github.mschache.karoopro.weather.WeatherStatus.NO_POSITION -> "Waiting for GPS position…"
                io.github.mschache.karoopro.weather.WeatherStatus.ERROR -> "Weather unavailable\nNeeds WiFi or the Companion app"
                else -> "Loading weather…"
            }
            return WeatherContent(msg, null, Fmt.NONE, "", null, emptyList(), emptyList())
        }
        val comp = if (heading != null && w.windSpeed != null && w.windFromDeg != null) Wind.components(w.windSpeed, w.windFromDeg, heading) else null
        val toDeg = w.windFromDeg?.let { Wind.normalize(it + 180 - (heading ?: 0.0)).toFloat() }
        val (label, value) = when (comp?.kind) {
            null -> "WIND" to w.windSpeed
            io.github.mschache.karoopro.calc.WindKind.HEAD -> "HEAD" to comp.head
            io.github.mschache.karoopro.calc.WindKind.TAIL -> "TAIL" to -comp.head
            io.github.mschache.karoopro.calc.WindKind.CROSS -> "CROSS" to kotlin.math.abs(comp.cross)
        }
        return WeatherContent(
            message = null,
            kind = comp?.kind,
            component = label + " " + Fmt.int(value?.let { Fmt.speedValue(it, u) }),
            unit = Fmt.speedUnit(u),
            relativeToDeg = toDeg,
            stats = listOf(
                Stat("WIND", Fmt.int(w.windSpeed?.let { Fmt.speedValue(it, u) }), Fmt.speedUnit(u)),
                Stat("GUST", Fmt.int(w.gust?.let { Fmt.speedValue(it, u) }), Fmt.speedUnit(u)),
                Stat("RAIN", Fmt.int(w.rainChance?.toDouble()), "%"),
                Stat("TEMP", Fmt.temperature(w.temperature, u), Fmt.temperatureUnit(u)),
            ),
            hours = w.hours.map { h ->
                val c = if (heading != null && h.windSpeed != null && h.windFromDeg != null) Wind.components(h.windSpeed, h.windFromDeg, heading) else null
                HourModel(
                    label = hourFormat.format(Instant.ofEpochSecond(h.epochSeconds)),
                    speed = Fmt.int(h.windSpeed?.let { Fmt.speedValue(it, u) }),
                    relativeToDeg = h.windFromDeg?.let { Wind.normalize(it + 180 - (heading ?: 0.0)).toFloat() },
                    kind = c?.kind,
                )
            },
        )
    }

    private fun overlay(src: RideSource, s: Settings): OverlayModel? {
        if (s.alertStyle == AlertStyle.OFF) return null
        val full = s.alertStyle == AlertStyle.FULL
        return when (val o = src.overlay) {
            null -> null
            is WorkoutOverlay.NextStep -> OverlayModel.NextStep(
                o.nextStep, o.stepCount, o.secondsLeft, full,
                target = o.plan?.let { PlanText.target(it) },
                duration = o.plan?.let { Fmt.seconds(it.seconds.toLong()) },
            )
            is WorkoutOverlay.NewTarget -> {
                val st = o.state
                OverlayModel.NewTarget(
                    title = "STEP ${st.stepNumber}/${st.stepCount} · ${st.kind.label}",
                    target = if (st.low != null && st.high != null) "${st.low}–${st.high}" else "GO",
                    unit = st.kind.unit,
                    full = full,
                )
            }
        }
    }
}
