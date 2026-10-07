package com.unrealcraft.client;

/** Triangle soup: 3 vertices per triangle, already fitted into the 0..1 item model space. */
public final class GlbMesh {
    /** xyz per vertex, in block units (0..1 is the item cube). */
    public final float[] pos;
    /** unit normal xyz per vertex. */
    public final float[] nrm;
    /** uv per vertex in 0..1 (glTF convention: v grows downward, same as Minecraft). */
    public final float[] uv;

    /** Per triangle: index into {@link #images} of its base colour texture, or -1 if it has none. */
    public int[] triImage = new int[0];
    /** Embedded images (PNG/JPEG bytes) in glTF order; an entry is null if it could not be read. */
    public java.util.List<byte[]> images = java.util.List.of();
    /** Parallel to images: basename from glTF uri/name (e.g. tx_wood_brown_01). */
    public java.util.List<String> imageNames = java.util.List.of();

    GlbMesh(float[] pos, float[] nrm, float[] uv) {
        this.pos = pos;
        this.nrm = nrm;
        this.uv = uv;
    }

    public int vertexCount() { return pos.length / 3; }
    public int triangleCount() { return vertexCount() / 3; }

    private void rotate(int axis, double deg) {
        if (deg == 0) return;
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        for (float[] a : new float[][] {pos, nrm}) {
            for (int v = 0; v < a.length; v += 3) {
                float x = a[v], y = a[v + 1], z = a[v + 2];
                switch (axis) {
                    case 0 -> { a[v + 1] = (float) (y * c - z * s); a[v + 2] = (float) (y * s + z * c); }
                    case 1 -> { a[v] = (float) (x * c + z * s); a[v + 2] = (float) (-x * s + z * c); }
                    default -> { a[v] = (float) (x * c - y * s); a[v + 1] = (float) (x * s + y * c); }
                }
            }
        }
    }

    /** returns {minX,minY,minZ,maxX,maxY,maxZ}. */
    private float[] bounds() {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = 0; v < pos.length; v += 3) {
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], pos[v + k]);
                b[k + 3] = Math.max(b[k + 3], pos[v + k]);
            }
        }
        return b;
    }

    private static int longestAxis(float[] b) {
        float ex = b[3] - b[0], ey = b[4] - b[1], ez = b[5] - b[2];
        return (ex >= ey && ex >= ez) ? 0 : (ey >= ez ? 1 : 2);
    }

    /** Orients, centres and scales the raw glTF geometry so it fits an item model. */
    void fit(GlbOptions o) {
        if (o.raw) { // keep the model's own origin and orientation, only change units
            for (int v = 0; v < pos.length; v++) pos[v] *= o.scale;
            return;
        }
        rotate(0, o.rx);
        rotate(1, o.ry);
        rotate(2, o.rz);
        if (o.autoOrient) {
            int ax = longestAxis(bounds());
            if (ax == 0) rotate(2, 90);       // +X -> +Y
            else if (ax == 2) rotate(0, -90); // +Z -> +Y
        }
        if (o.flip) rotate(o.entity ? 1 : 2, 180); // entities: turn around; items: upside down

        if (o.entity) {
            float[] e = bounds();
            float len = Math.max(e[longestAxis(e) + 3] - e[longestAxis(e)], 1e-6f);
            float k = o.size * o.scale / len;
            float mx = (e[0] + e[3]) / 2, mz = (e[2] + e[5]) / 2;
            for (int v = 0; v < pos.length; v += 3) {
                pos[v] = (pos[v] - mx) * k + o.ox;
                pos[v + 1] = (pos[v + 1] - e[1]) * k + o.oy;
                pos[v + 2] = (pos[v + 2] - mz) * k + o.oz;
            }
            return;
        }

        float[] b = bounds();
        int ax = longestAxis(b);
        float longest = Math.max(b[ax + 3] - b[ax], 1e-6f);
        float s = (o.diagonal ? 1.25f : 1.0f) * o.scale / longest;
        float cx = (b[0] + b[3]) / 2, cy = (b[1] + b[4]) / 2, cz = (b[2] + b[5]) / 2;
        for (int v = 0; v < pos.length; v += 3) {
            pos[v] = (pos[v] - cx) * s;
            pos[v + 1] = (pos[v + 1] - cy) * s;
            pos[v + 2] = (pos[v + 2] - cz) * s;
        }
        if (o.diagonal) rotate(2, -45); // blade up -> up-right, like vanilla sword sprites
        for (int v = 0; v < pos.length; v += 3) {
            pos[v] += 0.5f + o.ox;
            pos[v + 1] += 0.5f + o.oy;
            pos[v + 2] += 0.5f + o.oz;
        }
    }
}
