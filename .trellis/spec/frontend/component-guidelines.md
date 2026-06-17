# Component Guidelines

> How components are built in this project.

---

## Overview

EasyDB 前端使用 Ant Design 组件库 + 自定义玻璃态设计系统（`--glass-*` CSS 变量）。

---

## Ant Design Modal Confirm — Static Method Styling

When styling Ant Design modal dialogs globally, do not assume `.ant-modal-content` is the only visible surface.

Ant Design v6 confirm dialogs (`Modal.confirm`, `modal.confirm`) render a confirm-specific structure:

- `.ant-modal-content`
- `.ant-modal-container`
- `.ant-modal-confirm-body-wrapper`
- `.ant-modal-confirm-body`
- `.ant-modal-confirm-title`
- `.ant-modal-confirm-content`
- `.ant-modal-confirm-btns`

The actual white background can come from `.ant-modal-container`. If global glass styles only change text colors or buttons but miss `.ant-modal-container`, the dialog can become white-on-white: title/content disappear, cancel buttons look blank, and only highlighted text selection reveals the content.

Correct global override pattern:

```css
.ant-modal-content,
.ant-modal-container {
  background: var(--glass-popup) !important;
  color: var(--edb-text-primary) !important;
}

.ant-modal-confirm-title,
.ant-modal-confirm-title * {
  color: var(--edb-text-primary) !important;
}

.ant-modal-confirm-content,
.ant-modal-confirm-content * {
  color: var(--edb-text-secondary) !important;
}

.ant-modal-confirm-btns .ant-btn > span,
.ant-modal-footer .ant-btn > span {
  color: inherit !important;
}
```

For input flows, prefer a controlled `<Modal>` over `Modal.confirm` because confirm dialogs are optimized for short confirmation text, not form controls. For destructive confirmations, `Modal.confirm` is acceptable only if screenshots verify title, content, cancel button, and dangerous OK button are all visible in dark and light themes.

---

## Ant Design Virtual Table — Column Resize Performance

When adding column resize to `<Table virtual>`, do not update React state on every `mousemove`.

Bad pattern:

```tsx
const handleMouseMove = (event: MouseEvent) => {
  setColumnWidths((prev) => ({ ...prev, [column]: nextWidth }))
}
```

Why this is bad:

- Every width update recreates the `columns` array.
- AntD Table updates its `colgroup`, table layout, horizontal scroll width, and virtual-list measurements.
- Wide result grids turn left/right dragging into repeated layout reflow, so even `requestAnimationFrame` throttling still feels choppy.

Correct pattern:

```tsx
startDeferredColumnResize({
  event,
  startWidth,
  minWidth,
  maxWidth,
  boundsElement: tableWrapperRef.current,
  onCommit: (width) => setColumnWidths((prev) => ({ ...prev, [column]: width })),
})
```

During drag, move a lightweight fixed-position guide line imperatively. Commit the real column width only once on `mouseup`.

Use this pattern for SQL result grids and editable data tables. Live-resizing the actual AntD columns is not acceptable for large/virtualized tables.

When a resizable column contains single-line ellipsis content, the cell's inner content must follow the column width. Do not hardcode inner `maxWidth` values such as `maxWidth: 300`; use `width: '100%'`, `overflow: 'hidden'`, `textOverflow: 'ellipsis'`, and `whiteSpace: 'nowrap'`. For manually truncated previews, compute the preview length from the current column width. Never use `whiteSpace: 'normal'` or dynamic row heights in virtual tables for long text display; use a click-to-view modal for full content instead.

## Workbench Tab Switching — Virtual Table Mount Cost

Workbench object tabs cache table preview rows in state, but switching the active top-level tab still unmounts the old detail pane and mounts the new one. For large table previews, the tab switch path must avoid unnecessary data cloning and layout work.

Bad pattern:

```tsx
const tableData = useMemo(
  () => previewRows.map((row, index) => ({ ...row, _key: index, _rowIndex: index })),
  [previewRows]
)
```

Why this is bad:

