# Claude Bridge 模式设计

> Phase 2。让 `/mode claude` 后的普通文本走 `claude` CLI 子进程，并支持**跨消息会话延续**：
> 首条消息建会话 → 后续消息 `--resume <session_id>` 复用上下文；会话元数据持久化到 SQLite。

## 与视频点评（Review）的区别

Review 现走 `task/DashScopeVideoProvider`（DashScope OpenAI 兼容端点直连，不经 claude 子进程）；Bridge 走 `mode/claude/ClaudeCodeAdapter`（claude CLI 子进程 + stream-json）。两者实现完全独立，互不影响。

| 维度 | Review（`DashScopeVideoProvider`） | Bridge（`ClaudeCodeAdapter`） |
|------|-----------------------------------|------------------------------|
| 触发 | 上传视频（抢占式） | `/mode claude` 后的普通文本 |
| 执行 | DashScope 直连 HTTP（OSS 上传 + `/chat/completions`） | claude CLI 子进程 |
| 会话 | 单次任务，无延续 | 首条建会话，后续 `--resume` 续传 |
| 输出 | SSE 解析为点评文本 | init 事件回传 `session_id`，作为后续 `--resume` 参数 |

## 组件

```
ClaudeBridgeMode (implements BotMode, type=CLAUDE)
  ├── ClaudeCodeAdapter            # ProcessBuilder + stream-json 解析；--resume 续传
  │     └── ClaudeAdapterCallback  # onSessionId / onToken / onComplete / onError
  ├── ClaudeSessionRepository      # claude_sessions 表 CRUD
  └── ExecutorService              # daemon fixed(2)，子进程不阻塞 SDK 消息线程
```

- `ClaudeSession`：会话元数据 POJO（`sessionId / userId / cwd / model / title / createdAt / updatedAt`，全 `private final`）。
- 活跃会话 ID 存于 `PlayerSession.activeClaudeSessionId`（**transient**，内存态，不入库）——
  `SystemCommandMode`（`/new`、`/resume`）与 `ClaudeBridgeMode` 持有同一 `PlayerSession`，状态天然共享。

## 子进程协议（已 spike 验证）

```
claude -p "<prompt>" --output-format stream-json --verbose
        [--model <M>] --permission-mode <mode> [--resume <session_id>]
```

- init 事件返回 `session_id`：`{"type":"system","subtype":"init",...,"session_id":"857e1a35-..."}`。
- `--resume <session_id>` 跨进程有效：init 事件回显同一 `session_id`，无 "session not found"。
- 参数构造见 `ClaudeCodeAdapter.buildArgs`；`startProcess` 可被测试覆写以注入假 Process。
- `StreamConsumer` 区分两类文本，避免重复：`streamedText`（`content_block_delta` / `message.content` 的实时 token）
  与 `resultText`（`type==result` 行的权威终值）；`getFullText()` 优先返回 `resultText`。
- `is_error: true`（如 403 free quota exhausted）→ `onError`。

## 消息流

```
handleText(ctx, session, text)
  ├── adapter == null → 回复"Claude Bridge 未启用…"（task 未启用兜底）
  ├── resumeSessionId = session.getActiveClaudeSessionId()（可空=新会话）
  ├── sender.startTyping
  └── executor.submit → adapter.run(userId, prompt, resumeSessionId, callback)
        ├── onSessionId(id)
        │     ├── 新会话 → repository.insert(ClaudeSession，title=prompt 前 40 字)
        │     │              + session.setActiveClaudeSessionId(id)
        │     └── 续传   → repository.touchUpdatedAt(id)
        ├── onToken → 刷新 typing
        ├── onComplete → TaskMessageHandler.splitMessage(2000) → 多条 sendText
        │                 + session.incrementClaudeTurn()（累计轮次，供自动压缩判定）
        └── onError → 友好提示 + stopTyping
```

- `privileged = session.isClaudePrivileged()`（默认 `false`）随 `adapter.run` 透传，决定受限档还是提权档（见下「权限两档」）。
- `adapter.run` **之前**经 `maybeCompact` 判定是否需自动压缩上下文（见下「自动压缩」）。

## 权限三档与工作空间隔离

