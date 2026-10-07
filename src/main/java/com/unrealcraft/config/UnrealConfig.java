package com.unrealcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class UnrealConfig {
    /** External folder containing runtime GLB maps/props. */
    public String mapsDir = "";
    public String glbDir = "";
    /** Default scale for /ue spawn props. */
    public float defaultScale = 1.0f;

    /** Map anchor height; imported maps are centered in X/Z around the anchor. */
    public int mapSeaY = 63;
    /** Source GLB units represented by one Minecraft block. Use 50 for UE1/T3D-scale exports. */
    public float q3UnitsPerBlock = 32.0f;
    public float mapUnitsPerBlock = 50.0f;
    /** GLB map rotation in degrees. Blender-exported GLB is normally already Y-up, so default is zero. */
    public float mapRotX = 0f;
    public float mapRotY = 0f;
    public float mapRotZ = 0f;
    /** Generate invisible barrier collision from imported GLB/T3D map surfaces. */
    public boolean buildCollision = true;
    /** Maximum collision blocks generated for one map. */
    public int collisionBlockLimit = 250000;

    /** GoldSrc BSP v30 map path. */
    public String hl1BspPath = "C:/Sierra/Half-Life/valve/maps/c1a0.bsp";
    /** Optional semicolon-separated GoldSrc WAD3 files. */
    public String hl1WadPath = "C:/Sierra/Half-Life/valve/halflife.wad;C:/Sierra/Half-Life/valve/liquids.wad;C:/Sierra/Half-Life/valve/xeno.wad";
    /** GoldSrc source units represented by one Minecraft block. */
    public float hl1UnitsPerBlock = 32.0f;

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("unrealcraft.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static UnrealConfig INSTANCE;

    public static UnrealConfig get() { if (INSTANCE == null) load(); return INSTANCE; }

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(PATH), UnrealConfig.class);
                if (INSTANCE == null) INSTANCE = new UnrealConfig();
            } else {
                INSTANCE = new UnrealConfig();
                save();
            }
            if (INSTANCE.mapUnitsPerBlock <= 0.001f) INSTANCE.mapUnitsPerBlock = 50f;
            if (INSTANCE.q3UnitsPerBlock <= 0.001f) INSTANCE.q3UnitsPerBlock = 32f;
            if (INSTANCE.hl1UnitsPerBlock <= 0.001f) INSTANCE.hl1UnitsPerBlock = 32f;
            if (INSTANCE.collisionBlockLimit < 1000) INSTANCE.collisionBlockLimit = 250000;
        } catch (Exception e) {
            INSTANCE = new UnrealConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException ignored) {}
    }
}
