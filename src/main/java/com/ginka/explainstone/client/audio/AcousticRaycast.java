/*
 * explainstone —— 讲解石 / 颂美铃 / 广播发信器（Minecraft Forge 1.20.1 模组）
 *
 *  作者 / Author : GINKA_3L96WW1
 *
 * 本文件由作者手工编写；贴图与模型由 tools/ 下的脚本程序化生成。
 * 保留此注释即可随意修改、分发。 喵
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.ginka.explainstone.client.audio;

import com.ginka.explainstone.ExplainstoneConfig;
import com.ginka.explainstone.sound.SoundMaterial;
import com.ginka.explainstone.sound.SoundMaterials;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 直达声遮挡：从音响中心向听者耳朵做 DDA 体素遍历，
 * 用方块碰撞形状计算实际穿透长度，再按声学材质累乘透射率。 喵
 */
public final class AcousticRaycast {
    /** 低频/高频能量透射率；1.0 表示没有遮挡 喵 */
    public record Occlusion(double lowEnergy, double highEnergy) {
        public static final Occlusion NONE = new Occlusion(1.0, 1.0);
    }

    private AcousticRaycast() {
    }

    public static Occlusion sample(ClientLevel level, Vec3 from, Vec3 to) {
        if (!ExplainstoneConfig.soundOcclusion()) {
            return Occlusion.NONE;
        }
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4) {
            return Occlusion.NONE;
        }

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);
        int stepX = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        int stepY = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        int stepZ = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : (stepX > 0 ? (x + 1 - from.x) : (from.x - x)) / Math.abs(dx);
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : (stepY > 0 ? (y + 1 - from.y) : (from.y - y)) / Math.abs(dy);
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : (stepZ > 0 ? (z + 1 - from.z) : (from.z - z)) / Math.abs(dz);

        BlockPos sourcePos = BlockPos.containing(from);
        double low = 1.0;
        double high = 1.0;
        double t = 0.0;

        // 上限只是防御异常数据；正常最多几百格 喵
        for (int guard = 0; guard < 4096; guard++) {
            double tNext = Math.min(1.0, Math.min(tMaxX, Math.min(tMaxY, tMaxZ)));
            if (tNext > t
                    && !(x == sourcePos.getX() && y == sourcePos.getY() && z == sourcePos.getZ())) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.isLoaded(pos)) {
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        SoundMaterial material = SoundMaterials.of(state, level, pos);
                        if (material != SoundMaterial.AIR) {
                            // 关闭的门/活板门即使碰撞形状很薄，也要覆盖整个门洞；打开的门是 AIR。
                            // 不完整方块统一按“整格路径 + sqrt 系数”处理，不再按碰撞厚度二次削弱。 喵
                            boolean doorPanel = isClosedDoorPanel(state);
                            VoxelShape shape = state.getCollisionShape(level, pos);
                            if (doorPanel || !shape.isEmpty()) {
                                boolean full = !doorPanel && Block.isShapeFullBlock(shape);
                                double d = (tNext - t) * length;
                                if (d > 1.0E-6) {
                                    double tl = material.transmissionLow();
                                    double th = material.transmissionHigh();
                                    if (!full) {
                                        // 不完整方块：透射系数取平方根，厚度不再额外削弱 喵
                                        tl = Math.sqrt(tl);
                                        th = Math.sqrt(th);
                                    }
                                    low *= Math.pow(tl, d);
                                    high *= Math.pow(th, d);
                                    if (low < 1.0E-5 && high < 1.0E-5) {
                                        return new Occlusion(low, high);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (tNext >= 1.0) {
                break;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }

        return new Occlusion(clamp01(low), clamp01(high));
    }

    /** 关闭的门 / 活板门覆盖整个门洞；打开的由 SoundMaterials 返回 AIR 喵 */
    private static boolean isClosedDoorPanel(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return !state.getValue(BlockStateProperties.OPEN);
        }
        if (block instanceof TrapDoorBlock trapdoor) {
            return !state.getValue(BlockStateProperties.OPEN);
        }
        return false;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
