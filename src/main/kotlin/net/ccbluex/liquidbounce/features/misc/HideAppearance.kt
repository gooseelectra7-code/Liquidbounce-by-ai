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
package net.ccbluex.liquidbounce.features.misc

import com.mojang.blaze3d.platform.IconSet
import com.terraformersmc.modmenu.util.mod.Mod
import kotlinx.coroutines.cancel
import net.ccbluex.liquidbounce.api.core.ioScope
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.EventManager
import net.ccbluex.liquidbounce.event.events.KeyboardKeyEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.command.CommandManager
import net.ccbluex.liquidbounce.features.global.GlobalSettingsRichPresence
import net.ccbluex.liquidbounce.features.misc.HideAppearance.isHidingNow
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.utils.block.ChunkScanner
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.env
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.modmenu.ModMenuCompatibility
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.minecraft.SharedConstants
import org.lwjgl.glfw.GLFW
import java.io.File
import java.lang.Thread.sleep
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

private val modMenuPresent = runCatching {
    Class.forName("com.terraformersmc.modmenu.ModMenu")
    true
}.getOrDefault(false)

/**
 * Hides client appearance
 *
 * using 2x CRTL + SHIFT to hide and unhide the client
 */
object HideAppearance : EventListener {

    /**
     * These mods will be removed from ModMenu.
     * When [isHidingNow] is true
     * Or added, if [isHidingNow] is false
     *
     * Because we don't know about the [Mod] container on each mod in this list
     * We set the default value is null.
     * And we'll provide the value after first removing the mod
     */
    private val modContainersToHide: MutableMap<String, Mod?> = arrayOf(
        "liquidbounce"
    ).associateWith { null }.toMutableMap()

    private val shiftChronometer = Chronometer()

    var isHidingNow = env("LB_UI_HIDE", "net.ccbluex.liquidbounce.ui.hide")?.toBoolean() ?: false
        set(value) {
            field = value
            mc.schedule(::updateClient)

            if (modMenuPresent) {
                if (value) {
                    for (id in modContainersToHide.keys) {
                        modContainersToHide[id] = ModMenuCompatibility.INSTANCE.removeModUnchecked(id)
                    }
                } else {
                    for ((id, container) in modContainersToHide) {
                        container?.let {
                            ModMenuCompatibility.INSTANCE.addModUnchecked(id, it)
                        }
                    }
                }
            }
        }

    var isDestructed = false

    private fun updateClient() {
        // The screen manager re-evaluates the open screen every tick, and leaves vanilla alone
        // while hiding, so there is nothing to re-route here.
        mc.updateTitle()
        mc.window.setIcon(
            mc.vanillaPackResources,
            if (SharedConstants.getCurrentVersion().stable()) IconSet.RELEASE else IconSet.SNAPSHOT
        )
    }

    @Suppress("unused")
    private val keyHandler = handler<KeyboardKeyEvent> { event ->
        val keyCode = event.keyCode
        val modifier = event.mods

        if (inGame) {
            return@handler
        }

        if (keyCode == GLFW.GLFW_KEY_LEFT_SHIFT && modifier == GLFW.GLFW_MOD_CONTROL) {
            if (!shiftChronometer.hasElapsed(400L)) {
                isHidingNow = !isHidingNow
            }

            shiftChronometer.reset()
        }
    }

    /**
     * Attempt to destruct the client
     */
    fun destructClient() {
        isHidingNow = true
        isDestructed = true

        mc.gui.chat.recentChat.removeIf {
            it.startsWith(CommandManager.GlobalSettings.prefix)
        }

        // Shut the client's own long-lived services down explicitly. Firing [ClientShutdownEvent]
        // would be the natural route, but it cannot be used here: [EventManager.callEvent] drops
        // every event once [isDestructed] is set (so it arrived too late in the old ordering), and
        // the destruct command runs inside the very coroutine [CommandExecutor] cancels on that
        // event — which would abort us mid-teardown before the wipe is scheduled. Each stop is
        // individually failure-safe so one throwing never strands the rest.
        runCatching { ChunkScanner.stopThread() }
        runCatching { GlobalSettingsRichPresence.shutdownIpc() }

        // Cancel all async tasks
        ioScope.cancel()

        EventManager.unregisterAll()

        // Disable all modules
        // Be careful to not trigger ConfigManager saving, but this should be prevented by [isDestructed]
        // and unregistering all events
        for (module in ModuleManager) {
            module.enabled = false
        }
        ModuleManager.clear()
    }

    /** Case-insensitive markers that identify a LiquidBounce-authored log line. */
    private val logMarkers = listOf("liquidbounce", "ccbluex")

