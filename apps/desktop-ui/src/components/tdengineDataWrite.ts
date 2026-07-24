import type { TableKind, TimeSeriesWriteRow, TimeSeriesWriteTargetKind } from '@/types'

export function parseTimeSeriesPaste(text: string): string[][] {
  const normalized = text.replace(/\r\n?/g, '\n').replace(/\n$/, '')
  return normalized ? normalized.split('\n').map(line => line.split('\t')) : []
}

export function explicitTimeSeriesNow(): string {
  const date = new Date()
  const pad = (value: number, size = 2) => String(value).padStart(size, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`
}

export function timeSeriesWriteTargetKind(tableKind: TableKind, mode: 'existing' | 'new'): TimeSeriesWriteTargetKind {
  if (tableKind === 'BASIC_TABLE') return 'BASIC_TABLE'
  if (tableKind === 'CHILD_TABLE') return 'EXISTING_CHILD_TABLE'
  return mode === 'new' ? 'NEW_CHILD_TABLE' : 'EXISTING_CHILD_TABLE'
}

export function validateExplicitTimeSeriesRows(rows: readonly TimeSeriesWriteRow[], timestampName: string): void {
  rows.forEach((row, rowIndex) => row.cells.forEach(cell => {
    if (!cell.isNull && cell.value == null) throw new Error(`第 ${rowIndex + 1} 行字段 ${cell.name} 未填写值或 NULL`)
    if (cell.name === timestampName && (cell.isNull || !cell.value?.trim())) throw new Error(`第 ${rowIndex + 1} 行时间戳必须显式填写`)
  }))
}
