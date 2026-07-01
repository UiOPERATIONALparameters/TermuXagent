package com.termuxagent.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termuxagent.data.chat.AssistantBlock
import com.termuxagent.data.chat.ToolCallStatus
import com.termuxagent.ui.theme.MonoTextStyle
import com.termuxagent.ui.theme.StatusError
import com.termuxagent.ui.theme.StatusSuccess
import com.termuxagent.ui.theme.StatusWarn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Composable
fun ToolCallCard(block: AssistantBlock.ToolCall, modifier: Modifier = Modifier) {
    var expanded by remember(block.id) { mutableStateOf(false) }

    val statusColor = when (block.status) {
        ToolCallStatus.STREAMING, ToolCallStatus.RUNNING -> StatusWarn
        ToolCallStatus.DONE -> StatusSuccess
        ToolCallStatus.FAILED -> StatusError
    }
    val statusLabel = when (block.status) {
        ToolCallStatus.STREAMING -> "preparing"
        ToolCallStatus.RUNNING -> "running"
        ToolCallStatus.DONE -> "done"
        ToolCallStatus.FAILED -> "failed"
    }
    val statusIcon = when (block.status) {
        ToolCallStatus.STREAMING, ToolCallStatus.RUNNING -> Icons.Rounded.PlayArrow
        ToolCallStatus.DONE -> Icons.Rounded.Check
        ToolCallStatus.FAILED -> Icons.Rounded.ErrorOutline
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (block.name == "shell") Icons.Rounded.Terminal else statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.name,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
            }
            if (block.argsRaw.isNotBlank()) {
                Spacer(Modifier.size(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = prettifyArgs(block.argsRaw).take(400) + if (block.argsRaw.length > 400) "…" else "",
                        style = MonoTextStyle.copy(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.padding(10.dp),
                        maxLines = 4
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded && block.result != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "Output",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(6.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = block.result.orEmpty(),
                            style = MonoTextStyle.copy(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

private fun prettifyArgs(raw: String): String {
    if (raw.isBlank()) return "(no args)"
    return runCatching {
        val el = Json.parseToJsonElement(raw)
        val obj = el as? JsonObject ?: return raw
        obj.entries.joinToString("\n") { (k, v) ->
            val s = v.toString().trim('"')
            "  $k = $s"
        }
    }.getOrDefault(raw)
}
