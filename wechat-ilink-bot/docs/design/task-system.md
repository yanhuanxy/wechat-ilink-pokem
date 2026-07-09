# Task 子系统设计（Claude Code 视频任务流）

> ⚠️ 本文描述的是**最初的 Claude Code CLI 视频任务流**，对应的 `ClaudeCodeProvider` / `WorkspaceManager` **已移除**。
> Review 现走 `DashScopeVideoProvider`（DashScope OpenAI 兼容端点直连，见下文"配置"节与 [claude-bridge.md](claude-bridge.md)）。
> 下方"Claude Code CLI 调用方式"等节为历史流程记录。

## 背景

bot 平台的首个非游戏场景。用户发送视频后紧跟一条文字说明，触发本地 Claude Code CLI（订阅模式，无 API key）处理视频内容，结果回到微信。

## 触发条件

- 用户先发送一条视频消息
- bot 回复"已收到视频，请在 60 秒内发送处理说明"
- 60 秒窗口内，用户发送的下一条**非 # 前缀**文字消息视为任务说明
- 视频与文字组合后，提交 Claude Code 处理

60 秒未匹配的视频票据由后台清理线程自动移除。

## 包结构

```
com.github.wechat.ilink.bot.task/
├── TaskProvider.java          # 抽象接口
├── TaskRequest.java           # { taskId, userId, sessionId, videoBytes, videoFileName, userPrompt }
├── TaskConfig.java            # { enabled, claudePath, workspaceRoot, timeoutMs, permissionMode, ... }
├── DashScopeVideoProvider.java # DashScope OpenAI 兼容端点直连（Review 实现）
├── DashScopeUploader.java     # OSS 视频上传
├── VideoTaskBuffer.java       # 60 秒窗口的视频缓冲（per-user）
├── SkillInstaller.java        # 内置 skill 解压到 claudeHome
└── TaskMessageHandler.java    # 消息路由（被 GameBot 委托调用）
```

## 依赖关系

- `TaskProvider` 复用 `llm.StreamCallback`（onToken / onComplete / onError）
- `TaskMessageHandler` 由 GameBot 持有，处理视频下载 + 任务派发
- `DashScopeVideoProvider` 经 `DashScopeUploader` 上传视频到 OSS，rubric 从 `claudeHome` 读取
- `VideoTaskBuffer` 是单例，由 GameApplication 注入到所有 TaskMessageHandler

## 核心接口

```java
public interface TaskProvider {
    void execute(TaskRequest request, StreamCallback callback);
}
```

不复用 `LlmProvider`，原因：
- 任务语义不同于聊天（长时执行、工作目录、文件 IO）
- 任务结果长度远超聊天回复，需要拆分发送
- 任务可能失败（超时、退出码非零），错误处理路径不同

## Claude Code CLI 调用方式

订阅模式 OAuth token 存在 `~/.claude/` 下，首次使用需本机跑一次 `claude` 完成登录。

`ClaudeCodeProvider.startProcess` 启动命令：

```
claude -p "<userPrompt>\n\n[附件] 视频文件..."
  --add-dir <workspace>
  --output-format stream-json
  --verbose
  --session-id <userId>
  --permission-mode <acceptEdits|bypassPermissions|default>
```

- `cwd` = per-task 工作目录（`data/tasks/{userId}/{taskId}/`）
- 视频字节先写入 `<workspace>/input.mp4`
- stdout 输出 stream-json，逐行解析：
  - `content_block_delta.text` → onToken
  - `result` → 完整结果
  - `message.content` 数组 → 拼接所有 text
- 进程退出码非零 → onError
- 超时（默认 5 分钟）→ destroyForcibly + onError

## 消息路由（ModeRouter → ReviewMode）

视频任务由 `ReviewMode` 承担（`ModeRouter` 抢占式路由到它，与当前模式无关）：

```
ModeRouter.route(msg):
  if findVideoItem(msg) != null → ReviewMode.handleVideo（委托 TaskMessageHandler.tryHandleVideo）
  ...（#、/、! 等其它前缀路由见 mode-router.md）...
  if 60s 内有视频票据 → ReviewMode.handlePendingPrompt（委托 TaskMessageHandler.tryHandleTaskText）
```

`TaskMessageHandler`：
- `tryHandleVideo`：检查视频 → 下载 → buffer.put → 提示用户
- `tryHandleTaskText`：consume buffer 命中 → 立即回复"任务已提交" → 异步提交到 TaskExecutor

