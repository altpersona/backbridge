package dev.micro.backbridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class BackBridgeClient implements ClientModInitializer {
    private static final int MAX_PLACEMENTS = 64;
    private static final int PLACE_INTERVAL_TICKS = 4;
    private static final int MAX_ATTEMPTS_PER_BLOCK = 8;
    private static final int PLACEMENT_CONFIRM_TICKS = 2;
    private static final int EMPTY_SYNC_GRACE_TICKS = 10;
    private static final KeyMapping PLACE_LINE_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping(
            "key.backbridge.place_line",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.MISC
        )
    );
    private static PlacementSession activeSession;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (PLACE_LINE_KEY.consumeClick()) {
                startPlacement(client);
            }

            tickPlacement(client);
        });
    }

    private static void startPlacement(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            player.displayClientMessage(Component.translatable("message.backbridge.invalid_block"), true);
            return;
        }

        int maxPlacements = player.isCreative() ? MAX_PLACEMENTS : Math.min(MAX_PLACEMENTS, stack.getCount());
        if (maxPlacements <= 0) {
            player.displayClientMessage(Component.translatable("message.backbridge.nothing_placed"), true);
            return;
        }

        activeSession = new PlacementSession(
            player.getInventory().getSelectedSlot(),
            blockItem,
            player.blockPosition().below(),
            player.getDirection().getOpposite(),
            maxPlacements
        );
        setSessionMovement(client);
        player.displayClientMessage(Component.translatable("message.backbridge.started", maxPlacements), true);
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
        if (!stack.isEmpty() && stack.getItem() != session.bridgeItem) {
            cancelSession(client, player, session);
            return;
        }

        if (stack.isEmpty()) {
            session.emptyTicks++;
        } else {
            session.emptyTicks = 0;
        }

        if (session.currentTarget != null && !client.level.getBlockState(session.currentTarget).canBeReplaced()) {
            session.confirmTicksOnCurrentTarget++;

            if (session.confirmTicksOnCurrentTarget < PLACEMENT_CONFIRM_TICKS) {
                return;
            }

            session.supportPos = session.currentTarget;
            session.currentTarget = null;
            session.attemptsOnCurrentTarget = 0;
            session.confirmTicksOnCurrentTarget = 0;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            session.placed++;

            tryPlaceTorch(client, player, session.supportPos);
        } else {
            session.confirmTicksOnCurrentTarget = 0;
        }

        if (session.placed >= session.maxPlacements) {
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

            BlockPos nextTarget = session.supportPos.relative(session.backward);
            if (!client.level.getBlockState(nextTarget).canBeReplaced()) {
                finishSession(client, player, session.placed);
                return;
            }

            session.currentTarget = nextTarget;
            session.attemptsOnCurrentTarget = 0;
            session.confirmTicksOnCurrentTarget = 0;
        }

        if (session.attemptsOnCurrentTarget >= MAX_ATTEMPTS_PER_BLOCK) {
            stallSession(client, player, session);
            return;
        }

        if (!player.isWithinBlockInteractionRange(session.supportPos, 0.0D)
            || !player.isWithinBlockInteractionRange(session.currentTarget, 0.0D)) {
            return;
        }

        if (!placeAgainst(client, player, session.supportPos, session.backward)) {
            session.attemptsOnCurrentTarget++;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            return;
        }

        session.attemptsOnCurrentTarget++;
        session.cooldownTicks = PLACE_INTERVAL_TICKS;
    }

    private static boolean placeAgainst(Minecraft client, LocalPlayer player, BlockPos supportPos, Direction side) {
        Vec3 hitPos = Vec3.atCenterOf(supportPos)
            .add(side.getUnitVec3().scale(0.5D));
        BlockHitResult hitResult = new BlockHitResult(hitPos, side, supportPos, false);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

        if (!result.consumesAction()) {
            return false;
        }

        player.swing(InteractionHand.MAIN_HAND);
        return true;
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
            player.displayClientMessage(Component.translatable("message.backbridge.placed", placed), true);
        } else {
            player.displayClientMessage(Component.translatable("message.backbridge.nothing_placed"), true);
        }
    }

    private static void cancelSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        restoreMovementKeys(client);
        player.displayClientMessage(Component.translatable("message.backbridge.cancelled", session.placed), true);
    }

    private static void stallSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        restoreMovementKeys(client);
        player.displayClientMessage(Component.translatable("message.backbridge.stalled", session.placed), true);
    }

    private static void setSessionMovement(Minecraft client) {
        forceKeyState(client.options.keyUp, false);
        forceKeyState(client.options.keyDown, true);
        forceKeyState(client.options.keyShift, true);
    }

    private static void restoreMovementKeys(Minecraft client) {
        restorePhysicalKeyState(client, client.options.keyUp);
        restorePhysicalKeyState(client, client.options.keyDown);
        restorePhysicalKeyState(client, client.options.keyShift);
    }

    private static void forceKeyState(KeyMapping keyMapping, boolean down) {
        keyMapping.setDown(down);
    }

    private static void restorePhysicalKeyState(Minecraft client, KeyMapping keyMapping) {
        InputConstants.Key boundKey = KeyBindingHelper.getBoundKeyOf(keyMapping);
        boolean physicalDown = boundKey.getType() == InputConstants.Type.MOUSE
            ? false
            : InputConstants.isKeyDown(client.getWindow(), boundKey.getValue());
        keyMapping.setDown(physicalDown);
    }

    private static final class PlacementSession {
        private final int slotIndex;
        private final BlockItem bridgeItem;
        private final Direction backward;
        private final int maxPlacements;
        private BlockPos supportPos;
        private BlockPos currentTarget;
        private int placed;
        private int cooldownTicks;
        private int attemptsOnCurrentTarget;
        private int confirmTicksOnCurrentTarget;
        private int emptyTicks;

        private PlacementSession(int slotIndex, BlockItem bridgeItem, BlockPos supportPos, Direction backward, int maxPlacements) {
            this.slotIndex = slotIndex;
            this.bridgeItem = bridgeItem;
            this.supportPos = supportPos;
            this.backward = backward;
            this.maxPlacements = maxPlacements;
        }
    }
}
