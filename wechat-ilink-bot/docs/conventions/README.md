# 编码约定

## 基本原则

- Java 17（可用 Java 17 语法）
- 无 Spring、无 DI 框架、无 Lombok
- 构造器注入，`GameApplication` 为唯一组合根
- 依赖方向：Application → Framework → Implementation，严格向下

## 详细约定

| 约定 | 文档 |
|------|------|
| 命名规则 | naming.md |
| 依赖注入 | di.md |
| 错误处理 | error-handling.md |
| 日志规范 | logging.md |
| 测试规范 | testing.md |
| 会话管理 | session-management.md |
| 命令模式 | command-pattern.md |

## 代码大小限制

- 单文件 ≤ 400 行
- 单方法 ≤ 60 行

## 提交消息

```
feat: 添加种植命令
fix: 修复收获空地块的空指针异常
refactor: 提取通用命令解析逻辑
test: 添加浇水命令测试
docs: 更新命令规范文档
```
