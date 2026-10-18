# Server guide

[Back to True Seasons](../README.md)

Setup, settings and administration for True Seasons.

## Autumn trees

Autumn uses Minecraft's yellow, orange and red poplar leaf appearances. Each tree's color remains stable across reloads. Oak, birch, jungle, acacia, dark oak and mangrove trees participate; cherry always uses red. Spruce, existing poplar, pale oak and azalea retain their normal appearance. Original leaf types, decay and drops stay unchanged. No resource pack is required.

Natural tree colors follow the nearest matching trunk, including through overlapping crowns. Connected logs are treated as one tree. Eligible leaves use orange while tree data is unavailable or a structure exceeds the search limits. They resolve to their tree's color as nearby chunks arrive.

Once a leaf's color is resolved, temporary neighboring-chunk loss does not turn it green again. Property updates and chunk resends retain that color while the tree is unavailable, while season changes, biome exclusions and player replacements still take effect.

Leaves waiting for tree data are checked again when the missing chunks arrive, including branches that extend farther across the view. These repairs take priority over routine color refreshes and do not load extra terrain.

Player-placed leaves and hedges use orange in autumn without needing a trunk, while cherry leaves stay red. Cherry petals on the ground remain pink; Minecraft has no matching red version to display without a resource pack.

Grass colors still follow the biome palettes in `visuals.yml`. Set `autumn-trees.enabled: false` there to keep ordinary leaf appearances.

### Calendar color transitions

The season's colors arrive during its first seven real days. Grass and biome foliage move through a fixed set of shades; whole autumn trees change at different, stable times. Trees also return to their ordinary appearance at different times when autumn ends. Snowfall and melting continue to follow terrain migration progress separately.

Change `transition.opening-days` in `visuals.yml` to adjust the window, or use `0` for immediate colors. A window longer than the season is limited to that season's length. `/ts season set` applies the final colors immediately; `/ts season sync` returns to the current calendar stage. Downtime skips directly to the current stage.

`/ts migration pause` freezes the saved season, color stage and snow coverage, including across restarts. Resume keeps a manual target or resynchronizes calendar mode to the current date. Resume before changing seasons. `/ts status` shows color progress while an opening transition is active.

BlueMap shows the full seasonal colors and receives no extra renders for the intermediate shades. Players receive the fixed shades when they connect, adding some initial join data. The server shares prepared palettes across joins while keeping outgoing client data independent. Disabling the opening window omits its unused shades from joining clients. Existing terrain and leaves are never rewritten for color transitions.

### Spring blossoms and summer fireflies

About one third of oak, birch and dark oak trees display Minecraft's flowering azalea leaves in spring. The selection stays fixed throughout the season and across restarts. Whole trees blossom and return to normal at different times during the calendar's opening window. Decorative and unresolved foliage keeps its ordinary spring appearance; cherry trees already have blossoms, and evergreen and tropical tree types stay unchanged.

Blossoms change appearance only. Leaves keep their saved type, properties, decay and drops, and BlueMap shows the same spring selection. Set `spring-blossoms.enabled: false` in `visuals.yml` to disable them. Changing this setting does not require clients to receive new biome colors.

Summer adds sparse native fireflies at dusk and night, near land beside water or sheltered vegetation. Oceans, caves, rain and summer-exempt cold biomes are excluded. There are no new entities, extra pollen or petal particles, or saved world changes. Sounds are off by default.

Control fireflies in `ambience.yml`. Their sampling attempts and particles share a server-wide allowance across players, including failed habitat checks. The defaults allow 16 attempts and at most eight particles once per second, within 24 blocks of players. Work pauses below the configured TPS threshold and samples only loaded chunks. Fireflies have their own toggle and do not depend on seasonal weather being enabled.

## Install

Tested with **Paper 26.3, builds 177, 181 and 200**, **Java 25**, and optional **BlueMap 5.29**. Other versions have not been verified. BlueMap is optional.

