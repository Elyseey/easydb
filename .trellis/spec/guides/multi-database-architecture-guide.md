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
- [ ] Before adding or rejecting dialect-specific SQL, verify the syntax against the matching official documentation under `docs/`; do not infer support only from MySQL or Oracle compatibility.
- [ ] Database integration tests must not embed credentials or real network addresses, print decrypted secrets, contact external databases by default, or swallow failures as successful tests.
- [ ] Real-database tests must use a separate opt-in task/source set and a second authorization boundary for mutations. Follow the executable contract in `backend/database-integration-testing.md`.
- [ ] Preserve identifier text returned by database catalogs/JDBC metadata. Do not uppercase or lowercase an existing schema, table, view, routine, function, trigger, column, or index name before a lookup or quoted reference.
- [ ] Treat metadata catalog sources as independently fallible. A permission error from one source must not discard names already returned by JDBC metadata or another visible-object view.
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
- **Dameng Identifier Identity Rule**: Names returned by JDBC metadata or a Dameng catalog are object identity and must be passed unchanged through table/column/index lookup, DDL retrieval, preview, drop/rename, and routine invocation. DM8 is case-sensitive by default and quoted lowercase, mixed-case, or space-bearing identifiers are distinct. Normalize only a newly entered *unquoted-semantics* name where the product contract explicitly requires it; for example, EasyDB currently trims and uppercases a new ordinary Schema name before quoting it to preserve the existing unquoted-DM creation behavior. Never use `trim().uppercase()` as a generic catalog lookup helper.
- **Dameng Catalog Fallback Rule**: Load Schema sources independently (`DatabaseMetaData.schemas`, current `USER`, `ALL_USERS`, and visible `ALL_OBJECTS` owners) and merge successful results. For routines and triggers, query the public visible-object catalog (`ALL_OBJECTS`) first; only use `SYS.SYSOBJECTS` as a fallback when the visible query fails or returns no rows. This avoids making ordinary metadata browsing depend on SYS privileges while retaining compatibility with installations whose `SYSOBJECTS` synonym may resolve differently under SVI.
- **Dameng/Oracle Comments Retrieval Rule**: Table and column comments are crucial for database understanding. When fetching tables (in `listTables`) and columns (in `getColumns`) for Oracle-like databases such as Dameng, do not hardcode comments to `null` or omit them. Use catalog comment dictionaries on `OWNER`, `TABLE_NAME`, and `COLUMN_NAME`. For Dameng, table comments can come from `ALL_TAB_COMMENTS`, but column comments may be empty in `ALL_COL_COMMENTS` while present in `DBA_COL_COMMENTS`; try `DBA_COL_COMMENTS` first when permitted, and fall back to `ALL_COL_COMMENTS`. An empty result from one comment view is not proof that comments do not exist.
- **Dameng/Oracle Character Length Rule**: For character columns, do not display `DATA_LENGTH` as the design length. `DATA_LENGTH` can be bytes, so `VARCHAR(20 CHAR)` may appear as `DATA_LENGTH = 80`. Use `CHAR_LENGTH` for character types (`CHAR`, `NCHAR`, `VARCHAR`, `VARCHAR2`, `NVARCHAR2`) and reserve `DATA_LENGTH` for binary/byte-oriented types.
- **Dameng Schema Creation Rule**: EasyDB's generic `createDatabase(session, name, charset, collation)` contract means "create the top-level namespace for this database type." For MySQL this is `CREATE DATABASE`; for Dameng it is `CREATE SCHEMA` through `DamengMetadataAdapter`. Do not ask for a password or create a user in the schema creation flow. User creation is a separate admin feature with different fields and privileges. Frontend capabilities must distinguish schema browsing (`schemas`), namespace creation (`schemaCreation`), destructive deletion (`schemaManagement`), and runtime charset/collation alteration (`schemaAlterCharset`). DM8 `CHARSET/UNICODE_FLAG` is fixed at database initialization, so Dameng must keep `schemaAlterCharset = false` while still allowing `CREATE SCHEMA` and `DROP SCHEMA ... CASCADE`.
- **Table Designer Type and Comment Rule**: Table design UIs must not reuse the MySQL type list and default column template for every database. Frontend type options/defaults must be selected by `dbType`, and driver dialects must still defensively normalize incoming types before generating DDL. For Dameng/Oracle-like engines, strip MySQL display lengths from integer types (`BIGINT(20)` → `BIGINT`, `INT(11)` → `INT`), map `VARCHAR` to `VARCHAR2`, map text/json types to `CLOB`, and avoid MySQL `AUTO_INCREMENT` in the UI. A backend dialect should never emit a type string directly from a generic UI without validating/mapping it for the target database. Table/column comments are also dialect-specific: MySQL may inline `COMMENT`, while Dameng/Oracle-like engines must emit separate `COMMENT ON TABLE/COLUMN` statements via `buildCreateTableStatements(...)`; creation routes must execute every statement returned by the dialect, not only the first `CREATE TABLE`.
- **Table Designer ALTER Rule**: Edit-table flows must not generate MySQL ALTER SQL for every database. MySQL supports patterns such as backtick identifiers, `CHANGE COLUMN`, inline column `COMMENT`, and `ALTER TABLE ... ADD/DROP INDEX`; Dameng/Oracle-like engines need double-quoted identifiers, separate `ALTER TABLE ... RENAME COLUMN old TO new`, `ALTER TABLE ... MODIFY ...`, standalone `COMMENT ON TABLE/COLUMN`, and standalone `CREATE/DROP INDEX` statements. Dameng preserves the existing NULL/NOT NULL constraint when `MODIFY <column definition>` omits it, so a nullable-state diff must emit an explicit `ALTER TABLE ... ALTER COLUMN ... SET NULL` or `SET NOT NULL` statement. Keep that constraint change separate from type/default changes to avoid repeating a NULL constraint on an already-nullable column. If a UI generates multiple DDL statements for one save action, the execution layer or caller must execute them as individual statements unless the SQL service explicitly supports multi-statement execution for that driver.
- **Schema Object Mutation Rule**: Generic routes for schema object mutations must delegate to driver contracts such as `MetadataAdapter` instead of building database-specific DDL in the route. This applies to object identity changes (`renameTable`) and destructive operations (`drop`, `truncate`) when the SQL shape or schema context can vary by database. For table rename, MySQL uses `RENAME TABLE db.old TO db.new`, while Dameng/Oracle-like engines use `ALTER TABLE "SCHEMA"."OLD" RENAME TO "NEW"`. The route should only parse/validate the request and call the adapter selected by `DatabaseAdapterRegistry`.
- **Time-Series Lifecycle Mutation Rule**: Time-series schema and child-property mutation routes must accept structured commands only and resolve an optional lifecycle adapter through `DatabaseAdapterRegistry`. Database-specific DDL such as TDengine `ALTER STABLE`, `SET TAG`, `TTL`, and `COMMENT` belongs exclusively in the driver. Preview and apply must rebuild SQL from the same structured command; apply must re-inspect metadata, reject a changed fingerprint or preview token, and execute exactly one atomic DDL statement. Tag NULL intent must be represented separately from the value so an empty string is not collapsed into NULL. Never accept previewed or arbitrary DDL back from the client as an execution request. Driver catalog queries must also quote database-reserved catalog column names (for example TDengine `INS_TABLES`.`ttl`) and expose a non-keyword alias to generic result mapping.
- **Time-Series Destructive Delete Rule**: Time-series object deletion needs its own capability and structured preview/apply contract; do not reuse generic relational `tableDrop`. The driver must inspect the exact catalog-returned object identity and distinguish ordinary tables, child tables, and super tables. A super-table snapshot includes the current child-table count. Apply must require the exact case-sensitive object name, re-inspect the complete snapshot, reject any fingerprint/token change, rebuild `DROP TABLE` or `DROP STABLE` inside the driver, and execute exactly one statement. Client-provided DDL is never an apply input.
- **Time-Series Basic Table and Write Rule**: Ordinary-table structure management and bounded data writes need separate optional capabilities; do not enable relational `TableDesigner` or `rowEdit`. Generic routes/services accept only structured column commands or typed rows and resolve adapters through `DatabaseAdapterRegistry`; TDengine DDL, `INSERT ... USING ... TAGS ... VALUES`, literals, and parameter binding stay in the driver. Apply re-inspects metadata and validates fingerprint/token, never accepts displayed SQL. Keep the primary timestamp unique and immutable, require a concrete non-NULL/non-blank timestamp in every row, cap a batch at 100 rows, and preserve `NULL` separately from empty strings. Use the TDengine 3.0.4-compatible 48 KB conservative row-width limit unless an explicit server-version capability proves a larger limit. Super tables receive data only through existing or newly created children, and large child selection must use server search/paging rather than preloading the catalog.
- **Time-Series CSV Import Rule**: CSV import is a separate capability and background-task contract; it must not raise the manual visual-write 100-row limit. Generic routes accept only file identity, format settings, mappings, target identity, Tags, and preview fingerprints. Streaming CSV parsing, canonical file checks, progress/cancellation, and managed receipts live in launcher orchestration; TDengine SQL, one-time child creation, type validation, and JDBC binding remain in the driver adapter. Do not recursively resubmit a failed multi-row statement unless driver atomicity is proven. Follow `backend/time-series-csv-import.md`.
- **Workbench Data Preview Rule**: Data-preview entry points must use lightweight metadata contracts such as `MetadataAdapter.getColumns(...)` plus `previewRows(...)`. Do not call full `getTableDefinition(...)` for a data preview, because full definitions may load DDL, indexes, comments, and broad catalog scans. DDL and indexes must be lazy-loaded only by DDL/design flows.
- **Workbench Design Metadata Rule**: Design/edit views must avoid heavyweight DDL calls when opening a table, but they must not drop metadata. Use the aggregated `MetadataAdapter.getTableDesign(...)` contract, implemented from lightweight table info + columns + indexes, so the frontend needs one request and table comments, column comments, primary keys, and indexes remain available without invoking DDL. The workbench shell must not preload columns for design or DDL tabs; data preview remains the only automatic columns consumer.
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

The `UPPER(...)` comparisons above are diagnostics for investigating catalog visibility only. Production metadata lookup must compare the exact catalog-returned owner/object name so quoted mixed-case identifiers do not collapse onto a different object.

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
Frontend pages must resolve pair and role support through `utils/databaseTaskPairs.ts`, and the
sidebar plus command palette must share the same navigation capability decision. Follow the
executable contract in `frontend/component-guidelines.md`.

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
