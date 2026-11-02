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
package com.ginka.explainstone.client;

import com.ginka.explainstone.BroadcastTransmitterBlock;
import com.ginka.explainstone.ExplainstoneConfig;
import com.ginka.explainstone.SongmelynBlock;
import com.ginka.explainstone.SpeakerBlock;
import com.ginka.explainstone.client.audio.AudioSources;
import com.ginka.explainstone.client.audio.SpeakerAudio;
import com.ginka.explainstone.client.audio.SpeakerUploader;
import com.ginka.explainstone.client.dialog.OggFilePicker;
import com.ginka.explainstone.client.widget.IntSlider;
import com.ginka.explainstone.speaker.SpeakerBlockEntity;
import com.ginka.explainstone.speaker.SpeakerNetworking;
import com.ginka.explainstone.speaker.SpeakerPayloads;
import com.ginka.explainstone.speaker.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.List;

/**
 * 讲解石 / 颂美铃 / 广播发信器 的交互界面。
 * 布局按屏幕高度自适应（从底部往上排），小窗口/大 GUI 缩放时不会溢出。 喵
 */
public class SpeakerScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int ROW_H = 12;
    private static final int BTN_H = 20;
    private static final int GAP = 24;
    private static final String CHANNEL_PREFIX = "350.";
    private static final int DEFAULT_CHANNEL = 234;

    private final BlockPos pos;
    private int scroll;
    private boolean updatingField;
    /** 系统文件框/游戏内选择器正开着，避免重复点导入叠出好几个窗口 喵 */
    private boolean pickerOpen;

    // 自适应布局（init() 里算好） 喵
    private int panelW;
    private int left;
    private int titleY;
    private int listTop;
    private int listRows;
    private int nowPlayingY;
    private int progressBarTop;
    private int progressBarBottom;
    private int row1;
    private int row2;
    private int row3;
    private int row4;
    private int channelY;
    private int sliderY;
    /** 最底下一行滑块（发信器：全局音量；音响：本机音量）的 y 坐标 喵 */
    private int volumeSliderY;

    private Button tabSelf;
    private Button tabReceive;
    private Button playButton;
    private Button stopButton;
    private Button modeButton;
    private Button redstoneButton;
    private Button playModeButton;
    private Button importButton;
    private Button removeButton;
    private Button deleteAllButton;
    private Button confirmButton;
    private Button resetButton;
    private EditBox channelField;
    private IntSlider rangeSlider;
    private IntSlider volumeSlider;
    private int pendingVolumeValue = -1;
    private long pendingVolumeAt;
    /** 已经发出、等待服务端确认的音量；确认前不要把滑块拉回旧值（会闪烁） 喵 */
    private int awaitingVolumeValue = -1;
    private long awaitingVolumeUntil;

    private int pendingRangeValue = -1;
    private long pendingRangeAt;
    private int awaitingRangeValue = -1;
    private long awaitingRangeUntil;

    public SpeakerScreen(BlockPos pos) {
        super(Component.translatable("screen.explainstone.title.speaker"));
        this.pos = pos;
    }

    /** 当前方块的名字翻译键 喵 */
    private String blockNameKey() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "screen.explainstone.title.speaker";
        }
        net.minecraft.world.level.block.Block block = client.level.getBlockState(this.pos).getBlock();
        if (block instanceof BroadcastTransmitterBlock) {
            return "screen.explainstone.title.transmitter";
        }
        if (block instanceof SongmelynBlock) {
            return "screen.explainstone.title.songmelyn";
        }
        return "screen.explainstone.title.speaker";
    }

    private Component blockName() {
        return Component.translatable(this.blockNameKey());
    }

    private boolean isTransmitter() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null
                && client.level.getBlockState(this.pos).getBlock() instanceof BroadcastTransmitterBlock;
    }

    /** 同一个按钮：正在播放时是“暂停”，暂停中/已停止时是“播放” 喵 */
    private void onPlayPauseClicked() {
        SpeakerBlockEntity be = this.speaker();
        boolean active = be != null && !be.isPaused() && this.isBlockPlaying(be);
        send(active ? SpeakerPayloads.ACTION_PAUSE : SpeakerPayloads.ACTION_PLAY, 0);
    }

    /** 方块状态是否处于“正在播放”（暂停时服务端会把 PLAYING 置 false） 喵 */
    private boolean isBlockPlaying(SpeakerBlockEntity be) {
        BlockState state = be.getBlockState();
        if (state.getBlock() instanceof BroadcastTransmitterBlock) {
            return state.getValue(BroadcastTransmitterBlock.PLAYING);
        }
        if (state.getBlock() instanceof SpeakerBlock) {
            return state.getValue(SpeakerBlock.PLAYING);
        }
        return false;
    }

    @Override
    public Component getTitle() {
        return this.blockName();
    }

    private SpeakerBlockEntity speaker() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        return client.level.getBlockEntity(this.pos) instanceof SpeakerBlockEntity be ? be : null;
    }

    @Override
    protected void init() {
        // 界面重建（改窗口大小）时旧滑块引用要清掉，下面按方块类型重新创建 喵
        this.rangeSlider = null;
        this.volumeSlider = null;
        this.panelW = Math.min(PANEL_W, Math.max(220, this.width - 16));
        this.left = (this.width - this.panelW) / 2;
        int half = this.panelW / 2 - 2;
        boolean transmitter = this.isTransmitter();

        // ---- 从底部往上排，保证永远放得下（窗口很矮时自动压缩行距）---- 喵
        int gap = this.height < 260 ? 20 : GAP;
        int bottom = this.height - 6;
        // 发信器有两个滑块（信号范围 + 全局音量），音响/颂美铃只有一个本机音量滑块 喵
        this.volumeSliderY = bottom - BTN_H;
        this.sliderY = transmitter ? this.volumeSliderY - BTN_H - 4 : this.volumeSliderY;
        this.channelY = this.sliderY - gap;
        this.row4 = this.channelY - gap;
        this.row3 = this.row4 - gap;
        this.row2 = this.row3 - gap;
        this.row1 = this.row2 - gap;
        // “正在播放 / 上传状态 / 列表提示”共用进度条上方的一行，列表一直排到这一行之前 喵
        this.nowPlayingY = this.row1 - 30;
        this.progressBarTop = this.row1 - 16;
        this.progressBarBottom = this.row1 - 6;

        // ---- 顶部固定 ---- 喵
        this.titleY = 8;
        int tabY = this.titleY + 14;
        // 发信器没有顶部标签页，列表直接从标题下面开始；讲解石/颂美铃要跳过标签页那一行 喵
        this.listTop = transmitter ? tabY : tabY + 18 + 2;
        this.listRows = Math.max(1, (this.nowPlayingY - 2 - this.listTop) / ROW_H);

        // 模式标签页 喵
        this.tabSelf = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.tab.self"), b -> {
            SpeakerBlockEntity be = this.speaker();
            if (be != null && be.isReceiveMode()) {
                send(SpeakerPayloads.ACTION_TOGGLE_RECEIVE, 0);
            }
            this.refreshLabels();
        }).bounds(this.left, tabY, half, 18).build());
        this.tabReceive = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.tab.receive"), b -> {
            SpeakerBlockEntity be = this.speaker();
            if (be != null && !be.isReceiveMode()) {
                send(SpeakerPayloads.ACTION_TOGGLE_RECEIVE, 0);
            }
            this.refreshLabels();
        }).bounds(this.left + half + 4, tabY, half, 18).build());
        // 发信器没有这两个标签页：初始化时就隐藏，别让它们先闪一帧盖住列表 喵
        this.tabSelf.visible = !transmitter;
        this.tabReceive.visible = !transmitter;

        // 播放 / 暂停（同一个按钮切换）+ 停止 喵
        this.playButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.play"),
                        b -> this.onPlayPauseClicked())
                .bounds(this.left, this.row1, half, BTN_H).build());
        this.stopButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.stop"),
                        b -> send(SpeakerPayloads.ACTION_STOP, 0))
                .bounds(this.left + half + 4, this.row1, half, BTN_H).build());

        // 定时（只有发信器用；讲解石/颂美铃用顶部标签页切换自播放/收信，不再需要底部收信开关） 喵
        this.modeButton = transmitter
                ? this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.timer.off"), b -> {
                    int[] steps = {0, 60, 180, 600};
                    SpeakerBlockEntity be = this.speaker();
                    int current = be == null ? 0 : be.getTimerSeconds();
                    int next = steps[0];
                    for (int i = 0; i < steps.length; i++) {
                        if (steps[i] == current) {
                            next = steps[(i + 1) % steps.length];
                            break;
                        }
                    }
                    send(SpeakerPayloads.ACTION_SET_TIMER, next);
                    this.refreshLabels();
                }).bounds(this.left, this.row2, half, BTN_H).build())
                : null;

        // 讲解石没有收信开关了，红石按钮独占整行；发信器仍和定时按钮分左右 喵
        this.redstoneButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.redstone"), b -> {
            send(SpeakerPayloads.ACTION_TOGGLE_REDSTONE, 0);
            this.refreshLabels();
        }).bounds(transmitter ? this.left + half + 4 : this.left, this.row2,
                transmitter ? half : this.panelW, BTN_H).build());

        // 播放模式（放音开关已移除：把音量调到 0 就是静音） 喵
        this.playModeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.mode"), b -> {
            send(SpeakerPayloads.ACTION_CYCLE_MODE, 0);
            this.refreshLabels();
        }).bounds(this.left, this.row3, this.panelW, BTN_H).build());

        // 导入 / 删除选中 / 删除全部 喵
        int third = Math.max(60, (this.panelW - 8) / 3);
        this.importButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.import"),
                        b -> this.onImportClicked())
                .bounds(this.left, this.row4, third, BTN_H).build());
        this.removeButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.remove"), b -> {
            SpeakerBlockEntity be = this.speaker();
            if (be != null && be.getSelected() >= 0) {
                SpeakerUploader.cancel(this.pos);
                send(SpeakerPayloads.ACTION_REMOVE_TRACK, be.getSelected());
            }
        }).bounds(this.left + third + 4, this.row4, third, BTN_H).build());
        this.deleteAllButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.remove_all"), b -> {
            SpeakerBlockEntity be = this.speaker();
            SpeakerUploader.cancel(this.pos);
            if (be != null && !be.getTracks().isEmpty()) {
                send(SpeakerPayloads.ACTION_REMOVE_ALL, 0);
            }
        }).bounds(this.left + (third + 4) * 2, this.row4, third, BTN_H).build());

        // 频率：350.xxx 喵
        SpeakerBlockEntity be = this.speaker();
        int channel = be == null ? DEFAULT_CHANNEL : be.getChannel();
        this.channelField = new EditBox(this.font, this.left + 34, this.channelY, 62, 18,
                Component.translatable("screen.explainstone.label.frequency"));
        this.channelField.setMaxLength(CHANNEL_PREFIX.length() + 3);
        this.channelField.setValue(CHANNEL_PREFIX + String.format("%03d", channel));
        this.channelField.setResponder(text -> {
            if (this.updatingField) {
                return;
            }
            String fixed = sanitizeChannel(text);
            if (!fixed.equals(text)) {
                this.updatingField = true;
                this.channelField.setValue(fixed);
                this.updatingField = false;
            }
        });
        this.addRenderableWidget(this.channelField);

        this.confirmButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.confirm"), b -> {
            int value = parseChannel(this.channelField == null ? "" : this.channelField.getValue());
            this.setChannelField(value);
            send(SpeakerPayloads.ACTION_SET_CHANNEL, value);
        }).bounds(this.left + 102, this.channelY - 1, 56, BTN_H).build());

        this.resetButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.button.reset"), b -> {
            this.setChannelField(DEFAULT_CHANNEL);
            send(SpeakerPayloads.ACTION_SET_CHANNEL, DEFAULT_CHANNEL);
        }).bounds(this.left + 162, this.channelY - 1, 56, BTN_H).build());

        // 滑块：发信器上排是信号范围、下排是所有收信音响的全局音量；
        // 音响/颂美铃只有本机音量（音量同时决定可闻范围，0 即静音） 喵
        if (transmitter) {
            int range = be == null ? 512 : be.getTransmitRange();
            this.rangeSlider = this.addRenderableWidget(new IntSlider(this.left, this.sliderY, this.panelW, BTN_H,
                    Component.translatable("screen.explainstone.slider.range").getString(), 32, 512, range, this::queueRange));
            int broadcastVolume = be == null ? 100 : be.getBroadcastVolume();
            this.volumeSlider = this.addRenderableWidget(new IntSlider(this.left, this.volumeSliderY, this.panelW, BTN_H,
                    Component.translatable("screen.explainstone.slider.broadcast_volume").getString(), 0, 100,
                    broadcastVolume, this::queueVolume));
        } else {
            int volume = be == null ? 100 : be.getVolume();
            this.volumeSlider = this.addRenderableWidget(new IntSlider(this.left, this.volumeSliderY, this.panelW, BTN_H,
                    Component.translatable("screen.explainstone.slider.volume").getString(), 0, 100, volume, this::queueVolume));
        }

        this.refreshLabels();
    }

    private void queueRange(int value) {
        this.pendingRangeValue = value;
        this.pendingRangeAt = System.currentTimeMillis() + 150;
    }

    private void queueVolume(int value) {
        this.pendingVolumeValue = value;
        this.pendingVolumeAt = System.currentTimeMillis() + 150;
    }

    private void flushPendingVolume() {
        if (this.pendingVolumeValue < 0 || System.currentTimeMillis() < this.pendingVolumeAt) {
            return;
        }
        int value = this.pendingVolumeValue;
        this.pendingVolumeValue = -1;
        this.awaitingVolumeValue = value;
        this.awaitingVolumeUntil = System.currentTimeMillis() + 1500L;
        // 发信器的滑块改的是广播全局音量，其它方块改自己的音量 喵
        send(this.isTransmitter()
                ? SpeakerPayloads.ACTION_SET_BROADCAST_VOLUME
                : SpeakerPayloads.ACTION_SET_VOLUME, value);
    }

    private void flushPendingRange() {
        if (this.pendingRangeValue < 0 || System.currentTimeMillis() < this.pendingRangeAt) {
            return;
        }
        int value = this.pendingRangeValue;
        this.pendingRangeValue = -1;
        this.awaitingRangeValue = value;
        this.awaitingRangeUntil = System.currentTimeMillis() + 1500L;
        send(SpeakerPayloads.ACTION_SET_TRANSMIT_RANGE, value);
    }

    /** 强制保留 "350." 前缀，后面最多 3 位数字 喵 */
    private static String sanitizeChannel(String text) {
        String digits;
        if (text.startsWith(CHANNEL_PREFIX)) {
            digits = text.substring(CHANNEL_PREFIX.length());
        } else {
            digits = text.replaceAll("\\D", "");
            if (digits.startsWith("350")) {
                digits = digits.substring(3);
            }
        }
        digits = digits.replaceAll("\\D", "");
        if (digits.length() > 3) {
            digits = digits.substring(0, 3);
        }
        return CHANNEL_PREFIX + digits;
    }

    private static int parseChannel(String text) {
        String digits = text.startsWith(CHANNEL_PREFIX) ? text.substring(CHANNEL_PREFIX.length()) : text;
        digits = digits.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return DEFAULT_CHANNEL;
        }
        return Math.max(0, Math.min(999, Integer.parseInt(digits)));
    }

    private void setChannelField(int channel) {
        if (this.channelField == null) {
            return;
        }
        this.updatingField = true;
        this.channelField.setValue(CHANNEL_PREFIX + String.format("%03d", Math.floorMod(channel, 1000)));
        this.updatingField = false;
    }

    private void refreshLabels() {
        SpeakerBlockEntity be = this.speaker();
        boolean transmitter = this.isTransmitter();
        boolean receive = !transmitter && be != null && be.isReceiveMode();

        if (this.tabSelf != null) {
            this.tabSelf.visible = !transmitter;
            this.tabSelf.active = true;
            this.tabSelf.setMessage(receive
                    ? Component.translatable("screen.explainstone.tab.self")
                    : Component.translatable("screen.explainstone.tab.self.active"));
        }
        if (this.tabReceive != null) {
            this.tabReceive.visible = !transmitter;
            this.tabReceive.active = true;
            this.tabReceive.setMessage(receive
                    ? Component.translatable("screen.explainstone.tab.receive.active")
                    : Component.translatable("screen.explainstone.tab.receive"));
        }
        if (this.modeButton != null) {
            int timer = be == null ? 0 : be.getTimerSeconds();
            this.modeButton.setMessage(timer <= 0
                    ? Component.translatable("screen.explainstone.timer.off")
                    : Component.translatable("screen.explainstone.timer.minutes", timer / 60));
        }
        if (this.redstoneButton != null) {
            boolean respect = be == null || be.isRespectRedstone();
            this.redstoneButton.setMessage(respect
                    ? Component.translatable("screen.explainstone.redstone.detect")
                    : Component.translatable("screen.explainstone.redstone.ignore"));
            this.redstoneButton.visible = !receive;   // 收信模式没有红石 喵
        }
        if (this.playModeButton != null) {
            this.playModeButton.setMessage(Component.translatable("screen.explainstone.mode.label",
                    be == null ? Component.translatable("screen.explainstone.mode.sequence")
                            : Component.translatable("screen.explainstone.mode." + be.getMode().getSerializedName())));
            this.playModeButton.visible = !receive;
        }
        if (this.playButton != null) {
            boolean active = be != null && !be.isPaused() && this.isBlockPlaying(be);
            this.playButton.setMessage(Component.translatable(active
                    ? "screen.explainstone.button.pause"
                    : "screen.explainstone.button.play"));
            this.playButton.visible = !receive;
        }
        if (this.stopButton != null) {
            this.stopButton.visible = !receive;
        }
        if (this.importButton != null) {
            this.importButton.visible = !receive;
        }
        if (this.removeButton != null) {
            this.removeButton.visible = !receive;
        }
        if (this.deleteAllButton != null) {
            // 收信模式下列表是广播内容，不能在这里删本机的曲目 喵
            this.deleteAllButton.visible = !receive;
        }
        boolean showChannel = transmitter || receive;
        if (this.channelField != null) {
            this.channelField.visible = showChannel;
            if (!this.channelField.isFocused()) {
                this.setChannelField(be == null ? DEFAULT_CHANNEL : be.getChannel());
            }
        }
        if (this.confirmButton != null) {
            this.confirmButton.visible = showChannel;
        }
        if (this.resetButton != null) {
            this.resetButton.visible = showChannel;
        }
        this.flushPendingRange();
        this.flushPendingVolume();

        long now = System.currentTimeMillis();
        // 发信器的音量滑块控制的是广播全局音量（乘到每个收信音响上），其它方块是本机音量 喵
        int actualVolume = be == null ? 100 : (transmitter ? be.getBroadcastVolume() : be.getVolume());
        if (this.awaitingVolumeValue >= 0
                && (actualVolume == this.awaitingVolumeValue || now > this.awaitingVolumeUntil)) {
            this.awaitingVolumeValue = -1;
        }
        if (this.volumeSlider != null && this.pendingVolumeValue < 0 && this.awaitingVolumeValue < 0
                && this.volumeSlider.getIntValue() != actualVolume) {
            this.volumeSlider.setIntValue(actualVolume);
        }

        if (this.rangeSlider != null) {
            int actualRange = be == null ? 512 : be.getTransmitRange();
            if (this.awaitingRangeValue >= 0
                    && (actualRange == this.awaitingRangeValue || now > this.awaitingRangeUntil)) {
                this.awaitingRangeValue = -1;
            }
            if (this.pendingRangeValue < 0 && this.awaitingRangeValue < 0
                    && this.rangeSlider.getIntValue() != actualRange) {
                this.rangeSlider.setIntValue(actualRange);
            }
        }
    }

    private void send(int action, int value) {
        if (Minecraft.getInstance().getConnection() != null) {
            SpeakerNetworking.CHANNEL.sendToServer(new SpeakerPayloads.Action(this.pos, action, value));
        }
    }

    // ---------------------------------------------------------------- 渲染 喵
    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);

        SpeakerBlockEntity be = this.speaker();
        boolean transmitter = this.isTransmitter();
        List<Track> tracks = be == null ? List.of() : be.getTracks();
        int selected = be == null ? -1 : be.getSelected();
        boolean playing = be != null && (transmitter
                ? be.getBlockState().getValue(BroadcastTransmitterBlock.PLAYING)
                : be.getBlockState().getValue(SpeakerBlock.PLAYING));
        boolean receive = !transmitter && be != null && be.isReceiveMode();
        boolean useBroadcast = transmitter || receive;
        // 发信器/收信端看的是频道广播是否暂停；本机播放看自己的会话/方块暂停标志 喵
        boolean paused = be != null && (useBroadcast
                ? (be.isPaused() || SpeakerAudio.broadcastPaused(be.getChannel()))
                : (be.isPaused() || SpeakerAudio.sessionPaused(this.pos)));

        Component title = this.blockName();
        if (paused) {
            title = title.copy().append(Component.translatable("screen.explainstone.paused_state"));
        } else if (playing) {
            title = title.copy().append(Component.translatable(
                    transmitter ? "screen.explainstone.sending" : "screen.explainstone.playing"));
        }
        context.drawCenteredString(this.font, title, this.width / 2, this.titleY, 0xFFFFFF);

        float[] progress = useBroadcast
                ? SpeakerAudio.broadcastProgress(be == null ? 0 : be.getChannel())
                : SpeakerAudio.progress(this.pos);
        SpeakerUploader.Status upload = SpeakerUploader.status(this.pos);
        // 只有真的需要显示“正在播放/已暂停”或上传状态时才给那一行留位置；
        // 没有进度和上传状态时，列表一直用到播放/停止按钮上方，别让进度条那块区域空着 喵
        int listBottomLimit = (progress != null || upload != null)
                ? this.nowPlayingY - 2
                : this.row1 - 2;
        this.listRows = Math.max(1, (listBottomLimit - this.listTop) / ROW_H);

        if (receive) {
            context.drawCenteredString(this.font,
                    Component.translatable("screen.explainstone.receive.title"),
                    this.width / 2, this.listTop + 10, 0x9BE8C8);
            context.drawCenteredString(this.font,
                    Component.translatable("screen.explainstone.receive.hint"),
                    this.width / 2, this.listTop + 26, 0x909090);
        } else {
            int maxScroll = Math.max(0, tracks.size() - this.listRows);
            this.scroll = Math.min(this.scroll, maxScroll);
            if (tracks.isEmpty()) {
                context.drawString(this.font, Component.translatable("screen.explainstone.list.empty"),
                        this.left + 8, this.listTop + 2, 0xA0A0A0, true);
            }
            for (int row = 0; row < this.listRows; row++) {
                int index = this.scroll + row;
                if (index >= tracks.size()) {
                    break;
                }
                Track track = tracks.get(index);
                int y = this.listTop + row * ROW_H;
                if (index == selected) {
                    context.fill(this.left, y - 1, this.left + this.panelW, y + ROW_H - 2, 0x602E6BE6);
                }
                String prefix = (index + 1) + ". " + (index == selected ? "▶ " : "");
                String suffix = "";
                if (track.isFile() && !AudioSources.hasLocal(track)) {
                    suffix = "  " + Component.translatable("screen.explainstone.list.downloading").getString();
                }
                String length = formatTime(AudioSources.durationSeconds(track));
                int reserved = length == null ? 4 : this.font.width(length) + 6;
                String name = ellipsize(prefix + track.label() + suffix, this.panelW - 8 - reserved);
                context.drawString(this.font, name, this.left + 4, y, 0xFFFFFF, true);
                if (length != null) {
                    context.drawString(this.font, length,
                            this.left + this.panelW - 4 - this.font.width(length), y, 0xB0C8FF, true);
                }
            }
        }

        String uploadStatus = null;
        if (upload != null) {
            if (upload.phase() == SpeakerUploader.Phase.CHECKING) {
                uploadStatus = Component.translatable("screen.explainstone.upload.checking").getString();
            } else {
                String key = upload.phase() == SpeakerUploader.Phase.UPLOADING
                        ? "screen.explainstone.upload.uploading"
                        : "screen.explainstone.upload.processing";
                uploadStatus = Component.translatable(key, upload.percent()).getString();
            }
        }

        String time = progress == null ? null
                : timeOrZero((int) progress[0]) + " / " + timeOrZero((int) progress[1]);
        int timeWidth = time == null ? 0 : this.font.width(time);
        // 上传状态、“正在播放/已暂停”、列表提示共用一行，列表可以向下多排几行 喵
        if (uploadStatus != null) {
            int available = this.panelW - 8 - (time == null ? 0 : timeWidth + 6);
            context.drawString(this.font, ellipsize(uploadStatus, available),
                    this.left + 4, this.nowPlayingY, 0xFFD37A, true);
        } else if (progress != null) {
            String label = useBroadcast
                    ? SpeakerAudio.broadcastTrackLabel(be == null ? 0 : be.getChannel())
                    : SpeakerAudio.currentTrackLabel(this.pos);
            if (label != null) {
                String nowPlaying = Component.translatable(paused
                        ? "screen.explainstone.now_playing_paused"
                        : "screen.explainstone.now_playing", label).getString();
                int available = this.panelW - 8 - timeWidth;
                context.drawString(this.font, ellipsize(nowPlaying, available),
                        this.left + 4, this.nowPlayingY, 0xFFFFFF, true);
            }
        }

        if (progress != null) {
            context.drawString(this.font, time,
                    this.left + this.panelW - 4 - timeWidth, this.nowPlayingY, 0xB0C8FF, true);
            context.fill(this.left, this.progressBarTop, this.left + this.panelW,
                    this.progressBarBottom, 0x60FFFFFF);
            int filled = Math.round(this.panelW * Math.max(0f, Math.min(1f, progress[0] / progress[1])));
            context.fill(this.left, this.progressBarTop, this.left + filled,
                    this.progressBarBottom, 0xC06BCB77);
        }

        if (transmitter || receive) {
            context.drawString(this.font, Component.translatable("screen.explainstone.label.frequency"),
                    this.left + 6, this.channelY + 5, 0xFFFFFF, true);
        }

        this.refreshLabels();
    }

    private static String timeOrZero(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** 秒 → m:ss；拿不到时长返回 null 喵 */
    private static String formatTime(int seconds) {
        if (seconds <= 0) {
            return null;
        }
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    /** 按像素宽度截断文本，超长时补省略号，避免和右侧时长叠在一起 喵 */
    private String ellipsize(String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0) {
            return "...";
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String dots = "...";
        int dotWidth = this.font.width(dots);
        if (maxWidth <= dotWidth) {
            return dots;
        }
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end)) + dotWidth > maxWidth) {
            end--;
        }
        return text.substring(0, end) + dots;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        SpeakerBlockEntity be = this.speaker();
        boolean receive = !this.isTransmitter() && be != null && be.isReceiveMode();
        if (!receive && button == 0 && mouseX >= this.left && mouseX <= this.left + this.panelW
                && mouseY >= this.listTop - 1 && mouseY < this.listTop + this.listRows * ROW_H) {
            int index = this.scroll + (int) ((mouseY - this.listTop + 1) / ROW_H);
            if (be != null && index >= 0 && index < be.getTracks().size()) {
                send(SpeakerPayloads.ACTION_SELECT, index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        SpeakerBlockEntity be = this.speaker();
        int maxScroll = be == null ? 0 : Math.max(0, be.getTracks().size() - this.listRows);
        this.scroll = Math.max(0, Math.min(maxScroll, this.scroll - (int) Math.signum(delta)));
        return true;
    }

    /**
     * 导入按钮：
     * 默认开系统文件框（Linux 上优先 zenity/kdialog，其次 AWT），开不出来就切到游戏内文件浏览器；
     * 配置项 filePicker = in-game 时直接用游戏内浏览器（方便测试） 喵
     */
    private void onImportClicked() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        // 上次浏览过的目录优先；第一次打开时交给对话框/浏览器自己去用户主目录 喵
        Path startDir = OggFilePicker.lastDirOr(null);
        if (ExplainstoneConfig.useInGameFilePicker()) {
            this.openInGamePicker(client, startDir);
            return;
        }
        if (this.pickerOpen) {
            // 已经有一个系统选择框开着，别叠第二个 喵
            return;
        }
        this.pickerOpen = true;
        String title = Component.translatable("screen.explainstone.picker.title").getString();
        Thread thread = new Thread(() -> {
            OggFilePicker.Result result = OggFilePicker.pick(startDir, title);
            client.execute(() -> {
                this.pickerOpen = false;
                this.handlePickResult(client, startDir, result);
            });
        }, "explainstone-file-dialog");
        thread.setDaemon(true);
        thread.start();
    }

    private void handlePickResult(Minecraft client, Path startDir, OggFilePicker.Result result) {
        switch (result.outcome()) {
            case SELECTED -> {
                OggFilePicker.remember(result.files().get(0));
                SpeakerUploader.start(client, this.pos, result.files());
            }
            case UNAVAILABLE -> {
                // 系统框整个不可用（精简 JRE、headless、Flatpak 沙箱……）：改用游戏内浏览器 喵
                info(client, Component.translatable("message.explainstone.import.dialog_failed"));
                this.openInGamePicker(client, startDir);
            }
            // 用户自己取消：什么都不做 喵
            case CANCELLED -> {
            }
        }
    }

    /** 用游戏自己画的界面挑文件（系统文件框不可用、或者配置指定用它时） 喵 */
    private void openInGamePicker(Minecraft client, Path startDir) {
        if (client.level == null) {
            return;
        }
        client.setScreen(new OggPickerScreen(this, startDir, paths -> {
            if (!paths.isEmpty()) {
                OggFilePicker.remember(paths.get(0));
                SpeakerUploader.start(client, this.pos, paths);
            }
        }));
    }

    private void info(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.translatable("message.explainstone.prefix", this.blockName(), text), false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}