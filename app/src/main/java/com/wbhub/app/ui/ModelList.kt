package com.wbhub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wbhub.app.proto.HubModel

/**
 * Vendor mark drawn from the initial rather than fetched artwork: the upstream
 * catalogue carries no logo URL, and a lettered badge stays crisp and available
 * offline.
 */
@Composable
fun VendorBadge(model: HubModel, modifier: Modifier = Modifier) {
    val colors = vendorColors(model)
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.background)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = model.vendor.take(1).ifEmpty { model.name.take(1) },
            color = colors.foreground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class VendorColors(val background: Color, val foreground: Color, val border: Color)

@Composable
private fun vendorColors(model: HubModel): VendorColors {
    val scheme = MaterialTheme.colorScheme
    return when (model.vendor.lowercase()) {
        "混元" -> VendorColors(Color(0xFF0052D9), Color.White, Color(0xFF003FB3))
        "智谱" -> VendorColors(Color(0xFF12B886), Color.White, Color(0xFF0B9A70))
        "DeepSeek" -> VendorColors(Color(0xFF4D6BFE), Color.White, Color(0xFF3452C9))
        "kimi" -> VendorColors(Color(0xFF7C3AED), Color.White, Color(0xFF5B21B6))
        "minimax" -> VendorColors(Color(0xFFE8590C), Color.White, Color(0xFFBF4909))
        else -> VendorColors(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
}

/** The catalogue the bridge exposes, cheapest first. */
@Composable
fun ModelList(
    models: List<HubModel>,
    onCopyModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (models.isEmpty()) {
        Text(
            "暂无模型数据，启动服务后会自动拉取。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val sorted = models.sortedWith(compareBy({ it.sortKey }, { it.id }))
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "共 ${sorted.size} 个模型，按倍率从低到高",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(sorted, key = { it.id }) { model ->
            ModelRow(model, onCopy = { onCopyModel(model.id) })
        }
    }
}

@Composable
private fun ModelRow(model: HubModel, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VendorBadge(model)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), Arrangement.spacedBy(3.dp)) {
                Text(
                    text = model.name.ifBlank { model.id },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RateChip(model)
                if (model.supportsImages) {
                    Text(
                        "图片",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (model.badges.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                model.badges.forEach { badge ->
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(badge, style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RateChip(model: HubModel) {
    val label = if (model.isFree) "免费" else model.rate
    val container = if (model.isFree) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (model.isFree) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}
