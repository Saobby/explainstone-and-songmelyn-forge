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

import java.util.ArrayList;
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
     *
     * <p><b>注意：这里的候选值必须传可变集合（{@code new ArrayList<>(...) }），不能传 {@code List.of(...)}。</b>
     * Forge 47.2.x 在配置文件里**缺少这个键**（例如从旧版本升级上来、旧 toml 里还没有 filePicker）时，
     * 会走 {@code ValueSpec.test(null)} → {@code allowedValues.contains(null)}；而 {@code List.of()} 是
     * 不可变列表，{@code contains(null)} 会抛 NPE（{@code Objects.requireNonNull}）。
     * 这个异常会从 {@code ConfigTracker.loadConfigs} 一路冒到资源重载，游戏提示"资源加载失败"
     * 并**禁用整合包的全部资源包**。{@code ArrayList} 的 {@code contains(null)} 返回 false，
     * Forge 就会老老实实把缺失的键补成默认值。 喵
     */
    public static final ForgeConfigSpec.ConfigValue<String> FILE_PICKER = BUILDER
            .comment("Which file picker the \"Import ogg…\" button opens.",
                    "system  - the desktop's native dialog (zenity/qarma/kdialog on Linux, AWT elsewhere).",
                    "          If no system dialog can be created, the in-game file browser is used instead.",
                    "in-game - always use the in-game file browser (handy for testing on any platform).",
                    "Values: system, in-game. Client-side only.")
            .defineInList("filePicker", "system", new ArrayList<>(List.of("system", "in-game")));

    // ------------------------------------------------------------ 表现与资源上限 喵
    /** 音符粒子的距离上限（格）：更远的音响不冒粒子（纯渲染上限，不影响听觉） 喵 */
    public static final ForgeConfigSpec.DoubleValue NOTE_PARTICLE_DISTANCE = BUILDER
            .comment("Maximum distance (in blocks) at which a playing speaker still emits note particles.",
                    "Render/perf cap only; it never affects what you hear. 0 disables the particles.")
            .defineInRange("noteParticleDistance", 48.0, 0.0, 256.0);

    /** 每 tick 全客户端最多生成多少颗音符（防止密集音响群把屏幕糊满/掉帧） 喵 */
    public static final ForgeConfigSpec.IntValue NOTES_PER_TICK_BUDGET = BUILDER
            .comment("Hard cap on note particles spawned per tick for this client.",
                    "Speakers that do not fit in the budget simply skip their particles for that tick.",
                    "Each speaker emits 0.5 notes/tick at 100% volume, so the default 128 covers",
                    "256 speakers at full volume. Lower it if a dense cluster costs you frames.")
            .defineInRange("notesPerTickBudget", 128, 0, 2000);

    /** 本机同时最多保留多少个 OpenAL 音源 喵 */
    public static final ForgeConfigSpec.IntValue MAX_VOICES = BUILDER
            .comment("Maximum number of OpenAL sources this client keeps alive at once.",
                    "Each playing/queued speaker occupies one source; raise it for very dense setups.",
                    "It must be at least the total number of receiving speakers playing at the same",
                    "time (e.g. maxSpeakersPerChannel x number of simultaneously active channels);",
                    "otherwise the extra speakers are skipped and the client logs a warning.")
            .defineInRange("maxVoices", 256, 4, 4096);

    /** 音源与时间轴偏差超过多少秒才强制重新对齐（兜底，正常播放不会触发） 喵 */
    public static final ForgeConfigSpec.DoubleValue DRIFT_RESYNC_SECONDS = BUILDER
            .comment("Force a re-align when a voice drifts more than this many seconds from the timeline.",
                    "Fallback for audio-device hiccups only; normal playback never comes close to it.")
            .defineInRange("driftResyncSeconds", 2.0, 0.1, 120.0);

    /** 服务端推来的进度和本地推算差多少毫秒以上就当成\"跳变\"（seek / 切歌） 喵 */
    public static final ForgeConfigSpec.IntValue TIMELINE_JUMP_MILLIS = BUILDER
            .comment("Treat an incoming position that differs from the local timeline by more than this",
                    "many milliseconds as a seek (whole group jumps together).")
            .defineInRange("timelineJumpMillis", 2000, 50, 60000);

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

    // ------------------------------------------------------------ 表现与资源上限取值入口 喵
    /**
     * 读配置并兜底：这些值在渲染/音频线程上每 tick 读，配置没加载好时不能让游戏崩。 喵
     */
    private static <T> T orDefault(ForgeConfigSpec.ConfigValue<T> value, T fallback) {
        try {
            T current = value.get();
            return current == null ? fallback : current;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** 音符粒子距离上限（格）；0 = 不冒粒子 喵 */
    public static double noteParticleDistance() {
        return orDefault(NOTE_PARTICLE_DISTANCE, 48.0);
    }

    /** 每 tick 全客户端的音符数上限 喵 */
    public static int notesPerTickBudget() {
        return orDefault(NOTES_PER_TICK_BUDGET, 128);
    }

    /** 本机同时保留的 OpenAL 音源上限 喵 */
    public static int maxVoices() {
        return orDefault(MAX_VOICES, 256);
    }

    /** 偏差超过多少秒就强制重新对齐 喵 */
    public static double driftResyncSeconds() {
        return orDefault(DRIFT_RESYNC_SECONDS, 2.0);
    }

    /** 进度差多少毫秒算"跳变" 喵 */
    public static long timelineJumpMillis() {
        return orDefault(TIMELINE_JUMP_MILLIS, 2000);
    }
}
