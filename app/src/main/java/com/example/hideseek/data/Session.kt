package com.example.hideseek.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Address of the game server as seen from the phone (PC's Wi-Fi IP; changeable on the login screen). */
const val DEFAULT_SERVER_URL = "http://192.168.68.64:4000"

class ApiException(message: String, val status: Int) : Exception(message)

/** Login token, server address, the signed-in user, and REST calls to the server. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("hideseek", Context.MODE_PRIVATE)

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, null))
    val token: StateFlow<String?> = _token

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL)
    val serverUrl: StateFlow<String> = _serverUrl

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    /** Random id for this install; the server ties the guest account to it. */
    private val deviceId: String = prefs.getString(KEY_DEVICE, null)
        ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }

    /** No login screen: get (or re-use) this device's guest account. */
    suspend fun guestLogin() {
        val res = post("/api/auth/guest", JSONObject().put("deviceId", deviceId)) as JSONObject
        signIn(res.getString("token"), User.parse(res.getJSONObject("user")))
    }

    suspend fun updateName(name: String) {
        _user.value = User.parse(post("/api/me", JSONObject().put("name", name)) as JSONObject)
    }

    fun signIn(token: String, user: User) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _user.value = user
        _token.value = token
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _token.value = null
        _user.value = null
    }

    fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER, clean).apply()
        _serverUrl.value = clean
    }

    suspend fun refreshUser() {
        try {
            _user.value = User.parse(get("/api/me") as JSONObject)
        } catch (e: ApiException) {
            if (e.status == 401) signOut() else throw e
        }
    }

    suspend fun get(path: String): Any = request("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()): Any = request("POST", path, body)
    suspend fun delete(path: String): Any = request("DELETE", path, null)

    /** JSON request to the server. Returns a JSONObject or JSONArray; throws ApiException with a friendly message. */
    private suspend fun request(method: String, path: String, body: JSONObject?): Any = withContext(Dispatchers.IO) {
        val base = _serverUrl.value
        val conn = try {
            (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw ApiException("Invalid server address: $base", 0)
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            _token.value?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONTokener(text).nextValue() }.getOrNull()
            if (code >= 400) {
                val msg = (json as? JSONObject)?.optString("error")?.takeIf { it.isNotBlank() } ?: "Request failed ($code)"
                if (code == 401 && _token.value != null) signOut()
                throw ApiException(msg, code)
            }
            json ?: JSONObject()
        } catch (e: IOException) {
            throw ApiException("Can't reach the server at $base. Check Wi-Fi / server address.", 0)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_SERVER = "server_url"
        const val KEY_DEVICE = "device_id"
    }
}

fun Any.asArray(): JSONArray = this as? JSONArray ?: JSONArray()
