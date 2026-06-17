package com.easydb.launcher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MysqlToDamengMigrationAdapterTest {

    @Test
    fun `uses dameng auto increment instead of identity for mysql auto increment columns`() {
        assertEquals("AUTO_INCREMENT", MysqlToDamengMigrationAdapter.DAMENG_AUTO_INCREMENT_CLAUSE)
    }

    @Test
    fun `maps mysql character and large object types to dameng types`() {
        assertEquals("VARCHAR(64 char)", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("varchar(64)"))
        assertEquals("CHAR(8 char)", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("char(8)"))
        assertEquals("CLOB", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("longtext"))
        assertEquals("TEXT", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("text"))
        assertEquals("BLOB", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("mediumblob"))
        assertEquals("CLOB", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("json"))
    }

    @Test
    fun `maps mysql unsigned numeric and temporal types to dameng types`() {
        assertEquals("SMALLINT", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("tinyint(3) unsigned"))
        assertEquals("BIGINT", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("int(10) unsigned"))
        assertEquals("DECIMAL(20,0)", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("bigint(20) unsigned"))
        assertEquals("DECIMAL(12,2)", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("decimal(12,2)"))
        assertEquals("TIMESTAMP", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("datetime"))
        assertEquals("INT", MysqlToDamengMigrationAdapter.mapMysqlTypeToDameng("year"))
    }

    @Test
    fun `normalizes mysql defaults for dameng ddl`() {
        assertEquals("CURRENT_TIMESTAMP", MysqlToDamengMigrationAdapter.normalizeDamengDefault("now()"))
        assertEquals("7", MysqlToDamengMigrationAdapter.normalizeDamengDefault("b'111'"))
        assertEquals("1", MysqlToDamengMigrationAdapter.normalizeDamengDefault("true"))
        assertEquals("'abc'", MysqlToDamengMigrationAdapter.normalizeDamengDefault("abc"))
        assertNull(MysqlToDamengMigrationAdapter.normalizeDamengDefault(null))
        assertNull(MysqlToDamengMigrationAdapter.normalizeDamengDefault("NULL"))
    }
}
