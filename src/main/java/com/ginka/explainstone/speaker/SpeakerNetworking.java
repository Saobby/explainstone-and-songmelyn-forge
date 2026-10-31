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

import com.ginka.explainstone.BroadcastTransmitterBlock;
import com.ginka.explainstone.Explainstone;
import com.ginka.explainstone.ExplainstoneConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务端逻辑：处理界面操作、接收导入的 ogg（存进存档）、把存档里的音频发给客户端。
 * 音频库位置：&lt;存档&gt;/explainstone/audio/&lt;sha1&gt;.ogg 喵
 */
public final class SpeakerNetworking {
    /** 网络协议版本（7：暂停快照 + 播放/暂停 + 播放列表分帧 + 上传前 SHA-1 预检，旧客户端会被安全拒绝） 喵 */
    private static final String PROTOCOL_VERSION = "7";
    /** 界面操作的最大有效距离（格） 喵 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64 * 64;

    private static final Map<String, Upload> UPLOADS = new HashMap<>();
    /** 服务端时长缓存：sha1 → 秒；避免每次建会话都重新解析文件 喵 */
    private static final Map<String, Integer> DURATION_CACHE = new HashMap<>();
    /** 分块上传超过这个时间没有新块就丢弃，防止半截上传一直占内存 喵 */
    private static final long UPLOAD_TIMEOUT_MS = 120_000L;
    /**
     * 单个玩家同时进行的上传数上限（配置项 maxUploadsPerPlayer；正常客户端一次只传一首） 喵
     * 全服上限 / 内存预算 / 各类令牌桶配额见 ExplainstoneConfig。
     */
    /** 按玩家记录的令牌桶；只在服务端线程访问 喵 */
    private static final Map<UUID, RateBucket> ACTION_BUCKETS = new HashMap<>();
    private static final Map<UUID, RateBucket> AUDIO_REQUEST_BUCKETS = new HashMap<>();
    private static final Map<UUID, RateBucket> AUDIO_BYTE_BUCKETS = new HashMap<>();

    public static SimpleChannel CHANNEL;

    private SpeakerNetworking() {
    }

    /** 简单令牌桶：容量防突发、按秒回填防刷屏 喵 */
    private static final class RateBucket {
        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefill = System.currentTimeMillis();

        private RateBucket(double capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
        }

