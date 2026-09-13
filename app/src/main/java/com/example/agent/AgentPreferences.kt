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

    var thinkingBudget: Int
        get() = prefs.getInt(KEY_THINKING_BUDGET, DEFAULT_THINKING_BUDGET)
        set(value) {
            prefs.edit().putInt(KEY_THINKING_BUDGET, value).apply()
        }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
        set(value) {
            prefs.edit().putInt(KEY_MAX_STEPS, value).apply()
        }

    var isCalculatorEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALCULATOR_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_CALCULATOR_ENABLED, value).apply()
        }

    var isDateTimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DATETIME_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DATETIME_ENABLED, value).apply()
        }

    fun resetSystemPrompt(): String {
        val defaultVal = AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT
        systemPrompt = defaultVal
        return defaultVal
    }

    fun resetAll() {
        resetSystemPrompt()
        isThinkingEnabled = true
        thinkingBudget = DEFAULT_THINKING_BUDGET
        maxSteps = DEFAULT_MAX_STEPS
        isCalculatorEnabled = true
        isDateTimeEnabled = true
    }

    companion object {
        const val KEY_SYSTEM_PROMPT = "agent_system_prompt"
        const val KEY_THINKING_ENABLED = "agent_thinking_enabled"
        const val KEY_THINKING_BUDGET = "agent_thinking_budget"
        const val KEY_MAX_STEPS = "agent_max_steps"
        const val KEY_CALCULATOR_ENABLED = "agent_calculator_enabled"
        const val KEY_DATETIME_ENABLED = "agent_datetime_enabled"
        const val DEFAULT_THINKING_BUDGET = 1024
        const val DEFAULT_MAX_STEPS = 8
    }
}
