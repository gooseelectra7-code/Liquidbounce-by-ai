/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.bots.ipc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ccbluex.liquidbounce.utils.client.clientLogger
import java.io.BufferedWriter
import java.io.IOException

/**
 * One subprocess + its newline-JSON IPC streams.
 *
 * Caller owns the process lifecycle via [start]/[close]. While the process is running, callers
 * send commands with [send] and observe events on [events].
 *
 * Phase 1 note: the bundled Botcraft binary does not exist yet, so [start] currently spawns
 * a minimal OS-native mock that emits a single "joined" event and exits. From Phase 2 onwards
 * the same channel will host the real patched Botcraft process — the framing stays identical.
 */
class BotIpcChannel(private val tag: String) {

    private val logger = clientLogger("BotIpc/$tag")

    @Volatile
    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var readerJob: Job? = null
    private var stderrJob: Job? = null

    private val _events = MutableSharedFlow<BotEvent>(replay = 0, extraBufferCapacity = 64)
    val events: Flow<BotEvent> = _events.asSharedFlow()

    val running: Boolean get() = process?.isAlive == true

    /**
     * Spawn the subprocess. The command is supplied fully resolved — see [mockCommandFor]
     * for the Phase 1 placeholder.
     */
    fun start(command: List<String>, scope: CoroutineScope) {
        check(process == null) { "Channel already started" }

        val p = spawn(command, scope) ?: return
        process = p
        stdinWriter = p.outputStream.bufferedWriter()

        readerJob = scope.launch(Dispatchers.IO) { pumpStdout(p) }
        stderrJob = scope.launch(Dispatchers.IO) { pumpStderr(p) }
    }

    private fun spawn(command: List<String>, scope: CoroutineScope): Process? {
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        return try {
            pb.start()
        } catch (e: IOException) {
            logger.error("Failed to spawn bot process: ${e.message}", e)
            scope.launch { _events.emit(BotEvent.Error("spawn failed: ${e.message}")) }
            null
        }
    }

    private suspend fun pumpStdout(p: Process) {
        try {
            p.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    _events.emit(BotEvent.fromJson(line))
                }
            }
        } catch (e: Exception) {
            logger.warn("stdout reader failed: ${e.message}")
        }
        val code = withContext(Dispatchers.IO) { p.waitFor() }
        logger.info("Process exited with code $code")
        _events.emit(BotEvent.Left("exit $code"))
    }

    private fun pumpStderr(p: Process) {
        try {
            p.errorStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotEmpty()) logger.warn("[stderr] $line")
                }
            }
        } catch (_: Exception) {
            // Stream closed on process exit — ignore.
        }
    }

    /** Serialize [command] to JSON and write it to stdin with a trailing newline. */
    suspend fun send(command: BotCommand) {
        val writer = stdinWriter ?: error("Channel not started")
        val line = command.toJson()
        withContext(Dispatchers.IO) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    /** Terminate the subprocess and release I/O resources. Safe to call multiple times. */
    fun close() {
        try {
            stdinWriter?.close()
        } catch (_: IOException) {
            // ignore
        }
        process?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            }
        }
        readerJob?.cancel()
        stderrJob?.cancel()
        process = null
        stdinWriter = null
    }

    companion object {
        /**
         * OS-appropriate command line that prints one mock event and exits. Used in Phase 1
         * before the real Botcraft binary is wired up.
         */
        fun mockCommandFor(profile: String): List<String> {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val payload = """{"type":"joined"}"""
            return if (isWindows) {
                listOf("cmd", "/c", "echo $payload && echo {\"type\":\"log\",\"level\":\"INFO\"," +
                    "\"message\":\"mock bot $profile alive\"}")
            } else {
                listOf("sh", "-c", "printf '%s\\n%s\\n' " +
                    "'$payload' " +
                    "'{\"type\":\"log\",\"level\":\"INFO\",\"message\":\"mock bot $profile alive\"}'")
            }
        }
    }
}
