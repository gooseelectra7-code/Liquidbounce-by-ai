# LiquidBounce 1.21.1 (Fabric/Kotlin) API шпаргалка для портированных модулей

> Собрано из `C:\Users\caves\Music\lb+b\LiquidBounce` (nextgen, Fabric 1.21.1). Примеры — фрагменты реального кода, без написания модулей.

---

## 1. PacketEvent & TransferOrigin

### Импорт
```kotlin
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
```

### Сигнатуры
```kotlin
class PacketEvent(
    val origin: TransferOrigin,
    val packet: Packet<*>,
    val original: Boolean = true
) : CancellableEvent()

enum class TransferOrigin {
    INCOMING,   // S2C
    OUTGOING    // C2S
}
```

### Пример: обработка пакета + отмена (VelocityModify)
```kotlin
private val packetHandler = handler<PacketEvent> { event ->
    when (val packet = event.packet) {
        is ClientboundSetEntityMotionPacket if packet.id == player.id -> {
            event.cancelEvent()
        }
        is ClientboundExplodePacket if packet.playerKnockback.isPresent -> {
            val knockback = packet.playerKnockback.get()
            knockback.x *= horizontal
            knockback.y *= vertical
            knockback.z *= horizontal
        }
    }
}
```

### Применение
- `event.cancelEvent()` — отменить пакет (не обрабатывать/не отправлять).
- `event.packet` — `net.minecraft.network.protocol.Packet<*>`; проверяй `is <тип>`.

---

## 2. Входящие пакеты скорости / задержка / блинк-очередь

### Импорт
```kotlin
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundExplodePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
```

### Сигнатуры
```kotlin
class BlinkPacketEvent(
    val packet: Packet<*>?,       // null = момент выбора действия (tick)
    val origin: TransferOrigin
) : Event() {
    var action: BlinkManager.Action = BlinkManager.Action.FLUSH
}

enum class BlinkManager.Action { FLUSH, QUEUE, PASS }
```

### Пример: задержка входящих пакетов (Backtrack)
```kotlin
private val queuePacketHandler = handler<BlinkPacketEvent> { event ->
    if (event.origin != TransferOrigin.INCOMING) return@handler

    when (val packet = event.packet) {
        is ClientboundPlayerPositionPacket, is ClientboundDisconnectPacket -> {
            clear(true)
            return@handler
        }
    }

    // Сравнение позиции цели — решаем, обрабатывать или задержать
    event.action = BlinkManager.Action.QUEUE
}

// Позже — flush
BlinkManager.flush(TransferOrigin.INCOMING)
BlinkManager.flush { snapshot ->
    snapshot.origin == TransferOrigin.INCOMING && snapshot.timestamp <= now - delay
}
BlinkManager.packetQueue.removeIf { snapshot -> snapshot.origin == TransferOrigin.INCOMING }
```

### Пример: обработка / отмена входящей скорости (VelocityGrim2371)
```kotlin
private val packetHandler = sequenceHandler<PacketEvent> { event ->
    if (event.packet.isLocalPlayerDamage()) {
        cancelNextVelocity = true
    } else if (cancelNextVelocity && event.packet.isLocalPlayerVelocity()) {
        event.cancelEvent()
    }
}
```

### Хелперы
```kotlin
// PacketExtensions.kt
fun Packet<*>?.isLocalPlayerVelocity(considerExplosion: Boolean = true): Boolean
fun Packet<*>?.isLocalPlayerDamage(): Boolean

// Использование ClientboundSetEntityMotionPacket
packet.id == mc.player?.id
packet.movement.x = ...

// ClientboundExplodePacket
packet.playerKnockback.isPresent
packet.playerKnockback.get().x *= ...

// ClientboundPlayerPositionPacket
Rotation(packet.change.yRot, packet.change.xRot)
```

---

## 3. Переключение слотов и тихий своп (sendHeldItemChange / SilentHotbar)

### Импорт
```kotlin
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.network.sendHeldItemChange
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
```

### Отправка ручного переключения слота
```kotlin
// NetworkUtils.kt
fun ClientCommonPacketListenerImpl.sendHeldItemChange(slot: Int) {
    send(ServerboundSetCarriedItemPacket(slot))
}

// Пример: мгновенный своп взад-вперёд (clickBlockWithSlot)
this.inventory.selectedSlot = slot
if (slot != prevHotbarSlot) {
    connection.sendHeldItemChange(slot)
}
// ... interact ...
if (slot != prevHotbarSlot && hand == InteractionHand.MAIN_HAND && switchMode == SwitchMode.SILENT) {
    connection.sendHeldItemChange(prevHotbarSlot)
}
this.inventory.selectedSlot = prevHotbarSlot
```

