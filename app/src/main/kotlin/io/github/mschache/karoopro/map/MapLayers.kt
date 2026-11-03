package io.github.mschache.karoopro.map

import android.util.Log
import io.github.mschache.karoopro.R
import io.github.mschache.karoopro.calc.GeoPoint
import io.github.mschache.karoopro.calc.GradeRuns
import io.github.mschache.karoopro.calc.RouteGeometry
import io.github.mschache.karoopro.calc.RouteRun
import io.github.mschache.karoopro.calc.RouteWind
import io.github.mschache.karoopro.calc.Wind
import io.github.mschache.karoopro.calc.WindKind
import io.github.mschache.karoopro.calc.WindRuns
import io.github.mschache.karoopro.calc.encodePolyline
import io.github.mschache.karoopro.data.Metric
import io.github.mschache.karoopro.data.RideDataHub
import io.github.mschache.karoopro.data.RouteTracker
import io.github.mschache.karoopro.data.Settings
import io.github.mschache.karoopro.karoo.httpGet
import io.github.mschache.karoopro.ui.field.DESCENT_COLORS
import io.github.mschache.karoopro.weather.OpenMeteo
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.floor

/**
 * Pangu Dash's layers on top of the Karoo map while navigating: climbs as a wide grey-to-black band by
 * grade, the wind on the route ahead as a thinner line on top, and wind arrows every 5 km.
 */
