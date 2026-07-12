# Multi-Database Architecture Guide

> Purpose: Prevent MySQL-specific assumptions from leaking into generic EasyDB features.

Use this guide before modifying any database-related code, including connection, metadata, SQL execution, workbench, migration, sync, compare, export, backup, tracker, slow-query, and settings.

## Trigger Checklist

Read this guide when any change:

- [ ] Touches `ConnectionConfig.dbType` or `DbType`
- [ ] Adds a new database driver
- [ ] Reads metadata, DDL, table lists, columns, indexes, routines, or triggers
- [ ] Executes SQL on behalf of the user
- [ ] Formats, compacts, tokenizes, or rewrites SQL text in the frontend
- [ ] Generates SQL snippets or templates in the frontend
- [ ] Uses migration, sync, compare, export, backup, restore, tracker, or slow-query
- [ ] Adds frontend UI for a database feature

## Non-Negotiable Rules

- [ ] Generic routes must not call `ServiceRegistry.mysqlAdapter`
- [ ] Generic services must not cast to `MysqlDatabaseSession`
- [ ] Generic services must not use MySQL SQL such as `USE \`db\``, `SHOW CREATE`, `INFORMATION_SCHEMA`, or `performance_schema`
- [ ] Single-connection features must resolve adapters through `DatabaseAdapterRegistry`
- [ ] Pair features must resolve adapters by `(sourceDbType, targetDbType)`
- [ ] Frontend feature entry points must check database capabilities before showing actions
- [ ] MySQL-only features must be explicitly guarded and return unsupported for other database types
- [ ] Client-side SQL text transforms must select lexical/formatting rules by `dbType`; for example, MySQL `#` comments must not consume PostgreSQL `#>` / `#>>` JSON operators. Follow the executable contract in `frontend/component-guidelines.md`.
- [ ] Client-side SQL templates must be filtered by `dbType`; generic templates must not hardcode database-specific quoting or syntax. Follow the executable contract in `frontend/component-guidelines.md`.

## Correct Routing Pattern

```kotlin
val session = getSessionOrFail(call, connMgr) ?: return@get
val adapter = ServiceRegistry.adapterRegistry.get(session.config.dbType)
call.ok(adapter.metadataAdapter().listDatabases(session))
```

## Wrong Routing Pattern

```kotlin
val adapter = ServiceRegistry.mysqlAdapter
call.ok(adapter.metadataAdapter().listDatabases(session))
```

This is wrong in generic routes because a Dameng/PostgreSQL/Oracle connection will execute MySQL metadata SQL.

## Dialect Boundary

The following belong in a dialect/driver adapter, not in generic services:

- Identifier quoting
- Schema/database switching:
  - **Schema Switching Rule**: When running queries in the SQL editor or query session, the active database/schema context must be explicitly set before executing the query. Different databases have different switching mechanisms:
    - MySQL: `USE \`database_name\``
    - PostgreSQL: `SET search_path TO "schema_name"`
    - Dameng / Oracle: `ALTER SESSION SET CURRENT_SCHEMA = "schema_name"`
  - Never hardcode the switching SQL in generic execution paths (like `SqlExecutionService.kt`). Always resolve it via the dialect's `buildSwitchDatabaseSql(database)` method.
- Pagination
- DDL generation
- UPSERT/MERGE
- EXPLAIN
- System catalog queries

## Metadata Object Model

Different databases expose different top-level object models. Do not force every driver into the MySQL mental model of `database -> tables`.

