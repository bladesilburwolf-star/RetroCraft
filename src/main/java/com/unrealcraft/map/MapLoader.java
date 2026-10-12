package com.unrealcraft.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.unrealcraft.Unrealcraft;
import com.unrealcraft.HalfLifeMapLoader;
import com.unrealcraft.Quake3MapLoader;
import com.unrealcraft.Sm64MapLoader;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.UePropEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Minewind-style: map shell spawns around the player at sea level (Y=63 by default),
 * not at the player's eye height. Orientation forced from config (Z-up → Y-up).
 */
public final class MapLoader {
    private static final List<UUID> LOADED = new ArrayList<>();
    private static final List<BlockPos> COLLISION = new ArrayList<>();

    private MapLoader() {}

    public static void clear(ServerWorld world) {
        Quake3MapLoader.clear(world);
        Sm64MapLoader.clear(world);
        for (UUID id : new ArrayList<>(LOADED)) {
            var e = world.getEntity(id);
            if (e != null) e.discard();
        }
        LOADED.clear();
        for (BlockPos p : new ArrayList<>(COLLISION)) world.setBlockState(p, Blocks.AIR.getDefaultState());
        COLLISION.clear();
    }

    public static int load(ServerPlayerEntity player, MapInfo map) {
        ServerWorld world = player.getServerWorld();
        clear(world);
        Path meshJson = map.jsonPath();

        // Route non-GLB formats to dedicated importers
        if (map.path() != null) {
            String fmt = map.format() == null ? "" : map.format();
            String abs = map.path().toAbsolutePath().toString();
            if ("sm64".equals(fmt)) {
                String id = map.id();
                int cut = id.lastIndexOf('_');
                int area = 1;
                try { area = Integer.parseInt(id.substring(cut + 1)); } catch (Exception ignored) {}
                return Sm64MapLoader.load(player.getCommandSource(), map.path().getFileName().toString(), area);
            }
            if ("pk3".equals(fmt)) {
                return Quake3MapLoader.load(player.getCommandSource(), abs);
            }
            if ("bsp".equals(fmt)) {
                // IBSP = Quake 3; version 30 = GoldSrc
                try (var in = java.nio.file.Files.newInputStream(map.path())) {
                    byte[] hdr = in.readNBytes(8);
                    if (hdr.length >= 4 && hdr[0]=='I' && hdr[1]=='B' && hdr[2]=='S' && hdr[3]=='P') {
                        return Quake3MapLoader.load(player.getCommandSource(), abs);
                    }
                } catch (Exception ignored) {}
                return HalfLifeMapLoader.start(player.getCommandSource(), abs);
            }
            if ("t3d".equals(fmt)) {
                try {
                    meshJson = T3dImporter.importFile(map.path(), UnrealConfig.get().mapUnitsPerBlock);
                } catch (Exception e) {
                    player.sendMessage(Text.literal("§cT3D import failed: " + e.getMessage()), false);
                    return 0;
                }
            } else if ("unr".equals(fmt)) {
                // Need converted GLB/mesh json beside the file
                if (meshJson != null && Files.isRegularFile(meshJson)) {
                    // fall through using mesh json as geometry source
                } else if (map.glbPath() == null) {
                    Path siblingGlb = map.path().resolveSibling(map.id().split("_")[0] + ".glb");
                    // try base name without format suffix
                    String base = map.path().getFileName().toString();
                    int d = base.lastIndexOf('.');
                    if (d > 0) base = base.substring(0, d);
                    siblingGlb = map.path().resolveSibling(base + ".glb");
                    if (!Files.isRegularFile(siblingGlb)) {
                        player.sendMessage(Text.literal(
                                "§cNo GLB/mesh JSON for " + map.displayName()
                                        + ". Convert T3D/UNR first, or pick a .glb/.bsp/.pk3."), false);
                        return 0;
                    }
                }
            }
        }


        UnrealConfig cfg = UnrealConfig.get();
        Vec3d me = player.getPos();
        // Minewind: anchor XZ to player, Y to sea level
        double ax = Math.floor(me.x);
        double az = Math.floor(me.z);
        double ay = cfg.mapSeaY;

        // World maps are not item models: preserve their dimensions and normalize source
        // units into Minecraft blocks. The GLB renderer centres X/Z and grounds the map.
        float scale = 1.0f;

        String modelName = map.id();
        if (map.glbPath() == null && meshJson != null) {
            modelName = "json:" + meshJson.toAbsolutePath();
        }
        if (map.glbPath() != null) {
            Path parent = map.glbPath().getParent();
            if (parent != null) {
                cfg.glbDir = parent.toAbsolutePath().toString();
                UnrealConfig.save();
            }
            String fn = map.glbPath().getFileName().toString();
            int dot = fn.lastIndexOf('.');
            modelName = dot > 0 ? fn.substring(0, dot) : fn;
        }

        UePropEntity shell = new UePropEntity(world, modelName, scale);
        shell.setMapMode(true);
        shell.refreshPositionAndAngles(ax + 0.5, ay, az + 0.5, 0, 0);
        shell.setRotationDegrees(0, 0, 0);

        // Optional: map JSON can override quat / scale / sea offset
        if (meshJson != null && Files.isRegularFile(meshJson)) {
            try {
                String raw = Files.readString(meshJson);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("map_scale")) scale = root.get("map_scale").getAsFloat();
                // GLB map rotation is applied during mesh import; do not rotate the entity a second time.
                shell.setPropScale(scale);

                if (root.has("objects") && root.get("objects").isJsonArray()) {
                    for (JsonElement el : root.getAsJsonArray("objects")) {
                        if (!el.isJsonObject()) continue;
                        JsonObject o = el.getAsJsonObject();
                        if (!o.has("type") || !o.has("pos")) continue;
                        String type = o.get("type").getAsString();
                        if (!"PlayerStart".equals(type) && !type.contains("Start")) continue;
                        JsonArray pos = o.getAsJsonArray("pos");
                        if (pos.size() < 3) continue;
                        double px = ax + pos.get(0).getAsDouble();
                        double py = ay + pos.get(1).getAsDouble();
                        double pz = az + pos.get(2).getAsDouble();
                        player.requestTeleport(px, py, pz);
                        break;
                    }
                }
            } catch (Exception e) {
                Unrealcraft.LOGGER.warn("Map JSON: {}", e.toString());
            }
        }

        world.spawnEntity(shell);
        LOADED.add(shell.getUuid());

        if (cfg.buildCollision) {
            List<BlockPos> blocks = map.glbPath() != null
                    ? GlbCollision.build(map.glbPath(), new BlockPos((int) ax, (int) ay, (int) az))
                    : GlbCollision.buildJson(meshJson, new BlockPos((int) ax, (int) ay, (int) az));
            for (BlockPos bp : blocks) {
                world.setBlockState(bp, Blocks.BARRIER.getDefaultState());
                COLLISION.add(bp);
            }
        }

        player.sendMessage(Text.literal(String.format(
                "Loaded §e%s§r at sea level (%d, %d, %d) scale=%.3f rot=(%.0f,%.0f,%.0f)",
                map.displayName(), (int) ax, (int) ay, (int) az, scale,
                cfg.mapRotX, cfg.mapRotY, cfg.mapRotZ)), false);
        return 1;
    }
}
