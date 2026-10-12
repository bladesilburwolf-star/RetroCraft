package com.unrealcraft.sm64;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads SM64 decomp placements for trees and other static props.
 *
 * Sources (in order of importance for trees):
 *   1. collision.inc.c  SPECIAL_OBJECT / SPECIAL_OBJECT_WITH_YAW  (castle grounds trees live here)
 *   2. macro.inc.c      MACRO_OBJECT / MACRO_OBJECT_WITH_BHV_PARAM
 *   3. script.c         OBJECT(...)
 */
public final class Sm64Objects {
    public record Placement(String kind, float x, float y, float z, int yaw, String modelOrPreset) {}

    // SPECIAL_OBJECT(/*preset*/ special_bubble_tree, /*pos*/ x, y, z)
    private static final Pattern SPECIAL = Pattern.compile(
            "SPECIAL_OBJECT(?:_WITH_YAW(?:_AND_PARAM)?)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)" +
            "(?:\\s*,\\s*(?:/\\*[^*]*\\*/\\s*)?(-?\\d+))?",  // optional yaw
            Pattern.MULTILINE);

    private static final Pattern OBJECT = Pattern.compile(
            "(?<![A-Za-z0-9_])OBJECT(?:_WITH_ACTS)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,",
            Pattern.MULTILINE);

    private static final Pattern MACRO = Pattern.compile(
            "MACRO_OBJECT(?:_WITH_BHV_PARAM)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)",
            Pattern.MULTILINE);

    private Sm64Objects() {}

    public static List<Placement> load(Path sm64Root, String level, int area) throws IOException {
        Path levelDir = sm64Root.resolve("levels").resolve(level);
        List<Placement> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();

        // collision is the main source for trees on castle grounds / bob / etc.
        Path col = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("collision.inc.c");
        if (Files.isRegularFile(col)) files.add(col);

        Path script = levelDir.resolve("script.c");
        if (Files.isRegularFile(script)) files.add(script);

        Path macro = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("macro.inc.c");
        if (Files.isRegularFile(macro)) files.add(macro);
        Path macro2 = levelDir.resolve("macro.inc.c");
        if (Files.isRegularFile(macro2)) files.add(macro2);

        String sharedScripts = "";
        Path shared = sm64Root.resolve("levels").resolve("scripts.c");
        if (Files.isRegularFile(shared)) sharedScripts = stripComments(Files.readString(shared));
        for (Path f : files) {
            String src = stripComments(Files.readString(f));
            if (f.getFileName().toString().equals("script.c")) src = areaBlock(src, area, sharedScripts);
            parseSpecial(src, out);
            parseObject(src, out);
            parseMacro(src, out);
        }
        Map<String, Placement> unique = new LinkedHashMap<>();
        for (Placement p : out) {
            String key = p.kind() + '|' + Float.floatToIntBits(p.x()) + '|' + Float.floatToIntBits(p.y())
                    + '|' + Float.floatToIntBits(p.z()) + '|' + p.yaw();
            unique.putIfAbsent(key, p);
        }
        return new ArrayList<>(unique.values());
    }

