# Phase 3.2 交互式工具审批 — 可行性 Spike

> 状态：**NO-GO（已决策不做，2026-07-09）**。逐次交互审批不实现；权限收敛为 JVM 判定的二元制（受限只读 / admin 提权 bypass）。
> 下方"证据/最小设计"保留为**技术勘察记录**，佐证"为何不做"，非待办。
> 关联：[claude-bridge.md](claude-bridge.md)、[../ROADMAP.md](../ROADMAP.md) P4、[../plans/current-sprint.md](../plans/current-sprint.md) Phase 3 评估结论。

## 决策更新（2026-07-09）：NO-GO

原 spike 结论 `GO（有条件）` 已被推翻，**不实现逐次工具审批**。三条理由：

1. **实测契约不同且更脆**：本机 claude `2.1.204` 的 `--permission-prompt-tool <tool>` 参数是 **MCP 工具名**（需 JVM 反向实现一个 MCP server 供 claude 回调），并非下文"最小设计"设想的「命令式脚本 / 本地 HTTP 端点」。落地被四个未验证点卡住：① 精确 JSON schema（input `tool_name`/`input`、output `behavior`/`updatedInput`/`message`，需活 endpoint 实测）；② 第三方 Anthropic 兼容端点是否支持该编排；③ `disallowedTools` 硬拒与"可被 prompt 放行"如何共存；④ 桥形态（内嵌 MCP server vs 外部脚本 + side-channel）。其中 ①② 必须有稳定 endpoint 才能收口，spike 当时即卡在智谱 529。
2. **对已能提权者不增安全**：能拉起 claude 子进程者本就具备操作寄主系统的能力（或就是 admin，能为子进程闯的祸兜底）。逐次审批是多余的安全戏码，只徒增失败面/攻击面。
3. **远程开发损效率**：逐动作 y/n 来回在微信侧成本高。真正的效率甜点"逐会话确认一次"由现有 `/sudo` 档已实现（admin `/sudo on` 开一次、本进程生命周期有效、`/new` 不重置、仅重启回收）。

**最终模型**：完全限制（受限只读，默认，所有用户）vs 超级权限（admin `/sudo` bypass）。`plan`/`approve` 保留为**可选**只读评审工具，非必经路径。opt-in 旋钮 `claudeBridgeAdminDefaultPrivileged`（默认 false）让 admin 进入 CLAUDE 模式即默认提权，进一步压远程效率。详见 [claude-bridge.md](claude-bridge.md) 权限章。

---

## 以下为原可行性勘察（保留存档，已不作为待办）

## 要回答的问题

> headless `-p` 模式下，`--permission-prompt-tool <tool>` 是否同步阻塞等待外部决策？
> 若是 → 能否用一个"外部审批桥"（阻塞等微信 y/n）实现逐次工具审批，从而绕开持久子进程改造？

## 证据

1. **flag 存在**：本机 claude CLI `2.1.204` 实测 `claude --permission-prompt-tool` 报 `option '--permission-prompt-tool <tool>' argument missing` → 该选项有效，只是未在主 `--help` 文档化（高级选项）。
2. **阻塞契约**：据 Claude Code 官方文档与 Agent SDK，`--permission-prompt-tool` 把权限请求路由给指定工具/命令，**Claude Code 调用后等待其返回 allow/deny 决策，再决定是否执行该工具**；非交互模式下"被标记工具需 prompt-tool 返回 allow 才放行"。即调用是同步阻塞的。
3. **架构契合**：现有 Bridge 是「每条消息 fork 一个 `claude -p` 子进程 + 异步 executor + `process.waitFor(timeout)`」。审批桥方案不要求跨消息的常驻子进程——见下。

> 实测探针未跑通：当前 bridge 模型走智谱 GLM 网关，处于 `529 overloaded` 瞬态，模型推理被阻断、到不了"决定调用工具"阶段，故 `--permission-prompt-tool` 无法被触发观察。flag 存在性 + 文档化阻塞契约已足以支撑结论；精确 stdin/stdout JSON schema 留作实现首步确认（见"待确认"）。

## 关键洞察：无需持久子进程改造

ROADMAP P4 原估的阻断点是"持久子进程 + ApprovalBuffer + y/n 拦截"。但 `--permission-prompt-tool` 是**同步阻塞**的，天然契合现有 fork-per-message 架构：

