package com.easydb.drivers.dameng

/** 达梦表/视图 DDL 可附带 COMMENT 语句；程序块必须保持为单个 JDBC 语句。 */
internal object DamengRestoreDdl {
    private val blockTypes = setOf("procedure", "function", "trigger")

    fun statements(ddl: String, objectType: String): List<String> {
        val script = ddl.trim()
        if (script.isEmpty()) return emptyList()
        if (objectType.lowercase() in blockTypes) return listOf(script)

        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inLineComment = false
        var inBlockComment = false
        var qQuoteClose: Char? = null

        while (index < script.length) {
            val ch = script[index]
            val next = script.getOrNull(index + 1)

            if (qQuoteClose != null) {
                current.append(ch)
                if (ch == qQuoteClose && next == '\'') {
                    current.append(next)
                    qQuoteClose = null
                    index += 2
                } else {
                    index++
                }
                continue
            }

            if (inLineComment) {
                current.append(ch)
                if (ch == '\n') inLineComment = false
                index++
                continue
            }
            if (inBlockComment) {
                current.append(ch)
                if (ch == '*' && next == '/') {
                    current.append(next)
                    inBlockComment = false
                    index += 2
                } else {
                    index++
                }
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && ch == '-' && next == '-') {
                current.append(ch).append(next)
                inLineComment = true
                index += 2
                continue
            }
            if (!inSingleQuote && !inDoubleQuote && ch == '/' && next == '*') {
                current.append(ch).append(next)
                inBlockComment = true
                index += 2
                continue
            }

            if (!inSingleQuote && !inDoubleQuote && (ch == 'q' || ch == 'Q') && next == '\'') {
                val opener = script.getOrNull(index + 2)
                if (opener != null) {
                    qQuoteClose = when (opener) {
                        '[' -> ']'
                        '{' -> '}'
                        '(' -> ')'
                        '<' -> '>'
                        else -> opener
                    }
                    current.append(ch).append(next).append(opener)
                    index += 3
                    continue
                }
            }

            if (ch == '\'' && !inDoubleQuote) {
                current.append(ch)
                if (inSingleQuote && next == '\'') {
                    current.append(next)
                    index += 2
                    continue
                }
                inSingleQuote = !inSingleQuote
                index++
                continue
            }
            if (ch == '"' && !inSingleQuote) {
                current.append(ch)
                if (inDoubleQuote && next == '"') {
                    current.append(next)
                    index += 2
                    continue
                }
                inDoubleQuote = !inDoubleQuote
                index++
                continue
            }

            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                current.setLength(0)
            } else {
                current.append(ch)
            }
            index++
        }

        current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
        return statements
    }
}
