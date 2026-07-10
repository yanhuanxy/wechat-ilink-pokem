# Hooks 子系统设计

> **状态：已实现（Phase H1–H4 + Part B）。** Bot 运行时生命周期 hook 子系统已落地（`mode/hook/` + `config/HookConfig`）；Part B 的开发期 Claude Code 守卫已安装到 `.claude/settings.json`（Edit|Write 经 `.claude/hooks/guard-edit.sh`）。

## 什么是 harness（概念入门）

**harness（驾驭框架 / 脚手架）= 围绕"智能体"的那一圈胶水代码**。常被引用的一句话：*"agent = 模型 + 你围绕它搭建的一切"*——这"一切"（读输入、决定调谁、执行、拿结果、再输出、出错重试、记日志）就是 harness。harness 本身不是"大脑"，而是把大脑变成可用系统的那套管道。Claude Code 就是一个 harness：Claude 模型外面套着一层循环，读 prompt → 选工具(Bash/Edit/…) → 执行 → 喂回结果 → 做权限控制。

**本项目的"运行时 harness"** 指这条编排主干：

```
微信SDK ──► GameBot        (SDK 边界：收消息 / 发消息 / 下载媒体)
              │ onMessages()
              ▼
          ModeRouter        (路由器：按规则决定"谁来处理")
              │ route()  视频 → # → / → ! → ticket → 当前模式；入口顺带限流/审计
              ▼
          BotMode 执行体    (真正的"能力"，≈ harness 里的工具)
            ChatMode(LLM) / FarmMode(游戏) / ClaudeBridge(子进程) /
            ReviewMode(视频) / AutogameMode(MCP) / SystemCommand(/mode)
              │   经 ModeContext 拿依赖；经 ModeSender 发回复
              ▼
          ModeSender = RetrySender → GameBot → SDK
```

| 本项目组件 | harness 角色 | 类比 Claude Code |
|---|---|---|
| `GameBot` | 收发 IO 边界 | 工具执行 + 输出层 |
| `ModeRouter` | 调度 / 路由 | agent loop 决定调哪个工具 |
| 各 `BotMode` | 能力 / "工具" | Bash / Edit / Read |
| `RetrySender`/`RateLimiter`/`FlushGate`/`McpHealthMonitor` | 横切策略（可靠性四件套） | 权限层 + hooks |

能力住在 mode 里（Chat 用 LLM、Claude 用子进程…），**harness 是把这些能力路由起来、并裹上可靠性/审计的那层管道**——正是 `GameBot` + `ModeRouter` 干的事（详见 [mode-router.md](mode-router.md)、[reliability.md](reliability.md)）。

**"运行时 harness" vs "开发期 harness"** 是两套不同的 harness，勿混：
- **运行时 harness** = bot 自己，正在跑、服务真实微信用户的那套（`GameBot`/`ModeRouter`）。
- **开发期 harness** = Claude Code（由 `.claude/settings.json` 配置），即*用来开发本项目*的工具。

> 下文 Part A 的"运行时 harness 缺少 hooks"，即指：bot 自己处理消息的这层管道**没有可插拔拦截点**——审计/限流/错误兜底写死在 `ModeRouter`/`GameBot` 里，而非可加/删/调序的 hook。这正是本提案要补的缺口。

## 背景

主流 agent harness（Claude Code / Cursor / Codex）有一个共识：**agent = 模型 + 围绕它的一切**——prompts、tools、context policies、**hooks**、sandboxes、feedback loops；每个生产级 harness 本质是"一个 while-loop + tool registry + permission layer"。其中 **hook** 是 harness 在特定**生命周期事件**触发的逻辑，并通过**控制流协议**（Claude Code 用 `exit code 0=放行 / 2=阻断` + JSON stdout）把审计 / 限流 / 审批 / 通知等横切关注点从主流程解耦。

本项目把 `GameBot` + `ModeRouter` 视作 harness——它同样在做"接收 → 路由 → 分发到执行体（mode）→ 收发"的编排。但同类横切逻辑目前**全部硬编码**，没有可插拔的 hook 抽象：

- `MessageAuditLog.inbound` 写死在 [`ModeRouter:99`](mode-router.md) 路由入口
- `MessageAuditLog.outbound` 写死在 `GameBot` 的 5 个 `sendXxx` 方法
- `RateLimiter.tryAcquire` 写死在 `ModeRouter:85`，是唯一门控、无 allow/block 协议
- catch-all 错误兜底写死在 `GameBot.onMessages` 的 try/catch
- `/mode X` 在 `SystemCommandMode.handleMode` 直接 `setCurrentMode`，无 before/after

