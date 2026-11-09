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
import com.ginka.explainstone.SpeakerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端"收信音响登记表"：频道 → 音响坐标（含音量与朝向）。
 *
 * <p>为什么要登记：客户端只能扫描自己已加载的区块，而发信范围最大 512 格，扫不全。
 * 为了做到"同频道的音响在客户端**同时创建、同时起播**"，必须由服务端把完整列表下发。
 *
 * <p>生命周期（最容易出错的地方）：
 * <ul>
 *   <li>每个 tick 由方块 ticker 调 {@link #onBlockEntityTick}（幂等）：
 *       收信模式 → 登记/更新；不是收信模式 → 注销；</li>
 *   <li>方块被拆/被替换 → {@link #unregister}（{@code SpeakerBlock#onRemove}）；</li>
 *   <li>区块卸载**不注销**（音响还在世界里），登记表会持久化到
 *       {@code <存档>/explainstone/speakers.nbt}；</li>
 *   <li>下发前顺手"自愈"：坐标所在区块是加载着的、但那里已经不是收信音响
 *       （被 /setblock、被其它模组改掉）→ 直接清掉这条登记。 喵</li>
 * </ul>
 */
public final class SpeakerRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-speakers");
    private static final int FORMAT_VERSION = 1;
    /** 防御异常存档：最多读入多少条登记 喵 */
    private static final int MAX_ENTRIES = 65_536;

    /** 维度 → (坐标 → 登记信息) 喵 */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Entry>> SPEAKERS = new HashMap<>();
    /** 维度 → (频道 → 变更代号)：客户端靠它判断列表有没有变 喵 */
    private static final Map<ResourceKey<Level>, Map<Integer, Long>> REVISIONS = new HashMap<>();
    private static Path storagePath;
    private static boolean dirty;

    private SpeakerRegistry() {
    }

    /** 一条登记：频道 + 音响自己的音量 + 朝向（ordinal，客户端用来算指向性衰减） 喵 */
    public record Entry(int channel, int volume, int facing) {
    }

    /** 一次下发给客户端的快照：坐标 + 音量 + 朝向，顺序一一对应 喵 */
    public record Snapshot(long revision, long[] positions, int[] volumes, int[] facings) {
        public int size() {
            return this.positions == null ? 0 : this.positions.length;
        }
    }

    // ------------------------------------------------------------ 读写存档 喵
    /** 服务端启动（世界加载前）读登记表 喵 */
    public static synchronized void load(MinecraftServer server) {
        SPEAKERS.clear();
        REVISIONS.clear();
        storagePath = null;
        dirty = false;
        if (server == null) {
            return;
        }
        storagePath = server.getWorldPath(LevelResource.ROOT)
                .resolve("explainstone").resolve("speakers.nbt");
        if (!Files.isRegularFile(storagePath)) {
            return;
        }
        try {
            CompoundTag root;
            try (InputStream input = Files.newInputStream(storagePath);
                 DataInputStream data = new DataInputStream(new java.util.zip.GZIPInputStream(input))) {
                root = NbtIo.read(data, new NbtAccounter(8L * 1024 * 1024));
            }
            if (root == null) {
                return;
            }
            ListTag list = root.getList("speakers", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < MAX_ENTRIES; i++) {
                CompoundTag tag = list.getCompound(i);
                ResourceLocation id = ResourceLocation.tryParse(tag.getString("dim"));
                if (id == null) {
                    continue;
                }
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
                BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
                int channel = Math.floorMod(tag.getInt("channel"), 1000);
                int volume = Math.max(0, Math.min(100, tag.getInt("volume")));
                int facing = Math.floorMod(tag.getInt("facing"), 6);
                SPEAKERS.computeIfAbsent(dimension, key -> new HashMap<>())
                        .put(pos.immutable(), new Entry(channel, volume, facing));
                bumpRevision(dimension, channel);
            }
        } catch (Exception e) {
            LOGGER.warn("读取收信音响登记表失败，将从空表开始: {}", e.toString());
            SPEAKERS.clear();
            REVISIONS.clear();
        }
    }

    /** 定期/停止时保存（脏了才写） 喵 */
    public static synchronized void save() {
        if (!dirty || storagePath == null) {
            return;
        }
        dirty = false;
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ListTag list = new ListTag();
            int written = 0;
            for (Map.Entry<ResourceKey<Level>, Map<BlockPos, Entry>> dimension : SPEAKERS.entrySet()) {
                for (Map.Entry<BlockPos, Entry> speaker : dimension.getValue().entrySet()) {
                    if (written++ >= MAX_ENTRIES) {
                        break;
                    }
                    CompoundTag tag = new CompoundTag();
                    tag.putString("dim", dimension.getKey().location().toString());
                    tag.putInt("x", speaker.getKey().getX());
                    tag.putInt("y", speaker.getKey().getY());
                    tag.putInt("z", speaker.getKey().getZ());
                    tag.putInt("channel", speaker.getValue().channel());
                    tag.putInt("volume", speaker.getValue().volume());
                    tag.putInt("facing", speaker.getValue().facing());
                    list.add(tag);
                }
            }
            CompoundTag root = new CompoundTag();
            root.putInt("version", FORMAT_VERSION);
            root.put("speakers", list);
            Path temp = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp.toFile());
            try {
                Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("保存收信音响登记表失败: {}", e.toString());
            dirty = true;
        }
    }

    /** 服务端停止时调用（先 save 再 clear） 喵 */
    public static synchronized void clear() {
        SPEAKERS.clear();
        REVISIONS.clear();
        storagePath = null;
        dirty = false;
    }

    // ------------------------------------------------------------ 登记生命周期 喵
    /**
     * 方块 ticker 每 tick 调一次（幂等）：收信模式 → 登记/更新；否则注销。
     *
     * <p>区块卸载时登记表**不删**（音响还在世界里），所以区块重新加载后这里会自动补登记；
     * 频道被塞满时只提示一次日志，不会每 tick 刷屏。 喵
     */
    public static void onBlockEntityTick(ServerLevel level, SpeakerBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        if (!be.isReceiveMode()) {
            if (unregister(level, pos)) {
                be.setSpeakerRegistered(false);
            }
            return;
        }
        int facing = be.getBlockState().getValue(SpeakerBlock.FACING).ordinal();
        boolean ok = register(level, pos, be.getChannel(), be.getVolume(), facing);
        if (ok) {
            be.setSpeakerRegistered(true);
            return;
        }
        if (!be.isSpeakerRegisterRejected()) {
            be.setSpeakerRegisterRejected(true);
            LOGGER.warn("频道 {} 的收信音响已达上限 {}，{} 不会参与同步播放",
                    be.getChannel(), ExplainstoneServerConfig.maxSpeakersPerChannel(),
                    pos.toShortString());
        }
    }

    /** 登记/更新一条收信音响；频道满了（且不是更新自己）返回 false 喵 */
    public static synchronized boolean register(ServerLevel level, BlockPos pos, int channel, int volume,
                                                int facing) {
        if (level == null || pos == null) {
            return false;
        }
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Entry> map = SPEAKERS.computeIfAbsent(dimension, key -> new HashMap<>());
        Entry old = map.get(pos);
        if (old != null && old.channel() == channel && old.volume() == volume && old.facing() == facing) {
            return true;   // 没变化，不打断客户端 喵
        }
        if (old == null || old.channel() != channel) {
            if (countLocked(dimension, channel) >= ExplainstoneServerConfig.maxSpeakersPerChannel()) {
                return false;
            }
            if (old != null) {
                bumpRevision(dimension, old.channel());
            }
        }
        map.put(pos.immutable(), new Entry(channel, volume, facing));
        bumpRevision(dimension, channel);
        dirty = true;
        return true;
    }

    /** 注销一条收信音响；返回是否真的删掉了 喵 */
    public static synchronized boolean unregister(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        Map<BlockPos, Entry> map = SPEAKERS.get(level.dimension());
        if (map == null) {
            return false;
        }
        Entry removed = map.remove(pos);
        if (removed == null) {
            return false;
        }
        bumpRevision(level.dimension(), removed.channel());
        dirty = true;
        return true;
    }

    /** 某个频道当前有多少收信音响 喵 */
    public static synchronized int count(ServerLevel level, int channel) {
        return level == null ? 0 : countLocked(level.dimension(), channel);
    }

    /** 频道列表的变更代号：变了就说明需要重推给客户端 喵 */
    public static synchronized long revision(ServerLevel level, int channel) {
        if (level == null) {
            return 0L;
        }
        Map<Integer, Long> revisions = REVISIONS.get(level.dimension());
        Long value = revisions == null ? null : revisions.get(channel);
        return value == null ? 0L : value;
    }

    /**
     * 取某个频道所有收信音响的快照（下发用）。顺手做一次自愈：
     * 区块是加载着的、但那里已经不是收信音响 → 清掉这条登记。 喵
     */
    public static synchronized Snapshot snapshot(ServerLevel level, int channel) {
        if (level == null) {
            return new Snapshot(0L, new long[0], new int[0], new int[0]);
        }
        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, Entry> map = SPEAKERS.get(dimension);
        if (map == null || map.isEmpty()) {
            return new Snapshot(revision(level, channel), new long[0], new int[0], new int[0]);
        }
        List<BlockPos> stale = null;
        List<BlockPos> positions = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<BlockPos, Entry> speaker : map.entrySet()) {
            if (speaker.getValue().channel() != channel) {
                continue;
            }
            BlockPos pos = speaker.getKey();
            if (level.isLoaded(pos)) {
                boolean stillThere = level.getBlockEntity(pos) instanceof SpeakerBlockEntity be
                        && be.isReceiveMode() && be.getChannel() == channel;
                if (!stillThere) {
                    if (stale == null) {
                        stale = new ArrayList<>();
                    }
                    stale.add(pos);
                    continue;
                }
            }
            positions.add(pos);
            entries.add(speaker.getValue());
        }
        if (stale != null) {
            for (BlockPos pos : stale) {
                Entry removed = map.remove(pos);
                if (removed != null) {
                    bumpRevision(dimension, removed.channel());
                    dirty = true;
                }
            }
        }
        int size = Math.min(positions.size(), ExplainstoneServerConfig.maxSpeakersPerChannel());
        long[] packed = new long[size];
        int[] volumes = new int[size];
        int[] facings = new int[size];
        for (int i = 0; i < size; i++) {
            packed[i] = positions.get(i).asLong();
            volumes[i] = entries.get(i).volume();
            facings[i] = entries.get(i).facing();
        }
        return new Snapshot(revision(level, channel), packed, volumes, facings);
    }

    // ------------------------------------------------------------ 内部 喵
    private static int countLocked(ResourceKey<Level> dimension, int channel) {
        Map<BlockPos, Entry> map = SPEAKERS.get(dimension);
        if (map == null) {
            return 0;
        }
        int count = 0;
        for (Entry entry : map.values()) {
            if (entry.channel() == channel) {
                count++;
            }
        }
        return count;
    }

    private static void bumpRevision(ResourceKey<Level> dimension, int channel) {
        Map<Integer, Long> revisions = REVISIONS.computeIfAbsent(dimension, key -> new HashMap<>());
        revisions.merge(channel, 1L, Long::sum);
    }

    /** 朝向 ordinal → Direction（客户端解码用；越界回退 NORTH） 喵 */
    public static Direction facingOf(int ordinal) {
        Direction[] values = Direction.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Direction.NORTH;
    }
}
