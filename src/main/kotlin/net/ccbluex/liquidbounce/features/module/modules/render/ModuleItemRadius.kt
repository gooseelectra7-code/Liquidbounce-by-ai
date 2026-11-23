/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * 1:1 port of the SystemDLC `client.module.render.ItemRadius`
 * (`.../system-source/src/main/java/client/module/render/ItemRadius.java`, 885 L). It draws a
 * world-space ability/area preview for a fixed set of FunTime custom items held in hand and
 * colour-flashes toward a highlight colour when a visible enemy is inside the zone.
 *
 * ## What is ported (source method -> port function, cited ItemRadius.java:line)
 *  - `getIntByPlayerEntity` (:582-591)  -> [shapeIndex]        : which preview to draw (main then off hand;
 *    Бульдозер pickaxe wins if selected).
 *  - `getIntByItemStack2`   (:593-625)  -> [shapeOf]           : main/off-hand item -> shape 0..5.
 *  - `isTrackedItemItemStack`(:627-636) -> [isTrackedItemItemStack] : item id + lore-substring match
 *    (uses the shared [SystemDlcItems.isStringItemStack]).
 *  - `getIntByItemStack`    (:188-207)  -> [buldozerLevel]     : "Бульдозер"-enchanted pickaxe level (lore/name regex).
 *  - `getIntByString`       (:682-707)  -> [parseLevel]        : roman/decimal level parse (Pattern flags 66).
 *  - `render5`              (:222-271)  -> the [WorldRenderEvent] handler (dispatch by shape index).
 *  - circle emitter `onVec3dVec3dMatrixStackIntDouble` (:460-490) + fan `onIntDoubleVec3dVec3dMatrixStack`
 *    (:492-518) -> [drawCircleShape] (72-seg rim loop + optional TRIANGLE_FAN fill).
 *  - box emitter `onIntMatrixStackIntBoxVec3d` (:709-754) -> [drawShapeBox] (wire + optional fill).
 *  - `onIntListIntMatrixStackVec3d` (:390-400) -> [drawMergedBoxes] (VoxelShape union -> per-AABB box).
 *  - Трапка box  `getBoxByPlayerEntity`          (:532-538) -> [trapkaBox].
 *  - Пласт wall  `getBoxByBooleanFloatBlockPos`  (:803-816) -> [plastWallBox].
 *  - Пласт ceil  `getBoxByBlockPosBoolean`       (:818-824) -> [plastCeilingBox].
 *  - Пласт floor `getBoxByBooleanBlockPos`       (:870-875) -> [plastFloorBox].
 *  - Пласт diag  `getListByBlockPosBooleanInt`   (:826-868) -> [plastDiagonalList].
 *  - Бульдозер grid `onVec3dFloatPlayerEntityMatrixStack` (:638-671) + `getBlockPosByIntBlockPosAxisInt`
 *    (:673-680) -> [renderBuldozer] + [buldozerOffset] (AxisSwitchMap X->2,Y->1,Z->3, AxisSwitchMap.java:8-23).
 *  - colour lerp `onIntFloatBooleanInt` (:756-789) + `getIntByIntInt` (:336-338) + `getIntByFloatIntInt`
 *    (:324-334) -> [updateFlash] + [withAlpha] + [lerpArgb].
 *  - enemy detection: `isPlayerEntityPlayerEntity`  (:169-171) -> [skip];
 *    `isPlayerEntityPlayerEntity2` (:277-296, LOS raycast) -> [hasLineOfSight];
 *    `isDoubleVec3dPlayerEntity`   (:340-356) -> [anyEnemyInCircle];
 *    `isPlayerEntityBox`           (:358-372) -> [anyEnemyInBox];
 *    `isPlayerEntityList`          (:304-322, NO LOS) -> [anyEnemyInList].
 *  - DetectSkin action `update11` (:177-186) + `getItemStackByPlayerEntity` (:417-427) +
 *    `isItemStack` (:429-445) -> [detectDragonSkin] + [findTrapka] + [isDragonNamed].
 *  - `check3` (:578-580) -> the public [dragonSkin] accessor for the ItemTracker module.
 *
 * ## Render API translation (uses OUR world render API)
 * The SystemDLC helpers (`client/render/DepthState.java`) rasterize raw POSITION_COLOR buffers with manual
 * GL depth state. They are re-expressed with our `renderEnvironmentForWorld { withPositionRelativeToCamera { .. } }`
 * plus `drawBox` / `drawLineStrip` / `drawCustomMesh` (see [drawShapeBox], [drawCircleShape]). Our base
 * `Lines`/`LineStrip`/`Triangles`/`Quads` pipelines are all `forWorldRender(noDepthTest = true)`
 * (ClientRenderPipelines.kt:75-79,265-359) i.e. drawn THROUGH walls, matching the source's circle rim/fill
 * (depthFunc 519, DepthState.java:361-368,400-407) and box WIRE (DepthState.update2, :400-407). DEVIATION: the
 * source draws the box FILL depth-tested (DepthState.render with flag=false leaves depthFunc 515,
 * DepthState.java:151-184); our exposed `Quads` pipeline is no-depth-test, so the box fill is drawn through
 * walls too. Circles are fully faithful.
 *
 * ## Fidelity notes / deviations
 *  - The `podsvetkaPopadaniya` ColorToggleSetting is represented as a boolean [hitHighlight] + colour
 *    [hitHighlightColor] pair (LB has no single "colour-toggle" widget); behaviour is identical
 *    (`.check()` -> [hitHighlight], `.getInt()` -> [hitHighlightColor]).
 *  - The `opredelitSkin` ActionSetting is represented as a momentary boolean [detectSkin] whose `onChange`
 *    runs [detectDragonSkin] and always stores `false` (LB has no action-button widget).
 *  - Per-colour `setVisibleWhen` gates (:147-153) are dropped: all seven colours always show under [Colors].
 *  - Flash speed uses `partialTicks` as the source uses `getTickDelta(false)` (:227): `value237 += partialTicks/0.5`
 *    accumulates the fractional tick, NOT a wall-clock frame delta, so the flash effectively saturates within
 *    ~1 tick. Reproduced verbatim (source :782).
 *  - No friend filter: the source only skips self/dead/spectator/invisible (:169-171); ported as-is.
 */
