package com.unrealcraft.client.retro;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.sm64.Sm64Png;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads textures extracted from the SM64 ROM (PNG files in the decomp tree) into Minecraft's texture manager. */
public final class Sm64Textures {
    private static final Map<String, Identifier> CACHE = new HashMap<>();

    private Sm64Textures() {}

    /** @return the texture id, or null when the PNG has not been extracted / cannot be read. */
    public static Identifier get(Path root, String rel) {
        String key = root + "|" + rel;
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Identifier id = null;
        Path p = root.resolve(rel);
        if (Files.isRegularFile(p)) {
            try {
                Sm64Png png = Sm64Png.read(p);
                NativeImage img = new NativeImage(NativeImage.Format.RGBA, png.width, png.height, false);
                for (int y = 0; y < png.height; y++) for (int x = 0; x < png.width; x++) {
                    int c = png.argb[y * png.width + x];
                    int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    img.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r); // NativeImage stores ABGR
                }
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                id = Unrealcraft.id("sm64/" + Integer.toHexString(key.hashCode()) + "_" + CACHE.size());
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            } catch (Exception e) {
                Unrealcraft.LOGGER.warn("SM64 texture failed {}: {}", p, e.toString());
            }
        } else {
            Unrealcraft.LOGGER.warn("SM64 texture missing (extract your ROM assets first): {}", p);
        }
        CACHE.put(key, id);
        return id;
    }
}
