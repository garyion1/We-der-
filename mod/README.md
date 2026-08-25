# Auto Litematica Builder

A Fabric client mod for **Minecraft 1.21.11** that builds whatever schematic
you have placed in **Litematica** for you: it plans a placement order, walks
(and pearl-climbs) to each spot, restocks missing materials from the server's
auction house, and places blocks with human-paced, human-shaped input instead
of instant robotic actions.

No file picker, no coordinates to type: load and position the schematic in
Litematica the normal way, and this mod follows it automatically. Requires
**Litematica** installed alongside it (recommended dependency, not bundled).

Press **`[`** in game to open the menu.

---

## Status: builds, but has never been run

Every commit here is compiled against real 1.21.11 mappings by CI, so it
compiles and packages into a loadable jar. **Nobody has ever launched it.** No
block has been placed, no path walked, no purchase made. Treat it as a first
draft that happens to compile.

Try it in **singleplayer** first.

## Before using it on a server

- **Most servers' rules prohibit automation** — auto-builders, macros, and
  auto-buyers alike — whether or not anti-cheat catches them. Checking your
  server's rules is on you.
- **Auto-buying is off by default** (`Buying` tab) because it spends real
  in-game currency with nobody watching.
- The human-like motion makes the builder *look* natural rather than robotic.
  It is **not** designed against any particular anti-cheat and spoofs nothing.
  A server inspecting packets, or simply noticing an account building for nine
  hours, is unaffected by it.

---

## The menu (`[`)

| Tab | Contains |
|---|---|
| **Build** | live status of what Litematica has placed, order (6 modes), layer direction, scaffold block, break wrong blocks, skip fluids / block entities, verify pass, retries |
| **Move** | pearl climbing + reserve, jumping, sprint, max fall, reach, pathfinding effort, sneak near edges, avoid hazards, return home |
| **Timing** | pace, delay scale, randomness, fatigue, breaks (on/interval/length/look around) |
| **Buying** | auto-buy, `/ah` command, price regex, price limits, page scanning, confirm click, buy extra, out-of-materials policy |
| **Safety** | health floor, hunger floor, player-nearby, inventory full, time limit, consecutive-failure cutoff |
| **Status** | live state, progress, skipped count, fatigue — and the approve/decline buttons for an expensive purchase |

### Build orders

- **Layer by layer** — bottom-up (or top-down), nearest-first within a layer.
- **Nearest block first** — least walking.
- **Shell first** — outer surfaces, then the interior.
- **Layers + auto-scaffold** — drops temporary support columns under floating
  sections so they can be reached.
- **One material at a time** — works through a single block type before moving
  on. Keeps the hotbar stable.
- **Random** — unstructured fill.

### Setting up `/ah` for your server

This is the part most likely to need tuning, because every server's auction GUI
differs. Open `/ah` by hand first and look at how a listing shows its price:

- Default pattern `\$\s*([0-9][0-9,.]*)` matches `$1,234`.
- `Price: 1234 coins` → use `Price: ([0-9,.]+)`.
- Group 1 must be the number. Price is compared **per item**, so a 64-stack at
  320,000 counts as 5,000 each.

Then: if buying takes two clicks on your server, turn on **Needs confirm click**.

**Price limits** are two separate numbers:
- *Buy without asking under* (default 100,000/item) — spent unattended.
- *Never buy over* (default 5,000,000) — refused outright, always.
- Between the two, the build pauses and waits for you to approve on the Status
  tab. A listing whose price can't be parsed is never bought — unreadable
  counts as too expensive, not as free.

With **Check every page** on, it surveys every page of results, then re-runs
the search and pages back to whichever page held the cheapest listing before
buying. (Only the page on screen is clickable, which is why it needs two
passes.)

---

## Building the jar

