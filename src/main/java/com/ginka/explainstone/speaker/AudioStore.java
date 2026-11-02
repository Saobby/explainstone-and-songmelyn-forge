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

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 服务端音频仓库（内容寻址）：{@code <world>/explainstone/audio/<sha1>.ogg}。
 *
 * <p>这一层只关心"某份音频的字节在不在"，不关心任何播放列表：
 * <ul>
 *   <li>{@link #available(String)} 是"可用性"的唯一真相，播放列表同步时由它派生位图；</li>
 *   <li>写入/删除时递增 {@link #version()}，播放会话据此判断要不要重发（O(1) 比较，不用每次扫盘）；</li>
 *   <li>启动时扫一遍目录重建索引，因此崩溃/重启后不会出现"标记已上传但文件不在"的脏状态。 喵</li>
 * </ul>
 */
public final class AudioStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-audio-store");
    private static final Set<String> AVAILABLE = new HashSet<>();
    private static MinecraftServer server;
    private static Path directory;
    private static long version;

    private AudioStore() {
    }

    /** 服务端启动时调用：扫目录重建可用性索引 喵 */
    public static synchronized void load(MinecraftServer server) {
        AudioStore.server = server;
        AVAILABLE.clear();
        version++;
        directory = directoryOf(server);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOGGER.warn("创建音频目录失败: {}", e.toString());
        }
        try (Stream<Path> stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String sha1 = sha1Of(path);
                if (sha1 != null) {
                    AVAILABLE.add(sha1);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("扫描音频目录失败: {}", e.toString());
        }
        LOGGER.info("音频仓库载入完成：{} 份音频", AVAILABLE.size());
    }

    /** 服务端停止时清空，换存档不会继承旧索引 喵 */
    public static synchronized void clear() {
        AVAILABLE.clear();
        directory = null;
        server = null;
        version++;
    }

    /** 世界存档里的音频目录 喵 */
    private static Path directoryOf(MinecraftServer server) {
        return server == null ? null
                : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("explainstone").resolve("audio");
    }

    /** 可用性的唯一判定：字节是否已在存档里 喵 */
    public static synchronized boolean available(String sha1) {
        return SpeakerNetworking.isValidSha1(sha1) && AVAILABLE.contains(sha1);
    }

    /** 某份音频的文件路径；仓库还没载入时懒加载一次目录，避免索引没建好就全都拿不到路径 喵 */
    public static synchronized Path file(String sha1) {
        if (!SpeakerNetworking.isValidSha1(sha1)) {
            return null;
        }
        if (directory == null) {
            directory = directoryOf(server);
        }
        return directory == null ? null : directory.resolve(sha1 + ".ogg");
    }

    /** 可用性变化计数器：会话同步前比较一下就知道要不要重发 喵 */
    public static synchronized long version() {
        return version;
    }

    /** 写入音频（幂等：已存在就不重写），成功后立刻标为可用 喵 */
    public static synchronized boolean put(String sha1, byte[] bytes) {
        Path target = file(sha1);
        if (target == null || bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            Files.createDirectories(target.getParent());
            if (!Files.isRegularFile(target)) {
                Files.write(target, bytes);
            }
            if (AVAILABLE.add(sha1)) {
                version++;
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("写入音频失败 sha1={}: {}", sha1, e.toString());
            return false;
        }
    }

    /** 引用计数归零时删除文件（由 AudioUsageRegistry 调用） 喵 */
    public static synchronized void delete(String sha1) {
        Path target = file(sha1);
        if (target == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(target)) {
                LOGGER.info("删除无人引用的音频 sha1={}", sha1);
            }
        } catch (IOException e) {
            LOGGER.warn("删除音频失败 sha1={}: {}", sha1, e.toString());
        }
        if (AVAILABLE.remove(sha1)) {
            version++;
        }
        SpeakerNetworking.forgetDuration(sha1);
    }

    /** 文件名是 40 位小写十六进制才算本仓库的音频 喵 */
    private static String sha1Of(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".ogg") || !Files.isRegularFile(path)) {
            return null;
        }
        String sha1 = name.substring(0, name.length() - 4);
        return SpeakerNetworking.isValidSha1(sha1) ? sha1 : null;
    }
}
