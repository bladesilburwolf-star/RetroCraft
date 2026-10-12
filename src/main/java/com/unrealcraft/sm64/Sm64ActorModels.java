package com.unrealcraft.sm64;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads actor F3D meshes from actors/&lt;folder&gt; (Fabric 1.21.1, pure Java).
 * Reads geo-referenced display lists (including animated-part lists) from the user's decomp tree.
 * Cached per source root and model. Missing models return an empty mesh for the caller's fallback renderer.
 */
public final class Sm64ActorModels {
    /** geoScale is the root GEO_SCALE value (16.16 fixed point) for source-unit vertices. */
    public record Mesh(List<F3d.Tri> tris, String layer, float geoScale, float minY, boolean billboard) {}

    private static final Map<String, Mesh> CACHE = new HashMap<>();
    private static final Pattern GEO_DL = Pattern.compile(
            "GEO_DISPLAY_LIST\\(\\s*(\\w+)\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern GEO_PART = Pattern.compile(
            "GEO_ANIMATED_PART\\(\\s*\\w+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*-?\\d+\\s*,\\s*(\\w+)\\s*\\)");
    private static final Pattern GEO_LAYOUT = Pattern.compile(
            "(?:const\\s+)?GeoLayout\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\{");
    private static final Pattern GEO_COMMAND = Pattern.compile("(GEO_[A-Z0-9_]+)\\s*\\(([^()]*)\\)");

    private Sm64ActorModels() {}

    public static Mesh tree(Path sm64Root, String presetOrKind) {
        return load(sm64Root, "tree", mapTreeGeo(presetOrKind));
    }

