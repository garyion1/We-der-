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

## Setup

This environment has no network access to Mojang's/Fabric's Maven repositories, so
none of this has been compiled or run yet. To actually build it:

1. Install a JDK 21 and clone this `mod/` directory as a Fabric Loom project.
2. Check **https://fabricmc.net/develop** for the Yarn mappings / Loader / Fabric API
   build numbers that actually exist for `1.21.11`, and update `gradle.properties` --
   the versions there are best-effort placeholders.
3. `./gradlew genSources` (optional, for readable decompiled MC source in your IDE)
   then `./gradlew build`.
4. Fix whatever doesn't compile. The most likely trouble spots, in order:
   - `exec/BuildExecutor.java` and `gui/BuildOptionsScreen.java` -- written against
     Fabric/Yarn API shapes that are usually stable across 1.20-1.21.x, but every
     release renames a few things and this was written without a compiler in the
     loop.
   - `economy/AuctionHouseBuyer.java`'s use of `DataComponentTypes.LORE` / the
     `GenericContainerScreen` class name (item component API arrived in 1.20.5+
     and is where I have the least certainty).
5. Install Litematica (recommended, not required) to preview/position builds before
   running this mod against them.

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
