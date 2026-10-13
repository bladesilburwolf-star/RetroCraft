package com.unrealcraft.sm64;

import com.unrealcraft.quake3.Quake3Bsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds simple solid boxes for props that Mario should stand on / bump into.
 * Units are SM64 world coords (same as level collision tris).
 * Safe — only AABBs, no F3D parse.
 */
public final class Sm64PropCollision {
    private Sm64PropCollision() {}

    /** Kinds that get solid collision. Coins/stars/butterflies are collectible / decorative only. */
    public static boolean isSolid(String kind) {
        return switch (kind) {
            case "platform", "box", "chest", "cannon", "door", "sign", "mine" -> true;
            default -> false;
        };
    }

    /** Half-extents in SM64 units: {hx, hy, hz} — box centered at prop pos with bottom on y. */
    public static float[] halfExtents(String kind) {
        return switch (kind) {
            case "platform" -> new float[]{200, 25, 80};   // long plank
            case "box" -> new float[]{50, 50, 50};
            case "chest" -> new float[]{60, 40, 45};
            case "cannon" -> new float[]{70, 60, 70};
            case "door" -> new float[]{10, 120, 60};
            case "sign" -> new float[]{20, 80, 20};
            case "mine" -> new float[]{40, 40, 40};
            default -> new float[]{40, 40, 40};
        };
    }

    /**
     * Append solid prop boxes to an existing level collision mesh.
     * Call after {@code new Sm64Collision(levelTris)} then rebuild, or use {@link #merge}.
     */
    public static List<Quake3Bsp.Tri> extraTris(List<Sm64Objects.Placement> props) {
        List<Quake3Bsp.Tri> out = new ArrayList<>();
        if (props == null) return out;
        for (Sm64Objects.Placement p : props) {
            if (!isSolid(p.kind())) continue;
            float[] h = halfExtents(p.kind());
            // bottom of box on prop y (SM64 objects sit on the ground point)
            float cx = p.x(), cy = p.y() + h[1], cz = p.z();
            addBox(out, cx, cy, cz, h[0], h[1], h[2]);
        }
        return out;
    }

    /** Level tris + prop solids → one list for Sm64Collision. */
    public static List<Quake3Bsp.Tri> merge(List<Quake3Bsp.Tri> level, List<Sm64Objects.Placement> props) {
        List<Quake3Bsp.Tri> all = new ArrayList<>(level);
        all.addAll(extraTris(props));
        return all;
    }

    private static void addBox(List<Quake3Bsp.Tri> out, float cx, float cy, float cz,
                               float hx, float hy, float hz) {
        float x0 = cx - hx, x1 = cx + hx;
        float y0 = cy - hy, y1 = cy + hy;
        float z0 = cz - hz, z1 = cz + hz;
        // top (floor)
        tri(out, x0, y1, z0, x1, y1, z0, x1, y1, z1);
        tri(out, x0, y1, z0, x1, y1, z1, x0, y1, z1);
        // bottom (ceil when under)
        tri(out, x0, y0, z0, x1, y0, z1, x1, y0, z0);
        tri(out, x0, y0, z0, x0, y0, z1, x1, y0, z1);
        // +Z wall
        tri(out, x0, y0, z1, x1, y0, z1, x1, y1, z1);
        tri(out, x0, y0, z1, x1, y1, z1, x0, y1, z1);
        // -Z
        tri(out, x1, y0, z0, x0, y0, z0, x0, y1, z0);
        tri(out, x1, y0, z0, x0, y1, z0, x1, y1, z0);
        // +X
        tri(out, x1, y0, z1, x1, y0, z0, x1, y1, z0);
        tri(out, x1, y0, z1, x1, y1, z0, x1, y1, z1);
        // -X
        tri(out, x0, y0, z0, x0, y0, z1, x0, y1, z1);
        tri(out, x0, y0, z0, x0, y1, z1, x0, y1, z0);
    }

    private static void tri(List<Quake3Bsp.Tri> out,
                            float x0, float y0, float z0,
                            float x1, float y1, float z1,
                            float x2, float y2, float z2) {
        float[] xyz = {x0, y0, z0, x1, y1, z1, x2, y2, z2};
        float[] uv = new float[6];
        // rough normal
        float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
        float vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len; ny /= len; nz /= len;
        out.add(new Quake3Bsp.Tri("prop_solid", xyz, uv, new float[]{nx, ny, nz}));
    }
}
