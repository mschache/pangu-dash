package io.github.mschache.karoopro.data

import io.github.mschache.karoopro.calc.Climb
import io.github.mschache.karoopro.calc.ClimbProgress
import io.github.mschache.karoopro.calc.ClimbView
import io.github.mschache.karoopro.calc.Climbs
import io.github.mschache.karoopro.calc.Descent
import io.github.mschache.karoopro.calc.DescentProgress
import io.github.mschache.karoopro.calc.DescentView
import io.github.mschache.karoopro.calc.Descents
import io.github.mschache.karoopro.calc.ElevationProfile
import io.github.mschache.karoopro.calc.GeoPoint
import io.github.mschache.karoopro.calc.Polyline
import io.github.mschache.karoopro.calc.ProfilePoint
import io.github.mschache.karoopro.calc.RouteGeometry
import io.github.mschache.karoopro.karoo.field
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState.NavigationState

/**
 * The route being navigated, shared by the map layers and the field: its geometry, elevation
 * profile and climbs, and how far along it the rider is. Debug builds can put a synthetic route in
 * place of navigation ([showDemoRoute]); the ride distance then moves the rider round it.
 */
class RouteTracker(private val hub: RideDataHub) {
    class Route(
        val key: String,
        val geometry: RouteGeometry,
        val profile: List<ProfilePoint>,
        val routeDistance: Double?,
        val demo: Boolean = false,
    ) {
        val elevation: ElevationProfile by lazy { ElevationProfile(profile) }
        val climbs: List<Climb> by lazy { Climbs.detect(elevation) }
        val descents: List<Descent> by lazy { Descents.detect(elevation) }
    }

    @Volatile private var route: Route? = null
    @Volatile private var demoRoute: Route? = null
    @Volatile private var lastAlong: Double? = null

    /** Debug builds: meters added to the ride distance on the demo route ([setDemoAlong]). */
    @Volatile private var demoShift = 0.0

    /** The navigated route (or the demo route), null without one. */
    fun current(): Route? {
        val (polyline, elevation, routeDistance) = when (val nav = hub.navigation.value) {
            is NavigationState.NavigatingRoute -> Triple(nav.routePolyline, nav.routeElevationPolyline, nav.routeDistance)
            is NavigationState.NavigatingToDestination -> Triple(nav.polyline, nav.elevationPolyline, null)
            else -> return demoRoute
        }
        if (polyline.isBlank()) return null
        val key = polyline.hashCode().toString() + ":" + polyline.length
        route?.takeIf { it.key == key }?.let { return it }
        val geometry = RouteGeometry.fromEncoded(polyline)
        val profile = elevation?.let { e -> Polyline.decode(e, 1).map { ProfilePoint(it.first, it.second) } }.orEmpty()
        lastAlong = null
        return Route(key, geometry, profile, routeDistance).also { route = it }
    }

    /**
     * Meters along [r]: from the distance left to the destination while navigating (the last known
     * value, else the start, until the Karoo reports it); round the demo route by ride distance.
     */
    fun along(r: Route): Double {
        if (r.demo) {
            val ridden = (hub.value(Metric.DISTANCE) ?: 0.0) + demoShift
            return if (r.geometry.length > 0) ridden.mod(r.geometry.length) else 0.0
        }
        val toGo = hub.stream(DataType.Type.DISTANCE_TO_DESTINATION).value.field(DataType.Field.DISTANCE_TO_DESTINATION)
        val total = r.routeDistance ?: r.geometry.length
        val along = toGo?.let { (total - it).coerceIn(0.0, r.geometry.length) } ?: lastAlong ?: 0.0
        lastAlong = along
        return along
    }

    /** The climb the rider is on or the next one on the current route. */
    fun climb(): ClimbProgress? {
        val r = current() ?: return null
        return Climbs.progress(r.climbs, along(r), r.elevation)
    }

    /** [climb] with its profile, for the Climb panel. */
    fun climbView(): ClimbView? {
        val r = current() ?: return null
        return Climbs.view(r.climbs, along(r), r.elevation)
    }

    /** The descent the rider is on or the next one on the current route. */
    fun descent(): DescentProgress? {
        val r = current() ?: return null
        return Descents.progress(r.descents, along(r), r.elevation)
    }

    /** [descent] with its profile, for the Descent panel. */
    fun descentView(): DescentView? {
        val r = current() ?: return null
        return Descents.view(r.descents, along(r), r.elevation)
    }

    /** Route profile within [meters] ahead of the rider, distances relative to the rider; empty without a route. */
    fun terrainAhead(meters: Double): List<ProfilePoint> {
        val r = current() ?: return emptyList()
        val along = along(r)
        return r.profile.filter { it.distance >= along && it.distance <= along + meters }
            .map { ProfilePoint(it.distance - along, it.elevation) }
    }

    /** Debug builds: a ~12 km loop with three climbs starting at [lat],[lng], shown while nothing is navigated. */
    fun showDemoRoute(lat: Double, lng: Double) {
        val dLat = 3_000.0 / 111_195.0
        val dLng = dLat / kotlin.math.cos(Math.toRadians(lat))
        val corners = listOf(GeoPoint(lat, lng), GeoPoint(lat + dLat, lng), GeoPoint(lat + dLat, lng + dLng), GeoPoint(lat, lng + dLng), GeoPoint(lat, lng))
        val pts = corners.zipWithNext().flatMap { (a, b) -> (0 until 10).map { i -> GeoPoint(a.lat + (b.lat - a.lat) * i / 10, a.lng + (b.lng - a.lng) * i / 10) } } + corners.last()
        demoRoute = Route("demo:$lat,$lng", RouteGeometry(pts), DEMO_PROFILE, null, demo = true)
    }

    fun hideDemoRoute() {
        demoRoute = null
    }

    /** Debug builds: put the rider [meters] along the demo route now; riding moves on from there. */
    fun setDemoAlong(meters: Double) {
        demoShift = meters - (hub.value(Metric.DISTANCE) ?: 0.0)
    }

    companion object {
        /** 3 km north, 3 km east, 3 km south, 3 km west, with three climbs. */
        val DEMO_PROFILE = listOf(
            0.0 to 40.0, 2_000.0 to 40.0, 3_000.0 to 120.0, 5_000.0 to 120.0, 7_000.0 to 60.0,
            7_400.0 to 60.0, 8_000.0 to 130.0, 9_500.0 to 130.0, 9_800.0 to 170.0, 12_000.0 to 40.0,
        ).map { ProfilePoint(it.first, it.second) }
    }
}
