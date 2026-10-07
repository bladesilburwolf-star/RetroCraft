package com.unrealcraft.client.halflife;

import com.unrealcraft.Unrealcraft;
import com.unrealcraft.halflife.HalfLifeBsp;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import java.util.*;

public final class HalfLifeTextures {
    private static final Map<String,Identifier> CACHE=new HashMap<>();
    private static HalfLifeBsp bsp;
    private static Identifier fallback(){return Identifier.ofVanilla("textures/block/stone.png");}
    public static void setBsp(HalfLifeBsp b){bsp=b;CACHE.clear();}
    public static Identifier get(String name){if(name==null||name.isBlank())return fallback();String k=name.toUpperCase(Locale.ROOT);Identifier old=CACHE.get(k);if(old!=null)return old;if(bsp==null)return fallback();int idx=bsp.textureNames.indexOf(k);if(idx<0)return fallback();HalfLifeWadProxy t=new HalfLifeWadProxy(bsp.texture(idx));if(t.valid()){Identifier id=register(k,t.w,t.h,t.px);CACHE.put(k,id);return id;}return fallback();}
    private static Identifier register(String n,int w,int h,int[] px){try{NativeImage img=new NativeImage(NativeImage.Format.RGBA,w,h,false);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=px[y*w+x];img.setColor(x,y,((c>>>24)&255)<<24|((c>>0)&255)<<16|((c>>8)&255)<<8|((c>>16)&255));}Identifier id=Identifier.of(Unrealcraft.MOD_ID,"hl1/"+n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]","_"));MinecraftClient.getInstance().getTextureManager().registerTexture(id,new NativeImageBackedTexture(img));return id;}catch(Exception e){Unrealcraft.LOGGER.warn("HL1 texture {} failed",n,e);return fallback();}}
    private record HalfLifeWadProxy(int w,int h,int[] px){HalfLifeWadProxy(com.unrealcraft.halflife.HalfLifeWad.Texture t){this(t==null?0:t.width(),t==null?0:t.height(),t==null?new int[0]:t.argb());}boolean valid(){return w>0&&h>0&&px.length==w*h;}}
}
