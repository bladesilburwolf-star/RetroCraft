package com.unrealcraft;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.net.RetroMapPayload;
import com.unrealcraft.net.RetroMapServer;
import com.unrealcraft.quake3.Quake3Collision;
import com.unrealcraft.sm64.Sm64Level;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * /sm64 dir &lt;path-to-sm64-clone&gt;   - remember where your n64decomp/sm64 clone lives
 * /sm64 load &lt;level&gt; [area]       - e.g. /sm64 load bob   or   /sm64 load castle_grounds 1
 * /sm64 unload
 * Level collision is read from the decomp source tree; same placement rules as Quake 3 maps
 * (lowest point at mapSeaY, Mario's start at the player's X/Z, player moved onto the start).
 */
public final class Sm64MapLoader {
    private static final List<BlockPos> COLLISION = new ArrayList<>();
    // last loaded level, kept so barrier collision can be rebuilt when leaving Mario mode
    private static com.unrealcraft.sm64.Sm64Level.Result lastRes;
    private static com.unrealcraft.quake3.Quake3Bsp.Placement lastPl;
    private static BlockPos lastOrigin;
    private static boolean marioOn;

    private Sm64MapLoader() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            var root = CommandManager.literal("sm64");
            root.then(CommandManager.literal("dir")
                    .then(CommandManager.argument("path", StringArgumentType.string()).executes(c -> {
                        UnrealConfig.get().sm64Dir = StringArgumentType.getString(c, "path");
                        UnrealConfig.save();
                        c.getSource().sendFeedback(() -> Text.literal("SM64 source dir set to " + UnrealConfig.get().sm64Dir), false);
                        return 1;
                    })));
            root.then(CommandManager.literal("load")
                    .then(CommandManager.argument("level", StringArgumentType.word())
                            .executes(c -> load(c.getSource(), StringArgumentType.getString(c, "level"), 1))
                            .then(CommandManager.argument("area", IntegerArgumentType.integer(1, 13))
                                    .executes(c -> load(c.getSource(), StringArgumentType.getString(c, "level"),
                                            IntegerArgumentType.getInteger(c, "area"))))));
            root.then(CommandManager.literal("mario").executes(c -> toggleMario(c.getSource())));
            root.then(CommandManager.literal("unload").executes(c -> {
                clear(c.getSource().getWorld());
                return 1;
            }));
            dispatcher.register(root);
        });
    }

    public static int load(ServerCommandSource src, String level, int area) {
        ServerWorld w = src.getWorld();
        Quake3MapLoader.clear(w);
        clear(w);
        UnrealConfig cfg = UnrealConfig.get();
        if (cfg.sm64Dir == null || cfg.sm64Dir.isBlank()) {
            src.sendError(Text.literal("Set your SM64 decomp clone first: /sm64 dir \"C:/path/to/sm64\""));
            return 0;
        }
        if (!level.matches("[a-z0-9_]+")) {
            src.sendError(Text.literal("Bad level name: " + level));
            return 0;
        }
        Path levelDir = Path.of(cfg.sm64Dir.trim()).resolve("levels").resolve(level);
        if (!Files.isDirectory(levelDir)) {
            src.sendError(Text.literal("Level folder not found: " + levelDir));
            return 0;
        }
        try {
            float upb = cfg.sm64UnitsPerBlock;
            var res = Sm64Level.load(levelDir, area, upb);
            if (res.tris().isEmpty()) {
                src.sendError(Text.literal("No collision triangles in " + level + " area " + area));
                return 0;
            }
            var pl = Sm64Level.placement(res, upb);
            var pos = src.getPosition();
            int sea = cfg.mapSeaY;
            int ax = (int) Math.floor(pos.x), az = (int) Math.floor(pos.z);
            BlockPos origin = new BlockPos(ax, sea, az);

            lastRes = res; lastPl = pl; lastOrigin = origin;
            int placed = (cfg.buildCollision && !marioOn) ? buildCollision(w) : 0;

            RetroMapServer.show(w.getServer(), new RetroMapPayload("load", "sm64",
                    levelDir.toAbsolutePath() + "|" + area, ax, sea, az));

            ServerPlayerEntity player = src.getPlayer();
            if (player != null && pl.hasSpawn()) {
                player.requestTeleport(ax + 0.5, sea + pl.spawnFeetLocalY() + 0.05, az + 0.5);
            }
            final int fPlaced = placed;
            src.sendFeedback(() -> Text.literal(String.format(
                    "SM64 §e%s§r area %d: %d tris, anchor (%d, %d, %d), %d collision blocks%s",
                    level, area, res.tris().size(), ax, sea, az, fPlaced,
                    pl.hasSpawn() ? ", moved to Mario's start" : ", no start point (centred)")), false);
            Unrealcraft.LOGGER.info("SM64 loaded {} area {} tris={} placement={}", level, area, res.tris().size(), pl);
            return 1;
        } catch (Exception e) {
            Unrealcraft.LOGGER.error("SM64 load failed", e);
            src.sendError(Text.literal("SM64 load failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int buildCollision(ServerWorld w) {
        UnrealConfig cfg = UnrealConfig.get();
        int placed = 0;
        for (int[] v : Quake3Collision.voxels(lastRes.tris(), lastPl, cfg.collisionBlockLimit)) {
            BlockPos bp = lastOrigin.add(v[0], v[1], v[2]);
            if (w.isOutOfHeightLimit(bp)) continue;
            w.setBlockState(bp, Blocks.BARRIER.getDefaultState(), Block.NOTIFY_LISTENERS);
            COLLISION.add(bp);
            placed++;
        }
        return placed;
    }

    private static void removeCollision(ServerWorld w) {
        for (BlockPos p : COLLISION) w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        COLLISION.clear();
    }

    /** /sm64 mario: drive the player with the ported SM64 physics (barrier blocks are removed while it is on). */
    private static int toggleMario(ServerCommandSource src) {
        ServerWorld w = src.getWorld();
        if (lastRes == null) {
            src.sendError(Text.literal("Load an SM64 level first: /sm64 load bob"));
            return 0;
        }
        marioOn = !marioOn;
        if (marioOn) removeCollision(w);
        else if (UnrealConfig.get().buildCollision) buildCollision(w);
        RetroMapServer.send(w.getServer(), new RetroMapPayload("mario", "sm64", marioOn ? "on" : "off", 0, 0, 0));
        final boolean on = marioOn;
        src.sendFeedback(() -> Text.literal(on
                ? "Mario mode ON  (WASD move, Space = A jump, Shift = Z, mouse = camera, M = off)"
                : "Mario mode OFF"), false);
        return 1;
    }

    public static void clear(ServerWorld w) {
        RetroMapServer.hide(w.getServer(), "sm64");
        removeCollision(w);
        lastRes = null; lastPl = null; lastOrigin = null; marioOn = false;
    }
}
