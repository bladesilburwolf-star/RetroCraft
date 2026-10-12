package com.unrealcraft.halflife;

import com.unrealcraft.Unrealcraft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** GoldSrc/Quake 1 BSP v30 geometry reader. Geometry is taken directly from BSP faces. */
public final class HalfLifeBsp {
    public static final int LUMP_COUNT = 15;
    public record Vec3(float x, float y, float z) {}
    public record TexInfo(float[] s, float sShift, float[] t, float tShift, int mipTex, int flags) {}
    public record Face(int plane, int side, int firstEdge, int edgeCount, int texInfo, int[] styles, int lightOffset) {}
    public record Edge(int v0, int v1) {}
    public record Model(int[] headNodes, int visLeaves, int firstFace, int faceCount) {}
    public record Polygon(String texture, float[] xyz, float[] uv, int textureWidth, int textureHeight) {}
    public final String source;
    public final List<Vec3> vertices;
    public final List<Edge> edges;
    public final List<Integer> surfEdges;
    public final List<TexInfo> texInfos;
    public final List<Face> faces;
    public final List<Model> models;
    public final List<String> textureNames;
    private final List<HalfLifeWad> externalWads;
    private final List<HalfLifeWad.Texture> embeddedTextures;

    private HalfLifeBsp(String source, List<Vec3> vertices, List<Edge> edges, List<Integer> surfEdges,
                        List<TexInfo> texInfos, List<Face> faces, List<Model> models,
                        List<String> textureNames, List<HalfLifeWad.Texture> embeddedTextures, List<HalfLifeWad> externalWads) {
        this.source = source; this.vertices = vertices; this.edges = edges; this.surfEdges = surfEdges;
        this.texInfos = texInfos; this.faces = faces; this.models = models; this.textureNames = textureNames;
        this.embeddedTextures = embeddedTextures; this.externalWads = externalWads;
    }

    public static HalfLifeBsp load(Path path, Path externalWadPath) throws IOException { return load(path, externalWadPath == null ? List.of() : List.of(externalWadPath)); }

