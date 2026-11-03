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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端广播登记表：记录每个世界里正在广播的发信器。
 * 和讲解石的本地播放一样，这里保存「固定播放顺序 + 起始游戏 tick」，
 * 收信端自己按 elapsedTicks 计算当前曲目与偏移，保证全服进度一致。 喵
 */
public final class BroadcastRegistry {
    /** 默认信号传播距离（格），实际用每个发信器的 transmitRange 喵 */
    public static final double SIGNAL_RANGE = 512.0;
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-broadcast");

    private static final Map<ResourceKey<Level>, Map<BlockPos, Entry>> ACTIVE = new HashMap<>();
    private static long REVISION;

    private BroadcastRegistry() {
    }

    /** 服务器停止/切换存档时清空，避免单机换世界继承旧频道 喵 */
    public static void clear() {
        ACTIVE.clear();
    }

    private static final class Entry {
        final int channel;
        final int selected;
        final PlayMode mode;
        final SessionTimeline timeline;
        long revision;
        int range;
        /** 发信器全局音量（0-100）：收信音响的实际音量 = 本机音量 × 它 喵 */
        int volume;
        /** 上次重发时间，用于限制恶意刷操作导致的广播重推放大 喵 */
        long lastSentMs = System.currentTimeMillis();
        /** 上次同步时的音频仓库版本：变了就重发一次（有人上传完/文件被回收） 喵 */
        long availabilityVersion = AudioStore.version();

        private Entry(int channel, int selected, PlayMode mode, SessionTimeline timeline,
                      long revision, int range, int volume) {
            this.channel = channel;
            this.selected = selected;
            this.mode = mode;
            this.timeline = timeline;
            this.revision = revision;
            this.range = range;
            this.volume = volume;
        }

        private long elapsedTicks(ServerLevel level) {
            return this.timeline.elapsedTicks(level.getGameTime());
        }
    }

    /** 发信器的广播状态有变化（播放/停止/换曲/换频道/定时到点）时调用 喵 */
    public static void refresh(ServerLevel level, BlockPos pos, SpeakerBlockEntity be) {
        BlockState state = level.getBlockState(pos);
        List<Track> tracks = be.getTracks();
        int channel = be.getChannel();
        int selected = be.getSelected();
        PlayMode mode = be.getMode();
        boolean paused = be.isPaused() && !tracks.isEmpty() && be.getSelectedTrack() != null;
        boolean active = !paused && state.getBlock() instanceof BroadcastTransmitterBlock
                && state.getValue(BroadcastTransmitterBlock.PLAYING)
                && !tracks.isEmpty()
                && be.getSelectedTrack() != null;

        Map<BlockPos, Entry> map = ACTIVE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Entry existing = map.get(pos);
        int range = Math.max(32, Math.min(512, be.getTransmitRange()));
        int volume = Math.max(0, Math.min(100, be.getBroadcastVolume()));
        long now = level.getGameTime();
        if (paused) {
            if (existing != null && existing.channel == channel
                    && existing.selected == selected && existing.mode == mode) {
                existing.range = range;
                updateVolume(level, pos, existing, volume);
                boolean changed = existing.timeline.pause(now);
                SessionTimeline.Result result = existing.timeline.apply(tracks, mode, now,
                        track -> SpeakerNetworking.trackDuration(level, be, track));
                if (result == SessionTimeline.Result.UPDATED
                        || existing.availabilityVersion != AudioStore.version()) {
                    existing.availabilityVersion = AudioStore.version();
                    changed = true;
                }
                if (result == SessionTimeline.Result.REBUILD) {
                    // 时间轴对不上（当前曲目被删等）：只能重建 喵
                    map.remove(pos);
                    PlaybackPersistence.discard(level.dimension(), pos);
                    stopEntry(level, pos, existing);
                } else {
                    long nowMs = System.currentTimeMillis();
                    if (changed) {
                        // 换个 revision，收信端保留进度条但继续静音 喵
                        existing.revision = ++REVISION;
                        existing.lastSentMs = nowMs;
                        sendEntry(level, pos, existing);
                        LOGGER.info("broadcast session paused pos={} ch={} elapsed={} rev={}",
                                pos.toShortString(), channel, existing.elapsedTicks(level), existing.revision);
                    } else if (nowMs - existing.lastSentMs >= 5000L) {
                        // 暂停期间每 5 秒重发一次，收信端进度条不会因为太久没更新被丢掉 喵
                        existing.lastSentMs = nowMs;
                        sendEntry(level, pos, existing);
                    }
                    return;
                }
            } else if (existing != null) {
                // 暂停期间换了频道/列表：旧广播作废，按新列表重建暂停快照 喵
                map.remove(pos);
                PlaybackPersistence.discard(level.dimension(), pos);
                stopEntry(level, pos, existing);
            }
            spawn(level, pos, map, be, tracks, channel, selected, mode, range, volume, true);
            return;
        }
        if (!active) {
            PlaybackPersistence.discard(level.dimension(), pos);
            if (existing != null) {
                map.remove(pos);
                stopEntry(level, pos, existing);
            }
            return;
        }

        if (existing != null && existing.channel == channel
                && existing.selected == selected && existing.mode == mode) {
            boolean rangeChanged = existing.range != range;
            existing.range = range;
            // 音量变了只发小包，立即生效；整张播放列表仍然按下面的节流重推 喵
            updateVolume(level, pos, existing, volume);
            boolean resumed = existing.timeline.resume(now);
            SessionTimeline.Result result = existing.timeline.apply(tracks, mode, now,
                    track -> SpeakerNetworking.trackDuration(level, be, track));
            if (result == SessionTimeline.Result.REBUILD) {
                map.remove(pos);
                PlaybackPersistence.discard(level.dimension(), pos);
                stopEntry(level, pos, existing);
            } else {
                boolean changed = resumed || rangeChanged
                        || result == SessionTimeline.Result.UPDATED;
                if (existing.availabilityVersion != AudioStore.version()) {
                    // 有音频上传完成/被回收：位图变了，要让收信端立刻更新 喵
                    existing.availabilityVersion = AudioStore.version();
                    changed = true;
                }
                long nowMs = System.currentTimeMillis();
                if (!changed) {
                    // 什么都没变，但发信器的 ticker 只会每 5 秒调一次这里！
                    // 必须定期重推当 keep-alive，否则客户端 10 秒后会把广播丢掉、收信音响静音 喵
                    if (nowMs - existing.lastSentMs < 4000L) {
                        return;
                    }
                    existing.lastSentMs = nowMs;
                    sendEntry(level, pos, existing);
                    return;
                }
                if (!resumed && nowMs - existing.lastSentMs < 1000L) {
                    // 节流：防止连续刷界面操作放大成大量重推 喵
                    return;
                }
                existing.lastSentMs = nowMs;
                existing.revision = ++REVISION;
                sendEntry(level, pos, existing);
                LOGGER.info("broadcast session {} pos={} ch={} order={} paused={} rev={}",
                        resumed ? "resumed" : "updated", pos.toShortString(), channel,
                        existing.timeline.order().size(), existing.timeline.paused(), existing.revision);
                return;
            }
        } else if (existing != null) {
            // 频道/列表/模式变了：先让旧频道的客户端停下 喵
            stopEntry(level, pos, existing);
        }
        spawn(level, pos, map, be, tracks, channel, selected, mode, range, volume, false);
    }

