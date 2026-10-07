package com.unrealcraft.entity;

import com.unrealcraft.ModEntities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

public class UePropEntity extends Entity {
    private static final TrackedData<String> MODEL =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Float> SCALE =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> QX =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> QY =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> QZ =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> QW =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> MAP_MODE =
            DataTracker.registerData(UePropEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public UePropEntity(EntityType<? extends UePropEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    public UePropEntity(World world, String model, float scale) {
        this(ModEntities.UE_PROP, world);
        setModel(model);
        setPropScale(scale);
    }

    public String getModel() { return dataTracker.get(MODEL); }
    public void setModel(String m) { dataTracker.set(MODEL, m == null ? "" : m); }
    public float getPropScale() { return dataTracker.get(SCALE); }
    public void setPropScale(float s) { dataTracker.set(SCALE, s); }

    public float getQx() { return dataTracker.get(QX); }
    public float getQy() { return dataTracker.get(QY); }
    public float getQz() { return dataTracker.get(QZ); }
    public float getQw() { return dataTracker.get(QW); }

    public void setRotation(float x, float y, float z, float w) {
        dataTracker.set(QX, x);
        dataTracker.set(QY, y);
        dataTracker.set(QZ, z);
        dataTracker.set(QW, w);
    }

    /** Euler degrees XYZ → quaternion (intrinsic-ish sequential). */
    public boolean isMapMode() { return dataTracker.get(MAP_MODE); }
    public void setMapMode(boolean value) { dataTracker.set(MAP_MODE, value); }

    public void setRotationDegrees(float pitchX, float yawY, float rollZ) {
        double rx = Math.toRadians(pitchX) * 0.5;
        double ry = Math.toRadians(yawY) * 0.5;
        double rz = Math.toRadians(rollZ) * 0.5;
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double cz = Math.cos(rz), sz = Math.sin(rz);
        // XYZ order
        float qw = (float) (cx * cy * cz + sx * sy * sz);
        float qx = (float) (sx * cy * cz - cx * sy * sz);
        float qy = (float) (cx * sy * cz + sx * cy * sz);
        float qz = (float) (cx * cy * sz - sx * sy * cz);
        setRotation(qx, qy, qz, qw);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(MODEL, "");
        builder.add(SCALE, 1.0f);
        builder.add(QX, 0f);
        builder.add(QY, 0f);
        builder.add(QZ, 0f);
        builder.add(QW, 1f);
        builder.add(MAP_MODE, false);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.contains("Model")) setModel(nbt.getString("Model"));
        if (nbt.contains("Scale")) setPropScale(nbt.getFloat("Scale"));
        setRotation(
                nbt.contains("Qx") ? nbt.getFloat("Qx") : 0f,
                nbt.contains("Qy") ? nbt.getFloat("Qy") : 0f,
                nbt.contains("Qz") ? nbt.getFloat("Qz") : 0f,
                nbt.contains("Qw") ? nbt.getFloat("Qw") : 1f);
        setMapMode(nbt.contains("MapMode") && nbt.getBoolean("MapMode"));
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putString("Model", getModel());
        nbt.putFloat("Scale", getPropScale());
        nbt.putFloat("Qx", getQx());
        nbt.putFloat("Qy", getQy());
        nbt.putFloat("Qz", getQz());
        nbt.putFloat("Qw", getQw());
        nbt.putBoolean("MapMode", isMapMode());
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
