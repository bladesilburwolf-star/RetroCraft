package com.unrealcraft;

import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.map.MapRegistry;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Unrealcraft implements ModInitializer {
    public static final String MOD_ID = "unrealcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        UnrealConfig.load();
        MapRegistry.reload();
        ModEntities.register();
        com.unrealcraft.net.RetroMapServer.init();
        ModItems.register();
        PropCommands.init();
        HalfLifeMapLoader.init();
        Quake3MapLoader.init();
        Sm64MapLoader.init();
        LOGGER.info("Unrealcraft loaded — drop UE1 GLBs under assets/unrealcraft/models/glb/");
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
