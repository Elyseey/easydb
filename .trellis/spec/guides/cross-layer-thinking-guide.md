# Cross-Layer Thinking Guide

> **Purpose**: Think through data flow across layers before implementing.

---

## The Problem

**Most bugs happen at layer boundaries**, not within layers.

Common cross-layer bugs:
- API returns format A, frontend expects format B
- Database stores X, service transforms to Y, but loses data
- Multiple layers implement the same logic differently

---

## Before Implementing Cross-Layer Features

### Step 1: Map the Data Flow

Draw out how data moves:

```
Source → Transform → Store → Retrieve → Transform → Display
```

For each arrow, ask:
- What format is the data in?
- What could go wrong?
- Who is responsible for validation?

### Step 2: Identify Boundaries

| Boundary | Common Issues |
|----------|---------------|
| API ↔ Service | Type mismatches, missing fields |
| Service ↔ Database | Format conversions, null handling |
| Backend ↔ Frontend | Serialization, date formats |
| Component ↔ Component | Props shape changes |

### Step 3: Define Contracts

For each boundary:
- What is the exact input format?
- What is the exact output format?
- What errors can occur?

---

## Common Cross-Layer Mistakes

### Mistake 1: Implicit Format Assumptions

**Bad**: Assuming date format without checking

**Good**: Explicit format conversion at boundaries

### Mistake 2: Scattered Validation

**Bad**: Validating the same thing in multiple layers

**Good**: Validate once at the entry point

### Mistake 3: Leaky Abstractions

**Bad**: Component knows about database schema

**Good**: Each layer only knows its neighbors

### Mistake 3.1: Unencoded Dynamic URL Path Segments

**Scenario**: Database object names are inserted directly into REST paths.

**Result**: Names containing reserved URL characters change the route before the backend sees them. For example, Dameng system tables such as `##PLAN_TABLE` make the browser treat `#...` as a fragment, so the request path is truncated and returns 404.

**Example**:
```ts
// ❌ Bad — `#`, `/`, `?`, and spaces can corrupt the path
request(`/api/metadata/${connectionId}/${database}/tables/${table}/definition`)

// ✅ Good — encode every dynamic path segment independently
const seg = (value: string) => encodeURIComponent(value)
request(`/api/metadata/${seg(connectionId)}/${seg(database)}/tables/${seg(table)}/definition`)
```

**Rule**: Every user/database/object value placed into a URL path segment must pass through `encodeURIComponent`. Do not encode the full path at once.

### Mistake 4: View/Reference Leakage Across Synchronized Boundaries

**Scenario**: A `@Synchronized` method returns a `subList()` / `asReversed()` view of an internal mutable collection.
The caller (HTTP response serializer) iterates outside the lock, while another thread modifies the original list.

**Result**: `ConcurrentModificationException` 或更隐蔽的数据重复/错位。

**Example**:
```kotlin
// ❌ Bad — 返回视图引用，序列化在锁外进行
@Synchronized
fun query(): List<Event> {
    return events.asReversed().subList(start, end)  // 视图!
}

// ✅ Good — 返回防御性副本
@Synchronized
fun query(): List<Event> {
    return events.asReversed().subList(start, end).toList()  // 独立副本
}
```

**规则**: `@Synchronized` 方法如果返回集合，**必须返回独立副本**（`.toList()` / `.toMap()` 等），禁止返回原始集合的视图。

---

## Checklist for Cross-Layer Features

Before implementation:
- [ ] Mapped the complete data flow
- [ ] Identified all layer boundaries
- [ ] Defined format at each boundary
- [ ] Decided where validation happens
- [ ] **Checked synchronized/locked methods不返回可变集合的视图引用**

After implementation:
- [ ] Tested with edge cases (null, empty, invalid)
- [ ] Verified error handling at each boundary
- [ ] Checked data survives round-trip
- [ ] **Verified concurrent access safety（锁释放后返回值是否独立于共享状态）**
- [ ] **防御机制的误杀率 < 防御目标的发生率**

---

### Mistake 5: Defensive Measures with Catastrophic False-Positive Cost

**Scenario**: 添加去重/防抖机制防止罕见的重试事件，但去重 key 使用了不可靠的字段（如 MySQL `log_pos`），导致 99%+ 的合法事件被静默丢弃。

**原则**: 防御机制的副作用（误杀率）必须远小于它防御的风险（实际发生率）。

**Example**:
```kotlin
// ❌ Bad — log_pos 在 replication 中可能为 0，导致所有同表同类型事件被去重
val sig = "${db}.${table}:${file}:${header.position}-${eventType}"
if (!seen.add(sig)) return  // 丢弃了 99% 合法事件!

