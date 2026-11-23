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
package net.ccbluex.liquidbounce.features.module.modules.combat

import com.mojang.blaze3d.platform.InputConstants
import net.ccbluex.fastutil.enumSetOf
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.KeyEvent
import net.ccbluex.liquidbounce.event.events.MouseButtonEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.drawCircle
import net.ccbluex.liquidbounce.render.drawRoundedRect
import net.ccbluex.liquidbounce.render.drawTriangle
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.ItemSlot
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * AutoSwap module
 *
 * Tap the bind → cycle the off-hand item to the next configured item. Hold the bind (with
 * 3+ cells) → a custom radial wheel pops up: it frees the cursor without pausing/opening a
 * screen, you point at any item and release to swap it in. All swaps run through the click
 * scheduler, so they stay in sync with InventoryMove / silent inventory opening.
 *
 * With `SeparateSpheres` (default on) the [SwapItem.SPHERE] entry is not one cell for "any
 * player head" any more: every DISTINCT head in the inventory gets its own cell, named
 * after the head, so all of them are on the wheel at once and a specific one can be picked.
 * The cycle walks the same list, and it is anchored by entry identity rather than by index
 * because that list changes with the inventory.
 */
@Suppress("TooManyFunctions")
object ModuleAutoSwap : ClientModule("AutoSwap", ModuleCategories.COMBAT) {

    private const val HOLD_MS = 200L

    /**
     * Upper bound on the cells [SwapItem.SPHERE] may expand into. Servers hand out a lot of
     * different heads; past this the ring is unreadable whatever the radius, so the rest is
     * dropped rather than drawn on top of each other.
     */
    private const val MAX_SPHERE_CELLS = 12

    /** Head names are server-authored and can be long; the wheel label is cut to this. */
    private const val MAX_LABEL_CHARS = 24

    private val trigger by key("Trigger")
    private val items by multiEnumChoice("Items", enumSetOf(SwapItem.TOTEM, SwapItem.GOLDEN_APPLE))
    private val onlyEnchanted by boolean("OnlyEnchanted", false)

    /**
     * Every DISTINCT player head in the inventory gets its own wheel cell, named after the
     * head itself, instead of one "Sphere" cell that always picks the first head found.
     * Off, [SwapItem.SPHERE] behaves as it did before.
     */
    private val separateSpheres by boolean("SeparateSpheres", true)

    private val wheelMode by enumChoice("WheelMode", WheelMode.HOLD)
    private val constraints = tree(PlayerInventoryConstraints())

    @Suppress("unused")
    enum class WheelMode(override val tag: String) : Tagged {
        /** Hold the bind to open the wheel; release to pick. A quick tap cycles instead. */
        HOLD("Hold"),

        /** Tap the bind to open the wheel; tap again to pick (cycles when under 3 items). */
        TOGGLE("Toggle"),
    }

    @Suppress("unused")
    enum class SwapItem(
        override val tag: String,
        val matches: (ItemStack, onlyEnchanted: Boolean) -> Boolean,
    ) : Tagged {
        SPHERE("Sphere", { stack, _ -> stack.item === Items.PLAYER_HEAD }),
        TOTEM("Totem", { stack, ench ->
            stack.item === Items.TOTEM_OF_UNDYING && (!ench || stack.isEnchanted)
        }),
        SHIELD("Shield", { stack, _ -> stack.item === Items.SHIELD }),
        GOLDEN_APPLE("GoldenApple", { stack, ench ->
            if (ench) {
                stack.item === Items.ENCHANTED_GOLDEN_APPLE
            } else {
                stack.item === Items.GOLDEN_APPLE || stack.item === Items.ENCHANTED_GOLDEN_APPLE
            }
        }),
        CROSSBOW("Crossbow", { stack, _ -> stack.item === Items.CROSSBOW }),
    }

    /**
     * Identity of the entry the cycle last landed on, NOT an index: with
     * [separateSpheres] the entry list is rebuilt from the inventory, so it changes length
     * whenever a head is used up or picked up and a stored index would silently point at a
     * different item.
     */
    private var cycleKey: String? = null
    private var pendingFrom: ItemSlot? = null

