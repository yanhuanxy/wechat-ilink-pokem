package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.config.ReliabilityConfig;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
import com.github.wechat.ilink.bot.llm.ChatHistoryManager;
import com.github.wechat.ilink.bot.llm.LlmProvider;
import com.github.wechat.ilink.bot.llm.LlmRequestQueue;
import com.github.wechat.ilink.bot.mode.ChatMode;
import com.github.wechat.ilink.bot.mode.hook.BotHook;
import com.github.wechat.ilink.bot.mode.hook.HookContext;
import com.github.wechat.ilink.bot.mode.hook.HookEvent;
import com.github.wechat.ilink.bot.mode.hook.HookVerdict;
import com.github.wechat.ilink.bot.persistence.ActionRankRepository;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.persistence.MessageDedupRepository;
import com.github.wechat.ilink.bot.session.SessionManager;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameBotTest {

    @TempDir
    File tempDir;

    private ILinkClient client;
    private GameBot bot;
    private DatabaseManager dbManager;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        String dbPath = new File(tempDir, "test.db").getAbsolutePath();
        dbManager = new DatabaseManager(dbPath);
        dbManager.initialize();

        client = mock(ILinkClient.class);
        sessionManager = new SessionManager(dbManager);
        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-qr-data"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sessionManager, registry);
        ResponseRenderer renderer = new ResponseRenderer();

        bot = new GameBot(engine, renderer, null, new ChatHistoryManager(20), sessionManager,
                false, 5000, new LlmRequestQueue(3, 50));
        bot.setClient(client);
    }

    @Test
    void onMessages_noHashPrefix_noLlm_echoesText() throws Exception {
        WeixinMessage msg = createTextMessage("user1", "你好啊");
        bot.onMessages(list(msg));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).sendText(eq("user1"), captor.capture());
        assertEquals("你好啊", captor.getValue());
    }

    @Test
    void onMessages_noHashPrefix_withLlm_callsLlmAndSendsReply() throws Exception {
        LlmProvider llmProvider = mock(LlmProvider.class);
        when(llmProvider.chat(anyList())).thenReturn("这是AI的回复");

        String dbPath = new File(tempDir, "test2.db").getAbsolutePath();
        DatabaseManager db2 = new DatabaseManager(dbPath);
        db2.initialize();
        SessionManager sm2 = new SessionManager(db2);

        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(db2);
        new FarmGame(registry, rankRepo, db2, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-qr-data"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sm2, registry);
        ChatHistoryManager history = new ChatHistoryManager(20);

        GameBot llmBot = new GameBot(engine, new ResponseRenderer(), llmProvider, history, sm2,
                false, 5000, new LlmRequestQueue(3, 50));
        llmBot.setClient(client);

        WeixinMessage msg = createTextMessage("user1", "你好");
        llmBot.onMessages(list(msg));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(2000)).sendTextWithTyping(eq("user1"), captor.capture(), eq(3000L));
        assertEquals("这是AI的回复", captor.getValue());
        verify(llmProvider, timeout(2000)).chat(anyList());

        db2.close();
    }

    @Test
    void onMessages_noHashPrefix_llmError_sendsFallback() throws Exception {
        LlmProvider llmProvider = mock(LlmProvider.class);
        when(llmProvider.chat(anyList())).thenReturn(null);

        String dbPath = new File(tempDir, "test3.db").getAbsolutePath();
        DatabaseManager db3 = new DatabaseManager(dbPath);
        db3.initialize();
        SessionManager sm3 = new SessionManager(db3);

        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(db3);
        new FarmGame(registry, rankRepo, db3, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-qr-data"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sm3, registry);

        GameBot llmBot = new GameBot(engine, new ResponseRenderer(), llmProvider, new ChatHistoryManager(20), sm3,
                false, 5000, new LlmRequestQueue(3, 50));
        llmBot.setClient(client);

        WeixinMessage msg = createTextMessage("user1", "你好");
        llmBot.onMessages(list(msg));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(2000)).sendText(eq("user1"), captor.capture());
        assertTrue(captor.getValue().contains("暂时无法回复"));

        db3.close();
    }

    @Test
    void onMessages_hashPrefix_dispatchesCommand() throws Exception {
        WeixinMessage msg = createTextMessage("user1", "#帮助");
        bot.onMessages(list(msg));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).sendText(eq("user1"), captor.capture());
        assertTrue(captor.getValue().contains("帮帮农场"));
    }

    @Test
    void onMessages_hashWithArg_dispatchesWithArg() throws Exception {
        WeixinMessage msg = createTextMessage("user1", "#商店");
        bot.onMessages(list(msg));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).sendText(eq("user1"), captor.capture());
        assertTrue(captor.getValue().contains("种子商店"));
    }

    @Test
    void onMessages_nullItemList_skips() throws Exception {
        WeixinMessage msg = mock(WeixinMessage.class);
        when(msg.getItem_list()).thenReturn(null);
        bot.onMessages(list(msg));

        verify(client, never()).sendText(anyString(), anyString());
    }

    @Test
    void onMessages_emptyText_skips() throws Exception {
        WeixinMessage msg = createTextMessage("user1", "");
        bot.onMessages(list(msg));

        verify(client, never()).sendText(anyString(), anyString());
    }

    @Test
    void onMessages_hashOnly_sendsHint() throws Exception {
        WeixinMessage msg = createTextMessage("user1", "#");
        bot.onMessages(list(msg));

        verify(client).sendText(eq("user1"), contains("帮助"));
    }

    @Test
    void buildSystemPrompt_containsGameState() throws Exception {
        SessionManager sm = new SessionManager(dbManager);
        sm.getOrCreate("testUser");
        String prompt = ChatMode.buildSystemPrompt(sm.getOrCreate("testUser"));

        assertTrue(prompt.contains("500"));
        assertTrue(prompt.contains("Lv.1"));
        assertTrue(prompt.contains("4/36"));
    }

    @Test
    void onMessages_shareCommand_sendsQrCodeImage() throws Exception {
        String testUrl = "https://liteapp.weixin.qq.com/q/test?qrcode=abc&bot_type=3";
        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return testUrl; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sessionManager, registry);
        GameBot shareBot = new GameBot(engine, new ResponseRenderer(), null,
                new ChatHistoryManager(20), sessionManager, false, 5000, new LlmRequestQueue(3, 50));
        shareBot.setClient(client);

        WeixinMessage msg = createTextMessage("user1", "#分享");
        shareBot.onMessages(list(msg));

        ArgumentCaptor<byte[]> imageCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(client).sendImage(eq("user1"), imageCaptor.capture(), eq("bot-qrcode.png"), anyString());

        byte[] png = imageCaptor.getValue();
        assertTrue(png.length > 100);
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals(0x50, png[1] & 0xFF);
    }

    @Test
    void sendWelcome_dispatchesMenuAndSendsResponse() throws Exception {
        bot.sendWelcome("user1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(client).sendText(eq("user1"), captor.capture());
        assertTrue(captor.getValue().contains("帮帮农场"));
    }

    @Test
    void sendWelcome_clientThrowsException_doesNotPropagate() throws Exception {
        doThrow(new IOException("connection lost")).when(client).sendText(anyString(), anyString());

        bot.sendWelcome("user1");

        verify(client).sendText(eq("user1"), anyString());
    }

    @Test
    void onMessages_routerThrowsException_sendsFallbackAndDoesNotPropagate() throws Exception {
        WeixinMessage msg = mock(WeixinMessage.class);
        when(msg.getFrom_user_id()).thenReturn("user1");
        when(msg.getItem_list()).thenThrow(new RuntimeException("router boom"));

        bot.onMessages(list(msg));

        verify(client).sendText(eq("user1"), contains("出了点问题"));
    }

    @Test
    void downloadImage_delegatesToClient() throws Exception {
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        when(client.downloadImageFromMessageItem(item)).thenReturn(new byte[]{9, 9});

        byte[] result = bot.downloadImage(item);

        assertArrayEquals(new byte[]{9, 9}, result);
        verify(client).downloadImageFromMessageItem(item);
    }

    @Test
    void downloadFile_delegatesToClient() throws Exception {
        MessageItem item = new MessageItem();
        item.setFile_item(new FileItem());
        when(client.downloadFileFromMessageItem(item)).thenReturn(new byte[]{7});

        byte[] result = bot.downloadFile(item);

        assertArrayEquals(new byte[]{7}, result);
        verify(client).downloadFileFromMessageItem(item);
    }

    @Test
    void sendFile_delegatesToClient() throws Exception {
        byte[] data = new byte[]{1, 2};

        bot.sendFile("user1", data, "a.txt", "cap");

        verify(client).sendFile("user1", data, "a.txt", "cap");
    }

    @Test
    void sendVideo_delegatesToClient() throws Exception {
        byte[] data = new byte[]{3, 4};

        bot.sendVideo("user1", data, "a.mp4", 5000, "cap");

        verify(client).sendVideo("user1", data, "a.mp4", 5000, "cap");
    }

    @Test
    void sendText_firesPreSendHook_withTextKind() throws Exception {
        CapturingHook captor = new CapturingHook(HookEvent.PRE_SEND);
        bot.hooks().register(captor);

        bot.sendText("u1", "hi");

        assertTrue(captor.invoked);
        assertEquals("text", captor.ctx.getSendKind());
        assertEquals("hi", captor.ctx.getText());
        verify(client).sendText("u1", "hi");
    }

    @Test
    void sendImage_firesPreSendHook_locksMediaFormat() throws Exception {
        CapturingHook captor = new CapturingHook(HookEvent.PRE_SEND);
        bot.hooks().register(captor);
        byte[] bytes = new byte[]{1, 2, 3};

        bot.sendImage("u1", bytes, "f.png", "cap");

        assertTrue(captor.invoked);
        assertEquals("image", captor.ctx.getSendKind());
        // 锁住出向媒体审计格式：迁移前后契约 = "[fileName] caption"
        assertEquals("[f.png] cap", captor.ctx.getText());
        verify(client).sendImage("u1", bytes, "f.png", "cap");
    }

    @Test
    void sendText_preSendBlocked_skipsClientAndDoesNotThrow() throws Exception {
        bot.hooks().register(new BlockingHook(HookEvent.PRE_SEND, "filtered"));

        assertDoesNotThrow(() -> bot.sendText("u1", "hi"));

        verify(client, never()).sendText(anyString(), anyString());
    }

    @Test
    void onMessages_duplicateMessageId_processedOnlyOnce() throws Exception {
        GameBot dedupBot = dedupBot(new MessageDedupRepository(dbManager));
        WeixinMessage first = createTextMessage("user1", "你好啊", 100L);
        WeixinMessage redelivered = createTextMessage("user1", "你好啊", 100L);

        dedupBot.onMessages(list(first));
        dedupBot.onMessages(list(redelivered));

        // 第二次为 resume 重投的旧消息（同 message_id）→ 被跳过，只回复一次
        verify(client, times(1)).sendText(eq("user1"), eq("你好啊"));
    }

    @Test
    void onMessages_higherMessageId_isProcessed() throws Exception {
        GameBot dedupBot = dedupBot(new MessageDedupRepository(dbManager));
        dedupBot.onMessages(list(createTextMessage("user1", "第一条", 100L)));
        dedupBot.onMessages(list(createTextMessage("user1", "第二条", 101L)));

        verify(client, times(1)).sendText(eq("user1"), eq("第一条"));
        verify(client, times(1)).sendText(eq("user1"), eq("第二条"));
    }

    @Test
    void onMessages_nullMessageId_notDeduped() throws Exception {
        GameBot dedupBot = dedupBot(new MessageDedupRepository(dbManager));
        // message_id 为 null 无法判定重复 → 两次都放行处理
        dedupBot.onMessages(list(createTextMessage("user1", "无id", null)));
        dedupBot.onMessages(list(createTextMessage("user1", "无id", null)));

        verify(client, times(2)).sendText(eq("user1"), eq("无id"));
    }

    private GameBot dedupBot(MessageDedupRepository dedup) {
        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        new FarmGame(registry, rankRepo, dbManager, new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() { return "test-qr-data"; }
        }).registerCommands();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sessionManager, registry);
        GameBot dedupBot = new GameBot(engine, new ResponseRenderer(), null, new ChatHistoryManager(20),
                sessionManager, false, 5000, new LlmRequestQueue(3, 50), null, null, null,
                null, null, new ReliabilityConfig(), Collections.<String>emptySet(), dedup, false);
        dedupBot.setClient(client);
        return dedupBot;
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    private List<WeixinMessage> list(WeixinMessage msg) {
        List<WeixinMessage> messages = new ArrayList<WeixinMessage>();
        messages.add(msg);
        return messages;
    }

    private WeixinMessage createTextMessage(String userId, String text) {
        return createTextMessage(userId, text, null);
    }

    private WeixinMessage createTextMessage(String userId, String text, Long messageId) {
        WeixinMessage msg = mock(WeixinMessage.class);
        when(msg.getFrom_user_id()).thenReturn(userId);
        when(msg.getMessage_id()).thenReturn(messageId);

        TextItem textItem = mock(TextItem.class);
        when(textItem.getText()).thenReturn(text);

        MessageItem item = mock(MessageItem.class);
        when(item.getText_item()).thenReturn(textItem);

        List<MessageItem> items = new ArrayList<MessageItem>();
        items.add(item);
        when(msg.getItem_list()).thenReturn(items);

        return msg;
    }

    /** 捕获某事件负载的放行型测试 hook（断言 PRE_SEND 经 GameBot 真实触发）。 */
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

    /** 阻断型测试 hook（返回 BLOCK，验证 preSend 阻断语义）。 */
    static final class BlockingHook implements BotHook {
        private final HookEvent event;
        private final String reason;

        BlockingHook(HookEvent event, String reason) {
            this.event = event;
            this.reason = reason;
        }

        @Override
        public HookEvent event() {
            return event;
        }

        @Override
        public HookVerdict handle(HookContext ctx) {
            return HookVerdict.block(reason);
        }
    }
}
