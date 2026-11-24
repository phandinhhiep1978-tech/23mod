package dev.containerscan;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Press the key: opens every hopper within normal reach, one after another
 * (open -> wait for contents to sync -> close -> next). The InventoryCache records
 * each one as it opens. Uses ordinary right-click packets at ordinary reach.
 */
public final class HopperSweep {
    private enum Phase { IDLE, WAIT_OPEN, WAIT_SYNC, COOLDOWN }

    private static KeyBinding key;
    private static Phase phase = Phase.IDLE;
    private static final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private static int timer;
    private static int total;

    private HopperSweep() {}

    public static void init() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.containerscan.sweep", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET,
                KeybindHandler.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(HopperSweep::tick);
    }

    private static void tick(MinecraftClient mc) {
        while (key.wasPressed()) {
            if (phase != Phase.IDLE) stop(mc, "Hopper sweep cancelled");
            else start(mc);
        }
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.world == null) { queue.clear(); phase = Phase.IDLE; return; }

        switch (phase) {
            case COOLDOWN -> { if (--timer <= 0) next(mc); }
            case WAIT_OPEN -> {
                if (mc.currentScreen instanceof HandledScreen<?>) { phase = Phase.WAIT_SYNC; timer = 4; }
                else if (--timer <= 0) next(mc); // didn't open (out of range / protected) -> skip
            }
            case WAIT_SYNC -> {
                if (--timer <= 0) {
                    mc.player.closeHandledScreen();
                    phase = Phase.COOLDOWN;
                    timer = 3;
                }
            }
            default -> {}
        }
    }

    private static void start(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (mc.player.isSneaking()) { msg(mc, "Stop sneaking first"); return; }

        double reach = mc.player.getBlockInteractionRange() - 0.3;
        Vec3d eye = mc.player.getEyePos();
        int r = (int) Math.ceil(reach) + 1;
        BlockPos c = mc.player.getBlockPos();

        List<BlockPos> list = new ArrayList<>();
        for (BlockPos p : BlockPos.iterate(c.add(-r, -r, -r), c.add(r, r, r))) {
            BlockEntity be = mc.world.getBlockEntity(p);
            if (be != null && be.getType() == BlockEntityType.HOPPER
                    && eye.distanceTo(Vec3d.ofCenter(p)) <= reach) {
                list.add(p.toImmutable());
            }
        }
        list.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(Vec3d.ofCenter(p))));

        if (list.isEmpty()) { msg(mc, "No hoppers within reach"); return; }
        queue.clear();
        queue.addAll(list);
        total = list.size();
        next(mc);
    }

    private static void next(MinecraftClient mc) {
        BlockPos pos;
        while ((pos = queue.poll()) != null) {
            if (mc.player.isSneaking()) { stop(mc, "Hopper sweep stopped (sneaking)"); return; }
            BlockEntity be = mc.world.getBlockEntity(pos);
            double reach = mc.player.getBlockInteractionRange() - 0.3;
            if (be == null || be.getType() != BlockEntityType.HOPPER
                    || mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > reach) continue;

            msg(mc, "Opening hopper " + (total - queue.size()) + "/" + total);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
            phase = Phase.WAIT_OPEN;
            timer = 20;
            return;
        }
        stop(mc, "Hopper sweep done (" + total + " hoppers)");
    }

    private static void stop(MinecraftClient mc, String message) {
        queue.clear();
        phase = Phase.IDLE;
        if (mc.currentScreen instanceof HandledScreen<?> && mc.player != null) mc.player.closeHandledScreen();
        msg(mc, message);
    }

    private static void msg(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(text), true);
    }
}
