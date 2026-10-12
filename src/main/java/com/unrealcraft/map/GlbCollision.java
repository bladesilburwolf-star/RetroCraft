package com.unrealcraft.map;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.client.GlbLoader;
import com.unrealcraft.client.GlbMesh;
import com.unrealcraft.client.GlbOptions;
import com.unrealcraft.config.UnrealConfig;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.unrealcraft.client.MiniJson;

/** Server-side surface voxelization for world GLBs. It intentionally creates only surface collision. */
public final class GlbCollision {
    private GlbCollision() {}

    public static List<BlockPos> build(Path glb, BlockPos origin) {
        UnrealConfig cfg = UnrealConfig.get();
        if (!cfg.buildCollision || !Files.isRegularFile(glb)) return List.of();
        try {
            GlbOptions o = GlbOptions.mapDefaults(cfg.mapUnitsPerBlock, cfg.mapRotX, cfg.mapRotY, cfg.mapRotZ);
            GlbMesh mesh = GlbLoader.load(Files.readAllBytes(glb), o);
            Set<Long> seen = new HashSet<>();
            List<BlockPos> out = new ArrayList<>();
            float[] p = mesh.pos;
            for (int i = 0; i + 8 < p.length && out.size() < cfg.collisionBlockLimit; i += 9) {
                surface(p, i, origin, seen, out, cfg.collisionBlockLimit);
            }
            Unrealcraft.LOGGER.info("GLB collision {}: {} blocks", glb.getFileName(), out.size());
            return out;
        } catch (Exception e) {
            Unrealcraft.LOGGER.warn("GLB collision failed for {}: {}", glb, e.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<BlockPos> buildJson(Path json, BlockPos origin) {
        UnrealConfig cfg=UnrealConfig.get();
        if(!cfg.buildCollision||json==null||!Files.isRegularFile(json))return List.of();
        try{
            Object ro=MiniJson.parse(Files.readString(json));
            if(!(ro instanceof java.util.Map<?,?> root)||!(root.get("triangles") instanceof List<?> tris))return List.of();
            float sourceScale="unreal_t3d_raw".equals(root.get("source"))
                    ? 1.0f/Math.max(cfg.mapUnitsPerBlock,0.001f) : 1.0f;
            List<float[]> all=new ArrayList<>();
            float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
            for(Object to:tris){
                if(!(to instanceof List<?> t)||t.size()<3)continue;
                float[] q=new float[9];boolean ok=true;
                for(int k=0;k<3;k++){Object vo=t.get(k);if(!(vo instanceof List<?> a)||a.size()<3){ok=false;break;}for(int j=0;j<3;j++)q[k*3+j]=((Number)a.get(j)).floatValue()*sourceScale;}
                if(!ok)continue;all.add(q);
                for(int i=0;i<9;i+=3){minX=Math.min(minX,q[i]);maxX=Math.max(maxX,q[i]);minY=Math.min(minY,q[i+1]);maxY=Math.max(maxY,q[i+1]);minZ=Math.min(minZ,q[i+2]);maxZ=Math.max(maxZ,q[i+2]);}
            }
            float cx=(minX+maxX)*.5f,cz=(minZ+maxZ)*.5f,fy=minY;
            Set<Long> seen=new HashSet<>();List<BlockPos> out=new ArrayList<>();
            for(float[] q:all){
                for(int i=0;i<9;i+=3){q[i]-=cx;q[i+1]-=fy;q[i+2]-=cz;}
                surface(q,0,origin,seen,out,cfg.collisionBlockLimit);
                if(out.size()>=cfg.collisionBlockLimit)break;
            }
            return out;
        }catch(Exception e){Unrealcraft.LOGGER.warn("JSON collision failed {}: {}",json,e.toString());return List.of();}
    }

    private static void surface(float[] p, int i, BlockPos origin, Set<Long> seen, List<BlockPos> out, int limit) {
        float ax=p[i], ay=p[i+1], az=p[i+2], bx=p[i+3], by=p[i+4], bz=p[i+5], cx=p[i+6], cy=p[i+7], cz=p[i+8];
        float ux=bx-ax,uy=by-ay,uz=bz-az,vx=cx-ax,vy=cy-ay,vz=cz-az;
        float nx=uy*vz-uz*vy, ny=uz*vx-ux*vz, nz=ux*vy-uy*vx;
        float an=Math.abs(nx)+Math.abs(ny)+Math.abs(nz);
        if (an < 1e-5f) return;
        int axis = Math.abs(ny)>=Math.abs(nx)&&Math.abs(ny)>=Math.abs(nz)?1:(Math.abs(nx)>=Math.abs(nz)?0:2);
        float minU,maxU,minV,maxV;
        if(axis==1){minU=Math.min(ax,Math.min(bx,cx));maxU=Math.max(ax,Math.max(bx,cx));minV=Math.min(az,Math.min(bz,cz));maxV=Math.max(az,Math.max(bz,cz));}
        else if(axis==0){minU=Math.min(ay,Math.min(by,cy));maxU=Math.max(ay,Math.max(by,cy));minV=Math.min(az,Math.min(bz,cz));maxV=Math.max(az,Math.max(bz,cz));}
        else {minU=Math.min(ax,Math.min(bx,cx));maxU=Math.max(ax,Math.max(bx,cx));minV=Math.min(ay,Math.min(by,cy));maxV=Math.max(ay,Math.max(by,cy));}
        int u0=(int)Math.floor(minU),u1=(int)Math.ceil(maxU),v0=(int)Math.floor(minV),v1=(int)Math.ceil(maxV);
        float d=nx*ax+ny*ay+nz*az;
        for(int iu=u0;iu<=u1;iu++) for(int iv=v0;iv<=v1;iv++){
            float u=iu+.5f,v=iv+.5f,x,y,z;
            if(axis==1){x=u;z=v;if(Math.abs(ny)<1e-5)continue;y=(d-nx*x-nz*z)/ny;}
            else if(axis==0){y=u;z=v;if(Math.abs(nx)<1e-5)continue;x=(d-ny*y-nz*z)/nx;}
            else{x=u;y=v;if(Math.abs(nz)<1e-5)continue;z=(d-nx*x-ny*y)/nz;}
            if(!inside(ax,ay,az,bx,by,bz,cx,cy,cz,x,y,z,axis)) continue;
            BlockPos bp=BlockPos.ofFloored(x+origin.getX(),y+origin.getY(),z+origin.getZ());
            if(seen.add(bp.asLong())) { out.add(bp); if(out.size()>=limit)return; }
        }
    }

    private static boolean inside(float ax,float ay,float az,float bx,float by,float bz,float cx,float cy,float cz,
                                  float x,float y,float z,int axis) {
        float a1,a2,b1,b2,c1,c2,p1,p2;
        if(axis==1){a1=ax;a2=az;b1=bx;b2=bz;c1=cx;c2=cz;p1=x;p2=z;}
        else if(axis==0){a1=ay;a2=az;b1=by;b2=bz;c1=cy;c2=cz;p1=y;p2=z;}
        else{a1=ax;a2=ay;b1=bx;b2=by;c1=cx;c2=cy;p1=x;p2=y;}
        float den=(b2-c2)*(a1-c1)+(c1-b1)*(a2-c2);
        if(Math.abs(den)<1e-6f)return false;
        float u=((b2-c2)*(p1-c1)+(c1-b1)*(p2-c2))/den;
        float v=((c2-a2)*(p1-c1)+(a1-c1)*(p2-c2))/den;
        return u>=-.02f&&v>=-.02f&&u+v<=1.02f;
    }
}
