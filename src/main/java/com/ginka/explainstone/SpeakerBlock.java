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

import com.ginka.explainstone.speaker.PlaybackRegistry;
import com.ginka.explainstone.speaker.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class SpeakerBlock extends Block implements EntityBlock {
    /** 是否正在播放（红石触发或界面按钮都会改它） 喵 */
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
    /** 网罩朝向（模型正面朝北，放置时朝向玩家） 喵 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SpeakerBlock(Properties settings) {
        super(settings);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PLAYING, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PLAYING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 网罩面朝玩家 喵
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    /** 服务端用 ticker 维护播放会话：缺了就补、列表变了就重开、每 5 秒重推一次 喵 */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (lvl instanceof ServerLevel serverLevel && be instanceof SpeakerBlockEntity speaker) {
                PlaybackRegistry.tick(serverLevel, pos, st, speaker);
            }
        };
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        // 界面本身由客户端打开，这里只需要吃掉这次交互 喵
        if (level.isClientSide) {
            Explainstone.openSpeakerScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                                boolean notify) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be
                && !be.isReceiveMode()) {
            // 收信模式忽略红石（只听广播），其它模式保留红石检测 喵
            boolean powered = level.hasNeighborSignal(pos);
            // 只在红石信号"变化"时动作：拉杆/红石块变化才启停， 喵
            // 破坏旁边（包括脚下）的方块只是普通邻居更新，不会把正在播放的音乐关掉 喵
            boolean edge = powered != be.isLastPowered();
            be.setLastPowered(powered);
            if (edge && be.isRespectRedstone() && state.getValue(PLAYING) != powered) {
                level.setBlock(pos, state.setValue(PLAYING, powered), Block.UPDATE_ALL);
                if (level instanceof ServerLevel serverLevel) {
                    if (powered) {
                        PlaybackRegistry.ensure(serverLevel, pos, be);
                    } else {
                        PlaybackRegistry.stop(serverLevel, pos);
                    }
                }
            }
        }
        super.neighborChanged(state, level, pos, sourceBlock, sourcePos, notify);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            PlaybackRegistry.stop(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        // 正在播放 → 15 级红石信号 喵
        return state.getValue(PLAYING) ? 15 : 0;
    }
}
