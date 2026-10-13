package com.unrealcraft.client.retro;

import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.sm64.Sm64Objects;
import com.unrealcraft.sm64.Sm64ActorModels;
import com.unrealcraft.sm64.Sm64Visual;
import com.unrealcraft.config.UnrealConfig;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;

/**
 * Placeholder meshes for SM64 props at decomp placements.
 * Colours are distinct so each kind is readable in-game until real F3D actors land.
 */
public final class Sm64PropRenderer {
    private static final Identifier WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    private Sm64PropRenderer() {}

    public static void draw(
            List<Sm64Objects.Placement> props,
            Quake3Bsp.Placement pl,
            double anchorX, double anchorY, double anchorZ,
            MatrixStack ms,
            VertexConsumerProvider vcp,
            net.minecraft.util.math.Vec3d cam,
            double renderRadius
    ) {
        if (props == null || props.isEmpty()) return;
        double r2 = renderRadius * renderRadius;

        for (Sm64Objects.Placement p : props) {
            float lx = pl.lx(p.x());
            float ly = pl.ly(p.y());
            float lz = pl.lz(p.z());
            double wx = anchorX + lx, wy = anchorY + ly, wz = anchorZ + lz;
            double dx = wx - cam.x, dy = wy - cam.y, dz = wz - cam.z;
            if (dx * dx + dy * dy + dz * dz > r2) continue;

            ms.push();
            ms.translate(wx - cam.x, wy - cam.y, wz - cam.z);
            float k = 100f / Math.max(pl.scale(), 1f);
            ms.scale(k, k, k);
            // MACRO yaw is degrees; SPECIAL yaw is often 0 or s16-ish — treat as degrees for macros, small ints as s16/182
            float yawRad = Math.abs(p.yaw()) > 360
                    ? p.yaw() * (float) (Math.PI / 32768.0)
                    : p.yaw() * (float) (Math.PI / 180.0);
            ms.multiply(RotationAxis.POSITIVE_Y.rotation(yawRad));

            // Minecraft's immediate renderer closes the previous custom-texture buffer when a different layer is
            // requested, so a VertexConsumer must never be kept across another getBuffer() call: fetch it per prop.
            switch (p.kind()) {
                case "tree", "coin", "red_coin", "blue_coin", "star", "cannon", "door", "sign",
                     "flag", "chest", "box", "platform", "mine", "butterfly" ->
                        drawKindOrBox(ms, vcp, cam, wx, wz, yawRad, p, white(vcp));
                default -> drawBoxAt(ms.peek(), white(vcp), 0, 0.3f, 0, 0.2f, 0.3f, 0.2f, 180, 180, 180);
            }
            ms.pop();
        }
    }

    
    private static VertexConsumer white(VertexConsumerProvider vcp) {
        return vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(WHITE));
    }

    /**
     * Real tree model from actors/tree (two alpha-textured quads per tree, 800 or 512 units tall).
     * Source units are N64 units, drawn at 0.01 block per unit (the caller's scale makes that 1/scale);
     * UVs are texels and are normalised by the texture size. One buffer per texture, used immediately.
     * @return false when nothing could be drawn so the caller can fall back to the box tree
     */
    
    private static void drawKindOrBox(MatrixStack ms, VertexConsumerProvider vcp,
                                      net.minecraft.util.math.Vec3d cam,
                                      double wx, double wz, float yawRad,
                                      Sm64Objects.Placement p, VertexConsumer whiteVc) {
        try {
            if ("tree".equals(p.kind())) {
                float face = (float) Math.atan2(cam.x - wx, cam.z - wz);
                ms.multiply(RotationAxis.POSITIVE_Y.rotation(face - yawRad));
            }
            if (drawF3dKind(ms, vcp, p.kind(), p.modelOrPreset())) return;
        } catch (Throwable ignored) {}
        // box fallbacks
        MatrixStack.Entry en = ms.peek();
        switch (p.kind()) {
            case "tree" -> drawTree(en, whiteVc);
            case "coin" -> drawCoin(en, whiteVc, 255, 220, 40);
            case "red_coin" -> drawCoin(en, whiteVc, 255, 40, 40);
            case "blue_coin" -> drawCoin(en, whiteVc, 40, 120, 255);
            case "star" -> drawStar(en, whiteVc);
            case "cannon" -> drawCannon(en, whiteVc);
            case "door" -> drawDoor(en, whiteVc);
            case "sign" -> drawSign(en, whiteVc);
            case "flag" -> drawFlag(en, whiteVc);
            case "chest" -> drawChest(en, whiteVc);
            case "box" -> drawItemBox(en, whiteVc);
            case "platform" -> drawPlatform(en, whiteVc);
            case "mine" -> drawMine(en, whiteVc);
            case "butterfly" -> drawButterfly(en, whiteVc);
            default -> drawBoxAt(en, whiteVc, 0, 0.3f, 0, 0.2f, 0.3f, 0.2f, 180, 180, 180);
        }
    }

    private static boolean drawF3dKind(MatrixStack ms, VertexConsumerProvider vcp, String kind, String preset) {
        Path root = Path.of(UnrealConfig.get().sm64Dir.trim());
        Sm64ActorModels.Mesh mesh = Sm64ActorModels.forKind(root, kind, preset);
        if (mesh.tris().isEmpty()) return false;
        // Same as trees: group by texture, UV is already in texels (F3d divided by 32 once)
        Map<String, List<com.unrealcraft.sm64.F3d.Tri>> byTex = new LinkedHashMap<>();
        for (var tri : mesh.tris()) {
            byTex.computeIfAbsent(tri.texture() == null ? "" : tri.texture(), x -> new ArrayList<>()).add(tri);
        }
        MatrixStack.Entry en = ms.peek();
        final float s = 0.01f;
        boolean any = false;
        for (var e : byTex.entrySet()) {
            Identifier texId = null;
            int tw = 32, th = 32;
            if (!e.getKey().isEmpty()) {
                texId = Sm64Textures.get(root, e.getKey());
                try {
                    int[] size = Sm64Visual.textureSize(root, e.getKey());
                    if (size != null && size[0] > 0) { tw = size[0]; th = size[1]; }
                } catch (Throwable ignored) {}
            }
            // Missing PNG → white + vertex colour (avoid pure black slabs)
            VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(texId != null ? texId : WHITE));
            for (var tri : e.getValue()) {
                float[] xyz = tri.xyz(), uv = tri.uv();
                int[] rgb = tri.rgb();
                any = true;
                for (int k = 0; k < 4; k++) {
                    int i = Math.min(k, 2), pi = i * 3, ui = i * 2;
                    float tu = uv[ui] / tw, tv = uv[ui + 1] / th;
                    int r = rgb[pi], g = rgb[pi + 1], b = rgb[pi + 2];
                    // If no texture, boost brightness so props stay readable
                    if (texId == null) {
                        r = Math.min(255, r + 40);
                        g = Math.min(255, g + 40);
                        b = Math.min(255, b + 40);
                    }
                    vc.vertex(en, xyz[pi] * s, xyz[pi + 1] * s, xyz[pi + 2] * s)
                            .color(r, g, b, 255)
                            .texture(tu, tv)
                            .overlay(OverlayTexture.DEFAULT_UV)
                            .light(0xF000F0)
                            .normal(en, 0f, 1f, 0f);
                }
            }
        }
        return any;
    }

