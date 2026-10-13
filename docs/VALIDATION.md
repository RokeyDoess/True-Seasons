# Development build validation — October 12, 2026

## 0.1.5

The standalone build passed **1,230 assertions**, including the previous 1,095 checks and **135 new snow-break cooldown checks**. These exercise exact expiry, repeated breaks, world/column isolation, clear-weather eligibility, canceled/non-snow breaks, native snow formation, random/direct filtering, the post-event commit guard, zero configuration, monotonic clock wraparound and bounded cache maintenance.

The immediate broken-snow queue is removed. The default 60-second grace protects the horizontal column, so digging lower does not evade it. A spot becomes eligible for ordinary sampling after expiry; expiry schedules no forced refill. Native snow-formation events share the cooldown, and explicit player placement remains available. The cache holds at most 65,536 columns, reclaims expired records within bounded work and can evict older active records under exceptional capacity pressure. Cooldowns survive chunk unload/reload during one plugin instance; they are not persisted across a server restart.

The README was rewritten for GitHub visitors installing and operating the plugin. It documents the shipped settings and permissions, tested compatibility and current feature limits, and removes existing merge-conflict markers.

The focused real-Paper probe passed **13 checks**: adding the cooldown default to existing settings, verified sky lighting/support, a clear-weather break followed by rain, ordinary random sampling, digging to a lower surface, native block-form cancellation, exact expiry, retained ownership, repeated breaks, canceled breaks and zero new terrain. It uses an injected monotonic clock and a separate service on an already loaded fixture; it does not simulate a connected player's client interaction or repeat the unchanged full-world migration/BlueMap suite. Paper's test-plugin isolation and asynchronous lighting were accounted for in the fixture. Results are in `run/verification/snow-cooldown.log` and `snow-cooldown-result.txt`; the driver is `verify-snow-cooldown.ps1`.

Version 0.1.5 was installed in the stopped original test server after verification. Its SHA-256 is `DFBC1A91A90DCCD07E2867B4685A08E01A78921D24957E27222738B2026186CA`. The previous jar and plugin data were backed up under `run/original-state-before-0.1.5`; the original world was not started or edited. Background migration ordering and BlueMap render scheduling were reviewed but not changed in this build.

## 0.1.4

Environment: Java 25.0.4.1, Paper 26.3 build 177 and BlueMap 5.29. Only disposable copies of the test server are started during validation.

The final standalone build passed **1,095 assertions**: core 280, migration bookkeeping 64, shared coverage policy 12, command presentation 40, winter replenishment 124, saved chunk snapshots 30, snow completion 24, BlueMap reliability 33, deleted-source recovery 26, render/save receipts 22, durable journal 18 and native Minecraft packets/climate/palettes 422. These include real component text/colors, persisted pause/resume replies, TPS caching, saved completion and pending cursors, cap/deadlock boundaries, canceled/reentrant block-form events, ownership/map commit ordering and missing-region recovery. A separate read-only run against the original server state passed three additional recovery assertions.

The original stuck queue contained **12,033 final requests across 12 removed regions**, none overlapping the four remaining overworld regions. The recovery path verifies source absence, stages bounded durable batches and obtains actual forced region render/save receipts before retiring requests. Fresh unsaved world changes remain protected. The original queue is preserved until normal startup processes it.

The main live probe passed **79 checks across four normal starts**: 18 initial checks, 26 winter/restart checks, 28 completed-winter restart/removal/rescan checks and seven cleanup/restart checks. Both the final winter queue and the restored/cleanup queue drained to zero through the production BlueMap bridge, without a manual update. The copied obsolete 12-region queue was recovered during this run.

The copied overworld began with **4,096 fully generated chunks and region-header entries**. Winter migration preserved that header set. Explicit probe exploration generated one new full chunk and Minecraft's dependent protochunks, producing **4,573 header entries and 4,097 full chunks**. Subsequent spring migration and cleanup preserved that baseline. No original world files were started or edited.

Live checks verified the shared local coverage cap, storm pause/restart/completion, clear-weather refusal and rainy winter replenishment without reopening migration. Unchanged reload preserved the revision and counters. A completed-winter restart hydrated saved progress in less than ten seconds, with zero seasonal snow writes and no new introductory storm. Spring prioritized neighboring chunks, preserved player blocks and vegetation, removed owned snow, and retained complete progress across unchanged rediscovery. Cleanup survived restart and verified removal of map proof markers on disk.

