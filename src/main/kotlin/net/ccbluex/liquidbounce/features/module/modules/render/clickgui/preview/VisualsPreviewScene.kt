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
package net.ccbluex.liquidbounce.features.module.modules.render.clickgui.preview

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.PoseStack
import net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleTracers
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspBloomMode
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspBoxMode
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspLegacy2DMode
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspShaderMode
import net.ccbluex.liquidbounce.features.module.modules.render.esp.modes.EspShaderOutlineMode
import net.ccbluex.liquidbounce.features.module.modules.render.hawenesp.KawaseBlurPyramid
import net.ccbluex.liquidbounce.features.module.modules.render.clickgui.LeetClickGuiSettings
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.clearColor
import net.ccbluex.liquidbounce.utils.render.clearColorAndDepth
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.SubmitNodeStorage
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.world.entity.LivingEntity
import net.ccbluex.liquidbounce.render.engine.type.Rect
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/**
 * The offscreen scene behind the ClickGUI visuals preview.
 *
 * The panel cannot simply call the render modules inline: the GUI is DEFERRED (elements are
 * submitted to `guiRenderState` and rasterized later), while the modules draw IMMEDIATELY
 * through world pipelines. An inline call would land on the main target during the extract
 * phase and be painted over by the GUI itself.
 *
 * So the preview renders its own little world into a private [TextureTarget] — stand-in player
 * and module effects together, under ONE projection so they occlude each other correctly — and
 * the panel blits that texture as an ordinary GUI element. By the time the GUI pass samples it,
 * the content is already there.
 *
 * The entity goes in through a private [SubmitNodeStorage] + [FeatureRenderDispatcher] flushed
 * under [RenderSystem.outputColorTextureOverride], the same capture pattern the BurningHands
 * renderer uses.
 */
object VisualsPreviewScene {

    /**
     * Camera framing of the stand-in, in blocks.
     *
     * The visible height at the model plane is `2 * DISTANCE * tan(FOV/2)`. At 3.4 that is
     * ~2.61 blocks against the player's 1.8, so the figure fills roughly 70% of the stage and
     * keeps air on both sides. The camera sits a little above the model's own centre (0.9) so
     * the extra room lands on TOP, where the nametag anchor (~2.17) has to fit.
     */
    private const val CAMERA_HEIGHT = 1.05

    /**
     * FIXED framing distance of the stage camera. The Distance setting does NOT move it —
     * moving the camera would shrink the model and force a magnifying crop, which is just a
     * blurry zoom. See [ensureSilhouette] for how the simulated distance is produced instead.
     */
    private const val CAMERA_DISTANCE = 3.4f
    private const val FOV_DEGREES = 42.0f
    private const val NEAR = 0.05f
    private const val FAR = 64.0f

    /** Floor for the simulated-distance silhouette so the blur always has pixels to work on. */
    private const val MIN_SILHOUETTE_PX = 24

    private var target: TextureTarget? = null
    private var projectionBuffer: ProjectionMatrixBuffer? = null

    /**
     * View-projection of the LAST rendered frame, in the stand-in's own space (feet at the
     * origin). [projectToStage] uses it so screen-space effects — the nametag above all — can
     * be anchored to the model without going through the main camera, which this scene does
     * not share.
     */
    private val viewProjection = Matrix4f()

    private val submitStorage = SubmitNodeStorage()
    private var featureDispatcher: FeatureRenderDispatcher? = null

    /**
     * Stage-sized resources for ESP's shader modes. They cannot share the modules' own targets:
     * those are built at WINDOW size for the main render target, while these must match the
     * preview stage.
     */
    private var silhouette: TextureTarget? = null
    private val previewPyramid by lazy { KawaseBlurPyramid("LiquidBounce Visuals Preview Kawase") }

    private var failureLogged = false

    private val linearSampler by lazy {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    /**
     * Renders the stand-in and the enabled render modules into the private target and returns
     * a setup the caller can blit, or null when the scene could not be produced (no level, no
     * GPU resources, or a render failure — the panel then falls back to an empty stage).
     */
    fun render(width: Int, height: Int, yaw: Float, pitch: Float): TextureSetup? {
        if (width <= 0 || height <= 0 || !RenderSystem.isOnRenderThread()) {
            return null
        }
        val entity = VisualsPreview.previewPlayer() ?: return null

        return try {
            val scene = ensureTarget(width, height)
            drawScene(scene, entity, yaw, pitch)
            scene.colorTextureView?.let { TextureSetup.singleTexture(it, linearSampler) }
        } catch (t: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                logger.error("Visuals preview scene failed - the preview stage stays empty", t)
            }
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
            null
        }
    }

