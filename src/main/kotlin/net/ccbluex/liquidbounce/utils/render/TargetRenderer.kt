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
package net.ccbluex.liquidbounce.utils.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.ccbluex.fastutil.objectObjectMapOf
import net.ccbluex.fastutil.toEnumSet
import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.utils.TextureMode
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.FontManager
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawCircleOutline
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawCustomMeshTextured
import net.ccbluex.liquidbounce.render.drawGradientCircle
import net.ccbluex.liquidbounce.render.drawSquareTexture
import net.ccbluex.liquidbounce.render.drawTexQuad
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.font.HorizontalAnchor
import net.ccbluex.liquidbounce.render.engine.font.VerticalAnchor
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.utils.AnimatedValueGroup
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.utils.text.asPlainText
import net.ccbluex.liquidbounce.utils.client.clientStartDurationMs
import net.ccbluex.liquidbounce.utils.text.plus
import net.ccbluex.liquidbounce.utils.math.toRadians
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.entity.lastRenderPos
import net.ccbluex.liquidbounce.utils.math.minus
import net.ccbluex.liquidbounce.utils.render.WorldToScreen.calculateScreenPos
import net.ccbluex.liquidbounce.utils.render.putVec4
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.network.chat.Style
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector2f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A target tracker to choose the best enemy to attack
 */
class TargetRenderer(
    owner: ToggleableValueGroup,
    val target: () -> Entity?,
) : ToggleableValueGroup(owner, "TargetRendering", true) {

    constructor(module: ToggleableValueGroup, targetTracker: TargetTracker) : this(module, targetTracker::target)

    init {
        doNotIncludeAlways()
    }

    private val appearance = modes(owner, "Mode", 2) {
        arrayOf(
            TargetRenderAppearance.World.Legacy(it),
            TargetRenderAppearance.World.Circle(owner, it),
            TargetRenderAppearance.World.Image(owner, it),
            TargetRenderAppearance.World.GlowingCircle(owner, it),
            TargetRenderAppearance.World.Ghost(it),
            TargetRenderAppearance.World.AuraRing(it),
            TargetRenderAppearance.World.Marker(owner, it),
            TargetRenderAppearance.World.Spirits(it),
            TargetRenderAppearance.World.Spiral(it),
            TargetRenderAppearance.World.Worm(it),
            TargetRenderAppearance.World.ParticleCircle(it),
            TargetRenderAppearance.World.GhostOrbits(it),
            TargetRenderAppearance.World.Crystals(it),
            TargetRenderAppearance.World.Fireflies(it),
            TargetRenderAppearance.Gui.Text(owner, it),
            TargetRenderAppearance.Gui.Arrow(it),
            TargetRenderAppearance.Gui.Stipple(it),
        )
    }

    @Suppress("unused")
    private val worldRenderHandler = handler<WorldRenderEvent> { event ->
        val mode = appearance.activeMode as? TargetRenderAppearance.World ?: return@handler

        val target = target() ?: return@handler

        with(mode) {
            renderEnvironmentForWorld(event.matrixStack) {
                render(target, event.partialTicks)
            }
        }
    }

    @Suppress("unused")
    private val guiRenderHandler = handler<OverlayRenderEvent> { event ->
        val mode = appearance.activeMode as? TargetRenderAppearance.Gui ?: return@handler

        val target = target() ?: return@handler

        with(mode) {
            event.context.render(target, event.tickDelta)
        }
    }

}

private sealed class TargetRenderAppearance<Ctx : Any>(name: String) : Mode(name) {
    abstract fun Ctx.render(entity: Entity, partialTicks: Float)

    sealed class World(name: String) : TargetRenderAppearance<WorldRenderEnvironment>(name) {

        class Ghost(override val parent: ModeValueGroup<*>) : World("Ghost") {

            private val color by color("Color", Color4b.BLUE)
            private val size by float("Size", 0.5f, 0.4f..0.7f)
            private val length by int("Length", 25, 15..40)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                poseStack.pushPose()

                val interpolated = entity.lastRenderPos().lerp(entity.position(), partialTicks.toDouble())
                    .add(0.2, 1.25, 0.0)

                poseStack.translate(interpolated - camera.position())

                drawParticle(
                    { sin, cos -> Vec3(sin, cos, -cos) },
                    { sin, cos -> Vec3(-sin, -cos, cos) }
                )

                drawParticle(
                    { sin, cos -> Vec3(-sin, sin, -cos) },
                    { sin, cos -> Vec3(sin, -sin, cos) }
                )

                drawParticle(
                    { sin, cos -> Vec3(-sin, -sin, cos) },
                    { sin, cos -> Vec3(sin, sin, -cos) }
                )

                poseStack.popPose()
            }

            private inline fun WorldRenderEnvironment.drawParticle(
                translationsBefore: PoseStack.(Double, Double) -> Vec3,
                translateAfter: PoseStack.(Double, Double) -> Vec3
            ) {
                val radius = 0.67
                val distance = 10.0 + (length * 0.2)
                val alphaFactor = 15

                for (i in 0..<length) {
                    val angle: Double = 0.15f * (clientStartDurationMs - (i * distance)) / (30)
                    val sin = sin(angle) * radius
                    val cos = cos(angle) * radius

                    with(poseStack) {
                        translate(translationsBefore(sin, cos))

                        translate(-size / 2.0, -size / 2.0, 0.0)
                        mulPose(Axis.YP.rotationDegrees(-camera.yRot()))
                        mulPose(Axis.XP.rotationDegrees(camera.xRot()))
                        translate(size / 2.0, size / 2.0, 0.0)
                    }

                    val alpha = Mth.clamp(color.a - (i * alphaFactor), 0, color.a)
                    val renderColor = color.alpha(alpha)

                    drawSquareTexture(ghostModeTexture, size, renderColor.argb)

                    with(poseStack) {
                        translate(-size / 2.0, -size / 2.0, 0.0)
                        mulPose(Axis.XP.rotationDegrees(-camera.xRot()))
                        mulPose(Axis.YP.rotationDegrees(camera.yRot()))
                        translate(size / 2.0, size / 2.0, 0.0)

                        translate(translateAfter(sin, cos))
                    }
                }
            }
        }

