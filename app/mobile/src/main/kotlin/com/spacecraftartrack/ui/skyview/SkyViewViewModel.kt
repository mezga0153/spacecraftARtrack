package com.spacecraftartrack.ui.skyview

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spacecraftartrack.data.astronomy.AstronomyCalculator
import com.spacecraftartrack.data.astronomy.HorizontalPosition
import com.spacecraftartrack.data.location.LocationProvider
import com.spacecraftartrack.data.nasa.SpacecraftService
import com.spacecraftartrack.data.nasa.SpacecraftState
import com.spacecraftartrack.data.sensor.DeviceOrientation
import com.spacecraftartrack.data.sensor.OrientationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import javax.inject.Inject

/**
 * Guidance from the current camera pointing direction toward a sky target.
 * @param offsetAzDeg positive = target is to the right of camera centre
 * @param offsetAltDeg positive = target is above camera centre
 */
data class TargetGuidance(
    val offsetAzDeg: Float,
    val offsetAltDeg: Float,
    val isAligned: Boolean,
)

/**
 * Preset zoom levels shown in the lens-switcher UI.
 * Built from CameraX min/max zoom after the camera binds.
 */
data class ZoomPreset(
    val label: String,     // "0.5x", "1x", "2x", etc.
    val zoomRatio: Float,  // actual CameraX zoom ratio
)

data class SkyViewState(
    val orientation: DeviceOrientation = DeviceOrientation(),
    val moonPosition: HorizontalPosition? = null,
    val sunPosition: HorizontalPosition? = null,
    val spacecraftState: SpacecraftState = SpacecraftState.Loading,
    val location: Location? = null,
    val hasLocationPermission: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val sensorReady: Boolean = false,
    val artemisGuidance: TargetGuidance? = null,
    val moonGuidance: TargetGuidance? = null,
    val bottomCardExpanded: Boolean = false,
    val cameraFocalLengthMm: Float = 4.25f,
    val cameraSensorWidthMm: Float = 5.64f,
    val cameraSensorHeightMm: Float = 4.23f,
    val zoomRatio: Float = 1f,
    val zoomPresets: List<ZoomPreset> = listOf(ZoomPreset("1x", 1f)),
    val selectedPresetIndex: Int = 0,
)

