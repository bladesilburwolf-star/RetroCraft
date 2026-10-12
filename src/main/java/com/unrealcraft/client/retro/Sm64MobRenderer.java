package com.unrealcraft.client.retro;

import com.unrealcraft.entity.Sm64MobEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

/** Box-body renderer keyed by Sm64MobEntity.kind — matches Sm64EnemyRenderer colours. */
public class Sm64MobRenderer extends EntityRenderer<Sm64MobEntity> {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    public Sm64MobRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.4f;
    }

    @Override
    public Identifier getTexture(Sm64MobEntity entity) {
        return WHITE;
    }

    @Override
    public void render(Sm64MobEntity e, float yaw, float tickDelta, MatrixStack ms,
                       VertexConsumerProvider vcp, int light) {
        ms.push();
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        boolean moving = e.getVelocity().horizontalLengthSquared() > 0.0004;
        float gait = e.age + tickDelta;
        if (moving) {
            ms.translate(0, Math.abs((float)Math.sin(gait * 0.45f)) * 0.045f, 0);
            ms.multiply(RotationAxis.POSITIVE_Z.rotation((float)Math.sin(gait * 0.45f) * 0.04f));
        }
        var camera = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        if (Sm64PropRenderer.drawF3dActor(ms, vcp, e.getKind(), e.getKind(), camera, e.getX(), e.getZ())) {
            ms.pop();
            super.render(e, yaw, tickDelta, ms, vcp, light);
            return;
        }
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
        float[] c = colour(e.getKind());
        float[] s = size(e.getKind());
        box(ms.peek(), vc, 0, s[1] * 0.5f, 0, s[0] * 0.5f, s[1], s[0] * 0.5f,
                (int) c[0], (int) c[1], (int) c[2], light);
        // eye
        box(ms.peek(), vc, 0, s[1] * 0.7f, s[0] * 0.35f, 0.08f, 0.1f, 0.05f, 255, 255, 255, light);
        ms.pop();
        super.render(e, yaw, tickDelta, ms, vcp, light);
    }

    private static float[] colour(String kind) {
        return switch (kind) {
            case "goomba" -> new float[]{140, 90, 40};
            case "koopa" -> new float[]{50, 160, 50};
            case "bobomb", "king_bobomb" -> new float[]{30, 30, 30};
            case "bully", "big_bully" -> new float[]{180, 40, 40};
            case "boo", "big_boo" -> new float[]{230, 230, 255};
            case "whomp" -> new float[]{160, 150, 140};
            case "thwomp" -> new float[]{100, 100, 180};
            case "piranha" -> new float[]{40, 160, 40};
            case "chain_chomp" -> new float[]{40, 40, 40};
            case "bowser" -> new float[]{200, 120, 30};
            case "fly_guy" -> new float[]{200, 40, 40};
            case "lakitu" -> new float[]{220, 220, 80};
            case "chuckya" -> new float[]{200, 80, 160};
            case "amp" -> new float[]{255, 255, 80};
            case "mr_i" -> new float[]{240, 240, 240};
            default -> new float[]{200, 100, 200};
        };
    }

    private static float[] size(String kind) {
        return switch (kind) {
            case "whomp", "thwomp" -> new float[]{1.4f, 2.2f};
            case "bowser", "king_bobomb", "big_bully", "big_boo" -> new float[]{1.6f, 2.4f};
            case "chain_chomp" -> new float[]{1.2f, 1.2f};
            case "koopa", "chuckya" -> new float[]{0.7f, 1.2f};
            default -> new float[]{0.6f, 0.9f};
        };
    }

    private static void box(MatrixStack.Entry en, VertexConsumer vc,
                            float cx, float cy, float cz, float hx, float hy, float hz,
                            int r, int g, int b, int light) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy * 0.5f, y1 = cy + hy * 0.5f;
        float z0 = cz - hz, z1 = cz + hz;
        face(en, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, light);
        face(en, vc, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, light);
        face(en, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, light);
        face(en, vc, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, light);
        face(en, vc, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, light);
        face(en, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, light);
    }

    private static void face(MatrixStack.Entry en, VertexConsumer vc,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             int r, int g, int b, int light) {
        v(en, vc, x0, y0, z0, r, g, b, light); v(en, vc, x1, y1, z1, r, g, b, light);
        v(en, vc, x2, y2, z2, r, g, b, light); v(en, vc, x2, y2, z2, r, g, b, light);
        v(en, vc, x0, y0, z0, r, g, b, light); v(en, vc, x2, y2, z2, r, g, b, light);
        v(en, vc, x3, y3, z3, r, g, b, light); v(en, vc, x3, y3, z3, r, g, b, light);
    }

    private static void v(MatrixStack.Entry en, VertexConsumer vc, float x, float y, float z,
                          int r, int g, int b, int light) {
        vc.vertex(en, x, y, z).color(r, g, b, 255).texture(0f, 0f)
                .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(en, 0, 1, 0);
    }
}
