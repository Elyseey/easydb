# Quality Guidelines

> Code quality standards for frontend development.

---

## Overview

前端代码质量标准，聚焦在已发生过的真实 Bug 模式。

---

## Forbidden Patterns（禁用模式）

### ❌ 硬编码颜色值

```tsx
// 禁止
background: '#1e1e1e'
color: '#d4d4d4'

// 必须使用
background: 'var(--edb-bg-surface)'
color: 'inherit'                   // 或 token.colorText
```

### ❌ console.log 留在生产代码中

所有调试用 console.log 必须在 PR 前清除。

### ❌ any 类型

```tsx
// 禁止
const handler = (data: any) => ...

// 必须明确类型
const handler = (data: Record<string, unknown>) => ...
```

---

## Required Patterns（必须遵守）

### ✅ Ant Design Virtual Table — 使用 resize 触发重测，不使用 key/gridVersion remount

每当使用 `<Table virtual>` 且操作会导致**行数变化**（增/删），不要通过父节点 `key`、Table `key` 或 `gridVersion` 强制 remount。

**原因**：在 Tabs / flex 容器里 remount virtual table 时，`rc-virtual-list` 可能在容器高度为 0 的时机完成初始测量，导致渲染 0 行或滚动位置丢失。

```tsx
// 正确：行数变化后触发 virtual-list 重新测量
requestAnimationFrame(() => {
  tableRef.current?.scrollTo({ index: nextIndex })
  window.dispatchEvent(new Event('resize'))
})
```

> 详见 [Component Guidelines — Virtual Table 陷阱](./component-guidelines.md)

### ✅ 设计变量使用

所有颜色/边框/背景必须来自：
- `token.*`（Ant Design 主题变量）
- `var(--edb-*)` CSS 语义变量（跨主题的文字、背景、边框、状态和浮层）
- `var(--glass-*)` 仅用于流光主题特有的透明度、模糊和内发光效果

### ✅ 多风格主题契约

- `data-theme-style` 只允许 `professional | glass`，`data-theme` 只允许 `light | dark`。
- 新安装默认 `professional + light`；存在旧 `easydb-theme` 且没有
  `easydb-theme-style` 的用户迁移为 `glass + 原明暗模式`。
- 通用组件不得直接写流光配色；必须依赖 `--edb-*` 或 Ant Design token。
- 输入框必须显式覆盖文字、占位符、光标、hover、focus、error 和 disabled 状态。
- 组合输入框必须使用 `:focus-within` 给外层显示焦点环，不能只依赖 Ant Design
  运行时添加的 `*-focused` class。
- 下拉菜单、Popover、Modal、Drawer、消息通知使用统一语义浮层背景与层级变量，
  防止透明背景穿透或相互遮挡。

```css
/* ❌ 错误：通用组件绑定流光主题 */
.search-box { background: var(--glass-panel); color: #fff; }

/* ✅ 正确：四种风格/明暗组合共享语义变量 */
.search-box { background: var(--edb-input-bg); color: var(--edb-text-primary); }
.ant-input-affix-wrapper:focus-within {
  border-color: var(--edb-accent);
  box-shadow: 0 0 0 3px var(--edb-accent-muted);
}
```

### ✅ Workbench Tab Switching — 区分数据缓存和视图重建成本

工作台顶层页签切换必须优先检查前端渲染成本。`openTableTabs` 缓存了表数据，并不代表
`EditableDataTable`、`QueryEditorPane` 或 Monaco 实例不会重建。

涉及工作台页签、SQL 查询页签、表数据预览、设计器的改动必须检查：

- 激活已有 tab 时是否避免重复请求 `columns` / `previewRows` / `ddl`。
- 是否避免把 1000 行以上的宽表数据复制成新的 row object。
- 重型 pane 是否从 `WorkbenchPage` 内联 JSX 中提取为 memoized child。
- 传给 memoized pane 的回调是否用 `useCallback` 稳定。
- `<Table virtual>` 是否使用 numeric `scroll.x` / `scroll.y`，避免 `x: 'max-content'`。
- KeepAlive 中的 SQL pane 是否只在 active 时挂载 Monaco DOM，并为每个 SQL tab 使用唯一 `path` + `keepCurrentModel`。
- KeepAlive 中的 SQL 结果虚拟表是否在 active 恢复时重测 wrapper 高度、恢复 `scrollTop` 并触发 resize/scroll。

