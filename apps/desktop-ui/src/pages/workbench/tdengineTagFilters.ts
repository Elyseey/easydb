import type { TimeSeriesTagDefinition, TimeSeriesTagFilterOperator } from '@/types'

export const TAG_FILTER_OPERATOR_LABELS: Record<TimeSeriesTagFilterOperator, string> = {
  EQ: '等于',
  NE: '不等于',
  GT: '大于',
  GTE: '大于等于',
  LT: '小于',
  LTE: '小于等于',
  CONTAINS: '包含',
  IS_NULL: '为空',
  IS_NOT_NULL: '不为空',
}

const NULL_OPERATORS: TimeSeriesTagFilterOperator[] = ['IS_NULL', 'IS_NOT_NULL']

export function tagFilterOperators(type: string): TimeSeriesTagFilterOperator[] {
  const baseType = type.trim().toUpperCase().split('(')[0].replace(/\s+/g, ' ')
  if (['BINARY', 'VARCHAR', 'NCHAR'].includes(baseType)) {
    return ['EQ', 'NE', 'CONTAINS', ...NULL_OPERATORS]
  }
  if (['BOOL', 'BOOLEAN'].includes(baseType)) {
    return ['EQ', 'NE', ...NULL_OPERATORS]
  }
  return ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', ...NULL_OPERATORS]
}

export function defaultTagFilterOperator(
  definition: TimeSeriesTagDefinition | undefined,
): TimeSeriesTagFilterOperator {
  return definition ? tagFilterOperators(definition.type)[0] : 'EQ'
}

export function tagFilterNeedsValue(operator: TimeSeriesTagFilterOperator): boolean {
  return operator !== 'IS_NULL' && operator !== 'IS_NOT_NULL'
}
