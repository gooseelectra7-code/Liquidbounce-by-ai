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
package net.ccbluex.liquidbounce.features.module.modules.render.dashtrail

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.misc.FriendManager
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawCustomMeshTextured
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.utils.rainbow
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.client.logger
import net.minecraft.client.CameraType
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * DashTrail
 *
 * A sprite trail that peels off a moving player: every tick of travel emits a handful of smears
 * which then coast, fan out and fade. Each smear is one of 135 hand-drawn sprites, gets a second
 * additive bloom pass on top, and can shed sparks.
 *
 * Ported from the veganew client. Its three passes were immediate-mode `glBegin` batches with the
 * texture rebound per smear; here the smears are bucketed by sprite so each texture is one mesh, and
 * quad corners are built in world space rather than through a per-smear matrix push.
 */
@Suppress("TooManyFunctions")
object ModuleDashTrail : ClientModule("DashTrail", ModuleCategories.RENDER) {

    private val self by boolean("Self", true)
    private val onFirstPerson by boolean("OnFirstPerson", true)
    private val players by boolean("Players", false)
    private val friends by boolean("Friends", true)
    private val maxDistance by float("MaxDistance", 25f, 1f..100f, "blocks")

    private val colorMode by enumChoice("ColorMode", DashColorMode.RANDOM_PALETTE)
    private val customColor by color("CustomColor", Color4b(170, 40, 255, 255))

    private val motionSmoothing by boolean("MotionSmoothing", false)
    private val dashSegments by boolean("DashSegments", false)
    private val dashDots by boolean("DashDots", true)
    private val lighting by boolean("Lighting", true)
    private val dashLength by float("DashLength", 0.75f, 0.1f..1.5f)
    private val quality by enumChoice("Quality", Quality.FIDELITY)

    enum class DashColorMode(override val tag: String) : Tagged {
        RANDOM_PALETTE("RandomPalette"),
        CUSTOM("Custom"),
        RAINBOW("Rainbow"),
    }

    /**
     * Trades smear count and size against fill cost. [Quality.PERFORMANCE] emits a third as many
     * smears but draws each one larger, so the trail stays visually solid.
     */
    enum class Quality(
        override val tag: String,
        val countMultiplier: Float,
        val sizeMultiplier: Float,
    ) : Tagged {
        PERFORMANCE("Performance", 0.333f, 1.1f),
        FIDELITY("Fidelity", 1f, 1f),
        MAX_FIDELITY("MaxFidelity", 5f, 0.333f),
    }

    private val cubics = ObjectArrayList<DashCubic>()
    private val random = Random(0x0DA5)

    /** Reused across frames so bucketing does not allocate a map every render. */
    private val spriteBuckets = Reference2ObjectOpenHashMap<DashSprite, ObjectArrayList<DashCubic>>()

    private var warnedAboutCap = false

    override fun onEnabled() {
        // 135 PNGs decode on first touch. Doing it here costs the same but lands on the toggle,
        // where a brief pause is expected, instead of mid-sprint when the first smear spawns.
        DashTextures.static
        DashTextures.animated
        DashTextures.bloom
        super.onEnabled()
    }

    override fun onDisabled() {
        clear()
        super.onDisabled()
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        clear()
    }

    private fun clear() {
        cubics.clear()
        spriteBuckets.clear()
        warnedAboutCap = false
    }

    // -- Simulation ------------------------------------------------------------------------------

    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val now = System.currentTimeMillis()

        cubics.removeIf { it.isDead(now) }

        for (index in cubics.indices) {
            val cubic = cubics[index]

            cubic.tick(world.getEntity(cubic.ownerId) as? LivingEntity, motionDonorFor(index), random)

            if ((dashSegments || dashDots) && cubic.progress(now) < 0.3f && random.nextInt(12) > 5) {
                repeat(if (dashSegments) 1 else 2) {
                    cubic.addSpark(random, quality.sizeMultiplier)
                }
            }
        }

