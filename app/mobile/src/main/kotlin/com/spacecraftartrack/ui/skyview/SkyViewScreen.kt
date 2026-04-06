package com.spacecraftartrack.ui.skyview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spacecraftartrack.R
import com.spacecraftartrack.data.astronomy.HorizontalPosition
import com.spacecraftartrack.data.nasa.SpacecraftState
import com.spacecraftartrack.ui.theme.ArtemisOrange
import com.spacecraftartrack.ui.theme.MoonYellow
import com.spacecraftartrack.ui.theme.SunYellow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ── Utilities ────────────────────────────────────────────────────────────────

private fun azToCompass(az: Double): String {
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[((az + 22.5) / 45.0).toInt() % 8]
}

private fun formatDistance(km: Double): String {
    if (km <= 0.0) return ""
    val n = km.roundToInt()
    return n.toString().reversed().chunked(3).joinToString(",").reversed() + " km"
}

private fun guidanceHint(offsetAz: Float, offsetAlt: Float): String {
    val parts = mutableListOf<String>()
    if (abs(offsetAz)  >= 1f) parts.add("${if (offsetAz  < 0) "←" else "→"} ${abs(offsetAz).roundToInt()}°")
    if (abs(offsetAlt) >= 1f) parts.add("${if (offsetAlt < 0) "↓" else "↑"} ${abs(offsetAlt).roundToInt()}°")
    return parts.joinToString("  ")
}

/**
 * Convert an angular diameter (degrees) to screen pixels using the same
 * focal-length-based scale used by SkyProjection.project().
 */
private fun angularRadiusPx(
    angularDiaDeg: Float,
    screenW: Int,
    screenH: Int,
    focalLengthMm: Float,
    sensorWidthMm: Float,
    sensorHeightMm: Float,
    zoomRatio: Float,
): Float {
    val effectiveFl = focalLengthMm * zoomRatio
    val camPortraitAspect = sensorHeightMm / sensorWidthMm
    val screenAspect = screenW.toFloat() / screenH
    val scale = if (screenAspect <= camPortraitAspect) {
        (effectiveFl / sensorWidthMm) * screenH
    } else {
        (effectiveFl / sensorHeightMm) * screenW
    }
    // angular radius in radians * focal-length-in-pixels
    return (Math.toRadians((angularDiaDeg / 2f).toDouble()).toFloat() * scale).coerceAtLeast(6f)
}

