package com.github.wechat.ilink.bot.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    private static final Path CWD = Paths.get("").toAbsolutePath();

    @Test
    void resolveBase_sysPropSet_takesPrecedenceOverEnvAndJar() {
        Path jar = Paths.get("/opt/app/bot.jar");
        Path base = AppPaths.resolveBase("/custom/data", "/env/data", jar, CWD);

        assertEquals(Paths.get("/custom/data").toAbsolutePath().normalize(), base);
    }

    @Test
    void resolveBase_onlyEnvSet_usesEnv() {
        Path jar = Paths.get("/opt/app/bot.jar");
        Path base = AppPaths.resolveBase(null, "/env/data", jar, CWD);

        assertEquals(Paths.get("/env/data").toAbsolutePath().normalize(), base);
    }

    @Test
    void resolveBase_jarCodeSource_usesDataNextToJar() {
        Path jar = Paths.get("/opt/app/bot.jar");
        Path base = AppPaths.resolveBase(null, null, jar, CWD);

        Path expected = jar.toAbsolutePath().getParent().resolve("data").normalize();
        assertEquals(expected, base);
    }

    @Test
    void resolveBase_classesDirCodeSource_fallsBackToCwd() {
        Path classes = Paths.get("/proj/target/classes");
        Path cwd = Paths.get("/proj").toAbsolutePath();
        Path base = AppPaths.resolveBase(null, null, classes, cwd);

        assertEquals(cwd.resolve("data").normalize(), base);
    }

    @Test
    void resolveBase_emptyOverrides_treatedAsUnset() {
        Path jar = Paths.get("/opt/app/bot.jar");
        Path base = AppPaths.resolveBase("", "", jar, CWD);

        Path expected = jar.toAbsolutePath().getParent().resolve("data").normalize();
        assertEquals(expected, base);
    }
}