        class Legacy(override val parent: ModeValueGroup<*>) : World("Legacy") {

            private val size by float("Size", 0.5f, 0.1f..2f)

            private val height by float("Height", 0.1f, 0.02f..2f)

            private val color by color("Color", defaultColor)

            private val extraYOffset by float("ExtraYOffset", 0.1f, 0f..1f)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val box = AABB(
                    -size.toDouble(), 0.0, -size.toDouble(),
                    size.toDouble(), height.toDouble(), size.toDouble()
                )

                val pos = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight.toDouble() + extraYOffset.toDouble(), 0.0)

                withPositionRelativeToCamera(pos) {
                    drawBox(box, color)
                }
            }
        }

        class Image(owner: ToggleableValueGroup, override val parent: ModeValueGroup<*>) : World("Image") {

            private val textureMode = modes("Source", 0) {
                arrayOf(
                    TextureMode.Custom(it),
                    TextureMode.Builtin(it, PresetTexture.MARKER1, PresetTexture.entries.toEnumSet())
                )
            }
            private val scale by vec2f("Scale", Vector2f(1f, 1f))
            private val color by color("ColorModulator", Color4b.WHITE)
            private val rotate = tree(object : AnimatedValueGroup("Rotate") {
                override val curve = curve("Curve") {
                    "Progress" x 0f..1f
                    "Degrees" y -180f..180f
                    points(Vector2f(0f, 0f), Vector2f(1f, 0f))
                }
            })

            private val heightMode = modes(owner, "HeightMode") {
                arrayOf(
                    HeightMode.Feet(it),
                    HeightMode.Top(it),
                    HeightMode.Relative(it),
                    HeightMode.Health(it),
                    HeightMode.Animated(it),
                )
            }

            private enum class PresetTexture(override val tag: String, val path: String) : TextureMode.Builtin.Preset {
                MARKER1("Marker1", "target_renderer/target.png"),
                MARKER2("Marker2", "target_renderer/target2.png");

                override val texture = LiquidBounce.resource(this.path)
                    .readNativeImage().asTexture { "TargetRenderer Image $tag" }
            }

            private val quaternion = Quaternionf()

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val texture = textureMode.activeMode.texture ?: return

                val height = heightMode.activeMode.getHeight(entity, partialTicks)
                val pos = entity.interpolateCurrentPosition(partialTicks).add(0.0, height, 0.0)

                withPositionRelativeToCamera(pos) {
                    poseStack.mulPose(camera.rotation())
                    poseStack.mulPose(
                        quaternion.scaling(1f)
                            .rotateLocalZ(rotate.current().toRadians())
                    )
                    poseStack.last().scale(scale.x(), scale.y(), 1f)
                    drawTexQuad(texture, color.argb)
                }
            }
        }

        class Circle(owner: ToggleableValueGroup, override val parent: ModeValueGroup<*>) : World("Circle") {

            private val radius by float("Radius", 0.85f, 0.1f..2f)
            private val innerRadius by float("InnerRadius", 0f, 0f..2f)
                .onChange { min(radius, it) }

            private val heightMode = modes(owner, "HeightMode") {
                arrayOf(
                    HeightMode.Feet(it),
                    HeightMode.Top(it),
                    HeightMode.Relative(it),
                    HeightMode.Health(it),
                    HeightMode.Animated(it),
                )
            }

            private val outerColor by color("OuterColor", defaultColor)
            private val innerColor by color("InnerColor", defaultColor)

            private val outlineColor by color("Color", Color4b.fullAlpha(0x007CFF))

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val height = heightMode.activeMode.getHeight(entity, partialTicks)
                val pos = entity.interpolateCurrentPosition(partialTicks).add(0.0, height, 0.0)

                withPositionRelativeToCamera(pos) {
                    drawGradientCircle(radius, innerRadius, outerColor, innerColor)
                    drawCircleOutline(radius, outlineColor)
                }
            }

        }

        class GlowingCircle(owner: ToggleableValueGroup, override val parent: ModeValueGroup<*>) :
            World("GlowingCircle") {
            private val radius by float("Radius", 0.85f, 0.1f..2f)

            private val heightMode = modes(owner, "HeightMode") {
                arrayOf(
                    HeightMode.Feet(it),
                    HeightMode.Top(it),
                    HeightMode.Relative(it),
                    HeightMode.Health(it),
                    HeightMode.Animated(it),
                )
            }

            private val color by color("OuterColor", defaultColor)
            private val glowColor by color("GlowColor", Color4b.LIQUID_BOUNCE.alpha(0))

            private val glowHeightSetting by float("GlowHeight", 0.3f, -1f..1f)

            private val outlineColor by color("Color", Color4b.fullAlpha(0x007CFF))

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val height = heightMode.activeMode.getHeight(entity, partialTicks)
                val pos = entity.interpolateCurrentPosition(partialTicks).add(0.0, height, 0.0)

                val currentHeightMode = heightMode.activeMode

                val glowHeight = if (currentHeightMode is HeightMode.WithGlow) {
                    currentHeightMode.getGlowHeight(entity, partialTicks) - height
                } else {
                    glowHeightSetting.toDouble()
                }

                withPositionRelativeToCamera(pos) {
                    drawGradientCircle(
                        radius,
                        radius,
                        color,
                        glowColor,
                        Vector3f(0f, glowHeight.toFloat(), 0f)
                    )

                    drawCircle(radius, color)
                    drawCircleOutline(radius, outlineColor)
                }
            }

        }

        /**
         * Tilted ring of glowing orbs orbiting the target. Motion layered for organic feel:
         *  - Orbit angle is `ω·t + A·sin(2π·t/period)` so angular velocity glides.
         *  - Tilt axis itself precesses slowly around vertical — the diagonal direction drifts.
         *  - Per-orb vertical bob with phase offset — the ring undulates rather than pulses flat.
         *  - Per-orb opacity pulse with phase offset — gentle shimmer.
         */
        class AuraRing(override val parent: ModeValueGroup<*>) : World("AuraRing") {

            private val color by color("Color", Color4b(120, 220, 255, 255))
            private val ballCount by int("BallCount", 8, 3..24)
            private val ringRadius by float("RingRadius", 1.6f, 0.5f..6f, "blocks")
            private val ballSize by float("BallSize", 0.18f, 0.05f..0.6f, "blocks")

            private val tilt by float("Tilt", 28f, 0f..80f, "°")
            private val baseSpeed by float("BaseSpeed", 35f, 0f..360f, "°/s")
            private val wobbleAmplitude by float("WobbleAmplitude", 22f, 0f..120f, "°")
            private val wobblePeriod by float("WobblePeriod", 3.5f, 0.4f..15f, "s")
            private val tiltPrecession by float("TiltPrecession", 0.07f, 0f..1f, "rev/s")

            private val bobAmplitude by float("BobAmplitude", 0.08f, 0f..0.5f, "blocks")
            private val bobFrequency by float("BobFrequency", 0.8f, 0f..3f, "Hz")
            private val pulseDepth by float("PulseDepth", 0.25f, 0f..1f)

            private val verticalOffset by float("VerticalOffset", 0.9f, 0f..3f, "blocks")

            /**
             * Packed arena rather than a 3-buffer ring: one uniform block is written per orb, and
             * rotating per orb would fence-block (full CPU↔GPU sync) from the fourth orb onwards.
             */
            private val uboArena by lazy(LazyThreadSafetyMode.NONE) {
                ClientUniformDefine.KILL_WAVE.createArena("TargetRenderer Orb UBO arena")
            }

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val t = (System.nanoTime() / 1_000_000_000.0).toFloat()

                val anchor = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, verticalOffset.toDouble(), 0.0)

                val baseRad = (baseSpeed.toDouble() * PI / 180.0).toFloat()
                val wobbleRad = (wobbleAmplitude.toDouble() * PI / 180.0).toFloat()
                val orbitalAngle = baseRad * t + wobbleRad * sin(2f * PI.toFloat() * t / wobblePeriod)

                val tiltAxisAngle = 2f * PI.toFloat() * tiltPrecession * t
                val tiltAxisX = cos(tiltAxisAngle)
                val tiltAxisZ = sin(tiltAxisAngle)

                val tiltRad = (tilt.toDouble() * PI / 180.0).toFloat()
                val cosTilt = cos(tiltRad)
                val sinTilt = sin(tiltRad)

                val count = ballCount
                val twoPi = 2f * PI.toFloat()
                for (i in 0 until count) {
                    val phase = i.toFloat() / count * twoPi
                    val theta = orbitalAngle + phase

                    val rx = ringRadius * cos(theta)
                    val rz = ringRadius * sin(theta)

                    // Rodrigues about axis k=(tiltAxisX, 0, tiltAxisZ); v=(rx, 0, rz).
                    val tiltedX = rx * cosTilt
                    val tiltedZ = rz * cosTilt
                    val tiltedY = (tiltAxisZ * rx - tiltAxisX * rz) * sinTilt

                    val bob = bobAmplitude * sin(2f * PI.toFloat() * bobFrequency * t + phase * 1.7f)

                    val pos = Vec3(
                        anchor.x + tiltedX,
                        anchor.y + tiltedY + bob,
                        anchor.z + tiltedZ,
                    )

                    val pulse = 0.5f + 0.5f * sin(2f * PI.toFloat() * 0.45f * t + phase * 2.3f)
                    val alphaFactor = (1f - pulseDepth) + pulseDepth * pulse
                    val pulsedColor = color.with(a = (color.a * alphaFactor).toInt().coerceIn(0, 255))

                    drawOrb(pos, ballSize, pulsedColor, t)
                }
            }

            private fun WorldRenderEnvironment.drawOrb(
                pos: Vec3,
                radius: Float,
                orbColor: Color4b,
                time: Float,
            ) {
                val slice = uboArena.allocate(ClientUniformDefine.KILL_WAVE.size)
                slice.writeStd140 {
                    putVec4(0f, time, 1f, 0f)
                    putVec4(orbColor)
                }

                val uniforms = objectObjectMapOf(ClientUniformDefine.KILL_WAVE.uboName, slice)

                withPositionRelativeToCamera(pos) {
                    val verts = AuraRingOrbMesh.triangles
                    drawCustomMesh(
                        pipeline = ClientRenderPipelines.KillWave,
                        uniforms = uniforms,
                    ) { pose: PoseStack.Pose ->
                        var i = 0
                        while (i < verts.size) {
                            addVertex(pose, verts[i] * radius, verts[i + 1] * radius, verts[i + 2] * radius)
                            addVertex(pose, verts[i + 3] * radius, verts[i + 4] * radius, verts[i + 5] * radius)
                            addVertex(pose, verts[i + 6] * radius, verts[i + 7] * radius, verts[i + 8] * radius)
                            i += 9
                        }
                    }
                }
            }
        }

        /**
         * Spinning glow marker hovering at the target's center.
         * Ported from wyvern TargetESP "Маркер".
         */
        class Marker(owner: ToggleableValueGroup, override val parent: ModeValueGroup<*>) : World("Marker") {
            private val color by color("Color", defaultColor)
            private val size by float("Size", 1.0f, 0.1f..3f)
            private val heightMode = modes(owner, "HeightMode") {
                arrayOf(
                    HeightMode.Feet(it),
                    HeightMode.Top(it),
                    HeightMode.Relative(it),
                )
            }

            private var rotationAngle = 0f
            private var rotationSpeed = 0f
            private var reversing = false

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                updateRotation()
                val height = heightMode.activeMode.getHeight(entity, partialTicks)
                val pos = entity.interpolateCurrentPosition(partialTicks).add(0.0, height, 0.0)

                withPositionRelativeToCamera(pos) {
                    poseStack.mulPose(camera.rotation())
                    poseStack.mulPose(Axis.ZP.rotationDegrees(rotationAngle))
                    drawCustomMeshTextured(markerTexture, ClientRenderPipelines.TexQuads) { pose ->
                        val h = size / 2f
                        addVertex(pose, -h, -h, 0f).setUv(0f, 1f).setColor(color.argb)
                        addVertex(pose, h, -h, 0f).setUv(1f, 1f).setColor(color.argb)
                        addVertex(pose, h, h, 0f).setUv(1f, 0f).setColor(color.argb)
                        addVertex(pose, -h, h, 0f).setUv(0f, 0f).setColor(color.argb)
                    }
                }
            }

            private fun updateRotation() {
                if (!reversing) {
                    rotationSpeed += 0.01f
                    if (rotationSpeed > 2.3f) {
                        rotationSpeed = 2.3f
                        reversing = true
                    }
                } else {
                    rotationSpeed -= 0.01f
                    if (rotationSpeed < -2.3f) {
                        rotationSpeed = -2.3f
                        reversing = false
                    }
                }
                rotationAngle = (rotationAngle + rotationSpeed) % 360f
            }
        }

        /**
         * Layered swirl of glow particles around the target.
         * Ported from wyvern TargetESP "Призраки 1".
         */
        class Spirits(override val parent: ModeValueGroup<*>) : World("Spirits") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 0.65f, 0.1f..3f)
            private val particleSize by float("ParticleSize", 0.5f, 0.1f..2f)
            private val particles by int("Particles", 14, 4..24)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val pos = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight / 2.0, 0.0)

                // Double precision: a Float of System.currentTimeMillis() only steps in ~65s
                // chunks, which froze the animation.
                val time = System.currentTimeMillis() / 2.0

                withPositionRelativeToCamera(pos) {
                    for (layer in 0..2) {
                        for (i in 0 until particles) {
                            val progress = i.toFloat() / (particles - 1)
                            val pSize = (0.55f * (1f - progress) + 0.2f * progress) * particleSize
                            val angle = 0.2 * (time - i * 7.0) / 15.0

                            val sinA = -sin(angle) * radius
                            val cosA = -cos(angle) * radius

                            val px: Double
                            val py: Double
                            val pz: Double
                            when (layer) {
                                0 -> { px = sinA; py = cosA + i * 0.02; pz = -cosA }
                                1 -> { px = -sinA; py = sinA - i * 0.02; pz = -cosA }
                                else -> { px = -sinA; py = -sinA; pz = cosA }
                            }

                            poseStack.pushPose()
                            poseStack.translate(px, py, pz)
                            drawBillboardGlow(pSize, color.argb)
                            poseStack.popPose()
                        }
                    }
                }
            }
        }

        /**
         * Spiral funnel of glow particles.
         * Ported from wyvern TargetESP "Призраки 2".
         */
        class Spiral(override val parent: ModeValueGroup<*>) : World("Spiral") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 0.5f, 0.1f..3f)
            private val particleSize by float("ParticleSize", 1.0f, 0.1f..3f)
            private val startTime = System.currentTimeMillis()

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val pos = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight / 2.0, 0.0)

                val time = (System.currentTimeMillis() - startTime) / 1100f
                val rotation = time * 360f

                withPositionRelativeToCamera(pos) {
                    for (layer in 0..3) {
                        val layerOffset = (layer - 1) * 0.4f
                        var i = 0f
                        while (i < 130f) {
                            val angle = rotation + i + layerOffset * 360f
                            val radians = Math.toRadians(-angle.toDouble())
                            val yOffset = sin(radians + 2.0) * layerOffset
                            val glowSize = radius * (i / 140f) * particleSize

                            poseStack.pushPose()
                            poseStack.translate(
                                cos(radians) * radius,
                                yOffset,
                                sin(radians) * radius
                            )
                            drawBillboardGlow(glowSize, color.argb)
                            poseStack.popPose()
                            i += 2f
                        }
                    }
                }
            }
        }

        /**
         * Worm of glow orbs circling the target.
         * Ported from wyvern TargetESP "Призраки 3".
         */
        class Worm(override val parent: ModeValueGroup<*>) : World("Worm") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 1.5f, 0.5f..4f)
            private val particleSize by float("ParticleSize", 0.13f, 0.05f..0.5f)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val pos = entity.interpolateCurrentPosition(partialTicks)
                val width = entity.bbWidth * radius
                val entityHeight = entity.bbHeight
                val moving = ((mc.player?.tickCount ?: 0) + partialTicks) * 13f

                withPositionRelativeToCamera(pos) {
                    val step = 2
                    var wormTick = 0
                    var wormCd = 0
                    var i = 0
                    while (i < 360) {
                        if (wormCd > 0) {
                            wormCd -= step
                            i += step
                            continue
                        }
                        wormTick += step
                        if (wormTick > 50) {
                            wormCd = 100
                            wormTick = 0
                            i += step
                            continue
                        }
                        val size = particleSize + 0.005f * wormTick
                        val sin = sin(Math.toRadians((i + moving).toDouble())) * width
                        val cos = cos(Math.toRadians((i + moving).toDouble())) * width
                        val yAnim = sin(Math.toRadians((i / 2f + moving / 5f).toDouble()))

                        poseStack.pushPose()
                        poseStack.translate(
                            sin,
                            entityHeight / 1.5 + entityHeight / 3.0 * yAnim,
                            cos
                        )
                        drawBillboardGlow(size, color.argb)
                        poseStack.popPose()
                        i += step
                    }
                }
            }
        }

        /**
         * Rotating ring of glow particles that bobs up and down the target.
         * Ported/reworked from wyvern TargetESP "Круг".
         */
        class ParticleCircle(override val parent: ModeValueGroup<*>) : World("ParticleCircle") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 0.7f, 0.1f..3f)
            private val particleSize by float("ParticleSize", 0.35f, 0.1f..2f)
            private val particles by int("Particles", 24, 4..64)
            private val spinSpeed by float("SpinSpeed", 1.0f, 0f..5f)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val basePos = entity.interpolateCurrentPosition(partialTicks)
                val height = entity.bbHeight
                val period = 1500
                val time = (System.currentTimeMillis() % period).toDouble()
                val ascending = time > period / 2
                var progress = (time / (period / 2.0)).toFloat()
                if (ascending) progress -= 1f else progress = 1f - progress
                progress = if (progress < 0.5f) {
                    2f * progress * progress
                } else {
                    (1.0 - Math.pow((-2f * progress + 2f).toDouble(), 2.0) / 2.0).toFloat()
                }

                val yOff = height / 2f * (if (progress > 0.5f) 1f - progress else progress) *
                    (if (ascending) -1 else 1)
                val pos = basePos.add(0.0, (height * progress + yOff).toDouble(), 0.0)

                val spin = System.currentTimeMillis() / 1000.0 * spinSpeed
                val r = radius.toDouble()

                withPositionRelativeToCamera(pos) {
                    for (i in 0 until particles) {
                        val angle = spin + i.toDouble() / particles * 2.0 * PI
                        poseStack.pushPose()
                        poseStack.translate(cos(angle) * r, 0.0, sin(angle) * r)
                        drawBillboardGlow(particleSize, color.argb)
                        poseStack.popPose()
                    }
                }
            }
        }

        /**
         * Glowing comet trails orbiting the target.
         * Ported from wyvern TargetESP "Призрачные орбиты".
         */
        class GhostOrbits(override val parent: ModeValueGroup<*>) : World("GhostOrbits") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 0.4f, 0.1f..3f)
            private val particleSize by float("ParticleSize", 1.0f, 0.2f..3f)
            private val particleCount = 3
            private val trailLength = 40

            private val positions = arrayOfNulls<Vec3>(particleCount)
            private val trails = Array(particleCount) { ArrayDeque<Vec3>() }
            private var movingAngle = 0f
            private var lastTime = 0L

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val center = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight / 2.0, 0.0)

                val now = System.currentTimeMillis()
                if (lastTime == 0L) lastTime = now
                val dtMs = (now - lastTime).toFloat()
                lastTime = now
                val fpsFactor = 500f / max(mc.fps, 10)
                movingAngle += 20f * dtMs / 16.667f * (15f / 55f)

                for (i in 0 until particleCount) {
                    val angleOffset = i * 360f / particleCount
                    val currentAngle = movingAngle + angleOffset
                    val radian = Math.toRadians(currentAngle.toDouble())
                    val ox = sin(radian) * radius
                    val oz = cos(radian) * radius
                    val oy = 0.3 * sin(Math.toRadians((movingAngle / (i + 1f)).toDouble()))
                    val targetGhostPos = center.add(ox, oy, oz)

                    var p = positions[i]
                    if (p == null || p.distanceTo(targetGhostPos) > 10) {
                        p = targetGhostPos
                    }
                    val mul = 0.1f * fpsFactor
                    val diff = targetGhostPos.subtract(p)
                    p = p.add(diff.scale(mul.toDouble()))
                    positions[i] = p

                    val trail = trails[i]
                    if (trail.isEmpty() || trail.first().distanceTo(p) > 0.01) {
                        trail.addFirst(p)
                        while (trail.size > trailLength) trail.removeLast()
                    }

                    trail.forEachIndexed { j, point ->
                        val offset = 1f - j.toFloat() / trailLength
                        val opacity = Math.pow(offset.toDouble(), 1.8).toFloat() * 0.7f
                        val scale = max(0.28f * offset, 0.15f) * 0.8f * particleSize
                        withPositionRelativeToCamera(point) {
                            drawBillboardGlow(scale, color.alpha((opacity * 255).toInt().coerceIn(0, 255)).argb)
                        }
                    }
                }
            }
        }

        /**
         * Ring of rotating crystals around the target.
         * Ported from wyvern TargetESP "Кристаллы".
         */
        class Crystals(override val parent: ModeValueGroup<*>) : World("Crystals") {
            private val color by color("Color", defaultColor)
            private val radius by float("Radius", 1.5f, 0.5f..4f)
            private val crystalSize by float("CrystalSize", 1.0f, 0.3f..3f)
            private var moving = 0f

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val pos = entity.interpolateCurrentPosition(partialTicks)
                moving += 1f

                val entityHeight = entity.bbHeight
                val width = entity.bbWidth * radius
                val argb = color.argb
                val cw = 0.085f * crystalSize
                val ch = 0.22f * crystalSize

                withPositionRelativeToCamera(pos) {
                    var i = 0
                    while (i < 360) {
                        val angleDeg = i + moving * 0.3f
                        val angleRad = Math.toRadians(angleDeg.toDouble())
                        val sin = (sin(angleRad) * width).toFloat()
                        val cos = (cos(angleRad) * width).toFloat()
                        val heightPrc = (i / 20f * 0.6180339f) % 1f
                        val crystalY = entityHeight * heightPrc

                        poseStack.pushPose()
                        poseStack.translate(sin.toDouble(), crystalY.toDouble(), cos.toDouble())
                        val dir = Vector3f(-sin, 0f, -cos).normalize()
                        poseStack.mulPose(Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), dir))

                        val ex = floatArrayOf(cw, 0f, -cw, 0f)
                        val ez = floatArrayOf(0f, cw, 0f, -cw)

                        // Additive geometry so the whole diamond glows uniformly
                        drawCustomMesh(ClientRenderPipelines.GlowTriangles) { pose ->
                            for (j in 0..3) {
                                val next = (j + 1) % 4
                                addVertex(pose, 0f, ch, 0f).setColor(argb)
                                addVertex(pose, ex[j], 0f, ez[j]).setColor(argb)
                                addVertex(pose, ex[next], 0f, ez[next]).setColor(argb)
                            }
                            for (j in 0..3) {
                                val next = (j + 1) % 4
                                addVertex(pose, 0f, -ch, 0f).setColor(argb)
                                addVertex(pose, ex[next], 0f, ez[next]).setColor(argb)
                                addVertex(pose, ex[j], 0f, ez[j]).setColor(argb)
                            }
                        }
                        poseStack.popPose()

                        // Soft halo covering the whole crystal (not a single central dot)
                        poseStack.pushPose()
                        poseStack.translate(sin.toDouble(), crystalY.toDouble(), cos.toDouble())
                        drawBillboardGlow(ch * 2.2f, color.alpha(110).argb)
                        poseStack.popPose()

                        i += 19
                    }
                }
            }
        }

        /**
         * Swarm of glowing fireflies wandering around the target, each flickering on its own phase.
         * Fully procedural (layered sines) — no persistent state.
         */
        class Fireflies(override val parent: ModeValueGroup<*>) : World("Fireflies") {
            private val color by color("Color", Color4b(255, 220, 120, 255))
            private val count by int("Count", 14, 2..48)
            private val radius by float("Radius", 0.9f, 0.2f..3f)
            private val particleSize by float("ParticleSize", 0.22f, 0.05f..0.8f)
            private val speed by float("Speed", 1.0f, 0.1f..4f)

            override fun WorldRenderEnvironment.render(entity: Entity, partialTicks: Float) {
                val anchor = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight / 2.0, 0.0)
                val t = System.currentTimeMillis() / 1000.0 * speed
                val r = radius.toDouble()

                withPositionRelativeToCamera(anchor) {
                    for (i in 0 until count) {
                        val ph = i * 2.39996323 // golden-angle phase spread
                        val x = sin(t * 0.7 + ph) * r * cos(t * 0.31 + ph * 2.0)
                        val y = sin(t * 0.53 + ph * 1.3) * r * 0.75
                        val z = cos(t * 0.61 + ph * 1.7) * r * sin(t * 0.43 + ph)

                        val flick = (0.35 + 0.65 * (0.5 + 0.5 * sin(t * 3.0 + ph * 5.0))).toFloat()
                        val a = (color.a * flick).toInt().coerceIn(0, 255)

                        poseStack.pushPose()
                        poseStack.translate(x, y, z)
                        drawBillboardGlow(particleSize * (0.7f + 0.5f * flick), color.alpha(a).argb)
                        poseStack.popPose()
                    }
                }
            }
        }

    }


    sealed class Gui(name: String) : TargetRenderAppearance<GuiGraphicsExtractor>(name) {

        class Text(owner: ToggleableValueGroup, override val parent: ModeValueGroup<*>) : Gui("Text2D") {

            private val textScale by float("Scale", 1f, 0.01f..10f)
            private val textShadow by boolean("Shadow", true)
            private val color by color("Color", Color4b.RED)

            private val texts by textList("Text", mutableListOf("TARGET"))

            private val heightMode = modes(owner, "HeightMode") {
                arrayOf(
                    HeightMode.Feet(it),
                    HeightMode.Top(it),
                    HeightMode.Relative(it),
                    HeightMode.Health(it),
                    HeightMode.Animated(it),
                )
            }

            private val fontRenderer get() = FontManager.FONT_RENDERER

            override fun GuiGraphicsExtractor.render(entity: Entity, partialTicks: Float) {
                val height = heightMode.activeMode.getHeight(entity, partialTicks)
                val pos = entity.interpolateCurrentPosition(partialTicks).add(0.0, height, 0.0)
                val screenPos = calculateScreenPos(pos) ?: return

                texts.forEachIndexed { i, text ->
                    fontRenderer.draw(text.asPlainText(Style.EMPTY + color)) {
                        horizontalAnchor = HorizontalAnchor.CENTER
                        verticalAnchor = VerticalAnchor.MIDDLE
                        x = screenPos.x
                        y = screenPos.y + i * fontRenderer.height
                        shadow = textShadow
                        scale = textScale
                    }
                }
            }
        }

        class Arrow(override val parent: ModeValueGroup<*>) : Gui("Arrow") {

            private val color by color("Color", Color4b.RED)
            private val outlineColor by color("OutlineColor", Color4b.TRANSPARENT)
            private val size by float("Size", 1.5f, 0.5f..20f)

            override fun GuiGraphicsExtractor.render(entity: Entity, partialTicks: Float) {
                val pos = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight.toDouble(), 0.0)

                val screenPos = calculateScreenPos(pos) ?: return
                val minX = screenPos.x - 5 * size
                val midX = screenPos.x
                val maxX = screenPos.x + 5 * size
                val minY = screenPos.y - 10 * size
                val maxY = screenPos.y
                drawTriangle(
                    x0 = minX, y0 = minY,
                    x1 = midX, y1 = maxY,
                    x2 = maxX, y2 = minY,
                    color,
                    outlineColor,
                )
            }
        }

        /**
         * Screen-space sprite pinned to the target's mid-height, additively blended and driven by
         * three out-of-phase sine waves: a slow full rotation, a size pulse and a small wobble that
         * is subtracted from the rotation. The sprite also shrinks with distance, so a far target
         * does not cover half the screen.
         *
         * The phases are offset per entity id so two targets never animate in lockstep.
         */
        class Stipple(override val parent: ModeValueGroup<*>) : Gui("Stipple") {

            private enum class PresetTexture(
                override val tag: String,
                val path: String,
            ) : TextureMode.Builtin.Preset {
                TRIANGLE_STIPPLE("TriangleStipple", "target_renderer/stipple/trianglestipple.png"),
                TRIANGLE_STAPPLE("TriangleStapple", "target_renderer/stipple/trianglestapple.png"),
                QUAD_STAPPLE("QuadStapple", "target_renderer/stipple/quadstapple.png"),
                FINGER("Finger", "target_renderer/stipple/fuckfinger.png");

                override val texture by lazy(LazyThreadSafetyMode.NONE) {
                    LiquidBounce.resource(this.path)
                        .readNativeImage().asTexture { "TargetRenderer Stipple $tag" }
                }
            }

            private val textureMode = modes("Source", 1) {
                arrayOf(
                    TextureMode.Custom(it),
                    TextureMode.Builtin(it, PresetTexture.TRIANGLE_STIPPLE, PresetTexture.entries.toEnumSet())
                )
            }

            private val size by float("Size", 128f, 8f..512f, "px")
            private val color by color("Color", Color4b(62, 139, 255, 240))
            private val hurtColor by color("HurtColor", Color4b(255, 61, 84, 255))
            private val additive by boolean("Additive", true)
            private val distanceScaling by boolean("DistanceScaling", true)

            override fun GuiGraphicsExtractor.render(entity: Entity, partialTicks: Float) {
                val texture = textureMode.activeMode.texture ?: return

                val pos = entity.interpolateCurrentPosition(partialTicks)
                    .add(0.0, entity.bbHeight * 0.5, 0.0)
                val screenPos = calculateScreenPos(pos) ?: return

                // Per-target phase offset, so several markers do not pulse in sync.
                val millis = System.currentTimeMillis() + entity.id * PHASE_OFFSET_MS

                val wobble = (sin(millis / WOBBLE_PERIOD) + 1.0) * 0.5 * WOBBLE_DEGREES
                val pulse = ((sin(millis / PULSE_PERIOD) + 1.0) * 0.5).coerceIn(MIN_PULSE, 1.0)
                val spin = (sin(millis / SPIN_PERIOD) + 1.0) * 0.5 * 360.0 - (wobble - WOBBLE_DEGREES * 0.5)

                val distanceFactor = if (distanceScaling) {
                    val distance = entity.distanceTo(player)
                    1f - (abs(distance - IDEAL_DISTANCE) / DISTANCE_FALLOFF).coerceIn(0f, MAX_DISTANCE_SHRINK)
                } else {
                    1f
                }

                val half = size * pulse.toFloat() * distanceFactor * 0.5f
                if (half <= 0.5f) {
                    return
                }

                val argb = (if ((entity as? LivingEntity)?.hurtTime?.let { it > 0 } == true) hurtColor else color).argb
                val pipeline = if (additive) {
                    ClientRenderPipelines.GUI.TexQuadAdditiveNoCull
                } else {
                    ClientRenderPipelines.GUI.TexQuadNoCull
                }

                pose().withPush {
                    translate(screenPos.x, screenPos.y)
                    rotate(spin.toFloat().toRadians())
                    drawTexQuad(texture.textureSetup, -half, -half, half, half, argb = argb, pipeline = pipeline)
                }
            }

            private companion object {
                const val PHASE_OFFSET_MS = 400L
                const val WOBBLE_PERIOD = 150.0
                const val PULSE_PERIOD = 500.0
                const val SPIN_PERIOD = 1000.0
                const val WOBBLE_DEGREES = 30.0
                const val MIN_PULSE = 0.8
                const val IDEAL_DISTANCE = 6f
                const val DISTANCE_FALLOFF = 60f
                const val MAX_DISTANCE_SHRINK = 0.75f
            }
        }
    }
}