> **最终权限模型为二元制**（受限只读 / admin 提权 bypass）。逐次工具交互审批（`--permission-prompt-tool`）**已决策不做**（NO-GO，见 [claude-bridge-phase3.2-spike.md](claude-bridge-phase3.2-spike.md) 决策更新）：对已能提权者不增安全、只添脆弱面，且远程侧损效率。下述 `plan`/`approve` 为**可选只读评审**入口，非必经路径；真正的低摩擦提权是 admin 的 `/sudo` 逐会话档。

子进程**默认受限**；`/plan on` 切只读计划档产出方案文本；`/sudo on`（管理员）/`/approve`（管理员）切提权档执行。三档由 `PlayerSession` 的 transient 标志（`claudePrivileged` / `claudePlanMode`）切换，`ClaudeCodeAdapter.buildArgs` 按优先级 **bypass > plan > default** 分发：

| 档 | 触发 | `--permission-mode` | 工具策略 | 子进程 cwd |
|----|------|---------------------|----------|-----------|
| 受限（默认） | 无需操作 | `claudeBridgePermissionMode`（`default`） | `claudeBridgeAllowedTools`（只读 `Read,LS,Glob,Grep`）+ `claudeBridgeDisallowedTools`（`Bash,Write,Edit,NotebookEdit`） | `claudeBridgeCwd/<sanitize(userId)>` |
| plan | `/plan on`（所有用户） | `claudeBridgePlanMode`（`plan`） | 仅 `claudeBridgeAllowedTools`（只读白名单），不下黑名单（plan 模式本身禁写） | 同上 |
| 提权 | 管理员 `/sudo on` 或 `/approve` | `claudeBridgePrivilegedMode`（`bypassPermissions`）**+ `--dangerously-skip-permissions`** | 不下发（bypass 忽略） | 同上 |

- **工作空间隔离**：`ClaudeCodeAdapter.run` 以 `Paths.get(cwd, BridgeWorkspace.sanitize(userId))` 为子进程 cwd；受限档与 plan 档均为只读白名单、无 `--add-dir`，工作目录之外不可达 → **用户 A 读不到用户 B 的内容**。`/resume` 经 `SystemCommandMode.resolveSession` 按 `userId` 校验归属，且各用户 cwd 不同（claude 按 cwd 分目录存会话），跨用户续传亦不可行。
- plan 档只读（与 default 同安全级），故 `/plan`、`/approve` 对所有用户开放；提权档绕过工具与目录限制（可管控宿主机），故 `/sudo` 仅对 `claudeAdminUsers` 白名单内的 userId 可见、可用，非白名单命中即当作未知命令，不暴露该指令存在。
- 三档均为 transient：重启或对应 off 命令回收，默认回到受限。
- **管理员默认提权（opt-in）**：`claudeBridgeAdminDefaultPrivileged=true` 时，白名单内 admin 经 `/mode claude` 进入 CLAUDE 模式即在 `SystemCommandMode.handleMode` 中默认切提权档，省去开场 `/sudo on`（远程效率）。默认 `false`（对现状零影响）；提权仍为 transient，`/sudo off` 或重启回收。**已知限制**：重启后若持久 `bot_mode=CLAUDE` 被直接恢复（未再经 `/mode claude`），不自动提权，需重新进入模式或 `/sudo on`——刻意从严。
- **提权档在 headless `-p` 下必须额外带 `--dangerously-skip-permissions`**（P0 修复）：`--permission-mode bypassPermissions` 单独不够——进入 bypass 仍要一次危险模式确认，非交互环境无法预接受，claude 会静默跑在受限等效档、Write/Edit/Bash 被收走（提权形同虚设，实测根因）。该 flag 等价于宿主 `~/.claude/settings.json` 的 `skipDangerousModePermissionPrompt: true`（宿主交互式 bypass 能用的原因）；子进程配置目录被隔离、读不到宿主那份，故必须在 argv 显式下发。

### plan → approve → 执行 闭环

Bridge 是 headless `-p` 单次模式（每条消息 fork 一个 claude 进程 + `--resume` 续传），**不能**像交互式 TUI 那样 plan 后在同进程内"暂停等批准再执行"。执行闭环靠 `--resume` 跨消息衔接 + `/approve` 切执行档：

