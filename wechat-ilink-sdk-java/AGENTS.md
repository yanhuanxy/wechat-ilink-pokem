# AGENTS.md

> **本文件是本项目的唯一契约真相源（single source of truth）。** Claude Code 经 [CLAUDE.md](CLAUDE.md) 的 `@AGENTS.md` 导入本文；其它 AI 工具直接读本文。使用者文档（用法/示例/FAQ/协议概念科普）见 [README.md](README.md)，本文不重述。

## 项目概述

**wechat-ilink-sdk-java**（`io.github.lith0924:wechat-ilink-sdk:3.0.0`）—— 封装微信 iLink Bot 协议的 Java SDK：扫码登录、getupdates 长轮询收消息、发送文本/图/文件/语音/视频、媒体 CDN 加密上传下载、重启恢复（ResumeContext）。

消费方：[wechat-ilink-bot](../wechat-ilink-bot)（同工作区，经 Maven 依赖而非源码引用）、iMoney（[wechat-ilink-imoney](../wechat-ilink-imoney)）。**这是开源主战场**——对外 API 破坏性变更须极其谨慎。

## 技术基线（不允许擅自升级）

| 技术 | 版本 | 约束 |
|------|------|------|
| JDK | 17 | `maven.compiler.release=17` |
| HTTP | okhttp 4.12.0 | 唯一 HTTP 客户端 |
| JSON | jackson-databind 2.17.2 | DTO 序列化（snake_case 字段） |
| 日志 | slf4j-api 2.0.13 + logback (runtime) | 统一 SLF4J |
| 测试 | JUnit Jupiter 5.10（覆盖极薄，仅首批特征化测试） | 见「已知债」 |

**禁止**：Spring、DI 框架、Lombok（全部手写 getter/setter/Builder）。`<build>` 无自定义插件，走 Maven 默认。

## 包结构（`com.github.wechat.ilink.sdk`）

```
sdk/
├── ILinkClient            # 核心门面（AutoCloseable），聚合全部 service；pollLock 串行化长轮询
├── ILinkClientBuilder     # 唯一构造入口：ILinkClient.builder()
├── core/
│   ├── config/            # ILinkConfig（不可变+Builder，超时/重试/心跳/线程池旋钮）+ ConfigLoader（ilink-sdk.properties / sysprop / env）
│   ├── context/           # 会话上下文与游标：ConversationContext / ContextPoolManager / GetUpdatesCursorStore / ResumeContext
│   ├── crypto/            # AesEcbUtil（AES/ECB/PKCS5Padding，媒体加解密）
│   ├── exception/         # ILinkException 层级（ConnectFailed/MediaUpload/NotLogin/Protocol/RequestTimeout/SessionExpired）
│   ├── executor/          # ExecutorManager（io 线程池 + scheduler）
│   ├── http/              # HttpClientFacade（OkHttp+重试）/ BusinessApiClient（协议错误码判定）/ RequestHeaderFactory（协议头）
│   ├── lifecycle/         # HeartbeatService / HealthChecker
│   ├── listener/          # ListenerRegistry + OnLogin/OnMessage/OnDisconnect/OnHeartbeat
│   ├── login/             # LoginContext / LoginStatus / QRCodeResponse 等登录模型
│   ├── model/             # 协议 DTO（WeixinMessage / MessageItem / CDNMedia / GetUpdates* / Send*…）
│   ├── retry/             # RetryPolicy / ExponentialBackoffStrategy（指数退避+jitter）
│   ├── serializer/        # Serializer 接口 + JsonSerializer
│   ├── state/             # ClientStateManager + ConnectionStatus
│   └── utils/             # HashUtils(md5) / HexUtils / RandomUtils（SecureRandom 生成 UIN/clientId/aesKey）
└── service/               # 业务编排：LoginService / MessageService / MediaService / TypingService / UpdateService
```

**对外 API 面**（破坏性变更 = 破坏 bot 与 iMoney）：`ILinkClient` 全部 public 方法、`ILinkClientBuilder`、`ILinkConfig.Builder`、`core.model` / `core.login` DTO、`ResumeContext`、4 个 Listener 接口。

## 协议敏感区（未经明确要求不得改动）

以下承载微信 iLink 协议常量与线上行为，改错即全线不可用；任何改动须由用户明确提出并说明协议依据：

