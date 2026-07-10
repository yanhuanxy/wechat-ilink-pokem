package com.github.wechat.ilink.bot.mode;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
import com.github.wechat.ilink.bot.llm.ChatHistoryManager;
import com.github.wechat.ilink.bot.llm.LlmProvider;
import com.github.wechat.ilink.bot.llm.LlmRequestQueue;
import com.github.wechat.ilink.bot.mode.claude.BridgeFileBuffer;
import com.github.wechat.ilink.bot.mode.hook.BotHook;
import com.github.wechat.ilink.bot.mode.hook.HookContext;
import com.github.wechat.ilink.bot.mode.hook.HookEvent;
import com.github.wechat.ilink.bot.mode.hook.HookVerdict;
import com.github.wechat.ilink.bot.mode.hook.RateLimitHook;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.PlayerSession;
import com.github.wechat.ilink.bot.session.SessionManager;
import com.github.wechat.ilink.bot.task.TaskMessageHandler;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VideoItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModeRouterTest {

    @TempDir
    File tempDir;

    private DatabaseManager dbManager;
    private SessionManager sessionManager;
    private GameEngine engine;
    private ResponseRenderer renderer;
    private LlmProvider llmProvider;
    private ChatHistoryManager history;
    private LlmRequestQueue queue;
    private TaskMessageHandler taskHandler;
    private ModeSender sender;
    private ModeContext ctx;
    private ModeRouter router;
    private ExecutorService claudeExecutor;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "router.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();

        sessionManager = new SessionManager(dbManager);
        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "qr"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        engine = new GameEngine(parser, sessionManager, registry);
        renderer = new ResponseRenderer();
        llmProvider = mock(LlmProvider.class);
        when(llmProvider.chat(anyList())).thenReturn("AI 回复");
        history = new ChatHistoryManager(20);
        queue = new LlmRequestQueue(3, 50);
        taskHandler = mock(TaskMessageHandler.class);
        sender = mock(ModeSender.class);

        ctx = ModeContext.builder()
                .sender(sender).engine(engine).renderer(renderer)
                .llmProvider(llmProvider).chatHistory(history).llmQueue(queue)
                .sessions(sessionManager).taskHandler(taskHandler)
                .typingIntervalMs(5000).build();
        router = new ModeRouter(ctx,
                new ChatMode(),
                new FarmMode(),
                new SystemCommandMode(),
                new ReviewMode(taskHandler),
                null);
        claudeExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        queue.shutdown();
        claudeExecutor.shutdownNow();
        dbManager.close();
    }

    @Test
    void route_videoMessage_delegatesToReviewMode() {
        MessageItem item = new MessageItem();
        item.setVideo_item(new VideoItem());
        WeixinMessage msg = msgWithUserId("user1", item);

        when(taskHandler.tryHandleVideo(eq("user1"), any())).thenReturn(true);

        ModeOutcome outcome = router.route(msg);

        assertTrue(outcome.isHandled());
        verify(taskHandler).tryHandleVideo(eq("user1"), any());
    }

    @Test
    void route_claudeMode_image_downloadsAndBuffers() throws Exception {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        ClaudeBridgeMode claudeMode = new ClaudeBridgeMode(null, null, tempDir.getAbsolutePath(),
                "model", buffer, null, claudeExecutor);
        MediaDownloader downloader = mock(MediaDownloader.class);
        when(downloader.downloadImage(any(MessageItem.class))).thenReturn(new byte[]{1, 2, 3});
        ModeContext fileCtx = ModeContext.builder()
                .sender(sender).downloader(downloader).engine(engine).renderer(renderer)
                .llmProvider(llmProvider).chatHistory(history).llmQueue(queue)
                .sessions(sessionManager).taskHandler(taskHandler)
                .typingIntervalMs(5000).build();
        ModeRouter fileRouter = new ModeRouter(fileCtx,
                new ChatMode(), new FarmMode(), new SystemCommandMode(),
                new ReviewMode(taskHandler), claudeMode);

        PlayerSession session = sessionManager.getOrCreate("user1");
        session.setCurrentMode(BotModeType.CLAUDE);
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        WeixinMessage msg = msgWithUserId("user1", item);

        ModeOutcome outcome = fileRouter.route(msg);

        assertTrue(outcome.isHandled());
        verify(downloader).downloadImage(item);
        assertEquals(1, buffer.size());
        verify(sender).sendText(eq("user1"), contains("已收到文件"));
    }

    @Test
    void route_nonClaudeMode_image_skipsBuffering() {
        BridgeFileBuffer buffer = new BridgeFileBuffer(60_000L, 1024L);
        ClaudeBridgeMode claudeMode = new ClaudeBridgeMode(null, null, tempDir.getAbsolutePath(),
                "model", buffer, null, claudeExecutor);
        MediaDownloader downloader = mock(MediaDownloader.class);
        ModeContext fileCtx = ModeContext.builder()
                .sender(sender).downloader(downloader).engine(engine).renderer(renderer)
                .llmProvider(llmProvider).chatHistory(history).llmQueue(queue)
                .sessions(sessionManager).taskHandler(taskHandler)
                .typingIntervalMs(5000).build();
        ModeRouter fileRouter = new ModeRouter(fileCtx,
                new ChatMode(), new FarmMode(), new SystemCommandMode(),
                new ReviewMode(taskHandler), claudeMode);

        PlayerSession session = sessionManager.getOrCreate("user1");
        session.setCurrentMode(BotModeType.CHAT);
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        WeixinMessage msg = msgWithUserId("user1", item);

        ModeOutcome outcome = fileRouter.route(msg);

        assertEquals(ModeOutcome.Status.SKIP, outcome.getStatus());
        verifyNoInteractions(downloader);
        assertEquals(0, buffer.size());
    }

    @Test
    void route_hashPrefix_delegatesToFarmMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "#帮助");

        ModeOutcome outcome = router.route(msg);

        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("帮帮农场"));
    }

    @Test
    void route_slashPrefix_delegatesToSystemCommandMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "/help");

        ModeOutcome outcome = router.route(msg);

        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("系统命令"));
    }

    @Test
    void route_modeChat_persistsAndSwitchesMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "/mode chat");

        router.route(msg);

        verify(sender).sendText(eq("user1"), contains("已切换到 chat"));
        PlayerSession session = sessionManager.getOrCreate("user1");
        assertEquals(BotModeType.CHAT, session.getCurrentMode());
    }

    @Test
    void route_modeClaude_switchesToClaudeMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "/mode claude");

        router.route(msg);

        verify(sender).sendText(eq("user1"), contains("Claude Bridge"));
        PlayerSession session = sessionManager.getOrCreate("user1");
        assertEquals(BotModeType.CLAUDE, session.getCurrentMode());
    }

    @Test
    void route_modeFarm_rejectsWithoutChanging() throws Exception {
        WeixinMessage msg = textMessage("user1", "/mode farm");

        router.route(msg);

        verify(sender).sendText(eq("user1"), contains("无需切换"));
    }

    @Test
    void route_normalText_defaultChat_delegatesToChatMode() throws Exception {
        when(taskHandler.tryHandleTaskText(anyString(), anyString())).thenReturn(false);
        WeixinMessage msg = textMessage("user1", "你好");

        router.route(msg);

        verify(taskHandler).tryHandleTaskText("user1", "你好");
        verify(llmProvider, timeout(1000)).chat(anyList());
    }

    @Test
    void route_normalText_withPendingTicket_delegatesToReview() throws Exception {
        when(taskHandler.tryHandleTaskText("user1", "点评")).thenReturn(true);
        WeixinMessage msg = textMessage("user1", "点评");

        router.route(msg);

        verify(taskHandler).tryHandleTaskText("user1", "点评");
        verifyNoInteractions(llmProvider);
    }

    @Test
    void route_normalText_noPendingTicket_fallsToCurrentMode() throws Exception {
        when(taskHandler.tryHandleTaskText(anyString(), anyString())).thenReturn(false);
        WeixinMessage msg = textMessage("user1", "你好");

        router.route(msg);

        verify(taskHandler).tryHandleTaskText("user1", "你好");
        verify(llmProvider, timeout(1000)).chat(anyList());
    }

    @Test
    void route_emptyText_skips() {
        WeixinMessage msg = textMessage("user1", "");

        ModeOutcome outcome = router.route(msg);

        assertEquals(ModeOutcome.Status.SKIP, outcome.getStatus());
        verifyNoInteractions(sender);
    }

    @Test
    void route_nullItemList_skips() {
        WeixinMessage msg = mock(WeixinMessage.class);
        when(msg.getFrom_user_id()).thenReturn("user1");
        when(msg.getItem_list()).thenReturn(null);

        ModeOutcome outcome = router.route(msg);

        assertEquals(ModeOutcome.Status.SKIP, outcome.getStatus());
    }

    @Test
    void route_nullUserId_skips() {
        WeixinMessage msg = mock(WeixinMessage.class);
        when(msg.getFrom_user_id()).thenReturn(null);
        when(msg.getItem_list()).thenReturn(null);

        ModeOutcome outcome = router.route(msg);

        assertEquals(ModeOutcome.Status.SKIP, outcome.getStatus());
    }

    @Test
    void route_hashOnly_sendsHint() throws Exception {
        WeixinMessage msg = textMessage("user1", "#");

        ModeOutcome outcome = router.route(msg);

        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("帮助"));
    }

    @Test
    void route_fullWidthHash_delegatesToFarmMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "＃帮助");

        ModeOutcome outcome = router.route(msg);

        assertTrue(outcome.isHandled());
        verify(sender).sendText(eq("user1"), contains("帮帮农场"));
    }

    @Test
    void route_unknownSlashCommand_sendsUnknownHint() throws Exception {
        WeixinMessage msg = textMessage("user1", "/xyz");

        router.route(msg);

        verify(sender).sendText(eq("user1"), contains("未知命令"));
    }

    @Test
    void route_statusCommand_showsCurrentMode() throws Exception {
        WeixinMessage msg = textMessage("user1", "/status");

        router.route(msg);

        verify(sender).sendText(eq("user1"), contains("当前模式：chat"));
    }

    @Test
    void route_text_firesOnTextReceivedHook_withUserAndText() {
        CapturingHook captor = new CapturingHook(HookEvent.ON_TEXT_RECEIVED);
        ctx.hooks().register(captor);

        router.route(textMessage("user1", "#帮助"));

        assertTrue(captor.invoked);
        assertEquals("user1", captor.ctx.getUserId());
        assertEquals("#帮助", captor.ctx.getText());
    }

    @Test
    void route_rateLimited_sendsBusyMessage() throws Exception {
        when(taskHandler.tryHandleTaskText(anyString(), anyString())).thenReturn(false);
        RateLimiter limiter = new RateLimiter(1, 60_000L);
        ctx.hooks().register(new RateLimitHook(limiter, sender));
        ModeRouter limitedRouter = new ModeRouter(ctx,
                new ChatMode(), new FarmMode(), new SystemCommandMode(),
                new ReviewMode(taskHandler), null, null);

        limitedRouter.route(textMessage("user9", "first"));  // 放行
        limitedRouter.route(textMessage("user9", "second")); // 超限

        verify(sender).sendText(eq("user9"), contains("请求过于频繁"));
    }

    private WeixinMessage textMessage(String userId, String text) {
        TextItem textItem = new TextItem();
        textItem.setText(text);
        MessageItem item = new MessageItem();
        item.setText_item(textItem);
        return msgWithUserId(userId, item);
    }

    private WeixinMessage msgWithUserId(String userId, MessageItem... items) {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id(userId);
        List<MessageItem> list = new ArrayList<MessageItem>();
        Collections.addAll(list, items);
        msg.setItem_list(list);
        return msg;
    }

    /** 捕获某事件负载的放行型测试 hook（断言 ON_TEXT_RECEIVED 经 ModeRouter 真实触发）。 */
    static final class CapturingHook implements BotHook {
        private final HookEvent event;
        boolean invoked;
        HookContext ctx;

        CapturingHook(HookEvent event) {
            this.event = event;
        }

        @Override
        public HookEvent event() {
            return event;
        }

        @Override
        public HookVerdict handle(HookContext ctx) {
            this.invoked = true;
            this.ctx = ctx;
            return HookVerdict.continue_();
        }
    }
}
