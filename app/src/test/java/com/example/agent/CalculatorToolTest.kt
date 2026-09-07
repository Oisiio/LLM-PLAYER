package com.example.agent

import com.example.agent.tools.CalculatorTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CalculatorToolTest {

    private val calculator = CalculatorTool()

    @Test
    fun test1_basicAddition() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "123 + 456"))
        assertTrue("Expected success", result is ToolExecutionResult.Success)
        assertEquals("579", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun test2_operatorPrecedence() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "10 + 5 * 2"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("20", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun test3_parentheses() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "(10 + 5) * 2"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("30", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun test4_decimals() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "12.5 * 2"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("25", (result as ToolExecutionResult.Success).output)

        val divResult = calculator.execute(mapOf("expression" to "12.5 / 2.5"))
        assertTrue(divResult is ToolExecutionResult.Success)
        assertEquals("5", (divResult as ToolExecutionResult.Success).output)
    }

    @Test
    fun test5_division() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "100 / 4"))
        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("25", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun test6_divisionByZero() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "10 / 0"))
        assertTrue("Must return error result, not throw", result is ToolExecutionResult.Error)
        val errMsg = (result as ToolExecutionResult.Error).errorMessage
        assertTrue(errMsg.startsWith("Calculator Error: Division by zero"))
    }

    @Test
    fun testSafety_noArbitraryCodeExecution() = runBlocking {
        val invalidInputs = listOf(
            "eval('alert(1)')",
            "system(ls)",
            "123 + print(x)",
            "__import__('os')",
            "10 % 3", // % is not allowed in safe calculator
            "10 & 2"
        )
        for (input in invalidInputs) {
            val result = calculator.execute(mapOf("expression" to input))
            assertTrue("Expected error for '$input'", result is ToolExecutionResult.Error)
            assertTrue((result as ToolExecutionResult.Error).errorMessage.contains("Calculator Error"))
        }
    }

    @Test
    fun testSyntaxErrors() = runBlocking {
        val malformed = listOf(
            "",
            "   ",
            "10 +",
            "+ * 5",
            "(10 + 5",
            "10 + 5)"
        )
        for (input in malformed) {
            val result = calculator.execute(mapOf("expression" to input))
            assertTrue("Expected error for '$input'", result is ToolExecutionResult.Error)
        }
    }

    @Test
    fun testUnaryOperators() = runBlocking {
        val res1 = calculator.execute(mapOf("expression" to "-5 + 3"))
        assertEquals("-2", (res1 as ToolExecutionResult.Success).output)

        val res2 = calculator.execute(mapOf("expression" to "5 * -2"))
        assertEquals("-10", (res2 as ToolExecutionResult.Success).output)

        val res3 = calculator.execute(mapOf("expression" to "-(10 + 5)"))
        assertEquals("-15", (res3 as ToolExecutionResult.Success).output)
    }
}
