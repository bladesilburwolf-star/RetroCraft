package com.unrealcraft.sm64;

import com.unrealcraft.quake3.Quake3Bsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads level collision straight from the n64decomp/sm64 source tree
 * (levels/&lt;level&gt;/areas/&lt;n&gt;/collision.inc.c) and Mario's start from levels/&lt;level&gt;/script.c.
 * Pure Java. Triangles reuse {@link Quake3Bsp.Tri}; the "texture" field carries the SURFACE_* name
 * so the renderer can tint by surface type. SM64 and Minecraft are both right-handed Y-up, so no axis swap.
 */
public final class Sm64Level {
    public record Result(String level, int area, List<Quake3Bsp.Tri> tris, float[] marioStart) {}

    /** Camera-only / kill / separator surfaces that are not walkable level geometry. */
    private static final Set<String> SKIP = Set.of("SURFACE_DEATH_PLANE", "SURFACE_CAMERA_BOUNDARY", "SURFACE_INTANGIBLE");

    private static final Pattern TOKEN = Pattern.compile(
            "COL_VERTEX\\(\\s*(-?\\w+)\\s*,\\s*(-?\\w+)\\s*,\\s*(-?\\w+)\\s*\\)"
          + "|COL_TRI_INIT\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)"
          + "|COL_TRI_SPECIAL\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*[^)]*\\)"
          + "|COL_TRI\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)"
          + "|COL_SPECIAL_INIT|COL_WATER_BOX_INIT|COL_END");
    private static final Pattern MARIO_POS = Pattern.compile(
            "MARIO_POS\\(\\s*(\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\)");

    private Sm64Level() {}

    public static Result load(Path levelDir, int area, float unitsPerBlock) throws IOException {
        Path col = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("collision.inc.c");
        if (!Files.isRegularFile(col)) throw new IOException("No collision data: " + col);
        String src = stripComments(Files.readString(col));

        List<float[]> verts = new ArrayList<>();
        List<Quake3Bsp.Tri> tris = new ArrayList<>();
        String surface = "SURFACE_DEFAULT";
        Matcher m = TOKEN.matcher(src);
        while (m.find()) {
            String t = m.group();
            if (t.startsWith("COL_VERTEX")) {
                verts.add(new float[]{num(m.group(1)), num(m.group(2)), num(m.group(3))});
            } else if (t.startsWith("COL_TRI_INIT")) {
                surface = m.group(4);
            } else if (t.startsWith("COL_TRI_SPECIAL")) {
                add(tris, verts, surface, m.group(6), m.group(7), m.group(8), unitsPerBlock);
            } else if (t.startsWith("COL_TRI(")) {
                add(tris, verts, surface, m.group(9), m.group(10), m.group(11), unitsPerBlock);
            } else if (t.startsWith("COL_SPECIAL_INIT") || t.startsWith("COL_WATER_BOX_INIT") || t.equals("COL_END")) {
                break; // object / water-box tables follow; not level geometry
            }
        }
        return new Result(levelDir.getFileName().toString(), area, tris, marioStart(levelDir, area));
    }

    /** Mario's spawn {x,y,z,yaw} in SM64 units for this area, or null. */
    private static float[] marioStart(Path levelDir, int area) {
        try {
            Path script = levelDir.resolve("script.c");
            if (!Files.isRegularFile(script)) return null;
            Matcher m = MARIO_POS.matcher(stripComments(Files.readString(script)));
            float[] first = null;
            while (m.find()) {
                float[] r = {Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)),
                        Float.parseFloat(m.group(5)), Float.parseFloat(m.group(2))};
                if (Integer.parseInt(m.group(1)) == area) return r;
                if (first == null) first = r;
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    /** Anchor the level so Mario's start (or the level centre) is the origin, lowest point at local y=0. */
    public static Quake3Bsp.Placement placement(Result r, float unitsPerBlock) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (var t : r.tris()) for (int i = 0; i < 9; i += 3) {
            minX = Math.min(minX, t.xyz()[i]); maxX = Math.max(maxX, t.xyz()[i]);
            minY = Math.min(minY, t.xyz()[i + 1]);
            minZ = Math.min(minZ, t.xyz()[i + 2]); maxZ = Math.max(maxZ, t.xyz()[i + 2]);
        }
        float s = Math.max(unitsPerBlock, .001f);
        float[] ms = r.marioStart();
        if (ms != null) return new Quake3Bsp.Placement(ms[0], minY, ms[2], s, (ms[1] - minY) / s);
        return new Quake3Bsp.Placement((minX + maxX) * .5f, minY, (minZ + maxZ) * .5f, s, Float.NaN);
    }

    private static void add(List<Quake3Bsp.Tri> out, List<float[]> v, String surface, String a, String b, String c, float s) {
        if (SKIP.contains(surface)) return;
        int ia = (int) num(a), ib = (int) num(b), ic = (int) num(c);
        if (ia < 0 || ib < 0 || ic < 0 || ia >= v.size() || ib >= v.size() || ic >= v.size()) return;
        float[] A = v.get(ia), B = v.get(ib), C = v.get(ic);
        float ux = B[0] - A[0], uy = B[1] - A[1], uz = B[2] - A[2], vx = C[0] - A[0], vy = C[1] - A[1], vz = C[2] - A[2];
        // SM64 triangles are CCW seen from the surface side: normal = (b-a) x (c-a)
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len; ny /= len; nz /= len;
        float[] uv = new float[6];
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float[][] P = {A, B, C};
        for (int i = 0; i < 3; i++) { // world-aligned UVs: one stone tile per block
            float[] p = P[i];
            if (ay >= ax && ay >= az) { uv[i * 2] = p[0] / s; uv[i * 2 + 1] = p[2] / s; }
            else if (ax >= az)        { uv[i * 2] = p[2] / s; uv[i * 2 + 1] = p[1] / s; }
            else                      { uv[i * 2] = p[0] / s; uv[i * 2 + 1] = p[1] / s; }
        }
        out.add(new Quake3Bsp.Tri(surface, new float[]{A[0], A[1], A[2], B[0], B[1], B[2], C[0], C[1], C[2]}, uv, new float[]{nx, ny, nz}));
    }

    private static float num(String s) {
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
        if (s.startsWith("-0x")) return -Integer.parseInt(s.substring(3), 16);
        return Integer.parseInt(s);
    }

    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
