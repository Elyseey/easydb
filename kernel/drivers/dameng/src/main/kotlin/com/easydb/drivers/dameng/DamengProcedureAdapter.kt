package com.easydb.drivers.dameng

import com.easydb.common.*

/**
 * 达梦 DM8 专属存储过程/函数适配器。
 * DM8 兼容 Oracle 语法：参数查询用 ALL_ARGUMENTS，函数调用需 FROM dual，
 * 切换 schema 用 ALTER SESSION SET CURRENT_SCHEMA。
 */
class DamengProcedureAdapter : ProcedureAdapter {

    private val dialect = DamengDialectAdapter()

    override fun inspect(
        session: DatabaseSession,
        database: String,
        name: String,
        type: String
    ): ProcedureInspectResult {
        val conn = session.getJdbcConnection()
        val schema = database.trim().uppercase()
        val objectName = name.trim().uppercase()

        // 1. 参数列表：通过 ALL_ARGUMENTS（DM8 兼容 Oracle）
        val params = mutableListOf<ProcedureParam>()
        try {
            conn.prepareStatement(
                """
                SELECT ARGUMENT_NAME, POSITION, DATA_TYPE,
                       IN_OUT, DATA_LENGTH, DATA_PRECISION, DATA_SCALE
                FROM ALL_ARGUMENTS
                WHERE OWNER = ?
                  AND OBJECT_NAME = ?
                ORDER BY POSITION
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, schema)
                stmt.setString(2, objectName)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        params.add(
                            ProcedureParam(
                                ordinalPosition = rs.getInt("POSITION"),
                                name = rs.getString("ARGUMENT_NAME") ?: "(return)",
                                mode = rs.getString("IN_OUT") ?: "IN",
                                dataType = rs.getString("DATA_TYPE"),
                                characterMaxLength = rs.getLong("DATA_LENGTH").takeIf { !rs.wasNull() },
                                numericPrecision = rs.getInt("DATA_PRECISION").takeIf { !rs.wasNull() },
                                numericScale = rs.getInt("DATA_SCALE").takeIf { !rs.wasNull() },
                                dtdIdentifier = null
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // 回退到 SYSCOLUMNS 查询
            tryFallbackParamQuery(conn, schema, objectName, params)
        }

        // 2. DDL：通过 DBMS_METADATA.GET_DDL
        val ddl = try {
            conn.prepareStatement(
                "SELECT DBMS_METADATA.GET_DDL(?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, if (type == "PROCEDURE") "PROCEDURE" else "FUNCTION")
                stmt.setString(2, objectName)
                stmt.setString(3, schema)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        } catch (_: Exception) { null }

        // 3. definer
        val definer = try {
            conn.prepareStatement(
                """
                SELECT OWNER
                FROM ALL_PROCEDURES
                WHERE OWNER = ? AND OBJECT_NAME = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, schema)
                stmt.setString(2, objectName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("OWNER") else null
                }
            }
        } catch (_: Exception) { null }

        return ProcedureInspectResult(
            name = name,
            type = type,
            database = database,
            definer = definer,
            comment = null,
            params = params,
            ddl = ddl
        )
    }

    private fun tryFallbackParamQuery(
        conn: java.sql.Connection,
        schema: String,
        objectName: String,
        params: MutableList<ProcedureParam>
    ) {
        try {
            conn.prepareStatement(
                """
                SELECT C.NAME AS ARG_NAME, C.TYPE$ AS DATA_TYPE,
                       C.LENGTH$, C.SCALE, C.NULLABLE$, C.DEFVAL
                FROM SYS.SYSOBJECTS O
                JOIN SYS.SYSCOLUMNS C ON C.ID = O.ID
                WHERE O.NAME = ?
                  AND O.SCHID = (SELECT ID FROM SYS.SYSOBJECTS WHERE NAME = ? AND TYPE$ = 'SCH')
                ORDER BY C.COLID
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, objectName)
                stmt.setString(2, schema)
                stmt.executeQuery().use { rs ->
                    var pos = 1
                    while (rs.next()) {
                        params.add(
                            ProcedureParam(
                                ordinalPosition = pos++,
                                name = rs.getString("ARG_NAME"),
                                mode = "IN",
                                dataType = rs.getString("DATA_TYPE"),
                                characterMaxLength = rs.getLong("LENGTH$").takeIf { !rs.wasNull() },
                                numericPrecision = null,
                                numericScale = rs.getInt("SCALE").takeIf { !rs.wasNull() },
                                dtdIdentifier = null
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // 参数查询失败不影响整体流程
        }
    }

    /**
     * DM CALL 语句：CALL "SCHEMA"."PROC"(?, ?, ?)
     */
    override fun buildCallSql(database: String, name: String, paramCount: Int): String {
        val schema = database.trim().uppercase()
        val procName = name.trim().uppercase()
        val placeholders = (1..paramCount).joinToString(", ") { "?" }
        return "CALL ${dialect.quoteIdentifier(schema)}.${dialect.quoteIdentifier(procName)}($placeholders)"
    }

    /**
     * DM 函数调用：SELECT "SCHEMA"."FUNC"(?, ?) AS result FROM dual
     */
    override fun buildFunctionCallSql(database: String, name: String, paramCount: Int): String {
        val schema = database.trim().uppercase()
        val funcName = name.trim().uppercase()
        val placeholders = (1..paramCount).joinToString(", ") { "?" }
        return "SELECT ${dialect.quoteIdentifier(schema)}.${dialect.quoteIdentifier(funcName)}($placeholders) AS result FROM dual"
    }

    /**
     * DM 切换 schema：ALTER SESSION SET CURRENT_SCHEMA = "schema"
     */
    override fun buildSwitchDatabaseSql(database: String): String {
        return dialect.buildSwitchDatabaseSql(database) ?: ""
    }
}
