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
package net.ccbluex.liquidbounce.features.module.modules.combat.aura.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * How the domes are shaped. Shared by every live dome in the frame — the per-dome state is only
 * its centre, its radius and its fade.
 *
 * @param strength global multiplier over the displacement and the smear; 0 turns the effect off
 *   without any branch changing (the offsets simply become zero).
 * @param amplitude peak screen-space displacement, in UV units. The original's 0.026 is about 2.6%
 *   of the screen at the crest of a ripple, which is already strong; below 0.01 it reads as heat
 *   shimmer.
 * @param ripples how many concentric waves fill the dome. Fractional values are fine and the
 *   result stays continuous.
 * @param radialBlur width of the light radial smear, in UV units. 0 disables the extra four taps.
 * @param fadeIn fraction of the dome's life spent ramping the fade up from 0.
 * @param fadeOut fraction of its life spent ramping back down to 0.
 */
data class DomeSettings(
    val strength: Float = 1f,
    val amplitude: Float = 0.026f,
    val ripples: Float = 3f,
    val radialBlur: Float = 0.003f,
    val fadeIn: Float = 0.12f,
    val fadeOut: Float = 0.45f,
)

/**
 * The showpiece: a world-anchored hemisphere that refracts the scene where a crystal went off.
 *
 * Port of the competitor's `blast_shockwave`. Nothing is drawn — no ring, no colour, no geometry.
 * A full-screen pass unprojects every pixel, intersects the view ray with a sphere at the
 * explosion centre, keeps the upper half, drops what the depth buffer says is in front of the
 * shell, and then samples the scene from a displaced place: the whole volume of the dome ripples
 * with concentric waves. See `shaders/aura/blast_dome.fsh` for the maths and for why
 * every one of the original's early `return`s became a smoothstep here.
 *
 * ### Using it
 * ```
 * // when a crystal we attacked actually blew up
 * AuraBlastDome.add(this, crystal.position(), maxRadius = 6f, durationMs = 700L)
 *
 * // every frame, from the module's WorldRenderEvent handler
 * AuraBlastDome.render(event, DomeSettings(strength = settings.shockwaveStrength))
 * ```
 * [render] is a no-op while no dome is live, and allocates nothing until the first [add].
 *
 * ### Passes
 * Two, both full-screen. The first copies the finished world into an offscreen snapshot, because
 * the second writes into the main colour target and therefore cannot read it. The second binds
 * that snapshot plus the main target's depth attachment and blends the displaced colour back with
 * premultiplied alpha, so at the dome's boundary — where coverage reaches 0 — the pixel it writes
 * is bit-for-bit the pixel already there. The effect has no border at all.
 *
 * ### Timing
 * The main target must already hold the finished world when this runs. `WorldRenderEvent` fires
 * from `MixinGameRenderer.hookWorldRender`, after `LevelRenderer.renderLevel` has executed the
 * frame graph and before the hand and the HUD — the same window the ESP composites use. If the
 * module ends up needing a later hook, [renderPostWorld] is the identical entry point without the
 * event, to be called from `MixinLevelRenderer.onFragEffectsPostProcess` beside
 * `KillEffectScanMode.render()`.
 *
 * ### Failure
 * Anything the render thread could throw is caught once; after that the effect turns itself off
 * for the session and frees its target instead of throwing every frame.
 *
 * ### One instance, every aura
 * This lives in the shared `combat.aura.render` package rather than inside a module because it owns
 * GPU resources: a full-screen render target (plus its UBO and samplers) that is allocated lazily and
 * handed back when idle. A per-module copy would allocate a SECOND set, alive at the same time, for
 * exactly the same picture. Nothing here holds per-module state - every call is driven entirely by its
 * arguments - so one object serves CrystalAura and AutoAnchor at once. That is the opposite of the
 * copy-don't-share rule the two modules' LOGIC follows, and deliberately so: the logic was copied to
 * escape shared MUTABLE state, and there is none of that here.
 */
@Suppress("TooManyFunctions")
object AuraBlastDome {

    /** Must match `MAX_DOMES` in `shaders/aura/blast_dome.fsh`. */
    private const val MAX_DOMES = 4

    /** Idle frames tolerated before the scene snapshot is handed back. */
    private const val IDLE_FRAMES_BEFORE_RELEASE = 120

    private class Dome(
        val owner: Any,
        val center: Vec3,
        val maxRadius: Float,
        val durationMs: Long,
        val startTime: Long,
    )

