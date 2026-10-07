package com.unrealcraft;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.Quake3MapEntity;
import com.unrealcraft.quake3.Quake3Collision;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Load Quake 3 .pk3 / loose .bsp around the player at sea level (Minewind-style).
 */
public final class Quake3MapLoader {
    private static final List<BlockPos> COLLISION = new ArrayList<>();
    private static int entityId = -1;

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
            root.then(CommandManager.literal("unload").executes(c -> unload(c.getSource())));
            dispatcher.register(root);
        });
    }

    public static int load(ServerCommandSource src, String pk3) {
        return load(src, pk3, null);
    }

    public static int load(ServerCommandSource src, String pk3, String wanted) {
        unload(src);
        Path p = Path.of(pk3);
        if (!Files.isRegularFile(p)) {
            src.sendError(Text.literal("PK3/BSP not found: " + p));
            return 0;
        }
        try {
            Path bsp = p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bsp")
                    ? p : extractBsp(p, wanted);
            if (bsp == null) {
                src.sendError(Text.literal("No BSP found in PK3"));
                return 0;
            }
            ServerWorld w = src.getWorld();
            var pos = src.getPosition();
            int sea = UnrealConfig.get().mapSeaY;
            double ax = Math.floor(pos.x) + 0.5;
            double az = Math.floor(pos.z) + 0.5;

            Quake3MapEntity e = new Quake3MapEntity(w, bsp.toAbsolutePath().toString());
            e.refreshPositionAndAngles(ax, sea, az, 0, 0);
            w.spawnEntity(e);
            entityId = e.getId();

            if (UnrealConfig.get().buildCollision) {
                BlockPos origin = new BlockPos((int) Math.floor(pos.x), sea, (int) Math.floor(pos.z));
                for (BlockPos bp : Quake3Collision.build(bsp, origin)) {
                    w.setBlockState(bp, Blocks.BARRIER.getDefaultState());
                    COLLISION.add(bp);
                }
            }

            final String name = bsp.getFileName().toString();
            src.sendFeedback(() -> Text.literal(
                    "Quake 3 map §e" + name + "§r at sea level ("
                            + (int) ax + ", " + sea + ", " + (int) az + ")"), false);
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

    private static int unload(ServerCommandSource src) {
        ServerWorld w = src.getWorld();
        if (entityId >= 0) {
            var e = w.getEntityById(entityId);
            if (e != null) e.discard();
            entityId = -1;
        }
        for (BlockPos p : COLLISION) {
            w.setBlockState(p, Blocks.AIR.getDefaultState());
        }
        int n = COLLISION.size();
        COLLISION.clear();
        return n;
    }
}
