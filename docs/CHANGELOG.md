# 0.1.5

- Let players clear snow without an immediate refill. Replace the broken-snow priority queue with a configurable grace period, defaulting to 60 seconds, before ordinary winter snowfall may replenish that column.
- Apply the same grace to random refill, direct refill and native snow formation. Keep it across clear weather, pauses and chunk unload/reload; preserve explicit player placement and ignore canceled breaks.
- Bound cooldown memory and expiration work; add monotonic-clock and real Paper block/event regressions.
- Rewrite the README for server owners: packaged installation, configuration, commands, permissions, map updates, clean removal and current feature limits. Remove merge-conflict markers and development build instructions.

# 0.1.4

- Use colored native chat components for administration messages, with a Migration status header, cyan progress/coverage, distinct TPS colors and highlighted map/deferred counts.
- Cap local winter accumulation at shared coverage. Advance shared stages together when only local work remains and allow metadata finalization of pending cursor 256.
- Cache TPS aggregation for one second across migration, replenishment and status commands.
- Hydrate progress from saved chunk PDC on restart without replaying completed chunk loads. Preserve versions on unchanged configuration reload; apply radius changes for stationary players.
- Replenish broken winter snow during rain in loaded, completed chunks with bounded sampling, block-form events and durable ownership/map ordering. Preserve vegetation and existing snow layers.
- Recover map requests for verified removed chunks/regions through forced region cleanup and actual render/save receipts. Keep fresh unsaved edits protected, coalesce missing-region work and display specific waiting reasons.
- Fence obsolete completion after reentrant season events, prune deleted priority requests and revalidate saved-only completion against changed disk metadata.
- Add command-color, coverage, saved checkpoint, replenishment and deleted-source map recovery regressions; extend live validation with completed-winter restart and rain replenishment.

# 0.1.3

- Hold the introductory snowstorm for the entire terrain migration, including pauses and normal restarts; restore prior weather timers on completion. Remove the obsolete fixed-duration setting.
- Persist shared winter coverage across restarts. Newly encountered terrain catches up to that coverage before continuing gradual accumulation near players.
- Keep unchanged protochunks excluded during rediscovery instead of repeatedly inflating the migration denominator. Add only newly completed terrain to the workload and reserve 100% for accounted work. Report pending BlueMap work separately.
- Inspect saved chunk generation status off the server thread instead of calling Paper's blocking generation check during loads. Read TPS once per migration tick.
- Suppress the false reconnect warning for disconnecting players; recover an existing initialized channel handler when available.
- Add saved-status, progress, coverage and disconnect regressions, plus live storm lifetime, pause, restart and unchanged-rescan checks. Analyze the supplied spark capture in `docs/profile/fGwY7OOKVx-report.md`.

# 0.1.2

- Replace simulated precipitation with Minecraft's native snow renderer for every winter rain/thunderstorm. Use client-only cold biome aliases and restore original biome IDs on season/world changes, cleanup and disable; preserve saved biome data and shared packet objects.
- Requeue newly completed terrain, including known protochunks, and invalidate matching stamps with pending/cursor metadata. Completion requires all known work to be accounted for; unavailable terrain is not counted as migrated.
- Replace full-queue YAML rewrites with small ordered durable journal records and background snapshot compaction.
- Retain BlueMap final revisions until saved chunk data and each map's actual render/save completion are proven. Preserve newer revisions across in-flight renders and retain requests on cancellation, failure, freeze or restart.
- Verify temporary map proof metadata removal during cleanup. Keep map work durable when rendering is disabled.
- Protect tracked winter snow from natural melting, preserve configured values while removing obsolete particle controls, and bump the snow model revision for existing-world repair.
- Add actual Minecraft packet/biome decoder tests, migration completion regressions, BlueMap persistence/receipt tests and a live Paper configuration-channel probe.

# 0.1.1

- Prioritize a square radius around each player for seasonal migration and cleanup, ordered by nearest rings. Default: four chunks (9x9 area). Reserve background processing time and share the application/TPS budget.
- Cover every exposed surface that supports vanilla snow, including buildings and leaves. Use the highest non-air surface so roofs shield the ground below. Preserve existing snow, occupied vegetation, fluids and unsupported shapes.
- Accumulate winter snow in scattered column batches near player neighborhoods. Persist the partial cursor; remote chunks finish in one pass and spring/cleanup remove snow in one pass.
- Start a temporary winter snowstorm, journal and restore previous world weather, and use localized client snowflake effects with rain suppressed. Restore prior player weather on exit/cleanup/disable.
- Add missing commented configuration options while preserving customized values. Upgrade only the old shipped biome exclusion list to the new all-biomes default.
- Include the surface model revision in chunk versions so existing 0.1.0 chunks are revisited on upgrade. Snow ownership metadata remains backward compatible.
