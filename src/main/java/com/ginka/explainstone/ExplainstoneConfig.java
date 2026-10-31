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

/** 简易配置：config/explainstone-common.toml 喵 */
public final class ExplainstoneConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 广播是否只允许 OP（默认 false，谁都能广播） 喵 */
    public static final ForgeConfigSpec.BooleanValue BROADCAST_REQUIRES_OP = BUILDER
            .comment("Whether only operators (permission level 2) may start a broadcast.")
            .define("broadcastRequiresOp", false);

    /**
     * 100% 音量时的可闻距离（格）。
     * 实际距离 = 该值 × 音量百分比；例如音量 50% 时范围减半 喵
     */
    public static final ForgeConfigSpec.IntValue AUDIBLE_RANGE_AT_FULL_VOLUME = BUILDER
            .comment("Audible range in blocks at 100% volume.",
                    "The effective range scales linearly with volume (50% volume = half the range).",
                    "Keep this value identical on the client and the server.")
            .defineInRange("audibleRangeAtFullVolume", 40, 8, 256);

    /**
     * 站在音响正后方时的音量倍率（0.0 = 完全听不到，1.0 = 关闭前后方向衰减）。
     * 侧面在 1.0 与该值之间线性过渡。只影响客户端播放音量，不影响全服时间轴 喵
     */
    public static final ForgeConfigSpec.DoubleValue REAR_VOLUME_FACTOR = BUILDER
            .comment("Volume multiplier when the listener is directly behind a speaker.",
                    "0.0 = silent behind, 1.0 = no front/back attenuation.",
                    "Sides interpolate smoothly between front (1.0) and rear.",
                    "Client-side only; it does not affect playback synchronization.")
            .defineInRange("rearVolumeFactor", 0.25, 0.0, 1.0);

    /** 是否启用直达声遮挡（墙壁会减弱声音） 喵 */
    public static final ForgeConfigSpec.BooleanValue SOUND_OCCLUSION = BUILDER
            .comment("Attenuate direct sound when blocks are between a speaker and the listener.",
                    "High frequencies are reduced more than low frequencies when OpenAL EFX is available.",
                    "Client-side effect; does not affect playback synchronization.")
            .define("soundOcclusion", true);

    /** 是否启用房间混响 喵 */
    public static final ForgeConfigSpec.BooleanValue REVERB_ENABLED = BUILDER
            .comment("Enable room reverb for speakers inside an enclosed room.",
                    "The room acoustics are estimated on the client from the blocks around each speaker.",
                    "Client-side effect; it does not affect playback synchronization.")
            .define("reverbEnabled", true);

    /** 房间采样射线的最大距离（格） 喵 */
    public static final ForgeConfigSpec.DoubleValue REVERB_MAX_ROOM_DISTANCE = BUILDER
            .comment("Maximum distance used when sampling the room around a speaker.",
                    "Rooms larger than this may be treated as open/outdoor and get no reverb.")
            .defineInRange("reverbMaxRoomDistance", 48.0, 8.0, 128.0);

    /** EFX Reverb 效果器总增益 喵 */
    public static final ForgeConfigSpec.DoubleValue REVERB_GAIN = BUILDER
            .comment("EFX reverb effect gain (0.0 - 1.0).")
            .defineInRange("reverbGain", 0.3, 0.0, 1.0);

    /** EFX Reverb 高频增益 喵 */
    public static final ForgeConfigSpec.DoubleValue REVERB_GAIN_HF = BUILDER
            .comment("EFX reverb high-frequency gain (0.0 - 1.0).")
            .defineInRange("reverbGainHF", 0.8, 0.0, 1.0);

    /** EFX 辅助发送的湿声增益 喵 */
    public static final ForgeConfigSpec.DoubleValue REVERB_WET = BUILDER
            .comment("Wet return level for the reverb auxiliary send (0.0 - 1.0).")
            .defineInRange("reverbWet", 0.8, 0.0, 1.0);

    /** 单个导入音频文件大小上限（MB） 喵 */
    public static final ForgeConfigSpec.IntValue MAX_AUDIO_FILE_MB = BUILDER
            .comment("Maximum size of an imported ogg file in MiB.",
                    "Keep this value identical on the client and the server.")
            .defineInRange("maxAudioFileMb", 10, 1, 100);

    /** 单曲最大时长（秒） 喵 */
    public static final ForgeConfigSpec.IntValue MAX_AUDIO_DURATION_SECONDS = BUILDER
            .comment("Maximum duration of a single imported track in seconds.",
                    "Keep this value identical on the client and the server.")
            .defineInRange("maxAudioDurationSeconds", 600, 1, 7200);

    /** 播放列表最大曲目数 喵 */
    public static final ForgeConfigSpec.IntValue MAX_PLAYLIST_TRACKS = BUILDER
            .comment("Maximum number of tracks in one speaker/transmitter playlist.",
                    "Keep this value identical on the client and the server.")
            .defineInRange("maxPlaylistTracks", 500, 1, 10000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ExplainstoneConfig() {
    }

    /** 广播是否只允许 OP（默认 false，谁都能广播） 喵 */
    public static boolean broadcastRequiresOp() {
        return BROADCAST_REQUIRES_OP.get();
    }

    /** 100% 音量时的可闻距离（格） 喵 */
    public static int audibleRangeAtFullVolume() {
        return AUDIBLE_RANGE_AT_FULL_VOLUME.get();
    }

    /** 正后方音量倍率 喵 */
    public static double rearVolumeFactor() {
        return REAR_VOLUME_FACTOR.get();
    }

    /** 是否启用墙壁遮挡衰减 喵 */
    public static boolean soundOcclusion() {
        return SOUND_OCCLUSION.get();
    }

    /** 是否启用房间混响 喵 */
    public static boolean reverbEnabled() {
        return REVERB_ENABLED.get();
    }

    /** 房间采样最大距离 喵 */
    public static double reverbMaxRoomDistance() {
        return REVERB_MAX_ROOM_DISTANCE.get();
    }

    /** EFX Reverb 总增益 喵 */
    public static double reverbGain() {
        return REVERB_GAIN.get();
    }

    /** EFX Reverb 高频增益 喵 */
    public static double reverbGainHF() {
        return REVERB_GAIN_HF.get();
    }

    /** 混响湿声增益 喵 */
    public static double reverbWet() {
        return REVERB_WET.get();
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
}