    /**
     * Live domes, oldest first. Mutated by [add] (which may be reached from a packet handler) and
     * read by the render thread, so every touch goes through the list's own monitor.
     */
    private val domes = ObjectArrayList<Dome>()

    /** Snapshot of the finished world; the dome pass refracts this, not the target it writes to. */
    private val sceneHolder = LazyRenderTargetHolder(
        "LiquidBounce Aura Dome Scene",
        useDepth = false,
    )

    private val domeUbo by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.AURA_DOME.createSingleBuffer()
    }

    private val linearSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    private val depthSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }

    private val inverseMatrix = Matrix4f()

    /** Reused per frame so the render path allocates nothing. */
    private val frameDomes = ObjectArrayList<Dome>()

    /** Sticky: set once the effect has thrown, never cleared. */
    private var broken = false

    private var idleFrames = 0

    /**
     * Starts one dome at [center].
     *
     * Its age drives the radius: it grows from 0 to [maxRadius] over [durationMs] on an ease-out
     * curve (fast off the mark, settling at the end, the way a blast front actually decelerates)
     * and the effect ends the moment it gets there. The fade in and out is [DomeSettings]' job.
     *
     * Only [MAX_DOMES] domes are live at once; a new one past that drops the oldest. Ignored when
     * either bound is non-positive, so a caller need not sanity-check its own settings.
     *
     * [owner] tags the dome so that one aura shutting down does not wipe another's ([release]); pass any stable
     * identity, normally the calling renderer object itself. The [MAX_DOMES] budget is deliberately GLOBAL - it is
     * the shader's array size, not a per-caller allowance.
     */
    fun add(owner: Any, center: Vec3, maxRadius: Float, durationMs: Long) {
        if (broken || maxRadius <= 0f || durationMs <= 0L) {
            return
        }

        synchronized(domes) {
            if (domes.size >= MAX_DOMES) {
                domes.removeAt(0)
            }
            domes.add(Dome(owner, center, maxRadius, durationMs, System.currentTimeMillis()))
        }
    }

    /**
     * Draws every live dome, oldest first, in a single pair of full-screen passes.
     *
     * A no-op while nothing is live. [event] pins the call to the world-render phase (the main
     * target has to hold the finished world) and keeps the shape identical to [AuraGlow.render];
     * the matrices come from [WorldToScreen], which holds exactly the projection × modelView the
     * frame was built with.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun render(event: WorldRenderEvent, settings: DomeSettings) = renderPostWorld(settings)

    /**
     * [render] without the event, for a caller that hooks the world's completion directly rather
     * than going through `WorldRenderEvent`.
     */
    fun renderPostWorld(settings: DomeSettings) {
        if (broken) {
            return
        }

        val now = System.currentTimeMillis()
        collectLiveDomes(now)

        if (frameDomes.isEmpty()) {
            onIdleFrame()
            return
        }

        idleFrames = 0

        runCatching {
            drawDomes(settings, now)
        }.onFailure { cause ->
            broken = true
            logger.error("Aura blast dome disabled after a render failure", cause)
            // The effect is off for the whole session now, so nothing of anyone's is worth preserving.
            release(owner = null)
        }

        frameDomes.clear()
    }

    /**
     * Drops [owner]'s live domes and, once nothing is left to draw at all, frees the scene snapshot. Call on
     * world change and on disable; the target is rebuilt on the next frame that has a dome.
     *
     * A dome belongs to the world it went off in, which is why they are dropped rather than left to expire. The
     * owner filter is what makes that safe now that two auras share this object: without it, one module going
     * quiet would erase the other's blast mid-flight and hand back a render target it is still drawing into. Pass
     * `null` to drop everything - that is the world-change case, where no dome survives regardless of owner.
     */
    fun release(owner: Any?) {
        val empty = synchronized(domes) {
            if (owner == null) domes.clear() else domes.removeIf { it.owner === owner }
            domes.isEmpty()
        }
        if (!empty) {
            return
        }

        idleFrames = 0
        frameDomes.clear()
        runCatching {
            sceneHolder.close()
        }
    }

    /** Moves the domes that are still alive at [now] into [frameDomes], dropping the rest. */
    private fun collectLiveDomes(now: Long) {
        frameDomes.clear()
        synchronized(domes) {
            val iterator = domes.iterator()
            while (iterator.hasNext()) {
                val dome = iterator.next()
                if (now - dome.startTime >= dome.durationMs) {
                    iterator.remove()
                } else {
                    frameDomes.add(dome)
                }
            }
        }
    }

    private fun onIdleFrame() {
        if (sceneHolder.raw == null) {
            return
        }

        idleFrames++
        if (idleFrames >= IDLE_FRAMES_BEFORE_RELEASE) {
            // Nothing has been drawn for a while, so there is nothing of anyone's to preserve.
            release(owner = null)
        }
    }

    private fun drawDomes(settings: DomeSettings, now: Long) {
        val main = mc.mainRenderTarget
        val colorView = main.colorTextureView ?: return
        val depthView = main.depthTextureView ?: return
        if (main.width <= 0 || main.height <= 0) {
            return
        }

        // The snapshot is fully overwritten by the copy below, so it is never cleared first.
        val scene = sceneHolder.initAndGet(clear = false)
        val sceneView = scene.colorTextureView ?: return

        sceneView.createRenderPass({ "AuraBlastDome SceneCopy" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.AuraSceneCopy)
            pass.bindTexture("InSampler", colorView, linearSampler)
            pass.draw(0, 3)
        }

        writeParams(settings, now)

        // Colour-only pass: the depth texture is bound as a sampler, so it must not also be an
        // attachment of the very same pass.
        colorView.createRenderPass({ "AuraBlastDome Refract" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.AuraBlastDome)
            pass.bindTexture("SceneSampler", sceneView, linearSampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            ClientUniformDefine.AURA_DOME.setTo(pass, domeUbo)
            pass.draw(0, 3)
        }
    }

    private fun writeParams(settings: DomeSettings, now: Long) {
        inverseMatrix.set(WorldToScreen.projModelView).invert()

        val camera = WorldToScreen.cameraPosition
        val count = frameDomes.size.coerceAtMost(MAX_DOMES)
        val zZeroToOne = if (RenderSystem.getDevice().isZZeroToOne) 1f else 0f

        domeUbo.writeStd140 {
            putMat4f(inverseMatrix)
            putVec4(count.toFloat(), zZeroToOne, settings.strength.coerceAtLeast(0f), 0f)

            // domes[MAX_DOMES]: centre relative to the camera + current radius
            for (i in 0 until MAX_DOMES) {
                if (i >= count) {
                    putVec4(0f, 0f, 0f, 0f)
                    continue
                }

                val dome = frameDomes[i]
                putVec4(
                    (dome.center.x - camera.x).toFloat(),
                    (dome.center.y - camera.y).toFloat(),
                    (dome.center.z - camera.z).toFloat(),
                    radiusOf(dome, progressOf(dome, now)),
                )
            }

            // domeParams[MAX_DOMES]: fade + the shared shape
            for (i in 0 until MAX_DOMES) {
                if (i >= count) {
                    putVec4(0f, 0f, 0f, 0f)
                    continue
                }

                val dome = frameDomes[i]
                putVec4(
                    fadeOf(progressOf(dome, now), settings),
                    settings.amplitude.coerceAtLeast(0f),
                    settings.ripples.coerceAtLeast(0f),
                    settings.radialBlur.coerceAtLeast(0f),
                )
            }
        }
    }

    /** Ease-out over the dome's life: quick off the mark, settling as it reaches [Dome.maxRadius]. */
    private fun radiusOf(dome: Dome, progress: Float): Float {
        val remaining = 1f - progress
        return dome.maxRadius * (1f - remaining * remaining)
    }

    /** Ramps up over [DomeSettings.fadeIn] and back down over [DomeSettings.fadeOut]. */
    private fun fadeOf(progress: Float, settings: DomeSettings): Float {
        val fadeIn = settings.fadeIn.coerceIn(0f, 1f)
        val fadeOut = settings.fadeOut.coerceIn(0f, 1f)

        val rise = smoothStep(0f, fadeIn, progress)
        val fall = 1f - smoothStep(1f - fadeOut, 1f, progress)
        return (rise * fall).coerceIn(0f, 1f)
    }

    private fun progressOf(dome: Dome, now: Long): Float =
        ((now - dome.startTime).toFloat() / dome.durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * The GLSL `smoothstep`, with a degenerate range collapsing to a step at [edge0] instead of
     * dividing by zero (so `fadeIn = 0` means "already fully up", not NaN).
     */
    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 - edge0 <= 1e-5f) {
            return if (x < edge0) 0f else 1f
        }

        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
