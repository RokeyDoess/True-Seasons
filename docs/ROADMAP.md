# Next milestones

1. Extend the Paper 26.3 native precipitation adapter to consume configured seasonal foliage palettes. Verify reconnect, dimension changes, plugin removal and cleanup visually with an actual client.
2. Make BlueMap's vegetation colors consume the same resolver, with version-specific capability checks and restoration of render configuration. Physical snow updates already use durable final revisions and per-map render/save receipts.
3. Add load testing on a large pregenerated world and a player-driven test for event ownership, player priority, TPS throttling, and chunk save/crash boundaries. The automated fixture covers normal restarts, not power-loss simulation.
4. Improve large-world index memory and progress persistence if profiling warrants it; keep chunk PDC authoritative even if a compact advisory cursor is added.
5. Add Distant Horizons as a separate adapter only after its server helper/API is ready. No DH implementation belongs in this release.
