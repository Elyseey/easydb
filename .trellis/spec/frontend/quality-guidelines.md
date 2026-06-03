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
background: 'var(--glass-panel)'
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
- `var(--glass-*)` CSS 变量（项目玻璃态设计系统）

### ✅ Workbench Tab Switching — 区分数据缓存和视图重建成本

工作台顶层页签切换必须优先检查前端渲染成本。`openTableTabs` 缓存了表数据，并不代表
`EditableDataTable`、`QueryEditorPane` 或 Monaco 实例不会重建。

涉及工作台页签、SQL 查询页签、表数据预览、设计器的改动必须检查：

- 激活已有 tab 时是否避免重复请求 `columns` / `previewRows` / `ddl`。
- 是否避免把 1000 行以上的宽表数据复制成新的 row object。
- 重型 pane 是否从 `WorkbenchPage` 内联 JSX 中提取为 memoized child。
- 传给 memoized pane 的回调是否用 `useCallback` 稳定。
- `<Table virtual>` 是否使用 numeric `scroll.x` / `scroll.y`，避免 `x: 'max-content'`。

> 详见 [Component Guidelines — Workbench Tab Switching](./component-guidelines.md)

---

## Code Review Checklist

### 通用

- [ ] TypeScript 无 any 类型
- [ ] 无 console.log
- [ ] 无硬编码颜色值

### 涉及 `<Table virtual>` 的 PR

- [ ] 每个改变行数的操作是否都触发了 virtual-list resize 重测？
- [ ] 是否避免了父节点 `key` / Table `key` / `gridVersion` remount？
- [ ] `scroll.x` 和 `scroll.y` 是否使用数字值？
- [ ] 新增的操作是否在 component-guidelines.md 的表格中记录？

### 涉及工作台页签切换的 PR

- [ ] 已有 tab 切换是否不触发新的元数据或预览数据请求？
- [ ] 表页签切换是否不复制整行宽表数据？
- [ ] SQL 页签切换是否不会因为父组件重渲染重建 Monaco？
- [ ] 用至少一个宽表（约 50 列、1000 行 preview）手动验证 tab 切换无明显停顿？

### 涉及弹窗/Modal 的 PR

- [ ] SQL 预览等代码块是否使用了 `var(--glass-panel)` 而非硬编码颜色？
- [ ] `Modal.confirm` / `modal.confirm` 是否在深色和浅色主题下都能看见标题、正文、取消按钮和确认按钮？
- [ ] 全局 Modal 样式是否同时覆盖 `.ant-modal-content` 和 AntD v6 的 `.ant-modal-container`？
- [ ] 含输入框的弹窗是否使用受控 `<Modal>`，而不是把表单塞进 `Modal.confirm`？
- [ ] 右键菜单是否做了边界检测（靠近底部/右侧时翻转）？

---

## Testing Requirements

当前无自动化测试，验证方式：

1. `npx tsc --noEmit` 零错误
2. 目标功能手动测试
3. 不破坏现有功能（特别是数据编辑保存流程）
