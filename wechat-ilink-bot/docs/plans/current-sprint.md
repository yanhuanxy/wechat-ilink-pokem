# 当前迭代

## 目标

已完成：从「模块化农场游戏框架」演进为「基于 iLink SDK 的多模式微信机器人平台」——5 模式（Chat/Farm/Claude Bridge/Review/Autogame）+ MCP + 可靠性层。本文件记录各阶段交付；后续方向见 [ROADMAP.md](../ROADMAP.md)。

## 任务

### 已完成
- [x] Harness 架构文档系统
- [x] Maven 项目初始化（pom.xml、包结构）
- [x] 核心框架接口定义（Command、CommandResult、PlayerSession）
- [x] GameEngine + CommandParser + CommandRegistry 实现
- [x] SessionManager + 持久化实现（SQLite + WAL 模式）
- [x] ResponseRenderer 基础实现
- [x] GameApplication 组合根
- [x] GameBot SDK 桥接
- [x] 农场游戏 20 个命令（19 游戏命令 + HELP）
- [x] 单元测试覆盖（100 个测试，覆盖率 82%）
- [x] 集成测试（种植→收获→卖菜→持久化恢复）

### 本次迭代：game → bot 平台扩展 + Claude Code 视频任务流
- [x] 阶段 1：包名重命名 `com.github.wechat.ilink.game` → `com.github.wechat.ilink.bot`（含 ~60 个 Java 文件 + 文档同步）
- [x] 阶段 2.1：task 包核心接口（TaskProvider / TaskRequest / TaskConfig，复用 `llm.StreamCallback` 回调）
- [x] 阶段 2.2：WorkspaceManager（per-user / per-task 工作目录）
- [x] 阶段 2.3：ClaudeCodeProvider（ProcessBuilder + stream-json 解析，订阅模式 OAuth token）
- [x] 阶段 2.4：VideoTaskBuffer（60s 窗口视频缓冲）
- [x] 阶段 2.5：GameBot 路由扩展（视频 + 任务流分支）
- [x] 阶段 2.6：BotInstance + GameApplication 组装（TaskConfig 加载、TaskExecutor 单例、VideoTaskBuffer 单例）
- [x] 阶段 2.7：task-config.json 模板自动生成
- [x] 阶段 2.8：单元测试 33 个（VideoTaskBufferTest / TaskConfigTest / WorkspaceManagerTest / ClaudeCodeProviderTest / TaskMessageHandlerTest）
- [x] 阶段 2.9：Live 集成测试（@EnabledIfEnvironmentVariable 守卫，CI 不跑）
- [x] 阶段 2.10：文档同步（design/task-system.md 新增，architecture/overview.md、data-flow.md 更新）

### 多模式架构改造（Phase 1 — 模式路由地基）
- [x] 新增 `mode/` 包：`BotModeType`、`BotMode`、`ModeOutcome`、`ModeSender`、`ModeContext`、`ModeRouter` + 四个具体模式（ChatMode / FarmMode / ReviewMode / SystemCommandMode）
- [x] `PlayerSession` 加 `currentMode` 字段（默认 CHAT），`DatabaseManager.migratePlayerBotMode()` 自动迁移旧库
- [x] `PlayerRepository` 读写 `player.bot_mode` 列
- [x] `GameBot` 重构：实现 `ModeSender`，路由全部委托给 `ModeRouter`，行为对外保持不变
- [x] 新增系统命令 `/mode [chat|claude|farm|review]`、`/help`、`/status`
- [x] 单元测试：`ModeRouterTest`（15）、`ChatModeTest`（11）、`FarmModeTest`（6）、`GameBotTest` 更新
- [x] 文档同步：`data-flow.md`、`boundaries.md`、`game-bot.md`、`command-spec.md`、`session-management.md`、新增 `design/mode-router.md`
- [x] Phase 2：Claude Bridge 模式（`--resume` 跨消息会话 + `claude_sessions` 表 + `/new`、`/sessions`、`/resume` 命令）
  - [x] `claude_sessions` 表 + `ClaudeSession` POJO + `ClaudeSessionRepository`
  - [x] `mode/claude/`：`ClaudeCodeAdapter`（session-aware，`--resume` 续传）+ `ClaudeAdapterCallback`
  - [x] `ClaudeBridgeMode`（异步 daemon executor，stream-json → typing/分段回发）
  - [x] `PlayerSession.activeClaudeSessionId`（transient，不持久化）
  - [x] `TaskConfig` 增 `claudeBridge*` 字段；`/mode claude` 真切换 + `/new`、`/sessions`、`/resume`
  - [x] `ModeContext`（claudeSessionRepo）/ `ModeRouter`（claudeMode）/ `GameBot` / `BotInstance` / `GameApplication` 装配
  - [x] 测试：`ClaudeSessionRepositoryTest`、`ClaudeCodeAdapterTest`(10)、`ClaudeBridgeModeTest`(5)、`SystemCommandModeTest`(10) + 既有 mode 测试更新
  - [ ] Live 端到端：配置已切智谱 GLM（glm-5.2）；LiveTest 已可移植化（env/AppPaths/向上查找，不再硬编码本机路径）、子进程链路已验真（init/模型/鉴权均 OK，curl 直探智谱端点 200）；完整回复当前受智谱网关 529 overloaded 瞬态阻塞（provider 侧、非代码缺陷），待其恢复后复跑 `CLAUDE_BRIDGE_LIVE=true mvn test -Dtest=ClaudeCodeAdapterLiveTest`