class MapLayers(
    private val hub: RideDataHub,
    private val routes: RouteTracker,
    private val settings: StateFlow<Settings>,
    private val scope: CoroutineScope,
    private val memory: MapLayerMemory,
) {
    /** Wind samples along the route, valid for one route and a start distance. */
    private class WindCache(val routeKey: String, val fromAlong: Double, val fetchedAt: Long, val samples: List<Pair<GeoPoint, RouteWind>>)

    private var wind: WindCache? = null
    private var nextWindFetch = 0L

    fun run(emitter: Emitter<MapEffect>): Job = scope.launch {
        val shownLines = LinkedHashMap<String, String>() // id -> signature
        var shownSymbols: List<String> = emptyList()
        var lastSignature = ""
        val keep = hub.keepAlive(listOf(DataType.Type.DISTANCE_TO_DESTINATION, DataType.Type.AVERAGE_SPEED))
        Log.i(TAG, "map layer started")
        // Lines from a previous process (update or restart mid-ride) would otherwise stay on the map.
        memory.takeLeftovers().takeUnless { it.isEmpty }?.let { old ->
            old.lines.forEach { emitter.onNext(HidePolyline(it)) }
            if (old.symbols.isNotEmpty()) emitter.onNext(HideSymbols(old.symbols))
            Log.i(TAG, "cleared ${old.lines.size} leftover lines and ${old.symbols.size} arrows")
        }
        var lastState = ""
        try {
            while (isActive) {
                try {
                    val s = settings.value
                    val r = routes.current()
                    val anyLayer = s.mapWindRoute || s.mapClimbs || s.mapDescents || s.mapWindArrows
                    val state = "nav=${hub.navigation.value?.javaClass?.simpleName} route=${r?.geometry?.length?.toInt()} m " +
                        "profile=${r?.profile?.size} wind=${wind?.samples?.size}"
                    if (state != lastState) {
                        Log.i(TAG, "map layer: $state")
                        lastState = state
                    }
                    if (r == null || !anyLayer) {
                        clear(emitter, shownLines, shownSymbols)
                        shownSymbols = emptyList()
                        lastSignature = ""
                    } else {
                        val along = alongRoute(r)
                        if ((s.mapWindRoute || s.mapWindArrows) && s.weatherEnabled) refreshWind(r, along)
                        val (lines, symbols) = layers(s, r, along)
                        val signature = lines.joinToString("|") { it.id + it.color + it.width + it.encodedPolyline.length } +
                            symbols.joinToString("|") { (it as Symbol.Icon).let { i -> i.id + i.iconRes + i.orientation } }
                        if (signature != lastSignature) {
                            // Redraw in order (climb band under the wind line) so the layering stays right.
                            shownLines.keys.forEach { emitter.onNext(HidePolyline(it)) }
                            shownLines.clear()
                            lines.forEach {
                                emitter.onNext(it)
                                shownLines[it.id] = it.encodedPolyline
                            }
                            val ids = symbols.map { it.id }
                            val gone = shownSymbols - ids.toSet()
                            if (gone.isNotEmpty()) emitter.onNext(HideSymbols(gone))
                            if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
                            shownSymbols = ids
                            lastSignature = signature
                            memory.remember(shownLines.keys, shownSymbols)
                            val start = r.geometry.pointAt(along)
                            Log.i(TAG, "map layers: ${lines.size} lines, ${symbols.size} arrows at ${along.toInt()} m, from %.5f,%.5f".format(start.lat, start.lng))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "map layers failed", e)
                }
                delay(TICK_MS)
            }
        } finally {
            Log.i(TAG, "map layer stopped")
            keep.cancel()
        }
    }

    private fun clear(emitter: Emitter<MapEffect>, lines: MutableMap<String, String>, symbols: List<String>) {
        lines.keys.forEach { emitter.onNext(HidePolyline(it)) }
        lines.clear()
        if (symbols.isNotEmpty()) emitter.onNext(HideSymbols(symbols))
        memory.remember(emptyList(), emptyList())
    }

    /** Meters along the route, in 500 m steps so the layers redraw every 500 m, not every meter. */
    private fun alongRoute(r: RouteTracker.Route): Double = floor(routes.along(r) / STEP_M) * STEP_M

    private suspend fun refreshWind(r: RouteTracker.Route, along: Double) {
        val now = System.currentTimeMillis()
        val w = wind
        val stale = w == null || w.routeKey != r.key || along - w.fromAlong > REFETCH_ALONG_M || now - w.fetchedAt > REFRESH_MS
        if (!stale || now < nextWindFetch) return
        val end = minOf(r.geometry.length, along + WIND_AHEAD_M)
        val distances = generateSequence(along) { it + SAMPLE_EVERY_M }.takeWhile { it <= end }.toList()
        if (distances.isEmpty()) return
        val points = distances.map { r.geometry.pointAt(it) }
        val speed = hub.value(Metric.AVG_SPEED)?.takeIf { it > 2.0 } ?: DEFAULT_SPEED_MS
        val etas = distances.map { now / 1000 + ((it - along) / speed).toLong() }
        nextWindFetch = try {
            val body = hub.karoo.httpGet(OpenMeteo.routeUrl(points.map { it.lat to it.lng }), mapOf("User-Agent" to "Pangu Dash"))
            val winds = OpenMeteo.parseRoute(body.decodeToString(), etas, now)
            wind = WindCache(
                routeKey = r.key,
                fromAlong = along,
                fetchedAt = now,
                samples = distances.indices.mapNotNull { i ->
                    winds.getOrNull(i)?.let { (speedMs, from) -> points[i] to RouteWind(distances[i], speedMs, from) }
                },
            )
            now + 60_000
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "route wind fetch failed: ${e.message}")
            now + RETRY_MS
        }
    }

    private fun layers(s: Settings, r: RouteTracker.Route, along: Double): Pair<List<ShowPolyline>, List<Symbol>> {
        val g = r.geometry
        val lines = ArrayList<ShowPolyline>()
        val both = (s.mapClimbs || s.mapDescents) && s.mapWindRoute
        if (s.mapClimbs && r.profile.isNotEmpty()) {
            GradeRuns.runs(r.elevation, along, minOf(g.length, along + CLIMB_AHEAD_M)).forEachIndexed { i, run ->
                lines += line("climb-$i", g, run, CLIMB_COLORS[run.bucket], if (both) 16 else 10)
            }
        }
        if (s.mapDescents && r.profile.isNotEmpty()) {
            GradeRuns.runs(r.elevation, along, minOf(g.length, along + CLIMB_AHEAD_M), descents = true).forEachIndexed { i, run ->
                lines += line("descent-$i", g, run, DESCENT_COLORS[run.bucket], if (both) 16 else 10)
            }
        }
        val samples = wind?.takeIf { it.routeKey == r.key }?.samples.orEmpty()
        if (s.mapWindRoute && samples.isNotEmpty()) {
            WindRuns.runs(g, samples.map { it.second }, along, minOf(g.length, along + WIND_AHEAD_M)).forEachIndexed { i, run ->
                lines += line("wind-$i", g, run, WIND_COLORS[run.bucket], if (both) 6 else 10)
            }
        }
        val symbols = if (s.mapWindArrows) {
            samples.filter { it.second.distance >= along && it.second.speed >= 1.0 }.mapIndexed { i, (point, w) ->
                val kind = Wind.components(w.speed, w.fromDeg, g.bearingAt(w.distance)).kind
                Symbol.Icon(
                    id = "wind-arrow-$i",
                    lat = point.lat,
                    lng = point.lng,
                    iconRes = when (kind) {
                        WindKind.TAIL -> R.drawable.ic_map_wind_tail
                        WindKind.CROSS -> R.drawable.ic_map_wind_cross
                        WindKind.HEAD -> R.drawable.ic_map_wind_head
                    },
                    // Arrow points where the wind blows to.
                    orientation = ((w.fromDeg + 180) % 360).toFloat(),
                )
            }
        } else {
            emptyList()
        }
        return lines to symbols
    }

    private fun line(id: String, g: RouteGeometry, run: RouteRun, color: Int, width: Int) =
        ShowPolyline(id, encodePolyline(g.slice(run.from, run.to)), color, width)

    companion object {
        private const val TAG = "KarooPRO"
        private const val TICK_MS = 5_000L
        private const val STEP_M = 500.0
        private const val WIND_AHEAD_M = 30_000.0
        private const val CLIMB_AHEAD_M = 50_000.0
        private const val SAMPLE_EVERY_M = 5_000.0
        private const val REFETCH_ALONG_M = 5_000.0
        private const val REFRESH_MS = 20 * 60_000L
        private const val RETRY_MS = 2 * 60_000L
        private const val DEFAULT_SPEED_MS = 7.0

        /** Tail, cross, head: the same green / yellow / red as the Weather panel. */
        val WIND_COLORS = intArrayOf(0xFF00C46A.toInt(), 0xFFFFC400.toInt(), 0xFFFF3B30.toInt())

        /**
         * 3–6 %, 6–9 %, 9–12 %, 12 %+ from grey to black: the Karoo draws routes in purple and the map
         * uses greens, yellows and oranges, so a dark band reads as "climb" without clashing.
         */
        val CLIMB_COLORS = intArrayOf(0xFF9A9A9A.toInt(), 0xFF5E5E5E.toInt(), 0xFF2E2E2E.toInt(), 0xFF000000.toInt())
    }
}
