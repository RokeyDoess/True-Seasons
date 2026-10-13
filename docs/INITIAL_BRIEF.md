# True Seasons – Initial Plugin Brief

Build a Paper plugin called **True Seasons** for Minecraft 26.3+.

The goal is a cohesive, lightweight seasons plugin focused on **real-world synchronized seasons** and **full-world consistency**, without RPG systems, farming overhauls, temperature mechanics, holidays, custom dimensions, or unrelated gameplay systems.

## Core design goal

The plugin must avoid the biggest problem with existing season mods:

**Seasonal changes must eventually update the entire already-generated world, not only chunks that players happen to load.**

When a season changes, the plugin should run a controlled world migration across all existing chunks.

It must:

- never generate new chunks during seasonal migration
- gradually process every existing generated chunk
- survive server restarts
- resume incomplete migrations
- throttle itself to protect TPS
- prioritize chunks near online players
- update stale chunks immediately if a player enters one
- eventually reach 100% completion even if nobody revisits remote areas
- keep all persistent world changes reversible
- allow the plugin to be cleanly removed without leaving seasonal artifacts behind

Think of a season transition as a **world migration job**, not just a `ChunkLoadEvent`.

---

# Version 1 scope

## Seasons

Support:

- Spring
- Summer
- Autumn
- Winter

Default season timing should follow the real-world calendar.

Suggested defaults:

- Spring: March–May
- Summer: June–August
- Autumn: September–November
- Winter: December–February

Make this configurable.

The season system should expose a clean internal API such as:

```java
Season getCurrentSeason(World world);
double getSeasonProgress(World world);
```

Do not make other systems independently calculate the current season.

---

# World migration system

This is the most important subsystem.

When the season changes:

```text
Season changes
→ create migration job
→ find all existing generated chunks
→ queue them
→ gradually load them without generation
→ apply seasonal changes
→ save
→ unload if appropriate
→ mark complete
```

The plugin should keep track of migration state.

Each chunk should effectively have a seasonal version/state such as:

```text
2026-WINTER
```

A chunk with an older version is stale and must be updated.

Migration state should persist across server restarts.

Admin status should show progress such as:

```text
Current Season: Winter

Overworld Migration
384921 / 472781 chunks
81.4% complete
```

Important:

**Do not blindly generate chunks while iterating coordinates.**

Only process chunks that already exist.

If practical, inspect Minecraft region files to efficiently discover generated chunks rather than scanning a giant coordinate square, but use Paper APIs for actual chunk loading and modification.

Do not directly modify `.mca` files.

---

# Reversibility and plugin removal

True Seasons should be designed so the server can return to a clean vanilla-compatible world state before the plugin is removed.

The preferred architecture is:

```text
temporary visual changes
→ packet/presentation layer
→ disappear automatically when plugin is removed

persistent block changes
→ explicitly tracked as plugin-owned
→ safely reversible
```

Do not permanently alter anything unless necessary.

Anything True Seasons physically places or changes in the world must be identifiable as belonging to True Seasons.

## Cleanup command

Provide an admin command such as:

```text
/trueseasons cleanup
```

or:

```text
/trueseasons uninstall
```

This should start a full-world cleanup migration.

The cleanup must:

- stop normal seasonal migrations
- enumerate all existing generated chunks
- remove True Seasons-owned snow and other persistent seasonal blocks
- restore any persistent world state that True Seasons modified
- clear True Seasons chunk metadata after successful restoration
- never generate missing chunks
- survive restarts while cleanup is in progress
- throttle itself just like normal migrations
- provide progress through `/trueseasons status`
- report when the world is safe for plugin removal

Example:

```text
True Seasons Cleanup

Overworld
██████████████████░░ 91.2%

Seasonal snow removed: 1,482,391
Chunks cleaned: 430,221 / 471,783

Plugin removal safe: NO
```

When complete:

```text
True Seasons Cleanup Complete

All tracked world modifications have been restored.
No cleanup migration is pending.

It is now safe to stop the server and remove True Seasons.
```

Do not automatically disable or delete the plugin jar.

The administrator should still stop the server and remove it normally.

## Prefer making cleanup unnecessary where possible

