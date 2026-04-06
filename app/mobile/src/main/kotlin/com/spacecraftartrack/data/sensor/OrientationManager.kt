package com.spacecraftartrack.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone orientation expressed as a 3×3 rotation matrix (row-major).
 *
 * The matrix maps device-frame vectors to the world ENU frame:
 *   world_vector = R * device_vector
 *
 * To project a world-frame direction onto the camera (device +Z axis):
 *   device_vector = R^T * world_vector
 */
data class DeviceOrientation(
    /** 3×3 rotation matrix, row-major, device→world. */
    val rotationMatrix: FloatArray = FloatArray(9),
    /** Current azimuth (heading) in degrees, 0=North. */
    val azimuthDeg: Float = 0f,
    /** Current pitch in degrees; positive = tilted back. */
    val pitchDeg: Float = 0f,
    /** Current roll in degrees. */
    val rollDeg: Float = 0f,
    /**
     * Sensor accuracy: SensorManager.SENSOR_STATUS_ACCURACY_*
     *   0 = unreliable, 1 = low, 2 = medium, 3 = high
     */
    val accuracy: Int = 0,
    /** True once the heading has stabilised (low variance over a sliding window). */
    val headingStable: Boolean = false,
)

@Singleton
class OrientationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    /**
     * Emits [DeviceOrientation] updates at the given [samplingPeriodUs].
     * The flow is active while collected and stops when the collector cancels.
     */
    fun orientationFlow(
        samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    ): Flow<DeviceOrientation> = callbackFlow {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: run { close(); return@callbackFlow }

        val rotMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var currentAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW

        // Sliding window for heading stability detection.
        // We use circular mean/std to handle the 0°/360° wraparound.
        val windowSize = 40          // ~0.8 s at SENSOR_DELAY_GAME
        val azSinWindow = FloatArray(windowSize)
        val azCosWindow = FloatArray(windowSize)
        var windowIndex = 0
        var windowFilled = false
        val stabilityThresholdDeg = 5.0  // max circular std-dev to be "stable"

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                // Remap so that X=East, Y=North, Z=Up in portrait mode
                SensorManager.remapCoordinateSystem(
                    rotMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Z,
                    remappedMatrix,
                )
                SensorManager.getOrientation(remappedMatrix, orientation)

                val azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat().let {
                    if (it < 0) it + 360f else it
                }
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

                // Update circular heading window
                val azRad = Math.toRadians(azimuth.toDouble())
                azSinWindow[windowIndex] = sin(azRad).toFloat()
                azCosWindow[windowIndex] = cos(azRad).toFloat()
                windowIndex = (windowIndex + 1) % windowSize
                if (windowIndex == 0) windowFilled = true

                val stable = if (windowFilled) {
                    // Circular standard deviation
                    val n = windowSize.toFloat()
                    var sumSin = 0f; var sumCos = 0f
                    for (i in 0 until windowSize) { sumSin += azSinWindow[i]; sumCos += azCosWindow[i] }
                    val meanLen = sqrt((sumSin / n) * (sumSin / n) + (sumCos / n) * (sumCos / n).toDouble())
                    // circularStdDev = sqrt(-2 * ln(R̄)), but clamp R̄ to avoid NaN
                    val clamped = meanLen.coerceIn(0.001, 1.0)
                    val circStdDeg = Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(clamped)))
                    circStdDeg < stabilityThresholdDeg
                } else false

                trySend(
                    DeviceOrientation(
                        rotationMatrix = rotMatrix.copyOf(),
                        azimuthDeg = azimuth,
                        pitchDeg = pitch,
                        rollDeg = roll,
                        accuracy = currentAccuracy,
                        headingStable = stable,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                currentAccuracy = accuracy
            }
        }

        sensorManager.registerListener(listener, rotationSensor, samplingPeriodUs)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
