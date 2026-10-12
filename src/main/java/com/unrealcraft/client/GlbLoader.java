package com.unrealcraft.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal binary glTF (.glb) reader: triangle meshes, node transforms, POSITION / NORMAL / TEXCOORD_0.
 * Skins, animations, morph targets and materials are ignored.
 */
public final class GlbLoader {
    private GlbLoader() {}

    private static final int MAGIC = 0x46546C67, CHUNK_JSON = 0x4E4F534A, CHUNK_BIN = 0x004E4942;

    private static final class Floats {
        float[] a = new float[1024];
        int n;
        void add(float v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        float[] toArray() { return Arrays.copyOf(a, n); }
    }

    private static final class Ints {
        int[] a = new int[256];
        int n;
        void add(int v) { if (n == a.length) a = Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] toArray() { return Arrays.copyOf(a, n); }
    }

    private record Accessor(float[] data, int comps) {}

    private static final class Ctx {
        Map<String, Object> root;
        ByteBuffer bin;
        final Floats pos = new Floats(), nrm = new Floats(), uv = new Floats();
        final Ints triImage = new Ints();
    }

    public static GlbMesh load(byte[] data, GlbOptions opt) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 20 || b.getInt() != MAGIC) throw new IOException("Not a .glb file");
        b.getInt(); // version
        b.getInt(); // total length
        String json = null;
        ByteBuffer bin = null;
        while (b.remaining() >= 8) {
            int len = b.getInt(), type = b.getInt(), start = b.position();
            if (type == CHUNK_JSON && json == null) json = new String(data, start, len, StandardCharsets.UTF_8);
            else if (type == CHUNK_BIN && bin == null) bin = ByteBuffer.wrap(data, start, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            b.position(start + len);
        }
        if (json == null) throw new IOException("glb has no JSON chunk");

        Ctx c = new Ctx();
        c.root = map(MiniJson.parse(json));
        c.bin = bin;

        List<Object> scenes = list(c.root.get("scenes"));
        List<Object> nodes = list(c.root.get("nodes"));
        if (nodes == null) throw new IOException("glb has no nodes");
        List<Object> roots = new ArrayList<>();
        if (scenes != null && !scenes.isEmpty()) {
            int si = (int) num(c.root, "scene", 0);
            roots = list(map(scenes.get(Math.min(si, scenes.size() - 1))).get("nodes"));
        }
        float[] identity = identity();
        if (roots == null || roots.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) visit(c, i, identity, 0);
        } else {
            for (Object r : roots) visit(c, ((Number) r).intValue(), identity, 0);
        }
        if (c.pos.n == 0) throw new IOException("glb contains no triangle geometry");
        GlbMesh mesh = new GlbMesh(c.pos.toArray(), c.nrm.toArray(), c.uv.toArray());
        mesh.triImage = c.triImage.toArray();
        Images imgs = images(c);
        mesh.images = imgs.bytes;
        mesh.imageNames = imgs.names;
        // If glb has materials but no/empty images (NifTools export without textures),
        // invent image slots from material names so ExternalTextures can resolve them.
        mesh.fit(opt == null ? GlbOptions.defaults() : opt);
        return mesh;
    }

    // ---- scene graph -------------------------------------------------------------------------

    private static void visit(Ctx c, int nodeIndex, float[] parent, int depth) throws IOException {
        if (depth > 64) throw new IOException("glb node hierarchy too deep");
        Map<String, Object> node = map(list(c.root.get("nodes")).get(nodeIndex));
        float[] world = mul(parent, localMatrix(node));
        if (node.get("mesh") != null) mesh(c, (int) num(node, "mesh", 0), world);
        List<Object> kids = list(node.get("children"));
        if (kids != null) for (Object k : kids) visit(c, ((Number) k).intValue(), world, depth + 1);
    }

    private static float[] localMatrix(Map<String, Object> node) {
        List<Object> m = list(node.get("matrix"));
        if (m != null && m.size() == 16) {
            float[] out = new float[16];
            for (int i = 0; i < 16; i++) out[i] = ((Number) m.get(i)).floatValue();
            return out;
        }
        float[] t = vec(node.get("translation"), 3, 0, 0, 0);
        float[] r = vec(node.get("rotation"), 4, 0, 0, 0, 1);
        float[] s = vec(node.get("scale"), 3, 1, 1, 1);
        float x = r[0], y = r[1], z = r[2], w = r[3];
        float[] o = new float[16];
        o[0] = (1 - 2 * (y * y + z * z)) * s[0]; o[1] = 2 * (x * y + z * w) * s[0]; o[2] = 2 * (x * z - y * w) * s[0];
        o[4] = 2 * (x * y - z * w) * s[1]; o[5] = (1 - 2 * (x * x + z * z)) * s[1]; o[6] = 2 * (y * z + x * w) * s[1];
        o[8] = 2 * (x * z + y * w) * s[2]; o[9] = 2 * (y * z - x * w) * s[2]; o[10] = (1 - 2 * (x * x + y * y)) * s[2];
        o[12] = t[0]; o[13] = t[1]; o[14] = t[2]; o[15] = 1;
        return o;
    }

    private static void mesh(Ctx c, int meshIndex, float[] m) throws IOException {
        Map<String, Object> mesh = map(list(c.root.get("meshes")).get(meshIndex));
        List<Object> prims = list(mesh.get("primitives"));
        if (prims == null) return;
        // negative determinant (mirroring) flips winding
        boolean flip = det3(m) < 0;
        for (Object po : prims) {
            Map<String, Object> prim = map(po);
            if ((int) num(prim, "mode", 4) != 4) continue; // triangles only
            Map<String, Object> attrs = map(prim.get("attributes"));
            if (attrs == null || attrs.get("POSITION") == null) continue;
            Accessor P = accessor(c, ((Number) attrs.get("POSITION")).intValue());
            Accessor N = attrs.get("NORMAL") != null ? accessor(c, ((Number) attrs.get("NORMAL")).intValue()) : null;
            Accessor U = attrs.get("TEXCOORD_0") != null ? accessor(c, ((Number) attrs.get("TEXCOORD_0")).intValue()) : null;
            int image = imageOfMaterial(c, prim.get("material"));
            int vertexCount = P.data.length / 3;
            int[] idx;
            if (prim.get("indices") != null) {
                float[] f = accessor(c, ((Number) prim.get("indices")).intValue()).data;
                idx = new int[f.length];
                for (int i = 0; i < f.length; i++) idx[i] = (int) f[i];
            } else {
                idx = new int[vertexCount];
                for (int i = 0; i < idx.length; i++) idx[i] = i;
            }
            for (int t = 0; t + 2 < idx.length; t += 3) {
                int[] tri = flip ? new int[] {idx[t], idx[t + 2], idx[t + 1]} : new int[] {idx[t], idx[t + 1], idx[t + 2]};
                float[][] p = new float[3][];
                for (int k = 0; k < 3; k++) {
                    int v = tri[k];
                    if (v < 0 || v >= vertexCount) throw new IOException("glb index out of range");
                    p[k] = transformPoint(m, P.data[v * 3], P.data[v * 3 + 1], P.data[v * 3 + 2]);
                }
                c.triImage.add(image);
                float[] face = cross(p[0], p[1], p[2]);
                for (int k = 0; k < 3; k++) {
                    int v = tri[k];
                    c.pos.add(p[k][0]); c.pos.add(p[k][1]); c.pos.add(p[k][2]);
                    float[] n = N != null
                            ? normalize(transformDir(m, N.data[v * 3], N.data[v * 3 + 1], N.data[v * 3 + 2]), false)
                            : face;
                    c.nrm.add(n[0]); c.nrm.add(n[1]); c.nrm.add(n[2]);
                    if (U != null) { c.uv.add(U.data[v * 2]); c.uv.add(U.data[v * 2 + 1]); }
                    else { c.uv.add(0); c.uv.add(0); }
                }
            }
        }
    }

    // ---- materials and images ----------------------------------------------------------------

    private static int imageOfMaterial(Ctx c, Object materialIndex) {
        if (!(materialIndex instanceof Number n)) return -1;
        List<Object> mats = list(c.root.get("materials"));
        if (mats == null || n.intValue() < 0 || n.intValue() >= mats.size()) return -1;
        Map<String, Object> mat = map(mats.get(n.intValue()));
        Map<String, Object> pbr = map(mat.get("pbrMetallicRoughness"));
        if (pbr == null) return -1;
        Map<String, Object> base = map(pbr.get("baseColorTexture"));
        List<Object> textures = list(c.root.get("textures"));
        if (base == null || textures == null) return -1;
        int ti = (int) num(base, "index", -1);
        if (ti < 0 || ti >= textures.size()) return -1;
        return (int) num(map(textures.get(ti)), "source", -1);
    }


    private static boolean allNull(List<byte[]> list) {
        for (byte[] b : list) if (b != null) return false;
        return true;
    }

    /** Build imageNames + triImage from materials[].name / baseColorTexture when no embedded image bytes. */
    private static void synthesizeFromMaterials(Ctx c, GlbMesh mesh) {
        List<Object> mats = list(c.root.get("materials"));
        if (mats == null || mats.isEmpty()) return;
        List<String> names = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        Map<Integer, Integer> matToImg = new HashMap<>();
        for (int mi = 0; mi < mats.size(); mi++) {
            Map<String, Object> mat = map(mats.get(mi));
            String name = null;
            if (mat.get("name") instanceof String ns && !ns.isBlank()) name = stripExt(uriBasename(ns));
            // also try extras / common NifTools fields
            if (name == null) name = "mat_" + mi;
            matToImg.put(mi, names.size());
            names.add(name);
            bytes.add(null); // external resolver will fill
        }
        // Rebuild triImage by re-walking primitives — simpler: if triImage all -1, assign by sequential prim order
        // We already recorded triImage during mesh(); if all -1, paint everything with image 0
        boolean any = false;
        for (int v : mesh.triImage) if (v >= 0) { any = true; break; }
        if (!any && !names.isEmpty()) {
            int[] ti = new int[mesh.triangleCount()];
            // leave 0 = first material; better than grey stone
            java.util.Arrays.fill(ti, 0);
            mesh.triImage = ti;
        } else {
            // remap material indices? imageOfMaterial already stored image source index;
            // when images were empty those were -1. Can't recover per-tri without re-parse.
            // Assign first available name to all for now if still all -1.
        }
        mesh.imageNames = names;
        mesh.images = bytes;
        Unrealcraft_log("Synthesized " + names.size() + " material texture names for external load: " + names);
    }

    // Avoid depending on Unrealcraft from glb package if circular — use System.out for one line
    private static void Unrealcraft_log(String s) {
        System.out.println("[minewind] " + s);
    }

    private static final class Images {
        final List<byte[]> bytes;
        final List<String> names;
        Images(List<byte[]> bytes, List<String> names) { this.bytes = bytes; this.names = names; }
    }

    private static Images images(Ctx c) {
        List<Object> imgs = list(c.root.get("images"));
        if (imgs == null || imgs.isEmpty()) return new Images(List.of(), List.of());
        List<byte[]> out = new ArrayList<>(imgs.size());
        List<String> names = new ArrayList<>(imgs.size());
        for (Object o : imgs) {
            Map<String, Object> img = map(o);
            byte[] data = null;
            String name = null;
            try {
                if (img.get("name") instanceof String ns && !ns.isBlank()) name = stripExt(ns);
                if (img.get("uri") instanceof String u) {
                    if (name == null) name = stripExt(uriBasename(u));
                    if (u.startsWith("data:")) {
                        data = java.util.Base64.getDecoder().decode(u.substring(u.indexOf(',') + 1));
                    }
                }
                if (data == null && img.get("bufferView") != null && c.bin != null) {
                    Map<String, Object> view = map(list(c.root.get("bufferViews")).get((int) num(img, "bufferView", 0)));
                    int off = (int) num(view, "byteOffset", 0);
                    int len = (int) num(view, "byteLength", 0);
                    data = new byte[len];
                    ByteBuffer slice = c.bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    slice.position(off);
                    slice.get(data);
                }
            } catch (RuntimeException e) {
                data = null;
            }
            out.add(data);
            names.add(name);
        }
        return new Images(out, names);
    }

    private static String uriBasename(String u) {
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int slash = Math.max(u.lastIndexOf('/'), u.lastIndexOf('\\'));
        return slash >= 0 ? u.substring(slash + 1) : u;
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }


    // ---- accessors ---------------------------------------------------------------------------

    private static Accessor accessor(Ctx c, int index) throws IOException {
        Map<String, Object> acc = map(list(c.root.get("accessors")).get(index));
        int count = (int) num(acc, "count", 0);
        int comps = switch ((String) acc.get("type")) {
            case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16;
            default -> throw new IOException("Unsupported accessor type " + acc.get("type"));
        };
        float[] out = new float[count * comps];
        if (acc.get("bufferView") == null) return new Accessor(out, comps);
        if (c.bin == null) throw new IOException("glb has no BIN chunk");
        Map<String, Object> view = map(list(c.root.get("bufferViews")).get((int) num(acc, "bufferView", 0)));
        int type = (int) num(acc, "componentType", 5126);
        boolean norm = Boolean.TRUE.equals(acc.get("normalized"));
        int size = switch (type) { case 5120, 5121 -> 1; case 5122, 5123 -> 2; case 5125, 5126 -> 4; default -> throw new IOException("Bad componentType " + type); };
        int base = (int) num(view, "byteOffset", 0) + (int) num(acc, "byteOffset", 0);
        int stride = (int) num(view, "byteStride", 0);
        if (stride == 0) stride = size * comps;
        for (int e = 0; e < count; e++) {
            for (int k = 0; k < comps; k++) {
                int p = base + e * stride + k * size;
                if (p < 0 || p + size > c.bin.limit()) throw new IOException("glb accessor reads past end of BIN chunk");
                out[e * comps + k] = switch (type) {
                    case 5126 -> c.bin.getFloat(p);
                    case 5120 -> norm ? Math.max(c.bin.get(p) / 127f, -1f) : c.bin.get(p);
                    case 5121 -> norm ? (c.bin.get(p) & 0xFF) / 255f : (c.bin.get(p) & 0xFF);
                    case 5122 -> norm ? Math.max(c.bin.getShort(p) / 32767f, -1f) : c.bin.getShort(p);
                    case 5123 -> norm ? (c.bin.getShort(p) & 0xFFFF) / 65535f : (c.bin.getShort(p) & 0xFFFF);
                    default -> (float) (c.bin.getInt(p) & 0xFFFFFFFFL);
                };
            }
        }
        return new Accessor(out, comps);
    }

    // ---- math (column-major 4x4, as in glTF) ---------------------------------------------------

    private static float[] identity() { float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }

    private static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int col = 0; col < 4; col++)
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[col * 4 + k];
                o[col * 4 + row] = s;
            }
        return o;
    }

    private static float[] transformPoint(float[] m, float x, float y, float z) {
        return new float[] {
                m[0] * x + m[4] * y + m[8] * z + m[12],
                m[1] * x + m[5] * y + m[9] * z + m[13],
                m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    private static float[] transformDir(float[] m, float x, float y, float z) {
        return new float[] {m[0] * x + m[4] * y + m[8] * z, m[1] * x + m[5] * y + m[9] * z, m[2] * x + m[6] * y + m[10] * z};
    }

    private static float det3(float[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    private static float[] cross(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        return normalize(new float[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx}, false);
    }

    private static float[] normalize(float[] n, boolean negate) {
        float l = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (l < 1e-9f) return new float[] {0, 1, 0};
        float s = (negate ? -1f : 1f) / l;
        return new float[] {n[0] * s, n[1] * s, n[2] * s};
    }

    // ---- JSON helpers ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return (List<Object>) o; }

    private static double num(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static float[] vec(Object o, int n, float... def) {
        float[] out = def.clone();
        if (o instanceof List<?> l && l.size() >= n) for (int i = 0; i < n; i++) out[i] = ((Number) l.get(i)).floatValue();
        return out;
    }
}
