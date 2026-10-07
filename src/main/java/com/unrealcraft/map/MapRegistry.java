package com.unrealcraft.map;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.config.UnrealConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Scans {@link UnrealConfig#mapsDir} (and optional glbDir) for loadable maps.
 * Default project folder: {@code <gameDir>/../out/ue1_maps} is NOT assumed —
 * set mapsDir in config/unrealcraft.json to e.g. C:/Users/.../unrealcraft/out/ue1_maps
 */
public final class MapRegistry {
    private static final List<MapInfo> MAPS = new ArrayList<>();
    private static final Set<String> EXTS = Set.of(
            "glb", "t3d", "unr", "bsp", "pk3", "json"
    );

    private MapRegistry() {}

    public static void reload() {
        MAPS.clear();
        UnrealConfig cfg = UnrealConfig.get();
        List<Path> roots = new ArrayList<>();

        // Primary: mapsDir
        if (cfg.mapsDir != null && !cfg.mapsDir.isBlank()) {
            Path p = Path.of(cfg.mapsDir.trim());
            if (Files.isDirectory(p)) roots.add(p);
            else Unrealcraft.LOGGER.warn("mapsDir not found: {}", p);
        }

        // Always also scan config/unrealcraft/maps
        Path cfgMaps = FabricLoader.getInstance().getConfigDir().resolve("unrealcraft").resolve("maps");
        try {
            Files.createDirectories(cfgMaps);
            if (roots.stream().noneMatch(r -> r.toAbsolutePath().equals(cfgMaps.toAbsolutePath()))) {
                roots.add(cfgMaps);
            }
        } catch (IOException e) {
            Unrealcraft.LOGGER.warn("Could not create config maps dir: {}", e.toString());
        }

        // Optional extra glbDir
        if (cfg.glbDir != null && !cfg.glbDir.isBlank()) {
            Path p = Path.of(cfg.glbDir.trim());
            if (Files.isDirectory(p) && roots.stream().noneMatch(r -> r.toAbsolutePath().equals(p.toAbsolutePath()))) {
                roots.add(p);
            }
        }

        for (Path root : roots) {
            scanRoot(root);
        }

        // Built-in asset marker if nothing named Unreal
        if (MAPS.stream().noneMatch(m -> m.id().equalsIgnoreCase("Unreal"))) {
            MAPS.add(new MapInfo("Unreal", "Unreal (built-in GLB asset)", null, null, "glb", true));
        }

        MAPS.sort(Comparator.comparing(MapInfo::id, String.CASE_INSENSITIVE_ORDER));
        Unrealcraft.LOGGER.info("Maps available: {} (roots={})", MAPS.size(), roots);
    }

    private static void scanRoot(Path root) {
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String fn = file.getFileName().toString();
                int dot = fn.lastIndexOf('.');
                if (dot <= 0) return;
                String ext = fn.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!EXTS.contains(ext)) return;

                // Prefer companion mesh json only when not a standalone map type
                if ("json".equals(ext)) {
                    String lower = fn.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith("_mesh.json") && !lower.endsWith("_t3d_mesh.json")) return;
                    String id = stripExt(fn);
                    if (id.toLowerCase(Locale.ROOT).endsWith("_mesh")) {
                        id = id.substring(0, id.length() - 5);
                    }
                    if (id.toLowerCase(Locale.ROOT).endsWith("_t3d")) {
                        id = id.substring(0, id.length() - 4);
                    }
                    if (containsId(id)) return;
                    MAPS.add(new MapInfo(id, id + " (mesh json)", file, file, "mesh_json", false));
                    return;
                }

                String id = stripExt(fn);
                // Prefer richer formats: if same id already present with glb, keep both only if different formats
                Path json = file.resolveSibling(id + "_mesh.json");
                if (!Files.isRegularFile(json)) json = file.resolveSibling(id + "_t3d_mesh.json");
                if (!Files.isRegularFile(json)) json = file.resolveSibling(id + ".json");
                if (!Files.isRegularFile(json)) json = null;

                String format = ext;
                String label = id + " [" + ext.toUpperCase(Locale.ROOT) + "]";
                // Allow same id with different extensions (Endgame.unr vs Endgame.glb)
                String uniqueId = containsId(id) ? id + "_" + ext : id;
                MAPS.add(new MapInfo(uniqueId, label, file, json, format, false));
            });
        } catch (IOException e) {
            Unrealcraft.LOGGER.warn("Scan failed {}: {}", root, e.toString());
        }
    }

    private static boolean containsId(String id) {
        return MAPS.stream().anyMatch(m -> m.id().equalsIgnoreCase(id));
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    public static List<MapInfo> all() {
        if (MAPS.isEmpty()) reload();
        return Collections.unmodifiableList(MAPS);
    }

    public static Optional<MapInfo> find(String id) {
        return all().stream().filter(m -> m.id().equalsIgnoreCase(id)).findFirst();
    }

    public static Path primaryMapsDir() {
        UnrealConfig cfg = UnrealConfig.get();
        if (cfg.mapsDir != null && !cfg.mapsDir.isBlank()) {
            Path p = Path.of(cfg.mapsDir.trim());
            if (Files.isDirectory(p)) return p;
        }
        return FabricLoader.getInstance().getConfigDir().resolve("unrealcraft").resolve("maps");
    }
}
