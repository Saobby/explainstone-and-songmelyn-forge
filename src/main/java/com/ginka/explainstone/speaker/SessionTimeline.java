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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 一个播放会话的时间轴（讲解石和广播发信器共用）。
 *
 * <p>它只保存元数据：固定播放顺序、每首的时长、起始游戏 tick、暂停快照。
 * 曲目列表变化时**按增量应用**，而不是整条重建：
 * <ul>
 *   <li>尾部追加（导入新歌）、改名、时长修正 → 保留时间轴，只换顺序/时长；</li>
 *   <li>删除当前曲目之前的曲目 → 把起始 tick 往前挪同样的时长，当前这首接着放；</li>
 *   <li>删除当前正在放的曲目、或者增删混在一起 → 只能重建（{@link Result#REBUILD}）。 喵</li>
 * </ul>
 */
public final class SessionTimeline {
    /** 曲目变化的应用结果 喵 */
    public enum Result {
        /** 什么都没变 喵 */
        UNCHANGED,
        /** 原地更新完成，调用方需要重发一次 喵 */
        UPDATED,
        /** 时间轴对不上，调用方应该结束旧会话再重建 喵 */
        REBUILD
    }

    /** 查询某条曲目的时长（秒）；返回 0 表示暂时未知 喵 */
    public interface Durations {
        int secondsOf(Track track);
    }

    private List<Track> sourceTracks;
    private List<Track> order;
    private int[] durations;
    /** 播放列表分片：只在真的要下发时才编码（纯时间轴运算不需要它） 喵 */
    private List<String> parts;
    private boolean partsDirty = true;
    private long startGameTime;
    private boolean paused;
    private long pausedElapsed;

    public SessionTimeline(List<Track> sourceTracks, List<Track> order, int[] durations,
                           long startGameTime, boolean paused, long pausedElapsed) {
        this.sourceTracks = List.copyOf(sourceTracks);
        this.order = List.copyOf(order);
        this.durations = durations != null && durations.length == order.size() ? durations.clone() : new int[order.size()];
        this.startGameTime = startGameTime;
        this.paused = paused;
        this.pausedElapsed = Math.max(0L, pausedElapsed);
    }

    public List<Track> sourceTracks() {
        return this.sourceTracks;
    }

    public List<Track> order() {
        return this.order;
    }

    public int[] durations() {
        return this.durations;
    }

    /** 已经切好的播放列表分片；列表过大时为 null（不下发） 喵 */
    public List<String> parts() {
        if (this.partsDirty) {
            this.parts = encodeParts(this.order);
            this.partsDirty = false;
        }
        return this.parts;
    }

    public boolean paused() {
        return this.paused;
    }

    public long elapsedTicks(long nowGameTime) {
        if (this.paused) {
            return this.pausedElapsed;
        }
        return Math.max(0L, nowGameTime - this.startGameTime);
    }

    /** 冻结时间轴；返回 true 表示状态真的变了（重复暂停不算） 喵 */
    public boolean pause(long nowGameTime) {
        if (this.paused) {
            return false;
        }
        this.pausedElapsed = this.elapsedTicks(nowGameTime);
        this.paused = true;
        return true;
    }

    /** 从暂停位置恢复（客户端仍然得到同一个 elapsed） 喵 */
    public boolean resume(long nowGameTime) {
        if (!this.paused) {
            return false;
        }
        this.startGameTime = nowGameTime - this.pausedElapsed;
        this.paused = false;
        return true;
    }

    // ------------------------------------------------------------ 上一曲 / 下一曲 喵

    /** 整条播放顺序的总时长（tick）；顺序为空或有任何一首时长未知就返回 0 喵 */
    private long totalTicks() {
        if (this.order.isEmpty() || this.durations.length != this.order.size()) {
            return 0L;
        }
        long total = 0L;
        for (int seconds : this.durations) {
            if (seconds <= 0) {
                return 0L;   // 时长未知就没法算曲目边界，宁可不切 喵
            }
            total += seconds * 20L;
        }
        return total;
    }

    /**
     * 上一曲 / 下一曲：把整条时间轴往前或往后挪一首歌。
     *
     * <p>会话只有「已播放 tick」这一个位置量（客户端按 elapsed % 总时长 定位曲目和偏移），
     * 所以切歌不需要改播放顺序，只要平移 elapsed：
     * <ul>
     *   <li>下一曲 = elapsed + 当前曲还没播的部分（正好落在下一首开头）；</li>
     *   <li>上一曲 = elapsed − 当前曲已播部分 − 上一首时长（正好落在上一首开头）；</li>
     *   <li>结果对总时长取模：第一首按「上一曲」绕到最后一首，最后一首按「下一曲」绕回第一首，
     *       永远不会出现负数（客户端会把负 elapsed 钳成 0）。 喵</li>
     * </ul>
     * 暂停中也一样生效：改的是暂停快照，恢复后从新位置继续。
     *
     * @param direction 负数 = 上一曲，正数 = 下一曲 喵
     * @return 是否真的移动了（顺序为空 / 时长未知时不动） 喵
     */
    public boolean skipTrack(int direction, long nowGameTime) {
        long total = this.totalTicks();
        if (direction == 0 || total <= 0L) {
            return false;
        }
        long elapsed = Math.floorMod(this.elapsedTicks(nowGameTime), total);
        long acc = 0L;
        int index = -1;
        long offset = 0L;
        for (int i = 0; i < this.durations.length; i++) {
            long length = this.durations[i] * 20L;
            if (elapsed < acc + length) {
                index = i;
                offset = elapsed - acc;
                break;
            }
            acc += length;
        }
        if (index < 0) {
            return false;
        }
        long target;
        if (direction > 0) {
            target = elapsed + (this.durations[index] * 20L - offset);
        } else {
            int previous = Math.floorMod(index - 1, this.durations.length);
            target = elapsed - offset - this.durations[previous] * 20L;
        }
        target = Math.floorMod(target, total);
        if (this.paused) {
            this.pausedElapsed = target;
        } else {
            this.startGameTime = nowGameTime - target;
        }
        return true;
    }

    /**
     * 跳到当前曲目的第 {@code offsetTicks} 个 tick（点进度条 / 拖动进度条）。
     *
     * <p>和 {@link #skipTrack} 一样只改「已播放 tick」，不动 selected、不重建会话：
     * 先按当前时间轴定位出「当前曲目 + 它在时间轴上的起点」，再加上曲内偏移即可。
     * 暂停中同样有效（改暂停快照，恢复后从这里继续）。 喵
     *
     * @param offsetTicks 曲内偏移（tick，0 = 本曲开头）；超出本曲长度会被夹住 喵
     * @return 是否真的移动了（顺序为空 / 时长未知 / 偏移非法时 false） 喵
     */
    public boolean seekInCurrentTrack(int offsetTicks, long nowGameTime) {
        long total = this.totalTicks();
        if (total <= 0L || offsetTicks < 0) {
            return false;
        }
        Located current = this.locate(this.elapsedTicks(nowGameTime));
        if (current == null) {
            return false;
        }
        long base = 0L;
        for (int i = 0; i < current.index(); i++) {
            base += this.durations[i] * 20L;
        }
        long length = this.durations[current.index()] * 20L;
        // 点到最右端时留在本曲最后一个 tick，而不是悄悄跳到下一首 喵
        long offset = Math.min(offsetTicks, Math.max(0L, length - 1L));
        long target = Math.floorMod(base + offset, total);
        if (this.paused) {
            this.pausedElapsed = target;
        } else {
            this.startGameTime = nowGameTime - target;
        }
        return true;
    }

    /** 曲目列表变化时应用增量 喵 */
    public Result apply(List<Track> newTracks, PlayMode mode, long nowGameTime, Durations durationSource) {
        if (newTracks == null || newTracks.isEmpty()) {
            return Result.REBUILD;
        }
        if (sameIds(newTracks, this.sourceTracks)) {
            return applyMetadata(newTracks, nowGameTime, durationSource);
        }
        if (isPrefix(this.sourceTracks, newTracks)) {
            return applyAppend(newTracks, mode, nowGameTime, durationSource);
        }
        if (isSubsequence(newTracks, this.sourceTracks)) {
            return applyRemoval(newTracks, nowGameTime, durationSource);
        }
        return Result.REBUILD;
    }

    // ------------------------------------------------------------ 三种增量 喵

    /** 曲目集合没变：可能只是改名，也可能时长提示被真实值替换 喵 */
    private Result applyMetadata(List<Track> newTracks, long nowGameTime, Durations durationSource) {
        boolean labelsChanged = !newTracks.equals(this.sourceTracks);
        int[] updated = durationsFor(this.order, durationSource);
        boolean durationsChanged = !Arrays.equals(updated, this.durations);
        if (!labelsChanged && !durationsChanged) {
            return Result.UNCHANGED;
        }
        Located old = locate(elapsedTicks(nowGameTime));
        this.sourceTracks = List.copyOf(newTracks);
        if (labelsChanged) {
            this.order = relabel(this.order, newTracks);
        }
        this.durations = updated;
        this.partsDirty = true;
        if (old != null) {
            retime(old, nowGameTime);
        }
        return Result.UPDATED;
    }

    /** 只是尾部追加了曲目（导入新歌）：把新曲目接在当前顺序后面 喵 */
    private Result applyAppend(List<Track> newTracks, PlayMode mode, long nowGameTime, Durations durationSource) {
        Located old = locate(elapsedTicks(nowGameTime));
        List<Track> added = new ArrayList<>(newTracks.subList(this.sourceTracks.size(), newTracks.size()));
        List<Track> newOrder = new ArrayList<>(this.order);
        if (mode == PlayMode.REPEAT_ONE) {
            // 单曲循环的播放顺序永远只有一首，追加不影响它 喵
            added.clear();
        } else if (mode == PlayMode.SHUFFLE) {
            // 随机播放：新曲目打乱后接到本次循环末尾，不会重复已经播过的 喵
            Collections.shuffle(added, new Random());
        }
        newOrder.addAll(added);
        this.sourceTracks = List.copyOf(newTracks);
        this.order = List.copyOf(newOrder);
        this.durations = durationsFor(this.order, durationSource);
        this.partsDirty = true;
        if (old != null) {
            retime(old, nowGameTime);
        }
        return Result.UPDATED;
    }

    /** 只删除了若干曲目：当前这首还活着就接着放，被删了就重建 喵 */
    private Result applyRemoval(List<Track> newTracks, long nowGameTime, Durations durationSource) {
        Set<String> keep = new HashSet<>();
        for (Track track : newTracks) {
            keep.add(track.id());
        }
        Located old = locate(elapsedTicks(nowGameTime));
        if (old != null && !keep.contains(old.track().id())) {
            return Result.REBUILD;   // 正在放的那首被删了，只能重来 喵
        }
        List<Track> newOrder = new ArrayList<>(this.order.size());
        for (Track track : this.order) {
            if (keep.contains(track.id())) {
                newOrder.add(track);
            }
        }
        if (newOrder.isEmpty()) {
            return Result.REBUILD;
        }
        this.sourceTracks = List.copyOf(newTracks);
        this.order = List.copyOf(newOrder);
        this.durations = durationsFor(this.order, durationSource);
        this.partsDirty = true;
        if (old != null) {
            // 删掉的是"当前曲目之前"的曲目时，这里会自动把起始 tick 往前挪，当前曲目不会跳 喵
            retime(old, nowGameTime);
        }
        return Result.UPDATED;
    }
    // ------------------------------------------------------------ 工具 喵

    /** 播放位置：当前曲目 + 曲内偏移（tick） 喵 */
    private record Located(Track track, int index, long offsetTicks) {
    }

    /** 按旧顺序/旧时长定位；时长未知（0）时返回 null 表示"没法保证不跳"，调用方按原地保留处理 喵 */
    private Located locate(long elapsedTicks) {
        if (this.order.isEmpty()) {
            return null;
        }
        long total = 0L;
        for (int seconds : this.durations) {
            if (seconds <= 0) {
                return null;
            }
            total += seconds * 20L;
        }
        if (total <= 0L) {
            return null;
        }
        long pos = Math.floorMod(elapsedTicks, total);
        long acc = 0L;
        for (int i = 0; i < this.order.size(); i++) {
            long length = this.durations[i] * 20L;
            if (pos < acc + length) {
                return new Located(this.order.get(i), i, pos - acc);
            }
            acc += length;
        }
        return new Located(this.order.get(0), 0, 0L);
    }

    /**
     * 保持"这首歌听到第几秒"不变地重设时间轴：
     * 增删/改时长之后必须走这一步，否则客户端会跳到别的位置。 喵
     */
    private void retime(Located old, long nowGameTime) {
        int index = indexOfId(this.order, old.track().id());
        if (index < 0) {
            return;
        }
        long base = 0L;
        for (int i = 0; i < index; i++) {
            base += Math.max(0, this.durations[i]) * 20L;
        }
        long length = Math.max(0, this.durations[index]) * 20L;
        long offset = Math.max(0L, Math.min(old.offsetTicks(), length));
        long elapsed = base + offset;
        if (this.paused) {
            this.pausedElapsed = elapsed;
        } else {
            this.startGameTime = nowGameTime - elapsed;
        }
    }

    /** 按当前顺序重新取一遍时长（导入完成后 hint 会被真实值替换） 喵 */
    private int[] durationsFor(List<Track> tracks, Durations durationSource) {
        int[] result = new int[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            int seconds = durationSource == null ? 0 : durationSource.secondsOf(tracks.get(i));
            result[i] = Math.max(0, seconds);
        }
        return result;
    }

    /** 用新的 label 替换顺序里的同名曲目（按 id 匹配，顺序不变） 喵 */
    private static List<Track> relabel(List<Track> order, List<Track> updated) {
        List<Track> result = new ArrayList<>(order.size());
        for (Track track : order) {
            Track match = null;
            for (Track candidate : updated) {
                if (candidate.id().equals(track.id())) {
                    match = candidate;
                    break;
                }
            }
            result.add(match == null ? track : match);
        }
        return List.copyOf(result);
    }

    private static int indexOfId(List<Track> order, String id) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameIds(List<Track> a, List<Track> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id())) {
                return false;
            }
        }
        return true;
    }

    /** a 是否是 b 的前缀（b 只在尾部追加了曲目） 喵 */
    private static boolean isPrefix(List<Track> a, List<Track> b) {
        if (a.size() > b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).id().equals(b.get(i).id())) {
                return false;
            }
        }
        return true;
    }

    /** a 是否是 b 保持顺序的子序列（只做了删除） 喵 */
    private static boolean isSubsequence(List<Track> a, List<Track> b) {
        if (a.size() >= b.size()) {
            return false;
        }
        int index = 0;
        for (Track track : b) {
            if (index < a.size() && a.get(index).id().equals(track.id())) {
                index++;
            }
        }
        return index == a.size();
    }

    /** 把顺序编码后切成网络分片；列表太大就返回 null（宁可不发） 喵 */
    public static List<String> encodeParts(List<Track> order) {
        String encoded = encode(order);
        if (encoded.length() > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
            return null;
        }
        List<String> parts = SpeakerPayloads.splitTracks(encoded);
        return parts.size() > SpeakerPayloads.MAX_PARTS ? null : parts;
    }

    /** 曲目列表编码：label\u0001id，条目之间 \u0002 喵 */
    public static String encode(List<Track> tracks) {
        StringBuilder sb = new StringBuilder();
        if (tracks != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (i > 0) {
                    sb.append('\u0002');
                }
                Track track = tracks.get(i);
                sb.append(track.label() == null ? "" : track.label()).append('\u0001')
                        .append(track.id() == null ? "" : track.id());
            }
        }
        return sb.toString();
    }

    /**
     * 可用性位图：第 i 位 = 顺序里第 i 首现在能不能出声。
     *
     * <p>它由 {@link AudioStore} 派生，不存进播放列表，所以永远不会陈旧；
     * 同一份音频被多个列表引用时，一次上传完成会把所有列表里的那一位同时点亮。 喵
     */
    public static byte[] availabilityBits(List<Track> order) {
        byte[] bits = new byte[(order.size() + 7) / 8];
        for (int i = 0; i < order.size(); i++) {
            Track track = order.get(i);
            // 内置曲目永远算可用；导入曲目看仓库里有没有字节 喵
            boolean ok = !track.isFile() || AudioStore.available(track.sha1());
            if (ok) {
                bits[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return bits;
    }

    /** 顺序里现在有多少首已经有字节（诊断用） 喵 */
    public static int availableCount(List<Track> order) {
        int count = 0;
        for (Track track : order) {
            if (!track.isFile() || AudioStore.available(track.sha1())) {
                count++;
            }
        }
        return count;
    }
}
