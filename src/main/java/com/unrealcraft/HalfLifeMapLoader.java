package com.unrealcraft;

import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.HalfLifeMapEntity;
import com.unrealcraft.halflife.HalfLifeBsp;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import java.nio.file.Path;
import java.util.*;

/** Server-side GoldSrc importer. Visuals are BSP faces; collision is a sparse invisible surface voxelization. */
public final class HalfLifeMapLoader {
    private static final int BLOCKS_PER_TICK=12000;
    private static Job job;
    private static final List<BlockPos> placed=new ArrayList<>();
    private static int entityId=-1;
    private HalfLifeMapLoader(){}
    public static void init(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,env)->dispatcher.register(CommandManager.literal("hl1")
                .then(CommandManager.literal("load").then(CommandManager.argument("bsp",StringArgumentType.greedyString()).executes(c->start(c.getSource(),StringArgumentType.getString(c,"bsp")))))
                .then(CommandManager.literal("loadconfig").executes(c->start(c.getSource(),UnrealConfig.get().hl1BspPath)))
                .then(CommandManager.literal("unload").executes(c->unload(c.getSource())))));
        ServerTickEvents.END_SERVER_TICK.register(server->{if(job==null)return;try{if(job.tick())job=null;}catch(Exception e){Unrealcraft.LOGGER.error("HL1 load failed",e);job.source.sendError(Text.literal("HL1 load failed: "+e.getMessage()));job=null;}});
    }
    private static List<Path> wadPaths(String configured){ if(configured==null||configured.isBlank()) return List.of(); List<Path> out=new ArrayList<>(); for(String s:configured.split(";")){if(!s.isBlank()){Path p=Path.of(s.trim()); if(p.toFile().isFile()) out.add(p);}} return out; }
    public static int start(ServerCommandSource src,String path){
        if(job!=null){src.sendError(Text.literal("Already loading an HL1 map"));return 0;}
        unload(src); Path bsp=Path.of(path.trim()); if(!bsp.toFile().isFile()){src.sendError(Text.literal("BSP not found: "+bsp));return 0;}
        try{List<Path> wads=wadPaths(UnrealConfig.get().hl1WadPath);HalfLifeBsp map=HalfLifeBsp.load(bsp,wads);job=new Job(src,map,BlockPos.ofFloored(src.getPosition()),path.trim());src.sendFeedback(()->Text.literal("Importing GoldSrc BSP: "+bsp.getFileName()),false);return 1;}catch(Exception e){src.sendError(Text.literal("BSP parse failed: "+e.getMessage()));return 0;}
    }
    private static int unload(ServerCommandSource src){job=null;ServerWorld w=src.getWorld();if(entityId>=0){var e=w.getEntityById(entityId);if(e!=null)e.discard();entityId=-1;}int n=0;for(BlockPos p:placed){w.setBlockState(p,Blocks.AIR.getDefaultState());n++;}placed.clear();int finalN=n;if(finalN>0)src.sendFeedback(()->Text.literal("HL1 unloaded: "+finalN+" collision blocks"),false);return finalN;}
    private static final class Job{
        final ServerCommandSource source;final HalfLifeBsp bsp;final BlockPos origin;final String path;final List<BlockPos> queue=new ArrayList<>();int index;boolean planned;
        Job(ServerCommandSource s,HalfLifeBsp b,BlockPos o,String p){source=s;bsp=b;origin=o;path=p;}
        boolean tick(){if(!planned){build();planned=true;source.sendFeedback(()->Text.literal("HL1 geometry parsed: "+queue.size()+" collision blocks"),false);return false;}ServerWorld w=source.getWorld();int end=Math.min(queue.size(),index+BLOCKS_PER_TICK);for(;index<end;index++){BlockPos p=queue.get(index);w.setBlockState(p,Blocks.BARRIER.getDefaultState());placed.add(p);}if(index<queue.size()){if(index%60000<12000)source.sendFeedback(()->Text.literal("HL1 collision "+index+" / "+queue.size()),false);return false;}HalfLifeMapEntity ent=new HalfLifeMapEntity(w,path);ent.refreshPositionAndAngles(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);w.spawnEntity(ent);entityId=ent.getId();source.sendFeedback(()->Text.literal("HL1 map ready: "+bsp.faces.size()+" BSP faces"),false);return true;}
        void build(){
            List<HalfLifeBsp.Polygon> polys=bsp.polygons();if(polys.isEmpty())return;float s=Math.max(.25f,UnrealConfig.get().hl1UnitsPerBlock);
            float minX=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,minY=Float.MAX_VALUE,maxY=-Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
            for(var p:polys)for(int i=0;i<p.xyz().length;i+=3){minX=Math.min(minX,p.xyz()[i]);maxX=Math.max(maxX,p.xyz()[i]);minY=Math.min(minY,p.xyz()[i+1]);maxY=Math.max(maxY,p.xyz()[i+1]);minZ=Math.min(minZ,p.xyz()[i+2]);maxZ=Math.max(maxZ,p.xyz()[i+2]);}
            float cx=(minX+maxX)*.5f,cy=(minY+maxY)*.5f,cz=(minZ+maxZ)*.5f;Set<Long> seen=new HashSet<>();
            for(var p:polys){String tn=p.texture().toUpperCase(Locale.ROOT); if(tn.startsWith("SKY")||tn.startsWith("!")||tn.startsWith("*")) continue; int n=p.xyz().length/3;for(int i=1;i<n-1;i++){float[] a=world(p,0,cx,cy,cz,s),b=world(p,i,cx,cy,cz,s),c=world(p,i+1,cx,cy,cz,s);surface(a,b,c,seen);}}
        }
        float[] world(HalfLifeBsp.Polygon p,int i,float cx,float cy,float cz,float s){return new float[]{(p.xyz()[i*3]-cx)/s+origin.getX(),(p.xyz()[i*3+2]-cz)/s+origin.getY(),(p.xyz()[i*3+1]-cy)/s+origin.getZ()};}
        void surface(float[] a,float[] b,float[] c,Set<Long> seen){
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;float ax=Math.abs(nx),ay=Math.abs(ny),az=Math.abs(nz);if(ax+ay+az<1e-5)return;
            int axis=ay>=ax&&ay>=az?1:(ax>=az?0:2);float minU,maxU,minV,maxV;
            if(axis==1){minU=Math.min(a[0],Math.min(b[0],c[0]));maxU=Math.max(a[0],Math.max(b[0],c[0]));minV=Math.min(a[2],Math.min(b[2],c[2]));maxV=Math.max(a[2],Math.max(b[2],c[2]));}
            else if(axis==0){minU=Math.min(a[1],Math.min(b[1],c[1]));maxU=Math.max(a[1],Math.max(b[1],c[1]));minV=Math.min(a[2],Math.min(b[2],c[2]));maxV=Math.max(a[2],Math.max(b[2],c[2]));}
            else {minU=Math.min(a[0],Math.min(b[0],c[0]));maxU=Math.max(a[0],Math.max(b[0],c[0]));minV=Math.min(a[1],Math.min(b[1],c[1]));maxV=Math.max(a[1],Math.max(b[1],c[1]));}
            int iu0=(int)Math.floor(minU),iu1=(int)Math.ceil(maxU),iv0=(int)Math.floor(minV),iv1=(int)Math.ceil(maxV);float d=nx*a[0]+ny*a[1]+nz*a[2];
            for(int iu=iu0;iu<=iu1;iu++)for(int iv=iv0;iv<=iv1;iv++){float u=iu+.5f,v=iv+.5f;float x,y,z;if(axis==1){x=u;z=v;y=(d-nx*x-nz*z)/ny;}else if(axis==0){y=u;z=v;x=(d-ny*y-nz*z)/nx;}else{x=u;y=v;z=(d-nx*x-ny*y)/nz;}if(!insideProjected(a,b,c,x,y,z,axis))continue;BlockPos bp=BlockPos.ofFloored(x,y,z);long key=bp.asLong();if(seen.add(key)){queue.add(bp);if(axis==1&&ny>0) {BlockPos below=bp.down();if(seen.add(below.asLong()))queue.add(below);}}}
        }
        boolean insideProjected(float[]a,float[]b,float[]c,float x,float y,float z,int axis){float ax,ay,bx,by,cx,cy,px,py;if(axis==1){ax=a[0];ay=a[2];bx=b[0];by=b[2];cx=c[0];cy=c[2];px=x;py=z;}else if(axis==0){ax=a[1];ay=a[2];bx=b[1];by=b[2];cx=c[1];cy=c[2];px=y;py=z;}else{ax=a[0];ay=a[1];bx=b[0];by=b[1];cx=c[0];cy=c[1];px=x;py=y;}float d=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy);if(Math.abs(d)<1e-6)return false;float u=((by-cy)*(px-cx)+(cx-bx)*(py-cy))/d;float v=((cy-ay)*(px-cx)+(ax-cx)*(py-cy))/d;return u>=-.02f&&v>=-.02f&&u+v<=1.02f;}
    }
}
