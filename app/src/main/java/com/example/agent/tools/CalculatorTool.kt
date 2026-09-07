package com.example.agent.tools

import com.example.agent.Tool
import com.example.agent.ToolExecutionResult
import com.example.agent.ToolParameter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class CalculatorTool : Tool {
    override val name: String = "calculator"
    override val description: String = "数式を計算して結果を返します。加算(+)、減算(-)、乗算(*)、除算(/)、小数、括弧に対応しています。"
    override val parameters: List<ToolParameter> = listOf(
        ToolParameter(
            name = "expression",
            type = "string",
            description = "計算する数式。例: 12345 * 678, (100 + 50) * 2, 12.5 * 2, 100 / 4"
        )
    )

    override suspend fun execute(arguments: Map<String, String>): ToolExecutionResult {
        val expression = arguments["expression"]
            ?: arguments["input"]
            ?: arguments["expr"]
            ?: arguments.values.firstOrNull()

        if (expression.isNullOrBlank()) {
            return ToolExecutionResult.Error("Calculator Error: Empty expression")
        }
        return evaluate(expression)
    }

    fun evaluate(rawExpression: String): ToolExecutionResult {
        val trimmed = rawExpression.trim()
        if (trimmed.isEmpty()) {
            return ToolExecutionResult.Error("Calculator Error: Empty expression")
        }

        // Validate characters: only digits, spaces, '.', '+', '-', '*', '/', '(', ')' are allowed
        for (ch in trimmed) {
            if (!ch.isDigit() && ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r' &&
                ch != '.' && ch != '+' && ch != '-' && ch != '*' && ch != '/' && ch != '(' && ch != ')'
            ) {
                return ToolExecutionResult.Error("Calculator Error: Invalid characters in expression")
            }
        }

        val tokens = tokenize(trimmed) ?: return ToolExecutionResult.Error("Calculator Error: Invalid expression")
        if (tokens.isEmpty()) {
            return ToolExecutionResult.Error("Calculator Error: Empty expression")
        }

        return try {
            val parser = ExpressionParser(tokens)
            val result = parser.parse()
            if (result.isNaN() || result.isInfinite()) {
                ToolExecutionResult.Error("Calculator Error: Calculation resulted in undefined or infinite value")
            } else {
                ToolExecutionResult.Success(formatResult(result))
            }
        } catch (e: ArithmeticException) {
            ToolExecutionResult.Error("Calculator Error: ${e.message ?: "Division by zero"}")
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.Error("Calculator Error: ${e.message ?: "Invalid expression"}")
        } catch (e: Exception) {
            ToolExecutionResult.Error("Calculator Error: Invalid expression")
        }
    }

    private fun formatResult(value: Double): String {
        val rounded = value.roundToLong()
        return if (abs(value - rounded.toDouble()) < 1e-9) {
            rounded.toString()
        } else {
            val formatted = String.format(Locale.US, "%.10f", value)
            formatted.trimEnd('0').trimEnd('.')
        }
    }

    private sealed class Token {
        data class Number(val value: Double) : Token()
        data object Plus : Token()
        data object Minus : Token()
        data object Multiply : Token()
        data object Divide : Token()
        data object LParen : Token()
        data object RParen : Token()
    }

    private fun tokenize(expr: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (ch.isWhitespace()) {
                i++
                continue
            }
            when (ch) {
                '+' -> { tokens.add(Token.Plus); i++ }
                '-' -> { tokens.add(Token.Minus); i++ }
                '*' -> { tokens.add(Token.Multiply); i++ }
                '/' -> { tokens.add(Token.Divide); i++ }
                '(' -> { tokens.add(Token.LParen); i++ }
                ')' -> { tokens.add(Token.RParen); i++ }
                else -> {
                    if (ch.isDigit() || ch == '.') {
                        val sb = StringBuilder()
                        var dotCount = 0
                        while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                            if (expr[i] == '.') {
                                dotCount++
                                if (dotCount > 1) return null // Multiple decimal points
                            }
                            sb.append(expr[i])
                            i++
                        }
                        val numStr = sb.toString()
                        val num = numStr.toDoubleOrNull() ?: return null
                        tokens.add(Token.Number(num))
                    } else {
                        return null
                    }
                }
            }
        }
        return tokens
    }

    private class ExpressionParser(private val tokens: List<Token>) {
        private var pos = 0

        private fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null
        private fun consume(): Token = tokens[pos++]

        fun parse(): Double {
            val result = parseExpression()
            if (pos < tokens.size) {
                val rem = tokens[pos]
                if (rem is Token.RParen) {
                    throw IllegalArgumentException("Mismatched parentheses")
                }
                throw IllegalArgumentException("Invalid expression")
            }
            return result
        }

        // expression = term (('+' | '-') term)*
        private fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                val token = peek()
                when (token) {
                    is Token.Plus -> {
                        consume()
                        val right = parseTerm()
                        left += right
                    }
                    is Token.Minus -> {
                        consume()
                        val right = parseTerm()
                        left -= right
                    }
                    else -> break
                }
            }
            return left
        }

        // term = factor (('*' | '/') factor)*
        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                val token = peek()
                when (token) {
                    is Token.Multiply -> {
                        consume()
                        val right = parseFactor()
                        left *= right
                    }
                    is Token.Divide -> {
                        consume()
                        val right = parseFactor()
                        if (abs(right) < 1e-12) {
                            throw ArithmeticException("Division by zero")
                        }
                        left /= right
                    }
                    else -> break
                }
            }
            return left
        }

        // factor = ('+' | '-') factor | primary
        private fun parseFactor(): Double {
            val token = peek() ?: throw IllegalArgumentException("Invalid expression")
            if (token is Token.Plus) {
                consume()
                return parseFactor()
            }
            if (token is Token.Minus) {
                consume()
                return -parseFactor()
            }
            return parsePrimary()
        }

        // primary = NUMBER | '(' expression ')'
        private fun parsePrimary(): Double {
            val token = peek() ?: throw IllegalArgumentException("Invalid expression")
            return when (token) {
                is Token.Number -> {
                    consume()
                    token.value
                }
                is Token.LParen -> {
                    consume()
                    val expr = parseExpression()
                    val next = peek()
                    if (next !is Token.RParen) {
                        throw IllegalArgumentException("Mismatched parentheses")
                    }
                    consume()
                    expr
                }
                else -> throw IllegalArgumentException("Invalid expression")
            }
        }
    }
}
