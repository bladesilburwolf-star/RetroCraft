package com.unrealcraft.client.quake3;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.Quake3MapEntity;
import com.unrealcraft.quake3.Quake3Bsp;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Quake3MapRenderer extends EntityRenderer<Quake3MapEntity> {
    private final Map<String, Quake3MapMesh> cache = new HashMap<>();

    public Quake3MapRenderer(EntityRendererFactory.Context c) {
        super(c);
        shadowRadius = 0;
    }

    private Quake3MapMesh mesh(String path) {
        if (path == null || path.isBlank()) return null;
        if (cache.containsKey(path)) return cache.get(path);
        try {
            float upb = UnrealConfig.get().q3UnitsPerBlock;
            if (upb <= 0.001f) upb = 32f;
            Quake3Bsp b = Quake3Bsp.load(Path.of(path));
            Quake3MapMesh m = Quake3MapMesh.build(b, upb);
            cache.put(path, m);
            Unrealcraft.LOGGER.info("Q3 mesh {}: {} triangles (upb={})", path, m.tris.size(), upb);
            return m;
        } catch (Exception e) {
            Unrealcraft.LOGGER.error("Q3 BSP failed {}", path, e);
            cache.put(path, null);
            return null;
        }
    }

    @Override
    public boolean shouldRender(Quake3MapEntity e, Frustum f, double x, double y, double z) {
        return true;
    }

    @Override
    public void render(Quake3MapEntity e, float yaw, float tick, MatrixStack ms,
                       VertexConsumerProvider consumers, int light) {
        Quake3MapMesh m = mesh(e.getBspPath());
        if (m == null || m.tris.isEmpty()) return;
        // MatrixStack is already translated to entity position by the dispatcher
        ms.push();
        Identifier tex = Identifier.ofVanilla("textures/block/stone.png");
        for (var entry : m.byTexture().entrySet()) {
            VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex));
            var en = ms.peek();
            for (var t : entry.getValue()) {
                for (int i = 0; i < 3; i++) {
                    int p = i * 3, u = i * 2;
                    vc.vertex(en, t.pos()[p], t.pos()[p + 1], t.pos()[p + 2])
                            .color(255, 255, 255, 255)
                            .texture(t.uv()[u], 1f - t.uv()[u + 1])
                            .overlay(OverlayTexture.DEFAULT_UV)
                            .light(0xF000F0)
                            .normal(en, 0, 1, 0);
                }
            }
        }
        ms.pop();
    }

    @Override
    public Identifier getTexture(Quake3MapEntity e) {
        return Identifier.ofVanilla("textures/block/stone.png");
    }
}
