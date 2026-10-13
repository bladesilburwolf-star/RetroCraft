package com.unrealcraft.sm64;

import com.unrealcraft.quake3.Quake3Bsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of n64decomp/sm64 src/engine/surface_load.c (classification, normals, 16x16 spatial partition,
 * floor/ceiling list ordering) and src/engine/surface_collision.c (find_floor, find_ceil,
 * find_wall_collisions). Pure Java. Static level surfaces only (no objects, no water boxes).
 */
public final class Sm64Collision {
    public static final int LEVEL_BOUNDARY_MAX = 0x2000, CELL_SIZE = 1 << 10, NUM_CELLS = 16;
    public static final float FLOOR_LOWER_LIMIT = -11000f, CELL_HEIGHT_LIMIT = 20000f;

    public static final class Surface {
        public final String type;
        public final int[] v = new int[9]; // x1,y1,z1,x2,y2,z2,x3,y3,z3 (SM64 stores integers)
        public float nx, ny, nz, originOffset;
        public int lowerY, upperY;
        public boolean xProjection;

        Surface(String type) { this.type = type; }

        public boolean isFloorLike() { return ny > 0.01f; }
    }

    public static final class WallData {
        public float x, y, z, offsetY, radius;
        public int numWalls;
        public final Surface[] walls = new Surface[4];
    }

    @SuppressWarnings("unchecked")
    private final List<Surface>[][] floors = new List[NUM_CELLS][NUM_CELLS];
    @SuppressWarnings("unchecked")
    private final List<Surface>[][] ceils = new List[NUM_CELLS][NUM_CELLS];
    @SuppressWarnings("unchecked")
    private final List<Surface>[][] walls = new List[NUM_CELLS][NUM_CELLS];
    public int surfaceCount;

    public Sm64Collision(List<Quake3Bsp.Tri> tris) {
        for (int z = 0; z < NUM_CELLS; z++) for (int x = 0; x < NUM_CELLS; x++) {
            floors[z][x] = new ArrayList<>(); ceils[z][x] = new ArrayList<>(); walls[z][x] = new ArrayList<>();
        }
        for (Quake3Bsp.Tri t : tris) add(t);
        // decomp inserts keeping lists sorted by vertex1.y: floors high->low, ceilings low->high (stable)
        for (int z = 0; z < NUM_CELLS; z++) for (int x = 0; x < NUM_CELLS; x++) {
            floors[z][x].sort((a, b) -> Integer.compare(b.v[1], a.v[1]));
            ceils[z][x].sort((a, b) -> Integer.compare(a.v[1], b.v[1]));
        }
    }

