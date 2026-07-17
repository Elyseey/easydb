import { describe, expect, it } from 'vitest'
import { tableDetailLoadPlan } from '../tableDetailLoadPlan'

describe('tableDetailLoadPlan', () => {
  it('loads columns together with the data tab', () => {
    expect(tableDetailLoadPlan('data', [])).toEqual({
      loadColumns: true,
      loadTab: true,
    })
  })

  it('does not preload columns for design or DDL', () => {
    expect(tableDetailLoadPlan('design', [])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
    expect(tableDetailLoadPlan('ddl', [])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
  })

  it('loads columns explicitly after a design save and skips cached resources', () => {
    expect(tableDetailLoadPlan('columns', [])).toEqual({
      loadColumns: true,
      loadTab: false,
    })
    expect(tableDetailLoadPlan('data', ['columns', 'data'])).toEqual({
      loadColumns: false,
      loadTab: false,
    })
  })
})
