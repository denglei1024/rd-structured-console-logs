package com.github.denglei1024.structconsolelog.parser

import com.github.denglei1024.structconsolelog.model.LogLevel
import com.github.denglei1024.structconsolelog.model.LogStream
import com.github.denglei1024.structconsolelog.model.StructuredLogEntry
import java.time.Instant

data class SqlLogDetails(
    val sqlText: String,
    val executableSql: String,
    val parameters: Map<String, String>
)

class StructuredLogParser {
    private val ansiEscapePattern = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")
    private val orphanedColorCodePattern = Regex("""\[[0-9;]{1,20}m""")
    private val plainLogPattern = Regex(
        pattern = """^(?:\[(?<timestamp1>\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(?<level1>TRACE|TRC|DEBUG|DBG|INFO|INF|WARN|WARNING|WRN|ERROR|ERR|FATAL|FTL|VRB)])?\s*(?:(?<timestamp2>\d{4}-\d{2}-\d{2}[T ][0-9:.+,Z\-]+)\s+)?(?:\[(?<level2>TRACE|TRC|DEBUG|DBG|INFO|INF|WARN|WARNING|WRN|ERROR|ERR|FATAL|FTL|VRB)])?(?:\s*(?<level3>TRACE|TRC|DEBUG|DBG|INFO|INF|WARN|WARNING|WRN|ERROR|ERR|FATAL|FTL|VRB))?\s*(?:(?<logger>[A-Za-z0-9_.:$/-]+)\s*[-:]\s*)?(?<message>.*)$""",
        option = RegexOption.IGNORE_CASE
    )
    private val jsonPairPattern = Regex("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|[^,{}]+)")
    private val kvPattern = Regex("""([A-Za-z0-9_.@-]+)=("[^"]*"|\S+)""")
    private val sqlContinuationPattern = Regex(
        """^(SELECT|FROM|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|GROUP|ORDER|HAVING|LIMIT|OFFSET|INSERT|UPDATE|DELETE|VALUES|SET|UNION|AND|OR|ON)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sqlJoinContinuationPattern = Regex(
        """^\)\s+AS\b""",
        RegexOption.IGNORE_CASE
    )
    private val sqlParameterPattern = Regex(
        """(?<name>@[A-Za-z0-9_]+)=(?<value>'(?:''|[^'])*'|NULL|null|[^,\s\]]+)(?<meta>(?:\s+\([^)]*\))*)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(line: String, stream: LogStream, id: Long, capturedAt: Instant = Instant.now()): StructuredLogEntry {
        val normalized = sanitizeForParsing(line)
        val jsonFields = extractJsonFields(normalized)
        val kvFields = extractKeyValueFields(normalized)
        val plainMatch = plainLogPattern.matchEntire(normalized)

        val timestamp = firstNonBlank(
            jsonFields["timestamp"],
            jsonFields["time"],
            jsonFields["ts"],
            jsonFields["@timestamp"],
            groupValue(plainMatch, "timestamp1"),
            groupValue(plainMatch, "timestamp2")
        )

        val rawLevel = firstNonBlank(
            jsonFields["level"],
            jsonFields["lvl"],
            jsonFields["severity"],
            jsonFields["@l"],
            groupValue(plainMatch, "level1"),
            groupValue(plainMatch, "level2"),
            groupValue(plainMatch, "level3")
        )

        val logger = firstNonBlank(
            jsonFields["logger"],
            jsonFields["category"],
            jsonFields["source"],
            kvFields["logger"],
            kvFields["category"],
            groupValue(plainMatch, "logger")
        )

        val level = LogLevel.fromText(rawLevel) ?: if (stream == LogStream.STDERR) LogLevel.ERROR else LogLevel.UNKNOWN
        val message = firstNonBlank(
            jsonFields["message"],
            jsonFields["msg"],
            jsonFields["@m"],
            kvFields["message"],
            kvFields["msg"],
            groupValue(plainMatch, "message")
        ) ?: normalized

        val combinedFields = linkedMapOf<String, String>().apply {
            putAll(jsonFields)
            kvFields.forEach { (key, value) ->
                if (key !in this) {
                    put(key, value)
                }
            }
        }

        return StructuredLogEntry(
            id = id,
            capturedAt = capturedAt,
            parsedTimestamp = timestamp,
            level = level,
            logger = logger,
            message = message,
            rawText = normalized,
            stream = stream,
            fields = combinedFields,
            structured = timestamp != null || logger != null || combinedFields.isNotEmpty() || level != LogLevel.UNKNOWN
        )
    }

    fun isContinuationLine(line: String): Boolean {
        val trimmed = sanitizeForDisplay(line).trimStart()
        return line.startsWith(" ") ||
            line.startsWith("\t") ||
            trimmed.startsWith("at ") ||
            trimmed.startsWith("Caused by:") ||
            trimmed.startsWith("Suppressed:") ||
            sqlContinuationPattern.containsMatchIn(trimmed) ||
            sqlJoinContinuationPattern.containsMatchIn(trimmed) ||
            trimmed.startsWith("--- End of stack trace")
    }

    fun sanitizeForDisplay(line: String): String {
        return stripControlCodes(line).trimEnd()
    }

    fun extractSqlLog(rawText: String): SqlLogDetails? {
        val normalized = sanitizeForDisplay(rawText)
        if (!normalized.contains("Executed DbCommand", ignoreCase = true)) {
            return null
        }

        val lines = normalized
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) {
            return null
        }

        val header = lines.first()
        val sqlText = when {
            lines.size > 1 && lines.drop(1).all { isSqlTextLine(it.trimStart()) } -> lines.drop(1).joinToString("\n")
            else -> extractInlineSql(header) ?: return null
        }

        val parameters = parseSqlParameters(extractParameterSection(header))
        return SqlLogDetails(
            sqlText = sqlText,
            executableSql = applySqlParameters(sqlText, parameters),
            parameters = parameters
        )
    }

    private fun extractJsonFields(line: String): Map<String, String> {
        if (!line.startsWith("{") || !line.endsWith("}")) {
            return emptyMap()
        }

        val result = linkedMapOf<String, String>()
        jsonPairPattern.findAll(line).forEach { match ->
            result[match.groupValues[1]] = cleanupValue(match.groupValues[2])
        }
        return result
    }

    private fun extractKeyValueFields(line: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        kvPattern.findAll(line).forEach { match ->
            result[match.groupValues[1]] = cleanupValue(match.groupValues[2])
        }
        return result
    }

    private fun cleanupValue(value: String): String {
        return value.trim().removeSurrounding("\"").replace("\\\"", "\"")
    }

    private fun sanitizeForParsing(line: String): String {
        return sanitizeForDisplay(line).trim()
    }

    private fun stripControlCodes(line: String): String {
        return line
            .trimEnd('\r')
            .replace(ansiEscapePattern, "")
            .replace(orphanedColorCodePattern, "")
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun extractInlineSql(header: String): String? {
        val inlineSql = header.substringAfter("Executed DbCommand:", "").trim()
        if (inlineSql.isBlank()) {
            return null
        }

        val sqlOnly = inlineSql.substringBefore(" with parameters:", inlineSql).trim()
        return sqlOnly.takeIf { isSqlTextLine(it) }
    }

    private fun extractParameterSection(header: String): String {
        header.substringAfter("with parameters:", "").trim().takeIf { it.isNotBlank() }?.let { return it }

        val parametersStart = header.indexOf("[Parameters=[")
        if (parametersStart < 0) {
            return ""
        }

        val remainder = header.substring(parametersStart + "[Parameters=[".length)
        return remainder.substringBefore("], CommandType=", remainder.substringBefore(']')).trim()
    }

    private fun parseSqlParameters(parameterSection: String): Map<String, String> {
        if (parameterSection.isBlank()) {
            return emptyMap()
        }

        val parameters = linkedMapOf<String, String>()
        sqlParameterPattern.findAll(parameterSection).forEach { match ->
            val name = groupValue(match, "name") ?: return@forEach
            val value = groupValue(match, "value") ?: return@forEach
            val meta = groupValue(match, "meta").orEmpty()
            parameters[name] = renderSqlLiteral(value, meta)
        }
        return parameters
    }

    private fun applySqlParameters(sqlText: String, parameters: Map<String, String>): String {
        if (parameters.isEmpty()) {
            return sqlText
        }

        var executableSql = sqlText
        parameters.entries
            .sortedByDescending { it.key.length }
            .forEach { (name, value) ->
                executableSql = executableSql.replace(name, value)
            }
        return executableSql
    }

    private fun renderSqlLiteral(value: String, metadata: String): String {
        if (value.equals("null", ignoreCase = true)) {
            return "NULL"
        }

        val raw = value.removeSurrounding("'").replace("''", "'")
        val isBoolean = metadata.contains("DbType = Boolean", ignoreCase = true) ||
            raw.equals("true", ignoreCase = true) ||
            raw.equals("false", ignoreCase = true)
        if (isBoolean) {
            return if (raw.equals("true", ignoreCase = true)) "TRUE" else "FALSE"
        }

        if (!value.startsWith("'") && raw.matches(Regex("""-?\d+(\.\d+)?"""))) {
            return raw
        }

        return "'${raw.replace("'", "''")}'"
    }

    private fun isSqlTextLine(text: String): Boolean {
        return sqlContinuationPattern.containsMatchIn(text) || sqlJoinContinuationPattern.containsMatchIn(text)
    }

    private fun groupValue(match: MatchResult?, groupName: String): String? {
        val namedGroups = match?.groups as? MatchNamedGroupCollection ?: return null
        return namedGroups[groupName]?.value
    }
}

