package com.unrealcraft.sm64;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader + interpreter for the N64 F3D display lists found in the n64decomp/sm64 C source
 * (model.inc.c files). Pure Java. Handles the subset SM64 level and actor models actually use:
 * vertex loads, triangles, nested display lists, lights, geometry mode (lighting), texture images.
 */
public final class F3d {
    public record Vtx(float x, float y, float z, float u, float v, int r, int g, int b, int a) {}
    public record Light(int ar, int ag, int ab, int dr, int dg, int db, int dx, int dy, int dz) {}
    /** Triangle in source units. uv in texels (vertex UV / 32). Colours are final (lighting already applied). */
    public record Tri(String texture, float[] xyz, float[] uv, int[] rgb) {}

    public final Map<String, List<Vtx>> vtx = new HashMap<>();
    public final Map<String, List<String[]>> dls = new HashMap<>();
    public final Map<String, Light> lights = new HashMap<>();
    /** texture symbol -> extracted PNG path relative to the sm64 root (e.g. levels/bob/0.rgba16.png) */
    public final Map<String, String> textures = new HashMap<>();
    public final Set<String> unresolved = new TreeSet<>();

    private static final Pattern VTX_DEF = Pattern.compile("Vtx\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{");
    private static final Pattern GFX_DEF = Pattern.compile("Gfx\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{");
    private static final Pattern LIGHT_DEF = Pattern.compile("Lights1\\s+(\\w+)\\s*=\\s*gdSPDefLights1\\(([^)]*)\\)");
    private static final Pattern TEX_DEF = Pattern.compile("Texture\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{\\s*#include\\s+\"([^\"]+?)\\.inc\\.c\"");
    private static final Pattern VTX_ROW = Pattern.compile(
            "\\{\\{\\{\\s*(-?\\w+)\\s*,\\s*(-?\\w+)\\s*,\\s*(-?\\w+)\\s*}\\s*,\\s*\\w+\\s*,\\s*\\{\\s*(-?\\w+)\\s*,\\s*(-?\\w+)\\s*}\\s*,\\s*\\{\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)\\s*}\\s*}\\s*}");
    private static final Pattern CMD = Pattern.compile("\\b(gs\\w+)\\s*\\(");

    // ------------------------------------------------------------------------------ parsing
    public void parse(String src) {
        src = src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        Matcher m = TEX_DEF.matcher(src);
        while (m.find()) textures.put(m.group(1), m.group(2) + ".png");
        m = LIGHT_DEF.matcher(src);
        while (m.find()) {
            String[] a = m.group(2).split(",");
            if (a.length >= 9) lights.put(m.group(1), new Light(n(a[0]), n(a[1]), n(a[2]), n(a[3]), n(a[4]), n(a[5]),
                    (byte) n(a[6]), (byte) n(a[7]), (byte) n(a[8])));
        }
        m = VTX_DEF.matcher(src);
        while (m.find()) {
            String body = block(src, m.end());
            List<Vtx> list = new ArrayList<>();
            Matcher r = VTX_ROW.matcher(body);
            while (r.find()) {
                list.add(new Vtx(n(r.group(1)), n(r.group(2)), n(r.group(3)), n(r.group(4)), n(r.group(5)),
                        n(r.group(6)) & 255, n(r.group(7)) & 255, n(r.group(8)) & 255, n(r.group(9)) & 255));
            }
            vtx.put(m.group(1), list);
        }
        m = GFX_DEF.matcher(src);
        while (m.find()) {
            String body = block(src, m.end());
            List<String[]> cmds = new ArrayList<>();
            Matcher c = CMD.matcher(body);
            int pos = 0;
            while (c.find(pos)) {
                int open = c.end() - 1, close = matching(body, open);
                if (close < 0) break;
                cmds.add(split(c.group(1), body.substring(open + 1, close)));
                pos = close + 1;
            }
            dls.put(m.group(1), cmds);
        }
    }

    private static int n(String s) { return Integer.decode(s.trim()); }

    /** Text between the opening brace (already consumed at 'from') and its matching close. */
    private static String block(String s, int from) {
        int depth = 1, i = from;
        while (i < s.length() && depth > 0) {
            char ch = s.charAt(i++);
            if (ch == '{') depth++; else if (ch == '}') depth--;
        }
        return s.substring(from, Math.max(from, i - 1));
    }

