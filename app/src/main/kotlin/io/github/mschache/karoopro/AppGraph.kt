package io.github.mschache.karoopro

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.mschache.karoopro.alerts.AlertQueue
import io.github.mschache.karoopro.alerts.Beep
import io.github.mschache.karoopro.calc.FuelDue
import io.github.mschache.karoopro.calc.FuelItem
import io.github.mschache.karoopro.coach.CoachContract
import io.github.mschache.karoopro.coach.CoachEffort
import io.github.mschache.karoopro.coach.CoachLink
import io.github.mschache.karoopro.data.FitWriter
import io.github.mschache.karoopro.data.GearMonitor
import io.github.mschache.karoopro.data.Metric
import io.github.mschache.karoopro.data.PaceTracker
import io.github.mschache.karoopro.data.PanelController
import io.github.mschache.karoopro.data.ResultsRecorder
import io.github.mschache.karoopro.data.RideDataHub
import io.github.mschache.karoopro.data.RideRecorder
import io.github.mschache.karoopro.data.RideSnapshotStore
import io.github.mschache.karoopro.data.RouteTracker
import io.github.mschache.karoopro.data.SegmentMonitor
import io.github.mschache.karoopro.data.SettingsRepository
import io.github.mschache.karoopro.data.WorkoutMonitor
import io.github.mschache.karoopro.weather.WeatherRepository
import io.hammerhead.karooext.KarooSystemService
import io.github.mschache.karoopro.data.Panel
import io.github.mschache.karoopro.data.PanelTargets
import io.github.mschache.karoopro.ui.field.PanelsFieldDataType
import io.github.mschache.karoopro.ui.field.ProFieldDataType
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

const val EXTENSION_ID = "karoopro"