### Тихий своп через SilentHotbar (Scaffold / AutoTrap)
```kotlin
object SilentHotbar : EventListener {
    val serversideSlot: Int
    val clientsideSlot: Int

    fun selectSlotSilently(requester: Any?, slot: Int, ticksUntilReset: Int)
    fun selectSlotSilently(requester: Any?, slot: HotbarItemSlot, ticksUntilReset: Int)
    fun resetSlot(requester: Any?)
    fun isSlotModified(): Boolean
    fun isSlotModifiedBy(requester: Any?): Boolean
}

// Пример использования
context(requester: EventListener)
fun useHotbarSlotOrOffhand(
    slot: HotbarItemSlot,
    ticksUntilReset: Int = 1,
    yRot: Float = RotationManager.currentRotation?.yRot ?: player.yRot,
    xRot: Float = RotationManager.currentRotation?.xRot ?: player.xRot,
    swingMode: SwingMode = SwingMode.DO_NOT_HIDE,
): InteractionResult {
    SilentHotbar.selectSlotSilently(requester, slot, ticksUntilReset)
    return net.ccbluex.liquidbounce.utils.entity.useItem(slot.useHand, yRot, xRot, swingMode)
}
```

---

## 4. RotationManager / RotationsValueGroup

### Импорт
```kotlin
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.kotlin.Priority
```

### Сигнатуры
```kotlin
object RotationManager : EventListener {
    var currentRotation: Rotation?             // «наша» целевая ротация (не всегда серверная)
    var actualServerRotation: Rotation         // серверная ротация (из исходящих пакетов)
    var serverRotation: Rotation               // с учётом блинка/фриза

    fun setRotationTarget(
        rotation: Rotation,
        considerInventory: Boolean = true,
        valueGroup: RotationsValueGroup,
        priority: Priority,
        provider: ClientModule,
        whenReached: RestrictedSingleUseAction? = null
    )

    fun setRotationTarget(plan: RotationTarget, priority: Priority, provider: ClientModule)

    fun isRotatingAllowed(rotationTarget: RotationTarget): Boolean
    fun update()
}

open class RotationsValueGroup(
    owner: EventListener,
    movementCorrection: MovementCorrection = MovementCorrection.SILENT,
    combatSpecific: Boolean = false
) : ValueGroup("Rotations")

class RotationTarget(
    val rotation: Rotation,
    var entity: Entity? = null,
    val processors: List<RotationProcessor> = emptyList(),
    val ticksUntilReset: Int,
    val resetThreshold: Float,
    val considerInventory: Boolean,
    val movementCorrection: MovementCorrection,
    val whenReached: RestrictedSingleUseAction? = null
)

// Rotation
@JvmRecord
data class Rotation(
    val yaw: Float,
    val pitch: Float,
    val isNormalized: Boolean = false
)
```

### Пример: установка целевой ротации
```kotlin
private val rotations = tree(RotationsValueGroup(this, combatSpecific = true))

val targetRotation = Rotation.lookingAt(targetPos, player.eyePosition)
RotationManager.setRotationTarget(
    targetRotation,
    considerInventory = true,
    valueGroup = rotations,
    priority = Priority.IMPORTANT,
    provider = this,
    whenReached = { /* do something once reached */ }
)
```

### Пример: чтение текущей / серверной ротации
```kotlin
val yaw = RotationManager.currentRotation?.yRot ?: player.yRot
val pitch = RotationManager.currentRotation?.xRot ?: player.xRot
val serverRot = RotationManager.serverRotation
```

### MovementCorrection
```kotlin
enum class MovementCorrection {
    OFF,         // нет коррекции стрейфа
    SILENT,      // по умолчанию, коррекция движения под тихую ротацию
    STRICT,      // ещё более жёсткая
    CHANGE_LOOK, // видимая камера
    CAM_SNAP     // видимая камера с откатом
}
```

---

## 5. interactBlock / useItemOn

### Импорт
```kotlin
import net.ccbluex.liquidbounce.utils.entity.interactBlock
import net.ccbluex.liquidbounce.utils.entity.interactBlockLikeVanilla
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.entity.useItemStrict
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.minecraft.world.InteractionHand
import net.minecraft.world.phys.BlockHitResult
```

### Сигнатуры
```kotlin
fun interactBlock(
    hitResult: BlockHitResult,
    hand: InteractionHand = InteractionHand.MAIN_HAND,
    swingMode: SwingMode = SwingMode.DO_NOT_HIDE
): InteractionResult

fun interactBlockLikeVanilla(
    hitResult: BlockHitResult,
    swingMode: SwingMode = SwingMode.DO_NOT_HIDE,
    rotation: Rotation = RotationManager.currentRotation ?: player.rotation
): StrictInteractionResult?

fun useItem(
    hand: InteractionHand,
    yRot: Float = RotationManager.currentRotation?.yRot ?: player.yRot,
    xRot: Float = RotationManager.currentRotation?.xRot ?: player.xRot,
    swingMode: SwingMode = SwingMode.DO_NOT_HIDE
): InteractionResult

fun useItemStrict(...): StrictInteractionResult?
```

