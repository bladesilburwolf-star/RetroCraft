package com.unrealcraft.client;

import com.unrealcraft.map.MapInfo;
import com.unrealcraft.map.MapRegistry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/** Simple map picker — open with K. */
public class MapSelectScreen extends Screen {
    private final Screen parent;
    private List<MapInfo> maps = List.of();
    private int scroll;

    public MapSelectScreen(Screen parent) {
        super(Text.literal("Unrealcraft Maps"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        MapRegistry.reload();
        maps = MapRegistry.all();
        scroll = 0;
        int y = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(width / 2 - 50, y, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reload list"), b -> {
            MapRegistry.reload();
            maps = MapRegistry.all();
        }).dimensions(width / 2 - 160, y, 100, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Click a map to /ue map load  ·  Hotkey: K"),
                width / 2, 28, 0xAAAAAA);

        int top = 48;
        int rowH = 22;
        int visible = Math.max(1, (height - 90) / rowH);
        for (int i = 0; i < visible && scroll + i < maps.size(); i++) {
            MapInfo m = maps.get(scroll + i);
            int y = top + i * rowH;
            boolean hover = mouseX >= width / 2 - 160 && mouseX <= width / 2 + 160
                    && mouseY >= y && mouseY <= y + rowH - 2;
            int bg = hover ? 0x8844AAFF : 0x66000000;
            ctx.fill(width / 2 - 160, y, width / 2 + 160, y + rowH - 2, bg);
            String tag = m.fromAssets() ? " §7[asset]" : " §8[" + m.format() + "]";
            ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§e" + m.id() + "§r — " + m.displayName() + tag),
                    width / 2 - 150, y + 6, 0xFFFFFF);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int top = 48;
        int rowH = 22;
        int visible = Math.max(1, (height - 90) / rowH);
        for (int i = 0; i < visible && scroll + i < maps.size(); i++) {
            int y = top + i * rowH;
            if (mouseX >= width / 2 - 160 && mouseX <= width / 2 + 160
                    && mouseY >= y && mouseY <= y + rowH - 2) {
                MapInfo m = maps.get(scroll + i);
                if (client != null && client.player != null) {
                    client.player.networkHandler.sendChatCommand("ue map load " + m.id());
                }
                close();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        if (vert > 0) scroll = Math.max(0, scroll - 1);
        else if (vert < 0) scroll = Math.min(Math.max(0, maps.size() - 1), scroll + 1);
        return true;
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