- Every tab activation copies every field of every cached preview row.
- Wide tables amplify the cost because each row may contain many columns.
- The copy happens on the UI thread during the click-to-switch interaction.

Correct pattern:

```tsx
const tableData = useMemo(
  () => previewRows.map((_, index) => ({ _key: index, _rowIndex: index })),
  [previewRows]
)
```

Editable table cell rendering should read values from the original cached row array by row index, not from a copied AntD data record. Keep the AntD record as lightweight row identity and metadata.

For `<Table virtual>`, use numeric `scroll.x` and `scroll.y`. Avoid `scroll={{ x: 'max-content' }}` in virtual tables because it forces more table layout work and does not match Ant Design virtual table expectations.

```tsx
const tableScrollX = Math.max(900, columns.reduce((sum, column) => sum + columnWidth(column), 0))

<Table virtual scroll={{ x: tableScrollX, y: tableScrollY }} />
```

If tab switching still feels slow after row-copy and scroll sizing fixes, inspect whether the tab content is being unmounted. Workbench main tabs currently use a separate tab bar plus custom content rendering, so Ant Design `destroyInactiveTabPane` / `destroyOnHidden` is not enough when content is rendered outside `Tabs.items`.

Correct pattern for heavy workbench panes:

- Keep a bounded LRU set of recently used panes mounted.
- Always keep the active pane mounted.
- Pin dirty panes that contain unsaved edits.
- Remove closed panes from the mounted set immediately.
- Keep loaded data/query state in the tab session store so an evicted pane can remount without refetching.
- Pass an `active` signal into virtual-table panes and force a height/resize refresh when a hidden pane becomes visible.
- SQL query panes may stay mounted, but their Monaco editor DOM instance must exist only while the pane is active. Use a unique model `path` plus `keepCurrentModel` so tab text/model state survives without hidden editor DOM.

Do not keep every opened table tab permanently mounted; that trades one tab switch pause for cumulative DOM and memory pressure as users open more tables. Use bounded KeepAlive for render-instance performance, and store-backed tab sessions for recovery after eviction.

### Workbench KeepAlive — Monaco Editor Lifecycle

Monaco editors are imperative components and must be treated differently from plain React panes. Workbench SQL tab state is durable in `sqlEditorStore`, and SQL panes can remain mounted for result/state continuity, but inactive SQL editor DOM instances must not be kept mounted with `display: none`.

Bad pattern:

```tsx
<Editor
  key={`editor-${queryId}`}
  defaultValue={tab.sql}
  onMount={(editor) => editor.focus()}
/>
```

Why this is risky:

- Without a unique `path`, `@monaco-editor/react` can create or reuse the default empty URI model across multiple SQL tabs.
- Hidden keep-alive panes still have mounted editor instances, so a newly mounted editor and a hidden editor can compete for model state, focus, and async layout work.
- Focusing a hidden or just-disposed Monaco instance can surface runtime errors such as `InstantiationService has been disposed` or `Cannot read properties of undefined (reading 'domNode')`.

Correct pattern:

```tsx
const editorModelPath = useMemo(
  () => `inmemory://easydb/sql/${encodeURIComponent(queryId)}.sql`,
  [queryId],
)

useEffect(() => {
  if (!active) return
  const frame = window.requestAnimationFrame(() => {
    editorRef.current?.layout()
  })
  return () => window.cancelAnimationFrame(frame)
}, [active])

<Editor
  path={editorModelPath}
  keepCurrentModel
  defaultValue={tab.sql}
  onMount={(editor) => {
    editorRef.current = editor
    if (active) {
      window.requestAnimationFrame(() => {
        editor.layout()
        editor.focus()
      })
    }
  }}
