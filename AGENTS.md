# AGENTS.md — LiquidBounce (lb+b) build & deploy notes

Notes for AI agents working in this repo. Keep this file updated when the
build/deploy workflow changes.

## Start here (new chat)

- Build: `.\gradlew.bat build -x detekt -x test` (detekt fails on a
  pre-existing issue — ALWAYS skip it). Fast check:
  `.\gradlew.bat compileKotlin --console=plain`.
- Deploy: copy `build\libs\LBnew-0.38.1.jar` into the Prism mods folder
  (path in Deploy) and tell the user to relaunch. Runtime logs:
  `C:/Users/caves/AppData/Roaming/PrismLauncher/instances/LB/minecraft/logs/latest.log`.
- Git: conventional scoped commits; ask before committing, then push to
  `origin main` AUTOMATICALLY — no second confirmation (see the Git section).
- Custom ("ours") modules: see the map below. Ports come from source trees
  in `C:\Users\caves\Music\` (paths in the Porting section).
- Grim-safety is a HARD requirement for anything that sends packets. **Clones live at
  `C:\Users\caves\Music\Grim` (HEAD `8eb5f28`) and `C:\Users\caves\Music\TotemGuard` (branch `3.0`),
  re-cloned 2026-09-20 at the user's direction.** The older `Music\lb+b\New folder\Grim` path is gone; do
  not look for it. If they disappear again: `git clone --depth 1 https://github.com/GrimAnticheat/Grim`
  and `git -c core.longpaths=true clone --depth 1 --branch 3.0 https://github.com/Bram1903/TotemGuard`
  (without `core.longpaths` the TotemGuard test-plugin path fails checkout with `Filename too long`).
  Per-module invariants: Porting section; audit workflow: "Adding a new module" section.
- **READ THE NEXT SECTION BEFORE CITING A CHECK.** Most of the checks this file's KDocs treat as hard
  constraints are `experimental` and OFF on a default server.
- Language: the user chats in Russian; code, comments, docs and commit
  messages stay in English.
- Honesty: every claim cited `file:line`, "done" = compiled + invariants
  re-checked, mandatory final-report format — see "Truthfulness & reporting
  protocol" below. Subagent reports are claims to re-verify — see "Agent
  usage protocol". Both apply to every model and every subagent.

## Which Grim checks are actually live (read from the clone, 2026-09-20)

`@CheckData(..., experimental = true)` makes a check a NO-OP unless the server opted in:
`Check.java:141` / `:157` return early on `experimental && !player.isExperimentalChecks()`, and every shipped
config ships `experimental-checks: false` (`common/src/main/resources/config/en.yml:199-200`, same in de/es/fr/
it/ja/nl), with the code default also false (`ExperimentalChecksFeature.java:30`,
`config.getBooleanElse("experimental-checks", false)`).

Classified from the clone at `8eb5f28`:

- **LIVE by default:** `MultiActionsC`, `AirLiquidPlace`, `AirLiquidBreak`, `RotationPlace`, `BadPacketsA`,
  `FarPlace`, `PositionBreakB`, `WrongBreak`, `FastBreak`.
- **EXPERIMENTAL, off by default:** `PacketOrderI`, `PacketOrderJ`, `PacketOrderO`, `MultiActionsB`,
  `MultiActionsE`, `MultiActionsF`, `MultiActionsG`, `DuplicateRotPlace`, `MultiPlace`, `NoSwingBreak`.

This matters because the SECOND list is what most of this client's cross-module arbitration is built on:
"one attack and one use per tick, a dig owns its whole tick" is `PacketOrderI`; "a block interaction and an
entity interaction may not share a window" is `MultiActionsF`; "take the aim once per cycle" is
`DuplicateRotPlace`. All three are opt-in. The KDocs across `combat/` state them as live constraints; that
wording is wrong and was never corrected after the checks were made experimental upstream.

Do NOT read this as licence to delete the arbitration - a server can flip one switch, and the user's rule is
that Grim-safety is hard. Read it as the cost model: a change that only buys safety against an experimental
check is worth far less than one that buys safety against `RotationPlace`, `AirLiquidPlace` or `AirLiquidBreak`,
and should be priced accordingly before it adds complexity.

One detail worth keeping: `MultiActionsF`'s entity side is `INTERACT_ENTITY`, `ATTACK` and `SPECTATE_ENTITY`
(`MultiActionsF.java:57-59`) - a SWING is not in the set, and neither is it in `PacketOrderI`'s. Claims in this
repo that a stray swing pairs with a block interaction are unfounded.

## Truthfulness & reporting protocol (all models, all agents)

A wrong claim costs the user a live playtest. These rules override speed;
when they conflict with being fast or agreeable, be slow and correct.

### Claims
1. **Cite or don't claim.** Any statement about this codebase, Minecraft,
   Grim or NCP must be backed by something you read THIS session — cite
   `file:line`. Can't cite it → label it "not verified".
2. **Symbols are looked up, never recalled.** Before using any method /
   field / event / setting name, grep for it and copy the signature you
   found. If grep finds nothing, the symbol does not exist — say so
   instead of inventing a plausible one.
3. **Three truth states, never silently upgraded:** VERIFIED (ran /
   compiled / read it this session), INFERRED (deduced — name the
   premises), GUESS (labeled as such). "Should", "probably", "likely"
   are GUESS markers — and a GUESS about a mechanic is not a basis for
   writing code.
4. **Mechanic before code.** Anything that depends on game or anticheat
   behavior starts by reading the actual source (Grim clone:
   `C:\Users\caves\Music\lb+b\New folder\Grim`; MC via the mappings in this repo).
   Implementing a guessed mechanic is the most expensive failure mode
   in this repo.
5. **A checkable claim must be checked.** If one grep / read / compile
   settles it, run it before asserting it. "I don't know, checking" is
   always acceptable; a confident guess is not.
6. **Wrong premises get reported, not built on.** If evidence contradicts
   the task's assumption, stop and say so before writing code.

### Definition of done
7. "Done" = code written AND `.\gradlew.bat compileKotlin --console=plain`
   passed AND the invariants of every module you touched (Porting
   section) re-checked. Anything less is reported as its actual state:
   "written, NOT compiled".
8. **Never report an outcome you did not observe.** A failed command is
   quoted verbatim BEFORE any commentary and never softened into
   "partially works". In-game behavior is verifiable only by the user's
   playtest — never claim "works in game" or "won't flag"; claim what
   you verified and what the cited sources support.

### Final report — mandatory format, nothing after it
- **Changed:** each file + one line of what/why.
- **Verified:** the exact commands run and their real results.
  Nothing run → write "Nothing verified."
- **Not done / not verified:** every requirement of the task not fully
  covered, and every claim you could not check. An empty section here is
  itself a strong claim — make sure it is true.
- **Assumptions & risks:** what you assumed without proof; what can
  break in game.

Style: plain factual sentences. No celebration, no "should work", no
selling your own diff. A report that hides a failure or inflates
completeness is a worse defect than the failure itself.

## Agent usage protocol (orchestrating subagents)

An agent is a worker, not a witness: it runs the same models with the
same failure modes, minus your conversation context. Its report is a
CLAIM to be verified, not a result.

### Prompting
1. **Context is injected, never assumed.** An agent has not read this
   conversation and may not have read this file. Every prompt names the
   exact files to read, the AGENTS.md sections/invariants that bind the
   task (quote them or name the section), and the definition of done.
   A vague prompt returns plausible garbage.
2. **One concern per agent.** Small, verifiable deliverables: one module,
   one check family, one file. Architecture decisions stay in the main
   session — agents execute, they don't decide.
3. **Impose the Truthfulness protocol in the prompt**: require `file:line`
   citations, raw evidence (quoted source, command output) instead of
   conclusions, and the Changed / Verified / Not done report format.
4. **Read-only by default.** Research and audit agents get the read-only
   (Explore) type. Writing agents only for an actual edit task with the
   file list spelled out; parallel writers only on disjoint files (see
   "Splitting large modules") or in worktree isolation. Independent
   agents launch in parallel; dependent work runs sequentially.

### Trusting results
5. **No agent claim is VERIFIED until re-checked in the main session.**
   Code: run `compileKotlin` yourself — never accept "it compiles".
   Ports: diff review + spot-check disputed lines against the original
   (Porting checklist, item 4). Audit findings: open the cited source at
   the cited line for at least the load-bearing claims.
6. **"Found nothing" is the least trustworthy report.** Absence claims
   (no such check, no other callers, nothing else flags) get a direct
   grep or a second, differently-phrased agent before being believed.
7. **Verification of your own work goes to a fresh agent with zero
   context** — the screenshot-forensics rule generalizes: the verifier
   must not have seen your implementation or your hypothesis, and is
   asked to REFUTE the claim, not to confirm it.

### Known-good patterns in this repo
- Grim audit: swarm of read-only agents, one per check family, each
  quoting the check source from the Grim clone ("Adding a new module").
- Parallel edits: split the module first ("Splitting large modules"),
  one agent per file.
- UI from screenshots: forensic-spec agent whose prompt contains ONLY
  the image path ("Reproducing UI" section).

## Custom module map ("ours", not upstream)

