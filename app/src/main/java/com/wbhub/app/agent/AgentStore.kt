package com.wbhub.app.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for the agent page.
 *
 * Two things survive a restart: the settings that shape a request, and the
 * conversations themselves. Settings live in shared preferences because they
 * are a handful of scalars; conversations are files because they grow and are
 * one per session rather than one per key.
 */
class AgentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "agent-sessions").apply { mkdirs() }

    /** One saved conversation. */
    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<AgentClient.Message>,
        /** The visible transcript, so a reopened session renders as it did. */
        val entries: List<StoredEntry>,
    )

    /** A transcript line as stored; the role is kept by name for stability. */
    data class StoredEntry(val role: String, val text: String)

    // ------------------------------------------------------------------ //
    // Settings
    // ------------------------------------------------------------------ //

    fun modelId(): String = prefs.getString(KEY_MODEL, "").orEmpty()

    fun setModelId(value: String) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun effort(): String = prefs.getString(KEY_EFFORT, "").orEmpty()

    fun setEffort(value: String) = prefs.edit().putString(KEY_EFFORT, value).apply()

    fun contextWindow(): Int = prefs.getInt(KEY_CONTEXT, 0)

    fun setContextWindow(value: Int) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    fun exposeRoot(): Boolean = prefs.getBoolean(KEY_ROOT, false)

    fun setExposeRoot(value: Boolean) = prefs.edit().putBoolean(KEY_ROOT, value).apply()

    fun approval(): ApprovalMode = ApprovalMode.fromName(prefs.getString(KEY_APPROVAL, null))

    fun setApproval(value: ApprovalMode) = prefs.edit().putString(KEY_APPROVAL, value.name).apply()

    fun workDir(): String = prefs.getString(KEY_WORKDIR, "").orEmpty()

    fun setWorkDir(value: String) = prefs.edit().putString(KEY_WORKDIR, value).apply()

    fun activeSessionId(): String = prefs.getString(KEY_ACTIVE_SESSION, "").orEmpty()

    fun setActiveSessionId(value: String) = prefs.edit().putString(KEY_ACTIVE_SESSION, value).apply()

    // ------------------------------------------------------------------ //
    // Sessions
    // ------------------------------------------------------------------ //

    /** Every session, newest first. */
    fun sessions(): List<Session> = dir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { parse(file.readText(), file.nameWithoutExtension) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()

    fun load(id: String): Session? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { parse(file.readText(), id) }.getOrNull()
    }

    fun save(session: Session) {
        val document = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            put("entries", JSONArray().apply {
                session.entries.forEach { entry ->
                    put(JSONObject().apply { put("role", entry.role); put("text", entry.text) })
                }
            })
            put("messages", JSONArray().apply {
                session.messages.forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        if (message.toolCallId.isNotEmpty()) put("toolCallId", message.toolCallId)
                        message.toolCalls?.let { put("toolCalls", it) }
                    })
                }
            })
        }
        runCatching { File(dir, "${session.id}.json").writeText(document.toString()) }
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
    }

    /**
     * A title for the session list.
     *
     * The first user line is the most recognisable name a conversation has, so
     * it is used as-is and clipped rather than asking the model to summarise.
     */
    fun titleFor(prompt: String): String =
        prompt.lineSequence().firstOrNull()?.trim().orEmpty().take(40).ifEmpty { "新会话" }

    private fun parse(text: String, fallbackId: String): Session {
        val root = JSONObject(text)
        val entries = root.optJSONArray("entries")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                StoredEntry(row.optString("role"), row.optString("text"))
            }
        }.orEmpty()
        val messages = root.optJSONArray("messages")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                AgentClient.Message(
                    role = row.optString("role"),
                    content = row.optString("content"),
                    toolCalls = row.optJSONArray("toolCalls"),
                    toolCallId = row.optString("toolCallId"),
                )
            }
        }.orEmpty()
        return Session(
            id = root.optString("id").ifEmpty { fallbackId },
            title = root.optString("title").ifEmpty { "会话" },
            updatedAt = root.optLong("updatedAt"),
            messages = messages,
            entries = entries,
        )
    }

    private companion object {
        const val PREFS = "wb-agent"
        const val KEY_MODEL = "model_id"
        const val KEY_EFFORT = "effort"
        const val KEY_CONTEXT = "context_window"
        const val KEY_ROOT = "expose_root"
        const val KEY_APPROVAL = "approval"
        const val KEY_WORKDIR = "work_dir"
        const val KEY_ACTIVE_SESSION = "active_session"
    }
}