/>
```

Keep SQL text and result state in the tab store or mounted SQL pane, isolate the Monaco model by tab identity, and mount/focus/layout Monaco only for the active SQL pane. Do not keep multiple hidden Monaco editor DOM instances alive just to preserve SQL tab state.

### Workbench KeepAlive — SQL Result Virtual Table Restore

SQL result panes can remain mounted while hidden, but Ant Design virtual tables must be explicitly refreshed when the pane becomes active again. A hidden pane uses `display: none`, so `rc-virtual-list` can keep a stale visible range. The symptom is that query result rows look blank after switching away and back, and the rows only reappear after the user scrolls.

Correct pattern:

```tsx
const tableWrapperRef = useRef<HTMLDivElement | null>(null)
const tableBodyRef = useRef<HTMLDivElement | null>(null)
const scrollTopRef = useRef(0)

const updateMeasuredTableHeight = useCallback(() => {
  const wrapperEl = tableWrapperRef.current
  if (!wrapperEl) return
  const wrapperHeight = wrapperEl.clientHeight
  if (wrapperHeight === 0) return
  const thead = wrapperEl.querySelector('.ant-table-thead')
  const headerHeight = thead ? Math.ceil(thead.getBoundingClientRect().height) : 40
  setTableScrollY(Math.max(220, wrapperHeight - headerHeight - 2))
}, [])

useEffect(() => {
  if (!active) return
  requestAnimationFrame(() => {
    updateMeasuredTableHeight()
    requestAnimationFrame(() => {
      const scrollBody = tableBodyRef.current
      if (!scrollBody) return
      scrollBody.scrollTop = scrollTopRef.current
      window.dispatchEvent(new Event('resize'))
      scrollBody.dispatchEvent(new Event('scroll'))
    })
  })
}, [active, updateMeasuredTableHeight])
```

Every SQL result virtual table in a keep-alive pane must:

- Accept an `active` signal from the workbench pane.
- Record the table body's `scrollTop` on scroll.
- Re-measure the flex wrapper height when `active` changes to true.
- Restore `scrollTop` and dispatch resize/scroll after the pane is visible.
- Measure the actual table wrapper, not `window.innerHeight - containerTop`.

Do not rely on user scrolling to wake the virtual list after tab restore.

---

## Ant Design Virtual Table — ⚠️ 五轮调试记录的终极陷阱（已完全解决）

### 症状描述

对工作台数据表格进行新增行、删除行、复制行等操作后，表格 UI 不刷新，
**只有切换 Tab 再切回来才会显示最新数据**。SQL 场景正常，直接打开表场景不正常。

### 根因全链路（五层叠加，均已分析确认）

**Layer 1（原始 bug）：`useMemo` 包裹 Table JSX 导致 dataSource 冻结**

```tsx
// 错误：useMemo 缓存 JSX 导致 Table 永远拿到旧 dataSource
const tableNode = useMemo(() => <Table dataSource={tableData} ... />, [deps])
```

**Layer 2（第一次修复引入的 bug）：`key={gridVersion}` 在 useMemo 内部无效**

`key` 放在 `useMemo` 内的 Table 上，外层 reconciler 看不到 key 变化，无效。

**Layer 3（第二次修复引入的 bug）：`key` 移到外层 div 导致 remount 测量时机错误**

`<div key={gridVersion}><Table virtual /></div>` —— remount 时 flex 布局尚未 settle，
`rc-virtual-list` 测量容器高度为 **0**，渲染 0 行。

**Layer 4（第三次修复引入的 bug）：`scroll.y + offset` 微偏移导致大表滚动位置丢失**

```tsx
// scroll.y 每次行数奇偶变化 -> rc-virtual-list 内部重新初始化 -> scrollTop 重置
scroll={{ y: tableScrollY + (effectiveData.length % 2) * 0.001 }}
```

**Layer 5（真正根因，已查 rc-virtual-list 源码确认）：`height` 依赖在 Tabs 容器初始 mount 时为 0**

查看 `@rc-component/virtual-list/lib/List.js` 第 131-192 行：

```js
const { scrollHeight, start, end } = React.useMemo(() => {
  ...
  if (startIndex === undefined) {
    endIndex = Math.ceil(height / itemHeight);  // height=0 时 endIndex=0，渲染 0 行！
  }
}, [inVirtual, useVirtual, offsetTop, mergedData, heightUpdatedMark, height]);
```

`height` 来自 `onHolderResize`（容器高度）。在 Ant Design Tabs 场景下：
- 初始 mount 时 Tabs 的布局尚未完全 settle，`height` 被测量为 0
- 之后容器尺寸稳定，ResizeObserver 不再触发，`height` 永远是 0
- `mergedData` 更新（新行插入）→ useMemo 重算 → 但 `height=0` → `endIndex=0` → 渲染 0 行
- 切换 Tab 时 CSS `display:none` → `display:block` → ResizeObserver 触发 → `height` 更新 → 正常渲染

### 最终正确解决方案

**核心：移除所有 key/useMemo 策略；在行变更操作后 dispatch resize 事件，强制触发 `onHolderResize` 更新 height**

```tsx
// Step 1：在 useLayoutEffect 里暴露 forceVirtualRefreshRef
forceVirtualRefreshRef.current = () => {
  // rc-virtual-list 监听 window resize，会重新测量 height 并重算 visible-range
  window.dispatchEvent(new Event('resize'))
}

