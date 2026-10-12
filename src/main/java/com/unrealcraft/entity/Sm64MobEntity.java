package com.unrealcraft.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * SM64 enemy hosted as a persistent, ordinary Minecraft mob.
 */
public class Sm64MobEntity extends PathAwareEntity {
    private static final TrackedData<String> KIND =
            DataTracker.registerData(Sm64MobEntity.class, TrackedDataHandlerRegistry.STRING);
    private boolean importedFromLevel;

    public Sm64MobEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
    }

    @Override
    protected void initGoals() {
        goalSelector.add(2, new MeleeAttackGoal(this, 1.1, false));
        goalSelector.add(6, new WanderAroundFarGoal(this, 0.8));
        goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        goalSelector.add(8, new LookAroundGoal(this));
        targetSelector.add(1, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
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

    public void setImportedFromLevel(boolean value) { importedFromLevel = value; }
    public boolean isImportedFromLevel() { return importedFromLevel; }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("Sm64Kind", getKind());
        nbt.putBoolean("Sm64LevelSpawn", importedFromLevel);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Sm64Kind")) setKind(nbt.getString("Sm64Kind"));
        importedFromLevel = nbt.getBoolean("Sm64LevelSpawn");
    }
}
