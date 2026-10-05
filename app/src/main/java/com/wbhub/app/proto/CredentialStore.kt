package com.wbhub.app.proto

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Stores one credential per build and remembers which one is active.
 *
 * The two builds are separate account systems, so a user may legitimately hold
 * both at once and switch between them. Keeping them in separate slots — rather
 * than overwriting a single file — is what makes that switch instant, with no
 * re-login.
 */
class CredentialStore(context: Context) {

    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("wb-hub", Context.MODE_PRIVATE)

    fun fileFor(region: Wire.Region): File = File(dir, "wb-auth-${region.name.lowercase()}.json")

    /** Loads the stored credential for one build, or null when it has none. */
    fun load(region: Wire.Region): Credential? {
        val file = fileFor(region)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val token = json.optString("accessToken")
            if (token.isEmpty()) return null
            val stored = json.optLong("expiresAt", 0L)
            Credential(
                accessToken = token,
                refreshToken = json.optString("refreshToken"),
                // The login response may omit expiresAt; the token always
                // carries exp, so the claim is the fallback source.
                expiresAt = if (stored > 0) stored else expiryFromJwt(token),
                domain = json.optString("domain").ifEmpty { defaultDomain(region) },
                uid = json.optString("uid"),
                nickname = json.optString("nickname"),
                enterpriseId = json.optString("enterpriseId").ifEmpty { null },
                source = json.optString("source", "oauth"),
            ).takeIf { it.accessToken.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Saves a credential into one build's slot.
     *
     * The slot and the credential's domain are kept consistent: the domain is
     * what selects the upstream host, so a credential filed under one build but
     * carrying the other's domain would send its token to the wrong service.
     */
    fun save(region: Wire.Region, credential: Credential) {
        val normalized = if (Wire.regionOf(credential.domain) == region) {
            credential
        } else {
            credential.copy(domain = defaultDomain(region))
        }
        val json = JSONObject().apply {
            put("accessToken", normalized.accessToken)
            put("refreshToken", normalized.refreshToken)
            put("expiresAt", normalized.expiresAt)
            put("domain", normalized.domain)
            put("uid", normalized.uid)
            put("nickname", normalized.nickname)
            put("enterpriseId", normalized.enterpriseId ?: "")
            put("source", normalized.source)
        }
        fileFor(region).writeText(json.toString(2))
        // The daemon reads only the active account, so the mirror is refreshed
        // whenever the stored credential that is in use changes.
        if (activeRegion() == region) mirrorActiveForDaemon()
    }

    fun clear(region: Wire.Region) {
        val file = fileFor(region)
        if (file.exists()) file.delete()
    }

    /**
     * The build whose credential is currently in use.
     *
     * The stored preference is authoritative, including when that build has no
     * credential yet: choosing a version is what the sign-in button then acts
     * on, so a selection must never be silently overridden.
     */
    fun activeRegion(): Wire.Region {
        val stored = prefs.getString(KEY_ACTIVE, null) ?: return Wire.Region.CN
        return runCatching { Wire.Region.valueOf(stored) }.getOrDefault(Wire.Region.CN)
    }

    fun setActiveRegion(region: Wire.Region) {
        prefs.edit().putString(KEY_ACTIVE, region.name).apply()
        mirrorActiveForDaemon()
    }

    /**
     * Publishes the active credential to the path the standalone daemon reads.
     *
     * The daemon runs under the shell uid and cannot open app-private files, so
     * the copy is written to a world-readable location. Only the credential in
     * use is mirrored, which keeps a non-active account's token out of it.
     */
    fun mirrorActiveForDaemon() {
        val target = File(DAEMON_AUTH_PATH)
        val credential = active()
        runCatching {
            target.parentFile?.mkdirs()
            if (credential == null) {
                if (target.exists()) target.delete()
                return@runCatching
            }
            val json = JSONObject().apply {
                put("accessToken", credential.accessToken)
                put("refreshToken", credential.refreshToken)
                put("expiresAt", credential.expiresAt)
                put("domain", credential.domain)
                put("uid", credential.uid)
                put("nickname", credential.nickname)
                put("enterpriseId", credential.enterpriseId ?: "")
            }
            target.writeText(json.toString(2))
            // The daemon runs as shell, so the file must be readable by it.
            target.setReadable(true, false)
        }
    }

    /** The credential currently in use, or null when that build has none. */
    fun active(): Credential? = load(activeRegion())

    /** Whether a build already holds a credential, for the switcher's labels. */
    fun has(region: Wire.Region): Boolean = load(region) != null

    /**
     * Whether the access token is inside the renewal margin. A token without a
     * known expiry is renewed rather than trusted.
     */
    fun needsRefresh(credential: Credential): Boolean {
        if (credential.expiresAt <= 0) return true
        val nowSeconds = System.currentTimeMillis() / 1000
        return nowSeconds + REFRESH_MARGIN_SECONDS >= credential.expiresAt
    }

    /**
     * Returns a credential safe to send, renewing first when it is inside the
     * margin. Concurrent callers wait on the same lock instead of starting a
     * second exchange.
     */
    @Synchronized
    fun resolve(renew: (Credential) -> Credential): Credential? {
        val region = activeRegion()
        val credential = load(region) ?: return null
        if (!needsRefresh(credential)) return credential
        return runCatching {
            val renewed = renew(credential)
            save(region, renewed)
            renewed
        }.getOrElse {
            // A failed renewal still returns the existing token when it has not
            // yet expired, so an unreachable refresh endpoint does not take a
            // working session down.
            android.util.Log.w("WBHub", "token refresh failed; using existing token", it)
            credential
        }
    }

    private fun defaultDomain(region: Wire.Region): String =
        if (region == Wire.Region.GLOBAL) "www.workbuddy.ai" else Wire.CN_CHAT_BASE

    private fun expiryFromJwt(token: String): Long {
        val parts = token.split(".")
        if (parts.size < 2) return 0L
        return runCatching {
            val bytes = android.util.Base64.decode(
                parts[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            JSONObject(String(bytes, Charsets.UTF_8)).optLong("exp", 0L)
        }.getOrDefault(0L)
    }

    companion object {
        /** Where the standalone daemon expects the active credential. */
        const val DAEMON_AUTH_PATH = "/data/local/tmp/wb-hub/wb-auth.json"

        private const val KEY_ACTIVE = "active_region"

        /** Renew this long before the token actually expires. */
        const val REFRESH_MARGIN_SECONDS = 5 * 60L
    }
}
