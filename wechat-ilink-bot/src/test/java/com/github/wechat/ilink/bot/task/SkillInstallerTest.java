package com.github.wechat.ilink.bot.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillInstaller} 单测：用临时 claudeHome + 真实内置 skill 资源（test classpath 可见），
 * 验证首次安装、幂等跳过、嵌套目录创建。
 */
class SkillInstallerTest {

    private static final List<String> EXPECTED_FILES = Arrays.asList(
            "SKILL.md",
            "references/rubrics/beginner.md",
            "references/rubrics/intermediate.md",
            "references/rubrics/exam-prep.md",
            "scripts/analyze_video.py");

    @TempDir
    File tempDir;

    @Test
    void installAll_freshTarget_copiesAllBundledFiles() throws Exception {
        Path claudeHome = tempDir.toPath();
        SkillInstaller installer = new SkillInstaller(claudeHome);

        installer.installAll();

        Path skillRoot = claudeHome.resolve(".claude").resolve("skills").resolve("piano-practice-review");
        for (String rel : EXPECTED_FILES) {
            Path f = skillRoot.resolve(rel);
            assertTrue(Files.exists(f), "应已安装: " + rel);
            assertTrue(Files.size(f) > 0, "文件不应为空: " + rel);
        }
    }

    @Test
    void installAll_existingTarget_skipsAndDoesNotOverwrite() throws Exception {
        Path claudeHome = tempDir.toPath();
        SkillInstaller installer = new SkillInstaller(claudeHome);
        installer.installAll();

        // 模拟用户对 rubric 的本地修改：再次安装不应覆盖
        Path rubric = claudeHome.resolve(".claude/skills/piano-practice-review/references/rubrics/beginner.md");
        Files.write(rubric, "我的自定义 rubric".getBytes(StandardCharsets.UTF_8));

        installer.installAll();

        String content = new String(Files.readAllBytes(rubric), StandardCharsets.UTF_8);
        assertEquals("我的自定义 rubric", content, "已存在的目标不应被覆盖");
        assertTrue(Files.exists(claudeHome.resolve(".claude/skills/piano-practice-review/SKILL.md")),
                "其余文件仍应在位");
    }

    @Test
    void installAll_createsNestedDirectories() throws Exception {
        Path claudeHome = tempDir.toPath();
        new SkillInstaller(claudeHome).installAll();

        assertTrue(Files.isDirectory(claudeHome.resolve(".claude/skills/piano-practice-review/references/rubrics")),
                "嵌套 rubric 目录应被创建");
        assertTrue(Files.isDirectory(claudeHome.resolve(".claude/skills/piano-practice-review/scripts")),
                "嵌套 scripts 目录应被创建");
    }
}
