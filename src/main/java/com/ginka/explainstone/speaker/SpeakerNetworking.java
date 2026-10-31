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
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 服务端逻辑：处理界面操作、接收导入的 ogg（存进存档）、把存档里的音频发给客户端。
 * 音频库位置：&lt;存档&gt;/explainstone/audio/&lt;sha1&gt;.ogg 喵
 */
public final class SpeakerNetworking {
    /** 网络协议版本（含时长元数据，旧客户端会被安全拒绝） 喵 */
    private static final String PROTOCOL_VERSION = "3";
    /** 界面操作的最大有效距离（格） 喵 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64 * 64;

    private static final Map<String, Upload> UPLOADS = new HashMap<>();
    /** 服务端时长缓存：sha1 → 秒；避免每次建会话都重新解析文件 喵 */
    private static final Map<String, Integer> DURATION_CACHE = new HashMap<>();
    /** 分块上传超过这个时间没有新块就丢弃，防止半截上传一直占内存 喵 */
    private static final long UPLOAD_TIMEOUT_MS = 120_000L;

    public static SimpleChannel CHANNEL;

    private SpeakerNetworking() {
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

    /** 玩家退出时丢弃该玩家所有未完成的上传 喵 */
    public static void removePlayerUploads(ServerPlayer player) {
        if (player == null) {
            return;
        }
        String prefix = player.getUUID() + "/";
        UPLOADS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** 服务端停止时清空上传缓存 喵 */
    public static void clearUploads() {
        UPLOADS.clear();
    }

    // ------------------------------------------------------------ 音频库路径 喵
    public static Path audioDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("explainstone").resolve("audio");
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

    private static void handleRequest(SpeakerPayloads.RequestAudio payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyRequest(context.getSender(), payload));
        context.setPacketHandled(true);
    }

    private static void handleResync(SpeakerPayloads.Resync payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null) {
                PlaybackRegistry.syncNear(context.getSender());
                BroadcastRegistry.onJoin(context.getSender());
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
        BlockPos pos = payload.pos();
        if (player.blockPosition().distSqr(pos) > MAX_INTERACT_DISTANCE_SQ) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity be)) {
            return;
        }

        boolean isTransmitter = level.getBlockState(pos).getBlock() instanceof BroadcastTransmitterBlock;
        boolean broadcasting = payload.action() == SpeakerPayloads.ACTION_PLAY
                || payload.action() == SpeakerPayloads.ACTION_PLAY_INDEX;
        if (isTransmitter && broadcasting && ExplainstoneConfig.broadcastRequiresOp()
                && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("message.explainstone.broadcast.no_permission"), false);
            return;
        }

        switch (payload.action()) {
            case SpeakerPayloads.ACTION_PLAY -> {
                setPlaying(level, pos, true);
                if (be.getTimerSeconds() > 0) {
                    be.startTimer();
                }
            }
            case SpeakerPayloads.ACTION_STOP -> {
                setPlaying(level, pos, false);
                be.stopTimer();
            }
            case SpeakerPayloads.ACTION_SET_CHANNEL -> be.setChannel(payload.value());
            case SpeakerPayloads.ACTION_TOGGLE_RECEIVE -> be.setReceiveMode(!be.isReceiveMode());
            case SpeakerPayloads.ACTION_SET_TIMER -> be.setTimerSeconds(payload.value());
            case SpeakerPayloads.ACTION_TOGGLE_ENABLED -> be.setSpeakerEnabled(!be.isSpeakerEnabled());
            case SpeakerPayloads.ACTION_SET_TRANSMIT_RANGE -> be.setTransmitRange(payload.value());
            case SpeakerPayloads.ACTION_SET_VOLUME -> be.setVolume(payload.value());
            case SpeakerPayloads.ACTION_PLAY_INDEX -> {
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
        if (level.getBlockState(pos).getBlock() instanceof BroadcastTransmitterBlock) {
            BroadcastRegistry.refresh(level, pos, be);
        } else if (level.getBlockState(pos).getBlock() instanceof com.ginka.explainstone.SpeakerBlock) {
            PlaybackRegistry.ensure(level, pos, be);
        }
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
    private static void applyImport(ServerPlayer player, SpeakerPayloads.ImportChunk payload) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        // 和界面操作一样限制交互距离，防止客户端伪造坐标往远处方块上传文件 喵
        if (player.blockPosition().distSqr(payload.pos()) > MAX_INTERACT_DISTANCE_SQ) {
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

        String key = player.getUUID() + "/" + payload.pos().toShortString() + "/" + payload.sha1();
        Upload upload = UPLOADS.get(key);
        if (upload == null || upload.total != payload.total()) {
            UPLOADS.remove(key);
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
        if (seconds > ExplainstoneConfig.maxAudioDurationSeconds()) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_long",
                    ExplainstoneConfig.maxAudioDurationSeconds()), false);
            return;
        }

        SpeakerBlockEntity target = level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity be ? be : null;
        if (target == null) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.failed"), false);
            return;
        }
        String label = sanitizeLabel(payload.label());
        Track track = Track.file(label, payload.sha1());
        if (!target.getTracks().contains(track)
                && target.getTracks().size() >= ExplainstoneConfig.maxPlaylistTracks()) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.playlist_full",
                    ExplainstoneConfig.maxPlaylistTracks()), false);
            return;
        }

        try {
            Path dir = audioDir(level.getServer());
            Files.createDirectories(dir);
            Files.write(dir.resolve(payload.sha1() + ".ogg"), bytes);
        } catch (IOException e) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.failed"), false);
            return;
        }
        if (seconds > 0) {
            DURATION_CACHE.put(payload.sha1(), seconds);
        }

        if (!target.getTracks().contains(track) && target.addTrack(track) < 0) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.playlist_full",
                    ExplainstoneConfig.maxPlaylistTracks()), false);
            return;
        }
        player.displayClientMessage(Component.translatable("message.explainstone.import.ok", label), true);
    }

    /** 清除 label 里会破坏播放列表编码的分隔符，并限制长度 喵 */
    private static String sanitizeLabel(String label) {
        if (label == null) {
            return "audio";
        }
        String cleaned = label.replace('\u0001', ' ').replace('\u0002', ' ')
                .replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.length() > 128) {
            cleaned = cleaned.substring(0, 128);
        }
        return cleaned.isEmpty() ? "audio" : cleaned;
    }

    private static boolean isValidSha1(String sha1) {
        if (sha1 == null || sha1.length() != 40) {
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
            if (size <= 0) {
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
        if (sha1.isEmpty()) {
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
        if (seconds > 0) {
            DURATION_CACHE.put(sha1, seconds);
            return seconds;
        }
        return 0;
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
        String sha1 = payload.sha1();
        if (!isValidSha1(sha1)) {
            return;
        }
        Path file = audioDir(player.getServer()).resolve(sha1 + ".ogg");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            int total = (bytes.length + SpeakerPayloads.CHUNK_SIZE - 1) / SpeakerPayloads.CHUNK_SIZE;
            for (int i = 0; i < total; i++) {
                int from = i * SpeakerPayloads.CHUNK_SIZE;
                int len = Math.min(SpeakerPayloads.CHUNK_SIZE, bytes.length - from);
                byte[] part = new byte[len];
                System.arraycopy(bytes, from, part, 0, len);
                sendToPlayer(player, new SpeakerPayloads.AudioChunk(sha1, sha1, i, total, part));
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
