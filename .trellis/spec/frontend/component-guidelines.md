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

## Ant Design Dropdown + Tooltip — Keep Overlays Mutually Exclusive

Do not rely on Ant Design's default hover trigger when a `Dropdown` trigger contains a `Tooltip`. Both overlays can remain open at the same time, and Tooltip's default popup layer is above Dropdown, so the help text can cover the first menu item and make it impossible to click.

Use click to open the menu and hide the Tooltip while the menu is open:

```tsx
const [menuOpen, setMenuOpen] = useState(false)

<Dropdown
  trigger={['click']}
  onOpenChange={setMenuOpen}
  menu={{ items }}
>
  <Tooltip
    title="导出当前查询结果"
    open={menuOpen ? false : undefined}
  >
    <Button icon={<DownloadOutlined />} />
  </Tooltip>
</Dropdown>
```

The interaction contract is:

- Hovering the closed trigger may show the Tooltip.
- Clicking the trigger must close the Tooltip and show only the Dropdown.
- Every visible menu item must remain clickable.
- Closing the menu restores normal uncontrolled Tooltip behavior.

Add a component regression test that opens the Tooltip first, clicks the trigger, verifies `ant-tooltip-open` is removed while the Dropdown is visible, and clicks a menu item.

---

## Ant Design Menu Submenus — Restore the Portaled Popup Surface

The global glass override intentionally makes `.ant-menu` transparent so navigation menus can inherit their panel surface. This assumption does not hold for popup submenus: Ant Design portals them outside the custom context-menu container, so a nested menu can render directly over a result grid with the grid text visible through it.

Any standalone custom `<Menu>` that uses nested `children` must give the submenu popup its own surface through the Ant Design semantic `classNames.popup` / `styles.popup` API:

```tsx
<Menu
  items={items}
  classNames={{ popup: { root: 'result-context-menu-popup' } }}
  styles={{
    popup: {
      root: {
        zIndex: token.zIndexPopupBase + 2,
        background: 'var(--glass-popup)',
        border: '1px solid var(--glass-border)',
        boxShadow: 'var(--glass-shadow-lg)',
        backdropFilter: 'var(--glass-blur-heavy)',
      },
    },
  }}
/>
```

Do not fix this by removing the global transparent menu rule or by applying a broad `.ant-menu` popup override; both approaches can change sidebar and inline navigation surfaces. Keep the override scoped to the popup owned by the custom menu.

Add a regression test that opens a real nested submenu and verifies the portaled popup has an opaque `--glass-popup` background and a z-index above the root context menu.

---

## SQL Result Grids — Row Selection and Scoped Export

`SqlResultPanel` and `EditableDataTable` must expose the same selection/export contract even though one is read-only and the other supports editing.

### Signatures

```ts
useResultRowSelection(rowCount: number): {
  selectedRowKeys: number[]
  setSelectedRowKeys(keys: readonly React.Key[]): void
  clearSelection(): void
  selectForContextMenu(rowIndex: number): void
}

confirmDataExport({
  columns,
  rows: selectedRows,
  scope: 'selected',
  loadedOnly: result.preview && result.hasMore,
})
```

### Contracts

- Keep selection as lightweight row indices; do not copy wide row objects into selection state.
- Export selected rows in current grid order, not checkbox click order.
- Right-clicking an unselected row replaces the selection with that row.
- Right-clicking a selected row preserves the full multi-selection.
- Toolbar actions must distinguish `导出全部` from `导出所选 N 行`.
- New query execution clears selection by remounting the result grid with `key={result.executedAt}`.
- Appending more rows to the same result preserves selection because `executedAt` is unchanged.
- Selected export never auto-loads missing rows; `loadedOnly` must disclose that selection is limited to fetched rows.
- SQL INSERT actions are available only when the target table and `dbType` are known.
- Editable grids export the last queried data and explicitly disclose that unsaved changes are excluded.

### Right-click Matrix

| Current selection | Right-click target | Effective scope |
|---|---|---|
| Empty | Any row | That row |
| Rows 1, 3 | Row 3 | Rows 1, 3 |
| Rows 1, 3 | Row 2 | Row 2 only |

### Tests Required

- Pure selection test: preserve selected target, replace unselected target, remove invalid keys, and sort keys by grid order.
- Read-only grid test: checkbox-select multiple rows and assert `confirmDataExport.rows` contains only those rows.
- Editable grid test: selected export coexists with full export and passes `scope: 'selected'`.
- Context-menu test: right-clicking an unselected row shows `导出此行` and updates the visible selected count.

