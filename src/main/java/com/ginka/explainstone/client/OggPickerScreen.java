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

import com.ginka.explainstone.client.dialog.OggFilePicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 游戏内文件浏览器：挑本地 .ogg 音频用。
 *
 * <p>系统文件框（{@link OggFilePicker}）在 Linux 上可能整个用不了
 * （headless JRE、缺 libawt_xawt.so、Flatpak 沙箱……），这时改成用游戏自己画的界面浏览文件夹，
 * 不依赖任何系统组件，任何平台都一定能选到文件。 喵
 *
 * <p>点文件夹进入；点 .ogg 打勾/取消打勾（可以翻到别的文件夹继续选）；底部“导入选中”才开始上传。 喵
 */
public class OggPickerScreen extends Screen {
    private static final int ROW_H = 12;
    private static final int BTN_H = 20;
    private static final int PANEL_W = 320;
    /** 记住上次浏览到的目录，下次打开直接从那开始 喵 */
    private static Path lastDir;
    /** 列目录放到后台线程：网络盘/大文件夹不会卡住渲染线程 喵 */
    private static final ExecutorService LISTER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "explainstone-picker-list");
        thread.setDaemon(true);
        return thread;
    });

    private final Screen parent;
    private final Path startDir;
    private final Consumer<List<Path>> onPick;
    /** 已勾选的文件（按勾选顺序），跨目录保留 喵 */
    private final Set<Path> selected = new LinkedHashSet<>();

    private Path dir;
    private List<Entry> entries = List.of();
    private int scroll;
    private boolean loading;
    private String error;
    /** 旧目录的读取结果不许覆盖新目录（快速连点文件夹时会乱序返回） 喵 */
    private int requestId;

    private int panelW;
    private int left;
    private int pathY;
    private int listTop;
    private int listBottom;
    private int hintY;
    private int upY;
    private int importY;
    private Button importButton;

    public OggPickerScreen(Screen parent, Path startDir, Consumer<List<Path>> onPick) {
        super(Component.translatable("screen.explainstone.picker.title"));
        this.parent = parent;
        this.startDir = startDir;
        this.onPick = onPick;
    }

    private record Entry(Path path, String name, boolean directory) {
    }
    @Override
    protected void init() {
        this.panelW = Math.min(PANEL_W, Math.max(200, this.width - 16));
        this.left = (this.width - this.panelW) / 2;
        this.pathY = 20;
        this.importY = this.height - 6 - BTN_H;
        this.upY = this.importY - 4 - BTN_H;
        this.hintY = this.upY - 12;
        this.listTop = this.pathY + 12;
        this.listBottom = Math.max(this.listTop + ROW_H, this.hintY - 2);

        int third = Math.max(50, (this.panelW - 8) / 3);
        this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.picker.up"),
                        b -> this.navigateParent())
                .bounds(this.left, this.upY, third, BTN_H).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.picker.home"),
                        b -> this.navigate(homeDir()))
                .bounds(this.left + third + 4, this.upY, third, BTN_H).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.explainstone.picker.cancel"),
                        b -> this.onClose())
                .bounds(this.left + (third + 4) * 2, this.upY, third, BTN_H).build());
        this.importButton = this.addRenderableWidget(Button.builder(this.importLabel(), b -> this.confirm())
                .bounds(this.left, this.importY, this.panelW, BTN_H).build());

        if (this.dir == null) {
            this.navigate(this.resolveStartDir());
        } else if (!this.loading && this.entries.isEmpty()) {
            // 上次读的是空目录/读失败，重开界面时再读一次 喵
            this.navigate(this.dir);
        }
        this.refreshButtons();
    }

    /** 起始目录：上次浏览过的 → 传进来的（一般是 inbox）→ 用户主目录 喵 */
    private Path resolveStartDir() {
        if (lastDir != null && Files.isDirectory(lastDir)) {
            return lastDir;
        }
        if (this.startDir != null && Files.isDirectory(this.startDir)) {
            return this.startDir;
        }
        return homeDir();
    }

    private static Path homeDir() {
        String home = System.getProperty("user.home");
        if (home != null) {
            try {
                Path path = Path.of(home);
                if (Files.isDirectory(path)) {
                    return path;
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return Path.of(".").toAbsolutePath();
    }

    private void navigateParent() {
        if (this.dir == null) {
            return;
        }
        Path parent = this.dir.getParent();
        if (parent != null) {
            this.navigate(parent);
        }
    }

    /** 切到某个目录，异步列目录 喵 */
    private void navigate(Path target) {
        if (target == null) {
            return;
        }
        Path absolute;
        try {
            absolute = target.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            this.error = e.toString();
            return;
        }
        this.dir = absolute;
        lastDir = absolute;
        this.scroll = 0;
        this.entries = List.of();
        this.error = null;
        this.loading = true;
        int id = ++this.requestId;
        CompletableFuture
                .supplyAsync(() -> listDir(absolute), LISTER)
                .whenComplete((listing, throwable) -> Minecraft.getInstance().execute(() -> {
                    if (id != this.requestId) {
                        return;
                    }
                    this.loading = false;
                    if (throwable != null) {
                        this.entries = List.of();
                        this.error = throwable.toString();
                    } else {
                        this.entries = listing.entries();
                        this.error = listing.error();
                    }
                    this.refreshButtons();
                }));
    }

    /** 列目录的结果：失败时也带一条错误信息，让界面能显示原因 喵 */
    private record Listing(List<Entry> entries, String error) {
    }

    /** 文件夹在前、文件和文件夹各自按名字排；隐藏文件不显示（免得到处是点文件） 喵 */
    private static Listing listDir(Path dir) {
        List<Entry> dirs = new ArrayList<>();
        List<Entry> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (Files.isDirectory(path)) {
                    dirs.add(new Entry(path, name, true));
                } else if (OggFilePicker.isOggName(name) && Files.isRegularFile(path)) {
                    files.add(new Entry(path, name, false));
                }
            }
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            return new Listing(List.of(), message);
        }
        Comparator<Entry> order = Comparator.comparing(entry -> entry.name().toLowerCase(Locale.ROOT));
        dirs.sort(order);
        files.sort(order);
        List<Entry> all = new ArrayList<>(dirs.size() + files.size());
        all.addAll(dirs);
        all.addAll(files);
        return new Listing(List.copyOf(all), null);
    }
    // ---------------------------------------------------------------- 渲染 喵
    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredString(this.font, this.getTitle(), this.width / 2, 6, 0xFFFFFF);
        String path = this.dir == null ? "" : this.dir.toString();
        context.drawString(this.font, ellipsize(path, this.panelW), this.left, this.pathY, 0x9BE8C8, true);

        int rows = this.rows();
        int maxScroll = this.maxScroll();
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
        if (this.loading) {
            context.drawString(this.font, Component.translatable("screen.explainstone.picker.loading"),
                    this.left + 4, this.listTop, 0xA0A0A0, true);
        } else if (this.error != null) {
            context.drawString(this.font, ellipsize(this.error, this.panelW - 8),
                    this.left + 4, this.listTop, 0xFF8080, true);
        } else if (this.entries.isEmpty()) {
            context.drawString(this.font, Component.translatable("screen.explainstone.picker.empty"),
                    this.left + 4, this.listTop, 0xA0A0A0, true);
        }

        for (int row = 0; row < rows; row++) {
            int index = this.scroll + row;
            if (index >= this.entries.size()) {
                break;
            }
            Entry entry = this.entries.get(index);
            int y = this.listTop + row * ROW_H;
            boolean picked = !entry.directory() && this.selected.contains(entry.path());
            boolean hovered = mouseX >= this.left && mouseX <= this.left + this.panelW
                    && mouseY >= y - 1 && mouseY < y + ROW_H - 1;
            if (picked) {
                context.fill(this.left, y - 1, this.left + this.panelW, y + ROW_H - 1, 0x40FFD37A);
            } else if (hovered) {
                context.fill(this.left, y - 1, this.left + this.panelW, y + ROW_H - 1, 0x20FFFFFF);
            }
            String prefix = entry.directory() ? "[+] " : (picked ? "[x] " : "[ ] ");
            context.drawString(this.font, ellipsize(prefix + entry.name(), this.panelW - 8),
                    this.left + 4, y, entry.directory() ? 0xFFD37A : 0xFFFFFF, true);
        }

        // 提示行：有勾选时显示数量，否则显示操作说明 喵
        String hint = this.selected.isEmpty()
                ? Component.translatable("screen.explainstone.picker.hint").getString()
                : Component.translatable("screen.explainstone.picker.selected", this.selected.size()).getString();
        context.drawString(this.font, ellipsize(hint, this.panelW), this.left, this.hintY,
                this.selected.isEmpty() ? 0x909090 : 0xFFD37A, true);
    }

    private int rows() {
        return Math.max(1, (this.listBottom - this.listTop) / ROW_H);
    }

    private int maxScroll() {
        return this.loading ? 0 : Math.max(0, this.entries.size() - this.rows());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= this.left && mouseX <= this.left + this.panelW
                && mouseY >= this.listTop - 1 && mouseY < this.listBottom) {
            int index = this.scroll + (int) ((mouseY - this.listTop + 1) / ROW_H);
            if (index >= 0 && index < this.entries.size()) {
                Entry entry = this.entries.get(index);
                if (entry.directory()) {
                    this.navigate(entry.path());
                } else if (!this.selected.remove(entry.path())) {
                    this.selected.add(entry.path());
                }
                this.refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.scroll = Math.max(0, Math.min(this.maxScroll(),
                this.scroll - (int) Math.signum(delta)));
        return true;
    }

    // ---------------------------------------------------------------- 收尾 喵
    private void confirm() {
        if (this.selected.isEmpty()) {
            return;
        }
        List<Path> files = List.copyOf(this.selected);
        this.onPick.accept(files);
        this.onClose();
    }

    private void refreshButtons() {
        if (this.importButton == null) {
            return;
        }
        this.importButton.setMessage(this.importLabel());
        this.importButton.active = !this.selected.isEmpty();
    }

    private Component importLabel() {
        return Component.translatable("screen.explainstone.picker.import", this.selected.size());
    }

    /** 按像素宽度截断文本，超长时补省略号，长路径才不会跑出面板 喵 */
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
    public boolean isPauseScreen() {
        // 和讲解石界面保持一致：挑歌的时候世界继续跑 喵
        return false;
    }

    @Override
    public void onClose() {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(this.parent);
    }
}