private val defaultColor = Color4b.LIQUID_BOUNCE.alpha(100)

/** Pre-baked unit-sphere triangle mesh shared by every AuraRing instance. */
private object AuraRingOrbMesh {
    private const val LATITUDE_BANDS = 8
    private const val LONGITUDE_BANDS = 12

    val triangles: FloatArray = buildSphere()

    @Suppress("MagicNumber")
    private fun buildSphere(): FloatArray {
        val rings = Array(LATITUDE_BANDS + 1) { FloatArray((LONGITUDE_BANDS + 1) * 3) }
        for (lat in 0..LATITUDE_BANDS) {
            val theta = lat * PI / LATITUDE_BANDS
            val sinT = sin(theta).toFloat()
            val cosT = cos(theta).toFloat()
            val ring = rings[lat]
            for (lon in 0..LONGITUDE_BANDS) {
                val phi = lon * 2.0 * PI / LONGITUDE_BANDS
                val sinP = sin(phi).toFloat()
                val cosP = cos(phi).toFloat()
                val base = lon * 3
                ring[base] = cosP * sinT
                ring[base + 1] = cosT
                ring[base + 2] = sinP * sinT
            }
        }

        val triangleCount = LATITUDE_BANDS * LONGITUDE_BANDS * 2
        val out = FloatArray(triangleCount * 9)
        var ptr = 0
        for (lat in 0 until LATITUDE_BANDS) {
            val r0 = rings[lat]
            val r1 = rings[lat + 1]
            for (lon in 0 until LONGITUDE_BANDS) {
                val a0 = lon * 3
                val a1 = (lon + 1) * 3
                ptr = put(out, ptr, r0, a0)
                ptr = put(out, ptr, r1, a0)
                ptr = put(out, ptr, r0, a1)

                ptr = put(out, ptr, r0, a1)
                ptr = put(out, ptr, r1, a0)
                ptr = put(out, ptr, r1, a1)
            }
        }
        return out
    }

