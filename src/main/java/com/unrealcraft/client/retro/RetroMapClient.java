package com.unrealcraft.client.retro;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.client.quake3.Quake3MapMesh;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.net.RetroMapPayload;
import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.sm64.Sm64Level;
import com.unrealcraft.sm64.Sm64Objects;
import com.unrealcraft.sm64.Sm64Enemies;
import com.unrealcraft.sm64.Sm64Visual;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws imported maps from the world-render pipeline. SM64 maps also auto-draw
 * script-placed props (trees, coins, stars) via Sm64Objects + Sm64PropRenderer.
 */
public final class RetroMapClient {
    private static final Identifier TEX = Identifier.ofVanilla("textures/block/stone.png");
    private static final Identifier WHITE = Identifier.ofVanilla("textures/block/white_concrete.png");

    static final class Active {
        final String kind, path;
        final double x, y, z;
        Quake3MapMesh mesh;
        boolean failed;
        Sm64Level.Result sm64;
        Sm64VisualMesh visual;
        Quake3MapMesh backdrop; // tinted collision shape, drawn slightly behind a partly-read visual model
        List<Sm64Objects.Placement> props = List.of();
        List<Sm64Enemies.Spawn> enemies = List.of();
        Active(RetroMapPayload p) {
            kind = p.kind();
            path = p.path();
            x = p.x();
            y = p.y();
            z = p.z();
        }
    }

    private static final Map<String, Active> ACTIVE = new LinkedHashMap<>();