// Step 2：所有行变更操作后调用（以 addRow 为例）
const addRow = useCallback(() => {
  // ...setState calls...
  requestAnimationFrame(() => {
    tableRef.current?.scrollTo({ index: newRowIndex }) // 滚动到新行
    forceVirtualRefreshRef.current()                   // 强制 virtual list 重算
  })
}, [...])

// Step 3：Table 使用纯 tableScrollY，不加任何偏移
<Table
  ref={tableRef}
  virtual
  dataSource={tableData}
  scroll={{ x: 'max-content', y: tableScrollY }}
  ...
/>
```

### 绝对禁止的反模式

```tsx
// 永远不要：给 virtual Table 的父节点加 key（remount 时 height=0，渲染 0 行）
<div key={someVersion}>
  <Table virtual ... />
</div>

// 永远不要：useMemo 包裹包含 Table 的 JSX
const tableNode = useMemo(() => <Table ... />, [...])

// 永远不要：修改 scroll.y 的偏移来"强制刷新"（会重置 scrollTop，大表滚动位置丢失）
scroll={{ y: tableScrollY + (count % 2) * 0.001 }}
```

---

### ❌ 手工猜算 toolbar/header 高度导致末尾行被裁切

**症状**：big_table 滑动到最下面，最后几行显示不完整，且无法继续滚动。

**根因**：`updateHeight` 用 `containerRef.clientHeight - toolbarHeight - headerHeight - outerGap` 计算 `scroll.y`，
但漏算了 WHERE 筛选栏高度（约 40px）和 margin（各 8px），导致 `tableScrollY` 比 wrapper 实际可用空间大约 30-50px。
virtual list 的 `scrollHeight` 按 `tableScrollY` 分配，但 wrapper `overflow: hidden` 把超出部分裁切掉了。

**正确做法**：直接测量 `flex:1` wrapper 的 `clientHeight`，减去**动态查询**的 `.ant-table-thead` 高度：

```tsx
// 正确：直接测量 wrapper 实际高度 + 动态查 thead 高度
const tableWrapperRef = useRef<HTMLDivElement>(null)

useLayoutEffect(() => {
  const wrapperEl = tableWrapperRef.current
  if (!wrapperEl) return
  const updateHeight = () => {
    const wrapperHeight = wrapperEl.clientHeight
    if (wrapperHeight === 0) return
    const thead = wrapperEl.querySelector('.ant-table-thead')
    const headerHeight = thead ? Math.ceil(thead.getBoundingClientRect().height) : 42
    setTableScrollY(Math.max(220, wrapperHeight - headerHeight - 2))
  }
  updateHeight()
  requestAnimationFrame(updateHeight) // thead 可能还没渲染
  const observer = new ResizeObserver(updateHeight)
  observer.observe(wrapperEl)
  return () => observer.disconnect()
}, [])