```
/plan on            → claude 以 plan 档产出方案（只读，result 文本返回）
/approve            → 置一次性 approved 标志；管理员自动切 bypass（非管理员保持只读档）+ 提示
发送"执行"（或任意）→ ClaudeBridgeMode 拼执行前缀 + --resume 同会话 + 当前档 → claude 执行计划
```

- `/approve` 对齐 `/resume` 模式：`SystemCommandMode` 只"设标志 + 切档 + 提示"（不持有 adapter），执行轮由 `ClaudeBridgeMode.handleText` 在下一条消息消费 `claudeApprovedExec`（拼"请执行上一轮提出的计划"前缀，**一次性**：消费即清，切会话/重启亦清）。
- 执行写文件/跑命令需 bypass 档 → **非管理员** `/approve` 仅能完成只读部分，写操作被拒并提示联系管理员。

## 自动压缩（按轮次触发 /compact）

长对话上下文过长会拖慢/超限。`ClaudeBridgeMode.maybeCompact` 在处理用户消息**之前**判定：

```
compactThreshold > 0 && 非新会话 && 有 resumeSessionId && session.getClaudeTurnCount() >= compactThreshold
  → adapter.run(userId, "/compact", resumeSessionId, privileged, CompactCallback)   # 额外阻塞子进程
  → 成功 → session.resetClaudeTurnCount() + 提示「🗜️ 上下文较长，已自动压缩历史对话」
  → 失败 → 仅记日志，不阻断后续正常回复
```

- 轮次计数 `PlayerSession.claudeTurnCount`（transient）：每条成功回复 `incrementClaudeTurn()`；切换会话（`/new`、`/resume`、新会话首次赋 id）时归零（见 `setActiveClaudeSessionId`）。压缩内部轮不计数。
- 阈值由 `claudeBridgeCompactThreshold` 配置，**默认 `0`（关闭）**。
- 机制说明：适配器仅以 `claude -p <prompt>` 单次传参（无常驻 stdin），故"压缩"实现为对同一会话额外跑一次 `claude -p "/compact" --resume <sid>` 子进程。`/compact` 在 headless `-p` 模式的真实行为由 `ClaudeCodeAdapterLiveTest`（env-gated）验证；单测以假适配器验证编排顺序。

## 会话管理命令（SystemCommandMode）

| 命令 | 行为 |
|------|------|
| `/mode claude` | `setCurrentMode(CLAUDE)` + 持久化 `player.bot_mode` + 引导文案 |
| `/new` | `setActiveClaudeSessionId(null)`，下一条消息建新会话 |
| `/sessions` | `findByUserIdOrderByUpdatedDesc(10)`，列「序号 + 标题 + 更新时间」 |
| `/resume <序号\|id>` | 解析为 `sessionId` → `setActiveClaudeSessionId` →（必要时）切到 CLAUDE |
| `/sudo [on\|off\|status]` | **仅管理员**（`claudeAdminUsers`）：切换本会话 Claude 提权档（bypass） |
| `/plan [on\|off\|status]` | 切换 plan 档（`--permission-mode plan`，只读产出方案）；**所有用户可用**；与 `/sudo` 互斥 |
| `/approve` | 置一次性 approved 标志（下一条消息执行上一轮计划）；管理员自动切 bypass，非管理员保持只读 |

重启后活跃会话丢失（transient），用户用 `/sessions` + `/resume` 恢复。

## 配置

**模型与 endpoint/token 在 `models-config.json`**：`bridge.model` 指定主模型；`bridge.provider` 引用 `providers.<name>` 取该 provider 的 `baseUrl`/`apiKey`（DashScope 场景下通常与 `DashScopeVideoProvider` 共用 `providers.dashscope` —— DashScope 用 `/apps/anthropic/v1` 提供 Anthropic 协议兼容，与 `/compatible-mode/v1` 的 OpenAI 协议分离）。由 `GameApplication.injectModelConfig` 按 `bridge.provider` 解析成 `TaskConfig.claudeBridgeBaseUrl`/`claudeBridgeApiKey`（未声明/未找到则回退到 `dashscope*` 字段），`bridge.model` 经 `--model` 旗标驱动主对话模型。`bridge.model` 为空时 `ClaudeCodeAdapter(config)` 单参构造抛 `IllegalStateException`。

