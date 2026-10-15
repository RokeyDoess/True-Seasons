# Getting help

Start with the [README](README.md) for features and installation, or the [server guide](docs/SERVER_GUIDE.md) for settings, commands and clean removal. The plugin is a prerelease; the server guide lists the versions that have been tested.

- Ask setup questions and share feedback using the question template in [Issues](https://github.com/RokeyDoess/True-Seasons/issues/new/choose).
- Report reproducible problems through [Issues](https://github.com/RokeyDoess/True-Seasons/issues/new/choose).
- Check the [roadmap](docs/ROADMAP.md) before suggesting a feature.

For troubleshooting, include your plugin and server versions, relevant errors, and `/ts status`. For BlueMap problems, include its version and whether it is rendering, stopped or frozen. Remove private information before sharing logs.

## Common questions

**Does this need a resource pack?** No. Seasonal colors use Minecraft's existing appearances.

**Why is BlueMap behind the world?** Terrain changes must finish and save before their final map update. A pending map queue can remain after world migration finishes. Keep autosaving enabled and allow BlueMap to render.

**Why are some autumn leaves still green?** Unavailable terrain and unusually large trees or structures can retain their original appearance. Nearby chunk updates can finish the colors as terrain arrives. Eligible player-placed leaves and hedges are supported too; see the server guide for leaf types and settings.

**Can I remove the jar?** Complete the cleanup procedure in the README first. Snow and stored plants need restoration even though packet colors do not change saved biomes or leaf blocks.
