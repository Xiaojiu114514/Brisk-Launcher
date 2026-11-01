package com.brisklauncher.mcl.modpack;

import com.brisklauncher.mcl.game.GameInstance;
import com.brisklauncher.mcl.security.SafeLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * mrpack 编解码门面。
 *
 * <p>实现已拆到 {@link MrpackReader}（读取 + 路径预检）与 {@link MrpackExporter}（导出）。
 * 保留这个门面，是因为开发方案里把 “MrpackCodec” 约定为对外 API；
 * 真正的业务编排在 {@link MrpackInstaller}。
 */
public final class MrpackCodec {

    private final MrpackReader reader = new MrpackReader();
    private final MrpackExporter exporter = new MrpackExporter();

    /** 读取并校验索引（解压/下载之前必须先经过这一步）。 */
    public ModpackIndex readIndex(Path mrpackFile) throws IOException {
        return reader.read(mrpackFile);
    }

    /** 导出前自检：确保所有待写入条目路径安全。 */
    public void selfCheckExport(List<String> relativePaths) {
        for (String path : relativePaths) {
            MrpackPathValidator.validate(path);
        }
    }

    /** 导出一个实例为 mrpack。 */
    public Path export(GameInstance entry, Path destination, MrpackExporter.Options options)
            throws IOException {
        Path result = exporter.export(entry, destination, options);
        SafeLog.info("mrpack 导出完成：" + destination.getFileName());
        return result;
    }
}
