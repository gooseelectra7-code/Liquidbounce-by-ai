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

package net.ccbluex.liquidbounce.features.module.modules.combat.crystalaura.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.AuraBlastDome
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.SoftStyle
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftArc
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftOutlineRing
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftRing
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.drawSoftSegments
import net.ccbluex.liquidbounce.features.module.modules.combat.aura.render.pulse
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.ClientUniformDefine
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.createRenderPass
import net.ccbluex.liquidbounce.render.engine.LazyRenderTargetHolder
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.ccbluex.liquidbounce.utils.render.writeStd140
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * How the forge looks. Read once per frame by [AuraRenderer] so the two halves can never disagree.
 *
 * @param core draw the world-space half: the additive pool on the ground, its rim, the turning spokes and the
 *   embers. Cheap - it is ordinary geometry through [drawSoftRing] and friends.
 * @param haze draw the screen-space half: the heat plume of `shaders/aura/forge_haze.fsh`. Costs two
 *   full-screen passes per frame while any site is warm, which is why it has its own toggle.
 * @param strength global multiplier over the plume's displacement and its tint. `0` leaves the scene untouched
 *   and the pass is skipped entirely.
 * @param radius footprint of one site in blocks: the pool's radius on the ground, and the width of the plume's
 *   lobe before it spreads with height.
 * @param cold colour of a site that has just been hit once.
 * @param hot colour of a site that is being hammered continuously. The ramp runs cold -> hot -> white-hot; the
 *   knees are `HOT_KNEE` and `WHITE_KNEE` in [AuraForge].
 */
data class ForgeSettings(
    val core: Boolean,
    val haze: Boolean,
    val strength: Float,
    val radius: Float,
    val cold: Color4b,
    val hot: Color4b,
)

/**
 * The spam site as a heat source.
 *
 * CrystalAura hammers one block up to fifteen times a second, and one ripple per detonation says nothing
 * about that: fifteen identical rings a second read as a single flicker. This object accumulates the detonations
 * instead, and draws the result as a forge - a ground pool whose colour and size ramp with how hard the site is
 * being worked, plus a world-anchored heat plume above it.
 *
 * ### The accumulator
 * [record] is called from [AuraRenderer.recordShockwave], i.e. once per attack packet that really went out. Each
 * one adds [HEAT_PER_HIT] to the site keyed by the crystal's block position and refreshes its stamp; the heat
 * decays exponentially with a [DECAY_TAU_MS] time constant and is clamped to `0..1`. One crystal is therefore a
 * flicker that is gone in about a second, while a sustained cycle pins the site at 1 within three detonations -
 * exactly the difference the eye should see. [SLOTS] sites are tracked at once and a ninth evicts the coldest.
 *
 * Nothing is ever drawn at a fixed brightness: the value the renderer uses is the raw heat run through a
 * [SMOOTH_TAU_MS] low pass ([harvest]), so a detonation's step becomes a rise and the pool cannot pop.
 *
 * ### Thread safety
 * [record] runs on the client tick and everything else on the render thread, so the whole table is behind its own
 * monitor - the same arrangement, and for the same reason, as the live-dome list of [AuraBlastDome]. The
 * per-frame arrays ([frameKey], [frameHeat]) are filled inside that monitor and read outside it by the render
 * thread alone.
 *
 * ### Failure
 * Only the screen-space half can throw. It is caught once, after which the plume turns itself off for the session
 * and frees its target; the world-space core keeps drawing.
 */
@Suppress("TooManyFunctions")
object AuraForge {

    /** Block position of each live site (`BlockPos.asLong`), or [EMPTY_KEY]. */
    private val slotKey = LongArray(SLOTS) { EMPTY_KEY }

    /** Raw heat of each site AT [slotStamp]; the current value is [heatAt]. */
    private val slotHeat = FloatArray(SLOTS)
    private val slotStamp = LongArray(SLOTS)

    /** The low-passed heat actually drawn, so a detonation's step in [slotHeat] becomes a rise. */
    private val slotShown = FloatArray(SLOTS)

    /** This frame's live sites, hottest first. Filled by [collectSites], read by the render thread only. */
    private val frameKey = LongArray(SLOTS)
    private val frameHeat = FloatArray(SLOTS)

    /** Wall clock of the last frame the low pass was advanced on; `0` means "no previous frame". */
    private var lastFrameMs = 0L

    /** Endpoints of the embers, refilled in place every frame - the crown must not allocate. */
    private val emberPoints = FloatArray(EMBERS * EMBER_STRIDE)