private static boolean drawF3dTree(MatrixStack ms, VertexConsumerProvider vcp, String preset) {
        Path root = Path.of(UnrealConfig.get().sm64Dir.trim());
        Sm64ActorModels.Mesh mesh = Sm64ActorModels.tree(root, preset);
        if (mesh.tris().isEmpty()) return false;
        Map<String, List<com.unrealcraft.sm64.F3d.Tri>> byTex = new LinkedHashMap<>();
        for (var t : mesh.tris()) byTex.computeIfAbsent(t.texture() == null ? "" : t.texture(), x -> new ArrayList<>()).add(t);
        MatrixStack.Entry en = ms.peek();
        final float u = 0.01f;
        for (var e : byTex.entrySet()) {
            Identifier tex = e.getKey().isEmpty() ? null : Sm64Textures.get(root, e.getKey());
            int[] size = e.getKey().isEmpty() ? new int[]{32, 32} : Sm64Visual.textureSize(root, e.getKey());
            VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex != null ? tex : WHITE));
            for (var tri : e.getValue()) {
                float[] xyz = tri.xyz(), uv = tri.uv();
                int[] rgb = tri.rgb();
                for (int k = 0; k < 4; k++) { // QUADS layer: triangle as (a, b, c, c)
                    int i = Math.min(k, 2), pi = i * 3, ui = i * 2;
                    vc.vertex(en, xyz[pi] * u, xyz[pi + 1] * u, xyz[pi + 2] * u)
                            .color(rgb[pi], rgb[pi + 1], rgb[pi + 2], 255)
                            .texture(uv[ui] / size[0], uv[ui + 1] / size[1])
                            .overlay(OverlayTexture.DEFAULT_UV)
                            .light(0xF000F0)
                            .normal(en, 0f, 1f, 0f);
                }
            }
        }
        return true;
    }