> 详见 [Component Guidelines — Workbench Tab Switching](./component-guidelines.md)

---

## Code Review Checklist

### 通用

- [ ] TypeScript 无 any 类型
- [ ] 无 console.log
- [ ] 无硬编码颜色值
- [ ] Tooltip 包裹 Dropdown 触发器时，菜单是否使用 click 触发，并在菜单打开期间隐藏 Tooltip，避免浮层遮挡菜单项？
- [ ] 自定义 Ant Design `Menu` 包含嵌套子菜单时，Portal 浮层是否有独立的 `--glass-popup` 背景和高于根菜单的层级？
- [ ] 查询结果的只读/可编辑网格是否共享行选择、右键范围和“全部/所选”导出语义？

### 涉及 `<Table virtual>` 的 PR

- [ ] 每个改变行数的操作是否都触发了 virtual-list resize 重测？
- [ ] 是否避免了父节点 `key` / Table `key` / `gridVersion` remount？
- [ ] `scroll.x` 和 `scroll.y` 是否使用数字值？
- [ ] 新增的操作是否在 component-guidelines.md 的表格中记录？

### 涉及工作台页签切换的 PR

- [ ] 已有 tab 切换是否不触发新的元数据或预览数据请求？
- [ ] 表页签切换是否不复制整行宽表数据？
- [ ] SQL 页签切换是否不会因为父组件重渲染重建 Monaco？
- [ ] SQL 页签在工作台 KeepAlive 中是否只为 active pane 挂载 Monaco DOM，并使用唯一 model `path` + `keepCurrentModel`？
- [ ] SQL 查询结果表切回时是否不会空白，并能保留切走前的滚动位置？
- [ ] 用至少一个宽表（约 50 列、1000 行 preview）手动验证 tab 切换无明显停顿？

### 涉及弹窗/Modal 的 PR

- [ ] SQL 预览等代码块是否使用了 `var(--edb-bg-surface)` 等语义变量而非硬编码颜色？
- [ ] `Modal.confirm` / `modal.confirm` 是否在深色和浅色主题下都能看见标题、正文、取消按钮和确认按钮？
- [ ] 全局 Modal 样式是否同时覆盖 `.ant-modal-content` 和 AntD v6 的 `.ant-modal-container`？
- [ ] 含输入框的弹窗是否使用受控 `<Modal>`，而不是把表单塞进 `Modal.confirm`？
- [ ] 右键菜单是否做了边界检测（靠近底部/右侧时翻转）？

### 涉及 AutoComplete + Enter 触发的 PR

- [ ] Enter 触发操作前是否检查下拉框已关闭？（参考 `suggestionsOpen` 追踪）
- [ ] 是否避免在用户选择建议时误触发操作（如：确认表单 / 执行查询 / 提交）？
- [ ] 连续输入过程中是否会因为 React 异步状态导致误判？（用 `onDropdownVisibleChange` 而非事件推断）

### ❌ AutoComplete Enter 误触发（禁止模式）

```tsx
// 禁止：不区分“选建议”和“执行操作”，输入未完成就触发了
<AutoComplete onKeyDown={(e) => { if (e.key === 'Enter') submitQuery() }}>

// 必须：追踪下拉框状态，仅在下拉关闭时响应 Enter
const [open, setOpen] = useState(false)
<AutoComplete
  onDropdownVisibleChange={setOpen}
  onKeyDown={(e) => { if (e.key === 'Enter' && !open) { e.preventDefault(); submitQuery() }}}
>

---

## Testing Requirements

验证方式：

1. `npm run build`（包含 TypeScript 编译）零错误
2. `npx vitest run` 全部通过
3. 目标功能手动测试
4. 不破坏现有功能（特别是数据编辑保存流程）

---

## Scenario: SQL 查询结果安全回写

### 1. Scope / Trigger

当 SQL 查询结果允许直接编辑并通过表数据编辑 API 回写时，必须同时验证“表有主键”和“当前结果集包含完整主键”。所有用户输入值（尤其多行 JSON、引号和 CLOB 文本）必须作为 JDBC 参数传递；保存后的刷新必须绑定产生该结果集的原始 SELECT。

### 2. Signatures

```ts
type EditabilityReason = 'missing_primary_key_columns' | /* existing reasons */ string
type EditabilityStatus = {
  editable: boolean
  primaryKeys?: string[]
  missingPrimaryKeys?: string[]
}