// ✅ Good — 不使用不可靠字段做去重，或先验证字段的唯一性
// 方案 A: 不去重（connector 正常情况不会重发）
events.add(event)
// 方案 B: 如需去重，先打日志确认 key 分布
logger.debug("sig=$sig, position=${header.position}")
```

**规则**: 添加防御机制前，**必须验证 key 的唯一性分布**（至少用 debug 日志跑一次）。

---

## When to Create Flow Documentation

Create detailed flow docs when:
- Feature spans 3+ layers
- Multiple teams are involved
- Data format is complex
- Feature has caused bugs before

### Mistake 6: Kotlin Enum 缺少 `@Serializable`（kotlinx.serialization 体系）

**Scenario**: 在 EasyDB 使用 Ktor + `kotlinx.serialization`。新建模块时定义 enum class，只给 data class 加了 `@Serializable`，忘记给内嵌的 enum 加注解。

**Result**:
- **编译期不报错**（Kotlin 编译器不强制要求 enum 有注解）
- **运行时序列化失败**，POST 请求体抛出 `SerializationException`
- 错误信息：`Serializer for class 'XxxEnum' is not found`

**Example**:
```kotlin
// ❌ Bad — data class 有注解，但 enum 没有 → 运行时爆炸
@Serializable
data class SlowQueryQueryRequest(val sortBy: SlowQuerySortField)
enum class SlowQuerySortField { AVG_LATENCY, MAX_LATENCY }

