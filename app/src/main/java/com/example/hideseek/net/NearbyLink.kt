package com.example.hideseek.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject

// Phone-to-phone link with Google Nearby Connections. No internet, router or server:
// Nearby finds phones over Bluetooth / BLE and moves the connection to a direct Wi-Fi
// link (hotspot / Wi-Fi Direct / same LAN) when it can, falling back to Bluetooth.
//
// Star topology: the host phone advertises its room, the other phones discover it and
// connect. Every message is one small JSON object sent as a BYTES payload.

private const val SERVICE_ID = "com.example.hideseek.chorsipahi"
private const val NAME_PREFIX = "HS1"
private val STRATEGY = Strategy.P2P_STAR

/** Runtime permissions Nearby Connections needs on this Android version. */
val NEARBY_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

/**
 * Permissions from [NEARBY_PERMISSIONS] still blocking Nearby. We ask for precise location,
 * but on Android 12+ the user may pick "Approximate": Bluetooth scanning there is covered by
 * BLUETOOTH_SCAN, so either location grant is enough. Android 10-11 need precise location.
 */
fun missingNearbyPermissions(context: Context): List<String> {
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    val locationOk = when {
        Build.VERSION.SDK_INT >= 31 || Build.VERSION.SDK_INT <= 28 -> has(fine) || has(coarse)
        else -> has(fine)
    }
    return NEARBY_PERMISSIONS.filter { p ->
        if (p == fine || p == coarse) !locationOk else !has(p)
    }
}

interface LinkListener {
    /** Discovery: a host is advertising room [code]. */
    fun onRoomFound(endpointId: String, code: String, hostName: String) {}
    fun onRoomLost(endpointId: String) {}
    fun onConnected(endpointId: String) {}
    fun onDisconnected(endpointId: String) {}
    /** A connection request failed or was rejected. */
    fun onConnectFailed(endpointId: String, message: String) {}
    fun onMessage(endpointId: String, msg: JSONObject)
}

class NearbyLink(context: Context, private val listener: LinkListener) {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val connected = HashSet<String>()
    var advertising = false
        private set
    var discovering = false
        private set

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val msg = runCatching { JSONObject(String(bytes)) }.getOrNull() ?: return
            listener.onMessage(endpointId, msg)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        // Both sides accept automatically: the room code and host name are the "pairing".
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected += endpointId
                listener.onConnected(endpointId)
            } else {
                listener.onConnectFailed(endpointId, describe(result.status.statusCode))
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            listener.onDisconnected(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val parts = info.endpointName.split("|", limit = 3)
            if (parts.size == 3 && parts[0] == NAME_PREFIX) listener.onRoomFound(endpointId, parts[1], parts[2])
        }

        override fun onEndpointLost(endpointId: String) = listener.onRoomLost(endpointId)
    }

    /** Host: make room [code] visible to nearby phones. */
    fun startAdvertising(code: String, hostName: String, onError: (String) -> Unit) {
        if (advertising) client.stopAdvertising()
        advertising = true
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising("$NAME_PREFIX|$code|${hostName.replace("|", " ")}", SERVICE_ID, lifecycle, options)
            .addOnFailureListener { e ->
                if (statusOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) return@addOnFailureListener
                advertising = false
                onError(describe(e))
            }
    }

    fun stopAdvertising() {
        if (advertising) client.stopAdvertising()
        advertising = false
    }

    /** Guest: look for rooms nearby. */
    fun startDiscovery(onError: (String) -> Unit) {
        if (discovering) return
        discovering = true
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discovery, options).addOnFailureListener { e ->
            if (statusOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return@addOnFailureListener
            discovering = false
            onError(describe(e))
        }
    }

    fun stopDiscovery() {
        if (discovering) client.stopDiscovery()
        discovering = false
    }

    /** Guest: connect to a host found by discovery. */
    fun connect(endpointId: String, myName: String) {
        client.requestConnection(myName, endpointId, lifecycle).addOnFailureListener { e ->
            if (statusOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                connected += endpointId
                listener.onConnected(endpointId)
            } else {
                listener.onConnectFailed(endpointId, describe(e))
            }
        }
    }

    fun isConnected(endpointId: String) = endpointId in connected

    fun send(endpointId: String, msg: JSONObject) {
        if (endpointId !in connected) return
        client.sendPayload(endpointId, Payload.fromBytes(msg.toString().toByteArray()))
    }

    fun disconnect(endpointId: String) {
        connected -= endpointId
        client.disconnectFromEndpoint(endpointId)
    }

    /** Drop every connection and stop advertising / discovery. */
    fun stopAll() {
        client.stopAllEndpoints()
        connected.clear()
        advertising = false
        discovering = false
    }

    private fun statusOf(e: Exception) = (e as? ApiException)?.statusCode

    private fun describe(e: Exception): String = statusOf(e)?.let(::describe) ?: (e.message ?: "Connection failed")

    private fun describe(code: Int): String = when (code) {
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
        -> "Allow Nearby devices and Location permissions to play"
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error - turn Bluetooth off and on, then try again"
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Turn on Bluetooth and Wi-Fi (and Location), then try again"
        ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> "The host rejected the connection"
        ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> "That room is no longer nearby"
        ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR -> "Lost the connection - move closer and try again"
        else -> "Connection failed (${ConnectionsStatusCodes.getStatusCodeString(code)}). " +
            "Check Bluetooth, Wi-Fi and Location are on."
    }
}
