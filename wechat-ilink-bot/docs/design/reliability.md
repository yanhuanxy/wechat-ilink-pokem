# 可靠性增强设计（Phase 5）

> 在 autogame + MCP 引入新故障面（SSE 长连接、远程工具调用、单线程异步执行器）后，把 4 个组件
> 落到代码里**真实的可靠性缺口**上，而非泛泛加固。配置统一在 `data/reliability-config.json`
> （`config/ReliabilityConfig`，缺省生成模板、使用默认值）。

## 缺口与组件对应

| 缺口 | 组件 | 位置 |
|------|------|------|
| G1 发送失败静默丢失、零重试 | **RetrySender** | `GameBot` 用它包 `this` 作 `ModeContext.sender` |
| G2 无限流，单用户可刷爆 MCP/LLM/命令 | **RateLimiter** | `ModeRouter.route` 入口 |
| G3 MCP SSE 断开不自愈 | **McpHealthMonitor** + `McpClient.reconnect` | `mcp/` |
| G4 在途请求无观测 | `McpClient.pendingCount()` | `mcp/McpClient` |
| G5 shutdown 不关 mcpClient | `GameApplication.shutdown` | `GameApplication` |
| G6 pending 超时项泄漏 | `McpClient.await/sendRequest` 清理 | `mcp/McpClient` |
| G7 tool 不定期刷新 | `McpHealthMonitor` 周期 `registry.refresh` | `mcp/McpHealthMonitor` |
| G8 持久化合并/兜底 | **FlushGate** + 锁下沉 | `session/` |
| G9 重启 resume 重投旧消息、旧问题被重跑 | **MessageDedupRepository**（message_id 水位线） | `GameBot.onMessages` |

## RetrySender（G1）

`ModeSender` 装饰器：`sendText/sendImage/sendFile/sendVideo` 对 IOException 指数退避重试
（`sendMaxAttempts` 次，封顶 4s），全失败记 ERROR 并**放弃不抛**（保持既有 sendSafe"尽力发送"语义）。
- 不重试 `startTyping/stopTyping`（幂等）、`sendTextWithTyping`（SDK 内含 typing 延时 sleep，重试会累积阻塞消息线程）。
- 接线：`GameBot` 构造 `new RetrySender(this, attempts, backoffMs)` 作 `ModeContext` 的 sender；所有 mode 经 `ctx.sender()` 自动获得重试，**零调用方改动**。

## RateLimiter（G2）

per-user 固定窗口（`ConcurrentHashMap<userId, Window>`）：窗口内 `tryAcquire` 计数 ≤ `rateLimitPerMin` 放行，超出拒绝；窗口（`rateLimitWindowMs`）滚动后清零。daemon 线程清理过期窗口。
- 接线：`ModeRouter.route` 在 `session.touchActivity()` 后、任何分支前 gate；超限 → `sendSafe("请求过于频繁…")` + `handled`。

## MCP 自愈（G3–G7）

- `McpClient`：`sseAlive` 追踪（onOpen/endpoint=true，onClosed/onError=false）；`isConnected()=sseAlive && postEndpoint!=null && !closed`（修正"endpoint 已收但 SSE 已断"的假真）；`reconnect()` 复用同一实例（关旧 SSE + 重置握手 + 重连 + initialize），避免替换外部 final 引用；`pendingCount()` 观测在途；`await` 超时与 POST 失败均清 pending（防泄漏）。
- `McpHealthMonitor`（daemon ScheduledExecutor，周期 `mcpHealthIntervalMs`）：`isConnected()==false` → `reconnect()`，失败 warn 下轮再试，成功后 `registry.refresh()`；每 `mcpToolRefreshTicks` 周期刷新 tool（G7）。**全程监控线程，不阻塞消息线程**。
- 生命周期：`GameApplication.start` 启动 monitor；`shutdown` 关 monitor + `mcpClient.close()`（G5）。

## FlushGate（G8）+ 锁下沉

现状"每命令同步写"已耐用；FlushGate 提供**突发写合并 + 崩溃兜底**，且不破坏一致性：
- **锁下沉**：`GameEngine.userLocks` 移到 `SessionManager.lockFor/withLock`；`GameEngine.dispatch` 用 `sessionManager.lockFor(userId)`，异步 flush 复用同一把锁读一致快照。
- `FlushGate`：`scheduleFlush(userId)` —— `flushDelayMs<=0` 同步立即刷（默认，保持"命令返回即落盘"）；`>0` per-user debounce 合并。周期 `flushIntervalMs` 兜底刷所有 dirty；`flushAllNow()` shutdown 强制全量。
- 调用方迁移：`GameEngine`/`SystemCommandMode` 的 `saveSession` → `scheduleFlush`（简单构造器 gate=null 时同步，等价旧行为；全量构造器启用周期兜底）。
- **语义变化**：`flushDelayMs>0` 时命令返回不再保证已落盘，崩溃丢失 ≤ `flushIntervalMs`（可接受：单实例、内存态始终最新）。

## 消息去重 / 重投幂等（G9）

`BotInstance` 优雅关闭时把 SDK 的 `updatesCursor` 落库（`bot_session`），重启用 `resumeContext` 从该游标恢复拉取；游标边界会**重投最后一条已处理消息**。因 `currentMode` 持久化，重投的旧文本会再次路由（如 CLAUDE 模式再跑一次子进程 → 旧问题被重跑）。
- **MessageDedupRepository**（`persistence/`）：按用户维度记录已处理的最大 `message_id`（表 `processed_message`，纯 JDBC + `ConcurrentHashMap` 内存缓存算 max）。
  - `getLastMessageId(userId)`：无记录返回 `Long.MIN_VALUE`；`markProcessed(userId, id)`：仅当 `id > 水位线`时上移并 `INSERT OR REPLACE` 落库（取 max，不回退）。
- 接线：`GameBot.onMessages` 每条消息处理前，`message_id ≤ 水位线` → 跳过（重投的旧消息）；处理后 `markProcessed`。离线期间真正的新消息（id 更大）仍放行。`message_id` 为 null 时不去重（无法判定，宁可放行）。构造器 `dedup=null` 时整体关闭（向后兼容）。

## 配置（`ReliabilityConfig`）

| 字段 | 默认 | 说明 |
|------|------|------|
| `sendMaxAttempts` | 3 | 发送重试次数 |
| `sendBackoffMs` | 500 | 退避基数（指数，封顶 4s） |
| `rateLimitPerMin` | 30 | 每用户每窗口放行数 |
| `rateLimitWindowMs` | 60000 | 限流窗口 |
| `flushDelayMs` | 0 | 会话刷盘延迟（0=同步立即刷，>0=debounce 合并） |
| `flushIntervalMs` | 30000 | 周期兜底 flush |
| `mcpHealthIntervalMs` | 30000 | MCP 健康探测周期 |
| `mcpToolRefreshTicks` | 2 | 每 N 周期刷新 tool |

## 约束

Java 17；字段 `private final` + 构造器注入（`McpClient` 的 `sse`/`endpointReady` 因 `reconnect()` 需可变，例外）；单文件 ≤ 400 行 / 单方法 ≤ 60 行；
统一 SLF4J；`mode/` 包零 SDK import；重连/限流/刷盘全程非阻塞消息线程（监控/调度/daemon 线程）。