    /** 新建一个广播会话（优先恢复上次保存的随机顺序和进度） 喵 */
    private static void spawn(ServerLevel level, BlockPos pos, Map<BlockPos, Entry> map, SpeakerBlockEntity be,
                              List<Track> tracks, int channel, int selected, PlayMode mode,
                              int range, int volume, boolean paused) {
        PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
        List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
        if (order != null && (!saved.matches(tracks, mode, selected) || saved.channel() != channel)) {
            order = null;
        }
        long startGameTime = level.getGameTime();
        long pausedElapsed = 0L;
        boolean restored = order != null;
        if (order == null) {
            order = PlaybackRegistry.buildOrder(tracks, mode, selected);
        } else {
            pausedElapsed = Math.max(0L, saved.elapsedTicks());
            startGameTime -= pausedElapsed;
        }
        if (order.isEmpty()) {
            map.remove(pos);
            return;
        }
        SessionTimeline timeline = new SessionTimeline(tracks, order,
                SpeakerNetworking.trackDurations(level, be, order), startGameTime, paused, pausedElapsed);
        if (timeline.parts() == null) {
            LOGGER.warn("广播列表无法下发，pos={}（{} 首，编码 {} 字符）",
                    pos.toShortString(), order.size(), SessionTimeline.encode(order).length());
        }
        Entry entry = new Entry(channel, selected, mode, timeline, ++REVISION, range, volume);
        map.put(pos.immutable(), entry);
        LOGGER.info("broadcast session {} pos={} ch={} mode={} order={} rev={}",
                paused ? "paused" : "start", pos.toShortString(), channel, mode, order.size(), entry.revision);
        if (restored) {
            LOGGER.info("broadcast session resumed pos={} ch={} elapsed={} rev={}", pos.toShortString(),
                    channel, timeline.elapsedTicks(level.getGameTime()), entry.revision);
        }
        sendEntry(level, pos, entry);
    }

