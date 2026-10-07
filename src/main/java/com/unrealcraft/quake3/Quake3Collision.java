package com.unrealcraft.quake3;

import com.unrealcraft.config.UnrealConfig;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Path;
import java.util.*;

public final class Quake3Collision {
    private Quake3Collision(){}
    public static List<BlockPos> build(Path bsp, BlockPos origin){
        if(!UnrealConfig.get().buildCollision)return List.of();
        try{
            var raw=Quake3Bsp.load(bsp).triangles();
            if(raw.isEmpty())return List.of();
            float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
            for(var t:raw)for(int i=0;i<9;i+=3){minX=Math.min(minX,t.xyz()[i]);maxX=Math.max(maxX,t.xyz()[i]);minY=Math.min(minY,t.xyz()[i+1]);maxY=Math.max(maxY,t.xyz()[i+1]);minZ=Math.min(minZ,t.xyz()[i+2]);maxZ=Math.max(maxZ,t.xyz()[i+2]);}
            float cx=(minX+maxX)*.5f,cz=(minZ+maxZ)*.5f,fy=minY;
            float s=Math.max(UnrealConfig.get().q3UnitsPerBlock,.001f);
            Set<Long> seen=new HashSet<>();List<BlockPos> out=new ArrayList<>();
            for(var t:raw){
                float[] q=t.xyz().clone();for(int i=0;i<9;i+=3){q[i]=(q[i]-cx)/s;q[i+1]=(q[i+1]-fy)/s;q[i+2]=(q[i+2]-cz)/s;}
                surface(q,origin,seen,out,UnrealConfig.get().collisionBlockLimit);if(out.size()>=UnrealConfig.get().collisionBlockLimit)break;
            }
            return out;
        }catch(Exception e){return List.of();}
    }
    private static void surface(float[]q,BlockPos o,Set<Long>seen,List<BlockPos>out,int limit){
        float ax=q[0],ay=q[1],az=q[2],bx=q[3],by=q[4],bz=q[5],cx=q[6],cy=q[7],cz=q[8];
        float ux=bx-ax,uy=by-ay,uz=bz-az,vx=cx-ax,vy=cy-ay,vz=cz-az,nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
        int axis=Math.abs(ny)>=Math.abs(nx)&&Math.abs(ny)>=Math.abs(nz)?1:(Math.abs(nx)>=Math.abs(nz)?0:2);
        float minU,maxU,minV,maxV;if(axis==1){minU=Math.min(ax,Math.min(bx,cx));maxU=Math.max(ax,Math.max(bx,cx));minV=Math.min(az,Math.min(bz,cz));maxV=Math.max(az,Math.max(bz,cz));}
        else if(axis==0){minU=Math.min(ay,Math.min(by,cy));maxU=Math.max(ay,Math.max(by,cy));minV=Math.min(az,Math.min(bz,cz));maxV=Math.max(az,Math.max(bz,cz));}
        else{minU=Math.min(ax,Math.min(bx,cx));maxU=Math.max(ax,Math.max(bx,cx));minV=Math.min(ay,Math.min(by,cy));maxV=Math.max(ay,Math.max(by,cy));}
        int u0=(int)Math.floor(minU),u1=(int)Math.ceil(maxU),v0=(int)Math.floor(minV),v1=(int)Math.ceil(maxV);float d=nx*ax+ny*ay+nz*az;
        for(int iu=u0;iu<=u1;iu++)for(int iv=v0;iv<=v1;iv++){float u=iu+.5f,v=iv+.5f,x,y,z;if(axis==1){x=u;z=v;if(Math.abs(ny)<1e-6)continue;y=(d-nx*x-nz*z)/ny;}else if(axis==0){y=u;z=v;if(Math.abs(nx)<1e-6)continue;x=(d-ny*y-nz*z)/nx;}else{x=u;y=v;if(Math.abs(nz)<1e-6)continue;z=(d-nx*x-ny*y)/nz;}if(!inside(q,x,y,z,axis))continue;BlockPos bp=BlockPos.ofFloored(x+o.getX(),y+o.getY(),z+o.getZ());if(seen.add(bp.asLong())){out.add(bp);if(out.size()>=limit)return;}}
    }
    private static boolean inside(float[]q,float x,float y,float z,int axis){float a1,a2,b1,b2,c1,c2,p1,p2;if(axis==1){a1=q[0];a2=q[2];b1=q[3];b2=q[5];c1=q[6];c2=q[8];p1=x;p2=z;}else if(axis==0){a1=q[1];a2=q[2];b1=q[4];b2=q[5];c1=q[7];c2=q[8];p1=y;p2=z;}else{a1=q[0];a2=q[1];b1=q[3];b2=q[4];c1=q[6];c2=q[7];p1=x;p2=y;}float den=(b2-c2)*(a1-c1)+(c1-b1)*(a2-c2);if(Math.abs(den)<1e-6)return false;float u=((b2-c2)*(p1-c1)+(c1-b1)*(p2-c2))/den,v=((c2-a2)*(p1-c1)+(a1-c1)*(p2-c2))/den;return u>=-.02&&v>=-.02&&u+v<=1.02;}
}
