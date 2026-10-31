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
    /** 切换本机是否放音 喵 */
    public static final int ACTION_TOGGLE_ENABLED = 10;
    /** 设置发信器传播距离（value = 32..512） 喵 */
    public static final int ACTION_SET_TRANSMIT_RANGE = 11;
    /** 设置本机音量（value = 0..100，同时决定可闻范围） 喵 */
    public static final int ACTION_SET_VOLUME = 13;

    /** 单次传输的分块大小（远小于包上限） 喵 */
    public static final int CHUNK_SIZE = 8000;

    private SpeakerPayloads() {
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
            buf.writeUtf(this.label);
            buf.writeUtf(this.sha1);
            buf.writeVarInt(this.index);
            buf.writeVarInt(this.total);
            buf.writeByteArray(this.data);
        }

        public static ImportChunk decode(FriendlyByteBuf buf) {
            return new ImportChunk(buf.readBlockPos(), buf.readUtf(), buf.readUtf(),
                    buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
        }
    }

    /** 客户端 → 服务端：本地没有这个音频，请求下载 喵 */
    public record RequestAudio(String sha1) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.sha1);
        }

        public static RequestAudio decode(FriendlyByteBuf buf) {
            return new RequestAudio(buf.readUtf());
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

    /** 服务端 → 客户端：某频道开始/停止广播（信号范围 512 格） 喵 */
    public record Broadcast(int channel, int mode, boolean active, String tracks, int[] durations,
                            long elapsedTicks, long revision) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.channel);
            buf.writeVarInt(this.mode);
            buf.writeBoolean(this.active);
            buf.writeUtf(this.tracks);
            writeDurations(buf, this.durations);
            buf.writeLong(this.elapsedTicks);
            buf.writeLong(this.revision);
        }

        public static Broadcast decode(FriendlyByteBuf buf) {
            return new Broadcast(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                    buf.readUtf(), readDurations(buf), buf.readLong(), buf.readLong());
        }
    }

    /**
     * 服务端 → 客户端：某个讲解石方块的本地播放会话。
     * elapsedTicks 是发包时已经播放的游戏 tick 数，客户端再加本地经过的 tick 即可对齐 喵
     */
    public record Playback(BlockPos pos, int mode, boolean active, String tracks, int[] durations,
                           long elapsedTicks, long revision) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(this.pos);
            buf.writeVarInt(this.mode);
            buf.writeBoolean(this.active);
            buf.writeUtf(this.tracks);
            writeDurations(buf, this.durations);
            buf.writeLong(this.elapsedTicks);
            buf.writeLong(this.revision);
        }

        public static Playback decode(FriendlyByteBuf buf) {
            return new Playback(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(),
                    buf.readUtf(), readDurations(buf), buf.readLong(), buf.readLong());
        }
    }

    /** 服务端 → 客户端：音频文件分块（label 只是给缓存起个可读的名字） 喵 */
    public record AudioChunk(String sha1, String label, int index, int total, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.sha1);
            buf.writeUtf(this.label);
            buf.writeVarInt(this.index);
            buf.writeVarInt(this.total);
            buf.writeByteArray(this.data);
        }

        public static AudioChunk decode(FriendlyByteBuf buf) {
            return new AudioChunk(buf.readUtf(), buf.readUtf(),
                    buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
        }
    }
}