1. Download the jar from [Releases](https://github.com/RokeyDoess/True-Seasons/releases).
2. Stop your server normally and back up your world.
3. Place the jar in `plugins`, keeping only one True Seasons jar installed.
4. Start the server. Settings appear in `plugins/TrueSeasons/`.
5. Run `/ts info` and `/ts status`.

Use a full stop and start when updating the plugin.

The default calendar uses **America/Los_Angeles** time: spring is March–May, summer June–August, autumn September–November, and winter December–February. Change the time zone and months in `calendar.yml`.

Only overworld dimensions receive seasonal changes. An empty `worlds` list includes all overworlds; add names to limit the enabled worlds.

To try winter, run `/ts season set winter`. This choice survives restarts. Use `/ts season sync` to return to the calendar.

## Commands

`/ts` is short for `/trueseasons`.

| Command | Purpose |
| --- | --- |
| `/ts info` | Show the version and current season. |
| `/ts status` | Show a short season, terrain and map overview. |
| `/ts migration` | Show progress and the active region for each world. |
| `/ts migration details` | Add snow catch-up, refresh workload and detailed terrain counters. |
| `/ts migration notify` | Toggle private progress updates every five seconds. |
| `/ts migration pause` | Pause terrain updates and seasonal color progress. |
| `/ts migration resume` | Continue paused updates; calendar mode catches up to the current date. |
| `/ts season set <spring\|summer\|autumn\|winter>` | Choose a season. |
| `/ts season sync` | Return to the calendar. |
| `/ts reload` | Apply edited settings. |
| `/ts cleanup` | Request clean removal. |
| `/ts cleanup confirm` | Confirm within 60 seconds. |
| `/ts cleanup status` | Check cleanup progress. |

Migration output begins with **Migration status**; status and cleanup have their own headers. Progress and important numbers are highlighted, with BlueMap summarized in one row. Hover over that row in game for full map diagnostics. **READING SAVED PROGRESS** means saved progress is still being checked. Changing snow rules or enabled worlds can start a new terrain update; unchanged settings preserve progress.

Paused jobs show their saved reason and the `/ts migration resume` command. A display refresh failure does not pause terrain work. Failures in terrain work or durable integration updates still pause safely and notify admins. Older saved pauses may have no recorded reason.

When a season changes, everyone online sees a short message such as **Winter has begun.** It has no plugin prefix. Admins with `trueseasons.command.admin` and all operators also receive **[True Seasons] Winter migration started. Check /ts migration for more details.** Click the command to view progress. **[True Seasons] Migration complete.** follows when terrain work and queued BlueMap updates finish. Ordinary scans and unchanged restarts do not repeat these notices; cleanup cancels a pending seasonal completion notice.

Disable either audience independently with `announcements.season-start` or `announcements.migration` in `config.yml`, then run `/ts reload`.

Use `/ts migration notify` for a single progress line every five seconds, sent only to you. Run it again to stop. Updates end automatically when migration and queued map updates finish, or when you disconnect. Subscriptions are not saved across restarts. This uses the migration command permission and works independently of automatic admin announcements.

## Performance and settings

Saved-world discovery runs in the background and reuses each region file while checking its chunks. Chunk tracking also spreads nearby coordinates efficiently across its lookup tables. These improvements reduce indexing work; they do not eliminate the cost of changing terrain or rendering BlueMap.

`migration.rescan-seconds` in `config.yml` sets the wait between completed background checks. A slow check finishes before that wait begins, giving terrain updates and BlueMap time to catch up.

Each generated file explains its options.

Terrain migration controls are in `config.yml`: `migration.max-chunks-per-tick`, `migration.max-millis-per-tick` and `migration.max-concurrent-chunks` limit application work and outstanding loads. `migration.pause-below-tps` defaults to 18.5. Snow preview limits are separate in `snow.yml`, and BlueMap's render limits are in `integrations.yml`. Lower limits reduce the work admitted at once but extend the transition. Time budgets are scheduling targets; an individual operation can take longer.

| File | Controls |
| --- | --- |
| `config.yml` | Enabled worlds, announcements, update speed and climate exclusions. |
| `calendar.yml` | Time zone and season months. Each month must belong to one season. |
| `snow.yml` | Snow surfaces, transitions, preview limits, ground cover and replenishment delay. |
| `weather.yml` | Winter's introductory storm and seasonal rain/thunder frequency. |
| `integrations.yml` | BlueMap updates. |
| `visuals.yml` | Seasonal grass and leaf colors. |
| `ambience.yml` | Summer fireflies and their shared limits. |

Snow covers exposed, supporting surfaces by default. A surface whitelist is optional. Climate exclusions match parts of biome names and can be changed in `config.yml`. Additional winter exclusions belong in `snow.yml`.

During winter's opening storm, snow appears gradually across the chunks players can see, following the shared winter transition percentage. Finished regions reveal their snow gradually; unfinished visible terrain shows temporary snow previews on suitable empty surfaces. The background pass places real snow and removes exposed plants in region order. Previews do not change the world or request BlueMap renders.

`transition.enabled` in `snow.yml` controls this transition. Disabling it shows snow as regions finish. The old local radius and accumulation step settings no longer limit the visible storm. Natural snow, thicker layers and player replacements keep their real appearance. Newly visible terrain catches up within bounded update batches; occupied plants wait for the physical pass.

Newly visible chunks get priority when players join, travel or teleport. Snow refreshes share a server-wide allowance and viewers take turns, so catch-up work stays bounded as more players connect. Routine snow updates continue alongside new views.

Fresh preview planning starts with the closest visible chunks for each player before moving outward. This improves nearby catch-up without increasing the work budget; a full view can still take time to finish.

Tune that allowance in `snow.yml` with `presentation.max-chunks-per-tick` (default **64**, range 1–1024) and `presentation.max-block-updates-per-tick` (default **8192**, range 1–131072). Both limits apply across all players, with smaller per-player batches for fairness. Lower values reduce extra update work but make snow catch up more slowly; higher values allow faster multiplayer catch-up at a higher cost. Run `/ts reload` to apply changes. These limits cover winter previews, snow reveals and melting; they do not change terrain migration speed, BlueMap renders or normal chunk packets.

Grass, podzol and mycelium sides follow the same reveal, so hidden snow does not leave white ground textures behind. Saved block properties stay unchanged.

When winter ends, region restoration starts immediately. Visible snow coverage decreases with migration progress: remaining snow is previewed over already-restored terrain, while excess snow is hidden on regions still waiting for restoration. Restored flowers and both halves of tall plants stay hidden beneath their remaining snow preview, then return to view as it melts.

Melting starts from winter's existing coverage and pattern, including an interrupted winter. Pauses and low TPS suspend terrain work and coverage progress. Saved chunk receipts rebuild remaining previews after chunk reloads or restarts; changing to another nonwinter season continues the melt, and returning to winter cancels it. There is no separate melt timer.

Disable `snowmelt.enabled` in `snow.yml` to show physical restoration directly. Melting does not force rain or request extra BlueMap renders. Natural snow, thicker layers and player replacements retain their actual appearance. Clearing a snow preview reveals the restored plant without breaking it. BlueMap follows the real region restoration and save process.

`ground-cover.enabled` controls temporary plant removal. Disabling it restores saved plants during the next terrain update. Plants return only where their original space and support remain suitable. Breaking winter snow, changing its support or placing a replacement can discard the saved plant at that spot. Unresolved conflicts are reported as deferred work and need attention before cleanup can finish.

If a saved chunk cannot be read, `/ts migration details` shows one affected chunk's coordinates and the read failure reason.

Player-broken snow has a **60-second** refill delay by default. Change `replenishment.player-break-cooldown-seconds`; `0` disables the delay. Preview snow uses the same delay, including when the region pass arrives. It can be cleared but drops no items. Afterward, real snow may return during winter rain in loaded, completed terrain when light and support allow it. Existing layers do not grow taller.

The introductory storm lasts until terrain updates and players' pending snow reveals finish, including migration pauses and restarts. Previous weather returns afterward. Throughout winter, rain and thunderstorms appear as snowfall in eligible biomes; clear weather stays clear.

## BlueMap

True Seasons requests updates after a region's terrain changes finish and save. Nearby changes are combined to reduce renders, so the map may trail the world. Updates that change no blocks do not request a render.

BlueMap shows the actual saved snow in completed regions. The gradual reveal players see does not trigger additional map renders.

**TERRAIN COMPLETE — MAP PENDING** means terrain work has finished and BlueMap is catching up. Keep autosaving enabled and allow BlueMap to render. Frozen or stopped maps keep their updates pending.

The map summary shows pending regions and the main waiting reason. Hover over it in game for full diagnostics. BlueMap's own updates are separate. New terrain or later snow changes can require another render.

The count combines distinct world regions across terrain and color work. A region waiting for both counts once, as do multiple maps of the same world region. Unfinished updates can carry over from an earlier transition; their latest required changes must still finish. While region discovery is incomplete, the summary says updates are pending without inventing a region count.

Season or palette changes also queue color updates for existing map regions. These use the same render limits and wait for terrain changes to finish and save. Unchanged restarts and reloads do not repeat the color pass. BlueMap uses the same autumn tree choices and retains the original world data.

Edit `visuals.yml` to choose colors, then run `/ts reload`. Some plugin stacks may ask players to reconnect to load changed palettes.

Foliage catch-up shares a server-wide allowance between players. In `visuals.yml`, `foliage-updates.max-chunks-per-tick` and `max-states-per-tick` limit these extra refreshes. Dense chunks continue over later ticks. Lower the limits if needed on a busy server; catching up will take longer. These limits leave normal chunk delivery and BlueMap rendering unchanged.

## Permissions

Operators have administration access by default. All players can use `/ts info`.

| Permission | Access |
| --- | --- |
| `trueseasons.command.info` | Version and season information. |
| `trueseasons.command.migration` | Status, private progress updates, pause and resume. |
| `trueseasons.command.season` | Choose a season or return to the calendar. |
| `trueseasons.command.cleanup` | Request, confirm and inspect cleanup. |
| `trueseasons.command.admin` | Reload settings and all administration commands. |

## Remove cleanly

1. Run `/ts cleanup`, then `/ts cleanup confirm` within 60 seconds from the same player or console.
2. Wait for **Plugin removal: SAFE** in `/ts cleanup status`.
3. With BlueMap, also wait until no regions remain pending.
4. Stop the server normally and remove the jar.

Cleanup restores saved plants and removes the plugin's snow while preserving player replacements. Previously managed worlds must remain available until cleanup finishes, even if removed from the enabled list. Packet-based colors leave no saved biome or leaf changes to restore; cleanup also returns saved BlueMap tiles to their normal appearance.

If the plugin remains installed after cleanup, seasons stay disabled until `/ts season sync` or `/ts season set ...`.

Distant Horizons support is planned. See the [Changelog](CHANGELOG.md) and [Roadmap](ROADMAP.md).

## Help and contributions

See [Getting help](../SUPPORT.md) for questions and bug reports, or [Contributing](../CONTRIBUTING.md) to help with the project. Security problems have a [private reporting route](../SECURITY.md). Community conversations follow the [community guidelines](../CODE_OF_CONDUCT.md).
