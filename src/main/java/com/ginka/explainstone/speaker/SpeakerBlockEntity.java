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

import com.ginka.explainstone.ExplainstoneConfig;
import com.ginka.explainstone.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 讲解石方块的数据：播放列表 + 播放模式 + 是否接受红石触发 + 当前选中的曲目。
 * 这些数据存在存档里（NBT），并会同步给打开界面的客户端。 喵
 */
public class SpeakerBlockEntity extends BlockEntity {
    private final List<Track> tracks = new ArrayList<>();
    private int selected;
    private PlayMode mode = PlayMode.SEQUENCE;
    private boolean respectRedstone = true;
    /** 上一次看到的红石状态，用来只响应"边沿"（防止旁边方块被破坏时误停播放） 喵 */
    private boolean lastPowered;
    /** 频道号：0-999，界面上显示成 350.xxx 喵 */
    private int channel = 234;
    /** 收信模式：只听广播，不放自己的列表 喵 */
    private boolean receiveMode;
    /** 用户主动暂停：保留播放位置，但不出声；停止会丢弃暂停点 喵 */
    private boolean paused;
    /** 发信器信号传播距离（格），32-512 喵 */
    private int transmitRange = 512;
    /** 本机音量百分比 0-100 喵 */
    private int volume = 100;
    /** 发信器全局音量百分比 0-100：每个收信音响的实际音量 = 本机音量 × 它 喵 */
    private int broadcastVolume = 100;
    /** 定时播放秒数（0 = 不定时），只对发信器有效 喵 */
    private int timerSeconds;
    /** 定时倒计时（tick），运行时用 喵 */
    private int ticksLeft;
    /** 是否已经把播放列表登记进服务端音频引用表 喵 */
    private boolean audioUsageRegistered;