private static void drawTree(MatrixStack.Entry en, VertexConsumer vc) {
        float trunkH = 1.5f, trunkW = 0.2f, foliage = 0.9f;
        drawBoxAt(en, vc, 0, trunkH * 0.5f, 0, trunkW, trunkH, trunkW, 110, 70, 30);
        drawBoxAt(en, vc, 0, trunkH + foliage * 0.45f, 0, foliage, foliage * 0.9f, foliage, 40, 140, 50);
        drawBoxAt(en, vc, 0, trunkH + foliage * 1.1f, 0, foliage * 0.72f, foliage * 0.65f, foliage * 0.72f, 50, 160, 55);
        drawBoxAt(en, vc, 0, trunkH + foliage * 1.55f, 0, foliage * 0.45f, foliage * 0.45f, foliage * 0.45f, 60, 170, 60);
    }

    private static void drawCoin(MatrixStack.Entry en, VertexConsumer vc, int r, int g, int b) {
        drawBoxAt(en, vc, 0, 0.5f, 0, 0.28f, 0.28f, 0.06f, r, g, b);
    }

    private static void drawStar(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.6f, 0, 0.35f, 0.35f, 0.35f, 255, 230, 50);
        drawBoxAt(en, vc, 0, 1.0f, 0, 0.12f, 0.25f, 0.12f, 255, 240, 80); // top point
    }

    private static void drawCannon(MatrixStack.Entry en, VertexConsumer vc) {
        // base
        drawBoxAt(en, vc, 0, 0.25f, 0, 0.55f, 0.5f, 0.55f, 60, 60, 60);
        // barrel pointing +Z
        drawBoxAt(en, vc, 0, 0.7f, 0.45f, 0.22f, 0.22f, 0.7f, 90, 90, 90);
        // rim
        drawBoxAt(en, vc, 0, 0.7f, 0.95f, 0.28f, 0.28f, 0.1f, 40, 40, 40);
    }

    private static void drawDoor(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 1.0f, 0, 0.7f, 2.0f, 0.12f, 120, 80, 40);
        // handle
        drawBoxAt(en, vc, 0.45f, 1.0f, 0.1f, 0.08f, 0.08f, 0.08f, 200, 180, 60);
    }

    private static void drawSign(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.6f, 0, 0.08f, 1.2f, 0.08f, 100, 70, 30); // post
        drawBoxAt(en, vc, 0, 1.4f, 0, 0.5f, 0.4f, 0.08f, 200, 190, 150); // board
    }

    private static void drawFlag(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 1.5f, 0, 0.06f, 3.0f, 0.06f, 180, 180, 180); // pole
        drawBoxAt(en, vc, 0.35f, 2.6f, 0, 0.55f, 0.4f, 0.04f, 220, 40, 40); // flag
    }

    private static void drawChest(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.35f, 0, 0.6f, 0.7f, 0.45f, 140, 90, 40);
        drawBoxAt(en, vc, 0, 0.75f, 0, 0.62f, 0.15f, 0.47f, 160, 110, 50); // lid
    }

    private static void drawItemBox(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.4f, 0, 0.4f, 0.8f, 0.4f, 230, 50, 50); // ! box red
        drawBoxAt(en, vc, 0, 0.4f, 0.42f, 0.15f, 0.35f, 0.02f, 255, 255, 255); // !
    }

    /** Teeter / seesaw / generic platform — long flat plank. */
    private static void drawPlatform(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.12f, 0, 1.2f, 0.2f, 0.4f, 160, 120, 70);
        // pivot stub
        drawBoxAt(en, vc, 0, -0.15f, 0, 0.15f, 0.2f, 0.15f, 100, 100, 100);
    }

    private static void drawMine(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, 0, 0.35f, 0, 0.35f, 0.35f, 0.35f, 30, 30, 30);
        drawBoxAt(en, vc, 0, 0.7f, 0, 0.08f, 0.15f, 0.08f, 200, 40, 40); // fuse
    }

    private static void drawButterfly(MatrixStack.Entry en, VertexConsumer vc) {
        drawBoxAt(en, vc, -0.15f, 0.5f, 0, 0.12f, 0.02f, 0.2f, 255, 180, 220);
        drawBoxAt(en, vc, 0.15f, 0.5f, 0, 0.12f, 0.02f, 0.2f, 255, 180, 220);
        drawBoxAt(en, vc, 0, 0.5f, 0, 0.04f, 0.04f, 0.04f, 40, 40, 40);
    }

    private static void drawBoxAt(MatrixStack.Entry en, VertexConsumer vc,
                                  float cx, float cy, float cz,
                                  float hx, float hy, float hz,
                                  int r, int g, int b) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy * 0.5f, y1 = cy + hy * 0.5f;
        float z0 = cz - hz, z1 = cz + hz;
        // note: hy treated as full height for trees used half-extents differently;
        // callers pass half-extent in hx/hz and full or half in hy consistently with prior tree code
        // Re-normalize: treat hy as full height
        y0 = cy - hy * 0.5f;
        y1 = cy + hy * 0.5f;
        quad(en, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, 0, 0, 1);
        quad(en, vc, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r, g, b, 0, 0, -1);
        quad(en, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, -1, 0, 0);
        quad(en, vc, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r, g, b, 1, 0, 0);
        quad(en, vc, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, 0, 1, 0);
        quad(en, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, 0, -1, 0);
    }

    private static void quad(MatrixStack.Entry en, VertexConsumer vc,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             int r, int g, int b, float nx, float ny, float nz) {
        vert(en, vc, x0, y0, z0, r, g, b, nx, ny, nz);
        vert(en, vc, x1, y1, z1, r, g, b, nx, ny, nz);
        vert(en, vc, x2, y2, z2, r, g, b, nx, ny, nz);
        vert(en, vc, x2, y2, z2, r, g, b, nx, ny, nz);
        vert(en, vc, x0, y0, z0, r, g, b, nx, ny, nz);
        vert(en, vc, x2, y2, z2, r, g, b, nx, ny, nz);
        vert(en, vc, x3, y3, z3, r, g, b, nx, ny, nz);
        vert(en, vc, x3, y3, z3, r, g, b, nx, ny, nz);
    }

    private static void vert(MatrixStack.Entry en, VertexConsumer vc,
                             float x, float y, float z, int r, int g, int b,
                             float nx, float ny, float nz) {
        vc.vertex(en, x, y, z)
                .color(r, g, b, 255)
                .texture(0f, 0f)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF000F0)
                .normal(en, nx, ny, nz);
    }
}
