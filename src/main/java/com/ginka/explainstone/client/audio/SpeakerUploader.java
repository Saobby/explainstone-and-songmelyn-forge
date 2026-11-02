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

import com.ginka.explainstone.ExplainstoneServerConfig;
import com.ginka.explainstone.speaker.OggUtil;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import com.ginka.explainstone.speaker.SpeakerPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 客户端异步导入队列：文件读取、SHA-1 和本地缓存都在后台线程做，
 * 主线程每 tick 只发送有限数量的分块，避免导入大文件时卡住客户端。 喵
 */
public final class SpeakerUploader {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-upload");
    /** 每 tick 最多发送多少块；8×8000 ≈ 64 KiB/tick 喵 */
    private static final int MAX_CHUNKS_PER_TICK = 8;
    /** 等服务端回答「这些音频有没有」的最长时间 喵 */
    private static final long CHECK_TIMEOUT_MS = 15_000L;
    private static final Map<BlockPos, Batch> BATCHES = new ConcurrentHashMap<>();

    public enum Phase {
        PROCESSING,
        /** 已经在读文件并算好了 SHA-1，正等服务端告知哪些已存在 喵 */
        CHECKING,
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

    /** 服务端回答了元数据提交：把结果交给对应的上传队列 喵 */
    public static void onPlaylistAddResult(SpeakerPayloads.PlaylistAddResult payload) {
        if (payload == null) {
            return;
        }
        Batch batch = BATCHES.get(payload.pos().immutable());
        if (batch != null) {
            batch.acceptAddResult(payload.queryId(), payload.accepted(), payload.missing(), payload.rejected());
        }
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
        /** 正在等服务端回答的元数据提交；真正发送在主线程 tick 里做 喵 */
        private SpeakerPayloads.PlaylistAdd query;
        private long queryId;
        private boolean querySent;
        private boolean checkArrived;
        private boolean checkAccepted;
        private Set<String> missing = Set.of();
        private List<String> rejected = List.of();

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
                // 第一遍：只流式算 SHA-1，不把整批音频都塞进内存 喵
                List<Scanned> scanned = new ArrayList<>();
                for (Path path : this.paths) {
                    if (this.cancelled) {
                        return;
                    }
                    long size = safeSize(path);
                    if (size <= 0 || size > ExplainstoneServerConfig.maxAudioFileBytes()) {
                        error("message.explainstone.import.too_large", ExplainstoneServerConfig.maxAudioFileMb());
                        this.processedBytes += Math.max(0L, size);
                        this.uploadedBytes += Math.max(0L, size);
                        continue;
                    }
                    this.phase = Phase.PROCESSING;
                    this.currentRead = 0L;
                    Scanned result = this.scan(path);
                    if (result == null) {
                        if (this.cancelled) {
                            return;
                        }
                        this.processedBytes += size;
                        this.uploadedBytes += size;
                        continue;
                    }
                    scanned.add(result);
                    this.processedBytes += size;
                    this.currentRead = 0L;
                }
                // 第二遍：先把元数据提交上去（列表立刻成型），再只上传服务端还没有的字节 喵
                for (int start = 0; start < scanned.size(); start += SpeakerPayloads.MAX_QUERY_ENTRIES) {
                    if (this.cancelled) {
                        return;
                    }
                    int end = Math.min(scanned.size(), start + SpeakerPayloads.MAX_QUERY_ENTRIES);
                    List<Scanned> round = List.copyOf(scanned.subList(start, end));
                    Set<String> missing = this.submitMetadata(round);
                    if (missing == null) {
                        return;
                    }
                    for (Scanned item : round) {
                        if (this.cancelled) {
                            return;
                        }
                        if (!missing.contains(item.sha1())) {
                            // 服务端已经有这份音频：只补本地缓存，播放器就能直接播 喵
                            this.cacheLocal(item);
                            this.uploadedBytes += item.size();
                            continue;
                        }
                        this.upload(item);
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


        /** 把这一批元数据（label / sha1 / 时长提示）提交给服务端；返回 null 表示取消 喵 */
        private Set<String> submitMetadata(List<Scanned> round) {
            List<SpeakerPayloads.PlaylistAdd.Entry> entries = new ArrayList<>(round.size());
            for (Scanned item : round) {
                entries.add(new SpeakerPayloads.PlaylistAdd.Entry(item.label(), item.sha1(), item.seconds()));
            }
            long id = ThreadLocalRandom.current().nextLong();
            synchronized (this.lock) {
                this.phase = Phase.CHECKING;
                this.queryId = id;
                this.query = new SpeakerPayloads.PlaylistAdd(this.pos, id, entries);
                this.querySent = false;
                this.checkArrived = false;
                this.checkAccepted = false;
                this.missing = Set.of();
                this.rejected = List.of();
                this.lock.notifyAll();
            }
            long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MS;
            synchronized (this.lock) {
                while (!this.checkArrived && !this.cancelled) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0L) {
                        break;
                    }
                    try {
                        this.lock.wait(remain);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        this.cancelled = true;
                    }
                }
                if (this.cancelled) {
                    return null;
                }
                if (!this.checkArrived) {
                    LOGGER.warn("playlist-add 没有回应（pos={}），已取消本批导入", this.pos.toShortString());
                    error("message.explainstone.import.check_timeout");
                    this.cancelled = true;
                    return null;
                }
                if (!this.checkAccepted) {
                    error("message.explainstone.import.failed");
                    this.cancelled = true;
                    return null;
                }
                if (!this.rejected.isEmpty()) {
                    error("message.explainstone.import.rejected", this.rejected.size());
                }
                this.query = null;
                return this.missing;
            }
        }

        /** 主线程收到提交结果后交给后台线程 喵 */
        private void acceptAddResult(long queryId, boolean accepted, List<String> missing, List<String> rejected) {
            synchronized (this.lock) {
                if (this.phase != Phase.CHECKING || this.queryId != queryId || this.checkArrived) {
                    return;
                }
                this.missing = missing == null ? Set.of() : new HashSet<>(missing);
                this.rejected = rejected == null ? List.of() : List.copyOf(rejected);
                this.checkAccepted = accepted;
                this.checkArrived = true;
                this.lock.notifyAll();
            }
            LOGGER.info("playlist-add result pos={} accepted={} missing={} rejected={}",
                    this.pos.toShortString(), accepted, missing == null ? 0 : missing.size(),
                    rejected == null ? 0 : rejected.size());
        }

        /** 只流式读一遍文件算 SHA-1 和时长提示（顺手检查 OggS 头），不占内存 喵 */
        private Scanned scan(Path path) {
            try (InputStream in = Files.newInputStream(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[64 * 1024];
                byte[] head = new byte[OggUtil.EDGE_BYTES];
                byte[] tail = new byte[OggUtil.EDGE_BYTES];
                int headLength = 0;
                int tailLength = 0;
                long read = 0L;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (this.cancelled) {
                        return null;
                    }
                    if (n == 0) {
                        continue;
                    }
                    if (headLength < head.length) {
                        int copy = Math.min(head.length - headLength, n);
                        System.arraycopy(buffer, 0, head, headLength, copy);
                        headLength += copy;
                    }
                    // 尾部缓冲只留最后 EDGE_BYTES 个字节：末尾 page 的 granule 就在这里 喵
                    if (n >= tail.length) {
                        System.arraycopy(buffer, n - tail.length, tail, 0, tail.length);
                        tailLength = tail.length;
                    } else {
                        int keep = Math.min(tailLength, tail.length - n);
                        if (keep > 0) {
                            System.arraycopy(tail, tailLength - keep, tail, 0, keep);
                        }
                        System.arraycopy(buffer, 0, tail, keep, n);
                        tailLength = keep + n;
                    }
                    digest.update(buffer, 0, n);
                    read += n;
                    this.currentRead = read;
                    if (read > ExplainstoneServerConfig.maxAudioFileBytes()) {
                        error("message.explainstone.import.too_large", ExplainstoneServerConfig.maxAudioFileMb());
                        return null;
                    }
                }
                if (headLength < 4 || !OggUtil.isOgg(head)) {
                    error("message.explainstone.import.not_ogg");
                    return null;
                }
                // 时长提示：播放列表时间轴只依赖元数据，没有它连进度都算不出来 喵
                int seconds = OggUtil.durationSeconds(OggUtil.sampleRate(head, headLength),
                        OggUtil.lastGranule(tail, tailLength));
                if (seconds <= 0) {
                    error("message.explainstone.import.not_ogg");
                    return null;
                }
                String sha1 = sha1Hex(digest.digest());
                if (sha1.isEmpty()) {
                    error("message.explainstone.import.failed");
                    return null;
                }
                return new Scanned(labelOf(path), sha1, path, read, seconds);
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

        /** 真正上传前再读一遍文件并核对 SHA-1，防止预检之后文件被改 喵 */
        private byte[] readVerified(Scanned item) {
            try {
                byte[] bytes = Files.readAllBytes(item.path());
                if (bytes.length > ExplainstoneServerConfig.maxAudioFileBytes()) {
                    error("message.explainstone.import.too_large", ExplainstoneServerConfig.maxAudioFileMb());
                    return null;
                }
                if (!isOgg(bytes)) {
                    error("message.explainstone.import.not_ogg");
                    return null;
                }
                String sha1 = sha1Hex(MessageDigest.getInstance("SHA-1").digest(bytes));
                if (!sha1.equals(item.sha1())) {
                    error("message.explainstone.import.read_failed", "file changed");
                    return null;
                }
                return bytes;
            } catch (IOException e) {
                error("message.explainstone.import.read_failed", String.valueOf(e.getMessage()));
                LOGGER.warn("读取导入文件失败: {}", item.path(), e);
                return null;
            } catch (Exception e) {
                error("message.explainstone.import.failed");
                LOGGER.warn("处理导入文件失败: {}", item.path(), e);
                return null;
            }
        }

        /** 服务端已经有的音频只写本地缓存，播放器才能直接播 喵 */
        private void cacheLocal(Scanned item) {
            byte[] bytes = this.readVerified(item);
            if (bytes != null) {
                AudioSources.saveLocal(item.sha1(), bytes);
            }
        }

        /** 读文件 → 本地缓存 → 分块上传，主线程每 tick 发一部分 喵 */
        private void upload(Scanned item) {
            byte[] bytes = this.readVerified(item);
            if (bytes == null) {
                this.uploadedBytes += item.size();
                return;
            }
            // 本地缓存也在后台写，播放器不会因为没有本地文件而等主线程 喵
            AudioSources.saveLocal(item.sha1(), bytes);
            int total = (bytes.length + SpeakerPayloads.CHUNK_SIZE - 1) / SpeakerPayloads.CHUNK_SIZE;
            if (total <= 0) {
                error("message.explainstone.import.read_failed", "empty file");
                this.uploadedBytes += item.size();
                return;
            }
            Prepared prepared = new Prepared(item.label(), item.sha1(), bytes, total);
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
                if (this.phase == Phase.CHECKING) {
                    // 预检包只能在主线程发；后台线程现在正等在 checkWithServer 里 喵
                    if (!this.querySent && this.query != null) {
                        try {
                            SpeakerNetworking.CHANNEL.sendToServer(this.query);
                            this.querySent = true;
                        } catch (Throwable t) {
                            LOGGER.warn("发送音频预检请求失败: {}", t.toString());
                            this.cancel();
                            return true;
                        }
                    }
                    return false;
                }
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
            long done;
            switch (this.phase) {
                case UPLOADING -> done = this.uploadedBytes + this.currentUploadedBytes;
                case CHECKING -> done = this.totalBytes;
                default -> done = this.processedBytes + this.currentRead;
            }
            int percent = (int) Math.max(0L, Math.min(100L, done * 100L / total));
            return new Status(this.phase, percent);
        }

        private void cancel() {
            this.cancelled = true;
            synchronized (this.lock) {
                this.current = null;
                this.query = null;
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
            return OggUtil.isOgg(bytes);
        }

        /** 文件名去掉 .ogg 当曲目名，并限制网络包里的长度 喵 */
        private static String labelOf(Path path) {
            String label = path.getFileName().toString();
            if (label.toLowerCase().endsWith(".ogg")) {
                label = label.substring(0, label.length() - 4);
            }
            if (label.length() > SpeakerPayloads.MAX_LABEL_LENGTH) {
                label = label.substring(0, SpeakerPayloads.MAX_LABEL_LENGTH);
            }
            return label.isEmpty() ? "audio" : label;
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

    /** 预检阶段只记住这些信息，等确认需要上传再读一遍文件 喵 */
    private record Scanned(String label, String sha1, Path path, long size, int seconds) {
    }
}

