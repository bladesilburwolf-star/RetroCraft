package com.unrealcraft.client.retro;

import com.unrealcraft.Unrealcraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Mario mode: the client player is driven by the ported SM64 physics (30 FPS, stepped 3 frames per 2 client ticks)
 * on the loaded SM64 level. Toggle with the M key (asks the server, which removes the barrier blocks that would
 * otherwise fight the movement). Keys: WASD = stick, Space = A (jump), Shift = Z (crouch / long jump / ground pound), Ctrl/Sprint = B (dive). Mouse turns the camera.
 */
public final class MarioClient {
    private static KeyBinding toggleKey;
    private static boolean enabled;
    private static RetroMapClient.Active level;
    private static float frameAcc;

    private MarioClient() {}

    public static void init() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.unrealcraft.mario",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.misc"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (client.player != null) client.player.networkHandler.sendChatCommand("sm64 mario");
            }
            if (enabled && client.currentScreen == null) tick(client);
        });
    }

    public static boolean isEnabled() { return enabled; }
    public static String actionName() { return LibSm64Runtime.actionName(); }

    /** Called when the server confirms Mario mode on/off, or when the map goes away. */
    public static void setEnabled(boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!on) {
            enabled = false;
            LibSm64Runtime.stop();
            if (mc.player != null) mc.player.setInvisible(false);
            return;
        }
        RetroMapClient.Active a = RetroMapClient.sm64();
        if (a == null || mc.player == null) return;
        RetroMapClient.prepare(a);
        if (a.mesh == null || a.sm64 == null) return;
        LibSm64Runtime.stop();
        if (!LibSm64Runtime.start(mc, a)) return;
        level = a;
        frameAcc = 0f;
        enabled = true;
        mc.player.setInvisible(true);
        mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        Unrealcraft.LOGGER.info("Native libsm64 Mario mode enabled for {}", a.path);
    }

    private static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || RetroMapClient.sm64() != level) {
            setEnabled(false);
            return;
        }
        frameAcc += 1.5f; // 30 SM64 frames per second over 20 client ticks per second
        int frames = (int) frameAcc;
        frameAcc -= frames;
        LibSm64Runtime.tick(mc, level, frames);
    }

    /** Draw libsm64's animated triangle mesh at the player position. */
    public static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mc.player == null) return;
        float delta = mc.getRenderTickCounter().getTickDelta(false);
        Vec3d p = mc.player.getLerpedPos(delta);
        LibSm64Runtime.render(ms, vcp, cam, p);
    }
}
