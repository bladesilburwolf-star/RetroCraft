package com.unrealcraft.sm64;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * SM64 level visual mesh (Fabric 1.21.1).
 *
 * Crash fix for BOB / WF: do NOT recursively parse the entire level directory.
 * Those levels ship dozens of heavy model.inc.c files for objects; loading them
 * all into F3d OOMs before any triangles are drawn.
 *
 * Parse scope:
 *   - bin/ (shared, shallow)
 *   - levels/<name>/areas/<area>/  (recursive — this area only)
 *   - levels/<name>/*.c at the level root only (texture.inc.c, leveldata — not subfolders)
 *
 * Geo scope: area geo.inc.c + GEO_BRANCH only (no sub-object geos).
 */
public final class Sm64Visual {
    public record Tri(F3d.Tri tri, String layer) {}
    public record Result(List<Tri> tris, Map<String, int[]> texSize, Set<String> unresolved, int displayLists) {}

    private static final Pattern GEO_DL = Pattern.compile(
            "GEO_DISPLAY_LIST\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern GEO_BRANCH = Pattern.compile(
            "GEO_BRANCH(?:_OR_CONTINUE)?\\(\\s*\\w+\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern GEO_LAYOUT_DEF = Pattern.compile(
            "(?:const\\s+)?GeoLayout\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{");

    private static final int MAX_TRIS = 150_000;

    private static final Map<Path, Map<String, int[]>> SIZE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Width/height of an extracted PNG; defaults to 32x32 when unknown. */
    public static synchronized int[] textureSize(Path root, String rel) {
        int[] s = SIZE_CACHE.computeIfAbsent(root, Sm64Visual::textureSizes).get(rel);
        return s != null ? s : new int[]{32, 32};
    }


    private Sm64Visual() {}

    public static Result load(Path sm64Root, String level, int area) throws IOException {
        Path levelDir = sm64Root.resolve("levels").resolve(level);
        Path areaDir = levelDir.resolve("areas").resolve(Integer.toString(area));
        Path geo = areaDir.resolve("geo.inc.c");
        if (!Files.isRegularFile(geo)) throw new IOException("No geo layout: " + geo);

        F3d f3d = new F3d();
        parseDir(sm64Root.resolve("bin"), f3d, false);  // shallow
        parseDir(areaDir, f3d, true);                   // this area only
        parseLevelRootOnly(levelDir, f3d);              // texture.inc.c etc., not subdirs

        Map<String, String> layouts = new HashMap<>();
        collectLayouts(areaDir, layouts);

        Map<String, String[]> unique = new LinkedHashMap<>();
        for (String[] r : walkGeo(strip(Files.readString(geo)), layouts, new HashSet<>(), 0)) {
            unique.putIfAbsent(r[0] + "|" + r[1], r);
        }

        List<String[]> roots = new ArrayList<>(unique.values());
        List<Tri> out = new ArrayList<>();
        for (String[] r : roots) {
            try {
                for (F3d.Tri t : f3d.run(r[1])) {
                    out.add(new Tri(t, r[0]));
                    if (out.size() >= MAX_TRIS) break;
                }
            } catch (Exception ignored) {}
            if (out.size() >= MAX_TRIS) break;
        }
        return new Result(out, textureSizes(sm64Root), f3d.unresolved, roots.size());
    }

    /** Only files directly in the level folder (not areas/, not object subfolders). */
    private static void parseLevelRootOnly(Path levelDir, F3d f3d) throws IOException {
        if (!Files.isDirectory(levelDir)) return;
        try (Stream<Path> s = Files.list(levelDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String n = p.getFileName().toString();
                if (n.endsWith(".c") || n.endsWith(".inc.c")) {
                    try { f3d.parse(Files.readString(p)); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static List<String[]> walkGeo(String src, Map<String, String> layouts, Set<String> visiting, int depth) {
        List<String[]> roots = new ArrayList<>();
        if (depth > 10) return roots;
        Matcher dl = GEO_DL.matcher(src);
        while (dl.find()) roots.add(new String[]{dl.group(1), dl.group(2)});
        Matcher br = GEO_BRANCH.matcher(src);
        while (br.find()) {
            String name = br.group(1);
            if (visiting.contains(name)) continue;
            String body = layouts.get(name);
            if (body == null) continue;
            visiting.add(name);
            roots.addAll(walkGeo(body, layouts, visiting, depth + 1));
            visiting.remove(name);
        }
        return roots;
    }

    private static void collectLayouts(Path dir, Map<String, String> layouts) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.endsWith(".c") && !n.endsWith(".inc.c")) continue;
                String src = strip(Files.readString(p));
                Matcher m = GEO_LAYOUT_DEF.matcher(src);
                while (m.find()) {
                    String name = m.group(1);
                    int start = m.end(), depth = 1, i = start;
                    for (; i < src.length() && depth > 0; i++) {
                        char c = src.charAt(i);
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    if (depth == 0) layouts.putIfAbsent(name, src.substring(start, i - 1));
                }
            }
        }
    }

    public static double coverage(Result vis, List<com.unrealcraft.quake3.Quake3Bsp.Tri> col) {
        float[] v = {1e9f, 1e9f, -1e9f, -1e9f}, c = {1e9f, 1e9f, -1e9f, -1e9f};
        for (Tri t : vis.tris()) bounds(t.tri().xyz(), v);
        for (var t : col) bounds(t.xyz(), c);
        double cv = (double) (c[2] - c[0]) * (c[3] - c[1]);
        double vv = (double) Math.max(0, v[2] - v[0]) * Math.max(0, v[3] - v[1]);
        return cv > 1e-3 ? vv / cv : 0;
    }

    private static void bounds(float[] xyz, float[] b) {
        for (int i = 0; i < xyz.length; i += 3) {
            b[0] = Math.min(b[0], xyz[i]);
            b[1] = Math.min(b[1], xyz[i + 2]);
            b[2] = Math.max(b[2], xyz[i]);
            b[3] = Math.max(b[3], xyz[i + 2]);
        }
    }

    private static final Pattern TEX_META = Pattern.compile(
            "\"([^\"]+\\.png)\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*\\{");

    private static Map<String, int[]> textureSizes(Path root) {
        Map<String, int[]> out = new HashMap<>();
        try {
            Matcher m = TEX_META.matcher(Files.readString(root.resolve("assets.json")));
            while (m.find()) out.put(m.group(1), new int[]{Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
        } catch (Exception ignored) {}
        return out;
    }

    private static void parseDir(Path dir, F3d f3d, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) return;
        int depth = recursive ? 5 : 1;
        try (Stream<Path> s = Files.walk(dir, depth)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".c") || n.endsWith(".inc.c")) {
                    try { f3d.parse(Files.readString(p)); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static String strip(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
