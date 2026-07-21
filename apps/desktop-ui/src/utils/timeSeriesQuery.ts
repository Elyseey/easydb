import type { TimeSeriesQueryRequest } from '@/types'

export type TimeSeriesRangePreset = '15m' | '1h' | '6h' | '24h' | '7d' | 'custom' | 'all'

export interface TimeSeriesRangeState {
  preset: TimeSeriesRangePreset
  applied?: boolean
  customStartLocal?: string
  customEndLocal?: string
  startInclusive?: string
  endExclusive?: string
}

const RELATIVE_RANGE_MILLISECONDS: Partial<Record<TimeSeriesRangePreset, number>> = {
  '15m': 15 * 60 * 1000,
  '1h': 60 * 60 * 1000,
  '6h': 6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
}

const pad = (value: number, width = 2) => String(value).padStart(width, '0')

export function createDefaultTimeSeriesRange(): TimeSeriesRangeState {
  return { preset: '1h', applied: false }
}

export function formatLocalDateTimeInput(value: Date): string {
  return [
    `${pad(value.getFullYear(), 4)}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`,
    `${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`,
  ].join('T')
}

export function formatOffsetDateTime(value: Date): string {
  const offsetMinutes = -value.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absoluteOffset = Math.abs(offsetMinutes)
  const offset = `${sign}${pad(Math.floor(absoluteOffset / 60))}:${pad(absoluteOffset % 60)}`
  return `${formatLocalDateTimeInput(value)}.${pad(value.getMilliseconds(), 3)}${offset}`
}

export function localTimeZoneLabel(now = new Date()): string {
  const offsetMinutes = -now.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absoluteOffset = Math.abs(offsetMinutes)
  return `本机时区 UTC${sign}${pad(Math.floor(absoluteOffset / 60))}:${pad(absoluteOffset % 60)}`
}

export function createCustomTimeSeriesRange(now = new Date()): TimeSeriesRangeState {
  return {
    preset: 'custom',
    applied: false,
    customStartLocal: formatLocalDateTimeInput(new Date(now.getTime() - 60 * 60 * 1000)),
    customEndLocal: formatLocalDateTimeInput(now),
  }
}

function parseLocalDateTime(
  value: string | undefined,
  label: string,
): { offsetText: string; epochNanoseconds: bigint } {
  const match = value?.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/,
  )
  if (!match) throw new Error(`${label}不能为空，且必须是有效的本机日期时间`)
  const [, yearText, monthText, dayText, hourText, minuteText, secondText = '0', fractionText = ''] = match
  const parts = [yearText, monthText, dayText, hourText, minuteText, secondText].map(Number)
  const [year, month, day, hour, minute, second] = parts
  const parsed = new Date(year, month - 1, day, hour, minute, second, 0)
  if (
    parsed.getFullYear() !== year ||
    parsed.getMonth() !== month - 1 ||
    parsed.getDate() !== day ||
    parsed.getHours() !== hour ||
    parsed.getMinutes() !== minute ||
    parsed.getSeconds() !== second
  ) {
    throw new Error(`${label}不是有效的本机日期时间`)
  }
  const offsetMinutes = -parsed.getTimezoneOffset()
  const sign = offsetMinutes >= 0 ? '+' : '-'
  const absoluteOffset = Math.abs(offsetMinutes)
  const offset = `${sign}${pad(Math.floor(absoluteOffset / 60))}:${pad(absoluteOffset % 60)}`
  const fraction = fractionText ? `.${fractionText}` : '.000'
  const nanoseconds = BigInt(fractionText.padEnd(9, '0') || '0')
  return {
    offsetText: `${yearText}-${monthText}-${dayText}T${hourText}:${minuteText}:${secondText}${fraction}${offset}`,
    epochNanoseconds: BigInt(parsed.getTime()) * 1_000_000n + nanoseconds,
  }
}

export function anchorTimeSeriesRange(
  range: TimeSeriesRangeState,
  now = new Date(),
): TimeSeriesRangeState {
  if (range.preset === 'all') {
    return { ...range, applied: true, startInclusive: undefined, endExclusive: undefined }
  }

  if (range.preset === 'custom') {
    const start = parseLocalDateTime(range.customStartLocal, '开始时间')
    const end = parseLocalDateTime(range.customEndLocal, '结束时间')
    if (start.epochNanoseconds >= end.epochNanoseconds) throw new Error('开始时间必须早于结束时间')
    return {
      ...range,
      applied: true,
      startInclusive: start.offsetText,
      endExclusive: end.offsetText,
    }
  }

  const duration = RELATIVE_RANGE_MILLISECONDS[range.preset]
  if (!duration) throw new Error('不支持的时间范围')
  return {
    ...range,
    applied: true,
    startInclusive: formatOffsetDateTime(new Date(now.getTime() - duration)),
    endExclusive: formatOffsetDateTime(now),
  }
}

export function resolveTimeSeriesRange(
  range: TimeSeriesRangeState | undefined,
  reanchor: boolean,
  now = new Date(),
): TimeSeriesRangeState {
  const current = range ?? createDefaultTimeSeriesRange()
  if (
    !reanchor && current.applied &&
    (current.preset === 'all' || (current.startInclusive && current.endExclusive))
  ) {
    return current
  }
  return anchorTimeSeriesRange(current, now)
}

export function prepareTimeSeriesPreviewRequest(
  input: {
    timeRange?: TimeSeriesRangeState
    where?: string
    orderBy?: string
    limit: number
    offset: number
  },
  reanchor: boolean,
  now = new Date(),
): { timeRange: TimeSeriesRangeState; request: TimeSeriesQueryRequest } {
  const timeRange = resolveTimeSeriesRange(input.timeRange, reanchor, now)
  return {
    timeRange,
    request: {
      startInclusive: timeRange.startInclusive,
      endExclusive: timeRange.endExclusive,
      where: input.where,
      orderBy: input.orderBy,
      limit: input.limit,
      offset: input.offset,
    },
  }
}