```
用户消息 → ClaudeBridgeMode.handleText → executor.submit(runClaude)
  → adapter.run() fork claude -p ... --permission-prompt-tool <审批桥脚本>
  → claude 跑到需审批的工具 → 调用 <审批桥脚本> 并【阻塞等待】
       └─ 审批桥脚本把请求写入 per-session ApprovalBuffer，自身阻塞（等 y/n）
  → bot 侧 waitFor() 继续等（子进程还活着，只是卡在审批桥上）

（另一条微信消息）用户回复 y/n → ModeRouter.route → ClaudeBridgeMode.handleText
  → 检测到该 session 有 pending 审批 → 解除 ApprovalBuffer 的阻塞
       └─ 审批桥脚本返回 allow/deny → claude 继续/中止 → onComplete 回发结果
```

子进程在其**单次 run() 内**阻塞、bot 侧 waitFor 照常等待；y/n 作为**新微信消息**经现有 ModeRouter 入口解除 in-memory 桥。**没有跨消息常驻进程、没有 stdin 双向通道**——这是比原估小一个量级的改动。

## 最小设计草图（实现时细化）

| 组件 | 职责 | 落点 |
|------|------|------|
| 审批桥脚本 | claude 经 `--permission-prompt-tool` 调用；读 stdin 请求、写 per-session 待决项、阻塞等结果、stdout 返回 allow/deny JSON | 独立小脚本（仓库内，随 SkillInstaller 一同落盘）或一个本地 HTTP 端点 |
| `ApprovalBuffer` | per-session（或 per-subprocess）待决审批：存请求、提供阻塞 `awaitDecision()` 与 `resolve(y/n)` | `mode/claude/`，仿 `BridgeFileBuffer`（TTL + 超时兜底） |
| `ClaudeCodeAdapter.buildArgs` | 仅在受限档且启用逐次审批时下发 `--permission-prompt-tool <桥>` | 现有方法增量 |
| `ClaudeBridgeMode.handleText` | 入口先判 `approvalBuffer.hasPending(userId)`：是 → 解除待决、**不**走 runClaude；否 → 走原流程 | 现有方法增量 |
| 超时 | 审批桥阻塞超过 N 秒自动 deny（防用户不回复卡死子进程） | ApprovalBuffer TTL |

配置开关：`TaskConfig` 增 `claudeBridgeInteractiveApproval`（默认 false，opt-in，不破坏现状——遵循 ROADMAP 演进原则）。

## 待确认（原实现首步）——已作废，不再执行

> 下列问题为 NO-GO 前的未决项，现已随决策作废，**不作为待办**；保留以说明"为何难以落地"。

1. **精确 JSON schema**：`--permission-prompt-tool` 命令式（非 MCP）形态下，stdin 请求字段（`tool_name`/`input`/…）与 stdout 决策字段（`behavior: allow|deny`、`updatedInput`、`message`）的精确契约——待智谱恢复或换可用 endpoint 后，用 stub 脚本实证一次。
2. **桥的形态**：命令式脚本（claude 每次 fork 它）vs 本地 HTTP 端点（claude 经 curl 回调）。命令式更简单、无端口；HTTP 更易与 JVM 内 ApprovalBuffer 通信。倾向"命令式脚本 + 落盘/文件锁或本地 socket 与 JVM 通信"，实现时定。
3. **多实例/并发**：多 bot 实例时审批桥与 ApprovalBuffer 的 session 路由（按 userId+sessionId）。

## 结论（原 spike 结论——已被 2026-07-09 决策推翻，见文首「决策更新」）

> ⚠️ 以下为**作废的原结论**，仅存档。最终结论为 **NO-GO**（不实现逐次审批），理由见文首。

- ~~**GO**：Phase 3.2 可行，且改动面显著小于 ROADMAP 原估（无需持久子进程改造）。~~
- ~~**前置**：实现前需一个稳定的模型 endpoint（智谱 529 恢复，或换 endpoint）以实证 JSON schema 与端到端审批闭环。~~
- ~~**建议**：落地前先把 ApprovalBuffer + y/n 拦截 + opt-in 开关以最小切片实现，配 JUnit 5 测试。~~
