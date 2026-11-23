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
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.utils.client.logger
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The Windows "now playing" bridge.
 *
 * Windows exposes the current media session through the WinRT
 * `GlobalSystemMediaTransportControlsSession` API. The metadata alone is reachable from
 * PowerShell, but the cover art is not: PowerShell 5.1 hands back a bare `System.__ComObject` for
 * the WinRT stream and cannot marshal it. So a tiny C# helper is compiled once with the .NET
 * Framework compiler that ships with Windows, and driven as a subprocess:
 *
 * - `Smtc.exe poll` prints one `\u001f`-separated line
 *   `ok - title - artist - Playing|Paused|Stopped - positionMs - durationMs - coverBase64(PNG)`,
 *   or the single word `none`.
 * - `Smtc.exe next|prev|toggle` drives the session.
 *
 * The helper is compiled lazily into the client's config folder and only when it is missing or
 * older than its source. Every SDK path is globbed rather than hardcoded, because the version
 * folders differ per machine. Polling runs on a daemon thread once a second and never touches the
 * render thread; any failure at all — no Windows, no SDK, compile error, no session — leaves
 * [current] at `null`, which simply hides the panel, and is logged exactly once.
 */
internal object HudTestMediaBridge {

    /** One poll's worth of session state. */
    class Session(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        /** When this snapshot was taken, for interpolating the playback position between polls. */
        val polledAt: Long,
    )

    private const val POLL_INTERVAL_MS = 1000L
    private const val PROCESS_TIMEOUT_S = 8L
    private const val UNIT_SEPARATOR = '\u001F'

    @Volatile
    private var session: Session? = null

    /** Raw PNG bytes of a cover that the render thread has not uploaded yet. */
    @Volatile
    private var pendingCover: ByteArray? = null

    /** Title the cached cover belongs to; the cover is only re-decoded when this changes. */
    private var coverTitle: String? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var failureLogged = false
    private var executable: File? = null

    /** The latest session, or `null` when there is none or the bridge is unavailable. */
    fun current(): Session? = session

    /** Takes the undecoded cover bytes, if a new one arrived since the last call. */
    fun takePendingCover(): ByteArray? {
        val cover = pendingCover
        pendingCover = null
        return cover
    }

    /**
     * The playback position interpolated from the frame clock, so the progress bar moves smoothly
     * between the one-second polls instead of stepping.
     */
    fun positionMs(session: Session): Long {
        if (!session.playing) {
            return session.positionMs
        }
        val elapsed = System.currentTimeMillis() - session.polledAt
        return (session.positionMs + elapsed).coerceAtMost(session.durationMs.coerceAtLeast(0L))
    }

    /** Starts the poll thread if it is not already running. Safe to call every enable. */
    @Synchronized
    fun start() {
        if (running || !isWindows()) {
            return
        }
        running = true
        thread = Thread(::pollLoop, "HudTest media bridge").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the poll thread and forgets the current session. */
    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        session = null
        pendingCover = null
        coverTitle = null
    }

    /** Sends `next`, `prev` or `toggle` to the session, off the render thread. */
    fun command(command: String) {
        val exe = executable ?: return
        Thread({
            runCatching { runProcess(exe, command) }
        }, "HudTest media command").apply {
            isDaemon = true
            start()
        }
    }

    // --- Polling ---------------------------------------------------------------------------------