    public static HalfLifeBsp load(Path path, List<Path> externalWadPaths) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < 4 + LUMP_COUNT * 8) throw new IOException("BSP is truncated");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt();
        if (version != 30) throw new IOException("Unsupported GoldSrc BSP version " + version + " (expected 30)");
        int[] off = new int[LUMP_COUNT], len = new int[LUMP_COUNT];
        for (int i = 0; i < LUMP_COUNT; i++) { off[i] = b.getInt(); len[i] = b.getInt(); if (off[i] < 0 || len[i] < 0 || off[i] + (long)len[i] > data.length) throw new IOException("Invalid BSP lump " + i); }

        List<Vec3> vertices = readVertices(data, off[3], len[3]);
        List<Edge> edges = readEdges(data, off[12], len[12]);
        List<Integer> surfEdges = readInts(data, off[13], len[13]);
        List<TexInfo> texInfos = readTexInfo(data, off[6], len[6]);
        List<Face> faces = readFaces(data, off[7], len[7]);
        List<Model> models = readModels(data, off[14], len[14]);
        TexturePack textures = readTextures(data, off[2], len[2]);
        List<HalfLifeWad> external = new ArrayList<>();
        for (Path wadPath : externalWadPaths) if (wadPath != null && Files.exists(wadPath)) {
            try { external.add(HalfLifeWad.load(wadPath)); } catch (IOException e) { Unrealcraft.LOGGER.warn("External HL1 WAD failed {}: {}", wadPath, e.getMessage()); }
        }
        Unrealcraft.LOGGER.info("HL1 BSP {}: {} verts, {} faces, {} texinfo, {} textures", path.getFileName(), vertices.size(), faces.size(), texInfos.size(), textures.names.size());
        return new HalfLifeBsp(path.toString(), vertices, edges, surfEdges, texInfos, faces, models, textures.names, textures.textures, external);
    }

    public HalfLifeWad.Texture texture(int index) {
        if (index >= 0 && index < embeddedTextures.size() && embeddedTextures.get(index) != null) return embeddedTextures.get(index);
        if (index >= 0 && index < textureNames.size()) for (HalfLifeWad wad : externalWads) { HalfLifeWad.Texture t = wad.texture(textureNames.get(index)).orElse(null); if (t != null) return t; }
        return null;
    }

    public List<Polygon> polygons() {
        List<Polygon> out = new ArrayList<>();
        int worldFirst = 0, worldCount = faces.size();
        if (!models.isEmpty()) { worldFirst = Math.max(0, models.get(0).firstFace()); worldCount = Math.min(faces.size() - worldFirst, Math.max(0, models.get(0).faceCount())); }
        for (int faceIndex = worldFirst; faceIndex < worldFirst + worldCount; faceIndex++) {
            Face f = faces.get(faceIndex);
            if (f.texInfo() < 0 || f.texInfo() >= texInfos.size() || f.firstEdge() < 0 || f.edgeCount() < 3 || f.firstEdge() + f.edgeCount() > surfEdges.size()) continue;
            TexInfo ti = texInfos.get(f.texInfo());
            if (ti.mipTex() < 0 || ti.mipTex() >= textureNames.size()) continue;
            HalfLifeWad.Texture tex = texture(ti.mipTex());
            int tw = tex != null ? tex.width() : 64, th = tex != null ? tex.height() : 64;
            float[] xyz = new float[f.edgeCount() * 3], uv = new float[f.edgeCount() * 2];
            boolean ok = true;
            for (int i = 0; i < f.edgeCount(); i++) {
                int se = surfEdges.get(f.firstEdge() + i);
                int edgeIndex = Math.abs(se);
                if (edgeIndex < 0 || edgeIndex >= edges.size()) { ok = false; break; }
                Edge e = edges.get(edgeIndex);
                int vi = se >= 0 ? e.v0() : e.v1();
                if (vi < 0 || vi >= vertices.size()) { ok = false; break; }
                Vec3 v = vertices.get(vi);
                xyz[i*3] = v.x(); xyz[i*3+1] = v.y(); xyz[i*3+2] = v.z();
                uv[i*2] = (v.x()*ti.s()[0] + v.y()*ti.s()[1] + v.z()*ti.s()[2] + ti.sShift()) / tw;
                uv[i*2+1] = (v.x()*ti.t()[0] + v.y()*ti.t()[1] + v.z()*ti.t()[2] + ti.tShift()) / th;
            }
            if (ok) out.add(new Polygon(textureNames.get(ti.mipTex()), xyz, uv, tw, th));
        }
        return out;
    }

    private record TexturePack(List<String> names, List<HalfLifeWad.Texture> textures) {}
    private static TexturePack readTextures(byte[] data, int off, int len) {
        if (len < 4) return new TexturePack(new ArrayList<>(), new ArrayList<>());
        ByteBuffer b = slice(data, off, len); int count = b.getInt();
        if (count < 0 || count > 8192 || b.remaining() < count * 4L) return new TexturePack(new ArrayList<>(), new ArrayList<>());
        int[] offsets = new int[count]; for (int i=0;i<count;i++) offsets[i]=b.getInt();
        List<String> names = new ArrayList<>(Collections.nCopies(count, "")); List<HalfLifeWad.Texture> tex = new ArrayList<>(Collections.nCopies(count, null));
        for (int i=0;i<count;i++) {
            int p=offsets[i]; if (p<0 || p+40>len) continue;
            ByteBuffer t=slice(data, off+p, len-p); String name=readName(t,16); names.set(i,name);
            int w=t.getInt(),h=t.getInt(); int[] mip={t.getInt(),t.getInt(),t.getInt(),t.getInt()};
            if (w<=0||h<=0||w>4096||h>4096||mip[0]<40||mip[0]+w*h>t.capacity()||mip[3]<0||mip[3]+(w/8)*(h/8)+2+768>t.capacity()) continue;
            int[] px=new int[w*h]; int palPos=mip[3]+(w/8)*(h/8); if(palPos+2+768>t.capacity()) continue; int palStart=off+p+palPos+2;
            for(int k=0;k<px.length;k++){int c=data[off+p+mip[0]+k]&255;int q=palStart+c*3;px[k]=0xFF000000|((data[q]&255)<<16)|((data[q+1]&255)<<8)|(data[q+2]&255);} 
            tex.set(i,new HalfLifeWad.Texture(w,h,px));
        }
        return new TexturePack(names,tex);
    }
    private static List<Vec3> readVertices(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<Vec3> r=new ArrayList<>(l/12);while(b.remaining()>=12)r.add(new Vec3(b.getFloat(),b.getFloat(),b.getFloat()));return r;}
    private static List<Edge> readEdges(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<Edge> r=new ArrayList<>(l/4);while(b.remaining()>=4)r.add(new Edge(b.getShort()&65535,b.getShort()&65535));return r;}
    private static List<Integer> readInts(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<Integer> r=new ArrayList<>(l/4);while(b.remaining()>=4)r.add(b.getInt());return r;}
    private static List<TexInfo> readTexInfo(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<TexInfo> r=new ArrayList<>(l/40);while(b.remaining()>=40){float[] s={b.getFloat(),b.getFloat(),b.getFloat()};float ss=b.getFloat();float[] t={b.getFloat(),b.getFloat(),b.getFloat()};float ts=b.getFloat();r.add(new TexInfo(s,ss,t,ts,b.getInt(),b.getInt()));}return r;}
    private static List<Face> readFaces(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<Face> r=new ArrayList<>(l/20);while(b.remaining()>=20){int p=b.getShort()&65535,s=b.getShort()&65535,fe=b.getInt(),ec=b.getShort()&65535,ti=b.getShort()&65535;int[] st={b.get()&255,b.get()&255,b.get()&255,b.get()&255};r.add(new Face(p,s,fe,ec,ti,st,b.getInt()));}return r;}
    private static List<Model> readModels(byte[] d,int o,int l){ByteBuffer b=slice(d,o,l);List<Model> r=new ArrayList<>(l/64);while(b.remaining()>=64){for(int i=0;i<9;i++)b.getFloat();int[] heads={b.getInt(),b.getInt(),b.getInt(),b.getInt()};int vis=b.getInt(),first=b.getInt(),count=b.getInt();r.add(new Model(heads,vis,first,count));}return r;}
    private static ByteBuffer slice(byte[] d,int o,int l){return ByteBuffer.wrap(d,o,l).slice().order(ByteOrder.LITTLE_ENDIAN);}
    private static String readName(ByteBuffer b,int n){byte[] x=new byte[n];b.get(x);int e=0;while(e<n&&x[e]!=0)e++;return new String(x,0,e,StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT);}
}