type DataEditStatement = {
  sql: string              // executable SQL containing only ? placeholders
  parameters: Array<string | null>
  previewSql: string       // display only; never execute
}
```

后端 `POST /metadata/{connectionId}/{database}/tables/{table}/edit` 执行每条按主键定位的行变更时，预期影响行数必须为 `1`。

### 3. Contracts

- `result.columns` 是 SQL 实际结果列，是判断主键是否随结果返回的唯一来源。
- `tableDef.columns` 只提供表主键定义，不能证明结果行携带主键值。
- 保存批次必须事务化：全部语句各影响 1 行后提交；任一语句影响 0 行或多行则回滚。
- `DataEditResult.success = true` 只表示变更已提交，不能用于表示“SQL 无异常但没有更新数据”。
- `DialectAdapter` 只负责标识符和占位符 SQL；路由必须用 `PreparedStatement` 按 `parameters` 顺序绑定值，禁止执行 `previewSql`。
- 查询结果刷新使用当前 `SqlResult.sql`，不得调用会重新读取 Monaco 当前选区的通用执行入口。
- 提交成功后刷新失败时保留已提交状态，并分别提示“保存成功、刷新失败”。

### 4. Validation & Error Matrix

- 表没有主键 → `no_primary_key`，只读。
- SELECT 缺少任一主键列 → `missing_primary_key_columns`，只读并列出缺失列。
- SQL 生成数量少于变更数量 → 拒绝执行。
- 任一更新影响 0 行 → 回滚，提示刷新后重试。
- 任一主键更新影响多行 → 回滚，提示定位条件异常。
- JDBC 参数绑定/执行失败 → 回滚，`success = false`，保留数据库原始错误信息。
- 保存提交成功但原 SELECT 刷新失败 → 不回滚已提交数据，显示独立刷新提示。

### 5. Good/Base/Bad Cases

- Good：联合主键全部在 SELECT 中，多行 JSON 通过 `?` 参数更新，恰好 1 行并提交，再以该结果集的 `SqlResult.sql` 刷新。
- Base：`SELECT *` 包含完整主键，普通短文本保持可编辑。
- Bad：把 JSON 拼进 SQL 字面量，或保存后调用 `handleExecute()` 导致当前编辑器选区被误执行。

### 6. Tests Required

- 单元测试：缺少一个或多个主键列时返回 `missing_primary_key_columns` 和准确列名。
- 单元测试：完整主键存在时结果保持可编辑。
- 后端/集成测试：0 行更新不返回成功，批次中任一失败会回滚。
- 单元测试：可执行 SQL 只含占位符，多行 JSON/单引号原样存在于参数列表且顺序正确。
- 前端单元测试：刷新请求的 SQL 必须等于目标 `SqlResult.sql`；刷新错误必须 reject 供保存流程单独提示。
- 手动验证：保存后立即重新执行原 SELECT，必须看到持久化值。

### 7. Wrong vs Correct

```ts
// ❌ Wrong：只看表元数据
if (tablePrimaryKeys.length > 0) return { editable: true }

// ✅ Correct：结果集必须包含完整主键
const resultColumns = new Set(result.columns.map((name) => name.toLowerCase()))
const missing = tablePrimaryKeys.filter((name) => !resultColumns.has(name.toLowerCase()))
if (missing.length > 0) {
  return { editable: false, reason: 'missing_primary_key_columns', missingPrimaryKeys: missing }
}
```

```kotlin
// ❌ Wrong：用户值进入 SQL 语法文本
statement.executeUpdate("UPDATE ${dialect.quoteIdentifier(table)} SET value = ${dialect.escapeValue(json)}")

// ✅ Correct：方言生成占位符 SQL，值只通过 JDBC 参数传递
connection.prepareStatement(dialect.buildUpdateSql(table, setColumns, primaryKeys)).use { prepared ->
    parameters.forEachIndexed { index, value ->
        if (value == null) prepared.setNull(index + 1, java.sql.Types.NULL)
        else prepared.setString(index + 1, value)
    }
    prepared.executeUpdate()
}
```
