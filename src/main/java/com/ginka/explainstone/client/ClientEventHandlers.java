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

import com.ginka.explainstone.Explainstone;
import com.ginka.explainstone.client.audio.AudioSources;
import com.ginka.explainstone.client.audio.SpeakerAudio;
import com.ginka.explainstone.client.audio.SpeakerUploader;
import com.ginka.explainstone.speaker.TimelineClock;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Explainstone.MOD_ID, value = Dist.CLIENT)
public final class ClientEventHandlers {
    private ClientEventHandlers() {
    }

    /** 每 tick 更新音源（距离衰减、切换曲目、按模式续播） 喵 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft client = Minecraft.getInstance();
            // 单人暂停（Esc）期间集成服务器不 tick，时间轴要跟着冻结：
            // 这里每 tick 把"是不是暂停中"告诉时间轴墙钟（专用服务器没有暂停，这里永远是 false） 喵
            TimelineClock.onGamePaused(client.isPaused());
            SpeakerUploader.tick(client);
            SpeakerAudio.tick(client);
        }
    }

    /** 退出世界时停掉所有音源，避免残留声音 喵 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // OpenAL / 音源表都在客户端主线程上操作，这里统一切回主线程 喵
        Minecraft.getInstance().execute(() -> {
            SpeakerUploader.reset();
            SpeakerAudio.reset();
            // 服务端可用性缓存只在当前世界有效，换存档不能串味 喵
            AudioSources.clearServerAvailability();
        });
    }
}