## 异步执行与微信 2 秒响应

收到任务后立即回复"🤖 任务已提交，Claude Code 正在处理"，满足微信 2 秒响应约束。

任务跑完（通常 30 秒~几分钟）后：
- `onComplete` → 用 `splitMessage` 按 2000 字符拆分结果 → 多条 sendText 推送
- `onError` → 推送失败原因

## 安全边界

- 默认 `permissionMode=acceptEdits`：自动接受文件编辑，Bash 命令仍需确认（headless 模式下未确认 = 拒绝）
- 用户可切 `bypassPermissions`（全开，风险自担）
- 静态工具策略 `allowedTools` / `disallowedTools`（Phase 3）：落到 claude 的 `--allowedTools` / `--disallowedTools`
  旗标，对 Bridge（`ClaudeCodeAdapter`）生效（Review 走 DashScope 直连、不经 claude 工具策略；共享 `TaskConfig`）。
  逗号拼接，模式如 `Read`、`Grep`、`Bash(git log:*)`、`WebFetch(domain:example.com)`。
  **仅在 `permissionMode != bypassPermissions` 时实际起作用**（bypass 会跳过几乎所有检查）。
  两列表默认空 → 不追加旗标，行为与未配置一致（opt-in）。逐次交互审批为未来 Phase 3.2，
  依赖未文档化的 stream-json 控制协议，暂未实现。
- 工作目录隔离：每任务一个子目录，Claude Code 无法 `--add-dir` 到父级
- 视频大小上限 50MB（`TaskConfig.maxVideoBytes`，MVP 保护）

## 配置 `data/task-config.json`

```json
{
  "enabled" : true,
  "claudePath" : "claude",
  "workspaceRoot" : "data/tasks",
  "timeoutMs" : 300000,
  "permissionMode" : "acceptEdits",
  "allowedTools" : [ "Read", "Grep", "Glob", "Bash(git log:*)" ],
  "disallowedTools" : [ "Bash(rm:*)" ],
  "streamingEnabled" : true,
  "maxVideoBytes" : 52428800,
  "bufferTtlMs" : 60000
}
```

`TaskConfig.load` 文件不存在时生成模板（enabled=true，但默认实例仍 disabled，需要用户确认修改后启用）。

> 视频点评的**模型与 DashScope endpoint/token 不在本文件**，统一在 `data/models-config.json`：`review.model`（默认 `qwen-omni`）+ `review.provider`（默认 `dashscope`）引用 `providers.dashscope` 的 `baseUrl`/`apiKey`/`uploadsUrl`。`GameApplication` 加载后注入 `TaskConfig`（`dashscopeBaseUrl`/`dashscopeApiKey`/`dashscopeUploadsUrl`/`videoReviewModel`），`DashScopeVideoProvider` 与 `DashScopeUploader` 据此发请求。该 provider 与 Claude Bridge 共用，只定义一次。

## 关键文件

- `src/main/java/com/github/wechat/ilink/bot/task/TaskProvider.java`
- `src/main/java/com/github/wechat/ilink/bot/task/TaskRequest.java`
- `src/main/java/com/github/wechat/ilink/bot/task/TaskConfig.java`
- `src/main/java/com/github/wechat/ilink/bot/task/DashScopeVideoProvider.java`
- `src/main/java/com/github/wechat/ilink/bot/task/DashScopeUploader.java`
- `src/main/java/com/github/wechat/ilink/bot/task/VideoTaskBuffer.java`
- `src/main/java/com/github/wechat/ilink/bot/task/TaskMessageHandler.java`
- `src/main/java/com/github/wechat/ilink/bot/GameBot.java`（路由扩展）
- `src/main/java/com/github/wechat/ilink/bot/GameApplication.java`（组装）
- `src/main/java/com/github/wechat/ilink/bot/BotInstance.java`（注入 taskHandler）

## 测试

- `VideoTaskBufferTest`：put/consume/过期/并发/大小限制（10 测试）
- `TaskConfigTest`：模板/字段加载/损坏文件（4 测试）
- `TaskMessageHandlerTest`：路由/下载/任务派发/消息拆分（8 测试）
- `DashScopeVideoLiveTest`：`@EnabledIfEnvironmentVariable` 守卫的端到端测试（CI 不跑）
