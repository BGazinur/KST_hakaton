package com.arengineering.inspection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException

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
        if (address != next) token = null
        address = next
        return true
    }

    fun disconnect() {
        address = null
        token = null
    }

    suspend fun health() = request("/health", authenticated = false)

    suspend fun login(email: String, password: String): TerminalResponse {
        token = null
        val data = JSONObject().put("email", email).put("password", password)
        val response = request("/auth/login", data, authenticated = false)
        if (!response.successful) return response

        val body = JSONObject(response.body)
        token = body.getString("access_token")
        body.remove("access_token")
        return response.copy(body = body.toString())
    }

    suspend fun logout(): TerminalResponse {
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

    private suspend fun request(
        path: String,
        data: JSONObject? = null,
        authenticated: Boolean = true,
    ): TerminalResponse {
        val url = URI.create(checkNotNull(address) + path).toURL()
        val bearer = token.takeIf { authenticated }
        return withContext(Dispatchers.IO) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (data != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                TerminalResponse(status, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
    }
}