    // Tap / hold + wheel state
    private var holding = false
    private var pressAt = 0L
    private var wheelOpen = false
    private var selectedIndex = -1

    /**
     * Identity of the cell under the cursor in the LAST rendered frame. The pick runs on the
     * bind's release and rebuilds the entry list, which the inventory may have changed since
     * that frame - the index alone would then point at a different item.
     */
    private var selectedKey: String? = null

    /**
     * The click vanilla has already queued for a press the wheel consumed, swallowed at the next
     * tick head - see [handleWheelClick].
     */
    private var pendingClickKey: InputConstants.Key? = null

    private fun enabledItems(): List<SwapItem> = SwapItem.entries.filter { it in items }

    private fun findSlot(item: SwapItem): ItemSlot? = Slots.HotbarAndInventory.findSlot { stack ->
        item.matches(stack, onlyEnchanted)
    }

    // --- Entries (what the cycle walks and the wheel shows) -------------------------------------

    /**
     * One step of the cycle and one cell of the wheel: either a whole [SwapItem], or ONE
     * specific head when [separateSpheres] expands [SwapItem.SPHERE].
     *
     * [slot] is null when nothing in the inventory matches - the cell is still drawn, dimmed,
     * so the ring keeps its shape (and [icon] is then the placeholder stack).
     */
    private class SwapEntry(
        val key: String,
        val label: String,
        val slot: ItemSlot?,
        val icon: ItemStack,
    )

    /** The live entry list, rebuilt on demand from the configured items and the inventory. */
    private fun entries(): List<SwapEntry> {
        if (mc.player == null) {
            return emptyList()
        }

        val enabled = enabledItems()
        val entries = ArrayList<SwapEntry>(enabled.size)
        for (item in enabled) {
            if (item === SwapItem.SPHERE && separateSpheres) {
                val spheres = sphereEntries()
                if (spheres.isNotEmpty()) {
                    entries += spheres
                    continue
                }
                // no head anywhere: fall through to the plain, dimmed "Sphere" cell
            }

            val slot = findSlot(item)
            entries += SwapEntry(item.name, item.tag, slot, slot?.itemStack ?: defaultStack(item))
        }
        return entries
    }

    /**
     * One entry per DISTINCT head, in inventory order (hotbar first - that is the order
     * [Slots.HotbarAndInventory] is built in). Distinct means item + components, so several
     * stacks of the same head collapse into the cell of the first one.
     */
    private fun sphereEntries(): List<SwapEntry> {
        val spheres = ArrayList<SwapEntry>()
        for (slot in Slots.HotbarAndInventory) {
            val stack = slot.itemStack
            if (!SwapItem.SPHERE.matches(stack, onlyEnchanted)) {
                continue
            }
            if (spheres.any { ItemStack.isSameItemSameComponents(it.icon, stack) }) {
                continue
            }

            val name = stack.hoverName.string
            spheres += SwapEntry("sphere:$name:${spheres.size}", name, slot, stack)
            if (spheres.size >= MAX_SPHERE_CELLS) {
                break
            }
        }
        return spheres
    }

    /** Index of the entry the cycle last landed on, or -1 once it is gone from the list. */
    private fun cycleIndexIn(entries: List<SwapEntry>): Int =
        entries.indexOfFirst { it.key == cycleKey }

    // --- Trigger (keyboard + mouse) -------------------------------------------------------------

    @Suppress("unused")
    private val keyHandler = handler<KeyEvent> { onTrigger(it.key, it.action) }

    @Suppress("unused")
    private val mouseHandler = handler<MouseButtonEvent> { event ->
        if (!handleWheelClick(event.key, event.button, event.action)) {
            onTrigger(event.key, event.action)
        }
    }

