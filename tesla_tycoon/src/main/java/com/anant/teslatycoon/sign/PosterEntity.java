package com.anant.teslatycoon.sign;

import com.anant.teslatycoon.registry.ModEntities;
import com.anant.teslatycoon.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** The placeable luxury wall poster (wall_poster.png): 3 x 4 blocks, hangs on walls and glass pillars like a painting. */
public final class PosterEntity extends AbstractDecorationEntity {
    public PosterEntity(EntityType<? extends PosterEntity> type, World world) {
        super(type, world);
    }

    public PosterEntity(World world, BlockPos pos, Direction facing) {
        super(ModEntities.POSTER, world, pos);
        setFacing(facing);
    }

    @Override
    public int getWidthPixels() {
        return 48;
    }

    @Override
    public int getHeightPixels() {
        return 64;
    }

    @Override
    public void onBreak(@Nullable Entity entity) {
        if (getWorld().getGameRules().getBoolean(GameRules.DO_ENTITY_DROPS)) {
            playSound(SoundEvents.ENTITY_PAINTING_BREAK, 1.0f, 1.0f);
            if (!(entity instanceof PlayerEntity p && p.getAbilities().creativeMode)) {
                dropStack(new ItemStack(ModItems.WALL_POSTER));
            }
        }
    }

    @Override
    public void onPlace() {
        playSound(SoundEvents.ENTITY_PAINTING_PLACE, 1.0f, 1.0f);
    }

    @Override
    public void refreshPositionAndAngles(double x, double y, double z, float yaw, float pitch) {
        setPosition(x, y, z);
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int interpolationSteps, boolean interpolate) {
        setPosition(x, y, z);
    }

    @Override
    public Vec3d getSyncedPos() {
        return Vec3d.of(attachmentPos);
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this, facing.getId(), getDecorationBlockPos());
    }

    @Override
    public void onSpawnPacket(EntitySpawnS2CPacket packet) {
        super.onSpawnPacket(packet);
        setFacing(Direction.byId(packet.getEntityData()));
    }

    @Override
    public ItemStack getPickBlockStack() {
        return new ItemStack(ModItems.WALL_POSTER);
    }
}
