package com.brisklauncher.mcl.ui;

import com.brisklauncher.mcl.MclContext;
import com.brisklauncher.mcl.game.GameDirectory;
import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.game.mod.ModManager;
import com.brisklauncher.mcl.i18n.I18n;
import com.brisklauncher.mcl.security.SafeLog;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.Window;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拖拽导入：把文件拖到启动器窗口上，按类型选择导入方式。
 *
 * <p>规则（与 PCL/HMCL 一致）：
 * <ul>
 *   <li>{@code .mrpack} → 整合包导入（选目标文件夹 + 自定义版本名）</li>
 *   <li>{@code .jar} → 装进「当前选中实例」的 mods</li>
 *   <li>{@code .zip} → 询问是资源包 / 光影包 / 存档 / 整合包</li>
 *   <li>文件夹 → 存档</li>
 * </ul>
 * 后四类默认装进当前实例，不需要用户再选版本。
 */
public final class DropImporter {

    private DropImporter() {
    }

    /** 把拖拽事件挂到窗口上。 */
    public static void install(javafx.scene.Scene scene, MclContext context) {
        scene.setOnDragOver(event -> {
            Dragboard board = event.getDragboard();
            if (board.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
        });
        scene.setOnDragDropped(event -> {
            Dragboard board = event.getDragboard();
            boolean handled = false;
            if (board.hasFiles()) {
                List<File> files = new ArrayList<>(board.getFiles());
                handled = !files.isEmpty();
                if (handled) {
                    handle(scene.getWindow(), context, files);
                }
            }
            event.setDropCompleted(handled);
            event.consume();
        });
    }

    private static void handle(Window owner, MclContext context, List<File> files) {
        for (File file : files) {
            Path path = file.toPath();
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".mrpack")) {
                importModpack(owner, context, path);
            } else if (name.endsWith(".jar")) {
                copyInto(owner, context, path, ModManager.Kind.MODS);
            } else if (file.isDirectory()) {
                copyInto(owner, context, path, ModManager.Kind.SAVES);
            } else if (name.endsWith(".zip")) {
                askZipKind(owner, context, path);
            } else {
                MCLDialog.info(owner, I18n.t("drop.title"),
                        I18n.t("drop.unsupported", file.getName()));
            }
        }
    }

    /**
     * zip 可能是整合包 / 资源包 / 光影 / 存档，让用户选。
     * 整合包（Modrinth 格式：内含 modrinth.index.json）走导入流程，其余直接复制到实例目录。
     */
    private static void askZipKind(Window owner, MclContext context, Path path) {
        List<String> options = List.of(
                I18n.t("drop.kind.modpack"),
                I18n.t("content.resource_packs"),
                I18n.t("content.shader_packs"),
                I18n.t("content.saves"));
        MCLDialog.choose(owner, I18n.t("drop.title"), I18n.t("drop.choose_kind"), options,
                option -> option,
                option -> {
                    if (option.equals(I18n.t("drop.kind.modpack"))) {
                        importModpack(owner, context, path);
                    } else if (option.equals(I18n.t("content.resource_packs"))) {
                        copyInto(owner, context, path, ModManager.Kind.RESOURCE_PACKS);
                    } else if (option.equals(I18n.t("content.shader_packs"))) {
                        copyInto(owner, context, path, ModManager.Kind.SHADER_PACKS);
                    } else {
                        copyInto(owner, context, path, ModManager.Kind.SAVES);
                    }
                });
    }

    /** 复制/解压到当前实例对应目录。 */
    private static void copyInto(Window owner, MclContext context, Path source, ModManager.Kind kind) {
        GameInstance entry = currentInstance(context);
        if (entry == null) {
            MCLDialog.info(owner, I18n.t("drop.title"), I18n.t("drop.no_instance"));
            return;
        }
        try {
            Path dir = context.mods().ensureDir(entry, kind);
            String baseName = source.getFileName().toString();
            boolean isZip = baseName.toLowerCase(Locale.ROOT).endsWith(".zip");
            Path target;
            if (Files.isDirectory(source)) {
                target = dir.resolve(baseName);
                copyTree(source, target);
            } else if (kind == ModManager.Kind.SAVES && isZip) {
                // 存档永远是资料夹：zip 直接解压成同名目录（PCL/HMCL 也是这样）
                target = dir.resolve(baseName.substring(0, baseName.length() - 4));
                unzipTo(source, target);
            } else {
                target = dir.resolve(baseName);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            MCLDialog.info(owner, I18n.t("drop.title"),
                    I18n.t("drop.done", source.getFileName().toString(), entry.id()));
        } catch (Exception error) {
            SafeLog.warn("拖拽导入失败：" + error.getClass().getSimpleName());
            MCLDialog.error(owner, I18n.t("drop.title"), error.getMessage());
        }
    }

    /**
     * 解压 zip 到目标目录（存档导入用）。
     *
     * <p>注意两点（都是玩家实际踩到的）：
     * <ul>
     *   <li>Windows 上很多压缩包档案名是 GBK，按 UTF-8 解会抛
     *       {@code malformed input off : 1}；先用 UTF-8 试，失败换 GBK。</li>
     *   <li>压缩包常常多包一层资料夹（zip 里是 {@code 存档名/level.dat}），
     *       要剥掉这一层，否则游戏认不出存档。</li>
     * </ul>
     */
    private static void unzipTo(Path zip, Path target) throws Exception {
        Files.createDirectories(target);
        java.util.zip.ZipFile file = null;
        for (java.nio.charset.Charset charset : new java.nio.charset.Charset[]{
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.charset.Charset.forName("GBK")}) {
            try {
                file = new java.util.zip.ZipFile(zip.toFile(), charset);
                // 触发一次枚举，编码不对会在这里抛异常
                file.stream().count();
                break;
            } catch (Exception error) {
                if (file != null) {
                    file.close();
                    file = null;
                }
            }
        }
        if (file == null) {
            throw new java.io.IOException("无法读取压缩包（文件名编码无法识别）");
        }
        try (java.util.zip.ZipFile archive = file) {
            java.util.Set<String> tops = new java.util.TreeSet<>();
            int count = 0;
            long total = 0;
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(archive.entries())) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                if (slash > 0) {
                    tops.add(name.substring(0, slash));
                }
            }
            // 所有文件都在同一个顶层目录里 → 剥掉它
            String prefix = "";
            if (tops.size() == 1) {
                String only = tops.iterator().next();
                boolean allInside = java.util.Collections.list(archive.entries()).stream()
                        .map(entry -> entry.getName().replace('\\', '/'))
                        .allMatch(name -> name.equals(only) || name.startsWith(only + "/"));
                if (allInside) {
                    prefix = only + "/";
                }
            }
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(archive.entries())) {
                String name = entry.getName().replace('\\', '/');
                if (!prefix.isEmpty()) {
                    if (name.equals(prefix.substring(0, prefix.length() - 1))) {
                        continue;
                    }
                    name = name.substring(prefix.length());
                }
                if (name.isBlank()) {
                    continue;
                }
                // 安全审计 v2 A-10：条目数 / 解压总量 / 备用数据流（a.txt:evil）都要挡
                if (++count > com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_ENTRIES) {
                    throw new java.io.IOException("压缩包条目过多，已中止解压");
                }
                try {
                    com.brisklauncher.mcl.security.InputBudget.safeArchiveEntryName(name);
                } catch (SecurityException badName) {
                    throw new java.io.IOException(badName.getMessage());
                }
                Path dest = target.resolve(name).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (InputStream input = archive.getInputStream(entry)) {
                        long written = com.brisklauncher.mcl.security.InputBudget.copyBounded(
                                input, Files.newOutputStream(dest),
                                com.brisklauncher.mcl.security.InputBudget.MAX_ARCHIVE_TOTAL - total);
                        total += written;
                    } catch (java.io.IOException tooBig) {
                        try {
                            Files.deleteIfExists(dest);
                        } catch (Exception ignored) {
                            // 尽力而为
                        }
                        throw tooBig;
                    }
                }
            }
        }
    }
    private static void copyTree(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 整合包：选目标文件夹 → 选文件已有 → 自定义名称（复用版本页的流程）。 */
    private static void importModpack(Window owner, MclContext context, Path file) {
        List<GameDirectory> roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            MCLDialog.info(owner, I18n.t("version.import_modpack"), I18n.t("version.empty_roots"));
            return;
        }
        MCLDialog.choose(owner, I18n.t("version.import_modpack"), I18n.t("version.target_root_hint"),
                roots, root -> root.displayName() + "  (" + root.path() + ")",
                root -> MCLDialog.prompt(owner, I18n.t("version.import_modpack"),
                        I18n.t("version.import_name_hint"), packName(file), name ->
                                TaskDialog.run(owner, I18n.t("version.import_modpack"), progress ->
                                                context.modpackInstaller().install(root, file, name, progress),
                                        entry -> MCLDialog.info(owner, I18n.t("version.import_modpack"),
                                                I18n.t("launch.import_done", entry.id())))));
    }

    private static String packName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 当前选中的实例：优先启动页记住的那个；
     * 没有记忆（首次启动/刚删过）就退回第一个大目录里的第一个实例，
     * 这样拖拽不会"毫无反应"。
     */
    private static GameInstance currentInstance(MclContext context) {
        String rootPath = context.uiState().lastRoot();
        List<GameDirectory> roots = context.gameRepository().roots();
        if (roots.isEmpty()) {
            return null;
        }
        if (rootPath != null && !rootPath.isBlank()) {
            Path rootDir = Path.of(rootPath);
            for (GameDirectory root : roots) {
                if (!root.path().equals(rootDir)) {
                    continue;
                }
                String id = context.uiState().lastVersion(root.path());
                if (id != null && !id.isBlank()) {
                    GameInstance remembered = context.gameRepository().find(root, id).orElse(null);
                    if (remembered != null) {
                        return remembered;
                    }
                }
                List<GameInstance> all = context.gameRepository().versions(root);
                if (!all.isEmpty()) {
                    return all.get(0);
                }
            }
        }
        List<GameInstance> all = context.gameRepository().versions(roots.get(0));
        return all.isEmpty() ? null : all.get(0);
    }

    /** 供别处判断是否接受拖拽（例如对话框内部不处理）。 */
    public static boolean accepts(DragEvent event) {
        return event.getDragboard().hasFiles();
    }
}
