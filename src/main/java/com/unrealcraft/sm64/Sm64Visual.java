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
 * Parse shared/area source, then add only static MODEL_LEVEL_GEOMETRY objects reachable
 * from the selected area's level-script functions. Their geo layouts and display-list
 * definitions are resolved on demand, avoiding a full parse of every object model.
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
    private static final Pattern MODEL_GEO = Pattern.compile(
            "LOAD_MODEL_FROM_GEO\\s*\\(\\s*(MODEL_[A-Za-z0-9_]+)\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern TERRAIN_OBJECT = Pattern.compile(
            "OBJECT(?:_WITH_ACTS)?\\s*\\(\\s*(MODEL_[A-Za-z0-9_]+)\\s*,\\s*"
                    + "(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*"
                    + "(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)");
    private static final Pattern TERRAIN_MACRO = Pattern.compile(
            "MACRO_OBJECT(?:_WITH_BHV_PARAM)?\\s*\\(\\s*(macro_[A-Za-z0-9_]+)\\s*,\\s*"
                    + "(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)");
    private static final Pattern MACRO_MODEL = Pattern.compile(
            "/\\*\\s*(macro_[A-Za-z0-9_]+)\\s*\\*/\\s*\\{\\s*[^,]+,\\s*"
                    + "(MODEL_LEVEL_GEOMETRY_[A-Za-z0-9_]+)");

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
        collectLayouts(levelDir, layouts);
        Map<String, String> modelLayouts = objectLayouts(levelDir);

        // Large level landforms are ordinary static objects in script.c, not display lists
        // in the area's GEO layout. Resolve their model id -> geo layout -> display lists.
        List<TerrainObject> terrainObjects = terrainObjects(sm64Root, levelDir, level, area, modelLayouts);
        Set<String> terrainLists = new HashSet<>();
        for (TerrainObject object : terrainObjects) {
            String layout = modelLayouts.get(object.model());
            if (layout != null) {
                for (String[] r : walkGeo(layouts.getOrDefault(layout, ""), layouts, new HashSet<>(), 0))
                    terrainLists.add(r[1]);
            }
        }
        parseFilesDefining(levelDir, terrainLists, f3d);

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
        int remaining = Math.max(0, MAX_TRIS - out.size());
        for (TerrainObject object : terrainObjects) {
            if (remaining == 0) break;
            String layout = modelLayouts.get(object.model());
            if (layout == null) continue;
            List<String[]> placedRoots = walkGeo(layouts.getOrDefault(layout, ""), layouts, new HashSet<>(), 0);
            Set<String> seen = new HashSet<>();
            float yaw = (float) Math.toRadians(object.yawDegrees());
            float sin = (float) Math.sin(yaw), cos = (float) Math.cos(yaw);
            for (String[] root : placedRoots) {
                if (!seen.add(root[0] + "|" + root[1])) continue;
                try {
                    for (F3d.Tri tri : f3d.run(root[1])) {
                        float[] xyz = tri.xyz().clone();
                        for (int i = 0; i < 9; i += 3) {
                            float x = xyz[i], z = xyz[i + 2];
                            xyz[i] = object.x() + x * cos + z * sin;
                            xyz[i + 1] += object.y();
                            xyz[i + 2] = object.z() - x * sin + z * cos;
                        }
                        out.add(new Tri(new F3d.Tri(tri.texture(), xyz, tri.uv(), tri.rgb()), root[0]));
                        if (--remaining == 0) break;
                    }
                } catch (Exception ignored) {}
                if (remaining == 0) break;
            }
        }
        return new Result(out, textureSizes(sm64Root), f3d.unresolved, roots.size() + terrainLists.size());
    }

    private record TerrainObject(String model, float x, float y, float z, float yawDegrees) {}

    private static List<TerrainObject> terrainObjects(Path sm64Root, Path levelDir, String level, int area,
                                                       Map<String, String> modelLayouts) throws IOException {
        Path script = levelDir.resolve("script.c");
        List<TerrainObject> out = new ArrayList<>();
        if (Files.isRegularFile(script)) {
            String src = Sm64Objects.areaBlock(strip(Files.readString(script)), area);
            Matcher m = TERRAIN_OBJECT.matcher(src);
            while (m.find()) {
                String model = m.group(1);
                String prefix = "MODEL_" + level.toUpperCase(Locale.ROOT) + "_";
                if (!model.startsWith("MODEL_LEVEL_GEOMETRY_") && !model.startsWith(prefix)) continue;
                if (!modelLayouts.containsKey(model) || Sm64Enemies.kindFrom(model) != null) continue;
                int yaw = Integer.parseInt(m.group(6));
                float degrees = Math.abs(yaw) > 360 ? yaw * (360.0f / 65536.0f) : yaw;
                out.add(new TerrainObject(model, Float.parseFloat(m.group(2)),
                        Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)), degrees));
            }
        }
        Path macroFile = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("macro.inc.c");
        Path presetFile = sm64Root.resolve("include/macro_presets.inc.c");
        if (Files.isRegularFile(macroFile) && Files.isRegularFile(presetFile)) {
            Map<String, String> macroModels = new HashMap<>();
            Matcher presets = MACRO_MODEL.matcher(Files.readString(presetFile));
            while (presets.find()) macroModels.put(presets.group(1), presets.group(2));
            Matcher macros = TERRAIN_MACRO.matcher(strip(Files.readString(macroFile)));
            while (macros.find()) {
                String model = macroModels.get(macros.group(1));
                if (model == null) continue;
                out.add(new TerrainObject(model, Float.parseFloat(macros.group(3)),
                        Float.parseFloat(macros.group(4)), Float.parseFloat(macros.group(5)),
                        Float.parseFloat(macros.group(2))));
            }
        }
        return out;
    }

    private static Map<String, String> objectLayouts(Path levelDir) throws IOException {
        Path script = levelDir.resolve("script.c");
        if (!Files.isRegularFile(script)) return Map.of();
        Matcher m = MODEL_GEO.matcher(strip(Files.readString(script)));
        Map<String, String> out = new HashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }

    /** Parse only model files that define a display list reached by a placed terrain geo. */
    private static void parseFilesDefining(Path levelDir, Set<String> lists, F3d f3d) throws IOException {
        if (lists.isEmpty()) return;
        try (Stream<Path> files = Files.walk(levelDir, 5)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !(name.endsWith(".c") || name.endsWith(".inc.c"))) continue;
                String src;
                try { src = Files.readString(file); } catch (Exception ignored) { continue; }
                boolean relevant = false;
                for (String dl : lists) {
                    if (src.contains(dl + "[]")) { relevant = true; break; }
                }
                if (relevant) f3d.parse(src);
            }
        }
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
