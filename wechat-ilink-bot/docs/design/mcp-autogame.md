# MCP 客户端 + Autogame 模式设计

> autogame 模式让 bot 经 **MCP（Model Context Protocol）** 驱动外部自动化服务
> [wechat-link-autogame-xcx](../../../wechat-link-autogame-xcx)：用户发 `!` 指令 → bot 调 MCP tool →
> Python 端做图像识别 + ilink 指令调度 → 操作微信小程序游戏。bot 侧只做协议客户端与指令映射，
> 不接触任何屏幕识别 / 鼠标操作。

## 架构

```
微信用户发 "!run 签到"
    │
    ▼
AutogameMode（mode/，! 前缀）
    │  callTool("run_template", {name:"签到"})
    ▼
McpClient（mcp/）── JSON-RPC 2.0 over HTTP+SSE ──►  autogame-xcx MCP server（Python，localhost:8765）
                                                         │  list_templates / run_template /
                                                         │  get_status / stop_execution / get_report
                                                         ▼
                                                   图像识别(OCR/模板匹配) + ilink 指令调度 → 小程序
```

- bot ↔ MCP：标准 [MCP streamable HTTP+SSE transport](https://modelcontextprotocol.io)。bot 开 SSE 长连接拿 POST endpoint，再 POST JSON-RPC 请求，响应经 SSE 回流。
- MCP ↔ 小程序：autogame-xcx 内部完成（图像识别 + JPype1 调 ilink SDK + 串行调度），**bot 不感知**。

## 组件（`mcp/`）

| 类 | 职责 |
|----|------|
| `McpClient` | JSON-RPC over HTTP+SSE 客户端（okhttp POST + launchdarkly EventSource SSE）。`connect`/`initialize`/`listTools`/`callTool`/`isConnected`/`reconnect`/`pendingCount`/`close` |
| `McpToolRegistry` | 缓存 `tools/list` 结果（`AtomicReference` 不可变快照）；`refresh`/`all`/`find`/`isLoaded`。单实例被 `AutogameMode` 共享 |
| `McpHealthMonitor` | daemon 单线程调度器：`isConnected()==false` → `reconnect()`；每 N 周期 `registry.refresh()`（G3 自愈 + G7 tool 刷新） |
| `McpTool` | tool 元数据 POJO（name / description / inputSchema） |
| `McpToolResult` | `tools/call` 结果（isError + text；text 是 Python 端约定的 JSON 字符串） |
| `AutogameConfig` | 配置（enabled / mcpUrl，默认 `http://localhost:8765`），缺省生成模板 |

> `McpClient` 的 `sse`/`endpointReady` 因 `reconnect()` 需可变（`sseAlive` volatile、`postEndpoint` AtomicReference），是"字段 `private final`"规则的受控例外。

## `!` 命令 → MCP tool 映射（`AutogameMode`）

| `!` 命令 | MCP tool | 同步/异步 | 说明 |
|---------|----------|----------|------|
| `!list` | `list_templates` | 同步 | 列出可用模板 |
| `!run <名称>` | `run_template`({name}) | **异步** | 先回执"开始执行"，完成后回执结果（满足 bot ≤2s SLA） |
| `!status` | `get_status` | 同步 | 查询执行状态 |
| `!stop` | `stop_execution` | 同步 | 请求停止 |
| `!report` | `get_report` | 同步 | 上次执行报告 |
| `!help`（或 `!` 空） | — | — | 渲染 registry 中的 tool 列表 |

- 未连接（`mcpClient == null` 或 `!isConnected()`）→ 回复"MCP 服务未启用，请联系管理员启动 wechat-link-autogame-xcx 的远程服务"。
- `run_template` 在 daemon **单线程池**里异步执行（`autogame-async-*`），保证 run 串行——与 Python 端串行 scheduler 对应，避免窗口冲突。
- 短 tool（list/status/stop/report）同步阻塞 handleText 调用，但 MCP 往返通常 < 100ms。
- `callTool` 抛 IOException → 回复"调用失败：<msg>"。

## MCP 握手与调用协议

```
1. connect()        GET <baseUrl>/sse  →  开 SSE 长连接（EventSource）
2. onMessage        收到 `endpoint` 事件  →  postEndpoint = <POST URL>；sseAlive=true
3. initialize()     POST {jsonrpc, method:"initialize", id} → 拿 capabilities
4. listTools()      POST {method:"tools/list"} → parseTools → McpTool[]
5. callTool(n, a)   POST {method:"tools/call", params:{name, arguments}} → parseToolResult → McpToolResult
```

- 响应经 SSE 回流，按 JSON-RPC `id` 匹配 `pending` 的 `CompletableFuture`。
- `await` 超时与 POST 失败均从 `pending` 移除（防泄漏，G6）；`pendingCount()` 暴露在途请求数（G4）。

## 可靠性（详见 [reliability.md](reliability.md)）

| 缺口 | 处置 |
|------|------|
| SSE 断开不自愈（G3） | `isConnected()=sseAlive && postEndpoint!=null && !closed`（修正"endpoint 已收但 SSE 已断"假真）；`McpHealthMonitor` 周期探测 → `reconnect()` |
| `reconnect` 替换实例风险 | 复用同一 `McpClient` 实例（关旧 SSE + 重置握手 + 重连 + initialize），避免替换 `ModeContext`/`McpToolRegistry` 里的 final 引用 |
| 在途请求无观测（G4） | `pendingCount()` |
| pending 超时泄漏（G6） | `await` 超时 / POST 失败清 `pending` |
| tool 不刷新（G7） | `McpHealthMonitor` 每 `mcpToolRefreshTicks` 周期 `registry.refresh()` |
| 关闭不关 mcpClient（G5） | `GameApplication.shutdown()` 调 `mcpHealthMonitor.shutdown()` + `mcpClient.close()` |

**关键约束**：重连/刷新全程在监控线程，**不阻塞消息线程**；重连期间 `!` 命令走"未连接"兜底，不假死。

## 配置（`data/autogame-config.json`）

| 字段 | 默认 | 说明 |
|------|------|------|
| `enabled` | `false` | 为 `true` 才构造 `McpClient` 并启动 `McpHealthMonitor`；否则 `ModeContext.mcpClient` 为 null，`!` 路由不生效 |
| `mcpUrl` | `http://localhost:8765` | autogame-xcx MCP server 地址（Python GUI 默认端口） |

`initMcpClient()`（`GameApplication`）加载配置；连接失败时 warn 并置空 `mcpClient`/`mcpToolRegistry`，`!` 指令降级为"未启用"。

## 接线

- `GameApplication.initMcpClient()` → `McpClient` + `McpToolRegistry` + `McpHealthMonitor.start()`。
- `BotInstance.create` 把 `mcpClient`/`mcpToolRegistry` 注入 `GameBot` → `ModeContext`（14 参）。
- `GameBot` 构造器：`mcpClient != null` 才 `new AutogameMode()` 并注册到 `ModeRouter`（`!` 路由 + `switchableModes[AUTOGAME]`）。

## 约束

Java 17；字段 `private final` + 构造器注入（`McpClient` 的 `sse`/`endpointReady`/`sseAlive` 因重连需可变，例外）；单文件 ≤ 400 行 / 单方法 ≤ 60 行；
统一 SLF4J，不打印 token；`mode/` 包零 SDK import（`AutogameMode` 经 `ctx.sender()` 回发）；监控/调度全程在 daemon 线程。
