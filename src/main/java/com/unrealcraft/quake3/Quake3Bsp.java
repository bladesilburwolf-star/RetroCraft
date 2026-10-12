package com.unrealcraft.quake3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quake 3 BSP v46 reader (pure Java, no Minecraft types).
 * Output coordinates are already converted from Q3 (X right, Y forward, Z up) to Minecraft axes
 * (x, y=up, z=-forward). Sky / nodraw / clip-type surfaces are dropped; patch faces are tessellated.
 */
public final class Quake3Bsp {
    public record Vertex(float x,float y,float z,float nx,float ny,float nz,float u,float v) {}
    public record Face(int texture,int type,int vertex,int vertexCount,int meshVert,int meshVertCount,int patchWidth,int patchHeight) {}
    public record Shader(String name,int flags,int contents) {}
    /** xyz = 3 verts in Minecraft axes (raw Q3 units); nrm = unit face normal (Minecraft axes). */
    public record Tri(String texture,float[] xyz,float[] uv,float[] nrm) {}

    /**
     * Maps raw Q3 units to blocks: local = (p - ref) / scale.
     * ref.y is the lowest drawable point, ref.xz is the spawn point (or the map centre when no spawn exists).
     * spawnFeetLocalY is the floor height under the spawn in local blocks (NaN when there is no spawn).
     */
    public record Placement(float refX,float refY,float refZ,float scale,float spawnFeetLocalY) {
        public boolean hasSpawn() { return !Float.isNaN(spawnFeetLocalY); }
        public float lx(float x){ return (x-refX)/scale; }
        public float ly(float y){ return (y-refY)/scale; }
        public float lz(float z){ return (z-refZ)/scale; }
    }

    private static final int SURF_SKY=0x4, SURF_NODRAW=0x80, SURF_HINT=0x100, SURF_SKIP=0x200;
    private static final Pattern HIDDEN_SHADER=Pattern.compile(
            "^textures/common/(clip|weapclip|missileclip|botclip|nodraw|nodrawnonsolid|caulk|caulknodrop|hint|skip|trigger|cluster|areaportal|origin|invisible|fog.*)$");
    private static final Pattern ENTITY_BLOCK=Pattern.compile("\\{([^}]*)}");
    private static final Pattern KV=Pattern.compile("\"([^\"]+)\"\\s+\"([^\"]*)\"");

    private final List<Vertex> vertices;
    private final List<Integer> meshVerts;
    private final List<Face> faces;
    private final List<Shader> shaders;
    private final String entities;

    private Quake3Bsp(List<Vertex> v,List<Integer> mv,List<Face> f,List<Shader> s,String ents){vertices=v;meshVerts=mv;faces=f;shaders=s;entities=ents;}

    public static Quake3Bsp load(Path p) throws IOException {
        byte[] d=Files.readAllBytes(p);
        if(d.length<8+17*8)throw new IOException("Q3 BSP truncated");
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id=new byte[4];b.get(id);
        if(!"IBSP".equals(new String(id,StandardCharsets.US_ASCII)))throw new IOException("Not a Quake 3 BSP");
        int version=b.getInt();if(version!=46)throw new IOException("Unsupported Q3 BSP version "+version);
        int[] off=new int[17],len=new int[17];
        for(int i=0;i<17;i++){off[i]=b.getInt();len[i]=b.getInt();if(off[i]<0||len[i]<0||(long)off[i]+len[i]>d.length)throw new IOException("Invalid BSP lump "+i);}
        String ents=new String(d,off[0],len[0],StandardCharsets.ISO_8859_1);
        List<Shader> shaders=readShaders(d,off[1],len[1]);
        List<Vertex> verts=new ArrayList<>(len[10]/44);ByteBuffer vb=slice(d,off[10],len[10]);
        while(vb.remaining()>=44){
            float x=vb.getFloat(),y=vb.getFloat(),z=vb.getFloat();
            float u=vb.getFloat(),v=vb.getFloat();
            vb.getFloat(); vb.getFloat(); // lightmap UV
            float nx=vb.getFloat(),ny=vb.getFloat(),nz=vb.getFloat();
            vb.getInt(); // vertex colour
            verts.add(new Vertex(x,y,z,nx,ny,nz,u,v));
        }
        return new Quake3Bsp(verts,readInts(d,off[11],len[11]),readFaces(d,off[13],len[13]),shaders,ents);
    }

