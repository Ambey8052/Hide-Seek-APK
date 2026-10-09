package com.example.hideseek.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import com.example.hideseek.data.GameClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.sqrt

// ------------------------------------------------------------------ shake

private const val SHAKE_THRESHOLD_G = 1.9f // a resting phone reads ~1 g
private const val SHAKE_WINDOW_MS = 700L
private const val SHAKE_HITS = 3

/** Calls onShake when the phone is shaken firmly (several jolts within ~0.7 s). */
@Composable
fun ShakeDetector(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onShake)
    DisposableEffect(enabled) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sensor == null) return@DisposableEffect onDispose { }
        var hits = 0
        var firstHitAt = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g < SHAKE_THRESHOLD_G) return
                val now = System.currentTimeMillis()
                if (now - firstHitAt > SHAKE_WINDOW_MS) {
                    firstHitAt = now
                    hits = 0
                }
                if (++hits >= SHAKE_HITS) {
                    hits = 0
                    firstHitAt = 0
                    callback()
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
}

// ------------------------------------------------------------------ GPS

enum class GpsStatus { IDLE, STARTING, OK, DENIED, OFF }

data class GpsState(val status: GpsStatus, val accuracy: Float? = null)

private const val KEEPALIVE_MS = 5_000L
private const val MAX_RESEND_AGE_MS = 15_000L

private fun Context.hasLocationPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** While [active], streams high-accuracy GPS fixes to the server as "loc" events. */
@SuppressLint("MissingPermission")
@Composable
fun rememberLocationReporter(active: Boolean, game: GameClient): GpsState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(GpsState(GpsStatus.IDLE)) }
    var granted by remember { mutableStateOf(context.hasLocationPermission()) }
    var last by remember { mutableStateOf<Pair<JSONObject, Long>?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || context.hasLocationPermission()
        if (!granted) state = GpsState(GpsStatus.DENIED)
    }

    LaunchedEffect(active, granted) {
        if (active && !granted) {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    DisposableEffect(active, granted) {
        if (!active || !granted) {
            if (!active) state = GpsState(GpsStatus.IDLE)
            return@DisposableEffect onDispose { }
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val enabled = lm == null || Build.VERSION.SDK_INT < 28 || lm.isLocationEnabled
        state = GpsState(if (enabled) GpsStatus.STARTING else GpsStatus.OFF)

        val client = LocationServices.getFusedLocationProviderClient(context)
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val fix = JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
                    .put("mocked", loc.isMockLocation())
                last = fix to System.currentTimeMillis()
                state = GpsState(GpsStatus.OK, loc.accuracy)
                game.send("loc", fix)
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000)
            .setMinUpdateIntervalMillis(1_000)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }

    // A player standing perfectly still may get few updates; re-send the latest fix so the
    // server doesn't think they lost signal. Old fixes are never re-sent.
    LaunchedEffect(active) {
        while (active) {
            delay(KEEPALIVE_MS)
            val (fix, at) = last ?: continue
            val age = System.currentTimeMillis() - at
            if (age in KEEPALIVE_MS until MAX_RESEND_AGE_MS) game.send("loc", fix)
        }
    }
    return state
}

@Suppress("DEPRECATION")
private fun Location.isMockLocation() = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider

// ------------------------------------------------------------------ vibration

fun Context.vibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

fun Context.vibratePattern(vararg pattern: Long) {
    val v = vibrator() ?: return
    if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    else @Suppress("DEPRECATION") v.vibrate(pattern, -1)
}

fun Context.buzz(ms: Long = 40) = vibratePattern(0, ms)

fun Context.stopVibration() = vibrator()?.cancel()

// ------------------------------------------------------------------ misc

/** Server-synced current time, refreshed every [intervalMs]. */
@Composable
fun rememberServerNow(game: GameClient, intervalMs: Long = 250): Long {
    val now by produceState(game.serverNow()) {
        while (true) {
            value = game.serverNow()
            delay(intervalMs)
        }
    }
    return now
}

/** Keeps the screen on while this composable is shown (in-game). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

fun qrBitmap(text: String, size: Int = 600): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

fun formatClock(ms: Long): String {
    val total = ((ms.coerceAtLeast(0) + 999) / 1000)
    return "%02d:%02d".format(total / 60, total % 60)
}

fun formatRs(rs: Double): String =
    if (rs == Math.floor(rs)) "₹%,d".format(rs.toLong()) else "₹%,.2f".format(rs)