- [x] Phase 3：工具审批（静态策略白名单）—— `TaskConfig.allowedTools` / `disallowedTools` + `permissionMode`
  Bridge 经 `ClaudeCodeAdapter.appendBridgePolicy` 落到 `--allowedTools` / `--disallowedTools`（Review 走 DashScopeVideoProvider 直连、不经工具策略）；opt-in 不破坏现有行为
  - 评估结论：headless 模式无文档化的逐次工具审批协议（`--permission-prompt-tool` 无规范、stream-json 控制协议为内部接口），
    叠加 DashScope 403 无法 live 验证 → 采用文档化稳定的静态策略路线
  - Phase 3.2 逐次交互审批 —— ❌ **已决策不做（NO-GO，2026-07-09）**：`--permission-prompt-tool` 实为 MCP 工具名（需 JVM 反向做 MCP server）、schema/端点未验证、对已能提权者不增安全、远程损效率；权限收敛为二元制，另加 opt-in 旋钮 `claudeBridgeAdminDefaultPrivileged`（admin 进入即默认提权）。见 [claude-bridge-phase3.2-spike.md](../design/claude-bridge-phase3.2-spike.md) 决策更新
- [x] Phase 4：双向文件回传（AES-128-ECB 解密 + CDN 上传）
  - [x] `MediaDownloader` seam（入向）+ `ModeSender` 增 `sendImage/sendFile/sendVideo`（出向）；`GameBot` 实现两者，委托 SDK（SDK 内部完成 CDN 下载/上传 + AES 解密）
  - [x] `BridgeFileBuffer`（per-user 60s 入向票据缓冲，仿 `VideoTaskBuffer`）+ `BridgeWorkspace`（`<cwd>/<userId>/{input,output}`，入向落盘 / 出向扫描）
  - [x] `ModeRouter` CLAUDE 模式入向：`findMediaItem` → `handleClaudeFileIntake` → 下载 → `bufferIncomingFile`
  - [x] `ClaudeBridgeMode` 双向编排：票据消费 → 写 input + 建 output + 增强提示词（`effectivePrompt`）→ 出向 `sendOutputFiles` 按扩展名回发（图/视频/文件），上限 10 个 / 50MB
  - [x] `BotInstance.buildClaudeMode` 装配 buffer+workspace；`shutdown()` 接入 `fileBuffer.shutdown()`（对称 `VideoTaskBuffer`）
  - [x] 修复 `runClaude` 将 `effectivePrompt`（而非原始 `prompt`）传给 adapter 的 bug
  - [x] 测试：`BridgeFileBufferTest`(10)、`BridgeWorkspaceTest`(9)、`ClaudeBridgeModeTest` 文件流(3)、`ModeRouterTest` 入向(2)、`GameBotTest` 下载/发送委托(4) + 6 处 `ModeContext` 签名迁移
