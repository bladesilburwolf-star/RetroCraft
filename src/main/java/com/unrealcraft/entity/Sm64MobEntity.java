package com.unrealcraft.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * Placeholder SM64 enemy for Fabric 1.21.1.
 * Kind string drives colour/size in the renderer only.
 *
 * Do NOT override getDimensions(EntityPose) — it is final on LivingEntity in 1.21.1.
 * Hitbox stays at EntityType dimensions (0.7 x 1.0 from ModEntities).
 * No real AI yet — stands still.
 */
public class Sm64MobEntity extends PathAwareEntity {
    private static final TrackedData<String> KIND =
            DataTracker.registerData(Sm64MobEntity.class, TrackedDataHandlerRegistry.STRING);

    public Sm64MobEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setAiDisabled(true);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 10.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 16.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(KIND, "goomba");
    }

    public String getKind() {
        return getDataTracker().get(KIND);
    }

    public void setKind(String kind) {
        if (kind == null || kind.isEmpty()) kind = "goomba";
        getDataTracker().set(KIND, kind);
        // Visual scale is handled in Sm64MobRenderer by kind; hitbox stays EntityType default.
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("Sm64Kind", getKind());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Sm64Kind")) setKind(nbt.getString("Sm64Kind"));
    }
}
