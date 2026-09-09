package com.example.agent.tools

import com.example.agent.Tool
import com.example.agent.ToolExecutionResult
import com.example.agent.ToolParameter
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class DateTimeTool(
    private val clock: Clock = Clock.systemDefaultZone()
) : Tool {

    override val name: String = "datetime"
    override val description: String =
        "日時・日付の取得および計算を行います。actionには 'now'(現在日時), 'today'(今日の日付), 'day_of_week'(指定日の曜日), 'add_days'(指定日数後/前), 'diff_days'(2つの日付の差) があります。"

    override val parameters: List<ToolParameter> = listOf(
        ToolParameter(
            name = "action",
            type = "string",
            description = "実行する操作: 'now', 'today', 'day_of_week', 'add_days', 'diff_days'",
            required = true
        ),
        ToolParameter(
            name = "date",
            type = "string",
            description = "基準日付 (形式: YYYY-MM-DD)。day_of_week, add_days で使用",
            required = false
        ),
        ToolParameter(
            name = "days",
            type = "integer",
            description = "加算または減算する日数 (負数可)。add_days で使用",
            required = false
        ),
        ToolParameter(
            name = "from",
            type = "string",
            description = "開始日付 (形式: YYYY-MM-DD)。diff_days で使用",
            required = false
        ),
        ToolParameter(
            name = "to",
            type = "string",
            description = "終了日付 (形式: YYYY-MM-DD)。diff_days で使用",
            required = false
        )
    )

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    override suspend fun execute(arguments: Map<String, String>): ToolExecutionResult {
        // Look up action case-insensitively or deduce from available parameters
        val rawAction = arguments["action"]
            ?: arguments["type"]
            ?: arguments["command"]
            ?: arguments["op"]
            ?: deduceActionFromArgs(arguments)

        if (rawAction.isNullOrBlank()) {
            return ToolExecutionResult.Error("DateTime Error: Missing 'action' argument. Available actions: now, today, day_of_week, add_days, diff_days")
        }

        val action = rawAction.trim().lowercase()
        return when (action) {
            "now", "current_time", "time" -> handleNow()
            "today", "current_date" -> handleToday()
            "day_of_week", "dayofweek", "weekday" -> handleDayOfWeek(arguments)
            "add_days", "adddays", "add_day" -> handleAddDays(arguments)
            "diff_days", "diffdays", "diff_day", "datediff" -> handleDiffDays(arguments)
            else -> ToolExecutionResult.Error(
                "DateTime Error: Unknown action '$action'. Supported actions: now, today, day_of_week, add_days, diff_days"
            )
        }
    }

    private fun deduceActionFromArgs(arguments: Map<String, String>): String? {
        if (arguments.containsKey("from") && arguments.containsKey("to")) return "diff_days"
        if (arguments.containsKey("days")) return "add_days"
        if (arguments.containsKey("date")) return "day_of_week"
        return null
    }

    private fun handleNow(): ToolExecutionResult {
        val now = LocalDateTime.now(clock)
        return ToolExecutionResult.Success(now.format(dateTimeFormatter))
    }

    private fun handleToday(): ToolExecutionResult {
        val today = LocalDate.now(clock)
        return ToolExecutionResult.Success(today.format(dateFormatter))
    }

    private fun handleDayOfWeek(arguments: Map<String, String>): ToolExecutionResult {
        val dateStr = arguments["date"]
            ?: arguments["target"]
            ?: arguments["day"]
            ?: arguments.values.firstOrNull { it.matches(Regex("\\d{4}-\\d{1,2}-\\d{1,2}")) }

        if (dateStr.isNullOrBlank()) {
            return ToolExecutionResult.Error("DateTime Error: Missing 'date' argument for day_of_week")
        }

        val date = parseLocalDate(dateStr)
            ?: return ToolExecutionResult.Error("DateTime Error: Invalid date format '$dateStr'. Expected format: YYYY-MM-DD")

        val dayOfWeek = date.dayOfWeek
        val japaneseDay = formatDayOfWeek(dayOfWeek)
        return ToolExecutionResult.Success(japaneseDay)
    }

    private fun handleAddDays(arguments: Map<String, String>): ToolExecutionResult {
        // Date can be explicit or fallback to today if omitted
        val rawDateStr = arguments["date"]
            ?: arguments["target"]
            ?: arguments["day"]

        val date = if (rawDateStr.isNullOrBlank()) {
            LocalDate.now(clock)
        } else {
            parseLocalDate(rawDateStr)
                ?: return ToolExecutionResult.Error("DateTime Error: Invalid date format '$rawDateStr'. Expected format: YYYY-MM-DD")
        }

        val rawDays = arguments["days"]
            ?: arguments["num"]
            ?: arguments["count"]
            ?: arguments["value"]

        if (rawDays.isNullOrBlank()) {
            return ToolExecutionResult.Error("DateTime Error: Missing 'days' argument for add_days")
        }

        val days = rawDays.trim().toLongOrNull()
            ?: return ToolExecutionResult.Error("DateTime Error: Invalid 'days' argument '$rawDays'. Expected an integer")

        val newDate = date.plusDays(days)
        return ToolExecutionResult.Success(newDate.format(dateFormatter))
    }

    private fun handleDiffDays(arguments: Map<String, String>): ToolExecutionResult {
        val fromStr = arguments["from"]
            ?: arguments["start"]
            ?: arguments["date1"]

        val toStr = arguments["to"]
            ?: arguments["end"]
            ?: arguments["target"]
            ?: arguments["date2"]

        if (fromStr.isNullOrBlank() || toStr.isNullOrBlank()) {
            return ToolExecutionResult.Error("DateTime Error: Missing 'from' or 'to' argument for diff_days")
        }

        val fromDate = parseLocalDate(fromStr)
            ?: return ToolExecutionResult.Error("DateTime Error: Invalid 'from' date format '$fromStr'. Expected format: YYYY-MM-DD")

        val toDate = parseLocalDate(toStr)
            ?: return ToolExecutionResult.Error("DateTime Error: Invalid 'to' date format '$toStr'. Expected format: YYYY-MM-DD")

        val diff = ChronoUnit.DAYS.between(fromDate, toDate)
        return ToolExecutionResult.Success(diff.toString())
    }

    private fun parseLocalDate(text: String): LocalDate? {
        val trimmed = text.trim()
        return try {
            // First try standard ISO format
            LocalDate.parse(trimmed, dateFormatter)
        } catch (e: DateTimeParseException) {
            // Try lenient formats like YYYY/MM/DD or single digit M/D
            try {
                val normalized = trimmed.replace('/', '-')
                val parts = normalized.split('-')
                if (parts.size == 3) {
                    val year = parts[0].toInt()
                    val month = parts[1].toInt()
                    val day = parts[2].toInt()
                    LocalDate.of(year, month, day)
                } else {
                    null
                }
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun formatDayOfWeek(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "月曜日 (Monday)"
            DayOfWeek.TUESDAY -> "火曜日 (Tuesday)"
            DayOfWeek.WEDNESDAY -> "水曜日 (Wednesday)"
            DayOfWeek.THURSDAY -> "木曜日 (Thursday)"
            DayOfWeek.FRIDAY -> "金曜日 (Friday)"
            DayOfWeek.SATURDAY -> "土曜日 (Saturday)"
            DayOfWeek.SUNDAY -> "日曜日 (Sunday)"
        }
    }
}
