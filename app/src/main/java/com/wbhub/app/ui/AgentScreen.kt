package com.wbhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wbhub.app.proto.HubModel

/**
 * The built-in agent: a prompt box, the transcript, and the two settings that
 * change how the model answers.
 *
 * The model list comes from the account's own catalogue, so only models the
 * credential can actually call are offered. The effort row appears only for
 * models that declare supported levels, because sending one to a model that
 * does not take it is rejected upstream.
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
    onToggleRoot: (Boolean) -> Unit,
    onRequestStorage: () -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Follow the newest line as it streams in, which is the only way the tail
    // of a long answer stays visible without scrolling by hand.
    LaunchedEffect(agent.entries.size, agent.entries.lastOrNull()?.text?.length) {
        if (agent.entries.isNotEmpty()) listState.animateScrollToItem(agent.entries.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        SettingsCard(
            agent = agent,
            models = models,
            onSelectModel = onSelectModel,
            onSelectEffort = onSelectEffort,
            onToggleRoot = onToggleRoot,
            onRequestStorage = onRequestStorage,
            onClear = onClear,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (agent.entries.isEmpty()) {
                item { EmptyHint(agent) }
            }
            itemsIndexed(agent.entries) { _, entry -> EntryRow(entry) }
        }
        Composer(agent = agent, onInputChange = onInputChange, onSend = onSend)
    }
}

/** The model and effort pickers, plus the execution switches. */
@Composable
private fun SettingsCard(
    agent: AgentState,
    models: List<HubModel>,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onRequestStorage: () -> Unit,
    onClear: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == agent.modelId }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Picker(
                    label = "模型",
                    value = selected?.name ?: agent.modelId.ifBlank { "未选择" },
                    options = models.map { it.id to it.name },
                    modifier = Modifier.weight(1f),
                    onSelect = onSelectModel,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClear) { Text("清空") }
            }

            // Only shown when the chosen model declares levels; an unrelated
            // effort value would be refused rather than ignored.
            if (selected != null && selected.supportsEffort) {
                EffortRow(
                    model = selected,
                    effort = agent.effort,
                    onSelectEffort = onSelectEffort,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("使用 root 执行", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (agent.useRoot) "命令以 root 身份运行" else "命令以应用身份运行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = agent.useRoot, onCheckedChange = onToggleRoot)
            }

            if (!agent.storageGranted) {
                FilledTonalButton(onClick = onRequestStorage, modifier = Modifier.fillMaxWidth()) {
                    Text("授予所有文件访问权限")
                }
            }

            Text(
                "工作目录：${agent.workDir}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Effort choices, with an off switch when the model allows disabling thinking. */
@Composable
private fun EffortRow(model: HubModel, effort: String, onSelectEffort: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("思考强度", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (model.canDisableThinking) {
                FilterChip(
                    selected = effort == EFFORT_OFF,
                    onClick = { onSelectEffort(EFFORT_OFF) },
                    label = { Text("关闭") },
                )
            }
            model.efforts.forEach { level ->
                FilterChip(
                    selected = effort == level,
                    onClick = { onSelectEffort(level) },
                    label = { Text(level) },
                )
            }
        }
    }
}

/** A dropdown disguised as a labelled row. */
@Composable
private fun Picker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(value, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

/** One transcript line, styled by who produced it. */
@Composable
private fun EntryRow(entry: AgentEntry) {
    val scheme = MaterialTheme.colorScheme
    val (title, color) = when (entry.role) {
        AgentEntry.Role.USER -> "你" to scheme.primary
        AgentEntry.Role.ASSISTANT -> "Agent" to scheme.onSurface
        AgentEntry.Role.REASONING -> "思考" to scheme.onSurfaceVariant
        AgentEntry.Role.TOOL -> "工具" to scheme.tertiary
        AgentEntry.Role.ERROR -> "错误" to scheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
            Text(
                entry.text,
                style = if (entry.role == AgentEntry.Role.TOOL || entry.role == AgentEntry.Role.ERROR) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                // Reasons and tool output are read verbatim, so a monospaced face
                // keeps their alignment meaningful.
                fontFamily = if (entry.role == AgentEntry.Role.TOOL || entry.role == AgentEntry.Role.REASONING) {
                    FontFamily.Monospace
                } else {
                    null
                },
            )
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

/** The prompt box and its send button. */
@Composable
private fun Composer(agent: AgentState, onInputChange: (String) -> Unit, onSend: () -> Unit) {
    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = agent.input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 160.dp),
            placeholder = { Text("让 Agent 做什么…") },
            maxLines = 6,
        )
        Button(
            onClick = onSend,
            enabled = !agent.running && agent.input.isNotBlank() && agent.modelId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (agent.running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("执行中…")
            } else {
                Text("发送")
            }
        }
    }
}

/** Sentinel for "thinking off" that is distinct from an unset effort. */
const val EFFORT_OFF = "off"