唯一的 hook-like 模式是 [`RetrySender`](reliability.md)（`ModeSender` 装饰器），但只覆盖出向发送一侧。

此外，本项目开发期 Claude Code 配置 `.claude/settings.json` 已挂 PreToolUse 守卫：`guard-edit.sh`（Edit|Write 匹配）阻断对 `pom.xml`（技术基线）与 `data/*.json`（凭证）的自动编辑，另有 Bash 匹配器阻断 `git push`/`mvn deploy`/`rm -rf`。CLAUDE.md 的其余硬约束（Java 版本、禁 Spring/Lombok 等）仍只靠提示词软约束。

本文给出**两层 harness** 的 hook 设计：Part A 是 Bot 运行时生命周期 hook 子系统；Part B 是开发期 Claude Code hooks 的建议配置。

---

## Part A — Bot 运行时 harness：生命周期 hook 子系统

### A1. 主流 harness ↔ 本项目对照

| 本项目事件 | 触发时机 | 实现（hook） |
|---|---|---|
| `ON_MESSAGE_RECEIVED` | 入口：`touchActivity` 之后、视频/文本路由之前（每条消息） | `RateLimitHook`（per-user 限流，超限 short-circuit 并发繁忙提示） |
| `ON_TEXT_RECEIVED` | `extractText` 非空之后、`#`/`/`/`!` 前缀路由之前 | `InboundAuditHook`（入向审计） |
| `PRE_DISPATCH` / `POST_DISPATCH` | `current.handleText` 前后 | （默认无订阅）可挂 per-mode 门控 / 耗时埋点（POST_DISPATCH 带 outcome+durationMs） |
| `PRE_SEND` | 每个 `ModeSender.sendXxx` 之前 | `OutboundAuditHook`（出向审计；支持 BLOCK 跳过发送） |
| `ON_MODE_SWITCH` | `/mode`、`/resume` 切换 `setCurrentMode` 之前 | （默认无订阅）可挂切换门控（from→to，支持 BLOCK） |
| `ON_ERROR` | `GameBot.onMessages` catch-all | （默认无订阅）可挂告警（带 throwable） |
| `ON_TURN_COMPLETE` | 单条消息回合结束 | （默认无订阅）可挂 latency 指标（带 durationMs） |
| `ON_STARTUP` / `ON_SHUTDOWN` | `GameApplication.start()` / `shutdown()` | （默认无订阅）可挂应用级初始化 / 清理 |

### A2. hook 模型

遵循项目约定（Java 17、无 DI、构造器注入、单文件 ≤400 行、`mode/` 零 SDK import）。**复用已有概念，不另造并行体系**。

```java
/** 生命周期事件，对标 Claude Code 的 PreToolUse/PostToolUse/Stop/... */
public enum HookEvent {
    ON_MESSAGE_RECEIVED,  // 入口门控（≈ UserPromptSubmit）：touchActivity 后、路由前；限流在此
    ON_TEXT_RECEIVED,     // extractText 非空后、前缀路由前；入向审计在此
    PRE_DISPATCH,         // current.handleText 前（permission gate）
    POST_DISPATCH,        // handleText 返回后（带 outcome + durationMs）
    PRE_SEND,             // 出向 send 前（审计 / 内容过滤）
    ON_MODE_SWITCH,       // /mode、/resume 切换前（from→to）
    ON_ERROR,             // catch-all 异常（带 throwable）
    ON_TURN_COMPLETE,     // ≈ Stop：一回合结束（带 durationMs）
    ON_STARTUP, ON_SHUTDOWN
}

/** 不可变事件负载（嵌套 Builder 构造，字段按事件按需填） */
public final class HookContext {
    String userId; String text; PlayerSession session;
    BotModeType targetMode; BotModeType fromMode; BotModeType toMode;
    ModeOutcome outcome; Throwable throwable; long durationMs; String sendKind;
}

/** 控制流协议，对标 Claude Code exit code 0/2 + JSON stdout */
public final class HookVerdict {
    public static HookVerdict continue_() { ... }              // ≈ exit 0 放行
    public static HookVerdict block(String reason) { ... }      // ≈ exit 2 阻断 + 安全提示
    public static HookVerdict shortCircuit(ModeOutcome o) { ... } // 直接给定结局（如限流→handled）
}

public interface BotHook {
    HookEvent event();
    HookVerdict handle(HookContext ctx);
}
```

