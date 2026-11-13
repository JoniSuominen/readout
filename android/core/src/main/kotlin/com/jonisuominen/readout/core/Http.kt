package com.jonisuominen.readout.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMillis: Int = 15_000,
)

class HttpResponse(val status: Int, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * Plain HTTPS without redirects or caching. Fixed API endpoints never need a
 * redirect, and one must never forward a token request.
 */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = runInterruptible(Dispatchers.IO) {
        val url = URI(request.url).toURL()
        require(url.protocol == "https") { "Only HTTPS requests are allowed." }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = request.timeoutMillis
            connection.readTimeout = request.timeoutMillis
            connection.requestMethod = request.method
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}

internal fun queryString(items: List<Pair<String, String>>): String =
    items.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
