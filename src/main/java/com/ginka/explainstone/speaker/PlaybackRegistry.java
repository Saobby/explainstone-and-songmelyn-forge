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
                        PlaybackPersistence.trackIds(session.sourceTracks),
                        PlaybackPersistence.trackIds(session.order),
                        session.elapsedTicks(level)));
            }
        }
        return out;
    }

    private static final class Session {
        final List<Track> sourceTracks;
        final int selected;
        final PlayMode mode;
        final List<Track> order;
        final int[] durations;
        long startGameTime;
        long revision;
        /** 已经切好的曲目分片；列表过大时为 null（不下发） 喵 */
        final List<String> trackParts;
        /** 用户暂停：保持会话给客户端显示进度，但时间线冻结 喵 */
        boolean paused;
        /** 暂停时的已播放 tick；恢复时用它重设 startGameTime 喵 */
        long pausedElapsed;
        int range;

        private Session(List<Track> sourceTracks, int selected, PlayMode mode, List<Track> order,
                        int[] durations, long startGameTime, long revision, boolean paused, long pausedElapsed) {
            this.sourceTracks = sourceTracks;
            this.selected = selected;
            this.mode = mode;
            this.order = order;
            this.durations = durations;
            this.startGameTime = startGameTime;
            this.revision = revision;
            this.paused = paused;
            this.pausedElapsed = Math.max(0L, pausedElapsed);
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
            if (this.paused) {
                return this.pausedElapsed;
            }
            return Math.max(0L, level.getGameTime() - this.startGameTime);
        }

        /** 冻结时间线（只记一次，重复暂停不会把进度往后推） 喵 */
        private boolean pause(ServerLevel level) {
            if (this.paused) {
                return false;
            }
            this.pausedElapsed = this.elapsedTicks(level);
            this.paused = true;
            return true;
        }

        /** 从暂停位置恢复：把 startGameTime 往前挪，客户端仍得到同一个 elapsed 喵 */
        private void resume(ServerLevel level) {
            if (!this.paused) {
                return;
            }
            this.startGameTime = level.getGameTime() - this.pausedElapsed;
            this.paused = false;
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
        if (paused) {
            if (existing != null && existing.matches(tracks, mode, selected)) {
                existing.range = range;
                if (!existing.pause(level)) {
                    // 已经在暂停：进度不再变化，等每 5 秒的重推即可 喵
                    return;
                }
                // 换一个 revision，客户端会把它当成新的暂停快照，保留进度条但不发声 喵
                existing.revision = ++REVISION;
                long elapsed = existing.elapsedTicks(level);
                sendNearby(level, pos, existing);
                LOGGER.info("playback session paused pos={} elapsed={} rev={}",
                        pos.toShortString(), elapsed, existing.revision);
                return;
            }
            if (existing != null) {
                // 暂停期间列表/模式变了：旧进度作废，重建一个停在同一时间的暂停会话 喵
                map.remove(pos);
                PlaybackPersistence.discard(level.dimension(), pos);
                sendStop(level, pos, existing);
            }

            // 从保存的顺序/进度恢复成暂停状态；对不上就从 0 开始 喵
            PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
            List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
            if (order != null && !saved.matches(tracks, mode, selected)) {
                order = null;
            }
            long elapsed = order == null ? 0L : Math.max(0L, saved.elapsedTicks());
            if (order == null) {
                order = buildOrder(tracks, mode, selected);
            }
            if (order.isEmpty()) {
                stop(level, pos);
                return;
            }
            Session session = new Session(tracks, selected, mode, order,
                    SpeakerNetworking.trackDurations(level, order), level.getGameTime() - elapsed,
                    ++REVISION, true, elapsed);
            session.range = range;
            map.put(pos.immutable(), session);
            if (session.trackParts == null) {
                LOGGER.warn("playback session pos={} 的播放列表太大，暂不下发（减少曲目后会恢复）", pos.toShortString());
            }
            LOGGER.info("playback session paused pos={} mode={} order={} elapsed={} rev={}",
                    pos.toShortString(), mode, order.size(), elapsed, session.revision);
            sendNearby(level, pos, session);
            return;
        }

        if (existing != null && existing.matches(tracks, mode, selected)) {
            existing.range = range;
            if (existing.paused) {
                // 从暂停恢复：同一个顺序、同一个已播放位置 喵
                existing.resume(level);
                existing.revision = ++REVISION;
                long elapsed = existing.elapsedTicks(level);
                sendNearby(level, pos, existing);
                LOGGER.info("playback session resumed pos={} elapsed={} rev={}",
                        pos.toShortString(), elapsed, existing.revision);
            }
            return;
        }
        if (existing != null) {
            // 列表/模式变了：先让旧会话停下 喵
            map.remove(pos);
            PlaybackPersistence.discard(level.dimension(), pos);
            sendStop(level, pos, existing);
        }

        // 重进存档时优先恢复上次保存的随机顺序和进度；对不上就重新生成 喵
        PlaybackPersistence.Saved saved = PlaybackPersistence.claim(level.dimension(), pos);
        List<Track> order = saved == null ? null : saved.resolveOrder(tracks);
        if (order != null && !saved.matches(tracks, mode, selected)) {
            order = null;
        }
        long startGameTime = level.getGameTime();
        boolean restored = order != null;
        if (order == null) {
            order = buildOrder(tracks, mode, selected);
        } else {
            startGameTime -= Math.max(0L, saved.elapsedTicks());
        }
        if (order.isEmpty()) {
            stop(level, pos);
            return;
        }
        Session session = new Session(tracks, selected, mode, order,
                SpeakerNetworking.trackDurations(level, order), startGameTime, ++REVISION, false, 0L);
        session.range = range;
        map.put(pos.immutable(), session);
        if (session.trackParts == null) {
            LOGGER.warn("playback session pos={} 的播放列表太大，暂不下发（减少曲目后会恢复）", pos.toShortString());
        }
        LOGGER.info("playback session start pos={} mode={} order={} rev={}",
                pos.toShortString(), mode, order.size(), session.revision);
        if (restored) {
            LOGGER.info("playback session resumed pos={} elapsed={} rev={}", pos.toShortString(),
                    level.getGameTime() - startGameTime, session.revision);
        }
        sendNearby(level, pos, session);
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
                        session.revision, 0, 1));
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
                    session.mode.ordinal(), true, session.paused, parts.get(i), durations, elapsed,
                    session.revision, i, parts.size()));
        }
    }
}