    private fun put(out: FloatArray, ptr: Int, src: FloatArray, srcOff: Int): Int {
        out[ptr] = src[srcOff]
        out[ptr + 1] = src[srcOff + 1]
        out[ptr + 2] = src[srcOff + 2]
        return ptr + 3
    }
}

private val ghostModeTexture = LiquidBounce.resource("particles/glow.png")
    .readNativeImage().asTexture { "TargetRenderer Ghost" }

private val wyvernGlowTexture = LiquidBounce.resource("particles/wyvern_glow.png")
    .readNativeImage().asTexture { "TargetRenderer WyvernGlow" }

private val markerTexture = LiquidBounce.resource("particles/marker.png")
    .readNativeImage().asTexture { "TargetRenderer Marker" }

/**
 * Draws a camera-facing, centered sprite. With [ClientRenderPipelines.GlowTexQuads]
 * (the default) blending is **additive** so overlapping glow particles brighten
 * instead of stacking into opaque squares; pass [ClientRenderPipelines.TexQuads]
 * for normal alpha-blended icons. [half] is the half-size of the quad.
 */
private fun WorldRenderEnvironment.drawSprite(
    texture: AbstractTexture,
    half: Float,
    argb: Int,
    pipeline: RenderPipeline = ClientRenderPipelines.GlowTexQuads,
) {
    poseStack.pushPose()
    poseStack.mulPose(camera.rotation())
    drawCustomMeshTextured(texture, pipeline) { pose ->
        addVertex(pose, -half, -half, 0f).setUv(0f, 1f).setColor(argb)
        addVertex(pose, half, -half, 0f).setUv(1f, 1f).setColor(argb)
        addVertex(pose, half, half, 0f).setUv(1f, 0f).setColor(argb)
        addVertex(pose, -half, half, 0f).setUv(0f, 0f).setColor(argb)
    }
    poseStack.popPose()
}

