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

import java.util.List;

/**
 * 客户端配置（{@link net.minecraftforge.fml.config.ModConfig.Type#CLIENT}）。
 *
 * <p>文件位置：{@code config/explainstone-client.toml}，只影响本机的听觉效果和界面，
 * 不会同步、也不会影响服务端时间轴。服务端相关的东西都在
 * {@link ExplainstoneServerConfig} 里。 喵
 */
public final class ExplainstoneClientConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

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
                    "Client-side effect; does not affect playback synchronization.")
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

    /**
     * 导入音频时默认用哪种文件选择框。
     * system  = 系统原生对话框（Linux 上 zenity/qarma/kdialog，其它平台 AWT），开不出来就自动退到游戏内浏览器；
     * in-game = 直接用游戏内文件浏览器（方便在任意平台测试它） 喵
     */
    public static final ForgeConfigSpec.ConfigValue<String> FILE_PICKER = BUILDER
            .comment("Which file picker the \"Import ogg…\" button opens.",
                    "system  - the desktop's native dialog (zenity/qarma/kdialog on Linux, AWT elsewhere).",
                    "          If no system dialog can be created, the in-game file browser is used instead.",
                    "in-game - always use the in-game file browser (handy for testing on any platform).",
                    "Values: system, in-game. Client-side only.")
            .defineInList("filePicker", "system", List.of("system", "in-game"));

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ExplainstoneClientConfig() {
    }
    // ------------------------------------------------------------ 取值入口 喵
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

    /** 导入按钮是否直接用游戏内文件浏览器（而不是系统原生对话框） 喵 */
    public static boolean useInGameFilePicker() {
        try {
            String value = FILE_PICKER.get();
            return value != null && "in-game".equalsIgnoreCase(value.trim());
        } catch (RuntimeException e) {
            // 配置还没加载/被改坏时按默认的 system 走 喵
            return false;
        }
    }
}
