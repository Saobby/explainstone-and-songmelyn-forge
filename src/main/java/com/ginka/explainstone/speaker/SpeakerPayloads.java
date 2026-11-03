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

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 讲解石界面的所有自定义网络包（Forge 1.20.1 的 SimpleChannel 写法） 喵 */
public final class SpeakerPayloads {
    // 客户端 → 服务端：界面上的各种操作 喵
    public static final int ACTION_PLAY = 0;
    public static final int ACTION_STOP = 1;
    public static final int ACTION_CYCLE_MODE = 2;
    public static final int ACTION_TOGGLE_REDSTONE = 3;
    public static final int ACTION_SELECT = 4;
    public static final int ACTION_REMOVE_TRACK = 5;
    public static final int ACTION_PLAY_INDEX = 6;
    /** 设置频道（value = 0..999） 喵 */
    public static final int ACTION_SET_CHANNEL = 7;
    /** 切换收信模式 喵 */
    public static final int ACTION_TOGGLE_RECEIVE = 8;
    /** 设置定时播放秒数（value = 0/60/180/600） 喵 */
    public static final int ACTION_SET_TIMER = 9;
    /** 设置发信器传播距离（value = 32..512） 喵 */
    public static final int ACTION_SET_TRANSMIT_RANGE = 11;
    /** 设置本机音量（value = 0..100，同时决定可闻范围） 喵 */
    public static final int ACTION_SET_VOLUME = 13;
    /** 清空播放列表 喵 */
    public static final int ACTION_REMOVE_ALL = 14;
    /** 暂停：冻结当前进度，客户端静音但服务端保留恢复点 喵 */
    public static final int ACTION_PAUSE = 15;
    /** 设置发信器全局音量（value = 0..100，乘到所有收信音响的音量上） 喵 */
    public static final int ACTION_SET_BROADCAST_VOLUME = 16;
    /**
     * 上一曲 / 下一曲（value：负数 = 上一曲，正数 = 下一曲）。
     * 只平移会话时间轴（elapsedTicks），不换选中曲目，所以不会中断/重置进度。 喵
     */
    public static final int ACTION_SKIP = 17;

    /** 单次传输的分块大小（远小于包上限） 喵 */
    public static final int CHUNK_SIZE = 8000;
    /** 一次预检最多带多少个 SHA-1（分批发送，避免包太大） 喵 */
    public static final int MAX_QUERY_ENTRIES = 512;
    /** label 在网络上最多传输的长度 喵 */
    public static final int MAX_LABEL_LENGTH = 128;
    /** SHA-1 固定 40 位小写十六进制 喵 */
    public static final int SHA1_LENGTH = 40;
    /**
     * 单个分片（一帧）里最多携带多少个编码字符。UTF-8 最坏 3 字节/字符 → 约 192 KiB，
     * 远低于 Minecraft 单帧 2 MiB（21 位长度 varint）的上限。 喵
     */
    public static final int MAX_PART_CHARS = 65_536;
    /**
     * 一个播放会话最多允许多少个编码字符（仅作为客户端内存上限）。
     * 默认 10000 首 × (128 label + 1 + 45 id + 1) ≈ 1.75M，留一点余量。 喵
     */
    public static final int MAX_PLAYLIST_CHARS = 2_000_000;
    /** 最多允许多少个分片（防御恶意服务端/异常数据） 喵 */
    public static final int MAX_PARTS = 512;
    /** 可用性位图的最大字节数（10000 首也只需要 1250 字节） 喵 */
    public static final int MAX_PLAYLIST_BITS_BYTES = 4096;

    private SpeakerPayloads() {
    }

