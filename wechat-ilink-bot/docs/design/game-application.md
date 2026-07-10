# GameApplication 设计

## 职责

`GameApplication` 是组合根，负责：
1. 初始化 SQLite 数据库
2. 加载配置（reliability / llm / task / autogame / bots）并组装所有框架层组件
3. 初始化可选子系统：LLM、Task（视频）、MCP（autogame）
4. 按 `data/bots.json` 构建多个 `BotInstance`（多账号）并登录
5. 启动每个 bot 的长轮询循环（指数退避重试）
6. 关闭时按序清理资源

## 启动序列

```java
public class GameApplication {
    private volatile boolean running = true;
    private DatabaseManager dbManager;
    private SessionManager sessionManager;
    private LlmRequestQueue llmQueue;
    private TaskProvider taskProvider; private TaskConfig taskConfig;
    private VideoTaskBuffer videoBuffer; private ExecutorService taskExecutor;
    private McpClient mcpClient; private McpToolRegistry mcpToolRegistry; private McpHealthMonitor mcpHealthMonitor;
    private ReliabilityConfig reliabilityConfig;
    private final List<BotInstance> bots = new ArrayList<BotInstance>();
    private ExecutorService botExecutor;

    public void start() {
        // 1. 数据库 + 可靠性配置 + 会话管理（全构造器启 FlushGate）
        dbManager = new DatabaseManager("data/farm_game.db"); dbManager.initialize();
        reliabilityConfig = ReliabilityConfig.load("data/reliability-config.json");
        sessionManager = new SessionManager(dbManager,
                reliabilityConfig.getFlushDelayMs(), reliabilityConfig.getFlushIntervalMs());

        // 2. 统一模型注册表（providers 共享 + 每功能 model）；Chat 由其 chat 块解析出 LlmConfig
        ModelsConfig modelsConfig = ModelsConfig.load("data/models-config.json");
        LlmConfig llmConfig = modelsConfig.resolveChatLlmConfig();
        LlmProvider llmProvider = createProvider(llmConfig);
        ChatHistoryManager chatHistory = new ChatHistoryManager(llmConfig.getMaxHistory());
        llmQueue = new LlmRequestQueue(3, 50);

        // 3. 可选子系统（initTaskSubsystem 注入 modelsConfig 的 DashScope provider + review/bridge 模型）
        initTaskSubsystem(modelsConfig);   // TaskConfig（运维字段）+ 注入模型 → DashScopeVideoProvider + VideoTaskBuffer + SkillInstaller
        initMcpClient();       // AutogameConfig → McpClient.connect/initialize + Registry.refresh + HealthMonitor.start

        // 4. 多账号（data/bots.json）
        List<BotConfig> botConfigs = loadBotConfigs("data/bots.json");
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        for (BotConfig config : botConfigs) {
            TaskMessageHandler taskHandler = createTaskHandler();
            BotInstance instance = BotInstance.create(config, dbManager, sessionManager, llmProvider, chatHistory,
                    llmQueue, streamingEnabled, typingIntervalMs, shareProvider, taskHandler, taskConfig,
                    mcpClient, mcpToolRegistry, reliabilityConfig);
            instance.start(); bots.add(instance);
        }

        // 5. 每个 bot 一个长轮询线程
        botExecutor = Executors.newCachedThreadPool();
        for (BotInstance bot : bots) { botExecutor.submit(() -> pollLoop(bot)); }
    }

    // MCP 初始化（autogame 关闭时整段跳过）
    void initMcpClient() {
        AutogameConfig config = AutogameConfig.load("data/autogame-config.json");
        if (!config.isEnabled()) return;
        mcpClient = new McpClient(config.getMcpUrl());
        mcpClient.connect(); mcpClient.initialize();
        mcpToolRegistry = new McpToolRegistry(mcpClient); mcpToolRegistry.refresh();
        mcpHealthMonitor = new McpHealthMonitor(mcpClient, mcpToolRegistry,
                reliabilityConfig.getMcpHealthIntervalMs(), reliabilityConfig.getMcpToolRefreshTicks());
        mcpHealthMonitor.start();
    }

    // 长轮询：指数退避重试，永久错误/断连则移除 bot
    void pollLoop(BotInstance bot) {
        int err = 0; long base = 1000L, max = 30000L;
        while (running) {
            try { bot.pollUpdates(); err = 0; }
            catch (Exception e) {
                if (!bot.isAlive() || isPermanentFailure(e)) { bot.shutdown(); removeBot(bot); break; }
                long delay = Math.min(base * (1L << Math.min(err, 5)), max); err++;
                Thread.sleep(delay);
            }
        }
    }
}
```

