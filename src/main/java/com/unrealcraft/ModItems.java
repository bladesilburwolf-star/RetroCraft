package com.unrealcraft;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/** Creative tab only for now — spawn props with /ue spawn <model>. */
public class ModItems {
    public static void register() {
        Registry.register(Registries.ITEM_GROUP, Unrealcraft.id("main"),
                FabricItemGroup.builder()
                        .icon(() -> new ItemStack(Items.IRON_SWORD))
                        .displayName(Text.literal("Unrealcraft"))
                        .entries((ctx, entries) -> {})
                        .build());
    }
}