**子进程环境（`ClaudeCodeAdapter.applyBridgeEnv`，自包含）**：启动环境理论干净，程序仍**先清掉所有继承的 `ANTHROPIC_*`** 保证确定性，再下发完整一套——由 `bridge.model`/`bridge.smallModel` **派生** `ANTHROPIC_MODEL` + `ANTHROPIC_SMALL_FAST_MODEL` + `ANTHROPIC_DEFAULT_HAIKU/SONNET/OPUS_MODEL` + `ANTHROPIC_REASONING_MODEL`（haiku 用 `smallModel`，为空则用 `bridge.model`；其余用 `bridge.model`）。**关键**：claude 内部按 haiku/sonnet/opus/reasoning 角色选模型，缺失的角色会**回落到内置 Claude 模型名**（DashScope 不存在 → `400 [1211] 模型不存在`），故必须下发完整一套。配 `ANTHROPIC_BASE_URL`（bridgeBaseUrl）+ `ANTHROPIC_AUTH_TOKEN`（**Bearer**；curl 实测 `/apps/anthropic` 下 Bearer 与 x-api-key 均可用，代码统一用 Bearer）+ `CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1`、`ENABLE_TOOL_SEARCH=0`（关闭 Anthropic 专有、第三方兼容端点不支持的特性）。**关键（实测根因）**：`applyBridgeEnv` 另设 `CLAUDE_CONFIG_DIR=<claudeHome 绝对路径>`（用 `Paths.get(claudeHome).toAbsolutePath()` 下发——`task-config.json` 里的 `claudeHome` 常是相对字符串、会覆盖构造器经 `AppPaths.data()` 的绝对默认；若直接下发相对值，子进程会按自身 cwd `claudeBridgeCwd/<userId>` 二次解析到不存在位置，导致隔离配置目录与 `SkillInstaller` 装的 skill 全部失效），让子进程用独立配置目录、**不继承宿主 `~/.claude/settings.json`**——否则 claude 读到宿主 settings.json（如 ccswitch 激活的 GLM 配置）与本配置冲突 → `400 [1211] 模型不存在`（即便 key/端点/模型本身都有效）。`startProcess` 另以 INFO 打印下发的完整模型参数（token 脱敏），便于排查 1211。

**运维字段在 `task-config.json`**：

| 字段 | 默认 | 说明 |
|------|------|------|
| `claudeBridgeEnabled` | `false` | 为 `true` 才构造 `ClaudeCodeAdapter`，否则 mode 收到 null adapter → 回复未启用 |
| `claudeBridgeCwd` | `data/claude-workspace` | 子进程工作目录根；实际 cwd 为 `<此值>/<sanitize(userId)>`（每用户隔离） |
| `claudeBridgeMaxHistorySessions` | `50` | 预留 |
| `claudeBridgePermissionMode` | `default` | 受限档 `--permission-mode`；配合下方白/黑名单 |
| `claudeBridgeAllowedTools` | `["Read","LS","Glob","Grep"]` | 受限档 `--allowedTools`（只读）。空则不追加 |
| `claudeBridgeDisallowedTools` | `["Bash","Write","Edit","NotebookEdit"]` | 受限档 `--disallowedTools`。空则不追加 |
| `claudeBridgePlanMode` | `plan` | plan 档（`/plan on`）`--permission-mode`；工具复用 `claudeBridgeAllowedTools`、不下黑名单 |
| `claudeBridgePrivilegedMode` | `bypassPermissions` | 提权档（`/sudo on` 或 `/approve`）`--permission-mode` |
| `claudeAdminUsers` | `[]` | 可执行 `/sudo` 提权的微信 userId 白名单；空=无人可提权 |
| `claudeBridgeCompactThreshold` | `0` | 自动压缩阈值；`0`=关闭，`>0`=每会话累计 N 轮后下一条消息前自动 `/compact` |
| `claudeBridgeAdminDefaultPrivileged` | `false` | `true` 时白名单内 admin 经 `/mode claude` 进入即默认提权档，省去开场 `/sudo on`；提权仍为 transient，`/sudo off` 或重启回收 |

