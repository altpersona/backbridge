# Changelog

All notable changes to this project are tracked in this file.

Update the `Unreleased` section whenever gameplay behavior, controls, compatibility, placement logic, or player-facing messages change. Historical entries for `0.1.0` through `0.1.18` were reconstructed from the archived source jars in `build/libs`, because the Git history currently only preserves the `v0.1.9` tag.

## [Unreleased]

No unreleased changes yet.

## [0.1.21]

### Changed

- Build-up mode now honors inventory exhaustion mode from the existing `H` toggle.
- Retuned build-up placement to retry every tick during the jump placement window instead of using the slower horizontal placement cadence.
- Relaxed build-up placement previewing so the real placement call can handle the tight player-collision timing while the mod still validates target position, block state, and survival.

## [0.1.20]

### Added

- Added the `J` hotkey to build upward by holding jump and placing blocks against the last confirmed support block.

### Fixed

- Expanded whitelisted obstacle clearing to cover grass, ferns, and crops such as wheat in addition to flowers.

## [0.1.19]

### Added

- Added this in-repo changelog and linked it from the README so release notes live beside the code.

### Changed

- Locked active runs to the walk surface height captured at start, instead of waiting for the player to physically step onto a bad support block before noticing a Y-level change.
- Tightened placement previewing to follow vanilla block placement more closely by applying placement-context updates plus placement, survival, and obstruction checks before each block is actually placed.

### Fixed

- Prevented full blocks in the lane from silently stepping the bridge upward and letting the run continue several blocks ahead of the player.
- Kept the working lower-slab behavior while rejecting placements whose final walk surface would leave the original path height.
- Restored whitelisted torch and flower clearing before bridge placement by deferring placement prediction until after the target space is actually clear.

## [0.1.18]

### Added

- Added per-session path surface tracking so slab runs could remember the support height they started on.
- Added predicted placement validation before accepting a new target block, including target-position and block-state checks.

### Changed

- Sampled the starting support position once at run start and re-checked the player's current support surface during the session.
- Validated the actual placed block state before promoting it to the next support block.

### Fixed

- Reduced cases where slab placement could continue after the path surface changed relative to the original run height.

## [0.1.17]

### Changed

- Removed the explicit slab path raising helper and simplified target selection back to the direct next block in the lane.
- Required the next target itself to be replaceable or a whitelisted obstacle before the run would continue.

## [0.1.16]

### Added

- Added inventory exhaustion mode on `H` for survival runs.
- Added matching-stack refills from the rest of the inventory into the selected hotbar slot, preferring the largest compatible stack.
- Added start-position anchoring through `findStartSupportPos(...)` so runs begin from the actual surface under the player.
- Added slab step-up planning when the current support was a single slab and the next base could support an elevated target.

### Changed

- Runs can now continue until matching blocks are exhausted instead of stopping at the original selected-stack count when inventory exhaustion mode is enabled.
- Start messages now distinguish normal capped runs from inventory-exhaustion runs.

## [0.1.15]

### Fixed

- Switched whitelisted obstacle clearing to the directional block-destroy call, improving alignment with the normal client break path.

## [0.1.14]

### Fixed

- Anchored new runs to the surface the player is actually standing on, which fixed starts on lower slabs that previously dropped to the block underneath.

## [0.1.13]

### Added

- Added `PlacementStep` planning with separate target position, click support, click side, and obstacle-clearing state.
- Added slab-aware click positioning through `resolveClickSupportPos(...)` and `createPlacementHitPos(...)`.

### Changed

- Reworked placement planning so slabs could place from a lower support block instead of always clicking the previous bridge block.
- Centralized target validation behind shared placeable-or-clearable checks.

## [0.1.12]

### Added

- Added `resolveBottomSlabOnLowerBase(...)` so bottom slabs can be placed directly on the lower base block when that produces a cleaner path.

### Changed

- The planner now tries lower-base slab placement first, then falls back to the earlier elevated-slab path when needed.

## [0.1.11]

### Added

- Added explicit slab-aware next-step planning through `resolveNextPlacement(...)`.
- Added separate click support and click side tracking so placements could target either the previous support block or the block underneath the target.
- Added upward bottom-slab fallback placement when the direct forward base was blocked but could still support a slab above it.

## [0.1.10]

### Added

- Added whitelisted path-obstacle clearing for regular torches, wall torches, and flowers before placing the next bridge block.
- Added explicit obstacle-clearing session state so destruction and placement could be paced across ticks.

### Changed

- Confirmation logic now distinguishes between clearing a path obstacle and confirming a newly placed bridge block.
- Redstone torches are intentionally left alone by the obstacle-clearing whitelist.

## [0.1.9]

### Changed

- Archived gameplay source matches `0.1.8`; no BackBridge client-logic delta was preserved in the bundled source jars for this release.

## [0.1.8]

### Changed

- Tried torch placement after every confirmed bridge block instead of only every fourth block.
- Made torch placement light-aware so torches are only attempted when local block light is `1` or lower.

## [0.1.7]

### Fixed

- Restored automatic crouch-hold during active runs after the previous release temporarily stopped forcing it.

## [0.1.6]

### Changed

- Temporarily stopped forcing crouch during active runs while still holding backward movement automatically.

## [0.1.5]

### Added

- Added a short placement confirmation window before treating the next target as successfully placed.
- Added an empty-stack grace window to avoid ending a run early during client/server inventory sync delays.
- Added bridge-item tracking so a run cancels if the selected slot changes to a different block type.

### Changed

- Required both the current support block and the next target to stay within interaction range before placement attempts continue.
- Hardened torch placement with interaction-range and block-survival checks.

## [0.1.4]

### Added

- Added automatic offhand torch placement every fourth confirmed bridge block.

### Changed

- Removed the short forced forward recovery phase after a run ended and returned movement control directly to the player's real key state.

## [0.1.3]

### Added

- Added a short post-run forward recovery window to smooth the handoff after a run finished, stalled, or was cancelled.
- Split movement handling into active-session movement and recovery movement phases.

## [0.1.2]

### Added

- Began holding backward and crouch automatically while a run is active.
- Restored movement keys to their physical pressed state when the mod releases control.

### Changed

- Failed placement attempts now consume an attempt and cool down instead of stalling the entire run immediately.

## [0.1.1]

### Changed

- Archived gameplay source matches `0.1.0`; no BackBridge client-logic delta was preserved in the bundled source jars for this release.

## [0.1.0]

### Added

- Initial release of the Fabric client mod.
- Added the `G` hotkey to place a straight line of up to `64` blocks behind the player from the selected hotbar slot.
- Added paced multi-tick placement, basic placed/cancelled/stalled feedback messages, and cancellation when the selected slot no longer holds a block item.