### Wrong vs Correct

```tsx
// ❌ Wrong: each result grid invents its own scope rules and exports every row.
onContextMenu={() => confirmDataExport({ rows: result.rows, format: 'csv' })}

// ✅ Correct: both grids share selection semantics and pass only the selected rows.
const { selectedRowKeys, selectForContextMenu } = useResultRowSelection(rows.length)
const selectedRows = rowsForSelection(rows, selectedRowKeys)
onContextMenu={(rowIndex) => selectForContextMenu(rowIndex)}
```

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

## Workbench Query Tab Rename — Defer Focus and Own the Full Tab Surface

Inline query-tab rename must account for the event order of Ant Design Tabs and the desktop WebView.

- Start rename from double-click anywhere on the SQL query tab root or from the tab context menu. Exclude the close button and the active rename input.
- Do not use `autoFocus` on an input mounted by a double-click handler. The remaining tab click/focus work can immediately blur the input and close rename mode. Mount the input first, then focus and select it in `requestAnimationFrame`.
- Apply `userSelect: 'none'` to the non-editing query label so the browser does not briefly select the label text on the second click.
- Own double-click and native WebView context-menu handling at the Tabs root, not only on the inner label. Resolve the clicked tab with `closest('.ant-tabs-tab')` and its `data-node-key`, so the icon, text, tab padding, close icon, and rename input follow one interaction boundary.
- Enter commits, Escape cancels, and blur commits. Empty normalized names keep the previous title.
- Keep `WorkbenchTab.label` and the matching `EditorTab.title` synchronized when a query is renamed.

Required regression coverage:

- Title normalization rejects whitespace-only names and enforces the length limit.
- Context-menu target resolution returns the tab key for nested elements such as the close icon, not only the text label.

## Table Designer Rename — Save Structure Against the Original Identity

### 1. Scope / Trigger

Apply this contract when an existing table's name can be edited in `TableDesigner` and saved together with column, index, primary-key, or comment changes.

### 2. Signatures

```ts
interface TableDesignerSaveResult {
  tableName: string
  previousTableName?: string
  renamed: boolean
}

metadataApi.renameTable(connectionId, database, oldName, newName)
```

### 3. Contracts

- Keep the original catalog table name separately from the editable input value.
- Generate and execute structural DDL against the original table name. Call the generic `renameTable` metadata API only after every structural statement succeeds.
- A rename-only save executes no empty SQL batch and calls `renameTable` directly.
- `onSuccess` fires only after the complete requested save succeeds and carries both names when `renamed` is true.
- The workbench must pass the result through `applyRenamedTableState()` so the resource tree, table tab key/title, standalone designer state, selection, active table, and subsequent detail loads all use the new name.
- MySQL and Dameng keep their rename syntax inside their metadata adapters. The frontend may show a dialect-specific preview but must not replace the generic mutation API with raw rename SQL.
- DDL cannot be treated as an atomic transaction. If one or more structural statements succeeded before a later failure, show a partial-success warning and instruct the user to reopen the designer to confirm the real structure.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Trimmed table name is empty | Reject before SQL/API execution with `请输入表名` |
| Name unchanged and no structural diff | Show `无变更`; execute nothing |
| Name changed and no structural diff | Call only `renameTable(oldName, newName)` |
| Structural diff and name changed | Execute all structural statements on `oldName`, then rename |
| Structural statement fails before any success | Show the normal modification error; do not rename |
| Structural statement fails after earlier successes | Show `部分修改已生效` with completed/total counts; do not rename |
| Rename fails after structural success | State that structure changes are already applied and rename failed |

### 5. Good / Base / Bad Cases

- Good: edit `orders`, add a column, and rename to `orders_archive`; the column DDL targets `orders`, then the adapter renames it, and the active tab becomes `orders_archive`.
- Base: edit only a column or comment without changing the name; existing table-design behavior remains unchanged.
- Bad: generate `ALTER TABLE orders_archive ...` before `orders_archive` exists, or rename successfully while the workbench still queries the old tab identity.

### 6. Tests Required

