package bucketlist.core

import java.net.URI
import java.net.URISyntaxException

private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

fun normaliseServerUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter the server address" }
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("That doesn't look like a web address")
    }
    val host = uri.host
    require(!host.isNullOrEmpty()) { "That doesn't look like a web address" }
    require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) { "Use just the address, like https://bucket.example.com" }
    when (uri.scheme?.lowercase()) {
        "https" -> Unit
        "http" -> require(host.lowercase() in LOOPBACK_HOSTS) { "The server must use https://" }
        else -> throw IllegalArgumentException("The server must use https://")
    }
    return URI(uri.scheme.lowercase(), null, host.lowercase(), uri.port, uri.rawPath.trimEnd('/').ifEmpty { null }, null, null).toString()
}
