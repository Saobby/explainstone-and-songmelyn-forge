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

import com.ginka.explainstone.ExplainstoneConfig;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import com.ginka.explainstone.speaker.SpeakerPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端异步导入队列：文件读取、SHA-1 和本地缓存都在后台线程做，
 * 主线程每 tick 只发送有限数量的分块，避免导入大文件时卡住客户端。 喵
 */
public final class SpeakerUploader {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-upload");
    /** 每 tick 最多发送多少块；8×8000 ≈ 64 KiB/tick 喵 */
    private static final int MAX_CHUNKS_PER_TICK = 8;
    private static final Map<BlockPos, Batch> BATCHES = new ConcurrentHashMap<>();

    public enum Phase {
        PROCESSING,
        UPLOADING
    }

    public record Status(Phase phase, int percent) {
    }

    private SpeakerUploader() {
    }

    /** 开始导入一批文件；同一个方块上已有的队列会先被取消 喵 */
    public static void start(Minecraft client, BlockPos pos, List<Path> paths) {
        if (client.getConnection() == null || paths.isEmpty()) {
            return;
        }
        BlockPos key = pos.immutable();
        cancel(key);
        Batch batch = new Batch(client, key, List.copyOf(paths));
        BATCHES.put(key, batch);
        Thread thread = new Thread(batch::run, "explainstone-upload-" + key.toShortString());
        thread.setDaemon(true);
        thread.start();
    }

    /** 取消某方块上的处理/上传队列；正在后台处理的任务也会看到取消标记 喵 */
    public static void cancel(BlockPos pos) {
        Batch batch = BATCHES.remove(pos.immutable());
        if (batch != null) {
            batch.cancel();
        }
    }

    public static Status status(BlockPos pos) {
        Batch batch = BATCHES.get(pos.immutable());
        return batch == null || batch.finished ? null : batch.status();
    }

