package io.github.mschache.karoopro.ui.field

import io.github.mschache.karoopro.calc.Gearing
import io.github.mschache.karoopro.calc.Trend
import io.github.mschache.karoopro.calc.WindKind
import io.github.mschache.karoopro.calc.WorkoutState
import io.github.mschache.karoopro.data.BigMetric
import io.github.mschache.karoopro.data.Panel
import io.hammerhead.karooext.models.BatteryStatus

/** Colors for one frame; all ARGB ints. */
data class Palette(
    val dark: Boolean,
    val background: Int,
    val card: Int,
    val text: Int,
    val dim: Int,
    val faint: Int,
    val accent: Int,
    val good: Int,
    val warn: Int,
    val bad: Int,
    val push: Int,
    /** Pangu Coach's accent (unread bar, tap to confirm): its lime on the dark screen, never on a light one. */
    val coach: Int = accent,
) {
    companion object {
        val DARK = Palette(
            dark = true,
            background = 0xFF000000.toInt(),
            card = 0xFF17191C.toInt(),
            text = 0xFFFFFFFF.toInt(),
            dim = 0xFFA0A6AD.toInt(),
            faint = 0xFF3A3F45.toInt(),
            accent = 0xFF2E9BFF.toInt(),
            good = 0xFF00C46A.toInt(),
            warn = 0xFFFFC400.toInt(),
            bad = 0xFFFF3B30.toInt(),
            push = 0xFF2E9BFF.toInt(),
            coach = 0xFFD4F700.toInt(),
        )
        val LIGHT = Palette(
            dark = false,
            background = 0xFFFFFFFF.toInt(),
            card = 0xFFF0F1F3.toInt(),
            text = 0xFF000000.toInt(),
            dim = 0xFF5B6168.toInt(),
            faint = 0xFFD5D8DC.toInt(),
            accent = 0xFF0A6FD6.toInt(),
            good = 0xFF00995A.toInt(),
            warn = 0xFFB88A00.toInt(),
            bad = 0xFFD7261E.toInt(),
            push = 0xFF0A6FD6.toInt(),
        )
    }
}

data class FieldModel(
    val palette: Palette,
    val widthDp: Float,
    val heightDp: Float,
    val density: Float,
    val hero: HeroModel,
    val panels: List<Panel>,
    val panel: Panel,
    val content: PanelContent,
    val overlay: OverlayModel?,
    val interactive: Boolean,
    /** Background of the whole field; the zone tint when the color mode tints the page. */
    val pageBackground: Int,
)

data class HeroModel(
    val big: BigMetric,
    val options: List<BigMetric>,
    val value: String,
    val unit: String,
    val zone: Int?,
    val zoneColor: Int?,
    val zoneColors: List<Int>,
    /** Background of the hero block and the color of the big number on it. */
    val background: Int,
    val numberColor: Int,
    val textColor: Int,
    val dimColor: Int,
    val stats: List<Stat>,
    val cards: List<CardModel>,
    /** The line over the pills with what the Karoo's own top bar lacks; null when switched off. */
    val status: StatusLine? = null,
) {
    /** The hero is filled with the zone color, so anything drawn in that color needs its own backing. */
    val filled: Boolean get() = zoneColor != null && background == zoneColor
}

/** Sunset as a time of day ("18:42") and the temperature ("19°"); either may be missing. */
data class StatusLine(val sunset: String?, val temperature: String?)

data class Stat(val label: String, val value: String, val unit: String = "", val accent: Int? = null)

data class CardModel(val label: String, val value: String, val unit: String, val avg: String?, val accent: Int?)

sealed interface PanelContent

/**
 * One Ride tile: [icon] and a side bar in [accent], the unit top right, the value, and [graphic]
 * at the bottom. [label] shows when there's no icon, or when [showLabel] because the icon alone
 * doesn't name the value (average, max, lap...).
 */
data class TileModel(
    val label: String,
    val value: String,
    val unit: String,
    val graphic: TileGraphic?,
    val graphColor: Int,
    val icon: TileIcon? = null,
    val accent: Int = graphColor,
    val showLabel: Boolean = true,
)

