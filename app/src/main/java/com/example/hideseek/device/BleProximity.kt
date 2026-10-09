package com.example.hideseek.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.hideseek.data.GameClient
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

// Bluetooth LE proximity. During a game every phone broadcasts a tiny advertisement
// ("HS" + its 4-byte game tag from the server) and scans for the others. The signal
// strength (RSSI) of each tag heard is sent to the server once per second; the server
// turns it into "police within ~1 m" far more reliably than GPS can.
// The tag is random per game and means nothing outside it, so no identity is broadcast.

private const val MANUFACTURER_ID = 0xFFFF // reserved "test" company id: fine for a private beacon
private val PREFIX = byteArrayOf('H'.code.toByte(), 'S'.code.toByte())
private const val REPORT_EVERY_MS = 1_000L
private const val SIGHTING_MAX_AGE_MS = 3_000L

enum class BleStatus { IDLE, ON, OFF, DENIED, UNSUPPORTED }

val BLE_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    else emptyArray() // older Android: covered by the location permission

private fun Context.hasBlePermissions() = BLE_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** While [active], advertises [tag] and reports nearby players' tags to the server. */
@SuppressLint("MissingPermission")
@Composable
fun rememberBleProximity(active: Boolean, tag: String?, game: GameClient): BleStatus {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(BluetoothManager::class.java) }
    val adapter = manager?.adapter
    val supported = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    var granted by remember { mutableStateOf(context.hasBlePermissions()) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    val sightings = remember { ConcurrentHashMap<String, Pair<Int, Long>>() } // tag -> (rssi, at)
    val run = active && tag != null && supported

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasBlePermissions()
    }
    LaunchedEffect(run) {
        if (run && !granted) launcher.launch(BLE_PERMISSIONS)
    }
    // Notice the player switching Bluetooth on/off mid-game.
    LaunchedEffect(run) {
        while (run) {
            enabled = adapter?.isEnabled == true
            delay(2_000)
        }
    }

    DisposableEffect(run, granted, enabled, tag) {
        if (!run || !granted || !enabled || tag == null) return@DisposableEffect onDispose { }
        val advertiser = adapter!!.bluetoothLeAdvertiser
        val scanner = adapter.bluetoothLeScanner
        sightings.clear()

        val advertiseCallback = object : AdvertiseCallback() {}
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::record)
            private fun record(result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                if (data.size < 6 || data[0] != PREFIX[0] || data[1] != PREFIX[1]) return
                val seen = bytesToHex(data.copyOfRange(2, 6))
                if (seen != tag) sightings[seen] = result.rssi to System.currentTimeMillis()
            }
        }

        runCatching {
            advertiser?.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(false)
                    .build(),
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addManufacturerData(MANUFACTURER_ID, PREFIX + hexToBytes(tag))
                    .build(),
                advertiseCallback,
            )
        }
        runCatching {
            scanner?.startScan(
                listOf(ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, PREFIX, byteArrayOf(-1, -1)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
        }
        onDispose {
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            runCatching { scanner?.stopScan(scanCallback) }
        }
    }

    // Report what we hear once per second.
    LaunchedEffect(run, granted, enabled) {
        while (run && granted && enabled) {
            delay(REPORT_EVERY_MS)
            val cutoff = System.currentTimeMillis() - SIGHTING_MAX_AGE_MS
            val seen = JSONArray()
            sightings.forEach { (t, v) ->
                if (v.second >= cutoff) seen.put(JSONObject().put("tag", t).put("rssi", v.first))
            }
            if (seen.length() > 0) game.send("prox", JSONObject().put("seen", seen))
        }
    }

    return when {
        !run -> if (active && !supported) BleStatus.UNSUPPORTED else BleStatus.IDLE
        !granted -> BleStatus.DENIED
        !enabled -> BleStatus.OFF
        else -> BleStatus.ON
    }
}
