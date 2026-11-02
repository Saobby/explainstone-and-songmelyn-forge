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

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 服务端配置（{@link net.minecraftforge.fml.config.ModConfig.Type#SERVER}）。
 *
 * <p>文件位置：单人存档 {@code saves/<存档>/serverconfig/explainstone-server.toml}，
 * 专用服务器 {@code <服务端目录>/world/serverconfig/explainstone-server.toml}。
 * 这份配置由服务端持有，玩家连进来时会自动同步到客户端，所以
 * {@code audibleRangeAtFullVolume} 这类"两边必须一致"的值放这里最安全。 喵
 */
public final class ExplainstoneServerConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 广播是否只允许 OP（默认 false，谁都能广播） 喵 */
    public static final ForgeConfigSpec.BooleanValue BROADCAST_REQUIRES_OP = BUILDER
            .comment("Whether only operators (permission level 2) may start a broadcast.",
                    "When true, redstone power cannot start a broadcast either (it can still stop one).")
            .define("broadcastRequiresOp", false);

    /**
     * 100% 音量时的可闻距离（格）。
     * 实际距离 = 该值 × 音量百分比；例如音量 50% 时范围减半。
     * 这是服务端配置，会自动同步给客户端，两边不会再对不上 喵
     */
    public static final ForgeConfigSpec.IntValue AUDIBLE_RANGE_AT_FULL_VOLUME = BUILDER
            .comment("Audible range in blocks at 100% volume.",
                    "The effective range scales linearly with volume (50% volume = half the range).",
                    "Synced to clients automatically, so both sides always agree.")
            .defineInRange("audibleRangeAtFullVolume", 40, 8, 256);

    /** 单个导入音频文件大小上限（MB） 喵 */
    public static final ForgeConfigSpec.IntValue MAX_AUDIO_FILE_MB = BUILDER
            .comment("Maximum size of an imported ogg file in MiB.",
                    "The client pre-checks it and the server enforces it again.")
            .defineInRange("maxAudioFileMb", 10, 1, 100);

    /** 单曲最大时长（秒） 喵 */
    public static final ForgeConfigSpec.IntValue MAX_AUDIO_DURATION_SECONDS = BUILDER
            .comment("Maximum duration of a single imported track in seconds.",
                    "Also the upper bound for the duration hint a client may submit.")
            .defineInRange("maxAudioDurationSeconds", 600, 1, 7200);

    /** 播放列表最大曲目数 喵 */
    public static final ForgeConfigSpec.IntValue MAX_PLAYLIST_TRACKS = BUILDER
            .comment("Maximum number of tracks in one speaker/transmitter playlist.")
            .defineInRange("maxPlaylistTracks", 500, 1, 10000);

    // ------------------------------------------------------------ 乐观导入的配额 喵
    /**
     * 每个玩家最多同时占多少条"元数据已进列表、字节还没到"的待上传曲目。
     * 这是乐观导入的代价上限：防止有人只发元数据就把别人的播放列表塞满。 喵
     */
    public static final ForgeConfigSpec.IntValue PENDING_IMPORTS_PER_PLAYER = BUILDER
            .comment("How many 'metadata added, bytes not uploaded yet' tracks one player may hold at once.",
                    "Pending entries are dropped again after pendingImportTimeoutSeconds without bytes.",
                    "Lower it if you want to clamp metadata-only spam harder.")
            .defineInRange("pendingImportsPerPlayer", 500, 1, 2000);

    /** 待上传条目的存活时间（秒）：到点还没收到字节就从播放列表里摘掉 喵 */
    public static final ForgeConfigSpec.IntValue PENDING_IMPORT_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds a pending (metadata-only) track survives without its audio bytes arriving.",
                    "After that the entry is removed from the playlist again.")
            .defineInRange("pendingImportTimeoutSeconds", 120, 5, 3600);

    /** 校验失败后的冷却时间（秒）：这段时间内该玩家不能再提交导入 喵 */
    public static final ForgeConfigSpec.IntValue IMPORT_FAILURE_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown in seconds after a failed upload validation (bad sha1 / not ogg / too long).",
                    "0 disables the cooldown.")
            .defineInRange("importFailureCooldownSeconds", 60, 0, 3600);
    // ------------------------------------------------------------ 服务器限速 / 抗滥用 喵
    /** 每个玩家同时进行的导入上传数 喵 */
    public static final ForgeConfigSpec.IntValue MAX_UPLOADS_PER_PLAYER = BUILDER
            .comment("Server-side anti-abuse: maximum simultaneous chunked imports per player.",
                    "A normal client uploads one file at a time; 4 leaves room for retries.")
            .defineInRange("maxUploadsPerPlayer", 4, 1, 64);
    /** 全服同时进行的导入上传数 喵 */
    public static final ForgeConfigSpec.IntValue MAX_UPLOADS_TOTAL = BUILDER
            .comment("Maximum simultaneous chunked imports on the whole server.",
                    "Set this to at least maxUploadsPerPlayer × expected player count.")
            .defineInRange("maxUploadsTotal", 128, 1, 4096);
    /** 全服半截上传占用的总内存 喵 */
    public static final ForgeConfigSpec.IntValue MAX_UPLOAD_BUFFER_MB = BUILDER
            .comment("Total memory budget in MiB for incomplete uploads.",
                    "When exceeded, the current upload is dropped and the client is told to retry later.")
            .defineInRange("maxUploadBufferMb", 256, 8, 4096);

    /** 界面操作令牌桶：突发容量 + 每秒回填 喵 */
    public static final ForgeConfigSpec.IntValue ACTION_BURST = BUILDER
            .comment("Server-side anti-abuse: burst capacity of interface actions per player.")
            .defineInRange("actionBurst", 30, 1, 1000);
    public static final ForgeConfigSpec.IntValue ACTION_REFILL_PER_SECOND = BUILDER
            .comment("Interface actions allowed per second per player (after the burst is used).")
            .defineInRange("actionRefillPerSecond", 15, 1, 500);

    /** 音频下载请求令牌桶：突发容量 + 每秒回填 喵 */
    public static final ForgeConfigSpec.IntValue AUDIO_REQUEST_BURST = BUILDER
            .comment("Server-side anti-abuse: burst capacity of audio download requests per player.",
                    "A joining client may request the current + next track for several speakers.")
            .defineInRange("audioRequestBurst", 24, 1, 1000);
    public static final ForgeConfigSpec.IntValue AUDIO_REQUEST_REFILL_PER_SECOND = BUILDER
            .comment("Audio download requests allowed per second per player.")
            .defineInRange("audioRequestRefillPerSecond", 6, 1, 500);

    /** 音频下载流量令牌桶：突发 MB + 每秒回填 MB 喵 */
    public static final ForgeConfigSpec.IntValue AUDIO_DOWNLOAD_BURST_MB = BUILDER
            .comment("Server-side anti-abuse: burst budget in MiB for audio downloads per player.",
                    "Each request consumes the file size; normal listening stays far below this.")
            .defineInRange("audioDownloadBurstMb", 128, 1, 4096);
    public static final ForgeConfigSpec.IntValue AUDIO_DOWNLOAD_MB_PER_SECOND = BUILDER
            .comment("Audio download budget refilled per second per player, in MiB/s.")
            .defineInRange("audioDownloadMbPerSecond", 8, 1, 1024);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ExplainstoneServerConfig() {
    }

    /** 广播是否只允许 OP（默认 false，谁都能广播） 喵 */
    public static boolean broadcastRequiresOp() {
        return BROADCAST_REQUIRES_OP.get();
    }

    /** 100% 音量时的可闻距离（格） 喵 */
    public static int audibleRangeAtFullVolume() {
        try {
            return AUDIBLE_RANGE_AT_FULL_VOLUME.get();
        } catch (RuntimeException e) {
            // 服务端配置还没同步到客户端（正常不会发生）时给个安全默认值，别让渲染线程崩 喵
            return 40;
        }
    }

    /** 单个导入文件最大字节数 喵 */
    public static long maxAudioFileBytes() {
        return (long) MAX_AUDIO_FILE_MB.get() * 1024L * 1024L;
    }

    /** 导入文件大小上限（MB），用于提示 喵 */
    public static int maxAudioFileMb() {
        return MAX_AUDIO_FILE_MB.get();
    }

    /** 单曲最大时长（秒） 喵 */
    public static int maxAudioDurationSeconds() {
        return MAX_AUDIO_DURATION_SECONDS.get();
    }

    /** 播放列表最大曲目数 喵 */
    public static int maxPlaylistTracks() {
        return MAX_PLAYLIST_TRACKS.get();
    }

    /** 每玩家同时可占的待上传条目数 喵 */
    public static int pendingImportsPerPlayer() {
        return PENDING_IMPORTS_PER_PLAYER.get();
    }

    /** 待上传条目的存活时间（秒） 喵 */
    public static int pendingImportTimeoutSeconds() {
        return PENDING_IMPORT_TIMEOUT_SECONDS.get();
    }

    /** 校验失败后的冷却时间（秒） 喵 */
    public static int importFailureCooldownSeconds() {
        return IMPORT_FAILURE_COOLDOWN_SECONDS.get();
    }

    /** 每玩家同时进行的上传数 喵 */
    public static int maxUploadsPerPlayer() {
        return MAX_UPLOADS_PER_PLAYER.get();
    }

    /** 全服同时进行的上传数 喵 */
    public static int maxUploadsTotal() {
        return MAX_UPLOADS_TOTAL.get();
    }

    /** 全服半截上传内存预算（字节） 喵 */
    public static long maxUploadBufferBytes() {
        return (long) MAX_UPLOAD_BUFFER_MB.get() * 1024L * 1024L;
    }

    /** 界面操作突发容量 喵 */
    public static double actionBurst() {
        return ACTION_BURST.get();
    }

    /** 界面操作每秒回填 喵 */
    public static double actionRefillPerSecond() {
        return ACTION_REFILL_PER_SECOND.get();
    }

    /** 音频请求突发容量 喵 */
    public static double audioRequestBurst() {
        return AUDIO_REQUEST_BURST.get();
    }

    /** 音频请求每秒回填 喵 */
    public static double audioRequestRefillPerSecond() {
        return AUDIO_REQUEST_REFILL_PER_SECOND.get();
    }

    /** 音频下载流量突发预算（MB） 喵 */
    public static double audioDownloadBurstMb() {
        return AUDIO_DOWNLOAD_BURST_MB.get();
    }

    /** 音频下载流量每秒回填（MB/s） 喵 */
    public static double audioDownloadMbPerSecond() {
        return AUDIO_DOWNLOAD_MB_PER_SECOND.get();
    }
}
