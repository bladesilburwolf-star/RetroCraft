package com.unrealcraft;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.UePropEntity;
import com.unrealcraft.map.MapInfo;
import com.unrealcraft.map.MapLoader;
import com.unrealcraft.map.MapRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class PropCommands {
    private PropCommands() {}

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {
            var root = CommandManager.literal("ue");

            root.then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("model", StringArgumentType.string())
                            .executes(ctx -> spawn(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "model"),
                                    UnrealConfig.get().defaultScale))
                            .then(CommandManager.argument("scale", FloatArgumentType.floatArg(0.01f, 100f))
                                    .executes(ctx -> spawn(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "model"),
                                            FloatArgumentType.getFloat(ctx, "scale"))))));

            root.then(CommandManager.literal("reloadconfig")
                    .executes(ctx -> {
                        UnrealConfig.load();
                        MapRegistry.reload();
                        ctx.getSource().sendFeedback(() -> Text.literal("Config + map list reloaded"), false);
                        return 1;
                    }));

            root.then(CommandManager.literal("maps")
                    .executes(ctx -> {
                        MapRegistry.reload();
                        var list = MapRegistry.all();
                        ctx.getSource().sendFeedback(() -> Text.literal("Maps (" + list.size() + "):"), false);
                        for (MapInfo m : list) {
                            String where = m.fromAssets() ? " [asset]" : " [file]";
                            ctx.getSource().sendFeedback(
                                    () -> Text.literal("  §e" + m.id() + "§r — " + m.displayName() + where), false);
                        }
                        return list.size();
                    }));

            root.then(CommandManager.literal("map")
                    .then(CommandManager.literal("list")
                            .executes(ctx -> {
                                MapRegistry.reload();
                                var list = MapRegistry.all();
                                ctx.getSource().sendFeedback(() -> Text.literal("Maps (" + list.size() + "):"), false);
                                for (MapInfo m : list) {
                                    ctx.getSource().sendFeedback(
                                            () -> Text.literal("  §e" + m.id() + "§r — " + m.displayName()), false);
                                }
                                return list.size();
                            }))
                    .then(CommandManager.literal("load")
                            .then(CommandManager.argument("name", StringArgumentType.string())
                                    .executes(ctx -> {
                                        ServerPlayerEntity p = ctx.getSource().getPlayer();
                                        if (p == null) {
                                            ctx.getSource().sendError(Text.literal("Players only"));
                                            return 0;
                                        }
                                        String name = StringArgumentType.getString(ctx, "name");
                                        return MapRegistry.find(name)
                                                .map(m -> MapLoader.load(p, m))
                                                .orElseGet(() -> {
                                                    ctx.getSource().sendError(Text.literal("Unknown map: " + name));
                                                    return 0;
                                                });
                                    })))
                    .then(CommandManager.literal("clear")
                            .executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p == null) return 0;
                                MapLoader.clear(p.getServerWorld());
                                ctx.getSource().sendFeedback(() -> Text.literal("Cleared loaded map props"), false);
                                return 1;
                            })));

            dispatcher.register(root);
        });
    }

    private static int spawn(ServerCommandSource src, String model, float scale) {
        var world = src.getWorld();
        var pos = src.getPosition();
        UePropEntity e = new UePropEntity(world, model, scale);
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, src.getRotation().y, 0);
        world.spawnEntity(e);
        src.sendFeedback(() -> Text.literal("Spawned UE prop '" + model + "' scale=" + scale), false);
        return 1;
    }
}