- MySQL: EasyDB `database` maps to a database/schema selected by `USE`.
- Dameng/Oracle-like engines: EasyDB `database` currently maps to a schema/user for the generic workbench tree.
- A driver must query its own system catalog inside its driver module and return normalized EasyDB metadata objects.
- Generic routes must pass through the adapter result; they must not add MySQL-specific filters or catalog SQL.
- Do not hide system schemas, users, or system-named objects by default. If a product needs simplified display, add an explicit user-controlled filter/capability instead of silently filtering in the driver.
- Align visibility with the target database's native tool model when compatibility is the goal. For Dameng, SQLark shows schemas/users and also shows `##...` tables under the schema table group, so Dameng metadata must not filter those objects out.
- **Dameng/Oracle Schema Listing Rule**: When retrieving databases/schemas for Dameng, DO NOT use `ALL_USERS` or `DBA_USERS` alone. In production, `ALL_USERS` can return only a small subset while visible objects exist under many schemas. Build the schema list from `conn.metaData.schemas`, the current user, `ALL_USERS`, and visible object owners such as `SELECT DISTINCT OWNER FROM ALL_OBJECTS WHERE OBJECT_TYPE IN ('TABLE', 'VIEW')`. De-duplicate and sort after merging.
- **Dameng/Oracle Comments Retrieval Rule**: Table and column comments are crucial for database understanding. When fetching tables (in `listTables`) and columns (in `getColumns`) for Oracle-like databases such as Dameng, do not hardcode comments to `null` or omit them. Use catalog comment dictionaries on `OWNER`, `TABLE_NAME`, and `COLUMN_NAME`. For Dameng, table comments can come from `ALL_TAB_COMMENTS`, but column comments may be empty in `ALL_COL_COMMENTS` while present in `DBA_COL_COMMENTS`; try `DBA_COL_COMMENTS` first when permitted, and fall back to `ALL_COL_COMMENTS`. An empty result from one comment view is not proof that comments do not exist.
- **Dameng/Oracle Character Length Rule**: For character columns, do not display `DATA_LENGTH` as the design length. `DATA_LENGTH` can be bytes, so `VARCHAR(20 CHAR)` may appear as `DATA_LENGTH = 80`. Use `CHAR_LENGTH` for character types (`CHAR`, `NCHAR`, `VARCHAR`, `VARCHAR2`, `NVARCHAR2`) and reserve `DATA_LENGTH` for binary/byte-oriented types.
- **Dameng Schema Creation Rule**: EasyDB's generic `createDatabase(session, name, charset, collation)` contract means "create the top-level namespace for this database type." For MySQL this is `CREATE DATABASE`; for Dameng it is `CREATE SCHEMA` through `DamengMetadataAdapter`. Do not ask for a password or create a user in the schema creation flow. User creation is a separate admin feature with different fields and privileges. Frontend capabilities must distinguish schema browsing (`schemas`), namespace creation (`schemaCreation`), and destructive/management actions such as edit/drop (`schemaManagement`).
- **Table Designer Type and Comment Rule**: Table design UIs must not reuse the MySQL type list and default column template for every database. Frontend type options/defaults must be selected by `dbType`, and driver dialects must still defensively normalize incoming types before generating DDL. For Dameng/Oracle-like engines, strip MySQL display lengths from integer types (`BIGINT(20)` → `BIGINT`, `INT(11)` → `INT`), map `VARCHAR` to `VARCHAR2`, map text/json types to `CLOB`, and avoid MySQL `AUTO_INCREMENT` in the UI. A backend dialect should never emit a type string directly from a generic UI without validating/mapping it for the target database. Table/column comments are also dialect-specific: MySQL may inline `COMMENT`, while Dameng/Oracle-like engines must emit separate `COMMENT ON TABLE/COLUMN` statements via `buildCreateTableStatements(...)`; creation routes must execute every statement returned by the dialect, not only the first `CREATE TABLE`.
- **Table Designer ALTER Rule**: Edit-table flows must not generate MySQL ALTER SQL for every database. MySQL supports patterns such as backtick identifiers, `CHANGE COLUMN`, inline column `COMMENT`, and `ALTER TABLE ... ADD/DROP INDEX`; Dameng/Oracle-like engines need double-quoted identifiers, separate `ALTER TABLE ... RENAME COLUMN old TO new`, `ALTER TABLE ... MODIFY ...`, standalone `COMMENT ON TABLE/COLUMN`, and standalone `CREATE/DROP INDEX` statements. If a UI generates multiple DDL statements for one save action, the execution layer or caller must execute them as individual statements unless the SQL service explicitly supports multi-statement execution for that driver.
- **Schema Object Mutation Rule**: Generic routes for schema object mutations must delegate to driver contracts such as `MetadataAdapter` instead of building database-specific DDL in the route. This applies to object identity changes (`renameTable`) and destructive operations (`drop`, `truncate`) when the SQL shape or schema context can vary by database. For table rename, MySQL uses `RENAME TABLE db.old TO db.new`, while Dameng/Oracle-like engines use `ALTER TABLE "SCHEMA"."OLD" RENAME TO "NEW"`. The route should only parse/validate the request and call the adapter selected by `DatabaseAdapterRegistry`.
- **Workbench Data Preview Rule**: Data-preview entry points must use lightweight metadata contracts such as `MetadataAdapter.getColumns(...)` plus `previewRows(...)`. Do not call full `getTableDefinition(...)` for a data preview, because full definitions may load DDL, indexes, comments, and broad catalog scans. DDL and indexes must be lazy-loaded only by DDL/design flows.
- **Workbench Design Metadata Rule**: Design/edit views must avoid heavyweight DDL calls when opening a table, but they must not drop metadata. Use lightweight contracts like `getTableInfo(...) + getColumns(...) + getIndexes(...)` so table comments, column comments, primary keys, and indexes are all available without invoking DDL.
- Metadata lists should defensively de-duplicate by stable identity such as `(objectType, schema, objectName)` before returning to UI/API layers.

