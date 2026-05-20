package com.github.denglei1024.structconsolelog.services

import com.github.denglei1024.structconsolelog.model.LogLevel
import com.github.denglei1024.structconsolelog.model.LogStream
import com.github.denglei1024.structconsolelog.model.SessionStatus
import com.github.denglei1024.structconsolelog.model.StructuredLogEntry
import com.github.denglei1024.structconsolelog.model.StructuredLogSessionSnapshot
import com.github.denglei1024.structconsolelog.parser.StructuredLogParser
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import java.time.Instant
import java.util.LinkedHashMap

class StructuredLogProjectService(private val project: Project) : Disposable {
    private val parser = StructuredLogParser()
    private val lock = Any()
    private val dispatcher = EventDispatcher.create(Listener::class.java)
    private val sessions = LinkedHashMap<String, MutableSession>()
    private val sessionIdsByHandler = mutableMapOf<ProcessHandler, String>()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                val sessionId = ensureSession(executorId, env, handler)
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        appendChunk(sessionId, LogStream.fromOutputType(outputType), event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        finishSession(sessionId)
                    }
                })
                notifyChanged()
            }

            override fun processTerminated(
                executorId: String,
                env: ExecutionEnvironment,
                handler: ProcessHandler,
                exitCode: Int
            ) {
                sessionIdsByHandler[handler]?.let(::finishSession)
            }
        })
    }

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        dispatcher.addListener(listener, parentDisposable)
    }

    fun getSnapshot(): List<StructuredLogSessionSnapshot> {
        synchronized(lock) {
            return sessions.values.map { session ->
                StructuredLogSessionSnapshot(
                    id = session.id,
                    displayName = session.displayName,
                    executorId = session.executorId,
                    startedAt = session.startedAt,
                    finishedAt = session.finishedAt,
                    status = session.status,
                    entries = session.entries.toList()
                )
            }
        }
    }

    fun clearSession(sessionId: String) {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return
            session.entries.clear()
            session.buffers.values.forEach { it.setLength(0) }
            session.nextEntryId = 1L
        }
        notifyChanged()
    }

    private fun ensureSession(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler): String {
        synchronized(lock) {
            sessionIdsByHandler[handler]?.let { return it }

            val sessionId = buildString {
                append(handler.hashCode())
                append('-')
                append(System.nanoTime())
            }
            val displayName = env.runProfile.name.ifBlank { "Unnamed run configuration" }
            val session = MutableSession(
                id = sessionId,
                displayName = displayName,
                executorId = executorId,
                startedAt = Instant.now()
            )
            sessions[sessionId] = session
            sessionIdsByHandler[handler] = sessionId
            return sessionId
        }
    }

    private fun appendChunk(sessionId: String, stream: LogStream, text: String) {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return
            val buffer = session.buffers.getOrPut(stream) { StringBuilder() }
            buffer.append(text.replace("\r\n", "\n"))
            drainBuffer(session, stream, flush = false)
        }
        notifyChanged()
    }

    private fun finishSession(sessionId: String) {
        synchronized(lock) {
            val session = sessions[sessionId] ?: return
            session.buffers.keys.toList().forEach { stream ->
                drainBuffer(session, stream, flush = true)
            }
            session.finishedAt = Instant.now()
            session.status = SessionStatus.FINISHED
        }
        notifyChanged()
    }

    private fun drainBuffer(session: MutableSession, stream: LogStream, flush: Boolean) {
        val buffer = session.buffers.getOrPut(stream) { StringBuilder() }
        var startIndex = 0

        for (index in 0 until buffer.length) {
            if (buffer[index] == '\n') {
                handleLine(session, stream, buffer.substring(startIndex, index))
                startIndex = index + 1
            }
        }

        if (flush && startIndex < buffer.length) {
            handleLine(session, stream, buffer.substring(startIndex))
            startIndex = buffer.length
        }

        if (startIndex > 0) {
            buffer.delete(0, startIndex)
        }
    }

    private fun handleLine(session: MutableSession, stream: LogStream, line: String) {
        if (session.entries.isNotEmpty() && parser.isContinuationLine(line)) {
            val previous = session.entries.last()
            val sanitizedLine = parser.sanitizeForDisplay(line)
            previous.message = previous.message + "\n" + sanitizedLine
            previous.rawText = previous.rawText + "\n" + sanitizedLine
            return
        }

        val entry = parser.parse(
            line = line,
            stream = stream,
            id = session.nextEntryId++,
            capturedAt = Instant.now()
        )

        session.entries += entry
    }

    private fun notifyChanged() {
        ApplicationManager.getApplication().invokeLater {
            dispatcher.multicaster.logsUpdated()
        }
    }

    override fun dispose() = Unit

    interface Listener : java.util.EventListener {
        fun logsUpdated()
    }

    private data class MutableSession(
        val id: String,
        val displayName: String,
        val executorId: String,
        val startedAt: Instant,
        val entries: MutableList<StructuredLogEntry> = mutableListOf(),
        val buffers: MutableMap<LogStream, StringBuilder> = mutableMapOf(),
        var finishedAt: Instant? = null,
        var status: SessionStatus = SessionStatus.RUNNING,
        var nextEntryId: Long = 1L
    )
}

