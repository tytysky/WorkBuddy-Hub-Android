package com.wbhub.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.proto.HubModel

/**
 * The built-in agent: a transcript, a prompt box, and the settings that shape a
 * request.
 *
 * Reasoning and tool traffic are collapsed by default. They are the bulk of a
 * long run and are only interesting when something looks wrong, so the visible
 * transcript stays close to the conversation itself until one is opened.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentScreen(
    agent: AgentState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectContext: (Int) -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onSelectApproval: (ApprovalMode) -> Unit,
    onRequestStorage: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onAnswerApproval: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    // Follow the newest line as it streams in, which is the only way the tail
    // of a long answer stays visible without scrolling by hand.
    LaunchedEffect(agent.entries.size, agent.entries.lastOrNull()?.text?.length) {
        if (agent.entries.isNotEmpty()) listState.animateScrollToItem(agent.entries.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (agent.entries.isEmpty()) {
                item { EmptyHint(agent) }
            }
            itemsIndexed(agent.entries) { _, entry -> EntryRow(entry) }
        }
        Composer(
            agent = agent,
            models = models,
            onInputChange = onInputChange,
            onSend = onSend,
            onSelectModel = onSelectModel,
            onSelectEffort = onSelectEffort,
            onSelectContext = onSelectContext,
            onToggleRoot = onToggleRoot,
            onSelectApproval = onSelectApproval,
            onRequestStorage = onRequestStorage,
            onNewSession = onNewSession,
            onOpenSession = onOpenSession,
            onDeleteSession = onDeleteSession,
        )
    }

    // The prompt sits above everything so an answer is never hidden behind the
    // transcript the model is still appending to.
    agent.pendingApproval?.let { summary ->
        ApprovalDialog(summary = summary, onAnswer = onAnswerApproval)
    }
}

/**
 * The prompt box with everything that shapes a request attached to it.
 *
 * The settings sit in the same block as the input rather than above the
 * transcript, so the conversation keeps the height instead of a stack of
 * controls that are only consulted once in a while.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    agent: AgentState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectContext: (Int) -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onSelectApproval: (ApprovalMode) -> Unit,
    onRequestStorage: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
) {
    val selected = models.firstOrNull { it.id == agent.modelId }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One scrolling row keeps the chips from wrapping into a second line on
        // a narrow screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipPicker(
                value = agent.sessionTitle.ifBlank { "新会话" },
                options = buildList<Pair<String, String>> {
                    // A fresh conversation is the action taken most often, so it
                    // belongs at the top of the list rather than beside it.
                    add(NEW_SESSION to "＋ 新会话")
                    agent.sessions.forEach { add(it.id to it.title) }
                },
                onSelect = { picked ->
                    if (picked == NEW_SESSION) onNewSession() else onOpenSession(picked)
                },
                onDelete = onDeleteSession,
            )
            ChipPicker(
                value = selected?.name ?: agent.modelId.ifBlank { "选择模型" },
                options = models.map { it.id to it.name },
                onSelect = onSelectModel,
            )
            if (selected != null && selected.supportsEffort) {
                val effortOptions = buildList {
                    // "off" is only offered when the model allows it: an
                    // unsupported value is refused rather than ignored.
                    if (selected.canDisableThinking) add(EFFORT_OFF to "不思考")
                    selected.efforts.forEach { add(it to it) }
                }
                ChipPicker(
                    value = if (agent.effort.isBlank() || agent.effort == EFFORT_OFF) "不思考" else agent.effort,
                    options = effortOptions,
                    onSelect = onSelectEffort,
                )
            }
            if (selected != null && selected.hasContextChoice) {
                ChipPicker(
                    value = formatContext(agent.contextWindow.takeIf { it > 0 } ?: selected.contextWindow),
                    options = selected.contextOptions.map { it.toString() to formatContext(it) },
                    onSelect = { picked -> onSelectContext(picked.toIntOrNull() ?: 0) },
                )
            }
            if (agent.rootAvailable) {
                // Only offered when root was actually granted; a switch that
                // silently does nothing is worse than no switch.
                AssistChip(
                    onClick = { onToggleRoot(!agent.exposeRoot) },
                    label = { Text(if (agent.exposeRoot) "root 已暴露" else "不给 root") },
                )
            }
            ChipPicker(
                value = agent.approval.label,
                options = ApprovalMode.entries.map { it.name to it.label },
                onSelect = { picked ->
                    ApprovalMode.entries.firstOrNull { it.name == picked }?.let(onSelectApproval)
                },
            )
        }

        Text(
            agent.approval.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!agent.storageGranted) {
            Text(
                "未授予所有文件访问权限，点击此处设置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onRequestStorage),
            )
        }

        // Input and send share one row so the button reads as part of the box.
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = agent.input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp, max = 140.dp),
                placeholder = { Text("让 Agent 做什么…") },
                maxLines = 6,
            )
            Spacer(Modifier.width(6.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = !agent.running && agent.input.isNotBlank() && agent.modelId.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                if (agent.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/**
 * A compact dropdown rendered as a chip.
 *
 * A labelled row would take two lines each, and there are several of these
 * above a small prompt box.
 */