// JSX: wrapper 用 flex:1 自动吃掉剩余空间
<div ref={tableWrapperRef} style={{ flex: 1, overflow: 'hidden', minHeight: 0 }}>
  <Table virtual scroll={{ y: tableScrollY }} ... />
</div>
```

**绝对不要**：手工猜算 `containerHeight - toolbarHeight - 40 - 32`——每多一个 UI 元素就得改常量，维护不住。


---


---

## Component Structure

每个组件文件遵循以下顺序：

1. imports（React → antd → icons → internal types → services → utils）
2. 接口定义（Props interface）
3. 类型定义（local types）
4. 组件函数（useState → useMemo/useCallback → useEffect → render）

---

## Props Conventions

- 所有 props 必须用 TypeScript interface 定义，不使用 `any`
- 回调命名：`on<事件名>` (e.g., `onRefresh`, `onFilter`)
- 可选 props 用 `?` 标记，有合理默认语义

---

## Styling Patterns

- 优先使用 `token.*` 颜色变量（来自 `theme.useToken()`）
- 布局颜色使用 `var(--glass-*)` CSS 变量（支持深/浅色模式切换）
- 禁止硬编码颜色值（如 `#1e1e1e`）

---

## Common Mistakes

### ❌ useMemo 包裹 Table 导致不刷新

见上方《五层叠加》完整分析。

---

### ❌ useCallback 闭包陷阱：状态更新后立即调用读取旧 state 的函数

**症状**：`onRefresh`（保存后刷新）调用后数据不更新，重新打开或切换 Tab 后才看到新数据。

**场景**：`workbench/index.tsx` 中 `EditableDataTable` 的 `onRefresh` 回调：

```tsx
// 错误写法：updateTabState 是 setState（异步），loadTabDataForTab 读 openTableTabs（旧闭包）
onRefresh={() => {
  updateTabState(tabKey, (prev) => ({
    loadedTabs: prev.loadedTabs.filter(k => k !== 'data'),  // 异步，还未 commit
  }))
  loadTabDataForTab(...)  // 此时 openTableTabs 是旧值，loadedTabs 仍含 'data'
                          // → needTab = false → 直接 return，永远不重新加载
}}
```

**根因**：`loadTabDataForTab` 通过 `useCallback` 捕获了 `openTableTabs`。  
`updateTabState` 调用的是 React `setState`，是**异步**的——会在下次 render 时才生效。  
`loadTabDataForTab` 是**同步**调用，此时读到的 `openTableTabs[tabKey].loadedTabs` 还是**旧值**（含 `'data'`），  
因此 `needTab = !loadedTabs.includes('data') = false`，函数直接 `return`，数据不刷新。

**解决方案**：绕过 `loadedTabs` 检查，在 `onRefresh` 内直接调 API 并更新 state：

```tsx
// 正确写法：直接调 API，不依赖 loadedTabs 的检查逻辑
onRefresh={async () => {
  try {
    const rows = await metadataApi.previewRows(t.connectionId, t.database, t.tableName)
    updateTabState(tabKey, () => ({ previewRows: rows }))  // 直接更新，不依赖旧 state 的 loadedTabs
  } catch (e) {
    handleApiError(e, '刷新数据失败')
  }
}}
```

**推广规则**：凡是"先 setState，后立即调用依赖同一 state 的函数"的模式，都有此问题。  
正确做法是让依赖 state 的函数等待 state commit（放到 `useEffect` 里），或直接绕过 state 检查调 API。

---

### ❌ SQL 预览/弹窗使用硬编码颜色

```tsx
// 错误：硬编码黑色背景与主题不协调
<pre style={{ background: '#1e1e1e', color: '#d4d4d4' }}>

// 正确：使用 glass 设计变量
<pre style={{ background: 'var(--glass-panel)', border: '1px solid var(--glass-border)', color: 'inherit' }}>
```

---

### ❌ Virtual Table scroll.y 高度硬编码导致末行被裁切

**症状**：大表（1000 行）滚动到最底部时，最后一行只显示一半，且无法继续向下滚动。

