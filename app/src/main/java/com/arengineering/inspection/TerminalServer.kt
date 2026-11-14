package com.arengineering.inspection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URISyntaxException
import java.io.ByteArrayOutputStream
import java.io.IOException

internal data class TerminalResponse(val status: Int, val body: String) {
    val successful: Boolean get() = status in 200..299

    fun formattedBody(): String = try {
        when (val json = JSONTokener(body).nextValue()) {
            is JSONObject -> json.toString(2)
            is JSONArray -> json.toString(2)
            else -> body
        }
    } catch (_: JSONException) {
        body
    }
}

internal class TerminalServer {
    var address: String? = null
        private set
    private var token: String? = null
    private var borrowedToken = false

    fun copyConnection() = TerminalServer().also {
        it.address = address
        it.token = token
        it.borrowedToken = token != null
    }

    fun connect(value: String): Boolean {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }
        if (uri.scheme !in listOf("http", "https") || uri.host == null || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
            (uri.port != -1 && uri.port !in 1..65535)
        ) return false

        val next = value.trimEnd('/')
        if (address != next) {
            token = null
            borrowedToken = false
        }
        address = next
        return true
    }

    fun disconnect() {
        address = null
        token = null
        borrowedToken = false
    }

    suspend fun health() = request("/health", authenticated = false)

    suspend fun profile() = request("/auth/me")

    suspend fun equipment() = request("/equipment")

    suspend fun resolveEquipment(code: String, fromPlate: Boolean): TerminalResponse {
        val kind = if (fromPlate) "nameplate" else "qr"
        return request("/equipment/resolve?kind=$kind&value=${URLEncoder.encode(code, "UTF-8")}")
    }

    suspend fun history(id: Long) = request("/equipment/$id/reports?limit=20")

    suspend fun saveReport(data: JSONObject) = request("/reports", data)

    suspend fun login(email: String, password: String): TerminalResponse {
        token = null
        borrowedToken = false
        val data = JSONObject().put("email", email).put("password", password)
        val response = request("/auth/login", data, authenticated = false)
        if (!response.successful) return response

        val body = JSONObject(response.body)
        token = body.getString("access_token")
        body.remove("access_token")
        return response.copy(body = body.toString())
    }

    suspend fun logout(): TerminalResponse {
        if (borrowedToken) {
            token = null
            borrowedToken = false
            return TerminalResponse(204, "")
        }
        val response = request("/auth/logout", JSONObject())
        if (response.successful || response.status == 401) token = null
        return response
    }

    suspend fun passport(id: Long, checksOnly: Boolean): TerminalResponse {
        val response = request("/equipment/$id/passport")
        if (!checksOnly || !response.successful) return response
        val passport = JSONObject(response.body)
        val checks = JSONObject()
            .put("equipment", passport.getJSONObject("equipment"))
            .put("components", passport.getJSONArray("components"))
            .put("checks", passport.getJSONArray("checks"))
        return response.copy(body = checks.toString())
    }

    suspend fun saveCheck(equipmentId: Long, checkId: Long, outcome: String): TerminalResponse {
        val item = JSONObject().put("check_id", checkId).put("outcome", outcome)
        val data = JSONObject().put("equipment_id", equipmentId).put("items", JSONArray().put(item))
        return request("/reports", data)
    }

    suspend fun report(id: Long) = request("/reports/$id")

    suspend fun reportPdf(id: Long): ByteArray = exchange("/reports/$id/export/pdf", "application/pdf") { connection ->
        if (connection.responseCode != 200 || connection.contentType?.substringBefore(';') != "application/pdf") {
            throw IOException("PDF export returned ${connection.responseCode}")
        }
        val bytes = connection.inputStream.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > 8 * 1024 * 1024) throw IOException("PDF exceeds the download limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size < 5 || bytes.copyOfRange(0, 5).decodeToString() != "%PDF-") throw IOException("Invalid PDF")
        bytes
    }

    suspend fun reviewFrame(equipmentId: Long, checkId: Long, data: JSONObject): TerminalResponse =
        request("/equipment/$equipmentId/checks/$checkId/vision-review", data, readTimeoutMs = 45_000)

    private suspend fun request(
        path: String,
        data: JSONObject? = null,
        authenticated: Boolean = true,
        readTimeoutMs: Int = 4_000,
    ): TerminalResponse = exchange(path, "application/json", authenticated, readTimeoutMs) { connection ->
        if (data != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        TerminalResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
    }

    private suspend fun <T> exchange(
        path: String, accept: String, authenticated: Boolean = true, readTimeoutMs: Int = 4_000,
        read: (HttpURLConnection) -> T,
    ): T {
        val url = URI.create(checkNotNull(address) + path).toURL()
        val bearer = token.takeIf { authenticated }
        return withContext(Dispatchers.IO) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = readTimeoutMs
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", accept)
                bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                read(connection)
            } finally {
                connection.disconnect()
            }
        }
    }
}
