package com.unrealcraft.entity;

import com.unrealcraft.ModEntities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/** Holds a Quake 3 BSP path; client renderer draws the mesh at this entity (sea-level anchor). */
public class Quake3MapEntity extends Entity {
    private static final TrackedData<String> BSP_PATH =
            DataTracker.registerData(Quake3MapEntity.class, TrackedDataHandlerRegistry.STRING);

    public Quake3MapEntity(EntityType<? extends Quake3MapEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        // Must NOT be invisible — EntityRenderer skips invisible entities for normal players
        setInvisible(false);
        ignoreCameraFrustum = true;
    }

    public Quake3MapEntity(World world, String path) {
        this(ModEntities.Q3_MAP, world);
        setBspPath(path);
    }

    public String getBspPath() { return dataTracker.get(BSP_PATH); }
    public void setBspPath(String p) { dataTracker.set(BSP_PATH, p == null ? "" : p); }

    @Override
    protected void initDataTracker(DataTracker.Builder b) {
        b.add(BSP_PATH, "");
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound n) {
        if (n.contains("BspPath")) setBspPath(n.getString("BspPath"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound n) {
        n.putString("BspPath", getBspPath());
    }

    @Override
    public boolean shouldRender(double distance) {
        return distance < 512.0 * 512.0;
    }

    @Override
    public boolean isCollidable() { return false; }

    @Override
    public boolean canHit() { return false; }
}