## Dameng Metadata Verification Checklist

Before declaring a Dameng metadata field "missing", verify all relevant catalog paths and compare against a native tool:

```sql
-- Schema owners visible through objects can exceed ALL_USERS.
SELECT USERNAME FROM ALL_USERS ORDER BY USERNAME;
SELECT DISTINCT OWNER FROM ALL_OBJECTS WHERE OBJECT_TYPE IN ('TABLE', 'VIEW') ORDER BY OWNER;

-- Table comments.
SELECT OWNER, TABLE_NAME, COMMENTS
FROM ALL_TAB_COMMENTS
WHERE OWNER = ? AND UPPER(TABLE_NAME) = UPPER(?);

-- Column comments: DBA_COL_COMMENTS may contain data while ALL_COL_COMMENTS is empty.
SELECT 'ALL_COL_COMMENTS' AS SRC, COUNT(*) AS CNT
FROM ALL_COL_COMMENTS
WHERE OWNER = ? AND UPPER(TABLE_NAME) = UPPER(?) AND COMMENTS IS NOT NULL
UNION ALL
SELECT 'DBA_COL_COMMENTS', COUNT(*)
FROM DBA_COL_COMMENTS
WHERE OWNER = ? AND UPPER(TABLE_NAME) = UPPER(?) AND COMMENTS IS NOT NULL;

-- Character length: compare DATA_LENGTH and CHAR_LENGTH.
SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, CHAR_LENGTH, CHAR_USED
FROM ALL_TAB_COLUMNS
WHERE OWNER = ? AND UPPER(TABLE_NAME) = UPPER(?)
ORDER BY COLUMN_ID;
```

When local verification uses the launcher jar, rebuild the runtime artifact with `./gradlew :launcher:shadowJar` and restart the `18080` process. `compileKotlin` alone does not update the already-running shadow jar.

## Pair-Feature Boundary

Migration, sync, and compare are pair features:

```text
source db type + target db type -> adapter
```

Do not model these as source-only or target-only features.

Frontend capability flags for pair features are only coarse entry controls. For example,
`tasks.migration = true` means the database type can participate in at least one supported
migration pair, not that it can be used as both source and target. The feature page and backend
route must still validate the exact `(sourceDbType, targetDbType)` pair before preview/start.

## Pre-Commit Search

```bash
rg "ServiceRegistry\\.mysqlAdapter|MysqlDatabaseSession|MysqlConnectionAdapter" kernel
rg "INFORMATION_SCHEMA|information_schema|SHOW CREATE|performance_schema|USE `" kernel
rg "mysql|MySQL|MYSQL" apps/desktop-ui/src
```

For each hit:

- If it is inside a MySQL-specific module, the hit is expected.
- If it is inside a generic module, refactor to registry, adapter, dialect, or capabilities.

## Good/Base/Bad Cases

- Good: Dameng connection enters metadata route and gets Dameng adapter.
- Base: MySQL connection keeps existing behavior through MySQL adapter.
- Bad: Dameng connection executes `SELECT ... FROM INFORMATION_SCHEMA...`.