    /**
     * Tick HEAD: swallow the click the wheel consumed before `handleKeybinds` turns it into a
     * swing or a use (the [net.ccbluex.liquidbounce.features.module.modules.combat.ModuleSpearLunge]
     * idiom). Our own [handleWheelClick] runs from inside `MouseHandler.onButton`, BEFORE vanilla
     * raises the mapping's `clickCount`, so the count can only be taken back afterwards.
     */
    @Suppress("unused")
    private val tickHandler = handler<GameTickEvent> {
        val key = pendingClickKey ?: return@handler
        pendingClickKey = null

        for (mapping in arrayOf(mc.options.keyAttack, mc.options.keyUse)) {
            if (mapping.key == key) {
                mapping.clickCount = (mapping.clickCount - 1).coerceAtLeast(0)
            }
        }
    }

    /**
     * A press while the wheel is up: LEFT picks the cell under the cursor (a press inside the dead
     * zone picks nothing), RIGHT closes without swapping. Returns whether the wheel took the press.
     *
     * Why this exists: with no screen open, the first press vanilla sees goes to `grabMouse()`
     * (`MouseHandler.onButton`), which re-locks the cursor the wheel had just freed - so the wheel
     * could be looked at but never clicked. Closing the wheel here grabs the cursor ourselves, so
     * vanilla's own grab right after is a no-op, and the queued click is taken back on the next
     * tick instead of landing as a swing.
     *
     * The trigger key itself is never treated as a wheel click - [onTrigger] owns it, so a wheel
     * opened by a mouse button still closes the way its [WheelMode] says.
     */
    private fun handleWheelClick(key: InputConstants.Key, button: Int, action: Int): Boolean {
        if (!wheelOpen || action != GLFW.GLFW_PRESS || key == trigger) {
            return false
        }

        when (button) {
            GLFW.GLFW_MOUSE_BUTTON_LEFT -> finalizeWheel()
            GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                selectedIndex = -1
                selectedKey = null
                closeWheel()
            }
            else -> return false
        }