    /**
     * 上一曲 / 下一曲：把当前广播的时间轴整体平移一首，并立刻重推给信号范围内的收信端。
     * 和讲解石一样只动 elapsed、不动 selected，避免 refresh() 把会话当成换曲重建。 喵
     *
     * @param direction 负数 = 上一曲，正数 = 下一曲 喵
     * @return 是否真的移动了（没有广播会话 / 时长未知时 false） 喵
     */
    public static boolean skip(ServerLevel level, BlockPos pos, int direction) {
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return false;
        }
        Entry entry = map.get(pos);
        if (entry == null || !entry.timeline.skipTrack(direction, level.getGameTime())) {
            return false;
        }
        entry.revision = ++REVISION;
        entry.lastSentMs = System.currentTimeMillis();
        sendEntry(level, pos, entry);
        LOGGER.info("broadcast session skip pos={} ch={} dir={} elapsed={} rev={}", pos.toShortString(),
                entry.channel, direction, entry.elapsedTicks(level), entry.revision);
        return true;
    }

    /** 发信器被拆掉/被替换：撤销登记并通知附近玩家停播 喵 */
    public static void forget(ServerLevel level, BlockPos pos) {
        PlaybackPersistence.discard(level.dimension(), pos);
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        Entry removed = map.remove(pos);
        if (removed != null) {
            stopEntry(level, pos, removed);
        }
    }

    /** 给持久化用：当前所有活跃广播（含已播放 tick） 喵 */
    static List<PlaybackPersistence.Saved> snapshots(MinecraftServer server) {
        List<PlaybackPersistence.Saved> out = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, Map<BlockPos, Entry>> entry : ACTIVE.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Entry> broadcast : entry.getValue().entrySet()) {
                Entry session = broadcast.getValue();
                out.add(new PlaybackPersistence.Saved(entry.getKey(), broadcast.getKey(), true,
                        session.channel, session.mode.ordinal(), session.selected,
                        PlaybackPersistence.trackIds(session.timeline.sourceTracks()),
                        PlaybackPersistence.trackIds(session.timeline.order()),
                        session.elapsedTicks(level)));
            }
        }
        return out;
    }

    /** 玩家进服 / 请求 Resync 时补发当前在播的频道 喵 */
    public static void onJoin(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        for (Map.Entry<BlockPos, Entry> e : map.entrySet()) {
            if (player.blockPosition().distSqr(e.getKey()) <= (double) e.getValue().range * e.getValue().range) {
                send(e.getValue(), level, player);
            }
        }
    }

    /** 玩家换维度/退出时清空（客户端收到后自己清，这里只处理停播） 喵 */
    public static void stopAll(ServerLevel level) {
        Map<BlockPos, Entry> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        for (Entry entry : map.values()) {
            for (ServerPlayer player : level.players()) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(
                        entry.channel, 0, false, false, "", new int[0], entry.elapsedTicks(level),
                        entry.revision, 0, 1, entry.volume, new byte[0]));
            }
        }
        map.clear();
    }

    private static void sendEntry(ServerLevel level, BlockPos pos, Entry entry) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) entry.range * entry.range) {
                send(entry, level, player);
            }
        }
    }

    private static void send(Entry entry, ServerLevel level, ServerPlayer player) {
        List<String> parts = entry.timeline.parts();
        if (parts == null || parts.isEmpty()) {
            return;   // 列表过大：不下发，避免服务端编码异常 喵
        }
        long elapsed = entry.elapsedTicks(level);
        int[] durations = entry.timeline.durations();
        byte[] available = SessionTimeline.availabilityBits(entry.timeline.order());
        for (int i = 0; i < parts.size(); i++) {
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(entry.channel,
                    entry.mode.ordinal(), true, entry.timeline.paused(), parts.get(i),
                    i == 0 ? durations : new int[0], elapsed, entry.revision, i, parts.size(),
                    entry.volume, i == 0 ? available : new byte[0]));
        }
    }

    /**
     * 全局音量变化：只给信号范围内的玩家推一个几十字节的小包。
     * 音量滑块是连续拖动的，重推整张播放列表既慢又费带宽，所以单独走这条路 喵
     */
    private static void updateVolume(ServerLevel level, BlockPos pos, Entry entry, int volume) {
        if (entry.volume == volume) {
            return;
        }
        entry.volume = volume;
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) entry.range * entry.range) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.BroadcastVolume(entry.channel, volume));
            }
        }
    }

    private static void stopEntry(ServerLevel level, BlockPos pos, Entry entry) {
        long elapsed = entry.elapsedTicks(level);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) entry.range * entry.range) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(
                        entry.channel, 0, false, false, "", new int[0], elapsed, entry.revision, 0, 1,
                        entry.volume, new byte[0]));
            }
        }
    }
}
