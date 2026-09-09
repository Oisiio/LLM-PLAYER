package com.example.agent

import android.content.Context
import android.content.SharedPreferences

class AgentPreferences(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("talk_prefs", Context.MODE_PRIVATE)
    )

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT)
            ?: AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT
        set(value) {
            prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()
        }

    var isThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_THINKING_ENABLED, value).apply()
        }

    fun resetSystemPrompt(): String {
        val defaultVal = AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT
        systemPrompt = defaultVal
        return defaultVal
    }

    companion object {
        const val KEY_SYSTEM_PROMPT = "agent_system_prompt"
        const val KEY_THINKING_ENABLED = "agent_thinking_enabled"
    }
}