- Rename-only component test: input is enabled, no structural SQL executes, and the callback returns old/new names.
- MySQL ordering test: structural SQL uses the original backtick-quoted table and completes before `renameTable`.
- Dameng ordering test: structural SQL uses the original double-quoted table and completes before `renameTable`.
- Failure regression: partial structural success produces an explicit partial-success warning and never attempts rename.
- Frontend type-check/build after changing the save-result callback contract.

### 7. Wrong vs Correct

```ts
// ❌ Wrong: the editable value becomes the structural DDL target immediately.
const alterSql = generateAlterSql(tableName)
await metadataApi.renameTable(connectionId, database, oldName, tableName)

// ✅ Correct: structural identity stays stable until the final rename step.
const alterSql = generateAlterSql(originalTableName)
for (const statement of splitStatements(alterSql)) {
  await sqlApi.execute(connectionId, database, statement)
}
await metadataApi.renameTable(connectionId, database, originalTableName, tableName.trim())
```

## Query Object Detail Jump — Carry Connection Identity

### 1. Scope / Trigger

Apply this contract whenever a SQL query pane opens a table, view, procedure, function, or trigger in the workbench. A query pane can switch connections after its outer workbench tab was created, so the outer tab is not an authoritative source for the current connection.

### 2. Signatures

```ts
type OpenObjectDetailRequest = {
  connectionId: string
  database: string
  name: string
  objectType: 'table' | 'view' | 'procedure' | 'function' | 'trigger'
}

type QueryContextChange = {
  queryId: string
  connectionId?: string
  database?: string
}
```

### 3. Contracts

- `connectionId` must come from the same `EditorTab` used to load the object's metadata.
- The object-detail callback must carry `connectionId`; the workbench must not infer it from the active outer tab.
- When an inner query pane changes connection or database, synchronize the matching outer `SqlQueryTabState` by `queryId`.
- If the target connection is not yet present in the workbench tree, add that existing connection before opening the object tab. Do not create or replace a database session solely for the jump.

### 4. Validation & Error Matrix

| Condition | Behavior |
|---|---|
| Request has no connection ID | Do not create a jump request |
| Object type is not supported | Do not open a workbench object tab |
| Connection ID is absent from the canonical connection store | Show a clear warning and stop |
| Connection is valid but not loaded in the workbench | Add it to the workbench, then open the object |

### 5. Good / Base / Bad Cases

- Good: A query created on Dameng switches to MySQL, then opens `emission.users`; the object tab uses the current MySQL connection.
- Base: A query never switches connections; object navigation behaves exactly as before.
- Bad: MySQL `emission.users` is opened with the query tab's original Dameng connection and fails with an invalid Schema error.

### 6. Tests Required

- Unit test the request builder and assert it preserves `connectionId`, database, object name, and type.
- Cover unsupported object types returning no request.
- For component/E2E coverage, switch a query between two database types and assert the object-detail request uses the selected connection in both directions.

### 7. Wrong vs Correct

```ts
// Wrong: outer workbench state can be stale after the query pane switches connections.
openObject(outerTab.connectionId, request.database, request.name)

// Correct: identity travels with the object request produced by the current EditorTab.
onOpenObjectDetail({
  connectionId: currentEditorTab.connectionId,
  database,
  name,
  objectType,
})
```

---

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

### SQL Editor Text Transformations — Dialect-Aware and Undoable

#### 1. Scope / Trigger

Apply this contract whenever a Monaco action formats, compacts, normalizes, or otherwise replaces user SQL text.

#### 2. Signatures

```ts
beautifySql(sql: string, dbType: DbType): Promise<string>
compactSql(sql: string, dbType: DbType): string
```

#### 3. Contracts

- Resolve formatter/tokenizer behavior from the active connection `dbType`; never use MySQL rules as the generic fallback.
- If Monaco has a non-empty selection, replace only that range; otherwise replace the full model range.
- Apply the result with `editor.executeEdits(...)` between undo stops so one undo restores the original SQL.
- For async transforms, capture `model.getVersionId()` before work starts and refuse to apply if the model or version changed while awaiting the result.
- Keep SQL local unless the feature explicitly declares a server/AI boundary.
- A compactor may collapse whitespace only in normal SQL lexical regions. It must preserve quoted strings/identifiers, comments, PostgreSQL dollar-quoted blocks, and Oracle/Dameng `q'...'` text.

#### 4. Validation & Error Matrix

