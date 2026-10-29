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

import com.ginka.explainstone.BroadcastTransmitterBlock;
import com.ginka.explainstone.SpeakerBlock;
import com.ginka.explainstone.speaker.PlayMode;
import com.ginka.explainstone.speaker.SpeakerBlockEntity;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import com.ginka.explainstone.speaker.SpeakerPayloads;
import com.ginka.explainstone.speaker.Track;
import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 自建的 OpenAL 播放引擎（不受资源包限制，能直接播玩家导入的 ogg）。
 * 支持：本地播放列表 / 收信模式（听发信器广播）/ 发信器自身发声。 喵
 */
public final class SpeakerAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-audio");
    /** 单个音源解码后允许占用的 PCM 上限（32MB ≈ 5 分钟单声道 / 2.5 分钟立体声） 喵 */
    private static final long MAX_PCM_BYTES = 32L * 1024 * 1024;
    /** 只提示一次"音频太大" 喵 */
    private static boolean warnedTooBig;
    private static final int SCAN_INTERVAL = 10;
    private static final Predicate<BlockState> HAS_TARGET =
            state -> state.getBlock() instanceof SpeakerBlock || state.getBlock() instanceof BroadcastTransmitterBlock;

    private static final Map<BlockPos, Voice> VOICES = new HashMap<>();
    /** 引擎自己的 tick 时钟（20 tick = 1 秒），收到会话后用它推算已经过去的时间 喵 */
    private static long clock;
    /** 服务端下发的讲解石本地播放会话（方块坐标 → 会话） 喵 */
    private static final Map<BlockPos, SyncSession> SESSIONS = new HashMap<>();
    /** 服务端下发的频道广播会话（频道 → 会话） 喵 */
    private static final Map<Integer, BroadcastInfo> BROADCASTS = new HashMap<>();

    /**
     * 服务端会话的客户端副本：记住收到时的已播放 tick，再用本地 tick 时钟往后推。
     * 这样即使中途离开范围、重进世界，进度也会继续，而不是从 0 开始。 喵
     */
    private record SyncSession(List<Track> tracks, PlayMode mode, long elapsedTicksAtReceive,
                               long receivedClock, long revision) {
        private long elapsedTicks() {
            return this.elapsedTicksAtReceive + Math.max(0L, clock - this.receivedClock);
        }
    }

    private record BroadcastInfo(PlayMode mode, List<Track> tracks, long elapsedTicksAtReceive,
                                 long receivedClock, long revision, long lastSeenClock) {
        private long elapsedTicks() {
            return this.elapsedTicksAtReceive + Math.max(0L, clock - this.receivedClock);
        }
    }

    /** 时间轴上的一个位置：当前曲目 + 曲内偏移 + 曲目时长 喵 */
    private record Located(Track track, int index, double offsetSeconds, double durationSeconds) {
    }

    private static final Set<String> REQUESTED = new HashSet<>();

    /** 上一轮扫描到的方块（每 10 tick 刷新一次） 喵 */
    private static List<BlockPos> speakers = List.of();
    private static List<BlockPos> transmitters = List.of();
    /** 上一 tick 是否缺会话（用来做"刚进入范围立即请求一次"） 喵 */
    private static boolean wasNeedingSync;
    /** 当前客户端世界；换维度时清掉旧会话 喵 */
    private static ClientLevel lastWorld;

    private static long device;
    private static long context;
    private static boolean available;
    /** 没设备时给玩家提示一次 喵 */
    private static boolean warnNoDevice;
    private static int tickCounter;

    private SpeakerAudio() {
    }

    // ------------------------------------------------------------ OpenAL 上下文 喵
    /**
     * Minecraft 的声音引擎会在之后把它的 OpenAL 上下文设为当前上下文。
     * 我们每次操作自己的音源前都要切回自己的上下文，结束后再恢复，
     * 否则 AL 调用会作用到 MC 的音源/监听器上，声音也不会按我们的坐标做空间化 喵
     */
    private static long pushContext() {
        long previous = ALC10.alcGetCurrentContext();
        if (previous != context) {
            ALC10.alcMakeContextCurrent(context);
        }
        return previous;
    }

    private static void popContext(long previous) {
        if (previous != context) {
            ALC10.alcMakeContextCurrent(previous);
        }
    }

    public static void init() {
        long previous = ALC10.alcGetCurrentContext();
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) {
                LOGGER.warn("打不开 OpenAL 设备，讲解石将不会发声");
                warnNoDevice = true;
                return;
            }
            context = ALC10.alcCreateContext(device, (int[]) null);
            if (context == 0L || !ALC10.alcMakeContextCurrent(context)) {
                LOGGER.warn("创建 OpenAL 上下文失败，讲解石将不会发声");
                warnNoDevice = true;
                return;
            }
            available = true;
        } catch (Throwable t) {
            LOGGER.warn("初始化 OpenAL 失败: {}", t.toString());
            available = false;
            warnNoDevice = true;
        } finally {
            // 不占着当前上下文，MC 的声音引擎还要初始化它自己的 喵
            if (ALC10.alcGetCurrentContext() == context) {
                ALC10.alcMakeContextCurrent(previous);
            }
        }
    }

    /** 退出世界时清掉所有音源（设备保持打开，重进世界还能继续用） 喵 */
    public static void reset() {
        if (!available) {
            return;
        }
        long previous = pushContext();
        try {
            for (Voice voice : VOICES.values()) {
                voice.dispose();
            }
            VOICES.clear();
            clearBufferCache();
            speakers = List.of();
            transmitters = List.of();
            SESSIONS.clear();
            BROADCASTS.clear();
            REQUESTED.clear();
            lastWorld = null;
            wasNeedingSync = false;
        } finally {
            popContext(previous);
        }
    }

    public static void shutdown() {
        reset();
        if (!available) {
            return;
        }
        long previous = ALC10.alcGetCurrentContext();
        try {
            if (previous != context) {
                ALC10.alcMakeContextCurrent(context);
            }
            if (context != 0L) {
                ALC10.alcDestroyContext(context);
            }
            if (device != 0L) {
                ALC10.alcCloseDevice(device);
            }
        } catch (Throwable ignored) {
        } finally {
            ALC10.alcMakeContextCurrent(previous == context ? 0L : previous);
            available = false;
        }
    }

    // ------------------------------------------------------------------ tick
    public static void tick(Minecraft client) {
        if (!available) {
            if (warnNoDevice && client.player != null) {
                warnNoDevice = false;
                client.player.displayClientMessage(
                        Component.translatable("message.explainstone.audio.unavailable"), false);
            }
            return;
        }
        long previous = pushContext();
        try {
            tickSounds(client);
        } finally {
            popContext(previous);
        }
    }

    private static void tickSounds(Minecraft client) {
        ClientLevel world = client.level;
        LocalPlayer player = client.player;
        if (world == null || player == null) {
            clear();
            return;
        }
        if (world != lastWorld) {
            // 换维度/重进世界：旧坐标和频道的会话全部作废 喵
            lastWorld = world;
            SESSIONS.clear();
            BROADCASTS.clear();
        }
        updateListener(player);
        clock++;
        // 本 tick 所有音响共用一个参考时刻：解码耗时会计入偏移补偿，避免起播错开 喵
        long referenceNanos = System.nanoTime();

        // 扫描间隔随半径自适应：范围越大扫得越稀，避免大范围时卡顿 喵
        if (tickCounter++ % scanInterval() == 0) {
            scan(world, player.blockPosition());
        }

        // 用 RECORDS 分类：MC 声音设置里有独立的"唱片机"滑块，可以直接控制这类音频 喵
        // 我们自己的 context 不会自动吃 MC 的主音量，所以这里手动乘上 喵
        float master = client.options.getSoundSourceVolume(SoundSource.MASTER)
                * client.options.getSoundSourceVolume(SoundSource.RECORDS);
        Vec3 ear = player.getEyePosition();

        // 1) 广播/本地会话都由服务端推送，客户端只按 elapsedTicks 推算进度 喵
        Map<Integer, BroadcastInfo> broadcasts = BROADCASTS;

        Set<BlockPos> active = new HashSet<>();
        boolean needSync = false;

        // 2) 发信器只发信、不自己出声（声音由收信的讲解石发出） 喵

        // 3) 讲解石：本地播 or 收信 喵
        for (BlockPos pos : speakers) {
            if (!(world.getBlockEntity(pos) instanceof SpeakerBlockEntity be)) {
                continue;
            }
            if (!be.isSpeakerEnabled()) {
                continue;   // 这台讲解石被单独关掉了放音 喵
            }
            if (be.isReceiveMode()) {
                BroadcastInfo info = broadcasts.get(be.getChannel());
                if (info == null || info.tracks().isEmpty()) {
                    needSync = true;
                    continue;
                }
                active.add(pos);
                voice(pos).updateTimeline(info.tracks(), info.mode(), info.elapsedTicks(), ear, master,
                        be.getEffectiveAudibleRange(), be.getVolume(), referenceNanos);
                spawnNotes(world, pos);
            } else if (isPlaying(world, pos)) {
                SyncSession session = SESSIONS.get(pos);
                if (session == null || session.tracks().isEmpty()) {
                    needSync = true;
                    continue;
                }
                active.add(pos);
                voice(pos).updateTimeline(session.tracks(), session.mode(), session.elapsedTicks(), ear, master,
                        be.getEffectiveAudibleRange(), be.getVolume(), referenceNanos);
                spawnNotes(world, pos);
            }
        }

        // 3.5) 刚缺会话时立即要一次；之后每 2 秒定期校正/重试 喵
        boolean urgent = needSync && !wasNeedingSync;
        wasNeedingSync = needSync;
        if (!speakers.isEmpty() && Minecraft.getInstance().getConnection() != null
                && (urgent || clock % 40 == 0)) {
            SpeakerNetworking.CHANNEL.sendToServer(new SpeakerPayloads.Resync());
        }

        // 3.6) 已经停止/被拆掉的方块，清掉本地会话；频道广播 10 秒没更新也清掉 喵
        SESSIONS.keySet().removeIf(pos -> world.isLoaded(pos) && !isPlaying(world, pos));
        BROADCASTS.entrySet().removeIf(e -> clock - e.getValue().lastSeenClock() > 200);

        // 4) 停掉不该响的音源 喵
        Iterator<Map.Entry<BlockPos, Voice>> it = VOICES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Voice> entry = it.next();
            if (!active.contains(entry.getKey())) {
                entry.getValue().dispose();
                it.remove();
            }
        }

    }

    /** 正在出声的讲解石头顶冒音符（本地播放和收信模式都有） 喵 */
    private static void spawnNotes(ClientLevel world, BlockPos pos) {
        RandomSource random = world.random;
        if (random.nextInt(5) != 0) {
            return;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.05;
        double z = pos.getZ() + 0.5;
        for (int i = 0; i < 2; i++) {
            world.addParticle(ParticleTypes.NOTE,
                    x + (random.nextDouble() - 0.5) * 0.6,
                    y,
                    z + (random.nextDouble() - 0.5) * 0.6,
                    random.nextDouble(), 0.0, 0.0);
        }
    }

    private static Voice voice(BlockPos pos) {
        return VOICES.computeIfAbsent(pos, Voice::new);
    }

    private static void updateListener(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        // 上方向也要跟着头部转动，否则仰头/低头时左右声道会判断错误 喵
        Vec3 up = player.getUpVector(1.0F);
        AL10.alListener3f(AL10.AL_POSITION, (float) eye.x, (float) eye.y, (float) eye.z);
        AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{
                (float) look.x, (float) look.y, (float) look.z,
                (float) up.x, (float) up.y, (float) up.z});
        AL10.alListenerf(AL10.AL_GAIN, 1.0f);   // 主音量由 source 端的 master 承担，避免重复相乘 喵
    }

    private static void clear() {
        if (!available) {
            return;
        }
        for (Voice voice : VOICES.values()) {
            voice.dispose();
        }
        VOICES.clear();
        clearBufferCache();
        speakers = List.of();
        transmitters = List.of();
        SESSIONS.clear();
        BROADCASTS.clear();
        lastWorld = null;
        wasNeedingSync = false;
    }

    // ------------------------------------------------------------------ 扫描 喵
    private static int scanInterval() {
        int radius = 64;
        for (Voice voice : VOICES.values()) {
            radius = Math.max(radius, voice.rangeHint + 16);
        }
        if (radius > 320) {
            return 60;
        }
        return radius > 192 ? 40 : (radius > 96 ? 20 : SCAN_INTERVAL);
    }

    private static void scan(ClientLevel world, BlockPos center) {
        int radius = 64;
        for (Voice voice : VOICES.values()) {
            radius = Math.max(radius, voice.rangeHint + 16);
        }
        radius = Math.min(512, radius);
        int chunkRadius = (radius >> 4) + 1;
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int radiusSq = radius * radius;

        List<BlockPos> foundSpeakers = new ArrayList<>();
        List<BlockPos> foundTransmitters = new ArrayList<>();
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                if (!world.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                collect(world, chunk, center, radiusSq, foundSpeakers, foundTransmitters);
            }
        }
        foundSpeakers.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        foundTransmitters.sort((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)));
        speakers = foundSpeakers;
        transmitters = foundTransmitters;
        // 离开可闻范围后丢掉本地会话；重新进入时会立刻向服务端请求最新进度 喵
        SESSIONS.keySet().retainAll(new HashSet<>(foundSpeakers));
    }

    private static void collect(ClientLevel world, LevelChunk chunk, BlockPos center, int radiusSq,
                                List<BlockPos> outSpeakers, List<BlockPos> outTransmitters) {
        LevelChunkSection[] sections = chunk.getSections();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(HAS_TARGET)) {
                continue;
            }
            int baseY = world.getSectionYFromSectionIndex(index) << 4;
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localY = 0; localY < 16; localY++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!HAS_TARGET.test(state)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(startX + localX, baseY + localY, startZ + localZ);
                        if (pos.distSqr(center) > radiusSq) {
                            continue;
                        }
                        if (state.getBlock() instanceof BroadcastTransmitterBlock) {
                            // 发信器的广播状态由服务端推送，不用在这里扫 喵
                            continue;
                        } else if (world.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                            boolean receive = be.isReceiveMode();
                            if (!receive && !state.getValue(SpeakerBlock.PLAYING)) {
                                continue;
                            }
                            double range = be.getEffectiveAudibleRange();
                            if (range > 0.0 && pos.distSqr(center) <= range * range) {
                                outSpeakers.add(pos);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isPlaying(ClientLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof SpeakerBlock && state.getValue(SpeakerBlock.PLAYING);
    }

    /** 方块中心到耳朵的平方距离 喵 */
    private static double distanceSqToCenter(BlockPos pos, Vec3 vec) {
        double dx = pos.getX() + 0.5 - vec.x;
        double dy = pos.getY() + 0.5 - vec.y;
        double dz = pos.getZ() + 0.5 - vec.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 把多声道 16-bit PCM 下混成单声道。
     * OpenAL 对立体声 buffer 不做 3D 空间化，只有单声道 buffer 才会按声源/听者坐标做声像定位 喵
     */
    private static ByteBuffer downmixToMono(ByteBuffer source, int channels) {
        ByteBuffer src = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int frames = src.remaining() / (2 * channels);
        ByteBuffer mono = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                sum += src.getShort();
            }
            int sample = sum / channels;
            if (sample > Short.MAX_VALUE) {
                sample = Short.MAX_VALUE;
            } else if (sample < Short.MIN_VALUE) {
                sample = Short.MIN_VALUE;
            }
            mono.putShort((short) sample);
        }
        mono.flip();
        return mono;
    }

    // ------------------------------------------------------------ 共享解码 buffer 喵
    /**
     * 同一首曲子只解码一次，多个音响共用同一个 OpenAL buffer。
     * 这既省内存，也避免 4 个音响各自解码导致起播时间错开（回声） 喵
     */
    private static final int MAX_CACHED_BUFFERS = 3;
    private static final Map<String, SharedBuffer> BUFFER_CACHE = new LinkedHashMap<>(8, 0.75f, true);

    private static final class SharedBuffer {
        final int buffer;
        final double durationSeconds;
        int users;

        private SharedBuffer(int buffer, double durationSeconds) {
            this.buffer = buffer;
            this.durationSeconds = durationSeconds;
        }
    }

    private static SharedBuffer acquireBuffer(Track track) {
        SharedBuffer cached = BUFFER_CACHE.get(track.id());
        if (cached != null) {
            cached.users++;
            return cached;
        }
        // 没有缓存 → 解码一次并建成共享 buffer 喵
        InputStream stream = AudioSources.open(track);
        if (stream == null) {
            return null;
        }
        try (OggAudioStream ogg = new OggAudioStream(stream)) {
            javax.sound.sampled.AudioFormat format = ogg.getFormat();
            ByteBuffer pcm = ogg.readAll();
            if (pcm == null || !pcm.hasRemaining()) {
                return null;
            }
            if (!pcm.isDirect()) {
                ByteBuffer direct = ByteBuffer.allocateDirect(pcm.remaining());
                direct.put(pcm.duplicate());
                direct.flip();
                pcm = direct;
            }
            int channels = Math.max(1, format.getChannels());
            int sampleRate = (int) format.getSampleRate();
            if (channels > 1) {
                // OpenAL 不会对立体声 buffer 做 3D 定位；下混成单声道后才能听出方向 喵
                pcm = downmixToMono(pcm, channels);
                channels = 1;
            }
            if (pcm.remaining() > MAX_PCM_BYTES) {
                LOGGER.warn("音频解码后过大({} MB)，跳过播放: {}",
                        pcm.remaining() / (1024 * 1024), track.id());
                if (!warnedTooBig) {
                    warnedTooBig = true;
                    Minecraft client = Minecraft.getInstance();
                    if (client.player != null) {
                        client.player.displayClientMessage(
                                Component.translatable("message.explainstone.audio.too_big"), false);
                    }
                }
                return null;
            }
            double duration = pcm.remaining() / (2.0 * channels * sampleRate);
            int buffer = AL10.alGenBuffers();
            if (buffer == 0) {
                LOGGER.warn("alGenBuffers 失败: {}", track.id());
                return null;
            }
            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, sampleRate);
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) {
                LOGGER.warn("上传音频 buffer 失败(0x{}): {}", Integer.toHexString(error), track.id());
                AL10.alDeleteBuffers(buffer);
                return null;
            }
            SharedBuffer shared = new SharedBuffer(buffer, duration);
            shared.users = 1;
            BUFFER_CACHE.put(track.id(), shared);
            trimBufferCache();
            return shared;
        } catch (Exception e) {
            LOGGER.warn("解码音频失败 {}: {}", track.id(), e.toString());
            return null;
        }
    }

    private static void releaseBuffer(SharedBuffer shared) {
        if (shared == null) {
            return;
        }
        if (shared.users > 0) {
            shared.users--;
        }
        trimBufferCache();
    }

    /** 缓存超过上限时删掉最久未用且没有音响在用的 buffer 喵 */
    private static void trimBufferCache() {
        if (BUFFER_CACHE.size() <= MAX_CACHED_BUFFERS) {
            return;
        }
        Iterator<Map.Entry<String, SharedBuffer>> it = BUFFER_CACHE.entrySet().iterator();
        while (BUFFER_CACHE.size() > MAX_CACHED_BUFFERS && it.hasNext()) {
            SharedBuffer shared = it.next().getValue();
            if (shared.users <= 0) {
                AL10.alDeleteBuffers(shared.buffer);
                it.remove();
            }
        }
    }

    private static void clearBufferCache() {
        for (SharedBuffer shared : BUFFER_CACHE.values()) {
            AL10.alDeleteBuffers(shared.buffer);
        }
        BUFFER_CACHE.clear();
    }

    // ------------------------------------------------------------------ 音源 喵
    private static final class Voice {
        private final BlockPos pos;
        private Track loadedTrack;
        private int rangeHint = 40;
        private int volumePercent = 100;
        private double durationSeconds;
        private int source;
        /** 共享的 OpenAL buffer（多个音响可以指向同一个） 喵 */
        private SharedBuffer shared;

        private Voice(BlockPos pos) {
            this.pos = pos;
            this.source = AL10.alGenSources();
            AL10.alSourcef(this.source, AL10.AL_ROLLOFF_FACTOR, 0.0f);
            // 世界坐标定位（不是相对听者的坐标），这样 OpenAL 才会算声像 喵
            AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(this.source, AL10.AL_POSITION,
                    pos.getX() + 0.5f, pos.getY() + 0.5f, pos.getZ() + 0.5f);
            AL10.alSourcef(this.source, AL10.AL_PITCH, 1.0f);
        }

        /**
         * 按服务端时间轴更新音源：定位当前曲目与偏移。
         * 同一首歌已经在放就只做小幅度纠偏，不打断声音 喵
         */
        private void updateTimeline(List<Track> tracks, PlayMode mode, long elapsedTicks, Vec3 ear,
                                    float categoryGain, double audibleRange, int volumePercent,
                                    long referenceNanos) {
            this.volumePercent = Math.max(0, Math.min(100, volumePercent));
            double distance = Math.sqrt(distanceSqToCenter(this.pos, ear));
            double range = Math.max(0.0, audibleRange);
            this.rangeHint = (int) Math.ceil(range);
            float distanceGain = (range <= 0.0 || distance >= range)
                    ? 0.0f : (float) (1.0 - distance / range);
            float volumeScale = this.volumePercent / 100.0f;
            AL10.alSourcef(this.source, AL10.AL_GAIN, Math.max(0.0f, categoryGain * distanceGain * volumeScale));

            Located located = locate(tracks, mode, elapsedTicks);
            if (located == null) {
                // 还有曲目没下载/时长未知，先把文件都请求回来 喵
                requestMissingAudio(tracks);
                return;
            }
            if (!located.track().equals(this.loadedTrack)) {
                this.load(located.track(), located.offsetSeconds(), referenceNanos);
                return;
            }
            if (this.shared == null) {
                return;
            }
            // 本 tick 里前面音响的解码耗时会推迟后面音响的处理，比较时要把这部分时间算进去 喵
            double expected = located.offsetSeconds()
                    + (System.nanoTime() - referenceNanos) / 1_000_000_000.0;
            if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                this.seek(expected);
                AL10.alSourcePlay(this.source);
            } else {
                float current = AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET);
                // OpenAL 的播放位置按混音周期刷新，通常有 ~0.1 秒滞后；阈值留足余量 喵
                if (Math.abs(current - expected) > 0.5f) {
                    this.seek(expected);
                }
            }
        }

        private void load(Track track, double offsetSeconds, long referenceNanos) {
            this.releaseShared();
            this.loadedTrack = track;
            if (track.isFile() && !AudioSources.hasLocal(track)) {
                requestAudio(track);
                return;
            }
            SharedBuffer shared = acquireBuffer(track);
            if (shared == null) {
                return;
            }
            this.shared = shared;
            this.durationSeconds = shared.durationSeconds;
            AL10.alSourcei(this.source, AL10.AL_BUFFER, shared.buffer);
            AL10.alSourcei(this.source, AL10.AL_LOOPING, AL10.AL_FALSE);
            // 解码/上传 buffer 花掉的时间要补进偏移，否则先加载完的音响会“抢拍” 喵
            double elapsed = (System.nanoTime() - referenceNanos) / 1_000_000_000.0;
            this.seek(offsetSeconds + elapsed);
            AL10.alSourcePlay(this.source);
        }

        /** 释放对共享 buffer 的引用（buffer 本身留在缓存里，下次播放不用重新解码） 喵 */
        private void releaseShared() {
            if (this.source >= 0) {
                AL10.alSourceStop(this.source);
                AL10.alSourcei(this.source, AL10.AL_BUFFER, 0);
            }
            if (this.shared != null) {
                releaseBuffer(this.shared);
                this.shared = null;
            }
            this.durationSeconds = 0.0;
        }

        /** 跳到指定秒数（不改变播放/暂停状态） 喵 */
        private void seek(double offsetSeconds) {
            if (this.durationSeconds <= 0.0) {
                return;
            }
            double offset = Math.max(0.0, Math.min(this.durationSeconds - 0.05, offsetSeconds));
            AL10.alSourcef(this.source, AL11.AL_SEC_OFFSET, (float) offset);
        }

        private void dispose() {
            this.releaseShared();
            if (this.source >= 0) {
                AL10.alDeleteSources(this.source);
                this.source = -1;
            }
        }
    }

    // ---------------------------------------------------------------- 音频下载 喵
    private static void requestAudio(Track track) {
        if (!track.isFile()) {
            return;
        }
        String sha1 = track.sha1();
        if (!REQUESTED.add(sha1)) {
            return;
        }
        if (Minecraft.getInstance().getConnection() != null) {
            SpeakerNetworking.CHANNEL.sendToServer(new SpeakerPayloads.RequestAudio(sha1));
        }
    }

    /** 顺序里还有没下载到本地的文件就都请求一遍 喵 */
    private static void requestMissingAudio(List<Track> tracks) {
        for (Track track : tracks) {
            if (track.isFile() && !AudioSources.hasLocal(track)) {
                requestAudio(track);
            }
        }
    }

    /**
     * 总时间轴定位：total = 所有曲目时长之和，position = elapsed % total，
     * 再逐首累加找出当前曲目和曲内偏移。播放顺序已经由服务端固定好，
     * 所以顺序/随机/单曲循环都共用这一套算法。 喵
     */
    private static Located locate(List<Track> tracks, PlayMode mode, long elapsedTicks) {
        if (tracks.isEmpty()) {
            return null;
        }
        if (mode == PlayMode.REPEAT_ONE) {
            int duration = AudioSources.durationSeconds(tracks.get(0));
            if (duration <= 0) {
                return null;
            }
            double position = (Math.max(0L, elapsedTicks) / 20.0) % duration;
            return new Located(tracks.get(0), 0, position, duration);
        }
        int[] durations = new int[tracks.size()];
        double total = 0.0;
        for (int i = 0; i < tracks.size(); i++) {
            int duration = AudioSources.durationSeconds(tracks.get(i));
            if (duration <= 0) {
                return null;
            }
            durations[i] = duration;
            total += duration;
        }
        double position = (Math.max(0L, elapsedTicks) / 20.0) % total;
        double cursor = 0.0;
        for (int i = 0; i < durations.length; i++) {
            double end = cursor + durations[i];
            if (position < end) {
                return new Located(tracks.get(i), i, position - cursor, durations[i]);
            }
            cursor = end;
        }
        int last = durations.length - 1;
        return new Located(tracks.get(last), last, 0.0, durations[last]);
    }

    /** 播放进度：{已播秒, 总秒}；没有会话/时长未知返回 null 喵 */
    public static float[] progress(BlockPos pos) {
        SyncSession session = SESSIONS.get(pos);
        if (session == null) {
            return null;
        }
        Located located = locate(session.tracks(), session.mode(), session.elapsedTicks());
        return located == null ? null
                : new float[]{(float) located.offsetSeconds(), (float) located.durationSeconds()};
    }

    /** 广播进度（发信器自己不发声，按公共时间轴算）：{已播秒, 总秒}；没在播返回 null 喵 */
    public static float[] broadcastProgress(int channel) {
        BroadcastInfo info = BROADCASTS.get(channel);
        if (info == null) {
            return null;
        }
        Located located = locate(info.tracks(), info.mode(), info.elapsedTicks());
        return located == null ? null
                : new float[]{(float) located.offsetSeconds(), (float) located.durationSeconds()};
    }

    /** 服务端推送：某频道开始/停止广播（带播放顺序 + 已播放 tick） 喵 */
    public static void onBroadcast(SpeakerPayloads.Broadcast payload) {
        BroadcastInfo current = BROADCASTS.get(payload.channel());
        if (!payload.active()) {
            if (current == null || payload.revision() >= current.revision()) {
                BROADCASTS.remove(payload.channel());
                LOGGER.info("broadcast ch={} stopped", payload.channel());
            }
            return;
        }
        if (current != null && payload.revision() < current.revision()) {
            return;
        }
        BROADCASTS.put(payload.channel(), new BroadcastInfo(PlayMode.byOrdinal(payload.mode()),
                parseTracks(payload.tracks()), payload.elapsedTicks(), clock, payload.revision(), clock));
    }

    /** 服务端推送：某个讲解石本地播放会话开始/停止 喵 */
    public static void onPlayback(SpeakerPayloads.Playback payload) {
        SyncSession current = SESSIONS.get(payload.pos());
        if (!payload.active()) {
            if (current == null || payload.revision() >= current.revision()) {
                SESSIONS.remove(payload.pos());
            }
            return;
        }
        if (current != null && payload.revision() < current.revision()) {
            return;
        }
        SESSIONS.put(payload.pos().immutable(), new SyncSession(parseTracks(payload.tracks()),
                PlayMode.byOrdinal(payload.mode()), payload.elapsedTicks(), clock, payload.revision()));
    }

    private static List<Track> parseTracks(String encoded) {
        List<Track> tracks = new ArrayList<>();
        for (String part : encoded.split("\u0002")) {
            int sep = part.indexOf('\u0001');
            if (sep > 0) {
                tracks.add(new Track(part.substring(0, sep), part.substring(sep + 1)));
            }
        }
        return List.copyOf(tracks);
    }

    public static void onAudioReceived(String sha1) {
        REQUESTED.remove(sha1);
        for (Voice voice : VOICES.values()) {
            if (voice.loadedTrack != null && voice.loadedTrack.isFile() && voice.loadedTrack.sha1().equals(sha1)) {
                voice.loadedTrack = null;
            }
        }
    }
}