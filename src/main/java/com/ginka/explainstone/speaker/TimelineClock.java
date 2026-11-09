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
 * 服务端播放时间轴用的"墙钟"（毫秒）。
 *
 * <p>为什么不用 {@code level.getGameTime()}：游戏 tick 只在服务器真的跑了 tick 时才前进，
 * 服务器掉帧时（比如 15 TPS）"已播放时长"会只走 0.75 倍真实时间，而客户端和声卡都是按
 * 真实时间走的——两侧基不同，就会不断被判定成"时间轴跳变"并互相纠正。
 *
 * <p>实现就是"记录起点、用现在减起点"，外加一份**累计暂停时长**：
 * <pre>
 *   nowMillis = (System.nanoTime() - origin - pausedNanos) / 1e6
 * </pre>
 * 暂停时把这段时间记进 {@code pausedNanos}（等价于"把起点往后挪"），
 * 所以暂停多久都不会让时间轴往前跳。
 *
 * <p>暂停信号只能从客户端拿（服务端暂停时根本不 tick，见 {@code IntegratedServer.tickServer}）：
 * 单人游戏里客户端和集成服务器是同一个 JVM，客户端每 tick 读 {@code Minecraft.isPaused()}
 * 调 {@link #onGamePaused(boolean)} 即可；专用服务器永远不会暂停，这个方法不会被调用。 喵
 */
public final class TimelineClock {
    /** 时间轴原点（纳秒） 喵 */
    private static long originNanos = System.nanoTime();
    /** 累计已暂停的时长（纳秒） 喵 */
    private static long pausedNanos;
    /** 当前这次暂停从什么时候开始（0 = 不在暂停中） 喵 */
    private static long pauseStartedNanos;

    private TimelineClock() {
    }

    /** 时间轴当前时刻（毫秒，单调递增；暂停期间冻结） 喵 */
    public static synchronized long nowMillis() {
        long now = System.nanoTime();
        long paused = pausedNanos;
        if (pauseStartedNanos != 0L) {
            // 正在暂停：把"已经暂停了多久"也算进去，读出来就是冻结值 喵
            paused += Math.max(0L, now - pauseStartedNanos);
        }
        return Math.max(0L, now - originNanos - paused) / 1_000_000L;
    }

    /**
     * 游戏暂停状态变化（单人模式）：暂停期间整条时间轴冻结。
     *
     * <p>由客户端每 tick 调用一次（{@code Minecraft.isPaused()}）。
     * 专用服务器没有暂停，永远不会调到这里；玩家只是打开普通界面（比如音响 UI）也不会暂停，
     * 因为那些界面的 {@code isPauseScreen()} 返回 false。 喵
     */
    public static synchronized void onGamePaused(boolean paused) {
        long now = System.nanoTime();
        if (paused) {
            if (pauseStartedNanos == 0L) {
                pauseStartedNanos = now;
            }
        } else if (pauseStartedNanos != 0L) {
            // 恢复：把这次暂停的时长累计进去（等价于"把原点往后挪"） 喵
            pausedNanos += Math.max(0L, now - pauseStartedNanos);
            pauseStartedNanos = 0L;
        }
    }

    /** 服务器启动/换存档时归零，避免继承上一个世界的时间 喵 */
    public static synchronized void reset() {
        originNanos = System.nanoTime();
        pausedNanos = 0L;
        pauseStartedNanos = 0L;
    }
}
