# Changelog

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

Player priority and scattered accumulation were replaced by region-by-region updates in 0.1.7.