    /** First player spawn in raw Q3 coordinates {x,y,z(up),angle}, or null. info_player_start wins over deathmatch. */
    public float[] spawnOrigin(){
        float[] dm=null;
        Matcher m=ENTITY_BLOCK.matcher(entities);
        while(m.find()){
            String cls=null,org=null,ang=null;
            Matcher kv=KV.matcher(m.group(1));
            while(kv.find()){
                switch(kv.group(1)){case "classname"->cls=kv.group(2);case "origin"->org=kv.group(2);case "angle"->ang=kv.group(2);default->{}}
            }
            if(cls==null||org==null)continue;
            boolean start=cls.equals("info_player_start"),deathmatch=cls.equals("info_player_deathmatch");
            if(!start&&!deathmatch)continue;
            String[] s=org.trim().split("\\s+");
            if(s.length<3)continue;
            try{
                float[] r={Float.parseFloat(s[0]),Float.parseFloat(s[1]),Float.parseFloat(s[2]),ang==null?0f:Float.parseFloat(ang)};
                if(start)return r;
                if(dm==null)dm=r;
            }catch(NumberFormatException ignored){}
        }
        return dm;
    }

    public Placement placement(List<Tri> tris,float scale){
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(Tri t:tris)for(int i=0;i<9;i+=3){
            minX=Math.min(minX,t.xyz[i]);maxX=Math.max(maxX,t.xyz[i]);
            minY=Math.min(minY,t.xyz[i+1]);
            minZ=Math.min(minZ,t.xyz[i+2]);maxZ=Math.max(maxZ,t.xyz[i+2]);
        }
        float s=Math.max(scale,.001f);
        float[] sp=spawnOrigin();
        if(sp!=null){
            // Q3 player origin is 24 units above the floor it stands on
            float feetY=sp[2]-24f;
            return new Placement(mcx(sp[0]),minY,mcz(sp[1]),s,(feetY-minY)/s);
        }
        return new Placement((minX+maxX)*.5f,minY,(minZ+maxZ)*.5f,s,Float.NaN);
    }

    public List<Tri> triangles(){
        List<Tri> out=new ArrayList<>();
        for(Face f:faces){
            if(!drawable(f.texture))continue;
            if(f.type==2){addPatch(out,f);continue;}
            if(f.type!=1&&f.type!=3) continue;
            if(f.vertex<0||f.vertex+f.vertexCount>vertices.size()) continue;
            if(f.meshVert<0||f.meshVert+f.meshVertCount>meshVerts.size()) continue;
            for(int i=0;i+2<f.meshVertCount;i+=3){
                int a=f.vertex+meshVerts.get(f.meshVert+i), b=f.vertex+meshVerts.get(f.meshVert+i+1), c=f.vertex+meshVerts.get(f.meshVert+i+2);
                if(a<0||b<0||c<0||a>=vertices.size()||b>=vertices.size()||c>=vertices.size())continue;
                addTri(out,shaderName(f.texture),vertices.get(a),vertices.get(b),vertices.get(c));
            }
        }
        return out;
    }

