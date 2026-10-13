# What These Scripts Do

## `gametest.cmd` (repo root) / `gametest.ps1`
Runs the in-game tests and shows only each test's progress and a PASSED/FAILED summary with the reason (full output: `build/gametest-output.log`). From the repo root: `.\gametest` (PowerShell) or `gametest` (cmd); `gametest mace scaffold` runs only the tests whose name contains one of those words, `gametest anticheat` (or `grim`, `intave`) runs only the real anticheat servers (see `gradle/anticheat.gradle`), `-mods` runs with Sodium etc., `-update` re-records the screenshot templates, `-help` lists this. Don't click or close the game window while it runs.

## `genSources-eclipse.cmd`
Performs all the necessary setup so you can use this project in Eclipse. You need to run this script again every time that the Yarn mappings, Fabric loader or Fabric API in [gradle.properties](/gradle.properties) change. (Keep in mind that this also happens every time that you switch to a different Minecraft version.)

## `build.cmd`
Compiles the project into a `.jar` file that you can place in your `mods` folder. **This will not work without running `genSources-eclipse` first!**

## `migrateMappings_v2.cmd`

For switching to different Yarn mappings (e.g. when updating to a new Minecraft version). After running this script, update [gradle.properties](/gradle.properties) with the new versions, then re-run `genSources-eclipse`.

## `migrateMappings.cmd`

**Old version.** For switching to mappings that don't end in `:v2`. These only exist for Minecraft 1.14.x and some old snapshots. You won't need this in 1.15+.

## `downloadAssets.cmd`

To fix missing assets (no sound, missing textures, etc). Mostly happens when using the **old** `migrateMappings` script.

## `premerge.cmd`

To download a pull request and try it out locally before merging.
