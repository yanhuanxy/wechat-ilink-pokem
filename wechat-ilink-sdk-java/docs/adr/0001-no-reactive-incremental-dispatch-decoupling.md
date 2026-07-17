# ADR-0001 驳回 Reactive(Flux) 改造，采用增量式「分发解耦 + 自持接收循环」

- 状态：🟢 生效（P0 分发解耦 + 监听器隔离、P1 心跳看门狗、P3 onDisconnect + 死配置清理均已落地；**P2 撤销**——前提被 P0 消解）
- 权威约束位置：[AGENTS.md](../../AGENTS.md) 已知债；`ILinkClient.pollAndDispatchMessages` / `checkLiveness` + `core/executor/ExecutorManager`

## 背景

SDK 本质是同步 request/response 客户端：`getUpdates()` 是一次性阻塞轮询，`send*`/media 阻塞在调用方线程。唯一内建的**持续接收循环是心跳**——`HeartbeatService` 的 tick 被接到 `pollAndDispatchMessages()`，干的是「poll + 就地同步分发 `OnMessageListener`」；而 `heartbeatIntervalMs`（默认 30s）**同时是心跳节拍与消息轮询间隔**。由此三个卡点：

1. **分发与轮询同线程串行**：`scheduleWithFixedDelay` 语义下，下一次 poll 要等这次 poll + **全部监听器处理**跑完；一个慢监听器（LLM/DB/子进程）直接卡死收消息。
2. **监听器回调零隔离**：`pollAndDispatchMessages` 逐个调 listener 无 try/catch，一个监听器抛异常 → 中断整批剩余监听器，且在心跳路径上被 `HeartbeatService` 的 `catch(Throwable)` **误报成 `onHeartbeatFailure` 并静默丢掉整批消息**。
3. **心跳既当 liveness 又当 poller，成为冗余第二 poller**：getupdates 是**长轮询**（服务端 hold，`readTimeout=35s`）。实际消费方（bot 的 `GameApplication.pollLoop`）已自持一个 `while(running)` 连续长轮询循环——一次返回立刻再轮询，是长轮询的正确姿势，消息近实时、**不存在 30s 延迟**。而登录后 `heartbeatService.start()` 的 tick 也在调 `pollAndDispatchMessages`，于是与消费方循环**抢同一把 `pollLock`、重复打 getupdates**；心跳并未提供独立 liveness，只是排在消费方长轮询后面又发一次冗余请求。

> 更正：本 ADR 初稿曾把「30s」当作用户可感延迟。核实后——消费方自持连续长轮询，30s 心跳是**冗余 poller** 而非延迟来源。P1 的目标据此从"把 poll 间隔调小"修正为"把心跳从 poller 改回真正的 liveness"。

曾提议将 SDK 整体改造为 Reactive（Reactor / `Flux<WeixinMessage>` 推流）以解决上述问题。

## 决策

**驳回 Reactive 改造**；改用不破坏对外 API 的**增量路径**，分阶段消除卡点：

- **P0（本 ADR 随附落地）**：poll 后把监听器分发提交到**单线程 dispatch executor**（保序、不阻塞 poll 循环），每个监听器包 try/catch 单独兜异常。
- **P1（本 ADR 随附落地）**：心跳从 poller 改为**不轮询的 liveness 看门狗**——`pollAndDispatchMessages` 成功后打点 `lastPollSuccessAt`；心跳 tick（`checkLiveness`）只检查"距上次成功 getupdates 是否超过 `livenessThresholdMs`（默认 90s，宽于一个长轮询周期避免安静期误报）"，超阈值抛异常 → `HeartbeatService` 报 `onHeartbeatFailure`，否则 `onHeartbeatSuccess`。消费方循环成为唯一 poller，冗余双轮询消除。
- **P2（撤销）**：原计划"重试退避不在 poll 线程 `Thread.sleep`"。P0 落地后前提消解——消费方 poll 是专用线程，退避 sleep 正是"两次 poll 间的正确退避"；`send*`/media 是同步阻塞客户端的设计契约，改异步须动对外 API 面（违反兼容红线）。故 `HttpClientFacade` 保持不变。（若日后要做，唯一有价值的点是"HTTP 4xx 快速失败、不烧退避"，属协议邻域，需单独决策。）
- **P3（本 ADR 随附落地）**：① `OnDisconnectListener.onDisconnect` 此前注册但从不触发——现于 `pollAndDispatchMessages` 捕获 `SessionExpiredException`（服务端定性断线）时触发，**纯通知、逐监听器 try/catch 隔离、原样抛出**；**刻意不翻 `isLoggedIn` 状态**（翻转会让消费方基于 isLoggedIn 的控制流/清理路径受扰，如 bot pollLoop 会走无 `shutdown()` 的 `removeBot` 泄漏路径）。② `reconnect*`/`autoReconnect` 死配置全部 `@Deprecated`（Builder + getter）并从 `ConfigLoader` 移除加载；删掉 `routeTag` 上错误的 `// 好像没用` 注释（已证实用于 `SKRouteTag`）。io 线程池配置仍被 `LoginService` 使用，非死配置，保留不动。

## 理由

1. Reactive 会砸掉整个阻塞式**对外 API 面**（`ILinkClient` 全部 public 方法 = bot 与 iMoney 的调用点）并引入 Reactor 依赖——违反 AGENTS.md「对外 API 兼容」硬性规则，风险/收益不成比例。
2. 让人想上 Reactive 的真实收益（收消息不被慢处理阻塞、推流式消费）——**九成由 P0 分发解耦即可拿到**，纯增量、零 API 破坏。
3. 单线程 dispatch executor **保序**，不改变消费方对消息顺序的假设（bot 的 `GameBot.onMessages` 逐条路由）。

## 影响 / 代价

- **行为变更**：`OnMessageListener` 回调改在专用 dispatch 线程**异步**执行；`getUpdates()` 返回值仍同步给出，但监听器 side-effect 变为异步。已知消费方（bot）仅用 `onMessages` 回调、不依赖 `getUpdates()` 返回值，兼容。
- dispatch executor 队列当前**无界**：持续过载时可能堆积（默认 30s 心跳节拍下风险低）——有界队列 / 背压列入 P2 一并处理。
- `close()` 时 `shutdownNow` dispatch executor，可能丢弃队列中未分发的入向消息（关闭语义可接受）。
- **P1 行为变更**：心跳不再收消息——若某消费方此前**依赖 SDK 心跳来轮询**（自己不跑 poll 循环），升级后将收不到消息。已知消费方 bot 自持 `pollLoop`，不受影响；此约束需在 README/AGENTS 明确（消费方必须自持接收循环，P2 之后可提供可选自持循环）。新增可配 `livenessThresholdMs`。
- **特征化测试**列为紧随 follow-up：SDK 当前零测试，本次「保序 + 监听器隔离 + 看门狗阈值判定」行为应补测（需为 poll/dispatch/时钟引入可注入 seam）。