/** What a Ride tile draws under its value. */
sealed interface TileGraphic {
    /** 1 Hz history, oldest first, as a line. */
    data class Line(val values: List<Float?>) : TileGraphic

    /** Equal segments, each filled 0..1: hours ridden, or the way to the arrival. */
    data class Segments(val fills: List<Float>) : TileGraphic

    /** Progress along the navigated route, 0..1: a dotted line with a marker. */
    data class Route(val fraction: Float) : TileGraphic

    /** One bar per block, oldest first; the last is the running one. */
    data class Bars(val values: List<Float>) : TileGraphic

    /** Ticks from [min] to [max] with the target band [low]..[high] marked and [value] lit. */
    data class Band(val value: Float?, val low: Float, val high: Float, val min: Float, val max: Float) : TileGraphic
}

data class RideContent(val tiles: List<TileModel>) : PanelContent

data class EffortContent(
    /** Average power per 20 s for the last 10 minutes, oldest first, with its zone color. */
    val bars: List<Pair<Float?, Int>>,
    val barMax: Float,
    val wPrime: Stat,
    val wPrimeFraction: Float?,
    val stats: List<Stat>,
    /** (meters relative to the rider, elevation) pairs; negative = behind. */
    val terrain: List<Pair<Float, Float>>,
    val terrainLabel: String,
) : PanelContent

/** One slice of a climb's profile: [from]/[to] 0..1 along the climb, heights 0..1 from its foot to its top, grade in %. */
data class ClimbBar(val from: Float, val to: Float, val fromHeight: Float, val toHeight: Float, val grade: Float)

data class ClimbContent(
    /** "CLIMB 2/5", or "CLIMB 2/5 IN 1.20 KM" before it starts. */
    val title: String,
    /** "W′ EMPTY IN 3:20" or "W′ OK"; null without a power meter. */
    val effort: String?,
    /** W′ runs out before the top at this power. */
    val effortWarning: Boolean,
    /** Two large values: to the top on the climb, the climb's length and gain before it. */
    val big: List<Stat>,
    val stats: List<Stat>,
    val bars: List<ClimbBar>,
    /** The rider's place 0..1 along the climb; null before it starts. */
    val rider: Float?,
    /** Shown instead of everything else when there's no climb. */
    val message: String? = null,
    /** A descent: the profile goes downhill in blue hues, and [effort] names the steepest stretch ahead, not W′. */
    val descent: Boolean = false,
) : PanelContent

data class SegmentContent(
    /** "SEGMENT", "SEGMENT IN 320 m" or "SEGMENT DONE". */
    val title: String,
    /** Large: the gap to your PR (your PR time before the start). */
    val main: Stat,
    /** True when ahead (green), false when behind (red), null when it isn't a gap. */
    val mainAhead: Boolean?,
    /** The gap to the KOM (the KOM time before the start). */
    val second: Stat,
    val secondAhead: Boolean?,
    /** 0..1 through the segment, when known. */
    val progress: Float?,
    val stats: List<Stat>,
    /** Shown instead of everything else when there's no segment. */
    val message: String? = null,
) : PanelContent

/** One finished climb or segment: [good] colors the main value (ahead of the PR), null leaves it plain. */
data class ResultRow(val title: String, val main: String, val detail: String, val good: Boolean?)

data class ResultsContent(val rows: List<ResultRow>) : PanelContent

data class FuelContent(
    val title: String,
    val eaten: Stat,
    val burned: Stat,
    /** Eaten minus burned, and whether intake keeps up with the target so far. */
    val balance: Stat,
    val onTrack: Boolean,
    /** Eaten as a share of the target so far, 0..1+; null before the target starts. */
    val targetFraction: Float?,
    val eat: String,
    val eatDue: Boolean,
    val drink: String,
    val drinkDue: Boolean,
    val hint: String,
    /** CORE sensor: body temperature, its place on the 36.5–40 °C bar, and the heat strain index. */
    val core: CoreHeat? = null,
) : PanelContent

data class CoreHeat(val temp: String, val unit: String, val barFraction: Float, val hsi: String, val level: io.github.mschache.karoopro.calc.HeatStrain.Level)

