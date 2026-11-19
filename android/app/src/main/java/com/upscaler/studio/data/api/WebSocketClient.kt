package com.upscaler.studio.data.api

import com.google.gson.Gson
import com.upscaler.studio.data.model.ProgressEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed class StreamPacket {
    data class Event(val event: ProgressEvent) : StreamPacket()
    data class Disconnected(val reason: String) : StreamPacket()
}

class JobWebSocketClient(
    private val okHttpClient: OkHttpClient
) {
    private val gson = Gson()

    fun streamProgress(baseUrl: String, jobId: String, authToken: String?): Flow<StreamPacket> = callbackFlow {
        val cleanBase = baseUrl.trimEnd('/')
        val wsUrl = when {
            cleanBase.startsWith("https://", ignoreCase = true) ->
                "wss://" + cleanBase.removePrefix("https://")
            cleanBase.startsWith("http://", ignoreCase = true) ->
                "ws://" + cleanBase.removePrefix("http://")
            else -> "wss://$cleanBase"
        } + "/api/v1/jobs/$jobId/stream"

        val requestBuilder = Request.Builder().url(wsUrl)
        if (!authToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        }

        var terminalReceived = false

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = gson.fromJson(text, ProgressEvent::class.java)
                    if (event.type in listOf("complete", "error", "cancelled")) {
                        terminalReceived = true
                    }
                    trySend(StreamPacket.Event(event))
                    if (terminalReceived) {
                        close()
                    }
                } catch (_: Exception) {
                    // Ignore malformed single frame packet without tearing down active stream
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!terminalReceived) {
                    val msg = if (code == 4401) "Session authentication rejected (401)." else (reason.ifBlank { "Stream closing" })
                    trySend(StreamPacket.Disconnected(msg))
                    close()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!terminalReceived) {
                    trySend(StreamPacket.Disconnected(reason.ifBlank { "Connection closed" }))
                }
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!terminalReceived) {
                    val detail = when (response?.code) {
                        401 -> "Unauthorized: invalid or expired session token."
                        404 -> "Job not found on backend."
                        else -> t.localizedMessage ?: "WebSocket connection interrupted"
                    }
                    trySend(StreamPacket.Disconnected(detail))
                }
                close()
            }
        }

        val ws = okHttpClient.newWebSocket(requestBuilder.build(), listener)
        awaitClose {
            try {
                ws.close(1000, "Client detached")
            } catch (_: Exception) {
                ws.cancel()
            }
        }
    }
}
