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

    private static final class Session {
        final List<Track> sourceTracks;
        final int selected;
        final PlayMode mode;
        final List<Track> order;
        final int[] durations;
        final long startGameTime;
        final long revision;
        /** 已经切好的曲目分片；列表过大时为 null（不下发） 喵 */
        final List<String> trackParts;
        int range;

        private Session(List<Track> sourceTracks, int selected, PlayMode mode, List<Track> order,
                        int[] durations, long startGameTime, long revision) {
            this.sourceTracks = sourceTracks;
            this.selected = selected;
            this.mode = mode;
            this.order = order;
            this.durations = durations;
            this.startGameTime = startGameTime;
            this.revision = revision;
            String encoded = encode(order);
            List<String> parts = null;
            if (encoded.length() > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
                LOGGER.warn("播放列表编码后 {} 字符，超过上限 {}，本次不向客户端下发", encoded.length(),
                        SpeakerPayloads.MAX_PLAYLIST_CHARS);
            } else {
                parts = SpeakerPayloads.splitTracks(encoded);
                if (parts.size() > SpeakerPayloads.MAX_PARTS) {
                    LOGGER.warn("播放列表需要 {} 个分片，超过上限 {}，本次不向客户端下发", parts.size(),
                            SpeakerPayloads.MAX_PARTS);
                    parts = null;
                }
            }
            this.trackParts = parts;
        }

        private boolean matches(List<Track> tracks, PlayMode mode, int selected) {
            return this.selected == selected && this.mode == mode && this.sourceTracks.equals(tracks);
        }

        private long elapsedTicks(ServerLevel level) {
            return Math.max(0L, level.getGameTime() - this.startGameTime);
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
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tracks.size(); i++) {
            if (i > 0) {
                sb.append('\u0002');
            }
            Track track = tracks.get(i);
            sb.append(track.label() == null ? "" : track.label()).append('\u0001')
                    .append(track.id() == null ? "" : track.id());
        }
        return sb.toString();
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
        boolean playing = state.getBlock() instanceof SpeakerBlock
                && state.getValue(SpeakerBlock.PLAYING)
                && !be.isReceiveMode()
                && !be.getTracks().isEmpty();
        if (!playing) {
            stop(level, pos);
            return;
        }

        List<Track> tracks = be.getTracks();
        int selected = be.getSelected();
        PlayMode mode = be.getMode();
        Map<BlockPos, Session> map = ACTIVE.computeIfAbsent(level.dimension(), k -> new HashMap<>());
        Session existing = map.get(pos);
        if (existing != null && existing.matches(tracks, mode, selected)) {
            // 只是音量/距离变了：保留原进度 喵
            existing.range = Math.max(64, (int) Math.ceil(be.getEffectiveAudibleRange()) + 16);
            return;
        }

        List<Track> order = buildOrder(tracks, mode, selected);
        if (order.isEmpty()) {
            stop(level, pos);
            return;
        }
        Session session = new Session(tracks, selected, mode, order,
                SpeakerNetworking.trackDurations(level, order), level.getGameTime(), ++REVISION);
        session.range = Math.max(64, (int) Math.ceil(be.getEffectiveAudibleRange()) + 16);
        map.put(pos.immutable(), session);
        if (session.trackParts == null) {
            LOGGER.warn("playback session pos={} 的播放列表太大，暂不下发（减少曲目后会恢复）", pos.toShortString());
        }
        LOGGER.info("playback session start pos={} mode={} order={} rev={}",
                pos.toShortString(), mode, order.size(), session.revision);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) session.range * session.range) {
                send(player, pos, session, level);
            }
        }
    }

    public static void stop(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Session> map = ACTIVE.get(level.dimension());
        if (map == null) {
            return;
        }
        Session removed = map.remove(pos);
        if (removed == null) {
            return;
        }
        LOGGER.info("playback session stop pos={} rev={}", pos.toShortString(), removed.revision);
        long elapsed = removed.elapsedTicks(level);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= (double) removed.range * removed.range) {
                SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Playback(pos,
                        removed.mode.ordinal(), false, "", new int[0], elapsed, removed.revision, 0, 1));
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
        List<String> parts = session.trackParts;
        if (parts == null || parts.isEmpty()) {
            return;   // 播放列表过大：宁可不发，也不能在服务端编码时抛异常 喵
        }
        long elapsed = session.elapsedTicks(level);
        for (int i = 0; i < parts.size(); i++) {
            // durations 只在第 0 帧带，其他帧留空，避免大列表重复传几十 KB 喵
            int[] durations = i == 0 ? session.durations : new int[0];
            SpeakerNetworking.sendToPlayer(player, new SpeakerPayloads.Playback(pos,
                    session.mode.ordinal(), true, parts.get(i), durations, elapsed,
                    session.revision, i, parts.size()));
        }
    }
}
