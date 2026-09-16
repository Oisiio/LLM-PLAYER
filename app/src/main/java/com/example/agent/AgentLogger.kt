package com.example.agent

fun interface AgentLogger {
    fun log(message: String)

    companion object {
        @Volatile
        var isDebugLoggingEnabled: Boolean = false

        @Volatile
        var currentLogLevel: String = "Normal"

        fun reset() {
            isDebugLoggingEnabled = false
            currentLogLevel = "Normal"
        }

        private fun isError(msg: String): Boolean =
            msg.contains("error", ignoreCase = true) || msg.contains("exception", ignoreCase = true)

        private fun isWarning(msg: String): Boolean =
            msg.contains("warn", ignoreCase = true)

        val Default = AgentLogger { msg ->
            val isErr = isError(msg)
            val isWarn = isWarning(msg)

            // When Debug Logging is OFF, suppress normal/verbose logs, but NEVER drop errors
            if (!isDebugLoggingEnabled && !isErr && currentLogLevel != "Verbose") {
                return@AgentLogger
            }

            // Apply Log Level filtering:
            // Error -> Error only
            // Warning -> Warning & Error
            // Normal -> Normal, Warning, Error
            // Verbose -> All
            when (currentLogLevel) {
                "Error" -> {
                    if (!isErr) return@AgentLogger
                }
                "Warning" -> {
                    if (!isErr && !isWarn) return@AgentLogger
                }
                "Normal", "Verbose" -> {
                    // Allowed
                }
            }

            try {
                if (isErr) {
                    android.util.Log.e("Agent", msg)
                } else if (isWarn) {
                    android.util.Log.w("Agent", msg)
                } else {
                    android.util.Log.i("Agent", msg)
                }
            } catch (_: Throwable) {
                println(msg)
            }
        }
    }
}
