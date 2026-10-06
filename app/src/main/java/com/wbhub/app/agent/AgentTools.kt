package com.wbhub.app.agent

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The four tools the agent can call.
 *
 * Paths are resolved against a working directory rather than the whole file
 * system: a relative path lands inside it, and an absolute path is taken as
 * given. The shell runs through `sh`, escalated to root when that is enabled,
 * which is what lets the agent touch anything the device allows rather than
 * only the app's own sandbox.
 */
class AgentTools(
    private val context: Context,
    private val workDir: File,
    private val useRoot: Boolean,
) {

    /** Whatever the model asked for, plus the text that goes back to it. */
    data class Outcome(val text: String, val ok: Boolean)

    val definitions: List<Map<String, Any>> = listOf(
        tool(
            name = "read",
            description = "Read a text file and return its contents with line numbers. " +
                "Use offset and limit for large files.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "offset" to intProp("First line to return, 1-based. Defaults to 1."),
                "limit" to intProp("How many lines to return. Defaults to 2000."),
            ),
            required = listOf("path"),
        ),
        tool(
            name = "write",
            description = "Write text to a file, creating it and any missing parent " +
                "directories. Replaces the whole file.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "content" to stringProp("Full file contents to write."),
            ),
            required = listOf("path", "content"),
        ),
        tool(
            name = "edit",
            description = "Replace an exact string in a file. old_text must appear " +
                "exactly once unless replace_all is true.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "old_text" to stringProp("Exact text to find."),
                "new_text" to stringProp("Replacement text."),
                "replace_all" to boolProp("Replace every occurrence instead of requiring a unique match."),
            ),
            required = listOf("path", "old_text", "new_text"),
        ),
        tool(
            name = "bash",
            description = "Run a shell command and return its combined output. The " +
                "working directory is the agent's working directory. Root is used " +
                "when it is enabled.",
            properties = mapOf(
                "command" to stringProp("Shell command to run."),
                "timeout_seconds" to intProp("Kill the command after this many seconds. Defaults to 60."),
            ),
            required = listOf("command"),
        ),
    )

    /** Runs one tool call. Unknown names come back as a plain error, not a throw. */
    fun run(name: String, args: Map<String, Any?>): Outcome = when (name) {
        "read" -> read(args)
        "write" -> write(args)
        "edit" -> edit(args)
        "bash" -> bash(args)
        else -> Outcome("unknown tool: $name", false)
    }

    // ------------------------------------------------------------------ //
    // read
    // ------------------------------------------------------------------ //

    private fun read(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("read needs a path", false)
        if (!file.exists()) return Outcome("no such file: ${file.path}", false)
        if (file.isDirectory) return Outcome("${file.path} is a directory; use bash ls", false)
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: DEFAULT_READ_LINES).coerceIn(1, MAX_READ_LINES)

        return runCatching {
            val lines = file.readLines()
            val slice = lines.drop(offset - 1).take(limit)
            if (slice.isEmpty()) {
                Outcome("(no lines at offset $offset; the file has ${lines.size})", true)
            } else {
                // Line numbers are part of the contract: they are what makes a
                // following edit call able to name an exact location.
                val body = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
                val more = lines.size - (offset - 1) - slice.size
                val tail = if (more > 0) "\n… $more more lines" else ""
                Outcome(body + tail, true)
            }
        }.getOrElse { Outcome("read failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // write
    // ------------------------------------------------------------------ //

    private fun write(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("write needs a path", false)
        val content = args.str("content") ?: return Outcome("write needs content", false)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            Outcome("wrote ${content.length} chars to ${file.path}", true)
        }.getOrElse { Outcome("write failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // edit
    // ------------------------------------------------------------------ //

    private fun edit(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("edit needs a path", false)
        val oldText = args.str("old_text") ?: return Outcome("edit needs old_text", false)
        val newText = args.str("new_text") ?: return Outcome("edit needs new_text", false)
        val replaceAll = args["replace_all"] == true
        if (oldText.isEmpty()) return Outcome("old_text must not be empty", false)
        if (!file.exists()) return Outcome("no such file: ${file.path}", false)

        return runCatching {
            val original = file.readText()
            val occurrences = countOccurrences(original, oldText)
            when {
                occurrences == 0 -> Outcome("old_text not found in ${file.path}", false)
                // Requiring a unique match is what keeps an edit from silently
                // changing the wrong one of several identical fragments.
                occurrences > 1 && !replaceAll ->
                    Outcome("old_text appears $occurrences times; add more context or set replace_all", false)
                else -> {
                    val updated = if (replaceAll) {
                        original.replace(oldText, newText)
                    } else {
                        original.replaceFirst(oldText, newText)
                    }
                    file.writeText(updated)
                    val what = if (replaceAll) "$occurrences occurrences" else "1 occurrence"
                    Outcome("replaced $what in ${file.path}", true)
                }
            }
        }.getOrElse { Outcome("edit failed: ${it.message}", false) }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ------------------------------------------------------------------ //
    // bash
    // ------------------------------------------------------------------ //

    private fun bash(args: Map<String, Any?>): Outcome {
        val command = args.str("command") ?: return Outcome("bash needs a command", false)
        val timeout = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val argv = if (useRoot) {
            // -c takes the command as one argument; the shell then expands what
            // is inside it, which is what makes redirection and pipes work.
            arrayOf("su", "-c", command)
        } else {
            arrayOf("sh", "-c", command)
        }

        return runCatching {
            val process = ProcessBuilder(*argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Outcome("(timed out after ${timeout}s)\n$output", false)
            }
            val code = process.exitValue()
            val header = "exit code: $code"
            val limit = output.take(OUTPUT_LIMIT)
            val truncation = if (output.length > OUTPUT_LIMIT) "\n… output truncated" else ""
            Outcome("$header\n$limit$truncation", code == 0)
        }.getOrElse { Outcome("bash failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // Paths
    // ------------------------------------------------------------------ //

    /**
     * Resolves a tool path.
     *
     * A relative path is rooted at the working directory; an absolute one is
     * honoured as written, because the shell tools are expected to reach the
     * whole device once storage access has been granted.
     */
    private fun resolve(path: String?): File? {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        return if (file.isAbsolute) file else File(workDir, trimmed)
    }

    /** Where the shell starts, used by the UI to show the current directory. */
    companion object {
        private const val TAG = "WBHub"
        private const val DEFAULT_READ_LINES = 2000
        private const val MAX_READ_LINES = 20000
        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val OUTPUT_LIMIT = 30_000

        /** The default place for agent work: a visible folder on shared storage. */
        fun defaultWorkDir(): File =
            File(Environment.getExternalStorageDirectory(), "WBHub")

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, Any>,
            required: List<String>,
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to required,
                ),
            ),
        )

        private fun stringProp(description: String) = mapOf("type" to "string", "description" to description)
        private fun intProp(description: String) = mapOf("type" to "integer", "description" to description)
        private fun boolProp(description: String) = mapOf("type" to "boolean", "description" to description)
    }

    private fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
