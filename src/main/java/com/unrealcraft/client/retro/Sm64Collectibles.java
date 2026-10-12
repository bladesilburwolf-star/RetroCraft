package com.unrealcraft.client.retro;

import com.unrealcraft.sm64.Sm64Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.List;

/**
 * Client-side coin / star pickup while Mario mode is on.
 * Removes the prop from the active list and bumps the HUD.
 */
public final class Sm64Collectibles {
    private static final double PICKUP_R = 1.2;

    private Sm64Collectibles() {}

    public static void tick() {
        if (!MarioClient.isEnabled()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        RetroMapClient.Active a = RetroMapClient.sm64();
        if (a == null || a.props == null || a.props.isEmpty() || a.mesh == null) return;

        Vec3d p = mc.player.getPos();
        var pl = a.mesh.placement;
        // props list may be immutable — replace with mutable if needed
        List<Sm64Objects.Placement> list = a.props;
        if (!(list instanceof java.util.ArrayList)) {
            list = new java.util.ArrayList<>(list);
            a.props = list;
        }
        Iterator<Sm64Objects.Placement> it = list.iterator();
        while (it.hasNext()) {
            Sm64Objects.Placement prop = it.next();
            String k = prop.kind();
            if (!k.contains("coin") && !"star".equals(k)) continue;
            double wx = a.x + pl.lx(prop.x());
            double wy = a.y + pl.ly(prop.y());
            double wz = a.z + pl.lz(prop.z());
            double dx = p.x - wx, dy = p.y - wy, dz = p.z - wz;
            if (dx * dx + dy * dy + dz * dz > PICKUP_R * PICKUP_R) continue;
            it.remove();
            if ("star".equals(k)) Sm64Hud.addStar();
            else Sm64Hud.addCoin();
        }
    }
}
