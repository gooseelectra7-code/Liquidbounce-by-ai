# Aim recording format v5

The format the aim recorders write and `deeplearn/clone` trains from. Shared by the in-client
`CloneRecorder` and the standalone 1.20.1 `AimRecorder` mod, which must agree byte for byte.

v4 is frozen and stays readable; v5 is a superset with one structural change meant to make a v6
unnecessary.

## The rule that matters more than any field

**Every per-tick field is optional, and the sequence header lists which fields the recording actually
contains.**

In v4 an absent field deserialises to a JVM default, so a recording made before a field existed is
indistinguishable from one where the field was genuinely zero. That makes any later addition a
silent-corruption hazard: mix an old profile with a new one and half the data carries a real value
while the other half carries a constant, with nothing to report it.

v5 settles this once:

- every optional per-tick field is a **boxed / nullable** type, so "absent" survives parsing
- the header carries `f`, the list of per-tick keys this recording is guaranteed to populate
- a trainer that wants field X checks `f` and either drops the profiles without it or trains without
  it — **never** zero-fills across sources

With that in place, adding a field later is a header change rather than a format break. Old data stays
usable for everything it can support, and the failure mode is a refusal instead of a wrong number.

## Sequence header

| key | type | meaning |
|---|---|---|
| `v` | int | format version — 5 |
| `f` | string[] | per-tick keys populated in this recording (see the rule above) |
| `prof` | string | profile name |
| `g` | double | degrees per mouse count: `(sens*0.6+0.2)^3 * 8 * 0.15`, with the client's float constants |
| `sens` | double | raw sensitivity setting |
| `ts` | long | wall-clock ms at sequence start |
| `rec` | string | recorder name and version, e.g. `aimrecorder/1.1.0` |
| `uid` | string | random id for this recording, so duplicates survive being collected twice |
| `mc` | string | Minecraft version it was recorded on |
| `set` | object | recorder settings in force: target range, FOV limit, combat-window lead and trail, only-around-combat |
| `brk` | string | why the sequence ended: `world`, `death`, `screen`, `sensitivity`, `disable`, `end` |
| `t` | tick[] | the ticks |

`rec`, `mc` and `set` exist because datasets get collected from several people on different builds.
Without them a surprising distribution cannot be traced back to the recorder that produced it.

## Per-tick fields

### Carried over from v4, unchanged

`y` `p` own rotation **before** this tick's movement · `dx` `dy` movement in whole mouse counts ·
`ty` `tp` angle from the eyes to the tracked target's box centre · `d` distance to it · `h` target
hurt time · `tw` `tht` target hitbox width and height · `sf` `ss` own movement in the yaw-relative
frame · `c` crosshair raytrace hits the tracked target · `a` an attack packet went out · `e` a target
was tracked · `cd` attack cooldown · `sp` `sn` `og` `ui` sprinting, sneaking, on ground, using item ·
`kb` knockback packet received · `dmg` damage packet received · `hp` own health · `tc` targets in
range · `pg` ping · `st` session tick.

The tick convention is unchanged and is the thing most likely to be got wrong: own rotation and all
target geometry are sampled at the same instant, **before** the movement, and `dx`/`dy` are the step
from that snapshot to the next one.

### New in v5

Each of these is here because it cannot be recovered afterwards from what v4 stores.

**Identity — tells "switched target" apart from "fell behind"**

| key | type | meaning |
|---|---|---|
| `tid` | int? | entity id of the tracked target |
| `aid` | int? | entity id the attack packet was aimed at; absent when no attack |

v4 has no identity at all, so when the tracker changes target the angle jumps and looks exactly like
the target having moved. Every statistic built on target angular velocity is contaminated by it. A
heuristic recovers switches at only about 90% precision, while the live path knows them exactly —
a train/serve skew by construction.

**Where the player was actually looking — the highest-value addition**

| key | type | meaning |
|---|---|---|
| `lkt` | int? | what the crosshair ray hit: 0 nothing, 1 block, 2 entity |
| `lid` | int? | entity id hit, when `lkt` is 2 |
| `lkd` | float? | distance to whatever was hit |

v4 records the angle to the target *the recorder picked*. When the player is fighting someone else, or
looking at a block, the recording says "150° off target" while the action says "held still" — and a
model trained on that learns to hold still with a target behind it, which is exactly the failure seen
in play. With `lid` the same tick reads honestly: on target, just a different one.

This is also what makes hindsight goal relabelling possible — training on "the player was converging
on *their* goal" rather than "the player ignored ours" — which keeps every motor pattern in the data
instead of discarding a fifth of it.

**Raw geometry — separates own motion from the target's**

| key | type | meaning |
|---|---|---|
| `ex` `ey` `ez` | float? | own eye position |
| `bx` `by` `bz` | float? | tracked target's box centre |

`ty`/`tp`/`d` depend on both endpoints, so the target's apparent angular velocity is really the sum of
its motion and ours, and the two cannot be separated afterwards. From raw positions both are
recoverable exactly, along with any other geometry a later feature wants. `ty`/`tp`/`d` stay as well:
they are what the client computed at that instant, and re-deriving them can differ in the last digits.
Redundancy is cheaper than a mismatch.

**The opponent as a threat**

| key | type | meaning |
|---|---|---|
| `gy` `gp` | float? | tracked target's own yaw and pitch |
| `gsp` | bool? | target sprinting |

Whether the opponent is facing you changes how a person aims, and it cannot be inferred from your own
rotation.

**Other entities — why the crosshair went somewhere else**

| key | type | meaning |
|---|---|---|
| `ne` | object[]? | up to three nearest other candidates, each `{ i: id, y: yaw, p: pitch, d: distance }` |

Target switching is currently observable only after the fact. With the alternatives recorded, the
choice itself becomes modellable.

**Held equipment**

| key | type | meaning |
|---|---|---|
| `it` | string? | held item id, e.g. `minecraft:diamond_sword` |
| `bl` | bool? | blocking or raising a shield |

Aiming with a sword, with a bow and with a block in hand are different behaviours, and `ui` alone
cannot tell them apart.

**Timing**

| key | type | meaning |
|---|---|---|
| `ms` | int? | wall-clock milliseconds since the previous recorded tick |

Catches lag spikes and frame stalls. A session recorded through a stall is not the player's aim and
should be excluded rather than learned from; v4 gives no way to notice.

## Deliberately not recorded

Anything derivable: angular velocities, gains, error magnitudes, autocorrelations, path efficiency,
run lengths, on-target fractions. They are properties of the analysis, they change as the analysis
changes, and freezing them into files only guarantees they will be wrong later. The test for inclusion
is **irrecoverability**, not usefulness.

Also excluded: screen contents, chat, and keystrokes beyond the movement axes already present. `rec`
and `uid` identify the *recording*; a player name or UUID is not recorded, and if one is ever wanted
it must be opt-in and visible in the recorder, because these files are shared between people.

## Size

The new fields add roughly 60-80 bytes per tick before pretty-printing. A 100 000-tick session grows
from about 13 MB to about 21 MB. Set against asking several people to record again, that is not a
consideration.

## Migration

- v4 files stay readable and trainable. They simply declare a smaller `f`.
- A model's metadata records which fields its features required, so a later run cannot silently train
  on a profile that lacks one.
- Mixing v4 and v5 profiles is allowed **only** for features every source declares. The trainer refuses
  otherwise rather than zero-filling.
- The standalone recorder and `CloneRecorder` must be updated together, and a recording made by either
  must compare byte for byte against the other on the shared fields.
