package com.unrealcraft.client.retro;

import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.sm64.Sm64Visual;

import java.util.*;

/** CPU-side textured level mesh (pure Java). Grouped by texture, bucketed into 16-block cells for distance culling. */
public final class Sm64VisualMesh {
    public static final int CELL = 16;
    /** Suffix on a texture key for geometry that must be drawn alpha-blended (LAYER_TRANSPARENT*). */
    public static final String TRANSLUCENT = "|T";
    /** Decal layers are pushed this far (blocks) along their normal so they do not z-fight the ground. */
    private static final float DECAL_OFFSET = 0.015f;

    public static final class Tri {
        public final float[] pos = new float[9], uv = new float[6];
        public final int[] rgb = new int[9];
    }

    public static final class Cell {
        public final List<Tri> tris = new ArrayList<>();
        public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        public float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        public double distSq(double x, double y, double z) {
            double dx = Math.max(Math.max(minX - x, 0), x - maxX);
            double dy = Math.max(Math.max(minY - y, 0), y - maxY);
            double dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** key = PNG path relative to the sm64 root, or "" for untextured (vertex-coloured) geometry. */
    public final Map<String, List<Cell>> byTexture = new LinkedHashMap<>();
    public int triCount;

    private Sm64VisualMesh() {}

    public static Sm64VisualMesh build(Sm64Visual.Result vis, Quake3Bsp.Placement pl) {
        Sm64VisualMesh m = new Sm64VisualMesh();
        Map<String, Map<Long, Cell>> tmp = new LinkedHashMap<>();
        for (Sm64Visual.Tri vt : vis.tris()) {
            var t = vt.tri();
            String base = t.texture() == null ? "" : t.texture();
            String key = vt.layer().contains("TRANSPARENT") ? base + TRANSLUCENT : base;
            int[] size = vis.texSize().get(base);
            float tw = size == null ? 32f : size[0], th = size == null ? 32f : size[1];
            Tri out = new Tri();
            for (int i = 0; i < 9; i += 3) {
                out.pos[i] = pl.lx(t.xyz()[i]); out.pos[i + 1] = pl.ly(t.xyz()[i + 1]); out.pos[i + 2] = pl.lz(t.xyz()[i + 2]);
            }
            for (int i = 0; i < 3; i++) {
                out.uv[i * 2] = t.uv()[i * 2] / tw;
                out.uv[i * 2 + 1] = t.uv()[i * 2 + 1] / th;
            }
            System.arraycopy(t.rgb(), 0, out.rgb, 0, 9);
            if (vt.layer().contains("DECAL")) offsetAlongNormal(out.pos);
            float cx = (out.pos[0] + out.pos[3] + out.pos[6]) / 3f, cy = (out.pos[1] + out.pos[4] + out.pos[7]) / 3f,
                    cz = (out.pos[2] + out.pos[5] + out.pos[8]) / 3f;
            long ck = ((long) Math.floorDiv((int) Math.floor(cx), CELL) & 0xFFFFF) << 40
                    | ((long) Math.floorDiv((int) Math.floor(cy), CELL) & 0xFFFFF) << 20
                    | ((long) Math.floorDiv((int) Math.floor(cz), CELL) & 0xFFFFF);
            Cell c = tmp.computeIfAbsent(key, k -> new LinkedHashMap<>()).computeIfAbsent(ck, k -> new Cell());
            c.tris.add(out);
            for (int i = 0; i < 9; i += 3) {
                c.minX = Math.min(c.minX, out.pos[i]); c.maxX = Math.max(c.maxX, out.pos[i]);
                c.minY = Math.min(c.minY, out.pos[i + 1]); c.maxY = Math.max(c.maxY, out.pos[i + 1]);
                c.minZ = Math.min(c.minZ, out.pos[i + 2]); c.maxZ = Math.max(c.maxZ, out.pos[i + 2]);
            }
            m.triCount++;
        }
        for (var e : tmp.entrySet()) m.byTexture.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        return m;
    }

    private static void offsetAlongNormal(float[] p) {
        float ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2], vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-9f) return;
        nx = nx / l * DECAL_OFFSET; ny = ny / l * DECAL_OFFSET; nz = nz / l * DECAL_OFFSET;
        for (int i = 0; i < 9; i += 3) { p[i] += nx; p[i + 1] += ny; p[i + 2] += nz; }
    }
}