@Composable
fun SkyViewScreen(
    viewModel: SkyViewViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val haptic = LocalHapticFeedback.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val camera = permissions[Manifest.permission.CAMERA] == true
        val location = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onPermissionsGranted(hasCamera = camera, hasLocation = location)
    }

    LaunchedEffect(Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val hasLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasLocation) {
            viewModel.onPermissionsGranted(hasCamera = true, hasLocation = true)
            viewModel.startPeriodicSpacecraftRefresh()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }
    // Haptic pulse when Artemis comes into alignment
    LaunchedEffect(state.artemisGuidance?.isAligned) {
        if (state.artemisGuidance?.isAligned == true) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    if (!state.hasCameraPermission || !state.hasLocationPermission) {
        PermissionRationale(
            hasCamera = state.hasCameraPermission,
            onGrant = {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        CameraPreview(
            zoomRatio = state.zoomRatio,
            onZoomChanged = viewModel::setZoomRatio,
            onZoomRangeAvailable = viewModel::onCameraZoomRange,
        )
        if (state.sensorReady) {
            ArOverlay(state = state)
        }
        TopBar(state = state)
        if (!state.sensorReady) CalibrationBanner(
            accuracy = state.orientation.accuracy,
            modifier = Modifier.align(Alignment.Center),
        )
        BottomCard(
            state = state,
            onToggle = viewModel::toggleBottomCard,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        if (state.zoomPresets.size > 1) {
            LensSwitcher(
                presets = state.zoomPresets,
                selectedIndex = state.selectedPresetIndex,
                currentZoom = state.zoomRatio,
                onSelect = viewModel::selectZoomPreset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 220.dp),
            )
        }
    }
}

// ── Camera preview ───────────────────────────────────────────────────────────

@Composable
private fun CameraPreview(
    zoomRatio: Float,
    onZoomChanged: (Float) -> Unit,
    onZoomRangeAvailable: (minZoom: Float, maxZoom: Float) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var bound by remember { mutableStateOf(false) }

    // Apply zoom whenever the ratio changes
    LaunchedEffect(zoomRatio, camera) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER

                // Pinch-to-zoom gesture
                val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val cam = camera ?: return true
                        val zoomState = cam.cameraInfo.zoomState.value ?: return true
                        val newRatio = (zoomState.zoomRatio * detector.scaleFactor)
                            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        onZoomChanged(newRatio)
                        return true
                    }
                }
                val scaleDetector = ScaleGestureDetector(ctx, scaleListener)
                setOnTouchListener { _, event ->
                    scaleDetector.onTouchEvent(event)
                    true
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            if (bound) return@AndroidView
            bound = true

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    cameraProvider.unbindAll()
                    val cam = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                    camera = cam

                    // Report zoom range so ViewModel can build presets
                    cam.cameraInfo.zoomState.value?.let { zs ->
                        onZoomRangeAvailable(zs.minZoomRatio, zs.maxZoomRatio)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
    )
}

// ── AR overlay (Canvas drawn on top of camera) ───────────────────────────────

@Composable
private fun ArOverlay(state: SkyViewState) {
    var screenW by remember { mutableIntStateOf(1) }
    var screenH by remember { mutableIntStateOf(1) }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
    ) {
        screenW = size.width.roundToInt()
        screenH = size.height.roundToInt()

        val R = state.orientation.rotationMatrix
        val fl = state.cameraFocalLengthMm
        val sw = state.cameraSensorWidthMm
        val sh = state.cameraSensorHeightMm
        val zoom = state.zoomRatio

        // Compute pixel sizes for angular diameters
        // Moon: ~0.52°, Sun: ~0.53°
        val moonRadPx = angularRadiusPx(0.52f, screenW, screenH, fl, sw, sh, zoom)
        val sunRadPx  = angularRadiusPx(0.53f, screenW, screenH, fl, sw, sh, zoom)

        // Draw Sun
        state.sunPosition?.let { sun ->
            val offset = SkyProjection.project(sun, R, screenW, screenH, fl, sw, sh, zoom)
            val onScreen = offset != null &&
                    offset.x in 0f..screenW.toFloat() &&
                    offset.y in 0f..screenH.toFloat()
            if (onScreen && offset != null) {
                drawCircle(color = SunYellow.copy(alpha = 0.85f), radius = sunRadPx, center = offset, style = Stroke(width = 1.5f.dp.toPx()))
            } else {
                drawOffScreenIndicator(sun, R, screenW, screenH, SunYellow, "Sun")
            }
        }

        // Draw Moon
        state.moonPosition?.let { moon ->
            val offset = SkyProjection.project(moon, R, screenW, screenH, fl, sw, sh, zoom)
            val onScreen = offset != null &&
                    offset.x in 0f..screenW.toFloat() &&
                    offset.y in 0f..screenH.toFloat()
            if (onScreen && offset != null) {
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = moonRadPx, center = offset, style = Stroke(width = 1.5f.dp.toPx()))
            } else {
                drawOffScreenIndicator(moon, R, screenW, screenH, Color.White, "Moon")
            }
        }

        // Draw Artemis spacecraft
        when (val sc = state.spacecraftState) {
            is SpacecraftState.Tracking -> {
                val offset = SkyProjection.project(sc.position, R, screenW, screenH, fl, sw, sh, zoom)
                val onScreen = offset != null &&
                        offset.x in 0f..screenW.toFloat() &&
                        offset.y in 0f..screenH.toFloat()
                if (onScreen && offset != null) {
                    drawSpacecraft(offset)
                } else {
                    drawOffScreenIndicator(sc.position, R, screenW, screenH, ArtemisOrange, "Artemis II")
                }
            }
            else -> Unit
        }
    }
}

private fun DrawScope.drawSpacecraft(center: Offset) {
    val dotR   = 1.5f          // tiny centre dot (px)
    val armLen = 16.dp.toPx()
    val gap    = 4.dp.toPx()   // gap between dot and arm start
    val stroke = 1.dp.toPx()

    // centre dot
    drawCircle(color = ArtemisOrange, radius = dotR, center = center)

    // four arms
    drawLine(ArtemisOrange, Offset(center.x, center.y - gap), Offset(center.x, center.y - gap - armLen), strokeWidth = stroke)
    drawLine(ArtemisOrange, Offset(center.x, center.y + gap), Offset(center.x, center.y + gap + armLen), strokeWidth = stroke)
    drawLine(ArtemisOrange, Offset(center.x - gap, center.y), Offset(center.x - gap - armLen, center.y), strokeWidth = stroke)
    drawLine(ArtemisOrange, Offset(center.x + gap, center.y), Offset(center.x + gap + armLen, center.y), strokeWidth = stroke)
}

