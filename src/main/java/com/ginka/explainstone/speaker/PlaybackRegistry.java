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

import com.ginka.explainstone.SpeakerBlock;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 服务端讲解石播放会话表。
 *
 * 服务端只保存「固定播放顺序 + 起始游戏 tick」，不关心音频时长：
 * 客户端拿到 elapsedTicks 后自己按每首歌的时长取模定位当前曲目和偏移，
 * 因此所有人（以及离开范围后又回来的玩家）都会落在同一个时间点。 喵
 */
public final class PlaybackRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-playback");
    private static final Map<ResourceKey<Level>, Map<BlockPos, Session>> ACTIVE = new HashMap<>();
    private static long REVISION;

    private PlaybackRegistry() {
    }

    /** 服务器停止/切换存档时清空，避免单机换世界继承旧会话 喵 */
    public static void clear() {
        ACTIVE.clear();
    }

    /** 给持久化用：当前所有活跃会话（含已播放 tick） 喵 */
    static List<PlaybackPersistence.Saved> snapshots(MinecraftServer server) {
        List<PlaybackPersistence.Saved> out = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, Map<BlockPos, Session>> entry : ACTIVE.entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                continue;
            }
            for (Map.Entry<BlockPos, Session> sessionEntry : entry.getValue().entrySet()) {
                Session session = sessionEntry.getValue();
                out.add(new PlaybackPersistence.Saved(entry.getKey(), sessionEntry.getKey(), false, 0,
                        session.mode.ordinal(), session.selected,
                        PlaybackPersistence.trackIds(session.timeline.sourceTracks()),
                        PlaybackPersistence.trackIds(session.timeline.order()),
                        session.elapsedTicks(level)));
            }
        }
        return out;
    }

    private static final class Session {
        final int selected;
        final PlayMode mode;
        final SessionTimeline timeline;
        long revision;
        int range;
        /** 上次同步时的音频仓库版本：变了就说明有音频上传完成/被回收，要重发一次 喵 */
        long availabilityVersion;

        private Session(int selected, PlayMode mode, SessionTimeline timeline, long revision) {
            this.selected = selected;
            this.mode = mode;
            this.timeline = timeline;
            this.revision = revision;
            this.availabilityVersion = AudioStore.version();
        }

        private boolean sameSettings(int selected, PlayMode mode) {
            return this.selected == selected && this.mode == mode;
        }

        private long elapsedTicks(ServerLevel level) {
            return this.timeline.elapsedTicks(level.getGameTime());
        }
    }

    /**
     * 生成本次会话的固定播放顺序。
     * 顺序播放从当前选中曲目开始循环；随机播放把选中曲目放第一个、其余随机；单曲循环只有一首。 喵
     */
    public static List<Track> buildOrder(List<Track> tracks, PlayMode mode, int selected) {
        if (tracks.isEmpty()) {
            return List.of();
        }
        int start = Math.floorMod(selected, tracks.size());
        if (mode == PlayMode.REPEAT_ONE) {
            return List.of(tracks.get(start));
        }
        List<Track> order = new ArrayList<>();
        if (mode == PlayMode.SHUFFLE) {
            order.add(tracks.get(start));
            List<Track> rest = new ArrayList<>(tracks);
            rest.remove(start);
            Collections.shuffle(rest, new Random());
            order.addAll(rest);
        } else {
            order.addAll(tracks);
            Collections.rotate(order, -start);
        }
        return List.copyOf(order);
    }

    /** 曲目列表编码：label\u0001id，条目之间 \u0002 喵 */
    public static String encode(List<Track> tracks) {
        return SessionTimeline.encode(tracks);
    }

    /** 一条曲目在编码字符串里占的字符数（含分隔符，用于提前限制列表大小） 喵 */
    public static long encodedLength(Track track) {
        if (track == null) {
            return 0L;
        }
        int label = track.label() == null ? 0 : track.label().length();
        int id = track.id() == null ? 0 : track.id().length();
        return (long) label + 1L + id + 1L;
    }

    /** 整个播放列表编码后的字符数 喵 */
    public static long encodedLength(List<Track> tracks) {
        long length = 0L;
        if (tracks != null) {
            for (Track track : tracks) {
                length += encodedLength(track);
            }
        }
        return length;
    }

    /** 玩家进服 / 客户端请求 Resync 时，把附近的会话补发过去 喵 */
    public static void syncNear(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Session> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        for (Map.Entry<BlockPos, Session> e : map.entrySet()) {
            BlockPos pos = e.getKey();
            Session session = e.getValue();
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                session.range = Math.max(64, (int) Math.ceil(be.getEffectiveAudibleRange()) + 16);
            }
            if (player.blockPosition().distSqr(pos) <= (double) session.range * session.range) {
                send(player, pos, session, level);
            }
        }
    }

    public static void ensure(ServerLevel level, BlockPos pos, SpeakerBlockEntity be) {
        BlockState state = level.getBlockState(pos);
        List<Track> tracks = be.getTracks();
        int selected = be.getSelected();
        boolean receive = be.isReceiveMode();
        boolean paused = be.isPaused() && !receive && !tracks.isEmpty();
        boolean playing = !paused && state.getBlock() instanceof SpeakerBlock
                && state.getValue(SpeakerBlock.PLAYING)
                && !receive
                && !tracks.isEmpty();
        if (!playing && !paused) {
            stop(level, pos);
            return;
        }

        PlayMode mode = be.getMode();
        Map<BlockPos, Session> map = ACTIVE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Session existing = map.get(pos);
        int range = Math.max(64, (int) Math.ceil(be.getEffectiveAudibleRange()) + 16);
        long now = level.getGameTime();
        if (existing != null) {
            if (!existing.sameSettings(selected, mode)) {
                // 用户换了曲目/换了模式：语义上就是重新开始 喵
                map.remove(pos);
                PlaybackPersistence.discard(level.dimension(), pos);
                sendStop(level, pos, existing);
                existing = null;
            } else {
                existing.range = range;
                boolean changed = false;
                if (paused && existing.timeline.pause(now)) {
                    changed = true;
                } else if (!paused && existing.timeline.resume(now)) {
                    changed = true;
                }
                SessionTimeline.Result result = existing.timeline.apply(tracks, mode, now,
                        track -> SpeakerNetworking.trackDuration(level, be, track));
                if (result == SessionTimeline.Result.REBUILD) {
                    map.remove(pos);
                    PlaybackPersistence.discard(level.dimension(), pos);
                    sendStop(level, pos, existing);
                    existing = null;
                } else {
                    if (result == SessionTimeline.Result.UPDATED) {
                        changed = true;
                    }
                    if (existing.availabilityVersion != AudioStore.version()) {
                        // 有音频上传完成/被回收：位图变了，重发一次，所有人立刻能看到/听到 喵
                        existing.availabilityVersion = AudioStore.version();
                        changed = true;
                    }
                    if (changed) {
                        existing.revision = ++REVISION;
                        sendNearby(level, pos, existing);
                        LOGGER.info("playback session updated pos={} mode={} order={} paused={} rev={}",
                                pos.toShortString(), mode, existing.timeline.order().size(),
                                existing.timeline.paused(), existing.revision);
                    }
                    return;
                }
            }
        }

        // 新建会话：优先恢复上次保存的顺序和进度；对不上就重新生成 喵
        PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
        List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
        if (order != null && !saved.matches(tracks, mode, selected)) {
            order = null;
        }
        long startGameTime = now;
        long pausedElapsed = 0L;
        boolean restored = order != null;
        if (order == null) {
            order = buildOrder(tracks, mode, selected);
        } else {
            pausedElapsed = Math.max(0L, saved.elapsedTicks());
            startGameTime -= pausedElapsed;
        }
        if (order.isEmpty()) {
            stop(level, pos);
            return;
        }
        SessionTimeline timeline = new SessionTimeline(tracks, order,
                SpeakerNetworking.trackDurations(level, be, order), startGameTime, paused, pausedElapsed);
        Session session = new Session(selected, mode, timeline, ++REVISION);
        session.range = range;
        map.put(pos.immutable(), session);
        if (timeline.parts() == null) {
            LOGGER.warn("playback session pos={} 的播放列表无法下发（{} 首，编码 {} 字符）",
                    pos.toShortString(), order.size(), SessionTimeline.encode(order).length());
        }
        LOGGER.info("playback session {} pos={} mode={} order={} rev={} durations={} available={}/{}",
                paused ? "paused" : "start", pos.toShortString(), mode, order.size(), session.revision,
                firstDurations(timeline.durations()), SessionTimeline.availableCount(order), order.size());
        if (restored) {
            LOGGER.info("playback session resumed pos={} elapsed={} rev={}", pos.toShortString(),
                    timeline.elapsedTicks(now), session.revision);
        }
        sendNearby(level, pos, session);
    }

    /** 诊断用：打印前几首的时长 喵 */
    private static String firstDurations(int[] durations) {
        if (durations == null || durations.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(6, durations.length); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(durations[i]);
        }
        return sb.append(durations.length > 6 ? ",…]" : "]").toString();
    }

    /** 把会话（含暂停快照）发给范围内的所有玩家 喵 */
    private static void sendNearby(ServerLevel level, BlockPos pos, Session session) {
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) session.range * session.range) {
                send(player, pos, session, level);
            }
        }
    }

    public static void stop(ServerLevel level, BlockPos pos) {
        // 停止/拆除后不要再用旧进度恢复 喵
        PlaybackPersistence.discard(level.dimension(), pos);
        Map<BlockPos, Session> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        Session removed = map.remove(pos);
        if (removed == null) {
            return;
        }
        LOGGER.info("playback session stop pos={} rev={}", pos.toShortString(), removed.revision);
        sendStop(level, pos, removed);
    }

    /** 通知附近客户端这个会话结束/暂停（active=false） 喵 */
    private static void sendStop(ServerLevel level, BlockPos pos, Session session) {
        long elapsed = session.elapsedTicks(level);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) session.range * session.range) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Playback(pos,
                        session.mode.ordinal(), false, false, "", new int[0], elapsed,
                        session.revision, 0, 1, new byte[0]));
            }
        }
    }

    /** 每 tick 由方块 ticker 调用：会话缺失就补，列表/模式变了就重开，每 5 秒重推一次 喵 */
    public static void tick(ServerLevel level, BlockPos pos, BlockState state, SpeakerBlockEntity be) {
        ensure(level, pos, be);
        Session session = ACTIVE.getOrDefault(level.dimension(), Map.of()).get(pos);
        if (session != null && level.getGameTime() % 100 == 0) {
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(pos) <= (double) session.range * session.range) {
                    send(player, pos, session, level);
                }
            }
        }
    }

    private static void send(ServerPlayer player, BlockPos pos, Session session, ServerLevel level) {
        List<String> parts = session.timeline.parts();
        if (parts == null || parts.isEmpty()) {
            return;   // 播放列表过大：宁可不发，也不能在服务端编码时抛异常 喵
        }
        long elapsed = session.elapsedTicks(level);
        int[] durations = session.timeline.durations();
        byte[] available = SessionTimeline.availabilityBits(session.timeline.order());
        for (int i = 0; i < parts.size(); i++) {
            // durations + 可用性位图只在第 0 帧带，其他帧留空，避免大列表重复传几十 KB 喵
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Playback(pos,
                    session.mode.ordinal(), true, session.timeline.paused(), parts.get(i),
                    i == 0 ? durations : new int[0], elapsed, session.revision, i, parts.size(),
                    i == 0 ? available : new byte[0]));
        }
    }
}