- Empty selection/document -> no-op.
- Missing active connection or `dbType` -> keep text unchanged and show a clear error.
- Unclosed quote/comment/text block -> reject the transform and keep text unchanged.
- Formatter parse error or unsupported dialect syntax -> keep text unchanged and surface the parser message.
- Model version changed during an async transform -> discard the stale result and ask the user to retry.

#### 5. Good / Base / Bad Cases

- Good: PostgreSQL `payload #>> '{user,name}'` remains an operator expression while external whitespace is compacted.
- Base: MySQL `SELECT  1 # note\n FROM dual` keeps `# note` as a line comment and retains the terminating newline.
- Bad: A generic regex treats every `#` as a MySQL comment or runs `sql.replace(/\s+/g, ' ')`, corrupting PostgreSQL operators and whitespace inside literals.

#### 6. Tests Required

- Unit tests for every supported `DbType` formatter mapping.
- Lexer tests for single/double/backtick/bracket quotes, line/block comments, PostgreSQL dollar quotes, Oracle/Dameng q-quotes, and malformed input.
- Regression test distinguishing MySQL `#` comments from PostgreSQL `#>` / `#>>` operators.
- Monaco E2E coverage for selection-first behavior, full-document fallback, direct replacement, and one-step undo.

#### 7. Wrong vs Correct

```ts
// ❌ Wrong: destroys literal content and confuses dialect-specific tokens.
const compact = sql.replace(/\s+/g, ' ').trim()

// ✅ Correct: pass dbType into a lexer that collapses whitespace only in normal SQL regions.
const compact = compactSql(sql, activeConnection.dbType)

editor.pushUndoStop()
editor.executeEdits('easydb-sql-compact', [{ range, text: compact }])
editor.pushUndoStop()
```

### SQL Editor Common Templates — Immediate, Optional, and Dialect-Aware

#### 1. Scope / Trigger

Apply this contract whenever Monaco expands a SQL abbreviation or offers a built-in SQL snippet.

#### 2. Signatures

```ts
getSqlTemplates(dbType: DbType | undefined, enabled: boolean): SqlTemplate[]

registerSqlCompletionProvider(monaco, connectionId, database, {
  dbType,
  templatesEnabled,
})
```

#### 3. Contracts

- The `常用 SQL 模板` setting defaults to enabled, persists locally, and takes effect without reloading the page.
- Disabling templates removes only template suggestions; keyword, table, and column completion must continue working.
- Filter templates by the active connection `dbType`. A generic template must not contain database-specific quoting or syntax.
- Enable Monaco `tabCompletion: 'on'` while templates are enabled and set it to `'off'` while disabled. Monaco 0.55 does not reliably expand custom completion-provider snippets with `'onlySnippets'`.
- When the current token exactly matches a template abbreviation, return that template immediately before any asynchronous metadata request. This prevents Tab from inserting indentation or selecting a later suggestion while table metadata is loading.
- Offer templates only at a statement boundary: line start or after whitespace, `;`, or `(`. Do not offer them in qualified identifiers such as `users.sel`.
- Use Monaco snippet insertion rules and numbered tab stops. Update and delete templates must include an explicit `WHERE` placeholder.

#### 4. Validation & Error Matrix

- Templates disabled -> return no snippets; keep normal completion active.
- Missing `dbType` -> return no snippets until the connection dialect is known.
- Exact abbreviation -> return only the matching snippet synchronously.
- Qualified/member context -> return no template suggestions.
- Write template without an explicit `WHERE` placeholder -> invalid template; registry tests must fail.

#### 5. Good / Base / Bad Cases

- Good: typing `selw` and pressing Tab immediately expands a `SELECT ... FROM ... WHERE ...` snippet and focuses the table placeholder.
- Base: disabling templates leaves SQL keyword and metadata completion unchanged.
- Bad: exact abbreviation completion waits for table metadata, or relies on `tabCompletion: 'onlySnippets'` for a custom provider.

#### 6. Tests Required

- Unit tests for template registry uniqueness, dialect filtering, write-template safety, and enabled/disabled behavior.
- Provider tests for exact-match fast path, statement-boundary filtering, snippet insertion flags, and preservation of normal completion.
- Store tests for default-on behavior and local persistence.
- Monaco E2E coverage for Tab expansion, tab-stop navigation, immediate disabling, and settings persistence after reload.

#### 7. Wrong vs Correct

