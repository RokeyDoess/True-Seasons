# Changelog

## Unreleased

## 0.3.4

- Reduce the CPU and temporary memory needed to discover saved terrain on large worlds.
- Skip extra BlueMap renders when another season's unused palette changes, including in climate-exempt biomes.

## 0.3.3

- Lower the overhead of saving and restoring seasonal snow and plants.
- Avoid extra BlueMap renders when a settings change leaves the current season's appearance unchanged.
- Keep summer firefly search turns available to players in eligible worlds, instead of spending them in daytime, storms or other dimensions.

## 0.3.2

- Let terrain migration back off after busy ticks and resume automatically once the server recovers. Migration status explains this brief wait.
- Scatter spring blossoms among mostly ordinary leaves on about one in five eligible trees. Tree and flower coverage can be adjusted separately.
- Help summer fireflies find sheltered greenery and natural shorelines, including diagonal water edges, while keeping their shared particle limit.
- Restore summer effects in cherry groves and mangrove swamps, and give them their intended seasonal colors. Unchanged default settings upgrade automatically.
- Reduce repeated tree searches and temporary memory use when coloring forests, hedges and newly loaded chunks.

## 0.3.1

- Share foliage catch-up work fairly between players, with adjustable limits for busy servers and dense forests.
- Reduce repeated work when several players join together.
- Cut temporary memory use during background checks on large worlds.
- Keep terrain moving during background checks, and leave time between slow scans for BlueMap to catch up.
- Fix connected players getting stuck when reloading edited seasonal colors.
- Check leftover winter markers and damaged metadata before reporting safe removal, with clearer errors in `/ts migration details`.

## 0.3.0

- Ease into calendar colors over the season's opening week, with whole autumn trees changing at different times. Manual season changes remain immediate.
- Give a fixed third of oak, birch and dark oak trees a spring blossom appearance, in game and on BlueMap. Their original leaves and drops stay unchanged.
- Add sparse summer fireflies near water and sheltered greenery at dusk and night. Oceans are excluded and added sounds are off by default.
- Keep all transition progress paused across restarts. Calendar mode catches up to the current date on resume; manual mode keeps its saved target.
- Reuse prepared colors between joins and avoid extra BlueMap renders for gradual color changes.
- Use orange for hedges and autumn leaves waiting for nearby tree data. Previously resolved colors stay consistent, and cherry stays red.
- Reduce repeated work when coloring forests, handling unusually large trees and checking BlueMap progress.

## 0.2.2

- Reduce background work when discovering saved chunks and checking migration progress on disk.
- Improve chunk tracking performance in densely generated worlds.
- Keep damaged, oversized or moving chunk data pending for safe revalidation.

## 0.2.1

- Keep autumn leaf colors consistent as chunks load, reload and receive block updates.
- Repair leaves waiting for nearby tree data, including longer branches, with fewer unnecessary color updates.
- Prioritize nearby snow previews during join and travel catch-up, within the existing shared work limits.
- Keep terrain migration running when a dying or respawning player's visible chunks are temporarily unavailable.
- Show saved pause reasons in migration output and notify admins when a world or integration failure pauses migration.
- Count distinct BlueMap regions across terrain and color updates, including work carried over from an earlier transition.

## 0.2.0

- Make winter snow appear across unfinished visible terrain and replace diagonal bands with a scattered pattern.
- Melt snow gradually as regions restore, revealing saved plants underneath. Resume remaining snow previews after chunk reloads and restarts.
- Process regions in a spiral from the world origin, finishing each region before moving outward.
- Improve snow catch-up when players join or travel. Share configurable update limits across players and reduce repeated work during snow placement.
- Let players clear preview snow, respecting the configured refill delay.
- Shorten command output and keep BlueMap to one summary row. Add optional five-second progress updates with `/ts migration notify`.
- Announce new seasons to players, with separate migration start and completion notices for admins and operators. Both can be disabled.
- Rewrite the README around the plugin's features and move detailed settings into a server guide.

Known limitations: plants wait for their region's terrain pass, and new views catch up within limited update batches. BlueMap shows saved terrain rather than the gradual player view. Autumn has no gradual color fade, some unusually large trees or structures can retain their original appearance, and cherry ground petals remain pink.

## 0.1.11