    /** Tessellate the quadratic Bezier control grid used by Quake 3 patch faces. */
    private void addPatch(List<Tri> out, Face f) {
        int w=f.patchWidth,h=f.patchHeight;
        if(w<3||h<3||(w&1)==0||(h&1)==0||f.vertex<0||f.vertex+(long)w*h>vertices.size())return;
        final int steps=8;
        String texture=shaderName(f.texture);
        for(int py=0;py+2<h;py+=2) for(int px=0;px+2<w;px+=2) {
            Vertex[][] grid=new Vertex[steps+1][steps+1];
            for(int y=0;y<=steps;y++) for(int x=0;x<=steps;x++) {
                float u=x/(float)steps,v=y/(float)steps;
                float[] bu=bernstein(u),bv=bernstein(v);
                float[] a=new float[5];
                for(int gy=0;gy<3;gy++)for(int gx=0;gx<3;gx++){
                    Vertex q=vertices.get(f.vertex+(py+gy)*w+px+gx);float k=bu[gx]*bv[gy];
                    a[0]+=q.x*k;a[1]+=q.y*k;a[2]+=q.z*k;a[3]+=q.u*k;a[4]+=q.v*k;
                }
                grid[y][x]=new Vertex(a[0],a[1],a[2],0,0,0,a[3],a[4]);
            }
            for(int y=0;y<steps;y++)for(int x=0;x<steps;x++){
                Vertex a=grid[y][x],b=grid[y][x+1],c=grid[y+1][x+1],d=grid[y+1][x];
                addTri(out,texture,a,b,c);addTri(out,texture,a,c,d);
            }
        }
    }
    private static float[] bernstein(float t){float s=1-t;return new float[]{s*s,2*s*t,t*t};}

    private static void addTri(List<Tri> out,String texture,Vertex a,Vertex b,Vertex c){
        float[] p={mcx(a.x),mcy(a.z),mcz(a.y),mcx(b.x),mcy(b.z),mcz(b.y),mcx(c.x),mcy(c.z),mcz(c.y)};
        float ux=p[3]-p[0],uy=p[4]-p[1],uz=p[5]-p[2],vx=p[6]-p[0],vy=p[7]-p[1],vz=p[8]-p[2];
        // Q3 triangles are wound opposite to the right-hand rule, so the outward normal is (c-a)x(b-a)
        float nx=vy*uz-vz*uy,ny=vz*ux-vx*uz,nz=vx*uy-vy*ux;
        float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(len<1e-6f)return; // degenerate
        out.add(new Tri(texture,p,new float[]{a.u,a.v,b.u,b.v,c.u,c.v},new float[]{nx/len,ny/len,nz/len}));
    }

    /** False for sky, nodraw, hint/skip and common/clip-style shaders that must neither render nor collide. */
    private boolean drawable(int i){
        if(i<0||i>=shaders.size())return true;
        Shader s=shaders.get(i);
        if((s.flags&(SURF_SKY|SURF_NODRAW|SURF_HINT|SURF_SKIP))!=0)return false;
        return !HIDDEN_SHADER.matcher(s.name.toLowerCase(java.util.Locale.ROOT)).matches();
    }

    private String shaderName(int i){return i>=0&&i<shaders.size()?shaders.get(i).name:"q3_missing";}
    private static float mcx(float x){return x;}
    private static float mcy(float z){return z;}
    private static float mcz(float y){return -y;}

    private static List<Shader> readShaders(byte[]d,int o,int l){ByteBuffer b=slice(d,o,l);List<Shader>r=new ArrayList<>();while(b.remaining()>=72){byte[]n=new byte[64];b.get(n);int e=0;while(e<n.length&&n[e]!=0)e++;String s=new String(n,0,e,StandardCharsets.UTF_8);int flags=b.getInt();int contents=b.getInt();r.add(new Shader(s,flags,contents));}return r;}
    private static List<Face> readFaces(byte[]d,int o,int l){
        ByteBuffer b=slice(d,o,l);List<Face> r=new ArrayList<>(l/104);
        while(b.remaining()>=104){
            int tex=b.getInt(),effect=b.getInt(),type=b.getInt(),vertex=b.getInt(),vertexCount=b.getInt(),mesh=b.getInt(),meshCount=b.getInt();
            b.position(b.position()+68); // lightmap data, origin, vectors, normal
            int patchWidth=b.getInt(),patchHeight=b.getInt();
            r.add(new Face(tex,type,vertex,vertexCount,mesh,meshCount,patchWidth,patchHeight));
        }
        return r;
    }
    private static List<Integer> readInts(byte[]d,int o,int l){ByteBuffer b=slice(d,o,l);List<Integer>r=new ArrayList<>(l/4);while(b.remaining()>=4)r.add(b.getInt());return r;}
    private static ByteBuffer slice(byte[]d,int o,int l){return ByteBuffer.wrap(d,o,l).slice().order(ByteOrder.LITTLE_ENDIAN);}
    public int faceCount(){return faces.size();}
}
