package com.nightcinder.helix.stream

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** In-memory [WebSocket]: records what was sent/closed, never touches the network. */
internal class FakeWebSocket(private val originalRequest: Request) : WebSocket {
    val sentText = mutableListOf<String>()
    val sentBytes = mutableListOf<ByteArray>()
    var closedCode: Int? = null

    override fun request(): Request = originalRequest
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean {
        sentText += text
        return true
    }

    override fun send(bytes: ByteString): Boolean {
        sentBytes += bytes.toByteArray()
        return true
    }
    override fun close(code: Int, reason: String?): Boolean {
        closedCode = code
        return true
    }

    override fun cancel() {}
}

/**
 * [OkHttpClient] whose [newWebSocket] hands back [FakeWebSocket]s and keeps the listeners so tests
 * can drive callbacks (onOpen / onClosing / onClosed / onFailure) deterministically, without sleeps.
 */
internal class FakeWebSocketClient : OkHttpClient() {
    val listeners = mutableListOf<WebSocketListener>()
    val sockets = mutableListOf<FakeWebSocket>()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val socket = FakeWebSocket(request)
        listeners += listener
        sockets += socket
        return socket
    }

    fun response(index: Int, code: Int = 101): Response =
        Response.Builder()
            .request(sockets[index].request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .build()

    fun open(index: Int) = listeners[index].onOpen(sockets[index], response(index))
}
