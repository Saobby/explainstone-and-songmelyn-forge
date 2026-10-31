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
        final List<Track> sourceTracks;
        final int selected;
        final PlayMode mode;
        final List<Track> order;
        final int[] durations;
        long startGameTime;
        long revision;
        /** 已经切好的曲目分片；列表过大时为 null（不下发） 喵 */
        final List<String> trackParts;
        int range;
        /** 用户暂停：广播时间线冻结，收信端静音但保留进度显示 喵 */
        boolean paused;
        long pausedElapsed;
        /** 上次重发时间，用于限制恶意刷操作导致的广播重推放大 喵 */
        long lastSentMs = System.currentTimeMillis();

        private Entry(int channel, List<Track> sourceTracks, int selected, PlayMode mode, List<Track> order,
                      int[] durations, long startGameTime, long revision, int range, List<String> trackParts,
                      boolean paused, long pausedElapsed) {
            this.channel = channel;
            this.sourceTracks = sourceTracks;
            this.selected = selected;
            this.mode = mode;
            this.order = order;
            this.durations = durations;
            this.startGameTime = startGameTime;
            this.revision = revision;
            this.range = range;
            this.trackParts = trackParts;
            this.paused = paused;
            this.pausedElapsed = Math.max(0L, pausedElapsed);
        }

        private boolean matches(int channel, List<Track> tracks, PlayMode mode, int selected) {
            return this.channel == channel && this.selected == selected && this.mode == mode
                    && this.sourceTracks.equals(tracks);
        }

        private long elapsedTicks(ServerLevel level) {
            if (this.paused) {
                return this.pausedElapsed;
            }
            return Math.max(0L, level.getGameTime() - this.startGameTime);
        }

        private boolean pause(ServerLevel level) {
            if (this.paused) {
                return false;
            }
            this.pausedElapsed = this.elapsedTicks(level);
            this.paused = true;
            return true;
        }

        private void resume(ServerLevel level) {
            if (!this.paused) {
                return;
            }
            this.startGameTime = level.getGameTime() - this.pausedElapsed;
            this.paused = false;
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
        if (paused) {
            if (existing != null && existing.matches(channel, tracks, mode, selected)) {
                existing.range = range;
                long nowMs = System.currentTimeMillis();
                if (existing.pause(level)) {
                    // 暂停快照：收信端静音但保留进度，等到恢复时再继续 喵
                    existing.revision = ++REVISION;
                    existing.lastSentMs = nowMs;
                    long elapsed = existing.elapsedTicks(level);
                    sendEntry(level, pos, existing);
                    LOGGER.info("broadcast session paused pos={} ch={} elapsed={} rev={}",
                            pos.toShortString(), existing.channel, elapsed, existing.revision);
                } else if (nowMs - existing.lastSentMs >= 5000L) {
                    // 暂停期间每 5 秒重发一次，收信端的进度条不会因为 10 秒没更新被丢掉 喵
                    existing.lastSentMs = nowMs;
                    sendEntry(level, pos, existing);
                }
                return;
            }
            if (existing != null) {
                // 暂停期间换了频道/列表：旧广播作废，按新列表重建暂停快照 喵
                map.remove(pos);
                PlaybackPersistence.discard(level.dimension(), pos);
                stopEntry(level, pos, existing);
            }

            PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
            List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
            if (order != null && (!saved.matches(tracks, mode, selected) || saved.channel() != channel)) {
                order = null;
            }
            long elapsed = order == null ? 0L : Math.max(0L, saved.elapsedTicks());
            if (order == null) {
                order = PlaybackRegistry.buildOrder(tracks, mode, selected);
            }
            if (order.isEmpty()) {
                map.remove(pos);
                return;
            }
            String encoded = PlaybackRegistry.encode(order);
            List<String> parts = null;
            if (encoded.length() > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
                LOGGER.warn("广播列表编码后 {} 字符，超过上限 {}，pos={} 暂不下发", encoded.length(),
                        SpeakerPayloads.MAX_PLAYLIST_CHARS, pos.toShortString());
            } else {
                parts = SpeakerPayloads.splitTracks(encoded);
                if (parts.size() > SpeakerPayloads.MAX_PARTS) {
                    LOGGER.warn("广播列表需要 {} 个分片，超过上限 {}，pos={} 暂不下发", parts.size(),
                            SpeakerPayloads.MAX_PARTS, pos.toShortString());
                    parts = null;
                }
            }
            if (parts == null) {
                map.remove(pos);
                return;
            }
            Entry entry = new Entry(channel, tracks, selected, mode, order,
                    SpeakerNetworking.trackDurations(level, order), level.getGameTime() - elapsed,
                    ++REVISION, range, parts, true, elapsed);
            map.put(pos.immutable(), entry);
            LOGGER.info("broadcast session paused pos={} ch={} mode={} order={} elapsed={} rev={}",
                    pos.toShortString(), channel, mode, order.size(), elapsed, entry.revision);
            sendEntry(level, pos, entry);
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

        if (existing != null && existing.matches(channel, tracks, mode, selected)) {
            existing.range = range;
            if (existing.paused) {
                // 恢复：沿用暂停时的顺序和位置，客户端重新开始出声 喵
                existing.resume(level);
                existing.revision = ++REVISION;
                existing.lastSentMs = System.currentTimeMillis();
                long elapsed = existing.elapsedTicks(level);
                sendEntry(level, pos, existing);
                LOGGER.info("broadcast session resumed pos={} ch={} elapsed={} rev={}",
                        pos.toShortString(), channel, elapsed, existing.revision);
                return;
            }
            // 只是传播距离变了：保留原进度，重新下发一次（但最多 1 秒一次，防止刷操作放大） 喵
            long now = System.currentTimeMillis();
            if (now - existing.lastSentMs < 1000L) {
                return;
            }
            existing.lastSentMs = now;
            sendEntry(level, pos, existing);
            return;
        }
        if (existing != null) {
            // 频道/列表/模式变了：先让旧频道的客户端停下 喵
            stopEntry(level, pos, existing);
        }

        // 重进存档时优先恢复上次的随机顺序和进度；对不上就重新生成 喵
        PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
        List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
        if (order != null && (!saved.matches(tracks, mode, selected) || saved.channel() != channel)) {
            order = null;
        }
        long startGameTime = level.getGameTime();
        boolean restored = order != null;
        if (order == null) {
            order = PlaybackRegistry.buildOrder(tracks, mode, selected);
        } else {
            startGameTime -= Math.max(0L, saved.elapsedTicks());
        }
        if (order.isEmpty()) {
            map.remove(pos);
            return;
        }
        String encoded = PlaybackRegistry.encode(order);
        List<String> parts = null;
        if (encoded.length() > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
            LOGGER.warn("广播列表编码后 {} 字符，超过上限 {}，pos={} 暂不下发", encoded.length(),
                    SpeakerPayloads.MAX_PLAYLIST_CHARS, pos.toShortString());
        } else {
            parts = SpeakerPayloads.splitTracks(encoded);
            if (parts.size() > SpeakerPayloads.MAX_PARTS) {
                LOGGER.warn("广播列表需要 {} 个分片，超过上限 {}，pos={} 暂不下发", parts.size(),
                        SpeakerPayloads.MAX_PARTS, pos.toShortString());
                parts = null;
            }
        }
        if (parts == null) {
            map.remove(pos);
            return;
        }
        Entry entry = new Entry(channel, tracks, selected, mode, order,
                SpeakerNetworking.trackDurations(level, order), startGameTime, ++REVISION, range, parts,
                false, 0L);
        map.put(pos.immutable(), entry);
        LOGGER.info("broadcast session start pos={} ch={} mode={} order={} rev={}",
                pos.toShortString(), channel, mode, order.size(), entry.revision);
        if (restored) {
            LOGGER.info("broadcast session resumed pos={} ch={} elapsed={} rev={}", pos.toShortString(),
                    channel, level.getGameTime() - startGameTime, entry.revision);
        }
        sendEntry(level, pos, entry);
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
                        PlaybackPersistence.trackIds(session.sourceTracks),
                        PlaybackPersistence.trackIds(session.order),
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
                        entry.revision, 0, 1));
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
        List<String> parts = entry.trackParts;
        if (parts == null || parts.isEmpty()) {
            return;   // 列表过大：不下发，避免服务端编码异常 喵
        }
        long elapsed = entry.elapsedTicks(level);
        for (int i = 0; i < parts.size(); i++) {
            int[] durations = i == 0 ? entry.durations : new int[0];
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(entry.channel,
                    entry.mode.ordinal(), true, entry.paused, parts.get(i), durations, elapsed,
                    entry.revision, i, parts.size()));
        }
    }

    private static void stopEntry(ServerLevel level, BlockPos pos, Entry entry) {
        long elapsed = entry.elapsedTicks(level);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) entry.range * entry.range) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Broadcast(
                        entry.channel, 0, false, false, "", new int[0], elapsed, entry.revision, 0, 1));
            }
        }
    }
}
