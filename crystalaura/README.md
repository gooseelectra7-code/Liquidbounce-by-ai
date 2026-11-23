# CrystalAura — файлы для порта в другой клиент

Источник: `LBnew-main` (LiquidBounce fork, Minecraft 26.1.2, Fabric, Kotlin).
Модуль: `features/module/modules/combat/crystalaura/`.

## Что здесь лежит

- `main/crystalaura/` — сам модуль целиком (28 файлов):
  - `calc/` — ванилла-точная модель урона (`ClipCore`, `Exposure`, `VictimPipeline`, `EnchantProtection`)
  - `world/WorldView` — посекундный (per-tick) снимок мира
  - `scan/` + `base/` — branch-and-bound поиск позиций (place/break/base)
  - `mine/` — AutoMine
  - `aim/` — реплика `LocalPlayer.pick` + сглаживание
  - `exec/` — планировщик тика + исполнитель
  - `state/` — реестр кристаллов (ledger)
  - `net/PacketIntake` — очередь пакетов из netty-потока
  - `render/` — оверлей «Detonation»
  - `ModuleCrystalAura.kt` — точка входа, `CrystalConfig.kt` — настройки
- `test/crystalaura/` — 110 юнит-тестов (oracle = ванильный код через `FakeBlockGetter`)
- `shared-render/render/` — общий рендер-хелпер оверлея (`combat/aura/render/`), который
  модуль использует (soft geometry, tweens, glow, blast dome, text)
- `shaders/` — 4 GLSL-шейдера оверлея (`aura_glow`, `blast_dome`, `forge_haze`, `scene_copy`)
- `deps/utils/block/MiningClaim.kt` — арбитрация копания между модулями (используется `mine/`)

## Внешние зависимости (НЕ скопированы — это фреймворк LiquidBounce)

Модуль ссылается на API клиента; при порте их нужно заменить аналогами целевого клиента:

- Модульная система: `ClientModule`, `ModuleCategories`, `MinecraftShortcuts`, Value DSL
  (`ToggleableValueGroup`, `ValueGroup`, `Tagged`)
- События: `EventListener`, `handler`, `RotationUpdateEvent`, `InputHandleEvent`, `PacketEvent`,
  `WorldRenderEvent`, `OverlayRenderEvent`, `WorldChangeEvent`, `WorldEntityRemoveEvent`,
  `CancelBlockBreakingEvent`, `TransferOrigin`
- Ротации: `RotationManager`, `RotationTarget`, `Rotation`, `RotationUtil`, `MovementCorrection`,
  `RotationProcessor`
- Блоки/сущности: `doBreak`, `canBeReplacedWith`, `isInteractable`, `searchBlocksInCuboid`,
  `SwingMode`, `TargetTracker`, `TargetPriority`, `attackEntity`, `useItem`, `interactBlock`,
  `PositionExtrapolation`, `getBoundingBoxAt`, `getActualHealth`, `explosionDamageBreakdown`,
  `StrictInteractionResult/Source`
- Инвентарь: `Slots`, `HotbarItemSlot`, `HandSwapGate`, `SilentHotbar`, `findBestToolToMineBlock`
- Рендер-инфраструктура: `ClientRenderPipelines`, `ClientUniformDefine`, `WorldRenderEnvironment`,
  `renderEnvironmentForWorld`, `LazyRenderTargetHolder`, `Color4b`, `Vec3f`, `FontManager`,
  `WorldToScreen`, `writeStd140` (шейдеры регистрируются в коде — см. `ClientShaders.kt`,
  пайплайны `AuraGlow`/`AuraSceneCopy`/`AuraBlastDome`/`AuraForgeHaze` в `ClientRenderPipelines.kt`)
- Прочее: `Chronometer`, `FloatValueProvider`, `chat`/`logger`/`mc`/`player`/`world`,
  `Priority`, математика (`distanceToSqr`, `getNearestPoint`, `samplePointOnSide`)

Инварианты и доктрина модуля описаны в `AGENTS.md` репозитория (разделы
«Custom module map» → `combat/crystalaura/` и «CrystalAura: the doctrine it was built under») —
читать перед изменением логики.