- **`HookRegistry`**：按 `HookEvent` 分组的有序 `BotHook` 列表；`ModeRouter` / `GameBot` 在对应时机迭代 `fire(event, ctx)`，遇 `block`/`shortCircuit` 即止；单个 hook 抛异常被吞掉（记 WARN，视作放行）。
- **注册**：在 `GameBot` 大构造器注入（`HookConfig` 控制开关），经 15 参 `ModeContext.hooks()` 透出给 `ModeRouter` / `SystemCommandMode`——不引入 DI。
- **审计复用**：`MessageAuditLog` 不删，包装成 `InboundAuditHook`（`ON_TEXT_RECEIVED`）+ `OutboundAuditHook`（`PRE_SEND`）；`RateLimiter` 包装成 `RateLimitHook`（`ON_MESSAGE_RECEIVED`，超限返回 `shortCircuit(handled())`）。
- **`RetrySender` 不并入 hook**：它是发送侧重试装饰器，与生命周期 hook 正交；强行合并会破坏 `mode/` 的装饰器对称性（见 [reliability.md](reliability.md)）。

### A3. 落地点（已落地 H1–H3）

| 时机 | 文件 | 实现 |
|---|---|---|
| 限流门控 | `mode/ModeRouter.java`（route 入口） | `ON_MESSAGE_RECEIVED` hook（`RateLimitHook`），shortCircuit 返回 handled |
| 入向审计 | `mode/ModeRouter.java`（extractText 后） | `ON_TEXT_RECEIVED` hook（`InboundAuditHook`）；`touchActivity` 保持内联 |
| 模式分发前后 | `mode/ModeRouter.java`（current.handleText） | 包 `PRE_DISPATCH` / `POST_DISPATCH`（POST_DISPATCH 带 outcome+durationMs） |
| 出向审计 | `GameBot.java` 5 个 `sendXxx` | `PRE_SEND` hook（`OutboundAuditHook`），GameBot 经 `preSend()` 门控后委托 SDK |
| 错误兜底 | `GameBot.java`（onMessages catch） | 先 `ON_ERROR`，再既有兜底 `sendText` |
| 模式切换 | `mode/SystemCommandMode.java`（handleMode/handleResume） | `setCurrentMode` 前触发 `ON_MODE_SWITCH`（支持 BLOCK） |
| 应用启停 | `GameApplication.java`（start/shutdown） | 每 bot 触发 `ON_STARTUP` / `ON_SHUTDOWN` |

### A4. 配置落点

新增 `config/HookConfig.java`（`data/hooks-config.json`，仿 [`ReliabilityConfig`](reliability.md) 的 load + 模板模式），控制各 hook 的 enable/disable 与顺序。**不混入 `ReliabilityConfig`**（职责单一，避免 8 旋钮继续膨胀）。默认全启用既有 hook、行为零变化。

### A5. 分阶段路线

| Phase | 内容 | 状态 |
|-------|------|------|
| **H1**（纯重构） | 引入 `BotHook`/`HookEvent`/`HookVerdict`/`HookRegistry`，把 `MessageAuditLog`（入向 + 出向）迁成 hook | ✅ 已完成，全测试绿，行为零变化 |
| **H2** | `RateLimiter` 迁为 `ON_MESSAGE_RECEIVED` 入口 hook（verdict 协议），移出 `ModeRouter` | ✅ 已完成，限流语义不变 |
| **H3** | 新增 `PRE_DISPATCH`/`POST_DISPATCH`/`ON_MODE_SWITCH`/`ON_ERROR`/`ON_TURN_COMPLETE`/`ON_STARTUP`/`ON_SHUTDOWN` | ✅ 已完成，默认无订阅、行为零变化 |
| **H4** | `HookConfig` 驱动 audit/rateLimit enable/disable | ✅ 已完成，默认全启用 |

### A6. 取舍与风险

- **勿过度设计**：Hook 接口保持最小；Java 17、构造器注入。
- **预留仪器点（非死代码）**：`ON_MESSAGE_RECEIVED`/`ON_TEXT_RECEIVED`/`PRE_SEND` 有内置订阅者；`PRE_DISPATCH`/`POST_DISPATCH`/`ON_MODE_SWITCH`/`ON_ERROR`/`ON_TURN_COMPLETE`/`ON_STARTUP`/`ON_SHUTDOWN` 已在主流程接线、默认无订阅——为指标/告警/门控预留，按需注册即可。（`POST_SEND` 因无触发点已移除。）
- **2 秒约束**：hook 跑在消息线程，审计 / 限流保持同步且廉价（μs 级）；告警 / 指标类 hook 异步，不阻塞回复。
- **向后兼容**：每个 Phase 必须保持既有测试绿（对标 [mode-router.md](mode-router.md) 的"行为不变"原则）。
- **复用 `ModeOutcome`**：它已有 verdict 雏形（HANDLED/NOT_MATCHED/SKIP/ERROR），`HookVerdict.shortCircuit(outcome)` 直接复用，避免两套结局语义。
- **与可靠性四件套的关系**：`RetrySender`/`RateLimiter`/`FlushGate`/`McpHealthMonitor` 中，只有 `RateLimiter` 天然是 `ON_MESSAGE_RECEIVED` 入口 hook；`RetrySender` 是装饰器（保留）、`FlushGate`/`McpHealthMonitor` 是后台自愈（与 hook 无关）。

