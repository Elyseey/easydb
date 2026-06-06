# Thinking Guides

> **Purpose**: Expand your thinking to catch things you might not have considered.

---

## Why Thinking Guides?

**Most bugs and tech debt come from "didn't think of that"**, not from lack of skill:

- Didn't think about what happens at layer boundaries → cross-layer bugs
- Didn't think about code patterns repeating → duplicated code everywhere
- Didn't think about edge cases → runtime errors
- Didn't think about future maintainers → unreadable code

These guides help you **ask the right questions before coding**.

---

## Available Guides

| Guide | Purpose | When to Use |
|-------|---------|-------------|
| [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md) | Identify patterns and reduce duplication | When you notice repeated patterns |
| [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md) | Think through data flow across layers | Features spanning multiple layers |
| [Multi-Database Architecture Guide](./multi-database-architecture-guide.md) | Prevent MySQL-specific logic from leaking into generic database features | Any database-related feature or new driver |
| [Multi-Table Columns Bug](./break-loop-multi-table-columns.md) | SQL 多表查询列显示错误的根因分析 | 遇到列显示与 SQL 不一致的问题时 |
| [Dameng Metadata Comments and Length](./break-loop-dameng-metadata-comments.md) | 达梦 schema、表/列注释、字符长度元数据口径复盘 | 修改达梦/Oracle-like 元数据、设计页、DDL、工作台加载时 |

---

## Quick Reference: Thinking Triggers

### When to Think About Cross-Layer Issues

- [ ] Feature touches 3+ layers (API, Service, Component, Database)
- [ ] Data format changes between layers
- [ ] Multiple consumers need the same data
- [ ] You're not sure where to put some logic
- [ ] **新建 XxxModels.kt 文件，其中包含 enum class**（→ 检查每个 enum 是否有 `@Serializable`）
- [ ] **SQL 查询结果列与前端显示列来源不同**（→ `result.columns` 是 SQL 实际列，`tableDef.columns` 是完整表列，不可混用）
- [ ] **SQL 查询结果区同时展示执行批次和结果表**（→ 行数/结果集数与语句数必须分开命名）
- [ ] **分页 SQL 包装注入合成列**（→ 列提取后必须过滤 `_easydb_` 前缀）
- [ ] **用正则解析 SQL 提取表名**（→ 必须覆盖逗号分隔、JOIN、子查询等所有多表语法）
- [ ] **数据库对象重命名/移动后 UI 看起来没变**（→ 同步所有以旧对象名为 key 的前端状态，再后台刷新）

→ Read [Cross-Layer Thinking Guide](./cross-layer-thinking-guide.md)

### When to Think About Code Reuse

- [ ] You're writing similar code to something that exists
- [ ] You see the same pattern repeated 3+ times
- [ ] You're adding a new field to multiple places
- [ ] **You're modifying any constant or config**
- [ ] **You're creating a new utility/helper function** ← Search first!

→ Read [Code Reuse Thinking Guide](./code-reuse-thinking-guide.md)

---

### When to Think About MySQL performance_schema

- [ ] 功能需要读取 `events_statements_history_long`
- [ ] 功能需要检测慢查询 / SQL 执行历史
- [ ] **使用 `SELECT COUNT(*)` 检查 P_S 表可用性**（⚠️ 表可读 ≠ consumer 开启 ≠ 有数据）

→ Read [MySQL performance_schema Guide](./mysql-performance-schema-guide.md)

### When to Think About Multi-Database Architecture

- [ ] 新增或修改数据库类型 / driver / adapter
- [ ] 修改连接、元数据、SQL 执行、工作台、迁移、同步、结构对比
- [ ] 修改表/视图等 schema 对象的重命名、删除、截断入口
- [ ] 在通用代码中看到 `ServiceRegistry.mysqlAdapter` / `MysqlDatabaseSession`
- [ ] 在通用代码中看到 `INFORMATION_SCHEMA` / `SHOW CREATE` / `USE \`db\`` / `performance_schema`
- [ ] 前端新增数据库相关入口、按钮、菜单、页面

→ Read [Multi-Database Architecture Guide](./multi-database-architecture-guide.md)

---

## Pre-Modification Rule (CRITICAL)

> **Before changing ANY value, ALWAYS search first!**

```bash
# Search for the value you're about to change
grep -r "value_to_change" .
```

This single habit prevents most "forgot to update X" bugs.

---

## How to Use This Directory

1. **Before coding**: Skim the relevant thinking guide
2. **During coding**: If something feels repetitive or complex, check the guides
3. **After bugs**: Add new insights to the relevant guide (learn from mistakes)

---

## Contributing

Found a new "didn't think of that" moment? Add it to the relevant guide.

---

**Core Principle**: 30 minutes of thinking saves 3 hours of debugging.
