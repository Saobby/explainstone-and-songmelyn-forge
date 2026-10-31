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

import com.ginka.explainstone.speaker.SpeakerPayloads;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/** 客户端音频缓存：接收服务端下发的 ogg 并在下载完成后写入本地缓存 喵 */
public final class SpeakerAudioCache {
    /** 单个音频最多允许多少块（约 128 MiB），防止恶意服务端让客户端分配超大数组 喵 */
    private static final int MAX_CHUNKS = 16_384;
    /** 同时缓存中的下载数上限 喵 */
    private static final int MAX_PENDING = 64;
    private static final Map<String, Pending> PENDING = new HashMap<>();

    private SpeakerAudioCache() {
    }

    public static void onChunk(SpeakerPayloads.AudioChunk payload) {
        if (payload.total() <= 0 || payload.total() > MAX_CHUNKS
                || payload.index() < 0 || payload.index() >= payload.total()) {
            return;
        }
        if (!PENDING.containsKey(payload.sha1()) && PENDING.size() >= MAX_PENDING) {
            return;
        }
        Pending pending = PENDING.computeIfAbsent(payload.sha1(), k -> new Pending(payload.total()));
        pending.put(payload.index(), payload.data());
        if (pending.isComplete()) {
            PENDING.remove(payload.sha1());
            AudioSources.saveLocal(payload.sha1(), pending.bytes());
            // 下载完成就提前后台解码，真正起播时不用再等 喵
            SpeakerAudio.prefetchDownloaded(payload.sha1());
            SpeakerAudio.onAudioReceived(payload.sha1());
        }
    }

    private static final class Pending {
        private final byte[][] chunks;
        private int received;

        private Pending(int total) {
            this.chunks = new byte[Math.max(1, total)][];
        }

        private void put(int index, byte[] data) {
            if (index >= 0 && index < this.chunks.length && this.chunks[index] == null) {
                this.chunks[index] = data;
                this.received++;
            }
        }

        private boolean isComplete() {
            return this.received >= this.chunks.length;
        }

        private byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : this.chunks) {
                if (chunk != null) {
                    out.writeBytes(chunk);
                }
            }
            return out.toByteArray();
        }
    }
}