If a seasonal feature can be implemented entirely as a temporary presentation change, do that instead of writing permanent state.

For example:

- biome colors should preferably not permanently rewrite chunk biome data
- grass and foliage tinting should preferably be presentation-based
- weather rules require no cleanup
- seasonal state metadata itself does not require world restoration

The cleanup system should primarily exist for features such as physical seasonal snow.

The architecture should minimize how much cleanup actually needs to do.

---

# Winter snow system

Winter should be able to place seasonal snow across the world.

The system must distinguish:

- snow placed by True Seasons
- naturally generated snow
- player-placed snow

Spring cleanup and uninstall cleanup must remove **only snow belonging to True Seasons**.

Do not store millions of individual world coordinates globally if it can be avoided.

Prefer compact per-chunk tracking, such as a bitset or compressed surface-column map.

Example:

```text
Chunk seasonal metadata:
seasonVersion
seasonalSnowColumns
```

On winter migration:

```text
inspect surface
→ determine valid seasonal snow locations
→ place seasonal snow
→ record plugin-owned placements
```

On spring migration:

```text
read seasonal snow metadata
→ remove plugin-owned snow
→ clear seasonal snow metadata
```

On plugin cleanup:

```text
read seasonal snow metadata
→ remove every remaining plugin-owned snow placement
→ clear metadata
```

Player-created snow must remain untouched.

If terrain has changed since snow was placed, cleanup should fail safely rather than deleting unrelated blocks.

---

# Seasonal biome visuals

Design the plugin around one central seasonal biome/visual resolver.

Conceptually:

```java
SeasonVisualProfile getSeasonalVisuals(Location location);
```

or equivalent.

All visual systems should use the same source of truth.

Do not permanently rewrite underlying world biomes unless absolutely necessary.

Prefer presentation-level or packet-level biome changes where possible.

Seasonal appearance goals:

### Spring
- brighter greens
- lush foliage

### Summer
- normal/deeper greens

### Autumn
- muted grass
- orange/red/yellow foliage where appropriate

### Winter
- pale/desaturated grass
- subdued foliage
- snow where appropriate

Avoid turning all biomes into one generic seasonal color.

Different biome families should retain their identity.

For example:

```text
Forest autumn
→ orange/red/yellow

Taiga autumn
→ darker muted evergreen

Savanna autumn
→ relatively subtle change

Desert
→ little or no vegetation change
```

Biome families and seasonal palettes should be configurable.

---

# Seasonal transitions

For the first implementation, prefer:

```text
4 major authoritative world migrations per year
```

rather than many tiny palette transitions.

The migration framework should be capable of supporting gradual seasonal stages later.

Possible future states:

```text
Early Autumn
Autumn
Late Autumn
```

but do not make this necessary for v1.

Get full-world consistency working first.

---

# Weather

Include lightweight seasonal weather rules.

Examples:

- more rain in spring
- normal weather in summer
- increased rain/storm chance in autumn
- appropriate rain/snow behavior in winter

Do not build temperature survival systems.

Keep weather behavior configurable.

---

# BlueMap integration

BlueMap should be treated as the first major external integration.

When chunks are seasonally updated, True Seasons should be capable of requesting or queueing BlueMap rerenders for affected areas.

Do not rerender the entire map in one huge operation.

Use the same gradual migration model:

```text
chunk season update
→ queue BlueMap rerender
```

The goal is that BlueMap eventually reflects the complete current season instead of showing permanent snowy player trails.

BlueMap integration should also participate in cleanup.

For example:

```text
cleanup modifies chunk
→ queue BlueMap rerender
```

Once cleanup is finished, the map should eventually represent the restored vanilla world.

Keep BlueMap support optional.

True Seasons must function normally without BlueMap installed.

---

# Distant Horizons

Do NOT implement Distant Horizons support yet.

The current server helper is still snapshot/WIP.

However, structure True Seasons so DH support can be added later.

Keep a clean internal API for:

```text
current season
effective seasonal visuals
chunk seasonal version
season change events
chunk migration completion events
cleanup events
```

Future DH support should live in a separate integration module and should not affect the core architecture.

---

# Performance requirements

