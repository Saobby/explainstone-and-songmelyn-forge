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
import com.ginka.explainstone.speaker.PlaybackRegistry;
import com.ginka.explainstone.speaker.SpeakerBlockEntity;
import com.ginka.explainstone.speaker.SpeakerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SpeakerBlock extends Block implements EntityBlock {
    /** 是否正在播放（红石触发或界面按钮都会改它） 喵 */
    public static final BooleanProperty PLAYING = BooleanProperty.create("playing");
    /** 网罩朝向（模型正面朝北，放置时朝向玩家） 喵 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // 模型的实际包围盒：x 0.9~14.4，y 0~16，z 1.5~12.9（正面朝北） 喵
    private static final VoxelShape SHAPE_NORTH = Block.box(0.9, 0.0, 1.5, 14.4, 16.0, 12.9);
    private static final VoxelShape SHAPE_EAST = Block.box(3.1, 0.0, 0.9, 14.5, 16.0, 14.4);
    private static final VoxelShape SHAPE_SOUTH = Block.box(1.6, 0.0, 3.1, 15.1, 16.0, 14.5);
    private static final VoxelShape SHAPE_WEST = Block.box(1.5, 0.0, 1.6, 12.9, 16.0, 15.1);

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

    /**
     * 放置时记下"谁放的"。
     *
     * <p>用创造模式中键取方块再放下来，物品里会带方块实体 NBT（可能已经是收信模式 + 某个频道），
     * 而那份 NBT 要等 {@code BlockItem#updateCustomBlockEntityTag} 才写进方块实体——比这里晚。
     * 所以这里先记住放置者，等第一个 tick 登记失败（频道已满）时再回头提示他并把音响重置为自播放 喵
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player) {
            SpeakerRegistry.rememberPlacer(serverLevel, pos, player.getUUID());
        }
    }

    private static VoxelShape shapeOf(Direction facing) {
        return switch (facing) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    /** 选中框/准星命中框只包住模型，而不是整个方块 喵 */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(state.getValue(FACING));
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
                speaker.ensureAudioUsageRegistered();
                // 收信音响登记表：让服务端知道"这个频道有哪些音响"，好一次性下发给客户端 喵
                SpeakerRegistry.onBlockEntityTick(serverLevel, speaker);
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
                if (powered) {
                    // 红石重新开播时清掉用户暂停状态，否则会一直处于暂停 喵
                    be.setPaused(false);
                }
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
            // 方块没了 → 从这个音频的使用者列表里移除；没人用了就删存档里的文件 喵
            AudioUsageRegistry.update(serverLevel, pos, List.of());
            // 也从收信音响登记表里移除（区块卸载不会走到这里，所以那里不会误删） 喵
            SpeakerRegistry.unregister(serverLevel, pos);
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
