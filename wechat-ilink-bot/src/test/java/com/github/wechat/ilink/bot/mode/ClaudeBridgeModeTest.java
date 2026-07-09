package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.mode.claude.BridgeFileBuffer;
import com.github.wechat.ilink.bot.mode.claude.BridgeWorkspace;
import com.github.wechat.ilink.bot.mode.claude.ClaudeAdapterCallback;
import com.github.wechat.ilink.bot.mode.claude.ClaudeCodeAdapter;
import com.github.wechat.ilink.bot.persistence.ClaudeSessionRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.PlayerSession;
import com.github.wechat.ilink.bot.config.TaskConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClaudeBridgeModeTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private ClaudeSessionRepository repo;
    private ModeSender sender;
    private ModeContext ctx;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "bridge.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();
        repo = new ClaudeSessionRepository(dbManager);
        sender = mock(ModeSender.class);
        ctx = ModeContext.builder().sender(sender).claudeSessionRepo(repo).build();
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dbManager.close();
    }

    @Test
    void type_returnsClaude() {
        ClaudeBridgeMode mode = new ClaudeBridgeMode(null, repo, "cwd", "model", executor);
        assertEquals(BotModeType.CLAUDE, mode.type());
    }

    @Test
    void handleText_nullAdapter_sendsNotEnabled() throws Exception {
        ClaudeBridgeMode mode = new ClaudeBridgeMode(null, repo, "cwd", "model", executor);
        PlayerSession session = new PlayerSession("user1");

        ModeOutcome outcome = mode.handleText(ctx, session, "你好");

        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("未启用"));
    }

    @Test
    void handleText_newSession_insertsAndSetsActive() throws Exception {
        FakeAdapter adapter = new FakeAdapter("new-sid", "回复内容");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model-x", executor);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "第一条消息");

        verify(sender, timeout(2000)).sendText("user1", "回复内容");
        assertNull(adapter.resume.get(), "首次会话不应带 resume");
        assertEquals("new-sid", session.getActiveClaudeSessionId());
        assertNotNull(repo.findById("new-sid"));
        assertEquals("第一条消息", repo.findById("new-sid").getTitle());
    }

    @Test
    void handleText_existingSession_usesResumeAndTouches() throws Exception {
        repo.insert(new com.github.wechat.ilink.bot.mode.claude.ClaudeSession(
                "existing", "user1", "cwd", "model-x", "旧标题", 1L, 1L));
        FakeAdapter adapter = new FakeAdapter("existing", "续传回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model-x", executor);
        PlayerSession session = new PlayerSession("user1");
        session.setActiveClaudeSessionId("existing");

        mode.handleText(ctx, session, "后续消息");

        verify(sender, timeout(2000)).sendText("user1", "续传回复");
        assertEquals("existing", adapter.resume.get());
        assertTrue(repo.findById("existing").getUpdatedAt() > 1L, "updated_at 应被刷新");
        assertEquals("旧标题", repo.findById("existing").getTitle(), "续传不应改变标题");
    }

    @Test
    void handleText_adapterError_sendsFriendlyMessage() throws Exception {
        FakeAdapter adapter = new FakeAdapter(null, null);
        adapter.failWith = new RuntimeException("boom");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model", executor);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "触发错误");

        verify(sender, timeout(2000)).sendText(eq("user1"), contains("Claude 暂时无法回复"));
    }

    @Test
    void handleText_privilegedFlag_propagatesTrueToAdapter() throws Exception {
        FakeAdapter adapter = new FakeAdapter("new-sid", "回复内容");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model-x", executor);
        PlayerSession session = new PlayerSession("user1");
        session.setClaudePrivileged(true);

        mode.handleText(ctx, session, "提权后的问题");

        verify(sender, timeout(2000)).sendText("user1", "回复内容");
        assertEquals(Boolean.TRUE, adapter.privilegedSeen.get(), "提权标志应透传给 adapter");
    }

    @Test
    void handleText_default_propagatesRestrictedToAdapter() throws Exception {
        FakeAdapter adapter = new FakeAdapter("new-sid", "回复内容");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model-x", executor);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "普通问题");

        verify(sender, timeout(2000)).sendText("user1", "回复内容");
        assertEquals(Boolean.FALSE, adapter.privilegedSeen.get(), "默认应受限");
    }

    @Test
    void handleText_approved_prependsExecPrefixAndConsumesFlag() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter("new-sid", "执行结果");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model", executor);
        PlayerSession session = new PlayerSession("user1");
        session.setClaudeApprovedExec(true);

        mode.handleText(ctx, session, "开始");

        verify(sender, timeout(2000)).sendText("user1", "执行结果");
        assertEquals(1, adapter.prompts.size());
        assertTrue(adapter.prompts.get(0).contains("请执行上一轮提出的计划"), "approved 应拼执行前缀");
        assertTrue(adapter.prompts.get(0).endsWith("开始"), "应保留用户原文");
        assertFalse(session.isClaudeApprovedExec(), "approved 标志应被一次性消费");
    }

    @Test
    void handleText_restrictedWithTicket_omitsOutputDirInPrompt() throws Exception {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        BridgeWorkspace workspace = new BridgeWorkspace(tempDir.getAbsolutePath());
        OutputProducingAdapter adapter = new OutputProducingAdapter("new-sid", "回复内容");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, tempDir.getAbsolutePath(),
                "model", buffer, workspace, executor);

        buffer.put("user1", "data".getBytes("UTF-8"), "in.txt", false);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "处理这个");

        verify(sender, timeout(2000)).sendText("user1", "回复内容");
        String prompt = adapter.capturedPrompt.get();
        assertNotNull(prompt);
        assertTrue(prompt.contains("[附件]"), "受限模式仍应告知 input 附件路径");
        assertFalse(prompt.contains("回传文件"), "受限模式纯只读，不应提示 output 回传目录");
    }

    @Test
    void handleText_withBufferedTicket_augmentsPromptAndWritesInput() throws Exception {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        BridgeWorkspace workspace = new BridgeWorkspace(tempDir.getAbsolutePath());
        OutputProducingAdapter adapter = new OutputProducingAdapter("new-sid", "回复内容");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, tempDir.getAbsolutePath(),
                "model", buffer, workspace, executor);

        buffer.put("user1", "data".getBytes("UTF-8"), "in.txt", false);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "处理这个");

        verify(sender, timeout(2000)).sendText("user1", "回复内容");
        String prompt = adapter.capturedPrompt.get();
        assertNotNull(prompt);
        assertTrue(prompt.contains("[附件]"), "prompt 应包含附件说明");
        assertTrue(prompt.contains("in.txt"));
        Path inputFile = tempDir.toPath().resolve("user1").resolve("input").resolve("in.txt");
        assertTrue(Files.exists(inputFile), "入向文件应已写入 input 目录");
    }

    @Test
    void onComplete_outputFiles_sentByExtension() throws Exception {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        BridgeWorkspace workspace = new BridgeWorkspace(tempDir.getAbsolutePath());
        Path outputDir = tempDir.toPath().resolve("user1").resolve("output");
        OutputProducingAdapter adapter = new OutputProducingAdapter("sid", "ok", outputDir,
                Arrays.asList("out.png", "out.txt"));
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, tempDir.getAbsolutePath(),
                "model", buffer, workspace, executor);

        buffer.put("user1", new byte[]{1}, "in.bin", false);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "生成文件");

        verify(sender, timeout(2000)).sendText("user1", "ok");
        verify(sender, timeout(2000)).sendImage(eq("user1"), any(byte[].class), eq("out.png"), anyString());
        verify(sender, timeout(2000)).sendFile(eq("user1"), any(byte[].class), eq("out.txt"), anyString());
    }

    @Test
    void onComplete_tooManyOutputFiles_capsAtMax() throws Exception {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        BridgeWorkspace workspace = new BridgeWorkspace(tempDir.getAbsolutePath());
        Path outputDir = tempDir.toPath().resolve("user1").resolve("output");
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < 11; i++) {
            names.add("f" + i + ".png");
        }
        OutputProducingAdapter adapter = new OutputProducingAdapter("sid", "ok", outputDir, names);
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, tempDir.getAbsolutePath(),
                "model", buffer, workspace, executor);

        buffer.put("user1", new byte[]{1}, "in.bin", false);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "生成很多文件");

        verify(sender, timeout(2000)).sendText("user1", "ok");
        verify(sender, timeout(2000).times(10))
                .sendImage(eq("user1"), any(byte[].class), anyString(), anyString());
    }

    @Test
    void handleText_thresholdDisabled_neverCompacts() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter("existing", "回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model",
                null, null, executor, 0);
        PlayerSession session = new PlayerSession("user1");
        session.setActiveClaudeSessionId("existing");
        for (int i = 0; i < 5; i++) {
            session.incrementClaudeTurn();
        }

        mode.handleText(ctx, session, "用户消息");

        verify(sender, timeout(2000)).sendText("user1", "回复");
        assertEquals(1, adapter.prompts.size(), "阈值=0 不应触发 /compact");
        assertEquals("用户消息", adapter.prompts.get(0));
        verify(sender, never()).sendText(eq("user1"), contains("已自动压缩"));
    }

    @Test
    void handleText_thresholdReached_compactsBeforeUserPrompt() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter("existing", "回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model",
                null, null, executor, 3);
        PlayerSession session = new PlayerSession("user1");
        session.setActiveClaudeSessionId("existing");
        for (int i = 0; i < 3; i++) {
            session.incrementClaudeTurn();
        }

        mode.handleText(ctx, session, "用户消息");

        verify(sender, timeout(2000)).sendText("user1", "回复");
        assertEquals(2, adapter.prompts.size(), "达阈值应先 /compact 再处理用户消息");
        assertEquals("/compact", adapter.prompts.get(0), "首个子进程应是 /compact");
        assertEquals("existing", adapter.resumes.get(0), "压缩应带 resume 续传");
        assertEquals("用户消息", adapter.prompts.get(1));
        verify(sender, timeout(2000)).sendText(eq("user1"), contains("已自动压缩"));
        assertEquals(1, awaitTurnCount(session), "压缩后归零，用户轮回到 1");
    }

    @Test
    void handleText_newSessionHighCount_doesNotCompact() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter("new-sid", "回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model",
                null, null, executor, 3);
        PlayerSession session = new PlayerSession("user1");
        for (int i = 0; i < 5; i++) {
            session.incrementClaudeTurn();
        }

        mode.handleText(ctx, session, "首条消息");

        verify(sender, timeout(2000)).sendText("user1", "回复");
        assertEquals(1, adapter.prompts.size(), "新会话（无 resume）不应压缩");
        assertEquals("首条消息", adapter.prompts.get(0));
    }

    @Test
    void handleText_successfulReply_incrementsTurnCount() throws Exception {
        FakeAdapter adapter = new FakeAdapter("new-sid", "回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model", executor);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "消息");

        verify(sender, timeout(2000)).sendText("user1", "回复");
        assertEquals(1, awaitTurnCount(session), "成功回复应使轮次自增到 1");
    }

    @Test
    void handleText_compactFails_stillRepliesWithoutCompactNotice() throws Exception {
        CompactFailingAdapter adapter = new CompactFailingAdapter("existing", "正常回复");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model",
                null, null, executor, 3);
        PlayerSession session = new PlayerSession("user1");
        session.setActiveClaudeSessionId("existing");
        for (int i = 0; i < 3; i++) {
            session.incrementClaudeTurn();
        }

        mode.handleText(ctx, session, "用户消息");

        verify(sender, timeout(2000)).sendText("user1", "正常回复");
        assertEquals(2, adapter.prompts.size(), "达阈值应先 /compact 再处理用户消息");
        verify(sender, never()).sendText(eq("user1"), contains("已自动压缩"));
    }

    @Test
    void handleText_emptyResponse_sendsNoContentMessage() throws Exception {
        FakeAdapter adapter = new FakeAdapter("new-sid", "");
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, repo, "cwd", "model", executor);
        PlayerSession session = new PlayerSession("user1");

        mode.handleText(ctx, session, "消息");

        verify(sender, timeout(2000)).sendText("user1", "Claude 没有返回内容。");
    }

    /** 等待异步轮次计数稳定（onComplete 中自增发生在 sendText 之后，存在短暂时序差）。 */
    private static int awaitTurnCount(PlayerSession session) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (session.getClaudeTurnCount() > 0) {
                break;
            }
            Thread.sleep(20);
        }
        return session.getClaudeTurnCount();
    }

    /** 适配器：按调用顺序记录每次的 prompt / resume，并对每次调用回 onSessionId + onComplete。 */
    private static class RecordingAdapter extends ClaudeCodeAdapter {
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        final List<String> resumes = Collections.synchronizedList(new ArrayList<String>());
        private final String sessionId;
        private final String response;

        RecordingAdapter(String sessionId, String response) {
            super(newConfig());
            this.sessionId = sessionId;
            this.response = response;
        }

        private static TaskConfig newConfig() {
            TaskConfig c = new TaskConfig();
            c.setClaudeBridgeCwd("cwd");
            c.setClaudeBridgeModel("model");
            return c;
        }

        @Override
        public void run(String userId, String prompt, String resumeSessionId, boolean privileged, boolean plan, ClaudeAdapterCallback cb) {
            prompts.add(prompt);
            resumes.add(resumeSessionId);
            if (sessionId != null) {
                cb.onSessionId(sessionId);
            }
            cb.onComplete(response);
        }
    }

    private static class FakeAdapter extends ClaudeCodeAdapter {
        final AtomicReference<String> resume = new AtomicReference<String>(null);
        final AtomicReference<Boolean> privilegedSeen = new AtomicReference<Boolean>(null);
        private final String sessionId;
        private final String response;
        RuntimeException failWith;

        FakeAdapter(String sessionId, String response) {
            super(newConfig());
            this.sessionId = sessionId;
            this.response = response;
        }

        private static TaskConfig newConfig() {
            TaskConfig c = new TaskConfig();
            c.setClaudeBridgeCwd("cwd");
            c.setClaudeBridgeModel("model");
            return c;
        }

        @Override
        public void run(String userId, String prompt, String resumeSessionId, boolean privileged, boolean plan, ClaudeAdapterCallback cb) {
            resume.set(resumeSessionId);
            privilegedSeen.set(privileged);
            if (failWith != null) {
                cb.onError(failWith);
                return;
            }
            if (sessionId != null) {
                cb.onSessionId(sessionId);
            }
            cb.onComplete(response);
        }
    }

    /** 适配器：捕获 prompt，并可在 onComplete 前向 output 目录写入产物文件，用于验证出向回发。 */
    private static class OutputProducingAdapter extends ClaudeCodeAdapter {
        final AtomicReference<String> capturedPrompt = new AtomicReference<String>(null);
        private final String sessionId;
        private final String response;
        private final Path outputDir;
        private final List<String> outputFileNames;

        OutputProducingAdapter(String sessionId, String response) {
            this(sessionId, response, null, Collections.<String>emptyList());
        }

        OutputProducingAdapter(String sessionId, String response, Path outputDir, List<String> outputFileNames) {
            super(newConfig());
            this.sessionId = sessionId;
            this.response = response;
            this.outputDir = outputDir;
            this.outputFileNames = outputFileNames;
        }

        private static TaskConfig newConfig() {
            TaskConfig c = new TaskConfig();
            c.setClaudeBridgeCwd("cwd");
            c.setClaudeBridgeModel("model");
            return c;
        }

        @Override
        public void run(String userId, String prompt, String resumeSessionId, boolean privileged, boolean plan, ClaudeAdapterCallback cb) {
            capturedPrompt.set(prompt);
            if (outputDir != null) {
                try {
                    Files.createDirectories(outputDir);
                    for (String name : outputFileNames) {
                        Files.write(outputDir.resolve(name), new byte[]{1, 2, 3});
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (sessionId != null) {
                cb.onSessionId(sessionId);
            }
            cb.onComplete(response);
        }
    }

    /** 适配器：/compact 调用走 onError、其余调用正常完成——验证自动压缩失败不阻断主回复。 */
    private static class CompactFailingAdapter extends ClaudeCodeAdapter {
        final List<String> prompts = Collections.synchronizedList(new ArrayList<String>());
        private final String sessionId;
        private final String response;

        CompactFailingAdapter(String sessionId, String response) {
            super(newConfig());
            this.sessionId = sessionId;
            this.response = response;
        }

        private static TaskConfig newConfig() {
            TaskConfig c = new TaskConfig();
            c.setClaudeBridgeCwd("cwd");
            c.setClaudeBridgeModel("model");
            return c;
        }

        @Override
        public void run(String userId, String prompt, String resumeSessionId, boolean privileged, boolean plan, ClaudeAdapterCallback cb) {
            prompts.add(prompt);
            if ("/compact".equals(prompt)) {
                cb.onError(new RuntimeException("compact 失败"));
                return;
            }
            if (sessionId != null) {
                cb.onSessionId(sessionId);
            }
            cb.onComplete(response);
        }
    }
}
