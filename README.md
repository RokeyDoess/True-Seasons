# True Seasons

**Experimental PacketEvents branch.** This build tests optional compatibility with PacketEvents while keeping native packet handling. Use a separate server copy; this is not a published release. See the [experiment findings](docs/PACKETEVENTS_EXPERIMENT.md) for the comparison, tested plugin stack and remaining limits. The regular release below is unchanged.

[![Latest prerelease: v0.3.5](https://img.shields.io/badge/prerelease-v0.3.5-blue)](https://github.com/RokeyDoess/True-Seasons/releases/tag/v0.3.5)
[![Minecraft: 26.3](https://img.shields.io/badge/Paper-26.3-brightgreen)](#install)
[![Build](https://github.com/RokeyDoess/True-Seasons/actions/workflows/build.yml/badge.svg)](https://github.com/RokeyDoess/True-Seasons/actions/workflows/build.yml)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Spring blossoms, summer fireflies, autumn forests and winter snowstorms.

True Seasons brings seasons to the world you've already built, using Minecraft's own weather and block appearances. Individual trees turn yellow, orange or red. Winter settles across rooftops, treetops and hillsides, then gives way to melting snow and returning flowers.

**No resource pack. No client mod.** Players join with ordinary Minecraft.

**Latest prerelease: [0.3.5](https://github.com/RokeyDoess/True-Seasons/releases/tag/v0.3.5).** Find all published builds in [Releases](https://github.com/RokeyDoess/True-Seasons/releases).

## Seasons that feel like Minecraft

- **Spring in bloom.** Scattered blossoms appear among the ordinary leaves of about one in five suitable oak, birch and dark oak trees. Their original leaves and drops stay unchanged.
- **Summer after sunset.** Sparse fireflies appear near water and sheltered greenery at dusk and night. Oceans are excluded, and added sounds are off by default.
- **Autumn from tree to tree.** Oak and birch trees can take on different yellow, orange or red colors within the same forest. Each tree keeps its color between visits. Cherry turns red, spruce stays evergreen, and player-built hedges and decorative leaves join in.
- **Native snowfall and real snow cover.** Winter rain uses Minecraft's own snowfall. Snow settles on exposed terrain, rooftops and tree canopies, while grass, flowers and leaf litter make way for winter.
- **A transition you can watch.** Winter's opening storm gradually fills in the snow players see. When winter ends, snow disappears gradually and the plants underneath return. The scenery changes throughout the transition.
- **Biomes keep their character.** Deserts, jungles and other hot biomes skip winter and autumn effects by default. Cold biomes keep their natural colors through summer.
- **Your calendar, your seasons.** Colors arrive over the season's opening week, with whole trees changing at different times. Customize the calendar or switch to a season's final colors whenever you want.
- **Seasons on your web map, too.** BlueMap shows seasonal grass, spring blossoms, autumn trees and saved winter snow. True Seasons combines the updates and skips extra renders for gradual color changes.
- **Stay in the loop.** Simple season announcements for players, migration notices for admins, and optional private progress updates every five seconds.

## Made for a world you already care about

Seasonal colors leave your world's original biomes and tree types intact. An oak keeps its ordinary behavior and drops throughout autumn, and your existing world needs no regeneration to take part.

Winter saves the plants it removes and restores them when their space is still suitable. Saplings, crops, leaves and sheltered plants stay in place. Existing snow and player replacements are preserved.

Broken snow gets a refill delay, giving players time to dig. Terrain updates resume after restarts and work through existing regions without generating unexplored chunks. A cleanup command restores the plugin's temporary changes before removal.

## Performance

True Seasons is a heavier plugin by nature. Changing real terrain across a large world takes work, and BlueMap rendering adds to that cost. Keeping that work efficient is a core part of the plugin's design.

Terrain updates run in small, configurable batches, back off after busy ticks and pause below **18.5 TPS** by default. Snow previews and foliage catch-up use server-wide update budgets to limit extra work as more players connect. Map changes are combined, and completed regions are saved before their render is requested. Gradual snow previews add no extra BlueMap renders.

World discovery runs in the background, reuses read buffers and avoids repeated file opens for individual chunks. Chunk tracking also handles dense generated areas efficiently, reducing the work needed to find and resume saved progress.

Large worlds can take time to finish a transition. You can favor a lighter server workload or faster updates through the settings; hardware, player activity and your other plugins affect the result. See the [server guide](docs/SERVER_GUIDE.md#performance-and-settings) for the controls.

## Install

Requires **Paper 26.3** and **Java 25**. BlueMap is optional; **5.29** is the tested version. This is prerelease software, so try it on a backed-up test world first.

1. Download the jar from [Releases](https://github.com/RokeyDoess/True-Seasons/releases).
2. Stop your server normally and back up your world.
3. Put the jar in `plugins`, keeping only one True Seasons jar installed.
4. Start the server, then run `/ts info` and `/ts status`.

Settings appear in `plugins/TrueSeasons/`. Use a full server stop and start when updating the plugin.

By default, seasons follow **America/Los_Angeles** time: spring in March–May, summer in June–August, autumn in September–November, and winter in December–February. Change the time zone and months in `calendar.yml`. All overworld dimensions are included unless you choose specific worlds in `config.yml`.

Try `/ts season set winter` to see winter. Use `/ts season sync` to return to the calendar.

## Useful commands

`/ts` is short for `/trueseasons`. Operators have administration access by default.

| Command | What it does |
| --- | --- |
| `/ts info` | Show the version and current season. |
| `/ts status` | Get a short overview. |
| `/ts migration` | Check terrain progress and BlueMap. |
| `/ts migration notify` | Toggle private progress updates every five seconds. |
| `/ts migration pause` / `/ts migration resume` | Pause or continue terrain and color transitions. |
| `/ts season set <season>` | Choose spring, summer, autumn or winter. |
| `/ts season sync` | Follow the calendar again. |
| `/ts reload` | Apply edited settings. |

The [server guide](docs/SERVER_GUIDE.md) covers all commands, permissions, settings and clean removal.

## A few things to know

- BlueMap shows saved terrain. It can trail the world and will not mirror the gradual snow reveal players see.
- Newly visible snow catches up within limited update batches. Plants wait for their region's terrain pass before disappearing.
- Calendar grass colors change in steps, and whole trees change at different times. Unresolved autumn foliage uses orange; unresolved spring foliage keeps its ordinary appearance.
- Cherry petals on the ground remain pink.
- Distant Horizons support is planned.

**Before removing the plugin**, run `/ts cleanup`, then `/ts cleanup confirm`, and wait for **Plugin removal: SAFE** in `/ts cleanup status`, with no BlueMap regions pending. Stop the server before removing the jar. [Full cleanup instructions](docs/SERVER_GUIDE.md#remove-cleanly).

## Follow along

Read the [changelog](docs/CHANGELOG.md), see the [roadmap](docs/ROADMAP.md), or ask questions and report bugs in [Issues](https://github.com/RokeyDoess/True-Seasons/issues/new/choose).

[Getting help](SUPPORT.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Community guidelines](CODE_OF_CONDUCT.md)