/**
 * Additive glow billboard using the wyvern glow texture. [size] is the full edge length.
 */
private fun WorldRenderEnvironment.drawBillboardGlow(size: Float, argb: Int) {
    drawSprite(wyvernGlowTexture, size / 2f, argb)
}


private sealed class HeightMode(name: String) : Mode(name) {
    abstract fun getHeight(entity: Entity, partialTicks: Float): Double

    interface WithGlow {
        fun getGlowHeight(entity: Entity, partialTicks: Float): Double
    }

    class Feet(override val parent: ModeValueGroup<*>) : HeightMode("Feet") {
        private val offset by float("Offset", 0f, -1f..1f)
        override fun getHeight(entity: Entity, partialTicks: Float): Double = offset.toDouble()
    }

    class Top(override val parent: ModeValueGroup<*>) : HeightMode("Top") {
        private val offset by float("Offset", 0f, -1f..1f)
        override fun getHeight(entity: Entity, partialTicks: Float) = entity.box.maxY - entity.box.minY + offset
    }

    // Lets the user chose the height relative to the entity's height
    // Use 1 for it to always be at the top of the entity
    // Use 0 for it to always be at the feet of the entity

    class Relative(override val parent: ModeValueGroup<*>) : HeightMode("Relative") {
        private val height by float("Height", 0.5f, -0.5f..1.5f)

