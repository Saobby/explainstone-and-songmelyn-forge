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

import com.ginka.explainstone.ExplainstoneServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * "元数据已进播放列表、字节还没到" 的占位登记。
 *
 * <p>播放列表允许先有条目（这样导入时时间轴就不用重建），代价是任何人都能塞一堆永远不会上传的条目。
 * 所以这里给待上传条目上两道闸：
 * <ul>
 *   <li>每个玩家同时最多占 {@code pendingImportsPerPlayer} 条；</li>
 *   <li>{@code pendingImportTimeoutSeconds} 秒内字节没到，就把那条从播放列表里摘掉（并同步给客户端）。 喵</li>
 * </ul>
 *
 * <p>校验失败另有冷却（{@code importFailureCooldownSeconds}），防止恶意客户端反复塞假条目刷屏/刷重建。 喵
 */
public final class PendingImports {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-pending");
    private static final Map<Key, Entry> PENDING = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();

    private PendingImports() {
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos, String sha1) {
    }

    private static final class Entry {
        private final UUID owner;
        private final long expiresAt;

        private Entry(UUID owner, long expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }
    }

    /** 某玩家当前占着多少个待上传条目 喵 */
    public static int count(UUID owner) {
        int count = 0;
        for (Entry entry : PENDING.values()) {
            if (entry.owner.equals(owner)) {
                count++;
            }
        }
        return count;
    }

    /** 该玩家是否因为连续校验失败还在冷却里 喵 */
    public static boolean blocked(UUID owner) {
        Long until = COOLDOWN_UNTIL.get(owner);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            COOLDOWN_UNTIL.remove(owner);
            return false;
        }
        return true;
    }

    /** 校验失败后让这个玩家冷静一会儿，避免反复塞假条目 喵 */
    public static void enterCooldown(UUID owner) {
        int seconds = ExplainstoneServerConfig.importFailureCooldownSeconds();
        if (owner != null && seconds > 0) {
            COOLDOWN_UNTIL.put(owner, System.currentTimeMillis() + seconds * 1000L);
        }
    }

    /** 登记一条待上传；返回 false 表示该玩家配额已满 喵 */
    public static boolean claim(ServerPlayer player, BlockPos pos, String sha1) {
        if (player == null || pos == null || sha1 == null) {
            return false;
        }
        Key key = new Key(player.level().dimension(), pos.immutable(), sha1);
        Entry existing = PENDING.get(key);
        if (existing != null) {
            return true;   // 已经登记过，不重复占配额 喵
        }
        if (count(player.getUUID()) >= Math.max(1, ExplainstoneServerConfig.pendingImportsPerPlayer())) {
            return false;
        }
        long expiresAt = System.currentTimeMillis()
                + Math.max(5, ExplainstoneServerConfig.pendingImportTimeoutSeconds()) * 1000L;
        PENDING.put(key, new Entry(player.getUUID(), expiresAt));
        return true;
    }

    /** 字节到了（或已经存在）：释放登记 喵 */
    public static void release(ResourceKey<Level> dimension, BlockPos pos, String sha1) {
        if (dimension == null || pos == null || sha1 == null) {
            return;
        }
        PENDING.remove(new Key(dimension, pos.immutable(), sha1));
    }

    /** 某个方块上的条目全部清掉（清空列表 / 拆方块 / 校验失败被丢弃） 喵 */
    public static void releasePos(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        BlockPos key = pos.immutable();
        PENDING.keySet().removeIf(entry -> entry.dimension().equals(dimension) && entry.pos().equals(key));
    }

    public static void clear() {
        PENDING.clear();
        COOLDOWN_UNTIL.clear();
    }

    /**
     * 服务端每 5 秒调一次：超时还没收到字节的条目直接从播放列表里摘掉，
     * 免得一条假条目永远挂在别人的列表里（灰色不可播）。 喵
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Key, Entry>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> item = it.next();
            Key key = item.getKey();
            if (now < item.getValue().expiresAt) {
                continue;
            }
            it.remove();
            if (AudioStore.available(key.sha1())) {
                continue;   // 字节其实已经到了，只是没来得及 release 喵
            }
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.isLoaded(key.pos())) {
                continue;
            }
            if (level.getBlockEntity(key.pos()) instanceof SpeakerBlockEntity be
                    && be.removeTrackBySha(key.sha1())) {
                LOGGER.info("待上传超时，已从播放列表移除 pos={} sha1={}", key.pos().toShortString(), key.sha1());
            }
        }
    }
}
