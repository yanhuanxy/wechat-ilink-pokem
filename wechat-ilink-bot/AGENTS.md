# AGENTS.md

> Agent / 贡献者快速参考。完整契约见 [CLAUDE.md](CLAUDE.md)，门面见 [README.md](README.md)。

## 项目简介

**wechat-ilink-bot** —— 基于 `wechat-ilink-sdk-java` 3.0.0 + Java 17 + Maven 的**多模式微信机器人**。
框架处理多模式路由、命令解析、会话管理、消息分发与响应格式化；单个模式只需实现 `BotMode`。

## 技术栈基线（不允许擅自升级）

- JDK: 17（可用 Java 17 语法）
- Maven: 3.6.3（由 enforcer 强制）
- SDK: `io.github.lith0924:wechat-ilink-sdk:3.0.0`
- 数据库: SQLite 3.45.x + sqlite-jdbc 3.45.x（WAL 模式）
- HTTP/SSE: okhttp + okhttp-eventsource（MCP 客户端）
- 二维码: ZXing
- 测试: JUnit Jupiter 5.10.x + Mockito 5.x

## 五大模式 + 命令前缀（速查）

`ModeRouter` 按优先级路由（入口 `RateLimiter` 限流）：

| 触发 | 模式 | BotModeType | 做什么 |
|------|------|-------------|--------|
| 普通文本（默认） | Chat | `CHAT` | LLM 对话（流式/同步）；未配 LLM 原样回显 |
| `#` 前缀 | Farm | `FARM` | 帮帮农场（22 命令，经 GameEngine） |
| `/mode claude` 后文本 / CLAUDE 媒体 | Claude Bridge | `CLAUDE` | `claude` 子进程（`--resume`）+ 双向文件回传 |
| 上传视频（抢占式） | Review | `REVIEW` | 视频评测（Claude Code / DashScope） |
| `!` 前缀 | Autogame | `AUTOGAME` | MCP 调 autogame-xcx |
| `/` 前缀 | 系统命令 | — | `/mode` `/new` `/sessions` `/resume` `/help` `/status` |

`CHAT/CLAUDE/AUTOGAME` 可经 `/mode` 切换并持久化（`player.bot_mode`）；`FARM/REVIEW` 抢占式触发，无需切换。

## 快速导航

| 你想做什么 | 去哪里看 |
|-----------|---------|
| 项目门面 / 定位 | README.md |
| 后续路线 / 项目走向 | docs/ROADMAP.md |
| 了解系统架构 | docs/architecture/overview.md |
| 了解层边界 | docs/architecture/boundaries.md |
| 了解消息流 | docs/architecture/data-flow.md |
| 多模式路由设计 | docs/design/mode-router.md |
| MCP / autogame 模式 | docs/design/mcp-autogame.md |
| Claude Bridge 模式 | docs/design/claude-bridge.md |
| 视频任务子系统 | docs/design/task-system.md |
| 可靠性（重试/限流/自愈/刷盘） | docs/design/reliability.md |
| 会话管理 | docs/conventions/session-management.md |
| GameApplication 设计 | docs/design/game-application.md |
| GameBot 设计 | docs/design/game-bot.md |
| GameEngine 设计 | docs/design/game-engine.md |
| CommandParser 设计 | docs/design/command-parser.md |
| ResponseRenderer 设计 | docs/design/response-renderer.md |
| 农场游戏设计 | docs/design/farm-game.md |
| 编码规范 | docs/conventions/README.md |
| 命名约定 | docs/conventions/naming.md |
| 依赖注入 | docs/conventions/di.md |
| 错误处理 | docs/conventions/error-handling.md |
| 日志规范 | docs/conventions/logging.md |
| 测试规范 | docs/conventions/testing.md |
| 命令模式 | docs/conventions/command-pattern.md |
| 命令完整规范 | docs/reference/command-spec.md |
| 错误码表 | docs/reference/game-error-codes.md |
| 当前迭代 | docs/plans/current-sprint.md |

