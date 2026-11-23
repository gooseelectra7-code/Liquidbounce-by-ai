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
package net.ccbluex.liquidbounce.render.burninghands

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleBurningHands
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.clearColor
import net.ccbluex.liquidbounce.utils.render.clearColorAndDepth
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.GameRenderState
import org.joml.Matrix4f
import org.joml.Matrix4fc

/**
 * FBO orchestration for [ModuleBurningHands] — a port of the exploit client's
 * `BurningHandsRenderer` + `BurningHandsPipeline` (fire glow/trail around the
 * first-person hands and held items).
 *
 * Capture via own submit + own flush: vanilla draws the on-screen hands by itself; the mixin
 * submits an identical second copy (same pose object, same light) into [submitStorage], and
 * after vanilla's hand rasterization this renderer flushes the private storage into HAND_MASK
 * through a private [FeatureRenderDispatcher] with the output overrides set. Per frame:
 *
 * 1. [flushCapturedGeometry] clears the window-sized HAND_MASK target (color + depth),
 *    points [RenderSystem.outputColorTextureOverride] /
 *    [RenderSystem.outputDepthTextureOverride] at it, flushes [submitStorage]
 *    (`renderAllFeatures` builds the meshes, `endBatch` rasterizes them into the mask)
 *    under the camera rotation matrix (see [vanillaModelView] — without it the copy is
 *    locked to the world instead of the camera), and restores the overrides.
 * 2. [runPasses] executes the shader chain, all fullscreen triangle-pairs (`draw(0, 6)`):
 *    blit is skipped (vanilla's hands are already on screen) — glow (HAND_MASK ->
 *    GLOW_BUFFER, radial fire halo) ->
 *    trail (GLOW_BUFFER + previous trail -> next trail, ping-pong TRAIL_A/TRAIL_B with decay) ->
 *    composite (glow + trail additively onto the main framebuffer).
 *
 * Unlike the source, no dummy vertex buffer is bound: the burn vertex shaders generate their
 * positions from `gl_VertexID`, so the pipelines use [com.mojang.blaze3d.vertex.DefaultVertexFormat.EMPTY]
 * (same idiom as the hawen ESP passes).
 */
object BurningHandsRenderer {

    /**
     * Private submit-node storage that receives the second, identical hand submission.
     * Flushed once per frame into HAND_MASK by [flushCapturedGeometry]; cleared
     * defensively whenever the capture cannot run so stale nodes never accumulate.
     */
    private val submitStorage = SubmitNodeStorage()

    /**
     * Private dispatcher wrapping [submitStorage], reusing the shared buffer sources.
     * Constructed like `GameRenderer`'s own dispatcher (same constructor arguments).
     * NEVER closed — it shares the game's buffer sources, so [AutoCloseable.close] would
     * tear down vanilla state; it simply lives as long as the game.
     */
    private var featureDispatcher: FeatureRenderDispatcher? = null

    /**
     * Hand silhouette + colors, captured with depth so held items occlude correctly.
     * Managed manually (like the trail targets): cleared exactly ONCE per frame in
     * [beginMaskCapture], right before the private flush — never between the capture
     * and [runPasses].
     */
    private var maskTarget: TextureTarget? = null

    /** Trail accumulation targets. NOT auto-cleared — the trail must persist across frames. */
    private var trailTargets: Array<TextureTarget>? = null

    /** Glow buffer; fully overwritten every frame by the REPLACE-blended glow pass. */
    private var glowTarget: TextureTarget? = null

    /** Persistent single-frame UBO (64 bytes std140), written once per frame in [runPasses]. */
    private val burnUbo by lazy { ClientUniformDefine.BURN_DATA.createSingleBuffer() }