    private fun drawScene(scene: TextureTarget, entity: LivingEntity, yaw: Float, pitch: Float) {
        scene.clearColorAndDepth()

        val projection = perspective(scene.width, scene.height)
        val slice = projectionBufferOf().getBuffer(projection)

        RenderSystem.outputColorTextureOverride = scene.colorTextureView
        RenderSystem.outputDepthTextureOverride = scene.depthTextureView
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(slice, ProjectionType.PERSPECTIVE)

        val modelViewStack = RenderSystem.getModelViewStack()
        modelViewStack.pushMatrix()
        modelViewStack.identity()
        try {
            // Camera sits in front of the stand-in and looks at its chest; the pose carries the
            // view transform because the scene has no real Camera of its own.
            val view = Matrix4f()
                .translate(0f, -CAMERA_HEIGHT.toFloat(), -CAMERA_DISTANCE)
                .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
                .rotateY(Math.toRadians(yaw.toDouble()).toFloat())
            projection.mul(view, viewProjection)

            val pose = PoseStack()
            pose.mulPose(view)


            submitEntity(entity, pose)
            drawModuleEffects(entity, pose, view, yaw)

            // The shader modes address their targets EXPLICITLY (silhouette, pyramid levels,
            // then the stage). The output override outranks an explicit render target, so it
            // has to come off first — otherwise the opaque silhouette box lands straight on
            // the stage over the model, which reads as a flat box instead of a glow.
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
            // Runs after the model is in the stage, so the glow composites over it.
            drawShaderEffects(entity, pose, scene)
        } finally {
            modelViewStack.popMatrix()
            RenderSystem.restoreProjectionMatrix()
            RenderSystem.outputColorTextureOverride = null
            RenderSystem.outputDepthTextureOverride = null
        }
    }

    /**
     * Submits the stand-in into the private storage and rasterizes it. `renderAllFeatures`
     * only builds the meshes — `endBatch` is what actually draws, and it must run while the
     * output overrides still point at the scene target.
     */
    private fun submitEntity(entity: LivingEntity, pose: PoseStack) {
        val dispatcher = mc.entityRenderDispatcher
        val renderState = dispatcher.extractEntity(entity, 1.0f)
        val cameraState = mc.gameRenderer.gameRenderState.levelRenderState.cameraRenderState

        submitStorage.clear()
        dispatcher.submit(renderState, cameraState, 0.0, 0.0, 0.0, pose, submitStorage)

        featureDispatcher().renderAllFeatures()
        mc.gameRenderer.renderBuffers.bufferSource().endBatch()
        submitStorage.clear()
    }

    /**
     * Runs the enabled render modules' OWN drawing code for the stand-in, at the pose origin.
     * Each mode exposes a preview entry point that reuses its world-path drawing, so the panel
     * shows the real settings rather than a re-implementation of them.
     */
    private fun drawModuleEffects(entity: LivingEntity, pose: PoseStack, view: Matrix4f, yaw: Float) {
        if (!ModuleESP.running && !ModuleTracers.running) {
            return
        }
        // The scene's camera in stand-in space: the tracer starts near it, the way the world
        // path starts at Vec3f.eyeVector of the real camera.
        val eye = view.invert(Matrix4f()).transformPosition(Vector3f(0f, -0.30f, 0.80f))

        renderEnvironmentForWorld(pose) {
            if (ModuleESP.running) {
                when (ModuleESP.modes.activeMode) {
                    EspBoxMode -> with(EspBoxMode) { drawPreview(entity, EspBoxMode.previewBox(entity)) }
                    EspBloomMode -> with(EspBloomMode) { drawPreview(entity, EspBoxMode.previewBox(entity)) }
                    EspLegacy2DMode -> with(EspLegacy2DMode) { drawPreview(entity, yaw) }
                    // 2D draws in GUI space (handled by the panel); Glow has no drawing of its
                    // own (vanilla outline pass) and the Shader modes need their full FBO
                    // chains re-targeted - neither is wired here.
                    else -> Unit
                }
            }
            if (ModuleTracers.running) {
                with(ModuleTracers) { drawPreviewTracer(entity, Vec3f(eye.x, eye.y, eye.z)) }
            }
        }
    }

    /**
     * Projects a point of the stand-in's space (feet at the origin) into panel coordinates,
     * using the matrices of the last rendered frame. Returns null when the point lands behind
     * the camera. Y is returned already flipped for the GUI (v=0 at the top), matching how the
     * scene texture is blitted.
     */
    fun projectToStage(
        x: Float, y: Float, z: Float,
        stageX: Float, stageY: Float, stageW: Float, stageH: Float,
    ): FloatArray? {
        val clip = viewProjection.transform(Vector4f(x, y, z, 1f))
        if (clip.w <= 1.0e-4f) {
            return null
        }
        val ndcX = clip.x / clip.w
        val ndcY = clip.y / clip.w
        return floatArrayOf(
            stageX + (ndcX * 0.5f + 0.5f) * stageW,
            stageY + (0.5f - ndcY * 0.5f) * stageH,
        )
    }