/**
 * Draws an edge arrow indicator for an off-screen celestial object.
 * Finds the exact point where the line from screen-centre toward the object
 * intersects the screen border, then draws a filled arrow triangle there
 * plus the object label on the inside.
 */
private fun DrawScope.drawOffScreenIndicator(
    position: HorizontalPosition,
    R: FloatArray,
    screenW: Int,
    screenH: Int,
    color: Color,
    label: String,
) {
    val dir = SkyProjection.screenDirection(position, R) ?: return

    val cx = screenW / 2f
    val cy = screenH / 2f
    val margin = 44.dp.toPx()

    // Inset rect that the indicator circle stays within
    val left   = margin
    val right  = screenW - margin
    val top    = margin
    val bottom = screenH - margin

    // Find where the ray (cx,cy)+t*(dir.x,dir.y) first hits the inset rect boundary
    val candidates = mutableListOf<Float>()
    if (dir.x > 0f) candidates.add((right  - cx) / dir.x)
    if (dir.x < 0f) candidates.add((left   - cx) / dir.x)
    if (dir.y > 0f) candidates.add((bottom - cy) / dir.y)
    if (dir.y < 0f) candidates.add((top    - cy) / dir.y)
    val t = candidates.filter { it > 0f }.minOrNull() ?: return

    val ex = (cx + dir.x * t).coerceIn(left, right)
    val ey = (cy + dir.y * t).coerceIn(top, bottom)

    // Arrow angle: points FROM screen-centre TOWARD the object (outward)
    val angle = atan2(dir.y, dir.x)
    val tipLen      = 13.dp.toPx()
    val halfBase    = 8.dp.toPx()
    val backSet     = 5.dp.toPx()
    val perp = angle + Math.PI.toFloat() / 2f

    val tipX  = ex + cos(angle) * tipLen
    val tipY  = ey + sin(angle) * tipLen
    val bx    = ex - cos(angle) * backSet
    val by    = ey - sin(angle) * backSet
    val l1x   = bx + cos(perp) * halfBase
    val l1y   = by + sin(perp) * halfBase
    val l2x   = bx - cos(perp) * halfBase
    val l2y   = by - sin(perp) * halfBase

    val arrowPath = Path().apply {
        moveTo(tipX, tipY)
        lineTo(l1x, l1y)
        lineTo(l2x, l2y)
        close()
    }

    // Subtle glow behind arrow
    drawPath(arrowPath, color = color.copy(alpha = 0.25f))
    // Filled arrow
    drawPath(arrowPath, color = color)
    // Small dot at the base centre
    drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(ex, ey))

    // Label on the inside (opposite to arrow tip direction)
    val labelX = ex - cos(angle) * (tipLen + 6.dp.toPx())
    val labelY = ey - sin(angle) * (tipLen + 6.dp.toPx())
    drawContext.canvas.nativeCanvas.drawText(
        label,
        labelX,
        labelY,
        Paint().apply {
            textAlign = Paint.Align.CENTER
            textSize = 12.sp.toPx()
            this.color = color.copy(alpha = 0.95f).toArgb()
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        }
    )
}

