package com.easydb.common

/**
 * Reliable one-shot sync keys must be enforced by a primary/unique constraint
 * and cannot contain nullable columns, otherwise repeated sync can duplicate rows.
 */
object SyncKeyPolicy {
    fun reliableKey(definition: TableDefinition): List<String>? {
        val primaryColumns = definition.columns.filter { it.isPrimaryKey }.map { it.name }
        if (primaryColumns.isNotEmpty()) return primaryColumns

        definition.indexes.firstOrNull { it.isPrimary && it.columns.isNotEmpty() }?.let { return it.columns }

        val columnsByName = definition.columns.associateBy { it.name }
        return definition.indexes.firstOrNull { index ->
            index.isUnique && index.columns.isNotEmpty() && index.columns.all { name ->
                columnsByName[name]?.nullable == false
            }
        }?.columns
    }

    fun hasReliableKey(definition: TableDefinition, columns: List<String>): Boolean {
        val expected = columns.toSet()
        val primary = definition.columns.filter { it.isPrimaryKey }.map { it.name }
        if (primary.size == columns.size && primary.toSet() == expected) return true
        if (definition.indexes.any { index ->
                index.isPrimary && index.columns.size == columns.size && index.columns.toSet() == expected
            }) return true

        val columnsByName = definition.columns.associateBy { it.name }
        return definition.indexes.any { index ->
            index.isUnique && index.columns.size == columns.size && index.columns.toSet() == expected &&
                index.columns.all { name -> columnsByName[name]?.nullable == false }
        }
    }
}
