# BackBridge

A tiny Fabric client mod for Minecraft `1.21.11` that places a straight line of up to `64` blocks behind the player from the currently selected hotbar slot.

## Usage

1. Put a placeable block in your selected hotbar slot.
2. Stand on the first block of the bridge or path.
3. Face the direction you want to look while the line extends behind you.
4. Press `G`.

The mod tries to place one continuous horizontal line behind the block you are standing on. It stops when:

- it has placed `64` blocks,
- you run out of blocks,
- the path is blocked,
- the selected item is not a block.

Placement is paced over multiple game ticks instead of trying to fire all `64` block placements at once. The current build waits `4` ticks between placement attempts and reports the number of blocks that actually got confirmed in the world.
While a run is active, the mod holds backward and crouch for you so it can stay within normal placement range.
Your mouse still controls the direction of travel while the run is active, so moving the camera will also steer where the line continues.
If your offhand is holding torches, the mod tries to place one on top of a confirmed bridge block when the local block light at the torch position is `1` or lower, and only when the torch is in range and the block can actually support it.

## Notes

- This is a client-side Fabric mod.
- It is pinned to Minecraft `1.21.11`; Minecraft mods are not truly version agnostic across API and mapping changes.
- It uses Mojang mappings so moving forward from `1.21.11` to `26.1+` should be easier than a Yarn-based setup.
- The selected hotbar slot is used as requested for the simplest workflow.
- If you change the selected slot while a run is in progress, the current run is cancelled.
- In survival mode, using the feature makes you an easy target because your movement is constrained while the run is active.
