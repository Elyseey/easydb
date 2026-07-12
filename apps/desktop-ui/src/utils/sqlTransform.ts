import type { DbType } from '@/types'
import type { SqlLanguage } from 'sql-formatter'

const FORMATTER_DIALECTS: Record<DbType, SqlLanguage> = {
  mysql: 'mysql',
  dameng: 'plsql',
  postgresql: 'postgresql',
  oracle: 'plsql',
  sqlserver: 'transactsql',
  sqlite: 'sqlite',
}

export class SqlTransformError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SqlTransformError'
  }
}

export function sqlFormatterDialectFor(dbType: DbType): SqlLanguage {
  return FORMATTER_DIALECTS[dbType]
}

export async function beautifySql(sql: string, dbType: DbType): Promise<string> {
  const source = sql.trim()
  if (!source) return ''

  try {
    const { format } = await import('sql-formatter')
    return format(source, {
      language: sqlFormatterDialectFor(dbType),
      keywordCase: 'upper',
      tabWidth: 2,
      useTabs: false,
      logicalOperatorNewline: 'before',
      linesBetweenQueries: 1,
    })
  } catch (error) {
    const reason = error instanceof Error ? error.message : '无法解析 SQL'
    throw new SqlTransformError(`SQL 美化失败：${reason}`)
  }
}

function isWhitespace(char: string): boolean {
  return /\s/.test(char)
}

function closingDelimiter(opening: string): string {
  switch (opening) {
    case '[': return ']'
    case '{': return '}'
    case '(': return ')'
    case '<': return '>'
    default: return opening
  }
}

function readQuoted(sql: string, start: number, quote: "'" | '"' | '`'): number {
  let index = start + 1

  while (index < sql.length) {
    if (sql[index] === '\\') {
      index += Math.min(2, sql.length - index)
      continue
    }
    if (sql[index] === quote) {
      if (sql[index + 1] === quote) {
        index += 2
        continue
      }
      return index + 1
    }
    index++
  }

  throw new SqlTransformError(`SQL 压缩失败：存在未闭合的 ${quote} 引用`)
}

function readBracketIdentifier(sql: string, start: number): number {
  let index = start + 1

  while (index < sql.length) {
    if (sql[index] === ']') {
      if (sql[index + 1] === ']') {
        index += 2
        continue
      }
      return index + 1
    }
    index++
  }

  throw new SqlTransformError('SQL 压缩失败：存在未闭合的方括号标识符')
}

function readBlockComment(sql: string, start: number): number {
  let depth = 1
  let index = start + 2

  while (index < sql.length) {
    if (sql.startsWith('/*', index)) {
      depth++
      index += 2
      continue
    }
    if (sql.startsWith('*/', index)) {
      depth--
      index += 2
      if (depth === 0) return index
      continue
    }
    index++
  }

  throw new SqlTransformError('SQL 压缩失败：存在未闭合的块注释')
}

function dollarQuoteAt(sql: string, start: number): string | null {
  return sql.slice(start).match(/^(?:\$\$|\$[A-Za-z_][A-Za-z0-9_]*\$)/)?.[0] ?? null
}

function readDollarQuoted(sql: string, start: number, delimiter: string): number {
  const end = sql.indexOf(delimiter, start + delimiter.length)
  if (end >= 0) return end + delimiter.length
  throw new SqlTransformError(`SQL 压缩失败：存在未闭合的 ${delimiter} 文本块`)
}

function readOracleQuoted(sql: string, start: number): number | null {
  if ((sql[start] !== 'q' && sql[start] !== 'Q') || sql[start + 1] !== "'" || !sql[start + 2]) {
    return null
  }

  const terminator = `${closingDelimiter(sql[start + 2])}'`
  const end = sql.indexOf(terminator, start + 3)
  if (end >= 0) return end + terminator.length
  throw new SqlTransformError("SQL 压缩失败：存在未闭合的 Oracle q 引用")
}

/**
 * Conservatively collapses whitespace outside protected SQL lexical regions.
 * It intentionally keeps a single separator instead of removing spaces around
 * punctuation, because token-boundary rules vary between database dialects.
 */
export function compactSql(sql: string, dbType: DbType): string {
  let output = ''
  let index = 0
  let whitespacePending = false

  const appendPendingWhitespace = () => {
    if (whitespacePending && output && !output.endsWith('\n') && !output.endsWith('\r')) {
      output += ' '
    }
    whitespacePending = false
  }

  while (index < sql.length) {
    const char = sql[index]

    if (isWhitespace(char)) {
      whitespacePending = true
      index++
      continue
    }

    appendPendingWhitespace()

    const oracleQuoteEnd = dbType === 'oracle' || dbType === 'dameng'
      ? readOracleQuoted(sql, index)
      : null
    if (oracleQuoteEnd !== null) {
      output += sql.slice(index, oracleQuoteEnd)
      index = oracleQuoteEnd
      continue
    }

    if (
      char === "'" ||
      char === '"' ||
      (char === '`' && (dbType === 'mysql' || dbType === 'sqlite'))
    ) {
      const end = readQuoted(sql, index, char)
      output += sql.slice(index, end)
      index = end
      continue
    }

    if (char === '[' && (dbType === 'sqlserver' || dbType === 'sqlite')) {
      const end = readBracketIdentifier(sql, index)
      output += sql.slice(index, end)
      index = end
      continue
    }

    const dollarDelimiter = char === '$' && dbType === 'postgresql' ? dollarQuoteAt(sql, index) : null
    if (dollarDelimiter) {
      const end = readDollarQuoted(sql, index, dollarDelimiter)
      output += sql.slice(index, end)
      index = end
      continue
    }

    if (sql.startsWith('/*', index)) {
      const end = readBlockComment(sql, index)
      output += sql.slice(index, end)
      index = end
      continue
    }

    if (sql.startsWith('--', index) || (char === '#' && dbType === 'mysql')) {
      const lineEnd = sql.slice(index).search(/[\r\n]/)
      if (lineEnd < 0) {
        output += sql.slice(index)
        index = sql.length
        continue
      }

      const commentEnd = index + lineEnd
      output += sql.slice(index, commentEnd)
      if (sql.startsWith('\r\n', commentEnd)) {
        output += '\r\n'
        index = commentEnd + 2
      } else {
        output += sql[commentEnd]
        index = commentEnd + 1
      }
      whitespacePending = false
      continue
    }

    output += char
    index++
  }

  return output.trim()
}
