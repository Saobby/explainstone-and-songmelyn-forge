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
package com.ginka.explainstone.sound;

/**
 * 方块声学材质参数（游戏调试用经验值）。
 *
 * transmission*：声能穿过 1 格该材料后保留的比例（0~1）。
 * absorption*：材料吸声系数（后续混响阶段使用）。
 * 不完整方块在射线检测中会先对系数取平方根。 喵
 */
public enum SoundMaterial {
    AIR(1.00, 1.00, 0.00, 0.00),
    LEAVES(0.90, 0.70, 0.15, 0.40),
    WOOL(0.85, 0.65, 0.25, 0.65),
    CARPET(0.85, 0.65, 0.15, 0.55),
    WOOD(0.70, 0.25, 0.08, 0.15),
    LOG(0.60, 0.15, 0.06, 0.12),
    GLASS(0.55, 0.12, 0.02, 0.03),
    SAND(0.50, 0.15, 0.05, 0.10),
    DIRT(0.45, 0.10, 0.04, 0.08),
    GRAVEL(0.45, 0.10, 0.04, 0.08),
    GRASS(0.45, 0.10, 0.08, 0.15),
    SNOW(0.45, 0.10, 0.12, 0.30),
    BRICK(0.40, 0.06, 0.03, 0.06),
    STONE(0.35, 0.04, 0.02, 0.03),
    CONCRETE(0.30, 0.025, 0.02, 0.04),
    METAL(0.25, 0.015, 0.01, 0.02),
    SPONGE(0.85, 0.65, 0.45, 0.85);

    private final double transmissionLow;
    private final double transmissionHigh;
    private final double absorptionLow;
    private final double absorptionHigh;

    SoundMaterial(double transmissionLow, double transmissionHigh,
                  double absorptionLow, double absorptionHigh) {
        this.transmissionLow = transmissionLow;
        this.transmissionHigh = transmissionHigh;
        this.absorptionLow = absorptionLow;
        this.absorptionHigh = absorptionHigh;
    }

    public double transmissionLow() {
        return this.transmissionLow;
    }

    public double transmissionHigh() {
        return this.transmissionHigh;
    }

    public double absorptionLow() {
        return this.absorptionLow;
    }

    public double absorptionHigh() {
        return this.absorptionHigh;
    }
}
