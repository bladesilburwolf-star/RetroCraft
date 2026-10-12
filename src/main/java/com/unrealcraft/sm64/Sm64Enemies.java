package com.unrealcraft.sm64;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SM64 level scripts for enemy spawns. Phase 2: placements + kind labels only.
 * AI / collision / animations come later.
 *
 * Sources: script.c OBJECT(...), collision SPECIAL_OBJECT for bully/boo/mr_i, etc.
 */
public final class Sm64Enemies {
    public record Spawn(String kind, float x, float y, float z, int yaw, String source) {}

    private static final Pattern OBJECT = Pattern.compile(
            "(?<![A-Za-z0-9_])OBJECT(?:_WITH_ACTS)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,",
            Pattern.MULTILINE);

    private static final Pattern SPECIAL = Pattern.compile(
            "SPECIAL_OBJECT(?:_WITH_YAW(?:_AND_PARAM)?)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)" +
            "(?:\\s*,\\s*(?:/\\*[^*]*\\*/\\s*)?(-?\\d+))?",
            Pattern.MULTILINE);

    private static final Pattern MACRO = Pattern.compile(
            "MACRO_OBJECT(?:_WITH_BHV_PARAM)?\\s*\\(\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?([A-Za-z0-9_]+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*" +
            "(?:/\\*[^*]*\\*/\\s*)?(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)",
            Pattern.MULTILINE);

    private Sm64Enemies() {}

    public static List<Spawn> load(Path sm64Root, String level, int area) throws IOException {
        Path levelDir = sm64Root.resolve("levels").resolve(level);
        List<Spawn> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        Path script = levelDir.resolve("script.c");
        if (Files.isRegularFile(script)) files.add(script);
        Path col = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("collision.inc.c");
        if (Files.isRegularFile(col)) files.add(col);
        Path macro = levelDir.resolve("areas").resolve(Integer.toString(area)).resolve("macro.inc.c");
        if (Files.isRegularFile(macro)) files.add(macro);
        Path levelMacro = levelDir.resolve("macro.inc.c");
        if (Files.isRegularFile(levelMacro)) files.add(levelMacro);

        String sharedScripts = "";
        Path shared = sm64Root.resolve("levels").resolve("scripts.c");
        if (Files.isRegularFile(shared)) sharedScripts = Sm64Objects.stripComments(Files.readString(shared));

        for (Path f : files) {
            String src = Sm64Objects.stripComments(Files.readString(f));
            if (f.getFileName().toString().equals("script.c")) src = Sm64Objects.areaBlock(src, area, sharedScripts);
            Matcher m = OBJECT.matcher(src);
            while (m.find()) {
                String model = m.group(1);
                String kind = kindFrom(model);
                if (kind == null) continue;
                out.add(new Spawn(kind,
                        Float.parseFloat(m.group(2)), Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)),
                        Integer.parseInt(m.group(6)), model));
            }
            m = SPECIAL.matcher(src);
            while (m.find()) {
                String preset = m.group(1);
                String kind = kindFrom(preset);
                if (kind == null) continue;
                int yaw = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
                out.add(new Spawn(kind,
                        Float.parseFloat(m.group(2)), Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)),
                        yaw, preset));
            }
            m = MACRO.matcher(src);
            while (m.find()) {
                String preset = m.group(1);
                String kind = kindFrom(preset);
                if (kind == null) continue;
                out.add(new Spawn(kind,
                        Float.parseFloat(m.group(3)), Float.parseFloat(m.group(4)), Float.parseFloat(m.group(5)),
                        Integer.parseInt(m.group(2)), preset));
            }
        }
        Map<String, Spawn> unique = new LinkedHashMap<>();
        for (Spawn e : out) {
            String key = e.kind() + '|' + Float.floatToIntBits(e.x()) + '|' + Float.floatToIntBits(e.y())
                    + '|' + Float.floatToIntBits(e.z()) + '|' + e.yaw();
            unique.putIfAbsent(key, e);
        }
        return new ArrayList<>(unique.values());
    }

    /** Lower-case name split on '_' with the decomp prefixes (model_, macro_, special_, bhv) removed. */
    static Set<String> tokens(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        if (s.startsWith("bhv")) s = s.substring(3).replaceAll("([a-z])([0-9])", "$1_$2");
        Set<String> out = new LinkedHashSet<>(Arrays.asList(s.split("_")));
        out.removeAll(Set.of("model", "macro", "special", ""));
        return out;
    }

    /** Map a decomp model / preset name to an enemy kind, or null when it is not an enemy. Token based. */
    static String kindFrom(String name) {
        Set<String> t = tokens(name);
        if (t.contains("bobomb")) {
            if (t.contains("buddy")) return null; // friendly pink NPC
            return t.contains("king") ? "king_bobomb" : "bobomb";
        }
        if (t.contains("bully")) return t.contains("boss") || t.contains("big") || t.contains("chill") ? "big_bully" : "bully";
        if (t.contains("goomba")) return "goomba";
        // MODEL_KOOPA_WITH_SHELL is the enemy; a bare MODEL_KOOPA_SHELL is the item
        if (t.contains("koopa")) return t.contains("shell") && !t.contains("with") && !t.contains("without") ? null : "koopa";
        if (t.contains("boo") || t.contains("boos")) return t.contains("big") ? "big_boo" : "boo";
        if (t.contains("mr") && t.contains("i")) return "mr_i";
        if (t.contains("mr") && t.contains("blizzard")) return "mr_blizzard";
        if (t.contains("amp")) return "amp";
        if (t.contains("whomp")) return "whomp";
        if (t.contains("thwomp") || t.contains("grindel")) return "thwomp";
        if (t.contains("tox")) return "tox_box";
        if (t.contains("piranha")) return "piranha";
        if (t.contains("chomp")) return t.contains("gate") ? null : "chain_chomp";
        // only the boss itself: MODEL_BOWSER / special_bowser. MODEL_LLL_BOWSER_PIECE_*, CASTLE_BOWSER_TRAP, BOWSER_BOMB... are not.
        if (t.contains("bowser")) return t.size() == 1 ? "bowser" : null;
        if (t.contains("lakitu")) return "lakitu";
        if (t.contains("spiny")) return "spiny";
        if (t.contains("fly") && t.contains("guy")) return "fly_guy";
        if (t.contains("heave")) return "heave_ho";
        if (t.contains("chuckya")) return "chuckya";
        if (t.contains("scuttlebug")) return "scuttlebug";
        if (t.contains("moneybag")) return "moneybag";
        if (t.contains("pokey")) return "pokey";
        if (t.contains("spindrift")) return "spindrift";
        if (t.contains("skeeter")) return "skeeter";
        if (t.contains("snufit")) return "snufit";
        if (t.contains("eyerok")) return "eyerok";
        if (t.contains("wiggler")) return "wiggler";
        if (t.contains("klepto")) return "klepto";
        if (t.contains("ukiki")) return "ukiki";
        if (t.contains("bullet") && t.contains("bill")) return "bullet_bill";
        if (t.contains("mad") && t.contains("piano")) return "mad_piano";
        if (t.contains("bookend")) return "bookend";
        if (t.contains("haunted") && t.contains("chair")) return "haunted_chair";
        if (t.contains("monty") && t.contains("mole") && !t.contains("hole")) return "monty_mole";
        if (t.contains("sushi")) return "sushi";
        if (t.contains("bubba")) return "bubba";
        if (t.contains("unagi")) return "unagi";
        if (t.contains("dorrie")) return "dorrie";
        if (t.contains("fire") && t.contains("spitter")) return "fire_spitter";
        return null;
    }
}