    private void add(Quake3Bsp.Tri t) {
        Surface s = new Surface(t.texture());
        float[] p = t.xyz();
        for (int i = 0; i < 9; i++) s.v[i] = (int) p[i];
        int x1 = s.v[0], y1 = s.v[1], z1 = s.v[2], x2 = s.v[3], y2 = s.v[4], z2 = s.v[5], x3 = s.v[6], y3 = s.v[7], z3 = s.v[8];
        float nx = (y2 - y1) * (z3 - z2) - (z2 - z1) * (y3 - y2);
        float ny = (z2 - z1) * (x3 - x2) - (x2 - x1) * (z3 - z2);
        float nz = (x2 - x1) * (y3 - y2) - (y2 - y1) * (x3 - x2);
        float mag = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (mag < 0.0001f) return;
        mag = 1f / mag;
        s.nx = nx * mag; s.ny = ny * mag; s.nz = nz * mag;
        s.originOffset = -(s.nx * x1 + s.ny * y1 + s.nz * z1);
        s.lowerY = Math.min(y1, Math.min(y2, y3)) - 5;
        s.upperY = Math.max(y1, Math.max(y2, y3)) + 5;

        List<Surface>[][] grid;
        if (s.ny > 0.01f) grid = floors;
        else if (s.ny < -0.01f) grid = ceils;
        else {
            grid = walls;
            if (s.nx < -0.707f || s.nx > 0.707f) s.xProjection = true;
        }
        int margin = grid == walls ? 200 : 0; // wall checks reach up to 200 units sideways
        int minX = Math.min(x1, Math.min(x2, x3)) - margin, maxX = Math.max(x1, Math.max(x2, x3)) + margin;
        int minZ = Math.min(z1, Math.min(z2, z3)) - margin, maxZ = Math.max(z1, Math.max(z2, z3)) + margin;
        int cx0 = cell(minX), cx1 = cell(maxX), cz0 = cell(minZ), cz1 = cell(maxZ);
        for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++) grid[cz][cx].add(s);
        surfaceCount++;
    }

    private static int cell(int v) {
        int c = Math.floorDiv(v + LEVEL_BOUNDARY_MAX, CELL_SIZE);
        return Math.max(0, Math.min(NUM_CELLS - 1, c));
    }

    private static boolean inBounds(int x, int z) {
        return x > -LEVEL_BOUNDARY_MAX && x < LEVEL_BOUNDARY_MAX && z > -LEVEL_BOUNDARY_MAX && z < LEVEL_BOUNDARY_MAX;
    }

    /** Result holder for floor/ceiling queries. */
    public static final class Hit {
        public Surface surface;
        public float height;
    }

    public Hit findFloor(float xPos, float yPos, float zPos) {
        Hit h = new Hit();
        h.height = FLOOR_LOWER_LIMIT;
        int x = (int) xPos, y = (int) yPos, z = (int) zPos;
        if (!inBounds(x, z)) return h;
        List<Surface> list = floors[cell(z)][cell(x)];
        for (Surface s : list) {
            if ((s.v[2] - z) * (s.v[3] - s.v[0]) - (s.v[0] - x) * (s.v[5] - s.v[2]) < 0) continue;
            if ((s.v[5] - z) * (s.v[6] - s.v[3]) - (s.v[3] - x) * (s.v[8] - s.v[5]) < 0) continue;
            if ((s.v[8] - z) * (s.v[0] - s.v[6]) - (s.v[6] - x) * (s.v[2] - s.v[8]) < 0) continue;
            if (s.type.equals("SURFACE_CAMERA_BOUNDARY")) continue;
            if (s.ny == 0f) continue;
            float height = -(x * s.nx + s.nz * z + s.originOffset) / s.ny;
            if (y - (height + -78.0f) < 0.0f) continue; // 78 unit buffer
            h.surface = s; h.height = height;
            return h;
        }
        return h;
    }

    public Hit findCeil(float xPos, float yPos, float zPos) {
        Hit h = new Hit();
        h.height = CELL_HEIGHT_LIMIT;
        int x = (int) xPos, y = (int) yPos, z = (int) zPos;
        if (!inBounds(x, z)) return h;
        List<Surface> list = ceils[cell(z)][cell(x)];
        for (Surface s : list) {
            if ((s.v[2] - z) * (s.v[3] - s.v[0]) - (s.v[0] - x) * (s.v[5] - s.v[2]) > 0) continue;
            if ((s.v[5] - z) * (s.v[6] - s.v[3]) - (s.v[3] - x) * (s.v[8] - s.v[5]) > 0) continue;
            if ((s.v[8] - z) * (s.v[0] - s.v[6]) - (s.v[6] - x) * (s.v[2] - s.v[8]) > 0) continue;
            if (s.type.equals("SURFACE_CAMERA_BOUNDARY")) continue;
            if (s.ny == 0f) continue;
            float height = -(x * s.nx + s.nz * z + s.originOffset) / s.ny;
            if (y - (height - -78.0f) > 0.0f) continue;
            h.surface = s; h.height = height;
            return h;
        }
        return h;
    }

    /** find_wall_collisions: pushes (x,z) out of walls and records up to 4 of them. */
    public int findWalls(WallData d) {
        d.numWalls = 0;
        int ix = (int) d.x, iz = (int) d.z;
        if (!inBounds(ix, iz)) return 0;
        float radius = Math.min(d.radius, 200f);
        float x = d.x, y = d.y + d.offsetY, z = d.z;
        int numCols = 0;
        for (Surface s : walls[cell(iz)][cell(ix)]) {
            if (y < s.lowerY || y > s.upperY) continue;
            float offset = s.nx * x + s.ny * y + s.nz * z + s.originOffset;
            if (offset < -radius || offset > radius) continue;
            float px = x, pz = z, w1, w2, w3, y1, y2, y3;
            if (s.xProjection) {
                w1 = -s.v[2]; w2 = -s.v[5]; w3 = -s.v[8];
                y1 = s.v[1]; y2 = s.v[4]; y3 = s.v[7];
                if (s.nx > 0f) {
                    if ((y1 - y) * (w2 - w1) - (w1 - -pz) * (y2 - y1) > 0f) continue;
                    if ((y2 - y) * (w3 - w2) - (w2 - -pz) * (y3 - y2) > 0f) continue;
                    if ((y3 - y) * (w1 - w3) - (w3 - -pz) * (y1 - y3) > 0f) continue;
                } else {
                    if ((y1 - y) * (w2 - w1) - (w1 - -pz) * (y2 - y1) < 0f) continue;
                    if ((y2 - y) * (w3 - w2) - (w2 - -pz) * (y3 - y2) < 0f) continue;
                    if ((y3 - y) * (w1 - w3) - (w3 - -pz) * (y1 - y3) < 0f) continue;
                }
            } else {
                w1 = s.v[0]; w2 = s.v[3]; w3 = s.v[6];
                y1 = s.v[1]; y2 = s.v[4]; y3 = s.v[7];
                if (s.nz > 0f) {
                    if ((y1 - y) * (w2 - w1) - (w1 - px) * (y2 - y1) > 0f) continue;
                    if ((y2 - y) * (w3 - w2) - (w2 - px) * (y3 - y2) > 0f) continue;
                    if ((y3 - y) * (w1 - w3) - (w3 - px) * (y1 - y3) > 0f) continue;
                } else {
                    if ((y1 - y) * (w2 - w1) - (w1 - px) * (y2 - y1) < 0f) continue;
                    if ((y2 - y) * (w3 - w2) - (w2 - px) * (y3 - y2) < 0f) continue;
                    if ((y3 - y) * (w1 - w3) - (w3 - px) * (y1 - y3) < 0f) continue;
                }
            }
            if (s.type.equals("SURFACE_CAMERA_BOUNDARY")) continue;
            d.x += s.nx * (radius - offset);
            d.z += s.nz * (radius - offset);
            if (d.numWalls < 4) d.walls[d.numWalls++] = s;
            numCols++;
        }
        return numCols;
    }
}