    /** Generic: kind maps to actors folder + preferred geo name. */
    public static Mesh forKind(Path sm64Root, String kind, String preset) {
        return switch (kind) {
            case "tree" -> tree(sm64Root, preset);
            case "coin" -> load(sm64Root, "coin", "yellow_coin_no_shadow_geo");
            case "red_coin" -> load(sm64Root, "coin", "red_coin_no_shadow_geo");
            case "blue_coin" -> load(sm64Root, "coin", "blue_coin_no_shadow_geo");
            case "star" -> load(sm64Root, "star", "star_geo");
            case "sign" -> load(sm64Root, "wooden_signpost", "wooden_signpost_geo");
            case "cannon" -> cannon(sm64Root, preset);
            case "door" -> load(sm64Root, "door", doorGeo(preset));
            case "box" -> box(sm64Root, preset);
            case "chest" -> load(sm64Root, "treasure_chest", "treasure_chest_base_geo");
            case "mine" -> loadFirst(sm64Root, List.of("water_mine", "bowser_bomb", "bomb"), null);
            case "butterfly" -> load(sm64Root, "butterfly", "butterfly_geo");
            case "goomba" -> load(sm64Root, "goomba", "goomba_geo");
            case "koopa" -> load(sm64Root, "koopa", "koopa_without_shell_geo");
            case "bobomb" -> load(sm64Root, "bobomb", "black_bobomb_geo");
            case "king_bobomb" -> load(sm64Root, "king_bobomb", "king_bobomb_geo");
            case "bully", "big_bully" -> load(sm64Root, "bully", "bully_geo");
            case "boo", "big_boo" -> load(sm64Root, "boo", "boo_geo");
            case "whomp" -> load(sm64Root, "whomp", "whomp_geo");
            case "thwomp" -> load(sm64Root, "thwomp", "thwomp_geo");
            case "piranha" -> load(sm64Root, "piranha_plant", "piranha_plant_geo");
            case "chain_chomp" -> load(sm64Root, "chain_chomp", "chain_chomp_geo");
            case "bowser" -> load(sm64Root, "bowser", "bowser_geo_0000D8");
            case "fly_guy" -> load(sm64Root, "flyguy", "flyguy_geo");
            case "lakitu" -> load(sm64Root, "lakitu_enemy", "enemy_lakitu_geo");
            case "chuckya" -> load(sm64Root, "chuckya", "chuckya_geo");
            case "amp" -> load(sm64Root, "amp", "dAmpGeo");
            case "mr_i" -> load(sm64Root, "mr_i_eyeball", "mr_i_geo");
            case "mr_blizzard" -> load(sm64Root, "chillychief", "chilly_chief_geo");
            case "spiny" -> load(sm64Root, "spiny", null);
            case "heave_ho" -> load(sm64Root, "heave_ho", "heave_ho_geo");
            case "scuttlebug" -> load(sm64Root, "scuttlebug", "scuttlebug_geo");
            case "moneybag" -> load(sm64Root, "moneybag", "moneybag_geo_000000");
            case "pokey" -> load(sm64Root, "pokey", "pokey_head_geo");
            case "spindrift" -> load(sm64Root, "spindrift", "spindrift_geo");
            case "skeeter" -> load(sm64Root, "skeeter", "skeeter_geo");
            case "snufit" -> load(sm64Root, "snufit", "snufit_geo");
            case "eyerok" -> load(sm64Root, "eyerok", "eyerok_geo_0002AC");
            case "wiggler" -> load(sm64Root, "wiggler_head", "wiggler_head_geo");
            case "klepto" -> load(sm64Root, "klepto", "klepto_geo");
            case "ukiki" -> load(sm64Root, "ukiki", "ukiki_geo");
            case "bullet_bill" -> load(sm64Root, "bullet_bill", "bullet_bill_geo");
            case "mad_piano" -> load(sm64Root, "mad_piano", "mad_piano_geo");
            case "bookend" -> load(sm64Root, "bookend", "bookend_part_geo");
            case "haunted_chair" -> load(sm64Root, "chair", "haunted_chair_geo");
            case "monty_mole" -> load(sm64Root, "monty_mole", "monty_mole_geo");
            case "sushi" -> load(sm64Root, "sushi", "sushi_geo");
            case "bubba" -> load(sm64Root, "bubba", "bubba_geo");
            case "unagi" -> load(sm64Root, "unagi", "unagi_geo");
            case "dorrie" -> load(sm64Root, "dorrie", "dorrie_geo");
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

    private static Mesh cannon(Path root, String preset) {
        String p = preset == null ? "" : preset.toLowerCase(Locale.ROOT);
        if (p.contains("barrel")) return load(root, "cannon_barrel", "cannon_barrel_geo");
        if (p.contains("lid")) return load(root, "cannon_lid", null);
        return load(root, "cannon_base", "cannon_base_geo");
    }

    private static String doorGeo(String preset) {
        String p = preset == null ? "" : preset.toLowerCase(Locale.ROOT);
        if (p.contains("cabin")) return "cabin_door_geo";
        if (p.contains("wood")) return "wooden_door_geo";
        if (p.contains("metal")) return "metal_door_geo";
        if (p.contains("hmc") || p.contains("maze")) return "hazy_maze_door_geo";
        return "castle_door_geo";
    }

    private static Mesh box(Path root, String preset) {
        String p = preset == null ? "" : preset.toLowerCase(Locale.ROOT);
        if (p.contains("exclamation") || p.contains("yellow_box") || p.contains("blue_coin"))
            return load(root, "exclamation_box", "exclamation_box_geo");
        if (p.contains("metal")) return load(root, "metal_box", null);
        return load(root, "breakable_box", "breakable_box_geo");
    }

    private static Mesh loadFirst(Path root, List<String> folders, String preferredGeo) {
        for (String f : folders) {
            Mesh m = load(root, f, preferredGeo);
            if (!m.tris().isEmpty()) return m;
        }
        return empty();
    }

    private static Mesh load(Path sm64Root, String folder, String preferredGeo) {
        String cacheKey = sm64Root.toAbsolutePath().normalize() + "|" + folder + "|" + preferredGeo;
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
            List<GeoPart> parts = new ArrayList<>();
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
                if (body == src) {
                    Matcher firstLayout = GEO_LAYOUT.matcher(src);
                    if (firstLayout.find()) {
                        int start = firstLayout.end(), depth = 1, i = start;
                        for (; i < src.length() && depth > 0; i++) {
                            char c = src.charAt(i);
                            if (c == '{') depth++; else if (c == '}') depth--;
                        }
                        if (depth == 0) body = src.substring(start, i - 1);
                    }
                }
                parts = collectGeoParts(body);
            }
            if (parts.isEmpty() && !f3d.dls.isEmpty()) {
                // Prefer a DL whose name contains the folder token
                List<String> names = new ArrayList<>(f3d.dls.keySet());
                Collections.sort(names);
                String token = folder.replace("_", "").substring(0, Math.min(4, folder.length()));
                String fallback = names.stream().filter(k -> k.toLowerCase(Locale.ROOT).contains(token)).findFirst()
                        .orElse(names.get(0));
                parts.add(new GeoPart("LAYER_OPAQUE", fallback, new Transform()));
            }
            if (parts.isEmpty()) return empty();
            Set<String> distinctLists = new HashSet<>();
            boolean hasTransparent = false;
            String opaqueLayer = "LAYER_OPAQUE";
            for (GeoPart part : parts) {
                distinctLists.add(part.displayList());
                hasTransparent |= part.layer().contains("TRANSPARENT") || part.layer().contains("ALPHA");
                if (part.layer().contains("OPAQUE")) opaqueLayer = part.layer();
            }
            // Boo switches the same body display list between opaque and translucent modes. Other
            // actors often have a few alpha eyes/decals, so keep their main mesh on its opaque layer.
            layer = distinctLists.size() == 1 && hasTransparent ? "LAYER_TRANSPARENT" : opaqueLayer;
            // Coin geo layouts are switch cases for animation frames; drawing every branch stacks several coin orientations.
            if (folder.equals("coin") && parts.size() > 1) parts = List.of(parts.get(0));
            List<F3d.Tri> tris = new ArrayList<>();
            for (GeoPart part : parts) {
                if (folder.equals("amp") && part.displayList().equals("dAmpBodyDl")) continue;
                List<F3d.Tri> model = f3d.run(part.displayList());
                for (F3d.Tri tri : model) {
                    F3d.Tri placed = transform(tri, part.transform());
                    if (folder.equals("amp") && part.transform().billboard) {
                        float[] xyz = placed.xyz().clone();
                        for (int i = 2; i < 9; i += 3) xyz[i] += 55f;
                        placed = new F3d.Tri(placed.texture(), xyz, placed.uv(), placed.rgb());
                    }
                    tris.add(placed);
                }
                if (tris.size() >= 8000) break;
            }
            if (folder.equals("amp")) {
                // The stock Amp geo is a set of camera-facing texture cards. The decomp also
                // carries its unused rounded body mesh; use that as the volume under the face.
                Transform rounded = new Transform();
                rounded.scale = 0.25f; // dAmpGeo's root GEO_SCALE
                for (F3d.Tri tri : f3d.run("dAmpUnused3DModelDl")) {
                    F3d.Tri scaled = transform(tri, rounded);
                    int[] yellow = new int[9];
                    for (int i = 0; i < yellow.length; i += 3) {
                        yellow[i] = 245; yellow[i + 1] = 195; yellow[i + 2] = 25;
                    }
                    tris.add(new F3d.Tri(null, scaled.xyz(), scaled.uv(), yellow));
                }
            }
            if (tris.size() > 8000) tris = new ArrayList<>(tris.subList(0, 8000));
            float minY = 0f;
            if (!tris.isEmpty()) {
                minY = Float.POSITIVE_INFINITY;
                for (F3d.Tri tri : tris) {
                    float[] xyz = tri.xyz();
                    minY = Math.min(minY, Math.min(xyz[1], Math.min(xyz[4], xyz[7])));
                }
            }
            // Some SM64 actors are deliberately billboarded. Facing those meshes toward the
            // Minecraft camera prevents them collapsing to a line when viewed edge-on.
            boolean billboard = parts.stream().anyMatch(part -> part.transform().billboard);
            if (!tris.isEmpty()) {
                float[] b = bounds(tris);
                float largest = Math.max(b[1] - b[0], Math.max(b[3] - b[2], b[5] - b[4]));
                float smallest = Math.min(b[1] - b[0], Math.min(b[3] - b[2], b[5] - b[4]));
                billboard |= largest > 0 && smallest < largest * 0.002f;
            }
            // Geo scale/translations/rotations are baked into vertices above.
            return new Mesh(List.copyOf(tris), layer, 1f, minY, billboard);
        } catch (Exception e) {
            return empty();
        }
    }

