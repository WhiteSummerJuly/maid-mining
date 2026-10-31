package com.gzx.maidmining.item;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * 女仆身上的「工作区域」读写（存在实体的 persistent data 里）。
 */
public final class EntityMaidArea {

    /** NBT 键：int[6] = x1,y1,z1,x2,y2,z2 */
    public static final String TAG = "maidmining_area";

    private EntityMaidArea() {
    }

    public static int[] of(BlockPos a, BlockPos b) {
        return new int[]{a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()};
    }

    /** 返回 [minX,minY,minZ,maxX,maxY,maxZ]，没有就返回 null */
    public static BlockPos[] bounds(Entity entity) {
        int[] v = entity.getPersistentData().getIntArray(TAG);
        if (v.length != 6) {
            return null;
        }
        BlockPos a = new BlockPos(v[0], v[1], v[2]);
        BlockPos b = new BlockPos(v[3], v[4], v[5]);
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        return new BlockPos[]{min, max};
    }

    public static void clear(Entity entity) {
        entity.getPersistentData().remove(TAG);
    }
}
