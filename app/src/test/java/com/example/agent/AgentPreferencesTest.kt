package com.example.agent

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentPreferencesTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var agentPreferences: AgentPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        agentPreferences = AgentPreferences(fakePrefs)
    }

    @Test
    fun testDefaultSystemPrompt_matchesExpected() {
        assertEquals(AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT, agentPreferences.systemPrompt)
        assertTrue(agentPreferences.systemPrompt.contains("あなたはLLM-PLAYERのAgentです。"))
        assertTrue(agentPreferences.systemPrompt.contains("正確な計算が必要な場合はcalculatorを使用してください。"))
        assertTrue(agentPreferences.systemPrompt.contains("現在の日付・時刻・曜日が必要な場合はdatetimeを使用してください。"))
    }

    @Test
    fun testDefaultThinkingEnabled_isTrue() {
        assertTrue(agentPreferences.isThinkingEnabled)
    }

    @Test
    fun testSaveAndGetSystemPrompt() {
        val customPrompt = "これはカスタムプロンプトです。"
        agentPreferences.systemPrompt = customPrompt

        assertEquals(customPrompt, agentPreferences.systemPrompt)
        assertEquals(customPrompt, fakePrefs.getString(AgentPreferences.KEY_SYSTEM_PROMPT, null))
    }

    @Test
    fun testSaveAndGetThinkingEnabled() {
        agentPreferences.isThinkingEnabled = false

        assertFalse(agentPreferences.isThinkingEnabled)
        assertEquals(false, fakePrefs.getBoolean(AgentPreferences.KEY_THINKING_ENABLED, true))

        agentPreferences.isThinkingEnabled = true
        assertTrue(agentPreferences.isThinkingEnabled)
        assertEquals(true, fakePrefs.getBoolean(AgentPreferences.KEY_THINKING_ENABLED, false))
    }

    @Test
    fun testResetSystemPrompt_restoresDefault() {
        agentPreferences.systemPrompt = "変更されたプロンプト"
        assertNotEquals(AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT, agentPreferences.systemPrompt)

        val resetVal = agentPreferences.resetSystemPrompt()
        assertEquals(AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT, resetVal)
        assertEquals(AgentPromptBuilder.DEFAULT_SYSTEM_PROMPT, agentPreferences.systemPrompt)
    }

    @Test
    fun testSimulatedAppRestart_persistsValues() {
        // 1. User edits settings
        agentPreferences.systemPrompt = "永続化テストプロンプト"
        agentPreferences.isThinkingEnabled = false

        // 2. App restarts and creates a new AgentPreferences instance with the same underlying SharedPreferences
        val restartedPreferences = AgentPreferences(fakePrefs)

        assertEquals("永続化テストプロンプト", restartedPreferences.systemPrompt)
        assertFalse(restartedPreferences.isThinkingEnabled)
    }

    // In-memory SharedPreferences implementation for fast JVM unit tests
    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = (data[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor { temp[key] = value; return this }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { temp[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { temp[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { temp[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { temp[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { temp[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { temp[key] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) parent.data.clear()
                temp.forEach { (k, v) ->
                    if (v == null) parent.data.remove(k) else parent.data[k] = v
                }
            }
        }
    }
}
