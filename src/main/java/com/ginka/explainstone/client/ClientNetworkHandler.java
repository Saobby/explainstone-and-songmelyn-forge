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
package com.ginka.explainstone.client;

import com.ginka.explainstone.client.audio.SpeakerAudio;
import com.ginka.explainstone.client.audio.SpeakerAudioCache;
import com.ginka.explainstone.speaker.SpeakerPayloads;

/** 客户端网络包处理（由通用包处理器通过 DistExecutor 调用） 喵 */
public final class ClientNetworkHandler {
    private ClientNetworkHandler() {
    }

    public static void handleBroadcast(SpeakerPayloads.Broadcast payload) {
        SpeakerAudio.onBroadcast(payload);
    }

    public static void handleAudioChunk(SpeakerPayloads.AudioChunk payload) {
        SpeakerAudioCache.onChunk(payload);
    }
}
