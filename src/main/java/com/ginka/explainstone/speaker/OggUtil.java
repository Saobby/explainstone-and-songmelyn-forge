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
package com.ginka.explainstone.speaker;

/**
 * Ogg Vorbis 头/尾解析：格式校验 + 时长计算。
 *
 * <p>客户端导入前要给出时长提示（播放列表时间轴只依赖元数据），
 * 服务端校验时也要算真实时长，两边必须用同一套算法，所以抽到这里。 喵
 */
public final class OggUtil {
    /** 头/尾各读这么多字节就够定位识别头和最后一个 page 喵 */
    public static final int EDGE_BYTES = 64 * 1024;

    private OggUtil() {
    }

    /** 是不是一个 Ogg 流（只看开头 4 字节） 喵 */
    public static boolean isOgg(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g'
                && bytes[2] == 'g' && bytes[3] == 'S';
    }

    /** 从数据里找第一个 Ogg page 的 Vorbis 识别头，返回采样率；找不到返回 -1 喵 */
    public static int sampleRate(byte[] data) {
        return data == null ? -1 : sampleRate(data, data.length);
    }

    /** 只扫描前 length 个字节（流式导入时用，避免为了算时长把整文件留在内存里） 喵 */
    public static int sampleRate(byte[] data, int length) {
        if (data == null) {
            return -1;
        }
        int limit = Math.min(length, data.length);
        for (int i = 0; i + 4 <= limit; i++) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                if (i + 27 > limit) {
                    return -1;
                }
                int segments = data[i + 26] & 0xFF;
                int pkt = i + 27 + segments;
                if (pkt + 16 <= limit && data[pkt] == 1) {
                    return (data[pkt + 12] & 0xFF) | ((data[pkt + 13] & 0xFF) << 8)
                            | ((data[pkt + 14] & 0xFF) << 16) | ((data[pkt + 15] & 0xFF) << 24);
                }
                return -1;
            }
        }
        return -1;
    }

    /** 从数据里找最后一个 Ogg page 的 granule position；找不到返回 -1 喵 */
    public static long lastGranule(byte[] data) {
        return data == null ? -1L : lastGranule(data, data.length);
    }

    /** 只扫描前 length 个字节（尾部缓冲一般就是文件最后 64 KiB） 喵 */
    public static long lastGranule(byte[] data, int length) {
        if (data == null) {
            return -1L;
        }
        int limit = Math.min(length, data.length);
        for (int i = limit - 4; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S'
                    && i + 14 <= limit) {
                long granule = 0L;
                for (int b = 7; b >= 0; b--) {
                    granule = (granule << 8) | (data[i + 6 + b] & 0xFF);
                }
                return granule;
            }
        }
        return -1L;
    }

    /**
     * 时长（秒）：采样率和最后一个 granule 都在传入的数据里时才有结果。
     * 整文件解析时直接传全部字节；流式扫描时传"头 64 KiB + 尾 64 KiB"也行。 喵
     */
    public static int durationSeconds(byte[] data) {
        return durationSeconds(sampleRate(data), lastGranule(data));
    }

    /** 时长（秒），任一项无效返回 -1 喵 */
    public static int durationSeconds(int sampleRate, long lastGranule) {
        if (sampleRate <= 0 || lastGranule <= 0L) {
            return -1;
        }
        long seconds = lastGranule / sampleRate;
        return seconds <= 0L ? -1 : (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