    /** Snapshot of the finished world; the plume displaces this, not the target it writes to. */
    private val sceneHolder = LazyRenderTargetHolder(
        "LiquidBounce CrystalAura Forge Scene",
        useDepth = false,
    )

    private val forgeUbo by lazy(LazyThreadSafetyMode.NONE) {
        ClientUniformDefine.AURA_FORGE.createSingleBuffer()
    }

    private val linearSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
    }

    private val depthSampler by lazy(LazyThreadSafetyMode.NONE) {
        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
    }

    private val inverseMatrix = Matrix4f()

    /** Sticky: set once the plume has thrown, never cleared. */
    private var broken = false

    private var idleFrames = 0

    /**
     * Feeds one detonation into the site at [center] - the crystal's own position, so the key is the block the
     * crystal occupied and the pool ends up on the obsidian it stood on.
     *
     * Safe from the client tick: everything it touches is behind the table's monitor.
     */
    fun record(center: Vec3) {
        val key = BlockPos.containing(center).asLong()
        val now = System.currentTimeMillis()
        synchronized(slotKey) {
            val slot = slotFor(key, now)
            // A recycled slot starts from nothing, including its low pass - the evicted site's warmth is not
            // this one's, and inheriting it would light the new pool at the wrong brightness for a frame.
            val fresh = slotKey[slot] != key
            val base = if (fresh) 0f else heatAt(slot, now)
            if (fresh) {
                slotShown[slot] = 0f
            }
            slotKey[slot] = key
            slotStamp[slot] = now
            slotHeat[slot] = (base + HEAT_PER_HIT).coerceAtMost(1f)
        }
    }

    /**
     * The heat of the hottest site right now, `0` while nothing is warm - the renderer's gate for drawing the
     * forge at all, and the same number [draw] will use this frame.
     */
    fun peakHeat(nowMs: Long): Float = if (collectSites(nowMs) > 0) frameHeat[0] else 0f

    /**
     * Whether the core would actually DRAW at [nowMs] - `peakHeat` above the threshold [draw] itself applies.
     *
     * A site stays in the table down to [DEAD_HEAT] so its slot is not recycled under a still-fading ember, but
     * `draw` returns immediately below that, so `peakHeat > 0` is not the same question. The caller that decides
     * whether the frame has any geometry at all needs the second one.
     */
    fun hasVisibleCore(nowMs: Long): Boolean = peakHeat(nowMs) > DEAD_HEAT

    /**
     * Drops every site and frees the scene snapshot. Called from `AuraRenderer.reset`, i.e. on world change and
     * on disable - a forge belongs to the world it was lit in.
     */
    fun release() {
        synchronized(slotKey) {
            slotKey.fill(EMPTY_KEY)
            slotHeat.fill(0f)
            slotShown.fill(0f)
        }
        lastFrameMs = 0L
        freeTarget()
    }

    /**
     * The world-space core at the hottest site: an additive pool on the ground, its feathered rim, three slowly
     * turning spokes and a crown of rising embers.
     *
     * Additive throughout ([drawSoftRing] with `additive = true`), which is the point: the module's `Fill` is a
     * user setting that defaults to alpha 40 and the forge must read at a glance whatever it is set to. A hard
     * no-op while no site is warm.
     */
    fun draw(env: WorldRenderEnvironment, settings: ForgeSettings, style: SoftStyle, nowMs: Long) {
        if (!settings.core || settings.radius <= 0f) return
        if (collectSites(nowMs) <= 0) return

        val heat = frameHeat[0]
        if (heat <= DEAD_HEAT) return
        env.drawCore(anchorOf(frameKey[0]), heat, settings, style, nowMs)
    }

    /**
     * Draws the heat plume over every warm site, in a single pair of full-screen passes.
     *
     * [event] pins the call to the world-render phase - the main target has to hold the finished world - and
     * keeps the shape identical to [AuraBlastDome.render]; the matrices come from [WorldToScreen], which holds
     * exactly the projection × modelView the frame was built with.
     */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter")
    fun render(event: WorldRenderEvent, settings: ForgeSettings, nowMs: Long) = renderPostWorld(settings, nowMs)

    /**
     * [render] without the event, for a caller that hooks the world's completion directly rather than going
     * through `WorldRenderEvent`.
     */
    fun renderPostWorld(settings: ForgeSettings, nowMs: Long) {
        if (broken || !settings.haze || settings.strength <= 0f || settings.radius <= 0f) {
            onIdleFrame()
            return
        }

        val count = collectSites(nowMs).coerceAtMost(MAX_HAZE_SITES)
        if (count <= 0) {
            onIdleFrame()
            return
        }

        idleFrames = 0
        runCatching {
            drawHaze(settings, count, nowMs)
        }.onFailure { cause ->
            broken = true
            logger.error("CrystalAura forge haze disabled after a render failure", cause)
            freeTarget()
        }
    }

    /** Frees the scene snapshot only; the heat table survives, so the core keeps drawing without the plume. */
    private fun freeTarget() {
        idleFrames = 0
        runCatching {
            sceneHolder.close()
        }
    }

    /**
     * Nothing to displace this frame. The target is kept for a while (the aura goes quiet between cycles
     * constantly, and reallocating a window-sized target for every gap would cost more than holding it), then
     * handed back once the pause looks permanent.
     */
    private fun onIdleFrame() {
        if (sceneHolder.raw == null) {
            return
        }

        idleFrames++
        if (idleFrames >= IDLE_FRAMES_BEFORE_RELEASE) {
            freeTarget()
        }
    }

    /**
     * The scene copy plus the plume pass.
     *
     * The copy exists because the plume writes into the main colour target and therefore cannot read it; it goes
     * through the pipeline [AuraBlastDome] already uses for exactly this ([ClientRenderPipelines.AuraSceneCopy]),
     * so the client carries one copy shader rather than two. The plume pass is created COLOUR-ONLY: the depth
     * texture it samples is the very attachment the main target owns.
     */
    private fun drawHaze(settings: ForgeSettings, count: Int, nowMs: Long) {
        val main = mc.mainRenderTarget
        val colorView = main.colorTextureView ?: return
        val depthView = main.depthTextureView ?: return
        if (main.width <= 0 || main.height <= 0) {
            return
        }

        // The snapshot is fully overwritten by the copy below, so it is never cleared first.
        val scene = sceneHolder.initAndGet(clear = false)
        val sceneView = scene.colorTextureView ?: return

        sceneView.createRenderPass({ "AuraForge SceneCopy" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.AuraSceneCopy)
            pass.bindTexture("InSampler", colorView, linearSampler)
            pass.draw(0, 3)
        }

        writeParams(settings, count, nowMs, main.width.toFloat() / main.height.toFloat())

        colorView.createRenderPass({ "AuraForge Haze" }).use { pass ->
            pass.setPipeline(ClientRenderPipelines.AuraForgeHaze)
            pass.bindTexture("SceneSampler", sceneView, linearSampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            ClientUniformDefine.AURA_FORGE.setTo(pass, forgeUbo)
            pass.draw(0, 3)
        }
    }

    /**
     * Fills `AuraForgeData`; the order here IS the block's order in `shaders/aura/forge_haze.fsh`.
     *
     * The clock is wrapped at [TIME_WRAP_MS] rather than handed over raw: a float loses its sub-millisecond
     * resolution somewhere past an hour of uptime, and the noise would start to step. The wrap itself is a single
     * discontinuity once an hour, which no one will be looking at.
     */
    private fun writeParams(settings: ForgeSettings, count: Int, nowMs: Long, aspect: Float) {
        inverseMatrix.set(WorldToScreen.projModelView).invert()

        val camera = WorldToScreen.cameraPosition
        val zZeroToOne = if (RenderSystem.getDevice().isZZeroToOne) 1f else 0f
        val time = (nowMs % TIME_WRAP_MS).toFloat() / MILLIS_PER_SECOND
        val hot = settings.hot

        forgeUbo.writeStd140 {
            putMat4f(inverseMatrix)
            putVec4(count.toFloat(), zZeroToOne, settings.strength.coerceAtLeast(0f), time)
            putVec4(aspect, settings.radius, settings.radius * HAZE_RISE_SCALE, HAZE_AMPLITUDE)
            putVec4(hot.r / MAX_CHANNEL_F, hot.g / MAX_CHANNEL_F, hot.b / MAX_CHANNEL_F, HAZE_TINT)

            // sites[MAX_HAZE_SITES]: centre relative to the camera + its heat
            for (index in 0 until MAX_HAZE_SITES) {
                if (index >= count) {
                    putVec4(0f, 0f, 0f, 0f)
                    continue
                }

                val anchor = hazeAnchorOf(frameKey[index])
                putVec4(
                    (anchor.x - camera.x).toFloat(),
                    (anchor.y - camera.y).toFloat(),
                    (anchor.z - camera.z).toFloat(),
                    frameHeat[index],
                )
            }
        }
    }

    /**
     * Moves every site that is still warm at [nowMs] into [frameKey] / [frameHeat], hottest first, and drops the
     * rest. Idempotent within a frame: the low pass only advances when the clock did ([approachOf]), so calling
     * this from [peakHeat], [draw] and [renderPostWorld] of the same frame yields the very same numbers.
     */
    private fun collectSites(nowMs: Long): Int {
        val count = synchronized(slotKey) { harvest(nowMs) }
        sortByHeat(count)
        return count
    }

    /** [collectSites]' body, called with the table's monitor already held. */
    private fun harvest(nowMs: Long): Int {
        val approach = approachOf(nowMs)
        var count = 0
        for (index in 0 until SLOTS) {
            if (slotKey[index] == EMPTY_KEY) continue

            val raw = heatAt(index, nowMs)
            val shown = slotShown[index] + (raw - slotShown[index]) * approach
            slotShown[index] = shown
            if (raw < DEAD_HEAT && shown < DEAD_HEAT) {
                slotKey[index] = EMPTY_KEY
                continue
            }

            frameKey[count] = slotKey[index]
            frameHeat[count] = shown
            count++
        }
        return count
    }

    /**
     * How far the low pass moves towards the raw heat this frame, `1 - exp(-dt / tau)`. A repeated call inside
     * one frame gets `0` (the clock has not moved), and a long gap is clamped to [MAX_FRAME_MS] so coming back
     * from a pause snaps instead of easing in from a stale value.
     */
    private fun approachOf(nowMs: Long): Float {
        val previous = lastFrameMs
        lastFrameMs = nowMs
        if (previous == 0L || nowMs <= previous) return 0f

        val delta = (nowMs - previous).coerceAtMost(MAX_FRAME_MS).toFloat()
        return 1f - exp(-delta / SMOOTH_TAU_MS)
    }

    /** Insertion sort of the frame's sites by heat, descending. At most [SLOTS] entries, so nothing fancier. */
    private fun sortByHeat(count: Int) {
        for (index in 1 until count) {
            val heat = frameHeat[index]
            val key = frameKey[index]
            var scan = index - 1
            while (scan >= 0 && frameHeat[scan] < heat) {
                frameHeat[scan + 1] = frameHeat[scan]
                frameKey[scan + 1] = frameKey[scan]
                scan--
            }
            frameHeat[scan + 1] = heat
            frameKey[scan + 1] = key
        }
    }

    /** The slot [key] belongs in: its own if it has one, otherwise the coldest (an empty slot reads as `0`). */
    private fun slotFor(key: Long, nowMs: Long): Int {
        var coldest = 0
        var coldestHeat = Float.MAX_VALUE
        for (index in 0 until SLOTS) {
            if (slotKey[index] == key) return index

            val heat = heatAt(index, nowMs)
            if (heat < coldestHeat) {
                coldest = index
                coldestHeat = heat
            }
        }
        return coldest
    }

    /** The raw heat of one slot decayed to [nowMs]; `0` for an empty slot. */
    private fun heatAt(index: Int, nowMs: Long): Float {
        if (slotKey[index] == EMPTY_KEY) return 0f

        val age = (nowMs - slotStamp[index]).coerceAtLeast(0L).toFloat()
        return slotHeat[index] * exp(-age / DECAY_TAU_MS)
    }

    /**
     * Where the pool is drawn: the centre of the block the crystal occupied, lifted by [GROUND_LIFT] off the
     * obsidian below it. The lift is what keeps the additive pool clear of the surface it lies on - the additive
     * pipeline is depth-tested (`render/ClientRenderPipelines.kt:349`).
     */
    private fun anchorOf(key: Long): Vec3 {
        val pos = BlockPos.of(key)
        return Vec3(pos.x + HALF_BLOCK, pos.y + GROUND_LIFT, pos.z + HALF_BLOCK)
    }

    /** Where the plume's lobe is centred: inside the crystal rather than on the ground, so it is not half buried. */
    private fun hazeAnchorOf(key: Long): Vec3 {
        val pos = BlockPos.of(key)
        return Vec3(pos.x + HALF_BLOCK, pos.y + HAZE_LIFT, pos.z + HALF_BLOCK)
    }

    /**
     * The pool, its rim, the spokes and the embers, all additive and all scaled by [heat] so the whole thing
     * grows out of nothing and shrinks back into it.
     */
    private fun WorldRenderEnvironment.drawCore(
        anchor: Vec3,
        heat: Float,
        settings: ForgeSettings,
        style: SoftStyle,
        nowMs: Long,
    ) {
        val radius = settings.radius * (POOL_BASE + POOL_GAIN * heat) * pulseOf(heat, nowMs)
        if (radius <= 0f) return

        val ramp = rampColor(settings, heat)
        val bright = ramp.fade(heat)
        val edge = ramp.fade(heat * RIM_RATIO)

        // `solidCenter` is what turns the ring into a pool: at innerRadius 0 the innermost rail would otherwise
        // be transparent and the forge would have a hole in the middle (`render/SoftGeometry.kt`, drawSoftArc).
        drawSoftRing(anchor, radius, 0f, edge, bright, style, solidCenter = true, additive = true)
        drawSoftOutlineRing(anchor, radius, bright, style, additive = true)
        drawSpokes(anchor, radius, edge, style, nowMs)
        drawEmbers(anchor, radius, bright.fade(EMBER_ALPHA), style, nowMs)
    }

    /**
     * [SPOKES] short arcs just outside the pool, turning once every [SPIN_PERIOD_MS]. The angle wraps at a full
     * turn, where `2π` and `0` are the same direction, so the rotation never jumps.
     */
    private fun WorldRenderEnvironment.drawSpokes(
        anchor: Vec3,
        radius: Float,
        color: Color4b,
        style: SoftStyle,
        nowMs: Long,
    ) {
        val spin = (nowMs % SPIN_PERIOD_MS).toDouble() / SPIN_PERIOD_MS * TWO_PI
        val outer = radius * SPOKE_OUTER
        val inner = radius * SPOKE_INNER
        val tail = color.with(a = 0)
        for (index in 0 until SPOKES) {
            val from = spin + index * TWO_PI / SPOKES
            drawSoftArc(anchor, outer, inner, from, from + SPOKE_SPAN, color, tail, style, additive = true)
        }
    }

    /**
     * Embers climbing out of the pool, as ONE mesh through [drawSoftSegments] over the caller-owned
     * [emberPoints] - the crown is rebuilt every frame and must not allocate.
     *
     * Each ember's phase is its own constant offset plus the shared cycle, so they do not march in step, and its
     * length is `sin(phase * π)`: zero at the bottom of the climb and zero again at the top, which is what makes
     * a looping ember grow out of the pool and dissolve rather than blink in and out (a segment shorter than the
     * ribbon epsilon is skipped outright, `render/SoftGeometry.kt`, addRibbon).
     */
    private fun WorldRenderEnvironment.drawEmbers(
        anchor: Vec3,
        radius: Float,
        color: Color4b,
        style: SoftStyle,
        nowMs: Long,
    ) {
        if (color.isTransparent) return

        val cycle = (nowMs % EMBER_MS).toFloat() / EMBER_MS
        for (index in 0 until EMBERS) {
            val seed = index * EMBER_SEED_STEP
            val phase = fract(cycle + seed)
            val angle = index * GOLDEN_ANGLE
            val ring = radius * (EMBER_RING_MIN + EMBER_RING_SPAN * fract(seed * EMBER_RING_SEED))
            val base = index * EMBER_STRIDE
            emberPoints[base] = cos(angle) * ring
            emberPoints[base + 1] = phase * radius * EMBER_RISE
            emberPoints[base + 2] = sin(angle) * ring
            emberPoints[base + 3] = emberPoints[base]
            emberPoints[base + 4] = emberPoints[base + 1] + sin(phase * PI_F) * EMBER_LENGTH
            emberPoints[base + 5] = emberPoints[base + 2]
        }
        drawSoftSegments(anchor, emberPoints, EMBERS, color, style, EMBER_WIDTH, additive = true)
    }

    /**
     * Cold -> hot -> white-hot.
     *
     * The first half of the range crosses from [ForgeSettings.cold] to [ForgeSettings.hot] (blue to orange with
     * the defaults), and past [WHITE_KNEE] the result is pulled towards white so a site being hammered without
     * pause goes white-hot. The alpha of the warm colour is kept through the white mix: under additive blending
     * alpha IS brightness, and letting it run to white's 255 would blow the pool out the moment it got hot.
     */
    private fun rampColor(settings: ForgeSettings, heat: Float): Color4b {
        val warm = settings.cold.interpolateTo(settings.hot, (heat / HOT_KNEE).coerceAtMost(1f).toDouble())
        val white = ((heat - WHITE_KNEE) / (1f - WHITE_KNEE)).coerceIn(0f, 1f)
        return warm.interpolateTo(Color4b.WHITE, (white * WHITE_MIX).toDouble()).with(a = warm.a)
    }

    /**
     * The breath of the pool: a fixed-rate sine whose AMPLITUDE is the heat, so the harder the site is worked the
     * more it heaves - the pulse is tied to the detonation rate through the heat rather than through the rate of
     * the sine, which would jump its phase every time the rate changed.
     */
    private fun pulseOf(heat: Float, nowMs: Long): Float {
        val phase = (nowMs % PULSE_MS).toFloat() / PULSE_MS
        return 1f + PULSE_AMOUNT * heat * sin(phase * TWO_PI_F)
    }

    private fun fract(value: Float): Float = value - floor(value)

    /** Sites tracked at once, and how many of them the plume can carry - the latter matches `MAX_SITES`. */
    private const val SLOTS = 8
    private const val MAX_HAZE_SITES = 4

    private const val EMPTY_KEY = Long.MIN_VALUE

    /** One detonation's contribution, the decay time constant, and the floor a site is dropped at. */
    private const val HEAT_PER_HIT = 0.34f
    private const val DECAY_TAU_MS = 1100f
    private const val DEAD_HEAT = 0.004f

    /** Low pass on the drawn heat, and the longest frame gap it will ease over before snapping. */
    private const val SMOOTH_TAU_MS = 110f
    private const val MAX_FRAME_MS = 250L

    /** Idle frames tolerated before the scene snapshot is handed back. */
    private const val IDLE_FRAMES_BEFORE_RELEASE = 120

    /** Height of the pool over the block below it, and of the plume's lobe centre over the same block. */
    private const val GROUND_LIFT = 0.06
    private const val HAZE_LIFT = 0.9
    private const val HALF_BLOCK = 0.5

    /** Plume shape: how far it rises in radii, its peak displacement in UV, and how much colour it adds. */
    private const val HAZE_RISE_SCALE = 1.6f
    private const val HAZE_AMPLITUDE = 0.014f
    private const val HAZE_TINT = 0.1f

    /** The shader clock wraps here; see [writeParams]. */
    private const val TIME_WRAP_MS = 3_600_000L
    private const val MILLIS_PER_SECOND = 1000f

    /** Pool radius as a fraction of `ForgeRadius`: [POOL_BASE] cold, `POOL_BASE + POOL_GAIN` white-hot. */
    private const val POOL_BASE = 0.55f
    private const val POOL_GAIN = 0.45f

    /** Alpha of the pool's outer half relative to its centre. */
    private const val RIM_RATIO = 0.4f

    /** The breath: its period, and how far it moves the radius at full heat. */
    private const val PULSE_MS = 620L
    private const val PULSE_AMOUNT = 0.09f

    /** The turning spokes: how many, where they sit in pool radii, how wide each is, and how fast they turn. */
    private const val SPOKES = 3
    private const val SPOKE_INNER = 0.78f
    private const val SPOKE_OUTER = 1.16f
    private const val SPOKE_SPAN = 0.62
    private const val SPIN_PERIOD_MS = 5200L

    /** The embers: count, floats per segment, cycle length, and how far they climb in pool radii. */
    private const val EMBERS = 14
    private const val EMBER_STRIDE = 6
    private const val EMBER_MS = 1500L
    private const val EMBER_RISE = 1.4f

    /** Ember shape: tick length in blocks, alpha against the pool's centre, and ribbon width scale. */
    private const val EMBER_LENGTH = 0.26f
    private const val EMBER_ALPHA = 0.85f
    private const val EMBER_WIDTH = 0.7f

    /** Where the embers stand in pool radii, and the two irrational steps that scatter them. */
    private const val EMBER_RING_MIN = 0.25f
    private const val EMBER_RING_SPAN = 0.75f
    private const val EMBER_RING_SEED = 7.13f
    private const val EMBER_SEED_STEP = 0.137f
    private const val GOLDEN_ANGLE = 2.3999632f

    /** Knees of the colour ramp, and how far white-hot pulls towards white. */
    private const val HOT_KNEE = 0.5f
    private const val WHITE_KNEE = 0.55f
    private const val WHITE_MIX = 0.85f

    private const val TWO_PI = 2.0 * PI
    private const val TWO_PI_F = 6.2831855f
    private const val PI_F = 3.1415927f
    private const val MAX_CHANNEL_F = 255f
}
