package com.github.denglei1024.structconsolelog.model

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import java.time.Instant

enum class LogLevel(val weight: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5),
    UNKNOWN(6);

    companion object {
        fun fromText(value: String?): LogLevel? {
            return when (value?.uppercase()) {
                "TRACE", "TRC", "VRB" -> TRACE
                "DEBUG", "DBG" -> DEBUG
                "INFO", "INF" -> INFO
                "WARN", "WARNING", "WRN" -> WARN
                "ERROR", "ERR" -> ERROR
                "FATAL", "FTL" -> FATAL
                else -> null
            }
        }
    }
}

enum class LogStream(val label: String) {
    STDOUT("Stdout"),
    STDERR("Stderr"),
    SYSTEM("System");

    companion object {
        fun fromOutputType(outputType: Key<*>): LogStream {
            return when {
                ProcessOutputType.isStdout(outputType) -> STDOUT
                ProcessOutputType.isStderr(outputType) -> STDERR
                else -> SYSTEM
            }
        }
    }
}

enum class SessionStatus {
    RUNNING,
    FINISHED
}

data class StructuredLogEntry(
    val id: Long,
    val capturedAt: Instant,
    val parsedTimestamp: String?,
    val level: LogLevel,
    val logger: String?,
    var message: String,
    var rawText: String,
    val stream: LogStream,
    val fields: Map<String, String>,
    val structured: Boolean
)

data class StructuredLogSessionSnapshot(
    val id: String,
    val displayName: String,
    val executorId: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: SessionStatus,
    val entries: List<StructuredLogEntry>
) {
    val totalEntries: Int
        get() = entries.size
}

