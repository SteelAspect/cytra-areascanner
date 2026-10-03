# Cytra Area Scanner

Client-side Fabric mod for **Minecraft 1.21.11** (author: steelaspect) that adds an area scanner to
[Litematica](https://modrinth.com/mod/litematica). It scans every box of the active Litematica area selection
and highlights:

- **Unmovable blocks** (red): obsidian, crying obsidian, reinforced deepslate, end portal (frame),
  end gateway, nether portal, respawn anchor, enchanting table, ender chest, beacon, spawner, trial spawner, vault,
  barrier, light, structure block/void, jigsaw, all command blocks, moving pistons, piston heads and extended
  (sticky) pistons. On top of that list, anything a piston can't push by vanilla's own rules is included:
  indestructible blocks (hardness -1) and push reaction `BLOCK`. Optional (on by default): **all block entities**
  (chests, furnaces, hoppers, signs, ...), detected with `BlockState.hasBlockEntity()`.
  **Bedrock is never in this group**, so world floors and Nether ceilings don't swamp the results; add
  `minecraft:bedrock` to the custom list if you do want it.
- **Liquids** (blue): water and lava, sources and/or flowing, plus waterlogged blocks (checked through the
  block's `FluidState`).
- **Custom blocks**: any block you add by registry ID, each with its own colour and on/off toggle.

A block that matches several groups gets one colour and is counted once, in this order: custom > unmovable > liquid.

Works in singleplayer and on any server; nothing is sent to the server.

## Using it

1. Make an area selection with Litematica (Normal or Simple mode; all boxes of the selection are scanned).
2. Open Litematica's main menu (`M`) and click **Area Scanner** (under *Configuration menu*).
3. Click **Scan**. By default the scan runs on background worker threads (*Background Scanning*): the game
   only copies chunk data, the blocks are checked off-thread. With it off, the scan runs on the main thread over
   several ticks (see *Blocks Per Tick*). Progress shows on the HUD and in the screen's status line.
4. After the first scan the overlay updates live: blocks that change inside the selection are re-checked on the
   next tick, so destroyed or patched matches disappear and new ones appear. Nothing is rescanned every tick.
5. **Stop scanning** clears the overlay and stops the live updates.

Chunks that aren't loaded when the scan reaches them are marked *pending* and scanned as soon as they load.
When a scanned chunk unloads, its matches stay visible as last seen and the chunk is rescanned when it comes back.
The selection is copied when you press Scan; press **Rescan** after editing the selection.

### Screen layout

| Tab / button | What it holds |
|---|---|
| Unmovable & Liquids | Group toggles, sub-toggles (block entities, sources only, waterlogged), colours, custom list on/off |
| Rendering | Through walls, fill/outline on/off and alpha, line width, merge neighbours, render range, max rendered |
| Scanner | Background scanning, blocks per tick (main-thread mode), HUD on/off and position, chat export limit |
| Hotkeys | All hotkeys (unbound by default) |
| Custom List... | Add blocks by ID (autocomplete: Tab or click), add looked-at block, colour picker, toggle, remove |
| Presets... | Save the groups + custom list under a name; load, overwrite, delete |

Scan, Stop, **Export to chat** and **Copy coordinates** buttons sit above the option list.

### Hotkeys (MaLiLib, all unbound by default)

Open scanner GUI, Scan, Stop scanning, Next match, Add looked-at block, Export to chat, Export to clipboard,
plus toggle keys for the HUD and Render Through Walls.

- **Next match** turns the camera to the nearest remaining match; pressing again cycles to the next nearest one.
  The player is never moved.
- **Export to chat** prints coordinates per group (client-side only, limited per group by *Chat Export Limit*).
  **Export to clipboard** copies every match.

### HUD

`Scanner: X unmovable | Y liquids | Z custom`, with a pending-chunk count and a progress bar while scanning.
Toggle it with *Show HUD*.

## Config file

Everything (options, hotkeys, custom scan list and named presets) is stored in `config/cytra-areascanner.json`
and loaded on startup. Two example presets are created on first start: *Flying machine clear* and *Slime check*.

## Building

Requirements: JDK 21 (Fabric Loom 1.17 does not run on newer JDKs).

```bash
./gradlew build
```

The mod jar ends up at `build/libs/cytra-areascanner-<version>.jar`. Put it in your `mods` folder together with
Fabric API, Litematica and MaLiLib for 1.21.11.

If your default Java isn't 21, point Gradle at a JDK 21 first, e.g. `JAVA_HOME=/path/to/jdk-21 ./gradlew build`.

### In-game tests

```bash
./gradlew runClientGameTest
```

Starts a client, creates a test world and checks scanning through a two-box selection, live updates, the custom
list, settings changes, pending chunks, next match and the exports, in both scanning modes. Also takes
screenshots of every menu and runs a large-scan benchmark. Screenshots land in
`build/run/clientGameTest/screenshots/`.

## Versions

| | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.5 |
| Fabric API | 0.141.6+1.21.11 |
| Litematica | 0.26.16 (required) |
| MaLiLib | 0.27.20 (required) |
| Mappings | Official Mojang |
| Loom / Gradle | 1.17.21 / 9.8.0 |

## Notes

- Matches are stored in a `Long2ObjectOpenHashMap` keyed by `BlockPos.asLong()`, plus a per-section index.
- Chunk sections whose block palette can't contain a match (e.g. only stone and dirt) are skipped without
  reading their blocks.
- Background scanning: palettes are copied on the main thread, scanned by worker threads, and the results applied
  on the main thread. Blocks that change while a chunk is being scanned are re-checked against the live world,
  and results from an outdated scan (settings changed, chunk reloaded, scan stopped) are discarded.
- The overlay geometry is cached per 16x16x16 section; a change only rebuilds the affected sections, within a
  small time budget per frame.
- The overlay uses MaLiLib's render pipelines, the same ones Litematica's overlays use. Neighbouring matches of
  the same colour are merged into one shape (*Merge Neighbours*), which keeps large liquid areas cheap to draw.
- Benchmark (`PerformanceGameTest`): a 1.3M block selection with ~104k water blocks scans in 3 ticks in the
  background (10 ticks on the main thread) with no slow ticks or FPS drop; live updates still apply within a tick.
- For very large match sets, lower *Render Range* / *Max Rendered Blocks* if the frame rate drops.