### A7. 测试策略（实现时）

- 每个 hook 独立单测（mock `HookContext`，断言 `HookVerdict`）。
- `HookRegistry` 多 hook 排序 + `block`/`shortCircuit` 中止语义。
- 迁移类（`AuditHook`/`RateLimitHook`）：行为对标迁移前的内联实现，保证零回归。
- 覆盖率 ≥ 80%（遵循 `.claude/rules/testing.md`）。

---

## Part B — 开发期 Claude Code harness：`.claude/settings.json` 守卫（已安装）

把 CLAUDE.md 的软约束转成控制流层强制——对**密钥保护**和**技术基线**两类不可妥协项，hook 比 prompt 更可靠。已安装两个 `PreToolUse` 守卫（`.claude/settings.json` + `.claude/hooks/guard-edit.sh`），`exit 2` 阻断并把 stderr 反馈给模型、`exit 0` 放行。

### B1. 已安装的守卫

| 事件 | matcher | 实现 | 拦截 |
|------|---------|------|------|
| `PreToolUse` | `Bash` | 内联 `grep` stdin | `git push` / `mvn deploy` / `rm -rf`（提示手动执行） |
| `PreToolUse` | `Edit\|Write` | `bash .claude/hooks/guard-edit.sh` | 编辑 `pom.xml`（技术基线）/ `data/*.json`（凭证） |

**关键设计**：Edit|Write 守卫必须**按 `file_path` 字段精确匹配**（脚本里 `"file_path"...(pom\.xml|data/...)"`），而非粗粒度 grep 整段 stdin——否则任何"内容里提到 pom.xml"的编辑（如本文档、settings.json 自身）都会被误伤。故用独立脚本实现，同时避开 jq 依赖（git-bash 默认无 jq，缺失会让 hook 失效）。Bash 守卫直接 grep stdin 的 command 字段，代价是粗匹配（如 `echo "git push"` 也会被拦），对安全守卫可接受。

### B2. 实际配置

`.claude/settings.json` 装两条 PreToolUse（Bash 内联 grep；Edit|Write 调 `bash .claude/hooks/guard-edit.sh`）。`guard-edit.sh` 核心一行 `grep -qE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*(pom\.xml|data/[^"]+\.json)"'` → 命中 `exit 2`、否则 `exit 0`。已用样例 stdin 验证：`pom.xml` / `data/*.json` 路径阻断；源码、docs、settings.json（即使内容提到 pom.xml）放行。下方为早期 **jq 版方案（未采用**——git-bash 默认无 jq，且 Edit|Write 需按 file_path 精确匹配、粗 grep 会误伤，故改用脚本）：

```jsonc
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [{
          "type": "command",
          "command": "jq -r '.tool_input.command' | grep -qE 'git push|mvn deploy|rm -rf' && { echo '阻断：受保护操作' >&2; exit 2; } || exit 0"
        }]
      },
      {
        "matcher": "Edit|Write",
        "hooks": [{
          "type": "command",
          "command": "jq -r '.tool_input.file_path' | grep -qE '(^|/)(pom\\.xml|data/.*\\.json)$' && { echo '阻断：技术基线 / 凭证文件受保护' >&2; exit 2; } || exit 0"
        }]
      }
    ]
  }
}
```

> 注：`data/` 下配置模板由 Java 应用运行时生成（`ReliabilityConfig.createTemplate` / `HookConfig.createTemplate`），不经 Claude Edit/Write，故与守卫无冲突。

### B3. 未安装（可选）

- `Stop`：每回合响铃——太吵，未装。
- `SessionStart`：回显硬约束——CLAUDE.md 已覆盖，重复，未装。

---

## 与现有设计的关系

- **[mode-router.md](mode-router.md)**：hook 子系统是 ModeRouter 之上的横切层；`ModeOutcome` 被 `HookVerdict` 复用；不改变现有路由优先级。
- **[reliability.md](reliability.md)**：`RateLimiter` 迁为 `ON_MESSAGE_RECEIVED` 入口 hook（H2），`RetrySender`/`FlushGate`/`McpHealthMonitor` 维持原位。
- **[ROADMAP.md](../ROADMAP.md)**：Hooks 子系统（Phase H1–H4）已完成。
- **`.claude/rules/doc-maintenance.md`**：新增 hook 子系统 → 本文档的映射行。