> `BotInstance.create` / `createDynamic` 内部组装 `GameBot`（规范 14 参构造器，注入 reliability/mcp）并完成登录。
> `createShareBot` 支持运行时动态加 bot（二维码分享加号）。

### 免扫码会话复用

配置型 bot（`BotInstance.create` / `data/bots.json`）支持重启后免扫码复用已登录会话：

- **登录落库**：`onLoginSuccess` 时把 `LoginContext`（botToken/userId/botId/baseUrl）经 `BotSessionRepository` 写入 SQLite `bot_session` 表（按 bot 名）。
- **构建注入**：`create()` 启动时 `load(name)`，若有记录则经 `ILinkClientBuilder.resumeContext(...)` 注入；SDK 构造器据此直接置为 LOGGED_IN 并启动心跳。
- **启动校验**：`start()` 在 `resumed` 时先 `tryResume()` —— 探测一次 `client.getUpdates()`；成功则「跳过扫码」直接返回，失败（token 失效）则 `clear(name)` 并回落到原有 `MAX_LOGIN_ATTEMPTS` 扫码循环。
- **关闭保存**：`shutdown()` 在关 client 前 `persistSession()`，经 `exportResumeContext()` 落最新消息游标（`updatesCursor`），下次重启不丢消息位点。
- **范围**：动态分享 bot（`createDynamic`，name="dynamic"）不持久化。SDK 类型 ↔ `BotSessionRecord` 的转换在 `BotInstance` 内（应用层），持久化层零 SDK 依赖。

## 关机序列（`shutdown()`）

1. `running = false` — 退出所有轮询循环
2. 每个 `BotInstance.shutdown()`（`persistSession()` 落会话游标 → 关 SDK client）+ `bots.clear()`
3. `botExecutor.shutdownNow()` / `taskExecutor.shutdownNow()`
4. `videoBuffer.shutdown()` / `llmQueue.shutdown()`
5. `mcpHealthMonitor.shutdown()` / `mcpClient.close()`（MCP SSE + okhttp 线程）
6. `sessionManager.shutdown()`（全量 flush + 停 FlushGate）
7. `dbManager.close()`

## 设计要点

- `GameApplication` 是唯一组装根；所有依赖在此编织，无 DI 容器
- 配置全在 `data/`：`reliability-config.json` / `models-config.json` / `task-config.json` / `autogame-config.json` / `bots.json`；缺失时自动生成模板并使用默认值
- **模型/Provider 收敛到 `models-config.json`**：`providers` 定义共享 baseUrl+apiKey（+uploadsUrl），`chat/review/bridge` 各引用 provider + 声明自己的 model。Chat→`providers.zhipu`、Review/Bridge→共享的 `providers.dashscope`（只定义一次）。`task-config.json` 只保留运维字段（claudePath/workspaceRoot/permission/超时/Bridge cwd 等），模型与 DashScope endpoint/token 由 `GameApplication` 注入 `TaskConfig`
- 各子系统（LLM / Task / MCP）独立可选，`enabled=false` 时整段跳过，不影响其他模式
- **多账号**：`data/bots.json` 列多个 `BotConfig`，每账号一个 `BotInstance` + 独立轮询线程；支持运行时 `createDynamic` 动态加号
- 轮询循环含异常处理：`NotLoginException` / `SessionExpiredException` 视为永久错误移除 bot，其余按指数退避（1s→30s 封顶）重试
- 关机钩子（匿名 `Runnable`，Java 17 兼容）确保按序清理与会话持久化
- `BotInstance` 承担登录（优先免扫码复用 `bot_session`，失效再重试 `MAX_LOGIN_ATTEMPTS` 扫码）+ 登录成功后发欢迎菜单；`GameBot` 经 `setClient()` 延迟注入 `ILinkClient`
- `LlmRequestQueue` 封装 LLM 调用的有界线程池（3 线程、50 队列容量）和每用户并发限制
