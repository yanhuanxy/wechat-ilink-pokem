package com.github.wechat.ilink.bot.task;

import com.github.wechat.ilink.bot.config.TaskConfig;
import com.github.wechat.ilink.bot.util.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TaskConfigTest {

    @TempDir
    File tempDir;

    @Test
    void defaults_disabledByDefault() {
        TaskConfig config = new TaskConfig();

        assertFalse(config.isEnabled());
        assertEquals("claude", config.getClaudePath());
        assertEquals(AppPaths.data("tasks"), config.getWorkspaceRoot());
        assertEquals(AppPaths.data("claude-home"), config.getClaudeHome());
        assertEquals("", config.getVideoReviewModel());
        assertEquals("", config.getClaudeBridgeModel());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", config.getDashscopeBaseUrl());
        assertEquals("", config.getDashscopeApiKey());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/uploads", config.getDashscopeUploadsUrl());
        assertEquals(600_000L, config.getTimeoutMs());
        assertEquals("bypassPermissions", config.getPermissionMode());
        assertTrue(config.isStreamingEnabled());
        assertEquals(50L * 1024 * 1024, config.getMaxVideoBytes());
        assertEquals(60_000L, config.getBufferTtlMs());
        assertNotNull(config.getAllowedTools());
        assertTrue(config.getAllowedTools().isEmpty());
        assertNotNull(config.getDisallowedTools());
        assertTrue(config.getDisallowedTools().isEmpty());
    }

    @Test
    void defaults_bridgePermissionReadOnlyByDefault() {
        TaskConfig config = new TaskConfig();

        assertEquals("default", config.getClaudeBridgePermissionMode());
        assertEquals("plan", config.getClaudeBridgePlanMode());
        assertEquals("bypassPermissions", config.getClaudeBridgePrivilegedMode());
        assertEquals(java.util.Arrays.asList("Read", "LS", "Glob", "Grep"),
                config.getClaudeBridgeAllowedTools());
        assertEquals(java.util.Arrays.asList("Bash", "Write", "Edit", "NotebookEdit"),
                config.getClaudeBridgeDisallowedTools());
        assertNotNull(config.getClaudeAdminUsers());
        assertTrue(config.getClaudeAdminUsers().isEmpty(), "默认无管理员，任何人都不能提权");
        assertEquals(0, config.getClaudeBridgeCompactThreshold(), "默认关闭自动压缩");
    }

    @Test
    void load_compactThreshold_loadsValue() throws Exception {
        File file = new File(tempDir, "task-config.json");
        writeJson(file, "{"
                + "\"enabled\": true,"
                + "\"claudeBridgeCompactThreshold\": 20"
                + "}");

        TaskConfig config = TaskConfig.load(file.getAbsolutePath());

        assertEquals(20, config.getClaudeBridgeCompactThreshold());
    }

    @Test
    void load_toolPolicyArrays_loadsLists() throws Exception {
        File file = new File(tempDir, "task-config.json");
        writeJson(file, "{"
                + "\"enabled\": true,"
                + "\"permissionMode\": \"default\","
                + "\"allowedTools\": [\"Read\", \"Grep\", \"Bash(git log:*)\"],"
                + "\"disallowedTools\": [\"Bash(rm:*)\"]"
                + "}");

        TaskConfig config = TaskConfig.load(file.getAbsolutePath());

        assertEquals("default", config.getPermissionMode());
        assertEquals(3, config.getAllowedTools().size());
        assertEquals("Read", config.getAllowedTools().get(0));
        assertEquals("Bash(git log:*)", config.getAllowedTools().get(2));
        assertEquals(1, config.getDisallowedTools().size());
        assertEquals("Bash(rm:*)", config.getDisallowedTools().get(0));
    }

    @Test
    void load_missingFile_generatesTemplateAndReturnsDisabled() {
        File file = new File(tempDir, "task-config.json");
        assertFalse(file.exists());

        TaskConfig config = TaskConfig.load(file.getAbsolutePath());

        assertFalse(config.isEnabled());
        assertTrue(file.exists());
    }

    @Test
    void load_validFile_loadsFields() throws Exception {
        File file = new File(tempDir, "task-config.json");
        writeJson(file, "{"
                + "\"enabled\": true,"
                + "\"claudePath\": \"/usr/local/bin/claude\","
                + "\"workspaceRoot\": \"/tmp/ws\","
                + "\"claudeHome\": \"/tmp/claude-home\","
                + "\"timeoutMs\": 60000,"
                + "\"permissionMode\": \"bypassPermissions\","
                + "\"streamingEnabled\": false,"
                + "\"maxVideoBytes\": 1024,"
                + "\"bufferTtlMs\": 30000"
                + "}");

        TaskConfig config = TaskConfig.load(file.getAbsolutePath());

        assertTrue(config.isEnabled());
        assertEquals("/usr/local/bin/claude", config.getClaudePath());
        assertEquals("/tmp/ws", config.getWorkspaceRoot());
        assertEquals("/tmp/claude-home", config.getClaudeHome());
        assertEquals(60_000L, config.getTimeoutMs());
        assertEquals("bypassPermissions", config.getPermissionMode());
        assertFalse(config.isStreamingEnabled());
        assertEquals(1024L, config.getMaxVideoBytes());
        assertEquals(30_000L, config.getBufferTtlMs());
    }

    @Test
    void load_corruptedFile_returnsDefaults() {
        File file = new File(tempDir, "task-config.json");
        writeJson(file, "not a json");

        TaskConfig config = TaskConfig.load(file.getAbsolutePath());

        assertFalse(config.isEnabled());
    }

    private void writeJson(File file, String content) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(file);
            w.write(content);
            w.close();
        } catch (Exception e) {
            fail(e);
        }
    }
}
