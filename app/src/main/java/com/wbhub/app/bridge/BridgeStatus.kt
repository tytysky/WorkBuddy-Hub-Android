package com.wbhub.app.bridge

import java.util.concurrent.atomic.AtomicInteger

/**
 * Live bridge state shared between the service, the server, and the overlay.
 *
 * The overlay exists to keep the process visible to the system, so it has to
 * show something the user can act on; this is that something.
 */
object BridgeStatus {

    @Volatile
    var running: Boolean = false

    @Volatile
    var port: Int = 0

    /** Requests served since the bridge started. */
    val requestCount = AtomicInteger(0)

    /** How many of those came from the network rather than this device. */
    val remoteCount = AtomicInteger(0)

    @Volatile
    var lastRequest: String = ""

    @Volatile
    var lastError: String = ""

    @Volatile
    var region: String = ""

    fun reset(port: Int) {
        running = true
        this.port = port
        requestCount.set(0)
        remoteCount.set(0)
        lastRequest = ""
        lastError = ""
    }

    fun recordRequest(summary: String) {
        requestCount.incrementAndGet()
        lastRequest = summary
    }

    /** Records that a call arrived over the network. */
    fun recordRemoteRequest() {
        remoteCount.incrementAndGet()
    }

    fun recordError(message: String) {
        lastError = message.take(120)
    }

    fun stopped() {
        running = false
        lastRequest = ""
    }
}