```ts
// ❌ Wrong: Tab completion races with an asynchronous metadata request.
const tables = await fetchTables(connectionId, database)
return buildSuggestions(tables, getSqlTemplates(dbType, enabled))

// ✅ Correct: exact abbreviations never wait for metadata.
const exactTemplate = templates.find((template) => template.abbreviation === word)
if (exactTemplate) return { suggestions: [toCompletionItem(exactTemplate)] }

const tables = await fetchTables(connectionId, database)
return buildSuggestions(tables, templates)
```

### Database Namespace Management — Split Capabilities and Guard the API

#### 1. Scope / Trigger

Apply this contract whenever a workbench action creates, deletes, or alters a database/schema namespace or its charset/collation.

#### 2. Signatures

```ts
interface MetadataCapability {
  schemaCreation: boolean
  schemaManagement: boolean
  schemaAlterCharset: boolean
}
```

```kotlin
data class DatabaseCapabilities(
    val supportsAlterDatabaseCharset: Boolean = false,
)
```

#### 3. Contracts

- Namespace creation, destructive deletion, and runtime charset alteration are separate capabilities; one boolean must not expose all three actions.
- MySQL may set `schemaAlterCharset = true` and `supportsAlterDatabaseCharset = true`.
- Dameng keeps Schema creation/deletion enabled but charset alteration disabled. Official DM8 documentation states `CHARSET/UNICODE_FLAG` cannot be changed after database creation.
- The frontend hides unsupported actions, and the backend independently rejects direct API calls with `UNSUPPORTED_DB_FEATURE` before parsing or generating dialect SQL.
- Dialect support decisions must cite the checked official document in the task PRD or research notes.

#### 4. Validation & Error Matrix

- Capability false in UI -> action is absent, while other Schema actions remain available.
- Capability false in API -> `UNSUPPORTED_DB_FEATURE`; execute no SQL.
- Missing connection/session -> existing `NOT_CONNECTED` behavior.
- MySQL with valid charset/collation -> preserve the existing `ALTER DATABASE` behavior.

#### 5. Good / Base / Bad Cases

- Good: Dameng shows `新建 Schema` and `删除 Schema`, but no charset editor.
- Base: MySQL continues to show create, edit charset/collation, and delete actions.
- Bad: `schemaManagement = true` exposes a MySQL `ALTER DATABASE ... CHARACTER SET` action for every database type.

#### 6. Tests Required

- Frontend capability test: MySQL alteration true; Dameng creation/deletion true and alteration false.
- Backend adapter test: Dameng `supportsAlterDatabaseCharset` is false.
- Route regression test or compile-checked guard: unsupported adapters return before SQL construction/execution.
- Security scan for database tests: no embedded credentials, decrypted-secret logging, or real network addresses.
- Default unit tests must not open JDBC connections or mutate any database.

#### 7. Wrong vs Correct

```tsx
// ❌ Wrong: deletion support accidentally exposes charset editing.
if (cap.metadata.schemaManagement) showEditDatabase()

// ✅ Correct: each action checks its own capability.
if (cap.metadata.schemaAlterCharset) showEditDatabase()
if (cap.metadata.schemaManagement) showDropSchema()
```

```kotlin
// ❌ Wrong: build MySQL SQL before checking the active adapter.
stmt.execute("ALTER DATABASE ... CHARACTER SET ...")

// ✅ Correct: reject unsupported database types before SQL construction.
if (!adapter.capabilities().supportsAlterDatabaseCharset) {
    return call.fail("UNSUPPORTED_DB_FEATURE", "当前数据库不支持运行时修改字符集")
}
```

### Database Task Navigation — One Capability Source and Exact Pairs

#### 1. Scope / Trigger

Apply this contract whenever migration, synchronization, structure comparison, or diagnostic features are exposed through navigation, command palette actions, or connection selectors.

#### 2. Signatures

```ts
supportsDatabaseNavigation(dbType, capability): boolean
supportsDatabaseTaskPair(feature, sourceDbType, targetDbType): boolean
supportsDatabaseTaskRole(feature, dbType, role): boolean
```

#### 3. Contracts

- Sidebar items and command-palette navigation must use the same database capability decision.
- Pair features must use the shared frontend pair registry; do not copy supported-pair arrays into individual pages.
- Connection selectors filter by source/target role before selection, then selection handlers validate the exact pair again.
- Coarse capabilities control whether a feature is discoverable; exact `(sourceDbType, targetDbType)` support controls whether it can run.
- Changing the active database type must unregister stale commands and register only commands allowed by the new capability set.

