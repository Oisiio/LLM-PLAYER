package com.example.agent

fun interface AgentLogger {
    fun log(message: String)

    companion object {
        val Default = AgentLogger { msg ->
            try {
                android.util.Log.i("Agent", msg)
            } catch (_: Throwable) {
                println(msg)
            }
        }
    }
}
