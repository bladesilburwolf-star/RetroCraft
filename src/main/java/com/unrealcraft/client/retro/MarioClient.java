package com.unrealcraft.client.retro;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.sm64.Sm64Collision;
import com.unrealcraft.sm64.Sm64PropCollision;
import com.unrealcraft.sm64.Sm64Mario;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Mario mode: the client player is driven by the ported SM64 physics (30 FPS, stepped 3 frames per 2 client ticks)
 * on the loaded SM64 level. Toggle with the M key (asks the server, which removes the barrier blocks that would
 * otherwise fight the movement). Toggle from the pause menu or with /sm64 mario. Keys: WASD = stick, Space = A (jump), Shift = Z (crouch / long jump / ground pound), Ctrl/Sprint = B (dive). Mouse turns the camera.
 */
public final class MarioClient {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    private static boolean enabled;
    private static Sm64Mario mario;
    private static RetroMapClient.Active level;
    private static float frameAcc;

    private MarioClient() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (enabled) tick(client);
        });
    }

    public static boolean isEnabled() { return enabled; }
    public static Sm64Mario mario() { return mario; }

    /** Called when the server confirms Mario mode on/off, or when the map goes away. */
    public static void setEnabled(boolean on) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!on) {
            enabled = false;
            mario = null;
            if (mc.player != null) mc.player.setInvisible(false);
            return;
        }
        RetroMapClient.Active a = RetroMapClient.sm64();
        if (a == null || mc.player == null) return;
        RetroMapClient.prepare(a);
        if (a.mesh == null || a.sm64 == null) return;
        var pl = a.mesh.placement;
        var p = mc.player.getPos();
        float rx = pl.refX() + (float) ((p.x - a.x) * pl.scale());
        float ry = pl.refY() + (float) ((p.y - a.y) * pl.scale());
        float rz = pl.refZ() + (float) ((p.z - a.z) * pl.scale());
        var col = new Sm64Collision(Sm64PropCollision.merge(a.sm64.tris(), a.props));
        var f = col.findFloor(rx, ry, rz);
        if (f.surface == null) {
            mc.player.sendMessage(Text.literal("Mario mode: stand over the loaded level first."), false);
            return;
        }
        level = a;
        mario = new Sm64Mario(col, rx, f.height, rz, 0);
        frameAcc = 0f;
        enabled = true;
        mc.player.setInvisible(true);
        mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        Unrealcraft.LOGGER.info("Mario mode on at raw ({}, {}, {}), {} surfaces", rx, f.height, rz, col.surfaceCount);
    }

    private static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mario == null || RetroMapClient.sm64() != level) {
            setEnabled(false);
            return;
        }
        var o = mc.options;
        boolean input = mc.currentScreen == null;
        float sx = 0, sy = 0;
        if (input) {
            if (o.forwardKey.isPressed()) sy += 64;
            if (o.backKey.isPressed()) sy -= 64;
            if (o.rightKey.isPressed()) sx += 64;
            if (o.leftKey.isPressed()) sx -= 64;
        }
        mario.stickX = sx; mario.stickY = sy;
        mario.aDown = input && o.jumpKey.isPressed();
        mario.zDown = input && o.sneakKey.isPressed();
        mario.bDown = input && o.sprintKey.isPressed(); // B = dive
        // camera yaw = direction from Mario to the camera. MC yaw 0 = +Z and turns clockwise; SM64 yaw is the mirror.
        float mcYaw = mc.player.getYaw();
        mario.camYaw = (short) Math.round(-mcYaw / 360.0 * 65536.0 + 0x8000);

        frameAcc += 1.5f; // 30 SM64 frames per second over 20 client ticks per second
        int frames = (int) frameAcc;
        frameAcc -= frames;
        for (int i = 0; i < frames; i++) mario.update();

        var pl = level.mesh.placement;
        double wx = level.x + (mario.pos[0] - pl.refX()) / pl.scale();
        double wy = level.y + (mario.pos[1] - pl.refY()) / pl.scale();
        double wz = level.z + (mario.pos[2] - pl.refZ()) / pl.scale();
        var pe = mc.player;
        pe.setVelocity(Vec3d.ZERO);
        pe.setPosition(wx, wy, wz); // prev position was reset at the start of this tick, so vanilla interpolates smoothly
        pe.fallDistance = 0;
        pe.setInvisible(true);
    }

    /** Draw the placeholder Mario at the player's interpolated position. */
    public static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!enabled || mario == null || mc.player == null) return;
        float delta = mc.getRenderTickCounter().getTickDelta(false);
        Vec3d p = mc.player.getLerpedPos(delta);
        float scale = level.mesh.placement.scale();
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        ms.push();
        ms.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotation((short) mario.faceYaw * (float) Math.PI / 32768f));
        MarioModel.draw(ms.peek(), vc, 1f / scale);
        ms.pop();
    }
}
