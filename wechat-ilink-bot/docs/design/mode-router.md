# ModeRouter 设计

## 背景

在 Phase 1 重构前，`GameBot.onMessages()` 用一串 if/else 同时承担四条互不相关的职责：
1. 视频点评（`TaskMessageHandler` → `DashScopeVideoProvider`）
2. `#` 前缀农场游戏命令（`GameEngine`）
3. 非前缀文本走 LLM 聊天（`LlmProvider` + `ChatHistoryManager`）
4. 系统命令（`/mode` 等，硬编码在 GameBot 内）

随着模式数量增加（已扩展到 Claude Bridge、Autogame），这种隐式路由难以维护、难以测试。Phase 1 把路由提炼成显式的 `ModeRouter + BotMode` 架构，后续 Phase 持续在此基础上加模式，对外行为保持稳定。

## 设计目标

| 目标 | 实现方式 |
|------|---------|
| 显式路由 | `ModeRouter.route()` 按优先级串行调用各 Mode，规则集中在单文件 |
| SDK 隔离 | Mode 通过 `ModeSender` 接口回调 GameBot，不 import `ILinkClient` |
| 模式可插拔 | 新模式只需实现 `BotMode` 接口 + 注册到 `ModeRouter` 构造器 |
| 无状态 Mode | 所有用户状态存于 `PlayerSession.currentMode`，Mode 本身无字段 |
| 行为不变 | Phase 1 完成后所有现有测试通过，无功能回归 |

## 核心接口

```java
public interface BotMode {
    BotModeType type();
    ModeOutcome handleText(ModeContext ctx, PlayerSession session, String text) throws Exception;
}

public enum BotModeType {
    CHAT, CLAUDE, FARM, REVIEW, AUTOGAME;
    public static BotModeType defaultMode() { return CHAT; }
    public static BotModeType fromName(String name) { ... }
}
```

```java
public final class ModeOutcome {
    public enum Status { HANDLED, NOT_MATCHED, SKIP, ERROR }

    public static ModeOutcome handled() { ... }
    public static ModeOutcome notMatched() { ... }
    public static ModeOutcome skip() { ... }
    public static ModeOutcome error(String message) { ... }

    public boolean isHandled() { return status == HANDLED; }
    public Status getStatus() { return status; }
}
```

三状态语义：
- `HANDLED` — Mode 处理了消息（可能已发送回复），router 停止
- `NOT_MATCHED` — Mode 不匹配（如 FarmMode 收到非 `#` 文本），router 继续下一个
- `SKIP` — 消息无法处理（空文本等），router 停止，不报错

## ModeContext — 依赖载体

`ModeContext` 是一个不可变容器，集中持有所有 Mode 可能需要的依赖（14 字段，随各 Phase 扩展）：

```java
public final class ModeContext {
    private final ModeSender sender;             // = RetrySender(GameBot)，发送带重试
    private final MediaDownloader downloader;    // 入向媒体下载（Phase 4）
    private final GameEngine engine;             // FarmMode 用
    private final ResponseRenderer renderer;     // FarmMode 用
    private final LlmProvider llmProvider;       // ChatMode 用
    private final ChatHistoryManager chatHistory;
    private final LlmRequestQueue llmQueue;
    private final SessionManager sessions;
    private final TaskMessageHandler taskHandler; // ReviewMode 用
    private final ClaudeSessionRepository claudeSessionRepo; // ClaudeBridgeMode 用
    private final boolean streamingEnabled;
    private final int typingIntervalMs;
    private final McpClient mcpClient;           // AutogameMode 用（未启用为 null）
    private final McpToolRegistry mcpToolRegistry; // AutogameMode 用（未启用为 null）
    // 12 参 / 14 参 两个构造器 + 一组 getter
}
```

**构造时机**：`GameBot` 构造器内，把 `this`（GameBot 自身，作为 ModeSender）和其它依赖一起塞进 `ModeContext`，再把 `ModeContext` 塞进 `ModeRouter`。这是整个项目中"构造期引用泄漏"的唯一例外，由于 `ModeRouter` 不会在 GameBot 构造完成前被外部调用，安全。

## ModeSender — SDK 隔离屏障

```java
public interface ModeSender {
    void sendText(String userId, String text) throws IOException;
    void sendTextWithTyping(String userId, String text, long typingMillis) throws IOException;
    void sendImage(String userId, byte[] imageBytes, String fileName, String caption) throws IOException;
    void sendFile(String userId, byte[] fileBytes, String fileName, String caption) throws IOException;
    void sendVideo(String userId, byte[] videoBytes, String fileName, Integer playLengthMs, String caption) throws IOException;
    void startTyping(String userId) throws IOException;
    void stopTyping(String userId) throws IOException;
}
```