/** Process-wide services, shared by the extension service, field views, tap actions and settings UI. */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val karoo = KarooSystemService(app)
    val settingsRepository = SettingsRepository(app, scope)
    val settings = settingsRepository.settings
    val hub = RideDataHub(karoo, scope)
    val alerts = AlertQueue(karoo, scope)
    val recorder = RideRecorder(hub, settings, RideSnapshotStore(app), scope)
    val weather = WeatherRepository(hub, settings, scope)
    val workout = WorkoutMonitor(hub, settings, alerts, scope, fieldVisible = { fieldVisible() }) { state, now ->
        coach.state.value?.nextStep(state.stepNumber - 1, state.stepCount, now)?.inWatts(hub.profile.value.ftp)
    }
    val coach = CoachLink(app, settings, scope) { effort() }
    val routes = RouteTracker(hub)
    val segments = SegmentMonitor(hub, settings, scope)
    val gears = GearMonitor(hub, settings, alerts, scope)
    val results = ResultsRecorder(hub, routes, segments, scope)
    val pace = PaceTracker(hub, routes, settings)
    val panels = PanelController(
        hub, workout, coach, settings, scope,
        climb = { routes.climb() },
        descent = { routes.descent() },
        segment = { segments.state.value },
        paceAvailable = { routes.current()?.profile?.isNotEmpty() == true },
        hasResults = { results.results.value.isNotEmpty() },
        onFuelTap = { logFuel(settings.value.tapLogs) },
    )

    /** The Panels field's own deck: the big number first, its own current slide, no Bike auto-open. */
    val mapPanels = PanelController(
        hub, workout, coach, settings, scope,
        climb = { routes.climb() },
        descent = { routes.descent() },
        segment = { segments.state.value },
        paceAvailable = { routes.current()?.profile?.isNotEmpty() == true },
        hasResults = { results.results.value.isNotEmpty() },
        onFuelTap = { logFuel(settings.value.tapLogs) },
        withNumber = true,
        openBike = false,
    )

    /** Redraw both Pangu Dash fields now. */
    fun invalidateFields() {
        panels.invalidate()
        mapPanels.invalidate()
    }

    /** A tap on the Panels field: the middle of the big number switches it, everything else as on the full field. */
    fun panelsTap(direction: Int) {
        if (direction == 0 && mapPanels.shown() == Panel.NUMBER) cycleBigMetric() else mapPanels.tap(direction)
    }

    /** The next / previous panel bonus actions: whichever Pangu Dash field is on screen flips. */
    fun stepPanels(direction: Int) {
        val ids = hub.activePage.value?.elements?.map { it.dataTypeId }.orEmpty()
        val decks = PanelTargets.forPage(ids, DataType.dataTypeId(EXTENSION_ID, ProFieldDataType.TYPE_ID), DataType.dataTypeId(EXTENSION_ID, PanelsFieldDataType.TYPE_ID))
        if (PanelTargets.FULL in decks) panels.step(direction)
        if (PanelTargets.PANELS in decks) mapPanels.step(direction)
    }
    val fit = FitWriter(hub, recorder, weather, scope)
    val mapLayers = io.github.mschache.karoopro.map.MapLayers(
        hub, routes, settings, scope, io.github.mschache.karoopro.map.MapLayerMemory(java.io.File(app.filesDir, "map-layers.txt")),
    )

    /** What Pangu Coach's check-ins carry from Pangu Dash. */
    private fun effort(): Map<String, Double> {
        val s = settings.value
        return CoachEffort.values(
            wPrimeJ = hub.value(Metric.W_PRIME),
            wPrimeMaxJ = s.wPrimeJoules.toDouble(),
            cpW = (s.criticalPower ?: hub.profile.value.ftp).toDouble(),
            best = recorder.read { CoachContract.BEST.keys.associateWith { best.bestFor(it) } },
            wind = weather.wind(),
            step = workout.state.value,
            lastStep = workout.lastFinished.value,
        )
    }

    /** The rider ate or drank [item] (a tap on the Fuel panel or a bonus action). */
    fun logFuel(item: FuelItem) {
        recorder.logFuel(item)
        invalidateFields()
    }

    /** True when the visible ride page has a Pangu Dash field on it. */
    fun fieldVisible(): Boolean =
        hub.activePage.value?.elements?.any { it.dataTypeId.startsWith(DataType.dataTypeId(EXTENSION_ID, "")) } == true

    fun cycleBigMetric() {
        scope.launch {
            val hasPower = panels.demoMode ||
                hub.isStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_POWER) || hub.isStreaming(DataType.Type.POWER)
            settingsRepository.update { s ->
                // Without a power meter the power-based numbers show speed anyway, so skip them.
                val cycle = s.bigCycle(hasPower)
                val next = cycle[(cycle.indexOf(s.shownBigMetric(hasPower)) + 1) % cycle.size]
                if (next in s.bigMetrics) s.copy(bigMetric = next) else s
            }
            invalidateFields()
        }
    }

    fun start() {
        karoo.connect { connected -> Log.i("KarooPRO", "Karoo system connected=$connected") }
        recorder.start()
        weather.start()
        workout.start()
        coach.start()
        segments.start()
        gears.start()
        results.start()
        panels.start()
        mapPanels.start()
        hub.registerComputed(Metric.PACE_TARGET) { pace.state()?.target?.toDouble() }
        hub.registerComputed(Metric.PACE_AHEAD) { pace.state()?.aheadS?.times(1000) }
        scope.launch {
            recorder.fuelDue.collect { due ->
                val s = settings.value
                alerts.beep(Beep.FUEL)
                if (fieldVisible()) {
                    panels.fuelReminder()
                    mapPanels.fuelReminder()
                } else {
                    val (title, detail) = when (due) {
                        FuelDue.EAT -> "Eat" to "About ${s.gelCarbs} g carbs"
                        FuelDue.DRINK -> "Drink" to "A few good sips"
                    }
                    alerts.popup("karoopro-fuel", title, detail, 8_000)
                }
            }
        }
    }
}

class KarooProApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this).also { it.start() } }
}

val Context.graph: AppGraph get() = (applicationContext as KarooProApp).graph
