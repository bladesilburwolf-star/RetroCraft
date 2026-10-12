package com.unrealcraft.client;

import com.unrealcraft.Unrealcraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;

public final class GlbRender {
    private GlbRender() {}

    public static final class Prepared {
        final GlbMesh mesh;
        final Identifier fallback;
        final Identifier[] textures;
        final int[][] groups;

        Prepared(GlbMesh mesh, Identifier fallback, Identifier[] textures, int[][] groups) {
            this.mesh = mesh;
            this.fallback = fallback;
            this.textures = textures;
            this.groups = groups;
        }
    }

    public static Prepared prepare(String key, GlbMesh mesh, Identifier fallback) {
        int n = mesh.images != null ? mesh.images.size() : 0;
        Identifier[] textures = new Identifier[n];
        for (int i = 0; i < n; i++) {
            textures[i] = register(key, i, mesh.images.get(i));
        }
        int tris = mesh.triangleCount();
        int[] counts = new int[n + 1];
        int[] groupOf = new int[tris];
        for (int t = 0; t < tris; t++) {
            int img = (mesh.triImage != null && t < mesh.triImage.length) ? mesh.triImage[t] : -1;
            int g = (img >= 0 && img < n && textures[img] != null) ? img + 1 : 0;
            groupOf[t] = g;
            counts[g]++;
        }
        int[][] groups = new int[counts.length][];
        int[] fill = new int[counts.length];
        for (int g = 0; g < groups.length; g++) groups[g] = new int[counts[g]];
        for (int t = 0; t < tris; t++) groups[groupOf[t]][fill[groupOf[t]]++] = t;
        return new Prepared(mesh, fallback, textures, groups);
    }

    private static Identifier register(String key, int index, byte[] data) {
        if (data == null || data.length < 8) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) return null;
            NativeImage out = new NativeImage(NativeImage.Format.RGBA, img.getWidth(), img.getHeight(), false);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int argb = img.getRGB(x, y);
                    int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
                    out.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            Identifier id = Identifier.of(Unrealcraft.MOD_ID,
                    "dyn/" + key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_") + "/" + index);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(out));
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    public static void draw(Prepared p, MatrixStack.Entry entry, VertexConsumerProvider consumers, int overlay, int light) {
        GlbMesh m = p.mesh;
        if (m.pos == null || m.pos.length < 9) return;
        for (int g = 0; g < p.groups.length; g++) {
            int[] tris = p.groups[g];
            if (tris.length == 0) continue;
            Identifier texture = g == 0 ? p.fallback : p.textures[g - 1];
            if (texture == null) texture = p.fallback;
            if (texture == null) continue;
            VertexConsumer vc = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture));
            for (int t : tris) {
                for (int k = 0; k < 4; k++) {
                    int v = t * 3 + Math.min(k, 2);
                    int pi = v * 3, ui = v * 2;
                    if (pi + 2 >= m.pos.length) continue;
                    float u = (m.uv != null && ui + 1 < m.uv.length) ? m.uv[ui] : 0f;
                    float vv = (m.uv != null && ui + 1 < m.uv.length) ? m.uv[ui + 1] : 0f;
                    float nx = (m.nrm != null && pi + 2 < m.nrm.length) ? m.nrm[pi] : 0f;
                    float ny = (m.nrm != null && pi + 2 < m.nrm.length) ? m.nrm[pi + 1] : 1f;
                    float nz = (m.nrm != null && pi + 2 < m.nrm.length) ? m.nrm[pi + 2] : 0f;
                    vc.vertex(entry, m.pos[pi], m.pos[pi + 1], m.pos[pi + 2])
                            .color(255, 255, 255, 255)
                            .texture(u, vv)
                            .overlay(overlay)
                            .light(light)
                            .normal(entry, nx, ny, nz);
                }
            }
        }
    }
}