    private fun pollLoop() {
        val exe = try {
            HudTestMediaCompiler.ensureExecutable()
        } catch (e: Throwable) {
            logFailureOnce("HudTest: the media bridge could not be built", e)
            null
        }
        if (exe == null) {
            running = false
            return
        }
        executable = exe

        while (running) {
            try {
                parse(runProcess(exe, "poll"))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Throwable) {
                logFailureOnce("HudTest: the media bridge stopped responding", e)
                session = null
                running = false
                return
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun runProcess(exe: File, argument: String): String {
        // stderr is discarded rather than merged: a .NET stack trace on the same stream would
        // corrupt the record, and a stream nobody drains can block the child once its pipe fills
        val process = ProcessBuilder(exe.absolutePath, argument)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (!process.waitFor(PROCESS_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the media bridge timed out")
        }
        return output
    }

    private fun parse(output: String) {
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (line == null || line == "none") {
            session = null
            coverTitle = null
            return
        }
        val fields = line.split(UNIT_SEPARATOR)
        if (fields.size < 6 || fields[0] != "ok") {
            session = null
            return
        }

        val title = fields[1]
        session = Session(
            title = title,
            artist = fields[2],
            playing = fields[3].equals("Playing", ignoreCase = true),
            positionMs = fields[4].toLongOrNull() ?: 0L,
            durationMs = fields[5].toLongOrNull() ?: 0L,
            polledAt = System.currentTimeMillis(),
        )

        // The base64 cover is ~220 KB; only decode it when the track actually changed
        val encoded = fields.getOrNull(6).orEmpty()
        if (title != coverTitle) {
            coverTitle = title
            pendingCover = if (encoded.isEmpty()) {
                ByteArray(0)
            } else {
                runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            }
        }
    }

    private fun logFailureOnce(message: String, error: Throwable) {
        if (!failureLogged) {
            failureLogged = true
            logger.info("$message: ${error.message}")
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}

/**
 * Builds the C# helper the first time it is needed.
 *
 * Nothing here is hardcoded to a specific SDK version: the .NET Framework compiler, the union
 * WinMD and the two reference assemblies are all found by globbing their version folders and
 * taking the newest, since those numbers differ per machine.
 */
internal object HudTestMediaCompiler {

    private const val SOURCE_NAME = "Smtc.cs"
    private const val EXE_NAME = "Smtc.exe"
    private const val COMPILE_TIMEOUT_S = 90L

    /** Where the helper lives: the client's own config folder, next to the configs. */
    private val cacheFolder: File by lazy {
        File(ConfigSystem.rootFolder, "hudtest").apply { mkdirs() }
    }

    /**
     * Returns the compiled helper, writing and compiling it first when needed. Throws when any
     * part of the toolchain is missing — the caller turns that into "the panel stays hidden".
     */
    fun ensureExecutable(): File {
        val source = File(cacheFolder, SOURCE_NAME)
        val expected = HudTestMediaSource.CODE
        if (!source.isFile || source.readText(Charsets.UTF_8) != expected) {
            source.writeText(expected, Charsets.UTF_8)
        }

        val exe = File(cacheFolder, EXE_NAME)
        if (exe.isFile && exe.lastModified() >= source.lastModified()) {
            return exe
        }
        compile(source, exe)
        return exe
    }

    private fun compile(source: File, exe: File) {
        val compiler = newestMatch(
            File(System.getenv("SystemRoot") ?: "C:\\Windows", "Microsoft.NET\\Framework64"),
            depth = 1,
            leaf = "csc.exe",
        ) ?: error("no .NET Framework compiler found")

        val programFiles = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val winmd = newestMatch(
            File(programFiles, "Windows Kits\\10\\UnionMetadata"),
            depth = 1,
            leaf = "Windows.winmd",
        ) ?: error("no Windows union metadata found")
        val facade = newestMatch(
            File(programFiles, "Reference Assemblies\\Microsoft\\Framework\\.NETFramework"),
            depth = 1,
            leaf = "Facades\\System.Runtime.dll",
        ) ?: error("no System.Runtime facade found")
        val winRuntime = newestMatch(
            File(System.getenv("SystemRoot") ?: "C:\\Windows", "Microsoft.NET\\assembly\\GAC_MSIL"),
            depth = 1,
            leaf = "System.Runtime.WindowsRuntime.dll",
            root = "System.Runtime.WindowsRuntime",
        ) ?: error("no System.Runtime.WindowsRuntime found")

        val process = ProcessBuilder(
            compiler.absolutePath,
            "-nologo",
            "-target:exe",
            "-out:${exe.absolutePath}",
            "-reference:${winmd.absolutePath}",
            "-reference:${facade.absolutePath}",
            "-reference:${winRuntime.absolutePath}",
            source.absolutePath,
        ).directory(cacheFolder).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(COMPILE_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("the media bridge compiler timed out")
        }
        if (process.exitValue() != 0 || !exe.isFile) {
            error("the media bridge failed to compile: ${output.trim().take(400)}")
        }
    }

    /**
     * Finds `<base>[/<root>]/<newest version folder>[.../]<leaf>`, picking the highest-sorting
     * version folder that actually contains [leaf]. [depth] is how many version folders deep the
     * leaf sits below [base] (after the optional fixed [root] segment).
     */
    private fun newestMatch(base: File, depth: Int, leaf: String, root: String? = null): File? {
        var directory = base
        if (root != null) {
            directory = File(directory, root)
        }
        if (!directory.isDirectory) {
            return null
        }
        // Some SDK layouts drop the version folder entirely
        File(directory, leaf).takeIf(File::isFile)?.let { return it }

        var candidates = listOf(directory)
        repeat(depth) {
            candidates = candidates
                .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }
                .sortedWith(VERSION_FOLDERS_FIRST)
        }
        return candidates.asSequence().map { File(it, leaf) }.firstOrNull(File::isFile)
    }

    /**
     * Highest version folder first. Folders whose name does not look like a version are pushed to
     * the back on purpose: the Windows SDK ships a `UnionMetadata\Facade\Windows.winmd` stub next
     * to the real `UnionMetadata\<version>\Windows.winmd`, and a plain descending sort would pick
     * the stub — which does not carry the media control types at all.
     */
    private val VERSION_FOLDERS_FIRST: Comparator<File> =
        compareByDescending<File> { looksLikeVersion(it.name) }.thenByDescending { it.name }

    private fun looksLikeVersion(name: String): Boolean {
        val first = name.firstOrNull() ?: return false
        return first.isDigit() || (first == 'v' && name.getOrNull(1)?.isDigit() == true)
    }
}
