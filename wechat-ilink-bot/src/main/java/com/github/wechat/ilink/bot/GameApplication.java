package com.github.wechat.ilink.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.bot.command.QrCodeProvider;
import com.github.wechat.ilink.bot.config.AutogameConfig;
import com.github.wechat.ilink.bot.config.LlmConfig;
import com.github.wechat.ilink.bot.config.ReliabilityConfig;
import com.github.wechat.ilink.bot.config.TaskConfig;
import com.github.wechat.ilink.bot.llm.*;
import com.github.wechat.ilink.bot.mcp.McpClient;
import com.github.wechat.ilink.bot.mcp.McpHealthMonitor;
import com.github.wechat.ilink.bot.mcp.McpToolRegistry;
import com.github.wechat.ilink.bot.mode.hook.HookContext;
import com.github.wechat.ilink.bot.mode.hook.HookEvent;
import com.github.wechat.ilink.bot.mode.hook.HookRegistry;
import com.github.wechat.ilink.bot.persistence.DatabaseManager;
import com.github.wechat.ilink.bot.session.SessionManager;
import com.github.wechat.ilink.bot.task.*;
import com.github.wechat.ilink.bot.util.AppPaths;
import com.github.wechat.ilink.sdk.core.exception.NotLoginException;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GameApplication {

    private static final Logger log = LoggerFactory.getLogger(GameApplication.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile boolean running = true;
    private DatabaseManager dbManager;
    private SessionManager sessionManager;
    private LlmRequestQueue llmQueue;
    private LlmProvider llmProvider;
    private ChatHistoryManager chatHistory;
    private boolean streamingEnabled;
    private int typingIntervalMs;
    private TaskProvider taskProvider;
    private TaskConfig taskConfig;
    private VideoTaskBuffer videoBuffer;
    private ExecutorService taskExecutor;
    private McpClient mcpClient;
    private McpToolRegistry mcpToolRegistry;
    private McpHealthMonitor mcpHealthMonitor;
    private ReliabilityConfig reliabilityConfig;
    private final List<BotInstance> bots = new ArrayList<BotInstance>();
    private ExecutorService botExecutor;

    public static void main(String[] args) {
        GameApplication app = new GameApplication();
        app.start();
    }

    public void start() {
        dbManager = new DatabaseManager(AppPaths.data("farm_game.db"));
        dbManager.initialize();

        reliabilityConfig = ReliabilityConfig.load(AppPaths.data("reliability-config.json"));
        sessionManager = new SessionManager(dbManager,
                reliabilityConfig.getFlushDelayMs(), reliabilityConfig.getFlushIntervalMs());

        ModelsConfig modelsConfig = ModelsConfig.load(AppPaths.data("models-config.json"));
        LlmConfig llmConfig = modelsConfig.resolveChatLlmConfig();
        llmProvider = createProvider(llmConfig);
        chatHistory = new ChatHistoryManager(llmConfig.getMaxHistory());
        llmQueue = new LlmRequestQueue(3, 50);
        streamingEnabled = llmConfig.isStreamingEnabled();
        typingIntervalMs = llmConfig.getTypingIntervalMs();

        if (llmProvider != null) {
            log.info("LLM 已启用: provider={}, model={}, streaming={}", llmConfig.getProvider(), llmConfig.getModel(), streamingEnabled);
        } else {
            log.info("LLM 未启用，非指令消息将原样回显");
        }

        initTaskSubsystem(modelsConfig);
        initMcpClient();

        QrCodeProvider shareProvider = new QrCodeProvider() {
            @Override
            public String getQrCodeUrl() {
                return createShareBot();
            }
        };

        List<BotConfig> botConfigs = loadBotConfigs(AppPaths.data("bots.json"));
        if (botConfigs.isEmpty()) {
            log.error("未找到 bot 配置，请在 data/bots.json 中配置至少一个 bot");
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                shutdown();
            }
        }));

        for (BotConfig config : botConfigs) {
            try {
                TaskMessageHandler taskHandler = createTaskHandler();
                BotInstance instance = BotInstance.create(config, dbManager, sessionManager,
                        llmProvider, chatHistory, llmQueue,
                        streamingEnabled, typingIntervalMs, shareProvider, taskHandler, taskConfig,
                        mcpClient, mcpToolRegistry, reliabilityConfig);
                instance.start();
                bots.add(instance);
                fireLifecycle(instance, HookEvent.ON_STARTUP);
                log.info("Bot [{}] 启动成功", config.getName());
            } catch (Exception e) {
                log.error("Bot [{}] 启动失败", config.getName(), e);
            }
        }

        if (bots.isEmpty()) {
            log.error("没有成功启动的 bot，退出");
            return;
        }

        botExecutor = Executors.newCachedThreadPool();
        for (final BotInstance bot : new ArrayList<BotInstance>(bots)) {
            botExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    pollLoop(bot);
                }
            });
        }
    }

    String createShareBot() {
        final BotInstance.DynamicBotResult[] holder = new BotInstance.DynamicBotResult[1];
        BotInstance.DynamicBotResult result = BotInstance.createDynamic(
                dbManager, sessionManager, llmProvider, chatHistory,
                llmQueue, streamingEnabled, typingIntervalMs,
                new QrCodeProvider() {
                    @Override
                    public String getQrCodeUrl() {
                        return createShareBot();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        BotInstance.DynamicBotResult r = holder[0];
                        if (r == null) return;
                        synchronized (bots) {
                            bots.add(r.getInstance());
                        }
                        botExecutor.submit(new Runnable() {
                            @Override
                            public void run() {
                                pollLoop(r.getInstance());
                            }
                        });
                    }
                }, createTaskHandler(), taskConfig, mcpClient, mcpToolRegistry, reliabilityConfig);
        holder[0] = result;
        return result.getQrCodeUrl();
    }

    void initTaskSubsystem(ModelsConfig modelsConfig) {
        taskConfig = TaskConfig.load(AppPaths.data("task-config.json"));
        if (!taskConfig.isEnabled()) {
            log.info("Task 未启用（data/task-config.json 中 enabled=false 或文件未创建）");
            return;
        }
        injectModelConfig(taskConfig, modelsConfig);
        installBundledSkills(taskConfig);
        taskProvider = new DashScopeVideoProvider(taskConfig);
        videoBuffer = new VideoTaskBuffer(taskConfig.getBufferTtlMs(), taskConfig.getMaxVideoBytes());
        videoBuffer.startCleanup();
        taskExecutor = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "task-worker-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        log.info("Task 已启用: claudePath={}, workspaceRoot={}, claudeHome={}, videoReviewModel={}, claudeBridgeModel={}, claudeBridgeSmallModel={}, claudeBridgeBaseUrl={}, dashscopeBaseUrlSet={}, dashscopeApiKeySet={}, timeoutMs={}, permissionMode={}",
                taskConfig.getClaudePath(), taskConfig.getWorkspaceRoot(),
                taskConfig.getClaudeHome(), taskConfig.getVideoReviewModel(), taskConfig.getClaudeBridgeModel(),
                taskConfig.getClaudeBridgeSmallModel(), taskConfig.getClaudeBridgeBaseUrl(),
                isSet(taskConfig.getDashscopeBaseUrl()), isSet(taskConfig.getDashscopeApiKey()),
                taskConfig.getTimeoutMs(), taskConfig.getPermissionMode());
    }

    /** 把 models-config.json 中共享的 DashScope provider 与 review/bridge 模型注入 TaskConfig。 */
    private static void injectModelConfig(TaskConfig taskConfig, ModelsConfig modelsConfig) {
        ModelsConfig.Provider ds = modelsConfig.dashscope();
        if (isSet(ds.getBaseUrl())) {
            taskConfig.setDashscopeBaseUrl(ds.getBaseUrl());
        }
        taskConfig.setDashscopeApiKey(ds.getApiKey());
        if (isSet(ds.getUploadsUrl())) {
            taskConfig.setDashscopeUploadsUrl(ds.getUploadsUrl());
        }
        taskConfig.setVideoReviewModel(modelsConfig.reviewModel());
        taskConfig.setClaudeBridgeModel(modelsConfig.bridgeModel());
        taskConfig.setClaudeBridgeSmallModel(modelsConfig.bridgeSmallModel());

        // Bridge 走 claude 子进程，需要 Anthropic 协议端点：按 bridge.provider 解析（与 dashscope 视频端点分离）
        ModelsConfig.Provider bridgeProvider = modelsConfig.bridgeProvider();
        if (bridgeProvider != null) {
            taskConfig.setClaudeBridgeBaseUrl(bridgeProvider.getBaseUrl());
            taskConfig.setClaudeBridgeApiKey(bridgeProvider.getApiKey());
        }
    }

    private void installBundledSkills(TaskConfig taskConfig) {
        try {
            java.nio.file.Path claudeHome = java.nio.file.Paths.get(taskConfig.getClaudeHome());
            new SkillInstaller(claudeHome).installAll();
        } catch (Exception e) {
            log.warn("内置 skill 安装失败，claude 子进程可能无法触发相关 skill: {}", e.getMessage(), e);
        }
    }

    void initMcpClient() {
        AutogameConfig config = AutogameConfig.load(AppPaths.data("autogame-config.json"));
        if (!config.isEnabled()) {
            log.info("Autogame MCP 未启用（data/autogame-config.json 中 enabled=false 或文件未创建）");
            return;
        }
        try {
            mcpClient = new McpClient(config.getMcpUrl());
            mcpClient.connect();
            mcpClient.initialize();
            mcpToolRegistry = new McpToolRegistry(mcpClient);
            mcpToolRegistry.refresh();
            log.info("Autogame MCP 已启用: url={}, tools={}",
                    config.getMcpUrl(), mcpToolRegistry.all().size());
            mcpHealthMonitor = new McpHealthMonitor(mcpClient, mcpToolRegistry,
                    reliabilityConfig.getMcpHealthIntervalMs(), reliabilityConfig.getMcpToolRefreshTicks());
            mcpHealthMonitor.start();
        } catch (Exception e) {
            log.warn("Autogame MCP 连接失败，! 指令将不可用: {}", e.getMessage(), e);
            if (mcpClient != null) {
                mcpClient.close();
                mcpClient = null;
            }
            mcpToolRegistry = null;
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isEmpty();
    }

    TaskMessageHandler createTaskHandler() {
        if (taskProvider == null || videoBuffer == null || taskExecutor == null) {
            return null;
        }
        return new TaskMessageHandler(taskProvider, videoBuffer, taskExecutor);
    }

    void pollLoop(BotInstance bot) {
        int consecutiveErrors = 0;
        long baseDelay = 1000L;
        long maxDelay = 30000L;

        while (running) {
            try {
                bot.pollUpdates();
                consecutiveErrors = 0;
            } catch (Exception e) {
                if (!running) break;

                if (!bot.isAlive()) {
                    log.warn("[{}] 连接已断开，移除 bot", bot.getName());
                    removeBot(bot);
                    break;
                }

                if (isPermanentFailure(e)) {
                    log.error("[{}] 永久性错误，移除 bot: {}", bot.getName(), e.getMessage());
                    bot.shutdown();
                    removeBot(bot);
                    break;
                }

                consecutiveErrors++;
                long delay = Math.min(baseDelay * (1L << Math.min(consecutiveErrors - 1, 5)), maxDelay);
                log.warn("[{}] 轮询异常（第{}次），{}ms 后重试", bot.getName(), consecutiveErrors, delay);
                try { Thread.sleep(delay); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    boolean isPermanentFailure(Exception e) {
        return e instanceof NotLoginException
                || e instanceof SessionExpiredException;
    }

    /** 触发某 {@link BotInstance} 的应用级生命周期 hook（ON_STARTUP/ON_SHUTDOWN）；无订阅即空操作。 */
    private static void fireLifecycle(BotInstance instance, HookEvent event) {
        if (instance == null) {
            return;
        }
        HookRegistry hooks = instance.hooks();
        if (hooks != null && hooks.has(event)) {
            hooks.fire(event, HookContext.builder().build());
        }
    }

    void removeBot(BotInstance bot) {
        synchronized (bots) {
            bots.remove(bot);
        }
        log.info("活跃 bot 数量: {}", bots.size());
    }

    LlmProvider createProvider(LlmConfig config) {
        if (!config.isEnabled()) {
            return null;
        }
        String provider = config.getProvider();
        if ("openai".equals(provider)) {
            return new OpenAiProvider(config);
        }
        log.warn("未知 LLM provider: {}，目前支持: openai", provider);
        return null;
    }

    public void shutdown() {
        running = false;
        synchronized (bots) {
            for (BotInstance bot : bots) {
                fireLifecycle(bot, HookEvent.ON_SHUTDOWN);
                bot.shutdown();
            }
            bots.clear();
        }
        if (botExecutor != null) {
            botExecutor.shutdownNow();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdownNow();
        }
        if (videoBuffer != null) {
            videoBuffer.shutdown();
        }
        if (llmQueue != null) {
            llmQueue.shutdown();
        }
        if (mcpHealthMonitor != null) {
            mcpHealthMonitor.shutdown();
        }
        if (mcpClient != null) {
            mcpClient.close();
        }
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
        if (dbManager != null) {
            dbManager.close();
        }
        if (mcpClient != null) {
            mcpClient.close();
        }
        log.info("应用已关闭");
    }

    List<BotConfig> loadBotConfigs(String path) {
        File file = new File(path);
        if (!file.exists()) {
            List<BotConfig> defaults = Collections.singletonList(new BotConfig("default", null));
            createBotsTemplate(file, defaults);
            log.warn("Bot 配置文件不存在: {}，已生成模板并使用默认单 bot 配置，请按需修改", path);
            return defaults;
        }
        try {
            return MAPPER.readValue(file, new TypeReference<List<BotConfig>>() {});
        } catch (IOException e) {
            log.error("读取 bot 配置失败: {}", path, e);
            return Collections.emptyList();
        }
    }

    private static void createBotsTemplate(File file, List<BotConfig> template) {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file, template);
            log.info("已创建 Bot 配置模板: {}", file.getAbsolutePath());
        } catch (IOException e) {
            log.warn("无法创建 Bot 配置模板: {}", file.getAbsolutePath(), e);
        }
    }

    List<BotInstance> getBots() {
        synchronized (bots) {
            return new ArrayList<BotInstance>(bots);
        }
    }
}
