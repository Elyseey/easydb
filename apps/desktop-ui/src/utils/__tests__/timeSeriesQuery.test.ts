import { describe, expect, it } from 'vitest'
import {
  anchorTimeSeriesRange,
  createCustomTimeSeriesRange,
  createDefaultTimeSeriesRange,
  formatOffsetDateTime,
  prepareTimeSeriesPreviewRequest,
  resolveTimeSeriesRange,
} from '../timeSeriesQuery'

describe('time-series query ranges', () => {
  it('defaults to a fixed last-hour range with an explicit local offset', () => {
    const now = new Date(2026, 6, 17, 12, 0, 0, 123)
    const anchored = anchorTimeSeriesRange(createDefaultTimeSeriesRange(), now)

    expect(anchored.startInclusive).toBe(formatOffsetDateTime(new Date(now.getTime() - 60 * 60 * 1000)))
    expect(anchored.endExclusive).toBe(formatOffsetDateTime(now))
    expect(anchored.endExclusive).toMatch(/[+-]\d{2}:\d{2}$/)
  })

  it('keeps fixed bounds for load more and reanchors only when requested', () => {
    const firstNow = new Date(2026, 6, 17, 12, 0, 0)
    const first = resolveTimeSeriesRange({ preset: '15m' }, false, firstNow)
    const loadMore = resolveTimeSeriesRange(first, false, new Date(firstNow.getTime() + 60_000))
    const refreshed = resolveTimeSeriesRange(first, true, new Date(firstNow.getTime() + 60_000))

    expect(loadMore).toEqual(first)
    expect(refreshed.endExclusive).not.toBe(first.endExclusive)
  })

  it('converts custom local input and rejects incomplete or reversed ranges', () => {
    const custom = createCustomTimeSeriesRange(new Date(2026, 6, 17, 12, 0, 0))
    const anchored = anchorTimeSeriesRange(custom)

    expect(anchored.startInclusive).toContain('2026-07-17T11:00:00.000')
    expect(anchored.endExclusive).toContain('2026-07-17T12:00:00.000')
    expect(() => anchorTimeSeriesRange({ preset: 'custom' })).toThrow('开始时间')
    expect(() => anchorTimeSeriesRange({
      preset: 'custom',
      customStartLocal: '2026-07-17T13:00:00',
      customEndLocal: '2026-07-17T12:00:00',
    })).toThrow('开始时间必须早于结束时间')
  })

  it('preserves custom nanosecond boundary text for us and ns databases', () => {
    const anchored = anchorTimeSeriesRange({
      preset: 'custom',
      customStartLocal: '2026-07-17T11:00:00.123456789',
      customEndLocal: '2026-07-17T12:00:00.987654321',
    })

    expect(anchored.startInclusive).toContain('11:00:00.123456789')
    expect(anchored.endExclusive).toContain('12:00:00.987654321')
  })

  it('keeps all-time ranges unbounded', () => {
    expect(anchorTimeSeriesRange({
      preset: 'all',
      startInclusive: 'stale-start',
      endExclusive: 'stale-end',
    })).toEqual({ preset: 'all', applied: true, startInclusive: undefined, endExclusive: undefined })
  })

  it('keeps structured bounds independent while forwarding where sort and offset', () => {
    const first = prepareTimeSeriesPreviewRequest({
      timeRange: { preset: '6h' },
      where: 'voltage > 220',
      orderBy: '`voltage` ASC',
      limit: 1000,
      offset: 0,
    }, false, new Date(2026, 6, 17, 12, 0, 0))
    const next = prepareTimeSeriesPreviewRequest({
      timeRange: first.timeRange,
      where: 'voltage > 220',
      orderBy: '`voltage` ASC',
      limit: 1000,
      offset: 1000,
    }, false, new Date(2026, 6, 17, 13, 0, 0))

    expect(next.request).toMatchObject({
      startInclusive: first.request.startInclusive,
      endExclusive: first.request.endExclusive,
      where: 'voltage > 220',
      orderBy: '`voltage` ASC',
      limit: 1000,
      offset: 1000,
    })
  })
})
