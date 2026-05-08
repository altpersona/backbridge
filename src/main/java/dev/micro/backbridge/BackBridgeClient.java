package dev.micro.backbridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;

public final class BackBridgeClient implements ClientModInitializer {
    private static final int MAX_PLACEMENTS = 64;
    private static final int PLACE_INTERVAL_TICKS = 4;
    private static final int BUILD_UP_PLACE_INTERVAL_TICKS = 0;
    private static final int BUILD_UP_CONFIRM_TICKS = 1;
    private static final int BUILD_UP_MAX_ATTEMPTS_PER_BLOCK = 20;
    private static final double BUILD_UP_PLACE_HEIGHT_EPSILON = 0.05D;
    private static final int MAX_ATTEMPTS_PER_BLOCK = 8;
    private static final int PLACEMENT_CONFIRM_TICKS = 2;
    private static final int EMPTY_SYNC_GRACE_TICKS = 10;
    private static final KeyMapping PLACE_LINE_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping(
            "key.backbridge.place_line",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.MISC
        )
    );
    private static final KeyMapping TOGGLE_INVENTORY_EXHAUSTION_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping(
            "key.backbridge.toggle_inventory_exhaustion",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            KeyMapping.Category.MISC
        )
    );
    private static final KeyMapping BUILD_UP_KEY = KeyMappingHelper.registerKeyMapping(
        new KeyMapping(
            "key.backbridge.build_up",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            KeyMapping.Category.MISC
        )
    );
    private static PlacementSession activeSession;
    private static BuildUpSession activeBuildUpSession;
    private static boolean inventoryExhaustionModeEnabled;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_INVENTORY_EXHAUSTION_KEY.consumeClick()) {
                toggleInventoryExhaustionMode(client);
            }

            while (PLACE_LINE_KEY.consumeClick()) {
                startPlacement(client);
            }

            while (BUILD_UP_KEY.consumeClick()) {
                startBuildUp(client);
            }

            tickPlacement(client);
            tickBuildUp(client);
        });
    }

    private static void startPlacement(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.invalid_block"));
            return;
        }

        boolean useInventoryExhaustionMode = inventoryExhaustionModeEnabled && !player.isCreative();
        int maxPlacements = player.isCreative()
            ? MAX_PLACEMENTS
            : (useInventoryExhaustionMode ? Integer.MAX_VALUE : Math.min(MAX_PLACEMENTS, stack.getCount()));
        if (maxPlacements <= 0) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.nothing_placed"));
            return;
        }

        BlockPos startSupportPos = findStartSupportPos(player);
        double startSurfaceY = getSupportSurfaceY(client, startSupportPos);

        activeSession = new PlacementSession(
            player.getInventory().getSelectedSlot(),
            stack.copy(),
            blockItem,
            startSupportPos,
            player.getDirection().getOpposite(),
            maxPlacements,
            useInventoryExhaustionMode,
            startSurfaceY
        );
        activeBuildUpSession = null;
        restoreBuildUpKeys(client);
        setSessionMovement(client);
        player.sendOverlayMessage(
            useInventoryExhaustionMode
                ? Component.translatable("message.backbridge.started_inventory")
                : Component.translatable("message.backbridge.started", maxPlacements)
        );
    }

    private static void startBuildUp(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.invalid_block"));
            return;
        }

        boolean useInventoryExhaustionMode = inventoryExhaustionModeEnabled && !player.isCreative();
        int maxPlacements = player.isCreative()
            ? MAX_PLACEMENTS
            : (useInventoryExhaustionMode ? Integer.MAX_VALUE : Math.min(MAX_PLACEMENTS, stack.getCount()));
        if (maxPlacements <= 0) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.nothing_placed"));
            return;
        }

        activeSession = null;
        restoreMovementKeys(client);
        activeBuildUpSession = new BuildUpSession(
            player.getInventory().getSelectedSlot(),
            stack.copy(),
            blockItem,
            findStartSupportPos(player),
            maxPlacements,
            useInventoryExhaustionMode
        );
        setBuildUpMovement(client);
        player.sendOverlayMessage(
            useInventoryExhaustionMode
                ? Component.translatable("message.backbridge.build_up_started_inventory")
                : Component.translatable("message.backbridge.build_up_started", maxPlacements)
        );
    }

    private static void tickPlacement(Minecraft client) {
        PlacementSession session = activeSession;
        LocalPlayer player = client.player;

        if (session == null) {
            restoreMovementKeys(client);
            return;
        }

        if (player == null || client.level == null || client.gameMode == null) {
            activeSession = null;
            restoreMovementKeys(client);
            return;
        }

        setSessionMovement(client);

        if (player.getInventory().getSelectedSlot() != session.slotIndex) {
            cancelSession(client, player, session);
            return;
        }

        ItemStack stack = player.getInventory().getItem(session.slotIndex);
        if (stack.isEmpty() && session.untilInventoryExhausted && refillSelectedSlotFromInventory(client, player, session.slotIndex, session.bridgeTemplate)) {
            stack = player.getInventory().getItem(session.slotIndex);
        }

        if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, session.bridgeTemplate)) {
            cancelSession(client, player, session);
            return;
        }

        if (stack.isEmpty()) {
            session.emptyTicks++;
        } else {
            session.emptyTicks = 0;
        }

        BlockPos playerSupportPos = findStartSupportPos(player);
        if (!isSameSurfaceY(getSupportSurfaceY(client, playerSupportPos), session.pathSurfaceY)) {
            stallSession(client, player, session);
            return;
        }

        if (session.currentTarget != null) {
            BlockState currentTargetState = client.level.getBlockState(session.currentTarget);

            if (session.clearingObstacle) {
                if (!isWhitelistedPathObstacle(currentTargetState)) {
                    if (!currentTargetState.canBeReplaced()) {
                        finishSession(client, player, session.placed);
                        return;
                    }

                    session.clearingObstacle = false;
                    session.attemptsOnCurrentTarget = 0;
                    session.confirmTicksOnCurrentTarget = 0;
                }
            } else if (!currentTargetState.canBeReplaced()) {
                session.confirmTicksOnCurrentTarget++;

                if (session.confirmTicksOnCurrentTarget < PLACEMENT_CONFIRM_TICKS) {
                    return;
                }

                if (!isValidPathState(client, session, session.currentTarget, currentTargetState)) {
                    finishSession(client, player, session.placed);
                    return;
                }

                session.supportPos = session.currentTarget;
                session.currentTarget = null;
                session.clickSupportPos = null;
                session.attemptsOnCurrentTarget = 0;
                session.confirmTicksOnCurrentTarget = 0;
                session.cooldownTicks = PLACE_INTERVAL_TICKS;
                session.placed++;

                tryPlaceTorch(client, player, session.supportPos);
            } else {
                session.confirmTicksOnCurrentTarget = 0;
            }
        } else {
            session.confirmTicksOnCurrentTarget = 0;
        }

        if (!session.untilInventoryExhausted && session.placed >= session.maxPlacements) {
            finishSession(client, player, session.placed);
            return;
        }

        if (session.cooldownTicks > 0) {
            session.cooldownTicks--;
            return;
        }

        if (session.currentTarget == null) {
            if (!player.isCreative() && stack.isEmpty() && session.emptyTicks >= EMPTY_SYNC_GRACE_TICKS) {
                finishSession(client, player, session.placed);
                return;
            }

            PlacementStep nextStep = resolveNextPlacement(client, session);
            if (nextStep == null) {
                finishSession(client, player, session.placed);
                return;
            }

            session.currentTarget = nextStep.targetPos();
            session.clickSupportPos = nextStep.clickSupportPos();
            session.clickSide = nextStep.clickSide();
            session.clearingObstacle = nextStep.clearObstacle();
            session.attemptsOnCurrentTarget = 0;
            session.confirmTicksOnCurrentTarget = 0;
        }

        if (session.attemptsOnCurrentTarget >= MAX_ATTEMPTS_PER_BLOCK) {
            stallSession(client, player, session);
            return;
        }

        if (session.clearingObstacle) {
            if (!player.isWithinBlockInteractionRange(session.currentTarget, 0.0D)) {
                return;
            }

            if (!clearWhitelistedObstacle(client, player, session.currentTarget)) {
                session.attemptsOnCurrentTarget++;
                session.cooldownTicks = PLACE_INTERVAL_TICKS;
                return;
            }

            session.attemptsOnCurrentTarget++;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            return;
        }

        if (!player.isWithinBlockInteractionRange(session.clickSupportPos, 0.0D)
            || !player.isWithinBlockInteractionRange(session.currentTarget, 0.0D)) {
            return;
        }

        PredictedPlacement expectedPlacement = resolveExpectedPlacement(
            client,
            player,
            stack,
            session.currentTarget,
            session.clickSupportPos,
            session.clickSide,
            session.bridgeItem
        );
        if (expectedPlacement == null || !isValidPathState(client, session, expectedPlacement.targetPos(), expectedPlacement.state())) {
            finishSession(client, player, session.placed);
            return;
        }

        if (!placeAgainst(client, player, session.clickSupportPos, session.clickSide, session.bridgeItem)) {
            session.attemptsOnCurrentTarget++;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            return;
        }

        session.attemptsOnCurrentTarget++;
        session.cooldownTicks = PLACE_INTERVAL_TICKS;
    }

    private static boolean placeAgainst(Minecraft client, LocalPlayer player, BlockPos supportPos, Direction side, BlockItem bridgeItem) {
        Vec3 hitPos = createPlacementHitPos(supportPos, side, bridgeItem);
        BlockHitResult hitResult = new BlockHitResult(hitPos, side, supportPos, false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

        if (!result.consumesAction()) {
            return false;
        }

        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean clearWhitelistedObstacle(Minecraft client, LocalPlayer player, BlockPos targetPos) {
        BlockState targetState = client.level.getBlockState(targetPos);
        if (!isWhitelistedPathObstacle(targetState)) {
            return false;
        }

        if (!client.gameMode.startDestroyBlock(targetPos, Direction.UP)) {
            return false;
        }

        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private static boolean isWhitelistedPathObstacle(BlockState state) {
        return state.is(Blocks.TORCH)
            || state.is(Blocks.WALL_TORCH)
            || isClearablePlant(state);
    }

    private static boolean isClearablePlant(BlockState state) {
        return state.is(BlockTags.FLOWERS)
            || state.is(BlockTags.CROPS)
            || state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.getBlock() instanceof CropBlock
            || state.getBlock() instanceof TallGrassBlock
            || state.getBlock() instanceof VegetationBlock && state.canBeReplaced();
    }

    private static PlacementStep resolveNextPlacement(Minecraft client, PlacementSession session) {
        BlockPos nextTargetPos = session.supportPos.relative(session.backward);
        BlockState nextTargetState = client.level.getBlockState(nextTargetPos);
        if (!isTargetPlaceableOrClearable(nextTargetState)) {
            return null;
        }

        BlockPos clickSupportPos = resolveClickSupportPos(client, session, nextTargetPos);
        Direction clickSide = clickSupportPos.equals(session.supportPos) ? session.backward : Direction.UP;
        return new PlacementStep(
            nextTargetPos,
            clickSupportPos,
            clickSide,
            isWhitelistedPathObstacle(nextTargetState)
        );
    }

    private static boolean isTargetPlaceableOrClearable(BlockState state) {
        return state.canBeReplaced() || isWhitelistedPathObstacle(state);
    }

    private static BlockPos resolveClickSupportPos(Minecraft client, PlacementSession session, BlockPos targetPos) {
        if (session.bridgeItem.getBlock() instanceof SlabBlock && canUseAsPlacementBase(client, targetPos.below())) {
            return targetPos.below();
        }

        return session.supportPos;
    }

    private static PredictedPlacement resolveExpectedPlacement(
        Minecraft client,
        LocalPlayer player,
        ItemStack stack,
        BlockPos expectedTargetPos,
        BlockPos clickSupportPos,
        Direction clickSide,
        BlockItem bridgeItem
    ) {
        Vec3 hitPos = createPlacementHitPos(clickSupportPos, clickSide, bridgeItem);
        BlockHitResult hitResult = new BlockHitResult(hitPos, clickSide, clickSupportPos, false);
        BlockPlaceContext initialContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hitResult);
        BlockPlaceContext placementContext = bridgeItem.updatePlacementContext(initialContext);
        if (placementContext == null || !placementContext.canPlace()) {
            return null;
        }

        BlockPos placementPos = placementContext.getClickedPos();
        if (!placementPos.equals(expectedTargetPos)) {
            return null;
        }

        BlockState placementState = bridgeItem.getBlock().getStateForPlacement(placementContext);
        if (placementState == null || !placementState.canSurvive(client.level, placementPos)) {
            return null;
        }

        if (!client.level.isUnobstructed(placementState, placementPos, CollisionContext.placementContext(player))) {
            return null;
        }

        return new PredictedPlacement(placementPos, placementState);
    }

    private static PredictedPlacement resolveExpectedBuildUpPlacement(
        Minecraft client,
        LocalPlayer player,
        ItemStack stack,
        BlockPos expectedTargetPos,
        BlockPos clickSupportPos,
        BlockItem bridgeItem
    ) {
        Vec3 hitPos = createPlacementHitPos(clickSupportPos, Direction.UP, bridgeItem);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, clickSupportPos, false);
        BlockPlaceContext initialContext = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hitResult);
        BlockPlaceContext placementContext = bridgeItem.updatePlacementContext(initialContext);
        if (placementContext == null || !placementContext.canPlace()) {
            return null;
        }

        BlockPos placementPos = placementContext.getClickedPos();
        if (!placementPos.equals(expectedTargetPos)) {
            return null;
        }

        BlockState placementState = bridgeItem.getBlock().getStateForPlacement(placementContext);
        if (placementState == null || !placementState.canSurvive(client.level, placementPos)) {
            return null;
        }

        return new PredictedPlacement(placementPos, placementState);
    }

    private static void tickBuildUp(Minecraft client) {
        BuildUpSession session = activeBuildUpSession;
        LocalPlayer player = client.player;

        if (session == null) {
            return;
        }

        if (player == null || client.level == null || client.gameMode == null) {
            activeBuildUpSession = null;
            restoreBuildUpKeys(client);
            return;
        }

        setBuildUpMovement(client);

        if (player.getInventory().getSelectedSlot() != session.slotIndex) {
            cancelBuildUp(client, player, session);
            return;
        }

        ItemStack stack = player.getInventory().getItem(session.slotIndex);
        if (stack.isEmpty() && session.untilInventoryExhausted && refillSelectedSlotFromInventory(client, player, session.slotIndex, session.bridgeTemplate)) {
            stack = player.getInventory().getItem(session.slotIndex);
        }

        if (!stack.isEmpty() && !ItemStack.isSameItemSameComponents(stack, session.bridgeTemplate)) {
            cancelBuildUp(client, player, session);
            return;
        }

        if (stack.isEmpty()) {
            session.emptyTicks++;
        } else {
            session.emptyTicks = 0;
        }

        if (!session.untilInventoryExhausted && session.placed >= session.maxPlacements) {
            finishBuildUp(client, player, session.placed);
            return;
        }

        if (!player.isCreative() && stack.isEmpty() && session.emptyTicks >= EMPTY_SYNC_GRACE_TICKS) {
            finishBuildUp(client, player, session.placed);
            return;
        }

        if (session.cooldownTicks > 0) {
            session.cooldownTicks--;
            return;
        }

        BlockState supportState = client.level.getBlockState(session.supportPos);
        if (supportState.canBeReplaced()) {
            stallBuildUp(client, player, session);
            return;
        }

        BlockPos targetPos = session.supportPos.above();
        BlockState targetState = client.level.getBlockState(targetPos);
        if (session.clearingObstacle) {
            if (!isWhitelistedPathObstacle(targetState)) {
                session.clearingObstacle = false;
                session.attemptsOnCurrentTarget = 0;
                session.confirmTicksOnCurrentTarget = 0;
            }
        } else if (!targetState.canBeReplaced()) {
            session.confirmTicksOnCurrentTarget++;

            if (session.confirmTicksOnCurrentTarget < BUILD_UP_CONFIRM_TICKS) {
                return;
            }

            if (!isValidBuildUpState(client, session, targetPos, targetState)) {
                stallBuildUp(client, player, session);
                return;
            }

            session.supportPos = targetPos;
            session.attemptsOnCurrentTarget = 0;
            session.confirmTicksOnCurrentTarget = 0;
            session.cooldownTicks = BUILD_UP_PLACE_INTERVAL_TICKS;
            session.placed++;
            return;
        } else {
            session.confirmTicksOnCurrentTarget = 0;
        }

        if (session.attemptsOnCurrentTarget >= BUILD_UP_MAX_ATTEMPTS_PER_BLOCK) {
            stallBuildUp(client, player, session);
            return;
        }

        if (session.clearingObstacle) {
            if (!player.isWithinBlockInteractionRange(targetPos, 0.0D)) {
                return;
            }

            if (!clearWhitelistedObstacle(client, player, targetPos)) {
                session.attemptsOnCurrentTarget++;
                session.cooldownTicks = PLACE_INTERVAL_TICKS;
                return;
            }

            session.attemptsOnCurrentTarget++;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            return;
        }

        if (isWhitelistedPathObstacle(targetState)) {
            session.clearingObstacle = true;
            return;
        }

        if (!targetState.canBeReplaced()) {
            stallBuildUp(client, player, session);
            return;
        }

        if (!player.isWithinBlockInteractionRange(session.supportPos, 0.0D)
            || !player.isWithinBlockInteractionRange(targetPos, 0.0D)) {
            return;
        }

        PredictedPlacement expectedPlacement = resolveExpectedBuildUpPlacement(
            client,
            player,
            stack,
            targetPos,
            session.supportPos,
            session.bridgeItem
        );
        if (expectedPlacement == null || !isValidBuildUpState(client, session, expectedPlacement.targetPos(), expectedPlacement.state())) {
            return;
        }

        if (!isBuildUpPlacementWindow(client, player, targetPos, expectedPlacement.state())) {
            return;
        }

        if (!placeAgainst(client, player, session.supportPos, Direction.UP, session.bridgeItem)) {
            session.attemptsOnCurrentTarget++;
            session.cooldownTicks = BUILD_UP_PLACE_INTERVAL_TICKS;
            return;
        }

        session.attemptsOnCurrentTarget++;
        session.cooldownTicks = BUILD_UP_PLACE_INTERVAL_TICKS;
    }

    private static boolean isValidBuildUpState(Minecraft client, BuildUpSession session, BlockPos targetPos, BlockState state) {
        return targetPos.equals(session.supportPos.above()) && state.is(session.bridgeItem.getBlock());
    }

    private static boolean isBuildUpPlacementWindow(Minecraft client, LocalPlayer player, BlockPos targetPos, BlockState placementState) {
        VoxelShape shape = placementState.getCollisionShape(client.level, targetPos);
        double targetTopY = shape.isEmpty()
            ? targetPos.getY() + 1.0D
            : targetPos.getY() + shape.max(Direction.Axis.Y);
        return player.getY() >= targetTopY - BUILD_UP_PLACE_HEIGHT_EPSILON;
    }

    private static Vec3 createPlacementHitPos(BlockPos supportPos, Direction side, BlockItem bridgeItem) {
        if (side != Direction.UP && bridgeItem.getBlock() instanceof SlabBlock) {
            return new Vec3(
                supportPos.getX() + 0.5D,
                supportPos.getY() + 0.25D,
                supportPos.getZ() + 0.5D
            ).add(side.getUnitVec3().scale(0.5D));
        }

        return Vec3.atCenterOf(supportPos)
            .add(side.getUnitVec3().scale(0.5D));
    }

    private static boolean canUseAsPlacementBase(Minecraft client, BlockPos basePos) {
        BlockState baseState = client.level.getBlockState(basePos);
        return !baseState.canBeReplaced() && baseState.isFaceSturdy(client.level, basePos, Direction.UP);
    }

    private static boolean isValidPathState(Minecraft client, PlacementSession session, BlockPos targetPos, BlockState state) {
        return state.is(session.bridgeItem.getBlock())
            && isSameSurfaceY(getSupportSurfaceY(client, targetPos, state), session.pathSurfaceY);
    }

    private static double getSupportSurfaceY(Minecraft client, BlockPos pos) {
        return getSupportSurfaceY(client, pos, client.level.getBlockState(pos));
    }

    private static double getSupportSurfaceY(Minecraft client, BlockPos pos, BlockState state) {
        return pos.getY() + state.getCollisionShape(client.level, pos).max(Direction.Axis.Y);
    }

    private static boolean isSameSurfaceY(double first, double second) {
        return Math.abs(first - second) < 1.0E-4D;
    }

    private static BlockPos findStartSupportPos(LocalPlayer player) {
        Vec3 playerPos = player.position();
        return BlockPos.containing(playerPos.x, playerPos.y - 0.2D, playerPos.z);
    }

    private static boolean refillSelectedSlotFromInventory(Minecraft client, LocalPlayer player, int slotIndex, ItemStack bridgeTemplate) {
        int refillSlot = findBestRefillSlot(player, slotIndex, bridgeTemplate);
        if (refillSlot < 0) {
            return false;
        }

        client.gameMode.handleContainerInput(
            player.inventoryMenu.containerId,
            toInventoryMenuSlot(refillSlot),
            slotIndex,
            ContainerInput.SWAP,
            player
        );
        ItemStack refilledStack = player.getInventory().getItem(slotIndex);
        return !refilledStack.isEmpty() && ItemStack.isSameItemSameComponents(refilledStack, bridgeTemplate);
    }

    private static int findBestRefillSlot(LocalPlayer player, int selectedSlot, ItemStack bridgeTemplate) {
        int bestSlot = -1;
        int bestCount = 0;

        for (int slotIndex = 0; slotIndex < player.getInventory().getNonEquipmentItems().size(); slotIndex++) {
            if (slotIndex == selectedSlot) {
                continue;
            }

            ItemStack candidate = player.getInventory().getItem(slotIndex);
            if (candidate.isEmpty() || !ItemStack.isSameItemSameComponents(candidate, bridgeTemplate)) {
                continue;
            }

            if (candidate.getCount() > bestCount) {
                bestSlot = slotIndex;
                bestCount = candidate.getCount();
            }
        }

        return bestSlot;
    }

    private static int toInventoryMenuSlot(int inventorySlot) {
        if (inventorySlot < Inventory.getSelectionSize()) {
            return InventoryMenu.USE_ROW_SLOT_START + inventorySlot;
        }

        return inventorySlot;
    }

    private static void tryPlaceTorch(Minecraft client, LocalPlayer player, BlockPos supportPos) {
        ItemStack offhandStack = player.getOffhandItem();
        if (!(offhandStack.getItem() instanceof BlockItem blockItem)) {
            return;
        }

        if (!(blockItem.getBlock() instanceof BaseTorchBlock)) {
            return;
        }

        BlockPos torchPos = supportPos.above();
        if (!client.level.getBlockState(torchPos).canBeReplaced()) {
            return;
        }

        if (client.level.getBrightness(LightLayer.BLOCK, torchPos) > 1) {
            return;
        }

        if (!player.isWithinBlockInteractionRange(torchPos, 0.0D)) {
            return;
        }

        if (!blockItem.getBlock().defaultBlockState().canSurvive(client.level, torchPos)) {
            return;
        }

        Vec3 hitPos = Vec3.atCenterOf(supportPos)
            .add(Direction.UP.getUnitVec3().scale(0.5D));
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, supportPos, false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.OFF_HAND, hitResult);

        if (result.consumesAction()) {
            player.swing(InteractionHand.OFF_HAND);
        }
    }

    private static void finishSession(Minecraft client, LocalPlayer player, int placed) {
        activeSession = null;
        restoreMovementKeys(client);

        if (placed > 0) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.placed", placed));
        } else {
            player.sendOverlayMessage(Component.translatable("message.backbridge.nothing_placed"));
        }
    }

    private static void finishBuildUp(Minecraft client, LocalPlayer player, int placed) {
        activeBuildUpSession = null;
        restoreBuildUpKeys(client);

        if (placed > 0) {
            player.sendOverlayMessage(Component.translatable("message.backbridge.build_up_placed", placed));
        } else {
            player.sendOverlayMessage(Component.translatable("message.backbridge.nothing_placed"));
        }
    }

    private static void cancelSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        restoreMovementKeys(client);
        player.sendOverlayMessage(Component.translatable("message.backbridge.cancelled", session.placed));
    }

    private static void cancelBuildUp(Minecraft client, LocalPlayer player, BuildUpSession session) {
        activeBuildUpSession = null;
        restoreBuildUpKeys(client);
        player.sendOverlayMessage(Component.translatable("message.backbridge.build_up_cancelled", session.placed));
    }

    private static void stallSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        restoreMovementKeys(client);
        player.sendOverlayMessage(Component.translatable("message.backbridge.stalled", session.placed));
    }

    private static void stallBuildUp(Minecraft client, LocalPlayer player, BuildUpSession session) {
        activeBuildUpSession = null;
        restoreBuildUpKeys(client);
        player.sendOverlayMessage(Component.translatable("message.backbridge.build_up_stalled", session.placed));
    }

    private static void toggleInventoryExhaustionMode(Minecraft client) {
        inventoryExhaustionModeEnabled = !inventoryExhaustionModeEnabled;

        if (client.player != null) {
            client.player.sendOverlayMessage(
                Component.translatable(
                    inventoryExhaustionModeEnabled
                        ? "message.backbridge.inventory_mode_on"
                        : "message.backbridge.inventory_mode_off"
                )
            );
        }
    }

    private static void setSessionMovement(Minecraft client) {
        forceKeyState(client.options.keyUp, false);
        forceKeyState(client.options.keyDown, true);
        forceKeyState(client.options.keyShift, true);
    }

    private static void setBuildUpMovement(Minecraft client) {
        forceKeyState(client.options.keyJump, true);
    }

    private static void restoreMovementKeys(Minecraft client) {
        restorePhysicalKeyState(client, client.options.keyUp);
        restorePhysicalKeyState(client, client.options.keyDown);
        restorePhysicalKeyState(client, client.options.keyShift);
    }

    private static void restoreBuildUpKeys(Minecraft client) {
        restorePhysicalKeyState(client, client.options.keyJump);
    }

    private static void forceKeyState(KeyMapping keyMapping, boolean down) {
        keyMapping.setDown(down);
    }

    private static void restorePhysicalKeyState(Minecraft client, KeyMapping keyMapping) {
        InputConstants.Key boundKey = KeyMappingHelper.getBoundKeyOf(keyMapping);
        boolean physicalDown = boundKey.getType() == InputConstants.Type.MOUSE
            ? false
            : InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
        keyMapping.setDown(physicalDown);
    }

    private static final class PlacementSession {
        private final int slotIndex;
        private final ItemStack bridgeTemplate;
        private final BlockItem bridgeItem;
        private final Direction backward;
        private final int maxPlacements;
        private final boolean untilInventoryExhausted;
        private final double pathSurfaceY;
        private BlockPos supportPos;
        private BlockPos currentTarget;
        private BlockPos clickSupportPos;
        private int placed;
        private int cooldownTicks;
        private int attemptsOnCurrentTarget;
        private int confirmTicksOnCurrentTarget;
        private int emptyTicks;
        private boolean clearingObstacle;
        private Direction clickSide;

        private PlacementSession(
            int slotIndex,
            ItemStack bridgeTemplate,
            BlockItem bridgeItem,
            BlockPos supportPos,
            Direction backward,
            int maxPlacements,
            boolean untilInventoryExhausted,
            double pathSurfaceY
        ) {
            this.slotIndex = slotIndex;
            this.bridgeTemplate = bridgeTemplate;
            this.bridgeItem = bridgeItem;
            this.supportPos = supportPos;
            this.backward = backward;
            this.maxPlacements = maxPlacements;
            this.untilInventoryExhausted = untilInventoryExhausted;
            this.pathSurfaceY = pathSurfaceY;
            this.clickSide = backward;
        }
    }

    private static final class BuildUpSession {
        private final int slotIndex;
        private final ItemStack bridgeTemplate;
        private final BlockItem bridgeItem;
        private final int maxPlacements;
        private final boolean untilInventoryExhausted;
        private BlockPos supportPos;
        private int placed;
        private int cooldownTicks;
        private int attemptsOnCurrentTarget;
        private int confirmTicksOnCurrentTarget;
        private int emptyTicks;
        private boolean clearingObstacle;

        private BuildUpSession(
            int slotIndex,
            ItemStack bridgeTemplate,
            BlockItem bridgeItem,
            BlockPos supportPos,
            int maxPlacements,
            boolean untilInventoryExhausted
        ) {
            this.slotIndex = slotIndex;
            this.bridgeTemplate = bridgeTemplate;
            this.bridgeItem = bridgeItem;
            this.supportPos = supportPos;
            this.maxPlacements = maxPlacements;
            this.untilInventoryExhausted = untilInventoryExhausted;
        }
    }

    private record PlacementStep(BlockPos targetPos, BlockPos clickSupportPos, Direction clickSide, boolean clearObstacle) {
    }

    private record PredictedPlacement(BlockPos targetPos, BlockState state) {
    }
}
