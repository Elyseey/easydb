import { describe, expect, it } from 'vitest'
import { parseJsonExplain } from './explainParser'

describe('parseJsonExplain', () => {
  it('parses nested loops and dependent subquery amplification', () => {
    const model = parseJsonExplain(JSON.stringify({
      query_block: {
        select_id: 1,
        cost_info: { query_cost: '42.5' },
        nested_loop: [
          {
            table: {
              table_name: 'orders',
              access_type: 'ALL',
              rows_examined_per_scan: 5000,
              rows_produced_per_join: 3,
              filtered: '10.0',
              possible_keys: ['idx_status', 123],
              used_columns: ['id', 'status', null],
            },
          },
        ],
        select_list_subqueries: [
          {
            dependent: true,
            cacheable: false,
            query_block: {
              select_id: 2,
              table: {
                table_name: 'order_items',
                access_type: 'REF',
                rows_examined_per_scan: 2,
                rows_produced_per_join: 2,
                key: 'idx_order_id',
              },
            },
          },
        ],
      },
    }))

    expect(model).not.toBeNull()
    expect(model?.summary.estimatedCost).toBe(42.5)
    expect(model?.summary.hasDependentSubquery).toBe(true)
    expect(model?.summary.maxAmplification).toBe(3)
    expect(model?.roots[0].children[0]).toMatchObject({
      label: 'orders',
      possibleKeys: ['idx_status'],
      usedColumns: ['id', 'status'],
    })
    expect(model?.roots[0].children[1]).toMatchObject({
      nodeType: 'dependent_subquery',
      execTimes: 3,
      amplificationFactor: 3,
    })
  })

  it('returns null for malformed or unsupported JSON shapes', () => {
    expect(parseJsonExplain('{invalid')).toBeNull()
    expect(parseJsonExplain('{}')).toBeNull()
    expect(parseJsonExplain('{"query_block":[]}')).toBeNull()
  })
})