CI builds it on every push — grab the artifact from the
[Actions tab](https://github.com/garyion1/We-der-/actions) rather than building
locally if you just want the jar.

To build it yourself you need **JDK 21**:

```bash
cd mod
./gradlew build
```

Output lands in `build/libs/`. Use `./gradlew`, not a system `gradle`: the
wrapper pins Gradle 9.2, and Loom 1.14 publishes only a Gradle 9.2 variant.

Versions (from Fabric's 1.21.11 release and Modrinth), in `gradle.properties`:
Loom `1.14`, Yarn `1.21.11+build.6`, Loader `0.19.3`, Fabric API
`0.141.1+1.21.11`. Note 1.21.11 is the **last** version Fabric ships Yarn
mappings for; a future port means migrating to Mojang mappings, which renames
most of the Minecraft classes this touches.

## Installing

1. [Fabric Loader](https://fabricmc.net/use/installer/) for 1.21.11
2. [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`
3. This jar in `mods/`
4. Litematica is *recommended, not required* — useful for previewing and
   positioning a build. This mod reads `.litematic` files itself.

---

## How it fits together

```
schematic/LitematicaBridge        Reflectively reads Litematica's currently
                                    selected placement -- file, origin, rotation,
                                    mirror. See "Following Litematica" below for
                                    why this is reflection rather than a
                                    compile-time dependency.
schematic/LitematicaSync          Polls the bridge (once a second, paused while
                                    a build is running) and reloads the source
                                    when the placement changes.
schematic/RawLitematicReader      Parses .litematic (NBT + the bit-packed
                                    BlockStates array) directly, independent of
                                    Litematica. Format cross-checked against
                                    Baritone's implementation and litemapy.
                                    Applies the placement's rotation/mirror to
                                    both positions and block states.
planner/BuildPlanner              Diffs target vs world and orders placements so
                                    every block has a real neighbour to click
                                    against by the time its turn comes. O(n) via
                                    an incrementally-maintained frontier, not a
                                    rescan-everything loop.
nav/PathFinder                    Grid A* with jump/drop/pearl-climb edges,
                                    hazard-aware, fall-height limited.
nav/HumanMotion                   Aim easing, tremor, overshoot, fatigue, and
                                    all the timing distributions.
economy/AuctionHouseBuyer         Two-pass cheapest-listing search, price limits,
                                    approval flow.
exec/BuildExecutor                The tick state machine tying it together:
                                    navigate -> align -> get item -> break ->
                                    place -> verify -> dwell -> rest.
config/ConfigStore                Saves settings to config/autobuilder.properties.
gui/BuildOptionsScreen            The [ menu.
```

## Following Litematica

`LitematicaBridge` reads Litematica's selected placement through reflection
instead of a compile-time dependency, and tries a short list of plausible
method names at each step (`getSelectedSchematicPlacement`/`getSelectedPlacement`,
etc). Two reasons:

- Litematica publishes no stable API for other mods, and its internals move
  between versions and forks (upstream `maruohon/litematica` vs. the
  `sakura-ryoko` fork some players run instead). A hard dependency that fails
  to resolve would break this mod's build outright; reflection degrades to a
  clear "couldn't read Litematica's placement" log line instead.
- Only two things are actually needed from Litematica -- the `.litematic` file
  and where/how it's placed (origin, rotation, mirror). Block data is then read
  by this mod's own parser, which is the part that's actually been cross-checked
  against independent implementations.

If nothing loads: check the game log for `Couldn't read Litematica's
placement` -- that means the method names this mod tries no longer match your
Litematica version, and `LitematicaBridge.java`'s candidate name lists need a
new entry.

**Rotation/mirror caveat**: the transform assumes Litematica pivots a rotated
or mirrored placement at its origin corner. If a version instead pivots at the
bounding-box center, a rotated build will land offset from the real one. Test
with an unrotated placement first.

## Known limitations

- **Block entities are placed empty.** Chest contents and sign text aren't read
  from the schematic (`Entities`/`TileEntities` are skipped). There's a toggle
  to leave them out entirely.
- **Pearl aiming is approximate** — simulated physics (constant speed, gravity,
  drag) that won't exactly match server behaviour. Expect to tune it.
- **In-memory-only placements can't be followed** — if you built a placement in
  Litematica's editor but never saved it to a `.litematic` file, there's nothing
  on disk for this mod to read.
