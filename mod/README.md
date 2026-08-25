# Auto Litematica Builder

A Fabric client mod for **Minecraft 1.21.11** that builds a `.litematic` schematic
for you: it plans a placement order, walks (and pearl-climbs) to each spot,
restocks missing materials from the server's auction house, and places blocks
with human-paced, human-shaped input instead of instant robotic actions.

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
| **Build** | schematic file, origin, order (6 modes), layer direction, scaffold block, break wrong blocks, skip fluids / block entities, verify pass, retries |
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
schematic/RawLitematicReader      Parses .litematic (NBT + the bit-packed
                                    BlockStates array) directly, independent of
                                    Litematica. Format cross-checked against
                                    Baritone's implementation and litemapy.
planner/BuildPlanner              Diffs target vs world and orders placements so
                                    every block has a real neighbour to click
                                    against by the time its turn comes.
nav/PathFinder                    Grid A* with jump/drop/pearl-climb edges,
                                    hazard-aware, fall-height limited.
nav/HumanMotion                   Aim easing, tremor, overshoot, fatigue, and
                                    all the timing distributions.
economy/AuctionHouseBuyer         Two-pass cheapest-listing search, price limits,
                                    approval flow.
exec/BuildExecutor                The tick state machine tying it together:
                                    navigate -> align -> get item -> break ->
                                    place -> verify -> dwell -> rest.
gui/BuildOptionsScreen            The [ menu.
```

## Known limitations

- **Block entities are placed empty.** Chest contents and sign text aren't read
  from the schematic (`Entities`/`TileEntities` are skipped). There's a toggle
  to leave them out entirely.
- **Pearl aiming is approximate** — simulated physics (constant speed, gravity,
  drag) that won't exactly match server behaviour. Expect to tune it.
- **No config persistence.** Settings reset each launch.
- **The planner is O(n²)** — fine for a house, slow for tens of thousands of
  blocks. Needs a spatial index for very large schematics.
- **Block-entity rotation/state** beyond basic block properties is untested.