- [x] Phase 5：可靠性增强（RateLimiter / Watchdog / FlushGate / RetrySender）—— 重新评估纳入 autogame/MCP 面
  - [x] **RetrySender**（`mode/RetrySender`，G1）：`ModeSender` 装饰器，对 sendText/sendImage/sendFile/sendVideo 指数退避重试（全失败 log+放弃不抛）；`GameBot` 用它包 `this` 作 `ModeContext.sender`，零调用方改动
  - [x] **RateLimiter**（`mode/RateLimiter`，G2）：per-user 固定窗口限流，`ModeRouter.route` 入口 gate，超限回"请求过于频繁"；daemon 清理过期窗口
  - [x] **MCP Watchdog**（G3–G7）：`McpClient` 加 SSE 存活追踪 + `reconnect()`（复用实例）+ pending 超时/POST 失败清理 + `pendingCount()`；`McpHealthMonitor` 周期探测断线自愈 + 刷新 tool；`GameApplication.shutdown()` 关 monitor + `mcpClient.close()`
  - [x] **FlushGate**（`session/FlushGate`，G8）：per-user debounce 合并 + 周期兜底 flush；锁下沉（`GameEngine.userLocks` → `SessionManager.lockFor/withLock`）保证异步 flush 一致快照；默认同步立即刷（flushDelayMs=0），可配 debounce
  - [x] **配置**（`config/ReliabilityConfig`）：8 旋钮 JSON（默认值保持既有行为）；`GameApplication` 加载并注入
  - [x] 测试：`RetrySenderTest`(8)、`RateLimiterTest`(7)、`McpClientTest`(11)、`McpHealthMonitorTest`(6)、`FlushGateTest`(4)、`ReliabilityConfigTest`(3)、`SessionManagerTest` +3；`mvn test` 407 全绿（4 live 跳过）

### 日志落地 + bot 会话复用（免重扫）
- [x] **日志落地**（`logback.xml` + `util/MessageAuditLog`）：
  - 系统日志 `SYSTEM_FILE`（root，`logs/system/system.<date>.log`，按日期滚动 maxHistory 30，UTF-8）
  - 收发审计 `MSG_IO_SIFT`（MDC `userId` SiftingAppender → `logs/io/<userId>/io.<date>.log`，`additivity=false`）；入向埋点 `ModeRouter.route`、出向埋点 `GameBot` 各 `send*`
  - Claude 桥接生命周期+错误 `CLAUDE_FILE`（`mode.claude` / `ClaudeBridgeMode` logger → `logs/claude/claude.<date>.log`，无需改代码）
- [x] **bot 会话复用**（`persistence/BotSessionRepository` + `bot_session` 表）：
  - 登录落库（`onLoginSuccess` → `LoginContext` 四要素 + 游标）→ 重启 `ILinkClientBuilder.resumeContext` 注入 → `start()` 探测 `getUpdates` 校验，成功跳过扫码、失效清除回落扫码 → `shutdown()` 经 `exportResumeContext` 落最新游标
  - 仅配置型 bot（`bots.json`）；动态分享 bot 不持久化；持久化层零 SDK 依赖（转换在 `BotInstance`）
- [x] 测试：`BotSessionRepositoryTest`(6)、`MessageAuditLogTest`(4)、`BotInstanceTest` +2（converter 往返）；`mvn test` 419 全绿（4 live 跳过）
- [x] 文档：新增 `design/logging.md`；更新 `game-application.md`、`architecture/overview.md`、`game-bot.md`

### autogame + MCP（外部新增，补登）
- [x] `mcp/` 包：`McpClient`（JSON-RPC over HTTP+SSE）、`McpToolRegistry`、`McpTool`/`McpToolResult`、`McpHealthMonitor`、`AutogameConfig`
- [x] `mode/AutogameMode`（`!` 前缀 → `list_templates`/`run_template`/`get_status`/`stop_execution`/`get_report`；`run_template` 异步单线程）
- [x] `ModeContext` 扩 14 字段（`mcpClient`/`mcpToolRegistry`）；`ModeRouter` 增 `!` 路由 + `switchableModes[AUTOGAME]`；`BotModeType` 增 `AUTOGAME`
- [x] `GameApplication.initMcpClient` 装配 + `GameBot` 规范 14 参构造器（autogame/MCP/ReliabilityConfig）
- [x] 配置 `data/autogame-config.json`（默认 `enabled=false`，`mcpUrl=:8765`，缺省生成模板）
- [x] 测试：`AutogameModeTest`(8)、`McpClientTest`(11)、`McpHealthMonitorTest`(6)；`/mode autogame`、`!` 命令集

