package com.unrealcraft.halflife;

import com.unrealcraft.Unrealcraft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** GoldSrc WAD2/WAD3 texture reader. Supports indexed MIPTEX textures and palettes. */
public final class HalfLifeWad {
    public record Texture(int width, int height, int[] argb) {}
    private record Lump(String name, int offset, int size, int type, int compression) {}
    private final byte[] data;
    private final Map<String, Lump> lumps = new HashMap<>();
    private final Map<String, Texture> cache = new HashMap<>();

    private HalfLifeWad(byte[] data) { this.data = data; }

    public static HalfLifeWad load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < 12) throw new IOException("WAD3 header is truncated");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        String magic = ascii(b, 4);
        if (!magic.equals("WAD2") && !magic.equals("WAD3")) throw new IOException("Not a GoldSrc WAD: " + magic);
        int count = b.getInt();
        int dir = b.getInt();
        if (count < 0 || dir < 0 || dir + count * 32L > data.length) throw new IOException("Invalid WAD directory");
        HalfLifeWad wad = new HalfLifeWad(data);
        b.position(dir);
        for (int i = 0; i < count; i++) {
            int off = b.getInt(), diskSize = b.getInt(), size = b.getInt();
            int type = b.get() & 255, compression = b.get() & 255;
            b.getShort();
            String name = name(b, 16);
            if (off >= 0 && diskSize >= 0 && off + (long) diskSize <= data.length) wad.lumps.put(name, new Lump(name, off, size, type, compression));
        }
        Unrealcraft.LOGGER.info("Loaded GoldSrc WAD {} ({} lumps)", path, wad.lumps.size());
        return wad;
    }

    public Optional<Texture> texture(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.toUpperCase(Locale.ROOT);
        Texture cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        Lump l = lumps.get(key);
        if (l == null || l.compression != 0) return Optional.empty();
        Texture t = decode(l);
        if (t != null) cache.put(key, t);
        return Optional.ofNullable(t);
    }

    private Texture decode(Lump l) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data, l.offset, Math.min(l.size, data.length - l.offset)).slice().order(ByteOrder.LITTLE_ENDIAN);
            if (b.remaining() < 40) return null;
            String texName = name(b, 16);
            int w = b.getInt(), h = b.getInt();
            int[] mip = {b.getInt(), b.getInt(), b.getInt(), b.getInt()};
            if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return null;
            if (mip[0] < 40 || mip[0] + w * h > b.capacity()) return null;
            int pixelBase = l.offset + mip[0];
            int palettePos = mip[3] + (w >> 3) * (h >> 3);
            int[] pixels = new int[w * h];
            if (palettePos + 2 > l.size) return null;
            int palCountPos = l.offset + palettePos;
            int palBytes = Math.min(256 * 3, data.length - palCountPos - 2);
            if (palBytes < 768) return null;
            int palStart = palCountPos + 2;
            int[] pal = new int[256];
            for (int i = 0; i < 256; i++) {
                int p = palStart + i * 3;
                pal[i] = 0xFF000000 | ((data[p] & 255) << 16) | ((data[p + 1] & 255) << 8) | (data[p + 2] & 255);
            }
            for (int i = 0; i < pixels.length; i++) pixels[i] = pal[data[pixelBase + i] & 255];
            return new Texture(w, h, pixels);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ascii(ByteBuffer b, int n) { byte[] x = new byte[n]; b.get(x); return new String(x, StandardCharsets.US_ASCII); }
    private static String name(ByteBuffer b, int n) {
        byte[] x = new byte[n]; b.get(x); int end = 0; while (end < n && x[end] != 0) end++;
        return new String(x, 0, end, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT);
    }
}
