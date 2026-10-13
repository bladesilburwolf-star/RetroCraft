package com.unrealcraft.client.retro;

import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.sm64.Sm64Enemies;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.List;

/**
 * Phase-2 placeholder meshes for SM64 enemies. No AI yet — static markers at spawn points
 * so level population is visible. Colours differ by family.
 */
public final class Sm64EnemyRenderer {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    private Sm64EnemyRenderer() {}

    public static void draw(
            List<Sm64Enemies.Spawn> enemies,
            Quake3Bsp.Placement pl,
            double ax, double ay, double az,
            MatrixStack ms, VertexConsumerProvider vcp,
            net.minecraft.util.math.Vec3d cam, double renderRadius
    ) {
        if (enemies == null || enemies.isEmpty()) return;
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        double r2 = renderRadius * renderRadius;

        for (Sm64Enemies.Spawn e : enemies) {
            float lx = pl.lx(e.x()), ly = pl.ly(e.y()), lz = pl.lz(e.z());
            double wx = ax + lx, wy = ay + ly, wz = az + lz;
            double dx = wx - cam.x, dy = wy - cam.y, dz = wz - cam.z;
            if (dx * dx + dy * dy + dz * dz > r2) continue;

            ms.push();
            ms.translate(wx - cam.x, wy - cam.y, wz - cam.z);
            float k = 100f / Math.max(pl.scale(), 1f);
            ms.scale(k, k, k);
            float yawRad = Math.abs(e.yaw()) > 360
                    ? e.yaw() * (float) (Math.PI / 32768.0)
                    : e.yaw() * (float) (Math.PI / 180.0);
            ms.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));
            drawKind(ms.peek(), vc, e.kind());
            ms.pop();
        }
    }

    private static void drawKind(MatrixStack.Entry en, VertexConsumer vc, String kind) {
        switch (kind) {
            case "goomba" -> body(en, vc, 0.4f, 0.35f, 140, 90, 40);      // brown
            case "koopa" -> body(en, vc, 0.45f, 0.7f, 50, 160, 50);         // green
            case "bobomb" -> body(en, vc, 0.35f, 0.4f, 30, 30, 30);         // black
            case "king_bobomb" -> body(en, vc, 0.9f, 1.2f, 20, 20, 20);
            case "bully", "big_bully" -> body(en, vc, kind.startsWith("big") ? 0.8f : 0.45f, 0.5f, 180, 40, 40);
            case "boo", "big_boo" -> body(en, vc, kind.startsWith("big") ? 0.9f : 0.5f, 0.6f, 230, 230, 255);
            case "whomp" -> body(en, vc, 0.9f, 1.4f, 160, 150, 140);
            case "thwomp" -> body(en, vc, 0.8f, 1.0f, 100, 100, 180);
            case "piranha" -> body(en, vc, 0.35f, 0.9f, 40, 160, 40);
            case "chain_chomp" -> body(en, vc, 0.7f, 0.7f, 40, 40, 40);
            case "bowser" -> body(en, vc, 1.2f, 1.5f, 200, 120, 30);
            case "fly_guy" -> body(en, vc, 0.35f, 0.5f, 200, 40, 40);
            case "lakitu" -> body(en, vc, 0.4f, 0.55f, 220, 220, 80);
            case "chuckya" -> body(en, vc, 0.55f, 0.8f, 200, 80, 160);
            case "amp" -> body(en, vc, 0.3f, 0.3f, 255, 255, 80);
            case "mr_i" -> body(en, vc, 0.5f, 0.5f, 240, 240, 240);
            default -> body(en, vc, 0.4f, 0.5f, 200, 100, 200); // magenta = unclassified enemy
        }
    }

    private static void body(MatrixStack.Entry en, VertexConsumer vc, float w, float h, int r, int g, int b) {
        box(en, vc, 0, h * 0.5f, 0, w * 0.5f, h, w * 0.5f, r, g, b);
        // simple "eye" marker
        box(en, vc, 0, h * 0.7f, w * 0.35f, 0.08f, 0.1f, 0.05f, 255, 255, 255);
    }

    private static void box(MatrixStack.Entry en, VertexConsumer vc,
                            float cx, float cy, float cz, float hx, float hy, float hz,
                            int r, int g, int b) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy * 0.5f, y1 = cy + hy * 0.5f;
        float z0 = cz - hz, z1 = cz + hz;
        face(en, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b);
        face(en, vc, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b);
        face(en, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b);
        face(en, vc, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b);
        face(en, vc, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b);
        face(en, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b);
    }

    private static void face(MatrixStack.Entry en, VertexConsumer vc,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             int r, int g, int b) {
        v(en, vc, x0, y0, z0, r, g, b); v(en, vc, x1, y1, z1, r, g, b);
        v(en, vc, x2, y2, z2, r, g, b); v(en, vc, x2, y2, z2, r, g, b);
        v(en, vc, x0, y0, z0, r, g, b); v(en, vc, x2, y2, z2, r, g, b);
        v(en, vc, x3, y3, z3, r, g, b); v(en, vc, x3, y3, z3, r, g, b);
    }

    private static void v(MatrixStack.Entry en, VertexConsumer vc, float x, float y, float z, int r, int g, int b) {
        vc.vertex(en, x, y, z).color(r, g, b, 255).texture(0f, 0f)
                .overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(en, 0, 1, 0);
    }
}