    private RetroMapClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(RetroMapPayload.ID, (payload, ctx) -> ctx.client().execute(() -> {
            if ("clear".equals(payload.action())) {
                ACTIVE.remove(payload.kind());
                if ("sm64".equals(payload.kind())) MarioClient.setEnabled(false);
            } else if ("mario".equals(payload.action())) {
                MarioClient.setEnabled("on".equals(payload.path()));
            } else {
                ACTIVE.put(payload.kind(), new Active(payload));
            }
        }));
        MarioClient.init();
        Sm64Hud.init();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ACTIVE.clear();
            MarioClient.setEnabled(false);
        });
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            if (ACTIVE.isEmpty()) return;
            VertexConsumerProvider vcp = ctx.consumers();
            MatrixStack ms = ctx.matrixStack();
            if (vcp == null || ms == null) return;
            Vec3d cam = ctx.camera().getPos();
            for (Active a : ACTIVE.values()) draw(a, ms, vcp, cam);
            MarioClient.render(ms, vcp, cam);
        });
    }

    static Active sm64() {
        return ACTIVE.get("sm64");
    }

    static void prepare(Active a) {
        if (a.failed || a.mesh != null) return;
        try {
            UnrealConfig cfg = UnrealConfig.get();
            if ("q3".equals(a.kind)) {
                a.mesh = Quake3MapMesh.build(Quake3Bsp.load(Path.of(a.path)), cfg.q3UnitsPerBlock);
            } else if ("sm64".equals(a.kind)) {
                int bar = a.path.lastIndexOf('|');
                Path levelDir = Path.of(a.path.substring(0, bar));
                int area = Integer.parseInt(a.path.substring(bar + 1));
                var res = Sm64Level.load(levelDir, area, cfg.sm64UnitsPerBlock);
                a.sm64 = res;
                a.mesh = Quake3MapMesh.build(null, res.tris(), Sm64Level.placement(res, cfg.sm64UnitsPerBlock));
                if (cfg.sm64Textures) buildVisual(a, cfg, res, levelDir);
                loadProps(a, levelDir, res);
            } else {
                a.failed = true;
                return;
            }
            Unrealcraft.LOGGER.info("{} mesh {}: {} triangles, {} cells, anchor ({}, {}, {})",
                    a.kind, a.path, a.mesh.triCount, a.mesh.cells.size(), a.x, a.y, a.z);
        } catch (Exception e) {
            Unrealcraft.LOGGER.error("{} mesh failed for {}", a.kind, a.path, e);
            a.failed = true;
        }
    }

    private static void loadProps(Active a, Path levelDir, Sm64Level.Result res) {
        try {
            // levelDir is .../levels/<name>; sm64 root is parent of "levels"
            Path root = levelDir.getParent() != null ? levelDir.getParent().getParent() : null;
            if (root == null || !java.nio.file.Files.isDirectory(root.resolve("actors"))) {
                // fallback: config sm64Dir
                Path cfgRoot = Path.of(UnrealConfig.get().sm64Dir.trim());
                if (java.nio.file.Files.isDirectory(cfgRoot.resolve("actors"))) root = cfgRoot;
                else if (java.nio.file.Files.isDirectory(cfgRoot.resolve("levels"))) root = cfgRoot;
            }
            if (root == null) {
                Unrealcraft.LOGGER.warn("SM64 props: cannot resolve sm64 root from {}", levelDir);
                a.props = List.of();
                return;
            }
            List<Sm64Objects.Placement> list = Sm64Objects.load(root, res.level(), res.area());
            a.props = list;
            long trees = list.stream().filter(pl -> "tree".equals(pl.kind())).count();
            long coins = list.stream().filter(pl -> pl.kind().contains("coin")).count();
            long cannons = list.stream().filter(pl -> "cannon".equals(pl.kind())).count();
            long platforms = list.stream().filter(pl -> "platform".equals(pl.kind())).count();
            Unrealcraft.LOGGER.info("SM64 {}: props {} (trees={} coins={} cannons={} platforms={}) root={}",
                    res.level(), list.size(), trees, coins, cannons, platforms, root);
            try {
                a.enemies = Sm64Enemies.load(root, res.level(), res.area());
                Unrealcraft.LOGGER.info("SM64 {}: enemies {}", res.level(), a.enemies.size());
            } catch (Exception ee) {
                Unrealcraft.LOGGER.warn("SM64 enemies load failed: {}", ee.toString());
                a.enemies = List.of();
            }
        } catch (Exception e) {
            Unrealcraft.LOGGER.warn("SM64 props load failed: {}", e.toString());
            a.props = List.of();
        }
    }

    private static void buildVisual(Active a, UnrealConfig cfg, Sm64Level.Result res, Path levelDir) {
        try {
            Path root = levelDir.getParent().getParent();
            Sm64Visual.Result vis;
            try {
                vis = Sm64Visual.load(root, levelDir.getFileName().toString(), res.area());
            } catch (OutOfMemoryError oom) {
                Unrealcraft.LOGGER.error("SM64 {}: visual OOM — using collision only", res.level());
                System.gc();
                return;
            }
            double coverage = Sm64Visual.coverage(vis, res.tris());
            // Previously required 90% coverage — LLL/WF/TTM/RR/DDD often sit lower because
            // terrain is split across sub-geos. Use any non-empty visual so textures show.
            if (vis.tris().isEmpty()) {
                Unrealcraft.LOGGER.info("SM64 {}: no visual triangles, using collision shape", res.level());
                return;
            }
            a.visual = Sm64VisualMesh.build(vis, a.mesh.placement);
            if (coverage < 0.9) {
                // Part of this level's terrain lives in object geos that are not read yet. Keep the textured
                // part but also draw the collision shape 4 units behind every surface, so the rest of the level
                // is visible (tinted) instead of being an invisible floor.
                List<com.unrealcraft.quake3.Quake3Bsp.Tri> shifted = new ArrayList<>(res.tris().size());
                for (var t : res.tris()) {
                    float[] p = t.xyz().clone(), n = t.nrm();
                    for (int i = 0; i < 9; i++) p[i] -= n[i % 3] * 4f;
                    shifted.add(new com.unrealcraft.quake3.Quake3Bsp.Tri(t.texture(), p, t.uv(), n));
                }
                a.backdrop = Quake3MapMesh.build(null, shifted, a.mesh.placement);
                Unrealcraft.LOGGER.info("SM64 {}: visual covers {}% of the level ({} tris, {} DLs): textured part + tinted collision backdrop",
                        res.level(), Math.round(coverage * 100), vis.tris().size(), vis.displayLists());
            }
            Unrealcraft.LOGGER.info("SM64 {}: {} visual triangles, {} textures{}", res.level(), a.visual.triCount,
                    a.visual.byTexture.size(), vis.unresolved().isEmpty() ? "" : ", unresolved " + vis.unresolved());
        } catch (Exception e) {
            Unrealcraft.LOGGER.warn("SM64 visual model failed, using collision shape: {}", e.toString());
        }
    }

    private static void draw(Active a, MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        prepare(a);
        if (a.failed || a.mesh == null) return;
        if (a.visual != null) {
            drawVisual(a, a.visual, ms, vcp, cam);
            if (a.backdrop != null) drawQ3(a, a.backdrop, ms, vcp, cam);
        } else drawQ3(a, a.mesh, ms, vcp, cam);
        if ("sm64".equals(a.kind)) {
            double r = Math.max(32, UnrealConfig.get().retroRenderRadius);
            if (a.props != null && !a.props.isEmpty())
                Sm64PropRenderer.draw(a.props, a.mesh.placement, a.x, a.y, a.z, ms, vcp, cam, r);
            if (a.enemies != null && !a.enemies.isEmpty())
                Sm64EnemyRenderer.draw(a.enemies, a.mesh.placement, a.x, a.y, a.z, ms, vcp, cam, r);
        }
    }

    private static void drawVisual(Active a, Sm64VisualMesh mesh, MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        UnrealConfig cfg = UnrealConfig.get();
        Path root = Path.of(cfg.sm64Dir.trim());
        double lx = cam.x - a.x, ly = cam.y - a.y, lz = cam.z - a.z;
        double r = Math.max(32, cfg.retroRenderRadius), r2 = r * r;
        ms.push();
        ms.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z);
        MatrixStack.Entry en = ms.peek();
        for (var e : mesh.byTexture.entrySet()) {
            String key = e.getKey();
            boolean translucent = key.endsWith(Sm64VisualMesh.TRANSLUCENT);
            if (translucent) key = key.substring(0, key.length() - Sm64VisualMesh.TRANSLUCENT.length());
            Identifier tex = key.isEmpty() ? null : Sm64Textures.get(root, key);
            Identifier use = tex != null ? tex : WHITE;
            VertexConsumer vc = vcp.getBuffer(translucent ? RenderLayer.getEntityTranslucent(use)
                    : RenderLayer.getEntityCutoutNoCull(use));
            for (Sm64VisualMesh.Cell c : e.getValue()) {
                if (c.distSq(lx, ly, lz) > r2) continue;
                for (Sm64VisualMesh.Tri t : c.tris) {
                    for (int k = 0; k < 4; k++) {
                        int i = Math.min(k, 2), p = i * 3, u = i * 2;
                        vc.vertex(en, t.pos[p], t.pos[p + 1], t.pos[p + 2])
                                .color(t.rgb[p], t.rgb[p + 1], t.rgb[p + 2], 255)
                                .texture(t.uv[u], t.uv[u + 1])
                                .overlay(OverlayTexture.DEFAULT_UV)
                                .light(0xF000F0)
                                .normal(en, 0f, 1f, 0f);
                    }
                }
            }
        }
        ms.pop();
    }

    private static void drawQ3(Active a, Quake3MapMesh mesh, MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        double lx = cam.x - a.x, ly = cam.y - a.y, lz = cam.z - a.z;
        double r = Math.max(32, UnrealConfig.get().retroRenderRadius), r2 = r * r;
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX));
        ms.push();
        ms.translate(a.x - cam.x, a.y - cam.y, a.z - cam.z);
        MatrixStack.Entry en = ms.peek();
        for (Quake3MapMesh.Cell c : mesh.cells) {
            if (c.distSq(lx, ly, lz) > r2) continue;
            for (Quake3MapMesh.Tri t : c.tris) {
                for (int k = 0; k < 4; k++) {
                    int i = Math.min(k, 2);
                    int p = i * 3, u = i * 2;
                    vc.vertex(en, t.pos()[p], t.pos()[p + 1], t.pos()[p + 2])
                            .color(t.r(), t.g(), t.b(), 255)
                            .texture(t.uv()[u], 1f - t.uv()[u + 1])
                            .overlay(OverlayTexture.DEFAULT_UV)
                            .light(0xF000F0)
                            .normal(en, t.nrm()[0], t.nrm()[1], t.nrm()[2]);
                }
            }
        }
        ms.pop();
    }
}
