# True Seasons

Calendar seasons and reversible winter snow for Paper servers.

True Seasons follows a real-world calendar, brings Minecraft's native snowfall to winter, and adds snow to exposed terrain, roofs and leaf canopies. When winter ends, it removes the snow it placed while preserving existing snow, plants and player replacements.

**Development build: 0.1.6.** Winter snow and weather are available; the full seasonal grass and foliage color set is still being developed.

## What to expect

- Choose your time zone and season months, or set a season manually.
- Snow builds gradually near players while existing terrain farther away updates in the background. Newly explored terrain joins the current season.
- World updates continue without players online and recover saved progress after a restart. True Seasons does not generate unexplored terrain.
- Winter rain can replenish missing snow after the seasonal world update finishes. Player-broken snow waits for a configurable cooldown before it becomes eligible to return; snowfall and conditions at the spot determine when it actually returns.
- Optional BlueMap updates follow the changes in your world. Players do not need a client mod.

Flowers, short grass, leaf litter and other ground cover stay in place. Snow only goes onto empty spaces where a normal Minecraft snow layer can sit.

## Compatibility

Tested with **Paper 26.3, build 177**, **Java 25**, and optional **BlueMap 5.29**. Other server versions have not been verified. BlueMap is not required to run True Seasons.

Use a full server stop and start when installing or updating the plugin.

## Install

1. Download a packaged True Seasons `.jar` from [Releases](https://github.com/RokeyDoess/True-Seasons/releases).
2. Stop your server normally.
3. Put the jar in your server's `plugins` folder. Keep only one True Seasons jar installed.
4. Start the server. Settings appear in `plugins/TrueSeasons/`.
5. Run `/ts info` and `/ts status` to check the season and progress.

By default, seasons use **America/Los_Angeles** time:

| Season | Months |
| --- | --- |
| Spring | March–May |
| Summer | June–August |
| Autumn | September–November |
| Winter | December–February |

Only overworld dimensions are eligible. An empty `worlds` list includes all overworlds; add world names to limit which ones receive seasonal changes.

To try winter immediately, run `/ts season set winter`. This manual choice survives restarts. Run `/ts season sync` when you want the calendar to take over again.

## Commands

`/ts` is the short form of `/trueseasons`.

| Command | Purpose |
| --- | --- |
| `/ts info` | Show the plugin version and current season. |
| `/ts status` or `/ts migration` | Show world progress, snowfall coverage and pending map updates. |
| `/ts migration pause` | Pause seasonal world updates. |
| `/ts migration resume` | Continue paused updates. |
| `/ts season set <spring\|summer\|autumn\|winter>` | Choose a persistent manual season. |
| `/ts season sync` | Return to the calendar. |
| `/ts reload` | Apply edited settings. |
| `/ts cleanup` | Request clean removal. |
| `/ts cleanup confirm` | Confirm that request within 60 seconds. |
| `/ts cleanup status` | Check cleanup progress. |

Status output starts with **Migration status**. Progress is highlighted in cyan, server performance (TPS) uses green/yellow/red, and pending or deferred work is highlighted separately. On startup, **INDEXING SAVED PROGRESS** and **reading saved progress** mean the plugin is recovering saved progress, rather than starting the world over.

A reload preserves progress when the snow placement rules and enabled worlds are unchanged. Changing those rules can start a new world update.

## Configure your server

The generated files include comments explaining each option.

| File | Controls |
| --- | --- |
| `config.yml` | Enabled worlds, world update speed and player priority. Defaults prioritize a four-chunk radius and pause updates below 18.5 TPS. |
| `calendar.yml` | Time zone and season months, including southern hemisphere calendars. Every month must belong to exactly one season. |
| `snow.yml` | Snow surfaces, excluded biomes, gradual accumulation, protection and the cooldown for player-broken snow. |
| `weather.yml` | The introductory winter storm, snowfall, and seasonal rain/thunder probabilities. |
| `integrations.yml` | Optional BlueMap updates. |
| `visuals.yml` | Planned grass and foliage palettes; configured colors are not displayed yet. |

Snow defaults to **all exposed, supporting surfaces**, with no biome exclusions. Nearby snow builds in small, gradual steps. Settings control its pace and how much appears each time. You can choose a material whitelist instead or exclude biomes in `snow.yml`.

The player-break cooldown is set with `replenishment.player-break-cooldown-seconds` in `snow.yml` and defaults to **60 seconds**. Set it to `0` to disable the grace period. After the cooldown, snow can return during winter rain if the area is loaded, its seasonal update is complete, and the spot has suitable light and support. Existing snow layers are preserved rather than grown taller.

The introductory winter storm lasts until that world's terrain update finishes, including pauses and restarts. Previous weather is restored afterward. Outside that introductory storm, normal weather continues; winter rain and thunderstorms appear as snowfall.

## BlueMap

With BlueMap installed and enabled, True Seasons groups its updates by region. Each region waits until its known terrain has finished changing and its pending changes are saved. Nearby changes are combined before requesting a render, so map tiles can trail what players see in game. Updates that only record seasonal progress do not request a map render.

Status explains whether regions are waiting for terrain, saves or combined updates. It also shows **region renders scheduled this run**, counting True Seasons requests for each map since startup. BlueMap's own automatic updates are separate; later snow changes or newly explored terrain can require another render.

**WORLD COMPLETE — MAP UPDATES PENDING** means the terrain work has finished and the map is still catching up. Pending map work does not restart seasonal world updates. Keep normal server autosaving enabled and allow BlueMap to render; frozen or stopped maps will keep their updates pending.

## Permissions

Server operators have administration access by default. All players can use `/ts info`. A permissions plugin can grant individual capabilities:

| Permission | Access |
| --- | --- |
| `trueseasons.command.info` | Plugin and season information. |
| `trueseasons.command.migration` | Status, pause and resume. |
| `trueseasons.command.season` | Set a season or return to the calendar. |
| `trueseasons.command.cleanup` | Request, confirm and inspect cleanup. |
| `trueseasons.command.admin` | Reload settings and grant all administration commands. |

## Remove True Seasons cleanly

1. Run `/ts cleanup`, then `/ts cleanup confirm` within 60 seconds using the same player or console.
2. Check `/ts cleanup status` and wait for **Plugin removal safe: YES**.
3. If you use BlueMap, also wait for **pending regions: 0** so the map has caught up.
4. Stop the server normally, then remove the True Seasons jar.

Cleanup restores the plugin's snow and stops seasonal behavior. It preserves player replacements. Deferred work or an unavailable tracked world needs attention before cleanup can finish; worlds managed earlier must be available even if you later removed them from the enabled list.

If you keep the plugin after cleanup, it stays in restored mode. Use `/ts season sync` or `/ts season set …` to enable seasons again.

## Current scope

Seasonal grass and leaf color palettes are unfinished, including matching BlueMap vegetation colors. Distant Horizons support is planned for a later milestone. This build focuses on winter snow, weather, progress recovery and safe restoration; it leaves ground cover untouched.

More detail: [Changelog](docs/CHANGELOG.md) · [Roadmap](docs/ROADMAP.md) · [Validation notes](docs/VALIDATION.md).
