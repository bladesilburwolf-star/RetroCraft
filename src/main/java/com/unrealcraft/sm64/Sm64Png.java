package com.unrealcraft.sm64;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the PNGs written by the decomp's n64graphics tool as plain ARGB, reading raw channels
 * (gray / gray+alpha / RGB / RGBA, 8 bit) so grey values are not colour-managed.
 */
public final class Sm64Png {
    public final int width, height;
    /** ARGB, row-major, top row first. */
    public final int[] argb;

    private Sm64Png(int w, int h, int[] argb) { width = w; height = h; this.argb = argb; }

    public static Sm64Png read(Path p) throws IOException {
        BufferedImage img;
        try (var in = Files.newInputStream(p)) { img = ImageIO.read(in); }
        if (img == null) throw new IOException("Unreadable PNG: " + p);
        int w = img.getWidth(), h = img.getHeight();
        Raster r = img.getRaster();
        int bands = r.getNumBands();
        int[] out = new int[w * h];
        int[] px = new int[bands];
        boolean indexed = img.getColorModel() instanceof java.awt.image.IndexColorModel;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (indexed || bands > 4 || bands < 1 || img.getRaster().getDataBuffer().getDataType() != java.awt.image.DataBuffer.TYPE_BYTE) {
                out[y * w + x] = img.getRGB(x, y);
                continue;
            }
            r.getPixel(x, y, px);
            int a = 255, rr, g, b;
            switch (bands) {
                case 1 -> { rr = g = b = px[0]; }
                case 2 -> { rr = g = b = px[0]; a = px[1]; }
                case 3 -> { rr = px[0]; g = px[1]; b = px[2]; }
                default -> { rr = px[0]; g = px[1]; b = px[2]; a = px[3]; }
            }
            out[y * w + x] = (a & 255) << 24 | (rr & 255) << 16 | (g & 255) << 8 | (b & 255);
        }
        return new Sm64Png(w, h, out);
    }
}