    static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }

    /**
     * script.c lists the objects of every area, many of them inside helper functions
     * (script_func_local_N) that an area calls with JUMP_LINK. Return the AREA(n, ...) ... END_AREA() text plus the
     * bodies of every script function that block references, so objects of other areas are left out.
     */
    static String areaBlock(String src, int area) {
        return areaBlock(src, area, "");
    }

    static String areaBlock(String src, int area, String sharedScripts) {
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_])AREA\\(\\s*" + area + "\\s*,").matcher(src);
        if (!m.find()) return src;
        int end = src.indexOf("END_AREA", m.end());
        StringBuilder out = new StringBuilder(src.substring(m.start(), end < 0 ? src.length() : end));
        // The level entry point usually links shared scripts before AREA() (common enemies and objects).
        Matcher topLinks = Pattern.compile("JUMP_LINK\\(\\s*(script_func_global_\\w+)\\s*\\)")
                .matcher(src.substring(0, m.start()));
        while (topLinks.find()) out.append("\nJUMP_LINK(").append(topLinks.group(1)).append(")");
        Map<String, String> fns = new LinkedHashMap<>(scriptFunctions(sharedScripts));
        fns.putAll(scriptFunctions(src));
        Set<String> added = new HashSet<>();
        for (int pass = 0; pass < 4; pass++) { // functions may call other functions
            boolean grew = false;
            String hay = out.toString();
            for (var e : fns.entrySet()) {
                if (added.contains(e.getKey()) || !Pattern.compile("(?<![A-Za-z0-9_])" + e.getKey() + "(?![A-Za-z0-9_])").matcher(hay).find()) continue;
                out.append('\n').append(e.getValue());
                added.add(e.getKey());
                grew = true;
            }
            if (!grew) break;
        }
        return out.toString();
    }

    /** name -> body for every "const LevelScript name[] = { ... };" in the file. */
    private static Map<String, String> scriptFunctions(String src) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("LevelScript\\s+(\\w+)\\s*\\[\\s*]\\s*=\\s*\\{").matcher(src);
        while (m.find()) {
            int depth = 1, i = m.end();
            while (i < src.length() && depth > 0) {
                char c = src.charAt(i++);
                if (c == '{') depth++; else if (c == '}') depth--;
            }
            out.put(m.group(1), src.substring(m.end(), Math.max(m.end(), i - 1)));
        }
        return out;
    }

    private static void parseSpecial(String src, List<Placement> out) {
        Matcher m = SPECIAL.matcher(src);
        while (m.find()) {
            String preset = m.group(1);
            float x = Float.parseFloat(m.group(2));
            float y = Float.parseFloat(m.group(3));
            float z = Float.parseFloat(m.group(4));
            int yaw = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
            String kind = classify(null, preset);
            if (kind != null) out.add(new Placement(kind, x, y, z, yaw, preset));
        }
    }

    private static void parseObject(String src, List<Placement> out) {
        Matcher m = OBJECT.matcher(src);
        while (m.find()) {
            String model = m.group(1);
            float x = Float.parseFloat(m.group(2));
            float y = Float.parseFloat(m.group(3));
            float z = Float.parseFloat(m.group(4));
            int yaw = Integer.parseInt(m.group(6));
            String kind = classify(model, null);
            if (kind != null) out.add(new Placement(kind, x, y, z, yaw, model));
        }
    }

    private static void parseMacro(String src, List<Placement> out) {
        Matcher m = MACRO.matcher(src);
        while (m.find()) {
            String preset = m.group(1);
            int yaw = Integer.parseInt(m.group(2));
            float x = Float.parseFloat(m.group(3));
            float y = Float.parseFloat(m.group(4));
            float z = Float.parseFloat(m.group(5));
            String kind = classify(null, preset);
            if (kind != null) out.add(new Placement(kind, x, y, z, yaw, preset));
        }
    }

    /** null = skip. */
    /**
     * Map a decomp model / preset name to a render kind, or null to skip it.
     * Token based (MODEL_BLACK_BOBOMB -> {black, bobomb}) so "amp" cannot match "ramp".
     * Enemies are skipped here (see {@link Sm64Enemies}); level-geometry pieces and warps are skipped until real
     * geo is loaded for them. Names checked against every OBJECT / SPECIAL_OBJECT / MACRO_OBJECT in the decomp.
     */
    static String classify(String model, String preset) {
        String name = model != null ? model : preset;
        if (name == null) return null;
        Set<String> t = Sm64Enemies.tokens(name);
        if (t.contains("none") || t.contains("level") && (t.contains("geo") || t.contains("geometry"))) return null;
        if (t.contains("null") && t.contains("start") || t.contains("trigger") || t.contains("menu")) return null;
        if (t.contains("diamond") || t.contains("whirlpool") || t.contains("mist") || t.contains("spawner")) return null;
        if (Sm64Enemies.kindFrom(name) != null) return null; // enemies are placed by Sm64Enemies

        if (t.contains("tree") || t.contains("palm")) return "tree";
        if ((t.contains("coin") || t.contains("coins")) && !t.contains("box")) {
            if (t.contains("red")) return "red_coin";
            if (t.contains("blue")) return "blue_coin";
            return "coin";
        }
        if (t.contains("door")) return "door";
        if (t.contains("star") && !t.contains("smiley")) return "star";
        if (t.contains("cannon")) return t.contains("grill") ? "platform" : "cannon";
        if (t.contains("signpost") || t.contains("sign")) return "sign";
        if (t.contains("flag")) return "flag";
        if (t.contains("chest")) return "chest";
        if (t.contains("box") || t.contains("exclamation") || t.contains("breakable")) return "box";
        if (t.contains("mine")) return "mine";
        if (t.contains("butterfly") || t.contains("butterflies")) return "butterfly";
        for (String p : PLATFORM_TOKENS) if (t.contains(p)) return "platform";
        for (String p : PROP_TOKENS) if (t.contains(p)) return "prop";
        return null;
    }

    private static final String[] PLATFORM_TOKENS = {"platform", "platforms", "elevator", "lift", "seesaw", "tilting", "sinking",
            "rotating", "tumbling", "bridge", "bomp", "carpet", "ferris", "staircase", "merry", "bookshelf", "pillar",
            "pyramid", "arena", "falling", "sliding", "swinging", "stretching", "cage", "podium", "log", "pole",
            "grill", "pipe", "checkerboard", "cruiser", "fire", "bars", "gear", "treadmill", "spinner", "pendulum",
            "block", "drawbridge", "spindel", "tomb", "snowman", "ship", "board", "island", "clock"};
    private static final String[] PROP_TOKENS = {"flame", "1up", "yoshi", "toad", "penguin", "birds", "rock", "smiley",
            "switch", "bowling", "ball", "hole", "buddy", "cap", "wing", "heart"};

    public static List<String> listActorProps(Path sm64Root) throws IOException {
        Path actors = sm64Root.resolve("actors");
        if (!Files.isDirectory(actors)) return List.of();
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(actors)) {
            s.filter(Files::isDirectory).forEach(d -> {
                String n = d.getFileName().toString();
                if (n.contains("tree") || n.contains("door") || n.contains("sign")
                        || n.contains("coin") || n.contains("star") || n.contains("box")
                        || n.contains("cannon") || n.contains("platform")) {
                    names.add(n);
                }
            });
        }
        return names;
    }
}
