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

import com.ginka.explainstone.speaker.AudioUsageRegistry;
import com.ginka.explainstone.speaker.BroadcastRegistry;
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

import java.util.List;

/**
 * 广播发信器：把选中的音乐以某个频道播出去，收信模式的讲解石会自动收听。 喵
 */
public class BroadcastTransmitterBlock extends Block implements EntityBlock {
    /** 是否正在广播 喵 */
    public static final BooleanProperty PLAYING = BooleanProperty.create("broadcasting");
    /** 只用来节流"定期重推广播" 喵 */
    private static int BEACON_TICK;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BroadcastTransmitterBlock(Properties settings) {
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
        // 屏幕朝向玩家 喵
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        if (level.isClientSide) {
            Explainstone.openSpeakerScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block sourceBlock, BlockPos sourcePos,
                                boolean notify) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
            boolean powered = level.hasNeighborSignal(pos);
            boolean edge = powered != be.isLastPowered();
            be.setLastPowered(powered);
            if (edge && be.isRespectRedstone() && state.getValue(PLAYING) != powered
                    // 配置要求 OP 才能开播时，红石不能被用来绕过权限（停止广播仍然允许） 喵
                    && !(powered && ExplainstoneConfig.broadcastRequiresOp())) {
                level.setBlock(pos, state.setValue(PLAYING, powered), Block.UPDATE_ALL);
                if (powered) {
                    if (be.getTimerSeconds() > 0) {
                        be.startTimer();
                    }
                } else {
                    be.stopTimer();
                }
                if (level instanceof ServerLevel serverLevel) {
                    BroadcastRegistry.refresh(serverLevel, pos, be);
                }
            }
        }
        super.neighborChanged(state, level, pos, sourceBlock, sourcePos, notify);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            BroadcastRegistry.forget(serverLevel, pos);
            // 发信器没了 → 从音频的使用者列表里移除；没人用了就删存档里的文件 喵
            AudioUsageRegistry.update(serverLevel, pos, List.of());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(PLAYING) ? 15 : 0;
    }

    /** 定时播放：广播中按秒倒数，到点自动停 喵 */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (w, pos, st, be) -> {
            if (!(be instanceof SpeakerBlockEntity speaker)) {
                return;
            }
            speaker.ensureAudioUsageRegistered();
            BlockState current = w.getBlockState(pos);
            boolean broadcasting = current.getBlock() instanceof BroadcastTransmitterBlock && current.getValue(PLAYING);
            if (broadcasting && w instanceof ServerLevel serverLevel) {
                // 每 5 秒重推一次，客户端换了维度/晚进信号范围/漏收包都能自愈 喵
                if (++BEACON_TICK % 100 == 0) {
                    BroadcastRegistry.refresh(serverLevel, pos, speaker);
                }
            }
            if (broadcasting && speaker.getTimerSeconds() > 0 && !speaker.tickTimer()) {
                w.setBlock(pos, current.setValue(PLAYING, false), Block.UPDATE_ALL);
                if (w instanceof ServerLevel serverLevel) {
                    BroadcastRegistry.refresh(serverLevel, pos, speaker);
                }
            }
        };
    }
}