Two additional starts against the final jar passed **three production map-worker/final-save checks** and **ten checks without BlueMap/BMMarker**, for **92 live-server checks in total**. Final results and logs are in `run/verification`: `cycle-1.log` through `cycle-4.log`, `verification-result.txt`, `map-finalization.log`, `map-finalization-result.txt`, `optional.log` and `optional-result.txt`. The final presentation also keeps completed worlds visibly complete during periodic rescans; pause/resume replies start with the same header. The focused component tests cover those final presentation changes.

The verified jar was installed as `test server/plugins/TrueSeasons-0.1.4.jar`, with SHA-256 `1DD8FDAD452E9677E83C5E4EDEE89CC7FCFE195F6D6E7A05D65003240AA52354`. The original server remains stopped. Its jobs and map queue were unchanged, and the previous jar and full plugin-data directory were copied to `run/original-state-before-0.1.4`. A rollback to 0.1.3 should restore that state backup as well as the old jar, because the new map ledger contains deletion-recovery fields.

Ground-cover replacement was deliberately not implemented. Existing vegetation, leaf litter and flowers remain intact.

Limits: native packet/climate behavior, physical snow blocks, durable metadata and real map storage receipts were tested; client snowfall/chat appearance and browser map appearance were not visually checked. No new spark performance comparison, multiplayer stress test or hardware power-loss test was performed. TPS caching is verified by call-count regressions rather than a new performance capture. Gradle was not executed; `build.ps1` is the verified build path.

## 0.1.3

Environment: Java 25.0.4.1, Paper 26.3 build 177, BlueMap 5.29, with the same unrelated testing plugins as the previous build.

The standalone build passed **858 assertions**: core 280, migration bookkeeping 50, saved chunk status snapshots 9, snow completion and coverage metadata 24, BlueMap reliability 33, render/save receipts 22, durable journal 18, and native Minecraft packets/climate/palettes 422. New regressions cover repeated unchanged protochunk rediscovery, true generation promotion, incomplete work below 100%, unreadable metadata recovery, modern/legacy generation status and multiple compression formats, partial coverage validation, disconnect handling, and obsolete failed/null callbacks after a migration restart.

The supplied winter-to-autumn spark capture was decoded and independently checked against the installed spark parser. Recorded TPS was approximately 20, mean MSPT 13.69, p95 19.74 and maximum 49.58. The synchronous generation check accounted for 19.14% of sampled server-thread wall time, including waits; repeated TPS queries accounted for 5.39%. Version 0.1.3 removes that generation-check path and reads TPS once per migration tick. This is a source-level correction informed by the capture, not a measured before/after performance comparison. See [the profile report](profile/fGwY7OOKVx-report.md).

The main real-server probe passed **73 checks across four normal server starts**: 18 initial checks, 21 winter/restart checks, 27 map-resume/removal/rescan checks, and seven cleanup/restart checks. The first map wait exceeded the previous nine-minute probe budget; the server shut down normally, retaining its final winter blocks and remaining map requests. The recorded checks were preserved, and the test resumed from that checkpoint with an increased budget. `timeout-result.txt` retains the initial timeout; `verification-result.txt` records the completed run. The standard verifier now allows 18 minutes of probe polling and 20 minutes per server start.

The copied original overworld had **11,205 region-header entries**, with **5,236** fully generated chunks. Winter migration preserved that exact header set. Explicit test exploration completed one indexed protochunk and its Minecraft generation dependencies, resulting in **11,234** saved entries and **5,237** full chunks. Subsequent removal and cleanup preserved that baseline. No original world was started or edited; only the disposable clone was used.

New live checks verified that the storm survives natural expiry, pauses and normal restart, then restores the original clear-weather timer when terrain work finishes. A newly encountered nearby chunk caught up to the shared 50% floor, and the floor survived restart. Full winter completion reached 100% shared coverage. Unchanged disk rediscovery preserved the completed total and displayed 100%; map work remained separate. Both winter and restored map requests drained through production render/save receipts without a manual BlueMap update. The disconnected-player warning regression passed on the real Paper runtime. Existing ownership, exposed surface, conflict, neighborhood cleanup, new-terrain and unloaded-transition checks also passed.