- Reveal winter snow gradually across the terrain players can see, while keeping background updates in region order.
- Extend the local snowstorm to visible chunks. BlueMap still renders saved regions without extra updates for the gradual reveal.
- Match grass, podzol and mycelium sides to the snow reveal, and keep the opening storm running until players' final snow updates finish.
- Fix green patches in overlapping autumn tree crowns and add seasonal colors to player-placed leaves and hedges.
- Keep hot biomes free of autumn grass and tree changes, in game and on BlueMap.

Known limitations: snow in unfinished terrain can lag behind the transition percentage, and its distribution can form visible bands. Cherry ground petals remain pink; there is no matching red vanilla appearance.

## 0.1.10

- Give autumn trees stable yellow, orange or red leaf appearances without a resource pack or saved leaf changes. Include birch, make cherry red only and leave spruce unchanged.
- Restore original leaf appearances when autumn ends, while preserving player edits and ordinary leaf properties.
- Fix seasonal grass and leaf colors reverting to vanilla on BlueMap between renders. Match autumn tree appearances on the map.
- Keep autumn coloring within existing terrain without loading extra chunks.

## 0.1.9

- Add seasonal grass and leaf colors, including autumn tones for biome-tinted leaves. Spruce and other leaves with fixed colors keep their normal appearance.
- Improve BlueMap updates during season changes and cleanup, with saved progress after a restart.
- Restore nearby winter snow accumulation, limited by the shared transition percentage without changing region migration order.
- Keep player-broken snow's refill delay during local accumulation.
- Update the author credit to RokeyDoess and add a clickable GitHub link in plugin details.

Known limitations: BlueMap grass and leaf colors may remain vanilla after rendering. Autumn uses one foliage tint per biome; per-tree color variation, colored birch and red autumn cherry leaves are not included. Leaves with fixed colors keep their usual appearance.

## 0.1.8

- Fix spring restoration getting stuck when dirt beneath saved plants naturally becomes grass or mycelium. Restore plants without changing the current ground.

## 0.1.7

- Finish one region before moving to the next. Player movement no longer scatters seasonal updates across the world.
- Temporarily replace exposed grass, flowers, ferns, bushes, mushrooms and leaf litter with winter snow. Restore the original plants when winter ends, while preserving player changes. Saplings, leaves and crops stay in place.
- Keep hot biomes out of winter snow and snowfall by default. Climate exclusions are configurable.
- Show the active region in migration status.
- Reduce startup and season-change workload on large worlds.
- Fix BlueMap updates that could remain pending after terrain changes had finished.
- Preserve saved plants when snow melts naturally during restoration.

## 0.1.6

- Wait for a region's seasonal changes to finish and save before requesting a BlueMap update.
- Combine map updates and skip renders when no blocks changed.
- Show why map updates are waiting and how many region renders have been scheduled.
- Reduce overhead from snow replenishment, migration queues and BlueMap updates.
- Improve recovery of pending map updates after a restart.

## 0.1.5

- Give player-broken snow a configurable refill delay, defaulting to 60 seconds.
- Keep that delay through clear weather, pauses and chunk unloading.
- Expand installation, configuration and clean-removal guidance.

## 0.1.4

- Add colored command output and a clear Migration status heading.
- Match nearby snow accumulation to the shared winter transition percentage.
- Replenish missing snow during winter rain after terrain updates finish.
- Recover saved migration progress after a restart or unchanged settings reload.
- Improve recovery of pending BlueMap updates, including deleted terrain.
- Reduce the cost of checking server performance.

## 0.1.3

- Keep the introductory snowstorm running until terrain updates finish, including pauses and restarts.
- Save winter transition coverage so newly loaded terrain can catch up.
- Fix progress jumping to 100% and then falling back during repeated terrain scans.
- Reduce terrain-loading overhead and remove an incorrect reconnect warning during disconnects.

## 0.1.2

- Use Minecraft's native snowfall for winter rain and thunderstorms, without changing saved biomes.
- Include newly generated terrain in seasonal updates.
- Improve restart recovery and BlueMap update reliability.
- Protect winter snow from natural melting and strengthen cleanup checks.

## 0.1.1

- Prioritize seasonal updates around players.
- Cover exposed terrain, roofs and leaves wherever Minecraft supports a snow layer.
- Add gradual nearby snow accumulation and an introductory winter storm.
- Preserve custom settings during upgrades.

Background updates follow region order since 0.1.7. Nearby gradual snow accumulation returned in 0.1.9 without changing that order.