    private record GeoPart(String layer, String displayList, Transform transform) {}
    private static final class Transform {
        float x, y, z, scale = 1f;
        float pitch, yaw, roll;
        boolean billboard;
        Transform copy() {
            Transform t = new Transform(); t.x=x; t.y=y; t.z=z; t.scale=scale;
            t.pitch=pitch; t.yaw=yaw; t.roll=roll; t.billboard=billboard; return t;
        }
        void translate(float dx, float dy, float dz) {
            float cp=(float)Math.cos(pitch), sp=(float)Math.sin(pitch);
            float cy=(float)Math.cos(yaw), sy=(float)Math.sin(yaw);
            float cr=(float)Math.cos(roll), sr=(float)Math.sin(roll);
            float rx=dx*cr-dy*sr, ry=dx*sr+dy*cr, rz=dz;
            float py=ry*cp-rz*sp, pz=ry*sp+rz*cp;
            x += scale*(rx*cy+pz*sy); y += scale*py; z += scale*(-rx*sy+pz*cy);
        }
    }

    private static List<GeoPart> collectGeoParts(String body) {
        List<GeoPart> out = new ArrayList<>();
        ArrayDeque<Transform> stack = new ArrayDeque<>();
        ArrayDeque<Boolean> rangeStack = new ArrayDeque<>();
        Transform t = new Transform();
        boolean skipFarRange = false;
        Matcher m = GEO_COMMAND.matcher(body);
        while (m.find()) {
            String op = m.group(1);
            String[] a = m.group(2).split(",");
            for (int i=0;i<a.length;i++) a[i]=a[i].trim();
            switch (op) {
                case "GEO_OPEN_NODE" -> { stack.push(t.copy()); rangeStack.push(skipFarRange); }
                case "GEO_CLOSE_NODE" -> { if (!stack.isEmpty()) t=stack.pop(); if (!rangeStack.isEmpty()) skipFarRange=rangeStack.pop(); }
                case "GEO_RENDER_RANGE" -> { if (a.length >= 2) skipFarRange = number(a[0]) >= 3500; }
                case "GEO_SCALE" -> {
                    if (a.length >= 2) {
                        t = stack.isEmpty() ? new Transform() : stack.peek().copy();
                        t.scale *= number(a[1]) / 65536f;
                    }
                }
                case "GEO_BILLBOARD" -> t.billboard = true;
                case "GEO_TRANSLATE_NODE", "GEO_ANIMATED_PART" -> {
                    if (a.length >= 4) {
                        t = stack.isEmpty() ? new Transform() : stack.peek().copy();
                        t.translate(number(a[1]), number(a[2]), number(a[3]));
                        if (op.equals("GEO_ANIMATED_PART") && a.length >= 5 && !a[4].equals("NULL") && !skipFarRange)
                            out.add(new GeoPart(a[0], a[4], t.copy()));
                    }
                }
                case "GEO_ROTATION_NODE" -> {
                    if (a.length >= 4) {
                        t = stack.isEmpty() ? new Transform() : stack.peek().copy();
                        float unit = (float)(Math.PI / 32768.0);
                        t.pitch += number(a[1]) * unit;
                        t.yaw += number(a[2]) * unit;
                        t.roll += number(a[3]) * unit;
                    }
                }
                case "GEO_DISPLAY_LIST" -> {
                    if (a.length >= 2 && !skipFarRange) out.add(new GeoPart(a[0], a[1], t.copy()));
                }
            }
        }
        Map<String, GeoPart> unique = new LinkedHashMap<>();
        for (GeoPart part : out) {
            Transform pt = part.transform();
            String key = part.displayList() + '|' + pt.x + '|' + pt.y + '|' + pt.z + '|' + pt.scale
                    + '|' + pt.pitch + '|' + pt.yaw + '|' + pt.roll + '|' + pt.billboard;
            GeoPart previous = unique.get(key);
            if (previous == null || (!previous.layer().contains("TRANSPARENT") && part.layer().contains("TRANSPARENT")))
                unique.put(key, part);
        }
        return new ArrayList<>(unique.values());
    }

