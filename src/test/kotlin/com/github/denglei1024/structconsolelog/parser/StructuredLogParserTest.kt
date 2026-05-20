package com.github.denglei1024.structconsolelog.parser

import com.github.denglei1024.structconsolelog.model.LogLevel
import com.github.denglei1024.structconsolelog.model.LogStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredLogParserTest {
    private val parser = StructuredLogParser()

    @Test
    fun `parses plain log format`() {
        val entry = parser.parse(
            line = "2026-05-20 10:22:33 INFO api.gateway - Request completed",
            stream = LogStream.STDOUT,
            id = 1
        )

        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("api.gateway", entry.logger)
        assertEquals("Request completed", entry.message)
        assertEquals("2026-05-20 10:22:33", entry.parsedTimestamp)
    }

    @Test
    fun `parses json style log format`() {
        val entry = parser.parse(
            line = """{"timestamp":"2026-05-20T10:22:33Z","level":"ERROR","logger":"OrderService","message":"Payment rejected","orderId":"A-1"}""",
            stream = LogStream.STDERR,
            id = 2
        )

        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("OrderService", entry.logger)
        assertEquals("Payment rejected", entry.message)
        assertEquals("A-1", entry.fields["orderId"])
        assertTrue(entry.structured)
    }

    @Test
    fun `parses serilog short level format`() {
        val entry = parser.parse(
            line = "[14:52:06 INF] Executed DbCommand (32ms) [Parameters=[@__countryId_0='1142'], CommandType='Text', CommandTimeout='30']",
            stream = LogStream.STDOUT,
            id = 3
        )

        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("14:52:06", entry.parsedTimestamp)
        assertEquals(null, entry.logger)
        assertTrue(entry.message.startsWith("Executed DbCommand"))
    }

    @Test
    fun `strips ansi color fragments from raw text`() {
        val entry = parser.parse(
            line = "[0;90m[[0m[0m15:08:12[0m[0;90m [0m[0;97mINF[0m[0;90m] [0m[0;97mExecuted DbCommand[0m",
            stream = LogStream.STDOUT,
            id = 4
        )

        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("[15:08:12 INF] Executed DbCommand", entry.rawText)
        assertEquals("Executed DbCommand", entry.message)
    }

    @Test
    fun `strips ansi color fragments from continuation lines`() {
        val sanitized = parser.sanitizeForDisplay(
            "    |-请求参数(Body)：[[0m[0;97m{\"Key\":\"input\"}[0m[0;97m,[0m[0;97m{\"Key\":\"other\"}[0m"
        )

        assertEquals(
            "    |-请求参数(Body)：[{\"Key\":\"input\"},{\"Key\":\"other\"}",
            sanitized
        )
    }

    @Test
    fun `extracts executable sql from inline parameterized db command`() {
        val sqlLog = parser.extractSqlLog(
            "[15:51:38 INF] Executed DbCommand: SELECT `Id` FROM `v_airport` WHERE `IsDeleted` = @IsDeleted1 with parameters: @IsDeleted1='False' (Size = 4000) (DbType = Boolean)"
        )

        assertNotNull(sqlLog)
        assertEquals(
            "SELECT `Id` FROM `v_airport` WHERE `IsDeleted` = FALSE",
            sqlLog?.executableSql
        )
    }

    @Test
    fun `extracts multiline sql from db command logs`() {
        val sqlLog = parser.extractSqlLog(
            """
            [15:17:05 INF] Executed DbCommand (29ms) [Parameters=[], CommandType='Text', CommandTimeout='30']
            SELECT `v`.`Id`
            FROM `v_external_subject` AS `v`
            LEFT JOIN (
                SELECT `v0`.`ExternalSubjectId`
                FROM `v_external_subject_company_info` AS `v0`
            ) AS `t` ON `v`.`Id` = `t`.`ExternalSubjectId`
            WHERE NOT (`v`.`IsDeleted`) AND (`v`.`Id` = 41)
            """.trimIndent()
        )

        assertNotNull(sqlLog)
        assertTrue(sqlLog!!.sqlText.contains(") AS `t` ON `v`.`Id` = `t`.`ExternalSubjectId`"))
        assertTrue(sqlLog.executableSql.contains("WHERE NOT (`v`.`IsDeleted`) AND (`v`.`Id` = 41)"))
    }

    @Test
    fun `treats stack trace lines as continuations`() {
        assertTrue(parser.isContinuationLine("    at Program.Main()"))
        assertTrue(parser.isContinuationLine("Caused by: boom"))
        assertTrue(parser.isContinuationLine("SELECT `t`.`OverseasAgentId`"))
        assertTrue(parser.isContinuationLine(") AS `t` ON `v`.`Id` = `t`.`ExternalSubjectId`"))
        assertTrue(parser.isContinuationLine("AND (`v`.`Id` = 41)"))
    }
}
