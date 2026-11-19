# ArenaRegenDX

High-performance arena snapshot and restore for **Paper 1.21.8+** and **Folia 1.21.8+**.

**Author:** DravoraX  
**Version:** 2.1.0  
**License:** Licensed exclusively to DravoraX. All rights reserved.

---

## What it does

Capture a cuboid (a duel pit, a whole warzone, a practice map), then restore it after a match without tanking TPS.

Exclusion zones let you keep a spawn (or any volume) in the middle of that cuboid **unrestored**. Typical setup: select the warzone, create the arena, wand-select the spawn island, `/ardx exclude add warzone spawn`. Gold dust is the area that will regen. Red dust is protected.

## Quick start

```text
/ardx wand                         get the selection wand
left-click / right-click           pos 1 (lime) and pos 2 (aqua)
                                   gold + cyan dust shows the volume
/ardx create warzone               snapshot the selection
/ardx wand                         select the spawn in the middle
/ardx exclude add warzone spawn    that volume will never restore
/ardx exclude show warzone         gold = regen, red = protected
... fight ...
/ardx regen warzone                restore the warzone, leave spawn
```

Sphere around you: `/ardx exclude sphere warzone spawn 16`  
Vertical column: `/ardx exclude cylinder warzone spawn 12`

## Restore engine

- Pre-parsed BlockData palette (no string parse per block)
- NMS `Level.setBlock` when Paper CraftBukkit is present (Bukkit fallback otherwise)
- Dirty-voxel tracking: after a match, restore only blocks that actually changed
- Delta skip: identical blocks and air-to-air are not written
- Millisecond tick budget so the server never spends a full tick inside restore
- Folia: multiple chunks per tick, each on its region thread
- Tile entities via snapshot NBT (chests keep components / loot)
- Display entities (item / block / text) plus armor stands, frames, paintings
- Physics off
- `/ardx cancel` and a boss bar while restore runs

## Commands

Main command: `/ardx` (aliases: `arenaregendx`, `arenaregen`, `sregen`, `sar`)

| Command | Purpose |
|---------|---------|
| `/ardx wand` | Selection wand (dust overlay) |
| `/ardx create <name>` | Create arena + snapshot |
| `/ardx exclude add <arena> <zone>` | Cuboid exclusion from wand |
| `/ardx exclude sphere <arena> <zone> [r]` | Sphere at your feet |
| `/ardx exclude cylinder <arena> <zone> <r>` | Full-height cylinder |
| `/ardx exclude show <arena>` | Dust preview |
| `/ardx regen <name> [mode]` | Restore (skips exclusions; dirty voxels when tracked) |
| `/ardx cancel [name]` | Abort a running restore |
| `/ardx setspawn <arena>` | Teleport players here on regen |
| `/ardx preview <arena>` | Show bounds |
| `/ardx gui` | Menus |
| `/ardx status` | TPS, budget, queue |

## Permissions

`arenaregendx.*` (op). Individual nodes: `create`, `delete`, `save`, `load`, `regen`, `gui`, `wand`, `reload`, `debug`, `bypass`, `admin`.

## PlaceholderAPI

`%ardx_arenas%` `%ardx_active%` `%ardx_queue%` `%ardx_tps%` `%ardx_budget%` `%ardx_memory%`

## Build

```bash
gradlew.bat jar
```

Output: `build/libs/ArenaRegenDX-2.1.0.jar`

Requires JDK 21+. Gradle will provision a toolchain if needed.
