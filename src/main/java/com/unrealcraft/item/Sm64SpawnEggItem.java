package com.unrealcraft.item;

import com.unrealcraft.ModEntities;
import com.unrealcraft.entity.Sm64MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/** Places an Sm64MobEntity of a fixed kind on right-click. Creative-friendly. */
public class Sm64SpawnEggItem extends Item {
    private final String kind;

    public Sm64SpawnEggItem(String kind, Settings settings) {
        super(settings);
        this.kind = kind;
    }

    public String kind() { return kind; }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        if (ctx.getWorld().isClient) return ActionResult.SUCCESS;
        ServerWorld world = (ServerWorld) ctx.getWorld();
        BlockPos pos = ctx.getBlockPos().offset(ctx.getSide());
        Sm64MobEntity mob = ModEntities.SM64_MOB.create(world);
        if (mob == null) return ActionResult.FAIL;
        mob.setKind(kind);
        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                ctx.getPlayer() != null ? ctx.getPlayer().getYaw() : 0f, 0f);
        world.spawnEntity(mob);
        if (ctx.getPlayer() != null && !ctx.getPlayer().getAbilities().creativeMode) {
            ctx.getStack().decrement(1);
        }
        return ActionResult.CONSUME;
    }
}