    /**
     * 把编码后的播放列表切成多个分片。
     * 优先在曲目分隔符 \u0002 处切，避免把 label 切坏；单条曲目异常长时才退到
     * 安全的字符边界（不会切在代理对中间）。拼起来必须与原文完全一致。 喵
     */
    public static List<String> splitTracks(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of("");
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < encoded.length()) {
            int end = Math.min(encoded.length(), start + MAX_PART_CHARS);
            if (end < encoded.length()) {
                int cut = encoded.lastIndexOf('\u0002', end - 1);
                if (cut >= start) {
                    end = cut + 1;   // 分片带着分隔符，拼接时不会丢内容 喵
                } else if (Character.isLowSurrogate(encoded.charAt(end))
                        && Character.isHighSurrogate(encoded.charAt(end - 1))) {
                    end--;   // 不要切在代理对中间 喵
                }
            }
            parts.add(encoded.substring(start, end));
            start = end;
        }
        return List.copyOf(parts);
    }

    /** 播放 / 停止 / 切模式 / 红石开关 / 选曲 / 删曲 喵 */
    public record Action(BlockPos pos, int action, int value) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeVarInt(this.action);
            buf.writeVarInt(this.value);
        }

        public static Action decode(FriendlyByteBuf buf) {
            return new Action(buf.readBlockPos(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** 客户端 → 服务端：导入一个音频文件（分块上传，存进存档） 喵 */
    public record ImportChunk(BlockPos pos, String label, String sha1, int index, int total, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeUtf(this.label, MAX_LABEL_LENGTH);
            buf.writeUtf(this.sha1, SHA1_LENGTH);
            buf.writeVarInt(this.index);
            buf.writeVarInt(this.total);
            buf.writeByteArray(this.data);
        }

        public static ImportChunk decode(FriendlyByteBuf buf) {
            return new ImportChunk(buf.readBlockPos(), buf.readUtf(MAX_LABEL_LENGTH), buf.readUtf(SHA1_LENGTH),
                    buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_SIZE));
        }
    }

    /**
     * 客户端 → 服务端：提交一批要导入的曲目（**只需要元数据**）。
     *
     * <p>服务端会立刻把它们加进播放列表（受配额限制），回包里告诉客户端
     * 哪些 sha1 的字节还没有（{@code missing}），客户端只上传那些。
     * 这样"上传"和"播放列表"彻底解耦：列表立刻完整，字节慢慢传。 喵
     */
    public record PlaylistAdd(BlockPos pos, long queryId, List<Entry> entries) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeLong(this.queryId);
            int size = this.entries == null ? 0 : this.entries.size();
            buf.writeVarInt(size);
            for (int i = 0; i < size; i++) {
                Entry entry = this.entries.get(i);
                buf.writeUtf(entry.label(), MAX_LABEL_LENGTH);
                buf.writeUtf(entry.sha1(), SHA1_LENGTH);
                // 时长提示：播放列表的时间轴只依赖元数据，没有它就没法算进度 喵
                buf.writeVarInt(Math.max(0, entry.seconds()));
            }
        }

        public static PlaylistAdd decode(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            long queryId = buf.readLong();
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_QUERY_ENTRIES) {
                size = 0;
            }
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                entries.add(new Entry(buf.readUtf(MAX_LABEL_LENGTH), buf.readUtf(SHA1_LENGTH), buf.readVarInt()));
            }
            return new PlaylistAdd(pos, queryId, entries);
        }

        public record Entry(String label, String sha1, int seconds) {
        }
    }

    /**
     * 服务端 → 客户端：提交结果。
     * missing 里的 sha1 需要上传字节；rejected 里的（配额满 / 时长非法 / 冷却中）没有被加进列表 喵
     */
    public record PlaylistAddResult(BlockPos pos, long queryId, boolean accepted,
                                    List<String> missing, List<String> rejected) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeLong(this.queryId);
            buf.writeBoolean(this.accepted);
            writeSha1List(buf, this.missing);
            writeSha1List(buf, this.rejected);
        }

        public static PlaylistAddResult decode(FriendlyByteBuf buf) {
            BlockPos pos = buf.readBlockPos();
            long queryId = buf.readLong();
            boolean accepted = buf.readBoolean();
            List<String> missing = readSha1List(buf);
            List<String> rejected = readSha1List(buf);
            return new PlaylistAddResult(pos, queryId, accepted, missing, rejected);
        }
    }

    /** 服务端 → 客户端：某几份音频的可用性变了（上传完成 / 文件被回收） 喵 */
    public record AudioAvailability(List<String> sha1, boolean available) {
        public void encode(FriendlyByteBuf buf) {
            writeSha1List(buf, this.sha1);
            buf.writeBoolean(this.available);
        }

        public static AudioAvailability decode(FriendlyByteBuf buf) {
            List<String> sha1 = readSha1List(buf);
            return new AudioAvailability(sha1, buf.readBoolean());
        }
    }

    static void writeSha1List(FriendlyByteBuf buf, List<String> values) {
        int size = values == null ? 0 : values.size();
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUtf(values.get(i), SHA1_LENGTH);
        }
    }

    static List<String> readSha1List(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_QUERY_ENTRIES) {
            size = 0;
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUtf(SHA1_LENGTH));
        }
        return values;
    }

    /** 可用性位图：第 i 位对应播放顺序里的第 i 首，1 = 本地/服务端有这份音频 喵 */
    static void writeBits(FriendlyByteBuf buf, byte[] bits) {
        buf.writeByteArray(bits == null ? new byte[0] : bits);
    }

    static byte[] readBits(FriendlyByteBuf buf) {
        byte[] bits = buf.readByteArray(MAX_PLAYLIST_BITS_BYTES);
        return bits == null ? new byte[0] : bits;
    }

    /** 客户端 → 服务端：本地没有这个音频，请求下载 喵 */
    public record RequestAudio(String sha1) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.sha1, SHA1_LENGTH);
        }

        public static RequestAudio decode(FriendlyByteBuf buf) {
            return new RequestAudio(buf.readUtf(SHA1_LENGTH));
        }
    }

    /** 客户端 → 服务端：我这边没收到广播，请重推一次 喵 */
    public record Resync() {
        public void encode(FriendlyByteBuf buf) {
        }

        public static Resync decode(FriendlyByteBuf buf) {
            return new Resync();
        }
    }

    /** 时长数组：0 表示未知；和 tracks 顺序一一对应 喵 */
    static void writeDurations(FriendlyByteBuf buf, int[] durations) {
        if (durations == null) {
            buf.writeVarInt(0);
            return;
        }
        buf.writeVarInt(durations.length);
        for (int duration : durations) {
            buf.writeVarInt(Math.max(0, duration));
        }
    }

    static int[] readDurations(FriendlyByteBuf buf) {
        int length = buf.readVarInt();
        if (length <= 0 || length > 10000) {
            return new int[0];
        }
        int[] durations = new int[length];
        for (int i = 0; i < length; i++) {
            durations[i] = buf.readVarInt();
        }
        return durations;
    }

    /**
     * 服务端 → 客户端：某频道开始/停止广播（信号范围 512 格）。
     * volume 是发信器的全局音量（0-100），收信音响的实际音量 = 本机音量 × 它。
     * 播放列表过大时会拆成 partCount 帧发送，partIndex 从 0 开始；durations 只在第 0 帧带。 喵
     */
    public record Broadcast(int channel, int mode, boolean active, boolean paused, String tracks,
                            int[] durations, long elapsedTicks, long revision, int partIndex, int partCount,
                            int volume, byte[] available) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.channel);
            buf.writeVarInt(this.mode);
            buf.writeBoolean(this.active);
            buf.writeBoolean(this.paused);
            buf.writeUtf(this.tracks == null ? "" : this.tracks, MAX_PART_CHARS);
            writeDurations(buf, this.durations);
            buf.writeLong(this.elapsedTicks);
            buf.writeLong(this.revision);
            buf.writeVarInt(this.partIndex);
            buf.writeVarInt(this.partCount);
            buf.writeVarInt(this.volume);
            // 可用性位图只在第 0 帧带（其它帧是空的），客户端自己拼 喵
            writeBits(buf, this.available);
        }

        public static Broadcast decode(FriendlyByteBuf buf) {
            return new Broadcast(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    buf.readUtf(MAX_PART_CHARS), readDurations(buf), buf.readLong(), buf.readLong(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readBits(buf));
        }
    }

    /**
     * 服务端 → 客户端：某频道的全局音量变化（发信器音量滑块）。
     * 单独发一个几十字节的小包，拖滑块时不用把整张播放列表重推一遍 喵
     */
    public record BroadcastVolume(int channel, int volume) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.channel);
            buf.writeVarInt(this.volume);
        }

        public static BroadcastVolume decode(FriendlyByteBuf buf) {
            return new BroadcastVolume(buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * 服务端 → 客户端：某个讲解石方块的本地播放会话。
     * elapsedTicks 是发包时已经播放的游戏 tick 数，客户端再加本地经过的 tick 即可对齐；
     * 播放列表过大时会拆成 partCount 帧发送，durations 只在第 0 帧带。 喵
     */
    public record Playback(BlockPos pos, int mode, boolean active, boolean paused, String tracks,
                           int[] durations, long elapsedTicks, long revision, int partIndex, int partCount,
                           byte[] available) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeVarInt(this.mode);
            buf.writeBoolean(this.active);
            buf.writeBoolean(this.paused);
            buf.writeUtf(this.tracks == null ? "" : this.tracks, MAX_PART_CHARS);
            writeDurations(buf, this.durations);
            buf.writeLong(this.elapsedTicks);
            buf.writeLong(this.revision);
            buf.writeVarInt(this.partIndex);
            buf.writeVarInt(this.partCount);
            // 可用性位图只在第 0 帧带（其它帧是空的），客户端自己拼 喵
            writeBits(buf, this.available);
        }

        public static Playback decode(FriendlyByteBuf buf) {
            return new Playback(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    buf.readUtf(MAX_PART_CHARS), readDurations(buf), buf.readLong(), buf.readLong(),
                    buf.readVarInt(), buf.readVarInt(), readBits(buf));
        }
    }

    /** 服务端 → 客户端：音频文件分块（label 只是给缓存起个可读的名字） 喵 */
    public record AudioChunk(String sha1, String label, int index, int total, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.sha1, SHA1_LENGTH);
            buf.writeUtf(this.label, MAX_LABEL_LENGTH);
            buf.writeVarInt(this.index);
            buf.writeVarInt(this.total);
            buf.writeByteArray(this.data);
        }

        public static AudioChunk decode(FriendlyByteBuf buf) {
            return new AudioChunk(buf.readUtf(SHA1_LENGTH), buf.readUtf(MAX_LABEL_LENGTH),
                    buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_SIZE));
        }
    }
}