// ── UI overlay composables ────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(state: SkyViewState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        when (val sc = state.spacecraftState) {
            is SpacecraftState.Loading -> {
                Text("Artemis II", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Fetching position…", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            is SpacecraftState.Tracking -> {
                val sign = if (sc.position.altitude < 0) "below" else "above"
                val alt = "%.0f".format(Math.abs(sc.position.altitude))
                val dir = azToCompass(sc.position.azimuth)
                Text(sc.missionName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${alt}° $sign horizon · $dir", color = ArtemisOrange, fontSize = 12.sp)
            }
            is SpacecraftState.NoActiveMission -> {
                Text("No active mission", color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp)
            }
            is SpacecraftState.Error -> {
                Text("Artemis II", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Signal lost", color = Color.Red.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun GuidanceCenter(state: SkyViewState, modifier: Modifier = Modifier) {
    val guidance = state.artemisGuidance ?: return
    val withinRange = abs(guidance.offsetAzDeg) < 45f && abs(guidance.offsetAltDeg) < 45f
    if (!withinRange && !guidance.isAligned) return
    val text = if (guidance.isAligned) "◎  Locked on" else guidanceHint(guidance.offsetAzDeg, guidance.offsetAltDeg)
    val color = if (guidance.isAligned) Color(0xFF4CAF50) else Color.White
    Text(
        text = text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun BottomCard(
    state: SkyViewState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .navigationBarsPadding()
            .clickable(onClick = onToggle),
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(width = 36.dp, height = 4.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f)),
        )
        Spacer(Modifier.height(4.dp))

        // All targets in a compact single row each: dot · name · distance
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Artemis
            when (val sc = state.spacecraftState) {
                is SpacecraftState.Tracking -> CompactTarget(ArtemisOrange, "Artemis", formatDistance(sc.position.distanceKm))
                is SpacecraftState.Loading  -> CompactTarget(ArtemisOrange, "Artemis", "…")
                else                        -> CompactTarget(ArtemisOrange.copy(alpha = 0.4f), "Artemis", "–")
            }
            // Sun
            state.sunPosition?.let { sun ->
                CompactTarget(SunYellow, "Sun", formatDistance(sun.distanceKm))
            }
            // Moon
            state.moonPosition?.let { moon ->
                CompactTarget(Color.White, "Moon", formatDistance(moon.distanceKm))
            }
        }

        SensorAccuracyRow(state.orientation.accuracy)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CompactTarget(color: Color, name: String, distance: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Text(name, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(distance, color = color.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun SensorAccuracyRow(accuracy: Int) {
    val (label, color) = when (accuracy) {
        0    -> "Compass unreliable" to Color(0xFFF44336)
        1    -> "Compass low" to Color(0xFFFF9800)
        2    -> "~10° uncertainty" to Color(0xFFFFC107)
        else -> "Compass accurate" to Color(0xFF4CAF50)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (i < accuracy) color else Color.White.copy(alpha = 0.18f)),
            )
            if (i < 2) Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = color.copy(alpha = 0.85f), fontSize = 11.sp)
    }
}

@Composable
private fun LensSwitcher(
    presets: List<ZoomPreset>,
    selectedIndex: Int,
    currentZoom: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEachIndexed { idx, preset ->
            val isSel = idx == selectedIndex
            // When actively pinch-zooming (no preset selected), highlight the nearest preset
            val isNearest = selectedIndex == -1 &&
                    idx == presets.indices.minByOrNull { abs(presets[it].zoomRatio - currentZoom) }
            val highlighted = isSel || isNearest
            val displayLabel = if (highlighted && abs(currentZoom - preset.zoomRatio) > 0.05f) {
                "%.1fx".format(currentZoom)
            } else {
                preset.label
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .then(
                        if (highlighted) Modifier.border(1.5.dp, ArtemisOrange, CircleShape)
                        else Modifier
                    )
                    .background(
                        if (highlighted) Color.Black.copy(alpha = 0.7f) else Color.Transparent,
                    )
                    .clickable { onSelect(idx) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    displayLabel,
                    color = if (highlighted) ArtemisOrange else Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CalibrationBanner(accuracy: Int, modifier: Modifier = Modifier) {
    val bgColor = if (accuracy == 0) Color(0xFF7B0000).copy(alpha = 0.88f)
                  else Color.Black.copy(alpha = 0.68f)
    val body    = if (accuracy == 0) "Compass unreliable\nCalibrate now — move phone in a figure-8"
                  else "Calibrating compass\nMove phone in a figure-8"
    Text(
        modifier = modifier
            .background(bgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        text = body,
        color = Color.White,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

// ── Permission rationale ──────────────────────────────────────────────────────

@Composable
private fun PermissionRationale(hasCamera: Boolean, onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = if (!hasCamera) stringResource(R.string.permission_camera_title)
                else stringResource(R.string.permission_location_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (!hasCamera) stringResource(R.string.permission_camera_body)
                else stringResource(R.string.permission_location_body),
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGrant) {
                Text(stringResource(R.string.grant_permission))
            }
        }
    }
}
