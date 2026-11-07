package me.ik.argon.wax

import java.net.HttpURLConnection
import java.net.URL

/**
 * Platform-HTTPS transport for the WARP provisioning API with an HONEST client
 * identity: Argon's own User-Agent and the stock platform TLS stack. There is no
 * official-client header, UA, or TLS-fingerprint mimicry; if the provider declines
 * unofficial clients the caller surfaces that instead of working around it.
 * Bodies carry keys/tokens and are never logged.
 */
class WaxRegHttpTransport(private val userAgent: String) : WaxRegTransport {
    override fun request(
        method: String,
        url: String,
        bearerToken: String?,
        jsonBody: String?,
    ): WaxRegHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            if (bearerToken != null) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            if (jsonBody != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return WaxRegHttpResponse(status, body.take(16384))
        } finally {
            connection.disconnect()
        }
    }
}