- `combat/ModuleAutoTotem.kt` — **AutoTotem**, a port of javelin's
  `tech.javelin.features.module.impl.combat.AutoTotem` (source supplied inline by the user,
  Yarn mappings). It REPLACED the SystemDLC port that lived in this file until 2026-09-12; that
  module's Hand/main-hand path, TNT + trident triggers, sacrificial-talisman filter,
  PreferUnenchanted, SkipOnCooldown, NoSwapWhileEating, WithoutFireworks and the whole AntiGhost
  tree (Rescue / Park, on `OffhandSyncTracker` + `SilentHotbar`) are GONE — do not resurrect them
  from git history without asking; the user asked for the javelin logic specifically.
  Offhand-only, no rotations, no hotbar selection. Invariants, do NOT regress:
  - Every click goes through `ScheduleInventoryActionEvent` + `InventoryAction.Click.performSwap`
    (javelin's `swapManager.grimSwap(0, slot, 40, SWAP)`), so the Constraints tree, click delays and
    `HandSwapGate` all apply. Never emit a container click from this module directly.
  - `cooldownTicks` gates the SWAP BACK only, never the equip: 10 ticks after a crystal within
    dx/dz 7, dy 4 and not above us vanished, 6 after our own totem popped, 6 while falling.
  - The 300 ms `tasksDelay` covers BOTH branches here, unlike javelin, because
    `ScheduleInventoryActionEvent` fires once per InventoryManager CYCLE, not once per tick — an
    ungated branch is a click spammer.
  - `savedStack` survives until the item is really back in the offhand (a chain refused by the
    Constraints tree would otherwise lose it for good). Javelin clears on emit; we do not.
  - `savedGeneralStack` is compared by IDENTITY (`===`), matching javelin's `stack.equals(...)`:
    ItemStack does not override equals and a client-side swap moves the instance, so it means
    "any enchanted totem but the one we started with". A container resync breaks it by design.
  - Add / remove / entity-event packets are parked on the netty thread and replayed IN ORDER at the
    head of the tick; never read entities on the netty thread. The crystal-position ledger is
    rebuilt from the world each tick AND topped up from `ClientboundAddEntityPacket`, because a
    crystal placed and destroyed inside one packet drain never exists at a tick boundary and its
    removal would otherwise have no position to test.
  - **`isSwapping` is the anti-ghost gate** (2026-09-13, the user's report "totems ghost while the
    auras work"). It is up for all three phases of a swap of ours: scheduled but not sent (the
    ticks `InventoryManager` spends opening the inventory and waiting out `StartDelay` — nothing
    else covers those, `HandSwapGate` arms on the OUTGOING packet), on the wire, and sent but
    unconfirmed (`OffhandSyncTracker.isUnconfirmed`). `CrystalAura` and `AutoAnchor` stand fully
    down on it. The mechanism is the STATE ID: any inventory slot an aura consumes server-side
    bumps the open menu's state id, our click still carries the older one, and
    `handleContainerClick` answers it with a full `ClientboundContainerSetContentPacket` resync
    (`ServerGamePacketListenerImpl.java:1915-1916`) that overwrites the predicted offhand — the
    ghost. Both dead-man's switches (500 ms pending, 1000 ms unconfirmed) and the
    `canPerformAction` check in `armSwapWindow` are load-bearing: a gate that stands two modules
    down must never be able to stick, and a Constraints requirement that keeps failing
    (`NoMovement` while running) would otherwise re-arm a window no packet ever ends.
  OVERLAP: `player/offhand/ModuleOffhand.kt` (alias "AutoTotem") manages offhand totems too —
  enable only one of the two.
  - **`Route` (2026-09-14, the user asked for a main-hand swap).** `OffhandClick` is everything
    above and stays the DEFAULT. `MainHand` keeps a totem staged in the hotbar through
    `combat/totem/TotemStaging` and then presses the vanilla offhand swap on that slot:
    `SetCarriedItem` -> `SWAP_ITEM_WITH_OFFHAND` -> `SetCarriedItem` back. Invariants:
    - The staging swap is PRE-staged, outside `condition()` - it runs in `scheduleHandler` on every
      cycle whatever the health is. A container click on the critical path of the swap it feeds is
      the thing this route exists to remove; putting it back inside `condition()` regresses the
      whole point.
    - The held slot moved around the action is the one the server HOLDS: the tracker's `carriedIndex`,
      synced first by `ensureHasSentCarriedItem` (`utils/network/sendSwapHotbarSlotWithOffhand`,
      2026-10-03). Not `Inventory.selected` (the client's slot) and not `SilentHotbar.serversideSlot`
      either - that is what the tracker announces LATER this tick, after a module took or released a
      silent slot; bracketing with it swapped the wrong slot or sent a duplicate `SetCarriedItem`
      (BadPacketsA, live). This entry used to prescribe `serversideSlot`.
    - `armSwapWindow` is deliberately NOT called on this route. There is no scheduled-but-unsent
      phase (the action is on the wire immediately), `HandSwapGate` arms on
      `SWAP_ITEM_WITH_OFFHAND` by itself, and there is no unconfirmed phase either because a player
      action carries no state id and `OffhandSyncTracker.touchesOffhand` only matches container
      clicks. `TotemStaging.isStaging` is the whole anti-ghost window and `isSwapping` reports it.
    - `HandSwapGate.holdsClicks` is read by hand in `equipViaMainHand`: nothing in the schedule
      paces a player action, so without it a swap could be sent inside another swap's window.
    - `reset()` clears `TotemStaging` only on this route - the object is shared with
      `ModuleMainHandTotem` and has no ownership tracking.
    - NOT Grim-audited: the clone this file points at is gone from the machine, so no check source
      could be read. The three-packet burst (two `SetCarriedItem` in one tick) is not vanilla's
      shape - vanilla emits at most one, from `ensureHasSentCarriedItem` - though it is the shape
      `player/offhand/ModuleOffhand`'s SMART switch already uses here. Treat as UNVERIFIED.
- `combat/totem/TotemStaging.kt` + `combat/totem/ModuleMainHandTotem.kt` — **MainHandTotem** and the
  hotbar staging both totem features share, written 2026-09-14 on the user's description. Staging is
  a `ValueGroup` mounted in `GlobalManager` next to `HandSwapGate`, for the same reason that one is
  global: two features moving items into one hotbar slot must agree on which slot and on what
  happens to the item they displace. Invariants, do NOT regress:
  - `ensureStaged` / `releaseDisplaced` take the CALLER's `InventoryConstraints` instead of owning
    one, so AutoTotem's staging clicks stay governed by AutoTotem's own Constraints tree. That is
    what keeps that module's "every click goes through its Constraints tree" invariant true on the
    new route.
  - The anchor is sticky for the whole engagement (first slot picked is reused for every refill), so
    a fight does not walk the totem across the hotbar. Slot policy: first EMPTY hotbar slot, else
    `FallbackSlot` (1..9), whose occupant is remembered and put back.
  - **The refill tops up the ANCHOR, not "the hotbar".** `ensureStaged` reports READY only when the
    anchor itself holds a totem; a totem in some OTHER hotbar slot does not satisfy it, it is only
    the fallback the caller may use meanwhile (`readyOr`). Reported 2026-09-14 - "refill even if
    there are totems in the hotbar but one popped for us" - and the first cut of this, which treated
    any hotbar totem as staged, is exactly what left the anchor empty after a pop. A totem STACK of
    several keeps the anchor satisfied until the last one goes. Do not "simplify" this back into a
    single `stagedSlot != null` check.
  - `displaced` is matched by IDENTITY first, then `ItemStack.matches` - same reasoning as
    `ModuleAutoTotem.findSlotOf`: ItemStack has no `equals`, a client-side swap moves the instance,
    and a `ClientboundContainerSetContentPacket` resync breaks identity for good.
  - The restore is RE-DERIVED from where the displaced stack actually is, not replayed blind the way
    `AnchoredHotbarSwapController` does it. A SWAP is its own inverse only while both ends still
    hold what they held, and the totem end is the one that gets spent.
  - Staging is continuous while a feature wants it, so the displaced item can only go home on
    `StageState.UNAVAILABLE` (out of totems everywhere - the anchor is pointless and holding
    someone's item for a slot we cannot fill would be a bug). That state is sticky, so it cannot
    ping-pong.
  - `isStaging` is the anti-ghost window for the staging click, bounded at 500 ms. The staging SWAP
    targets a hotbar slot, so unless that is the held one it does NOT arm `HandSwapGate`
    (`handSwapReason` knows only the offhand button and the held slot) while still carrying a state
    id - so it needs a window of its own.
  - **The auras are stood down BEFORE the click, not merely for it** (`PreArmTicks`, default 2;
    2026-09-14, the user's words: stop everything before the hotbar swap too, like in the
    antighost). Arming at schedule time leaves the tick our own click is DECIDED on unprotected: an
    aura that already spent an inventory slot earlier in that same tick has bumped the menu's state
    id, and our click then goes out carrying the older one - resync, ghost. So a refill first
    declares an INTENT, which is phase 1 of `isStaging`, and only clicks `PreArmTicks` later, into a
    window nothing else has written to. Do not collapse this back into arm-and-schedule-in-one-go;
    0 is the setting for that.
    - `canPerformAction` is tested BEFORE the intent is declared, not just before the click. A
      requirement that keeps failing (`Requires = NoMovement` while running) would otherwise have us
      declare an intent every few ticks forever and duty-cycle two auras against a swap that never
      happens. This is the same reasoning as `ModuleAutoTotem.armSwapWindow`, one step earlier.
    - Bounded by `MAX_INTENT_TICKS` (20). Nothing should come near it - the pre-arm is a couple of
      ticks - so reaching it means the click is not coming and the auras must be released rather
      than kept waiting.
    - `HandSwapGate.holdsClicks` is checked at the end of the handshake rather than the start, so a
      tick the click could not have left on does not burn the pre-arm.
    - COST: the refill after a pop is `PreArmTicks` ticks later than it used to be. Acceptable only
      because both features PRE-stage outside the danger window - if that ever changes, this delay
      lands on the critical path and has to be reconsidered.
  - `ModuleMainHandTotem.isBusy` is the stand-down flag, read by `ModuleKillAura` (on the
    `canAttack` line), `ModuleCrystalAura` and `ModuleAutoAnchor` (both `standDownReason`). Its tick
    handler runs at priority 100 so those three see a FRESH flag - `EventHookRegistry` orders
    descending, and at the default 0 the tick we start holding on is a tick they would still swing
    on.
  - Release is HP-above-`ReleaseHealth` or a totem pop, per the user's choice; there is no max-hold
    ceiling by request. `ReleaseHealth` is coerced above the trigger at read time, because equal
    thresholds flip the hold - and a `SetCarriedItem` - every tick.
  - `SwitchMode.NORMAL` uses `ModuleCrystalAura`'s `normalSwitch` idiom including
    `dropRestoreIfPlayerMoved`: never fight the player, and let vanilla announce the slot through
    `ensureHasSentCarriedItem` rather than sending one here.
  - OVERLAP: MainHandTotem and AutoTotem's `Route = MainHand` share the anchor by design but want
    the slot for different hands - enable one.
- `combat/totem/SwapQuiet.kt` — **the AutoTotem + InventoryMove coupling** (2026-09-14, the user's
  ask: make the pair stop ghosting on TotemGuard 3.0 and on the latest Grim). Mounted in
  `GlobalManager`. Audited against sources READ THIS SESSION: Grim `HEAD 8eb5f28` (2026-09-09) and
  TotemGuard branch `3.0`. Invariants, do NOT regress:
  - **The ghost had a single dominant cause and it was not our code being clever enough.** Grim
    `checks/impl/multiactions/MultiActionsC.java:37-51` CANCELS any `CLICK_WINDOW` sent while
    `player.isSprinting` (ungated by version), or `knownInput.moving()` (1.21.2+), or
    `player.isSneaking` (<1.15 or **1.21.9+**, i.e. us). It is NOT experimental and the shipped
    `punishments/en.yml` Misc group contains `"MultiActions"`, so it cancels out of the box. You
    sprint in a fight, so the offhand swap was being cancelled in most fights.
  - **A cancel is unrecoverable, which is why we refuse rather than risk it.**
    `CompensatedInventory` applies the click BEFORE the cancel (dispatch order 121 vs 142, guard at
    `utils/latency/CompensatedInventory.java:261`), so client and Grim both hold the totem and the
    real server does not. The only repair is
    `utils/lists/CorrectingPlayerInventoryStorage.java:66-68`, ~5 ticks later, and it early-returns
    when `isPacketInventoryActive` is false (`:108`) - true for menus Grim does not model.
  - `player.serverOpenedInventoryThisTick` (`MultiActionsC.java:40`) is NOT an escape hatch: it is
    armed only by a server-to-client `OPEN_WINDOW`
    (`events/packets/PreViaCheckManagerListener.java:55-57`), and container id 0 never produces
    one. Do not try to satisfy it.
  - TotemGuard wants the same state: `InventoryB.java:42-48` fails a `CLICK_WINDOW` while sprinting
    or moving, and `:49-65` fails the `CLOSE_WINDOW` too - hence `HoldTicks` (3), so the quiet
    outlasts the chain `InventoryManager` closes.
  - **`Requires = NoMovement` is NOT a substitute.** It tests
    `player.input.moveVector.isLikelyZero && !player.jumping`
    (`utils/inventory/InventoryValueGroups.kt:123`) - no sprint, no sneak - and defaults to an
    empty set. `isQuiet` instead reads `forward/backward/left/right/jump` plus `shift` off
    `player.input.keyPresses`, plus `!isSprinting && !isShiftKeyDown`, which covers all three
    MultiActionsC predicates.
  - **`isQuiet` must NEVER read the input record's SPRINT field.** It did until 2026-09-15
    (`keyPresses == Input.EMPTY`, and `Input` is a seven-field record whose last field is
    `keySprint.isDown()`) and that was the freeze the tester reported the night of 2026-09-14: the
    stop travels through `MovementInputEvent`, which carries direction, jump and sneak ONLY - the
    sprint field is decided by a separate `SprintEvent` `SwapQuiet` does not handle - so for anyone
    holding the sprint key, and permanently for anyone with vanilla's Toggle Sprint on
    (`ToggleKeyMapping.isDown` stays true until pressed again), the quiet could never arrive. The
    callers re-request every tick (`InventoryManager`'s scheduler is a tick handler), so the player
    was pinned in place indefinitely for a swap that could never fire. Neither anticheat reads that
    bit: Grim's `knownInput.moving()` is `forward || backward || left || right || jump` and the
    sprint STATE it checks is the entity flag, which vanilla drops on its own once the impulse is
    zero (`LocalPlayer.shouldStopRunSprinting`).
  - **Sprint is never released by a packet of ours.** Zeroing the input lets vanilla drop it; a
    hand-sent `STOP_SPRINTING` risks `badpackets/BadPacketsF.java:42-51`, which CANCELS a redundant
    stop and would leave Grim thinking we still sprint - the exact state that cancels our clicks.
  - **`requestStop()` must be called on the SAME branch that bails out for lack of quiet.** A
    feature that refuses to click has also opened no swap window, so the derived request would
    never fire, the stop would never happen and the totem would never swap. That is a deadlock, not
    a delay.
  - **`MAX_STOP_TICKS` (20) bounds one CONTINUOUS run and is load-bearing.** Callers re-request
    every cycle, so nothing else can ever expire the stop. If something pins sprint on (a
    KeepSprint-style module, a mixin re-adding input after `FINAL_DECISION`) the quiet never
    arrives, and a player frozen mid-fight is worse than a totem that did not swap. The budget
    hands the input back and `reset()` ends the run so the next request starts fresh - a retry duty
    cycle, never a freeze and never a permanent refusal.
  - **`RETRY_COOLDOWN_TICKS` (20) is what makes that duty cycle real** (added 2026-09-15 with the
    `isQuiet` fix above). On its own the budget buys ONE tick: `reset()` runs in the same tick
    handler the callers request from, so the very next tick starts another run. The cooldown refuses
    a fresh run for 20 ticks after an abandoned one, which is the difference between ~95% and ~50%
    of the ticks being the client's when something genuinely does pin sprint on.
  - **`ModuleInventoryMove`'s `Behaviour.STOP_ON_ACTION` is the OTHER half of the same bug, and it
    is the one that ate the swap** (2026-09-16, the user's own log: Offhand set to always hold a
    totem, `InventoryMove = StopOnAction`, `Sprint` on - "he does not take it but stops me").
    `ModuleInventoryMove.kt` cancelled every `isC2SContainerPacket()` while
    `player.input.keyPresses != Input.EMPTY`, queued it in `delayedContainerPackets` and flushed it
    from its own `MovementInputEvent` handler - which ALSO zeroes the input for that tick. With the
    sprint bit in that comparison the module was "moving" while the player stood still, so EVERY
    container packet was held a frame and cost a tick of movement, and the totem click `SwapQuiet`
    had just bought a quiet window for was flushed a frame LATER, back outside it, where
    `MultiActionsC` cancels it - the ghost. The test is now movement keys + jump + sneak +
    `player.isSprinting` (the entity flag), which is what `movementKeys` and
    `InventoryMoveSystemBypass.isMovingByInput` already meant by moving.
  - **`SwapJitter.allows()` is waited out BEFORE `isQuiet` is asked for, never after.** With the
    quiet first, the caller stopped the player, went quiet, then sat out the random hold with
    nothing requesting anything: the stop expired mid-hold, sprint came back, and the next tick
    started the whole thing again - one stop per hold, none of which bought a swap. Order is
    `allows()` -> `isQuiet`/`requestStop()` -> click in `ModuleOffhand` and both routes of
    `ModuleAutoTotem`.
  - Also fixed under this audit: `ModuleAutoTotem`'s MainHand burst and
    `ModuleMainHandTotem.holdHand` now refuse to fire while `InventoryManager.isInventoryOpenServerSide`.
    TotemGuard `InventoryA.java:111-116` flags `HELD_ITEM_CHANGE` with a container up as
    "change slot" and `:140-147` flags `SWAP_ITEM_WITH_OFFHAND` as "swap offhand"; vanilla can do
    neither, so there is no tolerance to tune.
  - Known-unfixed, accepted: `misc/Post.java:98-106` collects `HELD_ITEM_CHANGE` (1.8+) sent after a
    movement packet - flag only, never cancels, and gated on `isTickingReliablyFor(3)`.
    `multiactions/MultiActionsD.java:34-37` flags our `CLOSE_WINDOW` for movement and DELIBERATELY
    does not cancel. `packetorder/PacketOrderO` flags anything after the tick's movement packet but
    is experimental (off by default). `BadPacketsA.java:24-33` (live) cancels a `HELD_ITEM_CHANGE`
    repeating the previous slot id - our two bracketing sends always differ, keep it that way.
  - **AutoTotemB is the detection to respect, not AutoTotemA.** `AutoTotemA` cannot see us: it needs
    `pickupTimestamp`, i.e. a CURSOR transition to a totem, and a mode-SWAP click never touches the
    cursor. (Not because of `SlotAction` - `InboundInventoryProcessor.java:220` marks every slot in
    the packet's map as `CLICK` regardless of click type.) `AutoTotemB` measures
    `replenishedAt - lastTotemUse` over ANY hand slot (`PacketInventory.java:183-185`: offhand OR
    main hand, so MainHandTotem counts too), takes the stdDev of the last 5, and punishes 3.0 under
    5 ms / 2.0 under 15 ms against a threshold of 4.0 with **`decayPerSecond() = 0`** - violations
    never decay, only a stdDev above 60 ms earns a reward. Our 300 ms pacing is a CONSTANT; the
    swap interval still needs real jitter. NOT YET DONE.
  - The 10 s inventory-camp guard in `HeuristicCheck.guarded()` does suppress AutoTotemB entirely,
    but it is a trap, not a bypass: `InventoryA` flags `aim` on ANY rotation change while the
    inventory is open, plus move/sprint/sneak/attack/use/dig. You cannot play in that state.
- `combat/totem/IncomingDamage.kt` — reusable incoming-damage prediction for the totem features
  (2026-09-14, the user asked for "просчет всего урона от взрывов и тп"). The MODEL is not new and must not be
  rewritten: `utils/entity/EntityExtensions.kt` already mirrors vanilla (`getEffectiveDamage`,
  `getExplosionDamageFromEntity`, `getDamageFromExplosion`) and `player/offhand/Totem.kt`'s `Health`
  group already wires it. This class only LIFTS that wiring out, because there it is nested
  `object`s parented to `ModuleOffhand` and cannot be mounted elsewhere; it is a CLASS taking its
  owner as a ctor arg, the `tree(WallHitBypass(this))` / `tree(PointTracker(this))` shape.
  Invariants, do NOT regress:
  - **The verdict is cached per tick, and that cache is load-bearing.** `ModuleAutoTotem.condition()`
    runs once per `InventoryManager` CYCLE, not once per tick, so an uncached `damageFromBlocks`
    walks a ~4000-block sphere several times a tick. The key is (tickCount, health, threshold) so a
    mid-tick damage packet still recomputes and the cache can never answer differently from a fresh
    call. Removing it is an FPS regression, not a cleanup.
  - **Defaults encode a permission, not a taste.** `enabledByDefault` is FALSE for AutoTotem, whose
    javelin semantics are 1:1 by invariant, and TRUE for MainHandTotem, which has no legacy. Do not
    flip AutoTotem's default without asking; with the group off, `condition()` is byte-for-byte the
    old behaviour.
  - Callers check `enabled` themselves and fall back to their own plain health test. Do not fold the
    predictor into the comparison - the fallback is what makes "off" exact.
  - Both callers pass the threshold they would have compared against, ALREADY adjusted (elytra, and
    MainHandTotem's release hysteresis). Feeding the higher release number in while holding is what
    keeps hysteresis working on the predicted path; passing the trigger number there makes the hold
    flap.
  - `ExplosionBlocks` is OFF by default for the sphere-scan cost, matching the original.
  - Three deliberate fixes over the original wiring, keep them: the sphere is `by lazy` (the
    original builds it only in an `onChanged` hook, which does NOT fire when the setting loads as
    true from a saved config, silently disabling the scan); a null block state is skipped rather
    than force-unwrapped (chunk border = crash otherwise); sub-toggles are flat booleans because a
    class cannot host nested `object` declarations.
  - `Safety` (burrowed / in hole) substitutes `SafeHealth` for the caller's threshold and can only
    ever make the feature LESS eager. Fall damage ADDS to an explosion rather than competing with
    it - landing into a crystal is both at once.
  - **`Trigger = OnlyLethal`** (2026-09-14, the user's words: take one only when we predict DEATH
    from an anchor, a crystal or the nearest opponent's sword). It answers a NARROWER question over
    a NARROWER source set, and in that mode `SubtractCalculatedDamage`, `ExplosionEntities`,
    `ExplosionBlocks`, `FallDamage` and `Safety` are all ignored on purpose - each either widens the
    sources or moves the comparison off death. Invariants:
    - The comparison is against the WHOLE health bar. There is no threshold and therefore NO
      hysteresis: the threshold argument goes unused, so a hold releases the moment death stops
      being predicted. That is the mode, not a bug - but it does mean a flickering crystal flickers
      the hold.
    - **The blast is a MAX, the sword is a SUM.** `max(crystal, anchor) + sword`. Explicitly the
      user's choice: a plain max over all three misses "crystal plus a hit in the same tick", which
      is the most common death in this game mode; summing crystal AND anchor too would spend totems
      on a coincidence that barely happens.
    - Sources are narrowed to `EndCrystal` (no TNT, no minecarts, no creepers) and CHARGED
      `RespawnAnchorBlock` (no beds). The anchor scan early-returns in a dimension where
      `respawnAnchorWorks`, which also skips the sphere walk there.
    - The sword is base item damage plus Sharpness via `utils/item/attackDamage` (the client cannot
      read enchantment damage off an item any more, MC-196250, and that property is the workaround),
      with NO crit multiplier and NO Strength - the user asked for the plain hit, so it
      under-predicts a jump-crit rather than pre-spending a totem on one that may never land.
    - Only the nearest opponent counts, only while holding a sword, and only inside `MeleeRange`
      (boxed distance, default 4 against a vanilla reach of 3 - a block of margin for the step they
      take while our packet flies). Enemy selection is `shouldBeAttacked()`, i.e. the client's own
      target config.
    - COST: unlike the THRESHOLD path, where the block scan is off by default, OnlyLethal always
      walks the radius-10 sphere for anchors. The per-tick cache is what makes that affordable; it
      is not optional here.
    - FOOTGUN, documented deliberately: in this mode there is no plain low-health safety net at all.
      At 2 HP with no crystal, anchor or sworded opponent nearby the module does NOTHING, because
      that is literally what "only when we predict death from those three" means. Anything not
      modelled (bows, other players' explosives, poison) will not trigger it.
- `combat/aimbot/autobow/` — **AutoBow** (BowAimbot + AutoShoot + FastCharge). Reworked 2026-09-21,
  then again 2026-09-23 after the user reported misses on a STANDING target with prediction off. The
  resolver is now our own (`AutoBowResolver` + `utils/aiming/projectiles/ArrowBallistics.kt`); the shared
  `SituationalProjectileAngleCalculator` (Cydhranian/Polynomial) is NOT used by AutoBow any more and was
  left untouched for Trajectories/ProjectileAimbot. Why the old one missed (all read from 26.1.2):
  it aimed at a surface point of the hitbox inflated by 0.5 (`PointFinding.kt:179-195`: the face centre
  nearest the box centre - laterally off-axis up to ~0.57 at a diagonal), below 5 blocks it used snowball
  physics aimed at the feet (`PolynomialProjectileAngleCalculator` reads `TrajectoryInfo.GENERIC`), it
  solved with `round(flightTicks)` (+-0.2-0.4 m at 30-60 blocks), and ignored the `eyeY - 0.1` spawn and
  the shooter's inherited motion. Invariants, do NOT regress:
  - Physics (`ArrowBallistics`): move by v, THEN v *= 0.99, THEN v.y -= 0.05 (`AbstractArrow.tick`). The
    closed form is exact at whole ticks; the solver scans flight time for the FIRST root of
    `|requiredVelocity(t) - inherited| = speed` (the direct shot) and bisects. `ArrowBallisticsTest`
    checks it against a vanilla-order stepper (< 1 cm, moving targets and inherited motion included).
  - Entity margin is `clamp((tickCount - 2) / 20, 0, 0.3)` (`ProjectileUtil.computeMargin`) and
    `tickCount` is incremented BEFORE `tick` (`ServerLevel.tickNonPassenger`): ZERO margin for the first
    two ticks. So the aim point is the hitbox CENTRE (`AimHeight`, default 0.5), never a surface point.
  - Spawn: `eyeY - 0.1` (`AbstractArrow.java:133`), crossbow firework `eyeY - 0.15`
    (`CrossbowItem.java:147`). Bow/trident inherit `getKnownMovement()` = the position delta of the last
    movement packet, y dropped on ground (`Projectile.java:157-163`); crossbow inherits nothing.
  - Timing: `Minecraft.tick` runs `handleKeybinds` (release, `Minecraft.java:1938`/`:2111`) BEFORE
    `tickEntities` (movement packet, `:1949`). The aimbot files from `RotationUpdateEvent` (before
    `RotationManager.update()`), so the rotation is on THIS tick's packet and steers a bow released NEXT
    tick. Hence: bow/trident solve with release delay >= 1, the charge `ticksUsingItem + delay`, and the
    shooter one tick ahead (`nextTickShooter`: vertical from a one-tick `SimulatedPlayer`, horizontal =
    last movement, because `SimulatedPlayer` lacks the item-use slowdown of `LocalPlayer.java:681-682`).
    A charged crossbow fires from a use packet carrying THIS tick's `currentRotation`
    (`handleUseItem` snaps to it) -> delay 0, current shooter.
  - With AutoShoot ON the rotation is filed only when `releaseDelay <= rotations.calculateTicks + 1`
    (one tick with an instant smoother): the aim appears on the last packet before the release. With
    AutoShoot OFF it is a continuous assist. Do not "fix" the asymmetry.
  - AutoShoot `AimCheck = Hit` (default) releases only if the rotation that will CARRY the shot
    (`serverRotation` for bow/trident, `currentRotation` for crossbow) hits the predicted target when
    flown by `AutoBowResolver.firstHit`. `Angle` is the old `AimThreshold` behaviour (threshold shown only
    in that mode). `SafeCharge` (default on): a bow shot must also hit with charge +-1, because the server
    counts the draw in its own ticks (`ServerGamePacketListenerImpl.tickPlayer` -> `doTick`); at 15 ticks
    one tick is ~+-1 m at 30 blocks. From 21 ticks the power is saturated and it never holds. The aimbot
    applies the same test when choosing the release tick, so it does not rotate early and then wait.
  - Prediction (`Predict` is a toggle group per the Settings style rule; there is no `Off` mode). Reworked
    2026-09-25 (the user: "make the resolver miss as rarely as possible"; "AutoShoot fires before the
    rotation has arrived"). Invariants, do NOT regress:
    - **The target's position and velocity come from its PACKETS (`TargetMotionLog`), not from the
      entity.** Players are broadcast every 2nd server tick (`EntityType.java:1115`), only when moved
      (`ServerEntity.java:121`, `:140-142`); the client interpolates each packet over 3 ticks
      (`InterpolationHandler.java:11`, `:84-106`), so `position() - lastPos` is a smoothed, lagging velocity
      and `position()` trails the packet by up to 2 ticks. The log reads `InterpolationHandler.position()`
      at a 1010-priority tick handler (ahead of RotationManager's 1000), stamps each DISTINCT position with
      the netty arrival time of the entity's latest position packet (`MixinClientboundMoveEntityPacketAccessor`
      for the id), and derives velocity as `(last - first) / ((count - 1) * updateInterval)` over up to 3
      consecutive packets - the server's cadence, immune to arrival jitter. A run broken for longer than
      the interval means the entity STOPPED (the server reports any move within the interval) -> velocity
      zero, never the client's leftover interpolation. `Simulation` starts the `SimulatedPlayer` AT the
      server position with the server velocity (`fromOtherPlayer(player, input, position, velocity)`), so
      `InterpolatedSimulationExtrapolation` needs no offset any more.
    - **The lead is `releaseDelay + sinceReceived + shotDelay + Alignment (+ LeadTicks)`, and the
      resolver reads the target at `tick - 1 + lead` per arrow tick (`ticks - 0.5 + lead` in the continuous
      solver).** Derived in `AutoBowResolver`'s class KDoc from the server's order: a server tick FIRST
      drains the client packets (`MinecraftServer.processPacketsAndTick`, `:1033` - a dedicated
      `network/PacketProcessor` queue, not main-thread tasks) and THEN runs the levels (`:1035`); a spawned
      projectile ticks at once (`PersistentEntitySectionManager.java:92-93`, `ServerLevel.java:2007-2008`),
      so the arrow's first move is in the level tick of the same server tick as our packet; the target's
      tracker sends from INSIDE a level tick before the entities (`ServerLevel.java:392`); the server player
      moves only by its packets (`tickPlayer` snaps the simulated tick back, `ServerGamePacketListenerImpl.java:313,318`).
      So arrow tick `n` meets the target `n - 1 + (k - j)` ticks past the held position, `k - j` = time the
      target's packet spent in flight and on our side + time our shot takes to be processed + the fraction of
      its tick at which the tracker sent. `sinceReceived` is the first (netty stamp), `ShotDelayMeter` the
      second, `Alignment` (default 0.2) the third. The downlink latency is in both and cancels: no
      half-round-trip assumption anywhere. The old model (`tick - 0.5`, lead = ping) was short by about a
      tick on average (0.3 blocks on a sprinting target - the box half-width). First cut of this rework
      (2026-09-25, same day) counted `n + (k - j)` on the belief that packets ran BETWEEN level ticks; it was
      wrong by a tick and is why the derivation above cites the drain order line by line.
    - **`ShotDelayMeter` measures the delay from OUR shots**: outgoing `UseItem` on a charged crossbow /
      `RELEASE_USE_ITEM` on a bow (>= 3 ticks) or trident (>= 10) -> the `ClientboundAddEntityPacket` whose
      `data` is our id (`Projectile.java:350-352`), sent while the server processes our packet
      (`ServerEntity.java:255-258`). Mean of the last 8 = `delayTicks`, std dev (floor 0.5) = the timing
      jitter the hit chance is flown under. Not gated on `PacketEvent.isCancelled`: FakeLag cancels to hold
      the packet and that hold IS delay. `PingCompensation` now switches this term; before the first shot it
      falls back to `player.ping` (broadcast only every 600 ticks, `PlayerList.java:470-471`) + 0.5.
    - **AutoShoot releases on HIT CHANCE, not on the nominal path grazing the box** (`HitChance` group,
      default on: `Relative` 0.9 of the chance the aimbot's own plan (`plannedRotation`) would achieve this
      tick, `Minimum` 0.25). `ArrowBallistics.hitChance` flies 32 FIXED draws of vanilla's spread
      (`Projectile.java:136-143`: triangle(0, 0.0172275) per axis on the UNIT direction, then scaled - it
      turns the shot AND changes its speed; fixed seed so the estimate cannot flicker) with a stratified
      timing shift of +-jitter ticks each, against the target box at `tick + lead + shift` and every other
      pickable living entity near the line (an unwanted entity first = a miss, `AbstractArrow.canHitEntity`).
      Blocks are read off the NOMINAL path once and bound the draws. This is what fixes "fires before the
      rotation has arrived": a smoothed aim sweeps onto the box from one side, and the first tick the
      nominal path touches it is the EDGE, where the spread loses half the shots. Off, the old nominal
      `shotHits` / hypothetical-hit checks apply; `SafeCharge` stays a separate hard test.
    - `Simulation` falls back to linear for a player that `isUsingItem` (same slowdown gap). Simulations are
      cached per tick and shared by the aimbot solve and the AutoShoot check.
  - Not modelled: water drag, firework lifetime and blast radius (a crossbow firework explodes after
    `10 * flight + 0..11` ticks, `FireworkRocketEntity.java:68`, and damages within 5 blocks, `:234-251`),
    the target's own velocity uncertainty over the flight (the chance covers spread and timing only),
    allied players the server would skip (`canHarmPlayer`) - counted as blockers, which only lowers the chance.
  - `ThroughWalls` defaults OFF (2026-09-23, user: "no rotation from behind a wall"): off, a target is
    only selected when the block-aware simulation of the solved shot reaches it, so nothing is filed for
    a covered target. A saved config keeps its old value.
  - `AutoShoot.HotbarCrossbow` (2026-09-23): fires charged crossbows from the hotbar while NOTHING is in
    use (`handleSetCarriedItem` stops a main-hand use on a slot change, `ServerGamePacketListenerImpl.java:1448-1449`),
    one per shot tick (`Delay`, floor 1), held hand first, then offhand, then hotbar. Never blind: it
    needs the aimbot target + `AimCheck` or `RequiresHypotheticalHit`. The aimbot plans with
    `nextCrossbowStack()` - the exact stack AutoShoot fires next (firework vs arrow physics).
    Packet shape per shot tick: `SetCarriedItem` (SilentHotbar, `ticksUntilReset = 0`, announced
    immediately via `ensureHasSentCarriedItem` so it LEADS the window) -> `UseItem` -> flying; the slot
    returns next tick from `gameMode.tick()`, in its own window (`PacketOrderE` is experimental but kept).
    The use packet carries `currentRotation` (`MixinServerboundUseItemPacket`) and so does the flying
    (`MixinLocalPlayer.hookSilentRotationYaw/Pitch`): BadPacketsJ (LIVE, `use_item_rotation_mismatch`)
    compares exactly those, so the shot must be fired AFTER `RotationManager.update()` of its tick and
    nothing may step the rotation between the use and the flying. The player's own USE is held on every
    shot tick (it would re-fire the still-"charged" client stack, see below) and their ATTACK on a tick
    the slot was switched (it would land with the crossbow).
    Stands down on a screen, `InventoryManager.isInventoryOpenServerSide`, a foreign SilentHotbar state,
    `HandSwapGate.holdsUses`, a pull in flight, AutoTotem / MainHandTotem busy, and a live KillAura target.
    - `Switch` (Silent default / Normal / None). Both switching modes send the SAME packets in the same
      tick: the switch shares the tick of its shot, so holding the slot between shots is cosmetic. NORMAL
      writes `Inventory.selectedSlot` (the CrystalAura `normalSwitch` idiom, `dropRestoreIfPlayerMoved`
      included - never fight the player's own scroll). `SwitchBack` (NORMAL only) returns the pre-volley
      slot from the tick head (so `gameMode.tick()` announces it) once the volley is over: nothing left
      to fire, the hotbar path standing down, or 10 ticks without a shot. The way back WAITS while
      `isUsingItem` (a slot change stops a main-hand use, e.g. the player reloading the spent crossbow),
      a screen or a server-side inventory is up. None: only a crossbow already in a hand is fired.
    - `FromInventory` (off by default, hidden under `Switch = None`): a `CLICK_WINDOW` SWAP of a charged
      crossbow into the hotbar, through `ScheduleInventoryActionEvent` + its own Constraints tree and its
      own `SwapQuiet` (MultiActionsC CANCELS a click while sprinting/moving/sneaking - see
      `combat/totem/SwapQuiet`). Only when no hand/hotbar crossbow is left, an enemy is tracked, no shot
      of ours is unconfirmed (a click with the older state id is answered with a full resync), no totem
      swap is in flight. `requestStop()` on the same branch that bails for lack of quiet. Shots are held
      from the schedule until the crossbow appears in the hotbar AND for that landing tick (no slot
      change + use behind a click in one window). 20-tick timeout, 300 ms between pulls.
  - **Never read `LocalPlayer.usingItemHand` without `isUsingItem`.** It is a public FIELD set in
    `startUsingItem` (`LocalPlayer.java:552`) and never cleared by `stopUsingItem` (`:570-573`), so after
    the first use of anything it names a hand for good. `AutoShoot.usingItemHand()` read it unguarded and
    sent every tick down the in-use path: the hotbar path never ran and the aimbot planned for whatever the
    stale hand held (2026-09-24, found from the `Debug` log - not one `hotbar:` line). Pulls from the
    inventory only LOOKED fine because they land in the held slot and fire from the hand.
  - `AutoBow.Debug` (off by default) writes every change of refusal reason to `latest.log` on three
    channels: `hotbar:`, `hand:`, `pull:`. Written on change, not per tick, so `MinInterval` is 0. The
    first thing to read on a "it does not shoot" report.
  - Fired crossbows are tracked by stack IDENTITY until the server's slot update replaces the instance
    (or empties it; 40-tick fallback): `CrossbowItem.performShooting` empties the charge only for a
    `ServerLevel` (`CrossbowItem.java:178-179`), and a second use of the still-"charged" client stack
    would reach the server as a use of an EMPTY crossbow, which starts loading it. Applies to
    `UsePrechargedCrossbow` as well. The entry is dropped the tick the instance leaves the inventory,
    not after the timeout - `FromInventory` waits on an empty list, and a timeout-only prune would make
    every pull wait 2 s after a volley.
- `combat/ModuleAutoWeb.kt` — catlean AutoWeb on the `CatleanPlaceModule` placer, webbing the
  target's own cells. 2026-09-06, aim arbitration with KillAura: the placer's rotation priority
  is `placeRotationPriority` (open, default `IMPORTANT_FOR_USAGE_2` = a TIE with KillAura, which is
  why those two yield to each other by flag); AutoWeb overrides it to
  `IMPORTANT_FOR_USAGE_3` so it simply outranks the aura for exactly the ticks it has a real
  placement (`placeOne` only requests then, `ticksUntilReset = 1`). Do NOT convert this to a
  KillAura-yields-flag: the aura must keep requesting at its own priority, so when our request
  lapses its plan is already top of the queue and the aim never passes through a reset tick.
- `combat/AutoCartFire.kt` — the FIRE/BIND mode chain of **AutoCart** (rails → TNT minecart →
  flint & steel on the block between player and rail → charged crossbow through it → detonate).
  Invariants, do NOT regress:
  - One use-item per tick, swaps through `player.inventory.selectedSlot` + `ensureHasSentCarriedItem`,
    swap leads the window, one use per tick (Grim `PacketOrderE`).
  - Every click re-checks `RotationManager.serverRotation` (settle gate) and `inReach`
    (`blockInteractionRange - 0.05`, grim FarPlace) before it acts; grim settle gate = wire hit on the target.
  - grim audit status: pending — mirror of `AutoCartXbow`'s already-audited shapes; audits needed
    against Grim `AirLiquidPlace`/`MultiActionsF`/`BadPacketsJ` when the clone is available
    (clone per AGENTS.md does not exist on this machine).
- `combat/ModuleCartAura.kt` — **CartAura**: the AutoCartFire chain fired around a popped
  target. Trigger: `ClientboundEntityEventPacket` (`PROTECTED_FROM_DEATH`) parked on netty,
  resolved on tick (`ModuleTotemPopESP.kt:93-118` idiom). One chain per pop, spaced by Cooldown.
  grim audit: chain packets are AutoCartFire's (same shape); pop detection is a server packet.
  NOT Grim-audited (reuses the chain shape).
- `misc/ModuleAutoPearlCatch.kt` — **AutoPearlCatch**: throws a wind charge at your own pearl
  mid-air. Detects own pearl via `ClientboundAddEntityPacket` (`EntityType.ENDER_PEARL`) + pearl
  in offhand/hotbar (`ModuleAutoPearl.kt:135-140`). Prediction = generic throwable physics
  (`TrajectoryInfo.GENERIC`: pos += vel; vel = vel * 0.99 − gravity(0.03)); first free tick within
  Range with a clear line from the eye wins. grim audit status: pending — wind-charge throw shape
  mirrors `ModuleAutoWindCharge`/`ModuleDiveBomb`; `ProjectileOrder`-style check in Grim once
  clone available.
- `ModuleAutoCart` — **AutoCart** now also runs the Fire chain from `CartMode.FIRE`
  (`AutoCartFire.tick()` on the module tick) and the Bind chain from `CartMode.BIND` (module bind
  press → `AutoCartFire.onBindPress()`); `ModuleAutoCart.reset()` calls `AutoCartFire.reset()`.
  Note: the pre-existing `CartMode.XBOW` (lava cart bomb, `AutoCartXbow.kt`) is untouched.
  KillAura's `canAttackNow` reads `ModuleAutoWeb.placedThisTick` and skips the attack on a tick
  a web went down (block + entity interaction in one window = MultiActionsF/PacketOrderJ); the
  ordering is guaranteed because every `RotationUpdateEvent` handler runs inside RotationManager's
  FIRST_PRIORITY `GameTickEvent` handler, ahead of the aura's own tick handler.
- `combat/testelytratarget/` — **TestElytraTarget**, an elytra chase autopilot rewritten from
  scratch (2026-09-15, user's request "с 0, не ориентируясь на текущий"). It REPLACED the
  2026-09-13 module of the same name, which was deleted whole along with its tests; it shares no
  file and no control design with it, nor with the catlean `combat/elytratarget/` port, which stays
  canonical until this one is proven in game (then renames, TestAutoAnchor precedent). While
  `ModuleElytraTarget` or `ModuleSpearElytraTarget` is `running` this one stands down wholly and
  warns once.
  The module does NOT attack — KillAura does, by the user's decision; this owns only target choice,
  steering, fireworks and takeoff. PLAN on `RotationUpdateEvent` (tick head, inside RotationManager's
  FIRST_PRIORITY handler, before `update()`), EXECUTE on `InputHandleEvent`.
  Control is a GUIDANCE LAW plus a feasibility filter, NOT the candidate-rollout search the previous
  module used. `guide/Guidance` blends, continuously by range: proportional navigation far out
  (command the velocity to rotate at `N` times the line-of-sight rate — the constant-bearing rule),
  a lead turn onto the EDGE of the attack band at the merge, and an orbit inside it. Because the
  blend is smoothstepped there are no phases and no hysteresis — a continuous blend cannot chatter.
  `guide/EnergyController` owns the flight-path angle only (guidance owns azimuth): it flies to hold
  a specific-energy margin `v^2/2 + g*h` over the target, which makes OVERSHOOT self-correcting —
  blowing past is a kinetic surplus, so the same feedback commands the climb that banks it as height.
  `guide/Feasibility` only ever vetoes: it rolls the command forward through vanilla physics and
  searches alternatives solely on the ticks where terrain/void/stall rejects it.
  Three modes. LEGIT: `CHANGE_LOOK`, and it requests BELOW the aura (`IMPORTANT_FOR_USAGE_1` vs the
  aura's `_2`) so that whenever the aura has a rotation — meaning a target in reach — the aura owns
  the precise aim while our heading points at the target anyway. HVH: `STRICT`, `_3`, pass-through
  processor, no zoom climb (the user: in HvH we do not turn around), so the orbit keeps the vertical.
  FULL_HVH: flies on `ModuleElytraFly` Vanilla mode, taken into custody and restored on exit.
  Invariants, do NOT regress:
  - The wing is steered ONLY through `RotationManager`; `MixinLivingEntity:250-290` makes vanilla
    fall-flying read `currentRotation`. Never write `player.yRot`.
  - The rotation request repeats EVERY tick while there is a target: `CHANGE_LOOK` is forced to
    `ticksUntilReset = 1` (`RotationManager.kt:147-150`) and a lapsed request is a reset tick, which
    in the air is the wing snapping back and out again.
  - `command` is nulled on EVERY early PLAN return, so EXECUTE can never act on stale geometry.
  - Reach is the AURA's (`ModuleKillAura.range`) and is a BAND: vanilla checks
    `distance >= minReach && distance <= maxReach` from the eyes to the hit point, so the orbit's
    radial term is two-sided and must be able to push outwards. Never introduce a private range.
  - FULL_HVH vertical is written from `handler<GameTickEvent>(priority = READ_FINAL_STATE)`, after
    ElytraFly's handler at 0 — `tickHandler {}` CANNOT do this, it takes no priority
    (`SuspendHandlers.kt:57-66`). ElytraFly's mode is changed only via `modes.setByString`
    (`activeMode` has a private setter) and the previous tag is always restored.
  - FULL_HVH control lags one tick on BOTH channels (yaw reaches `player.yRot` only via the
    render-time write, `RotationManager.kt:238-253`; forged input reaches `player.input` at the end
    of the tick, `MixinKeyboardInput.java:76-81`), so it plans against frame t+1. Equal lag on both
    is a delay, not a distortion — do not try to defeat it.
  - One firework use per tick; `HandSwapGate`, `ModuleOffhand.isOperating()` and
    `OFFHAND.canBeSwapTarget` are checked BEFORE the cooldown is spent, and a rocket is never stacked
    on a burning one.
  - `Standoff` defaults to 0.3 because it was MEASURED, not guessed (see below). Do not "restore" it
    to a wide value without re-running the comparison.
  - 30 headless unit tests under `src/test/.../testelytratarget/`. The centrepiece is the simulated
    comparison against pure pursuit, asserted in BOTH directions: a >3x win in the overshoot regime
    (measured 32 vs 6 and 47 vs 9 ticks in reach) and mere parity in a straight tail chase, so the
    claim cannot be read as broader than it is. Physics invariants are properties, not fixtures —
    there is no headless vanilla entity to compare against. The only render is the Debug group.
- `combat/crystalaura/` — **CrystalAura**, THE crystal aura of this client and an ORIGINAL module
  (the doctrine it was built under has its own section below — read it before changing anything here)
  written from scratch (2026-09-06/07, user's request "с 0"). It shares no code and no design with
  the two modules it replaced (the catlean `combat/crystal/` AutoCrystal and the upstream
  `combat/crystalaura/`), both DELETED on 2026-09-07. Scope set by the user: maximum speed, the
  ONLY throttles are `Place.Delay` / `Break.Delay` (ms, default 0 = act every tick that passes the
  damage checks); the hard anticheat rule is "no packet-order violations".
  Layout: `calc/` = the vanilla-exact damage model (`ClipCore` replicates
  `BlockGetter.traverseBlocks`, `Exposure` replicates `ServerExplosion.getSeenPercent`,
  `VictimPipeline` replicates `Player.hurtServer`, `EnchantProtection` re-implements the
  server-only `EnchantmentHelper.getDamageProtection`), `world/WorldView` = per-tick snapshot,
  `scan/`+`base/` = branch-and-bound position search, `mine/` = AutoMine, `aim/` = a vanilla
  `LocalPlayer.pick` replica plus the module's own smoother, `exec/` = tick planner + executor,
  `state/` = the crystal ledger, `render/` = the "Detonation" overlay. The overlay's reusable half
  (soft geometry, tweens, glow, blast dome, text) moved to `combat/aura/render/` on 2026-09-08 so
  AutoAnchor could share it: those objects own full-screen render targets, and a per-module copy
  would allocate a second set for the same picture. `AuraBlastDome.release` takes an OWNER for that
  reason — without it one aura shutting down erases the other's live blast. 110 unit tests under
  `src/test/.../crystalaura/` check the raycast and the exposure grid against vanilla's OWN code as
  the oracle (`FakeBlockGetter` runs the real `BlockGetter.clip`).

  Invariants, do NOT regress:
  - PLAN on `RotationUpdateEvent` (tick head), EXECUTE on `InputHandleEvent` (vanilla's click
    position). PLAN owns the intent, EXECUTE owns the verdict.
  - Every action is re-verified immediately before the packet against `AuraRotation.outgoing` —
    the rotation THIS tick's movement packet will carry — so a rotation requested in PLAN serves
    the action of the SAME tick. This is what vanilla does (`MouseHandler.turnPlayer` runs after
    the tick loop) and what Grim evaluates (interactions are drained post-flying). Never widen a
    tolerance to make an action land: one post-flying miss arms `RotationPlace`'s `flagBuffer`,
    which then CANCELS placements for 10 interactions.
    **The SECOND candidate differs by action class and this is not cosmetic.** Attacks and digs
    go through `ActionExecutor.byAim`, which falls back to the full previous rotation
    (`RotationBreak`/`Reach` do add `(lastYaw, lastPitch)` for 1.9+). A PLACEMENT goes through
    `byPlaceAim`, which tries only `(yaw, pitch)` and `(lastYaw, pitch)`. CORRECTION (2026-09-25,
    read from the clone at `8eb5f28`): `RotationPlace` DOES add `(lastYaw, lastPitch)` for a 1.9+
    client (`RotationPlace.java:110-113`); the old wording here said it did not. `byPlaceAim` is a
    subset of what the check accepts, so it is merely stricter than needed, never wrong. Also note
    that Grim moves the flying's rotation into `yaw` for the drain only when the click arrived
    < 15 ms before it (`CheckManagerListener.java:110-116`) - past that, a same-tick click is judged
    against the OLD rotation alone. Consequence to keep in mind when planning: a tick that
    steers the aim somewhere else invalidates a placement verified against the wire, which is why
    `TickPlanner`'s base→placement fallback files no look point at all.
  - `lastActionMs` is stamped in `observeOutgoing` from the PACKET, never from an executor report:
    `attackEntity` reports an attack another module cancelled through `AttackEntityEvent`, and the
    AutoMine job's dig packets report nothing. `ModuleSpeedMine` reads that clock.
  - The foreign-click flags (`foreignUseSeen` / `DigSeen` / `EntitySeen`) are cleared on the packet
    that ENDS a Grim tick window — the movement packet, or `ClientTickEnd` on a tick that sent none
    — and never in PLAN. PLAN is not a boundary: `ModuleAnchorOptimizer` emits its whole cycle from
    `RotationUpdateEvent` and the catlean placer places there too, so a reset in PLAN erased real
    clicks of this window whenever those modules' handlers ran first. Same rule in `AutoAnchor`.
  - The totem windows (`ModuleAutoTotem.isSwapping`, `ModuleMainHandTotem.isBusy`) are re-read at
    EXECUTE, not only in PLAN: `InventoryManager`'s scheduler is a plain `tickHandler` (priority 0)
    and PLAN runs at `FIRST_PRIORITY`, so a swap of this tick arms AFTER PLAN decided there was
    none. Same rule in `AutoAnchor`.
  - At most one attack and one use per tick, attack first. Digging is a THIRD mutually exclusive
    class — `PacketOrderI` flags any place/use/attack/interact in a tick carrying any dig packet,
    ABORT included — so a dig owns its whole tick.
  - The slot switch is filed in PLAN so `gameMode.tick()` emits the single `SetCarriedItem` at its
    own position, BEFORE the clicks. Moving it later forfeits Grim's same-tick rotation.
  - The module stands down for a tick when the PLAYER's own use/dig/attack packet already went out.
  - PLAN skips while a screen is open OR while `ModuleAutoTotem.isSwapping` (2026-09-13, the ghost
    totem — see that module's entry above for the state-id mechanism). Both go through
    `standDownReason()` / `standDown()` and stand the module ALL the way down, hotbar switch
    included: a `SetCarriedItem` of ours between `InventoryManager`'s inventory open and its click
    is a shape vanilla, which cannot touch the hotbar while its own menu is up, never produces.
    Holding only the click would not be enough. State resets on a `ClientLevel` identity check at
    the head of PLAN (`WorldChangeEvent` is `running`-gated and never fires on disconnect/reconnect).
  - AutoMine drives vanilla's own progressive breaking (`doBreak` → `continueDestroyBlock`); never
    `doBreak(immediate = true)` (two dig packets in one call). Aborts go through
    `stopDestroyBlock()` (face `DOWN`, sequence 0 — `PositionBreakB`). Never retarget while the
    client is still destroying: vanilla's own retarget path sends the ABORT with the NEW face.
    `MiningClaim` arbitrates with every other digger that TAKES one - which the player's own mouse does not, so
    `MineJob` additionally refuses to start, and refuses its `START`, while `interaction.isDestroying` is true
    without our own `START` behind it (`foreignDigInProgress`, 2026-09-20). Without that, `doBreak` on another
    cell takes vanilla's retarget path and emits their `ABORT` and our `START` from one call.
  - **`Mine.Engine = SpeedMine` (default; `mine/PacketMineJob.kt`, 2026-10-02, the user's ask: "while the packet
    SpeedMine works you can place crystals, blocks and so on").** The vanilla engine above owns EVERY tick of a dig
    (the swing-only middle ticks too), so the aura is frozen for the whole dig. The packet engine hands the cell to
    `ModuleSpeedMine` and takes only the ticks that carry a dig packet of OURS: aim + START, aim + STOP. Both engines
    implement `mine/AutoMineEngine` - `plan` returns a `MinePlan` exactly on the ticks the dig takes, null otherwise,
    and the hub plans no attack/use on those ticks. Invariants, do NOT regress:
    - **The aura sends the START and the first STOP itself** (`ModuleSpeedMine.startForAura` / `finishForAura`, from
      EXECUTE, with the face of the aura's own ray on the OUTGOING rotation - `PositionBreakA`, `RotationBreak`). Left
      to SpeedMine, the STOP starves: its `whenReached` only runs for the TOP rotation request
      (`RotationManager.kt:225-230`), the aura sits above it at 35 vs 30 whenever a crystal is in range, and its tool
      switch is announced as the aura's silent slot instead. On the FINISH tick the hub holds the best HOTBAR tool as
      the aura's own silent slot, so `gameMode.tick()` announces it before the STOP and SpeedMine's own
      `switchTo`/`switchBack` send nothing.
    - **Whether the STOP may go is asked BEFORE the tick is taken** (`auraFinishBlocker`: the STOP's own gates -
      `serverDigsAt`, SpeedMine's own pace (`strengthOf` with `Factor`) times the ticks since the START, the FastBreak
      mirror, the server's 0.7 test - all run for the stack the aura is about to hold), so a STOP that would be held
      never costs the aura a tick. NOT SpeedMine's `progress`: it accumulates for SpeedMine's own tool pick, which is
      the aura's crystal whenever `SwitchTool` is off or the durability filter refuses the pick (audit finding C).
    - **SpeedMine sends NO STOP of its own while the aura holds the dig** (`MineAction.step`: `if (auraOwned) return
      false`; `sendScheduledStop` skips it too). Its rotation request would otherwise sit unconsumed under the aura's
      and fire the first tick the aura asks for none - a second STOP into the air Grim predicts until the ack
      (`AirLiquidBreak`, LIVE). Keyed on the FLAG, not on `mineBreaks` (a STOP of ours that did not take leaves
      `mineBreaks` at 1 with the aura still on the cell - audit finding B). The flag is set EVERY tick the job reads
      the dig (`markForAura`), so a dig the player started first is adopted owned (finding A). So every way out of a
      packet job hands the dig back: a broken cell `handBackFromAura` (its rebreak runs again), a standing one
      `releaseForAura` on `giveUp` / `reset`; `stop` (a stand-down) is a deliberate no-op pause. A dig another START
      moved the server off is dropped by SpeedMine's `step` itself (the STOP-side `abandon` can never run for an
      owned dig - finding E), so the job sees it gone and restarts.
    - **No blind `RebreakStops` after an aura-owned STOP** (`auraOwned`): each is a dig packet in its own tick, and the
      aura stands down for such a tick - exactly when the pocket opened. `Rebreak = NCPAir` sends dig packets the aura
      cannot account for, so the engine refuses it (`ModuleSpeedMine.auraCompatible`). DoubleMine is ALLOWED since
      2026-10-03 (the user's ask): its START + ABORT + STOP leaves inside the job's own START tick; an aura cell the
      server ends up finishing by itself gets no STOP (`auraFinishBlocker`: `finishedByServer`) and is finished by
      SpeedMine's tool hold; the aura does not START over TWO digs of the player's (`digsElsewhereForOthers`).
    - `MineAction.breakBlock` returns on `ended` as well as `abandoned`, and `releaseForAura` abandons in every case:
      a `whenReached` still pending for a cancelled or released dig must send nothing.
    - `Mine.Tool = Normal` resets the aura's own silent slot first (both engines; finding G): a leftover silent crystal
      slot from `Place.Switch = Silent` wins in `ensureHasSentCarriedItem` and the dig was timed against a crystal.
    - **Our own obsidian is never rebroken.** With `Rebreak = Grim`/`InstantAsync` SpeedMine breaks whatever reappears
      in a cell it dug, and the obsidian block update is precisely its trigger. The BASE cell of a pocket is released
      once it is gone, and `ActionExecutor.placeBase` releases any SpeedMine dig on the cell before every base click.
      The crystal cell is handed back and keeps its rebreak (it keeps the pocket open) until SpeedMine's next START
      evicts that dig - a two-cell pocket's second cell does.
    - A ready aura action goes first at most `MAX_DEFER_TICKS` (2) ticks in a row, then the dig takes the tick
      (no starvation either way); a tick the job does not want ends the run. 3 refusals in a row back off 5 ticks. `Mine.MaxTicks` counts TAKEN ticks only;
      the wall wait is bounded by the dig's predicted time AS IF SPENT IN THE AIR (a jumped fight would otherwise end
      every dig) plus pacing, ping and slack; a cell predicted beyond 200 ticks standing is not started; a job whose
      victim is no longer a target ends (no new pocket is searched while one runs). `Interrupt` is hidden here.
    - Needs `SpeedMine` enabled in `Packet` mode (`ModuleSpeedMine.auraCompatible`). Otherwise `Mine.RequireSpeedMine`
      (default ON, shown only under `Engine = SpeedMine`; 2026-10-03, the user's ask) skips AutoMine altogether - the
      tick plans exactly as with AutoMine off and a running job of either engine is reset; OFF falls back to the
      vanilla engine. Either way the hub says so once. "Cannot dig for us" includes SpeedMine switched ON in a mode
      the engine refuses (Damage, NCPAir rebreak), not only switched off.
    - KNOWN COSTS: SpeedMine's `StartMode = Abort` sends its ABORT at the next tick head, so a START costs the aura
      that tick too (foreign dig seen). AutoCity feeding SpeedMine at the same time evicts our dig (bounded restarts).
      SpeedMine's own REBREAKS (after the first STOP) still use its rotation path and can starve under the aura's aim -
      pre-existing, not changed. NOT playtested.
  - **`Mine.MinGain` is measured against everything the aura can do without digging, and held for the WHOLE dig**
    (both engines; 2026-10-03, the user's report "it digs blocks that give no gain", log: a second pocket started
    the tick after the first one opened). The reference (`ModuleCrystalAura.withoutMining`) is the most of the
    placement ranking's head, the `BasePlace` plan, `breakBest` (its pedestal outlives the blast), our placements in
    flight (`CrystalLedger.pendingDamage`, the damage `markPlaced` now records) and the pocket a job just opened
    (`AutoMineEngine.takeFinished`, kept `OPENED_POCKET_TICKS` = 20 until `BasePlace` fills it). Before, only the
    first two counted, so for a round trip after every placement and for the tick after every dug pocket the
    reference was 0 and ANY dig passed. Two thresholds, on purpose: a NEW dig needs `mineGainFloor` (reference +
    MinGain), a RUNNING one is given up only once it is worth LESS than the reference (`has < ref`, the `keepAbove`
    of every `plan` call; strict, or `MinGain = 0` starts and drops the same pocket every cooldown), through the
    engine's own path (`MineJob.abort` with the interrupt cooldown, `PacketMineJob.giveUp`, never once our STOP is
    out). A give-up during the dig costs the aura that tick: SpeedMine's ABORT leaves from PLAN and reads as a
    foreign dig. A finished pocket of the engine NOT in use, and `openedPocket` while AutoMine is off, are dropped. Most of a running dig's cost is paid, and the band between the two is what stops
    start / give up / restart-from-zero whenever the reference wobbles (refutation audit, same day). The job is
    judged by the damage its pocket was PLANNED for, not re-scored per tick - a re-score through
    `PlaceSearch.evaluate` would read the pocket's own drop in the column (below) as "worthless" mid-dig. `Debug`
    prints `| mine ref=… need=… has=…`. Known gap: a crystal of ours standing on a spot the break rules refuse or
    cannot aim at is in neither `breakBest` nor pending and blocks its own column, so it adds nothing to the
    reference. NOT playtested.
  - **A pocket on standing obsidian outranks one that still needs obsidian by `BasePlace.MinGain`** (`MineSearch.rankOf`,
    2026-10-03, the user's report: a block sitting on obsidian - one dig, then the crystal straight on - lost to an
    equal pocket elsewhere). The second pocket is the same dig PLUS a base placement, the step `BasePlaceSearch` prices
    against a direct placement with that very setting, so its damage is divided by `1 + BasePlace.MinGain / 100` for
    the ranking (never for the MinGain floor, which stays raw damage). `refine`'s cutoff is the best RANK (>= the
    floor): rank <= damage <= bound, so a bound below it still ends the drain exactly. Ties: rank, then "needs no
    obsidian", then self damage, cells, distance. NOT playtested.
  - **A pocket that digs a block `ModuleSurround` placed within the last second is taken only when no other pocket
    deals the same damage within 0.5 HP** (`MineSearch.pick` -> `pickAvoiding`, 2026-10-17, the user's ask; a margin
    in HP was the user's choice over exact equality, which two different cells practically never reach). Measured on
    target DAMAGE, not on the rank - the user's words; a first cut also accepted "rank within 0.5", which let a pocket
    up to `D/6 + 0.5` HP weaker win at the default `BasePlace.MinGain` and was dropped after the refutation audit.
    Among the equal pockets `BY_RANK` decides. A Surround pocket more than 0.5 HP above every other is still dug; only
    the cells a plan BREAKS count. While the head is such a pocket `refine`'s cutoff is
    `max(floor, min(head.rank, head.damage - 0.5))`, so every alternative is drained (bound >= damage). One search
    judges all cells against ONE instant (`surroundSince`). The record is `BlockPlacer.placedSince`: stamped when the
    placer's observer saw our `UseItemOn` UNCANCELLED (InventoryMove's `FIRST_PRIORITY` flush cancels some), kept
    10 s, cleared on a world change, NOT on disable - a Surround switched off a moment ago still counts, by the user's
    "within the last second". Known hole shared with `ownSend`: per-placer, not per-thread brackets, so an `Instant`
    (netty) placement overlapping a main-thread one can miss or misattribute a record. Running jobs are not re-judged,
    and a cell already dug keeps SpeedMine's rebreak (`PacketMineJob` hands non-base cells back), so Surround
    re-placing it can still be rebroken - NOT addressed. `Debug`: the mine note ends in `surround=avoided x, y, z` or
    `surround=kept x, y, z` (`kept` can also mean `MAX_REFINED` ended the drain first). Pure rule tested in
    `MineSearchTest`. NOT playtested.
  - **Every dug pocket fills with its own drop.** `EndCrystalItem.useOn` refuses ANY entity in the crystal column
    (`EndCrystalItem.java:37-39`), item entities included; the broken block's drop lands in the pocket and pushes
    itself out of the obsidian upwards (`ItemEntity.java:137-139`), i.e. into that column. A real opponent next to
    it picks it up after the 10-tick pickup delay (`Block.java:435`, `ItemEntity.java:389-391`); `.fakeplayer`
    never does (it is not a server entity), so in fake-player tests every pocket stays unusable until WE pick the
    item up or it despawns, and AutoMine looks broken. Nothing in the aura handles items - `isColumnFree` simply
    refuses the cell, which is correct.

  - `Player.canHarmPlayer` is modelled in `WorldView.skipOf` / `selfSkip` (2026-09-20): a crystal WE
    detonate carries US as the causing entity (`EndCrystal.hurtServer` → `explosion(this,
    source.getEntity())`), so `ServerPlayer.hurtServer` refuses the hit outright for an allied
    victim with friendly fire off — for the target AND for ourselves. Only the team half is
    knowable client-side; the server's `isPvpAllowed()` is not, and when it is off we over-predict,
    which is conservative in both directions. **Do NOT copy this into `AutoAnchor`**:
    `badRespawnPointExplosion` carries no entity, so `canHarmPlayer` is never consulted there.
  - `Place.AssumeArmor = Full` SUBSTITUTES our armour/toughness for the victim's, it does not floor
    them: a `maxOf` cannot fix the case the setting exists for (equipment reported as empty, and 0
    loses every `max`). A victim genuinely better armoured than us is over-predicted; that is the
    accepted cost.
  - **BasePlace never offers a neighbour face the base cell's own block covers** (`base/BasePlaceSearch.coversFace`,
    2026-10-02, the user's report "trouble placing on blocks covered by one layer of snow"). A one-layer snow (or a
    floor fire) is a slab glued to the floor of its cell, so it covers the TOP face of the block beneath and no
    pick can end there (`ReplaceInPlaceTest` proves it with vanilla's `BlockGetter.clip`: 0 of 729 rays). Before,
    `clickFaces` offered that face: with `ThroughBlocks` off its 32-ray look search was wasted on the slab, with it
    on the obsidian went out THROUGH the very block it replaces. Two conditions, both load-bearing: BOTH outline
    shapes must be `Block.isFaceFull` on the shared plane (a fire on a bottom slab covers the plane, but the slab's
    top is half a block lower and reachable from the side - the refutation audit's counterexample), and a covered
    face is dropped only when the cell is self-clickable, so the replace-in-place click always takes its place (a
    light block held as a full cube by an operator would otherwise lose the placement). Do not "restore" the face
    for ThroughBlocks.
  - **A crystal the aura will not detonate must never hold the module** (2026-10-03, the user's report "places, but
    never detonates", log: one placement, then seconds of `idle try=--`). Two defects of one family, both
    pre-existing: with `SameTick` off a break candidate suppressed the tick's use BEFORE `resolve` tested its aim, so
    a crystal no ray picks left every tick with neither action; and `wastedByOwnBlast` counted every own crystal in
    break reach, so one the break rules REFUSED forbade every placement within 12 blocks of it for up to
    `OWN_TICKS` (600). Invariants, do NOT regress:
    - `breakBest` is the first crystal of `BreakSearch.ranked()` that `TickPlanner.canAim` admits (`searchTick`);
      the accepted ones it refused go to `Searches.unaimable`. `canAim` is exactly `attackHits` + `attackLook`'s
      `forCrystal` fallback on the same inputs, so `resolve`'s drop-the-attack path is now a guard, not a mechanism.
      An IdPredict phantom is tested the same way inside `decide`.
    - `wastedByOwnBlast` (`Standing.protects`): with a `breakBest`, standing crystals not known `unaimable` count
      as before. A crystal the aura is not detonating (no `breakBest`, or `unaimable`) counts only for
      `standingWaitTicks` = place prediction horizon + 2 after its spawn (`CrystalLedger.ownAge`), then not. NOT
      "never": the first cut skipped such crystals outright, and the refutation audit showed the stack that makes -
      with `Predict` on a crystal placed for where the target WILL be is routinely refused at its spawn (the break
      side predicts a shorter way ahead) and becomes the attack once the target arrives; placing in its blast
      meanwhile stacks crystals one detonation then throws away. NOT "always" either: that was the 30 s stall.
    - A placement is refused when the crystal it would spawn cannot be picked from the current eye
      (`canAimSpawn` = `hitsPhantomBox` / `forPhantomBox` on `ExplosionMath.crystalBox`, the tick's attack crystal
      ignored): `PlaceSearch`'s `breakReachSq` is a BOX distance, a lower bound, so a crystal behind the target's
      body, behind a block, or with only a corner in reach used to be placed and then stood undetonatable. The
      filter runs in `searchTick` BEFORE the ranking's head is taken, so BasePlace and AutoMine measure MinGain
      against a placement this tick can actually make.
    - **With `ThroughBlocks` on, the ATTACK rays ignore blocks too** (`RayTests.hitsCrystal` / `hitsPhantomBox` and
      their `LookPoints` searches take `throughBlocks`; 2026-10-03, the user's report "it does not place through
      blocks at all though it is on"). The first cut of `canAimSpawn` applied the crystal's occlusion to placements
      the setting had sent through that very wall, so it refused nearly all of them. Legal: the server tests an
      attack's range only (`ServerGamePacketListenerImpl.handleAttack`, `:1768`) and Grim's `Reach` intercepts the
      look ray with the target box only (`checks/impl/combat/Reach.java:293`). ENTITIES on the ray still mask the
      crystal (`LookThrough`). Do NOT split the attack half back out: a through-wall placement whose crystal cannot
      be attacked is exactly the "places but never detonates" stall. NOT playtested.
    - `Debug`: an idle line with no break candidate ends in `| standing: noaim#<id> refused#<id>` for our crystals in
      reach - the first thing to read on a "does not detonate" report.
    - KNOWN LIMITS: BasePlace's and AutoMine's own crystal cells are not run through `canAimSpawn`; the ranker caps
      finalists at 8, so with the best 8 cells all behind the target's body or out of the pick's reach nothing is
      placed even if a 9th would do (idle, not a stall - nothing is left standing). Worst case adds ~1.6k ray picks a tick (8 crystal
      aim tests + 8 spawn aim tests); the sustained case is ~600 with `ThroughBlocks` next to walls. NOT playtested.

  - **Runtime access (2026-10-07):** `EnchantProtection` in both CrystalAura and AutoAnchor calls
    vanilla's private `EnchantmentHelper.runIterationOnEquipment` through its package-private
    `EnchantmentInSlotVisitor`. Both are opened by OUR `liquidbounce.accesswidener`: Fabric API opens
    them for the development classpath but is only suggested in `fabric.mod.json`, so a user's
    installation without it otherwise throws `IllegalAccessError` before either aura can search.
    Keep BOTH entries (method and interface). `compileKotlin` alone cannot verify this resource fix;
    build the jar and check its packaged widener against the unmodified vanilla classes.

  Known model limits (documented in the KDocs, not bugs to "fix"): a remote victim's Resistance
  amplifier is NEVER synced — hence `Place.AssumeResistance`. Absorption is unavailable for
  non-player mobs. `lastHurt` is the ledger's own estimate.

  Settings that exist because a SERVER lied, not because the model was wrong (learned the hard way
  2026-09-07 — read the Debug line before suspecting a formula):
  - `Place.AssumeArmor` (Off/Protection/Full) — servers that obfuscate equipment make
    `getArmorValue()` read 0 and the enchantment list read as a DECOY. Substitutes our own values
    (a replacement, not a floor: a fake Protection IV would survive a `max`).
  - `Place.ThroughBlocks` (default OFF) — acts on occluded blocks AND attacks occluded crystals (since
    2026-10-03). Neither the vanilla server nor Grim traces the world between eye and target
    (`RotationPlace.didRayTraceHit` intercepts the TARGET box only; `Reach` the same for attacks). The one
    action in this module a vanilla client cannot produce.
  - `Break.IgnoreMissTime` (default ON) — vanilla's `missTime = 10` after a whiffed click froze
    detonation for half a second. It is a client-side input lockout; no server or Grim rule reads it.
  - `Break.IdPredict` (default OFF) — attacks a predicted entity id and can earn an
    `invalid_entity_attacked` kick; opt-in, limited to the tick right after the placement.
  The `Debug` toggle prints the victim's `arm` / `tgh` / `prot` / `res` as the model read them.
  That line is the FIRST thing to look at when damage predictions look wrong.
- `combat/autoanchor/` — **AutoAnchor**, THE anchor aura of this client and an ORIGINAL module,
  built to the CrystalAura doctrine below (read that section first). It shares no code and no design
  with the two modules it replaced (`ModuleAutoAnchor`, `ModuleAutoAnchor2` and their
  `AutoAnchorFinder` / `AutoAnchorInteractor` / `AnchorPlacementUtils` helpers), all DELETED on
  2026-09-08 once this one was proven in game; they were never used as references. It shipped as
  `TestAutoAnchor` and was renamed on the same day. `ModuleAnchorOptimizer` is a DIFFERENT feature
  (charge/pop anchors we placed, no damage model) and stays.
  Layout mirrors `combat/crystalaura/`: `calc/` = the damage model, `world/AnchorView` = per-tick
  snapshot, `scan/` = branch-and-bound search, `aim/` = pick replica plus smoother, `seq/` =
  `AnchorFlow` (pure, headless, unit-tested) plus `AnchorJob` (the client shell), `exec/` =
  executor, `state/` = the ledger, `render/` = the overlay. 106 tests under
  `src/test/.../autoanchor/`.

  Vanilla facts it is built on, all read from the 26.1.2 sources — do NOT re-derive:
  - Three interactions of the SAME class (place, charge, detonate are all `UseItemOn` on a block),
    and `PacketOrderI` allows ONE per tick. So a cycle is up to three ticks and can NEVER be
    batched. `AnchorFlow` enters at the first MISSING step, so a standing charged anchor is one
    tick, not three.
  - Power 5.0 (`doubleRadius` 10, peak raw 71) and the centre at `pos.getCenter()` — INSIDE the
    anchor's own block. That geometry, not a toggle, is why faceplacing works here and cannot for
    a crystal; the ranker reaches it by itself when the foot cell is unreachable.
  - `RespawnAnchorBlock.explode` calls `level.removeBlock` BEFORE `level.explode`, so the
    candidate cell is overridden to `Shapes.empty()` for the exposure rays — the mirror of the
    crystal's `Shapes.block()` base override. Getting it backwards collapses the exposure to 0 for
    every victim not directly above; this repo has made that mistake once already and
    `AnchorRankingTest` now guards it.
  - `bad_respawn_point` is in `#is_explosion` and in NO `bypasses_*` tag, so armour, shield,
    i-frames, Resistance and both Protections apply exactly as for a crystal — the victim pipeline
    is reused verbatim, with `isDirect = true` (the source carries no entity).
  - The charge IS predicted client-side; the detonation is NOT (`useWithoutItem` returns `CONSUME`
    and `ClientLevel.explode` is empty). The ledger is built around that asymmetry: a detonated
    cell stays hidden until an explode packet, a vanished anchor, or a timeout accounts for it.
  - Glowstone in the OFFHAND makes `useItemOn` return `PASS` and charges instead of detonating —
    the module says so in `Debug` and stands down rather than looping.

  Invariants, do NOT regress:
  - Arbitration is split by KIND. The tick is yielded for 200 ms after another module's block
    INTERACTION (wall clock — handler order between modules is undefined). The AIM is the aura chain
    below. `ModuleAutoTotem.isSwapping` is a THIRD kind and a full stand-down in `standDownReason()`
    (2026-09-13, the ghost totem — see that module's entry above); an aborted cycle is cheaper than
    a totem that does not pop.
  - **The aura chain, set by the user 2026-09-11: KillAura < AutoAnchor < CrystalAura.** Ordered by
    how often a module CAN act, inverted — KillAura swings at anything in reach, an anchor at the
    victim's feet almost always clears its damage floor, a crystal needs a pedestal AND enough
    damage. The rarest opportunity therefore outranks the commonest. Enforced by ABSTENTION, the
    idiom KillAura already used for CrystalAura: the lower module files no rotation request at all
    while a higher one is aiming (`ModuleKillAura.anAuraIsWorking`, `AutoAnchor.standDownReason`).
    The priority numbers are only the second layer — AutoAnchor and KillAura both sit at
    `IMPORTANT_FOR_USAGE_2`, and `RequestHandler` orders by priority alone, so the tie is broken by
    the abstention and not by the numbers. `ModuleAnchorOptimizer` is outside the chain and above all
    of it (`IMPORTANT_FOR_PLAYER_LIFE`).
    The flag is `isActing`, meaning WORKING — a packet sent within the last ~5 ticks, or a cycle
    still in flight. NOT `isRotating`: that is stamped whenever CrystalAura merely HOLDS the aim,
    which it does whenever a breakable crystal is in range, so gating on it made the lower auras
    stand down for a module that was only switched on. Enabled is not active, and the user's rule is
    "while one WORKS the others wait".
    **Surround sits on TOP of the chain since 2026-09-27** (the user's ask) - enforced on packets only,
    see the `world/ModuleSurround.kt` entry.
  - **Never CLICK a block inside a live blast shadow** (`AnchorLedger.isBlastShadowed`). Grim's
    `AirLiquidPlace` reads the clicked block out of its own lag-compensated world, flags when it is
    air or a no-place liquid, and runs on EVERY `PLAYER_BLOCK_PLACEMENT` — a charge and a detonate
    click count exactly as much as a placement — with cancel VL 0, so the first violation resyncs.
    An anchor blast destroys ordinary blocks on the normal rules (`RespawnAnchorBlock.explode` passes
    `ExplosionInteraction.BLOCK`; its calculator overrides the resistance only for the anchor's own
    cell in water), and the client predicts NO part of the detonation — so for about one round trip
    it still draws a sphere of blocks the server has removed, supports included. The shadow is armed
    at OUR detonating click (not when the explosion comes back — that is already too late) and by any
    `ClientboundExplodePacket`, and lasts ping + 2 ticks. Reported in game 2026-09-11 as "AutoAnchor
    and AnchorOptimizer sometimes get flagged by AirLiquidPlace". `ModuleAnchorOptimizer` only ever
    clicks the anchor itself, and an anchor is obsidian-tier so no blast can break it — its own guard
    is therefore per-cell (`detonatedAnchors`), not a sphere.
  - **A click on an anchor WE placed waits for the server's answer to the placement** (2026-09-25,
    packet audit). `AirLiquidPlace` (LIVE, cancel VL 0) reads the clicked block out of Grim's own
    world on EVERY `UseItemOn`, charge and detonate included, at packet receipt. Grim writes our
    placement there only at the drain, and skips it when our box or a tracked entity's box touches
    the block (`BlockPlace.java:546-596`; an entity the client already removed still counts until
    the transaction after its destroy is answered, `PacketEntityReplication.java:393-401` - a
    crystal popped this tick), or when a live check cancelled the placement. Then the anchor reaches
    Grim's world only with the server's block update, ~1 round trip later, and the next-tick charge
    was a click on air. None of that can be mirrored client-side (a contested-cell heuristic was
    written and dropped the same day after a refutation audit), so the client-wide
    `utils/block/PendingPlacements` (registered next to `OffhandSyncTracker` in `LiquidBounce.kt`)
    records EVERY block our client places - this module, the player's own hand, any other module -
    from `ClientBlockPlaceEvent`, binds it to the sequence of the very next outgoing `UseItemOn`
    (the event fires inside `startPrediction`'s `predict`, before its `send`,
    `MultiPlayerGameMode.java:289-295`), and answers `isAwaitingServer(pos)` until `BlockChangeAcks`
    reports the server's ack for it (sent AFTER the click's block updates,
    `ServerGamePacketListenerImpl.java:293-297` vs `:1361-1362`). `AnchorFlow.awaitingServer` holds
    every click on such an anchor, both after our own placement resolves and when a cycle `begin`s on
    one. Invariants: per CELL, independent of cycles; an unseen sequence never confirms; a 2 s
    timeout is the only other way out. COST: ~1 RTT + up to one server tick on every cycle that
    places. KNOWN HOLES: `PingSpoof` (holds incoming pings) and Timer `SlowBoost` (holds outgoing
    pongs) break the ack -> pong chain. Dropped items are NOT a cause (Grim does not track them,
    `CompensatedEntities.java:173-174`). Any module that clicks a block it has just placed has the
    same exposure and should ask `PendingPlacements` - `ModuleAnchorOptimizer` does; CrystalAura's
    base -> crystal placement has NOT been audited for it.
  - `Rotation.Settle` (off by default): click only once `AuraRotation.wire` lands on the block too.
    That is the rotation `RotationPlace` casts when the flying reaches Grim >= 15 ms after the click
    and on its pre-flying leg, which ONE post-flying miss of ANY module arms (the buffer is per
    player). Costs one tick per cycle that has to turn; a held aim costs nothing.
  - **Every range test uses `AnchorSettings.reach`** = `Range` capped at
    `player.blockInteractionRange()`. `RotationPlace` casts rays exactly the attribute long, with no
    threshold (`RotationPlace.java:120-129`), so a hit point past it is a post-flying miss ->
    `flagBuffer` -> the pre-flying leg cancels every first-of-cycle click (aimed and clicked in one
    tick) for ~10 interactions. `FarPlace` is the milder half. `Range` still goes to 5.5 in the GUI;
    it simply cannot exceed the attribute any more.
  - **A neighbour face the cell's own block covers is never a placement target** (`scan/AnchorSearch.coversFace`,
    used by `clickFaces` and re-tested every tick by `AnchorJob.isClickFace`; 2026-10-02, the user's report about
    snow). A one-layer snow covers the top face of the block beneath it, and `AnchorJob.clickTarget` takes the
    NEIGHBOUR form first: with `ThroughBlocks` off no ray could land on that face (vanilla clip, 0 of 729 rays in
    `ReplaceInPlaceTest`), so every cycle on a snow-covered cell aimed at nothing until its budget ran out and the
    search proposed the same cell again; with it on the anchor went out THROUGH the snow it replaces. Now the snow
    itself is clicked (replace-in-place), same cell, vanilla-shaped ray. Same two conditions as CrystalAura's copy
    (both shapes full on the shared plane; dropped only while `selfClick` holds). NOT playtested.
  - Stands down while `InventoryManager.isInventoryOpenServerSide` (PLAN and again at EXECUTE):
    TotemGuard `InventoryA` fails `PLAYER_BLOCK_PLACEMENT` ("place") and `HELD_ITEM_CHANGE`
    ("change slot") while a container is open. Grim has no such check. Residue: a slot switch filed
    in the PLAN of the tick another module opens the inventory, and a silent swap-back, can still land
    inside the session.
  - The one-interaction-per-tick rule is protected by a LIVE check too, not only by `PacketOrderI`:
    `AirLiquidPlace` runs at packet receipt and Grim writes a placement only at the drain, so a
    same-tick charge of a same-tick placement always reads air. On a tick with no flying (standing
    still, aim held) the drain before the charge is the charge's own `SetCarriedItem`
    (`PacketPlayerDigging.java:109`) or a pong (`GrimPlayer.java:444`).
  - `DetonateHand = Anchor` (and `Held` with a block item) makes Grim write a PHANTOM block against the
    clicked face until the server's answer: Grim counts a click on an anchor as consumed only for
    glowstone (`ConsumesBlockPlace.java:75-78`). `Safe` never picks a block item.
  - Take the aim ONCE per cycle and hold it. `DuplicateRotPlace` arms only on a repeated yaw delta
    above 2 degrees, so clicking one cell three ticks running is the SAFE shape and re-aiming
    before every click is the dangerous one.
  - The cycle is never re-scored mid-flight; "this is taking too long" is the cycle budget's job
    (`cycleBudget` = 20 ticks + ping ticks, ping capped at 40).
    There is deliberately no Interrupt setting — aborting after the charge burns a glowstone, and
    an abandoned cycle leaves a standing charged anchor, which is worth MORE than it cost.
  - **No `Recharge` and no `Cycle` group any more** (2026-09-26, the user: the module's job is anchors at the
    enemy's feet plus the damage count; knobs like these belong in the logic). Found from the user's log +
    `modules.json`: a saved `Recharge = False` left only already-charged anchors acceptable, so a whole fight
    logged `idle | -` with no reason, and `MaxTicks = 5` was shorter than the ack wait below, which is charged
    to the SAME counter (`AnchorFlow.tick`, step 2). Baked in: charging is always on (missing glowstone still
    restricts the search to charged anchors); the budget is `CYCLE_BASE_TICKS` (20, the old default) + ping
    ticks, because the wait for our placement's ack grows with ping and a fixed budget shrinks under it;
    `detonateTimeout` is always `ping + 2`; dud window 100 ticks and latch after 2 are constants. Do not bring
    any of them back as settings - a saved wrong value silently disables the module. The pure classes
    (`AnchorFlow.tick(maxTicks)`, `AnchorLedger.onTickStart`) still take them as PARAMETERS, for the tests.
    `ModuleAnchorOptimizer` has its OWN `Recharge` and was not touched.
  - The detonating hand prefers a TOTEM (on the `DEATH_PROTECTION` component, not the item id).
    `checkTotemDeathProtection` reads the MAIN hand first and `bad_respawn_point` does not bypass
    invulnerability, so the totem pops on our own blast. `ModuleAnchorOptimizer` does the same.
  - `AnyDimension` overrides the `respawnAnchorWorks` stand-down — the first thing to try when the
    module is silent on one server only.
  The overlay's two ground rings (`render/AnchorDanger`) are a DIAGNOSTIC, not decoration: at
  anchor range the self-damage gate binds far more often than the target one, so "it refuses
  everything" is usually "you are standing inside the red ring". Read them before touching the
  damage model.
- `combat/testaa/` — **testAA**, a port of the donut / "aesthetic" client's "Anchor Aura" (2026-10-01, the
  user's ask: port it, name it testAA, **strictly 1:1, WITHOUT our Grim guards**). Source:
  `C:\Users\caves\Documents\donut\work\src_named\c\AnchorAura.java` + `i^I^I^I^Iiiiii.java` (placing group) + the
  helpers in `SocialCommand.java` / `DamageUtil.java` / `RaycastUtil.java`; settings defaults from
  `donut\work\modules_default.bin.out`. Layout: `TestAAMath` (damage, exposure, blast-through cells), `TestAAWorld`
  (raycasts, the two aim searches, support / safe-place tests, slots), `TestAASearch` (anchor / placement / cover
  searches + sorts), `ModuleTestAA` (tick, actions, settings). Invariants, do NOT regress:
  - **None of `combat/autoanchor`'s anticheat invariants apply and none may be added here** without asking: charge
    and detonate go out as two `UseItemOn`s in ONE tick, a placement may follow in the same tick, `RemoveLocally`
    erases the detonated anchor client-side, no ack wait, no blast shadow, no reach cap, no aura chain, no totem
    stand-down. NOT Grim-audited. OVERLAP: enable either this or `ModuleAutoAnchor`.
  - The damage model is donut's, not vanilla's, on purpose: no difficulty scaling, `getArmorValue()` + toughness
    attribute, predicted OFFSET on the victim box, exposure with an AIR cell / SOLID cell / AIR set and collision
    shapes without entity context. Do not "fix" it onto `EntityExtensions.getDamageFromExplosion`.
  - Donut's tick handler, placement / cover executors, self-safety check, rotation gate and `<clinit>` are `native`
    (AOT) and were NOT traced; those parts are reconstructions, each marked GUESS/INFERRED in the KDoc. A subagent
    building tooling to trace that native code was stopped by a safety classifier on 2026-10-01 - do not resume
    native tracing; third-party reconstructions in `donut\work\aot_java\` are read as hints only (`f15` is
    internally contradictory; `f18` supports the rotation-gate reading).
  - PLAN at `RotationUpdateEvent` files the aim, EXECUTE at `InputHandleEvent` checks the OUTGOING rotation
    (`RotationManager.currentRotation`) and clicks - donut's gate writes its rotation holder synchronously and the
    lambda raycasts it at once, i.e. aim and click in one tick.
  - A fresh read-only refutation audit found no behavioural mismatch between the ported (non-native) parts and the
    decompile/bytecode. NOT playtested.
- `combat/testat/ModuleTestAT.kt` — **testAT**, donut's "Auto Totem" (2026-10-01, the user's ask, after
  `donut\work\aot_java\AUTO_TOTEM.md`). Unlike testAA this is mostly a RECONSTRUCTION: in donut the tick, every threat
  check and the swap are `native`; what is read is the settings tree + defaults (config dump), the four threat labels,
  the reset state and the "plain totem" helper (`SocialCommand.java:55409`). The rest follows the third-party
  reconstructions f22-f25 (hints; f23/f24 disagree) and is marked GUESS/INFERRED in the KDoc. Invariants:
  - **The swap is f25's, packets only**: `ensureHasSentCarriedItem` -> raw `SetCarriedItem(totemSlot)` ->
    `SWAP_ITEM_WITH_OFFHAND` -> `SetCarriedItem(held)` + a client-side mirror of the exchange. No container click,
    so `MultiActionsC` does not cancel it; totems come from the HOTBAR only. "held" is
    `SilentHotbar.serversideSlot`, never `Inventory.selected`. When the totem IS in the held slot the two
    `SetCarriedItem`s repeat it (`BadPacketsA`, live, cancels the duplicate) - kept as donut sends it.
  - Like testAA it takes none of `ModuleAutoTotem` / `ModuleOffhand`'s guards (no `SwapQuiet`, `HandSwapGate`, aura
    stand-downs, screen checks). OVERLAP: enable one totem module at a time.
  - Threat damage uses `testaa`'s port of donut's `DamageUtil` (`TestAAMath`); the Near Explosive scan reuses
    Falling Player's Radius / Height as its box, as f22 reads it. NOT playtested, not Grim-audited.
- `combat/autocity/ModuleAutoCity.kt` — mio's "AutoMine"/Combatmine: picks
  WHICH block near the enemy to break (un-trap > burrow > surround > head >
  feet > ender chest) and feeds a packet-mining engine (SpeedMine default,
  PacketMine option). Never sends dig packets itself — all packet-level
  Grim rules stay in the engine.
- `world/automine/` — legit ore AutoMine (NOT AutoCity — different module,
  see the AutoCity KDoc for the distinction).
- `world/ModuleSurround.kt` — upstream Surround (ccetl, on `utils/block/placer/BlockPlacer`). Ours is only
  its place in the aura chain: **KillAura < AutoAnchor < CrystalAura < Surround** (2026-09-27, the user's
  ask: Surround above all three). Invariants, do NOT regress:
  - **Held on PACKETS, not by abstention.** The placer already wins the aim on priority
    (`IMPORTANT_FOR_PLAYER_LIFE` 40 vs CrystalAura 35, AutoAnchor/KillAura 30), so the three keep filing
    their rotations and lose them; abstaining too would only buy a reset tick at the end of every burst
    (same reasoning as `ModuleAutoWeb` and `ModuleAnchorOptimizer`). KillAura holds its attack on the
    `canAttack` line; CrystalAura and AutoAnchor skip their slot switch in PLAN (`surroundHolds()`) and drop
    the plan at EXECUTE. Do NOT route this through their `standDownReason()` - that idles the rotation.
  - **EXECUTE must ask again.** The placement leaves from `PostRotationExecutor`'s `GameTickEvent` handler,
    `FIRST_PRIORITY` like `RotationManager`'s (which runs the auras' PLAN), so their order is undefined and a
    placement of this tick may be visible only from EXECUTE. KillAura's tick handler runs after both.
  - **`isActing` = `BlockPlacer.isPlacing` = a `UseItemOn`/`UseItem` of the placer in the CURRENT Grim tick
    window** (set from the PACKET under an `ownSend` bracket, cleared where Grim ends the window: every movement
    packet, and `ClientTickEnd` ONLY on a tick that sent no movement packet - `GrimProcessor.java:81-90`; clearing
    on every `ClientTickEnd` dropped a `PostMove` placement before any aura could read it). Nothing wider - the user's rule, 2026-10-03: "Surround holds only for the block placement,
    not while it is on". Until then it also covered 250 ms after every placement and every tick a placement was
    merely SCHEDULED (rotation still turning), and with `DisableOn` empty Surround rebuilds around the player
    wherever they step, so in a moving fight that held the three auras almost permanently (the user's log: 309
    `holding: Surround is placing` in one session). The wider window bought nothing: the aim is Surround's by
    priority anyway and the auras verify every click against the outgoing rotation. Do not bring back a
    "scheduled" or time-based half.
  - The placer's crystal destroyer is raised to the placer's priority when that is above
    `IMPORTANT_FOR_USAGE_3` (only Surround's is): at 35 it TIED CrystalAura, and a tie has no defined
    winner. Placer and destroyer share one rotation provider, so between themselves the later request
    still wins, as before.
  - `ModuleSurround.placedSince(pos, sinceMs)` (2026-10-17) exposes the placer's own placement record to CrystalAura's
    AutoMine, which avoids digging a block Surround placed within the last second when an equal pocket exists - see
    the `combat/crystalaura/` entry.
  - COST: a placement costs the auras the clicks of its own tick window; while Surround turns onto a cell they
    lose the AIM (priority), not the click. An AutoAnchor cycle that loses its aim long enough ends on its budget
    and re-enters later, and re-aims after the interruption (the once-per-cycle aim is broken for that cycle -
    `DuplicateRotPlace` is experimental).
  - NOT playtested. No new packet is sent; the change only withholds packets.
  - **A dropped item never blocks a placement** (`utils/block/BlockExtensions.kt`, `isBlockedByEntities` and
    `isBlockedByEntitiesReturnCrystal`, i.e. every `BlockPlacer` module; 2026-10-03, the user's report "Surround reacts
    very slowly, especially in cpvp"). Before, ANY entity box in the cell made the placer skip it, and a block the
    enemy mines drops itself into its own cell with a 10-tick pickup delay (`Block.java:407-413`, `:435`;
    `ItemEntity.java:389-390`), so every mined surround block was refilled >= 500 ms + half a round trip late, and a
    cell with loot in it (inventory full) stayed open. Items are the ONE entity class both sides ignore: the server
    refuses only `blocksBuilding` entities (`EntityGetter.java:37-42`), Grim does not track items
    (`CompensatedEntities.java:173-174`). Keep every OTHER entity blocking: an XP orb or an arrow does not stop the
    server, but Grim then leaves the cell air until the server's block update (`BlockPlace.java:567-586`), and a click
    against that block inside the window is `AirLiquidPlace` (LIVE). The same drop still stops the enemy's CRYSTAL
    (`EndCrystalItem.java:37-38` refuses any entity), which is the race this wins. NOT playtested.
  - Known, NOT fixed (2026-10-03): `Instant` places from the incoming `PacketEvent`, which fires on the NETTY thread
    (`MixinConnection.genericsFtw` HEAD) before the main thread applies the block update, and it only runs under
    `RotationMode = None` - do not recommend it. `Features.EXTEND` is dead: `getEntitySurround` tests `it !in blocked`
    where `it` is the hole cell it has just added to `blocked`, so it never adds a block. The crystal destroyer gates
    on `serverRotation` (aim already on the wire), so a crystal in a surround cell costs two ticks plus a round trip
    before the cell is free.
- `world/scaffold/techniques/normal/ScaffoldTellyRandomizer.kt` — Scaffold **Telly `ResetMode = Randomized`**
  (2026-10-04, the user's ask, for Polar and Intave - "on Grim almost any scaffold works"). Per jump: face forward for
  `MinForward`..deadline ticks, a coarse swing back (miss = 1.5..3 spreads), a correction onto the face, hold at least
  `MinHold` ticks, turn forward before touchdown. `YawSpread` / `PitchSpread` (pitch much narrower, the user's spec).
  Invariants, do NOT regress:
  - **The upper bounds are derived, never settings** (the user's choice). A fresh `SimulatedPlayer` per air tick finds
    touchdown (first step that lands or drops below the take-off row's top); the turn deadline is
    `land - 1 - SAFETY_TICKS(2) - calculateTicks(aim) - 1(coarse) - (needed - 1) * (1 + Delay.max)`, the release window
    `land - calculateTicks(player.rotation)`. Priorities: not falling > `MinHold` > speed.
  - **Release on `Flight.supported` (the simulated touchdown lands), NOT on "the line's gaps are filled"**, and
    `mayRelease` is evaluated even with no aim point. The first cut did both the other way: once the last gap was
    filled the technique has no target (`TargetFinding.kt:255-260`), the hold never released and every jump landed
    facing back; the scaffold also builds its own 4-connected path and may never fill a corner of the straight line.
  - **The spread is anchored to the face CENTRE, not to the technique's aim.** `Stabilized`/`NearestRotation` aim at the
    face point nearest the current server rotation (`FaceTargetPositionFactory.kt:122-126`), so an offset on top walks
    across the face by the spread every tick. The technique's aim is only the fallback for a hidden face centre, and
    then with no spread and no coarse miss.
  - Every request expires after ONE tick (`expiringNextTick`, wrapped in `ModuleScaffold.rotationUpdateHandler`): the
    stock Reset relies on `TicksUntilReset` (default 5), longer than a telly ground phase, so the return never started.
  - The jump waits for `facesForward` (outgoing rotation within 1 degree of the camera): with Silent correction the
    jump boost follows the silent yaw (`MixinLivingEntity.hookFixRotation`). While it waits nothing aims on the ground;
    `Ledge` is the guard against walking off.
  - Only `RotationTiming = Normal` with `MovementCorrection` Silent or Off is driven; anything else falls back to
    Reset with `Straight = 0` (`Straight` is hidden under Randomized). Strict is excluded on purpose: its velocity
    follows the silent yaw with the input untouched (`RotationManager.velocityHandler`), so W while aimed back flies
    backwards and the camera-yaw flight model would call a landing supported that the player falls short of. Known:
    the `Randomizer` group still shows under the unsupported combinations.
  - `supported` = the simulated touchdown lands AND a column of the take-off row (or the one above) is under a point
    0.15 inside the hitbox - a margin for the simulated path not being the real one. State from another flight is
    dropped: a tick on which the mode did not run resets everything, and a row more than 1 below / 3 above the player
    gives no prediction (= hold until touchdown). Deadline sums cap `calculateTicks` (Acceleration can return
    `Int.MAX_VALUE`).
  - `Debug` (ModuleDebug parameters `Telly.*`): phase, land, supported, needed, slack, held.
  - HONEST SCOPE: Polar/Intave closed source, nothing checked against them; all distributions are GUESSES; the
    simulated path is raw input along the camera yaw, the real one is rounded to 8 silent-frame directions (~22 deg).
    Sends no packet of its own; placement still gated by Scaffold's own ray test (`RotationPlace`). NOT playtested.
- `world/timer/TimerSlowBoost.kt` — **SlowBoost**, a fourth mode of the upstream `ModuleTimer`
  (2026-09-21, the user supplied godweer's `TimerSpooky` / `TimerGrimSP` and asked for the
  principle). Slow phase banks Grim timer credit, boost phase spends it; the bank only exists
  because our `ServerboundPongPacket`s are withheld. Audited against the clone at `8eb5f28`
  (paths in the KDoc). Invariants, do NOT regress:
  - **The credit is MIRRORED from the packet stream, not assumed from the timer setting.**
    `balanceNanos` adds 50 ms per outgoing tick packet — flying, or `ClientTickEnd` on a tick that
    sent no flying (`GrimProcessor.java:70-91`) — and is floored at `now - Budget`, which is
    `limitFallBehind` (`Timer.java:95-97`). Every deliberate difference from Grim's accounting
    errs on the side of counting MORE (teleport-confirm flyings are counted here, excluded there),
    so the modelled credit is always ≤ the real one. Keep that direction when touching it.
  - **The ceiling is `TimerLimit`, not `Timer`.** Freezing the transaction clock makes `Timer`
    unbounded, but `TimerLimit.limitFallBehind` floors it at `now - ping-abuse-limit-threshold`
    (1000 ms default, `TimerLimit.java:38-46`, `:51`) plus 120 ms drift. Hence `Budget` maxes at
    1100 ms. Raising it past 1120 flags on a default config.
  - Pongs are QUEUED and replayed in order, never dropped — a gap is what `TransactionOrder` flags
    (`GrimPlayer.java:429-430`). `MAX_HOLD_NANOS` (20 s) bounds one withholding run against the
    60 s `max-transaction-time` kick (`GrimPlayer.java:571-573`).
  - The phase machine is on `GameRenderTaskQueueEvent` (per FRAME) on purpose: at ChargeSpeed 0.05
    a tick is a real second, and the port's tick-driven machine overshoots both phases by that
    much. Do not move it to a tick handler.
  - `disable()` files an explicit 1.0 request: a lapsed request expires in two TICKS, which at 0.05
    is two real seconds of a frozen game after a mode switch.
  - Stands down in vehicles (`VehicleTimer` counts packets this model does not) and on any
    `ClientboundPlayerPositionPacket` (a setback may BE the timer flag — flush, drop the bank).
  - HONEST SCOPE, in the KDoc: average speed over a cycle is 1.0x by construction — this buys a
    ~1.4 s burst, not distance. NOT playtested. Withholding transactions also stalls Grim's lag
    compensation, so a crystal/anchor aura clicking a block someone ELSE placed during a cycle can
    read as air to `AirLiquidPlace`.
- `exploit/phase/` — **Phase**: catlean's modes (`su.catlean.Phase`, obfuscated `su.catlean.b0`) plus upstream's.
  catlean's `Vanilla` (default), `Pearl`, `Step` replaced the four upstream modes on 2026-09-24; on 2026-09-26 the
  user asked for those back NEXT to them, so `Hypixel` / `Intave` / `Blink` / `BlocksMC` are CCBlueX's files again
  (`nextgen` `29163ec`; two adapted lines, named in the `ModulePhase` KDoc; not Grim-audited). catlean's modes keep
  catlean's setting names and defaults. HOW THEY WERE PORTED, keep this in
  mind for any other catlean port: the supplied `catlean/src/su/catlean/Phase.java` is damaged by the deobfuscation
  pass (the whole Pearl tick handler is gutted), so every mode was re-read from the ORIGINAL bytecode
  (`javap -c -p` on `su/catlean/b0.class` from `backup/catlean_26.2-original-modsdir.jar`). The opaque predicate is
  `n0.m()` = `n0.M` = 100 (real branch is the non-zero one); encrypted ints were decrypted with the class key (the
  `deobf/final-src.jar` copy of a class has `a` folded into every method as a literal, here `36630561139088`), and
  the decryption was cross-checked twice (property-table constants 6/7, and `b0.D`'s key vs the live dump
  `deobf/dump3/callsites.tsv`). Invariants, do NOT regress:
  - `Vanilla` / `Step` are NOT Grim-safe by design (`prediction/Phase.java:36-44` setbacks; `Step` sends a 2-block
    rise in one position packet). Only `Pearl` has a vanilla-shaped wire.
  - `Pearl` requests an INSTANT rotation (`RotationTarget` with no processors, `RotationTarget.kt:96-98`) at
    `RotationUpdateEvent` and throws at `InputHandleEvent` of the SAME tick, only when
    `RotationManager.currentRotation` is on the plan; the use packet carries exactly that rotation. That is what
    Grim's LIVE `BadPacketsJ` (`badpackets/BadPacketsJ.java:38-58`) compares against the tick's rotation. Never
    throw with a rotation other than the one this tick's movement packet carries.
  - The throw callback runs inside `HandSwapGate.admitted { }`: an inventory-route SWAP into the held slot arms the
    gate (`HandSwapGate.handSwapReason`) and would otherwise make our own `useItem` return PASS.
  - Inventory routes click only while `SwapQuiet.isQuiet` (Grim `MultiActionsC` cancels clicks while moving, and
    this mode fires by definition while walking into a wall); the pearl plan is HELD across ticks
    (`awaitingQuiet`, max 25) because the stop itself ends the wall collision that triggered it, and
    `requestStop()` is called on the same branch that bails. A fill that would need a click then is skipped.
  - `Fill` flags Grim's LIVE `RotationPlace` whenever it actually places (it clicks a face chosen by
    `CatleanBlockPlacer.findHitResult` while looking along the steep throw rotation). Kept on by default because
    catlean has it on; it rarely fires (the target cell is normally the wall itself).
  - Kept literally from catlean although they read like mistakes: `isAtStep` asks ONE block state for its shape at
    its cell and the cell above (so a full block never counts as a step); the 80-degree ladder pitch is unreachable
    (catlean compares a `BlockState` to a `Block`); `Step` uses `yRot % 90`, whose sign follows the dividend, so a
    negative yaw never takes the diagonal branch.
  - NOT playtested.
- `movement/ModuleNoPush.kt` — **NoPush**, catlean's module (`su.catlean.NoPush`, obfuscated `su.catlean.bu`),
  which REPLACED the upstream one on 2026-09-24 at the user's request. Four toggles, all on by default: `Blocks`,
  `Entities`, `Liquids`, `FishingHook`. The upstream `PushBy` list (what MAY push - the inverse) and `Sinking` are
  gone, and so is the `NoPushBy` enum; the mixins call `ModuleNoPush.ignores*()`. Invariants, do NOT regress:
  - Read from the bytecode, not the decompile. The decompile reads `bu.n` backwards: its predicate is not the
    `ch.E()` (false) the handlers use but a true one - `bu.n` is vanilla `FlowingFluid.isSolidFace` verbatim, which
    only the real branch can be.
  - `Liquids` is NOT "zero the current". It is vanilla `FlowingFluid.getFlow` minus the horizontal term, with the
    `FALLING` column term kept (`ModuleNoPush.liquidFlow`): a waterfall still pulls down. Applied only to the local
    player's own pass (`MixinEntityFluidInteraction`, `@WrapOperation` on `getFlow` in `update`). catlean hooks the
    shared `FlowingFluid.getFlow`, which `FluidRenderer` also calls (`FluidRenderer.java:132`) - that would mesh all
    water as still water. Do not "restore" the global hook.
  - `Entities` is catlean's hook, in `MixinEntity`: both `push(DDD)` calls of `Entity.push(Entity)` run with zeros
    when the receiver is the local player. The upstream `ClientLevel.getPushableEntities` hook was removed with it.
    Client-side only the local player is pushable (`EntitySelector.java:36`) and it is reached as
    `player.push(other)` (`LivingEntity.doPush`, `:3203-3205`); `LivingEntity.push(Entity)` only adds a sleep
    check before `super`.
  - `FishingHook` skips `pullEntity` (existing `MixinFishingHook`) instead of dropping catlean's whole entity event
    31: `FishingHook.handleEntityEvent` does nothing else with that id (`:488-494`).
  - Grim: `Liquids` flags `Simulation` in moving water - Grim applies the full vanilla current
    (`predictionengine/EntityFluidInteraction.java:81`). Entity pushes are an uncertainty allowance there
    (`PredictionEngine.java:490`), not verified to cover an ignored push. NOT playtested; the two new/changed
    mixin injections were checked against the 26.1.2 sources only, not applied in a running game.
- `movement/noweb/modes/NoWebGrounded.kt` — **Grounded**, a NoWeb mode for the GroundedGrim fork
  (2026-09-29; clone `C:\Users\caves\Music\GroundedGrim` HEAD `79b8651`, audited against it). The fork has NO
  web-specific check; webs are enforced only by Simulation vs `stuckSpeedMultiplier` x0.25/x0.05
  (`StuckSpeed.java:17`), offset >= 0.1 = immediate setback. Invariants, do NOT regress:
  - **Movement is never modified on the wire** (strictest-config doctrine: `force-stuck-speed: true` default,
    `GrimPlayer.java:281`). `CancelCollision` defaults OFF; ON is only for servers accepting un-slowed
    movement (`force-stuck-speed: false` makes Grim offer the un-slowed candidate even deep in a web,
    `PredictionEngine.java:942-946`). When off, the collision hook only RECORDS the web.
  - The core is a proactive vanilla-shaped dig: webs scanned up to `ScanAhead` (2) blocks ahead plus the
    collision callback; sword/shears only (0.125/tick = 8 ticks, `BlockBreakSpeed.java:286-312`); swing
    before START and per dig tick; sequenced packets; STOP at full 1.0 progress, NOT the server's 0.7
    (FastBreak `blockBreakBalance` charges `predictedTime - realTime`, `FastBreak.java:99-114`); 300 ms
    FINISH->START (`FastBreak.java:82-91`); one dig at a time via `MiningClaim` (WrongBreak).
  - **Dig face is geometric, never hardcoded** (LIVE `PositionBreakA.java:50-58` cancels a face the eye
    position cannot see; a web is a full cube). ABORT stays `Direction.DOWN` (PositionBreakB).
  - **Re-digging a just-stopped cell waits for the server's ack** (`BlockChangeAcks`, 2 s timeout) — Grim
    predicts air on our STOP until the ack (`CheckManagerListener.java:824-828`) and LIVE `AirLiquidBreak`
    cancels a dig into that window. The 300 ms gate alone loses to RTT > 300 ms.
  - Stand-downs (experimental checks, kept cheap): frozen while `player.isUsingItem` (MultiActionsB/E) and
    while `ModuleCrystalAura.lastActionMs` is inside the 40 ms window (PacketOrderI/MultiActionsF), mirrored
    from SpeedMine. No ABORT is sent on a freeze.
  - RotationBreak is DISABLED in this fork (`CheckManager.java:268`) — the dig files no rotations; do not add any.
  - Crits inside webs are vanilla-legal (server drains packets before the level tick, so fallDistance is
    still > 0 at attack time; `MinecraftServer.java:1033` vs `:1035`, `Player.canCriticalAttack`
    `Player.java:1018-1028`; Grim's only crit check BehaviorE exempts stuck players) — no removal is needed
    for crits. NOT playtested.
- `render/ModuleHud.kt` — **HUD**, the ONLY HUD module (2026-09-24, the user's ask: merge every HUD into
  one, cut the browser HUD and the browser part of the client, and the very first native HUD). Every look
  is a `Mode` of `HUD -> Theme`: `Mursultan` (`MursultanHud`, default), `Hellhit` (`ModuleHellhitHud`),
  `Glass` (`ModuleHud2`, alias `Hud2`), `HudTest` (`ModuleHudTest`), `Hud3` (`ModuleHud3`, alias `Shard`),
  `None`. The object names kept their `Module*` prefix to spare the mixins a rename; none of them is a
  `ClientModule` any more. Invariants, do NOT regress:
  - A look that is not selected has no running handler: `Mode.running` needs the mode selected and
    `EventManager.kt:349` skips a listener that is not running. That is the whole exclusivity mechanism -
    do not bring back a per-tick "switch the other HUD modules off" enforcer.
  - `ClientModule.onToggled` -> `ToggleableValueGroup.updateChildState` -> the active mode's `enable()` /
    `disable()`; a mode switch goes through `ModeValueGroup.setAndUpdate`. So per-look setup and teardown
    live in `enable()` / `disable()` (HudTest's media bridge, Hud3's drag/toast reset, Hud2's presence
    reset). `MursultanHud.disable()` resets its fade/drag because its render handler no longer sees the
    deselected state.
  - `override val parent get() = ModuleHud.theme` must stay a GETTER: `ModuleHud.theme`'s initializer
    instantiates the modes, so reading it from a mode's own init would read null.
  - Settings of the former modules do NOT carry over from an older config (they were saved under the old
    module names). A saved `Theme` from the old enum fails to deserialize into the mode group, is logged
    and falls back to the default - harmless.
  - `Blur` (the background blur under translucent HUD pixels, `render/engine/BlurEffectRenderer`) is a
    generic GUI effect and stays on the module.
  REMOVED the same day, do not resurrect from history without asking: the stock LiquidBounce look (native
  `WatermarkHudComponent` / `ArrayListComponent` / `TargetHudComponent` / `MinimapHudComponent`,
  `ModuleHudTheme`, `render/hud/HudStyle|HudFills|HudFillMode|HudLogo`), and the ENTIRE browser (JCEF)
  integration: `integration/backend` (MCEF), `integration/interop` (HTTP server + REST API),
  `integration/theme` (web themes, web HUD components, `HudComponentTweak` and its mixin hooks),
  `integration/screen/impl` (browser screens/overlay), the Browser ClickGUI renderer with `Scale` /
  `Snapping` / `Cache` and `ModuleClickGui.sync()`, `.client browser|integration|theme`, `src-theme/`, the
  mcef and node-gradle dependencies, `MixinGLX`, `MixinLocatorBarRenderer`, the web-only events
  (browserReady, virtualScreen, componentsUpdate, clientPlayerData/Inventory/Effect, targetChange,
  themeColorChange, clickGuiScaleChange/ValueChange, spaceSeperatedNamesChange, accountManagerMessage), the
  `WebSocketEvent` marker, `interopGson` and `@ProtocolExclude`. What survived and moved:
  `integration/screen/native/model/ActiveServerList.kt` (native multiplayer), `api/core/OkHttpProgressInterceptor.kt`
  (marketplace + DJL downloads, same contract as MCEF's), `ClientRenderPipelines.Composite` (ex-`JCEF`:
  `SMOOTH_TEXTURE` + `Blit`, used by the HUD blur, FragEffects, Saturation and the loading overlay).
  `ScreenManager` now only swaps TITLE / MULTIPLAYER / SINGLEPLAYER for the native screens;
  `InterfaceMode` is NATIVE (default; a stored `BROWSER` fails `valueOf` and migrates to it) or VANILLA.
  `MixinScreen.init()V` keeps an EMPTY injector on purpose: `MixinLevelLoadingScreen` overrides it to add
  its button, and the call only reaches that override through the inherited injection.
  Marketplace `Theme` items are kept for API deserialization but are neither listable nor subscribable,
  and old theme subscriptions are skipped by `updateAll`.
- `render/hud3/` — **Hud3**, a native HUD reproducing the "Shard" design (2026-09-21, the user's
  ask: port it 1:1 and fix the crooked circle in potions). NOT measured off a screenshot — read out
  of the Figma file over the REST API (`peW5mqUNF4v3xOKGZXRtt3`, page `1:4`, frame `1:117`,
  components on page `1:2`), so every size/colour/radius/gap in the package cites the node id it
  came from. Architecture copies `render/hudtest/` (native-pixel space, `WatermarkFont` atlas text,
  `WatermarkGlass` region blur, anchor+offset draggable panels). Seven panels: Watermark `<3:489>`,
  Keybinds `<3:486>`, Potions `<3:488>`, TargetHud `<3:802>`, Armor `<3:485>`, ItemList `<3:487>`,
  Notifications `<3:179>`. Invariants, do NOT regress:
  - **Its own `Hud3Input`, not HudTest's.** Drag state is per-HUD; one shared object would let a
    grab in one module move the other's panel while both are enabled.
  - **The pill rim is `strokeAlign = OUTSIDE`, 1.5 px.** A node's stated size is its FILL box and
    the rim grows outside it, so `Hud3Panel.frame` keeps `RIM` of slack against the screen edges or
    a cornered panel loses its rim and reads as a flat edge.
  - **Both rings are real angular arcs (`Hud3Shapes.arc`) and that is the ONE deliberate departure
    from the file.** The design fakes them with `cornerRadius = 180` plus, for the potion timer, a
    LINEAR gradient on the stroke (`<9:830>`); a linear gradient cuts a circle along a straight
    chord, cannot express a countdown, and the second row's ring (`<9:825>`) has no gradient at all.
    Arc angles are generated high-to-low to match this repo's contour winding convention — emitting
    them the other way inverts every quad.
  - **Progress is a hard cut, not a fade.** The design's bars and ring put both gradient stops at
    the SAME position (`<3:434>`, `<3:163>`), i.e. a step to 15 % alpha. `progressBar`/`progressRing`
    draw two solids; do not "restore" a gradient.
  - Bottom-edge bars are inset by `bottomEdgeInset` because the design clips them with
    `clipsContent`; without it the first bar pokes out of the rounded corner.
  - Armour order is boots→helmet, read off the design's own render, NOT the vanilla HUD's order; the
    neutral grey bar means UNDAMAGED (that reading is what reconciles all four samples: 100 % grey,
    65 % green, 45 % amber, 25 % red).
  - Item sprites go through `HudTestItemIcons.draw` in GUI space (outside the pose); the skin head
    is local-space INSIDE the pose. Mixing the two up silently misplaces them.
  - **`Scale` defaults to 2, not 1** (2026-09-21, reported as "too small" and "the font feels low
    quality" - one cause, two symptoms). A local unit is a PHYSICAL pixel and the design was drawn
    in physical pixels on a 1080p frame, so at 1 the HUD is half the size of everything the game
    draws at gui scale 2 and its 10 px labels bake a 10 px glyph atlas. Offsets, gaps and paddings
    are all in the same units, so the layout scales as a whole. Do not "restore" 1.
  - Text is `SFProDisplay-Medium.otf`, which the design asks for and the repo bundles. There is NO
    SF Pro Regular in the tree, so the design's Regular runs are drawn in Medium — the only
    typographic deviation. `Typeface` (module-level, SfPro default) can switch the whole HUD to
    Inter: SF Pro **Display** is cut for large sizes and rasterizes softer at 8-10 px than Inter,
    which was drawn for interface text that size. Inter reuses `WatermarkFont.TEXT` so no second
    atlas set is baked. `Hud3Theme.font` is a GETTER for that reason - never cache it.
  - **Stacks sort by the width of the LEFT pill, descending, and sort AFTER `take(maxRows)`**
    (2026-09-21, the user asked to order effects and binds "by length"; it is also the order the
    design's own stacks are in). Two traps: sorting by the whole row's width reshuffles the stack
    mid-countdown, because a timer loses a character at every digit boundary (`10:00` -> `9:59`);
    and sorting before the `take` turns a display request into a filter, silently dropping
    short-named modules and the effect about to expire. Selection stays name order for binds and
    infinite-then-longest-remaining for effects.
  - `Keybinds.OnlyEnabled` defaults ON (2026-09-21, the user's request).
  - Icons are the design's OWN vectors exported at 4x and flattened to white alpha masks in
    `textures/hud3/`, tinted by vertex colour (same convention as the ClickGUI rail). `command.png`
    had its baked 45 % opacity renormalized out, so the opacity lives in the tint like every other.
  - Registered in `MixinGui#extractEffects`' existing OR chain so the Potions panel does not double
    up with the vanilla corner effect icons.
  - HONEST SCOPE: **NOT playtested.** Layout arithmetic was verified headlessly — fed the design's
    own text widths, every panel formula reproduces the file's own card size exactly (24/24) — and
    SF Pro was verified to load through `Font.createFont(TRUETYPE_FONT)`. Nothing about the on-screen
    result is verified. Perf note: the design is made of many small pills, so a panel costs ~2 GUI
    elements per pill rather than one batch per card the way HudTest does.
  - **Motion (2026-09-24, the user's ask: animations everywhere).** Everything that moves is ours - the
    design is a still frame - and lives in `Hud3Motion.kt`: `Hud3Smooth` (half-life follow, first update
    lands exactly), `Hud3Presence` (keyed list with enter/exit; a leaving entry keeps its place right
    after its old present predecessor; `layout()` weights each entry's pitch by its presence, so stacks
    close smoothly), `Hud3Pulse`. The `Animations` group (toggle + `Speed`) scales every duration; OFF
    makes every transition land on its first frame. Invariants, do NOT regress:
    - A panel keeps drawing its LAST content while it fades out. Before this, `render` returned the
      moment there was nothing new, so the fade-out was computed and never drawn. A switched-off panel
      is still rendered until `isOnScreen` goes false - which is why every panel ANDs `enabled` into
      what it considers present.
    - Item sprites in motion go through `HudTestItemIcons.drawTransient` (vanilla item rendering at any
      scale); only an item at rest uses the crisp resampled `draw`. That path bakes one texture per
      DEVICE SIZE, so a pop that changes size every frame would bake a texture every frame and churn the
      96-entry cache. `hud3Item` makes the choice; do not call `draw` with an animated size.
    - Text is never scaled (every distinct px bakes a glyph atlas). Flashes are colour lerps.
    - Card positions follow their placement (`POSITION_HALF_LIFE_MS`), so a drag trails the cursor by a
      few frames; a panel not drawn for `BOUNDS_TTL_MS` resets and lands where it is placed.
    - Expiring effects blink from 200 ticks, vanilla's own threshold (`Gui.java:506`, `endsWithin(200)`).
    - The target ring has a damage trail (holds 320 ms, then drains) and refills from empty on a target
      switch; the avatar's red flash follows `hurtTime / hurtDuration` (`LivingEntity.java:461-462`,
      `:1843-1844`).
    - NOT playtested.
- `render/waypoints/` — **Waypoints**, **Minimap** and the world map screen (2026-09-27, the user's ask:
  mark places for yourself, a minimap that collects the world map where waypoints are placed and zoomed,
  everything bound to the server IP, the waypoint look left to us). ORIGINAL code. The upstream
  `MinimapHudComponent` / `ChunkRenderer` deleted in `f158f85` were read (`git show f158f85^:...`) for the
  colour-capture idea only; nothing is shared. Sends NO packets, so there is no Grim surface.
  Layout: `MapIdentity` (server/dimension keys, folders), `WaypointStore` (per-server JSON), `WaypointGraphics`
  (the shared Glass look, marker, arrow, capsule), `ModuleWorldMap` (bind M opens the map; `Collect` is the capture
  switch), `ModuleMinimap` (HUD only), `WorldMapScreen`, `ModuleWaypoints` (beam, ground ring, screen marker,
  `AddKey`), `map/` (`MapTile`, `MapSampler`, `BlockTopColors`, `WorldMapStore`, `ConvexClip`), plus
  `command/commands/ingame/CommandWaypoint` (`.waypoint`, alias `.wp`).
  Storage: `<LiquidBounce>/worldmap/<serverKey>/waypoints.json` and `.../map/<dimension>/<rx>.<rz>.png|.hgt`
  (512x512 blocks per tile, the vanilla region grid; `.hgt` is a gzip of 512x512 shorts). Invariants, do NOT regress:
  - **The key is the typed address, normalised** (case, trailing dot, `:25565` stripped); singleplayer is keyed by
    its save folder. KNOWN LIMIT, told to the user: two addresses of one server are two stores, and a proxy network
    (lobby + survival behind one address) shares ONE store per dimension id, so its worlds draw over each other.
  - **Capture is main-thread and budgeted** (`CAPTURE_BUDGET_NANOS`, 2.5 ms per tick) from `ChunkLoadEvent`,
    `BlockChangeEvent` and `ChunkDeltaUpdateEvent` - all fired on the main thread (`handleLevelChunkWithLight` et al.
    re-dispatch through `PacketUtils.ensureRunningOnSameThread` before their RETURN). Deliberately NOT
    `ChunkScanner`, which samples chunks on coroutine threads while the main thread writes them. A block change
    re-samples its column plus the south and east neighbours (their slope shading reads it); a change more than 24
    blocks under the captured surface is ignored (mining does not churn the map). Only while WorldMap's `Collect`
    is on (default ON).
  - **Colours are the world seen from above, not vanilla's map palette** (`BlockTopColors`, 2026-09-27, the user's
    ask for a real top view): the average of the block's TOP-face texture (UP quads of its baked model
    `ModelManager.getBlockStateModelSet().get(state)`, else an unculled up-facing quad, else any unculled quad, else
    the particle sprite), multiplied by the biome tint when the quad has a `tintIndex`. The texture is read from the
    resource manager by the sprite's name (`textures/<path>.png`), so resource packs apply and no private field or
    atlas readback is needed; pixels under 10 % alpha are skipped. Cached per state and per sprite, cleared on
    `ResourceReloadEvent`. Which blocks are looked THROUGH still follows vanilla (`MapColor.NONE`: air, glass,
    barriers), so a glass roof shows what is under it. Water and lava use their still textures; water's tint is
    `BiomeColors.getAverageWaterColor`, NOT the block tint source - `BlockColors` registers only
    `waterParticles()` for `Blocks.WATER` (`BlockColors.java:42`), whose `colorInWorld` is -1.
  - **In 26.1.2 a GUI element with `bounds == null` is silently DROPPED** (`GuiRenderState.findAppropriateNode`
    returns false) and elements are layered by their bounds, so every custom element here passes real bounds. And
    `drawCustomElement`'s vertex callback runs DEFERRED, at GUI render time (`LambdaSimpleGuiElementRenderState.
    buildVertices`), so it may only capture data nobody mutates afterwards - the minimap bakes each clipped piece into
    fresh arrays because `ConvexClip` reuses its buffers for the next tile. Both mistakes were in the first draft.
  - **GUI pipelines cull back faces** (`RenderPipeline` builder default `cull = true`): quads go in vanilla `fill`'s
    screen order (a diamond as top, left, bottom, right) and `ModuleMinimap.fanAsQuads` fixes each triangle's winding.
  - **Round, rotating minimap without an offscreen target:** the view polygon is mapped to world space, clipped
    against every tile it overlaps (`ConvexClip`, Sutherland-Hodgman, convex in = convex out) and each piece is drawn
    as a fan with that tile's texture. `ConvexClipTest` checks the pieces tile the view exactly at any rotation.
  - **Tiles are never closed while a frame is drawing**, because an element extracted earlier in the frame may
    reference the texture: the tick handler (eviction, capture off, context switch), shutdown, and a context switch
    noticed at the START of a map draw (`beginFrame`, before that frame draws any tile) are the only closers.
    Eviction is LRU above 64 resident tiles and never touches a tile used in the last 3 s.
  - **The IO thread only ever sees `TileSnapshot` copies** (a live tile can be evicted and freed while its save is
    queued; writing a freed native image is a crash). One daemon thread, FIFO, so a load queued after a save of the
    same tile reads the saved file. Writes go to `.tmp` and are moved into place.
  - **Far waypoints:** the beam is scaled towards the eye beyond `min(160, safeDistance)` blocks (same picture), and
    screen markers are projected from at most `min(128, safeDistance)` because `WorldToScreen.calculateScreenPos`
    returns null past the far plane. `safeDistance` is 80 % of the far plane's lower bound
    (`Camera.update`: `max(renderDistance * 16 * 4, cloudRange * 16)`), ~102 blocks at render distance 2.
  - The look ("beacon pin"): additive beam through terrain (thin core in a wide halo, fading upwards, breathing,
    fading out when stood in), a faceted diamond marker with a name + distance capsule, coordinates added only while
    the marker is near the crosshair, a turning dashed ground ring within 48 blocks (depth-tested). The minimap and
    the world map use the same diamond.
  - **Panels are in the Glass HUD's language** (`WaypointGraphics`, the user's ask 2026-09-27: rounder, like HUD
    Glass): its Classic palette hard-coded (body gradient 150/175 alpha, lavender accent, info grey), a soft shadow,
    a hairline rim, and the REAL blur of `WatermarkGlass` under HUD panels. Radii are larger than Glass's 4.5 on
    purpose: panels 12, controls 8, text in capsules, the square minimap at 22 % of its side.
    **`WaypointGraphics.glass` marks the frame's blur only with no screen open or the chat open**
    (`ChatScreen.extractBackground` is empty): while a capture is pending `MixinGuiRenderState` swallows every other
    `blurBeforeThisStratum`, so a mark under a menu would take the menu's own background blur away. It skips the mark
    when a capture is already pending and catches vanilla's "Can only blur once per frame" (someone marked without
    `WatermarkGlass`) - never call `WatermarkGlass.request` without a mark that succeeded, or `requested` sticks and
    swallows blur marks for good. When a capture is already pending it takes `WatermarkGlass.pendingSetup()` instead
    of calling `request` (which overwrites strength and passes, last caller wins - it used to drop Hud3/HudTest/
    Hellhit to one pass), and it marks nothing once `WatermarkGlass.isAvailable` is false (a mark without a capture
    makes vanilla blur the whole frame in place). KNOWN, PRE-EXISTING, NOT FIXED: `WatermarkGlass` forces its radius
    through the menu-blur option after `GameRenderer` has already filled the `MenuBlurRadius` global for the frame,
    and `blur.json` passes `Radius 0`, so every native HUD's glass actually uses the player's vanilla "Menu
    Background Blur" setting (found by the 2026-09-27 audit, affects Hud2/Hud3/HudTest/Hellhit alike). The world map screen draws the same panels SOLID: with the Glass HUD active the
    frame's capture is taken before the HUD stratum, i.e. of the world, not of the map under the panels.
  - **The map opens through `mc.schedule`, never directly.** `KeyboardKeyEvent` (which also dispatches module
    binds) fires BEFORE `KeyboardHandler.keyPress` reads `minecraft.screen` (`MixinKeyboardHandler`, GETFIELD
    ordinal 0, vanilla `KeyboardHandler.java:450`), so a screen set there receives the same press in `keyPressed` -
    and the same key closes the map. `.waypoint map` defers the same way.
  - **`ModuleWorldMap` is a push-button like ClickGUI** (`disableActivation = true`, default bind M), so the key
    works whatever else is on. It opens from BOTH `onEnabled` and `onDisabled`, skipping `AutoConfig.loadingNow`:
    a click outside a world leaves the raw state on (`ClientModule.onToggled` returns before `disableActivation`
    applies), and then the first in-world press is an `onDisabled`; the loading guard keeps ModuleManager's
    first-join replay and the Unhook sweep from opening it, and `HideAppearance.isDestructed` keeps self-destruct
    (which disables modules outside a config load) from opening it. The scheduled open only replaces nothing or a
    ClickGUI screen: a container the server opened in that frame would otherwise stay open server-side.
    **The bind closes the map only after its key was RELEASED since opening** (`WorldMapScreen.closeArmed`):
    vanilla forwards key REPEAT (action 2) to `Screen.keyPressed`, so holding M past the OS repeat delay opened and
    closed it; the close also checks the bind's modifiers. The first playtest had an EMPTY map because capture used
    to follow the HUD minimap, which was off - capture and the key are WorldMap's now, never the minimap's.
  - **Capture follows WorldMap's `Collect`, polled every tick** (`WorldMapStore.collectWhile`), not pushed from
    callbacks: `ClientModule.onToggled` returns early outside a world. Not capturing, every tile idle for 3 s is
    released.
  - **The on-disk tile index is per FOLDER for the whole session** (`diskIndex`), fed by queued saves: a folder
    listing on re-entering a dimension would miss a tile whose first save is still queued, and a blank tile would
    overwrite it.
  - **The world map cannot zoom out past what 36 tiles cover** (`WorldMapScreen.minZoom`, floor 0.25 at 960x540
    GUI units): every tile in view is held in memory and there is no downsampled level, so a big GUI area would
    otherwise pin ~170 tiles (~425 MB). If zooming further out is ever wanted, the answer is a LOD layer, not a
    bigger cap.
  - **`WorldMapScreen` implements `ClickGuiSearchAware`**: InventoryMove passes movement keys in any non-container
    screen (`ModuleInventoryMove.shouldHandleInputs`), so without it typing a waypoint name walks the player.
  - A `waypoints.json` that fails to parse is renamed to `waypoints.json.broken-<ms>` before anything can save
    over it.
  - Known, accepted: zoomed far out the map shimmers (single mip level) and LINEAR sampling darkens exploration
    edges slightly (alpha-0 black texels); dragging `Waypoints -> Scale` bakes a glyph atlas per whole GUI px.
  - NOT playtested. Verified: compile, 9 unit tests (`ConvexClipTest`, `MapIdentityTest`), one refutation audit
    by a fresh agent (its findings above are fixed; the rest of its checks confirmed the GUI-layering, winding,
    threading and minimap-math invariants listed here).
- `render/ModuleCrystalView.kt` — crystal model tweaks (size / yTranslate /
  spin / bounce; author ccetl), added with the crystal rework.
- `world/speedmine/ModuleSpeedMine.kt` — HellHit SpeedMine port. Cross-module dig arbitration
  goes through `MiningClaim` (it takes the claim before its first START, since 2026-09-07); it
  additionally defers its own packets while `ModuleCrystalAura.lastActionMs` says the aura acted
  inside this tick window (`PacketOrderI`).
- `combat/placement/` — the catlean block placer (`CatleanBlockPlacer`, `CatleanPlaceModule`,
  `PlaceModes`) plus `CrystalTickGuard`, relocated out of the deleted `combat/crystal/` on
  2026-09-07 because `ModuleAutoWeb` extends `CatleanPlaceModule`. Generic placement machinery,
  no crystal coupling.
- `combat/criticals/modes/CriticalsCustom.kt` — **Criticals `Mode = Custom`** (2026-10-26, the user's
  ask), a port of catlean's `Criticals` CUSTOM mode. Five `Packet1..5` toggleable groups, each with
  `Ground` / `Full` / `Offset` (0.0..1.5), sent in order on every attack. Source: the readable
  decompile `catlean-src-clean/su/catlean/Criticals.java` (`:342-368` the CUSTOM branch, `:398-424`
  the sender, `:672-845` the defaults) - NOT the partially-deobfuscated
  `catlean/catlean/src/su/catlean/Criticals.java` the user pointed at, which is opaque (encrypted
  int keys, opaque predicates); the hand-written Kotlin rebuild `catlean/src/main/kotlin/su/catlean/
  Criticals.kt` is readable but has at least one WRONG default (offset-2 is 0.0625, not 0.0), so the
  decompile is the authority. Invariants, do NOT regress:
  - `Full` builds `PosRot` with `player.lastRotation` (`yRotLast/xRotLast`, the rotation already on
    the wire), like catlean's own `LocalPlayerAccessor` read. NEVER `player.yRot`: the silent
    rotation only rewrites `getYRot()` INSIDE `sendPosition`/`tick`
    (`MixinLocalPlayer.hookSilentRotationYaw`), so a hand-built packet carrying the camera yaw hands
    the server the camera rotation and undoes the silent aim. This is also why the mode does not go
    through `MovePacketType.FULL`, which does exactly that.
  - The trigger stays this client's: `AttackEntityEvent` + `canDoCriticalHit(ignoreOnGround = true,
    ignoreSprinting)`, not catlean's outgoing-`ServerboundAttackPacket` hook. catlean's own gate (on
    ground or flying, not in lava, not underwater) is a subset of `allowsCriticalHit`, except that
    ours also refuses while `abilities.flying` - keep ours.
  - NOT Grim-audited and NOT playtested: the offsets are the user's to choose, so there is nothing
    fixed to audit; the audited presets are `CriticalsPacket`'s.
- `utils/item/CreativeSlotGuard.kt` — **the creative-slot kick** (2026-10-26, the user's report: in
  singleplayer, taking a head out of creative kicks you from the world). The `minecraft:profile`
  component's codec validates the profile's player name on DECODE (`StringUtil.isValidPlayerName`), so a
  decorative head named `Candy Bowl` / `Packed Ice Block` encodes here and throws there:
  `DecoderException: Failed to decode packet 'serverbound/minecraft:set_creative_mode_slot'` ->
  `Player name contained disallowed characters` (`ItemStack$3.decode`), and the server drops the
  connection. Seen twice in one session's log. Invariants:
  - The guard sits on the OUTGOING packet, not on a caller: vanilla's own creative screen sends it, and
    so do `InventoryAction.Creative` and `setInventoryItemCreative`.
  - It REPAIRS rather than drops: the profile is rebuilt with the same id and the same properties (the
    `textures` property draws the head) under a sanitized name, and the repaired packet goes out through
    `sendPacketSilently`, which fires no `PacketEvent` - that is what keeps the guard from re-entering
    itself. Dropping the update instead would desync the slot.
  - KNOWN LIMITS: `ResolvableProfile`'s only public factories are `createResolved` / `createUnresolved`,
    so a profile's skin PATCH is lost (a head textured through the patch comes back plain), and a
    profile with no id is rebuilt unresolved under the sanitized name. Both beat the kick.
  - NOT the cause of the user's "AutoSwap on -> kick" correlation: `InventoryAction.Click.performSwap`
    sends a container click, not a creative slot update, and no path was found by which AutoSwap itself
    sends one. The guard covers the packet whoever sends it. NOT playtested.
- `combat/ModuleAutoSwap.kt` — off-hand cycler + radial wheel (tap = cycle, hold/toggle = wheel).
  **`SeparateSpheres` (default ON, 2026-10-26, the user's ask: "чтобы в свапе колесом можно было
  переключаться между сферами, чтобы сразу все высвечивались")** expands `SwapItem.SPHERE` - whose
  predicate is any `Items.PLAYER_HEAD`, i.e. a server's "сфера" - into ONE CELL PER DISTINCT HEAD
  (item + components), named after the head, so every sphere is on the wheel at once instead of one
  cell that always picked the first head found. Invariants, do NOT regress:
  - The cycle is anchored by ENTRY IDENTITY (`cycleKey`), not by an index: the entry list is rebuilt
    from the inventory on every call, so a stored index silently points at a different item once a
    head is used up or picked up. Same for the wheel's pick (`selectedKey`): the index is computed
    in a render frame and consumed on the bind's RELEASE, with the list rebuilt in between.
  - A `SwapItem` with nothing in the inventory still gets its dimmed placeholder cell, so the ring
    keeps its shape; spheres are the exception - with no head anywhere the single plain "Sphere"
    cell is drawn instead.
  - The ring radius grows with the cell count (`max(46, n * cellSize * 1.35 / 2pi)`) - at the fixed
    46 a dozen cells overlapped. `MAX_SPHERE_CELLS` (12) bounds the expansion, head labels are cut
    at `MAX_LABEL_CHARS` (24) because the names come from the server.
  - The wheel's "3+ items" threshold counts ENTRIES now, so three spheres alone open it.
  - **The wheel is clickable** (2026-10-26, the user: "нужно чтобы при свапе колесом анлочился курсор и
    можно было мышкой выбрать и нажать на айтем"). `openWheel` always released the cursor, but with no
    screen open the FIRST press vanilla sees goes to `grabMouse()` (`MouseHandler.onButton`, read from
    `minecraft-merged-deobf-26.1.2.jar`), which locked it straight back - so the wheel could be aimed
    at and never clicked. Now LEFT picks the sector under the cursor, RIGHT cancels, and:
    - `closeWheel` grabs the cursor itself, so vanilla's grab right after is a no-op;
    - the click vanilla queues is taken back at the next tick HEAD (`KeyMapping.clickCount`, the
      `ModuleSpearLunge` idiom) - our handler runs from INSIDE `onButton`, before the count is raised,
      so it cannot be prevented, only undone - otherwise picking a totem also swings;
    - the press clears `holding`, or a HOLD-mode release afterwards would `cycleNext()` on top of the
      pick;
    - the trigger key itself never counts as a wheel click - `onTrigger` owns it;
    - while the wheel is up a foreign grab is undone every frame (`isMouseGrabbed` -> `releaseMouse`),
      which also re-centres the cursor into the dead zone.
  - Sends no packet of its own (every swap still goes through `ScheduleInventoryActionEvent` +
    `InventoryAction.Click.performSwap`), so there is no new Grim surface. NOT playtested.
- `misc/ModuleCrystalOptimizer.kt` — use-key crystal spammer (wyvern port),
  Raytrace/Rotate explode modes.
- `misc/ModuleUnhook.kt` + `command/commands/client/CommandUnhook.kt` — "Unhook"
  panic button, port of the SystemDLC PanicModule (studied 2026-08-26). One
  keypress (or `.unhook`) closes the current screen and disables EVERY running
  module (ModuleHud included -> arraylist/notifications gone). HONEST SCOPE:
  Fabric mixins are applied at class-load and CANNOT be unloaded at runtime, so
  unlike SystemDLC (which unloads a native JVMTI agent) this only makes hooks
  inert by disabling their modules. Sends NO packets -> no Grim surface. Invariants:
  the disable sweep runs inside `AutoConfig.withLoading` (config NOT persisted, so
  the setup returns next launch; per-module toggle notifications suppressed);
  `panic()` is the shared entry (module tickHandler self-disables -> push-button;
  command calls it directly); ClickGUI stays reopenable (`running==true` always).
  Related to the client destruct/wipe feature but softer (in-memory vanish, no
  disk wipe).
- `render/esp/` + `render/hawenesp/` — shader ESP (hawen port), see the
  Shader ESP section.
- `render/ModuleBurningHands.kt` + `render/burninghands/BurningHandsRenderer.kt` —
  Burning Hands fire glow/trail around first-person hands (exploit-client port,
  render-only). Capture: `MixinGameRendererBurningHands` submits a second, identical
  `renderHandsWithItems` copy (same PoseStack — push/pop guarded — same light) into a
  PRIVATE `SubmitNodeStorage`; after vanilla's own hand rasterization (`endBatch` in
  `renderLevel` — verified the ONLY such invoke in that method), a private
  `FeatureRenderDispatcher` + `endBatch` flushes it into an offscreen mask FBO via
  `RenderSystem.outputColorTextureOverride`, then glow→trail→composite passes.
  **The flush MUST set the model-view to the camera rotation matrix R** (=
  `CameraRenderState.viewRotationMatrix`, the `positionMatrix` parameter of
  `renderItemInHand`, stashed by the mixin at the submit). `renderItemInHand` bakes R⁻¹
  into the hand pose and compensates with `modelViewStack.mul(R)`, but pops that push
  right after the submit — long before anything rasterizes. Without R the captured hands
  keep an uncancelled R⁻¹ and sit locked to the WORLD, coinciding with the real hands
  only at yaw 180 / pitch 0 (where R is the identity). Do NOT try to read the model-view
  at the rasterization point instead: it is the identity there (measured in game), which
  is why the earlier stash-at-`endBatch` approach was a silent no-op.
  Shaders: `shaders/burninghands/`, pipelines `BurnGlow/BurnTrail/BurnComposite`,
  UBO `BURN_DATA`. Perf-critical invariants: the glow pass is the expensive one (6 rings
  x 12 dirs = 72 samples/px), so it early-outs on a 16-tap probe over its own maximum
  reach (`GlowParams.x * 1.12`) — widen the probe, not the reach, if the halo ever shows
  holes. Glow and trail render at `RenderScale` (default 0.5) of the window while
  `Screen.xy` in the UBO stays the FULL window size, so every shader offset remains in
  screen pixels and the effect keeps its on-screen size at any scale; the mask stays
  full-res for the sharp silhouette gate. `FireParams.w` carries the FireColor ALPHA
  (overall opacity, applied ONLY in the composite — the trail target accumulates across
  frames and would compound it); Strength is read from `GlowParams.y`.
- `render/clickgui/LeetClickGuiScreen.kt` — native ClickGUI, single ~2400-line
  file, see its section (encoding gotchas inside).
- `movement/inventorymove/InventoryMoveGuiBypass.kt` — syntax GuiMove port;
  movement in GUI only via `shouldHandleInputs` (MixinKeyboardInput).
- Echo-module ports in `combat/` — see commit "faithfully re-port the seven
  echo modules" for the list.
- `utils/aiming/features/processors/anglesmooth/impl/IntpolAngleSmooth.kt` — "Intpol",
  the guide-style easing-interpolation rotation (2026-08-26, built to the user's
  pattern/easing guide): 30 easings.net curves as a nested `Curve` enum (springy
  In-variants off by default via `multiEnumChoice`), a fresh curve+duration per
  swing (never the same curve twice in a row), step = span × ΔE with the span
  re-estimated from the live error (over-unity curves Back/Elastic/Bounce Out
  genuinely overshoot), speed coupled to distance band (close slow / mid fast /
  far slow), own horizontal speed, fall distance and attack recharge; error-stop
  hold below `ErrorStop`, guide's 60/24 delta clamp, keep-old-angle under
  `KeepSameAngle`, rare twitches + per-swing-re-rolled sine tremor, and a
  smoothly wandering aim point scaled to the target's angular size
  (`prefersFannedAimPoint = false` — the float replaces the fan). Carries the
  same first-guide fixes as the since-removed Multipoint (same session):
  visuomotor delay line + interception lead
  (`Reaction`/`Lead`/`LeadWindow`) and a refractory-guarded
  turn-reaction freeze on ≥`TurnThreshold` aim-point jumps — both OFF on the
  converging path. HONEST SCOPE
  (in the KDoc): heuristic-anticheat mode, off the GCD grid, statistics not
  fitted to a recording — for Grim/Shard-class targets Clone remains the
  answer (an older Shard mode was removed 2026-09-02, Multipoint 2026-09-13). Invariant: on
  `entity == null` every feature is off and the hand-back converges
  monotonically with a minimum step (release depends on it).
  Registered in `RotationsValueGroup` after Acceleration.
- `anglesmooth/impl/ShardAngleSmooth.kt` — "Shard", javelin's
  `tech.javelin.features.module.impl.combat.attackaura.rotation.ShardRotation`, supplied inline by
  the user (Yarn) and ported as written (2026-09-25). It REPLACED `SlothAngleSmooth` (deleted in the
  same change; its Volon-family ancestors are gone too) and carries `Sloth` as an ALIAS, so a saved
  config that had Sloth selected loads onto Shard instead of falling back to the first mode. Not
  related to the older "Shard" mode deleted 2026-09-02 beyond the name.
  Mechanism, once per TICK: per axis the step is `wrapDegrees(target + jitter - current) * adj`,
  `adj` starting at 0.5; while the look ray (along `currentRotation`) hits the target's box shrunk 5 %
  a horizontal side and 10 % off the top (GROWN by 25 % / 50 % while the target glides), or the eyes
  are inside it, `adj -= U(0.005, 0.025)`, otherwise `+= U(0.001, 0.012)` (`0.008` / `0.01` while WE
  glide). Aim point noise ±0.04 / ±0.03 / ±0.04 blocks, angular jitter ±0.35°, and every 6th attack a
  two-tick flick of ±10–22° yaw / ±8° pitch. The step is truncated toward zero onto `RotationUtil.gcd`
  (the source's `x -= (x - last) % gcd`).
  Per tick, not per frame, is INFERRED (javelin's aura was not supplied): `flickTicks` decrements once
  per call and the source ends in a `RotationHandler.update(..., 1, 1, ...)` that must be renewed
  every call; a 0.5 fraction at frame rate would be a snap. Do not add Sloth-style sub-steps without
  evidence.
  The source's six helpers were NOT available (not in `Stal_DLC_1.21.11_Fabric`, which the user
  pointed at, nor anywhere else on the machine - searched 2026-09-25). Substitutes, all named in the
  KDoc: `MultipointUtils.getNearestPoint` -> the caller's `targetRotation` (KillAura's `PointTracker`,
  default `Optimal` = the box point nearest the eyes), recovered as a world point by clipping its ray
  against the pick box so the point noise can be applied; `PredictUtils.realPredict` -> the gliding
  target's last delta x `ElytraPredict.Ticks` from its box centre (Sloth's substitute);
  `AuraTransition` -> dropped (identity), with a full state reset on a target switch or a gap in the
  requests instead (GUESS); `MouseGCD` -> `RotationUtil.gcd`; `RotationHandler.update(rot, 360, 360,
  40, 35, ...)` -> `RotationManager`, reading 40 / 35 as the yaw / pitch return speed of the
  hand-back (GUESS from the argument order). `onAttack()` is an outgoing `ServerboundAttackPacket`
  on the tracked entity, read at `FINAL_DECISION + 1`: after anything that drops an attack, but
  BEFORE `BlinkManager` (`FINAL_DECISION`) cancels-and-queues it - its queue is flushed through
  `sendPacketSilently`, which fires no `PacketEvent`, so reading later (the first cut read at
  `READ_FINAL_STATE`) never saw an attack held by Blink/FakeLag. After a reset the step starts from
  `currentRotation`, not the camera as the source's does: in a silent rotation that re-read would be
  one packet jumping the whole camera-to-wire distance.
  Pitch fix, keep it: the jittered target pitch is clamped to ±90 AND the pitch difference is not
  `wrapDegrees`'d. The source does neither; `wrapDegrees(180) = -180` (`Mth.java:217-218`) makes its
  step from -90 towards +90 land at `-90 - 180·adj`, a pitch vanilla never holds (`Entity.java:499`).
  Known, documented: the elytra chase modules (`ModuleSpearElytraTarget`, `WingDriver`) pass the
  target entity with a STEERING rotation, and with `ElytraPredict` on and the target gliding that
  rotation is replaced by a lead aim at the target - the same as Sloth's `Predict` did.
  Invariants, do NOT regress: output is whole mouse counts, `isNormalized = true`,
  `suppressesPostProcessors` (Fail/ShortStop/Jitter would each push
  it off the grid); with `entity == null` the mode does NOT run this algorithm - a fraction of the
  residual never lands exactly and `RotationManager` releases the override only once it does - so
  that path is a linear grid-truncated return with a guaranteed one-count step.
  HONEST SCOPE: NOT playtested, NOT Grim-audited beyond "grid-truncated deltas, pitch within ±90, no
  packets of its own". Uniform noise and a fixed flick period are a heuristic-anticheat mode, not a
  Grim/Shard-AI answer. The flick is a real look-away: KillAura only swings when the rotation hits,
  so ~2 ticks of attacks are skipped every 6 hits, as in the source.
- `anglesmooth/impl/Test2AngleSmooth.kt` + `Test2AimMath.kt` — "Test2" (2026-10-07), an original
  experimental Kalman + gradient Perlin mode. Registered for combat configs only; not a replacement
  for Test/Shard. Invariants:
  - Filters the CALLER's target angle, never re-picks a point or adds entity-position prediction.
    Two constant-velocity [angle, angular velocity] Kalman filters; yaw innovations wrap, pitch
    innovations do not. Joseph covariance update; innovation over 45 degrees resets the filter.
    Filter/lead offset is capped at 3 degrees relative to the current observation.
  - Seeded 1-D GRADIENT Perlin with quintic fade, independent yaw/pitch permutations; deterministic
    and periodic at 256 cells, not a claim of non-repeating or human aim. Noise gain decreases
    smoothly with acquisition error (half gain at 30 degrees), without a hard cutoff. Kalman and
    Perlin are separate nested toggleable groups.
  - Filter/noise/controller advance once per player tick; identity changes restart filter/noise,
    but preserve motor momentum. Skipped/backwards ticks and disable discard all state.
    calculateTicks is a PURE stationary,
    noise-free estimate with four padding ticks (one tick if already within 0.5 degrees on both
    axes, preserving KillAura ON_TICK's early return), not a guarantee for a moving target.
  - Test2AxisController uses smooth tanh saturation for desired velocity and acceleration;
    reversals brake rather than zero momentum. Overshoot is possible. Steps are quantized INSIDE
    speed/acceleration bounds, carrying sub-count motion forward to avoid stalls; minimum
    effective acceleration is one emitted count. At large continuous yaw, the count quantum is
    coarsened toward one Float ULP while retaining at least one count within the speed bound.
    If Float ULP exceeds configured speed, no representable bounded step exists; convergence
    is not guaranteed at that precision ceiling.
    Float rounding,
    a physical pitch-boundary clamp and the final sub-half-count return residual are exceptions.
    Numeric settings are sanitized to GUI ranges. Fail/ShortStop/Jitter are suppressed.
    RotationsValueGroup hides the three ignored groups with a mode-change hook and an initial
    sync; this applies to every suppressing mode and preserves the groups' saved settings.
  - No entity: same configured motor and momentum, without filter/noise. preservesResetSteps
    prevents RotationManager replacing its step with a ResetThreshold snap; that setting is hidden
    with initial sync and a mode-change hook. Return lands exactly only below half a count with
    bounded braking (half an effective count after Float resolution coarsening). Pitch is
    unwrapped and clamped to +/-90; yaw is continuous. Sends no direct
    packets; keeps action ray/range and use-item gates.
  - requiresLiveTick rejects .clone evalmode: that command advances the SAME config instance on
    Dispatchers.Default, whereas this mode caches the live player's tick. Do not misuse
    derivesTargetFromEntity for that restriction: Test2 accepts the caller's angle.
  - Pure regression tests: Test2AimMathTest (tracking error, wrap/poles, noise continuity, state
    reset/idempotence, step bounds, exact return, timing isolation). NOT playtested; no anticheat
    verdict. Source checks describe bounds, not a guarantee of Grim compatibility.
- `utils/aiming/point/features/PointProcessorKalmanPerlin.kt` + `KalmanPerlinPointMotion.kt` —
  optional `AimPoint > KalmanPerlin` (2026-10-07). OFF by default, independent of Test2's angle
  smoother; nested Kalman and Perlin groups can be toggled separately. Invariants:
  - Filters proportions inside the CURRENT processor input box, not old world coordinates or
    entity motion. Reuses Test2's non-circular Kalman axes in percent coordinates (0..100):
    variance is percent^2, the 45-unit innovation reset is 45% of a side. Filter lag is capped
    relative to the incoming point; the edge-margin clamp can move it farther near a face.
  - Three independently seeded gradient Perlin axes; amplitudes are fractions of the respective
    box sides. Offset is half the difference from an initial sample (zero at startup), without
    a five-tick gain ramp. Separate phases and slow Perlin tempo modulation; each base noise has
    a 256-cell period, no human/non-repetition claim. Algebraic soft margin compression avoids clipped
    plateaus when Perlin is on; Kalman-only retains the margin clamp. The input box
    is preserved by identity and world coordinates are reconstructed on EVERY invocation.
  - Candidate probes have separate entity-identity state, cached once per target per player tick.
    Skipped/backwards ticks and disable clear history; settings changes restart filtering and
    noise baselines. Numeric inputs are sanitized to GUI bounds; non-finite settings use defaults.
    Entries expire after a skipped tick, with a 64-target capacity. Never evict an entry
    already used this tick: if all 64 are active, new candidates get the margin-clamped input
    without filtering/noise. Only unused entries from the preceding tick may be evicted by LRU.
  - Runs AFTER Sticky and BEFORE StayInHitbox. The latter can reduce/override wandering; it
    retains final priority. Both subfeatures off returns the input point EXACTLY, without margin,
    and hides EdgeMargin with initial sync and change hooks. Parent off skips the processor.
  - Changes the caller's PREFERENCE only. KillAura raytraceBox/attack gates are unchanged; its
    fanning modes can ignore the preference. Other PointTracker callers retain their own gates.
    Existing earlier processors can modify the box; this feature preserves the box it receives.
  - KalmanPerlinPointMotionTest covers filter error, noise bounds/continuity, moving/resized/flat
    boxes, multiple target probes, resets, toggles and lag. NOT playtested, no anticheat verdict.
- `deeplearn/clone/` + `misc/clonerecorder/ModuleCloneRecorder.kt` +
  `anglesmooth/impl/CloneAngleSmooth.kt` — recurrent per-player aim clone (schema/data v5).
  **v5 is intentionally incompatible with earlier recordings and models.** Recorder invariants:
  only target-present runs; every saved segment is within 40 ticks of a real, uncancelled vanilla
  attack against the same tracked entity; off-GCD rows are hard boundaries; sustained slow
  turn-aways are removed and split, while fast emergency turns are retained. Model invariants:
  target id changes zero angular velocity and set `TargetChanged`; current GCD and target box size
  are inputs; movement is one four-state joint hurdle plus a correlated bivariate mixture; attack
  is conditioned on and delayed with the same sampled movement; quantization and finite checks live
  inside `CloneModel.Session`. Every consumer owns a separate session — never move GRU state back
  onto shared `CloneModel`. Targetless runtime ticks reset immediately, matching training sequence
  boundaries. KillAura forces effective `RotationTiming.NORMAL` while Clone is selected, and the
  smoother must respect the caller's visible/raytraced target point. `improve` keeps both the old
  normalization and pinned split, assigns new sequences to training, stages output before publish,
  and must never save non-finite or partially calibrated weights. Eval must reproduce the one-tick
  pending action/click pair, seed both Kotlin and DJL RNGs, and keep discriminator windows
  non-overlapping.
  Added 2026-09-29 (the user's ask: tag where a recording came from; the Gym files had silently
  degraded 6dot1/6dot2). Invariants, do NOT regress:
  - **`AimSequence.source` (`src`)** is `gym`, `dummy` or the waypoint store's normalized server key
    (`MapIdentity.currentServerKey()`); the recorder closes a sequence whenever it changes, like a
    GCD change. Added WITHOUT a `VERSION` bump on purpose - it says where ticks came from, not what
    they mean; a bump would have discarded every recording.
  - **`isPractice`** = source `gym`/`dummy`, or on a legacy (`null`) recording: every target id is
    negative. That rule is exact, not a heuristic: both dummies are client-only entities with negative
    ids (`CloneGym.FIRST_GYM_ID`, `CommandFakePlayer.fakePlayerId`), servers never assign one, and on
    the `default` profile the practice files were exactly the 100 %-negative ones (all others 0 %).
  - `AimSequence.selectForTraining` sets practice aside unless the profile holds nothing else, and
    `parseDetailed` applies it, so `train`, `eval`, `evalmode` and `recalibrate` never see practice.
    `holdoutFor(setAside = ...)` counts set-aside sequences as present - otherwise every older model
    trained on Gym files reports them "no longer on disk".
  - **`CloneTrainer.trainOn`** is the whole training run minus loading the profile, and needs only DJL
    (no `DeepLearningEngine`, no `ConfigSystem`) - that is what lets a variant be trained headlessly
    in a JUnit test (`MinecraftBootstrap.ensureInitialized()` first, publicGson needs it) on the same
    sequences and split as another. `CloneTrainer.Options` holds the experiment knobs
    (`actionScale`, `weightDecay`, `actionNoise`); they are NOT settings, defaults are what
    `.clone train` runs.
  - `Normalization.actionScale` / `ModelMetadata.actionScale`: `null`/0 = plain degrees; otherwise the
    step is `s*asinh(v/s)` before standardizing and `s*sinh` (argument clamped to ±8) after sampling.
    Shipped with the model because the heads are fitted in that coordinate system.
  - A non-finite epoch ends training and KEEPS the best checkpoint when one exists (the user lost
    107 good epochs to one NaN, 2026-09-28); it throws only with nothing validated yet. The NaN source
    itself is NOT found - the data has no non-finite values - and Adam's per-step update is bounded
    (~3x lr), so gradient clipping is not the obvious fix. `actionScale = 2` reproduced a NaN at
    epoch 126 headlessly - that is the cheapest place to hunt it.
  - **`DEFAULT_HIDDEN` is 48** and `CloneNet.suggestedHidden` warns below 3 ticks per parameter (was
    10). Measured headlessly on one split: 48 beat 32 on hits and on adversary AUC; 64 lowered the
    held-out loss but moved no in-game metric. Held-out NLL is not the target - score in the rollout.
  - Measured and rejected the same day (same split, 35 held-out fights): `actionScale = 2`,
    `actionNoise = 0.3`, `weightDecay = 1e-4` - none beat the baseline beyond noise.
  **Reinforcement (`CloneReinforce`, `.clone reinforce <model> <new> [klWeight] [iterations]`,
  2026-09-29, the user's pick of four options).** Imitation carries no hit signal, so the clone falls
  short of the player once its own errors compound. This fine-tunes a trained clone RLHF-style:
  reward = the NEXT row's box on the crosshair (`HitboxHits.entersBox`, the recorder's own test),
  minus `klWeight` x the sampled KL to the reference; REINFORCE with standardized discounted returns
  (γ 0.9), Adam 1e-4 fixed, 64 windows x 128 ticks per iteration, rolled out exactly like `.clone eval`.
  Measured (h48 reference, T 1.0, 3 seeds, 35 held-out fights; player 62.5 % hits, 13.6 % approaches
  lost, 19.2 % still, 1.19 swings/s): reference 49.1 / 33.4 / 19.4 / 1.17, AUCs 0.78 / 0.84;
  β 0.3 frozen + clicks refitted **66.7 / 12.8 / 19.1 / 1.38, AUCs 0.84 / 0.89**; β 1.0 60.3 / 22.0 /
  20.7 / 1.29, AUCs 0.78 / 0.86; β 0.1 NOT frozen 75.0 / 6.8 / **10.7** / 1.90, AUCs **0.94 / 0.96**.
  Invariants, do NOT regress:
  - **Rollouts sample at Temperature 1.** REINFORCE is unbiased only for the distribution it
    samples, so a reinforced model is the model AT Temperature 1 - deploy it there.
  - **The move/still choice is frozen** (`freezeStateChoice`, default on): no reward reaches it, only
    the exact categorical KL to the reference. Without it the still share fell 19.4 -> 10.7 % in 150
    iterations (pausing never earns a hit) and the tree adversary went 0.84 -> 0.96.
  - **The click head gets no reward** and is held by the exact Bernoulli KL, then REFITTED in the
    closed loop (`calibrateClicks`: bisection on the bias until the clone, aiming on its own, swings
    on the player's share of ticks). `CloneCalibration`'s teacher-forced fit cannot do this: a clone
    that hits more sits on the box more and swings 1.5-2x the player's rate. The same applies to any
    hit-aware sampler.
  - It trains ONLY on the reference's `trainKeys` fights and copies its metadata, so `.clone eval`
    of the new model uses the reference's hold-out. `SPLIT_TAIL` references are refused (their train
    fights share tails with the hold-out).
  - The rollout samples from the trainer's live weights through `CloneModel.wrapping` (no ownership,
    `close()` leaves the weights alone) - verified by a probe: after one step the view's heads differ
    from the reference's. Reference log-probs come from `CloneModel.forwardHeads` (no gradient).
  - `Session.step(bestOf = K)` - draw K movements, keep the first that lands on the box, else the
    closest - is an OFFLINE probe (K 2 gave 68.6 % hits at the reference's AUCs); nothing live uses it.
  - **`progressWeight` stays 0 (measured, rejected).** A dense tracking reward - how much closer to
    the next row's box, outside it, than standing still - raised the TRAINING rollout hit rate
    (75.3 % at 0.3, 87.9 % at 1.0, against 69.4 % without) and moved held-out hits not at all (66.8 /
    67.0 vs 66.7 %), fast targets included, while momentum and path length drifted further from the
    player's. That is the clone memorising how the recorded targets move - the recorded opponent
    replays the same way every time. A full pathwise-gradient version is a denser signal still and
    was not built for that reason. The plain hit reward already fixed the original complaint: on the
    faster half of the held-out targets 66.0 % against the player's 58.4 % (reference 45.9 %).
  - Watch: at β 0.3 the pursuit lag measured 1.3 ticks against the player's 2.0 - faster than the
    hand it clones; β 1.0 is the first thing to try if a detection points at reaction time.
  - **The imitation game (GAIL) is ON by default** (`CloneDiscriminator`, `discriminatorWeight` 1,
    same day, the user's "антидетект"). A causal GRU over the step, the residual and the in-box aim
    point (no swing channel) scores every tick; its clipped logit is a SECOND reward stream with its
    own standardized returns, on the whole movement NLL (step + move/still choice). Measured (5
    adversary seeds each, same 35 fights): rl02 0.83 / 0.89 at 67.2 % hits; five GAIL variants all
    below it on both adversaries; the default recipe (weight 1, `warmStart` 32, pause anchor 0.1)
    on two training seeds 0.80 / 0.81 and 0.79 / 0.85 at 66.3 / 65.8 % - the imitation model's
    detectability (0.78 / 0.84) at the reinforced model's hits. Modest, not a cure. Invariants:
    - The discriminator WARMS UP (400 steps on 8 pooled reference-rollout batches) before the policy
      moves: without it the first run's discriminator scored 0.51 / 0.52 after 50 iterations and its
      reward was noise. It never judges a result - `.clone eval` trains its own adversaries fresh.
    - `stateKlWeight` defaults to 0.1 only while the discriminator is on (1 otherwise, the measured
      rl02 recipe): at 1 the pause choice moved 0.0005 nats/tick, i.e. not at all. The HIT reward
      still never reaches that choice (`freezeStateChoice`).
    - `warmStart` feeds 32 ticks of the player's own rows (teacher-forced) before each window. It
      was added on the guess that the discriminator keyed on every window's cold start - MEASURED
      FALSE (it separated warm windows as well or better); it is kept for ~1 point of hits.
    - What it does NOT fix, read off the tree adversary's own 64-tick features and present already
      in the imitation model: pauses in the wrong shape (11.8 still runs per 100 ticks vs 9.1, mean
      1.55 vs 2.12 ticks, runs of 6+ ticks 1 % vs 6 %) and more yaw reversals between consecutive
      moves (0.145 vs 0.114). Clicks after the closed-loop refit still run 1.29-1.33 swings/s on
      held-out fights against 1.19 ("attacks" is a top tree feature). `NoiseCorrelation` is NOT the
      answer to the reversals - its KDoc records 360 spins on v7 models.
  - **`rangeMatching` stays off (measured, rejected, 2026-09-30).** Per-range dual ascent on the hit
    reward towards the player's own rate in each range bucket (`CloneReinforce.RangeMatch`). The
    TRAINING rollouts converged on the targets, the held-out profile did not move: from model `8`,
    121 held-out fights, 0-1 / 3-4 / 5-6 blocks - player 72.6 / 60.3 / 34.4, `8gail` 79.4 / 57.2 /
    21.7, matched 78.3 / 56.5 / 20.5; tree AUC 0.920 -> 0.936. The weights sat on their bounds.
    One scored run (the second seed was killed for system memory before scoring). The profile -
    over the player close, under from 3 blocks out - is the imitation model's (it starts at 46 % at
    3-4 blocks against the player's 63 %).
  - **`AimLog` (`CloneAngleSmooth`, off by default, logging only)**: one line per 200 engaged ticks -
    on-box share by range, the first 10 ticks after a memory wipe vs the rest, wipe causes, and per
    lost target what it was doing and how many ticks it took to come back. Its first read found
    KillAura dropping the target for one tick whenever an opponent crossed the interaction range
    (fixed in `d42bbe5`). **Compare its numbers with the player's recordings from the SAME server**
    (`AimTick.onTarget` by `AimSequence.source`): the player's own on-box share is 68.3 % on
    space-times, 57.9 % on faketime.su, 64.2 % on the untagged legacy files, and faketime's targets
    turn twice as fast (median 2.9 against 1.5 degrees a tick). Against the pooled figure the clone
    looked "not weaker than the player"; per server it is weaker from 3 blocks out.
  - HONEST SCOPE: the recorded opponent does not react to the clone; the offline adversaries are
    trained on the player's own recordings, which a server anticheat does not have, so their AUC is
    not the server's verdict in either direction. NOT playtested; no anticheat verdict exists for a
    reinforced or GAIL model.
- `utils/aiming/learned/` + `anglesmooth/impl/LearnedAngleSmooth.kt` +
  `command/commands/deeplearn/CommandAi.kt` — **Learned**: replays the player's OWN recorded hand
  movement (2026-09-19, the user's ask: delete CloneVrock and "make us this rotation" from three
  `troll.xyz.ai` files - `AiTrainer.java`, `LearnedRotation.java`, `AiHumanizer.java`). It REPLACED
  `CloneVrockAngleSmooth.kt`, deleted the same day (`CloneAngleSmooth` keeps its `open` ctor
  parameters, harmless; `CommandClone.liveClone()` still reads the selected Clone). What is a port and
  what is not - do NOT describe the whole thing as a port:
  - PORTED as written: `LearnedRotation` (data model; JSON field names byte-for-byte the original's, so
    a `<name>.json` from that client loads here), `AimDataset` + `AiTrainer` (recording: per-tick camera
    deltas plus context flags, 15-minute cap, 20-second autosave, `dataset_<stamp>.json` v2 format),
    `LearnedRotationBuilder` (flick / micro / phrase classification, v32 robotic-profile filter, dedupe,
    subsample, v23 handwriting stats, attack profile), `AiHumanizer` (constants as written).
  - RECONSTRUCTED: the playback. The original's engine lived in its `KillauraModule`, which the user did
    NOT supply. `LearnedAngleSmooth` is built only from the sentences the supplied files' comments say
    about it (cumulative profile of a recorded flick stretched onto the live target, micro segments for
    the settle, |yaw| <= 60 / |pitch| <= 24 clamps, GCD quantization, phrases played 1:1 with a fallback
    to flicks); every decision those sentences do not settle is labelled GUESS in the KDoc of the method
    that makes it.
  Storage: `<config>/learned/datasets/`, `<config>/learned/rotations/`. Commands: `.ai start | stop |
  status | create <name> | list | select <name> | delete <name> | reload`; `create` also selects the
  result in KillAura through `RotationsValueGroup.selectAngleSmooth`, as the original did.
  Invariants, do NOT regress:
  - Output is whole mouse counts, `isNormalized = true`, post-processors suppressed while a library is
    selected (the recorded microstructure IS the humanization); a pause is exactly zero on the wire -
    `AiHumanizer.jitter` touches non-zero steps only.
  - The profile replay maps the REMAINING share of the curve's signed cumulative profile onto the
    CURRENT residual every tick - that is what tracks a moving target while keeping the recorded
    per-tick shape. `FRACTION_CAP` / `ARRIVED_EPSILON` keep an overshooting profile from blowing a
    small residual into a snap; the sub-count carry in `quantize` keeps a slow phrase's settle from
    rounding to nothing. Neither is a cleanup candidate.
  - Recording drops every tick with `RotationManager.currentRotation != null` (a module is steering),
    the same signal `ModuleCloneRecorder` uses; the first tick after a start or a world change only
    rebases the reference angles (a respawn would otherwise record a 180-degree "movement").
  - `LearnedRotations.register` scans the folder synchronously on the first selector: the saved config
    sets the selector by name right after construction and an unknown name is thrown away.
    (`ModuleClickGui.sync()`, which this used to guard, went with the browser ClickGUI on 2026-09-24.)
  - Raw on-target micros are capped at 1.5 degrees of net drift and abandoned once the residual passes
    2 degrees; a target switch, a skipped tick or a library swap abandons any replay in flight.
  - The attack profile (`attackSyncRate`, `attackDelayMin/Max`) is computed and printed by `.ai create`
    but NOT wired into KillAura's clicker - that half of the original engine was never seen.
  - Combat-only registration; unlike Clone there is no forced `RotationTiming` - SNAP timings cut a
    swing short, and which timing to run is the user's choice.
  - NOT Grim-audited (no clone on disk). What goes on the wire is recorded human deltas on the grid,
    but the tick timing of a replay against the original engine is unverified.
- `combat/ModuleAimbot.kt` `Mode = BoostSens` — ours (2026-09-23, the user's ask: boost the sensitivity
  while the mouse moves towards the enemy, slow it while it moves away). `Rotate` is the upstream
  behaviour and stays the DEFAULT. Invariants, do NOT regress:
  - The mode never rotates: no `RotationManager` target, no `setRotation`, `targetRotation` stays null.
    It only rewrites `MouseRotationEvent.cursorDeltaX/Y`, at `CRITICAL_MODIFICATION` priority so the
    camera, `RotationManager`'s mouse pass-through and FreeLook-style consumers (all at 0) see the
    scaled delta. `EventHookRegistry` orders descending (`event/EventHookRegistry.kt:29`).
  - Direction is decided PER AXIS against the aim point, re-projected every frame
    (`entity.getPosition(partialTicks) + aimOffset` vs `player.getEyePosition(partialTicks)`): the
    tick-time rotation lags a strafing target by up to a tick, which flips the towards/away sign
    exactly where it matters, next to the target.
  - The boost is clamped so it can bring the crosshair onto the aim point but not throw it past; the
    player's own delta is never cut. Past the point the same movement reads as "away" and is slowed,
    so an unclamped boost feels like hitting a wall.
  - Output is snapped to whole mouse counts (`(sens * 0.6F + 0.2F)^3 * 8.0`, vanilla
    `MouseHandler.turnPlayer`) with the remainder carried per axis, so every turn is one a real mouse at
    this sensitivity could produce. An axis with a zero delta is left untouched - adding the carry to
    it could round to a whole count and turn an axis the player never moved.
  - Skipped under FreeLook, Zoom, spyglass scoping and cinematic camera: the first turns a detached
    camera, the next two use vanilla's finer scoping grid, the last is off-grid by design.
  - `FOV` (default 60) bounds both the target pick and the effect, fading out over its outer 30%
    (`EDGE_FADE`). With the TargetTracker's own FOV at 180 the mode would otherwise slow every look
    away from the nearest player on the server.
  - Visibility per the Settings style rule: `BoostSens` group only in BoostSens, `AngleSmooth` and
    `Ignore` only in Rotate. `Mode` is declared ABOVE them deliberately (top of the GUI); safe because
    `onChanged` fires only from `set` (`config/types/Value.kt:268-270`) and `ChoiceListValue`'s `init`
    does not call it (`config/types/list/ChoiceListValue.kt:42-44`).
  - Grim, read from the clone at `8eb5f28`: the only registered rotation-delta checks are
    `AimModulo360` (>320 degree yaw snap) and `AimDuplicateLook` (a rotation packet that does not
    rotate) (`manager/CheckManager.java:159-161`); neither is reachable by scaling a mouse delta.
    `Baritone` (the GCD-based one) is not registered. No packets are sent by this mode.

## CrystalAura: the doctrine it was built under

Written 2026-09-06/07 from scratch at the user's request. These are not style preferences — every
one of them was paid for by a bug this build actually had. Read them before changing the module.

**1. The standard is what a perfect player would do.** The user's framing: a perfect player can
place 30 crystals a second, but cannot send an incorrect packet. So there are no artificial
delays — the ONLY throttles are `Place.Delay` and `Break.Delay`, both default 0 — and no
self-imposed slowness that vanilla does not impose. Where the module deliberately does something
a real client cannot, that is a NAMED opt-in setting, off by default, with the deviation spelled
out in its KDoc (`ThroughBlocks`, `SameTick`, `IdPredict`; `IgnoreMissTime` is the one such
setting that defaults ON, because `missTime` is a client-side input lockout no server reads).
Do not add a hidden deviation. Do not add a delay to "be safe".

**2. Vanilla is the specification, and it is READ, not remembered.** Every mechanic carries a
`path:line` citation into the decompiled 26.1.2 sources, in the KDoc next to the code that
depends on it. Never derive a mechanic from memory, from another client's implementation, or
from what "everyone knows" about Minecraft. Several of this module's hardest bugs were
mis-remembered mechanics, and several of its best decisions came from finding that the real code
says the opposite of the folklore.

**3. Vanilla is also the test oracle.** `FakeBlockGetter` implements `BlockGetter` so the tests
compare `ClipCore` against the REAL `BlockGetter.clip`; the exposure cross-check calls the real
`ServerExplosion.getSeenPercent`. Prefer a test that runs vanilla's own code over a test that
asserts numbers a human typed. Where the oracle is another re-implementation rather than vanilla
(the damage stages in `AuraDebug`), say so — a shared misconception passes silently.

**4. Anticheat rules come from the checks' source, not from guesses.** Every rule in the KDocs
names the check and its lines. This is how the module learned that Grim evaluates a rotation
AFTER the flying packet (so aim and act can share a tick) and that it does NOT trace occlusion
(so `ThroughBlocks` is legal). Both facts contradicted the cautious assumption that was in the
code first, and both were worth a large speed-up.

**5. Measure before you fix.** 2026-09-07 cost a whole investigation to a "the damage calc is
broken" report: three plausible hypotheses in a row were wrong, and the answer came from one line
of `Debug` output showing `arm=0` — the SERVER was hiding the victim's equipment; the formula was
never at fault. The `Debug` line prints `arm` / `tgh` / `prot` / `res` for exactly this reason.
Read the model's INPUT before suspecting its arithmetic.

**6. Exactness over reach.** Never widen a tolerance to make an action land. Grim's `flagBuffer`
answers a single post-flying miss by CANCELLING the next ten interactions, so an action that does
not provably hit must send nothing and retry next tick. "Almost aimed" is worse than idle.

**7. Ticks are the scarce resource.** At most one attack and one use per tick, attack first, and
digging takes the whole tick (`PacketOrderI`). A plan that cannot execute costs exactly as much
as a wrong one, which is why the searches verify that a target can be AIMED at before committing
to it — a miner that locks onto an unreachable cell does not merely fail, it starves the aura.

**8. Process: separate the writing from the judging.** Code was written by agents on disjoint
files; correctness was checked by SEPARATE read-only audit agents, one concern each, told to cite
`path:line` and to report "NOT FOUND" rather than guess. Those audits found defects the authoring
pass was structurally unable to see. When an audit's conclusion contradicts the brief it was
given, the audit usually wins — but verify its citations, because line numbers drift.

## Project

- Fabric mod fork of **LiquidBounce** (Minecraft `26.1.2`, Fabric, Kotlin).
- Sources: `src/main/kotlin` (most code) and `src/main/java`.
- Navigation engine: `utils/navigation/pathing/` (`NavService`; the old
  `pathfinder` package is gone). It has its own suite under `src/test`.
- Client shaders (GLSL) live under
  `src/main/resources/resources/liquidbounce/shaders/**` (note the doubled
  `resources/resources` segment — that is correct, not a typo).
- Shaders are registered in code, not loaded by path convention:
  - `render/ClientShaders.kt` — registers `.fsh`/`.vsh` sources.
  - `render/ClientUniformDefine.kt` — UBO layouts (std140).
  - `render/ClientRenderPipelines.kt` — pipelines (samplers, blend, UBO).

## Build

Use the Gradle wrapper (`.\gradlew.bat`, Windows PowerShell). Shell does NOT
support `&&`; chain with `;` or `if ($?) { ... }`.

- **Toolchain: JDK 25** (`gradle/libs.versions.toml` → `jdk = "25"`,
  toolchain pinned). If Gradle picks up a wrong PATH java, point
  `JAVA_HOME` at a JDK 25 explicitly. PATH java is 21; a JDK 25 lives at
  `C:\Users\caves\.jdks\jdk-25.0.4.1+1` (verified 2026-09-02):
  `$env:JAVA_HOME = "C:\Users\caves\.jdks\jdk-25.0.4.1+1"; .\gradlew.bat ...`
- **This checkout has no `.git` (2026-09-02):** `build` fails on
  `generateGitProperties` ("No Git repository found"). The user's decision is
  to skip it: always add `-x generateGitProperties`. Working command:
  `.\gradlew.bat build -x detekt -x test -x generateGitProperties --console=plain`
- **No Node any more.** The web theme (`src-theme`, built by node-gradle) was removed with the browser
  integration on 2026-09-24; the build needs only the JDK. Old notes about `-x bundleTheme` and
  prepending Node to PATH are obsolete.
- **Fast compile check (no resources, no jar):**
  `.\gradlew.bat compileKotlin --console=plain`
- **Produce the deployable jar:**
  `.\gradlew.bat build -x detekt -x test --console=plain`
  - Output: `build\libs\LBnew-0.38.1.jar` (~52 MB fat jar;
    `archives_base_name=LBnew` in `gradle.properties`).
  - There is **no** `remapJar` task; the `jar` task produces the final jar.

### IMPORTANT caveats

- `compileKotlin` alone does **NOT** process resources (shaders) and does
  **NOT** build a runnable jar. Editing a `.fsh` and only running
  `compileKotlin` will appear to "do nothing" in game.
- Plain `.\gradlew.bat build` currently **fails on `detekt`** on ~162
  pre-existing issues, the bulk of them in `utils/navigation/pathing/**` and
  `src/test`. This does not affect the jar; the `:jar` task runs before
  detekt. Always pass `-x detekt -x test` to get a clean jar build. Because
  the baseline is not clean, "detekt fails" tells you nothing about your own
  change — grep its output for the files you touched instead:
  `.\gradlew.bat detekt --console=plain 2>&1 | Select-String 'YourFile'`.
  Known-inherited in files you are likely to edit: `CloneAngleSmooth.process`
  length, `CloneEval`/`CommandClone` function counts.
- `-x test` skips a REAL suite (`src/test/**`: pathing, clone pipeline,
  aiming/math utils, ...). When you touch code that has a matching test
  class, run it targeted:
  `.\gradlew.bat test --tests "net.ccbluex.liquidbounce.<pkg>.*" --console=plain`.
  (Full-suite baseline health has not been verified — run targeted.)
- CometRenderer (`com.ferra13671:crm`) is commented out in
  `build.gradle.kts` (~line 122, artifact not available). Do not
  un-comment it as a side effect of an unrelated build fix.

## Deploy

The client is run by dropping the jar into a **Prism Launcher** instance's
mods folder (NOT via `runClient`):

```
C:/Users/caves/AppData/Roaming/PrismLauncher/instances/LB/minecraft/mods
```

Copy after building (the jar was previously named `liquidbounce-0.38.1.jar` —
if that old file is still in `mods`, DELETE it, otherwise Fabric sees a
duplicate mod id and crashes):

```powershell
Copy-Item "build\libs\LBnew-0.38.1.jar" `
  "C:/Users/caves/AppData/Roaming/PrismLauncher/instances/LB/minecraft/mods/LBnew-0.38.1.jar" -Force
```

Then relaunch the Minecraft instance in Prism. If nothing changed in game,
first suspect: the mods-folder jar is stale — verify its `LastWriteTime` is
newer than your build.

**Check the game is closed before copying.** The JVM keeps the mod jar open, so
overwriting it corrupts every class not yet loaded. It surfaces later as
`RuntimeException: Failed to load class file for '<some LB class>'` caused by
`ZipException: ZipFile invalid LOC header (bad signature)`, and the named class
is just whatever loaded next — it points nowhere near the real cause (bitten
twice: 2026-07-31, 2026-08-01). Do NOT test this by process name — Gradle's own
daemon is a `java` process and gives a false positive right after a build. Ask
the file system whether the jar is held instead:

```powershell
$dst = "C:/Users/caves/AppData/Roaming/PrismLauncher/instances/LB/minecraft/mods/LBnew-0.38.1.jar"
$locked = $false
if (Test-Path $dst) {
  try { $fs = [System.IO.File]::Open($dst, 'Open', 'Write', 'None'); $fs.Close() } catch { $locked = $true }
}
```

## Git

- Remotes: `origin` = GitHub (`cavestoru-jpg/LBnew`), `gitlab` = GitLab
  (`cavestoru-jpg/lb`). **Push to `origin main` automatically** once a commit
  exists — do NOT ask (user's rule, 2026-07-31). Push to `gitlab` ONLY when
  explicitly requested.
- Commit style (match history): conventional, scoped — `feat(ModuleName): ...`,
  `fix(ModuleName): ...`, `docs(AGENTS): ...`, `chore: ...`. One commit per
  logical change (e.g. module port, hardening pass, docs) — do not bundle
  unrelated modules into one commit.
- The user expects finished work to end committed and pushed. Ask before every
  `git commit` (rule of this workspace), even if they approved it for a previous
  task — but once the commit is made, `git push origin main` follows without a
  second confirmation.

## Reproducing UI from reference screenshots (unbiased-agent forensics)

When the task is "make it look exactly like this screenshot", do NOT eyeball
the reference yourself while holding your own implementation in context —
you will see what you built, not what is there (confirmed failure mode:
the first two HellhitHud TargetHUD attempts, 2026-08-03, both "looked right"
and were both wrong).

Proven workflow instead:

1. Launch a FRESH agent (general-purpose) whose prompt contains ONLY the
   image path — zero context about the feature, prior attempts, or what the
   UI is for. Ask for a forensic spec "another person could rebuild from
   without seeing the image".
2. Have it upscale the image 4-6x (nearest-neighbor + smooth, via PIL in
   the scratchpad) and SAMPLE exact pixel colors (`getpixel`) instead of
   estimating; require bounding boxes in px for every element, spacings,
   radii, and an explicit "ambiguities" list with alternate readings.
3. Implement from that spec's numbers (convert device px -> gui units by
   the screenshot's guiScale, usually /2), not from your memory of the
   picture. Where the spec lists an ambiguity, pick the conservative
   reading and tell the user what was left out.

What this catches that self-reading misses: translucency (a "brown" panel
was black-over-wood), bars disguised as underlines, engraved 2x2 grids read
as icon rows, frameless icons read as slots, and font sizes ~2x smaller
than assumed.

## Splitting large modules for parallel agent work

Modules growing past ~1000 lines should be split into a per-module package
so multiple agents can edit different concerns in parallel without file
conflicts. Two live patterns:

**Class-based split** — see `modules/combat/crystalaura/`: hub object plus
collaborating classes in subpackages (`calc/`, `scan/`, `aim/`, `exec/`, `mine/`,
`state/`, `render/`). This is the pattern to copy; prefer it whenever the
concerns carry real state of their own, which is almost always.

**Extension-function split** (both modules that used to demonstrate it were
deleted on 2026-09-07; the recipe still applies when all state must stay on
one object):

- One hub file: the `object` declaration, ALL
  settings/value groups, public hooks, ALL state fields (single source of
  truth), event handlers, small shared helpers, KDoc documenting which file
  owns what.
- Sibling files per concern (`<Module>Place.kt`, `<Module>Break.kt`, ...): logic
  as `internal fun <ModuleObject>.<name>(...)` extension functions
  (Kotlin objects can't span files). Widen `private` → `internal` on
  settings/state the extensions touch; keep `private set` unless an
  extension must write the field.
- Update the `ModuleManager` import and every cross-module import after the
  move (grep the old fully-qualified name).
- Such splits must be PURE moves (plus visibility/import adjustments) —
  no logic edits, no renames; verify with `compileKotlin`.

## Settings style (hard rule, the user has asked twice)

A setting that does not apply right now must not be on screen. Two mechanisms, and they are not
interchangeable:

**1. A sub-feature with its own settings -> a nested `ToggleableValueGroup`.** Both ClickGUIs already
collapse the children of a disabled group - Hellhit at `HellhitClickGuiScreen.kt:1404-1406`, Leet at
`LeetClickGuiScreen.kt:1680-1700`; each grows its reveal only while `value.enabled`, and
`hasVisibleSettings` skips a group whose only child is `Enabled`. So the GUI needs NO work: declaring
the sub-toggle as a flat `boolean` next to its options is what leaves the GUI nothing to collapse.
That was the actual fault both times this came back.

In a CLASS (an `IncomingDamage`-style group owned per module) the sub-group must be a nested CLASS
taking the owner as a constructor argument, not a nested `object` - an object cannot reference the
enclosing instance. `object` only works inside an `object` (which is why `player/offhand/Totem.kt`
can nest them).

**2. A setting that only applies under one MODE of a sibling -> drive `notAnOption` from an
`onChanged` hook.** There is no dedicated visibility mechanism in this config system; this is it.

- `Value.notAnOption` is read-public / `private set` (`config/types/Value.kt:110`). The no-arg
  `notAnOption()` is ONE-WAY. Use `notAnOption(state: Boolean)` (added 2026-09-16 for exactly this)
  so the value can come back.
- Both GUIs skip a flagged value (`HellhitClickGuiScreen.kt:1358`, `LeetClickGuiScreen.kt:1651`).
- A whole nested group can be hidden the same way: `ValueGroup` IS a `Value`
  (`config/types/group/ValueGroup.kt:94`).
- **Persistence is safe.** The file writer is built with `includeNotAnOption = true`
  (`gson/serializer/ValueGroupSerializer.kt:44-46`), so a hidden setting keeps its saved number and
  gets it back.
- The hook fires on a config LOAD as well as a click: `Value.deserializeFrom` ends in `set`
  (`Value.kt:307`) and `set` runs the changed-listeners (`:244`).
- **An `init` block is still required**, and forgetting it is the bug to expect: a change hook cannot
  cover the INITIAL state, so the default - and a saved value that happens to equal it - would come
  up with stale visibility until the dropdown is touched.
- **Declare the hidden value BEFORE the mode that hides it.** The hook touches that field, and a
  hook firing against a field the constructor has not reached yet reads null.

Shape:
```kotlin
private val customTicksValue = int("CustomTicks", 5, 0..20, "ticks")   // declared first
private val customTicks by customTicksValue

private val mode by enumChoice("Mode", Mode.NORMAL)
    .onChanged { syncCustomVisibility(it) }

init { syncCustomVisibility(mode) }                                     // covers the default

private fun syncCustomVisibility(current: Mode) =
    customTicksValue.notAnOption(current != Mode.CUSTOM)
```
Live examples: `combat/totem/SwapJitter` (`CustomTicks` under `Mode = Custom`) and
`combat/totem/IncomingDamage` (the whole `MeleeSword` group under `Trigger = OnlyLethal`).

**3. Prefer a toggle over an `Off` enum entry.** A `ToggleableValueGroup` already has `Enabled`, and
its children collapse with it; an extra `Off` in the mode enum duplicates that and leaves a dead
dropdown entry. `SwapJitter.Mode` lost its `OFF` for this reason.

**4. Cross-cutting groups belong to the MODULE, not to `GlobalManager`.** Mounting shared settings
globally puts them in the Hellhit `Settings` panel (gear icon, `HellhitClickGuiScreen.kt:993`), far
from the module they affect and unreachable in the Leet GUI, which renders no `GlobalManager` at all
(zero references). It also means one module's setting silently drives another's behaviour. Make the
group a class and give each module its own instance - `SwapQuiet`, `SwapJitter` and `TotemStaging`
were moved out of `GlobalManager` on 2026-09-16 for this. `HandSwapGate` stays global because it is
genuinely client-wide.

## Adding a new module

- Location: `features/module/modules/<category>/`; register in
  `ModuleManager.kt`. Past ~1000 lines, split per the section above.
- Use the existing idioms: Value DSL (`boolean`/`float`/`int`/`enumChoice`/
  `tree(...)`) and `handler<Event> { }` like neighboring modules. KDoc
  header: source of the module (ported from X / original) + Grim-relevant
  design decisions naming the checks they address.
- Packets only through sanctioned APIs (Porting checklist, item 2).
- If the module sends packets, it is NOT done until it passes a Grim audit:
  map its full packet surface, read the relevant check sources in the Grim
  clone (swarm of read-only agents works well — one per check family),
  fix, re-verify with `compileKotlin`. Then record the resulting invariants
  in the Porting section.

## Cross-cutting gotchas

- `Rotation.normalize()` rounds to sensitivity counts without independent off-grid noise.
  Pitch errors are unwrapped, with final +/-90 clamping. Float rounding and the pitch boundary
  prevent a claim of universally exact wire counts. Already-normalized modes keep their output.
  Default reset hand-off uses `realRotationContinuousWith`; smoothers with preservesResetSteps
  own their bounded exact endpoint instead. AimProcessor's feature extraction alone does not
  establish that exact counts or noise are safer; model acceptance requires runtime evidence.
- `PacketEvent` can fire on the NETTY thread (both directions): never touch
  the world or send game packets there — enqueue to a
  `ConcurrentLinkedQueue` and drain on the tick handler (pattern:
  `crystalaura/net/PacketIntake`, drained at the head of the aura's PLAN step).
- `GameTickEvent` fires at HEAD of `Minecraft.tick()`;
  `RotationManager.update()` runs on it with `FIRST_PRIORITY`, so a rotation
  requested in tick N reaches the wire in tick N+1's move packet. Anything
  that needs the aim on the wire BEFORE acting must gate on
  `RotationManager.serverRotation` (settled gates), not on when the target
  was set.
- Slot changes: only `inventory.selectedSlot = x` +
  `interaction.ensureHasSentCarriedItem()` (the vanilla tracker dedups; a
  raw `ServerboundSetCarriedItemPacket` desyncs `carriedIndex` → Grim
  BadPacketsA duplicates). `MixinMultiPlayerGameMode` redirects the
  tracker's slot source to `SilentHotbar.serversideSlot` while another
  module holds a SilentHotbar state — check it before assuming your slot
  goes out.
  A `SetCarriedItem` -> `SWAP_ITEM_WITH_OFFHAND` -> `SetCarriedItem` burst goes through
  `utils/network/sendSwapHotbarSlotWithOffhand`, which brackets with the slot the server HOLDS
  (`carriedIndex`, synced first) - see its KDoc for why `serversideSlot` is a tick off.
- **Moving an item between two player-inventory slots is ONE `SWAP` click, not a `PICKUP` chain**
  (2026-10-03, the user's report "the refiller clicks with the mouse instead of a fast swap into the
  slot"). A `SWAP` (`Click.performSwap`, button 0..8 = hotbar, 40 = offhand from 1.16) trades the
  clicked slot with the button's slot and never touches the cursor (`AbstractContainerMenu.doClick`
  `:477-513`). A `PICKUP` chain is 2-3 clicks `ClickDelay` apart with the stack on the cursor in
  between, and a click cut out of it (Grim `MultiActionsC`, LIVE, cancels clicks while sprinting /
  moving / sneaking) leaves client and server with different cursors. Converted that day:
  `ModuleReplenish` (`Method = Swap`, the new default; `PickUp` kept as opt-in), AutoBuff `Refill`,
  `ModuleOffhand` SMART's non-hotbar fallback, `ModuleAutoArmor` (worn piece -> replacement in the
  hotbar). Invariants, do NOT regress:
  - **A click that changes nothing is still sent** (`MultiPlayerGameMode.handleContainerInput`
    `:452-469`), and `InventoryManager` re-asks after every click it sends and only leaves its loop on
    an empty schedule. A chain that can be a no-op therefore clicks every `ClickDelay` FOREVER with the
    inventory open server-side - which also stands CrystalAura / AutoAnchor down
    (`isInventoryOpenServerSide`); at `ClickDelay = 0` it loops inside one tick (`waitTicks(0)` returns
    at once and resets the 100-cycle guard). Found twice that day: AutoBuff `Refill` (offhand counted as
    hotbar space, but a shift-click of a buff item never lands there; a player head went onto the HEAD
    slot) and `AutoArmor` on a worn Curse of Binding piece. Before scheduling, make sure the click CAN
    change something.
  - Replenish's swap into a NON-empty slot needs a stack above `ItemThreshold`. Every swap leaves the
    old low stack behind as a remnant; with "larger than the slot" alone the remnants traded places with
    the slot nearly every item (refutation audit). Remnants refill an EMPTY slot only, largest first.
  - Replenish `PickUp`'s third click puts the rest back into the SOURCE slot. It used to hit the full
    hotbar slot, which takes nothing (`Slot.safeInsert` `:131-133`), so the rest stayed on the cursor and
    the `carried` guard stopped every later refill.
  - NOT done, offered to the user: Replenish, AutoBuff `Refill` and AutoArmor do not ask `SwapQuiet`, so
    `MultiActionsC` still cancels their clicks while you sprint. A single `SWAP` makes a cancel cheap
    (one predicted swap resynced) but does not prevent it. NOT playtested.
- Outgoing `PacketEvent` fires synchronously on the sending thread, so a
  plain boolean guard around the module's own sends is a valid self-filter
  (pattern: SpeedMine `internalSwap`).
- One Grim tick-window closes on flying/`CLIENT_TICK_END`: multiple module
  actions inside one tick-handler pass share one window — this is what the
  per-tick mutexes and <40 ms timestamp gates between CrystalAura and SpeedMine
  protect (`ModuleCrystalAura.lastActionMs`). Relative handler order BETWEEN modules is undefined — use
  timestamps, not per-tick flags, for cross-module window checks.
- Sources are UTF-8 without BOM. Never round-trip ANY source file through
  PowerShell `Get-Content`/`Set-Content` (PS 5.1 re-encodes to ANSI and/or
  adds a BOM) — the ClickGUI warning generalizes to the whole tree. Use
  the Read/Edit/Write file tools.

## Verifying jar contents (sanity check)

Confirm your shader/class edits actually made it into the jar:

```powershell
$jar="build\libs\LBnew-0.38.1.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z=[System.IO.Compression.ZipFile]::OpenRead($jar)
$z.Entries | Where-Object { $_.FullName -like "*hawenesp*" } | ForEach-Object { $_.FullName }
$z.Dispose()
```

## Porting modules from other clients (echo, syntax, hawen, ...)

- Source trees seen so far: `C:\Users\caves\Music\echo` (Java/Fabric, Stonecutter),
  `C:\Users\caves\Music\hawen-output` (decompiled Java); "syntax" client snippets
  are provided inline by the user (Yarn mappings). Newest: `C:\Users\caves\Music\OpenMio-main`
  (obfuscated Java, `field_XXXX`; setting defaults are encrypted in `libs/mio-loader.jar`
  and unrecoverable — choose your own).
- **DELETED (2026-09-07):** the OpenMio-based `combat/autocrystal/`, the catlean-based
  `combat/crystal/` (AutoCrystal + AutoBasePlace) and the upstream `combat/crystalaura/` are all
  GONE, replaced by the original `combat/crystalaura/` module in the map above. Their Grim notes
  named the right checks, but nothing in this repo implements them any more — do not resurrect
  the invariants from git history; the current module's KDocs are authoritative.
- `ModuleSpeedMine.kt` is a port of HellHit's `SpeedMine.kt` (`C:\Users\caves\Downloads\SpeedMine.kt`),
  Grim-audited the same way. Invariants, do NOT regress: ABORT_DESTROY_BLOCK always
  `Direction.DOWN` with an `abortSent` per-action flag (vanilla never sends the real
  face — PositionBreakB; no duplicate ABORT — WrongBreak); START-only for insta-break
  blocks (AirLiquidBreak); swing before every START (NoSwingBreak); FINISH→START ≥ 300 ms
  and the `estFastBreakBalance` budget mirroring Grim's finish branch (FastBreak — this
  is what makes factor > 1 sustainable); DAMAGE mode is a per-block micro-boost capped to
  never save a whole tick; replace places via RotationManager (RotationPlace/PacketOrderI);
  NCP_AIR and doubleMine are documented NOT-for-Grim (FastBreak/AirLiquidBreak/MultiBreak
  are inherent to their design). Cross-module arbitration (rewritten 2026-09-07 when AutoCrystal
  was deleted): it takes the `MiningClaim` before its first START, so a single active dig pos is
  enforced against EVERY digger in the client (WrongBreak), and it defers its own packets while
  `ModuleCrystalAura.lastActionMs` puts the aura inside this tick window — a wall clock, because
  handler order between modules is undefined and a per-tick flag cannot see it (PacketOrderI).
  **The tool in the hand is the whole module** (2026-09-22, reported as "SpeedMine stops working on
  stone at some point while wood keeps working"). Four defects of one family were fixed that day;
  all four made the dig be TIMED for one item while the SERVER held another. Do NOT regress:
  - The STOP is checked against the server's own acceptance test BEFORE it is sent:
    `getDestroyProgress(held) * (ticksSpentDestroying + 1) >= 0.7F`
    (`ServerPlayerGameMode.handleBlockBreakAction`, STOP branch), evaluated against
    `player.mainHandItem` — which IS the server's view, because `MixinInventory` redirects
    `getSelectedItem` to `SilentHotbar.serversideSlot`. A STOP that fails it is not merely wasted:
    the server arms `hasDelayedDestroy` and finishes the block at the FULL vanilla time, and while
    that flag is up the next premature STOP on ANY other block is dropped outright
    (`if (!this.hasDelayedDestroy)`), so one wrong STOP costs several blocks. The symptom is
    "SpeedMine does nothing", never an error.
  - That gate is what covers the case the module cannot see by itself: our hotbar switch goes out
    through `ensureHasSentCarriedItem`, which announces `SilentHotbar.serversideSlot`, so while
    ANOTHER module holds a silent slot our tool never reaches the server at all.
  - `switchTo` RETURNS whether the stack really reached the hand (a container swap is skipped while
    moving — MultiActionsC). `breakBlock` and the insta-break START both refuse to fire on false;
    the insta path marking `instaCompleted` on a swap that did not happen meant no STOP was ever
    sent and the block never broke.
  - `toolSlot`'s durability filter exempts non-damageable stacks (`maxDamage <= 0`), like
    `InventoryUtils.kt:104` does. Without it, a block with no applicable tool (stone, no pickaxe)
    returned null, because every candidate ties at speed 1.0 and the tie-break returns the HELD
    slot — obsidian, a crystal, an empty slot, all `maxDamage == 0`.
  - A null tool means "dig with the held item", NOT "do nothing". `MineAction.update` used to
    `return` there, accumulating no progress and sending no STOP; since `attackHandler` cancels
    `BlockAttackEvent` unconditionally, vanilla's own dig never starts either, so the block was
    simply unbreakable while the module was on.
  - The FastBreak budget moves only once every gate has passed. Charging it before a deferral
    charges every retry of the same dig again.
  - `Debug` names every refusal (claim holder, the 300 ms pacing, the budget, the aura's window, a
    refused swap, a STOP the server would reject) and prints per break: timed-for stack, the stack
    the server sees, and the achieved share of 0.70. Read it before theorising.
  Read from `FastBreak.java` the same day, because the KDocs state it less precisely: the check keeps
  TWO balances. `blockDelayBalance` is the FINISH→START one and is NOT a hard 275 ms rule — a start
  `>= 275 ms` after the last finish decays it by 0.9, anything earlier adds `300 - delay`, and it
  flags above 1000 (both balances clamped to ±max(1000, ping)). Vanilla's own `destroyDelay = 5`
  puts the next START 6 ticks (300 ms) later, which is exactly what the module's 300 ms gate
  reproduces — so the gate is vanilla's shape, not an excess of caution, and cannot be lowered for
  speed. `blockBreakBalance` is the dig-duration one the module mirrors. **Consequence worth stating
  plainly: at `Factor = 1.0` the module is not faster than vanilla on any multi-tick block by
  construction.** The whole margin is the server's 0.7 threshold, i.e. `Factor <= 1/(0.7 - d)`,
  about 1.4 for a block of several ticks; above that the new gate makes it wait instead of burning
  the block. Also `maximumBlockDamage` is the MAX over every ANIMATION/flying packet of the dig
  (`FastBreak.onPreViaPacketReceive`), so Grim credits the best tool held at any point.
  **`Rebreak = Grim` (2026-09-24, the user's report "the rebreak depends on where I stand" + a
  `RebreakSpeed` request).** A rebreak is a bare STOP on a cell we already dug, sent when a block
  reappears there: the server keeps `destroyPos` / `destroyProgressStart` from our START
  (`ServerPlayerGameMode.java:218-229`), so it breaks at once. Do NOT regress:
  - The action SURVIVES the cell being empty (`rebreakable && instantRemining` branch at the top of
    `MineAction.update`). Before, `canBreak` (false for air) ran first and dropped the action the
    moment the client saw the break, so the rebreak only fired when break and re-place landed in
    one client tick, and the "block is gone" branch below it was dead code (it still is for Off /
    NCPAir, deliberately untouched).
  - A rebreak STOP waits until the server's ack of our PREVIOUS STOP has been PROCESSED
    (`utils/block/BlockChangeAcks`, fed by `MixinClientLevel` at the return of
    `ClientLevel.handleBlockChangedAck` - main thread; an incoming `PacketEvent` is netty and too
    early). Grim turns the cell to air on our FINISH and holds it there until that ack
    (`CheckManagerListener.java:786-790`, `CompensatedWorld.java:286-289`), while the client keeps
    drawing the old block; the rebreak skips `Await`, so before this gate it sent a STOP every tick
    of that round trip into Grim-air - `AirLiquidBreak`, LIVE, cancels.
  - `lastDigAnchor` is the last START/ABORT on the wire from ANY sender (read off outgoing
    packets). Every STOP in `breakBlock` (DoubleMine exempt) and every rebreak requires it to be
    ours on this cell; otherwise the action is `abandon()`ed WITHOUT an ABORT - an ABORT then would
    hit the other digger's dig (`WrongBreak`, and vanilla clears `isDestroyingBlock`). Blind spot:
    `sendPacketSilently` bypasses the event, and a handler that cancels the event before ours hides it.
  - Reach is `AABB(pos).distanceToSqr(eyes) <= range^2` (Grim `FarBreak`'s rule, inside the
    server's `+1.0`). It used to be eyes -> CENTRE `<= range + 0.5`, which shrank the reach to ~4.1
    diagonally and dropped the action for good - the positional dependence that was reported.
  - `RebreakSpeed` (1..50/s, default 20, visible only under `Rebreak = Grim`) is a CEILING: one
    rebreak per reappearance, each after the previous ack. Up to 20 it is a per-tick credit on the
    tick path (Grim-safe). Above 20 a per-frame path (`GameRenderTaskQueueEvent`) also fires, which
    is NOT Grim-safe: a dig after the tick's movement packet followed by a pong before the next one
    is `Post` (LIVE, `misc/Post.java:77-102`). With `Rotate` on, the frame path fires only when
    `serverRotation` already hits the block. The user chose this ceiling knowingly. The tick credit is
    capped at `1 + perTick`, not 1: with a cap of 1 every spend left exactly 0 and 11..19 all ran at 10/s.
  - `BlockChangeAcks` drops an ack larger than the level's own `currentSequence()` (read in the
    mixin through a `@Shadow` - the getter is package-private, `ClientLevel.java:204`). A dimension
    change builds a new `ClientLevel`, the ack for packets sent just before the portal lands on it, and
    kept it would open the gate for the whole stay in that level (counters restart at 0).
  - A `ClientboundRespawnPacket` (death or dimension change) drops every action WITHOUT a packet: the
    server's new `ServerPlayerGameMode` has `destroyPos = BlockPos.ZERO` (`ServerPlayerGameMode.java:44`),
    and a same-dimension respawn keeps the `ClientLevel`, so nothing else would notice.
  - A dropped action (`cancel` / `abandon` set `ended`) never sends the ABORT / STOP that
    `sendStartSequence` scheduled a tick after its START. An action evicted before its first tick used to
    send that ABORT after the next action's START - a stray ABORT for another cell.
  - Known, accepted: the 2 s ack timeout can open early if Blink/FakeLag holds our STOP longer than that
    (it is flushed through `sendPacketSilently`, invisible here). At the very edge of the new reach the
    aim relies on points near the nearest corner, a margin risk for `RotationBreak` (experimental).
    Pre-existing and untouched: `Replace` never runs (`hotBarLock` is set true in `breakBlock` right
    before `replace` checks it is false), and the "block is gone" branch is still dead in Off / NCPAir.
  - Mining Fatigue in `digSpeed` is vanilla's TABLE (`Player.java:594-601`: 0.3 / 0.09 / 0.0027 /
    8.1E-4), not `0.3^(amp+1)`, which was 10x too fast from level III on and made the STOP gate send
    STOPs the server refuses. Related fact for anyone tuning this: the server counts
    `ticksSpentDestroying` from the START only (`ServerPlayerGameMode.java:185`, `:220`), and a
    rebreak sends none, so on a long-lived action even Fatigue III passes eventually (obsidian,
    diamond + Efficiency V: ~11 400 ticks after the START). Any new START anywhere resets it.
  - `RebreakFastBreakCheck` (default ON, only under `Rebreak = Grim`): OFF skips the module's FastBreak
    mirror for rebreaks and books them as Grim's "close enough" (balance x0.9). Basis, read not observed:
    after our first FINISH Grim holds the cell as air and the swing we send after every STOP makes
    `maximumBlockDamage` Infinity (`FastBreak.java:132`, `BlockBreakSpeed.java:124`), so Grim predicts 0 ms
    until the next START. With the mirror ON, Fatigue III holds the first fast rebreak until ~13.6 min
    after the START (obsidian, diamond Eff V) instead of the server's ~9.5. Keep the swing after the STOP:
    the OFF path leans on it.
  - `RebreakStops` (1..20, default 1, only under `Rebreak = Grim`; the user's ask 2026-09-25, knowingly
    NOT Grim-safe above 1). After every regular STOP, `RebreakStops - 1` BLIND follow-up STOPs go out on
    the next ticks, one per tick per action (`lastStopTick`), last in `update()` so a regular STOP takes
    the tick. Blind = no ack gate, no rotation, whatever the client shows: they exist to catch a block
    put back server-side before we see it. On Grim each one before the ack is `AirLiquidBreak` (cancel +
    flag). Still refused on a lost `destroyPos` (`serverDigsAt`), out of reach, `AwaitCrystalAura`
    (`isInstantRemining`), the aura's window and `OnEating = Grim`; the tool is picked for the block the
    cell held at our STOP (`dugState`), because the server times the STOP against the hand.
  - **Crystal aura API (2026-10-02, CrystalAura `Mine.Engine = SpeedMine`, see that entry):** `startForAura`,
    `markForAura`, `handBackFromAura`, `auraFinishBlocker`, `finishForAura`, `releaseForAura`, `ticksToFinish`,
    `auraCompatible`. `breakBlock` returns its hold reason (null = sent) and takes `auraOwnsTick`, which skips only the
    `auraBusy` window (the aura calls from a tick it planned no other click in; that window would read its previous
    tick). `auraFinishBlocker` must stay the SAME gates as `breakBlock`, run for a given stack (plus the module's own
    pace for that stack in place of `progress`) - if a gate is added to one, add it to the other. While `auraOwned`
    the dig gets no STOP from `step`, no scheduled `StartMode = Stop` STOP and no blind follow-ups (the aura sends
    every STOP), and `step` drops it if another START moved the server off it; after `handBackFromAura` it is an
    ordinary dig again. `releaseForAura` removes and abandons the action at once (the instant remine is drained
    BEFORE `stopRequests` in the tick handler) and sends an ABORT only for a live dig on a standing block.
  - **DoubleMine, the server's delayed destroy (2026-10-03, the user's report "DoubleMine does not work").** Read from
    `ServerPlayerGameMode` (26.1.2): every DoubleMine START is START + ABORT + STOP; that early STOP arms the server's
    ONE `hasDelayedDestroy` when none is armed (`:231-236`), and the server then breaks that cell by itself on the
    first server tick where `getDestroyProgress(ITEM IN HAND) * (ticks since its START + 1) >= 1.0` (`:111-119`,
    `:134-136` - the full 1.0, not the STOP's 0.7). The next START moves `destroyPos`, and a STOP for any other cell is
    ignored (`:219`). The port (like HellHit's original, `C:\Users\caves\Downloads\SpeedMine.kt`) held the tool for
    one or two ticks after the SECOND block's STOP and otherwise sent the first block a STOP the server ignores (plus,
    with `RebreakStops`, its blind follow-ups) and then dropped it - so the first block broke only when that short hold
    happened to fall on a server tick after its time. Now, do NOT regress:
    - Two mirrors. `serverDelayed` = the server's armed cell: set when a STOP of ours arms it (`sendDoubleMineStart`,
      `armServerDelayed`), cleared only when the client sees the cell air (`maintainDelayedHold`) and on `reset`.
      `serverDestroyPos` = the last START on the wire from ANY sender EXCEPT an insta START (the server answers that
      with `destroyAndAck`, `destroyPos` does not move; our own is flagged `instaStartInFlight`). Do NOT use
      `lastDigAnchor` for this: it also moves on an ABORT and an insta START.
    - `MineAction.finishedByServer` = DoubleMine, cell == `serverDelayed`, cell != `serverDestroyPos`. Such a cell
      gets NO STOP from any path (`step` branch, `breakBlock` guard, `auraFinishBlocker`).
    - `startMine` (`makeRoomForDoubleMine`) first evicts as before, THEN hands a FREE delayed slot to the dig standing
      on `serverDestroyPos` with an early STOP (`armServerDelayed`, only in reach - beyond it the server drops the
      packet, `:155`, and the mirror would be poisoned) before the new START moves `destroyPos` - otherwise the new
      START's own early STOP takes the slot and the old dig is finished by nothing. What is left orphaned (neither cell
      - a foreign START, a desync) is `abandon()`ed without packets; a held click STARTs it again. `armServerDelayed`
      may send that STOP for an aura-held dig too: it is the one way to keep it, against the "no STOP of SpeedMine's
      own while the aura holds the dig" rule - accepted, DoubleMine is not a Grim mode.
    - The hold (`requestDelayedHold`): once due (`delayedDue`: vanilla progress with the tool, x (ticks since our
      START + 1) >= 1.0) the HOTBAR tool is put in the hand ONCE and left there until the cell is air, the action is
      dropped, or `ping * 2 + max(1 s, 15 % of the dig)` passes (the share covers a server below 20 TPS, which counts
      its own ticks). ONE hold per dig: an expired hold marks `holdExpired` and the dig is dropped, never held again
      (the first cut re-held every tick after expiry and pinned the slot for good - refutation audit). NEVER fought
      for: a slot change by anyone else (the player, another module's NORMAL switch, a dig of ours with another tool)
      ends the hold on the spot without a restore (the second cut re-asserted every tick and would have kept a NORMAL
      MainHandTotem's totem out of the hand - second audit). Not started with `SwitchTool` off, with an inventory
      tool, while an inventory swap is outstanding (`swappedTool`), while the player uses an item, or while
      `ModuleMainHandTotem.isBusy` / `ModuleAutoTotem.isSwapping`.
    - The slot to go back to: every capture uses `restingSlot()` (the player's slot, or during a hold the slot the
      hold hands back); a hold that starts while a STOP's delayed `switchBack` is pending takes that target
      (`pendingSwitchBack`); `switchBack` during a hold records its target as `holdHomeSlot` instead of switching, and
      an inventory swap-back is NOT held (the selected slot does not move during a hold, so it still pairs). The
      restore at the end waits while the player uses an item or an inventory swap is out (`pendingRestore`), and is
      skipped if the hand is no longer on the hold's slot; `reset()` restores too.
    - Known desyncs, each costing at most one wasted hold: `reset` clears the mirrors while the server keeps its
      delayed destroy across a toggle or a dimension change (`ServerPlayer.setServerLevel` keeps the game mode); a cell
      in an unloaded client chunk reads air; a START the server ignored (spawn protection) still arms the mirror.
    - Known gap: while another module holds a SilentHotbar slot (the crystal aura's crystal) `ensureHasSentCarriedItem`
      announces that slot, so the server sees no tool on those ticks and finishes the block on the first one it does.
    - NOT playtested; NOT Grim-safe (DoubleMine never was, see the setting note).
  - NOT playtested.
- `ModuleAnchorOptimizer.kt` (+ shared `utils/combat/autoanchor/` helpers) — fully automatic
  respawn-anchor charge/detonate cycle, Grim-audited 2026-08-26 against the clone (swarm audit,
  checks quoted from source). Invariants, do NOT regress: everything (swap, place, swing, restore)
  is emitted at the HEAD of the client tick (`RotationUpdateEvent`) — before vanilla's sneak/sprint
  ENTITY_ACTION and this tick's flying, so a Grim window never reads `[use, swap]` (PacketOrderE/F)
  and nothing lands between a flying and CLIENT_TICK_END (PacketOrderO/Post); swaps only via
  `ensureHasSentCarriedItem` (vanilla tracker dedup — BadPacketsA is SESSION-scoped on the
  announced slot, never re-announce the same one); at most one interaction and one swap per tick,
  paced on the ATTEMPT; the action fires only when a raytrace of `serverRotation` lands on the
  anchor (stricter than RotationPlace's full-cube candidate set) and the DuplicateRotPlace gate
  passes — `RotationConvergenceTracker` mirrors that check's YAW-delta logic (`deltaX > 2`
  repeated within 1e-4 across placements; the pre-2026-08-26 pitch-based version was a mis-port
  from echo); hard stands: never act while `isUsingItem` (MultiActionsE flags the swing, offhand
  use bypasses its slot comparison), full yield to KillAura with a live target (MultiActionsF:
  block + entity interact in one window), stand down while SilentHotbar is modified by another
  module (the carried-item announce would carry the enforced slot, not ours), no acting from
  vehicles (MultiActionsG). The OnTick rotation timing is documented NOT-for-Grim (TickTimer
  counts flyings per CLIENT_TICK_END on 1.21.2+ — non-experimental, setback on first violation;
  Timer books +100 ms balance per use; the 1.17 duplicate-position exemption is gone for 1.21+).
  2026-09-02: the damage model (MinDamage/MaxSelfDamage/AntiSuicide/TargetRange/IgnoreDamage)
  was REMOVED on the user's decision — it rejected nearly every anchor in reach. "Own anchor"
  (`OwnOnly`, replaces `FreshOnly`) is decided from the WORLD, not from the packet: an anchor is
  ours because the CLIENT said so: `MixinBlockItem` injects at the RETURN of
  `BlockItem.place(BlockPlaceContext)` and fires `ClientBlockPlaceEvent(pos, block, hand)` for the
  local player's own client-side placement, carrying `BlockPlaceContext.getClickedPos()` — the cell
  vanilla actually wrote, with the "replaced the clicked block" case already resolved. Nothing is
  derived from the packet any more. Verified in 26.1.2: `MultiPlayerGameMode.performUseItemOn` calls
  `ItemStack.useOn` client-side, `BlockItem.useOn` builds a `BlockPlaceContext` and calls `place`,
  and `Items.RESPAWN_ANCHOR` is a plain `BlockItem` (registered with `registerBlock`). The mixin
  filters `isClientSide` (so singleplayer's integrated server does not double-report) and
  `getPlayer() == mc.player`.
  Four dead ends, do not revisit any: (1) re-deriving the cell from the outgoing
  `ServerboundUseItemOnPacket`'s clicked face — two candidates, one wrong, plus a hand test that
  cannot see a stack the prediction already consumed; (2) claiming inside that packet handler from
  the client's prediction — needs the block to exist the instant the packet leaves, and did NOT work
  on at least one server; (3) vouching for an anchor that merely APPEARED with "a respawn anchor is
  in hand" — claims every anchor an OPPONENT places in reach; (4) the original scan-volume freshness
  heuristic. Keep two properties of the current shape: the event's cell goes into
  `pendingPlacements` (armed for `INTENT_WINDOW_TICKS`) rather than straight into `ownAnchors`,
  because the event fires on the PREDICTION and `pruneOwnAnchors` would drop it if the server
  reverts — waiting for the anchor to be standing there also survives a rejected-then-resent
  placement; and pending cells are checked DIRECTLY, never through the anchor scan, since routing
  them through it tied a placement to the scan cube and the previous tick's cube (a step sideways
  drops a cell near the edge of reach, a lowered `Range` drops it outright).
  `AnyDimension` overrules the server-supplied `RESPAWN_ANCHOR_WORKS` stand-down — first thing to
  try when the module is silent on one server only. `Debug` names every rejection/pause, and prints
  "we placed an anchor at …" and "own anchor confirmed at …" unthrottled — that pair splits "the
  client never placed" from "the block never survived", so keep it.
  2026-09-25: `runAction` does not click an anchor while `PendingPlacements.isAwaitingServer` says
  the server has not answered our placement of it (Grim `AirLiquidPlace`, LIVE - see the AutoAnchor
  entry for the mechanism). The gate sits before the swap and the OnTick spoof, so a waiting tick
  sends nothing; the aim and the sequence are kept.
- A port is NOT done when it merely compiles. Checklist:
  1. Read the ORIGINAL source fully; port semantics, not shape. Event mapping:
     echo `EventStartAttack.Pre` (pre-handleKeybinds click) -> raw GLFW press
     handlers (`MouseButtonEvent`/`KeyEvent` vs `mc.options.keyAttack.key`);
     `EventOnAttackEntity` -> `AttackEntityEvent` (fires only for resolved
     in-range attacks - NOT a substitute for startAttack hooks!);
     `EventHandleInput.Post` -> `InputHandleEvent`; `EventTick` -> `GameTickEvent`
     (tick HEAD). Consuming a pending vanilla click: decrement the access-widened
     `KeyMapping.clickCount`.
  2. Use sanctioned LB mechanisms: `BlinkManager` for packet queueing (NEVER
     `packet.handle()` from the netty thread), `RotationManager.setRotationTarget`
     with `MovementCorrection.SILENT` (NEVER assign `RotationManager.currentRotation`),
     `MovementInputEvent` + `DirectionalInput.NONE` to stop the player,
     `sendPacketSilently`, `Slots.*` for inventory, `interaction.useItemOn` /
     `MinecraftAccessor.callStartUseItem()/callStartAttack()` for vanilla actions.
  3. **Grim-safety is a hard requirement** (github.com/GrimAnticheat/Grim):
     verify the WORKING logic doesn't flag - no impossible rotations (aim modulo
     360 / snap deltas; Grim `DuplicateRotPlace` needs settled-rotation gates, see
     `RotationConvergenceTracker`), no input/movement packet mismatches (if the
     server must see "stopped", genuinely stop via MovementInputEvent so position
     deltas and `ServerboundPlayerInputPacket` stay truthful), no interact/swing
     packets while a container is server-side open, no badpackets (wrong sequence
     ids, duplicated use packets, packets from stale connections). Document the
     reasoning in code comments.
  4. Verify each agent-produced port yourself: diff review + spot-check disputed
     lines against the original + `compileKotlin` (and `compileJava` when mixin
     accessors change).
- InventoryMove Bypass/Legit (syntax GuiMove port) lives in
  `movement/inventorymove/InventoryMoveGuiBypass.kt`; movement in GUI MUST go
  through `shouldHandleInputs` (MixinKeyboardInput) - never press KeyMappings
  manually (that broke the module once).

## Shader ESP (ported from `C:\Users\caves\Music\hawen-output`)

ESP module: `features/module/modules/render/esp/ModuleESP.kt`; modes in
`.../esp/modes/`. Modes registered in `ModuleESP.modes` choices list.

Screen-space shader ESP pipeline (all box-silhouette based — silhouettes are
filled `drawBox` boxes rendered into an offscreen RT via
`renderEnvironmentForWorld(renderTarget = ...)`, NOT real entity models):

- `EspShaderMode` ("Shader") — Kawase blur pyramid → bloom glow + optional
  chams. Chams samples the **sharp** silhouette (`texture1` in
  `composite.fsh`), bloom samples the **blurred** pyramid (`texture0`).
- `EspShaderOutlineMode` ("Outline") — single-pass radial dilation outline
  (`shaders/hawenesp/outline.fsh`, pipeline `HawenOutline`, UBO
  `HAWEN_OUTLINE`). Grows the silhouette, discards interior → outline band;
  optional interior `Fill` (chams).
- `KawaseBlurPyramid` in `.../render/hawenesp/`.

Hawen reference implementation (Java, decompiled) lives in
`C:\Users\caves\Music\hawen-output\src\java\dev\sk3d\client\utils\render\shader\`
(`impl/entity/EntityShader.java`, `impl/outline/EntityOutlineShader.java`,
`glsl/*.java` embedded GLSL). Note: hawen renders real entity models into the
FBO; this port uses bounding boxes instead.

### Halo mode and the entity-outline target (2026-09-19)

`esp/modes/EspHaloMode.kt` (`Silhouette = Model`, the default) does NOT draw its own mask - it
reads vanilla's entity-outline target at `DrawOutlinesEvent(MINECRAFT_GLOW)`, fired from
`MixinLevelRenderer.onRenderGlow`. That injection MUST stay AFTER
`OutlineBufferSource.endOutlineBatch()`. It was upstream's `Shift.BEFORE` until this date and that
was a real bug, read out of the 26.1.2 sources and then re-audited by a second agent told to refute
it:

- `OutlineBufferSource` is `MultiBufferSource.immediate(new ByteBufferBuilder(1536))` with NO fixed
  buffers, so every outline batch shares one builder. `BufferSource.getBuffer` draws the pending
  batch when the shared render type changes (`MultiBufferSource.java:59-61`); the final one waits
  for `endBatch` (`:72-77`), and `LevelRenderer.java:710` is its only call site in the client. One
  instruction earlier the target is always short exactly one batch.
- **The outline render type is keyed on `(first texture, cull)`** (`RenderType.java:41-43`), NOT on
  the entity. Players sharing a skin share a batch, and one render type is not one batch - it is
  one batch per contiguous run of submits.
- Player bodies use `RenderTypes::entityTranslucent` (`PlayerModel.java:36`), which blends
  (`RenderPipelines.java:320-328`), so they land in the translucent list and are sorted
  `-lengthSquared()`, farthest first, NEAREST LAST (`ModelFeatureRenderer.java:48`). Capes
  (`RenderTypes.entitySolid`, `CapeLayer.java:63-64`) and armour/elytra (`armorCutoutNoCull`) do
  not blend, so they are drawn in the solid pass, before every body.
- So in a plain fight the missing silhouette was the NEAREST player's body. Not universally: the
  distance sort is per ORDER BUCKET and `FeatureRenderDispatcher.java:70-89` walks
  `submitsPerOrder` (`SubmitNodeStorage.java:33`) ascending, and a translucent model-part, item or
  block submit after the models takes the pending slot instead.
- **Why it read as an elytra/cape bug in game.** An elytra means no chestplate AND it suppresses
  the cape submit outright (`CapeLayer.java:57` requires no WINGS layer). So for such a player the
  torso silhouette comes only from the skin, i.e. from exactly the batch that was being dropped,
  while a chestplate would have covered the same area from the solid pass. The halo showed helmet,
  wings and legs with a hole where the body is.
- Elytra trivia worth keeping: `WingsLayer.java` passes `order = 0` while armour's short overload
  defaults to `1` (`EquipmentLayerRenderer.java:54`), and `getPlayerElytraTexture` falls back to
  the DEFAULT elytra texture, so every elytra wearer without a custom elytra or cape shares ONE
  outline batch.

Two things this does NOT fix, both documented rather than defects:

- Halo composites inside the main pass, while vanilla blends its glow in `doEntityOutline` after
  the whole level render (`GameRenderer.java:473`). So the halo sits UNDER translucent terrain
  (`LevelRenderer.java:721`) and translucent particles (`:728`). Moving it later is what the
  module's KDoc refuses, because that buries nametags, tracers and target renderers drawn in
  `WorldRenderEvent`.
- `GameRenderer.java:378` and `:763` run the same `FeatureRenderDispatcher` again after
  `renderLevel` and never call `endOutlineBatch`, so anything submitted with a non-zero outline
  colour during the hand or HUD passes leaks into the NEXT frame's target. Vanilla passes 0 there;
  do not be the first to pass something else.
## Native ClickGUI ("Leet" design)

Native (OpenGL) ClickGUI. Everything lives in ONE file:
`features/module/modules/render/clickgui/LeetClickGuiScreen.kt` (~2400 lines).
Opened by `ModuleClickGui.kt` when its `Renderer` value is `Native` (default); the other
renderers are Hellhit, Mursultan and System, all native (the Browser one was removed
2026-09-24). **Style guide: `LEET_STYLE.md` in the
same directory — read it before any native-GUI UI work** (colors, geometry,
widgets, animation system, input model, measure/draw contract, checklist).

### Layout

- Fixed centered window (`WINDOW_W 640 x WINDOW_H 340` gui units) on a dark
  page: near-black overlay + edge vignette behind a neutral rounded backdrop.
- Top-left: `LB` logo slot (RAIL x HEADER_H, not a category). Right of it:
  header row = category name + right-aligned description, then search field.
- Below the logo: ONE continuous vertical category strip (`RAIL 30` wide,
  `RAIL_CELL 30` per icon). Active category = lavender-filled Solar icon +
  barely visible cell highlight (`Theme.stripActive`). Icons are white 64px
  PNGs in `src/main/resources/resources/liquidbounce/textures/clickgui/`
  (`combat.png`, `movement.png`, ..., `search.png`), tinted via vertex color,
  loaded lazily by the `Icons` object.
- Modules: two FIXED columns (even index -> left, odd -> right; a module
  never changes column when expanding). Scrollable viewport with scissor.
- All spacing uses `SET_GAP = 6` (between settings, above/below the settings
  block, group-box padding). Card padding `CARD_PAD = 10`, radius `5`.

### Module cards

- 4 fill states (dark -> light): disabled / disabled+expanded / enabled /
  enabled+expanded, each with a hover variant; expanded cards also get a
  `rowBorder` outline. NO checkbox — enabled = lighter card.
- Collapsed card: whole card is a hitbox — LMB toggle, RMB expand, MMB bind
  capture (badge shows `...`; next key OR MOUSE BUTTON binds, ESC cancels the
  capture, Delete/Backspace unbinds). Expanded card:
  same on the head row only; right icon (1/3 bars) collapses.
- Bind badge (key name) sits before the expand icon.

### Settings widgets (`layoutSetting` walker)

- Measure/draw two-pass: every widget takes `ctx: GuiGraphicsExtractor?`;
  `null` = measure only (must return identical heights!). Hits (`Hit` class,
  rebuilt every frame) are only added when `ctx != null`.
- Boolean -> checkbox row. RangedValue -> slider; the value text + track pair
  is one block vertically centered around the row middle (text upper half,
  track lower half — nothing leaks past row bounds).
- Choice/Mode groups -> collapsed dropdown field, expands on **RMB** (LMB
  picks in the list, closes). MultiChoice -> same dropdown showing `k/n`;
  picking toggles and KEEPS the list open.
- Nested groups (Toggleable/Mode/plain ValueGroup) -> subtle rounded group
  box spanning `x-4 .. x+w+4`; children on a narrower column (`x+4`, `w-8`)
  so margins never collapse. The gating toggle row gets its own `groupHead`
  rect of the same width.
- Color -> row (hex + alpha % + swatch; click toggles) + full picker: square
  SV area (corners must stay square), rounded hue + alpha bars
  (`drawSweepRoundedRect`). Picker hue/S/V kept in `pickerState`
  (IdentityHashMap) so hue survives gray colors.
- TEXT values may hold `String`, `Regex`, or string/regex lists — NEVER cast
  to `Value<String>` blindly (Regex caused a crash once). String/Regex ->
  buffered text field (commit on Enter/click-away, invalid regex rejected);
  lists -> read-only `N items` badge.
- Setting descriptions are NOT rendered (they don't exist in practice).
- Do NOT filter modules by `hidden` — that flag only hides from the on-screen
  ArrayList (HUD must appear in the GUI).

### Input & animation invariants

- Sliders/pickers use press-capture + FRAME-DRIVEN drag: every frame
  `extractRenderState` polls `glfwGetMouseButton`; while LMB is held the
  captured `activeSlider`/`pointerDrag` is applied from the current mouse
  position and released the moment the button is up. New presses while a drag
  is captured are ignored (no capture stealing between sliders).
- All animations share ONE easing (`easeInOutCubic`); durations are 100–200 ms
  (micro-interactions 100–150, reveals 150–200). Animated: GUI open/close
  (ESC starts close; `tick()` really closes after `CLOSE_MS`), module
  expand/collapse (progress map, height scissored), card fill + name/desc
  color lerps (universal `watchColor` resolved-color watcher — covers
  hover/enable/expand variants), dropdown/color-picker/group-box reveals
  (height scissor, per-Value progress maps), checkbox toggle, category
  switch content intro + category icon lerp, search focus border,
  bind-capture badge, dropdown item hover, MultiChoice pick feedback,
  expand-icon morph (driven by the card's own eased progress).
  Hits below an animated scissor bottom are NOT clickable (`Hit.clipBottom`).
- Scroll is smoothed (`scrollTarget` + exponential settle ~150 ms) and snaps
  to the device-pixel grid at rest — the ONLY non-easeInOutCubic motion;
  the snap is mandatory so resting text stays pixel-crisp.
- Ctrl+wheel zoom (`uiScale` 0.5–1.6) stays PIXEL-CRISP because the zoom is a
  translate+scale whose translation is snapped to the device pixel grid at
  rest (`curTx/curTy` rounded by `dpr`); `WatermarkFont` glyph quads then land
  exactly on device pixels. Text density = `guiScale * uiScale` (atlases are
  rasterized at the zoomed size). Mouse mapping via `toVirtualX/Y` uses the
  same per-frame `curScale/curTx/curTy`.
- UI state (selected category, expanded set, expand progress, open dropdowns/
  pickers, scroll, search, zoom) persists across GUI reopenings in the
  `private companion object`.

### Virtual GUI category (native-only settings)

- An UNREGISTERED `ModuleCategory("gui")` sentinel is pinned to the bottom of
  the rail (gear icon, asset tag `settings`). Its content pane renders settings
  of the native GUI itself as a `List<GuiSettingEntry>` (a new setting = one
  list entry). The sentinel is never registered in `ModuleCategories` — do NOT
  register it.
- **A SECOND sentinel, `ModuleCategory("Settings")`, sits right above it (2026-10-26,
  the user's ask for "вкладку сеттингс как в hellhit с таргетами").** Its pane is the
  whole `GlobalManager` tree (Targets, Language, Commands, BlinkManager, SwapGate,
  AutoTranslate, ClientChat, RichPresence) drawn by the ordinary `layoutSettings`
  walker — the same thing Hellhit does at `HellhitClickGuiScreen.kt:1043`/`:1062`.
  Until then the native GUI referenced `GlobalManager` nowhere, so those settings
  were unreachable in it (this is the gap the Settings-style rule #4 warns about).
  Notes: its tag is capitalised so the header name and the `CATEGORY_DESC` key need
  no special case (unlike `"gui"`); its icon is the FLAT `mursultan/client-settings`
  because the styled sets hold exactly one gear and the GUI cell already uses it;
  adding a sentinel also means the strip height (`entries.size + 2`) and a
  `categoryIconProgress.putIfAbsent` line — the loop above only seeds registered
  categories. Unlike the GUI pane these are real `Value`s, so every identity-keyed
  animation/open-state map works with no string-key machinery.
- Pane persistence lives in `LeetClickGuiSettings.kt` (same package), stored
  as `clickgui-native.json` in the config root via `fileGson`, lazy-loaded,
  saved on change. Native-only: no ConfigSystem values. First setting: per-category Solar icon style (bold, linear,
  outline, line-duotone, bold-duotone, broken).
- Styled icons resolve to `textures/clickgui/<tag>/<style>.png` with the flat
  `<tag>.png` (bold) as fallback; existence is probed once per styled name via
  `Icons.has` (separate cache — the texture cache negative-caches permanently).

### Gotchas

- Fonts: `WatermarkFont.BRAND`/`TEXT` (bundled Inter) only bake ASCII +
  Cyrillic — fancy glyphs (─ ≡ ▾ ·) render as `?`. Use ASCII in UI strings.
- The file is UTF-8 WITHOUT BOM. Do not edit it with PowerShell
  `Get-Content`/`Set-Content` (PS 5.1 mangles encoding + adds BOM); use
  proper editor tooling only.
- `ClientModule.bindValue` is statically `Value<InputBind>` (not `BindValue`)
  because of its builder chain — type `pendingBind` accordingly.
