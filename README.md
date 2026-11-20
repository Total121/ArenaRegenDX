# ArenaRegenDX

High-performance arena snapshot and restore for **Paper 1.21.8+** and **Folia 1.21.8+**.

**Author:** DravoraX  
**Version:** 2.2.0  
**License:** Licensed exclusively to DravoraX. All rights reserved.

---

## What it does

Capture a cuboid (a duel pit, a whole warzone, a practice map), then restore it after a match without tanking TPS.

Exclusion zones keep a spawn (or any volume) inside that cuboid **unrestored**. Typical setup: select the warzone, create the arena, wand-select the spawn island, `/ardx exclude add warzone spawn`. Gold dust is the area that will be restored; red dust is protected.

## Quick start

```text
/ardx wand                         get the selection wand
left-click / right-click           pos 1 (lime) and pos 2 (aqua)
/ardx create warzone               create the arena and capture its snapshot
/ardx wand                         select the spawn in the middle
/ardx exclude add warzone spawn    that volume is never restored
/ardx exclude show warzone         gold = restored, red = protected
... fight ...
/ardx regen warzone                restore the warzone, leave the spawn alone
/ardx autoreset warzone 10m        or restore it automatically every 10 minutes
```

Sphere around you: `/ardx exclude sphere warzone spawn 16`  
Vertical column: `/ardx exclude cylinder warzone spawn 12`

## Restore engine

- Pre-resolved block palette; unchanged voxels are detected by comparing internal block states by identity, so delta skip costs one lookup per block
- Internal `Level.setBlock` without physics when available (Bukkit fallback otherwise); block entities are cleared before replacement so containers never spill items
- Dirty-voxel tracking: automatic restores rewrite only what changed. Tracking is only trusted after a snapshot or full restore, and is bounded in memory
- Millisecond + block budget per tick, scaled by TPS; a single tick never spends more than 25 ms restoring
- Paper: restores run inline on the main thread and span as many chunks as the budget allows; unloaded chunks are loaded asynchronously
- Folia: several chunks per tick, each on its own region thread; entities are restored per region
- Snapshot capture copies chunk data on the region thread and parses it off-thread
- Block entities via NBT (chests keep components / loot), plus armor stands, item frames (with their facing), paintings and display entities
- Safe mode moves players and mobs that end up inside a block to the nearest free space
- Modes: `instant`, `chunk_by_chunk`, `layer_by_layer` (bottom-up), `animated` (expanding shells from the centre, one shell per tick)
- `/ardx cancel` and a boss bar while a restore runs; a watchdog aborts a restore that stops making progress

## Commands

Main command: `/ardx` (aliases: `arenaregendx`, `arenaregen`, `sregen`, `sar`)

| Command | Purpose |
|---------|---------|
| `/ardx wand` | Selection wand (dust overlay) |
| `/ardx create <name> [reset] [async]` | Create an arena from the selection and capture it |
| `/ardx list` / `/ardx info <arena>` | Arenas, status, snapshot, timers, dirty-voxel count |
| `/ardx regen <arena> [mode]` | Restore (skips exclusions) |
| `/ardx cancel [arena]` | Abort a running restore (all if no arena) |
| `/ardx save <arena>` | Recapture the snapshot |
| `/ardx snapshots <arena>` | List stored snapshots |
| `/ardx load <arena> <id>` | Make an older snapshot the restore point |
| `/ardx autoreset <arena> <time\|off>` | Timed resets (`300`, `5m`, `1h`, `off`) |
| `/ardx speed <global\|arena> [value]` | `slow` / `normal` / `fast` / `max` / `inherit` / 10-500 % |
| `/ardx setspawn <arena> [clear]` | Teleport players here when a restore starts |
| `/ardx exclude add\|sphere\|cylinder\|remove\|list\|show\|hide` | Exclusion zones |
| `/ardx preview <arena>` | Show bounds in dust |
| `/ardx rename <old> <new>` / `/ardx delete <arena> confirm` | Manage arenas |
| `/ardx gui` / `/ardx status` / `/ardx reload` / `/ardx debug` | Menus and diagnostics |

## Permissions

`arenaregendx.*` (op) grants everything except `arenaregendx.bypass`.
Nodes: `use`, `create`, `delete`, `save`, `load`, `regen`, `cancel`, `gui`, `wand`, `reload`, `debug`, `admin` (implies every subcommand), `bypass` (restores you start skip safe mode; default false).

## PlaceholderAPI

Global: `%ardx_arenas%` `%ardx_active%` `%ardx_queue%` `%ardx_jobs%` `%ardx_tps%` `%ardx_tps_5m%` `%ardx_tps_15m%` `%ardx_ticktime%` `%ardx_budget%` `%ardx_memory%` `%ardx_memory_max%`

Per arena: `%ardx_status_<arena>%` `%ardx_progress_<arena>%` `%ardx_reset_<arena>%` `%ardx_reset_formatted_<arena>%` `%ardx_regenerating_<arena>%` `%ardx_size_<arena>%` `%ardx_blocks_<arena>%` `%ardx_snapshot_<arena>%`

## Configuration

| File | Contents |
|------|----------|
| `config.yml` | Wand, WorldEdit preference, explosion / fire / piston auto-regen, safe mode |
| `performance.yml` | Tick budgets, TPS thresholds, speed, delta skip, dirty tracking, Folia parallelism |
| `storage.yml` | Compression, snapshots kept per arena, snapshot cache memory budget |
| `messages.yml` | Every chat / action-bar message (MiniMessage, `<prefix>` supported) |
| `particles.yml` / `sounds.yml` / `gui.yml` | Visuals and audio |

All files are written atomically. Keys added by an update fall back to the bundled defaults, so older config files keep working.

## Developer API

```java
ArenaRegenAPI api = ArenaRegenAPI.get();
api.saveSnapshot("duel1");                                  // capture + make it the restore point
api.regen("duel1", RegenMode.INSTANT, true);                // full restore
api.regenChanged("duel1", RegenMode.INSTANT, true);         // changed voxels only when tracked
```

Events: `ArenaRegenCompleteEvent`, `SnapshotSavedEvent` (main thread on Paper, global region on Folia).

## Build

```bash
./gradlew build
```

Output: `build/libs/ArenaRegenDX-2.2.0.jar` (unit tests run as part of the build). Requires JDK 21+; Gradle provisions a toolchain if needed.
