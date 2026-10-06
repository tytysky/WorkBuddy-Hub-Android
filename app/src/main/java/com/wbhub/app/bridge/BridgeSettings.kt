package com.wbhub.app.bridge

import android.content.Context

/**
 * User preferences for the status panel.
 *
 * Kept in shared preferences rather than the credential file: these are
 * presentation choices that must survive a restart, and they are not secrets.
 */
object BridgeSettings {

    private const val PREFS = "wb-overlay"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_LOCKED = "locked"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_LAN = "lan_enabled"

    /**
     * The key local clients present. Fixed: it is written into configs on this
     * device and there is nothing to gain from rotating it.
     */
    const val DEFAULT_API_KEY = "wb-local"

    /**
     * The key peers on the local network must present.
     *
     * Separate from the local one on purpose: a key that travels over the
     * network is the one worth being able to change, and keeping it distinct
     * means revoking peer access does not disturb this device's own clients.
     */
    fun lanKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LAN_KEY

    fun setLanKey(context: Context, value: String) {
        val trimmed = value.trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, trimmed).apply()
    }

    const val DEFAULT_LAN_KEY = "wb-lan"

    /**
     * Whether the endpoint is reachable from the local network.
     *
     * Off by default: the endpoint holds a credential, and loopback is the
     * only binding that needs no further trust decision.
     */
    fun lanEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAN, false)

    fun setLanEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LAN, value).apply()
    }

    fun opacity(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_OPACITY, 0.94f)

    fun setOpacity(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_OPACITY, value.coerceIn(0.15f, 1f)).apply()
    }

    fun locked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCKED, false)

    fun setLocked(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCKED, value).apply()
    }

    fun position(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_X, -1) to prefs.getInt(KEY_Y, -1)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}

/**
 * Reads the address a peer on the local network should use.
 *
 * Only the Wi-Fi interface counts, and only its IPv4 address is reported. A
 * mobile-data address belongs to the carrier's internal network and looks like a
 * private one, and a link-local IPv6 address needs a scope suffix that clients
 * read inconsistently, so neither is worth handing out.
 */
object LanAddresses {

    fun current(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface ->
                runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) && isWifi(iface.name)
            }
            .flatMap { iface ->
                iface.interfaceAddresses
                    // An IPv4 literal is four dotted octets; anything else here
                    // is IPv6, which a peer would have to be told how to scope.
                    .mapNotNull { it.address?.hostAddress }
                    .filter { it.count { c -> c == '.' } == 3 }
            }
            .filter { it.isNotEmpty() && !it.startsWith("127.") }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * Interface names Android uses for Wi-Fi.
     *
     * A hotspot shows up under an `ap` or `swlan` name, and those are worth
     * reporting too: the device is then the network a peer joins.
     */
    private fun isWifi(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
}
