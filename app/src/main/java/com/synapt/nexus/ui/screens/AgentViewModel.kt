package com.synapt.nexus.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synapt.nexus.network.MeshInferenceRouter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

// ─── UI State (shared between ViewModel and Screen) ──────────────────────────

data class AgentUiState(
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessage.Role.SYSTEM,
            content = "Synapt Neural Mesh pronta.",
            processedBy = "sistema"
        )
    ),
    val inputText: String = "",
    val isGenerating: Boolean = false,
    val meshNodeCount: Int = 0,
    val activeNodeName: String = "local",
    val currentTokensPerSecond: Float = 0f,
    val totalMeshTps: Float = 0f,
    val error: String? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val isStreaming: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val processedBy: String = "local",
    val isLocal: Boolean = true,
    val totalTokens: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class AgentViewModel(
    private val router: MeshInferenceRouter
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private val history = mutableListOf<Pair<String, String>>()

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text, error = null) }
    }

    fun onSendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isGenerating) return

        val userMsg = ChatMessage(role = ChatMessage.Role.USER, content = text)
        val assistantId = UUID.randomUUID().toString()

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMsg + ChatMessage(
                    id = assistantId, role = ChatMessage.Role.ASSISTANT,
                    content = "", isStreaming = true
                ),
                inputText = "",
                isGenerating = true,
                error = null
            )
        }

        generationJob = viewModelScope.launch {
            val prompt = buildPrompt(text)
            val sb = StringBuilder()

            router.routeInference(
                prompt = prompt, maxTokens = 1024, temperature = 0.7f,
                sessionId = UUID.randomUUID().toString()
            ).collect { result ->
                when (result) {
                    is MeshInferenceRouter.InferenceResult.Token -> {
                        sb.append(result.text)
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == assistantId) msg.copy(
                                        content = sb.toString(),
                                        isStreaming = !result.done,
                                        tokensPerSecond = result.tokensPerSecond,
                                        processedBy = result.processedBy,
                                        isLocal = result.isLocal,
                                        totalTokens = result.totalTokens
                                    ) else msg
                                },
                                isGenerating = !result.done,
                                activeNodeName = result.processedBy,
                                currentTokensPerSecond = result.tokensPerSecond
                            )
                        }
                        if (result.done) history.add(text to sb.toString())
                    }
                    is MeshInferenceRouter.InferenceResult.Error -> {
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == assistantId) msg.copy(
                                        content = "⚠️ ${result.message}", isStreaming = false
                                    ) else msg
                                },
                                isGenerating = false,
                                error = result.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun onStopGeneration() {
        generationJob?.cancel()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
                isGenerating = false
            )
        }
    }

    fun onClearConversation() {
        generationJob?.cancel()
        history.clear()
        _uiState.value = AgentUiState()
    }

    fun updateMeshInfo(nodeCount: Int, totalTps: Float) {
        _uiState.update { it.copy(meshNodeCount = nodeCount, totalMeshTps = totalTps) }
    }

    private fun buildPrompt(userText: String): String = buildString {
        appendLine("<s>[SYSTEM]Você é Synapt, um assistente de IA rodando em uma rede neural distribuída de dispositivos móveis. Seja conciso e útil.[/SYSTEM]")
        history.takeLast(4).forEach { (u, a) ->
            appendLine("[INST] $u [/INST]")
            appendLine(a)
        }
        appendLine("[INST] $userText [/INST]")
    }
}
