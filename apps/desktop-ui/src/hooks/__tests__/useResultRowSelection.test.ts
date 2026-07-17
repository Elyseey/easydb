import { describe, expect, it } from 'vitest'
import { rowsForSelection, selectionForContextMenu } from '../useResultRowSelection'

describe('result row selection rules', () => {
  it('keeps a multi-selection when context-clicking a selected row', () => {
    expect(selectionForContextMenu([1, 3, 4], 3)).toEqual([1, 3, 4])
  })

  it('replaces the selection when context-clicking an unselected row', () => {
    expect(selectionForContextMenu([1, 3, 4], 2)).toEqual([2])
  })

  it('returns selected rows in grid order and ignores invalid or duplicate indices', () => {
    const rows = [{ id: 1 }, { id: 2 }, { id: 3 }]
    expect(rowsForSelection(rows, [2, 0, 2, 9])).toEqual([{ id: 1 }, { id: 3 }])
  })
})