1. **加解密**：`core/crypto/AesEcbUtil`（AES/ECB 是协议要求，勿"顺手升级"为 GCM）+ `MediaService` 加密上传流程（16 字节随机 key → AES-ECB → getuploadurl → CDN `x-encrypted-param` → CDNMedia 组装，aes_key hex 再 Base64、encrypt_type=1）。
2. **协议常量**：`MessageService` 消息 type 码（2=图 3=语音 4=文件 5=视频）与语音 encode_type=6(SILK)；`MediaService` 内部 upload 类型码（1=图 2=视频 3=文件 4=语音）——**两套编号不同，是协议如此，不是 bug**；CDN 域名、`https://ilinkai.weixin.qq.com` 登录端点、`/ilink/bot/*` 路径。
3. **协议头**：`RequestHeaderFactory`（`AuthorizationType: ilink_bot_token`、`X-WECHAT-UIN`、`SKRouteTag`、`iLink-App-ClientVersion:1`）。
4. **错误码判定**：`BusinessApiClient`（`ret==-14`→SessionExpired 等）。
5. **并发不变量**：`ILinkClient` 的 `pollLock` 串行化长轮询（修复 issue #5 游标竞争）——不得移除或绕过该锁。
6. **登录状态机**：`LoginService` 轮询（waiting/scanned/expired/confirmed）与 `bot_token/ilink_bot_id/baseurl` 字段。

## 硬性规则

1. **无 Lombok / 无 Spring**：手写构造器/Builder；字段 `private final` 优先，并发字段用 `volatile`/`AtomicReference`。
2. **DTO 字段 snake_case**（`from_user_id`、`context_token`），与协议 JSON 对齐，勿"修正"为 camelCase。
3. **日志用 SLF4J**，禁止 `System.out/printStackTrace`；**botToken 等凭证不得写入日志**。
4. **异常走 `ILinkException` 层级**；IO 方法声明 `throws IOException`。
5. **对外 API 兼容**：删除/改签名 public 方法前先确认 bot 与 iMoney 的调用点；能加不改，能废弃（@Deprecated）不删。
6. **新代码须带 JUnit 5 测试**（依赖已就位；存量零测试是债，不是许可）。
7. **消费方自持接收循环；心跳只做 liveness**：`getUpdates()` 是长轮询，收消息由消费方在自己的循环里连续调用；心跳（`checkLiveness`）只按 `livenessThresholdMs` 判存活、**不得改回代收消息**（否则与消费方循环冗余双轮询，退回 3.x 前的卡点）。决策见 [docs/adr/0001](docs/adr/0001-no-reactive-incremental-dispatch-decoupling.md)。
8. 提交消息前缀：`feat:` / `fix:` / `refactor:` / `test:` / `docs:`。

## 已知债（权威清单；不得扩大、不得效仿）

| 位置 | 债务 | 处置 |
|------|------|------|
| `src/test/` | **测试覆盖极薄**：仅 5 个特征化测试（登录轮询间隔/取消、心跳监听器隔离、liveness 看门狗），其余存量无测试 | 新改动补测试；存量按触碰顺序补特征化测试 |
| `ExecutorManager` dispatch 队列 | 无界队列，持续过载可堆积 | 明确搁置（[ADR-0001](docs/adr/0001-no-reactive-incremental-dispatch-decoupling.md) 影响段）；若过载成真用有界队列+CallerRunsPolicy，勿引入 Reactor |
| 全库缩进 | 2 空格与 4 空格混用（ILinkConfig/LoginService/MediaService 为 2 空格） | 改哪个文件跟哪个文件的现状，不做全库格式化 |
| `ILinkConfig` 的 `reconnect*`/`autoReconnect` 字段 | 产品不支持自动重连，字段无效 | ✅ 已 `@Deprecated` 并移出 `ConfigLoader`（[ADR-0001](docs/adr/0001-no-reactive-incremental-dispatch-decoupling.md) P3）；勿据此实现"自动重连" |

## 场景锚点（你要做什么 → 先看这个）

| 你要做什么 | 去哪里看 |
|-----------|---------|
| 查历史重大决策（为什么不上 Reactive / 接收循环演进） | [docs/adr/README.md](docs/adr/README.md) |
| SDK 用法 / 快速开始 / 完整示例 | [README.md](README.md)（快速开始、消息发送、完整示例章节） |
| 协议概念（contextToken/cursor/client_id/媒体类型/登录状态） | README「协议相关概念」「上下文机制说明」章节 |
| 配置项含义与默认值 | `core/config/ILinkConfig` + `src/main/resources/ilink-sdk.properties` + README「配置说明」 |
| 消费方怎么用本 SDK | [../wechat-ilink-bot/.claude/rules/sdk-usage.md](../wechat-ilink-bot/.claude/rules/sdk-usage.md) |
| 登录流程 | `service/LoginService` + `core/login/` |
| 收消息 / 长轮询 / 游标 | `service/UpdateService` + `ILinkClient.pollAndDispatchMessages` |
| 发消息 / 媒体加密 | `service/MessageService` / `service/MediaService` |
| 断线/心跳 | `core/lifecycle/` + `core/state/` |

## 常用命令

```powershell
mvn clean package        # 构建（无 shade，产出普通 jar）
mvn test                 # 跑测试（新测试写在 src/test/java 镜像包）
mvn install              # 装入本地仓库供 bot/imoney 引用
```

> 行为指南（Think Before Coding / Simplicity First / Surgical Changes / Goal-Driven Execution）由根级 [../CLAUDE.md](../CLAUDE.md) 统一定义，此处不重复。