    /**
     * Screen rect of the stand-in's bounding box on the stage — the preview's equivalent of
     * `WorldToScreen.calculateScreenRect`, for the modes that draw in screen space (ESP 2D).
     *
     * Projects all eight corners and takes their bounds, exactly as the world helper does.
     * Returns null when any corner falls behind the camera or the result is degenerate.
     */
    fun projectBoxToStage(
        entity: LivingEntity,
        stageX: Float, stageY: Float, stageW: Float, stageH: Float,
    ): Rect? {
        val dimensions = entity.getDimensions(entity.pose)
        val half = dimensions.width / 2f
        val height = dimensions.height

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for (dx in intArrayOf(-1, 1)) {
            for (dz in intArrayOf(-1, 1)) {
                for (y in floatArrayOf(0f, height)) {
                    val at = projectToStage(dx * half, y, dz * half, stageX, stageY, stageW, stageH)
                        ?: return null
                    minX = minOf(minX, at[0]); maxX = maxOf(maxX, at[0])
                    minY = minOf(minY, at[1]); maxY = maxOf(maxY, at[1])
                }
            }
        }
        if (maxX - minX < 0.5f || maxY - minY < 0.5f) {
            return null
        }
        return Rect(minX, minY, maxX, maxY)
    }

    /**
     * ESP's shader modes: their own silhouette -> Kawase -> composite chain, run into the
     * stage target at stage size instead of the window-sized main target.
     */
    private fun drawShaderEffects(entity: LivingEntity, pose: PoseStack, scene: TextureTarget) {
        val mode = ModuleESP.modes.activeMode
        if (!ModuleESP.running || (mode !== EspShaderMode && mode !== EspShaderOutlineMode)) {
            return
        }
        val silhouetteRt = ensureSilhouette(scene.width, scene.height)
        silhouetteRt.colorTexture?.clearColor(0)
        val box = EspBoxMode.previewBox(entity)
        if (mode === EspShaderMode) {
            EspShaderMode.renderPreview(entity, box, pose, silhouetteRt, previewPyramid, scene)
        } else {
            EspShaderOutlineMode.renderPreview(entity, box, pose, silhouetteRt, scene)
        }
    }

    /**
     * Silhouette target for the shader modes, sized to SIMULATE the viewing distance.
     *
     * The character of these effects — hard box up close, smoky cloud far away — comes from
     * the ratio of the silhouette's pixel size to the blur radius. The blur radius is fixed
     * in texels (the Kawase pyramid halves a fixed number of times), so shrinking the target
     * by `MIN / distance` is exactly equivalent to standing further away. The composite is a
     * fullscreen pass, so it upscales the result onto the stage for free — and upscaling an
     * already-blurred image costs nothing visually.
     *
     * This is why the stage camera stays put: rendering the MODEL further away and magnifying
     * it back would produce the same ratio but destroy the model's sharpness.
     */
    private fun ensureSilhouette(stageWidth: Int, stageHeight: Int): TextureTarget {
        val scale = LeetClickGuiSettings.PREVIEW_DISTANCE_MIN / LeetClickGuiSettings.previewDistance
        val width = (stageWidth * scale).toInt().coerceAtLeast(MIN_SILHOUETTE_PX)
        val height = (stageHeight * scale).toInt().coerceAtLeast(MIN_SILHOUETTE_PX)

        val current = silhouette
        if (current == null || current.width != width || current.height != height) {
            current?.destroyBuffers()
            return TextureTarget("LiquidBounce Visuals Preview Silhouette", width, height, false)
                .also { silhouette = it }
        }
        return current
    }

    private fun featureDispatcher(): FeatureRenderDispatcher =
        featureDispatcher ?: FeatureRenderDispatcher(
            submitStorage,
            mc.modelManager,
            mc.gameRenderer.renderBuffers.bufferSource(),
            mc.atlasManager,
            mc.gameRenderer.renderBuffers.outlineBufferSource(),
            mc.gameRenderer.renderBuffers.crumblingBufferSource(),
            mc.font,
            mc.gameRenderer.gameRenderState,
        ).also { featureDispatcher = it }

    private fun perspective(width: Int, height: Int): Matrix4f =
        Matrix4f().perspective(
            Math.toRadians(FOV_DEGREES.toDouble()).toFloat(),
            width.toFloat() / height.toFloat(),
            NEAR, FAR,
        )

    private fun projectionBufferOf(): ProjectionMatrixBuffer =
        projectionBuffer ?: ProjectionMatrixBuffer("LiquidBounce Visuals Preview")
            .also { projectionBuffer = it }

    private fun ensureTarget(width: Int, height: Int): TextureTarget {
        val current = target
        if (current == null || current.width != width || current.height != height) {
            current?.destroyBuffers()
            return TextureTarget("LiquidBounce Visuals Preview", width, height, true)
                .also { target = it }
        }
        return current
    }

    /** Releases GPU resources (GUI closed / world change). */
    fun release() {
        target?.destroyBuffers()
        target = null
        projectionBuffer?.close()
        projectionBuffer = null
        submitStorage.clear()
        failureLogged = false
        VisualsPreview.release()
    }
}