### Пример: клик по блоку (useItemOn) с ротациями
```kotlin
val result = interactBlock(hitResult, InteractionHand.MAIN_HAND, SwingMode.DO_NOT_HIDE)

// или через gameMode напрямую (NetworkUtils.kt)
interaction.useItemOn(player, InteractionHand.MAIN_HAND, hitResult)
mc.gameMode?.useItemOn(player, hand, hitResult)

// Использование предмета с кастомной ротацией
useItem(InteractionHand.MAIN_HAND, yRot, xRot, SwingMode.HIDE_SERVER)
```

### SwingMode
```kotlin
enum class SwingMode : Tagged, Consumer<InteractionHand> {
    DO_NOT_HIDE,  // клиент + сервер
    HIDE_BOTH,    // никому
    HIDE_CLIENT,  // только серверный пакет ServerboundSwingPacket
    HIDE_SERVER   // только клиентская анимация
}

SwingMode.swing(hand)
SwingMode.accept(hand)
```

---

## 6. handler / tickHandler

### Импорт
```kotlin
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.event.sequenceHandler
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
```

### Обычный handler (синхронный, отменяемый)
```kotlin
inline fun <reified T : Event> EventListener.handler(
    priority: Short = 0,
    handler: Consumer<T>
): EventHook<T>

// Пример: каждый game tick
private val tickHandler = handler<GameTickEvent> {
    // код
}

// Пример: отменить PlayerTickEvent
private val playerTickHandler = handler<PlayerTickEvent> { event ->
    event.cancelEvent()
}

// Пример: PacketEvent с приоритетом
private val packetHandler = handler<PacketEvent>(priority = 100) { event ->
    // ...
}
```

### tickHandler (сопрограмма, повторяется каждый tick)
```kotlin
inline fun EventListener.tickHandler(
    dispatcher: CoroutineDispatcher? = null,
    onCancellation: Runnable? = null,
    crossinline eventHandler: suspend CoroutineScope.() -> Unit
)

// Пример
private val tickHandler = tickHandler {
    waitTicks(5)
    // ...
}
```

### sequenceHandler (сопрограмма, запускается на событии)
```kotlin
// Пример: последовательность на пакете
private val packetHandler = sequenceHandler<PacketEvent> { event ->
    if (event.packet is ClientboundBlockUpdatePacket) {
        waitTicks(1)
    }
}
```

### Вспомогательные ожидания (для suspend)
```kotlin
// SuspendHandlers.kt
suspend fun waitTicks(ticks: Int)
// есть waitMatches / waitMatchesWithTimeout
```

---

## 7. Краткий референс по событиям

| Событие | Когда | Пример использования |
|---------|-------|----------------------|
| `GameTickEvent` | Каждый тик клиента | tick-логика модуля |
| `PlayerTickEvent` | Перед тиком игрока | отмена движения/действий |
| `PacketEvent` | Пакет | обработка/отмена пакетов |
| `BlinkPacketEvent` | Выбор действия с блинк-очередью | Backtrack, FakeLag |
| `WorldRenderEvent` | Отрисовка мира | ESP, траектории |
| `AttackEntityEvent` | Атака | Criticals, SuperKnockback |
| `RotationUpdateEvent` | Обновление ротаций | Aimbot |
| `MouseRotationEvent` | Движение мыши | видимая камера |
| `WorldChangeEvent` | Смена мира | сброс состояния |

---

## 8. Частые пакеты (сниппеты)

```kotlin
// S2C
ClientboundSetEntityMotionPacket(id, movement)
ClientboundExplodePacket(playerKnockback: Optional<Vec3>)
ClientboundPlayerPositionPacket(change: ClientboundPlayerPositionPacket.RelativeArgument)
ClientboundSetHealthPacket(health)
ClientboundDamageEventPacket(entityId)
ClientboundBlockUpdatePacket(pos, blockState)
ClientboundDisconnectPacket

// C2S
ServerboundMovePlayerPacket.Pos, .Rot, .PosRot, .StatusOnly
ServerboundUseItemOnPacket(hand, hitResult, sequence)
ServerboundUseItemPacket(hand, sequence, yRot, xRot)
ServerboundAttackPacket(entityId)
ServerboundInteractPacket
ServerboundSetCarriedItemPacket(slot)
ServerboundPlayerActionPacket(action, pos, direction)
ServerboundSwingPacket(hand)
ServerboundPongPacket
```
