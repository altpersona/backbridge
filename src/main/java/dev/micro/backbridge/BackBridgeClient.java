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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public final class BackBridgeClient implements ClientModInitializer {
    private static final int MAX_PLACEMENTS = 64;
    private static final int PLACE_INTERVAL_TICKS = 4;
    private static final int MAX_ATTEMPTS_PER_BLOCK = 8;
    private static final int RECOVERY_FORWARD_TICKS = 4;
    private static final KeyMapping PLACE_LINE_KEY = KeyBindingHelper.registerKeyBinding(
        new KeyMapping(
            "key.backbridge.place_line",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.MISC
        )
    );
    private static PlacementSession activeSession;
    private static int recoveryTicks;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (PLACE_LINE_KEY.consumeClick()) {
                startPlacement(client);
            }

            tickPlacement(client);
            tickRecovery(client);
        });
    }

    private static void startPlacement(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem)) {
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
            player.blockPosition().below(),
            player.getDirection().getOpposite(),
            maxPlacements
        );
        recoveryTicks = 0;
        setSessionMovement(client);
        player.displayClientMessage(Component.translatable("message.backbridge.started", maxPlacements), true);
    }

    private static void tickPlacement(Minecraft client) {
        PlacementSession session = activeSession;
        LocalPlayer player = client.player;

        if (session == null) {
            return;
        }

        if (player == null || client.level == null || client.gameMode == null) {
            activeSession = null;
            recoveryTicks = 0;
            restoreMovementKeys(client);
            return;
        }

        setSessionMovement(client);

        if (player.getInventory().getSelectedSlot() != session.slotIndex) {
            cancelSession(client, player, session);
            return;
        }

        ItemStack stack = player.getInventory().getItem(session.slotIndex);
        if (!(stack.getItem() instanceof BlockItem)) {
            cancelSession(client, player, session);
            return;
        }

        if (session.currentTarget != null && !client.level.getBlockState(session.currentTarget).canBeReplaced()) {
            session.supportPos = session.currentTarget;
            session.currentTarget = null;
            session.attemptsOnCurrentTarget = 0;
            session.cooldownTicks = PLACE_INTERVAL_TICKS;
            session.placed++;
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
            if (!player.isCreative() && stack.isEmpty()) {
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
        }

        if (session.attemptsOnCurrentTarget >= MAX_ATTEMPTS_PER_BLOCK) {
            stallSession(client, player, session);
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

    private static void tickRecovery(Minecraft client) {
        if (activeSession != null) {
            return;
        }

        if (recoveryTicks > 0) {
            setRecoveryMovement(client);
            recoveryTicks--;
            return;
        }

        restoreMovementKeys(client);
    }

    private static void finishSession(Minecraft client, LocalPlayer player, int placed) {
        activeSession = null;
        recoveryTicks = RECOVERY_FORWARD_TICKS;

        if (placed > 0) {
            player.displayClientMessage(Component.translatable("message.backbridge.placed", placed), true);
        } else {
            player.displayClientMessage(Component.translatable("message.backbridge.nothing_placed"), true);
        }
    }

    private static void cancelSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        recoveryTicks = RECOVERY_FORWARD_TICKS;
        player.displayClientMessage(Component.translatable("message.backbridge.cancelled", session.placed), true);
    }

    private static void stallSession(Minecraft client, LocalPlayer player, PlacementSession session) {
        activeSession = null;
        recoveryTicks = RECOVERY_FORWARD_TICKS;
        player.displayClientMessage(Component.translatable("message.backbridge.stalled", session.placed), true);
    }

    private static void setSessionMovement(Minecraft client) {
        forceKeyState(client.options.keyUp, false);
        forceKeyState(client.options.keyDown, true);
        forceKeyState(client.options.keyShift, true);
    }

    private static void setRecoveryMovement(Minecraft client) {
        forceKeyState(client.options.keyDown, false);
        forceKeyState(client.options.keyShift, false);
        forceKeyState(client.options.keyUp, true);
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
        private final Direction backward;
        private final int maxPlacements;
        private BlockPos supportPos;
        private BlockPos currentTarget;
        private int placed;
        private int cooldownTicks;
        private int attemptsOnCurrentTarget;

        private PlacementSession(int slotIndex, BlockPos supportPos, Direction backward, int maxPlacements) {
            this.slotIndex = slotIndex;
            this.supportPos = supportPos;
            this.backward = backward;
            this.maxPlacements = maxPlacements;
        }
    }
}
