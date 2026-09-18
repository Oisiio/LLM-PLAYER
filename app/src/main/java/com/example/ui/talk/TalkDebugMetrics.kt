package com.example.ui.talk

data class TalkDebugMetrics(
    val ttftMs: Double? = null,
    val promptTokens: Int? = null,
    val genTokens: Int? = null,
    val promptTimeMs: Double? = null,
    val genTimeMs: Double? = null,
    val totalTimeMs: Double? = null,
    val speedTokPerSec: Double? = null,
    val threads: Int = 4,
    val isGenerating: Boolean = false,
    val cachedTokens: Int = 0,
    val newPromptTokens: Int? = null
)
