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
package com.ginka.explainstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 颂美铃：壁挂式音响。碰撞箱/选中框按模型实际厚度收窄（贴着背面那一侧的一块薄板），
 * 空出来的部分可以走人。 喵
 */
public class SongmelynBlock extends SpeakerBlock {
    // 模型：x 1.6~14.4，y 1.2~15.2，z 10.8~15.9（背面朝 +Z） 喵
    // 模型背面在 +Z；blockstate 旋转 y=90 会把 +Z 转到 -X，所以朝东时背面在 x 0~5 喵
    private static final VoxelShape SHAPE_NORTH = Block.box(1.6, 1.2, 10.8, 14.4, 15.2, 15.9);
    private static final VoxelShape SHAPE_SOUTH = Block.box(1.6, 1.2, 0.1, 14.4, 15.2, 5.2);
    private static final VoxelShape SHAPE_EAST = Block.box(0.1, 1.2, 1.6, 5.2, 15.2, 14.4);
    private static final VoxelShape SHAPE_WEST = Block.box(10.8, 1.2, 1.6, 15.9, 15.2, 14.4);

    public SongmelynBlock(Properties settings) {
        super(settings);
    }

    private static VoxelShape shapeOf(BlockState state) {
        Direction facing = state.getValue(FACING);
        return switch (facing) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state);
    }

    /** 不参与方块面剔除（薄板，避免把背后的墙面剔掉） 喵 */
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }
}
