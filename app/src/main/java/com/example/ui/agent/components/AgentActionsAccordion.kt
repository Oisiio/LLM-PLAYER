package com.example.ui.agent.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.AgentStep
import com.example.agent.ToolExecutionResult

@Composable
fun AgentActionsAccordion(
    steps: List<AgentStep>,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false
) {
    if (steps.isEmpty()) return

    var isExpanded by remember { mutableStateOf(initialExpanded) }
    val stepCount = steps.size
    val hasErrors = steps.any { it.toolResult is ToolExecutionResult.Error }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (hasErrors) MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { isExpanded = !isExpanded }
            .testTag("agent_actions_accordion")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasErrors) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = if (isExpanded) "▼ Agent actions · $stepCount step${if (stepCount > 1) "s" else ""}"
                               else "▶ Agent actions · $stepCount step${if (stepCount > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "折りたたむ" else "展開する",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded content (Tool execution details only, strictly NO thinking)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    steps.forEachIndexed { index, step ->
                        val toolCall = step.toolCall
                        val toolResult = step.toolResult
                        val isSuccess = toolResult is ToolExecutionResult.Success

                        val toolName = toolCall?.toolName ?: "tool"
                        val toolIcon = when (toolName.lowercase()) {
                            "datetime" -> Icons.Default.CalendarToday
                            "calculator" -> Icons.Default.Calculate
                            else -> Icons.Default.Check
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // Tool header line
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Icon(
                                        imageVector = toolIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = formatToolDisplayName(toolName),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    val paramSummary = formatArgumentsSummary(toolCall?.arguments ?: emptyMap())
                                    if (paramSummary.isNotBlank()) {
                                        Text(
                                            text = "($paramSummary)",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                // Tool result line
                                val resultText = when (toolResult) {
                                    is ToolExecutionResult.Success -> toolResult.output
                                    is ToolExecutionResult.Error -> toolResult.errorMessage
                                    null -> "実行中…"
                                }

                                Text(
                                    text = "→ $resultText",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = if (isSuccess) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(start = 20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatToolDisplayName(toolName: String): String {
    return when (toolName.lowercase()) {
        "datetime" -> "datetime"
        "calculator" -> "calculator"
        else -> toolName
    }
}

private fun formatArgumentsSummary(args: Map<String, String>): String {
    if (args.isEmpty()) return ""
    return when {
        args.containsKey("expression") -> args["expression"] ?: ""
        args.containsKey("action") -> {
            val action = args["action"] ?: ""
            val rest = args.filterKeys { it != "action" }.values.joinToString(", ")
            if (rest.isNotBlank()) "$action: $rest" else action
        }
        else -> args.entries.joinToString(", ") { "${it.key}: ${it.value}" }
    }
}
