# 贡献指南 / Contributing

欢迎贡献！先读 [README.md](README.md) 了解项目定位，再读本文件了解开发约定。
完整架构契约（AI 辅助开发时强烈推荐）见 [CLAUDE.md](CLAUDE.md)。

## 环境准备

- **JDK 17**（源码/目标 17；可用 Java 17 语法）
- **Maven 3.6.3+**（由 enforcer 强制）

校验：
```bash
java -version
mvn -v
```

## 构建 & 测试

```bash
mvn clean test          # 跑全部单元测试（覆盖率报告见 target/site/jacoco/）
mvn clean package       # 打可运行 fat jar
```

- 新功能必须带 JUnit 5 测试，整体覆盖率 ≥ 80%。
- 测试命名：`methodName_scenario_expectedBehavior`。
- 带 live 集成测试（DashScope / Anthropic / 真实 MCP）用 `@EnabledIfEnvironmentVariable` 守卫，CI 默认跳过。

## 代码约定

- Java 17 语法：`var` / `record` / `sealed` / text block / switch expression / pattern matching 均可用。
- 无 Spring、无 DI 框架、无 Lombok；依赖全部**构造器注入**，字段 `private final`。
- 单文件 ≤ 400 行，单方法 ≤ 60 行。
- 统一 SLF4J 日志，**禁止** `System.out` / `printStackTrace`；敏感数据（token、apiKey）不得入日志。
- 严格三层依赖：Application → Framework → Implementation，**禁止**反向引用；`mode/` 包零 SDK import。
- 文档同步：改了代码按 `.claude/rules/doc-maintenance.md` 的映射表更新对应文档。

## 提交约定

提交消息前缀：`feat:` / `fix:` / `refactor:` / `test:` / `docs:`。

## 受保护文件（hook 守卫）

`pom.xml`（技术基线：JDK 17 / SDK 3.0.0）和 `data/*.json`（凭证）受 `.claude/hooks/guard-edit.sh`
保护，自动编辑会被阻断、需手动修改。如需升级基线版本，请在 PR 里说明动机并手动改 `pom.xml`。