        for (entity in world.players()) {
            if (shouldTrail(entity)) {
                emitFor(entity)
            }
        }
    }

    /**
     * MotionSmoothing chains each smear onto the next one of the same entity, so the whole trail
     * drifts as one ribbon. Without it every smear coasts on the motion it was born with.
     */
    private fun motionDonorFor(index: Int): DashCubic? {
        if (!motionSmoothing) {
            return null
        }

        val next = cubics.getOrNull(index + 1) ?: return null
        return next.takeIf { it.ownerId == cubics[index].ownerId }
    }

    private fun shouldTrail(entity: Player): Boolean {
        if (entity === player) {
            // In first person the smears spawn inside the camera, which is a wall of texture unless
            // the user asked for it.
            return self && (onFirstPerson || mc.options.cameraType != CameraType.FIRST_PERSON)
        }

        if (entity.distanceTo(player) > maxDistance) {
            return false
        }

        return if (FriendManager.isFriend(entity)) friends else players
    }

    private fun emitFor(entity: LivingEntity) {
        val dx = entity.x - entity.xOld
        val dy = entity.y - entity.yOld
        val dz = entity.z - entity.zOld

        val horizontalSpeed = sqrt(dx * dx + dz * dz)
        if (horizontalSpeed < MIN_SPEED) {
            return
        }

        val speed = sqrt(dx * dx + dy * dy + dz * dz)
        val multiplier = quality.countMultiplier
        val count = Mth.clamp((speed / MIN_SPEED * multiplier).toInt(), 1, (16 * multiplier).toInt())
        val withSparks = dashSegments || dashDots

        for (index in 0 until count) {
            if (cubics.size >= MAX_CUBICS) {
                if (!warnedAboutCap) {
                    warnedAboutCap = true
                    logger.info("DashTrail hit its $MAX_CUBICS smear cap; lower Quality to see the full trail.")
                }
                return
            }

            cubics.add(
                DashCubic(
                    entity = entity,
                    sprite = pickSprite(),
                    color = pickColor(),
                    lifetimeMs = ((BASE_LIFETIME_MS + random.nextInt(LIFETIME_JITTER_MS)) * dashLength).toInt(),
                    offsetInTick = index / count.toFloat(),
                    withSparks = withSparks,
                    random = random,
                )
            )
        }
    }

    private fun pickSprite(): DashSpriteSource {
        // The original rolled an animated group about 60% of the time; the rest are stills.
        if (random.nextInt(100) > 40) {
            val group = DashTextures.animated[random.nextInt(DashTextures.animated.size)]
            val period = ((BASE_LIFETIME_MS + random.nextInt(LIFETIME_JITTER_MS)) * dashLength).toInt()
            return DashSpriteSource.Animated(group, period)
        }

        return DashSpriteSource.Fixed(DashTextures.static[random.nextInt(DashTextures.static.size)])
    }

    private fun pickColor(): Int = when (colorMode) {
        DashColorMode.RANDOM_PALETTE -> Color.HSBtoRGB(random.nextInt(361) / 360f, 1f, 1f) or ALPHA_MASK
        DashColorMode.CUSTOM -> customColor.argb
        DashColorMode.RAINBOW -> rainbow().argb
    }

    // -- Rendering -------------------------------------------------------------------------------

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (cubics.isEmpty()) {
            return@handler
        }

        val now = System.currentTimeMillis()
        val partialTicks = event.partialTicks

        renderEnvironmentForWorld(event.matrixStack) {
            withPositionRelativeToCamera {
                if (dashDots) {
                    drawSparks(now, partialTicks, ClientRenderPipelines.Lines, dots = true)
                }

                if (dashSegments) {
                    drawSparks(now, partialTicks, ClientRenderPipelines.Quads, dots = false)
                }

                drawSmears(now, partialTicks, bloom = false)

                if (lighting) {
                    drawSmears(now, partialTicks, bloom = true)
                }
            }
        }
    }

    /**
     * Draws the sprite quads, one mesh per sprite.
     *
     * The [bloom] pass is a camera-facing additive halo behind the smear; the base pass orients the
     * quad along the direction of travel instead, which is what makes the trail read as motion
     * rather than as a row of stickers.
     */
    private fun WorldRenderEnvironment.drawSmears(now: Long, partialTicks: Float, bloom: Boolean) {
        bucketBySprite(bloom)

        val pipeline = if (bloom) ClientRenderPipelines.GlowTexQuads else ClientRenderPipelines.TexQuads

        for ((sprite, bucket) in spriteBuckets) {
            if (bucket.isEmpty) {
                continue
            }

            drawCustomMeshTextured(sprite.texture, pipeline) { pose ->
                for (cubic in bucket) {
                    emitSmear(pose, cubic, sprite, now, partialTicks, bloom, this@drawSmears)
                }
            }
        }
    }

    private fun bucketBySprite(bloom: Boolean) {
        spriteBuckets.values.forEach(ObjectArrayList<DashCubic>::clear)

        for (cubic in cubics) {
            val sprite = if (bloom) DashTextures.bloom else cubic.sprite.current()
            spriteBuckets.computeIfAbsent(sprite) { ObjectArrayList() }.add(cubic)
        }
    }

    @Suppress("LongParameterList")
    private fun VertexConsumer.emitSmear(
        pose: PoseStack.Pose,
        cubic: DashCubic,
        sprite: DashSprite,
        now: Long,
        partialTicks: Float,
        bloom: Boolean,
        env: WorldRenderEnvironment,
    ) {
        val alpha = cubic.alpha(now)
        if (alpha <= MIN_VISIBLE_ALPHA) {
            return
        }

        val scale = SPRITE_SCALE * alpha * quality.sizeMultiplier
        val halfWidth = sprite.width * scale * 0.5f
        val halfHeight = sprite.height * scale * 0.5f

        val x = cubic.renderX(partialTicks)
        val y = cubic.renderY(partialTicks)
        val z = cubic.renderZ(partialTicks)

        if (bloom) {
            // The halo is square and swells as the smear ages, so a dying smear leaves a soft flare.
            val swell = 1f + BLOOM_SWELL * (1f - cubic.progress(now)) * alpha
            val radius = sqrt(halfWidth * halfWidth + halfHeight * halfHeight) * swell
            val left = env.camera.leftVector()
            val up = env.camera.upVector()

            emitQuad(
                pose, x, y, z, fade(cubic.color, alpha * BLOOM_ALPHA),
                left.x() * radius, left.y() * radius, left.z() * radius,
                up.x() * radius, up.y() * radius, up.z() * radius,
            )
            return
        }

        val yawRad = cubic.yaw * Mth.DEG_TO_RAD
        val pitchRad = cubic.pitch * Mth.DEG_TO_RAD
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)

        emitQuad(
            pose, x, y, z, fade(cubic.color, alpha),
            cosYaw * halfWidth, 0f, sinYaw * halfWidth,
            -sinPitch * sinYaw * halfHeight, cosPitch * halfHeight, sinPitch * cosYaw * halfHeight,
        )
    }

    /** Emits one textured quad centred on ([x], [y], [z]) spanned by the right and up vectors. */
    @Suppress("LongParameterList")
    private fun VertexConsumer.emitQuad(
        pose: PoseStack.Pose,
        x: Double,
        y: Double,
        z: Double,
        argb: Int,
        rightX: Float,
        rightY: Float,
        rightZ: Float,
        upX: Float,
        upY: Float,
        upZ: Float,
    ) {
        addVertex(pose, (x - rightX - upX).toFloat(), (y - rightY - upY).toFloat(), (z - rightZ - upZ).toFloat())
            .setUv(0f, 1f).setColor(argb)
        addVertex(pose, (x + rightX - upX).toFloat(), (y + rightY - upY).toFloat(), (z + rightZ - upZ).toFloat())
            .setUv(1f, 1f).setColor(argb)
        addVertex(pose, (x + rightX + upX).toFloat(), (y + rightY + upY).toFloat(), (z + rightZ + upZ).toFloat())
            .setUv(1f, 0f).setColor(argb)
        addVertex(pose, (x - rightX + upX).toFloat(), (y - rightY + upY).toFloat(), (z - rightZ + upZ).toFloat())
            .setUv(0f, 0f).setColor(argb)
    }

    /**
     * Sparks, emitted as pairs straddling their smear. [dots] feeds them to a line pipeline (two
     * marks and a hairline between them); otherwise consecutive pairs form quads, which is the
     * ribbon look the original got out of `GL_QUADS`.
     */
    private fun WorldRenderEnvironment.drawSparks(
        now: Long,
        partialTicks: Float,
        pipeline: RenderPipeline,
        dots: Boolean,
    ) {
        drawCustomMesh(pipeline) { pose ->
            var emitted = 0

            for (cubic in cubics) {
                emitted += emitSparksOf(pose, cubic, now, partialTicks)
            }

            // Quads consume four vertices at a time; a dangling pair would be drawn as garbage.
            if (!dots && emitted % 4 != 0) {
                addVertex(pose, 0f, 0f, 0f).setColor(0)
                addVertex(pose, 0f, 0f, 0f).setColor(0)
            }
        }
    }

    /** @return how many vertices were written, so the caller can keep quads in groups of four. */
    private fun VertexConsumer.emitSparksOf(
        pose: PoseStack.Pose,
        cubic: DashCubic,
        now: Long,
        partialTicks: Float,
    ): Int {
        val cubicAlpha = cubic.alpha(now)
        if (cubic.sparks.isEmpty || cubicAlpha <= MIN_VISIBLE_ALPHA) {
            return 0
        }

        val x = cubic.renderX(partialTicks)
        val y = cubic.renderY(partialTicks)
        val z = cubic.renderZ(partialTicks)
        var emitted = 0

        for (spark in cubic.sparks) {
            val alpha = spark.alpha() * cubicAlpha
            if (alpha <= MIN_VISIBLE_ALPHA) {
                continue
            }

            val argb = fade(cubic.color, alpha * SPARK_ALPHA)
            val sx = spark.renderX(partialTicks)
            val sy = spark.renderY(partialTicks)
            val sz = spark.renderZ(partialTicks)

            addVertex(pose, (x + sx).toFloat(), (y + sy).toFloat(), (z + sz).toFloat()).setColor(argb)
            addVertex(pose, (x - sx).toFloat(), (y - sy).toFloat(), (z - sz).toFloat()).setColor(argb)
            emitted += 2
        }

        return emitted
    }

    private fun fade(argb: Int, factor: Float): Int {
        val alpha = (((argb ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (argb and 0x00FFFFFF) or (alpha shl 24)
    }

    private const val MIN_SPEED = 0.08
    private const val MIN_VISIBLE_ALPHA = 0.02f
    private const val MAX_CUBICS = 4000
    private const val BASE_LIFETIME_MS = 550
    private const val LIFETIME_JITTER_MS = 300
    private const val SPRITE_SCALE = 0.0033f
    private const val BLOOM_SWELL = 6f
    private const val BLOOM_ALPHA = 0.43f
    private const val SPARK_ALPHA = 0.75f
    private const val ALPHA_MASK = 0xFF shl 24

}
