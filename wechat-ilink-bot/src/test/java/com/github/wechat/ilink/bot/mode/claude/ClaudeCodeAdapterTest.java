package com.github.wechat.ilink.bot.mode.claude;

import com.github.wechat.ilink.bot.config.TaskConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeCodeAdapterTest {

    @TempDir
    java.io.File tempDir;

    private TaskConfig config;

    @BeforeEach
    void setUp() {
        config = new TaskConfig();
        config.setEnabled(true);
        config.setClaudePath("claude");
        config.setClaudeHome(tempDir.getAbsolutePath());
        config.setClaudeBridgeCwd(tempDir.getAbsolutePath());
        config.setClaudeBridgeModel("qwen-plus");
        config.setTimeoutMs(5_000L);
    }

    @Test
    void buildArgs_noResume_omitsResumeFlag() {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hello", null, false, false);

        assertTrue(args.contains("-p"));
        assertTrue(args.contains("--output-format"));
        assertTrue(args.contains("stream-json"));
        assertTrue(args.contains("--verbose"));
        assertFalse(args.contains("--resume"));
    }

    @Test
    void buildArgs_withResume_appendsResumeFlag() {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hello", "sid-123", false, false);

        int idx = args.indexOf("--resume");
        assertTrue(idx >= 0);
        assertEquals("sid-123", args.get(idx + 1));
    }

    @Test
    void buildArgs_withModel_includesModelFlag() {
        config.setClaudeBridgeModel("qwen-max");
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hi", null, false, false);

        int idx = args.indexOf("--model");
        assertTrue(idx >= 0);
        assertEquals("qwen-max", args.get(idx + 1));
    }

    @Test
    void buildArgs_restricted_emitsReadOnlyPolicy() {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hi", null, false, false);

        int permIdx = args.indexOf("--permission-mode");
        assertTrue(permIdx >= 0);
        assertEquals("default", args.get(permIdx + 1));
        int allowIdx = args.indexOf("--allowedTools");
        assertTrue(allowIdx > permIdx, "--allowedTools 必须在 --permission-mode 之后");
        assertEquals("Read,LS,Glob,Grep", args.get(allowIdx + 1));
        int disallowIdx = args.indexOf("--disallowedTools");
        assertTrue(disallowIdx > allowIdx, "--disallowedTools 必须在 --allowedTools 之后");
        assertEquals("Bash,Write,Edit,NotebookEdit", args.get(disallowIdx + 1));
        assertFalse(args.contains("--dangerously-skip-permissions"), "受限档只读，不应 bypass");
    }

    @Test
    void buildArgs_restricted_emptyBridgeLists_omitsToolFlags() {
        config.setClaudeBridgeAllowedTools(java.util.Collections.<String>emptyList());
        config.setClaudeBridgeDisallowedTools(java.util.Collections.<String>emptyList());
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hi", null, false, false);

        assertFalse(args.contains("--allowedTools"));
        assertFalse(args.contains("--disallowedTools"));
    }

    @Test
    void buildArgs_privileged_usesBypassAndNoToolFlags() {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hi", null, true, false);

        int permIdx = args.indexOf("--permission-mode");
        assertTrue(permIdx >= 0);
        assertEquals("bypassPermissions", args.get(permIdx + 1));
        assertTrue(args.contains("--dangerously-skip-permissions"),
                "headless -p 下 bypass 仍需一次危险模式确认，须该 flag 跳过（P0 修复）");
        assertFalse(args.contains("--allowedTools"), "提权档 bypass 忽略工具策略，不应下发");
        assertFalse(args.contains("--disallowedTools"));
    }

    @Test
    void buildArgs_plan_emitsPlanModeAndAllowlistOnly() {
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config);

        List<String> args = adapter.buildArgs("hi", null, false, true);

        int permIdx = args.indexOf("--permission-mode");
        assertTrue(permIdx >= 0);
        assertEquals("plan", args.get(permIdx + 1));
        int allowIdx = args.indexOf("--allowedTools");
        assertTrue(allowIdx > permIdx, "plan 档应下只读白名单");
        assertEquals("Read,LS,Glob,Grep", args.get(allowIdx + 1));
        assertFalse(args.contains("--disallowedTools"), "plan 档不下黑名单（plan 模式本身禁写）");
        assertFalse(args.contains("--dangerously-skip-permissions"), "plan 档只读，不应 bypass");
    }

    @Test
    void applyBridgeEnv_derivesCompleteModelSet_clearsPollution() {
        Map<String, String> env = new HashMap<String, String>();
        // 模拟继承污染：错误认证 + 一个被污染的角色模型（claude 内置名，DashScope 不存在）
        env.put("ANTHROPIC_API_KEY", "sk-inherited");
        env.put("ANTHROPIC_AUTH_TOKEN", "sk-host-polluted");
        env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", "claude-sonnet-x");

        TaskConfig cfg = new TaskConfig();
        cfg.setClaudeBridgeModel("qwen3.7-max");
        cfg.setClaudeBridgeSmallModel("qwen3.6-flash");
        cfg.setClaudeBridgeBaseUrl("https://dashscope.aliyuncs.com/apps/anthropic");
        cfg.setClaudeBridgeApiKey("sk-bridge");

        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);

        // 完整角色模型已派生（claude 任何角色都不回落内置 Claude 模型名）
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_MODEL"));
        assertEquals("qwen3.6-flash", env.get("ANTHROPIC_SMALL_FAST_MODEL"));
        assertEquals("qwen3.6-flash", env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_DEFAULT_SONNET_MODEL"));   // 污染被清除并覆盖
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_DEFAULT_OPUS_MODEL"));
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_REASONING_MODEL"));
        // Bearer 认证，清掉 x-api-key 污染
        assertEquals("sk-bridge", env.get("ANTHROPIC_AUTH_TOKEN"));
        assertNull(env.get("ANTHROPIC_API_KEY"));
        // 端点 + flags
        assertEquals("https://dashscope.aliyuncs.com/apps/anthropic", env.get("ANTHROPIC_BASE_URL"));
        assertEquals("1", env.get("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS"));
        assertEquals("0", env.get("ENABLE_TOOL_SEARCH"));
    }

    @Test
    void applyBridgeEnv_blankSmallModel_haikuFallsBackToMain() {
        Map<String, String> env = new HashMap<String, String>();

        TaskConfig cfg = new TaskConfig();
        cfg.setClaudeBridgeModel("qwen3.7-max");
        cfg.setClaudeBridgeApiKey("sk-bridge");

        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);

        // smallModel 为空时，haiku/small 角色回落到主模型，避免 claude 用内置 haiku
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_DEFAULT_HAIKU_MODEL"));
        assertEquals("qwen3.7-max", env.get("ANTHROPIC_SMALL_FAST_MODEL"));
    }

    @Test
    void applyBridgeEnv_noBridgeKey_fallsBackToDashscopeKey() {
        Map<String, String> env = new HashMap<String, String>();

        TaskConfig cfg = new TaskConfig();
        cfg.setClaudeBridgeModel("qwen-plus");
        cfg.setDashscopeApiKey("sk-ds");

        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);

        assertEquals("sk-ds", env.get("ANTHROPIC_AUTH_TOKEN"));
    }

    @Test
    void applyBridgeEnv_claudeConfigDir_emitsAbsolutePath() {
        Map<String, String> env = new HashMap<String, String>();
        TaskConfig cfg = new TaskConfig();
        cfg.setClaudeBridgeModel("qwen-plus");
        cfg.setClaudeHome("data/claude-home");   // 相对路径，模拟生产 JSON 覆盖构造器绝对默认

        ClaudeCodeAdapter.applyBridgeEnv(env, cfg);

        String dir = env.get("CLAUDE_CONFIG_DIR");
        assertNotNull(dir, "claudeHome 已配置时应下发 CLAUDE_CONFIG_DIR");
        assertTrue(java.nio.file.Paths.get(dir).isAbsolute(),
                "CLAUDE_CONFIG_DIR 必须绝对，避免被子进程 CWD 二次解析到不存在位置（P0 缺陷 B）");
    }

    @Test
    void run_initEvent_reportsSessionId() throws Exception {
        String stdout = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc-123\"}\n"
                + "{\"type\":\"result\",\"result\":\"done\"}\n";
        RecordingCallback cb = runAdapter(stdout, null, 0, null);

        assertEquals("abc-123", cb.sessionId.get());
        assertEquals("done", cb.completed.get());
        assertNull(cb.error.get());
    }

    @Test
    void run_contentBlockDelta_emitsTokens() throws Exception {
        String stdout = "{\"type\":\"system\",\"session_id\":\"sid\"}\n"
                + "{\"content_block_delta\":{\"text\":\"Hello \"}}\n"
                + "{\"content_block_delta\":{\"text\":\"World\"}}\n"
                + "{\"type\":\"result\",\"result\":\"Hello World\"}\n";
        RecordingCallback cb = runAdapter(stdout, null, 0, null);

        assertEquals("Hello World", cb.tokens.toString());
        assertEquals("Hello World", cb.completed.get());
    }

    @Test
    void run_resumePassedToStartProcess() throws Exception {
        String stdout = "{\"type\":\"result\",\"result\":\"ok\"}\n";
        AtomicReference<String> capturedResume = new AtomicReference<String>("UNSET");
        runAdapter(stdout, null, 0, capturedResume, "resume-me");

        assertEquals("resume-me", capturedResume.get());
    }

    @Test
    void run_apiError_callsOnError() throws Exception {
        String stdout = "{\"type\":\"result\",\"is_error\":true,"
                + "\"result\":\"API Error: 403 free quota exhausted\"}\n";
        RecordingCallback cb = runAdapter(stdout, null, 0, null);

        assertNotNull(cb.error.get());
        assertTrue(cb.error.get().getMessage().contains("403"));
        assertNull(cb.completed.get());
    }

    @Test
    void run_nonZeroExit_callsOnError() throws Exception {
        RecordingCallback cb = runAdapter("", "claude not found", 127, null);

        assertNotNull(cb.error.get());
        assertTrue(cb.error.get().getMessage().contains("127"));
    }

    @Test
    void run_messageContentNoResultLine_completesWithStreamedText() throws Exception {
        String stdout = "{\"type\":\"system\",\"session_id\":\"sid\"}\n"
                + "{\"type\":\"assistant\",\"message\":{\"content\":[{\"text\":\"片段A\"},{\"text\":\"片段B\"}]}}\n";
        RecordingCallback cb = runAdapter(stdout, null, 0, null);

        assertEquals("片段A片段B", cb.tokens.toString());
        assertEquals("片段A片段B", cb.completed.get());
    }

    @Test
    void run_processTimeout_callsOnError() throws Exception {
        config.setTimeoutMs(50L);
        final TimeoutProcess fake = new TimeoutProcess();
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config) {
            @Override
            protected Process startProcess(String prompt, String resume, Path workDir, boolean privileged, boolean plan) {
                return fake;
            }
        };

        RecordingCallback cb = new RecordingCallback();
        adapter.run("user1", "prompt", null, false, false, cb);

        assertTrue(cb.latch.await(2, TimeUnit.SECONDS));
        assertNotNull(cb.error.get());
        assertTrue(cb.error.get().getMessage().contains("超时"));
    }

    @Test
    void run_perUserWorkDir_isolatesUsers() throws Exception {
        final AtomicReference<Path> capturedWorkDir = new AtomicReference<Path>(null);
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config) {
            @Override
            protected Process startProcess(String prompt, String resume, Path workDir, boolean privileged, boolean plan) {
                capturedWorkDir.set(workDir);
                return new FakeProcess(
                        new ByteArrayInputStream("{\"type\":\"result\",\"result\":\"ok\"}\n".getBytes()),
                        new ByteArrayInputStream(new byte[0]), 0);
            }
        };

        RecordingCallback cbA = new RecordingCallback();
        adapter.run("alice@x.com", "p", null, false, false, cbA);
        assertTrue(cbA.latch.await(2, TimeUnit.SECONDS));
        Path dirA = capturedWorkDir.get();

        RecordingCallback cbB = new RecordingCallback();
        adapter.run("bob@x.com", "p", null, false, false, cbB);
        assertTrue(cbB.latch.await(2, TimeUnit.SECONDS));
        Path dirB = capturedWorkDir.get();

        assertNotNull(dirA);
        assertNotNull(dirB);
        assertNotEquals(dirA, dirB, "不同用户应落到不同工作目录，A 不可读 B");
        assertEquals(BridgeWorkspace.sanitize("alice@x.com"), dirA.getFileName().toString());
        assertEquals(BridgeWorkspace.sanitize("bob@x.com"), dirB.getFileName().toString());
    }

    private RecordingCallback runAdapter(String stdout, String stderr, int exitCode,
                                         AtomicReference<String> capturedResume) throws Exception {
        return runAdapter(stdout, stderr, exitCode, capturedResume, null);
    }

    private RecordingCallback runAdapter(String stdout, String stderr, int exitCode,
                                         final AtomicReference<String> capturedResume,
                                         String resumeSessionId) throws Exception {
        final FakeProcess fake = new FakeProcess(
                new ByteArrayInputStream(stdout.getBytes("UTF-8")),
                new ByteArrayInputStream((stderr == null ? "" : stderr).getBytes("UTF-8")),
                exitCode);
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(config) {
            @Override
            protected Process startProcess(String prompt, String resume, Path workDir, boolean privileged, boolean plan) {
                if (capturedResume != null) {
                    capturedResume.set(resume);
                }
                return fake;
            }
        };

        RecordingCallback cb = new RecordingCallback();
        adapter.run("user1", "prompt", resumeSessionId, false, false, cb);
        assertTrue(cb.latch.await(2, TimeUnit.SECONDS), "callback did not complete");
        return cb;
    }

    static class RecordingCallback implements ClaudeAdapterCallback {
        final AtomicReference<String> sessionId = new AtomicReference<String>(null);
        final AtomicReference<String> completed = new AtomicReference<String>(null);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>(null);
        final StringBuilder tokens = new StringBuilder();
        final CountDownLatch latch = new CountDownLatch(1);

        @Override public void onSessionId(String id) { sessionId.set(id); }
        @Override public synchronized void onToken(String token) { tokens.append(token); }
        @Override public void onComplete(String full) { completed.set(full); latch.countDown(); }
        @Override public void onError(Throwable t) { error.set(t); latch.countDown(); }
    }

    static class FakeProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;

        FakeProcess(InputStream stdout, InputStream stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return new OutputStream() {
            @Override public void write(int b) {}
        }; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() {}
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }

    static class TimeoutProcess extends Process {
        private volatile boolean alive = true;
        @Override public OutputStream getOutputStream() { return new OutputStream() {
            @Override public void write(int b) {}
        }; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return false; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
