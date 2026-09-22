package com.example.agent

import org.junit.Assert.*
import org.junit.Test

class StepDiagnosticsTest {

    @Test
    fun testFromJsonAndFormatReport() {
        val json = """
            {
                "step": 1,
                "gen_tokens": 86,
                "gen_time_ms": 64299.5,
                "speed_tok_s": 1.34,
                "has_first10": true,
                "first10_avg_ms": 750.2,
                "first10_tok_s": 1.33,
                "has_first20": true,
                "first20_avg_ms": 745.0,
                "first20_tok_s": 1.34,
                "has_middle": true,
                "middle_avg_ms": 740.0,
                "has_last10": true,
                "last10_avg_ms": 760.1,
                "last10_tok_s": 1.32,
                "decode_total_ms": 62100.0,
                "decode_avg_ms": 722.1,
                "decode_min_ms": 690.0,
                "decode_max_ms": 850.5,
                "sampling_total_ms": 120.5,
                "sampling_avg_ms": 1.4,
                "sampling_max_ms": 5.2,
                "callback_total_ms": 45.0,
                "callback_avg_ms": 0.52,
                "callback_max_ms": 2.1,
                "minor_page_faults": 124,
                "major_page_faults": 0,
                "page_faults_available": true,
                "cpu_cores": "4, 5, 6 (transitions: 3)"
            }
        """.trimIndent()

        val diag = StepDiagnostics.fromJson(json, stepNumber = 1)
        assertEquals(1, diag.stepNumber)
        assertEquals(86, diag.generatedTokens)
        assertEquals(64299.5, diag.generationTimeMs, 0.01)
        assertEquals(1.34, diag.speedTokPerSec, 0.01)
        assertEquals(750.2, diag.first10AvgMs!!, 0.01)
        assertEquals(745.0, diag.first20AvgMs!!, 0.01)
        assertEquals(740.0, diag.middleAvgMs!!, 0.01)
        assertEquals(760.1, diag.last10AvgMs!!, 0.01)
        assertEquals(62100.0, diag.decodeTotalMs, 0.01)
        assertEquals(722.1, diag.decodeAvgMs, 0.01)
        assertEquals(690.0, diag.decodeMinMs, 0.01)
        assertEquals(850.5, diag.decodeMaxMs, 0.01)
        assertEquals(120.5, diag.samplingTotalMs, 0.01)
        assertEquals(45.0, diag.callbackTotalMs, 0.01)
        assertEquals(124L, diag.minorPageFaults)
        assertEquals(0L, diag.majorPageFaults)
        assertEquals("4, 5, 6 (transitions: 3)", diag.cpuCores)

        val report = diag.formatReport()
        assertTrue(report.contains("Step 1"))
        assertTrue(report.contains("First 10:"))
        assertTrue(report.contains("Decode"))
        assertTrue(report.contains("Sampling"))
        assertTrue(report.contains("JNI Callback"))
        assertTrue(report.contains("Page Faults"))
        assertTrue(report.contains("CPU cores"))
    }
}
