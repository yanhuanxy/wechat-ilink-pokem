# wechat-ilink-bot

> **English** · A multi-mode WeChat bot built on [`wechat-ilink-sdk`](https://github.com/lith0924/wechat-ilink-sdk-java). One WeChat message is routed by prefix/type to one of five modes: LLM chat, a text farm game, a Claude Code bridge, video review, or mini-program automation.
> **Prereqs:** JDK 8+, Maven 3.6.3+. **Run:** `mvn clean package && java -jar target/wechat-ilink-bot-1.0.0-SNAPSHOT.jar`, then scan the QR code with WeChat. **Zero-config taste:** the Farm mode (`#帮助`) and Chat echo work with **no API key**. **License:** MIT.
> 下方为中文主体文档。

---

## 它能做什么

用户在微信里发消息，`ModeRouter` 按前缀/消息类型路由到五大模式之一：

| 触发 | 模式 | 做什么 |
|------|------|--------|
| 普通文本（默认） | **Chat** | LLM 对话（流式/同步，OpenAI 兼容）；未配 LLM 时原样回显 |
| `#` 前缀 | **Farm** | 帮帮农场文字游戏（22 个命令：种植/收获/卖菜/签到/排行…） |
| `/mode claude` 后的普通文本 | **Claude Bridge** | 走本机 `claude` CLI 子进程，跨消息会话延续（`--resume`）+ 双向文件回传 |
| 上传视频（抢占式） | **Review** | 视频点评任务（Claude Code / DashScope 视频模型） |
| `!` 前缀 | **Autogame** | 经 MCP 调用外部 autogame 服务，图像识别 + 自动操作小程序游戏 |
| `/` 前缀 | 系统命令 | `/mode` `/new` `/sessions` `/resume` `/help` `/status` |

> 农场是五模式之一，不再是项目标题——本项目是机器人平台，不是单纯的游戏框架。

## 各模式前置条件速查（先看这张表）

| 模式 | 触发 | 需要什么 | 默认 |
|------|------|----------|------|
| **Farm** | `#` | 无（纯 Java，开箱即玩） | ✅ 开 |
| **Chat** | 普通文本 | 无 key 时原样回显；填 LLM key 后真对话 | ✅ 开 |
| **Claude Bridge** | `/mode claude` | 本机 `claude` CLI（Node.js）+ bridge 端点/token（详见下文） | ⬜ 关 |
| **Review**（视频） | 上传视频 | `claude` CLI + Python 3 + ffmpeg + DashScope 视频模型 | ⬜ 关 |
| **Autogame** | `!` | 外部 MCP server（Python） | ⬜ 关 |

**零门槛体验路径**：`clone → mvn package → java -jar → 扫码 → 发 #帮助 玩农场 / 发普通文本测 Chat`，全程不碰 `claude` CLI、不需要任何 API key。

## 在 iLink 生态中的位置

```
wechat-ilink-sdk-java (io.github.lith0924:wechat-ilink-sdk)   ← 地基（Maven Central 依赖，非本仓库子目录）
        ▲  封装微信 PC 客户端：扫码登录、长轮询、收发文本/图/文件/视频、CDN+AES
        │
wechat-ilink-bot （本项目）                                    ← 集成中枢 / 参考应用
        │  多模式路由 + 会话 + 持久化 + LLM + 可靠性
        │
        ├── ! / MCP ──►  wechat-link-autogame-xcx（外部，可选）← Python：图像识别 + 指令调度
        └── (规划) ────►  wechat-ilink-imoney（外部，可选）    ← 对话记账
```

- **SDK 是 Maven 依赖**（`io.github.lith0924:wechat-ilink-sdk:2.3.3`，已发布到 [Maven Central](https://central.sonatype.com/artifact/io.github.lith0924/wechat-ilink-sdk/2.3.3)），`mvn` 会自动拉取，**无需克隆 SDK 源码**。
- autogame / imoney 是**可选外部项目**，不在本仓库内；不装也不影响其他模式。

## 架构

```
Application        GameApplication（组合根）· GameBot（SDK 桥 + ModeSender/MediaDownloader）· BotInstance（多实例 + 登录重试）
    ↓
Framework          mode/（ModeRouter + Chat/Farm/ClaudeBridge/Review/System/Autogame + RetrySender/RateLimiter + claude/）
                   engine/ · command/ · session/（+FlushGate）· persistence/ · llm/ · task/ · mcp/ · config/（ReliabilityConfig）
    ↓
Implementation     farm/（帮帮农场：22 命令处理器 + 领域模型）
```

依赖严格向下：Application → Framework → Implementation。`mode/` 包零 SDK import（统一经 `ModeSender` 回调到 `GameBot`）。详见 [docs/architecture/](docs/architecture/overview.md)。

## 技术基线

| 技术 | 版本 |
|------|------|
| JDK | 1.8（无 Java 9+ 语法） |
| Maven | 3.6.3+（由 enforcer 强制） |
| SDK | `io.github.lith0924:wechat-ilink-sdk:2.3.3`（Maven Central） |
| 数据库 | SQLite 3.45.x（WAL 模式，零配置，首次运行自动建库） |
| JSON | Jackson 2.x · HTTP/SSE | okhttp + okhttp-eventsource（MCP 客户端） · 二维码 | ZXing · 日志 | SLF4J + Logback · 测试 | JUnit Jupiter 5.10 + Mockito 5.x |

**禁止**：Spring、DI 框架、Lombok。完整契约见 [CLAUDE.md](CLAUDE.md)。

---

## 快速上手（小白向）

### 第 1 步：装环境

- **JDK 8**（源码目标 1.8；构建用 8/11/17 均可，但代码不可用 Java 9+ 语法）。下载：[Adoptium / Temurin](https://adoptium.net/)。
- **Maven 3.6.3+**。下载：[maven.apache.org](https://maven.apache.org/download.cgi)。

校验（两个命令都有输出即可）：
```bash
java -version
mvn -v
```

### 第 2 步：克隆并构建

```bash
git clone <本仓库地址> wechat-ilink-bot
cd wechat-ilink-bot
mvn clean package          # 首次会从 Maven Central 拉取 SDK 与依赖，需联网
```

构建成功后得到可运行 jar：`target/wechat-ilink-bot-1.0.0-SNAPSHOT.jar`。

> 想一键搞定？用 `run.bat`（Windows）或 `run.sh`（*nix / Git Bash），等价于「构建 + 运行」。

### 第 3 步：首次运行（会自动生成配置模板）

```bash
java -jar target/wechat-ilink-bot-1.0.0-SNAPSHOT.jar
# 或： mvn exec:java
```

首次启动会在仓库根的 `data/` 下自动生成配置模板（`bots.json` / `models-config.json` / `task-config.json` / `reliability-config.json` / `autogame-config.json`）。生成后**程序会等扫码登录**——此时可以先 `Ctrl+C` 退出，去编辑配置。

> `data/*.example` 是仓库跟踪的模板源（占位值），`data/*.json` 是你的本地真实配置（已 gitignore，不入库）。

### 第 4 步：按需填配置

| 你想用 | 编辑 | 填什么 |
|--------|------|--------|
| **只玩农场** | 无需改 | 直接下一步 |
| **Chat 真对话** | `data/models-config.json` | 在某个 provider 填 `apiKey`，并让 `chat.provider` 指向它 |
| **Claude Bridge** | `data/models-config.json` + `data/task-config.json` | 见下文「Claude Bridge / Review 模式」 |
| **视频点评 Review** | 同上 + 装 Python/ffmpeg | 见下文 |

> 没填任何 key 也能跑：Chat 模式会原样回显你发的消息，Farm 完全可用。

### 第 5 步：运行并扫码

```bash
java -jar target/wechat-ilink-bot-1.0.0-SNAPSHOT.jar
```

控制台会打印登录二维码（终端 ASCII + 可选图片），用**微信**扫码登录。登录成功后，用另一个微信给这个号发消息即可触发各模式：
- 发 `#帮助` → 农场命令列表
- 发 `你好` → Chat（有 key 则 AI 回复，无 key 则回显）
- 发 `/help` → 系统命令

各子系统均可独立禁用（`enabled:false`），互不影响。

---

## Claude Bridge / Review 模式（opt-in，需 `claude` CLI）

> 这两个模式都 spawn 本机 `claude` CLI（即 Anthropic **Claude Code**）子进程。**不开它们就完全不需要 `claude` CLI**——Farm 和 Chat 不依赖。

### 1. 装 `claude` CLI

两种方式任选：
- **npm**：先装 [Node.js ≥ 18](https://nodejs.org/)，再 `npm install -g @anthropic-ai/claude-code`；
- **原生安装脚本**：见 [Claude Code 官方文档](https://docs.claude.com/en/docs/claude-code)。

校验（能输出 stream-json 即可用）：
```bash
claude --version
claude -p "hi" --output-format stream-json --verbose
```
需支持的旗标：`-p/--print`、`--output-format stream-json`、`--verbose`、`--model`、`--permission-mode`、`--resume`、`--allowedTools/--disallowedTools`（均为 Claude Code 稳定特性，建议较新版本）。

### 2. 认证（两条路二选一）

**路径 A —— 第三方 Anthropic 兼容端点（默认，国内友好，无需 Anthropic 账号）**
走 DashScope 的 `/apps/anthropic` 网关，用 DashScope API key 作 Bearer。配置 `data/models-config.json`：
```json
{
  "providers": {
    "dashscope": { "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1", "apiKey": "你的百炼key", "uploadsUrl": "..." },
    "anthropic": { "baseUrl": "https://dashscope.aliyuncs.com/apps/anthropic", "apiKey": "你的百炼key", "uploadsUrl": "" }
  },
  "bridge": { "provider": "anthropic", "model": "qwen-max", "smallModel": "qwen-turbo" }
}
```
机器人会自动派生 `ANTHROPIC_BASE_URL` / `ANTHROPIC_AUTH_TOKEN` / 一整套 `ANTHROPIC_*_MODEL` 下发给子进程，并隔离 `CLAUDE_CONFIG_DIR=data/claude-home`，避免读到宿主 `~/.claude/settings.json` 冲突。

**路径 B —— 原生 Anthropic**
有 Anthropic API key 或 Claude 订阅：先 `claude login`（或设 `ANTHROPIC_API_KEY`），`bridge.provider` 指向真实 Anthropic endpoint。

### 3. 打开开关

`data/task-config.json` 把这两项置 `true`：
```json
{ "enabled": true, "claudeBridgeEnabled": true, "claudeAdminUsers": ["你的微信userId"] }
```
- `claudeAdminUsers` 是可 `/sudo` 提权的白名单；Claude Bridge 默认**受限只读**（`--permission-mode default` + 只读工具白名单），管理员可临时提权。详见 [docs/design/claude-bridge.md](docs/design/claude-bridge.md)。
- **Review（视频点评）** 还需本机 **Python 3 + ffmpeg**（内置 skill 用其抽帧）。
- 内置 skill（如 `piano-practice-review`）会在首次启动自动复制到 `data/claude-home/`，**无需手动安装**。

---

## 可靠性

`RetrySender`（发送指数退避重试）、`RateLimiter`（per-user 限流）、`McpHealthMonitor`（MCP SSE 断线自愈）、`FlushGate`（持久化合并 + 崩溃兜底，锁下沉保证一致快照）。旋钮见 `data/reliability-config.json`（首次生成，默认保持旧行为）。详见 [docs/design/reliability.md](docs/design/reliability.md)。

## 现状

- 5 模式 + 系统命令可用；Claude Bridge / Review / Autogame 为 opt-in（默认关闭）。
- 单元测试齐全；带 live 集成测试（DashScope / Anthropic / 真实 MCP）用环境变量守卫，CI 默认跳过。
- 文档全景见 [docs/](docs/)，后续路线见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 文档导航（精选入口）

- 架构总览：[docs/architecture/overview.md](docs/architecture/overview.md) · 边界：[boundaries.md](docs/architecture/boundaries.md) · 数据流：[data-flow.md](docs/architecture/data-flow.md)
- 多模式路由：[docs/design/mode-router.md](docs/design/mode-router.md) · Claude Bridge：[docs/design/claude-bridge.md](docs/design/claude-bridge.md) · 可靠性：[docs/design/reliability.md](docs/design/reliability.md)
- 命令规范：[docs/reference/command-spec.md](docs/reference/command-spec.md) · 错误码：[docs/reference/game-error-codes.md](docs/reference/game-error-codes.md)
- 编码约定：[docs/conventions/README.md](docs/conventions/README.md)

## 贡献 & 安全

- 贡献：见 [CONTRIBUTING.md](CONTRIBUTING.md)。AI 辅助开发建议额外读 [CLAUDE.md](CLAUDE.md) 与 [AGENTS.md](AGENTS.md)（可选，普通使用者可忽略）。
- 安全：见 [SECURITY.md](SECURITY.md)。**切勿**在 issue/PR/截图里粘贴真实 API key 或 token；`data/*.json` 已 gitignore。

## License

[MIT](LICENSE)。本项目基于 MIT 协议的 [`wechat-ilink-sdk`](https://github.com/lith0924/wechat-ilink-sdk-java) 构建。
