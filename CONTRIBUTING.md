# Contributing

Bug reports, documentation fixes and focused pull requests are welcome.

Check existing [issues](https://github.com/RokeyDoess/True-Seasons/issues) and the [roadmap](docs/ROADMAP.md) before opening something new. Discuss larger changes first so the approach can be agreed before you spend time implementing it.

## Reporting a problem

Use the bug report form. Include your Paper, Java, True Seasons and BlueMap versions, the steps that led to the problem, and relevant `/ts status` output. Screenshots are useful for snow and foliage problems. Share only the logs needed to explain the issue, with private information removed.

Use the question template in Issues for setup help and general feedback. Security problems should follow [the private reporting instructions](SECURITY.md).

## Pull requests

Keep each pull request focused on one problem. Explain what changes for server owners and how you checked it. Update the documentation when commands, settings or visible behavior change.

World preservation and performance matter. Preserve player edits and natural terrain, keep temporary changes recoverable, and avoid loading or generating terrain unnecessarily. Changes to snow, plants or map updates need checks for interrupted transitions and cleanup, as well as the normal path.

Leave server folders, worlds, logs, profiler captures and local analysis out of commits. Add regression tests for behavior that could damage saved progress or return after a restart.

## Build checks

Use Java 25 and Gradle 9.1.0 or later. Run `gradle check jar` to compile the plugin, run the standalone checks and build a jar in `build/libs`. GitHub runs these checks for code changes and pull requests.

These checks do not start a Minecraft server or run the native packet and live-world probes. Changes to packet handling, temporary terrain edits or BlueMap rendering need separate testing on a backed-up test world.

Contributions use the project's [GPL-3.0 license](LICENSE).
