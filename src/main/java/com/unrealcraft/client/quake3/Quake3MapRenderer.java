package com.unrealcraft.client.quake3;

import com.unrealcraft.entity.Quake3MapEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Legacy entity from older saves. Q3 maps are now drawn by RetroMapClient; this renders nothing. */
public final class Quake3MapRenderer extends EntityRenderer<Quake3MapEntity> {
    public Quake3MapRenderer(EntityRendererFactory.Context c) { super(c); shadowRadius = 0; }

    @Override
    public boolean shouldRender(Quake3MapEntity e, Frustum f, double x, double y, double z) { return false; }

    @Override
    public void render(Quake3MapEntity e, float yaw, float tick, MatrixStack ms, VertexConsumerProvider vcp, int light) {}

    @Override
    public Identifier getTexture(Quake3MapEntity e) { return Identifier.ofVanilla("textures/block/stone.png"); }
}