    private static int matching(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static String[] split(String name, String args) {
        List<String> out = new ArrayList<>();
        out.add(name);
        int depth = 0, start = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (ch == '(') depth++; else if (ch == ')') depth--;
            else if (ch == ',' && depth == 0) { out.add(args.substring(start, i).trim()); start = i + 1; }
        }
        String last = args.substring(start).trim();
        if (!last.isEmpty()) out.add(last);
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------------------ interpreter
    private static final class State {
        final Vtx[] buf = new Vtx[64];
        final int[][] col = new int[64][3];
        boolean lighting = true;
        boolean texturing;
        String texture;
        Light ambientL, diffuseL;
    }

    /** Execute a display list and collect its triangles. */
    public List<Tri> run(String root) {
        List<Tri> out = new ArrayList<>();
        exec(root, new State(), out, 0);
        return out;
    }

    /** Execute several display lists in sequence with fresh graphics state each (like separate geo nodes). */
    public List<Tri> runAll(Collection<String> roots) {
        List<Tri> out = new ArrayList<>();
        for (String r : roots) exec(r, new State(), out, 0);
        return out;
    }

    private boolean exec(String name, State st, List<Tri> out, int depth) {
        List<String[]> cmds = dls.get(name);
        if (cmds == null) { unresolved.add(name); return false; }
        if (depth > 32) return false;
        for (String[] c : cmds) {
            switch (c[0]) {
                case "gsSPEndDisplayList" -> { return true; }
                case "gsSPDisplayList" -> exec(c[1].trim(), st, out, depth + 1);
                case "gsSPBranchList" -> { exec(c[1].trim(), st, out, depth + 1); return true; }
                case "gsSPSetGeometryMode" -> { if (c[1].contains("G_LIGHTING")) st.lighting = true; }
                case "gsSPClearGeometryMode" -> { if (c[1].contains("G_LIGHTING")) st.lighting = false; }
                case "gsSPTexture" -> st.texturing = c[c.length - 1].contains("G_ON");
                case "gsDPSetTextureImage" -> st.texture = textureOf(c[c.length - 1]);
                case "gsDPLoadTextureBlock", "gsDPLoadTextureBlock_4b" -> st.texture = textureOf(c[1]);
                case "gsSPLight" -> {
                    Matcher m = Pattern.compile("&?(\\w+)\\.([la])").matcher(c[1]);
                    if (m.find()) {
                        Light l = lights.get(m.group(1));
                        if (l == null) unresolved.add(m.group(1));
                        else if (m.group(2).equals("l")) st.diffuseL = l; else st.ambientL = l;
                    }
                }
                case "gsSPVertex" -> loadVertices(c, st);
                case "gsSP1Triangle" -> tri(st, out, i(c[1]), i(c[2]), i(c[3]));
                case "gsSP2Triangles" -> {
                    tri(st, out, i(c[1]), i(c[2]), i(c[3]));
                    tri(st, out, i(c[5]), i(c[6]), i(c[7]));
                }
                default -> { }
            }
        }
        return false;
    }

    private String textureOf(String sym) {
        sym = sym.trim().replaceAll("^&", "");
        String p = textures.get(sym);
        if (p == null) unresolved.add(sym);
        return p;
    }

    private static int i(String s) { return Integer.decode(s.trim()); }

    private void loadVertices(String[] c, State st) {
        Matcher m = Pattern.compile("^&?(\\w+)(?:\\s*\\+\\s*(\\d+))?$").matcher(c[1].trim());
        if (!m.find()) return;
        List<Vtx> list = vtx.get(m.group(1));
        if (list == null) { unresolved.add(m.group(1)); return; }
        int off = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int count = i(c[2]), dst = i(c[3]);
        for (int k = 0; k < count && off + k < list.size() && dst + k < st.buf.length; k++) {
            Vtx v = list.get(off + k);
            st.buf[dst + k] = v;
            st.col[dst + k] = shade(v, st);
        }
    }

    /** Vertex colour: lit vertices store a normal in rgb; otherwise rgb is the colour. */
    private int[] shade(Vtx v, State st) {
        if (!st.lighting || st.diffuseL == null) return new int[]{v.r, v.g, v.b};
        float nx = (byte) v.r / 127f, ny = (byte) v.g / 127f, nz = (byte) v.b / 127f;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl > 0f) { nx /= nl; ny /= nl; nz /= nl; }
        Light d = st.diffuseL, a = st.ambientL != null ? st.ambientL : st.diffuseL;
        float dl = (float) Math.sqrt((float) d.dx() * d.dx() + (float) d.dy() * d.dy() + (float) d.dz() * d.dz());
        float dot = dl > 0f ? Math.max(0f, (nx * d.dx() + ny * d.dy() + nz * d.dz()) / dl) : 0f;
        int r = clamp(a.ar() + d.dr() * dot), g = clamp(a.ag() + d.dg() * dot), b = clamp(a.ab() + d.db() * dot);
        return new int[]{r, g, b};
    }

    private static int clamp(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    private void tri(State st, List<Tri> out, int a, int b, int c) {
        if (a < 0 || b < 0 || c < 0 || a >= 64 || b >= 64 || c >= 64) return;
        Vtx A = st.buf[a], B = st.buf[b], C = st.buf[c];
        if (A == null || B == null || C == null) return;
        String tex = st.texturing ? st.texture : null;
        out.add(new Tri(tex,
                new float[]{A.x(), A.y(), A.z(), B.x(), B.y(), B.z(), C.x(), C.y(), C.z()},
                new float[]{A.u() / 32f, A.v() / 32f, B.u() / 32f, B.v() / 32f, C.u() / 32f, C.v() / 32f},
                new int[]{st.col[a][0], st.col[a][1], st.col[a][2], st.col[b][0], st.col[b][1], st.col[b][2],
                        st.col[c][0], st.col[c][1], st.col[c][2]}));
    }
}
