package com.unrealcraft.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks which imported maps are active and keeps every client's renderer in sync (including late joiners). */
public final class RetroMapServer {
    private static final Map<String, RetroMapPayload> ACTIVE = new LinkedHashMap<>();

    private RetroMapServer() {}

    public static void init() {
        PayloadTypeRegistry.playS2C().register(RetroMapPayload.ID, RetroMapPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            for (RetroMapPayload p : ACTIVE.values()) ServerPlayNetworking.send(handler.player, p);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ACTIVE.clear());
    }

    public static void show(MinecraftServer server, RetroMapPayload load) {
        ACTIVE.put(load.kind(), load);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) ServerPlayNetworking.send(p, load);
    }

    /** Broadcast a one-off message (not remembered for late joiners). */
    public static void send(MinecraftServer server, RetroMapPayload p) {
        for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) ServerPlayNetworking.send(pl, p);
    }

    public static void hide(MinecraftServer server, String kind) {
        ACTIVE.remove(kind);
        RetroMapPayload c = RetroMapPayload.clear(kind);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) ServerPlayNetworking.send(p, c);
    }
}