    /** 客户端主线程每 tick 推动一下上传队列 喵 */
    public static void tick(Minecraft client) {
        if (BATCHES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Batch>> it = BATCHES.entrySet().iterator();
        while (it.hasNext()) {
            Batch batch = it.next().getValue();
            if (batch.tick(client)) {
                it.remove();
            }
        }
    }

    /** 退出世界时清空所有队列 喵 */
    public static void reset() {
        for (Batch batch : BATCHES.values()) {
            batch.cancel();
        }
        BATCHES.clear();
    }

    private static final class Batch {
        private final Minecraft client;
        private final BlockPos pos;
        private final List<Path> paths;
        private final Object lock = new Object();

        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile Phase phase = Phase.PROCESSING;
        private volatile long totalBytes;
        private volatile long processedBytes;
        private volatile long uploadedBytes;
        private volatile long currentRead;
        private volatile Prepared current;
        private int sentChunks;
        private long currentUploadedBytes;
        private boolean currentUploaded;

        private Batch(Minecraft client, BlockPos pos, List<Path> paths) {
            this.client = client;
            this.pos = pos;
            this.paths = paths;
        }

        private void run() {
            try {
                for (Path path : this.paths) {
                    this.totalBytes += safeSize(path);
                }
                for (Path path : this.paths) {
                    if (this.cancelled) {
                        return;
                    }
                    long size = safeSize(path);
                    if (size <= 0 || size > ExplainstoneConfig.maxAudioFileBytes()) {
                        error("message.explainstone.import.too_large", ExplainstoneConfig.maxAudioFileMb());
                        this.processedBytes += Math.max(0L, size);
                        this.uploadedBytes += Math.max(0L, size);
                        continue;
                    }
                    this.phase = Phase.PROCESSING;
                    this.currentRead = 0L;
                    Prepared prepared = this.process(path, size);
                    if (prepared == null) {
                        if (this.cancelled) {
                            return;
                        }
                        this.processedBytes += size;
                        this.uploadedBytes += size;
                        continue;
                    }
                    this.processedBytes += size;
                    this.currentRead = 0L;
                    synchronized (this.lock) {
                        if (this.cancelled) {
                            return;
                        }
                        this.current = prepared;
                        this.sentChunks = 0;
                        this.currentUploadedBytes = 0L;
                        this.currentUploaded = false;
                        this.phase = Phase.UPLOADING;
                        this.lock.notifyAll();
                        while (!this.currentUploaded && !this.cancelled) {
                            try {
                                this.lock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                this.cancelled = true;
                            }
                        }
                        this.current = null;
                        if (this.cancelled) {
                            return;
                        }
                        this.uploadedBytes += prepared.bytes.length;
                        this.currentUploadedBytes = 0L;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("导入队列异常: {}", t.toString());
                error("message.explainstone.import.failed");
            } finally {
                this.finished = true;
                synchronized (this.lock) {
                    this.lock.notifyAll();
                }
            }
        }


        private Prepared process(Path path, long size) {
            try (InputStream in = Files.newInputStream(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 64 * 1024));
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[64 * 1024];
                long read = 0L;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (this.cancelled) {
                        return null;
                    }
                    if (n == 0) {
                        continue;
                    }
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    read += n;
                    this.currentRead = read;
                    if (read > ExplainstoneConfig.maxAudioFileBytes()) {
                        error("message.explainstone.import.too_large", ExplainstoneConfig.maxAudioFileMb());
                        return null;
                    }
                }
                byte[] bytes = out.toByteArray();
                if (!isOgg(bytes)) {
                    error("message.explainstone.import.not_ogg");
                    return null;
                }
                String sha1 = sha1Hex(digest.digest());
                if (sha1.isEmpty()) {
                    error("message.explainstone.import.failed");
                    return null;
                }
                // 本地缓存也在后台写，播放器不会因为没有本地文件而等主线程 喵
                AudioSources.saveLocal(sha1, bytes);
                String label = path.getFileName().toString();
                if (label.toLowerCase().endsWith(".ogg")) {
                    label = label.substring(0, label.length() - 4);
                }
                int total = (bytes.length + SpeakerPayloads.CHUNK_SIZE - 1) / SpeakerPayloads.CHUNK_SIZE;
                if (total <= 0) {
                    error("message.explainstone.import.read_failed", "empty file");
                    return null;
                }
                return new Prepared(label, sha1, bytes, total);
            } catch (IOException e) {
                error("message.explainstone.import.read_failed", String.valueOf(e.getMessage()));
                LOGGER.warn("读取导入文件失败: {}", path, e);
                return null;
            } catch (Exception e) {
                error("message.explainstone.import.failed");
                LOGGER.warn("处理导入文件失败: {}", path, e);
                return null;
            }
        }

        /** 返回 true 表示这个 batch 可以从 map 里移除 喵 */
        private boolean tick(Minecraft mc) {
            if (this.cancelled || this.finished) {
                return true;
            }
            if (mc.getConnection() == null) {
                this.cancel();
                return true;
            }
            synchronized (this.lock) {
                if (this.phase != Phase.UPLOADING || this.current == null) {
                    return false;
                }
                Prepared prepared = this.current;
                int budget = MAX_CHUNKS_PER_TICK;
                try {
                    while (this.sentChunks < prepared.totalChunks && budget-- > 0) {
                        int index = this.sentChunks++;
                        int from = index * SpeakerPayloads.CHUNK_SIZE;
                        int len = Math.min(SpeakerPayloads.CHUNK_SIZE, prepared.bytes.length - from);
                        byte[] part = Arrays.copyOfRange(prepared.bytes, from, from + len);
                        SpeakerNetworking.CHANNEL.sendToServer(
                                new SpeakerPayloads.ImportChunk(this.pos, prepared.label, prepared.sha1,
                                        index, prepared.totalChunks, part));
                        this.currentUploadedBytes += len;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("发送导入分块失败: {}", t.toString());
                    this.cancel();
                    return true;
                }
                if (this.sentChunks >= prepared.totalChunks) {
                    this.currentUploaded = true;
                    this.lock.notifyAll();
                }
                return false;
            }
        }


        private Status status() {
            long total = Math.max(1L, this.totalBytes);
            long done = this.phase == Phase.UPLOADING
                    ? this.uploadedBytes + this.currentUploadedBytes
                    : this.processedBytes + this.currentRead;
            int percent = (int) Math.max(0L, Math.min(100L, done * 100L / total));
            return new Status(this.phase, percent);
        }

        private void cancel() {
            this.cancelled = true;
            synchronized (this.lock) {
                this.current = null;
                this.lock.notifyAll();
            }
        }

        private void error(String key, Object... args) {
            this.client.execute(() -> {
                if (this.client.player != null) {
                    this.client.player.displayClientMessage(Component.translatable(key, args), false);
                }
            });
        }

        private static long safeSize(Path path) {
            try {
                return Files.size(path);
            } catch (IOException e) {
                return 0L;
            }
        }

        private static boolean isOgg(byte[] bytes) {
            return bytes.length > 4 && bytes[0] == 'O' && bytes[1] == 'g'
                    && bytes[2] == 'g' && bytes[3] == 'S';
        }

        private static String sha1Hex(byte[] hash) {
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
    }

    private record Prepared(String label, String sha1, byte[] bytes, int totalChunks) {
    }
}

