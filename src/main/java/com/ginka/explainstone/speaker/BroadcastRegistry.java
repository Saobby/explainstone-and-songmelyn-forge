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
package com.ginka.explainstone.speaker;

import com.ginka.explainstone.BroadcastTransmitterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端广播登记表：记录每个世界里正在广播的发信器，
 * 并把「频道 → 曲目」推给 512 格内的客户端（信号传播距离 = 512 格）。 喵
 */
public final class BroadcastRegistry {
    /** 默认信号传播距离（格），实际用每个发信器的 transmitRange 喵 */
    public static final double SIGNAL_RANGE = 512.0;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Entry>> ACTIVE = new HashMap<>();

    private BroadcastRegistry() {
    }

    private record Entry(int channel, List<Track> tracks, int index, int mode, int range) {
        Track current() {
            return this.index >= 0 && this.index < this.tracks.size() ? this.tracks.get(this.index) : null;
        }
    }

    /** 曲目列表编码：label\u0001id，条目之间 \u0002 喵 */
    private static String encode(List<Track> tracks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            if (i > 0) {
                sb.append('\u0002');
            }
            sb.append(tracks.get(i).label()).append('\u0001').append(tracks.get(i).id());
        }
        return sb.toString();
    }

    /** 发信器的广播状态有变化（播放/停止/换曲/换频道/定时到点）时调用 喵 */
    public static void refresh(ServerLevel level, BlockPos pos, SpeakerBlockEntity be) {
        BlockState state = level.getBlockState(pos);
        boolean active = state.getBlock() instanceof BroadcastTransmitterBlock
                && state.getValue(BroadcastTransmitterBlock.PLAYING)
                && be.getSelectedTrack() != null;

        Map<BlockPos, Entry> map = ACTIVE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        int range = Math.max(32, Math.min(512, be.getTransmitRange()));
        Entry entry = active
                ? new Entry(be.getChannel(), be.getTracks(), be.getSelected(), be.getMode().ordinal(), range)
                : null;
        if (entry == null) {
            map.remove(pos);
        } else {
            map.put(pos.immutable(), entry);
        }

        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) range * range) {
                send(player, be.getChannel(), entry);
            }
        }
    }

    /** 发信器被拆掉/被替换：撤销登记并通知附近玩家停播 喵 */
    public static void forget(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        Entry removed = map.remove(pos);
        if (removed == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            send(player, removed.channel(), null);
        }
    }

    /** 玩家进服时补发当前在播的频道 喵 */
    public static void onJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        for (Map.Entry<BlockPos, Entry> e : map.entrySet()) {
            if (player.blockPosition().distSqr(e.getKey()) <= (double) e.getValue().range() * e.getValue().range()) {
                send(player, e.getValue().channel(), e.getValue());
            }
        }
    }

    /** 玩家换维度/退出时清空（客户端收到后自己清，这里只处理停播） 喵 */
    public static void stopAll(ServerLevel level) {
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            for (Entry entry : map.values()) {
                send(player, entry.channel(), null);
            }
        }
        map.clear();
    }

    private static void send(ServerPlayer player, int channel, Entry entry) {
        if (entry == null) {
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(channel, 0, 0, false, ""));
        } else {
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(
                    entry.channel(), entry.mode(), entry.index(), true, encode(entry.tracks())));
        }
    }
}
