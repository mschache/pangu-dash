package io.github.mschache.karoopro

import io.github.mschache.karoopro.calc.Climbs
import io.github.mschache.karoopro.calc.Descents
import io.github.mschache.karoopro.calc.ElevationProfile
import io.github.mschache.karoopro.calc.GeoPoint
import io.github.mschache.karoopro.calc.GradeRuns
import io.github.mschache.karoopro.calc.Polyline
import io.github.mschache.karoopro.calc.ProfilePoint
import io.github.mschache.karoopro.calc.RouteGeometry
import io.github.mschache.karoopro.calc.RouteRun
import io.github.mschache.karoopro.calc.RouteWind
import io.github.mschache.karoopro.calc.SegmentPhase
import io.github.mschache.karoopro.calc.SegmentReading
import io.github.mschache.karoopro.calc.SegmentTracker
import io.github.mschache.karoopro.calc.WindRuns
import io.github.mschache.karoopro.calc.encodePolyline
import io.github.mschache.karoopro.calc.mergeRuns
import io.github.mschache.karoopro.data.RouteTracker
import io.github.mschache.karoopro.weather.OpenMeteo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    // About 11.1 km due north, then 11.1 km due east (near the equator: 0.1° ≈ 11.1 km).
    private val route = RouteGeometry(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.1, 0.0), GeoPoint(0.1, 0.1)))

    @Test fun polylineRoundTrip() {
        val pts = listOf(GeoPoint(47.37123, 8.54171), GeoPoint(47.38001, 8.55002), GeoPoint(46.9, 7.4))
        val decoded = Polyline.decode(encodePolyline(pts), 5)
        assertEquals(pts.size, decoded.size)
        pts.zip(decoded).forEach { (p, d) ->
            assertEquals(p.lat, d.first, 1e-5)
            assertEquals(p.lng, d.second, 1e-5)
        }
        // Google's documented example encodes to this exact string.
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encodePolyline(listOf(GeoPoint(38.5, -120.2), GeoPoint(40.7, -120.95), GeoPoint(43.252, -126.453))))
    }

    @Test fun distancesAndLookup() {
        assertEquals(11_119.5, route.cumulative[1], 1.0)
        assertEquals(2 * 11_119.5, route.length, 2.0)
        val mid = route.pointAt(route.cumulative[1] / 2)
        assertEquals(0.05, mid.lat, 1e-6)
        assertEquals(0.0, route.bearingAt(5_000.0), 0.5) // heading north
        assertEquals(90.0, route.bearingAt(16_000.0), 0.5) // heading east
        val slice = route.slice(5_000.0, 15_000.0)
        assertEquals(3, slice.size) // start, the corner, end
    }

    @Test fun mergeJoinsSameBucketAndDropsUncolored() {
        val merged = mergeRuns(listOf(RouteRun(0.0, 100.0, 1), RouteRun(100.0, 200.0, 1), RouteRun(200.0, 300.0, -1), RouteRun(300.0, 400.0, 2)))
        assertEquals(listOf(RouteRun(0.0, 200.0, 1), RouteRun(300.0, 400.0, 2)), merged)
    }

    @Test fun gradeBuckets() {
        // Flat 1 km, then 1 km at 8 %, then 500 m at 13 %.
        val profile = listOf(ProfilePoint(0.0, 100.0), ProfilePoint(1000.0, 100.0), ProfilePoint(2000.0, 180.0), ProfilePoint(2500.0, 245.0))
        val runs = GradeRuns.runs(profile, 0.0, 2500.0, step = 100.0, window = 100.0)
        assertTrue(runs.none { it.to <= 1000.0 }) // the flat part has no color
        assertTrue(runs.any { it.bucket == 1 && it.from >= 1000.0 && it.to <= 2000.0 }) // 6–9 %
        assertEquals(3, runs.last().bucket) // ≥ 12 %
        assertEquals(-1, GradeRuns.bucket(-8.0)) // descents are not colored
    }

    @Test fun windRunsFollowTheRouteDirection() {
        // Wind from the north: headwind on the northbound leg, crosswind on the eastbound leg.
        val runs = WindRuns.runs(route, listOf(RouteWind(0.0, 5.0, 0.0)), 0.0, route.length)
        assertEquals(2, runs.first().bucket) // head
        assertEquals(1, runs.last().bucket) // cross
        assertTrue(WindRuns.runs(route, listOf(RouteWind(0.0, 0.5, 0.0)), 0.0, route.length).isEmpty()) // calm
    }

    @Test fun routeWeatherPicksForecastHourByArrivalTime() {
        val one = """{"latitude":47.0,"longitude":8.0,
            "current":{"time":1000,"wind_speed_10m":2.0,"wind_direction_10m":90},
            "hourly":{"time":[3600,7200,10800],"wind_speed_10m":[3.0,6.0,9.0],"wind_direction_10m":[100,200,300]}}"""
        val body = "[$one,$one]"
        val now = 3_000_000L // 3000 s
        val winds = OpenMeteo.parseRoute(body, listOf(3_000L, 10_500L), now)
        assertEquals(2.0 to 90.0, winds[0]) // arriving now: current conditions
        assertEquals(9.0 to 300.0, winds[1]) // arriving in ~2 h: the 10800 s forecast hour
        assertTrue(OpenMeteo.routeUrl(listOf(47.0 to 8.0, 47.1 to 8.1)).contains("latitude=47.000,47.100"))
    }
}

