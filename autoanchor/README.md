# AutoAnchor — файлы для порта в другой клиент

Источник: `LBnew-main` (LiquidBounce fork, Minecraft 26.1.2, Fabric, Kotlin).
Модуль: `features/module/modules/combat/autoanchor/` (оригинальный, построен по доктрине
CrystalAura — см. `AGENTS.md` репозитория, раздел «Custom module map» → `combat/autoanchor/`).

## Что здесь лежит

- `main/autoanchor/` — сам модуль целиком (22 файла):
  - `calc/` — модель урона (`AnchorMath`, `ClipCore`, `Exposure`, `VictimPipeline`,
    `EnchantProtection`; power 5.0, центр в `pos.getCenter()`, victim pipeline тот же, что у кристалла)
  - `world/AnchorView` — per-tick снимок мира
  - `scan/` — branch-and-bound поиск (`AnchorSearch`)
  - `seq/` — `AnchorFlow` (чистая, headless, юнит-тестированная машина состояний
    place → charge → detonate) + `AnchorJob` (клиентская оболочка)
  - `aim/` — реплика `LocalPlayer.pick` + сглаживание
  - `exec/` — исполнитель (`AnchorExecutor`)
  - `state/` — реестр якорей (`AnchorLedger`, включая blast-shadow защиту от Grim AirLiquidPlace)
  - `net/PacketIntake` — очередь пакетов из netty-потока
  - `render/` — оверлей (`AnchorVisuals`, `AnchorRenderer`, `AnchorDanger` — диагностические кольца)
  - `ModuleAutoAnchor.kt` — точка входа, `AnchorConfig.kt` — настройки
- `test/autoanchor/` — юнит-тесты (10 файлов, oracle = ванильный код через `FakeBlockGetter`;
  `AnchorRankingTest` сторожит инвариант пустого shape клетки взрыва)
- `shared-render/render/` — общий рендер-хелпер оверлея (`combat/aura/render/`), тот же пакет,
  что и у CrystalAura (soft geometry, tweens, glow, blast dome, text; `AuraBlastDome.release`
  принимает OWNER — см. KDoc)
- `shaders/` — GLSL-шейдера оверлея (`aura_glow`, `blast_dome`, `scene_copy`)

## Ключевые факты ваниллы, на которых построен модуль (не выводить заново)

- place/charge/detonate — три `UseItemOn` одного класса; Grim `PacketOrderI` разрешает ОДНУ
  интеракцию за тик, цикл = до 3 тиков, батчить нельзя
- `RespawnAnchorBlock.explode` удаляет блок ДО взрыва → клетка кандидата считается пустой
  для лучей экспозиции (обратная ошибка обнуляет экспозицию)
- `bad_respawn_point` в `#is_explosion`, не в `bypasses_*` → броня/щит/i-frames/Resistance/
  оба Protection применяются как у кристалла
- Заряд предсказывается клиентом, детонация — НЕТ; ledger построен на этой асимметрии
- Glowstone в оффхенде → `useItemOn` возвращает `PASS` (заряжает вместо детонации)

## Внешние зависимости (НЕ скопированы — фреймворк LiquidBounce)

- Модульная система: `ClientModule`, `ModuleCategories`, `MinecraftShortcuts`, Value DSL
- События: `RotationUpdateEvent`, `InputHandleEvent`, `PacketEvent`, `WorldRenderEvent`,
  `OverlayRenderEvent`, `WorldChangeEvent`, `TransferOrigin`, `EventListener`/`handler`
- Ротации: `RotationManager`, `RotationTarget`, `Rotation`, `RotationUtil`, `MovementCorrection`,
  `RotationProcessor`
- Блоки/сущности: `canBeReplacedWith`, `isInteractable`, `searchBlocksInCuboid`, `SwingMode`,
  `TargetTracker`, `TargetPriority`, `PositionExtrapolation`, `getBoundingBoxAt`,
  `getActualHealth`, `interactBlock`, `interpolateCurrentPosition`, `ping`, `rotation`,
  `utils.world.respawnAnchorWorks`
- Инвентарь: `Slots`, `HotbarItemSlot`, `HandSwapGate`, `SilentHotbar`
- Рендер-инфраструктура: `WorldRenderEnvironment`, `renderEnvironmentForWorld`, `Color4b`,
  `Vec3f`, `FontManager`/`FontFace`, `WorldToScreen` (пайплайны `AuraGlow`/`AuraSceneCopy`/
  `AuraBlastDome` регистрируются в `ClientShaders.kt` / `ClientRenderPipelines.kt`)
- Прочее: `Chronometer`, `FloatValueProvider`, `chat`/`mc`/`player`/`world`, `Priority`,
  `samplePointOnSide`
- Кросс-модульные ссылки (НЕ нужны для порта как код, нужны как концепция арбитрации):
  `ModuleCrystalAura` и `ModuleAnchorOptimizer` — цепочка аур KillAura < AutoAnchor <
  CrystalAura через воздержание (`standDownReason`, флаг `isActing`). В целевом клиенте
  заменить аналогичным механизмом или убрать.

## Что НЕ входит (осознанно)

- `ModuleAnchorOptimizer` и `utils/combat/autoanchor/` (`AnchorAimSearch`,
  `RotationConvergenceTracker`) — это ДРУГОЙ модуль (charge/pop чужих и своих якорей без
  модели урона), AutoAnchor их не использует.