#### 4. Validation & Error Matrix

- Unsupported role -> omit the connection option and reject programmatic selection with a clear message.
- Individually supported roles but unsupported pair -> reject before opening a connection or loading metadata.
- Dameng context -> migration, sync, and structure comparison are discoverable; sync/compare selectors allow only the exact Dameng-to-Dameng pair, while tracker and slow-query navigation remain absent.
- MySQL context -> preserve all currently supported navigation.

#### 5. Good / Base / Bad Cases

- Good: the shared registry allows all registered migration directions, allows Dameng in both sync/compare roles only for Dameng-to-Dameng, and rejects MySQL-to-Dameng sync/compare.
- Base: MySQL to MySQL remains available for migration, sync, and structure comparison.
- Bad: the sidebar hides sync for Dameng while the command palette or sync connection selector still exposes it.

#### 6. Tests Required

- Unit tests for every registered pair and role, including reverse-direction rejection.
- Navigation tests for MySQL and Dameng capability outcomes.
- Frontend build/type-check after changing pair or navigation capability types.
- When adding a new pair, update the shared registry tests and verify the backend pair registry supports the same direction.

#### 7. Wrong vs Correct

```ts
// ❌ Wrong: each entry point invents its own support rules.
const options = connections
registerCommand({ id: 'nav-sync', action: openSync })

// ✅ Correct: discovery and exact execution share capability sources.
const options = connections.filter((connection) =>
  supportsDatabaseTaskRole('sync', connection.dbType, 'source')
)
const commands = items.filter((item) =>
  supportsDatabaseNavigation(activeDbType, item.capability)
)
if (!supportsDatabaseTaskPair('sync', source.dbType, target.dbType)) return
```

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

---

## Connection Database Select — Guard Async Identity and Hidden-Pane Rendering

SQL query panes can stay mounted while hidden. A connection-scoped database selector therefore has two independent stale-state risks:

- A slower request for connection A can resolve after the user switches to connection B and overwrite B's database list.
- Ant Design's virtualized Select popup can retain a stale scroll range after its pane was hidden, producing an empty popup with a scrollbar until the user scrolls.

Required pattern:

- Keep database loading in the shared `useConnectionDatabases(connectionId)` hook.
- Associate every result with its connection/request identity and ignore results after effect cleanup.
- Derive `loading`, `databases`, and `error` only from the current request identity so a connection switch clears stale options immediately.
- Render SQL database selectors through the shared `ConnectionDatabaseSelect` component.
- Reset the Select instance by `connectionId` and keep `virtual={false}` for this small metadata list.
- Show loading, empty, and retry states explicitly; do not turn every request failure into a silent empty array.

Required regression coverage:

- Resolve connection B before a pending connection A request, then resolve A and verify B remains visible.
- Verify a failed request exposes a retry action and retry can load the list.
- Open the Select popup and verify the current database options render.

---

## Desktop External Links — Use the Tauri System-Browser Bridge

EasyDB runs inside a Tauri WebView. A successful `window.open(...)` call in jsdom or a normal
browser does not prove that a packaged desktop app will open a URL. WebViews may ignore or
block the new-window request.

Required pattern:

- Product code must call the shared `openExternalUrl(url)` utility for GitHub, downloads,
  documentation, and every other external URL.
- In Tauri, the utility must delegate to the registered `@tauri-apps/plugin-shell` `open()` API,
  which opens the system default application.
- Browser/Vite development may fall back to `window.open(url, '_blank', 'noopener,noreferrer')`
  inside the shared utility only.
- Keep the Rust plugin registration, frontend plugin package, and `shell:allow-open` capability
  synchronized. Having only one or two of these three pieces is not a working integration.
- Do not add direct `window.open` calls to React components.

Required regression coverage:

- Desktop-path unit test: with `isTauri() === true`, assert the shell plugin receives the URL.
- Browser-path unit test: assert the safe `window.open` fallback is used and the shell plugin is not.
- Component tests should verify that the visible action routes through the shared external-link path.

Root-cause lesson: DOM-level tests can validate a click handler while completely missing the
native bridge needed for the packaged application. For desktop integrations, test the platform
branch rather than only the browser side effect.