### Phase H — Hooks 子系统（运行时 harness 生命周期 hook + 开发期守卫）
- [x] **Part A 模型**：`mode/hook/` 包——`HookEvent`（10 个生命周期事件）、`HookContext`（不可变 Builder 负载）、`HookVerdict`（continue/block/shortCircuit，对标 Claude Code exit 0/2）、`BotHook` 接口、`HookRegistry`（按 event 分组 + `fire`，单 hook 抛异常吞掉视作放行）
- [x] **H1**（纯重构）：`MessageAuditLog` 入向/出向迁为 `InboundAuditHook`（`ON_TEXT_RECEIVED`）+ `OutboundAuditHook`（`PRE_SEND`，支持 BLOCK 跳过发送）；行为零变化
- [x] **H2**：`RateLimiter` 迁为 `RateLimitHook`（`ON_MESSAGE_RECEIVED` 入口门控，超限 `shortCircuit(handled)`），移出 `ModeRouter` 内联
- [x] **H3**：新增 7 个预留事件接线（`PRE_DISPATCH`/`POST_DISPATCH`（带 outcome+durationMs）/`ON_MODE_SWITCH`（`SystemCommandMode`，支持 BLOCK）/`ON_ERROR`（`GameBot` catch-all）/`ON_TURN_COMPLETE`/`ON_STARTUP`/`ON_SHUTDOWN`（`GameApplication`））——已接线、默认无订阅，行为零变化
- [x] **H4**：`config/HookConfig`（`data/hooks-config.json`，仿 `ReliabilityConfig` load+模板）驱动 audit/rateLimit enable/disable，默认全启用
- [x] **装配**：`GameBot` 大构造器注入 `HookRegistry`，经 `ModeContext.hooks()`（15 字段）透出给 `ModeRouter`/`SystemCommandMode`；`BotInstance`/`GameApplication` 装配 + 启停触发
- [x] **Part B**（开发期 Claude Code 守卫，已安装到 `.claude/settings.json`）：`PreToolUse` 两条——Bash 内联 grep 拦 `git push`/`mvn deploy`/`rm -rf`；Edit|Write 经 `.claude/hooks/guard-edit.sh` 按 `file_path` 精确拦 `pom.xml`/`data/*.json`
- [x] 测试：`HookRegistryTest`、`HookContextTest`、`HookVerdictTest`、`InboundAuditHookTest`、`OutboundAuditHookTest`、`RateLimitHookTest`、`HookConfigTest` + `GameBotTest`/`ModeRouterTest`/各 mode 测试更新；`mvn test` 492 全绿（6 live 跳过）
- [x] 文档：新增 `design/hooks.md`（Part A 运行时 hook + Part B 开发期守卫）；更新 `architecture/overview.md`、`ROADMAP.md`（里程碑表 Phase H 行）、`CLAUDE.md`、`doc-maintenance.md`

### 模型/Provider 配置收敛（新增）
- [x] 新增 `llm/ModelsConfig`：`providers`（共享 baseUrl/apiKey/uploadsUrl）+ `chat`/`review`/`bridge` 各引用 provider+model；缺失时从旧 `llm-config.json`/`task-config.json` 迁移并告警
- [x] `data/models-config.json` 作为唯一模型注册表；Chat→`providers.zhipu`、Review/Bridge→共享 `providers.dashscope`（只定义一次）
- [x] `TaskConfig` 去模型/endpoint/token 字段，诚实重命名 `anthropic*`→`dashscope*`（合并重复的 `dashscopeChatBaseUrl`）；由 `GameApplication` 注入
- [x] 文档：`game-application.md`、`architecture/overview.md`、`design/claude-bridge.md`、`design/task-system.md` 同步；`ModelsConfigTest`(4) 新增

## 验收标准
- [x] `mvn compile` 通过
- [x] `mvn test` 全部通过（492 个测试，6 个 live 测试 @EnabledIfEnvironmentVariable 跳过），覆盖率 >= 80%
- [x] 能通过 GameEngine.dispatch 执行种植和收获命令
- [x] 会话状态正确持久化和恢复
- [x] 包名重命名后所有现有测试无回归
- [x] task 子系统可被禁用（task-config.json enabled=false）不影响农场游戏

## 风险
| 风险 | 应对 |
|------|------|
| SDK 接口不明确 | 先 mock 接口开发，后续对接真实 SDK |
| 微信消息长度限制 | ResponseRenderer + TaskMessageHandler.splitMessage 支持拆分 |
| Claude Code CLI 未登录 | 首次使用需本机跑 `claude` 完成 OAuth；task-config.json enabled=false 默认禁用 |
| 视频文件过大 | 默认 50MB 上限，超限直接拒绝并提示 |
| 任务超时（5 分钟） | destroyForcibly + onError 回复"任务超时" |