The plugin should be deliberately conservative.

Migration should be configurable using values such as:

```yaml
migration:
  enabled: true
  max-concurrent-chunks: 4
  pause-below-tps: 18.5
  prioritize-player-chunks: true
  generate-missing-chunks: false
```

Cleanup should use the same throttling infrastructure.

Do not assume the whole world needs to transition quickly.

It is acceptable for a migration or cleanup operation to take hours if normal gameplay remains smooth.

The system should favor:

```text
slow + complete
```

over:

```text
fast + laggy
```

---

# Admin commands

Initial commands:

```text
/trueseasons info
/trueseasons status
/trueseasons reload

/trueseasons season set <spring|summer|autumn|winter>
/trueseasons season sync

/trueseasons migration status
/trueseasons migration pause
/trueseasons migration resume

/trueseasons cleanup
```

Optional aliases:

```text
/season
/ts
```

The cleanup command should require explicit confirmation before beginning because it may take a long time and temporarily stop normal seasonal behavior.

Example:

```text
/trueseasons cleanup
```

returns:

```text
This will begin restoring all True Seasons world modifications.
Run /trueseasons cleanup confirm to begin.
```

Permissions should be clean and LuckPerms-friendly.

Example:

```text
trueseasons.command.info
trueseasons.command.admin
trueseasons.command.season
trueseasons.command.migration
trueseasons.command.cleanup
```

---

# Config structure

Prefer separate configs rather than one massive file.

Possible layout:

```text
plugins/TrueSeasons/

config.yml
calendar.yml
visuals.yml
snow.yml
weather.yml
integrations.yml

state/
  worlds/
  migrations/
```

Configuration should be readable and heavily commented.

---

# Internal architecture

Suggested services:

```text
SeasonManager
SeasonCalendar
SeasonVisualResolver
SeasonMigrationService
CleanupMigrationService
GeneratedChunkIndex
SeasonalSnowService
WeatherService
IntegrationManager
BlueMapIntegration
```

Suggested events:

```text
SeasonChangeEvent
SeasonMigrationStartEvent
SeasonChunkUpdatedEvent
SeasonMigrationCompleteEvent

SeasonCleanupStartEvent
SeasonChunkCleanupEvent
SeasonCleanupCompleteEvent
```

Keep integrations separated from the core.

Normal seasonal migrations and uninstall cleanup should ideally use the same underlying chunk-processing framework.

For example:

```text
ChunkMigrationEngine
    ├── SeasonalMigrationTask
    └── CleanupMigrationTask
```

Do not create two completely separate world-scanning implementations.

---

# Persistent data philosophy

Persistent metadata should answer questions such as:

```text
What seasonal version was this chunk last processed with?

Did True Seasons place snow here?

Does this chunk still contain plugin-owned world modifications?

Has this chunk completed uninstall cleanup?
```

Do not use metadata merely because it is convenient.

Keep it compact and versioned so future releases can migrate old state safely.

---

# Non-goals for v1

Do not add:

- crop growth modifications
- custom crops
- temperature damage
- thirst
- mob variants
- RPG progression
- custom dimensions
- custom currencies
- seasonal quests
- economy mechanics
- holidays
- holiday events
- massive custom resource packs
- required client mods
- Distant Horizons integration yet

The plugin should remain focused.

---

# Primary success criteria

True Seasons v1 is successful if:

1. The server knows the correct real-world season.
2. Seasonal visuals change appropriately.
3. Winter can add snow.
4. Spring reliably cleans up winter snow.
5. Remote chunks update even if no player visits them.
6. No new chunks are generated by seasonal processing.
7. Migration survives server restarts.
8. Server performance remains stable.
9. BlueMap can eventually show the entire world in the correct season.
10. Every persistent world modification made by True Seasons is reversible.
11. An administrator can run cleanup and eventually reach a known **safe-to-remove** state.
12. Removing True Seasons after successful cleanup leaves no permanent seasonal artifacts.
13. The codebase is structured cleanly enough for future Distant Horizons integration.

The most important philosophies are:

**The entire existing world eventually reaches one authoritative seasonal state.**

and:

**True Seasons should never take ownership of world state that it cannot safely give back.**