@file:Suppress("MagicNumber", "TooManyFunctions")

package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.SystemDlcItems
import net.ccbluex.liquidbounce.features.module.modules.render.systemdlc.TrackedItem
import net.ccbluex.liquidbounce.render.ClientRenderPipelines
import net.ccbluex.liquidbounce.render.WorldRenderEnvironment
import net.ccbluex.liquidbounce.render.addVertex
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.drawCustomMesh
import net.ccbluex.liquidbounce.render.drawLineStrip
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.engine.type.Vec3f
import net.ccbluex.liquidbounce.render.renderEnvironmentForWorld
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.tags.ItemTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object ModuleItemRadius : ClientModule("ItemRadius", ModuleCategories.RENDER) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.itemRadius"

    /**
     * The seven previewable shapes, in the source multi-list display order
     * (`list`, ItemRadius.java:57-65). Tags are the Russian [TrackedItem.text] display names.
     */
    @Suppress("unused")
    enum class Shape(override val tag: String) : Tagged {
        ENDER_EYE("Дезориентация"),
        SUGAR("Явная пыль"),
        FIRE_CHARGE("Огненный смерч"),
        BOZHA_AURA("Божья аура"),
        NETHERITE_SCRAP("Трапка"),
        DRIED_KELP("Пласт"),
        BULDOZER("Бульдозер"),
    }

    /**
     * "Цвета"/Colors group — one ColorSetting per shape (ItemRadius.java:90-159). ARGB defaults are the
     * exact source ints: Дезка -16755456=0xFF005500, Явка -6710887=0xFF999999, Огненный заряд
     * -11206656=0xFF550000, Божья аура -16737895=0xFF009999, Трапка -7650029=0xFF8B4513, Пласт
     * -13421773=0xFF333333, Бульдозер -1=0xFFFFFFFF.
     */
    private object Colors : ValueGroup("Colors") {
        val dezka by color("Дезка", Color4b(0xFF005500.toInt()))
        val yavka by color("Явка", Color4b(0xFF999999.toInt()))
        val ognennyyZaryad by color("Огненный заряд", Color4b(0xFF550000.toInt()))
        val bozhyaAura by color("Божья аура", Color4b(0xFF009999.toInt()))
        val trapka by color("Трапка", Color4b(0xFF8B4513.toInt()))
        val plast by color("Пласт", Color4b(0xFF333333.toInt()))
        val buldozer by color("Бульдозер", Color4b(0xFFFFFFFF.toInt()))
    }

    // --- Settings (source addSettings order, ItemRadius.java:161) ---

    /** "Предметы"/Items — the shapes to draw a radius for (all selected by default). */
    private val items by multiEnumChoice(
        "Items",
        Shape.ENDER_EYE,
        Shape.SUGAR,
        Shape.FIRE_CHARGE,
        Shape.BOZHA_AURA,
        Shape.NETHERITE_SCRAP,
        Shape.DRIED_KELP,
        Shape.BULDOZER,
    )

    @Suppress("unused")
    private val colorsGroup = tree(Colors)

    /** "Подсветка попадания"/HitHighlight enable (source ColorToggleSetting, default on, :118). */
    private val hitHighlight by boolean("HitHighlight", true)

    /** The HitHighlight colour (source default -16711800 = 0xFF00FF88, :118). */
    private val hitHighlightColor by color("HitHighlightColor", Color4b(0xFF00FF88.toInt()))

    /** "Заполнение"/Fill — fill the shapes (source `zapolnenie`, default true, :122-125). */
    private val fill by boolean("Fill", true)

    /** "Скин дракона"/DragonSkin — enlarge Трапка/Пласт geometry (source `skinDrakona`, default false, :126). */
    private var skinDrakona by boolean("DragonSkin", false)

    /**
     * "Определить скин"/DetectSkin — momentary action (source ActionSetting, :130-133,162): find a Трапка in
     * the inventory and set [skinDrakona] from its "дракон" lore/name. Represented as a boolean that always
     * reverts to `false`.
     */
    @Suppress("unused")
    private val detectSkin = boolean("DetectSkin", false).onChange { requested ->
        if (requested) {
            detectDragonSkin()
        }
        false
    }

    /**
     * Public read of DragonSkin for the separate ItemTracker module (source `check3`, ItemRadius.java:578-580,
     * consumed by ItemTracker). Kotlin objects expose members directly, so ItemTracker reads
     * `ModuleItemRadius.dragonSkin`.
     */
    @JvmStatic
    val dragonSkin: Boolean
        get() = skinDrakona

    // --- Flash state machine fields (source value235/236/237/flag/value238) ---

    /** Current FILL colour (ARGB, 0x55 alpha base), lerped toward the target each frame (`value235`). */
    private var fillColorArgb = 0

    /** Current LINE colour (ARGB, 0xFF alpha base), lerped toward the target each frame (`value236`). */
    private var lineColorArgb = 0

    /** Flash progress 0..1 (`value237`). */
    private var flashProgress = 0.0f

    /** Whether the highlight is currently active (`flag`). */
    private var highlightState = false

    /** Current shape index, -1 = none (`value238`). */
    private var currentShape = -1

    /** `бульдозер\s*(iii|ii|i|\d+)?` with CASE_INSENSITIVE|UNICODE_CASE (Pattern flags 66, :56). */
    private val buldozerPattern: Pattern =
        Pattern.compile("бульдозер\\s*(iii|ii|i|\\d+)?", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        // source render5: notInGame() guard + player() null-check (:224-226)
        val p = mc.player ?: return@handler
        if (mc.level == null) {
            return@handler
        }
        val f = event.partialTicks // == getRenderTickCounter().getTickDelta(false) (:227)

        renderEnvironmentForWorld(event.matrixStack) {
            when (val shape = shapeIndex(p)) {
                -1 -> currentShape = -1 // source: value238 = -1 (:232)
                0 -> renderCircle(Colors.dezka.argb, 0, 10.0, f, p)
                1 -> renderCircle(Colors.yavka.argb, 1, 10.0, f, p)
                2 -> renderCircle(Colors.ognennyyZaryad.argb, 2, 10.0, f, p)
                3 -> renderCircle(Colors.bozhyaAura.argb, 3, 2.0, f, p)
                4 -> renderTrapka(f, p)
                5 -> renderPlast(f, p)
                6 -> renderBuldozer(f, p)
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Item -> shape resolution
    // ---------------------------------------------------------------------------------------------

    /** `getIntByPlayerEntity` (:582-591). */
    private fun shapeIndex(p: LocalPlayer): Int {
        val main = p.mainHandItem
        if (buldozerLevel(main) > 0 && Shape.BULDOZER in items) {
            return 6
        }
        val fromMain = shapeOf(main)
        return if (fromMain != -1) fromMain else shapeOf(p.offhandItem)
    }

    /** `getIntByItemStack2` (:593-625). */
    private fun shapeOf(stack: ItemStack): Int {
        if (stack.isEmpty) {
            return -1
        }
        if (isTrackedItemItemStack(TrackedItem.ENDER_EYE, stack) && Shape.ENDER_EYE in items) return 0
        if (isTrackedItemItemStack(TrackedItem.SUGAR, stack) && Shape.SUGAR in items) return 1
        if (isTrackedItemItemStack(TrackedItem.FIRE_CHARGE, stack) && Shape.FIRE_CHARGE in items) return 2
        if (isTrackedItemItemStack(TrackedItem.BOZHA_AURA, stack) && Shape.BOZHA_AURA in items) return 3
        if (isTrackedItemItemStack(TrackedItem.NETHERITE_SCRAP, stack) && Shape.NETHERITE_SCRAP in items) return 4
        if (isTrackedItemItemStack(TrackedItem.DRIED_KELP, stack) && Shape.DRIED_KELP in items) return 5
        return -1
    }

    /**
     * `isTrackedItemItemStack` (:627-636): the stack's item equals [tracked]'s item and, if the entry has a
     * lore matcher ([TrackedItem.text5]), a lore line contains it. Uses the shared
     * [SystemDlcItems.isStringItemStack]. Note this is name-agnostic (lore only), matching the source.
     */
    private fun isTrackedItemItemStack(tracked: TrackedItem, stack: ItemStack): Boolean {
        if (stack.item !== tracked.item) {
            return false
        }
        val lore = tracked.text5 ?: return true
        return SystemDlcItems.isStringItemStack(lore, stack)
    }

    /** `getIntByItemStack` (:188-207): max "Бульдозер" level from a pickaxe's lore lines / custom name. */
    private fun buldozerLevel(stack: ItemStack): Int {
        if (stack.isEmpty || !stack.`is`(ItemTags.PICKAXES)) {
            return 0
        }
        var level = 0
        stack.get(DataComponents.LORE)?.lines()?.forEach { line ->
            level = maxOf(level, parseLevel(line.string))
        }
        stack.get(DataComponents.CUSTOM_NAME)?.let { name ->
            level = maxOf(level, parseLevel(name.string))
        }
        return level
    }

    /** `getIntByString` (:682-707): parse the level captured after "бульдозер" (roman i/ii/iii or decimal). */
    private fun parseLevel(text: String): Int {
        val matcher = buldozerPattern.matcher(text)
        if (!matcher.find()) {
            return 0
        }
        val group = matcher.group(1)
        if (group.isNullOrEmpty()) {
            return 1
        }
        return when (group.lowercase(Locale.ROOT)) {
            "i" -> 1
            "ii" -> 2
            "iii" -> 3
            else -> group.toIntOrNull() ?: 0
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DetectSkin action
    // ---------------------------------------------------------------------------------------------

    /** `update11` (:177-186). */
    private fun detectDragonSkin() {
        val p = mc.player ?: return
        val stack = findTrapka(p) ?: return
        skinDrakona = isDragonNamed(stack)
    }

    /** `getItemStackByPlayerEntity` (:417-427): first Трапка in the inventory. */
    private fun findTrapka(p: LocalPlayer): ItemStack? {
        val inventory = p.inventory
        for (i in 0 until inventory.containerSize) {
            val stack = inventory.getItem(i)
            if (isTrackedItemItemStack(TrackedItem.NETHERITE_SCRAP, stack)) {
                return stack
            }
        }
        return null
    }

    /** `isItemStack` (:429-445): custom name OR any lore line contains "дракон" (case-insensitive, ROOT). */
    private fun isDragonNamed(stack: ItemStack): Boolean {
        val name = stack.get(DataComponents.CUSTOM_NAME)
        if (name != null && name.string.lowercase(Locale.ROOT).contains("дракон")) {
            return true
        }
        val lore = stack.get(DataComponents.LORE) ?: return false
        for (line in lore.lines()) {
            if (line.string.lowercase(Locale.ROOT).contains("дракон")) {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Colour flash state machine
    // ---------------------------------------------------------------------------------------------

    /** `getIntByIntInt` (:336-338): put [alpha] in the alpha channel, keep RGB of [argb]. */
    private fun withAlpha(alpha: Int, argb: Int): Int = (alpha shl 24) or (argb and 0xFFFFFF)

    /** `getIntByFloatIntInt` (:324-334): per-channel lerp from [from] toward [to] by [t] (int truncation). */
    private fun lerpArgb(t: Float, from: Int, to: Int): Int {
        val fa = (from ushr 24) and 0xFF
        val fr = (from ushr 16) and 0xFF
        val fg = (from ushr 8) and 0xFF
        val fb = from and 0xFF
        val ta = (to ushr 24) and 0xFF
        val tr = (to ushr 16) and 0xFF
        val tg = (to ushr 8) and 0xFF
        val tb = to and 0xFF
        val a = (fa + (ta - fa) * t).toInt()
        val r = (fr + (tr - fr) * t).toInt()
        val g = (fg + (tg - fg) * t).toInt()
        val b = (fb + (tb - fb) * t).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `onIntFloatBooleanInt` (:756-789). Advances [fillColorArgb]/[lineColorArgb] toward the (highlighted?) base. */
    private fun updateFlash(shapeIdx: Int, tickDelta: Float, enemyInside: Boolean, baseColor: Int) {
        if (currentShape != shapeIdx) {
            currentShape = shapeIdx
            highlightState = false
            flashProgress = 0.0f
            fillColorArgb = withAlpha(85, baseColor)
            lineColorArgb = withAlpha(255, baseColor)
        }
        val highlightActive = hitHighlight && enemyInside
        val highlightColor = if (hitHighlight) hitHighlightColor.argb else baseColor
        if (highlightActive != highlightState) {
            flashProgress = 0.0f
            highlightState = highlightActive
        }
        val targetBase = if (highlightActive) highlightColor else baseColor
        val targetFill = withAlpha(85, targetBase)
        val targetLine = withAlpha(255, targetBase)
        flashProgress = min(flashProgress + tickDelta / 0.5f, 1.0f)
        fillColorArgb = lerpArgb(flashProgress, fillColorArgb, targetFill)
        lineColorArgb = lerpArgb(flashProgress, lineColorArgb, targetLine)
    }

    // ---------------------------------------------------------------------------------------------
    // Enemy detection
    // ---------------------------------------------------------------------------------------------

    /** `isPlayerEntityPlayerEntity` (:169-171): skip self / dead / spectator / invisible. */
    private fun skip(local: Player, other: Player): Boolean =
        other === local || !other.isAlive || other.isSpectator || other.isInvisible || other.isInvisibleTo(local)

    /**
     * `isPlayerEntityPlayerEntity2` (:277-296): true if [viewer]'s eye has line of sight (MISS on any of four
     * sample rays) to [target]'s box. Ports the four sample points (target eye, box centre, two lower/upper
     * corners at centre-Y).
     */
    private fun hasLineOfSight(target: Player, viewer: Player): Boolean {
        val level = mc.level ?: return false
        val from = viewer.eyePosition
        val box = target.boundingBox
        val samples = arrayOf(
            target.eyePosition,
            box.center,
            Vec3(box.minX, box.center.y, box.minZ),
            Vec3(box.maxX, box.center.y, box.maxZ),
        )
        for (sample in samples) {
            val hit = level.clip(ClipContext(from, sample, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer))
            if (hit.type == HitResult.Type.MISS) {
                return true
            }
        }
        return false
    }

    /** `isDoubleVec3dPlayerEntity` (:340-356): a visible enemy is within [radius] of [center]. */
    private fun anyEnemyInCircle(radius: Double, center: Vec3, local: Player): Boolean {
        val radiusSq = radius * radius
        for (other in world.players()) {
            if (!skip(local, other) && hasLineOfSight(other, local) &&
                other.position().distanceToSqr(center) <= radiusSq
            ) {
                return true
            }
        }
        return false
    }

    /** `isPlayerEntityBox` (:358-372): a visible enemy's box intersects [box]. */
    private fun anyEnemyInBox(local: Player, box: AABB): Boolean {
        for (other in world.players()) {
            if (!skip(local, other) && hasLineOfSight(other, local) && other.boundingBox.intersects(box)) {
                return true
            }
        }
        return false
    }

    /** `isPlayerEntityList` (:304-322): a (non-skipped) enemy's block position is in [blocks]. NO LOS check. */
    private fun anyEnemyInList(local: Player, blocks: Set<BlockPos>): Boolean {
        for (other in world.players()) {
            if (!skip(local, other) && other.blockPosition() in blocks) {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Shape builders (block/box geometry)
    // ---------------------------------------------------------------------------------------------

    /** `getFloatByFloat` (:402-405): wrapDegrees then fold into [0,360). */
    private fun normalizeYaw(yaw: Float): Float {
        val wrapped = Mth.wrapDegrees(yaw)
        return if (wrapped < 0.0f) wrapped + 360.0f else wrapped
    }

    /** `getIntegerByFloat` (:407-415): nearest of 45/135/225/315 within 22 deg, else null. */
    private fun diagonalYaw(yaw: Float): Int? {
        for (angle in intArrayOf(45, 135, 225, 315)) {
            if (abs(yaw - angle) < 22.0f) {
                return angle
            }
        }
        return null
    }

    /** `getBoxByPlayerEntity` (:532-538): the Трапка cube (enlarged for dragon skin). */
    private fun trapkaBox(p: LocalPlayer): AABB {
        val bp = p.blockPosition()
        return if (dragonSkin) {
            AABB(bp.x - 2.0, bp.y.toDouble(), bp.z - 2.0, bp.x + 3.0, bp.y + 5.0, bp.z + 3.0)
        } else {
            AABB(bp.x - 1.0, bp.y.toDouble(), bp.z - 1.0, bp.x + 2.0, bp.y + 3.0, bp.z + 2.0)
        }
    }

    /** `getBoxByBooleanFloatBlockPos` (:803-816): vertical wall in the facing direction. */
    private fun plastWallBox(dragon: Boolean, yaw: Float, bp: BlockPos): AABB {
        val d0 = if (dragon) -3.0 else -2.0
        val d1 = if (dragon) 4.0 else 3.0
        val d2 = if (dragon) 6.0 else 4.0
        val x = bp.x
        val y = bp.y
        val z = bp.z
        return when {
            yaw >= 315.0f || yaw < 45.0f -> AABB(x + d0, y - 1.0, z + 2.0, x + d1, y + d2, z + 4.0)
            yaw < 135.0f -> AABB(x - 3.0, y - 1.0, z + d0, x - 1.0, y + d2, z + d1)
            yaw < 225.0f -> AABB(x + d0, y - 1.0, z - 3.0, x + d1, y + d2, z - 1.0)
            else -> AABB(x + 2.0, y - 1.0, z + d0, x + 4.0, y + d2, z + d1)
        }
    }

    /** `getBoxByBlockPosBoolean` (:818-824): ceiling slab (looking up, pitch < -45). */
    private fun plastCeilingBox(bp: BlockPos, dragon: Boolean): AABB {
        val d0 = if (dragon) -3.0 else -2.0
        val d1 = if (dragon) 4.0 else 3.0
        val d3 = if (dragon) 7.0 else 5.0
        return AABB(bp.x + d0, bp.y + 3.0, bp.z + d0, bp.x + d1, bp.y + d3, bp.z + d1)
    }

    /** `getBoxByBooleanBlockPos` (:870-875): floor slab (looking down, pitch > 45). */
    private fun plastFloorBox(dragon: Boolean, bp: BlockPos): AABB {
        val d0 = if (dragon) -3.0 else -2.0
        val d1 = if (dragon) 4.0 else 3.0
        return AABB(bp.x + d0, bp.y - 3.0, bp.z + d0, bp.x + d1, bp.y + 1.0, bp.z + d1)
    }

    /** `getListByBlockPosBooleanInt` (:826-868): the diagonal plate block set. */
    private fun plastDiagonalList(bp: BlockPos, dragon: Boolean, count: Int): Set<BlockPos> {
        val b0: Int
        val b1: Int
        val mirror: Boolean
        when (count) {
            45 -> { b0 = -3; b1 = 2; mirror = false }
            135 -> { b0 = -3; b1 = -2; mirror = true }
            225 -> { b0 = 2; b1 = -2; mirror = false }
            else -> { b0 = 2; b1 = 2; mirror = true } // 315
        }
        val base = bp.offset(b0, -1, b1)
        val i = if (dragon) -3 else -2
        val j = if (dragon) 3 else 2
        val k = if (dragon) 7 else 5
        val list = LinkedHashSet<BlockPos>()
        for (l in i..j) {
            val i1 = if (mirror) -l else l
            for (j1 in 0 until k) {
                list.add(base.offset(l, j1, i1))
                list.add(base.offset(l + 1, j1, i1))
            }
        }
        return list
    }

    /** `getBlockPosByIntBlockPosAxisInt` (:673-680) with AxisSwitchMap X->2, Y->1, Z->3 (AxisSwitchMap.java:8-23). */
    private fun buldozerOffset(count: Int, bp: BlockPos, axis: Direction.Axis, count2: Int): BlockPos =
        when (axis) {
            Direction.Axis.Y -> bp.offset(count, 0, count2) // case 1
            Direction.Axis.X -> bp.offset(0, count, count2) // case 2
            Direction.Axis.Z -> bp.offset(count, count2, 0) // case 3
        }

    // ---------------------------------------------------------------------------------------------
    // Per-shape render entry points (source render helpers)
    // ---------------------------------------------------------------------------------------------

    /** `onIntIntDoubleFloatPlayerEntityVec3dMatrixStack` (:447-458): flat horizontal circle. */
    private fun WorldRenderEnvironment.renderCircle(baseColor: Int, shapeIdx: Int, radius: Double, f: Float, p: LocalPlayer) {
        val lerpPos = p.interpolateCurrentPosition(f) // getVec3dByPlayerEntityFloat (:298-302)
        val center = Vec3(lerpPos.x, lerpPos.y + p.bbHeight - 1.4, lerpPos.z) // (:449)
        val inside = anyEnemyInCircle(radius, center, p)
        updateFlash(shapeIdx, f, inside, baseColor)
        drawCircleShape(center, radius, lineColorArgb, if (fill) fillColorArgb else null)
    }

    /** `render(MatrixStack, Vec3d, float, PlayerEntity)` (:520-530): Трапка box. */
    private fun WorldRenderEnvironment.renderTrapka(f: Float, p: LocalPlayer) {
        val box = trapkaBox(p)
        val inside = anyEnemyInBox(p, box)
        updateFlash(4, f, inside, Colors.trapka.argb)
        drawShapeBox(box, lineColorArgb, fillColorArgb)
    }

    /** `onVec3dMatrixStackFloatPlayerEntity` (:540-570): Пласт wall/slab/diagonal by pitch+yaw. */
    private fun WorldRenderEnvironment.renderPlast(f: Float, p: LocalPlayer) {
        val dragon = dragonSkin
        val bp = p.blockPosition()
        val pitch = p.xRot
        val yaw = normalizeYaw(p.yRot)
        if (abs(pitch) <= 45.0f) {
            val diagonal = diagonalYaw(yaw)
            if (diagonal != null) {
                val list = plastDiagonalList(bp, dragon, diagonal)
                val inside = anyEnemyInList(p, list)
                updateFlash(5, f, inside, Colors.plast.argb)
                drawMergedBoxes(list, lineColorArgb, fillColorArgb)
                return
            }
            renderPlastBox(f, p, plastWallBox(dragon, yaw, bp))
        } else if (pitch < -45.0f) {
            renderPlastBox(f, p, plastCeilingBox(bp, dragon))
        } else {
            renderPlastBox(f, p, plastFloorBox(dragon, bp))
        }
    }

    /** `render(MatrixStack, Box, Vec3d, float, PlayerEntity)` (:791-801): Пласт box (detect on original, draw contracted). */
    private fun WorldRenderEnvironment.renderPlastBox(f: Float, p: LocalPlayer, box: AABB) {
        // Box.contract(0.01, 0.01, 0.01): shrink only the max corner by 0.01 (Yarn semantics, :792).
        val drawn = AABB(box.minX, box.minY, box.minZ, box.maxX - 0.01, box.maxY - 0.01, box.maxZ - 0.01)
        val inside = anyEnemyInBox(p, box)
        updateFlash(5, f, inside, Colors.plast.argb)
        drawShapeBox(drawn, lineColorArgb, fillColorArgb)
    }

    /** `onVec3dFloatPlayerEntityMatrixStack` (:638-671): Бульдозер 3x3x(1|2) block preview at the crosshair. */
    private fun WorldRenderEnvironment.renderBuldozer(f: Float, p: LocalPlayer) {
        val hit = mc.hitResult
        if (hit !is BlockHitResult || hit.type != HitResult.Type.BLOCK) {
            return
        }
        val level = buldozerLevel(p.mainHandItem)
        if (level <= 0) {
            return
        }
        val depth = if (level >= 2) 2 else 1
        val axis = hit.direction.axis
        val direction = hit.direction.opposite
        val blockPos = hit.blockPos
        updateFlash(6, f, false, Colors.buldozer.argb) // flagx = false (Бульдозер never highlights, :649)
        for (dz in 0 until depth) {
            val slab = blockPos.relative(direction, dz)
            for (row in -1..1) {
                for (col in -1..1) {
                    val cell = buldozerOffset(row, slab, axis, col)
                    drawShapeBox(AABB(cell), lineColorArgb, fillColorArgb)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Low-level draw (our render API)
    // ---------------------------------------------------------------------------------------------

    /**
     * Circle rim (72-seg line loop, 5-deg steps) + optional flat TRIANGLE_FAN fill, both drawn through walls.
     * Source rim `onVec3dVec3dMatrixStackIntDouble` (:460-490); fill `onIntDoubleVec3dVec3dMatrixStack`
     * (:492-518). Local point at angle t: (sin t * r, 0, -cos t * r) in the XZ plane.
     */
    private fun WorldRenderEnvironment.drawCircleShape(center: Vec3, radius: Double, lineArgb: Int, fillArgb: Int?) {
        withPositionRelativeToCamera(center) {
            val rim = Array(73) { idx ->
                val a = Math.toRadians(5.0 * idx)
                Vec3f(sin(a) * radius, 0.0, -cos(a) * radius)
            }
            drawLineStrip(lineArgb, *rim)

            if (fillArgb != null) {
                drawCustomMesh(ClientRenderPipelines.Triangles) { pose ->
                    for (idx in 0 until 72) {
                        val a0 = Math.toRadians(5.0 * idx)
                        val a1 = Math.toRadians(5.0 * (idx + 1))
                        addVertex(pose, 0.0, 0.0, 0.0).setColor(fillArgb)
                        addVertex(pose, sin(a0) * radius, 0.0, -cos(a0) * radius).setColor(fillArgb)
                        addVertex(pose, sin(a1) * radius, 0.0, -cos(a1) * radius).setColor(fillArgb)
                    }
                }
            }
        }
    }

    /**
     * One box: wireframe outline ([wireArgb]) + optional translucent fill ([fillArgb], drawn only when Fill is
     * on and the colour is non-transparent), matching `onIntMatrixStackIntBoxVec3d` (:709-754). Anchored at the
     * box min corner so world coordinates stay float-precise.
     */
    private fun WorldRenderEnvironment.drawShapeBox(box: AABB, wireArgb: Int, fillArgb: Int) {
        val origin = Vec3(box.minX, box.minY, box.minZ)
        withPositionRelativeToCamera(origin) {
            // source gate: count2 != 0 && zapolnenie (ItemRadius.java:741)
            val face = if (fill && fillArgb != 0) Color4b(fillArgb) else null
            val local = box.move(-origin.x, -origin.y, -origin.z)
            drawBox(local, faceColor = face, outlineColor = Color4b(wireArgb))
        }
    }

    /**
     * `onIntListIntMatrixStackVec3d` (:390-400): union the block cubes into a VoxelShape, optimize, and draw
     * each resulting AABB as a box (removes internal cube subdivisions, matching the source's `simplify()`).
     */
    private fun WorldRenderEnvironment.drawMergedBoxes(blocks: Set<BlockPos>, wireArgb: Int, fillArgb: Int) {
        var shape: VoxelShape = Shapes.empty()
        for (bp in blocks) {
            shape = Shapes.joinUnoptimized(shape, Shapes.create(AABB(bp)), BooleanOp.OR)
        }
        for (box in shape.optimize().toAabbs()) {
            drawShapeBox(box, wireArgb, fillArgb)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /** `update12` (:572-576): reset the flash state. */
    private fun resetFlash() {
        currentShape = -1
        flashProgress = 0.0f
        highlightState = false
    }

    override fun onEnabled() {
        resetFlash() // source onEnable (:877-879)
    }

    override fun onDisabled() {
        resetFlash() // source onDisable (:213-216)
        super.onDisabled()
    }
}
