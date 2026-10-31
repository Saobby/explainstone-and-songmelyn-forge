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

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 播放会话持久化：把「随机顺序 + 已播放 tick」写进存档，
 * 退出重进后能接着之前的顺序和进度播放，而不是从头重新随机。 喵
 *
 * <p>位置：{@code <存档>/explainstone/playback.nbt}。
 * 只保存当前正在播放的讲解石/发信器会话；停止/被拆掉的方块会丢弃对应记录。
 */
public final class PlaybackPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-playback-save");
    private static final int FORMAT_VERSION = 1;
    /** 一次最多恢复多少个会话（防止异常存档把内存撑爆） 喵 */
    private static final int MAX_SESSIONS = 4096;
    /** 单个会话的曲目 id 数量上限 喵 */
    private static final int MAX_TRACK_IDS = 10_000;
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final Map<Key, Saved> SAVED = new HashMap<>();
    private static Path storagePath;
    private static MinecraftServer server;

    private PlaybackPersistence() {
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /**
     * 一条可恢复的播放会话：顺序用曲目 id 保存（label 从当前方块列表里重新取），
     * elapsedTicks 是保存时已经播放的 tick 数（离线期间视为暂停）。 喵
     */
    public record Saved(ResourceKey<Level> dimension, BlockPos pos, boolean broadcast, int channel,
                        int mode, int selected, List<String> sourceIds, List<String> orderIds,
                        long elapsedTicks) {

        /** 当前方块的播放列表、模式、选中项是否和保存时一致 喵 */
        public boolean matches(List<Track> source, PlayMode mode, int selected) {
            return this.mode == mode.ordinal() && this.selected == selected
                    && this.sourceIds.equals(trackIds(source));
        }

        /** 把保存的顺序 id 映射回当前列表里的曲目；对不上就返回 null（调用方重新生成顺序） 喵 */
        public List<Track> resolveOrder(List<Track> source) {
            Map<String, Track> byId = new HashMap<>();
            for (Track track : source) {
                byId.put(track.id(), track);
            }
            List<Track> order = new ArrayList<>(this.orderIds.size());
            for (String id : this.orderIds) {
                Track track = byId.get(id);
                if (track == null) {
                    return null;
                }
                order.add(track);
            }
            return order.isEmpty() ? null : List.copyOf(order);
        }
    }

    // ------------------------------------------------------------ 读写 喵
    public static synchronized void load(MinecraftServer srv) {
        SAVED.clear();
        server = srv;
        storagePath = srv == null ? null
                : srv.getWorldPath(LevelResource.ROOT).resolve("explainstone").resolve("playback.nbt");
        if (storagePath == null || !Files.isRegularFile(storagePath)) {
            return;
        }
        try {
            CompoundTag root;
            try (InputStream input = Files.newInputStream(storagePath);
                 DataInputStream data = new DataInputStream(new GZIPInputStream(input))) {
                root = NbtIo.read(data, new NbtAccounter(MAX_FILE_BYTES));
            }
            if (root == null) {
                return;
            }
            ListTag list = root.getList("sessions", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && SAVED.size() < MAX_SESSIONS; i++) {
                Saved saved = readSession(list.getCompound(i));
                if (saved != null) {
                    SAVED.put(new Key(saved.dimension(), saved.pos()), saved);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("读取播放进度失败，将从头开始: {}", e.toString());
            SAVED.clear();
        }
    }

    private static Saved readSession(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dimensionId == null) {
            return null;
        }
        int mode = tag.getInt("mode");
        int selected = tag.getInt("selected");
        long elapsed = tag.getLong("elapsed");
        if (mode < 0 || mode >= PlayMode.values().length || selected < 0 || elapsed < 0) {
            return null;
        }
        List<String> source = readIds(tag.getList("source", Tag.TAG_STRING));
        List<String> order = readIds(tag.getList("order", Tag.TAG_STRING));
        if (source == null || order == null || order.isEmpty()) {
            return null;
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        return new Saved(dimension, pos.immutable(), tag.getBoolean("broadcast"), tag.getInt("channel"),
                mode, selected, source, order, elapsed);
    }

    private static List<String> readIds(ListTag list) {
        if (list.size() > MAX_TRACK_IDS) {
            return null;
        }
        List<String> ids = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            if (!isValidTrackId(id)) {
                return null;
            }
            ids.add(id);
        }
        return ids;
    }

    /** 只接受 {@code file:<sha1>} 或 {@code builtin:<简单名字>}，避免异常存档注入奇怪 id 喵 */
    private static boolean isValidTrackId(String id) {
        if (id == null) {
            return false;
        }
        if (id.startsWith(Track.FILE_PREFIX)) {
            return SpeakerNetworking.isValidSha1(id.substring(Track.FILE_PREFIX.length()));
        }
        if (id.startsWith(Track.BUILTIN_PREFIX)) {
            return id.substring(Track.BUILTIN_PREFIX.length()).matches("[a-z0-9_\\-]+");
        }
        return false;
    }

    /** 取出某方块保存的会话（只恢复一次；不匹配时调用方直接丢掉） 喵 */
    static synchronized Saved claim(ResourceKey<Level> dimension, BlockPos pos) {
        return SAVED.remove(new Key(dimension, pos.immutable()));
    }

    /** 方块停止/被拆/列表变了：不要再用旧进度恢复 喵 */
    static synchronized void discard(ResourceKey<Level> dimension, BlockPos pos) {
        SAVED.remove(new Key(dimension, pos.immutable()));
    }

    /** 定期保存（由服务端 tick 调用） 喵 */
    public static void save() {
        MinecraftServer srv = server;
        if (srv != null) {
            save(srv);
        }
    }

    public static synchronized void save(MinecraftServer srv) {
        if (srv == null || storagePath == null) {
            return;
        }
        Map<Key, Saved> merged = new HashMap<>(SAVED);
        try {
            for (Saved saved : PlaybackRegistry.snapshots(srv)) {
                merged.put(new Key(saved.dimension(), saved.pos()), saved);
            }
            for (Saved saved : BroadcastRegistry.snapshots(srv)) {
                merged.put(new Key(saved.dimension(), saved.pos()), saved);
            }
        } catch (Exception e) {
            LOGGER.warn("收集播放会话失败: {}", e.toString());
        }
        if (merged.isEmpty()) {
            // 没有任何要保存的会话：删掉旧文件，避免每 5 秒写一个空文件 喵
            try {
                Files.deleteIfExists(storagePath);
            } catch (IOException ignored) {
            }
            return;
        }
        write(merged.values());
    }

    private static void write(Collection<Saved> sessions) {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ListTag list = new ListTag();
            for (Saved saved : sessions) {
                CompoundTag tag = new CompoundTag();
                tag.putString("dimension", saved.dimension().location().toString());
                tag.putInt("x", saved.pos().getX());
                tag.putInt("y", saved.pos().getY());
                tag.putInt("z", saved.pos().getZ());
                tag.putBoolean("broadcast", saved.broadcast());
                tag.putInt("channel", saved.channel());
                tag.putInt("mode", saved.mode());
                tag.putInt("selected", saved.selected());
                tag.put("source", writeIds(saved.sourceIds()));
                tag.put("order", writeIds(saved.orderIds()));
                tag.putLong("elapsed", saved.elapsedTicks());
                list.add(tag);
            }
            CompoundTag root = new CompoundTag();
            root.putInt("version", FORMAT_VERSION);
            root.put("sessions", list);

            Path temp = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp.toFile());
            try {
                Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, storagePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("保存播放进度失败: {}", e.toString());
        }
    }

    private static ListTag writeIds(List<String> ids) {
        ListTag list = new ListTag();
        for (String id : ids) {
            list.add(StringTag.valueOf(id));
        }
        return list;
    }

    /** 服务端停止时调用（先 save 再 clear） 喵 */
    public static synchronized void clear() {
        SAVED.clear();
        server = null;
        storagePath = null;
    }

    /** 曲目列表 → id 列表（保存快照和比较时用） 喵 */
    static List<String> trackIds(List<Track> tracks) {
        List<String> ids = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            ids.add(track.id());
        }
        return ids;
    }

}