class MapLayerMemoryTest {
    @Test fun leftoversSurviveARestartAndAreClearedOnce() {
        val file = java.io.File.createTempFile("map-layers", ".txt").apply { delete() }
        io.github.mschache.karoopro.map.MapLayerMemory(file).remember(listOf("wind-0", "climb-3"), listOf("wind-arrow-1"))
        // A new process reads what the old one drew...
        val restarted = io.github.mschache.karoopro.map.MapLayerMemory(file)
        val old = restarted.takeLeftovers()
        assertEquals(listOf("wind-0", "climb-3"), old.lines)
        assertEquals(listOf("wind-arrow-1"), old.symbols)
        // ...and only once.
        assertTrue(restarted.takeLeftovers().isEmpty)
        // Clearing the map forgets everything.
        restarted.remember(listOf("wind-0"), emptyList())
        restarted.remember(emptyList(), emptyList())
        assertTrue(restarted.takeLeftovers().isEmpty)
    }
}

class DescentsTest {
    private fun profile(vararg pts: Pair<Double, Double>) = ElevationProfile(pts.map { ProfilePoint(it.first, it.second) })

    @Test fun findsTheDemoRoutesLastDescent() {
        val p = ElevationProfile(RouteTracker.DEMO_PROFILE)
        val descents = Descents.detect(p)
        // 9.8 km at 170 m down to 12 km at 40 m: 130 m lost at about 6 %.
        val last = descents.last()
        assertEquals(9_800.0, last.start, 50.0)
        assertEquals(12_000.0, last.end, 50.0)
        assertEquals(130.0, last.drop, 5.0)
        assertEquals(5.9, last.avgGrade, 0.5)
        assertTrue(last.maxGrade > 0)
        // Climbs and descents never overlap: the first descent starts after the first climb's top.
        val climbs = Climbs.detect(p)
        assertTrue(descents.first().start >= climbs.first().end)
    }

    @Test fun ignoresShallowAndShortDrops() {
        // 2 % for 2 km: not a descent.
        assertTrue(Descents.detect(profile(0.0 to 100.0, 500.0 to 100.0, 2_500.0 to 60.0, 3_000.0 to 60.0)).isEmpty())
        // 20 m drop at 5 %: too small.
        assertTrue(Descents.detect(profile(0.0 to 100.0, 500.0 to 100.0, 900.0 to 80.0, 2_000.0 to 80.0)).isEmpty())
        // 2 km at 5 %: one descent from its top to its bottom.
        val d = Descents.detect(profile(0.0 to 200.0, 500.0 to 200.0, 2_500.0 to 100.0, 3_000.0 to 100.0))
        assertEquals(1, d.size)
        assertEquals(500.0, d[0].start, 50.0)
        assertEquals(2_500.0, d[0].end, 50.0)
        assertEquals(100.0, d[0].drop, 5.0)
    }

    @Test fun progressDownToTheBottomAndOnToTheNextDescent() {
        val p = ElevationProfile(RouteTracker.DEMO_PROFILE)
        val descents = Descents.detect(p)
        val last = descents.size
        val before = Descents.progress(descents, 9_000.0, p)!!
        assertEquals(last, before.number)
        assertFalse(before.onDescent)
        assertEquals(800.0, before.toStart, 50.0)
        assertEquals(before.descent.length, before.toBottom, 1.0)

        val on = Descents.progress(descents, 10_900.0, p)!!
        assertTrue(on.onDescent)
        assertEquals(1_100.0, on.toBottom, 50.0)
        assertEquals(p.at(10_900.0) - 40.0, on.elevationToBottom, 2.0)
        assertEquals(5.9, on.avgGradeLeft!!, 0.5)

        // The slices follow the real, downhill profile, so their grades are negative.
        val view = Descents.view(descents, 10_900.0, p)!!
        assertEquals(24, view.slices.size)
        assertTrue(view.slices.all { it.grade <= 0.0 })
        assertEquals(1_100.0, view.riderAt!!, 50.0)

        assertNull(Descents.progress(descents, 12_100.0, p))
    }

    @Test fun mapRunsBucketDescentsByHowSteepTheyDrop() {
        val p = profile(0.0 to 200.0, 500.0 to 200.0, 1_500.0 to 130.0, 2_500.0 to 130.0)
        val down = GradeRuns.runs(p, 0.0, 2_500.0, descents = true)
        // 7 % down for 1 km: bucket 1 (6–9 %); the flat ends are -1.
        assertTrue(down.any { it.bucket == 1 })
        assertTrue(down.none { it.bucket > 1 })
        // The same profile holds no climb runs.
        assertTrue(GradeRuns.runs(p, 0.0, 2_500.0).all { it.bucket == -1 })
    }
}

