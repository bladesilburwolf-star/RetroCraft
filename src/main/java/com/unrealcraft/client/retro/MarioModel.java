package com.unrealcraft.client.retro;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * PLACEHOLDER Mario built from coloured boxes, in SM64 units (feet at y=0, facing +Z, ~160 tall).
 * The real model (actors/mario geo layout + display lists + animations from the decomp) replaces this next.
 */
public final class MarioModel {
    private MarioModel() {}

    // cx, cy, cz, sx, sy, sz, rgb
    private static final float[][] PARTS = {
            {-20, 8, 8, 34, 16, 62, 0x6B3E1E}, {20, 8, 8, 34, 16, 62, 0x6B3E1E},           // shoes
            {-18, 32, 0, 30, 32, 32, 0x1E3CC8}, {18, 32, 0, 30, 32, 32, 0x1E3CC8},        // legs
            {0, 70, 0, 74, 44, 46, 0x1E3CC8},                                               // overalls
            {0, 99, 0, 70, 20, 42, 0xD22020},                                               // shirt
            {-48, 84, 0, 24, 44, 28, 0xD22020}, {48, 84, 0, 24, 44, 28, 0xD22020},        // arms
            {-48, 54, 0, 28, 24, 28, 0xFFFFFF}, {48, 54, 0, 28, 24, 28, 0xFFFFFF},        // gloves
            {0, 128, 0, 56, 44, 52, 0xF5C8A0},                                              // head
            {0, 118, 30, 14, 12, 12, 0xF5C8A0},                                             // nose
            {0, 112, 27, 40, 8, 10, 0x4A2A12},                                              // moustache
            {0, 128, -22, 54, 38, 10, 0x4A2A12},                                            // hair
            {0, 154, -2, 60, 14, 58, 0xD22020},                                             // cap
            {0, 146, 36, 54, 6, 24, 0xD22020},                                              // brim
            {0, 158, 12, 14, 6, 14, 0xFFFFFF},                                              // cap emblem
    };

    /** Draw into a QUADS layer. 'invScale' converts SM64 units to blocks. */
    public static void draw(MatrixStack.Entry en, VertexConsumer vc, float invScale) {
        for (float[] p : PARTS) {
            int rgb = (int) p[6];
            box(en, vc, p[0] * invScale, p[1] * invScale, p[2] * invScale,
                    p[3] * invScale * .5f, p[4] * invScale * .5f, p[5] * invScale * .5f,
                    (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        }
    }

    private static void box(MatrixStack.Entry en, VertexConsumer vc, float cx, float cy, float cz,
                            float hx, float hy, float hz, int r, int g, int b) {
        float x0 = cx - hx, x1 = cx + hx, y0 = cy - hy, y1 = cy + hy, z0 = cz - hz, z1 = cz + hz;
        quad(en, vc, r, g, b, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);   // +Z (front)
        quad(en, vc, r, g, b, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);  // -Z
        quad(en, vc, r, g, b, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);   // +X
        quad(en, vc, r, g, b, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);  // -X
        quad(en, vc, r, g, b, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);   // +Y
        quad(en, vc, r, g, b, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);  // -Y
    }

    private static void quad(MatrixStack.Entry en, VertexConsumer vc, int r, int g, int b, float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        v(en, vc, r, g, b, nx, ny, nz, ax, ay, az, 0, 1);
        v(en, vc, r, g, b, nx, ny, nz, bx, by, bz, 1, 1);
        v(en, vc, r, g, b, nx, ny, nz, cx, cy, cz, 1, 0);
        v(en, vc, r, g, b, nx, ny, nz, dx, dy, dz, 0, 0);
    }

    private static void v(MatrixStack.Entry en, VertexConsumer vc, int r, int g, int b, float nx, float ny, float nz,
                          float x, float y, float z, float u, float w) {
        vc.vertex(en, x, y, z).color(r, g, b, 255).texture(u, w).overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF000F0).normal(en, nx, ny, nz);
    }
}
