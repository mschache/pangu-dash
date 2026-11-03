package io.github.mschache.karoopro.calc

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

/** A route polyline with cumulative distances, for looking things up by distance along the route. */
class RouteGeometry(val points: List<GeoPoint>) {
    /** Meters from the start to each point. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + distance(points[i - 1], points[i])
    }

    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Interpolated position [d] meters along the route (clamped to the route). */
    fun pointAt(d: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        if (d <= 0 || points.size == 1) return points.first()
        if (d >= length) return points.last()
        var i = cumulative.binarySearch(d).let { if (it >= 0) return points[it] else -it - 1 }
        i = i.coerceIn(1, points.size - 1)
        val a = points[i - 1]
        val b = points[i]
        val span = cumulative[i] - cumulative[i - 1]
        val t = if (span > 0) (d - cumulative[i - 1]) / span else 0.0
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    /** Direction of travel at [d], measured over [window] meters (degrees, 0 = north). */
    fun bearingAt(d: Double, window: Double = 60.0): Double =
        bearing(pointAt(d - window / 2), pointAt(d + window / 2))

    /** The route between two distances, with interpolated end points. */
    fun slice(from: Double, to: Double): List<GeoPoint> {
        if (points.isEmpty() || to <= from) return emptyList()
        val out = ArrayList<GeoPoint>()
        out += pointAt(from)
        for (i in points.indices) if (cumulative[i] > from && cumulative[i] < to) out += points[i]
        out += pointAt(to)
        return out
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun fromEncoded(encoded: String): RouteGeometry =
            RouteGeometry(Polyline.decode(encoded, 5).map { GeoPoint(it.first, it.second) })

        fun distance(a: GeoPoint, b: GeoPoint): Double {
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        /** Initial bearing from [a] to [b], degrees 0..360 (0 = north). */
        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val la = Math.toRadians(a.lat)
            val lb = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lng - a.lng)
            val y = sin(dl) * cos(lb)
            val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}

/** A stretch of the route [from]..[to] (meters along it) that is drawn in one color. */
data class RouteRun(val from: Double, val to: Double, val bucket: Int)

/** Joins neighboring runs of the same bucket and drops "no color" runs (bucket < 0). */
fun mergeRuns(runs: List<RouteRun>): List<RouteRun> {
    val out = ArrayList<RouteRun>()
    for (r in runs) {
        val last = out.lastOrNull()
        if (last != null && last.bucket == r.bucket && abs(last.to - r.from) < 1e-6) out[out.size - 1] = last.copy(to = r.to)
        else out += r
    }
    return out.filter { it.bucket >= 0 }
}

object GradeRuns {
    /** Grade buckets: 0 = 3–6 %, 1 = 6–9 %, 2 = 9–12 %, 3 = 12 % and steeper; -1 = flatter than 3 % or downhill. */
    fun bucket(gradePercent: Double): Int = when {
        gradePercent >= 12 -> 3
        gradePercent >= 9 -> 2
        gradePercent >= 6 -> 1
        gradePercent >= 3 -> 0
        else -> -1
    }

    /**
     * Colored climbing stretches between [from] and [to], from an elevation profile of
     * (distance along route, elevation) points. Grade is measured over [window] meters per [step].
     */
    fun runs(profile: List<ProfilePoint>, from: Double, to: Double, step: Double = 100.0, window: Double = 200.0): List<RouteRun> =
        runs(ElevationProfile(profile), from, to, step, window)

    /** With [descents], the downhill stretches instead: bucketed by how steeply the route drops. */
    fun runs(profile: ElevationProfile, from: Double, to: Double, step: Double = 100.0, window: Double = 200.0, descents: Boolean = false): List<RouteRun> {
        if (profile.isEmpty || to <= from) return emptyList()
        val runs = ArrayList<RouteRun>()
        val sign = if (descents) -1 else 1
        var d = from
        while (d < to) {
            val end = minOf(d + step, to)
            runs += RouteRun(d, end, bucket(sign * profile.grade((d + end) / 2, window)))
            d = end
        }
        return mergeRuns(runs)
    }
}

/** Wind seen at one point of the route ahead. */
data class RouteWind(val distance: Double, val speed: Double, val fromDeg: Double)

object WindRuns {
    /** 0 = tailwind, 1 = crosswind, 2 = headwind (matching [WindKind]); -1 = calm. */
    fun bucket(kind: WindKind): Int = when (kind) {
        WindKind.TAIL -> 0
        WindKind.CROSS -> 1
        WindKind.HEAD -> 2
    }

    /**
     * Colored wind stretches between [from] and [to]: each [step] of route takes the wind of the
     * nearest sample and its own direction of travel. Winds under [calm] m/s are not colored.
     */
    fun runs(route: RouteGeometry, winds: List<RouteWind>, from: Double, to: Double, step: Double = 250.0, calm: Double = 1.0): List<RouteRun> {
        if (winds.isEmpty() || to <= from || route.points.size < 2) return emptyList()
        val runs = ArrayList<RouteRun>()
        var d = from
        while (d < to) {
            val end = minOf(d + step, to)
            val mid = (d + end) / 2
            val w = winds.minBy { abs(it.distance - mid) }
            val b = if (w.speed < calm) -1 else bucket(Wind.components(w.speed, w.fromDeg, route.bearingAt(mid)).kind)
            runs += RouteRun(d, end, b)
            d = end
        }
        return mergeRuns(runs)
    }
}

/** Encodes points as a Google polyline (the map layer API takes precision 5). */
fun encodePolyline(points: List<GeoPoint>, precision: Int = 5): String {
    val factor = Math.pow(10.0, precision.toDouble())
    val sb = StringBuilder()
    var lastLat = 0L
    var lastLng = 0L
    fun encode(v: Long) {
        var value = if (v < 0) (v shl 1).inv() else v shl 1
        while (value >= 0x20) {
            sb.append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
            value = value shr 5
        }
        sb.append((value + 63).toInt().toChar())
    }
    for (p in points) {
        val lat = (p.lat * factor).roundToLong()
        val lng = (p.lng * factor).roundToLong()
        encode(lat - lastLat)
        encode(lng - lastLng)
        lastLat = lat
        lastLng = lng
    }
    return sb.toString()
}