`GameBot implements ModeSender`，所有 Mode 通过这个接口发消息。这样：
- Mode 包内零 SDK import，符合 `.claude/rules/sdk-usage.md`
- 测试时可以 mock `ModeSender`，不需要 mock 整个 `ILinkClient`
- 未来若 SDK 升级签名变更，只改 GameBot 一处

`sendFile/sendVideo` 是 Phase 4 双向文件回传新增（Claude Bridge 产物回发）。对称地，入向有 `MediaDownloader`（`downloadImage(item)`/`downloadFile(item)`），同样由 `GameBot` 实现，委托 SDK 完成 CDN 下载 + AES 解密。

## 路由规则

`ModeRouter.route(WeixinMessage)` 按优先级串行判断（含 Phase 4/5 新增的限流与文件入向）：

```
0. userId 为空 → SKIP
1. sessions.getOrCreate(userId) + session.touchActivity()
2. RateLimiter.tryAcquire(userId) 超限 → sendSafe("请求过于频繁…") + HANDLED   [Phase 5]
3. 消息含 video_item → ReviewMode.handleVideo（抢占式，不看当前模式）
4. 当前模式 == CLAUDE 且消息含 image/file_item → handleClaudeFileIntake（下载 + BridgeFileBuffer）  [Phase 4]
5. extractText(msg) 为空 → SKIP
   ↳ 非空则 MessageAuditLog.inbound(userId, text)（入向审计落 logs/io/<userId>/，见 logging.md）
6. text 以 "#" 开头（全角 `＃` 已归一为半角）→ FarmMode.handleText（剥离 #）
7. text 以 "/" 开头 → SystemCommandMode.handleText
8. text 以 "!" 开头（且 autogameMode != null）→ AutogameMode.handleText（! → MCP）  [autogame]
9. ReviewMode.handlePendingPrompt（60s 内有视频票据） → peek 命中（不消费，窗口内可多次命中，票据靠 60s TTL 回收）
10. switchableModes[currentMode]（CHAT/CLAUDE/AUTOGAME 可切换；FARM/REVIEW 不可切换，未注册时回退 CHAT）
```

**为什么视频是抢占式**：视频消息没有文本载体，用户上传即表示意图明确（点评），与当前模式无关。

**为什么 CLAUDE 文件入向在文本提取前**：CLAUDE 模式下用户发图片/文件是"喂给 Claude"的明确意图，需在文字 prompt 之前先缓冲成票据，等下一条文字触发时拼成 `effectivePrompt`（见 [claude-bridge.md](claude-bridge.md)）。仅在 CLAUDE 模式生效，其他模式收到图片/文件不会误吞。

**为什么 `#` / `/` / `!` 抢占式**：
- `#` 是农场命令的稳定契约，向后兼容
- `/` 是全局系统命令，任何模式下都应可用
- `!` 是 autogame 指令前缀，对应 autogame-xcx 的命令体系（与小程序侧 `#` 指令解耦）
- 三者都明确表达"我要做特定操作"，互不冲突

**为什么 ReviewMode.handlePendingPrompt 在中间**：用户上传视频后 60s 内发的非前缀文字，应视为对该视频的后续 prompt（如"重点点评节奏"），而不是切到当前模式闲聊。

## 各 Mode 职责

| Mode | 触发条件 | 职责 |
|------|---------|------|
| `ReviewMode` | 视频消息 / pending ticket | 委托 `TaskMessageHandler.tryHandleVideo/tryHandleTaskText`，缓冲视频或提交 Claude Code/DashScope 任务 |
| `FarmMode` | `#` 前缀 | 剥离 `#` → `engine.dispatch` → 若 result 含 IMAGE_DATA_KEY 则 `sendImage`，否则 `renderer.render` + `sendText` |
| `SystemCommandMode` | `/` 前缀 | 解析 `/mode`、`/new`、`/sessions`、`/resume`、`/help`、`/status`、`/sudo`（管理员限定提权），切换 `session.currentMode` 并 `scheduleFlush` |
| `ClaudeBridgeMode` | `/mode claude` 后的普通文本 / CLAUDE 模式媒体入向 | 文本 → `claude` 子进程（`--resume` 续传）；媒体 → `BridgeFileBuffer` 票据，下条文字拼 `effectivePrompt`；产物按扩展名回发 |
| `AutogameMode` | `!` 前缀 | `!list`/`!run`/`!status`/`!stop`/`!report`/`!help` → `McpClient.callTool` 调 autogame-xcx MCP server；`run_template` 异步（见 [mcp-autogame.md](mcp-autogame.md)） |
| `ChatMode` | 默认模式 | `llmProvider == null` 时原样回显；否则构建 systemPrompt + 历史，按 `streamingEnabled` 走 `chatStream` 或 `chat` |