class ClimbsTest {
    private fun profile(vararg pts: Pair<Double, Double>) = ElevationProfile(pts.map { ProfilePoint(it.first, it.second) })

    @Test fun findsTheDemoRoutesThreeClimbs() {
        val climbs = Climbs.detect(ElevationProfile(RouteTracker.DEMO_PROFILE))
        assertEquals(3, climbs.size)
        // Each from its foot to its top, within the 25 m the ends are searched in.
        assertEquals(2_000.0, climbs[0].start, 50.0)
        assertEquals(3_000.0, climbs[0].end, 50.0)
        assertEquals(80.0, climbs[0].gain, 5.0)
        assertEquals(8.0, climbs[0].avgGrade, 1.0)
        assertEquals(7_400.0, climbs[1].start, 50.0)
        assertEquals(9_500.0, climbs[2].start, 50.0)
        assertEquals(9_800.0, climbs[2].end, 50.0)
    }

    @Test fun ignoresSmallBumpsAndJoinsShortDips() {
        // 20 m bump: not a climb.
        assertTrue(Climbs.detect(profile(0.0 to 100.0, 1_000.0 to 100.0, 1_400.0 to 120.0, 3_000.0 to 120.0)).isEmpty())
        // 2 km at 5 % with a 150 m, 5 m dip in the middle: one climb.
        val dip = profile(0.0 to 0.0, 500.0 to 0.0, 1_500.0 to 50.0, 1_575.0 to 45.0, 1_650.0 to 50.0, 2_650.0 to 100.0, 3_500.0 to 100.0)
        val climbs = Climbs.detect(dip)
        assertEquals(1, climbs.size)
        assertEquals(100.0, climbs[0].gain, 5.0)
        // A long descent in between splits it in two.
        val split = profile(0.0 to 0.0, 500.0 to 0.0, 1_500.0 to 50.0, 2_500.0 to 20.0, 3_500.0 to 70.0, 4_500.0 to 70.0)
        assertEquals(2, Climbs.detect(split).size)
    }

    @Test fun progressToTheTopAndOnToTheNextClimb() {
        val p = ElevationProfile(RouteTracker.DEMO_PROFILE)
        val climbs = Climbs.detect(p)
        val before = Climbs.progress(climbs, 1_500.0, p)!!
        assertEquals(1, before.number)
        assertEquals(3, before.total)
        assertFalse(before.onClimb)
        assertEquals(500.0, before.toStart, 50.0)
        val on = Climbs.progress(climbs, 2_500.0, p)!!
        assertTrue(on.onClimb)
        assertEquals(500.0, on.toTop, 50.0)
        assertEquals(40.0, on.elevationToTop, 5.0)
        assertEquals(8.0, on.avgGradeLeft!!, 1.0)
        // Over the top: the next climb.
        assertEquals(2, Climbs.progress(climbs, 3_100.0, p)!!.number)
        assertNull(Climbs.progress(climbs, 11_000.0, p))
    }

    @Test fun viewSlicesTheClimbAndPlacesTheRider() {
        val p = ElevationProfile(RouteTracker.DEMO_PROFILE)
        val view = Climbs.view(Climbs.detect(p), 2_500.0, p, count = 10)!!
        assertEquals(10, view.slices.size)
        assertEquals(0.0, view.slices.first().from, 1e-9)
        assertEquals(view.progress.climb.length, view.slices.last().to, 1e-6)
        assertEquals(8.0, view.slices[5].grade, 1.0)
        assertEquals(500.0, view.riderAt!!, 50.0)
        assertNull(Climbs.view(Climbs.detect(p), 1_000.0, p)!!.riderAt)
    }
}

class SegmentTrackerTest {
    @Test fun approachRunThenHoldTheResult() {
        val t = SegmentTracker(holdMs = 10_000, approachM = 500.0)
        assertNull(t.update(null, 0))
        assertNull(t.update(SegmentReading(distanceAway = 900.0), 1_000))
        assertEquals(SegmentPhase.APPROACHING, t.update(SegmentReading(distanceAway = 320.0, pr = 254_000.0), 2_000)!!.phase)
        val on = t.update(SegmentReading(elapsed = 5_000.0, prDelta = -2_000.0, distanceLeft = 1_400.0, totalDistance = 1_500.0), 3_000)!!
        assertEquals(SegmentPhase.ON, on.phase)
        assertEquals(1 / 15f, on.reading.progress!!, 1e-4f)
        // The streams stop: the last values stay up as the result for 10 s.
        val done = t.update(null, 60_000)!!
        assertEquals(SegmentPhase.DONE, done.phase)
        assertEquals(-2_000.0, done.reading.prDelta!!, 1e-9)
        assertEquals(SegmentPhase.DONE, t.update(null, 69_999)!!.phase)
        assertNull(t.update(null, 70_000))
    }

    @Test fun gapsReadAheadOrBehind() {
        assertEquals("-0:07", SegmentTracker.delta(-7_000.0))
        assertEquals("+1:12", SegmentTracker.delta(72_400.0))
        assertEquals("+0:00", SegmentTracker.delta(0.0))
        assertEquals("--", SegmentTracker.delta(null))
    }
}
