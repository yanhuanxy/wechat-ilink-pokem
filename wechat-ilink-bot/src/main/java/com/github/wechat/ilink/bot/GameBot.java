package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.config.HookConfig;
import com.github.wechat.ilink.bot.config.ReliabilityConfig;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.llm.ChatHistoryManager;
import com.github.wechat.ilink.bot.llm.LlmProvider;
import com.github.wechat.ilink.bot.llm.LlmRequestQueue;
import com.github.wechat.ilink.bot.mcp.McpClient;
import com.github.wechat.ilink.bot.mcp.McpToolRegistry;
import com.github.wechat.ilink.bot.mode.*;
import com.github.wechat.ilink.bot.mode.hook.*;
import com.github.wechat.ilink.bot.persistence.ClaudeSessionRepository;
import com.github.wechat.ilink.bot.session.SessionManager;
import com.github.wechat.ilink.bot.task.TaskMessageHandler;
import com.github.wechat.ilink.bot.util.AppPaths;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class GameBot implements OnMessageListener, ModeSender, MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(GameBot.class);

    private volatile ILinkClient client;
    private final ModeRouter router;
    private final GameEngine engine;
    private final ResponseRenderer renderer;
    private final HookRegistry hooks;

    public GameBot(GameEngine engine, ResponseRenderer renderer,
                   LlmProvider llmProvider, ChatHistoryManager chatHistory,
                   SessionManager sessions, boolean streamingEnabled,
                   int typingIntervalMs, LlmRequestQueue llmQueue) {
        this(engine, renderer, llmProvider, chatHistory, sessions,
                streamingEnabled, typingIntervalMs, llmQueue, null, null, null);
    }

    public GameBot(GameEngine engine, ResponseRenderer renderer,
                   LlmProvider llmProvider, ChatHistoryManager chatHistory,
                   SessionManager sessions, boolean streamingEnabled,
                   int typingIntervalMs, LlmRequestQueue llmQueue,
                   TaskMessageHandler taskHandler) {
        this(engine, renderer, llmProvider, chatHistory, sessions,
                streamingEnabled, typingIntervalMs, llmQueue, taskHandler, null, null);
    }

    public GameBot(GameEngine engine, ResponseRenderer renderer,
                   LlmProvider llmProvider, ChatHistoryManager chatHistory,
                   SessionManager sessions, boolean streamingEnabled,
                   int typingIntervalMs, LlmRequestQueue llmQueue,
                   TaskMessageHandler taskHandler, ClaudeBridgeMode claudeMode,
                   ClaudeSessionRepository claudeSessionRepo) {
        this(engine, renderer, llmProvider, chatHistory, sessions,
                streamingEnabled, typingIntervalMs, llmQueue, taskHandler,
                claudeMode, claudeSessionRepo, null, null, new ReliabilityConfig(),
                Collections.<String>emptySet());
    }

    public GameBot(GameEngine engine, ResponseRenderer renderer,
                   LlmProvider llmProvider, ChatHistoryManager chatHistory,
                   SessionManager sessions, boolean streamingEnabled,
                   int typingIntervalMs, LlmRequestQueue llmQueue,
                   TaskMessageHandler taskHandler, ClaudeBridgeMode claudeMode,
                   ClaudeSessionRepository claudeSessionRepo,
                   McpClient mcpClient, McpToolRegistry mcpToolRegistry,
                   ReliabilityConfig reliability, Set<String> claudeAdminUsers) {
        this.engine = engine;
        this.renderer = renderer;
        HookConfig hookConfig = HookConfig.load(AppPaths.data("hooks-config.json"));
        HookRegistry hookRegistry = new HookRegistry();
        if (hookConfig.isAudit()) {
            hookRegistry.register(new InboundAuditHook());
            hookRegistry.register(new OutboundAuditHook());
        }
        this.hooks = hookRegistry;
        ModeSender retrySender = new RetrySender(this,
                reliability.getSendMaxAttempts(), reliability.getSendBackoffMs());
        ModeContext ctx = ModeContext.builder()
                .sender(retrySender).downloader(this).engine(engine).renderer(renderer)
                .llmProvider(llmProvider).chatHistory(chatHistory).llmQueue(llmQueue)
                .sessions(sessions).taskHandler(taskHandler).claudeSessionRepo(claudeSessionRepo)
                .streamingEnabled(streamingEnabled).typingIntervalMs(typingIntervalMs)
                .mcpClient(mcpClient).mcpToolRegistry(mcpToolRegistry).hooks(hookRegistry).build();
        AutogameMode autogameMode = mcpClient != null ? new AutogameMode() : null;
        if (hookConfig.isRateLimit()) {
            RateLimiter rateLimiter = new RateLimiter(reliability.getRateLimitPerMin(), reliability.getRateLimitWindowMs());
            rateLimiter.startCleanup();
            hookRegistry.register(new RateLimitHook(rateLimiter, retrySender));
        }
        this.router = new ModeRouter(ctx,
                new ChatMode(),
                new FarmMode(),
                new SystemCommandMode(claudeAdminUsers),
                new ReviewMode(taskHandler),
                claudeMode,
                autogameMode);
    }

    public void setClient(ILinkClient client) {
        this.client = client;
    }

    /** 生命周期 hook 注册表（供 {@code GameApplication} 触发 ON_STARTUP/ON_SHUTDOWN 等应用级事件）。 */
    public HookRegistry hooks() {
        return hooks;
    }

    @Override
    public void sendText(String userId, String text) throws java.io.IOException {
        if (preSend(userId, "text", text)) {
            client.sendText(userId, text);
        }
    }

    @Override
    public void sendTextWithTyping(String userId, String text, long typingMillis) throws java.io.IOException {
        if (preSend(userId, "text", text)) {
            client.sendTextWithTyping(userId, text, typingMillis);
        }
    }

    @Override
    public void sendImage(String userId, byte[] imageBytes, String fileName, String caption) throws java.io.IOException {
        if (preSend(userId, "image", "[" + fileName + "] " + (caption == null ? "" : caption))) {
            client.sendImage(userId, imageBytes, fileName, caption);
        }
    }

    @Override
    public void sendFile(String userId, byte[] fileBytes, String fileName, String caption) throws java.io.IOException {
        if (preSend(userId, "file", "[" + fileName + "] " + (caption == null ? "" : caption))) {
            client.sendFile(userId, fileBytes, fileName, caption);
        }
    }

    @Override
    public void sendVideo(String userId, byte[] videoBytes, String fileName, Integer playLengthMs, String caption)
            throws java.io.IOException {
        if (preSend(userId, "video", "[" + fileName + "] " + (caption == null ? "" : caption))) {
            client.sendVideo(userId, videoBytes, fileName, playLengthMs, caption);
        }
    }

    /**
     * 触发 {@link HookEvent#PRE_SEND} hook（出向审计 / 内容过滤）。
     * 返回 true=放行发送，false=被阻断（记 WARN 并跳过本次发送；不抛异常，避免触发 {@code RetrySender} 重试）。
     * 审计节奏与迁移前一致：在 GameBot（RetrySender 的 delegate）内逐次 attempt 触发。
     */
    private boolean preSend(String userId, String kind, String content) {
        if (!hooks.has(HookEvent.PRE_SEND)) {
            return true;
        }
        HookVerdict verdict = hooks.fire(HookEvent.PRE_SEND,
                HookContext.builder().userId(userId).sendKind(kind).text(content).build());
        if (verdict.isBlock()) {
            log.warn("出向发送被 hook 阻断, userId={}, kind={}, reason={}", userId, kind, verdict.getReason());
            return false;
        }
        return true;
    }

    @Override
    public byte[] downloadImage(MessageItem item) throws java.io.IOException {
        return client.downloadImageFromMessageItem(item);
    }

    @Override
    public byte[] downloadFile(MessageItem item) throws java.io.IOException {
        return client.downloadFileFromMessageItem(item);
    }

    @Override
    public void startTyping(String userId) throws java.io.IOException {
        client.startTyping(userId);
    }

    @Override
    public void stopTyping(String userId) throws java.io.IOException {
        client.stopTyping(userId);
    }

    public void sendWelcome(String userId) {
        try {
            com.github.wechat.ilink.bot.command.CommandResult result = engine.dispatch(userId, "菜单");
            String response = renderer.render(result);
            if (response != null && !response.isEmpty()) {
                client.sendText(userId, response);
            }
        } catch (Exception e) {
            log.error("发送欢迎消息失败, userId={}", userId, e);
        }
    }

    @Override
    public void onMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String userId = msg.getFrom_user_id();
            long turnStart = System.currentTimeMillis();
            try {
                ModeOutcome outcome = router.route(msg);
                if (outcome.getStatus() == ModeOutcome.Status.HANDLED && outcome.getErrorMessage() != null) {
                    try {
                        client.sendText(userId, outcome.getErrorMessage());
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                log.error("消息处理出错, userId={}", userId, e);
                if (hooks.has(HookEvent.ON_ERROR)) {
                    hooks.fire(HookEvent.ON_ERROR,
                            HookContext.builder().userId(userId).throwable(e).build());
                }
                if (userId != null && !userId.isEmpty()) {
                    try {
                        client.sendText(userId, "出了点问题，请稍后再试");
                    } catch (Exception ignored) {
                    }
                }
            }
            if (hooks.has(HookEvent.ON_TURN_COMPLETE)) {
                hooks.fire(HookEvent.ON_TURN_COMPLETE,
                        HookContext.builder().userId(userId)
                                .durationMs(System.currentTimeMillis() - turnStart).build());
            }
        }
    }
}
