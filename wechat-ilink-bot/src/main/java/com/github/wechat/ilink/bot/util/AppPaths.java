package com.github.wechat.ilink.bot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用 data 目录解析器。
 *
 * <p>所有配置与数据库路径经 {@link #data(String)} 解析到统一的 base（data 目录），
 * 而非依赖进程工作目录（{@code user.dir}）。这样把可运行 jar 从任意 CWD 启动时，
 * data 仍稳定落在 jar 边上（或被环境变量/系统属性覆盖）。
 *
 * <p>解析优先级见 {@link #resolveBase(String, String, Path, Path)}。
 */
public final class AppPaths {

    private static final Logger log = LoggerFactory.getLogger(AppPaths.class);

    private static final Path BASE = init();

    private AppPaths() {
    }

    /** 把相对名解析为 data 目录下的绝对路径字符串（如 {@code data("farm_game.db")}）。 */
    public static String data(String name) {
        return BASE.resolve(name).toString();
    }

    /** data 目录本身。 */
    public static Path baseDir() {
        return BASE;
    }

    private static Path init() {
        Path base = resolveBase(
                System.getProperty("bot.data.dir"),
                System.getenv("BOT_DATA_DIR"),
                codeSourceLocation(),
                Paths.get("").toAbsolutePath());
        log.info("data 目录: {}", base);
        return base;
    }

    /**
     * 纯函数解析 base（data 目录），便于单测。优先级：
     * <ol>
     *   <li>{@code sysProp}（-Dbot.data.dir）非空 → 该路径</li>
     *   <li>{@code envVar}（BOT_DATA_DIR）非空 → 该路径</li>
     *   <li>{@code codeSourceLoc} 指向 .jar 文件 → {@code <jar 所在目录>/data}</li>
     *   <li>否则（IDE/classes 目录运行）→ {@code <cwd>/data}</li>
     * </ol>
     */
    static Path resolveBase(String sysProp, String envVar, Path codeSourceLoc, Path cwd) {
        if (sysProp != null && !sysProp.isEmpty()) {
            return Paths.get(sysProp).toAbsolutePath().normalize();
        }
        if (envVar != null && !envVar.isEmpty()) {
            return Paths.get(envVar).toAbsolutePath().normalize();
        }
        if (codeSourceLoc != null && codeSourceLoc.toString().endsWith(".jar")) {
            Path jarDir = codeSourceLoc.toAbsolutePath().getParent();
            if (jarDir != null) {
                return jarDir.resolve("data").normalize();
            }
        }
        return cwd.resolve("data").normalize();
    }

    private static Path codeSourceLocation() {
        try {
            URL loc = AppPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            return Paths.get(loc.toURI());
        } catch (Exception e) {
            log.warn("无法解析 code source 位置，将回退到工作目录", e);
            return null;
        }
    }
}
