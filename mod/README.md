# Auto Litematica Builder

A Fabric client mod for Minecraft 1.21.11 that builds a loaded `.litematic` schematic
itself: plans a sensible placement order, walks/pearl-climbs to each spot, restocks
missing materials (optionally by shopping a server's `/ah`), and places blocks with
human-paced, human-shaped input instead of instant/robotic actions.

Press **`'`** (apostrophe) in-game to open the build menu.

## Before you turn this on somewhere that matters

This automates real gameplay: movement, block placement, and (if you enable it)
purchases against a live in-game economy. Read this before you use it anywhere but
singleplayer:

- **Most multiplayer servers' rules prohibit macro/bot play**, including
  auto-builders and auto-buyers, whether or not the server has active anti-cheat.
  Using this on a server you don't own/administer without checking its rules risks
  a ban. That's on you to check, not something this README can clear for you.
- **`autoBuyMaterials` is off by default** (`BuilderConfig.autoBuyMaterials`) for
  exactly that reason — it sends real purchase commands against a real economy.
  Only turn it on somewhere you've confirmed automation is allowed.
- The "human-paced" motion (`nav/HumanMotion.java`) exists to make the build look
  natural rather than robotic — randomized timing and eased look/turn speed instead
  of instant snapping. It is not designed to fingerprint or specifically evade any
  named anti-cheat's detection; if a server's anti-cheat flags it, that's the
  server correctly doing its job.

## How it fits together

```
schematic/RawLitematicReader.java     Parses .litematic files directly (NBT + the
                                        bit-packed BlockStates array), independent of
                                        Litematica being installed. Verified against
                                        the format both Baritone's own litematica
                                        integration and the litemapy Python library
                                        independently reimplement.
schematic/LitematicFileSchematicSource Wraps the reader with a chosen file + world
                                        origin (type these into the GUI, or read them
                                        off Litematica's own ghost-preview placement
                                        if you use Litematica to position the build
                                        first -- Litematica is recommended, not
                                        required).
planner/BuildPlanner.java             Diffs target vs. world, orders placements so
                                        every block has a real neighbor to click
                                        against by the time its turn comes (bottom-up
                                        / nearest-first / outside-in / scaffold-aware
                                        strategies), and tallies materials needed.
nav/HumanMotion.java                  Timing + look-easing helpers: randomized
                                        dwell/reaction delays, eased turning with
                                        slight overshoot, occasional hesitation.
nav/PathFinder.java                   Grid A* with an extra pearl-climb edge type,
                                        used only when there's no walkable route up.
economy/AuctionHouseBuyer.java        Sends the shop command, scans the resulting
                                        GUI's item lore for a price via a configurable
                                        regex, buys the cheapest listing under your
                                        price cap. Entirely server-specific -- tune
                                        BuilderConfig.auctionPriceRegex /
                                        auctionRequiresConfirmClick to match your
                                        server's actual /ah GUI.
exec/BuildExecutor.java               The tick-driven state machine tying all of the
                                        above together: plan -> navigate -> align ->
                                        get item (buy if needed) -> place -> dwell ->
                                        repeat.
gui/BuildOptionsScreen.java           The ' menu: pick strategy/pace, toggle pearls
                                        and auto-buy, start/pause/stop.
```

## Building

**This has never been compiled.** It was written in an environment whose network
policy blocks `maven.fabricmc.net`, `libraries.minecraft.net` and
`piston-meta.mojang.com`, so Loom could never resolve Minecraft to compile against.
Everything below the Gradle config is therefore unverified against a real compiler --
expect to fix things. See "Expect to fix" below.

Requirements: **JDK 21** (not 22+ -- Loom targets 21 for this MC version).

```bash
cd mod
./gradlew build
```

The Gradle wrapper is included, pinning Gradle 8.14 -- use `./gradlew`, not a
system-installed `gradle`. Homebrew currently installs Gradle 9.x, which trips
over Loom's own deprecated API usage.

Output jar lands in `build/libs/` (ignore the `-sources` one; the plain
`auto-litematica-builder-0.1.0.jar` is the mod). Drop it in `.minecraft/mods/`
alongside Fabric Loader and Fabric API.

Versions in `gradle.properties` are taken from Fabric's 1.21.11 release
announcement (2025-12-05) and Modrinth's Fabric API listing:
Loom `1.14`, Loader `0.18.1`, Fabric API `0.141.1+1.21.11`. Yarn resolves
dynamically via `1.21.11+build.+`. Note 1.21.11 is the **last** version Fabric
ships Yarn mappings for -- a future port means migrating this code to Mojang
mappings (`loom.officialMojangMappings()`), which renames most of the Minecraft
classes this mod touches.

### Expect to fix

Roughly in descending order of how likely they are to break:

1. `economy/AuctionHouseBuyer.java` -- `DataComponentTypes.LORE` and
   `GenericContainerScreen`. The item-component API landed in 1.20.5 and has moved
   since; this is the least certain file in the project.
2. `exec/BuildExecutor.java` -- the largest surface area of Minecraft API calls
   (`interactionManager`, `getInventory().selectedSlot`, key-binding presses).
   `selectedSlot` in particular became a method rather than a field at some point.
3. `gui/BuildOptionsScreen.java` -- widget builder signatures shift between releases.

The parts *not* in that list -- `RawLitematicReader`, `BuildPlanner`, `PathFinder`,
`HumanMotion` -- are mostly plain Java and don't depend much on Minecraft's API
surface, so they're the most likely to be correct as written.

Install Litematica (recommended, not required) to preview and position builds
before running this against them.

## Known limitations / not yet handled

- Chests, signs, and other block entities: the schematic reader only places block
  *shapes*, not container contents or sign text (`Entities`/`TileEntities` in the
  `.litematic` NBT aren't read yet).
- Non-`SCAFFOLD_AWARE` strategies don't build temporary scaffolding under floating
  sections -- an unsupported block gets flagged as skipped rather than placed.
- Pearl-climb aiming (`BuildExecutor.solvePearlPitch`) is a numerically-simulated
  approximation of pearl physics (constant speed/gravity/drag), not exact server
  physics -- expect to tune it against real in-game throws.
- No persistence yet for `BuilderConfig` -- settings reset each launch. Wire up
  malilib's JSON config helpers (if you're depending on it) or a small Gson file
  in `BuilderConfig` if you want them to stick.
- The planner is a greedy O(n²) nearest-neighbor pass -- fine for a house, will get
  slow on schematics in the tens of thousands of blocks; add a spatial index
  (e.g. bucket by chunk) if you build something that large.
