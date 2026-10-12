package com.unrealcraft.net;

import com.unrealcraft.Unrealcraft;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Server -> client: draw (or clear) an imported map at a world anchor.
 * action = "load" | "clear", kind = "q3" (hl1 / t3d to follow), path = absolute file path readable by the client.
 */
public record RetroMapPayload(String action, String kind, String path, double x, double y, double z)
        implements CustomPayload {
    public static final Id<RetroMapPayload> ID = new Id<>(Unrealcraft.id("retro_map"));
    public static final PacketCodec<RegistryByteBuf, RetroMapPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RetroMapPayload::action,
            PacketCodecs.STRING, RetroMapPayload::kind,
            PacketCodecs.STRING, RetroMapPayload::path,
            PacketCodecs.DOUBLE, RetroMapPayload::x,
            PacketCodecs.DOUBLE, RetroMapPayload::y,
            PacketCodecs.DOUBLE, RetroMapPayload::z,
            RetroMapPayload::new);

    public static RetroMapPayload clear(String kind) { return new RetroMapPayload("clear", kind, "", 0, 0, 0); }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