        private boolean tryConsume(double cost) {
            long now = System.currentTimeMillis();
            long elapsed = now - this.lastRefill;
            if (elapsed > 0L) {
                this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.refillPerSecond / 1000.0);
                this.lastRefill = now;
            }
            if (this.tokens < cost) {
                return false;
            }
            this.tokens -= cost;
            return true;
        }
    }

    private static boolean allowAction(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return bucket(ACTION_BUCKETS, player, ExplainstoneConfig.actionBurst(),
                ExplainstoneConfig.actionRefillPerSecond()).tryConsume(1.0);
    }

    private static boolean allowAudioRequest(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return bucket(AUDIO_REQUEST_BUCKETS, player, ExplainstoneConfig.audioRequestBurst(),
                ExplainstoneConfig.audioRequestRefillPerSecond()).tryConsume(1.0);
    }

    /** 按文件大小扣流量预算：防止一秒钟拉几十份大文件把磁盘/带宽打满 喵 */
    private static boolean allowAudioBytes(ServerPlayer player, long bytes) {
        if (player == null) {
            return false;
        }
        double cost = Math.max(1.0, bytes / (1024.0 * 1024.0));
        return bucket(AUDIO_BYTE_BUCKETS, player, ExplainstoneConfig.audioDownloadBurstMb(),
                ExplainstoneConfig.audioDownloadMbPerSecond()).tryConsume(cost);
    }

    /**
     * 取（或按当前配置重建）某个玩家的令牌桶。
     * 这样 /reload 之后新配额会立刻生效，而不是等玩家重新登录。 喵
     */
    private static RateBucket bucket(Map<UUID, RateBucket> buckets, ServerPlayer player,
                                     double capacity, double refillPerSecond) {
        UUID id = player.getUUID();
        RateBucket bucket = buckets.get(id);
        if (bucket == null || bucket.capacity != capacity || bucket.refillPerSecond != refillPerSecond) {
            bucket = new RateBucket(capacity, refillPerSecond);
            buckets.put(id, bucket);
        }
        return bucket;
    }

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Explainstone.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals);

        // 客户端 → 服务端：界面上的各种操作 喵
        CHANNEL.registerMessage(0, SpeakerPayloads.Action.class,
                SpeakerPayloads.Action::encode, SpeakerPayloads.Action::decode,
                SpeakerNetworking::handleAction, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, SpeakerPayloads.ImportChunk.class,
                SpeakerPayloads.ImportChunk::encode, SpeakerPayloads.ImportChunk::decode,
                SpeakerNetworking::handleImportChunk, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, SpeakerPayloads.RequestAudio.class,
                SpeakerPayloads.RequestAudio::encode, SpeakerPayloads.RequestAudio::decode,
                SpeakerNetworking::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, SpeakerPayloads.Resync.class,
                SpeakerPayloads.Resync::encode, SpeakerPayloads.Resync::decode,
                SpeakerNetworking::handleResync, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // 服务端 → 客户端：广播状态 + 音频文件分块 喵
        CHANNEL.registerMessage(4, SpeakerPayloads.Broadcast.class,
                SpeakerPayloads.Broadcast::encode, SpeakerPayloads.Broadcast::decode,
                SpeakerNetworking::handleBroadcast, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(5, SpeakerPayloads.AudioChunk.class,
                SpeakerPayloads.AudioChunk::encode, SpeakerPayloads.AudioChunk::decode,
                SpeakerNetworking::handleAudioChunk, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 服务端 → 客户端：讲解石本地播放会话（播放顺序 + 已播放 tick + revision） 喵
        CHANNEL.registerMessage(6, SpeakerPayloads.Playback.class,
                SpeakerPayloads.Playback::encode, SpeakerPayloads.Playback::decode,
                SpeakerNetworking::handlePlayback, Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        // 上传前预检：客户端先发 SHA-1 列表，服务端告诉它哪些已经有了 喵
        CHANNEL.registerMessage(7, SpeakerPayloads.AudioQuery.class,
                SpeakerPayloads.AudioQuery::encode, SpeakerPayloads.AudioQuery::decode,
                SpeakerNetworking::handleQuery, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(8, SpeakerPayloads.AudioQueryResult.class,
                SpeakerPayloads.AudioQueryResult::encode, SpeakerPayloads.AudioQueryResult::decode,
                SpeakerNetworking::handleQueryResult, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToPlayer(ServerPlayer player, Object message) {
        // 没装本模组的客户端没有这条频道，直接跳过（避免服务端报错） 喵
        if (player.connection == null || !CHANNEL.isRemotePresent(player.connection.connection)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /** 定期清掉长时间没有新分块的上传，避免断线/卡住的上传永久占用内存 喵 */
    public static void cleanupUploads() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Upload>> it = UPLOADS.entrySet().iterator();
        while (it.hasNext()) {
            Upload upload = it.next().getValue();
            if (now - upload.lastActivity > UPLOAD_TIMEOUT_MS) {
                it.remove();
            }
        }
    }

    /** 玩家退出时丢弃该玩家所有未完成的上传与限速状态 喵 */
    public static void removePlayerUploads(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String prefix = player.getUUID() + "/";
        UPLOADS.keySet().removeIf(key -> key.startsWith(prefix));
        ACTION_BUCKETS.remove(player.getUUID());
        AUDIO_REQUEST_BUCKETS.remove(player.getUUID());
        AUDIO_BYTE_BUCKETS.remove(player.getUUID());
    }

    /** 服务端停止时清空上传/限速/时长缓存 喵 */
    public static void clearUploads() {
        UPLOADS.clear();
        DURATION_CACHE.clear();
        ACTION_BUCKETS.clear();
        AUDIO_REQUEST_BUCKETS.clear();
        AUDIO_BYTE_BUCKETS.clear();
    }

    /** 某个玩家当前有多少个未完成上传（key 前缀 = UUID + "/"） 喵 */
    private static int countUploads(String prefix) {
        int count = 0;
        for (String key : UPLOADS.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /** 全服所有未完成上传已经占用的字节数 喵 */
    private static long totalUploadBytes() {
        long total = 0L;
        for (Upload upload : UPLOADS.values()) {
            total += upload.size;
        }
        return total;
    }

    // ------------------------------------------------------------ 音频库路径 喵
    public static Path audioDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("explainstone").resolve("audio");
    }

    /** 存档里某份音频的路径 喵 */
    public static Path audioFile(MinecraftServer server, String sha1) {
        return audioDir(server).resolve(sha1 + ".ogg");
    }

    /** 音频文件被删掉后清掉时长缓存，避免之后又按旧时长排时间轴 喵 */
    static void forgetDuration(String sha1) {
        DURATION_CACHE.remove(sha1);
    }

    // ------------------------------------------------------------ 包处理入口 喵
    private static void handleAction(SpeakerPayloads.Action payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyAction(context.getSender(), payload));
        context.setPacketHandled(true);
    }

    private static void handleImportChunk(SpeakerPayloads.ImportChunk payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyImport(context.getSender(), payload));
        context.setPacketHandled(true);
    }

    private static void handleQuery(SpeakerPayloads.AudioQuery payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyQuery(context.getSender(), payload));
        context.setPacketHandled(true);
    }

    private static void handleQueryResult(SpeakerPayloads.AudioQueryResult payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ginka.explainstone.client.ClientNetworkHandler.handleAudioQueryResult(payload)));
        context.setPacketHandled(true);
    }

    private static void handleRequest(SpeakerPayloads.RequestAudio payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyRequest(context.getSender(), payload));
        context.setPacketHandled(true);
    }

    private static void handleResync(SpeakerPayloads.Resync payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && allowAction(player)) {
                PlaybackRegistry.syncNear(player);
                BroadcastRegistry.onJoin(player);
            }
        });
        context.setPacketHandled(true);
    }

    private static void handlePlayback(SpeakerPayloads.Playback payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ginka.explainstone.client.ClientNetworkHandler.handlePlayback(payload)));
        context.setPacketHandled(true);
    }

    private static void handleBroadcast(SpeakerPayloads.Broadcast payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ginka.explainstone.client.ClientNetworkHandler.handleBroadcast(payload)));
        context.setPacketHandled(true);
    }

    private static void handleAudioChunk(SpeakerPayloads.AudioChunk payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.ginka.explainstone.client.ClientNetworkHandler.handleAudioChunk(payload)));
        context.setPacketHandled(true);
    }

    // ------------------------------------------------------------ 界面操作 喵
    private static void applyAction(ServerPlayer player, SpeakerPayloads.Action payload) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 界面操作限速：防止伪造包高频刷方块同步/广播重推 喵
        if (!allowAction(player)) {
            return;
        }
        BlockPos pos = payload.pos();
        if (player.blockPosition().distSqr(pos) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity be)) {
            return;
        }

        boolean isTransmitter = level.getBlockState(pos).getBlock() instanceof BroadcastTransmitterBlock;
        if (isOpOnly(player, level, pos)) {
            // 发信器在 OP 模式下整个归 OP 管：防止非 OP 停止/改频道劫持正在进行的广播 喵
            player.displayClientMessage(Component.translatable("message.explainstone.broadcast.no_permission"), false);
            return;
        }

        boolean wasPaused = be.isPaused();
        switch (payload.action()) {
            case SpeakerPayloads.ACTION_PLAY -> {
                be.setPaused(false);
                setPlaying(level, pos, true);
                // 从暂停恢复时不要重置定时，否则剩余时间会重新算 喵
                if (!wasPaused && be.getTimerSeconds() > 0) {
                    be.startTimer();
                }
            }
            case SpeakerPayloads.ACTION_PAUSE -> {
                // 只冻结进度：保留定时剩余时间，客户端会收到 active=false 而静音 喵
                be.setPaused(true);
                setPlaying(level, pos, false);
            }
            case SpeakerPayloads.ACTION_STOP -> {
                be.setPaused(false);
                setPlaying(level, pos, false);
                be.stopTimer();
            }
            case SpeakerPayloads.ACTION_SET_CHANNEL -> be.setChannel(payload.value());
            case SpeakerPayloads.ACTION_TOGGLE_RECEIVE -> {
                be.setReceiveMode(!be.isReceiveMode());
                // 收发模式切换时暂停状态作废：收信模式没有“暂停”，切回自播放也从停止态开始 喵
                be.setPaused(false);
                if (be.isReceiveMode()) {
                    // 切到收信就停掉本机自播放：避免标题还显示"播放中"、红石输出也一直亮着 喵
                    setPlaying(level, pos, false);
                    be.stopTimer();
                    PlaybackRegistry.stop(level, pos);
                }
            }
            case SpeakerPayloads.ACTION_SET_TIMER -> be.setTimerSeconds(payload.value());
            case SpeakerPayloads.ACTION_SET_TRANSMIT_RANGE -> be.setTransmitRange(payload.value());
            case SpeakerPayloads.ACTION_SET_VOLUME -> be.setVolume(payload.value());
            case SpeakerPayloads.ACTION_PLAY_INDEX -> {
                be.setPaused(false);
                be.setSelected(payload.value());
                setPlaying(level, pos, true);
            }
            case SpeakerPayloads.ACTION_CYCLE_MODE -> be.setMode(be.getMode().next());
            case SpeakerPayloads.ACTION_TOGGLE_REDSTONE -> be.setRespectRedstone(!be.isRespectRedstone());
            case SpeakerPayloads.ACTION_SELECT -> be.setSelected(payload.value());
            case SpeakerPayloads.ACTION_REMOVE_TRACK -> be.removeTrack(payload.value());
            case SpeakerPayloads.ACTION_REMOVE_ALL -> be.clearTracks();
            default -> {
            }
        }

        // 状态变了 → 重新登记并同步给附近玩家（列表/模式没变时不会重置进度） 喵
        if (isTransmitter) {
            BroadcastRegistry.refresh(level, pos, be);
        } else if (level.getBlockState(pos).getBlock() instanceof com.ginka.explainstone.SpeakerBlock) {
            PlaybackRegistry.ensure(level, pos, be);
        }
    }

    /** 配置为「只有 OP 能广播」时，发信器的界面操作/预检/上传都只允许 OP 使用 喵 */
    private static boolean isOpOnly(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return ExplainstoneConfig.broadcastRequiresOp()
                && level.getBlockState(pos).getBlock() instanceof BroadcastTransmitterBlock
                && !player.hasPermissions(2);
    }

    private static void setPlaying(ServerLevel level, BlockPos pos, boolean playing) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BroadcastTransmitterBlock
                && state.getValue(BroadcastTransmitterBlock.PLAYING) != playing) {
            level.setBlock(pos, state.setValue(BroadcastTransmitterBlock.PLAYING, playing), Block.UPDATE_ALL);
        } else if (state.getBlock() instanceof com.ginka.explainstone.SpeakerBlock
                && state.getValue(com.ginka.explainstone.SpeakerBlock.PLAYING) != playing) {
            level.setBlock(pos, state.setValue(com.ginka.explainstone.SpeakerBlock.PLAYING, playing),
                    Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------ 导入文件 喵
    /**
     * 上传前预检：把客户端要上传的 SHA-1 列表和服务端已有文件比对。
     * 已经存在的音频直接把曲目录进这个方块的播放列表（客户端就不必再传一遍了）。 喵
     */
    private static void applyQuery(ServerPlayer player, SpeakerPayloads.AudioQuery payload) {
        if (player == null) {
            return;
        }
        // 预检也限速；超速直接回 accepted=false，避免客户端干等 15 秒 喵
        if (!allowAction(player)) {
            sendToPlayer(player, new SpeakerPayloads.AudioQueryResult(
                    payload.pos(), payload.queryId(), false, List.of()));
            return;
        }
        if (player.level() instanceof ServerLevel level && isOpOnly(player, level, payload.pos())) {
            player.displayClientMessage(Component.translatable(
                    "message.explainstone.broadcast.no_permission"), false);
        }
        List<String> existing = new ArrayList<>();
        boolean accepted = false;
        if (player.level() instanceof ServerLevel level
                && player.blockPosition().distSqr(payload.pos()) <= MAX_INTERACT_DISTANCE_SQ
                && !isOpOnly(player, level, payload.pos())
                && level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity target) {
            accepted = true;
            Path dir = audioDir(level.getServer());
            List<Track> candidates = new ArrayList<>();
            for (SpeakerPayloads.AudioQuery.Entry entry : payload.entries()) {
                String sha1 = entry.sha1();
                if (!isValidSha1(sha1) || !Files.isRegularFile(dir.resolve(sha1 + ".ogg"))) {
                    continue;
                }
                candidates.add(Track.file(sanitizeLabel(entry.label()), sha1));
            }
            // 一次加完：只同步一次方块数据，避免 512 条预检刷出 512 次方块更新 喵
            target.addTracks(candidates);
            boolean full = false;
            for (Track track : candidates) {
                if (target.getTracks().contains(track)) {
                    existing.add(track.sha1());
                } else {
                    full = true;
                }
            }
            if (full) {
                player.displayClientMessage(Component.translatable(
                        "message.explainstone.import.playlist_full",
                        ExplainstoneConfig.maxPlaylistTracks()), false);
            }
            // 直接引用已有文件时也要确保引用表认识这个方块 喵
            AudioUsageRegistry.update(level, payload.pos(), target.getTracks());
        }
        sendToPlayer(player, new SpeakerPayloads.AudioQueryResult(
                payload.pos(), payload.queryId(), accepted, existing));
    }

    private static void applyImport(ServerPlayer player, SpeakerPayloads.ImportChunk payload) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 和界面操作一样限制交互距离，防止客户端伪造坐标往远处方块上传文件 喵
        if (player.blockPosition().distSqr(payload.pos()) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        if (isOpOnly(player, level, payload.pos())) {
            // 只提示一次，避免每块都刷屏 喵
            if (payload.index() == 0) {
                player.displayClientMessage(Component.translatable(
                        "message.explainstone.broadcast.no_permission"), false);
            }
            return;
        }
        if (!isValidSha1(payload.sha1())) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.failed"), false);
            return;
        }
        if (payload.total() <= 0 || payload.index() < 0 || payload.index() >= payload.total()) {
            return;
        }
        long maxBytes = ExplainstoneConfig.maxAudioFileBytes();
        long maxChunks = (maxBytes + SpeakerPayloads.CHUNK_SIZE - 1L) / SpeakerPayloads.CHUNK_SIZE;
        if (payload.total() > maxChunks) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_large",
                    ExplainstoneConfig.maxAudioFileMb()), false);
            return;
        }
        byte[] part = payload.data();
        if (part == null || part.length > SpeakerPayloads.CHUNK_SIZE) {
            return;
        }
        // 先确认目标方块真的是讲解石/发信器，避免恶意客户端用任意坐标制造大量半截上传 喵
        SpeakerBlockEntity target = level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity be ? be : null;
        if (target == null) {
            return;
        }

        String playerPrefix = player.getUUID() + "/";
        String key = playerPrefix + payload.pos().toShortString() + "/" + payload.sha1();
        Upload upload = UPLOADS.get(key);
        if (upload == null || upload.total != payload.total()) {
            UPLOADS.remove(key);
            if (UPLOADS.size() >= ExplainstoneConfig.maxUploadsTotal()
                    || countUploads(playerPrefix) >= ExplainstoneConfig.maxUploadsPerPlayer()) {
                player.displayClientMessage(Component.translatable(
                        "message.explainstone.import.busy"), false);
                return;
            }
            upload = new Upload(payload.total());
            UPLOADS.put(key, upload);
        }
        // 按 index 放进数组，不依赖到达顺序；重复块直接忽略 喵
        if (!upload.put(payload.index(), part)) {
            return;
        }
        if (upload.size > maxBytes) {
            UPLOADS.remove(key);
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_large",
                    ExplainstoneConfig.maxAudioFileMb()), false);
            return;
        }
        // 全服半截上传总内存封顶，防止多人/多开把服务端堆爆 喵
        if (totalUploadBytes() > ExplainstoneConfig.maxUploadBufferBytes()) {
            UPLOADS.remove(key);
            player.displayClientMessage(Component.translatable(
                    "message.explainstone.import.busy"), false);
            return;
        }
        if (!upload.complete()) {
            return;
        }

        UPLOADS.remove(key);
        byte[] bytes = upload.assemble();
        // 服务端重新计算 SHA-1，不能信任客户端声明的文件名 喵
        if (!sha1Hex(bytes).equals(payload.sha1())) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.sha_mismatch"), false);
            return;
        }
        if (!isOgg(bytes)) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.not_ogg"), false);
            return;
        }
        int seconds = oggSeconds(bytes);
        // 必须能解析出 Vorbis 头 / 末尾 granule：畸形文件之后每次建会话都要全文件扫描，可能被用来刷磁盘 喵
        if (seconds < 0) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.not_ogg"), false);
            return;
        }
        if (seconds > ExplainstoneConfig.maxAudioDurationSeconds()) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_long",
                    ExplainstoneConfig.maxAudioDurationSeconds()), false);
            return;
        }

        String label = sanitizeLabel(payload.label());
        Track track = Track.file(label, payload.sha1());
        if (!target.getTracks().contains(track)
                && (target.getTracks().size() >= ExplainstoneConfig.maxPlaylistTracks()
                || PlaybackRegistry.encodedLength(target.getTracks()) + PlaybackRegistry.encodedLength(track)
                > SpeakerPayloads.MAX_PLAYLIST_CHARS)) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.playlist_full",
                    ExplainstoneConfig.maxPlaylistTracks()), false);
            return;
        }

        try {
            Path dir = audioDir(level.getServer());
            Files.createDirectories(dir);
            Path file = dir.resolve(payload.sha1() + ".ogg");
            // 预检之后又有人传了同一份音频时不必重复写盘 喵
            if (!Files.isRegularFile(file)) {
                Files.write(file, bytes);
            }
        } catch (IOException e) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.failed"), false);
            return;
        }
        // 时长 0 也缓存，避免之后每次建会话都重新扫这个文件 喵
        DURATION_CACHE.put(payload.sha1(), Math.max(0, seconds));

        if (!target.getTracks().contains(track) && target.addTrack(track) < 0) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.playlist_full",
                    ExplainstoneConfig.maxPlaylistTracks()), false);
            return;
        }
        // 列表里本来就有同一条曲目时 addTrack 不会触发，这里补登记一次引用 喵
        AudioUsageRegistry.update(level, payload.pos(), target.getTracks());
        player.displayClientMessage(Component.translatable("message.explainstone.import.ok", label), true);
    }

    /** 清除 label 里会破坏播放列表编码的分隔符、格式化符和控制字符，并限制长度 喵 */
    static String sanitizeLabel(String label) {
        if (label == null) {
            return "audio";
        }
        StringBuilder cleaned = new StringBuilder(label.length());
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            // § 会让客户端把曲目名当格式化文本，ISO 控制字符会破坏列表编码/显示 喵
            if (c == '\u00a7' || Character.isISOControl(c)) {
                cleaned.append(' ');
            } else {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString().trim();
        if (result.length() > SpeakerPayloads.MAX_LABEL_LENGTH) {
            result = result.substring(0, SpeakerPayloads.MAX_LABEL_LENGTH);
        }
        return result.isEmpty() ? "audio" : result;
    }

    /** SHA-1 只接受 40 位小写十六进制（文件名直接用它，拒绝路径穿越） 喵 */
    static boolean isValidSha1(String sha1) {
        if (sha1 == null || sha1.length() != SpeakerPayloads.SHA1_LENGTH) {
            return false;
        }
        for (int i = 0; i < sha1.length(); i++) {
            char c = sha1.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 直接解析 ogg 头算时长（采样率 + 末尾页 granule） 喵 */
    private static int oggSeconds(byte[] data) {
        int rate = sampleRate(data);
        if (rate <= 0) {
            return -1;
        }
        long granule = lastGranule(data);
        return granule > 0 ? (int) (granule / rate) : -1;
    }

    private static int sampleRate(byte[] data) {
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                if (i + 27 > data.length) {
                    return -1;
                }
                int segments = data[i + 26] & 0xFF;
                int pkt = i + 27 + segments;
                if (pkt + 16 <= data.length && data[pkt] == 1) {
                    return (data[pkt + 12] & 0xFF) | ((data[pkt + 13] & 0xFF) << 8)
                            | ((data[pkt + 14] & 0xFF) << 16) | ((data[pkt + 15] & 0xFF) << 24);
                }
                return -1;
            }
        }
        return -1;
    }

    private static long lastGranule(byte[] data) {
        for (int i = data.length - 4; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S'
                    && i + 14 <= data.length) {
                long granule = 0L;
                for (int b = 7; b >= 0; b--) {
                    granule = (granule << 8) | (data[i + 6 + b] & 0xFF);
                }
                return granule;
            }
        }
        return -1;
    }

    /**
     * 从文件头/尾各读一小段来算时长，避免建立会话时把 500 首音频整首读一遍。 喵
     */
    private static int oggSeconds(Path path) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > ExplainstoneConfig.maxAudioFileBytes()) {
                return -1;
            }
            int headLength = (int) Math.min(size, 64 * 1024);
            int rate = sampleRate(readRange(path, 0, headLength));
            if (rate <= 0 && size > headLength) {
                // 文件头前 64 KiB 不是 Vorbis 识别头时，退回整文件解析 喵
                return oggSeconds(Files.readAllBytes(path));
            }
            if (rate <= 0) {
                return -1;
            }
            int tailLength = (int) Math.min(size, 64 * 1024);
            long tailStart = size - tailLength;
            long granule = lastGranule(readRange(path, tailStart, tailLength));
            if (granule <= 0 && size > tailLength) {
                // 极少数文件的末尾页异常大，退回整文件解析 喵
                granule = lastGranule(Files.readAllBytes(path));
            }
            return granule > 0 ? (int) (granule / rate) : -1;
        } catch (IOException e) {
            return -1;
        }
    }

    private static byte[] readRange(Path path, long offset, int length) throws IOException {
        byte[] data = new byte[length];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(offset);
            file.readFully(data);
        }
        return data;
    }

    /** 当前会话顺序对应的时长数组；0 表示暂时未知 喵 */
    public static int[] trackDurations(ServerLevel level, List<Track> tracks) {
        int[] durations = new int[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            durations[i] = trackDuration(level, tracks.get(i));
        }
        return durations;
    }

    public static int trackDuration(ServerLevel level, Track track) {
        if (track == null || !track.isFile()) {
            return 0;
        }
        String sha1 = track.sha1();
        if (!isValidSha1(sha1)) {
            return 0;
        }
        Integer cached = DURATION_CACHE.get(sha1);
        if (cached != null) {
            return cached;
        }
        Path file = audioDir(level.getServer()).resolve(sha1 + ".ogg");
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        int seconds = oggSeconds(file);
        // 解析失败也缓存 0，避免每次建会话都重新扫文件（防止被刷磁盘） 喵
        DURATION_CACHE.put(sha1, Math.max(0, seconds));
        return Math.max(0, seconds);
    }

    private static boolean isOgg(byte[] bytes) {
        // Ogg 容器固定以 "OggS" 开头 喵
        return bytes.length > 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
    }

    // ------------------------------------------------------------ 下发文件 喵
    private static void applyRequest(ServerPlayer player, SpeakerPayloads.RequestAudio payload) {
        if (player == null || player.getServer() == null) {
            return;
        }
        // 下载请求限速：防止伪造包把大文件反复读进内存再刷给客户端 喵
        if (!allowAudioRequest(player)) {
            return;
        }
        String sha1 = payload.sha1();
        if (!isValidSha1(sha1)) {
            return;
        }
        Path file = audioDir(player.getServer()).resolve(sha1 + ".ogg");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            long size = Files.size(file);
            if (size <= 0L || size > ExplainstoneConfig.maxAudioFileBytes()) {
                return;
            }
            // 按文件大小扣下载流量预算（默认 128 MB 突发 / 8 MB/s 回填） 喵
            if (!allowAudioBytes(player, size)) {
                return;
            }
            int total = (int) ((size + SpeakerPayloads.CHUNK_SIZE - 1L) / SpeakerPayloads.CHUNK_SIZE);
            // 分块读文件：不再一次性 readAllBytes，避免几个并发请求就吃掉上百 MB 堆 喵
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[SpeakerPayloads.CHUNK_SIZE];
                for (int i = 0; i < total; i++) {
                    int want = (int) Math.min(SpeakerPayloads.CHUNK_SIZE, size - (long) i * SpeakerPayloads.CHUNK_SIZE);
                    int read = 0;
                    while (read < want) {
                        int n = in.read(buffer, read, want - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    if (read <= 0) {
                        break;
                    }
                    byte[] part = new byte[read];
                    System.arraycopy(buffer, 0, part, 0, read);
                    sendToPlayer(player, new SpeakerPayloads.AudioChunk(sha1, sha1, i, total, part));
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** 一个按块编号组装中的上传文件；块可以不按顺序到达 喵 */
    private static final class Upload {
        private final int total;
        private final byte[][] chunks;
        private int received;
        private long size;
        private long lastActivity = System.currentTimeMillis();

        private Upload(int total) {
            this.total = total;
            this.chunks = new byte[total][];
        }

        private boolean put(int index, byte[] data) {
            if (index < 0 || index >= this.chunks.length || this.chunks[index] != null) {
                return false;
            }
            this.chunks[index] = data;
            this.received++;
            this.size += data.length;
            this.lastActivity = System.currentTimeMillis();
            return true;
        }

        private boolean complete() {
            return this.received >= this.total;
        }

        private byte[] assemble() {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, this.size));
            for (byte[] chunk : this.chunks) {
                if (chunk != null) {
                    out.writeBytes(chunk);
                }
            }
            return out.toByteArray();
        }
    }
}