A fifth server start passed **10 checks** without BlueMap/BMMarker, verifying saved-world cleanup, zero new chunks, preserved custom settings and removal of the old storm-duration option. This totals **83 live-server checks**. Logs and results for 0.1.3 remain in `run/verification-0.1.3`; the one-time checkpoint recovery driver is `run/finish-verification.ps1`. The extra isolated BlueMap final-worker/save probe was already verified for 0.1.2; its unchanged implementation was exercised again by the full production queue drain here.

Limits remain: a connected Minecraft client's snowfall appearance and a browser's rendered map appearance need a visual check. Native packet/climate behavior, real physical blocks, saved world data and actual map receipts were verified. No post-change spark comparison, multi-player stress run, or hardware power-loss test was performed; Gradle was not executed.

## Previous 0.1.2 validation

Version: **0.1.2**. Environment: Java 25.0.4.1, Paper 26.3 build 177, BlueMap 5.29. The cloned stack also contained EssentialsX, VaultUnlocked, Chunky and BMMarker; only BlueMap has an integration adapter.

The standalone build passed **804 assertions**: calendar/region/neighborhood core 280, migration bookkeeping 16, snow completion metadata 13, BlueMap reliability 33, render/save receipts 22, durable journal 18, and native Minecraft packets/climate/palettes 422. The packet suite uses actual Minecraft registry stream codecs, biome climate decoding and vanilla palette decoding rather than a simulated snow effect.

The main real-server probe passed **60 checks across three normal server starts**. A fourth start passed **three checks** exercising the production BlueMap final-worker/save wrapper against real map storage. A fifth start passed **nine checks** for startup, saved-world cleanup and configuration upgrades with BlueMap/BMMarker absent. This totals **72 live-server checks** across five starts. Those results and logs remain in `run/verification-0.1.2`.

The original overworld had **5,479 region-header entries**, of which **2,569** were fully generated. Winter migration retained precisely the original header set. The probe then explicitly requested Minecraft generation of one previously indexed protochunk, with a deterministic exposed grass support to make its snow test independent of ocean/naturally snowy terrain. Minecraft's generation dependencies expanded the saved header baseline to **5,546** entries and **2,570** full chunks. Subsequent spring migration, cleanup and map verification retained that baseline. The plugin's engine and map-marker purge loads always forbid generation. The original test-server world was never started or edited during verification.

Verified on the live server:

- Actual Paper initial configuration connection/channel lookup and authoritative biome materialization for known-pack entries with omitted payloads.
- Existing snow and differing player blocks preserved; compact ownership, pause, season override and partial winter cursor persisted across restart.
- Gradual accumulation covered wooden roofs, leaves, glass and upper slabs. Sheltered ground, vegetation and unsupported shapes remained intact.
- A known protochunk joined winter migration promptly when test exploration finished generation. Its exposed support received tracked physical snow without restarting the season.
- A matching season stamp with a pending cursor reopened work and was fully completed.
- An unfinished winter chunk outside the player radius unloaded, resumed in background work and retained its finished snow. BlueMap's winter queue drained without a manual map-update command.
- Spring removal reached a neighboring chunk within the five-second test deadline. Full-world work continued with no players online.
- Tracked winter snow resisted natural fade events. Ambiguous edited layers retained ownership and blocked cleanup; differing replacements and unowned/player snow were preserved.
- Cleanup resumed after restart, finished all available chunks, verified temporary map-marker removal and reported removal-safe status.
- Real BlueMap native tile work and the extra final save barrier completed successfully. The wrapper checks native render/chunk error flags, pending low-resolution writes and modified state storage before issuing a receipt.
- The current pipeline held approximately 20 TPS during the accelerated four-load/four-application test. Growing whole-ledger YAML writes were replaced with small forced journal records and background snapshots.

The journal suite also simulates process loss before graceful close, torn trailing writes, newer mutations during compaction and a crash between atomic snapshot replacement and journal truncation. Checksum corruption fails closed. These are persistence simulations, not a power-loss test.

Main logs: `cycle-1.log`, `cycle-2.log`, `cycle-3.log`. Extra checks: `map-finalization.log`, `optional.log`. Results: `verification-result.txt`, `map-finalization-result.txt`, `optional-result.txt`.

Limits: actual snowfall appearance with a connected Minecraft client and rendered map appearance in a browser still need a visual check. Real packets, vanilla climate decisions and map task/storage completion were verified. No multi-player stress test or abrupt hardware power-loss test was performed. The verified build path is `build.ps1`; Gradle was not executed.
