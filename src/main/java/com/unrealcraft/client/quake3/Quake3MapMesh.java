package com.unrealcraft.client.quake3;

import com.unrealcraft.quake3.Quake3Bsp;
import java.util.ArrayList;
import java.util.List;

public final class Quake3MapMesh {
    public record Tri(String texture,float[] pos,float[] uv){}
    public final List<Tri> tris;
    private Quake3MapMesh(List<Tri> t){tris=t;}
    public static Quake3MapMesh build(Quake3Bsp bsp,float unitsPerBlock){
        List<Quake3Bsp.Tri> raw=bsp.triangles(); List<Tri> out=new ArrayList<>();
        if(raw.isEmpty())return new Quake3MapMesh(out);
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(var t:raw)for(int i=0;i<t.xyz().length;i+=3){minX=Math.min(minX,t.xyz()[i]);maxX=Math.max(maxX,t.xyz()[i]);minY=Math.min(minY,t.xyz()[i+1]);maxY=Math.max(maxY,t.xyz()[i+1]);minZ=Math.min(minZ,t.xyz()[i+2]);maxZ=Math.max(maxZ,t.xyz()[i+2]);}
        float cx=(minX+maxX)*.5f,cz=(minZ+maxZ)*.5f,fy=minY,s=Math.max(unitsPerBlock,.001f);
        for(var t:raw){float[] p=t.xyz().clone();for(int i=0;i<p.length;i+=3){p[i]=(p[i]-cx)/s;p[i+1]=(p[i+1]-fy)/s;p[i+2]=(p[i+2]-cz)/s;}out.add(new Tri(t.texture(),p,t.uv()));}
        return new Quake3MapMesh(out);
    }
    public java.util.Map<String,List<Tri>> byTexture(){java.util.Map<String,List<Tri>>r=new java.util.LinkedHashMap<>();for(Tri t:tris)r.computeIfAbsent(t.texture(),k->new ArrayList<>()).add(t);return r;}
}
