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
package com.ginka.explainstone.client.audio;

/**
 * 一个音响所在房间的声学参数（第一版近似）。
 * 体积单位 m³，面积单位 m²，RT60 单位秒。 喵
 */
public record RoomAcoustics(
        double volume,
        double surfaceArea,
        double absorptionLow,
        double absorptionHigh,
        double rt60Low,
        double rt60High,
        double rt60Mid
) {
}
