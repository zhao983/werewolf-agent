package com.example.werewolf.experiment;

import java.nio.file.Path;

/** 保存目录以应用所在目录为基准，避免从不同工作目录启动时读到不同的存档。 */
public final class ExperimentStorage {
    private ExperimentStorage() { }

    public static Path resolve(String configured) {
        Path requested = configured == null || configured.isBlank() ? Path.of("data", "experiments") : Path.of(configured);
        if (requested.isAbsolute()) return requested.normalize();
        try {
            var location = ExperimentStorage.class.getProtectionDomain().getCodeSource().getLocation();
            // 开发时直接读取 classes 目录；打包时由 Spring 解析嵌套 jar 的所在目录。
            Path home = location.getProtocol().equals("file") ? applicationHome(Path.of(location.toURI()))
                    : new org.springframework.boot.system.ApplicationHome(com.example.werewolf.WerewolfApplication.class).getDir().toPath();
            return home.resolve(requested).toAbsolutePath().normalize();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("无法确定存档目录，请设置 WEREWOLF_DATA_DIR 绝对路径", e);
        }
    }

    static Path applicationHome(Path location) {
        // Maven 开发模式位于 backend/target/classes；打包运行则使用 jar 所在目录。
        if (location.getFileName().toString().equals("classes") && location.getParent().getFileName().toString().equals("target"))
            return location.getParent().getParent();
        return java.nio.file.Files.isDirectory(location) ? location : location.getParent();
    }
}
