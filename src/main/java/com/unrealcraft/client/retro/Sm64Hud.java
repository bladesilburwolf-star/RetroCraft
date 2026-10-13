package com.unrealcraft.client.retro;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * SM64-style HUD: lives, stars, coins, 8-wedge power meter, current action.
 * Shown while Mario mode is on, or whenever an SM64 map is active.
 */
public final class Sm64Hud {
    private static int lives = 4;
    private static int stars = 0;
    private static int coins = 0;
    private static int health = 8;

    private Sm64Hud() {}

    public static void init() {
        HudRenderCallback.EVENT.register(Sm64Hud::render);
    }

    public static void setStats(int lives, int stars, int coins, int health) {
        Sm64Hud.lives = Math.max(0, lives);
        Sm64Hud.stars = Math.max(0, stars);
        Sm64Hud.coins = Math.max(0, Math.min(99, coins));
        Sm64Hud.health = Math.max(0, Math.min(8, health));
    }

    public static void addCoin() {
        coins++;
        if (coins >= 100) { coins = 0; lives++; }
    }

    public static void addStar() { stars++; }
    public static void damage(int wedges) { health = Math.max(0, health - wedges); }
    public static void healFull() { health = 8; }
    public static int health() { return health; }
    public static int lives() { return lives; }
    public static int stars() { return stars; }
    public static int coins() { return coins; }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;

        boolean marioOn = MarioClient.isEnabled();
        boolean sm64Map = RetroMapClient.sm64() != null;
        if (!marioOn && !sm64Map) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        var tr = mc.textRenderer;

        int x = 12, y = 10;
        ctx.fill(x - 4, y - 4, x + 100, y + 40, 0x88000000);
        ctx.drawTextWithShadow(tr, "MARIO", x, y, 0xFFFFFF00);
        ctx.drawTextWithShadow(tr, "x" + lives, x + 50, y, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, String.format("* %02d", stars), x, y + 12, 0xFFFFFF00);
        ctx.drawTextWithShadow(tr, String.format("$ x%02d", coins), x, y + 24, 0xFFFFFFFF);

        String label = marioOn ? "MARIO MODE" : "SM64 MAP";
        int rw = tr.getWidth(label);
        ctx.fill(sw - rw - 16, y - 4, sw - 8, y + 14, 0x88000000);
        ctx.drawTextWithShadow(tr, label, sw - rw - 12, y, 0xFFAAFFAA);

        // Power meter — 8 wedges, bottom centre
        int meterW = 8 * 10 + 4;
        int mx = sw / 2 - meterW / 2;
        int my = sh - 40;
        ctx.fill(mx - 6, my - 6, mx + meterW + 6, my + 18, 0x88000000);
        for (int i = 0; i < 8; i++) {
            int col = i < health ? 0xFFE02020 : 0xFF3A1010;
            ctx.fill(mx + i * 10, my, mx + i * 10 + 8, my + 12, col);
        }

        if (marioOn) {
            String action = MarioClient.actionName();
            int aw = tr.getWidth(action);
            ctx.drawTextWithShadow(tr, action, sw / 2 - aw / 2, my - 12, 0xFFCCFFCC);
        }
    }
}
