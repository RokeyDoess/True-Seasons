# True Seasons

[![Latest prerelease](https://img.shields.io/github/v/release/RokeyDoess/True-Seasons?include_prereleases)](https://github.com/RokeyDoess/True-Seasons/releases)
[![Build](https://github.com/RokeyDoess/True-Seasons/actions/workflows/build.yml/badge.svg)](https://github.com/RokeyDoess/True-Seasons/actions/workflows/build.yml)
[![Minecraft: 26.3](https://img.shields.io/badge/Minecraft-26.3-brightgreen)](#install)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Seasonal colors and reversible winter snow for Paper servers.

True Seasons brings Minecraft's native snowfall to winter and adds snow to exposed terrain, roofs and leaf canopies. Exposed grass, flowers and leaf litter disappear beneath the snow and return when winter ends.

**Latest prerelease: 0.1.10.** Seasonal colors, winter snow and weather are available.

## Features

- Follow a real-world calendar or choose a season manually.
- Give grass seasonal colors and autumn trees a stable yellow, orange or red appearance. Birch participates, cherry always turns red, and spruce keeps its normal color.
- Update terrain one region at a time, even without players online. Player movement does not change the order.
- Resume saved progress after a restart, without generating unexplored terrain.
- Restore winter's temporary changes while preserving player replacements and existing snow.
- Replenish missing snow during winter rain, with a delay after players break it.
- Update BlueMap as completed regions are saved. No client mod is required.

Grass, ferns, flowers, bushes, mushrooms and leaf litter are saved before removal. Tall plants keep both halves and their properties. Saplings, leaves, crops and sheltered plants stay in place. Snow requires a surface that supports a normal Minecraft snow layer.

Deserts, badlands, savannas, jungles and other hot biomes skip winter snow and snowfall by default. Cold biomes keep their normal colors during summer. Saved biomes and natural snow remain unchanged.

## Autumn trees

Autumn uses Minecraft's yellow, orange and red poplar leaf appearances. Each tree's color remains stable across reloads. Oak, birch, jungle, acacia, dark oak and mangrove trees participate; cherry always uses red. Spruce, existing poplar, pale oak and azalea retain their normal appearance. Original leaf types, decay and drops stay unchanged. No resource pack is required.

Colors follow the nearest matching trunk. Touching crowns can share a boundary, and connected logs are treated as one tree. Leaves without a usable trunk, unusually large structures or unavailable neighboring terrain retain their original appearance. Client colors finish updating as nearby chunks arrive. There is no gradual autumn color transition yet.

Some overlapping oak and birch crowns can retain green leaves until a client refresh. Decorative leaves without a trunk may also keep their usual appearance. Improvements are planned.

Grass colors still follow the biome palettes in `visuals.yml`. Set `autumn-trees.enabled: false` there to keep ordinary leaf appearances.

## Install

Tested with **Paper 26.3, builds 177 and 181**, **Java 25**, and optional **BlueMap 5.29**. Other versions have not been verified. BlueMap is optional.

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
| `/ts status` or `/ts migration` | Show terrain progress and pending map updates. |
| `/ts migration pause` | Pause terrain updates. |
| `/ts migration resume` | Continue paused updates. |
| `/ts season set <spring\|summer\|autumn\|winter>` | Choose a season. |
| `/ts season sync` | Return to the calendar. |
| `/ts reload` | Apply edited settings. |
| `/ts cleanup` | Request clean removal. |
| `/ts cleanup confirm` | Confirm within 60 seconds. |
| `/ts cleanup status` | Check cleanup progress. |

Status begins with **Migration status** and highlights progress, performance and pending work. **INDEXING SAVED PROGRESS** means the plugin is reading saved progress. Changing snow rules or enabled worlds can start a new terrain update; unchanged settings preserve progress.

## Settings

Each generated file explains its options.

| File | Controls |
| --- | --- |
| `config.yml` | Enabled worlds, update speed and climate exclusions. Updates pause below 18.5 TPS by default. |
| `calendar.yml` | Time zone and season months. Each month must belong to one season. |
| `snow.yml` | Snow surfaces, extra biome exclusions, ground cover and replenishment delay. |
| `weather.yml` | Winter's introductory storm and seasonal rain/thunder frequency. |
| `integrations.yml` | BlueMap updates. |
| `visuals.yml` | Seasonal grass and leaf colors. |

Snow covers exposed, supporting surfaces by default. A surface whitelist is optional. Climate exclusions match parts of biome names and can be changed in `config.yml`. Additional winter exclusions belong in `snow.yml`. Player-priority options do not change background migration order.

Nearby loaded chunks accumulate real snow up to the shared winter transition percentage. The background pass still completes regions in order. `migration.player-radius-chunks` controls only the local storm radius; `transition.enabled` and its step settings in `snow.yml` control local accumulation.

`ground-cover.enabled` controls temporary plant removal. Disabling it restores saved plants during the next terrain update. Plants return only where their original space and support remain suitable. Breaking winter snow, changing its support or placing a replacement can discard the saved plant at that spot. Unresolved conflicts are reported as deferred work and need attention before cleanup can finish.

Player-broken snow has a **60-second** refill delay by default. Change `replenishment.player-break-cooldown-seconds`; `0` disables the delay. Afterward, snow may return during winter rain in loaded, completed terrain when light and support allow it. Existing layers do not grow taller.

The introductory storm lasts until terrain updates finish, including pauses and restarts. Previous weather returns afterward. Throughout winter, rain and thunderstorms appear as snowfall in eligible biomes; clear weather stays clear.

## BlueMap

True Seasons requests updates after a region's terrain changes finish and save. Nearby changes are combined to reduce renders, so the map may trail the world. Updates that change no blocks do not request a render.

**WORLD COMPLETE — MAP UPDATES PENDING** means terrain work has finished and BlueMap is catching up. Keep autosaving enabled and allow BlueMap to render. Frozen or stopped maps keep their updates pending.

Status shows waiting reasons and True Seasons' region render count since startup. BlueMap's own updates are separate. New terrain or later snow changes can require another render.

Season or palette changes also queue color updates for existing map regions. These use the same render limits and wait for terrain changes to finish and save. Unchanged restarts and reloads do not repeat the color pass. BlueMap uses the same autumn tree choices and retains the original world data.

Edit `visuals.yml` to choose colors, then run `/ts reload`. Some plugin stacks may ask players to reconnect to load changed palettes.

## Permissions

Operators have administration access by default. All players can use `/ts info`.

| Permission | Access |
| --- | --- |
| `trueseasons.command.info` | Version and season information. |
| `trueseasons.command.migration` | Status, pause and resume. |
| `trueseasons.command.season` | Choose a season or return to the calendar. |
| `trueseasons.command.cleanup` | Request, confirm and inspect cleanup. |
| `trueseasons.command.admin` | Reload settings and all administration commands. |

## Remove cleanly

1. Run `/ts cleanup`, then `/ts cleanup confirm` within 60 seconds from the same player or console.
2. Wait for **Plugin removal safe: YES** in `/ts cleanup status`.
3. With BlueMap, also wait for **pending regions: 0**.
4. Stop the server normally and remove the jar.

Cleanup restores saved plants and removes the plugin's snow while preserving player replacements. Previously managed worlds must remain available until cleanup finishes, even if removed from the enabled list. Packet-based colors leave no saved biome or leaf changes to restore; cleanup also returns saved BlueMap tiles to their normal appearance.

If the plugin remains installed after cleanup, seasons stay disabled until `/ts season sync` or `/ts season set ...`.

Distant Horizons support is planned. See the [Changelog](docs/CHANGELOG.md) and [Roadmap](docs/ROADMAP.md).

## Help and contributions

See [Getting help](SUPPORT.md) for questions and bug reports, or [Contributing](CONTRIBUTING.md) to help with the project. Security problems have a [private reporting route](SECURITY.md). Community conversations follow the [community guidelines](CODE_OF_CONDUCT.md).
