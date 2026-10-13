package com.unrealcraft.client.quake3;

import com.unrealcraft.quake3.Quake3Bsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU-side render mesh for a Q3 map (pure Java). Positions are in blocks relative to the map anchor
 * (local = (raw - ref) / unitsPerBlock) and bucketed into 16-block cells so the renderer can cull by distance.
 */
public final class Quake3MapMesh {
    public static final int CELL = 16;

    public record Tri(float[] pos, float[] uv, float[] nrm, int r, int g, int b) {}

    public static final class Cell {
        public final List<Tri> tris = new ArrayList<>();
        public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        public float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        /** Squared distance from a point to this cell's bounding box. */
        public double distSq(double x, double y, double z) {
            double dx = Math.max(Math.max(minX - x, 0), x - maxX);
            double dy = Math.max(Math.max(minY - y, 0), y - maxY);
            double dz = Math.max(Math.max(minZ - z, 0), z - maxZ);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public final Quake3Bsp.Placement placement;
    public final List<Cell> cells;
    public final int triCount;

    private Quake3MapMesh(Quake3Bsp.Placement pl, List<Cell> cells, int n) {
        this.placement = pl; this.cells = cells; this.triCount = n;
    }

    public static Quake3MapMesh build(Quake3Bsp bsp, float unitsPerBlock) {
        List<Quake3Bsp.Tri> raw = bsp.triangles();
        return build(bsp, raw, bsp.placement(raw, unitsPerBlock));
    }

    public static Quake3MapMesh build(Quake3Bsp bsp, List<Quake3Bsp.Tri> raw, Quake3Bsp.Placement pl) {
        Map<Long, Cell> map = new LinkedHashMap<>();
        for (Quake3Bsp.Tri t : raw) {
            float[] p = new float[9];
            for (int i = 0; i < 9; i += 3) {
                p[i] = pl.lx(t.xyz()[i]); p[i + 1] = pl.ly(t.xyz()[i + 1]); p[i + 2] = pl.lz(t.xyz()[i + 2]);
            }
            int rgb = tint(t.texture());
            Tri tri = new Tri(p, t.uv(), t.nrm(), (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
            float cx = (p[0] + p[3] + p[6]) / 3f, cy = (p[1] + p[4] + p[7]) / 3f, cz = (p[2] + p[5] + p[8]) / 3f;
            long key = ((long) Math.floorDiv((int) Math.floor(cx), CELL) & 0xFFFFF) << 40
                    | ((long) Math.floorDiv((int) Math.floor(cy), CELL) & 0xFFFFF) << 20
                    | ((long) Math.floorDiv((int) Math.floor(cz), CELL) & 0xFFFFF);
            Cell c = map.computeIfAbsent(key, k -> new Cell());
            c.tris.add(tri);
            for (int i = 0; i < 9; i += 3) {
                c.minX = Math.min(c.minX, p[i]); c.maxX = Math.max(c.maxX, p[i]);
                c.minY = Math.min(c.minY, p[i + 1]); c.maxY = Math.max(c.maxY, p[i + 1]);
                c.minZ = Math.min(c.minZ, p[i + 2]); c.maxZ = Math.max(c.maxZ, p[i + 2]);
            }
        }
        return new Quake3MapMesh(pl, new ArrayList<>(map.values()), raw.size());
    }

    /** Stable pastel tint per shader name so surfaces are distinguishable until real textures are wired in. */
    private static int tint(String shader) {
        if (shader.startsWith("SURFACE_")) { // SM64 surface types get meaningful colours
            int c = surfaceTint(shader);
            if (c != 0) return c;
        }
        int h = shader.hashCode() * 0x9E3779B1;
        int r = 150 + ((h >>> 16) & 0x6F), g = 150 + ((h >>> 8) & 0x6F), b = 150 + (h & 0x6F);
        return (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
    }

    private static int surfaceTint(String n) {
        return switch (n) {
            case "SURFACE_DEFAULT", "SURFACE_NOISE_DEFAULT" -> 0xB4D2A0;
            case "SURFACE_NOT_SLIPPERY", "SURFACE_HARD_NOT_SLIPPERY" -> 0xD2B48C;
            case "SURFACE_SLIPPERY", "SURFACE_NOISE_SLIPPERY", "SURFACE_HARD_SLIPPERY", "SURFACE_NO_CAM_COL_SLIPPERY" -> 0x8CC8F0;
            case "SURFACE_VERY_SLIPPERY", "SURFACE_NOISE_VERY_SLIPPERY", "SURFACE_HARD_VERY_SLIPPERY", "SURFACE_NO_CAM_COL_VERY_SLIPPERY" -> 0x50F0FF;
            case "SURFACE_ICE" -> 0xE6F6FF;
            case "SURFACE_BURNING" -> 0xFF6A3C;
            case "SURFACE_HANGABLE" -> 0xB478F0;
            case "SURFACE_WATER", "SURFACE_FLOWING_WATER" -> 0x4678F0;
            case "SURFACE_WARP", "SURFACE_INSTANT_WARP_1B", "SURFACE_INSTANT_WARP_1C", "SURFACE_INSTANT_WARP_1D", "SURFACE_INSTANT_WARP_1E" -> 0xF050F0;
            case "SURFACE_DEEP_QUICKSAND", "SURFACE_INSTANT_QUICKSAND", "SURFACE_QUICKSAND", "SURFACE_SHALLOW_QUICKSAND" -> 0xE6D26E;
            default -> 0;
        };
    }
}
