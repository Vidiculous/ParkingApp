package com.example.parking

import android.content.Context
import android.util.Log
import com.example.parking.Prefs.backendUrl
import com.example.parking.Prefs.dongleId
import com.example.parking.Prefs.userName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "PARK_REPO"

data class ParkingEntry(
    val name: String,
    val dongleId: String,
    val status: String,
    val since: String?,
    val manual: Boolean,
)

data class Occupancy(val parked: Int, val total: Int, val full: Boolean)

data class ParkingState(val users: List<ParkingEntry>, val occupancy: Occupancy)

sealed class PostResult {
    object Ok : PostResult()
    data class OkWithWarning(val warning: String) : PostResult()
    data class Error(val message: String) : PostResult()
}

object ParkingRepository {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no timeout for WebSocket
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    // ── HTTP POST ─────────────────────────────────────────────────────────────

    suspend fun postStatus(context: Context, status: String, manual: Boolean): PostResult =
        withContext(Dispatchers.IO) {
            val name = context.userName
            val dongle = context.dongleId
            val url = context.backendUrl
            if (name.isBlank()) return@withContext PostResult.Error("Name not configured")

            val body = JSONObject().apply {
                put("name", name)
                put("dongle_id", dongle)
                put("status", status)
                put("manual", manual)
            }.toString().toRequestBody(JSON)

            var attempt = 0
            while (attempt < 3) {
                try {
                    val resp = client.newCall(
                        Request.Builder().url("$url/api/park").post(body).build()
                    ).execute()
                    resp.use {
                        val responseBody = it.body?.string() ?: "{}"
                        Log.d(TAG, "POST /api/park → ${it.code} $responseBody")
                        if (it.isSuccessful) {
                            val json = JSONObject(responseBody)
                            val warning = json.optString("warning", "")
                            return@withContext if (warning.isNotEmpty())
                                PostResult.OkWithWarning(warning)
                            else
                                PostResult.Ok
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "POST attempt ${attempt + 1} failed: ${e.message}")
                }
                attempt++
                if (attempt < 3) kotlinx.coroutines.delay(2_000)
            }
            PostResult.Error("Failed after 3 attempts")
        }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    fun connectWebSocket(
        context: Context,
        onUpdate: (ParkingState) -> Unit,
        onConnectionChange: (connected: Boolean) -> Unit,
    ): WebSocket {
        val url = context.backendUrl.replace(Regex("^http"), "ws") + "/ws"
        val request = Request.Builder().url(url).build()

        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS connected to $url")
                onConnectionChange(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onUpdate(parseState(text))
                } catch (e: Exception) {
                    Log.w(TAG, "WS parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                onConnectionChange(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $reason")
                onConnectionChange(false)
            }
        })
    }

    private fun parseState(json: String): ParkingState {
        val root = JSONObject(json)
        val usersObj = root.optJSONObject("users") ?: root  // fallback for old format
        val occObj = root.optJSONObject("occupancy")

        val users = mutableListOf<ParkingEntry>()
        usersObj.keys().forEach { name ->
            val v = usersObj.getJSONObject(name)
            users.add(
                ParkingEntry(
                    name = name,
                    dongleId = v.optString("dongle_id", ""),
                    status = v.optString("status", "unknown"),
                    since = v.optString("since").takeIf { it.isNotEmpty() },
                    manual = v.optBoolean("manual", false),
                )
            )
        }

        users.sortWith(compareBy(
            { if (it.status == "parked") 0 else 1 },
            { it.name }
        ))

        val occupancy = if (occObj != null) Occupancy(
            parked = occObj.optInt("parked", 0),
            total = occObj.optInt("total", 7),
            full = occObj.optBoolean("full", false),
        ) else Occupancy(
            parked = users.count { it.status == "parked" },
            total = 7,
            full = false,
        )

        return ParkingState(users, occupancy)
    }
}
