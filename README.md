# BackBridge

A tiny Fabric client mod for Minecraft `26.1.2` that places a straight line of up to `64` blocks behind the player from the currently selected hotbar slot.

Detailed release notes and in-progress changes are tracked in [CHANGELOG.md](CHANGELOG.md).

## Latest Release

Current release: `0.1.23+mc26.1.2`

- `G` builds the horizontal backward line.
- `J` builds upward while holding jump.
- `H` toggles inventory exhaustion mode for both build modes.
- Release jars are published on the GitHub releases page.

## Usage

1. Put a placeable block in your selected hotbar slot.
2. Stand on the first block of the bridge or path.
3. Face the direction you want to look while the line extends behind you.
4. Press `G`.
5. Press `J` to build upward instead. The mod holds jump and places blocks against the top of the last confirmed block below you.
6. Press `H` first if you want inventory exhaustion mode for either `G` or `J`, which keeps refilling the selected slot from matching stacks in your inventory until those matching blocks run out or the path stalls.

The mod tries to place one continuous horizontal line behind the block you are standing on. It stops when:

- it has placed `64` blocks,
- you run out of blocks,
- the path is blocked,
- the selected item is not a block.

Placement is paced over multiple game ticks instead of trying to fire all `64` block placements at once. Horizontal placement waits `4` ticks between placement attempts, while build-up retries each tick during the jump window, and both modes report the number of blocks that actually got confirmed in the world.
While a run is active, the mod holds backward and crouch for you so it can stay within normal placement range.
Your mouse still controls the direction of travel while the run is active, so moving the camera will also steer where the line continues.
If your offhand is holding torches, the mod tries to place one on top of a confirmed bridge block when the local block light at the torch position is `1` or lower, and only when the torch is in range and the block can actually support it.
If the next bridge position contains a regular torch, flower, grass, fern, or crop, the mod tries to clear that block first through the normal client block-break path before placing the bridge block there. Redstone torches are intentionally left alone.
If your selected block is a slab, the placer prefers placing bottom slabs from the top face of the block underneath the target whenever that produces a cleaner result, including when it needs to clear a whitelisted obstacle first.
If you start the run while standing on a slab, the line now anchors to that slab's level instead of dropping to the block underneath it.

## Notes

- This is a client-side Fabric mod.
- It is pinned to Minecraft `26.1.2`; Minecraft mods are not truly version agnostic across API and mapping changes.
- Minecraft `26.1.2` requires Java `25` or newer.
- It uses Mojang mappings so moving forward across `26.1.x` should be easier than a Yarn-based setup.
- The selected hotbar slot is used as requested for the simplest workflow.
- If you change the selected slot while a run is in progress, the current run is cancelled.
- Inventory exhaustion mode only affects non-creative runs. In that mode the selected hotbar slot stays fixed, and BackBridge swaps matching stacks from the rest of your inventory into that slot as it empties.
- In survival mode, using the feature makes you an easy target because your movement is constrained while the run is active.
