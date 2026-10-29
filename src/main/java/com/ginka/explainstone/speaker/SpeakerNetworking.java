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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 服务端逻辑：处理界面操作、接收导入的 ogg（存进存档）、把存档里的音频发给客户端。
 * 音频库位置：&lt;存档&gt;/explainstone/audio/&lt;sha1&gt;.ogg 喵
 */
public final class SpeakerNetworking {
    /** 网络协议版本（新增 Playback 消息，旧客户端会被安全拒绝） 喵 */
    private static final String PROTOCOL_VERSION = "2";
    /** 单个文件上限（6 MB），避免误传大文件把存档撑爆 喵 */
    private static final int MAX_FILE_BYTES = 6 * 1024 * 1024;
    /** 单曲时长上限（秒） 喵 */
    private static final int MAX_SECONDS = 5 * 60;
    /** 界面操作的最大有效距离（格） 喵 */
    private static final double MAX_INTERACT_DISTANCE_SQ = 64 * 64;

    private static final Map<String, ByteArrayOutputStream> UPLOADS = new HashMap<>();

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
        if (payload.total() <= 0 || payload.index() < 0 || payload.index() >= payload.total()) {
            return;
        }
        String key = player.getUUID() + "/" + payload.sha1();
        ByteArrayOutputStream buffer = UPLOADS.computeIfAbsent(key, k -> new ByteArrayOutputStream());
        buffer.writeBytes(payload.data());
        if (buffer.size() > MAX_FILE_BYTES) {
            UPLOADS.remove(key);
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_large"), false);
            return;
        }
        if (payload.index() + 1 < payload.total()) {
            return;
        }

        UPLOADS.remove(key);
        byte[] bytes = buffer.toByteArray();
        if (!isOgg(bytes)) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.not_ogg"), false);
            return;
        }
        int seconds = oggSeconds(bytes);
        if (seconds > MAX_SECONDS) {
            player.displayClientMessage(Component.translatable("message.explainstone.import.too_long"), false);
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

        if (level.getBlockEntity(payload.pos()) instanceof SpeakerBlockEntity be) {
            Track track = Track.file(payload.label(), payload.sha1());
            if (!be.getTracks().contains(track)) {
                be.addTrack(track);
            }
        }
        player.displayClientMessage(Component.translatable("message.explainstone.import.ok", payload.label()), true);
    }

    /** 直接解析 ogg 头算时长（采样率 + 末尾页 granule） 喵 */
    private static int oggSeconds(byte[] data) {
        int rate = -1;
        for (int i = 0; i + 4 <= data.length; i++) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                int segments = data[i + 26] & 0xFF;
                int pkt = i + 27 + segments;
                if (pkt + 16 <= data.length && data[pkt] == 1) {
                    rate = (data[pkt + 12] & 0xFF) | ((data[pkt + 13] & 0xFF) << 8)
                            | ((data[pkt + 14] & 0xFF) << 16) | ((data[pkt + 15] & 0xFF) << 24);
                }
                break;
            }
        }
        if (rate <= 0) {
            return -1;
        }
        for (int i = data.length - 4; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S'
                    && i + 14 <= data.length) {
                long granule = 0L;
                for (int b = 7; b >= 0; b--) {
                    granule = (granule << 8) | (data[i + 6 + b] & 0xFF);
                }
                return granule > 0 ? (int) (granule / rate) : -1;
            }
        }
        return -1;
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
        if (sha1.isEmpty() || sha1.length() > 64) {
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
}