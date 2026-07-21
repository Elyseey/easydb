import { describe, expect, it } from 'vitest'
import type { SavedScript, TableInfo, TimeSeriesChildTable } from '@/types'
import {
  filterSavedScriptsForTree,
  filterTdengineTreeObjects,
  filterTreeItemsByName,
} from '../treeSearch'

const object = (name: string, tableKind: TableInfo['tableKind']): TableInfo => ({
  name,
  type: tableKind === 'SUPER_TABLE' ? 'stable' : 'table',
  tableKind,
})

const child = (name: string, stableName: string): TimeSeriesChildTable => ({
  name,
  stableName,
  database: 'dpass_data_single',
  tagValues: [],
})

const script = (id: string, name: string, content: string): SavedScript => ({
  id,
  name,
  content,
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z',
})

describe('workbench tree search', () => {
  it('filters TDengine super tables, basic tables, and loaded child tables by substring', () => {
    const result = filterTdengineTreeObjects(
      [
        object('meters_00007', 'SUPER_TABLE'),
        object('weather', 'SUPER_TABLE'),
        object('device_00007_log', 'BASIC_TABLE'),
        object('unrelated', 'BASIC_TABLE'),
      ],
      {
        meters_00007: [child('meter_unrelated', 'meters_00007')],
        weather: [
          child('station_00007_east', 'weather'),
          child('station_12345_west', 'weather'),
        ],
      },
      '00007',
    )

    expect(result.basicTables.map((table) => table.name)).toEqual(['device_00007_log'])
    expect(result.superTables.map(({ table }) => table.name)).toEqual(['meters_00007', 'weather'])
    expect(result.superTables[0].children).toEqual([])
    expect(result.superTables[1].children.map((table) => table.name)).toEqual(['station_00007_east'])
  })

  it('matches object names case-insensitively', () => {
    expect(filterTreeItemsByName(
      [object('Sensor_ALPHA_Log', 'BASIC_TABLE'), object('sensor_beta_log', 'BASIC_TABLE')],
      'alpha',
    ).map((table) => table.name)).toEqual(['Sensor_ALPHA_Log'])
  })

  it('restores all TDengine objects and loaded children when search is cleared', () => {
    const objects = [
      object('meters', 'SUPER_TABLE'),
      object('device_log', 'BASIC_TABLE'),
    ]
    const children = { meters: [child('meter_1', 'meters'), child('meter_2', 'meters')] }

    const result = filterTdengineTreeObjects(objects, children, '')

    expect(result.superTables.map(({ table }) => table.name)).toEqual(['meters'])
    expect(result.superTables[0].children.map((table) => table.name)).toEqual(['meter_1', 'meter_2'])
    expect(result.basicTables.map((table) => table.name)).toEqual(['device_log'])
  })

  it('filters saved scripts by name or content and hides all on no match', () => {
    const scripts = [
      script('1', 'Daily 00007 report', 'select * from report'),
      script('2', 'Station lookup', 'select * from station_00007_east'),
      script('3', 'Unrelated', 'select 1'),
    ]

    expect(filterSavedScriptsForTree(scripts, '00007').map((item) => item.id)).toEqual(['1', '2'])
    expect(filterSavedScriptsForTree(scripts, 'MISSING')).toEqual([])
    expect(filterSavedScriptsForTree(scripts, '')).toEqual(scripts)
  })
})
