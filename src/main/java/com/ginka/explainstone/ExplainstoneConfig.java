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
}
