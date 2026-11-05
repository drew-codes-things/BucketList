package bucketlist.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Base64

class ApiException(val status: Int, message: String, val retryAfterSeconds: Int = 0) : IOException(message)

class RemoteEntry(val id: String, val sealedMeta: ByteArray)

class LoginResult(val token: String, val wrappedListKey: ByteArray)

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 30_000

private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

class BucketApi(baseUrl: String, var token: String? = null) {
    private val base = baseUrl.trimEnd('/')

    private fun open(method: String, path: String, authenticated: Boolean = true): HttpURLConnection {
        val connection = java.net.URI(base + path).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.useCaches = false
        if (authenticated) {
            val current = token ?: throw ApiException(401, "not signed in")
            connection.setRequestProperty("Authorization", "Bearer $current")
        }
        return connection
    }

    private fun readAll(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        val output = ByteArrayOutputStream()
        stream.use { it.copyTo(output) }
        return output.toByteArray()
    }

    private fun failure(connection: HttpURLConnection): ApiException {
        val body = readAll(connection.errorStream).toString(Charsets.UTF_8)
        val message = runCatching { JSONObject(body).optString("error", body) }.getOrDefault(body)
        val retry = connection.getHeaderField("Retry-After")?.toIntOrNull() ?: 0
        return ApiException(connection.responseCode, message.ifEmpty { "HTTP ${connection.responseCode}" }, retry)
    }

    private fun sendJson(connection: HttpURLConnection, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(bytes) }
    }

    private fun jsonResult(connection: HttpURLConnection, expected: Int): String {
        if (connection.responseCode != expected) throw failure(connection)
        return readAll(connection.inputStream).toString(Charsets.UTF_8)
    }

    private fun expectNoContent(connection: HttpURLConnection) {
        if (connection.responseCode != 204) throw failure(connection)
    }

    fun getKdf(): KdfParams {
        val connection = open("GET", "/v1/kdf", authenticated = false)
        val json = JSONObject(jsonResult(connection, 200))
        return KdfParams(decode(json.getString("salt")), json.getInt("iterations"), json.getInt("memoryKiB"), json.getInt("parallelism"))
    }

    fun createAccount(setupToken: String, params: KdfParams, authKey: ByteArray, wrappedListKey: ByteArray) {
        val connection = open("POST", "/v1/account", authenticated = false)
        connection.setRequestProperty("X-Setup-Token", setupToken)
        val kdf = JSONObject()
            .put("salt", encode(params.salt))
            .put("iterations", params.iterations)
            .put("memoryKiB", params.memoryKiB)
            .put("parallelism", params.parallelism)
        sendJson(connection, JSONObject().put("kdf", kdf).put("authKey", encode(authKey)).put("wrappedListKey", encode(wrappedListKey)))
        jsonResult(connection, 201)
    }

    fun login(authKey: ByteArray): LoginResult {
        val connection = open("POST", "/v1/login", authenticated = false)
        sendJson(connection, JSONObject().put("authKey", encode(authKey)))
        val json = JSONObject(jsonResult(connection, 200))
        val result = LoginResult(json.getString("token"), decode(json.getString("wrappedListKey")))
        token = result.token
        return result
    }

    fun logout() {
        val connection = open("POST", "/v1/logout")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(0)
        connection.outputStream.close()
        expectNoContent(connection)
        token = null
    }

    fun listEntries(): List<RemoteEntry> {
        val array = JSONArray(jsonResult(open("GET", "/v1/files"), 200))
        return (0 until array.length()).map { position ->
            val entry = array.getJSONObject(position)
            RemoteEntry(entry.getString("id"), decode(entry.getString("meta")))
        }
    }

    fun createEntry(sealedMeta: ByteArray): String {
        val connection = open("POST", "/v1/files")
        sendJson(connection, JSONObject().put("meta", encode(sealedMeta)))
        return JSONObject(jsonResult(connection, 201)).getString("id")
    }

    fun appendContent(id: String, offset: Long, bytes: ByteArray) {
        val connection = open("PUT", "/v1/files/$id/content?offset=$offset")
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.outputStream.use { it.write(bytes) }
        if (connection.responseCode != 200) throw failure(connection)
        readAll(connection.inputStream)
    }

    fun finalize(id: String, sealedSize: Long) {
        val connection = open("POST", "/v1/files/$id/finalize")
        sendJson(connection, JSONObject().put("size", sealedSize))
        expectNoContent(connection)
    }

    fun updateMeta(id: String, sealedMeta: ByteArray) {
        val connection = open("PUT", "/v1/files/$id/meta")
        sendJson(connection, JSONObject().put("meta", encode(sealedMeta)))
        expectNoContent(connection)
    }

    fun delete(id: String) {
        expectNoContent(open("DELETE", "/v1/files/$id"))
    }
}
