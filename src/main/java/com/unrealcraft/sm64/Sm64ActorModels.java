package com.unrealcraft.sm64;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads actor F3D meshes from actors/&lt;folder&gt; (Fabric 1.21.1, pure Java).
 * Cached per key. Failures return empty mesh — callers fall back to box props.
 */
public final class Sm64ActorModels {
    public record Mesh(List<F3d.Tri> tris, String layer) {}

    private static final Map<String, Mesh> CACHE = new HashMap<>();
    private static final Pattern GEO_DL = Pattern.compile(
            "GEO_DISPLAY_LIST\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern GEO_LAYOUT = Pattern.compile(
            "(?:const\\s+)?GeoLayout\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{");

    private Sm64ActorModels() {}

    public static Mesh tree(Path sm64Root, String presetOrKind) {
        return load(sm64Root, "tree", mapTreeGeo(presetOrKind));
    }

    /** Generic: kind maps to actors folder + preferred geo name. */
    public static Mesh forKind(Path sm64Root, String kind, String preset) {
        return switch (kind) {
            case "tree" -> tree(sm64Root, preset);
            case "coin", "red_coin", "blue_coin" -> load(sm64Root, "coin", null);
            case "star" -> load(sm64Root, "star", null);
            case "sign" -> load(sm64Root, "wooden_signpost", null);
            case "cannon" -> loadFirst(sm64Root, List.of("cannon_barrel", "cannon_base", "group12"), null);
            case "door" -> loadFirst(sm64Root, List.of("door", "wooden_door", "castle_door"), null);
            case "box" -> loadFirst(sm64Root, List.of("exclamation_box", "breakable_box", "box"), null);
            case "chest" -> loadFirst(sm64Root, List.of("treasure_chest", "chest"), null);
            case "mine" -> loadFirst(sm64Root, List.of("bowser_bomb", "bomb", "mine"), null);
            case "butterfly" -> load(sm64Root, "butterfly", null);
            default -> empty();
        };
    }

    private static String mapTreeGeo(String s) {
        if (s == null) return "bubbly_tree_geo";
        String p = s.toLowerCase(Locale.ROOT);
        if (p.contains("spiky")) return "spiky_tree_geo";
        if (p.contains("snow")) return "snow_tree_geo";
        if (p.contains("palm")) return "palm_tree_geo";
        return "bubbly_tree_geo";
    }

    private static Mesh loadFirst(Path root, List<String> folders, String preferredGeo) {
        for (String f : folders) {
            Mesh m = load(root, f, preferredGeo);
            if (!m.tris().isEmpty()) return m;
        }
        return empty();
    }

    private static Mesh load(Path sm64Root, String folder, String preferredGeo) {
        String cacheKey = folder + "|" + preferredGeo;
        if (CACHE.containsKey(cacheKey)) return CACHE.get(cacheKey);
        Mesh mesh = loadUncached(sm64Root, folder, preferredGeo);
        CACHE.put(cacheKey, mesh);
        return mesh;
    }

    private static Mesh loadUncached(Path sm64Root, String folder, String preferredGeo) {
        try {
            Path actors = sm64Root.resolve("actors").resolve(folder);
            if (!Files.isDirectory(actors)) return empty();
            F3d f3d = new F3d();
            try (Stream<Path> st = Files.walk(actors, 3)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".c") || n.endsWith(".inc.c")) {
                        try { f3d.parse(Files.readString(p)); } catch (Exception ignored) {}
                    }
                }
            }
            String layer = "LAYER_OPAQUE";
            String dl = null;
            Path geo = actors.resolve("geo.inc.c");
            if (!Files.isRegularFile(geo)) {
                // any geo*.c
                try (Stream<Path> st = Files.list(actors)) {
                    Optional<Path> g = st.filter(p -> p.getFileName().toString().contains("geo")).findFirst();
                    if (g.isPresent()) geo = g.get();
                }
            }
            if (Files.isRegularFile(geo)) {
                String src = Files.readString(geo);
                String body = src;
                if (preferredGeo != null) {
                    Matcher m = Pattern.compile(
                            "GeoLayout\\s+" + Pattern.quote(preferredGeo) + "\\s*\\[\\s*\\]\\s*=\\s*\\{",
                            Pattern.CASE_INSENSITIVE).matcher(src);
                    if (m.find()) {
                        int start = m.end(), depth = 1, i = start;
                        for (; i < src.length() && depth > 0; i++) {
                            char c = src.charAt(i);
                            if (c == '{') depth++; else if (c == '}') depth--;
                        }
                        if (depth == 0) body = src.substring(start, i - 1);
                    }
                }
                Matcher dlM = GEO_DL.matcher(body);
                if (dlM.find()) {
                    layer = dlM.group(1);
                    dl = dlM.group(2);
                }
            }
            if (dl == null && !f3d.dls.isEmpty()) {
                // Prefer a DL whose name contains the folder token
                dl = f3d.dls.keySet().stream()
                        .filter(k -> k.toLowerCase(Locale.ROOT).contains(folder.replace("_", "").substring(0, Math.min(4, folder.length()))))
                        .findFirst()
                        .orElse(f3d.dls.keySet().iterator().next());
            }
            if (dl == null) return empty();
            List<F3d.Tri> tris = f3d.run(dl);
            if (tris.size() > 8000) tris = new ArrayList<>(tris.subList(0, 8000));
            return new Mesh(List.copyOf(tris), layer);
        } catch (Exception e) {
            return empty();
        }
    }

    private static Mesh empty() {
        return new Mesh(List.of(), "LAYER_OPAQUE");
    }
}
