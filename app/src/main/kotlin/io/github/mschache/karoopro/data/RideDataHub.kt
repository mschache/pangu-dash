package io.github.mschache.karoopro.data

import io.github.mschache.karoopro.calc.UnitPrefs
import io.github.mschache.karoopro.calc.ZoneRange
import io.github.mschache.karoopro.karoo.consumerFlow
import io.github.mschache.karoopro.karoo.field
import io.github.mschache.karoopro.karoo.streamDataFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** The rider profile reduced to what Pangu Dash uses. */
data class Profile(
    val ftp: Int,
    val powerZones: List<ZoneRange>,
    val hrZones: List<ZoneRange>,
    val maxHr: Int,
    val weightKg: Float,
    val units: UnitPrefs,
    val restingHr: Int = 60,
) {
    companion object {
        val DEFAULT = Profile(
            ftp = 200,
            powerZones = listOf(ZoneRange(0, 109), ZoneRange(110, 149), ZoneRange(150, 179), ZoneRange(180, 209), ZoneRange(210, 239), ZoneRange(240, 299), ZoneRange(300, 2000)),
            hrZones = listOf(ZoneRange(0, 113), ZoneRange(114, 132), ZoneRange(133, 151), ZoneRange(152, 170), ZoneRange(171, 220)),
            maxHr = 190,
            weightKg = 75f,
            units = UnitPrefs(),
        )

        fun from(p: UserProfile) = Profile(
            ftp = p.ftp,
            powerZones = p.powerZones.map { ZoneRange(it.min, it.max) },
            hrZones = p.heartRateZones.map { ZoneRange(it.min, it.max) },
            maxHr = p.maxHr,
            restingHr = p.restingHr,
            weightKg = p.weight,
            units = UnitPrefs(
                metricDistance = p.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.METRIC,
                metricElevation = p.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.METRIC,
                metricTemperature = p.preferredUnit.temperature == UserProfile.PreferredUnit.UnitType.METRIC,
            ),
        )
    }
}

/**
 * One shared subscription per Karoo data type, however many views read it. A type streams while
 * something keeps it alive ([keepAlive]) and stops 5 s after the last keeper goes away.
 */
class RideDataHub(val karoo: KarooSystemService, private val scope: CoroutineScope) {
    private val streams = ConcurrentHashMap<String, StateFlow<StreamState?>>()
    private val computed = ConcurrentHashMap<Metric, () -> Double?>()

    fun stream(typeId: String): StateFlow<StreamState?> = streams.getOrPut(typeId) {
        karoo.streamDataFlow(typeId).stateIn(scope, SharingStarted.WhileSubscribed(5_000, 0), null)
    }

    /** Subscribe to [typeIds] until the returned job is cancelled. */
    fun keepAlive(typeIds: Collection<String>): Job = scope.launch {
        typeIds.toSet().forEach { id -> launch { stream(id).collect {} } }
    }

    fun value(source: Source.Karoo): Double? {
        val state = stream(source.typeId).value
        val first = state.field(source.field)
        val pair = source.pair ?: return first
        // Left/right values: the average of the sides that report (one-sided meters send one).
        return listOfNotNull(first, state.field(pair)).takeIf { it.isNotEmpty() }?.average()
    }

    fun value(metric: Metric): Double? = when (val s = metric.source) {
        is Source.Karoo -> value(s)
        Source.Computed -> computed[metric]?.invoke()
    }

    fun isStreaming(typeId: String): Boolean = stream(typeId).value is StreamState.Streaming

    fun registerComputed(metric: Metric, provider: () -> Double?) {
        computed[metric] = provider
    }

    val profile: StateFlow<Profile> = karoo.consumerFlow<UserProfile>()
        .map { Profile.from(it) }
        .stateIn(scope, SharingStarted.Eagerly, Profile.DEFAULT)

    /** Null until the Karoo has reported a state in this process. */
    val rideState: StateFlow<RideState?> = karoo.consumerFlow<RideState>()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isRecording: Boolean get() = rideState.value is RideState.Recording
    val isRideActive: Boolean get() = rideState.value.let { it is RideState.Recording || it is RideState.Paused }

    val activePage: StateFlow<RideProfile.Page?> = karoo.consumerFlow<ActiveRidePage>()
        .map { it.page }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val savedDevices: StateFlow<SavedDevices?> = karoo.consumerFlow<SavedDevices>()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val navigation: StateFlow<OnNavigationState.NavigationState?> = karoo.consumerFlow<OnNavigationState>()
        .map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _laps = MutableSharedFlow<Lap>(extraBufferCapacity = 4)
    val laps: SharedFlow<Lap> = _laps

    init {
        scope.launch { karoo.consumerFlow<Lap>().collect { _laps.emit(it) } }
    }
}
