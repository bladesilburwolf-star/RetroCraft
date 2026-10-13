package com.unrealcraft;

import com.unrealcraft.item.Sm64SpawnEggItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creative tab + SM64 enemy spawn eggs.
 * Open creative → Unrealcraft tab → place goombas, koopas, etc. by hand.
 */
public class ModItems {
    public static final Map<String, Item> SPAWN_EGGS = new LinkedHashMap<>();

    private static final String[] KINDS = {
            "goomba", "koopa", "bobomb", "bully", "big_bully",
            "boo", "big_boo", "whomp", "thwomp", "piranha",
            "chain_chomp", "fly_guy", "lakitu", "chuckya", "amp",
            "mr_i", "king_bobomb", "bowser"
    };

    public static void register() {
        for (String kind : KINDS) {
            String id = "spawn_" + kind;
            RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Unrealcraft.id(id));
            Item egg = new Sm64SpawnEggItem(kind, new Item.Settings().maxCount(64));
            Registry.register(Registries.ITEM, key, egg);
            SPAWN_EGGS.put(kind, egg);
        }

        Registry.register(Registries.ITEM_GROUP, Unrealcraft.id("main"),
                FabricItemGroup.builder()
                        .icon(() -> new ItemStack(Items.EGG))
                        .displayName(Text.literal("Unrealcraft / SM64"))
                        .entries((ctx, entries) -> {
                            for (Item egg : SPAWN_EGGS.values()) entries.add(egg);
                            // native stand-ins for props
                            entries.add(Items.OAK_DOOR);
                            entries.add(Items.IRON_DOOR);
                            entries.add(Items.OAK_SIGN);
                            entries.add(Items.OAK_HANGING_SIGN);
                            entries.add(Items.GOLD_NUGGET);  // coin stand-in
                            entries.add(Items.GOLD_INGOT);
                            entries.add(Items.NETHER_STAR); // star stand-in
                            entries.add(Items.TNT);         // bob-omb stand-in / mine
                        })
                        .build());

        Unrealcraft.LOGGER.info("SM64 spawn eggs registered: {}", SPAWN_EGGS.keySet());
    }
}