    /**
     * Removes the client's own artifacts: its JAR(s), its config folder (including
     * `command_history.txt`) and the LiquidBounce-authored lines in the game logs.
     *
     * Everything this live JVM does NOT hold open is deleted in-process right here, on every OS: the
     * whole config folder and every rotated log (`*.log` and `*.log.gz`, line-scrubbed so the vanilla
     * lines survive). Only two files are locked by this JVM and cannot be touched yet — the running
     * JAR and the current `latest.log`. On Unix those can still be unlinked inline; on Windows they are
     * handed to [scheduleWindowsCleanup], which waits for this process to exit (releasing the locks)
     * and only then deletes the JAR and scrubs `latest.log`, plus a backstop pass over the config
     * folder for anything the inline delete could not remove.
     *
     * This only ever touches paths the client created or occupies. It does not, and will not, alter
     * operating-system logs or audit records.
     */
    fun wipeClient() = thread(name = "wipe-client") {
        // Wait for the client to be destructed and for the last teardown writes to flush.
        sleep(1000L)

        val liquidBounceMod = FabricLoaderImpl.INSTANCE.allMods.find {
            it.metadata.id == "liquidbounce"
        }
        val jarFiles = liquidBounceMod?.origin?.paths
            ?.mapNotNull { runCatching { it.toFile() }.getOrNull() }
            .orEmpty()

        // Drop it from Fabric's in-memory registry so the mod list stops listing it this session.
        liquidBounceMod?.let { mod ->
            runCatching { FabricLoaderImpl.INSTANCE.modsInternal.remove(mod) }
        }

        val rootFolder = ConfigSystem.rootFolder
        val logsDir = mc.gameDirectory.resolve("logs")
        val latestLog = logsDir.resolve("latest.log")

        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        if (isWindows) {
            // Everything except the two locked files is removed NOW, in-process: the config folder
            // (command_history.txt with it) and every rotated log — latest.log is skipped, it is locked.
            runCatching { scrubLogLines(logsDir, skip = latestLog) }
            runCatching { scrubGzLogLines(logsDir) }
            runCatching { rootFolder.deleteRecursively() }

            // Only the running JAR and the open latest.log stay locked until this JVM exits — defer those.
            runCatching { scheduleWindowsCleanup(jarFiles, rootFolder, logsDir) }
        } else {
            // Unix can unlink files it still holds open, so the whole job runs inline.
            runCatching { scrubLogLines(logsDir) }
            runCatching { scrubGzLogLines(logsDir) }
            runCatching { rootFolder.deleteRecursively() }
            jarFiles.forEach { jar -> runCatching { jar.delete() } }
        }

        // Clear the visible chat scrollback but KEEP the command history: destructClient() already
        // dropped the client's own commands from it, so passing false preserves the player's vanilla
        // input history (the up-arrow buffer) instead of nuking it.
        mc.gui.chat.clearMessages(false)
    }

    /**
     * Rewrites every `*.log` in [logsDir] in place, dropping lines that mention the client while
     * keeping the vanilla ones. [skip] (the still-open `latest.log` on Windows) is left untouched;
     * rotated `*.log.gz` archives are handled by [scrubGzLogLines].
     */
    private fun scrubLogLines(logsDir: File, skip: File? = null) {
        val logFiles = logsDir.listFiles { file -> file.isFile && file.name.endsWith(".log") } ?: return
        for (logFile in logFiles) {
            if (skip != null && logFile.absolutePath == skip.absolutePath) {
                continue
            }
            runCatching {
                val kept = logFile.readLines().filterNot { line ->
                    logMarkers.any { line.contains(it, ignoreCase = true) }
                }
                logFile.writeText(kept.joinToString(System.lineSeparator()))
            }
        }
    }

    /**
     * Same line-scrub as [scrubLogLines] but for the rotated, gzip-compressed archives (`*.log.gz`):
     * decompress in memory, drop the client's lines, recompress in place. These are never held open,
     * so it runs inline on every OS — and it is exactly what a plain batch cannot do, which is why the
     * Windows deferred pass does not attempt them.
     */
    private fun scrubGzLogLines(logsDir: File) {
        val gzFiles = logsDir.listFiles { file -> file.isFile && file.name.endsWith(".log.gz") } ?: return
        for (gzFile in gzFiles) {
            runCatching {
                val kept = GZIPInputStream(gzFile.inputStream()).bufferedReader().use { reader ->
                    reader.readLines().filterNot { line ->
                        logMarkers.any { line.contains(it, ignoreCase = true) }
                    }
                }
                val tmp = File(gzFile.absolutePath + ".tmp")
                GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { writer ->
                    writer.write(kept.joinToString(System.lineSeparator()))
                }
                tmp.copyTo(gzFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Windows only. Writes a batch helper to the system temp dir (outside the folder it deletes) and
     * launches it detached. Windows does not terminate the child when this JVM exits, and the script
     * first waits for our PID to disappear — so the locks are gone before it runs. By then the inline
     * pass in [wipeClient] has already removed the config folder and the rotated logs; this deferred
     * pass mops up the two files that were still locked at wipe time — it deletes the JAR(s) and scrubs
     * `latest.log` — and re-runs `rmdir` on the config folder as a backstop, then removes itself.
     *
     * `ping -n 2` is the sleep (the child has no console, so `timeout` cannot be used). The `findstr`
     * pass spans every `*.log`, but its real target is `latest.log`; the rotated `*.log.gz` archives
     * are handled in-process by [scrubGzLogLines], since a gzip cannot be edited in plain batch.
     */
    private fun scheduleWindowsCleanup(jarFiles: List<File>, rootFolder: File, logsDir: File) {
        val pid = ProcessHandle.current().pid()
        val findstrMarkers = logMarkers.joinToString(" ") { "/c:\"$it\"" }

        val script = buildString {
            appendLine("@echo off")
            appendLine(":waitloop")
            appendLine("tasklist /fi \"PID eq $pid\" 2>nul | find \"$pid\" >nul")
            appendLine("if not errorlevel 1 ( ping -n 2 127.0.0.1 >nul & goto waitloop )")
            for (jar in jarFiles) {
                appendLine("del /f /q \"${jar.absolutePath}\"")
            }
            appendLine("rmdir /s /q \"${rootFolder.absolutePath}\"")
            appendLine("for %%F in (\"${logsDir.absolutePath}\\*.log\") do (")
            appendLine("  findstr /v /i $findstrMarkers \"%%F\" > \"%%F.tmp\" 2>nul")
            appendLine("  move /y \"%%F.tmp\" \"%%F\" >nul")
            appendLine(")")
            appendLine("del /f /q \"%~f0\"")
        }

        val batFile = File.createTempFile("lb-cleanup", ".bat")
        batFile.writeText(script)

        ProcessBuilder("cmd", "/c", batFile.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

}
