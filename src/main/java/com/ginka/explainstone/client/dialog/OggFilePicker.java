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
package com.ginka.explainstone.client.dialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 系统文件选择框（挑 .ogg 用）。
 *
 * <p>只靠 AWT 在 Linux 上很容易整个用不了：精简版 JRE 没有 libawt_xawt.so、
 * 启动器加了 -Djava.awt.headless=true、Flatpak/沙箱里没有 X11 权限，
 * 或者 AWT 对话框被无边框全屏的游戏窗口盖在下面 —— 任何一种都会让"点导入"看起来毫无反应。
 * 所以这里按下面的顺序尝试，全部失败时返回 {@link Outcome#UNAVAILABLE}，
 * 由界面退回游戏内文件浏览器（{@code OggPickerScreen}），保证任何平台都能导入。 喵
 *
 * <ol>
 *   <li>Linux：zenity / qarma / kdialog 等原生对话框（尊重桌面 portal，永远在最前面）；</li>
 *   <li>AWT {@code FileDialog}（各平台原生外观，强制置顶）；</li>
 *   <li>Swing {@code JFileChooser}（最后兜底，Java 自己画的窗口）。</li>
 * </ol>
 *
 * <p>{@link #pick(Path, String)} 会阻塞到用户关掉对话框，只能在后台线程调用。 喵
 */
public final class OggFilePicker {
    private static final Logger LOGGER = LoggerFactory.getLogger("explainstone-picker");
    /** 用户可能盯着对话框发呆，超时给得很宽松；真的卡死也不会把后台线程永远占住 喵 */
    private static final long TIMEOUT_SECONDS = 600L;
    /** zenity 支持自定义分隔符，用一个正常文件名里不会出现的控制字符 喵 */
    private static final String ZENITY_SEPARATOR = "\u001f";
    private static final Pattern ZENITY_SPLIT = Pattern.compile(Pattern.quote(ZENITY_SEPARATOR));
    /** 万一某个版本不认 --separator，还能按换行/竖线再切一次 喵 */
    private static final Pattern FALLBACK_SPLIT = Pattern.compile("[\r\n|]+");
    private static final Pattern LINE_SPLIT = Pattern.compile("\r?\n");
    /** PATH 里有没有某个可执行文件的缓存，免得每次点导入都扫一遍 PATH 喵 */
    private static final Map<String, Boolean> COMMAND_CACHE = new HashMap<>();

    private OggFilePicker() {
    }

    public enum Outcome {
        /** 用户选了文件 喵 */
        SELECTED,
        /** 用户主动取消（或者关掉了对话框） 喵 */
        CANCELLED,
        /** 这台机器上没有可用的系统文件框，调用方应该改用游戏内选择器 喵 */
        UNAVAILABLE
    }

    public record Result(Outcome outcome, List<Path> files) {
        private static final Result CANCELLED = new Result(Outcome.CANCELLED, List.of());
        private static final Result UNAVAILABLE = new Result(Outcome.UNAVAILABLE, List.of());

        static Result selected(List<Path> files) {
            return new Result(Outcome.SELECTED, List.copyOf(files));
        }
    }

    /**
     * 打开系统文件选择框并等用户操作完。
     *
     * @param startDir    对话框默认打开的目录（不存在时退回用户主目录） 喵
     * @param dialogTitle 对话框标题，由调用方在主线程取好本地化文本 喵
     * @return 结果；{@link Outcome#UNAVAILABLE} 表示系统框一个都用不了 喵
     */
    public static Result pick(Path startDir, String dialogTitle) {
        Path dir = sanitizeDir(startDir);
        if (isLinux()) {
            Result nativeResult = pickWithLinuxDialog(dir, dialogTitle);
            if (nativeResult != null) {
                return nativeResult;
            }
        }
        Result awtResult = pickWithAwt(dir, dialogTitle);
        if (awtResult != null) {
            return awtResult;
        }
        LOGGER.warn("没有可用的系统文件框（headless={}, java.awt.headless={}, os={}），改用游戏内文件浏览器",
                isHeadless(), System.getProperty("java.awt.headless"), System.getProperty("os.name"));
        return Result.UNAVAILABLE;
    }

    // ------------------------------------------------------------ Linux 原生对话框 喵

    /**
     * 依次试可用的原生对话框：KDE 会话优先 kdialog，其它桌面优先 zenity/qarma。
     *
     * @return null 表示这些程序都不存在（继续试 AWT）；否则就是用户的操作结果 喵
     */
    private static Result pickWithLinuxDialog(Path dir, String title) {
        List<Supplier<Result>> candidates = new ArrayList<>();
        // 加候选时把“程序存在”的判断也一起做掉，避免后面重复扫 PATH 喵
        Supplier<Result> zenity = hasCommand("zenity")
                ? () -> runNative(zenityCommand("zenity", dir, title), ZENITY_SPLIT, dir) : null;
        Supplier<Result> qarma = hasCommand("qarma")
                ? () -> runNative(zenityCommand("qarma", dir, title), ZENITY_SPLIT, dir) : null;
        Supplier<Result> kdialog = hasCommand("kdialog")
                ? () -> runNative(kdialogCommand(dir, title), LINE_SPLIT, dir) : null;
        if (isKdeSession()) {
            addCandidate(candidates, kdialog);
            addCandidate(candidates, zenity);
            addCandidate(candidates, qarma);
        } else {
            addCandidate(candidates, zenity);
            addCandidate(candidates, qarma);
            addCandidate(candidates, kdialog);
        }
        if (candidates.isEmpty()) {
            LOGGER.info("系统里没有 zenity/qarma/kdialog，改用 AWT 文件框");
            return null;
        }
        for (Supplier<Result> candidate : candidates) {
            Result result = candidate.get();
            if (result != null) {
                // null 表示这个对话框起不来（没有显示服务、缺库……），换下一个 喵
                return result;
            }
        }
        return null;
    }

    private static void addCandidate(List<Supplier<Result>> candidates, Supplier<Result> candidate) {
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static boolean isKdeSession() {
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        String session = System.getenv("KDE_FULL_SESSION");
        String both = (desktop == null ? "" : desktop) + " " + (session == null ? "" : session);
        return both.toLowerCase(Locale.ROOT).contains("kde");
    }

    private static List<String> zenityCommand(String binary, Path dir, String title) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("--file-selection");
        command.add("--multiple");
        command.add("--title=" + title);
        command.add("--separator=" + ZENITY_SEPARATOR);
        command.add("--file-filter=Ogg (*.ogg) | *.ogg");
        // 结尾的分隔符让 zenity 打开这个目录本身，而不是选中它 喵
        command.add("--filename=" + dir.toAbsolutePath() + File.separator);
        return command;
    }

    private static List<String> kdialogCommand(Path dir, String title) {
        List<String> command = new ArrayList<>();
        command.add("kdialog");
        command.add("--title");
        command.add(title);
        command.add("--getopenfilename");
        command.add(dir.toAbsolutePath().toString());
        command.add("*.ogg|Ogg audio");
        command.add("--multiple");
        command.add("--separate-output");
        return command;
    }

    /**
     * 跑一个原生对话框程序。
     *
     * @return null 表示这个程序起不来（缺库、没有显示服务……），应该换下一个候选 喵
     */
    private static Result runNative(List<String> command, Pattern separator, Path baseDir) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread outReader = drain(process.getInputStream(), out);
            Thread errReader = drain(process.getErrorStream(), err);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOGGER.warn("{} 超过 {} 秒没有关掉，放弃这次选择", command.get(0), TIMEOUT_SECONDS);
                return Result.CANCELLED;
            }
            outReader.join(2000L);
            errReader.join(2000L);
            int exit = process.exitValue();
            if (exit != 0) {
                String message = err.toString().strip();
                if (message.isEmpty()) {
                    // 原生对话框点取消就是退出码 1、什么都不打印 喵
                    return Result.CANCELLED;
                }
                LOGGER.info("{} 用不了：{}", command.get(0), message);
                return null;
            }
            List<Path> files = parseSelection(out.toString(), separator, baseDir);
            return files.isEmpty() ? Result.CANCELLED : Result.selected(files);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
            return Result.CANCELLED;
        } catch (IOException e) {
            destroy(process);
            LOGGER.info("启动 {} 失败：{}", command.get(0), e.toString());
            return null;
        }
    }

    private static Thread drain(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            char[] buffer = new char[4096];
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    sink.append(buffer, 0, read);
                }
            } catch (IOException ignored) {
            }
        }, "explainstone-picker-io");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void destroy(Process process) {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    /** 把对话框输出拆成路径列表，顺便过滤掉非 .ogg 和不存在的文件 喵 */
    private static List<Path> parseSelection(String text, Pattern separator, Path baseDir) {
        if (text.isBlank()) {
            return List.of();
        }
        List<Path> files = collectPaths(separator.split(text), baseDir);
        if (files.isEmpty()) {
            // 有的对话框版本不认自定义分隔符，退回按换行/竖线再切一次 喵
            files = collectPaths(FALLBACK_SPLIT.split(text), baseDir);
        }
        return files;
    }

    private static List<Path> collectPaths(String[] pieces, Path baseDir) {
        List<Path> files = new ArrayList<>();
        for (String piece : pieces) {
            String value = piece.strip();
            if (value.isEmpty() || !isOggName(value)) {
                continue;
            }
            try {
                Path path = Path.of(value);
                if (!path.isAbsolute()) {
                    // kdialog 之类可能回相对路径 喵
                    path = baseDir.resolve(path);
                }
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            } catch (InvalidPathException ignored) {
                // 名字里带奇怪字符，跳过就行 喵
            }
        }
        return files;
    }
    // ------------------------------------------------------------ AWT / Swing 兜底 喵

    /**
     * AWT 的两个候选：原生 FileDialog 优先，JFileChooser 次之。
     *
     * @return null 表示 AWT 整体不可用（headless / 缺 libawt_xawt） 喵
     */
    private static Result pickWithAwt(Path dir, String title) {
        if (isHeadless()) {
            LOGGER.info("java.awt 处于 headless 模式，跳过 AWT 文件框");
            return null;
        }
        Result result = onAwtThread(() -> awtFileDialog(dir, title));
        if (result != null) {
            return result;
        }
        return onAwtThread(() -> swingFileChooser(dir, title));
    }

    /**
     * 在 AWT 事件线程上创建并显示对话框。
     * 之前直接在模组的后台线程里 setVisible(true)，Linux/GTK 下可能根本不出窗口。 喵
     *
     * @return null 表示这次尝试失败，可以再试下一个候选 喵
     */
    private static Result onAwtThread(Supplier<Result> action) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    future.complete(action.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            // HeadlessException / UnsatisfiedLinkError（缺 libawt_xawt.so）/ NoClassDefFoundError（没有 java.desktop）都在这 喵
            LOGGER.warn("AWT 文件框不可用：{}", t.toString());
            return null;
        }
    }

    private static Result awtFileDialog(Path dir, String title) {
        java.awt.FileDialog dialog = new java.awt.FileDialog((java.awt.Frame) null, title,
                java.awt.FileDialog.LOAD);
        try {
            dialog.setDirectory(dir.toAbsolutePath().toString());
            dialog.setFilenameFilter((parent, name) -> isOggName(name));
            dialog.setMultipleMode(true);
            // 无边框全屏的游戏窗口会把 AWT 对话框盖住，强行置顶才看得见 喵
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
            List<Path> files = new ArrayList<>();
            File[] chosen = dialog.getFiles();
            if (chosen != null) {
                for (File file : chosen) {
                    if (file != null && isOggName(file.getName())) {
                        files.add(file.toPath());
                    }
                }
            }
            return files.isEmpty() ? Result.CANCELLED : Result.selected(files);
        } finally {
            dialog.dispose();
        }
    }
    private static Result swingFileChooser(Path dir, String title) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setCurrentDirectory(dir.toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ogg (*.ogg)", "ogg"));
        if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return Result.CANCELLED;
        }
        List<Path> files = new ArrayList<>();
        for (File file : chooser.getSelectedFiles()) {
            if (file != null && isOggName(file.getName())) {
                files.add(file.toPath());
            }
        }
        if (files.isEmpty() && chooser.getSelectedFile() != null
                && isOggName(chooser.getSelectedFile().getName())) {
            files.add(chooser.getSelectedFile().toPath());
        }
        return files.isEmpty() ? Result.CANCELLED : Result.selected(files);
    }

    // ------------------------------------------------------------ 小工具 喵

    /** java.awt 完全不可用时 {@code GraphicsEnvironment} 自己就会抛错，当成 headless 处理 喵 */
    private static boolean isHeadless() {
        try {
            return java.awt.GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("linux");
    }

    private static boolean hasCommand(String name) {
        synchronized (COMMAND_CACHE) {
            Boolean cached = COMMAND_CACHE.get(name);
            if (cached != null) {
                return cached;
            }
        }
        boolean found = false;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isEmpty()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(dir, name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        found = true;
                        break;
                    }
                } catch (InvalidPathException ignored) {
                }
            }
        }
        synchronized (COMMAND_CACHE) {
            COMMAND_CACHE.put(name, found);
        }
        return found;
    }

    /** 起始目录一定要是个存在的文件夹，否则对话框会跑到奇怪的地方 喵 */
    private static Path sanitizeDir(Path startDir) {
        if (startDir != null) {
            try {
                Path dir = startDir.toAbsolutePath();
                if (Files.isDirectory(dir)) {
                    return dir;
                }
            } catch (RuntimeException ignored) {
            }
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            try {
                Path dir = Path.of(home);
                if (Files.isDirectory(dir)) {
                    return dir;
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return Path.of(".").toAbsolutePath();
    }

    public static boolean isOggName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".ogg");
    }
}
