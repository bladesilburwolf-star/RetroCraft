package com.unrealcraft.client.halflife;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.config.UnrealConfig;
import com.unrealcraft.entity.HalfLifeMapEntity;
import com.unrealcraft.halflife.HalfLifeBsp;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public final class HalfLifeMapRenderer extends EntityRenderer<HalfLifeMapEntity> {
    private final Map<String, HalfLifeMapMesh> cache=new HashMap<>();
    private final Map<String, HalfLifeBsp> bsps=new HashMap<>();
    public HalfLifeMapRenderer(EntityRendererFactory.Context ctx){super(ctx);shadowRadius=0;}
    private HalfLifeMapMesh meshFor(String path){
        if(path==null||path.isBlank())return null; if(cache.containsKey(path))return cache.get(path);
        try{
            UnrealConfig c=UnrealConfig.get(); Path bsp=Path.of(path); List<Path> wads=wadPaths(c.hl1WadPath);
            HalfLifeBsp b=HalfLifeBsp.load(bsp,wads); bsps.put(path,b); HalfLifeTextures.setBsp(b);
            HalfLifeMapMesh m=HalfLifeMapMesh.build(b,c.hl1UnitsPerBlock); cache.put(path,m); Unrealcraft.LOGGER.info("HL1 mesh {}: {} triangles",bsp.getFileName(),m.tris.size()); return m;
        }catch(Exception e){Unrealcraft.LOGGER.error("HL1 BSP load failed {}",path,e);return null;}
    }
    private List<Path> wadPaths(String configured){
        if(configured==null||configured.isBlank()) return List.of();
        List<Path> out=new ArrayList<>();
        for(String s:configured.split(";")){
            if(s==null||s.isBlank()) continue;
            Path p=Path.of(s.trim());
            if(p.toFile().isFile()) out.add(p);
        }
        return out;
    }
    @Override public boolean shouldRender(HalfLifeMapEntity e,Frustum f,double x,double y,double z){return true;}
    @Override public void render(HalfLifeMapEntity entity,float yaw,float tickDelta,MatrixStack matrices,VertexConsumerProvider consumers,int light){
        HalfLifeMapMesh mesh=meshFor(entity.getBspPath()); if(mesh==null||mesh.tris.isEmpty())return; matrices.push();
        for(var entry:mesh.byTexture().entrySet()){
            Identifier tex=HalfLifeTextures.get(entry.getKey()); VertexConsumer vc=consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex)); MatrixStack.Entry en=matrices.peek();
            for(HalfLifeMapMesh.Tri t:entry.getValue())for(int i=0;i<3;i++){int p=i*3,u=i*2;vc.vertex(en,t.pos()[p],t.pos()[p+1],t.pos()[p+2]).color(255,255,255,255).texture(t.uv()[u],1-t.uv()[u+1]).overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(en,0,1,0);}
        } matrices.pop();
    }
    @Override public Identifier getTexture(HalfLifeMapEntity e){return Identifier.ofVanilla("textures/block/stone.png");}
}
