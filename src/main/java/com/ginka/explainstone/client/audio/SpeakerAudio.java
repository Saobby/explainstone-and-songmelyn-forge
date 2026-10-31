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
import com.ginka.explainstone.ExplainstoneConfig;
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
import net.minecraft.core.Direction;
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
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private record SyncSession(List<Track> tracks, int[] durations, PlayMode mode,
                               long elapsedTicksAtReceive, long receivedClock, long revision,
                               boolean paused) {
        private long elapsedTicks() {
            if (this.paused) {
                return this.elapsedTicksAtReceive;
            }
            return this.elapsedTicksAtReceive + Math.max(0L, clock - this.receivedClock);
        }
    }

    private record BroadcastInfo(PlayMode mode, List<Track> tracks, int[] durations,
                                 long elapsedTicksAtReceive, long receivedClock,
                                 long revision, long lastSeenClock, boolean paused,
                                 int volumePercent) {
        private long elapsedTicks() {
            if (this.paused) {
                return this.elapsedTicksAtReceive;
            }
            return this.elapsedTicksAtReceive + Math.max(0L, clock - this.receivedClock);
        }

        /** 换音量但不打断时间轴：把当前已播 tick 冻结成新的接收点 喵 */
        private BroadcastInfo withVolume(int volume) {
            return new BroadcastInfo(this.mode, this.tracks, this.durations, this.elapsedTicks(),
                    clock, this.revision, clock, this.paused, volume);
        }
    }

    /** 时间轴上的一个位置：当前曲目 + 曲内偏移 + 曲目时长 喵 */
    private record Located(Track track, int index, double offsetSeconds, double durationSeconds) {
    }

    /** 已请求但还没收到的音频：sha1 → 上次请求时间（毫秒）；允许超时重发，避免丢包后永久没声音 喵 */
    private static final Map<String, Long> REQUESTED = new HashMap<>();
    /** 同一个音频的重发间隔 喵 */
    private static final long REQUEST_RETRY_MS = 3_000L;

    /** 多帧播放列表的重组缓存；按 revision 覆盖，超时丢弃 喵 */
    private static final Map<BlockPos, PartialPlaylist> PARTIAL_SESSIONS = new HashMap<>();
    private static final Map<Integer, PartialPlaylist> PARTIAL_BROADCASTS = new HashMap<>();
    /** 分片重组超时（tick，10 秒） 喵 */
    private static final int PARTIAL_TIMEOUT_TICKS = 200;
    /** 防御恶意服务端：最多同时维护多少个会话/频道/重组缓存 喵 */
    private static final int MAX_SESSIONS = 4096;
    private static final int MAX_BROADCASTS = 1024;
    private static final int MAX_PARTIAL_SESSIONS = 256;
    private static final int MAX_PARTIAL_BROADCASTS = 1024;

    /**
     * 一个正在按帧重组的播放列表。
     * 第 0 帧带 durations，每个分片都带 mode / elapsedTicks / revision。 喵
     */
    private static final class PartialPlaylist {
        final long revision;
        final int partCount;
        final String[] parts;
        final long firstPartClock;
        int received;
        long totalChars;
        long lastUpdateClock;
        int mode;
        long elapsedTicks;
        boolean paused;
        int volume = 100;
        int[] durations = new int[0];

        private PartialPlaylist(long revision, int partCount, long clock) {
            this.revision = revision;
            this.partCount = partCount;
            this.parts = new String[partCount];
            this.firstPartClock = clock;
            this.lastUpdateClock = clock;
        }

        private boolean put(int index, String part, int[] durations, int mode, long elapsedTicks,
                            boolean paused, int volume, long clock) {
            if (index < 0 || index >= this.parts.length || this.parts[index] != null) {
                return false;
            }
            String value = part == null ? "" : part;
            this.parts[index] = value;
            this.received++;
            this.totalChars += value.length();
            if (index == 0 && durations != null && durations.length > 0) {
                this.durations = durations;
            }
            this.mode = mode;
            this.elapsedTicks = elapsedTicks;
            this.paused = paused;
            this.volume = Math.max(0, Math.min(100, volume));
            this.lastUpdateClock = clock;
            return true;
        }

        private boolean complete() {
            return this.received >= this.partCount;
        }

        private String joined() {
            StringBuilder sb = new StringBuilder((int) Math.min(Integer.MAX_VALUE, this.totalChars));
            for (String part : this.parts) {
                if (part != null) {
                    sb.append(part);
                }
            }
            return sb.toString();
        }
    }

    /** 上一轮扫描到的方块（每 10 tick 刷新一次） 喵 */
    private static List<BlockPos> speakers = List.of();
    private static List<BlockPos> transmitters = List.of();
    /** 上一 tick 是否缺会话（用来做"刚进入范围立即请求一次"） 喵 */
    private static boolean wasNeedingSync;
    /** 当前客户端世界；换维度时清掉旧会话 喵 */
    private static ClientLevel lastWorld;
    /** 单机 Esc 暂停时我们自己的音源也要暂停 喵 */
    private static boolean audioPaused;

    /** 当前使用的 OpenAL context（就是 Minecraft 自己的那个） 喵 */
    private static long usedContext;
    /** 没设备时给玩家提示一次 喵 */
    private static boolean warnNoDevice = true;
    private static int tickCounter;
    /** 本 tick 新加载、等待统一 seek + 批量起播的音源 喵 */
    private static final List<Voice> PENDING_STARTS = new ArrayList<>();
    /** EFX 是否可用（每个 context 重新检测一次） 喵 */
    private static Boolean efxSupported;
    /** 遮挡射线检测间隔（tick，2 = 100ms） 喵 */
    private static final int OCCLUSION_INTERVAL = 2;
    /** 房间重建间隔（tick，60 = 3s） 喵 */
    private static final int ROOM_INTERVAL = 60;
    /** 混响参数更新间隔（tick，2 = 100ms） 喵 */
    private static final int REVERB_INTERVAL = 2;
    /** 同一 tick 最多重建一个房间，避免多音响同时采样造成卡顿 喵 */
    private static long lastRoomSampleTick = Long.MIN_VALUE;
    private static boolean warnedNoEfx;

    private SpeakerAudio() {
    }

    /**
     * 直接复用 Minecraft 自己的 OpenAL context / 设备。
     * MC 的 context 是全局的，声音引擎线程也在使用它；如果我们再切到自己的 context，
     * MC 声音引擎的 buffer/source 名字会在那一瞬间失效（AL_INVALID_NAME），
     * 所以这里不再创建/切换上下文，只等 MC 初始化完成后直接使用 喵
     */
    public static void init() {
        // 等 Minecraft 声音引擎初始化完成后，tick 里会自动检测到 context 喵
    }

    /** MC 重新加载声音引擎（资源重载/切设备）时旧名字全部失效，只丢掉 AL 句柄 喵 */
    public static void onSoundEngineReload() {
        discardAudioHandles();
        usedContext = 0L;
    }

    /**
     * 只丢掉 AL 句柄（旧 context 已经销毁，不能对旧名字做任何 AL 调用）。
     * 服务端会话/播放进度与 context 无关，保留下来，下个 tick 直接按原位置重建音源 喵
     */
    private static void discardAudioHandles() {
        VOICES.clear();
        PENDING_STARTS.clear();
        BUFFER_CACHE.clear();
        audioPaused = false;
        efxSupported = null;
        warnedNoEfx = false;
        // 旧 context / 旧世界遗留的解码结果全部作废 喵
        clearDecodeState();
    }

    /** 丢弃后台解码任务/结果/失败冷却（换 context 或换世界时） 喵 */
    private static void clearDecodeState() {
        DECODE_EPOCH++;
        DECODE_JOBS.clear();
        DECODE_RESULTS.clear();
        DECODE_FAILED_AT.clear();
    }

    /** 只丢引用，不对旧 context 的名字做任何 AL 调用 喵 */
    private static void discardAudioState() {
        discardAudioHandles();
        speakers = List.of();
        transmitters = List.of();
        SESSIONS.clear();
        BROADCASTS.clear();
        PARTIAL_SESSIONS.clear();
        PARTIAL_BROADCASTS.clear();
        REQUESTED.clear();
        lastWorld = null;
        wasNeedingSync = false;
    }

    /** 当前 context 是否支持 EFX（用于低通滤波） 喵 */
    private static boolean efxSupported() {
        if (efxSupported == null) {
            try {
                efxSupported = ALC.getCapabilities().ALC_EXT_EFX;
            } catch (Throwable t) {
                efxSupported = false;
            }
        }
        return efxSupported;
    }

    /** 退出世界时清掉所有音源（MC 的 context/设备保持不动） 喵 */
    public static void reset() {
        long current = ALC10.alcGetCurrentContext();
        if (current == 0L || current != usedContext) {
            discardAudioState();
            return;
        }
        for (Voice voice : VOICES.values()) {
            voice.dispose();
        }
        VOICES.clear();
        PENDING_STARTS.clear();
        clearBufferCache();
        clearDecodeState();
        speakers = List.of();
        transmitters = List.of();
        SESSIONS.clear();
        BROADCASTS.clear();
        PARTIAL_SESSIONS.clear();
        PARTIAL_BROADCASTS.clear();
        REQUESTED.clear();
        lastWorld = null;
        wasNeedingSync = false;
        audioPaused = false;
    }

    public static void shutdown() {
        // 不要关闭 MC 的 context/设备，只清理我们自己的音源 喵
        reset();
    }

    // ------------------------------------------------------------------ tick
    public static void tick(Minecraft client) {
        long current = ALC10.alcGetCurrentContext();
        if (current == 0L) {
            if (warnNoDevice && client.player != null) {
                warnNoDevice = false;
                client.player.displayClientMessage(
                        Component.translatable("message.explainstone.audio.unavailable"), false);
            }
            return;
        }
        if (current != usedContext) {
            // MC 新建/重建了 context：我们旧的 buffer/source 名字已经失效，不能再去删 喵
            discardAudioHandles();
            usedContext = current;
            LOGGER.info("using Minecraft OpenAL context 0x{}", Long.toHexString(current));
        }
        processDecodeResults();
        tickSounds(client);
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
        // 单机暂停：客户端 tick 仍在跑，但集成服务端已停；必须一起暂停音源并冻结时钟，
        // 否则每 2 秒的 Resync 会把时间轴拉回暂停点，听起来像片段反复 喵
        if (client.isPaused()) {
            pauseVoices();
            return;
        }
        if (audioPaused) {
            resumeVoices();
        }
        clock++;
        // 本 tick 所有音响共用一个参考时刻：解码耗时会计入偏移补偿，避免起播错开 喵
        long referenceNanos = System.nanoTime();

        // 扫描间隔随半径自适应：范围越大扫得越稀，避免大范围时卡顿 喵
        if (tickCounter++ % scanInterval() == 0) {
            scan(world, player.blockPosition());
        }

        // 复用 MC 的 context 后，listener 的 gain 已经由 MC 乘上主音量；
        // 这里只乘 RECORDS（唱片机）分类音量，避免主音量乘两次 喵
        float master = client.options.getSoundSourceVolume(SoundSource.RECORDS);
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
            Direction facing = be.getBlockState().getValue(SpeakerBlock.FACING);
            if (be.isReceiveMode()) {
                BroadcastInfo info = broadcasts.get(be.getChannel());
                if (info == null || info.tracks().isEmpty()) {
                    needSync = true;
                    continue;
                }
                if (info.paused()) {
                    // 频道暂停：保留会话给界面进度条，但不让收信音响发声 喵
                    continue;
                }
                active.add(pos);
                // 实际音量 = 音响自己的音量 × 发信器的全局音量；可闻范围同比缩放 喵
                int broadcastVolume = info.volumePercent();
                voice(pos).updateTimeline(info.tracks(), info.durations(), info.mode(), info.elapsedTicks(),
                        world, ear, master, be.getEffectiveAudibleRange(broadcastVolume),
                        be.getEffectiveVolume(broadcastVolume), facing, referenceNanos);
                spawnNotes(world, pos);
            } else {
                SyncSession session = SESSIONS.get(pos);
                if (session != null && session.paused()) {
                    // 本机暂停：会话保留（进度条显示冻结位置），不加入 active，下一段会停掉音源 喵
                    if (!be.isPaused()) {
                        needSync = true;   // 恢复包还没到，催服务端重发 喵
                    }
                    continue;
                }
                if (isPlaying(world, pos)) {
                    if (session == null || session.tracks().isEmpty()) {
                        needSync = true;
                        continue;
                    }
                    active.add(pos);
                    voice(pos).updateTimeline(session.tracks(), session.durations(), session.mode(),
                            session.elapsedTicks(), world, ear, master, be.getEffectiveAudibleRange(),
                            be.getVolume(), facing, referenceNanos);
                    spawnNotes(world, pos);
                } else if (be.isPaused()) {
                    // 服务端已经暂停但进度快照还没到（刚进范围/重进存档）：请求 sync 喵
                    needSync = true;
                }
            }
        }

        // 所有本 tick 新加载的音源统一 seek，再批量起播，避免各自解码/起播造成一前一后 喵
        flushPendingStarts();

        // 3.5) 刚缺会话时立即要一次；之后每 2 秒定期校正/重试 喵
        boolean urgent = needSync && !wasNeedingSync;
        wasNeedingSync = needSync;
        if (!speakers.isEmpty() && Minecraft.getInstance().getConnection() != null
                && (urgent || clock % 40 == 0)) {
            SpeakerNetworking.CHANNEL.sendToServer(new SpeakerPayloads.Resync());
        }

        // 3.6) 已经停止/被拆掉的方块，清掉本地会话；暂停的会话要保留给进度条 喵
        SESSIONS.entrySet().removeIf(e -> world.isLoaded(e.getKey())
                && !e.getValue().paused() && !isPlaying(world, e.getKey()));
        BROADCASTS.entrySet().removeIf(e -> clock - e.getValue().lastSeenClock() > 200);
        // 没凑齐的分片缓存 10 秒后丢弃，避免恶意服务端/切维度时一直占内存 喵
        if (clock % 40 == 0) {
            PARTIAL_SESSIONS.entrySet().removeIf(e -> clock - e.getValue().lastUpdateClock > PARTIAL_TIMEOUT_TICKS);
            PARTIAL_BROADCASTS.entrySet().removeIf(e -> clock - e.getValue().lastUpdateClock > PARTIAL_TIMEOUT_TICKS);
        }

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

    /**
     * 同一 tick 内新加载的所有音响：用同一个现在时刻计算目标偏移，
     * 先全部 seek，再用一次 alSourcePlayv 批量起播，尽量让它们在同一混音周期开始。 喵
     */
    private static void flushPendingStarts() {
        if (PENDING_STARTS.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        int[] sources = new int[PENDING_STARTS.size()];
        int count = 0;
        for (Voice voice : PENDING_STARTS) {
            if (voice.source < 0) {
                continue;
            }
            double target = voice.pendingOffset
                    + (now - voice.pendingReferenceNanos) / 1_000_000_000.0;
            voice.seek(target);
            sources[count++] = voice.source;
        }
        if (count > 0) {
            if (count != sources.length) {
                sources = Arrays.copyOf(sources, count);
            }
            try {
                AL10.alSourcePlayv(sources);
            } catch (Throwable t) {
                // 极少数旧的 OpenAL 实现缺 alSourcePlayv 时退回逐个起播 喵
                for (int source : sources) {
                    AL10.alSourcePlay(source);
                }
            }
            if (count > 1) {
                LOGGER.debug("[sync] batched start of {} voices", count);
            }
        }
        PENDING_STARTS.clear();
    }

    /** 单机暂停：把所有正在播放的音源暂停（位置会保留） 喵 */
    private static void pauseVoices() {
        if (audioPaused) {
            return;
        }
        audioPaused = true;
        for (Voice voice : VOICES.values()) {
            if (voice.source >= 0
                    && AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                AL10.alSourcePause(voice.source);
            }
        }
    }

    /** 恢复暂停的音源（只恢复确实处于 paused 状态的） 喵 */
    private static void resumeVoices() {
        audioPaused = false;
        for (Voice voice : VOICES.values()) {
            if (voice.source >= 0
                    && AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_PAUSED) {
                AL10.alSourcePlay(voice.source);
            }
        }
    }

    private static void clear() {
        for (Voice voice : VOICES.values()) {
            voice.dispose();
        }
        VOICES.clear();
        PENDING_STARTS.clear();
        clearBufferCache();
        clearDecodeState();
        speakers = List.of();
        transmitters = List.of();
        SESSIONS.clear();
        BROADCASTS.clear();
        lastWorld = null;
        wasNeedingSync = false;
        audioPaused = false;
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
                            // 方块实体的 paused 更新可能比暂停快照晚一帧到达，
                            // 所以客户端已有的暂停会话也算“暂停中”，避免扫描时把会话删掉 喵
                            SyncSession session = SESSIONS.get(pos);
                            boolean paused = be.isPaused() || (session != null && session.paused());
                            // 暂停时 PLAYING 是 false，但会话要保留给界面显示进度，不能从扫描结果里排除 喵
                            if (!receive && !state.getValue(SpeakerBlock.PLAYING) && !paused) {
                                continue;
                            }
                            // 收信音响的可闻范围还要乘上发信器的全局音量，和实际播放保持一致 喵
                            BroadcastInfo broadcast = receive ? BROADCASTS.get(be.getChannel()) : null;
                            double effectiveRange = broadcast == null
                                    ? be.getEffectiveAudibleRange()
                                    : be.getEffectiveAudibleRange(broadcast.volumePercent());
                            // 暂停会话的服务端发送范围至少 64 格（和界面交互距离一致），
                            // 不能只按听得到的范围扫描，否则 40 格音量下站在 50 格处进度会消失 喵
                            double range = paused
                                    ? Math.max(64.0, effectiveRange)
                                    : effectiveRange;
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

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------ 共享解码 buffer 喵
    /**
     * 同一首曲子只解码一次，多个音响共用同一个 OpenAL buffer。
     * 这既省内存，也避免 4 个音响各自解码导致起播时间错开（回声） 喵
     */
    private static final int MAX_CACHED_BUFFERS = 4;
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

    /** 后台解码线程：首次起播不在主线程解码整首 Ogg，避免卡顿 喵 */
    private static final ExecutorService DECODE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "explainstone-audio-decode");
        thread.setDaemon(true);
        return thread;
    });
    /** 后台解码完成的结果，主线程每 tick 取一个上传到 OpenAL 喵 */
    private static final ConcurrentLinkedQueue<DecodeResult> DECODE_RESULTS = new ConcurrentLinkedQueue<>();
    /** 正在解码的曲目 id（只在主线程读写） 喵 */
    private static final Set<String> DECODE_JOBS = new HashSet<>();
    /** 解码失败/过大的冷却时间，避免每 tick 重试 喵 */
    private static final Map<String, Long> DECODE_FAILED_AT = new HashMap<>();
    private static final int MAX_DECODE_JOBS = 4;
    private static final long DECODE_RETRY_MS = 10_000L;
    /** 声音引擎重建时递增：旧 context 下解码出来的结果直接丢弃 喵 */
    private static volatile long DECODE_EPOCH;

    /** 后台解码结果；pcm == null 表示失败。 喵 */
    private static final class DecodeResult {
        final String trackId;
        final long epoch;
        final ByteBuffer pcm;
        final int sampleRate;
        final double durationSeconds;
        final boolean tooBig;

        private DecodeResult(String trackId, long epoch, ByteBuffer pcm, int sampleRate,
                             double durationSeconds, boolean tooBig) {
            this.trackId = trackId;
            this.epoch = epoch;
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.durationSeconds = durationSeconds;
            this.tooBig = tooBig;
        }
    }

    /** 纯后台工作：读文件 + Ogg 解码 + 下混，不访问世界/OpenAL 喵 */
    private static DecodeResult decodeTrack(Track track, long epoch) {
        InputStream stream = AudioSources.open(track);
        if (stream == null) {
            return new DecodeResult(track.id(), epoch, null, 0, 0.0, false);
        }
        try (OggAudioStream ogg = new OggAudioStream(stream)) {
            javax.sound.sampled.AudioFormat format = ogg.getFormat();
            ByteBuffer pcm = ogg.readAll();
            if (pcm == null || !pcm.hasRemaining()) {
                return new DecodeResult(track.id(), epoch, null, 0, 0.0, false);
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
                return new DecodeResult(track.id(), epoch, pcm, sampleRate, 0.0, true);
            }
            double duration = pcm.remaining() / (2.0 * channels * sampleRate);
            return new DecodeResult(track.id(), epoch, pcm, sampleRate, duration, false);
        } catch (Exception e) {
            LOGGER.warn("解码音频失败 {}: {}", track.id(), e.toString());
            return new DecodeResult(track.id(), epoch, null, 0, 0.0, false);
        }
    }

    /** 发起后台解码（同一首只解一次；队列满时让音源下个 tick 再试） 喵 */
    private static void startDecode(Track track) {
        String id = track.id();
        if (BUFFER_CACHE.containsKey(id) || DECODE_JOBS.contains(id)) {
            return;
        }
        // 没有可用的 OpenAL context 时先不解码，避免结果堆着没人上传 喵
        if (ALC10.alcGetCurrentContext() == 0L) {
            return;
        }
        Long failedAt = DECODE_FAILED_AT.get(id);
        if (failedAt != null && System.currentTimeMillis() - failedAt < DECODE_RETRY_MS) {
            return;
        }
        if (DECODE_JOBS.size() >= MAX_DECODE_JOBS) {
            return;
        }
        DECODE_JOBS.add(id);
        long epoch = DECODE_EPOCH;
        DECODE_EXECUTOR.execute(() -> DECODE_RESULTS.add(decodeTrack(track, epoch)));
    }

    /** 正在解码 or 解码队列已满：音源应该安静等待，而不是标记为失败 喵 */
    private static boolean shouldWaitForDecode(Track track) {
        return DECODE_JOBS.contains(track.id()) || DECODE_JOBS.size() >= MAX_DECODE_JOBS;
    }

    /** 预解码下一首：真正开始播放时 buffer 已经准备好了 喵 */
    private static void prefetchBuffer(Track track) {
        if (track == null) {
            return;
        }
        if (track.isFile() && !AudioSources.hasLocal(track)) {
            return;
        }
        startDecode(track);
    }

    /** 客户端下载完音频后提前解码：等会话真正开始时 buffer 已经就绪 喵 */
    public static void prefetchDownloaded(String sha1) {
        if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) {
            return;
        }
        prefetchBuffer(Track.file(sha1, sha1));
    }

    /** 主线程每 tick 最多上传一个解码结果，避免一次性 alBufferData 造成尖峰 喵 */
    private static void processDecodeResults() {
        DecodeResult result = DECODE_RESULTS.poll();
        if (result == null) {
            return;
        }
        if (result.epoch != DECODE_EPOCH) {
            return;   // 旧 context 的结果：直接丢掉（也不能动 DECODE_JOBS 里新的同名任务） 喵
        }
        DECODE_JOBS.remove(result.trackId);
        if (result.tooBig) {
            LOGGER.warn("音频解码后过大({} MB)，跳过播放: {}",
                    result.pcm.remaining() / (1024 * 1024), result.trackId);
            if (!warnedTooBig) {
                warnedTooBig = true;
                Minecraft client = Minecraft.getInstance();
                if (client.player != null) {
                    client.player.displayClientMessage(
                            Component.translatable("message.explainstone.audio.too_big"), false);
                }
            }
            DECODE_FAILED_AT.put(result.trackId, System.currentTimeMillis());
            return;
        }
        if (result.pcm == null || !result.pcm.hasRemaining()) {
            DECODE_FAILED_AT.put(result.trackId, System.currentTimeMillis());
            return;
        }
        int buffer = AL10.alGenBuffers();
        if (buffer == 0) {
            LOGGER.warn("alGenBuffers 失败: {}", result.trackId);
            DECODE_FAILED_AT.put(result.trackId, System.currentTimeMillis());
            return;
        }
        // 共享 MC 的 context 后，AL 错误状态可能混入其它线程的错误；
        // 这里不再用 alGetError 判断（否则会出现假的“上传失败”） 喵
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, result.pcm, result.sampleRate);
        BUFFER_CACHE.put(result.trackId, new SharedBuffer(buffer, result.durationSeconds));
        DECODE_FAILED_AT.remove(result.trackId);
        trimBufferCache();
    }

    /** 主线程取 buffer：缓存没有就发起后台解码并返回 null（下个 tick 再看） 喵 */
    private static SharedBuffer acquireBuffer(Track track) {
        SharedBuffer cached = BUFFER_CACHE.get(track.id());
        if (cached != null) {
            cached.users++;
            return cached;
        }
        startDecode(track);
        return null;
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
        /** 解码/建 buffer 失败过的曲目，过一段时间才重试，避免每 tick 重试 喵 */
        private Track failedTrack;
        private long failedAt;
        private int rangeHint = 40;
        private int volumePercent = 100;
        private double durationSeconds;
        private int source;
        /** 共享的 OpenAL buffer（多个音响可以指向同一个） 喵 */
        private SharedBuffer shared;
        /** 直达声遮挡：EFX 低通滤波器 + 平滑后的振幅增益 喵 */
        private int filter = -1;
        /** 当前 filter 绑定时的 GAINHF；OpenAL Soft 在 attach 时固定参数，需要重新绑定 喵 */
        private float filterHf = 1.0f;
        private float occlusionLow = 1.0f;
        private float occlusionHigh = 1.0f;
        private float broadbandOcclusion = 1.0f;
        private long occlusionAt = Long.MIN_VALUE;
        /** 房间混响：房间参数属于音响，不随听者移动 喵 */
        private RoomAcoustics room;
        private long roomAt = Long.MIN_VALUE;
        private int auxSlot = -1;
        private int reverbEffect = -1;
        private float reverbRtMid = 0.8f;
        private float reverbHfRatio = 1.0f;
        private float reverbWet = 0.0f;
        private float reverbEffectRt = -1.0f;
        private float reverbEffectHf = -1.0f;
        private long reverbAt = Long.MIN_VALUE;
        /** 新曲加载后的统一 seek / 起播参数 喵 */
        private double pendingOffset;
        private long pendingReferenceNanos;

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
        private void updateTimeline(List<Track> tracks, int[] durations, PlayMode mode, long elapsedTicks,
                                    ClientLevel world, Vec3 ear, float categoryGain, double audibleRange,
                                    int volumePercent, Direction facing, long referenceNanos) {
            this.volumePercent = Math.max(0, Math.min(100, volumePercent));
            double dx = ear.x - (this.pos.getX() + 0.5);
            double dy = ear.y - (this.pos.getY() + 0.5);
            double dz = ear.z - (this.pos.getZ() + 0.5);
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double range = Math.max(0.0, audibleRange);
            this.rangeHint = (int) Math.ceil(range);
            float distanceGain = (range <= 0.0 || distance >= range)
                    ? 0.0f : (float) (1.0 - distance / range);
            float volumeScale = this.volumePercent / 100.0f;

            // 用完整三维向量求与喇叭朝向的夹角余弦：正前方最大，正后方最小；
            // 垂直分量也参与，所以站在音响上方/上方靠前会比站在正前方轻。 喵
            float directionalGain = 1.0f;
            if (facing != null && distance > 1.0E-4) {
                double dot = (dx * facing.getStepX()
                        + dy * facing.getStepY()
                        + dz * facing.getStepZ()) / distance;
                dot = Math.max(-1.0, Math.min(1.0, dot));
                double t = Math.max(0.0, Math.min(1.0, (dot + 1.0) * 0.5));
                double rear = Math.max(0.0, Math.min(1.0, ExplainstoneConfig.rearVolumeFactor()));
                directionalGain = (float) (rear + (1.0 - rear) * t);
            }

            // 墙壁遮挡：与前后方向衰减相乘；在音响背面又隔墙就是双重衰减 喵
            this.updateOcclusion(world, ear, distance);
            this.updateRoom(world);
            this.updateReverb();
            AL10.alSourcef(this.source, AL10.AL_GAIN, Math.max(0.0f,
                    categoryGain * distanceGain * volumeScale * directionalGain * this.broadbandOcclusion));

            Located located = locate(tracks, mode, elapsedTicks, durations);
            if (located == null) {
                // 时长元数据不完整时只尝试第一首，绝不一次拉取整个播放列表 喵
                if (!tracks.isEmpty()) {
                    Track first = tracks.get(0);
                    if (first.isFile() && !AudioSources.hasLocal(first)) {
                        requestAudio(first);
                    }
                }
                return;
            }
            Track target = located.track();
            if (target.isFile() && !AudioSources.hasLocal(target)) {
                // 当前曲没下载完：先只拉当前曲；开始播放后再预取下一首 喵
                requestAudio(target);
                return;
            }
            requestNextTrack(tracks, mode, located.index());

            if (!target.equals(this.loadedTrack) && !target.equals(this.failedTrack)) {
                this.load(target, located.offsetSeconds(), referenceNanos);
                return;
            }
            if (this.shared == null) {
                // 偶发失败后等 10 秒再试，避免永久沉默或每 tick 重试 喵
                if (target.equals(this.failedTrack) && clock - this.failedAt >= 200L) {
                    this.failedTrack = null;
                }
                return;
            }
            // 本 tick 里前面音响的解码耗时会推迟后面音响的处理，比较时要把这部分时间算进去 喵
            double expected = located.offsetSeconds()
                    + (System.nanoTime() - referenceNanos) / 1_000_000_000.0;
            if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                // 需要重新起播的音源也加入本 tick 的统一批量起播，避免暂停/重载后错位 喵
                this.pendingOffset = located.offsetSeconds();
                this.pendingReferenceNanos = referenceNanos;
                if (!PENDING_STARTS.contains(this)) {
                    PENDING_STARTS.add(this);
                }
            } else {
                float current = AL10.alGetSourcef(this.source, AL11.AL_SEC_OFFSET);
                // OpenAL 的播放位置按混音周期刷新，通常有 ~0.1 秒滞后；阈值留足余量 喵
                if (Math.abs(current - expected) > 0.5f) {
                    this.seek(expected);
                }
            }
        }

        /**
         * 每 100ms 做一次直达声遮挡射线检测，并对增益做指数平滑。
         * 低频/高频分别计算；有 EFX 时用 low-pass filter，没有则退回宽带衰减 喵
         */
        private void updateOcclusion(ClientLevel world, Vec3 ear, double distance) {
            if (world == null || distance < 1.0E-3) {
                return;
            }
            long now = clock;
            if (this.occlusionAt != Long.MIN_VALUE
                    && now - this.occlusionAt < OCCLUSION_INTERVAL) {
                return;
            }
            long previous = this.occlusionAt;
            this.occlusionAt = now;

            Vec3 source = new Vec3(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5);
            AcousticRaycast.Occlusion target = AcousticRaycast.sample(world, source, ear);
            float low = (float) Math.sqrt(Math.max(0.0, Math.min(1.0, target.lowEnergy())));
            float high = (float) Math.sqrt(Math.max(0.0, Math.min(1.0, target.highEnergy())));

            float lambda = previous == Long.MIN_VALUE ? 1.0f
                    : (float) (1.0 - Math.exp(-Math.max(1L, now - previous) / 20.0 / 0.15));
            this.occlusionLow += (low - this.occlusionLow) * lambda;
            this.occlusionHigh += (high - this.occlusionHigh) * lambda;
            this.applyOcclusion();
        }

        private void applyOcclusion() {
            float lowGain = clamp01(this.occlusionLow);
            float highGain = clamp01(this.occlusionHigh);
            float hf = lowGain <= 1.0E-4f ? 0.0f : clamp01(highGain / lowGain);

            // 低频衰减始终直接乘在 AL_GAIN 上：即使 EFX 不工作也一定能听到“变轻” 喵
            this.broadbandOcclusion = lowGain;
            this.applyHighFrequencyFilter(hf);
        }

        /**
         * OpenAL Soft 1.23.1 在 AL_DIRECT_FILTER 绑定时固定滤波器参数，
         * 绑定后再改 GAINHF 不会生效；所以参数变化时要用新 filter 重新绑定。 喵
         */
        private void applyHighFrequencyFilter(float hf) {
            if (!efxSupported()) {
                if (!warnedNoEfx) {
                    warnedNoEfx = true;
                    LOGGER.info("[occl] EFX unavailable; using broadband attenuation fallback");
                }
                return;
            }
            if (hf >= 0.999f) {
                if (this.filter >= 0) {
                    AL10.alSourcei(this.source, EXTEfx.AL_DIRECT_FILTER, 0);
                    EXTEfx.alDeleteFilters(this.filter);
                    this.filter = -1;
                }
                this.filterHf = 1.0f;
                return;
            }
            if (this.filter >= 0 && Math.abs(hf - this.filterHf) < 0.002f) {
                return;
            }
            try {
                int newFilter = EXTEfx.alGenFilters();
                if (newFilter == 0) {
                    return;
                }
                EXTEfx.alFilteri(newFilter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                // filter 自身只负责高频相对衰减；低频衰减已经由 AL_GAIN 承担 喵
                EXTEfx.alFilterf(newFilter, EXTEfx.AL_LOWPASS_GAIN, 1.0f);
                EXTEfx.alFilterf(newFilter, EXTEfx.AL_LOWPASS_GAINHF, hf);
                AL10.alSourcei(this.source, EXTEfx.AL_DIRECT_FILTER, newFilter);
                int oldFilter = this.filter;
                this.filter = newFilter;
                this.filterHf = hf;
                if (oldFilter >= 0) {
                    EXTEfx.alDeleteFilters(oldFilter);
                }
            } catch (Throwable t) {
                efxSupported = false;
                LOGGER.warn("OpenAL EFX 不可用，退回宽带遮挡: {}", t.toString());
            }
        }

        /** 每 3 秒重建一次音响所在房间；房间参数属于音响，不随听者移动 喵 */
        private void updateRoom(ClientLevel world) {
            if (!ExplainstoneConfig.reverbEnabled()) {
                this.room = null;
                return;
            }
            if (world == null) {
                return;
            }
            if (this.roomAt != Long.MIN_VALUE && clock - this.roomAt < ROOM_INTERVAL) {
                return;
            }
            // 多音响错开采样，避免同一帧连续做几百条射线 喵
            if (lastRoomSampleTick == clock) {
                return;
            }
            lastRoomSampleTick = clock;
            this.roomAt = clock;
            RoomAcoustics previous = this.room;
            Vec3 source = new Vec3(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5);
            try {
                this.room = RoomSampler.sample(world, source,
                        ExplainstoneConfig.reverbMaxRoomDistance());
            } catch (Throwable t) {
                this.room = null;
                LOGGER.warn("房间混响采样失败: {}", t.toString());
            }
            if (previous == null && this.room != null) {
                LOGGER.info("[reverb] room detected at {}: V={} S={} RT={}/{}",
                        this.pos, this.room.volume(), this.room.surfaceArea(),
                        this.room.rt60Low(), this.room.rt60High());
            } else if (previous != null && this.room == null) {
                LOGGER.info("[reverb] room lost at {}", this.pos);
            } else if (previous != null && this.room != null
                    && Math.abs(previous.volume() - this.room.volume()) > 0.5) {
                LOGGER.info("[reverb] room changed at {}: V={} -> {} RT={}/{}",
                        this.pos, previous.volume(), this.room.volume(),
                        this.room.rt60Low(), this.room.rt60High());
            }
        }

        /** 平滑房间参数，并更新 EFX 混响；湿声会随直达声遮挡略微衰减但保留更多 喵 */
        private void updateReverb() {
            if (!efxSupported() || this.source < 0) {
                return;
            }
            long now = clock;
            if (this.reverbAt != Long.MIN_VALUE
                    && now - this.reverbAt < REVERB_INTERVAL) {
                return;
            }
            long previous = this.reverbAt;
            this.reverbAt = now;
            float lambda = previous == Long.MIN_VALUE ? 1.0f
                    : (float) (1.0 - Math.exp(-Math.max(1L, now - previous) / 20.0 / 0.25));

            float targetRt = this.room != null ? (float) this.room.rt60Mid() : 0.0f;
            float targetHf = 1.0f;
            float targetWet = 0.0f;
            if (this.room != null) {
                if (this.room.rt60Mid() > 1.0E-4) {
                    targetHf = clamp((float) (this.room.rt60High() / this.room.rt60Mid()), 0.1f, 2.0f);
                }
                // 混响路径的遮挡比直达声宽松：保留一部分低频间接声 喵
                float lenient = (float) Math.sqrt(clamp01(this.occlusionLow));
                targetWet = clamp01((float) (ExplainstoneConfig.reverbWet()
                        * (0.35 + 0.65 * lenient)));
            }

            this.reverbRtMid += (targetRt - this.reverbRtMid) * lambda;
            this.reverbHfRatio += (targetHf - this.reverbHfRatio) * lambda;
            this.reverbWet += (targetWet - this.reverbWet) * lambda;

            if (this.reverbWet < 0.002f) {
                this.disableReverb();
                return;
            }

            this.ensureReverbSlot();
            if (this.auxSlot < 0) {
                return;
            }
            EXTEfx.alAuxiliaryEffectSlotf(this.auxSlot, EXTEfx.AL_EFFECTSLOT_GAIN,
                    clamp01(this.reverbWet));
            if (this.room != null) {
                this.updateReverbEffect(this.reverbRtMid, this.reverbHfRatio);
            }
        }

        private void ensureReverbSlot() {
            if (this.auxSlot >= 0 || this.source < 0) {
                return;
            }
            try {
                int slot = EXTEfx.alGenAuxiliaryEffectSlots();
                if (slot == 0) {
                    return;
                }
                EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN,
                        clamp01(this.reverbWet));
                AL11.alSource3i(this.source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
                        slot, 0, EXTEfx.AL_FILTER_NULL);
                this.auxSlot = slot;
                this.reverbEffect = -1;
                this.reverbEffectRt = -1.0f;
                this.reverbEffectHf = -1.0f;
            } catch (Throwable t) {
                LOGGER.warn("创建 EFX 混响辅助槽失败: {}", t.toString());
            }
        }

        private void updateReverbEffect(float rt, float hfRatio) {
            if (this.auxSlot < 0) {
                return;
            }
            if (this.reverbEffect >= 0
                    && Math.abs(rt - this.reverbEffectRt) < 0.01f
                    && Math.abs(hfRatio - this.reverbEffectHf) < 0.01f) {
                return;
            }
            try {
                int effect = EXTEfx.alGenEffects();
                if (effect == 0) {
                    return;
                }
                EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DIFFUSION, 1.0f);
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAIN,
                        clamp01((float) ExplainstoneConfig.reverbGain()));
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_GAINHF,
                        clamp01((float) ExplainstoneConfig.reverbGainHF()));
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_TIME,
                        clamp(rt, 0.1f, 20.0f));
                EXTEfx.alEffectf(effect, EXTEfx.AL_REVERB_DECAY_HFRATIO,
                        clamp(hfRatio, 0.1f, 2.0f));
                // 参数必须在绑定到 slot 前设好（OpenAL Soft 会在绑定时固定） 喵
                EXTEfx.alAuxiliaryEffectSloti(this.auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
                int oldEffect = this.reverbEffect;
                this.reverbEffect = effect;
                this.reverbEffectRt = rt;
                this.reverbEffectHf = hfRatio;
                if (oldEffect >= 0) {
                    EXTEfx.alDeleteEffects(oldEffect);
                } else {
                    LOGGER.info("[reverb] EFX reverb active at {}: rt={} hf={} wet={}",
                            this.pos, rt, hfRatio, this.reverbWet);
                }
            } catch (Throwable t) {
                LOGGER.warn("更新 EFX 混响效果失败: {}", t.toString());
            }
        }

        private void disableReverb() {
            if (this.auxSlot >= 0) {
                if (this.source >= 0) {
                    AL11.alSource3i(this.source, EXTEfx.AL_AUXILIARY_SEND_FILTER, 0, 0, 0);
                }
                EXTEfx.alAuxiliaryEffectSloti(this.auxSlot, EXTEfx.AL_EFFECTSLOT_EFFECT,
                        EXTEfx.AL_EFFECTSLOT_NULL);
                EXTEfx.alDeleteAuxiliaryEffectSlots(this.auxSlot);
                this.auxSlot = -1;
            }
            if (this.reverbEffect >= 0) {
                EXTEfx.alDeleteEffects(this.reverbEffect);
                this.reverbEffect = -1;
            }
            this.reverbEffectRt = -1.0f;
            this.reverbEffectHf = -1.0f;
            this.reverbWet = 0.0f;
        }

        private void load(Track track, double offsetSeconds, long referenceNanos) {
            this.releaseShared();
            this.loadedTrack = track;
            this.failedTrack = null;
            if (track.isFile() && !AudioSources.hasLocal(track)) {
                // 文件还没下载完：不要记住 loadedTrack，否则文件到了也不会重试 喵
                this.loadedTrack = null;
                requestAudio(track);
                return;
            }
            SharedBuffer shared = acquireBuffer(track);
            if (shared == null) {
                this.loadedTrack = null;
                // 正在后台解码（或解码队列满）时安静等待，不要当成失败 喵
                if (!shouldWaitForDecode(track)) {
                    this.failedTrack = track;
                    this.failedAt = clock;
                }
                return;
            }
            this.shared = shared;
            this.durationSeconds = shared.durationSeconds;
            AL10.alSourcei(this.source, AL10.AL_BUFFER, shared.buffer);
            AL10.alSourcei(this.source, AL10.AL_LOOPING, AL10.AL_FALSE);
            // 先不单独 seek/play；等本 tick 所有音响都加载完，再统一 seek + 批量起播 喵
            this.pendingOffset = offsetSeconds;
            this.pendingReferenceNanos = referenceNanos;
            if (!PENDING_STARTS.contains(this)) {
                PENDING_STARTS.add(this);
            }
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
            PENDING_STARTS.remove(this);
            this.releaseShared();
            this.disableReverb();
            if (this.filter >= 0) {
                if (this.source >= 0) {
                    AL10.alSourcei(this.source, EXTEfx.AL_DIRECT_FILTER, 0);
                }
                EXTEfx.alDeleteFilters(this.filter);
                this.filter = -1;
            }
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
        long now = System.currentTimeMillis();
        Long last = REQUESTED.get(sha1);
        if (last != null && now - last < REQUEST_RETRY_MS) {
            return;
        }
        REQUESTED.put(sha1, now);
        if (Minecraft.getInstance().getConnection() != null) {
            SpeakerNetworking.CHANNEL.sendToServer(new SpeakerPayloads.RequestAudio(sha1));
        }
    }

    /** 当前曲已在播/可播放时，只预取顺序中的下一首 喵 */
    private static void requestNextTrack(List<Track> tracks, PlayMode mode, int currentIndex) {
        if (mode == PlayMode.REPEAT_ONE || tracks.size() <= 1) {
            return;
        }
        int nextIndex = Math.floorMod(currentIndex + 1, tracks.size());
        Track next = tracks.get(nextIndex);
        if (next.isFile() && !AudioSources.hasLocal(next)) {
            requestAudio(next);
            return;
        }
        // 文件已经在本地：提前在后台解码好，下一首开始时不卡 喵
        prefetchBuffer(next);
    }

    /**
     * 总时间轴定位：total = 所有曲目时长之和，position = elapsed % total，
     * 再逐首累加找出当前曲目和曲内偏移。播放顺序已经由服务端固定好，
     * 所以顺序/随机/单曲循环都共用这一套算法。 喵
     */
    private static Located locate(List<Track> tracks, PlayMode mode, long elapsedTicks,
                                  int[] serverDurations) {
        if (tracks.isEmpty()) {
            return null;
        }
        if (mode == PlayMode.REPEAT_ONE) {
            int duration = durationSeconds(tracks.get(0), serverDurations, 0);
            if (duration <= 0) {
                return null;
            }
            double position = (Math.max(0L, elapsedTicks) / 20.0) % duration;
            return new Located(tracks.get(0), 0, position, duration);
        }
        int[] durations = new int[tracks.size()];
        double total = 0.0;
        for (int i = 0; i < tracks.size(); i++) {
            int duration = durationSeconds(tracks.get(i), serverDurations, i);
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

    /** 优先使用服务端随会话下发的时长，未知时退回本地文件解析 喵 */
    private static int durationSeconds(Track track, int[] serverDurations, int index) {
        if (serverDurations != null && index >= 0 && index < serverDurations.length
                && serverDurations[index] > 0) {
            return serverDurations[index];
        }
        return AudioSources.durationSeconds(track);
    }

    /** 本地会话是否处于暂停（界面用“已暂停”替换“正在播放”） 喵 */
    public static boolean sessionPaused(BlockPos pos) {
        SyncSession session = SESSIONS.get(pos);
        return session != null && session.paused();
    }

    /** 频道广播是否处于暂停 喵 */
    public static boolean broadcastPaused(int channel) {
        BroadcastInfo info = BROADCASTS.get(channel);
        return info != null && info.paused();
    }

    /** 播放进度：{已播秒, 总秒}；没有会话/时长未知返回 null 喵 */
    public static float[] progress(BlockPos pos) {
        SyncSession session = SESSIONS.get(pos);
        if (session == null) {
            return null;
        }
        Located located = locate(session.tracks(), session.mode(), session.elapsedTicks(), session.durations());
        return located == null ? null
                : new float[]{(float) located.offsetSeconds(), (float) located.durationSeconds()};
    }

    /** 广播进度（发信器自己不发声，按公共时间轴算）：{已播秒, 总秒}；没在播返回 null 喵 */
    public static float[] broadcastProgress(int channel) {
        BroadcastInfo info = BROADCASTS.get(channel);
        if (info == null) {
            return null;
        }
        Located located = locate(info.tracks(), info.mode(), info.elapsedTicks(), info.durations());
        return located == null ? null
                : new float[]{(float) located.offsetSeconds(), (float) located.durationSeconds()};
    }

    /** 当前本地播放曲目的显示名；没有会话/时长未知返回 null 喵 */
    public static String currentTrackLabel(BlockPos pos) {
        SyncSession session = SESSIONS.get(pos);
        if (session == null) {
            return null;
        }
        Located located = locate(session.tracks(), session.mode(), session.elapsedTicks(), session.durations());
        return located == null ? null : located.track().label();
    }

    /** 当前频道广播曲目的显示名；没有广播返回 null 喵 */
    public static String broadcastTrackLabel(int channel) {
        BroadcastInfo info = BROADCASTS.get(channel);
        if (info == null) {
            return null;
        }
        Located located = locate(info.tracks(), info.mode(), info.elapsedTicks(), info.durations());
        return located == null ? null : located.track().label();
    }

    /** 服务端推送：某频道开始/停止广播（可能分多帧；带播放顺序 + 已播放 tick） 喵 */
    public static void onBroadcast(SpeakerPayloads.Broadcast payload) {
        int channel = payload.channel();
        BroadcastInfo current = BROADCASTS.get(channel);
        long currentRev = current == null ? Long.MIN_VALUE : current.revision();
        PartialPlaylist partial = PARTIAL_BROADCASTS.get(channel);
        if (!payload.active()) {
            if (payload.revision() >= currentRev) {
                BROADCASTS.remove(channel);
                LOGGER.info("broadcast ch={} stopped", channel);
            }
            if (partial == null || payload.revision() >= partial.revision) {
                PARTIAL_BROADCASTS.remove(channel);
            }
            return;
        }
        if (payload.revision() < currentRev) {
            return;
        }
        int partCount = payload.partCount();
        int partIndex = payload.partIndex();
        if (partCount <= 0 || partCount > SpeakerPayloads.MAX_PARTS
                || partIndex < 0 || partIndex >= partCount) {
            return;
        }
        if (partial != null && payload.revision() < partial.revision) {
            return;
        }
        if (partial == null || payload.revision() > partial.revision || partial.partCount != partCount) {
            if (partial == null && PARTIAL_BROADCASTS.size() >= MAX_PARTIAL_BROADCASTS) {
                return;
            }
            if (current == null && BROADCASTS.size() >= MAX_BROADCASTS) {
                return;   // 防御恶意服务端塞无限频道 喵
            }
            partial = new PartialPlaylist(payload.revision(), partCount, clock);
            PARTIAL_BROADCASTS.put(channel, partial);
        }
        if (!partial.put(partIndex, payload.tracks(), payload.durations(), payload.mode(),
                payload.elapsedTicks(), payload.paused(), payload.volume(), clock)) {
            return;
        }
        if (partial.totalChars > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
            PARTIAL_BROADCASTS.remove(channel);
            return;
        }
        if (!partial.complete()) {
            return;
        }
        PARTIAL_BROADCASTS.remove(channel);
        BROADCASTS.put(channel, new BroadcastInfo(PlayMode.byOrdinal(partial.mode),
                parseTracks(partial.joined()), partial.durations, partial.elapsedTicks,
                partial.firstPartClock, partial.revision, clock, partial.paused, partial.volume));
    }

    /**
     * 服务端推送：发信器的全局音量变了（只带一个小包，不重推播放列表）。
     * 收信音响的实际音量 = 自己的音量 × 这个全局音量，所以这里立即生效。 喵
     */
    public static void onBroadcastVolume(int channel, int volume) {
        int value = Math.max(0, Math.min(100, volume));
        BroadcastInfo info = BROADCASTS.get(channel);
        if (info == null || info.volumePercent() == value) {
            return;
        }
        BROADCASTS.put(channel, info.withVolume(value));
    }

    /** 服务端推送：某个讲解石本地播放会话开始/停止（可能分多帧） 喵 */
    public static void onPlayback(SpeakerPayloads.Playback payload) {
        BlockPos key = payload.pos().immutable();
        SyncSession current = SESSIONS.get(key);
        long currentRev = current == null ? Long.MIN_VALUE : current.revision();
        PartialPlaylist partial = PARTIAL_SESSIONS.get(key);
        if (!payload.active()) {
            if (payload.revision() >= currentRev) {
                SESSIONS.remove(key);
            }
            if (partial == null || payload.revision() >= partial.revision) {
                PARTIAL_SESSIONS.remove(key);
            }
            return;
        }
        if (payload.revision() < currentRev) {
            return;
        }
        int partCount = payload.partCount();
        int partIndex = payload.partIndex();
        if (partCount <= 0 || partCount > SpeakerPayloads.MAX_PARTS
                || partIndex < 0 || partIndex >= partCount) {
            return;
        }
        if (partial != null && payload.revision() < partial.revision) {
            return;
        }
        if (partial == null || payload.revision() > partial.revision || partial.partCount != partCount) {
            if (partial == null && PARTIAL_SESSIONS.size() >= MAX_PARTIAL_SESSIONS) {
                return;
            }
            if (current == null && SESSIONS.size() >= MAX_SESSIONS) {
                return;   // 防御恶意服务端塞无限会话 喵
            }
            partial = new PartialPlaylist(payload.revision(), partCount, clock);
            PARTIAL_SESSIONS.put(key, partial);
        }
        if (!partial.put(partIndex, payload.tracks(), payload.durations(), payload.mode(),
                payload.elapsedTicks(), payload.paused(), 100, clock)) {
            return;
        }
        if (partial.totalChars > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
            PARTIAL_SESSIONS.remove(key);
            return;
        }
        if (!partial.complete()) {
            return;
        }
        PARTIAL_SESSIONS.remove(key);
        SESSIONS.put(key, new SyncSession(parseTracks(partial.joined()), partial.durations,
                PlayMode.byOrdinal(partial.mode), partial.elapsedTicks,
                partial.firstPartClock, partial.revision, partial.paused));
    }

    private static List<Track> parseTracks(String encoded) {
        List<Track> tracks = new ArrayList<>();
        for (String part : encoded.split("\u0002")) {
            if (tracks.size() >= 10_000) {
                break;   // 防御恶意服务端塞入过多曲目 喵
            }
            int sep = part.indexOf('\u0001');
            if (sep >= 0) {
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
            if (voice.failedTrack != null && voice.failedTrack.isFile() && voice.failedTrack.sha1().equals(sha1)) {
                voice.failedTrack = null;
            }
        }
    }
}