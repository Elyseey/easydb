import { expect, test } from '@playwright/test'

const apiOk = (data: unknown) => ({
  success: true,
  data,
})

const previewRows = Array.from({ length: 120 }, (_, index) => ({
  id: index + 1,
  name: index === 0 ? 'Alice' : `User ${index + 1}`,
}))

test('workbench can open a second query tab after selecting database without Monaco lifecycle errors', async ({ page }) => {
  const browserErrors: string[] = []
  const monacoErrorPattern = /InstantiationService has been disposed|Cannot read properties of undefined \(reading 'domNode'\)|monaco-editor/

  await page.addInitScript(() => {
    window.localStorage.setItem('easydb_auto_check_update', 'false')
  })

  page.on('console', (message) => {
    if (message.type() === 'error') {
      browserErrors.push(message.text())
      if (monacoErrorPattern.test(message.text())) {
        console.error(`[browser console] ${message.text()}`)
      }
    }
  })
  page.on('pageerror', (error) => {
    browserErrors.push(error.message)
    if (monacoErrorPattern.test(error.message)) {
      console.error(`[page error] ${error.message}`)
    }
  })

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname

    if (path === '/api/connection/list') {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(apiOk([
          {
            id: 'e2e-conn',
            name: 'E2E MySQL',
            dbType: 'mysql',
            host: '127.0.0.1',
            port: 3306,
            username: 'root',
            password: '',
            status: 'connected',
          },
        ])),
      })
      return
    }

    if (path === '/api/groups/list') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(apiOk([])) })
      return
    }

    if (path === '/api/scripts/list') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(apiOk([])) })
      return
    }

    if (path === '/api/metadata/e2e-conn/databases') {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(apiOk([
          { name: 'easydb_test' },
          { name: 'mysql' },
        ])),
      })
      return
    }

    if (path === '/api/metadata/e2e-conn/easydb_test/objects') {
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify(apiOk([])) })
      return
    }

    if (path === '/api/sql/query-preview') {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify(apiOk({
          type: 'query',
          columns: ['id', 'name'],
          rows: previewRows,
          preview: true,
          hasMore: false,
          loadedRows: previewRows.length,
          pageSize: 200,
          offset: 0,
          duration: 1,
          sql: 'select 1 as id, "Alice" as name',
          executedAt: new Date().toISOString(),
        })),
      })
      return
    }

    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(apiOk({})) })
  })

  await page.goto('/connection')
  await expect(page.getByText('E2E MySQL')).toBeVisible()
  await page.getByText('E2E MySQL').dblclick()
  await expect(page).toHaveURL(/\/workbench/)
  await expect(page.locator('#root')).not.toBeEmpty()

  await page.getByRole('button', { name: /新建查询/ }).click()
  await page.getByText('请在上方选择我们要查询的数据库').waitFor()

  await page.locator('.ant-select').nth(1).click()
  await page.getByTitle('easydb_test').click()
  await page.locator('.monaco-editor').waitFor({ state: 'visible' })

  const editorSurface = page.locator('.monaco-editor').first()
  await editorSurface.click({ position: { x: 80, y: 24 } })
  await page.keyboard.insertText('selw')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Tab')
  await page.keyboard.insertText('users')
  await page.keyboard.press('Tab')
  await page.keyboard.insertText('enabled = 1')
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return (state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? '').replace(/\r\n/g, '\n')
  })).toBe('SELECT\n  *\nFROM\n  users\nWHERE\n  enabled = 1;')

  await page.evaluate(async () => {
    const { useAppSettingsStore } = await import('/src/stores/appSettingsStore.ts')
    useAppSettingsStore.getState().setSqlTemplatesEnabled(false)
    await new Promise<void>((resolve) => {
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve()))
    })
  })
  await editorSurface.click({ position: { x: 80, y: 24 } })
  await page.keyboard.press('Control+a')
  await page.keyboard.insertText('selw')
  await page.keyboard.press('Tab')
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return (state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? '').trim()
  })).toBe('selw')

  await page.evaluate(async () => {
    const { useAppSettingsStore } = await import('/src/stores/appSettingsStore.ts')
    useAppSettingsStore.getState().setSqlTemplatesEnabled(true)
    await new Promise<void>((resolve) => {
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => resolve()))
    })
  })
  await editorSurface.click({ position: { x: 80, y: 24 } })
  await page.keyboard.press('Control+a')
  await page.keyboard.insertText('select   id, name\nfrom   users\nwhere enabled = 1')
  await page.getByRole('button', { name: /格式/ }).click()
  await page.getByText('美化 SQL', { exact: true }).last().click()
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? ''
  })).toContain('\nFROM')

  await page.getByRole('button', { name: /格式/ }).click()
  await page.getByText('压缩 SQL', { exact: true }).last().click()
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? ''
  })).toBe('SELECT id, name FROM users WHERE enabled = 1')

  await editorSurface.click({ position: { x: 80, y: 24 } })
  await page.keyboard.press('Control+z')
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? ''
  })).toContain('\nFROM')

  await editorSurface.click({ position: { x: 80, y: 24 } })
  await page.keyboard.press('Control+a')
  await page.keyboard.insertText('select   1;\nselect   2;')
  await page.keyboard.press('Control+Home')
  await page.keyboard.press('Shift+End')
  await page.getByRole('button', { name: /格式/ }).click()
  await page.getByText('美化 SQL', { exact: true }).last().click()
  await expect.poll(async () => page.evaluate(async () => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    return (state.tabs.find((tab) => tab.key === state.activeTabKey)?.sql ?? '').replace(/\r\n/g, '\n')
  })).toBe('SELECT\n  1;\nselect   2;')

  await page.evaluate(async ({ rows }) => {
    const { useSqlEditorStore } = await import('/src/stores/sqlEditorStore.ts')
    const state = useSqlEditorStore.getState()
    const activeKey = state.activeTabKey
    const result = {
      type: 'query',
      columns: ['id', 'name'],
      rows,
      preview: true,
      hasMore: false,
      loadedRows: rows.length,
      pageSize: 200,
      offset: 0,
      duration: 1,
      sql: 'select 1 as id, "Alice" as name',
      executedAt: new Date().toISOString(),
      connectionId: 'e2e-conn',
      database: 'easydb_test',
    }
    state.updateTab(activeKey, {
      sql: 'select 1 as id, "Alice" as name',
      currentBatch: [result],
      results: [result],
      resultTab: 'result-0',
    })
  }, { rows: previewRows })
  await expect(page.getByRole('button', { name: 'Alice' })).toBeVisible()
  const resultBody = page.locator('.sql-spreadsheet-grid .ant-table-body, .sql-spreadsheet-grid .ant-table-tbody-virtual-holder').first()
  await resultBody.evaluate((element) => {
    element.scrollTop = 900
    element.dispatchEvent(new Event('scroll', { bubbles: true }))
  })
  await expect(page.getByRole('button', { name: 'User 30' })).toBeVisible()

  await page.getByRole('button', { name: /新建查询/ }).click()
  await expect(page.getByText('查询 2')).toBeVisible()
  await page.getByRole('tab', { name: /查询 1/ }).click()
  await expect(page.getByRole('button', { name: 'User 30' })).toBeVisible()

  const matchingErrors = browserErrors.filter((message) => monacoErrorPattern.test(message))
  expect(matchingErrors).toEqual([])

  await page.goto('/settings')
  await page.getByRole('tab', { name: /代码编辑器/ }).click()
  const templateSwitch = page.getByRole('switch', { name: '启用常用 SQL 模板' })
  await expect(templateSwitch).toBeChecked()
  await templateSwitch.click()
  await expect(templateSwitch).not.toBeChecked()

  await page.reload()
  await page.getByRole('tab', { name: /代码编辑器/ }).click()
  await expect(page.getByRole('switch', { name: '启用常用 SQL 模板' })).not.toBeChecked()
})
