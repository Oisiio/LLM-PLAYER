package com.example.agent.tools

import com.example.agent.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class DateTimeToolTest {

    private val fixedInstant = Instant.parse("2026-09-08T12:30:45Z")
    private val fixedZone = ZoneId.of("UTC")
    private val fixedClock = Clock.fixed(fixedInstant, fixedZone)

    private val dateTimeTool = DateTimeTool(fixedClock)

    @Test
    fun testNow_withFixedClock() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "now"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-09-08 12:30:45", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testToday_withFixedClock() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "today"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-09-08", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testDayOfWeek_normalDate() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "day_of_week", "date" to "2026-09-08"))
        assertTrue(result is ToolExecutionResult.Success)
        assertTrue((result as ToolExecutionResult.Success).output.contains("火曜日"))
    }

    @Test
    fun testDayOfWeek_leapYearDate() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "day_of_week", "date" to "2024-02-29"))
        assertTrue(result is ToolExecutionResult.Success)
        assertTrue((result as ToolExecutionResult.Success).output.contains("木曜日"))
    }

    @Test
    fun testAddDays_positive() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-09-08", "days" to "10"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-09-18", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_zero() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-09-08", "days" to "0"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-09-08", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_negative() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-09-08", "days" to "-8"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-08-31", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_monthTransition() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-09-25", "days" to "10"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-10-05", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_yearTransition() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-12-30", "days" to "5"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2027-01-04", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_leapYear() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2024-02-28", "days" to "1"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2024-02-29", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testAddDays_defaultToTodayWhenDateOmitted() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "days" to "2"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2026-09-10", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testDiffDays_sameDate() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "diff_days", "from" to "2026-09-08", "to" to "2026-09-08"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("0", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testDiffDays_forward() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "diff_days", "from" to "2026-09-08", "to" to "2026-12-25"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("108", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testDiffDays_backward() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "diff_days", "from" to "2026-12-25", "to" to "2026-09-08"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("-108", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testDiffDays_leapYear() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "diff_days", "from" to "2024-02-28", "to" to "2024-03-01"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("2", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun testError_invalidDateFormat() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "day_of_week", "date" to "invalid-date"))
        assertTrue(result is ToolExecutionResult.Error)
        assertTrue((result as ToolExecutionResult.Error).errorMessage.contains("Invalid date format"))
    }

    @Test
    fun testError_unknownAction() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "unknown_action"))
        assertTrue(result is ToolExecutionResult.Error)
        assertTrue((result as ToolExecutionResult.Error).errorMessage.contains("Unknown action"))
    }

    @Test
    fun testError_missingArgument() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "diff_days", "from" to "2026-09-08"))
        assertTrue(result is ToolExecutionResult.Error)
        assertTrue((result as ToolExecutionResult.Error).errorMessage.contains("Missing 'from' or 'to'"))
    }

    @Test
    fun testError_invalidDaysNumber() = runBlocking {
        val result = dateTimeTool.execute(mapOf("action" to "add_days", "date" to "2026-09-08", "days" to "not-a-number"))
        assertTrue(result is ToolExecutionResult.Error)
        assertTrue((result as ToolExecutionResult.Error).errorMessage.contains("Invalid 'days' argument"))
    }
}