data class PaceContent(
    val title: String,
    val target: String,
    val low: Int?,
    val high: Int?,
    val output: Int?,
    val status: io.github.mschache.karoopro.calc.TargetStatus,
    val next: String,
    val stats: List<Stat>,
    val message: String? = null,
) : PanelContent

/** One value on the Dash Strip; [color] overrides the text color. */
data class StripCell(val label: String, val value: String, val unit: String, val color: Int? = null)

data class StripModel(val palette: Palette, val cells: List<StripCell>)

data class HourModel(val label: String, val speed: String, val relativeToDeg: Float?, val kind: WindKind?)

data class WeatherContent(
    val message: String?,
    val kind: WindKind?,
    val component: String,
    val unit: String,
    /** Direction the wind blows towards, relative to the direction of travel (0 = pushing you). */
    val relativeToDeg: Float?,
    val stats: List<Stat>,
    val hours: List<HourModel>,
) : PanelContent

/** [delta] is the change against the previous lap ("+20"), for averages only. */
data class LapFieldModel(val label: String, val value: String, val unit: String, val previous: String?, val trend: Trend, val delta: String? = null)

data class LapContent(val title: String, val fields: List<LapFieldModel>) : PanelContent

data class ZoneBar(val label: String, val seconds: Long, val fraction: Float, val color: Int)

data class AnalysisContent(val best: List<Stat>, val zoneTitle: String, val zones: List<ZoneBar>, val totals: List<Stat>) : PanelContent

/** [next] is the planned next step, e.g. "4:00 @ 280 W", when Pangu Coach's relay knows the workout. */
data class WorkoutContent(val state: WorkoutState?, val unit: String, val next: String? = null) : PanelContent

data class BikeContent(
    val gearing: Gearing?,
    val gears: String,
    val teeth: String,
    val batteries: List<Pair<String, BatteryStatus?>>,
    val balance: Stat,
    val balanceLeft: Float?,
    val stats: List<Stat>,
) : PanelContent

data class CoachContent(
    val status: String,
    val warning: Boolean,
    /** How old the message is, e.g. "2 min ago"; empty without a message. */
    val age: String,
    /** The message title when it says more than "Coach". */
    val title: String?,
    val text: String,
    /** No message yet: [text] is a hint, drawn dim. */
    val placeholder: Boolean,
    val unread: Boolean,
    /** "TAP TO CONFIRM" while unread, "READ" after. */
    val hint: String,
) : PanelContent

/** The big number as a slide; it's drawn from [FieldModel.hero], so it carries nothing itself. */
data object NumberContent : PanelContent

/**
 * The Panels field: one slide condensed for a short, wide slot such as the map page. Every panel
 * becomes a few labeled values ([CompactCells]); the big number and coach messages keep their own
 * look.
 */
data class CompactModel(
    val palette: Palette,
    val density: Float,
    val panels: List<Panel>,
    val panel: Panel,
    /** "RIDE", "CLIMB 2/5", or the big number's name. */
    val title: String,
    val body: CompactBody,
    /** The zone tint on the big number's slide, else the page color. */
    val background: Int,
    val interactive: Boolean,
)

sealed interface CompactBody

data class CompactNumber(val hero: HeroModel) : CompactBody

/** Values side by side (two rows when the slot is tall), an optional bar under them, or just a [message]. */
data class CompactCells(
    val cells: List<StripCell>,
    /** 0..1 progress (W′ left, through the step, climb or segment), drawn as a thin bar. */
    val bar: Float? = null,
    val barColor: Int = 0,
    val message: String? = null,
) : CompactBody

data class CompactText(
    val status: String,
    val warning: Boolean,
    val age: String,
    val text: String,
    val placeholder: Boolean,
    val unread: Boolean,
    val hint: String,
) : CompactBody

sealed interface OverlayModel {
    val full: Boolean

    /** [target] (watts) and [duration] come from the plan, when known. */
    data class NextStep(
        val nextStep: Int,
        val stepCount: Int,
        val secondsLeft: Int,
        override val full: Boolean,
        val target: String? = null,
        val duration: String? = null,
    ) : OverlayModel
    data class NewTarget(val title: String, val target: String, val unit: String, override val full: Boolean) : OverlayModel
}