    private static float number(String raw) {
        String s=raw.trim();
        if (s.startsWith("-0x") || s.startsWith("-0X")) return -Integer.parseInt(s.substring(3),16);
        return Integer.decode(s);
    }

    private static F3d.Tri transform(F3d.Tri tri, Transform t) {
        float[] src=tri.xyz(), dst=src.clone();
        float cp=(float)Math.cos(t.pitch), sp=(float)Math.sin(t.pitch);
        float cy=(float)Math.cos(t.yaw), sy=(float)Math.sin(t.yaw);
        float cr=(float)Math.cos(t.roll), sr=(float)Math.sin(t.roll);
        for (int i=0;i<9;i+=3) {
            float x=src[i], y=src[i+1], z=src[i+2];
            float rx=x*cr-y*sr, ry=x*sr+y*cr;
            float py=ry*cp-z*sp, pz=ry*sp+z*cp;
            dst[i]=t.x+t.scale*(rx*cy+pz*sy);
            dst[i+1]=t.y+t.scale*py;
            dst[i+2]=t.z+t.scale*(-rx*sy+pz*cy);
        }
        return new F3d.Tri(tri.texture(),dst,tri.uv(),tri.rgb());
    }

    private static float[] bounds(List<F3d.Tri> tris) {
        float[] b={Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for (F3d.Tri tri:tris) {
            float[] v=tri.xyz();
            for (int i=0;i<9;i+=3) {
                b[0]=Math.min(b[0],v[i]); b[1]=Math.max(b[1],v[i]);
                b[2]=Math.min(b[2],v[i+1]); b[3]=Math.max(b[3],v[i+1]);
                b[4]=Math.min(b[4],v[i+2]); b[5]=Math.max(b[5],v[i+2]);
            }
        }
        return b;
    }

    private static Mesh empty() {
        return new Mesh(List.of(), "LAYER_OPAQUE", 1f, 0f, false);
    }
}