@HiltViewModel
class SkyViewViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val orientationManager: OrientationManager,
    private val locationProvider: LocationProvider,
    private val spacecraftService: SpacecraftService,
) : ViewModel() {

    init {
        loadCameraIntrinsics()
    }

    private val _state = MutableStateFlow(SkyViewState())
    val state: StateFlow<SkyViewState> = _state.asStateFlow()

    fun onPermissionsGranted(hasCamera: Boolean, hasLocation: Boolean) {
        _state.update { it.copy(hasCameraPermission = hasCamera, hasLocationPermission = hasLocation) }
        if (hasCamera) startOrientationUpdates()
        if (hasLocation) startLocationUpdates()
    }

    private fun startOrientationUpdates() {
        viewModelScope.launch {
            orientationManager.orientationFlow().collect { orientation ->
                val loc = _state.value.location
                val moonPos = if (loc != null) {
                    AstronomyCalculator.moonPosition(
                        latDeg = loc.latitude,
                        lonDeg = loc.longitude,
                        epochMillis = System.currentTimeMillis(),
                    )
                } else null

                val sunPos = if (loc != null) {
                    AstronomyCalculator.sunPosition(
                        latDeg = loc.latitude,
                        lonDeg = loc.longitude,
                        epochMillis = System.currentTimeMillis(),
                    )
                } else null

                val ready = orientation.accuracy >= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW
                        && orientation.headingStable
                val R = orientation.rotationMatrix
                val sc = _state.value.spacecraftState
                val artemisGuidance = if (sc is SpacecraftState.Tracking) computeGuidance(sc.position, R) else null
                val moonGuidance = moonPos?.let { computeGuidance(it, R) }
                _state.update {
                    it.copy(
                        orientation = orientation,
                        moonPosition = moonPos,
                        sunPosition = sunPos,
                        sensorReady = ready,
                        artemisGuidance = artemisGuidance,
                        moonGuidance = moonGuidance,
                    )
                }
            }
        }
    }

    private fun startLocationUpdates() {
        viewModelScope.launch {
            locationProvider.locationFlow().collect { location ->
                _state.update { it.copy(location = location) }
                // Refresh spacecraft position whenever location changes significantly
                refreshSpacecraftPosition(location)
            }
        }
    }

    private fun refreshSpacecraftPosition(location: Location) {
        viewModelScope.launch {
            val result = spacecraftService.fetchArtemisPosition(
                latDeg = location.latitude,
                lonDeg = location.longitude,
                altMeters = location.altitude,
            )
            _state.update { current ->
                // Keep the last known Tracking state if the refresh failed
                val newState = if (result is SpacecraftState.Tracking) {
                    result
                } else if (current.spacecraftState is SpacecraftState.Tracking) {
                    current.spacecraftState // keep stale data rather than blanking out
                } else {
                    result
                }
                current.copy(spacecraftState = newState)
            }
        }
    }

    fun startPeriodicSpacecraftRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(10_000L)
                _state.value.location?.let { refreshSpacecraftPosition(it) }
            }
        }
    }

    fun toggleBottomCard() {
        _state.update { it.copy(bottomCardExpanded = !it.bottomCardExpanded) }
    }

    private fun loadCameraIntrinsics() {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            val chars = cm.getCameraCharacteristics(id)
            val fl = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return
            val ss = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return
            _state.update {
                it.copy(
                    cameraFocalLengthMm = fl,
                    cameraSensorWidthMm = ss.width,
                    cameraSensorHeightMm = ss.height,
                )
            }
        } catch (_: Exception) { }
    }

    /**
     * Called by CameraPreview once the camera is bound and zoom range is known.
     * Builds the zoom-preset buttons from the hardware range.
     */
    fun onCameraZoomRange(minZoom: Float, maxZoom: Float) {
        val presets = mutableListOf<ZoomPreset>()
        if (minZoom < 0.9f)  presets.add(ZoomPreset("0.5x", minZoom.coerceAtMost(0.5f).coerceAtLeast(minZoom)))
        presets.add(ZoomPreset("1x", 1f))
        if (maxZoom >= 2f)   presets.add(ZoomPreset("2x", 2f))
        if (maxZoom >= 5f)   presets.add(ZoomPreset("5x", 5f))
        if (maxZoom >= 10f)  presets.add(ZoomPreset("10x", 10f))
        // Ensure the current zoom ratio maps to the right preset
        val curZoom = _state.value.zoomRatio
        val selIdx = presets.indexOfFirst { it.zoomRatio == curZoom }.coerceAtLeast(
            presets.indexOfFirst { it.zoomRatio == 1f }.coerceAtLeast(0)
        )
        _state.update { it.copy(zoomPresets = presets, selectedPresetIndex = selIdx) }
    }

    fun selectZoomPreset(index: Int) {
        val presets = _state.value.zoomPresets
        if (index !in presets.indices) return
        _state.update {
            it.copy(zoomRatio = presets[index].zoomRatio, selectedPresetIndex = index)
        }
    }

    fun setZoomRatio(ratio: Float) {
        _state.update { it.copy(zoomRatio = ratio, selectedPresetIndex = -1) }
    }

    /**
     * Compute how far the camera is from pointing at [target], in degrees.
     * Uses the raw rotation matrix: camera looks along -device Z.
     * Camera world direction (ENU) = R * [0,0,-1] = [-R[2], -R[5], -R[8]].
     */
    private fun computeGuidance(target: HorizontalPosition, R: FloatArray): TargetGuidance {
        val camEast  = -R[2].toDouble()
        val camNorth = -R[5].toDouble()
        val camUp    = -R[8].toDouble()
        val camAlt = Math.toDegrees(asin(camUp.coerceIn(-1.0, 1.0)))
        var camAz  = Math.toDegrees(atan2(camEast, camNorth))
        if (camAz < 0) camAz += 360.0
        var offsetAz = (target.azimuth - camAz).toFloat()
        while (offsetAz >  180f) offsetAz -= 360f
        while (offsetAz < -180f) offsetAz += 360f
        val offsetAlt = (target.altitude - camAlt).toFloat()
        return TargetGuidance(
            offsetAzDeg  = offsetAz,
            offsetAltDeg = offsetAlt,
            isAligned    = abs(offsetAz) < 2.5f && abs(offsetAlt) < 2.5f,
        )
    }
}