@Composable
private fun ChipPicker(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(value, maxLines = 1) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                    trailingIcon = if (onDelete != null && id != NEW_SESSION) {
                        {
                            IconButton(onClick = { onDelete(id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除会话")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** One transcript line, styled by who produced it. */
@Composable
private fun EntryRow(entry: AgentEntry) {
    when (entry.role) {
        // Reasoning and tool traffic are the verbose parts of a run, so they are
        // collapsed into a single line that opens on demand.
        AgentEntry.Role.REASONING,
        AgentEntry.Role.TOOL,
        -> CollapsibleRow(entry)

        else -> MessageRow(entry)
    }
}

/** A user or assistant line, always visible. */
@Composable
private fun MessageRow(entry: AgentEntry) {
    val scheme = MaterialTheme.colorScheme
    val (title, color) = when (entry.role) {
        AgentEntry.Role.USER -> "你" to scheme.primary
        AgentEntry.Role.ASSISTANT -> "Agent" to scheme.onSurface
        AgentEntry.Role.ERROR -> "错误" to scheme.error
        else -> "" to scheme.onSurface
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), Arrangement.spacedBy(4.dp)) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
            }
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A reasoning or tool line, folded to one row until it is tapped. */
@Composable
private fun CollapsibleRow(entry: AgentEntry) {
    var expanded by remember(entry.text) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val isReasoning = entry.role == AgentEntry.Role.REASONING
    val label = if (isReasoning) "思考" else "工具"
    val color = if (isReasoning) scheme.onSurfaceVariant else scheme.tertiary

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // The first line is the summary shown while folded, which is
                    // enough to tell what happened without opening it.
                    entry.text.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** Shown before the first prompt. */
@Composable
private fun EmptyHint(agent: AgentState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("内置 Agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "可以直接读写文件、执行命令。命令默认以应用身份运行，" +
                "开启 root 后可访问整个设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!agent.storageGranted) {
            Text(
                "尚未授予所有文件访问权限，外部目录可能无法访问。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Renders a context length the way the model catalogue states it. */
private fun formatContext(tokens: Int): String = when {
    tokens <= 0 -> "默认"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}

/**
 * Asks whether one tool call may proceed.
 *
 * Dismissing counts as a refusal: a dialog that vanishes because the user
 * tapped outside should not authorise a shell command.
 */
@Composable
private fun ApprovalDialog(summary: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("允许此操作？") },
        text = {
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("拒绝") } },
    )
}

/** Sentinel for "thinking off" that is distinct from an unset effort. */
const val EFFORT_OFF = "off"

/** Sentinel id for "start a new conversation" in the session picker. */
const val NEW_SESSION = "__new__"