> 注：`permissionMode` / `allowedTools` / `disallowedTools`（无 `claudeBridge` 前缀）是已移除的 Claude Code 视频点评流程遗留的工具策略字段；Review 现走 `DashScopeVideoProvider` 直连、不经 claude 子进程工具策略。Bridge 的工具策略由上表 `claudeBridge*` 字段控制。

> **工具策略（Phase 3）**：Bridge 由 `ClaudeCodeAdapter.appendBridgePolicy` 把 `claudeBridgeAllowedTools` / `claudeBridgeDisallowedTools`
> 拼到子进程参数（`--permission-mode` 之后）。**白名单仅在非 `bypassPermissions` 模式下生效**；两列表默认空，行为与今日一致（opt-in）。
> 逐次交互审批（Phase 3.2）**已决策不做**（NO-GO，见 [claude-bridge-phase3.2-spike.md](claude-bridge-phase3.2-spike.md)）；权限收敛为二元制。

`claude_sessions` 表始终建好、`ClaudeSessionRepository` 始终可用，与 adapter 是否启用无关。

## 双向文件回传（Phase 4）

CLAUDE 模式下支持用户上传图片/文件喂给 Claude，并把 Claude 产物回传给用户。**AES-128-ECB 解密 + CDN 上传/下载由 SDK 完成**，Bot 侧只做票据缓冲、工作目录管理与按扩展名分发。

### 组件

```
ModeRouter（CLAUDE 模式）
  └── handleClaudeFileIntake
        └── MediaDownloader.downloadImage/downloadFile   # seam，GameBot 实现 → SDK CDN+AES
        └── ClaudeBridgeMode.bufferIncomingFile → BridgeFileBuffer  # per-user 60s 票据

ClaudeBridgeMode.handleText（下一条文字触发）
  └── fileBuffer.consume → BridgeWorkspace.writeInput / freshOutputDir
  └── augmentPrompt(原始 text, inputPath, outputPath) → effectivePrompt  # 告知 Claude 文件位置
  └── ClaudeCodeAdapter.run(effectivePrompt, ...)        # 子进程读 input、产物写 output
        └── onComplete → sendOutputFiles
              └── BridgeWorkspace.collectOutputs → ModeSender.sendImage/sendFile/sendVideo
                                                          # GameBot 实现 → SDK CDN 上传
```

- `MediaDownloader`（`mode/` seam，对称 `ModeSender`）：`downloadImage(item)`/`downloadFile(item)`，由 `GameBot` 委托 `client.downloadImageFromMessageItem/downloadFileFromMessageItem`。
- `BridgeFileBuffer`：per-user 单票据，60s TTL，后台线程清理；仿 `task.VideoTaskBuffer`。票据一次性（`consume` 后即移除）。
- `BridgeWorkspace`：`<claudeBridgeCwd>/<userId>/{input,output}`。入向 `writeInput`（文件名净化，默认 `input.bin`）；出向 `freshOutputDir`（每回合先清空防重复回发）+ `collectOutputs`（仅常规文件）。
- `ClaudeBridgeMode.sendOutputFiles`：按扩展名分发 —— 图（jpg/png/gif/...）→ `sendImage`，视频（mp4/mov/...）→ `sendVideo`，其余 → `sendFile`；上限 10 个文件 / 单文件 50MB，超限跳过。

### 配置（复用 `task-config.json`）

| 字段 | 默认 | 说明 |
|------|------|------|
| `bufferTtlMs` | `60000` | 入向票据 TTL（与视频缓冲共用） |
| `maxVideoBytes` | `50MB` | 入向单文件上限（与视频共用） |
| `claudeBridgeCwd` | `data/claude-workspace` | 工作目录根 |

### 生命周期

`BotInstance.buildClaudeMode` 在 bridge 启用时构造 `BridgeFileBuffer`（`startCleanup()`）+ `BridgeWorkspace` 注入 `ClaudeBridgeMode`；返回值含 buffer 引用，`BotInstance.shutdown()` 调 `fileBuffer.shutdown()`，对称 `VideoTaskBuffer`。bridge 未启用时 buffer 为 null，无需清理。

## 约束

Java 17；字段 `private final` + 构造器注入；单文件 ≤ 400 行 / 单方法 ≤ 60 行；
统一 SLF4J，不打印 authToken；`mode/` 包零 SDK import（经 `ModeSender` 回发，子进程交互封装在 `claude/` 子包）。