## 包结构

```
com.github.wechat.ilink.bot/
├── GameApplication.java          # 组合根：加载配置、组装多 BotInstance、长轮询、优雅关闭
├── BotInstance.java              # 单微信账号实例（多实例 + 扫码登录重试 + 欢迎消息）
├── BotConfig.java                # 账号配置 POJO（data/bots.json）
├── GameBot.java                  # SDK 消息桥 + ModeSender/MediaDownloader 实现，路由委托 ModeRouter
│
├── mode/                         # 框架层 - 多模式路由
│   ├── BotMode / BotModeType / ModeOutcome     # 模式接口/枚举/路由结果
│   ├── ModeRouter.java           # 按优先级路由：限流 → 视频 → # → / → ! → ticket → 当前模式
│   ├── ModeContext.java          # 不可变依赖载体（14 字段）
│   ├── ModeSender.java           # 出向发送回调（sendText/Image/File/Video + typing）
│   ├── MediaDownloader.java      # 入向媒体下载 seam（对称 ModeSender）
│   ├── RetrySender.java          # ModeSender 装饰器（发送重试）
│   ├── RateLimiter.java          # per-user 限流
│   ├── ChatMode / FarmMode / ReviewMode / SystemCommandMode / ClaudeBridgeMode / AutogameMode
│   └── claude/                   # Claude Bridge 适配层
│       ├── ClaudeCodeAdapter / ClaudeAdapterCallback / ClaudeSession
│       └── BridgeFileBuffer / BridgeWorkspace        # 双向文件回传
│
├── engine/                       # GameEngine · CommandParser · ResponseRenderer
├── command/                      # Command · CommandRegistry · CommandResult · ParsedCommand · QrCodeProvider
├── session/                      # SessionManager · PlayerSession · FlushGate（持久化合并）
├── persistence/                  # DatabaseManager + Player/FarmPlot/Inventory/ActionRank/ClaudeSession Repository
├── llm/                          # LlmConfig · LlmProvider · OpenAiProvider · LlmRequestQueue · ChatHistoryManager · SseParser · StreamCallback · ChatMessage
├── task/                         # TaskProvider/Config/Request · ClaudeCodeProvider · DashScopeVideoProvider/Uploader · VideoTaskBuffer · WorkspaceManager · SkillInstaller · TaskMessageHandler
├── mcp/                          # McpClient · McpToolRegistry · McpHealthMonitor · McpTool · McpToolResult · AutogameConfig
├── config/                       # ReliabilityConfig（可靠性 8 旋钮）
├── util/                         # TextFormatter · Clock · QrCodeGenerator
│
└── farm/                         # 实现层 - 帮帮农场
    ├── FarmGame.java             # 游戏入口（注册命令）
    ├── handler/                  # 22 个命令处理器
    └── model/                    # 领域模型（Crop/CropRegistry/CropStage/FarmPlot/Inventory/Weather）
```

## 硬性规则

1. 依赖方向：Application → Framework → Implementation，严格向下；`mode/` 包零 SDK import（经 `ModeSender`/`MediaDownloader` 回调 `GameBot`）
2. 无 DI 框架：构造器注入，`GameApplication` 是唯一组合根
3. 禁止 `System.out/printStackTrace`，统一 SLF4J 结构化日志
4. 单文件 ≤ 400 行；单方法 ≤ 60 行
5. 新功能必须有 JUnit 5 测试，覆盖率 ≥ 80%
6. 所有 SDK 交互通过 `GameBot`（应用层/框架层禁止直接 import `ILinkClient`）
7. 敏感数据（botToken、contextToken）不得出现在日志
8. 命令处理 ≤ 2 秒返回（微信交互约束；长任务如 `!run`/Claude Bridge 异步执行）
9. 代码变更后按 `.claude/rules/doc-maintenance.md` 同步文档
