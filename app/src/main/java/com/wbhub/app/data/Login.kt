package com.wbhub.app.data

import com.wbhub.app.proto.Credential
import com.wbhub.app.proto.Wire
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Browser-based sign-in: ask the upstream for an auth URL, let the user complete
 * it in a browser, then poll until the token lands.
 *
 * This is the flow the official CLI uses and needs no desktop client — the
 * credential it produces is the API token, which a browser cookie never is.
 * The two builds have separate accounts and hosts, so the caller picks one.
 */
object Login {

    private const val STATE_PATH = "/v2/plugin/auth/state"
    private const val TOKEN_PATH = "/v2/plugin/auth/token"
    private const val ACCOUNT_PATH = "/v2/plugin/login/account"

    /** The build a sign-in targets. */
    enum class Realm(val label: String) {
        CN("国内版"),
        GLOBAL("国际版"),
        ;

        fun toRegion(): Wire.Region = if (this == GLOBAL) Wire.Region.GLOBAL else Wire.Region.CN
    }

    data class Session(val state: String, val authUrl: String, val realm: Realm)

    private fun base(realm: Realm) = Wire.chatBase(realm.toRegion())

    private fun userAgent(realm: Realm) =
        if (realm == Realm.GLOBAL) "WorkBuddy/5.5.2" else "WorkBuddy/5.5.6"

    private fun origin(realm: Realm) = Wire.originOf(realm.toRegion())

    private fun open(url: String, realm: Realm): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("User-Agent", userAgent(realm))
            setRequestProperty("Origin", origin(realm))
            setRequestProperty("Referer", "${origin(realm)}/")
        }

    private fun read(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
    }

    fun start(realm: Realm): Session {
        val conn = open("${base(realm)}$STATE_PATH?platform=CLI", realm).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        val text = try {
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write("{}") }
            read(conn)
        } finally {
            conn.disconnect()
        }
        val data = JSONObject(text).optJSONObject("data")
            ?: throw RuntimeException("auth/state 返回异常")
        val state = data.optString("state")
        val authUrl = data.optString("authUrl")
        if (state.isEmpty() || authUrl.isEmpty()) throw RuntimeException("缺少 state/authUrl")
        return Session(state, authUrl, realm)
    }

    sealed class Poll {
        object Pending : Poll()
        data class Done(val credential: Credential) : Poll()
        data class Failed(val message: String) : Poll()
    }

    fun poll(state: String, realm: Realm): Poll {
        val conn = open("${base(realm)}$TOKEN_PATH?state=$state", realm).apply { requestMethod = "GET" }
        val text = try {
            read(conn)
        } catch (e: Exception) {
            return Poll.Pending
        } finally {
            conn.disconnect()
        }
        val payload = JSONObject(text)
        val code = payload.optInt("code", -1)
        // 11217 is the upstream's "login still in progress" marker.
        if (code == 11217) return Poll.Pending
        if (code != 0) return Poll.Failed(payload.optString("msg", "code=$code"))
        val data = payload.optJSONObject("data") ?: return Poll.Pending
        val token = data.optString("accessToken")
        if (token.isEmpty()) return Poll.Pending
        return Poll.Done(
            Credential(
                accessToken = token,
                refreshToken = data.optString("refreshToken"),
                expiresAt = data.optLong("expiresAt", 0L),
                // The response may omit the domain; falling back to the version
                // the user signed in to keeps the credential filed under the
                // right build instead of defaulting to CN.
                domain = data.optString("domain").ifEmpty { defaultDomain(realm) },
                uid = uidOf(token),
                nickname = nickname(state, token, realm) ?: "",
                source = "oauth",
            )
        )
    }

    private fun nickname(state: String, token: String, realm: Realm): String? {
        return try {
            val conn = open("${base(realm)}$ACCOUNT_PATH?state=$state", realm).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
            }
            val text = try {
                read(conn)
            } finally {
                conn.disconnect()
            }
            JSONObject(text).optJSONObject("data")?.optString("nickname")?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** The domain that identifies a build when the response omits one. */
    private fun defaultDomain(realm: Realm): String =
        if (realm == Realm.GLOBAL) "www.workbuddy.ai" else Wire.chatBase(Wire.Region.CN)

    private fun uidOf(token: String): String {
        val parts = token.split(".")
        if (parts.size < 2) return ""
        return try {
            val bytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            JSONObject(String(bytes, StandardCharsets.UTF_8)).optString("sub", "")
        } catch (e: Exception) {
            ""
        }
    }
}
