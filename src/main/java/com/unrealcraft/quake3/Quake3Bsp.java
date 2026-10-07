package com.unrealcraft.quake3;

import com.unrealcraft.Unrealcraft;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Quake 3 BSP v46 reader. Renders polygon/mesh faces; patch surfaces are skipped for now. */
public final class Quake3Bsp {
    public record Vertex(float x,float y,float z,float nx,float ny,float nz,float u,float v) {}
    public record Face(int texture,int type,int vertex,int vertexCount,int meshVert,int meshVertCount,int patchWidth,int patchHeight) {}
    public record Shader(String name,int flags,int contents) {}
    public record Tri(String texture,float[] xyz,float[] uv) {}
    private final List<Vertex> vertices;
    private final List<Integer> meshVerts;
    private final List<Face> faces;
    private final List<Shader> shaders;

    private Quake3Bsp(List<Vertex> v,List<Integer> mv,List<Face> f,List<Shader> s){vertices=v;meshVerts=mv;faces=f;shaders=s;}

    public static Quake3Bsp load(Path p) throws IOException {
        byte[] d=Files.readAllBytes(p);
        if(d.length<8+17*8)throw new IOException("Q3 BSP truncated");
        ByteBuffer b=ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id=new byte[4];b.get(id);
        if(!"IBSP".equals(new String(id,StandardCharsets.US_ASCII)))throw new IOException("Not a Quake 3 BSP");
        int version=b.getInt();if(version!=46)throw new IOException("Unsupported Q3 BSP version "+version);
        int[] off=new int[17],len=new int[17];
        for(int i=0;i<17;i++){off[i]=b.getInt();len[i]=b.getInt();if(off[i]<0||len[i]<0||(long)off[i]+len[i]>d.length)throw new IOException("Invalid BSP lump "+i);}
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
        return new Quake3Bsp(verts,readInts(d,off[11],len[11]),readFaces(d,off[13],len[13]),shaders);
    }

    public List<Tri> triangles(){
        List<Tri> out=new ArrayList<>();
        for(Face f:faces){
            if (f.type == 2) {
                addPatch(out, f);
                continue;
            }
            if(f.type!=1&&f.type!=3) continue;
            if(f.vertex<0||f.vertex+f.vertexCount>vertices.size()) continue;
            if(f.meshVert<0||f.meshVert+f.meshVertCount>meshVerts.size()) continue;
            for(int i=0;i+2<f.meshVertCount;i+=3){
                int a=f.vertex+meshVerts.get(f.meshVert+i), b=f.vertex+meshVerts.get(f.meshVert+i+1), c=f.vertex+meshVerts.get(f.meshVert+i+2);
                if(a<0||b<0||c<0||a>=vertices.size()||b>=vertices.size()||c>=vertices.size())continue;
                Vertex va=vertices.get(a),vb=vertices.get(b),vc=vertices.get(c);
                out.add(new Tri(shaderName(f.texture),new float[]{mcx(va.x),mcy(va.z),mcz(va.y),mcx(vb.x),mcy(vb.z),mcz(vb.y),mcx(vc.x),mcy(vc.z),mcz(vc.y)},
                        new float[]{va.u,va.v,vb.u,vb.v,vc.u,vc.v}));
            }
        }
        return out;
    }

    /** Tessellate the quadratic Bezier control grid used by Quake 3 patch faces. */
    private void addPatch(List<Tri> out, Face f) {
        int w=f.patchWidth,h=f.patchHeight;
        if(w<3||h<3||(w&1)==0||(h&1)==0||f.vertex<0||f.vertex+(long)w*h>vertices.size())return;
        final int steps=8;
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
            String texture=shaderName(f.texture);
            for(int y=0;y<steps;y++)for(int x=0;x<steps;x++){
                Vertex a=grid[y][x],b=grid[y][x+1],c=grid[y+1][x+1],d=grid[y+1][x];
                addTri(out,texture,a,b,c);addTri(out,texture,a,c,d);
            }
        }
    }
    private static float[] bernstein(float t){float s=1-t;return new float[]{s*s,2*s*t,t*t};}
    private static void addTri(List<Tri> out,String texture,Vertex a,Vertex b,Vertex c){
        out.add(new Tri(texture,new float[]{mcx(a.x),mcy(a.z),mcz(a.y),mcx(b.x),mcy(b.z),mcz(b.y),mcx(c.x),mcy(c.z),mcz(c.y)},new float[]{a.u,a.v,b.u,b.v,c.u,c.v}));
    }
    private String shaderName(int i){return i>=0&&i<shaders.size()?shaders.get(i).name:"q3_missing";}
    private static float mcx(float x){return x;}
    private static float mcy(float z){return z;}
    private static float mcz(float y){return -y;}

    private static List<Shader> readShaders(byte[]d,int o,int l){ByteBuffer b=slice(d,o,l);List<Shader>r=new ArrayList<>();while(b.remaining()>=72){byte[]n=new byte[64];b.get(n);int e=0;while(e<n.length&&n[e]!=0)e++;String s=new String(n,0,e,StandardCharsets.UTF_8);r.add(new Shader(s,b.getInt(),b.getInt()));}return r;}
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
