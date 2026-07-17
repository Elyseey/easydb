import type {
  TimeSeriesCreateDefinition,
  TimeSeriesCreateKind,
  TimeSeriesCreateResult,
  TimeSeriesDataType,
  TimeSeriesFieldDraft,
  TimeSeriesTagDefinition,
  TimeSeriesTagValueDraft,
} from '@/types'

export type TimeSeriesFieldRow = TimeSeriesFieldDraft & { id: string }
export const TIME_SERIES_STRING_TYPES = new Set<TimeSeriesDataType>(['BINARY', 'VARCHAR', 'NCHAR'])

const utf8Length = (value: string) => new TextEncoder().encode(value).length
const hasInvalidIdentifierCharacter = (value: string) => [...value].some((character) => {
  const code = character.charCodeAt(0)
  return code < 32 || code === 127
})

export function validateTimeSeriesDefinition(definition: TimeSeriesCreateDefinition): string[] {
  const errors: string[] = []
  const validateName = (value: string, label: string, maxBytes: number) => {
    if (!value.trim()) errors.push(`${label}不能为空`)
    else if (value !== value.trim()) errors.push(`${label}首尾不能有空格`)
    else if (value.includes('.') || hasInvalidIdentifierCharacter(value)) errors.push(`${label}包含非法字符`)
    else if (utf8Length(value) > maxBytes) errors.push(`${label}不能超过 ${maxBytes} 字节`)
  }
  validateName(definition.name, '对象名', 192)

  if (definition.kind !== 'CHILD_TABLE') {
    if (definition.columns.length < 2) errors.push('至少需要时间戳字段和一个数据字段')
    if (definition.columns[0]?.type !== 'TIMESTAMP') errors.push('第一个字段必须是 TIMESTAMP')
    definition.columns.forEach((field) => validateName(field.name, '字段名', 64))
    if (definition.kind === 'SUPER_TABLE') {
      if (definition.tags.length === 0) errors.push('超级表至少需要一个 Tag')
      definition.tags.forEach((field) => validateName(field.name, 'Tag 名', 64))
    }
    const allFields = [...definition.columns, ...definition.tags]
    const duplicates = allFields.filter((field, index) => allFields.findIndex((item) => item.name === field.name) !== index)
    if (duplicates.length > 0) errors.push(`字段名与 Tag 名不能重复：${[...new Set(duplicates.map((item) => item.name))].join('、')}`)
    definition.columns.forEach((field) => {
      if (TIME_SERIES_STRING_TYPES.has(field.type) && (!field.length || field.length <= 0)) {
        errors.push(`${field.name || '字符串字段'}必须设置类型长度`)
      } else if (field.type === 'NCHAR' && (field.length ?? 0) > 16_379) {
        errors.push(`${field.name} 的 NCHAR 长度不能超过 16379`)
      } else if ((field.type === 'BINARY' || field.type === 'VARCHAR') && (field.length ?? 0) > 65_517) {
        errors.push(`${field.name} 的字符串长度不能超过 65517`)
      }
    })
    definition.tags.forEach((field) => {
      if (TIME_SERIES_STRING_TYPES.has(field.type) && (!field.length || field.length <= 0)) {
        errors.push(`${field.name || '字符串 Tag'}必须设置类型长度`)
      } else if (field.type === 'NCHAR' && (field.length ?? 0) > 4_095) {
        errors.push(`${field.name} 的 NCHAR Tag 长度不能超过 4095`)
      } else if ((field.type === 'BINARY' || field.type === 'VARCHAR') && (field.length ?? 0) > 16_382) {
        errors.push(`${field.name} 的字符串 Tag 长度不能超过 16382`)
      }
    })
  } else {
    if (!definition.stableName) errors.push('请选择当前数据库中的父超级表')
    definition.tagValues.forEach((tag) => {
      validateName(tag.name, 'Tag 名', 64)
      if (!tag.isNull && tag.value == null) errors.push(`${tag.name} 请输入值或选择 NULL`)
    })
  }
  if (definition.comment && utf8Length(definition.comment) > 1024) errors.push('COMMENT 不能超过 1024 字节')
  return [...new Set(errors)]
}

