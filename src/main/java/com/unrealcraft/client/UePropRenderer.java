package com.unrealcraft.client;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.UePropEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Quaternionf;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class UePropRenderer extends EntityRenderer<UePropEntity> {
    private static final Identifier FALLBACK = Identifier.ofVanilla("textures/block/stone.png");
    private final Map<String, GlbRender.Prepared> cache = new HashMap<>();

    public UePropRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.3f;
    }

    private GlbRender.Prepared mesh(String model, GlbOptions entityOptions) {
        if (model == null || model.isBlank()) return null;
        String cacheKey = model + "|" + (entityOptions != null && entityOptions.map);
        if (cache.containsKey(cacheKey)) return cache.get(cacheKey);
        try {
            GlbMesh mesh;
            if (model.startsWith("json:")) {
                mesh = loadMeshJson(Path.of(model.substring(5)), entityOptions);
            } else {
                byte[] bytes = loadBytes(model);
                if (bytes == null) {
                    Unrealcraft.LOGGER.warn("GLB not found: {}", model);
                    cache.put(cacheKey, null);
                    return null;
                }
                mesh = GlbLoader.load(bytes, entityOptions == null ? GlbOptions.defaults() : entityOptions);
            }
            if (mesh == null) { cache.put(cacheKey, null); return null; }
            GlbRender.Prepared p = GlbRender.prepare(model, mesh, FALLBACK);
            cache.put(cacheKey, p);
            return p;
        } catch (Exception e) {
            Unrealcraft.LOGGER.warn("Failed to load GLB {}", model, e);
            cache.put(cacheKey, null);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private GlbMesh loadMeshJson(Path path, GlbOptions options) throws Exception {
        if (!Files.isRegularFile(path)) throw new java.io.IOException("mesh JSON not found: " + path);
        Object rootObj = MiniJson.parse(Files.readString(path));
        if (!(rootObj instanceof Map<?,?> root)) throw new java.io.IOException("mesh JSON root is not an object");
        Object trisObj = root.get("triangles");
        if (!(trisObj instanceof java.util.List<?> tris)) throw new java.io.IOException("mesh JSON has no triangles");
        java.util.ArrayList<Float> pos = new java.util.ArrayList<>();
        java.util.ArrayList<Float> nrm = new java.util.ArrayList<>();
        java.util.ArrayList<Float> uv = new java.util.ArrayList<>();
        for (Object to : tris) {
            if (!(to instanceof java.util.List<?> tri) || tri.size() < 3) continue;
            float[][] v = new float[3][3];
            for (int k=0;k<3;k++) {
                Object vo=tri.get(k);
                if (!(vo instanceof java.util.List<?> a) || a.size()<3) continue;
                v[k][0]=((Number)a.get(0)).floatValue();
                v[k][1]=((Number)a.get(1)).floatValue();
                v[k][2]=((Number)a.get(2)).floatValue();
            }
            float ux=v[1][0]-v[0][0],uy=v[1][1]-v[0][1],uz=v[1][2]-v[0][2];
            float vx=v[2][0]-v[0][0],vy=v[2][1]-v[0][1],vz=v[2][2]-v[0][2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz); if(len>1e-6){nx/=len;ny/=len;nz/=len;}
            for(int k=0;k<3;k++){pos.add(v[k][0]);pos.add(v[k][1]);pos.add(v[k][2]);nrm.add(nx);nrm.add(ny);nrm.add(nz);uv.add(k==1?1f:0f);uv.add(k==2?1f:0f);}
        }
        GlbMesh mesh=new GlbMesh(toArray(pos),toArray(nrm),toArray(uv));
        mesh.triImage=new int[mesh.triangleCount()];
        java.util.Arrays.fill(mesh.triImage,-1);
        if(options!=null && options.map) mesh.fit(options);
        return mesh;
    }
    private static float[] toArray(java.util.List<Float> a){float[] r=new float[a.size()];for(int i=0;i<r.length;i++)r[i]=a.get(i);return r;}

    private byte[] loadBytes(String model) {
        String name = model.endsWith(".glb") ? model : model + ".glb";
        // 1) config external dir
        String extra = UnrealConfig.get().glbDir;
        if (extra != null && !extra.isBlank()) {
            Path p = Path.of(extra, name);
            if (Files.isRegularFile(p)) {
                try { return Files.readAllBytes(p); } catch (Exception ignored) {}
            }
            Path p2 = Path.of(extra, name.replace('\\', '/'));
            if (Files.isRegularFile(p2)) {
                try { return Files.readAllBytes(p2); } catch (Exception ignored) {}
            }
        }
        // 2) resource pack path assets/unrealcraft/models/glb/<name>
        Identifier id = Unrealcraft.id("models/glb/" + name.replace('\\', '/'));
        try {
            Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(id);
            if (res.isPresent()) {
                try (InputStream in = res.get().getInputStream()) {
                    return in.readAllBytes();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public void render(UePropEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light) {
        GlbOptions options = entity.isMapMode()
                ? GlbOptions.mapDefaults(UnrealConfig.get().mapUnitsPerBlock,
                    UnrealConfig.get().mapRotX, UnrealConfig.get().mapRotY, UnrealConfig.get().mapRotZ)
                : GlbOptions.rawDefaults(1.0f);
        GlbRender.Prepared p = mesh(entity.getModel(), options);
        if (p == null) return;
        matrices.push();
        // Map/prop orientation (Minewind-style quat), then uniform scale
        matrices.multiply(new Quaternionf(entity.getQx(), entity.getQy(), entity.getQz(), entity.getQw()));
        float s = entity.getPropScale();
        matrices.scale(s, s, s);
        GlbRender.draw(p, matrices.peek(), consumers, 0, light);
        matrices.pop();
    }

    @Override
    public boolean shouldRender(UePropEntity entity, net.minecraft.client.render.Frustum frustum, double x, double y, double z) {
        // Ignore frustum box (mesh is larger than entity hitbox); use distance to entity origin only
        double dx = entity.getX() - x, dy = entity.getY() - y, dz = entity.getZ() - z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double range = 256.0 + Math.min(256.0, Math.abs(entity.getPropScale()) * 2.0);
        return distSq < range * range;
    }

    @Override
    public Identifier getTexture(UePropEntity entity) {
        return FALLBACK;
    }
}
