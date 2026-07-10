package com.github.wechat.ilink.bot;

import com.github.wechat.ilink.bot.command.CommandRegistry;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.config.ReliabilityConfig;
import com.github.wechat.ilink.bot.config.TaskConfig;
import com.github.wechat.ilink.bot.engine.CommandParser;
import com.github.wechat.ilink.bot.engine.GameEngine;
import com.github.wechat.ilink.bot.engine.ResponseRenderer;
import com.github.wechat.ilink.bot.farm.FarmGame;
import com.github.wechat.ilink.bot.llm.ChatHistoryManager;
import com.github.wechat.ilink.bot.llm.LlmProvider;
import com.github.wechat.ilink.bot.llm.LlmRequestQueue;
import com.github.wechat.ilink.bot.mcp.McpClient;
import com.github.wechat.ilink.bot.mcp.McpToolRegistry;
import com.github.wechat.ilink.bot.mode.ClaudeBridgeMode;
import com.github.wechat.ilink.bot.mode.claude.BridgeFileBuffer;
import com.github.wechat.ilink.bot.mode.claude.BridgeWorkspace;
import com.github.wechat.ilink.bot.mode.claude.ClaudeCodeAdapter;
import com.github.wechat.ilink.bot.mode.hook.HookRegistry;
import com.github.wechat.ilink.bot.persistence.*;
import com.github.wechat.ilink.bot.session.SessionManager;
import com.github.wechat.ilink.bot.task.TaskMessageHandler;
import com.github.wechat.ilink.bot.util.AppPaths;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class BotInstance {

    private static final Logger log = LoggerFactory.getLogger(BotInstance.class);

    private static final int MAX_LOGIN_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 1_000L;

    private final String name;
    private final ILinkClient client;
    private final GameBot bot;
    private final LlmRequestQueue llmQueue;
    private final SessionManager sessionManager;
    private final BridgeFileBuffer bridgeFileBuffer;
    private final BotSessionRepository sessionRepo;
    private final boolean resumed;
    private volatile String pendingWelcomeUserId;

    BotInstance(String name, ILinkClient client, GameBot bot,
                LlmRequestQueue llmQueue, SessionManager sessionManager,
                BridgeFileBuffer bridgeFileBuffer) {
        this(name, client, bot, llmQueue, sessionManager, bridgeFileBuffer, null, false);
    }

    BotInstance(String name, ILinkClient client, GameBot bot,
                LlmRequestQueue llmQueue, SessionManager sessionManager,
                BridgeFileBuffer bridgeFileBuffer,
                BotSessionRepository sessionRepo, boolean resumed) {
        this.name = name;
        this.client = client;
        this.bot = bot;
        this.llmQueue = llmQueue;
        this.sessionManager = sessionManager;
        this.bridgeFileBuffer = bridgeFileBuffer;
        this.sessionRepo = sessionRepo;
        this.resumed = resumed;
    }

    public String getName() {
        return name;
    }

    /** 透传内部 {@code GameBot} 的 hook 注册表，供应用层触发 ON_STARTUP/ON_SHUTDOWN 等。 */
    public HookRegistry hooks() {
        return bot.hooks();
    }

    public void start() throws Exception {
        if (resumed && tryResume()) {
            return;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_LOGIN_ATTEMPTS; attempt++) {
            try {
                String qrCodeUrl = client.executeLogin();
                log.info("[{}] 第 {}/{} 次登录尝试，请扫码：{}", name, attempt, MAX_LOGIN_ATTEMPTS, qrCodeUrl);
                client.getLoginFuture().get();
                log.info("[{}] 登录成功", name);
                return;
            } catch (Exception e) {
                last = e;
                String reason = describeLoginFailure(e);
                if (attempt < MAX_LOGIN_ATTEMPTS) {
                    log.warn("[{}] 第 {} 次登录失败（{}），{}ms 后刷新二维码重试",
                            name, attempt, reason, RETRY_DELAY_MS);
                    Thread.sleep(RETRY_DELAY_MS);
                } else {
                    log.error("[{}] 登录重试 {} 次仍失败（{}）", name, MAX_LOGIN_ATTEMPTS, reason, e);
                }
            }
        }
        throw last;
    }

    /** 用持久化的 token 探测一次拉取，成功则复用已登录会话，失败则清除并回落扫码。 */
    private boolean tryResume() {
        try {
            client.getUpdates();
            log.info("[{}] 已复用已登录会话，跳过扫码", name);
            return true;
        } catch (Exception e) {
            log.warn("[{}] 会话恢复失败（{}），清除并重新扫码", name, describeLoginFailure(e));
            if (sessionRepo != null) {
                sessionRepo.clear(name);
            }
            return false;
        }
    }

    static LoginContext toLoginContext(BotSessionRecord r) {
        return new LoginContext(r.getBotToken(), r.getUserId(), r.getBotId(), r.getBaseUrl());
    }

    static ResumeContext toResumeContext(BotSessionRecord r) {
        return ResumeContext.builder(toLoginContext(r))
                .updatesCursor(r.getUpdatesCursor())
                .build();
    }

    static BotSessionRecord toRecord(String name, LoginContext ctx, String updatesCursor) {
        return new BotSessionRecord(name, ctx.getBotToken(), ctx.getUserId(),
                ctx.getBotId(), ctx.getBaseUrl(), updatesCursor);
    }

    static String describeLoginFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null) {
                if (msg.contains("qrcode expired")) return "二维码过期";
                if (msg.contains("login timeout")) return "登录超时";
                if (msg.contains("login cancelled")) return "登录取消";
                if (msg.contains("login polling failed")) return "网络错误";
            }
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName();
    }

    public void pollUpdates() throws IOException {
        sendWelcomeIfNeeded();
        client.getUpdates();
    }

    public void shutdown() {
        if (llmQueue != null) {
            llmQueue.shutdown();
        }
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
        if (bridgeFileBuffer != null) {
            bridgeFileBuffer.shutdown();
        }
        persistSession();
        if (client != null) {
            client.close();
        }
        log.info("[{}] 已关闭", name);
    }

    /** 关闭前导出最新会话（含消息游标）落库，供下次重启免扫码恢复。 */
    private void persistSession() {
        if (sessionRepo == null || client == null || !client.isLoggedIn()) {
            return;
        }
        try {
            ResumeContext rc = client.exportResumeContext();
            if (rc != null && rc.getLoginContext() != null) {
                sessionRepo.save(toRecord(name, rc.getLoginContext(), rc.getUpdatesCursor()));
            }
        } catch (Exception e) {
            log.warn("[{}] 会话持久化失败，下次启动可能需要重新扫码", name, e);
        }
    }

    public boolean isAlive() {
        return client.isLoggedIn();
    }

    public static BotInstance create(BotConfig config, DatabaseManager dbManager,
                                     SessionManager sessionManager, LlmProvider llmProvider,
                                     ChatHistoryManager chatHistory, LlmRequestQueue llmQueue,
                                     boolean streamingEnabled, int typingIntervalMs,
                                     QrCodeProvider qrCodeProvider, TaskMessageHandler taskHandler,
                                     TaskConfig taskConfig) {
        return create(config, dbManager, sessionManager, llmProvider, chatHistory, llmQueue,
                streamingEnabled, typingIntervalMs, qrCodeProvider, taskHandler, taskConfig,
                null, null, new ReliabilityConfig());
    }

    public static BotInstance create(BotConfig config, DatabaseManager dbManager,
                                     SessionManager sessionManager, LlmProvider llmProvider,
                                     ChatHistoryManager chatHistory, LlmRequestQueue llmQueue,
                                     boolean streamingEnabled, int typingIntervalMs,
                                     QrCodeProvider qrCodeProvider, TaskMessageHandler taskHandler,
                                     TaskConfig taskConfig,
                                     McpClient mcpClient, McpToolRegistry mcpToolRegistry,
                                     ReliabilityConfig reliability) {
        if (reliability == null) {
            reliability = new ReliabilityConfig();
        }
        CommandRegistry registry = new CommandRegistry();
        ResponseRenderer renderer = new ResponseRenderer();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);

        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sessionManager, registry);

        ClaudeSessionRepository claudeRepo = new ClaudeSessionRepository(dbManager);
        ClaudeModeBuild claudeBuild = buildClaudeMode(taskConfig, claudeRepo);
        MessageDedupRepository dedupRepo = new MessageDedupRepository(dbManager);

        final GameBot bot = new GameBot(engine, renderer, llmProvider, chatHistory, sessionManager,
                streamingEnabled, typingIntervalMs, llmQueue, taskHandler, claudeBuild.mode, claudeRepo,
                mcpClient, mcpToolRegistry, reliability, claudeAdminUsers(taskConfig), dedupRepo,
                adminDefaultPrivileged(taskConfig));

        ILinkConfig.Builder configBuilder = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000);
        if (config.getRouteTag() != null && !config.getRouteTag().isEmpty()) {
            configBuilder.routeTag(config.getRouteTag());
        }
        ILinkConfig clientConfig = configBuilder.build();

        final BotInstance[] selfRef = new BotInstance[1];
        final BotSessionRepository sessionRepo = new BotSessionRepository(dbManager);
        final BotSessionRecord saved = sessionRepo.load(config.getName());

        ILinkClientBuilder builder = ILinkClient.builder()
                .config(clientConfig)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("[{}] 登录成功, botId={}", config.getName(), ctx.getBotId());
                        sessionRepo.save(toRecord(config.getName(), ctx, null));
                        if (selfRef[0] != null) {
                            selfRef[0].pendingWelcomeUserId = ctx.getUserId();
                        }
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("[{}] 登录失败", config.getName(), throwable);
                    }
                })
                .onMessage(bot);
        if (saved != null) {
            builder.resumeContext(toResumeContext(saved));
            log.info("[{}] 检测到已保存会话，启动时尝试免扫码恢复", config.getName());
        }
        ILinkClient client = builder.build();

        bot.setClient(client);
        if (taskHandler != null) {
            taskHandler.setClient(client);
        }

        FarmGame farmGame = new FarmGame(registry, rankRepo, dbManager, qrCodeProvider);
        farmGame.registerCommands();

        BotInstance instance = new BotInstance(config.getName(), client, bot,
                llmQueue, sessionManager, claudeBuild.buffer, sessionRepo, saved != null);
        selfRef[0] = instance;

        return instance;
    }

    public static DynamicBotResult createDynamic(
            DatabaseManager dbManager, SessionManager sessionManager,
            LlmProvider llmProvider, ChatHistoryManager chatHistory,
            LlmRequestQueue llmQueue, boolean streamingEnabled, int typingIntervalMs,
            QrCodeProvider qrCodeProvider, Runnable onReady, TaskMessageHandler taskHandler,
            TaskConfig taskConfig) {
        return createDynamic(dbManager, sessionManager, llmProvider, chatHistory,
                llmQueue, streamingEnabled, typingIntervalMs, qrCodeProvider, onReady,
                taskHandler, taskConfig, null, null, new ReliabilityConfig());
    }

    public static DynamicBotResult createDynamic(
            DatabaseManager dbManager, SessionManager sessionManager,
            LlmProvider llmProvider, ChatHistoryManager chatHistory,
            LlmRequestQueue llmQueue, boolean streamingEnabled, int typingIntervalMs,
            QrCodeProvider qrCodeProvider, Runnable onReady, TaskMessageHandler taskHandler,
            TaskConfig taskConfig,
            McpClient mcpClient, McpToolRegistry mcpToolRegistry,
            ReliabilityConfig reliability) {
        if (reliability == null) {
            reliability = new ReliabilityConfig();
        }

        CommandRegistry registry = new CommandRegistry();
        ActionRankRepository rankRepo = new ActionRankRepository(dbManager);
        ResponseRenderer renderer = new ResponseRenderer();
        CommandParser parser = new CommandParser(registry);
        GameEngine engine = new GameEngine(parser, sessionManager, registry);

        ClaudeSessionRepository claudeRepo = new ClaudeSessionRepository(dbManager);
        ClaudeModeBuild claudeBuild = buildClaudeMode(taskConfig, claudeRepo);
        MessageDedupRepository dedupRepo = new MessageDedupRepository(dbManager);

        final GameBot bot = new GameBot(engine, renderer, llmProvider, chatHistory, sessionManager,
                streamingEnabled, typingIntervalMs, llmQueue, taskHandler, claudeBuild.mode, claudeRepo,
                mcpClient, mcpToolRegistry, reliability, claudeAdminUsers(taskConfig), dedupRepo,
                adminDefaultPrivileged(taskConfig));

        ILinkConfig clientConfig = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(30000)
                .build();

        final BotInstance[] selfRef = new BotInstance[1];

        ILinkClient client = ILinkClient.builder()
                .config(clientConfig)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("[dynamic] 登录成功, botId={}", ctx.getBotId());
                        if (selfRef[0] != null) {
                            selfRef[0].pendingWelcomeUserId = ctx.getUserId();
                        }
                        if (onReady != null) {
                            onReady.run();
                        }
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("[dynamic] 登录失败", throwable);
                    }
                })
                .onMessage(bot)
                .build();

        bot.setClient(client);
        if (taskHandler != null) {
            taskHandler.setClient(client);
        }

        FarmGame farmGame = new FarmGame(registry, rankRepo, dbManager, qrCodeProvider);
        farmGame.registerCommands();

        String qrCodeUrl = client.executeLogin();

        BotInstance instance = new BotInstance("dynamic", client, bot,
                llmQueue, sessionManager, claudeBuild.buffer);
        selfRef[0] = instance;

        return new DynamicBotResult(instance, qrCodeUrl);
    }

    static ClaudeModeBuild buildClaudeMode(TaskConfig taskConfig, ClaudeSessionRepository claudeRepo) {
        if (taskConfig == null) {
            return new ClaudeModeBuild(
                    new ClaudeBridgeMode(null, claudeRepo, AppPaths.data("claude-workspace"), ""), null);
        }
        String model = taskConfig.getClaudeBridgeModel();
        if (!taskConfig.isClaudeBridgeEnabled()) {
            return new ClaudeModeBuild(
                    new ClaudeBridgeMode(null, claudeRepo, taskConfig.getClaudeBridgeCwd(), model), null);
        }
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(taskConfig);
        BridgeFileBuffer fileBuffer = new BridgeFileBuffer(
                taskConfig.getBufferTtlMs(), taskConfig.getMaxVideoBytes());
        fileBuffer.startCleanup();
        BridgeWorkspace workspace = new BridgeWorkspace(taskConfig.getClaudeBridgeCwd());
        ClaudeBridgeMode mode = new ClaudeBridgeMode(adapter, claudeRepo, taskConfig.getClaudeBridgeCwd(), model,
                fileBuffer, workspace, taskConfig.getClaudeBridgeCompactThreshold());
        return new ClaudeModeBuild(mode, fileBuffer);
    }

    /** {@link #buildClaudeMode} 的产物：装配好的模式 + 需在关闭时清理的文件缓冲（bridge 未启用时为 null）。 */
    static class ClaudeModeBuild {
        final ClaudeBridgeMode mode;
        final BridgeFileBuffer buffer;

        ClaudeModeBuild(ClaudeBridgeMode mode, BridgeFileBuffer buffer) {
            this.mode = mode;
            this.buffer = buffer;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    /** 由 TaskConfig.claudeAdminUsers 派生可执行 /sudo 的微信 userId 白名单；taskConfig 为空时返回空集。 */
    private static Set<String> claudeAdminUsers(TaskConfig taskConfig) {
        if (taskConfig == null || taskConfig.getClaudeAdminUsers() == null) {
            return new HashSet<String>();
        }
        return new HashSet<String>(taskConfig.getClaudeAdminUsers());
    }

    /** 管理员默认提权开关；taskConfig 为空时默认关闭。 */
    private static boolean adminDefaultPrivileged(TaskConfig taskConfig) {
        return taskConfig != null && taskConfig.isClaudeBridgeAdminDefaultPrivileged();
    }

    private void sendWelcomeIfNeeded() {
        String userId = pendingWelcomeUserId;
        if (userId == null) {
            return;
        }
        pendingWelcomeUserId = null;
        bot.sendWelcome(userId);
    }

    public static class DynamicBotResult {
        private final BotInstance instance;
        private final String qrCodeUrl;

        DynamicBotResult(BotInstance instance, String qrCodeUrl) {
            this.instance = instance;
            this.qrCodeUrl = qrCodeUrl;
        }

        public BotInstance getInstance() {
            return instance;
        }

        public String getQrCodeUrl() {
            return qrCodeUrl;
        }
    }
}
