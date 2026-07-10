# Changelog

本项目所有重要变更记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added（开源准备）
- `LICENSE`（MIT）。
- 面向小白的快速上手与「各模式前置条件表」（见 README）。
- `run.bat` / `run.sh` 一键构建运行脚本。
- `data/*.example` 配置模板（`bots` / `models-config` / `task-config` / `reliability-config` / `autogame-config`）。
- `SECURITY.md`、`CONTRIBUTING.md`、`CHANGELOG.md`。
- 仓库级 `.gitignore`，覆盖 `data/` 运行期配置与状态。
- `docs/examples/dashscope-upload.py`（由根目录的 `demo` 文件整理归档）。

### Changed
- `pom.xml`：JDK 基线 `1.8` → `17`（启用 Java 17 语法：var/record/sealed/text block 等）；`wechat-ilink-sdk` 由 `2.4.0-SNAPSHOT` 升至 `3.0.0`（JDK17 基线；`2.3.3` 作为 JDK8 收尾版本保留在 Maven Central）。
- `pom.xml`：`wechat-ilink-sdk` 锁定到已发布版本 `2.3.3`（此前依赖未发布的 `2.4.0-SNAPSHOT`）。
- `pom.xml`：新增 `maven-shade-plugin`（可运行 fat jar）与 `exec-maven-plugin`（`mvn exec:java`）。
- `task-config` 默认 `enabled:false` / `claudeBridgeEnabled:false` / `claudeAdminUsers:[]`，避免未装 `claude` CLI 时首跑报错。

> 历史变更在 `git filter-repo` 抽取为独立仓库前，可由 `git log` 追溯；此处自开源版本起维护。
