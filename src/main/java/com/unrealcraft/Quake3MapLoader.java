package com.unrealcraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.net.RetroMapPayload;
import com.unrealcraft.net.RetroMapServer;
import com.unrealcraft.quake3.Quake3Bsp;
import com.unrealcraft.quake3.Quake3Collision;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Load Quake 3 .pk3 / loose .bsp. The map is drawn client-side (see RetroMapClient) from a payload,
 * so it no longer depends on entity tracking range, section culling or the player's height.
 * Placement: map X/Z origin = player's block, lowest drawable point at mapSeaY, player is moved onto the spawn floor.
 */
public final class Quake3MapLoader {
    private static final List<BlockPos> COLLISION = new ArrayList<>();

    private Quake3MapLoader() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            var root = CommandManager.literal("q3");
            root.then(CommandManager.literal("load")
                    .then(CommandManager.argument("pk3", StringArgumentType.string())
                            .executes(c -> load(c.getSource(), StringArgumentType.getString(c, "pk3")))
                            .then(CommandManager.argument("map", StringArgumentType.string())
                                    .executes(c -> load(c.getSource(),
                                            StringArgumentType.getString(c, "pk3"),
                                            StringArgumentType.getString(c, "map"))))));
            root.then(CommandManager.literal("unload").executes(c -> {
                clear(c.getSource().getWorld());
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    public static int load(ServerCommandSource src, String pk3) {
        return load(src, pk3, null);
    }

    public static int load(ServerCommandSource src, String pk3, String wanted) {
        ServerWorld w = src.getWorld();
        Sm64MapLoader.clear(w);
        clear(w);
        Path p = Path.of(pk3);
        if (!Files.isRegularFile(p)) {
            src.sendError(Text.literal("PK3/BSP not found: " + p));
            return 0;
        }
        try {
            Path bspPath = p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bsp")
                    ? p : extractBsp(p, wanted);
            if (bspPath == null) {
                src.sendError(Text.literal("No BSP found in PK3"));
                return 0;
            }
            UnrealConfig cfg = UnrealConfig.get();
            Quake3Bsp bsp = Quake3Bsp.load(bspPath);
            var tris = bsp.triangles();
            if (tris.isEmpty()) {
                src.sendError(Text.literal("Q3 BSP has no drawable geometry (" + bspPath.getFileName() + ")"));
                return 0;
            }
            var pl = bsp.placement(tris, cfg.q3UnitsPerBlock);

            var pos = src.getPosition();
            int sea = cfg.mapSeaY;
            int ax = (int) Math.floor(pos.x), az = (int) Math.floor(pos.z);
            BlockPos origin = new BlockPos(ax, sea, az);

            int placed = 0;
            if (cfg.buildCollision) {
                // NOTIFY_LISTENERS only: skip neighbour updates, which is what makes bulk barrier placement slow
                for (int[] v : Quake3Collision.voxels(tris, pl, cfg.collisionBlockLimit)) {
                    BlockPos bp = origin.add(v[0], v[1], v[2]);
                    if (w.isOutOfHeightLimit(bp)) continue;
                    w.setBlockState(bp, Blocks.BARRIER.getDefaultState(), Block.NOTIFY_LISTENERS);
                    COLLISION.add(bp);
                    placed++;
                }
            }

            String abs = bspPath.toAbsolutePath().toString();
            RetroMapServer.show(w.getServer(), new RetroMapPayload("load", "q3", abs, ax, sea, az));

            ServerPlayerEntity player = src.getPlayer();
            if (player != null && pl.hasSpawn()) {
                player.requestTeleport(ax + 0.5, sea + pl.spawnFeetLocalY() + 0.05, az + 0.5);
            }

            final String name = bspPath.getFileName().toString();
            final int fPlaced = placed;
            src.sendFeedback(() -> Text.literal(String.format(
                    "Quake 3 map §e%s§r: %d tris, anchor (%d, %d, %d), %d collision blocks%s",
                    name, tris.size(), ax, sea, az, fPlaced,
                    pl.hasSpawn() ? ", moved to spawn" : ", no spawn point (centred)")), false);
            Unrealcraft.LOGGER.info("Q3 loaded {} tris={} anchor=({},{},{}) placement={}", name, tris.size(), ax, sea, az, pl);
            return 1;
        } catch (Exception e) {
            Unrealcraft.LOGGER.error("Q3 load failed", e);
            src.sendError(Text.literal("Q3 load failed: " + e.getMessage()));
            return 0;
        }
    }

    private static Path extractBsp(Path pk3, String wanted) throws IOException {
        try (ZipFile z = new ZipFile(pk3.toFile())) {
            ZipEntry chosen = null;
            if (wanted != null && !wanted.isBlank()) {
                String n = wanted.toLowerCase(Locale.ROOT);
                if (!n.endsWith(".bsp")) n += ".bsp";
                chosen = z.getEntry(n);
                if (chosen == null) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry x = en.nextElement();
                        String xn = x.getName().toLowerCase(Locale.ROOT);
                        if (xn.endsWith("/" + n) || xn.equals(n) || xn.endsWith(n)) {
                            chosen = x;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry x = en.nextElement();
                    if (!x.isDirectory() && x.getName().toLowerCase(Locale.ROOT).endsWith(".bsp")) {
                        chosen = x;
                        break;
                    }
                }
            }
            if (chosen == null) return null;
            Path dir = pk3.getParent() == null ? Path.of("q3_cache") : pk3.getParent().resolve(".unrealcraft_q3");
            Files.createDirectories(dir);
            Path out = dir.resolve(Paths.get(chosen.getName()).getFileName().toString());
            Files.copy(z.getInputStream(chosen), out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
    }

    /** Remove the client-side render and the barrier collision of the active Q3 map. */
    public static void clear(ServerWorld w) {
        RetroMapServer.hide(w.getServer(), "q3");
        for (BlockPos p : COLLISION) {
            w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        COLLISION.clear();
    }
}
