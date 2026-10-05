package com.wbhub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wbhub.app.data.Login
import com.wbhub.app.bridge.CallRecord
import com.wbhub.app.bridge.UsageSummary
import java.util.Locale
import com.wbhub.app.proto.Wire

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CredentialScreen(
    state: HubState,
    onSwitchRealm: (Wire.Region) -> Unit,
    onLogin: (Wire.Region) -> Unit,
    onLogout: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val region = state.realm
    val regionLabel = realmName(region)
    val active = state.credential
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前账号 · $regionLabel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "查看详情",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        active == null -> Text(
                            "$regionLabel 还没有凭证，点下面的按钮登录。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> {
                            Text("账号：${active.nickname.ifBlank { active.uid.take(8) }}")
                            Text("有效期：${state.expiryText.ifBlank { "未知" }}")
                            Text(
                                "点本卡片查看 Token 等完整信息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // One control decides both which version to sign in to and which stored
        // credential the bridge serves; splitting them let the two disagree.
        item {
            Text("版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Wire.Region.entries.forEachIndexed { index, item ->
                    val stored = if (item == Wire.Region.GLOBAL) state.hasGlobalCredential else state.hasCnCredential
                    SegmentedButton(
                        selected = region == item,
                        onClick = { onSwitchRealm(item) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = Wire.Region.entries.size),
                    ) {
                        Text(if (stored) "${realmName(item)} ●" else realmName(item))
                    }
                }
            }
            Text(
                "● 表示该版本已保存凭证；切换后立即生效，无需重新登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        item {
            Button(onClick = { onLogin(region) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (active == null) "登录 $regionLabel" else "重新登录 $regionLabel")
            }
            val anyCredential = state.hasCnCredential || state.hasGlobalCredential
            if (anyCredential) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("退出登录")
                }
            }
        }

        item {
            Text(
                "说明：走官方 CLI 的 OAuth 流程，登录后可拿到 API token（网页 cookie 无法调用接口）。" +
                    "国内版与国际版是两套独立账号体系，可以各登录一个并随时切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Full credential detail, including both tokens, in a modal drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailDrawer(
    state: HubState,
    onDismiss: () -> Unit,
    onCopy: (label: String, value: String) -> Unit,
) {
    if (state.credential == null) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Text("尚无凭证", modifier = Modifier.padding(24.dp))
        }
        return
    }
    val cred = state.credential
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "凭证详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item { detailRow("版本", realmName(Wire.regionOf(cred.domain)), onCopy) }
            item { detailRow("昵称", cred.nickname.ifBlank { "(无)" }, onCopy) }
            item { detailRow("UID", cred.uid, onCopy) }
            item { detailRow("Domain", cred.domain, onCopy) }
            item { detailRow("来源", cred.source, onCopy) }
            item { detailRow("有效期", state.expiryText.ifBlank { "未知" }, onCopy) }
            item { detailRow("Access Token", cred.accessToken, onCopy) }
            item { detailRow("Refresh Token", cred.refreshToken.ifBlank { "(无)" }, onCopy) }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "点任意一行可复制其内容。Token 属于登录凭据，请勿分享。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One copyable key/value line in the detail drawer. */
@Composable
private fun detailRow(label: String, value: String, onCopy: (String, String) -> Unit) {
    Card(
        onClick = { onCopy(label, value) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BridgeScreen(
    state: HubState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onCopyEndpoint: () -> Unit,
    onShowHelp: () -> Unit,
    onCopyModel: (String) -> Unit,
    onRequestNotifications: () -> Unit,
    onRefreshModels: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestOverlay: () -> Unit,
    onOverlayOpacity: (Float) -> Unit,
    onOverlayLocked: (Boolean) -> Unit,
) {
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本地 API 平台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = state.bridgeRunning, onCheckedChange = { checked ->
                            if (checked) onStart(portText.toIntOrNull() ?: 8765) else onStop()
                        })
                    }
                    if (!state.batteryExempt) {
                        Text(
                            "建议同时关闭电池优化：点此设置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onRequestBatteryExemption),
                        )
                    }
                    if (!state.notificationsAllowed) {
                        Card(
                            onClick = onRequestNotifications,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text(
                                "通知权限未开启，服务可能被系统回收。点此开启。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit) },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (state.bridgeRunning) "运行中：http://127.0.0.1:${state.port}/v1" else "未运行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.bridgeRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (state.bridgeRunning) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = onCopyEndpoint) { Text("复制接入信息") }
                            FilledTonalButton(onClick = onStop) { Text("停止") }
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "后台常驻（悬浮窗）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (state.overlayAllowed) "已开启" else "未开启",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.overlayAllowed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        if (state.overlayAllowed) {
                            "服务运行时会在屏幕边缘显示状态面板，让应用保持可见，系统不会在后台冻结它。面板可拖动，点一下可收起。"
                        } else {
                            "开启后会在屏幕上显示一个小状态面板，让应用保持可见，避免后台被系统冻结。无需 root，也无需其它工具。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!state.overlayAllowed) {
                        Button(onClick = onRequestOverlay, modifier = Modifier.fillMaxWidth()) {
                            Text("开启悬浮窗")
                        }
                    } else {
                        Text(
                            "透明度 ${(state.overlayOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = state.overlayOpacity,
                            onValueChange = onOverlayOpacity,
                            valueRange = 0.15f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("固定位置", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "开启后悬浮窗不能被拖动，避免误触。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.overlayLocked,
                                onCheckedChange = onOverlayLocked,
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "接入方式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onShowHelp) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = "接入帮助",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text("baseUrl: http://127.0.0.1:${state.port}/v1")
                    Text("apiKey: ${state.secret}")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("可用模型（点模型名可复制）", Modifier.weight(1f))
                FilledTonalButton(
                    onClick = onRefreshModels,
                    enabled = state.loading != Loading.MODELS,
                ) {
                    if (state.loading == Loading.MODELS) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("获取中")
                    } else {
                        Text("重新获取")
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth()) {
                ModelList(
                    models = state.models,
                    onCopyModel = onCopyModel,
                    modifier = Modifier.heightIn(max = 620.dp),
                )
                if (state.loading == Loading.MODELS && state.models.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ContainedLoadingIndicator()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RewardsScreen(state: HubState, onCheckin: () -> Unit, onRefreshBalance: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(12.dp)) {
                    Text("每日签到", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = onCheckin,
                        enabled = state.loading != Loading.CHECKIN,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.loading == Loading.CHECKIN) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("签到中…")
                        } else {
                            Text("签到领积分")
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "余额",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = onRefreshBalance,
                            enabled = state.loading != Loading.BALANCE,
                        ) {
                            if (state.loading == Loading.BALANCE) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("刷新中")
                            } else {
                                Text("刷新")
                            }
                        }
                    }
                    val balance = state.balance
                    when {
                        state.loading == Loading.BALANCE && balance == null -> {
                            CircularWavyProgressIndicator()
                        }
                        balance == null -> Text("点“刷新”查询余额")
                        balance.accounts.isEmpty() -> Text("暂无套餐数据")
                        else -> {
                            Text(
                                "可用合计：${balance.total.toLong()}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            balance.accounts.forEach { acc ->
                                Text(
                                    "${acc.name}：${acc.remain.toLong()} / ${acc.size.toLong()}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (balance.cycleEnd.isNotBlank()) {
                                Text(
                                    "周期截止：${balance.cycleEnd}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Modal shown when a check-in attempt finishes. */
@Composable
fun CheckinDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("签到结果") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("好") }
        },
    )
}

/** Integration guide shown from the help icon. */
@Composable
fun HelpDialog(state: HubState, onDismiss: () -> Unit, onCopyEndpoint: () -> Unit) {
    val endpoint = "http://127.0.0.1:${state.port}/v1"
    val snippet = """
        {
          "providers": {
            "workbuddy": {
              "baseUrl": "$endpoint",
              "api": "openai-completions",
              "apiKey": "${state.secret}",
              "models": [
                { "id": "hy3" }
              ]
            }
          }
        }
    """.trimIndent()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何接入") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("服务提供 OpenAI 兼容接口，任意支持 openai-completions 的客户端均可直接使用。")
                }
                item { Text("1. 在本机启动下方开关"); Text("2. 客户端填入以下地址与密钥") }
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("baseUrl: $endpoint", style = MaterialTheme.typography.bodySmall)
                            Text("apiKey: ${state.secret}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("3. 模型名称见下方列表，点模型名即可复制") }
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text("models.json 示例", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(snippet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("提示：倍率 x0.00 的模型为免费，列表已按倍率从低到高排序。")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopyEndpoint() }) { Text("复制地址") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}


/** Display name for a build. */
internal fun realmName(region: Wire.Region): String =
    if (region == Wire.Region.GLOBAL) "国际版" else "国内版"


/** Confirms signing out, which discards every stored credential. */
@Composable
fun LogoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("退出登录") },
        text = { Text("将清除已保存的登录凭证（国内版与国际版），需要重新登录才能继续使用。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Call history with the usage totals derived from it. */
@Composable
fun CallsScreen(state: HubState, onClear: () -> Unit, onRefresh: () -> Unit) {
    // Calls arrive from the service without the UI being told, so the page
    // reloads while it is on screen rather than only when it is opened.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            kotlinx.coroutines.delay(2000L)
        }
    }
    val summary = UsageSummary.of(state.calls)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "用量统计",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.calls.isNotEmpty()) {
                            TextButton(onClick = onClear) { Text("清空") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("成功调用", summary.calls.toString())
                        StatCell("失败", summary.failures.toString())
                        StatCell("消耗积分", formatCredits(summary.credits))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatCell("输入 Tokens", summary.promptTokens.toString())
                        StatCell("输出 Tokens", summary.completionTokens.toString())
                        StatCell("合计 Tokens", summary.totalTokens.toString())
                    }
                }
            }
        }

        if (state.calls.isEmpty()) {
            item {
                Text(
                    "还没有调用记录。通过本地 API 平台发起对话后，这里会显示每次调用的模型、Tokens 与积分消耗。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                Text(
                    "最近 ${state.calls.size} 次调用（新→旧）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.calls.reversed(), key = { it.timestamp.toString() + it.model }) { record ->
                CallRow(record)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CallRow(record: CallRecord) {
    val failed = record.outcome == CallRecord.Outcome.FAILED
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.model.ifEmpty { "(未知模型)" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    record.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                Text(
                    "失败 · ${record.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "${record.promptTokens} 入 / ${record.completionTokens} 出 · 消耗 ${formatCredits(record.credits)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Credit figures are multipliers, so trailing zeros are trimmed. */
private fun formatCredits(value: Double): String {
    if (value == 0.0) return "0"
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}
