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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 服务端音频引用表：键是音频 SHA-1，值是正在使用这份音频的音响/发信器（维度 + 坐标）。
 *
 * <p>每次播放列表变化（导入、删曲、清空、方块被拆、区块加载）都会把该方块的引用同步进来；
 * 当某个音频的引用全部消失时，就把存档里的 {@code <sha1>.ogg} 删掉。
 * 表本身会写进 {@code <存档>/explainstone/audio-usage.nbt}，
 * 这样即使某个音响所在的区块还没加载，也不会误删它仍在使用的音频。 喵
 */
public final class AudioUsageRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-audio-usage");
    private static final int FORMAT_VERSION = 1;

    private static final Map<String, Map<Owner, Integer>> USAGE = new HashMap<>();
    private static Path storagePath;
    private static boolean dirty;

    private AudioUsageRegistry() {
    }

    /** 一个使用音频的方块：维度 + 坐标 喵 */
    public record Owner(ResourceKey<Level> dimension, BlockPos pos) {
    }

    // ------------------------------------------------------------ 读写存档 喵
    /** 服务端启动（世界加载前）时读取引用表 喵 */
    public static synchronized void load(MinecraftServer server) {
        USAGE.clear();
        storagePath = null;
        dirty = false;
        if (server == null) {
            return;
        }
        storagePath = server.getWorldPath(LevelResource.ROOT)
                .resolve("explainstone").resolve("audio-usage.nbt");
        if (!Files.isRegularFile(storagePath)) {
            return;
        }
        try {
            CompoundTag root;
            // 限制 NBT 读取配额：损坏或恶意构造的压缩引用表不会把服务端内存撑爆 喵
            try (InputStream input = Files.newInputStream(storagePath);
                 DataInputStream data = new DataInputStream(new GZIPInputStream(input))) {
                root = NbtIo.read(data, new NbtAccounter(8L * 1024 * 1024));
            }
            if (root == null) {
                return;
            }
            CompoundTag usage = root.getCompound("usage");
            for (String sha1 : usage.getAllKeys()) {
                if (!SpeakerNetworking.isValidSha1(sha1)) {
                    continue;
                }
                // 不限制单个音频的使用者条数：真正限制内存的是上面 8 MB 的 NBT 读取配额，
                // 而运行时 update() 本来就能记录任意多个音响/发信器 喵
                ListTag list = usage.getList(sha1, Tag.TAG_COMPOUND);
                Map<Owner, Integer> owners = new HashMap<>();
                for (int i = 0; i < list.size(); i++) {
                    CompoundTag entry = list.getCompound(i);
                    ResourceLocation id = ResourceLocation.tryParse(entry.getString("dimension"));
                    if (id == null) {
                        continue;
                    }
                    ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, id);
                    BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
                    int count = Math.max(1, entry.getInt("count"));
                    owners.merge(new Owner(dimension, pos), count, Integer::sum);
                }
                if (!owners.isEmpty()) {
                    USAGE.put(sha1, owners);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("读取音频引用表失败，将按区块加载情况重新建立: {}", e.toString());
        }
    }

    /** 有改动就写回存档（引用表很小，玩家操作时直接落盘） 喵 */
    public static synchronized void save() {
        if (!dirty || storagePath == null) {
            return;
        }
        if (write(storagePath)) {
            dirty = false;
        }
    }

    private static boolean write(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            CompoundTag usage = new CompoundTag();
            for (Map.Entry<String, Map<Owner, Integer>> audio : USAGE.entrySet()) {
                ListTag list = new ListTag();
                for (Map.Entry<Owner, Integer> owner : audio.getValue().entrySet()) {
                    CompoundTag entry = new CompoundTag();
                    entry.putString("dimension", owner.getKey().dimension().location().toString());
                    entry.putInt("x", owner.getKey().pos().getX());
                    entry.putInt("y", owner.getKey().pos().getY());
                    entry.putInt("z", owner.getKey().pos().getZ());
                    entry.putInt("count", owner.getValue());
                    list.add(entry);
                }
                usage.put(audio.getKey(), list);
            }
            CompoundTag root = new CompoundTag();
            root.putInt("version", FORMAT_VERSION);
            root.put("usage", usage);

            // 先写临时文件再替换，避免写到一半崩溃把引用表弄坏 喵
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            NbtIo.writeCompressed(root, temp.toFile());
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("保存音频引用表失败: {}", e.toString());
            return false;
        }
    }

    /** 服务端停止时调用（先 save 再 clear） 喵 */
    public static synchronized void clear() {
        USAGE.clear();
        storagePath = null;
        dirty = false;
    }

    // ------------------------------------------------------------ 引用同步 喵
    /**
     * 用方块当前的播放列表覆盖它在引用表里的记录。
     * 同一个音频出现多次会记数量，删掉其中一条不会误删文件。 喵
     */
    public static synchronized void update(ServerLevel level, BlockPos pos, List<Track> tracks) {
        if (level == null || pos == null) {
            return;
        }
        Owner owner = new Owner(level.dimension(), pos.immutable());
        Map<String, Integer> wanted = new HashMap<>();
        if (tracks != null) {
            for (Track track : tracks) {
                if (track == null || !track.isFile()) {
                    continue;
                }
                String sha1 = track.sha1();
                if (!SpeakerNetworking.isValidSha1(sha1)) {
                    continue;
                }
                wanted.merge(sha1, 1, Integer::sum);
            }
        }

        Set<String> touched = new HashSet<>(wanted.keySet());
        for (Map.Entry<String, Map<Owner, Integer>> audio : USAGE.entrySet()) {
            if (audio.getValue().containsKey(owner)) {
                touched.add(audio.getKey());
            }
        }

        boolean changed = false;
        for (String sha1 : touched) {
            Map<Owner, Integer> owners = USAGE.get(sha1);
            Integer current = owners == null ? null : owners.get(owner);
            Integer target = wanted.get(sha1);
            if (Objects.equals(current, target)) {
                continue;
            }
            changed = true;
            if (target == null) {
                if (owners == null) {
                    continue;
                }
                owners.remove(owner);
                if (owners.isEmpty()) {
                    USAGE.remove(sha1);
                }
            } else {
                USAGE.computeIfAbsent(sha1, key -> new HashMap<>()).put(owner, target);
            }
        }
        if (!changed) {
            return;
        }

        dirty = true;
        save();

        // 引用列表空了 → 存档里这份音频已经没有音响会用，删掉 喵
        for (String sha1 : touched) {
            if (!USAGE.containsKey(sha1)) {
                deleteAudio(level, sha1);
            }
        }
    }

    private static void deleteAudio(ServerLevel level, String sha1) {
        MinecraftServer server = level.getServer();
        if (server != null) {
            try {
                if (Files.deleteIfExists(SpeakerNetworking.audioFile(server, sha1))) {
                    LOGGER.info("音频 {} 已不再被任何音响使用，删除存档内的文件", sha1);
                }
            } catch (IOException e) {
                LOGGER.warn("删除音频 {} 失败: {}", sha1, e.toString());
            }
        }
        SpeakerNetworking.forgetDuration(sha1);
    }
}