    private val linearSampler by lazy {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    private var trailValid = false
    private var writeTrailA = true
    private var failureLogged = false

    /**
     * The camera rotation matrix (`CameraRenderState.viewRotationMatrix`) that vanilla passes
     * to `GameRenderer.renderItemInHand`, captured by the mixin at the hand submit.
     *
     * `renderItemInHand` bakes its INVERSE into the hand pose (`poseStack.mulPose(R.invert())`)
     * and compensates by multiplying the model-view stack with R — but it pops that push
     * immediately after the submit, well before the geometry is rasterized. Our flush must
     * therefore supply R itself: without it the captured hands keep an uncancelled R⁻¹ and
     * end up locked to the world instead of to the camera (they coincide with the real hands
     * only where R is the identity, i.e. yaw 180 / pitch 0).
     *
     * Reading the model-view at the rasterization point does NOT work — it is the identity
     * there (measured), which is exactly why the previous approach was a no-op.
     */
    private var vanillaModelView: Matrix4f? = null
    private var vanillaProjection: GpuBufferSlice? = null
    private var vanillaProjectionType: ProjectionType? = null

    /** One-shot checkpoint messages (re-armed on [reset]) so a silent death is visible once. */
    private val checkpointsLogged = HashSet<String>()

    private fun logCheckpoint(key: String, message: String) {
        if (checkpointsLogged.add(key)) {
            logger.info("[BurningHands] $message")
        }
    }

    private fun formatMat4(m: Matrix4f): String = buildString {
        append('[')
        for (row in 0..3) {
            if (row > 0) append(" | ")
            for (col in 0..3) {
                append(String.format("%.3f", m.get(row, col)))
                if (col < 3) append(' ')
            }
        }
        append(']')
    }

    /** Trail decay is hardcoded in the source (not exposed as a setting). */
    private const val TRAIL_DECAY = 0.94f

    /**
     * Wrap period of the shader clock. One hour rather than the 100 s the source used: the shaders
     * animate straight off this value, so every wrap is a visible jump in the fire. It cannot be
     * unbounded either — as a float, seconds-since-epoch has ~0.06 s of resolution, which would
     * quantize the animation outright.
     */
    private const val TIME_WRAP_MS = 3_600_000L

    /** Start of the shader-clock epoch; re-seeded by [reset]. */
    private var timeOriginMs = 0L

    @JvmStatic
    fun canCapture(): Boolean =
        ModuleBurningHands.running &&
            mc.player != null &&
            mc.level != null &&
            mc.mainRenderTarget.colorTextureView != null &&
            RenderSystem.isOnRenderThread()

    /** The private storage the mixin submits the second, identical hand copy into. */
    @JvmStatic
    fun submitStorage(): SubmitNodeStorage = submitStorage

    private fun dispatcher(gameRenderState: GameRenderState): FeatureRenderDispatcher =
        featureDispatcher ?: FeatureRenderDispatcher(
            submitStorage,
            mc.modelManager,
            mc.gameRenderer.renderBuffers.bufferSource(),
            mc.atlasManager,
            mc.gameRenderer.renderBuffers.outlineBufferSource(),
            mc.gameRenderer.renderBuffers.crumblingBufferSource(),
            mc.font,
            gameRenderState,
        ).also { featureDispatcher = it }

    /**
     * Captures the state the hand copy must be rasterized under: the camera rotation matrix
     * [viewRotation] vanilla hands to `renderItemInHand`, plus the hand projection that is
     * already active at that point (`renderLevel` sets it right before the call and nothing
     * touches it again until the flush). Called by the mixin at the second hand submit.
     */
    @JvmStatic
    fun stashHandViewMatrix(viewRotation: Matrix4fc) {
        if (!canCapture()) {
            return
        }
        val mv = Matrix4f(viewRotation)
        vanillaModelView = mv
        vanillaProjection = RenderSystem.getProjectionMatrixBuffer()
        vanillaProjectionType = RenderSystem.getProjectionType()
        logCheckpoint(
            "vanilla-state",
            "hand view matrix: modelview=${formatMat4(mv)}, projectionType=${RenderSystem.getProjectionType()}"
        )
    }

    /**
     * Flushes the captured hand nodes into HAND_MASK with the camera rotation as the model-view
     * (see [vanillaModelView]) and the stashed hand projection, applied around our OWN
     * `endBatch` — the call that actually rasterizes. Restores the previous state afterwards
     * and runs the shader passes. Called by the mixin right after vanilla's own hand
     * rasterization.
     */
    @JvmStatic
    fun flushCapturedGeometry(gameRenderState: GameRenderState) {
        if (!canCapture()) {
            submitStorage.clear()
            return
        }
        val mv = vanillaModelView
        val proj = vanillaProjection
        val projType = vanillaProjectionType
        if (mv == null || proj == null || projType == null) {
            submitStorage.clear()
            logCheckpoint("no-state", "flush skipped: hand view matrix not captured")
            return
        }
        if (!beginMaskCapture()) {
            submitStorage.clear()
            return
        }
        val stack = RenderSystem.getModelViewStack()
        stack.pushMatrix()
        stack.set(mv)
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(proj, projType)
        try {
            logCheckpoint("flush-state", "flush modelview=${formatMat4(RenderSystem.getModelViewMatrix())}")
            dispatcher(gameRenderState).renderAllFeatures()
            mc.gameRenderer.renderBuffers.bufferSource().endBatch()
        } finally {
            endMaskCapture()
            stack.popMatrix()
            RenderSystem.restoreProjectionMatrix()
        }
        runPasses()
    }

    /** Drops captured nodes without rendering (capture aborted this frame). */
    @JvmStatic
    fun clearCapturedGeometry() {
        submitStorage.clear()
    }

    /**
     * Prepares all targets and redirects the render output into HAND_MASK.
     * Must be paired with [endMaskCapture].
     */
    @JvmStatic
    fun beginMaskCapture(): Boolean {
        if (!canCapture()) {
            return false
        }
        return try {
            val mask = ensureMaskTarget()
            ensureSizedTargets()
            // The ONLY clear of the frame: our private flush draws into the mask
            // right after this, and nothing touches the mask again until runPasses().
            mask.clearColorAndDepth()
            RenderSystem.outputColorTextureOverride = mask.colorTextureView
            RenderSystem.outputDepthTextureOverride = mask.depthTextureView
            val ok = RenderSystem.outputColorTextureOverride != null && RenderSystem.outputDepthTextureOverride != null
            if (ok) {
                logCheckpoint("capture", "mask capture started (override set, ${mask.width}x${mask.height})")
            } else {
                logCheckpoint("capture-fail", "mask capture FAILED: texture views are null")
            }
            ok
        } catch (t: Throwable) {
            logCheckpoint("capture-throw", "mask capture threw: $t")
            endMaskCapture()
            false
        }
    }

    @JvmStatic
    fun endMaskCapture() {
        RenderSystem.outputColorTextureOverride = null
        RenderSystem.outputDepthTextureOverride = null
    }

    /**
     * Runs the glow -> trail -> composite chain onto the main framebuffer.
     * Call after the private flush captured the hands into HAND_MASK and the overrides
     * were restored. No blit: vanilla's own hands are already on screen.
     */
    @JvmStatic
    fun runPasses() {
        val mask = maskTarget ?: return logCheckpoint("no-mask", "runPasses: early return (no mask target)")
        val glow = glowTarget ?: return logCheckpoint("no-glow", "runPasses: early return (no glow target)")
        val trails = trailTargets ?: return logCheckpoint("no-trails", "runPasses: early return (no trail targets)")
        val maskView = mask.colorTextureView ?: return logCheckpoint("no-maskview", "runPasses: early return (mask colorTextureView null)")
        val glowView = glow.colorTextureView ?: return logCheckpoint("no-glowview", "runPasses: early return (glow colorTextureView null)")
        mc.mainRenderTarget.colorTextureView ?: return logCheckpoint("no-mainview", "runPasses: early return (main colorTextureView null)")
        logCheckpoint("passes", "runPasses: views ok, compositing ${mask.width}x${mask.height}")

        try {
            if (!trailValid) {
                for (target in trails) {
                    target.colorTexture?.clearColor(0)
                }
                trailValid = true
                writeTrailA = true
            }

            val module = ModuleBurningHands
            val width = mask.width
            val height = mask.height
            val fire = module.fireColor

            // Seconds since the effect started, anchored to the module rather than the wall clock,
            // so the wrap lands an hour after enabling instead of at an arbitrary moment.
            val nowMs = System.currentTimeMillis()
            if (timeOriginMs == 0L) {
                timeOriginMs = nowMs
            }
            val time = ((nowMs - timeOriginMs) % TIME_WRAP_MS).toFloat() / 1000.0f

            burnUbo.writeStd140 {
                // Screen
                putFloat(width.toFloat())
                putFloat(height.toFloat())
                putFloat(time)
                putFloat(if (module.fillHands) 1.0f else 0.0f)
                // GlowParams
                putFloat(module.radius)
                putFloat(module.strength)
                putFloat(module.speed)
                putFloat(module.colorMix)
                // TrailParams (flowSpeed mirrors the fire speed, as in the source)
                putFloat(TRAIL_DECAY)
                putFloat(module.flameHeight)
                putFloat(module.speed)
                putFloat(module.trailStrength)
                // FireParams (w = FireColor's alpha: overall effect opacity, applied once in
                // the composite pass — the trail target accumulates across frames, so scaling
                // it there would compound. The slot previously held a duplicate of Strength,
                // which the composite now reads from GlowParams.y instead.)
                putFloat(fire.r / 255.0f)
                putFloat(fire.g / 255.0f)
                putFloat(fire.b / 255.0f)
                putFloat(fire.a / 255.0f)
            }

            // 1. Glow: hand mask -> glow buffer.
            glow.createRenderPass({ "BurningHands Glow" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.BurnGlow)
                pass.bindTexture("HandSampler", maskView, linearSampler)
                pass.bindTexture("MaskSampler", maskView, linearSampler)
                pass.setUniform(ClientUniformDefine.BURN_DATA.uboName, burnUbo)
                pass.draw(0, 6)
            }

            // 2. Trail: previous trail + glow -> next trail (ping-pong).
            val previousTrail = trails[if (writeTrailA) 1 else 0]
            val nextTrail = trails[if (writeTrailA) 0 else 1]
            val previousTrailView = previousTrail.colorTextureView ?: return
            val nextTrailView = nextTrail.colorTextureView ?: return

            nextTrail.createRenderPass({ "BurningHands Trail" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.BurnTrail)
                pass.bindTexture("TrailSampler", previousTrailView, linearSampler)
                pass.bindTexture("GlowSampler", glowView, linearSampler)
                pass.bindTexture("HandSampler", maskView, linearSampler)
                pass.bindTexture("MaskSampler", maskView, linearSampler)
                pass.setUniform(ClientUniformDefine.BURN_DATA.uboName, burnUbo)
                pass.draw(0, 6)
            }

            // 3. Composite: glow + trail additively onto the main framebuffer (color-only pass).
            mc.mainRenderTarget.createRenderPass({ "BurningHands Composite" }, useDepthAttachment = false)
                .use { pass ->
                    pass.setPipeline(ClientRenderPipelines.BurnComposite)
                    pass.bindTexture("MaskSampler", maskView, linearSampler)
                    pass.bindTexture("GlowSampler", glowView, linearSampler)
                    pass.bindTexture("TrailSampler", nextTrailView, linearSampler)
                    pass.setUniform(ClientUniformDefine.BURN_DATA.uboName, burnUbo)
                    pass.draw(0, 6)
                }

            writeTrailA = !writeTrailA
        } catch (t: Throwable) {
            trailValid = false
            if (!failureLogged) {
                failureLogged = true
                logger.error("BurningHands pass failed — the fire effect is disabled until the next reset", t)
            }
        } finally {
            endMaskCapture()
        }
    }

    /** Resets all temporal state (module toggle, capture abort). GPU targets are kept. */
    @JvmStatic
    fun reset() {
        trailValid = false
        writeTrailA = true
        failureLogged = false
        timeOriginMs = 0L
        vanillaModelView = null
        vanillaProjection = null
        vanillaProjectionType = null
        checkpointsLogged.clear()
        endMaskCapture()
    }

    /** Releases all GPU targets (module disable). */
    @JvmStatic
    fun release() {
        reset()
        submitStorage.clear()
        maskTarget?.destroyBuffers()
        maskTarget = null
        glowTarget?.destroyBuffers()
        glowTarget = null
        trailTargets?.forEach { it.destroyBuffers() }
        trailTargets = null
    }

    /** Keeps the hand mask at window size (resize recreates the target). */
    private fun ensureMaskTarget(): TextureTarget {
        val width = mc.window.width.coerceAtLeast(1)
        val height = mc.window.height.coerceAtLeast(1)
        val current = maskTarget
        if (current == null || current.width != width || current.height != height) {
            current?.destroyBuffers()
            maskTarget = TextureTarget("LiquidBounce BurningHands HandMask", width, height, true)
        }
        return maskTarget!!
    }

    /**
     * Keeps the glow buffer and the trail ping-pong pair at
     * [ModuleBurningHands.renderScale] of the window size. A resize — or a change of the
     * scale — invalidates the trail history, exactly like the source does on resize.
     *
     * The shaders address their neighbourhood through `Screen.xy`, which stays the FULL
     * window size, so all offsets remain expressed in screen pixels and the effect keeps its
     * on-screen dimensions no matter what these targets are sized at.
     */
    private fun ensureSizedTargets() {
        val scale = ModuleBurningHands.renderScale.coerceIn(0.25f, 1f)
        val width = (mc.window.width * scale).toInt().coerceAtLeast(1)
        val height = (mc.window.height * scale).toInt().coerceAtLeast(1)

        val glow = glowTarget
        if (glow == null || glow.width != width || glow.height != height) {
            glow?.destroyBuffers()
            glowTarget = TextureTarget("LiquidBounce BurningHands Glow", width, height, false)
        }

        val trails = trailTargets
        if (trails == null || trails[0].width != width || trails[0].height != height) {
            trails?.forEach { it.destroyBuffers() }
            trailTargets = arrayOf(
                TextureTarget("LiquidBounce BurningHands Trail A", width, height, false),
                TextureTarget("LiquidBounce BurningHands Trail B", width, height, false),
            )
            trailValid = false
            writeTrailA = true
        }
    }
}
