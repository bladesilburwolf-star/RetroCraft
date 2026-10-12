package com.unrealcraft.entity;

import com.unrealcraft.ModEntities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/** Marker for one imported GoldSrc BSP. Client renderer draws the complete face mesh. */
public class HalfLifeMapEntity extends Entity {
    private static final TrackedData<String> BSP_PATH = DataTracker.registerData(HalfLifeMapEntity.class, TrackedDataHandlerRegistry.STRING);
    public HalfLifeMapEntity(EntityType<? extends HalfLifeMapEntity> type, World world) { super(type, world); noClip=true; setNoGravity(true); setInvisible(true); ignoreCameraFrustum=true; }
    public HalfLifeMapEntity(World world, String path){this(ModEntities.HL1_MAP,world);setBspPath(path);}
    public String getBspPath(){return dataTracker.get(BSP_PATH);}
    public void setBspPath(String path){dataTracker.set(BSP_PATH,path==null?"":path);}
    @Override protected void initDataTracker(DataTracker.Builder b){b.add(BSP_PATH,"");}
    @Override protected void readCustomDataFromNbt(NbtCompound n){if(n.contains("BspPath"))setBspPath(n.getString("BspPath"));}
    @Override protected void writeCustomDataToNbt(NbtCompound n){n.putString("BspPath",getBspPath());}
    @Override public boolean shouldRender(double distance){return true;}
}
