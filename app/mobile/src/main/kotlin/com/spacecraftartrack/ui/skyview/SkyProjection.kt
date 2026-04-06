package com.spacecraftartrack.ui.skyview

import androidx.compose.ui.geometry.Offset
import com.spacecraftartrack.data.astronomy.HorizontalPosition
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projects a celestial object (given by azimuth/altitude) onto screen pixel coordinates,
 * using the device rotation matrix obtained from the sensors.
 *
 * Coordinate systems
 * ──────────────────
 * World frame (ENU):
 *   +X = East,  +Y = North,  +Z = Up
 *
 * Device frame (portrait with screen facing user):
 *   The rotation matrix R satisfies:  world_vec = R · device_vec
 *   So to go from world → device:     device_vec = Rᵀ · world_vec
 *
 * Camera: the camera optical axis points in the device +Y direction when
 * the phone is held portrait (screen facing up) and in the device +Z direction
 * when held upright pointing at sky.  We use the standard Camera2 convention:
 *   camera looks along device +Z (i.e. the direction out of the back of the phone).
 *
 * Screen: x increases right, y increases downward.
 */
object SkyProjection {

    /**
     * @param position  horizontal position of the celestial object
     * @param R         3×3 rotation matrix (row-major), device → world (from [OrientationManager])
     * @param screenW   screen width in pixels
     * @param screenH   screen height in pixels
     * @param focalLengthMm   camera focal length in mm (from Camera2 characteristics)
     * @param sensorWidthMm   camera sensor physical width in mm (landscape)
     * @param sensorHeightMm  camera sensor physical height in mm (landscape)
     * @param zoomRatio        digital/optical zoom ratio (1 = no zoom)
     * @return screen [Offset] if the object is in front of the camera, null otherwise
     */
    fun project(
        position: HorizontalPosition,
        R: FloatArray,
        screenW: Int,
        screenH: Int,
        focalLengthMm: Float = 4.25f,
        sensorWidthMm: Float = 5.64f,
        sensorHeightMm: Float = 4.23f,
        zoomRatio: Float = 1f,
    ): Offset? {
        // 1. Convert azimuth/altitude to a unit vector in ENU world frame
        val azRad = position.azimuth * PI / 180.0
        val altRad = position.altitude * PI / 180.0
        val wx = sin(azRad) * cos(altRad)   // East
        val wy = cos(azRad) * cos(altRad)   // North
        val wz = sin(altRad)                // Up

        // 2. Transform to device frame: d = Rᵀ · w
        //    R is stored row-major: R[row*3+col]
        val dx = R[0] * wx + R[3] * wy + R[6] * wz
        val dy = R[1] * wx + R[4] * wy + R[7] * wz
        val dz = R[2] * wx + R[5] * wy + R[8] * wz

        // 3. Raw device frame: X = right, Y = top, Z = toward user.
        //    Camera (back of phone) looks along -device Z.
        //    dz >= 0 → object is behind the camera.
        if (dz >= 0.0) return null

        // 4. Perspective projection
        //    Compute focal length in screen pixels accounting for PreviewView FILL_CENTER.
        //    In portrait the sensor is rotated 90°:
        //      portrait width  ↔ sensorHeight
        //      portrait height ↔ sensorWidth
        //    FILL_CENTER picks the scale that fills both dimensions.
        //    Zoom crops the sensor, which is equivalent to multiplying the focal length.
        val depth = -dz
        val effectiveFl = focalLengthMm * zoomRatio
        val camPortraitAspect = sensorHeightMm / sensorWidthMm
        val screenAspect = screenW.toFloat() / screenH
        val scale = if (screenAspect <= camPortraitAspect) {
            // Common portrait: screen narrower than camera → match height, crop width
            (effectiveFl / sensorWidthMm) * screenH
        } else {
            // Wide/landscape screen → match width, crop height
            (effectiveFl / sensorHeightMm) * screenW
        }.toDouble()
        val sx = (screenW / 2.0 + dx / depth * scale).toFloat()  // device X → screen right
        val sy = (screenH / 2.0 - dy / depth * scale).toFloat()  // device Y up → screen up

        return Offset(sx, sy)
    }

    /**
     * Angular separation in degrees between two horizontal positions.
     */
    fun angularSeparation(a: HorizontalPosition, b: HorizontalPosition): Double {
        val az1 = a.azimuth * PI / 180.0
        val alt1 = a.altitude * PI / 180.0
        val az2 = b.azimuth * PI / 180.0
        val alt2 = b.altitude * PI / 180.0

        val cosAngle = sin(alt1) * sin(alt2) + cos(alt1) * cos(alt2) * cos(az1 - az2)
        return Math.toDegrees(Math.acos(cosAngle.coerceIn(-1.0, 1.0)))
    }

    /**
     * Returns the normalised 2-D screen-space direction vector pointing toward [position].
     * Unlike [project] this never returns null due to the object being behind the camera —
     * it falls back to the lateral device-frame components when dz ≤ 0.
     * Returns null only when the direction is geometrically indeterminate.
     */
    fun screenDirection(position: HorizontalPosition, R: FloatArray): Offset? {
        val azRad = position.azimuth * PI / 180.0
        val altRad = position.altitude * PI / 180.0
        val wx = sin(azRad) * cos(altRad)
        val wy = cos(azRad) * cos(altRad)
        val wz = sin(altRad)

        val dx = R[0] * wx + R[3] * wy + R[6] * wz
        val dy = R[1] * wx + R[4] * wy + R[7] * wz
        val dz = R[2] * wx + R[5] * wy + R[8] * wz

        // Raw device frame: camera looks along -device Z.
        // dz < 0 → in front of camera; use perspective direction.
        // dz >= 0 → behind camera; use lateral (dx, dy) direction.
        val sx: Double
        val sy: Double
        if (dz < 0.0) {
            val depth = -dz
            sx = dx / depth
            sy = -dy / depth   // device Y up → screen Y down (inverted)
        } else {
            val lat = sqrt(dx * dx + dy * dy)
            if (lat < 1e-9) return null
            sx = dx / lat
            sy = -dy / lat    // same inversion for consistency
        }
        val len = sqrt(sx * sx + sy * sy)
        if (len < 1e-9) return null
        return Offset((sx / len).toFloat(), (sy / len).toFloat())
    }
}