export function validateTimeSeriesTagValues(
  definitions: TimeSeriesTagDefinition[],
  values: TimeSeriesTagValueDraft[],
): string[] {
  const errors: string[] = []
  const byName = new Map(values.map((value) => [value.name, value]))
  const integerRanges: Record<string, [bigint, bigint]> = {
    TINYINT: [-128n, 127n], 'TINYINT UNSIGNED': [0n, 255n],
    SMALLINT: [-32768n, 32767n], 'SMALLINT UNSIGNED': [0n, 65535n],
    INT: [-2147483648n, 2147483647n], 'INT UNSIGNED': [0n, 4294967295n],
    BIGINT: [-9223372036854775808n, 9223372036854775807n],
    'BIGINT UNSIGNED': [0n, 18446744073709551615n],
  }
  definitions.forEach((definition) => {
    const draft = byName.get(definition.name)
    if (!draft || draft.isNull) return
    const raw = draft.value ?? ''
    const type = definition.type.trim().toUpperCase().replace(/\s+/g, ' ')
    const stringMatch = type.match(/^(?:BINARY|VARCHAR|NCHAR)\((\d+)\)$/)
    if (stringMatch) {
      const limit = Number(stringMatch[1])
      const actual = type.startsWith('NCHAR') ? [...raw].length : utf8Length(raw)
      if (actual > limit) errors.push(`${definition.name} 超过声明长度 ${limit}`)
      return
    }
    if (integerRanges[type]) {
      try {
        const normalized = raw.trim()
        if (!/^[+-]?\d+$/.test(normalized)) throw new Error('invalid')
        const number = BigInt(normalized)
        const [min, max] = integerRanges[type]
        if (number < min || number > max) errors.push(`${definition.name} 超出 ${type} 范围`)
      } catch {
        errors.push(`${definition.name} 必须是有效整数`)
      }
    } else if (type === 'BOOL' && !/^(true|false)$/i.test(raw.trim())) {
      errors.push(`${definition.name} 必须是 true 或 false`)
    } else if ((type === 'FLOAT' || type === 'DOUBLE') && (raw.trim() === '' || !Number.isFinite(Number(raw)))) {
      errors.push(`${definition.name} 必须是有效数字`)
    } else if (type === 'TIMESTAMP' && !/^(?:[+-]?\d+|\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?)$/.test(raw.trim())) {
      errors.push(`${definition.name} 必须是时间戳整数或完整日期时间`)
    }
  })
  return errors
}

export function buildTimeSeriesCreateDefinition(input: {
  kind: TimeSeriesCreateKind
  name: string
  columns: TimeSeriesFieldRow[]
  tags: TimeSeriesFieldRow[]
  stableName?: string
  tagValues: TimeSeriesTagValueDraft[]
  comment: string
}): TimeSeriesCreateDefinition {
  const fields = (rows: TimeSeriesFieldRow[]): TimeSeriesFieldDraft[] => rows.map((row) => ({
    name: row.name,
    type: row.type,
    ...(row.length == null ? {} : { length: row.length }),
  }))
  if (input.kind === 'CHILD_TABLE') {
    return {
      kind: input.kind,
      name: input.name,
      columns: [],
      tags: [],
      stableName: input.stableName || null,
      tagValues: input.tagValues,
      comment: null,
    }
  }
  return {
    kind: input.kind,
    name: input.name,
    columns: fields(input.columns),
    tags: input.kind === 'SUPER_TABLE' ? fields(input.tags) : [],
    stableName: null,
    tagValues: [],
    comment: input.comment || null,
  }
}

export type TimeSeriesRefreshTarget =
  | { kind: 'objects' }
  | { kind: 'children'; stableName: string }

export function timeSeriesRefreshTarget(result: TimeSeriesCreateResult): TimeSeriesRefreshTarget {
  if (result.kind === 'CHILD_TABLE' && result.stableName) {
    return { kind: 'children', stableName: result.stableName }
  }
  return { kind: 'objects' }
}
