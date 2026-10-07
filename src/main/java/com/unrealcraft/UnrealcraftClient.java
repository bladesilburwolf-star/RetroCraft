package com.unrealcraft;

import com.unrealcraft.client.MapSelectScreen;
import com.unrealcraft.client.UePropRenderer;
import com.unrealcraft.client.halflife.HalfLifeMapRenderer;
import com.unrealcraft.client.quake3.Quake3MapRenderer;
import com.unrealcraft.map.MapRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class UnrealcraftClient implements ClientModInitializer {
    private static KeyBinding openMapsKey;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.UE_PROP, UePropRenderer::new);
        EntityRendererRegistry.register(ModEntities.HL1_MAP, HalfLifeMapRenderer::new);
        EntityRendererRegistry.register(ModEntities.Q3_MAP, Quake3MapRenderer::new);
        MapRegistry.reload();

        openMapsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.unrealcraft.maps",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.unrealcraft"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMapsKey.wasPressed()) {
                if (client.player != null && client.currentScreen == null) {
                    client.setScreen(new MapSelectScreen(null));
                }
            }
        });
    }
}
