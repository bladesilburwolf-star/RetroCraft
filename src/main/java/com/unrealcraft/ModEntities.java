package com.unrealcraft;

import com.unrealcraft.entity.UePropEntity;
import com.unrealcraft.entity.HalfLifeMapEntity;
import com.unrealcraft.entity.Quake3MapEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;

public class ModEntities {
    public static final EntityType<UePropEntity> UE_PROP = Registry.register(
            Registries.ENTITY_TYPE,
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Unrealcraft.id("ue_prop")),
            EntityType.Builder.<UePropEntity>create(UePropEntity::new, SpawnGroup.MISC)
                    .dimensions(2.0f, 2.0f)
                    .maxTrackingRange(256)
                    .trackingTickInterval(20)
                    .build("ue_prop"));



    public static final EntityType<HalfLifeMapEntity> HL1_MAP = Registry.register(
            Registries.ENTITY_TYPE,
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Unrealcraft.id("hl1_map")),
            EntityType.Builder.<HalfLifeMapEntity>create(HalfLifeMapEntity::new, SpawnGroup.MISC)
                    .dimensions(2.0f, 2.0f).maxTrackingRange(256).trackingTickInterval(20).build("hl1_map"));



    public static final EntityType<Quake3MapEntity> Q3_MAP = Registry.register(
            Registries.ENTITY_TYPE,
            RegistryKey.of(RegistryKeys.ENTITY_TYPE, Unrealcraft.id("q3_map")),
            EntityType.Builder.<Quake3MapEntity>create(Quake3MapEntity::new, SpawnGroup.MISC)
                    .dimensions(2.0f, 2.0f).maxTrackingRange(256).trackingTickInterval(20).build("q3_map"));

    public static void register() {
        Unrealcraft.LOGGER.info("UE prop entity registered");
    }
}