    public SpeakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        // 包内不再自带音频，列表从空开始，玩家自己导入 ogg 喵
    }

    /** 供 BlockEntityType.Builder 使用的构造器 喵 */
    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlocks.SPEAKER_BLOCK_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------ 播放列表 喵
    public List<Track> getTracks() {
        return List.copyOf(this.tracks);
    }

    public int getSelected() {
        return this.tracks.isEmpty() ? -1 : Math.floorMod(this.selected, this.tracks.size());
    }

    public Track getSelectedTrack() {
        int i = this.getSelected();
        return i < 0 ? null : this.tracks.get(i);
    }

    public void setSelected(int index) {
        if (index >= 0 && index < this.tracks.size()) {
            this.selected = index;
            this.sync();
        }
    }

    public int addTrack(Track track) {
        if (track == null) {
            return -1;
        }
        return this.addTracks(List.of(track)) > 0 ? this.tracks.size() - 1 : -1;
    }

    /**
     * 批量加入曲目：数量上限、编码后的网络包大小都在这里统一限制；
     * 只同步一次、只刷新一次音频引用，避免一次预检刷出大量方块更新。 喵
     */
    public int addTracks(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return 0;
        }
        int max = ExplainstoneConfig.maxPlaylistTracks();
        long encoded = PlaybackRegistry.encodedLength(this.tracks);
        int added = 0;
        for (Track track : tracks) {
            if (track == null || this.tracks.contains(track)) {
                continue;
            }
            if (this.tracks.size() >= max) {
                break;
            }
            long length = PlaybackRegistry.encodedLength(track);
            if (encoded + length > SpeakerPayloads.MAX_PLAYLIST_CHARS) {
                break;   // 再加就超过列表上限了 喵
            }
            this.tracks.add(track);
            encoded += length;
            added++;
        }
        if (added > 0) {
            this.sync();
            this.refreshAudioUsage();
        }
        return added;
    }

    public void removeTrack(int index) {
        if (index >= 0 && index < this.tracks.size()) {
            this.tracks.remove(index);
            if (this.selected >= this.tracks.size()) {
                this.selected = Math.max(0, this.tracks.size() - 1);
            }
            this.sync();
            this.refreshAudioUsage();
        }
    }

    /** 清空整个播放列表 喵 */
    public void clearTracks() {
        if (!this.tracks.isEmpty()) {
            this.tracks.clear();
            this.selected = 0;
            this.sync();
            this.refreshAudioUsage();
        }
    }

    /**
     * 区块加载 / 方块实体进入世界时登记「音频 → 音响」引用。
     * 引用表的其它入口（增删曲目、拆方块）也会调用它。 喵
     */
    @Override
    public void onLoad() {
        this.refreshAudioUsage();
    }

    /** 服务端 ticker 兜底：某些加载路径不会走 onLoad，这里补登记一次 喵 */
    public void ensureAudioUsageRegistered() {
        if (!this.audioUsageRegistered) {
            this.refreshAudioUsage();
        }
    }

    /** 把当前播放列表里的导入音频登记到服务端引用表 喵 */
    private void refreshAudioUsage() {
        if (this.level instanceof ServerLevel serverLevel) {
            this.audioUsageRegistered = true;
            AudioUsageRegistry.update(serverLevel, this.worldPosition, this.tracks);
        }
    }

    public int indexOf(Track track) {
        return this.tracks.indexOf(track);
    }

    // ------------------------------------------------------------ 模式 / 红石 喵
    public PlayMode getMode() {
        return this.mode;
    }

    public void setMode(PlayMode mode) {
        this.mode = mode;
        this.sync();
    }

    public boolean isRespectRedstone() {
        return this.respectRedstone;
    }

    public boolean isLastPowered() {
        return this.lastPowered;
    }

    public int getChannel() {
        return this.channel;
    }

    public void setChannel(int channel) {
        this.channel = Math.floorMod(channel, 1000);
        this.sync();
    }

    public boolean isReceiveMode() {
        return this.receiveMode;
    }

    public void setReceiveMode(boolean receiveMode) {
        this.receiveMode = receiveMode;
        this.sync();
    }

    public boolean isPaused() {
        return this.paused;
    }

    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        this.sync();
    }

    public int getTransmitRange() {
        return this.transmitRange;
    }

    public void setTransmitRange(int range) {
        this.transmitRange = Math.max(32, Math.min(512, range));
        this.sync();
    }

    public int getVolume() {
        return this.volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        this.sync();
    }

    /** 发信器全局音量：乘到所有收信音响自己的音量上 喵 */
    public int getBroadcastVolume() {
        return this.broadcastVolume;
    }

    public void setBroadcastVolume(int volume) {
        this.broadcastVolume = Math.max(0, Math.min(100, volume));
        this.sync();
    }

    /** 实际可闻距离 = 配置的满音量范围 × 当前音量 喵 */
    public double getEffectiveAudibleRange() {
        return ExplainstoneConfig.audibleRangeAtFullVolume() * (this.volume / 100.0);
    }

    /**
     * 收信时的实际音量 = 本机音量 × 发信器全局音量（两者都是 0-100 的百分比）。
     * 发信器把音量调小，收听该频道的所有音响一起变轻 喵
     */
    public int getEffectiveVolume(int broadcastVolumePercent) {
        int broadcast = Math.max(0, Math.min(100, broadcastVolumePercent));
        return (int) Math.round(this.volume * (broadcast / 100.0));
    }

    /** 收信时的实际可闻距离：和实际音量一起按比例缩放 喵 */
    public double getEffectiveAudibleRange(int broadcastVolumePercent) {
        return ExplainstoneConfig.audibleRangeAtFullVolume()
                * (this.getEffectiveVolume(broadcastVolumePercent) / 100.0);
    }

    public int getTimerSeconds() {
        return this.timerSeconds;
    }

    public void setTimerSeconds(int seconds) {
        // 客户端可以传任意 int：限制在 0..24 小时，避免倒计时溢出 喵
        this.timerSeconds = Math.max(0, Math.min(86_400, seconds));
        this.sync();
    }

    /** 发信器开始广播时调用：启动定时倒计时 喵 */
    public void startTimer() {
        this.ticksLeft = (int) Math.min(Integer.MAX_VALUE, (long) this.timerSeconds * 20L);
    }

    public void stopTimer() {
        this.ticksLeft = 0;
    }

    public boolean hasTimerExpired() {
        return this.timerSeconds > 0 && this.ticksLeft <= 0;
    }

    public boolean tickTimer() {
        if (this.timerSeconds > 0) {
            if (this.ticksLeft > 0) {
                this.ticksLeft--;
            }
            return this.ticksLeft > 0;
        }
        return true;
    }

    /** 记录红石状态（不触发客户端同步，避免邻居更新时刷包） 喵 */
    public void setLastPowered(boolean powered) {
        if (this.lastPowered != powered) {
            this.lastPowered = powered;
            this.setChanged();
        }
    }

    public void setRespectRedstone(boolean respectRedstone) {
        this.respectRedstone = respectRedstone;
        this.sync();
    }

    /** 把改动同步到客户端（界面显示 + 音效引擎都要用） 喵 */
    public void sync() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------ NBT
    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        ListTag list = new ListTag();
        for (Track track : this.tracks) {
            list.add(track.toNbt());
        }
        nbt.put("tracks", list);
        nbt.putInt("selected", this.selected);
        nbt.putString("mode", this.mode.getSerializedName());
        nbt.putBoolean("respectRedstone", this.respectRedstone);
        nbt.putBoolean("lastPowered", this.lastPowered);
        nbt.putInt("channel", this.channel);
        nbt.putBoolean("receiveMode", this.receiveMode);
        nbt.putBoolean("paused", this.paused);
        nbt.putInt("timerSeconds", this.timerSeconds);
        nbt.putInt("ticksLeft", this.ticksLeft);
        nbt.putInt("transmitRange", this.transmitRange);
        nbt.putInt("volume", this.volume);
        nbt.putInt("broadcastVolume", this.broadcastVolume);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        this.tracks.clear();
        ListTag list = nbt.getList("tracks", Tag.TAG_COMPOUND);
        int limit = Math.max(1, ExplainstoneConfig.maxPlaylistTracks());
        for (int i = 0; i < list.size() && this.tracks.size() < limit; i++) {
            Track track = Track.fromNbt(list.getCompound(i));
            // 只接受能解析的曲目；file: 必须是合法 SHA-1（防止路径穿越 / 畸形存档） 喵
            if (!resolvable(track)) {
                continue;
            }
            this.tracks.add(sanitizeLoaded(track));
        }
        trimTracksToPacketBudget();
        this.selected = nbt.getInt("selected");
        for (PlayMode value : PlayMode.values()) {
            if (value.getSerializedName().equals(nbt.getString("mode"))) {
                this.mode = value;
            }
        }
        if (nbt.contains("respectRedstone")) {
            this.respectRedstone = nbt.getBoolean("respectRedstone");
        }
        if (nbt.contains("lastPowered")) {
            this.lastPowered = nbt.getBoolean("lastPowered");
        }
        if (nbt.contains("channel")) {
            this.channel = Math.floorMod(nbt.getInt("channel"), 1000);
        }
        if (nbt.contains("receiveMode")) {
            this.receiveMode = nbt.getBoolean("receiveMode");
        }
        if (nbt.contains("paused")) {
            this.paused = nbt.getBoolean("paused");
        }
        if (nbt.contains("timerSeconds")) {
            this.timerSeconds = Math.max(0, Math.min(86_400, nbt.getInt("timerSeconds")));
        }
        if (nbt.contains("ticksLeft")) {
            // 暂停/重进存档后定时器接着剩余时间走，不要重新计时 喵
            this.ticksLeft = Math.max(0, Math.min(1_728_000, nbt.getInt("ticksLeft")));
        }
        if (nbt.contains("transmitRange")) {
            this.transmitRange = Math.max(32, Math.min(512, nbt.getInt("transmitRange")));
        }
        if (nbt.contains("volume")) {
            this.volume = Math.max(0, Math.min(100, nbt.getInt("volume")));
        }
        if (nbt.contains("broadcastVolume")) {
            this.broadcastVolume = Math.max(0, Math.min(100, nbt.getInt("broadcastVolume")));
        }
    }

    /** 老存档 / 被 /data 改过的 label 可能超长或带格式符，清洗后再进列表（同时限制同步包大小） 喵 */
    private static Track sanitizeLoaded(Track track) {
        String label = SpeakerNetworking.sanitizeLabel(track.label());
        return label.equals(track.label()) ? track : new Track(label, track.id());
    }

    /** 保证整张列表编码后不超过上限（分片发送，已不再受单帧大小限制） 喵 */
    private void trimTracksToPacketBudget() {
        long encoded = PlaybackRegistry.encodedLength(this.tracks);
        while (encoded > SpeakerPayloads.MAX_PLAYLIST_CHARS && !this.tracks.isEmpty()) {
            Track removed = this.tracks.remove(this.tracks.size() - 1);
            encoded -= PlaybackRegistry.encodedLength(removed);
        }
    }

    /** 这条曲目还能不能放：导入曲目看文件，内置曲目看 jar 里有没有对应 ogg 喵 */
    private static boolean resolvable(Track track) {
        if (track == null || track.id() == null) {
            return false;
        }
        if (track.isFile()) {
            // file:<sha1> 的 sha1 会直接当文件名用，必须是 40 位小写十六进制 喵
            return SpeakerNetworking.isValidSha1(track.sha1());
        }
        String name = track.builtinName();
        if (!name.matches("[a-z0-9_\\-]+")) {
            return false;
        }
        return SpeakerBlockEntity.class.getResource("/assets/explainstone/sounds/" + name + ".ogg") != null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
