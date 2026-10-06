package com.wbhub.app.agent

/**
 * How a tool call is authorised before it runs.
 *
 * The three modes trade attention against friction: reading a file and running
 * an arbitrary shell command are not the same risk, so a single "allow tools"
 * switch would either be useless or be clicked through without reading.
 */
enum class ApprovalMode(val label: String, val description: String) {
    /**
     * Writes and shell commands run without asking, reads never ask. The model
     * is trusted to stay inside what the prompt asked for.
     */
    ALLOW_ALL("允许所有", "不询问，直接执行"),

    /**
     * Read-only calls run freely; anything that writes or executes asks first.
     * The split follows what the call can undo rather than how it is spelled.
     */
    AUTO("自动判断", "读取自动放行，写入与命令需确认"),

    /** Every call asks, including reads. */
    ASK_EVERY("每次询问", "每次工具调用都需确认"),
    ;

    companion object {
        fun fromName(value: String?): ApprovalMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * Classification of a tool call, used by [ApprovalMode.AUTO].
 *
 * A read is the only kind that changes nothing on disk and cannot run a
 * program, so it is the only kind auto-approved.
 */
object ToolPolicy {

    /** Whether the call only reads state and cannot change the device. */
    fun isReadOnly(name: String): Boolean = name == "read"

    /** Whether the call needs the user's answer under [mode]. */
    fun needsApproval(mode: ApprovalMode, name: String): Boolean = when (mode) {
        ApprovalMode.ALLOW_ALL -> false
        ApprovalMode.ASK_EVERY -> true
        ApprovalMode.AUTO -> !isReadOnly(name)
    }

    /**
     * One-line description of what a call will do, shown in the prompt.
     *
     * The arguments decide the risk, so the summary names them rather than the
     * tool: "bash: rm -rf /sdcard" is readable, "bash" is not.
     */
    fun summarize(name: String, args: Map<String, Any?>): String = when (name) {
        "read" -> "读取 ${args["path"] ?: "?"}"
        "write" -> "写入 ${args["path"] ?: "?"}（${(args["content"] as? String)?.length ?: 0} 字符）"
        "edit" -> "编辑 ${args["path"] ?: "?"}"
        "bash" -> "执行命令：${args["command"] ?: "?"}"
        else -> "$name ${args.values.joinToString(" ")}"
    }
}
