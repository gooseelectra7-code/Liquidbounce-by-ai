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
package net.ccbluex.liquidbounce.features.module.modules.render.killeffect

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.ccbluex.liquidbounce.utils.render.putVec4
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f

/**
 * Scan wave kill effect — port of the NightShift "Scan World".
 *
 * A kill starts a wave at the victim's position. Every frame, once the world has finished drawing
 * into the main target (`MixinLevelRenderer` calls [render] from the same place the FragEffects
 * desaturation runs), a full-screen pass rebuilds each pixel's world position from the depth buffer
 * and lights up the ones the expanding shell currently passes through. The wave therefore wraps
 * around terrain instead of being a transparent sphere hanging in the air.
 *
 * The depth buffer is sampled, not attached: the pass is created colour-only, so reading the
 * target's own depth texture is not a feedback loop.
 */
object KillEffectScanMode : Mode("Scan"), KillEffectMode, MinecraftShortcuts {

    override val parent: ModeValueGroup<*>
        get() = ModuleKillEffect.modes

    private val color by color("Color", Color4b(0, 191, 255, 255))
    private val duration by float("Duration", 2.5f, 0.5f..8f, "s")
    private val bandWidth by float("Width", 10f, 1f..32f, "blocks")
    private val maxRadius by float("MaxRadius", 80f, 10f..256f, "blocks")
    private val fadeOutPortion by float("FadeOut", 0.25f, 0f..0.9f)
    private val scanlines by boolean("Scanlines", true)
    private val brightness by float("Brightness", 1f, 0.1f..3f)

    private const val MAX_ACTIVE_SCANS = 8

    private class Scan(val center: Vec3, val startTime: Long)

    private val scans = ObjectArrayList<Scan>()

    private val uniform by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.SCAN_WAVE.createSingleBuffer()
    }

    private val depthSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }

    private val inverseMatrix = Matrix4f()

    override fun onKill(entity: Entity) {
        if (scans.size >= MAX_ACTIVE_SCANS) {
            scans.removeAt(0)
        }
        scans.add(Scan(entity.position().add(0.0, entity.bbHeight * 0.5, 0.0), System.currentTimeMillis()))
    }

    override fun reset() {
        scans.clear()
    }

    /** Called from `MixinLevelRenderer` once the world has finished drawing into the main target. */
    @JvmStatic
    fun render() {
        if (!ModuleKillEffect.running || ModuleKillEffect.modes.activeMode !== this || scans.isEmpty()) {
            return
        }

        val main = mc.mainRenderTarget
        val colorView = main.colorTextureView ?: return
        val depthView = main.depthTextureView ?: return
        val width = main.width
        val height = main.height
        if (width <= 0 || height <= 0) {
            return
        }

        // proj × modelView of this frame, inverted once and reused by every scan below
        inverseMatrix.set(WorldToScreen.projModelView).invert()
        val cameraPos = WorldToScreen.cameraPosition
        val now = System.currentTimeMillis()
        val durationMs = (duration * 1000f).toLong().coerceAtLeast(1L)
        val time = (now % 1_000_000L) / 1000f

        val iterator = scans.iterator()
        while (iterator.hasNext()) {
            val scan = iterator.next()
            val progress = (now - scan.startTime).toFloat() / durationMs
            if (progress >= 1f) {
                iterator.remove()
                continue
            }

            val fadeStart = 1f - fadeOutPortion
            val fade = if (progress <= fadeStart || fadeOutPortion <= 0f) {
                1f
            } else {
                1f - (progress - fadeStart) / fadeOutPortion
            }

            writeParams(scan, progress, fade, width, height, cameraPos, time)

            // Colour-only pass: the depth texture is bound as a sampler, so it must not also be
            // an attachment of the very same pass.
            colorView.createRenderPass({ "KillEffect Scan pass" }).use { pass ->
                pass.setPipeline(ClientRenderPipelines.KillEffectScan)
                pass.bindTexture("DepthSampler", depthView, depthSampler)
                ClientUniformDefine.SCAN_WAVE.setTo(pass, uniform)
                pass.draw(0, 3)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun writeParams(
        scan: Scan,
        progress: Float,
        fade: Float,
        width: Int,
        height: Int,
        cameraPos: Vec3,
        time: Float,
    ) {
        val base = color
        val strength = brightness
        uniform.writeStd140 {
            putMat4f(inverseMatrix)
            putVec4(cameraPos.x.toFloat(), cameraPos.y.toFloat(), cameraPos.z.toFloat(), 0f)
            putVec4(
                scan.center.x.toFloat(),
                scan.center.y.toFloat(),
                scan.center.z.toFloat(),
                progress * maxRadius,
            )
            // outer / mid: the leading edge of the band, inner: the trailing one, which is the
            // brightest part — the original grades exactly this way
            putScanColor(base, 0.35f * strength)
            putScanColor(base, 0.35f * strength)
            putScanColor(base, 0.70f * strength)
            putScanColor(base, if (scanlines) 0.35f * strength else 0f)
            putVec4(width.toFloat(), height.toFloat(), bandWidth, time)
            putVec4(fade.coerceIn(0f, 1f), if (RenderSystem.getDevice().isZZeroToOne) 1f else 0f, 0f, 0f)
        }
    }

    private fun com.mojang.blaze3d.buffers.Std140Builder.putScanColor(base: Color4b, alpha: Float) {
        putVec4(
            base.r / 255f,
            base.g / 255f,
            base.b / 255f,
            (base.a / 255f) * alpha,
        )
    }
}