// ✅ Good — enum 也必须加 @Serializable
@Serializable
data class SlowQueryQueryRequest(val sortBy: SlowQuerySortField)
@Serializable
enum class SlowQuerySortField { AVG_LATENCY, MAX_LATENCY }
```

**规则**: `kotlinx.serialization` 体系中，**所有被序列化的类型（包括 enum）必须加 `@Serializable`**。

**检查时机**: 新建 XxxModels.kt 时，对每个 enum 逐一确认。

---

### Mistake 7: SQL 解析正则遗漏多表语法 → 列显示错误

**Scenario**: 用户执行多表 JOIN/逗号分隔查询（如 `SELECT ... FROM a, b WHERE ...`），但前端显示的结果包含不在 SELECT 中的字段。

**Root Cause Chain**:
1. `extractAllTableNames` 正则只匹配 `FROM` 后第一个标识符，遗漏逗号分隔和 JOIN 后的表名
2. `analyzeEditability` 误判为"单表查询"，返回 `editable: true` + 完整表定义的所有列
3. `EditableDataTable` 使用 `tableDef.columns`（全列）而非 `result.columns`（SQL 实际列）

**Example**:
```ts
// ❌ Bad — 只匹配 FROM 后第一个表名，遗漏逗号分隔和 JOIN 表
const regex = /(?:FROM|UPDATE|INTO)\s+([`'"]?[a-zA-Z0-9_$.]+[`'"]?)/gi
// 对于 "FROM bd_frame_location l, bd_device d"，只匹配 bd_frame_location

// ✅ Good — 先提取 FROM 子句，再按逗号拆分 + 单独匹配 JOIN
// 1. 提取 FROM 到 WHERE 之间的子句，按逗号拆分取每个段的表名
// 2. 单独匹配 (LEFT|RIGHT|INNER|CROSS|FULL)? JOIN 后的表名
// 3. 去重合并
```

**规则**:
- SQL 解析正则必须覆盖所有多表语法：逗号分隔、JOIN（LEFT/RIGHT/INNER/CROSS）、子查询
- **编辑性分析的列来源必须与 SQL SELECT 列一致**，不得盲目使用完整表定义列
- `EditableDataTable` 应基于 `result.columns` 过滤显示，而非直接使用 `tableDef.columns`

---

### Mistake 8: 分页策略注入的合成列泄漏到结果中

**Scenario**: 使用 `ROWNUM_SUBQUERY` 分页策略（Oracle/达梦旧版）时，分页 SQL 注入 `_easydb_rn` 列，但列提取代码不做过滤，导致结果中出现不期望的合成列。

**Root Cause**: `PaginationStrategy.ROWNUM_SUBQUERY` 生成 `SELECT _easydb_inner.*, ROWNUM _easydb_rn FROM (...)`，`getColumnLabel()` 遍历全部列时把 `_easydb_rn` 也收入 `columns`。

**Example**:
```kotlin
// ❌ Bad — 无过滤，合成列 _easydb_rn 进入结果
val columns = (1..columnCount).map { meta.getColumnLabel(it) }

// ✅ Good — 过滤掉 EasyDB 内部合成列
val columns = (1..columnCount)
    .map { meta.getColumnLabel(it) }
    .filter { !it.startsWith("_easydb_") }
```

**规则**: 所有分页策略注入的合成列（以 `_easydb_` 为前缀），必须在列提取后过滤掉。`SqlExecutionService.previewQuery` 和 `SqlQuerySessionManager` 都需要此过滤。

---

### Mistake 9: Metadata Mutation Succeeds but Client Identity State Stays Old

**Scenario**: A database object mutation changes object identity, such as renaming a table. The backend API succeeds, but the frontend only refreshes one list and leaves other state keyed by the old name.

**Result**:
- The object tree, selected context, active tab key, tab title, detail loaders, and cached object maps can disagree.
- Users see a success toast but the UI still appears unchanged or keeps sending follow-up requests to the old object path.

**Example**:
```ts
// ❌ Bad — only refreshes the object list
await metadataApi.renameTable(connId, dbName, oldName, newName)
loadTables(connId, dbName)

// ✅ Good — update every client identity copy first, then refresh from the backend
await metadataApi.renameTable(connId, dbName, oldName, newName)
applyRenamedTableState(connId, dbName, oldName, newName)
loadTables(connId, dbName)
```

**Rule**: Any mutation that changes an entity identity must update all frontend state keyed by the old identity before relying on a background refresh. For workbench table rename, check `objectsMap`, `selectedCtx`, `activeTable`, `activeTableTabKey`, and `openTableTabs`.

---

## Checklist for Cross-Layer Features（更新版）

Before implementation:
- [ ] Mapped the complete data flow
- [ ] Identified all layer boundaries
- [ ] Defined format at each boundary
- [ ] Decided where validation happens
- [ ] **Checked synchronized/locked methods不返回可变集合的视图引用**
- [ ] **`kotlinx.serialization`体系：所有出现在 @Serializable data class 中的 enum 都加了 `@Serializable`**
- [ ] **SQL 解析正则是否覆盖逗号分隔和 JOIN 多表语法**
- [ ] **分页策略注入的合成列是否在列提取后被过滤掉**
- [ ] **对象重命名/移动后是否同步所有以旧对象名为 key 的前端状态**

After implementation:
- [ ] Tested with edge cases (null, empty, invalid)
- [ ] Verified error handling at each boundary
- [ ] Checked data survives round-trip
- [ ] **Verified concurrent access safety**
- [ ] **防御机制的误杀率 < 防御目标的发生率**
- [ ] **POST 接口用真实 JSON body 实测一次，验证序列化/反序列化不抛异常**
- [ ] **多表 JOIN 查询的列显示是否与 SQL SELECT 一致（非完整表列）**
- [ ] **ROWNUM_SUBQUERY 等分页策略的合成列是否被过滤**
- [ ] **重命名成功后 UI 对象树、选中上下文、active tab、打开 tab key 是否都指向新对象名**
