package com.example.agent

import java.util.Locale

data class StepDiagnostics(
    val stepNumber: Int = 1,
    val generatedTokens: Int = 0,
    val generationTimeMs: Double = 0.0,
    val speedTokPerSec: Double = 0.0,
    // 1 & 2: Token latency
    val first10AvgMs: Double? = null,
    val first10TokPerSec: Double? = null,
    val first20AvgMs: Double? = null,
    val first20TokPerSec: Double? = null,
    val middleAvgMs: Double? = null,
    val last10AvgMs: Double? = null,
    val last10TokPerSec: Double? = null,
    // 3: Decode (llama_decode alone in loop)
    val decodeTotalMs: Double = 0.0,
    val decodeAvgMs: Double = 0.0,
    val decodeMinMs: Double = 0.0,
    val decodeMaxMs: Double = 0.0,
    // 4: Sampling (sample_with_sampling_filters alone)
    val samplingTotalMs: Double = 0.0,
    val samplingAvgMs: Double = 0.0,
    val samplingMaxMs: Double = 0.0,
    // 5: JNI Callback (onToken)
    val callbackTotalMs: Double = 0.0,
    val callbackAvgMs: Double = 0.0,
    val callbackMaxMs: Double = 0.0,
    // 6: Page Faults (getrusage RUSAGE_SELF)
    val minorPageFaults: Long = 0L,
    val majorPageFaults: Long = 0L,
    val pageFaultsAvailable: Boolean = true,
    // 7: CPU Cores
    val cpuCores: String = ""
) {
    fun formatReport(): String = formatDiagnosticsText(this)

    companion object {
        private fun parseFlatJson(jsonStr: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val regex = """"([^"]+)"\s*:\s*(?:"([^"]*)"|([^,\}\s]+))""".toRegex()
            for (match in regex.findAll(jsonStr)) {
                val key = match.groupValues[1]
                val stringVal = match.groups[2]?.value
                val rawVal = match.groups[3]?.value
                val value = stringVal ?: rawVal ?: ""
                result[key] = value
            }
            return result
        }

        fun fromJson(jsonStr: String, stepNumber: Int = 1): StepDiagnostics {
            val map = parseFlatJson(jsonStr)
            val parsedStep = map["step"]?.toIntOrNull() ?: stepNumber
            val hasFirst10 = map["has_first10"]?.toBoolean() ?: false
            val hasFirst20 = map["has_first20"]?.toBoolean() ?: false
            val hasMiddle = map["has_middle"]?.toBoolean() ?: false
            val hasLast10 = map["has_last10"]?.toBoolean() ?: false

            val genTokens = (map["generated_tokens"] ?: map["gen_tokens"])?.toIntOrNull() ?: 0
            val genTime = (map["generation_time_ms"] ?: map["gen_time_ms"])?.toDoubleOrNull() ?: 0.0
            val speed = (map["speed_tok_per_sec"] ?: map["speed_tok_s"])?.toDoubleOrNull() ?: 0.0

            return StepDiagnostics(
                stepNumber = parsedStep,
                generatedTokens = genTokens,
                generationTimeMs = genTime,
                speedTokPerSec = speed,
                first10AvgMs = if (hasFirst10) map["first10_avg_ms"]?.toDoubleOrNull() else null,
                first10TokPerSec = if (hasFirst10) map["first10_tok_s"]?.toDoubleOrNull() else null,
                first20AvgMs = if (hasFirst20) map["first20_avg_ms"]?.toDoubleOrNull() else null,
                first20TokPerSec = if (hasFirst20) map["first20_tok_s"]?.toDoubleOrNull() else null,
                middleAvgMs = if (hasMiddle) map["middle_avg_ms"]?.toDoubleOrNull() else null,
                last10AvgMs = if (hasLast10) map["last10_avg_ms"]?.toDoubleOrNull() else null,
                last10TokPerSec = if (hasLast10) map["last10_tok_s"]?.toDoubleOrNull() else null,
                decodeTotalMs = map["decode_total_ms"]?.toDoubleOrNull() ?: 0.0,
                decodeAvgMs = map["decode_avg_ms"]?.toDoubleOrNull() ?: 0.0,
                decodeMinMs = map["decode_min_ms"]?.toDoubleOrNull() ?: 0.0,
                decodeMaxMs = map["decode_max_ms"]?.toDoubleOrNull() ?: 0.0,
                samplingTotalMs = map["sampling_total_ms"]?.toDoubleOrNull() ?: 0.0,
                samplingAvgMs = map["sampling_avg_ms"]?.toDoubleOrNull() ?: 0.0,
                samplingMaxMs = map["sampling_max_ms"]?.toDoubleOrNull() ?: 0.0,
                callbackTotalMs = map["callback_total_ms"]?.toDoubleOrNull() ?: 0.0,
                callbackAvgMs = map["callback_avg_ms"]?.toDoubleOrNull() ?: 0.0,
                callbackMaxMs = map["callback_max_ms"]?.toDoubleOrNull() ?: 0.0,
                minorPageFaults = map["minor_page_faults"]?.toLongOrNull() ?: 0L,
                majorPageFaults = map["major_page_faults"]?.toLongOrNull() ?: 0L,
                pageFaultsAvailable = map["page_faults_available"]?.toBoolean() ?: true,
                cpuCores = map["cpu_cores"] ?: ""
            )
        }

        fun formatDiagnosticsText(diag: StepDiagnostics): String {
            val sb = StringBuilder()
            sb.appendLine("Step ${diag.stepNumber}")
            sb.appendLine("Generated: ${diag.generatedTokens} tok")
            sb.appendLine("Generation: ${String.format(Locale.US, "%.2f", diag.generationTimeMs / 1000.0)} s")
            sb.appendLine("Speed: ${String.format(Locale.US, "%.2f", diag.speedTokPerSec)} tok/s")
            sb.appendLine()
            sb.appendLine("Token latency")
            if (diag.first10AvgMs != null) {
                val speedStr = diag.first10TokPerSec?.let { " (${String.format(Locale.US, "%.2f", it)} tok/s)" } ?: ""
                sb.appendLine("First 10:  ${String.format(Locale.US, "%.1f", diag.first10AvgMs)} ms/tok$speedStr")
            } else {
                sb.appendLine("First 10:  -")
            }
            if (diag.first20AvgMs != null) {
                val speedStr = diag.first20TokPerSec?.let { " (${String.format(Locale.US, "%.2f", it)} tok/s)" } ?: ""
                sb.appendLine("First 20:  ${String.format(Locale.US, "%.1f", diag.first20AvgMs)} ms/tok$speedStr")
            } else {
                sb.appendLine("First 20:  -")
            }
            if (diag.middleAvgMs != null) {
                sb.appendLine("Middle:    ${String.format(Locale.US, "%.1f", diag.middleAvgMs)} ms/tok")
            } else {
                sb.appendLine("Middle:    -")
            }
            if (diag.last10AvgMs != null) {
                val speedStr = diag.last10TokPerSec?.let { " (${String.format(Locale.US, "%.2f", it)} tok/s)" } ?: ""
                sb.appendLine("Last 10:   ${String.format(Locale.US, "%.1f", diag.last10AvgMs)} ms/tok$speedStr")
            } else {
                sb.appendLine("Last 10:   -")
            }
            sb.appendLine()
            sb.appendLine("Decode")
            sb.appendLine("Total: ${String.format(Locale.US, "%.1f", diag.decodeTotalMs)} ms")
            sb.appendLine("Avg:   ${String.format(Locale.US, "%.1f", diag.decodeAvgMs)} ms")
            sb.appendLine("Min:   ${String.format(Locale.US, "%.1f", diag.decodeMinMs)} ms")
            sb.appendLine("Max:   ${String.format(Locale.US, "%.1f", diag.decodeMaxMs)} ms")
            sb.appendLine()
            sb.appendLine("Sampling")
            sb.appendLine("Total: ${String.format(Locale.US, "%.1f", diag.samplingTotalMs)} ms")
            sb.appendLine("Avg:   ${String.format(Locale.US, "%.1f", diag.samplingAvgMs)} ms")
            sb.appendLine("Max:   ${String.format(Locale.US, "%.1f", diag.samplingMaxMs)} ms")
            sb.appendLine()
            sb.appendLine("JNI Callback")
            sb.appendLine("Total: ${String.format(Locale.US, "%.1f", diag.callbackTotalMs)} ms")
            sb.appendLine("Avg:   ${String.format(Locale.US, "%.1f", diag.callbackAvgMs)} ms")
            sb.appendLine("Max:   ${String.format(Locale.US, "%.1f", diag.callbackMaxMs)} ms")
            sb.appendLine()
            sb.appendLine("Page Faults")
            if (diag.pageFaultsAvailable) {
                sb.appendLine("Minor: ${diag.minorPageFaults}")
                sb.appendLine("Major: ${diag.majorPageFaults}")
            } else {
                sb.appendLine("Minor: 取得不可")
                sb.appendLine("Major: 取得不可")
            }
            sb.appendLine()
            sb.appendLine("CPU cores")
            sb.appendLine("Step ${diag.stepNumber}: ${if (diag.cpuCores.isNotEmpty()) diag.cpuCores else "Unknown"}")
            return sb.toString().trimEnd()
        }
    }
}