**错误根因**：`updateHeight` 只测量了部分 toolbar 的高度（如图标工具栏），硬编码了 `headerHeight = 40, outerGap = 32`，
漏掉了同级的 WHERE 筛选栏（约 40px）和各元素 `marginBottom`（约 8px × 2），
导致 `tableScrollY` 比实际可用空间大约 40px。
Table body 超出外层 `overflow: hidden` 的 wrapper，底部被裁切。

```tsx
// 错误写法：硬编码猜算，遗漏 WHERE 筛选栏等元素
const updateHeight = () => {
  const toolbarHeight = toolbarRef.current?.getBoundingClientRect().height ?? 0  // 只测了图标栏！
  const headerHeight = 40   // 硬编码，与实际 thead 高度可能不符
  const outerGap = 32       // 随意取的安全边距，不够精确
  setTableScrollY(containerHeight - toolbarHeight - headerHeight - outerGap)
}
```

**正确做法**：在 `flex:1` 的 wrapper div 上加 `ref`，直接测量 wrapper 的 `clientHeight`，动态查询 `.ant-table-thead` 的真实高度：

```tsx
// 正确：直接测量 wrapper 实际高度，动态获取 thead 高度
const updateHeight = () => {
  const wrapperHeight = tableWrapperRef.current?.clientHeight ?? 0
  if (wrapperHeight === 0) return
  const thead = tableWrapperRef.current?.querySelector('.ant-table-thead')
  const headerHeight = thead ? Math.ceil(thead.getBoundingClientRect().height) : 42
  setTableScrollY(Math.max(220, wrapperHeight - headerHeight - 2))  // 2px 安全边距
}

// 观测 wrapper（flex:1 自动传导所有父元素变化）
const observer = new ResizeObserver(updateHeight)
observer.observe(tableWrapperRef.current)

// mount 后再次测量（首次渲染时 thead 可能还不存在）
requestAnimationFrame(updateHeight)
```

**原则**：让 CSS flex 做布局计算，代码只做最终可用区域的测量，永远不要手动累加各 toolbar 高度。

---

### ❌ 可选 Prop 接线遗漏：组件有能力但调用方从未传递

**症状**：`big_table`（180,001 行）打开后只显示 1000 行，滚动到底不会加载更多。

**根因**：`EditableDataTable` 组件声明了 `onLoadMore?: () => void` / `hasMore?: boolean` / `loadingMore?: boolean` 三个可选 prop，
组件内部实现了完整的触底检测和加载逻辑（`maybeLoadMore`），
但 `workbench/index.tsx` 渲染 `EditableDataTable` 时**从未传递这三个 prop**，
导致 `maybeLoadMore` 内 `!onLoadMore` 为 true，永远不触发加载。

```tsx
// 错误：EditableDataTable 有 onLoadMore 能力，但调用方没传
<EditableDataTable
  dataSource={t.previewRows}
  onRefresh={...}
  // ❌ 缺少 hasMore、onLoadMore、loadingMore
/>
```

**正确做法**：在 Tab State 中新增分页状态，在渲染时传入完整的分页 props：

```tsx
// 正确：显式传递分页相关 props
<EditableDataTable
  dataSource={t.previewRows}
  hasMore={t.hasMoreRows}
  loadingMore={t.loadingMoreRows}
  onLoadMore={async () => {
    const rows = await metadataApi.previewRows(
      connId, db, table,
      { limit: PREVIEW_PAGE_SIZE, offset: t.previewRows.length }
    )
    updateTabState(tabKey, (prev) => ({
      previewRows: [...prev.previewRows, ...rows],
      hasMoreRows: rows.length >= PREVIEW_PAGE_SIZE,
      loadingMoreRows: false,
    }))
  }}
  onRefresh={...}
/>
```

**推广规则**：当组件的可选 prop 控制核心功能（分页、权限、验证等），
调用方**必须显式决定是否传入**（传值 or 注释说明为什么不传）。
新增可选 prop 时，应在 PR 中附"调用方接线检查"清单。