        override fun getHeight(entity: Entity, partialTicks: Float): Double {
            val box = entity.box
            val entityHeight = box.maxY - box.minY
            return height * entityHeight
        }
    }

    class Health(override val parent: ModeValueGroup<*>) : HeightMode("Health") {
        override fun getHeight(entity: Entity, partialTicks: Float): Double {
            if (entity !is LivingEntity) return 0.0
            val box = entity.box
            val entityHeight = box.maxY - box.minY
            return entity.health / entity.maxHealth * entityHeight
        }
    }

    class Animated(override val parent: ModeValueGroup<*>) : HeightMode("Animated"), WithGlow {
        private val speed by float("Speed", 0.18f, 0.01f..1f)
        private val heightMultiplier by float("HeightMultiplier", 0.4f, 0.1f..1f)
        private val heightOffset by float("HeightOffset", 1.3f, 0f..2f)
        private val glowOffset by float("GlowOffset", -1f, -3.1f..3.1f)

        override fun getHeight(entity: Entity, partialTicks: Float): Double {
            return calculateHeight((entity.tickCount + partialTicks) * speed)
        }

        override fun getGlowHeight(entity: Entity, partialTicks: Float): Double {
            return calculateHeight((entity.tickCount + partialTicks) * speed + glowOffset)
        }

        private fun calculateHeight(time: Float) =
            (sin(time) * heightMultiplier + heightOffset).toDouble()
    }
}