        // a wheel opened by HOLD must not cycle again when the bind is finally released
        holding = false
        pendingClickKey = key
        return true
    }

    private fun onTrigger(key: InputConstants.Key, action: Int) {
        if (trigger == InputConstants.UNKNOWN || trigger != key) {
            return
        }

        when (wheelMode) {
            WheelMode.HOLD -> handleHoldAction(action)
            WheelMode.TOGGLE -> handleToggleAction(action)
        }
    }

    private fun handleHoldAction(action: Int) {
        when (action) {
            GLFW.GLFW_PRESS -> if (mc.screen == null) {
                holding = true
                pressAt = System.currentTimeMillis()
            }

            GLFW.GLFW_RELEASE -> {
                if (wheelOpen) {
                    finalizeWheel()
                } else if (holding) {
                    cycleNext() // a tap
                }
                holding = false
            }
        }
    }

    private fun handleToggleAction(action: Int) {
        if (action != GLFW.GLFW_PRESS) {
            return
        }
        when {
            wheelOpen -> finalizeWheel()
            mc.screen != null -> Unit
            entries().size >= 3 -> openWheel()
            else -> cycleNext()
        }
    }

    /** Advances the cycle and queues the swap of the next available item into the off-hand. */
    private fun cycleNext() {
        val entries = entries()
        if (entries.isEmpty()) {
            return
        }

        // start == -1 (the last entry is gone) makes the first candidate index 0
        val start = cycleIndexIn(entries)
        for (offset in 1..entries.size) {
            val entry = entries[(start + offset) % entries.size]
            val slot = entry.slot ?: continue
            cycleKey = entry.key
            pendingFrom = slot
            return
        }
    }

    // --- Wheel ----------------------------------------------------------------------------------

    private fun openWheel() {
        wheelOpen = true
        selectedIndex = -1
        selectedKey = null
        mc.mouseHandler.releaseMouse()
    }

    private fun finalizeWheel() {
        val entries = entries()
        // by identity first, by the rendered index only as a fallback
        val entry = entries.firstOrNull { it.key == selectedKey }
            ?: entries.getOrNull(selectedIndex)
        entry?.slot?.let {
            cycleKey = entry.key
            pendingFrom = it
        }
        closeWheel()
    }

    private fun closeWheel() {
        if (wheelOpen) {
            wheelOpen = false
            mc.mouseHandler.grabMouse()
        }
    }

    @Suppress("unused", "detekt:LongMethod")
    private val renderHandler = handler<OverlayRenderEvent> { event ->
        val enabled = entries()

        // Open the wheel after the hold threshold (3+ cells only)
        if (holding && !wheelOpen && enabled.size >= 3 &&
            System.currentTimeMillis() - pressAt > HOLD_MS && mc.screen == null
        ) {
            openWheel()
        }
        // Safety: a screen opened or the module/conditions changed
        if (wheelOpen && (mc.screen != null || enabled.size < 3)) {
            closeWheel()
        }
        if (!wheelOpen) {
            return@handler
        }

        // Keep the cursor free for as long as the wheel is up: vanilla re-grabs it on any press it
        // gets to handle itself (`MouseHandler.onButton` -> `grabMouse()`), and a locked cursor
        // cannot point at a cell. Only ever runs once per foreign grab - `releaseMouse` is a no-op
        // when the cursor is already free - and it re-centres the cursor, i.e. into the dead zone.
        if (mc.mouseHandler.isMouseGrabbed) {
            mc.mouseHandler.releaseMouse()
        }

        val ctx = event.context
        val guiScale = mc.window.guiScale
        val cx = mc.window.guiScaledWidth * 0.5f
        val cy = mc.window.guiScaledHeight * 0.5f
        val mx = (mc.mouseHandler.xpos() / guiScale).toFloat()
        val my = (mc.mouseHandler.ypos() / guiScale).toFloat()

        val cellSize = 20f
        val deadZone = 14f
        val n = enabled.size
        // The ring grows with the cell count so every cell stays legible: with the spheres
        // expanded there can be a dozen of them, and at a fixed radius they would overlap.
        val ringRadius = max(46f, n * cellSize * 1.35f / (2f * Math.PI.toFloat()))
        val segAngle = (Math.PI * 2.0 / n).toFloat()
        val startAngle = (-Math.PI / 2.0).toFloat()

        // Update selection from the cursor angle — each item owns a full pie sector, so you
        // only need to point at its *region*, not the item itself
        val dx = mx - cx
        val dy = my - cy
        selectedIndex = if (hypot(dx, dy) < deadZone) {
            -1
        } else {
            val angle = atan2(dy, dx)
            val idx = Math.round((angle - startAngle) / segAngle)
            ((idx % n) + n) % n
        }
        selectedKey = enabled.getOrNull(selectedIndex)?.key

        // Backdrop ring
        ctx.drawCircle(cx, cy, ringRadius + cellSize, ringRadius - cellSize * 0.2f) {
            Color4b(12, 14, 20, 180).argb
        }

        // Highlight the selected sector (own geometry → matches the hit area exactly)
        if (selectedIndex in enabled.indices) {
            val sectorStart = startAngle + (selectedIndex - 0.5f) * segAngle
            val inner = deadZone
            val outer = ringRadius + cellSize
            val steps = 12
            val fill = Color4b(80, 95, 170, 90)
            for (s in 0 until steps) {
                val a0 = sectorStart + segAngle * (s / steps.toFloat())
                val a1 = sectorStart + segAngle * ((s + 1) / steps.toFloat())
                val ix0 = cx + inner * cos(a0); val iy0 = cy + inner * sin(a0)
                val ix1 = cx + inner * cos(a1); val iy1 = cy + inner * sin(a1)
                val ox0 = cx + outer * cos(a0); val oy0 = cy + outer * sin(a0)
                val ox1 = cx + outer * cos(a1); val oy1 = cy + outer * sin(a1)
                ctx.drawTriangle(ix0, iy0, ox0, oy0, ox1, oy1, fillColor = fill, cull = false)
                ctx.drawTriangle(ix0, iy0, ox1, oy1, ix1, iy1, fillColor = fill, cull = false)
            }
        }

        // Item cells around the ring
        for ((i, entry) in enabled.withIndex()) {
            val a = startAngle + i * segAngle
            val ix = cx + ringRadius * cos(a)
            val iy = cy + ringRadius * sin(a)
            val selected = i == selectedIndex
            val stack = entry.icon
            val dim = entry.slot == null

            val cellColor = if (selected) Color4b(70, 80, 140, 220) else Color4b(20, 22, 30, 200)
            val border = if (selected) Color4b(150, 160, 230, 255) else Color4b(60, 65, 85, 160)
            ctx.drawRoundedRect(
                ix - cellSize * 0.5f, iy - cellSize * 0.5f,
                ix + cellSize * 0.5f, iy + cellSize * 0.5f,
                4f, fillColor = cellColor, outlineColor = border, outlineWidth = 1f,
            )

            // Icon (dimmed when the item is not in the inventory)
            if (!stack.isEmpty) {
                if (dim) {
                    ctx.pose().pushMatrix()
                }
                ctx.item(stack, Math.round(ix - 8f), Math.round(iy - 8f))
                if (dim) {
                    ctx.pose().popMatrix()
                    ctx.drawRoundedRect(
                        ix - cellSize * 0.5f, iy - cellSize * 0.5f,
                        ix + cellSize * 0.5f, iy + cellSize * 0.5f,
                        4f, fillColor = Color4b(10, 12, 16, 130),
                    )
                }
            }
        }

        // Selected item name in the center (head names come from the server, so cap them)
        if (selectedIndex in enabled.indices) {
            val label = enabled[selectedIndex].label
            val name = if (label.length > MAX_LABEL_CHARS) {
                label.take(MAX_LABEL_CHARS - 1) + "..."
            } else {
                label
            }
            val w = mc.font.width(name)
            ctx.text(mc.font, name, (cx - w * 0.5f).toInt(), (cy - mc.font.lineHeight * 0.5f).toInt(), -1)
        }
    }

    // --- Hud2 integration -----------------------------------------------------------------------

    /** Snapshot for the Hud2 AutoSwap element: the off-hand item and the next item to cycle in. */
    class HudSnapshot(val current: ItemStack, val next: ItemStack)

    /** The current off-hand item and the next configured item AutoSwap would swap in, or `null`. */
    val hudSnapshot: HudSnapshot?
        get() {
            val localPlayer = mc.player ?: return null
            val entries = entries()
            if (entries.isEmpty()) {
                return null
            }
            val start = cycleIndexIn(entries)
            var next = ItemStack.EMPTY
            for (offset in 1..entries.size) {
                val entry = entries[(start + offset) % entries.size]
                if (entry.slot != null) {
                    next = entry.icon
                    break
                }
            }
            if (next.isEmpty) {
                next = entries[(start + 1) % entries.size].icon
            }
            return HudSnapshot(localPlayer.offhandItem, next)
        }

    /** Triggered by the Hud2 swap button — cycles the off-hand to the next configured item. */
    fun hudRequestSwap() = cycleNext()

    /** Short label for the trigger key, for the Hud2 swap button (empty when unbound). */
    val hudKeyLabel: String
        get() {
            val key = trigger
            if (key == InputConstants.UNKNOWN) {
                return ""
            }
            if (key.type == InputConstants.Type.MOUSE) {
                return "M${key.value + 1}"
            }
            return key.name.removePrefix("key.keyboard.").substringAfterLast('.').uppercase().take(5)
        }

    private fun defaultStack(item: SwapItem): ItemStack = when (item) {
        SwapItem.SPHERE -> Items.PLAYER_HEAD
        SwapItem.TOTEM -> Items.TOTEM_OF_UNDYING
        SwapItem.SHIELD -> Items.SHIELD
        SwapItem.GOLDEN_APPLE -> if (onlyEnchanted) Items.ENCHANTED_GOLDEN_APPLE else Items.GOLDEN_APPLE
        SwapItem.CROSSBOW -> Items.CROSSBOW
    }.defaultInstance

    // --- Swap execution (synced with GUI move via the scheduler) --------------------------------

    @Suppress("unused")
    private val scheduleHandler = handler<ScheduleInventoryActionEvent> { event ->
        val from = pendingFrom ?: return@handler
        pendingFrom = null
        event.schedule(constraints, InventoryAction.Click.performSwap(from = from, to = HotbarItemSlot.OFFHAND))
    }

    override fun onDisabled() {
        pendingFrom = null
        holding = false
        closeWheel()
        super.onDisabled()
    }
}
