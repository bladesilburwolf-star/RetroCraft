package com.unrealcraft.client.halflife;

import com.unrealcraft.halflife.HalfLifeBsp;
import java.util.*;

/** Exact GoldSrc BSP face mesh. Faces are convex and are triangulated directly from surfedges. */
public final class HalfLifeMapMesh {
    public record Tri(String texture, float[] pos, float[] uv) {}
    public final List<Tri> tris;
    private HalfLifeMapMesh(List<Tri> tris){this.tris=tris;}

    public static HalfLifeMapMesh build(HalfLifeBsp bsp, float unitsPerBlock) {
        List<HalfLifeBsp.Polygon> polys=bsp.polygons(); List<Tri> out=new ArrayList<>();
        if(polys.isEmpty()) return new HalfLifeMapMesh(out);
        float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(HalfLifeBsp.Polygon p:polys) for(int i=0;i<p.xyz().length;i+=3){minX=Math.min(minX,p.xyz()[i]);maxX=Math.max(maxX,p.xyz()[i]);minY=Math.min(minY,p.xyz()[i+1]);maxY=Math.max(maxY,p.xyz()[i+1]);minZ=Math.min(minZ,p.xyz()[i+2]);maxZ=Math.max(maxZ,p.xyz()[i+2]);}
        float cx=(minX+maxX)*.5f, cy=(minY+maxY)*.5f, cz=(minZ+maxZ)*.5f;
        for(HalfLifeBsp.Polygon p:polys){
            int n=p.xyz().length/3; if(n<3)continue;
            for(int i=1;i<n-1;i++){
                float[] pos=new float[9],uv=new float[6];
                int[] ids={0,i,i+1};
                for(int k=0;k<3;k++){int j=ids[k];float x=p.xyz()[j*3],y=p.xyz()[j*3+1],z=p.xyz()[j*3+2];pos[k*3]=(x-cx)/unitsPerBlock;pos[k*3+1]=(z-cz)/unitsPerBlock;pos[k*3+2]=(y-cy)/unitsPerBlock;uv[k*2]=p.uv()[j*2];uv[k*2+1]=p.uv()[j*2+1];}
                out.add(new Tri(p.texture(),pos,uv));
            }
        }
        return new HalfLifeMapMesh(out);
    }
    public Map<String,List<Tri>> byTexture(){Map<String,List<Tri>> r=new LinkedHashMap<>();for(Tri t:tris)r.computeIfAbsent(t.texture(),k->new ArrayList<>()).add(t);return r;}
}
