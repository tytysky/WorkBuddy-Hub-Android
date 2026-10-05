package com.wbhub.app.data

/** One account's outcome in a batch check-in. */
data class CheckinItem(
    val label: String,
    val ok: Boolean,
    val message: String,
)
