import { describe, expect, it } from 'vitest'
import { tableDetailLoadPlan } from '../tableDetailLoadPlan'

describe('tableDetailLoadPlan', () => {
  it('loads columns together with the data tab', () => {
    expect(tableDetailLoadPlan('data', [])).toEqual({
      loadColumns: true,
      loadTab: true,
    })
  })

  it('does not preload columns for design, tags or DDL', () => {
    expect(tableDetailLoadPlan('design', [])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
    expect(tableDetailLoadPlan('ddl', [])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
    expect(tableDetailLoadPlan('tags', [])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
    expect(tableDetailLoadPlan('structure', [])).toEqual({
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

  it('tracks design and structure as independent shell resources', () => {
    expect(tableDetailLoadPlan('structure', ['structure'])).toEqual({
      loadColumns: false,
      loadTab: false,
    })
    expect(tableDetailLoadPlan('design', ['structure'])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
    expect(tableDetailLoadPlan('structure', ['design'])).toEqual({
      loadColumns: false,
      loadTab: true,
    })
  })
})