## BotModeType 持久化

`PlayerSession.currentMode` 字段 + `player.bot_mode` 列（TEXT，存枚举名如 `'CHAT'`）：

| 时机 | 动作 |
|------|------|
| 新用户首次消息 | `PlayerSession` 构造 → `currentMode = BotModeType.defaultMode()` → `playerRepo.insert()` |
| `/mode X` 切换 | `SystemCommandMode` → `session.setCurrentMode(X)`（dirty=true）→ `sessions.saveSession()` → `UPDATE player SET bot_mode` |
| 重启后加载 | `SessionManager.getOrCreate()` → `playerRepo.findById()` → `session.setCurrentMode(BotModeType.fromName(row.bot_mode))` |
| 旧库升级 | `DatabaseManager.migratePlayerBotMode()` 用 `PRAGMA table_info` 检测缺列，`ALTER TABLE ADD COLUMN bot_mode TEXT` |

## 错误处理

`GameBot.onMessages` 对 `router.route()` 包 try/catch，任何 Mode 抛异常都被 catch-all 兜底为"出了点问题，输入 /help 查看可用命令"。

各 Mode 内部还有自己的细粒度错误处理（如 ChatMode 区分 LLM 错误和队列拒绝），不会被外层 catch-all 覆盖。

## 与原 GameBot 行为对照

| 场景 | 重构前 | 重构后 |
|------|-------|-------|
| `#帮助` | GameBot.handleCommand → engine | ModeRouter → FarmMode → engine（一致） |
| `你好`（无 LLM） | GameBot.handleChat → 原样回显 | ModeRouter → ChatMode → 原样回显（一致） |
| `你好`（有 LLM） | GameBot.handleChat → llmProvider.chat | ModeRouter → ChatMode → llmProvider.chat（一致） |
| `/mode chat` | 不支持（旧版无 `/` 路由） | ModeRouter → SystemCommandMode → 切换（新能力） |
| 视频上传 | GameBot 视频分支 → TaskMessageHandler | ModeRouter → ReviewMode → TaskMessageHandler（一致） |
| 视频后 60s 内发文字 | GameBot 视频分支 → TaskMessageHandler.tryHandleTaskText | ModeRouter → ReviewMode.handlePendingPrompt（一致） |

## 测试策略

- `ModeRouterTest`（15 个）：覆盖 4 种入站形态 + 空文本/null/异常路径
- `ChatModeTest`（11 个）：sync/streaming 路径 + 错误兜底 + 历史记录
- `FarmModeTest`（6 个）：`#` 前缀派发 + 异常路径 + 图片发送
- `GameBotTest`：新增 `onMessages_routerThrowsException_sendsFallbackAndDoesNotPropagate`

覆盖率：mode 包 85.36%，GameBot 87.78%（均超 80% 门槛）。

## 已交付里程碑（原「后续阶段」）

| Phase | 改动 | 状态 |
|-------|------|------|
| Phase 2 | `ClaudeBridgeMode`（`--resume` 续传 + `claude_sessions` 表 + `/new`/`/sessions`/`/resume`） | ✅ 已完成 |
| Phase 3 | 工具审批走静态策略白名单（`TaskConfig.allowedTools/disallowedTools` + `permissionMode`）；逐次交互审批（3.2）**已决策不做**（NO-GO，权限收敛为二元制） | ✅ 已完成 |
| Phase 4 | `ModeContext` 增 `MediaDownloader`（入向）+ `ModeSender` 增 `sendFile/sendVideo`（出向）+ `BridgeFileBuffer`/`BridgeWorkspace` | ✅ 已完成 |
| Phase 5 | `ModeContext.sender` 经 `RetrySender` 装饰；`ModeRouter` 入口 `RateLimiter`；`McpHealthMonitor` 自愈；`FlushGate` + 锁下沉 | ✅ 已完成 |
| autogame | `ModeContext` 增 `mcpClient`/`mcpToolRegistry`（14 参构造器），`ModeRouter` 增 `!` 路由 + `AutogameMode` | ✅ 已完成 |

`ModeContext` 是统一扩展点：新模式只需加字段 + 加 Mode 实现 + 注册到 `ModeRouter` 构造器。`switchableModes` 决定哪些模式可经 `/mode` 切换（当前 CHAT/CLAUDE/AUTOGAME；FARM/REVIEW 抢占式触发，无需切换）。
