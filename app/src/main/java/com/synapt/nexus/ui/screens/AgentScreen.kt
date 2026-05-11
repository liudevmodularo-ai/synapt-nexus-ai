package com.synapt.nexus.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.*

private val NexusDark   = Color(0xFF0A0E1A)
private val NexusCard   = Color(0xFF111827)
private val NexusAccent = Color(0xFF6C63FF)
private val NexusCyan   = Color(0xFF00D4FF)
private val NexusGreen  = Color(0xFF00E676)
private val NexusYellow = Color(0xFFFFD600)
private val NexusBorder = Color(0xFF1F2937)
private val AiBubble    = Color(0xFF0F1628)

@Composable
fun AgentScreen(
    uiState: AgentUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(NexusDark)) {
        AgentTopBar(uiState.meshNodeCount, uiState.activeNodeName, uiState.currentTokensPerSecond, uiState.isGenerating, onClear)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { msg ->
                AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 2 }) {
                    MessageBubble(msg)
                }
            }
            if (uiState.isGenerating && uiState.messages.lastOrNull()?.content?.isEmpty() == true) {
                item { TypingIndicator() }
            }
        }

        MeshStatusBar(uiState.meshNodeCount, uiState.totalMeshTps, uiState.meshNodeCount > 0)
        AgentInputBar(uiState.inputText, uiState.isGenerating, onInputChange, onSend, onStop)
    }
}

@Composable
fun AgentTopBar(meshNodes: Int, activeNode: String, tps: Float, isGenerating: Boolean, onClear: () -> Unit) {
    Surface(color = NexusCard) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Brush.radialGradient(listOf(NexusAccent, NexusCyan))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Psychology, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("SYNAPT AGENT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(NexusGreen))
                    Spacer(Modifier.width(4.dp))
                    val status = when {
                        isGenerating && activeNode != "local" -> "processando em $activeNode"
                        isGenerating -> "gerando localmente"
                        meshNodes > 0 -> "mesh: $meshNodes nós • ${tps.toInt()} t/s"
                        else -> "modo local"
                    }
                    Text(status, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.DeleteOutline, "Limpar", tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) = when (message.role) {
    ChatMessage.Role.SYSTEM    -> SystemMsg(message.content)
    ChatMessage.Role.USER      -> UserMsg(message.content)
    ChatMessage.Role.ASSISTANT -> AssistantMsg(message)
}

@Composable
fun SystemMsg(content: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(content, color = Color.Gray, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(NexusBorder, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun UserMsg(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            color = NexusAccent.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, NexusAccent.copy(alpha = 0.4f)),
            modifier = Modifier.widthIn(max = 280.dp)) {
            Text(content, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
fun AssistantMsg(message: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.radialGradient(listOf(NexusAccent, NexusCyan))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp), color = AiBubble,
                border = BorderStroke(1.dp, NexusBorder), modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.content.isNotEmpty()) Text(message.content, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
                    if (message.isStreaming) BlinkingCursor()
                }
            }
        }
        if (!message.isStreaming && message.totalTokens > 0) {
            Spacer(Modifier.height(3.dp))
            Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val nodeColor = if (message.isLocal) NexusCyan else NexusGreen
                Icon(if (message.isLocal) Icons.Default.PhoneAndroid else Icons.Default.Hub, null, tint = nodeColor, modifier = Modifier.size(10.dp))
                Text(message.processedBy, color = nodeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("• ${message.totalTokens}t • ${"%.1f".format(message.tokensPerSecond)}t/s", color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val inf = rememberInfiniteTransition(label = "dots")
    val d1 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(0)), "d1")
    val d2 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(200)), "d2")
    val d3 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(400)), "d3")
    Row(Modifier.padding(start = 36.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = AiBubble, border = BorderStroke(1.dp, NexusBorder)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(d1, d2, d3).forEach { a -> Box(Modifier.size(7.dp).clip(CircleShape).background(NexusAccent.copy(alpha = 0.3f + a * 0.7f))) }
            }
        }
    }
}

@Composable
fun BlinkingCursor() {
    val inf = rememberInfiniteTransition(label = "cursor")
    val a by inf.animateFloat(1f, 0f, infiniteRepeatable(tween(500), RepeatMode.Reverse), "ca")
    Text("▌", color = NexusAccent.copy(alpha = a), fontSize = 14.sp)
}

@Composable
fun MeshStatusBar(nodeCount: Int, totalTps: Float, isVisible: Boolean) {
    AnimatedVisibility(isVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = NexusCard) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Hub, null, tint = NexusGreen, modifier = Modifier.size(14.dp))
                Text("Mesh Neural: $nodeCount nó${if (nodeCount != 1) "s" else ""} ativos", color = NexusGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("~${totalTps.toInt()} t/s combinados", color = NexusCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun AgentInputBar(text: String, isGenerating: Boolean, onTextChange: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit) {
    Surface(color = NexusCard, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).navigationBarsPadding().imePadding(),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = onTextChange, modifier = Modifier.weight(1f),
                placeholder = { Text("Mensagem para Synapt…", color = Color.Gray, fontSize = 14.sp) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexusAccent, unfocusedBorderColor = NexusBorder,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    cursorColor = NexusAccent,
                    focusedContainerColor = Color(0xFF0D1220), unfocusedContainerColor = Color(0xFF0D1220)
                ),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!isGenerating) onSend() }),
                enabled = !isGenerating
            )
            FilledIconButton(
                onClick = { if (isGenerating) onStop() else onSend() },
                enabled = isGenerating || text.isNotBlank(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isGenerating) NexusYellow else NexusAccent,
                    disabledContainerColor = NexusBorder
                )
            ) {
                Icon(if (isGenerating) Icons.Default.Stop else Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
